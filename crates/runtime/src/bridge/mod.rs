//! The dev bridge's server end (Kotlin's `bridge/`): the connection to the editor that started this dev server
//! (`client`, `frames`), the routing of what it sends (`Inbound`, on the bridge's thread), and what answers its
//! requests on the main thread (`Bridge`, `requests`).
//!
//! **Threads.** Frames arrive on the bridge's thread. A request runs on the thread its method declares
//! (`bridge::Thread`): `Bridge` ones (`ping`, `profiler_subscribe`) are answered there at once, everything else is
//! handed to the main thread and answered when [`Bridge::run`] takes it, at the start of the next tick, where the
//! session lives (it isn't `Send`). Every request gets exactly one response; a batch is answered with one array once
//! every request in it is.
//!
//! **The debugger's hook.** `dap` notifications go to the [`Debugger`] set with [`Bridge::set_debugger`], on the
//! bridge's thread, so they're heard while a breakpoint holds the main thread; while it holds it, a main-thread request
//! is refused at once with the debugger's sentence, and when the connection ends the debugger detaches. There's no
//! debugger yet (the next phase): with none, `dap` is ignored.
//!
//! **What the editor hears unasked**: the console (every line the runtime logs, through [`Bridge::platform`]'s log,
//! and scripts' lines and errors as themselves, through [`Console`]), `problems` (on connect, after every reload, and
//! when they change, looked at once a second), `status` (on change, at most once a second) and `settings_changed`.

pub mod client;
pub mod frames;
pub mod requests;

use std::cell::{Cell, RefCell};
use std::path::PathBuf;
use std::rc::Rc;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::mpsc::{Receiver, Sender, channel};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

use netherforge_format::Problem;
use netherforge_format::bridge::rpc::{self, Frame, Message};
use netherforge_format::bridge::{
    self, ConsoleEntry, Event, HelloParams, LogLevel, Method, SourceRef,
};
use netherforge_platform::{Platform, PlatformError, PlatformLog};
use serde_json::Value;

use crate::server::ServerEvents;
use client::Client;

/// How long a dev server keeps running without its editor before it stops itself: every editor run listens on a new
/// port, so a server whose editor quit, crashed or was killed can never reconnect.
pub const DEFAULT_ABANDON_AFTER: Duration = Duration::from_secs(15);

/// How often the editor hears the status and the problems, if they changed: once a second.
pub const STATUS_EVERY: u64 = 20;

/// What the bridge is started with (Kotlin's `BridgeConfig`).
#[derive(Clone)]
pub struct BridgeConfig {
    pub port: u16,
    pub token: String,
    pub abandon_after: Duration,
    /// The server's folder, which the editor started it in: paths the bridge answers with (`save_world`, settings'
    /// files) are relative to it, since the editor reads files from its dev server's folder and nowhere else.
    pub server_directory: PathBuf,
    /// The project's folder, as the hello names it.
    pub project: PathBuf,
}

impl BridgeConfig {
    /// The bridge `config::read` found (a dev server's port and token, from the editor's properties), for the project
    /// it runs, in the server's folder `server_directory`; none on a server the editor didn't start.
    pub fn of(
        config: &crate::config::RuntimeConfig,
        server_directory: PathBuf,
    ) -> Option<BridgeConfig> {
        let bridge = config.bridge.as_ref()?;
        Some(BridgeConfig {
            server_directory,
            ..BridgeConfig::new(bridge.port, bridge.token.clone(), config.project.clone())
        })
    }

    pub fn new(port: u16, token: impl Into<String>, project: PathBuf) -> BridgeConfig {
        BridgeConfig {
            port,
            token: token.into(),
            abandon_after: DEFAULT_ABANDON_AFTER,
            server_directory: std::env::current_dir().unwrap_or_default(),
            project,
        }
    }
}

/// Where scripts' lines and errors go when there's a bridge: the server's console gets the line, the editor's the
/// entry, located.
pub trait Console {
    /// A line a script logged: `line` for the server's console, `message` and `source` for the editor's.
    fn script_line(&self, line: String, message: String, source: Option<SourceRef>);
    /// A script's failure: `line` for the server's console (a warning), `message`, `source` and `traceback` for the
    /// editor's.
    fn script_error(
        &self,
        line: String,
        message: String,
        source: Option<SourceRef>,
        traceback: Option<String>,
    );
}

