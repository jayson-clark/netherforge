//! Nodes' rigid bodies on Rapier (the roadmap's "Rigid-body physics on Rapier", replacing Kotlin's
//! hand-ported solver): one Rapier world per Minecraft world of a room, stepped once a tick after every
//! instance has moved and before scripts' `tick`. Every body in it is simulated together, so a crate knocks
//! over another centity's crate (a deliberate change from Kotlin, where another centity's bodies were
//! immovable scenery).
//!
//! What a body runs into:
//!  - the world's blocks, as fixed colliders built from their live collision boxes, by chunk section, around
//!    the awake bodies; a section is read again when a block in it is placed or broken (`block_changed`,
//!    waking the bodies near it), and once it's older than `SECTION_TICKS` (for the changes no event says:
//!    an explosion, a piston, fluids), and let go of when nothing has needed it for a while;
//!  - every centity's hitboxes that no body carries, as fixed colliders, measured again when they change;
//!  - every other body.
//!
//! A body's collider is its node's hitbox boxes (or `physics.collider`, or the unit cube), scaled by the node,
//! a box or a sphere per `physics.shape`; its mass is `physics.mass` or its volume. Physics writes the node's
//! translation and rotation through the setters a script uses, and remembers the pose it wrote: a node that
//! reads differently next tick was moved by a script or a clip, and its body is put there. Afterwards a node
//! hears `collide` for what its body started touching, and `wake` and `sleep` when it changed between moving
//! and settled. Velocities are blocks a second; spin is degrees a second to scripts, radians here.

use std::collections::{HashMap, HashSet};
use std::rc::Rc;

use netherforge_format::game::GameData;
use netherforge_format::kind::centity::PhysicsShape;
use netherforge_format::kind::centity::compiled::ResolvedPhysics;
use netherforge_format::math::{BoundingBox, Matrix4, Vec3};
use netherforge_platform::{Platform, Uuid};
use rapier3d_f64::math::{Pose, Rotation, Vector as RVec};
use rapier3d_f64::prelude::{
    ActiveEvents, ChannelEventCollector, CoefficientCombineRule, ColliderBuilder, ColliderHandle,
    CollisionEvent, Group, InteractionGroups, InteractionTestMode, PhysicsWorld, RigidBodyBuilder,
    RigidBodyHandle, RigidBodyType,
};

use super::Instance;
use super::instance::Channel;
use super::quat::Quat;

/// A tick, in seconds.
const SECONDS: f64 = 1.0 / 20.0;
/// Blocks a side of a section of the world read at once.
const SECTION: i32 = 16;
/// Ticks a section's blocks are kept before they're read again.
const SECTION_TICKS: u64 = 40;
/// Ticks a section nothing needed is kept.
const SECTION_IDLE: u64 = 200;
/// How far a body may travel from its anchor before the anchor follows: a chunk.
pub const REANCHOR: f64 = 16.0;
/// The least a body may weigh.
const MIN_MASS: f64 = 1e-3;
/// Differences below these are the same pose.
const STILL: f64 = 1e-6;
/// A contact normal this far up is ground.
const GROUND: f64 = 0.7;

const BLOCKS: Group = Group::GROUP_1;
const HITBOXES: Group = Group::GROUP_2;
const BODIES: Group = Group::GROUP_3;

/// What a node's body keeps between ticks that isn't its pose: what scripts set, and what they're told.
#[derive(Clone, Debug, PartialEq)]
pub struct BodyState {
    /// Blocks a second, along the world's axes.
    pub velocity: Vec3,
    /// Radians a second.
    pub angular_velocity: Vec3,
    /// Whether its `physics.gravity` pulls it (`set_gravity`).
    pub gravity: bool,
    /// Moved by its own velocity alone, never by gravity or contacts (`set_kinematic`).
    pub kinematic: bool,
    /// Simulated at all: a frozen body doesn't move, and the others hit it (`set_physics_enabled`).
    pub enabled: bool,
    pub on_ground: bool,
    pub asleep: bool,
    /// Whether scripts were last told it's asleep.
    pub reported_asleep: bool,
    /// A script changed its velocity, flags or woke it since the last step.
    pub dirty: bool,
    /// Its Rapier body, once made.
    pub(crate) handle: Option<RigidBodyHandle>,
    /// Where physics last put the node: its world origin and rotation, and the node's scale then.
    pub(crate) written: Option<(Vec3, Quat, Vec3)>,
}

impl Default for BodyState {
    fn default() -> BodyState {
        BodyState {
            velocity: Vec3::ZERO,
            angular_velocity: Vec3::ZERO,
            gravity: true,
            kinematic: false,
            enabled: true,
            on_ground: false,
            asleep: false,
            reported_asleep: false,
            dirty: true,
            handle: None,
            written: None,
        }
    }
}

