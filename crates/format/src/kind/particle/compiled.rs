//! An effect ready to sample (format's `ParticleEffectCompiler`): ids
//! namespaced, colours as `0xRRGGBB`, directions of unit length, curves as
//! keys in tick order, block states parsed, and each emitter's data kind
//! decided. Emitters in name order; what an emitter leaves out is its fields'
//! schema defaults.

use serde::{Deserialize, Serialize};

use super::{
    ColorKey, Distribution, EmitterDef, EmitterShape, Motion, NumberKey, ParticleEffectFile,
};
use crate::animation::Easing;
use crate::block_state::BlockState;
use crate::game::{self, GameData, ParticleData};
use crate::item::ItemDef;
use crate::math::{Matrix4, Vec3};

#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct CompiledEffect {
    pub id: String,
    pub duration: i32,
    #[serde(rename = "loop")]
    pub looped: bool,
    pub emitters: Vec<CompiledEmitter>,
}

#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CompiledEmitter {
    pub name: String,
    /// Namespaced.
    pub particle: String,
    /// What a spawn carries: always a kind effects can play.
    pub kind: ParticleData,
    pub start: i32,
    /// Exclusive.
    pub end: i32,
    pub burst: Option<i32>,
    pub every: Option<i32>,
    /// The rate's constant, or none for a burst emitter. A rate curve wins over it.
    pub rate: Option<f64>,
    pub shape: EmitterShape,
    pub distribution: Distribution,
    pub spin: f64,
    pub offset: Vec3,
    /// The emitter's rotation as a matrix: turns points and directions.
    pub rotation: Matrix4,
    pub motion: Motion,
    /// Of unit length, with `motion: "direction"`.
    pub direction: Option<Vec3>,
    pub count: i32,
    pub spread: Vec3,
    pub force: bool,
    pub to_color: i32,
    pub block_state: Option<BlockState>,
    pub item: Option<ItemDef>,
    pub rate_curve: Option<NumberCurve>,
    /// A constant's curve is one key: every property is sampled the same way.
    pub size_curve: NumberCurve,
    pub speed_curve: NumberCurve,
    /// None for a shape without a radius.
    pub radius_curve: Option<NumberCurve>,
    pub color_curve: ColorCurve,
}

/// Keys by tick, never none: the first value held before the first key, the
/// last after the last.
#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct NumberCurve {
    pub times: Vec<i32>,
    pub values: Vec<f64>,
    pub easings: Vec<Easing>,
}

impl NumberCurve {
    pub fn constant(value: f64) -> NumberCurve {
        NumberCurve {
            times: vec![0],
            values: vec![value],
            easings: vec![Easing::Linear],
        }
    }

    pub fn of(keys: &[NumberKey]) -> NumberCurve {
        let sorted = crate::animation::by_time(keys, |k| k.time.into());
        NumberCurve {
            times: sorted.iter().map(|k| k.time).collect(),
            values: sorted.iter().map(|k| k.value).collect(),
            easings: sorted
                .iter()
                .map(|k| k.easing.unwrap_or(Easing::Linear))
                .collect(),
        }
    }

    pub fn at(&self, tick: f64) -> f64 {
        let last = self.times.len() - 1;
        match segment(&self.times, tick) {
            None => self.values[0],
            Some(i) if i >= last => self.values[last],
            Some(i) => {
                let t = self.easings[i].apply(
                    (tick - f64::from(self.times[i]))
                        / f64::from(self.times[i + 1] - self.times[i]),
                );
                self.values[i] + (self.values[i + 1] - self.values[i]) * t
            }
        }
    }

    /// The largest value anywhere on it.
    pub fn max(&self) -> f64 {
        self.values
            .iter()
            .copied()
            .fold(f64::NEG_INFINITY, f64::max)
    }
}

/// Colours as `0xRRGGBB`, each channel eased in sRGB; otherwise a `NumberCurve`.
#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct ColorCurve {
    pub times: Vec<i32>,
    pub colors: Vec<i32>,
    pub easings: Vec<Easing>,
}

impl ColorCurve {
    pub fn constant(color: i32) -> ColorCurve {
        ColorCurve {
            times: vec![0],
            colors: vec![color],
            easings: vec![Easing::Linear],
        }
    }

    pub fn of(keys: &[ColorKey]) -> ColorCurve {
        let sorted = crate::animation::by_time(keys, |k| k.time.into());
        ColorCurve {
            times: sorted.iter().map(|k| k.time).collect(),
            colors: sorted.iter().map(|k| parse_color(&k.color)).collect(),
            easings: sorted
                .iter()
                .map(|k| k.easing.unwrap_or(Easing::Linear))
                .collect(),
        }
    }

    pub fn at(&self, tick: f64) -> i32 {
        let last = self.times.len() - 1;
        let i = match segment(&self.times, tick) {
            None => return self.colors[0],
            Some(i) if i >= last => return self.colors[last],
            Some(i) => i,
        };
        let t = self.easings[i].apply(
            (tick - f64::from(self.times[i])) / f64::from(self.times[i + 1] - self.times[i]),
        );
        let (from, to) = (self.colors[i], self.colors[i + 1]);
        [16, 8, 0].iter().fold(0, |out, shift| {
            let a = f64::from((from >> shift) & 0xff);
            let b = f64::from((to >> shift) & 0xff);
            // Kotlin's `roundToInt` rounds half up.
            out | (((a + (b - a) * t + 0.5).floor() as i32).clamp(0, 255) << shift)
        })
    }
}

