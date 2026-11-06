//! `nf.http` (Kotlin's `http/` and `NfHttpImpl`): requests to web services, streams and WebSockets.
//!
//! What a script got wrong in the call is an error at its line, found on the main thread before anything starts.
//! What only the network can say, or the owner's limits, is the work's failure: `nil, err`. The network work runs on
//! threads of the runtime's own (`Workers` for requests, a thread per open connection), never the main thread; a
//! request's value reaches its script at the tick boundary as every async value does (`pending::deliver`), and a
//! connection's messages are queued for the connections' pass each tick.
//!
//! The client is ureq (blocking, HTTP/1.1, rustls with the platform's trust): see `fetch.rs` for the guard.

pub mod api;
pub mod connections;
pub mod fetch;
pub mod guard;
pub mod split;

use std::collections::{HashMap, VecDeque};
use std::sync::{Arc, Mutex, mpsc};
use std::time::{Duration, Instant};

pub use connections::Connections;
pub use fetch::{Fetcher, HttpCall, HttpReply};

/// `http:` in `config.yml`: the server owner's limits on `nf.http`.
#[derive(Clone, Debug)]
pub struct HttpConfig {
    /// Private addresses (loopback, private, link-local, shared, unique-local) may be reached too.
    pub allow_private_addresses: bool,
    pub max_request_bytes: usize,
    pub max_response_bytes: usize,
    /// One request's whole time, its redirects included; how long a connection may take to answer.
    pub timeout_seconds: u64,
    /// Requests (and connections opened) each package may start a minute.
    pub requests_per_minute: usize,
    /// What `nf.http.stream` and `nf.http.websocket` may hold open and move.
    pub connections: ConnectionLimits,
    /// `http.allow-private`: each a package that may reach one private host and port.
    pub allow_private: Vec<PrivateAllowance>,
}

impl Default for HttpConfig {
    fn default() -> HttpConfig {
        HttpConfig {
            allow_private_addresses: false,
            max_request_bytes: 1024 * 1024,
            max_response_bytes: 4 * 1024 * 1024,
            timeout_seconds: 15,
            requests_per_minute: 60,
            connections: ConnectionLimits::default(),
            allow_private: Vec::new(),
        }
    }
}

/// Redirects one request follows before it fails.
pub const MAX_REDIRECTS: usize = 5;

/// `http.connections:` in `config.yml`.
#[derive(Clone, Debug)]
pub struct ConnectionLimits {
    pub max_per_package: usize,
    pub max_total: usize,
    /// One line, event or message (either way), at most.
    pub max_message_bytes: usize,
    /// Read from and written to the network per package a minute.
    pub bytes_per_minute: u64,
    /// Ended after this long with nothing sent or received.
    pub idle_seconds: u64,
    /// Messages waiting for scripts per connection, at most, before the server stops reading.
    pub max_queued_messages: usize,
    /// Messages handed to scripts per connection a tick.
    pub messages_per_tick: usize,
}

impl Default for ConnectionLimits {
    fn default() -> ConnectionLimits {
        ConnectionLimits {
            max_per_package: 4,
            max_total: 32,
            max_message_bytes: 1024 * 1024,
            bytes_per_minute: 16 * 1024 * 1024,
            idle_seconds: 300,
            max_queued_messages: 1000,
            messages_per_tick: 100,
        }
    }
}

/// One entry of `http.allow-private`: package `namespace` may connect to `host` (an address, or a name looked up when
/// it's needed) at `port` though the address is private. It lifts only the address refusal: the package still
/// declares the host in `requires.http`.
#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub struct PrivateAllowance {
    pub namespace: String,
    pub host: String,
    pub port: u16,
    /// The package's requests to `host` and `port` a minute, counted apart from `http.requests-per-minute` (none:
    /// they count against that; 0: no limit).
    pub requests_per_minute: Option<usize>,
}

const MINUTE: Duration = Duration::from_secs(60);

/// How many requests each key (a package's namespace) may start a minute: a sliding window. Main thread only.
#[derive(Debug)]
pub struct RequestRate {
    per_minute: usize,
    started: HashMap<String, VecDeque<Instant>>,
}

impl RequestRate {
    pub fn new(per_minute: usize) -> RequestRate {
        RequestRate {
            per_minute,
            started: HashMap::new(),
        }
    }

