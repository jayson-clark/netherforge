//! Centities walking (or flying) where `centity:move_to` sends them (Kotlin's `CentityPaths`, `Walk` and
//! `Doors`): our own A* over the world's collision shapes (`pathing`), and a step along the path each tick,
//! after animation and before physics. Searching is bounded: one search by its range and `NODE_LIMIT`
//! places, and the new paths walks look for on their own share `TICK_NODES` places a tick. `move_to` always
//! searches at once, since it says whether there's a way. Walks aren't saved; a reload and a teleport drop
//! one without `path_end`, as `stop_pathing` does.

use std::collections::HashSet;

use netherforge_api::{codecs, handles, shapes};
use netherforge_format::game::{GameData, Registry};
use netherforge_format::math::{BoundingBox, Matrix4, Vec3};
use netherforge_platform::{Location, Platform, Uuid};

use super::Instance;
use super::api::CentityContext;
use super::pathing::{self, PathResult, SearchLimits, Space, Walker};
use super::world_shapes::{BlockShapes, BrickShapes, RegionShapes};
use crate::events::uuid_text;

/// How close it has to come to count as reached: `PATH_REACH` in `spec/constants.ts`.
pub const PATH_REACH: f64 = 2.0;
/// How often a walk after something looks for a new path to it.
pub const REPATH_TICKS: i64 = 10;
/// The defaults and limits of `CentityPathOptions`.
pub const DEFAULT_SPEED: f64 = 4.3;
pub const MAX_SPEED: f64 = 20.0;
pub const DEFAULT_STEP_HEIGHT: f64 = 1.0;
pub const DEFAULT_MAX_DROP: f64 = 3.0;
pub const DEFAULT_RANGE: f64 = 48.0;
pub const MAX_RANGE: f64 = 128.0;
pub const MAX_WIDTH: f64 = 8.0;
pub const MAX_HEIGHT: f64 = 16.0;
/// How far below its feet a walk looks for ground to start from.
const START_DROP: f64 = 64.0;
/// Places the walks finding new paths on their own may search in one tick, together.
const TICK_NODES: usize = 16384;
/// How far it turns a tick when it faces its way, in degrees.
const TURN_PER_TICK: f64 = 30.0;
/// Minecraft's gravity and fastest fall, in blocks a second (squared).
const GRAVITY: f64 = 32.0;
const MAX_FALL: f64 = 78.4;
/// How far what it goes after must move before it looks for a new path to it.
const TARGET_MOVED: f64 = 1.0;
/// How much closer a new path must end for a partial walk to carry on.
const PROGRESS: f64 = 0.5;
/// Moving less than this in a tick is standing still.
const STILL: f64 = 1e-3;
/// Ticks standing still before it looks for another way.
const STUCK_TICKS: i32 = 40;
/// New ways in a row it may look for without reaching a point of one, before it gives up.
const MAX_REPATHS: i32 = 3;
/// The smallest measured walker.
const MIN_SIZE: f64 = 0.1;
const SECONDS: f64 = 1.0 / 20.0;

/// What a walk goes to: a place in the centity's world, or something it goes after.
#[derive(Clone, Debug, PartialEq)]
pub enum PathTarget {
    Place(Vec3),
    Entity(Uuid),
    Centity(Uuid),
}

/// How a walk goes, from `move_to`'s options, checked. `speed` is in blocks a second.
#[derive(Clone, Debug, PartialEq)]
pub struct PathSettings {
    pub speed: f64,
    pub fly: bool,
    pub face: bool,
    pub width: Option<f64>,
    pub height: Option<f64>,
    pub step_height: f64,
    pub max_drop: f64,
    pub range: f64,
    pub open_doors: bool,
}

impl Default for PathSettings {
    fn default() -> PathSettings {
        PathSettings {
            speed: DEFAULT_SPEED,
            fly: false,
            face: true,
            width: None,
            height: None,
            step_height: DEFAULT_STEP_HEIGHT,
            max_drop: DEFAULT_MAX_DROP,
            range: DEFAULT_RANGE,
            open_doors: false,
        }
    }
}

