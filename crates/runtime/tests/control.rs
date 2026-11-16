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
