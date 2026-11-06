//! The connections scripts hold open (Kotlin's `Connections`, `Connection`, `OkHttpConnections`): `nf.http.stream`'s
//! and `nf.http.websocket`'s, each its opening scope's, ended `"unloaded"` when that scope is released and when the
//! session stops.
//!
//! **The transport only queues.** A connection holds a thread of its own while it's open (not a worker's: four held
//! streams would starve every request), which talks to the main thread only through the connection's [`Sink`]:
//! `opened`, `message`, `ended` (heard once), queued in order. The connections' pass each tick hands scripts what's
//! queued, at most `messages-per-tick` per connection, then checks idle and the first answer. Nothing a transport does
//! runs Lua.
//!
//! **Backpressure, not dropping.** `Sink::message` takes a permit of the connection's room (`max-queued-messages`)
//! and waits for one; the main thread gives one back per message delivered. A reader waiting there stops reading the
//! socket, so TCP slows the far end.
//!
//! **Limits end, they don't error.** Too many open, or the request rate, makes the connection anyway and queues
//! `ended("limit")` for the next tick. **Bytes are counted** per package against `bytes-per-minute`: a WebSocket's on
//! the wire (its socket is the runtime's own, under TLS), a stream's as its body is read (after ureq has decoded a
//! gzipped one: Kotlin counted those on the wire too); either way a far end can't make the server buffer more, and a
//! WebSocket's message and frame are capped at `max-message-bytes` besides.
//!
//! Streams are ureq on an agent of their own per hop (no pool: a socket is one connection's), followed through their
//! redirects as requests are; closing one shuts its agent down, which ends a read in progress. WebSockets are
//! tungstenite over a socket the guard chose the address of (rustls for `wss`), read with a short timeout so the same
//! thread writes what scripts send; they don't follow redirects.

use std::collections::{BTreeMap, VecDeque};
use std::io::{self, Read, Write};
use std::net::{SocketAddr, TcpStream};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Condvar, Mutex, mpsc};
use std::time::{Duration, Instant};

use indexmap::IndexMap;
use netherforge_luau::ScopeId;
use url::Url;

use super::fetch::{Declared, Fetcher, HttpCall, REDIRECTS, host_text, port_of, redirected, send};
use super::split::{Message, Split, StreamFormat, StreamSplitter};
use super::{ByteRate, ConnectionLimits, MAX_REDIRECTS, PrivateAllowance};

/// Why a connection ended, as its `end` event's `reason` says it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum End {
    Finished,
    Closed,
    Failed,
    Limit,
    Idle,
    Unloaded,
}

impl End {
    pub fn name(self) -> &'static str {
        match self {
            End::Finished => "finished",
            End::Closed => "closed",
            End::Failed => "failed",
            End::Limit => "limit",
            End::Idle => "idle",
            End::Unloaded => "unloaded",
        }
    }

    /// The close code a WebSocket's far end is sent when the runtime ends it for this.
    pub fn close_code(self) -> u16 {
        match self {
            End::Unloaded => GOING_AWAY,
            End::Limit => POLICY,
            _ => NORMAL,
        }
    }
}

pub const NORMAL: u16 = 1000;
pub const GOING_AWAY: u16 = 1001;
pub const POLICY: u16 = 1008;
const UNSUPPORTED: u16 = 1003;
const TOO_BIG: u16 = 1009;

/// What a script asked to open, checked on the main thread.
pub struct ConnectionRequest {
    pub url: Url,
    pub method: String,
    pub headers: BTreeMap<String, String>,
    pub body: Option<Vec<u8>>,
    /// A stream's; none for a WebSocket.
    pub format: Option<StreamFormat>,
    /// A WebSocket's subprotocols to offer.
    pub protocols: Vec<String>,
    /// The owner's `http.allow-private` entries for the package that opened it, held to every hop.
    pub allowances: Vec<PrivateAllowance>,
    /// The host requirement for a redirect's host, bound to the calling package.
    pub declared: Declared,
}

/// What a transport hears from the far end, for the main thread to hand scripts in order.
pub enum Inbound {
    Opened {
        status: u16,
        headers: BTreeMap<String, String>,
        protocol: Option<String>,
    },
    Message(Message),
    Ended {
        reason: End,
        code: Option<u16>,
        detail: Option<String>,
    },
}

