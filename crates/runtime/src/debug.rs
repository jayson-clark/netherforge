//! The debugger on a dev server (Kotlin's `Debugger`): breakpoints, stepping and variables in the project's scripts,
//! spoken as the Debug Adapter Protocol to the editor over the bridge's `dap` channel. The runtime is the debug
//! adapter; the host stops (`netherforge_luau::debug`), this answers the editor.
//!
//! It outlives sessions, like the profiler: each new Lua state is told the breakpoints before any script runs in it
//! (`Session::set_on_host`), and every tick's start hands it what changed ([`Debugger::sync`]).
//!
//! **Stopping freezes the tick.** The host stops on the server's main thread, inside whatever call into a script was
//! running, and asks [`HostHandler::stopped`], which doesn't return until the editor says to go on: nothing else on the
//! main thread runs meanwhile (no tick, no event, no request the bridge hands the main thread: those are refused while
//! paused, [`Debugger::refusal`]). The held thread waits on this debugger's own inbox instead: what the editor asks
//! that only the stopped stack can answer (the stack, scopes, variables) comes to it there, and once a second the
//! platform is told the server is alive (`PauseOps::hold`: the watchdog, and an action bar that keeps players'
//! connections from timing out). The time held is left out of the host's clock, so no time limit, cost or profile
//! counts it.
//!
//! DAP messages arrive on the bridge's thread, which never waits for the main thread: configuration (breakpoints,
//! break on errors, attach, detach, pause) is kept here under a lock and taken by the Lua state at its next `sync` (or
//! at once by a held one), and requests about a stop are queued for the held main thread and answered when it has.

use std::collections::{BTreeSet, HashMap, VecDeque};
use std::path::PathBuf;
use std::rc::Rc;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant};

use netherforge_luau::debug::{DebugConfig, DebugHandler, HandleView, Resume, Stopped};
use netherforge_luau::{Host, WeakHost};
use serde_json::{Value, json};

use crate::Shared;

/// The one thread scripts run on, as DAP names threads.
pub const THREAD: i64 = 1;

/// The exception filter that stops on uncaught script errors.
pub const ERRORS: &str = "errors";

/// How often the held main thread tells the platform the server is alive.
pub const HOLD_EVERY: Duration = Duration::from_millis(1000);

const NOT_STOPPED: &str = "the server isn't paused";

/// A DAP request, as it came.
#[derive(Clone)]
struct Request {
    seq: i64,
    command: String,
    arguments: Value,
}

/// What the held main thread is asked to do.
enum Command {
    /// Something only the stop can answer: `stackTrace`, `scopes`, `variables`.
    Ask(Request),
    /// The configuration changed while held: the Lua state takes it at once.
    Reconfigure,
    /// Go on.
    Resume(Resume),
}

#[derive(Default)]
struct State {
    attached: bool,
    breakpoints: HashMap<String, BTreeSet<u32>>,
    errors: bool,
    pause_wanted: bool,
    version: u64,
    /// Where the main thread is held, while it is.
    stopped: Option<String>,
    inbox: VecDeque<Command>,
    /// The Lua state configured last, and at which version.
    applied: Option<(usize, u64)>,
}

/// Sends one DAP message to the editor, from any thread.
pub type Send = Arc<dyn Fn(Value) + std::marker::Send + Sync>;

/// The debug adapter: shared by the bridge's thread (what the editor sends) and the main thread (the Lua state).
pub struct Debugger {
    state: Mutex<State>,
    signal: Condvar,
    send: Send,
    seq: AtomicI64,
    /// The project's folder, for an editor that names files by where they are on disk.
    project: Option<PathBuf>,
    /// Every package's folder by namespace, as the last load found them (`ProjectFiles::folders`).
    packages: Mutex<Option<PackageFolders>>,
    hold_every: Duration,
}

/// Each package's folder by namespace, which the project's loads keep up to date for another thread.
pub type PackageFolders = Arc<Mutex<Vec<(String, PathBuf)>>>;

