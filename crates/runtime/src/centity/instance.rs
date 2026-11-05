//! One spawned centity (Kotlin's `Instance`): which definition it runs, where it stands, the entities it
//! owns, and what a script can change about it.
//!
//! Per-node state is held in vectors aligned with the definition's node order (parents first), and rebuilt
//! by `redefine` when the definition is reloaded. Entities are held by node *name*, because names are what
//! survive a reload: a node that's still there keeps its entities, and the reconcile pass works out what's
//! missing or stale. Local transforms are the source of truth, not matrices, so a script that writes a
//! rotation reads the same numbers back.
//!
//! Not yet (each with its area): animations and tweens, bodies and physics, health, walks, flights,
//! hitboxes' shapes, nodes scripts add.

use std::collections::{HashMap, HashSet};
use std::rc::Rc;

use indexmap::{IndexMap, IndexSet};
use netherforge_format::kind::centity::compiled::{CompiledCentity, Node};
use netherforge_format::kind::centity::{Billboard, DEFAULT_BILLBOARD, DisplayDef, Transform};
use netherforge_format::math::{Matrix4, Vec3};
use netherforge_luau::ScopeId;
use netherforge_platform::{DisplayLook, Location, Uuid};

use super::animation::AnimationPlayer;

/// A tick, in seconds: what clips advance by.
pub const SECONDS_PER_TICK: f64 = 0.05;

/// Minecraft's default view range for a display, in blocks: a multiplier of 1.
pub const DEFAULT_VIEW_RANGE: f64 = 64.0;

/// Displays ease to each new pose over this many ticks unless a script says otherwise, which smooths 20 Hz
/// updates.
pub const DEFAULT_INTERPOLATION_TICKS: i32 = 1;

/// The longest teleport Minecraft eases a display over.
pub const MAX_TELEPORT_TICKS: i32 = 59;

/// `degrees` wrapped into [-180, 180), as Minecraft wraps a facing; anything not finite is 0.
/// A node's channel easing from where it was to where a script sent it (`node:tween`).
#[derive(Clone, Debug)]
pub struct Tween {
    pub index: usize,
    pub channel: Channel,
    pub from: Vec3,
    pub to: Vec3,
    pub ticks: i32,
    pub easing: netherforge_format::animation::Easing,
    /// Out, then home again.
    pub back: bool,
    pub tick: i32,
}

impl Tween {
    pub fn done(&self) -> bool {
        self.tick
            >= if self.back {
                self.ticks * 2
            } else {
                self.ticks
            }
    }

    /// Where it is at its tick: out, then (with `back`) home again.
    pub fn value(&self) -> Vec3 {
        let (tick, ticks) = (f64::from(self.tick), f64::from(self.ticks));
        let out = if self.tick <= self.ticks {
            tick / ticks
        } else {
            2.0 - tick / ticks
        };
        let t = self.easing.apply(out.clamp(0.0, 1.0));
        self.from + (self.to - self.from) * t
    }
}

/// One channel of a transform.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Channel {
    Translation,
    Rotation,
    Scale,
}

impl Channel {
    /// This channel of `transform`, its default where it sets none.
    pub fn of(self, transform: &Transform) -> Vec3 {
        match self {
            Channel::Translation => transform.translation.unwrap_or(Vec3(0.0, 0.0, 0.0)),
            Channel::Rotation => transform.rotation.unwrap_or(Vec3(0.0, 0.0, 0.0)),
            Channel::Scale => transform.scale.unwrap_or(Vec3(1.0, 1.0, 1.0)),
        }
    }

    fn put(self, transform: &mut Transform, value: Vec3) {
        let slot = match self {
            Channel::Translation => &mut transform.translation,
            Channel::Rotation => &mut transform.rotation,
            Channel::Scale => &mut transform.scale,
        };
        *slot = Some(value);
    }
}

pub fn wrap_degrees(degrees: f64) -> f64 {
    if !degrees.is_finite() {
        return 0.0;
    }
    (degrees + 180.0).rem_euclid(360.0) - 180.0 + 0.0
}

