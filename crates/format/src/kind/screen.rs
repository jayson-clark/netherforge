//! `screens/<id>/screen.json`: a wall of maps scripts draw on and play
//! videos on. Format's `ScreenFile`, `ScreenKind` and `ScreenValidator`: its
//! size, colour, world, ranges and volume are its fields' rules. Two screens
//! hanging in one block is a check across resources.

use schemars::JsonSchema;
use serde::{Deserialize, Serialize};

use crate::document::{Context, Document};
use crate::problem::ProblemSink;
use crate::script::{self, ScriptDef};

/// A map's side in pixels.
pub const TILE: i32 = 128;
/// The most maps a screen (or video) may have along a side.
pub const MAX_TILES: i32 = 16;
pub const DEFAULT_RANGE: i32 = 64;
pub const MAX_RANGE: i32 = 256;
pub const DEFAULT_CLICK_RANGE: f64 = 24.0;
pub const MAX_CLICK_RANGE: f64 = 64.0;

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ScreenFile {
    #[serde(rename = "$schema", skip_serializing_if = "Option::is_none")]
    pub schema: Option<String>,
    /// Maps across.
    #[schemars(range(min = 1, max = 16), extend("x-problem" = "screen.size"))]
    pub width: i32,
    /// Maps down.
    #[schemars(range(min = 1, max = 16), extend("x-problem" = "screen.size"))]
    pub height: i32,
    /// What it's filled with before anything is drawn (the nearest map colour); absent: clear.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(extend("pattern" = crate::rules::RGB, "x-problem" = "screen.color", "x-rule" = "a colour written #rrggbb, like \"#202030\""))]
    pub background: Option<String>,
    /// Where it hangs, kept there by the server.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub place: Option<ScreenPlace>,
    /// Who it's drawn for. Default every player near it.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub viewers: Option<ScreenViewers>,
    /// How near a player must be to be drawn it, in blocks.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 1, max = 256), extend("default" = 64, "x-problem" = "screen.range"))]
    pub range: Option<i32>,
    /// Whether its frames glow. Default true.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub glowing: Option<bool>,
    /// How far a click reaches it from, in blocks; 0 is the game's reach alone.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 0, max = 64), extend("default" = 24, "x-problem" = "screen.range"))]
    pub click_range: Option<f64>,
    /// A video it plays from the start.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub video: Option<ScreenVideo>,
    /// Its one script, with `this` the screen.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub script: Option<ScriptDef>,
}

/// Where a screen hangs: the block its top left map hangs in, and the way it faces.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct ScreenPlace {
    #[schemars(extend("pattern" = crate::names::WORLD_NAME_PATTERN, "x-problem" = "screen.world", "x-rule" = crate::names::WORLD_NAME_RULE))]
    pub world: String,
    pub x: i32,
    pub y: i32,
    pub z: i32,
    pub facing: ScreenFacing,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum ScreenFacing {
    North,
    South,
    East,
    West,
}

impl ScreenFacing {
    /// The way out of the wall, towards a player looking at it: (x, z).
    pub fn normal(self) -> (i32, i32) {
        match self {
            ScreenFacing::North => (0, -1),
            ScreenFacing::South => (0, 1),
            ScreenFacing::East => (1, 0),
            ScreenFacing::West => (-1, 0),
        }
    }

    /// The way to that player's right: the way its maps go across.
    pub fn right(self) -> (i32, i32) {
        let (x, z) = self.normal();
        (z, -x)
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum ScreenViewers {
    /// Every player within its range.
    Near,
    /// Only the players a script shows it to.
    Script,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct ScreenVideo {
    pub video: String,
    /// Plays it again when it ends. Default true.
    #[serde(rename = "loop", skip_serializing_if = "Option::is_none")]
    pub looped: Option<bool>,
    /// Its sound's volume; 0 plays it silent.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 0, max = 1), extend("default" = 1, "x-problem" = "screen.volume"))]
    pub volume: Option<f64>,
}

impl Document for ScreenFile {
    const SCHEMA: &'static str = "../../.netherforge/schema/screen.schema.json";

    fn set_schema(&mut self, schema: Option<String>) {
        self.schema = schema;
    }

    const SCRIPTED: bool = true;

    fn script(&self) -> Option<&ScriptDef> {
        self.script.as_ref()
    }

    fn validate(&self, cx: &Context, sink: &mut ProblemSink) {
        if let Some(s) = &self.script {
            script::check(s, "$.script", cx, sink);
        }
    }
}