impl BodyState {
    pub fn wake(&mut self) {
        self.asleep = false;
        self.dirty = true;
    }
}

/// The boxes a body collides as, in its node's space: `physics.collider`, else its hitbox's (one box around
/// them for `shape: box`), else the unit cube a block display fills.
pub fn collider_boxes(
    physics: &ResolvedPhysics,
    hitbox: Option<Vec<BoundingBox>>,
) -> Vec<BoundingBox> {
    if let Some(collider) = physics.collider {
        return vec![collider];
    }
    let boxes = hitbox
        .filter(|b| !b.is_empty())
        .unwrap_or_else(|| vec![super::hitboxes::UNIT_CUBE]);
    if physics.shape == Some(PhysicsShape::Box) && boxes.len() > 1 {
        return vec![super::hitboxes::bounds_of(&boxes, &Matrix4::IDENTITY)];
    }
    boxes
}

fn volume(b: &BoundingBox) -> f64 {
    let s = b.max - b.min;
    (s.0 * s.1 * s.2).max(0.0)
}

/// What a body weighs when nothing says: its collider's volume (scaled), at least `MIN_MASS`.
pub fn mass_of(physics: &ResolvedPhysics, boxes: &[BoundingBox], scale: Vec3) -> f64 {
    physics
        .mass
        .unwrap_or_else(|| {
            boxes.iter().map(volume).sum::<f64>() * (scale.0 * scale.1 * scale.2).abs()
        })
        .max(MIN_MASS)
}

fn rv(v: Vec3) -> RVec {
    RVec::new(v.0, v.1, v.2)
}

fn fv(v: RVec) -> Vec3 {
    Vec3(v.x, v.y, v.z)
}

fn rq(q: Quat) -> Rotation {
    Rotation::from_xyzw(q.x, q.y, q.z, q.w)
}

fn fq(q: &Rotation) -> Quat {
    Quat {
        x: q.x,
        y: q.y,
        z: q.z,
        w: q.w,
    }
}

/// The fraction of speed shed a second as Rapier's damping coefficient.
fn damping(fraction: f64) -> f64 {
    if fraction >= 1.0 {
        50.0
    } else {
        -(1.0 - fraction.max(0.0)).ln()
    }
}

/// One body's Rapier side.
struct Entry {
    handle: RigidBodyHandle,
    world: String,
    instance: Uuid,
    node: String,
    scale: Vec3,
    /// Its velocity before the step, for how hard it hit what it started touching.
    before: Vec3,
}

/// Fixed colliders built from one section of a world's blocks.
struct Section {
    colliders: Vec<ColliderHandle>,
    built: u64,
    needed: u64,
}

/// One Rapier world, and what's in it.
struct World {
    rapier: PhysicsWorld,
    events: std::sync::mpsc::Receiver<CollisionEvent>,
    collector: ChannelEventCollector,
    sections: HashMap<(i32, i32, i32), Section>,
    /// Each instance's hitboxes no body carries, as last measured, and their colliders.
    scenery: HashMap<Uuid, (Rc<super::spatial::Obstacles>, Vec<ColliderHandle>)>,
}

impl World {
    fn new() -> World {
        let (collisions, events) = std::sync::mpsc::channel();
        let (forces, _) = std::sync::mpsc::channel();
        let (tears, _) = std::sync::mpsc::channel();
        let mut rapier = PhysicsWorld::new();
        // Each body's own gravity is its gravity scale.
        rapier.gravity = RVec::new(0.0, -1.0, 0.0);
        rapier.integration_parameters.dt = SECONDS;
        World {
            rapier,
            events,
            collector: ChannelEventCollector::new(collisions, forces, tears),
            sections: HashMap::new(),
            scenery: HashMap::new(),
        }
    }

    fn remove_collider(&mut self, handle: ColliderHandle) {
        let w = &mut self.rapier;
        w.colliders.remove(
            handle,
            &mut w.islands,
            &mut w.bodies,
            &mut w.soft_bodies,
            false,
        );
    }

    fn remove_body(&mut self, handle: RigidBodyHandle) {
        let w = &mut self.rapier;
        w.bodies.remove(
            handle,
            &mut w.islands,
            &mut w.colliders,
            &mut w.impulse_joints,
            &mut w.multibody_joints,
            &mut w.soft_bodies,
            true,
        );
    }
}

/// A started contact, for `collide`.
pub struct Collision {
    pub instance: Uuid,
    pub node: String,
    pub other: Option<(Uuid, String)>,
    pub position: Vec3,
    pub normal: Vec3,
    pub speed: f64,
}

