//! `centities/<id>/centity.json`: a composed, scripted entity: a tree of
//! nodes, each drawing a display, maybe clicked through a hitbox and moved by
//! physics, with animations, a body, health and natural spawning. Format's
//! `CentityFile`, `CentityKind` and `CentityValidator`: its names, numbers and
//! colours are its fields' rules; its node tree, what a display, hitbox or
//! animation depends on, and the game's ids are checked here. (Its compiler
//! comes with the runtime's centities.)

use std::collections::HashSet;

use schemars::JsonSchema;
use serde::{Deserialize, Serialize};

use crate::animation::{Keyframe, by_time};
use crate::block_state::{self, BlockState, BlockStateCodes};
use crate::canonical::{Entries, Map, set};
use crate::document::{Context, Document};
use crate::game::{self, GameData, Registry};
use crate::json::{child_path, format_number};
use crate::math::{BoundingBox, Vec3};
use crate::problem::{ProblemCode, ProblemSink, codes};
use crate::script::{self, ScriptDef};
use crate::worldgen::SpawnRange;

pub const FILE_NAME: &str = "centity.json";
pub const MAX_LINKS: i32 = 64;
pub const DEFAULT_LINK_LENGTH: f64 = 0.5;
pub const MAX_LINK_LENGTH: f64 = 8.0;
pub const DEFAULT_LINE_WIDTH: i32 = 200;
pub const IMAGE_MAX_SIDE: i32 = 256;
pub const IMAGE_MAX_PIXELS: i32 = 128 * 128;
pub const PIXELS_PER_BLOCK: f64 = 16.0;
/// The item a model display is drawn on.
pub const MODEL_CARRIER: &str = "minecraft:paper";
pub const MAX_BODY_WIDTH: f64 = 16.0;
pub const MAX_BODY_HEIGHT: f64 = 32.0;
pub const MAX_HEALTH_SEGMENTS: i32 = 40;
/// The node that draws the health bar, when a centity has one of its own.
pub const HEALTH_BAR_NODE: &str = "health_bar";
pub const DEFAULT_SPAWN_WEIGHT: i32 = 10;
pub const DEFAULT_SPAWN_CAP: i32 = 4;
pub const DEFAULT_DESPAWN_DISTANCE: i32 = 96;
pub const MIN_LIGHT: i32 = 0;
pub const MAX_LIGHT: i32 = 15;
/// How near and far from a player the spawner places centities.
pub const MIN_SPAWN_DISTANCE: i32 = 24;
pub const MAX_SPAWN_DISTANCE: i32 = 48;
pub const DAY_TICKS: i32 = 24_000;
pub const LAST_TICK: i32 = DAY_TICKS - 1;
/// A spawning condition's name: an id, or `ns:name` for a package's.
const CONDITION_PATTERN: &str = "^[a-z0-9][a-z0-9_]{0,63}(:[a-z0-9][a-z0-9_]{0,63})?$";

pub const BLOCK_CODES: BlockStateCodes = BlockStateCodes {
    state: codes::CENTITY_BLOCK_STATE,
    unknown: codes::CENTITY_UNKNOWN_BLOCK,
    property: codes::CENTITY_BLOCK_PROPERTY,
};

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct CentityFile {
    #[serde(rename = "$schema", skip_serializing_if = "Option::is_none")]
    pub schema: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    #[serde(default)]
    #[schemars(extend("propertyNames" = {
        "pattern": crate::names::NODE_NAME_PATTERN, "x-problem": "centity.node-name", "x-rule": crate::names::NODE_NAME_RULE
    }))]
    pub nodes: Map<NodeDef>,
    #[serde(default, skip_serializing_if = "Entries::is_empty")]
    #[schemars(extend("propertyNames" = {
        "pattern": crate::names::NODE_NAME_PATTERN, "x-problem": "centity.animation-name", "x-rule": crate::names::NODE_NAME_RULE
    }))]
    pub animations: Map<AnimationDef>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub body: Option<BodyDef>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub health: Option<HealthDef>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub script: Option<ScriptDef>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub spawning: Option<SpawningDef>,
    /// The project's own facts about it, any JSON, for scripts.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub properties: Option<Map<serde_json::Value>>,
}

