//! `nf.network` (Kotlin's `network/` and `NetworkImpls`): this server's place in a network of servers behind one
//! proxy, which runs the network's **hub** (`packages/network`, Kotlin, unchanged). This side is a client of the hub's
//! protocol, frame for frame (`protocol.rs`, `seal.rs`).
//!
//! - **The link** (`link.rs`, Kotlin's `NetworkLink`) is the plugin's, not a session's: a reload doesn't reconnect.
//!   It's remote (`client.rs`: TCP to the proxy's hub, on threads of its own, what it hears queued for the main thread
//!   with backpressure), local (`local.rs`: a dev server not in a network, or a script test: a stand-in for the hub in
//!   this process, with the test's other servers) or off.
//! - **The session's side** (`service.rs`, Kotlin's `Network`): the channels and maps scripts named, what waits for an
//!   answer, and what came in, drained once a tick and handed to scripts as events. Answers reach scripts at the tick
//!   boundary like every async value.
//! - **The API** (`api.rs`): `nf.network`, `NetworkChannel`, `NetworkMap`, `NetworkHold`.

pub mod api;
pub mod client;
pub mod link;
pub mod local;
pub mod protocol;
pub mod seal;
pub mod service;

use std::collections::VecDeque;
use std::sync::{Condvar, Mutex};

pub use link::{Link, Mode, NetworkLink};
pub use protocol::{HubFrame, ServerFrame};

/// `network:` in `config.yml` (Kotlin's `NetworkConfig`), read on dev servers too.
#[derive(Clone, Debug)]
pub struct NetworkConfig {
    pub enabled: bool,
    /// The hub's address: the proxy's host, and the port its `hub.properties` says.
    pub host: String,
    pub port: u16,
    /// This server's name as the proxy names it.
    pub server_name: Option<String>,
    /// The secret this server proves: its credential's, or the hub's shared one.
    pub secret: Option<String>,
    /// This server's credential's id; none: the shared secret.
    pub credential: Option<String>,
    pub group: Option<String>,
    pub capacity: Option<i32>,
    /// Where the proxy reaches this server when it adds itself to the proxy's servers.
    pub register: Option<protocol::Registration>,
    /// Whether the server is ready only once its project runs (`network.require-project`).
    pub require_project: bool,
    /// How long the server waits as it starts for the hub to welcome it (its grants, the network's policy for it)
    /// before its project starts; 0: it doesn't (`network.wait-for-hub-seconds`).
    pub wait_for_hub_seconds: u64,
    /// Whether a server the hub hasn't welcomed runs nothing rather than run without the network's policy; it starts
    /// by itself once the hub is there (`network.require-hub`).
    pub require_hub: bool,
    /// How long this server may be empty before the hub hears it's idle; 0: never.
    pub idle_after_seconds: u64,
    /// What each package may send a minute: messages, requests, map writes and players sent.
    pub messages_per_minute: usize,
    /// How long a request waits for its answer, here.
    pub request_timeout_seconds: u64,
    /// What reaches scripts a tick at most; the rest waits for the next.
    pub messages_per_tick: usize,
    /// What may wait for the main thread before the server stops reading from the hub.
    pub max_queued: usize,
    /// A network of this server alone, when it isn't in one: `dev` on a dev server, `test` in a script test (Kotlin
    /// passes it beside the config). None: a production server, which is in no network unless `enabled`.
    pub alone: Option<String>,
}

impl Default for NetworkConfig {
    fn default() -> NetworkConfig {
        NetworkConfig {
            enabled: false,
            host: "127.0.0.1".into(),
            port: protocol::DEFAULT_PORT,
            server_name: None,
            secret: None,
            credential: None,
            group: None,
            capacity: None,
            register: None,
            require_project: false,
            wait_for_hub_seconds: 0,
            require_hub: false,
            idle_after_seconds: 0,
            messages_per_minute: 1200,
            request_timeout_seconds: 10,
            messages_per_tick: 1000,
            max_queued: 10_000,
            alone: None,
        }
    }
}

impl NetworkConfig {
    /// Why `enabled` can't be acted on, or none when it can (Kotlin's words).
    #[allow(clippy::question_mark)]
    pub fn problem(&self) -> Option<String> {
        use protocol::{AUTO_NAME, is_credential, is_server};
        if !self.enabled {
            return None;
        }
        let Some(name) = &self.server_name else {
            return Some(
                "network.server-name is empty: set it to this server's name in the proxy's config"
                    .into(),
            );
        };
        Some(if !is_server(name) {
            format!("network.server-name \"{name}\" isn't a server name (letters, digits, _ . -)")
        } else if let Some(c) = self.credential.as_ref().filter(|c| !is_credential(c)) {
            format!("network.credential.id \"{c}\" isn't a credential id")
        } else if self.credential.is_some() && self.secret.is_none() {
            "network.credential.secret is empty: it's what /nfhub credentials add printed".into()
        } else if self.secret.is_none() {
            "network.secret is empty: copy it from the proxy's plugins/netherforge/hub.properties, or give the server a credential".into()
        } else if self.port == 0 {
            format!("network.hub's port {} isn't a port", self.port)
        } else if let Some(g) = self.group.as_ref().filter(|g| !is_server(g)) {
            format!("network.group \"{g}\" isn't a group's name (letters, digits, _ . -)")
        } else if name == AUTO_NAME && self.register.is_none() {
            "network.server-name is auto, which only a server that registers itself can be (network.register.address)".into()
        } else if name == AUTO_NAME && self.group.is_none() {
            "network.server-name is auto, which needs network.group to number it from".into()
        } else if let Some(r) = self
            .register
            .as_ref()
            .filter(|r| !(1..=65535).contains(&r.port))
        {
            format!("network.register.address's port {} isn't a port", r.port)
        } else {
            return None;
        })
    }

