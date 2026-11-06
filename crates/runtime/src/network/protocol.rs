//! What a server and its network's hub say to each other (`:network`'s `Protocol.kt`, which the hub on the proxy
//! speaks): one JSON object a line, its kind in `"t"`. The hub stays Kotlin, so these are its frames field for field:
//! names as kotlinx writes them, a field at its default left out (`encodeDefaults = false`) and a null one too
//! (`explicitNulls = false`), so what this side writes is byte for byte what a Kotlin server would
//! (`packages/network/testdata/session.json` holds both to it). Proxies' frames aren't here: a server never sees one,
//! and a line this side doesn't know is ignored, as the hub's unknown keys are.

use serde::{Deserialize, Serialize};
use serde_json::Value;

/// This protocol's version: a hub refuses a hello of another.
pub const VERSION: i32 = 3;

/// A registering server's name that asks the hub for one: its group's, numbered.
pub const AUTO_NAME: &str = "auto";

/// The port a hub listens on unless its owner says otherwise.
pub const DEFAULT_PORT: u16 = 25590;

pub const PING_MILLIS: u64 = 15_000;
pub const IDLE_MILLIS: u64 = 60_000;
/// How long a server waits for the hub's answer to its hello.
pub const HELLO_MILLIS: u64 = 10_000;
/// The most a frame may be before the handshake is done.
pub const MAX_FRAME_BEFORE_WELCOME: usize = 16 * 1024;
/// The most a frame may be once the handshake is done (a welcome lists every player).
pub const MAX_FRAME: usize = 16 * 1024 * 1024;
/// The most a sealed line may be: `MAX_FRAME` of JSON, in base64, with its tag.
pub const MAX_SEALED_LINE: usize = MAX_FRAME / 3 * 4 + 64;
/// The hub's `max-value-bytes` unless its owner says otherwise.
pub const DEFAULT_MAX_VALUE_BYTES: i32 = 64 * 1024;

fn is_false(b: &bool) -> bool {
    !*b
}
fn is_true(b: &bool) -> bool {
    *b
}
fn is_zero(n: &i32) -> bool {
    *n == 0
}
fn is_empty(s: &str) -> bool {
    s.is_empty()
}
fn is_ready(state: &Lifecycle) -> bool {
    *state == Lifecycle::Ready
}
fn yes() -> bool {
    true
}
fn default_max_value() -> i32 {
    DEFAULT_MAX_VALUE_BYTES
}
fn is_default_max_value(n: &i32) -> bool {
    *n == DEFAULT_MAX_VALUE_BYTES
}
fn is_trusted(grants: &Grants) -> bool {
    *grants == Grants::trusted()
}

/// How many bytes `value` takes as JSON: what the limits on values count (`Protocol.size`).
pub fn size(value: Option<&Value>) -> usize {
    value.map_or(0, |v| v.to_string().len())
}

/// Where a server is in its life.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Lifecycle {
    /// Its project is loading, or a script holds it back.
    Starting,
    #[default]
    Ready,
    Draining,
    Stopping,
}

impl Lifecycle {
    pub fn word(self) -> &'static str {
        match self {
            Lifecycle::Starting => "starting",
            Lifecycle::Ready => "ready",
            Lifecycle::Draining => "draining",
            Lifecycle::Stopping => "stopping",
        }
    }
}

/// How the hub picks an instance of a group to send players to.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Balance {
    Least,
    Fill,
}

impl Balance {
    pub fn of(word: &str) -> Option<Balance> {
        match word {
            "least" => Some(Balance::Least),
            "fill" => Some(Balance::Fill),
            _ => None,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum MapOp {
    Set,
    Delete,
    Add,
    Insert,
}

impl MapOp {
    pub fn word(self) -> &'static str {
        match self {
            MapOp::Set => "set",
            MapOp::Delete => "delete",
            MapOp::Add => "add",
            MapOp::Insert => "insert",
        }
    }
}

/// Why a map's key changed, as scripts read it.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum MapCause {
    Set,
    Delete,
    Expired,
}

impl MapCause {
    pub fn word(self) -> &'static str {
        match self {
            MapCause::Set => "set",
            MapCause::Delete => "delete",
            MapCause::Expired => "expired",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ModerationAction {
    Ban,
    Unban,
    Mute,
    Unmute,
}

impl ModerationAction {
    pub fn word(self) -> &'static str {
        match self {
            ModerationAction::Ban => "ban",
            ModerationAction::Unban => "unban",
            ModerationAction::Mute => "mute",
            ModerationAction::Unmute => "unmute",
        }
    }
}

/// What a server may do with one channel or map.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Access {
    Read,
    Write,
    Request,
    Answer,
}

impl Access {
    pub fn word(self) -> &'static str {
        match self {
            Access::Read => "read",
            Access::Write => "write",
            Access::Request => "request",
            Access::Answer => "answer",
        }
    }
}

/// Channels and maps shared with a server it doesn't own: a full name, or a pattern ending in `*`.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Share {
    pub name: String,
    pub access: Vec<Access>,
}