/// Where and when the world spawns it by itself.
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SpawningDef {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub worlds: Option<Vec<WorldName>>,
    /// Biomes, the game's or the project's, or `#tags` of the game's.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub biomes: Option<Vec<String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub light: Option<SpawnRange>,
    /// The blocks (or `#tags`) it may stand on.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub blocks: Option<Vec<String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub placement: Option<SpawnPlacement>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub height: Option<SpawnRange>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 1), extend("default" = 10, "x-problem" = "centity.spawning-number"))]
    pub weight: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub group: Option<SpawnRange>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 1), extend("default" = 4, "x-problem" = "centity.spawning-number"))]
    pub cap: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 1), extend("default" = 96, "x-problem" = "centity.spawning-number"))]
    pub despawn_distance: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub keep_on_interact: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub time: Option<SpawnTime>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub moon_phase: Option<Vec<MoonPhase>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub weather: Option<Vec<SpawnWeather>>,
    /// A condition scripts register, by name.
    #[serde(rename = "when", skip_serializing_if = "Option::is_none")]
    #[schemars(extend(
        "pattern" = CONDITION_PATTERN,
        "x-problem" = "centity.spawning-when",
        "x-rule" = "a condition's name: lowercase letters, digits and _, starting with a letter or digit, at most 64 characters, or `ns:name` for a package's"
    ))]
    pub condition: Option<String>,
}

crate::ruled_string!(
    /// A world's name.
    WorldName,
    crate::names::WORLD_NAME_PATTERN,
    "centity.spawning-world",
    crate::names::WORLD_NAME_RULE
);

/// A time of day: `day`, `night`, or a range of ticks (a min above the max runs through midnight).
#[derive(Clone, Copy, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(untagged)]
pub enum SpawnTime {
    Period(DayPeriod),
    Range(SpawnRange),
}

impl SpawnTime {
    pub fn start(&self) -> i32 {
        match self {
            SpawnTime::Period(p) => p.ticks().0,
            SpawnTime::Range(r) => r.min.unwrap_or(0),
        }
    }

    pub fn end(&self) -> i32 {
        match self {
            SpawnTime::Period(p) => p.ticks().1,
            SpawnTime::Range(r) => r.max.unwrap_or(LAST_TICK),
        }
    }

    pub fn contains(&self, time_of_day: i32) -> bool {
        let (start, end) = (self.start(), self.end());
        if start <= end {
            (start..=end).contains(&time_of_day)
        } else {
            time_of_day >= start || time_of_day <= end
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum DayPeriod {
    Day,
    Night,
}

impl DayPeriod {
    /// Its first and last tick of the day.
    pub fn ticks(self) -> (i32, i32) {
        match self {
            DayPeriod::Day => (23_000, 12_999),
            DayPeriod::Night => (13_000, 22_999),
        }
    }
}

#[derive(
    Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize, JsonSchema,
)]
#[serde(rename_all = "snake_case")]
pub enum MoonPhase {
    FullMoon,
    WaningGibbous,
    LastQuarter,
    WaningCrescent,
    NewMoon,
    WaxingCrescent,
    FirstQuarter,
    WaxingGibbous,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum SpawnPlacement {
    Ground,
    Water,
}

#[derive(
    Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize, JsonSchema,
)]
#[serde(rename_all = "snake_case")]
pub enum SpawnWeather {
    Clear,
    Rain,
    Thunder,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct NodeDef {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub parent: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub transform: Option<Transform>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub display: Option<DisplayDef>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub hitbox: Option<HitboxDef>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub physics: Option<PhysicsDef>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub rope: Option<RopeDef>,
}

/// A chain of links the server lays from the node, each drawing its display.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RopeDef {
    #[schemars(range(min = 1, max = 64), extend("x-problem" = "centity.rope"))]
    pub links: i32,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(max = 8), extend("exclusiveMinimum" = 0, "default" = 0.5, "x-problem" = "centity.rope"))]
    pub link_length: Option<f64>,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct Transform {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub translation: Option<Vec3>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub rotation: Option<Vec3>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub scale: Option<Vec3>,
}

