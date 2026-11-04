//! What the world's generation shares between kinds: the steps features are
//! placed in (a biome's and a structure's).

use schemars::JsonSchema;
use serde::{Deserialize, Serialize};

/// When in a chunk's generation something is placed, in the game's order.
#[derive(
    Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize, Deserialize, JsonSchema,
)]
#[serde(rename_all = "snake_case")]
pub enum GenerationStep {
    RawGeneration,
    Lakes,
    LocalModifications,
    UndergroundStructures,
    SurfaceStructures,
    Strongholds,
    UndergroundOres,
    UndergroundDecoration,
    FluidSprings,
    VegetalDecoration,
    TopLayerModification,
}

impl GenerationStep {
    pub fn id(self) -> String {
        crate::canonical::name_of(&self)
    }
}

/// How many: a group of spawns, a range of ticks.
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct SpawnRange {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub min: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub max: Option<i32>,
}
