use std::rc::Rc;
use std::time::Duration;

use netherforge_luau::mlua::{Function, Value};
use netherforge_luau::{Failure, Host, Limits, Location, ManualClock, Stop};

const BUDGET: u64 = 100_000;

fn host() -> Host {
    Host::new(Limits::default()).unwrap()
}

fn run(host: &Host, source: &str) -> Result<Value, Failure> {
    let scope = host.open_scope("test", "test").unwrap();
    host.run(scope, BUDGET, "modules/m/init.lua", source)
}

fn stopped_at(result: Result<Value, Failure>) -> (Stop, Option<Location>) {
    match result {
        Err(Failure::Stopped { stop, location }) => (stop, location),
        other => panic!("expected a stop, got {other:?}"),
    }
}

#[test]
fn runs_a_chunk_and_returns_its_values() {
    let host = host();
    let scope = host.open_scope("m", "m").unwrap();
    let n: f64 = host
        .run(scope, BUDGET, "modules/m/init.lua", "return 1 + 2")
        .unwrap();
    assert_eq!(n, 3.0);
}

#[test]
fn errors_name_the_chunk_and_line() {
    let host = host();
    let failure = run(&host, "local x = 1\nerror('boom')").unwrap_err();
    assert_eq!(failure.to_string(), "modules/m/init.lua:2: boom");
    let Failure::Error { traceback, .. } = failure else {
        unreachable!()
    };
    assert!(traceback.unwrap().contains("modules/m/init.lua:2"));
}

#[test]
fn compile_errors_are_failures() {
    let host = host();
    let failure = run(&host, "local = 1").unwrap_err();
    assert!(
        matches!(&failure, Failure::Error { message, .. } if message.contains("modules/m/init.lua:1")),
        "{failure:?}"
    );
}

#[test]
fn each_scope_has_its_own_globals() {
    let host = host();
    let a = host.open_scope("a", "a").unwrap();
    let b = host.open_scope("b", "b").unwrap();
    host.run::<()>(a, BUDGET, "a.lua", "x = 1").unwrap();
    let seen: Value = host.run(b, BUDGET, "b.lua", "return x").unwrap();
    assert_eq!(seen, Value::Nil);
    let own: f64 = host.run(a, BUDGET, "a.lua", "return x").unwrap();
    assert_eq!(own, 1.0);
}

#[test]
fn a_scope_may_shadow_a_library_for_itself() {
    let host = host();
    let a = host.open_scope("a", "a").unwrap();
    let b = host.open_scope("b", "b").unwrap();
    let mine: f64 = host
        .run(
            a,
            BUDGET,
            "a.lua",
            "math = { floor = function() return 42 end }\nreturn math.floor(1.5)",
        )
        .unwrap();
    assert_eq!(mine, 42.0);
    let theirs: f64 = host
        .run(b, BUDGET, "b.lua", "return math.floor(1.5)")
        .unwrap();
    assert_eq!(theirs, 1.0);
}

#[test]
fn the_libraries_are_read_only() {
    let host = host();
    for source in [
        "string.format = nil",
        "rawset(string, 'format', nil)",
        "table.insert = print",
        "getmetatable('').__index = {}",
    ] {
        assert!(run(&host, source).is_err(), "{source} should fail");
    }
    let format: String = run(&host, "return type(string.format)")
        .unwrap()
        .to_string()
        .unwrap();
    assert_eq!(format, "function");
}

#[test]
fn what_scripts_dont_get() {
    let host = host();
    for name in [
        "os",
        "debug",
        "getfenv",
        "setfenv",
        "loadstring",
        "require",
        "collectgarbage",
        "gcinfo",
        "newproxy",
        "print",
        "_G",
        "shared",
    ] {
        let seen = run(&host, &format!("return {name}")).unwrap();
        assert_eq!(seen, Value::Nil, "{name} should be missing");
    }
}

#[test]
fn what_scripts_get_from_luau() {
    let host = host();
    let n: f64 = host
        .run(
            host.open_scope("m", "m").unwrap(),
            BUDGET,
            "m.lua",
            "local v = vector.create(3, 4, 0)\nreturn vector.magnitude(v) + bit32.band(6, 3) + buffer.len(buffer.create(2))",
        )
        .unwrap();
    assert_eq!(n, 5.0 + 2.0 + 2.0);
}

// The known hangs: each is stopped, at the script's line.

#[test]
fn an_endless_loop_is_stopped_at_its_line() {
    let host = host();
    let (stop, location) = stopped_at(run(&host, "local n = 0\nwhile true do n += 1 end"));
    assert_eq!(stop, Stop::Budget { budget: BUDGET });
    assert_eq!(
        location,
        Some(Location {
            chunk: "modules/m/init.lua".into(),
            line: 2
        })
    );
}

#[test]
fn every_kind_of_loop_is_stopped() {
    let host = host();
    for source in [
        "repeat until false",
        "for i = 1, math.huge do end",
        "for _ in function() return 1 end do end",
        "local function f(n) if n > 0 then return f(n - 1) + 1 end return 0 end while true do f(10) end",
        "coroutine.wrap(function() while true do end end)()",
        "local t = {} for i = 1, 100 do t[i] = i end while true do table.sort(t, function(a, b) return a > b end) end",
        "while true do string.gsub('aaaa', 'a', function(c) return c end) end",
        "while true do pcall(error, 'x') end",
    ] {
        let (stop, _) = stopped_at(run(&host, source));
        assert_eq!(stop, Stop::Budget { budget: BUDGET }, "{source}");
    }
}

#[test]
fn endless_recursion_overflows_the_stack() {
    // Luau has no proper tail calls, so this is an error, not a hang.
    let host = host();
    let failure = run(&host, "local function f() return f() end f()").unwrap_err();
    assert!(failure.to_string().contains("stack overflow"), "{failure}");
}