/// What a node draws.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(
    tag = "type",
    rename_all = "snake_case",
    rename_all_fields = "camelCase",
    deny_unknown_fields
)]
pub enum DisplayDef {
    Block {
        block: String,
    },
    Item {
        item: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        item_transform: Option<ItemTransform>,
    },
    /// An item look of a resource pack, drawn on paper.
    Model {
        model: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        item_transform: Option<ItemTransform>,
    },
    Text {
        text: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        billboard: Option<Billboard>,
        #[serde(skip_serializing_if = "Option::is_none")]
        alignment: Option<TextAlignment>,
        /// `#aarrggbb`.
        #[serde(skip_serializing_if = "Option::is_none")]
        #[schemars(extend("pattern" = "^#[0-9a-fA-F]{8}$", "x-problem" = "centity.background", "x-rule" = "#AARRGGBB"))]
        background: Option<String>,
        #[serde(skip_serializing_if = "Option::is_none")]
        #[schemars(range(min = 1), extend("default" = 200, "x-problem" = "centity.line-width"))]
        line_width: Option<i32>,
        #[serde(skip_serializing_if = "Option::is_none")]
        see_through: Option<bool>,
        #[serde(skip_serializing_if = "Option::is_none")]
        shadow: Option<bool>,
        #[serde(skip_serializing_if = "Option::is_none")]
        anchor: Option<Anchor>,
    },
    Rect {
        #[schemars(extend("pattern" = crate::rules::ARGB, "x-problem" = "centity.color", "x-rule" = "#RRGGBB or #AARRGGBB"))]
        color: String,
        /// `[width, height]` in blocks.
        #[serde(skip_serializing_if = "Option::is_none")]
        size: Option<Vec<f64>>,
        #[serde(skip_serializing_if = "Option::is_none")]
        anchor: Option<Anchor>,
    },
    /// A picture of pixels a script sets.
    Image {
        width: i32,
        height: i32,
        #[serde(skip_serializing_if = "Option::is_none")]
        size: Option<Vec<f64>>,
        #[serde(skip_serializing_if = "Option::is_none")]
        anchor: Option<Anchor>,
    },
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum ItemTransform {
    None,
    ThirdpersonLefthand,
    ThirdpersonRighthand,
    FirstpersonLefthand,
    FirstpersonRighthand,
    Head,
    Gui,
    Ground,
    Fixed,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum Anchor {
    TopLeft,
    Top,
    TopRight,
    Left,
    Center,
    Right,
    BottomLeft,
    Bottom,
    BottomRight,
}

impl Anchor {
    /// Where on what it anchors it is, `(x, y)` from the bottom left.
    pub fn at(self) -> (f64, f64) {
        use Anchor::*;
        match self {
            TopLeft => (0.0, 1.0),
            Top => (0.5, 1.0),
            TopRight => (1.0, 1.0),
            Left => (0.0, 0.5),
            Center => (0.5, 0.5),
            Right => (1.0, 0.5),
            BottomLeft => (0.0, 0.0),
            Bottom => (0.5, 0.0),
            BottomRight => (1.0, 0.0),
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum Billboard {
    Fixed,
    Vertical,
    Horizontal,
    Center,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum TextAlignment {
    Center,
    Left,
    Right,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct HitboxDef {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub boxes: Option<Vec<BoundingBox>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub shape: Option<HitboxShape>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub raycast: Option<bool>,
    /// What the editor fitted its boxes to (`fit_key`).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub fitted_to: Option<String>,
}

impl HitboxDef {
    pub fn follows_collision(&self) -> bool {
        self.shape == Some(HitboxShape::Collision)
    }

    pub fn raycast(&self) -> bool {
        self.raycast
            .unwrap_or(self.follows_collision() || self.boxes.as_ref().is_some_and(|b| b.len() > 1))
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum HitboxShape {
    Collision,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PhysicsDef {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub gravity: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(extend("exclusiveMinimum" = 0, "x-problem" = "centity.physics-mass"))]
    pub mass: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 0, max = 1), extend("x-problem" = "centity.physics-range"))]
    pub bounciness: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 0), extend("x-problem" = "centity.physics-range"))]
    pub friction: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 0, max = 1), extend("x-problem" = "centity.physics-range"))]
    pub drag: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 0, max = 1), extend("x-problem" = "centity.physics-range"))]
    pub angular_drag: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(extend("exclusiveMinimum" = 0, "x-problem" = "centity.physics-stuck"))]
    pub max_speed: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub max_spin: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub collider: Option<BoundingBox>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub shape: Option<PhysicsShape>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub rotates: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub sleeps: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub blocks: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub entities: Option<bool>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum PhysicsShape {
    Box,
    Sphere,
}

