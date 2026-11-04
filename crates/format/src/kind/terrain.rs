//! `terrain/<id>.json`: how a world's land is shaped, and maybe a
//! `terrain/<id>.lua` beside it whose stages the generator hands to Lua.
//! Format's `TerrainFile`, `TerrainKind.validate` and `TerrainValidator`:
//! its names, counts and numbers are their fields' rules (heights held to
//! every height a world can have, `LIMITS`); what depends on several fields,
//! the file's own areas and climate noises, and the game's blocks and biomes
//! are checked here. That a custom block is a plain cube is a check across
//! resources. (Its generator comes with the runtime's worlds.)

use schemars::JsonSchema;
use serde::{Deserialize, Serialize};

use crate::block_state::{self, BlockStateCodes};
use crate::canonical::{Entries, Map};
use crate::document::{Context, Document};
use crate::game::{self, GameData, Registry};
use crate::json::{child_path, format_number};
use crate::noise::NoiseDef;
use crate::problem::{ProblemCode, ProblemSink, codes};

pub const DEFAULT_STONE: &str = "minecraft:stone";
pub const DEFAULT_FLUID: &str = "minecraft:water";
pub const DEFAULT_BIOME: &str = "minecraft:plains";
pub const AIR: &str = "minecraft:air";
pub const MAX_LAYERS: usize = 32;
pub const MAX_NAMED: usize = 64;
pub const MAX_NOISES: usize = 16;
pub const MAX_CLIMATE_NOISES: usize = 8;
pub const MAX_THICKNESS: i32 = 256;
pub const MAX_AMPLITUDE: f64 = 4096.0;
pub const MAX_SCALE: f64 = 16.0;
pub const DEFAULT_BASE: i32 = 64;
pub const DEFAULT_SEA_LEVEL: i32 = 62;
pub const DEFAULT_BLEND: i32 = 16;
pub const DEFAULT_AMPLITUDE: f64 = 16.0;
pub const SCRIPT_EXTENSION: &str = ".lua";
pub const DEFAULT_BUDGET: i32 = 1_000_000;
pub const MAX_SCRIPT_BLOCKS: usize = 1024;

/// The heights a world can have, `min_y` to `max_y` (exclusive).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct WorldHeight {
    pub min_y: i32,
    pub max_y: i32,
}

/// Every height any world can have: what a file's heights are held to, since it doesn't know its worlds.
pub const LIMITS: WorldHeight = WorldHeight {
    min_y: -2032,
    max_y: 2032,
};
pub const OVERWORLD: WorldHeight = WorldHeight {
    min_y: -64,
    max_y: 320,
};

const BLOCK: BlockStateCodes = BlockStateCodes {
    state: codes::TERRAIN_BLOCK,
    unknown: codes::TERRAIN_BLOCK,
    property: codes::TERRAIN_BLOCK,
};

crate::ruled_string!(
    /// A name in one of a terrain's maps (caves, ores, areas, noises): an id, as the editor shows it.
    TerrainName,
    crate::names::ID_PATTERN,
    "terrain.name",
    crate::names::ID_RULE
);

/// A map of a terrain's, by name.
pub type Named<V> = Entries<TerrainName, V>;

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TerrainFile {
    #[serde(rename = "$schema", skip_serializing_if = "Option::is_none")]
    pub schema: Option<String>,
    #[serde(default, skip_serializing_if = "is_default")]
    pub terrain: Terrain,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    #[schemars(length(max = 32), extend("x-problem" = "terrain.limit"))]
    pub layers: Vec<Layer>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    #[schemars(length(max = 32), extend("x-problem" = "terrain.limit"))]
    pub underwater: Vec<Layer>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub stone: Option<Stone>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub floor: Option<Floor>,
    #[serde(default, skip_serializing_if = "Entries::is_empty")]
    #[schemars(extend("maxProperties" = 64, "x-problem" = "terrain.limit"))]
    pub caves: Named<Cave>,
    #[serde(default, skip_serializing_if = "Entries::is_empty")]
    #[schemars(extend("maxProperties" = 64, "x-problem" = "terrain.limit"))]
    pub ores: Named<Ore>,
    #[serde(default, skip_serializing_if = "Entries::is_empty")]
    #[schemars(extend("maxProperties" = 64, "x-problem" = "terrain.limit"))]
    pub decorations: Named<Decoration>,
    #[serde(default, skip_serializing_if = "is_default")]
    pub climate: Climate,
    #[serde(default, skip_serializing_if = "Entries::is_empty")]
    #[schemars(extend("maxProperties" = 64, "x-problem" = "terrain.limit"))]
    pub biomes: Named<BiomeArea>,
    #[serde(default, skip_serializing_if = "is_default")]
    pub structures: StructureRules,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub script: Option<TerrainScript>,
}

