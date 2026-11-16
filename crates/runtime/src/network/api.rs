//! `nf.network`, `NetworkChannel`, `NetworkMap` and `NetworkHold` (Kotlin's `NetworkImpls`): a mistake in the call
//! (a name that isn't one, another package's channel the caller can't use, a value over the network's limit or that
//! JSON can't hold) is an error at the script's line; what only the network can say (not connected, nobody answering,
//! the proxy refusing) is `false` or `nil, err`.

use std::collections::BTreeMap;

use mlua::{Function, Value};
use netherforge_api::api::{NetworkChannelApi, NetworkHoldApi, NetworkMapApi, NfNetworkApi};
use netherforge_api::handles::{self, Handle};
use netherforge_api::{ApiError, ApiResult, Caller, Pending, shapes, unions};

use super::protocol::{self, Balance, Lifecycle, MapOp, ModerationAction, ServerState};
use super::service::{NetworkContext, Written};
use crate::events::parse_uuid;
use crate::scripts::Target;

fn err(message: impl Into<String>) -> ApiError {
    ApiError(message.into())
}

/// `name` when it's a server's or group's name; an error naming `place` otherwise.
fn check_server(name: String, place: &str) -> ApiResult<String> {
    if !protocol::is_server(&name) {
        return Err(err(format!(
            "bad argument '{place}' (a server's or group's name expected, got \"{name}\")"
        )));
    }
    Ok(name)
}

impl NetworkContext {
    fn server_shape(
        state: &ServerState,
        counts: &std::collections::HashMap<String, i64>,
    ) -> shapes::NetworkServer {
        shapes::NetworkServer {
            name: state.name.clone(),
            connected: state.linked,
            players: counts.get(&state.name).copied().unwrap_or(0),
            group: state.group_name().into(),
            capacity: state.capacity.map(i64::from),
            accepting: state.accepting,
            state: state.state.map(|s| s.word().into()),
            idle: state.idle,
            tick_milliseconds: state.tick_millis,
        }
    }

    fn subscribe(
        &self,
        handle: Handle,
        called: &str,
        event: String,
        handler: Function,
        options: Option<shapes::EventOptions>,
        once: bool,
    ) -> ApiResult<handles::Subscription> {
        let scope = self
            .scripts
            .host()
            .running_scope()
            .ok_or_else(|| err(format!("{called} was called by no script")))?;
        let target = netherforge_api::handle::push_any(self.scripts.host().lua(), &handle)
            .map(Target::Handle)
            .map_err(|e| err(e.to_string()))?;
        self.scripts
            .subscribe(scope, target, event, handler, options, once)
    }

    /// A player as the network knows them: their UUID and name; an error when nobody by that name is online.
    fn who(&self, player: unions::PlayerOrString) -> ApiResult<(String, String)> {
        match player {
            unions::PlayerOrString::Player(p) => {
                let name = parse_uuid(&p.id)
                    .and_then(|u| self.shared.platform.players().get(u))
                    .map_or_else(|| p.id.clone(), |r| r.name);
                Ok((p.id, name))
            }
            unions::PlayerOrString::String(name) => {
                if let Some(found) = self.find_player(&name) {
                    return Ok((found.id, found.name));
                }
                if parse_uuid(&name).is_some() {
                    return Ok((name.clone(), name));
                }
                Err(err(format!(
                    "nobody called \"{name}\" is online in the network: name them by UUID"
                )))
            }
        }
    }

    fn sanction(
        &self,
        caller: &Caller,
        action: ModerationAction,
        player: unions::PlayerOrString,
        options: Option<shapes::NetworkSanctionOptions>,
    ) -> ApiResult<Pending<bool>> {
        let (id, name) = self.who(player)?;
        let (reason, until) = options.map_or((None, None), |o| (o.reason, o.expires));
        let namespace = self.namespace(caller.scope);
        let reply = self.moderate(&namespace, action, id, name, reason, until);
        Ok(Box::pin(async move { reply.await.map_err(ApiError) }))
    }

