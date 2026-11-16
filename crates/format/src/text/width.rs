//! The pixel width MiniMessage text draws at in the default font, exactly or
//! not at all (format's `TextWidth`): for the server, which lays out menus and
//! text displays with it (`nf.text.width`) and must never guess.
//!
//! Unlike `TextMetrics` (the editor's previews, which estimate what they don't
//! know), anything this can't measure exactly makes the whole answer `None`: a
//! character the advances don't cover, a glyph tag whose advance is unknown, a
//! tag that inserts text only the client resolves (`<lang>`, `<key>`,
//! `<score>`…), or a font other than the default. It's deliberately not built
//! on `TextMetrics` or the MiniMessage pass: see `metrics` for why the two
//! measurers stay apart.

use std::borrow::Cow;

use super::{CharAdvance, GlyphAdvance, glyph, space};
use crate::font::DefaultFontFile;
use crate::kind::resource_pack::{MAX_OFFSET, fonts};

/// The server's measure of MiniMessage text.
pub struct TextWidth<'a> {
    /// A character's advance by code point, gap included (from the default font file).
    advance: CharAdvance<'a>,
    /// A pack glyph's bare character's advance by code point (what `nf.text.glyph` gives, which takes the
    /// text's colour where the tag doesn't), or a wide glyph's piece's.
    glyph_char: CharAdvance<'a>,
    /// A pack glyph's advance by reference (`ui:coin`), for `<glyph:ui:coin>`.
    glyph: GlyphAdvance<'a>,
}

/// What `layout` made of a text: the MiniMessage to parse, and the `<at>` tags it couldn't place.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Layout<'t> {
    pub text: Cow<'t, str>,
    pub unplaced: Vec<String>,
}

impl<'a> TextWidth<'a> {
    pub fn new(advance: impl Fn(u32) -> Option<i32> + 'a) -> Self {
        TextWidth {
            advance: Box::new(advance),
            glyph_char: Box::new(|_| None),
            glyph: Box::new(|_| None),
        }
    }

    /// Measuring with `font`'s advances.
    pub fn of_font(font: &'a DefaultFontFile) -> Self {
        Self::new(|code| font.advance(code))
    }

    /// With a pack glyph's advance by reference.
    pub fn with_glyphs(mut self, glyph: impl Fn(&str) -> Option<i32> + 'a) -> Self {
        self.glyph = Box::new(glyph);
        self
    }

    /// With a pack glyph's bare character's advance by code point.
    pub fn with_glyph_chars(mut self, glyph_char: impl Fn(u32) -> Option<i32> + 'a) -> Self {
        self.glyph_char = Box::new(glyph_char);
        self
    }

    /// The width of `text` in pixels, or `None` when it can't be measured
    /// exactly. Tags draw nothing, except `<bold>` (a pixel more per
    /// character, as the game draws bold), `<glyph:pack:key>` (the glyph's
    /// advance), `<space:N>` (N, bold or not) and `<at:N>…</at>` (nothing:
    /// what it holds is drawn over the line). The pack's space characters
    /// measure as the moves they are, and a glyph's bare characters as the
    /// glyph (bold like any character, as the game draws them). Text with line
    /// breaks (`\n`, `<newline>`, `<br>`) measures as its widest line. A tag
    /// MiniMessage doesn't know is drawn as written, so it's measured as text;
    /// `\<` is a literal `<`.
    pub fn measure(&self, text: &str) -> Option<i32> {
        let walk = self.walk(&units(text));
        if walk.lines.iter().any(|l| !l.known) {
            return None;
        }
        Some(walk.lines.iter().map(|l| l.width).max().unwrap_or(0).max(0))
    }

    /// How many lines `text` draws as when nothing wraps it: one more than its line breaks.
    pub fn line_count(&self, text: &str) -> usize {
        self.walk(&units(text)).lines.len()
    }

