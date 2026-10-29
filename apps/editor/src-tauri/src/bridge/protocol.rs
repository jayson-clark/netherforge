//! The parts of the dev bridge protocol (`packages/format/.../bridge/Bridge.kt`,
//! JSON-RPC 2.0 over NDJSON) the backend reads itself: the plugin's `hello`
//! and `authenticate` requests, to prove the token both ways (format's
//! `BridgeAuth` makes the same proofs) and check its protocol version, and the
//! answers to the backend's own requests (ids `"backend:<n>"`, so they never
//! meet the UI's numbers). Everything else passes through as text: the UI
//! speaks JSON-RPC itself (vscode-jsonrpc), typed by format's generated
//! `BridgeRequests` and `BridgeEvents`.
//!
//! Hand-written rather than a JSON-RPC crate: the backend relays frames and
//! reads two shapes, which is a few serde structs, not a client or server.

use serde::Deserialize;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};

use crate::error::{Error, ErrorCode, Result};

/// format's `Bridge.PROTOCOL`: the editor refuses a plugin of another.
/// `session.ndjson`'s first hello is this version (tested on both sides).
pub const PROTOCOL: u64 = 2;

/// format's `Bridge.PROTOCOL_MISMATCH`, what a refused hello is answered with.
pub const PROTOCOL_MISMATCH: i64 = -32001;

/// JSON-RPC's method-not-found: the plugin doesn't have the method (an
/// extension it lacks).
pub const METHOD_NOT_FOUND: i64 = -32601;

/// The request that exports the server's game data; the backend sends it
/// itself after a hello, and gives it longer to answer.
pub const EXPORT_GAME_DATA: &str = "export_game_data";

const BACKEND_ID: &str = "backend:";

#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Hello {
    /// The plugin's nonce for this connection: what the editor's proof covers.
    /// The token itself never crosses.
    #[serde(default)]
    pub nonce: String,
    /// Missing reads as 0, which no editor speaks.
    #[serde(default)]
    pub protocol: u64,
    pub plugin_version: String,
    pub minecraft: String,
    pub project: String,
}

#[derive(Deserialize)]
struct HelloRequest {
    jsonrpc: String,
    id: Value,
    method: String,
    params: Hello,
}

/// A connection's first frame, when it's the hello request: its id and params.
pub fn read_hello(line: &str) -> Option<(Value, Hello)> {
    let request: HelloRequest = serde_json::from_str(line).ok()?;
    (request.jsonrpc == "2.0" && request.method == "hello").then_some((request.id, request.params))
}

/// The answer to a hello of our protocol: the editor's own [nonce] and its
/// [proof] of the token over both nonces, which the plugin checks before it
/// proves the token itself.
pub fn hello_answer(id: &Value, nonce: &str, proof: &str) -> Value {
    json!({"jsonrpc": "2.0", "id": id, "result": {"protocol": PROTOCOL, "nonce": nonce, "proof": proof}})
}

#[derive(Deserialize)]
struct AuthenticateParams {
    proof: String,
}

#[derive(Deserialize)]
struct AuthenticateRequest {
    jsonrpc: String,
    id: Value,
    method: String,
    params: AuthenticateParams,
}

/// The plugin's second frame, when it's the authenticate request: its id and proof.
pub fn read_authenticate(line: &str) -> Option<(Value, String)> {
    let request: AuthenticateRequest = serde_json::from_str(line).ok()?;
    (request.jsonrpc == "2.0" && request.method == "authenticate")
        .then_some((request.id, request.params.proof))
}

/// The answer to an accepted proof: the connection is open.
pub fn authenticated(id: &Value) -> Value {
    json!({"jsonrpc": "2.0", "id": id, "result": null})
}

/// A new nonce: 24 random bytes, as hex.
pub fn nonce() -> String {
    let mut bytes = [0u8; 24];
    getrandom::fill(&mut bytes).expect("the system has randomness");
    hex::encode(bytes)
}

/// Whether [nonce] is one a plugin may send: hex, 32 to 64 digits.
pub fn is_nonce(nonce: &str) -> bool {
    (32..=64).contains(&nonce.len()) && nonce.bytes().all(|b| b.is_ascii_hexdigit())
}

/// The editor's proof of [token] for the plugin's nonce [plugin] and its own [editor].
pub fn editor_proof(token: &str, plugin: &str, editor: &str) -> String {
    hex::encode(hmac_sha256(
        token.as_bytes(),
        format!("editor\n{plugin}\n{editor}").as_bytes(),
    ))
}

