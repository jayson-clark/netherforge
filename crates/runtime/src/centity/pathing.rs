//! Finding a way through the world's blocks (Kotlin's `pathing`): `Space` answers where a `Walker` (a square
//! footprint and a height) fits among the blocks' collision boxes; `ground` searches over ground only (a node
//! a column and a height it can stand at, its eight neighbours each one `floor_at` look), `air` through the
//! air in three dimensions; both share one A* and smooth what they find into straight stretches. A search is
//! bounded by its range and `NODE_LIMIT` places, answering the closest place it reached past either.

use std::collections::HashMap;

use netherforge_format::math::Vec3;

use super::world_shapes::BlockShapes;

/// Faces closer than this touch rather than overlap.
pub const EPS: f64 = 1e-4;
/// How many places one search may expand: `NODE_LIMIT` in `spec/constants.ts`.
pub const NODE_LIMIT: usize = 4096;
/// How many grid points ahead smoothing looks for a straight line to.
const LOOKAHEAD: usize = 16;
/// How far apart smoothing tests a straight line, in blocks.
const SAMPLE: f64 = 0.25;

/// The box that walks: a square footprint `width` blocks across, centred on its feet, `height` up.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Walker {
    pub width: f64,
    pub height: f64,
}

impl Walker {
    /// Where the grid's cells are centred along x and z: on block centres for a walker one, three… blocks
    /// wide, on block corners for two, four…
    pub fn offset(&self) -> f64 {
        if ((self.width - EPS).ceil() as i64) % 2 == 0 {
            0.0
        } else {
            0.5
        }
    }
}

/// Questions about where a walker fits among the blocks `shapes` gives.
pub struct Space<'s> {
    shapes: &'s dyn BlockShapes,
    pub walker: Walker,
}

impl<'s> Space<'s> {
    pub fn new(shapes: &'s dyn BlockShapes, walker: Walker) -> Self {
        Space { shapes, walker }
    }

    fn half(&self) -> f64 {
        self.walker.width / 2.0
    }

    /// Whether no block's box overlaps the region, touching faces aside, and every block it touches is
    /// loaded.
    pub fn clear(&self, min: Vec3, max: Vec3) -> bool {
        for x in (min.0 + EPS).floor() as i32..=(max.0 - EPS).floor() as i32 {
            for z in (min.2 + EPS).floor() as i32..=(max.2 - EPS).floor() as i32 {
                for y in (min.1 + EPS).floor() as i32 - 1..=(max.1 - EPS).floor() as i32 {
                    let Some(boxes) = self.shapes.boxes(x, y, z) else {
                        return false;
                    };
                    for b in boxes {
                        if b.min.0 < max.0 - EPS
                            && b.max.0 > min.0 + EPS
                            && b.min.1 < max.1 - EPS
                            && b.max.1 > min.1 + EPS
                            && b.min.2 < max.2 - EPS
                            && b.max.2 > min.2 + EPS
                        {
                            return false;
                        }
                    }
                }
            }
        }
        true
    }

    /// Whether the walker, feet at (x, z), has room from `from` up to `to`.
    pub fn clear_at(&self, x: f64, z: f64, from: f64, to: f64) -> bool {
        let h = self.half();
        self.clear(Vec3(x - h, from, z - h), Vec3(x + h, to, z + h))
    }

    /// Whether the walker can move straight between two footprints at a height of `from` to `to`: the box
    /// around both is clear, so it never cuts a corner.
    pub fn clear_between(&self, x1: f64, z1: f64, x2: f64, z2: f64, from: f64, to: f64) -> bool {
        let h = self.half();
        self.clear(
            Vec3(x1.min(x2) - h, from, z1.min(z2) - h),
            Vec3(x1.max(x2) + h, to, z1.max(z2) + h),
        )
    }

    /// The same in three dimensions, for flying.
    pub fn clear_flying(&self, a: Vec3, b: Vec3) -> bool {
        let h = self.half();
        self.clear(
            Vec3(a.0.min(b.0) - h, a.1.min(b.1), a.2.min(b.2) - h),
            Vec3(
                a.0.max(b.0) + h,
                a.1.max(b.1) + self.walker.height,
                a.2.max(b.2) + h,
            ),
        )
    }

