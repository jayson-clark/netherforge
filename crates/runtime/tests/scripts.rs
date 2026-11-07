//! Scripts end to end: a room runs module bodies, and the test plays the
//! server's ticks.

use netherforge_luau::mlua::Value;
use netherforge_luau::{Failure, Host, Limits, ScopeId, Stop};
use netherforge_runtime::{Grants, MAX_ERRORS, Room, Target};

const BUDGET: u64 = 1_000_000;

struct Test {
    room: Room,
}

impl Test {
    fn new() -> Test {
        let host = Host::new(Limits::default()).unwrap();
        Test {
            room: Room::new(host, Grants::default()).unwrap(),
        }
    }

    /// A module `name` whose body is `source`, run now.
    fn module(&self, name: &str, source: &str) -> ScopeId {
        let scripts = self.room.scripts();
        let scope = scripts.open(name, name, BUDGET).unwrap();
        let _ = scripts.run_body(scope, &format!("modules/{name}/init.lua"), source);
        scope
    }

    fn ticks(&self, n: usize) {
        for _ in 0..n {
            self.room.scripts().tick();
        }
    }

    /// The global `name` of `scope`.
    fn get<T: netherforge_luau::mlua::FromLua>(&self, scope: ScopeId, name: &str) -> T {
        self.room.host().env(scope).unwrap().get(name).unwrap()
    }

    fn messages(&self) -> Vec<String> {
        self.room
            .scripts()
            .take_reports()
            .into_iter()
            .map(|r| format!("{}: {}", r.what, r.failure))
            .collect()
    }
}

#[test]
fn a_custom_event_reaches_every_handler_in_order() {
    let t = Test::new();
    let a = t.module("a", "heard = {} nf.on('shop:bought', function(e) heard[#heard + 1] = 'a ' .. e.item e.price = 5 end)");
    t.module("b", "nf.on('shop:bought', function(e) e:cancel() end)");
    let c = t.module(
        "c",
        "local payload = { item = 'hat', price = 10 }
cancelled = nf.emit('shop:bought', payload)
price = payload.price",
    );
    assert_eq!(t.get::<Vec<String>>(a, "heard"), ["a hat"]);
    assert!(t.get::<bool>(c, "cancelled"));
    // The payload is handed over as it is: what a handler sets, the caller reads.
    assert_eq!(t.get::<f64>(c, "price"), 5.0);
    assert!(t.messages().is_empty());
}

#[test]
fn nobody_listening_is_nothing_to_cancel() {
    let t = Test::new();
    let s = t.module("s", "cancelled = nf.emit('shop:bought', {})");
    assert!(!t.get::<bool>(s, "cancelled"));
}

#[test]
fn a_built_in_name_cant_be_emitted_or_misspelled() {
    let t = Test::new();
    t.module("s", "nf.emit('tick', {})");
    t.module("u", "nf.on('tikc', function() end)");
    let messages = t.messages();
    assert!(
        messages[0].contains("\"tick\" is a built-in event name"),
        "{messages:?}"
    );
    assert!(
        messages[1].contains("no event \"tikc\" for nf.on (events: "),
        "{messages:?}"
    );
    assert!(messages[1].contains("tick"), "{messages:?}");
}

#[test]
fn tick_handlers_run_every_tick_or_every_few() {
    let t = Test::new();
    let s = t.module(
        "s",
        "every_tick, every_other = 0, 0
nf.on('tick', function() every_tick += 1 end)
nf.on('tick', function() every_other += 1 end, { every = 2 })",
    );
    t.ticks(10);
    assert_eq!(t.get::<f64>(s, "every_tick"), 10.0);
    assert_eq!(t.get::<f64>(s, "every_other"), 5.0);
    t.module("bad", "nf.on('shop:bought', function() end, { every = 2 })");
    assert!(t.messages()[0].contains("option 'every' doesn't apply to the shop:bought event"));
}

#[test]
fn a_failing_handler_is_reported_and_the_rest_still_run() {
    let t = Test::new();
    let s = t.module(
        "s",
        "after = 0
nf.on('tick', function() error('boom') end)
nf.on('tick', function() after += 1 end)",
    );
    t.ticks(1);
    assert_eq!(t.get::<f64>(s, "after"), 1.0);
    assert_eq!(t.messages(), ["tick handler: modules/s/init.lua:2: boom"]);
}

