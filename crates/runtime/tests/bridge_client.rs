//! The bridge's connection (Kotlin's `BridgeClientTest`), against a test playing the editor over a real socket:
//! the handshake and its proofs, an impostor on the port, the frame caps, giving up on an editor that's gone, and a
//! slow burst of stream items going out in a few batches.

use std::io::{BufRead, BufReader, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::mpsc::{Receiver, Sender, channel};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use netherforge_format::bridge::rpc::{self, Frame, Message};
use netherforge_format::bridge::{self, HelloParams, auth};
use netherforge_runtime::bridge::client::{Client, Events, MAX_FRAME, Settings};
use serde_json::{Value, json};

struct Heard {
    problems: Mutex<Sender<String>>,
    connected: Mutex<Sender<()>>,
    abandoned: AtomicBool,
}

impl Events for Heard {
    fn hello(&self, nonce: &str) -> HelloParams {
        HelloParams {
            nonce: nonce.into(),
            protocol: bridge::PROTOCOL,
            plugin_version: "0.1.0".into(),
            minecraft: "26.3".into(),
            project: "/p".into(),
        }
    }
    fn frame(&self, _: Frame) {}
    fn connected(&self) {
        let _ = self.connected.lock().unwrap().send(());
    }
    fn problem(&self, message: String) {
        let _ = self.problems.lock().unwrap().send(message);
    }
    fn abandoned(&self) {
        self.abandoned.store(true, Ordering::SeqCst);
    }
}

struct Harness {
    listener: Option<TcpListener>,
    client: Client,
    heard: Arc<Heard>,
    problems: Receiver<String>,
    connected: Receiver<()>,
    now: Arc<AtomicU64>,
}

impl Harness {
    fn new(abandon_after: Duration) -> Harness {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let (problems_to, problems) = channel();
        let (connected_to, connected) = channel();
        let heard = Arc::new(Heard {
            problems: Mutex::new(problems_to),
            connected: Mutex::new(connected_to),
            abandoned: AtomicBool::new(false),
        });
        let now = Arc::new(AtomicU64::new(0));
        let clock = now.clone();
        let settings = Settings {
            abandon_after,
            clock: Arc::new(move || clock.load(Ordering::SeqCst)),
            ..Settings::new(listener.local_addr().unwrap().port(), "secret")
        };
        let client = Client::new(settings, heard.clone());
        Harness {
            listener: Some(listener),
            client,
            heard,
            problems,
            connected,
            now,
        }
    }

    fn accept(&self) -> (TcpStream, BufReader<TcpStream>) {
        let (socket, _) = self.listener.as_ref().unwrap().accept().unwrap();
        socket
            .set_read_timeout(Some(Duration::from_secs(10)))
            .unwrap();
        let reader = BufReader::new(socket.try_clone().unwrap());
        (socket, reader)
    }

    /// Reads the hello and answers it as the editor does with `token`'s proof, then takes the client's proof back.
    fn answer_hello(&self, socket: &mut TcpStream, reader: &mut BufReader<TcpStream>, token: &str) {
        let line = read_line(reader);
        assert!(line.contains("\"hello\""), "{line}");
        assert!(!line.contains("secret"), "the token never crosses: {line}");
        let Frame::Single(Message::Request { id, params, .. }) = rpc::decode(&line) else {
            panic!("a request: {line}")
        };
        let hello: HelloParams = serde_json::from_value(params.unwrap()).unwrap();
        let editor = auth::nonce();
        let answer = json!({ "protocol": bridge::PROTOCOL, "nonce": editor, "proof": auth::editor_proof(token, &hello.nonce, &editor) });
        write_line(socket, &Message::ok(id, answer).line());
        if token != "secret" {
            return;
        }
        let line = read_line(reader);
        let Frame::Single(Message::Request { id, params, .. }) = rpc::decode(&line) else {
            panic!("authenticate: {line}")
        };
        let proof = params.unwrap()["proof"].as_str().unwrap().to_owned();
        assert_eq!(proof, auth::plugin_proof("secret", &editor, &hello.nonce));
        write_line(socket, &Message::ok(id, Value::Null).line());
        self.connected
            .recv_timeout(Duration::from_secs(10))
            .expect("accepted");
    }

    fn problem(&self) -> String {
        self.problems
            .recv_timeout(Duration::from_secs(10))
            .expect("a problem")
    }
}

impl Drop for Harness {
    fn drop(&mut self) {
        self.client.stop();
    }
}

fn read_line(reader: &mut BufReader<TcpStream>) -> String {
    let mut line = String::new();
    reader.read_line(&mut line).unwrap();
    line.trim_end().to_owned()
}

fn write_line(socket: &mut TcpStream, line: &str) {
    socket.write_all(format!("{line}\n").as_bytes()).unwrap();
}

/// Whether the client closed its end: a read sees the end of the stream.
fn closed_by_peer(reader: &mut BufReader<TcpStream>) -> bool {
    let mut line = String::new();
    matches!(reader.read_line(&mut line), Ok(0) | Err(_))
}

#[test]
fn the_handshake_proves_the_token_both_ways_and_then_frames_flow() {
    let harness = Harness::new(Duration::MAX);
    harness.client.start();
    let (mut socket, mut reader) = harness.accept();
    harness.answer_hello(&mut socket, &mut reader, "secret");
    assert!(harness.client.connected());
    harness
        .client
        .notify::<bridge::StatusChanged>(&bridge::Status {
            players: vec!["Alex".into()],
            instances: 2,
        });
    let line = read_line(&mut reader);
    assert_eq!(
        serde_json::from_str::<Value>(&line).unwrap(),
        json!({ "jsonrpc": "2.0", "method": "status", "params": { "players": ["Alex"], "instances": 2 } })
    );
}

#[test]
fn something_on_the_editors_port_that_cant_prove_the_token_gets_nothing_and_no_connection() {
    let harness = Harness::new(Duration::MAX);
    harness.client.start();
    let (mut socket, mut reader) = harness.accept();
    harness.answer_hello(&mut socket, &mut reader, "not the token");
    assert!(
        harness
            .problem()
            .contains("isn't the editor that started this server")
    );
    assert!(closed_by_peer(&mut reader));
    assert!(
        harness
            .connected
            .recv_timeout(Duration::from_millis(200))
            .is_err()
    );
}

#[test]
fn a_frame_over_the_small_cap_before_the_hellos_answer_closes_the_connection() {
    let harness = Harness::new(Duration::MAX);
    harness.client.start();
    let (mut socket, mut reader) = harness.accept();
    read_line(&mut reader);
    write_line(&mut socket, &"x".repeat(20_000));
    assert!(harness.problem().contains("before answering the hello"));
    assert!(closed_by_peer(&mut reader), "the client closed");
    assert!(harness.connected.try_recv().is_err(), "never accepted");
}

#[test]
fn after_the_hello_the_larger_cap_holds_and_a_frame_past_it_closes_the_connection() {
    let harness = Harness::new(Duration::MAX);
    harness.client.start();
    let (mut socket, mut reader) = harness.accept();
    harness.answer_hello(&mut socket, &mut reader, "secret");
    write_line(&mut socket, &"x".repeat(20_000));
    assert!(
        harness
            .problems
            .recv_timeout(Duration::from_millis(300))
            .is_err(),
        "within the cap"
    );
    socket
        .write_all("y".repeat(MAX_FRAME + 1).as_bytes())
        .unwrap();
    assert!(harness.problem().contains("closing the connection"));
    assert!(closed_by_peer(&mut reader), "the client closed");
}

#[test]
fn time_connected_doesnt_count_towards_giving_up_only_time_since_the_editor_went() {
    let harness = Harness::new(Duration::from_millis(300));
    harness.client.start();
    {
        let (mut socket, mut reader) = harness.accept();
        harness.answer_hello(&mut socket, &mut reader, "secret");
        // Connected for far longer than the client waits for an editor that's gone.
        harness.now.fetch_add(10_000, Ordering::SeqCst);
    }
    // The editor went just now on the client's clock, so it tries again rather than giving up.
    let (mut socket, mut reader) = harness.accept();
    harness.answer_hello(&mut socket, &mut reader, "secret");
    assert!(!harness.heard.abandoned.load(Ordering::SeqCst));
    drop(socket);
    drop(reader);
    let mut harness = harness;
    harness.listener.take();
    let deadline = std::time::Instant::now() + Duration::from_secs(10);
    while !harness.heard.abandoned.load(Ordering::SeqCst) && std::time::Instant::now() < deadline {
        harness.now.fetch_add(100, Ordering::SeqCst);
        std::thread::sleep(Duration::from_millis(20));
    }
    assert!(
        harness.heard.abandoned.load(Ordering::SeqCst),
        "gave up once the editor had been gone past the limit"
    );
}

#[test]
fn a_slow_burst_of_stream_items_still_goes_out_in_a_few_batches_and_in_order() {
    const ITEMS: usize = 200;
    let harness = Harness::new(Duration::MAX);
    harness.client.start();
    let (mut socket, mut reader) = harness.accept();
    harness.answer_hello(&mut socket, &mut reader, "secret");
    let client = harness.client.clone();
    // An item a millisecond: a writer sending each batch as soon as it's woken would send them one by one.
    let producer = std::thread::spawn(move || {
        for i in 0..ITEMS {
            client.stream_value("test", json!(format!("item {i}")));
            std::thread::sleep(Duration::from_millis(1));
        }
    });
    let mut seen = Vec::new();
    let mut frames = 0;
    while seen.len() < ITEMS {
        let line = read_line(&mut reader);
        let Frame::Single(Message::Notification { params, .. }) = rpc::decode(&line) else {
            panic!("{line}")
        };
        for item in params.unwrap()["items"].as_array().unwrap() {
            seen.push(item.as_str().unwrap().to_owned());
        }
        frames += 1;
    }
    producer.join().unwrap();
    let expected: Vec<String> = (0..ITEMS).map(|i| format!("item {i}")).collect();
    assert_eq!(seen, expected);
    assert!(frames <= ITEMS / 4, "{frames} frames for {ITEMS} items");
}

#[test]
fn what_was_sent_while_disconnected_is_delivered_after_the_next_hello() {
    let harness = Harness::new(Duration::MAX);
    harness.client.send(&Message::notification("early", None));
    harness.client.start();
    let (mut socket, mut reader) = harness.accept();
    harness.answer_hello(&mut socket, &mut reader, "secret");
    assert_eq!(
        read_line(&mut reader),
        r#"{"jsonrpc":"2.0","method":"early"}"#
    );
}
