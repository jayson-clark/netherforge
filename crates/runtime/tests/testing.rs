//! Script test runs (`*_test.lua`, `nf.test`) on the Rust runtime: a project's tests found and run, each on a
//! fresh server (a fake platform and a session with a harness), and reported as Kotlin's runner reports them.

use std::cell::RefCell;
use std::collections::{BTreeMap, BTreeSet};
use std::rc::{Rc, Weak};

use netherforge_format::package::NoPackages;
use netherforge_format::project::ProjectSource;
use netherforge_platform::{
    FakePlatform, FakePlatformLog, ItemData, PlatformError, PlatformEvents, PlatformLog, PlayerRef,
    ServerInfo, Uuid, game_event,
};
use netherforge_runtime::server::ServerEvents;
use netherforge_runtime::session::{Config, Session};
use netherforge_runtime::testing::TestHarness;
use netherforge_runtime::testing::runner::{Runner, TestEvent, TestServer, TextReport, junit};

#[derive(Default)]
struct Fake {
    lines: RefCell<Vec<String>>,
    online: RefCell<Vec<PlayerRef>>,
    held: RefCell<BTreeMap<String, ItemData>>,
}

impl FakePlatformLog for Fake {
    fn info(&self, message: String) {
        self.lines.borrow_mut().push(format!("info {message}"));
    }
    fn warn(&self, message: String) {
        self.lines.borrow_mut().push(format!("warn {message}"));
    }
    fn error(&self, message: String, _: Option<PlatformError>) {
        self.lines.borrow_mut().push(format!("error {message}"));
    }
}

impl netherforge_platform::FakeTeamOps for Fake {
    fn names(&self) -> Vec<String> {
        Vec::new()
    }
    fn clear_below_names(&self) {}
}

impl netherforge_platform::FakePlayerOps for Fake {
    fn online(&self) -> Vec<PlayerRef> {
        self.online.borrow().clone()
    }
    fn get(&self, uuid: Uuid) -> Option<PlayerRef> {
        self.online
            .borrow()
            .iter()
            .find(|p| p.uuid == uuid)
            .cloned()
    }
}

impl netherforge_platform::FakeWorldEntityOps for Fake {
    fn equipment(&self, _: Uuid, slot: String) -> Option<ItemData> {
        self.held.borrow().get(&slot).cloned()
    }
}

impl netherforge_platform::FakeItemViewOps for Fake {
    fn refresh(&self, _: Uuid) -> bool {
        true
    }
}

impl netherforge_platform::FakePermissionOps for Fake {
    fn apply(&self, _: Uuid, _: std::collections::HashMap<String, bool>) {}
}

impl FakePlatform for Fake {
    fn players(&self) -> &dyn netherforge_platform::PlayerOps {
        self
    }
    fn world_entities(&self) -> &dyn netherforge_platform::WorldEntityOps {
        self
    }
    fn permissions(&self) -> &dyn netherforge_platform::PermissionOps {
        self
    }
    fn item_views(&self) -> &dyn netherforge_platform::ItemViewOps {
        self
    }
    fn teams(&self) -> &dyn netherforge_platform::TeamOps {
        self
    }
    fn info(&self) -> ServerInfo {
        ServerInfo {
            minecraft_version: "26.3".into(),
            plugin_version: "0.0.0".into(),
            supported_targets: vec!["26.3".into()],
        }
    }
    fn log(&self) -> &dyn PlatformLog {
        self
    }
}

struct Files {
    names: BTreeSet<String>,
    texts: BTreeMap<String, String>,
}

impl ProjectSource for Files {
    fn files(&self) -> &BTreeSet<String> {
        &self.names
    }
    fn read(&self, path: &str) -> Option<String> {
        self.texts.get(path).cloned()
    }
}

const MANIFEST: &str = r#"{ "formatVersion": 1, "name": "Test", "namespace": "test", "version": "1.0.0", "minecraft": "26.3" }"#;

/// A project of `files` (the manifest added), kept for the run: a test server's session borrows it.
fn project(files: &[(&str, &str)]) -> &'static Files {
    let mut texts: BTreeMap<String, String> = files
        .iter()
        .map(|(p, t)| (p.to_string(), t.to_string()))
        .collect();
    texts
        .entry("netherforge.json".into())
        .or_insert_with(|| MANIFEST.into());
    Box::leak(Box::new(Files {
        names: texts.keys().cloned().collect(),
        texts,
    }))
}

