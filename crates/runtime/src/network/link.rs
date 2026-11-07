//! This server's place in its network for the plugin's life (Kotlin's `NetworkLink`): the link to the hub, what the
//! hub has said of the network (who's online where, which servers are connected), and the players sent here who
//! haven't arrived yet. Sessions come and go; the link doesn't, so a reload doesn't reconnect. Only the main thread
//! reads its state: it's applied as `drain` takes what came in.

use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::rc::Rc;
use std::sync::Arc;
use std::time::Instant;

use indexmap::IndexMap;
use serde_json::Value;

use super::client::{Hello, HubClient, SharedLifecycle};
use super::local::{Limits, LocalHub};
use super::protocol::{Grants, HubFrame, Lifecycle, PlayerState, ServerFrame, ServerState};
use super::{Inbound, Inbox, NetworkConfig, Welcome};

/// The name a script test's network has: its server is ready from the start.
pub const TEST_NETWORK: &str = "test";
const TICK_REPORT_TICKS: u64 = 100;
const FAILURE_EVERY_SECONDS: u64 = 5 * 60;

/// How this server is in a network.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Mode {
    /// `network.enabled`: TCP to the proxy's hub.
    Remote,
    /// A dev server not put in a network, or a script test: a hub in this process with this server alone.
    Local,
    /// A production server not in a network.
    Off,
}

/// A player sent here, waiting to arrive: who sent them, with what, and the tick it was heard.
#[derive(Clone, Debug)]
pub struct Arrival {
    pub from: String,
    pub data: Option<Value>,
    pub tick: u64,
}

/// `/nf shutdown` under way.
#[derive(Clone, Debug)]
pub struct Shutdown {
    pub after_empty: bool,
    pub deadline: u64,
    pub stopping_since: Option<u64>,
}

/// The network as the hub last said, and this server's own state in it.
pub struct LinkState {
    pub name: Option<String>,
    pub connected: bool,
    pub players: IndexMap<String, PlayerState>,
    pub servers: IndexMap<String, ServerState>,
    pub accepting: bool,
    pub idle: bool,
    pub startup_millis: Option<i64>,
    pub tick_millis: Option<f64>,
    pub max_value_bytes: i32,
    pub grants: Grants,
    pub policy: Option<String>,
    pub keeps_packs: bool,
    pub arrivals: HashMap<String, Arrival>,
    pub shutdown: Option<Shutdown>,
    last_failure: Option<(String, Instant)>,
}

/// The link, shared by every session of the plugin's life: what `session::Config::network_link` takes.
#[derive(Clone)]
pub struct Link(pub Rc<NetworkLink>);

impl std::fmt::Debug for Link {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "Link({:?})", self.0.mode)
    }
}

pub struct NetworkLink {
    config: NetworkConfig,
    /// When it was made: the plugin's start, which a server's time to be ready is counted from.
    pub created: Instant,
    pub mode: Mode,
    inbox: Arc<Inbox>,
    lifecycle: Arc<SharedLifecycle>,
    client: RefCell<Option<HubClient>>,
    local: RefCell<Option<Rc<LocalHub>>>,
    started: Cell<bool>,
    next_id: Cell<i64>,
    pub state: RefCell<LinkState>,
}

impl NetworkLink {
    pub fn new(config: NetworkConfig) -> Rc<NetworkLink> {
        let mode = if config.enabled && config.problem().is_none() {
            Mode::Remote
        } else if config.alone.is_some() && !config.enabled {
            Mode::Local
        } else {
            Mode::Off
        };
        let name = match mode {
            Mode::Remote => config.server_name.clone(),
            Mode::Local => config.alone.clone(),
            Mode::Off => None,
        };
        let lifecycle = Arc::new(SharedLifecycle::default());
        lifecycle.set(if config.alone.as_deref() == Some(TEST_NETWORK) {
            Lifecycle::Ready
        } else {
            Lifecycle::Starting
        });
        let bound = (mode == Mode::Remote).then_some(config.max_queued);
        Rc::new(NetworkLink {
            mode,
            inbox: Arc::new(Inbox::new(bound)),
            lifecycle,
            client: RefCell::default(),
            local: RefCell::default(),
            started: Cell::new(false),
            next_id: Cell::new(1),
            state: RefCell::new(LinkState {
                name,
                connected: false,
                players: IndexMap::new(),
                servers: IndexMap::new(),
                accepting: true,
                idle: false,
                startup_millis: None,
                tick_millis: None,
                max_value_bytes: super::protocol::DEFAULT_MAX_VALUE_BYTES,
                grants: Grants::trusted(),
                policy: None,
                keeps_packs: false,
                arrivals: HashMap::new(),
                shutdown: None,
                last_failure: None,
            }),
            created: Instant::now(),
            config,
        })
    }