/// A body that changed between moving and settled, for `wake` and `sleep`.
pub struct Settled {
    pub instance: Uuid,
    pub node: String,
    pub asleep: bool,
}

/// The rigid bodies of one room.
#[derive(Default)]
pub struct Physics {
    worlds: HashMap<String, World>,
    bodies: HashMap<(Uuid, String), Entry>,
    by_handle: HashMap<(String, RigidBodyHandle), (Uuid, String)>,
    tick: u64,
}

/// A node's pose in the world: its origin, its rotation and its scale.
fn pose_of(instance: &Instance, world: &[Matrix4], index: usize) -> (Vec3, Quat, Vec3) {
    let a = instance.anchor();
    let m = &world[index];
    (
        m.translation() + Vec3(a.x, a.y, a.z),
        Quat::from_basis(m),
        m.scale_of(),
    )
}

fn same_pose(a: &(Vec3, Quat, Vec3), b: &(Vec3, Quat, Vec3)) -> bool {
    let close = |x: Vec3, y: Vec3| (x - y).length() < STILL;
    let turned = (a.1.x - b.1.x)
        .abs()
        .max((a.1.y - b.1.y).abs())
        .max((a.1.z - b.1.z).abs())
        .max((a.1.w - b.1.w).abs());
    // q and -q are one rotation.
    let flipped = (a.1.x + b.1.x)
        .abs()
        .max((a.1.y + b.1.y).abs())
        .max((a.1.z + b.1.z).abs())
        .max((a.1.w + b.1.w).abs());
    close(a.0, b.0) && close(a.2, b.2) && turned.min(flipped) < STILL
}

/// Whether node `index` hangs under a node with physics (it then moves with that body).
fn under_a_body(instance: &Instance, index: usize) -> bool {
    let nodes = &instance.definition.nodes;
    let mut at = nodes[index].parent_index;
    while at >= 0 {
        if nodes[at as usize].physics.is_some() {
            return true;
        }
        at = nodes[at as usize].parent_index;
    }
    false
}

impl Physics {
    pub fn has_bodies(&self) -> bool {
        !self.bodies.is_empty()
    }

    /// Every body, its section and scenery let go of: the session is ending.
    pub fn clear(&mut self) {
        *self = Physics::default();
    }

    /// The block at `x, y, z` of `world` was placed or broken: its section's colliders go, to be built again
    /// from the world as it is by the next step, and every body near it wakes (a crate resting on it falls).
    pub fn block_changed(&mut self, world: &str, x: i32, y: i32, z: i32) {
        let Some(w) = self.worlds.get_mut(world) else {
            return;
        };
        let key = (
            x.div_euclid(SECTION),
            y.div_euclid(SECTION),
            z.div_euclid(SECTION),
        );
        if let Some(old) = w.sections.remove(&key) {
            for c in old.colliders {
                w.remove_collider(c);
            }
        }
        let (lo, hi) = (
            Vec3(f64::from(x) - 1.0, f64::from(y) - 1.0, f64::from(z) - 1.0),
            Vec3(f64::from(x) + 2.0, f64::from(y) + 2.0, f64::from(z) + 2.0),
        );
        let near: Vec<RigidBodyHandle> = w
            .rapier
            .bodies
            .iter()
            .filter(|(_, body)| body.is_dynamic())
            .filter(|(_, body)| {
                body.colliders().iter().any(|c| {
                    w.rapier.colliders.get(*c).is_some_and(|collider| {
                        let aabb = collider.compute_aabb();
                        let (a, b) = (fv(aabb.mins), fv(aabb.maxs));
                        a.0 <= hi.0
                            && b.0 >= lo.0
                            && a.1 <= hi.1
                            && b.1 >= lo.1
                            && a.2 <= hi.2
                            && b.2 >= lo.2
                    })
                })
            })
            .map(|(handle, _)| handle)
            .collect();
        for handle in near {
            if let Some(body) = w.rapier.bodies.get_mut(handle) {
                body.wake_up(true);
            }
        }
    }