/// How the centity as a whole moves, as a mob's body does.
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct BodyDef {
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(max = 16), extend("exclusiveMinimum" = 0, "x-problem" = "centity.body-size"))]
    pub width: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(max = 32), extend("exclusiveMinimum" = 0, "x-problem" = "centity.body-size"))]
    pub height: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub gravity: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 0), extend("x-problem" = "centity.body-range"))]
    pub max_fall_speed: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 0, max = 1), extend("x-problem" = "centity.body-range"))]
    pub drag: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 0, max = 1), extend("x-problem" = "centity.body-range"))]
    pub liquid_drag: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 0), extend("x-problem" = "centity.body-range"))]
    pub liquid_gravity: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 0), extend("x-problem" = "centity.body-range"))]
    pub acceleration: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 0), extend("x-problem" = "centity.body-range"))]
    pub air_acceleration: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 0), extend("x-problem" = "centity.body-range"))]
    pub friction: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 0), extend("x-problem" = "centity.body-range"))]
    pub step_height: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 0, max = 1), extend("x-problem" = "centity.body-range"))]
    pub bounce: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub fly: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub blocks: Option<bool>,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct HealthDef {
    #[schemars(extend("exclusiveMinimum" = 0, "x-problem" = "centity.health"))]
    pub max: f64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub remove_on_death: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub bar: Option<HealthBarDef>,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct HealthBarDef {
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 1, max = 40), extend("x-problem" = "centity.health"))]
    pub segments: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 0), extend("x-problem" = "centity.health"))]
    pub show_ticks: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub offset: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(length(min = 1), extend("x-problem" = "centity.health"))]
    pub glyph: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub filled: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub empty: Option<String>,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct AnimationDef {
    /// Seconds. Default: its last key's time.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(extend("exclusiveMinimum" = 0, "x-problem" = "centity.animation-length"))]
    pub length: Option<f64>,
    #[serde(rename = "loop", skip_serializing_if = "Option::is_none")]
    pub looped: Option<LoopMode>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub autoplay: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub blend: Option<AnimationBlend>,
    /// Keys by node, then channel.
    #[serde(default)]
    pub tracks: Map<Entries<Channel, Vec<Keyframe>>>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum AnimationBlend {
    Replace,
    Additive,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum LoopMode {
    Once,
    Loop,
    Hold,
}

/// What a track moves, in declaration order (format's for its keys).
#[derive(
    Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize, Deserialize, JsonSchema,
)]
#[serde(rename_all = "snake_case")]
pub enum Channel {
    Translation,
    Rotation,
    Scale,
}

impl Channel {
    /// Its value when nothing moves it.
    pub fn identity(self) -> Vec3 {
        if self == Channel::Scale {
            Vec3(1.0, 1.0, 1.0)
        } else {
            Vec3::default()
        }
    }
}

