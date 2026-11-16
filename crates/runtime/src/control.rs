//! The server's local control endpoint (Kotlin's `Control` and `LocalHttp`), for the tool that runs it (a container's
//! sidecar, a host's agent): its state, a stream of what changes, and draining, shutting down, saving and pausing
//! writes, making, loading and unloading worlds. Only on a Unix socket or on `127.0.0.1` with a token: never reachable
//! from elsewhere. Every action is logged with where it came from.
//!
//! What it reports is a snapshot the main thread makes every few ticks; actions are queued for the main thread, done
//! at its next tick and answered once done. Its health isn't: when the last tick ended is kept every tick and read by
//! the endpoint's own threads, so a main thread that's stuck (an endless script, a deadlock) shows as stalled.
//!
//! The HTTP is a very small HTTP/1.1: one request a connection (`Connection: close`), a body by its `Content-Length`
//! (at most 64 KiB).

use std::cell::RefCell;
use std::collections::{BTreeMap, BTreeSet, HashMap};
use std::io::{BufRead as _, BufReader, Read, Write};
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::sync::mpsc;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use netherforge_api::{ApiError, Pending, shapes};
use netherforge_platform::{Platform, Uuid};
use serde_json::{Value, json};

use crate::config::ControlConfig;
use crate::session::Session;

const TICKS_PER_SECOND: u64 = 20;
const SNAPSHOT_TICKS: u64 = 5;
const MAX_QUEUED: usize = 1000;
const PING_SECONDS: u64 = 15;
const ACT_SECONDS: u64 = 30;
/// How long a world's import may take, its files' copy included.
const LOAD_SECONDS: u64 = 120;
const DEFAULT_SHUTDOWN_SECONDS: u64 = 60;
const DEFAULT_PAUSE_SECONDS: u64 = 300;
const MAX_BODY: usize = 64 * 1024;
const MAX_LINE: usize = 8 * 1024;
const MAX_HEADERS: usize = 100;
const MAX_CONNECTIONS: usize = 16;

/// What the endpoint's threads and the main thread share.
struct Shared {
    /// The last snapshot (without the tick's health).
    state: Mutex<serde_json::Map<String, Value>>,
    /// When the last tick ended (Unix milliseconds; 0 before the first), and how long it took (its bits).
    last_tick: AtomicU64,
    tick_ms: AtomicU64,
    /// Each `/events` stream's queue.
    listeners: Mutex<Vec<mpsc::SyncSender<Option<String>>>>,
    /// Actions for the main thread, each with where its answer goes.
    actions: Mutex<Vec<(Action, mpsc::Sender<Answer>)>>,
    stopped: AtomicBool,
    stall_seconds: u64,
    token: Option<String>,
    token_file: String,
    /// Where it listens, as the log says it.
    where_: String,
    connections: AtomicUsize,
}

/// An action's answer: its JSON, or the status and why.
type Answer = Result<Value, (u16, String)>;

enum Action {
    Drain(bool),
    Shutdown {
        after_empty: bool,
        timeout: u64,
    },
    Save {
        world: Option<String>,
        pause: bool,
        timeout: u64,
    },
    Create {
        name: String,
        options: shapes::WorldCreateOptions,
    },
    Load {
        name: String,
        folder: Option<String>,
        terrain: Option<String>,
    },
    Unload {
        name: String,
        save: bool,
        delete: bool,
        move_to: Option<String>,
    },
    Resume {
        world: Option<String>,
    },
}

impl Action {
    fn what(&self) -> String {
        match self {
            Action::Drain(on) => format!("drain{}", if *on { "" } else { " off" }),
            Action::Shutdown {
                after_empty,
                timeout,
            } => format!(
                "shutdown{}, {timeout} seconds",
                if *after_empty { " after empty" } else { "" }
            ),
            Action::Save {
                world,
                pause,
                timeout,
            } => format!(
                "save{}{}",
                world
                    .as_ref()
                    .map(|w| format!(" world {w}"))
                    .unwrap_or_default(),
                if *pause {
                    format!(
                        ", pausing {} for up to {timeout} seconds",
                        if world.is_none() {
                            "writes"
                        } else {
                            "its writes"
                        }
                    )
                } else {
                    String::new()
                }
            ),
            Action::Create { name, .. } => format!("create world {name}"),
            Action::Load { name, folder, .. } => format!(
                "load world {name}{}",
                folder
                    .as_ref()
                    .map(|f| format!(" from {f}"))
                    .unwrap_or_default()
            ),
            Action::Unload { name, .. } => format!("unload world {name}"),
            Action::Resume { world: None } => "resume writes".into(),
            Action::Resume { world: Some(w) } => format!("resume world {w}'s writes"),
        }
    }
}

/// A player online: their name and the world they're in.
#[derive(Clone, PartialEq)]
struct PlayerView {
    name: String,
    world: Option<String>,
}

#[derive(Clone, PartialEq)]
struct Snapshot {
    state: String,
    running: bool,
    refused: Option<(Option<String>, String)>,
    players: BTreeMap<Uuid, PlayerView>,
    worlds: BTreeSet<String>,
    paused: bool,
    paused_worlds: BTreeSet<String>,
}

