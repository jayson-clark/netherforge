//! `particles/<id>/effect.json`: a timeline of particle spawns scripts play
//! at a place. Format's `ParticleEffectFile`, `ParticleEffectKind`,
//! `ParticleOptions` and `ParticleEffectValidator`: its duration, emitter
//! names, burst, rate, count, every, size, colours and speed are its fields'
//! rules; what depends on its particle, shape, motion and timeline is
//! checked here. (Its sampler and compiler come with the runtime's effects.)

use std::collections::HashSet;

use schemars::JsonSchema;
use serde::{Deserialize, Serialize};

use crate::animation::{Easing, Vec3, by_time};
use crate::block_state::{self, BlockStateCodes};
use crate::canonical::Map;
use crate::document::{Context, Document};
use crate::game::{self, GameData, ParticleData};
use crate::item::{self, ItemDef};
use crate::json::{child_path, format_number};
use crate::problem::{ProblemSink, codes};

pub const FILE_NAME: &str = "effect.json";
pub const MIN_DURATION: i32 = 1;
pub const MAX_DURATION: i32 = 72000;
/// Points one effect may spawn in a tick, at worst, summed over its emitters.
pub const MAX_POINTS_PER_TICK: i64 = 256;
pub const MAX_BURST: i32 = 256;
pub const MAX_RATE: f64 = 256.0;
pub const MAX_COUNT: i32 = 100;
pub const MIN_SIZE: f64 = 0.01;
pub const MAX_SIZE: f64 = 4.0;
pub const DEFAULT_COUNT: i32 = 1;
pub const DEFAULT_SIZE: f64 = 1.0;
pub const DEFAULT_COLOR: &str = "#ffffff";

pub const BLOCK_CODES: BlockStateCodes = BlockStateCodes {
    state: codes::PARTICLE_BLOCK_STATE,
    unknown: codes::PARTICLE_UNKNOWN_BLOCK,
    property: codes::PARTICLE_BLOCK_PROPERTY,
};

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct ParticleEffectFile {
    #[serde(rename = "$schema", skip_serializing_if = "Option::is_none")]
    pub schema: Option<String>,
    /// How long the timeline is, in ticks.
    #[schemars(range(min = 1, max = 72000), extend("x-problem" = "particle.duration"))]
    pub duration: i32,
    /// Restart at tick 0 after the last tick, until stopped. Default false.
    #[serde(rename = "loop", skip_serializing_if = "Option::is_none")]
    pub looped: Option<bool>,
    /// Keyed by name; they run in key order.
    #[serde(default)]
    #[schemars(extend("propertyNames" = {
        "pattern": crate::names::NODE_NAME_PATTERN,
        "x-problem": "particle.emitter-name",
        "x-rule": crate::names::NODE_NAME_RULE
    }))]
    pub emitters: Map<EmitterDef>,
}