/// The fake server's side of a test run: its clock is the runtime's tick, its players the fake's.
struct Harness {
    platform: Rc<Fake>,
    events: RefCell<Weak<ServerEvents<'static>>>,
}

fn uuid_of(name: &str) -> Uuid {
    Uuid(
        name.bytes()
            .fold(7u128, |h, b| h.wrapping_mul(31).wrapping_add(b as u128)),
    )
}

impl TestHarness for Harness {
    fn tick(&self) {
        if let Some(events) = self.events.borrow().upgrade() {
            events.tick();
        }
    }

    fn join(&self, name: &str) -> Option<PlayerRef> {
        if let Some(p) = self
            .platform
            .online
            .borrow()
            .iter()
            .find(|p| p.name == name)
        {
            return Some(p.clone());
        }
        let player = PlayerRef {
            uuid: uuid_of(name),
            name: name.into(),
        };
        self.platform.online.borrow_mut().push(player.clone());
        let events = self.events.borrow().upgrade()?;
        events.game().player_join(&mut game_event::PlayerJoin {
            player: player.clone(),
            first_join: true,
            message: Some(format!("{name} joined the game")),
        });
        Some(player)
    }

    fn shown(&self, _: Uuid, _: Option<i32>, _: bool) -> Option<ItemData> {
        None
    }

    fn events(&self) -> Option<Rc<dyn PlatformEvents>> {
        let events: Rc<dyn PlatformEvents> = self.events.borrow().upgrade()?;
        Some(events)
    }
}

/// One test's server.
struct Server {
    platform: Rc<Fake>,
    events: Rc<ServerEvents<'static>>,
}

impl TestServer for Server {
    fn events(&self) -> &ServerEvents<'static> {
        &self.events
    }
}

fn server(files: &'static Files) -> Server {
    let platform = Rc::new(Fake::default());
    let harness = Rc::new(Harness {
        platform: platform.clone(),
        events: RefCell::default(),
    });
    let session = Session::start_configured(
        platform.clone(),
        files,
        &NoPackages,
        Rc::new(netherforge_runtime::store::Store::in_memory()),
        Config {
            testing: Some(harness.clone()),
            ..Config::default()
        },
    );
    let events = Rc::new(ServerEvents::new(session));
    *harness.events.borrow_mut() = Rc::downgrade(&events);
    Server { platform, events }
}

/// Every event of a run, as JSON lines, durations zeroed.
fn run(files: &'static Files, filter: Option<&str>) -> Vec<TestEvent> {
    let start = || server(files);
    let runner = Runner {
        project: "/p".into(),
        version: "dev".into(),
        start: &start,
    };
    let mut events = Vec::new();
    let finished = runner.run(filter, &mut |e| events.push(e.clone()));
    if let Err(refused) = finished {
        events.push(TestEvent::Failed {
            message: refused.message,
            problems: refused.problems,
        });
    }
    for event in &mut events {
        match event {
            TestEvent::Result(r) => r.duration_millis = 0,
            TestEvent::Finished(f) => f.duration_millis = 0,
            _ => {}
        }
    }
    events
}

const GREETER: &str = r#"
local M = { joined = 0 }
function M.hello() return "hello" end
nf.on("player_join", function(e) M.joined = M.joined + 1 end)
nf.on("player_chat", function(e)
  if e.message == "boom" then error("it went boom") end
  e.message = string.upper(e.message)
end)
return M
"#;

const TESTS: &str = r#"local greeter = require("greeter")
local made = 0
made = made + 1

nf.test.case("greets", function()
  assert(greeter.hello() == "hello")
  assert(made == 1, "the file's code runs once per test")
end)

nf.test.case("fails at its line", function()
  assert(1 == 2, "one isn't two")
end)

nf.test.case("time passes", function()
  local count = 0
  nf.every(1, function() count = count + 1 end)
  nf.test.advance(5)
  print("counted", count)
  assert(count == 5, "count " .. count)
end)

nf.test.case("players join and chat", function()
  local alex = nf.test.player("Alex")
  assert(nf.test.player("Alex") == alex, "a name already joined is that player")
  assert(greeter.joined == 1, "joined " .. greeter.joined)
  local raised = nf.test.raise("player_chat", { player = alex, message = "hi", format = "<player>: <message>" })
  assert(raised.event.message == "HI", raised.event.message)
  assert(raised.cancelled == false)
end)

