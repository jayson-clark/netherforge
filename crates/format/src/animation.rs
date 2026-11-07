//! What a track of keys is made of, shared by centity animations and
//! cutscenes: format's `Keyframe` and `Easing`.

use schemars::JsonSchema;
use serde::{Deserialize, Serialize};

use crate::math::Vec3;

/// One pose on a track; `easing` shapes the segment leaving this key.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct Keyframe {
    pub time: f64,
    pub value: Vec3,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub easing: Option<Easing>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum Easing {
    Linear,
    /// Hold this key's value until the next one.
    Step,
    EaseIn,
    EaseOut,
    EaseInOut,
}

impl Easing {
    /// Reshapes a normalised segment position; 0 maps to 0 and 1 to 1 for every curve but `Step`.
    pub fn apply(self, t: f64) -> f64 {
        match self {
            Easing::Linear => t,
            Easing::Step => 0.0,
            Easing::EaseIn => t * t,
            Easing::EaseOut => 1.0 - (1.0 - t) * (1.0 - t),
            Easing::EaseInOut => {
                if t < 0.5 {
                    2.0 * t * t
                } else {
                    1.0 - 2.0 * (1.0 - t) * (1.0 - t)
                }
            }
        }
    }
}

/// `items` in time order, keeping the order of those at one time.
pub fn by_time<T: Clone>(items: &[T], time: impl Fn(&T) -> f64) -> Vec<T> {
    let mut out = items.to_vec();
    out.sort_by(|a, b| time(a).total_cmp(&time(b)));
    out
}

/// Where a time falls on a track of keys in time order: between keys `from`
/// and `to`, `t` of the way along with `from`'s easing; the same key twice
/// outside the keyed range (or across a segment no time long). Shared by a
/// centity's channels and a cutscene's camera, so they sample alike.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Segment {
    pub from: usize,
    pub to: usize,
    pub t: f64,
}

/// The segment `time` falls in on `keys` (never none), each key's time and
/// easing as `time_of` and `easing_of` give them.
pub fn segment<K>(
    keys: &[K],
    time_of: impl Fn(&K) -> f64,
    easing_of: impl Fn(&K) -> Option<Easing>,
    time: f64,
) -> Segment {
    let last = keys.len() - 1;
    let held = |i| Segment {
        from: i,
        to: i,
        t: 0.0,
    };
    if time <= time_of(&keys[0]) {
        return held(0);
    }
    if time >= time_of(&keys[last]) {
        return held(last);
    }
    let next = keys.partition_point(|k| time_of(k) <= time);
    let (from, to) = (&keys[next - 1], &keys[next]);
    let span = time_of(to) - time_of(from);
    if span <= 0.0 {
        return held(next);
    }
    Segment {
        from: next - 1,
        to: next,
        t: easing_of(from)
            .unwrap_or(Easing::Linear)
            .apply((time - time_of(from)) / span),
    }
}
