//! `buffs/<id>.json`: a buff or a debuff scripts put on a player or a centity
//! for a while. Format's `BuffFile` and `BuffKind`. Every rule is a field's.

use schemars::JsonSchema;
use serde::{Deserialize, Serialize};

use crate::canonical::Map;
use crate::document::Document;

/// A buff or a debuff scripts put on a player or a centity for a while
/// (`player:add_buff`): what it adds to their stats while it lasts, what it
/// cures and what keeps it off, and how it's shown for a HUD to draw.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct BuffFile {
    #[serde(rename = "$schema", skip_serializing_if = "Option::is_none")]
    pub schema: Option<String>,
    /// What players read it as.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    /// `<pack>/<key>`: a glyph of the project's resource packs to draw it by.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub icon: Option<String>,
    /// Its colour, `#rrggbb`, for a HUD or a bar.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(extend("pattern" = crate::rules::RGB, "x-problem" = "buff.color", "x-rule" = "#rrggbb"))]
    pub color: Option<String>,
    /// Whether it's bad for whoever has it. Default false.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub debuff: Option<bool>,
    /// What it adds to stats while it lasts: `{ "defense": 8, "move": 0.25 }`, negative to take away.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(extend("propertyNames" = {
        "pattern": "^[a-z][a-z0-9_]{0,63}$",
        "x-problem": "buff.stat",
        "x-rule": "a stat name: lowercase letters, digits and _"
    }))]
    pub stats: Option<Map<f64>>,
    /// Buffs it takes away as it comes.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub cures: Option<Vec<String>>,
    /// Buffs that keep it from taking hold while they last.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub blocked_by: Option<Vec<String>>,
    /// What another of it does to one that's still going. Default `longest`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub stacking: Option<BuffStacking>,
    /// The most ticks it can have left. Default: no limit.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 1), extend("x-problem" = "buff.ticks"))]
    pub max_ticks: Option<i32>,
}

/// What adding a buff someone already has does: keep the longer time, add the times, or start again.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "lowercase")]
pub enum BuffStacking {
    Longest,
    Add,
    Replace,
}

impl Document for BuffFile {
    const SCHEMA: &'static str = "../.netherforge/schema/buff.schema.json";

    fn set_schema(&mut self, schema: Option<String>) {
        self.schema = schema;
    }
}