/// The key whose segment `tick` falls in: none before the first, the last
/// key's at or after it.
fn segment(times: &[i32], tick: f64) -> Option<usize> {
    if tick < f64::from(times[0]) {
        return None;
    }
    let mut i = 0;
    while i < times.len() - 1 && f64::from(times[i + 1]) <= tick {
        i += 1;
    }
    Some(i)
}

/// `#rrggbb` as `0xRRGGBB`.
pub fn parse_color(text: &str) -> i32 {
    i32::from_str_radix(text.trim_start_matches('#'), 16).expect("a checked colour")
}

/// `0xRRGGBB` as `#rrggbb`.
pub fn format_color(color: i32) -> String {
    format!("#{:06x}", color & 0xffffff)
}

/// An emitter's constants as they run: each its field's schema default when
/// the file says none.
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Constants {
    count: i32,
    size: f64,
    speed: f64,
    color: String,
    to_color: String,
}

/// `file` (the effect `id`) ready to sample, for a file whose checks found no
/// errors. `game` decides each emitter's data kind; without it (the editor's
/// preview has none) the kind is read off the data fields the emitter sets,
/// which is right for every particle that takes them.
pub fn compile(id: &str, file: &ParticleEffectFile, game: Option<&dyn GameData>) -> CompiledEffect {
    let mut emitters: Vec<CompiledEmitter> = file
        .emitters
        .iter()
        .map(|(name, emitter)| compile_emitter(&name.0, emitter, file, game))
        .collect();
    emitters.sort_by(|a, b| a.name.cmp(&b.name));
    CompiledEffect {
        id: id.to_owned(),
        duration: file.duration,
        looped: file.looped.unwrap_or(false),
        emitters,
    }
}

fn compile_emitter(
    name: &str,
    emitter: &EmitterDef,
    file: &ParticleEffectFile,
    game: Option<&dyn GameData>,
) -> CompiledEmitter {
    let kind = emitter.kind(game).unwrap_or_else(|| guess_kind(emitter));
    assert!(
        kind != ParticleData::Other,
        "emitter {name}: particle {} can't be played",
        emitter.particle
    );
    let constants: Constants = crate::resolve::resolved(emitter);
    let shape = emitter.shape.clone().unwrap_or(EmitterShape::Point);
    let curves = emitter.curves.clone().unwrap_or_default();
    let keyed = |keys: &Option<Vec<NumberKey>>| {
        keys.as_deref()
            .filter(|k| !k.is_empty())
            .map(NumberCurve::of)
    };
    let radius_curve = shape.has_radius().then(|| {
        keyed(&curves.radius).unwrap_or_else(|| {
            NumberCurve::constant(
                shape
                    .radius()
                    .unwrap_or_else(|| panic!("emitter {name} has no radius")),
            )
        })
    });
    CompiledEmitter {
        name: name.to_owned(),
        particle: game::normalize(&emitter.particle),
        kind,
        start: emitter.start.unwrap_or(0),
        end: emitter.end.unwrap_or(file.duration),
        burst: emitter.burst,
        every: emitter.every,
        rate: emitter.rate,
        shape,
        distribution: emitter.distribution.unwrap_or(Distribution::Random),
        spin: emitter.spin.unwrap_or(0.0),
        offset: emitter.offset.unwrap_or(Vec3::ZERO),
        rotation: Matrix4::from_trs(
            Vec3::ZERO,
            emitter.rotation.unwrap_or(Vec3::ZERO),
            Vec3::ONE,
        ),
        motion: emitter.motion.unwrap_or(Motion::Random),
        direction: emitter.direction.map(unit),
        count: constants.count,
        spread: emitter.spread.unwrap_or(Vec3::ZERO),
        force: emitter.force.unwrap_or(false),
        to_color: parse_color(&constants.to_color),
        block_state: emitter.block_state.as_ref().map(|s| {
            BlockState::parse(s).unwrap_or_else(|| panic!("emitter {name}: bad block state"))
        }),
        item: emitter.item.as_deref().cloned(),
        rate_curve: keyed(&curves.rate),
        size_curve: keyed(&curves.size).unwrap_or_else(|| NumberCurve::constant(constants.size)),
        speed_curve: keyed(&curves.speed).unwrap_or_else(|| NumberCurve::constant(constants.speed)),
        radius_curve,
        color_curve: curves
            .color
            .as_deref()
            .filter(|k| !k.is_empty())
            .map(ColorCurve::of)
            .unwrap_or_else(|| ColorCurve::constant(parse_color(&constants.color))),
    }
}

/// Without the game's facts: what the emitter's data fields say it is.
fn guess_kind(emitter: &EmitterDef) -> ParticleData {
    let keyed = |keys: &Option<Vec<NumberKey>>| keys.as_ref().is_some_and(|k| !k.is_empty());
    let curves = emitter.curves.as_ref();
    if emitter.block_state.is_some() {
        ParticleData::Block
    } else if emitter.item.is_some() {
        ParticleData::Item
    } else if emitter.to_color.is_some() {
        ParticleData::DustTransition
    } else if emitter.size.is_some() || curves.is_some_and(|c| keyed(&c.size)) {
        ParticleData::Dust
    } else if emitter.color.is_some()
        || curves.is_some_and(|c| c.color.as_ref().is_some_and(|k| !k.is_empty()))
    {
        ParticleData::Color
    } else {
        ParticleData::None
    }
}

fn unit(v: Vec3) -> Vec3 {
    let length = (v.0 * v.0 + v.1 * v.1 + v.2 * v.2).sqrt();
    Vec3(v.0 / length, v.1 / length, v.2 / length)
}
