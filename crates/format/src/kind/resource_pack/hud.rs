//! `pack.json`'s `hud` (format's `HudDef`): the game's HUD elements a pack
//! hides, and elements of its own scripts fill in per player, drawn in the
//! action bar: bars, rows of icons and lines of text. Positions are GUI
//! pixels: `x` from the middle of the screen, `y` from its bottom up.

use schemars::JsonSchema;
use serde::{Deserialize, Serialize};

use super::{PackKey, TexturePath};
use crate::canonical::{Entries, Map};

/// How far above the screen's bottom the highest element's bottom edge can be.
pub const MAX_Y: i32 = 65;
/// Where a glyph with an ascent of 0 has its bottom edge: the action bar's line.
pub const BASELINE: i32 = 65;
/// How far either way an element can be from the middle of the screen.
pub const MAX_X: i32 = 1000;
pub const MAX_SEGMENTS: i32 = 64;

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct HudDef {
    /// The game's own HUD elements to hide, for every player.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub hide: Option<Vec<HudElement>>,
    /// The game's GUI sprites (`hud/hotbar`) to draw with the pack's pictures instead.
    #[serde(default, skip_serializing_if = "Entries::is_empty")]
    pub replace: Map<TexturePath>,
    #[serde(default, skip_serializing_if = "Entries::is_empty")]
    pub bars: Entries<PackKey, HudBarDef>,
    #[serde(default, skip_serializing_if = "Entries::is_empty")]
    pub icons: Entries<PackKey, HudIconsDef>,
    #[serde(default, skip_serializing_if = "Entries::is_empty")]
    pub texts: Entries<PackKey, HudTextDef>,
}

/// One of the game's HUD elements a pack can hide, by the prefixes of the sprites that draw it.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum HudElement {
    Health,
    Food,
    Armor,
    Air,
    Experience,
}

impl HudElement {
    pub fn sprites(self) -> &'static [&'static str] {
        match self {
            HudElement::Health => &["hud/heart/"],
            HudElement::Food => &["hud/food_"],
            HudElement::Armor => &["hud/armor_"],
            HudElement::Air => &["hud/air"],
            HudElement::Experience => &["hud/experience_bar_"],
        }
    }

    /// Whether the game's sprite `sprite` is one that draws this.
    pub fn draws(self, sprite: &str) -> bool {
        self.sprites().iter().any(|p| sprite.starts_with(p))
    }
}

/// A bar: `segments` pictures in a row, each full, half or empty.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct HudBarDef {
    pub full: TexturePath,
    pub empty: TexturePath,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub half: Option<TexturePath>,
    #[schemars(range(min = 1, max = 64), extend("x-problem" = "resource_pack.hud"))]
    pub segments: i32,
    #[schemars(range(min = -1000, max = 1000), extend("x-problem" = "resource_pack.hud"))]
    pub x: i32,
    #[schemars(range(min = 0, max = 65), extend("x-problem" = "resource_pack.hud"))]
    pub y: i32,
    /// Pixels between segments. Default 0.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 0), extend("default" = 0, "x-problem" = "resource_pack.hud"))]
    pub gap: Option<i32>,
    /// Fill from the right. Default false.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reverse: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub align: Option<HudAlign>,
}

/// A row of icons, of which a script shows any.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct HudIconsDef {
    pub icons: Entries<PackKey, TexturePath>,
    #[schemars(range(min = -1000, max = 1000), extend("x-problem" = "resource_pack.hud"))]
    pub x: i32,
    #[schemars(range(min = 0, max = 65), extend("x-problem" = "resource_pack.hud"))]
    pub y: i32,
    /// Pixels between icons. Default 1.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 0), extend("default" = 1, "x-problem" = "resource_pack.hud"))]
    pub gap: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub align: Option<HudAlign>,
}

/// A line of text on the action bar's line, which scripts set.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct HudTextDef {
    #[schemars(range(min = -1000, max = 1000), extend("x-problem" = "resource_pack.hud"))]
    pub x: i32,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub align: Option<HudAlign>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum HudAlign {
    Left,
    Center,
    Right,
}

impl HudDef {
    /// Every key, of every kind of element: what scripts name them by.
    pub fn keys(&self) -> impl Iterator<Item = &PackKey> {
        let bars = self.bars.iter().map(|(k, _)| k);
        bars.chain(self.icons.iter().map(|(k, _)| k))
            .chain(self.texts.iter().map(|(k, _)| k))
    }
}

/// How tall a HUD picture whose pixels couldn't be read is drawn: a line of text's.
pub const DEFAULT_HEIGHT: i32 = 9;
/// The font a pack's HUD elements are in: `<namespace>:<pack>/hud`.
pub const FONT: &str = "hud";
/// Pixels between icons, unless a row says.
pub const DEFAULT_ICON_GAP: i32 = 1;

/// One HUD picture: what draws it (its character, or a picture wider than a
/// font sheet's pieces, whose characters are `cells`), its texture, how tall,
/// its ascent (what puts it at its height on the screen) and its advance.
#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct Piece {
    pub char: String,
    pub texture: String,
    pub height: i32,
    pub ascent: i32,
    pub advance: Option<i32>,
    pub cells: String,
}

