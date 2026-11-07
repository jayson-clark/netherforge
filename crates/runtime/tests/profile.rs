//! The profiler and what scripts cost (Kotlin's `ProfilerTest` and `ScriptCostsTest`'s heart): exact time per tick
//! step, scope and function, a sample once a second, `/nf profile` writing a report, a recording across a full
//! reload; slow scripts warned about, and a scope over its tick's time waiting for the next. A clock that moves 1 ms
//! every time it's read makes every call in cost exactly 1 ms of its own.

mod support;
use support::*;

use std::rc::Rc;

use netherforge_format::bridge::{HandlerTime, ProfileSample, SourceRef};
use netherforge_luau::ManualClock;
use netherforge_platform::PlatformEvents;
use netherforge_runtime::admin::AdminCommand;
use netherforge_runtime::profile::Profiler;
use netherforge_runtime::server::ServerEvents;
use netherforge_runtime::session::Config;

const MILLI: i64 = 1_000_000;

const SCRIPTED: &str = r#"{ "nodes": { "root": { "display": { "type": "block", "block": "minecraft:stone" }, "hitbox": {} } }, "script": { "file": "script.lua" } }"#;

struct Server {
    platform: Rc<Fake>,
    events: Rc<ServerEvents<'static>>,
    files: &'static Files,
    profiles: std::path::PathBuf,
    samples: Rc<RefCell<Vec<ProfileSample>>>,
}

fn temp(name: &str) -> std::path::PathBuf {
    std::env::temp_dir().join(format!("nf-{name}-{}-{}", std::process::id(), {
        static NEXT: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
        NEXT.fetch_add(1, std::sync::atomic::Ordering::SeqCst)
    }))
}

/// A server running `files` on a clock that moves 1 ms each read; `config` as given, the clock added.
fn server_with(files: &[(&str, &str)], config: Config) -> Server {
    let platform = Rc::new(Fake::default());
    *platform.player.borrow_mut() = Some(place(0.0, 64.0, 0.0));
    let mut all = vec![("netherforge.json", MANIFEST)];
    all.extend_from_slice(files);
    let files: &'static Files = Box::leak(Box::new(Files::new(&all)));
    let clock = ManualClock::new();
    clock.step_on_read(MILLI as u64);
    let session = Session::start_configured(
        platform.clone(),
        files,
        &NoPackages,
        Rc::new(netherforge_runtime::store::Store::in_memory()),
        Config {
            clock: Some(Rc::new(clock)),
            ..config
        },
    );
    let events = Rc::new(ServerEvents::new(session));
    let profiles = temp("profiles");
    events.set_profiler(Rc::new(Profiler::new(
        profiles.clone(),
        "Minecraft 26.3, NetherForge 0.0.0".into(),
    )));
    AdminCommand::register(&events, temp("state")).expect("registered");
    Server {
        platform,
        events,
        files,
        profiles,
        samples: Rc::default(),
    }
}

fn server(files: &[(&str, &str)]) -> Server {
    server_with(files, Config::default())
}

impl Server {
    /// The editor subscribes: every sample is kept.
    fn stream(&self) {
        let samples = self.samples.clone();
        self.events.profiler().set_sink(Some(Box::new(move |s| {
            samples.borrow_mut().push(s.clone())
        })));
    }

    fn tick(&self, n: usize) {
        for _ in 0..n {
            self.events.tick();
        }
    }

    fn samples(&self) -> Vec<ProfileSample> {
        self.samples.borrow().clone()
    }
}

fn at<'h>(handlers: &'h [HandlerTime], file: &str, line: Option<i32>) -> &'h HandlerTime {
    let want = SourceRef {
        file: file.into(),
        line,
    };
    let found: Vec<&HandlerTime> = handlers
        .iter()
        .filter(|h| h.source.as_ref() == Some(&want))
        .collect();
    assert_eq!(found.len(), 1, "{file}:{line:?} in {handlers:#?}");
    found[0]
}

