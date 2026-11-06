//! The local network's hub: a stand-in for `:network`'s `Hub` in this process, for a network of this server alone (a
//! dev server not put in one, named `dev`; a script test's, `test`) and the test's other servers. Kotlin runs its real
//! hub in the JVM behind a `LocalProxy`; the Rust runtime has no hub of its own (the hub stays Kotlin), so this is the
//! hub's rules for what one process needs, frame for frame and word for word where a script sees them: presence (this
//! server's players are the proxy's, joined and quit through the session's hooks), servers and their states, channels
//! (publishing, requests routed under ids of the hub's own, replies), shared maps (`SharedMaps`: expiry, each
//! package's limits), moderation's maps and reports, and sending players (servers, groups by balance and room, sends
//! that wait). Everything is trusted: credentials and their grants are the real hub's (and `nf.test.network_role`'s,
//! which comes with `nf.test`). Everything happens on the main thread: what a frame sets off reaches this server's
//! inbox as it's sent, and players are moved after the frame is handled (the hub's `later`).

use std::cell::RefCell;
use std::collections::{HashMap, HashSet, VecDeque};
use std::rc::Rc;
use std::sync::Arc;
use std::time::Instant;

use indexmap::IndexMap;
use serde_json::Value;

use super::protocol::{
    self, BANS, Balance, HubFrame, Lifecycle, MUTES, MapCause, MapOp, ModerationAction,
    PlayerState, Sanction, ServerFrame, ServerState, is_full, is_key, namespace_of,
};
use super::{Inbound, Inbox, Welcome};

/// What the hub holds servers and packages to (`NetworkLimits`).
#[derive(Clone, Debug)]
pub struct Limits {
    pub max_value_bytes: i32,
    pub max_keys_per_package: usize,
    pub max_bytes_per_package: i64,
    pub request_timeout_millis: i64,
}

impl Default for Limits {
    fn default() -> Limits {
        Limits {
            max_value_bytes: protocol::DEFAULT_MAX_VALUE_BYTES,
            max_keys_per_package: 10_000,
            max_bytes_per_package: 16 * 1024 * 1024,
            request_timeout_millis: 30_000,
        }
    }
}

/// The most a send may wait, and how many may wait for one target.
const MAX_WAIT_MILLIS: i64 = 10 * 60_000;
const MAX_WAITING: usize = 100;
/// Where a stopping server's players go: the hub's `fallback` unless its owner says otherwise.
const FALLBACK: &str = "lobby";
const MAX_REPORTS: usize = 1000;

/// A report a script made, as the hub keeps it.
#[derive(Clone, Debug, PartialEq)]
pub struct Report {
    pub id: i64,
    pub player: String,
    pub name: String,
    pub reason: String,
    pub details: Option<Value>,
    pub by: Option<String>,
    pub server: String,
    pub at: i64,
}

/// What a test's other server heard.
#[derive(Default, Debug)]
pub struct PeerLog {
    pub received: Vec<(String, String, Value)>,
    pub requests: VecDeque<(i64, String, String, Value)>,
    pub replies: HashMap<i64, (Option<Value>, Option<String>)>,
    pub arrivals: Vec<(String, String, Option<Value>)>,
    pub frames: Vec<HubFrame>,
}

#[derive(Clone)]
enum Sink {
    Here(Arc<Inbox>),
    Peer(Rc<RefCell<PeerLog>>),
}

struct Session {
    group: Option<String>,
    capacity: Option<i32>,
    accepting: bool,
    state: Lifecycle,
    idle: bool,
    startup_millis: Option<i64>,
    tick_millis: Option<f64>,
    channels: HashSet<String>,
    maps: HashSet<String>,
    sink: Sink,
}

impl Session {
    fn open(&self) -> bool {
        self.state == Lifecycle::Ready && self.accepting
    }
}

struct Route {
    origin: String,
    origin_id: i64,
    target: String,
    deadline: i64,
}

struct Wait {
    origin: String,
    frame: Send,
    deadline: i64,
    why: String,
    position: i32,
}

#[derive(Clone)]
struct Send {
    id: i64,
    players: Vec<String>,
    server: Option<String>,
    data: Option<Value>,
    group: Option<String>,
    balance: Option<Balance>,
    wait_millis: Option<i64>,
}

/// Players to move once the frame is handled, and whom to tell.
struct Move {
    origin: String,
    id: i64,
    server: String,
    target: Option<String>,
    party: usize,
    moving: Vec<PlayerState>,
}

enum Placement {
    Failed(String),
    Later(String, String),
    Go {
        server: String,
        party: usize,
        moving: Vec<PlayerState>,
    },
}

struct Entry {
    value: Value,
    bytes: usize,
    expires_at: Option<i64>,
}

/// The network's shared maps (`SharedMaps`).
#[derive(Default)]
struct Maps {
    maps: HashMap<String, IndexMap<String, Entry>>,
    keys: HashMap<String, usize>,
    bytes: HashMap<String, i64>,
}

enum Outcome {
    Changed(Option<Value>, Value),
    Unchanged(Value),
    Refused(String),
}

/// A whole number without a fraction, as Lua would write it (`SharedMaps.number`).
fn number(value: f64) -> Value {
    if value == value.round() && value.abs() < 1e15 {
        Value::from(value as i64)
    } else {
        serde_json::Number::from_f64(value).map_or(Value::Null, Value::Number)
    }
}

impl Maps {
    fn entries(&self, map: &str) -> serde_json::Map<String, Value> {
        self.maps
            .get(map)
            .map(|e| {
                e.iter()
                    .map(|(k, v)| (k.clone(), v.value.clone()))
                    .collect()
            })
            .unwrap_or_default()
    }