    /// The highest height from `from_y - down` to `from_y + up` the walker can stand at with its feet at
    /// (x, z), with room above; NaN when there's none, or a block it needs isn't loaded.
    pub fn floor_at(&self, x: f64, z: f64, from_y: f64, up: f64, down: f64) -> f64 {
        let h = self.half();
        let (min_x, max_x, min_z, max_z) = (x - h, x + h, z - h, z + h);
        let (low, high) = (from_y - down, from_y + up);
        let mut tops = Vec::new();
        for bx in (min_x + EPS).floor() as i32..=(max_x - EPS).floor() as i32 {
            for bz in (min_z + EPS).floor() as i32..=(max_z - EPS).floor() as i32 {
                for by in (low - EPS).floor() as i32 - 1..=(high + EPS).floor() as i32 {
                    let Some(boxes) = self.shapes.boxes(bx, by, bz) else {
                        return f64::NAN;
                    };
                    for b in boxes {
                        let top = b.max.1;
                        if top < low - EPS || top > high + EPS {
                            continue;
                        }
                        if b.min.0 >= max_x - EPS
                            || b.max.0 <= min_x + EPS
                            || b.min.2 >= max_z - EPS
                            || b.max.2 <= min_z + EPS
                        {
                            continue;
                        }
                        tops.push(top);
                    }
                }
            }
        }
        tops.sort_by(f64::total_cmp);
        tops.dedup();
        for top in tops.into_iter().rev() {
            if self.clear_at(x, z, top, top.max(from_y) + self.walker.height) {
                return top;
            }
        }
        f64::NAN
    }

    /// Whether something under the footprint at (x, z) holds the walker up at exactly `y`, with room above.
    pub fn supported(&self, x: f64, z: f64, y: f64) -> bool {
        !self.floor_at(x, z, y, 0.0, 0.0).is_nan()
    }
}

/// A way found: where the walker's feet go, first where it starts; `complete` when it ends at the goal,
/// otherwise at the closest place the search reached; `expanded`, how many places it looked at.
#[derive(Clone, Debug, PartialEq)]
pub struct PathResult {
    pub points: Vec<Vec3>,
    pub complete: bool,
    pub expanded: usize,
}

/// How far a search may go: no further than `range` blocks from its start, no more than `nodes` places.
#[derive(Clone, Copy, Debug)]
pub struct SearchLimits {
    pub range: f64,
    pub nodes: usize,
}

impl SearchLimits {
    pub fn new(range: f64) -> SearchLimits {
        SearchLimits {
            range,
            nodes: NODE_LIMIT,
        }
    }
}

struct Node {
    at: Vec3,
    cell: (i32, i32, i32),
    g: f64,
    h: f64,
    parent: Option<usize>,
    closed: bool,
}

/// The A* both searches share: nodes in one array, a binary heap of open ones, and the closest one to the goal
/// seen so far.
struct AStar {
    goal: Vec3,
    nodes: Vec<Node>,
    index: HashMap<(i32, i32, i64), usize>,
    heap: std::collections::BinaryHeap<Open>,
    best: Option<usize>,
}

#[derive(PartialEq)]
struct Open(f64, usize);
impl Eq for Open {}
impl PartialOrd for Open {
    fn partial_cmp(&self, other: &Self) -> Option<std::cmp::Ordering> {
        Some(self.cmp(other))
    }
}
impl Ord for Open {
    // A min-heap on f, then the node made first.
    fn cmp(&self, other: &Self) -> std::cmp::Ordering {
        other.0.total_cmp(&self.0).then(other.1.cmp(&self.1))
    }
}

impl AStar {
    fn new(goal: Vec3) -> AStar {
        AStar {
            goal,
            nodes: Vec::new(),
            index: HashMap::new(),
            heap: Default::default(),
            best: None,
        }
    }

    fn add(&mut self, at: Vec3, cell: (i32, i32, i32), cost: f64, parent: Option<usize>) -> usize {
        let n = self.nodes.len();
        let h = (self.goal - at).length();
        self.nodes.push(Node {
            at,
            cell,
            g: cost,
            h,
            parent,
            closed: false,
        });
        self.push(n);
        n
    }

