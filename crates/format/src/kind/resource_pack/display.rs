//! How rect and image displays (and anchored text) are drawn by a text display,
//! to the pixel (format's `DisplayGlyphs`): the engine's own glyphs in the
//! default font, the text that draws each, and where on the entity that text
//! lands.
//!
//! **The client's geometry** (what `TextMetrics` already says, from the game's
//! text display renderer): a font pixel is `BLOCKS_PER_PIXEL` blocks; a display
//! of `n` lines whose widest is `l` pixels (advances, the gap after each glyph
//! included) shifts its text `1 - l/2` pixels across and stands it on the
//! origin, `10n - 1` pixels of lines above; line `k` starts `10k` pixels down
//! from the top; a bitmap glyph's top is `7 - ascent` pixels below its line's
//! top; and the background reaches from one pixel left of the text and one
//! above the first line to the right end of the text and the bottom of the last
//! line, so from `-l/2` to `l/2 + 1` across and from 0 to `10n` up.
//!
//! **Pixels.** A pixel glyph is a white bitmap 2^k by 1 drawn `PIXEL_HEIGHT`
//! tall (one line exactly) with ascent 7, so its top is its line's top and it
//! fills the line; it's `PIXEL` font pixels wide per picture pixel and advances
//! one more, which a one-pixel move back takes away. So a run of `n` pixels of
//! one colour is a glyph per set bit of `n`, tinted by the text colour, and a
//! row is exactly `10 × width` pixels: rows meet without a gap and pixels are
//! square. A transparent run is a move. Every row is padded to the full width,
//! so the alignment changes nothing.

use super::fonts::{MAX_OFFSET, offset};
use crate::image::Pixels;
use crate::kind::centity::Anchor;
use crate::math::Matrix4;
use crate::text::metrics::{BLOCKS_PER_PIXEL as P, LINE_HEIGHT};

/// The engine's pixel glyphs: run `2^k` is this plus `k`. Above the space characters (`fonts`).
pub const PIXEL_START: u32 = 0xF840;

/// Run glyphs: 1, 2, 4, … 128 pixels.
pub const RUNS: u32 = 8;

/// One picture pixel, in font pixels, across and down: a whole line.
pub const PIXEL: i32 = LINE_HEIGHT;

/// A pixel glyph's drawn height: a line.
pub const PIXEL_HEIGHT: i32 = LINE_HEIGHT;

/// Ascent that puts a glyph's top at its line's top.
pub const TOP_ASCENT: i32 = 7;

/// Where the pixel glyphs' pictures are, as resource keys `netherforge:font/pixel_<n>.png`.
pub const NAMESPACE: &str = "netherforge";

/// The move a rect's text is: its width in pixels, one space character.
const RECT_ADVANCE: i32 = 8;

/// The character of the run glyph for `2^k` pixels.
pub fn run_char(k: u32) -> char {
    char::from_u32(PIXEL_START + k).expect("a private-use character")
}

/// Every file the pixel glyphs need in a pack, path to bytes: one white picture per run.
pub fn pictures() -> Vec<(String, Vec<u8>)> {
    (0..RUNS)
        .map(|k| {
            let width = 1u32 << k;
            let white = vec![u32::MAX; width as usize];
            (
                format!("assets/{NAMESPACE}/textures/font/pixel_{width}.png"),
                crate::png::write(width, 1, &white),
            )
        })
        .collect()
}

/// Each run glyph's provider: its picture's resource key and its character.
pub fn providers() -> Vec<(String, char)> {
    (0..RUNS)
        .map(|k| {
            (
                format!("{NAMESPACE}:font/pixel_{}.png", 1u32 << k),
                run_char(k),
            )
        })
        .collect()
}

/// A rect's text: a move of `RECT_ADVANCE` pixels, so the background is that plus one across and a line tall.
pub fn rect_text() -> String {
    offset(RECT_ADVANCE)
}

/// A run of an image's text: characters, in one colour (`rgb`, `0xRRGGBB`), or none for moves and line breaks.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct TextRun {
    pub text: String,
    pub rgb: Option<u32>,
}

/// MiniMessage that draws `pixels`: `image_runs` written out, each colour run in a `<color>` of its own.
pub fn image_text(width: u32, height: u32, pixels: Option<&Pixels>) -> String {
    let mut out = String::new();
    for run in image_runs(width, height, pixels) {
        match run.rgb {
            None => out.push_str(&run.text),
            Some(rgb) => {
                out.push_str(&format!("<color:#{rgb:06x}>"));
                out.push_str(&run.text);
                out.push_str("</color>");
            }
        }
    }
    out
}

