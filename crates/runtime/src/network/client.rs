//! The server's end of a TCP connection to the hub (`HubClient`): a thread that dials, answers the challenge and
//! reads; a writer that drains what `send` queued and pings when there's nothing to say. A dropped or refused
//! connection is dialled again after a pause that doubles from half a second to ten, until `stop`. What's queued when
//! a connection drops is thrown away: a message for the network as it was then isn't worth sending later.

use std::collections::VecDeque;
use std::io::{BufRead, BufReader, Write};
use std::net::{Shutdown, TcpStream, ToSocketAddrs};
use std::sync::atomic::{AtomicBool, AtomicU8, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use super::protocol::{self, HubFrame, Lifecycle, Registration, ServerFrame};
use super::seal::{self, Channel};
use super::{Inbound, Inbox, Welcome};

const MIN_BACKOFF: Duration = Duration::from_millis(500);
const MAX_BACKOFF: Duration = Duration::from_secs(10);
const CONNECT_TIMEOUT: Duration = Duration::from_secs(5);
const LEAVE_WAIT: Duration = Duration::from_secs(1);

/// Who this server is to the hub, as its hello says.
#[derive(Clone, Debug)]
pub struct Hello {
    pub host: String,
    pub port: u16,
    pub server: String,
    pub secret: String,
    pub credential: Option<String>,
    pub group: Option<String>,
    pub capacity: Option<i32>,
    pub register: Option<Registration>,
    /// This process's id: dialling again, it keeps its name.
    pub instance: String,
    /// The most that may wait to be written.
    pub max_queued: usize,
}

/// A `Lifecycle` the link's own thread reads for the hello.
#[derive(Default)]
pub struct SharedLifecycle(AtomicU8);

impl SharedLifecycle {
    pub fn get(&self) -> Lifecycle {
        match self.0.load(Ordering::Relaxed) {
            0 => Lifecycle::Starting,
            1 => Lifecycle::Ready,
            2 => Lifecycle::Draining,
            _ => Lifecycle::Stopping,
        }
    }

    pub fn set(&self, state: Lifecycle) {
        let n = match state {
            Lifecycle::Starting => 0,
            Lifecycle::Ready => 1,
            Lifecycle::Draining => 2,
            Lifecycle::Stopping => 3,
        };
        self.0.store(n, Ordering::Relaxed);
    }
}

struct Outbox {
    lines: VecDeque<String>,
}

struct Inner {
    hello: Hello,
    inbox: Arc<Inbox>,
    state: Arc<SharedLifecycle>,
    running: AtomicBool,
    accepted: AtomicBool,
    out: Mutex<Outbox>,
    out_ready: Condvar,
    /// The open connection, so `stop` can cut a read short.
    socket: Mutex<Option<TcpStream>>,
    /// What the backoff sleeps on, so `stop` wakes it.
    pause: Mutex<()>,
    paused: Condvar,
}

/// The plugin's end of its link to a hub over TCP.
pub struct HubClient {
    inner: Arc<Inner>,
    thread: Option<JoinHandle<()>>,
}

impl HubClient {
    pub fn new(hello: Hello, inbox: Arc<Inbox>, state: Arc<SharedLifecycle>) -> HubClient {
        HubClient {
            inner: Arc::new(Inner {
                hello,
                inbox,
                state,
                running: AtomicBool::new(false),
                accepted: AtomicBool::new(false),
                out: Mutex::new(Outbox {
                    lines: VecDeque::new(),
                }),
                out_ready: Condvar::new(),
                socket: Mutex::new(None),
                pause: Mutex::new(()),
                paused: Condvar::new(),
            }),
            thread: None,
        }
    }

    pub fn start(&mut self) {
        if self.inner.running.swap(true, Ordering::SeqCst) {
            return;
        }
        let inner = self.inner.clone();
        self.thread = std::thread::Builder::new()
            .name("NetherForge network".into())
            .spawn(move || dial(&inner))
            .ok();
    }

    /// Whether the hub has accepted this server and the connection is up.
    pub fn connected(&self) -> bool {
        self.inner.accepted.load(Ordering::SeqCst)
    }

    /// Queues `frame` for the hub: false, and nothing queued, while not connected or past the queue's limit.
    pub fn send(&self, frame: &ServerFrame) -> bool {
        if !self.connected() {
            return false;
        }
        let mut out = self.inner.out.lock().unwrap_or_else(|e| e.into_inner());
        if out.lines.len() >= self.inner.hello.max_queued {
            return false;
        }
        out.lines.push_back(protocol::encode(frame));
        drop(out);
        self.inner.out_ready.notify_all();
        true
    }

    /// Says this server is shutting down, waiting a moment for it to be written, before `stop`.
    pub fn leave(&self) {
        if !self.send(&ServerFrame::Leaving) {
            return;
        }
        let deadline = Instant::now() + LEAVE_WAIT;
        while Instant::now() < deadline && self.connected() {
            if self
                .inner
                .out
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .lines
                .is_empty()
            {
                break;
            }
            std::thread::sleep(Duration::from_millis(10));
        }
        // The writer took it; a moment for it to be written.
        std::thread::sleep(Duration::from_millis(50));
    }

    pub fn stop(&mut self) {
        self.inner.running.store(false, Ordering::SeqCst);
        if let Some(socket) = self
            .inner
            .socket
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .as_ref()
        {
            let _ = socket.shutdown(Shutdown::Both);
        }
        self.inner.out_ready.notify_all();
        self.inner.paused.notify_all();
        // A reader waiting for room in the inbox lets go: what it holds isn't drained now.
        self.inbox_closed();
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
    }

    fn inbox_closed(&self) {
        self.inner.inbox.close();
    }
}

impl Drop for HubClient {
    fn drop(&mut self) {
        self.stop();
    }
}

fn running(inner: &Inner) -> bool {
    inner.running.load(Ordering::SeqCst)
}

fn dial(inner: &Arc<Inner>) {
    let hello = &inner.hello;
    let mut backoff = MIN_BACKOFF;
    while running(inner) {
        match connect(&hello.host, hello.port) {
            Ok(socket) => {
                let _ = socket.set_nodelay(true);
                if let Ok(clone) = socket.try_clone() {
                    *inner.socket.lock().unwrap_or_else(|e| e.into_inner()) = Some(clone);
                }
                if session(inner, socket) {
                    backoff = MIN_BACKOFF;
                }
            }
            Err(e) => {
                if running(inner) {
                    inner.inbox.push(Inbound::Failed(format!(
                        "the network's hub at {}:{} couldn't be reached ({e})",
                        hello.host, hello.port
                    )));
                }
            }
        }
        *inner.socket.lock().unwrap_or_else(|e| e.into_inner()) = None;
        inner.accepted.store(false, Ordering::SeqCst);
        inner
            .out
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .lines
            .clear();
        if !running(inner) {
            break;
        }
        let guard = inner.pause.lock().unwrap_or_else(|e| e.into_inner());
        let _ = inner
            .paused
            .wait_timeout_while(guard, backoff, |_| running(inner));
        backoff = (backoff * 2).min(MAX_BACKOFF);
    }
}

fn connect(host: &str, port: u16) -> std::io::Result<TcpStream> {
    let mut last = std::io::Error::other("no address");
    for address in (host, port).to_socket_addrs()? {
        match TcpStream::connect_timeout(&address, CONNECT_TIMEOUT) {
            Ok(socket) => return Ok(socket),
            Err(e) => last = e,
        }
    }
    Err(last)
}

/// Why a read ended.
enum Read {
    Line(String),
    End,
    TooLong(usize),
    TimedOut,
    Failed(String),
}

/// The next line without its ending, never holding more than `limit` bytes of one.
fn read_line(reader: &mut BufReader<TcpStream>, limit: usize) -> Read {
    let mut line = Vec::new();
    loop {
        let buffer = match reader.fill_buf() {
            Ok(buffer) => buffer,
            Err(e)
                if matches!(
                    e.kind(),
                    std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut
                ) =>
            {
                return Read::TimedOut;
            }
            Err(e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(e) => return Read::Failed(e.to_string()),
        };
        if buffer.is_empty() {
            return if line.is_empty() {
                Read::End
            } else {
                Read::Line(String::from_utf8_lossy(&line).into_owned())
            };
        }
        match buffer.iter().position(|b| *b == b'\n') {
            Some(at) => {
                if line.len() + at > limit {
                    return Read::TooLong(limit);
                }
                line.extend_from_slice(&buffer[..at]);
                reader.consume(at + 1);
                if line.last() == Some(&b'\r') {
                    line.pop();
                }
                return Read::Line(String::from_utf8_lossy(&line).into_owned());
            }
            None => {
                let n = buffer.len();
                if line.len() + n > limit {
                    return Read::TooLong(limit);
                }
                line.extend_from_slice(buffer);
                reader.consume(n);
            }
        }
    }
}

/// One connection, from the challenge to its end. True when the hub accepted it.
fn session(inner: &Arc<Inner>, socket: TcpStream) -> bool {
    let hello = &inner.hello;
    let at = format!("{}:{}", hello.host, hello.port);
    let failed = |why: String| inner.inbox.push(Inbound::Failed(why));
    let _ = socket.set_read_timeout(Some(Duration::from_millis(protocol::HELLO_MILLIS)));
    let Ok(writing) = socket.try_clone() else {
        return false;
    };
    let mut reader = BufReader::new(socket);
    let challenge = match read_line(&mut reader, protocol::MAX_FRAME_BEFORE_WELCOME) {
        Read::Line(line) => protocol::decode_hub(&line),
        _ => return false,
    };
    let Some(HubFrame::Challenge { version, nonce }) = challenge else {
        failed(format!("{at} didn't speak like a NetherForge hub"));
        return false;
    };
    if version != protocol::VERSION {
        failed(format!(
            "the hub speaks the network's protocol version {version}, and this server {}: update NetherForge on the {}",
            protocol::VERSION,
            if version < protocol::VERSION {
                "proxy"
            } else {
                "server"
            }
        ));
        return false;
    }
    let own = seal::new_nonce();
    let credential = hello.credential.as_deref();
    let frame = ServerFrame::Hello {
        version: protocol::VERSION,
        server: hello.server.clone(),
        proof: seal::proof(&hello.secret, &nonce, &hello.server, &own, credential),
        nonce: own.clone(),
        credential: hello.credential.clone(),
        group: hello.group.clone(),
        capacity: hello.capacity,
        register: hello.register.clone(),
        instance: Some(hello.instance.clone()),
        state: inner.state.get(),
        role: None,
    };
    let mut out = std::io::BufWriter::new(writing);
    if writeln!(out, "{}", protocol::encode(&frame))
        .and_then(|()| out.flush())
        .is_err()
    {
        return false;
    }
    // Everything from the welcome on is sealed: a hub that can open and seal with these keys holds the secret.
    let channel = Arc::new(Mutex::new(Channel::server(
        &hello.secret,
        &nonce,
        &own,
        &hello.server,
        credential,
    )));
    let answer = match read_line(&mut reader, protocol::MAX_SEALED_LINE) {
        Read::Line(line) => line,
        _ => {
            failed("the network's hub closed the connection before answering".into());
            return false;
        }
    };
    let opened = channel
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .open(&answer);
    let frame = match &opened {
        Some(text) => protocol::decode_hub(text),
        None => protocol::decode_hub(&answer),
    };
    let welcome = match frame {
        Some(frame @ HubFrame::Welcome { .. }) if opened.is_some() => {
            Welcome::of(frame).expect("a welcome")
        }
        Some(HubFrame::Welcome { .. }) => {
            failed(format!(
                "{at} answered without the network's secret: it isn't this network's hub (check network.hub)"
            ));
            return false;
        }
        Some(HubFrame::Refused { reason }) => {
            failed(format!("the network's hub refused this server: {reason}"));
            return false;
        }
        _ => {
            failed(format!(
                "{at} answered with something this server can't open: it isn't this network's hub, or its secret differs"
            ));
            return false;
        }
    };
    let _ = reader
        .get_ref()
        .set_read_timeout(Some(Duration::from_millis(protocol::IDLE_MILLIS)));
    inner
        .out
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .lines
        .clear();
    inner.accepted.store(true, Ordering::SeqCst);
    let closed = Arc::new(AtomicBool::new(false));
    let writer = {
        let inner = inner.clone();
        let channel = channel.clone();
        let closed = closed.clone();
        std::thread::Builder::new()
            .name("NetherForge network writer".into())
            .spawn(move || drain(&inner, out, &channel, &closed))
            .ok()
    };
    inner.inbox.push(Inbound::Connected(Box::new(welcome)));
    let mut why = "the hub closed the connection".to_owned();
    while running(inner) {
        match read_line(&mut reader, protocol::MAX_SEALED_LINE) {
            Read::Line(read) => {
                if read.trim().is_empty() {
                    continue;
                }
                let Some(line) = channel
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .open(&read)
                else {
                    why = "the hub sent a line not sealed with this connection's keys".into();
                    break;
                };
                if let Some(frame) = protocol::decode_hub(&line) {
                    inner.inbox.push(Inbound::Frame(frame));
                }
            }
            Read::End => break,
            Read::TimedOut => {
                why = format!(
                    "the hub was silent for {} seconds",
                    protocol::IDLE_MILLIS / 1000
                );
                break;
            }
            Read::TooLong(limit) => {
                why = format!("the hub sent a line longer than {limit} bytes");
                break;
            }
            Read::Failed(e) => {
                why = e;
                break;
            }
        }
    }
    if !running(inner) {
        why = "the server is stopping".into();
    }
    inner.accepted.store(false, Ordering::SeqCst);
    closed.store(true, Ordering::SeqCst);
    let _ = reader.get_ref().shutdown(Shutdown::Both);
    inner.out_ready.notify_all();
    if let Some(writer) = writer {
        let _ = writer.join();
    }
    inner.inbox.push(Inbound::Disconnected(why));
    true
}

/// Writes what's queued, each line sealed in the order written, and a ping after a quiet spell.
fn drain(
    inner: &Inner,
    mut out: std::io::BufWriter<TcpStream>,
    channel: &Mutex<Channel>,
    closed: &AtomicBool,
) {
    let ping = Duration::from_millis(protocol::PING_MILLIS);
    loop {
        let lines: Vec<String> = {
            let guard = inner.out.lock().unwrap_or_else(|e| e.into_inner());
            let (mut guard, timeout) = inner
                .out_ready
                .wait_timeout_while(guard, ping, |o| {
                    o.lines.is_empty() && !closed.load(Ordering::SeqCst) && running(inner)
                })
                .unwrap_or_else(|e| e.into_inner());
            if closed.load(Ordering::SeqCst) || !running(inner) {
                return;
            }
            if timeout.timed_out() && guard.lines.is_empty() {
                vec![protocol::encode(&ServerFrame::Ping)]
            } else {
                guard.lines.drain(..).collect()
            }
        };
        let mut channel = channel.lock().unwrap_or_else(|e| e.into_inner());
        for line in &lines {
            if writeln!(out, "{}", channel.seal(line)).is_err() {
                let _ = out.get_ref().shutdown(Shutdown::Both);
                return;
            }
        }
        drop(channel);
        if out.flush().is_err() {
            let _ = out.get_ref().shutdown(Shutdown::Both);
            return;
        }
    }
}
