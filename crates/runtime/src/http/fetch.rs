//! Makes `nf.http.request`'s requests, off the main thread (it blocks), with ureq (Kotlin's `HttpFetcher` on OkHttp).
//!
//! **The SSRF guard is the client's resolver** ([`Guard`]). ureq asks its resolver for every connection it makes,
//! an address in the URL included, so the guard resolves a name once (through the injectable `resolve`), judges every
//! address it leads to (a name leading anywhere it may not go is refused whole), and hands ureq exactly the
//! addresses it judged, which it then connects to: there's no second lookup to rebind. An IP written in the URL is
//! judged the same way, never looked up. The agent has no proxy (the environment's is never read), no cookies, no
//! automatic redirects, and its own pool of kept-alive connections: a pooled connection is one that was judged when
//! it was made.
//!
//! **Redirects are followed here**, one ureq call per hop, each held to the same rules: the host requirement as the
//! calling package's, the address check, `https` never downgraded to `http`, `Authorization`, `Cookie` and
//! `Proxy-Authorization` dropped when the host changes, at most [`MAX_REDIRECTS`], all inside one
//! `http.timeout-seconds`.
//!
//! **One package may reach one private host and port** (`http.allow-private`): a call carries the owner's allowances
//! for the package whose code made it, and each hop goes through an agent whose guard also lets through the addresses
//! the allowances on that hop's port stand for: one agent (and pool) per set of allowed hosts, so a connection made
//! under one package's allowance is never handed to a call without it.
//!
//! A URL is read by the WHATWG rules (the `url` crate), as a browser reads it, so `http://127.1/` is 127.0.0.1 and
//! judged as loopback. Kotlin refused such spellings unread because the JDK and OkHttp could read one differently;
//! here the address judged is the one connected to whatever the spelling. [`close`](Fetcher::close) shuts every
//! agent down: what's in flight fails and no socket is left open.

use std::collections::{BTreeMap, HashMap};
use std::io::{self, Read as _};
use std::net::{IpAddr, SocketAddr, ToSocketAddrs as _};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use ureq::unversioned::resolver::{ResolvedSocketAddrs, Resolver};
use ureq::unversioned::transport::{DefaultConnector, NextTimeout};
use url::Url;

use super::{HttpConfig, MAX_REDIRECTS, PrivateAllowance, guard};

/// Where a name leads: called once for each connection made to a host that isn't an address.
pub type Resolve = Arc<dyn Fn(&str) -> io::Result<Vec<IpAddr>> + Send + Sync>;
/// Where a connection to a judged address really goes: the same address, but in a test (which stands a local
/// server in for a public one).
pub type Dial = Arc<dyn Fn(SocketAddr) -> SocketAddr + Send + Sync>;
/// This machine's own addresses.
pub type OwnAddresses = Arc<dyn Fn() -> Vec<IpAddr> + Send + Sync>;

/// One request as `nf.http.request` checked it: the method in capitals, an `http` or `https` URL with a host.
#[derive(Clone, Debug)]
pub struct HttpCall {
    pub method: String,
    pub url: Url,
    pub headers: BTreeMap<String, String>,
    pub body: Option<Vec<u8>>,
}

/// What came back: the status, its headers by lowercase name (repeated ones joined with `, `), the decoded body, the
/// URL it came from, and the body's bytes as they came (what `nf.images.download` decodes).
#[derive(Clone, Debug)]
pub struct HttpReply {
    pub status: u16,
    pub headers: BTreeMap<String, String>,
    pub body: String,
    pub url: Url,
    pub bytes: Vec<u8>,
}

/// The host requirement for a redirect's host, bound on the main thread to the calling package: why it's refused,
/// in the words `Requirements.check` uses.
pub type Declared = Arc<dyn Fn(&str) -> Result<(), String> + Send + Sync>;