/// What a hitbox fitted to `display` records in `fittedTo`.
pub fn fit_key(display: Option<&DisplayDef>) -> Option<String> {
    match display? {
        DisplayDef::Block { block } => {
            Some(BlockState::parse(block).map_or_else(|| block.clone(), |s| s.to_string()))
        }
        DisplayDef::Text { text, .. } => Some(format!("text:{text}")),
        _ => None,
    }
}

pub fn node_path(name: &str) -> String {
    child_path("$.nodes", name)
}

impl CentityFile {
    /// Nodes that can't be ordered parents-first because their ancestry loops.
    pub fn cyclic(&self) -> Vec<String> {
        let mut result = Vec::new();
        for (start, _) in self.nodes.iter() {
            let mut seen = HashSet::new();
            let mut current = Some(start.0.as_str());
            while let Some(name) = current {
                if !seen.insert(name) {
                    if name == start.0 {
                        result.push(start.0.clone());
                    }
                    break;
                }
                let parent = self.nodes.get_str(name).and_then(|n| n.parent.as_deref());
                current = parent.filter(|p| *p != name && self.nodes.get_str(p).is_some());
            }
        }
        result.sort();
        result
    }
}

/// One node on its own, as a script adds it to a running centity: what it
/// draws, where it can be clicked and how it moves. Its name's rule and its
/// parent are the file's to check.
pub fn check_node(name: &str, node: &NodeDef, sink: &mut ProblemSink, game: Option<&dyn GameData>) {
    let at = node_path(name);
    if node.rope.is_some() {
        let at = format!("{at}.rope");
        if node.display.is_none() {
            sink.report(
                codes::CENTITY_ROPE,
                "a rope needs a display: what each link draws",
                Some(at.clone()),
            );
        }
        if node.hitbox.is_some() || node.physics.is_some() {
            sink.report(
                codes::CENTITY_ROPE,
                "a rope can't have a hitbox or physics: it's laid by the server",
                Some(at),
            );
        }
    }
    if let Some(display) = &node.display {
        check_display(display, &format!("{at}.display"), sink, game);
    }
    if let Some(hitbox) = &node.hitbox {
        check_hitbox(node, hitbox, &format!("{at}.hitbox"), sink);
    }
    if let Some(physics) = &node.physics {
        let at = format!("{at}.physics");
        if physics.blocks == Some(false) && physics.entities == Some(false) {
            sink.report(
                codes::CENTITY_PHYSICS_FALLS,
                "It collides with neither blocks nor centities, so nothing will stop it falling",
                Some(at.clone()),
            );
        }
        if physics.collider.is_some_and(|b| !b.has_volume()) {
            sink.report(
                codes::CENTITY_PHYSICS_COLLIDER,
                "The collider's max must be greater than its min on every axis",
                Some(format!("{at}.collider")),
            );
        }
    }
}

fn check_size(size: &[f64], at: &str, sink: &mut ProblemSink) {
    if size.len() != 2 || size.iter().any(|s| *s <= 0.0 || !s.is_finite()) {
        sink.report(
            codes::CENTITY_DISPLAY_SIZE,
            "size is [width, height] in blocks, both more than 0",
            Some(at.into()),
        );
    }
}

fn check_display(
    display: &DisplayDef,
    at: &str,
    sink: &mut ProblemSink,
    game: Option<&dyn GameData>,
) {
    match display {
        DisplayDef::Block { block } => {
            block_state::check(block, &format!("{at}.block"), BLOCK_CODES, sink, game)
        }
        DisplayDef::Item { item, .. } => {
            let id = game::normalize(item);
            if !game::is_id(item) {
                sink.report(
                    codes::CENTITY_ITEM_ID,
                    format!("\"{item}\" isn't an item id"),
                    Some(format!("{at}.item")),
                );
            } else if let Some(game) = game
                && game.lacks(Registry::Item, &id)
            {
                sink.report(
                    codes::CENTITY_UNKNOWN_ITEM,
                    format!(
                        "Minecraft {} has no item \"{id}\"",
                        game.minecraft_version()
                    ),
                    Some(format!("{at}.item")),
                );
            }
        }
        // Its look is a reference, checked across resources.
        DisplayDef::Model { .. } | DisplayDef::Text { .. } => {}
        DisplayDef::Rect { size, .. } => {
            if let Some(size) = size {
                check_size(size, &format!("{at}.size"), sink);
            }
        }
        DisplayDef::Image {
            width,
            height,
            size,
            ..
        } => {
            let side = 1..=IMAGE_MAX_SIDE;
            if !side.contains(width) || !side.contains(height) {
                sink.report(
                    codes::CENTITY_IMAGE_SIZE,
                    format!(
                        "an image is 1 to {IMAGE_MAX_SIDE} pixels each way, not {width} by {height}"
                    ),
                    Some(at.into()),
                );
            } else if width * height > IMAGE_MAX_PIXELS {
                sink.report(
                    codes::CENTITY_IMAGE_SIZE,
                    format!(
                        "an image has at most {IMAGE_MAX_PIXELS} pixels, not {}",
                        width * height
                    ),
                    Some(at.into()),
                );
            }
            if let Some(size) = size {
                check_size(size, &format!("{at}.size"), sink);
            }
        }
    }
}

