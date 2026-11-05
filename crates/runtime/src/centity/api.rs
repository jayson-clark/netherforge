//! Centities as scripts see them (Kotlin's `NfCentitiesImpl`, `CentityImpl` and `NodeImpl`), and an
//! instance's life around its script: spawned, its script started with `this` and `spawn` heard; ticked,
//! `tick` raised to whoever listens; removed, `remove` heard before its script and handlers go.
//!
//! A handle names an instance by its id and a node by its instance and name, so a handle outlives what it
//! names: once the instance is gone its getters answer nil (or false, or nothing) and its setters do
//! nothing, as Kotlin's do. What isn't ported yet (animations, physics, hitboxes, health, paths, saved
//! data...) is `Unported`, said function by function below, so the class is whole and the compiler holds it
//! to the spec.

use std::collections::BTreeMap;
use std::rc::{Rc, Weak};

use mlua::{Function, Value};
use netherforge_api::api::{CentityApi, NfCentitiesApi, NodeApi};
use netherforge_api::codec::Codec;
use netherforge_api::handles::{self, Handle};
use netherforge_api::{ApiError, ApiResult, Caller, codecs, handle, shapes, unions, values};
use netherforge_format::kind::centity::{
    Anchor, Billboard, DEFAULT_LINE_WIDTH, DisplayDef, LoopMode, PIXELS_PER_BLOCK, TextAlignment,
};
use netherforge_format::math::{Matrix4, Vec3};
use netherforge_luau::ScopeId;
use netherforge_platform::{ClickButton, Location, PlayerRef, Ray, Uuid};

use super::Instance;
use super::animation::Play;
use super::instance::Channel;
use super::instance::SECONDS_PER_TICK;
use super::quat::{Quat, look_rotation};
use crate::events::{parse_uuid, uuid_text};
use crate::shared::Shared;
use crate::{Scripts, Target};
use netherforge_format::animation::Easing;

fn err(message: impl Into<String>) -> ApiError {
    ApiError(message.into())
}

fn lua_err(e: mlua::Error) -> ApiError {
    err(e.to_string())
}

fn vec(v: values::Vec3) -> Vec3 {
    Vec3(v.x, v.y, v.z)
}

fn value(v: Vec3) -> values::Vec3 {
    values::Vec3 {
        x: v.0,
        y: v.1,
        z: v.2,
    }
}

/// `no centity "dor" in this project (did you mean "door"?)`: Kotlin's `notInProject`.
pub fn not_in_project(kind: &str, name: &str, known: &[String]) -> String {
    let close = known
        .iter()
        .map(|k| (distance(k, name), k))
        .min()
        .filter(|(d, _)| *d <= 2)
        .map(|(_, k)| format!(" (did you mean \"{k}\"?)"))
        .unwrap_or_default();
    format!("no {kind} \"{name}\" in this project{close}")
}

fn distance(a: &str, b: &str) -> usize {
    let b: Vec<char> = b.chars().collect();
    let mut row: Vec<usize> = (0..=b.len()).collect();
    for (i, ca) in a.chars().enumerate() {
        let mut previous = row[0];
        row[0] = i + 1;
        for j in 1..=b.len() {
            let saved = row[j];
            row[j] = (row[j] + 1)
                .min(row[j - 1] + 1)
                .min(previous + usize::from(ca != b[j - 1]));
            previous = saved;
        }
    }
    row[b.len()]
}

/// The centity API and the instances' lives, for one room.
#[derive(Clone)]
pub struct CentityContext {
    pub(crate) shared: Rc<Shared>,
    pub(crate) scripts: Scripts,
}

impl CentityContext {
    pub fn new(shared: Rc<Shared>, scripts: Scripts) -> CentityContext {
        CentityContext { shared, scripts }
    }

    /// Tells `scripts` which centity and node handles still name something: a handle to a removed
    /// instance, or a node it doesn't have, gets subscriptions that never run.
    pub fn install_liveness(shared: &Rc<Shared>, scripts: &Scripts) {
        let weak: Weak<Shared> = Rc::downgrade(shared);
        scripts.add_liveness(Box::new(move |handle| {
            let shared = weak.upgrade()?;
            let centities = shared.centities.try_borrow().ok()?;
            let live = |id: &str| {
                parse_uuid(id)
                    .and_then(|id| centities.get(id))
                    .filter(|i| !i.removed)
            };
            match handle {
                Handle::Centity(c) => Some(live(&c.id).is_some()),
                Handle::Node(n) => {
                    Some(live(&n.centity).is_some_and(|i| i.index_of(&n.name).is_some()))
                }
                _ => None,
            }
        }));
    }

    pub(crate) fn handle_target(&self, handle: Handle) -> Option<Target> {
        let host = self.scripts.host();
        handle::push_any(host.lua(), &handle)
            .ok()
            .map(Target::Handle)
    }

    pub(crate) fn centity_target(&self, id: Uuid) -> Option<Target> {
        self.handle_target(Handle::Centity(handles::Centity { id: uuid_text(&id) }))
    }

    /// Raises built-in `event` on instance `id`, its payload `{ centity = <it> }`.
    fn raise_centity(&self, id: Uuid, event: &str) {
        let Some(target) = self.centity_target(id) else {
            return;
        };
        if !self.scripts.listening(&target, event) {
            return;
        }
        let lua = self.scripts.host().lua().clone();
        let payload = shapes::CentityEvent {
            centity: handles::Centity { id: uuid_text(&id) },
        };
        if let Ok(Value::Table(fields)) = codecs::CentityEvent.push(&lua, payload) {
            self.scripts.raise(&target, event, fields);
        }
    }

    pub(crate) fn live(&self, id: Uuid) -> bool {
        self.shared
            .centities
            .borrow()
            .get(id)
            .is_some_and(|i| !i.removed)
    }

    /// Raises `animation_start` or `animation_end` on instance `id`.
    fn raise_animation(&self, id: Uuid, event: &str, animation: String) {
        let Some(target) = self.centity_target(id) else {
            return;
        };
        if !self.scripts.listening(&target, event) {
            return;
        }
        let lua = self.scripts.host().lua().clone();
        let payload = shapes::AnimationEvent {
            centity: handles::Centity { id: uuid_text(&id) },
            animation,
        };
        if let Ok(Value::Table(fields)) = codecs::AnimationEvent.push(&lua, payload) {
            self.scripts.raise(&target, event, fields);
        }
    }

    /// The index of `this`'s centity's clip `name`; one it doesn't have is an error. None once it's gone.
    fn clip(&self, this: &handles::Centity, name: &str) -> ApiResult<Option<usize>> {
        let Some(definition) = self.with(this, |i| i.definition.clone()) else {
            return Ok(None);
        };
        match definition.animations.iter().position(|a| a.name == name) {
            Some(index) => Ok(Some(index)),
            None => {
                let known: Vec<&str> = definition
                    .animations
                    .iter()
                    .map(|a| a.name.as_str())
                    .collect();
                let has = if known.is_empty() {
                    String::new()
                } else {
                    format!(" (it has: {})", known.join(", "))
                };
                Err(err(format!(
                    "{} has no animation \"{name}\"{has}",
                    definition.id
                )))
            }
        }
    }

    // ---- stats, buffs and health ----

    pub(crate) fn holder(&self, this: &handles::Centity) -> Option<crate::stats::Holder> {
        self.with(this, |i| crate::stats::Holder::Centity(i.id))
    }

    /// A buff a script names; one the project doesn't have is an error.
    fn buff(&self, name: &str) -> ApiResult<String> {
        if self.shared.stats.borrow().has(name) {
            return Ok(name.to_owned());
        }
        let file = name.split_once(':').map_or(name, |(_, f)| f);
        Err(err(format!(
            "there's no buff \"{name}\" (buffs/{file}.json)"
        )))
    }

    /// Raises `event` on centity `id` with `payload` (pushed by `codec`): what handlers made of it, none
    /// when nothing listens.
    pub(crate) fn raise_on<C: Codec>(
        &self,
        id: Uuid,
        event: &str,
        codec: C,
        payload: C::T,
    ) -> Option<crate::Delivered> {
        let target = self.centity_target(id)?;
        if !self.scripts.listening(&target, event) {
            return None;
        }
        let lua = self.scripts.host().lua().clone();
        let Ok(Value::Table(fields)) = codec.push(&lua, payload) else {
            return None;
        };
        Some(self.scripts.raise(&target, event, fields))
    }

    /// What a handler left in writable field `field`; none when it can't be read.
    pub(crate) fn field<C: Codec>(
        &self,
        delivered: &crate::Delivered,
        field: &str,
        codec: C,
    ) -> Option<C::T> {
        let lua = self.scripts.host().lua().clone();
        let value: Value = delivered.fields.raw_get(field).ok()?;
        codec.read(&lua, value, &format!("event.{field}")).ok()
    }

    /// Gives `holder` `buff` for `ticks` through its rules and its `buff_add` handlers: whether it took hold.
    fn give_buff(&self, holder: crate::stats::Holder, buff: &str, ticks: i64) -> bool {
        if self.shared.stats.borrow().blocked(holder, buff) {
            return false;
        }
        let crate::stats::Holder::Centity(id) = holder else {
            return false;
        };
        let event = shapes::BuffEvent {
            buff: buff.to_owned(),
            ticks,
            player: None,
            centity: Some(handles::Centity { id: uuid_text(&id) }),
        };
        let mut given = ticks;
        if let Some(delivered) = self.raise_on(id, "buff_add", codecs::BuffEvent, event) {
            if delivered.cancelled {
                return false;
            }
            given = self
                .field(&delivered, "ticks", netherforge_api::codec::Int)
                .unwrap_or(ticks);
        }
        let cured = self.shared.stats.borrow_mut().add(holder, buff, given);
        self.buffs_ended(cured);
        true
    }

    /// `buff_end` for each buff that ended.
    fn buffs_ended(&self, ended: Vec<crate::stats::Ended>) {
        for end in ended {
            let crate::stats::Holder::Centity(id) = end.holder else {
                continue;
            };
            let event = shapes::BuffEndEvent {
                buff: end.buff,
                reason: end.reason.to_owned(),
                player: None,
                centity: Some(handles::Centity { id: uuid_text(&id) }),
            };
            self.raise_on(id, "buff_end", codecs::BuffEndEvent, event);
        }
    }

    /// The buffs as the project's files say now: those whose file is gone end.
    pub fn define_buffs(&self, files: BTreeMap<String, netherforge_format::kind::buff::BuffFile>) {
        let ended = self.shared.stats.borrow_mut().define(files);
        self.buffs_ended(ended);
    }

    /// Buffs count down a tick, on every centity still in the world.
    fn tick_stats(&self) {
        let ended = {
            let centities = self.shared.centities.borrow();
            let present = |holder: crate::stats::Holder| match holder {
                crate::stats::Holder::Centity(id) => centities.get(id).is_some_and(|i| !i.removed),
                // Players' stats are ported with players.
                crate::stats::Holder::Player(_) => true,
            };
            self.shared.stats.borrow_mut().tick(&present)
        };
        self.buffs_ended(ended);
    }

    /// Deals `amount` to centity `id` through its `damage` handlers: what was taken, none when it has no
    /// health or is dead. At 0 it dies (`death`), and is removed when its health says so.
    pub fn deal_damage(
        &self,
        id: Uuid,
        amount: f64,
        source: Option<Handle>,
        cause: Option<String>,
    ) -> Option<f64> {
        let before = self.shared.centities.borrow().get(id)?.health.clone()?;
        if before.dead {
            return None;
        }
        let event = shapes::CentityDamageEvent {
            centity: handles::Centity { id: uuid_text(&id) },
            amount,
            source: source.clone(),
            cause: cause.clone(),
        };
        let mut amount = amount;
        if let Some(delivered) = self.raise_on(id, "damage", codecs::CentityDamageEvent, event) {
            let still = self
                .shared
                .centities
                .borrow()
                .get(id)
                .filter(|i| !i.removed)
                .and_then(|i| i.health.clone());
            if still.is_none_or(|h| h.dead || h.max != before.max) {
                return None;
            }
            if delivered.cancelled {
                return Some(0.0);
            }
            amount = self
                .field(&delivered, "amount", netherforge_api::codec::Num)
                .unwrap_or(amount);
        }
        let now = self.scripts.tick_count();
        let (taken, died, remove) = {
            let mut centities = self.shared.centities.borrow_mut();
            let instance = centities.get_mut(id)?;
            let h = instance.health.as_mut()?;
            let taken = amount.max(0.0).min(h.current);
            let taken = if taken.is_finite() { taken } else { 0.0 };
            h.current -= taken;
            let died = h.current <= 0.0;
            if died {
                h.current = 0.0;
                h.dead = true;
            }
            let remove = h.remove_on_death;
            super::health::draw(instance, true, now);
            (taken, died, remove)
        };
        if died {
            let event = shapes::CentityDeathEvent {
                centity: handles::Centity { id: uuid_text(&id) },
                source,
                cause,
            };
            self.raise_on(id, "death", codecs::CentityDeathEvent, event);
            let dead = self
                .shared
                .centities
                .borrow()
                .get(id)
                .is_some_and(|i| !i.removed && i.health.as_ref().is_some_and(|h| h.dead));
            if remove && dead {
                CentityContext::remove(self, id);
            }
        }
        Some(taken)
    }

    // ---- following a player ----

    /// Makes instance `id` ride `player`: its anchor entity mounts them, and each tick it moves to their place
    /// and turns to their body. False when they're offline. A walk it was on ends.
    pub(crate) fn follow_player(&self, id: Uuid, player: Uuid) -> bool {
        let Some(at) = self.shared.platform.players().location(player) else {
            return false;
        };
        if let Some(i) = self.shared.centities.borrow_mut().get_mut(id) {
            i.walk = None;
            i.follower = Some(player);
        }
        self.follow_at(id, at);
        true
    }

    /// Stops following, where the centity is now. False when it wasn't following.
    pub(crate) fn unfollow(&self, id: Uuid) -> bool {
        let mut centities = self.shared.centities.borrow_mut();
        let Some(i) = centities.get_mut(id) else {
            return false;
        };
        if i.follower.take().is_none() {
            return false;
        }
        if std::mem::take(&mut i.follow_mounted)
            && let Some(vehicle) = i.anchor_entity
        {
            let entities: &dyn netherforge_platform::EntityOps = if i.viewers.is_some() {
                self.shared.platform.client_entities()
            } else {
                self.shared.platform.entities()
            };
            entities.dismount(vehicle);
        }
        true
    }

    /// Moves a following centity to its player (each tick, before its sync).
    fn follow_tick(&self, id: Uuid) {
        let Some(player) = self
            .shared
            .centities
            .borrow()
            .get(id)
            .and_then(|i| i.follower)
        else {
            return;
        };
        match self.shared.platform.players().location(player) {
            Some(at) => self.follow_at(id, at),
            None => {
                // Offline: the anchor is left where it was, as after `stop_following`.
                if let Some(i) = self.shared.centities.borrow_mut().get_mut(id) {
                    i.follower = None;
                    i.follow_mounted = false;
                }
            }
        }
    }

    /// Puts the anchor at `at` and turns the centity to the player's body, then mounts the anchor entity on
    /// the player (which also puts it back after something got it off).
    fn follow_at(&self, id: Uuid, at: Location) {
        let platform = &*self.shared.platform;
        let mut centities = self.shared.centities.borrow_mut();
        let Some(i) = centities.get_mut(id) else {
            return;
        };
        let anchor = Location {
            yaw: 0.0,
            pitch: 0.0,
            ..at.clone()
        };
        if *i.anchor() != anchor {
            i.set_anchor(anchor);
        }
        let player = i.follower;
        i.turn_to(
            player
                .and_then(|p| platform.world_entities().body_yaw(p))
                .unwrap_or(at.yaw),
        );
        let entities: &dyn netherforge_platform::EntityOps = if i.viewers.is_some() {
            platform.client_entities()
        } else {
            platform.entities()
        };
        i.follow_mounted = match (i.anchor_entity, player) {
            (Some(vehicle), Some(player)) => entities.mount(player, vehicle),
            _ => false,
        };
    }

    // ---- clicks ----