/// A URL's host in lowercase, without IPv6's brackets, when it's an `http` or `https` one with a host.
pub fn host_of(url: &Url) -> Option<String> {
    if url.scheme() != "http" && url.scheme() != "https" {
        return None;
    }
    host_text(url)
}

/// A URL's host in lowercase, without IPv6's brackets.
pub fn host_text(url: &Url) -> Option<String> {
    let host = url.host_str()?.to_lowercase();
    let host = host
        .trim_start_matches('[')
        .trim_end_matches(']')
        .to_owned();
    (!host.is_empty()).then_some(host)
}

/// A URL's port, or its scheme's default.
pub fn port_of(url: &Url) -> u16 {
    url.port_or_known_default().unwrap_or(80)
}

/// The address check: where a host leads, judged, for one set of allowed private hosts.
pub struct Guard {
    allow_private: bool,
    resolve: Resolve,
    dial: Option<Dial>,
    own: OwnAddresses,
    /// Hosts (addresses or names) whose addresses are let through though private: the calling package's allowances
    /// for the port connected to.
    allowed: Vec<String>,
}

/// Why a connection couldn't be made, in the words a script is told.
#[derive(Debug)]
pub struct Unreachable(pub String);

impl std::fmt::Display for Unreachable {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for Unreachable {}

impl Guard {
    /// What the hosts in `allowed` stand for now: an address as written, a name as it resolves.
    fn allowed_addresses(&self) -> Vec<IpAddr> {
        self.allowed
            .iter()
            .flat_map(|host| match guard::literal(host) {
                Some(address) => vec![address],
                None => (self.resolve)(host).unwrap_or_default(),
            })
            .collect()
    }

    /// Why `address` is out of bounds for a request to `host`, or none when it may be reached: public, or private
    /// and one of the allowed hosts' (never one that names no one host).
    fn refusal(&self, host: &str, address: IpAddr) -> Option<String> {
        let kind = guard::classify(address);
        let own = !self.allow_private && self.own_has(address);
        if kind.is_none() && !own {
            return None;
        }
        if kind != Some(guard::Kind::Never)
            && !self.allowed.is_empty()
            && self.allowed_addresses().contains(&address)
        {
            return None;
        }
        let why = guard::refusal(address, self.allow_private)
            .or(own.then_some("this server's own address"))?;
        let text = address.to_string();
        let leads = if host == text {
            format!("{host} is")
        } else {
            format!("{host} leads to {text},")
        };
        Some(format!(
            "{leads} {why}, which nf.http.request doesn't reach (the server owner can allow private addresses with \
             http.allow-private-addresses in config.yml, or one package one host and port with http.allow-private)"
        ))
    }

    fn own_has(&self, address: IpAddr) -> bool {
        let address = match address {
            IpAddr::V6(v6) => v6.to_ipv4_mapped().map_or(address, IpAddr::V4),
            v4 => v4,
        };
        (self.own)().contains(&address)
    }

    /// Where `host` (a URL's, brackets gone) at `port` leads, every address judged, ready to connect to.
    pub fn addresses(&self, host: &str, port: u16) -> Result<Vec<SocketAddr>, Unreachable> {
        let found = if guard::looks_literal(host) {
            vec![guard::literal(host).ok_or_else(|| {
                Unreachable(format!(
                    "{host} isn't an IP address nf.http.request can send to (write IPv4 as four plain numbers)"
                ))
            })?]
        } else {
            let found =
                (self.resolve)(host).map_err(|_| Unreachable(format!("{host} doesn't resolve")))?;
            if found.is_empty() {
                return Err(Unreachable(format!("{host} doesn't resolve")));
            }
            found
        };
        // Every address it leads to is judged, not just the one used: a name that leads anywhere private is refused
        // whole.
        for address in &found {
            if let Some(why) = self.refusal(host, *address) {
                return Err(Unreachable(why));
            }
        }
        Ok(found
            .into_iter()
            .map(|address| {
                let at = SocketAddr::new(address, port);
                self.dial.as_ref().map_or(at, |dial| dial(at))
            })
            .collect())
    }
}

impl std::fmt::Debug for Guard {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Guard")
            .field("allowed", &self.allowed)
            .finish()
    }
}

