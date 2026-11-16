//! The server's side of the dev bridge on the Rust runtime, driven by a test playing the editor over a real socket
//! (Kotlin's `BridgeTest`): the hello, every request, unknown methods and bad params, a batch, `ping` while the main
//! thread is held, a burst of log lines in a few batches, script lines located, a refusing editor, particle effects
//! played for the editor, the bots extension aiming at a centity's node, structures and worlds captured, settings, a
//! reconnect with a fresh hello, and a server whose editor is gone stopping itself.

mod support;
use support::*;

use std::cell::{Cell, RefCell};
use std::io::{BufRead, BufReader, Write};
use std::net::{TcpListener, TcpStream};
use std::rc::Rc;
use std::sync::mpsc::{Receiver, channel};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use netherforge_format::bridge::rpc::{self, Frame};
use netherforge_format::bridge::{self, ConsoleEntry, HelloParams, auth};
use netherforge_platform::PlatformEvents;
use netherforge_runtime::bridge::{Bridge, BridgeConfig};
use netherforge_runtime::server::ServerEvents;
use serde_json::{Value, json};

/// What the editor heard, as it arrived.
#[derive(Clone, Debug)]
enum Heard {
    Hello(HelloParams),
    Console(Vec<ConsoleEntry>),
    Event(String, Value),
    Response(Value, Result<Value, (i64, String)>),
    Batch(Vec<Value>),
}

/// One accepted connection, read on a thread of its own so the test can run the server's ticks meanwhile. It answers
/// the hello (or refuses it with `refuse`) and the server's proof.
struct Editor {
    writer: Mutex<TcpStream>,
    heard: Receiver<Heard>,
    seen: RefCell<Vec<Heard>>,
    next_id: Cell<i64>,
    batches: Arc<Mutex<Vec<usize>>>,
}

impl Editor {
    fn accept(listener: &TcpListener, refuse: Option<&str>) -> Editor {
        listener.set_nonblocking(false).unwrap();
        let (socket, _) = listener.accept().unwrap();
        let mut writer = socket.try_clone().unwrap();
        let reader = BufReader::new(socket.try_clone().unwrap());
        let (to, heard) = channel();
        let batches = Arc::new(Mutex::new(Vec::new()));
        let counted = batches.clone();
        let refuse = refuse.map(str::to_owned);
        let mut answering = socket.try_clone().unwrap();
        std::thread::spawn(move || {
            let mut hello: Option<HelloParams> = None;
            let mut editor_nonce = String::new();
            for line in reader.lines() {
                let Ok(line) = line else { break };
                let value: Value = serde_json::from_str(&line).unwrap();
                if let Value::Array(items) = &value {
                    let _ = to.send(Heard::Batch(items.clone()));
                    continue;
                }
                match rpc::decode(&line) {
                    Frame::Single(rpc::Message::Request { id, method, params }) => {
                        if method == "authenticate" {
                            let proof = params.unwrap()["proof"].as_str().unwrap().to_owned();
                            let h = hello.clone().unwrap();
                            assert_eq!(
                                proof,
                                auth::plugin_proof("secret", &editor_nonce, &h.nonce),
                                "the server proves the token"
                            );
                            let ok = rpc::Message::ok(id, Value::Null).line();
                            answering.write_all(format!("{ok}\n").as_bytes()).unwrap();
                            let _ = to.send(Heard::Hello(h));
                            continue;
                        }
                        assert_eq!(method, "hello");
                        let h: HelloParams = serde_json::from_value(params.unwrap()).unwrap();
                        let answer = match &refuse {
                            Some(why) => {
                                rpc::Message::error(id, bridge::PROTOCOL_MISMATCH, why.clone())
                            }
                            None => {
                                editor_nonce = auth::nonce();
                                rpc::Message::ok(
                                    id,
                                    json!({ "protocol": bridge::PROTOCOL, "nonce": editor_nonce, "proof": auth::editor_proof("secret", &h.nonce, &editor_nonce) }),
                                )
                            }
                        };
                        answering
                            .write_all(format!("{}\n", answer.line()).as_bytes())
                            .unwrap();
                        if refuse.is_some() {
                            let _ = to.send(Heard::Hello(h));
                        } else {
                            hello = Some(h);
                        }
                    }
                    Frame::Single(rpc::Message::Notification { method, params }) => {
                        let params = params.unwrap_or(Value::Null);
                        if method == "console" {
                            let items: Vec<ConsoleEntry> =
                                serde_json::from_value(params["items"].clone()).unwrap();
                            counted.lock().unwrap().push(items.len());
                            let _ = to.send(Heard::Console(items));
                        } else {
                            let _ = to.send(Heard::Event(method, params));
                        }
                    }
                    Frame::Single(rpc::Message::Response { id, outcome }) => {
                        let _ = to.send(Heard::Response(
                            id,
                            outcome.map_err(|e| (e.code, e.message)),
                        ));
                    }
                    other => panic!("{other:?}"),
                }
            }
        });
        let _ = &mut writer;
        Editor {
            writer: Mutex::new(writer),
            heard,
            seen: RefCell::default(),
            next_id: Cell::new(1),
            batches,
        }
    }

