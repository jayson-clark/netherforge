//! Where centities' hitboxes are, and what a region reaches (Kotlin's `ObstacleGrid`, `QueryShape`,
//! `QueryFilter` and `SpatialQueries`): every instance's shown hitbox boxes, measured once per change and filed
//! by world cell, so a question looks only at the instances whose boxes come near it; the shapes questions
//! ask (a box, a sphere, a cone, a swept sphere); and what they find among centities and the server's
//! entities, nearest first. `touch` and flights ask them; `world:overlap_*` will, with worlds.

use std::collections::{HashMap, HashSet};

use netherforge_format::math::{BoundingBox, Vec3};
use netherforge_platform::{Platform, Uuid};

/// One instance's shown hitbox boxes, relative to its anchor along the world's axes, in node order; the node
/// each box is of; and the box around them all (none with none).
#[derive(Clone, Debug, PartialEq)]
pub struct Obstacles {
    pub boxes: Vec<BoundingBox>,
    pub nodes: Vec<usize>,
    pub bounds: Option<BoundingBox>,
}

/// Blocks per cell along each axis.
const CELL: f64 = 4.0;
/// Padding on every filing and question, far above any rounding between the two frames.
const PAD: f64 = 0.01;
/// Past this many cells an instance goes on the always-asked list instead.
const MAX_FILED_CELLS: i64 = 512;
/// Past this many cells a question walks every filed instance instead.
const MAX_QUERY_CELLS: i64 = 512;

fn cell(coordinate: f64) -> i32 {
    (coordinate / CELL).floor() as i32
}

type Key = (i32, i32, i32);

/// The instances filed by world cell.
#[derive(Default)]
pub struct Grid {
    worlds: HashMap<String, HashMap<Key, Vec<Uuid>>>,
    oversized: Vec<(Uuid, String)>,
    filed: HashMap<Uuid, (String, Vec<Key>)>,
}

impl Grid {
    pub fn unfile(&mut self, id: Uuid) {
        self.oversized.retain(|(o, _)| *o != id);
        let Some((world, keys)) = self.filed.remove(&id) else {
            return;
        };
        if let Some(cells) = self.worlds.get_mut(&world) {
            for key in keys {
                if let Some(list) = cells.get_mut(&key) {
                    list.retain(|o| *o != id);
                    if list.is_empty() {
                        cells.remove(&key);
                    }
                }
            }
        }
    }

    /// Files `id` under every cell its `bounds` (relative to `anchor`) cover in `world`.
    pub fn file(&mut self, id: Uuid, world: &str, anchor: Vec3, bounds: &BoundingBox) {
        let from = (
            cell(anchor.0 + bounds.min.0 - PAD),
            cell(anchor.1 + bounds.min.1 - PAD),
            cell(anchor.2 + bounds.min.2 - PAD),
        );
        let to = (
            cell(anchor.0 + bounds.max.0 + PAD),
            cell(anchor.1 + bounds.max.1 + PAD),
            cell(anchor.2 + bounds.max.2 + PAD),
        );
        let count = i64::from(to.0 - from.0 + 1)
            * i64::from(to.1 - from.1 + 1)
            * i64::from(to.2 - from.2 + 1);
        if count > MAX_FILED_CELLS {
            self.oversized.push((id, world.to_owned()));
            return;
        }
        let cells = self.worlds.entry(world.to_owned()).or_default();
        let mut keys = Vec::with_capacity(count as usize);
        for x in from.0..=to.0 {
            for y in from.1..=to.1 {
                for z in from.2..=to.2 {
                    cells.entry((x, y, z)).or_default().push(id);
                    keys.push((x, y, z));
                }
            }
        }
        self.filed.insert(id, (world.to_owned(), keys));
    }