/// What draws `pixels`, run by run: a line per row (each break a run of its
/// own), each colour run its glyphs tinted `rgb`, pixels below `OPAQUE_FROM`
/// alpha left out as moves. No pixels (a blank image) are rows of moves only.
/// No run holds more than a row's worth of characters, so each is a short
/// piece of text.
pub fn image_runs(width: u32, height: u32, pixels: Option<&Pixels>) -> Vec<TextRun> {
    let at = |x: u32, y: u32| {
        pixels
            .filter(|p| x < p.width() && y < p.height())
            .map(|p| p.get(x, y))
            .filter(|&c| Pixels::opaque(c))
    };
    let back = offset(-1);
    let mut out = Vec::new();
    for y in 0..height {
        if y > 0 {
            out.push(TextRun {
                text: "\n".into(),
                rgb: None,
            });
        }
        let mut x = 0;
        while x < width {
            let color = at(x, y);
            let mut end = x + 1;
            while end < width {
                let next = at(end, y);
                if next.is_some() != color.is_some()
                    || matches!((color, next), (Some(c), Some(n)) if c & 0xFFFFFF != n & 0xFFFFFF)
                {
                    break;
                }
                end += 1;
            }
            let run = end - x;
            let mut text = String::new();
            if color.is_some() {
                for k in (0..RUNS).rev() {
                    let glyph = 1u32 << k;
                    // A run longer than the biggest glyph uses it as many times as it takes.
                    let times = if k == RUNS - 1 {
                        run / glyph
                    } else {
                        u32::from(run & glyph != 0)
                    };
                    for _ in 0..times {
                        text.push(run_char(k));
                        text.push_str(&back);
                    }
                }
            } else {
                let mut left = run as i32 * PIXEL;
                while left > 0 {
                    let step = left.min(MAX_OFFSET);
                    text.push_str(&offset(step));
                    left -= step;
                }
            }
            out.push(TextRun {
                text,
                rgb: color.map(|c| c & 0xFFFFFF),
            });
            x = end;
        }
    }
    out
}

/// The line width an image's text needs so no row ever wraps.
pub fn image_line_width(width: i32) -> i32 {
    width * PIXEL + PIXEL
}

/// A box in the display's own space, in blocks: where its text draws, before any fitting.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct DisplayBox {
    pub min_x: f64,
    pub min_y: f64,
    pub max_x: f64,
    pub max_y: f64,
}

impl DisplayBox {
    pub fn width(&self) -> f64 {
        self.max_x - self.min_x
    }

    pub fn height(&self) -> f64 {
        self.max_y - self.min_y
    }
}

/// Where a rect's background draws: `rect_text()` is the one line's whole text.
pub const RECT_BOX: DisplayBox = DisplayBox {
    min_x: -(RECT_ADVANCE as f64) / 2.0 * P,
    min_y: 0.0,
    max_x: (RECT_ADVANCE as f64 / 2.0 + 1.0) * P,
    max_y: LINE_HEIGHT as f64 * P,
};

/// Where an image's pixels draw: rows from the top of the first line, one pixel low of the background's.
pub fn image_box(width: i32, height: i32) -> DisplayBox {
    let l = f64::from(width * PIXEL);
    let m = f64::from(height * LINE_HEIGHT - 1);
    DisplayBox {
        min_x: (1.0 - l / 2.0) * P,
        min_y: (m - f64::from(height * PIXEL)) * P,
        max_x: (1.0 + l / 2.0) * P,
        max_y: m * P,
    }
}

/// Where text `line_width` pixels wide (its widest line) in `lines` lines
/// draws: its advances across, and from the top of its first line (a glyph of
/// ascent 7 has its top there) to the bottom of its last, which is a pixel
/// inside the background at the top and a pixel past it at the bottom, as an
/// image's rows are.
pub fn text_box(line_width: i32, lines: i32) -> DisplayBox {
    let m = f64::from(lines * LINE_HEIGHT - 1);
    let l = f64::from(line_width);
    DisplayBox {
        min_x: (1.0 - l / 2.0) * P,
        min_y: (m - f64::from(lines * LINE_HEIGHT)) * P,
        max_x: (1.0 + l / 2.0) * P,
        max_y: m * P,
    }
}

/// The matrix, in the display's own space, that puts `bounds` where `anchor`
/// says on a rectangle `width` by `height` blocks around the origin (stretched
/// to it: no `width` and `height` keep the box's own size).
pub fn fit(bounds: DisplayBox, anchor: Anchor, width: Option<f64>, height: Option<f64>) -> Matrix4 {
    let w = width.unwrap_or(bounds.width());
    let h = height.unwrap_or(bounds.height());
    let (ax, ay) = anchor.at();
    let mut m = Matrix4::IDENTITY;
    m.translate(-ax * w, -ay * h, 0.0)
        .scale(w / bounds.width(), h / bounds.height(), 1.0)
        .translate(-bounds.min_x, -bounds.min_y, 0.0);
    m
}
