//! Clips as keyframes the client eases between, rather than a pose every tick (Kotlin's `Keyframes`).
//!
//! A display eases to each new transformation over its interpolation duration on the client: translation
//! and scale in a straight line, rotation along the shortest arc. So while only clips move a node, sync can
//! look ahead, send the pose some ticks on with that many ticks to get there, and send nothing more until it
//! arrives, as long as the client's straight line passes within `TRANSLATION_TOLERANCE` of where the clip
//! really puts the node at every tick between. `reach` finds how far that is: the length of a linear
//! segment (to `MAX_TICKS`), a few ticks along an eased one, one tick across a step or anything the client
//! couldn't follow.
//!
//! The model of the client: Paper decomposes the matrix it's given into translation, rotation, scale and a
//! second rotation, and the client eases each. For a matrix with no shear whose scale is uniform, or the
//! same at both ends, that second rotation is the same at both ends, so easing is exactly `between`.
//! Anything else (shear from a parent's uneven scale, a mirror, a collapsed node, an uneven scale that
//! changes) isn't planned.

use netherforge_format::math::Matrix4;

/// The furthest ahead a move is planned: a second.
pub const MAX_TICKS: i32 = 20;

/// How far off the client may be, in blocks, at any tick of a planned move.
pub const TRANSLATION_TOLERANCE: f64 = 0.01;

/// How far off each of a matrix's axes may be, per block of the node's scale.
pub const AXIS_TOLERANCE: f64 = 0.01;

const EPSILON: f64 = 1e-6;

/// A matrix as the client eases it: translation, rotation (x, y, z, w) and scale.
#[derive(Clone, Copy, Debug)]
pub struct Parts {
    pub translation: [f64; 3],
    pub rotation: [f64; 4],
    pub scale: [f64; 3],
}

/// How many ticks ahead a display can be sent from `now`: the most `h` (at least 1, at most `max`) for which
/// easing from `now` to `ahead(h)` over `h` ticks passes `ahead(k)` at each tick `k` between. `ahead` is the
/// node's matrix that many ticks on, asked for in order and no further than needed.
pub fn reach(now: &Matrix4, max: i32, ahead: &mut dyn FnMut(i32) -> Matrix4) -> i32 {
    let start = decompose(now);
    let mut best = 1;
    for h in 2..=max {
        if !follows(now, start.as_ref(), ahead, h) {
            break;
        }
        best = h;
    }
    best
}

fn follows(
    now: &Matrix4,
    start: Option<&Parts>,
    ahead: &mut dyn FnMut(i32) -> Matrix4,
    h: i32,
) -> bool {
    let target = ahead(h);
    if now.values == target.values {
        return (1..h).all(|k| now.values == ahead(k).values);
    }
    let Some(end) = decompose(&target) else {
        return false;
    };
    let Some(start) = start else {
        return false;
    };
    if !easable(start, &end) {
        return false;
    }
    let tolerance = AXIS_TOLERANCE * 1f64.max(largest(&start.scale).max(largest(&end.scale)));
    (1..h).all(|k| {
        let predicted = between(start, &end, f64::from(k) / f64::from(h));
        close(&predicted, &ahead(k), tolerance)
    })
}

/// A scale the client eases the way `between` does: the same at both ends, or even at both.
fn easable(a: &Parts, b: &Parts) -> bool {
    same_scale(&a.scale, &b.scale) || (uniform(&a.scale) && uniform(&b.scale))
}

fn same_scale(a: &[f64; 3], b: &[f64; 3]) -> bool {
    (0..3).all(|i| (a[i] - b[i]).abs() <= EPSILON * 1f64.max(a[i].abs()))
}

fn uniform(s: &[f64; 3]) -> bool {
    (s[0] - s[1]).abs() <= EPSILON * s[0] && (s[0] - s[2]).abs() <= EPSILON * s[0]
}

fn largest(s: &[f64; 3]) -> f64 {
    s[0].max(s[1].max(s[2]))
}

fn close(a: &Matrix4, b: &Matrix4, axis: f64) -> bool {
    for column in 0..3 {
        for row in 0..3 {
            if (a.values[column * 4 + row] - b.values[column * 4 + row]).abs() > axis {
                return false;
            }
        }
    }
    (0..3).all(|row| (a.values[12 + row] - b.values[12 + row]).abs() <= TRANSLATION_TOLERANCE)
}

/// `matrix` as translation, rotation and scale, or none when the client wouldn't ease it as such: a
/// collapsed axis, shear or a mirror.
pub fn decompose(matrix: &Matrix4) -> Option<Parts> {
    let v = &matrix.values;
    let scale: [f64; 3] = std::array::from_fn(|column| {
        let at = column * 4;
        (v[at] * v[at] + v[at + 1] * v[at + 1] + v[at + 2] * v[at + 2]).sqrt()
    });
    if scale.iter().any(|s| *s < EPSILON) {
        return None;
    }
    // The rotation's columns: the axes, each its own length.
    let r: [[f64; 3]; 3] = std::array::from_fn(|column| {
        std::array::from_fn(|row| v[column * 4 + row] / scale[column])
    });
    let dot = |a: &[f64; 3], b: &[f64; 3]| a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    if dot(&r[0], &r[1]).abs() > 1e-4
        || dot(&r[0], &r[2]).abs() > 1e-4
        || dot(&r[1], &r[2]).abs() > 1e-4
    {
        return None;
    }
    let determinant = r[0][0] * (r[1][1] * r[2][2] - r[2][1] * r[1][2])
        - r[1][0] * (r[0][1] * r[2][2] - r[2][1] * r[0][2])
        + r[2][0] * (r[0][1] * r[1][2] - r[1][1] * r[0][2]);
    if determinant < 0.0 {
        return None;
    }
    Some(Parts {
        translation: [v[12], v[13], v[14]],
        rotation: quaternion(&r),
        scale,
    })
}

