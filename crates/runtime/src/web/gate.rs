//! The web routes' door (Kotlin's `WebGate`): what the listener's threads do with a request before any script sees
//! it, and the queue it waits in for the main thread.
//!
//! Everything that can be refused without the main thread is refused here, so a flood costs the tick nothing: a path
//! too long (`414`), an address over its requests a minute (`429`), a body over the limit (`413`, read no further
//! than the limit), more waiting than `max-pending` (`503`). A request's own thread waits for its answer and writes
//! it, never the main thread, so a slow client holds up nobody but itself.

use std::collections::{BTreeMap, HashMap, VecDeque};
use std::io::{self, BufRead, BufReader, Read, Write};
use std::net::TcpStream;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, mpsc};
use std::time::{Duration, Instant};

/// `web:` in `config.yml`: whether `nf.web` routes are served (off by default), on the resource pack's listener, and
/// the owner's limits on them.
#[derive(Clone, Debug)]
pub struct WebConfig {
    pub enabled: bool,
    /// What `nf.web.url` builds on: the listener's own address when unset.
    pub public_url: Option<String>,
    /// `X-Forwarded-For` is the client's address: for a server behind the owner's own reverse proxy.
    pub trust_proxy_headers: bool,
    pub max_body_bytes: usize,
    pub max_response_bytes: usize,
    pub requests_per_minute_per_ip: usize,
    pub max_pending: usize,
    pub handler_timeout_seconds: u64,
}

impl Default for WebConfig {
    fn default() -> WebConfig {
        WebConfig {
            enabled: false,
            public_url: None,
            trust_proxy_headers: false,
            max_body_bytes: 64 * 1024,
            max_response_bytes: 1024 * 1024,
            requests_per_minute_per_ip: 60,
            max_pending: 64,
            handler_timeout_seconds: 10,
        }
    }
}

/// What a request is answered with.
#[derive(Clone, Debug)]
pub struct WebAnswer {
    pub status: u16,
    pub headers: Vec<(String, String)>,
    pub body: Vec<u8>,
}

impl WebAnswer {
    /// A plain-text answer, for the gate's own refusals and the router's.
    pub fn text(status: u16, text: &str, headers: &[(&str, &str)]) -> WebAnswer {
        let mut all = vec![(
            "content-type".to_owned(),
            "text/plain; charset=utf-8".to_owned(),
        )];
        all.extend(
            headers
                .iter()
                .map(|(n, v)| ((*n).to_owned(), (*v).to_owned())),
        );
        WebAnswer {
            status,
            headers: all,
            body: text.as_bytes().to_vec(),
        }
    }
}

/// One request, read and admitted, waiting for (or being handled on) the main thread. Answered once, from any thread.
pub struct WebCall {
    pub method: String,
    /// The raw path, percent-encoded as it came.
    pub path: String,
    pub raw_query: Option<String>,
    /// By lowercase name, repeated ones joined with `, `.
    pub headers: BTreeMap<String, String>,
    pub body: Vec<u8>,
    pub ip: String,
    answered: AtomicBool,
    reply: Mutex<Option<mpsc::Sender<WebAnswer>>>,
}

impl WebCall {
    /// A call whose answer goes to the returned receiver.
    pub fn new(
        method: String,
        path: String,
        raw_query: Option<String>,
        headers: BTreeMap<String, String>,
        body: Vec<u8>,
        ip: String,
    ) -> (Arc<WebCall>, mpsc::Receiver<WebAnswer>) {
        let (send, receive) = mpsc::channel();
        (
            Arc::new(WebCall {
                method,
                path,
                raw_query,
                headers,
                body,
                ip,
                answered: AtomicBool::new(false),
                reply: Mutex::new(Some(send)),
            }),
            receive,
        )
    }

    pub fn is_answered(&self) -> bool {
        self.answered.load(Ordering::SeqCst)
    }

    /// Answers it, unless it has been answered already: false then.
    pub fn answer(&self, answer: WebAnswer) -> bool {
        if self.answered.swap(true, Ordering::SeqCst) {
            return false;
        }
        if let Some(reply) = self.reply.lock().unwrap_or_else(|e| e.into_inner()).take() {
            let _ = reply.send(answer);
        }
        true
    }
}

const MINUTE: Duration = Duration::from_secs(60);
const SWEEP_EVERY: u64 = 1024;
const MAX_PATH: usize = 2048;
const MAX_HEAD: usize = 16 * 1024;

/// How many requests each address may make a minute: a sliding window, from the listener's threads.
#[derive(Default)]
struct AddressRate {
    started: HashMap<String, VecDeque<Instant>>,
    sweeps: u64,
}