/// One centity's walk: where it's going, the path it's on and how far along, and where its feet are.
#[derive(Clone, Debug)]
pub struct Walk {
    pub target: PathTarget,
    pub settings: PathSettings,
    pub walker: Walker,
    pub feet: Vec3,
    pub goal: Vec3,
    pub points: Vec<Vec3>,
    pub complete: bool,
    /// The point it's heading for.
    pub next: usize,
    /// How fast it's falling, blocks a second (walking only).
    pub falling: f64,
    pub age: i64,
    pub stuck: i32,
    pub repaths: i32,
}

impl Walk {
    fn follow(&mut self, path: PathResult) {
        self.points = path.points;
        self.complete = path.complete;
        self.next = 1;
        self.stuck = 0;
    }
}

/// One block of a door (or a fence gate) a walk opened.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct Doorway {
    pub x: i32,
    pub y: i32,
    pub z: i32,
}

impl Doorway {
    /// Whether `feet` stand in this door's column of blocks.
    fn holds(&self, feet: Vec3) -> bool {
        feet.0.floor() as i32 == self.x && feet.2.floor() as i32 == self.z
    }
}

const DOOR_TAGS: &[&str] = &["minecraft:wooden_doors", "minecraft:fence_gates"];
/// How far apart the places along its path it looks at are, in blocks.
const DOOR_STEP: f64 = 0.25;
/// How near its feet a door on its way is opened, in blocks.
const DOOR_REACH: f64 = 1.6;
/// How far it has gone from a door it opened before shutting it, in blocks.
const DOOR_LEAVE: f64 = 2.0;
/// A door's two blocks, and a gate's one.
const DOOR_HEIGHT: i32 = 2;

/// The game's wooden doors and fence gates for walks that go through them (`open_doors`).
struct Doors<'p> {
    platform: &'p dyn Platform,
    kinds: HashSet<String>,
}

impl<'p> Doors<'p> {
    fn new(platform: &'p dyn Platform) -> Self {
        let game: &dyn GameData = platform.game();
        let kinds = DOOR_TAGS
            .iter()
            .filter_map(|tag| game.tag_of(Registry::Block, tag))
            .flatten()
            .cloned()
            .collect();
        Doors { platform, kinds }
    }

    /// The state at a block when it's one of the doors a walk can open.
    fn door_at(&self, world: &str, x: i32, y: i32, z: i32) -> Option<String> {
        let state = self.platform.blocks().get(world.to_owned(), x, y, z)?.state;
        let kind = state.split('[').next().unwrap_or_default();
        self.kinds.contains(kind).then_some(state)
    }

    /// Opens the shut doors on `walk`'s way within reach of its feet, and shuts the ones it opened and has left
    /// behind.
    fn pass(&self, world: &str, walk: &Walk, opened: &mut Vec<Doorway>) {
        let feet = walk.feet;
        let (mut at, mut left, mut next) = (feet, DOOR_REACH, walk.next);
        let mut seen = HashSet::new();
        while left > 0.0 {
            for dy in 0..DOOR_HEIGHT {
                let cell = Doorway {
                    x: at.0.floor() as i32,
                    y: at.1.floor() as i32 + dy,
                    z: at.2.floor() as i32,
                };
                if seen.insert(cell) {
                    self.open(world, opened, cell);
                }
            }
            if next >= walk.points.len() {
                break;
            }
            let to = walk.points[next];
            let d = (to - at).length();
            if d <= DOOR_STEP {
                at = to;
                next += 1;
                left -= d;
            } else {
                at = at + (to - at) * (DOOR_STEP / d);
                left -= DOOR_STEP;
            }
        }
        self.shut(world, opened, &|d| {
            (Vec3(f64::from(d.x) + 0.5, f64::from(d.y), f64::from(d.z) + 0.5) - feet).length()
                > DOOR_LEAVE
        });
    }