#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct Bar {
    pub def: HudBarDef,
    pub full: Piece,
    pub empty: Piece,
    pub half: Option<Piece>,
}

impl Bar {
    /// The width each segment takes: the widest picture's, and the gap; none when a picture's width isn't known.
    pub fn slot(&self) -> Option<i32> {
        let widest = [Some(&self.full), Some(&self.empty), self.half.as_ref()]
            .into_iter()
            .flatten()
            .map(|p| p.advance)
            .collect::<Option<Vec<i32>>>()?
            .into_iter()
            .max()?;
        Some(widest + self.def.gap.unwrap_or(0))
    }
}

#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct Icons {
    pub def: HudIconsDef,
    pub icons: std::collections::BTreeMap<String, Piece>,
}

/// A pack's HUD elements with their characters settled, each picture a
/// character of the pack's own HUD font (format's `CompiledHud`).
#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct CompiledHud {
    pub font: String,
    pub hide: Vec<HudElement>,
    pub bars: std::collections::BTreeMap<String, Bar>,
    pub icons: std::collections::BTreeMap<String, Icons>,
    pub texts: std::collections::BTreeMap<String, HudTextDef>,
}

impl CompiledHud {
    /// `def` of pack `id` in `namespace`, its pictures numbered from the start
    /// of the private-use block in the pack's HUD font; `image` answers a
    /// texture's pixels by its `textures/` path.
    pub fn compile(
        namespace: &str,
        id: &str,
        def: &HudDef,
        image: &dyn Fn(&str) -> Option<crate::project::ImageInfo>,
    ) -> CompiledHud {
        use super::fonts;
        let mut next = 0;
        // A picture cut into pieces takes a character per further piece, after every picture's own.
        let mut next_piece = def
            .bars
            .iter()
            .map(|(_, b)| if b.half.is_some() { 3 } else { 2 })
            .sum::<usize>()
            + def.icons.iter().map(|(_, r)| r.icons.len()).sum::<usize>();
        let mut piece = |texture: &str, y: i32| {
            let facts = image(texture).filter(|i| i.height > 0);
            // Drawn at its own size: a HUD picture's pixels are the screen's.
            let height = facts.as_ref().map_or(DEFAULT_HEIGHT, |i| i.height as i32);
            let first = fonts::char_at(next);
            next += 1;
            let mut extra = || {
                (next_piece < fonts::PUA_COUNT).then(|| {
                    next_piece += 1;
                    fonts::char_at(next_piece - 1)
                })
            };
            let drawn = fonts::picture(first, facts.as_ref(), height, &mut extra);
            Piece {
                char: drawn.text,
                texture: texture.to_owned(),
                height,
                ascent: y + height - BASELINE,
                advance: drawn.advance,
                cells: drawn.cells,
            }
        };
        let mut bars: Vec<(&PackKey, &HudBarDef)> = def.bars.iter().collect();
        bars.sort_by(|a, b| a.0.0.cmp(&b.0.0));
        let bars = bars
            .into_iter()
            .map(|(key, bar)| {
                let full = piece(&bar.full, bar.y);
                let empty = piece(&bar.empty, bar.y);
                let half = bar.half.as_ref().map(|h| piece(h, bar.y));
                (
                    key.0.clone(),
                    Bar {
                        def: bar.clone(),
                        full,
                        empty,
                        half,
                    },
                )
            })
            .collect();
        let mut rows: Vec<(&PackKey, &HudIconsDef)> = def.icons.iter().collect();
        rows.sort_by(|a, b| a.0.0.cmp(&b.0.0));
        let icons = rows
            .into_iter()
            .map(|(key, row)| {
                let mut each: Vec<(&PackKey, &TexturePath)> = row.icons.iter().collect();
                each.sort_by(|a, b| a.0.0.cmp(&b.0.0));
                let icons = each
                    .into_iter()
                    .map(|(icon, texture)| (icon.0.clone(), piece(texture, row.y)))
                    .collect();
                (
                    key.0.clone(),
                    Icons {
                        def: row.clone(),
                        icons,
                    },
                )
            })
            .collect();
        // A set in the file's order.
        let mut hide: Vec<HudElement> = Vec::new();
        for element in def.hide.iter().flatten() {
            if !hide.contains(element) {
                hide.push(*element);
            }
        }
        CompiledHud {
            font: format!("{namespace}:{id}/{FONT}"),
            hide,
            bars,
            icons,
            texts: def
                .texts
                .iter()
                .map(|(k, t)| (k.0.clone(), t.clone()))
                .collect(),
        }
    }

    /// Every picture, in the order the font lists them.
    pub fn pieces(&self) -> Vec<&Piece> {
        let bars = self.bars.values().flat_map(|b| {
            [Some(&b.full), Some(&b.empty), b.half.as_ref()]
                .into_iter()
                .flatten()
        });
        bars.chain(self.icons.values().flat_map(|r| r.icons.values()))
            .collect()
    }
}

