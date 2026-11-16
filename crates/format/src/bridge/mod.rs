//! The dev bridge (format's `bridge`): how a running editor and the server on
//! its dev server talk. Defined once here, with the editor's TypeScript types
//! generated from it (`specta`), and spoken by the editor's backend and, as the
//! runtime ports, the server. The Kotlin plugin's `Bridge.kt` speaks the same
//! protocol until the Kotlin runtime goes; the recorded session
//! (`testdata/bridge/session.ndjson`) holds both to it.
//!
//! **Transport.** The editor listens on `127.0.0.1:<port>` and starts the server
//! with `-Dnetherforge.bridge.port=<port>` and the environment variable
//! `NETHERFORGE_BRIDGE_TOKEN`. The plugin connects when it enables (and
//! reconnects if the connection drops). Frames are newline-delimited JSON-RPC
//! 2.0 (`rpc`), one message or batch per line.
//!
//! **The handshake.** The plugin's first frame is the `hello` request, carrying
//! a nonce and `PROTOCOL`, never the token: whatever listens on the port gets
//! nothing it could use. The editor answers one of another protocol with a
//! `PROTOCOL_MISMATCH` error saying so, and anything else with a nonce of its
//! own and its proof of the token over both (`HelloResult`); the plugin checks
//! that proof before it says anything more, then proves the token itself with
//! `authenticate` (`auth` says how a proof is made). The editor closes a
//! connection whose first frame isn't a hello, or whose proof is wrong, without
//! another word.
//!
//! **After it.** The editor sends requests (`REQUESTS`, the core's and each
//! extension's) and the plugin sends notifications (`EVENTS`) and streams
//! (`STREAMS`); the debugger's DAP messages (`dap`) go both ways as
//! notifications. A request for a method the plugin doesn't have is answered
//! with `rpc::METHOD_NOT_FOUND`. The bridge exists only on a dev server.
//!
//! **Encoding** is format's for the bridge (Kotlin's `Bridge.json`): one line
//! per frame, unknown keys ignored, and a field left out when it's null or its
//! default, so a frame says only what's not assumed.

use indexmap::IndexMap;
use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};

use crate::game::GameDataBundle;
use crate::manifest::SettingDef;
use crate::problem::Problem;

pub mod bots;
pub mod rpc;

#[cfg(feature = "bridge-auth")]
pub mod auth;

pub use bots::*;

/// The protocol's version. Bump it for any change one side can't read from the other: the editor refuses a
/// plugin of another version (and the plugin says why) rather than half-understanding it.
pub const PROTOCOL: i32 = 2;

pub const PORT_PROPERTY: &str = "netherforge.bridge.port";
pub const TOKEN_ENV: &str = "NETHERFORGE_BRIDGE_TOKEN";
pub const PROJECT_PROPERTY: &str = "netherforge.project";
/// The editor's package cache (`<data>/packages`), where a dev server finds git packages' checkouts.
pub const PACKAGE_CACHE_PROPERTY: &str = "netherforge.packages";

/// A dev server the editor runs in a dev network: the hub's `host:port` (the editor's proxy), this server's name
/// there, and the hub's secret (in the environment, never on the command line); its group and capacity when the
/// editor's network gives it one, and the credential it joins with when its role isn't `trusted`.
pub const NETWORK_HUB_PROPERTY: &str = "netherforge.network.hub";
pub const NETWORK_SERVER_PROPERTY: &str = "netherforge.network.server";
pub const NETWORK_GROUP_PROPERTY: &str = "netherforge.network.group";
pub const NETWORK_CAPACITY_PROPERTY: &str = "netherforge.network.capacity";
pub const NETWORK_CREDENTIAL_PROPERTY: &str = "netherforge.network.credential";
pub const NETWORK_SECRET_ENV: &str = "NETHERFORGE_NETWORK_SECRET";

/// The request was understood and couldn't be done; the message says why (`no player "Steve" online`).
pub const REQUEST_FAILED: i64 = -32000;

/// The editor refused a hello of another `PROTOCOL`; the message says which versions met.
pub const PROTOCOL_MISMATCH: i64 = -32001;

/// What a stream's notification carries its items under.
pub const ITEMS: &str = "items";

/// Which thread the server answers a request on.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Thread {
    /// The server's main thread, where everything the runtime touches lives: the default.
    Main,
    /// The bridge's own thread, at once: for control requests that must be answered while the main thread is
    /// busy or stopped (the debugger, profiler subscriptions). They may touch only what's safe from any thread.
    Bridge,
}

