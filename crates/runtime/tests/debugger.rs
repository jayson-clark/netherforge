//! The debugger, end to end over DAP (Kotlin's `DebuggerTest`): a thread plays the editor, speaking DAP to the
//! debugger as the bridge's thread hands it messages, while the test's own thread runs the server's ticks and is held
//! at each stop: breakpoints and the stack, scopes and variables (tables, upvalues, globals, a centity handle),
//! stepping in, out and over, a task stepping over its wait, pause, break on errors, the time limit leaving paused
//! time out, an editor leaving while paused, breakpoints across a full reload and in bodies, and a file named by its
//! place on disk.

mod support;
use support::*;

use std::rc::Rc;
use std::sync::mpsc::{Receiver, Sender, channel};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use netherforge_platform::PlatformEvents;
use netherforge_runtime::bridge::Debugger as _;
use netherforge_runtime::debug::Debugger;
use netherforge_runtime::server::ServerEvents;
use serde_json::{Value, json};

const SCRIPTED: &str = r#"{ "nodes": { "root": { "display": { "type": "block", "block": "minecraft:stone" }, "hitbox": {} } }, "script": { "file": "script.lua" } }"#;

/// The editor's end: what the debugger sent, and requests answered by their `seq`.
struct Editor {
    debugger: Arc<Debugger>,
    inbox: Mutex<Receiver<Value>>,
    held: Mutex<Vec<Value>>,
    seq: Mutex<i64>,
}

impl Editor {
    fn request(&self, command: &str, arguments: Value) -> Value {
        let seq = {
            let mut seq = self.seq.lock().unwrap();
            *seq += 1;
            *seq
        };
        self.debugger.receive(json!({
            "seq": seq, "type": "request", "command": command, "arguments": arguments,
        }));
        self.wait(|m| m["type"] == "response" && m["request_seq"] == seq)
    }

    /// The next message `pick` takes, keeping the rest for later.
    fn wait(&self, pick: impl Fn(&Value) -> bool) -> Value {
        {
            let mut held = self.held.lock().unwrap();
            if let Some(i) = held.iter().position(&pick) {
                return held.remove(i);
            }
        }
        loop {
            let message = self
                .inbox
                .lock()
                .unwrap()
                .recv_timeout(Duration::from_secs(10))
                .expect("the debugger answered in time");
            if pick(&message) {
                return message;
            }
            self.held.lock().unwrap().push(message);
        }
    }

    fn event(&self, name: &str) -> Value {
        self.wait(|m| m["type"] == "event" && m["event"] == name)
    }

    fn attach(&self, breakpoints: &[(&str, &[u32])]) {
        assert_eq!(
            self.request("initialize", json!({ "adapterID": "netherforge" }))["success"],
            true
        );
        self.event("initialized");
        self.request("attach", json!({}));
        for (file, lines) in breakpoints {
            let points: Vec<Value> = lines.iter().map(|l| json!({ "line": l })).collect();
            let answer = self.request(
                "setBreakpoints",
                json!({ "source": { "path": file }, "breakpoints": points }),
            );
            assert_eq!(answer["success"], true, "{answer}");
        }
        self.request("configurationDone", json!({}));
    }

    /// The stack's frames as `name file:line`.
    fn stack(&self) -> Vec<String> {
        let answer = self.request("stackTrace", json!({ "threadId": 1 }));
        answer["body"]["stackFrames"]
            .as_array()
            .unwrap()
            .iter()
            .map(|f| {
                format!(
                    "{} {}:{}",
                    f["name"].as_str().unwrap(),
                    f["source"]["path"].as_str().unwrap(),
                    f["line"]
                )
            })
            .collect()
    }

