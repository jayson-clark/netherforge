//! `screen_fonts/<id>/font.json`: a font screens write text in, drawn in the
//! file (pixels), from a picture (bitmap) or from a font file (TrueType).
//! Format's `ScreenFontFile`, `ScreenFontKind` and `ScreenFontValidator`: its
//! sizes are its fields' rules; what depends on its type is checked here.
//! Whether a bitmap's picture cuts into its grid is a check across resources
//! (it reads the picture's size).

use schemars::JsonSchema;
use serde::{Deserialize, Serialize};

use crate::canonical::Map;
use crate::document::{Context, Document};
use crate::json::child_path;
use crate::problem::{ProblemSink, codes};

pub const FILE_NAME: &str = "font.json";
/// The game's own map font, which every project has: no file names it.
pub const DEFAULT: &str = "default";
pub const DEFAULT_SIZE: i32 = 16;
pub const MAX_SIZE: i32 = 128;
pub const MAX_SPACING: i32 = 16;
pub const MAX_LINE_HEIGHT: i32 = 256;
/// In a pixels font's rows: a pixel drawn, and one not.
pub const INK: char = '#';
pub const BLANK: char = '.';

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ScreenFontFile {
    #[serde(rename = "$schema", skip_serializing_if = "Option::is_none")]
    pub schema: Option<String>,
    #[serde(rename = "type")]
    pub kind: ScreenFontType,
    /// A bitmap font's picture (`.png`) or a TrueType font's file (`.ttf`, `.otf`), in its folder.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub file: Option<String>,
    /// A bitmap font's characters, a row of its grid each; `\u0000` leaves a cell empty.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub chars: Option<Vec<String>>,
    /// A pixels font's characters, each drawn as rows of `#` and `.`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub glyphs: Option<Map<Vec<String>>>,
    /// A TrueType font's size, in pixels.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 4, max = 128), extend("default" = 16, "x-problem" = "screen_font.size"))]
    pub size: Option<i32>,
    /// Whether a TrueType font's edges are blended. Default false.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub antialias: Option<bool>,
    /// Pixels between one character and the next.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 0, max = 16), extend("default" = 1, "x-problem" = "screen_font.size"))]
    pub spacing: Option<i32>,
    /// How wide a space is; absent: a third of a character's height.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 0, max = 128), extend("x-problem" = "screen_font.size"))]
    pub space_width: Option<i32>,
    /// Pixels from one line's top to the next's; absent: the characters' height and 1.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 1, max = 256), extend("x-problem" = "screen_font.size"))]
    pub line_height: Option<i32>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum ScreenFontType {
    Pixels,
    Bitmap,
    Truetype,
}

impl ScreenFontType {
    /// What its file ends with.
    pub fn extensions(self) -> &'static [&'static str] {
        match self {
            ScreenFontType::Pixels => &[],
            ScreenFontType::Bitmap => &[".png"],
            ScreenFontType::Truetype => &[".ttf", ".otf"],
        }
    }

    fn name(self) -> &'static str {
        match self {
            ScreenFontType::Pixels => "pixels",
            ScreenFontType::Bitmap => "bitmap",
            ScreenFontType::Truetype => "TrueType",
        }
    }
}

impl ScreenFontFile {
    /// A bitmap font's grid: characters across and rows down, when `chars` says.
    pub fn grid(&self) -> Option<(usize, usize)> {
        let chars = self.chars.as_ref().filter(|c| !c.is_empty())?;
        let across = chars[0].chars().count();
        (across != 0).then_some((across, chars.len()))
    }