    pub fn config(&self) -> &NetworkConfig {
        &self.config
    }

    /// Where this server is in its life.
    pub fn lifecycle(&self) -> Lifecycle {
        self.lifecycle.get()
    }

    /// An id for a request, write or send: one sequence for the plugin's life, so an answer to an old session's never
    /// completes a new one's.
    pub fn next_id(&self) -> i64 {
        let id = self.next_id.get();
        self.next_id.set(id + 1);
        id
    }

    pub fn connected(&self) -> bool {
        self.state.borrow().connected
    }

    pub fn name(&self) -> Option<String> {
        self.state.borrow().name.clone()
    }

    /// The local network's hub, in a local link.
    pub fn local(&self) -> Option<Rc<LocalHub>> {
        self.local.borrow().clone()
    }

    /// Starts it, once (a later session finds it started). What to say in the log is handed to `log`.
    pub fn start(&self, info: &dyn Fn(String)) {
        if self.started.replace(true) {
            return;
        }
        self.inbox.reopen();
        match self.mode {
            Mode::Off => {
                if let Some(problem) = self.config.problem() {
                    info(format!("NetherForge isn't joining its network: {problem}"));
                }
            }
            Mode::Remote => {
                let how = self
                    .config
                    .credential
                    .as_ref()
                    .map(|c| format!(" with the credential {c}"))
                    .unwrap_or_default();
                let name = self.config.server_name.clone().unwrap_or_default();
                info(format!(
                    "Joining the network as {name} through its hub at {}:{}{how}",
                    self.config.host, self.config.port
                ));
                let mut client = HubClient::new(
                    Hello {
                        host: self.config.host.clone(),
                        port: self.config.port,
                        server: name,
                        secret: self.config.secret.clone().unwrap_or_default(),
                        credential: self.config.credential.clone(),
                        group: self.config.group.clone(),
                        capacity: self.config.capacity,
                        register: self.config.register.clone(),
                        instance: super::seal::new_nonce(),
                        max_queued: 50_000,
                    },
                    self.inbox.clone(),
                    self.lifecycle.clone(),
                );
                client.start();
                *self.client.borrow_mut() = Some(client);
            }
            Mode::Local => {
                let alone = self.config.alone.clone().unwrap_or_default();
                let hub = LocalHub::new(&alone, Limits::default());
                *self.local.borrow_mut() = Some(hub.clone());
                hub.connect_here(self.inbox.clone(), self.lifecycle());
                // A local link connects as it starts: the network is there before the first tick, whose drain raises
                // `network_connect`. The welcome is applied now and again as it's drained (which changes nothing).
                if let Some(Inbound::Connected(welcome)) = self.inbox.front() {
                    self.welcomed(&welcome);
                }
            }
        }
    }

    /// Stops it: a clean shutdown says it's leaving first.
    pub fn stop(&self) {
        if let Some(mut client) = self.client.borrow_mut().take() {
            if client.connected() {
                client.leave();
            }
            client.stop();
        }
        if let Some(hub) = self.local.borrow_mut().take() {
            hub.disconnect_here();
        }
        self.inbox.close();
        self.started.set(false);
        let mut state = self.state.borrow_mut();
        state.connected = false;
        state.players.clear();
        state.servers.clear();
    }

    /// Sends `frame` to the hub; false when not connected.
    pub fn send(&self, frame: ServerFrame) -> bool {
        match self.mode {
            Mode::Remote => self
                .client
                .borrow()
                .as_ref()
                .is_some_and(|c| c.send(&frame)),
            Mode::Local => {
                if !self.connected() {
                    return false;
                }
                let Some(hub) = self.local() else {
                    return false;
                };
                hub.receive_here(frame);
                true
            }
            Mode::Off => false,
        }
    }

    /// The main thread, once a tick: a local hub's own tick (expiry, timeouts) once a second.
    pub fn tick(&self, tick: u64) {
        if tick.is_multiple_of(20)
            && let Some(hub) = self.local()
        {
            hub.tick();
        }
    }