    fn push(&mut self, n: usize) {
        // A hair over h breaks ties towards the goal, so open ground doesn't fan out.
        let node = &self.nodes[n];
        self.heap.push(Open(node.g + node.h * (1.0 + 1e-3), n));
    }

    /// Reaches the grid point `key` from `from` at `cost` more, if that's new or cheaper than before.
    fn relax(
        &mut self,
        key: (i32, i32, i64),
        at: Vec3,
        cell: (i32, i32, i32),
        from: usize,
        cost: f64,
    ) {
        let through = self.nodes[from].g + cost;
        match self.index.get(&key) {
            None => {
                let n = self.add(at, cell, through, Some(from));
                self.index.insert(key, n);
            }
            Some(&known) => {
                if self.nodes[known].closed || through >= self.nodes[known].g - 1e-9 {
                    return;
                }
                self.nodes[known].g = through;
                self.nodes[known].parent = Some(from);
                self.push(known);
            }
        }
    }

    /// The next open node, cheapest first, closed as it's handed out.
    fn pop(&mut self) -> Option<usize> {
        while let Some(Open(_, n)) = self.heap.pop() {
            if self.nodes[n].closed {
                continue;
            }
            self.nodes[n].closed = true;
            let better = match self.best {
                None => true,
                Some(b) => {
                    let (nh, bh) = (self.nodes[n].h, self.nodes[b].h);
                    nh < bh - 1e-9 || ((nh - bh).abs() <= 1e-9 && self.nodes[n].g < self.nodes[b].g)
                }
            };
            if better {
                self.best = Some(n);
            }
            return Some(n);
        }
        None
    }

    fn path_to(&self, end: usize) -> Vec<Vec3> {
        let mut out = Vec::new();
        let mut at = Some(end);
        while let Some(n) = at {
            out.push(self.nodes[n].at);
            at = self.nodes[n].parent;
        }
        out.reverse();
        out
    }
}

fn horizontal(a: Vec3, b: Vec3) -> f64 {
    let (dx, dz) = (b.0 - a.0, b.2 - a.2);
    (dx * dx + dz * dz).sqrt()
}

/// The cell along one horizontal axis whose centre is nearest `v`, for a grid centred at `cell + offset`.
fn cell_of(v: f64, offset: f64) -> i32 {
    (v - offset + 0.5).floor() as i32
}

/// Drops grid points a straight line can skip.
fn smooth(points: Vec<Vec3>, straight: &dyn Fn(Vec3, Vec3) -> bool) -> Vec<Vec3> {
    if points.len() <= 2 {
        return points;
    }
    let mut out = vec![points[0]];
    let last = points.len() - 1;
    let mut from = 0;
    while from < last {
        let mut to = from + 1;
        let mut next = from + 2;
        while next <= last && next - from <= LOOKAHEAD && straight(points[from], points[next]) {
            to = next;
            next += 1;
        }
        out.push(points[to]);
        from = to;
    }
    out
}

/// Steps up cost a little more than flat ground, and drops a little, so a level way round wins over a climb.
const STEP_COST: f64 = 0.5;
const DROP_COST: f64 = 0.2;
/// How close in each direction a column has to come to the goal to have arrived.
const ARRIVE_ACROSS: f64 = 0.75;
const ARRIVE_UP: f64 = 1.0;
/// How close a flying cell has to come to the goal to have arrived.
const ARRIVE_AIR: f64 = 1.0;