    fn write_line(&self, line: &str) {
        self.writer
            .lock()
            .unwrap()
            .write_all(format!("{line}\n").as_bytes())
            .unwrap();
    }

    /// The next frame `matching` (one passed over is kept for a later call), running `pump` (the server's ticks)
    /// while it waits, for up to 10 s.
    fn next(&self, pump: &dyn Fn(), matching: impl Fn(&Heard) -> bool) -> Heard {
        let kept = self.seen.borrow().iter().position(&matching);
        if let Some(at) = kept {
            return self.seen.borrow_mut().remove(at);
        }
        let deadline = Instant::now() + Duration::from_secs(10);
        while Instant::now() < deadline {
            pump();
            let Ok(heard) = self.heard.recv_timeout(Duration::from_millis(5)) else {
                continue;
            };
            if matching(&heard) {
                return heard;
            }
            self.seen.borrow_mut().push(heard);
        }
        panic!(
            "nothing like that within 10s; seen {:?}",
            self.seen.borrow()
        )
    }

    fn hello(&self, pump: &dyn Fn()) -> HelloParams {
        match self.next(pump, |h| matches!(h, Heard::Hello(_))) {
            Heard::Hello(hello) => hello,
            _ => unreachable!(),
        }
    }

    fn event(&self, pump: &dyn Fn(), name: &str) -> Value {
        match self.next(pump, |h| matches!(h, Heard::Event(n, _) if n == name)) {
            Heard::Event(_, params) => params,
            _ => unreachable!(),
        }
    }

    /// Sends request `method` with `params`, and waits for its answer.
    fn request(
        &self,
        pump: &dyn Fn(),
        method: &str,
        params: Value,
    ) -> Result<Value, (i64, String)> {
        let id = self.next_id.get();
        self.next_id.set(id + 1);
        let params = (!params.is_null()).then_some(params);
        self.write_line(&rpc::Message::request(id, method, params).line());
        self.answer(pump, id)
    }

    fn answer(&self, pump: &dyn Fn(), id: i64) -> Result<Value, (i64, String)> {
        match self.next(
            pump,
            |h| matches!(h, Heard::Response(got, _) if *got == json!(id)),
        ) {
            Heard::Response(_, outcome) => outcome,
            _ => unreachable!(),
        }
    }
}

const TOWER: &str = r#"{ "nodes": {
  "base": { "display": { "type": "block", "block": "minecraft:stone" }, "hitbox": {} },
  "cap": { "parent": "base", "transform": { "translation": [0, 1.5, 0] }, "display": { "type": "block", "block": "minecraft:glowstone" }, "hitbox": {} },
  "flag": { "parent": "cap", "transform": { "translation": [0, 1, 0] }, "display": { "type": "block", "block": "minecraft:red_wool" } }
}, "script": { "file": "script.lua" } }"#;

const SETTINGS_MANIFEST: &str = r#"{ "formatVersion": 1, "name": "Demo", "namespace": "demo", "version": "1.0.0", "minecraft": "26.3", "settings": { "greeting": { "type": "string", "default": "hi", "description": "What it says" } } }"#;

fn project() -> Files {
    Files::new(&[
        ("netherforge.json", SETTINGS_MANIFEST),
        ("centities/tower/centity.json", TOWER),
        ("centities/tower/script.lua", "print('tower up')"),
        ("modules/greeter/init.lua", "print('hello')"),
        (
            "particles/sparkle/effect.json",
            r#"{ "duration": 10, "emitters": { "a": { "particle": "minecraft:flame", "burst": 1, "every": 1 } } }"#,
        ),
    ])
}

/// A server for `files` with a bridge to `listener`, its config changed by `configure`.
fn server<'a>(
    platform: &Rc<Fake>,
    files: &'a Files,
    listener: &TcpListener,
    configure: impl FnOnce(&mut BridgeConfig),
) -> (Rc<Bridge>, ServerEvents<'a>) {
    let mut config = BridgeConfig::new(
        listener.local_addr().unwrap().port(),
        "secret",
        "/projects/demo".into(),
    );
    configure(&mut config);
    let bridge = Bridge::new(platform.clone(), config);
    let session =
        netherforge_runtime::session::Session::start(bridge.platform(), files, &NoPackages);
    let events = ServerEvents::new(session);
    events.attach_bridge(bridge.clone());
    bridge.connect();
    (bridge, events)
}

