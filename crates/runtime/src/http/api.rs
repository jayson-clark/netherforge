//! `nf.http`, `HttpStream` and `WebSocket` (Kotlin's `NfHttpImpl` and `ConnectionImpls`), the session's HTTP state,
//! and the service that delivers what connections heard.

use std::collections::{BTreeMap, BTreeSet, HashMap};
use std::rc::{Rc, Weak};
use std::sync::Arc;

use mlua::{Function, Value};
use netherforge_api::api::{HttpStreamApi, NfHttpApi, WebSocketApi};
use netherforge_api::codec::Codec;
use netherforge_api::handles::{self, Handle};
use netherforge_api::{ApiError, ApiResult, Caller, Pending, codecs, shapes};
use netherforge_format::requirement::Requirement;
use netherforge_luau::ScopeId;
use netherforge_platform::Platform;
use url::Url;

use super::connections::{ConnectionRequest, Connections, End, Heard, NORMAL};
use super::fetch::{Declared, Dial, Fetcher, HttpCall, HttpReply, OwnAddresses, Resolve, host_of};
use super::split::StreamFormat;
use super::{HttpConfig, PrivateAllowance, Reply, RequestRate, Workers};
use crate::scripts::Target;
use crate::session::Service;
use crate::shared::Shared;
use crate::world::GameContext;
use crate::{Grants, Room};

fn err(message: impl Into<String>) -> ApiError {
    ApiError(message.into())
}

/// What tests put in place of the network's own answers: where names lead, where connections go, and which
/// addresses are this machine's.
#[derive(Clone, Default)]
pub struct NetHooks {
    pub resolve: Option<Resolve>,
    pub dial: Option<Dial>,
    pub own: Option<OwnAddresses>,
}

impl std::fmt::Debug for NetHooks {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("NetHooks")
            .field("resolve", &self.resolve.is_some())
            .field("dial", &self.dial.is_some())
            .field("own", &self.own.is_some())
            .finish()
    }
}

/// The session's HTTP: the owner's limits, the client and its threads (made on first use, closed when the session
/// stops, so a reload leaves no threads or sockets), the request rates, and the connections open.
#[derive(Default)]
pub struct Http {
    pub config: HttpConfig,
    pub hooks: NetHooks,
    fetcher: Option<Arc<Fetcher>>,
    workers: Option<Workers>,
    rate: Option<RequestRate>,
    allowance_rates: HashMap<PrivateAllowance, RequestRate>,
    pub connections: Connections,
}

impl Http {
    pub fn fetcher(&mut self) -> Arc<Fetcher> {
        let (config, hooks) = (&self.config, &self.hooks);
        self.fetcher
            .get_or_insert_with(|| {
                Arc::new(Fetcher::with(
                    config.clone(),
                    hooks
                        .resolve
                        .clone()
                        .unwrap_or_else(super::fetch::system_resolve),
                    hooks.dial.clone(),
                    hooks.own.clone().unwrap_or_else(super::fetch::system_own),
                ))
            })
            .clone()
    }

    pub fn workers(&mut self) -> Workers {
        self.workers
            .get_or_insert_with(|| Workers::new("NetherForge HTTP"))
            .clone()
    }

    /// Ends the client: what's in flight fails, and its threads end once their jobs are done.
    pub fn close(&mut self) {
        if let Some(fetcher) = self.fetcher.take() {
            fetcher.close();
        }
        self.workers = None;
        self.rate = None;
        self.allowance_rates.clear();
    }

