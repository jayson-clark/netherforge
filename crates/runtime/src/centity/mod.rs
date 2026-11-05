//! Centities on the Rust runtime (the Kotlin runtime's `Centities`, as far as it's ported): each project
//! centity's compiled definition, the instances in the world, and keeping their entities in line with them.
//!
//! An instance is drawn by entities the Platform makes: an invisible **anchor** at the instance's anchor,
//! which every display rides with its offset in its transform (so moving the centity is one teleport), and a
//! display per node that has one. `sync` brings them in line with the instance each tick, writing only what
//! changed; `reconcile` makes them match its definition (after a reload, or when one went missing).
//!
//! `api` is the centity API scripts call and an instance's life around its script (`spawn`, `remove`, `tick`).
//!
//! Not yet (each with its area): hitboxes and clicks, saving placements,
//! private instances (client entities), animations, physics and movement, health, natural spawning, ropes.

pub mod animation;
pub mod api;
pub mod drawing;
pub mod instance;
pub mod keyframes;

use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};
use std::rc::Rc;

use indexmap::IndexMap;
use netherforge_format::kind::centity::DisplayDef;
use netherforge_format::kind::centity::compiled::CompiledCentity;
use netherforge_format::math::Matrix4;
use netherforge_platform::{
    DisplayLook, DisplayPose, EntityOps, EntityRole, EntityTag, Location, Platform, Uuid,
};

pub use instance::Instance;

/// The node an anchor entity's tag names: none.
pub const ANCHOR_NODE: &str = "";

/// The instances of a session's centities, and their definitions.
#[derive(Default)]
pub struct Centities {
    definitions: BTreeMap<String, Rc<CompiledCentity>>,
    instances: IndexMap<Uuid, Instance>,
    /// Each entity of ours, by its instance and node.
    owners: HashMap<Uuid, (Uuid, String)>,
    /// The next instance's id, for a platform that doesn't make them (tests); see `spawn`.
    next_id: u128,
    /// Anchored texts already warned about, so one set every tick warns once.
    unmeasured: HashSet<String>,
}

/// A whole centity's drawing context: measuring text, and saying what can't be drawn.
pub struct Drawing<'a> {
    pub text_size: &'a dyn Fn(&str) -> Option<(i32, i32)>,
    /// Says what can't be drawn as asked: each display's once.
    pub warn: &'a dyn Fn(String),
}

impl Centities {
    /// Takes `definitions`, the project's compiled centities by the name the server knows them by.
    pub fn define(&mut self, definitions: BTreeMap<String, Rc<CompiledCentity>>) {
        self.definitions = definitions;
    }

    pub fn definition(&self, id: &str) -> Option<&Rc<CompiledCentity>> {
        self.definitions.get(id)
    }

    pub fn definition_ids(&self) -> Vec<String> {
        self.definitions.keys().cloned().collect()
    }

    pub fn get(&self, id: Uuid) -> Option<&Instance> {
        self.instances.get(&id)
    }

    pub fn get_mut(&mut self, id: Uuid) -> Option<&mut Instance> {
        self.instances.get_mut(&id)
    }

    pub fn all(&self) -> impl Iterator<Item = &Instance> {
        self.instances.values()
    }

    /// Which instance, and which of its nodes, `entity` is part of.
    pub fn owner_of(&self, entity: Uuid) -> Option<&(Uuid, String)> {
        self.owners.get(&entity)
    }

    /// Puts a new instance of `centity` into the world at `at`, facing `yaw` (the location's own facing is
    /// ignored), its entities made. None when there's no such centity or no such world.
    pub fn spawn(
        &mut self,
        platform: &dyn Platform,
        drawing: &Drawing,
        centity: &str,
        at: Location,
        yaw: f64,
        id: Option<Uuid>,
    ) -> Option<Uuid> {
        let definition = self.definitions.get(centity)?.clone();
        if !platform.worlds().exists(at.world.clone()) {
            return None;
        }
        let id = id.unwrap_or_else(|| {
            self.next_id += 1;
            Uuid(self.next_id)
        });
        let mut instance = Instance::new(id, definition, at, yaw);
        autoplay(&mut instance);
        self.instances.insert(id, instance);
        self.sync(platform, drawing, id);
        Some(id)
    }