/// The rotation whose matrix has columns `c`, as a unit quaternion (x, y, z, w).
fn quaternion(c: &[[f64; 3]; 3]) -> [f64; 4] {
    let (m00, m11, m22) = (c[0][0], c[1][1], c[2][2]);
    let trace = m00 + m11 + m22;
    let mut q = if trace > 0.0 {
        let s = (trace + 1.0).sqrt() * 2.0;
        [
            (c[1][2] - c[2][1]) / s,
            (c[2][0] - c[0][2]) / s,
            (c[0][1] - c[1][0]) / s,
            0.25 * s,
        ]
    } else if m00 > m11 && m00 > m22 {
        let s = (1.0 + m00 - m11 - m22).sqrt() * 2.0;
        [
            0.25 * s,
            (c[1][0] + c[0][1]) / s,
            (c[2][0] + c[0][2]) / s,
            (c[1][2] - c[2][1]) / s,
        ]
    } else if m11 > m22 {
        let s = (1.0 + m11 - m00 - m22).sqrt() * 2.0;
        [
            (c[1][0] + c[0][1]) / s,
            0.25 * s,
            (c[2][1] + c[1][2]) / s,
            (c[2][0] - c[0][2]) / s,
        ]
    } else {
        let s = (1.0 + m22 - m00 - m11).sqrt() * 2.0;
        [
            (c[2][0] + c[0][2]) / s,
            (c[2][1] + c[1][2]) / s,
            0.25 * s,
            (c[0][1] - c[1][0]) / s,
        ]
    };
    normalize(&mut q);
    q
}

fn normalize(q: &mut [f64; 4]) {
    let length = (q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]).sqrt();
    for x in q.iter_mut() {
        *x /= length;
    }
}

/// What the client shows `t` of the way from `a` to `b`.
pub fn between(a: &Parts, b: &Parts, t: f64) -> Matrix4 {
    let [x, y, z, w] = slerp(&a.rotation, &b.rotation, t);
    let s: [f64; 3] = std::array::from_fn(|i| a.scale[i] + (b.scale[i] - a.scale[i]) * t);
    // The rotation's columns, each times its scale.
    let columns = [
        [
            1.0 - 2.0 * (y * y + z * z),
            2.0 * (x * y + w * z),
            2.0 * (x * z - w * y),
        ],
        [
            2.0 * (x * y - w * z),
            1.0 - 2.0 * (x * x + z * z),
            2.0 * (y * z + w * x),
        ],
        [
            2.0 * (x * z + w * y),
            2.0 * (y * z - w * x),
            1.0 - 2.0 * (x * x + y * y),
        ],
    ];
    let mut out = Matrix4::IDENTITY;
    let v = &mut out.values;
    for column in 0..3 {
        for row in 0..3 {
            v[column * 4 + row] = columns[column][row] * s[column];
        }
        v[column * 4 + 3] = 0.0;
    }
    for row in 0..3 {
        v[12 + row] = a.translation[row] + (b.translation[row] - a.translation[row]) * t;
    }
    v[15] = 1.0;
    out
}

/// Along the shortest arc, as the client's quaternions go.
fn slerp(a: &[f64; 4], b: &[f64; 4], t: f64) -> [f64; 4] {
    let mut cos = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3];
    let sign = if cos < 0.0 { -1.0 } else { 1.0 };
    cos *= sign;
    let (wa, wb) = if 1.0 - cos > 1e-6 {
        let angle = cos.min(1.0).acos();
        let s = angle.sin();
        (((1.0 - t) * angle).sin() / s, (t * angle).sin() / s)
    } else {
        (1.0 - t, t)
    };
    let mut q: [f64; 4] = std::array::from_fn(|i| wa * a[i] + wb * sign * b[i]);
    normalize(&mut q);
    q
}

#[cfg(test)]
mod tests {
    use super::*;

    fn moved(x: f64) -> Matrix4 {
        let mut m = Matrix4::IDENTITY;
        m.translate(x, 0.0, 0.0);
        m
    }

    #[test]
    fn a_straight_move_is_planned_to_the_limit_and_a_step_not_at_all() {
        assert_eq!(
            reach(&moved(0.0), MAX_TICKS, &mut |k| moved(f64::from(k) * 0.1)),
            MAX_TICKS
        );
        // Eased: the straight line drifts off the curve after a few ticks (six, for this one).
        let eased = reach(&moved(0.0), MAX_TICKS, &mut |k| {
            moved(f64::from(k).powi(2) * 0.001)
        });
        assert_eq!(eased, 6);
        // A jump at tick 2: only one tick.
        assert_eq!(
            reach(&moved(0.0), MAX_TICKS, &mut |k| moved(if k < 2 {
                0.0
            } else {
                5.0
            })),
            1
        );
    }

    #[test]
    fn a_matrix_decomposes_and_eases_back_to_itself() {
        let mut m = Matrix4::IDENTITY;
        m.translate(1.0, 2.0, 3.0);
        m.rotate_y(0.7);
        m.scale(2.0, 2.0, 2.0);
        let parts = decompose(&m).expect("easable");
        let back = between(&parts, &parts, 0.5);
        for i in 0..16 {
            assert!((back.values[i] - m.values[i]).abs() < 1e-9, "{i}");
        }
        let mut mirrored = Matrix4::IDENTITY;
        mirrored.scale(-1.0, 1.0, 1.0);
        assert!(decompose(&mirrored).is_none());
    }
}