    /// Why package `namespace` can't start another request to `url` this minute, or none when it may (and it's
    /// counted). A request to the host and port of one of its `allowances` with a rate of its own counts against that.
    fn rate_refusal(
        &mut self,
        namespace: &str,
        url: &Url,
        allowances: &[PrivateAllowance],
    ) -> Option<String> {
        let host = super::fetch::host_text(url).unwrap_or_default();
        let port = super::fetch::port_of(url);
        if let Some(own) = allowances.iter().find(|a| {
            a.requests_per_minute.is_some() && a.host.to_lowercase() == host && a.port == port
        }) {
            let most = own.requests_per_minute.unwrap_or_default();
            if most == 0
                || self
                    .allowance_rates
                    .entry(own.clone())
                    .or_insert_with(|| RequestRate::new(most))
                    .try_start(&own.namespace)
            {
                return None;
            }
            return Some(format!(
                "package \"{}\" has made {most} requests to {host}:{port} in the last minute, its limit there \
                 (requests-per-minute of its http.allow-private entry in config.yml)",
                own.namespace
            ));
        }
        let per_minute = self.config.requests_per_minute;
        if self
            .rate
            .get_or_insert_with(|| RequestRate::new(per_minute))
            .try_start(namespace)
        {
            return None;
        }
        Some(format!(
            "package \"{namespace}\" has made {per_minute} requests in the last minute, the server's limit \
             (http.requests-per-minute in config.yml)"
        ))
    }
}

const NO_BODY: &[&str] = &["GET", "HEAD", "OPTIONS"];
const SERVER_SET: &[&str] = &[
    "host",
    "content-length",
    "transfer-encoding",
    "connection",
    "upgrade",
    "expect",
    "te",
    "trailer",
    "keep-alive",
    "proxy-connection",
    "accept-encoding",
];
const APPLICATION_CODES: std::ops::RangeInclusive<i64> = 3000..=4999;
const MAX_REASON_BYTES: usize = 123;

/// Whether `text` is an HTTP token (a header's name, a subprotocol's).
pub fn is_token(text: &str) -> bool {
    !text.is_empty()
        && text
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"!#$%&'*+.^_`|~-".contains(&b))
}

/// `text` as a URL of one of `schemes` with a host, no credentials and a port that is one; else an error at the
/// script's line.
fn checked_url(text: &str, schemes: &[&str]) -> ApiResult<Url> {
    let url = Url::parse(text).map_err(|e| err(format!("options.url isn't a valid URL: {e}")))?;
    let written: Vec<String> = schemes.iter().map(|s| format!("{s}://")).collect();
    if !schemes.contains(&url.scheme()) || url.host_str().is_none_or(str::is_empty) {
        return Err(err(format!(
            "options.url must be an {} URL with a host (got \"{text}\")",
            written.join(" or ")
        )));
    }
    if !url.username().is_empty() || url.password().is_some() {
        return Err(err(
            "options.url can't have a user name or password in it: send credentials in a header",
        ));
    }
    if url.port() == Some(0) {
        return Err(err("options.url has a port that isn't one"));
    }
    Ok(url)
}

/// `given` as headers that can be sent as they are: names that are tokens, values of printable ASCII (and tabs),
/// none the server sets itself, none twice.
fn checked_headers(given: BTreeMap<String, String>) -> ApiResult<BTreeMap<String, String>> {
    let mut seen = BTreeSet::new();
    for (name, value) in &given {
        if !is_token(name) {
            return Err(err(format!(
                "options.headers has a name that isn't a header name: \"{name}\""
            )));
        }
        let lower = name.to_lowercase();
        if !seen.insert(lower.clone()) {
            return Err(err(format!(
                "options.headers has \"{name}\" twice (names are compared without case)"
            )));
        }
        if SERVER_SET.contains(&lower.as_str()) {
            return Err(err(format!(
                "options.headers can't set \"{name}\": the server sets it"
            )));
        }
        if value
            .chars()
            .any(|c| c as u32 > 0x7e || ((c as u32) < 0x20 && c != '\t'))
        {
            return Err(err(format!(
                "options.headers[\"{name}\"] has a character a header can't hold (only printable ASCII and tabs)"
            )));
        }
    }
    Ok(given)
}

/// `nf.http`, its handles, and `nf.images.download`'s request.
#[derive(Clone)]
pub struct HttpApi {
    pub game: GameContext,
    pub grants: Grants,
}

impl HttpApi {
    fn shared(&self) -> &Rc<Shared> {
        &self.game.shared
    }

    fn namespace(&self, scope: ScopeId) -> String {
        self.game
            .scripts
            .namespace(scope)
            .unwrap_or_else(|| self.grants.home.clone())
    }

