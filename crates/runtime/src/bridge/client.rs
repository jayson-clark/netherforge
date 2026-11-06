//! The server's end of the dev bridge (Kotlin's `BridgeClient`): a socket to the editor on `127.0.0.1:<port>`,
//! JSON-RPC 2.0 frames one per line both ways.
//!
//! Two threads, so the server's main thread never waits on the editor: the connection thread connects, sends the
//! hello and waits for its answer, proves the token, then reads frames and hands them to [`Events::frame`]; a writer
//! thread drains the outgoing queue. Anything sent while disconnected waits in the queue (bounded; the oldest go
//! first) and is delivered after the next accepted hello. A dropped connection is retried with backoff, from 250 ms
//! up to 5 s, until [`Client::stop`].
//!
//! Stream items are coalesced: an item joins the batch at the end of the queue when there is one, so a burst of log
//! lines goes out as one notification, in order with everything else. The writer lets a batch that ends the queue
//! fill for up to `LINGER` before sending it, so how a burst is batched doesn't depend on which thread runs first;
//! anything else queued after the batch ends the wait at once.
//!
//! An editor that refuses the hello (another protocol) is final: [`Events::refused`] hears its sentence and the
//! client stops. Retrying gives up after `abandon_after` without a connection (from the last one ending, or from
//! the start), and then [`Events::abandoned`] runs once: a dev server's editor listens on a fresh port each run, so
//! one gone that long isn't coming back to this server.

use std::collections::VecDeque;
use std::io::Write;
use std::net::{Shutdown, SocketAddr, TcpStream};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant};

use netherforge_format::bridge::rpc::{self, Frame, Message};
use netherforge_format::bridge::{
    self, AuthenticateParams, HelloParams, HelloResult, Method, auth,
};
use serde_json::Value;

use super::frames::{FrameError, FrameReader};

/// The most the editor's answer to the hello can be: a refusal is a sentence.
pub const MAX_FRAME_BEFORE_HELLO: usize = 16 * 1024;
/// The most one frame from the editor can be once the hello is accepted. Its requests are small (a reload's paths, a
/// command); the editor's own cap on what it reads from the server is larger, since the game data export is megabytes.
pub const MAX_FRAME: usize = 16 * 1024 * 1024;
/// Frames (or stream items) waiting at most; past it the oldest go.
pub const QUEUE_LIMIT: usize = 10_000;
/// Items in one stream notification at most.
pub const MAX_BATCH: usize = 500;
/// How long a batch ending the queue may wait for more items before it's sent: unnoticeable in a console.
pub const LINGER: Duration = Duration::from_millis(10);
const HELLO_ID: i64 = 0;
const AUTHENTICATE_ID: i64 = 1;
const MIN_BACKOFF: Duration = Duration::from_millis(250);
const MAX_BACKOFF: Duration = Duration::from_secs(5);
const CONNECT_TIMEOUT: Duration = Duration::from_secs(2);
const HELLO_TIMEOUT: Duration = Duration::from_secs(10);
const POLL: Duration = Duration::from_millis(200);
const STOP_WAIT: Duration = Duration::from_secs(2);

/// What the client tells whoever runs it, on its own threads.
pub trait Events: Send + Sync {
    /// The hello for a connection, carrying its nonce.
    fn hello(&self, nonce: &str) -> HelloParams;
    /// A frame from the editor after the hello, on the connection thread.
    fn frame(&self, frame: Frame);
    /// The hello was accepted.
    fn connected(&self) {}
    /// A connection whose hello was accepted ended.
    fn disconnected(&self) {}
    /// Something to say in the server's log: a connection that failed, an impostor, a frame too large.
    fn problem(&self, message: String);
    /// The editor refused the hello, once; the client has stopped.
    fn refused(&self, message: String) {
        self.problem(message);
    }
    /// Retrying gave up, once.
    fn abandoned(&self) {}
}

/// How the client is set up.
#[derive(Clone)]
pub struct Settings {
    pub port: u16,
    /// The token the editor started the server with: proved, never sent.
    pub token: String,
    /// How long it keeps trying without a connection before it gives up.
    pub abandon_after: Duration,
    /// The time, in milliseconds from any fixed point: tests move their own.
    pub clock: Arc<dyn Fn() -> u64 + Send + Sync>,
}

impl Settings {
    pub fn new(port: u16, token: impl Into<String>) -> Settings {
        let start = Instant::now();
        Settings {
            port,
            token: token.into(),
            abandon_after: Duration::MAX,
            clock: Arc::new(move || start.elapsed().as_millis() as u64),
        }
    }
}

enum Outgoing {
    Line(String),
    Batch {
        stream: &'static str,
        items: Vec<Value>,
    },
}