    #[allow(clippy::too_many_arguments)]
    fn write(
        &mut self,
        limits: &Limits,
        now: i64,
        map: &str,
        key: &str,
        op: MapOp,
        value: Option<Value>,
        amount: Option<f64>,
        expires_millis: Option<i64>,
    ) -> Outcome {
        let existing = self.maps.get(map).and_then(|m| m.get(key));
        let expires_at = expires_millis.map(|m| now + m);
        match op {
            MapOp::Set => {
                let Some(value) = value else {
                    return Outcome::Refused("a set needs a value".into());
                };
                match self.put(limits, map, key, value.clone(), expires_at) {
                    Some(why) => Outcome::Refused(why),
                    None => Outcome::Changed(Some(value), Value::Bool(true)),
                }
            }
            MapOp::Insert => {
                let Some(value) = value else {
                    return Outcome::Refused("an insert needs a value".into());
                };
                if existing.is_some() {
                    return Outcome::Unchanged(Value::Bool(false));
                }
                match self.put(limits, map, key, value.clone(), expires_at) {
                    Some(why) => Outcome::Refused(why),
                    None => Outcome::Changed(Some(value), Value::Bool(true)),
                }
            }
            MapOp::Delete => {
                if self.remove(map, key) {
                    Outcome::Changed(None, Value::Bool(true))
                } else {
                    Outcome::Unchanged(Value::Bool(false))
                }
            }
            MapOp::Add => {
                let Some(amount) = amount.filter(|a| a.is_finite()) else {
                    return Outcome::Refused("an add needs a finite amount".into());
                };
                let current = match existing.map(|e| &e.value) {
                    None => 0.0,
                    Some(Value::Number(n)) => n.as_f64().unwrap_or(0.0),
                    Some(_) => {
                        return Outcome::Refused(format!(
                            "\"{key}\" holds something that isn't a number"
                        ));
                    }
                };
                let kept = existing.and_then(|e| e.expires_at);
                let value = number(current + amount);
                match self.put(limits, map, key, value.clone(), expires_at.or(kept)) {
                    Some(why) => Outcome::Refused(why),
                    None => Outcome::Changed(Some(value.clone()), value),
                }
            }
        }
    }

    fn put(
        &mut self,
        limits: &Limits,
        map: &str,
        key: &str,
        value: Value,
        expires_at: Option<i64>,
    ) -> Option<String> {
        let size = protocol::size(Some(&value));
        if size > limits.max_value_bytes as usize {
            return Some(format!(
                "the value is {size} bytes, over the network's limit of {}",
                limits.max_value_bytes
            ));
        }
        let namespace = namespace_of(map).to_owned();
        let previous = self.maps.get(map).and_then(|m| m.get(key)).map(|e| e.bytes);
        let keys_after =
            self.keys.get(&namespace).copied().unwrap_or(0) + usize::from(previous.is_none());
        let bytes_after = self.bytes.get(&namespace).copied().unwrap_or(0) + size as i64
            - previous.unwrap_or(0) as i64;
        let limited = !protocol::is_reserved(map);
        if limited && keys_after > limits.max_keys_per_package {
            return Some(format!(
                "package \"{namespace}\" already keeps {} keys in its maps, the network's limit",
                limits.max_keys_per_package
            ));
        }
        if limited && bytes_after > limits.max_bytes_per_package {
            return Some(format!(
                "package \"{namespace}\" would keep {bytes_after} bytes in its maps, over the network's limit of {}",
                limits.max_bytes_per_package
            ));
        }
        self.maps.entry(map.to_owned()).or_default().insert(
            key.to_owned(),
            Entry {
                value,
                bytes: size,
                expires_at,
            },
        );
        self.keys.insert(namespace.clone(), keys_after);
        self.bytes.insert(namespace, bytes_after);
        None
    }

    fn remove(&mut self, map: &str, key: &str) -> bool {
        let Some(entries) = self.maps.get_mut(map) else {
            return false;
        };
        let Some(entry) = entries.shift_remove(key) else {
            return false;
        };
        if entries.is_empty() {
            self.maps.remove(map);
        }
        let namespace = namespace_of(map).to_owned();
        *self.keys.entry(namespace.clone()).or_insert(1) -= 1;
        *self.bytes.entry(namespace).or_insert(entry.bytes as i64) -= entry.bytes as i64;
        true
    }

    fn expire(&mut self, now: i64) -> Vec<(String, String)> {
        let mut gone = Vec::new();
        for (map, entries) in &self.maps {
            for (key, entry) in entries {
                if entry.expires_at.is_some_and(|at| at <= now) {
                    gone.push((map.clone(), key.clone()));
                }
            }
        }
        for (map, key) in &gone {
            self.remove(map, key);
        }
        gone
    }
}

struct Hub {
    here: String,
    /// The proxy's servers besides this one: the test's.
    peers: Vec<String>,
    sessions: IndexMap<String, Session>,
    players: IndexMap<String, PlayerState>,
    came_from: HashMap<String, String>,
    maps: Maps,
    limits: Limits,
    routes: HashMap<i64, Route>,
    next_route: i64,
    next_turn: usize,
    told: HashMap<String, ServerState>,
    reports: Vec<Report>,
    next_report: i64,
    queues: IndexMap<String, VecDeque<Wait>>,
    incoming: HashMap<String, i32>,
    moves: Vec<Move>,
    kicks: Vec<(String, String)>,
    /// Whether players may be sent here: a test that joins them as they'd arrive (`nf.test`'s join).
    joins_here: bool,
    joins: Vec<(String, String)>,
    started: Instant,
    /// What the hub's clock reads beyond the real one: a test's.
    skew: i64,
    unix_base: i64,
}

impl Hub {
    fn now(&self) -> i64 {
        self.started.elapsed().as_millis() as i64 + self.skew
    }

    fn unix(&self) -> i64 {
        self.unix_base + self.now()
    }

    fn deliver(&self, to: &str, frame: HubFrame) {
        let Some(session) = self.sessions.get(to) else {
            return;
        };
        deliver(&session.sink, frame);
    }

    fn broadcast(&self, frame: &HubFrame) {
        for session in self.sessions.values() {
            deliver(&session.sink, frame.clone());
        }
    }

    fn proxy_servers(&self) -> Vec<String> {
        std::iter::once(self.here.clone())
            .chain(self.peers.iter().cloned())
            .collect()
    }

    fn server_named(&self, name: &str) -> Option<String> {
        self.proxy_servers()
            .into_iter()
            .find(|s| s.eq_ignore_ascii_case(name))
    }

    fn counts(&self) -> HashMap<String, i32> {
        let mut counts = HashMap::new();
        for player in self.players.values() {
            if let Some(server) = &player.server {
                *counts.entry(server.clone()).or_insert(0) += 1;
            }
        }
        counts
    }

