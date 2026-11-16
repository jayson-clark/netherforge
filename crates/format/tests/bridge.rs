//! Format's `BridgeTest` and `BridgeAuthTest`: the recorded session in
//! `packages/format/testdata/bridge/` is the protocol's contract fixture. Every
//! side of the bridge (the Kotlin plugin, this, the editor) must read each line
//! and write it back identically, and every message has its declared shape.

use std::collections::BTreeMap;

use netherforge_format::bridge::rpc::{self, Frame, Message};
use netherforge_format::bridge::{self, Thread, auth};
use serde_json::{Value, json};

fn lines() -> Vec<String> {
    let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../packages/format/testdata/bridge/session.ndjson");
    std::fs::read_to_string(path)
        .unwrap()
        .lines()
        .filter(|l| !l.trim().is_empty())
        .map(String::from)
        .collect()
}

fn messages(frame: Frame) -> Vec<Message> {
    match frame {
        Frame::Single(m) => vec![m],
        Frame::Batch(ms) => ms,
    }
}

#[test]
fn the_recorded_session_round_trips() {
    for line in lines() {
        let frame = rpc::decode(&line);
        assert!(
            frame
                .messages()
                .iter()
                .all(|m| !matches!(m, Message::Invalid { .. })),
            "{line}"
        );
        assert_eq!(rpc::frame_line(&frame), line);
    }
}

/// Methods the recording calls on purpose that the protocol doesn't have, to record the answer: each must be
/// answered with "method not found". Any other unknown method is drift.
const DELIBERATELY_UNKNOWN: &[&str] = &["debugger/attach"];

/// Two JSON values alike, a whole number and the same number as a double alike (Kotlin keeps `64` as written;
/// a typed double writes `64.0`).
fn same(a: &Value, b: &Value) -> bool {
    match (a, b) {
        (Value::Number(x), Value::Number(y)) => x.as_f64() == y.as_f64(),
        (Value::Array(x), Value::Array(y)) => {
            x.len() == y.len() && x.iter().zip(y).all(|(a, b)| same(a, b))
        }
        (Value::Object(x), Value::Object(y)) => {
            x.len() == y.len() && x.keys().eq(y.keys()) && x.iter().all(|(k, v)| same(v, &y[k]))
        }
        _ => a == b,
    }
}

#[test]
fn every_recorded_message_has_its_declared_shape() {
    // Each unanswered request's id → its method, or none for a deliberately unknown one.
    let mut asked: BTreeMap<String, Option<String>> = BTreeMap::new();
    // A request whose answer must be an error, by id: the error code it must carry.
    let mut must_fail: BTreeMap<String, i64> = BTreeMap::new();
    let mut answered = 0;
    for message in lines().iter().flat_map(|l| messages(rpc::decode(l))) {
        match message {
            Message::Request { id, method, params } => {
                let id = id.to_string();
                assert!(!asked.contains_key(&id), "a request reuses the id {id}");
                match bridge::reencode_params(&method, params.as_ref()) {
                    None => {
                        assert!(
                            DELIBERATELY_UNKNOWN.contains(&method.as_str()),
                            "\"{method}\" isn't a method of the protocol"
                        );
                        asked.insert(id.clone(), None);
                        must_fail.insert(id, rpc::METHOD_NOT_FOUND);
                    }
                    // Params the declared type can't read are recorded to show the answer: invalid params.
                    Some(Err(_)) => {
                        asked.insert(id.clone(), Some(method));
                        must_fail.insert(id, rpc::INVALID_PARAMS);
                    }
                    // Params written by the declared type are what was recorded.
                    Some(Ok(written)) => {
                        let alike = match (&params, &written) {
                            (None, None) => true,
                            (Some(a), Some(b)) => same(a, b),
                            _ => false,
                        };
                        assert!(alike, "{method}: {params:?} written back as {written:?}");
                        asked.insert(id, Some(method));
                    }
                }
            }
            Message::Response { id, outcome } => {
                let id = id.to_string();
                let method = asked
                    .remove(&id)
                    .unwrap_or_else(|| panic!("a response to {id}, which nothing asked"));
                if let Some(code) = must_fail.remove(&id) {
                    assert_eq!(
                        outcome.as_ref().err().map(|e| e.code),
                        Some(code),
                        "the answer to {id}"
                    );
                }
                if let Ok(result) = outcome {
                    let method = method.expect("an unknown method can't have a result");
                    bridge::check_result(&method, &result)
                        .unwrap()
                        .unwrap_or_else(|e| panic!("{method}'s result: {e}"));
                    answered += 1;
                }
            }
            Message::Notification { method, params } => {
                bridge::check_notification(&method, params.as_ref())
                    .unwrap_or_else(|| panic!("\"{method}\" isn't a notification"))
                    .unwrap_or_else(|e| panic!("{method}: {e}"));
            }
            Message::Invalid { .. } => panic!("invalid: {message:?}"),
        }
    }
    assert!(answered > 10);
    let Message::Request { params, .. } = &messages(rpc::decode(&lines()[0]))[0] else {
        panic!("the session starts with a hello")
    };
    let hello = bridge::decode_params::<bridge::Hello>(params.as_ref()).unwrap();
    assert_eq!(
        hello.protocol,
        bridge::PROTOCOL,
        "the recorded hello is this protocol's"
    );
}

