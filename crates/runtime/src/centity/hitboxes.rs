//! Where a hitbox is clickable (Kotlin's `Hitboxes`), given that Minecraft's interaction entities are
//! axis-aligned boxes with a square footprint and no transform of their own.
//!
//! `bounds` is the broad phase the server can spawn: the extent of the node's boxes after its world
//! transform, widened to a square. A rotated or oblong box gets padding that's clickable too. `hit` is the
//! narrow phase: the player's ray is pushed through the node's inverse world matrix and meets the boxes in
//! node space, where they're still axis-aligned at their authored size. That handles rotation, non-uniform
//! scale and parent shear exactly. Because the inverse is linear, distances come back in world units and
//! compare across nodes.
//!
//! All positions are relative to the instance's anchor, the frame its matrices compose into.

use netherforge_format::math::{BoundingBox, Matrix4, Vec3};

/// What a hitbox with no boxes covers: the unit cube a block display fills.
pub const UNIT_CUBE: BoundingBox = BoundingBox {
    min: Vec3(0.0, 0.0, 0.0),
    max: Vec3(1.0, 1.0, 1.0),
};

/// The sliver a degenerate box stays clickable at, on the server and in the ray test alike.
pub const MIN_SIZE: f64 = 0.01;

/// How near the anchor, in blocks, a hitbox counts as standing at it.
pub const AT_ANCHOR: f64 = 1e-6;

const PARALLEL: f64 = 1e-12;

/// An interaction entity's box: its bottom centre, relative to the anchor, its width and its height.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Bounds {
    pub center_x: f64,
    pub bottom_y: f64,
    pub center_z: f64,
    pub width: f64,
    pub height: f64,
}

impl Bounds {
    /// A sliver at `at`: what a hidden or unclickable hitbox collapses to, so clicks pass it by.
    pub fn collapsed(at: Vec3) -> Bounds {
        Bounds {
            center_x: at.0,
            bottom_y: at.1,
            center_z: at.2,
            width: MIN_SIZE,
            height: MIN_SIZE,
        }
    }

    pub fn as_box(&self) -> BoundingBox {
        let half = self.width / 2.0;
        BoundingBox {
            min: Vec3(self.center_x - half, self.bottom_y, self.center_z - half),
            max: Vec3(
                self.center_x + half,
                self.bottom_y + self.height,
                self.center_z + half,
            ),
        }
    }

    /// Whether it stands right at the anchor (centred on it, its bottom at its height): where an interaction
    /// entity riding the anchor entity is.
    pub fn at_anchor(&self) -> bool {
        self.center_x.abs() < AT_ANCHOR
            && self.bottom_y.abs() < AT_ANCHOR
            && self.center_z.abs() < AT_ANCHOR
    }
}

/// The square-footed box around `boxes` once `world` has placed them.
pub fn bounds(boxes: &[BoundingBox], world: &Matrix4) -> Bounds {
    let (mut min, mut max) = ([f64::MAX; 3], [-f64::MAX; 3]);
    for b in boxes {
        for corner in 0..8 {
            let p = world.transform_position(Vec3(
                if corner & 1 == 0 { b.min.0 } else { b.max.0 },
                if corner & 2 == 0 { b.min.1 } else { b.max.1 },
                if corner & 4 == 0 { b.min.2 } else { b.max.2 },
            ));
            for (axis, value) in p.coordinates().into_iter().enumerate() {
                min[axis] = min[axis].min(value);
                max[axis] = max[axis].max(value);
            }
        }
    }
    if min[0] > max[0] {
        return Bounds::collapsed(Vec3::ZERO);
    }
    Bounds {
        center_x: (min[0] + max[0]) / 2.0,
        bottom_y: min[1],
        center_z: (min[2] + max[2]) / 2.0,
        width: (max[0] - min[0]).max(max[2] - min[2]).max(MIN_SIZE),
        height: (max[1] - min[1]).max(MIN_SIZE),
    }
}

/// Where a ray meets a hitbox: how far along it (in units of the ray's direction), and the unit normal of
/// the face it entered through, in the frame the ray was given in. A ray starting inside hits at 0, and its
/// normal points back along the ray.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Hit {
    pub distance: f64,
    pub normal: Vec3,
}

