//! The generator of one world (format's `TerrainGenerator`, `ChunkBuffer`,
//! `ChunkGeneration` and their `Sampler`): a `CompiledTerrain` with a world's
//! seed and heights. Everything is made as it's bound and only read after, so
//! one generator serves any number of threads; a `Sampler` asks heights and
//! areas for one thread, keeping what it works out.
//!
//! Generating a chunk runs its stages in order (the terrain, the script's
//! `terrain` stage, caves, the floor, ores, decorations, the script's
//! `decorate` stage), each from the chunk's position and the world's seed
//! alone, so a chunk never depends on the ones made before it. Its random
//! numbers are Kotlin's `Random` (`crate::random`), and its noise format's
//! doubles, so a seed is the same world it was in format's Kotlin.

use std::cell::RefCell;
use std::collections::HashMap;
use std::rc::Rc;

use super::compiled::{
    CompiledArea, CompiledCave, CompiledDecoration, CompiledLayer, CompiledOre, CompiledTerrain,
    LinkedTemplate, TerrainBlock,
};
use super::density::{CELL_XZ, Corner, CornerColumn, CornerSource, DensityColumn, DensityField};
use super::{CaveType, DecorationPlacement, OreDistribution};
use crate::noise::fast_noise_lite::FastNoiseLite;
use crate::noise::seeds;
use crate::random::Random;

/// The spacing of the points biome areas are read at for blending heights, and of the grid their weights are kept on.
pub const BLEND_CELL: i32 = 8;
/// How far a vein reaches from where it starts along any axis: less than a chunk, so neighbours can be made alone.
const REACH: i32 = 15;
/// How far apart `dry_column_near` looks.
const STEP: i32 = 8;
/// How many points a sampler keeps of each kind before it starts again.
const CACHE_LIMIT: usize = 1 << 16;

/// The blocks of one chunk column by column, as indexes into the terrain's
/// palette (0 is air), from `min_y` up to but not including `max_y`.
#[derive(Clone, Debug, PartialEq)]
pub struct ChunkBuffer {
    pub min_y: i32,
    pub max_y: i32,
    pub height: i32,
    /// Column by column (`x`, then `z`), each from the bottom up.
    pub blocks: Vec<i32>,
    /// The containers to fill from a loot table: a block's index in `blocks` to the table's in the terrain's `loot`.
    pub loot: HashMap<usize, i32>,
}

/// A container a generated chunk fills from loot table `table` (an index into the terrain's `loot`).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct LootPlace {
    pub x: i32,
    pub y: i32,
    pub z: i32,
    pub table: i32,
}

impl ChunkBuffer {
    pub fn new(min_y: i32, max_y: i32) -> ChunkBuffer {
        let height = max_y - min_y;
        ChunkBuffer {
            min_y,
            max_y,
            height,
            blocks: vec![0; 16 * 16 * height as usize],
            loot: HashMap::new(),
        }
    }

    /// Each container `loot` marks: its place in the world (for the chunk at `chunk_x`, `chunk_z`) and table.
    pub fn loot_places(&self, chunk_x: i32, chunk_z: i32) -> Vec<LootPlace> {
        let mut entries: Vec<(&usize, &i32)> = self.loot.iter().collect();
        entries.sort();
        entries
            .into_iter()
            .map(|(index, table)| {
                let column = *index as i32 / self.height;
                LootPlace {
                    x: chunk_x * 16 + column / 16,
                    y: self.min_y + *index as i32 % self.height,
                    z: chunk_z * 16 + column % 16,
                    table: *table,
                }
            })
            .collect()
    }

    pub fn column_start(&self, local_x: i32, local_z: i32) -> usize {
        ((local_x * 16 + local_z) * self.height) as usize
    }

    pub fn get(&self, local_x: i32, y: i32, local_z: i32) -> i32 {
        self.blocks[self.column_start(local_x, local_z) + (y - self.min_y) as usize]
    }

    pub fn set(&mut self, local_x: i32, y: i32, local_z: i32, block: i32) {
        let at = self.column_start(local_x, local_z) + (y - self.min_y) as usize;
        self.blocks[at] = block;
    }

    /// Sets `from_y` to `to_y`, both included, of one column; the part outside the chunk's height is left out.
    pub fn fill(&mut self, local_x: i32, local_z: i32, from_y: i32, to_y: i32, block: i32) {
        let start = self.column_start(local_x, local_z);
        for y in from_y.max(self.min_y)..=to_y.min(self.max_y - 1) {
            self.blocks[start + (y - self.min_y) as usize] = block;
        }
    }
}

/// A script's stages, as a set.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Stages(u8);

/// One of the stages a terrain's script may hand back.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Stage {
    Height,
    Density,
    Area,
    Biome,
    Terrain,
    Decorate,
}

impl Stage {
    pub const ALL: [Stage; 6] = [
        Stage::Height,
        Stage::Density,
        Stage::Area,
        Stage::Biome,
        Stage::Terrain,
        Stage::Decorate,
    ];

    /// Its name, as the script's table of stages has it.
    pub fn name(self) -> &'static str {
        match self {
            Stage::Height => "height",
            Stage::Density => "density",
            Stage::Area => "area",
            Stage::Biome => "biome",
            Stage::Terrain => "terrain",
            Stage::Decorate => "decorate",
        }
    }

    pub fn named(name: &str) -> Option<Stage> {
        Stage::ALL.into_iter().find(|s| s.name() == name)
    }
}

impl Stages {
    pub fn with(self, stage: Stage) -> Stages {
        Stages(self.0 | 1 << stage as u8)
    }

    pub fn contains(self, stage: Stage) -> bool {
        self.0 & (1 << stage as u8) != 0
    }
}

/// A terrain's script, bound to one generator: a pool of states, one per thread
/// generating at once, each made on first need and kept for the next call
/// (format's `ScriptRunner`). Its random numbers and noises are the generator's
/// (`ScriptSeeds`); how a state runs the script is the host's.
pub trait TerrainScript: Send + Sync {
    /// The stages the script returns (none when its body fails): the same in every state.
    fn stages(&self) -> Stages;
    fn take(&self, generator: &TerrainGenerator) -> Box<dyn ScriptState>;
    fn give(&self, state: Box<dyn ScriptState>);
    /// Lets go of every state (one in use goes as it's given back).
    fn close(&self);
}

/// One state running a script, used by one thread at a time. What it asks of
/// other columns, it asks a sampler of its own with no script state
/// (`TerrainGenerator::sampler(None)`), so a stage that answers it runs in
/// another state: a call never comes back into the state that made it.
pub trait ScriptState {
    /// The height of column (x, z) from the file's `height`; none when the stage failed there.
    fn height(&mut self, generator: &TerrainGenerator, x: i32, z: i32, height: i32) -> Option<i32>;
    /// The density at the grid's point from the file's `value` there; none when the stage failed there.
    fn density(
        &mut self,
        generator: &TerrainGenerator,
        x: i32,
        y: i32,
        z: i32,
        value: f64,
    ) -> Option<f64>;
    /// The area of column (x, z) from the file's; none when the stage failed there.
    fn area(&mut self, generator: &TerrainGenerator, x: i32, z: i32, file: usize) -> Option<usize>;
    /// The area of the place (x, y, z) from the file's; none when the stage failed there.
    fn biome(
        &mut self,
        generator: &TerrainGenerator,
        x: i32,
        y: i32,
        z: i32,
        file: usize,
    ) -> Option<usize>;
    /// Runs chunk stage `stage`; false when it failed (its changes are then undone).
    fn chunk_stage(
        &mut self,
        generator: &TerrainGenerator,
        stage: Stage,
        chunk: &mut ChunkGeneration,
    ) -> bool;
}

/// What makes a terrain's script for a generator: the host (the server's or
/// the editor's Luau) with the files the script reads.
pub trait TerrainScripts {
    fn bind(
        &self,
        terrain: &CompiledTerrain,
        seed: i64,
        min_y: i32,
        max_y: i32,
    ) -> Box<dyn TerrainScript>;
}