/// A display look at its defaults: what a fresh node has.
pub fn default_look() -> DisplayLook {
    DisplayLook {
        glowing: false,
        glow_color: None,
        brightness: None,
        billboard: None,
        view_range: 1.0,
        teleport_ticks: 0,
        text_opacity: None,
        item: None,
    }
}

/// What a display entity was last shown: its matrix and cull size.
#[derive(Clone, Debug, PartialEq)]
pub struct PushedPose {
    pub matrix: Matrix4,
    pub cull_size: f64,
}

pub struct Instance {
    pub id: Uuid,
    /// Its centity's definition as the file declares it: `definition` is this plus the nodes scripts added.
    pub declared: Rc<CompiledCentity>,
    /// What the centity's file says.
    pub definition: Rc<CompiledCentity>,
    /// Where every display stands: they ride the anchor entity there, their offsets in their transforms.
    /// Its own facing is always zero: the centity's facing is `yaw`, which turns the transforms instead.
    anchor: Location,
    /// Which way the centity faces, in degrees, Minecraft's convention (0 south, 90 west), in [-180, 180).
    yaw: f64,

    /// Node name → its display entity.
    pub displays: IndexMap<String, Uuid>,
    /// Node name → its hitbox's interaction entity.
    pub hitboxes: IndexMap<String, Uuid>,
    /// Each hitbox entity's box as last sent.
    pub pushed_hitboxes: HashMap<Uuid, super::hitboxes::Bounds>,
    /// The invisible entity at the anchor every display rides: moving the centity is one teleport of it.
    pub anchor_entity: Option<Uuid>,
    /// The entities known to ride the anchor entity now.
    pub riders: HashSet<Uuid>,
    /// Entities of ours we couldn't remove when they stopped being wanted: tried again when it's loaded.
    pub orphans: Vec<Uuid>,

    /// It appeared by itself, from its `spawning` rules: not saved, removed when nobody's near.
    pub natural: bool,
    /// A script spawned it temporary (`temporary` or `lifetime`): not saved, its entities not persistent,
    /// removed when `owner` (the scope that spawned it) goes or at `expires`. `keep()` ends it.
    pub temporary: bool,
    /// The scope a temporary instance goes with.
    pub owner: Option<ScopeId>,
    /// The server tick a temporary instance with a lifetime is removed at.
    pub expires: Option<u64>,
    /// The only players a private instance is shown to: its entities are client entities only their
    /// clients have. Set before anything is made; only a temporary instance is private.
    pub viewers: Option<IndexSet<Uuid>>,
    /// Its script's scope, while it runs.
    pub scope: Option<ScopeId>,
    /// Ticks it has been ticked.
    pub age: u64,
    pub removed: bool,
    /// Its `remove` handlers are running.
    pub removing: bool,
    /// How far away its displays are drawn, in blocks. Kept across reloads.
    view_range: f64,
    /// Players it's hidden from, online or not: what's said again when the server forgets.
    pub hidden_from: IndexSet<Uuid>,

    /// The entities no longer match the definition.
    pub needs_reconcile: bool,
    /// Everything must be pushed again: entities just came back from disk, or were just respawned.
    pub force_sync: bool,
    /// The anchor moved; every entity follows.
    pub moved: bool,

    rest: Vec<Transform>,
    pose: Vec<Transform>,
    hidden: Vec<bool>,
    content: Vec<Option<DisplayDef>>,
    content_changed: Vec<bool>,
    looks: Vec<DisplayLook>,
    interpolation: Vec<i32>,
    /// Nodes whose display jumps to its next pose rather than easing there.
    jumps: Vec<bool>,
    /// Nodes whose hitbox takes no clicks (`set_clickable(false)`).
    unclickable: Vec<bool>,
    /// The clips playing on it.
    pub animations: AnimationPlayer,
    /// The tweens easing its nodes, at most one per node and channel.
    pub tweens: Vec<Tween>,
    /// Its rope nodes strung between two ends, by node name: laid again each tick.
    pub ropes: HashMap<String, super::ropes::StrungRope>,
    /// Nodes something other than a clip changed since the last sync (a script, being shown or hidden):
    /// their pose is sent now rather than planned ahead.
    edited: Vec<bool>,
    /// Every node is, as for `edited` (it turned, or was just made or redefined).
    all_edited: bool,
    /// The clips' `version` at the last sync: a clip started, stopped or moved since means nothing planned
    /// holds.
    synced_clips: u64,
    /// The age at which each display arrives at the pose it was last sent ahead to.
    pub due: Vec<u64>,
    /// The pose changed since the last sync.
    pose_dirty: bool,
    /// `placed`, kept until the pose or facing changes.
    placed_cache: Option<Vec<Matrix4>>,