    /// Brings Rapier in line with every instance (bodies made, moved, retyped, removed), steps each world a
    /// tick, and writes the bodies back onto their nodes. What scripts are to hear.
    pub fn step(
        &mut self,
        platform: &dyn Platform,
        centities: &mut super::Centities,
    ) -> (Vec<Collision>, Vec<Settled>) {
        self.tick += 1;
        let game = platform.game();
        let ids: Vec<Uuid> = centities.all().map(|i| i.id).collect();
        let mut live: HashSet<(Uuid, String)> = HashSet::new();
        let mut worlds_with_bodies: HashSet<String> = HashSet::new();
        for id in &ids {
            let Some(instance) = centities.get_mut(*id) else {
                continue;
            };
            if instance.removed || !super::Centities::loaded(platform, instance) {
                continue;
            }
            if !instance
                .definition
                .nodes
                .iter()
                .any(|n| n.physics.is_some())
            {
                continue;
            }
            worlds_with_bodies.insert(instance.anchor().world.clone());
            self.sync_instance(game, instance, &mut live);
        }
        // Bodies whose node or instance is gone, or was remade.
        let gone: Vec<(Uuid, String)> = self
            .bodies
            .keys()
            .filter(|k| !live.contains(*k))
            .cloned()
            .collect();
        for key in gone {
            if let Some(entry) = self.bodies.remove(&key) {
                self.by_handle.remove(&(entry.world.clone(), entry.handle));
                if let Some(world) = self.worlds.get_mut(&entry.world) {
                    world.remove_body(entry.handle);
                }
            }
        }
        let tick = self.tick;
        let mut collisions = Vec::new();
        let names: Vec<String> = self.worlds.keys().cloned().collect();
        for name in names {
            if !worlds_with_bodies.contains(&name) {
                // Nothing simulated here: its blocks and scenery go.
                self.worlds.remove(&name);
                continue;
            }
            self.scenery(game, centities, &name);
            self.blocks(platform, &name, tick);
            let world = self.worlds.get_mut(&name).expect("listed");
            let w = &mut world.rapier;
            w.step_with_events(&(), &world.collector);
            let started: Vec<(ColliderHandle, ColliderHandle)> = world
                .events
                .try_iter()
                .filter_map(|e| match e {
                    CollisionEvent::Started(a, b, _) => Some((a, b)),
                    CollisionEvent::Stopped(..) => None,
                })
                .collect();
            for (a, b) in started {
                collisions.extend(self.started(&name, a, b));
            }
        }
        let settled = self.write_back(centities);
        (collisions, settled)
    }

    /// Makes, moves and retypes the bodies of one instance's nodes.
    fn sync_instance(
        &mut self,
        game: &dyn GameData,
        instance: &mut Instance,
        live: &mut HashSet<(Uuid, String)>,
    ) {
        let world = instance.placed().to_vec();
        let id = instance.id;
        let name = instance.anchor().world.clone();
        for index in 0..instance.definition.nodes.len() {
            let Some(physics) = instance.definition.nodes[index].physics.clone() else {
                continue;
            };
            let Some(state) = instance.bodies.get(index).cloned().flatten() else {
                continue;
            };
            let node = instance.definition.nodes[index].name.clone();
            let key = (id, node.clone());
            live.insert(key.clone());
            let pose = pose_of(instance, &world, index);
            self.worlds.entry(name.clone()).or_insert_with(World::new);
            // A remade state (a reload) or a body in another world now is made again.
            let stale = match (self.bodies.get(&key), state.handle) {
                (Some(e), Some(h)) => {
                    e.handle != h || e.world != name || (e.scale - pose.2).length() > STILL
                }
                (Some(_), None) => true,
                (None, _) => false,
            };
            if stale && let Some(entry) = self.bodies.remove(&key) {
                self.by_handle.remove(&(entry.world.clone(), entry.handle));
                if let Some(w) = self.worlds.get_mut(&entry.world) {
                    w.remove_body(entry.handle);
                }
            }
            if !self.bodies.contains_key(&key) {
                let boxes = collider_boxes(
                    &physics,
                    instance.definition.nodes[index]
                        .hitbox
                        .as_ref()
                        .map(|_| super::boxes_of(game, instance, index)),
                );
                let handle = make_body(
                    self.worlds.get_mut(&name).expect("made"),
                    &physics,
                    &state,
                    &boxes,
                    pose,
                );
                self.by_handle.insert((name.clone(), handle), key.clone());
                self.bodies.insert(
                    key.clone(),
                    Entry {
                        handle,
                        world: name.clone(),
                        instance: id,
                        node: node.clone(),
                        scale: pose.2,
                        before: state.velocity,
                    },
                );
                if let Some(Some(s)) = instance.bodies.get_mut(index) {
                    s.handle = Some(handle);
                    s.written = Some(pose);
                    s.dirty = false;
                }
                continue;
            }
            let entry = self.bodies.get_mut(&key).expect("there");
            entry.before = state.velocity;
            let w = &mut self.worlds.get_mut(&name).expect("made").rapier;
            let Some(body) = w.bodies.get_mut(entry.handle) else {
                continue;
            };
            // A script or a clip moved the node since physics last put it there: the body goes there too.
            if state
                .written
                .as_ref()
                .is_none_or(|was| !same_pose(was, &pose))
            {
                body.set_position(Pose::from_parts(rv(pose.0), rq(pose.1)), true);
                if let Some(Some(s)) = instance.bodies.get_mut(index) {
                    s.written = Some(pose);
                }
            }
            if state.dirty {
                let kind = if !state.enabled {
                    RigidBodyType::Fixed
                } else if state.kinematic {
                    RigidBodyType::KinematicVelocityBased
                } else {
                    RigidBodyType::Dynamic
                };
                if body.body_type() != kind {
                    body.set_body_type(kind, true);
                }
                body.set_gravity_scale(if state.gravity { physics.gravity } else { 0.0 }, true);
                body.set_linvel(rv(state.velocity), true);
                body.set_angvel(rv(state.angular_velocity), true);
                if !state.asleep {
                    body.wake_up(true);
                }
                if let Some(Some(s)) = instance.bodies.get_mut(index) {
                    s.dirty = false;
                }
            }
        }
    }