/// What a connection's thread and the main thread share.
pub struct Sink {
    inbound: Mutex<VecDeque<Inbound>>,
    /// Permits for messages waiting for the main thread.
    room: Mutex<usize>,
    freed: Condvar,
    /// Set when the main thread ends it: a reader stops.
    ending: AtomicBool,
    end_heard: AtomicBool,
    bytes: Arc<ByteRate>,
    namespace: String,
    bytes_per_minute: u64,
}

impl Sink {
    fn new(room: usize, bytes: Arc<ByteRate>, namespace: String, bytes_per_minute: u64) -> Sink {
        Sink {
            inbound: Mutex::default(),
            room: Mutex::new(room),
            freed: Condvar::new(),
            ending: AtomicBool::new(false),
            end_heard: AtomicBool::new(false),
            bytes,
            namespace,
            bytes_per_minute,
        }
    }

    fn queue(&self, item: Inbound) {
        self.inbound
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .push_back(item);
    }

    pub fn opened(&self, status: u16, headers: BTreeMap<String, String>, protocol: Option<String>) {
        if !self.end_heard.load(Ordering::SeqCst) {
            self.queue(Inbound::Opened {
                status,
                headers,
                protocol,
            });
        }
    }

    /// A message, once there's room for it: a reader waits here while the main thread hasn't taken the room's worth,
    /// which stops it reading the network. False when the connection ended meanwhile (stop reading).
    pub fn message(&self, message: Message) -> bool {
        let mut room = self.room.lock().unwrap_or_else(|e| e.into_inner());
        loop {
            if self.stopped() || self.end_heard.load(Ordering::SeqCst) {
                return false;
            }
            if *room > 0 {
                *room -= 1;
                drop(room);
                self.queue(Inbound::Message(message));
                return true;
            }
            room = self
                .freed
                .wait_timeout(room, Duration::from_millis(100))
                .unwrap_or_else(|e| e.into_inner())
                .0;
        }
    }

    /// The far end finished, broke or was refused: heard once, after whatever came before it.
    pub fn ended(&self, reason: End, code: Option<u16>, detail: Option<String>) {
        if !self.end_heard.swap(true, Ordering::SeqCst) {
            self.queue(Inbound::Ended {
                reason,
                code,
                detail,
            });
        }
    }

    /// Counts `bytes` read from the network against the package's limit. Past it, the connection ends `"limit"` and
    /// this is false: the reader stops before buffering any more.
    pub fn received(&self, bytes: u64) -> bool {
        if self.bytes.try_move(&self.namespace, bytes) {
            return true;
        }
        self.ended(
            End::Limit,
            None,
            Some(bytes_refusal(&self.namespace, self.bytes_per_minute)),
        );
        false
    }

    /// Whether the main thread has ended it.
    pub fn stopped(&self) -> bool {
        self.ending.load(Ordering::SeqCst)
    }

    fn take(&self) -> Option<Inbound> {
        self.inbound
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .pop_front()
    }

    fn is_empty(&self) -> bool {
        self.inbound
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .is_empty()
    }

    fn release(&self) {
        *self.room.lock().unwrap_or_else(|e| e.into_inner()) += 1;
        self.freed.notify_one();
    }

    fn clear(&self) {
        self.inbound
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clear();
        self.freed.notify_all();
    }
}

/// Why `namespace` went over the byte limit.
pub fn bytes_refusal(namespace: &str, per_minute: u64) -> String {
    format!(
        "package \"{namespace}\" moved more than {per_minute} bytes over its connections in the last minute, the \
         server's limit (http.connections.bytes-per-minute in config.yml)"
    )
}

/// The transport's half: what the main thread asks of the far end. Never blocks.
pub trait Link {
    /// Queues a WebSocket text message; false when it can't be sent any more.
    fn send(&self, text: String) -> bool;
    /// Closes a WebSocket with `code` and `reason`, or drops a stream.
    fn close(&self, code: u16, reason: Option<String>);
}