/// What a particle effect's colour fields must be, in words.
const COLOR_RULE: &str = "a colour written #rrggbb in lowercase, like \"#ffaa00\"";

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EmitterDef {
    /// A particle id: `minecraft:end_rod`, or `end_rod`.
    pub particle: String,
    /// The first tick it's active. Default 0.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub start: Option<i32>,
    /// The tick it stops at, exclusive. Default the effect's duration.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub end: Option<i32>,
    /// Points per burst. Exactly one of burst and rate.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 1, max = 256), extend("x-problem" = "particle.burst"))]
    pub burst: Option<i32>,
    /// Repeat the burst every this many ticks. Absent: one burst at start.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 1), extend("x-problem" = "particle.every"))]
    pub every: Option<i32>,
    /// Points per tick; fractions accumulate.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(max = 256), extend("exclusiveMinimum" = 0, "x-problem" = "particle.rate"))]
    pub rate: Option<f64>,
    /// Where points go. Default a single point.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub shape: Option<EmitterShape>,
    /// How points spread over the shape. Default random.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub distribution: Option<Distribution>,
    /// Degrees per tick the shape turns about its up axis: ring, disc and line.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub spin: Option<f64>,
    /// The emitter's origin in effect space, blocks.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub offset: Option<Vec3>,
    /// Euler degrees, X then Y then Z.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub rotation: Option<Vec3>,
    /// How each point's particle moves. Default random.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub motion: Option<Motion>,
    /// Which way particles fly with `motion: "direction"`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub direction: Option<Vec3>,
    /// The game's particles per point, with `motion: "random"` only.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 1, max = 100), extend("default" = 1, "x-problem" = "particle.count"))]
    pub count: Option<i32>,
    /// The game's Gaussian spread per axis, with `motion: "random"` only.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub spread: Option<Vec3>,
    /// The game's `extra`: random speed, or velocity along the direction.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 0), extend("default" = 0, "x-problem" = "particle.speed"))]
    pub speed: Option<f64>,
    /// Shown past the client's particle setting and up to 128 blocks away.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub force: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(extend("pattern" = crate::rules::RGB_LOWERCASE, "default" = "#ffffff", "x-problem" = "particle.color", "x-rule" = COLOR_RULE))]
    pub color: Option<String>,
    /// The colour a dust transition fades to.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(extend("pattern" = crate::rules::RGB_LOWERCASE, "default" = "#ffffff", "x-problem" = "particle.color", "x-rule" = COLOR_RULE))]
    pub to_color: Option<String>,
    /// Dust size.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 0.01, max = 4), extend("default" = 1, "x-problem" = "particle.size"))]
    pub size: Option<f64>,
    /// The block a block particle shows, with properties if wanted.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub block_state: Option<String>,
    /// The item an item particle shows.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub item: Option<Box<ItemDef>>,
    /// Properties that change over the effect's timeline.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub curves: Option<Curves>,
}

/// Where an emitter puts its points, in emitter space: up is +Y.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(tag = "type", rename_all = "snake_case", deny_unknown_fields)]
pub enum EmitterShape {
    Point,
    /// From the origin to `to`.
    Line {
        to: Vec3,
    },
    /// On the circle in the XZ plane.
    Ring {
        #[serde(skip_serializing_if = "Option::is_none")]
        radius: Option<f64>,
    },
    /// Inside the circle, uniform by area.
    Disc {
        #[serde(skip_serializing_if = "Option::is_none")]
        radius: Option<f64>,
    },
    /// In the ball, or on its shell with `surface`.
    Sphere {
        #[serde(skip_serializing_if = "Option::is_none")]
        radius: Option<f64>,
        #[serde(skip_serializing_if = "Option::is_none")]
        surface: Option<bool>,
    },
    /// In the box centred on the origin, or on its faces with `surface`.
    Box {
        size: Vec3,
        #[serde(skip_serializing_if = "Option::is_none")]
        surface: Option<bool>,
    },
}

impl EmitterShape {
    pub fn radius(&self) -> Option<f64> {
        match self {
            EmitterShape::Ring { radius }
            | EmitterShape::Disc { radius }
            | EmitterShape::Sphere { radius, .. } => *radius,
            _ => None,
        }
    }

    pub fn has_radius(&self) -> bool {
        matches!(
            self,
            EmitterShape::Ring { .. } | EmitterShape::Disc { .. } | EmitterShape::Sphere { .. }
        )
    }

    pub fn even_allowed(&self) -> bool {
        matches!(
            self,
            EmitterShape::Line { .. }
                | EmitterShape::Ring { .. }
                | EmitterShape::Sphere {
                    surface: Some(true),
                    ..
                }
        )
    }

    pub fn spin_allowed(&self) -> bool {
        matches!(
            self,
            EmitterShape::Ring { .. } | EmitterShape::Disc { .. } | EmitterShape::Line { .. }
        )
    }

