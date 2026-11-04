//! Format's `math`: three numbers, and an axis-aligned box of them.

use schemars::JsonSchema;
use serde::{Deserialize, Serialize};

/// Three numbers, written `[x, y, z]`.
#[derive(Clone, Copy, Debug, Default, PartialEq, Serialize, Deserialize, JsonSchema)]
pub struct Vec3(pub f64, pub f64, pub f64);

impl Vec3 {
    pub fn coordinates(self) -> [f64; 3] {
        [self.0, self.1, self.2]
    }
}

/// An axis-aligned box: its `min` and `max` corners, in blocks.
#[derive(Clone, Copy, Debug, Default, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct BoundingBox {
    pub min: Vec3,
    pub max: Vec3,
}

impl BoundingBox {
    /// Whether its max is past its min on every axis.
    pub fn has_volume(&self) -> bool {
        let (min, max) = (self.min.coordinates(), self.max.coordinates());
        (0..3).all(|i| max[i] > min[i])
    }
}