    fn send_to(
        &self,
        caller: &Caller,
        players: Vec<unions::PlayerOrString>,
        target: unions::StringOrNetworkSendOptions,
        data: Value,
    ) -> ApiResult<Pending<String>> {
        let (server, group, options) = match target {
            unions::StringOrNetworkSendOptions::String(name) => {
                (Some(check_server(name, "target")?), None, None)
            }
            unions::StringOrNetworkSendOptions::NetworkSendOptions(options) => {
                if options.server.is_none() == options.group.is_none() {
                    return Err(err(
                        "bad argument 'target' (a table naming a server or a group expected, not both or neither)",
                    ));
                }
                let server = options
                    .server
                    .clone()
                    .map(|s| check_server(s, "target.server"))
                    .transpose()?;
                let group = options
                    .group
                    .clone()
                    .map(|g| check_server(g, "target.group"))
                    .transpose()?;
                (server, group, Some(options))
            }
        };
        let balance = match options.as_ref().and_then(|o| o.balance.clone()) {
            Some(word) => Some(Balance::of(&word).ok_or_else(|| {
                err(format!(
                    "bad argument 'target.balance' (\"least\" or \"fill\" expected, got \"{word}\")"
                ))
            })?),
            None => None,
        };
        let sent = if data.is_nil() {
            options.as_ref().map_or(Value::Nil, |o| o.data.clone())
        } else {
            data
        };
        let json = self.json(&sent, "data")?;
        let mut ids = Vec::new();
        for player in players {
            match player {
                unions::PlayerOrString::Player(p) => ids.push(p.id),
                unions::PlayerOrString::String(name) => match self.find_player(&name) {
                    Some(found) => ids.push(found.id),
                    None => {
                        let why = format!("nobody called \"{name}\" is online in the network");
                        return Ok(Box::pin(async move { Err(ApiError(why)) }));
                    }
                },
            }
        }
        let wait = options.as_ref().and_then(|o| o.wait);
        if let Some(wait) = wait
            && (!wait.is_finite() || wait < 0.0)
        {
            return Err(err(
                "bad argument 'target.wait' (seconds, at least 0, expected)",
            ));
        }
        let on_wait = options.and_then(|o| o.on_wait).map(|f| (caller.scope, f));
        let namespace = self.namespace(caller.scope);
        let reply = self.send(&namespace, ids, server, json, group, balance, wait, on_wait);
        Ok(Box::pin(async move { reply.await.map_err(ApiError) }))
    }
}

impl NfNetworkApi for NetworkContext {
    fn server_name(&self, _: &Caller) -> ApiResult<Option<String>> {
        Ok(self.link().and_then(|l| l.name()))
    }

    fn server_role(&self, _: &Caller) -> ApiResult<Option<shapes::NetworkRole>> {
        let Some(link) = self.link().filter(|l| l.connected()) else {
            return Ok(None);
        };
        let grants = link.state.borrow().grants.clone();
        Ok(Some(shapes::NetworkRole {
            role: grants.role.clone(),
            trusted: grants.trusted,
            owns: grants.owns.clone(),
            shares: grants
                .shares
                .iter()
                .map(|share| {
                    let mut access = share.access.clone();
                    access.sort();
                    shapes::NetworkShare {
                        name: share.name.clone(),
                        access: access.iter().map(|a| a.word().into()).collect(),
                    }
                })
                .collect(),
            presence: grants.presence,
            send_to: grants.send_to.clone(),
        }))
    }

    fn group(&self, _: &Caller) -> ApiResult<Option<String>> {
        Ok(self.link().and_then(|l| l.group()))
    }

    fn set_accepting(&self, _: &Caller, accepting: bool) -> ApiResult<()> {
        if let Some(link) = self.link() {
            link.set_accepting(accepting);
        }
        Ok(())
    }

    fn state(&self, _: &Caller) -> ApiResult<String> {
        Ok(self
            .link()
            .map_or(Lifecycle::Starting, |l| l.lifecycle())
            .word()
            .into())
    }