    fn open(&self, world: &str, opened: &mut Vec<Doorway>, cell: Doorway) {
        let Some(state) = self.door_at(world, cell.x, cell.y, cell.z) else {
            return;
        };
        if !state.contains("open=false") {
            return;
        }
        if self.platform.blocks().set(
            world.to_owned(),
            cell.x,
            cell.y,
            cell.z,
            state.replace("open=false", "open=true"),
            false,
        ) {
            opened.push(cell);
        }
    }

    /// Shuts the doors in `opened` that are `done` with, and forgets them.
    fn shut(&self, world: &str, opened: &mut Vec<Doorway>, done: &dyn Fn(&Doorway) -> bool) {
        let mut kept = Vec::new();
        for door in opened.drain(..) {
            if !done(&door) {
                kept.push(door);
                continue;
            }
            if let Some(state) = self.door_at(world, door.x, door.y, door.z)
                && state.contains("open=true")
            {
                self.platform.blocks().set(
                    world.to_owned(),
                    door.x,
                    door.y,
                    door.z,
                    state.replace("open=true", "open=false"),
                    false,
                );
            }
        }
        *opened = kept;
    }
}

/// `shapes` with every door seen as open: what a search for a walk that opens them goes through.
struct Through<'a> {
    doors: &'a Doors<'a>,
    world: &'a str,
    shapes: &'a dyn BlockShapes,
}

impl BlockShapes for Through<'_> {
    fn boxes(&self, x: i32, y: i32, z: i32) -> Option<Vec<BoundingBox>> {
        let found = self.shapes.boxes(x, y, z)?;
        Some(
            if !found.is_empty() && self.doors.door_at(self.world, x, y, z).is_some() {
                Vec::new()
            } else {
                found
            },
        )
    }
}

fn across(a: Vec3, b: Vec3) -> f64 {
    let (dx, dz) = (b.0 - a.0, b.2 - a.2);
    (dx * dx + dz * dz).sqrt()
}

fn union(a: &BoundingBox, b: &BoundingBox) -> BoundingBox {
    BoundingBox {
        min: Vec3(
            a.min.0.min(b.min.0),
            a.min.1.min(b.min.1),
            a.min.2.min(b.min.2),
        ),
        max: Vec3(
            a.max.0.max(b.max.0),
            a.max.1.max(b.max.1),
            a.max.2.max(b.max.2),
        ),
    }
}

/// The box round `instance`'s shown hitboxes, in its own space.
fn bounds_of(game: &dyn GameData, instance: &Instance) -> Option<BoundingBox> {
    let composed = instance.definition.world(Some(instance.shown_pose()));
    let mut bounds: Option<BoundingBox> = None;
    for (index, node) in instance.definition.nodes.iter().enumerate() {
        if node.hitbox.is_none() || !instance.shown(index) {
            continue;
        }
        let b =
            super::hitboxes::bounds_of(&super::boxes_of(game, instance, index), &composed[index]);
        bounds = Some(bounds.map_or(b, |o| union(&o, &b)));
    }
    bounds
}

/// Where `instance`'s feet are in the world: the middle of the bottom of its hitboxes, or its anchor.
pub fn feet_of(game: &dyn GameData, instance: &Instance) -> Vec3 {
    feet_at(instance, bounds_of(game, instance))
}

fn feet_at(instance: &Instance, bounds: Option<BoundingBox>) -> Vec3 {
    let a = instance.anchor();
    let middle = bounds.map_or(Vec3::ZERO, |b| {
        instance.turn().transform_direction(Vec3(
            (b.min.0 + b.max.0) / 2.0,
            0.0,
            (b.min.2 + b.max.2) / 2.0,
        ))
    });
    Vec3(
        a.x + middle.0,
        a.y + bounds.map_or(0.0, |b| b.min.1),
        a.z + middle.2,
    )
}