/// Arbitrary JSON, carried untouched: `unknown` to the editor's UI.
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
#[serde(transparent)]
pub struct Json(pub serde_json::Value);

#[cfg(feature = "specta")]
impl specta::Type for Json {
    fn definition(types: &mut specta::Types) -> specta::datatype::DataType {
        specta_typescript::Unknown::<()>::definition(types)
    }
}

/// A request's params when it takes none: sent without `params`, read from anything. `undefined` in TypeScript.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct NoParams;

impl Serialize for NoParams {
    fn serialize<S: serde::Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
        s.serialize_unit()
    }
}

impl<'de> Deserialize<'de> for NoParams {
    fn deserialize<D: serde::Deserializer<'de>>(d: D) -> Result<NoParams, D::Error> {
        serde::de::IgnoredAny::deserialize(d).map(|_| NoParams)
    }
}

#[cfg(feature = "specta")]
impl specta::Type for NoParams {
    fn definition(_: &mut specta::Types) -> specta::datatype::DataType {
        specta::datatype::DataType::Reference(specta_typescript::define("undefined"))
    }
}

/// A request: its method, its params and its result. One without params takes `NoParams`; one without an answer
/// returns `()` (answered with `"result": null`).
pub trait Method {
    const NAME: &'static str;
    const THREAD: Thread;
    type Params: Serialize + DeserializeOwned;
    type Result: Serialize + DeserializeOwned;
}

/// A notification: sent as it happens, never answered.
pub trait Event {
    const NAME: &'static str;
    type Params: Serialize + DeserializeOwned;
}

/// A stream: high-rate data, sent as notifications that each carry a batch, `{"items": [...]}`, in order. The
/// sender coalesces whatever is waiting into one notification, so a burst costs one frame rather than one per item.
pub trait Stream {
    const NAME: &'static str;
    type Item: Serialize + DeserializeOwned;
}

/// A stream's notification: its items, in order.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
pub struct Batch<T> {
    pub items: Vec<T>,
}

/// What the method table knows of a request, for whoever routes them.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct MethodInfo {
    pub name: &'static str,
    pub thread: Thread,
    /// Whether it's sent with `params`.
    pub takes_params: bool,
    /// Whether its answer says anything (`"result": null` when it doesn't).
    pub answers: bool,
}

/// A request in TypeScript's map of them: what it takes and answers.
#[cfg(feature = "specta")]
#[derive(specta::Type, Serialize)]
pub struct Call<P, R> {
    pub params: P,
    pub result: R,
}

fn is_unit<T: 'static>() -> bool {
    std::any::TypeId::of::<T>() == std::any::TypeId::of::<()>()
}

fn is_no_params<T: 'static>() -> bool {
    std::any::TypeId::of::<T>() == std::any::TypeId::of::<NoParams>()
}

