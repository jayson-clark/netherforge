//! A noise as files describe one (format's `NoiseDef`): FastNoiseLite's
//! settings, its numbers' rules its fields'; the noise itself
//! (`fast_noise_lite`, in doubles); and the seeds each part of a world draws
//! from (`seeds`, format's `TerrainSeeds`).

pub mod fast_noise_lite;

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
    pub fn kind_or_default(&self) -> NoiseType {
        self.kind.unwrap_or(NoiseType::OpenSimplex2)
    }

    pub fn frequency_or_default(&self) -> f64 {
        self.frequency.unwrap_or(DEFAULT_FREQUENCY)
    }

    pub fn octaves_or_default(&self) -> i32 {
        self.octaves.unwrap_or(1)
    }

    pub fn fractal_or_default(&self) -> NoiseFractal {
        self.fractal.unwrap_or(NoiseFractal::Fbm)
    }

    /// The noise of this field with `seed`: configured once, then safe to ask
    /// from any number of threads (asking changes nothing).
    pub fn build(&self, seed: i32) -> fast_noise_lite::FastNoiseLite {
        let mut noise = fast_noise_lite::FastNoiseLite::with_seed(seed);
        noise.set_noise_type(Some(self.kind_or_default().fnl()));
        noise.set_frequency(Some(self.frequency_or_default()));
        if self.octaves_or_default() > 1 {
            noise.set_fractal_type(Some(self.fractal_or_default().fnl()));
            noise.set_fractal_octaves(Some(self.octaves_or_default()));
            noise.set_fractal_lacunarity(Some(self.lacunarity.unwrap_or(DEFAULT_LACUNARITY)));
            noise.set_fractal_gain(Some(self.gain.unwrap_or(DEFAULT_GAIN)));
        }
        noise
    }

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

impl NoiseType {
    pub fn fnl(self) -> fast_noise_lite::NoiseType {
        use fast_noise_lite::NoiseType as N;
        match self {
            NoiseType::OpenSimplex2 => N::OpenSimplex2,
            NoiseType::OpenSimplex2s => N::OpenSimplex2S,
            NoiseType::Perlin => N::Perlin,
            NoiseType::Value => N::Value,
            NoiseType::ValueCubic => N::ValueCubic,
            NoiseType::Cellular => N::Cellular,
        }
    }
}

impl NoiseFractal {
    pub fn fnl(self) -> fast_noise_lite::FractalType {
        use fast_noise_lite::FractalType as F;
        match self {
            NoiseFractal::None => F::None,
            NoiseFractal::Fbm => F::FBm,
            NoiseFractal::Ridged => F::Ridged,
            NoiseFractal::PingPong => F::PingPong,
        }
    }
}

/// Every random choice of a generated world comes from its seed alone: these
/// make the integer each part of it starts from, in 32-bit integers only, so
/// the server and the editor's preview draw the same world (format's
/// `TerrainSeeds`).
pub mod seeds {
    /// The 32 bits a world's `seed` stands for.
    pub fn fold(seed: i64) -> i32 {
        (seed ^ ((seed as u64) >> 32) as i64) as i32
    }

    /// The seed of the part of a world called `role` (`height:hills`,
    /// `cave:caverns`), apart from every other part's.
    pub fn for_role(seed: i64, role: &str) -> i32 {
        let mut h = mix(fold(seed) ^ 0x2545_F491);
        for c in role.encode_utf16() {
            h = mix(h ^ i32::from(c)).wrapping_mul(0x0100_0193);
        }
        mix(h)
    }

    /// A well-mixed integer from `x`: murmur3's finaliser.
    pub fn mix(x: i32) -> i32 {
        let mut h = x;
        h ^= ((h as u32) >> 16) as i32;
        h = h.wrapping_mul(-0x7a14_3595);
        h ^= ((h as u32) >> 13) as i32;
        h = h.wrapping_mul(-0x3d4d_51cb);
        h ^= ((h as u32) >> 16) as i32;
        h
    }

    /// A number from 0 up to but not including 1 for the cell (x, y, z) of a world.
    pub fn unit(seed: i32, x: i32, y: i32, z: i32) -> f64 {
        let mut h = mix(seed ^ x.wrapping_mul(0x1B87_3593));
        h = mix(h ^ z.wrapping_mul(0x2C1B_3C6D));
        h = mix(h ^ y.wrapping_mul(0x297A_2D39));
        f64::from((h as u32) >> 8) * (1.0 / 16_777_216.0)
    }
}
