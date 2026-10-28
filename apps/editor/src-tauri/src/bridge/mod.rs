//! The editor's side of the dev bridge (protocol: `packages/format/.../bridge/Bridge.kt`,
//! JSON-RPC 2.0 over NDJSON).
//!
//! The editor listens on `127.0.0.1:0` before launching the server and passes
//! the port and a random token to it. The plugin connects (and reconnects
//! after a drop); its first frame must be a `hello` request carrying the
//! token within [HELLO_TIMEOUT], or the connection is closed unanswered. A
//! hello of another protocol version is answered with an error saying so
//! ([BridgeHandler::refused] gets the same sentence) and closed. A newer
//! accepted connection replaces an older one.
//!
//! A frame is capped in size, by [FrameReader] (which never buffers more
//! than the cap): [MAX_FRAME_BEFORE_HELLO] until the hello, [MAX_FRAME] after
//! it. A longer one closes the connection and the reason goes to
//! [BridgeHandler::refused], so it reaches the console. The plugin's reader
//! (`FrameReader.kt`) caps what the editor sends the same way.
//!
//! After that the backend is a relay: [Bridge::send] writes the UI's frames,
//! and every frame the plugin sends goes to [BridgeHandler::message] as its
//! text, except the answers to the backend's own requests ([Bridge::request],
//! ids `"backend:<n>"`).

pub mod frames;
pub mod protocol;

use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use serde_json::Value;
use tokio::io::{AsyncWriteExt, BufReader};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{mpsc, oneshot};
use tokio::task::AbortHandle;

use crate::error::{Error, ErrorCode, Result};
use frames::{Frame, FrameReader};
use protocol::Hello;

pub const HELLO_TIMEOUT: Duration = Duration::from_secs(10);
/// The hello is a token, a version and a path: nothing a stranger gets to make bigger.
pub const MAX_FRAME_BEFORE_HELLO: usize = 16 * 1024;
/// One frame from the plugin once it's authenticated. The game data export
/// (the biggest, about 7 MB) is one.
pub const MAX_FRAME: usize = 64 * 1024 * 1024;
/// Exporting every registry and collision shape takes a while on a cold server.
pub const EXPORT_TIMEOUT: Duration = Duration::from_secs(300);

/// What the bridge tells its owner. Called from the bridge's tasks.
pub trait BridgeHandler: Send + Sync + 'static {
    fn connected(&self, hello: &Hello);
    fn disconnected(&self);
    /// A plugin connection the editor closed (its hello was refused, or a
    /// frame was too large): why, as a sentence.
    fn refused(&self, reason: &str);
    /// A frame from the plugin, as it was sent.
    fn message(&self, frame: String);
}

#[derive(Clone)]
pub struct Bridge {
    inner: Arc<Inner>,
}

type Pending = HashMap<u64, oneshot::Sender<Result<Value>>>;

struct Connection {
    id: u64,
    sender: mpsc::UnboundedSender<String>,
    tasks: Vec<AbortHandle>,
}

/// The frame caps in force; the constants, but a test shrinks them.
#[derive(Clone, Copy)]
struct Limits {
    before_hello: usize,
    after_hello: usize,
}

struct Inner {
    limits: Limits,
    token: String,
    port: u16,
    handler: Arc<dyn BridgeHandler>,
    connection: Mutex<Option<Connection>>,
    pending: Mutex<Pending>,
    next_request: AtomicU64,
    next_connection: AtomicU64,
    accept_task: Mutex<Option<AbortHandle>>,
}

impl Bridge {
    /// Binds `127.0.0.1:0` and starts accepting. Must run inside a tokio runtime.
    pub async fn listen(token: String, handler: Arc<dyn BridgeHandler>) -> Result<Self> {
        Self::listen_with(
            token,
            handler,
            Limits {
                before_hello: MAX_FRAME_BEFORE_HELLO,
                after_hello: MAX_FRAME,
            },
        )
        .await
    }