impl Debugger {
    /// A debugger sending its messages with `send`, mapping absolute paths under `project` to project paths.
    pub fn new(send: Send, project: Option<PathBuf>) -> Arc<Debugger> {
        Debugger::with_hold(send, project, HOLD_EVERY)
    }

    /// `new`, telling the platform the server is alive every `hold_every` while held.
    pub fn with_hold(send: Send, project: Option<PathBuf>, hold_every: Duration) -> Arc<Debugger> {
        Arc::new(Debugger {
            state: Mutex::default(),
            packages: Mutex::default(),
            signal: Condvar::new(),
            send,
            seq: AtomicI64::new(0),
            project,
            hold_every,
        })
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, State> {
        self.state.lock().unwrap_or_else(|e| e.into_inner())
    }

    fn next_seq(&self) -> i64 {
        self.seq.fetch_add(1, Ordering::SeqCst) + 1
    }

    fn respond(&self, request: &Request, body: Option<Value>) {
        let mut message = json!({
            "seq": self.next_seq(),
            "type": "response",
            "request_seq": request.seq,
            "success": true,
            "command": request.command,
        });
        if let Some(body) = body {
            message["body"] = body;
        }
        (self.send)(message);
    }

    fn fail(&self, request: &Request, why: &str) {
        (self.send)(json!({
            "seq": self.next_seq(),
            "type": "response",
            "request_seq": request.seq,
            "success": false,
            "command": request.command,
            "message": why,
        }));
    }

    fn event(&self, event: &str, body: Option<Value>) {
        let mut message = json!({ "seq": self.next_seq(), "type": "event", "event": event });
        if let Some(body) = body {
            message["body"] = body;
        }
        (self.send)(message);
    }

    /// Under the lock: the configuration changed; a held Lua state takes it at once.
    fn configured(&self, state: &mut State) {
        state.version += 1;
        if state.stopped.is_some() {
            state.inbox.push_back(Command::Reconfigure);
            self.signal.notify_all();
        }
    }

    /// Has the held main thread go on; false when it isn't held.
    fn resume(&self, how: Resume) -> bool {
        let mut state = self.lock();
        if state.stopped.is_none() {
            return false;
        }
        state.inbox.push_back(Command::Resume(how));
        self.signal.notify_all();
        true
    }

    /// A source's path as a script's chunk is named: the project path (`modules/m/init.lua`) or package path
    /// (`library:modules/greetings/init.lua`), what stacks, errors and the editor's documents say. An editor that
    /// sees the file on disk says its absolute path: one under the project's folder is mapped to its project path.
    pub fn source_path(&self, path: &str) -> String {
        let slashed = path.replace('\\', "/");
        let slashed = slashed.strip_prefix("./").unwrap_or(&slashed).to_owned();
        let file = std::path::Path::new(&slashed);
        if !file.is_absolute() {
            return slashed;
        }
        // A package nested in another folder wins over the folder around it.
        let mut best: Option<(usize, String)> = None;
        let mut consider = |folder: &std::path::Path, namespace: Option<&str>| {
            let Ok(local) = file.strip_prefix(folder) else {
                return;
            };
            let local = local.to_string_lossy().replace('\\', "/");
            let depth = folder.components().count();
            if !local.is_empty() && best.as_ref().is_none_or(|(d, _)| depth > *d) {
                let path = match namespace {
                    Some(ns) => format!("{ns}:{local}"),
                    None => local,
                };
                best = Some((depth, path));
            }
        };
        if let Some(project) = &self.project {
            consider(project, None);
        }
        let packages = self
            .packages
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clone();
        if let Some(packages) = packages {
            for (namespace, folder) in packages.lock().unwrap_or_else(|e| e.into_inner()).iter() {
                consider(folder, Some(namespace));
            }
        }
        best.map_or(slashed, |(_, path)| path)
    }

    /// Maps files in packages' folders to their package paths, as `folders` says where they are.
    pub fn set_packages(&self, folders: PackageFolders) {
        *self.packages.lock().unwrap_or_else(|e| e.into_inner()) = Some(folders);
    }

    /// One request from the editor, on the bridge's thread.
    fn request(&self, request: Request) {
        let args = &request.arguments;
        match request.command.as_str() {
            "initialize" => {
                self.respond(
                    &request,
                    Some(json!({
                        "supportsConfigurationDoneRequest": true,
                        "exceptionBreakpointFilters": [{
                            "filter": ERRORS,
                            "label": "Script errors",
                            "description": "Pause where an error no pcall catches happens",
                            "default": false,
                        }],
                    })),
                );
                // DAP: the adapter says it's ready for breakpoints once it has answered `initialize`.
                self.event("initialized", None);
            }
            "attach" => {
                let mut state = self.lock();
                state.attached = true;
                self.configured(&mut state);
                drop(state);
                self.respond(&request, None);
            }
            "configurationDone" => self.respond(&request, None),
            "disconnect" => {
                bridge_detach(self);
                self.respond(&request, None);
            }
            "setBreakpoints" => {
                let Some(path) = args["source"]["path"].as_str() else {
                    return self.fail(&request, "setBreakpoints needs the source's path");
                };
                let path = self.source_path(path);
                let lines: Vec<u32> = match args["breakpoints"].as_array() {
                    Some(points) => points
                        .iter()
                        .filter_map(|b| b["line"].as_u64())
                        .map(|l| l as u32)
                        .collect(),
                    None => args["lines"]
                        .as_array()
                        .map(|l| {
                            l.iter()
                                .filter_map(Value::as_u64)
                                .map(|l| l as u32)
                                .collect()
                        })
                        .unwrap_or_default(),
                };
                {
                    let mut state = self.lock();
                    if lines.is_empty() {
                        state.breakpoints.remove(&path);
                    } else {
                        state
                            .breakpoints
                            .insert(path.clone(), lines.iter().copied().collect());
                    }
                    self.configured(&mut state);
                }
                let name = path.rsplit('/').next().unwrap_or(&path).to_owned();
                let breakpoints: Vec<Value> = lines
                    .iter()
                    .map(|line| {
                        json!({
                            "verified": true,
                            "line": line,
                            "source": { "path": path, "name": name },
                        })
                    })
                    .collect();
                self.respond(&request, Some(json!({ "breakpoints": breakpoints })));
            }
            "setExceptionBreakpoints" => {
                let on = args["filters"]
                    .as_array()
                    .is_some_and(|f| f.iter().any(|f| f == ERRORS));
                let mut state = self.lock();
                state.errors = on;
                self.configured(&mut state);
                drop(state);
                self.respond(&request, Some(json!({})));
            }
            "threads" => self.respond(
                &request,
                Some(json!({ "threads": [{ "id": THREAD, "name": "Server thread" }] })),
            ),
            "pause" => {
                let mut state = self.lock();
                if state.stopped.is_none() {
                    state.pause_wanted = true;
                    self.configured(&mut state);
                }
                drop(state);
                self.respond(&request, None);
            }
            "continue" => {
                self.resume(Resume::Continue);
                self.respond(&request, Some(json!({ "allThreadsContinued": true })));
            }
            "next" | "stepIn" | "stepOut" => {
                let how = match request.command.as_str() {
                    "next" => Resume::Next,
                    "stepIn" => Resume::StepIn,
                    _ => Resume::StepOut,
                };
                if self.resume(how) {
                    self.respond(&request, None);
                } else {
                    self.fail(&request, NOT_STOPPED);
                }
            }
            "stackTrace" | "scopes" | "variables" => {
                let mut state = self.lock();
                if state.stopped.is_none() {
                    drop(state);
                    return self.fail(&request, NOT_STOPPED);
                }
                state.inbox.push_back(Command::Ask(request));
                self.signal.notify_all();
            }
            other => self.fail(&request, &format!("the debugger doesn't do {other}")),
        }
    }

    /// Brings `host` up to date with what the editor configured: on the main thread, at the start of every tick and
    /// as soon as a session's Lua state exists (before any of its scripts run, `force`).
    pub fn sync(&self, host: &Host, force: bool) {
        let id = host.lua() as *const _ as usize;
        let (config, attached) = {
            let mut state = self.lock();
            let pause = std::mem::take(&mut state.pause_wanted);
            if !force && !pause && state.applied == Some((id, state.version)) {
                return;
            }
            state.applied = Some((id, state.version));
            (
                DebugConfig {
                    breakpoints: state.breakpoints.clone(),
                    errors: state.errors,
                    pause,
                },
                state.attached,
            )
        };
        if attached {
            host.debug_configure(config);
        } else {
            host.debug_detach();
        }
    }

    /// Why a request for the main thread is refused while the server is paused; none while it runs.
    pub fn refused(&self) -> Option<String> {
        self.lock().stopped.as_ref().map(|at| {
            format!(
                "the dev server is paused at a breakpoint ({at}); continue it in the editor first"
            )
        })
    }

    /// Whether the server is held at a stop now.
    pub fn paused(&self) -> bool {
        self.lock().stopped.is_some()
    }
}

/// Forgets the editor: no breakpoints, nothing to stop on, and a held server goes on.
fn bridge_detach(debugger: &Debugger) {
    let mut state = debugger.lock();
    state.attached = false;
    state.breakpoints.clear();
    state.errors = false;
    state.pause_wanted = false;
    state.version += 1;
    if state.stopped.is_some() {
        state.inbox.push_back(Command::Resume(Resume::Detach));
        debugger.signal.notify_all();
    }
}

impl crate::bridge::Debugger for Debugger {
    fn receive(&self, message: Value) {
        if message["type"] != "request" {
            return;
        }
        let (Some(seq), Some(command)) = (message["seq"].as_i64(), message["command"].as_str())
        else {
            return;
        };
        self.request(Request {
            seq,
            command: command.to_owned(),
            arguments: message.get("arguments").cloned().unwrap_or(Value::Null),
        });
    }

