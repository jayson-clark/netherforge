//! `nf.network` on the Rust runtime: the hub's protocol held to Kotlin's recording, the link over TCP to a hub played
//! by the test, and scripts on the local network (a dev server's) with the test's other servers.

use netherforge_runtime::network::protocol::{self, HubFrame, ServerFrame};
use netherforge_runtime::network::seal::{self, Channel};

fn recording() -> serde_json::Value {
    let path = concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../../packages/network/testdata/session.json"
    );
    serde_json::from_str(&std::fs::read_to_string(path).expect("the recording")).expect("JSON")
}

fn text<'a>(v: &'a serde_json::Value, key: &str) -> &'a str {
    v[key].as_str().unwrap_or_else(|| panic!("{key}"))
}

/// Every line of Kotlin's recorded connections: the proof, each frame's JSON written exactly as Kotlin writes it,
/// and each sealed line's bytes, both ways.
#[test]
fn the_recorded_sessions_are_this_sides_protocol_line_for_line() {
    let recording = recording();
    let sessions = recording["sessions"].as_array().expect("sessions");
    assert_eq!(sessions.len(), 2);
    let mut kinds = std::collections::BTreeSet::new();
    for session in sessions {
        let (secret, server) = (text(session, "secret"), text(session, "server"));
        let (hub_nonce, server_nonce) = (text(session, "hubNonce"), text(session, "serverNonce"));
        let credential = session["credential"].as_str();
        assert_eq!(
            seal::proof(secret, hub_nonce, server, server_nonce, credential),
            text(session, "proof")
        );
        assert!(seal::verify(
            secret,
            hub_nonce,
            server,
            server_nonce,
            credential,
            text(session, "proof")
        ));
        assert!(!seal::verify(
            "other",
            hub_nonce,
            server,
            server_nonce,
            credential,
            text(session, "proof")
        ));
        let mut ours = Channel::server(secret, hub_nonce, server_nonce, server, credential);
        let mut hubs = Channel::hub(secret, hub_nonce, server_nonce, server, credential);
        for entry in session["lines"].as_array().expect("lines") {
            let line = text(entry, "line");
            let from = text(entry, "from");
            let frame = match entry.get("frame").and_then(|f| f.as_str()) {
                Some(frame) => {
                    if from == "hub" {
                        assert_eq!(hubs.seal(frame), line, "the hub's seal of {frame}");
                        assert_eq!(ours.open(line).as_deref(), Some(frame));
                    } else {
                        assert_eq!(ours.seal(frame), line, "this side's seal of {frame}");
                        assert_eq!(hubs.open(line).as_deref(), Some(frame));
                    }
                    frame
                }
                None => line,
            };
            let kind = serde_json::from_str::<serde_json::Value>(frame).expect("JSON")["t"]
                .as_str()
                .expect("a kind")
                .to_owned();
            if from == "hub" {
                let decoded =
                    protocol::decode_hub(frame).unwrap_or_else(|| panic!("reads {frame}"));
                assert_eq!(protocol::encode(&decoded), frame);
            } else {
                let decoded =
                    protocol::decode_server(frame).unwrap_or_else(|| panic!("reads {frame}"));
                assert_eq!(protocol::encode(&decoded), frame);
            }
            kinds.insert(format!("{from}:{kind}"));
        }
    }
    // Every kind a server sends or hears, so a frame added to the protocol is recorded too.
    assert_eq!(kinds.len(), 22 + 14, "{kinds:?}");
}

#[test]
fn a_line_altered_replayed_or_reordered_doesnt_open() {
    let (hub_nonce, server_nonce) = (seal::new_nonce(), seal::new_nonce());
    let mut hub = Channel::hub("s3cret", &hub_nonce, &server_nonce, "lobby", None);
    let mut server = Channel::server("s3cret", &hub_nonce, &server_nonce, "lobby", None);
    let first = hub.seal("one");
    let second = hub.seal("two");
    assert_eq!(server.open(&second), None, "out of order");
    let mut altered = first.clone().into_bytes();
    let at = altered.len() - 3;
    altered[at] = if altered[at] == b'A' { b'B' } else { b'A' };
    assert_eq!(
        server.open(&String::from_utf8(altered).unwrap()),
        None,
        "altered"
    );
    assert_eq!(server.open(&first).as_deref(), Some("one"));
    assert_eq!(server.open(&first), None, "replayed");
    assert_eq!(server.open(&second).as_deref(), Some("two"));
    assert_eq!(server.open("{\"t\":\"pong\"}"), None, "a plain line");
    let line = hub.seal("three");
    assert_eq!(
        Channel::server("s3cret", &hub_nonce, &server_nonce, "survival", None).open(&line),
        None
    );
    assert_eq!(
        Channel::hub("s3cret", &hub_nonce, &server_nonce, "lobby", None).open(&line),
        None
    );
}

