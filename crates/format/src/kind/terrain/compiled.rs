//! A terrain ready to run (format's `TerrainCompiler` and `CompiledTerrain`):
//! every block an index into its palette (0 is air), everything in name order,
//! so what a generator does depends on the file's content alone. What the file
//! leaves out is its fields' schema defaults (`resolve::filled`).
//!
//! The project's structures its decorations place are linked in afterwards
//! (`with_structures`), by whoever can read them; until then such a decoration
//! places nothing.

use std::collections::BTreeMap;

use serde::Serialize;

use super::{
    AIR, BlockChoice, CaveType, DEFAULT_BIOME, DEFAULT_FLUID, DEFAULT_STONE, DecorationPlacement,
    DensityNoise, Layer, Named, OreDistribution, TerrainFile, TerrainNoise,
};
use crate::block_state::BlockState;
use crate::game;
use crate::noise::NoiseDef;
use crate::resolve::filled;

/// A field whose schema gives it a default, once filled.
fn given<T>(value: Option<T>) -> T {
    value.expect("a field with a schema default is filled")
}

/// A block a generated chunk holds: a game block state in canonical text
/// (`minecraft:oak_log[axis=y]`), or one of the project's blocks by the name
/// the project knows it by (which the server holds as a note block state).
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(untagged)]
pub enum TerrainBlock {
    Vanilla { state: String },
    Custom { name: String },
}

impl TerrainBlock {
    /// A name for people: the block's state, or the custom block's name.
    pub fn label(&self) -> &str {
        match self {
            TerrainBlock::Vanilla { state } => state,
            TerrainBlock::Custom { name } => name,
        }
    }
}

/// A file's script as a generator runs it: the project (or package) path of
/// its `.lua`, the instructions each call may use, and its noises by name.
#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct CompiledScript {
    pub file: String,
    pub budget: i32,
    pub noises: Vec<CompiledScriptNoise>,
}

#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct CompiledScriptNoise {
    pub name: String,
    pub noise: NoiseDef,
}

/// `thickness` blocks of palette entry `block`.
#[derive(Clone, Copy, Debug, PartialEq, Serialize)]
pub struct CompiledLayer {
    pub block: i32,
    pub thickness: i32,
}

#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct CompiledTerrainNoise {
    pub name: String,
    pub noise: NoiseDef,
    pub amplitude: f64,
}

/// An area's terrain: `base` plus the file's noises times `scale` plus its own
/// `noises`; with a density, the file's 3D noises times `density_scale` plus
/// its own `density_noises`.
#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CompiledAreaTerrain {
    pub base: i32,
    pub scale: f64,
    pub noises: Vec<CompiledTerrainNoise>,
    pub density_scale: f64,
    pub density_noises: Vec<CompiledDensityNoise>,
}

#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct CompiledDensityNoise {
    pub name: String,
    pub noise: NoiseDef,
    pub amplitude: f64,
    pub squash: f64,
}

/// A file's 3D terrain: its own 3D noises (each area's are its terrain's) and its islands.
#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct CompiledDensity {
    pub noises: Vec<CompiledDensityNoise>,
    pub islands: Option<CompiledIslands>,
}

#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct CompiledIslands {
    pub y: i32,
    pub thickness: i32,
    pub noise: NoiseDef,
    pub threshold: f64,
    /// The areas they float over, as indexes, sorted; none for all.
    pub areas: Vec<usize>,
}

#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CompiledCave {
    pub name: String,
    #[serde(rename = "type")]
    pub kind: CaveType,
    pub noise: NoiseDef,
    pub threshold: f64,
    pub min_y: i32,
    pub max_y: i32,
    pub depth: i32,
    /// The areas it's carved in, as indexes, sorted; none for all.
    pub areas: Vec<usize>,
    /// Blocks it narrows over at each end; 0 for a flat cut.
    pub fade: i32,
}

impl CompiledCave {
    /// The threshold at `y` (inside its heights): its own deeper than `fade`
    /// from both ends, tightening linearly towards them until the block at each
    /// end carves nothing (a cheese cave's 1, a spaghetti cave's 0).
    pub fn threshold_at(&self, y: i32) -> f64 {
        if self.fade == 0 {
            return self.threshold;
        }
        let inside = (y - self.min_y).min(self.max_y - y);
        if inside >= self.fade {
            return self.threshold;
        }
        let open = f64::from(inside) / f64::from(self.fade);
        match self.kind {
            CaveType::Cheese => 1.0 - (1.0 - self.threshold) * open,
            CaveType::Spaghetti => self.threshold * open,
        }
    }
}