    /// `text` with every `<at>` turned into the moves that place it: a
    /// `<space>` before the tag (from the cursor to where the inside starts)
    /// and one where it closes (back to where the cursor was), which is what
    /// the server parses. An `<at>` that can't be placed exactly (its inside,
    /// or the line it's counted from, can't be measured) is left as it is,
    /// which draws its inside in line, and is listed in `unplaced`.
    pub fn layout<'t>(&self, text: &'t str) -> Layout<'t> {
        if !text.to_lowercase().contains(&format!("<{}", space::AT)) {
            return Layout {
                text: Cow::Borrowed(text),
                unplaced: Vec::new(),
            };
        }
        let source = units(text);
        let walk = self.walk(&source);
        let mut inserts: Vec<(usize, String)> = Vec::new();
        let mut unplaced = Vec::new();
        for at in &walk.ats {
            match (lead(at, &walk.lines), at.inner) {
                (Some(lead), Some(inner)) if at.known => {
                    inserts.push((at.open_at, moves(lead)));
                    inserts.push((at.close_at, moves(-(lead + inner))));
                }
                _ => unplaced.push(at.tag.clone()),
            }
        }
        if inserts.is_empty() {
            return Layout {
                text: Cow::Borrowed(text),
                unplaced,
            };
        }
        inserts.sort_by_key(|(at, _)| *at);
        let mut out = String::new();
        let mut cursor = 0;
        for (at, moves) in inserts {
            out.push_str(&String::from_utf16_lossy(&source[cursor..at]));
            out.push_str(&moves);
            cursor = at;
        }
        out.push_str(&String::from_utf16_lossy(&source[cursor..]));
        Layout {
            text: Cow::Owned(out),
            unplaced,
        }
    }

    /// Every line of `text` measured, and every `<at>` in it, with where it closes and how wide its inside is.
    fn walk(&self, text: &[u16]) -> Walk {
        let mut w = Walker {
            open: Vec::new(),
            lines: vec![Line::default()],
            ats: Vec::new(),
        };
        let mut cursor = 0;
        while cursor < text.len() {
            let c = text[cursor];
            if c == BACKSLASH
                && cursor + 1 < text.len()
                && (text[cursor + 1] == LT || text[cursor + 1] == BACKSLASH)
            {
                self.char(&mut w, u32::from(text[cursor + 1]));
                cursor += 2;
                continue;
            }
            if c == NL {
                w.lines.push(Line::default());
                cursor += 1;
                continue;
            }
            if c == LT
                && let Some(end) = text[cursor..]
                    .iter()
                    .position(|&u| u == GT)
                    .map(|i| cursor + i)
            {
                let inside = String::from_utf16_lossy(&text[cursor + 1..end]);
                if let Some(tag) = Tag::parse(&inside) {
                    let kind = tag.kind(&inside);
                    if kind != Kind::Literal {
                        self.tag(&mut w, &tag, kind, &inside, cursor);
                        cursor = end + 1;
                        continue;
                    }
                }
            }
            let (code, size) = super::code_point(text, cursor);
            self.char(&mut w, code);
            cursor += size;
        }
        w.close(0, text.len());
        Walk {
            lines: w.lines,
            ats: w.ats,
        }
    }

    fn char(&self, w: &mut Walker, code: u32) {
        let Some(width) = fonts::space_advance(code)
            .or_else(|| (self.glyph_char)(code))
            .or_else(|| (self.advance)(code))
        else {
            return w.unknown();
        };
        let bold = w.bold();
        w.line().width += if bold { width + 1 } else { width };
    }

    fn tag(&self, w: &mut Walker, tag: &Tag, kind: Kind, inside: &str, cursor: usize) {
        match kind {
            Kind::Break => w.lines.push(Line::default()),
            // A pack glyph is never drawn bold: it's a picture, maybe in pieces (`fonts::picture`).
            Kind::Glyph => match glyph::reference(inside).and_then(|r| (self.glyph)(&r)) {
                Some(width) => w.line().width += width,
                None => w.unknown(),
            },
            Kind::Space => {
                if let Some(px) = space::space(&space::arguments(inside)) {
                    w.line().width += px;
                }
            }
            Kind::At => {
                let frame = space::at(&space::arguments(inside)).map(|spec| {
                    let line = w.lines.len() - 1;
                    w.ats.push(Frame {
                        spec,
                        open_at: cursor,
                        tag: format!("<{inside}>"),
                        line,
                        cursor: w.lines[line].width,
                        cursor_known: w.lines[line].known,
                        close_at: 0,
                        inner: None,
                        known: true,
                    });
                    w.ats.len() - 1
                });
                let bold = w.bold();
                w.open.push(Open {
                    name: tag.name.clone(),
                    bold,
                    frame,
                });
            }
            Kind::Unmeasurable => w.unknown(),
            Kind::Reset => w.close(0, cursor),
            Kind::Style => {
                if tag.closing {
                    // A closing tag closes the latest one of its name and everything opened inside it.
                    let at = if tag.name.is_empty() {
                        w.open.len().checked_sub(1)
                    } else {
                        w.open.iter().rposition(|o| o.name == tag.name)
                    };
                    if let Some(at) = at {
                        w.close(at, cursor);
                    }
                } else {
                    let bold = tag.bold.unwrap_or_else(|| w.bold());
                    w.open.push(Open {
                        name: tag.name.clone(),
                        bold,
                        frame: None,
                    });
                }
            }
            Kind::Literal => {}
        }
    }
}