    /// Routes a click on one of our entities (Kotlin's `CentityClicks.click`): true when the entity is
    /// ours, whether or not any script wanted the click. When the player's line of sight is known the click
    /// goes to the nearest node it actually hits, not just the entity the client picked: a node whose box sits
    /// in front wins over a sibling whose padding is nearer, and a click through the padding of a `raycast`
    /// node reaches nothing. Then along its path: the node, each node above it, the centity, then
    /// `nf.on("centity_click")`, until a handler stops it.
    pub fn click(
        &self,
        entity: Uuid,
        player: &PlayerRef,
        button: ClickButton,
        sight: Option<Ray>,
    ) -> bool {
        let picked = {
            let mut centities = self.shared.centities.borrow_mut();
            let Some((id, name)) = centities.owner_of(entity).cloned() else {
                return false;
            };
            let Some(instance) = centities.get_mut(id) else {
                return true;
            };
            // The server keeps a hidden interaction from being clicked; this says so for any platform.
            if instance.removed || instance.hidden_from.contains(&player.uuid) {
                return true;
            }
            let Some(clicked) = instance.index_of(&name) else {
                return true;
            };
            let Some(picked) = pick(
                self.shared.platform.game(),
                instance,
                clicked,
                sight.as_ref(),
            ) else {
                return true;
            };
            // `spawning.keep_on_interact`: a click that reaches a node keeps a natural one, before scripts hear it.
            let keep = instance.natural
                && instance
                    .definition
                    .spawning
                    .as_ref()
                    .and_then(|s| s.keep_on_interact)
                    == Some(true);
            if keep {
                instance.natural = false;
                for entity in instance.entity_ids() {
                    self.shared.platform.entities().set_persistent(entity, true);
                }
            }
            let nodes = &instance.definition.nodes;
            let mut path = Vec::new();
            let mut at = picked.index as i32;
            while at >= 0 {
                path.push(nodes[at as usize].name.clone());
                at = nodes[at as usize].parent_index;
            }
            (id, path, picked, keep)
        };
        let (id, path, picked, kept) = picked;
        if kept {
            self.shared.centities.borrow_mut().changed(id);
        }
        let centity = uuid_text(&id);
        let mut stages: Vec<(Target, &str)> = path
            .iter()
            .filter_map(|name| {
                let node = Handle::Node(Self::node_handle(&centity, name));
                Some((self.handle_target(node)?, "click"))
            })
            .collect();
        if let Some(target) = self.centity_target(id) {
            stages.push((target, "click"));
        }
        stages.push((Target::Nf, "centity_click"));
        if !stages.iter().any(|(t, e)| self.scripts.listening(t, e)) {
            return true;
        }
        let lua = self.scripts.host().lua().clone();
        let event = shapes::ClickEvent {
            player: handles::Player {
                id: uuid_text(&player.uuid),
            },
            target: Self::node_handle(&centity, &path[0]),
            click: button.lua_name().to_owned(),
            hit_position: picked.position.map(value),
            hit_normal: picked.normal.map(value),
        };
        if let Ok(Value::Table(fields)) = codecs::ClickEvent.push(&lua, event) {
            self.scripts.raise_path(&stages, fields, false);
        }
        true
    }

    // ---- lives ----

    /// Puts a new instance of `centity` into the world at `at`, facing `yaw`: its entities made, its
    /// script's body run and `spawn` heard. None when there's no such centity or world.
    pub fn spawn(&self, centity: &str, at: Location, yaw: f64) -> Option<Uuid> {
        self.spawn_with(centity, at, yaw, &|_| {})
    }

    /// `spawn`, with `setup` run on the instance before anything of it is made (temporary, private).
    pub fn spawn_with(
        &self,
        centity: &str,
        at: Location,
        yaw: f64,
        setup: &dyn Fn(&mut Instance),
    ) -> Option<Uuid> {
        let platform = &*self.shared.platform;
        let id = self
            .shared
            .with_drawing(|c, drawing| c.spawn(platform, drawing, centity, at, yaw, setup))?;
        self.start_script(id, true);
        Some(id)
    }

    /// Runs instance `id`'s centity's script with `this` its handle; then, for a new instance, `spawn`.
    pub fn start_script(&self, id: Uuid, fresh: bool) {
        let centity = match self.shared.centities.borrow().get(id) {
            Some(i) if !i.removed && i.scope.is_none() => i.centity().to_owned(),
            _ => return,
        };
        let script = self.shared.centity_scripts.borrow().get(&centity).cloned();
        let platform = &*self.shared.platform;
        if let Some(i) = self.shared.centities.borrow_mut().get_mut(id) {
            i.chunk_loaded = Some(super::Centities::loaded(platform, i));
        }
        let modules = self.shared.modules.borrow().clone();
        if let (Some(path), Some(modules)) = (script, modules) {
            let this = handle::push_any(
                self.scripts.host().lua(),
                &Handle::Centity(handles::Centity { id: uuid_text(&id) }),
            );
            if let Ok(this) = this {
                let short: String = uuid_text(&id).chars().take(8).collect();
                let folder = path.rsplit_once('/').map_or(path.as_str(), |(f, _)| f);
                let (scope, _) = modules.script_with(
                    &format!("centity {centity} {short}"),
                    folder,
                    &path,
                    &[("this", Value::UserData(this))],
                );
                if let Some(instance) = self.shared.centities.borrow_mut().get_mut(id) {
                    instance.scope = scope;
                }
            }
            // Its body may have removed it.
            if self
                .shared
                .centities
                .borrow()
                .get(id)
                .is_none_or(|i| i.removed)
            {
                return;
            }
        }
        if fresh {
            self.raise_centity(id, "spawn");
        }
    }

    /// Ends instance `id`'s script (its `unload` handlers run), leaving it in the world.
    pub fn stop_script(&self, id: Uuid) {
        let scope = self
            .shared
            .centities
            .borrow_mut()
            .get_mut(id)
            .and_then(|i| i.scope.take());
        if let Some(scope) = scope {
            match self.shared.modules.borrow().clone() {
                Some(modules) => modules.close(scope),
                None => self.scripts.close(scope),
            }
        }
    }

    /// Takes instance `id` out of the world for good: `remove`, then its script ends, then every handler on
    /// it and its nodes goes, then its entities.
    pub fn remove(&self, id: Uuid) -> bool {
        {
            let mut centities = self.shared.centities.borrow_mut();
            let Some(instance) = centities.get_mut(id) else {
                return false;
            };
            if instance.removed || instance.removing {
                return false;
            }
            // Still itself while `remove` handlers run; a second remove from one of them does nothing.
            instance.removing = true;
        }
        self.raise_centity(id, "remove");
        self.shut_all(id);
        let nodes: Vec<String> = match self.shared.centities.borrow_mut().get_mut(id) {
            Some(instance) => {
                instance.removed = true;
                instance
                    .definition
                    .nodes
                    .iter()
                    .map(|n| n.name.clone())
                    .collect()
            }
            None => Vec::new(),
        };
        self.stop_script(id);
        self.drop_targets(id, &nodes);
        self.shared
            .centities
            .borrow_mut()
            .remove(&*self.shared.platform, id);
        true
    }

    fn drop_targets(&self, id: Uuid, nodes: &[String]) {
        if let Some(target) = self.centity_target(id) {
            self.scripts.drop_target(&target);
        }
        for name in nodes {
            self.drop_node(id, name);
        }
    }

    fn drop_node(&self, id: Uuid, name: &str) {
        let node = Handle::Node(handles::Node {
            centity: uuid_text(&id),
            name: name.to_owned(),
        });
        if let Some(target) = self.handle_target(node) {
            self.scripts.drop_target(&target);
        }
    }

    /// The temporary instances whose scope has gone (it closed, or its body failed): they go with it.
    pub fn scopes_released(&self) {
        let released = self.scripts.take_released();
        if released.is_empty() {
            return;
        }
        self.release_conditions(&released);
        let doomed: Vec<Uuid> = self
            .shared
            .centities
            .borrow()
            .all()
            .filter(|i| i.temporary && !i.removed && i.owner.is_some_and(|o| released.contains(&o)))
            .map(|i| i.id)
            .collect();
        for id in doomed {
            self.remove(id);
        }
    }

    /// One tick of every instance: lifetimes run out (loaded or not), then each one's `tick` to whoever
    /// listens on it, then its entities synced.
    pub fn tick(&self) {
        let now = self.scripts.tick_count();
        let expired: Vec<Uuid> = self
            .shared
            .centities
            .borrow()
            .all()
            .filter(|i| !i.removed && i.expires.is_some_and(|e| now >= e))
            .map(|i| i.id)
            .collect();
        for id in expired {
            self.remove(id);
        }
        self.tick_stats();
        self.shared.path_nodes.set(0);
        let ids: Vec<Uuid> = self.shared.centities.borrow().all().map(|i| i.id).collect();
        for id in ids {
            // Into or out of loaded chunks: `chunk_load` or `chunk_unload`; one that isn't loaded doesn't tick.
            let (was, loaded) = {
                let platform = &*self.shared.platform;
                let mut centities = self.shared.centities.borrow_mut();
                let Some(instance) = centities.get_mut(id).filter(|i| !i.removed) else {
                    continue;
                };
                let loaded = super::Centities::loaded(platform, instance);
                (instance.chunk_loaded.replace(loaded), loaded)
            };
            if was.is_some_and(|was| was != loaded) {
                self.raise_centity(id, if loaded { "chunk_load" } else { "chunk_unload" });
            }
            if !loaded || !self.live(id) {
                continue;
            }
            if let Some(instance) = self.shared.centities.borrow_mut().get_mut(id) {
                instance.age += 1;
            }
            if let Some(i) = self.shared.centities.borrow_mut().get_mut(id) {
                super::health::hide_bar(i, now);
            }
            // Tweens move the rest pose, which the clips then play over.
            if let Some(i) = self.shared.centities.borrow_mut().get_mut(id)
                && !i.tweens.is_empty()
            {
                i.step_tweens();
            }
            let ended = self.shared.centities.borrow_mut().animate(id);
            for clip in ended {
                if !self.live(id) {
                    break;
                }
                self.raise_animation(id, "animation_end", clip);
            }
            if !self.live(id) {
                continue;
            }
            // A follow moves it to its player first, as a walk does; it never has both.
            if self
                .shared
                .centities
                .borrow()
                .get(id)
                .is_some_and(|i| i.follower.is_some())
            {
                self.follow_tick(id);
            }
            // Walking moves the whole centity first, so physics settles its bodies where it has been walked to.
            if self
                .shared
                .centities
                .borrow()
                .get(id)
                .is_some_and(|i| i.walk.is_some() && i.flight.is_none())
            {
                self.step_walk(id);
                if !self.live(id) {
                    continue;
                }
            }
            // Doors a walk opened and didn't get to shut shut once it's out of them.
            self.shut_behind(id);
            // A flight moves the whole centity, and nothing else does while it flies.
            if self
                .shared
                .centities
                .borrow()
                .get(id)
                .is_some_and(|i| i.flight.is_some())
            {
                self.step_flight(id);
                if !self.live(id) {
                    continue;
                }
            }
            self.move_body(id);
        }
        // Every body in the room is stepped together, once every instance has moved.
        self.step_physics();
        let ids: Vec<Uuid> = self.shared.centities.borrow().all().map(|i| i.id).collect();
        for id in ids {
            let loaded = self
                .shared
                .centities
                .borrow()
                .get(id)
                .is_some_and(|i| !i.removed && i.chunk_loaded == Some(true));
            if !loaded {
                continue;
            }
            self.raise_tick(id, now);
            if !self.live(id) {
                continue;
            }
            self.lay_ropes(id);
            let platform = &*self.shared.platform;
            self.shared
                .with_drawing(|c, drawing| c.sync(platform, drawing, id));
        }
        self.touches();
        self.natural_tick();
    }

    /// One step of every rigid body, then `collide`, `wake` and `sleep` on their nodes.
    fn step_physics(&self) {
        let (collisions, settled) = {
            let mut centities = self.shared.centities.borrow_mut();
            if !centities
                .all()
                .any(|i| i.bodies.iter().any(Option::is_some))
                && !self.shared.physics.borrow().has_bodies()
            {
                return;
            }
            self.shared
                .physics
                .borrow_mut()
                .step(&*self.shared.platform, &mut centities)
        };
        for c in collisions {
            if !self.live(c.instance) {
                continue;
            }
            let centity = uuid_text(&c.instance);
            let node = Self::node_handle(&centity, &c.node);
            let Some(target) = self.handle_target(Handle::Node(node.clone())) else {
                continue;
            };
            if !self.scripts.listening(&target, "collide") {
                continue;
            }
            let event = shapes::CollideEvent {
                node,
                other: c.other.map(|(o, n)| Self::node_handle(&uuid_text(&o), &n)),
                hit_position: value(c.position),
                hit_normal: value(c.normal),
                speed: c.speed,
            };
            let lua = self.scripts.host().lua().clone();
            if let Ok(Value::Table(fields)) = codecs::CollideEvent.push(&lua, event) {
                self.scripts.raise(&target, "collide", fields);
            }
        }
        for s in settled {
            if !self.live(s.instance) {
                continue;
            }
            let node = Self::node_handle(&uuid_text(&s.instance), &s.node);
            let Some(target) = self.handle_target(Handle::Node(node.clone())) else {
                continue;
            };
            let event = if s.asleep { "sleep" } else { "wake" };
            if !self.scripts.listening(&target, event) {
                continue;
            }
            let lua = self.scripts.host().lua().clone();
            if let Ok(Value::Table(fields)) =
                codecs::NodeEvent.push(&lua, shapes::NodeEvent { node })
            {
                self.scripts.raise(&target, event, fields);
            }
        }
    }

    /// `touch`: after every instance has moved this tick, each one something listens to `touch` on hears what
    /// its hitboxes started touching since the last tick. One nobody listens to keeps no contacts.
    fn touches(&self) {
        let ids: Vec<Uuid> = self.shared.centities.borrow().all().map(|i| i.id).collect();
        let platform = &*self.shared.platform;
        for id in ids {
            let listening = self
                .centity_target(id)
                .is_some_and(|t| self.scripts.listening(&t, "touch"));
            let (now, before, nodes) = {
                let mut centities = self.shared.centities.borrow_mut();
                let Some(instance) = centities.get(id) else {
                    continue;
                };
                if instance.removed {
                    continue;
                }
                if !listening || !super::Centities::loaded(platform, instance) {
                    if let Some(i) = centities.get_mut(id) {
                        i.touching.clear();
                    }
                    continue;
                }
                let now = centities.contacts(platform, id);
                let Some(instance) = centities.get_mut(id) else {
                    continue;
                };
                let before =
                    std::mem::replace(&mut instance.touching, now.keys().copied().collect());
                let names: Vec<String> = instance
                    .definition
                    .nodes
                    .iter()
                    .map(|n| n.name.clone())
                    .collect();
                (now, before, names)
            };
            let centity = uuid_text(&id);
            for (other, contact) in now {
                if before.contains(&other) {
                    continue;
                }
                if !self.live(id) {
                    break;
                }
                let other_node = contact.other.and_then(|(o, n)| {
                    let name = self
                        .shared
                        .centities
                        .borrow()
                        .get(o)?
                        .definition
                        .nodes
                        .get(n)?
                        .name
                        .clone();
                    Some(Self::node_handle(&uuid_text(&o), &name))
                });
                let event = shapes::TouchEvent {
                    centity: handles::Centity {
                        id: centity.clone(),
                    },
                    node: Self::node_handle(&centity, &nodes[contact.node]),
                    other: contact
                        .other
                        .map(|(o, _)| handles::Centity { id: uuid_text(&o) }),
                    other_node,
                    entity: contact
                        .entity
                        .map(|e| handles::Entity { id: uuid_text(&e) }),
                };
                self.raise_on(id, "touch", codecs::TouchEvent, event);
            }
        }
    }

    /// `tick` on instance `id`, to whoever listens.
    fn raise_tick(&self, id: Uuid, now: u64) {
        self.raise_on(
            id,
            "tick",
            codecs::TickEvent,
            shapes::TickEvent { tick: now as i64 },
        );
    }

    /// Moves instance `id`'s body a tick (unless something else moves it), and says how it landed and what it
    /// ran into.
    fn move_body(&self, id: Uuid) {
        let moved = {
            let mut centities = self.shared.centities.borrow_mut();
            let Some(instance) = centities.get_mut(id) else {
                return;
            };
            // A walk or a flight moves it instead.
            if instance.mover.is_none() || instance.walk.is_some() || instance.flight.is_some() {
                return;
            }
            super::movers::step(&*self.shared.platform, instance)
        };
        let Some(moved) = moved else {
            return;
        };
        let centity = handles::Centity { id: uuid_text(&id) };
        if let Some(speed) = moved.landed {
            let event = shapes::CentityLandEvent {
                centity: centity.clone(),
                speed,
            };
            self.raise_on(id, "land", codecs::CentityLandEvent, event);
        }
        for bump in moved.bumps {
            if !self.live(id) {
                break;
            }
            let event = shapes::CentityBumpEvent {
                centity: centity.clone(),
                normal: value(bump.normal),
                speed: bump.speed,
            };
            self.raise_on(id, "bump", codecs::CentityBumpEvent, event);
        }
    }