    /// Frame `frame`'s scopes by name, with their references.
    fn scopes(&self, frame: i64) -> Vec<(String, i64)> {
        let answer = self.request("scopes", json!({ "frameId": frame }));
        answer["body"]["scopes"]
            .as_array()
            .unwrap()
            .iter()
            .map(|s| {
                (
                    s["name"].as_str().unwrap().to_owned(),
                    s["variablesReference"].as_i64().unwrap(),
                )
            })
            .collect()
    }

    /// What `reference` expands to: name, value, type, reference.
    fn variables(&self, reference: i64) -> Vec<(String, String, String, i64)> {
        let answer = self.request("variables", json!({ "variablesReference": reference }));
        answer["body"]["variables"]
            .as_array()
            .unwrap_or_else(|| panic!("{answer}"))
            .iter()
            .map(|v| {
                (
                    v["name"].as_str().unwrap().to_owned(),
                    v["value"].as_str().unwrap().to_owned(),
                    v["type"].as_str().unwrap_or("").to_owned(),
                    v["variablesReference"].as_i64().unwrap(),
                )
            })
            .collect()
    }

    fn stopped_at(&self) -> (String, String) {
        let stopped = self.event("stopped");
        let top = self.stack().into_iter().next().unwrap();
        (stopped["body"]["reason"].as_str().unwrap().to_owned(), top)
    }
}

struct Server {
    platform: Rc<Fake>,
    events: Rc<ServerEvents<'static>>,
    files: &'static Files,
    editor: Arc<Editor>,
}

fn server(files: &[(&str, &str)]) -> Server {
    let platform = Rc::new(Fake::default());
    *platform.player.borrow_mut() = Some(place(0.0, 64.0, 0.0));
    let mut all = vec![("netherforge.json", MANIFEST)];
    all.extend_from_slice(files);
    let files: &'static Files = Box::leak(Box::new(Files::new(&all)));
    let events = Rc::new(ServerEvents::new(Session::start(
        platform.clone(),
        files,
        &NoPackages,
    )));
    let (to, inbox): (Sender<Value>, Receiver<Value>) = channel();
    let to = Mutex::new(to);
    let debugger = Debugger::with_hold(
        Arc::new(move |m| {
            let _ = to.lock().unwrap().send(m);
        }),
        Some(std::path::PathBuf::from("/projects/demo")),
        Duration::from_millis(50),
    );
    events.attach_debugger(debugger.clone());
    Server {
        platform,
        events,
        files,
        editor: Arc::new(Editor {
            debugger,
            inbox: Mutex::new(inbox),
            held: Mutex::default(),
            seq: Mutex::new(0),
        }),
    }
}

impl Server {
    fn tick(&self, n: usize) {
        for _ in 0..n {
            self.events.tick();
        }
    }

    /// Plays the editor on a thread of its own while `f` runs here (a tick held at a stop).
    fn while_editor<T: std::marker::Send + 'static>(
        &self,
        editor: impl FnOnce(&Editor) -> T + std::marker::Send + 'static,
        here: impl FnOnce(),
    ) -> T {
        let e = self.editor.clone();
        let watcher = std::thread::spawn(move || editor(&e));
        here();
        watcher.join().expect("the editor's part")
    }
}