fn is_default<T: Default + PartialEq>(value: &T) -> bool {
    *value == T::default()
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Terrain {
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = -2032, max = 2031), extend("default" = 64, "x-problem" = "terrain.height"))]
    pub base: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = -2032, max = 2031), extend("default" = 62, "x-problem" = "terrain.height"))]
    pub sea_level: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub fluid: Option<String>,
    #[serde(default, skip_serializing_if = "Entries::is_empty")]
    #[schemars(extend("maxProperties" = 16, "x-problem" = "terrain.limit"))]
    pub noises: Named<TerrainNoise>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 0, max = 64), extend("default" = 16, "x-problem" = "terrain.border"))]
    pub blend: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub density: Option<Density>,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct TerrainNoise {
    #[serde(default)]
    pub noise: NoiseDef,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = -4096, max = 4096), extend("default" = 16, "x-problem" = "terrain.height"))]
    pub amplitude: Option<f64>,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct AreaTerrain {
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = -2032, max = 2031), extend("x-problem" = "terrain.height"))]
    pub base: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 0, max = 16), extend("default" = 1, "x-problem" = "terrain.height"))]
    pub scale: Option<f64>,
    #[serde(default, skip_serializing_if = "Entries::is_empty")]
    #[schemars(extend("maxProperties" = 16, "x-problem" = "terrain.limit"))]
    pub noises: Named<TerrainNoise>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub density: Option<AreaDensity>,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct Density {
    #[serde(default, skip_serializing_if = "Entries::is_empty")]
    #[schemars(extend("maxProperties" = 16, "x-problem" = "terrain.limit"))]
    pub noises: Named<DensityNoise>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub islands: Option<Islands>,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct DensityNoise {
    #[serde(default)]
    pub noise: NoiseDef,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = -4096, max = 4096), extend("default" = 16, "x-problem" = "terrain.height"))]
    pub amplitude: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(max = 16), extend("exclusiveMinimum" = 0, "default" = 1, "x-problem" = "terrain.density"))]
    pub squash: Option<f64>,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct AreaDensity {
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 0, max = 16), extend("default" = 1, "x-problem" = "terrain.density"))]
    pub scale: Option<f64>,
    #[serde(default, skip_serializing_if = "Entries::is_empty")]
    #[schemars(extend("maxProperties" = 16, "x-problem" = "terrain.limit"))]
    pub noises: Named<DensityNoise>,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct Islands {
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = -2032, max = 2031), extend("default" = 160, "x-problem" = "terrain.density"))]
    pub y: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 2, max = 256), extend("default" = 32, "x-problem" = "terrain.density"))]
    pub thickness: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub noise: Option<NoiseDef>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = -1), extend("exclusiveMaximum" = 1, "default" = 0.4, "x-problem" = "terrain.density"))]
    pub threshold: Option<f64>,
    /// The file's areas they float over.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub biomes: Vec<String>,
}

/// A block of the game's (`block`, a state) or of the project's (`customBlock`).
pub trait BlockChoice {
    fn block(&self) -> Option<&str>;
    fn custom_block(&self) -> Option<&str>;
}

macro_rules! block_choice {
    ($($t:ty),*) => {$(
        impl BlockChoice for $t {
            fn block(&self) -> Option<&str> {
                self.block.as_deref()
            }
            fn custom_block(&self) -> Option<&str> {
                self.custom_block.as_deref()
            }
        }
    )*};
}