/// One connection a script opened. The main thread's; its thread reaches it only through `sink`.
pub struct Connection {
    pub number: i64,
    pub websocket: bool,
    pub url: String,
    /// The scope that opened it, which it ends with.
    pub owner: ScopeId,
    pub namespace: String,
    pub opened_at: u64,
    pub opened: bool,
    pub status: Option<u16>,
    pub protocol: Option<String>,
    /// The tick something was last sent or handed to scripts on it.
    pub last_activity: u64,
    /// `end` is being or has been heard, and nothing more is read.
    pub ended: bool,
    link: Option<Box<dyn Link>>,
    sink: Arc<Sink>,
}

impl Connection {
    /// What `state()` answers.
    pub fn state(&self) -> &'static str {
        if self.ended {
            "ended"
        } else if self.opened {
            "open"
        } else {
            "connecting"
        }
    }
}

/// Every connection open, by number.
#[derive(Default)]
pub struct Connections {
    pub active: IndexMap<i64, Connection>,
    next: i64,
    bytes: Option<Arc<ByteRate>>,
}

/// What a script's handlers hear next, taken out of the connections so nothing is borrowed while they run.
pub enum Heard {
    Opened {
        number: i64,
        status: u16,
        headers: BTreeMap<String, String>,
        protocol: Option<String>,
    },
    Message {
        number: i64,
        message: Message,
    },
    /// Ended now: `end` is to be raised, then the connection forgotten.
    Ended {
        number: i64,
        reason: End,
        code: Option<u16>,
        detail: Option<String>,
    },
}

impl Connections {
    pub fn find(&self, number: i64) -> Option<&Connection> {
        self.active.get(&number)
    }

    fn bytes(&mut self, limits: &ConnectionLimits) -> Arc<ByteRate> {
        self.bytes
            .get_or_insert_with(|| Arc::new(ByteRate::new(limits.bytes_per_minute)))
            .clone()
    }

    /// Opens a connection for `owner` (of package `namespace`) to `request`'s URL (`url` as the script wrote it).
    /// `refusal` is why the request rate refused it, if it did. Over a limit, it ends `"limit"` at the next tick
    /// instead of connecting. Returns its number.
    #[allow(clippy::too_many_arguments)]
    pub fn open(
        &mut self,
        owner: ScopeId,
        namespace: &str,
        request: ConnectionRequest,
        url: String,
        websocket: bool,
        refusal: Option<String>,
        limits: &ConnectionLimits,
        tick: u64,
        fetcher: impl FnOnce() -> Arc<Fetcher>,
    ) -> i64 {
        self.next += 1;
        let number = self.next;
        let bytes = self.bytes(limits);
        let sink = Arc::new(Sink::new(
            limits.max_queued_messages,
            bytes,
            namespace.to_owned(),
            limits.bytes_per_minute,
        ));
        let refusal = refusal.or_else(|| self.refusal(namespace, limits));
        let link: Option<Box<dyn Link>> = match refusal {
            Some(why) => {
                sink.ended(End::Limit, None, Some(why));
                None
            }
            None if websocket => Some(open_websocket(
                fetcher(),
                request,
                limits.max_message_bytes,
                sink.clone(),
            )),
            None => Some(open_stream(
                fetcher(),
                request,
                limits.max_message_bytes,
                sink.clone(),
            )),
        };
        self.active.insert(
            number,
            Connection {
                number,
                websocket,
                url,
                owner,
                namespace: namespace.to_owned(),
                opened_at: tick,
                opened: false,
                status: None,
                protocol: None,
                last_activity: tick,
                ended: false,
                link,
                sink,
            },
        );
        number
    }

    fn refusal(&self, namespace: &str, limits: &ConnectionLimits) -> Option<String> {
        let open: Vec<&Connection> = self.active.values().filter(|c| !c.ended).collect();
        if open.iter().filter(|c| c.namespace == namespace).count() >= limits.max_per_package {
            return Some(format!(
                "package \"{namespace}\" already has {} connections open, the server's limit \
                 (http.connections.max-per-package in config.yml)",
                limits.max_per_package
            ));
        }
        if open.len() >= limits.max_total {
            return Some(format!(
                "the server already has {} connections open, its limit (http.connections.max-total in config.yml)",
                limits.max_total
            ));
        }
        None
    }

