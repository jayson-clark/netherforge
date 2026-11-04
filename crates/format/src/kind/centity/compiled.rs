//! A centity as it runs or draws (format's `CentityCompiler`,
//! `CompiledCentity` and `Composer`): nodes flat, every parent before its
//! children; references as indices; keys in time order; its body, health and
//! each node's physics with every default filled in and every value held in
//! its range (`resolve`, from the fields' schemas).

use serde::{Deserialize, Serialize};

use super::{
    AnimationBlend, BodyDef, CentityFile, Channel, DisplayDef, HealthBarDef, HealthDef, HitboxDef,
    LoopMode, NodeDef, PhysicsDef, PhysicsShape, SpawningDef, Transform,
};
use crate::animation::{Easing, Keyframe};
use crate::canonical::Map;
use crate::math::{BoundingBox, Matrix4, Vec3, degrees_to_radians, shortest_delta};
use crate::resolve::resolved;
use crate::script::ScriptDef;

/// The shortest a clip is, seconds: one with no keys still has a length.
const MIN_LENGTH: f64 = 1e-4;

/// A centity ready to run or draw, from a file whose checks found no errors.
#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CompiledCentity {
    pub id: String,
    pub name: Option<String>,
    pub nodes: Vec<Node>,
    pub animations: Vec<CompiledAnimation>,
    /// Its one script, run once per instance.
    pub script: Option<ScriptDef>,
    pub spawning: Option<SpawningDef>,
    pub body: Option<ResolvedBody>,
    pub health: Option<ResolvedHealth>,
    /// Its `properties`, as the file has them.
    pub properties: Option<Map<serde_json::Value>>,
}

impl CompiledCentity {
    pub fn index_of(&self, node: &str) -> Option<usize> {
        self.nodes.iter().position(|n| n.name == node)
    }

    pub fn node(&self, name: &str) -> Option<&Node> {
        self.index_of(name).map(|i| &self.nodes[i])
    }

    pub fn animation(&self, name: &str) -> Option<&CompiledAnimation> {
        self.animations.iter().find(|a| a.name == name)
    }

    /// The clips that start as an instance does.
    pub fn autoplay(&self) -> impl Iterator<Item = &CompiledAnimation> {
        self.animations.iter().filter(|a| a.autoplay)
    }

    /// Each node's world matrix, in node order, from each one's local
    /// transform (its own, unless `locals` says otherwise): shared so a
    /// viewport can't disagree with the server about where a node is.
    pub fn world(&self, locals: Option<&[Transform]>) -> Vec<Matrix4> {
        let own: Vec<Transform>;
        let locals = match locals {
            Some(l) => l,
            None => {
                own = self.nodes.iter().map(|n| n.transform.clone()).collect();
                &own
            }
        };
        assert_eq!(locals.len(), self.nodes.len(), "a transform for each node");
        let mut world: Vec<Matrix4> = Vec::with_capacity(locals.len());
        for (node, transform) in self.nodes.iter().zip(locals) {
            let local = transform.matrix();
            world.push(match node.parent_index {
                -1 => local,
                parent => world[parent as usize].mul(&local),
            });
        }
        world
    }

    /// The local transforms after sampling `animation` at `time` over the
    /// authored pose: a track owns its channel, or for an additive clip adds
    /// to it (scale multiplies).
    pub fn pose(&self, animation: &CompiledAnimation, time: f64) -> Vec<Transform> {
        let at = animation.resolve(time, animation.looped).time;
        let mut locals: Vec<Transform> = self.nodes.iter().map(|n| n.transform.clone()).collect();
        for track in &animation.tracks {
            let local = &mut locals[track.node_index];
            *local = local.posed(track.channel, track.sample(at), animation.blend);
        }
        locals
    }
}

/// One node as it runs.
#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Node {
    pub name: String,
    /// Its parent's index in the nodes, or -1 for a root.
    pub parent_index: i32,
    pub transform: Transform,
    pub display: Option<DisplayDef>,
    pub hitbox: Option<HitboxDef>,
    pub physics: Option<ResolvedPhysics>,
    /// For a rope: its links' indices in order from its start, and each one's length.
    pub rope: Option<Rope>,
}