/// The plugin's proof of [token] for the editor's nonce [editor] and its own [plugin].
pub fn plugin_proof(token: &str, editor: &str, plugin: &str) -> String {
    hex::encode(hmac_sha256(
        token.as_bytes(),
        format!("plugin\n{editor}\n{plugin}").as_bytes(),
    ))
}

/// HMAC-SHA256 (RFC 2104), over the `sha2` the backend already has.
fn hmac_sha256(key: &[u8], message: &[u8]) -> [u8; 32] {
    const BLOCK: usize = 64;
    let mut block = [0u8; BLOCK];
    if key.len() > BLOCK {
        block[..32].copy_from_slice(&Sha256::digest(key));
    } else {
        block[..key.len()].copy_from_slice(key);
    }
    let mut inner = Sha256::new();
    inner.update(block.map(|b| b ^ 0x36));
    inner.update(message);
    let mut outer = Sha256::new();
    outer.update(block.map(|b| b ^ 0x5c));
    outer.update(inner.finalize());
    outer.finalize().into()
}

/// Why a plugin speaking [theirs] is refused, as the console and the plugin say it.
pub fn mismatch(theirs: u64) -> String {
    format!(
        "This server's NetherForge plugin speaks dev bridge protocol {theirs} and the editor speaks {PROTOCOL}, so the editor can't talk to it. Use the plugin that came with this editor."
    )
}

/// The answer to a hello of another protocol version.
pub fn hello_refused(id: &Value, reason: &str) -> Value {
    json!({"jsonrpc": "2.0", "id": id, "error": {"code": PROTOCOL_MISMATCH, "message": reason}})
}

/// The backend's own request number [n].
pub fn backend_request(n: u64, method: &str) -> Value {
    json!({"jsonrpc": "2.0", "id": format!("{BACKEND_ID}{n}"), "method": method})
}

/// When [frame] answers one of the backend's own requests: which, and its outcome.
pub fn backend_response(frame: &Value) -> Option<(u64, Result<Value>)> {
    let n = frame
        .get("id")?
        .as_str()?
        .strip_prefix(BACKEND_ID)?
        .parse()
        .ok()?;
    if frame.get("method").is_some() {
        return None;
    }
    if let Some(error) = frame.get("error") {
        let code = match error.get("code").and_then(Value::as_i64) {
            Some(METHOD_NOT_FOUND) => ErrorCode::UnknownMethod,
            _ => ErrorCode::Plugin,
        };
        let message = error
            .get("message")
            .and_then(Value::as_str)
            .unwrap_or("The plugin refused the request");
        return Some((n, Err(Error::new(code, message))));
    }
    Some((n, Ok(frame.get("result").cloned().unwrap_or(Value::Null))))
}