/// The seeds a script's `math.random` takes for each call, and its noises:
/// from the world's seed alone, so a script's world is the same everywhere.
pub struct ScriptSeeds {
    height: i32,
    terrain: i32,
    decorate: i32,
    density: i32,
    area: i32,
    biome: i32,
}

impl ScriptSeeds {
    pub fn new(seed: i64) -> ScriptSeeds {
        let role = |name: &str| seeds::for_role(seed, &format!("script:{name}"));
        ScriptSeeds {
            height: role("height"),
            terrain: role("terrain"),
            decorate: role("decorate"),
            density: role("density"),
            area: role("area"),
            biome: role("biome"),
        }
    }

    /// The noises a script asks for, in its compiled order.
    pub fn noises(terrain: &CompiledTerrain, seed: i64) -> Vec<FastNoiseLite> {
        terrain
            .script
            .iter()
            .flat_map(|s| s.noises.iter())
            .map(|n| {
                n.noise
                    .build(seeds::for_role(seed, &format!("script:noise:{}", n.name)))
            })
            .collect()
    }

    pub fn height(&self, x: i32, z: i32) -> i32 {
        place(self.height, x, z)
    }

    pub fn chunk(&self, stage: Stage, chunk_x: i32, chunk_z: i32) -> i32 {
        place(
            if stage == Stage::Terrain {
                self.terrain
            } else {
                self.decorate
            },
            chunk_x,
            chunk_z,
        )
    }

    pub fn density(&self, x: i32, y: i32, z: i32) -> i32 {
        seeds::mix(place(self.density, x, z) ^ y.wrapping_mul(0x5BD1_E995))
    }

    pub fn area(&self, x: i32, z: i32) -> i32 {
        place(self.area, x, z)
    }

    pub fn biome(&self, x: i32, y: i32, z: i32) -> i32 {
        seeds::mix(place(self.biome, x, z) ^ y.wrapping_mul(0x5BD1_E995))
    }
}

fn place(role: i32, x: i32, z: i32) -> i32 {
    seeds::mix(seeds::mix(role ^ x.wrapping_mul(0x1B87_3593)) ^ z.wrapping_mul(0x2C1B_3C6D))
}

/// One chunk being generated, as each stage sees it.
pub struct ChunkGeneration<'a> {
    pub chunk_x: i32,
    pub chunk_z: i32,
    pub buffer: &'a mut ChunkBuffer,
    /// The y of each column's top block (`local_x * 16 + local_z`); with a density its topmost solid one.
    pub surface: Vec<i32>,
    /// The height each column's 2D terrain and the script's `height` stage make.
    pub base: Vec<i32>,
    /// Each column's biome area, as an index into the terrain's areas.
    pub areas: Vec<usize>,
    /// With a density, which blocks the terrain makes solid, laid out as the buffer's.
    pub solid: Option<Vec<bool>>,
    /// When a place's biome can differ from its column's: the area of each 4x4x4 cell.
    pub cells: Option<Vec<usize>>,
}

impl ChunkGeneration<'_> {
    pub fn min_x(&self) -> i32 {
        self.chunk_x * 16
    }

    pub fn min_z(&self) -> i32 {
        self.chunk_z * 16
    }

    /// The area at a place in the chunk: its cell's when places differ from their column, else its column's.
    pub fn area_at(&self, local_x: i32, y: i32, local_z: i32) -> usize {
        let Some(cells) = &self.cells else {
            return self.areas[(local_x * 16 + local_z) as usize];
        };
        let height = (self.buffer.height + 3) / 4;
        cells[(((local_x >> 2) * 4 + (local_z >> 2)) * height + ((y - self.buffer.min_y) >> 2))
            as usize]
    }
}

/// A place a decoration was tried at that passed everything its column alone decides.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct DecorationSite {
    pub x: i32,
    pub y: i32,
    pub z: i32,
    pub turn: i32,
}

pub struct TerrainGenerator {
    pub terrain: CompiledTerrain,
    pub seed: i64,
    pub min_y: i32,
    pub max_y: i32,
    column_areas: Vec<usize>,
    volume_areas: Vec<usize>,
    climate_noises: Vec<FastNoiseLite>,
    height_noises: Vec<FastNoiseLite>,
    area_noises: Vec<Vec<FastNoiseLite>>,
    temperature_noise: FastNoiseLite,
    humidity_noise: FastNoiseLite,
    jittered: bool,
    jitter_x: FastNoiseLite,
    jitter_z: FastNoiseLite,
    one_terrain: bool,
    blended: bool,
    kernel: Vec<(i32, i32, f64)>,
    cave_noises: Vec<(FastNoiseLite, Option<FastNoiseLite>)>,
    cave_areas: Vec<Option<Vec<bool>>>,
    ore_areas: Vec<Option<Vec<bool>>>,
    floor_seed: i32,
    decoration_seeds: Vec<i32>,
    decoration_noises: Vec<Option<FastNoiseLite>>,
    decoration_areas: Vec<Option<Vec<bool>>>,
    decoration_on: Vec<Vec<bool>>,
    decoration_reach: Vec<i32>,
    script: Option<Box<dyn TerrainScript>>,
    density: Option<DensityField>,
}

impl CompiledTerrain {
    /// The generator of the world seeded `seed` with blocks from `min_y` up to
    /// but not including `max_y`. A terrain with a script runs it with
    /// `scripts`; without them only the file's own stages run, which is never
    /// what a world gets.
    pub fn bind(
        &self,
        seed: i64,
        min_y: i32,
        max_y: i32,
        scripts: Option<&dyn TerrainScripts>,
    ) -> TerrainGenerator {
        TerrainGenerator::new(self.clone(), seed, min_y, max_y, scripts)
    }
}