nf.test.case("a script's error fails it", function()
  local alex = nf.test.player("Alex")
  nf.test.raise("player_chat", { player = alex, message = "boom", format = "x" })
end)

nf.test.case("a terrain stood in for", function()
  local terrain = nf.test.terrain({ noises = { ranges = 0.9 }, height = function(x, z) return x + 0.5 end })
  assert(terrain.noise("ranges"):at(1, 2, 3) == 0.9)
  assert(terrain.noise("other"):at(1, 2, 3) == 0)
  assert(terrain.height(70, 0) == 70)
  assert(terrain.biome(0, 0, 0) == "minecraft:plains")
  local plan = terrain.plan("towns", 16, function(x, z) return { x = x, z = z } end)
  local cell, cx = plan:at(40, 3)
  assert(cell.x == 2 and cx == 2 and plan:get(2, 0) == cell)
end)
"#;

#[test]
fn a_projects_tests_run_each_on_a_server_of_its_own() {
    let files = project(&[
        ("modules/greeter/init.lua", GREETER),
        ("tests/greeter_test.lua", TESTS),
        (
            "tests/broken_test.lua",
            "nf.test.case('first', function() end)\nerror('not finished')",
        ),
    ]);
    let events = run(files, None);
    let lines: Vec<String> = events.iter().map(TestEvent::json).collect();
    assert_eq!(
        lines,
        [
            r#"{"event":"start","project":"/p","version":"dev"}"#,
            r#"{"event":"file","file":"tests/broken_test.lua","tests":["first"],"message":"tests/broken_test.lua:2: not finished","source":{"file":"tests/broken_test.lua","line":2}}"#,
            r#"{"event":"result","file":"tests/broken_test.lua","name":"(running tests/broken_test.lua)","status":"errored","durationMillis":0,"message":"tests/broken_test.lua:2: not finished","source":{"file":"tests/broken_test.lua","line":2},"traceback":"\ttests/broken_test.lua:2: in function <tests/broken_test.lua:1>"}"#,
            r#"{"event":"file","file":"tests/greeter_test.lua","tests":["greets","fails at its line","time passes","players join and chat","a script's error fails it","a terrain stood in for"]}"#,
            r#"{"event":"result","file":"tests/greeter_test.lua","name":"greets","status":"passed","durationMillis":0}"#,
            r#"{"event":"result","file":"tests/greeter_test.lua","name":"fails at its line","status":"failed","durationMillis":0,"message":"tests/greeter_test.lua:11: one isn't two","source":{"file":"tests/greeter_test.lua","line":11},"traceback":"\ttests/greeter_test.lua:11: in function <tests/greeter_test.lua:10>"}"#,
            r#"{"event":"result","file":"tests/greeter_test.lua","name":"time passes","status":"passed","durationMillis":0,"logs":["tests/greeter_test.lua:18: counted\t5"]}"#,
            r#"{"event":"result","file":"tests/greeter_test.lua","name":"players join and chat","status":"passed","durationMillis":0}"#,
            r#"{"event":"result","file":"tests/greeter_test.lua","name":"a script's error fails it","status":"errored","durationMillis":0,"message":"a script failed while the test ran: module greeter: player_chat handler failed: it went boom","source":{"file":"modules/greeter/init.lua","line":6},"traceback":"\tmodules/greeter/init.lua:6: in function <modules/greeter/init.lua:5>\n\ttests/greeter_test.lua:33: in function <tests/greeter_test.lua:31>"}"#,
            r#"{"event":"result","file":"tests/greeter_test.lua","name":"a terrain stood in for","status":"passed","durationMillis":0}"#,
            r#"{"event":"end","passed":4,"failed":1,"errored":2,"durationMillis":0}"#,
        ]
    );

    let mut text = TextReport::default();
    let printed: Vec<String> = events.iter().flat_map(|e| text.lines(e)).collect();
    assert_eq!(
        printed,
        [
            "tests/broken_test.lua",
            "  ERROR  (running tests/broken_test.lua) (0 ms)",
            "        tests/broken_test.lua:2: not finished",
            "tests/greeter_test.lua",
            "  PASS  greets (0 ms)",
            "  FAIL  fails at its line (0 ms)",
            "        tests/greeter_test.lua:11: one isn't two",
            "  PASS  time passes (0 ms)",
            "  PASS  players join and chat (0 ms)",
            "  ERROR  a script's error fails it (0 ms)",
            "        modules/greeter/init.lua:6: a script failed while the test ran: module greeter: player_chat handler failed: it went boom",
            "  PASS  a terrain stood in for (0 ms)",
            "",
            "4 passed, 1 failed, 2 errored (0.0 s)",
        ]
    );

    let results: Vec<_> = events
        .iter()
        .filter_map(|e| match e {
            TestEvent::Result(r) => Some(r.clone()),
            _ => None,
        })
        .filter(|r| r.file == "tests/broken_test.lua" || r.name == "time passes")
        .collect();
    assert_eq!(
        junit(&results, 0),
        r#"<?xml version="1.0" encoding="UTF-8"?>
<testsuites tests="2" failures="0" errors="1" time="0.000">
  <testsuite name="tests/broken_test.lua" tests="1" failures="0" errors="1" time="0.000">
    <testcase classname="tests/broken_test.lua" name="(running tests/broken_test.lua)" time="0.000">
      <error message="tests/broken_test.lua:2: not finished">tests/broken_test.lua:2
	tests/broken_test.lua:2: in function &lt;tests/broken_test.lua:1&gt;</error>
    </testcase>
  </testsuite>
  <testsuite name="tests/greeter_test.lua" tests="1" failures="0" errors="0" time="0.000">
    <testcase classname="tests/greeter_test.lua" name="time passes" time="0.000">
      <system-out>tests/greeter_test.lua:18: counted	5</system-out>
    </testcase>
  </testsuite>
</testsuites>
"#
    );
}