    /// Every instance in `world` whose boxes may reach `region` (world coordinates): a superset.
    pub fn near(&self, world: &str, region: &BoundingBox) -> HashSet<Uuid> {
        let mut out: HashSet<Uuid> = self
            .oversized
            .iter()
            .filter(|(_, w)| w == world)
            .map(|(id, _)| *id)
            .collect();
        let Some(cells) = self.worlds.get(world) else {
            return out;
        };
        let from = (
            cell(region.min.0 - PAD),
            cell(region.min.1 - PAD),
            cell(region.min.2 - PAD),
        );
        let to = (
            cell(region.max.0 + PAD),
            cell(region.max.1 + PAD),
            cell(region.max.2 + PAD),
        );
        let count = i64::from(to.0 - from.0 + 1)
            * i64::from(to.1 - from.1 + 1)
            * i64::from(to.2 - from.2 + 1);
        if count > MAX_QUERY_CELLS {
            // A region this big is quicker walked instance by instance.
            out.extend(cells.values().flatten().copied());
            return out;
        }
        for x in from.0..=to.0 {
            for y in from.1..=to.1 {
                for z in from.2..=to.2 {
                    if let Some(list) = cells.get(&(x, y, z)) {
                        out.extend(list.iter().copied());
                    }
                }
            }
        }
        out
    }
}

pub const EPSILON: f64 = 1e-9;
/// Steps of each one-dimensional search.
const SEARCH_STEPS: usize = 64;

pub fn overlaps(a: &BoundingBox, b: &BoundingBox) -> bool {
    a.min.0 <= b.max.0
        && a.max.0 >= b.min.0
        && a.min.1 <= b.max.1
        && a.max.1 >= b.min.1
        && a.min.2 <= b.max.2
        && a.max.2 >= b.min.2
}

pub fn inside(b: &BoundingBox, p: Vec3) -> bool {
    p.0 >= b.min.0
        && p.0 <= b.max.0
        && p.1 >= b.min.1
        && p.1 <= b.max.1
        && p.2 >= b.min.2
        && p.2 <= b.max.2
}

/// The point of `b` nearest `p`: `p` itself when it's inside.
pub fn closest(b: &BoundingBox, p: Vec3) -> Vec3 {
    Vec3(
        p.0.clamp(b.min.0, b.max.0),
        p.1.clamp(b.min.1, b.max.1),
        p.2.clamp(b.min.2, b.max.2),
    )
}

pub fn distance_to(b: &BoundingBox, p: Vec3) -> f64 {
    (closest(b, p) - p).length()
}

/// Where along the segment from `start` by `along` (0 to 1) it comes nearest `b` (a ternary search: the
/// distance is convex).
pub fn nearest_along(b: &BoundingBox, start: Vec3, along: Vec3) -> f64 {
    let (mut lo, mut hi) = (0.0, 1.0);
    for _ in 0..SEARCH_STEPS {
        let a = lo + (hi - lo) / 3.0;
        let c = hi - (hi - lo) / 3.0;
        if distance_to(b, start + along * a) <= distance_to(b, start + along * c) {
            hi = c;
        } else {
            lo = a;
        }
    }
    (lo + hi) / 2.0
}

/// How a box reached a shape: `distance` from its origin (along the way, for a capsule) and the `position`
/// that's measured to.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Reach {
    pub distance: f64,
    pub position: Vec3,
}

/// A region the spatial questions test hitboxes against, in world coordinates.
#[derive(Clone, Debug, PartialEq)]
pub enum QueryShape {
    /// Everything inside or touching the box; distances from its middle.
    Aabb { min: Vec3, max: Vec3 },
    /// Everything within `radius` of `center`.
    Sphere { center: Vec3, radius: f64 },
    /// A cone with its tip at `tip`, opening along the unit `axis` for `length` blocks, `half_angle` degrees
    /// from the axis to its side.
    Cone {
        tip: Vec3,
        axis: Vec3,
        length: f64,
        half_angle: f64,
    },
    /// A sphere of `radius` moved from `from` to `to`: a box counts from the first point along the way where
    /// it touches, and the position is the sphere's middle there.
    Capsule { from: Vec3, to: Vec3, radius: f64 },
}

impl QueryShape {
    /// The box around it, for the broad phase.
    pub fn region(&self) -> BoundingBox {
        match self {
            QueryShape::Aabb { min, max } => BoundingBox {
                min: *min,
                max: *max,
            },
            QueryShape::Sphere { center, radius } => {
                let r = Vec3(*radius, *radius, *radius);
                BoundingBox {
                    min: *center - r,
                    max: *center + r,
                }
            }
            QueryShape::Cone { tip, length, .. } => {
                let r = Vec3(*length, *length, *length);
                BoundingBox {
                    min: *tip - r,
                    max: *tip + r,
                }
            }
            QueryShape::Capsule { from, to, radius } => BoundingBox {
                min: Vec3(
                    from.0.min(to.0) - radius,
                    from.1.min(to.1) - radius,
                    from.2.min(to.2) - radius,
                ),
                max: Vec3(
                    from.0.max(to.0) + radius,
                    from.1.max(to.1) + radius,
                    from.2.max(to.2) + radius,
                ),
            },
        }
    }