block_choice!(Layer, Stone, Floor, Ore, Decoration);

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Layer {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub block: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub custom_block: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 1, max = 256), extend("default" = 1, "x-problem" = "terrain.layer"))]
    pub thickness: Option<i32>,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Stone {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub block: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub custom_block: Option<String>,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Floor {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub block: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub custom_block: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 1, max = 256), extend("default" = 5, "x-problem" = "terrain.layer"))]
    pub thickness: Option<i32>,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum CaveType {
    #[default]
    Cheese,
    Spaghetti,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Cave {
    #[serde(rename = "type", skip_serializing_if = "Option::is_none")]
    pub kind: Option<CaveType>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub noise: Option<NoiseDef>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub threshold: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = -2032, max = 2031), extend("default" = -56, "x-problem" = "terrain.cave"))]
    pub min_y: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = -2032, max = 2031), extend("default" = 56, "x-problem" = "terrain.cave"))]
    pub max_y: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 0, max = 256), extend("default" = 4, "x-problem" = "terrain.cave"))]
    pub depth: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 0, max = 256), extend("default" = 0, "x-problem" = "terrain.cave"))]
    pub fade: Option<i32>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub biomes: Vec<String>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum OreDistribution {
    Uniform,
    Triangle,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Ore {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub block: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub custom_block: Option<String>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub replace: Vec<String>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub replace_custom_blocks: Vec<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 1, max = 64), extend("default" = 8, "x-problem" = "terrain.ore"))]
    pub size: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 0, max = 256), extend("default" = 8, "x-problem" = "terrain.ore"))]
    pub veins: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = -2032, max = 2031), extend("default" = -64, "x-problem" = "terrain.ore"))]
    pub min_y: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = -2032, max = 2031), extend("default" = 64, "x-problem" = "terrain.ore"))]
    pub max_y: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub distribution: Option<OreDistribution>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub biomes: Vec<String>,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub enum DecorationPlacement {
    #[default]
    Surface,
    Underwater,
    Underground,
    CaveFloor,
    CaveCeiling,
}

impl DecorationPlacement {
    /// Where a structure can go.
    pub fn takes_structures(self) -> bool {
        matches!(
            self,
            DecorationPlacement::Surface
                | DecorationPlacement::Underwater
                | DecorationPlacement::Underground
        )
    }
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Decoration {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub block: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub custom_block: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub structure: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub placement: Option<DecorationPlacement>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 0, max = 256), extend("default" = 1, "x-problem" = "terrain.decoration"))]
    pub count: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 0, max = 1), extend("default" = 1, "x-problem" = "terrain.decoration"))]
    pub chance: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub noise: Option<NoiseDef>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = -1), extend("exclusiveMaximum" = 1, "default" = 0, "x-problem" = "terrain.decoration"))]
    pub threshold: Option<f64>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub biomes: Vec<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = -2032, max = 2031), extend("x-problem" = "terrain.decoration"))]
    pub min_y: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = -2032, max = 2031), extend("x-problem" = "terrain.decoration"))]
    pub max_y: Option<i32>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    #[schemars(length(max = 32), extend("x-problem" = "terrain.limit"))]
    pub on: Vec<String>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    #[schemars(length(max = 32), extend("x-problem" = "terrain.limit"))]
    pub on_custom_blocks: Vec<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub rotate: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub loot: Option<String>,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct Climate {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub temperature: Option<NoiseDef>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub humidity: Option<NoiseDef>,
    #[serde(default, skip_serializing_if = "Entries::is_empty")]
    #[schemars(extend("maxProperties" = 8, "x-problem" = "terrain.limit"))]
    pub noises: Named<NoiseDef>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub jitter: Option<Jitter>,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct Jitter {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub noise: Option<NoiseDef>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 0, max = 4096), extend("default" = 16, "x-problem" = "terrain.border"))]
    pub amplitude: Option<f64>,
}

/// Where in the climate an area is: each end from -1 to 1.
#[derive(Clone, Copy, Debug, Default, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct ClimateRange {
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = -1, max = 1), extend("default" = -1, "x-problem" = "terrain.climate"))]
    pub min: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = -1, max = 1), extend("default" = 1, "x-problem" = "terrain.climate"))]
    pub max: Option<f64>,
}

impl ClimateRange {
    pub fn min(&self) -> f64 {
        self.min.unwrap_or(-1.0)
    }