/// The guard as ureq's resolver.
#[derive(Debug)]
struct GuardResolver(Arc<Guard>);

impl Resolver for GuardResolver {
    fn resolve(
        &self,
        uri: &ureq::http::Uri,
        _: &ureq::config::Config,
        _: NextTimeout,
    ) -> Result<ResolvedSocketAddrs, ureq::Error> {
        let host = uri
            .host()
            .unwrap_or_default()
            .trim_start_matches('[')
            .trim_end_matches(']')
            .to_lowercase();
        let port = uri
            .port_u16()
            .unwrap_or(if uri.scheme_str() == Some("https") {
                443
            } else {
                80
            });
        let addresses = self
            .0
            .addresses(&host, port)
            .map_err(|e| ureq::Error::Io(io::Error::other(e)))?;
        let mut out = self.empty();
        for address in addresses.into_iter().take(16) {
            out.push(address);
        }
        Ok(out)
    }
}

/// The system's resolver, for a name only (an address never reaches it).
pub fn system_resolve() -> Resolve {
    Arc::new(|host: &str| {
        Ok((host, 0)
            .to_socket_addrs()?
            .map(|a| a.ip())
            .collect::<Vec<IpAddr>>())
    })
}

/// The system's own addresses, read from its network interfaces.
pub fn system_own() -> OwnAddresses {
    Arc::new(guard::own_addresses)
}

/// The session's requests: an agent per set of allowed hosts, each with its guard and pool.
pub struct Fetcher {
    pub limits: HttpConfig,
    resolve: Resolve,
    dial: Option<Dial>,
    own: OwnAddresses,
    agents: Mutex<HashMap<Vec<String>, ureq::Agent>>,
    closed: AtomicBool,
}

const USER_AGENT: &str = "NetherForge";
const CREDENTIALS: &[&str] = &["authorization", "cookie", "proxy-authorization"];
const BODY_HEADERS: &[&str] = &["content-type", "content-length", "content-encoding"];
const BODY_METHODS: &[&str] = &["POST", "PUT", "PATCH"];
pub const REDIRECTS: &[u16] = &[301, 302, 303, 307, 308];

impl Fetcher {
    pub fn new(limits: HttpConfig) -> Fetcher {
        Fetcher::with(limits, system_resolve(), None, system_own())
    }

    /// A fetcher whose names resolve through `resolve`, whose connections go where `dial` says (tests), and which
    /// knows `own` as this machine's addresses.
    pub fn with(
        limits: HttpConfig,
        resolve: Resolve,
        dial: Option<Dial>,
        own: OwnAddresses,
    ) -> Fetcher {
        Fetcher {
            limits,
            resolve,
            dial,
            own,
            agents: Mutex::default(),
            closed: AtomicBool::new(false),
        }
    }

    /// The guard for a connection to `port` under `allowances`.
    pub fn guard(&self, allowances: &[PrivateAllowance], port: u16) -> Arc<Guard> {
        Arc::new(Guard {
            allow_private: self.limits.allow_private_addresses,
            resolve: self.resolve.clone(),
            dial: self.dial.clone(),
            own: self.own.clone(),
            allowed: allowed_at(allowances, port),
        })
    }