    /// A script sends `text` on `number`: false when it has ended; over the byte limit it's ended now (and the
    /// `end` to raise is returned).
    pub fn send(
        &mut self,
        number: i64,
        text: String,
        limits: &ConnectionLimits,
        tick: u64,
    ) -> (bool, Option<Heard>) {
        let bytes = self.bytes(limits);
        let Some(connection) = self.active.get_mut(&number).filter(|c| !c.ended) else {
            return (false, None);
        };
        if !bytes.try_move(&connection.namespace, text.len() as u64) {
            let detail = bytes_refusal(&connection.namespace, limits.bytes_per_minute);
            return (
                false,
                self.end(number, End::Limit, None, Some(detail), None, None),
            );
        }
        connection.last_activity = tick;
        let sent = connection.link.as_ref().is_none_or(|link| link.send(text));
        (sent, None)
    }

    /// Ends `number`: the far end is let go of (a WebSocket told why), what's still queued is dropped, and the `end`
    /// its handlers are to hear is returned (none when it had ended already). The caller raises it, then forgets it.
    pub fn end(
        &mut self,
        number: i64,
        reason: End,
        code: Option<u16>,
        detail: Option<String>,
        close_code: Option<u16>,
        close_reason: Option<String>,
    ) -> Option<Heard> {
        let connection = self.active.get_mut(&number).filter(|c| !c.ended)?;
        connection.ended = true;
        connection.sink.ending.store(true, Ordering::SeqCst);
        if let Some(link) = &connection.link {
            link.close(close_code.unwrap_or(reason.close_code()), close_reason);
        }
        connection.sink.clear();
        Some(Heard::Ended {
            number,
            reason,
            code,
            detail,
        })
    }

    /// What's queued, in order, as handlers are to hear it this tick, with idle and unanswered connections ended.
    /// Each connection's are taken as their turn comes, so call it again after raising what it gave.
    pub fn next_heard(
        &mut self,
        number: i64,
        delivered: &mut usize,
        limits: &ConnectionLimits,
        timeout_seconds: u64,
        now: u64,
    ) -> Option<Heard> {
        let connection = self.active.get_mut(&number).filter(|c| !c.ended)?;
        if *delivered < limits.messages_per_tick
            && let Some(item) = connection.sink.take()
        {
            return match item {
                Inbound::Opened {
                    status,
                    headers,
                    protocol,
                } => {
                    connection.opened = true;
                    connection.status = Some(status);
                    connection.protocol = protocol.clone();
                    connection.last_activity = now;
                    Some(Heard::Opened {
                        number,
                        status,
                        headers,
                        protocol,
                    })
                }
                Inbound::Message(message) => {
                    connection.sink.release();
                    *delivered += 1;
                    connection.last_activity = now;
                    Some(Heard::Message { number, message })
                }
                Inbound::Ended {
                    reason,
                    code,
                    detail,
                } => self.end(number, reason, code, detail, None, None),
            };
        }
        let idle_ticks = limits.idle_seconds * TICKS_PER_SECOND;
        let answer_ticks = timeout_seconds * TICKS_PER_SECOND;
        if !connection.opened && now.saturating_sub(connection.opened_at) >= answer_ticks {
            let detail = format!(
                "it wasn't answered within {timeout_seconds} seconds (http.timeout-seconds)"
            );
            return self.end(number, End::Failed, None, Some(detail), None, None);
        }
        if connection.opened
            && now.saturating_sub(connection.last_activity) >= idle_ticks
            && connection.sink.is_empty()
        {
            return self.end(number, End::Idle, None, None, None, None);
        }
        None
    }

    /// How many `scope` holds open (`/nf scripts`' count).
    pub fn count_owned(&self, scope: ScopeId) -> usize {
        self.active.values().filter(|c| c.owner == scope).count()
    }
}

const TICKS_PER_SECOND: u64 = 20;

// ---- streams

struct StreamLink {
    closed: Arc<AtomicBool>,
    /// The agent of the hop in progress: shutting it down ends a read in progress.
    agent: Arc<Mutex<Option<ureq::Agent>>>,
}

impl Link for StreamLink {
    fn send(&self, _: String) -> bool {
        false
    }
    fn close(&self, _: u16, _: Option<String>) {
        self.closed.store(true, Ordering::SeqCst);
        if let Some(agent) = self.agent.lock().unwrap_or_else(|e| e.into_inner()).take() {
            agent.shutdown();
        }
    }
}