/// The nearest of `boxes` the ray hits within `max_distance`, with the face's normal turned out of node
/// space by `world`.
pub fn hit(
    boxes: &[BoundingBox],
    world: &Matrix4,
    origin: Vec3,
    direction: Vec3,
    max_distance: f64,
) -> Option<Hit> {
    // A node scaled to zero on some axis has no inverse, and nothing to hit.
    let inverse = world.invert_affine()?;
    let local_origin = inverse.transform_position(origin);
    let local_direction = inverse.transform_direction(direction);
    let nearest = boxes
        .iter()
        .filter_map(|b| slab(b, local_origin, local_direction, max_distance))
        .min_by(|a, b| a.distance.total_cmp(&b.distance))?;
    // Normals turn by the inverse transpose, so a stretched box's faces stay perpendicular.
    let Vec3(x, y, z) = nearest.normal;
    let v = &inverse.values;
    let turned = Vec3(
        v[0] * x + v[1] * y + v[2] * z,
        v[4] * x + v[5] * y + v[6] * z,
        v[8] * x + v[9] * y + v[10] * z,
    );
    Some(Hit {
        distance: nearest.distance,
        normal: unit(turned)
            .or_else(|| unit(direction * -1.0))
            .unwrap_or(Vec3(0.0, 1.0, 0.0)),
    })
}

/// The same against the interaction entity itself, for a node that doesn't ask for the narrow phase.
pub fn hit_bounds(
    bounds: &Bounds,
    origin: Vec3,
    direction: Vec3,
    max_distance: f64,
) -> Option<Hit> {
    slab(&bounds.as_box(), origin, direction, max_distance)
}

fn unit(v: Vec3) -> Option<Vec3> {
    let length = (v.0 * v.0 + v.1 * v.1 + v.2 * v.2).sqrt();
    (length >= PARALLEL).then(|| Vec3(v.0 / length, v.1 / length, v.2 / length))
}

fn slab(b: &BoundingBox, origin: Vec3, direction: Vec3, max_distance: f64) -> Option<Hit> {
    let mut enter = 0.0f64;
    let mut exit = max_distance;
    // The face entered through: the axis whose entry was last, against the ray.
    let mut normal = unit(direction * -1.0).unwrap_or(Vec3(0.0, 1.0, 0.0));
    let (from, along, low, high) = (
        origin.coordinates(),
        direction.coordinates(),
        b.min.coordinates(),
        b.max.coordinates(),
    );
    for axis in 0..3 {
        let (low, high) = (low[axis], high[axis].max(low[axis] + MIN_SIZE));
        if along[axis].abs() < PARALLEL {
            if from[axis] < low || from[axis] > high {
                return None;
            }
            continue;
        }
        let first = (low - from[axis]) / along[axis];
        let second = (high - from[axis]) / along[axis];
        let near = first.min(second);
        if near > enter {
            enter = near;
            let sign = if along[axis] > 0.0 { -1.0 } else { 1.0 };
            normal = match axis {
                0 => Vec3(sign, 0.0, 0.0),
                1 => Vec3(0.0, sign, 0.0),
                _ => Vec3(0.0, 0.0, sign),
            };
        }
        exit = exit.min(first.max(second));
        if enter > exit {
            return None;
        }
    }
    Some(Hit {
        distance: enter,
        normal,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_turned_box_is_hit_where_it_is_and_its_bounds_are_square() {
        let mut world = Matrix4::IDENTITY;
        world.translate(0.0, 1.0, 0.0);
        world.rotate_y(std::f64::consts::FRAC_PI_4);
        let boxes = [BoundingBox {
            min: Vec3(-0.5, 0.0, -0.1),
            max: Vec3(0.5, 1.0, 0.1),
        }];
        let b = bounds(&boxes, &world);
        // Its turned corners reach out to a square wider than it is long.
        assert!(b.width > 0.8, "{b:?}");
        assert!((b.bottom_y - 1.0).abs() < 1e-12);
        // Straight down the middle from above: hits its top face, 1 block down.
        let hit = hit(
            &boxes,
            &world,
            Vec3(0.0, 3.0, 0.0),
            Vec3(0.0, -1.0, 0.0),
            8.0,
        )
        .expect("a hit");
        assert!((hit.distance - 1.0).abs() < 1e-9, "{hit:?}");
        assert!((hit.normal.1 - 1.0).abs() < 1e-9, "{hit:?}");
        // Down each diagonal: one runs along the thin box, the other through padding only the square bounds
        // have.
        let down = Vec3(0.0, -1.0, 0.0);
        let diagonals = [Vec3(0.3, 3.0, 0.3), Vec3(0.3, 3.0, -0.3)];
        let narrow = diagonals
            .iter()
            .filter(|at| super::hit(&boxes, &world, **at, down, 8.0).is_some())
            .count();
        assert_eq!(narrow, 1);
        assert!(
            diagonals
                .iter()
                .all(|at| hit_bounds(&b, *at, down, 8.0).is_some())
        );
    }
}