    pub pushed_poses: HashMap<Uuid, PushedPose>,
    pub pushed_looks: HashMap<Uuid, DisplayLook>,
    /// Where each billboarded display was last put: one stands on its own at its node.
    pub pushed_spots: HashMap<Uuid, Location>,
}

impl Instance {
    pub fn new(id: Uuid, definition: Rc<CompiledCentity>, anchor: Location, yaw: f64) -> Instance {
        let mut instance = Instance {
            id,
            declared: definition.clone(),
            definition: definition.clone(),
            anchor: Location {
                yaw: 0.0,
                pitch: 0.0,
                ..anchor
            },
            yaw: wrap_degrees(yaw),
            displays: IndexMap::new(),
            hitboxes: IndexMap::new(),
            pushed_hitboxes: HashMap::new(),
            anchor_entity: None,
            riders: HashSet::new(),
            orphans: Vec::new(),
            natural: false,
            temporary: false,
            owner: None,
            expires: None,
            viewers: None,
            scope: None,
            age: 0,
            removed: false,
            removing: false,
            view_range: DEFAULT_VIEW_RANGE,
            hidden_from: IndexSet::new(),
            needs_reconcile: true,
            force_sync: true,
            moved: false,
            rest: Vec::new(),
            pose: Vec::new(),
            hidden: Vec::new(),
            content: Vec::new(),
            content_changed: Vec::new(),
            looks: Vec::new(),
            interpolation: Vec::new(),
            jumps: Vec::new(),
            unclickable: Vec::new(),
            animations: AnimationPlayer::default(),
            tweens: Vec::new(),
            ropes: HashMap::new(),
            edited: Vec::new(),
            all_edited: true,
            synced_clips: 0,
            due: Vec::new(),
            pose_dirty: true,
            placed_cache: None,
            pushed_poses: HashMap::new(),
            pushed_looks: HashMap::new(),
            pushed_spots: HashMap::new(),
        };
        instance.redefine(definition);
        instance
    }

    /// Never saved, its entities never persistent.
    pub fn transient(&self) -> bool {
        self.natural || self.temporary
    }

    /// Whether `player` sees it: one of a private instance's viewers, or anyone it isn't hidden from.
    pub fn sees(&self, player: Uuid) -> bool {
        match &self.viewers {
            Some(viewers) => viewers.contains(&player),
            None => !self.hidden_from.contains(&player),
        }
    }

    pub fn centity(&self) -> &str {
        &self.definition.id
    }

    /// Moves onto a new definition: per-node state starts again from what the file says. Entities are kept by
    /// node name and fixed up by the next reconcile.
    pub fn redefine(&mut self, next: Rc<CompiledCentity>) {
        let n = next.nodes.len();
        self.rest = next
            .nodes
            .iter()
            .map(|node| node.transform.clone())
            .collect();
        self.pose = self.rest.clone();
        self.hidden = vec![false; n];
        self.content = next.nodes.iter().map(|node| node.display.clone()).collect();
        self.content_changed = vec![false; n];
        self.looks = vec![default_look(); n];
        self.interpolation = vec![DEFAULT_INTERPOLATION_TICKS; n];
        self.jumps = vec![false; n];
        self.edited = vec![false; n];
        self.unclickable = vec![false; n];
        self.due = vec![0; n];
        self.all_edited = true;
        self.animations.stop_all();
        self.tweens.clear();
        self.ropes.clear();
        self.declared = next.clone();
        self.definition = next;
        self.needs_reconcile = true;
        self.force_sync = true;
        self.posed();
    }

    fn posed(&mut self) {
        self.pose_dirty = true;
        self.placed_cache = None;
    }

    pub fn index_of(&self, node: &str) -> Option<usize> {
        self.definition.index_of(node)
    }