impl TerrainGenerator {
    fn new(
        terrain: CompiledTerrain,
        seed: i64,
        min_y: i32,
        max_y: i32,
        scripts: Option<&dyn TerrainScripts>,
    ) -> TerrainGenerator {
        let areas = &terrain.areas;
        let role = |name: String| seeds::for_role(seed, &name);
        let mask = |indexes: &[usize]| {
            (!indexes.is_empty()).then(|| {
                (0..areas.len())
                    .map(|a| indexes.contains(&a))
                    .collect::<Vec<bool>>()
            })
        };
        let first = &areas[0].terrain;
        let one_terrain = areas.iter().all(|a| {
            a.terrain.base == first.base
                && a.terrain.scale == first.scale
                && a.terrain.noises == first.noises
        });
        let reach = (terrain.blend + BLEND_CELL - 1) / BLEND_CELL;
        let weights: Vec<f64> = (-reach..=reach)
            .map(|i| f64::from(0.max(terrain.blend + BLEND_CELL - i.abs() * BLEND_CELL)))
            .collect();
        let total = weights.iter().fold(0.0, |s, w| s + w) * weights.iter().fold(0.0, |s, w| s + w);
        let mut kernel = Vec::new();
        for i in -reach..=reach {
            for j in -reach..=reach {
                let w = weights[(i + reach) as usize] * weights[(j + reach) as usize] / total;
                if w > 0.0 {
                    kernel.push((i, j, w));
                }
            }
        }
        let decoration_on = terrain
            .decorations
            .iter()
            .map(|d| {
                terrain
                    .palette
                    .iter()
                    .enumerate()
                    .map(|(i, block)| {
                        if !d.on.is_empty() || !d.on_custom.is_empty() {
                            match block {
                                TerrainBlock::Vanilla { state } => {
                                    d.on.iter()
                                        .any(|on| on == state.split('[').next().unwrap_or(state))
                                }
                                TerrainBlock::Custom { name } => d.on_custom.contains(name),
                            }
                        } else if d.placement == DecorationPlacement::Underground {
                            i as i32 == terrain.stone
                        } else {
                            i != 0 && i as i32 != terrain.fluid
                        }
                    })
                    .collect()
            })
            .collect();
        let decoration_reach = terrain
            .decorations
            .iter()
            .map(
                |d| match d.structure.as_ref().and_then(|s| terrain.templates.get(s)) {
                    Some(t) => (t.size_x.max(t.size_z) + 15) / 16,
                    None => 0,
                },
            )
            .collect();
        let script = terrain
            .script
            .as_ref()
            .and(scripts)
            .map(|s| s.bind(&terrain, seed, min_y, max_y));
        let density = terrain
            .density
            .as_ref()
            .map(|d| DensityField::new(&terrain, d, seed, min_y, max_y));
        TerrainGenerator {
            column_areas: terrain.column_areas(),
            volume_areas: terrain.volume_areas(),
            climate_noises: terrain
                .climate
                .iter()
                .map(|c| c.noise.build(role(format!("climate:noise:{}", c.name))))
                .collect(),
            height_noises: terrain
                .noises
                .iter()
                .map(|n| n.noise.build(role(format!("height:{}", n.name))))
                .collect(),
            area_noises: areas
                .iter()
                .map(|a| {
                    a.terrain
                        .noises
                        .iter()
                        .map(|n| {
                            n.noise
                                .build(role(format!("area:{}:height:{}", a.name, n.name)))
                        })
                        .collect()
                })
                .collect(),
            temperature_noise: terrain
                .temperature
                .build(role("climate:temperature".into())),
            humidity_noise: terrain.humidity.build(role("climate:humidity".into())),
            jittered: areas.len() > 1 && terrain.jitter_amplitude > 0.0,
            jitter_x: terrain.jitter.build(role("climate:jitter:x".into())),
            jitter_z: terrain.jitter.build(role("climate:jitter:z".into())),
            one_terrain,
            blended: !one_terrain && terrain.blend > 0,
            kernel,
            cave_noises: terrain
                .caves
                .iter()
                .map(|c| {
                    let first = c.noise.build(role(format!("cave:{}", c.name)));
                    let second = (c.kind == CaveType::Spaghetti)
                        .then(|| c.noise.build(role(format!("cave:{}:b", c.name))));
                    (first, second)
                })
                .collect(),
            cave_areas: terrain.caves.iter().map(|c| mask(&c.areas)).collect(),
            ore_areas: terrain.ores.iter().map(|o| mask(&o.areas)).collect(),
            floor_seed: role("floor".into()),
            decoration_seeds: terrain
                .decorations
                .iter()
                .map(|d| role(format!("decoration:{}", d.name)))
                .collect(),
            decoration_noises: terrain
                .decorations
                .iter()
                .map(|d| {
                    d.noise
                        .map(|n| n.build(role(format!("decoration:{}:noise", d.name))))
                })
                .collect(),
            decoration_areas: terrain.decorations.iter().map(|d| mask(&d.areas)).collect(),
            decoration_on,
            decoration_reach,
            script,
            density,
            terrain,
            seed,
            min_y,
            max_y,
        }
    }

    fn stages(&self) -> Stages {
        self.script.as_ref().map(|s| s.stages()).unwrap_or_default()
    }

    /// Whether a place's biome can differ from its column's: the file has volume areas, or its script a `biome` stage.
    pub fn point_areas(&self) -> bool {
        !self.volume_areas.is_empty() || self.stages().contains(Stage::Biome)
    }

    /// A sampler, with the state of the chunk it serves (whose heights and stages run in it), if any.
    pub fn sampler<'a>(&'a self, script: Option<&'a RefCell<Box<dyn ScriptState>>>) -> Sampler<'a> {
        Sampler {
            generator: self,
            script,
            scripted_density: self.density.is_some() && self.stages().contains(Stage::Density),
            lattice: Default::default(),
            grid: Default::default(),
            corners: Default::default(),
            column_area: Default::default(),
            surfaces: Default::default(),
            file_heights: Default::default(),
        }
    }

    /// Lets go of the script's states.
    pub fn close(&self) {
        if let Some(script) = &self.script {
            script.close();
        }
    }

    /// Makes one of the script's states (its body run) and keeps it for the next call.
    pub fn load_script(&self) {
        if let Some(script) = &self.script {
            let state = script.take(self);
            script.give(state);
        }
    }

    /// Runs `f` with a state of the script's, given back after (none without a script).
    fn with_state<T>(
        &self,
        given: Option<&RefCell<Box<dyn ScriptState>>>,
        f: impl FnOnce(&mut dyn ScriptState) -> T,
    ) -> Option<T> {
        if let Some(state) = given {
            return Some(f(state.borrow_mut().as_mut()));
        }
        let script = self.script.as_ref()?;
        let mut state = script.take(self);
        let out = f(state.as_mut());
        script.give(state);
        Some(out)
    }

    /// Runs `f` with a sampler of its own script state, given back after.
    pub fn sampling<T>(&self, f: impl FnOnce(&Sampler) -> T) -> T {
        let Some(script) = &self.script else {
            return f(&self.sampler(None));
        };
        let state = RefCell::new(script.take(self));
        let out = f(&self.sampler(Some(&state)));
        script.give(state.into_inner());
        out
    }

    fn area_height(&self, area: usize, x: f64, z: f64, file_sum: f64) -> f64 {
        let terrain = &self.terrain.areas[area].terrain;
        let mut height = f64::from(terrain.base) + terrain.scale * file_sum;
        for (i, noise) in self.area_noises[area].iter().enumerate() {
            height += noise.get_noise_2d(x, z) * terrain.noises[i].amplitude;
        }
        height
    }

    /// The y of the top block of the terrain at a column.
    pub fn surface_at(&self, x: i32, z: i32) -> i32 {
        self.sampler(None).surface_at(x, z)
    }

    /// The biome area at a column: the climate's, where the jitter moves it, then the script's `area` stage.
    pub fn area_at(&self, x: i32, z: i32) -> usize {
        self.area_with(x, z, None)
    }

    fn area_with(&self, x: i32, z: i32, script: Option<&RefCell<Box<dyn ScriptState>>>) -> usize {
        let file = self.file_area_at(x, z);
        if !self.stages().contains(Stage::Area) {
            return file;
        }
        self.with_state(script, |s| s.area(self, x, z, file))
            .flatten()
            .unwrap_or(file)
    }

    fn climate_point(&self, x: i32, z: i32) -> (f64, f64) {
        let (mut cx, mut cz) = (f64::from(x), f64::from(z));
        if self.jittered {
            let dx = self.jitter_x.get_noise_2d(cx, cz) * self.terrain.jitter_amplitude;
            let dz = self.jitter_z.get_noise_2d(cx, cz) * self.terrain.jitter_amplitude;
            cx += dx;
            cz += dz;
        }
        (cx, cz)
    }

    /// The climate at a column: its temperature, its humidity, then each of the file's own values.
    pub fn climate_at(&self, x: i32, z: i32) -> Vec<f64> {
        let (cx, cz) = self.climate_point(x, z);
        let mut out = vec![
            self.temperature_noise.get_noise_2d(cx, cz),
            self.humidity_noise.get_noise_2d(cx, cz),
        ];
        out.extend(self.climate_noises.iter().map(|n| n.get_noise_2d(cx, cz)));
        out
    }

    /// How far a climate is outside an area's box, squared: 0 inside it.
    fn climate_cost(
        &self,
        area: &CompiledArea,
        temperature: f64,
        humidity: f64,
        climate: Option<&[f64]>,
    ) -> f64 {
        let dt = outside(temperature, area.temperature_min, area.temperature_max);
        let dh = outside(humidity, area.humidity_min, area.humidity_max);
        let mut cost = dt * dt + dh * dh;
        if let Some(climate) = climate {
            for i in 0..self.climate_noises.len() {
                let d = outside(climate[2 + i], area.climate[2 * i], area.climate[2 * i + 1]);
                cost += d * d;
            }
        }
        cost
    }