    /// Fixed colliders for every instance's hitboxes in `name` that no body carries.
    fn scenery(&mut self, game: &dyn GameData, centities: &mut super::Centities, name: &str) {
        let ids: Vec<Uuid> = centities
            .all()
            .filter(|i| !i.removed && i.anchor().world == name)
            .map(|i| i.id)
            .collect();
        let world = self.worlds.get_mut(name).expect("made");
        let keep: HashSet<Uuid> = ids.iter().copied().collect();
        let gone: Vec<Uuid> = world
            .scenery
            .keys()
            .filter(|id| !keep.contains(id))
            .copied()
            .collect();
        for id in gone {
            if let Some((_, colliders)) = world.scenery.remove(&id) {
                for c in colliders {
                    world.remove_collider(c);
                }
            }
        }
        for id in ids {
            let Some(obstacles) = centities.obstacles(game, id) else {
                continue;
            };
            if world
                .scenery
                .get(&id)
                .is_some_and(|(was, _)| Rc::ptr_eq(was, &obstacles))
            {
                continue;
            }
            if let Some((_, colliders)) = world.scenery.remove(&id) {
                for c in colliders {
                    world.remove_collider(c);
                }
            }
            let Some(instance) = centities.get(id) else {
                continue;
            };
            let a = instance.anchor();
            let at = Vec3(a.x, a.y, a.z);
            let mut colliders = Vec::new();
            for (b, node) in obstacles.boxes.iter().zip(&obstacles.nodes) {
                if instance.definition.nodes[*node].physics.is_some()
                    || under_a_body(instance, *node)
                {
                    continue;
                }
                let half = (b.max - b.min) * 0.5;
                let collider =
                    ColliderBuilder::cuboid(half.0.max(1e-3), half.1.max(1e-3), half.2.max(1e-3))
                        .translation(rv((b.min + b.max) * 0.5 + at))
                        .friction(1.0)
                        .restitution(0.0)
                        .collision_groups(InteractionGroups::new(
                            HITBOXES,
                            BODIES,
                            InteractionTestMode::And,
                        ))
                        .build();
                colliders.push(world.rapier.colliders.insert(collider));
            }
            world.scenery.insert(id, (obstacles, colliders));
        }
    }

    /// Fixed colliders for the blocks around every awake body in `name`.
    fn blocks(&mut self, platform: &dyn Platform, name: &str, tick: u64) {
        let world = self.worlds.get_mut(name).expect("made");
        let mut needed: HashSet<(i32, i32, i32)> = HashSet::new();
        for (_, body) in world.rapier.bodies.iter() {
            if !body.is_dynamic() || body.is_sleeping() {
                continue;
            }
            let reach = fv(body.linvel()).length() * SECONDS + 1.0;
            for c in body.colliders() {
                let Some(collider) = world.rapier.colliders.get(*c) else {
                    continue;
                };
                if !collider.collision_groups().filter.contains(BLOCKS) {
                    continue;
                }
                let aabb = collider.compute_aabb();
                let (lo, hi) = (
                    fv(aabb.mins) - Vec3(reach, reach, reach),
                    fv(aabb.maxs) + Vec3(reach, reach, reach),
                );
                let s = |v: f64| (v.floor() as i32).div_euclid(SECTION);
                for x in s(lo.0)..=s(hi.0) {
                    for y in s(lo.1)..=s(hi.1) {
                        for z in s(lo.2)..=s(hi.2) {
                            needed.insert((x, y, z));
                        }
                    }
                }
            }
        }
        for key in &needed {
            let fresh = world
                .sections
                .get(key)
                .is_some_and(|s| tick - s.built < SECTION_TICKS);
            if let Some(s) = world.sections.get_mut(key) {
                s.needed = tick;
            }
            if fresh {
                continue;
            }
            if let Some(old) = world.sections.remove(key) {
                for c in old.colliders {
                    world.remove_collider(c);
                }
            }
            let colliders = build_section(platform, &mut world.rapier, name, *key);
            world.sections.insert(
                *key,
                Section {
                    colliders,
                    built: tick,
                    needed: tick,
                },
            );
        }
        let idle: Vec<(i32, i32, i32)> = world
            .sections
            .iter()
            .filter(|(_, s)| tick - s.needed > SECTION_IDLE)
            .map(|(k, _)| *k)
            .collect();
        for key in idle {
            if let Some(old) = world.sections.remove(&key) {
                for c in old.colliders {
                    world.remove_collider(c);
                }
            }
        }
    }