    /// Whether node `index` was added by a script rather than declared by the file.
    pub fn is_added(&self, index: usize) -> bool {
        index >= self.declared.nodes.len()
    }

    /// Adds `node` (its parent, if any, already there) after every other node: its index.
    pub fn add_node(&mut self, node: Node) -> usize {
        let mut nodes = self.definition.nodes.clone();
        nodes.push(node);
        self.reshape(nodes);
        self.definition.nodes.len() - 1
    }

    /// Removes the added node `index` and every node below it: their names. Everything else keeps its
    /// state, moved to its new index.
    pub fn remove_node(&mut self, index: usize) -> Vec<String> {
        let nodes = &self.definition.nodes;
        let mut doomed = vec![false; nodes.len()];
        // Parents come first, so one pass finds every descendant.
        for (at, node) in nodes.iter().enumerate() {
            doomed[at] =
                at == index || (node.parent_index >= 0 && doomed[node.parent_index as usize]);
        }
        let mut renumber = vec![-1i32; nodes.len()];
        let mut kept = 0;
        for at in 0..nodes.len() {
            if !doomed[at] {
                renumber[at] = kept;
                kept += 1;
            }
        }
        let names: Vec<String> = (0..nodes.len())
            .filter(|at| doomed[*at])
            .map(|at| nodes[at].name.clone())
            .collect();
        let next: Vec<Node> = nodes
            .iter()
            .enumerate()
            .filter(|(at, _)| !doomed[*at])
            .map(|(_, n)| Node {
                parent_index: if n.parent_index < 0 {
                    -1
                } else {
                    renumber[n.parent_index as usize]
                },
                ..n.clone()
            })
            .collect();
        self.tweens.retain(|t| !doomed[t.index]);
        for tween in &mut self.tweens {
            tween.index = renumber[tween.index] as usize;
        }
        self.reshape(next);
        names
    }

    /// Moves onto `nodes` (declared nodes first, unchanged), carrying each surviving node's state by name.
    fn reshape(&mut self, nodes: Vec<Node>) {
        let old = self.definition.clone();
        let from: Vec<Option<usize>> = nodes.iter().map(|n| old.index_of(&n.name)).collect();
        fn carry<T: Clone>(from: &[Option<usize>], old: &[T], new: impl Fn(usize) -> T) -> Vec<T> {
            from.iter()
                .enumerate()
                .map(|(i, f)| f.map_or_else(|| new(i), |f| old[f].clone()))
                .collect()
        }
        self.rest = carry(&from, &self.rest, |i| nodes[i].transform.clone());
        self.pose = carry(&from, &self.pose, |i| nodes[i].transform.clone());
        self.content = carry(&from, &self.content, |i| nodes[i].display.clone());
        self.looks = carry(&from, &self.looks, |_| default_look());
        self.hidden = carry(&from, &self.hidden, |_| false);
        self.content_changed = carry(&from, &self.content_changed, |_| false);
        self.unclickable = carry(&from, &self.unclickable, |_| false);
        self.interpolation = carry(&from, &self.interpolation, |_| DEFAULT_INTERPOLATION_TICKS);
        let n = nodes.len();
        self.edited = vec![false; n];
        self.jumps = vec![false; n];
        self.due = vec![0; n];
        self.all_edited = true;
        self.definition = Rc::new(CompiledCentity {
            nodes,
            ..(*self.declared).clone()
        });
        self.needs_reconcile = true;
        self.posed();
    }

    /// Starts `tween`, replacing any of the same node and channel.
    pub fn tween(&mut self, tween: Tween) {
        self.tweens
            .retain(|t| !(t.index == tween.index && t.channel == tween.channel));
        self.tweens.push(tween);
    }

    /// Moves every tween on a tick, setting its channel's rest pose, and drops those that are done.
    pub fn step_tweens(&mut self) {
        let mut tweens = std::mem::take(&mut self.tweens);
        for tween in &mut tweens {
            tween.tick += 1;
            self.set(tween.index, tween.channel, tween.value(), false);
        }
        tweens.retain(|t| !t.done());
        self.tweens = tweens;
    }

    /// A channel of node `index`'s rest pose: the authored transform, or what a script last set.
    pub fn rest(&self, index: usize, channel: Channel) -> Vec3 {
        channel.of(&self.rest[index])
    }