    fn hold_ready(&self, caller: &Caller) -> ApiResult<handles::NetworkHold> {
        Ok(self.hold(caller.scope))
    }

    fn set_draining(&self, _: &Caller, draining: bool) -> ApiResult<()> {
        if let Some(link) = self.link() {
            link.set_draining(draining);
        }
        Ok(())
    }

    fn is_draining(&self, _: &Caller) -> ApiResult<bool> {
        Ok(self
            .link()
            .is_some_and(|l| matches!(l.lifecycle(), Lifecycle::Draining | Lifecycle::Stopping)))
    }

    fn is_accepting(&self, _: &Caller) -> ApiResult<bool> {
        Ok(self.link().is_none_or(|l| l.state.borrow().accepting))
    }

    fn is_connected(&self, _: &Caller) -> ApiResult<bool> {
        Ok(self.link().is_some_and(|l| l.connected()))
    }

    fn servers(&self, _: &Caller) -> ApiResult<Vec<shapes::NetworkServer>> {
        let Some(link) = self.link() else {
            return Ok(Vec::new());
        };
        let counts = link.counts();
        Ok(link
            .state
            .borrow()
            .servers
            .values()
            .map(|s| Self::server_shape(s, &counts))
            .collect())
    }

    fn groups(&self, _: &Caller) -> ApiResult<Vec<shapes::NetworkGroup>> {
        let Some(link) = self.link() else {
            return Ok(Vec::new());
        };
        let counts = link.counts();
        let state = link.state.borrow();
        let mut groups: BTreeMap<String, Vec<&ServerState>> = BTreeMap::new();
        for server in state.servers.values() {
            groups
                .entry(server.group_name().to_owned())
                .or_default()
                .push(server);
        }
        Ok(groups
            .into_iter()
            .map(|(name, mut instances)| {
                instances.sort_by(|a, b| a.name.cmp(&b.name));
                shapes::NetworkGroup {
                    name,
                    players: instances
                        .iter()
                        .map(|s| counts.get(&s.name).copied().unwrap_or(0))
                        .sum(),
                    capacity: instances
                        .iter()
                        .map(|s| s.capacity.map(i64::from))
                        .sum::<Option<i64>>(),
                    instances: instances
                        .iter()
                        .map(|s| Self::server_shape(s, &counts))
                        .collect(),
                }
            })
            .collect())
    }

    fn players(
        &self,
        _: &Caller,
        options: Option<shapes::NetworkPlayersOptions>,
    ) -> ApiResult<Vec<shapes::NetworkPlayer>> {
        let (server, group) = match options {
            Some(o) => (
                o.server
                    .map(|s| check_server(s, "options.server"))
                    .transpose()?,
                o.group
                    .map(|g| check_server(g, "options.group"))
                    .transpose()?,
            ),
            None => (None, None),
        };
        let Some(link) = self.link() else {
            return Ok(Vec::new());
        };
        let state = link.state.borrow();
        Ok(state
            .players
            .values()
            .filter(|p| {
                server.as_ref().is_none_or(|s| {
                    p.server
                        .as_ref()
                        .is_some_and(|on| on.eq_ignore_ascii_case(s))
                }) && group.as_ref().is_none_or(|g| {
                    p.server.as_ref().is_some_and(|on| {
                        state
                            .servers
                            .get(on)
                            .map_or(on.as_str(), |s| s.group_name())
                            .eq_ignore_ascii_case(g)
                    })
                })
            })
            .map(|p| shapes::NetworkPlayer {
                id: p.id.clone(),
                name: p.name.clone(),
                server: p.server.clone(),
                proxy: p.proxy.clone(),
            })
            .collect())
    }

    fn find_player(&self, _: &Caller, player: String) -> ApiResult<Option<shapes::NetworkPlayer>> {
        Ok(
            NetworkContext::find_player(self, &player).map(|p| shapes::NetworkPlayer {
                id: p.id,
                name: p.name,
                server: p.server,
                proxy: p.proxy,
            }),
        )
    }