/// A link to nothing: the connection couldn't start.
struct Unconnected;

impl Link for Unconnected {
    fn send(&self, _: String) -> bool {
        false
    }
    fn close(&self, _: u16, _: Option<String>) {}
}

fn open_stream(
    fetcher: Arc<Fetcher>,
    request: ConnectionRequest,
    max_message_bytes: usize,
    sink: Arc<Sink>,
) -> Box<dyn Link> {
    let closed = Arc::new(AtomicBool::new(false));
    let agent = Arc::new(Mutex::new(None));
    let link = StreamLink {
        closed: closed.clone(),
        agent: agent.clone(),
    };
    let thread_sink = sink.clone();
    let spawned = std::thread::Builder::new()
        .name("NetherForge connection".into())
        .spawn(move || {
            let outcome = run_stream(
                &fetcher,
                &request,
                max_message_bytes,
                &thread_sink,
                &closed,
                &agent,
            );
            if let Err((reason, detail)) = outcome
                && !closed.load(Ordering::SeqCst)
                && !thread_sink.stopped()
            {
                thread_sink.ended(reason, None, Some(detail));
            }
        });
    match spawned {
        Ok(_) => Box::new(link),
        Err(e) => {
            sink.ended(End::Failed, None, Some(format!("no thread for it: {e}")));
            Box::new(Unconnected)
        }
    }
}

/// Reads a stream to its end: redirects followed first, one hop at a time. An error is how it ended.
fn run_stream(
    fetcher: &Fetcher,
    request: &ConnectionRequest,
    max_message_bytes: usize,
    sink: &Sink,
    closed: &AtomicBool,
    current: &Mutex<Option<ureq::Agent>>,
) -> Result<(), (End, String)> {
    let mut call = HttpCall {
        method: request.method.clone(),
        url: request.url.clone(),
        headers: request.headers.clone(),
        body: request.body.clone(),
    };
    let mut redirects = 0;
    loop {
        let agent = fetcher.agent(fetcher.guard(&request.allowances, port_of(&call.url)), 0);
        *current.lock().unwrap_or_else(|e| e.into_inner()) = Some(agent.clone());
        if closed.load(Ordering::SeqCst) {
            agent.shutdown();
            return Ok(());
        }
        let response =
            send(&agent, &call, None).map_err(|e| (End::Failed, fetcher.failed(e, &call.url)))?;
        let status = response.status().as_u16();
        let headers = super::fetch::headers_of(response.headers());
        if let Some(location) = headers
            .get("location")
            .filter(|_| REDIRECTS.contains(&status))
        {
            redirects += 1;
            if redirects > MAX_REDIRECTS {
                return Err((
                    End::Failed,
                    format!("it was redirected more than {MAX_REDIRECTS} times"),
                ));
            }
            let to = call.url.join(location).map_err(|_| {
                (
                    End::Failed,
                    format!("it was redirected to \"{location}\", which isn't a URL"),
                )
            })?;
            call =
                redirected(&call, to, status, &request.declared).map_err(|e| (End::Failed, e))?;
            continue;
        }
        sink.opened(status, headers, None);
        let mut splitter = StreamSplitter::new(
            request.format.unwrap_or(StreamFormat::Lines),
            max_message_bytes,
        );
        let mut emit = |message: Message| {
            if sink.message(message) {
                Ok(())
            } else {
                Err(())
            }
        };
        let mut body = response.into_body();
        let mut reader = body.with_config().limit(u64::MAX).reader();
        let mut buffer = [0u8; 8192];
        let too_large = |t: super::split::TooLarge| (End::Limit, t.words());
        loop {
            if closed.load(Ordering::SeqCst) || sink.stopped() {
                return Ok(());
            }
            let read = match reader.read(&mut buffer) {
                Ok(n) => n,
                Err(e) if e.kind() == io::ErrorKind::Interrupted => continue,
                Err(e) => {
                    return Err((
                        End::Failed,
                        format!(
                            "the stream from {} broke: {e}",
                            host_text(&call.url).unwrap_or_default()
                        ),
                    ));
                }
            };
            if read == 0 {
                break;
            }
            if !sink.received(read as u64) {
                return Ok(());
            }
            match splitter.feed(&buffer[..read], &mut emit) {
                Ok(()) => {}
                Err(Split::TooLarge(t)) => return Err(too_large(t)),
                Err(Split::Stopped(())) => return Ok(()),
            }
        }
        match splitter.finish(&mut emit) {
            Ok(()) => {}
            Err(Split::TooLarge(t)) => return Err(too_large(t)),
            Err(Split::Stopped(())) => return Ok(()),
        }
        sink.ended(End::Finished, None, None);
        return Ok(());
    }
}