    /// The collide events a started contact between colliders `a` and `b` makes.
    fn started(&self, name: &str, a: ColliderHandle, b: ColliderHandle) -> Vec<Collision> {
        let Some(world) = self.worlds.get(name) else {
            return Vec::new();
        };
        let w = &world.rapier;
        let owner = |c: ColliderHandle| -> Option<(Uuid, String)> {
            let body = w.colliders.get(c)?.parent()?;
            self.by_handle.get(&(name.to_owned(), body)).cloned()
        };
        let (oa, ob) = (owner(a), owner(b));
        let Some(pair) = w.narrow_phase.contact_pair(a, b) else {
            return Vec::new();
        };
        let Some((manifold, contact)) = pair.find_deepest_contact() else {
            return Vec::new();
        };
        let (Some(c1), Some(_)) = (
            w.colliders.get(pair.collider1),
            w.colliders.get(pair.collider2),
        ) else {
            return Vec::new();
        };
        let position = fv(c1.position().transform_point(contact.local_p1));
        // The normal points from the first collider into the second.
        let normal = fv(manifold.data.normal);
        let first_is_a = pair.collider1 == a;
        let mut out = Vec::new();
        for (me, other, toward) in [(oa.clone(), ob.clone(), first_is_a), (ob, oa, !first_is_a)] {
            let Some(me) = me else { continue };
            // Out of the face it hit, pointing back at it.
            let n = if toward { -normal } else { normal };
            let before = self.bodies.get(&me).map_or(Vec3::ZERO, |e| e.before);
            out.push(Collision {
                instance: me.0,
                node: me.1,
                other,
                position,
                normal: n,
                speed: before.dot(n).abs(),
            });
        }
        out
    }

    /// Each body's pose back onto its node, its velocity and state onto its `BodyState`; what settled or woke.
    fn write_back(&mut self, centities: &mut super::Centities) -> Vec<Settled> {
        let mut settled = Vec::new();
        let keys: Vec<(Uuid, String)> = self.bodies.keys().cloned().collect();
        let mut touched: HashSet<Uuid> = HashSet::new();
        for key in keys {
            let entry = &self.bodies[&key];
            let Some(world) = self.worlds.get_mut(&entry.world) else {
                continue;
            };
            let handle = entry.handle;
            let Some(instance) = centities.get_mut(entry.instance) else {
                continue;
            };
            let Some(index) = instance.index_of(&entry.node) else {
                continue;
            };
            let Some(physics) = instance.definition.nodes[index].physics.clone() else {
                continue;
            };
            // Clamped to its most speed and spin.
            let on_ground = ground(&world.rapier, handle);
            let Some(body) = world.rapier.bodies.get_mut(handle) else {
                continue;
            };
            let mut v = fv(body.linvel());
            if v.length() > physics.max_speed {
                v = v.normalized() * physics.max_speed;
                body.set_linvel(rv(v), false);
            }
            let mut spin = fv(body.angvel());
            let most = physics.max_spin_radians();
            if spin.length() > most {
                spin = spin.normalized() * most;
                body.set_angvel(rv(spin), false);
            }
            let asleep = body.is_sleeping();
            let origin = fv(body.translation());
            let facing = fq(body.rotation());
            let dynamic = body.body_type() != RigidBodyType::Fixed;
            if let Some(Some(s)) = instance.bodies.get_mut(index) {
                s.velocity = v;
                s.angular_velocity = spin;
                s.on_ground = on_ground;
                s.asleep = asleep;
                if s.asleep != s.reported_asleep {
                    s.reported_asleep = s.asleep;
                    settled.push(Settled {
                        instance: entry.instance,
                        node: entry.node.clone(),
                        asleep: s.asleep,
                    });
                }
            }
            if !dynamic {
                continue;
            }
            // The body's pose onto the node, in its parent's frame.
            let world_matrices = instance.placed().to_vec();
            let a = instance.anchor().clone();
            let parent = match instance.definition.nodes[index].parent_index {
                p if p < 0 => instance.turn(),
                p => world_matrices[p as usize],
            };
            let Some(parent_inverse) = parent.invert_affine() else {
                continue;
            };
            let was = pose_of(instance, &world_matrices, index);
            if same_pose(&was, &(origin, facing, was.2)) {
                continue;
            }
            let local = parent_inverse.transform_position(origin - Vec3(a.x, a.y, a.z));
            instance.set(index, Channel::Translation, local, false);
            if physics.rotates {
                let relative = Quat::from_basis(&parent).conjugate() * facing;
                instance.set(index, Channel::Rotation, relative.to_euler(), false);
            }
            let now = instance.placed().to_vec();
            let written = pose_of(instance, &now, index);
            if let Some(Some(s)) = instance.bodies.get_mut(index) {
                s.written = Some(written);
            }
            touched.insert(entry.instance);
        }
        for id in touched {
            if let Some(instance) = centities.get_mut(id) {
                reanchor(instance);
            }
        }
        settled
    }