impl Node {
    pub fn is_root(&self) -> bool {
        self.parent_index < 0
    }
}

#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Rope {
    pub links: Vec<usize>,
    pub link_length: f64,
}

#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CompiledAnimation {
    pub name: String,
    /// Seconds, always more than 0.
    pub length: f64,
    #[serde(rename = "loop")]
    pub looped: LoopMode,
    pub autoplay: bool,
    pub tracks: Vec<Track>,
    pub blend: AnimationBlend,
}

/// Where a time lands in a clip, and whether that's past its end.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Position {
    pub time: f64,
    pub ended: bool,
}

impl CompiledAnimation {
    /// Where `time` lands in the clip under `looped` (its own mode, unless a
    /// player overrides it).
    pub fn resolve(&self, time: f64, looped: LoopMode) -> Position {
        match looped {
            LoopMode::Loop => Position {
                time: time - self.length * (time / self.length).floor(),
                ended: false,
            },
            LoopMode::Once | LoopMode::Hold if time >= self.length => Position {
                time: self.length,
                ended: true,
            },
            LoopMode::Once | LoopMode::Hold => Position { time, ended: false },
        }
    }
}

/// One node's channel: keys in time order, never none.
#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Track {
    pub node_index: usize,
    pub channel: Channel,
    pub keys: Vec<Keyframe>,
}

impl Track {
    /// The pose at `time`, holding the first and last key outside the keyed range.
    pub fn sample(&self, time: f64) -> Vec3 {
        let (first, last) = (&self.keys[0], &self.keys[self.keys.len() - 1]);
        if time <= first.time {
            return first.value;
        }
        if time >= last.time {
            return last.value;
        }
        // The first key past `time`.
        let next = self.keys.partition_point(|k| k.time <= time);
        let (from, to) = (&self.keys[next - 1], &self.keys[next]);
        let span = to.time - from.time;
        if span <= 0.0 {
            return to.value;
        }
        let t = from
            .easing
            .unwrap_or(Easing::Linear)
            .apply((time - from.time) / span);
        let (a, b) = (from.value, to.value);
        match self.channel {
            Channel::Rotation => Vec3(
                a.0 + shortest_delta(a.0, b.0) * t,
                a.1 + shortest_delta(a.1, b.1) * t,
                a.2 + shortest_delta(a.2, b.2) * t,
            ),
            _ => a + (b - a) * t,
        }
    }
}

impl Transform {
    pub fn translation_or_default(&self) -> Vec3 {
        self.translation.unwrap_or(Vec3::ZERO)
    }

    pub fn rotation_or_default(&self) -> Vec3 {
        self.rotation.unwrap_or(Vec3::ZERO)
    }

    pub fn scale_or_default(&self) -> Vec3 {
        self.scale.unwrap_or(Vec3::ONE)
    }

    /// Its local matrix, `T * Rx * Ry * Rz * S`.
    pub fn matrix(&self) -> Matrix4 {
        Matrix4::from_trs(
            self.translation_or_default(),
            self.rotation_or_default(),
            self.scale_or_default(),
        )
    }

    /// This transform with `channel` set to `value` (replace) or changed by it
    /// (additive: translation and rotation added, scale multiplied).
    pub fn posed(&self, channel: Channel, value: Vec3, blend: AnimationBlend) -> Transform {
        let additive = blend == AnimationBlend::Additive;
        let mut out = self.clone();
        match channel {
            Channel::Translation => {
                out.translation = Some(match additive {
                    true => self.translation_or_default() + value,
                    false => value,
                })
            }
            Channel::Rotation => {
                out.rotation = Some(match additive {
                    true => self.rotation_or_default() + value,
                    false => value,
                })
            }
            Channel::Scale => {
                out.scale = Some(match additive {
                    true => self.scale_or_default().times(value),
                    false => value,
                })
            }
        }
        out
    }
}