    /// Where a rope's end is in `world` now; none when it's gone or in another world.
    fn rope_end(&self, end: &super::ropes::RopeEnd, world: &str) -> Option<Vec3> {
        use super::ropes::{ENTITY_MIDDLE, PLAYER_HANDS, RopeEnd};
        match end {
            RopeEnd::Point(at) => Some(*at),
            RopeEnd::OfNode { centity, node } => {
                let centities = self.shared.centities.borrow();
                let other = centities
                    .get(*centity)
                    .filter(|o| !o.removed && o.anchor().world == world)?;
                let index = other.index_of(node)?;
                Some(other.world_matrix(index).translation())
            }
            RopeEnd::OfEntity(entity) => {
                let info = self.shared.platform.world_entities().info(*entity)?;
                let at = info.location;
                if at.world != world {
                    return None;
                }
                let up = if info.player.is_some() {
                    PLAYER_HANDS
                } else {
                    ENTITY_MIDDLE
                };
                Some(Vec3(at.x, at.y + up, at.z))
            }
        }
    }

    /// Lays each strung rope of instance `id` between where its ends are now; one whose end has gone is let go.
    fn lay_ropes(&self, id: Uuid) {
        let (world, ropes) = match self.shared.centities.borrow().get(id) {
            Some(i) if !i.ropes.is_empty() => (i.anchor().world.clone(), i.ropes.clone()),
            _ => return,
        };
        for (name, rope) in ropes {
            let from = self.rope_end(&rope.from, &world);
            let to = self.rope_end(&rope.to, &world);
            let mut centities = self.shared.centities.borrow_mut();
            let Some(instance) = centities.get_mut(id) else {
                return;
            };
            match (instance.index_of(&name), from, to) {
                (Some(index), Some(from), Some(to)) => {
                    super::ropes::lay(instance, index, from, to, rope.length)
                }
                _ => {
                    instance.ropes.remove(&name);
                }
            }
        }
    }

    /// The instances of the centities in `ids` onto their definitions as they read now (`define` has
    /// run): each one's script stops, it moves onto its new definition (one whose centity is gone is
    /// removed, its handlers with it), the handlers on nodes it no longer has go, and its script starts
    /// again, without `spawn`.
    pub fn reload(
        &self,
        ids: &std::collections::BTreeSet<String>,
        before: &BTreeMap<Uuid, Vec<String>>,
    ) {
        for id in before.keys() {
            self.stop_script(*id);
        }
        let platform = &*self.shared.platform;
        self.shared
            .with_drawing(|c, drawing| c.reload(platform, drawing, ids));
        // Inert ones whose centity is back are instances again.
        let revived = self.shared.centities.borrow_mut().revive(platform, ids);
        for id in revived {
            self.start_script(id, false);
        }
        for (id, nodes) in before {
            let now: Option<Vec<String>> = self
                .shared
                .centities
                .borrow()
                .get(*id)
                .map(|i| i.definition.nodes.iter().map(|n| n.name.clone()).collect());
            match now {
                None => self.drop_targets(*id, nodes),
                Some(now) => {
                    for gone in nodes.iter().filter(|n| !now.contains(n)) {
                        self.drop_node(*id, gone);
                    }
                    self.start_script(*id, false);
                }
            }
        }
    }

    /// Each live instance of the centities in `ids`, with its nodes' names: what `reload` is given.
    pub fn instances_of(
        &self,
        ids: &std::collections::BTreeSet<String>,
    ) -> BTreeMap<Uuid, Vec<String>> {
        self.shared
            .centities
            .borrow()
            .all()
            .filter(|i| ids.contains(i.centity()))
            .map(|i| {
                (
                    i.id,
                    i.definition.nodes.iter().map(|n| n.name.clone()).collect(),
                )
            })
            .collect()
    }

    /// Every instance's script ends and its entities go (the session stops; placements aren't saved yet).
    /// The session is ending: a temporary instance belongs to scripts that are stopping, so it goes; every
    /// other one's script stops, its data table, record, place and facing are saved, and it's left in the
    /// world (its entities are persistent) for the next session to restore.
    pub fn stop_all(&self) {
        let temporary: Vec<Uuid> = self
            .shared
            .centities
            .borrow()
            .all()
            .filter(|i| i.temporary)
            .map(|i| i.id)
            .collect();
        for id in temporary {
            self.remove(id);
        }
        let ids: Vec<Uuid> = self.shared.centities.borrow().all().map(|i| i.id).collect();
        for id in ids {
            self.stop_script(id);
        }
        self.save_data();
        self.shared.centity_data.borrow_mut().clear();
        let store = &self.shared.store;
        let mut centities = self.shared.centities.borrow_mut();
        centities.flush(store);
        centities.save_placements(store);
        store.commit();
        *centities = super::Centities::default();
    }

    /// Every `centity:data()` table that changed since it was saved.
    pub fn save_data(&self) {
        let host = self.scripts.host();
        let ids: Vec<Uuid> = self.shared.centity_data.borrow().keys().copied().collect();
        for id in ids {
            let Some(table) = self
                .shared
                .centity_data
                .borrow()
                .get(&id)
                .map(|d| d.table.clone())
            else {
                continue;
            };
            if !self.live(id) {
                self.shared.centity_data.borrow_mut().remove(&id);
                continue;
            }
            let encoded = netherforge_luau::json::encode(
                &host,
                &Value::Table(table),
                "data",
                "save",
                &netherforge_luau::json::NoTags,
            );
            let Ok(encoded) = encoded else { continue };
            for problem in &encoded.problems {
                self.shared.platform.log().warn(format!(
                    "Saving centity {}'s data: {problem}",
                    uuid_text(&id)
                ));
            }
            let Some(text) = encoded.text else { continue };
            if text.len() > MAX_DATA_BYTES {
                self.shared.platform.log().warn(format!(
                    "centity {}'s data takes {:.1} MiB, more than the 1 MiB a table may, so it wasn't saved: the last save stands",
                    uuid_text(&id),
                    text.len() as f64 / 1_048_576.0
                ));
                continue;
            }
            let mut data = self.shared.centity_data.borrow_mut();
            let Some(kept) = data.get_mut(&id) else {
                continue;
            };
            if kept.saved.as_deref() != Some(text.as_str()) {
                kept.saved = Some(text.clone());
                self.shared.store.put_centity_data(id, text);
            }
        }
    }

    /// The store hears what changed this tick; every so often every data table is saved too.
    pub fn commit(&self) {
        if self.scripts.tick_count().is_multiple_of(AUTOSAVE_EVERY) {
            self.save_data();
            self.shared
                .centities
                .borrow_mut()
                .save_placements(&self.shared.store);
        }
        self.shared.centities.borrow_mut().flush(&self.shared.store);
        self.shared.store.commit();
        for failure in self.shared.store.take_failures() {
            self.shared.platform.log().error(failure, None);
        }
    }

    /// The instances the store had, back as they were: their scripts start, without `spawn`.
    pub fn start_restored(&self, ids: &[Uuid]) {
        for id in ids {
            self.start_script(*id, false);
        }
    }

    // ---- looking things up ----

    fn id_of(this: &handles::Centity) -> Option<Uuid> {
        parse_uuid(&this.id)
    }

    /// `f` of the live instance `this` names; none once it's gone.
    pub(crate) fn with<R>(
        &self,
        this: &handles::Centity,
        f: impl FnOnce(&mut Instance) -> R,
    ) -> Option<R> {
        let id = Self::id_of(this)?;
        let mut centities = self.shared.centities.borrow_mut();
        let instance = centities.get_mut(id).filter(|i| !i.removed)?;
        Some(f(instance))
    }

    /// `f` of node `this`'s instance and index; none once either is gone.
    fn with_node<R>(
        &self,
        this: &handles::Node,
        f: impl FnOnce(&mut Instance, usize) -> R,
    ) -> Option<R> {
        let id = parse_uuid(&this.centity)?;
        let mut centities = self.shared.centities.borrow_mut();
        let instance = centities.get_mut(id).filter(|i| !i.removed)?;
        let index = instance.index_of(&this.name)?;
        Some(f(instance, index))
    }

    pub(crate) fn node_handle(centity: &str, name: &str) -> handles::Node {
        handles::Node {
            centity: centity.to_owned(),
            name: name.to_owned(),
        }
    }

    /// The world a bare position means for `scope`'s code: a centity script's own instance's; for anything
    /// else, the server's default.
    fn script_world(&self, scope: ScopeId) -> String {
        let own = self
            .shared
            .centities
            .borrow()
            .all()
            .find(|i| i.scope == Some(scope) && !i.removed)
            .map(|i| i.anchor().world.clone());
        own.unwrap_or_else(|| self.shared.platform.worlds().default_world())
    }

    fn running_scope(&self, called: &str) -> ApiResult<ScopeId> {
        self.scripts
            .host()
            .running_scope()
            .ok_or_else(|| err(format!("{called} was called by no script")))
    }

    fn known(&self) -> Vec<String> {
        self.shared.centities.borrow().definition_ids()
    }

    fn properties_of(
        &self,
        properties: Option<&netherforge_format::canonical::Map<serde_json::Value>>,
    ) -> ApiResult<Value> {
        let host = self.scripts.host();
        let object: serde_json::Map<String, serde_json::Value> = properties
            .map(|m| m.iter().map(|(k, v)| (k.0.clone(), v.clone())).collect())
            .unwrap_or_default();
        netherforge_luau::json::to_lua(
            host.lua(),
            &serde_json::Value::Object(object),
            &netherforge_luau::json::NoTags,
        )
        .map_err(lua_err)
    }
}

impl netherforge_api::api::NfSpawningApi for CentityContext {
    fn condition(&self, caller: &Caller, name: String, callback: Function) -> ApiResult<()> {
        if !netherforge_format::names::is_id(&name) {
            return Err(err(format!(
                "bad argument 'name' (\"{name}\" can't name a spawn condition: {})",
                netherforge_format::names::ID_RULE
            )));
        }
        let namespace = self
            .scripts
            .namespace(caller.scope)
            .unwrap_or_else(|| self.shared.centities.borrow().home().to_owned());
        let key = format!("{namespace}:{name}");
        let mut spawner = self.shared.spawner.borrow_mut();
        if let Some(existing) = spawner.conditions.get(&key) {
            let label = self
                .scripts
                .host()
                .scope_name(existing.scope)
                .unwrap_or_else(|| "a script".into());
            return Err(err(format!(
                "the spawn condition \"{name}\" is already registered by {label}"
            )));
        }
        spawner.conditions.insert(
            key,
            super::natural::Condition {
                scope: caller.scope,
                name,
                callback,
            },
        );
        Ok(())
    }
}

impl NfCentitiesApi for CentityContext {
    fn properties(&self, _: &Caller, kind: String) -> ApiResult<Value> {
        let definition = self.shared.centities.borrow().definition(&kind).cloned();
        let Some(definition) = definition else {
            return Err(err(not_in_project("centity", &kind, &self.known())));
        };
        self.properties_of(definition.properties.as_ref())
    }

    fn spawn(
        &self,
        caller: &Caller,
        kind: String,
        location_or_position: unions::LocationOrVec3,
        options: Option<shapes::CentitySpawnOptions>,
    ) -> ApiResult<Option<handles::Centity>> {
        let lifetime = options.as_ref().and_then(|o| o.lifetime);
        if let Some(l) = lifetime
            && l < 1
        {
            return Err(err(format!(
                "bad argument 'options.lifetime' (at least 1 expected, got {l})"
            )));
        }
        let temporary =
            options.as_ref().and_then(|o| o.temporary) == Some(true) || lifetime.is_some();
        let viewers: Option<indexmap::IndexSet<Uuid>> = options
            .as_ref()
            .and_then(|o| o.viewers.as_ref())
            .map(|v| v.iter().filter_map(|p| parse_uuid(&p.id)).collect());
        if viewers.is_some() && !temporary {
            return Err(err(
                "bad argument 'options.viewers' (only a temporary centity can be private: give it `temporary = true` or a `lifetime`)",
            ));
        }
        let now = self.scripts.tick_count();
        let owner = caller.scope;
        if self.shared.centities.borrow().definition(&kind).is_none() {
            return Err(err(not_in_project("centity", &kind, &self.known())));
        }
        let (world, position, yaw) = match location_or_position {
            unions::LocationOrVec3::Location(l) => (l.world.name, l.position, l.yaw),
            unions::LocationOrVec3::Vec3(p) => (self.script_world(caller.scope), p, None),
        };
        let at = Location {
            world,
            x: position.x,
            y: position.y,
            z: position.z,
            yaw: 0.0,
            pitch: 0.0,
        };
        let setup = |i: &mut Instance| {
            if temporary {
                i.temporary = true;
                i.owner = Some(owner);
                i.expires = lifetime.map(|l| now + l as u64);
            }
            i.viewers = viewers.clone();
        };
        Ok(self
            .spawn_with(&kind, at, yaw.unwrap_or(0.0), &setup)
            .map(|id| handles::Centity { id: uuid_text(&id) }))
    }

    fn get(&self, _: &Caller, id: String) -> ApiResult<Option<handles::Centity>> {
        let this = handles::Centity { id };
        Ok(self.with(&this, |_| ()).map(|_| this))
    }

    fn all(
        &self,
        _: &Caller,
        filter: Option<shapes::CentityFilter>,
    ) -> ApiResult<Vec<handles::Centity>> {
        let filter = filter.unwrap_or(shapes::CentityFilter {
            kind: None,
            world: None,
            near: None,
            radius: None,
        });
        if filter.near.is_none() != filter.radius.is_none() {
            return Err(err("filter.near and filter.radius go together"));
        }
        let centities = self.shared.centities.borrow();
        Ok(centities
            .all()
            .filter(|i| {
                let at = i.anchor();
                let near = match (&filter.near, filter.radius) {
                    (Some(p), Some(r)) => {
                        let (dx, dy, dz) = (at.x - p.x, at.y - p.y, at.z - p.z);
                        (dx * dx + dy * dy + dz * dz).sqrt() <= r
                    }
                    _ => true,
                };
                !i.removed
                    && filter.kind.as_ref().is_none_or(|k| i.centity() == k)
                    && filter.world.as_ref().is_none_or(|w| at.world == w.name)
                    && near
            })
            .map(|i| handles::Centity {
                id: uuid_text(&i.id),
            })
            .collect())
    }
}

/// How far a click may reach for the narrow phase; past any reach the server allows.
pub const REACH: f64 = 8.0;

/// The node a click lands on, with where the line of sight met it and the face's normal, in the world (none
/// when there's no line of sight to go by).
struct Picked {
    index: usize,
    position: Option<Vec3>,
    normal: Option<Vec3>,
}

fn pick(
    game: &dyn netherforge_format::game::GameData,
    instance: &mut Instance,
    clicked: usize,
    sight: Option<&Ray>,
) -> Option<Picked> {
    let sight = match sight {
        Some(s) if s.world == instance.anchor().world => s,
        _ => {
            return instance.clickable(clicked).then_some(Picked {
                index: clicked,
                position: None,
                normal: None,
            });
        }
    };
    // Relative to the anchor along the world's axes, where the entities are shown.
    let anchor = instance.anchor().clone();
    let origin = Vec3(sight.x - anchor.x, sight.y - anchor.y, sight.z - anchor.z);
    let direction = Vec3(sight.dx, sight.dy, sight.dz);
    if let Some((index, hit)) = nearest_hitbox(game, instance, origin, direction, REACH) {
        let at = origin + direction * hit.distance;
        return Some(Picked {
            index,
            position: Some(Vec3(at.0 + anchor.x, at.1 + anchor.y, at.2 + anchor.z)),
            normal: Some(hit.normal),
        });
    }
    // A node without the narrow phase is its interaction entity, so a ray that missed it is a stale look
    // vector, not a miss.
    let raycast = instance.definition.nodes[clicked]
        .hitbox
        .as_ref()
        .is_some_and(|h| h.raycast());
    (!raycast && instance.clickable(clicked)).then_some(Picked {
        index: clicked,
        position: None,
        normal: None,
    })
}

/// The node of `instance` whose hitbox a ray hits first within `max_distance`, with the hit: `origin`
/// relative to the anchor along the world's axes, `direction` a unit vector. A `raycast` node is tested
/// against its boxes, any other against the interaction entity's bounds: the shapes a click is picked by.
fn nearest_hitbox(
    game: &dyn netherforge_format::game::GameData,
    instance: &mut Instance,
    origin: Vec3,
    direction: Vec3,
    max_distance: f64,
) -> Option<(usize, super::hitboxes::Hit)> {
    use super::hitboxes;
    let world = instance.placed().to_vec();
    let mut best: Option<(usize, hitboxes::Hit)> = None;
    for (index, node) in instance.definition.nodes.iter().enumerate() {
        let Some(hitbox) = &node.hitbox else {
            continue;
        };
        if !instance.shown(index) || !instance.clickable(index) {
            continue;
        }
        let boxes = super::boxes_of(game, instance, index);
        let hit = if hitbox.raycast() {
            hitboxes::hit(&boxes, &world[index], origin, direction, max_distance)
        } else {
            let b = hitboxes::bounds(&boxes, &world[index]);
            hitboxes::hit_bounds(&b, origin, direction, max_distance)
        };
        if let Some(hit) = hit
            && best.is_none_or(|(_, b)| hit.distance < b.distance)
        {
            best = Some((index, hit));
        }
    }
    best
}

