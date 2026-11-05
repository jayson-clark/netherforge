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
use netherforge_api::unported::Unported;
use netherforge_api::{ApiError, ApiResult, Caller, codecs, handle, shapes, unions, values};
use netherforge_format::kind::centity::{Billboard, DisplayDef};
use netherforge_format::math::{Matrix4, Vec3};
use netherforge_luau::ScopeId;
use netherforge_platform::{Location, Uuid};

use super::Instance;
use super::instance::Channel;
use crate::events::{parse_uuid, uuid_text};
use crate::shared::Shared;
use crate::{Scripts, Target};

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
    shared: Rc<Shared>,
    scripts: Scripts,
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

    fn handle_target(&self, handle: Handle) -> Option<Target> {
        let host = self.scripts.host();
        handle::push_any(host.lua(), &handle)
            .ok()
            .map(Target::Handle)
    }

    fn centity_target(&self, id: Uuid) -> Option<Target> {
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

    // ---- lives ----

    /// Puts a new instance of `centity` into the world at `at`, facing `yaw`: its entities made, its
    /// script's body run and `spawn` heard. None when there's no such centity or world.
    pub fn spawn(&self, centity: &str, at: Location, yaw: f64) -> Option<Uuid> {
        let platform = &*self.shared.platform;
        let id = self
            .shared
            .with_drawing(|c, drawing| c.spawn(platform, drawing, centity, at, yaw, None))?;
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

    /// One tick of every instance: `tick` to whoever listens on it, then its entities synced.
    pub fn tick(&self) {
        let ids: Vec<Uuid> = self.shared.centities.borrow().all().map(|i| i.id).collect();
        let now = self.scripts.tick_count();
        for id in ids {
            match self.shared.centities.borrow_mut().get_mut(id) {
                Some(instance) if !instance.removed => instance.age += 1,
                _ => continue,
            }
            if let Some(target) = self.centity_target(id)
                && self.scripts.listening(&target, "tick")
            {
                let lua = self.scripts.host().lua().clone();
                let payload = shapes::TickEvent { tick: now as i64 };
                if let Ok(Value::Table(fields)) = codecs::TickEvent.push(&lua, payload) {
                    self.scripts.raise(&target, "tick", fields);
                }
            }
            let platform = &*self.shared.platform;
            self.shared
                .with_drawing(|c, drawing| c.sync(platform, drawing, id));
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
    pub fn stop_all(&self) {
        let ids: Vec<Uuid> = self.shared.centities.borrow().all().map(|i| i.id).collect();
        for id in ids {
            self.stop_script(id);
        }
        self.shared
            .centities
            .borrow_mut()
            .remove_all(&*self.shared.platform);
    }

    // ---- looking things up ----

    fn id_of(this: &handles::Centity) -> Option<Uuid> {
        parse_uuid(&this.id)
    }

    /// `f` of the live instance `this` names; none once it's gone.
    fn with<R>(&self, this: &handles::Centity, f: impl FnOnce(&mut Instance) -> R) -> Option<R> {
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

    fn node_handle(centity: &str, name: &str) -> handles::Node {
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
        if let Some(options) = &options {
            if options.temporary == Some(true) {
                return Err(err(
                    "option 'temporary' of nf.centities.spawn isn't on the Rust runtime yet",
                ));
            }
            if options.lifetime.is_some() {
                return Err(err(
                    "option 'lifetime' of nf.centities.spawn isn't on the Rust runtime yet",
                ));
            }
            if options.viewers.is_some() {
                return Err(err(
                    "option 'viewers' of nf.centities.spawn isn't on the Rust runtime yet",
                ));
            }
        }
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
        Ok(CentityContext::spawn(self, &kind, at, yaw.unwrap_or(0.0))
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
        Ok(self
            .with(this, |i| i.hidden_from.contains(&player))
            .unwrap_or(false))
    }
    fn viewers(&self, _: &handles::Centity) -> ApiResult<Option<Vec<handles::Player>>> {
        // Only a private centity has viewers, and those aren't ported yet.
        Ok(None)
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

    // ---- not ported yet ----
    fn is_natural(&self, this: &handles::Centity) -> ApiResult<bool> {
        CentityApi::is_natural(&Unported, this)
    }
    fn is_temporary(&self, this: &handles::Centity) -> ApiResult<bool> {
        CentityApi::is_temporary(&Unported, this)
    }
    fn lifetime(&self, this: &handles::Centity) -> ApiResult<Option<i64>> {
        CentityApi::lifetime(&Unported, this)
    }
    fn set_lifetime(&self, this: &handles::Centity, ticks: Option<i64>) -> ApiResult<bool> {
        CentityApi::set_lifetime(&Unported, this, ticks)
    }
    fn keep(&self, this: &handles::Centity) -> ApiResult<bool> {
        CentityApi::keep(&Unported, this)
    }
    fn add_node(
        &self,
        this: &handles::Centity,
        name: String,
        definition: Value,
    ) -> ApiResult<Option<handles::Node>> {
        CentityApi::add_node(&Unported, this, name, definition)
    }
    fn bounds(&self, this: &handles::Centity) -> ApiResult<Option<shapes::Bounds>> {
        CentityApi::bounds(&Unported, this)
    }
    fn look_at(&self, this: &handles::Centity, point: values::Vec3) -> ApiResult<bool> {
        CentityApi::look_at(&Unported, this, point)
    }
    fn follow(&self, this: &handles::Centity, player: handles::Player) -> ApiResult<bool> {
        CentityApi::follow(&Unported, this, player)
    }
    fn stop_following(&self, this: &handles::Centity) -> ApiResult<bool> {
        CentityApi::stop_following(&Unported, this)
    }
    fn stat(&self, this: &handles::Centity, name: String) -> ApiResult<f64> {
        CentityApi::stat(&Unported, this, name)
    }
    fn stats(&self, this: &handles::Centity) -> ApiResult<BTreeMap<String, f64>> {
        CentityApi::stats(&Unported, this)
    }
    fn modifiers(
        &self,
        this: &handles::Centity,
        source: String,
    ) -> ApiResult<Option<BTreeMap<String, f64>>> {
        CentityApi::modifiers(&Unported, this, source)
    }
    fn set_modifiers(
        &self,
        this: &handles::Centity,
        source: String,
        modifiers: Option<BTreeMap<String, f64>>,
    ) -> ApiResult<bool> {
        CentityApi::set_modifiers(&Unported, this, source, modifiers)
    }
    fn add_buff(&self, this: &handles::Centity, buff: String, ticks: i64) -> ApiResult<bool> {
        CentityApi::add_buff(&Unported, this, buff, ticks)
    }
    fn remove_buff(&self, this: &handles::Centity, buff: String) -> ApiResult<bool> {
        CentityApi::remove_buff(&Unported, this, buff)
    }
    fn has_buff(&self, this: &handles::Centity, buff: String) -> ApiResult<bool> {
        CentityApi::has_buff(&Unported, this, buff)
    }
    fn buff_ticks(&self, this: &handles::Centity, buff: String) -> ApiResult<Option<i64>> {
        CentityApi::buff_ticks(&Unported, this, buff)
    }
    fn buffs(&self, this: &handles::Centity) -> ApiResult<Vec<shapes::ActiveBuff>> {
        CentityApi::buffs(&Unported, this)
    }
    fn health(&self, this: &handles::Centity) -> ApiResult<Option<f64>> {
        CentityApi::health(&Unported, this)
    }
    fn max_health(&self, this: &handles::Centity) -> ApiResult<Option<f64>> {
        CentityApi::max_health(&Unported, this)
    }
    fn set_health(&self, this: &handles::Centity, health: f64) -> ApiResult<bool> {
        CentityApi::set_health(&Unported, this, health)
    }
    fn set_max_health(&self, this: &handles::Centity, max_health: f64) -> ApiResult<bool> {
        CentityApi::set_max_health(&Unported, this, max_health)
    }
    fn damage(
        &self,
        this: &handles::Centity,
        amount: f64,
        options: Option<shapes::CentityDamageOptions>,
    ) -> ApiResult<Option<f64>> {
        CentityApi::damage(&Unported, this, amount, options)
    }
    fn heal(&self, this: &handles::Centity, amount: f64) -> ApiResult<Option<f64>> {
        CentityApi::heal(&Unported, this, amount)
    }
    fn is_dead(&self, this: &handles::Centity) -> ApiResult<bool> {
        CentityApi::is_dead(&Unported, this)
    }
    fn has_body(&self, this: &handles::Centity) -> ApiResult<bool> {
        CentityApi::has_body(&Unported, this)
    }
    fn body(&self, this: &handles::Centity) -> ApiResult<Option<shapes::BodySettings>> {
        CentityApi::body(&Unported, this)
    }
    fn set_body(&self, this: &handles::Centity, settings: shapes::BodySettings) -> ApiResult<bool> {
        CentityApi::set_body(&Unported, this, settings)
    }
    fn velocity(&self, this: &handles::Centity) -> ApiResult<Option<values::Vec3>> {
        CentityApi::velocity(&Unported, this)
    }
    fn set_velocity(&self, this: &handles::Centity, velocity: values::Vec3) -> ApiResult<bool> {
        CentityApi::set_velocity(&Unported, this, velocity)
    }
    fn add_velocity(&self, this: &handles::Centity, velocity: values::Vec3) -> ApiResult<bool> {
        CentityApi::add_velocity(&Unported, this, velocity)
    }
    fn walk_velocity(&self, this: &handles::Centity) -> ApiResult<Option<values::Vec3>> {
        CentityApi::walk_velocity(&Unported, this)
    }
    fn set_walk_velocity(
        &self,
        this: &handles::Centity,
        velocity: Option<values::Vec3>,
    ) -> ApiResult<bool> {
        CentityApi::set_walk_velocity(&Unported, this, velocity)
    }
    fn jump(&self, this: &handles::Centity, height: f64) -> ApiResult<bool> {
        CentityApi::jump(&Unported, this, height)
    }
    fn is_on_ground(&self, this: &handles::Centity) -> ApiResult<bool> {
        CentityApi::is_on_ground(&Unported, this)
    }
    fn is_in_liquid(&self, this: &handles::Centity) -> ApiResult<bool> {
        CentityApi::is_in_liquid(&Unported, this)
    }
    fn wall(&self, this: &handles::Centity) -> ApiResult<Option<values::Vec3>> {
        CentityApi::wall(&Unported, this)
    }
    fn launch(&self, this: &handles::Centity, options: shapes::LaunchOptions) -> ApiResult<bool> {
        CentityApi::launch(&Unported, this, options)
    }
    fn is_launched(&self, this: &handles::Centity) -> ApiResult<bool> {
        CentityApi::is_launched(&Unported, this)
    }
    fn stop_flight(&self, this: &handles::Centity) -> ApiResult<bool> {
        CentityApi::stop_flight(&Unported, this)
    }
    fn move_to(
        &self,
        this: &handles::Centity,
        target: unions::LocationOrVec3OrEntityOrCentity,
        options: Option<shapes::CentityPathOptions>,
    ) -> ApiResult<bool> {
        CentityApi::move_to(&Unported, this, target, options)
    }
    fn stop_pathing(&self, this: &handles::Centity) -> ApiResult<bool> {
        CentityApi::stop_pathing(&Unported, this)
    }
    fn has_path(&self, this: &handles::Centity) -> ApiResult<bool> {
        CentityApi::has_path(&Unported, this)
    }
    fn path_target(&self, this: &handles::Centity) -> ApiResult<Option<values::Vec3>> {
        CentityApi::path_target(&Unported, this)
    }
    fn data(&self, this: &handles::Centity) -> ApiResult<Value> {
        CentityApi::data(&Unported, this)
    }
    fn play_animation(
        &self,
        this: &handles::Centity,
        name: String,
        options: Option<shapes::AnimationOptions>,
    ) -> ApiResult<bool> {
        CentityApi::play_animation(&Unported, this, name, options)
    }
    fn stop_animation(&self, this: &handles::Centity, name: Option<String>) -> ApiResult<()> {
        CentityApi::stop_animation(&Unported, this, name)
    }
    fn pause_animation(&self, this: &handles::Centity, name: String) -> ApiResult<bool> {
        CentityApi::pause_animation(&Unported, this, name)
    }
    fn resume_animation(&self, this: &handles::Centity, name: String) -> ApiResult<bool> {
        CentityApi::resume_animation(&Unported, this, name)
    }
    fn is_animation_playing(&self, this: &handles::Centity, name: String) -> ApiResult<bool> {
        CentityApi::is_animation_playing(&Unported, this, name)
    }
    fn playing_animations(&self, this: &handles::Centity) -> ApiResult<Vec<String>> {
        CentityApi::playing_animations(&Unported, this)
    }
    fn animations(&self, this: &handles::Centity) -> ApiResult<Vec<String>> {
        CentityApi::animations(&Unported, this)
    }
    fn animation_tick(&self, this: &handles::Centity, name: String) -> ApiResult<Option<f64>> {
        CentityApi::animation_tick(&Unported, this, name)
    }
    fn seek_animation(&self, this: &handles::Centity, name: String, tick: f64) -> ApiResult<bool> {
        CentityApi::seek_animation(&Unported, this, name, tick)
    }
    fn animation_speed(&self, this: &handles::Centity, name: String) -> ApiResult<Option<f64>> {
        CentityApi::animation_speed(&Unported, this, name)
    }
    fn set_animation_speed(
        &self,
        this: &handles::Centity,
        name: String,
        multiplier: f64,
    ) -> ApiResult<bool> {
        CentityApi::set_animation_speed(&Unported, this, name, multiplier)
    }
    fn is_glowing(&self, this: &handles::Centity) -> ApiResult<bool> {
        CentityApi::is_glowing(&Unported, this)
    }
    fn set_glowing(&self, this: &handles::Centity, glowing: bool) -> ApiResult<bool> {
        CentityApi::set_glowing(&Unported, this, glowing)
    }
    fn glow_color(&self, this: &handles::Centity) -> ApiResult<Option<String>> {
        CentityApi::glow_color(&Unported, this)
    }
    fn set_glow_color(&self, this: &handles::Centity, color: Option<String>) -> ApiResult<bool> {
        CentityApi::set_glow_color(&Unported, this, color)
    }
}

impl CentityContext {
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
                Some(DisplayDef::Text {
                    text: _,
                    billboard,
                    alignment,
                    background,
                    line_width,
                    see_through,
                    shadow,
                    anchor,
                }) => {
                    i.set_display(
                        at,
                        DisplayDef::Text {
                            text: text.0,
                            billboard,
                            alignment,
                            background,
                            line_width,
                            see_through,
                            shadow,
                            anchor,
                        },
                    );
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

    // ---- not ported yet ----
    fn add_child(
        &self,
        this: &handles::Node,
        name: String,
        definition: Value,
    ) -> ApiResult<Option<handles::Node>> {
        NodeApi::add_child(&Unported, this, name, definition)
    }
    fn remove(&self, this: &handles::Node) -> ApiResult<bool> {
        NodeApi::remove(&Unported, this)
    }
    fn stretch_between(
        &self,
        this: &handles::Node,
        from: values::Vec3,
        to: values::Vec3,
        options: Option<shapes::StretchOptions>,
    ) -> ApiResult<bool> {
        NodeApi::stretch_between(&Unported, this, from, to, options)
    }
    fn is_rope(&self, this: &handles::Node) -> ApiResult<bool> {
        NodeApi::is_rope(&Unported, this)
    }
    fn rope_between(
        &self,
        this: &handles::Node,
        from: unions::Vec3OrNodeOrEntity,
        to: unions::Vec3OrNodeOrEntity,
        options: Option<shapes::RopeOptions>,
    ) -> ApiResult<bool> {
        NodeApi::rope_between(&Unported, this, from, to, options)
    }
    fn release_rope(&self, this: &handles::Node) -> ApiResult<()> {
        NodeApi::release_rope(&Unported, this)
    }
    fn tween(&self, this: &handles::Node, options: shapes::TweenOptions) -> ApiResult<bool> {
        NodeApi::tween(&Unported, this, options)
    }
    fn is_tweening(&self, this: &handles::Node) -> ApiResult<bool> {
        NodeApi::is_tweening(&Unported, this)
    }
    fn stop_tween(&self, this: &handles::Node) -> ApiResult<bool> {
        NodeApi::stop_tween(&Unported, this)
    }
    fn world_rotation(&self, this: &handles::Node) -> ApiResult<Option<values::Vec3>> {
        NodeApi::world_rotation(&Unported, this)
    }
    fn world_direction(
        &self,
        this: &handles::Node,
        direction: values::Vec3,
    ) -> ApiResult<Option<values::Vec3>> {
        NodeApi::world_direction(&Unported, this, direction)
    }
    fn look_at(&self, this: &handles::Node, point: values::Vec3) -> ApiResult<bool> {
        NodeApi::look_at(&Unported, this, point)
    }
    fn rotate(&self, this: &handles::Node, axis: values::Vec3, degrees: f64) -> ApiResult<bool> {
        NodeApi::rotate(&Unported, this, axis, degrees)
    }
    fn is_clickable(&self, this: &handles::Node) -> ApiResult<bool> {
        NodeApi::is_clickable(&Unported, this)
    }
    fn set_clickable(&self, this: &handles::Node, clickable: bool) -> ApiResult<()> {
        NodeApi::set_clickable(&Unported, this, clickable)
    }
    fn image(&self, this: &handles::Node) -> ApiResult<Option<handles::Image>> {
        NodeApi::image(&Unported, this)
    }
    fn set_image(&self, this: &handles::Node, image: Option<handles::Image>) -> ApiResult<bool> {
        NodeApi::set_image(&Unported, this, image)
    }
    fn display_size(&self, this: &handles::Node) -> ApiResult<Option<values::Vec3>> {
        NodeApi::display_size(&Unported, this)
    }
    fn set_display_size(&self, this: &handles::Node, size: values::Vec3) -> ApiResult<bool> {
        NodeApi::set_display_size(&Unported, this, size)
    }
    fn display_anchor(&self, this: &handles::Node) -> ApiResult<Option<String>> {
        NodeApi::display_anchor(&Unported, this)
    }
    fn set_display_anchor(&self, this: &handles::Node, anchor: Option<String>) -> ApiResult<bool> {
        NodeApi::set_display_anchor(&Unported, this, anchor)
    }
    fn set_display_block(&self, this: &handles::Node, block: String) -> ApiResult<bool> {
        NodeApi::set_display_block(&Unported, this, block)
    }
    fn display_item(&self, this: &handles::Node) -> ApiResult<Option<values::Item>> {
        NodeApi::display_item(&Unported, this)
    }
    fn set_display_item(&self, this: &handles::Node, item: values::Item) -> ApiResult<bool> {
        NodeApi::set_display_item(&Unported, this, item)
    }
    fn model(&self, this: &handles::Node) -> ApiResult<Option<String>> {
        NodeApi::model(&Unported, this)
    }
    fn set_model(&self, this: &handles::Node, model: String) -> ApiResult<bool> {
        NodeApi::set_model(&Unported, this, model)
    }
    fn display_text_style(&self, this: &handles::Node) -> ApiResult<Option<shapes::TextStyle>> {
        NodeApi::display_text_style(&Unported, this)
    }
    fn set_display_text_style(
        &self,
        this: &handles::Node,
        style: shapes::TextStyle,
    ) -> ApiResult<bool> {
        NodeApi::set_display_text_style(&Unported, this, style)
    }
    fn is_glowing(&self, this: &handles::Node) -> ApiResult<bool> {
        NodeApi::is_glowing(&Unported, this)
    }
    fn set_glowing(&self, this: &handles::Node, glowing: bool) -> ApiResult<bool> {
        NodeApi::set_glowing(&Unported, this, glowing)
    }
    fn glow_color(&self, this: &handles::Node) -> ApiResult<Option<String>> {
        NodeApi::glow_color(&Unported, this)
    }
    fn set_glow_color(&self, this: &handles::Node, color: Option<String>) -> ApiResult<bool> {
        NodeApi::set_glow_color(&Unported, this, color)
    }
    fn brightness(&self, this: &handles::Node) -> ApiResult<Option<shapes::Brightness>> {
        NodeApi::brightness(&Unported, this)
    }
    fn set_brightness(
        &self,
        this: &handles::Node,
        brightness: Option<shapes::Brightness>,
    ) -> ApiResult<bool> {
        NodeApi::set_brightness(&Unported, this, brightness)
    }
    fn velocity(&self, this: &handles::Node) -> ApiResult<Option<values::Vec3>> {
        NodeApi::velocity(&Unported, this)
    }
    fn set_velocity(&self, this: &handles::Node, velocity: values::Vec3) -> ApiResult<bool> {
        NodeApi::set_velocity(&Unported, this, velocity)
    }
    fn add_velocity(&self, this: &handles::Node, velocity: values::Vec3) -> ApiResult<bool> {
        NodeApi::add_velocity(&Unported, this, velocity)
    }
    fn angular_velocity(&self, this: &handles::Node) -> ApiResult<Option<values::Vec3>> {
        NodeApi::angular_velocity(&Unported, this)
    }
    fn set_angular_velocity(
        &self,
        this: &handles::Node,
        angular_velocity: values::Vec3,
    ) -> ApiResult<bool> {
        NodeApi::set_angular_velocity(&Unported, this, angular_velocity)
    }
    fn mass(&self, this: &handles::Node) -> ApiResult<f64> {
        NodeApi::mass(&Unported, this)
    }
    fn apply_impulse(&self, this: &handles::Node, impulse: values::Vec3) -> ApiResult<bool> {
        NodeApi::apply_impulse(&Unported, this, impulse)
    }
    fn apply_impulse_at(
        &self,
        this: &handles::Node,
        impulse: values::Vec3,
        point: values::Vec3,
    ) -> ApiResult<bool> {
        NodeApi::apply_impulse_at(&Unported, this, impulse, point)
    }
    fn is_on_ground(&self, this: &handles::Node) -> ApiResult<bool> {
        NodeApi::is_on_ground(&Unported, this)
    }
    fn is_asleep(&self, this: &handles::Node) -> ApiResult<bool> {
        NodeApi::is_asleep(&Unported, this)
    }
    fn wake(&self, this: &handles::Node) -> ApiResult<()> {
        NodeApi::wake(&Unported, this)
    }
    fn apply_force(&self, this: &handles::Node, force: values::Vec3) -> ApiResult<bool> {
        NodeApi::apply_force(&Unported, this, force)
    }
    fn has_gravity(&self, this: &handles::Node) -> ApiResult<bool> {
        NodeApi::has_gravity(&Unported, this)
    }
    fn set_gravity(&self, this: &handles::Node, gravity: bool) -> ApiResult<bool> {
        NodeApi::set_gravity(&Unported, this, gravity)
    }
    fn is_kinematic(&self, this: &handles::Node) -> ApiResult<bool> {
        NodeApi::is_kinematic(&Unported, this)
    }
    fn set_kinematic(&self, this: &handles::Node, kinematic: bool) -> ApiResult<bool> {
        NodeApi::set_kinematic(&Unported, this, kinematic)
    }
    fn is_physics_enabled(&self, this: &handles::Node) -> ApiResult<bool> {
        NodeApi::is_physics_enabled(&Unported, this)
    }
    fn set_physics_enabled(&self, this: &handles::Node, enabled: bool) -> ApiResult<bool> {
        NodeApi::set_physics_enabled(&Unported, this, enabled)
    }
}