    /// Its `type`.
    pub fn name(&self) -> String {
        let value = serde_json::to_value(self).expect("a shape serializes");
        value["type"].as_str().unwrap_or_default().to_owned()
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum Distribution {
    Random,
    /// Evenly spaced: for line, ring, and sphere with `surface`.
    Even,
}

/// How a point's particle moves: the two numbers a spawn of the game's carries.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum Motion {
    /// The game's scatter: `count` particles, Gaussian `spread`, drift at `speed`.
    Random,
    /// Away from the shape's centre.
    Outward,
    /// Towards it.
    Inward,
    /// Along `direction`.
    Direction,
}

/// Keyed channels on the effect's one timeline. A channel replaces its constant.
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct Curves {
    /// Points per tick, 0 to 256: for an emitter with a rate.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub rate: Option<Vec<NumberKey>>,
    /// Dust size: for a particle that takes one.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub size: Option<Vec<NumberKey>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub speed: Option<Vec<NumberKey>>,
    /// More than 0: for ring, disc and sphere.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub radius: Option<Vec<NumberKey>>,
    /// For a particle that takes a colour.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub color: Option<Vec<ColorKey>>,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct NumberKey {
    pub time: i32,
    pub value: f64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub easing: Option<Easing>,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct ColorKey {
    pub time: i32,
    #[schemars(extend("pattern" = crate::rules::RGB_LOWERCASE, "x-problem" = "particle.color", "x-rule" = COLOR_RULE))]
    pub color: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub easing: Option<Easing>,
}

/// An emitter's data fields, by their keys in the file, in the file's order:
/// which a particle takes is the one place the checks, the compiler and the
/// editor's inspector read (`ParticleOptions`).
pub mod options {
    use crate::game::ParticleData;

    pub const COLOR: &str = "color";
    pub const TO_COLOR: &str = "toColor";
    pub const SIZE: &str = "size";
    pub const BLOCK_STATE: &str = "blockState";
    pub const ITEM: &str = "item";
    pub const ALL: [&str; 5] = [COLOR, TO_COLOR, SIZE, BLOCK_STATE, ITEM];

    /// The data fields a particle of `kind` takes.
    pub fn takes(kind: ParticleData) -> &'static [&'static str] {
        match kind {
            ParticleData::Dust => &[COLOR, SIZE],
            ParticleData::DustTransition => &[COLOR, TO_COLOR, SIZE],
            ParticleData::Color => &[COLOR],
            ParticleData::Block => &[BLOCK_STATE],
            ParticleData::Item => &[ITEM],
            ParticleData::None | ParticleData::Other => &[],
        }
    }

    /// The data field a particle of `kind` can't be spawned without.
    pub fn required(kind: ParticleData) -> Option<&'static str> {
        match kind {
            ParticleData::Block => Some(BLOCK_STATE),
            ParticleData::Item => Some(ITEM),
            _ => None,
        }
    }
}

impl EmitterDef {
    /// Its particle's data kind, when the game knows it.
    pub fn kind(&self, game: Option<&dyn GameData>) -> Option<ParticleData> {
        game.filter(|_| game::is_id(&self.particle))?
            .particle(&game::normalize(&self.particle))
    }

    /// The most points it can spawn in a tick: its burst, or its rate's highest value rounded up.
    pub fn worst_points_per_tick(&self) -> i64 {
        if let Some(burst) = self.burst {
            return burst.into();
        }
        let Some(rate) = self.rate else { return 0 };
        let keys = self
            .curves
            .as_ref()
            .and_then(|c| c.rate.as_deref())
            .unwrap_or_default();
        keys.iter().map(|k| k.value).fold(rate, f64::max).ceil() as i64
    }

    fn has(&self, field: &str) -> bool {
        match field {
            options::COLOR => self.color.is_some(),
            options::TO_COLOR => self.to_color.is_some(),
            options::SIZE => self.size.is_some(),
            options::BLOCK_STATE => self.block_state.is_some(),
            options::ITEM => self.item.is_some(),
            _ => unreachable!("{field} isn't a data field"),
        }
    }