/// The debugger, as the bridge reaches it (the next phase's): DAP messages in, whether it holds the main thread, and
/// letting go when the editor does. Called on the bridge's thread.
pub trait Debugger: Send + Sync {
    /// One Debug Adapter Protocol message from the editor.
    fn receive(&self, message: Value);
    /// Why a main-thread request can't run now (the server is paused at a breakpoint), or none.
    fn refusal(&self) -> Option<String>;
    /// The editor went: nothing may stay paused.
    fn detach(&self);
}

/// Something the bridge's thread hands the main thread.
enum Work {
    Request {
        method: &'static str,
        params: Option<Value>,
        reply: Reply,
    },
    Connected,
    Problem(String),
    Refused(String),
    Abandoned,
}

/// Where a request's one response goes: alone, or into its batch's array.
enum Sink {
    Single(Client, Value),
    InBatch {
        batch: Arc<BatchSlots>,
        index: usize,
        id: Value,
    },
}

struct BatchSlots {
    client: Client,
    slots: Mutex<Vec<Option<Message>>>,
    left: AtomicUsize,
}

struct ReplyInner {
    answered: AtomicBool,
    sink: Sink,
}

/// A request's answer, given once (later ones are dropped), from any thread.
#[derive(Clone)]
pub struct Reply(Arc<ReplyInner>);

/// Why a request failed: its JSON-RPC code and the sentence the editor shows.
#[derive(Clone, Debug, PartialEq)]
pub struct Failure {
    pub code: i64,
    pub message: String,
}

impl Failure {
    /// The request was understood and couldn't be done.
    pub fn request(message: impl Into<String>) -> Failure {
        Failure {
            code: bridge::REQUEST_FAILED,
            message: message.into(),
        }
    }
}

impl Reply {
    fn new(sink: Sink) -> Reply {
        Reply(Arc::new(ReplyInner {
            answered: AtomicBool::new(false),
            sink,
        }))
    }

    /// Answers with `result`, or the failure.
    pub fn answer(&self, outcome: Result<Value, Failure>) {
        if self.0.answered.swap(true, Ordering::SeqCst) {
            return;
        }
        let response = |id: Value| match outcome {
            Ok(result) => Message::ok(id, result),
            Err(failure) => Message::error(id, failure.code, failure.message),
        };
        match &self.0.sink {
            Sink::Single(client, id) => client.send(&response(id.clone())),
            Sink::InBatch { batch, index, id } => {
                batch.slots.lock().unwrap()[*index] = Some(response(id.clone()));
                if batch.left.fetch_sub(1, Ordering::SeqCst) == 1 {
                    let all: Vec<Message> =
                        batch.slots.lock().unwrap().drain(..).flatten().collect();
                    batch.client.send_batch(&all);
                }
            }
        }
    }
}

/// The bridge's thread's half: routes each frame, answers what's answered there, hands the rest to the main thread.
struct Inbound {
    client: OnceLock<Client>,
    work: Mutex<Sender<Work>>,
    hello: HelloParams,
    profiler: Arc<AtomicBool>,
    debugger: Arc<Mutex<Option<Arc<dyn Debugger>>>>,
}

impl Inbound {
    fn client(&self) -> &Client {
        self.client.get().expect("set as the client is made")
    }

    fn hand(&self, work: Work) {
        let _ = self.work.lock().unwrap().send(work);
    }

    fn dispatch(&self, message: Message, sink: Option<Sink>) {
        match message {
            Message::Request { id, method, params } => {
                let sink = sink.unwrap_or_else(|| Sink::Single(self.client().clone(), id.clone()));
                self.request(&method, params, Reply::new(sink));
            }
            Message::Invalid { id, error } => {
                let sink = sink.unwrap_or_else(|| Sink::Single(self.client().clone(), id.clone()));
                Reply::new(sink).answer(Err(Failure {
                    code: error.code,
                    message: error.message,
                }));
            }
            // The debugger's channel is the only notification the server acts on; the editor answers only the hello.
            Message::Notification { method, params } => {
                if method == bridge::Dap::NAME
                    && let Some(params) = params
                    && let Some(debugger) = self.debugger.lock().unwrap().clone()
                {
                    debugger.receive(params);
                }
            }
            Message::Response { .. } => {}
        }
    }