    /// The column's area by its climate alone, before the script's `area` stage.
    pub fn file_area_at(&self, x: i32, z: i32) -> usize {
        if self.column_areas.len() == 1 {
            return self.column_areas[0];
        }
        let (temperature, humidity, climate) = if self.climate_noises.is_empty() {
            let (cx, cz) = self.climate_point(x, z);
            (
                self.temperature_noise.get_noise_2d(cx, cz),
                self.humidity_noise.get_noise_2d(cx, cz),
                None,
            )
        } else {
            let climate = self.climate_at(x, z);
            (climate[0], climate[1], Some(climate))
        };
        let mut best = self.column_areas[0];
        let (mut best_cost, mut best_span) = (f64::MAX, f64::MAX);
        for &i in &self.column_areas {
            let area = &self.terrain.areas[i];
            let cost = self.climate_cost(area, temperature, humidity, climate.as_deref());
            let span = area.span();
            if cost < best_cost || (cost == best_cost && span < best_span) {
                best = i;
                best_cost = cost;
                best_span = span;
            }
        }
        best
    }

    /// The biome area at a place: the most specific volume area whose ranges and
    /// climate box hold it exactly, else its column's, then the script's `biome`
    /// stage. A place is read at the corner of its 4x4x4 cell.
    pub fn point_area_at(&self, x: i32, y: i32, z: i32, sampler: &Sampler) -> usize {
        let (qx, qy, qz) = (x & -4, y & -4, z & -4);
        let column = sampler.area_at(qx, qz);
        if !self.point_areas() {
            return column;
        }
        let mut chosen = column;
        if !self.volume_areas.is_empty() {
            let surface = sampler.kept_surface_at(qx, qz);
            let mut climate: Option<Vec<f64>> = None;
            let mut best_span = f64::MAX;
            for &i in &self.volume_areas {
                let area = &self.terrain.areas[i];
                if !area
                    .volume
                    .as_ref()
                    .expect("a volume area")
                    .contains(qy, surface)
                {
                    continue;
                }
                let at = climate.get_or_insert_with(|| self.climate_at(qx, qz));
                if self.climate_cost(area, at[0], at[1], Some(at)) > 0.0 {
                    continue;
                }
                if area.span() < best_span {
                    chosen = i;
                    best_span = area.span();
                }
            }
        }
        if self.stages().contains(Stage::Biome) {
            chosen = self
                .with_state(sampler.script, |s| s.biome(self, qx, qy, qz, chosen))
                .flatten()
                .unwrap_or(chosen);
        }
        chosen
    }

    /// The biome at a place, as the file writes it.
    pub fn biome_at(&self, x: i32, y: i32, z: i32, sampler: &Sampler) -> &str {
        &self.terrain.areas[self.point_area_at(x, y, z, sampler)].biome
    }

    /// The biome at a column.
    pub fn column_biome_at(&self, x: i32, z: i32) -> &str {
        &self.terrain.areas[self.area_at(x, z)].biome
    }

    /// Which areas have a biome `wanted` says yes to.
    pub fn areas_where(&self, wanted: &dyn Fn(&str) -> bool) -> Vec<bool> {
        self.terrain
            .areas
            .iter()
            .map(|a| wanted(&a.biome))
            .collect()
    }

    /// The heights a column is looked at: `y` first (inside the world), then `step` above and below it in turn.
    pub fn locate_heights(&self, y: i32, step: i32) -> Vec<i32> {
        let origin = y.clamp(self.min_y, self.max_y - 1);
        let mut out = vec![origin];
        let mut d = step;
        while origin + d < self.max_y || origin - d >= self.min_y {
            if origin + d < self.max_y {
                out.push(origin + d);
            }
            if origin - d >= self.min_y {
                out.push(origin - d);
            }
            d += step;
        }
        out
    }

    /// The columns of ring `r` (a square `r` blocks out, `step` apart) as offsets, in `/locate biome`'s order.
    pub fn locate_ring(r: i32, step: i32) -> Vec<(i32, i32)> {
        if r == 0 {
            return vec![(0, 0)];
        }
        let mut out = Vec::new();
        for dx in (-r..=r).step_by(step as usize) {
            let side = dx.abs() == r;
            for dz in (-r..=r).step_by(step as usize) {
                if side || dz.abs() == r {
                    out.push((dx, dz));
                }
            }
        }
        out
    }

    /// The column nearest (x, z) whose terrain is above the sea, looked for in steps of 8 blocks out to `radius`.
    pub fn dry_column_near(&self, x: i32, z: i32, radius: i32) -> Option<(i32, i32)> {
        let sampler = self.sampler(None);
        let sea = self.terrain.sea_level;
        if sampler.surface_at(x, z) >= sea {
            return Some((x, z));
        }
        let mut r = STEP;
        while r <= radius {
            for d in (-r..=r).step_by(STEP as usize) {
                for (cx, cz) in [
                    (x + d, z - r),
                    (x + d, z + r),
                    (x - r, z + d),
                    (x + r, z + d),
                ] {
                    if sampler.surface_at(cx, cz) >= sea {
                        return Some((cx, cz));
                    }
                }
            }
            r += STEP;
        }
        None
    }

    /// Generates chunk (chunk_x, chunk_z) into a new buffer.
    pub fn generate(&self, chunk_x: i32, chunk_z: i32) -> ChunkBuffer {
        let mut buffer = ChunkBuffer::new(self.min_y, self.max_y);
        self.generate_into(chunk_x, chunk_z, &mut buffer);
        buffer
    }

    /// Generates chunk (chunk_x, chunk_z) into `buffer`, which must be empty and of this world's height.
    pub fn generate_into(&self, chunk_x: i32, chunk_z: i32, buffer: &mut ChunkBuffer) {
        assert!(
            buffer.min_y == self.min_y && buffer.max_y == self.max_y,
            "the buffer isn't this world's height"
        );
        // The script's state is this chunk's while it's made: its heights and its stages run in it.
        let state = self.script.as_ref().map(|s| RefCell::new(s.take(self)));
        {
            let sampler = self.sampler(state.as_ref());
            let mut base = vec![0; 256];
            let mut areas = vec![0; 256];
            for lx in 0..16 {
                for lz in 0..16 {
                    let (x, z) = (chunk_x * 16 + lx, chunk_z * 16 + lz);
                    base[(lx * 16 + lz) as usize] = sampler.base_height(x, z);
                    areas[(lx * 16 + lz) as usize] = sampler.area_at(x, z);
                }
            }
            let mut solid = self
                .density
                .as_ref()
                .map(|_| vec![false; buffer.blocks.len()]);
            let mut surface = base.clone();
            if let Some(solid) = &mut solid {
                for lx in 0..16 {
                    for lz in 0..16 {
                        let column = (lx * 16 + lz) as usize;
                        let density = sampler.density_column(
                            chunk_x * 16 + lx,
                            chunk_z * 16 + lz,
                            base[column],
                        );
                        surface[column] =
                            self.solid_column(&density, solid, buffer.column_start(lx, lz));
                    }
                }
            }
            let cells = self
                .point_areas()
                .then(|| self.cell_areas(chunk_x, chunk_z, &sampler));
            let mut chunk = ChunkGeneration {
                chunk_x,
                chunk_z,
                buffer,
                surface,
                base,
                areas,
                solid,
                cells,
            };
            self.terrain_stage(&mut chunk);
            self.script_stage(&mut chunk, state.as_ref(), Stage::Terrain);
            self.carve(&mut chunk);
            self.floor(&mut chunk);
            self.ores(&mut chunk, &sampler);
            self.decorate(&mut chunk, &sampler);
            self.script_stage(&mut chunk, state.as_ref(), Stage::Decorate);
        }
        if let (Some(script), Some(state)) = (&self.script, state) {
            script.give(state.into_inner());
        }
    }