/// The main thread's half.
#[derive(Default)]
struct Main {
    last: Option<Snapshot>,
    /// Every world's writes paused: until this tick at the latest.
    paused_until: Option<u64>,
    /// Worlds whose writes are paused on their own, each until its tick.
    world_pauses: HashMap<String, u64>,
    /// When each loaded world last had no players, by tick.
    world_empty_since: HashMap<String, u64>,
    /// When the server last had players, by tick.
    empty_since: Option<u64>,
    /// Imports still copying a world's files, and where their answers go.
    loading: Vec<(
        String,
        Pending<netherforge_api::handles::World>,
        mpsc::Sender<Answer>,
    )>,
    ticks: u64,
}

pub struct ServerControl {
    shared: Arc<Shared>,
    main: RefCell<Main>,
    socket: Option<std::path::PathBuf>,
    port: Option<u16>,
}

fn now_millis() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_or(0, |d| d.as_millis() as u64)
}

enum Listener {
    Tcp(std::net::TcpListener),
    #[cfg(unix)]
    Unix(std::os::unix::net::UnixListener),
}

trait Stream: Read + Write + Send {}
impl<T: Read + Write + Send> Stream for T {}

impl ServerControl {
    /// Listens where `config` says (nowhere: off). A place it can't listen on is said in the log.
    pub fn start(config: &ControlConfig, platform: &dyn Platform) -> Option<ServerControl> {
        if config.socket.is_none() && config.port.is_none() {
            return None;
        }
        let failed = |at: String, e: String| {
            platform.log().error(
                format!("NetherForge's control endpoint couldn't listen on {at}"),
                Some(netherforge_platform::PlatformError {
                    message: e,
                    cause: None,
                }),
            );
        };
        let (listener, where_, port) = match &config.socket {
            Some(path) => {
                #[cfg(unix)]
                {
                    if let Some(dir) = path.parent() {
                        let _ = std::fs::create_dir_all(dir);
                    }
                    let _ = std::fs::remove_file(path);
                    match std::os::unix::net::UnixListener::bind(path) {
                        Ok(l) => {
                            use std::os::unix::fs::PermissionsExt as _;
                            // Who can open the file may use it: the server's own user.
                            let _ = std::fs::set_permissions(
                                path,
                                std::fs::Permissions::from_mode(0o600),
                            );
                            (
                                Listener::Unix(l),
                                format!("the socket {}", path.display()),
                                None,
                            )
                        }
                        Err(e) => {
                            failed(path.display().to_string(), e.to_string());
                            return None;
                        }
                    }
                }
                #[cfg(not(unix))]
                {
                    failed(
                        path.display().to_string(),
                        "Unix sockets need a Unix server: use control.port".into(),
                    );
                    return None;
                }
            }
            None => {
                let port = config.port.expect("a port");
                match std::net::TcpListener::bind(("127.0.0.1", port)) {
                    Ok(l) => {
                        let bound = l.local_addr().map_or(port, |a| a.port());
                        (Listener::Tcp(l), format!("127.0.0.1:{bound}"), Some(bound))
                    }
                    Err(e) => {
                        failed(format!("127.0.0.1:{port}"), e.to_string());
                        return None;
                    }
                }
            }
        };
        let token = match &config.socket {
            Some(_) => None,
            None => match read_token(&config.token_file, platform) {
                Ok(token) => Some(token),
                Err(why) => {
                    failed(where_.clone(), why);
                    return None;
                }
            },
        };
        let shared = Arc::new(Shared {
            state: Mutex::default(),
            last_tick: AtomicU64::new(0),
            tick_ms: AtomicU64::new(0),
            listeners: Mutex::default(),
            actions: Mutex::default(),
            stopped: AtomicBool::new(false),
            stall_seconds: config.stall_seconds.max(1) as u64,
            token,
            token_file: config.token_file.display().to_string(),
            where_: where_.clone(),
            connections: AtomicUsize::new(0),
        });
        let accepting = shared.clone();
        std::thread::Builder::new()
            .name("NetherForge control".into())
            .spawn(move || accept(listener, accepting))
            .ok()?;
        let watching = shared.clone();
        std::thread::Builder::new()
            .name("NetherForge control watchdog".into())
            .spawn(move || watch(watching))
            .ok()?;
        platform.log().info(format!(
            "NetherForge's control endpoint is listening on {where_}"
        ));
        Some(ServerControl {
            shared,
            main: RefCell::default(),
            socket: config.socket.clone(),
            port,
        })
    }

    /// The TCP port it listens on, when it's on one.
    pub fn port(&self) -> Option<u16> {
        self.port
    }

    /// Stops listening: paused writes resume, streams end.
    pub fn stop(&self, platform: &dyn Platform) {
        {
            let mut main = self.main.borrow_mut();
            let worlds: Vec<String> = main.world_pauses.keys().cloned().collect();
            for world in worlds {
                resume_world(&mut main, platform, &world, None);
            }
            resume(&mut main, platform, Some("the server is stopping"));
        }
        self.shared.stopped.store(true, Ordering::Release);
        for listener in self
            .shared
            .listeners
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .drain(..)
        {
            let _ = listener.try_send(None);
        }
        // A connection wakes the thread waiting for one.
        match (&self.socket, self.port) {
            #[cfg(unix)]
            (Some(path), _) => {
                let _ = std::os::unix::net::UnixStream::connect(path);
                let _ = std::fs::remove_file(path);
            }
            (_, Some(port)) => {
                let _ = std::net::TcpStream::connect_timeout(
                    &std::net::SocketAddr::from(([127, 0, 0, 1], port)),
                    Duration::from_millis(500),
                );
            }
            _ => {}
        }
    }