    fn refusal(&self) -> Option<String> {
        self.refused()
    }

    fn detach(&self) {
        bridge_detach(self);
    }
}

/// The debugger as one Lua state's host asks it (the main thread's half): it holds the thread at a stop and answers
/// what the editor asks of it, and describes handles.
pub struct HostHandler {
    pub debugger: Arc<Debugger>,
    pub shared: Rc<Shared>,
    pub host: WeakHost,
}

impl HostHandler {
    /// Installs the debugger in `host`, for the session whose services share `shared`, and tells it what to stop at.
    pub fn install(debugger: &Arc<Debugger>, host: &Host, shared: &Rc<Shared>) {
        host.set_debug_handler(Some(Rc::new(HostHandler {
            debugger: debugger.clone(),
            shared: shared.clone(),
            host: host.downgrade(),
        })));
        debugger.sync(host, true);
    }

    fn answer(&self, stop: &mut Stopped, request: &Request) {
        let d = &self.debugger;
        let args = &request.arguments;
        match request.command.as_str() {
            "stackTrace" => {
                let rows = stop.stack();
                let all: Vec<Value> = rows
                    .iter()
                    .enumerate()
                    .map(|(i, row)| {
                        let name = row.file.rsplit('/').next().unwrap_or(&row.file).to_owned();
                        json!({
                            "id": i + 1,
                            "name": row.name,
                            "source": { "path": row.file, "name": name },
                            "line": row.line,
                            "column": 1,
                        })
                    })
                    .collect();
                let from = args["startFrame"].as_u64().unwrap_or(0) as usize;
                let from = from.min(all.len());
                let levels = args["levels"]
                    .as_u64()
                    .filter(|l| *l > 0)
                    .map_or(all.len(), |l| l as usize);
                let frames: Vec<Value> = all.iter().skip(from).take(levels).cloned().collect();
                d.respond(
                    request,
                    Some(json!({ "stackFrames": frames, "totalFrames": all.len() })),
                );
            }
            "scopes" => {
                let frame = args["frameId"].as_u64().unwrap_or(0) as usize;
                match stop.scopes(frame) {
                    Ok(scopes) => {
                        let scopes: Vec<Value> = scopes
                            .into_iter()
                            .map(|(name, reference)| {
                                let mut scope = json!({
                                    "name": name,
                                    "variablesReference": reference,
                                    "expensive": false,
                                });
                                if name == "Locals" {
                                    scope["presentationHint"] = json!("locals");
                                }
                                scope
                            })
                            .collect();
                        d.respond(request, Some(json!({ "scopes": scopes })));
                    }
                    Err(why) => d.fail(request, &why),
                }
            }
            "variables" => {
                let reference = args["variablesReference"].as_i64().unwrap_or(0);
                match stop.variables(reference) {
                    Ok(rows) => {
                        let variables: Vec<Value> = rows
                            .into_iter()
                            .map(|v| {
                                json!({
                                    "name": v.name,
                                    "value": v.value,
                                    "type": v.kind,
                                    "variablesReference": v.reference,
                                })
                            })
                            .collect();
                        d.respond(request, Some(json!({ "variables": variables })));
                    }
                    Err(why) => d.fail(request, &why),
                }
            }
            _ => d.fail(request, NOT_STOPPED),
        }
    }

