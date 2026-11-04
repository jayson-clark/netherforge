//! `skies/<id>.json`: the colours a sky gives the world it's in. Format's
//! `SkyFile`, `SkyKind` and `SkyValidator`; each colour's form and the fade are
//! their fields' rules. (Its datapack, the biomes' twins and the night's
//! timeline, is written with the start-up datapack.)

use schemars::JsonSchema;
use serde::{Deserialize, Serialize};

use crate::document::{Context, Document};
use crate::problem::{ProblemSink, codes};

pub const DEFAULT_FADE: i32 = 3;
pub const MAX_FADE: i32 = 8;

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SkyFile {
    #[serde(rename = "$schema", skip_serializing_if = "Option::is_none")]
    pub schema: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(extend("pattern" = crate::rules::RGB, "x-problem" = "sky.color", "x-rule" = "a colour written #rrggbb, like \"#5a0a0a\""))]
    pub sky: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(extend("pattern" = crate::rules::RGB, "x-problem" = "sky.color", "x-rule" = "a colour written #rrggbb, like \"#5a0a0a\""))]
    pub fog: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(extend(
        "pattern" = crate::rules::ARGB,
        "x-problem" = "sky.color",
        "x-rule" = "a colour written #rrggbb or #aarrggbb, like \"#5a0a0a\" or \"#cc5a0a0a\""
    ))]
    pub clouds: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(extend("pattern" = crate::rules::RGB, "x-problem" = "sky.color", "x-rule" = "a colour written #rrggbb, like \"#5a0a0a\""))]
    pub water: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(extend("pattern" = crate::rules::RGB, "x-problem" = "sky.color", "x-rule" = "a colour written #rrggbb, like \"#5a0a0a\""))]
    pub water_fog: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(extend("pattern" = crate::rules::RGB, "x-problem" = "sky.color", "x-rule" = "a colour written #rrggbb, like \"#5a0a0a\""))]
    pub light: Option<String>,
    /// Whether night darkens it. Default true.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub darkens: Option<bool>,
    /// Steps it fades in over. Default 3.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 1, max = 8), extend("x-problem" = "sky.fade"))]
    pub fade: Option<i32>,
}

impl Document for SkyFile {
    const SCHEMA: &'static str = "../.netherforge/schema/sky.schema.json";

    fn set_schema(&mut self, schema: Option<String>) {
        self.schema = schema;
    }

    fn validate(&self, _: &Context, sink: &mut ProblemSink) {
        let colors = [
            &self.sky,
            &self.fog,
            &self.clouds,
            &self.water,
            &self.water_fog,
            &self.light,
        ];
        if colors.iter().all(|c| c.is_none()) {
            sink.report(
                codes::SKY_EMPTY,
                "A sky gives at least one colour: sky, fog, clouds, water, waterFog or light",
                Some("$".into()),
            );
        }
        // Night holds only the sky's and the fog's colours.
        if self.darkens == Some(false) && self.sky.is_none() && self.fog.is_none() {
            sink.report(
                codes::SKY_NIGHT,
                "darkens is false, but the sky gives no sky or fog colour to hold at night",
                Some("$.darkens".into()),
            );
        }
    }
}
