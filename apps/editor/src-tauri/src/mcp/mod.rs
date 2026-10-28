//! The editor's MCP server, for coding agents: `http://127.0.0.1:<port>/mcp`.
//!
//! This side is only the HTTP pipe. The MCP server itself is the official
//! TypeScript SDK's `McpServer`, running in the UI next to the stores and
//! `format` its tools answer from (`apps/editor/src/core/mcp/`): it does the
//! protocol (initialize, versions, ping, tool listing, argument validation).
//! [http] checks who's asking and frames the body; [UiPipe] carries each
//! JSON-RPC message to the UI as `mcp://message` and brings the SDK's response
//! back through `mcp_send`. No answer within [UI_TIMEOUT] is an error to the
//! client, never a hang.
//!
//! It runs while the editor is open, whether or not a project is (tools say
//! when they need one), and [McpServer::apply] restarts it when its settings
//! change. While the open project isn't trusted ([crate::trust]), every
//! request is refused here ([UiPipe::set_refusal], [UNTRUSTED]) and none
//! reaches the UI's tools. A port that's taken is reported in [McpStatus], not fatal. Every
//! request needs the install's bearer token ([token]).

pub mod http;
pub mod token;

use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use serde::Serialize;
use serde_json::{Value, json};
use tokio::net::TcpListener;
use tokio::sync::oneshot;
use tokio::task::JoinHandle;

use crate::app::events::EventSink;

/// Long enough for a reload of a whole project on a busy server.
pub const UI_TIMEOUT: Duration = Duration::from_secs(120);

/// JSON-RPC's code for an error inside the server.
const INTERNAL_ERROR: i64 = -32603;

/// The server-defined JSON-RPC code for a request refused because the open project isn't trusted.
pub const UNTRUSTED: i64 = -32001;

/// `mcp://message`'s payload: one JSON-RPC message from an agent, and the
/// `MCP-Protocol-Version` header it came with (the SDK's transport checks it).
#[derive(Debug, Clone, Serialize, specta::Type, tauri_specta::Event)]
#[serde(rename_all = "camelCase")]
#[tauri_specta(event_name = "mcp://message")]
pub struct McpMessage {
    #[specta(type = specta_typescript::Unknown)]
    pub message: Value,
    pub protocol_version: Option<String>,
}

/// Where the HTTP side sends each message (the UI in the app, a fake in tests).
pub trait Forward: Send + Sync + 'static {
    /// Hands [message] on. A request (a `method` and an `id`) gets its response
    /// back; anything else (notifications, a client's responses) gets none.
    fn forward(
        &self,
        message: Value,
        protocol_version: Option<String>,
    ) -> impl Future<Output = Option<Value>> + Send;
}

/// Carries messages to the UI's MCP server and its responses back.
///
/// Requests from different agents can share an id (each counts from its own
/// 1), so a request goes to the UI under an id of the pipe's own, and its
/// response comes back with the agent's id restored.
pub struct UiPipe {
    sink: Arc<dyn EventSink>,
    /// Why agents are kept out, while they are: the open project isn't
    /// trusted (`AppState::refresh_agents`). Every request is then answered
    /// with it here and never reaches the UI's tools.
    refusal: Mutex<Option<String>>,
    pending: Mutex<HashMap<u64, oneshot::Sender<Value>>>,
    next: AtomicU64,
    timeout: Duration,
}

fn is_request(message: &Value) -> bool {
    message.get("method").is_some_and(Value::is_string)
        && message
            .get("id")
            .is_some_and(|id| id.is_string() || id.is_number())
}

impl UiPipe {
    pub fn new(sink: Arc<dyn EventSink>, timeout: Duration) -> Self {
        Self {
            sink,
            refusal: Mutex::new(None),
            pending: Mutex::new(HashMap::new()),
            next: AtomicU64::new(1),
            timeout,
        }
    }

    /// Keeps agents out with [refusal] (a sentence they're answered with), or lets them in (`None`).
    pub fn set_refusal(&self, refusal: Option<String>) {
        *self.refusal.lock().unwrap() = refusal;
    }

    fn emit(&self, message: Value, protocol_version: Option<String>) {
        self.sink.emit(&McpMessage {
            message,
            protocol_version,
        });
    }

    /// A message the UI's MCP server sent. A response to a request waiting
    /// here completes it (true). Anything else has nowhere to go over
    /// stateless HTTP and is dropped: a response that came too late, or a
    /// notification or request meant for the agent.
    pub fn deliver(&self, message: Value) -> bool {
        if message.get("method").is_some() {
            return false;
        }
        let Some(id) = message.get("id").and_then(Value::as_u64) else {
            return false;
        };
        let Some(waiter) = self.pending.lock().unwrap().remove(&id) else {
            return false;
        };
        waiter.send(message).is_ok()
    }
}