    // ---- where it is ----

    pub fn anchor(&self) -> &Location {
        &self.anchor
    }

    pub fn set_anchor(&mut self, to: Location) {
        self.anchor = Location {
            yaw: 0.0,
            pitch: 0.0,
            ..to
        };
        self.moved = true;
    }

    pub fn yaw(&self) -> f64 {
        self.yaw
    }

    /// Turns it to face `yaw`.
    pub fn turn_to(&mut self, yaw: f64) {
        let yaw = wrap_degrees(yaw);
        if yaw == self.yaw {
            return;
        }
        self.yaw = yaw;
        self.all_edited = true;
        self.posed();
    }

    pub fn view_range(&self) -> f64 {
        self.view_range
    }

    pub fn set_view_range(&mut self, blocks: f64) {
        self.view_range = blocks;
        self.pose_dirty = true;
    }

    // ---- transforms ----

    /// Node `index`'s local transform as shown now.
    pub fn pose(&self, index: usize) -> &Transform {
        &self.pose[index]
    }

    /// Sets node `index`'s rest transform (an animation driving a channel still overrides it while it plays).
    /// `instant` shows it there at the next sync without easing.
    pub fn set_transform(&mut self, index: usize, transform: Transform, instant: bool) {
        self.rest[index] = transform.clone();
        self.pose[index] = transform;
        self.edited[index] = true;
        if instant {
            self.jumps[index] = true;
        }
        self.posed();
    }

    /// A channel of node `index`'s local transform as shown now, its default where the file sets none.
    pub fn get(&self, index: usize, channel: Channel) -> Vec3 {
        channel.of(&self.pose[index])
    }

    /// Sets one channel of node `index`'s rest pose (and the pose shown now), the others kept.
    pub fn set(&mut self, index: usize, channel: Channel, value: Vec3, instant: bool) {
        channel.put(&mut self.rest[index], value);
        channel.put(&mut self.pose[index], value);
        self.edited[index] = true;
        if instant {
            self.jumps[index] = true;
        }
        self.posed();
    }

    /// Node `index`'s matrix into the world.
    pub fn world_matrix(&self, index: usize) -> Matrix4 {
        let composed = self.definition.world(Some(&self.pose));
        self.space().mul(&composed[index])
    }

    /// The matrix into the world of the space node `index`'s transform is in: its parent's, or the
    /// centity's for a root.
    pub fn parent_world_matrix(&self, index: usize) -> Matrix4 {
        match self.definition.nodes[index].parent_index {
            p if p < 0 => self.space(),
            p => self.world_matrix(p as usize),
        }
    }

    /// The centity's facing as a rotation, from its axes to the world's: its +Z (the way a centity faces)
    /// comes out along the facing. Quarter turns are exact, so a centity turned to a compass direction
    /// reports whole numbers.
    pub fn turn(&self) -> Matrix4 {
        let mut matrix = Matrix4::IDENTITY;
        if self.yaw == 0.0 {
            return matrix;
        }
        let quarter = self.yaw / 90.0;
        if quarter != quarter.floor() {
            matrix.rotate_y(-self.yaw.to_radians());
            return matrix;
        }
        // rotate_y(-yaw) with its sine and cosine taken exactly.
        let (sin, cos) = match (quarter as i64).rem_euclid(4) {
            1 => (-1.0, 0.0),
            2 => (0.0, -1.0),
            3 => (1.0, 0.0),
            _ => (0.0, 1.0),
        };
        let v = &mut matrix.values;
        v[0] = cos;
        v[2] = -sin;
        v[8] = sin;
        v[10] = cos;
        matrix
    }

    /// The centity's own space as a matrix into the world: origin at the anchor, turned by `yaw`.
    pub fn space(&self) -> Matrix4 {
        let mut m = Matrix4::IDENTITY;
        m.translate(self.anchor.x, self.anchor.y, self.anchor.z);
        m.mul(&self.turn())
    }