/// The methods, events and streams, written once: each a typed marker (`Reload`, implementing `Method`), the
/// runtime's tables (`REQUESTS`, `EVENTS`, `STREAMS`), and the editor's TypeScript maps (`BridgeRequests`,
/// `BridgeEvents`).
macro_rules! protocol {
    (
        handshake { $( $(#[$hdoc:meta])* $hty:ident $hname:literal ($hp:ty) -> $hr:ty, $hthread:ident; )* }
        requests { $( $(#[$doc:meta])* $ty:ident $name:literal ($p:ty) -> $r:ty, $thread:ident; )* }
        events { $( $(#[$edoc:meta])* $ety:ident $ename:literal ($ep:ty); )* }
        streams { $( $(#[$sdoc:meta])* $sty:ident $sname:literal ($si:ty); )* }
    ) => {
        $( $(#[$hdoc])* pub struct $hty;
           impl Method for $hty {
               const NAME: &'static str = $hname;
               const THREAD: Thread = Thread::$hthread;
               type Params = $hp;
               type Result = $hr;
           } )*
        $( $(#[$doc])* pub struct $ty;
           impl Method for $ty {
               const NAME: &'static str = $name;
               const THREAD: Thread = Thread::$thread;
               type Params = $p;
               type Result = $r;
           } )*
        $( $(#[$edoc])* pub struct $ety;
           impl Event for $ety {
               const NAME: &'static str = $ename;
               type Params = $ep;
           } )*
        $( $(#[$sdoc])* pub struct $sty;
           impl Stream for $sty {
               const NAME: &'static str = $sname;
               type Item = $si;
           } )*

        /// The handshake's two requests, which the plugin sends first on every connection.
        pub fn handshake() -> Vec<MethodInfo> {
            vec![$( MethodInfo {
                name: $hname,
                thread: Thread::$hthread,
                takes_params: !is_no_params::<$hp>(),
                answers: !is_unit::<$hr>(),
            } ),*]
        }

        /// Every request the editor can send: the core's, then each extension's.
        pub fn requests() -> Vec<MethodInfo> {
            vec![$( MethodInfo {
                name: $name,
                thread: Thread::$thread,
                takes_params: !is_no_params::<$p>(),
                answers: !is_unit::<$r>(),
            } ),*]
        }

        /// Method `name`'s params (handshake, core or extension) read as its type and written back as they'd be
        /// sent; `None` for a method there isn't.
        pub fn reencode_params(
            name: &str,
            params: Option<&serde_json::Value>,
        ) -> Option<Result<Option<serde_json::Value>, serde_json::Error>> {
            match name {
                $( $hname => Some(decode_params::<$hty>(params).map(|p| encode_params::<$hty>(&p))), )*
                $( $name => Some(decode_params::<$ty>(params).map(|p| encode_params::<$ty>(&p))), )*
                _ => None,
            }
        }

        /// Whether `result` reads as method `name`'s answer; `None` for a method there isn't.
        pub fn check_result(name: &str, result: &serde_json::Value) -> Option<Result<(), serde_json::Error>> {
            match name {
                $( $hname => Some(decode_result::<$hty>(result).map(|_| ())), )*
                $( $name => Some(decode_result::<$ty>(result).map(|_| ())), )*
                _ => None,
            }
        }

        /// Whether `params` read as notification `name`'s (an event's, or a stream's batch); `None` for one there
        /// isn't.
        pub fn check_notification(
            name: &str,
            params: Option<&serde_json::Value>,
        ) -> Option<Result<(), serde_json::Error>> {
            let empty = serde_json::Value::Object(Default::default());
            match name {
                $( $ename => Some(<$ep>::deserialize(params.unwrap_or(&empty)).map(|_| ())), )*
                $( $sname => Some(decode_batch::<$sty>(params).map(|_| ())), )*
                _ => None,
            }
        }

        /// Every notification the plugin sends that isn't a stream.
        pub const EVENTS: &[&str] = &[$( $ename ),*];

        /// Every stream the plugin sends.
        pub const STREAMS: &[&str] = &[$( $sname ),*];

        /// Every request the editor can send over the dev bridge, by method: its params (`undefined` for none)
        /// and its result (`null` for none). What the editor's bridge client is typed by.
        #[cfg(feature = "specta")]
        #[derive(specta::Type, Serialize)]
        #[allow(non_snake_case)]
        pub struct BridgeRequests {
            $( #[serde(rename = $name)] pub $ty: Call<$p, $r>, )*
        }

        /// Every notification the plugin sends, by method: its params. A stream's carry a batch of items.
        #[cfg(feature = "specta")]
        #[derive(specta::Type, Serialize)]
        #[allow(non_snake_case)]
        pub struct BridgeEvents {
            $( #[serde(rename = $ename)] pub $ety: $ep, )*
            $( #[serde(rename = $sname)] pub $sty: Batch<$si>, )*
        }
    };
}

protocol! {
    handshake {
        /// The plugin's first frame on every connection.
        Hello "hello" (HelloParams) -> HelloResult, Bridge;
        /// The plugin's second, once the editor's answer proved it holds the token: the plugin's own proof.
        Authenticate "authenticate" (AuthenticateParams) -> (), Bridge;
    }
    requests {
        /// Reloads project paths: each one's resource, once.
        Reload "reload" (ReloadParams) -> ReloadResult, Main;
        /// Answers with where the centity spawned.
        Spawn "spawn" (SpawnParams) -> InstanceInfo, Main;
        /// Live centity instances, including inert ones whose centity is missing.
        Instances "instances" (NoParams) -> Vec<InstanceInfo>, Main;
        /// What the server knows about the game, from its live registries. Takes a moment: once per version.
        ExportGameData "export_game_data" (NoParams) -> GameDataBundle, Main;
        /// Runs a console command; fails when the server didn't run it.
        Command "command" (CommandParams) -> (), Main;
        PlayParticleEffect "play_particle_effect" (PlayParticleEffectParams) -> (), Main;
        /// Ends every effect `play_particle_effect` started.
        StopParticleEffects "stop_particle_effects" (NoParams) -> (), Main;
        /// Fails when nobody (or not that player) is online.
        PlayerPositionOf "player_position" (PlayerPositionParams) -> PlayerPosition, Main;
        /// The loaded worlds, the main one first.
        Worlds "worlds" (NoParams) -> Vec<LoadedWorld>, Main;
        SaveStructure "save_structure" (SaveStructureParams) -> SavedStructure, Main;
        /// Answers where the saved world's files are, relative to the server's folder.
        SaveWorld "save_world" (SaveWorldParams) -> SavedWorld, Main;
        /// Every package's server-owner settings and the values the server runs with.
        Settings "settings" (NoParams) -> ServerSettings, Main;
        /// Sets a server-owner setting (or puts it back to its default), as `/nf settings set` does; fails with
        /// why when the value isn't one the setting can have. Answers every package's settings as they are then.
        SetSetting "set_setting" (SetSettingParams) -> ServerSettings, Main;
        /// Answered at once on the bridge's thread, whatever the main thread is doing: tells a busy (or, with
        /// the debugger, paused) server from a gone one.
        Ping "ping" (NoParams) -> (), Bridge;
        /// Starts or stops the profiler stream; answered on the bridge's thread.
        ProfilerSubscribe "profiler_subscribe" (ProfilerSubscribeParams) -> (), Bridge;
        /// The bots extension (`bots`): fake players, on a server that has them.
        BotsJoin "bots/join" (BotJoinParams) -> BotInfo, Main;
        BotsLeave "bots/leave" (BotParams) -> (), Main;
        BotsList "bots/list" (NoParams) -> Vec<BotInfo>, Main;
        BotsAct "bots/act" (BotActParams) -> BotActResult, Main;
        BotsState "bots/state" (BotParams) -> BotState, Main;
        BotsEvents "bots/events" (BotEventsParams) -> BotEvents, Main;
    }
    events {
        /// On load, every reload, every connect: the full set.
        ProblemsChanged "problems" (Problems);
        /// Online players and instance count, on change, at most once a second.
        StatusChanged "status" (Status);
        /// Every package's server-owner settings, whenever one changed or the project restarted.
        SettingsChanged "settings_changed" (ServerSettings);
        /// The debugger's channel, both ways: each notification one Debug Adapter Protocol message as DAP
        /// defines it, untouched by the bridge. The plugin is the debug adapter and the editor its client; the
        /// plugin reads them on the bridge's thread, so they're heard while a breakpoint holds the main thread.
        Dap "dap" (Json);
    }
    streams {
        /// Runtime log lines and script errors.
        Console "console" (ConsoleEntry);
        /// The profiler's measurements, batched about once a second, while the editor subscribes.
        Profiler "profiler" (ProfileSample);
    }
}

/// The bridge's types for the editor's TypeScript (`BridgeRequests`, `BridgeEvents` and every shape they carry),
/// as the wire has them: an `Option` field is left out when it's none, never sent as `null`, so its type is the
/// value's alone (`path?: string`), not specta's `string | null`. Handed to tauri-specta (`Builder::types`).
#[cfg(feature = "specta")]
pub fn typescript_types() -> specta::Types {
    use specta::datatype::{DataType, Fields};

    fn values_only(fields: &mut Fields) {
        if let Fields::Named(named) = fields {
            for (_, field) in &mut named.fields {
                if let Some(DataType::Nullable(inner)) = &field.ty {
                    field.ty = Some((**inner).clone());
                }
            }
        }
    }

    let mut types = specta::Types::default();
    <BridgeRequests as specta::Type>::definition(&mut types);
    <BridgeEvents as specta::Type>::definition(&mut types);
    types.iter_mut(|named| {
        if !named.module_path.starts_with("netherforge_format") {
            return;
        }
        match &mut named.ty {
            Some(DataType::Struct(s)) => values_only(&mut s.fields),
            Some(DataType::Enum(e)) => {
                for (_, variant) in &mut e.variants {
                    values_only(&mut variant.fields);
                }
            }
            _ => {}
        }
    });
    types
}

/// Requests outside the core protocol live under a namespace (`bots/join`): what only some servers have.
pub const EXTENSIONS: &[&str] = &["bots"];

/// The notifications the editor sends the plugin: the debugger's channel is the only one.
pub const EDITOR_NOTIFICATIONS: &[&str] = &[Dap::NAME];

/// The request named `name`, core or extension.
pub fn request(name: &str) -> Option<MethodInfo> {
    requests().into_iter().find(|m| m.name == name)
}

// ---- encoding, by Kotlin's rules ----

pub(crate) fn is_default<T: Default + PartialEq>(value: &T) -> bool {
    *value == T::default()
}

pub(crate) fn yes() -> bool {
    true
}

pub(crate) fn is_true(value: &bool) -> bool {
    *value
}

/// A method's params as they're sent: none for a method that takes none.
pub fn encode_params<M: Method>(params: &M::Params) -> Option<serde_json::Value>
where
    M::Params: 'static,
{
    (!is_no_params::<M::Params>()).then(|| serde_json::to_value(params).expect("params serialize"))
}

/// A method's params as they're read: absent params read as `{}`, so a method whose params are all optional may
/// be sent without any.
pub fn decode_params<M: Method>(
    json: Option<&serde_json::Value>,
) -> Result<M::Params, serde_json::Error> {
    match json {
        Some(value) => M::Params::deserialize(value),
        None => M::Params::deserialize(&serde_json::Value::Object(Default::default())),
    }
}

/// A method's answer as it's sent: `null` for one that answers nothing.
pub fn encode_result<M: Method>(result: &M::Result) -> serde_json::Value {
    serde_json::to_value(result).expect("results serialize")
}

/// A method's answer as it's read.
pub fn decode_result<M: Method>(json: &serde_json::Value) -> Result<M::Result, serde_json::Error>
where
    M::Result: 'static,
{
    if is_unit::<M::Result>() {
        return M::Result::deserialize(&serde_json::Value::Null);
    }
    M::Result::deserialize(json)
}

/// A stream's notification params: `{"items": [...]}`.
pub fn encode_batch<S: Stream>(items: &[S::Item]) -> serde_json::Value {
    serde_json::json!({ ITEMS: serde_json::to_value(items).expect("items serialize") })
}

/// A stream's items from its notification's params; none when it carries none.
pub fn decode_batch<S: Stream>(
    json: Option<&serde_json::Value>,
) -> Result<Vec<S::Item>, serde_json::Error> {
    match json.and_then(|j| j.get(ITEMS)) {
        Some(items) => Vec::<S::Item>::deserialize(items),
        None => Ok(Vec::new()),
    }
}

// ---- plugin → editor ----

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct HelloParams {
    /// The plugin's random nonce for this connection (hex), which the editor's proof covers.
    pub nonce: String,
    /// `PROTOCOL` of the plugin's build.
    pub protocol: i32,
    pub plugin_version: String,
    pub minecraft: String,
    /// Absolute path of the project directory the plugin loaded.
    pub project: String,
}

/// The editor's answer to a hello of its `protocol`: a nonce of its own and its proof of the token over both
/// nonces, which the plugin checks before answering with `authenticate`.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
pub struct HelloResult {
    pub protocol: i32,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub nonce: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub proof: Option<String>,
}

/// The plugin's proof of the token over both nonces, once it has checked the editor's.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
pub struct AuthenticateParams {
    pub proof: String,
}

/// The problems the plugin found loading the project; replaces any earlier set.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
pub struct Problems {
    pub problems: Vec<Problem>,
}

/// Live state worth showing in the editor's status bar. Sent on change, at most once a second.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
pub struct Status {
    pub players: Vec<String>,
    pub instances: i32,
}

/// What the console stream carries, in the order it happened.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum ConsoleEntry {
    /// A line of runtime output. `source` is set when it came from a script.
    Log {
        level: LogLevel,
        message: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        source: Option<SourceRef>,
    },
    /// A Lua error, located in the project so the editor can open it.
    ScriptError {
        message: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        source: Option<SourceRef>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        traceback: Option<String>,
    },
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "lowercase")]
pub enum LogLevel {
    Debug,
    Info,
    Warn,
    Error,
}

/// A place in the project: `centities/tower/root.lua`, line 12.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
pub struct SourceRef {
    pub file: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub line: Option<i32>,
}

// ---- the profiler ----

/// One batch of the profiler stream: a run of server ticks, every one of them measured exactly (not sampled).
/// Sent about once a second while the editor subscribes.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
pub struct ProfileSample {
    pub ticks: Vec<ProfileTick>,
    /// Every script scope that ran over these ticks, by its own time.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub scopes: Vec<ScopeTime>,
    /// Every function the runtime called into over these ticks (a handler, a timer, a task's resume…), by its
    /// own time.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub handlers: Vec<HandlerTime>,
}

/// One server tick, as NetherForge spent it. Times are nanoseconds.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
pub struct ProfileTick {
    pub tick: i64,
    /// NetherForge's whole tick.
    pub nanos: i64,
    /// Each step of the tick, by name, in the order they run (`timers`, `async`, `events`, `world`…): what
    /// `nanos` is made of.
    pub phases: IndexMap<String, i64>,
    /// What scripts' own code took, wherever in the tick it ran.
    #[serde(default, skip_serializing_if = "is_default")]
    pub scripts: i64,
    /// The scopes that took the most this tick, the most first (at most three).
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub top: Vec<ScopeTime>,
    /// What the steps are made of, by path: each service's share of a step (`world/centities`) and the parts a
    /// service times itself (`world/centities/sync`). A part's time is inside its parent's.
    #[serde(default, skip_serializing_if = "IndexMap::is_empty")]
    pub parts: IndexMap<String, i64>,
}

/// A script scope's own time: a module, or one centity instance's, menu window's, dialog's or item's script
/// (`centity tower 1a2b3c4d`).
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
pub struct ScopeTime {
    pub scope: String,
    pub nanos: i64,
    pub calls: i32,
    /// Its most expensive tick.
    #[serde(default, skip_serializing_if = "is_default")]
    pub max: i64,
}

fn one() -> i32 {
    1
}

fn is_one(value: &i32) -> bool {
    *value == 1
}

/// The own time of one function the runtime calls: `kind` is the event a handler is for (`tick`,
/// `player_join`), or `load` (a script's body), `timer`, `task`, `command`, `complete`, `goal`, `callback`.
/// `source` is where the function starts (none when it isn't project code).
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
pub struct HandlerTime {
    /// The script it belongs to, as logs name it (`centity tower`, `module shop`).
    pub script: String,
    pub kind: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub source: Option<SourceRef>,
    pub calls: i32,
    pub nanos: i64,
    /// Its slowest single call.
    pub max: i64,
    /// How many scopes ran it (each instance of a centity has its own).
    #[serde(default = "one", skip_serializing_if = "is_one")]
    pub scopes: i32,
}

/// Starts (`on`) or stops the profiler stream.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
pub struct ProfilerSubscribeParams {
    pub on: bool,
}

// ---- editor → plugin ----

/// Reload these project-relative paths. The plugin maps each path to the resource that owns it and reloads that
/// resource once.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
pub struct ReloadParams {
    pub paths: Vec<String>,
}

/// Spawn a centity in front of `player` (or the first online player).
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
pub struct SpawnParams {
    pub centity: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub player: Option<String>,
}

/// Run a server console command.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
pub struct CommandParams {
    pub line: String,
}

/// Play particle effect `effect` three blocks in front of `player`'s eyes, facing them (or the first online
/// player's, or at the default world's spawn), owned by the runtime rather than a script. `loop` overrides the
/// file's.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
pub struct PlayParticleEffectParams {
    pub effect: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub player: Option<String>,
    #[serde(rename = "loop", default, skip_serializing_if = "Option::is_none")]
    pub looped: Option<bool>,
}

/// Where an online player stands, to the block: `player` (a name or UUID), or the first online when none.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
pub struct PlayerPositionParams {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub player: Option<String>,
}

/// Save the box from `from` to `to` (both corners included, in any order) in `world` as a structure file, with
/// the entities in it when `entities`, into the answer rather than a file: the editor writes it into the project.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
pub struct SaveStructureParams {
    pub world: String,
    pub from: BlockPos,
    pub to: BlockPos,
    #[serde(default, skip_serializing_if = "is_default")]
    pub entities: bool,
}

/// Save `world` and flush it to disk, for the editor to copy as a map.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
pub struct SaveWorldParams {
    pub world: String,
}

// ---- shared shapes ----

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
pub struct ReloadResult {
    pub resources: Vec<ReloadedResource>,
    /// The server must restart for the change to take effect: something it learns only as it starts (the
    /// start-up datapack: advancements) is no longer what it started with.
    #[serde(default, skip_serializing_if = "is_default")]
    pub restart: bool,
}

/// One resource a reload touched, named as format's `classify` names a path: its package (by its namespace),
/// kind and id. A whole package reloaded (its `netherforge.json` changed) has neither kind nor id.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
pub struct ReloadedResource {
    #[serde(rename = "package")]
    pub pkg: String,
    /// The kind's id in format's registry (`centity`, `module`, `pack`…); none for the whole package.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub kind: Option<String>,
    /// The resource's id in its package; none for the whole package.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub id: Option<String>,
    pub ok: bool,
    /// What kept running on the new version: live instances moved onto the new definition for a centity; open
    /// windows kept for a menu; players the rebuilt resource pack was sent to for a pack.
    #[serde(default, skip_serializing_if = "is_default")]
    pub reattached: i32,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub problems: Vec<Problem>,
    /// For a pack: the resource pack the plugin built from every pack in the project.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub pack: Option<PackBuild>,
}

impl ReloadedResource {
    /// How a person reads it: `centity:tower`, or the package's namespace for the whole package.
    pub fn label(&self) -> String {
        match (&self.kind, &self.id) {
            (Some(kind), Some(id)) => format!("{kind}:{id}"),
            _ => self.pkg.clone(),
        }
    }
}

/// One build of the project's resource pack.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
pub struct PackBuild {
    /// Lowercase hex SHA-1 of the zip: changes only when the content does.
    pub sha1: String,
    /// Where clients fetch it, or none when the plugin isn't serving it.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub url: Option<String>,
    /// The zip's size in bytes.
    pub bytes: i32,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
pub struct InstanceInfo {
    pub uuid: String,
    pub centity: String,
    pub world: String,
    pub x: f64,
    pub y: f64,
    pub z: f64,
}

/// A block position.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
pub struct BlockPos {
    pub x: i32,
    pub y: i32,
    pub z: i32,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
pub struct PlayerPosition {
    pub player: String,
    pub world: String,
    pub x: i32,
    pub y: i32,
    pub z: i32,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
pub struct LoadedWorld {
    pub name: String,
    /// `normal`, `nether` or `end`.
    pub environment: String,
    /// The server's main world, which a project never unloads.
    pub main: bool,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
pub struct SavedStructure {
    /// The structure file (gzipped NBT, Minecraft's structure format), base64.
    pub nbt: String,
    /// Its size in blocks.
    pub size: BlockPos,
}

/// A saved world's files, as `/`-separated paths relative to the server's folder. Since Minecraft 26.1 every
/// world is a dimension of the main world's storage: `level` is that storage's `level.dat`, `dimension` the
/// world's own folder (`world/dimensions/minecraft/<name>`).
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
pub struct SavedWorld {
    pub level: String,
    pub dimension: String,
    pub main: bool,
    /// The world's spawn, which `level` holds only for the main world.
    pub spawn: WorldSpawn,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
pub struct WorldSpawn {
    pub x: i32,
    pub y: i32,
    pub z: i32,
    #[serde(default, skip_serializing_if = "is_default")]
    pub yaw: f64,
    #[serde(default, skip_serializing_if = "is_default")]
    pub pitch: f64,
}

// ---- settings ----

/// Every package's server-owner settings on the dev server, each with the value it runs with.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
pub struct ServerSettings {
    pub packages: Vec<PackageSettings>,
}

/// One package's settings: the project's own, or a dependency's.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
pub struct PackageSettings {
    pub namespace: String,
    /// The package's name, `netherforge.json`'s.
    pub name: String,
    /// Where the server keeps its owner's values for it, relative to the server's folder.
    pub file: String,
    /// By name, as `netherforge.json` declares them.
    pub settings: IndexMap<String, SettingState>,
}

/// A setting as the server runs it.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
pub struct SettingState {
    pub definition: SettingDef,
    /// What scripts read now: the owner's value, or the default.
    pub value: Json,
    /// Whether the owner set it; otherwise `value` is the default.
    #[serde(default, skip_serializing_if = "is_default")]
    pub set: bool,
    /// Why the value in the settings file wasn't used, when it couldn't be.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub problem: Option<String>,
}

/// Sets a setting of `namespace`'s; a `value` left out puts it back to its default.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
pub struct SetSettingParams {
    pub namespace: String,
    pub setting: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub value: Option<Json>,
}