    fn state(&self, name: &str, players: i32) -> ServerState {
        let Some(s) = self.sessions.get(name) else {
            return ServerState::unlinked(name, players);
        };
        ServerState {
            name: name.into(),
            linked: true,
            group: s.group.clone(),
            capacity: s.capacity,
            accepting: s.accepting,
            players,
            state: Some(s.state),
            idle: s.idle,
            startup_millis: s.startup_millis,
            tick_millis: s.tick_millis.map(|t| (t * 10.0).round() / 10.0),
        }
    }

    fn servers(&self) -> Vec<ServerState> {
        let counts = self.counts();
        self.proxy_servers()
            .iter()
            .map(|s| self.state(s, counts.get(s).copied().unwrap_or(0)))
            .collect()
    }

    fn tell_servers(&mut self) {
        let now: Vec<ServerState> = self.servers();
        let changed: Vec<ServerState> = now
            .iter()
            .filter(|s| self.told.get(&s.name) != Some(s))
            .cloned()
            .collect();
        self.told = now.into_iter().map(|s| (s.name.clone(), s)).collect();
        if !changed.is_empty() {
            self.broadcast(&HubFrame::Servers { servers: changed });
        }
    }

    fn members(&self, group: &str) -> Vec<String> {
        let mut members: Vec<String> = self
            .sessions
            .iter()
            .filter(|(name, s)| {
                s.group
                    .as_deref()
                    .unwrap_or(name)
                    .eq_ignore_ascii_case(group)
            })
            .map(|(name, _)| name.clone())
            .collect();
        members.sort();
        members
    }

    fn occupancy(&self, name: &str, counts: &HashMap<String, i32>) -> i32 {
        counts.get(name).copied().unwrap_or(0) + self.incoming.get(name).copied().unwrap_or(0)
    }

    fn pick(&self, group: &str, balance: Option<Balance>, size: i32) -> Option<String> {
        let counts = self.counts();
        let room: Vec<String> = self
            .members(group)
            .into_iter()
            .filter(|name| {
                let s = &self.sessions[name];
                s.open()
                    && s.capacity
                        .is_none_or(|c| self.occupancy(name, &counts) + size <= c)
            })
            .collect();
        let by = |name: &String| self.occupancy(name, &counts);
        match balance.unwrap_or(Balance::Least) {
            Balance::Least => room.iter().min_by_key(|n| by(n)).cloned(),
            Balance::Fill => room.iter().rev().max_by_key(|n| by(n)).cloned(),
        }
    }

    fn presence(&self, before: Option<&PlayerState>, after: Option<&PlayerState>) {
        // Every server here is trusted, so every one sees every move.
        let frame = match (before, after) {
            (None, Some(after)) => HubFrame::PlayerJoined {
                player: after.clone(),
            },
            (Some(before), None) => HubFrame::PlayerLeft {
                id: before.id.clone(),
            },
            (before, Some(after)) => HubFrame::PlayerMoved {
                id: after.id.clone(),
                server: after.server.clone().unwrap_or_default(),
                previous: before.and_then(|b| b.server.clone()),
            },
            (None, None) => return,
        };
        self.broadcast(&frame);
    }

    fn moved(&mut self, id: &str, name: &str, server: &str) {
        let previous = self.players.get(id).cloned();
        let now = PlayerState {
            id: id.into(),
            name: name.into(),
            server: Some(server.into()),
            proxy: previous.as_ref().and_then(|p| p.proxy.clone()),
            pack: previous.as_ref().and_then(|p| p.pack.clone()),
        };
        self.players.insert(id.into(), now.clone());
        match &previous {
            None => {
                self.came_from.remove(id);
                self.presence(None, Some(&now));
            }
            Some(previous) if previous.server.as_deref() != Some(server) => {
                if let Some(from) = &previous.server {
                    self.came_from.insert(id.into(), from.clone());
                }
                self.presence(Some(previous), Some(&now));
            }
            Some(_) => {}
        }
        self.dispatch();
    }

    fn left(&mut self, id: &str) {
        let Some(previous) = self.players.shift_remove(id) else {
            return;
        };
        self.came_from.remove(id);
        self.presence(Some(&previous), None);
        let mut told = Vec::new();
        for queue in self.queues.values_mut() {
            queue.retain(|wait| {
                if wait.frame.players.iter().any(|p| p == id) {
                    told.push((wait.origin.clone(), wait.frame.id));
                    false
                } else {
                    true
                }
            });
        }
        for (origin, frame_id) in told {
            self.deliver(
                &origin,
                HubFrame::SendResult {
                    id: frame_id,
                    error: Some(format!("{} left the network while waiting", previous.name)),
                    server: None,
                },
            );
        }
        self.dispatch();
    }

    fn open(
        &mut self,
        name: &str,
        sink: Sink,
        group: Option<String>,
        capacity: Option<i32>,
        state: Lifecycle,
    ) {
        self.close(name);
        self.sessions.insert(
            name.into(),
            Session {
                group,
                capacity,
                accepting: true,
                state,
                idle: false,
                startup_millis: None,
                tick_millis: None,
                channels: HashSet::new(),
                maps: HashSet::new(),
                sink: sink.clone(),
            },
        );
        let welcome = Welcome {
            server: name.into(),
            servers: self.servers(),
            players: self.players.values().cloned().collect(),
            max_value_bytes: self.limits.max_value_bytes,
            grants: protocol::Grants::trusted(),
            policy: None,
            keeps_packs: false,
        };
        match &sink {
            Sink::Here(inbox) => inbox.push(Inbound::Connected(Box::new(welcome))),
            Sink::Peer(log) => log.borrow_mut().frames.push(HubFrame::Welcome {
                server: welcome.server,
                servers: welcome.servers,
                players: welcome.players,
                max_value_bytes: welcome.max_value_bytes,
                grants: welcome.grants,
                policy: None,
                keeps_packs: false,
            }),
        }
        let state = self.state(name, self.counts().get(name).copied().unwrap_or(0));
        for (other, session) in &self.sessions {
            if other != name {
                deliver(
                    &session.sink,
                    HubFrame::Server {
                        server: state.clone(),
                    },
                );
            }
        }
        self.told.insert(name.into(), state);
        self.dispatch();
    }

