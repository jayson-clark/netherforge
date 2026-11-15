//! The local control endpoint (Kotlin's `ControlTest`): health without a token, everything else with one, state as the
//! main thread last saw it, actions done at its next tick, and saving with writes paused and resumed.

mod support;

use std::io::{Read as _, Write as _};
use std::rc::Rc;

use netherforge_platform::PlatformEvents as _;
use netherforge_runtime::config::ControlConfig;
use netherforge_runtime::control::ServerControl;
use netherforge_runtime::server::ServerEvents;
use support::*;

/// `method path` with `body`, answered while the main thread goes on ticking: the status and the JSON.
fn request(
    events: &ServerEvents,
    port: u16,
    token: Option<&str>,
    method: &str,
    path: &str,
    body: &str,
) -> (u16, serde_json::Value) {
    let (method, path, body) = (method.to_owned(), path.to_owned(), body.to_owned());
    let token = token.map(str::to_owned);
    let asking = std::thread::spawn(move || {
        let mut stream = std::net::TcpStream::connect(("127.0.0.1", port)).unwrap();
        let auth = token
            .map(|t| format!("Authorization: Bearer {t}\r\n"))
            .unwrap_or_default();
        write!(
            stream,
            "{method} {path} HTTP/1.1\r\nHost: x\r\n{auth}Content-Length: {}\r\n\r\n{body}",
            body.len()
        )
        .unwrap();
        let mut answer = String::new();
        stream.read_to_string(&mut answer).unwrap();
        answer
    });
    while !asking.is_finished() {
        events.tick();
        std::thread::sleep(std::time::Duration::from_millis(2));
    }
    let answer = asking.join().unwrap();
    let status = answer[9..12].parse().unwrap();
    let json = answer.split_once("\r\n\r\n").map_or("null", |(_, b)| b);
    (status, serde_json::from_str(json).unwrap())
}

#[test]
fn the_control_endpoint_answers_health_state_and_saves_with_writes_paused() {
    let dir = tempfile::tempdir().unwrap();
    let platform = Rc::new(Fake::default());
    let files: &'static Files = Box::leak(Box::new(Files::new(&[(
        "netherforge.json",
        r#"{ "formatVersion": 1, "name": "Demo", "namespace": "demo", "version": "1.2.3", "minecraft": "26.3" }"#,
    )])));
    let events = ServerEvents::new(Session::start(platform.clone(), files, &NoPackages));
    let control = Rc::new(
        ServerControl::start(
            &ControlConfig {
                socket: None,
                port: Some(0),
                token_file: dir.path().join("control.token"),
                stall_seconds: 10,
            },
            &*platform,
        )
        .expect("listening"),
    );
    events.set_control(Some(control.clone()));
    let port = control.port().unwrap();
    let token = std::fs::read_to_string(dir.path().join("control.token")).unwrap();
    let token = token.trim();
    assert!(token.len() >= 24);

    events.tick();
    assert_eq!(request(&events, port, None, "GET", "/healthz", "").0, 200);
    let (status, refused) = request(&events, port, None, "GET", "/state", "");
    assert_eq!(status, 401, "{refused}");

    let (status, state) = request(&events, port, Some(token), "GET", "/state", "");
    assert_eq!(status, 200);
    assert_eq!(state["project"], "Demo");
    assert_eq!(state["version"], "1.2.3");
    assert_eq!(state["running"], true);
    assert_eq!(state["state"], "ready");
    assert_eq!(state["stalled"], false);

    let (status, saved) = request(
        &events,
        port,
        Some(token),
        "POST",
        "/save",
        r#"{"pause": true}"#,
    );
    assert_eq!(status, 200, "{saved}");
    assert_eq!(saved["writes_paused"], true);
    assert!(platform.saving.borrow().iter().all(|(_, on)| !on));
    let (_, state) = request(&events, port, Some(token), "GET", "/state", "");
    assert_eq!(state["writes_paused"], true);

    let (status, resumed) = request(&events, port, Some(token), "POST", "/resume", "");
    assert_eq!(status, 200, "{resumed}");
    assert_eq!(resumed["writes_paused"], false);
    assert!(platform.saving.borrow().last().is_some_and(|(_, on)| *on));

    let (status, missing) = request(
        &events,
        port,
        Some(token),
        "POST",
        "/save",
        r#"{"world": "nowhere"}"#,
    );
    assert_eq!(status, 404, "{missing}");
    let (status, drained) = request(&events, port, Some(token), "POST", "/drain", "");
    assert_eq!(
        (status, drained["state"].as_str()),
        (200, Some("draining")),
        "{drained}"
    );
    assert_eq!(
        request(&events, port, Some(token), "GET", "/nothing", "").0,
        404
    );
    assert_eq!(
        request(&events, port, Some(token), "POST", "/save", "[1]").0,
        400
    );
    control.stop(&*platform);
}