#[test]
fn pcall_cant_catch_a_stop() {
    let host = host();
    let source = "while true do pcall(function() while true do end end) end";
    let (stop, _) = stopped_at(run(&host, source));
    assert_eq!(stop, Stop::Budget { budget: BUDGET });
    // Even a script that catches it and returns normally is reported stopped.
    let caught = "local ok = pcall(function() while true do end end)\nreturn ok";
    stopped_at(run(&host, caught));
}

#[test]
fn the_time_limit_stops_a_call_within_budget() {
    let clock = ManualClock::new();
    clock.step_on_read(1_000_000); // a millisecond passes every look at the clock
    let limits = Limits {
        deadline: Duration::from_millis(50),
        clock_every: 1,
        ..Limits::default()
    };
    let host = Host::with_clock(limits, Rc::new(clock)).unwrap();
    let scope = host.open_scope("m", "m").unwrap();
    let result = host.run::<Value>(scope, u64::MAX, "m.lua", "while true do end");
    let (stop, _) = stopped_at(result);
    assert_eq!(
        stop,
        Stop::Time {
            limit: Duration::from_millis(50)
        }
    );
}

#[test]
fn a_budget_is_capped_by_the_limit() {
    let limits = Limits {
        max_budget: Some(1_000),
        ..Limits::default()
    };
    let host = Host::new(limits).unwrap();
    let scope = host.open_scope("m", "m").unwrap();
    let (stop, _) = stopped_at(host.run(scope, BUDGET, "m.lua", "while true do end"));
    assert_eq!(stop, Stop::Budget { budget: 1_000 });
}

#[test]
fn a_nested_call_in_is_charged_to_its_caller() {
    let host = host();
    let caller = host.open_scope("caller", "caller").unwrap();
    let callee = host.open_scope("callee", "callee").unwrap();
    let spin = host
        .load(callee, "callee.lua", "for i = 1, 600 do end")
        .unwrap();
    // A Rust function the caller can call, which calls into the callee.
    let weak = host.downgrade();
    let call_in = host
        .lua()
        .create_function(move |_, ()| {
            let host = weak.upgrade().expect("the host is alive");
            host.invoke::<()>(callee, 1_000, &spin, ())
                .map_err(netherforge_luau::mlua::Error::external)
        })
        .unwrap();
    host.env(caller)
        .unwrap()
        .raw_set("call_in", call_in)
        .unwrap();
    // Each call in fits its own budget of 1000, but two of them don't fit the
    // caller's.
    let f: Function = host
        .load(caller, "caller.lua", "call_in() call_in()")
        .unwrap();
    let (stop, _) = stopped_at(host.invoke(caller, 1_000, &f, ()));
    assert_eq!(stop, Stop::Budget { budget: 1_000 });
    let once: Function = host.load(caller, "caller.lua", "call_in()").unwrap();
    host.invoke::<()>(caller, 1_000, &once, ()).unwrap();
}

// Memory.

fn small_host() -> Host {
    Host::new(Limits {
        memory_bytes: 32 * 1024 * 1024,
        ..Limits::default()
    })
    .unwrap()
}

#[test]
fn memory_is_charged_to_the_scope_that_allocated_it() {
    let host = small_host();
    let holder = host.open_scope("holder", "holder").unwrap();
    let idle = host.open_scope("idle", "idle").unwrap();
    host.run::<()>(
        holder,
        BUDGET,
        "h.lua",
        "held = string.rep('x', 4 * 1024 * 1024)",
    )
    .unwrap();
    let held = host.scope_memory(holder).unwrap();
    assert!(held >= 4 * 1024 * 1024, "held {held}");
    assert!(host.scope_memory(idle).unwrap() < 64 * 1024);
    // Letting go of it credits the scope back once it's collected.
    host.run::<()>(holder, BUDGET, "h.lua", "held = nil")
        .unwrap();
    host.lua().gc_collect().unwrap();
    assert!(host.scope_memory(holder).unwrap() < 1024 * 1024);
}

#[test]
fn churning_garbage_isnt_a_leak() {
    let host = small_host();
    let scope = host.open_scope("churn", "churn").unwrap();
    let source = "for i = 1, 2000 do local t = table.create(1000, i) end";
    for _ in 0..20 {
        host.run::<()>(scope, 10_000_000, "c.lua", source).unwrap();
    }
    host.lua().gc_collect().unwrap();
    assert!(host.scope_memory(scope).unwrap() < 1024 * 1024);
}

#[test]
fn running_out_of_memory_fails_the_call_and_blames_the_holder() {
    let host = small_host();
    let hog = host.open_scope("hog", "hog").unwrap();
    let result = host.run::<()>(
        hog,
        u64::MAX,
        "hog.lua",
        "kept = {} for i = 1, 1e9 do kept[i] = string.rep('x', 1000) .. i end",
    );
    assert_eq!(result.unwrap_err(), Failure::Memory);
    assert_eq!(host.take_blamed(), Some(hog));
    assert_eq!(host.take_blamed(), None);
}

#[test]
fn instances_of_one_script_share_its_memory_key() {
    let host = small_host();
    let one = host.open_scope("tower 1", "centities/tower").unwrap();
    let two = host.open_scope("tower 2", "centities/tower").unwrap();
    host.run::<()>(one, BUDGET, "t.lua", "held = string.rep('x', 1024 * 1024)")
        .unwrap();
    host.run::<()>(two, BUDGET, "t.lua", "held = string.rep('y', 1024 * 1024)")
        .unwrap();
    let both = host.scope_memory(one).unwrap();
    assert!(both >= 2 * 1024 * 1024, "both {both}");
    assert_eq!(host.scope_memory(two), Some(both));
}