impl Forward for UiPipe {
    async fn forward(&self, mut message: Value, protocol_version: Option<String>) -> Option<Value> {
        let refusal = self.refusal.lock().unwrap().clone();
        if let Some(refusal) = refusal {
            // Nothing reaches the UI's tools: a request gets the refusal, anything else is dropped.
            return is_request(&message).then(|| {
                json!({
                    "jsonrpc": "2.0",
                    "id": message["id"],
                    "error": { "code": UNTRUSTED, "message": refusal },
                })
            });
        }
        if !is_request(&message) {
            self.emit(message, protocol_version);
            return None;
        }
        let id = self.next.fetch_add(1, Ordering::Relaxed);
        let theirs = std::mem::replace(&mut message["id"], json!(id));
        let (sender, receiver) = oneshot::channel();
        self.pending.lock().unwrap().insert(id, sender);
        self.emit(message, protocol_version);
        let answer = tokio::time::timeout(self.timeout, receiver).await;
        self.pending.lock().unwrap().remove(&id);
        let mut response = match answer {
            Ok(Ok(response)) => response,
            _ => json!({
                "jsonrpc": "2.0",
                "error": {
                    "code": INTERNAL_ERROR,
                    "message": "The NetherForge editor didn't answer. Is its window open?",
                },
            }),
        };
        response["id"] = theirs;
        Some(response)
    }
}

/// What Settings → Agents shows.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, specta::Type)]
#[serde(rename_all = "camelCase")]
pub struct McpStatus {
    pub enabled: bool,
    pub port: u16,
    /// Set while listening: `http://127.0.0.1:<port>/mcp`.
    pub url: Option<String>,
    /// Why it isn't listening, when it should be.
    pub error: Option<String>,
    /// What agents send as `Authorization: Bearer <token>`.
    pub token: String,
}

pub fn url_for(port: u16) -> String {
    format!("http://127.0.0.1:{port}{}", http::PATH)
}

pub struct McpServer {
    pub pipe: Arc<UiPipe>,
    token: Arc<str>,
    running: tokio::sync::Mutex<Option<JoinHandle<()>>>,
    status: Mutex<McpStatus>,
}

impl McpServer {
    pub fn new(sink: Arc<dyn EventSink>, token: String) -> Arc<Self> {
        Arc::new(Self {
            pipe: Arc::new(UiPipe::new(sink, UI_TIMEOUT)),
            status: Mutex::new(McpStatus {
                token: token.clone(),
                ..McpStatus::default()
            }),
            token: token.into(),
            running: tokio::sync::Mutex::new(None),
        })
    }

    pub fn status(&self) -> McpStatus {
        self.status.lock().unwrap().clone()
    }