#[cfg(unix)]
#[test]
fn on_a_unix_socket_no_token_is_asked_for() {
    let dir = tempfile::tempdir().unwrap();
    let socket = dir.path().join("nf.sock");
    let platform = Rc::new(Fake::default());
    let files: &'static Files = Box::leak(Box::new(Files::new(&[(
        "netherforge.json",
        r#"{ "formatVersion": 1, "name": "Demo", "namespace": "demo", "version": "1.0.0", "minecraft": "26.3" }"#,
    )])));
    let events = ServerEvents::new(Session::start(platform.clone(), files, &NoPackages));
    let control = Rc::new(
        ServerControl::start(
            &ControlConfig {
                socket: Some(socket.clone()),
                port: None,
                token_file: dir.path().join("unused.token"),
                stall_seconds: 10,
            },
            &*platform,
        )
        .expect("listening"),
    );
    events.set_control(Some(control.clone()));
    events.tick();
    let asking = std::thread::spawn(move || {
        let mut stream = std::os::unix::net::UnixStream::connect(&socket).unwrap();
        stream.write_all(b"GET /state HTTP/1.1\r\n\r\n").unwrap();
        let mut answer = String::new();
        stream.read_to_string(&mut answer).unwrap();
        answer
    });
    while !asking.is_finished() {
        events.tick();
        std::thread::sleep(std::time::Duration::from_millis(2));
    }
    let answer = asking.join().unwrap();
    assert!(answer.starts_with("HTTP/1.1 200"), "{answer}");
    assert!(answer.contains("\"project\":\"Demo\""), "{answer}");
    assert!(!dir.path().join("unused.token").exists());
    control.stop(&*platform);
    assert!(
        !dir.path().join("nf.sock").exists(),
        "the socket goes with it"
    );
}

/// A server for [`a_kept_rooms_centities_travel_with_its_folder`]: its world storage, its store, its events and its
/// control endpoint (port and token).
struct Server {
    platform: Rc<Fake>,
    store: Rc<netherforge_runtime::store::Store>,
    events: ServerEvents<'static>,
    control: Rc<ServerControl>,
    port: u16,
    token: String,
}

impl Server {
    fn start(files: &'static Files, imports: &std::path::Path, dir: &std::path::Path) -> Server {
        let platform = Rc::new(Fake::default());
        *platform.storage.borrow_mut() = Some(dir.join("world"));
        let store = Rc::new(netherforge_runtime::store::Store::in_memory());
        let mut config = netherforge_runtime::session::Config::default();
        config.worlds.import_root = Some(imports.to_owned());
        let events = ServerEvents::new(Session::start_configured(
            platform.clone(),
            files,
            &NoPackages,
            store.clone(),
            config,
        ));
        let control = Rc::new(
            ServerControl::start(
                &ControlConfig {
                    socket: None,
                    port: Some(0),
                    token_file: dir.join("control.token"),
                    stall_seconds: 10,
                },
                &*platform,
            )
            .expect("listening"),
        );
        events.set_control(Some(control.clone()));
        let port = control.port().unwrap();
        let token = std::fs::read_to_string(dir.join("control.token")).unwrap();
        events.tick();
        Server {
            platform,
            store,
            events,
            control,
            port,
            token: token.trim().to_owned(),
        }
    }

    fn ask(&self, path: &str, body: &str) -> (u16, serde_json::Value) {
        request(
            &self.events,
            self.port,
            Some(&self.token),
            "POST",
            path,
            body,
        )
    }

    /// What the room's script said this time (`said` lines), in order.
    fn said(&self) -> Vec<String> {
        self.platform
            .take()
            .iter()
            .filter_map(|c| c.split_once("said\t").map(|(_, rest)| rest))
            .map(|rest| {
                rest.rsplit_once(" (")
                    .map_or(rest, |(l, _)| l)
                    .replace('\t', " ")
            })
            .collect()
    }
}

