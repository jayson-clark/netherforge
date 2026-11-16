//! A centity flying as a projectile (`centity:launch`, Kotlin's `Flight` and `Flights`): homing, gravity
//! and drag change its velocity, one swept question (a capsule of its radius up to the first block in its
//! way) finds what it passes in order (`hit`, its `pierce`), and the block ends it, bounces it or holds it
//! (`flight_end`, `bounce`, `stick`). Runtime state, never saved.

use std::collections::{HashMap, HashSet};

use netherforge_api::handles::{self, Handle};
use netherforge_api::{codecs, shapes, values};
use netherforge_format::math::Vec3;
use netherforge_platform::{BlockHit, Location, Uuid};

use super::Instance;
use super::api::CentityContext;
use super::instance::Channel;
use super::quat::look_rotation;
use super::spatial::{Found, QueryFilter, QueryShape};
use crate::events::{pack, uuid_text};

/// A tick, in seconds.
const SECONDS: f64 = 1.0 / 20.0;
/// A face whose normal points this far up is a floor it can come to rest on.
const FLOOR: f64 = 0.7;
/// How far off a face a bounce leaves it, so the next tick's ray doesn't start inside the block.
const SURFACE: f64 = 0.01;

#[derive(Clone, Debug)]
pub struct Flight {
    /// Blocks per second.
    pub velocity: Vec3,
    /// Blocks per second squared.
    pub gravity: f64,
    pub gravity_delay: i64,
    /// Fraction of speed shed per second.
    pub drag: f64,
    pub radius: f64,
    pub pierce: i64,
    pub rehit: Option<i64>,
    pub filter: QueryFilter,
    pub owner: Option<Handle>,
    pub blocks: bool,
    pub bounce: Option<f64>,
    pub friction: f64,
    pub max_bounces: Option<i64>,
    pub rest_speed: f64,
    pub stick: bool,
    pub homing_range: Option<f64>,
    /// Degrees per second.
    pub homing_turn: f64,
    pub lifetime: i64,
    pub aim: Option<String>,
    pub remove_on_end: bool,
    /// Ticks since it was launched.
    pub age: i64,
    pub bounces: i64,
    /// Stuck in a block or lying where it came to rest: it waits out its lifetime.
    pub still: bool,
    /// What it has hit (instances' and entities' ids) and when.
    pub hit_at: HashMap<Uuid, i64>,
    /// Which launch this is, so a handler that launched it again is told apart from this flight.
    pub number: u64,
}

fn here(instance: &Instance) -> Vec3 {
    let a = instance.anchor();
    Vec3(a.x, a.y, a.z)
}

/// What a flight ended on, for `flight_end`.
#[derive(Default)]
struct Ending {
    target: Option<handles::Centity>,
    entity: Option<handles::Entity>,
    block: Option<handles::Block>,
    normal: Option<Vec3>,
}

impl CentityContext {
    /// The flight instance `id` is on now, and its number.
    fn flight(&self, id: Uuid) -> Option<Flight> {
        self.shared.centities.borrow().get(id)?.flight.clone()
    }

    /// Whether instance `id` is still on flight `number`: a handler may have removed it, stopped it or
    /// launched it again.
    fn on_flight(&self, id: Uuid, number: u64) -> bool {
        self.shared
            .centities
            .borrow()
            .get(id)
            .is_some_and(|i| !i.removed && i.flight.as_ref().is_some_and(|f| f.number == number))
    }

    fn update_flight(&self, id: Uuid, f: impl FnOnce(&mut Flight)) {
        if let Some(flight) = self
            .shared
            .centities
            .borrow_mut()
            .get_mut(id)
            .and_then(|i| i.flight.as_mut())
        {
            f(flight);
        }
    }