    fn body(&self, method: &str, text: Option<String>) -> ApiResult<Option<Vec<u8>>> {
        let Some(text) = text else { return Ok(None) };
        if NO_BODY.contains(&method) {
            return Err(err(format!("a {method} request can't have a body")));
        }
        let most = self.shared().http.borrow().config.max_request_bytes;
        if text.len() > most {
            return Err(err(format!(
                "options.body is {} bytes, over the server's limit of {most} (http.max-request-bytes in config.yml)",
                text.len()
            )));
        }
        Ok(Some(text.into_bytes()))
    }

    /// The owner's `http.allow-private` entries for package `namespace`. In a test, `nf.test.allow_private` stands in
    /// for them too (a test has no config.yml).
    fn allowances(&self, namespace: &str) -> Vec<PrivateAllowance> {
        let tests = self.shared().tests.borrow();
        self.shared()
            .http
            .borrow()
            .config
            .allow_private
            .iter()
            .chain(tests.allowances.iter())
            .filter(|a| a.namespace == namespace)
            .cloned()
            .collect()
    }

    /// The host requirement for a redirect's host, bound here to package `namespace`, for `what`.
    fn declared(&self, namespace: &str, what: &'static str) -> Declared {
        let grants = self.grants.clone();
        let namespace = namespace.to_owned();
        Arc::new(move |host: &str| {
            grants
                .check(
                    Some(&namespace),
                    &Requirement::Http(Some(host.to_owned())),
                    what,
                )
                .map_err(|e| e.0)
        })
    }

