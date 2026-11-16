//! Text on screens (Kotlin's `ScreenFont`, `Glyph`, `TextLayout` and `ScreenText`): MiniMessage's colours and
//! decorations, broken into lines, measured and drawn in map colours. Fonts are the game's own map font (as the
//! server has it), or a project's: pixels written in its file, a bitmap's grid of characters, or a TrueType file
//! (rasterised by `fontdue` where Kotlin used AWT, so its pixels differ a little; nothing holds them to Kotlin's).

use std::cell::RefCell;
use std::collections::HashMap;

use netherforge_format::kind::screen_font::{
    BLANK, DEFAULT_SIZE, INK, ScreenFontFile, ScreenFontType,
};
use netherforge_format::map_colors::{Palette, parse};
use netherforge_format::text::minimessage::{Piece, pieces};
use netherforge_platform::MapFont;

use super::canvas::Canvas;

/// One character's picture: how much of each pixel it covers (0 to 255, row by row), and how far it moves the pen.
#[derive(Clone, Debug)]
pub struct Glyph {
    pub width: i32,
    pub height: i32,
    pub advance: i32,
    pub coverage: Vec<u8>,
}

type Lookup = Box<dyn Fn(u32) -> Option<Glyph>>;

/// A font text is drawn in: `height` pixels from a line's top to its characters' bottom, `line_height` from one
/// line's top to the next's, `spacing` between characters, `space_width` for a space; `antialias` blends edges.
pub struct ScreenFont {
    pub height: i32,
    pub line_height: i32,
    pub spacing: i32,
    pub space_width: i32,
    pub antialias: bool,
    lookup: Lookup,
    glyphs: RefCell<HashMap<u32, Option<Glyph>>>,
}

impl ScreenFont {
    fn new(
        height: i32,
        line_height: i32,
        spacing: i32,
        space_width: i32,
        antialias: bool,
        lookup: Lookup,
    ) -> ScreenFont {
        ScreenFont {
            height,
            line_height,
            spacing,
            space_width,
            antialias,
            lookup,
            glyphs: RefCell::default(),
        }
    }

    /// The character `code`, or none when the font has none (it then draws nothing and takes no room).
    pub fn glyph(&self, code: u32) -> Option<Glyph> {
        if let Some(known) = self.glyphs.borrow().get(&code) {
            return known.clone();
        }
        let found = (self.lookup)(code);
        self.glyphs.borrow_mut().insert(code, found.clone());
        found
    }

    /// How far `code` moves the pen, its spacing after it included (a space's width for a space).
    pub fn advance(&self, code: u32, bold: bool) -> i32 {
        if code == u32::from(' ') {
            return self.space_width + self.spacing;
        }
        self.glyph(code)
            .map_or(0, |g| g.advance + self.spacing + i32::from(bold))
    }

    /// The game's own map font, as the server has it.
    pub fn of_game(font: &MapFont) -> ScreenFont {
        let glyphs: HashMap<u32, Glyph> = font
            .glyphs
            .iter()
            .map(|(code, g)| {
                let coverage = g.pixels.iter().map(|p| if *p { 255 } else { 0 }).collect();
                (
                    *code as u32,
                    Glyph {
                        width: g.width,
                        height: g.height,
                        advance: g.width,
                        coverage,
                    },
                )
            })
            .collect();
        let space = glyphs
            .get(&u32::from(' '))
            .map_or((font.height / 3).max(1), |g| g.advance);
        ScreenFont::new(
            font.height,
            font.height + 1,
            1,
            space,
            false,
            Box::new(move |code| glyphs.get(&code).cloned()),
        )
    }

    /// A project's font from its file and the bytes of the picture or font file it names (none for a pixels font).
    pub fn of_file(file: &ScreenFontFile, bytes: Option<&[u8]>) -> Result<ScreenFont, String> {
        match file.kind {
            ScreenFontType::Pixels => Ok(pixels(file)),
            ScreenFontType::Bitmap => bitmap(file, bytes.ok_or("a bitmap font needs its picture")?),
            ScreenFontType::Truetype => {
                true_type(file, bytes.ok_or("a TrueType font needs its file")?)
            }
        }
    }
}