    /// Flies instance `id` a tick, when it's flying.
    pub(crate) fn step_flight(&self, id: Uuid) {
        let Some(mut flight) = self.flight(id) else {
            return;
        };
        let number = flight.number;
        flight.age += 1;
        self.update_flight(id, |f| f.age = flight.age);
        let Some(from) = self.shared.centities.borrow().get(id).map(here) else {
            return;
        };
        if flight.age > flight.lifetime {
            return self.end_flight(id, number, "lifetime", from, Ending::default());
        }
        if flight.still {
            return;
        }
        let mut v = flight.velocity;
        if let Some(range) = flight.homing_range {
            v = self.home(id, &flight, v, range);
        }
        if flight.age > flight.gravity_delay {
            v = Vec3(v.0, v.1 - flight.gravity * SECONDS, v.2);
        }
        if flight.drag > 0.0 {
            v = v * (1.0 - flight.drag).powf(SECONDS);
        }
        let to = from + v * SECONDS;
        let world = self
            .shared
            .centities
            .borrow()
            .get(id)
            .map(|i| i.anchor().world.clone())
            .unwrap_or_default();
        let span = (to - from).length();
        let platform = &*self.shared.platform;
        let block = if flight.blocks && span > 1e-9 {
            platform.worlds().raycast_blocks(
                world.clone(),
                from,
                (to - from) * (1.0 / span),
                span,
                false,
            )
        } else {
            None
        };
        let reach = block.as_ref().map_or(to, |b| b.position);
        self.update_flight(id, |f| f.velocity = v);
        // What it passes on its way to the block (or to where it goes), in order.
        if span > 1e-9 || flight.radius > 0.0 {
            let mut filter = flight.filter.clone();
            filter.exclude_centities.insert(id);
            let shape = QueryShape::Capsule {
                from,
                to: reach,
                radius: flight.radius,
            };
            let found = self
                .shared
                .centities
                .borrow_mut()
                .find(platform, &world, &shape, &filter);
            for found in found {
                let thing = match &found {
                    Found::Centity { instance, .. } => *instance,
                    Found::Entity { id, .. } => *id,
                };
                let age = flight.age;
                let again = match self.flight(id).and_then(|f| f.hit_at.get(&thing).copied()) {
                    None => true,
                    Some(last) => flight.rehit.is_some_and(|r| age - last >= r),
                };
                if !again {
                    continue;
                }
                self.update_flight(id, |f| {
                    f.hit_at.insert(thing, age);
                });
                let position = found.reach().position;
                let (target, node, entity) = match &found {
                    Found::Centity { instance, node, .. } => {
                        let name = self
                            .shared
                            .centities
                            .borrow()
                            .get(*instance)
                            .and_then(|i| i.definition.nodes.get(*node).map(|n| n.name.clone()))
                            .unwrap_or_default();
                        (
                            Some(handles::Centity {
                                id: uuid_text(instance),
                            }),
                            Some(Self::node_handle(&uuid_text(instance), &name)),
                            None,
                        )
                    }
                    Found::Entity { id: e, .. } => {
                        (None, None, Some(handles::Entity { id: uuid_text(e) }))
                    }
                };
                let event = shapes::FlightHitEvent {
                    centity: handles::Centity { id: uuid_text(&id) },
                    target: target.clone(),
                    node,
                    entity: entity.clone(),
                    position: values::Vec3 {
                        x: position.0,
                        y: position.1,
                        z: position.2,
                    },
                    velocity: values::Vec3 {
                        x: v.0,
                        y: v.1,
                        z: v.2,
                    },
                    owner: flight.owner.clone(),
                };
                let passed = self
                    .raise_on(id, "hit", codecs::FlightHitEvent, event)
                    .is_some_and(|d| d.cancelled);
                if !self.on_flight(id, number) {
                    return;
                }
                if passed {
                    continue;
                }
                let pierce = self.flight(id).map_or(0, |f| f.pierce);
                if pierce > 0 {
                    self.update_flight(id, |f| f.pierce -= 1);
                    continue;
                }
                if !self.move_flight(id, number, position) {
                    return;
                }
                return self.end_flight(
                    id,
                    number,
                    "hit",
                    position,
                    Ending {
                        target,
                        entity,
                        ..Ending::default()
                    },
                );
            }
        }
        if let Some(block) = block {
            return self.meet(id, number, &world, block, v);
        }
        if self.move_flight(id, number, to) {
            self.aim_flight(id);
        }
    }