    /// Its own point, which distances and the line of sight are measured from.
    pub fn origin(&self) -> Vec3 {
        match self {
            QueryShape::Aabb { min, max } => (*min + *max) * 0.5,
            QueryShape::Sphere { center, .. } => *center,
            QueryShape::Cone { tip, .. } => *tip,
            QueryShape::Capsule { from, .. } => *from,
        }
    }

    /// Whether `b` reaches into the shape, and where.
    pub fn test(&self, b: &BoundingBox) -> Option<Reach> {
        match self {
            QueryShape::Aabb { min, max } => {
                if !overlaps(
                    b,
                    &BoundingBox {
                        min: *min,
                        max: *max,
                    },
                ) {
                    return None;
                }
                let origin = self.origin();
                let at = closest(b, origin);
                Some(Reach {
                    distance: (at - origin).length(),
                    position: at,
                })
            }
            QueryShape::Sphere { center, radius } => {
                let at = closest(b, *center);
                let distance = (at - *center).length();
                (distance <= *radius).then_some(Reach {
                    distance,
                    position: at,
                })
            }
            QueryShape::Cone {
                tip,
                axis,
                length,
                half_angle,
            } => {
                if inside(b, *tip) {
                    return Some(Reach {
                        distance: 0.0,
                        position: *tip,
                    });
                }
                let cos_limit = half_angle.clamp(0.0, 180.0).to_radians().cos();
                let t = nearest_along(b, *tip, *axis * *length);
                let at = closest(b, *tip + *axis * (*length * t));
                let to = at - *tip;
                let distance = to.length();
                if distance > *length
                    || (distance > EPSILON && to.dot(*axis) / distance < cos_limit)
                {
                    return None;
                }
                Some(Reach {
                    distance,
                    position: at,
                })
            }
            QueryShape::Capsule { from, to, radius } => {
                let (from, radius) = (*from, *radius);
                let along = *to - from;
                let span = along.length();
                if distance_to(b, from) <= radius {
                    return Some(Reach {
                        distance: 0.0,
                        position: from,
                    });
                }
                let nearest = nearest_along(b, from, along);
                if distance_to(b, from + along * nearest) > radius {
                    return None;
                }
                // Where the line enters the box grown by the radius: exact when the sphere meets a face there.
                let entry = entry(b, from, along, radius)
                    .filter(|e| *e <= nearest)
                    .unwrap_or(0.0);
                if distance_to(b, from + along * entry) <= radius + EPSILON {
                    return Some(Reach {
                        distance: span * entry,
                        position: from + along * entry,
                    });
                }
                // It meets an edge or a corner: halve the way to the nearest point.
                let (mut lo, mut hi) = (entry, nearest);
                for _ in 0..SEARCH_STEPS {
                    let mid = (lo + hi) / 2.0;
                    if distance_to(b, from + along * mid) <= radius {
                        hi = mid;
                    } else {
                        lo = mid;
                    }
                }
                let _ = lo;
                Some(Reach {
                    distance: span * hi,
                    position: from + along * hi,
                })
            }
        }
    }
}

/// Where (0 to 1) the segment from `from` by `along` enters `b` grown by `grow` on every side.
fn entry(b: &BoundingBox, from: Vec3, along: Vec3, grow: f64) -> Option<f64> {
    let (mut enter, mut leave) = (0.0f64, 1.0f64);
    let (starts, steps) = (from.coordinates(), along.coordinates());
    let (lows, highs) = (b.min.coordinates(), b.max.coordinates());
    for a in 0..3 {
        let d = steps[a];
        let (low, high) = (lows[a] - grow, highs[a] + grow);
        if d == 0.0 {
            if starts[a] < low || starts[a] > high {
                return None;
            }
            continue;
        }
        let (mut t0, mut t1) = ((low - starts[a]) / d, (high - starts[a]) / d);
        if t0 > t1 {
            std::mem::swap(&mut t0, &mut t1);
        }
        enter = enter.max(t0);
        leave = leave.min(t1);
        if enter > leave {
            return None;
        }
    }
    Some(enter)
}