impl CentityContext {
    fn search(
        &self,
        world: &str,
        walker: Walker,
        from: Vec3,
        goal: Vec3,
        settings: &PathSettings,
        budgeted: bool,
    ) -> PathResult {
        let platform = &*self.shared.platform;
        let bricks = BrickShapes::new(platform.worlds(), world);
        let doors = Doors::new(platform);
        let through = Through {
            doors: &doors,
            world,
            shapes: &bricks,
        };
        let shapes: &dyn BlockShapes = if settings.open_doors {
            &through
        } else {
            &bricks
        };
        let space = Space::new(shapes, walker);
        let limits = SearchLimits::new(settings.range);
        let path = if settings.fly {
            pathing::air(&space, from, goal, limits)
        } else {
            pathing::ground(
                &space,
                from,
                goal,
                settings.step_height,
                settings.max_drop,
                START_DROP,
                limits,
            )
        };
        if budgeted {
            self.shared
                .path_nodes
                .set(self.shared.path_nodes.get() + path.expanded);
        }
        path
    }

    fn budget_left(&self) -> bool {
        self.shared.path_nodes.get() < TICK_NODES
    }

    /// Where `target` is now, in instance `id`'s world; none when it's gone or elsewhere.
    fn locate(&self, id: Uuid, target: &PathTarget) -> Option<Vec3> {
        let centities = self.shared.centities.borrow();
        let world = centities.get(id)?.anchor().world.clone();
        match target {
            PathTarget::Place(at) => Some(*at),
            PathTarget::Entity(e) => {
                let at = self.shared.platform.world_entities().info(*e)?.location;
                (at.world == world).then_some(Vec3(at.x, at.y, at.z))
            }
            PathTarget::Centity(c) => {
                let other = centities
                    .get(*c)
                    .filter(|o| !o.removed && *c != id && o.anchor().world == world)?;
                let a = other.anchor();
                Some(Vec3(a.x, a.y, a.z))
            }
        }
    }

    /// Sends instance `id` to `target`, replacing any walk it was on (without `path_end`). False when there's no
    /// way any closer, or `target` is gone, in another world or out of range.
    pub(crate) fn start_walk(&self, id: Uuid, target: PathTarget, settings: PathSettings) -> bool {
        if let Some(i) = self.shared.centities.borrow_mut().get_mut(id) {
            i.walk = None;
        }
        let Some(goal) = self.locate(id, &target) else {
            return false;
        };
        let game = self.shared.platform.game();
        let Some((feet, walker, world)) = self.shared.centities.borrow().get(id).map(|i| {
            let bounds = bounds_of(game, i);
            let size = bounds.map(|b| b.max - b.min);
            let width = settings
                .width
                .or(size.map(|s| s.0.max(s.2).max(MIN_SIZE)))
                .unwrap_or(1.0);
            let height = settings
                .height
                .or(size.map(|s| s.1.max(MIN_SIZE)))
                .unwrap_or(1.0);
            (
                feet_at(i, bounds),
                Walker { width, height },
                i.anchor().world.clone(),
            )
        }) else {
            return false;
        };
        if (feet - goal).length() > settings.range {
            return false;
        }
        let path = self.search(&world, walker, feet, goal, &settings, false);
        if path.points.is_empty() {
            return false;
        }
        if let Some(i) = self.shared.centities.borrow_mut().get_mut(id) {
            i.walk = Some(Walk {
                target,
                settings,
                walker,
                feet,
                goal,
                points: path.points,
                complete: path.complete,
                next: 1,
                falling: 0.0,
                age: 0,
                stuck: 0,
                repaths: 0,
            });
        }
        true
    }

    fn walk_of(&self, id: Uuid) -> Option<Walk> {
        self.shared.centities.borrow().get(id)?.walk.clone()
    }

