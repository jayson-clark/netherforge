//! `fonts/default.json`: the default font's glyph advances for one Minecraft
//! version, so the server can measure text. Only the game client knows them,
//! so the editor reads them from the imported client and writes them into the
//! project. Format's `DefaultFontFile` and `DefaultFontValidator`.

use schemars::JsonSchema;
use serde::{Deserialize, Serialize};

use crate::canonical::{Entries, Map};
use crate::document::{Context, Document};
use crate::json::child_path;
use crate::names::MinecraftVersion;
use crate::problem::{Problem, ProblemSink, codes};

pub const FILE: &str = "fonts/default.json";
const MAX_CODE_POINT: u32 = 0x10FFFF;

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct DefaultFontFile {
    #[serde(rename = "$schema", skip_serializing_if = "Option::is_none")]
    pub schema: Option<String>,
    /// The Minecraft version whose client these were read from: `26.3`.
    pub minecraft: String,
    /// Pixels the cursor moves after each character, gap included, by code point (`"65": 6` is `A`).
    #[serde(default, skip_serializing_if = "Entries::is_empty")]
    #[schemars(extend("propertyNames" = {
        "pattern": "^(0|[1-9][0-9]{0,6})$",
        "x-problem": "font.code-point",
        "x-rule": "a code point (a whole number, no leading zeros)"
    }))]
    pub advances: Map<Advance>,
}

/// A character's advance: pixels, never negative.
#[derive(Clone, Copy, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(transparent)]
#[schemars(extend("minimum" = 0, "x-problem" = "font.advance"))]
pub struct Advance(pub i32);

impl DefaultFontFile {
    /// A character's advance, if the file covers it.
    pub fn advance(&self, code_point: u32) -> Option<i32> {
        self.advances.get_str(&code_point.to_string()).map(|a| a.0)
    }
}

impl Document for DefaultFontFile {
    const SCHEMA: &'static str = "../.netherforge/schema/default_font.schema.json";

    fn set_schema(&mut self, schema: Option<String>) {
        self.schema = schema;
    }

    /// A key past the last code point (its pattern takes seven digits).
    fn validate(&self, _: &Context, sink: &mut ProblemSink) {
        for (key, _) in self.advances.iter() {
            if key.0.parse::<u32>().is_ok_and(|c| c > MAX_CODE_POINT) {
                sink.report(
                    codes::FONT_CODE_POINT,
                    format!(
                        "\"{}\" isn't a code point (a whole number, no leading zeros)",
                        key.0
                    ),
                    Some(child_path("$.advances", &key.0)),
                );
            }
        }
    }
}

/// The file from its `text`, checked against the project's `target` version:
/// one generated for another version is stale (its advances may have changed).
pub fn load(text: &str, target: Option<&str>) -> (Option<DefaultFontFile>, Vec<Problem>) {
    let read = crate::document::load::<DefaultFontFile>(text, FILE, &Default::default());
    let mut sink = ProblemSink::new(FILE);
    if let Some(file) = &read.value {
        match MinecraftVersion::parse(&file.minecraft) {
            None => sink.report(
                codes::FONT_MINECRAFT,
                format!("\"{}\" isn't a Minecraft version", file.minecraft),
                Some("$.minecraft".into()),
            ),
            Some(version)
                if target
                    .and_then(MinecraftVersion::parse)
                    .is_some_and(|t| t != version) =>
            {
                let target = target.expect("parsed");
                sink.report(
                    codes::FONT_STALE,
                    format!(
                        "These advances are Minecraft {}'s, but the project targets {target}: open the project in the editor with {target} imported to regenerate them",
                        file.minecraft
                    ),
                    Some("$.minecraft".into()),
                );
            }
            Some(_) => {}
        }
    }
    let mut problems = sink.problems;
    problems.extend(read.problems);
    (read.value, problems)
}

/// Whether `font` is there and was generated for `target`: what measuring text for the project needs.
pub fn is_current(font: Option<&DefaultFontFile>, target: Option<&str>) -> bool {
    let (Some(font), Some(target)) = (font, target) else {
        return false;
    };
    MinecraftVersion::parse(&font.minecraft)
        .is_some_and(|v| MinecraftVersion::parse(target) == Some(v))
}

/// Warns at `at` that `why` needs the default font's advances when `font` is
/// missing or stale for `target`: for anything that measures text on the server.
pub fn require_current(
    font: Option<&DefaultFontFile>,
    target: Option<&str>,
    sink: &mut ProblemSink,
    at: Option<String>,
    why: &str,
) {
    if is_current(font, target) {
        return;
    }
    let state = match font {
        None => format!("there's no {FILE}"),
        Some(font) => format!("{FILE} is for Minecraft {}", font.minecraft),
    };
    sink.report(
        codes::FONT_NEEDED,
        format!(
            "{why}, but {state}: open the project in the editor with Minecraft {} imported to write it",
            target.unwrap_or("(the target)")
        ),
        at,
    );
}