    pub fn max(&self) -> f64 {
        self.max.unwrap_or(1.0)
    }
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct BlockRange {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub min: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub max: Option<i32>,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct BiomeArea {
    /// One biome: the game's or the project's.
    pub biome: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub temperature: Option<ClimateRange>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub humidity: Option<ClimateRange>,
    #[serde(default, skip_serializing_if = "Entries::is_empty")]
    pub climate: Map<ClimateRange>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub y: Option<BlockRange>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub depth: Option<BlockRange>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub surface: Option<BlockRange>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(length(max = 32), extend("x-problem" = "terrain.limit"))]
    pub layers: Option<Vec<Layer>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(length(max = 32), extend("x-problem" = "terrain.limit"))]
    pub underwater: Option<Vec<Layer>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub terrain: Option<AreaTerrain>,
}

impl BiomeArea {
    /// Whether it's limited by height: a volume, not a column's area.
    pub fn is_volume(&self) -> bool {
        self.y.is_some() || self.depth.is_some() || self.surface.is_some()
    }
}

/// What the file hands its `.lua`.
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TerrainScript {
    /// Instructions a call.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 1000, max = 100000000), extend("default" = 1000000, "x-problem" = "terrain.script"))]
    pub budget: Option<i32>,
    #[serde(default, skip_serializing_if = "Entries::is_empty")]
    #[schemars(extend("maxProperties" = 16, "x-problem" = "terrain.limit"))]
    pub noises: Named<NoiseDef>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub blocks: Vec<String>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub custom_blocks: Vec<String>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    #[schemars(length(max = 64), extend("x-problem" = "terrain.script"))]
    pub loot: Vec<String>,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct StructureRules {
    /// Whether the game's own structures generate too. Default false.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub vanilla: Option<bool>,
}

/// What a file's checks need of the whole file (`TerrainValidator.Check`).
struct Check<'a> {
    sink: &'a mut ProblemSink,
    game: Option<&'a dyn GameData>,
    areas: Vec<&'a str>,
    climate_noises: Vec<&'a str>,
    volumes: Vec<&'a str>,
}

