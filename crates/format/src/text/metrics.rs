//! How Minecraft lays out MiniMessage text, in font pixels (1/40 of a block for
//! a text display): format's `TextMetrics`.
//!
//! Glyph widths come from the player's own client (the default font's bitmaps,
//! read by the editor when it imports a version), passed in as `advance`. So
//! this only runs where client data exists: the editor's previews and its
//! "fit to display" for text hitboxes. The server never measures text with it.
//!
//! Why there are two measurers: this one and `TextWidth` answer different
//! questions and must disagree where they do. This is a layout for drawing a
//! preview: it always has an answer (a character the font doesn't cover gets
//! an estimate, a glyph no pack has draws nothing, as the game draws it), it
//! wraps, and every line keeps its range of the source, all over the same
//! MiniMessage pieces the editor draws. `TextWidth` is the server's
//! `nf.text.width`: one number that's exact or `None`, never an estimate, so
//! anything it can't know (an uncovered character, an unknown glyph advance, a
//! client-only insert, another font) makes it `None`, and it reads unknown tags
//! as MiniMessage does (as text). Folding one into the other would make the
//! server guess or the preview go blank.

use serde::Serialize;

use super::minimessage::{Piece, pieces};
use super::{CharAdvance, GlyphAdvance};
use crate::kind::centity::{Billboard, DEFAULT_BILLBOARD, DEFAULT_LINE_WIDTH};
use crate::kind::resource_pack::fonts;
use crate::math::{BoundingBox, Vec3};

/// A font pixel on a text display, in blocks.
pub const BLOCKS_PER_PIXEL: f64 = 0.025;

/// One line: glyphs plus the gap after them. A display is exactly this times its line count.
pub const LINE_HEIGHT: i32 = 10;

/// The background reaches this far past the text at the +X end only.
pub const PADDING: i32 = 1;

/// Depth given to a flat text quad so it has a side to be clicked from.
pub const SLIVER: f64 = 0.01;

/// Assumed for a glyph the imported font doesn't cover. Most Latin glyphs are 6 (5 + gap).
const UNKNOWN_ADVANCE: i32 = 6;

/// Full-width CJK glyphs.
const WIDE_ADVANCE: i32 = 9;

fn is_wide(code: u32) -> bool {
    matches!(code,
        0x1100..=0x115F
        | 0x2E80..=0xA4CF
        | 0xAC00..=0xD7A3
        | 0xF900..=0xFAFF
        | 0xFE30..=0xFE4F
        | 0xFF00..=0xFF60
        | 0xFFE0..=0xFFE6)
}

/// One rendered line: its width in pixels and the part of the source it shows
/// (`start` inclusive, `end` exclusive, tags included), in UTF-16 code units.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
pub struct Line {
    pub width: i32,
    pub start: usize,
    pub end: usize,
}

/// A glyph's width, whether it's a space, and where it is in the source (`at` to `end`).
struct Glyph {
    width: i32,
    is_space: bool,
    at: usize,
    end: usize,
}

/// A source line before wrapping: its glyphs and where it starts and ends in the source.
struct SourceLine {
    glyphs: Vec<Glyph>,
    start: usize,
    end: usize,
}

/// The editor's layout of MiniMessage text.
pub struct TextMetrics<'a> {
    /// A glyph's advance in pixels, including the gap after it.
    advance: CharAdvance<'a>,
    /// A pack glyph's advance by reference (`ui:coin`), for `<glyph:ui:coin>`
    /// tags; `None` for a glyph the packs don't have, which the game draws as
    /// nothing.
    glyph: GlyphAdvance<'a>,
}

impl<'a> TextMetrics<'a> {
    pub fn new(advance: impl Fn(u32) -> Option<i32> + 'a) -> Self {
        TextMetrics {
            advance: Box::new(advance),
            glyph: Box::new(|_| None),
        }
    }

    /// With a pack glyph's advance by reference.
    pub fn with_glyphs(mut self, glyph: impl Fn(&str) -> Option<i32> + 'a) -> Self {
        self.glyph = Box::new(glyph);
        self
    }

    /// Width of every rendered line of `text`, wrapped greedily at
    /// `line_width` (at the last space that fits, mid-word if one word
    /// doesn't; 0 or less doesn't wrap). Tags measure as nothing except
    /// `<bold>`, which costs a pixel per glyph, `<newline>`/`<br>`,
    /// `<glyph:pack:key>`, which is that glyph's advance, and `<space:N>`,
    /// which is N; what an `<at>` holds is nothing. Always at least one line.
    pub fn line_widths(&self, text: &str, line_width: i32) -> Vec<i32> {
        self.lines(text, line_width)
            .into_iter()
            .map(|l| l.width)
            .collect()
    }

    /// The rendered lines of `text`, as `line_widths` wraps them, each with
    /// the range of `text` it shows, so a preview can draw exactly the lines
    /// the game will.
    pub fn lines(&self, text: &str, line_width: i32) -> Vec<Line> {
        let mut out = Vec::new();
        for line in self.measure(text) {
            wrap(&line, line_width, &mut out);
        }
        out
    }

