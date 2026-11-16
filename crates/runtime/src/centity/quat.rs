//! A unit quaternion, for orientation that is being turned rather than authored (Kotlin's `Quat`).
//!
//! Files spell rotation as XYZ euler degrees because a builder can read them; euler angles gimbal lock and
//! don't compose, so a turn is worked out here and converted back to euler on the way out. The euler
//! convention matches `Matrix4::rotate_xyz` exactly, `R = Rx · Ry · Rz`.

use netherforge_format::math::{Matrix4, Vec3, degrees_to_radians, radians_to_degrees};

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Quat {
    pub x: f64,
    pub y: f64,
    pub z: f64,
    pub w: f64,
}

const POLE: f64 = 0.99999;

impl std::ops::Mul for Quat {
    type Output = Quat;
    /// `self * other`: the rotation `other` followed by the rotation `self`.
    fn mul(self, o: Quat) -> Quat {
        let Quat { x, y, z, w } = self;
        Quat {
            x: w * o.x + x * o.w + y * o.z - z * o.y,
            y: w * o.y - x * o.z + y * o.w + z * o.x,
            z: w * o.z + x * o.y - y * o.x + z * o.w,
            w: w * o.w - x * o.x - y * o.y - z * o.z,
        }
    }
}

impl Quat {
    pub const IDENTITY: Quat = Quat {
        x: 0.0,
        y: 0.0,
        z: 0.0,
        w: 1.0,
    };

    /// The inverse rotation, which for a unit quaternion is its conjugate.
    pub fn conjugate(self) -> Quat {
        Quat {
            x: -self.x,
            y: -self.y,
            z: -self.z,
            w: self.w,
        }
    }

    /// Rotates `v` via `v + 2w(q × v) + 2(q × (q × v))`.
    pub fn rotate(self, v: Vec3) -> Vec3 {
        let Quat { x, y, z, w } = self;
        let (cx, cy, cz) = (y * v.2 - z * v.1, z * v.0 - x * v.2, x * v.1 - y * v.0);
        let (ccx, ccy, ccz) = (y * cz - z * cy, z * cx - x * cz, x * cy - y * cx);
        Vec3(
            v.0 + 2.0 * (w * cx + ccx),
            v.1 + 2.0 * (w * cy + ccy),
            v.2 + 2.0 * (w * cz + ccz),
        )
    }

    /// A world direction expressed in the body's frame.
    pub fn inverse_rotate(self, v: Vec3) -> Vec3 {
        self.conjugate().rotate(v)
    }

    pub fn normalized(self) -> Quat {
        let length = (self.x * self.x + self.y * self.y + self.z * self.z + self.w * self.w).sqrt();
        if length < 1e-12 {
            return Quat::IDENTITY;
        }
        let s = 1.0 / length;
        Quat {
            x: self.x * s,
            y: self.y * s,
            z: self.z * s,
            w: self.w * s,
        }
    }

    /// This orientation after spinning at `angular_velocity` radians per second for `seconds` (the
    /// exponential map, which doesn't shrink a big turn).
    pub fn integrate(self, angular_velocity: Vec3, seconds: f64) -> Quat {
        let Vec3(ax, ay, az) = angular_velocity;
        let speed = (ax * ax + ay * ay + az * az).sqrt();
        if speed < 1e-8 {
            return self;
        }
        let angle = speed * seconds * 0.5;
        let scale = angle.sin() / speed;
        let spin = Quat {
            x: ax * scale,
            y: ay * scale,
            z: az * scale,
            w: angle.cos(),
        };
        (spin * self).normalized()
    }

    /// XYZ euler angles in degrees, as `Matrix4::rotate_xyz` reads them. Lossy at a pitch of ±90°.
    pub fn to_euler(self) -> Vec3 {
        let Quat { x, y, z, w } = self;
        let m00 = 1.0 - 2.0 * (y * y + z * z);
        let m01 = 2.0 * (x * y - z * w);
        let m02 = 2.0 * (x * z + y * w);
        let m10 = 2.0 * (x * y + z * w);
        let m11 = 1.0 - 2.0 * (x * x + z * z);
        let m12 = 2.0 * (y * z - x * w);
        let m22 = 1.0 - 2.0 * (x * x + y * y);
        let sin_y = m02.clamp(-1.0, 1.0);
        let angle_y = sin_y.asin();
        // At the pole Z takes zero and X the whole merged rotation.
        if sin_y.abs() > POLE {
            return Vec3(
                radians_to_degrees((sin_y * m10).atan2(m11)),
                radians_to_degrees(angle_y),
                0.0,
            );
        }
        Vec3(
            radians_to_degrees((-m12).atan2(m22)),
            radians_to_degrees(angle_y),
            radians_to_degrees((-m01).atan2(m00)),
        )
    }

    /// A turn of `radians` about +Y, by the right-hand rule (as `Matrix4::rotate_y`).
    pub fn about_y(radians: f64) -> Quat {
        Quat {
            x: 0.0,
            y: (radians * 0.5).sin(),
            z: 0.0,
            w: (radians * 0.5).cos(),
        }
    }

    /// A turn of `degrees` about `axis` (not zero).
    pub fn about(axis: Vec3, degrees: f64) -> Quat {
        let length = (axis.0 * axis.0 + axis.1 * axis.1 + axis.2 * axis.2).sqrt();
        let half = degrees_to_radians(degrees) / 2.0;
        let k = half.sin() / length;
        Quat {
            x: axis.0 * k,
            y: axis.1 * k,
            z: axis.2 * k,
            w: half.cos(),
        }
    }

