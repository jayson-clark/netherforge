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