    /// Takes an instance out of the world for good: its entities go.
    pub fn remove(&mut self, platform: &dyn Platform, id: Uuid) -> bool {
        let Some(mut instance) = self.instances.shift_remove(&id) else {
            return false;
        };
        instance.removed = true;
        for entity in instance.entity_ids() {
            self.owners.remove(&entity);
            // An entity in an unloaded chunk is cleaned up as a stray when it loads.
            platform.entities().remove(entity);
        }
        true
    }

    /// Every instance out of the world.
    pub fn remove_all(&mut self, platform: &dyn Platform) {
        let ids: Vec<Uuid> = self.instances.keys().copied().collect();
        for id in ids {
            self.remove(platform, id);
        }
    }

    /// Moves every instance of the centities in `ids` onto their new definitions (an instance of one that's
    /// gone is removed). How many instances moved.
    pub fn reload(
        &mut self,
        platform: &dyn Platform,
        drawing: &Drawing,
        ids: &BTreeSet<String>,
    ) -> usize {
        let mut moved = 0;
        let affected: Vec<Uuid> = self
            .instances
            .values()
            .filter(|i| ids.contains(i.centity()))
            .map(|i| i.id)
            .collect();
        for id in affected {
            let centity = self.instances[&id].centity().to_owned();
            match self.definitions.get(&centity).cloned() {
                Some(next) => {
                    if let Some(instance) = self.instances.get_mut(&id) {
                        instance.redefine(next);
                        autoplay(instance);
                        moved += 1;
                    }
                    self.sync(platform, drawing, id);
                }
                None => {
                    self.remove(platform, id);
                }
            }
        }
        moved
    }

    /// One tick: every loaded instance synced.
    pub fn tick(&mut self, platform: &dyn Platform, drawing: &Drawing) {
        let ids: Vec<Uuid> = self.instances.keys().copied().collect();
        for id in ids {
            if let Some(instance) = self.instances.get_mut(&id) {
                instance.age += 1;
            }
            self.animate(id);
            self.sync(platform, drawing, id);
        }
    }

    /// Moves instance `id`'s clips on a tick and poses it: the clips that reached their end.
    pub fn animate(&mut self, id: Uuid) -> Vec<String> {
        let Some(instance) = self.instances.get_mut(&id) else {
            return Vec::new();
        };
        if !instance.animations.active() {
            return Vec::new();
        }
        let ended = instance.animations.advance(instance::SECONDS_PER_TICK);
        instance.animate();
        ended
    }

    fn loaded(platform: &dyn Platform, instance: &Instance) -> bool {
        (instance.anchor_entity.is_none() && instance.displays.is_empty())
            || platform.worlds().entities_loaded(instance.anchor().clone())
    }

    fn drawn(&mut self, drawing: &Drawing, display: &DisplayDef) -> drawing::Drawn {
        let mut warnings = Vec::new();
        let drawn = drawing::drawn(display, drawing.text_size, &mut |w| warnings.push(w));
        for warning in warnings {
            // One set every tick warns once.
            if self.unmeasured.insert(format!("{display:?}")) {
                (drawing.warn)(warning);
            }
        }
        drawn
    }