#[test]
fn the_console_stream_carries_its_entries_in_order() {
    use bridge::{ConsoleEntry, LogLevel};
    let entries = vec![
        ConsoleEntry::Log {
            level: LogLevel::Info,
            message: "a".into(),
            source: None,
        },
        ConsoleEntry::Log {
            level: LogLevel::Warn,
            message: "b".into(),
            source: None,
        },
    ];
    let notification = Message::notification(
        <bridge::Console as bridge::Stream>::NAME,
        Some(bridge::encode_batch::<bridge::Console>(&entries)),
    );
    assert_eq!(
        notification.line(),
        r#"{"jsonrpc":"2.0","method":"console","params":{"items":[{"type":"log","level":"info","message":"a"},{"type":"log","level":"warn","message":"b"}]}}"#
    );
    let Message::Notification { params, .. } = notification else {
        unreachable!()
    };
    assert_eq!(
        bridge::decode_batch::<bridge::Console>(params.as_ref()).unwrap(),
        entries
    );
}

/// The recording sets a value and is answered; what it doesn't show is a reset and a setting the owner never set.
#[test]
fn settings_messages_leave_out_what_they_dont_say() {
    let reset = bridge::SetSettingParams {
        namespace: "basic".into(),
        setting: "greeting".into(),
        value: None,
    };
    let request = Message::request(
        5,
        "set_setting",
        bridge::encode_params::<bridge::SetSetting>(&reset),
    );
    // No value puts the setting back to its default: the key is left out, not sent as null.
    assert_eq!(
        request.line(),
        r#"{"jsonrpc":"2.0","id":5,"method":"set_setting","params":{"namespace":"basic","setting":"greeting"}}"#
    );
    let text =
        r#"{"definition":{"type":"boolean","description":"On?","default":true},"value":true}"#;
    let state: bridge::SettingState = serde_json::from_str(text).unwrap();
    assert!(!state.set, "a value the owner didn't set is the default");
    assert_eq!(state.problem, None);
    assert_eq!(serde_json::to_string(&state).unwrap(), text);
}

#[test]
fn a_field_from_a_newer_version_is_ignored() {
    let status: bridge::Status =
        serde_json::from_value(json!({"players": ["alex"], "instances": 2, "tps": 19.9})).unwrap();
    assert_eq!(
        status,
        bridge::Status {
            players: vec!["alex".into()],
            instances: 2
        }
    );
}