/// What a ray hit among the centities: `instance`'s node `index`, where in the world, the face's normal and how
/// far along.
#[derive(Clone, Debug, PartialEq)]
pub struct HitboxHit {
    pub instance: Uuid,
    pub index: usize,
    pub position: Vec3,
    pub normal: Vec3,
    pub distance: f64,
}

/// The first centity hitbox a ray in `world` from `origin` along the unit `direction` hits within
/// `max_distance`, tested with the shapes clicks are (Kotlin's `CentityClicks.raycast`); instances whose chunk
/// isn't loaded aren't hit.
pub fn raycast(
    centities: &mut super::Centities,
    platform: &dyn netherforge_platform::Platform,
    world: &str,
    origin: Vec3,
    direction: Vec3,
    max_distance: f64,
) -> Option<HitboxHit> {
    let game = platform.game();
    let end = origin + direction * max_distance;
    let region = netherforge_format::math::BoundingBox {
        min: Vec3(
            origin.0.min(end.0),
            origin.1.min(end.1),
            origin.2.min(end.2),
        ),
        max: Vec3(
            origin.0.max(end.0),
            origin.1.max(end.1),
            origin.2.max(end.2),
        ),
    };
    let mut best: Option<HitboxHit> = None;
    for id in centities.near(game, world, &region) {
        let Some(instance) = centities.get_mut(id) else {
            continue;
        };
        if instance.removed
            || instance.anchor().world != world
            || !super::Centities::loaded(platform, instance)
        {
            continue;
        }
        let anchor = instance.anchor().clone();
        let from = Vec3(
            origin.0 - anchor.x,
            origin.1 - anchor.y,
            origin.2 - anchor.z,
        );
        let reach = best.as_ref().map_or(max_distance, |b| b.distance);
        let Some((index, hit)) = nearest_hitbox(game, instance, from, direction, reach) else {
            continue;
        };
        if best.as_ref().is_some_and(|b| hit.distance >= b.distance) {
            continue;
        }
        let at = from + direction * hit.distance;
        best = Some(HitboxHit {
            instance: id,
            index,
            position: Vec3(at.0 + anchor.x, at.1 + anchor.y, at.2 + anchor.z),
            normal: hit.normal,
            distance: hit.distance,
        });
    }
    best
}

/// Where an instance is: its anchor, facing its yaw.
fn location_of(instance: &Instance) -> values::Location {
    let at = instance.anchor();
    values::Location {
        world: handles::World {
            name: at.world.clone(),
        },
        position: values::Vec3 {
            x: at.x,
            y: at.y,
            z: at.z,
        },
        yaw: Some(instance.yaw()),
        pitch: None,
    }
}

fn invert(m: Matrix4) -> Option<Matrix4> {
    m.invert_affine()
}

impl CentityApi for CentityContext {
    fn id(&self, this: &handles::Centity) -> ApiResult<String> {
        Ok(this.id.clone())
    }
    fn kind(&self, this: &handles::Centity) -> ApiResult<Option<String>> {
        Ok(self.with(this, |i| i.centity().to_owned()))
    }
    fn exists(&self, this: &handles::Centity) -> ApiResult<bool> {
        Ok(self.with(this, |_| ()).is_some())
    }
    fn node(&self, this: &handles::Centity, name: String) -> ApiResult<Option<handles::Node>> {
        Ok(self
            .with(this, |i| i.index_of(&name).is_some())
            .filter(|has| *has)
            .map(|_| Self::node_handle(&this.id, &name)))
    }
    fn nodes(&self, this: &handles::Centity) -> ApiResult<Vec<handles::Node>> {
        Ok(self
            .with(this, |i| {
                i.definition
                    .nodes
                    .iter()
                    .map(|n| Self::node_handle(&this.id, &n.name))
                    .collect()
            })
            .unwrap_or_default())
    }
    fn roots(&self, this: &handles::Centity) -> ApiResult<Vec<handles::Node>> {
        Ok(self
            .with(this, |i| {
                i.definition
                    .nodes
                    .iter()
                    .filter(|n| n.is_root())
                    .map(|n| Self::node_handle(&this.id, &n.name))
                    .collect()
            })
            .unwrap_or_default())
    }
    fn properties(&self, this: &handles::Centity) -> ApiResult<Value> {
        let Some(definition) = self.with(this, |i| i.definition.clone()) else {
            return Ok(Value::Nil);
        };
        self.properties_of(definition.properties.as_ref())
    }
    fn location(&self, this: &handles::Centity) -> ApiResult<Option<values::Location>> {
        Ok(self.with(this, |i| location_of(i)))
    }
    fn position(&self, this: &handles::Centity) -> ApiResult<Option<values::Vec3>> {
        Ok(self.with(this, |i| location_of(i).position))
    }
    fn world(&self, this: &handles::Centity) -> ApiResult<Option<handles::World>> {
        Ok(self.with(this, |i| handles::World {
            name: i.anchor().world.clone(),
        }))
    }
    fn yaw(&self, this: &handles::Centity) -> ApiResult<Option<f64>> {
        Ok(self.with(this, |i| i.yaw()))
    }
    fn set_yaw(&self, this: &handles::Centity, degrees: f64) -> ApiResult<()> {
        self.with(this, |i| i.turn_to(degrees));
        Ok(())
    }
    fn to_local(
        &self,
        this: &handles::Centity,
        point: values::Vec3,
    ) -> ApiResult<Option<values::Vec3>> {
        Ok(self
            .with(this, |i| invert(i.space()))
            .flatten()
            .map(|m| value(m.transform_position(vec(point)))))
    }
    fn to_world(
        &self,
        this: &handles::Centity,
        point: values::Vec3,
    ) -> ApiResult<Option<values::Vec3>> {
        Ok(self.with(this, |i| value(i.to_world(vec(point)))))
    }
    fn teleport(
        &self,
        this: &handles::Centity,
        location_or_position: unions::LocationOrVec3,
    ) -> ApiResult<bool> {
        let Some((id, here)) = self.with(this, |i| (i.id, i.anchor().world.clone())) else {
            return Ok(false);
        };
        // The location's facing turns the centity; a bare position, or a location with no facing, keeps its own.
        let (world, position, yaw) = match location_or_position {
            unions::LocationOrVec3::Location(l) => (l.world.name, l.position, l.yaw),
            unions::LocationOrVec3::Vec3(p) => (here, p, None),
        };
        if !self.shared.platform.worlds().exists(world.clone()) {
            return Ok(false);
        }
        // A teleport ends a walk, as `stop_pathing` does, and a follow; the doors it opened shut.
        self.with(this, |i| i.walk = None);
        self.shut_all(id);
        self.unfollow(id);
        self.with(this, |i| {
            i.set_anchor(Location {
                world,
                x: position.x,
                y: position.y,
                z: position.z,
                yaw: 0.0,
                pitch: 0.0,
            });
            if let Some(yaw) = yaw {
                i.turn_to(yaw);
            }
        });
        let platform = &*self.shared.platform;
        self.shared
            .with_drawing(|c, drawing| c.sync(platform, drawing, id));
        Ok(true)
    }
    fn remove(&self, this: &handles::Centity) -> ApiResult<()> {
        if let Some(id) = Self::id_of(this) {
            CentityContext::remove(self, id);
        }
        Ok(())
    }
    fn hide_from(&self, this: &handles::Centity, player: handles::Player) -> ApiResult<bool> {
        self.hidden(this, player, true)
    }
    fn show_to(&self, this: &handles::Centity, player: handles::Player) -> ApiResult<bool> {
        self.hidden(this, player, false)
    }
    fn is_hidden_from(&self, this: &handles::Centity, player: handles::Player) -> ApiResult<bool> {
        let Some(player) = parse_uuid(&player.id) else {
            return Ok(false);
        };
        Ok(self.with(this, |i| !i.sees(player)).unwrap_or(false))
    }
    fn viewers(&self, this: &handles::Centity) -> ApiResult<Option<Vec<handles::Player>>> {
        Ok(self
            .with(this, |i| {
                i.viewers.as_ref().map(|v| {
                    v.iter()
                        .map(|p| handles::Player { id: uuid_text(p) })
                        .collect()
                })
            })
            .flatten())
    }
    fn view_range(&self, this: &handles::Centity) -> ApiResult<Option<f64>> {
        Ok(self.with(this, |i| i.view_range()))
    }
    fn set_view_range(&self, this: &handles::Centity, blocks: f64) -> ApiResult<bool> {
        if !blocks.is_finite() || blocks < 0.0 {
            return Err(err(
                "bad argument 'blocks' (a view range can't be negative)",
            ));
        }
        Ok(self.with(this, |i| i.set_view_range(blocks)).is_some())
    }
    fn on(
        &self,
        this: &handles::Centity,
        event: String,
        handler: Function,
        options: Option<shapes::EventOptions>,
    ) -> ApiResult<handles::Subscription> {
        let scope = self.running_scope("Centity:on")?;
        let target = self
            .handle_target(Handle::Centity(this.clone()))
            .ok_or_else(|| err("the centity's handle is gone"))?;
        self.scripts
            .subscribe(scope, target, event, handler, options, false)
    }
    fn once(
        &self,
        this: &handles::Centity,
        event: String,
        handler: Function,
    ) -> ApiResult<handles::Subscription> {
        let scope = self.running_scope("Centity:once")?;
        let target = self
            .handle_target(Handle::Centity(this.clone()))
            .ok_or_else(|| err("the centity's handle is gone"))?;
        self.scripts
            .subscribe(scope, target, event, handler, None, true)
    }
    fn emit(&self, this: &handles::Centity, event: String, payload: Value) -> ApiResult<bool> {
        let target = self
            .handle_target(Handle::Centity(this.clone()))
            .ok_or_else(|| err("the centity's handle is gone"))?;
        self.scripts.emit_custom(target, event, payload)
    }

