//! `nf.network` for a session (Kotlin's `Network`): the channels and maps its scripts named, what's on its way to the
//! hub and waiting for an answer, and what came in, handed to scripts as events. The connection is the link's, for
//! the plugin's life: a reload only says again what this session listens to.
//!
//! Once a tick it drains what the link heard (at most `messages-per-tick`), raises the events, answers requests,
//! delivers arrivals, and tells the hub what it listens to when that changed (every channel a script named that has a
//! `message` or `request` handler, and every map a script got). Answers complete what scripts wait on (their values
//! reach them at the next tick boundary); a lost connection fails every one still waiting.

use std::cell::RefCell;
use std::collections::HashMap;
use std::rc::Rc;
use std::sync::mpsc;

use indexmap::{IndexMap, IndexSet};
use mlua::{Lua, Value};
use netherforge_api::codec::Codec;
use netherforge_api::handles::{self, Handle};
use netherforge_api::{ApiError, ApiResult, codecs, shapes};
use netherforge_format::Problem;
use netherforge_luau::ScopeId;
use netherforge_platform::{Platform, PlayerRef, Uuid};

use super::link::{Mode, NetworkLink, Shutdown};
use super::protocol::{
    self, Access, HubFrame, Lifecycle, MapOp, ModerationAction, PlayerState, Sanction, ServerFrame,
};
use super::{Inbound, NetworkConfig};
use crate::Room;
use crate::events::{parse_uuid, uuid_text};
use crate::http::{Reply, RequestRate};
use crate::scripts::{Scripts, Target};
use crate::session::Service;
use crate::shared::Shared;

pub const TICKS_PER_SECOND: u64 = 20;
pub const MILLIS_PER_TICK: i64 = 50;
pub const SEND_TIMEOUT_SECONDS: u64 = 30;
/// How long a send may wait for room, at most (the hub's limit).
pub const MAX_WAIT_SECONDS: f64 = 600.0;
/// How long `/nf shutdown` gives the hub to move the server's players before it stops anyway.
pub const EVACUATE_TICKS: u64 = 15 * TICKS_PER_SECOND;
/// How long a player sent here may take to arrive before what was sent with them is let go.
pub const ARRIVAL_TICKS: u64 = 60 * TICKS_PER_SECOND;
const WARN_EVERY_TICKS: u64 = 60 * TICKS_PER_SECOND;

fn err(message: impl Into<String>) -> ApiError {
    ApiError(message.into())
}

/// What a map's write answers: a boolean (`set`, `delete`, `insert`) or the new total (`add`).
#[derive(Clone, Debug)]
pub enum Written {
    Done(bool),
    Total(f64),
}

/// Something waiting for the hub's answer: its sender, its deadline (a tick), and what a timeout says.
struct Waiting<T> {
    send: mpsc::Sender<Result<T, String>>,
    deadline: u64,
    what: String,
}

impl<T> Waiting<T> {
    fn fail(self, why: &str) {
        let _ = self.send.send(Err(why.to_owned()));
    }
}

/// A map's copy on this server.
#[derive(Default)]
pub struct Mirror {
    pub ready: bool,
    pub entries: IndexMap<String, serde_json::Value>,
}

/// What a waiting send shows while it waits: the script's function, or its players' action bars.
struct OnWait {
    scope: Option<ScopeId>,
    callback: Option<mlua::Function>,
    players: Vec<Uuid>,
    target: String,
}

/// The session's side of the network, in `Shared::network`.
#[derive(Default)]
pub struct Network {
    /// The plugin's link, while a session runs.
    pub link: Option<Rc<NetworkLink>>,
    pub config: NetworkConfig,
    channels: IndexSet<String>,
    pub mirrors: IndexMap<String, Mirror>,
    requests: HashMap<i64, Waiting<Option<serde_json::Value>>>,
    writes: HashMap<i64, (MapOp, Waiting<Written>)>,
    sends: HashMap<i64, Waiting<String>>,
    moderations: HashMap<i64, Waiting<bool>>,
    showing: HashMap<i64, OnWait>,
    holds: HashMap<i64, ScopeId>,
    next_hold: i64,
    heard: Option<Lifecycle>,
    empty_since: Option<u64>,
    rate: Option<RequestRate>,
    listened: Option<(Vec<String>, Vec<String>)>,
    maps_changed: bool,
    joined_at: HashMap<Uuid, u64>,
    warned: HashMap<String, u64>,
    pub sent_by_package: HashMap<String, u64>,
    pub heard_by_package: HashMap<String, u64>,
    /// What the session should do next: stop the server (the end of `/nf shutdown`).
    pub stop_server: Option<String>,
    /// The packages that declare `requires.network`, and the project's namespace.
    declaring: Vec<String>,
    home: Option<String>,
}

impl Network {
    pub fn link(&self) -> Option<Rc<NetworkLink>> {
        self.link.clone()
    }

    fn mode(&self) -> Mode {
        self.link.as_ref().map_or(Mode::Off, |l| l.mode)
    }

    fn connected(&self) -> bool {
        self.link.as_ref().is_some_and(|l| l.connected())
    }

    fn send(&self, frame: ServerFrame) -> bool {
        self.link.as_ref().is_some_and(|l| l.send(frame))
    }

    fn next_id(&self) -> i64 {
        self.link.as_ref().map_or(0, |l| l.next_id())
    }

    fn not_connected(&self) -> String {
        match self.mode() {
            Mode::Off => {
                "this server isn't part of a network (network.enabled in config.yml)".into()
            }
            _ => "this server isn't connected to the network's hub".into(),
        }
    }

    fn warn_once(&mut self, platform: &dyn Platform, now: u64, key: &str, message: String) {
        if self
            .warned
            .get(key)
            .is_some_and(|last| now.saturating_sub(*last) < WARN_EVERY_TICKS)
        {
            return;
        }
        self.warned.insert(key.into(), now);
        platform.log().warn(message);
    }

    /// Whether `namespace` may send one more this minute; says so in the log when it may not.
    fn allowed(&mut self, platform: &dyn Platform, now: u64, namespace: &str) -> bool {
        let per_minute = self.config.messages_per_minute;
        if self
            .rate
            .get_or_insert_with(|| RequestRate::new(per_minute))
            .try_start(namespace)
        {
            *self.sent_by_package.entry(namespace.into()).or_insert(0) += 1;
            return true;
        }
        self.warn_once(
            platform,
            now,
            &format!("rate:{namespace}"),
            format!(
                "package \"{namespace}\" sent more than {per_minute} times to the network in a minute, the server's limit (network.messages-per-minute in config.yml); what's over it isn't sent"
            ),
        );
        false
    }