    fn request(&self, method: &str, params: Option<Value>, reply: Reply) {
        let Some(info) = bridge::request(method) else {
            return reply.answer(Err(Failure {
                code: rpc::METHOD_NOT_FOUND,
                message: format!("Unknown method \"{method}\""),
            }));
        };
        if let Some(Err(e)) = bridge::reencode_params(info.name, params.as_ref()) {
            return reply.answer(Err(Failure {
                code: rpc::INVALID_PARAMS,
                message: format!("Invalid params for {}: {e}", info.name),
            }));
        }
        match info.thread {
            bridge::Thread::Bridge => reply.answer(self.on_bridge_thread(info.name, params)),
            // While the debugger holds the main thread nothing handed to it would run: say why at once.
            bridge::Thread::Main => {
                let paused = self
                    .debugger
                    .lock()
                    .unwrap()
                    .as_ref()
                    .and_then(|d| d.refusal());
                match paused {
                    Some(why) => reply.answer(Err(Failure::request(why))),
                    None => self.hand(Work::Request {
                        method: info.name,
                        params,
                        reply,
                    }),
                }
            }
        }
    }

    /// The control requests, answered at once whatever the main thread is doing.
    fn on_bridge_thread(&self, method: &str, params: Option<Value>) -> Result<Value, Failure> {
        match method {
            bridge::Ping::NAME => Ok(Value::Null),
            // It only sets a flag the main thread reads.
            bridge::ProfilerSubscribe::NAME => {
                let on = bridge::decode_params::<bridge::ProfilerSubscribe>(params.as_ref())
                    .map_err(|e| Failure {
                        code: rpc::INVALID_PARAMS,
                        message: format!("Invalid params for {method}: {e}"),
                    })?
                    .on;
                self.profiler.store(on, Ordering::SeqCst);
                Ok(Value::Null)
            }
            _ => Err(Failure {
                code: rpc::INTERNAL_ERROR,
                message: format!("{method} has no answer on the bridge's thread"),
            }),
        }
    }
}

impl client::Events for Inbound {
    fn hello(&self, nonce: &str) -> HelloParams {
        HelloParams {
            nonce: nonce.to_owned(),
            ..self.hello.clone()
        }
    }

    fn frame(&self, frame: Frame) {
        match frame {
            Frame::Single(message) => self.dispatch(message, None),
            Frame::Batch(messages) => {
                // A batch is answered with one array of its responses, once all are in; notifications have none.
                let expected = messages
                    .iter()
                    .filter(|m| matches!(m, Message::Request { .. } | Message::Invalid { .. }))
                    .count();
                if expected == 0 {
                    for message in messages {
                        self.dispatch(message, None);
                    }
                    return;
                }
                let batch = Arc::new(BatchSlots {
                    client: self.client().clone(),
                    slots: Mutex::new(vec![None; messages.len()]),
                    left: AtomicUsize::new(expected),
                });
                for (index, message) in messages.into_iter().enumerate() {
                    let sink = match &message {
                        Message::Request { id, .. } | Message::Invalid { id, .. } => {
                            Some(Sink::InBatch {
                                batch: batch.clone(),
                                index,
                                id: id.clone(),
                            })
                        }
                        _ => None,
                    };
                    self.dispatch(message, sink);
                }
            }
        }
    }

    fn connected(&self) {
        self.hand(Work::Connected);
    }

    // An editor that went away while the server was paused mustn't leave it paused.
    fn disconnected(&self) {
        if let Some(debugger) = self.debugger.lock().unwrap().clone() {
            debugger.detach();
        }
    }

    fn problem(&self, message: String) {
        self.hand(Work::Problem(message));
    }

    fn refused(&self, message: String) {
        self.hand(Work::Refused(message));
    }

    fn abandoned(&self) {
        self.hand(Work::Abandoned);
    }
}

/// The server's console, and the editor's through the bridge (Kotlin's `RuntimeLog`): what the runtime logs goes
/// to both.
struct TeeLog {
    inner: Rc<dyn Platform>,
    client: Client,
}

impl TeeLog {
    fn send(&self, level: LogLevel, message: String) {
        self.client.stream::<bridge::Console>(&ConsoleEntry::Log {
            level,
            message,
            source: None,
        });
    }
}

