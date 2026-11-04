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

impl Vec3 {
    pub const ZERO: Vec3 = Vec3(0.0, 0.0, 0.0);
    pub const ONE: Vec3 = Vec3(1.0, 1.0, 1.0);

    /// Each coordinate times `other`'s.
    pub fn times(self, other: Vec3) -> Vec3 {
        Vec3(self.0 * other.0, self.1 * other.1, self.2 * other.2)
    }
}

impl std::ops::Add for Vec3 {
    type Output = Vec3;
    fn add(self, o: Vec3) -> Vec3 {
        Vec3(self.0 + o.0, self.1 + o.1, self.2 + o.2)
    }
}

impl std::ops::Sub for Vec3 {
    type Output = Vec3;
    fn sub(self, o: Vec3) -> Vec3 {
        Vec3(self.0 - o.0, self.1 - o.1, self.2 - o.2)
    }
}

impl std::ops::Mul<f64> for Vec3 {
    type Output = Vec3;
    fn mul(self, factor: f64) -> Vec3 {
        Vec3(self.0 * factor, self.1 * factor, self.2 * factor)
    }
}

/// Degrees the short way round: 350° to 10° travels 20°; exactly opposite
/// angles travel +180 (a centity's rotation channel, a cutscene's camera).
pub fn shortest_delta(from: f64, to: f64) -> f64 {
    // Kotlin's `%` keeps the dividend's sign, as Rust's does.
    let raw = (to - from) % 360.0;
    if raw > 180.0 {
        raw - 360.0
    } else if raw <= -180.0 {
        raw + 360.0
    } else {
        raw
    }
}