    /// The main thread, each tick: the tick's health kept, actions done, and every few ticks a new snapshot, what
    /// changed told to listeners, and an expired pause ended.
    pub fn tick(&self, tick: u64, session: Option<&Session>, platform: &dyn Platform) {
        let ms = platform.performance().last_tick_milliseconds();
        self.shared.tick_ms.store(ms.to_bits(), Ordering::Relaxed);
        self.shared.last_tick.store(now_millis(), Ordering::Relaxed);
        self.main.borrow_mut().ticks = tick;
        let actions = std::mem::take(
            &mut *self
                .shared
                .actions
                .lock()
                .unwrap_or_else(|e| e.into_inner()),
        );
        let acted = !actions.is_empty();
        for (action, reply) in actions {
            platform.log().info(format!(
                "Control endpoint ({}): {}",
                self.shared.where_,
                action.what()
            ));
            if let Some(answer) = self.act(action, session, platform, &reply) {
                let _ = reply.send(answer);
            }
        }
        self.poll_loading();
        let first = self.main.borrow().last.is_none();
        if acted || first || tick.is_multiple_of(SNAPSHOT_TICKS) {
            self.snapshot(tick, session, platform, !acted);
        }
    }

    fn poll_loading(&self) {
        let mut main = self.main.borrow_mut();
        let waker = std::task::Waker::noop();
        let mut cx = std::task::Context::from_waker(waker);
        main.loading.retain_mut(
            |(name, future, reply)| match future.as_mut().poll(&mut cx) {
                std::task::Poll::Pending => true,
                std::task::Poll::Ready(Ok(_)) => {
                    let _ = reply.send(Ok(json!({ "world": name })));
                    false
                }
                std::task::Poll::Ready(Err(ApiError(why))) => {
                    let _ = reply.send(Err((409, why)));
                    false
                }
            },
        );
    }

    /// One action, on the main thread: its answer, or none when it's answered later (an import copying files).
    fn act(
        &self,
        action: Action,
        session: Option<&Session>,
        platform: &dyn Platform,
        reply: &mpsc::Sender<Answer>,
    ) -> Option<Answer> {
        let refused = |why: &str| Some(Err((409, why.to_owned())));
        let state = |session: Option<&Session>| json!({ "state": lifecycle(session) });
        match action {
            Action::Drain(on) => {
                let link = session.and_then(|s| s.shared().network.borrow().link());
                match link {
                    Some(link) => link.set_draining(on),
                    None => return refused("this server isn't part of a network"),
                }
                Some(Ok(state(session)))
            }
            Action::Shutdown {
                after_empty,
                timeout,
            } => {
                let Some(session) = session else {
                    platform.shutdown_server("NetherForge: the control endpoint's shutdown".into());
                    return Some(Ok(json!({ "state": "stopping" })));
                };
                let has_link = session.shared().network.borrow().link().is_some();
                match session.room() {
                    Some(room) if has_link => crate::network::service::NetworkContext {
                        shared: session.shared().clone(),
                        scripts: room.scripts().clone(),
                    }
                    .shutdown(after_empty, timeout),
                    _ => {
                        session.shared().network.borrow_mut().stop_server =
                            Some("NetherForge: the control endpoint's shutdown".into());
                    }
                }
                Some(Ok(state(Some(session))))
            }
            Action::Save {
                world,
                pause,
                timeout,
            } => {
                let loaded = platform.worlds().names();
                if let Some(w) = &world
                    && !loaded.contains(w)
                {
                    return Some(Err((404, format!("no world \"{w}\" is loaded"))));
                }
                let worlds = match &world {
                    Some(w) => vec![w.clone()],
                    None => loaded,
                };
                for each in &worlds {
                    platform.world_manager().save_files(each.clone());
                }
                if pause {
                    let mut main = self.main.borrow_mut();
                    for each in &worlds {
                        platform.world_manager().set_saving(each.clone(), false);
                    }
                    let until = main.ticks + timeout * TICKS_PER_SECOND;
                    match &world {
                        None => main.paused_until = Some(until),
                        Some(w) => {
                            main.world_pauses.insert(w.clone(), until);
                        }
                    }
                }
                let mut out = json!({ "saved": worlds, "writes_paused": pause });
                if let Some(w) = world {
                    out["world"] = json!(w);
                }
                Some(Ok(out))
            }
            Action::Create { name, options } => {
                let Some(context) = managed(session) else {
                    return refused("the project isn't running");
                };
                match context.create(&name, Some(options)) {
                    Ok(Some(_)) => Some(Ok(json!({ "world": name }))),
                    Ok(None) => refused(&format!("the server couldn't make world {name}")),
                    Err(ApiError(why)) if why.contains("isn't one of the project's") => {
                        Some(Err((400, why)))
                    }
                    Err(ApiError(why)) => refused(&why),
                }
            }
            Action::Load {
                name,
                folder,
                terrain,
            } => {
                let Some(context) = managed(session) else {
                    return refused("the project isn't running");
                };
                match folder {
                    Some(folder) => match context.import(&folder, &name, terrain) {
                        Ok(pending) => {
                            self.main
                                .borrow_mut()
                                .loading
                                .push((name, pending, reply.clone()));
                            None
                        }
                        Err(ApiError(why)) => refused(&why),
                    },
                    None => match context.load(&name) {
                        Ok(Some(_)) => Some(Ok(json!({ "world": name }))),
                        Ok(None) => Some(Err((
                            404,
                            format!("the server has no world saved as {name}"),
                        ))),
                        Err(ApiError(why)) => refused(&why),
                    },
                }
            }
            Action::Unload {
                name,
                save,
                delete,
                move_to,
            } => {
                let Some(context) = managed(session) else {
                    return refused("the project isn't running");
                };
                {
                    // Its own pause ends, so it's saved as an unload saves it.
                    let mut main = self.main.borrow_mut();
                    if main.world_pauses.remove(&name).is_some() && main.paused_until.is_none() {
                        platform.world_manager().set_saving(name.clone(), true);
                    }
                }
                match context.unload(&name, save, delete, move_to) {
                    Ok(true) => Some(Ok(json!({ "world": name }))),
                    Ok(false) => refused(&format!("{name} had gone, or the server kept it")),
                    Err(ApiError(why)) => refused(&why),
                }
            }
            Action::Resume { world } => {
                let mut main = self.main.borrow_mut();
                // Whether writes are still paused where it asked: worlds paused on their own, or the whole server's.
                let paused = match &world {
                    None => {
                        resume(&mut main, platform, None);
                        !main.world_pauses.is_empty()
                    }
                    Some(w) => {
                        if !platform.worlds().names().contains(w) {
                            return Some(Err((404, format!("no world \"{w}\" is loaded"))));
                        }
                        resume_world(&mut main, platform, w, None);
                        main.paused_until.is_some()
                    }
                };
                let mut out = json!({ "writes_paused": paused });
                if let Some(w) = world {
                    out["world"] = json!(w);
                }
                Some(Ok(out))
            }
        }
    }