    fn play_animation(
        &self,
        this: &handles::Centity,
        name: String,
        options: Option<shapes::AnimationOptions>,
    ) -> ApiResult<bool> {
        let speed = options.as_ref().and_then(|o| o.speed).unwrap_or(1.0);
        if !speed.is_finite() || speed < 0.0 {
            return Err(err("bad field 'options.speed' (a speed can't be negative)"));
        }
        let from = options.as_ref().and_then(|o| o.from_tick).unwrap_or(0.0);
        if !from.is_finite() || from < 0.0 {
            return Err(err(
                "bad field 'options.from_tick' (a tick can't be negative)",
            ));
        }
        let blend = options.as_ref().and_then(|o| o.blend_ticks).unwrap_or(0);
        if blend < 0 {
            return Err(err(
                "bad field 'options.blend_ticks' (ticks can't be negative)",
            ));
        }
        let Some(index) = self.clip(this, &name)? else {
            return Ok(false);
        };
        let looped_option = options.as_ref().and_then(|o| o.r#loop);
        let Some(id) = self.with(this, |i| {
            let definition = i.definition.clone();
            let own = definition.animations[index].looped;
            let looped = looped_option.map(|l| match l {
                true => LoopMode::Loop,
                false if own == LoopMode::Loop => LoopMode::Once,
                false => own,
            });
            let play = Play {
                speed,
                from: from * SECONDS_PER_TICK,
                looped,
                blend_seconds: blend as f64 * SECONDS_PER_TICK,
            };
            let pose = i.shown_pose().to_vec();
            i.animations.play(&definition, index, play, Some(&pose));
            i.animate();
            i.id
        }) else {
            return Ok(false);
        };
        self.raise_animation(id, "animation_start", name);
        Ok(true)
    }
    fn stop_animation(&self, this: &handles::Centity, name: Option<String>) -> ApiResult<()> {
        self.with(this, |i| {
            match &name {
                Some(name) => {
                    i.animations.stop(name);
                }
                None => i.animations.stop_all(),
            }
            i.animate();
        });
        Ok(())
    }
    fn pause_animation(&self, this: &handles::Centity, name: String) -> ApiResult<bool> {
        if self.clip(this, &name)?.is_none() {
            return Ok(false);
        }
        Ok(self
            .with(this, |i| i.animations.pause(&name))
            .unwrap_or(false))
    }
    fn resume_animation(&self, this: &handles::Centity, name: String) -> ApiResult<bool> {
        if self.clip(this, &name)?.is_none() {
            return Ok(false);
        }
        Ok(self
            .with(this, |i| i.animations.resume(&name))
            .unwrap_or(false))
    }
    fn is_animation_playing(&self, this: &handles::Centity, name: String) -> ApiResult<bool> {
        Ok(self
            .with(this, |i| i.animations.is_playing(&name))
            .unwrap_or(false))
    }
    fn playing_animations(&self, this: &handles::Centity) -> ApiResult<Vec<String>> {
        Ok(self
            .with(this, |i| i.animations.names())
            .unwrap_or_default())
    }
    fn animations(&self, this: &handles::Centity) -> ApiResult<Vec<String>> {
        Ok(self
            .with(this, |i| {
                i.definition
                    .animations
                    .iter()
                    .map(|a| a.name.clone())
                    .collect()
            })
            .unwrap_or_default())
    }
    fn animation_tick(&self, this: &handles::Centity, name: String) -> ApiResult<Option<f64>> {
        if self.clip(this, &name)?.is_none() {
            return Ok(None);
        }
        Ok(self
            .with(this, |i| i.animations.position(&name))
            .flatten()
            .map(|seconds| seconds / SECONDS_PER_TICK))
    }
    fn seek_animation(&self, this: &handles::Centity, name: String, tick: f64) -> ApiResult<bool> {
        if !tick.is_finite() || tick < 0.0 {
            return Err(err("bad argument 'tick' (a tick can't be negative)"));
        }
        if self.clip(this, &name)?.is_none() {
            return Ok(false);
        }
        Ok(self
            .with(this, |i| {
                let moved = i.animations.seek(&name, tick * SECONDS_PER_TICK);
                if moved {
                    i.animate();
                }
                moved
            })
            .unwrap_or(false))
    }
    fn animation_speed(&self, this: &handles::Centity, name: String) -> ApiResult<Option<f64>> {
        if self.clip(this, &name)?.is_none() {
            return Ok(None);
        }
        Ok(self.with(this, |i| i.animations.speed(&name)).flatten())
    }
    fn set_animation_speed(
        &self,
        this: &handles::Centity,
        name: String,
        multiplier: f64,
    ) -> ApiResult<bool> {
        if !multiplier.is_finite() || multiplier < 0.0 {
            return Err(err("bad argument 'multiplier' (a speed can't be negative)"));
        }
        if self.clip(this, &name)?.is_none() {
            return Ok(false);
        }
        Ok(self
            .with(this, |i| i.animations.set_speed(&name, multiplier))
            .unwrap_or(false))
    }

    fn bounds(&self, this: &handles::Centity) -> ApiResult<Option<shapes::Bounds>> {
        let game = self.shared.platform.game();
        Ok(self
            .with(this, |i| {
                let world = i.placed().to_vec();
                let mut boxes = Vec::new();
                for (index, node) in i.definition.nodes.iter().enumerate() {
                    if node.hitbox.is_none() || !i.shown(index) {
                        continue;
                    }
                    for b in super::boxes_of(game, i, index) {
                        boxes.push(super::hitboxes::bounds_of(&[b], &world[index]));
                    }
                }
                if boxes.is_empty() {
                    return None;
                }
                let all = super::hitboxes::bounds_of(&boxes, &Matrix4::IDENTITY);
                let a = i.anchor();
                Some(shapes::Bounds {
                    min: value(Vec3(all.min.0 + a.x, all.min.1 + a.y, all.min.2 + a.z)),
                    max: value(Vec3(all.max.0 + a.x, all.max.1 + a.y, all.max.2 + a.z)),
                })
            })
            .flatten())
    }
    fn look_at(&self, this: &handles::Centity, point: values::Vec3) -> ApiResult<bool> {
        Ok(self
            .with(this, |i| {
                let a = i.anchor();
                let (x, z) = (point.x - a.x, point.z - a.z);
                // Straight above or below, any facing is as good as another: keep this one.
                if x * x + z * z >= 1e-18 {
                    i.turn_to((-x).atan2(z).to_degrees());
                }
            })
            .is_some())
    }
    fn is_glowing(&self, this: &handles::Centity) -> ApiResult<bool> {
        Ok(self
            .with(this, |i| {
                let drawn = drawn(i);
                !drawn.is_empty() && drawn.iter().all(|n| i.look(*n).glowing)
            })
            .unwrap_or(false))
    }
    fn set_glowing(&self, this: &handles::Centity, glowing: bool) -> ApiResult<bool> {
        Ok(self
            .with(this, |i| {
                for n in drawn(i) {
                    let mut look = i.look(n).clone();
                    look.glowing = glowing;
                    i.set_look(n, look);
                }
            })
            .is_some())
    }
    fn glow_color(&self, this: &handles::Centity) -> ApiResult<Option<String>> {
        Ok(self
            .with(this, |i| {
                let mut colours: Vec<Option<i32>> =
                    coloured(i).iter().map(|n| i.look(*n).glow_color).collect();
                colours.dedup();
                match colours.as_slice() {
                    [Some(rgb)] => Some(format_rgb(*rgb)),
                    _ => None,
                }
            })
            .flatten())
    }
    fn set_glow_color(&self, this: &handles::Centity, color: Option<String>) -> ApiResult<bool> {
        let rgb = color
            .as_deref()
            .map(|c| parse_rgb(c, "color"))
            .transpose()?;
        Ok(self
            .with(this, |i| {
                let coloured = coloured(i);
                for n in &coloured {
                    let mut look = i.look(*n).clone();
                    look.glow_color = rgb;
                    i.set_look(*n, look);
                }
                !coloured.is_empty()
            })
            .unwrap_or(false))
    }

    fn add_node(
        &self,
        this: &handles::Centity,
        name: String,
        definition: Value,
    ) -> ApiResult<Option<handles::Node>> {
        self.add(this, name, None, definition)
    }

    fn is_natural(&self, this: &handles::Centity) -> ApiResult<bool> {
        Ok(self.with(this, |i| i.natural).unwrap_or(false))
    }
    fn is_temporary(&self, this: &handles::Centity) -> ApiResult<bool> {
        Ok(self.with(this, |i| i.temporary).unwrap_or(false))
    }
    fn lifetime(&self, this: &handles::Centity) -> ApiResult<Option<i64>> {
        let now = self.scripts.tick_count();
        Ok(self
            .with(this, |i| i.expires.map(|e| e.saturating_sub(now) as i64))
            .flatten())
    }
    fn set_lifetime(&self, this: &handles::Centity, ticks: Option<i64>) -> ApiResult<bool> {
        if let Some(t) = ticks
            && t < 1
        {
            return Err(err(format!(
                "bad argument 'ticks' (at least 1 expected, got {t})"
            )));
        }
        let now = self.scripts.tick_count();
        let platform = &*self.shared.platform;
        Ok(self
            .with(this, |i| {
                // One that wasn't temporary becomes so, going by its lifetime alone.
                if !i.temporary {
                    i.temporary = true;
                    let entities = if i.viewers.is_some() {
                        platform.client_entities() as &dyn netherforge_platform::EntityOps
                    } else {
                        platform.entities()
                    };
                    for entity in i.entity_ids() {
                        entities.set_persistent(entity, false);
                    }
                }
                i.expires = ticks.map(|t| now + t as u64);
            })
            .is_some())
    }
    fn keep(&self, this: &handles::Centity) -> ApiResult<bool> {
        let platform = &*self.shared.platform;
        Ok(self
            .with(this, |i| {
                // A private one stays temporary: its viewers' clients are all there is of it.
                if !i.transient() || i.viewers.is_some() {
                    return false;
                }
                i.natural = false;
                i.temporary = false;
                i.owner = None;
                i.expires = None;
                for entity in i.entity_ids() {
                    platform.entities().set_persistent(entity, true);
                }
                true
            })
            .unwrap_or(false))
    }

    fn stat(&self, this: &handles::Centity, name: String) -> ApiResult<f64> {
        Ok(self
            .holder(this)
            .map_or(0.0, |h| self.shared.stats.borrow_mut().stat(h, &name)))
    }
    fn stats(&self, this: &handles::Centity) -> ApiResult<BTreeMap<String, f64>> {
        Ok(self
            .holder(this)
            .map(|h| self.shared.stats.borrow_mut().stats(h))
            .unwrap_or_default())
    }
    fn modifiers(
        &self,
        this: &handles::Centity,
        source: String,
    ) -> ApiResult<Option<BTreeMap<String, f64>>> {
        Ok(self
            .holder(this)
            .and_then(|h| self.shared.stats.borrow().modifiers(h, &source)))
    }
    fn set_modifiers(
        &self,
        this: &handles::Centity,
        source: String,
        modifiers: Option<BTreeMap<String, f64>>,
    ) -> ApiResult<bool> {
        let Some(holder) = self.holder(this) else {
            return Ok(false);
        };
        self.shared
            .stats
            .borrow_mut()
            .set_modifiers(holder, &source, modifiers)
            .map_err(err)?;
        Ok(true)
    }
    fn add_buff(&self, this: &handles::Centity, buff: String, ticks: i64) -> ApiResult<bool> {
        if ticks < 1 {
            return Err(err(format!(
                "bad argument 'ticks' (at least 1 expected, got {ticks})"
            )));
        }
        let id = self.buff(&buff)?;
        let Some(holder) = self.holder(this) else {
            return Ok(false);
        };
        Ok(self.give_buff(holder, &id, ticks))
    }
    fn remove_buff(&self, this: &handles::Centity, buff: String) -> ApiResult<bool> {
        let id = self.buff(&buff)?;
        let Some(holder) = self.holder(this) else {
            return Ok(false);
        };
        let had = self.shared.stats.borrow_mut().remove(holder, &id);
        if had {
            self.buffs_ended(vec![crate::stats::Ended {
                holder,
                buff: id,
                reason: "removed",
            }]);
        }
        Ok(had)
    }
    fn has_buff(&self, this: &handles::Centity, buff: String) -> ApiResult<bool> {
        let id = self.buff(&buff)?;
        Ok(self
            .holder(this)
            .is_some_and(|h| self.shared.stats.borrow().ticks(h, &id).is_some()))
    }
    fn buff_ticks(&self, this: &handles::Centity, buff: String) -> ApiResult<Option<i64>> {
        let id = self.buff(&buff)?;
        Ok(self
            .holder(this)
            .and_then(|h| self.shared.stats.borrow().ticks(h, &id)))
    }
    fn buffs(&self, this: &handles::Centity) -> ApiResult<Vec<shapes::ActiveBuff>> {
        let Some(holder) = self.holder(this) else {
            return Ok(Vec::new());
        };
        let stats = self.shared.stats.borrow();
        Ok(stats
            .buffs(holder)
            .into_iter()
            .map(|active| {
                let file = stats.file(&active.id);
                shapes::ActiveBuff {
                    id: active.id.clone(),
                    ticks: active.ticks,
                    name: file.and_then(|f| f.name.clone()).map(values::Text),
                    icon: file.and_then(|f| f.icon.clone()),
                    color: file.and_then(|f| f.color.clone()),
                    debuff: file.and_then(|f| f.debuff).unwrap_or(false),
                }
            })
            .collect())
    }
    fn health(&self, this: &handles::Centity) -> ApiResult<Option<f64>> {
        Ok(self
            .with(this, |i| i.health.as_ref().map(|h| h.current))
            .flatten())
    }
    fn max_health(&self, this: &handles::Centity) -> ApiResult<Option<f64>> {
        Ok(self
            .with(this, |i| i.health.as_ref().map(|h| h.max))
            .flatten())
    }
    fn set_health(&self, this: &handles::Centity, health: f64) -> ApiResult<bool> {
        if !health.is_finite() {
            return Err(err("bad argument 'health' (a finite number expected)"));
        }
        let now = self.scripts.tick_count();
        Ok(self
            .with(this, |i| {
                let Some(h) = &mut i.health else {
                    return false;
                };
                h.current = health.clamp(0.0, h.max);
                if h.current > 0.0 {
                    h.dead = false;
                }
                super::health::draw(i, true, now);
                true
            })
            .unwrap_or(false))
    }
    fn set_max_health(&self, this: &handles::Centity, max_health: f64) -> ApiResult<bool> {
        if !(max_health > 0.0 && max_health.is_finite()) {
            return Err(err(format!(
                "bad argument 'max_health' (more than 0 expected, got {})",
                lua_number(max_health)
            )));
        }
        let now = self.scripts.tick_count();
        Ok(self
            .with(this, |i| {
                let h = i.health.get_or_insert(super::health::Health {
                    max: max_health,
                    current: max_health,
                    remove_on_death: true,
                    bar: None,
                    dead: false,
                    shown_until: None,
                });
                h.max = max_health;
                h.current = h.current.min(max_health);
                super::health::draw(i, true, now);
            })
            .is_some())
    }
    fn damage(
        &self,
        this: &handles::Centity,
        amount: f64,
        options: Option<shapes::CentityDamageOptions>,
    ) -> ApiResult<Option<f64>> {
        if !(amount >= 0.0 && amount.is_finite()) {
            return Err(err(format!(
                "bad argument 'amount' (at least 0 expected, got {})",
                lua_number(amount)
            )));
        }
        let Some(id) = self.with(this, |i| i.id) else {
            return Ok(None);
        };
        let (source, cause) = options.map_or((None, None), |o| (o.source, o.cause));
        Ok(self.deal_damage(id, amount, source, cause))
    }
    fn heal(&self, this: &handles::Centity, amount: f64) -> ApiResult<Option<f64>> {
        if !(amount >= 0.0 && amount.is_finite()) {
            return Err(err(format!(
                "bad argument 'amount' (at least 0 expected, got {})",
                lua_number(amount)
            )));
        }
        let now = self.scripts.tick_count();
        Ok(self
            .with(this, |i| {
                let h = i.health.as_mut()?;
                let gained = amount.max(0.0).min(h.max - h.current);
                h.current += gained;
                if h.current > 0.0 {
                    h.dead = false;
                }
                super::health::draw(i, gained > 0.0, now);
                Some(gained)
            })
            .flatten())
    }
    fn is_dead(&self, this: &handles::Centity) -> ApiResult<bool> {
        Ok(self
            .with(this, |i| i.health.as_ref().is_some_and(|h| h.dead))
            .unwrap_or(false))
    }

    fn has_body(&self, this: &handles::Centity) -> ApiResult<bool> {
        Ok(self.with(this, |i| i.mover.is_some()).unwrap_or(false))
    }
    fn body(&self, this: &handles::Centity) -> ApiResult<Option<shapes::BodySettings>> {
        Ok(self
            .with(this, |i| {
                i.mover.as_ref().map(|m| {
                    let b = &m.settings;
                    shapes::BodySettings {
                        width: Some(b.width),
                        height: Some(b.height),
                        gravity: Some(b.gravity),
                        max_fall_speed: Some(b.max_fall_speed),
                        drag: Some(b.drag),
                        liquid_drag: Some(b.liquid_drag),
                        liquid_gravity: Some(b.liquid_gravity),
                        acceleration: Some(b.acceleration),
                        air_acceleration: Some(b.air_acceleration),
                        friction: Some(b.friction),
                        step_height: Some(b.step_height),
                        bounce: Some(b.bounce),
                        fly: Some(b.fly),
                        blocks: Some(b.blocks),
                    }
                })
            })
            .flatten())
    }
    fn set_body(&self, this: &handles::Centity, settings: shapes::BodySettings) -> ApiResult<bool> {
        use netherforge_format::kind::centity::BodyDef;
        use netherforge_format::kind::centity::compiled::ResolvedBody;
        for (name, value) in [("width", settings.width), ("height", settings.height)] {
            if let Some(v) = value
                && !(v > 0.0 && v.is_finite())
            {
                return Err(err(format!(
                    "bad argument 'settings.{name}' (more than 0 expected, got {})",
                    lua_number(v)
                )));
            }
        }
        Ok(self
            .with(this, |i| {
                let old = i.mover.as_ref().map_or_else(
                    || ResolvedBody::of(&BodyDef::default()),
                    |m| m.settings.clone(),
                );
                let next = ResolvedBody::of(&BodyDef {
                    width: Some(settings.width.unwrap_or(old.width)),
                    height: Some(settings.height.unwrap_or(old.height)),
                    gravity: Some(settings.gravity.unwrap_or(old.gravity)),
                    max_fall_speed: Some(settings.max_fall_speed.unwrap_or(old.max_fall_speed)),
                    drag: Some(settings.drag.unwrap_or(old.drag)),
                    liquid_drag: Some(settings.liquid_drag.unwrap_or(old.liquid_drag)),
                    liquid_gravity: Some(settings.liquid_gravity.unwrap_or(old.liquid_gravity)),
                    acceleration: Some(settings.acceleration.unwrap_or(old.acceleration)),
                    air_acceleration: Some(
                        settings.air_acceleration.unwrap_or(old.air_acceleration),
                    ),
                    friction: Some(settings.friction.unwrap_or(old.friction)),
                    step_height: Some(settings.step_height.unwrap_or(old.step_height)),
                    bounce: Some(settings.bounce.unwrap_or(old.bounce)),
                    fly: Some(settings.fly.unwrap_or(old.fly)),
                    blocks: Some(settings.blocks.unwrap_or(old.blocks)),
                });
                match &mut i.mover {
                    Some(m) => m.settings = next,
                    None => i.mover = Some(super::movers::Mover::new(next)),
                }
            })
            .is_some())
    }
    fn velocity(&self, this: &handles::Centity) -> ApiResult<Option<values::Vec3>> {
        Ok(self.with(this, |i| value(i.velocity())))
    }
    fn set_velocity(&self, this: &handles::Centity, velocity: values::Vec3) -> ApiResult<bool> {
        let v = finite_velocity(velocity, "velocity")?;
        Ok(self.with(this, |i| i.push(v, false)).unwrap_or(false))
    }
    fn add_velocity(&self, this: &handles::Centity, velocity: values::Vec3) -> ApiResult<bool> {
        let v = finite_velocity(velocity, "velocity")?;
        Ok(self.with(this, |i| i.push(v, true)).unwrap_or(false))
    }
    fn walk_velocity(&self, this: &handles::Centity) -> ApiResult<Option<values::Vec3>> {
        Ok(self
            .with(this, |i| i.mover.as_ref().and_then(|m| m.walk).map(value))
            .flatten())
    }
    fn set_walk_velocity(
        &self,
        this: &handles::Centity,
        velocity: Option<values::Vec3>,
    ) -> ApiResult<bool> {
        let walk = velocity
            .map(|v| finite_velocity(v, "velocity"))
            .transpose()?;
        Ok(self
            .with(this, |i| {
                let Some(m) = &mut i.mover else {
                    return false;
                };
                m.walk = walk;
                true
            })
            .unwrap_or(false))
    }
    fn jump(&self, this: &handles::Centity, height: f64) -> ApiResult<bool> {
        if !(height >= 0.0 && height.is_finite()) {
            return Err(err(format!(
                "bad argument 'height' (at least 0 expected, got {})",
                lua_number(height)
            )));
        }
        Ok(self
            .with(this, |i| {
                let Some(m) = &mut i.mover else {
                    return false;
                };
                m.velocity = Vec3(m.velocity.0, m.jump_speed(height), m.velocity.2);
                m.on_ground = false;
                true
            })
            .unwrap_or(false))
    }
    fn is_on_ground(&self, this: &handles::Centity) -> ApiResult<bool> {
        Ok(self
            .with(this, |i| i.mover.as_ref().is_some_and(|m| m.on_ground))
            .unwrap_or(false))
    }
    fn is_in_liquid(&self, this: &handles::Centity) -> ApiResult<bool> {
        Ok(self
            .with(this, |i| i.mover.as_ref().is_some_and(|m| m.in_liquid))
            .unwrap_or(false))
    }
    fn wall(&self, this: &handles::Centity) -> ApiResult<Option<values::Vec3>> {
        Ok(self
            .with(this, |i| i.mover.as_ref().and_then(|m| m.wall).map(value))
            .flatten())
    }

    fn launch(&self, this: &handles::Centity, options: shapes::LaunchOptions) -> ApiResult<bool> {
        let velocity = finite_velocity(options.velocity, "options.velocity")?;
        let at_least = |value: Option<f64>, least: f64, name: &str| -> ApiResult<()> {
            match value {
                Some(v) if !(v >= least && v.is_finite()) => Err(err(format!(
                    "bad argument 'options.{name}' (at least {} expected, got {})",
                    lua_number(least),
                    lua_number(v)
                ))),
                _ => Ok(()),
            }
        };
        let within = |value: Option<f64>, name: &str| -> ApiResult<()> {
            match value {
                Some(v) if !(0.0..=1.0).contains(&v) => Err(err(format!(
                    "bad argument 'options.{name}' (0 to 1 expected, got {})",
                    lua_number(v)
                ))),
                _ => Ok(()),
            }
        };
        at_least(options.radius, 0.0, "radius")?;
        at_least(options.rest_speed, 0.0, "rest_speed")?;
        within(options.drag, "drag")?;
        within(options.bounce, "bounce")?;
        within(options.friction, "friction")?;
        for (name, value) in [
            ("pierce", options.pierce),
            ("gravity_delay", options.gravity_delay),
            ("max_bounces", options.max_bounces),
        ] {
            if let Some(v) = value
                && v < 0
            {
                return Err(err(format!(
                    "bad argument 'options.{name}' (at least 0 expected, got {v})"
                )));
            }
        }
        for (name, value) in [("rehit", options.rehit), ("lifetime", options.lifetime)] {
            if let Some(v) = value
                && v < 1
            {
                return Err(err(format!(
                    "bad argument 'options.{name}' (at least 1 expected, got {v})"
                )));
            }
        }
        if let Some(h) = &options.homing {
            at_least(Some(h.range), 0.0, "homing.range")?;
            at_least(h.turn, 0.0, "homing.turn")?;
        }
        let gravity = options.gravity.unwrap_or(0.0);
        if !gravity.is_finite() {
            return Err(err(
                "bad argument 'options.gravity' (a finite number expected)",
            ));
        }
        let mut filter = match &options.hits {
            Some(hits) => super::flights::query_filter(hits).map_err(err)?,
            None => super::spatial::QueryFilter::default(),
        };
        match &options.owner {
            Some(Handle::Centity(c)) => {
                if let Some(id) = parse_uuid(&c.id) {
                    filter.exclude_centities.insert(id);
                }
            }
            Some(Handle::Entity(e)) => {
                if let Some(id) = parse_uuid(&e.id) {
                    filter.exclude_entities.insert(id);
                }
            }
            _ => {}
        }
        // A question's own limit and line of sight aren't a flight's: it meets what's in its way, in order.
        filter.limit = usize::MAX;
        filter.line_of_sight = false;
        let number = self.shared.next_flight.get() + 1;
        self.shared.next_flight.set(number);
        let launched = self.with(this, |i| {
            if let Some(aim) = &options.aim
                && i.index_of(aim).is_none()
            {
                return Err(err(format!(
                    "bad argument 'options.aim' (the centity has no node \"{aim}\")"
                )));
            }
            i.walk = None;
            i.flight = Some(super::flights::Flight {
                velocity,
                gravity,
                gravity_delay: options.gravity_delay.unwrap_or(0),
                drag: options.drag.unwrap_or(0.0),
                radius: options.radius.unwrap_or(DEFAULT_RADIUS),
                pierce: options.pierce.unwrap_or(0),
                rehit: options.rehit,
                filter,
                owner: options.owner.clone(),
                blocks: options.blocks != Some(false),
                bounce: options.bounce,
                friction: options.friction.unwrap_or(1.0),
                max_bounces: options.max_bounces,
                rest_speed: options.rest_speed.unwrap_or(DEFAULT_REST_SPEED),
                stick: options.stick == Some(true),
                homing_range: options.homing.as_ref().map(|h| h.range),
                homing_turn: options
                    .homing
                    .as_ref()
                    .and_then(|h| h.turn)
                    .unwrap_or(DEFAULT_HOMING_TURN),
                lifetime: options.lifetime.unwrap_or(DEFAULT_LIFETIME),
                aim: options.aim.clone(),
                remove_on_end: options.remove_on_end != Some(false),
                age: 0,
                bounces: 0,
                still: false,
                hit_at: Default::default(),
                number,
            });
            Ok(i.id)
        });
        match launched {
            None => Ok(false),
            Some(Err(e)) => Err(e),
            Some(Ok(id)) => {
                self.aim_flight(id);
                Ok(true)
            }
        }
    }
    fn is_launched(&self, this: &handles::Centity) -> ApiResult<bool> {
        Ok(self.with(this, |i| i.flight.is_some()).unwrap_or(false))
    }
    fn stop_flight(&self, this: &handles::Centity) -> ApiResult<bool> {
        let Some(id) = self.with(this, |i| i.id) else {
            return Ok(false);
        };
        Ok(self.stop_flight_of(id))
    }

    fn follow(&self, this: &handles::Centity, player: handles::Player) -> ApiResult<bool> {
        let (Some(id), Some(who)) = (self.with(this, |i| i.id), parse_uuid(&player.id)) else {
            return Ok(false);
        };
        Ok(self.follow_player(id, who))
    }
    fn stop_following(&self, this: &handles::Centity) -> ApiResult<bool> {
        let Some(id) = self.with(this, |i| i.id) else {
            return Ok(false);
        };
        Ok(self.unfollow(id))
    }
    fn move_to(
        &self,
        this: &handles::Centity,
        target: unions::LocationOrVec3OrEntityOrCentity,
        options: Option<shapes::CentityPathOptions>,
    ) -> ApiResult<bool> {
        use super::paths::{self, PathSettings, PathTarget};
        let o = options.unwrap_or(shapes::CentityPathOptions {
            speed: None,
            fly: None,
            face: None,
            width: None,
            height: None,
            step_height: None,
            max_drop: None,
            open_doors: None,
            range: None,
        });
        let check = |field: &str,
                     value: Option<f64>,
                     ok: &dyn Fn(f64) -> bool,
                     what: String|
         -> ApiResult<Option<f64>> {
            match value {
                Some(v) if !v.is_finite() || !ok(v) => Err(err(format!(
                    "options.{field} must be {what}, not {}",
                    lua_number(v)
                ))),
                _ => Ok(value),
            }
        };
        let most = |n: f64| format!("more than 0 and at most {}", n as i64);
        let speed = check(
            "speed",
            o.speed,
            &|v| v > 0.0 && v <= paths::MAX_SPEED,
            most(paths::MAX_SPEED),
        )?;
        let width = check(
            "width",
            o.width,
            &|v| v > 0.0 && v <= paths::MAX_WIDTH,
            most(paths::MAX_WIDTH),
        )?;
        let height = check(
            "height",
            o.height,
            &|v| v > 0.0 && v <= paths::MAX_HEIGHT,
            most(paths::MAX_HEIGHT),
        )?;
        let step = check(
            "step_height",
            o.step_height,
            &|v| v >= 0.0,
            "at least 0".into(),
        )?;
        let drop = check("max_drop", o.max_drop, &|v| v >= 0.0, "at least 0".into())?;
        let range = check(
            "range",
            o.range,
            &|v| v > 0.0 && v <= paths::MAX_RANGE,
            most(paths::MAX_RANGE),
        )?;
        let settings = PathSettings {
            speed: speed.unwrap_or(paths::DEFAULT_SPEED),
            fly: o.fly == Some(true),
            face: o.face != Some(false),
            width,
            height,
            step_height: step.unwrap_or(paths::DEFAULT_STEP_HEIGHT),
            max_drop: drop.unwrap_or(paths::DEFAULT_MAX_DROP),
            range: range.unwrap_or(paths::DEFAULT_RANGE),
            open_doors: o.open_doors == Some(true),
        };
        let Some((id, world)) = self.with(this, |i| (i.id, i.anchor().world.clone())) else {
            return Ok(false);
        };
        let goal = match target {
            unions::LocationOrVec3OrEntityOrCentity::Location(l) => {
                (l.world.name == world).then(|| PathTarget::Place(vec(l.position)))
            }
            unions::LocationOrVec3OrEntityOrCentity::Vec3(p) => Some(PathTarget::Place(vec(p))),
            unions::LocationOrVec3OrEntityOrCentity::Centity(c) => {
                parse_uuid(&c.id).map(PathTarget::Centity)
            }
            unions::LocationOrVec3OrEntityOrCentity::Entity(e) => {
                parse_uuid(&e.id).map(PathTarget::Entity)
            }
        };
        let Some(goal) = goal else {
            return Ok(false);
        };
        // A walk ends a follow, as a teleport does.
        self.unfollow(id);
        Ok(self.start_walk(id, goal, settings))
    }
    fn stop_pathing(&self, this: &handles::Centity) -> ApiResult<bool> {
        Ok(self
            .with(this, |i| i.walk.take().is_some())
            .unwrap_or(false))
    }
    fn has_path(&self, this: &handles::Centity) -> ApiResult<bool> {
        Ok(self.with(this, |i| i.walk.is_some()).unwrap_or(false))
    }
    fn path_target(&self, this: &handles::Centity) -> ApiResult<Option<values::Vec3>> {
        Ok(self
            .with(this, |i| {
                i.walk
                    .as_ref()
                    .and_then(|w| w.points.last().copied())
                    .map(value)
            })
            .flatten())
    }

    fn data(&self, this: &handles::Centity) -> ApiResult<Value> {
        let Some(id) = self.with(this, |i| i.id) else {
            return Ok(Value::Nil);
        };
        if let Some(kept) = self.shared.centity_data.borrow().get(&id) {
            return Ok(Value::Table(kept.table.clone()));
        }
        let host = self.scripts.host();
        let lua = host.lua();
        let saved = self.shared.store.centity_data(id);
        let table = match saved
            .as_deref()
            .map(|t| netherforge_luau::json::decode(lua, t, &netherforge_luau::json::NoTags))
        {
            Some(Ok(Value::Table(t))) => t,
            Some(_) => {
                self.shared.platform.log().warn(format!(
                    "What the store has for centity {}'s data isn't a saved table; it starts empty",
                    this.id
                ));
                lua.create_table().map_err(lua_err)?
            }
            None => lua.create_table().map_err(lua_err)?,
        };
        self.shared.centity_data.borrow_mut().insert(
            id,
            crate::shared::DataTable {
                table: table.clone(),
                saved,
            },
        );
        Ok(Value::Table(table))
    }

    // ---- not ported yet ----
}

impl CentityContext {
    /// Adds a node a script described under node `parent` (a root when none): its handle. The name must be
    /// free; its entities are made by the next sync, in the same tick.
    fn add(
        &self,
        this: &handles::Centity,
        name: String,
        parent: Option<usize>,
        definition: Value,
    ) -> ApiResult<Option<handles::Node>> {
        let host = self.scripts.host();
        let game = self.shared.platform.game();
        let node = super::definitions::node_from(
            &host,
            &name,
            parent.map_or(-1, |p| p as i32),
            &definition,
            Some(game),
        )
        .map_err(err)?;
        let added = self.with(this, |i| {
            if i.index_of(&name).is_some() {
                return Err(err(format!(
                    "bad argument 'name' (the centity already has a node called \"{name}\")"
                )));
            }
            i.add_node(node);
            Ok(())
        });
        match added {
            None => Ok(None),
            Some(result) => result.map(|_| Some(Self::node_handle(&this.id, &name))),
        }
    }