/// What a question may find: `kinds` are centities' and entities' types together; `exclude_*` are instances
/// and entities by id.
#[derive(Clone, Debug, PartialEq)]
pub struct QueryFilter {
    pub centities: bool,
    pub players: bool,
    pub entities: bool,
    pub kinds: Option<HashSet<String>>,
    pub exclude_centities: HashSet<Uuid>,
    pub exclude_entities: HashSet<Uuid>,
    pub line_of_sight: bool,
    pub limit: usize,
}

impl Default for QueryFilter {
    fn default() -> QueryFilter {
        QueryFilter {
            centities: true,
            players: true,
            entities: true,
            kinds: None,
            exclude_centities: HashSet::new(),
            exclude_entities: HashSet::new(),
            line_of_sight: false,
            limit: usize::MAX,
        }
    }
}

/// One thing a question found: an instance's node, or an entity, with how it was reached.
#[derive(Clone, Debug, PartialEq)]
pub enum Found {
    Centity {
        instance: Uuid,
        node: usize,
        reach: Reach,
    },
    Entity {
        id: Uuid,
        reach: Reach,
    },
}

impl Found {
    pub fn reach(&self) -> &Reach {
        match self {
            Found::Centity { reach, .. } | Found::Entity { reach, .. } => reach,
        }
    }
}

/// What one of an instance's nodes touches: another instance's node, or an entity.
#[derive(Clone, Debug, PartialEq)]
pub struct Contact {
    pub node: usize,
    pub other: Option<(Uuid, usize)>,
    pub entity: Option<Uuid>,
}

/// `b` placed at `at`.
pub fn placed(b: &BoundingBox, at: Vec3) -> BoundingBox {
    BoundingBox {
        min: b.min + at,
        max: b.max + at,
    }
}

impl super::Centities {
    /// Instance `id`'s shown hitbox boxes as they stand now, measured once per change.
    pub fn obstacles(
        &mut self,
        game: &dyn netherforge_format::game::GameData,
        id: Uuid,
    ) -> Option<std::rc::Rc<Obstacles>> {
        let instance = self.get_mut(id)?;
        if let Some(o) = &instance.obstacles {
            return Some(o.clone());
        }
        let world = instance.placed().to_vec();
        let (mut boxes, mut nodes) = (Vec::new(), Vec::new());
        for (index, node) in instance.definition.nodes.iter().enumerate() {
            if node.hitbox.is_none() || !instance.shown(index) {
                continue;
            }
            for b in super::boxes_of(game, instance, index) {
                boxes.push(super::hitboxes::bounds_of(&[b], &world[index]));
                nodes.push(index);
            }
        }
        let bounds = (!boxes.is_empty()).then(|| {
            super::hitboxes::bounds_of(&boxes, &netherforge_format::math::Matrix4::IDENTITY)
        });
        let measured = std::rc::Rc::new(Obstacles {
            boxes,
            nodes,
            bounds,
        });
        instance.obstacles = Some(measured.clone());
        Some(measured)
    }

    /// Files again every instance reshaped since the last question.
    fn refile(&mut self, game: &dyn netherforge_format::game::GameData) {
        let dirty: Vec<Uuid> = std::mem::take(&mut *self.dirty.borrow_mut())
            .into_iter()
            .collect();
        for id in dirty {
            self.grid.unfile(id);
            let Some(obstacles) = self.obstacles(game, id) else {
                continue;
            };
            let Some(instance) = self.get(id).filter(|i| !i.removed) else {
                continue;
            };
            if let Some(bounds) = &obstacles.bounds {
                let a = instance.anchor();
                let (world, at) = (a.world.clone(), Vec3(a.x, a.y, a.z));
                self.grid.file(id, &world, at, bounds);
            }
        }
    }