    fn snapshot(&self, tick: u64, session: Option<&Session>, platform: &dyn Platform, tell: bool) {
        let mut main = self.main.borrow_mut();
        let players: BTreeMap<Uuid, PlayerView> = platform
            .players()
            .online()
            .into_iter()
            .map(|p| {
                let world = platform.players().location(p.uuid).map(|l| l.world);
                (
                    p.uuid,
                    PlayerView {
                        name: p.name,
                        world,
                    },
                )
            })
            .collect();
        main.empty_since = if players.is_empty() {
            main.empty_since.or(Some(tick))
        } else {
            None
        };
        let worlds: BTreeSet<String> = platform.worlds().names().into_iter().collect();
        let occupied: BTreeSet<&String> =
            players.values().filter_map(|p| p.world.as_ref()).collect();
        main.world_empty_since.retain(|w, _| worlds.contains(w));
        for world in &worlds {
            if occupied.contains(world) {
                main.world_empty_since.remove(world);
            } else {
                main.world_empty_since.entry(world.clone()).or_insert(tick);
            }
        }
        // A world unloaded while paused: its pause ends with it.
        main.world_pauses.retain(|w, _| worlds.contains(w));
        let expired: Vec<String> = main
            .world_pauses
            .iter()
            .filter(|(_, until)| tick >= **until)
            .map(|(w, _)| w.clone())
            .collect();
        for world in expired {
            resume_world(&mut main, platform, &world, Some("its timeout ran out"));
        }
        if let Some(until) = main.paused_until {
            if tick >= until {
                resume(&mut main, platform, Some("its timeout ran out"));
            } else {
                // A world loaded while writes are paused is paused too.
                let before: BTreeSet<String> = main
                    .last
                    .as_ref()
                    .map(|l| l.worlds.clone())
                    .unwrap_or_default();
                for world in worlds.difference(&before) {
                    platform.world_manager().set_saving(world.clone(), false);
                }
            }
        }
        let now = Snapshot {
            state: lifecycle(session),
            running: session.is_some_and(Session::running),
            refused: session.and_then(|s| s.refusal()).map(|r| {
                (
                    r.problem.as_ref().and_then(|p| p.code.clone()),
                    r.message.clone(),
                )
            }),
            players,
            worlds,
            paused: main.paused_until.is_some(),
            paused_worlds: main.world_pauses.keys().cloned().collect(),
        };
        let state = state_of(&now, &main, tick, session);
        *self.shared.state.lock().unwrap_or_else(|e| e.into_inner()) = state;
        let before = main.last.replace(now.clone());
        let (Some(before), true) = (before, tell) else {
            return;
        };
        let shared = &self.shared;
        if before.state != now.state
            || before.running != now.running
            || before.refused != now.refused
        {
            let mut data = json!({ "state": now.state, "previous": before.state });
            running(&mut data, &now);
            told(shared, "state", &data);
        }
        let player = |uuid: &Uuid, view: &PlayerView| {
            let mut data = json!({ "player": view.name, "uuid": crate::events::uuid_text(uuid) });
            if let Some(w) = &view.world {
                data["world"] = json!(w);
            }
            data
        };
        for (uuid, view) in &now.players {
            match before.players.get(uuid) {
                None => told(shared, "player_join", &player(uuid, view)),
                Some(was) => {
                    if let (Some(from), Some(to)) = (&was.world, &view.world)
                        && from != to
                    {
                        told(
                            shared,
                            "player_world",
                            &json!({ "player": view.name, "uuid": crate::events::uuid_text(uuid), "from": from, "to": to }),
                        );
                    }
                }
            }
        }
        for (uuid, view) in &before.players {
            if !now.players.contains_key(uuid) {
                told(shared, "player_quit", &player(uuid, view));
            }
        }
        for world in now.worlds.difference(&before.worlds) {
            told(shared, "world_load", &json!({ "world": world }));
        }
        for world in before.worlds.difference(&now.worlds) {
            told(shared, "world_unload", &json!({ "world": world }));
        }
        if before.paused != now.paused {
            told(shared, "writes", &json!({ "paused": now.paused }));
        }
        for world in now
            .paused_worlds
            .symmetric_difference(&before.paused_worlds)
        {
            told(
                shared,
                "writes",
                &json!({ "world": world, "paused": now.paused_worlds.contains(world) }),
            );
        }
    }
}

