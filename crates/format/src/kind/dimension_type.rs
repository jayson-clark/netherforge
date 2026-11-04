//! `dimension_types/<id>.json`: a world's build limits, light, sky, colours
//! and the game's rules for it. Format's `DimensionTypeFile`,
//! `DimensionTypeKind` and `DimensionTypeValidator`: its ranges, colours and
//! heights' steps are their fields' rules, and each default is declared once,
//! in its field's schema. (Its datapack entry comes with the start-up datapack.)

use schemars::JsonSchema;
use serde::{Deserialize, Serialize};

use crate::document::{Context, Document};
use crate::game::{self, Registry};
use crate::problem::{ProblemSink, codes};

pub const DEFAULT_MIN_Y: i32 = -64;
pub const DEFAULT_HEIGHT: i32 = 384;
pub const LOWEST_Y: i32 = -2032;
pub const TOP_Y: i32 = 2032;
pub const SECTION: i32 = 16;
pub const DEFAULT_MONSTER_LIGHT_MIN: i32 = 0;
pub const DEFAULT_MONSTER_LIGHT_MAX: i32 = 7;

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct DimensionTypeFile {
    #[serde(rename = "$schema", skip_serializing_if = "Option::is_none")]
    pub schema: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = -2032), extend("multipleOf" = 16, "default" = -64, "x-problem" = "dimension-type.height"))]
    pub min_y: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 16), extend("multipleOf" = 16, "default" = 384, "x-problem" = "dimension-type.height"))]
    pub height: Option<i32>,
    /// Default: the height.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub logical_height: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(extend("default" = true))]
    pub sky_light: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(extend("default" = false))]
    pub ceiling: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 0, max = 1), extend("default" = 0, "x-problem" = "dimension-type.light"))]
    pub ambient_light: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(extend("default" = false))]
    pub fixed_time: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(extend("default" = "overworld"))]
    pub sky: Option<DimensionTypeSky>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub colors: Option<DimensionTypeColors>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = -2032, max = 2032), extend("default" = 192.33, "x-problem" = "dimension-type.number"))]
    pub cloud_height: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(extend("default" = true))]
    pub bed_works: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(extend("default" = false))]
    pub respawn_anchor_works: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(extend("default" = false))]
    pub piglin_safe: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(extend("default" = true))]
    pub raids: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(extend("default" = false))]
    pub ultrawarm: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub monster_spawn_light: Option<LightRange>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 0, max = 15), extend("default" = 0, "x-problem" = "dimension-type.light"))]
    pub monster_spawn_block_light: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(extend("default" = "#minecraft:infiniburn_overworld"))]
    pub infiniburn: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 0.00001, max = 30_000_000.0), extend("default" = 1, "x-problem" = "dimension-type.number"))]
    pub coordinate_scale: Option<f64>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "lowercase")]
pub enum DimensionTypeSky {
    Overworld,
    End,
    None,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct DimensionTypeColors {
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(extend("pattern" = crate::rules::RGB, "default" = "#78a7ff", "x-problem" = "dimension-type.color", "x-rule" = "a colour written #rrggbb"))]
    pub sky: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(extend("pattern" = crate::rules::RGB, "default" = "#c0d8ff", "x-problem" = "dimension-type.color", "x-rule" = "a colour written #rrggbb"))]
    pub fog: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(extend(
        "pattern" = crate::rules::ARGB,
        "default" = "#ccffffff",
        "x-problem" = "dimension-type.color",
        "x-rule" = "a colour written #rrggbb or #aarrggbb"
    ))]
    pub clouds: Option<String>,
}

/// The light levels monsters spawn at, 0 to 15.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct LightRange {
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 0, max = 15), extend("default" = 0, "x-problem" = "dimension-type.light"))]
    pub min: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 0, max = 15), extend("default" = 7, "x-problem" = "dimension-type.light"))]
    pub max: Option<i32>,
}

impl DimensionTypeFile {
    /// The lowest block and the one above the highest a world of this type has.
    pub fn world_height(&self) -> (i32, i32) {
        let min = self.min_y.unwrap_or(DEFAULT_MIN_Y);
        (min, min + self.height.unwrap_or(DEFAULT_HEIGHT))
    }
}

impl Document for DimensionTypeFile {
    const SCHEMA: &'static str = "../.netherforge/schema/dimension_type.schema.json";

    fn set_schema(&mut self, schema: Option<String>) {
        self.schema = schema;
    }

    /// What depends on more than one field: the top, the logical height, the
    /// light range's order, and the infiniburn tag.
    fn validate(&self, cx: &Context, sink: &mut ProblemSink) {
        let height = self.height.unwrap_or(DEFAULT_HEIGHT);
        let top = i64::from(self.min_y.unwrap_or(DEFAULT_MIN_Y)) + i64::from(height);
        if top > i64::from(TOP_Y) {
            sink.report(
                codes::DIMENSION_TYPE_HEIGHT,
                format!("minY + height is {top}: the top of a world can be at most {TOP_Y}"),
                Some(
                    if self.height.is_some() {
                        "$.height"
                    } else {
                        "$.minY"
                    }
                    .into(),
                ),
            );
        }
        if let Some(logical) = self.logical_height
            && !(0..=height).contains(&logical)
        {
            sink.report(
                codes::DIMENSION_TYPE_LOGICAL_HEIGHT,
                format!("logicalHeight is from 0 to the height ({height}), not {logical}"),
                Some("$.logicalHeight".into()),
            );
        }
        if let Some(light) = &self.monster_spawn_light {
            let min = light.min.unwrap_or(DEFAULT_MONSTER_LIGHT_MIN);
            let max = light.max.unwrap_or(DEFAULT_MONSTER_LIGHT_MAX);
            if min > max {
                sink.report(
                    codes::DIMENSION_TYPE_LIGHT,
                    format!("min ({min}) is above max ({max})"),
                    Some("$.monsterSpawnLight.min".into()),
                );
            }
        }
        if let Some(text) = &self.infiniburn {
            let id = text.trim_start_matches('#');
            if !text.starts_with('#') || !game::is_id(id) {
                sink.report(
                    codes::DIMENSION_TYPE_INFINIBURN,
                    format!("\"{text}\" isn't a block tag, written #namespace:path"),
                    Some("$.infiniburn".into()),
                );
            } else if let Some(game) = cx.game
                && game.tag(Registry::Block, &game::normalize(id)).is_none()
            {
                sink.report(
                    codes::DIMENSION_TYPE_INFINIBURN_TAG,
                    format!(
                        "Minecraft {} has no block tag \"{text}\"",
                        game.minecraft_version()
                    ),
                    Some("$.infiniburn".into()),
                );
            }
        }
    }
}