/// A frame from the UI, checked to be JSON (a message or a batch) and made
/// one line. One whose id looks like the backend's is refused: its answer
/// would never reach the UI.
pub fn ui_frame(message: &str) -> Result<String> {
    let value: Value = serde_json::from_str(message).map_err(|e| {
        Error::new(
            ErrorCode::Invalid,
            format!("A bridge message isn't JSON: {e}"),
        )
    })?;
    let messages = match &value {
        Value::Array(items) => items.iter().collect(),
        Value::Object(_) => vec![&value],
        _ => {
            return Err(Error::new(
                ErrorCode::Invalid,
                "A bridge message is a JSON object or a batch of them",
            ));
        }
    };
    let backend = |m: &&Value| {
        m.get("id")
            .and_then(Value::as_str)
            .is_some_and(|id| id.starts_with(BACKEND_ID))
    };
    if messages.iter().any(backend) {
        return Err(Error::new(
            ErrorCode::Invalid,
            format!("Ids starting with \"{BACKEND_ID}\" are the backend's"),
        ));
    }
    // serde_json writes no raw newline: the frame is one line.
    Ok(serde_json::to_string(&value)?)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn session() -> Vec<String> {
        let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../../packages/format/testdata/bridge/session.ndjson");
        std::fs::read_to_string(&path)
            .unwrap_or_else(|e| panic!("couldn't read {}: {e}", path.display()))
            .lines()
            .filter(|l| !l.trim().is_empty())
            .map(str::to_string)
            .collect()
    }

    /// The recorded contract fixture: what this side reads, it reads.
    #[test]
    fn reads_the_recorded_hellos_and_speaks_their_protocol() {
        let lines = session();
        let frames: Vec<Value> = lines
            .iter()
            .map(|l| serde_json::from_str(l).unwrap())
            .collect();
        let hellos: Vec<_> = lines.iter().filter_map(|l| read_hello(l)).collect();
        assert_eq!(hellos.len(), 2);
        let (id, hello) = &hellos[0];
        assert_eq!(hello.protocol, PROTOCOL, "the plugin's protocol is ours");
        assert!(is_nonce(&hello.nonce));
        // The recorded answer is what we'd answer for the recorded token ("t0k3n") and editor nonce.
        let answer = &frames[1];
        let editor = answer["result"]["nonce"].as_str().unwrap();
        let proof = editor_proof("t0k3n", &hello.nonce, editor);
        assert_eq!(answer, &hello_answer(id, editor, &proof));
        // And the plugin's proof is the one we'd accept.
        let (auth_id, given) = read_authenticate(&lines[2]).expect("the recorded authenticate");
        assert_eq!(given, plugin_proof("t0k3n", editor, &hello.nonce));
        assert_eq!(frames[3], authenticated(&auth_id));
        assert!(
            lines.iter().take(4).all(|l| !l.contains("t0k3n")),
            "the token never crosses"
        );

        let (id, newer) = &hellos[1];
        assert_ne!(newer.protocol, PROTOCOL);
        let refused = hello_refused(id, &mismatch(newer.protocol));
        assert!(frames.contains(&refused), "{refused}");
    }

    #[test]
    fn only_a_hello_request_opens_a_connection() {
        assert!(
            read_hello(r#"{"jsonrpc":"2.0","method":"console","params":{"items":[]}}"#).is_none()
        );
        assert!(read_hello(r#"{"type":"hello","nonce":"t"}"#).is_none());
        // An old plugin without a protocol is one the editor doesn't speak.
        let (_, hello) = read_hello(
            r#"{"jsonrpc":"2.0","id":0,"method":"hello","params":{"token":"t","pluginVersion":"0","minecraft":"26.3","project":"/p"}}"#,
        )
        .unwrap();
        assert_eq!(hello.protocol, 0);
    }

    #[test]
    fn hmac_is_rfc_4231s() {
        // RFC 4231, test case 2.
        assert_eq!(
            hex::encode(hmac_sha256(b"Jefe", b"what do ya want for nothing?")),
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"
        );
        // Test case 6: a key longer than a block.
        assert_eq!(
            hex::encode(hmac_sha256(
                &[0xaa; 131],
                b"Test Using Larger Than Block-Size Key - Hash Key First"
            )),
            "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54"
        );
    }

    #[test]
    fn the_backends_own_requests_are_answered_to_it() {
        let frames: Vec<Value> = session()
            .iter()
            .map(|l| serde_json::from_str(l).unwrap())
            .collect();
        let recorded = frames
            .iter()
            .find(|f| f["id"] == "backend:1")
            .expect("session.ndjson has the backend's export");
        assert_eq!(recorded, &backend_request(1, EXPORT_GAME_DATA));
        assert!(
            backend_response(recorded).is_none(),
            "a request isn't an answer"
        );

        let ok = json!({"jsonrpc": "2.0", "id": "backend:7", "result": {"schema": 1}});
        assert_eq!(
            backend_response(&ok).unwrap().1.unwrap(),
            json!({"schema": 1})
        );
        let unknown = json!({"jsonrpc": "2.0", "id": "backend:8", "error": {"code": -32601, "message": "Unknown method"}});
        let (n, result) = backend_response(&unknown).unwrap();
        assert_eq!(n, 8);
        assert_eq!(result.unwrap_err().code(), ErrorCode::UnknownMethod);
        let failed = json!({"jsonrpc": "2.0", "id": "backend:9", "error": {"code": -32000, "message": "no"}});
        assert_eq!(
            backend_response(&failed).unwrap().1.unwrap_err().code(),
            ErrorCode::Plugin
        );
        assert!(backend_response(&json!({"jsonrpc": "2.0", "id": 1, "result": null})).is_none());
    }

    #[test]
    fn a_ui_frame_is_json_on_one_line_with_the_uis_ids() {
        let line =
            ui_frame("{\"jsonrpc\": \"2.0\",\n \"id\": 3, \"method\": \"instances\"}").unwrap();
        assert!(!line.contains('\n'));
        assert_eq!(
            serde_json::from_str::<Value>(&line).unwrap(),
            json!({"jsonrpc": "2.0", "id": 3, "method": "instances"})
        );
        assert!(ui_frame(r#"[{"jsonrpc":"2.0","id":1,"method":"ping"}]"#).is_ok());
        assert!(ui_frame("reload").is_err());
        assert!(ui_frame("3").is_err());
        assert!(ui_frame(r#"{"jsonrpc":"2.0","id":"backend:1","method":"ping"}"#).is_err());
    }
}