    /// Brings instance `id`'s entities in line with its state, writing only what changed.
    pub fn sync(&mut self, platform: &dyn Platform, drawing: &Drawing, id: Uuid) {
        let Some(instance) = self.instances.get(&id) else {
            return;
        };
        if instance.removed || !Self::loaded(platform, instance) {
            return;
        }
        self.drop_gone(platform, id);
        if self.instances[&id].needs_reconcile {
            self.reconcile(platform, drawing, id);
        }
        self.sweep_orphans(platform, id);
        let instance = &self.instances[&id];
        if !instance.pose_dirty() && !instance.force_sync && !instance.moved {
            return;
        }
        let entities = platform.entities();
        let vehicle = self.sync_anchor(platform, id);
        let mut needs_reconcile = false;
        let nodes: Vec<(usize, String)> = self.instances[&id]
            .definition
            .nodes
            .iter()
            .enumerate()
            .map(|(i, n)| (i, n.name.clone()))
            .collect();
        let world: Vec<Matrix4> = self
            .instances
            .get_mut(&id)
            .expect("listed")
            .placed()
            .to_vec();
        let cull = cull_size(&world);
        let mut ahead = Lookahead::default();
        for (index, name) in nodes {
            let Some(entity) = self.instances[&id].displays.get(&name).copied() else {
                continue;
            };
            let display = self.instances[&id].display(index).cloned();
            let content_changed = self.instances[&id].content_changed(index);
            let drawn = display.as_ref().map(|d| self.drawn(drawing, d));
            let instance = self.instances.get_mut(&id).expect("listed");
            if content_changed
                && let Some(drawn) = &drawn
                && !entities.update_display(entity, drawn.shown.clone())
            {
                needs_reconcile = true;
            }
            // After the content, which a look overrides, and before a teleport it eases.
            let look = instance.shown_look(index);
            if instance.force_sync
                || content_changed
                || instance.pushed_looks.get(&entity) != Some(&look)
            {
                entities.set_look(entity, look.clone());
                instance.pushed_looks.insert(entity, look);
            }
            if instance.billboarded(index) {
                // A billboard stands on its own at its node: riding, it could only be drawn at the anchor.
                if instance.riders.remove(&entity) {
                    entities.dismount(entity);
                }
                let spot = stands_at(instance, index, &world);
                if instance.force_sync || instance.pushed_spots.get(&entity) != Some(&spot) {
                    entities.teleport(entity, spot.clone());
                    instance.pushed_spots.insert(entity, spot);
                }
            } else {
                // Back on the anchor entity when it stops being a billboard.
                let was_standing = instance.pushed_spots.remove(&entity).is_some();
                if (instance.force_sync || was_standing)
                    && let Some(vehicle) = vehicle
                {
                    ride(entities, instance, vehicle, entity);
                }
                // One that couldn't get on the anchor entity is moved on its own.
                if (instance.moved || instance.force_sync || was_standing)
                    && !instance.riders.contains(&entity)
                {
                    entities.teleport(entity, instance.anchor().clone());
                }
            }
            let shown = instance.shown(index);
            let matrix = if shown {
                world[index]
            } else {
                collapsed(&world[index])
            };
            sync_pose(
                entities,
                instance,
                Pose {
                    index,
                    entity,
                    matrix,
                    shown,
                    cull,
                },
                drawn.as_ref(),
                &mut ahead,
            );
        }
        let instance = self.instances.get_mut(&id).expect("listed");
        instance.mark_synced();
        if needs_reconcile {
            instance.needs_reconcile = true;
            // A display that turned out to be the wrong kind is respawned on the next pass.
            self.sync(platform, drawing, id);
        }
    }

    /// The anchor entity, moved and eased as the instance says: what its riders follow.
    fn sync_anchor(&mut self, platform: &dyn Platform, id: Uuid) -> Option<Uuid> {
        let instance = self.instances.get_mut(&id)?;
        let vehicle = instance.anchor_entity?;
        let look = DisplayLook {
            view_range: instance.view_range() / instance::DEFAULT_VIEW_RANGE,
            teleport_ticks: instance.move_ticks(),
            ..instance::default_look()
        };
        let entities = platform.entities();
        if instance.force_sync || instance.pushed_looks.get(&vehicle) != Some(&look) {
            entities.set_look(vehicle, look.clone());
            instance.pushed_looks.insert(vehicle, look);
        }
        if instance.moved {
            entities.teleport(vehicle, instance.anchor().clone());
        }
        Some(vehicle)
    }