impl CaveType {
    /// A cave's threshold when it says none: cheese is carved above it, spaghetti within it of zero.
    pub fn default_threshold(self) -> f64 {
        match self {
            CaveType::Cheese => 0.55,
            CaveType::Spaghetti => 0.08,
        }
    }
}

#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CompiledOre {
    pub name: String,
    /// The palette entry it places.
    pub block: i32,
    /// The palette entries it may replace, sorted.
    pub replace: Vec<i32>,
    pub size: i32,
    pub veins: i32,
    pub min_y: i32,
    pub max_y: i32,
    pub distribution: OreDistribution,
    /// The areas a vein may start in, sorted; none for all.
    pub areas: Vec<usize>,
}

/// A decoration: a palette entry `block` (0 for a structure) or the `structure`
/// (as the file names it, a key of the templates once linked), tried `count`
/// times a chunk.
#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CompiledDecoration {
    pub name: String,
    pub block: i32,
    pub structure: Option<String>,
    pub placement: DecorationPlacement,
    pub count: i32,
    pub chance: f64,
    pub noise: Option<NoiseDef>,
    pub threshold: f64,
    /// The areas it's placed in, sorted; none for all.
    pub areas: Vec<usize>,
    /// None for the world's own bottom and top.
    pub min_y: Option<i32>,
    pub max_y: Option<i32>,
    /// The game's blocks (canonical ids) it sits on, hangs from or replaces; none for the default (when `on_custom` is none too).
    pub on: Vec<String>,
    pub rotate: bool,
    /// The loot table its containers are filled from (an index into the terrain's `loot`), or -1 for none.
    pub loot: i32,
    /// The project's blocks it sits on, hangs from or replaces besides `on`, by name, sorted.
    pub on_custom: Vec<String>,
}

/// A biome area: its biome as the file names it, and the climate box it's
/// found in; `layers` and `underwater` none where the file's own apply.
#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CompiledArea {
    pub name: String,
    pub biome: String,
    pub temperature_min: f64,
    pub temperature_max: f64,
    pub humidity_min: f64,
    pub humidity_max: f64,
    pub layers: Option<Vec<CompiledLayer>>,
    pub underwater: Option<Vec<CompiledLayer>>,
    pub terrain: CompiledAreaTerrain,
    /// Its range of each of the file's other climate values, in the terrain's `climate` order: min, max, min, max...
    pub climate: Vec<f64>,
    /// Where it is, when it's a volume inside the columns' areas; none for a column's own area.
    pub volume: Option<CompiledVolume>,
}

impl CompiledArea {
    /// How much climate its box covers: a smaller box is the more specific.
    pub fn span(&self) -> f64 {
        let mut span =
            (self.temperature_max - self.temperature_min) * (self.humidity_max - self.humidity_min);
        for pair in self.climate.chunks(2) {
            span *= pair[1] - pair[0];
        }
        span
    }
}

/// A volume area's ranges, both ends included: heights, blocks below the
/// column's surface, and the column's surface.
#[derive(Clone, Copy, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CompiledVolume {
    pub min_y: i32,
    pub max_y: i32,
    pub min_depth: i32,
    pub max_depth: i32,
    pub min_surface: i32,
    pub max_surface: i32,
}

impl CompiledVolume {
    pub fn contains(&self, y: i32, surface: i32) -> bool {
        (self.min_y..=self.max_y).contains(&y)
            && (self.min_depth..=self.max_depth).contains(&(surface - y))
            && (self.min_surface..=self.max_surface).contains(&surface)
    }
}

/// One of the file's own climate values.
#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct CompiledClimateNoise {
    pub name: String,
    pub noise: NoiseDef,
}

/// A structure as a generator places it: its blocks (four numbers a block: x,
/// y and z from its smallest corner, and its index in its own palette), and
/// for each quarter turn (0 to 3, clockwise seen from above) the palette entry
/// each of its states becomes, 0 for the air it doesn't place.
#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LinkedTemplate {
    pub size_x: i32,
    pub size_y: i32,
    pub size_z: i32,
    pub blocks: Vec<i32>,
    pub turns: Vec<Vec<i32>>,
    /// For each of its own palette entries, whether it holds a block entity.
    pub with_entity: Vec<bool>,
}