fn check_hitbox(node: &NodeDef, hitbox: &HitboxDef, at: &str, sink: &mut ProblemSink) {
    if hitbox.follows_collision() {
        if hitbox.boxes.is_some() {
            sink.report(
                codes::CENTITY_HITBOX_BOTH,
                "A hitbox has either boxes or shape: \"collision\", not both",
                Some(at.into()),
            );
        }
        if !matches!(node.display, Some(DisplayDef::Block { .. })) {
            sink.report(
                codes::CENTITY_HITBOX_COLLISION,
                "shape: \"collision\" needs a block display on the same node",
                Some(format!("{at}.shape")),
            );
        }
    }
    for (index, b) in hitbox.boxes.iter().flatten().enumerate() {
        if !b.has_volume() {
            sink.report(
                codes::CENTITY_HITBOX_SIZE,
                "Each box's max must be greater than its min on every axis",
                Some(format!("{at}.boxes[{index}]")),
            );
        }
    }
    if hitbox.boxes.as_ref().is_some_and(Vec::is_empty) {
        sink.report(
            codes::CENTITY_HITBOX_EMPTY,
            "This hitbox has no boxes, so nothing can click it",
            Some(format!("{at}.boxes")),
        );
    }
    if let Some(fitted) = &hitbox.fitted_to
        && let Some(current) = fit_key(node.display.as_ref())
        && current != *fitted
    {
        sink.report(
            codes::CENTITY_HITBOX_STALE,
            format!("Hitbox was fitted to \"{fitted}\" but the display is now \"{current}\"; fit it again"),
            Some(format!("{at}.fittedTo")),
        );
    }
}

/// A biome or block id, or `#tag`: well formed, and with game data, one the game has.
fn spawning_id(
    text: &str,
    what: &str,
    registry: Registry,
    code: ProblemCode,
    at: String,
    sink: &mut ProblemSink,
    game: Option<&dyn GameData>,
) {
    let tag = text.starts_with('#');
    let id = text.trim_start_matches('#');
    if !game::is_id(id) {
        sink.report(
            code,
            format!(
                "\"{text}\" isn't a {what} {}",
                if tag { "tag" } else { "id" }
            ),
            Some(at),
        );
        return;
    }
    let normal = game::normalize(id);
    let Some(game) = game.filter(|g| g.registry(registry.key()).is_some()) else {
        return;
    };
    if tag && game.tag_of(registry, &normal).is_none() {
        sink.report(
            code,
            format!(
                "Minecraft {} has no {what} tag \"#{normal}\"",
                game.minecraft_version()
            ),
            Some(at),
        );
    } else if !tag && game.lacks(registry, &normal) {
        sink.report(
            code,
            format!(
                "Minecraft {} has no {what} \"{normal}\"",
                game.minecraft_version()
            ),
            Some(at),
        );
    }
}

fn check_range(range: &SpawnRange, at: &str, sink: &mut ProblemSink) {
    if let (Some(min), Some(max)) = (range.min, range.max)
        && min > max
    {
        sink.report(
            codes::CENTITY_SPAWNING_RANGE,
            "min is above max",
            Some(at.into()),
        );
    }
}