    fn channel(&self, caller: &Caller, name: String) -> ApiResult<handles::NetworkChannel> {
        let namespace = self.namespace(caller.scope);
        let full = self.resolve(&namespace, &name, "nf.network.channel")?;
        Ok(NetworkContext::channel(self, full))
    }

    fn map(&self, caller: &Caller, name: String) -> ApiResult<handles::NetworkMap> {
        let namespace = self.namespace(caller.scope);
        let full = self.resolve(&namespace, &name, "nf.network.map")?;
        self.shared.network.borrow_mut().mirror(&full);
        Ok(handles::NetworkMap { name: full })
    }

    fn ban(
        &self,
        caller: &Caller,
        player: unions::PlayerOrString,
        options: Option<shapes::NetworkSanctionOptions>,
    ) -> ApiResult<Pending<bool>> {
        self.sanction(caller, ModerationAction::Ban, player, options)
    }

    fn unban(&self, caller: &Caller, player: unions::PlayerOrString) -> ApiResult<Pending<bool>> {
        self.sanction(caller, ModerationAction::Unban, player, None)
    }

    fn mute(
        &self,
        caller: &Caller,
        player: unions::PlayerOrString,
        options: Option<shapes::NetworkSanctionOptions>,
    ) -> ApiResult<Pending<bool>> {
        self.sanction(caller, ModerationAction::Mute, player, options)
    }

    fn unmute(&self, caller: &Caller, player: unions::PlayerOrString) -> ApiResult<Pending<bool>> {
        self.sanction(caller, ModerationAction::Unmute, player, None)
    }

    fn is_banned(&self, _: &Caller, player: unions::PlayerOrString) -> ApiResult<bool> {
        let (id, _) = self.who(player)?;
        Ok(self
            .shared
            .network
            .borrow()
            .sanction(protocol::BANS, &id)
            .is_some())
    }

    fn is_muted(&self, _: &Caller, player: unions::PlayerOrString) -> ApiResult<bool> {
        let (id, _) = self.who(player)?;
        Ok(self
            .shared
            .network
            .borrow()
            .sanction(protocol::MUTES, &id)
            .is_some())
    }

    fn report(
        &self,
        caller: &Caller,
        player: unions::PlayerOrString,
        reason: String,
        details: Value,
        by: Option<handles::Player>,
    ) -> ApiResult<bool> {
        if reason.trim().is_empty() {
            return Err(err("bad argument 'reason' (a reason expected)"));
        }
        let (id, name) = self.who(player)?;
        let json = self.json(&details, "details")?;
        let reporter = by.map(|p| {
            parse_uuid(&p.id)
                .and_then(|u| self.shared.platform.players().get(u))
                .map_or_else(|| p.id.clone(), |r| r.name)
        });
        let namespace = self.namespace(caller.scope);
        let reason =
            String::from_utf16_lossy(&reason.encode_utf16().take(500).collect::<Vec<u16>>());
        Ok(NetworkContext::report(
            self, &namespace, id, name, reason, json, reporter,
        ))
    }

    fn send_player(
        &self,
        caller: &Caller,
        player: unions::PlayerOrString,
        target: unions::StringOrNetworkSendOptions,
        data: Value,
    ) -> ApiResult<Pending<String>> {
        self.send_to(caller, vec![player], target, data)
    }

    fn send_players(
        &self,
        caller: &Caller,
        players: Vec<unions::PlayerOrString>,
        target: unions::StringOrNetworkSendOptions,
        data: Value,
    ) -> ApiResult<Pending<String>> {
        if players.is_empty() {
            return Err(err("bad argument 'players' (at least one player expected)"));
        }
        self.send_to(caller, players, target, data)
    }
}

impl NetworkHoldApi for NetworkContext {
    fn release(&self, this: &handles::NetworkHold) -> ApiResult<()> {
        NetworkContext::release(self, this);
        Ok(())
    }

    fn is_released(&self, this: &handles::NetworkHold) -> ApiResult<bool> {
        Ok(NetworkContext::is_released(self, this))
    }
}