impl PlatformLog for TeeLog {
    fn info(&self, message: String) {
        self.inner.log().info(message.clone());
        self.send(LogLevel::Info, message);
    }
    fn warn(&self, message: String) {
        self.inner.log().warn(message.clone());
        self.send(LogLevel::Warn, message);
    }
    fn error(&self, message: String, cause: Option<PlatformError>) {
        let shown = match &cause {
            Some(cause) => format!("{message}: {}", cause.message),
            None => message.clone(),
        };
        self.inner.log().error(message, cause);
        self.send(LogLevel::Error, shown);
    }
}

struct BridgeConsole {
    inner: Rc<dyn Platform>,
    client: Client,
}

impl Console for BridgeConsole {
    fn script_line(&self, line: String, message: String, source: Option<SourceRef>) {
        self.inner.log().info(line);
        self.client.stream::<bridge::Console>(&ConsoleEntry::Log {
            level: LogLevel::Info,
            message,
            source,
        });
    }
    fn script_error(
        &self,
        line: String,
        message: String,
        source: Option<SourceRef>,
        traceback: Option<String>,
    ) {
        self.inner.log().warn(line);
        self.client
            .stream::<bridge::Console>(&ConsoleEntry::ScriptError {
                message,
                source,
                traceback,
            });
    }
}

/// The dev bridge on the main thread (Kotlin's `BridgeService`): made with the platform as the adapter has it, it
/// hands back the platform the runtime should run on ([`Bridge::platform`]: the same, its log teed to the editor),
/// and the server's events run its work each tick once attached (`ServerEvents::attach_bridge`).
pub struct Bridge {
    config: BridgeConfig,
    client: Client,
    inbox: Receiver<Work>,
    profiler: Arc<AtomicBool>,
    debugger: Arc<Mutex<Option<Arc<dyn Debugger>>>>,
    inner: Rc<dyn Platform>,
    last_status: RefCell<Option<bridge::Status>>,
    last_problems: RefCell<Option<Vec<Problem>>>,
    ticks: Cell<u64>,
}

impl Bridge {
    /// The bridge for `platform`, connecting to the editor at once.
    pub fn start(platform: Rc<dyn Platform>, config: BridgeConfig) -> Rc<Bridge> {
        let bridge = Bridge::new(platform, config);
        bridge.client.start();
        bridge
    }

    /// The bridge, not connecting yet (`connect`).
    pub fn new(platform: Rc<dyn Platform>, config: BridgeConfig) -> Rc<Bridge> {
        let info = platform.info();
        let (to, inbox) = channel();
        let profiler = Arc::new(AtomicBool::new(false));
        let debugger: Arc<Mutex<Option<Arc<dyn Debugger>>>> = Arc::default();
        let inbound = Arc::new(Inbound {
            client: OnceLock::new(),
            work: Mutex::new(to),
            hello: HelloParams {
                nonce: String::new(),
                protocol: bridge::PROTOCOL,
                plugin_version: info.plugin_version,
                minecraft: info.minecraft_version,
                project: config.project.display().to_string(),
            },
            profiler: profiler.clone(),
            debugger: debugger.clone(),
        });
        let settings = client::Settings {
            abandon_after: config.abandon_after,
            ..client::Settings::new(config.port, config.token.clone())
        };
        let client = Client::new(settings, inbound.clone());
        let _ = inbound.client.set(client.clone());
        Rc::new(Bridge {
            config,
            client,
            inbox,
            profiler,
            debugger,
            inner: platform,
            last_status: RefCell::new(None),
            last_problems: RefCell::new(None),
            ticks: Cell::new(0),
        })
    }

    /// Starts connecting.
    pub fn connect(&self) {
        self.client.start();
    }

    /// Stops: the connection closes and nothing more is tried.
    pub fn stop(&self) {
        self.client.stop();
    }

    /// The platform the runtime runs on while there's a bridge: the adapter's, its log teed to the editor's console.
    pub fn platform(&self) -> Rc<dyn Platform> {
        Rc::new(netherforge_platform::LogOver {
            inner: self.inner.clone(),
            log: Box::new(TeeLog {
                inner: self.inner.clone(),
                client: self.client.clone(),
            }),
        })
    }