impl Check<'_> {
    fn report(&mut self, code: ProblemCode, message: impl Into<String>, at: impl Into<String>) {
        self.sink.report(code, message, Some(at.into()));
    }

    /// A block or a custom block (or a structure), not more; `required` when neither isn't allowed either.
    fn choice(
        &mut self,
        choice: &dyn BlockChoice,
        at: &str,
        required: bool,
        structure: bool,
        decoration: bool,
    ) {
        let given = [
            choice.block().is_some(),
            choice.custom_block().is_some(),
            structure,
        ]
        .iter()
        .filter(|g| **g)
        .count();
        if given > 1 {
            let names = if structure {
                "`block`, `customBlock` and `structure`, not more"
            } else {
                "`block` and `customBlock`, not both"
            };
            self.report(codes::TERRAIN_ONE_BLOCK, format!("Name one of {names}"), at);
        } else if given == 0 && required {
            let names = if decoration {
                "a `block`, a `customBlock` or a `structure`"
            } else {
                "a `block` or a `customBlock`"
            };
            self.report(codes::TERRAIN_ONE_BLOCK, format!("Name {names}"), at);
        }
        if let Some(block) = choice.block() {
            block_state::check(block, &format!("{at}.block"), BLOCK, self.sink, self.game);
        }
    }

    fn layers(&mut self, layers: &[Layer], at: &str) {
        for (i, layer) in layers.iter().enumerate() {
            self.choice(layer, &format!("{at}[{i}]"), true, false, false);
        }
    }

    /// A `biomes` filter names areas of this file.
    fn area_filter(&mut self, names: &[String], at: &str) {
        for (i, name) in names.iter().enumerate() {
            if !self.areas.contains(&name.as_str()) {
                let mut sorted = self.areas.clone();
                sorted.sort();
                let known = if sorted.is_empty() {
                    "the file has none".to_owned()
                } else {
                    format!("its areas are {}", sorted.join(", "))
                };
                self.report(
                    codes::TERRAIN_AREA,
                    format!("\"{name}\" isn't one of the file's biome areas ({known})"),
                    format!("{at}[{i}]"),
                );
            }
        }
    }

    /// The lower of two heights not above the higher (each in the world is its rule).
    fn heights(&mut self, min: i32, max: i32, at: &str, code: ProblemCode) {
        if min > max {
            self.report(
                code,
                format!("minY ({min}) is above maxY ({max})"),
                format!("{at}.minY"),
            );
        }
    }

    fn block_ids(&mut self, ids: &[String], at: &str) {
        for (i, id) in ids.iter().enumerate() {
            let path = format!("{at}[{i}]");
            if !game::is_id(id) {
                self.report(
                    codes::TERRAIN_BLOCK,
                    format!("\"{id}\" isn't a block id"),
                    path,
                );
            } else if let Some(game) = self.game
                && game.block_properties(&game::normalize(id)).is_none()
            {
                self.report(
                    codes::TERRAIN_BLOCK,
                    format!(
                        "Minecraft {} has no block \"{}\"",
                        game.minecraft_version(),
                        game::normalize(id)
                    ),
                    path,
                );
            }
        }
    }

    fn cave(&mut self, cave: &Cave, at: &str) {
        let kind = cave.kind.unwrap_or_default();
        if let Some(t) = cave.threshold {
            let cheese = kind == CaveType::Cheese;
            let ok = if cheese {
                (-1.0..=1.0).contains(&t)
            } else {
                t > 0.0 && t <= 1.0
            };
            if !ok {
                let range = if cheese {
                    "from -1 to 1"
                } else {
                    "more than 0 and at most 1"
                };
                let name = crate::canonical::name_of(&kind);
                self.report(
                    codes::TERRAIN_CAVE,
                    format!("threshold of a {name} cave must be {range}"),
                    format!("{at}.threshold"),
                );
            }
        }
        self.heights(
            cave.min_y.unwrap_or(-56),
            cave.max_y.unwrap_or(56),
            at,
            codes::TERRAIN_CAVE,
        );
        self.area_filter(&cave.biomes, &format!("{at}.biomes"));
    }

    fn ore(&mut self, ore: &Ore, at: &str) {
        if ore.block.is_none() == ore.custom_block.is_none() {
            let message = if ore.block.is_none() {
                "An ore needs a `block` (a vanilla one) or a `customBlock` (one of the project's)"
            } else {
                "An ore is a `block` or a `customBlock`, not both"
            };
            self.report(codes::TERRAIN_ORE, message, at);
        }
        if let Some(block) = &ore.block {
            block_state::check(block, &format!("{at}.block"), BLOCK, self.sink, self.game);
        }
        self.block_ids(&ore.replace, &format!("{at}.replace"));
        self.heights(
            ore.min_y.unwrap_or(-64),
            ore.max_y.unwrap_or(64),
            at,
            codes::TERRAIN_ORE,
        );
        self.area_filter(&ore.biomes, &format!("{at}.biomes"));
    }

    fn decoration(&mut self, decoration: &Decoration, at: &str) {
        self.choice(decoration, at, true, decoration.structure.is_some(), true);
        if decoration.structure.is_some()
            && !decoration.placement.unwrap_or_default().takes_structures()
        {
            self.report(
                codes::TERRAIN_DECORATION,
                "A structure goes on the surface, underwater or underground, not on a cave's floor or ceiling",
                format!("{at}.placement"),
            );
        }
        if decoration.loot.is_some() && decoration.custom_block.is_some() {
            self.report(
                codes::TERRAIN_DECORATION,
                "A project block holds no items to fill from a loot table",
                format!("{at}.loot"),
            );
        }
        if decoration.structure.is_none() && decoration.rotate.is_some() {
            self.report(
                codes::TERRAIN_DECORATION,
                "Only a structure is turned",
                format!("{at}.rotate"),
            );
        }
        if decoration.threshold.is_some() && decoration.noise.is_none() {
            self.report(
                codes::TERRAIN_DECORATION,
                "A threshold is of a noise: add one, or leave the threshold out",
                format!("{at}.threshold"),
            );
        }
        let (lowest, highest) = (LIMITS.min_y, LIMITS.max_y - 1);
        self.heights(
            decoration.min_y.unwrap_or(lowest),
            decoration.max_y.unwrap_or(highest),
            at,
            codes::TERRAIN_DECORATION,
        );
        self.block_ids(&decoration.on, &format!("{at}.on"));
        self.area_filter(&decoration.biomes, &format!("{at}.biomes"));
    }

    fn climate_range(&mut self, range: &ClimateRange, at: &str) {
        if range.min() > range.max() {
            self.report(
                codes::TERRAIN_CLIMATE,
                format!(
                    "min ({}) is above max ({})",
                    format_number(range.min()),
                    format_number(range.max())
                ),
                format!("{at}.min"),
            );
        }
    }

    /// A volume's range: inside the world (a depth needn't be), and not empty.
    fn block_range(&mut self, range: Option<&BlockRange>, at: &str, in_world: bool) {
        let Some(range) = range else { return };
        let (lowest, highest) = (LIMITS.min_y, LIMITS.max_y - 1);
        let limit = LIMITS.max_y - LIMITS.min_y;
        for (key, value) in [("min", range.min), ("max", range.max)] {
            let Some(value) = value else { continue };
            let ok = if in_world {
                (lowest..=highest).contains(&value)
            } else {
                (-limit..=limit).contains(&value)
            };
            if !ok {
                let span = if in_world {
                    format!("from {lowest} to {highest}")
                } else {
                    format!("from -{limit} to {limit}")
                };
                self.report(
                    codes::TERRAIN_VOLUME,
                    format!("{key} must be {span}, not {value}"),
                    format!("{at}.{key}"),
                );
            }
        }
        if let (Some(min), Some(max)) = (range.min, range.max)
            && min > max
        {
            self.report(
                codes::TERRAIN_VOLUME,
                format!("min ({min}) is above max ({max})"),
                format!("{at}.min"),
            );
        }
    }

    fn area(&mut self, area: &BiomeArea, at: &str) {
        let biome = &area.biome;
        // The project's own biomes are references, checked across resources; the game's are checked here.
        if biome.starts_with('#') {
            self.report(
                codes::TERRAIN_BIOME,
                format!("An area is one biome, not a tag: \"{biome}\""),
                format!("{at}.biome"),
            );
        } else if game::is_games(biome) {
            if !game::is_id(biome) {
                self.report(
                    codes::TERRAIN_BIOME,
                    format!("\"{biome}\" isn't a biome id, like \"minecraft:plains\""),
                    format!("{at}.biome"),
                );
            } else if let Some(game) = self.game
                && game.lacks(Registry::Biome, biome)
            {
                self.report(
                    codes::TERRAIN_BIOME,
                    format!(
                        "Minecraft {} has no biome \"{biome}\"",
                        game.minecraft_version()
                    ),
                    format!("{at}.biome"),
                );
            }
        }
        if let Some(range) = &area.temperature {
            self.climate_range(range, &format!("{at}.temperature"));
        }
        if let Some(range) = &area.humidity {
            self.climate_range(range, &format!("{at}.humidity"));
        }
        for (name, range) in area.climate.iter() {
            let path = child_path(&format!("{at}.climate"), name);
            if !self.climate_noises.contains(&name.0.as_str()) {
                let mut sorted = self.climate_noises.clone();
                sorted.sort();
                let known = if sorted.is_empty() {
                    "it declares none".to_owned()
                } else {
                    format!("it declares {}", sorted.join(", "))
                };
                self.report(
                    codes::TERRAIN_CLIMATE,
                    format!("\"{name}\" isn't one of the file's climate.noises ({known})"),
                    path.clone(),
                );
            }
            self.climate_range(range, &path);
        }
        if area.is_volume() {
            self.block_range(area.y.as_ref(), &format!("{at}.y"), true);
            self.block_range(area.depth.as_ref(), &format!("{at}.depth"), false);
            self.block_range(area.surface.as_ref(), &format!("{at}.surface"), true);
            for (key, given) in [
                ("layers", area.layers.is_some()),
                ("underwater", area.underwater.is_some()),
                ("terrain", area.terrain.is_some()),
            ] {
                if given {
                    self.report(
                        codes::TERRAIN_VOLUME,
                        format!("An area limited by height gives its places a biome and nothing else: it has no {key}"),
                        format!("{at}.{key}"),
                    );
                }
            }
        }
        if let Some(layers) = &area.layers {
            self.layers(layers, &format!("{at}.layers"));
        }
        if let Some(layers) = &area.underwater {
            self.layers(layers, &format!("{at}.underwater"));
        }
    }
}