    async fn listen_with(
        token: String,
        handler: Arc<dyn BridgeHandler>,
        limits: Limits,
    ) -> Result<Self> {
        let listener = TcpListener::bind(("127.0.0.1", 0))
            .await
            .map_err(|e| Error::msg(format!("Couldn't open the dev bridge port: {e}")))?;
        let port = listener.local_addr()?.port();
        let inner = Arc::new(Inner {
            limits,
            token,
            port,
            handler,
            connection: Mutex::new(None),
            pending: Mutex::new(HashMap::new()),
            next_request: AtomicU64::new(1),
            next_connection: AtomicU64::new(1),
            accept_task: Mutex::new(None),
        });
        let weak = Arc::downgrade(&inner);
        let task = tokio::spawn(async move {
            while let Ok((stream, _)) = listener.accept().await {
                let Some(inner) = weak.upgrade() else { break };
                tokio::spawn(handle_connection(inner, stream));
            }
        });
        *inner.accept_task.lock().unwrap() = Some(task.abort_handle());
        Ok(Self { inner })
    }

    pub fn port(&self) -> u16 {
        self.inner.port
    }

    pub fn is_connected(&self) -> bool {
        self.inner.connection.lock().unwrap().is_some()
    }

    /// Writes one frame from the UI (a JSON-RPC message or batch) to the plugin.
    pub fn send(&self, message: &str) -> Result<()> {
        let line = protocol::ui_frame(message)?;
        if self.inner.write(line) {
            Ok(())
        } else {
            Err(not_connected())
        }
    }

    /// The backend's own request [method] (no params): resolves with its
    /// result, or fails with its error, within [timeout].
    pub async fn request(&self, method: &str, timeout: Duration) -> Result<Value> {
        self.request_with(method, None, timeout).await
    }

    /// [request] with [params].
    pub async fn request_with(
        &self,
        method: &str,
        params: Option<Value>,
        timeout: Duration,
    ) -> Result<Value> {
        let n = self.inner.next_request.fetch_add(1, Ordering::Relaxed);
        let mut frame = protocol::backend_request(n, method);
        if let Some(params) = params {
            frame["params"] = params;
        }
        let line = serde_json::to_string(&frame)?;
        let (tx, rx) = oneshot::channel();
        self.inner.pending.lock().unwrap().insert(n, tx);
        if !self.inner.write(line) {
            self.inner.pending.lock().unwrap().remove(&n);
            return Err(not_connected());
        }
        match tokio::time::timeout(timeout, rx).await {
            Ok(Ok(result)) => result,
            Ok(Err(_)) => Err(Error::new(ErrorCode::NotConnected, "The dev bridge closed")),
            Err(_) => {
                self.inner.pending.lock().unwrap().remove(&n);
                Err(Error::new(
                    ErrorCode::Timeout,
                    format!("The plugin didn't answer within {} s", timeout.as_secs()),
                ))
            }
        }
    }

    /// Stops listening, drops the connection and fails pending requests.
    pub fn shutdown(&self) {
        if let Some(task) = self.inner.accept_task.lock().unwrap().take() {
            task.abort();
        }
        let connection = self.inner.connection.lock().unwrap().take();
        if let Some(connection) = connection {
            for task in connection.tasks {
                task.abort();
            }
        }
        self.inner.fail_pending("The dev server stopped");
    }
}

fn not_connected() -> Error {
    Error::new(
        ErrorCode::NotConnected,
        "The NetherForge plugin isn't connected to the editor yet",
    )
}

impl Inner {
    /// Queues [line] on the current connection; false when there's none.
    fn write(&self, line: String) -> bool {
        match self.connection.lock().unwrap().as_ref() {
            Some(connection) => connection.sender.send(line).is_ok(),
            None => false,
        }
    }

    fn fail_pending(&self, reason: &str) {
        let pending: Vec<_> = self.pending.lock().unwrap().drain().collect();
        for (_, tx) in pending {
            let _ = tx.send(Err(Error::new(ErrorCode::NotConnected, reason)));
        }
    }

    /// Hands [line] to the backend's own request it answers; false when it answers none.
    fn resolve(&self, line: &str) -> bool {
        let Ok(frame) = serde_json::from_str::<Value>(line) else {
            return false;
        };
        let Some((n, result)) = protocol::backend_response(&frame) else {
            return false;
        };
        if let Some(tx) = self.pending.lock().unwrap().remove(&n) {
            let _ = tx.send(result);
        }
        true
    }
}