    /// The quad a `fixed` text display fills, in its node's space, or `None`
    /// for any other billboard (those turn to face each viewer, so they have
    /// no one shape). Centred across the origin and standing on it; a sliver
    /// deep on the -Z side the text faces.
    pub fn display_box(
        &self,
        text: &str,
        billboard: Option<Billboard>,
        line_width: Option<i32>,
    ) -> Option<BoundingBox> {
        if billboard.unwrap_or(DEFAULT_BILLBOARD) != Billboard::Fixed {
            return None;
        }
        let widths = self.line_widths(text, line_width.unwrap_or(DEFAULT_LINE_WIDTH));
        let widest = widths.iter().copied().max().unwrap_or(0);
        let half_width = f64::from(widest) * BLOCKS_PER_PIXEL / 2.0;
        let height = widths.len() as f64 * f64::from(LINE_HEIGHT) * BLOCKS_PER_PIXEL;
        Some(BoundingBox {
            min: Vec3(-half_width, 0.0, -SLIVER),
            max: Vec3(
                half_width + f64::from(PADDING) * BLOCKS_PER_PIXEL,
                height,
                0.0,
            ),
        })
    }

    fn width(&self, code: u32, bold: bool) -> i32 {
        let base = fonts::space_advance(code)
            .or_else(|| (self.advance)(code))
            .unwrap_or(if is_wide(code) {
                WIDE_ADVANCE
            } else {
                UNKNOWN_ADVANCE
            });
        if bold { base + 1 } else { base }
    }

    /// MiniMessage flattened to glyph widths (the pass's pieces), split at
    /// line breaks. What an `<at>` holds takes no width (it's drawn over the
    /// line), and a `<space:N>` is N.
    fn measure(&self, input: &str) -> Vec<SourceLine> {
        let length = super::utf16_len(input);
        let mut lines = vec![SourceLine {
            glyphs: Vec::new(),
            start: 0,
            end: length,
        }];
        let mut overlays = 0;
        for piece in pieces(input) {
            let last = lines.last_mut().expect("there's always a line");
            match piece {
                Piece::Overlay { .. } => overlays += 1,
                Piece::OverlayEnd { .. } => overlays -= 1,
                Piece::Move { px, at, end } => last.glyphs.push(Glyph {
                    width: if overlays > 0 { 0 } else { px },
                    is_space: false,
                    at,
                    end,
                }),
                Piece::Break { at, end } => {
                    last.end = at;
                    lines.push(SourceLine {
                        glyphs: Vec::new(),
                        start: end,
                        end: length,
                    });
                }
                // A pack glyph is never drawn bold: it's a picture, maybe in pieces (`fonts::picture`).
                Piece::Glyph {
                    reference, at, end, ..
                } => {
                    if let Some(width) = (self.glyph)(&reference) {
                        last.glyphs.push(Glyph {
                            width: if overlays > 0 { 0 } else { width },
                            is_space: false,
                            at,
                            end,
                        });
                    }
                }
                Piece::Char {
                    code,
                    at,
                    end,
                    style,
                    ..
                } => {
                    let width = if overlays > 0 {
                        0
                    } else {
                        self.width(code, style.bold == Some(true))
                    };
                    last.glyphs.push(Glyph {
                        width,
                        is_space: code == u32::from(' ') && overlays == 0,
                        at,
                        end,
                    });
                }
            }
        }
        lines
    }
}

fn wrap(source: &SourceLine, line_width: i32, out: &mut Vec<Line>) {
    let line = &source.glyphs;
    let sum = |from: usize, to: usize| line[from..to].iter().map(|g| g.width).sum::<i32>();
    // A wrapped line shows the source from where its first glyph's tags start.
    let mut emit = |from: usize, to: usize, start: usize, end: usize| {
        out.push(Line {
            width: sum(from, to),
            start,
            end,
        })
    };
    if line_width <= 0 {
        emit(0, line.len(), source.start, source.end);
        return;
    }
    let mut start = 0;
    let mut source_start = source.start;
    let mut width = 0;
    let mut last_space: Option<usize> = None;
    for index in 0..line.len() {
        let advance = line[index].width;
        if width + advance > line_width && index > start {
            let at_space = last_space.filter(|&s| s >= start);
            let break_at = at_space.unwrap_or(index);
            // A breaking space is dropped: it ends one line and starts neither.
            emit(start, break_at, source_start, line[break_at].at);
            if at_space.is_some() {
                start = break_at + 1;
                source_start = line[break_at].end;
            } else {
                start = break_at;
                source_start = line[break_at].at;
            }
            last_space = None;
            width = sum(start, index);
        }
        if line[index].is_space {
            last_space = Some(index);
        }
        width += advance;
    }
    emit(start, line.len(), source_start, source.end);
}