// ---- WebSockets

enum Outbound {
    Text(String),
    Close(u16, Option<String>),
}

struct SocketLink {
    send: Mutex<mpsc::Sender<Outbound>>,
    open: Arc<AtomicBool>,
}

impl Link for SocketLink {
    fn send(&self, text: String) -> bool {
        self.open.load(Ordering::SeqCst)
            && self
                .send
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .send(Outbound::Text(text))
                .is_ok()
    }
    fn close(&self, code: u16, reason: Option<String>) {
        self.open.store(false, Ordering::SeqCst);
        let _ = self
            .send
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .send(Outbound::Close(code, reason));
    }
}

fn open_websocket(
    fetcher: Arc<Fetcher>,
    request: ConnectionRequest,
    max_message_bytes: usize,
    sink: Arc<Sink>,
) -> Box<dyn Link> {
    let (send, outbound) = mpsc::channel();
    let open = Arc::new(AtomicBool::new(true));
    let thread_sink = sink.clone();
    let timeout = Duration::from_secs(fetcher.limits.timeout_seconds);
    let spawned = std::thread::Builder::new()
        .name("NetherForge connection".into())
        .spawn(move || {
            if let Err((reason, detail)) = run_websocket(
                &fetcher,
                &request,
                max_message_bytes,
                &thread_sink,
                &outbound,
                timeout,
            ) && !thread_sink.stopped()
            {
                thread_sink.ended(reason, None, Some(detail));
            }
        });
    match spawned {
        Ok(_) => Box::new(SocketLink {
            send: Mutex::new(send),
            open,
        }),
        Err(e) => {
            sink.ended(End::Failed, None, Some(format!("no thread for it: {e}")));
            Box::new(Unconnected)
        }
    }
}

/// A socket's input counted against its package's byte limit before anything reads it.
struct Counted<S> {
    inner: S,
    sink: Arc<Sink>,
}

impl<S: Read> Read for Counted<S> {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        let n = self.inner.read(buf)?;
        if n > 0 && !self.sink.received(n as u64) {
            return Err(io::Error::other("over the byte limit"));
        }
        Ok(n)
    }
}

impl<S: Write> Write for Counted<S> {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        self.inner.write(buf)
    }
    fn flush(&mut self) -> io::Result<()> {
        self.inner.flush()
    }
}

/// The socket a WebSocket talks over: plain or TLS.
enum Wire {
    Plain(Counted<TcpStream>),
    Tls(Box<rustls::StreamOwned<rustls::ClientConnection, Counted<TcpStream>>>),
}

impl Read for Wire {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        match self {
            Wire::Plain(s) => s.read(buf),
            Wire::Tls(s) => s.read(buf),
        }
    }
}

impl Write for Wire {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        match self {
            Wire::Plain(s) => s.write(buf),
            Wire::Tls(s) => s.write(buf),
        }
    }
    fn flush(&mut self) -> io::Result<()> {
        match self {
            Wire::Plain(s) => s.flush(),
            Wire::Tls(s) => s.flush(),
        }
    }
}

/// The TLS client's settings: the platform's trust, ring's ciphers.
fn tls_config() -> Result<Arc<rustls::ClientConfig>, String> {
    use rustls_platform_verifier::BuilderVerifierExt as _;
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    rustls::ClientConfig::builder_with_provider(provider)
        .with_safe_default_protocol_versions()
        .map_err(|e| e.to_string())?
        .with_platform_verifier()
        .map_err(|e| e.to_string())
        .map(|b| Arc::new(b.with_no_client_auth()))
}