    fn close(&mut self, name: &str) {
        if self.sessions.shift_remove(name).is_none() {
            return;
        }
        for queue in self.queues.values_mut() {
            queue.retain(|w| w.origin != name);
        }
        let state = self.state(name, self.counts().get(name).copied().unwrap_or(0));
        self.broadcast(&HubFrame::Server {
            server: state.clone(),
        });
        self.told.insert(name.into(), state);
        let ended: Vec<i64> = self
            .routes
            .iter()
            .filter(|(_, r)| r.target == name || r.origin == name)
            .map(|(id, _)| *id)
            .collect();
        for id in ended {
            let route = self.routes.remove(&id).expect("listed");
            if route.origin != name {
                self.deliver(
                    &route.origin,
                    HubFrame::Reply {
                        id: route.origin_id,
                        value: None,
                        error: Some(format!("{name} disconnected before answering")),
                    },
                );
            }
        }
    }

    fn changed(
        &self,
        map: &str,
        key: &str,
        value: Option<Value>,
        from: Option<String>,
        cause: MapCause,
    ) {
        let frame = HubFrame::MapChanged {
            map: map.into(),
            key: key.into(),
            value,
            from,
            cause,
        };
        for session in self.sessions.values() {
            if session.maps.contains(map) {
                deliver(&session.sink, frame.clone());
            }
        }
    }

    fn tick(&mut self) {
        let now = self.now();
        for (map, key) in self.maps.expire(now) {
            self.changed(&map, &key, None, None, MapCause::Expired);
        }
        let late: Vec<i64> = self
            .routes
            .iter()
            .filter(|(_, r)| r.deadline <= now)
            .map(|(id, _)| *id)
            .collect();
        for id in late {
            let route = self.routes.remove(&id).expect("listed");
            self.deliver(
                &route.origin,
                HubFrame::Reply {
                    id: route.origin_id,
                    value: None,
                    error: Some(format!(
                        "{} didn't answer within {} seconds",
                        route.target,
                        self.limits.request_timeout_millis / 1000
                    )),
                },
            );
        }
        let mut told = Vec::new();
        for queue in self.queues.values_mut() {
            queue.retain(|wait| {
                if wait.deadline <= now {
                    told.push((
                        wait.origin.clone(),
                        wait.frame.id,
                        wait.frame.wait_millis,
                        wait.why.clone(),
                    ));
                    false
                } else {
                    true
                }
            });
        }
        for (origin, id, wait, why) in told {
            self.deliver(
                &origin,
                HubFrame::SendResult {
                    id,
                    error: Some(format!(
                        "waited {} seconds: {why}",
                        wait.unwrap_or(0) / 1000
                    )),
                    server: None,
                },
            );
        }
        self.dispatch();
        self.tell_servers();
    }