fn pixels(file: &ScreenFontFile) -> ScreenFont {
    let mut glyphs: HashMap<u32, Glyph> = HashMap::new();
    for (key, rows) in file.glyphs.iter().flat_map(|g| g.iter()) {
        let mut chars = key.chars();
        let (Some(c), None) = (chars.next(), chars.next()) else {
            continue;
        };
        let width = rows.first().map_or(0, |r| r.chars().count()) as i32;
        let coverage = (0..width as usize * rows.len())
            .map(|i| {
                let row: Vec<char> = rows[i / width as usize].chars().collect();
                if row.get(i % width as usize) == Some(&INK) {
                    255
                } else {
                    0
                }
            })
            .collect();
        let _ = BLANK;
        glyphs.insert(
            c as u32,
            Glyph {
                width,
                height: rows.len() as i32,
                advance: width,
                coverage,
            },
        );
    }
    let height = glyphs.values().map(|g| g.height).max().unwrap_or(1);
    ScreenFont::new(
        height,
        file.line_height.unwrap_or(height + 1),
        file.spacing.unwrap_or(1),
        file.space_width.unwrap_or((height / 3).max(1)),
        false,
        Box::new(move |code| glyphs.get(&code).cloned()),
    )
}

fn bitmap(file: &ScreenFontFile, bytes: &[u8]) -> Result<ScreenFont, String> {
    let picture = crate::images::decode(bytes).ok_or_else(|| {
        format!(
            "{} isn't a picture",
            file.file.as_deref().unwrap_or("its picture")
        )
    })?;
    let (across, down) = file.grid().ok_or("the font lists no characters")?;
    let (w, h) = (picture.width() as usize, picture.height() as usize);
    let (cell_width, cell_height) = (w / across, h / down);
    if cell_width == 0 || cell_height == 0 {
        return Err(format!(
            "{} is too small for its grid",
            file.file.as_deref().unwrap_or("its picture")
        ));
    }
    let mut cells: HashMap<u32, (usize, usize)> = HashMap::new();
    for (row, line) in file.chars.iter().flatten().enumerate() {
        for (column, c) in line.chars().enumerate() {
            if c != '\0' {
                cells.entry(c as u32).or_insert((column, row));
            }
        }
    }
    let lookup = move |code: u32| -> Option<Glyph> {
        let (column, row) = *cells.get(&code)?;
        let coverage: Vec<u8> = (0..cell_width * cell_height)
            .map(|i| {
                let x = (column * cell_width + i % cell_width) as u32;
                let y = (row * cell_height + i / cell_width) as u32;
                if picture.get(x, y) >> 24 >= 128 {
                    255
                } else {
                    0
                }
            })
            .collect();
        // As wide as its opaque columns, as the game measures a bitmap font's characters.
        let width = (0..cell_width)
            .filter(|x| (0..cell_height).any(|y| coverage[y * cell_width + x] != 0))
            .map(|x| x + 1)
            .max()
            .unwrap_or(0);
        let trimmed = (0..width * cell_height)
            .map(|i| coverage[(i / width.max(1)) * cell_width + i % width.max(1)])
            .collect();
        Some(Glyph {
            width: width as i32,
            height: cell_height as i32,
            advance: width as i32,
            coverage: trimmed,
        })
    };
    let height = cell_height as i32;
    Ok(ScreenFont::new(
        height,
        file.line_height.unwrap_or(height + 1),
        file.spacing.unwrap_or(1),
        file.space_width.unwrap_or((height / 3).max(1)),
        false,
        Box::new(lookup),
    ))
}