    fn hidden(
        &self,
        this: &handles::Centity,
        player: handles::Player,
        hidden: bool,
    ) -> ApiResult<bool> {
        let Some(player) = parse_uuid(&player.id) else {
            return Err(err(format!(
                "bad argument 'player' (no player \"{}\")",
                player.id
            )));
        };
        let Some(id) = self.with(this, |i| i.id) else {
            return Ok(false);
        };
        self.shared
            .centities
            .borrow_mut()
            .set_hidden(&*self.shared.platform, id, player, hidden);
        Ok(true)
    }
}

/// The most a data table may take, encoded.
const MAX_DATA_BYTES: usize = 1 << 20;
/// How often every table and place that changed is saved: five minutes, as the server's own autosave.
const AUTOSAVE_EVERY: u64 = 6000;

/// What a launch without them flies with.
const DEFAULT_RADIUS: f64 = 0.1;
const DEFAULT_REST_SPEED: f64 = 1.0;
const DEFAULT_HOMING_TURN: f64 = 180.0;
const DEFAULT_LIFETIME: i64 = 600;

/// A text display's opacity when it's fully opaque.
const OPAQUE: i32 = 255;

/// The nodes that draw something.
fn drawn(instance: &Instance) -> Vec<usize> {
    (0..instance.definition.nodes.len())
        .filter(|n| instance.display(*n).is_some())
        .collect()
}

/// The block, item and model displays: the ones a glow colour shows on.
fn coloured(instance: &Instance) -> Vec<usize> {
    drawn(instance)
        .into_iter()
        .filter(|n| !matches!(instance.display(*n), Some(DisplayDef::Text { .. })))
        .collect()
}

/// `#RRGGBB` as its number; anything else is an error naming `parameter`.
fn parse_rgb(text: &str, parameter: &str) -> ApiResult<i32> {
    if text.len() == 7 && is_color(text) {
        return Ok(i32::from_str_radix(&text[1..], 16).expect("hex digits"));
    }
    Err(err(format!(
        "bad argument '{parameter}' (#RRGGBB expected, got \"{text}\")"
    )))
}

fn format_rgb(rgb: i32) -> String {
    format!("#{:06x}", rgb & 0xFF_FFFF)
}

/// `v`, or an error naming `name` when it isn't finite.
fn finite_velocity(v: values::Vec3, name: &str) -> ApiResult<Vec3> {
    if !(v.x.is_finite() && v.y.is_finite() && v.z.is_finite()) {
        return Err(err(format!(
            "bad argument '{name}' (a finite velocity expected)"
        )));
    }
    Ok(vec(v))
}

/// A number as Lua prints it: a whole one without a point.
fn lua_number(n: f64) -> String {
    if n == n.floor() && n.abs() < 1e15 {
        format!("{}", n as i64)
    } else {
        format!("{n}")
    }
}

/// A display's kind, as `display_kind` names it.
fn kind_of(display: &DisplayDef) -> &'static str {
    match display {
        DisplayDef::Block { .. } => "block",
        DisplayDef::Item { .. } => "item",
        DisplayDef::Model { .. } => "model",
        DisplayDef::Text { .. } => "text",
        DisplayDef::Rect { .. } => "rect",
        DisplayDef::Image { .. } => "image",
    }
}

/// `#RRGGBB` or `#AARRGGBB`.
fn is_color(color: &str) -> bool {
    color.starts_with('#')
        && matches!(color.len(), 7 | 9)
        && color[1..].chars().all(|c| c.is_ascii_hexdigit())
}

impl CentityContext {
    /// Changes a node's body and wakes it; false for a node without `physics`.
    fn change_body(
        &self,
        this: &handles::Node,
        f: impl FnOnce(&mut super::physics::BodyState),
    ) -> bool {
        self.with_node(this, |i, at| {
            let Some(b) = &mut i.bodies[at] else {
                return false;
            };
            f(b);
            b.wake();
            true
        })
        .unwrap_or(false)
    }

    /// Sets or adds to a body's velocity, or (in degrees a second) its spin, and wakes it.
    fn motion(
        &self,
        this: &handles::Node,
        v: values::Vec3,
        spin: bool,
        add: bool,
    ) -> ApiResult<bool> {
        let v = finite_velocity(v, if spin { "angular_velocity" } else { "velocity" })?;
        Ok(self.change_body(this, |b| {
            if spin {
                let r = Vec3(v.0.to_radians(), v.1.to_radians(), v.2.to_radians());
                b.angular_velocity = if add { b.angular_velocity + r } else { r };
            } else {
                b.velocity = if add { b.velocity + v } else { v };
            }
        }))
    }

    /// Pushes a node's body with `impulse` (along the world's axes) at world `point`, or through its centre.
    fn impulse(&self, this: &handles::Node, impulse: Vec3, point: Option<Vec3>) -> bool {
        let Some((id, has)) = self.with_node(this, |i, at| (i.id, i.bodies[at].is_some())) else {
            return false;
        };
        if !has {
            return false;
        }
        let pushed = self
            .shared
            .physics
            .borrow_mut()
            .with_body(id, &this.name, |body| {
                use rapier3d_f64::math::Vector;
                let i = Vector::new(impulse.0, impulse.1, impulse.2);
                match point {
                    Some(p) => body.apply_impulse_at_point(i, Vector::new(p.0, p.1, p.2), true),
                    None => body.apply_impulse(i, true),
                }
                let (v, w) = (body.linvel(), body.angvel());
                (Vec3(v.x, v.y, v.z), Vec3(w.x, w.y, w.z))
            });
        let mass = self.mass(this).unwrap_or(1.0).max(1e-3);
        self.change_body(this, |b| match pushed {
            Some((v, w)) => {
                b.velocity = v;
                b.angular_velocity = w;
            }
            // Not made yet: through its centre, by its mass.
            None => b.velocity = b.velocity + impulse * (1.0 / mass),
        })
    }

    fn channel(&self, this: &handles::Node, channel: Channel) -> Option<values::Vec3> {
        self.with_node(this, |i, at| value(i.get(at, channel)))
    }

    fn set_channel(
        &self,
        this: &handles::Node,
        channel: Channel,
        to: values::Vec3,
        options: Option<shapes::PoseOptions>,
    ) {
        let instant = options.and_then(|o| o.instant).unwrap_or(false);
        self.with_node(this, |i, at| i.set(at, channel, vec(to), instant));
    }
}