#[test]
fn each_function_scripts_run_is_timed_by_what_it_is_and_where_it_starts() {
    let s = server(&[(
        "modules/shop/init.lua",
        r#"nf.on("tick", function()
end)
nf.every(5, function()
end)
nf.after(3, function()
end)
nf.task(function()
  while true do nf.wait(10) end
end)
nf.commands.register("shop", function(event)
end)
"#,
    )]);
    s.stream();
    s.tick(20);
    command(&s.platform, Some(alex()), "shop");
    s.tick(20);
    let samples = s.samples();
    let (first, second) = (&samples[0], &samples[1]);
    assert_eq!(
        first.ticks.iter().map(|t| t.tick).collect::<Vec<_>>(),
        (1..=20).collect::<Vec<_>>()
    );
    // Every handler, timer and task resume is 1 ms of its own.
    assert_eq!(
        *at(&first.handlers, "modules/shop/init.lua", Some(1)),
        HandlerTime {
            script: "module shop".into(),
            kind: "tick".into(),
            source: Some(SourceRef {
                file: "modules/shop/init.lua".into(),
                line: Some(1)
            }),
            calls: 20,
            nanos: 20 * MILLI,
            max: MILLI,
            scopes: 1,
        }
    );
    let every = at(&first.handlers, "modules/shop/init.lua", Some(3));
    assert_eq!((every.kind.as_str(), every.calls), ("timer", 4));
    let after = at(&first.handlers, "modules/shop/init.lua", Some(5));
    assert_eq!(
        (after.kind.as_str(), after.calls, after.nanos),
        ("timer", 1, MILLI)
    );
    // The task's resumes are its own kind (the resume isn't project code, so where it is isn't said).
    assert!(
        first
            .handlers
            .iter()
            .any(|h| h.kind == "task" && h.calls == 2),
        "{:#?}",
        first.handlers
    );
    // The command is timed as its handler.
    assert_eq!(
        at(&second.handlers, "modules/shop/init.lua", Some(10)).kind,
        "command"
    );

    // The scope's own time per tick, and each tick's steps.
    assert_eq!(first.scopes.len(), 1);
    assert_eq!(first.scopes[0].scope, "module shop");
    let tick = &first.ticks[4];
    assert_eq!(
        tick.phases.keys().collect::<Vec<_>>(),
        [
            "timers", "async", "events", "world", "effects", "upkeep", "accounts", "save"
        ]
    );
    assert_eq!(tick.top.len(), 1);
    assert_eq!(tick.top[0].scope, "module shop");
    assert_eq!(tick.scripts, tick.top[0].nanos);
    assert!(tick.nanos >= tick.phases.values().sum::<i64>(), "{tick:?}");
    // What the steps are made of: each service's share, within its phase (the modules' is upkeep's).
    let modules = tick
        .parts
        .get("services/modules")
        .expect("the modules' part");
    assert!(*modules <= tick.phases["upkeep"], "{tick:?}");
}

#[test]
fn instances_of_a_centity_share_their_handlers_rows_each_a_scope_of_its_own() {
    let s = server(&[
        ("centities/box/centity.json", SCRIPTED),
        (
            "centities/box/script.lua",
            r#"this:on("tick", function() end)"#,
        ),
    ]);
    s.stream();
    let a = s
        .events
        .session()
        .spawn("box", place(0.0, 64.0, 0.0), 0.0)
        .unwrap();
    let b = s
        .events
        .session()
        .spawn("box", place(4.0, 64.0, 0.0), 0.0)
        .unwrap();
    s.tick(20);
    let sample = &s.samples()[0];
    let tick = at(&sample.handlers, "centities/box/script.lua", Some(1));
    assert_eq!(tick.script, "centity box");
    assert_eq!((tick.scopes, tick.calls), (2, 40));
    let short = |id| netherforge_runtime::events::uuid_text(&id)[..8].to_owned();
    let mut scopes: Vec<String> = sample.scopes.iter().map(|s| s.scope.clone()).collect();
    scopes.sort();
    let mut want = vec![
        format!("centity box {}", short(a)),
        format!("centity box {}", short(b)),
    ];
    want.sort();
    assert_eq!(scopes, want);
}