impl NetworkChannelApi for NetworkContext {
    fn name(&self, this: &handles::NetworkChannel) -> ApiResult<String> {
        Ok(NetworkContext::spell(&this.name, &self.calling()))
    }

    fn publish(
        &self,
        this: &handles::NetworkChannel,
        value: Value,
        options: Option<shapes::NetworkPublishOptions>,
    ) -> ApiResult<bool> {
        let (server, group) = match options {
            Some(o) => (
                o.server
                    .map(|s| check_server(s, "options.server"))
                    .transpose()?,
                o.group
                    .map(|g| check_server(g, "options.group"))
                    .transpose()?,
            ),
            None => (None, None),
        };
        if server.is_some() && group.is_some() {
            return Err(err(
                "bad argument 'options' (a server or a group, not both)",
            ));
        }
        let json = self.required(&value, "value")?;
        NetworkContext::publish(self, &self.calling(), &this.name, json, server, group)
    }

    fn request(
        &self,
        this: &handles::NetworkChannel,
        target: unions::StringOrNetworkTarget,
        data: Value,
    ) -> ApiResult<Pending<Value>> {
        let (server, group, admin) = match target {
            unions::StringOrNetworkTarget::String(name) => {
                (Some(check_server(name, "target")?), None, None)
            }
            unions::StringOrNetworkTarget::NetworkTarget(t) => {
                let named = [t.server.is_some(), t.group.is_some(), t.admin.is_some()]
                    .iter()
                    .filter(|b| **b)
                    .count();
                if named != 1 {
                    return Err(err(
                        "bad argument 'target' (a table naming one of a server, a group or an admin client expected)",
                    ));
                }
                if let Some(admin) = &t.admin
                    && !protocol::is_credential(admin)
                {
                    return Err(err("bad argument 'target.admin' (a client's id expected)"));
                }
                (
                    t.server
                        .map(|s| check_server(s, "target.server"))
                        .transpose()?,
                    t.group
                        .map(|g| check_server(g, "target.group"))
                        .transpose()?,
                    t.admin,
                )
            }
        };
        let json = self.required(&data, "data")?;
        let reply = NetworkContext::request(
            self,
            &self.calling(),
            &this.name,
            server,
            json,
            group,
            admin,
        )?;
        let lua = self.scripts.host().lua().weak();
        Ok(Box::pin(async move {
            let value = reply.await.map_err(ApiError)?;
            let lua = lua.try_upgrade().ok_or_else(|| err("the server stopped"))?;
            NetworkContext::to_lua(&lua, value.as_ref()).map_err(|e| err(e.to_string()))
        }))
    }

    fn on(
        &self,
        this: &handles::NetworkChannel,
        event: String,
        handler: Function,
        options: Option<shapes::EventOptions>,
    ) -> ApiResult<handles::Subscription> {
        self.subscribe(
            Handle::NetworkChannel(this.clone()),
            "NetworkChannel:on",
            event,
            handler,
            options,
            false,
        )
    }

    fn once(
        &self,
        this: &handles::NetworkChannel,
        event: String,
        handler: Function,
    ) -> ApiResult<handles::Subscription> {
        self.subscribe(
            Handle::NetworkChannel(this.clone()),
            "NetworkChannel:once",
            event,
            handler,
            None,
            true,
        )
    }
}

impl NetworkContext {
    fn write_map(
        &self,
        this: &handles::NetworkMap,
        key: String,
        op: MapOp,
        value: Option<serde_json::Value>,
        amount: Option<f64>,
        expires: Option<i64>,
    ) -> ApiResult<Option<crate::http::Reply<Written>>> {
        self.shared.network.borrow_mut().mirror(&this.name);
        self.write(
            &self.calling(),
            &this.name,
            &key,
            op,
            value,
            amount,
            expires,
        )
    }
}

impl NetworkMapApi for NetworkContext {
    fn name(&self, this: &handles::NetworkMap) -> ApiResult<String> {
        Ok(NetworkContext::spell(&this.name, &self.calling()))
    }