    /// Up to `max` of what came in, oldest first, with the state they change already changed. A failure to connect
    /// is logged, once per new reason (or every few minutes).
    pub fn drain(
        &self,
        max: usize,
        tick: u64,
        info: &dyn Fn(String),
        warn: &dyn Fn(String),
    ) -> Vec<Inbound> {
        let mut out = Vec::new();
        while out.len() < max {
            let Some(item) = self.inbox.pop() else { break };
            match &item {
                Inbound::Connected(welcome) => {
                    self.welcomed(welcome);
                    if self.mode == Mode::Remote {
                        info(format!(
                            "Joined the network as {}: {} servers connected",
                            welcome.server,
                            welcome.servers.iter().filter(|s| s.linked).count()
                        ));
                    }
                }
                Inbound::Disconnected(why) => {
                    let mut state = self.state.borrow_mut();
                    state.connected = false;
                    state.players.clear();
                    state.servers.clear();
                    if self.mode == Mode::Remote {
                        warn(format!(
                            "Lost the connection to the network's hub ({why}); trying again"
                        ));
                    }
                }
                Inbound::Failed(why) => {
                    let mut state = self.state.borrow_mut();
                    let fresh = match &state.last_failure {
                        Some((last, at)) => {
                            last != why || at.elapsed().as_secs() > FAILURE_EVERY_SECONDS
                        }
                        None => true,
                    };
                    if fresh {
                        state.last_failure = Some((why.clone(), Instant::now()));
                        warn(format!("Couldn't join the network: {why}; trying again"));
                    }
                }
                Inbound::Frame(frame) => {
                    if let HubFrame::PlayerLeft { id } = frame {
                        let gone = self.state.borrow_mut().players.shift_remove(id);
                        if let Some(player) = gone {
                            out.push(Inbound::Left(player));
                        }
                        continue;
                    }
                    // A server's link coming or going is an event; the rest of what changes of servers is only state.
                    let quiet = match frame {
                        HubFrame::Servers { .. } | HubFrame::PlayerPack { .. } => true,
                        HubFrame::Server { server } => self
                            .state
                            .borrow()
                            .servers
                            .get(&server.name)
                            .is_some_and(|s| s.linked == server.linked),
                        _ => false,
                    };
                    self.apply(frame, tick);
                    if quiet {
                        continue;
                    }
                }
                Inbound::Left(_) => {}
            }
            out.push(item);
        }
        out
    }

    /// Waits up to `seconds` for the hub's first welcome, on the main thread as the server starts, and takes what it
    /// says of this server (its grants, the network's policy) at once, so the project starts under it. False when it
    /// didn't come in time. Its `Connected` is drained later as ever (saying it again changes nothing).
    pub fn await_welcome(&self, seconds: u64) -> bool {
        if self.mode != Mode::Remote || seconds == 0 {
            return self.connected();
        }
        let deadline = Instant::now() + std::time::Duration::from_secs(seconds);
        loop {
            if let Some(Inbound::Connected(welcome)) = self.inbox.front() {
                self.welcomed(&welcome);
                return true;
            }
            if Instant::now() >= deadline {
                return false;
            }
            std::thread::sleep(std::time::Duration::from_millis(10));
        }
    }

    /// The network as `welcome` says it is.
    fn welcomed(&self, welcome: &Welcome) {
        {
            let mut state = self.state.borrow_mut();
            state.connected = true;
            state.name = Some(welcome.server.clone());
            state.last_failure = None;
            state.max_value_bytes = welcome.max_value_bytes;
            state.grants = welcome.grants.clone();
            state.policy.clone_from(&welcome.policy);
            state.keeps_packs = welcome.keeps_packs;
            state.players = welcome
                .players
                .iter()
                .map(|p| (p.id.clone(), p.clone()))
                .collect();
            state.servers = welcome
                .servers
                .iter()
                .map(|s| (s.name.clone(), s.clone()))
                .collect();
        }
        // The hello said the state; the rest of what it is, again.
        self.status();
    }

    fn apply(&self, frame: &HubFrame, tick: u64) {
        let mut state = self.state.borrow_mut();
        match frame {
            HubFrame::PlayerJoined { player } => {
                state.players.insert(player.id.clone(), player.clone());
            }
            HubFrame::PlayerMoved { id, server, .. } => {
                if let Some(player) = state.players.get_mut(id) {
                    player.server = Some(server.clone());
                }
            }
            HubFrame::Server { server } => {
                state.servers.insert(server.name.clone(), server.clone());
            }
            HubFrame::Servers { servers } => {
                for server in servers {
                    state.servers.insert(server.name.clone(), server.clone());
                }
            }
            HubFrame::PlayerPack { id, pack } => {
                if let Some(player) = state.players.get_mut(id) {
                    player.pack = Some(pack.clone());
                }
            }
            HubFrame::Arriving { player, from, data } => {
                state.arrivals.insert(
                    player.clone(),
                    Arrival {
                        from: from.clone(),
                        data: data.clone(),
                        tick,
                    },
                );
            }
            HubFrame::ArrivalCancelled { player } => {
                state.arrivals.remove(player);
            }
            _ => {}
        }
    }