    /// A request as `nf.http.request` makes it, checked the same way for `what` (the function a script called), with
    /// `read` turning the reply into its value on the worker, after the request (`nf.images.download` decodes there).
    pub fn fetch<T: Send + 'static>(
        &self,
        caller: &Caller,
        options: shapes::HttpRequestOptions,
        what: &'static str,
        read: impl FnOnce(HttpReply) -> Result<T, String> + Send + 'static,
    ) -> ApiResult<Reply<T>> {
        let url = checked_url(&options.url, &["http", "https"])?;
        let host = host_of(&url).unwrap_or_default();
        let method = options.method.unwrap_or_else(|| "GET".into());
        let body = self.body(&method, options.body)?;
        let headers = checked_headers(options.headers.unwrap_or_default())?;
        let namespace = self.namespace(caller.scope);
        // The narrow check: this host, for the package whose code is running. Redirects are held to the same one.
        self.grants
            .check(Some(&namespace), &Requirement::Http(Some(host)), what)?;
        let declared = self.declared(&namespace, what);
        let allowances = self.allowances(&namespace);
        let (send, receive) = std::sync::mpsc::channel();
        let (refusal, fetcher, workers) = {
            let mut http = self.shared().http.borrow_mut();
            (
                http.rate_refusal(&namespace, &url, &allowances),
                http.fetcher(),
                http.workers(),
            )
        };
        if let Some(why) = refusal {
            let _ = send.send(Err(why));
            return Ok(Reply(receive));
        }
        let call = HttpCall {
            method,
            url,
            headers,
            body,
        };
        if self.shared().tests.borrow().harness.is_some() {
            self.answered_in_test(&fetcher, call, &allowances, send, read);
            return Ok(Reply(receive));
        }
        workers.submit(move || {
            let reply = fetcher.fetch(call, &allowances, &declared).and_then(read);
            let _ = send.send(reply);
        });
        Ok(Reply(receive))
    }

    /// A test run's request (`nf.test.http`): nothing reaches the network. The address rules a literal URL meets on a
    /// server are held to first (a name isn't looked up; `localhost` is this machine), then the test's handler answers
    /// it on the main thread at the next tick, as the test's code.
    fn answered_in_test<T: Send + 'static>(
        &self,
        fetcher: &Fetcher,
        call: HttpCall,
        allowances: &[PrivateAllowance],
        send: std::sync::mpsc::Sender<Result<T, String>>,
        read: impl FnOnce(HttpReply) -> Result<T, String> + Send + 'static,
    ) {
        let host = super::fetch::host_text(&call.url).unwrap_or_default();
        let port = super::fetch::port_of(&call.url);
        let literal = if host == "localhost" || host.ends_with(".localhost") {
            "127.0.0.1".to_owned()
        } else {
            host
        };
        if super::guard::looks_literal(&literal)
            && let Err(why) = fetcher.guard(allowances, port).addresses(&literal, port)
        {
            let _ = send.send(Err(why.0));
            return;
        }
        self.shared()
            .tests
            .borrow_mut()
            .queued
            .push(crate::testing::Queued {
                call,
                done: Box::new(move |reply| {
                    let _ = send.send(reply.and_then(read));
                }),
            });
    }

    /// Opens a stream or a WebSocket for the running script, its limits checked.
    fn open(
        &self,
        caller: &Caller,
        request: ConnectionRequest,
        url: String,
        websocket: bool,
    ) -> i64 {
        let namespace = self.namespace(caller.scope);
        let tick = self.game.scripts.tick_count();
        let mut http = self.shared().http.borrow_mut();
        let refusal = http.rate_refusal(&namespace, &request.url, &request.allowances);
        let limits = http.config.connections.clone();
        let fetcher = http.fetcher();
        http.connections.open(
            caller.scope,
            &namespace,
            request,
            url,
            websocket,
            refusal,
            &limits,
            tick,
            || fetcher,
        )
    }

    fn handle_of(&self, number: i64) -> Option<Handle> {
        let http = self.shared().http.borrow();
        let connection = http.connections.find(number)?;
        Some(if connection.websocket {
            Handle::WebSocket(handles::WebSocket {
                number,
                url: connection.url.clone(),
            })
        } else {
            Handle::HttpStream(handles::HttpStream {
                number,
                url: connection.url.clone(),
            })
        })
    }

    fn target(&self, handle: &Handle) -> Option<Target> {
        netherforge_api::handle::push_any(self.game.scripts.host().lua(), handle)
            .ok()
            .map(Target::Handle)
    }

    /// Raises what a connection heard on its handle. An end is raised while the handle still answers, then the
    /// connection is forgotten with every handler on it.
    pub fn hear(&self, heard: Heard) {
        let scripts = &self.game.scripts;
        let lua = scripts.host().lua().clone();
        let number = match &heard {
            Heard::Opened { number, .. }
            | Heard::Message { number, .. }
            | Heard::Ended { number, .. } => *number,
        };
        let Some(handle) = self.handle_of(number) else {
            return;
        };
        let target = self.target(&handle);
        let status = self
            .shared()
            .http
            .borrow()
            .connections
            .find(number)
            .and_then(|c| c.status);
        let (event, payload) = match (&handle, heard) {
            (
                Handle::WebSocket(socket),
                Heard::Opened {
                    headers, protocol, ..
                },
            ) => (
                "open",
                codecs::WebSocketOpenEvent.push(
                    &lua,
                    shapes::WebSocketOpenEvent {
                        socket: socket.clone(),
                        protocol,
                        headers,
                    },
                ),
            ),
            (
                Handle::HttpStream(stream),
                Heard::Opened {
                    status, headers, ..
                },
            ) => (
                "open",
                codecs::HttpStreamOpenEvent.push(
                    &lua,
                    shapes::HttpStreamOpenEvent {
                        stream: stream.clone(),
                        status: i64::from(status),
                        headers,
                    },
                ),
            ),
            (Handle::WebSocket(socket), Heard::Message { message, .. }) => (
                "message",
                codecs::WebSocketMessageEvent.push(
                    &lua,
                    shapes::WebSocketMessageEvent {
                        socket: socket.clone(),
                        data: message.data,
                    },
                ),
            ),
            (Handle::HttpStream(stream), Heard::Message { message, .. }) => (
                "message",
                codecs::HttpStreamMessageEvent.push(
                    &lua,
                    shapes::HttpStreamMessageEvent {
                        stream: stream.clone(),
                        data: message.data,
                        r#type: message.r#type,
                        id: message.id,
                    },
                ),
            ),
            (
                Handle::WebSocket(socket),
                Heard::Ended {
                    reason,
                    code,
                    detail,
                    ..
                },
            ) => (
                "end",
                codecs::WebSocketEndEvent.push(
                    &lua,
                    shapes::WebSocketEndEvent {
                        socket: socket.clone(),
                        reason: reason.name().into(),
                        code: code.map(i64::from),
                        detail,
                    },
                ),
            ),
            (Handle::HttpStream(stream), Heard::Ended { reason, detail, .. }) => (
                "end",
                codecs::HttpStreamEndEvent.push(
                    &lua,
                    shapes::HttpStreamEndEvent {
                        stream: stream.clone(),
                        reason: reason.name().into(),
                        status: status.map(i64::from),
                        detail,
                    },
                ),
            ),
            _ => return,
        };
        if let (Some(target), Ok(Value::Table(fields))) = (&target, payload) {
            scripts.raise(target, event, fields);
        }
        if event == "end" {
            self.shared()
                .http
                .borrow_mut()
                .connections
                .active
                .shift_remove(&number);
            if let Some(target) = &target {
                scripts.drop_target(target);
            }
        }
    }

    /// Ends `number` for `reason` (and its `end` is heard now).
    pub fn end(
        &self,
        number: i64,
        reason: End,
        close_code: Option<u16>,
        close_reason: Option<String>,
    ) -> bool {
        let heard = self.shared().http.borrow_mut().connections.end(
            number,
            reason,
            None,
            None,
            close_code,
            close_reason,
        );
        let ended = heard.is_some();
        if let Some(heard) = heard {
            self.hear(heard);
        }
        ended
    }

    /// The connections' pass: what each heard, in order, at most `messages-per-tick` each, then idle and unanswered
    /// ones ended.
    pub fn pass(&self) {
        let numbers: Vec<i64> = self
            .shared()
            .http
            .borrow()
            .connections
            .active
            .keys()
            .copied()
            .collect();
        let now = self.game.scripts.tick_count();
        for number in numbers {
            let mut delivered = 0;
            loop {
                let heard = {
                    let mut http = self.shared().http.borrow_mut();
                    let limits = http.config.connections.clone();
                    let timeout = http.config.timeout_seconds;
                    http.connections
                        .next_heard(number, &mut delivered, &limits, timeout, now)
                };
                let Some(heard) = heard else { break };
                self.hear(heard);
            }
        }
    }

    /// Ends every connection `which` names `"unloaded"`.
    pub fn unload_where(&self, which: impl Fn(ScopeId) -> bool) {
        let numbers: Vec<i64> = self
            .shared()
            .http
            .borrow()
            .connections
            .active
            .values()
            .filter(|c| !c.ended && which(c.owner))
            .map(|c| c.number)
            .collect();
        for number in numbers {
            self.end(number, End::Unloaded, None, None);
        }
    }

    /// Tells `scripts` which stream and socket handles still name something.
    pub fn install_liveness(shared: &Rc<Shared>, scripts: &crate::Scripts) {
        let weak: Weak<Shared> = Rc::downgrade(shared);
        scripts.add_liveness(Box::new(move |handle| {
            let shared = weak.upgrade()?;
            let http = shared.http.try_borrow().ok()?;
            match handle {
                Handle::HttpStream(h) => Some(http.connections.find(h.number).is_some()),
                Handle::WebSocket(h) => Some(http.connections.find(h.number).is_some()),
                _ => None,
            }
        }));
    }

    fn connection<R>(
        &self,
        number: i64,
        f: impl FnOnce(&super::connections::Connection) -> R,
    ) -> Option<R> {
        self.shared().http.borrow().connections.find(number).map(f)
    }
}