    /// The area of each 4x4x4 cell of a chunk.
    fn cell_areas(&self, chunk_x: i32, chunk_z: i32, sampler: &Sampler) -> Vec<usize> {
        let height = (self.max_y - self.min_y + 3) / 4;
        let mut out = vec![0; (16 * height) as usize];
        for qx in 0..4 {
            for qz in 0..4 {
                for qy in 0..height {
                    out[((qx * 4 + qz) * height + qy) as usize] = self.point_area_at(
                        chunk_x * 16 + qx * 4,
                        self.min_y + qy * 4,
                        chunk_z * 16 + qz * 4,
                        sampler,
                    );
                }
            }
        }
        out
    }

    /// Marks a column's solid blocks from `start` (its place in the buffer); answers its topmost one's y.
    fn solid_column(&self, column: &DensityColumn, solid: &mut [bool], start: usize) -> i32 {
        let mut top = self.min_y - 1;
        let mut y = column.highest();
        while y >= self.min_y {
            let at = start + (y - self.min_y) as usize;
            if column.solid_below(y) {
                // Everything from here down is solid, whatever the noises say.
                solid[start..=at].fill(true);
                return if top < self.min_y { y } else { top };
            }
            if column.solid(y) {
                solid[at] = true;
                if top < self.min_y {
                    top = y;
                }
            }
            y -= 1;
        }
        top
    }

    /// The script's chunk stage, when it has one; one that fails leaves the chunk as it found it.
    fn script_stage(
        &self,
        chunk: &mut ChunkGeneration,
        state: Option<&RefCell<Box<dyn ScriptState>>>,
        stage: Stage,
    ) {
        let Some(state) = state else { return };
        if !self.stages().contains(stage) {
            return;
        }
        let before = chunk.buffer.blocks.clone();
        let loot = chunk.buffer.loot.clone();
        if !state.borrow_mut().chunk_stage(self, stage, chunk) {
            chunk.buffer.blocks = before;
            chunk.buffer.loot = loot;
        }
    }

    /// The layers of a column of `area`, under the sea or not.
    fn layers_of<'a>(&'a self, area: &'a CompiledArea, sea: bool) -> &'a [CompiledLayer] {
        let own = if sea {
            area.underwater.as_ref().or(area.layers.as_ref())
        } else {
            area.layers.as_ref()
        };
        match own {
            Some(layers) => layers,
            None if sea => &self.terrain.underwater,
            None => &self.terrain.layers,
        }
    }

    /// The block the terrain puts at the top of a column of `area` (before caves and ores).
    fn top_block(&self, area: usize, sea: bool) -> i32 {
        self.layers_of(&self.terrain.areas[area], sea)
            .first()
            .map_or(self.terrain.stone, |l| l.block)
    }

    /// Stone, the layers on it and the sea over it.
    fn terrain_stage(&self, chunk: &mut ChunkGeneration) {
        if chunk.solid.is_some() {
            return self.density_terrain(chunk);
        }
        let w = &self.terrain;
        for lx in 0..16 {
            for lz in 0..16 {
                let column = (lx * 16 + lz) as usize;
                let top = chunk.surface[column];
                let sea = top < w.sea_level;
                let mut y = top;
                for layer in self.layers_of(&w.areas[chunk.areas[column]], sea) {
                    if y < self.min_y {
                        break;
                    }
                    chunk
                        .buffer
                        .fill(lx, lz, y - layer.thickness + 1, y, layer.block);
                    y -= layer.thickness;
                }
                if y >= self.min_y {
                    chunk.buffer.fill(lx, lz, self.min_y, y, w.stone);
                }
                if sea {
                    chunk.buffer.fill(lx, lz, top + 1, w.sea_level, w.fluid);
                }
            }
        }
    }

    /// With a density: every solid block, topped by the layers from each surface down, and the sea in every
    /// space at or below its level.
    fn density_terrain(&self, chunk: &mut ChunkGeneration) {
        let w = &self.terrain;
        let solid = chunk.solid.as_ref().expect("a density's chunk");
        let height = chunk.buffer.height;
        for lx in 0..16 {
            for lz in 0..16 {
                let column = (lx * 16 + lz) as usize;
                let start = chunk.buffer.column_start(lx, lz);
                let area = &w.areas[chunk.areas[column]];
                let mut layers: &[CompiledLayer] = &[];
                let (mut layer, mut left) = (0, 0);
                for i in (0..height).rev() {
                    let at = start + i as usize;
                    let y = self.min_y + i;
                    if !solid[at] {
                        if y <= w.sea_level {
                            chunk.buffer.blocks[at] = w.fluid;
                        }
                        continue;
                    }
                    if i == height - 1 || !solid[at + 1] {
                        layers = self.layers_of(area, y < w.sea_level);
                        layer = 0;
                        left = layers.first().map_or(0, |l| l.thickness);
                    }
                    if layer < layers.len() {
                        chunk.buffer.blocks[at] = layers[layer].block;
                        left -= 1;
                        if left == 0 {
                            layer += 1;
                            if layer < layers.len() {
                                left = layers[layer].thickness;
                            }
                        }
                    } else {
                        chunk.buffer.blocks[at] = w.stone;
                    }
                }
            }
        }
    }

    /// Caves, never nearer the surface than they say, in the areas they name.
    fn carve(&self, chunk: &mut ChunkGeneration) {
        if self.terrain.caves.is_empty() {
            return;
        }
        if chunk.solid.is_some() {
            return self.density_carve(chunk);
        }
        for (i, cave) in self.terrain.caves.iter().enumerate() {
            let (noise, second) = &self.cave_noises[i];
            let allowed = self.cave_areas[i].as_deref();
            for lx in 0..16 {
                for lz in 0..16 {
                    // The whole column when its area is allowed; with volumes, the places whose own area is.
                    let column = allowed.is_none_or(|a| a[chunk.areas[(lx * 16 + lz) as usize]]);
                    if !column && chunk.cells.is_none() {
                        continue;
                    }
                    let x = f64::from(chunk.min_x() + lx);
                    let z = f64::from(chunk.min_z() + lz);
                    let to = cave
                        .max_y
                        .min(chunk.surface[(lx * 16 + lz) as usize] - cave.depth)
                        .min(self.max_y - 2);
                    for y in cave.min_y.max(self.min_y + 1)..=to {
                        if !column && !allowed.expect("an area mask")[chunk.area_at(lx, y, lz)] {
                            continue;
                        }
                        if opens(cave, noise, second.as_ref(), x, y, z) {
                            chunk.buffer.set(lx, y, lz, 0);
                        }
                    }
                }
            }
        }
    }

    /// Caves with a density: carved in what the terrain made solid, wherever at least each cave's `depth` of
    /// solid blocks is on it.
    fn density_carve(&self, chunk: &mut ChunkGeneration) {
        for (i, cave) in self.terrain.caves.iter().enumerate() {
            let (noise, second) = &self.cave_noises[i];
            let allowed = self.cave_areas[i].as_deref();
            let from = cave.min_y.max(self.min_y + 1);
            let to = cave.max_y.min(self.max_y - 2);
            if from > to {
                continue;
            }
            for lx in 0..16 {
                for lz in 0..16 {
                    let column = allowed.is_none_or(|a| a[chunk.areas[(lx * 16 + lz) as usize]]);
                    if !column && chunk.cells.is_none() {
                        continue;
                    }
                    let x = f64::from(chunk.min_x() + lx);
                    let z = f64::from(chunk.min_z() + lz);
                    let start = chunk.buffer.column_start(lx, lz) as i32 - self.min_y;
                    // How many solid blocks are on the one at y, counted down from the top of the world.
                    let mut above = 0;
                    for y in (from..self.max_y).rev() {
                        let at = (start + y) as usize;
                        let here = chunk.solid.as_ref().expect("a density's chunk")[at];
                        if here
                            && y <= to
                            && above >= cave.depth
                            && (column || allowed.expect("an area mask")[chunk.area_at(lx, y, lz)])
                            && opens(cave, noise, second.as_ref(), x, y, z)
                        {
                            chunk.buffer.blocks[at] = 0;
                        }
                        above = if here { above + 1 } else { 0 };
                    }
                }
            }
        }
    }