    /// The particle and its data fields; its kind when known.
    fn check_particle(
        &self,
        at: &str,
        sink: &mut ProblemSink,
        game: Option<&dyn GameData>,
    ) -> Option<ParticleData> {
        let id = game::normalize(&self.particle);
        let particle_at = Some(child_path(at, "particle"));
        if !game::is_id(&self.particle) {
            sink.report(
                codes::PARTICLE_ID,
                format!("\"{}\" isn't a particle id", self.particle),
                particle_at.clone(),
            );
        } else if let Some(game) = game
            && game.particle(&id).is_none()
        {
            sink.report(
                codes::PARTICLE_UNKNOWN,
                format!(
                    "Minecraft {} has no particle \"{id}\"",
                    game.minecraft_version()
                ),
                particle_at.clone(),
            );
        }
        let kind = self.kind(game);
        if kind == Some(ParticleData::Other) {
            sink.report(
                codes::PARTICLE_UNSUPPORTED,
                format!("{id} takes options effects can't send; play it from a script instead"),
                particle_at,
            );
        }
        if let Some(kind) = kind.filter(|k| *k != ParticleData::Other) {
            let takes = options::takes(kind);
            for field in options::ALL {
                if self.has(field) && !takes.contains(&field) {
                    sink.report(
                        codes::PARTICLE_OPTION,
                        format!("{id} takes no {field}"),
                        Some(child_path(at, field)),
                    );
                }
            }
            if let Some(required) = options::required(kind)
                && !self.has(required)
            {
                sink.report(
                    codes::PARTICLE_OPTION,
                    format!("{id} needs a {required}"),
                    Some(at.into()),
                );
            }
        }
        if let Some(state) = &self.block_state {
            block_state::check(
                state,
                &child_path(at, "blockState"),
                BLOCK_CODES,
                sink,
                game,
            );
        }
        if let Some(item) = &self.item {
            item::check(item, &child_path(at, "item"), sink, game);
        }
        kind
    }

    fn check(&self, duration: i32, at: &str, sink: &mut ProblemSink, game: Option<&dyn GameData>) {
        let kind = self.check_particle(at, sink, game);
        let field = |name: &str| Some(child_path(at, name));

        // Timing
        let start = self.start.unwrap_or(0);
        let end = self.end.unwrap_or(duration);
        if self.start.is_some() && !(0..duration).contains(&start) {
            sink.report(
                codes::PARTICLE_WINDOW,
                format!("start must be from 0 to before the effect's duration ({duration})"),
                field("start"),
            );
        }
        if self.end.is_some() && (end <= start || end > duration) {
            sink.report(
                codes::PARTICLE_WINDOW,
                format!("end must be after start and at most the effect's duration ({duration})"),
                field("end"),
            );
        }

        // Emission
        if self.burst.is_none() == self.rate.is_none() {
            sink.report(
                codes::PARTICLE_EMISSION,
                "An emitter has exactly one of burst and rate",
                Some(at.into()),
            );
        }
        if self.every.is_some() && self.burst.is_none() {
            sink.report(
                codes::PARTICLE_EVERY,
                "every repeats a burst, so it needs burst",
                field("every"),
            );
        }

        // Shape
        let shape = self.shape.clone().unwrap_or(EmitterShape::Point);
        let radius_curve = self
            .curves
            .as_ref()
            .and_then(|c| c.radius.as_ref())
            .is_some_and(|k| !k.is_empty());
        let shape_at = child_path(at, "shape");
        match &shape {
            EmitterShape::Line { to } if *to == Vec3::default() => {
                sink.report(
                    codes::PARTICLE_SHAPE,
                    "A line's to can't be [0, 0, 0]",
                    Some(child_path(&shape_at, "to")),
                );
            }
            EmitterShape::Box { size, .. } if size.coordinates().iter().any(|c| *c <= 0.0) => {
                sink.report(
                    codes::PARTICLE_SHAPE,
                    "A box's size must be more than 0 on every axis",
                    Some(child_path(&shape_at, "size")),
                );
            }
            s if s.has_radius() => {
                let name = s.name();
                match s.radius() {
                    Some(_) if radius_curve => sink.report(
                        codes::PARTICLE_CURVE_CONFLICT,
                        "radius is set and curves.radius drives it too; keep one",
                        Some(child_path(&shape_at, "radius")),
                    ),
                    Some(r) if r <= 0.0 => sink.report(
                        codes::PARTICLE_SHAPE,
                        format!("A {name}'s radius must be more than 0"),
                        Some(child_path(&shape_at, "radius")),
                    ),
                    None if !radius_curve => sink.report(
                        codes::PARTICLE_SHAPE,
                        format!("A {name} needs a radius, or a radius curve"),
                        Some(shape_at.clone()),
                    ),
                    _ => {}
                }
            }
            _ => {}
        }
        if self.distribution == Some(Distribution::Even) && !shape.even_allowed() {
            sink.report(
                codes::PARTICLE_DISTRIBUTION,
                "even spacing is only for a line, a ring, or a sphere with surface",
                field("distribution"),
            );
        }
        if self.spin.is_some() && !shape.spin_allowed() {
            sink.report(
                codes::PARTICLE_SPIN,
                "Only a ring, a disc or a line can spin",
                field("spin"),
            );
        }

        // Motion
        let motion = self.motion.unwrap_or(Motion::Random);
        match (motion, self.direction) {
            (Motion::Direction, None) => sink.report(
                codes::PARTICLE_DIRECTION,
                "motion: \"direction\" needs a direction",
                Some(at.into()),
            ),
            (Motion::Direction, Some(d)) if d == Vec3::default() => sink.report(
                codes::PARTICLE_DIRECTION,
                "direction can't be [0, 0, 0]",
                field("direction"),
            ),
            (Motion::Direction, Some(_)) | (_, None) => {}
            (_, Some(_)) => sink.report(
                codes::PARTICLE_DIRECTION,
                "direction is only for motion: \"direction\"",
                field("direction"),
            ),
        }
        if self.count.is_some() && motion != Motion::Random {
            sink.report(
                codes::PARTICLE_COUNT,
                "count is only for motion: \"random\"; the game ignores it otherwise",
                field("count"),
            );
        }
        if let Some(spread) = self.spread {
            if motion != Motion::Random {
                sink.report(
                    codes::PARTICLE_SPREAD,
                    "spread is only for motion: \"random\"; the game ignores it otherwise",
                    field("spread"),
                );
            } else if spread.coordinates().iter().any(|c| *c < 0.0) {
                sink.report(
                    codes::PARTICLE_SPREAD,
                    "spread can't be negative",
                    field("spread"),
                );
            }
        }

        self.check_curves(duration, kind, &shape, at, sink);
    }