/// Walking: a way for `space`'s walker, feet at `start`, to `goal`, stepping up to `step_height` and dropping
/// up to `max_drop`, starting on the ground under it within `start_drop` below.
pub fn ground(
    space: &Space,
    start: Vec3,
    goal: Vec3,
    step_height: f64,
    max_drop: f64,
    start_drop: f64,
    limits: SearchLimits,
) -> PathResult {
    let offset = space.walker.offset();
    let height = space.walker.height;
    let floor = space.floor_at(start.0, start.2, start.1, step_height, start_drop);
    if floor.is_nan() {
        return PathResult {
            points: Vec::new(),
            complete: false,
            expanded: 0,
        };
    }
    let (base_x, base_z) = (cell_of(start.0, offset), cell_of(start.2, offset));
    let mut search = AStar::new(goal);
    search.add(Vec3(start.0, floor, start.2), (0, 0, 0), 0.0, None);
    let (mut expanded, mut arrived) = (0, None);
    while let Some(n) = search.pop() {
        let p = search.nodes[n].at;
        if horizontal(p, goal) <= ARRIVE_ACROSS && (p.1 - goal.1).abs() <= ARRIVE_UP {
            arrived = Some(n);
            break;
        }
        if expanded >= limits.nodes {
            break;
        }
        expanded += 1;
        let first = n == 0;
        let (cx0, _, cz0) = search.nodes[n].cell;
        for dx in -1..=1 {
            for dz in -1..=1 {
                // The start isn't on the grid: its own column is a step too.
                if dx == 0 && dz == 0 && !first {
                    continue;
                }
                let (cx, cz) = (cx0 + dx, cz0 + dz);
                let q = Vec3(
                    f64::from(base_x + cx) + offset,
                    0.0,
                    f64::from(base_z + cz) + offset,
                );
                if horizontal(start, q) > limits.range {
                    continue;
                }
                let t = space.floor_at(q.0, q.2, p.1, step_height, max_drop);
                if t.is_nan() {
                    continue;
                }
                let top = p.1.max(t);
                if !space.clear_between(p.0, p.2, q.0, q.2, top, top + height) {
                    continue;
                }
                if t > p.1 + EPS && !space.clear_at(p.0, p.2, p.1, t + height) {
                    continue;
                }
                let climb = if t > p.1 {
                    STEP_COST * (t - p.1)
                } else {
                    DROP_COST * (p.1 - t)
                };
                let to = Vec3(q.0, t, q.2);
                let cost = (to - p).length() + climb;
                search.relax(
                    (cx, cz, (t * 64.0).round() as i64),
                    to,
                    (cx, 0, cz),
                    n,
                    cost,
                );
            }
        }
    }
    let end = arrived.or(search.best).unwrap_or(0);
    if arrived.is_none() && end == 0 {
        return PathResult {
            points: Vec::new(),
            complete: false,
            expanded,
        };
    }
    let mut points = search.path_to(end);
    if arrived.is_some() {
        // The goal itself, when it's a step from where the search arrived.
        let last = *points.last().expect("a start");
        let t = space.floor_at(goal.0, goal.2, last.1, step_height, max_drop);
        if !t.is_nan() && ((goal.0 - last.0).abs() > 1e-9 || (goal.2 - last.2).abs() > 1e-9) {
            let top = last.1.max(t);
            let fits = space.clear_between(last.0, last.2, goal.0, goal.2, top, top + height)
                && (t <= last.1 + EPS || space.clear_at(last.0, last.2, last.1, t + height));
            if fits {
                points.push(Vec3(goal.0, t, goal.2));
            }
        }
    }
    PathResult {
        points: smooth(points, &|a, b| ground_straight(space, a, b)),
        complete: arrived.is_some(),
        expanded,
    }
}

/// Whether the walker can walk straight from `a` to `b` on level ground, held up all the way.
pub fn ground_straight(space: &Space, a: Vec3, b: Vec3) -> bool {
    if (a.1 - b.1).abs() > EPS {
        return false;
    }
    let steps = ((horizontal(a, b) / SAMPLE).ceil() as i32).max(1);
    let top = a.1 + space.walker.height;
    let (mut px, mut pz) = (a.0, a.2);
    for i in 1..=steps {
        let s = f64::from(i) / f64::from(steps);
        let (sx, sz) = (a.0 + (b.0 - a.0) * s, a.2 + (b.2 - a.2) * s);
        if !space.clear_between(px, pz, sx, sz, a.1, top) || !space.supported(sx, sz, a.1) {
            return false;
        }
        px = sx;
        pz = sz;
    }
    true
}