fn managed(session: Option<&Session>) -> Option<crate::managed_worlds::ManagedContext> {
    let session = session?;
    let room = session.room()?;
    Some(crate::managed_worlds::ManagedContext {
        shared: session.shared().clone(),
        scripts: room.scripts().clone(),
    })
}

/// The server's state in its network: `ready` for a server in none.
fn lifecycle(session: Option<&Session>) -> String {
    session
        .and_then(|s| s.shared().network.borrow().link())
        .map_or("ready", |link| link.lifecycle().word())
        .to_owned()
}

/// Every world's pause ends (said in the log when not asked: `why`); worlds paused on their own stay.
fn resume(main: &mut Main, platform: &dyn Platform, why: Option<&str>) {
    if main.paused_until.take().is_none() {
        return;
    }
    for world in platform.worlds().names() {
        if !main.world_pauses.contains_key(&world) {
            platform.world_manager().set_saving(world, true);
        }
    }
    if let Some(why) = why {
        platform
            .log()
            .info(format!("Control endpoint: writes resume: {why}"));
    }
}

/// `world`'s own pause ends; its writes go on unless the whole server's are paused.
fn resume_world(main: &mut Main, platform: &dyn Platform, world: &str, why: Option<&str>) {
    if main.world_pauses.remove(world).is_none() {
        return;
    }
    if main.paused_until.is_none() {
        platform.world_manager().set_saving(world.to_owned(), true);
    }
    if let Some(why) = why {
        platform.log().info(format!(
            "Control endpoint: world {world}'s writes resume: {why}"
        ));
    }
}

fn running(data: &mut Value, now: &Snapshot) {
    data["running"] = json!(now.running);
    if let Some((code, message)) = &now.refused {
        let mut refused = json!({ "message": message });
        if let Some(code) = code {
            refused["code"] = json!(code);
        }
        data["refused"] = refused;
    }
}

fn state_of(
    now: &Snapshot,
    main: &Main,
    tick: u64,
    session: Option<&Session>,
) -> serde_json::Map<String, Value> {
    let mut out = json!({
        "state": now.state,
        "player_count": now.players.len(),
    });
    let mut players: Vec<(&Uuid, &PlayerView)> = now.players.iter().collect();
    players.sort_by_key(|(_, v)| v.name.to_lowercase());
    out["players"] = players
        .iter()
        .map(|(uuid, view)| {
            let mut p = json!({ "uuid": crate::events::uuid_text(uuid), "name": view.name });
            if let Some(w) = &view.world {
                p["world"] = json!(w);
            }
            p
        })
        .collect();
    let mut names: Vec<&str> = now.players.values().map(|p| p.name.as_str()).collect();
    names.sort_unstable();
    out["player_names"] = json!(names);
    if let Some(since) = main.empty_since {
        out["empty_seconds"] = json!((tick - since) / TICKS_PER_SECOND);
    }
    let link = session.and_then(|s| s.shared().network.borrow().link());
    out["idle"] = json!(link.as_ref().is_some_and(|l| l.state.borrow().idle));
    if let Some(link) = &link {
        if let Some(ms) = link.state.borrow().startup_millis {
            out["startup_millis"] = json!(ms);
        }
        if let Some(name) = link.name() {
            out["server"] = json!(name);
        }
        if let Some(group) = link.group() {
            out["group"] = json!(group);
        }
    }
    if let Some(manifest) = session.and_then(|s| s.project().manifest.as_ref()) {
        out["project"] = json!(manifest.name);
        out["version"] = json!(manifest.version);
    }
    running(&mut out, now);
    let mut counts: HashMap<&str, usize> = HashMap::new();
    for world in now.players.values().filter_map(|p| p.world.as_deref()) {
        *counts.entry(world).or_default() += 1;
    }
    out["worlds"] = now
        .worlds
        .iter()
        .map(|w| {
            let mut world =
                json!({ "name": w, "players": counts.get(w.as_str()).copied().unwrap_or(0) });
            if let Some(since) = main.world_empty_since.get(w) {
                world["empty_seconds"] = json!((tick - since) / TICKS_PER_SECOND);
            }
            if now.paused || now.paused_worlds.contains(w) {
                world["writes_paused"] = json!(true);
            }
            world
        })
        .collect();
    out["writes_paused"] = json!(now.paused || !now.paused_worlds.is_empty());
    let paused: Vec<&String> = if now.paused {
        now.worlds.iter().collect()
    } else {
        now.paused_worlds.iter().collect()
    };
    out["paused_worlds"] = json!(paused);
    match out {
        Value::Object(map) => map,
        _ => serde_json::Map::new(),
    }
}

