//! MiniMessage as the editor's previews read it (format's `MiniMessagePass`):
//! one pass that says what each character and glyph tag draws, in which style,
//! and where lines break. `TextMetrics` measures these pieces and the editor
//! draws them, so a preview draws exactly the characters the layout wrapped.
//!
//! Every `<…>` is a tag and draws nothing, except `<glyph:ui/coin>`, which is
//! one picture and opens nothing, `<space:N>`, a move, and `<at:N>`, which
//! draws what it holds over the line (`Overlay`); `\n`, `<newline>` and `<br>`
//! break the line. Tags this knows style what follows (colours, decorations,
//! `<reset>`); the rest balance on the same stack and change nothing, so an
//! unknown pair can't leak a style. A gradient is drawn in its first colour, a
//! rainbow red. Each piece keeps where it is in the source, in UTF-16 code
//! units as the editor counts, so a line's range (from `TextMetrics::lines`)
//! cuts the same pieces.
//!
//! Not `TextWidth`'s rules: that one is the server's exact measure, where a tag
//! MiniMessage doesn't know is drawn as written.

use serde::Serialize;

use super::{glyph, space};

/// How a stretch of text looks: only what a tag set. `None` is "as the text
/// around it" (a preview's own default, say a tooltip's grey); `false` is a
/// decoration turned off (`<!italic>`, `<italic:false>`).
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize)]
pub struct TextStyle {
    /// `#rrggbb`, lower case.
    pub color: Option<String>,
    pub bold: Option<bool>,
    pub italic: Option<bool>,
    pub underlined: Option<bool>,
    pub strikethrough: Option<bool>,
    pub obfuscated: Option<bool>,
}

/// A piece of text, with where it starts in the source (`at`) and where the
/// next may start (`end`: after the character, or the tag's `>`).
#[derive(Clone, Debug, PartialEq)]
pub enum Piece {
    /// One code point, drawn: `text` is it as written (two UTF-16 units past the BMP).
    Char {
        code: u32,
        text: String,
        at: usize,
        end: usize,
        style: TextStyle,
    },
    /// A `<glyph:…>` tag: the reference it names (`ui/coin`, `acme:ui/coin`).
    Glyph {
        reference: String,
        at: usize,
        end: usize,
        style: TextStyle,
    },
    /// A line break; the next line starts at `end`.
    Break { at: usize, end: usize },
    /// A `<space:N>` tag: the cursor moves `px` pixels.
    Move { px: i32, at: usize, end: usize },
    /// An `<at>` tag that moves: the pieces up to its `OverlayEnd` draw over the line and take no width.
    Overlay {
        spec: space::At,
        at: usize,
        end: usize,
    },
    /// Where an `Overlay` closes (its closing tag, a tag closing it, `<reset>`, or the text's end).
    OverlayEnd { at: usize, end: usize },
}

impl Piece {
    pub fn at(&self) -> usize {
        match self {
            Piece::Char { at, .. }
            | Piece::Glyph { at, .. }
            | Piece::Break { at, .. }
            | Piece::Move { at, .. }
            | Piece::Overlay { at, .. }
            | Piece::OverlayEnd { at, .. } => *at,
        }
    }

    pub fn end(&self) -> usize {
        match self {
            Piece::Char { end, .. }
            | Piece::Glyph { end, .. }
            | Piece::Break { end, .. }
            | Piece::Move { end, .. }
            | Piece::Overlay { end, .. }
            | Piece::OverlayEnd { end, .. } => *end,
        }
    }
}

/// Minecraft's sixteen named colours, and the `grey` spellings.
pub const NAMED_COLORS: [(&str, &str); 18] = [
    ("black", "#000000"),
    ("dark_blue", "#0000aa"),
    ("dark_green", "#00aa00"),
    ("dark_aqua", "#00aaaa"),
    ("dark_red", "#aa0000"),
    ("dark_purple", "#aa00aa"),
    ("gold", "#ffaa00"),
    ("gray", "#aaaaaa"),
    ("grey", "#aaaaaa"),
    ("dark_gray", "#555555"),
    ("dark_grey", "#555555"),
    ("blue", "#5555ff"),
    ("green", "#55ff55"),
    ("aqua", "#55ffff"),
    ("red", "#ff5555"),
    ("light_purple", "#ff55ff"),
    ("yellow", "#ffff55"),
    ("white", "#ffffff"),
];

/// A named colour's `#rrggbb`.
pub fn named_color(name: &str) -> Option<&'static str> {
    NAMED_COLORS
        .iter()
        .find(|(n, _)| *n == name)
        .map(|(_, c)| *c)
}

/// The decoration a tag's name sets, as a setter on a style.
fn decoration(name: &str) -> Option<fn(&mut TextStyle) -> &mut Option<bool>> {
    Some(match name {
        "bold" | "b" => |s| &mut s.bold,
        "italic" | "i" | "em" => |s| &mut s.italic,
        "underlined" | "u" => |s| &mut s.underlined,
        "strikethrough" | "st" => |s| &mut s.strikethrough,
        "obfuscated" | "obf" => |s| &mut s.obfuscated,
        _ => return None,
    })
}