    /// The bottom of the world: solid at its lowest layer, thinning out above.
    fn floor(&self, chunk: &mut ChunkGeneration) {
        let thickness = self.terrain.floor_thickness;
        if thickness <= 0 {
            return;
        }
        for lx in 0..16 {
            for lz in 0..16 {
                for offset in 0..thickness.min(chunk.buffer.height) {
                    let y = self.min_y + offset;
                    let chance = 1.0 - f64::from(offset) / f64::from(thickness);
                    if offset == 0
                        || seeds::unit(self.floor_seed, chunk.min_x() + lx, y, chunk.min_z() + lz)
                            < chance
                    {
                        chunk.buffer.set(lx, y, lz, self.terrain.floor_block);
                    }
                }
            }
        }
    }

    /// The random numbers of one role in one chunk: the same whichever chunk asks.
    fn chunk_random(role_seed: i32, cx: i32, cz: i32) -> Random {
        Random::of_int(seeds::mix(
            seeds::mix(role_seed ^ cx.wrapping_mul(0x1B87_3593)) ^ cz.wrapping_mul(0x2C1B_3C6D),
        ))
    }

    /// Veins: each chunk works out the veins that start in the chunks around it, from their own positions'
    /// randomness, and keeps what lands in itself.
    fn ores(&self, chunk: &mut ChunkGeneration, sampler: &Sampler) {
        let point_areas = self.point_areas();
        for (index, ore) in self.terrain.ores.iter().enumerate() {
            if ore.veins == 0 {
                continue;
            }
            let role_seed = seeds::for_role(self.seed, &format!("ore:{}", ore.name));
            let allowed = self.ore_areas[index].as_deref();
            let low = ore.min_y.max(self.min_y);
            let high = ore.max_y.min(self.max_y - 1);
            if low > high {
                continue;
            }
            for dx in -1..=1 {
                for dz in -1..=1 {
                    let (cx, cz) = (chunk.chunk_x + dx, chunk.chunk_z + dz);
                    let mut random = Self::chunk_random(role_seed, cx, cz);
                    for _ in 0..ore.veins {
                        let ox = cx * 16 + random.next_int_below(16);
                        let oz = cz * 16 + random.next_int_below(16);
                        let oy = match ore.distribution {
                            OreDistribution::Uniform => low + random.next_int_below(high - low + 1),
                            OreDistribution::Triangle => {
                                let a = random.next_int_below(high - low + 1);
                                let b = random.next_int_below(high - low + 1);
                                low + (a + b + 1) / 2
                            }
                        };
                        // A vein that doesn't start in its areas isn't grown (nor are its numbers drawn).
                        let starts = allowed.is_none_or(|a| {
                            a[sampler.area_at(ox, oz)]
                                || (point_areas && a[self.point_area_at(ox, oy, oz, sampler)])
                        });
                        if starts {
                            self.vein(chunk, ore, ox, oy, oz, &mut random);
                        }
                    }
                }
            }
        }
    }

    /// One vein grown from a block by adding a neighbour of a random block of it, `size` blocks in all.
    fn vein(
        &self,
        chunk: &mut ChunkGeneration,
        ore: &CompiledOre,
        ox: i32,
        oy: i32,
        oz: i32,
        random: &mut Random,
    ) {
        let size = ore.size as usize;
        let mut cells = vec![0; size];
        let mut count = 1;
        cells[0] = pack(0, 0, 0);
        let mut attempts = 0;
        while count < size && attempts < size * 24 {
            attempts += 1;
            let from = cells[random.next_int_below(count as i32) as usize];
            let (mut dx, mut dy, mut dz) = (unpack_x(from), unpack_y(from), unpack_z(from));
            match random.next_int_below(6) {
                0 => dx += 1,
                1 => dx -= 1,
                2 => dy += 1,
                3 => dy -= 1,
                4 => dz += 1,
                _ => dz -= 1,
            }
            if dx.abs() > REACH || dy.abs() > REACH || dz.abs() > REACH {
                continue;
            }
            let packed = pack(dx, dy, dz);
            if !cells[..count].contains(&packed) {
                cells[count] = packed;
                count += 1;
            }
        }
        for &cell in &cells[..count] {
            let (x, y, z) = (
                ox + unpack_x(cell),
                oy + unpack_y(cell),
                oz + unpack_z(cell),
            );
            let (lx, lz) = (x - chunk.min_x(), z - chunk.min_z());
            if !(0..=15).contains(&lx)
                || !(0..=15).contains(&lz)
                || y < self.min_y
                || y >= self.max_y
            {
                continue;
            }
            if ore.replace.contains(&chunk.buffer.get(lx, y, lz)) {
                chunk.buffer.set(lx, y, lz, ore.block);
            }
        }
    }

    /// Where decoration `index` is tried in chunk (cx, cz) for its next try, from `random`, or none when the
    /// column says no. Every try draws the same five numbers, and nothing but the column's own is asked.
    pub fn decoration_site(
        &self,
        index: usize,
        cx: i32,
        cz: i32,
        random: &mut Random,
        sampler: &Sampler,
    ) -> Option<DecorationSite> {
        let d = &self.terrain.decorations[index];
        let x = cx * 16 + random.next_int_below(16);
        let z = cz * 16 + random.next_int_below(16);
        let roll = random.next_double();
        let pick = random.next_double();
        let turn = random.next_int_below(4);
        let mut chance = d.chance;
        if let Some(noise) = &self.decoration_noises[index] {
            let n = noise.get_noise_2d(f64::from(x), f64::from(z));
            chance *= ((n - d.threshold) / (1.0 - d.threshold)).clamp(0.0, 1.0);
        }
        if roll >= chance {
            return None;
        }
        let area = sampler.area_at(x, z);
        let allowed = self.decoration_areas[index].as_deref();
        // With volumes, a place whose column isn't allowed may still be: its own area is asked once it has a height.
        let column = allowed.is_none_or(|a| a[area]);
        if !column && !self.point_areas() {
            return None;
        }
        let low = d.min_y.unwrap_or(self.min_y).max(self.min_y);
        let high = d.max_y.unwrap_or(self.max_y - 1).min(self.max_y - 1);
        let y = match d.placement {
            DecorationPlacement::Surface | DecorationPlacement::Underwater => {
                let sea = d.placement == DecorationPlacement::Underwater;
                // Any of the column's dry tops (or sea floors), the try's number says which.
                let tops: Vec<i32> = sampler
                    .surfaces_at(x, z)
                    .into_iter()
                    .filter(|t| (*t < self.terrain.sea_level) == sea)
                    .collect();
                if tops.is_empty() || !self.decoration_on[index][self.top_block(area, sea) as usize]
                {
                    return None;
                }
                tops[(tops.len() - 1).min((pick * tops.len() as f64).floor() as usize)] + 1
            }
            DecorationPlacement::Underground => {
                pick_y(low, high.min(sampler.surface_at(x, z)), pick)?
            }
            DecorationPlacement::CaveFloor | DecorationPlacement::CaveCeiling => {
                pick_y(low, high.min(sampler.surface_at(x, z) - 1), pick)?
            }
        };
        if !(low..=high).contains(&y) {
            return None;
        }
        if !column && !allowed.expect("an area mask")[self.point_area_at(x, y, z, sampler)] {
            return None;
        }
        Some(DecorationSite {
            x,
            y,
            z,
            turn: if d.rotate { turn } else { 0 },
        })
    }

    /// The decoration sites starting in chunk (cx, cz) for decoration `index` that the columns decide alone.
    pub fn decoration_sites(
        &self,
        index: usize,
        cx: i32,
        cz: i32,
        sampler: &Sampler,
    ) -> Vec<DecorationSite> {
        let mut random = Self::chunk_random(self.decoration_seeds[index], cx, cz);
        (0..self.terrain.decorations[index].count)
            .filter_map(|_| self.decoration_site(index, cx, cz, &mut random, sampler))
            .collect()
    }

