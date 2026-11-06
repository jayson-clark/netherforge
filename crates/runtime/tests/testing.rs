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
    fn kick(&self, uuid: Uuid, _: Option<String>) -> bool {
        self.online.borrow_mut().retain(|p| p.uuid != uuid);
        true
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
    bytes: BTreeMap<String, Vec<u8>>,
}

impl ProjectSource for Files {
    fn files(&self) -> &BTreeSet<String> {
        &self.names
    }
    fn read(&self, path: &str) -> Option<String> {
        self.texts.get(path).cloned()
    }
    fn bytes(&self, path: &str) -> Option<Vec<u8>> {
        self.bytes
            .get(path)
            .cloned()
            .or_else(|| self.texts.get(path).map(|t| t.clone().into_bytes()))
    }
    fn reads_wasm(&self) -> bool {
        true
    }
    fn wasm(&self, path: &str) -> Option<netherforge_format::wasm::WasmInfo> {
        Some(netherforge_format::wasm::read(self.bytes.get(path)?))
    }
}

const MANIFEST: &str = r#"{ "formatVersion": 1, "name": "Test", "namespace": "test", "version": "1.0.0", "minecraft": "26.3" }"#;

/// A project of `files` (the manifest added), kept for the run: a test server's session borrows it.
fn project(files: &[(&str, &str)]) -> &'static Files {
    project_with(files, &[])
}

/// `project`, with files of bytes too.
fn project_with(files: &[(&str, &str)], binary: &[(&str, Vec<u8>)]) -> &'static Files {
    let mut texts: BTreeMap<String, String> = files
        .iter()
        .map(|(p, t)| (p.to_string(), t.to_string()))
        .collect();
    texts
        .entry("netherforge.json".into())
        .or_insert_with(|| MANIFEST.into());
    let bytes: BTreeMap<String, Vec<u8>> = binary
        .iter()
        .map(|(p, b)| (p.to_string(), b.clone()))
        .collect();
    Box::leak(Box::new(Files {
        names: texts.keys().chain(bytes.keys()).cloned().collect(),
        texts,
        bytes,
    }))
}