    /// Each node's matrix relative to the anchor along the world's axes, turned by `yaw`: what the entities
    /// are shown (they stand at the anchor, unturned). Kept until the pose changes.
    pub fn placed(&mut self) -> &[Matrix4] {
        if self.placed_cache.is_none() {
            let composed = self.definition.world(Some(&self.pose));
            let turn = self.turn();
            let placed = if self.yaw == 0.0 {
                composed
            } else {
                composed.iter().map(|m| turn.mul(m)).collect()
            };
            self.placed_cache = Some(placed);
        }
        self.placed_cache.as_deref().expect("just made")
    }

    /// The pose as shown now (rest plus clips), for a clip blending in from it.
    pub fn shown_pose(&self) -> &[Transform] {
        &self.pose
    }

    /// The pose again from rest plus the playing clips.
    pub fn animate(&mut self) {
        let mut next = self.rest.clone();
        self.animations.apply(&mut next);
        if next != self.pose {
            self.pose = next;
            self.posed();
        }
    }

    /// `placed` as it will be `ticks` ticks from now if only the clips move it: rest plus the clips advanced
    /// that far, nothing else changing.
    pub fn placed_ahead(&self, ticks: i32) -> Vec<Matrix4> {
        let mut ahead = self.rest.clone();
        self.animations
            .apply_ahead(&mut ahead, f64::from(ticks) * SECONDS_PER_TICK);
        let composed = self.definition.world(Some(&ahead));
        if self.yaw == 0.0 {
            return composed;
        }
        let turn = self.turn();
        composed.iter().map(|m| turn.mul(m)).collect()
    }

    /// Whether node `index`'s shown matrix changed since the last sync in a way the clips alone don't
    /// account for: it, or a node above it, was set by a script, hidden or shown, or eased differently; or
    /// the centity turned, or a clip was started, stopped or moved.
    pub fn edited(&self, index: usize) -> bool {
        if self.all_edited || self.animations.version() != self.synced_clips {
            return true;
        }
        let mut at = index as i32;
        while at >= 0 {
            if self.edited[at as usize] {
                return true;
            }
            at = self.definition.nodes[at as usize].parent_index;
        }
        false
    }

    /// A position in the centity's space, in the world.
    pub fn to_world(&self, point: Vec3) -> Vec3 {
        self.space().transform_position(point)
    }

    pub fn pose_dirty(&self) -> bool {
        self.pose_dirty
    }

    pub fn mark_synced(&mut self) {
        self.pose_dirty = false;
        self.force_sync = false;
        self.moved = false;
        self.content_changed.fill(false);
        self.jumps.fill(false);
        self.edited.fill(false);
        self.all_edited = false;
        self.synced_clips = self.animations.version();
    }

    // ---- visibility and content ----

    pub fn visible(&self, index: usize) -> bool {
        !self.hidden[index]
    }

    pub fn set_visible(&mut self, index: usize, value: bool) {
        if self.hidden[index] == !value {
            return;
        }
        self.hidden[index] = !value;
        self.edited[index] = true;
        self.jumps[index] = true;
        self.pose_dirty = true;
    }

    /// Hidden when it or any ancestor is.
    pub fn shown(&self, index: usize) -> bool {
        let mut at = index as i32;
        while at >= 0 {
            if self.hidden[at as usize] {
                return false;
            }
            at = self.definition.nodes[at as usize].parent_index;
        }
        true
    }

    pub fn display(&self, index: usize) -> Option<&DisplayDef> {
        self.content[index].as_ref()
    }

    pub fn set_display(&mut self, index: usize, display: DisplayDef) {
        self.content[index] = Some(display);
        self.content_changed[index] = true;
        // Its fit may change with it (a rect's size, an anchor), so its pose is sent now too.
        self.edited[index] = true;
        self.pose_dirty = true;
    }

    pub fn content_changed(&self, index: usize) -> bool {
        self.content_changed[index]
    }

    /// How node `index`'s display turns to the camera: a script's look, else a text display's own, else fixed.
    pub fn billboard(&self, index: usize) -> Billboard {
        let own = match &self.content[index] {
            Some(DisplayDef::Text { billboard, .. }) => billboard.unwrap_or(DEFAULT_BILLBOARD),
            _ => Billboard::Fixed,
        };
        self.looks[index].billboard.unwrap_or(own)
    }