/// A project structure's blocks as the server's or the editor's reader gives
/// them (format never reads a `.nbt`): its palette in block states, four
/// numbers a block, and the states with a block entity (where loot can go).
#[derive(Clone, Debug, PartialEq)]
pub struct StructureTemplate {
    pub size_x: i32,
    pub size_y: i32,
    pub size_z: i32,
    pub palette: Vec<String>,
    pub blocks: Vec<i32>,
    pub with_entity: std::collections::BTreeSet<usize>,
}

#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CompiledTerrain {
    pub palette: Vec<TerrainBlock>,
    pub base: i32,
    pub sea_level: i32,
    pub fluid: i32,
    pub noises: Vec<CompiledTerrainNoise>,
    pub layers: Vec<CompiledLayer>,
    pub underwater: Vec<CompiledLayer>,
    pub stone: i32,
    pub floor_block: i32,
    pub floor_thickness: i32,
    pub caves: Vec<CompiledCave>,
    pub ores: Vec<CompiledOre>,
    pub decorations: Vec<CompiledDecoration>,
    pub temperature: NoiseDef,
    pub humidity: NoiseDef,
    pub jitter: NoiseDef,
    pub jitter_amplitude: f64,
    /// How far heights blend across a border between areas whose terrain differs, in blocks; 0 for none.
    pub blend: i32,
    /// At least one: a file with no biomes is all plains.
    pub areas: Vec<CompiledArea>,
    /// Whether the game's own structures generate.
    pub vanilla_structures: bool,
    /// The script the file hands stages to.
    pub script: Option<CompiledScript>,
    /// The file's 3D terrain, or none when a column is solid up to its height.
    pub density: Option<CompiledDensity>,
    /// The structures `with_structures` linked in, by the name the file gives them.
    pub templates: BTreeMap<String, LinkedTemplate>,
    /// The file's own climate values, by name.
    pub climate: Vec<CompiledClimateNoise>,
    /// Every loot table it fills containers from (its decorations' and its script's), as the file names them, sorted.
    pub loot: Vec<String>,
}

/// What a template holds that a decoration leaves as the world has it.
const NOT_PLACED: [&str; 3] = [
    "minecraft:air",
    "minecraft:cave_air",
    "minecraft:structure_void",
];

impl CompiledTerrain {
    /// The areas that are a column's own (not volumes), as indexes.
    pub fn column_areas(&self) -> Vec<usize> {
        (0..self.areas.len())
            .filter(|i| self.areas[*i].volume.is_none())
            .collect()
    }

    /// The volume areas, as indexes.
    pub fn volume_areas(&self) -> Vec<usize> {
        (0..self.areas.len())
            .filter(|i| self.areas[*i].volume.is_some())
            .collect()
    }

    /// Every biome the areas use, once each, in area order.
    pub fn biomes(&self) -> Vec<&str> {
        let mut out: Vec<&str> = Vec::new();
        for area in &self.areas {
            if !out.contains(&area.biome.as_str()) {
                out.push(&area.biome);
            }
        }
        out
    }

    /// The custom blocks it places: what the server must have a state for.
    pub fn custom_blocks(&self) -> Vec<&str> {
        let mut out: Vec<&str> = Vec::new();
        for block in &self.palette {
            if let TerrainBlock::Custom { name } = block
                && !out.contains(&name.as_str())
            {
                out.push(name);
            }
        }
        out
    }

    /// The project's structures its decorations place, as the file names them, sorted.
    pub fn structures(&self) -> Vec<&str> {
        let mut out: Vec<&str> = self
            .decorations
            .iter()
            .filter_map(|d| d.structure.as_deref())
            .collect();
        out.sort();
        out.dedup();
        out
    }