    /// Makes the entities match the definition: removes ones whose node is gone, updates every display's
    /// content (respawning one whose kind changed), spawns what's missing.
    fn reconcile(&mut self, platform: &dyn Platform, drawing: &Drawing, id: Uuid) {
        let entities = platform.entities();
        let stale: Vec<(String, Uuid)> = {
            let instance = &self.instances[&id];
            instance
                .displays
                .iter()
                .filter(|(name, _)| match instance.index_of(name) {
                    Some(index) => instance.display(index).is_none(),
                    None => true,
                })
                .map(|(n, e)| (n.clone(), *e))
                .collect()
        };
        for (name, entity) in stale {
            self.instances
                .get_mut(&id)
                .expect("listed")
                .displays
                .shift_remove(&name);
            self.retire(platform, id, entity);
        }
        let instance = self.instances.get_mut(&id).expect("listed");
        let world: Vec<Matrix4> = instance.placed().to_vec();
        let cull = cull_size(&world);
        let anchor = instance.anchor().clone();
        if instance.anchor_entity.is_none()
            && let Some(entity) = entities.spawn_anchor(
                anchor.clone(),
                EntityTag {
                    instance: id,
                    node: ANCHOR_NODE.into(),
                    role: EntityRole::Anchor,
                },
            )
        {
            instance.anchor_entity = Some(entity);
            self.owners.insert(entity, (id, ANCHOR_NODE.into()));
            self.show_new(platform, id, entity);
        }
        let nodes: Vec<(usize, String)> = self.instances[&id]
            .definition
            .nodes
            .iter()
            .enumerate()
            .map(|(i, n)| (i, n.name.clone()))
            .collect();
        for (index, name) in nodes {
            let Some(display) = self.instances[&id].display(index).cloned() else {
                continue;
            };
            let drawn = self.drawn(drawing, &display);
            let existing = self.instances[&id].displays.get(&name).copied();
            if let Some(existing) = existing
                && !entities.update_display(existing, drawn.shown.clone())
            {
                self.instances
                    .get_mut(&id)
                    .expect("listed")
                    .displays
                    .shift_remove(&name);
                self.retire(platform, id, existing);
            }
            if self.instances[&id].displays.contains_key(&name) {
                continue;
            }
            let instance = &self.instances[&id];
            let shown = if instance.shown(index) {
                world[index]
            } else {
                collapsed(&world[index])
            };
            let matrix = if instance.billboarded(index) {
                billboard_pose(&shown)
            } else {
                shown
            };
            let spawned = entities.spawn_display(
                stands_at(instance, index, &world),
                drawn.shown.clone(),
                DisplayPose {
                    matrix: fitted(&drawn, &display, matrix),
                    interpolation_ticks: 0,
                    cull_size: cull,
                },
                EntityTag {
                    instance: id,
                    node: name.clone(),
                    role: EntityRole::Display,
                },
            );
            if let Some(entity) = spawned {
                self.instances
                    .get_mut(&id)
                    .expect("listed")
                    .displays
                    .insert(name.clone(), entity);
                self.owners.insert(entity, (id, name));
                self.show_new(platform, id, entity);
            }
        }
        let instance = self.instances.get_mut(&id).expect("listed");
        instance.needs_reconcile = false;
        instance.force_sync = true;
    }

    /// Who sees a new entity of an instance: everyone it isn't hidden from.
    fn show_new(&self, platform: &dyn Platform, id: Uuid, entity: Uuid) {
        for player in &self.instances[&id].hidden_from {
            platform.entities().set_hidden(*player, entity, true);
        }
    }

    /// Hides instance `id` from `player` (every entity of it), or shows it again.
    pub fn set_hidden(&mut self, platform: &dyn Platform, id: Uuid, player: Uuid, hidden: bool) {
        let Some(instance) = self.instances.get_mut(&id) else {
            return;
        };
        let changed = if hidden {
            instance.hidden_from.insert(player)
        } else {
            instance.hidden_from.shift_remove(&player)
        };
        if !changed {
            return;
        }
        for entity in instance.shown_entities() {
            platform.entities().set_hidden(player, entity, hidden);
        }
    }

    /// A player joined: everything hidden from them is hidden again, since the server forgot.
    pub fn player_joined(&self, platform: &dyn Platform, player: Uuid) {
        for instance in self.instances.values() {
            if !instance.hidden_from.contains(&player) {
                continue;
            }
            for entity in instance.shown_entities() {
                platform.entities().set_hidden(player, entity, true);
            }
        }
    }