#[test]
fn a_filter_picks_tests_by_file_and_name() {
    let files = project(&[
        ("modules/greeter/init.lua", GREETER),
        ("tests/greeter_test.lua", TESTS),
    ]);
    let events = run(files, Some("GREETER_TEST.lua: greets"));
    let names: Vec<String> = events
        .iter()
        .filter_map(|e| match e {
            TestEvent::Result(r) => Some(r.name.clone()),
            _ => None,
        })
        .collect();
    assert_eq!(names, ["greets"]);
}

#[test]
fn a_project_that_cant_run_runs_no_test() {
    let files = project(&[(
        "netherforge.json",
        r#"{ "formatVersion": 1, "name": "Test", "namespace": "test", "version": "1.0.0", "minecraft": "1.8" }"#,
    )]);
    let events = run(files, None);
    let [TestEvent::Failed { message, .. }] = &events[..] else {
        panic!("{events:?}");
    };
    assert!(message.starts_with("the project can't run: "), "{message}");
}

#[test]
fn mistakes_are_errors_at_the_tests_line() {
    let files = project(&[(
        "tests/m_test.lua",
        r#"
nf.test.case("dup", function() end)
print(pcall(nf.test.case, "dup", function() end))
print(pcall(nf.test.case, " ", function() end))
nf.test.case("late", function()
  print(pcall(nf.test.case, "inner", function() end))
  print(pcall(nf.test.advance, 0))
  print(pcall(nf.test.player, "no"))
  print(pcall(nf.test.raise, "nope"))
  print(pcall(nf.test.raise, "player_chat"))
  print(pcall(nf.test.raise, "world_dusk", { world = "w" }))
  print(pcall(nf.test.raise, "player_chat", { player = 3 }))
  print(pcall(nf.test.terrain, 3))
end)
"#,
    )]);
    let server = server(files);
    let session = server.events().session();
    let loaded = session.load_tests("tests/m_test.lua");
    assert_eq!(loaded.names, ["dup", "late"]);
    assert_eq!(
        session.run_test("late"),
        netherforge_runtime::testing::TestOutcome::Passed
    );
    let (_, mut logs) = session.take_test_record();
    let raised = logs.remove(5);
    assert!(raised.ends_with("expected, got \"nope\")"), "{raised}");
    let logs: Vec<&str> = logs
        .iter()
        .map(|l| l.split_once(": ").map_or(l.as_str(), |(_, m)| m))
        .collect();
    assert_eq!(
        logs,
        [
            "false\ttests/m_test.lua:3: there is already a test named \"dup\" in tests/m_test.lua",
            "false\ttests/m_test.lua:4: a test needs a name",
            "false\ttests/m_test.lua:6: nf.test.case is for a test file's own code, which is where its tests are declared",
            "false\ttests/m_test.lua:7: bad argument 'ticks' (at least 1 expected, got 0)",
            "false\ttests/m_test.lua:8: bad argument 'name' (3 to 16 letters, digits and _ expected, got \"no\")",
            "false\ttests/m_test.lua:10: event \"player_chat\" needs a payload: a table of format, message, player",
            "false\ttests/m_test.lua:11: bad argument 'payload.world' (World expected, got string)",
            "false\ttests/m_test.lua:12: bad argument 'payload.player' (Player expected, got number)",
            "false\ttests/m_test.lua:13: bad argument 'options' (table expected, got number)",
        ]
    );
    drop(session);
    drop(server.platform);
}