/// What one player's HUD shows of the pack's elements, by key: a bar's fill,
/// the icons a row shows (in order), a line's text (MiniMessage).
#[derive(Clone, Debug, Default, PartialEq)]
pub struct HudState {
    pub bars: std::collections::BTreeMap<String, BarFill>,
    pub icons: std::collections::BTreeMap<String, Vec<String>>,
    pub texts: std::collections::BTreeMap<String, String>,
}

/// A bar's `value` out of `max`, over `segments` of its segments (all of them when none).
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct BarFill {
    pub value: f64,
    pub max: f64,
    pub segments: Option<i32>,
}

/// The action bar line that draws a player's HUD (format's `HudLine.compose`):
/// every element in key order (bars, then rows of icons, then lines of text),
/// each moving the cursor to its place, drawing and moving back, so the line is
/// no width at all and the game's centring leaves every element at its `x`.
/// `width` measures a line of text as the game draws it; a line it can't
/// measure isn't drawn.
pub fn compose(hud: &CompiledHud, state: &HudState, width: &dyn Fn(&str) -> Option<i32>) -> String {
    use super::fonts::offset;
    let mut out = String::new();
    // One run in the pack's font, untinted and with no shadow, closed so nothing of it reaches what follows.
    let run = |chars: &str, out: &mut String| {
        if !chars.is_empty() {
            out.push_str(&format!(
                "<white><!shadow><font:{}>{chars}</font></!shadow></white>",
                hud.font
            ));
        }
    };
    for (key, bar) in &hud.bars {
        let (Some(fill), Some(slot)) = (state.bars.get(key), bar.slot()) else {
            continue;
        };
        let pieces = segments(
            bar,
            fill.value,
            fill.max,
            fill.segments.unwrap_or(bar.def.segments),
        );
        let count = pieces.len() as i32;
        let left = left(
            bar.def.x,
            bar.def.align,
            slot * count - bar.def.gap.unwrap_or(0),
        );
        let mut chars = offset(left);
        for piece in pieces {
            chars.push_str(&piece.char);
            chars.push_str(&offset(
                slot - piece.advance.expect("a bar with a slot knows its pictures"),
            ));
        }
        chars.push_str(&offset(-(left + slot * count)));
        run(&chars, &mut out);
    }
    for (key, row) in &hud.icons {
        let shown: Vec<&Piece> = state
            .icons
            .get(key)
            .into_iter()
            .flatten()
            .filter_map(|i| row.icons.get(i))
            .collect();
        if shown.is_empty() || shown.iter().any(|p| p.advance.is_none()) {
            continue;
        }
        let gap = row.def.gap.unwrap_or(DEFAULT_ICON_GAP);
        let moved: i32 = shown.iter().map(|p| p.advance.unwrap_or(0) + gap).sum();
        let left = left(row.def.x, row.def.align, moved - gap);
        let mut chars = offset(left);
        for piece in shown {
            chars.push_str(&piece.char);
            chars.push_str(&offset(gap));
        }
        chars.push_str(&offset(-(left + moved)));
        run(&chars, &mut out);
    }
    for (key, def) in &hud.texts {
        let Some(text) = state.texts.get(key).filter(|t| !t.is_empty()) else {
            continue;
        };
        let Some(wide) = width(text) else { continue };
        let left = left(def.x, def.align, wide);
        run(&offset(left), &mut out);
        // Its own colours and shadow, in the game's font; reset after, so no style of it reaches the elements after.
        out.push_str(text);
        out.push_str("<reset>");
        run(&offset(-(left + wide)), &mut out);
    }
    out
}

/// Where something `wide` pixels across has its left edge, when `align` puts that part of it at `x`.
fn left(x: i32, align: Option<HudAlign>, wide: i32) -> i32 {
    match align.unwrap_or(HudAlign::Left) {
        HudAlign::Left => x,
        HudAlign::Center => x - wide / 2,
        HudAlign::Right => x - wide,
    }
}

/// The pictures of `bar` for `value` out of `max` over `shown` of its segments, left to right.
pub fn segments(bar: &Bar, value: f64, max: f64, shown: i32) -> Vec<&Piece> {
    let count = shown.clamp(1, bar.def.segments);
    let fraction = if max > 0.0 && value.is_finite() {
        (value / max).clamp(0.0, 1.0)
    } else {
        0.0
    };
    // Kotlin's `round` rounds half to even.
    let halves = match &bar.half {
        Some(_) => (fraction * f64::from(count) * 2.0).round_ties_even() as i32,
        None => (fraction * f64::from(count)).round_ties_even() as i32 * 2,
    };
    let (full, half) = (halves / 2, halves % 2);
    let mut pieces: Vec<&Piece> = Vec::new();
    pieces.extend((0..full).map(|_| &bar.full));
    pieces.extend((0..half).map(|_| bar.half.as_ref().expect("half a segment has its picture")));
    pieces.extend((0..count - full - half).map(|_| &bar.empty));
    if bar.def.reverse == Some(true) {
        pieces.reverse();
    }
    pieces
}