fn is_hex(token: &str) -> bool {
    token.len() == 7 && token.starts_with('#') && token[1..].bytes().all(|b| b.is_ascii_hexdigit())
}

fn color_of(token: Option<&str>) -> Option<String> {
    let token = token?;
    if is_hex(token) {
        return Some(token.to_lowercase());
    }
    named_color(&token.to_lowercase()).map(str::to_string)
}

/// `style` after the opening tag `tag` (its inside, `color:gold`); unchanged for a tag that styles nothing.
fn styled(style: &TextStyle, tag: &str) -> TextStyle {
    let mut parts = tag.split(':');
    let name = parts.next().unwrap_or("").to_lowercase();
    let first = parts.next();
    let color = match name.as_str() {
        "color" | "colour" | "c" | "gradient" => color_of(first),
        "rainbow" => named_color("red").map(str::to_string),
        _ => color_of(Some(&name)),
    };
    let mut out = style.clone();
    if color.is_some() {
        out.color = color;
        return out;
    }
    if let Some(set) = decoration(&name) {
        *set(&mut out) = Some(first != Some("false"));
        return out;
    }
    if let Some(set) = name.strip_prefix('!').and_then(decoration) {
        *set(&mut out) = Some(false);
    }
    out
}

const LT: u16 = b'<' as u16;
const GT: u16 = b'>' as u16;
const NL: u16 = b'\n' as u16;

/// The pieces of MiniMessage `text`, in order.
pub fn pieces(text: &str) -> Vec<Piece> {
    let units: Vec<u16> = text.encode_utf16().collect();
    let slice = |from: usize, to: usize| String::from_utf16_lossy(&units[from..to]);
    let mut out = Vec::new();
    let mut stack = vec![TextStyle::default()];
    // Parallel to the stack: whether each entry is an `<at>` that moves, which ends an overlay when it closes.
    let mut overlays = vec![false];
    let pop = |stack: &mut Vec<TextStyle>,
               overlays: &mut Vec<bool>,
               out: &mut Vec<Piece>,
               down: usize,
               at: usize,
               end: usize| {
        while stack.len() > down {
            stack.pop();
            if overlays.pop() == Some(true) {
                out.push(Piece::OverlayEnd { at, end });
            }
        }
    };
    let mut cursor = 0;
    while cursor < units.len() {
        let c = units[cursor];
        if c == NL {
            out.push(Piece::Break {
                at: cursor,
                end: cursor + 1,
            });
            cursor += 1;
            continue;
        }
        if c == LT
            && let Some(close) = units[cursor..]
                .iter()
                .position(|&u| u == GT)
                .map(|i| cursor + i)
        {
            let tag = slice(cursor + 1, close);
            let closing = tag.starts_with('/');
            let unclosed = tag.strip_prefix('/').unwrap_or(&tag);
            let name = unclosed.split(':').next().unwrap_or("").to_lowercase();
            let reference = if closing {
                None
            } else {
                glyph::reference(&tag)
            };
            let end = close + 1;
            let top = stack.last().cloned().unwrap_or_default();
            if name == "newline" || name == "br" {
                out.push(Piece::Break { at: cursor, end });
            } else if let Some(reference) = reference {
                out.push(Piece::Glyph {
                    reference,
                    at: cursor,
                    end,
                    style: top,
                });
            } else if closing && (name == glyph::NAME || name == space::SPACE) {
                // A stray </glyph> or </space> closes nothing.
            } else if name == space::SPACE {
                if let Some(px) = space::space(&space::arguments(&tag)) {
                    out.push(Piece::Move {
                        px,
                        at: cursor,
                        end,
                    });
                }
            } else if name == "reset" {
                pop(&mut stack, &mut overlays, &mut out, 1, cursor, end);
            } else if closing {
                if stack.len() > 1 {
                    let down = stack.len() - 1;
                    pop(&mut stack, &mut overlays, &mut out, down, cursor, end);
                }
            } else if name == space::AT {
                let spec = space::at(&space::arguments(&tag));
                if let Some(spec) = spec {
                    out.push(Piece::Overlay {
                        spec,
                        at: cursor,
                        end,
                    });
                }
                stack.push(top);
                overlays.push(spec.is_some());
            } else {
                stack.push(styled(&top, &tag));
                overlays.push(false);
            }
            cursor = end;
            continue;
        }
        let (code, size) = super::code_point(&units, cursor);
        out.push(Piece::Char {
            code,
            text: slice(cursor, cursor + size),
            at: cursor,
            end: cursor + size,
            style: stack.last().cloned().unwrap_or_default(),
        });
        cursor += size;
    }
    let len = units.len();
    pop(&mut stack, &mut overlays, &mut out, 1, len, len);
    out
}