    /// It meets `block` going at `v`: it sticks, bounces or ends there.
    fn meet(&self, id: Uuid, number: u64, world: &str, block: BlockHit, v: Vec3) {
        let Some(flight) = self.flight(id) else {
            return;
        };
        let n = block.normal;
        let into = v.dot(n);
        let handle = handles::Block {
            world: world.to_owned(),
            position: pack(block.x, block.y, block.z),
        };
        let payload = shapes::FlightBlockEvent {
            centity: handles::Centity { id: uuid_text(&id) },
            block: handle.clone(),
            position: values::Vec3 {
                x: block.position.0,
                y: block.position.1,
                z: block.position.2,
            },
            normal: values::Vec3 {
                x: n.0,
                y: n.1,
                z: n.2,
            },
            speed: -into,
            owner: flight.owner.clone(),
        };
        if flight.stick {
            if !self.move_flight(id, number, block.position) {
                return;
            }
            self.update_flight(id, |f| {
                f.velocity = Vec3::ZERO;
                f.still = true;
            });
            self.raise_on(id, "stick", codecs::FlightBlockEvent, payload);
            return;
        }
        let bounce = match flight.bounce {
            Some(b) if flight.max_bounces.is_none_or(|m| flight.bounces < m) => b,
            _ => {
                if !self.move_flight(id, number, block.position) {
                    return;
                }
                let ending = Ending {
                    block: Some(handle),
                    normal: Some(n),
                    ..Ending::default()
                };
                return self.end_flight(id, number, "block", block.position, ending);
            }
        };
        // Off the face: what went into it comes back by `bounce`, what went along it keeps `friction`.
        let normal_part = n * into;
        let along = v - normal_part;
        let mut out = along * flight.friction - normal_part * bounce;
        let mut still = false;
        if n.1 > FLOOR && out.length() < flight.rest_speed {
            out = Vec3::ZERO;
            still = true;
        }
        self.update_flight(id, |f| {
            f.bounces += 1;
            f.velocity = out;
            f.still = still;
        });
        if !self.move_flight(id, number, block.position + n * SURFACE) {
            return;
        }
        self.raise_on(id, "bounce", codecs::FlightBlockEvent, payload);
        if self.on_flight(id, number) {
            self.aim_flight(id);
        }
    }

    /// Turns `v` toward the nearest thing it can hit within `range`, by at most its turn rate.
    fn home(&self, id: Uuid, flight: &Flight, v: Vec3, range: f64) -> Vec3 {
        let speed = v.length();
        if speed < 1e-9 {
            return v;
        }
        let Some((from, world)) = self
            .shared
            .centities
            .borrow()
            .get(id)
            .map(|i| (here(i), i.anchor().world.clone()))
        else {
            return v;
        };
        let mut filter = flight.filter.clone();
        filter.exclude_centities.insert(id);
        filter.limit = 1;
        let shape = QueryShape::Sphere {
            center: from,
            radius: range,
        };
        let platform = &*self.shared.platform;
        let Some(target) = self
            .shared
            .centities
            .borrow_mut()
            .find(platform, &world, &shape, &filter)
            .into_iter()
            .next()
        else {
            return v;
        };
        let toward = target.reach().position - from;
        let distance = toward.length();
        if distance < 1e-9 {
            return v;
        }
        let want = toward * (1.0 / distance);
        let now = v * (1.0 / speed);
        let angle = now.dot(want).clamp(-1.0, 1.0).acos();
        let most = flight.homing_turn.to_radians() * SECONDS;
        if angle <= most {
            return want * speed;
        }
        // Rotate toward it in the plane of the two, by `most`.
        let axis = now.cross(want);
        let length = axis.length();
        if length < 1e-9 {
            return v;
        }
        let axis = axis * (1.0 / length);
        let turned = now * most.cos()
            + axis.cross(now) * most.sin()
            + axis * (axis.dot(now) * (1.0 - most.cos()));
        turned * speed
    }

    /// Moves its anchor to `to`; ends it `unloaded` (and answers false) where its entities couldn't stand.
    fn move_flight(&self, id: Uuid, number: u64, to: Vec3) -> bool {
        let Some(next) = self.shared.centities.borrow().get(id).map(|i| Location {
            x: to.0,
            y: to.1,
            z: to.2,
            ..i.anchor().clone()
        }) else {
            return false;
        };
        if !self.shared.platform.worlds().entities_loaded(next.clone()) {
            let at = self.shared.centities.borrow().get(id).map_or(to, here);
            self.end_flight(id, number, "unloaded", at, Ending::default());
            return false;
        }
        if let Some(i) = self.shared.centities.borrow_mut().get_mut(id) {
            i.set_anchor(next);
        }
        true
    }