impl AddressRate {
    fn try_start(&mut self, key: &str, per_minute: usize) -> bool {
        let now = Instant::now();
        self.sweeps += 1;
        // Now and then, addresses that went quiet go, so the map can't grow with every address there is.
        if self.sweeps.is_multiple_of(SWEEP_EVERY) {
            self.started
                .retain(|_, times| times.back().is_some_and(|t| now - *t < MINUTE));
        }
        let times = self.started.entry(key.to_owned()).or_default();
        while times.front().is_some_and(|t| now - *t >= MINUTE) {
            times.pop_front();
        }
        if times.len() >= per_minute {
            return false;
        }
        times.push_back(now);
        true
    }
}

/// The door: the refusals, and the queue for the main thread.
pub struct WebGate {
    pub config: WebConfig,
    queue: Mutex<VecDeque<Arc<WebCall>>>,
    rate: Mutex<AddressRate>,
}

impl WebGate {
    pub fn new(config: WebConfig) -> WebGate {
        WebGate {
            config,
            queue: Mutex::default(),
            rate: Mutex::default(),
        }
    }

    /// The refusal for a request from `ip` with a body of `length` bytes (none when unknown) to `path`, or none when
    /// it may go on. Counts the request against `ip`.
    pub fn refusal(&self, ip: &str, path: &str, length: Option<u64>) -> Option<WebAnswer> {
        if path.len() > MAX_PATH {
            return Some(WebAnswer::text(414, "That address is too long.", &[]));
        }
        let allowed = self
            .rate
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .try_start(ip, self.config.requests_per_minute_per_ip);
        if !allowed {
            return Some(WebAnswer::text(
                429,
                "Too many requests: try again in a minute.",
                &[("retry-after", "60")],
            ));
        }
        if length.is_some_and(|n| n > self.config.max_body_bytes as u64) {
            return Some(self.too_large());
        }
        None
    }

    fn too_large(&self) -> WebAnswer {
        WebAnswer::text(
            413,
            &format!(
                "The request is larger than this server takes ({} bytes).",
                self.config.max_body_bytes
            ),
            &[],
        )
    }

    /// Queues `call` for the main thread, or answers it `503` when too many already wait.
    pub fn admit(&self, call: Arc<WebCall>) {
        let mut queue = self.queue.lock().unwrap_or_else(|e| e.into_inner());
        if queue.len() >= self.config.max_pending {
            drop(queue);
            call.answer(WebAnswer::text(
                503,
                "The server is busy: try again shortly.",
                &[("retry-after", "5")],
            ));
            return;
        }
        queue.push_back(call);
    }

    /// The next request waiting for the main thread.
    pub fn take(&self) -> Option<Arc<WebCall>> {
        self.queue
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .pop_front()
    }

    /// A request on the listener's thread, its head already read (`head`, with whatever of its body came with it in
    /// `rest`): refused, or read, queued and answered here once the main thread has.
    pub fn accept(
        &self,
        mut stream: TcpStream,
        method: &str,
        target: &str,
        headers: BTreeMap<String, String>,
        rest: Vec<u8>,
    ) {
        let head = method == "HEAD";
        let direct = stream
            .peer_addr()
            .map(|a| a.ip().to_string())
            .unwrap_or_else(|_| "unknown".into());
        let ip = if self.config.trust_proxy_headers {
            forwarded(headers.get("x-forwarded-for").map(String::as_str)).unwrap_or(direct)
        } else {
            direct
        };
        let (path, query) = match target.split_once('?') {
            Some((p, q)) => (p.to_owned(), Some(q.to_owned())),
            None => (target.to_owned(), None),
        };
        let length = headers
            .get("content-length")
            .and_then(|v| v.trim().parse::<u64>().ok());
        if let Some(refusal) = self.refusal(&ip, &path, length) {
            write_answer(&mut stream, &refusal, head);
            return;
        }
        let chunked = headers
            .get("transfer-encoding")
            .is_some_and(|v| v.to_ascii_lowercase().contains("chunked"));
        let body = match read_body(
            &mut stream,
            rest,
            length,
            chunked,
            self.config.max_body_bytes,
        ) {
            Ok(Some(body)) => body,
            Ok(None) => {
                write_answer(&mut stream, &self.too_large(), head);
                return;
            }
            Err(_) => return,
        };
        let (call, answer) = WebCall::new(
            method.to_owned(),
            if path.is_empty() { "/".into() } else { path },
            query,
            headers,
            body,
            ip,
        );
        self.admit(call);
        let waited = Duration::from_secs(self.config.handler_timeout_seconds + 30);
        let answer = answer
            .recv_timeout(waited)
            .unwrap_or_else(|_| WebAnswer::text(503, "That isn't available right now.", &[]));
        write_answer(&mut stream, &answer, head);
    }
}