/// Flying: a way for `space`'s walker, feet at `start`, to `goal` through the air.
pub fn air(space: &Space, start: Vec3, goal: Vec3, limits: SearchLimits) -> PathResult {
    let offset = space.walker.offset();
    let (base_x, base_y, base_z) = (
        cell_of(start.0, offset),
        start.1.round() as i32,
        cell_of(start.2, offset),
    );
    let mut search = AStar::new(goal);
    search.add(start, (0, 0, 0), 0.0, None);
    let (mut expanded, mut arrived) = (0, None);
    while let Some(n) = search.pop() {
        if search.nodes[n].h <= ARRIVE_AIR {
            arrived = Some(n);
            break;
        }
        if expanded >= limits.nodes {
            break;
        }
        expanded += 1;
        let first = n == 0;
        let p = search.nodes[n].at;
        let (cx0, cy0, cz0) = search.nodes[n].cell;
        for dx in -1..=1 {
            for dy in -1..=1 {
                for dz in -1..=1 {
                    if dx == 0 && dy == 0 && dz == 0 && !first {
                        continue;
                    }
                    let (cx, cy, cz) = (cx0 + dx, cy0 + dy, cz0 + dz);
                    let q = Vec3(
                        f64::from(base_x + cx) + offset,
                        f64::from(base_y + cy),
                        f64::from(base_z + cz) + offset,
                    );
                    if (q - start).length() > limits.range || !space.clear_flying(p, q) {
                        continue;
                    }
                    search.relax(
                        (cx, cz, i64::from(cy)),
                        q,
                        (cx, cy, cz),
                        n,
                        (q - p).length(),
                    );
                }
            }
        }
    }
    let end = arrived.or(search.best).unwrap_or(0);
    if arrived.is_none() && end == 0 {
        return PathResult {
            points: Vec::new(),
            complete: false,
            expanded,
        };
    }
    let mut points = search.path_to(end);
    if arrived.is_some() {
        let last = *points.last().expect("a start");
        if last != goal && air_straight(space, last, goal) {
            points.push(goal);
        }
    }
    PathResult {
        points: smooth(points, &|a, b| air_straight(space, a, b)),
        complete: arrived.is_some(),
        expanded,
    }
}

/// Whether the walker can fly straight from `a` to `b`.
pub fn air_straight(space: &Space, a: Vec3, b: Vec3) -> bool {
    let steps = (((b - a).length() / SAMPLE).ceil() as i32).max(1);
    let mut p = a;
    for i in 1..=steps {
        let s = a + (b - a) * (f64::from(i) / f64::from(steps));
        if !space.clear_flying(p, s) {
            return false;
        }
        p = s;
    }
    true
}

#[cfg(test)]
mod tests {
    use super::*;
    use netherforge_format::math::BoundingBox;

    /// Floor below y 0, and a wall at x 3 from z -2 to 2, two high.
    struct Yard;
    impl BlockShapes for Yard {
        fn boxes(&self, x: i32, y: i32, z: i32) -> Option<Vec<BoundingBox>> {
            let solid = y < 0 || (x == 3 && (-2..=2).contains(&z) && y < 2);
            let (x, y, z) = (f64::from(x), f64::from(y), f64::from(z));
            Some(if solid {
                vec![BoundingBox {
                    min: Vec3(x, y, z),
                    max: Vec3(x + 1.0, y + 1.0, z + 1.0),
                }]
            } else {
                Vec::new()
            })
        }
    }

    #[test]
    fn a_walk_goes_round_a_wall_and_a_flight_over_it() {
        let space = Space::new(
            &Yard,
            Walker {
                width: 0.6,
                height: 1.8,
            },
        );
        let (from, to) = (Vec3(0.5, 0.0, 0.5), Vec3(6.5, 0.0, 0.5));
        let walk = ground(&space, from, to, 1.0, 3.0, 64.0, SearchLimits::new(48.0));
        assert!(walk.complete, "{walk:?}");
        // Round the end of the wall, never through it.
        assert!(walk.points.iter().any(|p| p.2.abs() > 2.0), "{walk:?}");
        let flight = air(&space, from, to, SearchLimits::new(48.0));
        assert!(flight.complete, "{flight:?}");
        assert!(flight.points.iter().any(|p| p.1 >= 2.0), "{flight:?}");
    }
}
