//! The host's half of the debugger: breakpoints by chunk and line, the stack and what it holds read raw, stepping in,
//! over and out (a call in nested in the stepped one runs through), pausing, breaking on uncaught errors only, the
//! time held left out of the clock, and detaching.

use std::cell::RefCell;
use std::collections::{BTreeSet, HashMap, VecDeque};
use std::rc::Rc;

use netherforge_luau::debug::{DebugConfig, DebugHandler, HandleView, Resume, Stopped};
use netherforge_luau::{Host, Limits, ManualClock, ScopeId};

/// What a stop looked like: why, where, the stack's rows, and the innermost frame's locals.
#[derive(Debug, Clone)]
struct Seen {
    reason: String,
    at: String,
    stack: Vec<String>,
    locals: Vec<(String, String, String)>,
    text: Option<String>,
}

#[derive(Default)]
struct Recorder {
    seen: RefCell<Vec<Seen>>,
    then: RefCell<VecDeque<Resume>>,
    /// What expanding the first local that has entries lists, at each stop.
    expanded: RefCell<Vec<Vec<(String, String)>>>,
    clock: RefCell<Option<ManualClock>>,
}

impl DebugHandler for Recorder {
    fn stopped(&self, stop: &mut Stopped) -> Resume {
        let stack = stop
            .stack()
            .iter()
            .map(|r| format!("{} {}:{}", r.name, r.file, r.line))
            .collect();
        let scopes = stop.scopes(1).expect("scopes");
        let locals = stop.variables(scopes[0].1).expect("locals");
        if let Some(table) = locals.iter().find(|v| v.reference > 0) {
            let inside = stop.variables(table.reference).expect("entries");
            self.expanded
                .borrow_mut()
                .push(inside.into_iter().map(|v| (v.name, v.value)).collect());
        }
        self.seen.borrow_mut().push(Seen {
            reason: stop.reason.clone(),
            at: format!("{}:{}", stop.file, stop.line),
            stack,
            locals: locals
                .into_iter()
                .map(|v| (v.name, v.value, v.kind))
                .collect(),
            text: stop.text.clone(),
        });
        // The editor takes its time: none of it is the script's.
        if let Some(clock) = &*self.clock.borrow() {
            clock.advance(5_000_000_000);
        }
        self.then
            .borrow_mut()
            .pop_front()
            .unwrap_or(Resume::Continue)
    }

    fn describe(&self, _: &mlua::AnyUserData) -> Option<HandleView> {
        None
    }
}

fn host() -> (Host, ScopeId, Rc<Recorder>) {
    let host = Host::new(Limits::default()).unwrap();
    let scope = host.open_scope("module m", "m").unwrap();
    let recorder = Rc::new(Recorder::default());
    host.set_debug_handler(Some(recorder.clone()));
    (host, scope, recorder)
}

fn at(file: &str, lines: &[u32]) -> HashMap<String, BTreeSet<u32>> {
    HashMap::from([(file.to_owned(), lines.iter().copied().collect())])
}

const SCRIPT: &str = "local function add(a, b)
  local sum = a + b
  return sum