    /// This terrain with the blocks its script's `sources` (the script and the
    /// modules it may `require`) name in full as string literals added to its
    /// palette, after every other: a game block state whose block `is_game_block`
    /// knows, or a project block's id that `is_project_block` knows, bare (the
    /// terrain's own) or a package's in full (`acme:altar`). A package's terrain
    /// (`namespace`) is compiled with its names in full, so its own bare ids join
    /// as `namespace:id`.
    pub fn with_script_blocks(
        &self,
        sources: &[&str],
        is_game_block: &dyn Fn(&str) -> bool,
        namespace: Option<&str>,
        is_project_block: &dyn Fn(&str) -> bool,
    ) -> CompiledTerrain {
        if self.script.is_none() {
            return self.clone();
        }
        let mut literals: Vec<String> = sources
            .iter()
            .flat_map(|s| crate::lua::literals(s))
            .collect();
        literals.sort();
        literals.dedup();
        let mut entries = self.palette.clone();
        for literal in literals {
            let block = if literal.starts_with("minecraft:") {
                BlockState::parse(&literal)
                    .filter(|s| is_game_block(&s.id))
                    .map(|s| TerrainBlock::Vanilla {
                        state: s.to_string(),
                    })
            } else if is_project_block_name(&literal) {
                let name = match namespace {
                    Some(ns) if !literal.contains(':') => format!("{ns}:{literal}"),
                    _ => literal,
                };
                is_project_block(&name).then_some(TerrainBlock::Custom { name })
            } else {
                None
            };
            if let Some(block) = block
                && !entries.contains(&block)
            {
                entries.push(block);
            }
        }
        CompiledTerrain {
            palette: entries,
            ..self.clone()
        }
    }

    /// This terrain with the structures its decorations place: each of
    /// `structures` (by the name the file gives it) that one names has its
    /// states added to the palette, once in each quarter turn. A structure left
    /// out isn't placed. Air, cave air and structure voids in a template are
    /// never placed: what's there stays.
    pub fn with_structures(
        &self,
        structures: &BTreeMap<String, StructureTemplate>,
    ) -> CompiledTerrain {
        let mut palette = Palette {
            entries: self.palette.clone(),
        };
        let mut linked = BTreeMap::new();
        for name in self.structures() {
            let Some(template) = structures.get(name) else {
                continue;
            };
            let canonical: Vec<String> = template
                .palette
                .iter()
                .map(|s| canonical_state(s))
                .collect();
            let turns = (0..4)
                .map(|turn| {
                    canonical
                        .iter()
                        .map(|state| {
                            let id = BlockState::parse(state).map(|s| s.id);
                            match id.is_some_and(|id| NOT_PLACED.contains(&id.as_str())) {
                                true => 0,
                                false => palette.of(TerrainBlock::Vanilla {
                                    state: turn_state(state, turn),
                                }),
                            }
                        })
                        .collect()
                })
                .collect();
            linked.insert(
                name.to_owned(),
                LinkedTemplate {
                    size_x: template.size_x,
                    size_y: template.size_y,
                    size_z: template.size_z,
                    blocks: template.blocks.clone(),
                    turns,
                    with_entity: (0..template.palette.len())
                        .map(|i| template.with_entity.contains(&i))
                        .collect(),
                },
            );
        }
        CompiledTerrain {
            palette: palette.entries,
            templates: linked,
            ..self.clone()
        }
    }
}

/// What a project block's id written in a script looks like: the project's own, or a package's.
fn is_project_block_name(text: &str) -> bool {
    let part = |p: &str| {
        !p.is_empty()
            && p.bytes()
                .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'_')
    };
    match text.split_once(':') {
        Some((ns, id)) => part(ns) && part(id),
        None => part(text),
    }
}

/// A block state in canonical text, or an id with its namespace.
fn canonical_state(text: &str) -> String {
    BlockState::parse(text).map_or_else(|| game::normalize(text), |s| s.to_string())
}