    /// The Rapier body of instance `id`'s node `node`, when it has one: its mass, and pushing it.
    pub fn with_body<R>(
        &mut self,
        id: Uuid,
        node: &str,
        f: impl FnOnce(&mut rapier3d_f64::prelude::RigidBody) -> R,
    ) -> Option<R> {
        let entry = self.bodies.get(&(id, node.to_owned()))?;
        let body = self
            .worlds
            .get_mut(&entry.world)?
            .rapier
            .bodies
            .get_mut(entry.handle)?;
        Some(f(body))
    }
}

/// Whether body `handle` touches something under it.
fn ground(w: &PhysicsWorld, handle: RigidBodyHandle) -> bool {
    let Some(body) = w.bodies.get(handle) else {
        return false;
    };
    for c in body.colliders() {
        for pair in w.narrow_phase.contact_pairs_with(*c) {
            if !pair.has_any_active_contact() {
                continue;
            }
            let Some((manifold, _)) = pair.find_deepest_contact() else {
                continue;
            };
            let n = fv(manifold.data.normal);
            // From the first collider into the second: up into this body when it's the second.
            let up = if pair.collider2 == *c { n.1 } else { -n.1 };
            if up > GROUND {
                return true;
            }
        }
    }
    false
}

/// Makes a body for a node at `pose` with the collider `boxes` (node space).
fn make_body(
    world: &mut World,
    physics: &ResolvedPhysics,
    state: &BodyState,
    boxes: &[BoundingBox],
    pose: (Vec3, Quat, Vec3),
) -> RigidBodyHandle {
    let (origin, facing, scale) = pose;
    let kind = if !state.enabled {
        RigidBodyType::Fixed
    } else if state.kinematic {
        RigidBodyType::KinematicVelocityBased
    } else {
        RigidBodyType::Dynamic
    };
    let mut builder = RigidBodyBuilder::new(kind)
        .pose(Pose::from_parts(rv(origin), rq(facing)))
        .linvel(rv(state.velocity))
        .angvel(rv(state.angular_velocity))
        .gravity_scale(if state.gravity { physics.gravity } else { 0.0 })
        .linear_damping(damping(physics.drag))
        .angular_damping(damping(physics.angular_drag))
        .can_sleep(physics.sleeps)
        .ccd_enabled(true);
    if !physics.rotates {
        builder = builder.lock_rotations();
    }
    let handle = world.rapier.bodies.insert(builder.build());
    let mass = mass_of(physics, boxes, scale);
    let total: f64 = boxes.iter().map(volume).sum::<f64>().max(1e-9);
    let mut filter = BODIES;
    if physics.blocks {
        filter |= BLOCKS;
    }
    if physics.entities {
        filter |= HITBOXES;
    }
    let groups = InteractionGroups::new(BODIES, filter, InteractionTestMode::And);
    let sphere = physics.shape == Some(PhysicsShape::Sphere);
    let extent = super::hitboxes::bounds_of(boxes, &Matrix4::IDENTITY);
    let parts: Vec<BoundingBox> = if sphere { vec![extent] } else { boxes.to_vec() };
    for b in &parts {
        let half = (b.max - b.min).times(scale) * 0.5;
        let center = ((b.min + b.max) * 0.5).times(scale);
        let shape = if sphere {
            // A sphere fits inside whatever the collider would have been.
            ColliderBuilder::ball(half.0.min(half.1).min(half.2).max(1e-3))
        } else {
            ColliderBuilder::cuboid(
                half.0.abs().max(1e-3),
                half.1.abs().max(1e-3),
                half.2.abs().max(1e-3),
            )
        };
        let share = if sphere { 1.0 } else { volume(b) / total };
        let collider = shape
            .translation(rv(center))
            .mass((mass * share).max(MIN_MASS))
            .friction(physics.friction)
            .friction_combine_rule(CoefficientCombineRule::Min)
            .restitution(physics.bounciness)
            .restitution_combine_rule(CoefficientCombineRule::Max)
            .collision_groups(groups)
            .active_events(ActiveEvents::COLLISION_EVENTS)
            .build();
        let w = &mut world.rapier;
        w.colliders
            .insert_with_parent(collider, handle, &mut w.bodies);
    }
    handle
}