fn connect(
    addresses: &[SocketAddr],
    timeout: Duration,
    host: &str,
    port: u16,
) -> Result<TcpStream, String> {
    let mut last = None;
    for address in addresses {
        match TcpStream::connect_timeout(address, timeout) {
            Ok(stream) => return Ok(stream),
            Err(e) => last = Some(e),
        }
    }
    Err(match last {
        Some(e) if e.kind() == io::ErrorKind::TimedOut => {
            format!("it timed out after {} seconds", timeout.as_secs())
        }
        Some(e) => format!("couldn't connect to {host}:{port}: {e}"),
        None => format!("couldn't connect to {host}:{port}: no address answered"),
    })
}

const READ_SLICE: Duration = Duration::from_millis(50);
const PING_EVERY: Duration = Duration::from_secs(30);

fn run_websocket(
    fetcher: &Fetcher,
    request: &ConnectionRequest,
    max_message_bytes: usize,
    sink: &Arc<Sink>,
    outbound: &mpsc::Receiver<Outbound>,
    timeout: Duration,
) -> Result<(), (End, String)> {
    use tungstenite::protocol::{CloseFrame, WebSocketConfig, frame::coding::CloseCode};
    use tungstenite::{Error as WsError, Message as WsMessage};
    let failed = |detail: String| (End::Failed, detail);
    let url = &request.url;
    let host = host_text(url).unwrap_or_default();
    let port = port_of(url);
    let addresses = fetcher
        .guard(&request.allowances, port)
        .addresses(&host, port)
        .map_err(|e| failed(e.0))?;
    let tcp = connect(&addresses, timeout, &host, port).map_err(failed)?;
    let _ = tcp.set_nodelay(true);
    let control = tcp.try_clone().map_err(|e| failed(e.to_string()))?;
    let _ = control.set_read_timeout(Some(timeout));
    let counted = Counted {
        inner: tcp,
        sink: sink.clone(),
    };
    let wire = if url.scheme() == "wss" {
        let name = rustls::pki_types::ServerName::try_from(host.clone())
            .map_err(|e| failed(format!("{host} can't be checked for TLS: {e}")))?;
        let connection = rustls::ClientConnection::new(tls_config().map_err(failed)?, name)
            .map_err(|e| failed(e.to_string()))?;
        Wire::Tls(Box::new(rustls::StreamOwned::new(connection, counted)))
    } else {
        Wire::Plain(counted)
    };
    let mut opening = tungstenite::http::Request::builder()
        .method("GET")
        .uri(url.as_str())
        .header("Host", url.host_str().unwrap_or_default())
        .header("Connection", "Upgrade")
        .header("Upgrade", "websocket")
        .header("Sec-WebSocket-Version", "13")
        .header(
            "Sec-WebSocket-Key",
            tungstenite::handshake::client::generate_key(),
        );
    if !request
        .headers
        .keys()
        .any(|n| n.eq_ignore_ascii_case("user-agent"))
    {
        opening = opening.header("User-Agent", "NetherForge");
    }
    for (name, value) in &request.headers {
        opening = opening.header(name.as_str(), value.as_str());
    }
    if !request.protocols.is_empty() {
        opening = opening.header("Sec-WebSocket-Protocol", request.protocols.join(", "));
    }
    let opening = opening.body(()).map_err(|e| failed(e.to_string()))?;
    let config = WebSocketConfig::default()
        .max_message_size(Some(max_message_bytes))
        .max_frame_size(Some(max_message_bytes));
    let (mut socket, response) =
        match tungstenite::client::client_with_config(opening, wire, Some(config)) {
            Ok(opened) => opened,
            Err(tungstenite::HandshakeError::Failure(WsError::Http(response))) => {
                let code = response.status().as_u16();
                let redirect = if REDIRECTS.contains(&code) {
                    " (WebSockets don't follow redirects)"
                } else {
                    ""
                };
                return Err(failed(format!(
                    "{host} answered {code} instead of opening a WebSocket{redirect}"
                )));
            }
            Err(tungstenite::HandshakeError::Failure(e)) => {
                return Err(failed(describe(&e, &host)));
            }
            Err(tungstenite::HandshakeError::Interrupted(_)) => {
                return Err(failed(format!(
                    "it timed out after {} seconds",
                    timeout.as_secs()
                )));
            }
        };
    let headers: BTreeMap<String, String> = {
        let mut all: BTreeMap<String, String> = BTreeMap::new();
        for (name, value) in response.headers() {
            let value = String::from_utf8_lossy(value.as_bytes()).into_owned();
            all.entry(name.as_str().to_lowercase())
                .and_modify(|v| {
                    v.push_str(", ");
                    v.push_str(&value);
                })
                .or_insert(value);
        }
        all
    };
    let protocol = headers.get("sec-websocket-protocol").cloned();
    sink.opened(response.status().as_u16(), headers, protocol);
    let _ = control.set_read_timeout(Some(READ_SLICE));
    let mut pinged = Instant::now();
    let mut closing: Option<Instant> = None;
    loop {
        // What scripts sent, and a close the main thread asked for.
        loop {
            match outbound.try_recv() {
                Ok(Outbound::Text(text)) => {
                    if socket.send(WsMessage::text(text)).is_err() {
                        break;
                    }
                }
                Ok(Outbound::Close(code, reason)) => {
                    let frame = CloseFrame {
                        code: CloseCode::from(code),
                        reason: reason.unwrap_or_default().into(),
                    };
                    let _ = socket.close(Some(frame));
                    closing.get_or_insert_with(Instant::now);
                }
                Err(mpsc::TryRecvError::Empty) => break,
                // The main thread forgot the connection: let it go.
                Err(mpsc::TryRecvError::Disconnected) => {
                    if closing.is_none() {
                        let _ = socket.close(None);
                        closing = Some(Instant::now());
                    }
                    break;
                }
            }
        }
        if closing.is_some_and(|at| at.elapsed() > Duration::from_secs(2)) {
            return Ok(());
        }
        if closing.is_none() && pinged.elapsed() >= PING_EVERY {
            let _ = socket.send(WsMessage::Ping(Vec::new().into()));
            pinged = Instant::now();
        }
        match socket.read() {
            Ok(WsMessage::Text(text)) => {
                if closing.is_none()
                    && !sink.message(Message {
                        data: text.as_str().to_owned(),
                        r#type: None,
                        id: None,
                    })
                {
                    // The main thread ended it while this waited: its close is queued.
                    continue;
                }
            }
            Ok(WsMessage::Binary(_)) => {
                let _ = socket.close(Some(CloseFrame {
                    code: CloseCode::from(UNSUPPORTED),
                    reason: "binary messages aren't taken".into(),
                }));
                let _ = socket.flush();
                return Err(failed(
                    "the other side sent a binary message, which nf.http.websocket doesn't take"
                        .into(),
                ));
            }
            Ok(WsMessage::Close(frame)) => {
                if closing.is_none() {
                    let (code, reason) = frame.map_or((None, None), |f| {
                        let reason = f.reason.as_str().to_owned();
                        (
                            Some(u16::from(f.code)),
                            (!reason.is_empty()).then_some(reason),
                        )
                    });
                    sink.ended(End::Finished, code, reason);
                }
                let _ = socket.flush();
            }
            Ok(_) => {}
            Err(WsError::Io(e))
                if matches!(
                    e.kind(),
                    io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut
                ) => {}
            Err(WsError::ConnectionClosed | WsError::AlreadyClosed) => return Ok(()),
            Err(WsError::Capacity(_)) => {
                let _ = socket.close(Some(CloseFrame {
                    code: CloseCode::from(TOO_BIG),
                    reason: "message too big".into(),
                }));
                let _ = socket.flush();
                return Err((
                    End::Limit,
                    format!(
                        "a message is larger than http.connections.max-message-bytes ({max_message_bytes} bytes)"
                    ),
                ));
            }
            Err(e) => {
                if closing.is_some() || sink.stopped() {
                    return Ok(());
                }
                return Err(failed(describe(&e, &host)));
            }
        }
    }
}

fn describe(e: &tungstenite::Error, host: &str) -> String {
    match e {
        tungstenite::Error::Io(io) if io.to_string() == "over the byte limit" => {
            "over the byte limit".into()
        }
        tungstenite::Error::Io(io) => format!("the connection to {host} broke: {io}"),
        other => format!("the connection to {host} failed: {other}"),
    }
}