fn true_type(file: &ScreenFontFile, bytes: &[u8]) -> Result<ScreenFont, String> {
    let font = fontdue::Font::from_bytes(bytes, fontdue::FontSettings::default())
        .map_err(str::to_owned)?;
    let size = file.size.unwrap_or(DEFAULT_SIZE) as f32;
    let metrics = font
        .horizontal_line_metrics(size)
        .ok_or("the font has no horizontal metrics")?;
    let ascent = metrics.ascent.round() as i32;
    let height = ascent + (-metrics.descent).round() as i32;
    let antialias = file.antialias.unwrap_or(false);
    let space = font.metrics(' ', size).advance_width.round() as i32;
    let lookup = move |code: u32| -> Option<Glyph> {
        let c = char::from_u32(code)?;
        if font.lookup_glyph_index(c) == 0 {
            return None;
        }
        let (m, bitmap) = font.rasterize(c, size);
        let advance = m.advance_width.round() as i32;
        // Placed from the pen's position on a line `height` tall: what's left of the pen is dropped.
        let width = (advance.max(m.xmin + m.width as i32)).max(1);
        let mut coverage = vec![0u8; (width * height) as usize];
        let top = ascent - (m.ymin + m.height as i32);
        for gy in 0..m.height as i32 {
            for gx in 0..m.width as i32 {
                let (x, y) = (m.xmin + gx, top + gy);
                if x < 0 || y < 0 || x >= width || y >= height {
                    continue;
                }
                let a = bitmap[(gy * m.width as i32 + gx) as usize];
                coverage[(y * width + x) as usize] = if antialias {
                    a
                } else if a >= 128 {
                    255
                } else {
                    0
                };
            }
        }
        Some(Glyph {
            width,
            height,
            advance,
            coverage,
        })
    };
    Ok(ScreenFont::new(
        height,
        file.line_height.unwrap_or(height + 1),
        file.spacing.unwrap_or(1),
        file.space_width.unwrap_or(space),
        antialias,
        Box::new(lookup),
    ))
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Align {
    Left,
    Center,
    Right,
}

/// How text is laid out and drawn: what `screen:text` and `nf.screens.measure_text` take, checked.
pub struct TextLayout<'f> {
    pub font: &'f ScreenFont,
    /// Where text sets no colour: `0xRRGGBB`.
    pub color: u32,
    pub scale: i32,
    pub align: Align,
    /// Break lines at spaces past this many pixels; none for none.
    pub width: Option<i32>,
    /// A shadow's colour, or none for none.
    pub shadow: Option<u32>,
    pub line_height: Option<i32>,
}

/// One character as it's drawn, in its style.
#[derive(Clone)]
struct Mark {
    code: u32,
    color: Option<u32>,
    bold: bool,
    underlined: bool,
    strikethrough: bool,
}

struct Line {
    marks: Vec<Mark>,
    width: i32,
}

/// How big `text` is drawn with `layout`, in pixels: its widest line, and all its lines.
pub fn measure(text: &str, layout: &TextLayout) -> (i32, i32) {
    size(&lines(text, layout), layout)
}

fn size(lines: &[Line], layout: &TextLayout) -> (i32, i32) {
    if lines.is_empty() {
        return (0, 0);
    }
    let width = lines.iter().map(|l| l.width).max().unwrap_or(0) * layout.scale;
    let step = layout.line_height.unwrap_or(layout.font.line_height);
    let height = ((lines.len() as i32 - 1) * step + layout.font.height) * layout.scale;
    (width, height)
}

/// Draws `text` at (`x`, `y`) (its top, and its left, middle or right as `align` says) onto `canvas`, matching
/// colours in `palette`. Its size, as `measure` says.
pub fn draw(
    canvas: &mut Canvas,
    palette: &mut Palette,
    x: i32,
    y: i32,
    text: &str,
    layout: &TextLayout,
) -> (i32, i32) {
    let lines = lines(text, layout);
    if let Some(shadow) = layout.shadow {
        paint(
            canvas,
            palette,
            x + layout.scale,
            y + layout.scale,
            &lines,
            layout,
            Some(shadow),
        );
    }
    paint(canvas, palette, x, y, &lines, layout, None);
    size(&lines, layout)
}

fn paint(
    canvas: &mut Canvas,
    palette: &mut Palette,
    x: i32,
    y: i32,
    lines: &[Line],
    layout: &TextLayout,
    only: Option<u32>,
) {
    let font = layout.font;
    let scale = layout.scale;
    let step = layout.line_height.unwrap_or(font.line_height) * scale;
    for (index, line) in lines.iter().enumerate() {
        let top = y + index as i32 * step;
        let mut pen = match layout.align {
            Align::Left => x,
            Align::Center => x - line.width * scale / 2,
            Align::Right => x - line.width * scale,
        };
        for mark in &line.marks {
            let rgb = only.or(mark.color).unwrap_or(layout.color);
            let advance = font.advance(mark.code, mark.bold) * scale;
            if mark.code != u32::from(' ')
                && let Some(glyph) = font.glyph(mark.code)
            {
                draw_glyph(
                    canvas,
                    palette,
                    pen,
                    top,
                    &glyph,
                    rgb,
                    scale,
                    font.antialias,
                );
                if mark.bold {
                    draw_glyph(
                        canvas,
                        palette,
                        pen + scale,
                        top,
                        &glyph,
                        rgb,
                        scale,
                        font.antialias,
                    );
                }
            }
            let color = i32::from(palette.nearest(rgb));
            if mark.underlined {
                canvas.fill(pen, top + font.height * scale, advance, scale, color);
            }
            if mark.strikethrough {
                canvas.fill(pen, top + (font.height / 2) * scale, advance, scale, color);
            }
            pen += advance;
        }
    }
}