fn check_spawning(spawning: &SpawningDef, sink: &mut ProblemSink, game: Option<&dyn GameData>) {
    let at = "$.spawning";
    for (index, biome) in spawning.biomes.iter().flatten().enumerate() {
        if game::is_games(biome) {
            spawning_id(
                biome,
                "biome",
                Registry::Biome,
                codes::CENTITY_SPAWNING_BIOME,
                format!("{at}.biomes[{index}]"),
                sink,
                game,
            );
        }
    }
    for (index, block) in spawning.blocks.iter().flatten().enumerate() {
        spawning_id(
            block,
            "block",
            Registry::Block,
            codes::CENTITY_SPAWNING_BLOCK,
            format!("{at}.blocks[{index}]"),
            sink,
            game,
        );
    }
    if let Some(light) = &spawning.light {
        check_range(light, &format!("{at}.light"), sink);
        for (end, value) in [("min", light.min), ("max", light.max)] {
            if value.is_some_and(|v| !(MIN_LIGHT..=MAX_LIGHT).contains(&v)) {
                sink.report(
                    codes::CENTITY_SPAWNING_RANGE,
                    "A light level is from 0 to 15",
                    Some(format!("{at}.light.{end}")),
                );
            }
        }
    }
    if let Some(height) = &spawning.height {
        check_range(height, &format!("{at}.height"), sink);
    }
    if let Some(group) = &spawning.group {
        check_range(group, &format!("{at}.group"), sink);
        for (end, value) in [("min", group.min), ("max", group.max)] {
            if value.is_some_and(|v| v < 1) {
                sink.report(
                    codes::CENTITY_SPAWNING_NUMBER,
                    "A group has at least one",
                    Some(format!("{at}.group.{end}")),
                );
            }
        }
    }
    if let Some(SpawnTime::Range(range)) = &spawning.time {
        for (end, value) in [("min", range.min), ("max", range.max)] {
            if value.is_some_and(|v| !(0..=LAST_TICK).contains(&v)) {
                sink.report(
                    codes::CENTITY_SPAWNING_TIME,
                    format!("A time of day is a tick from 0 to {LAST_TICK} (a min above the max runs through midnight)"),
                    Some(format!("{at}.time.{end}")),
                );
            }
        }
    }
    if let Some(distance) = spawning.despawn_distance
        && (1..=MAX_SPAWN_DISTANCE).contains(&distance)
    {
        sink.report(
            codes::CENTITY_SPAWNING_DESPAWN,
            format!(
                "The spawner places centities up to {MAX_SPAWN_DISTANCE} blocks from a player, so a despawnDistance of {distance} removes them as they appear"
            ),
            Some(format!("{at}.despawnDistance")),
        );
    }
}

fn check_animations(file: &CentityFile, sink: &mut ProblemSink) {
    for (name, clip) in file.animations.iter() {
        let at = child_path("$.animations", name);
        if clip.tracks.is_empty() {
            sink.report(
                codes::CENTITY_ANIMATION_EMPTY,
                format!("Animation \"{name}\" has no tracks"),
                Some(format!("{at}.tracks")),
            );
            continue;
        }
        let mut last_key = 0.0f64;
        for (node, channels) in clip.tracks.iter() {
            let track_at = child_path(&format!("{at}.tracks"), node);
            if file.nodes.get_str(node).is_none() {
                sink.report(
                    codes::CENTITY_ANIMATION_NODE,
                    format!("Animation \"{name}\" drives unknown node \"{node}\""),
                    Some(track_at),
                );
                continue;
            }
            for (channel, keys) in channels.iter() {
                let channel = crate::canonical::name_of(channel);
                if keys.is_empty() {
                    sink.report(
                        codes::CENTITY_TRACK_EMPTY,
                        format!("Track {node}.{channel} in \"{name}\" has no keyframes"),
                        Some(format!("{track_at}.{channel}")),
                    );
                    continue;
                }
                if keys.iter().any(|k| k.time < 0.0) {
                    sink.report(
                        codes::CENTITY_KEY_TIME,
                        "Keyframes can't be before 0",
                        Some(format!("{track_at}.{channel}")),
                    );
                }
                last_key = keys.iter().map(|k| k.time).fold(last_key, f64::max);
            }
        }
        // A length that isn't more than 0 is its field's rule.
        if let Some(length) = clip.length.filter(|l| *l > 0.0 && *l < last_key) {
            sink.report(
                codes::CENTITY_KEYS_PAST_LENGTH,
                format!("Keyframes after {}s never play", format_number(length)),
                Some(format!("{at}.length")),
            );
        }
    }
}