#[test]
fn a_breakpoint_stops_the_tick_and_the_editor_reads_the_stack_the_scopes_and_the_variables() {
    let s = server(&[
        ("centities/box/centity.json", SCRIPTED),
        ("centities/box/script.lua", ""),
        (
            "modules/m/init.lua",
            r#"local count = 0
shared = { greeting = "hi", list = { 1, 2, 3 } }
local function bump(by)
  local total = count + by
  count = total
  return total
end
nf.on("tick", function()
  local box = nf.centities.all()[1]
  bump(2)
end)
"#,
        ),
    ]);
    s.editor.attach(&[("modules/m/init.lua", &[5])]);
    let id = s
        .events
        .session()
        .spawn("box", place(1.0, 64.0, 2.0), 0.0)
        .unwrap();
    let seen = s.while_editor(
        |e| {
            let (reason, top) = e.stopped_at();
            let stack = e.stack();
            let scopes = e.scopes(1);
            let locals = e.variables(scopes[0].1);
            let upvalues = e.variables(scopes.iter().find(|(n, _)| n == "Upvalues").unwrap().1);
            let globals = e.variables(scopes.iter().find(|(n, _)| n == "Globals").unwrap().1);
            let shared = globals.iter().find(|g| g.0 == "shared").cloned().unwrap();
            let inside = e.variables(shared.3);
            let handler_scopes = e.scopes(2);
            let handler_locals = e.variables(handler_scopes[0].1);
            let boxed = handler_locals
                .iter()
                .find(|v| v.0 == "box")
                .cloned()
                .unwrap();
            let facts = e.variables(boxed.3);
            assert_eq!(
                e.request("continue", json!({ "threadId": 1 }))["success"],
                true
            );
            e.event("continued");
            (
                reason, top, stack, locals, upvalues, shared, inside, boxed, facts,
            )
        },
        || s.tick(1),
    );
    let (reason, top, stack, locals, upvalues, shared, inside, boxed, facts) = seen;
    assert_eq!(reason, "breakpoint");
    assert_eq!(top, "bump modules/m/init.lua:5");
    assert_eq!(stack[1], "function (line 8) modules/m/init.lua:10");
    assert_eq!(
        locals,
        [
            ("by".into(), "2".into(), "number".into(), 0),
            ("total".into(), "2".into(), "number".into(), 0)
        ]
    );
    assert_eq!(upvalues, [("count".into(), "0".into(), "number".into(), 0)]);
    assert_eq!(shared.1, "{greeting = \"hi\", list = {…}}");
    assert_eq!(
        inside
            .iter()
            .map(|v| (v.0.as_str(), v.1.as_str()))
            .collect::<Vec<_>>(),
        [("greeting", "\"hi\""), ("list", "{1, 2, 3}")]
    );
    let short = &netherforge_runtime::events::uuid_text(&id)[..8];
    assert_eq!(boxed.1, format!("Centity box {short}"));
    assert_eq!(boxed.2, "Centity");
    assert_eq!(facts[0].0, "centity");
    assert_eq!(facts[0].1, "box");
    assert!(
        facts.iter().any(|f| f.0 == "position" && f.1 == "1, 64, 2"),
        "{facts:?}"
    );
    // The server was kept alive while held.
    assert!(
        s.platform
            .take()
            .iter()
            .any(|c| c
                .starts_with("hold Paused by the NetherForge debugger at modules/m/init.lua:5"))
    );
}

#[test]
fn stepping_in_out_and_over_follows_the_script() {
    let s = server(&[(
        "modules/m/init.lua",
        r#"local function inner()
  return 1
end
nf.on("tick", function()
  local a = inner()
  local b = 2
end)
"#,
    )]);
    s.editor.attach(&[("modules/m/init.lua", &[5])]);
    let stops = s.while_editor(
        |e| {
            let mut stops = vec![e.stopped_at()];
            e.request("stepIn", json!({ "threadId": 1 }));
            stops.push(e.stopped_at());
            e.request("stepOut", json!({ "threadId": 1 }));
            stops.push(e.stopped_at());
            e.request("next", json!({ "threadId": 1 }));
            stops.push(e.stopped_at());
            // Stepping off the end of the handler runs on: nothing stops in the next one.
            e.request("next", json!({ "threadId": 1 }));
            e.request(
                "setBreakpoints",
                json!({ "source": { "path": "modules/m/init.lua" }, "breakpoints": [] }),
            );
            e.event("continued");
            e.event("continued");
            e.event("continued");
            stops
        },
        || s.tick(1),
    );
    let at: Vec<(&str, &str)> = stops
        .iter()
        .map(|(r, t)| (r.as_str(), t.as_str()))
        .collect();
    assert_eq!(
        at[0],
        ("breakpoint", "function (line 4) modules/m/init.lua:5")
    );
    assert_eq!(at[1], ("step", "inner modules/m/init.lua:2"));
    // Out of `inner`: its result went straight into `a`, so the next line of the handler is 6.
    assert_eq!(at[2].1, "function (line 4) modules/m/init.lua:6");
    assert_eq!(at[3].1, "function (line 4) modules/m/init.lua:7");
}

