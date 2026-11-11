//! The blocks of a world as movers, walks and path searches see them (Kotlin's `RegionShapes` and `BrickShapes`).
//!
//! A region is read from the platform in one `collision_boxes` call and filed by the block each box belongs to; a
//! block in a chunk that isn't loaded answers none, never loaded. `RegionShapes` is one such read, for one look.
//! `BlockCache` keeps them across ticks, by `BRICK` blocks a side: every walking centity asked the server for the
//! blocks around it every tick (and for the block at its middle, for whether it's in a liquid), the most of what
//! crossed to the JVM on Kosmoi's entities baseline. A brick is read again after `BRICK_TICKS` (the changes nothing
//! says: a piston, fluids), and at once when a block in it is set or broken (`Shared::block_changed`: by a script,
//! the server's events, a walk's doors).

use std::cell::{Cell, RefCell};
use std::rc::Rc;
/// foldhash, not SipHash: a mover reads its region every tick, keyed by nothing an attacker picks.
type HashMap<K, V> = std::collections::HashMap<K, V, foldhash::fast::RandomState>;

use netherforge_format::math::{BoundingBox, Vec3};
use netherforge_platform::{BlockOps, WorldOps};

/// A block's (or a brick's) position.
type Cell3 = (i32, i32, i32);

/// Pulls a region's far faces in, so the platform's whole-block loops stop at the last block.
const INSIDE: f64 = 1e-6;

/// Blocks a side of a kept brick (one never crosses a chunk).
const BRICK: i32 = 8;
/// Ticks a brick's blocks are kept before they're read again: a second.
pub const BRICK_TICKS: u64 = 20;
/// Ticks a brick nothing asked for is kept.
const BRICK_IDLE: u64 = 200;

/// What a block is made of to something moving through it.
pub trait BlockShapes {
    /// The collision boxes of the block at (x, y, z), in world coordinates: empty for air, none when its
    /// chunk isn't loaded (a walker treats it as a wall).
    fn boxes(&self, x: i32, y: i32, z: i32) -> Option<Vec<BoundingBox>>;
}

/// One region's blocks as read: each cell's boxes, or none in a chunk that isn't loaded.
struct Region {
    origin: (i32, i32, i32),
    size: (i32, i32, i32),
    cells: Vec<Option<Vec<BoundingBox>>>,
}