/// Constant-time-ish so the token can't be probed byte by byte.
fn token_matches(expected: &str, given: &str) -> bool {
    let (a, b) = (expected.as_bytes(), given.as_bytes());
    a.len() == b.len() && a.iter().zip(b).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

async fn handle_connection(inner: Arc<Inner>, stream: TcpStream) {
    let _ = stream.set_nodelay(true);
    let (read, mut write) = stream.into_split();
    let mut frames = FrameReader::new(BufReader::new(read), inner.limits.before_hello);

    // Authenticate: the first frame must be a hello with our token. Anything
    // else is closed without a word, so the port can't be probed.
    let first = match tokio::time::timeout(HELLO_TIMEOUT, frames.next()).await {
        Ok(Ok(Frame::Line(line))) => protocol::read_hello(&line),
        Ok(Ok(Frame::TooLarge)) => {
            // Said to the console, not to the peer: nothing answers a stranger on this port.
            inner.handler.refused(&frames::too_large(
                inner.limits.before_hello,
                "before the hello",
            ));
            None
        }
        _ => None,
    };
    let Some((hello_id, hello)) = first.filter(|(_, h)| token_matches(&inner.token, &h.token))
    else {
        let _ = write.shutdown().await;
        return;
    };
    if hello.protocol != protocol::PROTOCOL {
        let reason = protocol::mismatch(hello.protocol);
        let answer = protocol::hello_refused(&hello_id, &reason);
        let _ = write.write_all(format!("{answer}\n").as_bytes()).await;
        let _ = write.shutdown().await;
        inner.handler.refused(&reason);
        return;
    }
    let accepted = protocol::hello_accepted(&hello_id);
    if write
        .write_all(format!("{accepted}\n").as_bytes())
        .await
        .is_err()
    {
        return;
    }

    let connection_id = inner.next_connection.fetch_add(1, Ordering::Relaxed);
    let (sender, mut outgoing) = mpsc::unbounded_channel::<String>();
    let writer = tokio::spawn(async move {
        while let Some(mut line) = outgoing.recv().await {
            line.push('\n');
            if write.write_all(line.as_bytes()).await.is_err() || write.flush().await.is_err() {
                break;
            }
        }
    });

    // The reader waits until the connection is registered and announced, so
    // `connected` always precedes this connection's messages and its end.
    let (ready_tx, ready_rx) = oneshot::channel::<()>();
    let reader_inner = inner.clone();
    let reader = tokio::spawn(async move {
        let inner = reader_inner;
        if ready_rx.await.is_err() {
            return;
        }
        frames.set_limit(inner.limits.after_hello);
        loop {
            match frames.next().await {
                Ok(Frame::Line(line)) => {
                    if line.trim().is_empty() || inner.resolve(&line) {
                        continue;
                    }
                    inner.handler.message(line);
                }
                Ok(Frame::TooLarge) => {
                    inner.handler.refused(&frames::too_large(
                        inner.limits.after_hello,
                        "from the plugin",
                    ));
                    break;
                }
                Ok(Frame::End) | Err(_) => break,
            }
        }
        // Closed. Only the current connection's end matters.
        let was_current = {
            let mut guard = inner.connection.lock().unwrap();
            if guard.as_ref().is_some_and(|c| c.id == connection_id) {
                if let Some(c) = guard.take() {
                    for task in c.tasks.iter().skip(1) {
                        task.abort();
                    }
                }
                true
            } else {
                false
            }
        };
        if was_current {
            inner.fail_pending("The dev bridge disconnected");
            inner.handler.disconnected();
        }
    });

    let previous = inner.connection.lock().unwrap().replace(Connection {
        id: connection_id,
        sender,
        // The reader first: when it ends it aborts the rest (the writer).
        tasks: vec![reader.abort_handle(), writer.abort_handle()],
    });
    if let Some(previous) = previous {
        for task in previous.tasks {
            task.abort();
        }
    }
    inner.handler.connected(&hello);
    let _ = ready_tx.send(());
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};

    #[derive(Default)]
    struct Recorder {
        events: Mutex<Vec<String>>,
        messages: Mutex<Vec<String>>,
    }

    impl BridgeHandler for Recorder {
        fn connected(&self, hello: &Hello) {
            self.events
                .lock()
                .unwrap()
                .push(format!("connected {}", hello.minecraft));
        }
        fn disconnected(&self) {
            self.events.lock().unwrap().push("disconnected".into());
        }
        fn refused(&self, reason: &str) {
            self.events
                .lock()
                .unwrap()
                .push(format!("refused {reason}"));
        }
        fn message(&self, frame: String) {
            self.messages.lock().unwrap().push(frame);
        }
    }

    async fn wait_until(mut f: impl FnMut() -> bool) {
        for _ in 0..200 {
            if f() {
                return;
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
        panic!("timed out waiting");
    }

    fn hello_line(token: &str, protocol: u64) -> String {
        format!(
            "{{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"hello\",\"params\":{{\"token\":\"{token}\",\"protocol\":{protocol},\"pluginVersion\":\"0.1.0\",\"minecraft\":\"26.3\",\"project\":\"/p\"}}}}\n"
        )
    }

    type Plugin = (
        tokio::io::Lines<BufReader<tokio::net::tcp::OwnedReadHalf>>,
        tokio::net::tcp::OwnedWriteHalf,
    );

    /// Connects as the plugin and says hello; the editor's answer is left to read.
    async fn plugin_with(bridge: &Bridge, token: &str, protocol: u64) -> Plugin {
        let stream = TcpStream::connect(("127.0.0.1", bridge.port()))
            .await
            .unwrap();
        let (read, mut write) = stream.into_split();
        write
            .write_all(hello_line(token, protocol).as_bytes())
            .await
            .unwrap();
        (BufReader::new(read).lines(), write)
    }

    /// Connects as the plugin, says hello and reads it accepted.
    async fn plugin(bridge: &Bridge, token: &str) -> Plugin {
        let (mut lines, write) = plugin_with(bridge, token, protocol::PROTOCOL).await;
        let answer: Value =
            serde_json::from_str(&lines.next_line().await.unwrap().unwrap()).unwrap();
        assert_eq!(answer, protocol::hello_accepted(&json!(0)));
        (lines, write)
    }

    async fn frame(
        lines: &mut tokio::io::Lines<BufReader<tokio::net::tcp::OwnedReadHalf>>,
    ) -> Value {
        serde_json::from_str(&lines.next_line().await.unwrap().unwrap()).unwrap()
    }

    #[tokio::test]
    async fn rejects_a_wrong_token_or_a_non_hello_first_frame_without_a_word() {
        let recorder = Arc::new(Recorder::default());
        let bridge = Bridge::listen("secret".into(), recorder.clone())
            .await
            .unwrap();

        let (mut lines, _write) = plugin_with(&bridge, "wrong", protocol::PROTOCOL).await;
        assert_eq!(lines.next_line().await.unwrap(), None, "closed unanswered");

        let stream = TcpStream::connect(("127.0.0.1", bridge.port()))
            .await
            .unwrap();
        let (read, mut write) = stream.into_split();
        write
            .write_all(b"{\"jsonrpc\":\"2.0\",\"method\":\"status\",\"params\":{\"players\":[],\"instances\":0}}\n")
            .await
            .unwrap();
        assert_eq!(
            BufReader::new(read).lines().next_line().await.unwrap(),
            None
        );

        assert!(!bridge.is_connected());
        assert!(recorder.events.lock().unwrap().is_empty());
        assert!(recorder.messages.lock().unwrap().is_empty());
        let refused = bridge.send(r#"{"jsonrpc":"2.0","id":1,"method":"instances"}"#);
        assert_eq!(refused.unwrap_err().code(), ErrorCode::NotConnected);
    }

    async fn capped(recorder: &Arc<Recorder>) -> Bridge {
        Bridge::listen_with(
            "secret".into(),
            recorder.clone(),
            Limits {
                before_hello: 512,
                after_hello: 2048,
            },
        )
        .await
        .unwrap()
    }

    #[tokio::test]
    async fn closes_on_a_frame_over_the_cap_before_the_hello() {
        let recorder = Arc::new(Recorder::default());
        let bridge = capped(&recorder).await;
        // No newline at all: the editor mustn't wait for one before giving up.
        let stream = TcpStream::connect(("127.0.0.1", bridge.port()))
            .await
            .unwrap();
        let (read, mut write) = stream.into_split();
        write.write_all(&[b'a'; 513]).await.unwrap();
        assert_eq!(
            BufReader::new(read).lines().next_line().await.unwrap(),
            None,
            "closed unanswered"
        );
        wait_until(|| !recorder.events.lock().unwrap().is_empty()).await;
        let events = recorder.events.lock().unwrap().clone();
        assert_eq!(events.len(), 1);
        assert!(
            events[0].starts_with("refused Closed the dev bridge connection")
                && events[0].contains("before the hello")
                && events[0].contains("512 bytes"),
            "{events:?}"
        );
        assert!(!bridge.is_connected());

        // A hello is under the cap, so a real plugin's still gets in.
        let (_lines, _write) = plugin(&bridge, "secret").await;
    }

    #[tokio::test]
    async fn the_cap_grows_after_the_hello_and_a_frame_past_it_closes_the_connection() {
        let recorder = Arc::new(Recorder::default());
        let bridge = capped(&recorder).await;
        let (mut lines, mut write) = plugin(&bridge, "secret").await;
        wait_until(|| bridge.is_connected()).await;

        // Over the pre-hello cap, under the new one: relayed.
        let big = format!(
            "{{\"jsonrpc\":\"2.0\",\"method\":\"x\",\"params\":\"{}\"}}",
            "a".repeat(1000)
        );
        write
            .write_all(format!("{big}\n").as_bytes())
            .await
            .unwrap();
        wait_until(|| recorder.messages.lock().unwrap().len() == 1).await;
        assert_eq!(recorder.messages.lock().unwrap()[0], big);

        // Past it: closed, with the reason in the console and the end reported.
        write.write_all(&[b'a'; 2049]).await.unwrap();
        assert_eq!(lines.next_line().await.unwrap(), None, "closed");
        wait_until(|| recorder.events.lock().unwrap().len() == 3).await;
        let events = recorder.events.lock().unwrap().clone();
        assert!(
            events[1].starts_with("refused Closed the dev bridge connection")
                && events[1].contains("from the plugin")
                && events[1].contains("2048 bytes"),
            "{events:?}"
        );
        assert_eq!(events[2], "disconnected");
        assert!(!bridge.is_connected());
        assert_eq!(recorder.messages.lock().unwrap().len(), 1);
    }

    #[tokio::test]
    async fn refuses_another_protocol_with_a_sentence() {
        let recorder = Arc::new(Recorder::default());
        let bridge = Bridge::listen("secret".into(), recorder.clone())
            .await
            .unwrap();
        let (mut lines, _write) = plugin_with(&bridge, "secret", protocol::PROTOCOL + 1).await;
        let answer = frame(&mut lines).await;
        assert_eq!(answer["error"]["code"], protocol::PROTOCOL_MISMATCH);
        let reason = protocol::mismatch(protocol::PROTOCOL + 1);
        assert_eq!(answer["error"]["message"], reason.as_str());
        assert_eq!(lines.next_line().await.unwrap(), None);
        wait_until(|| !recorder.events.lock().unwrap().is_empty()).await;
        assert_eq!(
            recorder.events.lock().unwrap().as_slice(),
            [format!("refused {reason}")]
        );
        assert!(!bridge.is_connected());
    }

    #[tokio::test]
    async fn relays_frames_both_ways_and_keeps_its_own_answers() {
        let recorder = Arc::new(Recorder::default());
        let bridge = Bridge::listen("secret".into(), recorder.clone())
            .await
            .unwrap();
        let (mut lines, mut write) = plugin(&bridge, "secret").await;
        wait_until(|| bridge.is_connected()).await;
        assert_eq!(
            recorder.events.lock().unwrap().as_slice(),
            ["connected 26.3"]
        );

        // The UI's request goes out as one line, untouched.
        bridge
            .send("{\"jsonrpc\":\"2.0\",\n\"id\":4,\"method\":\"reload\",\"params\":{\"paths\":[\"a.lua\"]}}")
            .unwrap();
        assert_eq!(
            frame(&mut lines).await,
            json!({"jsonrpc": "2.0", "id": 4, "method": "reload", "params": {"paths": ["a.lua"]}})
        );

        // The backend's own request, answered while the plugin's other frames pass to the UI.
        let export = tokio::spawn({
            let bridge = bridge.clone();
            async move {
                bridge
                    .request(protocol::EXPORT_GAME_DATA, EXPORT_TIMEOUT)
                    .await
            }
        });
        let asked = frame(&mut lines).await;
        assert_eq!(asked["method"], protocol::EXPORT_GAME_DATA);
        let frames = format!(
            "{{\"jsonrpc\":\"2.0\",\"method\":\"console\",\"params\":{{\"items\":[]}}}}\n\
             {{\"jsonrpc\":\"2.0\",\"id\":4,\"result\":{{\"resources\":[]}}}}\n\
             {{\"jsonrpc\":\"2.0\",\"id\":{},\"result\":{{\"schema\":1}}}}\n",
            asked["id"]
        );
        write.write_all(frames.as_bytes()).await.unwrap();
        assert_eq!(export.await.unwrap().unwrap(), json!({"schema": 1}));
        wait_until(|| recorder.messages.lock().unwrap().len() == 2).await;
        let messages = recorder.messages.lock().unwrap().clone();
        assert!(messages[0].contains("\"console\""));
        assert_eq!(
            messages[1],
            r#"{"jsonrpc":"2.0","id":4,"result":{"resources":[]}}"#
        );
    }

    #[tokio::test]
    async fn times_out_and_fails_its_own_requests_on_disconnect() {
        let recorder = Arc::new(Recorder::default());
        let bridge = Bridge::listen("secret".into(), recorder.clone())
            .await
            .unwrap();
        let (mut lines, write) = plugin(&bridge, "secret").await;
        wait_until(|| bridge.is_connected()).await;

        let timed_out = bridge.request("instances", Duration::from_millis(50)).await;
        assert_eq!(timed_out.unwrap_err().code(), ErrorCode::Timeout);
        lines.next_line().await.unwrap();

        let pending = tokio::spawn({
            let bridge = bridge.clone();
            async move {
                bridge
                    .request(protocol::EXPORT_GAME_DATA, EXPORT_TIMEOUT)
                    .await
            }
        });
        lines.next_line().await.unwrap();
        drop(write);
        drop(lines);
        assert!(
            pending
                .await
                .unwrap()
                .unwrap_err()
                .message()
                .contains("disconnected")
        );
        wait_until(|| !bridge.is_connected()).await;
        assert_eq!(
            recorder.events.lock().unwrap().as_slice(),
            ["connected 26.3", "disconnected"]
        );
    }

    #[tokio::test]
    async fn a_reconnect_replaces_the_old_connection() {
        let recorder = Arc::new(Recorder::default());
        let bridge = Bridge::listen("secret".into(), recorder.clone())
            .await
            .unwrap();
        let (_old_lines, _old_write) = plugin(&bridge, "secret").await;
        wait_until(|| recorder.events.lock().unwrap().len() == 1).await;
        let (mut lines, _write) = plugin(&bridge, "secret").await;
        wait_until(|| recorder.events.lock().unwrap().len() == 2).await;

        bridge
            .send(r#"{"jsonrpc":"2.0","id":1,"method":"instances"}"#)
            .unwrap();
        assert_eq!(frame(&mut lines).await["method"], "instances");
        // The replaced connection's end isn't reported as a disconnect.
        assert!(
            !recorder
                .events
                .lock()
                .unwrap()
                .contains(&"disconnected".to_string())
        );
        bridge.shutdown();
        assert!(!bridge.is_connected());
    }

    #[test]
    fn compares_tokens() {
        assert!(token_matches("abc", "abc"));
        assert!(!token_matches("abc", "abd"));
        assert!(!token_matches("abc", "ab"));
    }
}