    /// Whether node `index`'s display turns to the camera at all.
    pub fn billboarded(&self, index: usize) -> bool {
        self.content[index].is_some() && self.billboard(index) != Billboard::Fixed
    }

    pub fn look(&self, index: usize) -> &DisplayLook {
        &self.looks[index]
    }

    pub fn set_look(&mut self, index: usize, look: DisplayLook) {
        self.looks[index] = look;
        self.pose_dirty = true;
    }

    /// What node `index`'s display is drawn with: its own look, plus the centity's view range and its easing as
    /// the teleport duration.
    pub fn shown_look(&self, index: usize) -> DisplayLook {
        DisplayLook {
            view_range: self.view_range / DEFAULT_VIEW_RANGE,
            teleport_ticks: self.interpolation[index].min(MAX_TELEPORT_TICKS),
            ..self.looks[index].clone()
        }
    }

    /// Whether node `index`'s hitbox takes clicks.
    pub fn clickable(&self, index: usize) -> bool {
        !self.unclickable[index]
    }

    pub fn set_clickable(&mut self, index: usize, value: bool) {
        if self.unclickable[index] == !value {
            return;
        }
        self.unclickable[index] = !value;
        self.pose_dirty = true;
    }

    /// Ticks node `index`'s display eases to a new pose over.
    pub fn interpolation(&self, index: usize) -> i32 {
        self.interpolation[index]
    }

    pub fn set_interpolation(&mut self, index: usize, ticks: i32) {
        self.interpolation[index] = ticks;
        self.edited[index] = true;
        self.pose_dirty = true;
    }

    /// Whether node `index`'s display jumps at the next sync: it or a node above it was shown, hidden or moved
    /// `instant`.
    pub fn jumps(&self, index: usize) -> bool {
        let mut at = index as i32;
        while at >= 0 {
            if self.jumps[at as usize] {
                return true;
            }
            at = self.definition.nodes[at as usize].parent_index;
        }
        false
    }

    /// Ticks a move of the whole centity is eased over (the anchor entity's teleport): its roots' easing, the
    /// least of them, at most what Minecraft eases a teleport over.
    pub fn move_ticks(&self) -> i32 {
        let mut ticks = MAX_TELEPORT_TICKS;
        for (index, node) in self.definition.nodes.iter().enumerate() {
            if node.is_root() {
                ticks = ticks.min(self.interpolation[index]);
            }
        }
        ticks.max(0)
    }

    /// Every entity it owns or still has to clean up.
    pub fn entity_ids(&self) -> Vec<Uuid> {
        self.anchor_entity
            .iter()
            .copied()
            .chain(self.displays.values().copied())
            .chain(self.hitboxes.values().copied())
            .chain(self.orphans.iter().copied())
            .collect()
    }

    /// The entities a player sees of it: its anchor, displays and hitboxes.
    pub fn shown_entities(&self) -> Vec<Uuid> {
        self.anchor_entity
            .iter()
            .copied()
            .chain(self.displays.values().copied())
            .chain(self.hitboxes.values().copied())
            .collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_quarter_turn_is_exact_and_faces_as_minecraft_does() {
        let definition: netherforge_format::kind::centity::CentityFile = serde_json::from_str(
            r#"{ "nodes": { "nose": { "transform": { "translation": [0, 0, 1] } } } }"#,
        )
        .unwrap();
        let compiled = Rc::new(netherforge_format::kind::centity::compiled::compile(
            "c",
            &definition,
        ));
        let at = Location {
            world: "world".into(),
            x: 0.0,
            y: 0.0,
            z: 0.0,
            yaw: 0.0,
            pitch: 0.0,
        };
        // 90 is west: the centity's +Z (its nose) comes out along -X, exactly.
        let mut west = Instance::new(Uuid(1), compiled.clone(), at.clone(), 90.0);
        assert_eq!(west.placed()[0].translation(), Vec3(-1.0, 0.0, 0.0));
        let mut north = Instance::new(Uuid(2), compiled.clone(), at.clone(), 180.0);
        assert_eq!(north.placed()[0].translation(), Vec3(0.0, 0.0, -1.0));
        // Facings wrap into [-180, 180).
        assert_eq!(Instance::new(Uuid(3), compiled, at, 270.0).yaw(), -90.0);
    }
}