fn told(shared: &Shared, event: &str, data: &Value) {
    let text = format!("event: {event}\ndata: {data}\n\n");
    shared
        .listeners
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .retain(|l| l.try_send(Some(text.clone())).is_ok());
}

fn ticking(shared: &Shared) -> bool {
    let last = shared.last_tick.load(Ordering::Relaxed);
    last != 0 && now_millis().saturating_sub(last) < shared.stall_seconds * 1000
}

/// The watchdog's thread, every second: `stalled` once ticks stop (after the first), and back to ticking.
fn watch(shared: Arc<Shared>) {
    let mut stalled = false;
    while !shared.stopped.load(Ordering::Acquire) {
        std::thread::sleep(Duration::from_secs(1));
        let last = shared.last_tick.load(Ordering::Relaxed);
        if last == 0 {
            continue;
        }
        let now = ticking(&shared);
        if !now && !stalled {
            eprintln!(
                "NetherForge: no tick has completed for {} seconds; the server is stalled",
                shared.stall_seconds
            );
            told(
                &shared,
                "stalled",
                &json!({ "last_tick": last, "seconds": now_millis().saturating_sub(last) / 1000 }),
            );
        }
        stalled = !now;
    }
}

/// The snapshot, with the tick's health as it is now (the snapshot may be old if ticks stopped).
fn state_now(shared: &Shared) -> Value {
    let mut state = shared
        .state
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .clone();
    let last = shared.last_tick.load(Ordering::Relaxed);
    if last != 0 {
        state.insert("last_tick".into(), json!(last));
    }
    let ms = f64::from_bits(shared.tick_ms.load(Ordering::Relaxed));
    state.insert("tick_ms".into(), json!((ms * 100.0).round() / 100.0));
    state.insert("stalled".into(), json!(last != 0 && !ticking(shared)));
    Value::Object(state)
}

fn accept(listener: Listener, shared: Arc<Shared>) {
    let next = || -> Option<Box<dyn Stream>> {
        match &listener {
            Listener::Tcp(l) => l.accept().ok().map(|(s, _)| Box::new(s) as Box<dyn Stream>),
            #[cfg(unix)]
            Listener::Unix(l) => l.accept().ok().map(|(s, _)| Box::new(s) as Box<dyn Stream>),
        }
    };
    while !shared.stopped.load(Ordering::Acquire) {
        let Some(stream) = next() else { continue };
        if shared.stopped.load(Ordering::Acquire) {
            break;
        }
        if shared.connections.fetch_add(1, Ordering::AcqRel) >= MAX_CONNECTIONS {
            shared.connections.fetch_sub(1, Ordering::AcqRel);
            continue;
        }
        let serving = shared.clone();
        let spawned = std::thread::Builder::new()
            .name("NetherForge control connection".into())
            .spawn(move || {
                serve(stream, &serving);
                serving.connections.fetch_sub(1, Ordering::AcqRel);
            });
        if spawned.is_err() {
            shared.connections.fetch_sub(1, Ordering::AcqRel);
        }
    }
}

struct Request {
    method: String,
    path: String,
    headers: HashMap<String, String>,
    body: String,
}

fn line(input: &mut impl std::io::BufRead) -> Result<String, Option<String>> {
    let mut bytes = Vec::new();
    let read = input
        .take(MAX_LINE as u64 + 1)
        .read_until(b'\n', &mut bytes)
        .map_err(|_| None)?;
    if read == 0 {
        return Err(None);
    }
    if bytes.len() > MAX_LINE {
        return Err(Some(format!("a line is over {MAX_LINE} bytes")));
    }
    while bytes.last().is_some_and(|b| *b == b'\n' || *b == b'\r') {
        bytes.pop();
    }
    Ok(String::from_utf8_lossy(&bytes).into_owned())
}

