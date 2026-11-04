//! `structures/<id>.nbt`, a saved region scripts place, and beside it
//! `structures/<id>.json`, where the world generates it by itself (format's
//! `StructureGeneration`, `StructureKind` and `StructureValidator`). Format
//! never reads the template; the generation file is its one document. Its
//! spacing, ranges, weights and pool names are its fields' rules. That a
//! pool's structures exist is a check across resources.

use schemars::JsonSchema;
use serde::{Deserialize, Serialize};

use crate::canonical::Map;
use crate::document::{Context, Document};
use crate::game::{self, GameData, Registry};
use crate::json::child_path;
use crate::problem::{ProblemSink, codes};
use crate::worldgen::GenerationStep;

pub const DEFAULT_SPACING: i32 = 32;
pub const DEFAULT_SEPARATION: i32 = 8;
pub const DEFAULT_MAX_DISTANCE: i32 = 80;
pub const DEFAULT_DEPTH_WITH_POOLS: i32 = 7;
pub const MAX_SPACING: i32 = 4096;
pub const MAX_DEPTH: i32 = 20;
pub const MAX_DISTANCE: i32 = 128;
pub const MAX_WEIGHT: i32 = 150;
/// The pool a structure's own template starts from: `<namespace>:<id>/start`.
pub const START_POOL: &str = "start";

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct StructureGeneration {
    #[serde(rename = "$schema", skip_serializing_if = "Option::is_none")]
    pub schema: Option<String>,
    /// Where it generates: biomes, the game's or the project's, or one of the game's tags.
    pub biomes: Vec<String>,
    /// The side, in chunks, of the squares each of which may hold one.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 1, max = 4096), extend("default" = 32, "x-problem" = "structure.spread"))]
    pub spacing: Option<i32>,
    /// Chunks kept clear between two squares' structures: below spacing.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 0, max = 4095), extend("default" = 8, "x-problem" = "structure.spread"))]
    pub separation: Option<i32>,
    /// Mixes its squares apart from every other's. Default: made from its name.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 0), extend("x-problem" = "structure.spread"))]
    pub salt: Option<i32>,
    /// When in the world's generation it's placed. Default surface structures.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub step: Option<GenerationStep>,
    /// How the terrain around it is shaped to it. Default none.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub terrain_adaptation: Option<TerrainAdaptation>,
    /// What startHeight counts from. Default `world_surface_wg`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub heightmap: Option<StructureHeightmap>,
    /// Blocks above the heightmap's ground, or the height itself with `none`. Default 0.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub start_height: Option<i32>,
    /// More pieces: pools of project structures the template's jigsaw blocks name.
    #[serde(default, skip_serializing_if = "crate::canonical::Entries::is_empty")]
    #[schemars(extend("propertyNames" = {
        "pattern": crate::names::ID_PATTERN,
        "x-problem": "structure.pool",
        "x-rule": crate::names::ID_RULE
    }))]
    pub pools: Map<StructurePool>,
    /// How many pieces deep jigsaw blocks connect. Default 7 with pools, else 1.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 1, max = 20), extend("x-problem" = "structure.range"))]
    pub depth: Option<i32>,
    /// How far from where it starts a piece may reach, in blocks.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 1, max = 128), extend("default" = 80, "x-problem" = "structure.range"))]
    pub max_distance: Option<i32>,
}