impl Region {
    fn read(
        worlds: &dyn WorldOps,
        world: &str,
        from: (i32, i32, i32),
        to: (i32, i32, i32),
    ) -> Region {
        let (x0, y0, z0) = from;
        let size = (to.0 - x0 + 1, to.1 - y0 + 1, to.2 - z0 + 1);
        let mut region = Region {
            origin: from,
            size,
            cells: vec![None; (size.0.max(0) * size.1.max(0) * size.2.max(0)) as usize],
        };
        let mut loaded: HashMap<(i32, i32), bool> = HashMap::default();
        for x in 0..size.0 {
            for z in 0..size.2 {
                let chunk = ((x0 + x) >> 4, (z0 + z) >> 4);
                let is = *loaded
                    .entry(chunk)
                    .or_insert_with(|| worlds.is_chunk_loaded(world.to_owned(), chunk.0, chunk.1));
                if is {
                    for y in 0..size.1 {
                        let at = region.index(x, y, z);
                        region.cells[at] = Some(Vec::new());
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
            let at = region.index(x, y, z);
            if let Some(known) = &mut region.cells[at] {
                known.push(b);
            }
        }
        region
    }

    fn index(&self, x: i32, y: i32, z: i32) -> usize {
        ((x * self.size.2 + z) * self.size.1 + y) as usize
    }

    /// The block at (x, y, z), when it's inside.
    fn cell(&self, x: i32, y: i32, z: i32) -> Option<&Option<Vec<BoundingBox>>> {
        let (rx, ry, rz) = (x - self.origin.0, y - self.origin.1, z - self.origin.2);
        let inside = (0..self.size.0).contains(&rx)
            && (0..self.size.1).contains(&ry)
            && (0..self.size.2).contains(&rz);
        inside.then(|| &self.cells[self.index(rx, ry, rz)])
    }
}

/// One region's blocks read for one look; a block outside it is asked for on its own.
pub struct RegionShapes<'w> {
    worlds: &'w dyn WorldOps,
    world: String,
    region: Region,
}

impl<'w> RegionShapes<'w> {
    pub fn new(
        worlds: &'w dyn WorldOps,
        world: &str,
        from: (i32, i32, i32),
        to: (i32, i32, i32),
    ) -> Self {
        RegionShapes {
            worlds,
            world: world.to_owned(),
            region: Region::read(worlds, world, from, to),
        }
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
        if let Some(cell) = self.region.cell(x, y, z) {
            return cell.clone();
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

/// A brick as read, when, and when something last asked for it.
struct Brick {
    region: Rc<Region>,
    read: u64,
    asked: Cell<u64>,
}

/// One world's kept bricks, and whether its blocks are liquid (for whether a mover is in one), by when each was read.
#[derive(Default)]
struct WorldBlocks {
    bricks: RefCell<HashMap<Cell3, Brick>>,
    /// Each block's liquid-ness, and the tick it was read.
    liquids: RefCell<HashMap<Cell3, (u64, bool)>>,
}

/// Worlds' blocks kept across ticks for movers, walks and path searches (see the module's doc).
#[derive(Default)]
pub struct BlockCache {
    worlds: RefCell<HashMap<String, Rc<WorldBlocks>>>,
}

fn brick_of(x: i32, y: i32, z: i32) -> (i32, i32, i32) {
    (
        x.div_euclid(BRICK),
        y.div_euclid(BRICK),
        z.div_euclid(BRICK),
    )
}

impl BlockCache {
    fn world(&self, name: &str) -> Rc<WorldBlocks> {
        if let Some(w) = self.worlds.borrow().get(name) {
            return w.clone();
        }
        let w = Rc::new(WorldBlocks::default());
        self.worlds.borrow_mut().insert(name.to_owned(), w.clone());
        w
    }

    /// The block at (x, y, z) in `world` was set or broken: what's kept of it is read again when next asked.
    pub fn changed(&self, world: &str, x: i32, y: i32, z: i32) {
        if let Some(w) = self.worlds.borrow().get(world) {
            w.bricks.borrow_mut().remove(&brick_of(x, y, z));
            w.liquids.borrow_mut().remove(&(x, y, z));
        }
    }

    /// Forgets what nothing asked for lately.
    pub fn sweep(&self, tick: u64) {
        for w in self.worlds.borrow().values() {
            w.bricks
                .borrow_mut()
                .retain(|_, b| tick.saturating_sub(b.asked.get()) <= BRICK_IDLE);
            w.liquids
                .borrow_mut()
                .retain(|_, (read, _)| tick.saturating_sub(*read) < BRICK_TICKS);
        }
        self.worlds
            .borrow_mut()
            .retain(|_, w| !w.bricks.borrow().is_empty() || !w.liquids.borrow().is_empty());
    }

    /// `world`'s blocks at tick `tick`, read through the platform's `worlds` where they're not kept.
    pub fn shapes<'a>(&self, worlds: &'a dyn WorldOps, world: &str, tick: u64) -> CachedShapes<'a> {
        CachedShapes {
            worlds,
            world: world.to_owned(),
            blocks: self.world(world),
            tick,
            last: RefCell::new(None),
        }
    }

    /// Whether the block at (x, y, z) in `world` is a liquid, kept as bricks are.
    pub fn liquid(
        &self,
        blocks: &dyn BlockOps,
        world: &str,
        (x, y, z): (i32, i32, i32),
        tick: u64,
    ) -> bool {
        let w = self.world(world);
        if let Some((read, liquid)) = w.liquids.borrow().get(&(x, y, z))
            && tick.saturating_sub(*read) < BRICK_TICKS
        {
            return *liquid;
        }
        let liquid = blocks
            .get(world.to_owned(), x, y, z)
            .is_some_and(|b| b.liquid);
        w.liquids.borrow_mut().insert((x, y, z), (tick, liquid));
        liquid
    }
}

/// One world's blocks as a `BlockCache` keeps them, for one mover's step, one walk's or one search.
pub struct CachedShapes<'a> {
    worlds: &'a dyn WorldOps,
    world: String,
    blocks: Rc<WorldBlocks>,
    tick: u64,
    /// The brick asked last: most questions in a row are about blocks in the same one.
    last: RefCell<Option<(Cell3, Rc<Region>)>>,
}

impl CachedShapes<'_> {
    fn brick(&self, key: (i32, i32, i32)) -> Rc<Region> {
        if let Some((k, region)) = &*self.last.borrow()
            && *k == key
        {
            return region.clone();
        }
        let kept = self.blocks.bricks.borrow().get(&key).and_then(|b| {
            b.asked.set(self.tick);
            (self.tick.saturating_sub(b.read) < BRICK_TICKS).then(|| b.region.clone())
        });
        let region = kept.unwrap_or_else(|| {
            let from = (key.0 * BRICK, key.1 * BRICK, key.2 * BRICK);
            let to = (from.0 + BRICK - 1, from.1 + BRICK - 1, from.2 + BRICK - 1);
            let region = Rc::new(Region::read(self.worlds, &self.world, from, to));
            self.blocks.bricks.borrow_mut().insert(
                key,
                Brick {
                    region: region.clone(),
                    read: self.tick,
                    asked: Cell::new(self.tick),
                },
            );
            region
        });
        *self.last.borrow_mut() = Some((key, region.clone()));
        region
    }
}

impl BlockShapes for CachedShapes<'_> {
    fn boxes(&self, x: i32, y: i32, z: i32) -> Option<Vec<BoundingBox>> {
        let region = self.brick(brick_of(x, y, z));
        region.cell(x, y, z).and_then(|cell| cell.clone())
    }
}