/// The fixed colliders of one section's blocks: every box but a full cube buried among full cubes.
fn build_section(
    platform: &dyn Platform,
    w: &mut PhysicsWorld,
    name: &str,
    key: (i32, i32, i32),
) -> Vec<ColliderHandle> {
    let (x0, y0, z0) = (key.0 * SECTION, key.1 * SECTION, key.2 * SECTION);
    let inside = 1e-6;
    let boxes = platform.worlds().collision_boxes(
        name.to_owned(),
        f64::from(x0),
        f64::from(y0),
        f64::from(z0),
        f64::from(x0 + SECTION) - inside,
        f64::from(y0 + SECTION) - inside,
        f64::from(z0 + SECTION) - inside,
    );
    let cell = |b: &BoundingBox| {
        (
            (b.min.0 + inside).floor() as i32,
            (b.min.1 + inside).floor() as i32,
            (b.min.2 + inside).floor() as i32,
        )
    };
    let full = |b: &BoundingBox| {
        let (x, y, z) = cell(b);
        (b.min - Vec3(f64::from(x), f64::from(y), f64::from(z))).length() < 1e-9
            && (b.max - Vec3(f64::from(x + 1), f64::from(y + 1), f64::from(z + 1))).length() < 1e-9
    };
    let cubes: HashSet<(i32, i32, i32)> = boxes.iter().filter(|b| full(b)).map(cell).collect();
    let buried = |c: (i32, i32, i32)| {
        let within = |v: i32, lo: i32| v > lo && v < lo + SECTION - 1;
        within(c.0, x0)
            && within(c.1, y0)
            && within(c.2, z0)
            && [
                (1, 0, 0),
                (-1, 0, 0),
                (0, 1, 0),
                (0, -1, 0),
                (0, 0, 1),
                (0, 0, -1),
            ]
            .iter()
            .all(|(dx, dy, dz)| cubes.contains(&(c.0 + dx, c.1 + dy, c.2 + dz)))
    };
    let groups = InteractionGroups::new(BLOCKS, BODIES, InteractionTestMode::And);
    let mut out = Vec::new();
    for b in &boxes {
        if full(b) && buried(cell(b)) {
            continue;
        }
        let half = (b.max - b.min) * 0.5;
        let collider =
            ColliderBuilder::cuboid(half.0.max(1e-4), half.1.max(1e-4), half.2.max(1e-4))
                .translation(rv((b.min + b.max) * 0.5))
                .friction(1.0)
                .restitution(0.0)
                .collision_groups(groups)
                .build();
        out.push(w.colliders.insert(collider));
    }
    out
}

/// Keeps a travelling body near its anchor: past `REANCHOR` blocks the anchor moves under it and every root
/// node moves back by as much, so no world position changes. Not while a clip plays.
fn reanchor(instance: &mut Instance) {
    if instance.animations.active() {
        return;
    }
    let world = instance.placed().to_vec();
    let far = instance
        .definition
        .nodes
        .iter()
        .enumerate()
        .filter(|(_, n)| n.physics.is_some())
        .map(|(i, _)| world[i].translation())
        .max_by(|a, b| (a.0 * a.0 + a.2 * a.2).total_cmp(&(b.0 * b.0 + b.2 * b.2)));
    let Some(far) = far else { return };
    if far.0 * far.0 + far.2 * far.2 < REANCHOR * REANCHOR {
        return;
    }
    let shift = Vec3(far.0.floor(), 0.0, far.2.floor());
    let Some(back) = instance
        .turn()
        .invert_affine()
        .map(|m| m.transform_direction(shift))
    else {
        return;
    };
    for index in 0..instance.definition.nodes.len() {
        if instance.definition.nodes[index].is_root() {
            let t = instance.get(index, Channel::Translation);
            instance.set(index, Channel::Translation, t - back, false);
        }
    }
    let a = instance.anchor().clone();
    instance.set_anchor(netherforge_platform::Location {
        x: a.x + shift.0,
        z: a.z + shift.2,
        ..a
    });
    // Every body's pose is what it was: physics needn't move them.
    let now = instance.placed().to_vec();
    for index in 0..instance.definition.nodes.len() {
        if instance.bodies.get(index).is_some_and(Option::is_some) {
            let pose = pose_of(instance, &now, index);
            if let Some(Some(s)) = instance.bodies.get_mut(index) {
                s.written = Some(pose);
            }
        }
    }
}