impl Share {
    pub fn matches(&self, full: &str) -> bool {
        match self.name.strip_suffix('*') {
            Some(prefix) => full.starts_with(prefix),
            None => full == self.name,
        }
    }
}

/// What a server may do in the network, from its credential (`Grants.kt`); the hub holds every frame to it.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Grants {
    pub role: String,
    #[serde(default, skip_serializing_if = "is_false")]
    pub trusted: bool,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub owns: Vec<String>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub shares: Vec<Share>,
    #[serde(default = "yes", skip_serializing_if = "is_true")]
    pub presence: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub send_to: Option<Vec<String>>,
    #[serde(default, skip_serializing_if = "is_false")]
    pub moderation: bool,
}

impl Default for Grants {
    fn default() -> Grants {
        Grants::trusted()
    }
}

/// The hub's own maps' namespace: no package writes them, every server reads its bans and mutes.
pub const RESERVED_NAMESPACE: &str = "netherforge";
pub const BANS: &str = "netherforge:bans";
pub const MUTES: &str = "netherforge:mutes";

pub fn is_reserved(full: &str) -> bool {
    namespace_of(full) == RESERVED_NAMESPACE
}

impl Grants {
    pub fn trusted() -> Grants {
        Grants {
            role: "trusted".into(),
            trusted: true,
            owns: Vec::new(),
            shares: Vec::new(),
            presence: true,
            send_to: None,
            moderation: false,
        }
    }

    pub fn moderates(&self) -> bool {
        self.trusted || self.moderation
    }

    /// Whether it may do `access` on `full`.
    pub fn allows(&self, full: &str, access: Access) -> bool {
        if is_reserved(full) {
            return access == Access::Read && (full == BANS || full == MUTES);
        }
        if self.trusted || self.owns.iter().any(|o| o == namespace_of(full)) {
            return true;
        }
        self.shares.iter().any(|share| {
            share.matches(full)
                && (share.access.contains(&access)
                    || (access == Access::Read && share.access.contains(&Access::Write)))
        })
    }
}

/// A server of the network as the hub knows it.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ServerState {
    pub name: String,
    pub linked: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub group: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub capacity: Option<i32>,
    #[serde(default = "yes", skip_serializing_if = "is_true")]
    pub accepting: bool,
    #[serde(default, skip_serializing_if = "is_zero")]
    pub players: i32,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub state: Option<Lifecycle>,
    #[serde(default, skip_serializing_if = "is_false")]
    pub idle: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub startup_millis: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tick_millis: Option<f64>,
}

impl ServerState {
    /// One the proxy names that NetherForge on it hasn't connected from.
    pub fn unlinked(name: &str, players: i32) -> ServerState {
        ServerState {
            name: name.into(),
            linked: false,
            group: None,
            capacity: None,
            accepting: true,
            players,
            state: None,
            idle: false,
            startup_millis: None,
            tick_millis: None,
        }
    }

    /// Its group's name: its group, or its own name when it's a group of one.
    pub fn group_name(&self) -> &str {
        self.group.as_deref().unwrap_or(&self.name)
    }
}

/// A player online in the network.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct PlayerState {
    pub id: String,
    pub name: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub server: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub proxy: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub pack: Option<String>,
}

/// A server adding itself to the proxy as it joins.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Registration {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub host: Option<String>,
    pub port: i32,
}

/// A ban or a mute, as the hub keeps it in its own maps.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Sanction {
    pub name: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub reason: Option<String>,
    pub by: String,
    pub at: i64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub until: Option<i64>,
}

/// What the hub sends a server.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(tag = "t", rename_all = "snake_case", rename_all_fields = "camelCase")]
pub enum HubFrame {
    Challenge {
        version: i32,
        nonce: String,
    },
    Welcome {
        server: String,
        servers: Vec<ServerState>,
        players: Vec<PlayerState>,
        #[serde(
            default = "default_max_value",
            skip_serializing_if = "is_default_max_value"
        )]
        max_value_bytes: i32,
        #[serde(default, skip_serializing_if = "is_trusted")]
        grants: Grants,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        policy: Option<String>,
        #[serde(default, skip_serializing_if = "is_false")]
        keeps_packs: bool,
    },
    ModerateResult {
        id: i64,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        error: Option<String>,
    },
    PlayerPack {
        id: String,
        pack: String,
    },
    Refused {
        reason: String,
    },
    Message {
        channel: String,
        from: String,
        value: Value,
    },
    Requested {
        id: i64,
        channel: String,
        from: String,
        value: Value,
    },
    Reply {
        id: i64,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        value: Option<Value>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        error: Option<String>,
    },
    MapSnapshot {
        map: String,
        entries: serde_json::Map<String, Value>,
    },
    MapChanged {
        map: String,
        key: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        value: Option<Value>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        from: Option<String>,
        cause: MapCause,
    },
    MapResult {
        id: i64,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        value: Option<Value>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        error: Option<String>,
    },
    PlayerJoined {
        player: PlayerState,
    },
    PlayerMoved {
        id: String,
        server: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        previous: Option<String>,
    },
    PlayerLeft {
        id: String,
    },
    Server {
        server: ServerState,
    },
    Servers {
        servers: Vec<ServerState>,
    },
    Arriving {
        player: String,
        from: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        data: Option<Value>,
    },
    ArrivalCancelled {
        player: String,
    },
    SendResult {
        id: i64,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        error: Option<String>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        server: Option<String>,
    },
    SendWaiting {
        id: i64,
        position: i32,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        status: Option<String>,
    },
    Drain {
        draining: bool,
    },
    Problem {
        message: String,
    },
    Pong,
}

