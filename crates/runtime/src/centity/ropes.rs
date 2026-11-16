//! Laying out ropes and stretched nodes (Kotlin's `Ropes`): the shapes, apart from what the ends are, so they
//! can be worked out and tested on their own.

use netherforge_format::math::{Matrix4, Vec3};
use netherforge_platform::Uuid;

use super::Instance;
use super::instance::Channel;
use super::quat::look_rotation;

/// One end of a strung rope: a point, a node of a centity (its origin, as shown), or an entity.
#[derive(Clone, Debug, PartialEq)]
pub enum RopeEnd {
    Point(Vec3),
    OfNode { centity: Uuid, node: String },
    OfEntity(Uuid),
}

/// A rope node strung between two ends, kept laid each tick (`node:rope_between`); `length` none is always
/// taut.
#[derive(Clone, Debug, PartialEq)]
pub struct StrungRope {
    pub from: RopeEnd,
    pub to: RopeEnd,
    pub length: Option<f64>,
}

/// How high above an entity's feet a rope tied to it hangs: a player's hands, another's middle.
pub const PLAYER_HANDS: f64 = 1.1;
pub const ENTITY_MIDDLE: f64 = 0.5;

/// The least a stretched node's Z scale gets, so a node laid between two points at one place doesn't vanish
/// into a plane.
const MIN_LENGTH: f64 = 0.01;

/// How far a rope `length` long hangs below the straight line at its middle, between ends `distance` apart:
/// none when it's taut, and as a string pinched in the middle would hang when it isn't.
pub fn sag(length: Option<f64>, distance: f64) -> f64 {
    match length {
        Some(length) if distance < length => 0.5 * (length * length - distance * distance).sqrt(),
        _ => 0.0,
    }
}

/// `count + 1` points from `a` to `b` along a curve sagging `sag` blocks at its middle: a quadratic curve
/// whose control point is twice the sag below the halfway point.
pub fn points(a: Vec3, b: Vec3, sag: f64, count: usize) -> Vec<Vec3> {
    let control = Vec3(
        (a.0 + b.0) * 0.5,
        (a.1 + b.1) * 0.5 - 2.0 * sag,
        (a.2 + b.2) * 0.5,
    );
    (0..=count)
        .map(|i| {
            let t = i as f64 / count as f64;
            let u = 1.0 - t;
            a * (u * u) + control * (2.0 * u * t) + b * (t * t)
        })
        .collect()
}

/// How many of a rope's `links` links, each `link_length` long, its ends `distance` apart need.
pub fn shown(links: usize, link_length: f64, length: Option<f64>, distance: f64) -> usize {
    let needed = (distance.max(length.unwrap_or(distance)) / link_length - 1e-9).ceil();
    (needed.max(1.0) as usize).clamp(1, links.max(1))
}

/// Lays node `index` from world point `from` to `to`: its origin halfway, its Z along the way, its Z scale
/// the distance; X and Y scale `thickness`, or as they are. `parent_inverse` is the inverse of its parent's
/// world matrix (none works it out). False when its parent's space can't be inverted.
pub fn stretch(
    instance: &mut Instance,
    index: usize,
    from: Vec3,
    to: Vec3,
    thickness: Option<f64>,
    parent_inverse: Option<Matrix4>,
) -> bool {
    let Some(inverse) =
        parent_inverse.or_else(|| instance.parent_world_matrix(index).invert_affine())
    else {
        return false;
    };
    let a = inverse.transform_position(from);
    let b = inverse.transform_position(to);
    let along = b - a;
    let distance = along.length();
    instance.set(index, Channel::Translation, (a + b) * 0.5, false);
    if distance > 1e-9 {
        instance.set(
            index,
            Channel::Rotation,
            look_rotation(along * (1.0 / distance)),
            false,
        );
    }
    let scale = instance.get(index, Channel::Scale);
    instance.set(
        index,
        Channel::Scale,
        Vec3(
            thickness.unwrap_or(scale.0),
            thickness.unwrap_or(scale.1),
            distance.max(MIN_LENGTH),
        ),
        false,
    );
    true
}

/// Lays rope node `rope` between world points `from` and `to`, `length` long (none: taut).
pub fn lay(instance: &mut Instance, rope: usize, from: Vec3, to: Vec3, length: Option<f64>) {
    let Some(def) = instance.definition.nodes[rope].rope.clone() else {
        return;
    };
    let Some(inverse) = instance.world_matrix(rope).invert_affine() else {
        return;
    };
    let distance = (to - from).length();
    let count = shown(def.links.len(), def.link_length, length, distance);
    let points = points(from, to, sag(length, distance), count);
    for (i, link) in def.links.iter().enumerate() {
        let shown = i < count;
        if shown {
            stretch(
                instance,
                *link,
                points[i],
                points[i + 1],
                None,
                Some(inverse),
            );
        }
        if instance.visible(*link) != shown {
            instance.set_visible(*link, shown);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_slack_rope_sags_and_a_taut_one_doesnt() {
        assert_eq!(sag(None, 4.0), 0.0);
        assert_eq!(sag(Some(3.0), 4.0), 0.0);
        assert!((sag(Some(5.0), 4.0) - 1.5).abs() < 1e-12);
        let p = points(Vec3::ZERO, Vec3(4.0, 0.0, 0.0), 1.5, 2);
        assert!((p[1].1 + 1.5).abs() < 1e-12, "{p:?}");
        assert_eq!(shown(10, 0.5, Some(5.0), 4.0), 10);
        assert_eq!(shown(10, 1.0, None, 2.5), 3);
    }
}