#[test]
fn what_is_not_a_message_is_answered_as_the_spec_says() {
    fn invalid(line: &str) -> (Value, i64) {
        match rpc::decode(line) {
            Frame::Single(Message::Invalid { id, error }) => (id, error.code),
            other => panic!("{line}: {other:?}"),
        }
    }
    assert_eq!(
        invalid(r#"{"jsonrpc":"2.0","method":""#),
        (Value::Null, rpc::PARSE_ERROR)
    );
    assert_eq!(invalid("[]").1, rpc::INVALID_REQUEST);
    assert_eq!(
        invalid(r#"{"jsonrpc":"1.0","id":1,"method":"ping"}"#),
        (json!(1), rpc::INVALID_REQUEST)
    );
    for line in [
        r#"{"jsonrpc":"2.0","id":1,"method":7}"#,
        r#"{"jsonrpc":"2.0","id":1,"method":"ping","params":3}"#,
        r#"{"jsonrpc":"2.0","id":true,"method":"ping"}"#,
        r#"{"jsonrpc":"2.0","id":1,"result":1,"error":{"code":1,"message":"x"}}"#,
        r#"{"jsonrpc":"2.0","id":1,"error":{"code":"x","message":"x"}}"#,
    ] {
        assert_eq!(invalid(line).1, rpc::INVALID_REQUEST, "{line}");
    }
    // A batch's elements are read one by one: a bad one doesn't spoil the rest.
    let Frame::Batch(batch) = rpc::decode(
        r#"[{"jsonrpc":"2.0","id":"a","method":"ping"},1,{"jsonrpc":"2.0","method":"note"}]"#,
    ) else {
        panic!("a batch")
    };
    assert_eq!(batch[0], Message::request("a", "ping", None));
    assert!(matches!(batch[1], Message::Invalid { .. }));
    assert_eq!(batch[2], Message::notification("note", None));
    let Frame::Single(Message::Response { outcome, .. }) =
        rpc::decode(r#"{"jsonrpc":"2.0","id":4,"error":{"code":-32000,"message":"no","data":{}}}"#)
    else {
        panic!("a response")
    };
    let error = outcome.unwrap_err();
    assert_eq!((error.code, error.data), (-32000, Some(json!({}))));
}

#[test]
fn a_request_without_params_is_sent_without_them() {
    let instances = Message::request(
        3,
        "instances",
        bridge::encode_params::<bridge::Instances>(&bridge::NoParams),
    );
    assert_eq!(
        instances.line(),
        r#"{"jsonrpc":"2.0","id":3,"method":"instances"}"#
    );
    assert_eq!(
        bridge::encode_result::<bridge::StopParticleEffects>(&()),
        Value::Null
    );
}

#[test]
fn extensions_live_under_their_namespace_and_only_bridge_requests_skip_the_main_thread() {
    let requests = bridge::requests();
    let namespaced: Vec<&str> = requests
        .iter()
        .filter(|m| m.name.contains('/'))
        .map(|m| m.name)
        .collect();
    assert!(namespaced.iter().all(|n| {
        bridge::EXTENSIONS
            .iter()
            .any(|e| n.starts_with(&format!("{e}/")))
    }));
    let on_bridge: Vec<&str> = requests
        .iter()
        .filter(|m| m.thread == Thread::Bridge)
        .map(|m| m.name)
        .collect();
    assert_eq!(on_bridge, ["ping", "profiler_subscribe"]);
    let mut names: Vec<&str> = bridge::handshake()
        .iter()
        .chain(&requests)
        .map(|m| m.name)
        .chain(bridge::EVENTS.iter().copied())
        .chain(bridge::STREAMS.iter().copied())
        .collect();
    let count = names.len();
    names.sort();
    names.dedup();
    assert_eq!(names.len(), count, "two methods share a name");
    let instances = bridge::request("instances").unwrap();
    assert!(!instances.takes_params && instances.answers);
    let ping = bridge::request("ping").unwrap();
    assert!(!ping.takes_params && !ping.answers);
}

/// The plugin's Kotlin `Bridge.kt` declares the same methods, events and streams: what the recording doesn't
/// call is held to its list.
#[test]
fn every_method_kotlin_declares_is_here() {
    let kotlin = std::fs::read_to_string(std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join(
        "../../packages/format/src/commonMain/kotlin/dev/netherforge/format/bridge/Bridge.kt",
    ))
    .unwrap()
        + &std::fs::read_to_string(std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join(
            "../../packages/format/src/commonMain/kotlin/dev/netherforge/format/bridge/Bots.kt",
        ))
        .unwrap();
    let declared = regex::Regex::new(r#"Bridge(?:Method|Event|Stream)\("([a-z_/]+)""#).unwrap();
    let mut theirs: Vec<String> = declared
        .captures_iter(&kotlin)
        .map(|c| c[1].to_string())
        .collect();
    theirs.sort();
    let mut ours: Vec<String> = bridge::handshake()
        .iter()
        .chain(&bridge::requests())
        .map(|m| m.name.to_string())
        .chain(bridge::EVENTS.iter().map(|e| e.to_string()))
        .chain(bridge::STREAMS.iter().map(|s| s.to_string()))
        .collect();
    ours.sort();
    assert_eq!(ours, theirs);
}

// ---- the handshake ----

#[test]
fn the_recorded_handshakes_proofs_are_the_tokens() {
    let lines = lines();
    let first = |i: usize| messages(rpc::decode(&lines[i])).remove(0);
    let Message::Request { params, .. } = first(0) else {
        panic!()
    };
    let hello = bridge::decode_params::<bridge::Hello>(params.as_ref()).unwrap();
    let Message::Response { outcome, .. } = first(1) else {
        panic!()
    };
    let answer = bridge::decode_result::<bridge::Hello>(&outcome.unwrap()).unwrap();
    let Message::Request { params, .. } = first(2) else {
        panic!()
    };
    let proof = bridge::decode_params::<bridge::Authenticate>(params.as_ref()).unwrap();
    let editor = answer.nonce.unwrap();
    assert!(auth::is_nonce(&hello.nonce) && auth::is_nonce(&editor));
    assert_eq!(
        Some(auth::editor_proof("t0k3n", &hello.nonce, &editor)),
        answer.proof
    );
    assert_eq!(
        auth::plugin_proof("t0k3n", &editor, &hello.nonce),
        proof.proof
    );
    // The token itself never appears on the wire.
    assert!(lines.iter().take(4).all(|l| !l.contains("t0k3n")));
}