/// What a jigsaw block may connect a piece from, picked by weight.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct StructurePool {
    pub elements: Vec<PoolElement>,
    /// `rigid` places a piece as saved; `terrain_matching` bends it to the ground. Default rigid.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub projection: Option<PoolProjection>,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct PoolElement {
    /// The project's own structure, whose template is the piece.
    pub structure: String,
    /// Its share of the pool. Default 1.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 1, max = 150), extend("default" = 1, "x-problem" = "structure.range"))]
    pub weight: Option<i32>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum TerrainAdaptation {
    None,
    BeardThin,
    BeardBox,
    Bury,
    Encapsulate,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum StructureHeightmap {
    None,
    WorldSurfaceWg,
    WorldSurface,
    OceanFloorWg,
    OceanFloor,
    MotionBlocking,
    MotionBlockingNoLeaves,
}

impl StructureHeightmap {
    /// How the game spells it (`WORLD_SURFACE_WG`); `None` for none.
    pub fn game(self) -> Option<String> {
        (self != StructureHeightmap::None).then(|| crate::canonical::name_of(&self).to_uppercase())
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum PoolProjection {
    Rigid,
    TerrainMatching,
}

/// Whether a biome entry is the game's (the project's are references): `minecraft:…`, or a tag in no other namespace.
fn is_games(text: &str) -> bool {
    match text.strip_prefix('#') {
        Some(tag) => tag
            .split_once(':')
            .is_none_or(|(n, _)| n == game::NAMESPACE),
        None => text
            .split_once(':')
            .is_some_and(|(n, _)| n == game::NAMESPACE),
    }
}

/// A biome list is ids, or one `#tag`: the game takes a tag only on its own.
fn check_biomes(biomes: &[String], sink: &mut ProblemSink, game: Option<&dyn GameData>) {
    if biomes.is_empty() {
        sink.report(
            codes::STRUCTURE_BIOMES,
            "A structure generates in at least one biome, or in a tag's: biomes is empty",
            Some("$.biomes".into()),
        );
        return;
    }
    let tagged = biomes.iter().any(|b| b.starts_with('#'));
    if tagged && biomes.len() > 1 {
        sink.report(
            codes::STRUCTURE_BIOMES,
            "A tag (#…) stands alone in biomes: list biome ids, or name one tag",
            Some("$.biomes".into()),
        );
        return;
    }
    for (index, text) in biomes.iter().enumerate() {
        if !is_games(text) {
            continue;
        }
        let at = Some(format!("$.biomes[{index}]"));
        let id = text.trim_start_matches('#');
        if !game::is_id(id) {
            sink.report(
                codes::STRUCTURE_BIOMES,
                format!("\"{text}\" isn't a biome id, like \"minecraft:plains\", or a tag, like \"#minecraft:is_forest\""),
                at,
            );
            continue;
        }
        let Some(game) = game else { continue };
        let normal = game::normalize(id);
        let missing = if tagged {
            game.tag(Registry::Biome, &normal).is_none()
        } else {
            !game.has(Registry::Biome, &normal)
        };
        if missing {
            let noun = if tagged { "tag" } else { "biome" };
            sink.report(
                codes::STRUCTURE_UNKNOWN_BIOME,
                format!(
                    "Minecraft {} has no {noun} \"{text}\"",
                    game.minecraft_version()
                ),
                at,
            );
        }
    }
}

impl Document for StructureGeneration {
    const SCHEMA: &'static str = "../.netherforge/schema/structure_generation.schema.json";

    fn set_schema(&mut self, schema: Option<String>) {
        self.schema = schema;
    }

    fn validate(&self, cx: &Context, sink: &mut ProblemSink) {
        check_biomes(&self.biomes, sink, cx.game);
        // Each in its range is its rule; that separation stays below spacing is checked once both are.
        let spacing = self.spacing.unwrap_or(DEFAULT_SPACING);
        let separation = self.separation.unwrap_or(DEFAULT_SEPARATION);
        let in_range =
            (1..=MAX_SPACING).contains(&spacing) && (0..MAX_SPACING).contains(&separation);
        if in_range && separation >= spacing {
            sink.report(
                codes::STRUCTURE_SPREAD,
                format!("separation ({separation}) must be below spacing ({spacing}): it's the gap kept inside each square"),
                Some(if self.separation.is_some() { "$.separation" } else { "$.spacing" }.into()),
            );
        }
        for (name, pool) in self.pools.iter() {
            let at = child_path("$.pools", &name.0);
            if name.0 == START_POOL {
                sink.report(
                    codes::STRUCTURE_POOL,
                    format!("\"{START_POOL}\" is the pool the structure's own template starts from: name this one something else"),
                    Some(at.clone()),
                );
            }
            if pool.elements.is_empty() {
                sink.report(
                    codes::STRUCTURE_POOL,
                    "A pool picks from at least one structure",
                    Some(child_path(&at, "elements")),
                );
            }
        }
    }
}