    /// An agent with `guard`, keeping `idle` connections alive in its pool.
    pub fn agent(&self, guard: Arc<Guard>, idle: usize) -> ureq::Agent {
        let tls = ureq::tls::TlsConfig::builder()
            .root_certs(ureq::tls::RootCerts::PlatformVerifier)
            .build();
        let config = ureq::Agent::config_builder()
            .proxy(None)
            .max_redirects(0)
            .max_redirects_will_error(false)
            .http_status_as_error(false)
            .user_agent(ureq::config::AutoHeaderValue::None)
            .max_idle_connections(idle)
            .max_idle_connections_per_host(idle)
            .max_idle_age(Duration::from_secs(30))
            .timeout_connect(Some(Duration::from_secs(self.limits.timeout_seconds)))
            .max_response_header_size(64 * 1024)
            .tls_config(tls)
            .build();
        ureq::Agent::with_parts(config, DefaultConnector::default(), GuardResolver(guard))
    }

    /// The pooled agent for a hop to `port` under `allowances`.
    fn pooled(&self, allowances: &[PrivateAllowance], port: u16) -> ureq::Agent {
        let allowed = allowed_at(allowances, port);
        let mut agents = self.agents.lock().unwrap_or_else(|e| e.into_inner());
        agents
            .entry(allowed)
            .or_insert_with(|| self.agent(self.guard(allowances, port), 5))
            .clone()
    }

    /// Sends `call` and follows its redirects, each hop's host held to `declared` and every address to the guard
    /// under `allowances`. Every failure is said in a script's words.
    pub fn fetch(
        &self,
        call: HttpCall,
        allowances: &[PrivateAllowance],
        declared: &Declared,
    ) -> Result<HttpReply, String> {
        let deadline = Instant::now() + Duration::from_secs(self.limits.timeout_seconds);
        let mut current = call;
        let mut redirects = 0;
        loop {
            let (reply, location) = self.exchange(&current, deadline, allowances)?;
            let Some(location) = location.filter(|_| REDIRECTS.contains(&reply.status)) else {
                return Ok(reply);
            };
            redirects += 1;
            if redirects > MAX_REDIRECTS {
                return Err(format!("it was redirected more than {MAX_REDIRECTS} times"));
            }
            let next = current
                .url
                .join(&location)
                .map_err(|_| format!("it was redirected to \"{location}\", which isn't a URL"))?;
            current = redirected(&current, next, reply.status, declared)?;
        }
    }

    /// One hop: the reply, and the `Location` it carries.
    fn exchange(
        &self,
        call: &HttpCall,
        deadline: Instant,
        allowances: &[PrivateAllowance],
    ) -> Result<(HttpReply, Option<String>), String> {
        if self.closed.load(Ordering::SeqCst) {
            return Err("the server is stopping its requests".into());
        }
        let left = deadline.saturating_duration_since(Instant::now());
        if left.is_zero() {
            return Err(self.timed_out());
        }
        let agent = self.pooled(allowances, port_of(&call.url));
        let response = send(&agent, call, Some(left)).map_err(|e| self.failed(e, &call.url))?;
        let status = response.status().as_u16();
        let headers = headers_of(response.headers());
        let location = headers.get("location").cloned();
        let bytes = if call.method == "HEAD" {
            Vec::new()
        } else {
            self.read(response)?
        };
        let body = decode(&bytes, headers.get("content-type").map(String::as_str));
        Ok((
            HttpReply {
                status,
                headers,
                body,
                url: call.url.clone(),
                bytes,
            },
            location,
        ))
    }

    /// The body, read only as far as `http.max-response-bytes` allows: a longer one fails, however its length was
    /// told (or hidden, as gzip does).
    fn read(&self, response: ureq::http::Response<ureq::Body>) -> Result<Vec<u8>, String> {
        let cap = self.limits.max_response_bytes as u64;
        let told = response
            .headers()
            .get("content-length")
            .and_then(|v| v.to_str().ok())
            .and_then(|v| v.trim().parse::<u64>().ok());
        if told.is_some_and(|n| n > cap) {
            return Err(self.too_large());
        }
        let mut body = response.into_body();
        let mut reader = body.with_config().limit(u64::MAX).reader();
        let mut out = Vec::new();
        let mut buffer = [0u8; 8192];
        loop {
            match reader.read(&mut buffer) {
                Ok(0) => return Ok(out),
                Ok(n) => {
                    out.extend_from_slice(&buffer[..n]);
                    if out.len() as u64 > cap {
                        return Err(self.too_large());
                    }
                }
                Err(e) if e.kind() == io::ErrorKind::Interrupted => {}
                Err(e) => return Err(self.io_failed(&e)),
            }
        }
    }