#[test]
fn a_scope_that_closes_before_the_batch_ends_keeps_its_time() {
    let s = server(&[("modules/a/init.lua", r#"nf.on("tick", function() end)"#)]);
    s.stream();
    s.tick(5);
    s.files.write(
        "modules/a/init.lua",
        r#"nf.on("tick", function() end) -- again"#,
    );
    s.events
        .session_mut()
        .reload(&["modules/a/init.lua".to_owned()]);
    s.tick(15);
    let sample = &s.samples()[0];
    // The old scope's 5 ticks and the new one's 15, in one row: same script, same place.
    assert_eq!(
        at(&sample.handlers, "modules/a/init.lua", Some(1)).calls,
        20
    );
    // The new body, timed as the module's file.
    let body = at(&sample.handlers, "modules/a/init.lua", None);
    assert_eq!(
        (body.kind.as_str(), body.calls, body.nanos),
        ("load", 1, MILLI)
    );
}

#[test]
fn a_production_server_measures_nothing_until_asked() {
    let s = server(&[("modules/a/init.lua", r#"nf.on("tick", function() end)"#)]);
    assert!(!s.events.profiler().active());
    s.tick(20);
    let session = s.events.session();
    assert!(session.room().unwrap().host().take_profile().is_empty());
}

#[test]
fn nf_profile_records_for_that_long_writes_a_report_and_says_where() {
    let s = server(&[(
        "modules/a/init.lua",
        r#"nf.on("tick", function()
  local n = 0
  for i = 1, 1000 do n = n + i end
end)"#,
    )]);
    s.tick(7);
    let nf = |line: &str| command(&s.platform, Some(alex()), line);
    assert_eq!(
        nf("nf profile 2"),
        ["<gold>Profiling for 2 s<gray>; the report goes into the plugin's profiles folder."]
    );
    assert!(s.events.profiler().active());
    // One at a time.
    assert_eq!(
        nf("nf profile 5"),
        ["<red>a profile is already running (2 s left); wait for its report"]
    );
    assert_eq!(
        nf("nf profile"),
        ["<gray>/nf profile <seconds> (one is recording: 2 s left)"]
    );
    assert_eq!(
        nf("nf profile soon"),
        ["<red>say how many seconds to profile: /nf profile 30"]
    );

    s.tick(40);
    s.events.profiler().await_writes();
    assert!(!s.events.profiler().active());

    let mut names: Vec<String> = std::fs::read_dir(&s.profiles)
        .unwrap()
        .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
        .collect();
    names.sort();
    assert_eq!(names.len(), 2, "{names:?}");
    let text = s
        .profiles
        .join(names.iter().find(|n| n.ends_with(".txt")).unwrap());
    let calls = s.platform.take();
    assert!(
        calls
            .iter()
            .any(|c| c.starts_with(&format!("info Profile written to {}", text.display()))),
        "{calls:?}"
    );

    let report: serde_json::Value = serde_json::from_str(
        &std::fs::read_to_string(
            s.profiles
                .join(names.iter().find(|n| n.ends_with(".json")).unwrap()),
        )
        .unwrap(),
    )
    .unwrap();
    assert_eq!(report["seconds"], 2);
    // The ticks after the command, 40 of them.
    let ticks: Vec<i64> = report["ticks"]
        .as_array()
        .unwrap()
        .iter()
        .map(|t| t["tick"].as_i64().unwrap())
        .collect();
    assert_eq!(ticks, (8..=47).collect::<Vec<_>>());
    let handlers = report["handlers"].as_array().unwrap();
    assert_eq!(handlers.len(), 1, "{handlers:?}");
    assert_eq!(handlers[0]["calls"], 40);
    assert_eq!(
        handlers[0]["source"],
        serde_json::json!({ "file": "modules/a/init.lua", "line": 1 })
    );
    let summary = std::fs::read_to_string(&text).unwrap();
    assert!(
        summary.starts_with("NetherForge profile: 2 s from "),
        "{summary}"
    );
    assert!(
        summary.contains("module a, tick, modules/a/init.lua:1"),
        "{summary}"
    );

    // Afterwards nothing is measured.
    s.tick(5);
    let session = s.events.session();
    assert!(session.room().unwrap().host().take_profile().is_empty());
}

#[test]
fn a_recording_goes_on_across_a_full_reload() {
    let s = server(&[("modules/a/init.lua", r#"nf.on("tick", function() end)"#)]);
    let said = Rc::new(RefCell::new(None::<String>));
    let into = said.clone();
    s.events
        .profiler()
        .record(0, 1, Box::new(move |m| *into.borrow_mut() = Some(m)))
        .unwrap();
    s.tick(10);
    s.events.session_mut().restart();
    s.tick(10);
    s.events.profiler().await_writes();
    let written = said.borrow().clone().expect("told");
    assert!(written.contains("profile-"), "{written}");
    let text = std::fs::read_dir(&s.profiles)
        .unwrap()
        .map(|e| e.unwrap().path())
        .find(|p| p.extension().is_some_and(|e| e == "txt"))
        .unwrap();
    let text = std::fs::read_to_string(text).unwrap();
    // Ten ticks on each Lua state.
    assert!(text.contains("       20 "), "{text}");
}

#[test]
fn a_slow_script_is_warned_about_once_and_a_busy_one_waits_for_the_next_tick() {
    let s = server_with(
        &[(
            "modules/slow/init.lua",
            r#"local n = 0
nf.on("tick", function() n = n + 1 end)
nf.every(1, function() print("timer " .. n) end)"#,
        )],
        Config {
            performance: Some(netherforge_runtime::config::PerformanceConfig {
                warn_millis: 0.5,
                warn_ticks: 5,
                max_tick_millis: 1.5,
            }),
            ..Config::default()
        },
    );
    s.tick(30);
    let calls = s.platform.take();
    let slow: Vec<&String> = calls.iter().filter(|c| c.contains("is slow")).collect();
    assert_eq!(slow.len(), 1, "{calls:?}");
    assert!(
        slow[0].starts_with("warn module slow is slow: ")
            && slow[0].contains("over the 0.5 ms limit; see /nf scripts"),
        "{slow:?}"
    );
    assert!(
        calls
            .iter()
            .any(|c| c.starts_with("warn module slow ran more than 1.5 ms in a tick")),
        "{calls:?}"
    );
    let problems = s.events.session().problems();
    assert!(
        problems
            .iter()
            .any(|p| p.code.as_deref() == Some("script.slow")),
        "{problems:?}"
    );
}