    /// Decorations, in name order: each chunk places those starting in it and the parts of structures starting near it.
    fn decorate(&self, chunk: &mut ChunkGeneration, sampler: &Sampler) {
        for (index, d) in self.terrain.decorations.iter().enumerate() {
            if d.count == 0 {
                continue;
            }
            let template = d
                .structure
                .as_ref()
                .and_then(|s| self.terrain.templates.get(s));
            if d.structure.is_some() && template.is_none() {
                continue;
            }
            let reach = self.decoration_reach[index];
            for cx in chunk.chunk_x - reach..=chunk.chunk_x + reach {
                for cz in chunk.chunk_z - reach..=chunk.chunk_z + reach {
                    let mut random = Self::chunk_random(self.decoration_seeds[index], cx, cz);
                    for _ in 0..d.count {
                        if let Some(site) =
                            self.decoration_site(index, cx, cz, &mut random, sampler)
                        {
                            match template {
                                Some(template) => self.structure(chunk, template, site, d.loot),
                                None => self.block(chunk, index, d, site),
                            }
                        }
                    }
                }
            }
        }
    }

    /// A block decoration at its site, if the chunk's blocks there let it.
    fn block(
        &self,
        chunk: &mut ChunkGeneration,
        index: usize,
        d: &CompiledDecoration,
        site: DecorationSite,
    ) {
        let (lx, lz) = (site.x - chunk.min_x(), site.z - chunk.min_z());
        let on = &self.decoration_on[index];
        let buffer = &*chunk.buffer;
        let at = |y: i32| buffer.get(lx, y, lz);
        let solid = |y: i32| {
            (self.min_y..self.max_y).contains(&y) && {
                let b = at(y);
                b != 0 && b != self.terrain.fluid
            }
        };
        let y = match d.placement {
            DecorationPlacement::Surface => {
                Some(site.y).filter(|&y| y < self.max_y && at(y) == 0 && on[at(y - 1) as usize])
            }
            DecorationPlacement::Underwater => Some(site.y).filter(|&y| {
                y < self.max_y && at(y) == self.terrain.fluid && on[at(y - 1) as usize]
            }),
            DecorationPlacement::Underground => Some(site.y).filter(|&y| on[at(y) as usize]),
            DecorationPlacement::CaveFloor => {
                // The first floor at or below the height tried: air with ground under it.
                let mut y = site.y;
                let low = d.min_y.unwrap_or(self.min_y).max(self.min_y + 1);
                while y >= low && !(at(y) == 0 && solid(y - 1)) {
                    y -= 1;
                }
                Some(y).filter(|&y| y >= low && on[at(y - 1) as usize])
            }
            DecorationPlacement::CaveCeiling => {
                let mut y = site.y;
                let high = d
                    .max_y
                    .unwrap_or(self.max_y - 2)
                    .min(chunk.surface[(lx * 16 + lz) as usize] - 1)
                    .min(self.max_y - 2);
                while y <= high && !(at(y) == 0 && solid(y + 1)) {
                    y += 1;
                }
                Some(y).filter(|&y| y <= high && on[at(y + 1) as usize])
            }
        };
        let Some(y) = y else { return };
        chunk.buffer.set(lx, y, lz, d.block);
        let at = chunk.buffer.column_start(lx, lz) + (y - self.min_y) as usize;
        if d.loot >= 0 {
            chunk.buffer.loot.insert(at, d.loot);
        } else {
            chunk.buffer.loot.remove(&at);
        }
    }

    /// The part of a structure decoration at its site that's in this chunk: its lowest layer at the site, centred on it.
    fn structure(
        &self,
        chunk: &mut ChunkGeneration,
        template: &LinkedTemplate,
        site: DecorationSite,
        loot: i32,
    ) {
        let turn = site.turn;
        let (width, depth) = match turn % 2 {
            0 => (template.size_x, template.size_z),
            _ => (template.size_z, template.size_x),
        };
        let (x0, z0) = (site.x - width / 2, site.z - depth / 2);
        let (min_x, min_z) = (chunk.min_x(), chunk.min_z());
        if x0 + width <= min_x || x0 >= min_x + 16 || z0 + depth <= min_z || z0 >= min_z + 16 {
            return;
        }
        let states = &template.turns[turn as usize];
        for block in template.blocks.chunks(4) {
            let (bx, by, bz, entry) = (block[0], block[1], block[2], block[3] as usize);
            let state = states[entry];
            if state == 0 {
                continue;
            }
            let rx = match turn {
                0 => bx,
                1 => template.size_z - 1 - bz,
                2 => template.size_x - 1 - bx,
                _ => bz,
            };
            let rz = match turn {
                0 => bz,
                1 => bx,
                2 => template.size_z - 1 - bz,
                _ => template.size_x - 1 - bx,
            };
            let (lx, lz, y) = (x0 + rx - min_x, z0 + rz - min_z, site.y + by);
            if !(0..=15).contains(&lx)
                || !(0..=15).contains(&lz)
                || y < self.min_y
                || y >= self.max_y
            {
                continue;
            }
            chunk.buffer.set(lx, y, lz, state);
            let at = chunk.buffer.column_start(lx, lz) + (y - self.min_y) as usize;
            if loot >= 0 && template.with_entity.get(entry).copied().unwrap_or(false) {
                chunk.buffer.loot.insert(at, loot);
            } else {
                chunk.buffer.loot.remove(&at);
            }
        }
    }
}

/// Whether `cave` carves the block at (x, y, z), with its threshold there.
fn opens(
    cave: &CompiledCave,
    noise: &FastNoiseLite,
    second: Option<&FastNoiseLite>,
    x: f64,
    y: i32,
    z: f64,
) -> bool {
    let threshold = cave.threshold_at(y);
    let fy = f64::from(y);
    match cave.kind {
        CaveType::Cheese => noise.get_noise_3d(x, fy, z) > threshold,
        CaveType::Spaghetti => {
            noise.get_noise_3d(x, fy, z).abs() < threshold
                && second
                    .expect("a spaghetti cave's second noise")
                    .get_noise_3d(x, fy, z)
                    .abs()
                    < threshold
        }
    }
}

fn outside(value: f64, min: f64, max: f64) -> f64 {
    if value < min {
        min - value
    } else if value > max {
        value - max
    } else {
        0.0
    }
}

fn pick_y(low: i32, high: i32, pick: f64) -> Option<i32> {
    (low <= high).then(|| low + (pick * f64::from(high - low + 1)).floor() as i32)
}

fn pack(x: i32, y: i32, z: i32) -> i32 {
    ((x + 64) << 16) | ((y + 64) << 8) | (z + 64)
}

fn unpack_x(packed: i32) -> i32 {
    ((packed as u32) >> 16) as i32 - 64
}

fn unpack_y(packed: i32) -> i32 {
    (((packed as u32) >> 8) & 0xFF) as i32 - 64
}

fn unpack_z(packed: i32) -> i32 {
    (packed & 0xFF) - 64
}

fn key(i: i32, j: i32) -> i64 {
    (i64::from(i) << 32) | (i64::from(j) & 0xFFFF_FFFF)
}

/// Asks the heights and areas of columns for one thread, keeping what it works
/// out: one serves a chunk, or a picture of many columns. Its answers are a
/// new one's. `script` is the state the chunk's script runs in, which its
/// heights use too; without one, a height the script gives takes a state for the call.
pub struct Sampler<'a> {
    generator: &'a TerrainGenerator,
    script: Option<&'a RefCell<Box<dyn ScriptState>>>,
    scripted_density: bool,
    lattice: RefCell<HashMap<i64, usize>>,
    grid: RefCell<HashMap<i64, Rc<Vec<f64>>>>,
    corners: RefCell<HashMap<i64, Corner>>,
    column_area: RefCell<HashMap<i64, usize>>,
    surfaces: RefCell<HashMap<i64, i32>>,
    file_heights: RefCell<HashMap<i64, i32>>,
}