impl Document for CentityFile {
    const SCHEMA: &'static str = "../../.netherforge/schema/centity.schema.json";

    fn set_schema(&mut self, schema: Option<String>) {
        self.schema = schema;
    }

    const SCRIPTED: bool = true;

    fn script(&self) -> Option<&ScriptDef> {
        self.script.as_ref()
    }

    /// Spawning's lists as sets; keyframes in time order.
    fn canonical(&self) -> Self {
        let mut out = self.clone();
        if let Some(spawning) = &mut out.spawning {
            let sorted = |list: &mut Option<Vec<String>>| {
                if let Some(l) = list {
                    *l = set(l);
                }
            };
            if let Some(worlds) = &mut spawning.worlds {
                let names: Vec<String> = worlds.iter().map(|w| w.0.clone()).collect();
                *worlds = set(&names).into_iter().map(WorldName).collect();
            }
            sorted(&mut spawning.biomes);
            sorted(&mut spawning.blocks);
            if let Some(phases) = &mut spawning.moon_phase {
                phases.sort();
                phases.dedup();
            }
            if let Some(weather) = &mut spawning.weather {
                weather.sort();
                weather.dedup();
            }
        }
        for (_, clip) in out.animations.0.iter_mut() {
            for (_, channels) in clip.tracks.0.iter_mut() {
                for (_, keys) in channels.0.iter_mut() {
                    *keys = by_time(keys, |k| k.time);
                }
            }
        }
        out
    }

    fn validate(&self, cx: &Context, sink: &mut ProblemSink) {
        if self.nodes.is_empty() {
            sink.report(
                codes::CENTITY_NO_NODES,
                "A centity needs at least one node",
                Some("$.nodes".into()),
            );
            return;
        }
        for (name, node) in self.nodes.iter() {
            let at = node_path(name);
            if let Some(parent) = &node.parent {
                if parent == &name.0 {
                    sink.report(
                        codes::CENTITY_SELF_PARENT,
                        format!("Node \"{name}\" is its own parent"),
                        Some(format!("{at}.parent")),
                    );
                } else if self.nodes.get_str(parent).is_none() {
                    sink.report(
                        codes::CENTITY_UNKNOWN_PARENT,
                        format!("Node \"{name}\" has unknown parent \"{parent}\""),
                        Some(format!("{at}.parent")),
                    );
                }
            }
            check_node(name, node, sink, cx.game);
        }
        for name in self.cyclic() {
            sink.report(
                codes::CENTITY_CYCLE,
                format!("Node \"{name}\" is part of a parent cycle"),
                Some(format!("{}.parent", node_path(&name))),
            );
        }
        check_animations(self, sink);
        if let Some(s) = &self.script {
            script::check(s, "$.script", cx, sink);
        }
        if let Some(spawning) = &self.spawning {
            check_spawning(spawning, sink, cx.game);
        }
        if self.health.is_some()
            && let Some(own) = self.nodes.get_str(HEALTH_BAR_NODE)
            && !matches!(own.display, Some(DisplayDef::Text { .. }))
        {
            sink.report(
                codes::CENTITY_HEALTH,
                format!("The node \"{HEALTH_BAR_NODE}\" draws the health bar, so it must be a text display"),
                Some(format!("{}.display", node_path(HEALTH_BAR_NODE))),
            );
        }
    }
}