impl Outgoing {
    fn size(&self) -> usize {
        match self {
            Outgoing::Line(_) => 1,
            Outgoing::Batch { items, .. } => items.len(),
        }
    }
    fn line(&self) -> String {
        match self {
            Outgoing::Line(line) => line.clone(),
            Outgoing::Batch { stream, items } => {
                Message::notification(stream, Some(serde_json::json!({ bridge::ITEMS: items })))
                    .line()
            }
        }
    }
}

#[derive(Default)]
struct Queue {
    items: VecDeque<Outgoing>,
    /// Stream items count one each.
    pending: usize,
}

impl Queue {
    fn trim(&mut self) {
        while self.pending > QUEUE_LIMIT && self.items.len() > 1 {
            let gone = self.items.pop_front().expect("more than one");
            self.pending -= gone.size();
        }
    }
}

struct Inner {
    settings: Settings,
    events: Arc<dyn Events>,
    running: AtomicBool,
    accepted: AtomicBool,
    socket: Mutex<Option<TcpStream>>,
    queue: Mutex<Queue>,
    waiting: Condvar,
    thread: Mutex<Option<std::thread::JoinHandle<()>>>,
}

/// The connection to the editor. Cheap to clone; safe from any thread.
#[derive(Clone)]
pub struct Client(Arc<Inner>);

impl Client {
    pub fn new(settings: Settings, events: Arc<dyn Events>) -> Client {
        Client(Arc::new(Inner {
            settings,
            events,
            running: AtomicBool::new(false),
            accepted: AtomicBool::new(false),
            socket: Mutex::new(None),
            queue: Mutex::default(),
            waiting: Condvar::new(),
            thread: Mutex::new(None),
        }))
    }

    /// Starts connecting, on a thread of its own.
    pub fn start(&self) {
        if self.0.running.swap(true, Ordering::SeqCst) {
            return;
        }
        let inner = self.0.clone();
        let thread = std::thread::Builder::new()
            .name("NetherForge bridge".into())
            .spawn(move || connect_loop(&inner))
            .expect("a thread");
        *self.0.thread.lock().unwrap() = Some(thread);
    }

    /// Stops: the connection closes and nothing more is tried.
    pub fn stop(&self) {
        self.0.running.store(false, Ordering::SeqCst);
        if let Some(socket) = &*self.0.socket.lock().unwrap() {
            let _ = socket.shutdown(Shutdown::Both);
        }
        let thread = self.0.thread.lock().unwrap().take();
        if let Some(thread) = thread {
            thread.thread().unpark();
            let deadline = Instant::now() + STOP_WAIT;
            while !thread.is_finished() && Instant::now() < deadline {
                std::thread::sleep(Duration::from_millis(5));
            }
            if thread.is_finished() {
                let _ = thread.join();
            }
        }
    }

    /// Whether a connection's hello was accepted and it's still open.
    pub fn connected(&self) -> bool {
        self.0.accepted.load(Ordering::SeqCst) && self.0.socket.lock().unwrap().is_some()
    }

    /// Queues a message (a response, a notification). Never blocks.
    pub fn send(&self, message: &Message) {
        self.enqueue(Outgoing::Line(message.line()));
    }

    /// Queues a batch of responses as one frame.
    pub fn send_batch(&self, messages: &[Message]) {
        self.enqueue(Outgoing::Line(rpc::batch_line(messages)));
    }

    /// Queues notification `event` with its params.
    pub fn notify<E: bridge::Event>(&self, params: &E::Params) {
        let params = serde_json::to_value(params).expect("params serialize");
        self.send(&Message::notification(E::NAME, Some(params)));
    }

    /// Adds `item` to stream `S`'s batch at the end of the queue, or starts one.
    pub fn stream<S: bridge::Stream>(&self, item: &S::Item) {
        self.stream_value(
            S::NAME,
            serde_json::to_value(item).expect("items serialize"),
        );
    }

    /// `stream`, for an item already JSON.
    pub fn stream_value(&self, stream: &'static str, item: Value) {
        let mut queue = self.0.queue.lock().unwrap();
        if let Some(Outgoing::Batch { stream: s, items }) = queue.items.back_mut()
            && *s == stream
            && items.len() < MAX_BATCH
        {
            // Joining the batch doesn't wake the writer: it's lingering for exactly this.
            items.push(item);
            queue.pending += 1;
            queue.trim();
            return;
        }
        queue.items.push_back(Outgoing::Batch {
            stream,
            items: vec![item],
        });
        queue.pending += 1;
        queue.trim();
        self.0.waiting.notify_all();
    }