/// Looks `key` up in `cache`, or works it out and keeps it (starting again once the cache is full).
fn kept<V: Clone>(cache: &RefCell<HashMap<i64, V>>, key: i64, make: impl FnOnce() -> V) -> V {
    if let Some(v) = cache.borrow().get(&key) {
        return v.clone();
    }
    let value = make();
    let mut cache = cache.borrow_mut();
    if cache.len() >= CACHE_LIMIT {
        cache.clear();
    }
    cache.insert(key, value.clone());
    value
}

impl<'a> Sampler<'a> {
    /// The area of a column: its climate's, then the script's `area` stage.
    pub fn area_at(&self, x: i32, z: i32) -> usize {
        let g = self.generator;
        if !g.stages().contains(Stage::Area) {
            return g.file_area_at(x, z);
        }
        kept(&self.column_area, key(x, z), || {
            g.area_with(x, z, self.script)
        })
    }

    /// The area at a place: see `TerrainGenerator::point_area_at`.
    pub fn point_area_at(&self, x: i32, y: i32, z: i32) -> usize {
        self.generator.point_area_at(x, y, z, self)
    }

    /// The first of `ys` at column (x, z) whose place's area is one `wanted` has.
    pub fn first_where(&self, x: i32, z: i32, ys: &[i32], wanted: &[bool]) -> Option<i32> {
        if !self.generator.point_areas() {
            return ys.first().copied().filter(|_| wanted[self.area_at(x, z)]);
        }
        ys.iter()
            .copied()
            .find(|&y| wanted[self.point_area_at(x, y, z)])
    }

    /// `surface_at`, kept: what a volume's depth and surface are measured from.
    fn kept_surface_at(&self, x: i32, z: i32) -> i32 {
        kept(&self.surfaces, key(x, z), || self.surface_at(x, z))
    }

    /// The y of the top block of the terrain at a column: its base height, or with a density the topmost solid
    /// block (one below the world's bottom when there's none).
    pub fn surface_at(&self, x: i32, z: i32) -> i32 {
        match &self.generator.density {
            None => self.base_height(x, z),
            Some(_) => self.density_column(x, z, self.base_height(x, z)).top(),
        }
    }

    /// The y of every top surface of a column, from the highest down; without a density only `surface_at`.
    pub fn surfaces_at(&self, x: i32, z: i32) -> Vec<i32> {
        match &self.generator.density {
            None => vec![self.base_height(x, z)],
            Some(_) => self.density_column(x, z, self.base_height(x, z)).surfaces(),
        }
    }

    /// The height the 2D terrain and the script's height stage make at a column, kept inside the world.
    pub fn base_height(&self, x: i32, z: i32) -> i32 {
        let g = self.generator;
        let file = self.file_height(x, z);
        let height = match g.stages().contains(Stage::Height) {
            true => g
                .with_state(self.script, |s| s.height(g, x, z, file))
                .flatten()
                .unwrap_or(file),
            false => file,
        };
        height.clamp(g.min_y, g.max_y - 2)
    }

    pub fn density_column(&self, x: i32, z: i32, height: i32) -> DensityColumn<'_> {
        let field = self
            .generator
            .density
            .as_ref()
            .expect("a terrain with a density");
        DensityColumn::new(
            field,
            self,
            self.scripted_density,
            height,
            x,
            z,
            &|gx, gz| self.corner(gx, gz),
        )
    }

    fn corner(&self, gx: i32, gz: i32) -> Corner {
        kept(&self.corners, key(gx, gz), || {
            let g = self.generator;
            let field = g.density.as_ref().expect("a terrain with a density");
            let (x, z) = (gx * CELL_XZ, gz * CELL_XZ);
            let weights = field.weighed.then(|| self.weights_at(x, z));
            let area = if weights.is_none() && g.terrain.areas.len() > 1 {
                self.area_at(x, z)
            } else {
                0
            };
            let islands_weight = field.islands_weight(weights.as_deref(), area);
            Rc::new(RefCell::new(CornerColumn::new(
                field,
                x,
                z,
                weights,
                islands_weight,
                self.scripted_density,
            )))
        })
    }

    /// How much each area counts at a column: blended across borders, or all of it the column's own area's.
    fn weights_at(&self, x: i32, z: i32) -> Vec<f64> {
        let g = self.generator;
        if g.terrain.blend > 0 {
            return self.blend_weights(x, z);
        }
        let mut out = vec![0.0; g.terrain.areas.len()];
        out[self.area_at(x, z)] = 1.0;
        out
    }

    /// The y of the top block the file's terrain puts at a column, before the script's height stage.
    pub fn file_height(&self, x: i32, z: i32) -> i32 {
        kept(&self.file_heights, key(x, z), || self.new_file_height(x, z))
    }

    fn new_file_height(&self, x: i32, z: i32) -> i32 {
        let g = self.generator;
        let (dx, dz) = (f64::from(x), f64::from(z));
        let mut file_sum = 0.0;
        for (i, noise) in g.terrain.noises.iter().enumerate() {
            file_sum += g.height_noises[i].get_noise_2d(dx, dz) * noise.amplitude;
        }
        let height = if g.one_terrain {
            g.area_height(0, dx, dz, file_sum)
        } else if !g.blended {
            g.area_height(self.area_at(x, z), dx, dz, file_sum)
        } else {
            let weights = self.blend_weights(x, z);
            let mut height = 0.0;
            for (a, w) in weights.iter().enumerate() {
                if *w > 0.0 {
                    height += w * g.area_height(a, dx, dz, file_sum);
                }
            }
            height
        };
        height.floor() as i32
    }

    /// How much each area counts at a column: the weights of the grid points around it, interpolated bilinearly.
    fn blend_weights(&self, x: i32, z: i32) -> Vec<f64> {
        let gx = x.div_euclid(BLEND_CELL);
        let gz = z.div_euclid(BLEND_CELL);
        let fx = f64::from(x - gx * BLEND_CELL) / f64::from(BLEND_CELL);
        let fz = f64::from(z - gz * BLEND_CELL) / f64::from(BLEND_CELL);
        let (w00, w10, w01, w11) = (
            self.weights(gx, gz),
            self.weights(gx + 1, gz),
            self.weights(gx, gz + 1),
            self.weights(gx + 1, gz + 1),
        );
        (0..self.generator.terrain.areas.len())
            .map(|a| {
                (1.0 - fx) * (1.0 - fz) * w00[a]
                    + fx * (1.0 - fz) * w10[a]
                    + (1.0 - fx) * fz * w01[a]
                    + fx * fz * w11[a]
            })
            .collect()
    }

    /// How much each area counts at grid point (gx, gz): the share of the lattice points around it in each.
    fn weights(&self, gx: i32, gz: i32) -> Rc<Vec<f64>> {
        kept(&self.grid, key(gx, gz), || {
            let mut out = vec![0.0; self.generator.terrain.areas.len()];
            for &(i, j, w) in &self.generator.kernel {
                out[self.lattice_area(gx + i, gz + j)] += w;
            }
            Rc::new(out)
        })
    }

    fn lattice_area(&self, i: i32, j: i32) -> usize {
        kept(&self.lattice, key(i, j), || {
            self.area_at(i * BLEND_CELL, j * BLEND_CELL)
        })
    }
}

impl CornerSource for Sampler<'_> {
    fn base_height(&self, x: i32, z: i32) -> i32 {
        Sampler::base_height(self, x, z)
    }

    fn script_density(&self, x: i32, y: i32, z: i32, value: f64) -> Option<f64> {
        let g = self.generator;
        g.with_state(self.script, |s| s.density(g, x, y, z, value))
            .flatten()
    }
}