    // ---- this server's life ---------------------------------------------------------

    /// Says to the hub what this server is now.
    fn status(&self) {
        let frame = {
            let state = self.state.borrow();
            ServerFrame::Status {
                accepting: state.accepting,
                state: self.lifecycle(),
                idle: state.idle,
                startup_millis: state.startup_millis,
                tick_millis: state.tick_millis,
            }
        };
        self.send(frame);
    }

    /// Ready, `startup_millis` after the server's process started; only once.
    pub fn ready(&self, startup_millis: i64) {
        if self.lifecycle() != Lifecycle::Starting {
            return;
        }
        self.lifecycle.set(Lifecycle::Ready);
        self.state.borrow_mut().startup_millis = Some(startup_millis);
        self.status();
    }

    /// Draining or ready again; a server shutting down stays as it is.
    pub fn set_draining(&self, draining: bool) {
        if self.state.borrow().shutdown.is_some() {
            return;
        }
        let now = self.lifecycle();
        let next = match (draining, now) {
            (true, Lifecycle::Ready | Lifecycle::Starting) => Lifecycle::Draining,
            (false, Lifecycle::Draining) => Lifecycle::Ready,
            _ => return,
        };
        self.lifecycle.set(next);
        self.status();
    }

    /// Begins `/nf shutdown`: draining now, then stopping.
    pub fn begin_shutdown(&self, plan: Shutdown) {
        self.state.borrow_mut().shutdown = Some(plan);
        if self.lifecycle() != Lifecycle::Stopping {
            self.lifecycle.set(Lifecycle::Draining);
        }
        self.status();
    }

    /// Stopping: the hub sends every player here to the group's fallback.
    pub fn stopping(&self, tick: u64) {
        self.lifecycle.set(Lifecycle::Stopping);
        if let Some(plan) = self.state.borrow_mut().shutdown.as_mut() {
            plan.stopping_since = Some(tick);
        }
        self.status();
        self.send(ServerFrame::Evacuate);
    }

    pub fn set_idle(&self, idle: bool) {
        if self.state.borrow().idle == idle {
            return;
        }
        self.state.borrow_mut().idle = idle;
        self.status();
    }

    pub fn set_accepting(&self, accepting: bool) {
        if self.state.borrow().accepting == accepting {
            return;
        }
        self.state.borrow_mut().accepting = accepting;
        self.status();
    }

    /// Every few seconds: the server's average tick time to the hub.
    pub fn report_tick(&self, tick: u64, millis: f64) {
        if !tick.is_multiple_of(TICK_REPORT_TICKS) || !self.connected() {
            return;
        }
        self.state.borrow_mut().tick_millis = Some(millis);
        self.status();
    }

    /// This server's group: `network.group`, or its own name.
    pub fn group(&self) -> Option<String> {
        let own = if self.mode == Mode::Remote {
            self.config.group.clone()
        } else {
            None
        };
        own.or_else(|| self.name())
    }

    /// Each server's players: counted from who's online where when this server sees everyone, else as the hub said.
    pub fn counts(&self) -> HashMap<String, i64> {
        let state = self.state.borrow();
        let mut counts = HashMap::new();
        if state.grants.presence {
            for player in state.players.values() {
                if let Some(server) = &player.server {
                    *counts.entry(server.clone()).or_insert(0) += 1;
                }
            }
        } else {
            for server in state.servers.values() {
                counts.insert(server.name.clone(), i64::from(server.players));
            }
        }
        counts
    }

    // ---- this server's own players, in a local network -------------------------

    /// A player joined this server: in a local network the proxy is this server, and says so.
    pub fn joined_here(&self, id: &str, name: &str) {
        if let Some(hub) = self.local() {
            hub.joined_here(id, name);
        }
    }

    /// A player left this server: in a local network they leave it, unless they were sent elsewhere.
    pub fn quit_here(&self, id: &str) {
        if let Some(hub) = self.local() {
            hub.quit_here(id);
        }
    }
}

impl Drop for NetworkLink {
    fn drop(&mut self) {
        if let Some(mut client) = self.client.get_mut().take() {
            client.stop();
        }
    }
}