/// The request, or why it isn't one (none: the connection ended).
fn read(input: &mut impl std::io::BufRead) -> Result<Request, Option<String>> {
    let head = line(input)?;
    let parts: Vec<&str> = head.split(' ').collect();
    if parts.len() != 3 || !parts[2].starts_with("HTTP/1.") {
        return Err(Some("not an HTTP request".into()));
    }
    let mut headers = HashMap::new();
    loop {
        let line = line(input)?;
        if line.is_empty() {
            break;
        }
        if headers.len() >= MAX_HEADERS {
            return Err(Some("too many headers".into()));
        }
        if let Some((k, v)) = line.split_once(':') {
            headers.insert(k.trim().to_lowercase(), v.trim().to_owned());
        }
    }
    let length: usize = headers
        .get("content-length")
        .and_then(|l| l.parse().ok())
        .unwrap_or(0);
    if length > MAX_BODY {
        return Err(Some(format!("the body is over {MAX_BODY} bytes")));
    }
    let mut body = vec![0; length];
    input.read_exact(&mut body).map_err(|_| None)?;
    let target = parts[1];
    let path = target.split('?').next().unwrap_or(target);
    let path = url::form_urlencoded::parse(format!("p={}", path.replace('+', "%2B")).as_bytes())
        .next()
        .map_or_else(|| path.to_owned(), |(_, v)| v.into_owned());
    Ok(Request {
        method: parts[0].to_owned(),
        path,
        headers,
        body: String::from_utf8_lossy(&body).into_owned(),
    })
}

fn reason(status: u16) -> &'static str {
    match status {
        200 => "OK",
        400 => "Bad Request",
        401 => "Unauthorized",
        404 => "Not Found",
        409 => "Conflict",
        503 => "Service Unavailable",
        _ => "Internal Server Error",
    }
}

fn send(out: &mut dyn Write, status: u16, json: &Value) {
    let body = json.to_string();
    let _ = write!(
        out,
        "HTTP/1.1 {status} {}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
        reason(status),
        body.len()
    );
    let _ = out.flush();
}

fn error(out: &mut dyn Write, status: u16, why: &str) {
    send(out, status, &json!({ "error": why }));
}

fn serve(stream: Box<dyn Stream>, shared: &Arc<Shared>) {
    let mut reader = BufReader::new(stream);
    let request = match read(&mut reader) {
        Ok(r) => r,
        Err(None) => return,
        Err(Some(why)) => {
            error(reader.get_mut(), 400, &why);
            return;
        }
    };
    let out = reader.get_mut();
    route(&request, out, shared);
}

/// `action` done on the main thread at its next tick: its answer, or why there's none.
fn act(shared: &Shared, action: Action, seconds: u64) -> Answer {
    let (send, receive) = mpsc::channel();
    shared
        .actions
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .push((action, send));
    match receive.recv_timeout(Duration::from_secs(seconds)) {
        Ok(answer) => answer,
        Err(_) => Err((
            409,
            format!("the server didn't do it within {seconds} seconds"),
        )),
    }
}

fn route(request: &Request, out: &mut dyn Write, shared: &Arc<Shared>) {
    // Health is for probes, which may send no token, and says only whether the server ticks.
    if request.method == "GET" && request.path == "/healthz" {
        if ticking(shared) {
            send(out, 200, &json!({ "healthy": true }));
        } else {
            let why = if shared.last_tick.load(Ordering::Relaxed) == 0 {
                "starting"
            } else {
                "stalled"
            };
            send(out, 503, &json!({ "healthy": false, "why": why }));
        }
        return;
    }
    if let Some(expected) = &shared.token {
        let given = request
            .headers
            .get("authorization")
            .map(|h| h.strip_prefix("Bearer ").unwrap_or(h).trim().to_owned())
            .unwrap_or_default();
        let same = given.len() == expected.len()
            && given
                .bytes()
                .zip(expected.bytes())
                .fold(0u8, |d, (a, b)| d | (a ^ b))
                == 0;
        if !same {
            error(
                out,
                401,
                &format!(
                    "a request needs Authorization: Bearer <the token in {}>",
                    shared.token_file
                ),
            );
            return;
        }
    }
    let body: serde_json::Map<String, Value> = if request.body.trim().is_empty() {
        serde_json::Map::new()
    } else {
        match serde_json::from_str(&request.body) {
            Ok(Value::Object(map)) => map,
            _ => {
                error(out, 400, "the body isn't a JSON object");
                return;
            }
        }
    };
    let flag = |key: &str| body.get(key).and_then(Value::as_bool);
    let number = |key: &str| body.get(key).and_then(Value::as_f64);
    let text = |key: &str| body.get(key).and_then(Value::as_str).map(str::to_owned);
    let seconds = |key: &str, default: u64| {
        number(key)
            .filter(|n| *n >= 1.0)
            .map_or(default, |n| n as u64)
    };
    let answer = match (request.method.as_str(), request.path.as_str()) {
        ("GET", "/state") => Ok(state_now(shared)),
        ("GET", "/events") => {
            events(out, shared);
            return;
        }
        ("POST", "/drain") => act(
            shared,
            Action::Drain(flag("draining").unwrap_or(true)),
            ACT_SECONDS,
        ),
        ("POST", "/shutdown") => act(
            shared,
            Action::Shutdown {
                after_empty: flag("after_empty").unwrap_or(false),
                timeout: seconds("timeout", DEFAULT_SHUTDOWN_SECONDS),
            },
            ACT_SECONDS,
        ),
        ("POST", "/save") => act(
            shared,
            Action::Save {
                world: text("world"),
                pause: flag("pause").unwrap_or(false),
                timeout: seconds("timeout", DEFAULT_PAUSE_SECONDS),
            },
            ACT_SECONDS,
        ),
        ("POST", "/worlds/create") => match (text("name"), create_options(&body)) {
            (None, _) => Err((400, "a create names its world".into())),
            (_, Err(why)) => Err((400, why)),
            (Some(name), Ok(options)) => act(shared, Action::Create { name, options }, ACT_SECONDS),
        },
        ("POST", "/worlds/load") => match text("name") {
            None => Err((400, "a load names its world".into())),
            Some(name) => act(
                shared,
                Action::Load {
                    name,
                    folder: text("folder"),
                    terrain: text("terrain"),
                },
                LOAD_SECONDS,
            ),
        },
        ("POST", "/worlds/unload") => match text("name") {
            None => Err((400, "an unload names its world".into())),
            Some(name) => act(
                shared,
                Action::Unload {
                    name,
                    save: flag("save").unwrap_or(true),
                    delete: flag("delete").unwrap_or(false),
                    move_to: text("move_players_to"),
                },
                ACT_SECONDS,
            ),
        },
        ("POST", "/resume") => act(
            shared,
            Action::Resume {
                world: text("world"),
            },
            ACT_SECONDS,
        ),
        (method, path) => Err((404, format!("there's no {method} {path}"))),
    };
    match answer {
        Ok(json) => send(out, 200, &json),
        Err((status, why)) => error(out, status, &why),
    }
}