impl NfHttpApi for HttpApi {
    fn request(
        &self,
        caller: &Caller,
        options: shapes::HttpRequestOptions,
    ) -> ApiResult<Pending<shapes::HttpResponse>> {
        let reply = self.fetch(caller, options, "nf.http.request", |reply| {
            Ok(shapes::HttpResponse {
                status: i64::from(reply.status),
                headers: reply.headers,
                body: reply.body,
                url: reply.url.to_string(),
            })
        })?;
        Ok(Box::pin(async move { reply.await.map_err(ApiError) }))
    }

    fn stream(
        &self,
        caller: &Caller,
        options: shapes::HttpStreamOptions,
    ) -> ApiResult<handles::HttpStream> {
        let url = checked_url(&options.url, &["http", "https"])?;
        let method = options.method.unwrap_or_else(|| "GET".into());
        let body = self.body(&method, options.body)?;
        let format = StreamFormat::of(options.format.as_deref());
        let mut headers = checked_headers(options.headers.unwrap_or_default())?;
        if format == StreamFormat::Events
            && !headers.keys().any(|n| n.eq_ignore_ascii_case("accept"))
        {
            headers.insert("Accept".into(), "text/event-stream".into());
        }
        let namespace = self.namespace(caller.scope);
        self.grants.check(
            Some(&namespace),
            &Requirement::Http(host_of(&url)),
            "nf.http.stream",
        )?;
        let request = ConnectionRequest {
            url,
            method,
            headers,
            body,
            format: Some(format),
            protocols: Vec::new(),
            allowances: self.allowances(&namespace),
            declared: self.declared(&namespace, "nf.http.stream"),
        };
        let number = self.open(caller, request, options.url.clone(), false);
        Ok(handles::HttpStream {
            number,
            url: options.url,
        })
    }