/// Copies folder `from` into `to`, as Kosmoi's agent copies what a save names.
fn copy_tree(from: &std::path::Path, to: &std::path::Path) {
    std::fs::create_dir_all(to).unwrap();
    for entry in std::fs::read_dir(from).unwrap().flatten() {
        if entry.path().is_dir() {
            copy_tree(&entry.path(), &to.join(entry.file_name()));
        } else {
            std::fs::copy(entry.path(), to.join(entry.file_name())).unwrap();
        }
    }
}

#[test]
fn a_kept_rooms_centities_travel_with_its_folder() {
    let imports = tempfile::tempdir().unwrap();
    std::fs::create_dir_all(
        imports
            .path()
            .join("first/worlds/home/dimensions/minecraft/overworld"),
    )
    .unwrap();
    let files: &'static Files = Box::leak(Box::new(Files::new(&[
        ("netherforge.json", MANIFEST),
        ("centities/lamp/centity.json", &lamp(1.0)),
        (
            "modules/game/init.lua",
            r#"
local room = nf.rooms.current()
if not room:is_main() then
  local home = assert(nf.worlds.get("home"))
  local lamps = nf.centities.all({ world = home })
  for _, lamp in ipairs(lamps) do
    print("said", "back", lamp:id(), lamp:data().lit, lamp:position())
  end
  if #lamps == 0 then
    local lamp = assert(nf.centities.spawn("lamp", home:location(vector.create(3, 64, 4))))
    lamp:data().lit = 7
    print("said", "spawned", lamp:id())
  end
end
"#,
        ),
    ])));

    // On one server: Alice's room opens from her first folder (a world, no centities), and its script spawns one.
    let one_dir = tempfile::tempdir().unwrap();
    let one = Server::start(files, imports.path(), one_dir.path());
    let (status, opened) = one.ask(
        "/rooms/open",
        r#"{"key": "player_alice", "keep": true, "folder": "first"}"#,
    );
    assert_eq!(status, 200, "{opened}");
    let said = one.said();
    let id = said[0]
        .strip_prefix("spawned ")
        .expect("spawned")
        .to_owned();

    // Saved: the centity's record and table are in the world's folder, which is what's copied out.
    let (status, saved) = one.ask("/save", r#"{"room": "player_alice"}"#);
    assert_eq!(status, 200, "{saved}");
    let folder = std::path::PathBuf::from(saved["worlds"][0]["folder"].as_str().unwrap());
    let records: serde_json::Value = serde_json::from_str(
        &std::fs::read_to_string(folder.join("data/netherforge/centities.json")).unwrap(),
    )
    .unwrap();
    assert_eq!(records["centities"][0]["id"], id.as_str());
    assert_eq!(records["centities"][0]["centity"], "demo:lamp");
    assert_eq!(
        records["centities"][0]["data"],
        serde_json::json!({ "lit": 7 })
    );
    let carried = imports.path().join("carried");
    copy_tree(
        &folder,
        &carried.join("worlds/home/dimensions/minecraft/overworld"),
    );

    // Closed with delete: the world's files go, and the store forgets its centities.
    let (status, _) = one.ask("/rooms/close", r#"{"key": "player_alice", "delete": true}"#);
    assert_eq!(status, 200);
    let started = std::time::Instant::now();
    while folder.exists() {
        assert!(started.elapsed().as_secs() < 5, "never deleted");
        one.events.tick();
        std::thread::sleep(std::time::Duration::from_millis(2));
    }
    assert!(
        !one.store
            .instances()
            .iter()
            .any(|r| r.anchor.world == "home__player_alice"),
        "{:?}",
        one.store.instances()
    );
    one.control.stop(&*one.platform);

    // On another server, with a store of its own: opened from the copy, the centity is back as itself, its table too.
    let two_dir = tempfile::tempdir().unwrap();
    let two = Server::start(files, imports.path(), two_dir.path());
    let (status, opened) = two.ask(
        "/rooms/open",
        r#"{"key": "player_alice", "keep": true, "folder": "carried"}"#,
    );
    assert_eq!(status, 200, "{opened}");
    assert_eq!(two.said(), [format!("back {id} 7 vec3(3, 64, 4)")]);

    // It closes without delete: what it left on disk has the records as the room stopped.
    let (status, _) = two.ask("/rooms/close", r#"{"key": "player_alice"}"#);
    assert_eq!(status, 200);
    let left = two_dir
        .path()
        .join("world/dimensions/minecraft/home__player_alice");
    let records = std::fs::read_to_string(left.join("data/netherforge/centities.json")).unwrap();
    assert!(records.contains(&id), "{records}");
    two.control.stop(&*two.platform);
}