    fn too_large(&self) -> String {
        format!(
            "the response is larger than http.max-response-bytes ({} bytes)",
            self.limits.max_response_bytes
        )
    }

    fn timed_out(&self) -> String {
        format!("it timed out after {} seconds", self.limits.timeout_seconds)
    }

    fn io_failed(&self, e: &io::Error) -> String {
        if let Some(why) = e.get_ref().and_then(|i| i.downcast_ref::<Unreachable>()) {
            return why.0.clone();
        }
        if let Some(ureq::Error::Timeout(_)) = e.get_ref().and_then(|i| i.downcast_ref()) {
            return self.timed_out();
        }
        if matches!(
            e.kind(),
            io::ErrorKind::TimedOut | io::ErrorKind::WouldBlock
        ) {
            return self.timed_out();
        }
        format!("the request failed: {e}")
    }

    /// What ureq's `e` says, in this API's words: a refusal or a name that doesn't resolve as it was worded, a
    /// timeout, a connection, the rest.
    pub fn failed(&self, e: ureq::Error, url: &Url) -> String {
        let host = host_text(url).unwrap_or_default();
        let port = port_of(url);
        match e {
            ureq::Error::Io(io) => {
                if let Some(why) = io.get_ref().and_then(|i| i.downcast_ref::<Unreachable>()) {
                    return why.0.clone();
                }
                match io.kind() {
                    io::ErrorKind::TimedOut | io::ErrorKind::WouldBlock => self.timed_out(),
                    io::ErrorKind::ConnectionRefused => {
                        format!("couldn't connect to {host}:{port}: the connection was refused")
                    }
                    io::ErrorKind::NotConnected => "the server is stopping its requests".into(),
                    _ => format!("the request to {host} failed: {io}"),
                }
            }
            ureq::Error::Timeout(_) => self.timed_out(),
            ureq::Error::HostNotFound => format!("{host} doesn't resolve"),
            ureq::Error::ConnectionFailed => {
                format!("couldn't connect to {host}:{port}: no address answered")
            }
            ureq::Error::BodyExceedsLimit(_) => self.too_large(),
            other => format!("the request to {host} failed: {other}"),
        }
    }

    /// Ends every agent: what's in flight fails, pooled connections close, and no more requests are made.
    pub fn close(&self) {
        self.closed.store(true, Ordering::SeqCst);
        let agents = std::mem::take(&mut *self.agents.lock().unwrap_or_else(|e| e.into_inner()));
        for (_, agent) in agents {
            agent.shutdown();
        }
    }
}

/// The hosts `allowances` (one package's) let a connection to `port` reach though private, in one order.
fn allowed_at(allowances: &[PrivateAllowance], port: u16) -> Vec<String> {
    let mut hosts: Vec<String> = allowances
        .iter()
        .filter(|a| a.port == port)
        .map(|a| {
            a.host
                .to_lowercase()
                .trim_start_matches('[')
                .trim_end_matches(']')
                .to_owned()
        })
        .collect();
    hosts.sort();
    hosts.dedup();
    hosts
}