    /// Counts a request for `key` and says whether it's within the limit; one past it isn't counted.
    pub fn try_start(&mut self, key: &str) -> bool {
        let now = Instant::now();
        let times = self.started.entry(key.to_owned()).or_default();
        while times.front().is_some_and(|t| now - *t >= MINUTE) {
            times.pop_front();
        }
        if times.len() >= self.per_minute {
            return false;
        }
        times.push_back(now);
        true
    }
}

/// What one key moved in the last minute: each move with when it was, and their total.
type Window = (VecDeque<(Instant, u64)>, u64);

/// How many bytes each key may move a minute: a sliding window, counted from any thread (readers count what
/// arrives, the main thread what scripts send).
#[derive(Debug)]
pub struct ByteRate {
    per_minute: u64,
    moved: Mutex<HashMap<String, Window>>,
}

impl ByteRate {
    pub fn new(per_minute: u64) -> ByteRate {
        ByteRate {
            per_minute,
            moved: Mutex::default(),
        }
    }

    /// Counts `bytes` for `key` and says whether that's within the limit; bytes past it aren't counted.
    pub fn try_move(&self, key: &str, bytes: u64) -> bool {
        let now = Instant::now();
        let mut moved = self.moved.lock().unwrap_or_else(|e| e.into_inner());
        let (window, total) = moved.entry(key.to_owned()).or_default();
        while let Some(&(at, n)) = window.front() {
            if now - at < MINUTE {
                break;
            }
            *total -= n;
            window.pop_front();
        }
        if *total + bytes > self.per_minute {
            return false;
        }
        window.push_back((now, bytes));
        *total += bytes;
        true
    }
}

type Job = Box<dyn FnOnce() + Send>;

/// The runtime's worker threads for requests (Kotlin's `Workers`): a few threads taking jobs in order, made on the
/// first job, ended once the last clone goes and the jobs are done. A request blocks a worker while it waits on the
/// network, so connections that stay open get threads of their own instead.
#[derive(Clone)]
pub struct Workers {
    send: Arc<Mutex<mpsc::Sender<Job>>>,
}

/// Threads requests run on.
const WORKERS: usize = 4;

impl Workers {
    pub fn new(name: &str) -> Workers {
        Workers::with_threads(name, WORKERS)
    }

    /// A lane: one thread, so its jobs run one at a time in the order they're given (Kotlin's `workers.lane`).
    pub fn lane(name: &str) -> Workers {
        Workers::with_threads(name, 1)
    }

    fn with_threads(name: &str, threads: usize) -> Workers {
        let (send, jobs) = mpsc::channel::<Job>();
        let jobs = Arc::new(Mutex::new(jobs));
        for n in 0..threads {
            let jobs = jobs.clone();
            let _ = std::thread::Builder::new()
                .name(format!("{name} {}", n + 1))
                .spawn(move || {
                    loop {
                        let job = {
                            let jobs = jobs.lock().unwrap_or_else(|e| e.into_inner());
                            jobs.recv()
                        };
                        let Ok(job) = job else { break };
                        // A job that panics mustn't take the worker with it.
                        let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(job));
                    }
                });
        }
        Workers {
            send: Arc::new(Mutex::new(send)),
        }
    }

    pub fn submit(&self, job: impl FnOnce() + Send + 'static) {
        let send = self.send.lock().unwrap_or_else(|e| e.into_inner());
        let _ = send.send(Box::new(job));
    }
}

/// A value another thread sends once, as a future the room polls at its tick boundary.
pub struct Reply<T>(pub mpsc::Receiver<Result<T, String>>);

impl<T> std::future::Future for Reply<T> {
    type Output = Result<T, String>;

    fn poll(
        self: std::pin::Pin<&mut Self>,
        _: &mut std::task::Context<'_>,
    ) -> std::task::Poll<Self::Output> {
        match self.0.try_recv() {
            Ok(value) => std::task::Poll::Ready(value),
            Err(mpsc::TryRecvError::Empty) => std::task::Poll::Pending,
            Err(mpsc::TryRecvError::Disconnected) => {
                std::task::Poll::Ready(Err("the request was stopped".into()))
            }
        }
    }
}