    fn refusal(&mut self, platform: &dyn Platform, now: u64, namespace: &str) -> Option<String> {
        if !self.connected() {
            return Some(self.not_connected());
        }
        if !self.allowed(platform, now, namespace) {
            return Some(format!(
                "package \"{namespace}\" is over the server's limit of {} a minute (network.messages-per-minute in config.yml)",
                self.config.messages_per_minute
            ));
        }
        None
    }

    fn timeout(&self, now: u64) -> u64 {
        now + self.config.request_timeout_seconds * TICKS_PER_SECOND
    }

    /// A map's copy, made (and listened for) the first time a script gets it.
    pub fn mirror(&mut self, full: &str) -> &mut Mirror {
        if !self.mirrors.contains_key(full) {
            self.maps_changed = true;
        }
        self.mirrors.entry(full.into()).or_default()
    }

    /// `player`'s ban or mute now (`map` says which), as this server's copy has it.
    pub fn sanction(&self, map: &str, player: &str) -> Option<Sanction> {
        let value = self.mirrors.get(map)?.entries.get(player)?;
        let sanction: Sanction = serde_json::from_value(value.clone()).ok()?;
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map_or(0, |d| d.as_millis() as i64);
        sanction.until.is_none_or(|u| u > now).then_some(sanction)
    }

    /// What a muted player is told when their chat is held back; none when they aren't muted.
    pub fn muted(&self, player: Uuid) -> Option<String> {
        let mute = self.sanction(protocol::MUTES, &uuid_text(&player))?;
        Some(format!(
            "<red>You're muted on this network{}",
            mute.reason.map(|r| format!(": {r}")).unwrap_or_default()
        ))
    }

    fn fail_all(&mut self, why: &str) {
        for (_, waiting) in self.requests.drain() {
            waiting.fail(why);
        }
        for (_, waiting) in self.sends.drain() {
            waiting.fail(why);
        }
        for (_, waiting) in self.moderations.drain() {
            waiting.fail(why);
        }
        for (_, (_, waiting)) in self.writes.drain() {
            waiting.fail(why);
        }
    }
}

/// `nf.network`'s calls, as one context: the session's network and the scripts.
#[derive(Clone)]
pub struct NetworkContext {
    pub shared: Rc<Shared>,
    pub scripts: Scripts,
}

impl NetworkContext {
    fn platform(&self) -> &dyn Platform {
        &*self.shared.platform
    }

    fn now(&self) -> u64 {
        self.scripts.tick_count()
    }

    fn lua(&self) -> Lua {
        self.scripts.host().lua().clone()
    }

    pub fn link(&self) -> Option<Rc<NetworkLink>> {
        self.shared.network.borrow().link()
    }

    /// The package whose code `scope` runs.
    pub fn namespace(&self, scope: ScopeId) -> String {
        self.scripts
            .namespace(scope)
            .unwrap_or_else(|| self.shared.names.borrow().home.clone())
    }

    /// The running script's package, for a handle's method.
    pub fn calling(&self) -> String {
        match self.scripts.host().running_scope() {
            Some(scope) => self.namespace(scope),
            None => self.shared.names.borrow().home.clone(),
        }
    }

    fn target(&self, handle: Handle) -> Option<Target> {
        netherforge_api::handle::push_any(self.scripts.host().lua(), &handle)
            .ok()
            .map(Target::Handle)
    }

    // ---- names -------------------------------------------------------------------

    /// `name` (a channel's or map's, as `namespace`'s script wrote it) in full: bare is the package's own, `ns:name`
    /// one of a package it depends on.
    pub fn resolve(&self, namespace: &str, name: &str, what: &str) -> ApiResult<String> {
        let (owner, bare) = name.split_once(':').unwrap_or((namespace, name));
        if !protocol::is_name(bare) {
            return Err(err(format!(
                "{what}: \"{name}\" isn't a name: lowercase letters, digits and _ . - /, at most 64 characters (and a namespace before a :)"
            )));
        }
        if owner != namespace
            && !self
                .shared
                .names
                .borrow()
                .namespaces
                .get(namespace)
                .is_some_and(|n| n.dependencies.contains(owner))
        {
            return Err(err(format!(
                "{what}: package \"{namespace}\" doesn't depend on \"{owner}\", so it can't use \"{name}\""
            )));
        }
        Ok(format!("{owner}:{bare}"))
    }

    /// `full` as package `reader` writes it: bare for its own.
    pub fn spell(full: &str, reader: &str) -> String {
        match full.split_once(':') {
            Some((ns, bare)) if ns == reader => bare.into(),
            _ => full.into(),
        }
    }

    // ---- values ------------------------------------------------------------------

    /// A script's value as JSON (the `data()` codec), at most the hub's limit: anything else is an error naming
    /// `what`. Nil is none.
    pub fn json(&self, value: &Value, what: &str) -> ApiResult<Option<serde_json::Value>> {
        if value.is_nil() {
            return Ok(None);
        }
        let host = self.scripts.host();
        let encoded = netherforge_luau::json::encode(
            &host,
            value,
            what,
            "written as JSON",
            &netherforge_api::handle::SavedHandles,
        )
        .map_err(|e| err(format!("{e:?}")))?;
        if let Some(problem) = encoded.problems.into_iter().next() {
            return Err(err(problem));
        }
        let text = encoded
            .text
            .ok_or_else(|| err(format!("{what} can't be written as JSON")))?;
        let json: serde_json::Value =
            serde_json::from_str(&text).map_err(|e| err(e.to_string()))?;
        self.checked(&json, what)?;
        Ok(Some(json))
    }

    /// `value` as JSON for the network, which has no nil to send.
    pub fn required(&self, value: &Value, what: &str) -> ApiResult<serde_json::Value> {
        self.json(value, what)?
            .ok_or_else(|| err(format!("bad argument '{what}' (a value expected, got nil)")))
    }

    fn checked(&self, json: &serde_json::Value, what: &str) -> ApiResult<()> {
        let size = protocol::size(Some(json));
        let limit = self.link().map_or(protocol::DEFAULT_MAX_VALUE_BYTES, |l| {
            l.state.borrow().max_value_bytes
        });
        if size > limit as usize {
            return Err(err(format!(
                "{what} is {size} bytes as JSON, over the network's limit of {limit} (max-value-bytes in the proxy's hub.properties)"
            )));
        }
        Ok(())
    }