end
local t = { 1, 2, name = \"x\" }
local r = add(2, 3)
t.r = r
return t
";

#[test]
fn a_breakpoint_stops_with_the_stack_and_the_locals_read() {
    let (host, scope, recorder) = host();
    host.debug_configure(DebugConfig {
        breakpoints: at("modules/m/init.lua", &[3]),
        ..DebugConfig::default()
    });
    host.run::<()>(scope, 1_000_000, "modules/m/init.lua", SCRIPT)
        .unwrap();
    let seen = recorder.seen.borrow();
    assert_eq!(seen.len(), 1, "{seen:?}");
    assert_eq!(seen[0].reason, "breakpoint");
    assert_eq!(seen[0].at, "modules/m/init.lua:3");
    assert_eq!(
        seen[0].stack,
        ["add modules/m/init.lua:3", "(body) modules/m/init.lua:6"]
    );
    assert_eq!(
        seen[0].locals,
        [
            ("a".into(), "2".into(), "number".into()),
            ("b".into(), "3".into(), "number".into()),
            ("sum".into(), "5".into(), "number".into())
        ]
    );
}

#[test]
fn stepping_over_in_and_out_follows_the_script() {
    let (host, scope, recorder) = host();
    host.debug_configure(DebugConfig {
        breakpoints: at("modules/m/init.lua", &[6]),
        ..DebugConfig::default()
    });
    recorder.then.borrow_mut().extend([
        Resume::StepIn,  // from 6 into add, at 2
        Resume::Next,    // 3
        Resume::StepOut, // back at 6 (the assignment finishing) or 7
        Resume::Next,
        Resume::Continue,
    ]);
    host.run::<()>(scope, 1_000_000, "modules/m/init.lua", SCRIPT)
        .unwrap();
    let at: Vec<String> = recorder
        .seen
        .borrow()
        .iter()
        .map(|s| s.at.clone())
        .collect();
    assert_eq!(at[0], "modules/m/init.lua:6");
    assert_eq!(at[1], "modules/m/init.lua:2");
    assert_eq!(at[2], "modules/m/init.lua:3");
    assert!(
        at[3] == "modules/m/init.lua:6" || at[3] == "modules/m/init.lua:7",
        "{at:?}"
    );
    assert!(at.len() >= 5, "{at:?}");
    // The table local expands to its entries, sequence first.
    let expanded = recorder.expanded.borrow();
    assert!(
        expanded.iter().any(|e| e
            == &[
                ("[1]".to_owned(), "1".to_owned()),
                ("[2]".into(), "2".into()),
                ("name".into(), "\"x\"".into())
            ]),
        "{expanded:?}"
    );
}

#[test]
fn a_step_ends_with_the_call_it_began_in() {
    let (host, scope, recorder) = host();
    host.debug_configure(DebugConfig {
        breakpoints: at("modules/m/init.lua", &[2]),
        ..DebugConfig::default()
    });
    recorder
        .then
        .borrow_mut()
        .extend([Resume::Next, Resume::Next, Resume::Next]);
    let f: mlua::Function = host
        .run(
            scope,
            1_000_000,
            "modules/m/init.lua",
            "return function()\n  local x = 1\n  return x\nend\n",
        )
        .unwrap();
    host.invoke::<()>(scope, 1000, &f, ()).unwrap();
    // A second call in, after the stepped one returned: nothing stops but its breakpoint.
    recorder.then.borrow_mut().clear();
    host.debug_configure(DebugConfig::default());
    host.invoke::<()>(scope, 1000, &f, ()).unwrap();
    let at: Vec<String> = recorder
        .seen
        .borrow()
        .iter()
        .map(|s| s.at.clone())
        .collect();
    assert_eq!(at, ["modules/m/init.lua:2", "modules/m/init.lua:3"]);
}

#[test]
fn pause_stops_at_the_next_line_any_script_runs() {
    let (host, scope, recorder) = host();
    host.debug_configure(DebugConfig {
        pause: true,
        ..DebugConfig::default()
    });
    host.run::<()>(
        scope,
        1_000_000,
        "modules/m/init.lua",
        "local x = 1\nlocal y = 2\n",
    )
    .unwrap();
    let seen = recorder.seen.borrow();
    assert_eq!(seen.len(), 1);
    assert_eq!(
        (seen[0].reason.as_str(), seen[0].at.as_str()),
        ("pause", "modules/m/init.lua:1")
    );
}

#[test]
fn break_on_errors_stops_where_an_uncaught_error_happened_and_it_still_fails() {
    let (host, scope, recorder) = host();
    host.debug_configure(DebugConfig {
        errors: true,
        ..DebugConfig::default()
    });
    let failure = host
        .run::<()>(
            scope,
            1_000_000,
            "modules/m/init.lua",
            "local ok = pcall(error, 'caught')\nlocal function boom(n)\n  error('boom ' .. n)\nend\nboom(4)\n",
        )
        .unwrap_err();
    assert!(
        failure.to_string().contains("modules/m/init.lua:3: boom 4"),
        "{failure}"
    );
    let seen = recorder.seen.borrow();
    assert_eq!(seen.len(), 1, "the pcall's error isn't one: {seen:?}");
    assert_eq!(seen[0].reason, "exception");
    assert_eq!(seen[0].at, "modules/m/init.lua:3");
    assert_eq!(
        seen[0].text.as_deref(),
        Some("modules/m/init.lua:3: boom 4")
    );
    assert_eq!(seen[0].stack[1], "(body) modules/m/init.lua:5");
}

#[test]
fn time_held_at_a_stop_is_no_ones() {
    let clock = ManualClock::new();
    let host = Host::with_clock(Limits::default(), Rc::new(clock.clone())).unwrap();
    let scope = host.open_scope("module m", "m").unwrap();
    let recorder = Rc::new(Recorder::default());
    *recorder.clock.borrow_mut() = Some(clock.clone());
    host.set_debug_handler(Some(recorder.clone()));
    host.debug_configure(DebugConfig {
        breakpoints: at("modules/m/init.lua", &[2]),
        ..DebugConfig::default()
    });
    let before = host.now();
    // Five seconds at the breakpoint, past the one-second time limit, and the call isn't stopped.
    host.run::<()>(
        scope,
        1_000_000,
        "modules/m/init.lua",
        "local x = 1\nlocal y = 2\nlocal z = 3\n",
    )
    .unwrap();
    assert_eq!(recorder.seen.borrow().len(), 1);
    assert!(host.now() - before < 1_000_000_000);
    let costs = host.take_costs(false);
    assert!(costs[&scope].nanos < 1_000_000_000, "{:?}", costs[&scope]);
}

#[test]
fn detaching_forgets_every_breakpoint() {
    let (host, scope, recorder) = host();
    host.debug_configure(DebugConfig {
        breakpoints: at("modules/m/init.lua", &[1, 2]),
        ..DebugConfig::default()
    });
    recorder.then.borrow_mut().push_back(Resume::Detach);
    host.run::<()>(
        scope,
        1_000_000,
        "modules/m/init.lua",
        "local x = 1\nlocal y = 2\n",
    )
    .unwrap();
    assert_eq!(recorder.seen.borrow().len(), 1);
    host.run::<()>(
        scope,
        1_000_000,
        "modules/m/init.lua",
        "local x = 1\nlocal y = 2\n",
    )
    .unwrap();
    assert_eq!(recorder.seen.borrow().len(), 1);
}

#[test]
fn a_coroutine_made_before_the_breakpoint_stops_at_it_too() {
    let (host, scope, recorder) = host();
    let co: mlua::Thread = host
        .run(
            scope,
            1_000_000,
            "modules/m/init.lua",
            "return coroutine.create(function()\n  coroutine.yield()\n  local after = 1\nend)\n",
        )
        .unwrap();
    host.debug_configure(DebugConfig {
        breakpoints: at("modules/m/init.lua", &[3]),
        ..DebugConfig::default()
    });
    let resume: mlua::Function = host
        .run(
            scope,
            1000,
            "modules/m/resume.lua",
            "return coroutine.resume",
        )
        .unwrap();
    host.invoke::<()>(scope, 1000, &resume, co.clone()).unwrap();
    host.invoke::<()>(scope, 1000, &resume, co).unwrap();
    let at: Vec<String> = recorder
        .seen
        .borrow()
        .iter()
        .map(|s| s.at.clone())
        .collect();
    assert_eq!(at, ["modules/m/init.lua:3"]);
}
