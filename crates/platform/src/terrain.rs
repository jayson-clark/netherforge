//! A project generator as the server's chunk threads run it (Kotlin's `platform.terrain.TerrainSource`): any thread, at
//! once, any number of worlds (each call says which). Not part of the generated glue, whose objects live on the main
//! thread: `crates/ffi` writes its wire by hand.

use std::collections::BTreeSet;
use std::sync::Arc;

/// Which world a generator makes: its seed, and its blocks from `min_y` up to but not including `max_y`.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct TerrainWorld {
    pub seed: i64,
    pub min_y: i32,
    pub max_y: i32,
}

/// A chunk a generator made: `blocks` are indexes into its palette (`ProjectGenerator::states`), column by column
/// (`x`, then `z`), each from the bottom up, `height` a column; `loot` the containers it fills, by the server's tables.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct GeneratedChunk {
    pub min_y: i32,
    pub height: i32,
    pub blocks: Vec<i32>,
    pub loot: Vec<GeneratedLoot>,
}

/// A container a generator fills: where, and from which loot table.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct GeneratedLoot {
    pub x: i32,
    pub y: i32,
    pub z: i32,
    pub table: String,
}

/// See the module.
pub trait TerrainSource: Send + Sync {
    /// The block state the palette's stone is: what a state the server doesn't know is written as instead.
    fn stone(&self) -> String;
    fn sea_level(&self) -> i32;
    /// Whether the game's own structures generate in its worlds.
    fn vanilla_structures(&self) -> bool;
    /// The biomes its areas name (as `ProjectGenerator::biomes` keys them), in its file's order.
    fn biomes(&self) -> Vec<String>;
    /// Whether it fills any container from a loot table.
    fn has_loot(&self) -> bool;
    fn generate(&self, world: TerrainWorld, chunk_x: i32, chunk_z: i32) -> GeneratedChunk;
    /// The y of the top block of the terrain at a column.
    fn surface_at(&self, world: TerrainWorld, x: i32, z: i32) -> i32;
    /// A column near (`x`, `z`) whose top isn't under water.
    fn dry_column_near(&self, world: TerrainWorld, x: i32, z: i32) -> Option<(i32, i32)>;
    /// The biome at a place, as `biomes` names it.
    fn biome_at(&self, world: TerrainWorld, x: i32, y: i32, z: i32) -> String;
    /// The heights `/locate biome` looks at from `y` outwards, every `step`.
    fn locate_heights(&self, world: TerrainWorld, y: i32, step: i32) -> Vec<i32>;
    /// The first of `columns` (`x, z` pairs) with one of `wanted` at one of `ys`: its index and the y.
    fn first_where(
        &self,
        world: TerrainWorld,
        columns: &[(i32, i32)],
        ys: &[i32],
        wanted: &BTreeSet<String>,
    ) -> Option<(i32, i32)>;
    /// Lets go of what it keeps (its scripts' states).
    fn close(&self);
}

/// A generator as the Platform passes it: shared with every chunk thread. Two are equal when they're the same one.
#[derive(Clone)]
pub struct SharedTerrain(pub Arc<dyn TerrainSource>);

impl std::fmt::Debug for SharedTerrain {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("SharedTerrain")
    }
}

impl PartialEq for SharedTerrain {
    fn eq(&self, other: &Self) -> bool {
        Arc::ptr_eq(&self.0, &other.0)
    }
}