#[test]
fn the_server_says_hello_then_answers_every_request_with_its_id() {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let platform = Rc::new(Fake::default());
    let files = project();
    let (bridge, events) = server(&platform, &files, &listener, |_| {});
    let pump = || events.tick();
    let editor = Editor::accept(&listener, None);
    let hello = editor.hello(&pump);
    // A nonce, never the token.
    assert!(hello.nonce.len() >= 32 && !hello.nonce.contains("secret"));
    assert_eq!(hello.protocol, bridge::PROTOCOL);
    assert_eq!(hello.minecraft, "26.3");
    assert_eq!(hello.project, "/projects/demo");
    assert_eq!(editor.event(&pump, "problems"), json!({ "problems": [] }));
    let settings = editor.event(&pump, "settings_changed");
    assert_eq!(
        settings["packages"][0]["settings"]["greeting"]["value"],
        "hi"
    );
    assert_eq!(
        editor.event(&pump, "status"),
        json!({ "players": [], "instances": 0 })
    );

    let spawned = editor
        .request(&pump, "spawn", json!({ "centity": "tower" }))
        .unwrap();
    assert_eq!(spawned["centity"], "tower");
    assert_eq!(
        editor.request(&pump, "instances", Value::Null).unwrap(),
        json!([spawned])
    );
    let reload = editor
        .request(
            &pump,
            "reload",
            json!({ "paths": ["centities/tower/script.lua"] }),
        )
        .unwrap();
    assert_eq!(
        reload,
        json!({ "resources": [{ "package": "demo", "kind": "centity", "id": "tower", "ok": true, "reattached": 1 }] })
    );
    assert_eq!(
        editor
            .request(&pump, "export_game_data", Value::Null)
            .unwrap()["minecraft"],
        "26.3"
    );
    assert_eq!(
        editor.request(&pump, "command", json!({ "line": "/nf list" })),
        Ok(Value::Null)
    );
    assert_eq!(
        editor.request(&pump, "command", json!({ "line": "nothing here" })),
        Err((
            bridge::REQUEST_FAILED,
            "the server didn't run \"nothing here\"".into()
        ))
    );
    let (code, message) = editor
        .request(&pump, "spawn", json!({ "centity": "nope" }))
        .unwrap_err();
    assert_eq!(code, bridge::REQUEST_FAILED);
    assert!(message.contains("no centity \"nope\""), "{message}");

    // A script's line arrives on the console stream with where it was written.
    files.write(
        "modules/greeter/init.lua",
        "-- now it only says hello\nprint('hello from greeter')\n",
    );
    editor
        .request(
            &pump,
            "reload",
            json!({ "paths": ["modules/greeter/init.lua"] }),
        )
        .unwrap();
    let line = editor.next(&pump, |h| {
        matches!(h, Heard::Console(items) if items.iter().any(|e| matches!(e, ConsoleEntry::Log { message, .. } if message == "hello from greeter")))
    });
    let Heard::Console(items) = line else {
        unreachable!()
    };
    let source = items.iter().find_map(|e| match e {
        ConsoleEntry::Log {
            message, source, ..
        } if message == "hello from greeter" => source.clone(),
        _ => None,
    });
    assert_eq!(
        source,
        Some(bridge::SourceRef {
            file: "modules/greeter/init.lua".into(),
            line: Some(2),
        })
    );
    // What the editor's client is typed by holds for what the server sent: every notification and answer reads as
    // its declared type (format's tables, which the editor's TypeScript is generated from).
    for (method, result) in [("spawn", &spawned), ("reload", &reload)] {
        assert!(
            matches!(bridge::check_result(method, result), Some(Ok(()))),
            "{method}"
        );
    }
    for heard in editor.seen.borrow().iter() {
        if let Heard::Event(name, params) = heard {
            assert!(
                matches!(bridge::check_notification(name, Some(params)), Some(Ok(()))),
                "{name}: {params}"
            );
        }
    }
    // The server's console has the line as it always did.
    assert!(
        platform
            .take()
            .iter()
            .any(|l| l.starts_with("info [module greeter] hello from greeter"))
    );
    bridge.stop();
}

