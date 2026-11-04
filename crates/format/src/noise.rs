//! A noise as files describe one (format's `NoiseDef`): FastNoiseLite's
//! settings. Its numbers' rules are its fields'. (The noise itself comes with
//! the terrain generator.)

use schemars::JsonSchema;
use serde::{Deserialize, Serialize};

pub const DEFAULT_FREQUENCY: f64 = 0.01;
pub const DEFAULT_LACUNARITY: f64 = 2.0;
pub const DEFAULT_GAIN: f64 = 0.5;
pub const MAX_OCTAVES: i32 = 8;
pub const MAX_FREQUENCY: f64 = 1.0;

#[derive(Clone, Copy, Debug, Default, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct NoiseDef {
    #[serde(rename = "type", skip_serializing_if = "Option::is_none")]
    pub kind: Option<NoiseType>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(max = 1), extend("exclusiveMinimum" = 0, "default" = 0.01, "x-problem" = "terrain.noise"))]
    pub frequency: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub fractal: Option<NoiseFractal>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 1, max = 8), extend("default" = 1, "x-problem" = "terrain.noise"))]
    pub octaves: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 1, max = 4), extend("default" = 2, "x-problem" = "terrain.noise"))]
    pub lacunarity: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 0, max = 1), extend("default" = 0.5, "x-problem" = "terrain.noise"))]
    pub gain: Option<f64>,
}

impl NoiseDef {
    /// One with only its frequency and octaves set.
    pub const fn of(frequency: f64, octaves: i32) -> NoiseDef {
        NoiseDef {
            kind: None,
            frequency: Some(frequency),
            fractal: None,
            octaves: Some(octaves),
            lacunarity: None,
            gain: None,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub enum NoiseType {
    OpenSimplex2,
    OpenSimplex2s,
    Perlin,
    Value,
    ValueCubic,
    Cellular,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub enum NoiseFractal {
    None,
    Fbm,
    Ridged,
    PingPong,
}