    /// A JSON value as a script's.
    pub fn to_lua(lua: &Lua, json: Option<&serde_json::Value>) -> mlua::Result<Value> {
        match json {
            None | Some(serde_json::Value::Null) => Ok(Value::Nil),
            Some(json) => {
                netherforge_luau::json::to_lua(lua, json, &netherforge_api::handle::SavedHandles)
            }
        }
    }

    // ---- grants ------------------------------------------------------------------

    /// An error when the hub has said this server may not do `access` on `full`.
    pub fn forbid(&self, full: &str, access: Access, what: &str) -> ApiResult<()> {
        let Some(link) = self.link() else {
            return Ok(());
        };
        let state = link.state.borrow();
        if !state.connected || state.grants.allows(full, access) {
            return Ok(());
        }
        let verb = match access {
            Access::Read => "listen to",
            Access::Write => "write to",
            Access::Request => "ask on",
            Access::Answer => "answer on",
        };
        Err(err(format!(
            "{what}: network.forbidden: this server's credential ({}) may not {verb} \"{full}\": the network's owner shares it in the hub's credentials or hub.properties (nf.network.server_role() says what this server may do)",
            state.grants.role
        )))
    }

    // ---- what scripts send -------------------------------------------------------

    pub fn channel(&self, full: String) -> handles::NetworkChannel {
        self.shared
            .network
            .borrow_mut()
            .channels
            .insert(full.clone());
        handles::NetworkChannel { name: full }
    }

    pub fn publish(
        &self,
        namespace: &str,
        channel: &str,
        value: serde_json::Value,
        server: Option<String>,
        group: Option<String>,
    ) -> ApiResult<bool> {
        self.forbid(channel, Access::Write, "channel:publish")?;
        let now = self.now();
        let mut network = self.shared.network.borrow_mut();
        if !network.connected() || !network.allowed(self.platform(), now, namespace) {
            return Ok(false);
        }
        Ok(network.send(ServerFrame::Publish {
            channel: channel.into(),
            value,
            to: server,
            group,
        }))
    }