#[allow(clippy::too_many_arguments)]
fn draw_glyph(
    canvas: &mut Canvas,
    palette: &mut Palette,
    x: i32,
    y: i32,
    glyph: &Glyph,
    rgb: u32,
    scale: i32,
    antialias: bool,
) {
    let solid = i32::from(palette.nearest(rgb));
    for gy in 0..glyph.height {
        for gx in 0..glyph.width {
            let cover = glyph.coverage[(gy * glyph.width + gx) as usize];
            if cover == 0 {
                continue;
            }
            for sy in 0..scale {
                for sx in 0..scale {
                    let (px, py) = (x + gx * scale + sx, y + gy * scale + sy);
                    if !canvas.inside(px, py) {
                        continue;
                    }
                    let color = if cover == 255 || !antialias {
                        Some(solid)
                    } else {
                        blend(canvas, palette, px, py, rgb, cover)
                    };
                    if let Some(color) = color {
                        canvas.set(px, py, color);
                    }
                }
            }
        }
    }
}

/// `rgb` over the pixel at (`x`, `y`) by `cover` of 255, as the nearest map colour; none to leave it.
fn blend(
    canvas: &Canvas,
    palette: &mut Palette,
    x: i32,
    y: i32,
    rgb: u32,
    cover: u8,
) -> Option<i32> {
    let Some(under) = palette.rgb(canvas.get(x, y)) else {
        return (cover >= 128).then(|| i32::from(palette.nearest(rgb)));
    };
    let cover = u32::from(cover);
    let mix = |shift: u32| {
        (((rgb >> shift) & 0xFF) * cover + ((under >> shift) & 0xFF) * (255 - cover)) / 255
    };
    Some(i32::from(
        palette.nearest((mix(16) << 16) | (mix(8) << 8) | mix(0)),
    ))
}

/// `text` as lines of styled characters, broken at line breaks and, past `layout.width`, at spaces.
fn lines(text: &str, layout: &TextLayout) -> Vec<Line> {
    let font = layout.font;
    let width = |marks: &[Mark]| {
        (marks
            .iter()
            .map(|m| font.advance(m.code, m.bold))
            .sum::<i32>()
            - font.spacing)
            .max(0)
    };
    let mut out = Vec::new();
    let mut current: Vec<Mark> = Vec::new();
    for piece in pieces(text) {
        match piece {
            Piece::Break { .. } => {
                let marks = std::mem::take(&mut current);
                out.push(Line {
                    width: width(&marks),
                    marks,
                });
            }
            Piece::Char { code, style, .. } => current.push(Mark {
                code,
                color: style.color.as_deref().and_then(parse),
                bold: style.bold == Some(true),
                underlined: style.underlined == Some(true),
                strikethrough: style.strikethrough == Some(true),
            }),
            _ => {}
        }
    }
    out.push(Line {
        width: width(&current),
        marks: current,
    });
    let Some(wrap) = layout.width.map(|w| w / layout.scale) else {
        return out;
    };
    out.into_iter()
        .flat_map(|line| wrap_line(line, wrap, &width))
        .collect()
}

/// `line` broken at spaces so no part is wider than `limit`, a word too long for a line alone left whole.
fn wrap_line(line: Line, limit: i32, width: &dyn Fn(&[Mark]) -> i32) -> Vec<Line> {
    if line.width <= limit {
        return vec![line];
    }
    let marks = line.marks;
    let mut out = Vec::new();
    let (mut start, mut last_space, mut i) = (0usize, None::<usize>, 0usize);
    while i < marks.len() {
        if marks[i].code == u32::from(' ') {
            last_space = Some(i);
        }
        if let Some(space) = last_space.filter(|s| *s > start)
            && width(&marks[start..=i]) > limit
        {
            let kept = marks[start..space].to_vec();
            out.push(Line {
                width: width(&kept),
                marks: kept,
            });
            start = space + 1;
            last_space = None;
            i = start;
            continue;
        }
        i += 1;
    }
    let rest = marks[start..].to_vec();
    out.push(Line {
        width: width(&rest),
        marks: rest,
    });
    out
}