    fn check_curves(
        &self,
        duration: i32,
        kind: Option<ParticleData>,
        shape: &EmitterShape,
        at: &str,
        sink: &mut ProblemSink,
    ) {
        let Some(curves) = &self.curves else { return };
        let takes = |field: &str| kind.is_none_or(|k| options::takes(k).contains(&field));
        let for_particle = format!("for {}", self.particle);
        let on_shape = format!("on a {}", shape.name());
        // One channel: allowed here, not beside its constant, keys inside the timeline and at distinct ticks.
        let channel = |name: &str,
                       times: Vec<i32>,
                       allowed: bool,
                       where_: &str,
                       constant: bool,
                       sink: &mut ProblemSink| {
            if times.is_empty() {
                return;
            }
            let path = format!("{at}.curves.{name}");
            if !allowed {
                sink.report(
                    codes::PARTICLE_CURVE_CHANNEL,
                    format!("There's no {name} curve {where_}")
                        .trim_end()
                        .to_owned(),
                    Some(path.clone()),
                );
            }
            if constant {
                sink.report(
                    codes::PARTICLE_CURVE_CONFLICT,
                    format!("{name} is set and has a curve too; keep one"),
                    Some(path.clone()),
                );
            }
            let mut seen = HashSet::new();
            for (index, time) in times.into_iter().enumerate() {
                let at = Some(format!("{path}[{index}].time"));
                if !(0..=duration).contains(&time) {
                    sink.report(
                        codes::PARTICLE_CURVE_TIME,
                        format!("A key's time must be 0 to the effect's duration ({duration})"),
                        at,
                    );
                } else if !seen.insert(time) {
                    sink.report(
                        codes::PARTICLE_CURVE_DUPLICATE,
                        format!("Two keys are at tick {time}"),
                        at,
                    );
                }
            }
        };
        let numbers = |keys: &Option<Vec<NumberKey>>| keys.as_deref().unwrap_or_default().to_vec();
        let times = |keys: &[NumberKey]| keys.iter().map(|k| k.time).collect::<Vec<_>>();
        let values = |name: &str,
                      keys: &[NumberKey],
                      ok: &dyn Fn(f64) -> bool,
                      code,
                      message: &str,
                      sink: &mut ProblemSink| {
            for (index, key) in keys.iter().enumerate() {
                if !ok(key.value) {
                    sink.report(
                        code,
                        message,
                        Some(format!("{at}.curves.{name}[{index}].value")),
                    );
                }
            }
        };
        let rate = numbers(&curves.rate);
        channel(
            "rate",
            times(&rate),
            self.rate.is_some(),
            "on an emitter without rate",
            false,
            sink,
        );
        values(
            "rate",
            &rate,
            &|v| (0.0..=MAX_RATE).contains(&v),
            codes::PARTICLE_RATE,
            &format!("A rate key must be 0 to {}", format_number(MAX_RATE)),
            sink,
        );
        let size = numbers(&curves.size);
        channel(
            "size",
            times(&size),
            takes(options::SIZE),
            &for_particle,
            self.size.is_some(),
            sink,
        );
        let size_rule = format!(
            "size must be {} to {}",
            format_number(MIN_SIZE),
            format_number(MAX_SIZE)
        );
        values(
            "size",
            &size,
            &|v| (MIN_SIZE..=MAX_SIZE).contains(&v),
            codes::PARTICLE_SIZE,
            &size_rule,
            sink,
        );
        let speed = numbers(&curves.speed);
        channel("speed", times(&speed), true, "", self.speed.is_some(), sink);
        values(
            "speed",
            &speed,
            &|v| v >= 0.0,
            codes::PARTICLE_SPEED,
            "speed can't be negative",
            sink,
        );
        // A radius set beside its curve is reported at the shape.
        let radius = numbers(&curves.radius);
        channel(
            "radius",
            times(&radius),
            shape.has_radius(),
            &on_shape,
            false,
            sink,
        );
        values(
            "radius",
            &radius,
            &|v| v > 0.0,
            codes::PARTICLE_SHAPE,
            "A radius must be more than 0",
            sink,
        );
        let color = curves.color.as_deref().unwrap_or_default();
        channel(
            "color",
            color.iter().map(|k| k.time).collect(),
            takes(options::COLOR),
            &for_particle,
            self.color.is_some(),
            sink,
        );
    }
}