    fn reply<T: 'static>(receive: mpsc::Receiver<Result<T, String>>) -> Reply<T> {
        Reply(receive)
    }

    /// Asks `server` on `channel`, or one instance of `group`, or an admin client.
    pub fn request(
        &self,
        namespace: &str,
        channel: &str,
        server: Option<String>,
        value: serde_json::Value,
        group: Option<String>,
        admin: Option<String>,
    ) -> ApiResult<Reply<Option<serde_json::Value>>> {
        self.forbid(channel, Access::Request, "channel:request")?;
        let (send, receive) = mpsc::channel();
        let now = self.now();
        let mut network = self.shared.network.borrow_mut();
        if let Some(why) = network.refusal(self.platform(), now, namespace) {
            let _ = send.send(Err(why));
            return Ok(Self::reply(receive));
        }
        let id = network.next_id();
        let who = server
            .clone()
            .or_else(|| admin.as_ref().map(|a| format!("the admin client {a}")))
            .unwrap_or_else(|| format!("no instance of {}", group.clone().unwrap_or_default()));
        let deadline = network.timeout(now);
        network.requests.insert(
            id,
            Waiting {
                send: send.clone(),
                deadline,
                what: format!("{who} didn't answer on \"{channel}\""),
            },
        );
        let frame = ServerFrame::Request {
            id,
            channel: channel.into(),
            to: server,
            value,
            group,
            admin,
        };
        // A local hub answers within the send: nothing is borrowed while it does.
        let link = network.link();
        drop(network);
        if !link.is_some_and(|l| l.send(frame)) {
            let mut network = self.shared.network.borrow_mut();
            network.requests.remove(&id);
            let _ = send.send(Err(network.not_connected()));
        }
        Ok(Self::reply(receive))
    }

    /// A map write: `set` and `delete` answer whether it went (none: nothing was sent), `add` and `insert` with
    /// the hub's answer.
    #[allow(clippy::too_many_arguments)]
    pub fn write(
        &self,
        namespace: &str,
        map: &str,
        key: &str,
        op: MapOp,
        value: Option<serde_json::Value>,
        amount: Option<f64>,
        expires: Option<i64>,
    ) -> ApiResult<Option<Reply<Written>>> {
        if !protocol::is_key(key) {
            return Err(err(format!(
                "bad argument 'key' (1 to {} characters, none a control character)",
                protocol::MAX_KEY_LENGTH
            )));
        }
        if let Some(expires) = expires.filter(|e| *e < 1) {
            return Err(err(format!(
                "bad argument 'options.expires' (at least 1 tick expected, got {expires})"
            )));
        }
        self.forbid(map, Access::Write, &format!("map:{}", op.word()))?;
        let waits = matches!(op, MapOp::Add | MapOp::Insert);
        let (send, receive) = mpsc::channel();
        let now = self.now();
        let mut network = self.shared.network.borrow_mut();
        if let Some(why) = network.refusal(self.platform(), now, namespace) {
            if !waits {
                return Ok(None);
            }
            let _ = send.send(Err(why));
            return Ok(Some(Self::reply(receive)));
        }
        let id = network.next_id();
        let deadline = network.timeout(now);
        network.writes.insert(
            id,
            (
                op,
                Waiting {
                    send: send.clone(),
                    deadline,
                    what: "the network's hub didn't answer".into(),
                },
            ),
        );
        let frame = ServerFrame::MapWrite {
            id,
            map: map.into(),
            key: key.into(),
            op,
            value,
            amount,
            expires_millis: expires.map(|e| e * MILLIS_PER_TICK),
        };
        let link = network.link();
        drop(network);
        if !link.is_some_and(|l| l.send(frame)) {
            let mut network = self.shared.network.borrow_mut();
            network.writes.remove(&id);
            if !waits {
                return Ok(None);
            }
            let _ = send.send(Err(network.not_connected()));
        }
        Ok(Some(Self::reply(receive)))
    }

    /// Sends `players` (UUIDs) together to `server`, or to an instance of `group` the hub picks.
    #[allow(clippy::too_many_arguments)]
    pub fn send(
        &self,
        namespace: &str,
        players: Vec<String>,
        server: Option<String>,
        data: Option<serde_json::Value>,
        group: Option<String>,
        balance: Option<protocol::Balance>,
        wait_seconds: Option<f64>,
        on_wait: Option<(ScopeId, mlua::Function)>,
    ) -> Reply<String> {
        let (send, receive) = mpsc::channel();
        let now = self.now();
        let mut network = self.shared.network.borrow_mut();
        if let Some(why) = network.refusal(self.platform(), now, namespace) {
            let _ = send.send(Err(why));
            return Reply(receive);
        }
        if let Some(link) = network.link() {
            let state = link.state.borrow();
            let here = state.name.clone();
            if !state.grants.trusted
                && players.iter().any(|p| {
                    state
                        .players
                        .get(p)
                        .and_then(|p| p.server.as_ref())
                        .is_some_and(|s| Some(s) != here.as_ref())
                })
            {
                let _ = send.send(Err(format!(
                    "network.forbidden: this server's credential ({}) may only send players who are on it",
                    state.grants.role
                )));
                return Reply(receive);
            }
        }
        let id = network.next_id();
        let wait = wait_seconds.map(|w| w.clamp(0.0, MAX_WAIT_SECONDS));
        let wait_ticks = (wait.unwrap_or(0.0) * TICKS_PER_SECOND as f64) as u64;
        network.sends.insert(
            id,
            Waiting {
                send: send.clone(),
                deadline: now + wait_ticks + SEND_TIMEOUT_SECONDS * TICKS_PER_SECOND,
                what: format!("the proxy didn't move them within {SEND_TIMEOUT_SECONDS} seconds"),
            },
        );
        if wait.is_some() {
            let here: Vec<Uuid> = players
                .iter()
                .filter_map(|p| parse_uuid(p))
                .filter(|u| self.platform().players().get(*u).is_some())
                .collect();
            let (scope, callback) = match on_wait {
                Some((scope, f)) => (Some(scope), Some(f)),
                None => (None, None),
            };
            network.showing.insert(
                id,
                OnWait {
                    scope,
                    callback,
                    players: here,
                    target: server.clone().or_else(|| group.clone()).unwrap_or_default(),
                },
            );
        }
        let frame = ServerFrame::Send {
            id,
            players,
            server,
            data,
            group,
            balance,
            wait_millis: wait.map(|w| (w * 1000.0) as i64),
        };
        let link = network.link();
        drop(network);
        if !link.is_some_and(|l| l.send(frame)) {
            let mut network = self.shared.network.borrow_mut();
            network.sends.remove(&id);
            network.showing.remove(&id);
            let _ = send.send(Err(network.not_connected()));
        }
        Reply(receive)
    }

    /// Bans, unbans, mutes or unmutes `player` (`name`) network-wide, as package `namespace` asks.
    pub fn moderate(
        &self,
        namespace: &str,
        action: ModerationAction,
        player: String,
        name: String,
        reason: Option<String>,
        until: Option<i64>,
    ) -> Reply<bool> {
        let (send, receive) = mpsc::channel();
        let now = self.now();
        let mut network = self.shared.network.borrow_mut();
        if let Some(why) = network.refusal(self.platform(), now, namespace) {
            let _ = send.send(Err(why));
            return Reply(receive);
        }
        if let Some(link) = network.link() {
            let grants = link.state.borrow().grants.clone();
            if !grants.moderates() {
                let _ = send.send(Err(format!(
                    "network.forbidden: this server's credential ({}) may not moderate the network (moderation=true on the hub)",
                    grants.role
                )));
                return Reply(receive);
            }
        }
        let id = network.next_id();
        let deadline = network.timeout(now);
        network.moderations.insert(
            id,
            Waiting {
                send: send.clone(),
                deadline,
                what: "the network's hub didn't answer".into(),
            },
        );
        let frame = ServerFrame::Moderate {
            id,
            action,
            player,
            name,
            reason,
            by: format!("package {namespace}"),
            until,
        };
        let link = network.link();
        drop(network);
        if !link.is_some_and(|l| l.send(frame)) {
            let mut network = self.shared.network.borrow_mut();
            network.moderations.remove(&id);
            let _ = send.send(Err(network.not_connected()));
        }
        Reply(receive)
    }

    pub fn report(
        &self,
        namespace: &str,
        player: String,
        name: String,
        reason: String,
        details: Option<serde_json::Value>,
        by: Option<String>,
    ) -> bool {
        let now = self.now();
        let mut network = self.shared.network.borrow_mut();
        if !network.connected() || !network.allowed(self.platform(), now, namespace) {
            return false;
        }
        let link = network.link();
        drop(network);
        link.is_some_and(|l| {
            l.send(ServerFrame::Report {
                player,
                name,
                reason,
                details,
                by,
            })
        })
    }

    /// A player online in the network by name (any case) or UUID.
    pub fn find_player(&self, player: &str) -> Option<PlayerState> {
        let link = self.link()?;
        let state = link.state.borrow();
        state
            .players
            .get(&player.to_lowercase())
            .or_else(|| {
                state
                    .players
                    .values()
                    .find(|p| p.name.eq_ignore_ascii_case(player))
            })
            .cloned()
    }

    // ---- this server's life ---------------------------------------------------------

    /// A hold on this server being ready, until it's released or `scope` ends.
    pub fn hold(&self, scope: ScopeId) -> handles::NetworkHold {
        let mut network = self.shared.network.borrow_mut();
        network.next_hold += 1;
        let id = network.next_hold;
        if network
            .link()
            .is_some_and(|l| l.lifecycle() == Lifecycle::Starting)
        {
            network.holds.insert(id, scope);
        }
        handles::NetworkHold { id }
    }

    pub fn release(&self, hold: &handles::NetworkHold) {
        self.shared.network.borrow_mut().holds.remove(&hold.id);
    }

    pub fn is_released(&self, hold: &handles::NetworkHold) -> bool {
        !self.shared.network.borrow().holds.contains_key(&hold.id)
    }

    /// Starts `/nf shutdown`: `after_empty`, it waits up to `timeout_seconds` for the server to empty first.
    pub fn shutdown(&self, after_empty: bool, timeout_seconds: u64) {
        let Some(link) = self.link() else { return };
        if link.state.borrow().shutdown.is_some() {
            return;
        }
        self.platform().log().info(format!(
            "Shutting down: draining{}",
            if after_empty {
                format!(", then waiting up to {timeout_seconds} seconds for the server to empty")
            } else {
                String::new()
            }
        ));
        link.begin_shutdown(Shutdown {
            after_empty,
            deadline: self.now() + timeout_seconds * TICKS_PER_SECOND,
            stopping_since: None,
        });
    }

    // ---- what came in ------------------------------------------------------------

    /// One tick: what came in, what's listened to, the server's life, timeouts and arrivals.
    pub fn tick(&self) {
        let Some(link) = self.link() else { return };
        let now = self.now();
        link.tick(now);
        let max = self.shared.network.borrow().config.messages_per_tick;
        let log = self.platform().log();
        let items = link.drain(max, now, &|m| log.info(m), &|m| log.warn(m));
        for item in items {
            self.received(&link, item);
        }
        if link.connected() {
            self.listen(&link);
        }
        self.life(&link, now);
        self.time_out(now);
        self.arrive(&link, now);
        if let Some(hub) = link.local() {
            for (id, message) in hub.take_kicks() {
                if let Some(uuid) = parse_uuid(&id) {
                    self.platform().players().kick(uuid, Some(message));
                }
            }
        }
    }

    fn raise_nf(&self, event: &str, payload: mlua::Result<Value>) {
        if let Ok(Value::Table(fields)) = payload {
            self.scripts.raise(&Target::Nf, event, fields);
        }
    }

    fn network_player(state: &PlayerState) -> shapes::NetworkPlayer {
        shapes::NetworkPlayer {
            id: state.id.clone(),
            name: state.name.clone(),
            server: state.server.clone(),
            proxy: state.proxy.clone(),
        }
    }

    fn received(&self, link: &NetworkLink, item: Inbound) {
        let lua = self.lua();
        match item {
            Inbound::Connected(welcome) => {
                self.shared.network.borrow_mut().listened = None;
                if self.scripts.listening(&Target::Nf, "network_connect") {
                    self.raise_nf(
                        "network_connect",
                        codecs::NetworkConnectEvent.push(
                            &lua,
                            shapes::NetworkConnectEvent {
                                server: welcome.server.clone(),
                            },
                        ),
                    );
                }
            }
            Inbound::Disconnected(why) => {
                self.shared.network.borrow_mut().fail_all(&format!(
                    "the connection to the network's hub was lost ({why})"
                ));
                self.raise_nf(
                    "network_disconnect",
                    codecs::NetworkDisconnectEvent
                        .push(&lua, shapes::NetworkDisconnectEvent { detail: why }),
                );
            }
            Inbound::Failed(_) => {}
            Inbound::Left(player) => self.raise_nf(
                "network_player_quit",
                codecs::NetworkPlayerEvent.push(
                    &lua,
                    shapes::NetworkPlayerEvent {
                        player: Self::network_player(&player),
                    },
                ),
            ),
            Inbound::Frame(frame) => self.frame(link, &lua, frame),
        }
    }

    fn heard_by(&self, full: &str) {
        *self
            .shared
            .network
            .borrow_mut()
            .heard_by_package
            .entry(protocol::namespace_of(full).into())
            .or_insert(0) += 1;
    }

    fn frame(&self, link: &NetworkLink, lua: &Lua, frame: HubFrame) {
        match frame {
            HubFrame::Message {
                channel,
                from,
                value,
            } => {
                if !self.shared.network.borrow().channels.contains(&channel) {
                    return;
                }
                self.heard_by(&channel);
                let handle = handles::NetworkChannel { name: channel };
                let Some(target) = self.target(Handle::NetworkChannel(handle.clone())) else {
                    return;
                };
                if !self.scripts.listening(&target, "message") {
                    return;
                }
                let Ok(value) = Self::to_lua(lua, Some(&value)) else {
                    return;
                };
                if let Ok(Value::Table(fields)) = codecs::NetworkMessageEvent.push(
                    lua,
                    shapes::NetworkMessageEvent {
                        channel: handle,
                        value,
                        server: from,
                    },
                ) {
                    self.scripts.raise(&target, "message", fields);
                }
            }
            HubFrame::Requested {
                id,
                channel,
                from,
                value,
            } => self.answer(link, lua, id, channel, from, value),
            HubFrame::Reply { id, value, error } => {
                let waiting = self.shared.network.borrow_mut().requests.remove(&id);
                if let Some(waiting) = waiting {
                    let _ = waiting.send.send(match error {
                        Some(e) => Err(e),
                        None => Ok(value.filter(|v| !v.is_null())),
                    });
                }
            }
            HubFrame::MapSnapshot { map, entries } => self.snapshot(lua, map, entries),
            HubFrame::MapChanged {
                map,
                key,
                value,
                from,
                cause,
            } => self.changed(lua, map, key, value, from, cause.word()),
            HubFrame::MapResult { id, value, error } => {
                let entry = self.shared.network.borrow_mut().writes.remove(&id);
                let Some((op, waiting)) = entry else { return };
                match error {
                    Some(error) => {
                        if matches!(op, MapOp::Set | MapOp::Delete) {
                            let now = self.now();
                            self.shared.network.borrow_mut().warn_once(
                                self.platform(),
                                now,
                                &format!("map:{error}"),
                                format!(
                                    "The network's hub refused a write to a shared map: {error}"
                                ),
                            );
                        }
                        let _ = waiting.send.send(Err(error));
                    }
                    None => {
                        let written = match op {
                            MapOp::Add => Written::Total(
                                value
                                    .as_ref()
                                    .and_then(serde_json::Value::as_f64)
                                    .unwrap_or(0.0),
                            ),
                            _ => Written::Done(
                                value
                                    .as_ref()
                                    .and_then(serde_json::Value::as_bool)
                                    .unwrap_or(false),
                            ),
                        };
                        let _ = waiting.send.send(Ok(written));
                    }
                }
            }
            HubFrame::SendWaiting {
                id,
                position,
                status,
            } => self.waited(id, position, status),
            HubFrame::ModerateResult { id, error } => {
                let waiting = self.shared.network.borrow_mut().moderations.remove(&id);
                if let Some(waiting) = waiting {
                    let _ = waiting.send.send(match error {
                        Some(e) => Err(e),
                        None => Ok(true),
                    });
                }
            }
            HubFrame::Drain { draining } => {
                self.platform().log().info(if draining {
                    "The network's admin API drains this server".into()
                } else {
                    "The network's admin API ends its draining".to_owned()
                });
                link.set_draining(draining);
            }
            HubFrame::SendResult { id, error, server } => {
                let (waiting, _shown) = {
                    let mut network = self.shared.network.borrow_mut();
                    (network.sends.remove(&id), network.showing.remove(&id))
                };
                if let Some(waiting) = waiting {
                    let _ = waiting.send.send(match error {
                        Some(e) => Err(e),
                        None => Ok(server.unwrap_or_default()),
                    });
                }
            }
            HubFrame::PlayerJoined { player } => self.raise_nf(
                "network_player_join",
                codecs::NetworkPlayerEvent.push(
                    lua,
                    shapes::NetworkPlayerEvent {
                        player: Self::network_player(&player),
                    },
                ),
            ),
            HubFrame::PlayerMoved { id, previous, .. } => {
                let player = link.state.borrow().players.get(&id).cloned();
                if let (Some(player), Some(previous)) = (player, previous) {
                    self.raise_nf(
                        "network_player_switch",
                        codecs::NetworkPlayerSwitchEvent.push(
                            lua,
                            shapes::NetworkPlayerSwitchEvent {
                                player: Self::network_player(&player),
                                previous,
                            },
                        ),
                    );
                }
            }
            HubFrame::Server { server } => {
                if Some(&server.name) != link.name().as_ref() {
                    let event = if server.linked {
                        "network_server_connect"
                    } else {
                        "network_server_disconnect"
                    };
                    self.raise_nf(
                        event,
                        codecs::NetworkServerEvent.push(
                            lua,
                            shapes::NetworkServerEvent {
                                server: server.name,
                            },
                        ),
                    );
                }
            }
            HubFrame::Problem { message } => {
                let now = self.now();
                self.shared.network.borrow_mut().warn_once(
                    self.platform(),
                    now,
                    &format!("problem:{message}"),
                    format!("The network's hub refused something this server sent: {message}"),
                );
            }
            _ => {}
        }
    }

    /// A send waits at its place: its script hears it, or its players see it.
    fn waited(&self, id: i64, position: i32, status: Option<String>) {
        let shown = {
            let network = self.shared.network.borrow();
            let Some(shown) = network.showing.get(&id) else {
                return;
            };
            (
                shown.scope,
                shown.callback.clone(),
                shown.players.clone(),
                shown.target.clone(),
            )
        };
        if let (Some(scope), Some(callback)) = (shown.0, shown.1) {
            if let Err(failure) =
                self.scripts
                    .call_kept(scope, &callback, (i64::from(position), status))
            {
                let now = self.now();
                self.shared.network.borrow_mut().warn_once(
                    self.platform(),
                    now,
                    "on_wait",
                    format!("A send's on_wait failed: {failure}"),
                );
            }
            return;
        }
        let text = match status {
            Some(status) => format!("<gray>{status}"),
            None => format!(
                "<gray>Waiting for <yellow>{}</yellow>… <white>#{position}",
                shown.3
            ),
        };
        for player in shown.2 {
            self.platform().players().actionbar(player, text.clone());
        }
    }

    fn answer(
        &self,
        link: &NetworkLink,
        lua: &Lua,
        id: i64,
        channel: String,
        from: String,
        value: serde_json::Value,
    ) {
        self.heard_by(&channel);
        let reply = |value: Option<serde_json::Value>, error: Option<String>| {
            link.send(ServerFrame::Reply { id, value, error });
        };
        let here = link.name().unwrap_or_default();
        let handle = handles::NetworkChannel {
            name: channel.clone(),
        };
        let target = self.target(Handle::NetworkChannel(handle.clone()));
        let known = self.shared.network.borrow().channels.contains(&channel);
        let Some(target) = target.filter(|t| known && self.scripts.listening(t, "request")) else {
            return reply(
                None,
                Some(format!(
                    "nothing on {here} answers requests on \"{channel}\""
                )),
            );
        };
        let Ok(value) = Self::to_lua(lua, Some(&value)) else {
            return reply(None, Some(format!("{here} couldn't read the request")));
        };
        let Ok(Value::Table(fields)) = codecs::NetworkRequestEvent.push(
            lua,
            shapes::NetworkRequestEvent {
                channel: handle,
                value,
                server: from,
                reply: Value::Nil,
            },
        ) else {
            return;
        };
        let delivered = self.scripts.raise(&target, "request", fields);
        let answer: Value = delivered.fields.get("reply").unwrap_or(Value::Nil);
        let json = match self.json(&answer, "event.reply") {
            Ok(json) => json,
            Err(ApiError(why)) => {
                let limit = link.state.borrow().max_value_bytes;
                return reply(
                    None,
                    Some(if why.contains("over the network's limit") {
                        format!(
                            "{here}'s answer is {} bytes as JSON, over the network's limit of {limit}",
                            why.split(" is ")
                                .nth(1)
                                .and_then(|r| r.split(' ').next())
                                .unwrap_or("?")
                        )
                    } else {
                        format!("{here}'s answer can't be sent: {why}")
                    }),
                );
            }
        };
        reply(json, None);
    }

    fn map_handle(&self, map: &str) -> Option<(handles::NetworkMap, Target)> {
        let handle = handles::NetworkMap { name: map.into() };
        let target = self.target(Handle::NetworkMap(handle.clone()))?;
        Some((handle, target))
    }

    fn snapshot(
        &self,
        lua: &Lua,
        map: String,
        entries: serde_json::Map<String, serde_json::Value>,
    ) {
        let (was_ready, before) = {
            let mut network = self.shared.network.borrow_mut();
            let Some(mirror) = network.mirrors.get_mut(&map) else {
                return;
            };
            let before = std::mem::take(&mut mirror.entries);
            mirror.entries = entries.clone().into_iter().collect();
            let was = mirror.ready;
            mirror.ready = true;
            (was, before)
        };
        let Some((handle, target)) = self.map_handle(&map) else {
            return;
        };
        if !was_ready {
            if let Ok(Value::Table(fields)) =
                codecs::NetworkMapReadyEvent.push(lua, shapes::NetworkMapReadyEvent { map: handle })
            {
                self.scripts.raise(&target, "ready", fields);
            }
            return;
        }
        // Again, after a reconnect: what changed meanwhile is heard as changes, by no one in particular.
        let mut keys: Vec<String> = before.keys().cloned().collect();
        for key in entries.keys() {
            if !before.contains_key(key) {
                keys.push(key.clone());
            }
        }
        for key in keys {
            let previous = before.get(&key);
            let value = entries.get(&key);
            if previous == value {
                continue;
            }
            let cause = if value.is_none() { "delete" } else { "set" };
            self.raise_change(lua, &handle, &target, key, value, previous, None, cause);
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn raise_change(
        &self,
        lua: &Lua,
        handle: &handles::NetworkMap,
        target: &Target,
        key: String,
        value: Option<&serde_json::Value>,
        previous: Option<&serde_json::Value>,
        from: Option<String>,
        cause: &str,
    ) {
        if !self.scripts.listening(target, "change") {
            return;
        }
        let (Ok(value), Ok(previous)) = (Self::to_lua(lua, value), Self::to_lua(lua, previous))
        else {
            return;
        };
        if let Ok(Value::Table(fields)) = codecs::NetworkMapChangeEvent.push(
            lua,
            shapes::NetworkMapChangeEvent {
                map: handle.clone(),
                key,
                value,
                previous,
                server: from,
                cause: cause.into(),
            },
        ) {
            self.scripts.raise(target, "change", fields);
        }
    }

    fn changed(
        &self,
        lua: &Lua,
        map: String,
        key: String,
        value: Option<serde_json::Value>,
        from: Option<String>,
        cause: &str,
    ) {
        let previous = {
            let mut network = self.shared.network.borrow_mut();
            let Some(mirror) = network.mirrors.get_mut(&map) else {
                return;
            };
            match &value {
                None => mirror.entries.shift_remove(&key),
                Some(v) => mirror.entries.insert(key.clone(), v.clone()),
            }
        };
        let Some((handle, target)) = self.map_handle(&map) else {
            return;
        };
        self.raise_change(
            lua,
            &handle,
            &target,
            key,
            value.as_ref(),
            previous.as_ref(),
            from,
            cause,
        );
    }

    /// Tells the hub what this session listens to, when it changed.
    fn listen(&self, link: &NetworkLink) {
        let grants = link.state.borrow().grants.clone();
        let (channels, maps) = {
            let network = self.shared.network.borrow();
            (
                network.channels.iter().cloned().collect::<Vec<_>>(),
                network.mirrors.keys().cloned().collect::<Vec<_>>(),
            )
        };
        let mut warnings = Vec::new();
        let channels: Vec<String> = channels
            .into_iter()
            .filter(|channel| {
                let Some(target) = self.target(Handle::NetworkChannel(handles::NetworkChannel {
                    name: channel.clone(),
                })) else {
                    return false;
                };
                let hears = self.scripts.listening(&target, "message");
                let answers = self.scripts.listening(&target, "request");
                let messages = hears && grants.allows(channel, Access::Read);
                let requests = answers && grants.allows(channel, Access::Answer);
                if !messages && !requests && (hears || answers) {
                    warnings.push((
                        format!("forbidden:{channel}"),
                        format!(
                            "network.forbidden: this server's credential ({}) may not listen to \"{channel}\", so its handlers there hear nothing",
                            grants.role
                        ),
                    ));
                }
                messages || requests
            })
            .collect();
        let maps: Vec<String> = maps
            .into_iter()
            .filter(|map| {
                let allowed = grants.allows(map, Access::Read);
                if !allowed {
                    warnings.push((
                        format!("forbidden:{map}"),
                        format!(
                            "network.forbidden: this server's credential ({}) may not read the map \"{map}\", so its copy stays empty",
                            grants.role
                        ),
                    ));
                }
                allowed
            })
            .collect();
        let now = self.now();
        {
            let mut network = self.shared.network.borrow_mut();
            for (key, message) in warnings {
                network.warn_once(self.platform(), now, &key, message);
            }
            let wanted = (channels.clone(), maps.clone());
            if network.listened.as_ref() == Some(&wanted) {
                return;
            }
            network.listened = Some(wanted);
            network.maps_changed = false;
        }
        if !link.send(ServerFrame::Listen { channels, maps }) {
            self.shared.network.borrow_mut().listened = None;
        }
    }

    /// Once a tick: ready when nothing holds it back, idle when empty long enough, and `/nf shutdown`'s steps.
    fn life(&self, link: &NetworkLink, now: u64) {
        // A session ticking is a project running, so `network.require-project` holds here.
        let (holds_empty, idle_after, started) = {
            let network = self.shared.network.borrow_mut();
            (
                network.holds.is_empty(),
                network.config.idle_after_seconds,
                link.created,
            )
        };
        if link.lifecycle() == Lifecycle::Starting && holds_empty {
            let millis = started.elapsed().as_millis() as i64;
            link.ready(millis);
            // Said only in a network, where whatever runs the servers waits for it (Kotlin says it everywhere).
            if link.mode != Mode::Off {
                self.platform().log().info(format!(
                    "Ready {:.1} seconds after the server's process started",
                    millis as f64 / 1000.0
                ));
            }
        }
        let state = link.lifecycle();
        let previous = self.shared.network.borrow_mut().heard.replace(state);
        if let Some(previous) = previous.filter(|p| *p != state) {
            self.raise_nf(
                "network_state",
                codecs::NetworkStateEvent.push(
                    &self.lua(),
                    shapes::NetworkStateEvent {
                        state: state.word().into(),
                        previous: previous.word().into(),
                    },
                ),
            );
        }
        let empty = self.shared.network.borrow().joined_at.is_empty();
        let idle = {
            let mut network = self.shared.network.borrow_mut();
            network.empty_since = if empty {
                Some(network.empty_since.unwrap_or(now))
            } else {
                None
            };
            let after = idle_after * TICKS_PER_SECOND;
            after > 0
                && network
                    .empty_since
                    .is_some_and(|since| now - since >= after)
        };
        link.set_idle(idle);
        let Some(plan) = link.state.borrow().shutdown.clone() else {
            return;
        };
        if link.lifecycle() != Lifecycle::Stopping {
            if plan.after_empty && !empty && now < plan.deadline {
                return;
            }
            self.platform().log().info(if empty {
                "Shutting down: the server is empty".into()
            } else {
                "Shutting down: sending its players to the group's fallback".to_owned()
            });
            link.stopping(now);
            return;
        }
        let since = plan.stopping_since.unwrap_or(now);
        if !empty && now - since < EVACUATE_TICKS && link.connected() {
            return;
        }
        link.state.borrow_mut().shutdown = None;
        self.shared.network.borrow_mut().stop_server = Some("NetherForge: /nf shutdown".into());
    }

    fn time_out(&self, now: u64) {
        let mut network = self.shared.network.borrow_mut();
        fn expire<T>(waiting: &mut HashMap<i64, Waiting<T>>, now: u64) {
            let late: Vec<i64> = waiting
                .iter()
                .filter(|(_, w)| w.deadline <= now)
                .map(|(id, _)| *id)
                .collect();
            for id in late {
                let w = waiting.remove(&id).expect("listed");
                let why = format!("{} within the time allowed", w.what);
                w.fail(&why);
            }
        }
        expire(&mut network.requests, now);
        expire(&mut network.sends, now);
        expire(&mut network.moderations, now);
        let late: Vec<i64> = network
            .writes
            .iter()
            .filter(|(_, (_, w))| w.deadline <= now)
            .map(|(id, _)| *id)
            .collect();
        for id in late {
            let (_, w) = network.writes.remove(&id).expect("listed");
            let why = format!("{} within the time allowed", w.what);
            w.fail(&why);
        }
    }

    /// Players sent here who are online now: their `arrive`, the tick after they joined at the earliest.
    fn arrive(&self, link: &NetworkLink, now: u64) {
        let arrivals: Vec<(String, super::link::Arrival)> = link
            .state
            .borrow()
            .arrivals
            .iter()
            .map(|(k, v)| (k.clone(), v.clone()))
            .collect();
        for (id, arrival) in arrivals {
            let player = parse_uuid(&id).filter(|u| self.platform().players().get(*u).is_some());
            let Some(player) = player else {
                if now.saturating_sub(arrival.tick) > ARRIVAL_TICKS {
                    link.state.borrow_mut().arrivals.remove(&id);
                }
                continue;
            };
            if self
                .shared
                .network
                .borrow()
                .joined_at
                .get(&player)
                .is_some_and(|at| *at >= now)
            {
                continue;
            }
            link.state.borrow_mut().arrivals.remove(&id);
            let lua = self.lua();
            let handle = handles::Player {
                id: uuid_text(&player),
            };
            let Some(target) = self.target(Handle::Player(handle.clone())) else {
                continue;
            };
            let Ok(data) = Self::to_lua(&lua, arrival.data.as_ref()) else {
                continue;
            };
            if let Ok(Value::Table(fields)) = codecs::PlayerArriveEvent.push(
                &lua,
                shapes::PlayerArriveEvent {
                    player: handle,
                    from: arrival.from,
                    data,
                },
            ) {
                self.scripts.raise_path(
                    &[(target, "arrive"), (Target::Nf, "player_arrive")],
                    fields,
                    false,
                );
            }
        }
    }
}

/// The session's network service: started with the session (the link's own start is once for the plugin's life),
/// ticked, and stopped with it.
pub struct NetworkService {
    pub shared: Rc<Shared>,
    pub context: RefCell<Option<NetworkContext>>,
}

impl Service for NetworkService {
    fn name(&self) -> &'static str {
        "network"
    }

    fn start(&self, room: &Room, project: &crate::session::Define) {
        let grants = crate::session::grants(project.project);
        {
            let mut network = self.shared.network.borrow_mut();
            network.declaring = grants
                .requires
                .iter()
                .filter(|(_, r)| r.as_ref().is_some_and(|r| r.network == Some(true)))
                .map(|(ns, _)| ns.clone())
                .collect();
            network.home = Some(grants.home.clone());
        }
        let context = NetworkContext {
            shared: self.shared.clone(),
            scripts: room.scripts().clone(),
        };
        let link = self.shared.network.borrow().link();
        if let Some(link) = link {
            let log = self.shared.platform.log();
            link.start(&|m| log.info(m));
            if link.mode != Mode::Off {
                // The network's bans and mutes: this server's copies, to enforce and to answer from.
                let mut network = self.shared.network.borrow_mut();
                network.mirror(protocol::BANS);
                network.mirror(protocol::MUTES);
            }
            // Everyone already here (a reload) is this server's in a local network.
            for player in self.shared.platform.players().online() {
                self.shared
                    .network
                    .borrow_mut()
                    .joined_at
                    .insert(player.uuid, 0);
                link.joined_here(&uuid_text(&player.uuid), &player.name);
            }
        }
        *self.context.borrow_mut() = Some(context);
    }

    fn tick(&self, _room: &Room, _platform: &dyn Platform) {
        let context = self.context.borrow().clone();
        if let Some(context) = context {
            context.tick();
        }
    }

    fn player_joined(&self, player: &PlayerRef) {
        let now = self.context.borrow().as_ref().map_or(0, |c| c.now());
        let link = {
            let mut network = self.shared.network.borrow_mut();
            network.joined_at.insert(player.uuid, now);
            network.link()
        };
        if let Some(link) = link {
            link.joined_here(&uuid_text(&player.uuid), &player.name);
        }
    }

    fn player_quit(&self, player: &PlayerRef) {
        let link = {
            let mut network = self.shared.network.borrow_mut();
            network.joined_at.remove(&player.uuid);
            network.link()
        };
        if let Some(link) = link {
            link.quit_here(&uuid_text(&player.uuid));
        }
    }

    fn scope_released(&self, scopes: &[ScopeId]) {
        let mut network = self.shared.network.borrow_mut();
        network.holds.retain(|_, scope| !scopes.contains(scope));
        for shown in network.showing.values_mut() {
            if shown.scope.is_some_and(|s| scopes.contains(&s)) {
                shown.scope = None;
                shown.callback = None;
            }
        }
    }

    fn stop(&self, _room: &Room, _platform: &dyn Platform) {
        let link = {
            let mut network = self.shared.network.borrow_mut();
            network.fail_all("the project stopped");
            network.showing.clear();
            network.holds.clear();
            network.channels.clear();
            network.mirrors.clear();
            network.listened = None;
            network.joined_at.clear();
            network.link()
        };
        // The next session says what it listens to; until then, nothing.
        if let Some(link) = link
            && link.connected()
        {
            link.send(ServerFrame::Listen {
                channels: Vec::new(),
                maps: Vec::new(),
            });
        }
        *self.context.borrow_mut() = None;
    }

    /// With the server in no network, each package that declares it has a warning saying so.
    fn problems(&self) -> Vec<Problem> {
        let network = self.shared.network.borrow();
        if network.mode() != Mode::Off {
            return Vec::new();
        }
        let why = network
            .link
            .as_ref()
            .and_then(|l| l.config().problem())
            .unwrap_or_else(|| {
                "the server owner puts it in one with network: in config.yml".into()
            });
        network
            .declaring
            .iter()
            .map(|namespace| {
                let file = if Some(namespace) == network.home.as_ref() {
                    netherforge_format::manifest::FILE_NAME.to_owned()
                } else {
                    format!("{namespace}:{}", netherforge_format::manifest::FILE_NAME)
                };
                netherforge_format::codes::RUNTIME_NETWORK_DISABLED.at(
                    &file,
                    format!(
                        "This server isn't part of a network, so this package's nf.network has no other servers: {why}"
                    ),
                    Some("$.requires.network".into()),
                )
            })
            .collect()
    }
}