    /// `host:port`, or a host alone (the default port): what `network.hub` says.
    pub fn hub(text: &str) -> Option<(String, u16)> {
        let value = text.trim();
        if value.is_empty() {
            return None;
        }
        let colons = value.matches(':').count();
        let Some(colon) = value.rfind(':') else {
            return Some((value.into(), protocol::DEFAULT_PORT));
        };
        if colons > 1 && !value.starts_with('[') {
            return Some((value.into(), protocol::DEFAULT_PORT));
        }
        let port = value[colon + 1..].parse().ok()?;
        let host = value[..colon].trim_start_matches('[').trim_end_matches(']');
        Some((host.into(), port))
    }
}

/// As the server starts: waits up to `network.wait-for-hub-seconds` for the hub to welcome it, so the project starts
/// under the network's policy and grants (Kotlin's runtime does it before its first session), saying so in the log.
/// The link must have been started. True when the hub welcomed it.
pub fn wait_for_hub(link: &Link, log: &dyn netherforge_platform::PlatformLog) -> bool {
    let config = link.0.config();
    let waiting = config.wait_for_hub_seconds;
    if waiting == 0 || link.0.mode != Mode::Remote {
        return link.0.connected();
    }
    log.info(format!(
        "Waiting up to {waiting} seconds for the network's hub before starting the project"
    ));
    let welcomed = link.0.await_welcome(waiting);
    if !welcomed {
        log.warn(format!(
            "The network's hub didn't welcome this server within {waiting} seconds{}",
            if config.require_hub {
                "; the project waits for it"
            } else {
                "; starting the project without it"
            }
        ));
    }
    welcomed
}

/// What came in from the hub, for the main thread.
#[derive(Clone, Debug)]
pub enum Inbound {
    Connected(Box<Welcome>),
    Disconnected(String),
    Failed(String),
    Frame(HubFrame),
    /// A player left the network: who they were, since the link no longer has them.
    Left(protocol::PlayerState),
}

/// The hub's welcome: the network as it is, and what this server may do.
#[derive(Clone, Debug, PartialEq)]
pub struct Welcome {
    pub server: String,
    pub servers: Vec<protocol::ServerState>,
    pub players: Vec<protocol::PlayerState>,
    pub max_value_bytes: i32,
    pub grants: protocol::Grants,
    pub policy: Option<String>,
    pub keeps_packs: bool,
}

impl Welcome {
    pub fn of(frame: HubFrame) -> Option<Welcome> {
        match frame {
            HubFrame::Welcome {
                server,
                servers,
                players,
                max_value_bytes,
                grants,
                policy,
                keeps_packs,
            } => Some(Welcome {
                server,
                servers,
                players,
                max_value_bytes,
                grants,
                policy,
                keeps_packs,
            }),
            _ => None,
        }
    }
}

/// What the link heard, waiting for the main thread. A remote link's reader waits for room past its bound (which
/// stops it reading the socket: backpressure, as for WebSockets, not dropping); a local one never waits.
pub struct Inbox {
    state: Mutex<InboxState>,
    room: Condvar,
    bound: Option<usize>,
}

struct InboxState {
    queue: VecDeque<Inbound>,
    closed: bool,
}

impl Inbox {
    pub fn new(bound: Option<usize>) -> Inbox {
        Inbox {
            state: Mutex::new(InboxState {
                queue: VecDeque::new(),
                closed: false,
            }),
            room: Condvar::new(),
            bound,
        }
    }

    /// Queues `item`, waiting for room first when it's bounded. Dropped once the inbox is closed (the link stopping:
    /// nothing drains what's said then).
    pub fn push(&self, item: Inbound) {
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(bound) = self.bound {
            while state.queue.len() >= bound && !state.closed {
                state = self.room.wait(state).unwrap_or_else(|e| e.into_inner());
            }
        }
        if !state.closed {
            state.queue.push_back(item);
        }
    }

    /// The oldest item, making room for one more.
    pub fn pop(&self) -> Option<Inbound> {
        let item = self
            .state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .queue
            .pop_front();
        if item.is_some() {
            self.room.notify_one();
        }
        item
    }

    /// A copy of the oldest item, left where it is.
    pub fn front(&self) -> Option<Inbound> {
        self.state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .queue
            .front()
            .cloned()
    }

    pub fn close(&self) {
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        state.closed = true;
        state.queue.clear();
        drop(state);
        self.room.notify_all();
    }

    /// Opens it again (a stopped link started again).
    pub fn reopen(&self) {
        self.state.lock().unwrap_or_else(|e| e.into_inner()).closed = false;
    }

    pub fn len(&self) -> usize {
        self.state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .queue
            .len()
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }
}