/// The next hop of a redirect from `from` to `to` with `status`: the method and headers it keeps.
pub fn redirected(
    from: &HttpCall,
    to: Url,
    status: u16,
    declared: &Declared,
) -> Result<HttpCall, String> {
    let host = host_of(&to).ok_or_else(|| {
        format!("it was redirected to \"{to}\", which has no host or isn't http or https")
    })?;
    if from.url.scheme() == "https" && to.scheme() != "https" {
        return Err(format!("it was redirected from https to plain http ({to})"));
    }
    declared(&host).map_err(|why| format!("it was redirected to {host}, and {why}"))?;
    // 303 is always a GET; 301 and 302 turn a POST into one, as every client does; 307 and 308 repeat the request.
    let to_get = (status == 303 && from.method != "HEAD")
        || ((status == 301 || status == 302) && from.method == "POST");
    let same_host = host_of(&from.url).as_deref() == Some(host.as_str())
        && from.url.scheme() == to.scheme()
        && port_of(&from.url) == port_of(&to);
    let headers = from
        .headers
        .iter()
        .filter(|(name, _)| {
            let lower = name.to_lowercase();
            (same_host || !CREDENTIALS.contains(&lower.as_str()))
                && (!to_get || !BODY_HEADERS.contains(&lower.as_str()))
        })
        .map(|(n, v)| (n.clone(), v.clone()))
        .collect();
    Ok(HttpCall {
        method: if to_get {
            "GET".into()
        } else {
            from.method.clone()
        },
        url: to,
        headers,
        body: if to_get { None } else { from.body.clone() },
    })
}

/// Sends `call` once on `agent`, within `timeout` when there is one: `User-Agent: NetherForge` unless the call sets
/// one, an empty body for a POST, PUT or PATCH that has none.
pub fn send(
    agent: &ureq::Agent,
    call: &HttpCall,
    timeout: Option<Duration>,
) -> Result<ureq::http::Response<ureq::Body>, ureq::Error> {
    let mut builder = ureq::http::Request::builder()
        .method(call.method.as_str())
        .uri(call.url.as_str());
    if !call
        .headers
        .keys()
        .any(|n| n.eq_ignore_ascii_case("user-agent"))
    {
        builder = builder.header("User-Agent", USER_AGENT);
    }
    for (name, value) in &call.headers {
        builder = builder.header(name.as_str(), value.as_str());
    }
    let body = match &call.body {
        Some(body) => Some(body.clone()),
        None if BODY_METHODS.contains(&call.method.as_str()) => Some(Vec::new()),
        None => None,
    };
    match body {
        Some(body) => {
            let request = builder.body(body).map_err(ureq::Error::Http)?;
            let request = agent
                .configure_request(request)
                .timeout_global(timeout)
                .allow_non_standard_methods(true)
                .build();
            agent.run(request)
        }
        None => {
            let request = builder.body(()).map_err(ureq::Error::Http)?;
            let request = agent
                .configure_request(request)
                .timeout_global(timeout)
                .allow_non_standard_methods(true)
                .build();
            agent.run(request)
        }
    }
}

/// Headers by lowercase name, a repeated one's values joined with `, `.
pub fn headers_of(headers: &ureq::http::HeaderMap) -> BTreeMap<String, String> {
    let mut all: BTreeMap<String, String> = BTreeMap::new();
    for (name, value) in headers {
        let value = String::from_utf8_lossy(value.as_bytes()).into_owned();
        all.entry(name.as_str().to_lowercase())
            .and_modify(|v| {
                v.push_str(", ");
                v.push_str(&value);
            })
            .or_insert(value);
    }
    all
}

/// `body` as text by the charset `content_type` names, UTF-8 when it names none or one there's no decoder for.
pub fn decode(body: &[u8], content_type: Option<&str>) -> String {
    let charset = content_type.and_then(|t| {
        let lower = t.to_ascii_lowercase();
        let at = lower.find("charset=")?;
        let rest = &t[at + "charset=".len()..];
        let name = rest
            .split(|c: char| c == ';' || c.is_whitespace())
            .next()?
            .trim_matches('"');
        encoding_rs::Encoding::for_label(name.as_bytes())
    });
    match charset {
        Some(encoding) => encoding.decode_without_bom_handling(body).0.into_owned(),
        None => String::from_utf8_lossy(body).into_owned(),
    }
}