    fn websocket(
        &self,
        caller: &Caller,
        options: shapes::WebSocketOptions,
    ) -> ApiResult<handles::WebSocket> {
        let url = checked_url(&options.url, &["ws", "wss"])?;
        let headers = checked_headers(options.headers.unwrap_or_default())?;
        if let Some(name) = headers
            .keys()
            .find(|n| n.to_lowercase().starts_with("sec-websocket-"))
        {
            return Err(err(format!(
                "options.headers can't set \"{name}\": the server sets it (offer subprotocols with options.protocols)"
            )));
        }
        let protocols = options.protocols.unwrap_or_default();
        if let Some(bad) = protocols.iter().find(|p| !is_token(p)) {
            return Err(err(format!(
                "options.protocols has \"{bad}\", which isn't a protocol name"
            )));
        }
        let namespace = self.namespace(caller.scope);
        self.grants.check(
            Some(&namespace),
            &Requirement::Http(super::fetch::host_text(&url)),
            "nf.http.websocket",
        )?;
        let request = ConnectionRequest {
            url,
            method: "GET".into(),
            headers,
            body: None,
            format: None,
            protocols,
            allowances: self.allowances(&namespace),
            declared: Arc::new(|host: &str| {
                Err(format!("WebSockets don't follow redirects (to {host})"))
            }),
        };
        let number = self.open(caller, request, options.url.clone(), true);
        Ok(handles::WebSocket {
            number,
            url: options.url,
        })
    }
}

impl HttpStreamApi for HttpApi {
    fn url(&self, this: &handles::HttpStream) -> ApiResult<String> {
        Ok(this.url.clone())
    }
    fn state(&self, this: &handles::HttpStream) -> ApiResult<String> {
        Ok(self
            .connection(this.number, |c| c.state())
            .unwrap_or("ended")
            .to_owned())
    }
    fn status(&self, this: &handles::HttpStream) -> ApiResult<Option<i64>> {
        Ok(self
            .connection(this.number, |c| c.status)
            .flatten()
            .map(i64::from))
    }
    fn close(&self, this: &handles::HttpStream) -> ApiResult<bool> {
        Ok(self.end(this.number, End::Closed, None, None))
    }
    fn on(
        &self,
        this: &handles::HttpStream,
        event: String,
        handler: Function,
        options: Option<shapes::EventOptions>,
    ) -> ApiResult<handles::Subscription> {
        self.game.subscribe_on(
            Handle::HttpStream(this.clone()),
            "HttpStream:on",
            event,
            handler,
            options,
            false,
        )
    }
    fn once(
        &self,
        this: &handles::HttpStream,
        event: String,
        handler: Function,
    ) -> ApiResult<handles::Subscription> {
        self.game.subscribe_on(
            Handle::HttpStream(this.clone()),
            "HttpStream:once",
            event,
            handler,
            None,
            true,
        )
    }
}