/// `degrees` as an angle in `[-180, 180)`, which is how the game keeps a facing.
pub fn normalize_degrees(degrees: f64) -> f64 {
    ((degrees + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
}

pub fn degrees_to_radians(degrees: f64) -> f64 {
    degrees * (std::f64::consts::PI / 180.0)
}

pub fn radians_to_degrees(radians: f64) -> f64 {
    radians * (180.0 / std::f64::consts::PI)
}

/// The 4x4 transform maths composition needs, shared so the editor's preview
/// and the server place every node identically (format's `Matrix4`).
///
/// JOML's conventions, which Paper exposes: column-major storage
/// (`element(row, col) == values[col * 4 + row]`); builders post-multiply
/// (`translate` then `scale` is `T * S`); `rotate_xyz` applies X, then Y, then Z.
#[derive(Clone, Copy, Debug, PartialEq, Serialize)]
pub struct Matrix4 {
    pub values: [f64; 16],
}

impl Default for Matrix4 {
    fn default() -> Self {
        Matrix4::IDENTITY
    }
}

impl Matrix4 {
    pub const IDENTITY: Matrix4 = Matrix4 {
        values: [
            1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0,
        ],
    };

    /// Below this a determinant is zero: an axis has collapsed.
    const SINGULAR: f64 = 1e-12;

    /// `self * right`.
    pub fn mul(&self, right: &Matrix4) -> Matrix4 {
        let (a, b) = (&self.values, &right.values);
        let mut out = [0.0; 16];
        for col in 0..4 {
            let (b0, b1, b2, b3) = (b[col * 4], b[col * 4 + 1], b[col * 4 + 2], b[col * 4 + 3]);
            for row in 0..4 {
                out[col * 4 + row] =
                    a[row] * b0 + a[4 + row] * b1 + a[8 + row] * b2 + a[12 + row] * b3;
            }
        }
        Matrix4 { values: out }
    }

    pub fn translate(&mut self, x: f64, y: f64, z: f64) -> &mut Self {
        let v = &mut self.values;
        for row in 0..4 {
            v[12 + row] += v[row] * x + v[4 + row] * y + v[8 + row] * z;
        }
        self
    }

    pub fn scale(&mut self, x: f64, y: f64, z: f64) -> &mut Self {
        let v = &mut self.values;
        for row in 0..4 {
            v[row] *= x;
            v[4 + row] *= y;
            v[8 + row] *= z;
        }
        self
    }

    pub fn rotate_x(&mut self, radians: f64) -> &mut Self {
        let (c, s) = (radians.cos(), radians.sin());
        let v = &mut self.values;
        for row in 0..4 {
            let (col1, col2) = (v[4 + row], v[8 + row]);
            v[4 + row] = col1 * c + col2 * s;
            v[8 + row] = col1 * -s + col2 * c;
        }
        self
    }

    pub fn rotate_y(&mut self, radians: f64) -> &mut Self {
        let (c, s) = (radians.cos(), radians.sin());
        let v = &mut self.values;
        for row in 0..4 {
            let (col0, col2) = (v[row], v[8 + row]);
            v[row] = col0 * c + col2 * -s;
            v[8 + row] = col0 * s + col2 * c;
        }
        self
    }

    pub fn rotate_z(&mut self, radians: f64) -> &mut Self {
        let (c, s) = (radians.cos(), radians.sin());
        let v = &mut self.values;
        for row in 0..4 {
            let (col0, col1) = (v[row], v[4 + row]);
            v[row] = col0 * c + col1 * s;
            v[4 + row] = col0 * -s + col1 * c;
        }
        self
    }

    pub fn rotate_xyz(&mut self, x: f64, y: f64, z: f64) -> &mut Self {
        if x != 0.0 {
            self.rotate_x(x);
        }
        if y != 0.0 {
            self.rotate_y(y);
        }
        if z != 0.0 {
            self.rotate_z(z);
        }
        self
    }

    pub fn transform_position(&self, p: Vec3) -> Vec3 {
        let v = &self.values;
        Vec3(
            v[0] * p.0 + v[4] * p.1 + v[8] * p.2 + v[12],
            v[1] * p.0 + v[5] * p.1 + v[9] * p.2 + v[13],
            v[2] * p.0 + v[6] * p.1 + v[10] * p.2 + v[14],
        )
    }

    pub fn transform_direction(&self, d: Vec3) -> Vec3 {
        let v = &self.values;
        Vec3(
            v[0] * d.0 + v[4] * d.1 + v[8] * d.2,
            v[1] * d.0 + v[5] * d.1 + v[9] * d.2,
            v[2] * d.0 + v[6] * d.1 + v[10] * d.2,
        )
    }

    /// The inverse of this affine matrix, or `None` when an axis has collapsed
    /// to zero scale (a script can do that at any time).
    pub fn invert_affine(&self) -> Option<Matrix4> {
        let v = &self.values;
        let (a, d, g, b, e, h, c, f, i) = (v[0], v[1], v[2], v[4], v[5], v[6], v[8], v[9], v[10]);
        let (tx, ty, tz) = (v[12], v[13], v[14]);
        let det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
        if det > -Self::SINGULAR && det < Self::SINGULAR {
            return None;
        }
        let s = 1.0 / det;
        let i00 = (e * i - f * h) * s;
        let i01 = (c * h - b * i) * s;
        let i02 = (b * f - c * e) * s;
        let i10 = (f * g - d * i) * s;
        let i11 = (a * i - c * g) * s;
        let i12 = (c * d - a * f) * s;
        let i20 = (d * h - e * g) * s;
        let i21 = (b * g - a * h) * s;
        let i22 = (a * e - b * d) * s;
        Some(Matrix4 {
            values: [
                i00,
                i10,
                i20,
                0.0,
                i01,
                i11,
                i21,
                0.0,
                i02,
                i12,
                i22,
                0.0,
                -(i00 * tx + i01 * ty + i02 * tz),
                -(i10 * tx + i11 * ty + i12 * tz),
                -(i20 * tx + i21 * ty + i22 * tz),
                1.0,
            ],
        })
    }

    pub fn translation(&self) -> Vec3 {
        Vec3(self.values[12], self.values[13], self.values[14])
    }

    /// The length of each basis column: the scale, up to any shear a chain introduced.
    pub fn scale_of(&self) -> Vec3 {
        let v = &self.values;
        let length = |o: usize| (v[o] * v[o] + v[o + 1] * v[o + 1] + v[o + 2] * v[o + 2]).sqrt();
        Vec3(length(0), length(4), length(8))
    }

    /// The local matrix of a TRS transform: `T * Rx * Ry * Rz * S`.
    pub fn from_trs(translation: Vec3, rotation_degrees: Vec3, scale: Vec3) -> Matrix4 {
        let mut m = Matrix4::IDENTITY;
        m.translate(translation.0, translation.1, translation.2)
            .rotate_xyz(
                degrees_to_radians(rotation_degrees.0),
                degrees_to_radians(rotation_degrees.1),
                degrees_to_radians(rotation_degrees.2),
            )
            .scale(scale.0, scale.1, scale.2);
        m
    }
}