    /// Where scripts' lines and errors go (`Shared::console`).
    pub fn console(&self) -> Rc<dyn Console> {
        Rc::new(BridgeConsole {
            inner: self.inner.clone(),
            client: self.client.clone(),
        })
    }

    pub fn config(&self) -> &BridgeConfig {
        &self.config
    }

    /// Whether an editor is connected.
    pub fn connected(&self) -> bool {
        self.client.connected()
    }

    /// Whether the editor wants the profiler's stream.
    pub fn profiler_streaming(&self) -> bool {
        self.profiler.load(Ordering::SeqCst)
    }

    /// The debugger `dap` notifications go to (the next phase's).
    pub fn set_debugger(&self, debugger: Option<Arc<dyn Debugger>>) {
        *self.debugger.lock().unwrap() = debugger;
    }

    /// Sends one of the debugger's DAP messages, only to an editor that's connected: a DAP session is the
    /// connection's, and the next one starts its own, so nothing is kept for it.
    pub fn dap(&self, message: Value) {
        if self.client.connected() {
            self.client.notify::<bridge::Dap>(&bridge::Json(message));
        }
    }

    /// Notification `E` to the editor.
    pub fn notify<E: bridge::Event>(&self, params: &E::Params) {
        self.client.notify::<E>(params);
    }

    /// An item on stream `S` (the profiler's samples).
    pub fn stream<S: bridge::Stream>(&self, item: &S::Item) {
        self.client.stream::<S>(item);
    }

    /// What the bridge's thread handed the main thread since the last tick: requests answered, the connection's
    /// news logged. At the start of every tick, outside the session's.
    pub fn run(&self, events: &ServerEvents) {
        while let Ok(work) = self.inbox.try_recv() {
            match work {
                Work::Request {
                    method,
                    params,
                    reply,
                } => requests::answer(self, events, method, params, reply),
                Work::Connected => self.send_state(events),
                Work::Problem(message) => self.inner.log().warn(message),
                Work::Refused(message) => self.inner.log().error(
                    format!("The NetherForge editor refused this dev server's bridge: {message}"),
                    None,
                ),
                Work::Abandoned => {
                    self.inner.log().warn(format!(
                        "The NetherForge editor that started this dev server has been gone for {}s; stopping the server",
                        self.config.abandon_after.as_secs()
                    ));
                    self.inner
                        .shutdown_server("NetherForge editor closed".into());
                }
            }
        }
    }

    /// After a tick: the status and the problems, once a second, if they changed.
    pub fn tick(&self, events: &ServerEvents) {
        let ticks = self.ticks.get() + 1;
        self.ticks.set(ticks);
        if ticks.is_multiple_of(STATUS_EVERY) {
            self.send_status(events, false);
            self.send_problems(events, false);
        }
    }

    /// Everything an editor that just connected needs to catch up.
    fn send_state(&self, events: &ServerEvents) {
        self.send_problems(events, true);
        self.send_settings(events);
        self.send_status(events, true);
    }

    pub(crate) fn send_problems(&self, events: &ServerEvents, force: bool) {
        let Ok(session) = events.try_session() else {
            return;
        };
        let problems = session.problems();
        if force || self.last_problems.borrow().as_ref() != Some(&problems) {
            *self.last_problems.borrow_mut() = Some(problems.clone());
            self.notify::<bridge::ProblemsChanged>(&bridge::Problems { problems });
        }
    }

    pub(crate) fn send_settings(&self, events: &ServerEvents) {
        let Ok(session) = events.try_session() else {
            return;
        };
        let settings = requests::settings(self, &session);
        self.notify::<bridge::SettingsChanged>(&settings);
    }

    fn send_status(&self, events: &ServerEvents, force: bool) {
        let Ok(session) = events.try_session() else {
            return;
        };
        let mut players: Vec<String> = self
            .inner
            .players()
            .online()
            .into_iter()
            .map(|p| p.name)
            .collect();
        players.sort();
        let status = bridge::Status {
            players,
            // Inert records too: an instance whose centity is missing is still there.
            instances: {
                let centities = session.centities();
                (centities.all().count() + centities.inert().count()) as i32
            },
        };
        if force || self.last_status.borrow().as_ref() != Some(&status) {
            *self.last_status.borrow_mut() = Some(status.clone());
            self.notify::<bridge::StatusChanged>(&status);
        }
    }
}