const LT: u16 = b'<' as u16;
const GT: u16 = b'>' as u16;
const NL: u16 = b'\n' as u16;
const BACKSLASH: u16 = b'\\' as u16;

fn units(text: &str) -> Vec<u16> {
    text.encode_utf16().collect()
}

/// How far the cursor moves to where `at`'s inside starts; `None` when that can't be known.
fn lead(at: &Frame, lines: &[Line]) -> Option<i32> {
    let inner = at.inner?;
    let line = &lines[at.line];
    let anchor = match at.spec.from {
        space::From::Cursor => return Some(at.spec.x - shift(at.spec.align, inner)),
        space::From::Start => 0,
        // The game centres on the whole line's width, halved in integers.
        space::From::Center if line.known => line.width / 2,
        space::From::End if line.known => line.width,
        _ => return None,
    };
    if !at.cursor_known {
        return None;
    }
    Some(anchor + at.spec.x - shift(at.spec.align, inner) - at.cursor)
}

fn shift(align: space::Align, inner: i32) -> i32 {
    match align {
        space::Align::Left => 0,
        space::Align::Center => inner / 2,
        space::Align::Right => inner,
    }
}

/// `<space>` tags moving `px`, as many as it takes past `MAX_OFFSET`; empty for zero.
fn moves(px: i32) -> String {
    let mut out = String::new();
    let mut left = px;
    while left != 0 {
        let step = left.clamp(-MAX_OFFSET, MAX_OFFSET);
        out.push_str(&space::tag(step));
        left -= step;
    }
    out
}

/// One line as walked: its width so far (at the end, its width), and whether every part of it measured.
struct Line {
    width: i32,
    known: bool,
}

impl Default for Line {
    fn default() -> Self {
        Line {
            width: 0,
            known: true,
        }
    }
}

/// An `<at>` as walked: what it says, where its tag starts (`open_at`), the
/// tag as written, the line it's on (by index) and the cursor there; once it
/// closes, where (`close_at`: the closing tag's `<`, or the text's end) and its
/// inside's width. `known` is false once anything inside it couldn't be
/// measured.
struct Frame {
    spec: space::At,
    open_at: usize,
    tag: String,
    line: usize,
    cursor: i32,
    cursor_known: bool,
    close_at: usize,
    inner: Option<i32>,
    known: bool,
}

/// An open tag: its name, whether it leaves text bold, and the `<at>` it is (by index), if it's one that moves.
struct Open {
    name: String,
    bold: bool,
    frame: Option<usize>,
}

struct Walk {
    lines: Vec<Line>,
    ats: Vec<Frame>,
}

struct Walker {
    open: Vec<Open>,
    lines: Vec<Line>,
    ats: Vec<Frame>,
}

impl Walker {
    fn line(&mut self) -> &mut Line {
        self.lines.last_mut().expect("there's always a line")
    }

    fn bold(&self) -> bool {
        self.open.last().is_some_and(|o| o.bold)
    }

    fn unknown(&mut self) {
        self.line().known = false;
        for open in &self.open {
            if let Some(frame) = open.frame {
                self.ats[frame].known = false;
            }
        }
    }