    fn put_walk(&self, id: Uuid, walk: Walk) {
        if let Some(i) = self.shared.centities.borrow_mut().get_mut(id)
            && i.walk.is_some()
        {
            i.walk = Some(walk);
        }
    }

    /// Moves instance `id` one tick along its walk, if it's on one.
    pub(crate) fn step_walk(&self, id: Uuid) {
        let Some(mut walk) = self.walk_of(id) else {
            return;
        };
        walk.age += 1;
        if !matches!(walk.target, PathTarget::Place(_)) {
            let Some(at) = self.locate(id, &walk.target) else {
                return self.end_walk(id, false);
            };
            if (walk.feet - at).length() <= PATH_REACH {
                return self.end_walk(id, true);
            }
            if walk.age % REPATH_TICKS == 0 && (at - walk.goal).length() > TARGET_MOVED {
                walk.goal = at;
                // Out of budget, it carries on along the old path and tries again later.
                if self.budget_left() && !self.repath(id, &mut walk, false) {
                    return;
                }
            }
        }
        self.put_walk(id, walk.clone());
        self.move_walk(id, walk);
    }

    fn move_walk(&self, id: Uuid, mut walk: Walk) {
        let platform = &*self.shared.platform;
        let settings = walk.settings.clone();
        let from = walk.feet;
        let world = match self.shared.centities.borrow().get(id) {
            Some(i) => i.anchor().world.clone(),
            None => return,
        };
        if settings.open_doors {
            let doors = Doors::new(platform);
            if let Some(i) = self.shared.centities.borrow_mut().get_mut(id) {
                doors.pass(&world, &walk, &mut i.opened_doors);
            }
        }
        // Where it passes this tick: each point of the path it reaches, then where it stops.
        let mut passed = vec![from];
        let mut next = walk.next;
        let mut left = settings.speed * SECONDS;
        while left > 1e-9 && next < walk.points.len() {
            let at = *passed.last().expect("a start");
            let to = walk.points[next];
            let d = if settings.fly {
                (to - at).length()
            } else {
                across(at, to)
            };
            if d <= left {
                passed.push(if settings.fly {
                    to
                } else {
                    Vec3(to.0, at.1, to.2)
                });
                left -= d;
                next += 1;
            } else {
                let s = left / d;
                passed.push(Vec3(
                    at.0 + (to.0 - at.0) * s,
                    if settings.fly {
                        at.1 + (to.1 - at.1) * s
                    } else {
                        at.1
                    },
                    at.2 + (to.2 - at.2) * s,
                ));
                left = 0.0;
            }
        }
        let fall = if settings.fly {
            0.0
        } else {
            (walk.falling + GRAVITY * SECONDS).min(MAX_FALL) * SECONDS
        };
        let feet = {
            let half = walk.walker.width / 2.0;
            let (mut lo, mut hi) = (
                Vec3(f64::MAX, f64::MAX, f64::MAX),
                Vec3(-f64::MAX, -f64::MAX, -f64::MAX),
            );
            for p in &passed {
                lo = Vec3(lo.0.min(p.0), lo.1.min(p.1), lo.2.min(p.2));
                hi = Vec3(hi.0.max(p.0), hi.1.max(p.1), hi.2.max(p.2));
            }
            let climb = if settings.fly {
                0.0
            } else {
                settings.step_height
            };
            let shapes = RegionShapes::around(
                platform.worlds(),
                &world,
                Vec3(lo.0 - half, lo.1 - fall - 1.0, lo.2 - half),
                Vec3(
                    hi.0 + half,
                    hi.1 + climb + walk.walker.height + 1.0,
                    hi.2 + half,
                ),
            );
            let space = Space::new(&shapes, walk.walker);
            if settings.fly {
                fly(&space, &passed)
            } else {
                walk_along(&space, &mut walk, &passed, fall)
            }
        };
        let Some(feet) = feet else {
            self.put_walk(id, walk.clone());
            return self.blocked(id, walk);
        };
        let grounded = settings.fly || walk.falling == 0.0;
        if !self.place(id, &mut walk, feet) {
            self.put_walk(id, walk.clone());
            return self.blocked(id, walk);
        }
        if next > walk.next {
            walk.repaths = 0;
        }
        walk.next = next;
        if (from - feet).length() < STILL && grounded {
            walk.stuck += 1;
            if walk.stuck >= STUCK_TICKS {
                self.put_walk(id, walk.clone());
                return self.blocked(id, walk);
            }
        } else {
            walk.stuck = 0;
        }
        self.put_walk(id, walk.clone());
        if walk.next >= walk.points.len() && grounded {
            self.arrived(id, walk);
        }
    }