    /// Points its `aim` node's +Z along its velocity.
    pub(crate) fn aim_flight(&self, id: Uuid) {
        let mut centities = self.shared.centities.borrow_mut();
        let Some(instance) = centities.get_mut(id) else {
            return;
        };
        let Some(flight) = &instance.flight else {
            return;
        };
        let (Some(node), v) = (flight.aim.clone(), flight.velocity) else {
            return;
        };
        let Some(index) = instance.index_of(&node) else {
            return;
        };
        if v.length() < 1e-6 {
            return;
        }
        let at = instance.world_matrix(index).translation();
        let Some(inverse) = instance.parent_world_matrix(index).invert_affine() else {
            return;
        };
        let target = inverse.transform_position(at + v);
        let forward = target - instance.get(index, Channel::Translation);
        let length = forward.length();
        if length < 1e-9 {
            return;
        }
        instance.set(
            index,
            Channel::Rotation,
            look_rotation(forward * (1.0 / length)),
            false,
        );
    }

    /// Ends instance `id`'s flight `number`: `flight_end`, then, unless told not to, it's removed.
    fn end_flight(&self, id: Uuid, number: u64, reason: &str, position: Vec3, ending: Ending) {
        let flight = {
            let mut centities = self.shared.centities.borrow_mut();
            let Some(instance) = centities.get_mut(id) else {
                return;
            };
            if !instance.flight.as_ref().is_some_and(|f| f.number == number) {
                return;
            }
            instance.flight.take().expect("flying")
        };
        let event = shapes::FlightEndEvent {
            centity: handles::Centity { id: uuid_text(&id) },
            reason: reason.to_owned(),
            position: values::Vec3 {
                x: position.0,
                y: position.1,
                z: position.2,
            },
            target: ending.target,
            entity: ending.entity,
            block: ending.block,
            normal: ending.normal.map(|n| values::Vec3 {
                x: n.0,
                y: n.1,
                z: n.2,
            }),
            owner: flight.owner.clone(),
        };
        self.raise_on(id, "flight_end", codecs::FlightEndEvent, event);
        let flying_again = self
            .shared
            .centities
            .borrow()
            .get(id)
            .is_none_or(|i| i.removed || i.flight.is_some());
        if flight.remove_on_end && !flying_again {
            CentityContext::remove(self, id);
        }
    }

    /// `stop_flight`: ends it where it is.
    pub(crate) fn stop_flight_of(&self, id: Uuid) -> bool {
        let Some((number, at)) = self
            .shared
            .centities
            .borrow()
            .get(id)
            .and_then(|i| Some((i.flight.as_ref()?.number, here(i))))
        else {
            return false;
        };
        self.end_flight(id, number, "stopped", at, Ending::default());
        true
    }
}

/// A question's options as a filter (Kotlin's `queryFilter`): a kind names a centity or an entity type.
pub fn query_filter(options: &shapes::QueryOptions) -> Result<QueryFilter, String> {
    if let Some(limit) = options.limit
        && limit < 1
    {
        return Err(format!(
            "bad argument 'options.limit' (at least 1 expected, got {limit})"
        ));
    }
    let kinds = options.kinds.as_ref().map(|kinds| {
        let mut out = HashSet::new();
        for kind in kinds {
            if netherforge_format::game::is_id(kind) {
                out.insert(netherforge_format::game::normalize(kind));
            }
            out.insert(kind.clone());
        }
        out
    });
    let exclude = options.exclude.clone().unwrap_or_default();
    Ok(QueryFilter {
        centities: options.centities != Some(false),
        players: options.players != Some(false),
        entities: options.entities != Some(false),
        kinds,
        exclude_centities: exclude
            .iter()
            .filter_map(|h| match h {
                Handle::Centity(c) => crate::events::parse_uuid(&c.id),
                _ => None,
            })
            .collect(),
        exclude_entities: exclude
            .iter()
            .filter_map(|h| match h {
                Handle::Entity(e) => crate::events::parse_uuid(&e.id),
                Handle::Player(p) => crate::events::parse_uuid(&p.id),
                Handle::Living(l) => crate::events::parse_uuid(&l.id),
                Handle::Mob(m) => crate::events::parse_uuid(&m.id),
                _ => None,
            })
            .collect(),
        line_of_sight: options.line_of_sight == Some(true),
        limit: options.limit.map_or(usize::MAX, |l| l as usize),
    })
}