impl NodeApi for CentityContext {
    fn name(&self, this: &handles::Node) -> ApiResult<String> {
        Ok(this.name.clone())
    }
    fn centity(&self, this: &handles::Node) -> ApiResult<handles::Centity> {
        Ok(handles::Centity {
            id: this.centity.clone(),
        })
    }
    fn parent(&self, this: &handles::Node) -> ApiResult<Option<handles::Node>> {
        Ok(self
            .with_node(this, |i, at| {
                let parent = i.definition.nodes[at].parent_index;
                (parent >= 0).then(|| {
                    Self::node_handle(&this.centity, &i.definition.nodes[parent as usize].name)
                })
            })
            .flatten())
    }
    fn children(&self, this: &handles::Node) -> ApiResult<Vec<handles::Node>> {
        Ok(self
            .with_node(this, |i, at| {
                i.definition
                    .nodes
                    .iter()
                    .filter(|n| n.parent_index == at as i32)
                    .map(|n| Self::node_handle(&this.centity, &n.name))
                    .collect()
            })
            .unwrap_or_default())
    }
    fn translation(&self, this: &handles::Node) -> ApiResult<Option<values::Vec3>> {
        Ok(self.channel(this, Channel::Translation))
    }
    fn rotation(&self, this: &handles::Node) -> ApiResult<Option<values::Vec3>> {
        Ok(self.channel(this, Channel::Rotation))
    }
    fn scale(&self, this: &handles::Node) -> ApiResult<Option<values::Vec3>> {
        Ok(self.channel(this, Channel::Scale))
    }
    fn set_translation(
        &self,
        this: &handles::Node,
        translation: values::Vec3,
        options: Option<shapes::PoseOptions>,
    ) -> ApiResult<()> {
        self.set_channel(this, Channel::Translation, translation, options);
        Ok(())
    }
    fn set_rotation(
        &self,
        this: &handles::Node,
        rotation: values::Vec3,
        options: Option<shapes::PoseOptions>,
    ) -> ApiResult<()> {
        self.set_channel(this, Channel::Rotation, rotation, options);
        Ok(())
    }
    fn set_scale(
        &self,
        this: &handles::Node,
        scale: values::Vec3,
        options: Option<shapes::PoseOptions>,
    ) -> ApiResult<()> {
        self.set_channel(this, Channel::Scale, scale, options);
        Ok(())
    }
    fn world_position(&self, this: &handles::Node) -> ApiResult<Option<values::Vec3>> {
        Ok(self.with_node(this, |i, at| value(i.world_matrix(at).translation())))
    }
    fn to_local(
        &self,
        this: &handles::Node,
        point: values::Vec3,
    ) -> ApiResult<Option<values::Vec3>> {
        Ok(self
            .with_node(this, |i, at| invert(i.world_matrix(at)))
            .flatten()
            .map(|m| value(m.transform_position(vec(point)))))
    }
    fn to_world(
        &self,
        this: &handles::Node,
        point: values::Vec3,
    ) -> ApiResult<Option<values::Vec3>> {
        Ok(self.with_node(this, |i, at| {
            value(i.world_matrix(at).transform_position(vec(point)))
        }))
    }
    fn is_visible(&self, this: &handles::Node) -> ApiResult<bool> {
        Ok(self.with_node(this, |i, at| i.visible(at)).unwrap_or(false))
    }
    fn set_visible(&self, this: &handles::Node, visible: bool) -> ApiResult<()> {
        self.with_node(this, |i, at| i.set_visible(at, visible));
        Ok(())
    }
    fn display_kind(&self, this: &handles::Node) -> ApiResult<Option<String>> {
        Ok(self
            .with_node(this, |i, at| i.display(at).map(|d| kind_of(d).to_owned()))
            .flatten())
    }
    fn display_color(&self, this: &handles::Node) -> ApiResult<Option<String>> {
        Ok(self
            .with_node(this, |i, at| match i.display(at) {
                Some(DisplayDef::Rect { color, .. }) => Some(color.clone()),
                _ => None,
            })
            .flatten())
    }
    fn set_display_color(&self, this: &handles::Node, color: String) -> ApiResult<bool> {
        if !is_color(&color) {
            return Err(err(format!(
                "bad argument 'color' (#RRGGBB or #AARRGGBB, got \"{color}\")"
            )));
        }
        let name = this.name.clone();
        match self.with_node(this, |i, at| match i.display(at).cloned() {
            Some(DisplayDef::Rect {
                color: now,
                size,
                anchor,
            }) => {
                if now != color {
                    i.set_display(
                        at,
                        DisplayDef::Rect {
                            color,
                            size,
                            anchor,
                        },
                    );
                }
                Ok(true)
            }
            _ => Err(err(format!("node \"{name}\" isn't a rect display"))),
        }) {
            Some(result) => result,
            None => Ok(false),
        }
    }
    fn display_text(&self, this: &handles::Node) -> ApiResult<Option<values::Text>> {
        Ok(self
            .with_node(this, |i, at| match i.display(at) {
                Some(DisplayDef::Text { text, .. }) => Some(values::Text(text.clone())),
                _ => None,
            })
            .flatten())
    }
    fn set_display_text(&self, this: &handles::Node, text: values::Text) -> ApiResult<bool> {
        Ok(self
            .with_node(this, |i, at| match i.display(at).cloned() {
                Some(mut display @ DisplayDef::Text { .. }) => {
                    if let DisplayDef::Text { text: now, .. } = &mut display {
                        *now = text.0;
                    }
                    i.set_display(at, display);
                    true
                }
                _ => false,
            })
            .unwrap_or(false))
    }
    fn display_block(&self, this: &handles::Node) -> ApiResult<Option<String>> {
        Ok(self
            .with_node(this, |i, at| match i.display(at) {
                Some(DisplayDef::Block { block }) => Some(block.clone()),
                _ => None,
            })
            .flatten())
    }
    fn billboard(&self, this: &handles::Node) -> ApiResult<Option<String>> {
        Ok(self
            .with_node(this, |i, at| {
                i.display(at)?;
                Some(netherforge_format::canonical::name_of(&i.billboard(at)))
            })
            .flatten())
    }
    fn set_billboard(&self, this: &handles::Node, billboard: String) -> ApiResult<bool> {
        let value = match billboard.as_str() {
            "fixed" => Billboard::Fixed,
            "vertical" => Billboard::Vertical,
            "horizontal" => Billboard::Horizontal,
            "center" => Billboard::Center,
            _ => {
                return Err(err(format!(
                    "bad argument 'billboard' (\"fixed\", \"vertical\", \"horizontal\" or \"center\" expected, got \"{billboard}\")"
                )));
            }
        };
        Ok(self
            .with_node(this, |i, at| {
                if i.display(at).is_none() {
                    return false;
                }
                let mut look = i.look(at).clone();
                look.billboard = Some(value);
                i.set_look(at, look);
                true
            })
            .unwrap_or(false))
    }
    fn interpolation_ticks(&self, this: &handles::Node) -> ApiResult<Option<i64>> {
        Ok(self.with_node(this, |i, at| i64::from(i.interpolation(at))))
    }
    fn set_interpolation_ticks(&self, this: &handles::Node, ticks: i64) -> ApiResult<bool> {
        if !(0..=i64::from(i32::MAX)).contains(&ticks) {
            return Err(err(format!(
                "bad argument 'ticks' (at least 0 expected, got {ticks})"
            )));
        }
        Ok(self
            .with_node(this, |i, at| i.set_interpolation(at, ticks as i32))
            .is_some())
    }
    fn on(
        &self,
        this: &handles::Node,
        event: String,
        handler: Function,
        options: Option<shapes::EventOptions>,
    ) -> ApiResult<handles::Subscription> {
        let scope = self.running_scope("Node:on")?;
        let target = self
            .handle_target(Handle::Node(this.clone()))
            .ok_or_else(|| err("the node's handle is gone"))?;
        self.scripts
            .subscribe(scope, target, event, handler, options, false)
    }
    fn once(
        &self,
        this: &handles::Node,
        event: String,
        handler: Function,
    ) -> ApiResult<handles::Subscription> {
        let scope = self.running_scope("Node:once")?;
        let target = self
            .handle_target(Handle::Node(this.clone()))
            .ok_or_else(|| err("the node's handle is gone"))?;
        self.scripts
            .subscribe(scope, target, event, handler, None, true)
    }

    fn is_clickable(&self, this: &handles::Node) -> ApiResult<bool> {
        Ok(self
            .with_node(this, |i, at| i.clickable(at))
            .unwrap_or(false))
    }
    fn set_clickable(&self, this: &handles::Node, clickable: bool) -> ApiResult<()> {
        self.with_node(this, |i, at| i.set_clickable(at, clickable));
        Ok(())
    }
    fn world_rotation(&self, this: &handles::Node) -> ApiResult<Option<values::Vec3>> {
        Ok(self.with_node(this, |i, at| {
            value(Quat::from_basis(&i.world_matrix(at)).to_euler())
        }))
    }
    fn world_direction(
        &self,
        this: &handles::Node,
        direction: values::Vec3,
    ) -> ApiResult<Option<values::Vec3>> {
        Ok(self.with_node(this, |i, at| {
            value(Quat::from_basis(&i.world_matrix(at)).rotate(vec(direction)))
        }))
    }
    fn look_at(&self, this: &handles::Node, point: values::Vec3) -> ApiResult<bool> {
        Ok(self
            .with_node(this, |i, at| {
                // Where the point is from the node's origin, in the space its rotation is in: its parent's.
                let Some(inverse) = i.parent_world_matrix(at).invert_affine() else {
                    return false;
                };
                let target = inverse.transform_position(vec(point));
                let forward = target - i.get(at, Channel::Translation);
                let length = forward.length();
                if length < 1e-9 {
                    return true;
                }
                i.set(
                    at,
                    Channel::Rotation,
                    look_rotation(forward * (1.0 / length)),
                    false,
                );
                true
            })
            .unwrap_or(false))
    }
    fn rotate(&self, this: &handles::Node, axis: values::Vec3, degrees: f64) -> ApiResult<bool> {
        let axis = vec(axis);
        if axis.length() == 0.0 {
            return Err(err(
                "bad argument 'axis' (a rotation needs an axis that isn't zero)",
            ));
        }
        Ok(self
            .with_node(this, |i, at| {
                // Pre-multiplied: the turn happens about the parent's axis, after the node's own rotation.
                let turned =
                    Quat::about(axis, degrees) * Quat::from_euler(i.get(at, Channel::Rotation));
                i.set(at, Channel::Rotation, turned.normalized().to_euler(), false);
            })
            .is_some())
    }
    fn display_size(&self, this: &handles::Node) -> ApiResult<Option<values::Vec3>> {
        Ok(self
            .with_node(this, |i, at| {
                let size = match i.display(at)? {
                    DisplayDef::Rect { size, .. } => size.clone().unwrap_or_else(|| vec![1.0, 1.0]),
                    DisplayDef::Image {
                        width,
                        height,
                        size,
                        ..
                    } => size.clone().unwrap_or_else(|| {
                        vec![
                            f64::from(*width) / PIXELS_PER_BLOCK,
                            f64::from(*height) / PIXELS_PER_BLOCK,
                        ]
                    }),
                    _ => return None,
                };
                Some(value(Vec3(size[0], size[1], 0.0)))
            })
            .flatten())
    }
    fn set_display_size(&self, this: &handles::Node, size: values::Vec3) -> ApiResult<bool> {
        if !(size.x > 0.0 && size.y > 0.0 && size.x.is_finite() && size.y.is_finite()) {
            return Err(err(format!(
                "bad argument 'size' (a width and height more than 0, got {} by {})",
                lua_number(size.x),
                lua_number(size.y)
            )));
        }
        let wanted = Some(vec![size.x, size.y]);
        let name = this.name.clone();
        self.with_node(this, |i, at| {
            let mut display = i.display(at).cloned();
            match &mut display {
                Some(DisplayDef::Rect { size, .. } | DisplayDef::Image { size, .. }) => {
                    if *size != wanted {
                        *size = wanted;
                        i.set_display(at, display.expect("a display"));
                    }
                    Ok(true)
                }
                _ => Err(err(format!(
                    "node \"{name}\" isn't a rect or an image display"
                ))),
            }
        })
        .unwrap_or(Ok(false))
    }
    fn display_anchor(&self, this: &handles::Node) -> ApiResult<Option<String>> {
        Ok(self
            .with_node(this, |i, at| {
                let anchor = match i.display(at)? {
                    DisplayDef::Rect { anchor, .. } | DisplayDef::Image { anchor, .. } => {
                        Some(anchor.unwrap_or(Anchor::Center))
                    }
                    DisplayDef::Text { anchor, .. } => *anchor,
                    _ => None,
                }?;
                Some(netherforge_format::canonical::name_of(&anchor))
            })
            .flatten())
    }
    fn set_display_anchor(&self, this: &handles::Node, anchor: Option<String>) -> ApiResult<bool> {
        let wanted: Option<Anchor> = match &anchor {
            None => None,
            Some(name) => Some(
                serde_json::from_value(serde_json::Value::String(name.clone())).map_err(|_| {
                    err(format!(
                        "bad argument 'anchor' (an anchor such as \"bottom\", got \"{name}\")"
                    ))
                })?,
            ),
        };
        let name = this.name.clone();
        self.with_node(this, |i, at| {
            let mut display = i.display(at).cloned();
            match &mut display {
                Some(
                    DisplayDef::Rect { anchor, .. }
                    | DisplayDef::Image { anchor, .. }
                    | DisplayDef::Text { anchor, .. },
                ) => {
                    if *anchor != wanted {
                        *anchor = wanted;
                        i.set_display(at, display.expect("a display"));
                    }
                    Ok(true)
                }
                _ => Err(err(format!(
                    "node \"{name}\" isn't a rect, image or text display"
                ))),
            }
        })
        .unwrap_or(Ok(false))
    }
    fn set_display_block(&self, this: &handles::Node, block: String) -> ApiResult<bool> {
        let game = self.shared.platform.game();
        // A block state the server knows, with only properties the block has.
        let Some(state) = netherforge_format::block_state::BlockState::parse(&block) else {
            return Ok(false);
        };
        let Some(properties) = game.block_properties(&state.id) else {
            return Ok(false);
        };
        if state.properties.iter().any(|(name, value)| {
            !properties
                .get(name)
                .is_some_and(|values| values.contains(value))
        }) {
            return Ok(false);
        }
        Ok(self
            .with_node(this, |i, at| match i.display(at) {
                Some(DisplayDef::Block { .. }) => {
                    i.set_display(
                        at,
                        DisplayDef::Block {
                            block: state.to_string(),
                        },
                    );
                    true
                }
                _ => false,
            })
            .unwrap_or(false))
    }
    fn display_item(&self, this: &handles::Node) -> ApiResult<Option<values::Item>> {
        Ok(self
            .with_node(this, |i, at| match i.display(at) {
                Some(DisplayDef::Item { item, .. }) => {
                    Some(crate::items::to_value(&i.look(at).item.clone().unwrap_or(
                        netherforge_platform::ItemData {
                            def: netherforge_format::item::ItemDef {
                                kind: item.clone(),
                                ..Default::default()
                            },
                            raw: None,
                            look: None,
                        },
                    )))
                }
                _ => None,
            })
            .flatten())
    }
    fn set_display_item(&self, this: &handles::Node, item: values::Item) -> ApiResult<bool> {
        let mut item =
            crate::items::from_value(&item, "item", self.shared.platform.game()).map_err(err)?;
        Ok(self
            .with_node(this, |i, at| match i.display(at).cloned() {
                Some(DisplayDef::Item { item_transform, .. }) => {
                    // The content names the kind (what a respawn builds); the look carries the whole item over it.
                    i.set_display(
                        at,
                        DisplayDef::Item {
                            item: item.def.kind.clone(),
                            item_transform,
                        },
                    );
                    item.def.count = None;
                    let mut look = i.look(at).clone();
                    look.item = Some(item);
                    i.set_look(at, look);
                    true
                }
                _ => false,
            })
            .unwrap_or(false))
    }
    fn model(&self, this: &handles::Node) -> ApiResult<Option<String>> {
        Ok(self
            .with_node(this, |i, at| match i.display(at) {
                Some(DisplayDef::Model { model, .. }) => Some(model.clone()),
                _ => None,
            })
            .flatten())
    }
    fn set_model(&self, this: &handles::Node, model: String) -> ApiResult<bool> {
        Ok(self
            .with_node(this, |i, at| match i.display(at).cloned() {
                Some(DisplayDef::Model {
                    model: now,
                    item_transform,
                }) => {
                    if now != model {
                        i.set_display(
                            at,
                            DisplayDef::Model {
                                model,
                                item_transform,
                            },
                        );
                    }
                    true
                }
                _ => false,
            })
            .unwrap_or(false))
    }
    fn display_text_style(&self, this: &handles::Node) -> ApiResult<Option<shapes::TextStyle>> {
        Ok(self
            .with_node(this, |i, at| match i.display(at) {
                Some(DisplayDef::Text {
                    background,
                    shadow,
                    alignment,
                    line_width,
                    see_through,
                    ..
                }) => Some(shapes::TextStyle {
                    background: background.clone(),
                    opacity: Some(i64::from(i.look(at).text_opacity.unwrap_or(OPAQUE))),
                    shadow: Some(shadow.unwrap_or(false)),
                    alignment: Some(netherforge_format::canonical::name_of(
                        &alignment.unwrap_or(TextAlignment::Center),
                    )),
                    line_width: Some(i64::from(line_width.unwrap_or(DEFAULT_LINE_WIDTH))),
                    see_through: Some(see_through.unwrap_or(false)),
                }),
                _ => None,
            })
            .flatten())
    }
    fn set_display_text_style(
        &self,
        this: &handles::Node,
        style: shapes::TextStyle,
    ) -> ApiResult<bool> {
        if let Some(background) = &style.background
            && !(background.len() == 9 && is_color(background))
        {
            return Err(err(format!(
                "bad field 'style.background' (#AARRGGBB expected, got \"{background}\")"
            )));
        }
        if let Some(opacity) = style.opacity
            && !(0..=i64::from(OPAQUE)).contains(&opacity)
        {
            return Err(err(format!(
                "bad field 'style.opacity' (0 to 255 expected, got {opacity})"
            )));
        }
        let alignment = match style.alignment.as_deref() {
            None => None,
            Some("center") => Some(TextAlignment::Center),
            Some("left") => Some(TextAlignment::Left),
            Some("right") => Some(TextAlignment::Right),
            Some(other) => {
                return Err(err(format!(
                    "bad field 'style.alignment' (\"center\", \"left\" or \"right\" expected, got \"{other}\")"
                )));
            }
        };
        if let Some(width) = style.line_width
            && !(1..=i64::from(i32::MAX)).contains(&width)
        {
            return Err(err(format!(
                "bad field 'style.line_width' (at least 1 expected, got {width})"
            )));
        }
        Ok(self
            .with_node(this, |i, at| match i.display(at).cloned() {
                Some(mut display @ DisplayDef::Text { .. }) => {
                    if let DisplayDef::Text {
                        alignment: a,
                        background,
                        line_width,
                        see_through,
                        shadow,
                        ..
                    } = &mut display
                    {
                        *a = alignment;
                        *background = style.background.clone();
                        *line_width = style.line_width.map(|w| w as i32);
                        *see_through = style.see_through;
                        *shadow = style.shadow;
                    }
                    i.set_display(at, display);
                    let mut look = i.look(at).clone();
                    look.text_opacity = style.opacity.map(|o| o as i32).filter(|o| *o != OPAQUE);
                    i.set_look(at, look);
                    true
                }
                _ => false,
            })
            .unwrap_or(false))
    }
    fn is_glowing(&self, this: &handles::Node) -> ApiResult<bool> {
        Ok(self
            .with_node(this, |i, at| i.display(at).is_some() && i.look(at).glowing)
            .unwrap_or(false))
    }
    fn set_glowing(&self, this: &handles::Node, glowing: bool) -> ApiResult<bool> {
        Ok(self
            .with_node(this, |i, at| {
                if i.display(at).is_none() {
                    return false;
                }
                let mut look = i.look(at).clone();
                look.glowing = glowing;
                i.set_look(at, look);
                true
            })
            .unwrap_or(false))
    }
    fn glow_color(&self, this: &handles::Node) -> ApiResult<Option<String>> {
        Ok(self
            .with_node(this, |i, at| match i.display(at) {
                None | Some(DisplayDef::Text { .. }) => None,
                Some(_) => i.look(at).glow_color.map(format_rgb),
            })
            .flatten())
    }
    fn set_glow_color(&self, this: &handles::Node, color: Option<String>) -> ApiResult<bool> {
        let rgb = color
            .as_deref()
            .map(|c| parse_rgb(c, "color"))
            .transpose()?;
        Ok(self
            .with_node(this, |i, at| match i.display(at) {
                // Minecraft draws no glow colour on a text display.
                None | Some(DisplayDef::Text { .. }) => false,
                Some(_) => {
                    let mut look = i.look(at).clone();
                    look.glow_color = rgb;
                    i.set_look(at, look);
                    true
                }
            })
            .unwrap_or(false))
    }
    fn brightness(&self, this: &handles::Node) -> ApiResult<Option<shapes::Brightness>> {
        Ok(self
            .with_node(this, |i, at| {
                i.display(at)?;
                i.look(at).brightness.as_ref().map(|b| shapes::Brightness {
                    block_light: Some(i64::from(b.block)),
                    sky_light: Some(i64::from(b.sky)),
                })
            })
            .flatten())
    }
    fn set_brightness(
        &self,
        this: &handles::Node,
        brightness: Option<shapes::Brightness>,
    ) -> ApiResult<bool> {
        let level = |value: Option<i64>, field: &str| -> ApiResult<i32> {
            let value = value.ok_or_else(|| {
                err(format!(
                    "bad field 'brightness.{field}' (a light level from 0 to 15 is required)"
                ))
            })?;
            if !(0..=15).contains(&value) {
                return Err(err(format!(
                    "bad field 'brightness.{field}' (0 to 15 expected, got {value})"
                )));
            }
            Ok(value as i32)
        };
        let levels = match &brightness {
            None => None,
            Some(b) => Some(netherforge_platform::DisplayBrightness {
                block: level(b.block_light, "block_light")?,
                sky: level(b.sky_light, "sky_light")?,
            }),
        };
        Ok(self
            .with_node(this, |i, at| {
                if i.display(at).is_none() {
                    return false;
                }
                let mut look = i.look(at).clone();
                look.brightness = levels;
                i.set_look(at, look);
                true
            })
            .unwrap_or(false))
    }