/// A block state turned `turns` quarter turns clockwise (seen from above), as a
/// structure turned with it holds it: `facing`, `axis`, `rotation` and the four
/// side properties.
pub fn turn_state(state: &str, turns: i32) -> String {
    const SIDES: [&str; 4] = ["north", "east", "south", "west"];
    let quarter = turns.rem_euclid(4) as usize;
    if quarter == 0 {
        return state.to_owned();
    }
    let Some(parsed) = BlockState::parse(state) else {
        return state.to_owned();
    };
    let side = |name: &str| SIDES.iter().position(|s| *s == name);
    let mut out: Vec<(String, String)> = Vec::new();
    let mut put = |key: String, value: String| match out.iter_mut().find(|(k, _)| *k == key) {
        Some(entry) => entry.1 = value,
        None => out.push((key, value)),
    };
    for (key, value) in &parsed.properties {
        let rotation = value.parse::<i32>().ok().filter(|r| (0..=15).contains(r));
        if key == "facing"
            && let Some(at) = side(value)
        {
            put(key.clone(), SIDES[(at + quarter) % 4].to_owned());
        } else if key == "axis" && quarter % 2 == 1 && (value == "x" || value == "z") {
            put(key.clone(), if value == "x" { "z" } else { "x" }.to_owned());
        } else if key == "rotation"
            && let Some(r) = rotation
        {
            put(key.clone(), ((r + 4 * quarter as i32) % 16).to_string());
        } else if let Some(at) = side(key) {
            put(SIDES[(at + quarter) % 4].to_owned(), value.clone());
        } else {
            put(key.clone(), value.clone());
        }
    }
    BlockState {
        id: parsed.id,
        properties: out,
    }
    .to_string()
}

/// The palette as it's built: air always 0, and a block one place however often a file names it.
struct Palette {
    entries: Vec<TerrainBlock>,
}

impl Palette {
    fn vanilla(&mut self, text: &str) -> i32 {
        self.of(TerrainBlock::Vanilla {
            state: canonical_state(text),
        })
    }

    fn custom(&mut self, name: &str) -> i32 {
        self.of(TerrainBlock::Custom {
            name: name.to_owned(),
        })
    }

    fn of(&mut self, block: TerrainBlock) -> i32 {
        let at = match self.entries.iter().position(|b| *b == block) {
            Some(at) => at,
            None => {
                self.entries.push(block);
                self.entries.len() - 1
            }
        };
        at as i32
    }

    fn block(&mut self, choice: &dyn BlockChoice, default: &str) -> i32 {
        match choice.custom_block() {
            Some(name) => self.custom(name),
            None => self.vanilla(choice.block().unwrap_or(default)),
        }
    }

    fn layers(&mut self, list: &[Layer]) -> Vec<CompiledLayer> {
        list.iter()
            .map(|layer| CompiledLayer {
                block: self.block(layer, AIR),
                thickness: given(filled(layer).thickness),
            })
            .collect()
    }
}

fn noises(map: &Named<TerrainNoise>) -> Vec<CompiledTerrainNoise> {
    let mut out: Vec<CompiledTerrainNoise> = map
        .iter()
        .map(|(name, n)| CompiledTerrainNoise {
            name: name.0.clone(),
            noise: n.noise,
            amplitude: given(filled(n).amplitude),
        })
        .collect();
    out.sort_by(|a, b| a.name.cmp(&b.name));
    out
}

fn density_noises(map: &Named<DensityNoise>) -> Vec<CompiledDensityNoise> {
    let mut out: Vec<CompiledDensityNoise> = map
        .iter()
        .map(|(name, n)| {
            let n = filled(n);
            CompiledDensityNoise {
                name: name.0.clone(),
                noise: n.noise,
                amplitude: given(n.amplitude),
                squash: given(n.squash),
            }
        })
        .collect();
    out.sort_by(|a, b| a.name.cmp(&b.name));
    out
}

/// Each of `named`'s entries in name order.
fn by_name<V>(named: &Named<V>) -> Vec<(&str, &V)> {
    let mut out: Vec<(&str, &V)> = named.iter().map(|(k, v)| (k.0.as_str(), v)).collect();
    out.sort_by(|a, b| a.0.cmp(b.0));
    out
}