    /// From XYZ euler degrees, `R = Rx · Ry · Rz`.
    pub fn from_euler(degrees: Vec3) -> Quat {
        let (hx, hy, hz) = (
            degrees_to_radians(degrees.0) * 0.5,
            degrees_to_radians(degrees.1) * 0.5,
            degrees_to_radians(degrees.2) * 0.5,
        );
        let qx = Quat {
            x: hx.sin(),
            y: 0.0,
            z: 0.0,
            w: hx.cos(),
        };
        let qy = Quat {
            x: 0.0,
            y: hy.sin(),
            z: 0.0,
            w: hy.cos(),
        };
        let qz = Quat {
            x: 0.0,
            y: 0.0,
            z: hz.sin(),
            w: hz.cos(),
        };
        qx * qy * qz
    }

    /// The rotation part of an affine matrix, with any scale divided out. A basis that isn't a rotation
    /// (uneven scale or shear from a parent chain) is orthonormalised into the nearest one.
    pub fn from_basis(matrix: &Matrix4) -> Quat {
        let v = &matrix.values;
        let (mut ax, mut ay, mut az) = (v[0], v[1], v[2]);
        let mut length = (ax * ax + ay * ay + az * az).sqrt();
        if length < 1e-9 {
            return Quat::IDENTITY;
        }
        ax /= length;
        ay /= length;
        az /= length;
        let (mut bx, mut by, mut bz) = (v[4], v[5], v[6]);
        let dot = bx * ax + by * ay + bz * az;
        bx -= ax * dot;
        by -= ay * dot;
        bz -= az * dot;
        length = (bx * bx + by * by + bz * bz).sqrt();
        if length < 1e-9 {
            return Quat::IDENTITY;
        }
        bx /= length;
        by /= length;
        bz /= length;
        // The third column makes the frame right-handed, which drops a reflection too.
        let (cx, cy, cz) = (ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx);
        from_columns([ax, ay, az], [bx, by, bz], [cx, cy, cz])
    }
}

/// The branch-on-trace conversion, on an orthonormal basis given as its columns.
fn from_columns(c0: [f64; 3], c1: [f64; 3], c2: [f64; 3]) -> Quat {
    let (m00, m10, m20) = (c0[0], c0[1], c0[2]);
    let (m01, m11, m21) = (c1[0], c1[1], c1[2]);
    let (m02, m12, m22) = (c2[0], c2[1], c2[2]);
    let trace = m00 + m11 + m22;
    let q = |x, y, z, w| Quat { x, y, z, w }.normalized();
    if trace > 0.0 {
        let s = (trace + 1.0).sqrt() * 2.0;
        return q((m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s, 0.25 * s);
    }
    if m00 > m11 && m00 > m22 {
        let s = (1.0 + m00 - m11 - m22).sqrt() * 2.0;
        return q(0.25 * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s);
    }
    if m11 > m22 {
        let s = (1.0 + m11 - m00 - m22).sqrt() * 2.0;
        return q((m01 + m10) / s, 0.25 * s, (m12 + m21) / s, (m02 - m20) / s);
    }
    let s = (1.0 + m22 - m00 - m11).sqrt() * 2.0;
    q((m02 + m20) / s, (m12 + m21) / s, 0.25 * s, (m10 - m01) / s)
}

/// The XYZ euler rotation (degrees) that points +Z along `forward` (a unit vector) with +X level. Straight
/// up or down, where "level" means nothing, +X stays east.
pub fn look_rotation(forward: Vec3) -> Vec3 {
    let cross = |a: Vec3, b: Vec3| {
        Vec3(
            a.1 * b.2 - a.2 * b.1,
            a.2 * b.0 - a.0 * b.2,
            a.0 * b.1 - a.1 * b.0,
        )
    };
    let flat = cross(Vec3(0.0, 1.0, 0.0), forward);
    let length = (flat.0 * flat.0 + flat.1 * flat.1 + flat.2 * flat.2).sqrt();
    let right = if length < 1e-9 {
        Vec3(1.0, 0.0, 0.0)
    } else {
        Vec3(flat.0 / length, flat.1 / length, flat.2 / length)
    };
    let up = cross(forward, right);
    let mut basis = Matrix4::IDENTITY;
    let v = &mut basis.values;
    v[0] = right.0;
    v[1] = right.1;
    v[2] = right.2;
    v[4] = up.0;
    v[5] = up.1;
    v[6] = up.2;
    v[8] = forward.0;
    v[9] = forward.1;
    v[10] = forward.2;
    Quat::from_basis(&basis).to_euler()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn euler_round_trips_through_a_matrix() {
        let euler = Vec3(30.0, -40.0, 75.0);
        let m = Matrix4::from_trs(Vec3::ZERO, euler, Vec3::ONE);
        let back = Quat::from_basis(&m).to_euler();
        for (a, b) in euler.coordinates().into_iter().zip(back.coordinates()) {
            assert!((a - b).abs() < 1e-9, "{euler:?} {back:?}");
        }
        assert!((Quat::from_euler(euler).to_euler().1 - euler.1).abs() < 1e-9);
        // Looking east (+X) is a quarter turn about Y.
        let east = look_rotation(Vec3(1.0, 0.0, 0.0));
        assert!((east.1 - 90.0).abs() < 1e-9, "{east:?}");
    }
}