    /// Puts its feet at `feet`, turning it towards the way it went (about its feet) when it faces its way.
    /// False, moving nothing, when it would land in a chunk whose entities aren't loaded.
    fn place(&self, id: Uuid, walk: &mut Walk, feet: Vec3) -> bool {
        let from = walk.feet;
        if feet == from {
            return true;
        }
        let mut centities = self.shared.centities.borrow_mut();
        let Some(instance) = centities.get_mut(id) else {
            return false;
        };
        let mut yaw = instance.yaw();
        let (dx, dz) = (feet.0 - from.0, feet.2 - from.2);
        if walk.settings.face && dx * dx + dz * dz > 1e-8 {
            let want = (-dx).atan2(dz).to_degrees();
            yaw += super::instance::wrap_degrees(want - yaw).clamp(-TURN_PER_TICK, TURN_PER_TICK);
        }
        let anchor = instance.anchor().clone();
        let mut offset = Vec3(anchor.x - from.0, anchor.y - from.1, anchor.z - from.2);
        if yaw != instance.yaw() {
            let mut turn = Matrix4::IDENTITY;
            turn.rotate_y(-(yaw - instance.yaw()).to_radians());
            offset = turn.transform_direction(offset);
        }
        let next = feet + offset;
        let moved = Location {
            x: next.0,
            y: next.1,
            z: next.2,
            ..anchor
        };
        if !self.shared.platform.worlds().entities_loaded(moved.clone()) {
            return false;
        }
        if yaw != instance.yaw() {
            instance.turn_to(yaw);
        }
        instance.set_anchor(moved);
        walk.feet = feet;
        true
    }

    /// It walked its path to the end.
    fn arrived(&self, id: Uuid, mut walk: Walk) {
        let distance = (walk.feet - walk.goal).length();
        if matches!(walk.target, PathTarget::Place(_)) && (walk.complete || distance <= PATH_REACH)
        {
            return self.end_walk(id, distance <= PATH_REACH);
        }
        // A partial path, or something it goes after that moved on: the rest of the way, if any closer.
        if !self.budget_left() {
            return;
        }
        self.repath(id, &mut walk, true);
    }

    /// Something is in its way or it hasn't moved for a while: another way, or it gives up.
    fn blocked(&self, id: Uuid, mut walk: Walk) {
        if !self.budget_left() {
            return;
        }
        walk.repaths += 1;
        if walk.repaths > MAX_REPATHS {
            return self.end_walk(id, false);
        }
        self.put_walk(id, walk.clone());
        self.repath(id, &mut walk, false);
    }

    /// Finds `walk` a new path from where it is. False, having ended the walk, when there's none (or, with
    /// `needs_progress`, none that ends any closer).
    fn repath(&self, id: Uuid, walk: &mut Walk, needs_progress: bool) -> bool {
        let Some(world) = self
            .shared
            .centities
            .borrow()
            .get(id)
            .map(|i| i.anchor().world.clone())
        else {
            return false;
        };
        let path = self.search(
            &world,
            walk.walker,
            walk.feet,
            walk.goal,
            &walk.settings,
            true,
        );
        let here = (walk.feet - walk.goal).length();
        let closer = path
            .points
            .last()
            .is_some_and(|end| (*end - walk.goal).length() < here - PROGRESS);
        if path.points.is_empty() || (needs_progress && !closer) {
            self.end_walk(id, here <= PATH_REACH);
            return false;
        }
        walk.follow(path);
        self.put_walk(id, walk.clone());
        true
    }