/// A node's `physics` as it runs: its schema's defaults and ranges applied.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ResolvedPhysics {
    /// Blocks per second squared.
    pub gravity: f64,
    /// The authored mass, or none to take it from the collider's volume.
    pub mass: Option<f64>,
    pub bounciness: f64,
    pub friction: f64,
    /// The fraction of speed shed per second.
    pub drag: f64,
    pub angular_drag: f64,
    /// Blocks per second.
    pub max_speed: f64,
    /// Degrees per second.
    pub max_spin: f64,
    pub collider: Option<BoundingBox>,
    /// None follows the hitbox.
    pub shape: Option<PhysicsShape>,
    pub rotates: bool,
    pub sleeps: bool,
    /// Collides with the world's blocks.
    pub blocks: bool,
    /// Collides with other centities' hitboxes.
    pub entities: bool,
}

impl ResolvedPhysics {
    pub fn of(def: &PhysicsDef) -> ResolvedPhysics {
        resolved(def)
    }

    pub fn max_spin_radians(&self) -> f64 {
        degrees_to_radians(self.max_spin)
    }
}

/// A centity's `body` as it runs: its schema's defaults and ranges applied
/// (a script's `set_body` changes a copy).
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ResolvedBody {
    pub width: f64,
    pub height: f64,
    pub gravity: f64,
    pub max_fall_speed: f64,
    pub drag: f64,
    pub liquid_drag: f64,
    pub liquid_gravity: f64,
    pub acceleration: f64,
    pub air_acceleration: f64,
    pub friction: f64,
    pub step_height: f64,
    pub bounce: f64,
    pub fly: bool,
    pub blocks: bool,
}

impl ResolvedBody {
    pub fn of(def: &BodyDef) -> ResolvedBody {
        resolved(def)
    }
}

/// A centity's `health` as it runs; `bar` none without one.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ResolvedHealth {
    pub max: f64,
    pub remove_on_death: bool,
    #[serde(skip_deserializing)]
    pub bar: Option<ResolvedBar>,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ResolvedBar {
    pub segments: i32,
    pub show_ticks: i32,
    pub offset: f64,
    pub glyph: String,
    pub filled: String,
    pub empty: String,
}

impl ResolvedHealth {
    pub fn of(def: &HealthDef) -> ResolvedHealth {
        ResolvedHealth {
            bar: def.bar.as_ref().map(resolved::<HealthBarDef, ResolvedBar>),
            ..resolved(def)
        }
    }
}

/// One node as it runs: `parent_index` is its parent's place in the list it
/// joins, or -1 for a root. A script adds nodes this way.
pub fn compile_node(name: &str, def: &NodeDef, parent_index: i32) -> Node {
    Node {
        name: name.to_owned(),
        parent_index,
        transform: def.transform.clone().unwrap_or_default(),
        display: def.display.clone(),
        hitbox: def.hitbox.clone(),
        physics: def.physics.as_ref().map(ResolvedPhysics::of),
        rope: None,
    }
}

/// The name of a rope's `link`th link (from 1).
pub fn link_name(rope: &str, link: i32) -> String {
    format!("{rope}[{link}]")
}