/// `file` ready to run, for a file whose checks found no errors. `script` is
/// where its script is (`terrain/<id>.lua`), needed when it has one.
pub fn compile(file: &TerrainFile, script: Option<&str>) -> CompiledTerrain {
    let mut palette = Palette {
        entries: vec![TerrainBlock::Vanilla {
            state: AIR.to_owned(),
        }],
    };
    let terrain = filled(&file.terrain);
    let base = given(terrain.base);
    let fluid = palette.vanilla(terrain.fluid.as_deref().unwrap_or(DEFAULT_FLUID));
    let own_terrain = CompiledAreaTerrain {
        base,
        scale: 1.0,
        noises: Vec::new(),
        density_scale: 1.0,
        density_noises: Vec::new(),
    };
    let climate_file = filled(&file.climate);
    let climate: Vec<CompiledClimateNoise> = by_name(&file.climate.noises)
        .into_iter()
        .map(|(name, noise)| CompiledClimateNoise {
            name: name.to_owned(),
            noise: *noise,
        })
        .collect();
    let mut loot: Vec<String> = file
        .decorations
        .iter()
        .filter_map(|(_, d)| d.loot.clone())
        .chain(file.script.iter().flat_map(|s| s.loot.iter().cloned()))
        .collect();
    loot.sort();
    loot.dedup();
    let range = |r: &Option<super::ClimateRange>| {
        let r = r.as_ref().map(filled).unwrap_or_default();
        (r.min.unwrap_or(-1.0), r.max.unwrap_or(1.0))
    };
    let mut areas: Vec<CompiledArea> = by_name(&file.biomes)
        .into_iter()
        .map(|(name, area)| {
            let (temperature_min, temperature_max) = range(&area.temperature);
            let (humidity_min, humidity_max) = range(&area.humidity);
            CompiledArea {
                name: name.to_owned(),
                biome: area.biome.clone(),
                temperature_min,
                temperature_max,
                humidity_min,
                humidity_max,
                layers: area.layers.as_deref().map(|l| palette.layers(l)),
                underwater: area.underwater.as_deref().map(|l| palette.layers(l)),
                terrain: area.terrain.as_ref().map_or_else(
                    || own_terrain.clone(),
                    |own| {
                        let own_filled = filled(own);
                        let density = own.density.as_ref().map(filled);
                        CompiledAreaTerrain {
                            base: own.base.unwrap_or(base),
                            scale: given(own_filled.scale),
                            noises: noises(&own.noises),
                            density_scale: density.as_ref().map_or(1.0, |d| given(d.scale)),
                            density_noises: own
                                .density
                                .as_ref()
                                .map(|d| density_noises(&d.noises))
                                .unwrap_or_default(),
                        }
                    },
                ),
                climate: climate
                    .iter()
                    .flat_map(|c| {
                        let (min, max) = range(&area.climate.get_str(&c.name).cloned());
                        [min, max]
                    })
                    .collect(),
                volume: area.is_volume().then(|| {
                    let ends = |r: &Option<super::BlockRange>| {
                        let r = r.as_ref();
                        (
                            r.and_then(|r| r.min).unwrap_or(i32::MIN),
                            r.and_then(|r| r.max).unwrap_or(i32::MAX),
                        )
                    };
                    let ((min_y, max_y), (min_depth, max_depth), (min_surface, max_surface)) =
                        (ends(&area.y), ends(&area.depth), ends(&area.surface));
                    CompiledVolume {
                        min_y,
                        max_y,
                        min_depth,
                        max_depth,
                        min_surface,
                        max_surface,
                    }
                }),
            }
        })
        .collect();
    if areas.is_empty() {
        areas.push(CompiledArea {
            name: "default".to_owned(),
            biome: DEFAULT_BIOME.to_owned(),
            temperature_min: -1.0,
            temperature_max: 1.0,
            humidity_min: -1.0,
            humidity_max: 1.0,
            layers: None,
            underwater: None,
            terrain: own_terrain,
            climate: Vec::new(),
            volume: None,
        });
    }
    let areas_of = |names: &[String]| {
        let mut out: Vec<usize> = names
            .iter()
            .filter_map(|n| areas.iter().position(|a| &a.name == n))
            .collect();
        out.sort();
        out.dedup();
        out
    };
    let stone = match &file.stone {
        Some(stone) => palette.block(stone, DEFAULT_STONE),
        None => palette.vanilla(DEFAULT_STONE),
    };
    let floor = file.floor.as_ref().map(filled);
    let layers = palette.layers(&file.layers);
    let underwater = match file.underwater.is_empty() {
        true => palette.layers(&file.layers),
        false => palette.layers(&file.underwater),
    };
    let floor_block = match &file.floor {
        Some(f) => palette.block(f, "minecraft:bedrock"),
        None => 0,
    };
    let caves = by_name(&file.caves)
        .into_iter()
        .map(|(name, cave)| {
            let c = filled(cave);
            let kind = given(c.kind);
            CompiledCave {
                name: name.to_owned(),
                kind,
                noise: given(c.noise),
                threshold: cave.threshold.unwrap_or(kind.default_threshold()),
                min_y: given(c.min_y),
                max_y: given(c.max_y),
                depth: given(c.depth),
                areas: areas_of(&cave.biomes),
                fade: given(c.fade),
            }
        })
        .collect();
    let ores = by_name(&file.ores)
        .into_iter()
        .map(|(name, ore)| {
            let o = filled(ore);
            let mut replace: Vec<i32> = ore.replace.iter().map(|b| palette.vanilla(b)).collect();
            replace.extend(ore.replace_custom_blocks.iter().map(|b| palette.custom(b)));
            if replace.is_empty() {
                replace.push(stone);
            }
            replace.sort();
            replace.dedup();
            CompiledOre {
                name: name.to_owned(),
                block: palette.block(ore, AIR),
                replace,
                size: given(o.size),
                veins: given(o.veins),
                min_y: given(o.min_y),
                max_y: given(o.max_y),
                distribution: given(o.distribution),
                areas: areas_of(&ore.biomes),
            }
        })
        .collect();
    let decorations = by_name(&file.decorations)
        .into_iter()
        .map(|(name, decoration)| {
            let d = filled(decoration);
            let sorted = |list: &[String], f: fn(&str) -> String| {
                let mut out: Vec<String> = list.iter().map(|s| f(s)).collect();
                out.sort();
                out.dedup();
                out
            };
            CompiledDecoration {
                name: name.to_owned(),
                block: match decoration.structure {
                    Some(_) => 0,
                    None => palette.block(decoration, AIR),
                },
                structure: decoration.structure.clone(),
                placement: given(d.placement),
                count: given(d.count),
                chance: given(d.chance),
                noise: decoration.noise,
                threshold: given(d.threshold),
                areas: areas_of(&decoration.biomes),
                min_y: decoration.min_y,
                max_y: decoration.max_y,
                on: sorted(&decoration.on, game::normalize),
                rotate: decoration.structure.is_some() && given(d.rotate),
                loot: decoration
                    .loot
                    .as_ref()
                    .and_then(|l| loot.iter().position(|x| x == l))
                    .map_or(-1, |i| i as i32),
                on_custom: sorted(&decoration.on_custom_blocks, str::to_owned),
            }
        })
        .collect();
    let jitter = file
        .climate
        .jitter
        .as_ref()
        .map(filled)
        .unwrap_or_else(|| filled(&super::Jitter::default()));
    let density = terrain.density.as_ref().map(|density| CompiledDensity {
        noises: density_noises(&density.noises),
        islands: density.islands.as_ref().map(|islands| {
            let i = filled(islands);
            CompiledIslands {
                y: given(i.y),
                thickness: given(i.thickness),
                noise: given(i.noise),
                threshold: given(i.threshold),
                areas: areas_of(&islands.biomes),
            }
        }),
    });
    let compiled_script = file.script.as_ref().map(|s| {
        // Last, so the file's own blocks keep their places whatever the script lists.
        for block in &s.blocks {
            palette.vanilla(block);
        }
        for block in &s.custom_blocks {
            palette.custom(block);
        }
        CompiledScript {
            file: script
                .expect("a terrain with a script compiles with its script's path")
                .to_owned(),
            budget: given(filled(s).budget),
            noises: by_name(&s.noises)
                .into_iter()
                .map(|(name, noise)| CompiledScriptNoise {
                    name: name.to_owned(),
                    noise: *noise,
                })
                .collect(),
        }
    });
    CompiledTerrain {
        palette: palette.entries,
        base,
        sea_level: given(terrain.sea_level),
        fluid,
        noises: noises(&terrain.noises),
        layers,
        underwater,
        stone,
        floor_block,
        floor_thickness: floor.map_or(0, |f| given(f.thickness)),
        caves,
        ores,
        decorations,
        temperature: given(climate_file.temperature),
        humidity: given(climate_file.humidity),
        jitter: given(jitter.noise),
        jitter_amplitude: given(jitter.amplitude),
        blend: given(terrain.blend),
        areas,
        vanilla_structures: given(filled(&file.structures).vanilla),
        script: compiled_script,
        density,
        templates: BTreeMap::new(),
        climate,
        loot,
    }
}