/// `POST /worlds/create`'s body as `nf.worlds.create` takes it; a value that isn't one is why.
fn create_options(
    body: &serde_json::Map<String, Value>,
) -> Result<shapes::WorldCreateOptions, String> {
    let text = |key: &str| -> Result<Option<String>, String> {
        match body.get(key) {
            None | Some(Value::Null) => Ok(None),
            Some(Value::String(s)) => Ok(Some(s.clone())),
            Some(_) => Err(format!("{key} is a string")),
        }
    };
    let flag = |key: &str| -> Result<Option<bool>, String> {
        match body.get(key) {
            None | Some(Value::Null) => Ok(None),
            Some(Value::Bool(b)) => Ok(Some(*b)),
            Some(_) => Err(format!("{key} is true or false")),
        }
    };
    let choice = |key: &str, choices: &[&str]| -> Result<Option<String>, String> {
        let value = text(key)?;
        if let Some(v) = &value
            && !choices.contains(&v.as_str())
        {
            return Err(format!("{key} \"{v}\" isn't {}", choices.join(", ")));
        }
        Ok(value)
    };
    let seed = match body.get("seed") {
        None | Some(Value::Null) => None,
        Some(Value::Number(n)) => Some(n.as_i64().ok_or("seed is a whole number")?),
        Some(_) => return Err("seed is a whole number".into()),
    };
    Ok(shapes::WorldCreateOptions {
        generator: choice(
            "generator",
            &["normal", "flat", "void", "amplified", "large_biomes"],
        )?,
        terrain: text("terrain")?,
        environment: choice("environment", &["normal", "nether", "end"])?,
        dimension_type: text("dimension_type")?,
        seed,
        structures: flag("structures")?,
        keep_spawn_loaded: flag("keep_spawn_loaded")?,
    })
}

/// The stream of changes, as Server-Sent Events, until the tool goes or the server stops.
fn events(out: &mut dyn Write, shared: &Arc<Shared>) {
    let (send, receive) = mpsc::sync_channel(MAX_QUEUED);
    shared
        .listeners
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .push(send);
    let head = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n";
    if write!(out, "{head}event: state\ndata: {}\n\n", state_now(shared)).is_err()
        || out.flush().is_err()
    {
        return;
    }
    loop {
        let next = match receive.recv_timeout(Duration::from_secs(PING_SECONDS)) {
            Ok(Some(text)) => text,
            Ok(None) | Err(mpsc::RecvTimeoutError::Disconnected) => break,
            Err(mpsc::RecvTimeoutError::Timeout) => ": ping\n\n".to_owned(),
        };
        if out.write_all(next.as_bytes()).is_err() || out.flush().is_err() {
            break;
        }
    }
}

/// The token in `file`, made (readable by the server's own user only) when there's none yet.
fn read_token(file: &std::path::Path, platform: &dyn Platform) -> Result<String, String> {
    if file.exists() {
        let token =
            std::fs::read_to_string(file).map_err(|e| format!("{}: {e}", file.display()))?;
        let token = token.trim().to_owned();
        if token.len() < 24 {
            return Err(format!(
                "{}'s token is shorter than 24 characters",
                file.display()
            ));
        }
        return Ok(token);
    }
    if let Some(dir) = file.parent() {
        std::fs::create_dir_all(dir).map_err(|e| e.to_string())?;
    }
    let mut bytes = [0u8; 32];
    getrandom::fill(&mut bytes).map_err(|e| e.to_string())?;
    let token: String = bytes.iter().map(|b| format!("{b:02x}")).collect();
    std::fs::write(file, format!("{token}\n")).map_err(|e| format!("{}: {e}", file.display()))?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt as _;
        let _ = std::fs::set_permissions(file, std::fs::Permissions::from_mode(0o600));
    }
    platform.log().info(format!(
        "NetherForge: wrote a new control token to {}",
        file.display()
    ));
    Ok(token)
}
