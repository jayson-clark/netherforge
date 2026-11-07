//! A project generator crossing to the JVM (`SharedTerrain`), whose calls come from every one of the server's chunk
//! threads: unlike the generated glue's objects, which live on the main thread, it's kept in a registry of its own that
//! any thread reaches ([`shared`]), and let go of at once from any thread. Its wire is written by hand, here and in
//! `apps/plugin/ffi`'s `RustTerrainSource`: the methods by number in [`dispatch`]'s order.

use std::collections::{BTreeSet, HashMap};
use std::sync::atomic::Ordering;
use std::sync::{Arc, LazyLock, Mutex};

use netherforge_platform::{GeneratedChunk, SharedTerrain, TerrainWorld};

use crate::objects;
use crate::wire::{Put, Reader, Take, Writer};

/// What runs a call to a thread-safe object: from any thread.
pub type SharedDispatch = Arc<dyn Fn(u32, &mut Reader, &mut Writer) + Send + Sync>;

static SHARED: LazyLock<Mutex<HashMap<u64, SharedDispatch>>> = LazyLock::new(Default::default);

/// Hands [dispatch] out to any thread: its id, from the same numbers as the main thread's objects.
pub fn export(dispatch: SharedDispatch) -> u64 {
    let id = objects::NEXT.fetch_add(1, Ordering::Relaxed);
    SHARED
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .insert(id, dispatch);
    id
}

/// What thread-safe object [id] runs, if it wasn't let go.
pub fn shared(id: u64) -> Option<SharedDispatch> {
    SHARED
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .get(&id)
        .cloned()
}

/// Lets go of thread-safe object [id] now, if it's one: false when it isn't.
pub fn release(id: u64) -> bool {
    let gone = SHARED.lock().unwrap_or_else(|e| e.into_inner()).remove(&id);
    gone.is_some()
}

/// How many thread-safe objects are handed out (for tests).
pub fn shared_count() -> usize {
    SHARED.lock().unwrap_or_else(|e| e.into_inner()).len()
}

impl Put for SharedTerrain {
    fn put(self, w: &mut Writer) {
        w.u8(objects::RUST);
        w.u64(export(dispatch(self)));
    }
}

impl Take for SharedTerrain {
    fn take(_: &mut Reader) -> Self {
        panic!("a TerrainSource is the Rust runtime's: none comes from the JVM")
    }
}

impl Take for TerrainWorld {
    fn take(r: &mut Reader) -> Self {
        TerrainWorld {
            seed: i64::take(r),
            min_y: i32::take(r),
            max_y: i32::take(r),
        }
    }
}

impl Put for GeneratedChunk {
    fn put(self, w: &mut Writer) {
        self.min_y.put(w);
        self.height.put(w);
        self.blocks.put(w);
        w.count(self.loot.len());
        for loot in self.loot {
            (loot.x, loot.y, loot.z).put(w);
            loot.table.put(w);
        }
    }
}

/// The generator's methods by number: the order `RustTerrainSource` calls them in.
fn dispatch(source: SharedTerrain) -> SharedDispatch {
    let source = source.0;
    Arc::new(move |method, r, w| match method {
        0 => source.stone().put(w),
        1 => source.sea_level().put(w),
        2 => source.vanilla_structures().put(w),
        3 => source.biomes().put(w),
        4 => source.has_loot().put(w),
        5 => {
            let world = TerrainWorld::take(r);
            let (x, z) = (i32::take(r), i32::take(r));
            source.generate(world, x, z).put(w);
        }
        6 => {
            let world = TerrainWorld::take(r);
            let (x, z) = (i32::take(r), i32::take(r));
            source.surface_at(world, x, z).put(w);
        }
        7 => {
            let world = TerrainWorld::take(r);
            let (x, z) = (i32::take(r), i32::take(r));
            source.dry_column_near(world, x, z).put(w);
        }
        8 => {
            let world = TerrainWorld::take(r);
            let (x, y, z) = (i32::take(r), i32::take(r), i32::take(r));
            source.biome_at(world, x, y, z).put(w);
        }
        9 => {
            let world = TerrainWorld::take(r);
            let (y, step) = (i32::take(r), i32::take(r));
            source.locate_heights(world, y, step).put(w);
        }
        10 => {
            let world = TerrainWorld::take(r);
            let columns = <Vec<(i32, i32)>>::take(r);
            let ys = <Vec<i32>>::take(r);
            let wanted: BTreeSet<String> = <Vec<String>>::take(r).into_iter().collect();
            source.first_where(world, &columns, &ys, &wanted).put(w);
        }
        11 => source.close(),
        other => panic!("TerrainSource has no method {other}"),
    })
}