#[test]
fn stepping_over_a_tasks_wait_stops_at_its_next_line_when_it_wakes() {
    let s = server(&[(
        "modules/m/init.lua",
        r#"nf.task(function()
  nf.wait(1)
  nf.wait(3)
  local done = true
end)
"#,
    )]);
    s.editor.attach(&[("modules/m/init.lua", &[3])]);
    let stops = s.while_editor(
        |e| {
            let first = e.stopped_at();
            e.request("next", json!({ "threadId": 1 }));
            e.event("continued");
            let second = e.stopped_at();
            e.request("continue", json!({ "threadId": 1 }));
            (first, second)
        },
        || s.tick(6),
    );
    assert_eq!(stops.0.1, "function (line 1) modules/m/init.lua:3");
    assert_eq!(
        stops.1,
        (
            "step".to_owned(),
            "function (line 1) modules/m/init.lua:4".to_owned()
        )
    );
}

#[test]
fn pause_stops_at_the_next_line_any_script_runs() {
    let s = server(&[(
        "modules/m/init.lua",
        "nf.on('tick', function()\n  local x = 1\nend)\n",
    )]);
    s.editor.attach(&[]);
    s.editor.request("pause", json!({ "threadId": 1 }));
    let stopped = s.while_editor(
        |e| {
            let stopped = e.stopped_at();
            e.request("continue", json!({}));
            stopped
        },
        || s.tick(1),
    );
    assert_eq!(
        stopped,
        (
            "pause".to_owned(),
            "function (line 1) modules/m/init.lua:2".to_owned()
        )
    );
    // While it runs, there's no stack to ask for.
    let answer = s.editor.request("stackTrace", json!({ "threadId": 1 }));
    assert_eq!(answer["success"], false);
    assert_eq!(answer["message"], "the server isn't paused");
}

#[test]
fn break_on_script_errors_stops_where_the_error_happened_and_its_reported_after() {
    let s = server(&[(
        "modules/m/init.lua",
        "nf.on('tick', function()\n  pcall(error, 'caught')\n  local t = nil\n  return t.field\nend)\n",
    )]);
    s.editor.attach(&[]);
    s.editor
        .request("setExceptionBreakpoints", json!({ "filters": ["errors"] }));
    s.platform.take();
    let stopped = s.while_editor(
        |e| {
            let stopped = e.event("stopped");
            let top = e.stack().remove(0);
            e.request("continue", json!({}));
            (stopped, top)
        },
        || s.tick(1),
    );
    assert_eq!(stopped.0["body"]["reason"], "exception");
    assert!(
        stopped.0["body"]["text"]
            .as_str()
            .unwrap()
            .contains("modules/m/init.lua:4"),
        "{}",
        stopped.0
    );
    assert_eq!(stopped.1, "function (line 1) modules/m/init.lua:4");
    let calls = s.platform.take();
    assert!(
        calls
            .iter()
            .any(|c| c.starts_with("warn module m: tick handler failed")),
        "{calls:?}"
    );
}

#[test]
fn time_paused_isnt_the_scripts_its_time_limit_leaves_it_out() {
    let s = server(&[(
        "modules/m/init.lua",
        "nf.on('tick', function()\n  local x = 1\n  local y = 2\nend)\n",
    )]);
    s.editor.attach(&[("modules/m/init.lua", &[2])]);
    s.platform.take();
    s.while_editor(
        |e| {
            e.stopped_at();
            // Longer than a call may run.
            std::thread::sleep(Duration::from_millis(1200));
            e.request("continue", json!({}));
        },
        || s.tick(1),
    );
    let calls = s.platform.take();
    assert!(!calls.iter().any(|c| c.contains("time limit")), "{calls:?}");
}