impl Document for ParticleEffectFile {
    const SCHEMA: &'static str = "../../.netherforge/schema/particle_effect.schema.json";

    fn set_schema(&mut self, schema: Option<String>) {
        self.schema = schema;
    }

    /// Curve keys in time order; emitters are a map, which the writer orders.
    fn canonical(&self) -> Self {
        let mut out = self.clone();
        for (_, emitter) in out.emitters.0.iter_mut() {
            if let Some(curves) = &mut emitter.curves {
                let sort = |keys: &mut Option<Vec<NumberKey>>| {
                    if let Some(k) = keys {
                        *k = by_time(k, |k| k.time.into());
                    }
                };
                sort(&mut curves.rate);
                sort(&mut curves.size);
                sort(&mut curves.speed);
                sort(&mut curves.radius);
                if let Some(k) = &mut curves.color {
                    *k = by_time(k, |k| k.time.into());
                }
            }
        }
        out
    }

    fn validate(&self, cx: &Context, sink: &mut ProblemSink) {
        if self.emitters.is_empty() {
            sink.report(
                codes::PARTICLE_EMITTERS_EMPTY,
                "This effect has no emitters, so it shows nothing",
                Some("$.emitters".into()),
            );
        }
        for (name, emitter) in self.emitters.iter() {
            emitter.check(
                self.duration,
                &child_path("$.emitters", &name.0),
                sink,
                cx.game,
            );
        }
        let worst: i64 = self
            .emitters
            .iter()
            .map(|(_, e)| e.worst_points_per_tick())
            .sum();
        if worst > MAX_POINTS_PER_TICK {
            sink.report(
                codes::PARTICLE_BUDGET,
                format!("This effect can spawn {worst} points in one tick; the limit is {MAX_POINTS_PER_TICK}"),
                Some("$.emitters".into()),
            );
        }
    }
}