    /// Stops the server if it runs, then starts it on [port] if [enabled].
    /// Must run inside a tokio runtime.
    pub async fn apply(&self, enabled: bool, port: u16) -> McpStatus {
        let mut running = self.running.lock().await;
        if let Some(task) = running.take() {
            task.abort();
            // Wait for it to drop its listener, so the port is free to bind again.
            let _ = task.await;
        }
        let mut status = McpStatus {
            enabled,
            port,
            url: None,
            error: None,
            token: self.token.to_string(),
        };
        if enabled {
            match TcpListener::bind(("127.0.0.1", port)).await {
                Ok(listener) => {
                    let task =
                        tokio::spawn(http::serve(listener, self.pipe.clone(), self.token.clone()));
                    *running = Some(task);
                    status.url = Some(url_for(port));
                }
                Err(error) => {
                    status.error = Some(format!(
                        "Couldn't listen on port {port}: {error}. Pick another port, or close what's using it."
                    ));
                }
            }
        }
        *self.status.lock().unwrap() = status.clone();
        status
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::app::events::RecordingSink;

    async fn next_message(sink: &RecordingSink, count: usize) -> Value {
        loop {
            let messages = sink.named::<McpMessage>();
            if messages.len() >= count {
                return messages[count - 1].clone();
            }
            tokio::task::yield_now().await;
        }
    }

    #[tokio::test]
    async fn requests_go_to_the_ui_under_their_own_id_and_come_back_with_the_agents() {
        let sink = Arc::new(RecordingSink::default());
        let pipe = Arc::new(UiPipe::new(sink.clone(), Duration::from_secs(5)));
        // Two agents, both counting from 1.
        let calls: Vec<_> = (0..2)
            .map(|_| {
                let pipe = pipe.clone();
                tokio::spawn(async move {
                    let request = json!({ "jsonrpc": "2.0", "id": 1, "method": "tools/list" });
                    pipe.forward(request, Some("2025-06-18".into())).await
                })
            })
            .collect();
        let first = next_message(&sink, 1).await;
        let second = next_message(&sink, 2).await;
        assert_eq!(first["protocolVersion"], "2025-06-18");
        assert_eq!(first["message"]["method"], "tools/list");
        let ids = [
            first["message"]["id"].clone(),
            second["message"]["id"].clone(),
        ];
        assert_ne!(ids[0], ids[1], "told apart on the way to the UI");
        for (n, id) in ids.iter().enumerate() {
            assert!(pipe.deliver(json!({ "jsonrpc": "2.0", "id": id, "result": { "n": n } })));
        }
        let mut answers = Vec::new();
        for call in calls {
            answers.push(call.await.unwrap().unwrap());
        }
        for answer in &answers {
            assert_eq!(answer["id"], 1, "the agent's own id");
        }
        let mut seen: Vec<_> = answers.iter().map(|a| a["result"]["n"].clone()).collect();
        seen.sort_by_key(|n| n.as_u64());
        assert_eq!(seen, [json!(0), json!(1)]);
        assert!(
            !pipe.deliver(json!({ "jsonrpc": "2.0", "id": ids[0], "result": {} })),
            "answered once"
        );
    }

    #[tokio::test]
    async fn an_untrusted_project_keeps_agents_out_until_trusted() {
        let sink = Arc::new(RecordingSink::default());
        let pipe = UiPipe::new(sink.clone(), Duration::from_millis(50));
        pipe.set_refusal(Some("not trusted".into()));
        let request = json!({ "jsonrpc": "2.0", "id": 7, "method": "tools/call" });
        let answer = pipe.forward(request.clone(), None).await.unwrap();
        assert_eq!(answer["id"], 7);
        assert_eq!(answer["error"]["code"], UNTRUSTED);
        assert_eq!(answer["error"]["message"], "not trusted");
        let note = json!({ "jsonrpc": "2.0", "method": "notifications/initialized" });
        assert_eq!(pipe.forward(note, None).await, None);
        assert!(
            sink.named::<McpMessage>().is_empty(),
            "nothing reached the UI"
        );

        pipe.set_refusal(None);
        let answer = pipe.forward(request, None).await.unwrap();
        assert_eq!(
            answer["error"]["code"], INTERNAL_ERROR,
            "through to the (silent) UI"
        );
        assert_eq!(sink.named::<McpMessage>().len(), 1);
    }

    #[tokio::test]
    async fn notifications_pass_through_unanswered() {
        let sink = Arc::new(RecordingSink::default());
        let pipe = UiPipe::new(sink.clone(), Duration::from_secs(5));
        let note = json!({ "jsonrpc": "2.0", "method": "notifications/initialized" });
        assert_eq!(pipe.forward(note.clone(), None).await, None);
        assert_eq!(sink.named::<McpMessage>()[0]["message"], note);
        // What the UI's server sends that isn't a response has nowhere to go.
        assert!(
            !pipe
                .deliver(json!({ "jsonrpc": "2.0", "method": "notifications/tools/list_changed" }))
        );
        assert!(!pipe.deliver(json!({ "jsonrpc": "2.0", "id": 99, "result": {} })));
    }

    #[tokio::test]
    async fn silence_becomes_an_error_with_the_agents_id() {
        let sink = Arc::new(RecordingSink::default());
        let pipe = UiPipe::new(sink.clone(), Duration::from_millis(50));
        let request = json!({ "jsonrpc": "2.0", "id": "a", "method": "tools/call" });
        let answer = pipe.forward(request, None).await.unwrap();
        assert_eq!(answer["id"], "a");
        assert_eq!(answer["error"]["code"], INTERNAL_ERROR);
        assert!(
            answer["error"]["message"]
                .as_str()
                .unwrap()
                .contains("didn't answer")
        );
        assert!(
            pipe.pending.lock().unwrap().is_empty(),
            "forgotten after the timeout"
        );
    }

    #[tokio::test]
    async fn applies_settings_and_reports_a_taken_port() {
        let server = McpServer::new(Arc::new(RecordingSink::default()), "secret".into());
        let taken = std::net::TcpListener::bind(("127.0.0.1", 0)).unwrap();
        let port = taken.local_addr().unwrap().port();
        let status = server.apply(true, port).await;
        assert_eq!(status.url, None);
        assert!(status.error.unwrap().contains(&port.to_string()));

        // After the error, a free port works. A port found free and released
        // can be taken by anything else on the machine before it's bound
        // again, so this asks for a fresh one and tries a few.
        drop(taken);
        let mut status = server.apply(true, port).await;
        let mut port = port;
        for _ in 0..5 {
            if status.url.is_some() {
                break;
            }
            port = std::net::TcpListener::bind(("127.0.0.1", 0))
                .unwrap()
                .local_addr()
                .unwrap()
                .port();
            status = server.apply(true, port).await;
        }
        assert_eq!(status.url, Some(url_for(port)));
        assert_eq!(status.token, "secret");
        assert!(
            tokio::net::TcpStream::connect(("127.0.0.1", port))
                .await
                .is_ok()
        );

        let status = server.apply(false, port).await;
        assert_eq!(
            status,
            McpStatus {
                enabled: false,
                port,
                url: None,
                error: None,
                token: "secret".into(),
            }
        );
        assert_eq!(server.status(), status);
    }
}