#[test]
fn an_editor_that_goes_away_while_the_server_is_paused_leaves_it_running_without_breakpoints() {
    let s = server(&[(
        "modules/m/init.lua",
        "nf.on('tick', function()\n  local x = 1\nend)\n",
    )]);
    s.editor.attach(&[("modules/m/init.lua", &[2])]);
    s.while_editor(
        |e| {
            e.stopped_at();
            // The bridge's connection ends: what it does then.
            e.debugger.detach();
            e.event("continued");
        },
        || s.tick(1),
    );
    // No breakpoint is left to stop at.
    s.tick(3);
    assert!(!s.editor.debugger.paused());
}

#[test]
fn breakpoints_outlive_a_full_reload_and_hold_in_scripts_bodies() {
    let s = server(&[("modules/m/init.lua", "local x = 1\nlocal y = 2\n")]);
    s.editor.attach(&[("modules/m/init.lua", &[2])]);
    s.files
        .write("modules/m/init.lua", "local x = 1\nlocal y = 3\n");
    let stopped = s.while_editor(
        |e| {
            let stopped = e.stopped_at();
            e.request("continue", json!({}));
            stopped
        },
        || {
            s.events.session_mut().restart();
        },
    );
    assert_eq!(
        stopped,
        (
            "breakpoint".to_owned(),
            "(body) modules/m/init.lua:2".to_owned()
        )
    );
}

#[test]
fn a_breakpoint_in_a_project_file_binds_by_its_place_on_disk_too() {
    let s = server(&[(
        "modules/m/init.lua",
        "nf.on('tick', function()\n  local x = 1\nend)\n",
    )]);
    s.editor.attach(&[]);
    let answer = s.editor.request(
        "setBreakpoints",
        json!({ "source": { "path": "/projects/demo/modules/m/init.lua" }, "breakpoints": [{ "line": 2 }] }),
    );
    // The answer names the chunk, as stacks do.
    assert_eq!(
        answer["body"]["breakpoints"][0]["source"]["path"],
        "modules/m/init.lua"
    );
    let stopped = s.while_editor(
        |e| {
            let stopped = e.stopped_at();
            e.request("continue", json!({}));
            stopped
        },
        || s.tick(1),
    );
    assert_eq!(stopped.1, "function (line 1) modules/m/init.lua:2");
}

#[test]
fn a_packages_file_named_by_its_place_on_disk_is_its_package_path() {
    let debugger = Debugger::new(
        Arc::new(|_: Value| {}),
        Some(std::path::PathBuf::from("/projects/demo")),
    );
    // A path dependency inside the project's folder, and one from the package cache.
    debugger.set_packages(Arc::new(Mutex::new(vec![
        (
            "inner".to_owned(),
            std::path::PathBuf::from("/projects/demo/packages/inner"),
        ),
        (
            "acme".to_owned(),
            std::path::PathBuf::from("/cache/acme@1.2.0"),
        ),
    ])));
    assert_eq!(
        debugger.source_path("/cache/acme@1.2.0/modules/api/init.lua"),
        "acme:modules/api/init.lua"
    );
    // The package nested in the project's folder wins over the folder around it.
    assert_eq!(
        debugger.source_path("/projects/demo/packages/inner/modules/x/init.lua"),
        "inner:modules/x/init.lua"
    );
    assert_eq!(
        debugger.source_path("/projects/demo/modules/m/init.lua"),
        "modules/m/init.lua"
    );
    // Anywhere else stays as it was; a path already relative too.
    assert_eq!(debugger.source_path("/elsewhere/a.lua"), "/elsewhere/a.lua");
    assert_eq!(
        debugger.source_path("acme:modules/api/init.lua"),
        "acme:modules/api/init.lua"
    );
}