impl TerrainFile {
    /// Its checks on itself, against every height a world can have.
    fn check(&self, check: &mut Check) {
        if let Some(fluid) = &self.terrain.fluid {
            block_state::check(fluid, "$.terrain.fluid", BLOCK, check.sink, check.game);
        }
        if let Some(islands) = self
            .terrain
            .density
            .as_ref()
            .and_then(|d| d.islands.as_ref())
        {
            let at = "$.terrain.density.islands";
            check.area_filter(&islands.biomes, &format!("{at}.biomes"));
            for (i, name) in islands.biomes.iter().enumerate() {
                if check.volumes.contains(&name.as_str()) {
                    check.report(
                        codes::TERRAIN_VOLUME,
                        format!(
                            "\"{name}\" is limited by height: islands float over columns' areas"
                        ),
                        format!("{at}.biomes[{i}]"),
                    );
                }
            }
        }
        for (name, area) in self.biomes.iter() {
            if area
                .terrain
                .as_ref()
                .and_then(|t| t.density.as_ref())
                .is_some()
                && self.terrain.density.is_none()
            {
                check.report(
                    codes::TERRAIN_DENSITY,
                    "An area's density is of the file's: add a terrain.density to the file (`{}` for no noises of its own)",
                    format!("{}.terrain.density", child_path("$.biomes", name)),
                );
            }
        }
        check.layers(&self.layers, "$.layers");
        check.layers(&self.underwater, "$.underwater");
        if let Some(stone) = &self.stone {
            check.choice(stone, "$.stone", true, false, false);
        }
        if let Some(floor) = &self.floor {
            check.choice(floor, "$.floor", false, false, false);
        }
        for (name, cave) in self.caves.iter() {
            check.cave(cave, &child_path("$.caves", name));
        }
        for (name, ore) in self.ores.iter() {
            check.ore(ore, &child_path("$.ores", name));
        }
        for (name, decoration) in self.decorations.iter() {
            check.decoration(decoration, &child_path("$.decorations", name));
        }
        if !self.biomes.is_empty() && self.biomes.iter().all(|(_, a)| a.is_volume()) {
            check.report(
                codes::TERRAIN_VOLUME,
                "Every biome area here is limited by height, so no column has an area: add one without `y`, `depth` or `surface`",
                "$.biomes",
            );
        }
        for (name, area) in self.biomes.iter() {
            check.area(area, &child_path("$.biomes", name));
        }
        if let Some(script) = &self.script {
            if script.blocks.len() + script.custom_blocks.len() > MAX_SCRIPT_BLOCKS {
                check.report(
                    codes::TERRAIN_SCRIPT,
                    format!("A script lists at most {MAX_SCRIPT_BLOCKS} blocks"),
                    "$.script",
                );
            }
            for (i, block) in script.blocks.iter().enumerate() {
                block_state::check(
                    block,
                    &format!("$.script.blocks[{i}]"),
                    BLOCK,
                    check.sink,
                    check.game,
                );
            }
        }
    }
}