    fn enqueue(&self, outgoing: Outgoing) {
        let mut queue = self.0.queue.lock().unwrap();
        queue.pending += outgoing.size();
        queue.items.push_back(outgoing);
        queue.trim();
        self.0.waiting.notify_all();
    }
}

/// Everything waiting: waits up to `POLL` for something, then up to `LINGER` while a batch that isn't full ends the
/// queue.
fn take_all(inner: &Inner) -> Vec<Outgoing> {
    let mut queue = inner.queue.lock().unwrap();
    if queue.items.is_empty() {
        queue = inner.waiting.wait_timeout(queue, POLL).unwrap().0;
    }
    let deadline = Instant::now() + LINGER;
    while matches!(queue.items.back(), Some(Outgoing::Batch { items, .. }) if items.len() < MAX_BATCH)
    {
        let now = Instant::now();
        if now >= deadline {
            break;
        }
        let before = queue.items.len();
        let (next, _) = inner.waiting.wait_timeout(queue, deadline - now).unwrap();
        queue = next;
        // Something other than an item joining arrived: send now.
        if queue.items.len() != before {
            break;
        }
    }
    queue.pending = 0;
    queue.items.drain(..).collect()
}

/// Puts back what couldn't be written, ahead of what's queued since.
fn put_back(inner: &Inner, outgoing: Vec<Outgoing>) {
    let mut queue = inner.queue.lock().unwrap();
    for item in outgoing.into_iter().rev() {
        queue.pending += item.size();
        queue.items.push_front(item);
    }
    queue.trim();
}

fn connect_loop(inner: &Arc<Inner>) {
    let port = inner.settings.port;
    let clock = inner.settings.clock.clone();
    let mut backoff = MIN_BACKOFF;
    let mut warned = false;
    let mut last_contact = clock();
    while inner.running.load(Ordering::SeqCst) {
        let address = SocketAddr::from(([127, 0, 0, 1], port));
        match TcpStream::connect_timeout(&address, CONNECT_TIMEOUT) {
            Ok(socket) => {
                let _ = socket.set_nodelay(true);
                *inner.socket.lock().unwrap() = socket.try_clone().ok();
                backoff = MIN_BACKOFF;
                warned = false;
                if let Err(e) = session(inner, socket)
                    && inner.running.load(Ordering::SeqCst)
                {
                    inner
                        .events
                        .problem(format!("Dev bridge on port {port}: {e}; retrying"));
                    warned = true;
                }
                last_contact = clock();
            }
            Err(e) => {
                if inner.running.load(Ordering::SeqCst) && !warned {
                    inner
                        .events
                        .problem(format!("Dev bridge on port {port}: {e}; retrying"));
                    warned = true;
                }
            }
        }
        *inner.socket.lock().unwrap() = None;
        inner.accepted.store(false, Ordering::SeqCst);
        if !inner.running.load(Ordering::SeqCst) {
            break;
        }
        if clock().saturating_sub(last_contact) >= inner.settings.abandon_after.as_millis() as u64 {
            inner.running.store(false, Ordering::SeqCst);
            inner.events.abandoned();
            break;
        }
        std::thread::park_timeout(backoff);
        backoff = (backoff * 2).min(MAX_BACKOFF);
    }
}

fn write_line(writer: &mut impl Write, line: &str) -> std::io::Result<()> {
    writer.write_all(line.as_bytes())?;
    writer.write_all(b"\n")?;
    writer.flush()
}