    /// Closes the tags open from `from` on, innermost first: an `<at>` gives its inside's width back.
    fn close(&mut self, from: usize, at: usize) {
        let current = self.lines.len() - 1;
        while self.open.len() > from {
            let Some(index) = self.open.pop().and_then(|o| o.frame) else {
                continue;
            };
            let frame = &mut self.ats[index];
            frame.close_at = at;
            if frame.line != current {
                frame.known = false;
                continue;
            }
            let line = &mut self.lines[current];
            frame.inner = Some(line.width - frame.cursor);
            line.width = frame.cursor;
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Kind {
    Style,
    Break,
    Glyph,
    Space,
    At,
    Reset,
    Unmeasurable,
    Literal,
}

/// Tags that only style, open and close, and draw nothing of their own.
const STYLES: &[&str] = &[
    "black",
    "dark_blue",
    "dark_green",
    "dark_aqua",
    "dark_red",
    "dark_purple",
    "gold",
    "gray",
    "grey",
    "dark_gray",
    "dark_grey",
    "blue",
    "green",
    "aqua",
    "red",
    "light_purple",
    "yellow",
    "white",
    "color",
    "colour",
    "c",
    "shadow",
    "bold",
    "b",
    "italic",
    "em",
    "i",
    "underlined",
    "u",
    "strikethrough",
    "st",
    "obfuscated",
    "obf",
    "click",
    "hover",
    "insert",
    "insertion",
    "rainbow",
    "gradient",
    "transition",
    "pride",
    "font",
];

/// Tags whose text only the client knows (a translation, a key binding, a score…): nothing to measure.
const INSERTS: &[&str] = &[
    "key",
    "lang",
    "tr",
    "translate",
    "lang_or",
    "tr_or",
    "translate_or",
    "selector",
    "sel",
    "score",
    "nbt",
    "data",
    "sprite",
    "head",
];

const DEFAULT_FONTS: &[&str] = &["default", "minecraft:default"];

/// NetherForge's own tags, which a closing tag of the same name closes.
const OWN: &[&str] = &[glyph::NAME, space::SPACE, space::AT];

/// A tag's name (lowercase, without `/` or `!`), whether it closes, and whether it turns bold on or off.
struct Tag {
    name: String,
    closing: bool,
    bold: Option<bool>,
}

fn is_lower_hex(name: &str) -> bool {
    name.len() == 7
        && name.starts_with('#')
        && name[1..]
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

impl Tag {
    fn parse(inside: &str) -> Option<Tag> {
        let mut rest = inside;
        let closing = rest.starts_with('/');
        if closing {
            rest = &rest[1..];
        }
        let negated = !closing && rest.starts_with('!');
        if negated {
            rest = &rest[1..];
        }
        let name = rest.split(':').next().unwrap_or("").to_lowercase();
        if name.is_empty() {
            return (closing && rest.is_empty()).then_some(Tag {
                name,
                closing: true,
                bold: None,
            });
        }
        if !name
            .bytes()
            .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b"_#-".contains(&b))
        {
            return None;
        }
        let bold = if name != "bold" && name != "b" {
            None
        } else if negated {
            Some(false)
        } else {
            let after = rest.split_once(':').map_or("true", |(_, a)| a);
            Some(after.to_lowercase() != "false")
        };
        Some(Tag {
            name,
            closing,
            bold,
        })
    }

    fn kind(&self, inside: &str) -> Kind {
        let name = self.name.as_str();
        if self.closing {
            return if name.is_empty()
                || STYLES.contains(&name)
                || name.starts_with('#')
                || OWN.contains(&name)
            {
                Kind::Style
            } else {
                Kind::Literal
            };
        }
        match name {
            "newline" | "br" => Kind::Break,
            glyph::NAME => Kind::Glyph,
            space::SPACE => Kind::Space,
            space::AT => Kind::At,
            "reset" => Kind::Reset,
            "font" => {
                let font =
                    glyph::arguments(inside.split_once(':').map_or("", |(_, a)| a)).join(":");
                if DEFAULT_FONTS.contains(&font.as_str()) {
                    Kind::Style
                } else {
                    Kind::Unmeasurable
                }
            }
            _ if INSERTS.contains(&name) => Kind::Unmeasurable,
            _ if STYLES.contains(&name) || is_lower_hex(name) => Kind::Style,
            _ => Kind::Literal,
        }
    }
}