/// `file` (the centity `id`) as it runs. Panics on a file whose checks found
/// errors (no nodes, a cycle, an unknown parent): that's a caller's bug.
pub fn compile(id: &str, file: &CentityFile) -> CompiledCentity {
    assert!(!file.nodes.is_empty(), "centity \"{id}\" has no nodes");
    // Parents first; siblings in name order, so the result never depends on the file's order.
    let mut children: std::collections::BTreeMap<Option<&str>, Vec<&str>> = Default::default();
    for (name, node) in file.nodes.iter() {
        children
            .entry(node.parent.as_deref())
            .or_default()
            .push(&name.0);
    }
    for names in children.values_mut() {
        names.sort();
    }
    let mut order: Vec<&str> = Vec::with_capacity(file.nodes.len());
    let mut queue: std::collections::VecDeque<&str> =
        children.get(&None).cloned().unwrap_or_default().into();
    while let Some(name) = queue.pop_front() {
        if order.contains(&name) {
            continue;
        }
        order.push(name);
        queue.extend(children.get(&Some(name)).into_iter().flatten());
    }
    assert_eq!(
        order.len(),
        file.nodes.len(),
        "centity \"{id}\" has nodes with unknown parents or a cycle"
    );
    let index = |name: &str| order.iter().position(|n| *n == name);
    let def = |name: &str| file.nodes.get_str(name).expect("an ordered node is a node");
    let mut nodes: Vec<Node> = order
        .iter()
        .map(|name| {
            let def = def(name);
            let parent = def
                .parent
                .as_deref()
                .and_then(index)
                .map_or(-1, |i| i as i32);
            compile_node(name, def, parent)
        })
        .collect();
    // A rope draws nothing itself: its display is its links', children of it after every other
    // node, laid straight along its Z until a script strings it.
    for (rope_index, name) in order.iter().enumerate() {
        let Some(rope) = &def(name).rope else {
            continue;
        };
        let length = crate::resolve::resolved::<_, RopeLength>(rope).link_length;
        let display = nodes[rope_index].display.take();
        let links = (1..=rope.links)
            .map(|link| {
                nodes.push(Node {
                    name: link_name(name, link),
                    parent_index: rope_index as i32,
                    transform: Transform {
                        translation: Some(Vec3(0.0, 0.0, (link as f64 - 0.5) * length)),
                        rotation: None,
                        scale: Some(Vec3(1.0, 1.0, length)),
                    },
                    display: display.clone(),
                    hitbox: None,
                    physics: None,
                    rope: None,
                });
                nodes.len() - 1
            })
            .collect();
        let node = &mut nodes[rope_index];
        node.hitbox = None;
        node.physics = None;
        node.rope = Some(Rope {
            links,
            link_length: length,
        });
    }
    let mut animations: Vec<CompiledAnimation> = file
        .animations
        .iter()
        .map(|(name, clip)| {
            let mut tracks = Vec::new();
            let mut by_node: Vec<_> = clip.tracks.iter().collect();
            by_node.sort_by(|a, b| a.0.0.cmp(&b.0.0));
            for (node, channels) in by_node {
                let node_index = index(&node.0).unwrap_or_else(|| {
                    panic!("animation \"{name}\" drives unknown node \"{node}\"")
                });
                let mut channels: Vec<_> = channels.iter().collect();
                channels.sort_by_key(|(channel, _)| **channel);
                for (channel, keys) in channels {
                    if keys.is_empty() {
                        continue;
                    }
                    tracks.push(Track {
                        node_index,
                        channel: *channel,
                        keys: crate::animation::by_time(keys, |k| k.time),
                    });
                }
            }
            let last_key = tracks
                .iter()
                .map(|t| t.keys[t.keys.len() - 1].time)
                .fold(None, |max: Option<f64>, t| {
                    Some(max.map_or(t, |m| m.max(t)))
                })
                .unwrap_or(0.0);
            CompiledAnimation {
                name: name.0.clone(),
                length: clip.length.unwrap_or(last_key).max(MIN_LENGTH),
                looped: clip.looped.unwrap_or(LoopMode::Once),
                autoplay: clip.autoplay.unwrap_or(false),
                tracks,
                blend: clip.blend.unwrap_or(AnimationBlend::Replace),
            }
        })
        .collect();
    animations.sort_by(|a, b| a.name.cmp(&b.name));
    CompiledCentity {
        id: id.to_owned(),
        name: file.name.clone(),
        nodes,
        animations,
        script: file.script.clone(),
        spawning: file.spawning.clone(),
        body: file.body.as_ref().map(ResolvedBody::of),
        health: file.health.as_ref().map(ResolvedHealth::of),
        properties: file.properties.clone(),
    }
}

/// A rope's link length as it runs.
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct RopeLength {
    link_length: f64,
}