    /// The server is alive while held: the platform's watchdog and the players' connections.
    fn hold(&self, at: &str) {
        self.shared
            .platform
            .pause()
            .hold(format!("Paused by the NetherForge debugger at {at}"));
    }
}

impl DebugHandler for HostHandler {
    fn stopped(&self, stop: &mut Stopped) -> Resume {
        let d = &self.debugger;
        let at = format!("{}:{}", stop.file, stop.line);
        d.lock().stopped = Some(at.clone());
        let description = match stop.reason.as_str() {
            "breakpoint" => "Paused on breakpoint",
            "exception" => "Paused on script error",
            "pause" => "Paused",
            _ => "Paused after step",
        };
        let mut body = json!({
            "reason": stop.reason,
            "description": description,
            "threadId": THREAD,
            "allThreadsStopped": true,
        });
        if let Some(text) = &stop.text {
            body["text"] = json!(text);
        }
        d.event("stopped", Some(body));
        let mut next_hold = Instant::now();
        let resume = loop {
            if Instant::now() >= next_hold {
                self.hold(&at);
                next_hold = Instant::now() + d.hold_every;
            }
            let command = {
                let mut state = d.lock();
                loop {
                    if let Some(command) = state.inbox.pop_front() {
                        break Some(command);
                    }
                    let wait = next_hold.saturating_duration_since(Instant::now());
                    if wait.is_zero() {
                        break None;
                    }
                    state = d
                        .signal
                        .wait_timeout(state, wait)
                        .unwrap_or_else(|e| e.into_inner())
                        .0;
                }
            };
            match command {
                None => continue,
                Some(Command::Ask(request)) => self.answer(stop, &request),
                Some(Command::Reconfigure) => {
                    if let Some(host) = self.host.upgrade() {
                        d.sync(&host, true);
                    }
                }
                Some(Command::Resume(how)) => break how,
            }
        };
        let unanswered: Vec<Request> = {
            let mut state = d.lock();
            state.stopped = None;
            state
                .inbox
                .drain(..)
                .filter_map(|c| match c {
                    Command::Ask(r) => Some(r),
                    _ => None,
                })
                .collect()
        };
        for request in unanswered {
            d.fail(&request, NOT_STOPPED);
        }
        d.event(
            "continued",
            Some(json!({ "threadId": THREAD, "allThreadsContinued": true })),
        );
        resume
    }