#[test]
fn a_handler_failing_twenty_times_in_a_row_is_cancelled() {
    let t = Test::new();
    t.module("s", "nf.on('tick', function() error('boom') end)");
    t.ticks(MAX_ERRORS as usize + 5);
    let reports = t.room.scripts().take_reports();
    assert_eq!(reports.len(), MAX_ERRORS as usize);
    assert!(reports.last().unwrap().gave_up);
    assert!(!reports[0].gave_up);
}

#[test]
fn once_subscriptions_and_cancelling() {
    let t = Test::new();
    let s = t.module(
        "s",
        "once, many = 0, 0
nf.once('tick', function() once += 1 end)
sub = nf.on('tick', function() many += 1 end)",
    );
    t.ticks(2);
    t.room
        .host()
        .run::<()>(
            s,
            BUDGET,
            "modules/s/init.lua",
            "active = sub:is_active() sub:cancel() still = sub:is_active()",
        )
        .unwrap();
    t.ticks(2);
    assert_eq!(t.get::<f64>(s, "once"), 1.0);
    assert_eq!(t.get::<f64>(s, "many"), 2.0);
    assert!(t.get::<bool>(s, "active"));
    assert!(!t.get::<bool>(s, "still"));
}

#[test]
fn an_events_fields_are_read_only_unless_writable() {
    let t = Test::new();
    t.module("s", "nf.on('tick', function(e) e.name = 'x' end)");
    t.module("u", "nf.on('tick', function(e) e.anything = 1 end)");
    t.module("v", "nf.on('tick', function(e) e:cancel() end)");
    t.ticks(1);
    let messages = t.messages();
    assert!(
        messages[0].contains("event.name can't be assigned"),
        "{messages:?}"
    );
    assert!(
        messages[1].contains(
            "event.anything can't be assigned on a tick event (it has no writable fields)"
        ),
        "{messages:?}"
    );
    assert!(
        messages[2].contains("the tick event can't be cancelled"),
        "{messages:?}"
    );
}

#[test]
fn a_writable_field_is_read_as_it_will_be_read_back() {
    let t = Test::new();
    let s = t.module(
        "s",
        "nf.on('player_join', function(e)
  ok, why = pcall(function() e.message = 5 end)
  kind = type(why)
  e.message = 'welcome'
end)",
    );
    let lua = t.room.host().lua().clone();
    let payload = lua.create_table().unwrap();
    payload.raw_set("first_join", true).unwrap();
    let delivered = t.room.scripts().raise(&Target::Nf, "player_join", payload);
    assert!(!t.get::<bool>(s, "ok"));
    assert_eq!(
        t.get::<String>(s, "kind"),
        "string",
        "a caught mistake is a string"
    );
    assert_eq!(
        t.get::<String>(s, "why"),
        "modules/s/init.lua:2: bad value for event.message (string or nil expected, got number)"
    );
    assert_eq!(
        delivered.fields.raw_get::<String>("message").unwrap(),
        "welcome"
    );
}