    /// The instances in `world` whose boxes may reach `region`, in the order they were made.
    pub fn near(
        &mut self,
        game: &dyn netherforge_format::game::GameData,
        world: &str,
        region: &BoundingBox,
    ) -> Vec<Uuid> {
        self.refile(game);
        let near = self.grid.near(world, region);
        let mut out: Vec<Uuid> = near.into_iter().collect();
        out.sort_by_key(|id| self.order_of(*id));
        out
    }

    /// Each loaded instance in `world` that `shape` reaches, by its nearest box.
    pub fn centity_hits(
        &mut self,
        platform: &dyn Platform,
        world: &str,
        shape: &QueryShape,
    ) -> Vec<(Uuid, usize, Reach)> {
        let game = platform.game();
        let region = shape.region();
        let mut hits = Vec::new();
        for id in self.near(game, world, &region) {
            let Some(instance) = self.get(id) else {
                continue;
            };
            if instance.removed || !Self::loaded(platform, instance) {
                continue;
            }
            let a = instance.anchor();
            let at = Vec3(a.x, a.y, a.z);
            let Some(obstacles) = self.obstacles(game, id) else {
                continue;
            };
            let mut best: Option<(usize, Reach)> = None;
            for (i, local) in obstacles.boxes.iter().enumerate() {
                let b = placed(local, at);
                if !overlaps(&b, &region) {
                    continue;
                }
                if let Some(reach) = shape.test(&b)
                    && best.is_none_or(|(_, r)| reach.distance < r.distance)
                {
                    best = Some((obstacles.nodes[i], reach));
                }
            }
            if let Some((node, reach)) = best {
                hits.push((id, node, reach));
            }
        }
        hits
    }

    /// Everything in `world` that `shape` reaches, as `filter` allows, nearest first.
    pub fn find(
        &mut self,
        platform: &dyn Platform,
        world: &str,
        shape: &QueryShape,
        filter: &QueryFilter,
    ) -> Vec<Found> {
        let mut found = Vec::new();
        let region = shape.region();
        if filter.centities {
            for (instance, node, reach) in self.centity_hits(platform, world, shape) {
                if filter.exclude_centities.contains(&instance) {
                    continue;
                }
                let kind = self
                    .get(instance)
                    .map(|i| i.centity().to_owned())
                    .unwrap_or_default();
                if filter.kinds.as_ref().is_some_and(|k| !k.contains(&kind)) {
                    continue;
                }
                found.push(Found::Centity {
                    instance,
                    node,
                    reach,
                });
            }
        }
        if filter.players || filter.entities {
            for entity in platform.world_entities().boxes(world.to_owned(), region) {
                if if entity.player {
                    !filter.players
                } else {
                    !filter.entities
                } {
                    continue;
                }
                if filter.exclude_entities.contains(&entity.id)
                    || filter
                        .kinds
                        .as_ref()
                        .is_some_and(|k| !k.contains(&entity.kind))
                {
                    continue;
                }
                if let Some(reach) = shape.test(&entity.r#box) {
                    found.push(Found::Entity {
                        id: entity.id,
                        reach,
                    });
                }
            }
        }
        found.sort_by(|a, b| a.reach().distance.total_cmp(&b.reach().distance));
        if filter.line_of_sight {
            let origin = shape.origin();
            found.retain(|f| sees(platform, world, origin, f.reach().position));
        }
        found.truncate(filter.limit);
        found
    }