    fn retire(&mut self, platform: &dyn Platform, id: Uuid, entity: Uuid) {
        self.forget(id, entity);
        if !platform.entities().remove(entity)
            && let Some(instance) = self.instances.get_mut(&id)
        {
            instance.orphans.push(entity);
        }
    }

    /// Forgets entities that can't be reached although their chunk's entities are loaded: something else
    /// removed them. `reconcile` spawns them again.
    fn drop_gone(&mut self, platform: &dyn Platform, id: Uuid) {
        let entities = platform.entities();
        let instance = &self.instances[&id];
        let anchor = instance.anchor().clone();
        let gone = |entity: Uuid, at: &Location| {
            !entities.is_loaded(entity) && platform.worlds().entities_loaded(at.clone())
        };
        let gone_displays: Vec<(String, Uuid)> = instance
            .displays
            .iter()
            .filter(|(_, e)| gone(**e, instance.pushed_spots.get(e).unwrap_or(&anchor)))
            .map(|(n, e)| (n.clone(), *e))
            .collect();
        let gone_anchor = instance.anchor_entity.filter(|e| gone(*e, &anchor));
        if gone_displays.is_empty() && gone_anchor.is_none() {
            return;
        }
        if let Some(entity) = gone_anchor {
            // Its riders are left standing where they were: the new one picks them up.
            let instance = self.instances.get_mut(&id).expect("listed");
            instance.anchor_entity = None;
            instance.riders.clear();
            self.forget(id, entity);
        }
        for (name, entity) in gone_displays {
            self.instances
                .get_mut(&id)
                .expect("listed")
                .displays
                .shift_remove(&name);
            self.forget(id, entity);
        }
        self.instances.get_mut(&id).expect("listed").needs_reconcile = true;
    }

    fn forget(&mut self, id: Uuid, entity: Uuid) {
        self.owners.remove(&entity);
        if let Some(instance) = self.instances.get_mut(&id) {
            instance.riders.remove(&entity);
            instance.pushed_poses.remove(&entity);
            instance.pushed_looks.remove(&entity);
            instance.pushed_spots.remove(&entity);
        }
    }

    /// Orphans are tried once the instance is loaded, then forgotten.
    fn sweep_orphans(&mut self, platform: &dyn Platform, id: Uuid) {
        let Some(instance) = self.instances.get_mut(&id) else {
            return;
        };
        for entity in instance.orphans.drain(..) {
            platform.entities().remove(entity);
        }
    }
}

fn ride(entities: &dyn EntityOps, instance: &mut Instance, vehicle: Uuid, entity: Uuid) {
    if entities.mount(vehicle, entity) {
        instance.riders.insert(entity);
    } else {
        instance.riders.remove(&entity);
    }
}

/// Shows node `index`'s display at `matrix` (its pose now, collapsed when hidden), eased as the node says,
/// unless it was last shown just so.
/// Starts the clips its file says play by themselves.
fn autoplay(instance: &mut Instance) {
    let definition = instance.definition.clone();
    for (index, clip) in definition.animations.iter().enumerate() {
        if clip.autoplay {
            instance
                .animations
                .play(&definition, index, animation::Play::default(), None);
        }
    }
    instance.animate();
}

/// Which display `sync_pose` shows, and where: its node's matrix now (collapsed when hidden).
struct Pose {
    index: usize,
    entity: Uuid,
    matrix: Matrix4,
    shown: bool,
    cull: f64,
}

/// `Instance::placed_ahead` for 1, 2, … ticks on, each worked out once, the first time a display asks.
#[derive(Default)]
struct Lookahead {
    ticks: Vec<Vec<Matrix4>>,
}

impl Lookahead {
    fn at(&mut self, instance: &Instance, tick: i32) -> &[Matrix4] {
        while (self.ticks.len() as i32) < tick {
            let next = instance.placed_ahead(self.ticks.len() as i32 + 1);
            self.ticks.push(next);
        }
        &self.ticks[tick as usize - 1]
    }
}