    fn describe(&self, value: &mlua::AnyUserData) -> Option<HandleView> {
        let handle = value.borrow::<netherforge_api::handle::HandleData>().ok()?;
        let class = handle.class().to_owned();
        let keys: Vec<String> = handle
            .key()
            .iter()
            .map(|k| match k {
                netherforge_api::handle::KeyPart::Str(s) => s.clone(),
                netherforge_api::handle::KeyPart::Int(i) => i.to_string(),
            })
            .collect();
        drop(handle);
        Some(describe(&self.shared, &class, &keys))
    }
}

/// A handle as the editor shows it: `Player Steve` with where they are, `Centity tower 1a2b3c4d`, `Node top of tower
/// 1a2b3c4d`, `World world`, else its class and keys.
fn describe(shared: &Shared, class: &str, keys: &[String]) -> HandleView {
    let generic = || HandleView {
        summary: format!("{class}: {}", keys.join(", ")),
        class: class.to_owned(),
        facts: keys
            .iter()
            .enumerate()
            .map(|(i, k)| (format!("key {}", i + 1), k.clone()))
            .collect(),
    };
    let first = keys.first().cloned().unwrap_or_default();
    let position = |at: Option<netherforge_platform::Location>| -> Vec<(String, String)> {
        at.map(|at| {
            let round = |n: f64| (n * 100.0).floor() / 100.0;
            vec![
                ("world".to_owned(), at.world.clone()),
                (
                    "position".to_owned(),
                    format!("{}, {}, {}", round(at.x), round(at.y), round(at.z)),
                ),
            ]
        })
        .unwrap_or_default()
    };
    let uuid = || crate::events::parse_uuid(&first);
    match class {
        "Player" | "Bot" => {
            let Some(id) = uuid() else { return generic() };
            let players = shared.platform.players();
            let player = players.get(id);
            let mut facts: Vec<(String, String)> = player
                .iter()
                .map(|p| ("name".to_owned(), p.name.clone()))
                .collect();
            facts.push(("uuid".into(), first.clone()));
            facts.extend(position(players.location(id)));
            HandleView {
                summary: match &player {
                    Some(p) => format!("{class} {}", p.name),
                    None => format!("{class} (offline) {first}"),
                },
                class: class.to_owned(),
                facts,
            }
        }
        "Centity" => {
            let Some(id) = uuid() else { return generic() };
            let centities = shared.centities.borrow();
            match centities.get(id) {
                Some(instance) => {
                    let mut facts = vec![
                        ("centity".to_owned(), instance.centity().to_owned()),
                        ("uuid".to_owned(), first.clone()),
                    ];
                    facts.extend(position(Some(instance.anchor().clone())));
                    HandleView {
                        summary: format!(
                            "Centity {} {}",
                            instance.centity(),
                            &first[..8.min(first.len())]
                        ),
                        class: class.to_owned(),
                        facts,
                    }
                }
                None => HandleView {
                    summary: format!("Centity (gone) {first}"),
                    class: class.to_owned(),
                    facts: vec![("uuid".into(), first.clone())],
                },
            }
        }
        "Node" => {
            let node = keys.get(1).cloned().unwrap_or_default();
            let centity = uuid()
                .and_then(|id| {
                    shared
                        .centities
                        .borrow()
                        .get(id)
                        .map(|i| i.centity().to_owned())
                })
                .unwrap_or_else(|| "(gone)".into());
            HandleView {
                summary: format!("Node {node} of {centity} {}", &first[..8.min(first.len())]),
                class: class.to_owned(),
                facts: vec![
                    ("node".into(), node),
                    ("centity".into(), centity),
                    ("uuid".into(), first.clone()),
                ],
            }
        }
        "World" => HandleView {
            summary: format!("World {first}"),
            class: class.to_owned(),
            facts: vec![("name".into(), first.clone())],
        },
        _ => generic(),
    }
}