#[test]
fn a_script_error_reaches_the_console_located_and_its_problem_follows() {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let platform = Rc::new(Fake::default());
    let files = project();
    let (bridge, events) = server(&platform, &files, &listener, |_| {});
    let pump = || events.tick();
    let editor = Editor::accept(&listener, None);
    editor.hello(&pump);
    files.write("modules/greeter/init.lua", "local x = 1\nerror('broken')\n");
    let reload = editor
        .request(
            &pump,
            "reload",
            json!({ "paths": ["modules/greeter/init.lua"] }),
        )
        .unwrap();
    assert_eq!(reload["resources"][0]["ok"], false);
    assert_eq!(reload["resources"][0]["problems"][0]["line"], 2);
    let error = editor.next(&pump, |h| {
        matches!(h, Heard::Console(items) if items.iter().any(|e| matches!(e, ConsoleEntry::ScriptError { .. })))
    });
    let Heard::Console(items) = error else {
        unreachable!()
    };
    let (message, source) = items
        .iter()
        .find_map(|e| match e {
            ConsoleEntry::ScriptError {
                message, source, ..
            } => Some((message.clone(), source.clone())),
            _ => None,
        })
        .unwrap();
    assert!(message.contains("body failed"), "{message}");
    assert_eq!(source.unwrap().line, Some(2));
    // The problems after the reload carry it.
    let problems = editor.next(&pump, |h| {
        matches!(h, Heard::Event(n, p) if n == "problems" && !p["problems"].as_array().unwrap().is_empty())
    });
    let Heard::Event(_, problems) = problems else {
        unreachable!()
    };
    assert_eq!(problems["problems"][0]["code"], "script.error");
    bridge.stop();
}