/// The fake server's side of a test run: its clock is the runtime's tick, its players the fake's.
struct Harness {
    platform: Rc<Fake>,
    events: RefCell<Weak<ServerEvents<'static>>>,
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
            uuid: netherforge_runtime::testing::player_id(name),
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

/// The results of running `files`' tests: each test's status, then its message.
fn results(files: &'static Files) -> Vec<netherforge_runtime::testing::runner::TestResult> {
    run(files, None)
        .into_iter()
        .filter_map(|e| match e {
            TestEvent::Result(r) => Some(r),
            TestEvent::Failed { message, .. } => panic!("{message}"),
            _ => None,
        })
        .collect()
}

/// Asserts every test of `files` passed, saying why one didn't.
fn all_pass(files: &'static Files) {
    let results = results(files);
    assert!(!results.is_empty(), "no test ran");
    for result in results {
        assert_eq!(
            result.status,
            netherforge_runtime::testing::runner::Status::Passed,
            "{}: {:?} {:?}",
            result.name,
            result.message,
            result.logs
        );
    }
}

fn manifest(requires: &str) -> String {
    format!(
        r#"{{ "formatVersion": 1, "name": "Test", "namespace": "test", "version": "1.0.0", "minecraft": "26.3", "requires": {requires} }}"#
    )
}

#[test]
fn a_test_plays_the_far_end_of_the_scripts_connections() {
    let manifest = manifest(r#"{ "http": ["chat.example.com", "feed.example.com"] }"#);
    all_pass(project(&[
        ("netherforge.json", &manifest),
        (
            "modules/bridge/init.lua",
            r#"
local M = { heard = {}, ended = {} }
M.socket = nf.http.websocket({ url = "wss://chat.example.com/live", protocols = { "v2" } })
M.socket:send("hello")
M.socket:on("open", function(event) M.protocol = event.protocol end)
M.socket:on("message", function(event)
  table.insert(M.heard, event.data)
  M.socket:send("got " .. event.data)
end)
M.socket:on("end", function(event) table.insert(M.ended, event.reason .. " " .. tostring(event.code) .. " " .. tostring(event.detail)) end)
M.feed = nf.http.stream({ url = "https://feed.example.com/live", format = "events" })
M.feed:on("open", function(event) M.status = event.status end)
M.feed:on("message", function(event) table.insert(M.heard, event.type .. ":" .. event.data) end)
M.feed:on("end", function(event) table.insert(M.ended, event.reason .. " " .. tostring(event.detail)) end)
return M
"#,
        ),
        (
            "tests/bridge_test.lua",
            r#"
local bridge = require("bridge")

nf.test.case("the far end is the test's", function()
  assert(#nf.test.connections() == 2)
  local socket = nf.test.connections("wss://chat.example.com")[1]
  assert(socket == bridge.socket)
  assert(socket:state() == "connecting")
  nf.test.accept(socket, { protocol = "v2" })
  nf.test.receive(socket, "hi")
  nf.test.advance(1)
  assert(socket:state() == "open" and bridge.protocol == "v2")
  assert(bridge.heard[1] == "hi")
  local sent = nf.test.sent(socket)
  assert(sent[1] == "hello" and sent[2] == "got hi", table.concat(sent, ","))

  local feed = nf.test.connections("https://feed.example.com")[1]
  nf.test.accept(feed, { status = 201 })
  nf.test.receive(feed, "event: goal\ndata: red")
  nf.test.receive(feed, "\n\n")
  nf.test.advance(1)
  assert(bridge.status == 201 and feed:status() == 201)
  assert(bridge.heard[2] == "goal:red", tostring(bridge.heard[2]))

  nf.test.disconnect(socket, { code = 4000, reason = "resume" })
  nf.test.disconnect(feed, { error = "reset" })
  nf.test.advance(1)
  assert(bridge.ended[1] == "finished 4000 resume", bridge.ended[1])
  assert(bridge.ended[2] == "failed reset", bridge.ended[2])
  assert(socket:state() == "ended")
  assert(not pcall(nf.test.receive, socket, "late"))
end)

nf.test.case("mistakes are errors", function()
  local socket = nf.test.connections("wss://")[1]
  assert(not pcall(nf.test.accept, socket, { protocol = "v9" }))
  assert(not pcall(nf.test.accept, socket, { status = 200 }))
  nf.test.accept(socket)
  assert(not pcall(nf.test.accept, socket))
end)
"#,
        ),
    ]));
}

#[test]
fn a_test_answers_the_scripts_requests_after_the_checks_a_server_makes() {
    let manifest = manifest(r#"{ "http": ["api.weather.example", "127.0.0.1"] }"#);
    let files = project(&[
        ("netherforge.json", &manifest),
        (
            "modules/weather/init.lua",
            r#"
local M = {}
function M.refresh()
  nf.http.request({ url = "https://api.weather.example/now?city=oslo", headers = { ["X-Key"] = "k" } }, function(response, err)
    if response then
      M.sky = nf.json.decode(response.body).sky
      M.status, M.kind = response.status, response.headers["content-type"]
    else
      M.err = err
    end
  end)
end
return M
"#,
        ),
        (
            "tests/weather_test.lua",
            r#"local weather = require("weather")

nf.test.case("the fake answers", function()
  local asked
  nf.test.http(function(request)
    asked = request
    return { status = 203, headers = { ["Content-Type"] = "application/json" }, body = nf.json.encode({ sky = "rain" }) }
  end)
  weather.refresh()
  nf.test.advance(1)
  assert(weather.sky == "rain", tostring(weather.err))
  assert(weather.status == 203 and weather.kind == "application/json")
  assert(asked.method == "GET" and asked.url == "https://api.weather.example/now?city=oslo" and asked.headers["X-Key"] == "k")
end)

nf.test.case("the checks come first", function()
  nf.test.http(function() return { body = "never" } end)
  local ok, err = pcall(nf.http.request, { url = "https://undeclared.example/" }, function() end)
  assert(not ok and tostring(err):find("hasn't declared"), tostring(err))
  local got, why
  nf.http.request({ url = "http://127.0.0.1:9000/" }, function(response, e) got, why = response, e end)
  nf.test.advance(1)
  assert(got == nil and tostring(why):find("loopback"), tostring(why))
end)

nf.test.case("an unanswered request fails the test", function()
  nf.test.http(function() return nil end)
  weather.refresh()
  nf.test.advance(1)
end)

nf.test.case("with no handler too, even when the script takes its error", function()
  weather.refresh()
  nf.test.advance(1)
  assert(weather.err)
end)
"#,
        ),
    ]);
    use netherforge_runtime::testing::runner::Status;
    let results = results(files);
    let statuses: Vec<Status> = results.iter().map(|r| r.status).collect();
    assert_eq!(
        statuses,
        [
            Status::Passed,
            Status::Passed,
            Status::Failed,
            Status::Failed
        ],
        "{results:?}"
    );
    assert_eq!(
        results[2].message.as_deref(),
        Some(
            "tests/weather_test.lua:29: the request to https://api.weather.example/now?city=oslo wasn't answered: nf.test.http's handler answered nil"
        )
    );
    assert_eq!(results[2].source.as_ref().and_then(|s| s.line), Some(29));
    assert!(
        results[3]
            .message
            .as_deref()
            .is_some_and(|m| m.contains("no nf.test.http handler")),
        "{:?}",
        results[3].message
    );
}

#[test]
fn a_test_stands_in_for_the_owners_private_address_allowance() {
    let manifest = manifest(r#"{ "http": ["127.0.0.1", "localhost"] }"#);
    all_pass(project(&[
        ("netherforge.json", &manifest),
        (
            "tests/bridge_test.lua",
            r#"
local function ask(url)
  local got, why
  nf.http.request({ url = url }, function(response, err) got, why = response, err end)
  nf.test.advance(1)
  return got, why
end

nf.test.case("the allowed address is answered", function()
  nf.test.allow_private({ package = "test", host = "127.0.0.1", port = 9361 })
  nf.test.http(function(request)
    if request.url == "http://127.0.0.1:9361/events" then return { status = 204 } end
  end)
  local got, why = ask("http://127.0.0.1:9361/events")
  assert(got and got.status == 204, tostring(why))
  local _, other = ask("http://127.0.0.1:9000/events")
  assert(tostring(other):find("loopback"), "another port is still refused: " .. tostring(other))
end)

nf.test.case("localhost, with a rate of its own", function()
  nf.test.allow_private({ package = "test", host = "localhost", port = 8080, requests_per_minute = 1 })
  nf.test.http(function() return { body = "ok" } end)
  local got, why = ask("http://localhost:8080/")
  assert(got and got.body == "ok", tostring(why))
  local _, limited = ask("http://localhost:8080/")
  assert(limited, "the second request in the minute is over its rate")
end)

nf.test.case("another package's allowance doesn't help", function()
  nf.test.allow_private({ package = "other", host = "127.0.0.1", port = 9361 })
  nf.test.http(function() return { body = "never" } end)
  local got, why = ask("http://127.0.0.1:9361/events")
  assert(got == nil and tostring(why):find("loopback"), tostring(why))
end)

nf.test.case("it lasts only for its test", function()
  nf.test.http(function() return { body = "never" } end)
  local got, why = ask("http://127.0.0.1:9361/events")
  assert(got == nil and tostring(why):find("loopback"), tostring(why))
end)

nf.test.case("an allowance is checked as the owner's is", function()
  for _, bad in ipairs({
    { package = "Not A Namespace", host = "127.0.0.1", port = 1 },
    { package = "test", host = "127.0.0.1/x", port = 1 },
    { package = "test", host = "127.0.0.1", port = 0 },
    { package = "test", host = "127.0.0.1", port = 1, requests_per_minute = -1 },
  }) do
    local ok, err = pcall(nf.test.allow_private, bad)
    assert(not ok and tostring(err):find("bad argument 'allowance'"), tostring(err))
  end
end)
"#,
        ),
    ]));
}

#[test]
fn a_test_makes_web_requests_through_the_routes_and_plays_a_login() {
    let manifest = manifest(r#"{ "web": true, "http": ["auth.example.com"] }"#);
    all_pass(project(&[
        ("netherforge.json", &manifest),
        (
            "modules/site/init.lua",
            r#"
local M = { linked = {}, failed = {} }
nf.web.route("POST", "/hooks/:kind", function(request, response)
  nf.after(3, function() response:json(202, { kind = request.params.kind, got = request.body }) end)
end)
M.flow = nf.web.oauth({
  name = "example",
  authorize_url = "https://auth.example.com/authorize",
  token_url = "https://auth.example.com/token",
  client_id = "abc",
  scopes = { "identify", "email" },
})
M.flow:on("complete", function(event) M.linked[event.player:name()] = event.access_token .. "/" .. tostring(event.expires_in) end)
M.flow:on("fail", function(event) table.insert(M.failed, event.error) end)
return M
"#,
        ),
        (
            "tests/site_test.lua",
            r#"
local site = require("site")

nf.test.case("a hook answered later is waited for", function()
  local answer = nf.test.request("POST", "/test/hooks/store", { body = "order 7" })
  assert(answer.status == 202, answer.status .. " " .. answer.body)
  assert(answer.body == '{"got":"order 7","kind":"store"}', answer.body)
  assert(answer.headers["content-type"] == "application/json")
  assert(nf.test.request("GET", "/test/hooks/store").status == 405)
  assert(nf.test.request("GET", "/test/nope").status == 404)
end)

nf.test.case("a login completes once, through its callback", function()
  local alex = nf.test.player("Alex")
  local url = assert(site.flow:begin(alex))
  assert(url:find("^https://auth.example.com/authorize%?"), url)
  assert(url:find("code_challenge_method=S256", 1, true), url)
  assert(url:find("redirect_uri=http%3A%2F%2Flocalhost%2Ftest%2Foauth%2Fexample%2Fcallback", 1, true), url)
  assert(url:find("scope=identify%20email", 1, true), url)
  local state = url:match("state=([^&]+)")
  nf.test.oauth_token(site.flow, { access_token = "tok", expires_in = 3600 })
  local forged = nf.test.request("GET", "/test/oauth/example/callback?code=c&state=forged")
  assert(forged.status == 400)
  local back = nf.test.request("GET", "/test/oauth/example/callback?code=c&state=" .. state)
  assert(back.status == 200, back.body)
  -- Nothing is traded yet: the browser confirms which player it signs in as.
  assert(back.body:find("Minecraft player Alex", 1, true), back.body)
  assert(site.linked.Alex == nil)
  local token = back.body:match('name="token" value="([^"]+)"')
  assert(nf.test.request("POST", "/test/oauth/example/confirm", { body = "token=wrong" }).status == 400)
  local done = nf.test.request("POST", "/test/oauth/example/confirm", { body = "token=" .. token })
  assert(done.status == 200 and done.body:find("Signed in"), done.body)
  assert(site.linked.Alex == "tok/3600", tostring(site.linked.Alex))
  local again = nf.test.request("GET", "/test/oauth/example/callback?code=c&state=" .. state)
  assert(again.status == 400, "a state is used once")
  local twice = nf.test.request("POST", "/test/oauth/example/confirm", { body = "token=" .. token })
  assert(twice.status == 400, "a confirmation is used once")
end)

nf.test.case("a declined login fails, and a route can't take a flow's path", function()
  local alex = nf.test.player("Alex")
  local state = site.flow:begin(alex):match("state=([^&]+)")
  local back = nf.test.request("GET", "/test/oauth/example/callback?error=access_denied&state=" .. state)
  assert(back.status == 400)
  assert(site.failed[1] == "the service said access_denied", tostring(site.failed[1]))
  assert(not pcall(nf.web.route, "GET", "/oauth/example/callback", function() end))
  assert(not pcall(nf.web.oauth, { name = "x", authorize_url = "https://a.b/", token_url = "https://undeclared.example/t", client_id = "c" }))
end)
"#,
        ),
    ]));
}

/// A library adding two numbers.
fn library() -> Vec<u8> {
    wat::parse_str(
        r#"(module (func (export "add") (param i32 i32) (result i32) (i32.add (local.get 0) (local.get 1))))"#,
    )
    .unwrap()
}

/// A program that answers each line it reads with `echo: <line>`, and exits with 0 at the end of its input.
fn echo() -> Vec<u8> {
    wat::parse_str(
        r#"
    (module
      (import "wasi_snapshot_preview1" "fd_read" (func $fd_read (param i32 i32 i32 i32) (result i32)))
      (import "wasi_snapshot_preview1" "fd_write" (func $fd_write (param i32 i32 i32 i32) (result i32)))
      (memory (export "memory") 1)
      (data (i32.const 24) "\n")
      (data (i32.const 100) "echo: ")
      (func (export "_start")
        (local $n i32)
        (loop $next
          (i32.store (i32.const 0) (i32.const 16))
          (i32.store (i32.const 4) (i32.const 1))
          (drop (call $fd_read (i32.const 0) (i32.const 0) (i32.const 1) (i32.const 8)))
          (if (i32.eqz (i32.load (i32.const 8))) (then (return)))
          (if (i32.eq (i32.load8_u (i32.const 16)) (i32.const 10))
            (then
              (i32.store (i32.const 32) (i32.const 100)) (i32.store (i32.const 36) (i32.const 6))
              (i32.store (i32.const 40) (i32.const 200)) (i32.store (i32.const 44) (local.get $n))
              (i32.store (i32.const 48) (i32.const 24)) (i32.store (i32.const 52) (i32.const 1))
              (drop (call $fd_write (i32.const 1) (i32.const 32) (i32.const 3) (i32.const 8)))
              (local.set $n (i32.const 0))
              (br $next)))
          (i32.store8 (i32.add (i32.const 200) (local.get $n)) (i32.load8_u (i32.const 16)))
          (local.set $n (i32.add (local.get $n) (i32.const 1)))
          (br $next)))
    )
    "#,
    )
    .unwrap()
}

#[test]
fn modules_really_run_in_a_test_and_a_test_can_stand_in_for_one() {
    let manifest = manifest(r#"{ "wasm": true }"#);
    all_pass(project_with(
        &[
            ("netherforge.json", &manifest),
            (
                "modules/engine/init.lua",
                r#"
local M = { said = {} }
function M.start()
  M.program = nf.wasm.start("echo")
  M.program:on("output", function(event) table.insert(M.said, event.line) end)
  M.program:on("end", function(event) M.ended = event.reason .. " " .. tostring(event.exit_code) end)
end
return M
"#,
            ),
            (
                "tests/engine_test.lua",
                r#"
local engine = require("engine")

nf.test.case("a real module answers, a tick after it's asked", function()
  local answer
  nf.task(function()
    local math = nf.wasm.instantiate("math")
    answer = math:call("add", { 2, 3 })[1]
  end)
  nf.test.advance(3)
  assert(answer == 5, tostring(answer))
  engine.start()
  engine.program:send("hello")
  nf.test.advance(1)
  assert(engine.said[1] == "echo: hello", tostring(engine.said[1]))
end)

nf.test.case("a stand-in answers in its place", function()
  nf.test.stub_wasm("echo", {
    input = function(line) return { "stub: " .. line, "again" } end,
  })
  nf.test.stub_wasm("math", {
    functions = { add = function(arguments) return { arguments[1] * 100 } end, name = function() return "stub" end },
    run = function(options) return { exit_code = 7, stdout = options.stdin, stderr = "" } end,
  })
  engine.start()
  engine.program:send("hello")
  nf.test.advance(2)
  assert(engine.said[1] == "stub: hello" and engine.said[2] == "again", table.concat(engine.said, "|"))
  engine.program:close_input()
  nf.test.advance(2)
  assert(engine.ended == "exited 0", tostring(engine.ended))

  local got = {}
  nf.task(function()
    local math = nf.wasm.instantiate("math")
    got.sum = math:call("add", { 2, 3 })[1]
    got.name = math:call_text("name")
    got.missing = select(2, math:call("nope"))
    got.run = nf.wasm.run("math", { stdin = "in" })
  end)
  nf.test.advance(6)
  assert(got.sum == 200, tostring(got.sum))
  assert(got.name == "stub", tostring(got.name))
  assert(got.missing:find("has no function"), tostring(got.missing))
  assert(got.run.exit_code == 7 and got.run.stdout == "in")

  -- Stand-ins count against the owner's limits as the modules they stand in for do.
  local refused
  nf.task(function()
    for _ = 1, 9 do
      local instance, err = nf.wasm.instantiate("math")
      if not instance then refused = err break end
    end
  end)
  nf.test.advance(20)
  assert(refused and refused:find("max%-instances%-per%-package"), tostring(refused))
end)
"#,
            ),
        ],
        &[
            ("wasm/math/module.wasm", library()),
            ("wasm/echo/module.wasm", echo()),
        ],
    ));
}

#[test]
fn a_test_plays_the_other_servers_of_a_network_of_its_own() {
    let manifest = manifest(r#"{ "network": true }"#);
    all_pass(project(&[
        ("netherforge.json", &manifest),
        (
            "modules/net/init.lua",
            r#"
local M = { heard = {}, arrived = {}, players = {}, changes = {} }
nf.network.channel("chat"):on("message", function(event)
  table.insert(M.heard, event.server .. ":" .. event.value)
end)
nf.network.channel("prices"):on("request", function(event)
  event.reply = { item = event.value, price = 42, asker = event.server }
end)
M.scores = nf.network.map("scores")
M.scores:on("change", function(event)
  table.insert(M.changes, event.key .. "=" .. tostring(event.value) .. " by " .. tostring(event.server))
end)
nf.on("player_arrive", function(event)
  table.insert(M.arrived, event.player:name() .. " from " .. event.from .. " " .. event.data.kit)
end)
nf.on("network_player_join", function(event)
  table.insert(M.players, event.player.name .. "@" .. tostring(event.player.server))
end)
return M
"#,
        ),
        (
            "tests/net_test.lua",
            r#"
local net = require("net")

nf.test.case("messages go both ways", function()
  assert(nf.network.server_name() == "test" and nf.network.is_connected())
  nf.test.network_server("lobby")
  nf.test.network_received("lobby", "chat")
  nf.test.advance(1)
  nf.test.network_publish("lobby", "chat", "hi")
  nf.test.advance(1)
  assert(net.heard[1] == "lobby:hi", tostring(net.heard[1]))
  assert(nf.network.channel("chat"):publish("hello"))
  nf.test.advance(1)
  local got = nf.test.network_received("lobby", "chat")
  assert(#got == 2 and got[1].value == "hi" and got[1].server == "lobby")
  assert(got[2].value == "hello" and got[2].server == "test" and got[2].channel == "chat")
  assert(net.heard[2] == "test:hello", tostring(net.heard[2]))
end)

nf.test.case("an admin client publishes as the hub delivers it", function()
  nf.test.network_server("lobby")
  nf.test.advance(1)
  nf.test.network_publish("admin:platform", "chat", "from the service")
  nf.test.network_publish("lobby", "chat", "from a server")
  nf.test.advance(1)
  assert(net.heard[1] == "admin:platform:from the service", tostring(net.heard[1]))
  assert(net.heard[2] == "lobby:from a server", tostring(net.heard[2]))
  assert(not pcall(nf.test.network_publish, "admin:not an id", "chat", 1))
end)

nf.test.case("requests go both ways", function()
  nf.test.network_server("bank")
  nf.test.network_answer("bank", "balance", function(name, server) return name .. " has 100, " .. server end)
  local answer, err
  nf.task(function() answer, err = nf.network.channel("balance"):request("bank", "ann") end)
  nf.test.advance(3)
  assert(answer == "ann has 100, test", tostring(answer) .. " " .. tostring(err))
  local quote = nf.test.network_request("bank", "prices", "diamond")
  assert(quote.item == "diamond" and quote.price == 42 and quote.asker == "bank")
  assert(not pcall(nf.test.network_request, "bank", "nothing", 1))
end)

nf.test.case("maps are shared", function()
  nf.test.network_server("lobby")
  nf.test.advance(2)
  assert(net.scores:is_ready())
  nf.test.network_write("lobby", "scores", "ann", 5)
  nf.test.advance(1)
  assert(net.scores:get("ann") == 5)
  assert(net.changes[1] == "ann=5 by lobby", tostring(net.changes[1]))
  local total
  nf.task(function() total = net.scores:add("ann", 2) end)
  nf.test.advance(2)
  assert(total == 7 and net.scores:get("ann") == 7)
end)

nf.test.case("a group's instances share players out", function()
  nf.test.network_server("arena-1", { group = "arena", capacity = 1 })
  nf.test.network_server("arena-2", { group = "arena", capacity = 1 })
  nf.test.advance(1)
  assert(nf.network.group() == "test")
  local groups = {}
  for _, group in ipairs(nf.network.groups()) do groups[group.name] = group end
  assert(#groups.arena.instances == 2 and groups.arena.capacity == 2, tostring(#groups.arena.instances))
  local ann = nf.test.player("Ann")
  local server, err
  nf.task(function() server, err = nf.network.send_player(ann, { group = "arena" }) end)
  nf.test.advance(2)
  assert(server == "arena-1", tostring(err))
end)

nf.test.case("players come and go, and are sent both ways", function()
  nf.test.network_join("lobby", "Bob")
  nf.test.advance(1)
  assert(net.players[1] == "Bob@lobby", tostring(net.players[1]))
  local ann = nf.test.network_send("lobby", "Ann", { kit = "archer" })
  nf.test.advance(1)
  assert(net.arrived[1] == "Ann from lobby archer", tostring(net.arrived[1]))
  assert(nf.network.find_player("Ann").server == "test")
  local moved, err
  nf.task(function() moved, err = nf.network.send_player(ann, "lobby") end)
  nf.test.advance(2)
  assert(moved == "lobby", tostring(err))
  assert(#nf.players.online() == 0, "sent away, they left this server")
  assert(nf.network.find_player("Ann").server == "lobby")
  nf.test.network_quit("Bob")
  nf.test.advance(1)
  assert(nf.network.find_player("Bob") == nil)
end)
"#,
        ),
    ]));
}

#[test]
fn a_test_plays_a_group_starting_holds_this_server_to_a_role_and_moderates() {
    let manifest = manifest(r#"{ "network": true, "moderation": true }"#);
    all_pass(project(&[
        ("netherforge.json", &manifest),
        (
            "tests/net_test.lua",
            r#"
nf.test.case("a player waits for the arena to start, then goes", function()
  nf.test.network_server("arena-1", { group = "arena", state = "starting" })
  local ann = nf.test.player("Ann")
  local went, place
  nf.task(function()
    went = nf.network.send_player(ann, { group = "arena", wait = 30, on_wait = function(p) place = p end })
  end)
  nf.test.advance(2)
  assert(went == nil and place == 1, tostring(place))
  nf.test.network_status("arena-1", { state = "ready" })
  nf.test.advance(2)
  assert(went == "arena-1", tostring(went))
end)

nf.test.case("a standard server is held to its role", function()
  nf.test.network_role({ role = "standard", owns = { "other" } })
  nf.test.advance(1)
  assert(nf.network.server_role().role == "standard")
  local ok, err = pcall(function() nf.network.channel("chat"):publish("hi") end)
  assert(not ok and err:find("network.forbidden"), tostring(err))
  assert(not pcall(nf.test.network_role, { role = "nobody" }))
  assert(not pcall(nf.test.network_role, { role = "standard", shares = { "nope" } }))
end)

nf.test.case("bans, mutes and reports go to the network's hub", function()
  nf.test.network_server("lobby")
  nf.test.network_join("lobby", "Bea")
  nf.test.advance(1)
  local banned
  nf.task(function() banned = nf.network.ban("Bea", { reason = "griefing" }) end)
  nf.test.advance(2)
  assert(banned == true)
  assert(nf.network.is_banned("Bea"))
  nf.network.report("Bea", "rude", { said = "x" })
  nf.test.advance(1)
  local reports = nf.test.network_reports()
  assert(#reports == 1 and reports[1].player == "Bea" and reports[1].details.said == "x")
end)
"#,
        ),
    ]));
}