/// Shows a node's display at its matrix. While only clips move it, the pose is sent ahead instead: as far
/// as the client's easing follows the clips (`keyframes::reach`), and then nothing until it gets there.
/// Anything else (a script, a new clip, the first sync) sends the pose now, eased as the node says.
fn sync_pose(
    entities: &dyn EntityOps,
    instance: &mut Instance,
    pose: Pose,
    drawn: Option<&drawing::Drawn>,
    ahead: &mut Lookahead,
) {
    let Pose {
        index,
        entity,
        matrix,
        shown,
        cull,
    } = pose;
    let billboarded = instance.billboarded(index);
    let planned = !instance.force_sync
        && !billboarded
        && shown
        && instance.animations.active()
        && instance.interpolation(index) > 0
        && !instance.edited(index);
    let (target, ticks) = if planned {
        // Still on its way to what it was sent.
        if instance.age < instance.due[index] {
            return;
        }
        let reach = keyframes::reach(&matrix, keyframes::MAX_TICKS, &mut |k| {
            ahead.at(instance, k)[index]
        });
        instance.due[index] = instance.age + reach as u64;
        // The client shows the pose now; it eases on to the pose that many ticks on.
        (ahead.at(instance, reach)[index], reach)
    } else {
        instance.due[index] = instance.age;
        let ticks = if instance.force_sync || instance.jumps(index) {
            0
        } else {
            instance.interpolation(index)
        };
        let target = if billboarded {
            billboard_pose(&matrix)
        } else {
            matrix
        };
        (target, ticks)
    };
    let shown = match (drawn, instance.display(index)) {
        (Some(drawn), Some(display)) => fitted(drawn, display, target),
        _ => target,
    };
    let pushed = instance::PushedPose {
        matrix: shown,
        cull_size: cull,
    };
    if instance.force_sync || instance.pushed_poses.get(&entity) != Some(&pushed) {
        entities.set_pose(
            entity,
            DisplayPose {
                matrix: shown,
                interpolation_ticks: ticks,
                cull_size: cull,
            },
        );
        instance.pushed_poses.insert(entity, pushed);
    }
}

/// What a display is drawn at, given its node's `matrix` in the world. A model look is authored nose on +Z,
/// but the client draws it turned a half turn about its own Y, so it's turned back here to put its nose where
/// the node's +Z points. A drawn display (a rect, an image, anchored text) is fitted to its box.
fn fitted(drawn: &drawing::Drawn, display: &DisplayDef, matrix: Matrix4) -> Matrix4 {
    let mut out = matrix;
    if matches!(display, DisplayDef::Model { .. }) {
        out.rotate_y(std::f64::consts::PI);
        return out;
    }
    match &drawn.fit {
        Some(fit) => matrix.mul(fit),
        None => out,
    }
}

/// Where node `index`'s display entity stands: at the anchor, its offset in its matrix; a billboard at its
/// node, since the client turns a billboard to the camera before its transformation.
fn stands_at(instance: &Instance, index: usize, world: &[Matrix4]) -> Location {
    let anchor = instance.anchor().clone();
    if !instance.billboarded(index) {
        return anchor;
    }
    let at = world[index].translation();
    Location {
        x: anchor.x + at.0,
        y: anchor.y + at.1,
        z: anchor.z + at.2,
        ..anchor
    }
}

/// A billboard's matrix: its node's scale only. Its place is the entity's, and the client chooses its turn.
fn billboard_pose(matrix: &Matrix4) -> Matrix4 {
    let scale = matrix.scale_of();
    let mut m = Matrix4::IDENTITY;
    m.scale(scale.0, scale.1, scale.2);
    m
}

/// A hidden node's matrix: where it is, at no size.
fn collapsed(matrix: &Matrix4) -> Matrix4 {
    let at = matrix.translation();
    let mut m = Matrix4::IDENTITY;
    m.translate(at.0, at.1, at.2).scale(0.0, 0.0, 0.0);
    m
}

/// Twice the furthest node offset, rounded up so a moving centity doesn't resend it every tick.
fn cull_size(world: &[Matrix4]) -> f64 {
    let mut furthest: f64 = 1.0;
    for matrix in world {
        let t = matrix.translation();
        furthest = furthest.max((t.0 * t.0 + t.1 * t.1 + t.2 * t.2).sqrt());
    }
    (furthest + 2.0).ceil() * 2.0
}