    fn add_child(
        &self,
        this: &handles::Node,
        name: String,
        definition: Value,
    ) -> ApiResult<Option<handles::Node>> {
        let Some(parent) = self.with_node(this, |_, at| at) else {
            return Ok(None);
        };
        let centity = handles::Centity {
            id: this.centity.clone(),
        };
        self.add(&centity, name, Some(parent), definition)
    }
    fn remove(&self, this: &handles::Node) -> ApiResult<bool> {
        let name = this.name.clone();
        let Some(result) = self.with_node(this, |i, at| {
            if !i.is_added(at) {
                return Err(err(format!(
                    "node \"{name}\" is declared by centity.json; only nodes a script added can be removed"
                )));
            }
            Ok((i.id, i.remove_node(at)))
        }) else {
            return Ok(false);
        };
        let (id, gone) = result?;
        for node in gone {
            self.drop_node(id, &node);
        }
        Ok(true)
    }
    fn stretch_between(
        &self,
        this: &handles::Node,
        from: values::Vec3,
        to: values::Vec3,
        options: Option<shapes::StretchOptions>,
    ) -> ApiResult<bool> {
        let thickness = options.and_then(|o| o.thickness);
        if let Some(t) = thickness
            && !(t > 0.0 && t.is_finite())
        {
            return Err(err(format!(
                "bad argument 'options.thickness' (more than 0, got {})",
                lua_number(t)
            )));
        }
        Ok(self
            .with_node(this, |i, at| {
                super::ropes::stretch(i, at, vec(from), vec(to), thickness, None)
            })
            .unwrap_or(false))
    }
    fn is_rope(&self, this: &handles::Node) -> ApiResult<bool> {
        Ok(self
            .with_node(this, |i, at| i.definition.nodes[at].rope.is_some())
            .unwrap_or(false))
    }
    fn rope_between(
        &self,
        this: &handles::Node,
        from: unions::Vec3OrNodeOrEntity,
        to: unions::Vec3OrNodeOrEntity,
        options: Option<shapes::RopeOptions>,
    ) -> ApiResult<bool> {
        use super::ropes::{RopeEnd, StrungRope};
        let length = options.and_then(|o| o.length);
        if let Some(l) = length
            && !(l > 0.0 && l.is_finite())
        {
            return Err(err(format!(
                "bad argument 'options.length' (more than 0, got {})",
                lua_number(l)
            )));
        }
        let end = |end: unions::Vec3OrNodeOrEntity| -> Option<RopeEnd> {
            Some(match end {
                unions::Vec3OrNodeOrEntity::Vec3(p) => RopeEnd::Point(vec(p)),
                unions::Vec3OrNodeOrEntity::Node(n) => RopeEnd::OfNode {
                    centity: parse_uuid(&n.centity)?,
                    node: n.name,
                },
                unions::Vec3OrNodeOrEntity::Entity(e) => RopeEnd::OfEntity(parse_uuid(&e.id)?),
            })
        };
        let name = this.name.clone();
        let (from, to) = (end(from), end(to));
        self.with_node(this, |i, at| {
            if i.definition.nodes[at].rope.is_none() {
                return Err(err(format!(
                    "node \"{name}\" isn't a rope: give it \"rope\" in centity.json"
                )));
            }
            let (Some(from), Some(to)) = (from, to) else {
                return Ok(false);
            };
            // Laid on each of its ticks from the next, wherever its ends are then.
            i.ropes
                .insert(name.clone(), StrungRope { from, to, length });
            Ok(true)
        })
        .unwrap_or(Ok(false))
    }
    fn release_rope(&self, this: &handles::Node) -> ApiResult<()> {
        self.with_node(this, |i, _| i.ropes.remove(&this.name));
        Ok(())
    }
    fn tween(&self, this: &handles::Node, options: shapes::TweenOptions) -> ApiResult<bool> {
        let targets: Vec<(Channel, values::Vec3)> = [
            (Channel::Translation, options.translation),
            (Channel::Rotation, options.rotation),
            (Channel::Scale, options.scale),
        ]
        .into_iter()
        .filter_map(|(c, v)| Some((c, v?)))
        .collect();
        if targets.is_empty() {
            return Err(err(
                "bad argument 'options' (a tween needs translation, rotation or scale)",
            ));
        }
        if options.ticks < 1 {
            return Err(err(format!(
                "bad argument 'options.ticks' (at least 1, got {})",
                options.ticks
            )));
        }
        let easing = match options.easing.as_deref() {
            None => Easing::EaseInOut,
            Some(name) => serde_json::from_value(serde_json::Value::String(name.to_owned())).map_err(|_| {
                err(format!(
                    "bad argument 'options.easing' (linear, step, ease_in, ease_out, ease_in_out, got \"{name}\")"
                ))
            })?,
        };
        let ticks = options.ticks.min(i64::from(i32::MAX / 2)) as i32;
        let back = options.back == Some(true);
        Ok(self
            .with_node(this, |i, at| {
                for (channel, to) in &targets {
                    let from = i.rest(at, *channel);
                    i.tween(super::instance::Tween {
                        index: at,
                        channel: *channel,
                        from,
                        to: vec(*to),
                        ticks,
                        easing,
                        back,
                        tick: 0,
                    });
                }
            })
            .is_some())
    }
    fn is_tweening(&self, this: &handles::Node) -> ApiResult<bool> {
        Ok(self
            .with_node(this, |i, at| i.tweens.iter().any(|t| t.index == at))
            .unwrap_or(false))
    }
    fn stop_tween(&self, this: &handles::Node) -> ApiResult<bool> {
        Ok(self
            .with_node(this, |i, at| {
                let before = i.tweens.len();
                i.tweens.retain(|t| t.index != at);
                i.tweens.len() != before
            })
            .unwrap_or(false))
    }

    fn velocity(&self, this: &handles::Node) -> ApiResult<Option<values::Vec3>> {
        Ok(self.with_node(this, |i, at| {
            value(i.bodies[at].as_ref().map_or(Vec3::ZERO, |b| b.velocity))
        }))
    }
    fn set_velocity(&self, this: &handles::Node, velocity: values::Vec3) -> ApiResult<bool> {
        self.motion(this, velocity, false, false)
    }
    fn add_velocity(&self, this: &handles::Node, velocity: values::Vec3) -> ApiResult<bool> {
        self.motion(this, velocity, false, true)
    }
    fn angular_velocity(&self, this: &handles::Node) -> ApiResult<Option<values::Vec3>> {
        Ok(self.with_node(this, |i, at| {
            let r = i.bodies[at]
                .as_ref()
                .map_or(Vec3::ZERO, |b| b.angular_velocity);
            value(Vec3(r.0.to_degrees(), r.1.to_degrees(), r.2.to_degrees()))
        }))
    }
    fn set_angular_velocity(
        &self,
        this: &handles::Node,
        angular_velocity: values::Vec3,
    ) -> ApiResult<bool> {
        self.motion(this, angular_velocity, true, false)
    }
    fn mass(&self, this: &handles::Node) -> ApiResult<f64> {
        let Some((id, name)) = self.with_node(this, |i, _| (i.id, this.name.clone())) else {
            return Ok(0.0);
        };
        if let Some(mass) = self
            .shared
            .physics
            .borrow_mut()
            .with_body(id, &name, |b| b.mass())
        {
            return Ok(mass);
        }
        let game = self.shared.platform.game();
        Ok(self
            .with_node(this, |i, at| {
                let node = &i.definition.nodes[at];
                let physics = node.physics.as_ref()?;
                let hitbox = node.hitbox.as_ref().map(|_| super::boxes_of(game, i, at));
                let boxes = super::physics::collider_boxes(physics, hitbox);
                let scale = i.world_matrix(at).scale_of();
                Some(super::physics::mass_of(physics, &boxes, scale))
            })
            .flatten()
            .unwrap_or(0.0))
    }
    fn apply_impulse(&self, this: &handles::Node, impulse: values::Vec3) -> ApiResult<bool> {
        Ok(self.impulse(this, vec(impulse), None))
    }
    fn apply_impulse_at(
        &self,
        this: &handles::Node,
        impulse: values::Vec3,
        point: values::Vec3,
    ) -> ApiResult<bool> {
        Ok(self.impulse(this, vec(impulse), Some(vec(point))))
    }
    fn apply_force(&self, this: &handles::Node, force: values::Vec3) -> ApiResult<bool> {
        // A force for one tick is the impulse it adds up to over that tick.
        Ok(self.impulse(this, vec(force) * SECONDS_PER_TICK, None))
    }
    fn is_on_ground(&self, this: &handles::Node) -> ApiResult<bool> {
        Ok(self
            .with_node(this, |i, at| {
                i.bodies[at].as_ref().is_some_and(|b| b.on_ground)
            })
            .unwrap_or(false))
    }
    fn is_asleep(&self, this: &handles::Node) -> ApiResult<bool> {
        Ok(self
            .with_node(this, |i, at| {
                i.bodies[at].as_ref().is_some_and(|b| b.asleep)
            })
            .unwrap_or(false))
    }
    fn wake(&self, this: &handles::Node) -> ApiResult<()> {
        self.with_node(this, |i, at| {
            if let Some(b) = &mut i.bodies[at] {
                b.wake();
            }
        });
        Ok(())
    }
    fn has_gravity(&self, this: &handles::Node) -> ApiResult<bool> {
        Ok(self
            .with_node(this, |i, at| {
                i.bodies[at].as_ref().is_some_and(|b| b.gravity)
            })
            .unwrap_or(false))
    }
    fn set_gravity(&self, this: &handles::Node, gravity: bool) -> ApiResult<bool> {
        Ok(self.change_body(this, |b| b.gravity = gravity))
    }
    fn is_kinematic(&self, this: &handles::Node) -> ApiResult<bool> {
        Ok(self
            .with_node(this, |i, at| {
                i.bodies[at].as_ref().is_some_and(|b| b.kinematic)
            })
            .unwrap_or(false))
    }
    fn set_kinematic(&self, this: &handles::Node, kinematic: bool) -> ApiResult<bool> {
        Ok(self.change_body(this, |b| b.kinematic = kinematic))
    }
    fn is_physics_enabled(&self, this: &handles::Node) -> ApiResult<bool> {
        Ok(self
            .with_node(this, |i, at| {
                i.bodies[at].as_ref().is_some_and(|b| b.enabled)
            })
            .unwrap_or(false))
    }
    fn set_physics_enabled(&self, this: &handles::Node, enabled: bool) -> ApiResult<bool> {
        Ok(self.change_body(this, |b| b.enabled = enabled))
    }

    fn image(&self, this: &handles::Node) -> ApiResult<Option<handles::Image>> {
        let pixels = self
            .with_node(this, |i, at| match i.display(at) {
                Some(DisplayDef::Image { pixels, .. }) => pixels.clone(),
                _ => None,
            })
            .flatten();
        pixels.map(|p| self.shared.images.hold(p)).transpose()
    }
    fn set_image(&self, this: &handles::Node, image: Option<handles::Image>) -> ApiResult<bool> {
        let pixels = match image {
            None => None,
            Some(handle) => Some(
                self.shared
                    .images
                    .get(handle.number)
                    .ok_or_else(|| err("this image is gone (the session that made it ended)"))?,
            ),
        };
        let name = this.name.clone();
        self.with_node(this, |i, at| match i.display(at).cloned() {
            Some(mut display @ DisplayDef::Image { .. }) => {
                if let DisplayDef::Image { pixels: now, .. } = &mut display
                    && *now != pixels
                {
                    *now = pixels;
                    i.set_display(at, display);
                }
                Ok(true)
            }
            _ => Err(err(format!("node \"{name}\" isn't an image display"))),
        })
        .unwrap_or(Ok(false))
    }

    // ---- not ported yet ----
}