#[test]
fn an_unknown_method_unreadable_params_and_a_broken_frame_are_answered_as_json_rpc_says() {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let platform = Rc::new(Fake::default());
    let files = project();
    let (bridge, events) = server(&platform, &files, &listener, |_| {});
    let pump = || events.tick();
    let editor = Editor::accept(&listener, None);
    editor.hello(&pump);

    editor.write_line(r#"{"jsonrpc":"2.0","id":1,"method":"debugger/attach"}"#);
    assert_eq!(
        editor.answer(&pump, 1),
        Err((
            rpc::METHOD_NOT_FOUND,
            "Unknown method \"debugger/attach\"".into()
        ))
    );
    editor.write_line(r#"{"jsonrpc":"2.0","id":2,"method":"spawn","params":{"player":"Steve"}}"#);
    let (code, message) = editor.answer(&pump, 2).unwrap_err();
    assert_eq!(code, rpc::INVALID_PARAMS);
    assert!(message.starts_with("Invalid params for spawn"), "{message}");
    editor.write_line(r#"{"jsonrpc":"2.0","id":3,"method":"#);
    let broken = editor.next(&pump, |h| matches!(h, Heard::Response(Value::Null, _)));
    let Heard::Response(_, Err((code, _))) = broken else {
        panic!("{broken:?}")
    };
    assert_eq!(code, rpc::PARSE_ERROR);

    // A notification is never answered, not even an unknown one; the next request still is.
    editor.write_line(r#"{"jsonrpc":"2.0","method":"nobody/listens"}"#);
    editor.next_id.set(4);
    assert_eq!(editor.request(&pump, "ping", Value::Null), Ok(Value::Null));

    // A batch gets one array back, in order, once every request in it is answered.
    editor.write_line(
        r#"[{"jsonrpc":"2.0","id":10,"method":"instances"},{"jsonrpc":"2.0","method":"x"},{"jsonrpc":"2.0","id":11,"method":"nope"}]"#,
    );
    let Heard::Batch(items) = editor.next(&pump, |h| matches!(h, Heard::Batch(_))) else {
        unreachable!()
    };
    assert_eq!(
        items.iter().map(|i| i["id"].clone()).collect::<Vec<_>>(),
        [json!(10), json!(11)]
    );
    assert_eq!(items[1]["error"]["code"], rpc::METHOD_NOT_FOUND);
    bridge.stop();
}

#[test]
fn a_request_for_the_bridges_thread_is_answered_while_the_main_thread_is_busy() {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let platform = Rc::new(Fake::default());
    let files = project();
    let (bridge, events) = server(&platform, &files, &listener, |_| {});
    let pump = || events.tick();
    let editor = Editor::accept(&listener, None);
    editor.hello(&pump);
    // No ticks from here: `instances` waits, `ping` and `profiler_subscribe` don't.
    editor.write_line(r#"{"jsonrpc":"2.0","id":1,"method":"instances"}"#);
    editor.write_line(r#"{"jsonrpc":"2.0","id":2,"method":"ping"}"#);
    editor.write_line(
        r#"{"jsonrpc":"2.0","id":3,"method":"profiler_subscribe","params":{"on":true}}"#,
    );
    let idle = || {};
    assert_eq!(editor.answer(&idle, 2), Ok(Value::Null));
    assert_eq!(editor.answer(&idle, 3), Ok(Value::Null));
    assert!(bridge.profiler_streaming());
    assert!(
        editor
            .seen
            .borrow()
            .iter()
            .all(|h| !matches!(h, Heard::Response(id, _) if *id == json!(1))),
        "nothing ran the main thread"
    );
    // Now the main thread runs, and the other is answered.
    assert!(editor.answer(&pump, 1).is_ok());
    bridge.stop();
}

#[test]
fn a_burst_of_log_lines_goes_out_in_a_few_batches_in_order() {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let platform = Rc::new(Fake::default());
    let files = project();
    let (bridge, events) = server(&platform, &files, &listener, |_| {});
    let pump = || events.tick();
    let editor = Editor::accept(&listener, None);
    editor.hello(&pump);
    editor.event(&pump, "status");
    let log = bridge.platform();
    for i in 0..2_000 {
        log.log().info(format!("line {i}"));
    }
    let mut seen = Vec::new();
    while seen.len() < 2_000 {
        let Heard::Console(items) = editor.next(&pump, |h| matches!(h, Heard::Console(_))) else {
            unreachable!()
        };
        seen.extend(items.into_iter().filter_map(|e| match e {
            ConsoleEntry::Log { message, .. } if message.starts_with("line ") => Some(message),
            _ => None,
        }));
    }
    let expected: Vec<String> = (0..2_000).map(|i| format!("line {i}")).collect();
    assert_eq!(seen, expected);
    let batches = editor.batches.lock().unwrap().len();
    assert!(batches < 100, "{batches} batches");
    bridge.stop();
}

#[test]
fn an_editor_that_refuses_the_hello_is_final_and_the_console_says_why() {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let platform = Rc::new(Fake::default());
    let files = project();
    let (bridge, events) = server(&platform, &files, &listener, |_| {});
    let pump = || events.tick();
    let why =
        "This server's NetherForge plugin speaks dev bridge protocol 1 and the editor speaks 2";
    let editor = Editor::accept(&listener, Some(why));
    editor.hello(&pump);
    let refused = format!("error The NetherForge editor refused this dev server's bridge: {why}");
    let deadline = Instant::now() + Duration::from_secs(10);
    let mut lines = Vec::new();
    while !lines.contains(&refused) && Instant::now() < deadline {
        pump();
        lines.extend(platform.take());
        std::thread::sleep(Duration::from_millis(10));
    }
    assert!(lines.contains(&refused), "{lines:?}");
    // No retry: nothing else connects.
    listener.set_nonblocking(true).unwrap();
    std::thread::sleep(Duration::from_millis(600));
    assert!(listener.accept().is_err(), "the server shouldn't reconnect");
    bridge.stop();
}

#[test]
fn the_editor_plays_a_particle_effect_in_front_of_a_player_and_stops_what_it_played() {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let platform = Rc::new(Fake::default());
    // Facing east (yaw -90): three blocks in front of the eyes is +X.
    *platform.player.borrow_mut() = Some(netherforge_platform::Location {
        world: "world".into(),
        x: 10.0,
        y: 64.0,
        z: 0.0,
        yaw: -90.0,
        pitch: 0.0,
    });
    platform.alex_eye.set(true);
    let files = project();
    let (bridge, events) = server(&platform, &files, &listener, |_| {});
    let pump = || events.tick();
    let editor = Editor::accept(&listener, None);
    editor.hello(&pump);
    let played = editor.request(
        &pump,
        "play_particle_effect",
        json!({ "effect": "sparkle", "player": "Alex", "loop": true }),
    );
    assert_eq!(played, Ok(Value::Null));
    assert!(
        platform
            .take()
            .iter()
            .any(|l| l == "info Playing particle effect sparkle")
    );
    {
        let session = events.session();
        let particles = session.shared().particles.borrow();
        let effect = particles.playing().next().expect("playing");
        assert!(effect.owner.is_none(), "the runtime's, not a script's");
        assert!((effect.origin.0 - 13.0).abs() < 1e-9, "{:?}", effect.origin);
        assert!(
            (effect.origin.1 - 65.62).abs() < 1e-9,
            "{:?}",
            effect.origin
        );
        assert!((effect.yaw - 90.0).abs() < 1e-9, "facing the player");
    }
    for _ in 0..30 {
        events.tick();
    }
    assert_eq!(
        events
            .session()
            .shared()
            .particles
            .borrow()
            .playing()
            .count(),
        1,
        "looping"
    );
    let (_, broken) = editor
        .request(&pump, "play_particle_effect", json!({ "effect": "nope" }))
        .unwrap_err();
    assert!(broken.contains("no particle effect \"nope\""), "{broken}");
    assert_eq!(
        editor.request(&pump, "stop_particle_effects", Value::Null),
        Ok(Value::Null)
    );
    events.tick();
    assert!(
        events
            .session()
            .shared()
            .particles
            .borrow()
            .playing()
            .all(|e| e.ended)
    );
    bridge.stop();
}

#[test]
fn bot_requests_reach_the_platforms_bots_a_centity_named_by_node_aimed_at_its_hitbox() {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let platform = Rc::new(Fake::default());
    let files = project();
    let (bridge, events) = server(&platform, &files, &listener, |_| {});
    let pump = || {
        platform.bots.tick();
        events.tick();
    };
    let editor = Editor::accept(&listener, None);
    editor.hello(&pump);

    // Joining finishes ticks later; the answer waits for it.
    let joined = editor
        .request(
            &pump,
            "bots/join",
            json!({ "name": "Tester", "at": { "x": 1.5, "y": 64.5, "z": -2.5 } }),
        )
        .unwrap();
    assert_eq!(joined["name"], "Tester");
    let listed = editor.request(&pump, "bots/list", Value::Null).unwrap();
    assert_eq!(listed[0]["name"], "Tester");

    let tower = events
        .session()
        .spawn("tower", place(1.5, 64.0, 2.5), 0.0)
        .unwrap();
    let (cap, base) = {
        let session = events.session();
        let centities = session.centities();
        let instance = centities.get(tower).unwrap();
        (instance.hitboxes["cap"], instance.hitboxes["base"])
    };
    let id = netherforge_runtime::events::uuid_text(&tower);
    let clicked = editor.request(
        &pump,
        "bots/act",
        json!({ "name": "Tester", "action": { "type": "interact", "centity": id, "node": "cap" } }),
    );
    assert!(clicked.is_ok(), "{clicked:?}");
    assert_eq!(
        platform
            .bots
            .acted
            .borrow()
            .last()
            .unwrap()
            .2
            .as_ref()
            .unwrap()
            .entity,
        cap
    );
    // Without a node: the first one that can be clicked.
    assert!(
        editor
            .request(
                &pump,
                "bots/act",
                json!({ "name": "Tester", "action": { "type": "attack", "centity": id } }),
            )
            .is_ok()
    );
    assert_eq!(
        platform
            .bots
            .acted
            .borrow()
            .last()
            .unwrap()
            .2
            .as_ref()
            .unwrap()
            .entity,
        base
    );
    assert_eq!(
        editor.request(
            &pump,
            "bots/act",
            json!({ "name": "Tester", "action": { "type": "interact", "centity": id, "node": "flag" } }),
        ),
        Err((
            bridge::REQUEST_FAILED,
            "node \"flag\" of centity tower has no hitbox to click".into()
        ))
    );
    assert_eq!(
        editor.request(
            &pump,
            "bots/act",
            json!({ "name": "Nobody", "action": { "type": "jump" } })
        ),
        Err((bridge::REQUEST_FAILED, "no bot named \"Nobody\"".into()))
    );
    assert_eq!(
        editor.request(&pump, "bots/leave", json!({ "name": "Tester" })),
        Ok(Value::Null)
    );
    assert_eq!(
        editor.request(&pump, "bots/list", Value::Null),
        Ok(json!([]))
    );
    bridge.stop();
}

#[test]
fn the_editor_captures_positions_worlds_a_box_as_bytes_and_a_world_as_files() {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let folder = tempfile::tempdir().unwrap();
    let platform = Rc::new(Fake::default());
    *platform.storage.borrow_mut() = Some(folder.path().join("world"));
    let files = project();
    let server_folder = folder.path().to_owned();
    let (bridge, events) = server(&platform, &files, &listener, |c| {
        c.server_directory = server_folder;
    });
    let pump = || events.tick();
    let editor = Editor::accept(&listener, None);
    editor.hello(&pump);

    assert_eq!(
        editor.request(&pump, "player_position", json!({})),
        Err((bridge::REQUEST_FAILED, "nobody is online".into()))
    );
    *platform.player.borrow_mut() = Some(netherforge_platform::Location {
        world: "world".into(),
        x: 10.7,
        y: 64.0,
        z: -3.2,
        yaw: 0.0,
        pitch: 0.0,
    });
    platform.extra_worlds.borrow_mut().push("arena".into());
    assert_eq!(
        editor.request(&pump, "player_position", json!({})),
        Ok(json!({ "player": "Alex", "world": "world", "x": 10, "y": 64, "z": -4 }))
    );
    assert_eq!(
        editor.request(&pump, "player_position", json!({ "player": "Herobrine" })),
        Err((
            bridge::REQUEST_FAILED,
            "no player \"Herobrine\" online".into()
        ))
    );
    assert_eq!(
        editor.request(&pump, "worlds", Value::Null),
        Ok(json!([
            { "name": "world", "environment": "normal", "main": true },
            { "name": "arena", "environment": "normal", "main": false }
        ]))
    );

    // A box's structure comes back as bytes; nothing is left behind.
    let saved = editor
        .request(
            &pump,
            "save_structure",
            json!({ "world": "world", "from": { "x": 2, "y": 65, "z": 2 }, "to": { "x": 1, "y": 64, "z": 0 }, "entities": true }),
        )
        .unwrap();
    assert_eq!(saved["size"], json!({ "x": 2, "y": 2, "z": 3 }));
    use base64::Engine;
    let bytes = base64::engine::general_purpose::STANDARD
        .decode(saved["nbt"].as_str().unwrap())
        .unwrap();
    assert_eq!(String::from_utf8(bytes).unwrap(), "size 2 2 3\n");
    assert_eq!(
        editor.request(
            &pump,
            "save_structure",
            json!({ "world": "world", "from": { "x": 0, "y": 0, "z": 0 }, "to": { "x": 48, "y": 1, "z": 1 } }),
        ),
        Err((
            bridge::REQUEST_FAILED,
            "that box is 49×2×2 blocks; a structure is at most 48 along each side".into()
        ))
    );
    assert_eq!(
        editor.request(
            &pump,
            "save_structure",
            json!({ "world": "gone", "from": { "x": 0, "y": 0, "z": 0 }, "to": { "x": 1, "y": 1, "z": 1 } }),
        ),
        Err((bridge::REQUEST_FAILED, "no world \"gone\" is loaded".into()))
    );

    // A world is saved, and where its files are is relative to the server's folder.
    platform.spawns.borrow_mut().insert(
        "arena".into(),
        netherforge_platform::Location {
            world: "arena".into(),
            x: 3.5,
            y: 71.0,
            z: -8.5,
            yaw: 90.0,
            pitch: 0.0,
        },
    );
    assert_eq!(
        editor.request(&pump, "save_world", json!({ "world": "arena" })),
        Ok(
            json!({ "level": "world/level.dat", "dimension": "world/dimensions/minecraft/arena", "main": false, "spawn": { "x": 3, "y": 71, "z": -9, "yaw": 90.0 } })
        )
    );
    let main = editor
        .request(&pump, "save_world", json!({ "world": "world" }))
        .unwrap();
    assert_eq!(
        (main["dimension"].clone(), main["main"].clone()),
        (json!("world/dimensions/minecraft/overworld"), json!(true))
    );
    assert_eq!(*platform.saved_worlds.borrow(), ["arena", "world"]);
    assert_eq!(
        editor.request(&pump, "save_world", json!({ "world": "gone" })),
        Err((bridge::REQUEST_FAILED, "no world \"gone\" is loaded".into()))
    );
    // Files outside the server's folder are never offered to the editor.
    let elsewhere = tempfile::tempdir().unwrap();
    *platform.storage.borrow_mut() = Some(elsewhere.path().to_owned());
    let (_, outside) = editor
        .request(&pump, "save_world", json!({ "world": "world" }))
        .unwrap_err();
    assert!(
        outside.contains("isn't in the server's folder"),
        "{outside}"
    );
    bridge.stop();
}

#[test]
fn the_editor_reads_and_sets_server_owner_settings_and_hears_them_change() {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let platform = Rc::new(Fake::default());
    let files = project();
    let (bridge, events) = server(&platform, &files, &listener, |_| {});
    let pump = || events.tick();
    let editor = Editor::accept(&listener, None);
    editor.hello(&pump);
    editor.event(&pump, "settings_changed");
    let settings = editor.request(&pump, "settings", Value::Null).unwrap();
    let greeting = &settings["packages"][0]["settings"]["greeting"];
    assert_eq!(
        (greeting["value"].clone(), greeting.get("set")),
        (json!("hi"), None)
    );
    let set = editor
        .request(
            &pump,
            "set_setting",
            json!({ "namespace": "demo", "setting": "greeting", "value": "hello" }),
        )
        .unwrap();
    let greeting = &set["packages"][0]["settings"]["greeting"];
    assert_eq!(
        (greeting["value"].clone(), greeting["set"].clone()),
        (json!("hello"), json!(true))
    );
    let heard = editor.event(&pump, "settings_changed");
    assert_eq!(
        heard["packages"][0]["settings"]["greeting"]["value"],
        "hello"
    );
    let (code, wrong) = editor
        .request(
            &pump,
            "set_setting",
            json!({ "namespace": "demo", "setting": "nope", "value": 1 }),
        )
        .unwrap_err();
    assert_eq!(code, bridge::REQUEST_FAILED);
    assert!(wrong.contains("no setting \"nope\""), "{wrong}");
    bridge.stop();
}

#[test]
fn a_dropped_connection_is_retried_and_the_editor_gets_a_fresh_hello() {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let platform = Rc::new(Fake::default());
    let files = project();
    let (bridge, events) = server(&platform, &files, &listener, |_| {});
    let pump = || events.tick();
    let first = Editor::accept(&listener, None);
    let nonce = first.hello(&pump).nonce;
    first
        .writer
        .lock()
        .unwrap()
        .shutdown(std::net::Shutdown::Both)
        .unwrap();
    let again = Editor::accept(&listener, None);
    assert_ne!(
        again.hello(&pump).nonce,
        nonce,
        "a fresh nonce for each connection"
    );
    assert!(again.request(&pump, "instances", Value::Null).is_ok());
    bridge.stop();
}

#[test]
fn a_dev_server_whose_editor_is_gone_stops_itself() {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let platform = Rc::new(Fake::default());
    let files = project();
    let (bridge, events) = server(&platform, &files, &listener, |c| {
        c.abandon_after = Duration::from_millis(500);
    });
    let pump = || events.tick();
    let editor = Editor::accept(&listener, None);
    editor.hello(&pump);
    // The editor quits: the connection drops and its port goes with it.
    editor
        .writer
        .lock()
        .unwrap()
        .shutdown(std::net::Shutdown::Both)
        .unwrap();
    drop(listener);
    let deadline = Instant::now() + Duration::from_secs(10);
    while platform.shutdown.borrow().is_none() && Instant::now() < deadline {
        pump();
        std::thread::sleep(Duration::from_millis(20));
    }
    assert_eq!(
        platform.shutdown.borrow().as_deref(),
        Some("NetherForge editor closed")
    );
    assert!(platform.take().iter().any(|l| l.contains("has been gone")));
    bridge.stop();
}

/// Every request in format's recorded session (`testdata/bridge/session.ndjson`, what the editor's client is tested
/// with too), sent by the editor to this server: each is answered once, with a result that reads as its method's
/// type, or the error the recording has for it (its code), or a failure the server explains (`REQUEST_FAILED`: the
/// fake's world isn't the recording's). Never an unknown method or unreadable params where the recording had none.
#[test]
fn every_request_the_recorded_session_sends_is_answered_as_its_method_says() {
    let recorded = std::fs::read_to_string(
        std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../packages/format/testdata/bridge/session.ndjson"),
    )
    .unwrap();
    let lines: Vec<Value> = recorded
        .lines()
        .filter(|l| !l.trim().is_empty())
        .map(|l| serde_json::from_str(l).unwrap())
        .collect();
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let platform = Rc::new(Fake::default());
    let files = project();
    let (bridge, events) = server(&platform, &files, &listener, |_| {});
    let pump = || {
        platform.bots.tick();
        events.tick();
    };
    let editor = Editor::accept(&listener, None);
    editor.hello(&pump);
    let mut sent = 0;
    for (at, line) in lines.iter().enumerate() {
        let (Some(method), Some(id)) = (line["method"].as_str(), line.get("id")) else {
            continue;
        };
        // The server's own requests, and the backend's (answered to it, not the UI).
        if ["hello", "authenticate"].contains(&method)
            || id.as_str().is_some_and(|i| i.starts_with("backend:"))
        {
            continue;
        }
        let recorded_error = lines[at + 1..]
            .iter()
            .find(|l| l.get("id") == Some(id) && l.get("method").is_none())
            .and_then(|l| l["error"]["code"].as_i64());
        let outcome = editor.request(
            &pump,
            method,
            line.get("params").cloned().unwrap_or(Value::Null),
        );
        match outcome {
            Ok(result) => assert!(
                matches!(bridge::check_result(method, &result), Some(Ok(()))),
                "{method}: {result}"
            ),
            Err((code, message)) => assert!(
                Some(code) == recorded_error || code == bridge::REQUEST_FAILED,
                "{method}: {code} {message}"
            ),
        }
        sent += 1;
    }
    assert!(sent > 25, "{sent} requests");
    bridge.stop();
}