/// The client's address from `X-Forwarded-For`: the last entry, the one the owner's own reverse proxy appended for
/// the connection it took. Every entry before it is whatever the client sent.
pub fn forwarded(header: Option<&str>) -> Option<String> {
    header?
        .split(',')
        .map(str::trim)
        .rfind(|e| !e.is_empty())
        .map(str::to_owned)
}

/// The body, or none when it's longer than `limit` (reading stops there, whatever it said its length was).
fn read_body(
    stream: &mut TcpStream,
    rest: Vec<u8>,
    length: Option<u64>,
    chunked: bool,
    limit: usize,
) -> io::Result<Option<Vec<u8>>> {
    let mut reader = BufReader::new(io::Cursor::new(rest).chain(stream.try_clone()?));
    if chunked {
        let mut body = Vec::new();
        loop {
            let mut line = String::new();
            reader.read_line(&mut line)?;
            let size = usize::from_str_radix(line.trim().split(';').next().unwrap_or("0"), 16)
                .map_err(|_| io::Error::other("bad chunk"))?;
            if size == 0 {
                // Trailers, then the blank line.
                loop {
                    let mut line = String::new();
                    if reader.read_line(&mut line)? == 0 || line.trim().is_empty() {
                        break;
                    }
                }
                return Ok(Some(body));
            }
            if body.len() + size > limit {
                return Ok(None);
            }
            let start = body.len();
            body.resize(start + size, 0);
            reader.read_exact(&mut body[start..])?;
            let mut end = [0u8; 2];
            reader.read_exact(&mut end)?;
        }
    }
    let length = length.unwrap_or(0) as usize;
    if length > limit {
        return Ok(None);
    }
    let mut body = vec![0; length];
    reader.read_exact(&mut body)?;
    Ok(Some(body))
}

/// A request's method, target, headers by lowercase name, and the bytes read past its head.
pub type Head = (String, String, BTreeMap<String, String>, Vec<u8>);

/// The head of a request off `stream`: its method, target and headers by lowercase name, and the bytes read past it.
pub fn read_head(stream: &mut TcpStream) -> Option<Head> {
    let mut read = Vec::new();
    let mut buffer = [0u8; 2048];
    loop {
        let mut slots = [httparse::EMPTY_HEADER; 64];
        let mut request = httparse::Request::new(&mut slots);
        match request.parse(&read) {
            Ok(httparse::Status::Complete(at)) => {
                let mut headers: BTreeMap<String, String> = BTreeMap::new();
                for header in request.headers.iter() {
                    let value = String::from_utf8_lossy(header.value).into_owned();
                    headers
                        .entry(header.name.to_lowercase())
                        .and_modify(|v| {
                            v.push_str(", ");
                            v.push_str(&value);
                        })
                        .or_insert(value);
                }
                return Some((
                    request.method?.to_uppercase(),
                    request.path?.to_owned(),
                    headers,
                    read[at..].to_vec(),
                ));
            }
            Ok(httparse::Status::Partial) if read.len() < MAX_HEAD => {}
            _ => return None,
        }
        match stream.read(&mut buffer) {
            Ok(0) | Err(_) => return None,
            Ok(n) => read.extend_from_slice(&buffer[..n]),
        }
    }
}

/// Writes `answer`, its body left out for a `HEAD`, and closes the connection.
pub fn write_answer(stream: &mut TcpStream, answer: &WebAnswer, head: bool) {
    let reason = ureq::http::StatusCode::from_u16(answer.status)
        .ok()
        .and_then(|s| s.canonical_reason())
        .unwrap_or("Status");
    let mut text = format!("HTTP/1.1 {} {reason}\r\n", answer.status);
    for (name, value) in &answer.headers {
        text.push_str(&format!("{name}: {value}\r\n"));
    }
    let bodyless =
        answer.status == 204 || answer.status == 304 || (100..200).contains(&answer.status);
    if !bodyless {
        text.push_str(&format!("content-length: {}\r\n", answer.body.len()));
    }
    text.push_str("connection: close\r\n\r\n");
    let _ = stream.write_all(text.as_bytes());
    if !head && !bodyless {
        let _ = stream.write_all(&answer.body);
    }
    let _ = stream.flush();
}