impl Document for TerrainFile {
    const SCHEMA: &'static str = "../.netherforge/schema/terrain.schema.json";

    fn set_schema(&mut self, schema: Option<String>) {
        self.schema = schema;
    }

    fn validate(&self, cx: &Context, sink: &mut ProblemSink) {
        fn names(m: &Named<BiomeArea>, keep: fn(&BiomeArea) -> bool) -> Vec<&str> {
            m.iter()
                .filter(|(_, a)| keep(a))
                .map(|(k, _)| k.0.as_str())
                .collect()
        }
        let mut check = Check {
            sink,
            game: cx.game,
            areas: names(&self.biomes, |_| true),
            climate_noises: self
                .climate
                .noises
                .iter()
                .map(|(k, _)| k.0.as_str())
                .collect(),
            volumes: names(&self.biomes, BiomeArea::is_volume),
        };
        self.check(&mut check);
        // Its script beside it: the file's `script` and the `.lua` say the same.
        let Some(files) = cx.files else { return };
        let script = format!("{}{SCRIPT_EXTENSION}", cx.id);
        let script_path = format!(
            "{}{SCRIPT_EXTENSION}",
            cx.location.strip_suffix(".json").unwrap_or(cx.location)
        );
        if self.script.is_some() && !files.contains(&script) {
            sink.report(
                codes::TERRAIN_SCRIPT_MISSING,
                format!("There's no {script_path} beside this file for its script to run"),
                Some("$.script".into()),
            );
        } else if self.script.is_none() && files.contains(&script) {
            sink.problems.push(codes::TERRAIN_SCRIPT_UNUSED.at(
                &script_path,
                format!(
                    "{} has no `script`, so this never runs: add one to hand it stages",
                    cx.location
                ),
                None,
            ));
        }
    }
}