#[test]
fn a_frame_this_side_doesnt_know_is_ignored() {
    assert_eq!(
        protocol::decode_hub(r#"{"t":"proxy_move","id":1,"player":"p","server":"s"}"#),
        None
    );
    assert_eq!(protocol::decode_hub("not json"), None);
    // Keys a later hub adds are skipped.
    assert_eq!(
        protocol::decode_hub(r#"{"t":"pong","extra":1}"#),
        Some(HubFrame::Pong)
    );
    assert_eq!(
        protocol::decode_server(r#"{"t":"ping"}"#),
        Some(ServerFrame::Ping)
    );
}

// ---- scripts on a network ------------------------------------------------------------------

use std::cell::RefCell;
use std::collections::BTreeSet;
use std::time::{Duration, Instant};

use netherforge_format::package::NoPackages;
use netherforge_format::project::ProjectSource;
use netherforge_platform::{
    FakePlatform, FakePlatformLog, PlatformError, PlatformLog, PlayerRef, ServerInfo, Uuid,
};
use netherforge_runtime::network::local::LocalHub;
use netherforge_runtime::network::protocol::Lifecycle;
use netherforge_runtime::network::{Link, NetworkConfig, NetworkLink};
use netherforge_runtime::session::{Config, Session};

#[derive(Default)]
struct Fake {
    lines: RefCell<Vec<String>>,
    online: RefCell<Vec<PlayerRef>>,
    kicked: RefCell<Vec<(Uuid, Option<String>)>>,
    bars: RefCell<Vec<(Uuid, String)>>,
}

impl Fake {
    fn take(&self) -> Vec<String> {
        std::mem::take(&mut self.lines.borrow_mut())
    }
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
    fn kick(&self, uuid: Uuid, reason: Option<String>) -> bool {
        self.kicked.borrow_mut().push((uuid, reason));
        self.online.borrow_mut().retain(|p| p.uuid != uuid);
        true
    }
    fn actionbar(&self, uuid: Uuid, text: String) -> bool {
        self.bars.borrow_mut().push((uuid, text));
        true
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

/// No structure has put a marker in the world.
impl netherforge_platform::FakeEntityOps for Fake {
    fn structure_markers(&self) -> Vec<netherforge_platform::StructureMarker> {
        Vec::new()
    }
}

impl FakePlatform for Fake {
    fn entities(&self) -> &dyn netherforge_platform::EntityOps {
        self
    }
    fn players(&self) -> &dyn netherforge_platform::PlayerOps {
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
    names: &'static BTreeSet<String>,
    texts: std::collections::BTreeMap<String, String>,
}

impl ProjectSource for Files {
    fn files(&self) -> &BTreeSet<String> {
        self.names
    }
    fn read(&self, path: &str) -> Option<String> {
        self.texts.get(path).cloned()
    }
}

/// The project `test`, declaring `requires` (`network` unless told otherwise), with one module `t`.
fn project(script: &str, requires: &str) -> Files {
    let manifest = format!(
        r#"{{ "formatVersion": 1, "name": "Test", "namespace": "test", "version": "1.0.0", "minecraft": "26.3", "requires": {requires} }}"#
    );
    let files = [
        ("netherforge.json".to_owned(), manifest),
        ("modules/t/init.lua".to_owned(), script.to_owned()),
    ];
    Files {
        names: Box::leak(Box::new(files.iter().map(|(p, _)| p.clone()).collect())),
        texts: files.into_iter().collect(),
    }
}

type Platform = std::rc::Rc<Fake>;

fn start<'a>(
    files: &'a Files,
    platform: &Platform,
    network: NetworkConfig,
    link: Option<Link>,
) -> Session<'a> {
    Session::start_configured(
        platform.clone(),
        files,
        &NoPackages,
        std::rc::Rc::new(netherforge_runtime::store::Store::in_memory()),
        Config {
            network,
            network_link: link,
            ..Config::default()
        },
    )
}

fn dev() -> NetworkConfig {
    NetworkConfig {
        alone: Some("dev".into()),
        ..NetworkConfig::default()
    }
}

/// What scripts printed and what went wrong.
fn lines(platform: &Fake) -> Vec<String> {
    let mut lines = Vec::new();
    for line in platform.take() {
        if let Some(rest) = line.strip_prefix("info [module t] ") {
            let text = rest.rsplit_once(" (modules/").map_or(rest, |(t, _)| t);
            lines.push(text.replace('\t', " "));
        } else if line.starts_with("warn") || line.starts_with("error") {
            lines.push(line);
        }
    }
    lines
}

/// Ticks until scripts have printed `count` lines (at most ten seconds), and gives them.
fn printed(session: &Session, platform: &Fake, count: usize) -> Vec<String> {
    let mut seen = lines(platform);
    let started = Instant::now();
    while started.elapsed() < Duration::from_secs(10) {
        if seen.len() >= count {
            return seen;
        }
        session.tick();
        seen.extend(lines(platform));
        std::thread::sleep(Duration::from_millis(2));
    }
    panic!("only {} of {count} lines: {seen:?}", seen.len());
}

/// `printed`, with `each` run after every tick (a test's other server answering).
fn printed_with(
    session: &Session,
    platform: &Fake,
    count: usize,
    mut each: impl FnMut(),
) -> Vec<String> {
    let mut seen = lines(platform);
    let started = Instant::now();
    while started.elapsed() < Duration::from_secs(10) {
        if seen.len() >= count {
            return seen;
        }
        session.tick();
        each();
        seen.extend(lines(platform));
        std::thread::sleep(Duration::from_millis(2));
    }
    panic!("only {} of {count} lines: {seen:?}", seen.len());
}

fn hub(session: &Session) -> std::rc::Rc<LocalHub> {
    session
        .shared()
        .network
        .borrow()
        .link()
        .and_then(|l| l.local())
        .expect("a local network")
}

const NETWORK: &str = r#"{ "network": true }"#;

#[test]
fn a_dev_server_is_a_network_of_its_own() {
    let files = project(
        r#"
        print(nf.network.server_name(), nf.network.is_connected(), nf.network.group(), nf.network.state())
        nf.on("network_state", function(e) print("state", e.state, e.previous) end)
        local chat = nf.network.channel("chat")
        chat:on("message", function(e) print("heard", e.value.text, e.server, e.channel:name()) end)
        chat:on("request", function(e) e.reply = { echo = e.value, from = e.server } end)
        local scores = nf.network.map("scores")
        scores:on("ready", function() print("ready", scores:is_ready()) end)
        scores:on("change", function(e) print("change", e.key, e.value, e.previous, e.server, e.cause) end)
        nf.task(function()
            nf.wait(2)
            print("published", chat:publish({ text = "hi" }))
            local answer, err = chat:request("dev", 42)
            print("answer", answer and answer.echo, answer and answer.from, err)
            print("set", scores:set("alex", 3))
            nf.wait(2)
            print("got", scores:get("alex"), scores:all().alex)
            print("add", scores:add("alex", 2))
            print("insert", scores:insert("lock", "me"), scores:insert("lock", "you"))
            nf.wait(20)
            print("servers", #nf.network.servers(), nf.network.servers()[1].name, nf.network.servers()[1].state)
            print("nobody", chat:request("survival", 1))
        end)
    "#,
        NETWORK,
    );
    let platform = Platform::default();
    let session = start(&files, &platform, dev(), None);
    // Ready at the first tick, with no hold: `network_state` is a change scripts heard the state before.
    assert_eq!(
        printed(&session, &platform, 14),
        [
            "dev true dev starting",
            "published true",
            "ready true",
            "heard hi dev chat",
            "answer 42 dev nil",
            "set true",
            "change alex 3 nil dev set",
            "got 3 3",
            "change alex 5 3 dev set",
            "add 5 nil",
            "change lock me nil dev set",
            "insert true false nil",
            "servers 1 dev ready",
            "nobody nil survival isn't connected to the network",
        ]
    );
}

fn uuid(n: u128) -> Uuid {
    Uuid(n)
}

fn uuid_text(n: u128) -> String {
    let hex = format!("{n:032x}");
    format!(
        "{}-{}-{}-{}-{}",
        &hex[0..8],
        &hex[8..12],
        &hex[12..16],
        &hex[16..20],
        &hex[20..]
    )
}

#[test]
fn the_tests_other_servers_publish_ask_and_answer() {
    let files = project(
        r#"
        local chat = nf.network.channel("chat")
        chat:on("message", function(e) print("heard", e.value, e.server) end)
        nf.task(function()
            nf.wait(3)
            print("all", chat:publish("everyone"))
            nf.wait(1)
            print("one", chat:publish("you", { server = "survival" }))
            print("group", chat:publish("them", { group = "worlds" }))
            print("asked", chat:request("survival", { n = 1 }).got.n)
            print("group asked", chat:request({ group = "worlds" }, 2).got)
            print("failed", chat:request("survival", 3))
            print("unanswered", chat:request("creative", 4))
            print("admin", chat:request({ admin = "matchmaker" }, 5))
        end)
    "#,
        NETWORK,
    );
    let platform = Platform::default();
    let session = start(&files, &platform, dev(), None);
    let hub = hub(&session);
    let survival = hub.peer("survival", Some("worlds".into()), None, Lifecycle::Ready);
    let creative = hub.peer("creative", Some("worlds".into()), None, Lifecycle::Ready);
    survival.listen("test:chat");
    session.tick();
    creative.send(ServerFrame::Publish {
        channel: "test:chat".into(),
        value: serde_json::json!("from creative"),
        to: None,
        group: None,
    });
    // The test answers for survival twice, then fails; creative never listens.
    let mut answered = 0;
    let seen = printed_with(&session, &platform, 10, || {
        let asked = survival.log.borrow().requests.front().cloned();
        if let Some((_, channel, from, value)) = asked {
            answered += 1;
            assert_eq!((channel.as_str(), from.as_str()), ("test:chat", "dev"));
            if answered < 3 {
                survival.answer(Some(serde_json::json!({ "got": value })), None);
            } else {
                survival.answer(None, Some("survival failed to answer: boom".into()));
            }
        }
    });
    assert_eq!(
        seen,
        [
            "heard from creative creative",
            "all true",
            "heard everyone dev",
            "one true",
            "group true",
            "asked 1",
            "group asked 2",
            "failed nil survival failed to answer: boom",
            "unanswered nil nothing on creative answers requests on \"test:chat\"",
            "admin nil the admin client matchmaker isn't connected to the hub",
        ]
    );
    let heard: Vec<String> = survival
        .log
        .borrow()
        .received
        .iter()
        .map(|(c, from, v)| format!("{c} {from} {v}"))
        .collect();
    assert_eq!(
        heard,
        [
            "test:chat creative \"from creative\"",
            "test:chat dev \"everyone\"",
            "test:chat dev \"you\"",
            "test:chat dev \"them\"",
        ]
    );
    assert!(
        creative.log.borrow().received.is_empty(),
        "creative doesn't listen"
    );
}

#[test]
fn presence_sending_players_and_arrivals() {
    let files = project(
        r#"
        nf.on("network_player_join", function(e) print("join", e.player.name, e.player.server) end)
        nf.on("network_player_switch", function(e) print("switch", e.player.name, e.player.server, e.previous) end)
        nf.on("network_player_quit", function(e) print("quit", e.player.name) end)
        nf.on("network_server_connect", function(e) print("server up", e.server) end)
        nf.on("network_server_disconnect", function(e) print("server down", e.server) end)
        nf.on("player_arrive", function(e) print("arrived", e.player:id() ~= nil, e.from, e.data and e.data.kit) end)
        nf.on("tick", function()
            if nf.time and false then end
        end)
        nf.task(function()
            nf.wait_until(function() return nf.network.find_player("alex") ~= nil end)
            print("players", #nf.network.players(), #nf.network.players({ server = "survival" }), #nf.network.players({ group = "dev" }))
            print("found", nf.network.find_player("ALEX").server)
            print("sent", nf.network.send_player("alex", "survival", { kit = "starter" }))
            print("again", nf.network.send_player("alex", "survival"))
            print("nobody", nf.network.send_player("nobody", "survival"))
            print("home", nf.network.send_player("alex", "dev"))
            print("groups", nf.network.groups()[1].name, #nf.network.groups())
        end)
    "#,
        NETWORK,
    );
    let platform = Platform::default();
    let alex = PlayerRef {
        uuid: uuid(7),
        name: "Alex".into(),
    };
    let session = start(&files, &platform, dev(), None);
    let hub = hub(&session);
    let survival = hub.peer("survival", None, None, Lifecycle::Ready);
    platform.online.borrow_mut().push(alex.clone());
    session.player_joined(&alex);
    let seen = printed(&session, &platform, 10);
    assert_eq!(
        seen,
        [
            "server up survival",
            "join Alex dev",
            "players 1 0 1",
            "found dev",
            "switch Alex survival dev",
            "sent survival nil",
            "again nil they're already on survival",
            "nobody nil nobody called \"nobody\" is online in the network",
            "home nil this server is a network of its own",
            "groups dev 2",
        ]
    );
    // Moving them took them from this server, as the proxy would; their arrival is survival's.
    assert_eq!(
        platform.kicked.borrow()[0],
        (uuid(7), Some("Sent to survival".into()))
    );
    assert_eq!(
        survival.log.borrow().arrivals,
        [(
            uuid_text(7),
            "dev".to_owned(),
            Some(serde_json::json!({ "kit": "starter" }))
        )]
    );
    session.player_quit(&alex);
    // Another player sent here by survival arrives the tick after they join (the test joins them, as the proxy's
    // move would).
    let sam = PlayerRef {
        uuid: uuid(9),
        name: "Sam".into(),
    };
    hub.accept_joins();
    hub.place(&uuid_text(9), "Sam", "survival");
    survival.send(ServerFrame::Send {
        id: 1,
        players: vec![uuid_text(9)],
        server: Some("dev".into()),
        data: Some(serde_json::json!({ "kit": "pvp" })),
        group: None,
        balance: None,
        wait_millis: None,
    });
    assert_eq!(hub.take_joins(), [(uuid_text(9), "Sam".to_owned())]);
    platform.online.borrow_mut().push(sam.clone());
    session.player_joined(&sam);
    assert_eq!(
        printed(&session, &platform, 3),
        [
            "join Sam survival",
            "switch Sam dev survival",
            "arrived true survival pvp"
        ]
    );
    assert_eq!(survival.log.borrow().replies.len(), 0);
    survival.disconnect();
    assert!(
        printed(&session, &platform, 1)
            .iter()
            .any(|l| l == "server down survival")
    );
}

#[test]
fn holding_ready_draining_and_accepting() {
    let files = project(
        r#"
        local hold = nf.network.hold_ready()
        nf.on("network_state", function(e) print("state", e.state, e.previous) end)
        nf.task(function()
            nf.wait(3)
            print("still", nf.network.state(), hold:is_released())
            hold:release()
            nf.wait(2)
            print("now", nf.network.state(), hold:is_released())
            nf.network.set_draining(true)
            nf.wait(1)
            print("draining", nf.network.is_draining())
            nf.network.set_draining(false)
            nf.network.set_accepting(false)
            nf.wait(1)
            print("accepting", nf.network.is_accepting(), nf.network.is_draining())
        end)
    "#,
        NETWORK,
    );
    let platform = Platform::default();
    let session = start(&files, &platform, dev(), None);
    assert_eq!(
        printed(&session, &platform, 7),
        [
            "still starting false",
            "state ready starting",
            "now ready true",
            "state draining ready",
            "draining true",
            "state ready draining",
            "accepting false false",
        ]
    );
}

#[test]
fn mistakes_are_errors_at_the_scripts_line() {
    let files = project(
        r#"
        local function try(f, ...) local ok, e = pcall(f, ...) print(ok, (tostring(e):gsub("^[^:]+:%d+: ", ""))) end
        try(nf.network.channel, "Bad Name")
        try(nf.network.channel, "other:chat")
        local chat = nf.network.channel("chat")
        try(function() return chat:publish(nil) end)
        try(function() return chat:publish(function() end) end)
        try(function() return chat:publish(string.rep("x", 70000)) end)
        try(function() return chat:publish(1, { server = "a", group = "b" }) end)
        try(function() return nf.network.map("m"):set("", 1) end)
        try(function() return nf.network.map("m"):set("k", 1, { expires = 0 }) end)
        try(function() return nf.network.map("netherforge:bans") end)
        nf.task(function()
            try(function() return nf.network.send_players({}, "x") end)
            try(function() return nf.network.send_player("alex", { server = "a", group = "b" }) end)
        end)
    "#,
        NETWORK,
    );
    let platform = Platform::default();
    let session = start(&files, &platform, dev(), None);
    assert_eq!(
        printed(&session, &platform, 11),
        [
            "false nf.network.channel: \"Bad Name\" isn't a name: lowercase letters, digits and _ . - /, at most 64 characters (and a namespace before a :)",
            "false nf.network.channel: package \"test\" doesn't depend on \"other\", so it can't use \"other:chat\"",
            "false bad argument 'value' (a value expected, got nil)",
            "false value: a function can't be written as JSON",
            "false value is 70002 bytes as JSON, over the network's limit of 65536 (max-value-bytes in the proxy's hub.properties)",
            "false bad argument 'options' (a server or a group, not both)",
            "false bad argument 'key' (1 to 256 characters, none a control character)",
            "false bad argument 'options.expires' (at least 1 tick expected, got 0)",
            "false nf.network.map: package \"test\" doesn't depend on \"netherforge\", so it can't use \"netherforge:bans\"",
            "false bad argument 'players' (at least one player expected)",
            "false bad argument 'target' (a table naming a server or a group expected, not both or neither)",
        ]
    );
}

#[test]
fn a_server_in_no_network_has_none() {
    let files = project(
        r#"
        print(nf.network.server_name(), nf.network.is_connected(), #nf.network.servers())
        local chat = nf.network.channel("chat")
        print("published", chat:publish("x"))
        print("set", nf.network.map("m"):set("k", 1))
        nf.task(function() print("asked", chat:request("lobby", 1)) end)
    "#,
        NETWORK,
    );
    let platform = Platform::default();
    let session = start(&files, &platform, NetworkConfig::default(), None);
    assert_eq!(
        printed(&session, &platform, 4),
        [
            "nil false 0",
            "published false",
            "set false",
            "asked nil this server isn't part of a network (network.enabled in config.yml)",
        ]
    );
    let problems: Vec<_> = session
        .problems()
        .into_iter()
        .filter(|p| p.code.as_deref() == Some("runtime.network-disabled"))
        .map(|p| (p.file, p.path))
        .collect();
    assert_eq!(
        problems,
        [(
            "netherforge.json".to_owned(),
            Some("$.requires.network".to_owned())
        )]
    );
}

#[test]
fn a_package_must_declare_the_network() {
    let files = project(r#"print(pcall(nf.network.channel, "chat"))"#, "{}");
    let platform = Platform::default();
    let session = start(&files, &platform, dev(), None);
    let seen = printed(&session, &platform, 1);
    assert!(
        seen[0].starts_with("false") && seen[0].contains("needs network"),
        "{seen:?}"
    );
}

#[test]
fn maps_expire_and_a_reload_keeps_the_link() {
    let files = project(
        r#"
        local m = nf.network.map("locks")
        m:on("change", function(e) print("change", e.key, e.value, e.cause, e.server) end)
        nf.task(function()
            nf.wait(2)
            print("claimed", m:insert("door", "dev", { expires = 100 }))
        end)
    "#,
        NETWORK,
    );
    let platform = Platform::default();
    let link = Link(NetworkLink::new(dev()));
    let mut session = start(&files, &platform, dev(), Some(link.clone()));
    assert_eq!(
        printed(&session, &platform, 2),
        ["change door dev set dev", "claimed true nil"]
    );
    let hub = hub(&session);
    hub.advance(5_000);
    // The hub's own tick is once a second.
    assert_eq!(
        printed(&session, &platform, 1),
        ["change door nil expired nil"]
    );
    session.reload(&["netherforge.json".to_owned()]);
    assert!(
        std::rc::Rc::ptr_eq(&hub, &self::hub(&session)),
        "the same network"
    );
    assert!(link.0.connected());
}

// ---- the link over TCP, to a hub the test plays ---------------------------------------------

use std::io::{BufRead, BufReader, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::{Arc, Mutex};

/// How the test's hub answers a hello.
#[derive(Clone, Copy, PartialEq)]
enum Answer {
    /// As the proxy's hub does: sealed welcome, then every frame.
    Welcome,
    /// A welcome it can't seal: not the network's hub.
    Unsealed,
}

/// A hub on loopback for one connection at a time, speaking the protocol as `:network`'s `Hub` does for what the
/// test needs: it records what it was sent and answers publishes, requests and map writes.
struct TestHub {
    port: u16,
    heard: Arc<Mutex<Vec<ServerFrame>>>,
    /// Closes the connection open now (the hub going away).
    open: Arc<Mutex<Option<TcpStream>>>,
}

fn test_hub(secret: &'static str, answer: Answer) -> TestHub {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    let heard = Arc::new(Mutex::new(Vec::new()));
    let open = Arc::new(Mutex::new(None));
    let (heard2, open2) = (heard.clone(), open.clone());
    std::thread::spawn(move || {
        for stream in listener.incoming() {
            let Ok(stream) = stream else { return };
            *open2.lock().unwrap() = stream.try_clone().ok();
            let _ = serve(stream, secret, answer, &heard2);
        }
    });
    TestHub { port, heard, open }
}

fn serve(
    stream: TcpStream,
    secret: &str,
    answer: Answer,
    heard: &Mutex<Vec<ServerFrame>>,
) -> std::io::Result<()> {
    let mut out = stream.try_clone()?;
    let mut reader = BufReader::new(stream);
    let nonce = seal::new_nonce();
    writeln!(
        out,
        "{}",
        protocol::encode(&HubFrame::Challenge {
            version: protocol::VERSION,
            nonce: nonce.clone()
        })
    )?;
    let mut line = String::new();
    reader.read_line(&mut line)?;
    let Some(ServerFrame::Hello {
        server,
        proof,
        nonce: theirs,
        credential,
        state,
        ..
    }) = protocol::decode_server(line.trim())
    else {
        return Ok(());
    };
    if !seal::verify(
        secret,
        &nonce,
        &server,
        &theirs,
        credential.as_deref(),
        &proof,
    ) {
        let refused = HubFrame::Refused {
            reason: "its secret isn't the hub's (network.secret in its config.yml must be the proxy plugin's)".into(),
        };
        writeln!(out, "{}", protocol::encode(&refused))?;
        return Ok(());
    }
    let mut channel = Channel::hub(secret, &nonce, &theirs, &server, credential.as_deref());
    let welcome = protocol::encode(&HubFrame::Welcome {
        server: server.clone(),
        servers: vec![
            protocol::ServerState {
                state: Some(state),
                ..protocol::ServerState::unlinked(&server, 0)
            }
            .pipe(|mut s| {
                s.linked = true;
                s
            }),
            protocol::ServerState::unlinked("survival", 2),
        ],
        players: vec![protocol::PlayerState {
            id: uuid_text(5),
            name: "Steve".into(),
            server: Some("survival".into()),
            proxy: None,
            pack: None,
        }],
        max_value_bytes: 1000,
        grants: protocol::Grants::trusted(),
        policy: None,
        keeps_packs: false,
    });
    if answer == Answer::Unsealed {
        writeln!(out, "{welcome}")?;
        return Ok(());
    }
    writeln!(out, "{}", channel.seal(&welcome))?;
    let mut channels = Vec::new();
    loop {
        let mut line = String::new();
        if reader.read_line(&mut line)? == 0 {
            return Ok(());
        }
        let Some(text) = channel.open(line.trim_end()) else {
            return Ok(());
        };
        let Some(frame) = protocol::decode_server(&text) else {
            continue;
        };
        heard.lock().unwrap().push(frame.clone());
        let reply = match frame {
            ServerFrame::Listen { channels: c, maps } => {
                channels = c;
                maps.into_iter()
                    .map(|map| HubFrame::MapSnapshot {
                        map,
                        entries: serde_json::Map::new(),
                    })
                    .collect()
            }
            ServerFrame::Publish {
                channel: c, value, ..
            } if channels.contains(&c) => {
                vec![HubFrame::Message {
                    channel: c,
                    from: server.clone(),
                    value,
                }]
            }
            ServerFrame::Request { id, value, .. } => {
                vec![HubFrame::Reply {
                    id,
                    value: Some(serde_json::json!({ "echo": value })),
                    error: None,
                }]
            }
            ServerFrame::MapWrite {
                id,
                map,
                key,
                value,
                ..
            } => vec![
                HubFrame::MapChanged {
                    map,
                    key,
                    value,
                    from: Some(server.clone()),
                    cause: protocol::MapCause::Set,
                },
                HubFrame::MapResult {
                    id,
                    value: Some(serde_json::json!(true)),
                    error: None,
                },
            ],
            ServerFrame::Ping => vec![HubFrame::Pong],
            _ => Vec::new(),
        };
        for frame in reply {
            writeln!(out, "{}", channel.seal(&protocol::encode(&frame)))?;
        }
    }
}

trait Pipe: Sized {
    fn pipe<R>(self, f: impl FnOnce(Self) -> R) -> R {
        f(self)
    }
}
impl<T> Pipe for T {}

fn remote(port: u16, secret: &str) -> NetworkConfig {
    NetworkConfig {
        enabled: true,
        port,
        server_name: Some("lobby".into()),
        secret: Some(secret.into()),
        ..NetworkConfig::default()
    }
}

/// Ticks until the log has a line `wanted` holds, giving every line seen.
fn logged(session: &Session, platform: &Fake, wanted: impl Fn(&str) -> bool) -> Vec<String> {
    let mut seen = Vec::new();
    let started = Instant::now();
    while started.elapsed() < Duration::from_secs(10) {
        session.tick();
        seen.extend(platform.take());
        if seen.iter().any(|l| wanted(l)) {
            return seen;
        }
        std::thread::sleep(Duration::from_millis(5));
    }
    panic!("never logged: {seen:?}");
}

#[test]
fn a_server_joins_its_hub_over_tcp_sealed() {
    let hub = test_hub("s3cret", Answer::Welcome);
    let files = project(
        r#"
        nf.on("network_connect", function(e) print("connected", e.server, #nf.network.players(), nf.network.find_player("steve").server) end)
        nf.on("network_disconnect", function(e) print("lost") end)
        local chat = nf.network.channel("chat")
        chat:on("message", function(e) print("heard", e.value.n, e.server) end)
        local m = nf.network.map("m")
        m:on("change", function(e) print("change", e.key, e.value, e.server) end)
        nf.task(function()
            nf.wait_until(function() return nf.network.is_connected() end)
            nf.wait(3)
            print("published", chat:publish({ n = 1 }))
            print("asked", chat:request("survival", 7).echo)
            print("set", m:set("k", "v"))
            print("too big", pcall(chat.publish, chat, string.rep("x", 1000)))
        end)
    "#,
        NETWORK,
    );
    let platform = Platform::default();
    let session = start(&files, &platform, remote(hub.port, "s3cret"), None);
    assert_eq!(
        printed(&session, &platform, 7),
        [
            "connected lobby 1 survival",
            "published true",
            "heard 1 lobby",
            "asked 7",
            "set true",
            "too big false modules/t/init.lua:14: value is 1002 bytes as JSON, over the network's limit of 1000 (max-value-bytes in the proxy's hub.properties)",
            "change k v lobby",
        ]
    );
    let heard = hub.heard.lock().unwrap().clone();
    // The welcome is answered with what the server is now (ready once its first tick found nothing holding it).
    assert!(matches!(heard[0], ServerFrame::Status { .. }), "{heard:?}");
    assert!(
        heard.iter().any(|f| matches!(
            f,
            ServerFrame::Status {
                state: Lifecycle::Ready,
                startup_millis: Some(_),
                ..
            }
        )),
        "{heard:?}"
    );
    assert!(heard.iter().any(|f| matches!(f, ServerFrame::Listen { channels, maps } if channels == &["test:chat"] && maps.contains(&"test:m".to_owned()) && maps.contains(&"netherforge:bans".to_owned()))), "{heard:?}");
    // The hub goes: scripts hear it, and the link dials again.
    hub.open
        .lock()
        .unwrap()
        .take()
        .unwrap()
        .shutdown(std::net::Shutdown::Both)
        .unwrap();
    let seen = logged(&session, &platform, |l| {
        l.contains("Joined the network as lobby")
    });
    assert!(
        seen.iter()
            .any(|l| l.starts_with("warn Lost the connection to the network's hub")),
        "{seen:?}"
    );
    assert!(
        seen.iter().any(|l| l.contains("[module t] lost")),
        "{seen:?}"
    );
}

#[test]
fn a_hub_refusing_or_not_holding_the_secret_is_said_in_the_log() {
    let wrong = test_hub("s3cret", Answer::Welcome);
    let files = project("", NETWORK);
    let platform = Platform::default();
    let session = start(&files, &platform, remote(wrong.port, "not it"), None);
    let seen = logged(&session, &platform, |l| l.starts_with("warn Couldn't join"));
    assert!(
        seen.contains(&"warn Couldn't join the network: the network's hub refused this server: its secret isn't the hub's (network.secret in its config.yml must be the proxy plugin's); trying again".to_owned()),
        "{seen:?}"
    );
    drop(session);
    let stranger = test_hub("s3cret", Answer::Unsealed);
    let platform = Platform::default();
    let session = start(&files, &platform, remote(stranger.port, "s3cret"), None);
    let seen = logged(&session, &platform, |l| l.starts_with("warn Couldn't join"));
    assert!(
        seen.iter().any(|l| l.ends_with("answered without the network's secret: it isn't this network's hub (check network.hub); trying again")),
        "{seen:?}"
    );
    // Nothing listens there: an address nobody answers on.
    let closed = TcpListener::bind("127.0.0.1:0")
        .unwrap()
        .local_addr()
        .unwrap()
        .port();
    let platform = Platform::default();
    let session = start(&files, &platform, remote(closed, "s3cret"), None);
    let seen = logged(&session, &platform, |l| l.starts_with("warn Couldn't join"));
    assert!(
        seen.iter().any(|l| l.contains(&format!(
            "the network's hub at 127.0.0.1:{closed} couldn't be reached"
        ))),
        "{seen:?}"
    );
    assert!(
        seen.contains(&format!(
            "info Joining the network as lobby through its hub at 127.0.0.1:{closed}"
        )),
        "{seen:?}"
    );
}

#[test]
fn a_config_that_cant_join_says_why() {
    let config = NetworkConfig {
        enabled: true,
        server_name: Some("lobby".into()),
        ..NetworkConfig::default()
    };
    assert_eq!(
        config.problem().as_deref(),
        Some(
            "network.secret is empty: copy it from the proxy's plugins/netherforge/hub.properties, or give the server a credential"
        )
    );
    assert_eq!(
        NetworkConfig::hub("proxy.example:25591"),
        Some(("proxy.example".into(), 25591))
    );
    assert_eq!(
        NetworkConfig::hub("proxy.example"),
        Some(("proxy.example".into(), 25590))
    );
    assert_eq!(NetworkConfig::hub("[::1]:9"), Some(("::1".into(), 9)));
    assert_eq!(NetworkConfig::hub("::1"), Some(("::1".into(), 25590)));
    let files = project("", NETWORK);
    let platform = Platform::default();
    let session = start(&files, &platform, config, None);
    let seen = platform.take();
    assert!(
        seen.iter().any(|l| l
            .starts_with("info NetherForge isn't joining its network: network.secret is empty")),
        "{seen:?}"
    );
    assert_eq!(
        session
            .problems()
            .iter()
            .filter(|p| p.code.as_deref() == Some("runtime.network-disabled"))
            .count(),
        1
    );
}

#[test]
fn bans_mutes_and_reports_go_through_the_hub() {
    let files = project(
        r#"
        nf.task(function()
            nf.wait_until(function() return nf.network.find_player("alex") ~= nil end)
            nf.wait(2)
            print("ban", nf.network.ban("alex", { reason = "griefing" }))
            nf.wait(2)
            print("banned", nf.network.is_banned("alex"), nf.network.is_muted("alex"))
            print("unban", nf.network.unban("alex"))
            print("again", nf.network.unban("alex"))
            print("mute", nf.network.mute("alex", { reason = "spam" }))
            nf.wait(2)
            print("muted", nf.network.is_muted("alex"))
            print("past", nf.network.mute("alex", { expires = 1 }))
            print("report", nf.network.report("alex", "cheating", { seen = 3 }))
            print("nobody", pcall(nf.network.is_banned, "nobody"))
        end)
    "#,
        r#"{ "network": true, "moderation": true }"#,
    );
    let platform = Platform::default();
    let alex = PlayerRef {
        uuid: uuid(7),
        name: "Alex".into(),
    };
    let session = start(&files, &platform, dev(), None);
    platform.online.borrow_mut().push(alex.clone());
    session.player_joined(&alex);
    assert_eq!(
        printed(&session, &platform, 9),
        [
            "ban true nil",
            "banned true false",
            "unban true nil",
            "again nil Alex wasn't banned",
            "mute true nil",
            "muted true",
            "past nil until is in the past",
            "report true",
            "nobody false modules/t/init.lua:15: nobody called \"nobody\" is online in the network: name them by UUID",
        ]
    );
    // A ban kicks them off, as the proxy would.
    assert_eq!(
        platform.kicked.borrow()[0],
        (
            uuid(7),
            Some("You're banned from the network: griefing".into())
        )
    );
    assert_eq!(
        session.shared().network.borrow().muted(uuid(7)).as_deref(),
        Some("<red>You're muted on this network: spam")
    );
    let reports = hub(&session).reports();
    assert_eq!(
        (
            reports[0].name.as_str(),
            reports[0].reason.as_str(),
            reports[0].server.as_str(),
            reports[0].details.clone()
        ),
        (
            "Alex",
            "cheating",
            "dev",
            Some(serde_json::json!({ "seen": 3 }))
        )
    );
}