    fn handle(&mut self, from: &str, frame: ServerFrame) {
        if !self.sessions.contains_key(from) {
            return;
        }
        match frame {
            ServerFrame::Hello { .. } => self.deliver(
                from,
                HubFrame::Problem {
                    message: "it already said hello".into(),
                },
            ),
            ServerFrame::Ping => self.deliver(from, HubFrame::Pong),
            ServerFrame::Status {
                accepting,
                state,
                idle,
                startup_millis,
                tick_millis,
            } => {
                let s = self.sessions.get_mut(from).expect("checked");
                s.accepting = accepting;
                s.state = state;
                s.idle = idle;
                s.startup_millis = startup_millis.or(s.startup_millis);
                s.tick_millis = tick_millis.or(s.tick_millis);
                self.dispatch();
            }
            ServerFrame::Evacuate => {
                self.sessions.get_mut(from).expect("checked").state = Lifecycle::Stopping;
                self.evacuate(from);
            }
            ServerFrame::Leaving => {}
            ServerFrame::Moderate {
                id,
                action,
                player,
                name,
                reason,
                by,
                until,
            } => {
                let why = self.moderate(
                    action,
                    &player,
                    &name,
                    reason,
                    &format!("{by} on {from}"),
                    until,
                );
                self.deliver(from, HubFrame::ModerateResult { id, error: why });
            }
            ServerFrame::Report {
                player,
                name,
                reason,
                details,
                by,
            } => {
                self.next_report += 1;
                let report = Report {
                    id: self.next_report,
                    player,
                    name,
                    reason: reason.chars().take(500).collect(),
                    details,
                    by,
                    server: from.into(),
                    at: self.unix(),
                };
                self.reports.push(report);
                if self.reports.len() > MAX_REPORTS {
                    self.reports.remove(0);
                }
            }
            ServerFrame::PackLoaded { player, sha1 } => {
                if let Some(state) = self.players.get_mut(&player)
                    && state.pack.as_deref() != Some(&sha1)
                    && sha1.len() == 40
                {
                    state.pack = Some(sha1.clone());
                    self.broadcast(&HubFrame::PlayerPack {
                        id: player,
                        pack: sha1,
                    });
                }
            }
            ServerFrame::Listen { channels, maps } => {
                let s = self.sessions.get_mut(from).expect("checked");
                s.channels = channels.into_iter().filter(|c| is_full(c)).collect();
                let before = std::mem::take(&mut s.maps);
                s.maps = maps.into_iter().filter(|m| is_full(m)).collect();
                let new: Vec<String> = s.maps.difference(&before).cloned().collect();
                for map in new {
                    let entries = self.maps.entries(&map);
                    self.deliver(from, HubFrame::MapSnapshot { map, entries });
                }
            }
            ServerFrame::Publish {
                channel,
                value,
                to,
                group,
            } => {
                if !is_full(&channel) {
                    return self.deliver(
                        from,
                        HubFrame::Problem {
                            message: format!("\"{channel}\" isn't a channel"),
                        },
                    );
                }
                let size = protocol::size(Some(&value));
                if size > self.limits.max_value_bytes as usize {
                    return self.deliver(
                        from,
                        HubFrame::Problem {
                            message: format!(
                                "a message on \"{channel}\" is {size} bytes, over the network's limit of {}",
                                self.limits.max_value_bytes
                            ),
                        },
                    );
                }
                let targets: Vec<String> = match (&to, &group) {
                    (Some(to), _) => self
                        .server_named(to)
                        .filter(|s| self.sessions.contains_key(s))
                        .into_iter()
                        .collect(),
                    (None, Some(group)) => self.members(group),
                    (None, None) => self.sessions.keys().cloned().collect(),
                };
                let message = HubFrame::Message {
                    channel: channel.clone(),
                    from: from.into(),
                    value,
                };
                for target in targets {
                    if self.sessions[&target].channels.contains(&channel) {
                        self.deliver(&target, message.clone());
                    }
                }
            }
            ServerFrame::Request {
                id,
                channel,
                to,
                value,
                group,
                admin,
            } => self.request(from, id, channel, to, value, group, admin),
            ServerFrame::Reply { id, value, error } => {
                let Some(route) = self.routes.get(&id).filter(|r| r.target == from) else {
                    return;
                };
                let (origin, origin_id) = (route.origin.clone(), route.origin_id);
                self.routes.remove(&id);
                let size = protocol::size(value.as_ref());
                let reply = if size > self.limits.max_value_bytes as usize {
                    HubFrame::Reply {
                        id: origin_id,
                        value: None,
                        error: Some(format!(
                            "{from}'s reply is {size} bytes, over the network's limit of {}",
                            self.limits.max_value_bytes
                        )),
                    }
                } else {
                    HubFrame::Reply {
                        id: origin_id,
                        value,
                        error,
                    }
                };
                self.deliver(&origin, reply);
            }
            ServerFrame::MapWrite {
                id,
                map,
                key,
                op,
                value,
                amount,
                expires_millis,
            } => {
                let result = |value: Option<Value>, error: Option<String>| HubFrame::MapResult {
                    id,
                    value,
                    error,
                };
                if !is_full(&map) {
                    return self
                        .deliver(from, result(None, Some(format!("\"{map}\" isn't a map"))));
                }
                if !is_key(&key) {
                    return self.deliver(
                        from,
                        result(
                            None,
                            Some(format!(
                                "a key is 1 to {} characters",
                                protocol::MAX_KEY_LENGTH
                            )),
                        ),
                    );
                }
                if protocol::is_reserved(&map) {
                    return self.deliver(
                        from,
                        result(
                            None,
                            Some(format!("network.forbidden: credential \"null\" (trusted) may not write to \"{map}\"")),
                        ),
                    );
                }
                let now = self.now();
                match self.maps.write(
                    &self.limits.clone(),
                    now,
                    &map,
                    &key,
                    op,
                    value,
                    amount,
                    expires_millis,
                ) {
                    Outcome::Refused(why) => self.deliver(from, result(None, Some(why))),
                    Outcome::Unchanged(r) => self.deliver(from, result(Some(r), None)),
                    Outcome::Changed(value, r) => {
                        let cause = if value.is_none() {
                            MapCause::Delete
                        } else {
                            MapCause::Set
                        };
                        self.changed(&map, &key, value, Some(from.into()), cause);
                        self.deliver(from, result(Some(r), None));
                    }
                }
            }
            ServerFrame::Send {
                id,
                players,
                server,
                data,
                group,
                balance,
                wait_millis,
            } => self.send_player(
                from,
                Send {
                    id,
                    players,
                    server,
                    data,
                    group,
                    balance,
                    wait_millis,
                },
            ),
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn request(
        &mut self,
        from: &str,
        id: i64,
        channel: String,
        to: Option<String>,
        value: Value,
        group: Option<String>,
        admin: Option<String>,
    ) {
        let fail = |hub: &Hub, why: String| {
            hub.deliver(
                from,
                HubFrame::Reply {
                    id,
                    value: None,
                    error: Some(why),
                },
            )
        };
        if !is_full(&channel) {
            return fail(self, format!("\"{channel}\" isn't a channel"));
        }
        let size = protocol::size(Some(&value));
        if size > self.limits.max_value_bytes as usize {
            return fail(
                self,
                format!(
                    "the request is {size} bytes, over the network's limit of {}",
                    self.limits.max_value_bytes
                ),
            );
        }
        let answers = |hub: &Hub, name: &str| hub.sessions[name].channels.contains(&channel);
        let target = if let Some(admin) = admin {
            return fail(
                self,
                format!("the admin client {admin} isn't connected to the hub"),
            );
        } else if let Some(group) = group {
            let members = self.members(&group);
            let answering: Vec<String> = members
                .iter()
                .filter(|m| answers(self, m))
                .cloned()
                .collect();
            if answering.is_empty() {
                return fail(
                    self,
                    if members.is_empty() {
                        format!("no instance of {group} is connected to the network")
                    } else {
                        format!(
                            "nothing on any instance of {group} answers requests on \"{channel}\""
                        )
                    },
                );
            }
            let turn = self.next_turn;
            self.next_turn += 1;
            answering[turn % answering.len()].clone()
        } else {
            let Some(to) = to else {
                return fail(self, "a request names a server or a group".into());
            };
            let Some(target) = self
                .server_named(&to)
                .filter(|s| self.sessions.contains_key(s))
            else {
                return fail(self, format!("{to} isn't connected to the network"));
            };
            if !answers(self, &target) {
                return fail(
                    self,
                    format!("nothing on {target} answers requests on \"{channel}\""),
                );
            }
            target
        };
        self.next_route += 1;
        let route = self.next_route;
        self.routes.insert(
            route,
            Route {
                origin: from.into(),
                origin_id: id,
                target: target.clone(),
                deadline: self.now() + self.limits.request_timeout_millis,
            },
        );
        self.deliver(
            &target,
            HubFrame::Requested {
                id: route,
                channel,
                from: from.into(),
                value,
            },
        );
    }

    fn moderate(
        &mut self,
        action: ModerationAction,
        player: &str,
        name: &str,
        reason: Option<String>,
        by: &str,
        until: Option<i64>,
    ) -> Option<String> {
        let now = self.unix();
        if until.is_some_and(|u| u <= now) {
            return Some("until is in the past".into());
        }
        let map = match action {
            ModerationAction::Ban | ModerationAction::Unban => BANS,
            _ => MUTES,
        };
        let setting = matches!(action, ModerationAction::Ban | ModerationAction::Mute);
        let value = setting.then(|| {
            serde_json::to_value(Sanction {
                name: name.into(),
                reason,
                by: by.into(),
                at: now,
                until,
            })
            .expect("a sanction is JSON")
        });
        let op = if setting { MapOp::Set } else { MapOp::Delete };
        let clock = self.now();
        match self.maps.write(
            &self.limits.clone(),
            clock,
            map,
            player,
            op,
            value,
            None,
            until.map(|u| u - now),
        ) {
            Outcome::Refused(why) => return Some(why),
            Outcome::Unchanged(_) if !setting => {
                return Some(format!(
                    "{name} wasn't {}",
                    if map == BANS { "banned" } else { "muted" }
                ));
            }
            Outcome::Unchanged(_) => {}
            Outcome::Changed(value, _) => {
                let cause = if value.is_none() {
                    MapCause::Delete
                } else {
                    MapCause::Set
                };
                self.changed(map, player, value, Some(by.into()), cause);
            }
        }
        if action == ModerationAction::Ban
            && self.players.get(player).and_then(|p| p.server.as_deref()) == Some(&self.here)
        {
            let message = match &self
                .maps
                .entries(BANS)
                .get(player)
                .and_then(|v| v.get("reason"))
                .and_then(Value::as_str)
            {
                Some(reason) => format!("You're banned from the network: {reason}"),
                None => "You're banned from the network".into(),
            };
            self.kicks.push((player.into(), message));
        }
        None
    }

    fn place(&self, frame: &Send) -> Placement {
        if frame.players.is_empty() {
            return Placement::Failed("there's nobody to send".into());
        }
        if frame.server.is_none() == frame.group.is_none() {
            return Placement::Failed("a send names a server or a group".into());
        }
        let mut party = Vec::new();
        let mut seen = HashSet::new();
        for id in &frame.players {
            if !seen.insert(id) {
                continue;
            }
            let Some(player) = self.players.get(id) else {
                return Placement::Failed(if frame.players.len() == 1 {
                    "they aren't online in the network".into()
                } else {
                    format!("{id} isn't online in the network")
                });
            };
            party.push(player.clone());
        }
        let size = protocol::size(frame.data.as_ref());
        if size > self.limits.max_value_bytes as usize {
            return Placement::Failed(format!(
                "the data is {size} bytes, over the network's limit of {}",
                self.limits.max_value_bytes
            ));
        }
        let server = if let Some(named) = &frame.server {
            let Some(name) = self.server_named(named) else {
                return Placement::Later(
                    named.to_lowercase(),
                    format!("the proxy has no server named \"{named}\""),
                );
            };
            match self.sessions.get(&name).map(|s| s.state) {
                Some(state @ (Lifecycle::Draining | Lifecycle::Stopping)) => {
                    return Placement::Failed(format!(
                        "{name} is {}: it takes no new players",
                        state.word()
                    ));
                }
                Some(Lifecycle::Starting) => {
                    return Placement::Later(name.to_lowercase(), format!("{name} is starting"));
                }
                _ => name,
            }
        } else {
            let group = frame.group.as_deref().expect("checked");
            let members = self.members(group);
            let moving = party
                .iter()
                .filter(|p| !members.iter().any(|m| Some(m) == p.server.as_ref()))
                .count()
                .max(1) as i32;
            match self.pick(group, frame.balance, moving).or_else(|| {
                self.server_named(group)
                    .filter(|s| members.is_empty() && !self.sessions.contains_key(s))
            }) {
                Some(server) => server,
                None => {
                    let why = if members.is_empty() {
                        format!("the network has no group or server named \"{group}\" connected")
                    } else if members
                        .iter()
                        .all(|m| self.sessions[m].state != Lifecycle::Ready)
                    {
                        format!("no instance of {group} is ready")
                    } else if members.iter().all(|m| !self.sessions[m].open()) {
                        format!("no instance of {group} takes players now")
                    } else {
                        format!(
                            "no instance of {group} has room for {}",
                            if party.len() == 1 {
                                "them".to_owned()
                            } else {
                                format!("{} players", party.len())
                            }
                        )
                    };
                    return Placement::Later(group.to_lowercase(), why);
                }
            }
        };
        let moving: Vec<PlayerState> = party
            .iter()
            .filter(|p| p.server.as_deref() != Some(&server))
            .cloned()
            .collect();
        if moving.is_empty() {
            return Placement::Failed(if party.len() == 1 {
                format!("they're already on {server}")
            } else {
                format!("they're all already on {server}")
            });
        }
        Placement::Go {
            server,
            party: party.len(),
            moving,
        }
    }

    fn send_player(&mut self, origin: &str, frame: Send) {
        match self.place(&frame) {
            Placement::Failed(why) => self.deliver(
                origin,
                HubFrame::SendResult {
                    id: frame.id,
                    error: Some(why),
                    server: None,
                },
            ),
            Placement::Go {
                server,
                party,
                moving,
            } => self.go(origin, &frame, server, party, moving),
            Placement::Later(target, why) => {
                let wait = frame.wait_millis.map(|w| w.min(MAX_WAIT_MILLIS));
                let Some(wait) = wait.filter(|w| *w > 0) else {
                    return self.deliver(
                        origin,
                        HubFrame::SendResult {
                            id: frame.id,
                            error: Some(why),
                            server: None,
                        },
                    );
                };
                let deadline = self.now() + wait;
                let queue = self.queues.entry(target.clone()).or_default();
                if queue.len() >= MAX_WAITING {
                    return self.deliver(
                        origin,
                        HubFrame::SendResult {
                            id: frame.id,
                            error: Some(format!(
                                "{MAX_WAITING} sends already wait for {target}: {why}"
                            )),
                            server: None,
                        },
                    );
                }
                queue.push_back(Wait {
                    origin: origin.into(),
                    frame,
                    deadline,
                    why,
                    position: 0,
                });
                self.positions();
            }
        }
    }

    fn go(
        &mut self,
        origin: &str,
        frame: &Send,
        server: String,
        party: usize,
        moving: Vec<PlayerState>,
    ) {
        let target = self.sessions.contains_key(&server).then(|| server.clone());
        if let Some(target) = &target {
            for player in &moving {
                self.deliver(
                    target,
                    HubFrame::Arriving {
                        player: player.id.clone(),
                        from: origin.into(),
                        data: frame.data.clone(),
                    },
                );
            }
        }
        *self.incoming.entry(server.clone()).or_insert(0) += moving.len() as i32;
        self.moves.push(Move {
            origin: origin.into(),
            id: frame.id,
            server,
            target,
            party,
            moving,
        });
    }

    fn dispatch(&mut self) {
        if self.queues.is_empty() {
            return;
        }
        let mut changed = false;
        let keys: Vec<String> = self.queues.keys().cloned().collect();
        for key in keys {
            while let Some((origin, frame)) = self
                .queues
                .get(&key)
                .and_then(|q| q.front())
                .map(|w| (w.origin.clone(), w.frame.clone()))
            {
                match self.place(&frame) {
                    Placement::Later(_, why) => {
                        if let Some(wait) = self.queues.get_mut(&key).and_then(|q| q.front_mut()) {
                            wait.why = why;
                        }
                        break;
                    }
                    Placement::Failed(why) => self.deliver(
                        &origin,
                        HubFrame::SendResult {
                            id: frame.id,
                            error: Some(why),
                            server: None,
                        },
                    ),
                    Placement::Go {
                        server,
                        party,
                        moving,
                    } => self.go(&origin, &frame, server, party, moving),
                }
                if let Some(queue) = self.queues.get_mut(&key) {
                    queue.pop_front();
                }
                changed = true;
            }
        }
        self.queues.retain(|_, q| !q.is_empty());
        if changed {
            self.positions();
        }
    }

    fn positions(&mut self) {
        let mut told = Vec::new();
        for queue in self.queues.values_mut() {
            for (index, wait) in queue.iter_mut().enumerate() {
                let position = index as i32 + 1;
                if wait.position != position {
                    wait.position = position;
                    told.push((wait.origin.clone(), wait.frame.id, position));
                }
            }
        }
        for (origin, id, position) in told {
            self.deliver(
                &origin,
                HubFrame::SendWaiting {
                    id,
                    position,
                    status: None,
                },
            );
        }
    }

    fn evacuate(&mut self, name: &str) {
        let on: Vec<String> = self
            .players
            .values()
            .filter(|p| p.server.as_deref() == Some(name))
            .map(|p| p.id.clone())
            .collect();
        for id in on {
            let frame = Send {
                id: -1,
                players: vec![id],
                server: None,
                data: None,
                group: Some(FALLBACK.into()),
                balance: None,
                wait_millis: None,
            };
            if let Placement::Go {
                server,
                party,
                moving,
            } = self.place(&frame)
            {
                self.go(name, &frame, server, party, moving);
            }
        }
    }
}

fn deliver(sink: &Sink, frame: HubFrame) {
    match sink {
        Sink::Here(inbox) => inbox.push(Inbound::Frame(frame)),
        Sink::Peer(log) => {
            let mut log = log.borrow_mut();
            match &frame {
                HubFrame::Message {
                    channel,
                    from,
                    value,
                } => {
                    log.received
                        .push((channel.clone(), from.clone(), value.clone()));
                }
                HubFrame::Requested {
                    id,
                    channel,
                    from,
                    value,
                } => {
                    log.requests
                        .push_back((*id, channel.clone(), from.clone(), value.clone()));
                }
                HubFrame::Reply { id, value, error } => {
                    log.replies.insert(*id, (value.clone(), error.clone()));
                }
                HubFrame::Arriving { player, from, data } => {
                    log.arrivals
                        .push((player.clone(), from.clone(), data.clone()));
                }
                _ => {}
            }
            log.frames.push(frame);
        }
    }
}

/// The local network: its hub, this server's place in it, and the test's other servers.
pub struct LocalHub {
    hub: RefCell<Hub>,
}

impl LocalHub {
    pub fn new(here: &str, limits: Limits) -> Rc<LocalHub> {
        Rc::new(LocalHub {
            hub: RefCell::new(Hub {
                here: here.into(),
                peers: Vec::new(),
                sessions: IndexMap::new(),
                players: IndexMap::new(),
                came_from: HashMap::new(),
                maps: Maps::default(),
                limits,
                routes: HashMap::new(),
                next_route: 0,
                next_turn: 0,
                told: HashMap::new(),
                reports: Vec::new(),
                next_report: 0,
                queues: IndexMap::new(),
                incoming: HashMap::new(),
                moves: Vec::new(),
                kicks: Vec::new(),
                joins_here: false,
                joins: Vec::new(),
                started: Instant::now(),
                skew: 0,
                unix_base: std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .map_or(0, |d| d.as_millis() as i64),
            }),
        })
    }

    /// This server joins: welcomed into `inbox` at once.
    pub fn connect_here(&self, inbox: Arc<Inbox>, state: Lifecycle) {
        let here = self.hub.borrow().here.clone();
        self.hub
            .borrow_mut()
            .open(&here, Sink::Here(inbox), None, None, state);
        self.after();
    }

    pub fn disconnect_here(&self) {
        let here = self.hub.borrow().here.clone();
        self.hub.borrow_mut().close(&here);
        self.after();
    }

    /// A frame from this server.
    pub fn receive_here(&self, frame: ServerFrame) {
        let here = self.hub.borrow().here.clone();
        self.receive(&here, frame);
    }

    fn receive(&self, from: &str, frame: ServerFrame) {
        self.hub.borrow_mut().handle(from, frame);
        self.after();
    }

    /// What the hub set off, after the frame: the players it moves.
    fn after(&self) {
        loop {
            let moves = std::mem::take(&mut self.hub.borrow_mut().moves);
            if moves.is_empty() {
                return;
            }
            for each in moves {
                let mut failed: Vec<(PlayerState, String)> = Vec::new();
                for player in &each.moving {
                    if let Err(why) = self.proxy_send(&player.id, &player.name, &each.server) {
                        failed.push((player.clone(), why));
                    }
                }
                let mut hub = self.hub.borrow_mut();
                if let Some(count) = hub.incoming.get_mut(&each.server) {
                    *count -= each.moving.len() as i32;
                    if *count <= 0 {
                        hub.incoming.remove(&each.server);
                    }
                }
                if let Some(target) = &each.target {
                    for (player, _) in &failed {
                        hub.deliver(
                            target,
                            HubFrame::ArrivalCancelled {
                                player: player.id.clone(),
                            },
                        );
                    }
                }
                let why = match failed.len() {
                    0 => None,
                    _ if each.party == 1 => Some(failed[0].1.clone()),
                    _ => Some(
                        failed
                            .iter()
                            .map(|(p, e)| format!("{}: {e}", p.name))
                            .collect::<Vec<_>>()
                            .join("; "),
                    ),
                };
                if each.id >= 0 {
                    hub.deliver(
                        &each.origin,
                        HubFrame::SendResult {
                            id: each.id,
                            error: why,
                            server: Some(each.server.clone()),
                        },
                    );
                }
                hub.dispatch();
            }
        }
    }

    /// The local proxy moving a player: to this server only a test could (its join); elsewhere they're placed there,
    /// and kicked from here when they were here, as the proxy taking them would.
    fn proxy_send(&self, id: &str, name: &str, server: &str) -> Result<(), String> {
        let mut hub = self.hub.borrow_mut();
        if server == hub.here {
            if !hub.joins_here {
                return Err("this server is a network of its own".into());
            }
            // The join here says where they are.
            hub.joins.push((id.into(), name.into()));
            return Ok(());
        }
        let from = hub.players.get(id).and_then(|p| p.server.clone());
        hub.moved(id, name, server);
        if from.as_deref() == Some(hub.here.as_str()) {
            hub.kicks.push((id.into(), format!("Sent to {server}")));
        }
        Ok(())
    }

    /// Once a second: expired keys, requests nobody answered, sends that waited too long, servers' changes.
    pub fn tick(&self) {
        self.hub.borrow_mut().tick();
        self.after();
    }

    /// A player joined this server: the proxy is this server, and says so.
    pub fn joined_here(&self, id: &str, name: &str) {
        let mut hub = self.hub.borrow_mut();
        let here = hub.here.clone();
        hub.moved(id, name, &here);
        drop(hub);
        self.after();
    }

    /// A player left this server: they leave the network, unless they were sent elsewhere.
    pub fn quit_here(&self, id: &str) {
        let mut hub = self.hub.borrow_mut();
        let here = hub.here.clone();
        if hub.players.get(id).and_then(|p| p.server.as_deref()) == Some(here.as_str()) {
            hub.left(id);
        }
        drop(hub);
        self.after();
    }

    /// Players to kick from this server, as the proxy moving them would: by UUID, with what they're told.
    pub fn take_kicks(&self) -> Vec<(String, String)> {
        std::mem::take(&mut self.hub.borrow_mut().kicks)
    }

    /// Players may be sent here from now on: each is handed to `take_joins`, for the test to join them as they'd
    /// arrive (Kotlin's `joinHere`).
    pub fn accept_joins(&self) {
        self.hub.borrow_mut().joins_here = true;
    }

    /// The players sent here since the last time, by UUID and name, to join.
    pub fn take_joins(&self) -> Vec<(String, String)> {
        std::mem::take(&mut self.hub.borrow_mut().joins)
    }

    /// What scripts reported, newest first.
    pub fn reports(&self) -> Vec<Report> {
        self.hub.borrow().reports.iter().rev().cloned().collect()
    }

    /// A map's entries as the hub holds them.
    pub fn entries(&self, map: &str) -> serde_json::Map<String, Value> {
        self.hub.borrow().maps.entries(map)
    }

    /// Moves the hub's clock on (a test's: expiries and timeouts).
    pub fn advance(&self, millis: i64) {
        self.hub.borrow_mut().skew += millis;
    }

    /// The test's server `name`, joining the network the first time it's named.
    pub fn peer(
        self: &Rc<LocalHub>,
        name: &str,
        group: Option<String>,
        capacity: Option<i32>,
        state: Lifecycle,
    ) -> TestPeer {
        let log = Rc::new(RefCell::new(PeerLog::default()));
        {
            let mut hub = self.hub.borrow_mut();
            if !hub.peers.iter().any(|p| p == name) {
                hub.peers.push(name.into());
            }
            hub.open(name, Sink::Peer(log.clone()), group, capacity, state);
        }
        self.after();
        TestPeer {
            name: name.into(),
            hub: self.clone(),
            log,
            channels: RefCell::default(),
        }
    }

    /// A player joins (or moves to) `server`, as the proxy says it.
    pub fn place(&self, id: &str, name: &str, server: &str) {
        self.hub.borrow_mut().moved(id, name, server);
        self.after();
    }

    pub fn leave(&self, id: &str) {
        self.hub.borrow_mut().left(id);
        self.after();
    }
}

/// Another server of a test's network, played by the test.
pub struct TestPeer {
    pub name: String,
    hub: Rc<LocalHub>,
    pub log: Rc<RefCell<PeerLog>>,
    channels: RefCell<Vec<String>>,
}

impl TestPeer {
    pub fn send(&self, frame: ServerFrame) {
        self.hub.receive(&self.name, frame);
    }

    /// Listens to `channel` (a full name) from now on.
    pub fn listen(&self, channel: &str) {
        let mut channels = self.channels.borrow_mut();
        if !channels.iter().any(|c| c == channel) {
            channels.push(channel.into());
        }
        let frame = ServerFrame::Listen {
            channels: channels.clone(),
            maps: Vec::new(),
        };
        drop(channels);
        self.send(frame);
    }

    /// It says where it is in its life and whether it takes players.
    pub fn status(&self, state: Lifecycle, accepting: bool) {
        self.send(ServerFrame::Status {
            accepting,
            state,
            idle: false,
            startup_millis: None,
            tick_millis: None,
        });
    }

    /// Answers the oldest request it was asked with `value`; false when there's none.
    pub fn answer(&self, value: Option<Value>, error: Option<String>) -> bool {
        let Some((id, ..)) = self.log.borrow_mut().requests.pop_front() else {
            return false;
        };
        self.send(ServerFrame::Reply { id, value, error });
        true
    }

    /// It goes from the network.
    pub fn disconnect(&self) {
        self.hub.hub.borrow_mut().close(&self.name);
        self.hub.after();
    }
}
