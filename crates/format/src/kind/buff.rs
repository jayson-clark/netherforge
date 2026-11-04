//! `buffs/<id>.json`: a buff or a debuff scripts put on a player or a centity
//! for a while. Format's `BuffFile` and `BuffKind`.

use std::sync::LazyLock;

use regex::Regex;
use serde::{Deserialize, Serialize};

use crate::canonical::Map;
use crate::document::Document;
use crate::problem::{ProblemSink, codes};

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct BuffFile {
    #[serde(rename = "$schema", skip_serializing_if = "Option::is_none")]
    pub schema: Option<String>,
    /// What players read it as.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    /// `<pack>/<key>`: a glyph to draw it by.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub icon: Option<String>,
    /// `#rrggbb`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub color: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub debuff: Option<bool>,
    /// What it adds to stats while it lasts.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub stats: Option<Map<f64>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub cures: Option<Vec<String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub blocked_by: Option<Vec<String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub stacking: Option<BuffStacking>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub max_ticks: Option<i32>,
}

/// What adding a buff someone already has does.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum BuffStacking {
    Longest,
    Add,
    Replace,
}

/// A stat's name: what `player:stat` takes.
pub static STAT_NAME: LazyLock<Regex> =
    LazyLock::new(|| Regex::new("^[a-z][a-z0-9_]{0,63}$").expect("a pattern"));
static COLOR: LazyLock<Regex> =
    LazyLock::new(|| Regex::new("^#[0-9a-fA-F]{6}$").expect("a pattern"));

impl Document for BuffFile {
    const SCHEMA: &'static str = "../.netherforge/schema/buff.schema.json";

    fn set_schema(&mut self, schema: Option<String>) {
        self.schema = schema;
    }

    fn validate(&self, sink: &mut ProblemSink) {
        if let Some(color) = &self.color
            && !COLOR.is_match(color)
        {
            sink.report(
                codes::BUFF_COLOR,
                "color must be #rrggbb",
                Some("$.color".into()),
            );
        }
        for (stat, value) in self.stats.iter().flat_map(|s| s.iter()) {
            if !STAT_NAME.is_match(&stat.0) {
                sink.report(
                    codes::BUFF_STAT,
                    format!("\"{stat}\" isn't a stat name: lowercase letters, digits and _"),
                    Some("$.stats".into()),
                );
            } else if !value.is_finite() {
                sink.report(
                    codes::BUFF_STAT,
                    format!("{stat} must be a number"),
                    Some(format!("$.stats.{stat}")),
                );
            }
        }
        if self.max_ticks.is_some_and(|t| t < 1) {
            sink.report(
                codes::BUFF_TICKS,
                "maxTicks must be at least 1",
                Some("$.maxTicks".into()),
            );
        }
    }
}