/// One connection: the hello, the proofs both ways, then frames until it ends. An I/O error is the caller's to say.
fn session(inner: &Arc<Inner>, socket: TcpStream) -> std::io::Result<()> {
    let port = inner.settings.port;
    let token = &inner.settings.token;
    let mut writer = std::io::BufWriter::new(socket.try_clone()?);
    let mut reader = FrameReader::new(socket.try_clone()?, MAX_FRAME_BEFORE_HELLO);
    let nonce = auth::nonce();
    let hello = inner.events.hello(&nonce);
    let request = Message::request(
        HELLO_ID,
        bridge::Hello::NAME,
        bridge::encode_params::<bridge::Hello>(&hello),
    );
    write_line(&mut writer, &request.line())?;

    // Nothing else goes out until the editor has proved it holds the token, and accepted ours.
    socket.set_read_timeout(Some(HELLO_TIMEOUT))?;
    let Some(answer) = await_answer(inner, &mut reader, HELLO_ID)? else {
        return Ok(());
    };
    let result = match answer {
        Err(error) => {
            inner.running.store(false, Ordering::SeqCst);
            inner.events.refused(error.message);
            return Ok(());
        }
        Ok(result) => serde_json::from_value::<HelloResult>(result).ok(),
    };
    let editor = result.as_ref().and_then(|r| r.nonce.clone());
    let proved = match (&editor, &result) {
        (Some(editor), Some(result)) => auth::matches(
            &auth::editor_proof(token, &nonce, editor),
            result.proof.as_deref(),
        ),
        _ => false,
    };
    let Some(editor) = editor.filter(|_| proved) else {
        // Whatever listens here took the port of the editor that started this server: it gets nothing more.
        inner.events.problem(format!(
            "Dev bridge: what answers on 127.0.0.1:{port} isn't the editor that started this server (it can't prove it holds the token); not connecting to it"
        ));
        let _ = socket.shutdown(Shutdown::Both);
        return Ok(());
    };
    let proof = AuthenticateParams {
        proof: auth::plugin_proof(token, &editor, &nonce),
    };
    let request = Message::request(
        AUTHENTICATE_ID,
        bridge::Authenticate::NAME,
        bridge::encode_params::<bridge::Authenticate>(&proof),
    );
    write_line(&mut writer, &request.line())?;
    let Some(authenticated) = await_answer(inner, &mut reader, AUTHENTICATE_ID)? else {
        return Ok(());
    };
    if let Err(error) = authenticated {
        inner.events.problem(format!(
            "Dev bridge: the editor didn't accept this server's proof: {}",
            error.message
        ));
        return Ok(());
    }
    socket.set_read_timeout(None)?;
    inner.accepted.store(true, Ordering::SeqCst);
    reader.limit = MAX_FRAME;

    let closed = Arc::new(AtomicBool::new(false));
    let writing = {
        let inner = inner.clone();
        let closed = closed.clone();
        let socket = socket.try_clone()?;
        std::thread::Builder::new()
            .name("NetherForge bridge writer".into())
            .spawn(move || drain(&inner, &socket, writer, &closed))
            .expect("a thread")
    };
    inner.events.connected();
    let outcome = loop {
        if !inner.running.load(Ordering::SeqCst) {
            break Ok(());
        }
        match reader.read_frame() {
            Ok(None) => break Ok(()),
            Ok(Some(line)) if line.trim().is_empty() => {}
            Ok(Some(line)) => inner.events.frame(rpc::decode(&line)),
            Err(FrameError::TooLarge(limit)) => {
                // Dropped, and retried like any other drop; the reason goes to the console.
                inner.events.problem(format!(
                    "Dev bridge: closing the connection, the editor sent a frame longer than {limit} bytes"
                ));
                break Ok(());
            }
            // The connection went (or `stop` shut it): retried like any drop.
            Err(FrameError::Io(_)) => break Ok(()),
        }
    };
    let _ = socket.shutdown(Shutdown::Both);
    inner.accepted.store(false, Ordering::SeqCst);
    closed.store(true, Ordering::SeqCst);
    inner.waiting.notify_all();
    let _ = writing.join();
    inner.events.disconnected();
    outcome
}

/// The editor's answer to handshake request `id`, or none when the connection closed first (a wrong proof is never
/// answered) or sent too much.
fn await_answer(
    inner: &Inner,
    reader: &mut FrameReader<TcpStream>,
    id: i64,
) -> std::io::Result<Option<Result<Value, rpc::Error>>> {
    loop {
        let line = match reader.read_frame() {
            Ok(Some(line)) => line,
            Ok(None) => return Ok(None),
            Err(FrameError::TooLarge(limit)) => {
                inner.events.problem(format!(
                    "Dev bridge: closing the connection, the editor sent a frame longer than {limit} bytes before answering the hello"
                ));
                return Ok(None);
            }
            Err(FrameError::Io(e)) => return Err(e),
        };
        if line.trim().is_empty() {
            continue;
        }
        if let Frame::Single(Message::Response { id: got, outcome }) = rpc::decode(&line) {
            let same = got.as_i64() == Some(id) || got.as_str() == Some(&id.to_string());
            if same {
                return Ok(Some(outcome));
            }
        }
        inner
            .events
            .problem("The editor sent a frame before answering the hello; ignored".into());
    }
}

fn drain(inner: &Inner, socket: &TcpStream, mut writer: impl Write, closed: &AtomicBool) {
    while !closed.load(Ordering::SeqCst) {
        let all = take_all(inner);
        if all.is_empty() {
            continue;
        }
        let mut failed = false;
        for outgoing in &all {
            if writer.write_all(outgoing.line().as_bytes()).is_err()
                || writer.write_all(b"\n").is_err()
            {
                failed = true;
                break;
            }
        }
        if failed || writer.flush().is_err() {
            // Put back for the next connection rather than lost.
            put_back(inner, all);
            let _ = socket.shutdown(Shutdown::Both);
            return;
        }
    }
}