    fn is_ready(&self, this: &handles::NetworkMap) -> ApiResult<bool> {
        Ok(self.shared.network.borrow_mut().mirror(&this.name).ready)
    }

    fn get(&self, this: &handles::NetworkMap, key: String) -> ApiResult<Value> {
        let value = self
            .shared
            .network
            .borrow_mut()
            .mirror(&this.name)
            .entries
            .get(&key)
            .cloned();
        NetworkContext::to_lua(self.scripts.host().lua(), value.as_ref())
            .map_err(|e| err(e.to_string()))
    }

    fn all(&self, this: &handles::NetworkMap) -> ApiResult<BTreeMap<String, Value>> {
        let entries: Vec<(String, serde_json::Value)> = self
            .shared
            .network
            .borrow_mut()
            .mirror(&this.name)
            .entries
            .iter()
            .map(|(k, v)| (k.clone(), v.clone()))
            .collect();
        let lua = self.scripts.host().lua().clone();
        entries
            .into_iter()
            .map(|(k, v)| {
                NetworkContext::to_lua(&lua, Some(&v))
                    .map(|v| (k, v))
                    .map_err(|e| err(e.to_string()))
            })
            .collect()
    }

    fn set(
        &self,
        this: &handles::NetworkMap,
        key: String,
        value: Value,
        options: Option<shapes::NetworkMapWriteOptions>,
    ) -> ApiResult<bool> {
        let expires = options.and_then(|o| o.expires);
        if value.is_nil() {
            return Ok(self
                .write_map(this, key, MapOp::Delete, None, None, expires)?
                .is_some());
        }
        let json = self.json(&value, "value")?;
        Ok(self
            .write_map(this, key, MapOp::Set, json, None, expires)?
            .is_some())
    }

    fn delete(&self, this: &handles::NetworkMap, key: String) -> ApiResult<bool> {
        Ok(self
            .write_map(this, key, MapOp::Delete, None, None, None)?
            .is_some())
    }

    fn add(
        &self,
        this: &handles::NetworkMap,
        key: String,
        amount: f64,
        options: Option<shapes::NetworkMapWriteOptions>,
    ) -> ApiResult<Pending<f64>> {
        if !amount.is_finite() {
            return Err(err(format!(
                "bad argument 'amount' (a finite number expected, got {amount})"
            )));
        }
        let reply = self
            .write_map(
                this,
                key,
                MapOp::Add,
                None,
                Some(amount),
                options.and_then(|o| o.expires),
            )?
            .expect("an add always waits");
        Ok(Box::pin(async move {
            match reply.await.map_err(ApiError)? {
                Written::Total(total) => Ok(total),
                Written::Done(_) => Ok(0.0),
            }
        }))
    }

    fn insert(
        &self,
        this: &handles::NetworkMap,
        key: String,
        data: Value,
        options: Option<shapes::NetworkMapWriteOptions>,
    ) -> ApiResult<Pending<bool>> {
        let json = self.required(&data, "data")?;
        let reply = self
            .write_map(
                this,
                key,
                MapOp::Insert,
                Some(json),
                None,
                options.and_then(|o| o.expires),
            )?
            .expect("an insert always waits");
        Ok(Box::pin(async move {
            match reply.await.map_err(ApiError)? {
                Written::Done(done) => Ok(done),
                Written::Total(_) => Ok(true),
            }
        }))
    }

    fn on(
        &self,
        this: &handles::NetworkMap,
        event: String,
        handler: Function,
        options: Option<shapes::EventOptions>,
    ) -> ApiResult<handles::Subscription> {
        self.subscribe(
            Handle::NetworkMap(this.clone()),
            "NetworkMap:on",
            event,
            handler,
            options,
            false,
        )
    }

    fn once(
        &self,
        this: &handles::NetworkMap,
        event: String,
        handler: Function,
    ) -> ApiResult<handles::Subscription> {
        self.subscribe(
            Handle::NetworkMap(this.clone()),
            "NetworkMap:once",
            event,
            handler,
            None,
            true,
        )
    }
}