    /// Everything instance `id`'s shown hitboxes touch now (box against box, touching faces included), by the
    /// other instance's or entity's id: its own other nodes don't count. What `touch` compares tick to tick.
    pub fn contacts(
        &mut self,
        platform: &dyn Platform,
        id: Uuid,
    ) -> indexmap::IndexMap<Uuid, Contact> {
        let game = platform.game();
        let mut found = indexmap::IndexMap::new();
        let Some(own) = self.obstacles(game, id) else {
            return found;
        };
        let Some(bounds) = own.bounds else {
            return found;
        };
        let Some(instance) = self.get(id) else {
            return found;
        };
        let a = instance.anchor();
        let (world, at) = (a.world.clone(), Vec3(a.x, a.y, a.z));
        let mine: Vec<BoundingBox> = own.boxes.iter().map(|b| placed(b, at)).collect();
        let region = placed(&bounds, at);
        let touched_by = |b: &BoundingBox| -> Option<usize> {
            if !overlaps(b, &region) {
                return None;
            }
            mine.iter()
                .position(|m| overlaps(m, b))
                .map(|i| own.nodes[i])
        };
        for other in self.near(game, &world, &region) {
            if other == id {
                continue;
            }
            let Some(them) = self.get(other) else {
                continue;
            };
            if them.removed || !Self::loaded(platform, them) {
                continue;
            }
            let oa = them.anchor();
            let other_at = Vec3(oa.x, oa.y, oa.z);
            let Some(theirs) = self.obstacles(game, other) else {
                continue;
            };
            for (i, b) in theirs.boxes.iter().enumerate() {
                if let Some(node) = touched_by(&placed(b, other_at)) {
                    found.insert(
                        other,
                        Contact {
                            node,
                            other: Some((other, theirs.nodes[i])),
                            entity: None,
                        },
                    );
                    break;
                }
            }
        }
        for entity in platform.world_entities().boxes(world, region) {
            if let Some(node) = touched_by(&entity.r#box) {
                found.insert(
                    entity.id,
                    Contact {
                        node,
                        other: None,
                        entity: Some(entity.id),
                    },
                );
            }
        }
        found
    }
}

/// Whether a ray from `from` reaches `to` with no block's collision shape in the way.
pub fn sees(platform: &dyn Platform, world: &str, from: Vec3, to: Vec3) -> bool {
    let d = to - from;
    let distance = d.length();
    if distance < 1e-6 {
        return true;
    }
    match platform.worlds().raycast_blocks(
        world.to_owned(),
        from,
        d * (1.0 / distance),
        distance,
        false,
    ) {
        None => true,
        Some(hit) => (hit.position - from).length() >= distance - 1e-4,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn unit_at(x: f64) -> BoundingBox {
        BoundingBox {
            min: Vec3(x, 0.0, 0.0),
            max: Vec3(x + 1.0, 1.0, 1.0),
        }
    }

    #[test]
    fn shapes_reach_what_they_should() {
        let sphere = QueryShape::Sphere {
            center: Vec3(-1.0, 0.5, 0.5),
            radius: 1.0,
        };
        assert_eq!(sphere.test(&unit_at(0.0)).map(|r| r.distance), Some(1.0));
        assert!(sphere.test(&unit_at(0.5)).is_none());
        let swept = QueryShape::Capsule {
            from: Vec3(-5.0, 0.5, 0.5),
            to: Vec3(5.0, 0.5, 0.5),
            radius: 0.25,
        };
        let reach = swept.test(&unit_at(0.0)).expect("in its way");
        assert!((reach.distance - 4.75).abs() < 1e-6, "{reach:?}");
        let cone = QueryShape::Cone {
            tip: Vec3(-3.0, 0.5, 0.5),
            axis: Vec3(1.0, 0.0, 0.0),
            length: 5.0,
            half_angle: 10.0,
        };
        assert!(cone.test(&unit_at(0.0)).is_some());
        let aside = QueryShape::Cone {
            tip: Vec3(-3.0, 5.5, 0.5),
            axis: Vec3(1.0, 0.0, 0.0),
            length: 5.0,
            half_angle: 10.0,
        };
        assert!(aside.test(&unit_at(0.0)).is_none());
    }

    #[test]
    fn the_grid_files_by_cell() {
        let mut grid = Grid::default();
        let id = Uuid(1);
        grid.file(id, "world", Vec3(10.0, 64.0, 10.0), &unit_at(0.0));
        assert!(
            grid.near(
                "world",
                &BoundingBox {
                    min: Vec3(9.0, 63.0, 9.0),
                    max: Vec3(12.0, 66.0, 12.0)
                }
            )
            .contains(&id)
        );
        assert!(
            grid.near(
                "world",
                &BoundingBox {
                    min: Vec3(100.0, 63.0, 9.0),
                    max: Vec3(102.0, 66.0, 12.0)
                }
            )
            .is_empty()
        );
        assert!(grid.near("nether", &unit_at(10.0)).is_empty());
        grid.unfile(id);
        assert!(
            grid.near(
                "world",
                &BoundingBox {
                    min: Vec3(9.0, 63.0, 9.0),
                    max: Vec3(12.0, 66.0, 12.0)
                }
            )
            .is_empty()
        );
    }
}