    /// A pixels font's glyphs: one character each, rows of `#` and `.`, each as wide, all as tall.
    fn check_glyphs(&self, sink: &mut ProblemSink) {
        let Some(glyphs) = self.glyphs.as_ref().filter(|g| !g.is_empty()) else {
            sink.report(
                codes::SCREEN_FONT_GLYPH,
                "A pixels font draws its characters in glyphs, like { \"A\": [\".#.\", \"#.#\"] }",
                Some("$.glyphs".into()),
            );
            return;
        };
        let height = glyphs.iter().next().map_or(0, |(_, rows)| rows.len());
        for (char, rows) in glyphs.iter() {
            let at = child_path("$.glyphs", &char.0);
            if char.0.chars().count() != 1 {
                sink.report(
                    codes::SCREEN_FONT_GLYPH,
                    format!("\"{}\" isn't one character", char.0),
                    Some(at),
                );
                continue;
            }
            if rows.is_empty() || rows.len() > MAX_SIZE as usize {
                sink.report(
                    codes::SCREEN_FONT_GLYPH,
                    format!("A character is 1 to {MAX_SIZE} rows tall"),
                    Some(at),
                );
                continue;
            }
            if rows.len() != height {
                let s = if height == 1 { "" } else { "s" };
                sink.report(
                    codes::SCREEN_FONT_GLYPH,
                    format!(
                        "Every character is as tall ({height} row{s}), but this one has {}",
                        rows.len()
                    ),
                    Some(at.clone()),
                );
            }
            let width = rows[0].chars().count();
            for (row, line) in rows.iter().enumerate() {
                let length = line.chars().count();
                if length != width || length > MAX_SIZE as usize {
                    sink.report(
                        codes::SCREEN_FONT_GLYPH,
                        format!("Every row of a character is as wide (at most {MAX_SIZE})"),
                        Some(format!("{at}[{row}]")),
                    );
                } else if line.chars().any(|c| c != INK && c != BLANK) {
                    sink.report(
                        codes::SCREEN_FONT_GLYPH,
                        format!("A row is # for a pixel and . for none, not \"{line}\""),
                        Some(format!("{at}[{row}]")),
                    );
                }
            }
        }
    }
}

impl Document for ScreenFontFile {
    const SCHEMA: &'static str = "../../.netherforge/schema/screen_font.schema.json";

    fn set_schema(&mut self, schema: Option<String>) {
        self.schema = schema;
    }

    fn validate(&self, cx: &Context, sink: &mut ProblemSink) {
        use ScreenFontType::*;
        let field = |sink: &mut ProblemSink, message: &str, at: &str| {
            sink.report(codes::SCREEN_FONT_FIELD, message, Some(at.into()))
        };
        if self.kind == Pixels {
            if self.file.is_some() {
                field(
                    sink,
                    "file is a bitmap or TrueType font's: a pixels font draws its glyphs",
                    "$.file",
                );
            }
        } else {
            let extensions = self.kind.extensions();
            match &self.file {
                Some(path)
                    if crate::names::is_relative_file(path)
                        && extensions.iter().any(|e| path.to_lowercase().ends_with(e)) =>
                {
                    if cx.files.is_some_and(|f| !f.contains(path)) {
                        sink.report(
                            codes::SCREEN_FONT_MISSING,
                            format!("\"{path}\" isn't in the font's folder"),
                            Some("$.file".into()),
                        );
                    }
                }
                _ => sink.report(
                    codes::SCREEN_FONT_FILE,
                    format!(
                        "A {} font's file is a {} file in its folder",
                        self.kind.name(),
                        extensions.join(" or ")
                    ),
                    Some("$.file".into()),
                ),
            }
        }
        if self.kind != Bitmap && self.chars.is_some() {
            field(sink, "chars is a bitmap font's", "$.chars");
        }
        if self.kind != Pixels && self.glyphs.is_some() {
            field(sink, "glyphs is a pixels font's", "$.glyphs");
        }
        if self.kind != Truetype {
            if self.size.is_some() {
                field(sink, "size is a TrueType font's", "$.size");
            }
            if self.antialias.is_some() {
                field(sink, "antialias is a TrueType font's", "$.antialias");
            }
        }
        match self.kind {
            Pixels => self.check_glyphs(sink),
            Bitmap => match self.chars.as_deref() {
                Some(chars) if chars.iter().any(|r| !r.is_empty()) => {
                    let width = chars[0].chars().count();
                    for (row, line) in chars.iter().enumerate() {
                        let length = line.chars().count();
                        if length != width {
                            sink.report(
                                codes::SCREEN_FONT_CHARS,
                                format!("Every row of chars is as long as the first ({width} characters), but this one has {length}"),
                                Some(format!("$.chars[{row}]")),
                            );
                        }
                    }
                }
                _ => sink.report(
                    codes::SCREEN_FONT_CHARS,
                    "A bitmap font lists its characters in chars, a row of its grid each",
                    Some("$.chars".into()),
                ),
            },
            Truetype => {}
        }
        if cx.id == DEFAULT {
            sink.report(
                codes::SCREEN_FONT_ID,
                format!("\"{DEFAULT}\" is the game's own map font: call this one something else"),
                Some("$".into()),
            );
        }
    }
}