    /// Shuts the doors its walks opened that it isn't standing in, now that it isn't walking.
    pub(crate) fn shut_behind(&self, id: Uuid) {
        let platform = &*self.shared.platform;
        let mut centities = self.shared.centities.borrow_mut();
        let Some(instance) = centities.get_mut(id) else {
            return;
        };
        if instance.walk.is_some() || instance.opened_doors.is_empty() {
            return;
        }
        let feet = feet_of(platform.game(), instance);
        let world = instance.anchor().world.clone();
        Doors::new(platform).shut(&world, &mut instance.opened_doors, &|d| !d.holds(feet));
    }

    /// Shuts every door its walks opened, wherever it is: it has been teleported away, or removed.
    pub(crate) fn shut_all(&self, id: Uuid) {
        let platform = &*self.shared.platform;
        let mut centities = self.shared.centities.borrow_mut();
        let Some(instance) = centities.get_mut(id) else {
            return;
        };
        if instance.opened_doors.is_empty() {
            return;
        }
        let world = instance.anchor().world.clone();
        Doors::new(platform).shut(&world, &mut instance.opened_doors, &|_| true);
    }

    /// Its walk ended: `path_end`, once its doors are shut, so a handler can send it off again.
    fn end_walk(&self, id: Uuid, reached: bool) {
        self.shut_all(id);
        if let Some(i) = self.shared.centities.borrow_mut().get_mut(id) {
            i.walk = None;
        }
        let event = shapes::CentityPathEndEvent {
            centity: handles::Centity { id: uuid_text(&id) },
            reached,
        };
        self.raise_on(id, "path_end", codecs::CentityPathEndEvent, event);
    }
}

/// Where a flight along `passed` ends, or none when something is in the way of any stretch of it.
fn fly(space: &Space, passed: &[Vec3]) -> Option<Vec3> {
    for w in passed.windows(2) {
        if !space.clear_flying(w[0], w[1]) {
            return None;
        }
    }
    passed.last().copied()
}

/// Where a walk along `passed` ends: each stretch at the height it has reached, stepping up onto what's in the
/// way when it can, then held up where it stops or falling by up to `fall`. Sets `falling`.
fn walk_along(space: &Space, walk: &mut Walk, passed: &[Vec3], fall: f64) -> Option<Vec3> {
    let height = walk.walker.height;
    let mut y = passed[0].1;
    for w in passed.windows(2) {
        let (a, b) = (w[0], w[1]);
        if across(a, b) <= 1e-9 || space.clear_between(a.0, a.2, b.0, b.2, y, y + height) {
            continue;
        }
        // In the way at this height: a step up, or a wall.
        let up = space.floor_at(b.0, b.2, y, walk.settings.step_height, 0.0);
        let fits = !up.is_nan()
            && space.clear_at(a.0, a.2, y, up + height)
            && space.clear_between(a.0, a.2, b.0, b.2, up, up + height);
        if !fits {
            return None;
        }
        y = up;
        walk.falling = 0.0;
    }
    let end = *passed.last()?;
    if !space.supported(end.0, end.2, y) {
        // Nothing holds it up here: it falls, landing on whatever it reaches this tick.
        let land = space.floor_at(end.0, end.2, y, 0.0, fall);
        if land.is_nan() {
            walk.falling = fall / SECONDS;
            return Some(Vec3(end.0, y - fall, end.2));
        }
        y = land;
    }
    walk.falling = 0.0;
    Some(Vec3(end.0, y, end.2))
}