impl WebSocketApi for HttpApi {
    fn url(&self, this: &handles::WebSocket) -> ApiResult<String> {
        Ok(this.url.clone())
    }
    fn state(&self, this: &handles::WebSocket) -> ApiResult<String> {
        Ok(self
            .connection(this.number, |c| c.state())
            .unwrap_or("ended")
            .to_owned())
    }
    fn protocol(&self, this: &handles::WebSocket) -> ApiResult<Option<String>> {
        Ok(self
            .connection(this.number, |c| c.protocol.clone())
            .flatten())
    }
    fn send(&self, this: &handles::WebSocket, text: String) -> ApiResult<bool> {
        let (limits, tick) = (
            self.shared().http.borrow().config.connections.clone(),
            self.game.scripts.tick_count(),
        );
        if text.len() > limits.max_message_bytes {
            return Err(err(format!(
                "the message is {} bytes, over the server's limit of {} (http.connections.max-message-bytes in config.yml)",
                text.len(),
                limits.max_message_bytes
            )));
        }
        let (sent, heard) =
            self.shared()
                .http
                .borrow_mut()
                .connections
                .send(this.number, text, &limits, tick);
        if let Some(heard) = heard {
            self.hear(heard);
        }
        Ok(sent)
    }
    fn close(
        &self,
        this: &handles::WebSocket,
        code: Option<i64>,
        reason: Option<String>,
    ) -> ApiResult<bool> {
        let code = code.unwrap_or(i64::from(NORMAL));
        if code != i64::from(NORMAL) && !APPLICATION_CODES.contains(&code) {
            return Err(err(format!(
                "bad argument 'code' (1000, or 3000 to 4999 expected, got {code})"
            )));
        }
        if reason.as_ref().is_some_and(|r| r.len() > MAX_REASON_BYTES) {
            return Err(err(format!(
                "bad argument 'reason' (at most {MAX_REASON_BYTES} bytes expected)"
            )));
        }
        Ok(self.end(this.number, End::Closed, Some(code as u16), reason))
    }
    fn on(
        &self,
        this: &handles::WebSocket,
        event: String,
        handler: Function,
        options: Option<shapes::EventOptions>,
    ) -> ApiResult<handles::Subscription> {
        self.game.subscribe_on(
            Handle::WebSocket(this.clone()),
            "WebSocket:on",
            event,
            handler,
            options,
            false,
        )
    }
    fn once(
        &self,
        this: &handles::WebSocket,
        event: String,
        handler: Function,
    ) -> ApiResult<handles::Subscription> {
        self.game.subscribe_on(
            Handle::WebSocket(this.clone()),
            "WebSocket:once",
            event,
            handler,
            None,
            true,
        )
    }
}

/// HTTP's service: hands scripts what connections heard each tick, ends what a stopped scope opened, and closes the
/// client when the session stops.
pub struct HttpService {
    pub shared: Rc<Shared>,
    /// The room's API for HTTP, once it starts.
    pub api: std::cell::RefCell<Option<HttpApi>>,
}

impl Service for HttpService {
    fn costs(&self, scope: netherforge_luau::ScopeId) -> Vec<(&'static str, usize)> {
        vec![(
            "connections",
            self.shared.http.borrow().connections.count_owned(scope),
        )]
    }

    fn name(&self) -> &'static str {
        "http"
    }

    fn start(&self, room: &Room, project: &crate::session::Define) {
        // A test run's connections reach no network: the test plays their far ends.
        self.shared.http.borrow_mut().connections.testing =
            self.shared.tests.borrow().harness.is_some();
        *self.api.borrow_mut() = Some(HttpApi {
            game: GameContext::new(self.shared.clone(), room.scripts().clone()),
            grants: crate::session::grants(project.project),
        });
    }

    fn tick(&self, _room: &Room, _platform: &dyn Platform) {
        let api = self.api.borrow().clone();
        if let Some(api) = api
            && !self.shared.http.borrow().connections.active.is_empty()
        {
            api.pass();
        }
    }

    fn scope_released(&self, scopes: &[ScopeId]) {
        let api = self.api.borrow().clone();
        if let Some(api) = api {
            api.unload_where(|owner| scopes.contains(&owner));
        }
    }

    fn stop(&self, _room: &Room, _platform: &dyn Platform) {
        let api = self.api.borrow_mut().take();
        if let Some(api) = api {
            api.unload_where(|_| true);
        }
        let mut http = self.shared.http.borrow_mut();
        let testing = http.connections.testing;
        http.connections = Connections::default();
        http.connections.testing = testing;
        http.close();
    }
}