#[test]
fn timers_run_when_theyre_due() {
    let t = Test::new();
    let s = t.module(
        "s",
        "fired, repeats = {}, 0
nf.after(2, function() fired[#fired + 1] = 'two' end)
nf.after(1, function() fired[#fired + 1] = 'one' end)
repeating = nf.every(3, function() repeats += 1 end)
cancelled = nf.after(1, function() fired[#fired + 1] = 'never' end)
cancelled:cancel()",
    );
    t.ticks(1);
    assert_eq!(t.get::<Vec<String>>(s, "fired"), ["one"]);
    t.ticks(8);
    assert_eq!(t.get::<Vec<String>>(s, "fired"), ["one", "two"]);
    assert_eq!(t.get::<f64>(s, "repeats"), 3.0);
}

#[test]
fn a_task_waits_and_carries_on() {
    let t = Test::new();
    let s = t.module(
        "s",
        "steps = {}
nf.task(function(greeting)
  steps[#steps + 1] = greeting
  nf.wait(2)
  steps[#steps + 1] = 'waited'
  local ok = nf.wait_until(function() return ready end, 10)
  steps[#steps + 1] = 'until ' .. tostring(ok)
  local event = nf.wait_for(nf, 'door:opened', function(e) return e.who == 'Alex' end)
  steps[#steps + 1] = 'door ' .. event.who
end, 'hello')",
    );
    // A task starts at once.
    assert_eq!(t.get::<Vec<String>>(s, "steps"), ["hello"]);
    t.ticks(1);
    assert_eq!(t.get::<Vec<String>>(s, "steps").len(), 1);
    t.ticks(1);
    assert_eq!(t.get::<Vec<String>>(s, "steps"), ["hello", "waited"]);
    t.room.host().env(s).unwrap().set("ready", true).unwrap();
    t.ticks(1);
    assert_eq!(
        t.get::<Vec<String>>(s, "steps").last().unwrap(),
        "until true"
    );
    t.module(
        "other",
        "nf.emit('door:opened', { who = 'Steve' }) nf.emit('door:opened', { who = 'Alex' })",
    );
    assert_eq!(
        t.get::<Vec<String>>(s, "steps").last().unwrap(),
        "door Alex"
    );
    assert!(t.messages().is_empty());
}

#[test]
fn wait_until_gives_up_at_its_timeout() {
    let t = Test::new();
    let s = t.module(
        "s",
        "nf.task(function() result = nf.wait_until(function() return false end, 3) end)",
    );
    t.ticks(5);
    assert!(!t.get::<bool>(s, "result"));
}

#[test]
fn only_a_tasks_own_code_may_wait() {
    let t = Test::new();
    t.module("body", "nf.wait(1)");
    t.module("handler", "nf.on('tick', function() nf.wait(1) end)");
    // A handler the task's own code sets off runs inside the task, but isn't its code.
    t.module(
        "inner",
        "nf.task(function() nf.on('door:opened', function() nf.wait(1) end) nf.emit('door:opened', {}) end)",
    );
    t.module(
        "sorting",
        "nf.task(function() table.sort({3, 1, 2}, function(a, b) nf.wait(1) return a < b end) end)",
    );
    t.module("yielding", "nf.task(function() coroutine.yield() end)");
    t.ticks(1);
    let messages = t.messages();
    let has = |part: &str| messages.iter().any(|m| m.contains(part));
    assert!(has("nf.wait only works inside a task"), "{messages:?}");
    assert!(
        has("nf.wait can't wait in an event handler, even one a task set off"),
        "{messages:?}"
    );
    assert!(
        has("can't wait inside a function called back from outside Lua"),
        "{messages:?}"
    );
    assert!(has("coroutine.yield can't pause a task"), "{messages:?}");
}

#[test]
fn a_failing_body_disables_what_its_scope_made() {
    let t = Test::new();
    let s = t.module("s", "count = 0 nf.on('tick', function() count += 1 end) nf.after(1, function() count += 100 end) error('broken')");
    t.ticks(3);
    assert_eq!(t.get::<f64>(s, "count"), 0.0);
    assert_eq!(t.messages(), ["body: modules/s/init.lua:1: broken"]);
}

#[test]
fn closing_a_scope_ends_its_tasks_and_handlers() {
    let t = Test::new();
    let s = t.module("s", "nf.task(function() nf.wait(1) error('should never run') end) nf.on('tick', function() error('nor this') end)");
    t.room.scripts().close(s);
    t.ticks(3);
    assert!(t.messages().is_empty());
}

#[test]
fn a_handler_past_its_budget_is_stopped_and_reported() {
    let t = Test::new();
    let scripts = t.room.scripts();
    let scope = scripts.open("s", "s", 10_000).unwrap();
    scripts
        .run_body(
            scope,
            "modules/s/init.lua",
            "nf.on('tick', function()\n  while true do end\nend)",
        )
        .unwrap();
    t.ticks(1);
    let reports = scripts.take_reports();
    assert!(
        matches!(
            &reports[0].failure,
            Failure::Stopped { stop: Stop::Budget { budget: 10_000 }, location: Some(at) } if at.line == 2
        ),
        "{reports:?}"
    );
}

#[test]
fn whats_listened_to_can_be_asked() {
    let t = Test::new();
    assert!(!t.room.scripts().listening(&Target::Nf, "tick"));
    t.module("s", "nf.on('tick', function() end)");
    assert!(t.room.scripts().listening(&Target::Nf, "tick"));
    let left: Value = t
        .room
        .host()
        .run(
            t.module("n", ""),
            5_000,
            "modules/n/init.lua",
            "return nf.instructions_left()",
        )
        .unwrap();
    assert!(
        matches!(left, Value::Integer(n) if n > 4_000 && n <= 5_000)
            || matches!(left, Value::Number(n) if n > 4_000.0 && n <= 5_000.0),
        "{left:?}"
    );
}
