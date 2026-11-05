//! The blocks of one region of a world (Kotlin's `RegionShapes` and `BrickShapes`): read from the platform in
//! one `collision_boxes` call and filed by the block each box belongs to. A block outside the region is asked
//! for on its own; a block in a chunk that isn't loaded answers none, never loaded. Kept for one look only:
//! the world changes between ticks.

use std::cell::RefCell;
use std::collections::HashMap;

use netherforge_format::math::{BoundingBox, Vec3};
use netherforge_platform::WorldOps;

/// Pulls a region's far faces in, so the platform's whole-block loops stop at the last block.
const INSIDE: f64 = 1e-6;

/// What a block is made of to something moving through it.
pub trait BlockShapes {
    /// The collision boxes of the block at (x, y, z), in world coordinates: empty for air, none when its
    /// chunk isn't loaded (a walker treats it as a wall).
    fn boxes(&self, x: i32, y: i32, z: i32) -> Option<Vec<BoundingBox>>;
}

pub struct RegionShapes<'w> {
    worlds: &'w dyn WorldOps,
    world: String,
    origin: (i32, i32, i32),
    size: (i32, i32, i32),
    cells: Vec<Option<Vec<BoundingBox>>>,
}

impl<'w> RegionShapes<'w> {
    pub fn new(
        worlds: &'w dyn WorldOps,
        world: &str,
        from: (i32, i32, i32),
        to: (i32, i32, i32),
    ) -> Self {
        let (x0, y0, z0) = from;
        let size = (to.0 - x0 + 1, to.1 - y0 + 1, to.2 - z0 + 1);
        let mut shapes = RegionShapes {
            worlds,
            world: world.to_owned(),
            origin: from,
            size,
            cells: vec![None; (size.0.max(0) * size.1.max(0) * size.2.max(0)) as usize],
        };
        let mut loaded: HashMap<(i32, i32), bool> = HashMap::new();
        for x in 0..size.0 {
            for z in 0..size.2 {
                let chunk = ((x0 + x) >> 4, (z0 + z) >> 4);
                let is = *loaded
                    .entry(chunk)
                    .or_insert_with(|| worlds.is_chunk_loaded(world.to_owned(), chunk.0, chunk.1));
                if is {
                    for y in 0..size.1 {
                        let at = shapes.index(x, y, z);
                        shapes.cells[at] = Some(Vec::new());
                    }
                }
            }
        }
        let boxes = worlds.collision_boxes(
            world.to_owned(),
            f64::from(x0),
            f64::from(y0),
            f64::from(z0),
            f64::from(to.0 + 1) - INSIDE,
            f64::from(to.1 + 1) - INSIDE,
            f64::from(to.2 + 1) - INSIDE,
        );
        for b in boxes {
            // A block's boxes start inside it (a fence's only reach up out of it).
            let x = (b.min.0 + INSIDE).floor() as i32 - x0;
            let y = (b.min.1 + INSIDE).floor() as i32 - y0;
            let z = (b.min.2 + INSIDE).floor() as i32 - z0;
            if !(0..size.0).contains(&x) || !(0..size.1).contains(&y) || !(0..size.2).contains(&z) {
                continue;
            }
            let at = shapes.index(x, y, z);
            if let Some(known) = &mut shapes.cells[at] {
                known.push(b);
            }
        }
        shapes
    }

    fn index(&self, x: i32, y: i32, z: i32) -> usize {
        ((x * self.size.2 + z) * self.size.1 + y) as usize
    }

    /// The blocks around the region from `min` to `max`, and the block below it.
    pub fn around(worlds: &'w dyn WorldOps, world: &str, min: Vec3, max: Vec3) -> Self {
        RegionShapes::new(
            worlds,
            world,
            (
                min.0.floor() as i32,
                min.1.floor() as i32 - 1,
                min.2.floor() as i32,
            ),
            (
                max.0.floor() as i32,
                max.1.floor() as i32,
                max.2.floor() as i32,
            ),
        )
    }
}

impl BlockShapes for RegionShapes<'_> {
    fn boxes(&self, x: i32, y: i32, z: i32) -> Option<Vec<BoundingBox>> {
        let (rx, ry, rz) = (x - self.origin.0, y - self.origin.1, z - self.origin.2);
        if (0..self.size.0).contains(&rx)
            && (0..self.size.1).contains(&ry)
            && (0..self.size.2).contains(&rz)
        {
            return self.cells[self.index(rx, ry, rz)].clone();
        }
        if !self
            .worlds
            .is_chunk_loaded(self.world.clone(), x >> 4, z >> 4)
        {
            return None;
        }
        Some(self.worlds.collision_boxes(
            self.world.clone(),
            f64::from(x),
            f64::from(y),
            f64::from(z),
            f64::from(x + 1) - INSIDE,
            f64::from(y + 1) - INSIDE,
            f64::from(z + 1) - INSIDE,
        ))
    }
}

/// A world's blocks for one search: read `BRICK` blocks a side at a time (a brick never crosses a chunk) as
/// the search reaches them, and kept until it's done.
pub struct BrickShapes<'w> {
    worlds: &'w dyn WorldOps,
    world: String,
    bricks: RefCell<HashMap<(i32, i32, i32), RegionShapes<'w>>>,
}

const BRICK: i32 = 8;

impl<'w> BrickShapes<'w> {
    pub fn new(worlds: &'w dyn WorldOps, world: &str) -> Self {
        BrickShapes {
            worlds,
            world: world.to_owned(),
            bricks: RefCell::default(),
        }
    }
}

impl BlockShapes for BrickShapes<'_> {
    fn boxes(&self, x: i32, y: i32, z: i32) -> Option<Vec<BoundingBox>> {
        let key = (
            x.div_euclid(BRICK),
            y.div_euclid(BRICK),
            z.div_euclid(BRICK),
        );
        let mut bricks = self.bricks.borrow_mut();
        let brick = bricks.entry(key).or_insert_with(|| {
            let from = (key.0 * BRICK, key.1 * BRICK, key.2 * BRICK);
            RegionShapes::new(
                self.worlds,
                &self.world,
                from,
                (from.0 + BRICK - 1, from.1 + BRICK - 1, from.2 + BRICK - 1),
            )
        });
        brick.boxes(x, y, z)
    }
}