#[test]
fn a_server_has_no_nf_test() {
    let files = project(&[("modules/m/init.lua", "print(nf.test, nf.server ~= nil)")]);
    let platform = Rc::new(Fake::default());
    let _session = Session::start(platform.clone(), files, &NoPackages);
    assert!(
        platform
            .lines
            .borrow()
            .iter()
            .any(|l| l.starts_with("info [module m] nil\ttrue")),
        "{:?}",
        platform.lines.borrow()
    );
}

#[test]
fn clicks_keys_and_letting_go_raise_what_the_server_would() {
    let files = project(&[
        (
            "modules/hands/init.lua",
            r#"
nf.on("player_interact", function(e) print("interact", e.player:name(), e.hand, e.item and e.item.kind) end)
nf.on("player_use_item", function(e) print("use", e.item.kind) if e.item.kind == "minecraft:shield" then e:cancel() end end)
nf.on("player_input", function(e) print("input", e.forward, e.jump, e.sneak) end)
nf.on("player_stop_using_item", function(e) print("stop", e.item.kind, e.ticks) end)
"#,
        ),
        (
            "tests/hands_test.lua",
            r#"
nf.test.case("hands", function()
  local alex = nf.test.player("Alex")
  print("left", nf.test.click(alex, "left"))
  print("right", nf.test.click(alex, "right"))
  nf.test.input(alex, { forward = true, jump = true })
  print(pcall(nf.test.release, alex, -1))
  print(pcall(nf.test.release, alex, 5))
  print(pcall(nf.test.click, alex, "left", { face = "up" }))
end)
"#,
        ),
    ]);
    let server = server(files);
    let session = server.events().session();
    session.load_tests("tests/hands_test.lua");
    assert_eq!(
        session.run_test("hands"),
        netherforge_runtime::testing::TestOutcome::Passed
    );
    server.platform.held.borrow_mut().insert(
        "main_hand".into(),
        ItemData {
            def: netherforge_format::item::ItemDef {
                kind: "minecraft:shield".into(),
                ..Default::default()
            },
            raw: None,
            look: None,
        },
    );
    session.load_tests("tests/hands_test.lua");
    session.run_test("hands");
    let (errors, logs) = session.take_test_record();
    assert_eq!(errors, []);
    let logs: Vec<&str> = logs
        .iter()
        .map(|l| l.split_once(": ").map_or(l.as_str(), |(_, m)| m))
        .collect();
    assert_eq!(
        logs,
        [
            "interact\tAlex\tmain_hand\tnil",
            "left\ttrue",
            "interact\tAlex\tmain_hand\tnil",
            "right\ttrue",
            "input\ttrue\ttrue\tfalse",
            "false\ttests/hands_test.lua:7: bad argument 'ticks' (0 or more expected, got -1)",
            "false\ttests/hands_test.lua:8: Alex has nothing in their hands to stop using",
            "false\ttests/hands_test.lua:9: bad argument 'options.face' (only with options.block)",
            "interact\tAlex\tmain_hand\tminecraft:shield",
            "left\ttrue",
            "interact\tAlex\tmain_hand\tminecraft:shield",
            "use\tminecraft:shield",
            "right\tfalse",
            "input\ttrue\ttrue\tfalse",
            "false\ttests/hands_test.lua:7: bad argument 'ticks' (0 or more expected, got -1)",
            "stop\tminecraft:shield\t5",
            "true",
            "false\ttests/hands_test.lua:9: bad argument 'options.face' (only with options.block)",
        ]
    );
}