/// What a server sends the hub.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(tag = "t", rename_all = "snake_case", rename_all_fields = "camelCase")]
pub enum ServerFrame {
    Hello {
        version: i32,
        server: String,
        proof: String,
        #[serde(default, skip_serializing_if = "is_empty")]
        nonce: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        credential: Option<String>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        group: Option<String>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        capacity: Option<i32>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        register: Option<Registration>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        instance: Option<String>,
        #[serde(default, skip_serializing_if = "is_ready")]
        state: Lifecycle,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        role: Option<String>,
    },
    Moderate {
        id: i64,
        action: ModerationAction,
        player: String,
        name: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        reason: Option<String>,
        by: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        until: Option<i64>,
    },
    Report {
        player: String,
        name: String,
        reason: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        details: Option<Value>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        by: Option<String>,
    },
    PackLoaded {
        player: String,
        sha1: String,
    },
    Leaving,
    Status {
        #[serde(default = "yes", skip_serializing_if = "is_true")]
        accepting: bool,
        #[serde(default, skip_serializing_if = "is_ready")]
        state: Lifecycle,
        #[serde(default, skip_serializing_if = "is_false")]
        idle: bool,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        startup_millis: Option<i64>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        tick_millis: Option<f64>,
    },
    Evacuate,
    Listen {
        channels: Vec<String>,
        maps: Vec<String>,
    },
    Publish {
        channel: String,
        value: Value,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        to: Option<String>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        group: Option<String>,
    },
    Request {
        id: i64,
        channel: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        to: Option<String>,
        value: Value,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        group: Option<String>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        admin: Option<String>,
    },
    Reply {
        id: i64,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        value: Option<Value>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        error: Option<String>,
    },
    MapWrite {
        id: i64,
        map: String,
        key: String,
        op: MapOp,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        value: Option<Value>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        amount: Option<f64>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        expires_millis: Option<i64>,
    },
    Send {
        id: i64,
        players: Vec<String>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        server: Option<String>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        data: Option<Value>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        group: Option<String>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        balance: Option<Balance>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        wait_millis: Option<i64>,
    },
    Ping,
}

/// A frame as its line.
pub fn encode<T: Serialize>(frame: &T) -> String {
    serde_json::to_string(frame).expect("a frame is always JSON")
}

/// A line from the hub, or none when it isn't a frame this side knows.
pub fn decode_hub(line: &str) -> Option<HubFrame> {
    serde_json::from_str(line).ok()
}

/// A line from a server, or none when it isn't a frame this side knows.
pub fn decode_server(line: &str) -> Option<ServerFrame> {
    serde_json::from_str(line).ok()
}

// ---- names (`NetworkNames`) ----------------------------------------------------------------

fn all(text: &str, ok: impl Fn(char) -> bool) -> bool {
    (1..=64).contains(&text.chars().count()) && text.chars().all(ok)
}

/// A server or group as a proxy names it.
pub fn is_server(name: &str) -> bool {
    all(name, |c| c.is_ascii_alphanumeric() || "_.-".contains(c))
}

/// A credential's id.
pub fn is_credential(id: &str) -> bool {
    is_server(id)
}

pub fn is_namespace(name: &str) -> bool {
    all(name, |c| {
        c.is_ascii_lowercase() || c.is_ascii_digit() || "_.-".contains(c)
    })
}

/// A channel's or map's own name.
pub fn is_name(name: &str) -> bool {
    all(name, |c| {
        c.is_ascii_lowercase() || c.is_ascii_digit() || "_.-/".contains(c)
    })
}

/// Whether `full` is `namespace:name`.
pub fn is_full(full: &str) -> bool {
    match full.split_once(':') {
        Some((namespace, name)) => {
            !namespace.is_empty() && is_namespace(namespace) && is_name(name)
        }
        None => false,
    }
}

pub fn namespace_of(full: &str) -> &str {
    full.split_once(':').map_or(full, |(ns, _)| ns)
}

pub const MAX_KEY_LENGTH: usize = 256;

/// A map's key: 1 to 256 characters (UTF-16 units, as Kotlin counts), none a control character.
pub fn is_key(key: &str) -> bool {
    let length = key.encode_utf16().count();
    length > 0 && length <= MAX_KEY_LENGTH && !key.chars().any(char::is_control)
}
