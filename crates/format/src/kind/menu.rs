//! `menus/<id>/menu.json`: a menu window, its slots and its script. Format's
//! `MenuFile`, `MenuKind` and `MenuValidator`; it compiles to a `CompiledMenu`.

use schemars::JsonSchema;
use serde::{Deserialize, Serialize};

use crate::canonical::Map;
use crate::document::{Context, Document};
use crate::item::{self, ItemDef};
use crate::json::child_path;
use crate::problem::{ProblemSink, codes};
use crate::script::{self, ScriptDef};

pub const FILE_NAME: &str = "menu.json";
pub const DEFAULT_ROWS: i32 = 3;
pub const MAX_ROWS: i32 = 6;
/// Every container screen a menu may be is this many GUI pixels wide.
pub const WINDOW_WIDTH: i32 = 176;
/// Where a title that isn't centred starts, from the window's left edge.
pub const TITLE_X: i32 = 8;

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct MenuFile {
    #[serde(rename = "$schema", skip_serializing_if = "Option::is_none")]
    pub schema: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    #[serde(rename = "type", skip_serializing_if = "Option::is_none")]
    pub kind: Option<MenuType>,
    /// A chest's rows, 1 to 6; the other types have a fixed size.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub rows: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(extend("x-minimessage" = true))]
    pub title: Option<String>,
    /// `<pack>/<key>`: a picture drawn as the window's background.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(extend("x-ref" = "skin"))]
    pub skin: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub shared: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub locked: Option<bool>,
    /// Slots by index (`"0"`, `"13"`).
    #[serde(default, skip_serializing_if = "Map::is_empty")]
    pub slots: Map<SlotDef>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub script: Option<ScriptDef>,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct SlotDef {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub item: Option<ItemDef>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum MenuType {
    Chest,
    Barrel,
    ShulkerBox,
    Hopper,
    Dispenser,
    Dropper,
    Crafter,
}

impl MenuType {
    pub fn id(self) -> String {
        crate::canonical::name_of(&self)
    }

    /// Whether its window centres its title (a dispenser's, a dropper's, a crafter's).
    pub fn centres_title(self) -> bool {
        matches!(
            self,
            MenuType::Dispenser | MenuType::Dropper | MenuType::Crafter
        )
    }

    /// Where the title starts, in pixels from the window's left edge, when its words are `words_width` wide:
    /// `TITLE_X`, or centred on `WINDOW_WIDTH` the way the game centres it (integer division, as the client does).
    pub fn title_x(self, words_width: i32) -> i32 {
        if self.centres_title() {
            (WINDOW_WIDTH - words_width) / 2
        } else {
            TITLE_X
        }
    }

    /// How far a skin must move so it lands where it would on a window whose title starts at `TITLE_X`: 0 unless
    /// the title is centred, which moves the zero-wide skin prefix along with the words.
    pub fn skin_shift(self, words_width: i32) -> i32 {
        TITLE_X - self.title_x(words_width)
    }

    /// How many slots a type always has (a chest's depend on its rows).
    pub fn fixed_slots(self) -> i32 {
        match self {
            MenuType::Chest => 0,
            MenuType::Barrel | MenuType::ShulkerBox => 27,
            MenuType::Hopper => 5,
            MenuType::Dispenser | MenuType::Dropper | MenuType::Crafter => 9,
        }
    }

    /// How many slots wide its window is.
    pub fn columns(self) -> i32 {
        match self {
            MenuType::Hopper => 5,
            MenuType::Dispenser | MenuType::Dropper | MenuType::Crafter => 3,
            _ => 9,
        }
    }

    pub fn size(self, rows: i32) -> i32 {
        if self == MenuType::Chest {
            rows * 9
        } else {
            self.fixed_slots()
        }
    }
}

impl Document for MenuFile {
    /// A centred title moves the skin by the words' width, which the server measures with the default font.
    fn cross_check(&self, cx: &crate::project::Across, sink: &mut ProblemSink) {
        let kind = self.kind.unwrap_or(MenuType::Chest);
        if kind.centres_title()
            && self.skin.is_some()
            && self.title.as_deref().is_some_and(|t| !t.trim().is_empty())
        {
            crate::font::require_current(
                cx.default_font(),
                cx.minecraft(),
                sink,
                Some("$.skin".into()),
                &format!(
                    "A {} centres its title, so its skin is placed from the title's width",
                    kind.id()
                ),
            );
        }
    }

    const SCHEMA: &'static str = "../../.netherforge/schema/menu.schema.json";

    fn set_schema(&mut self, schema: Option<String>) {
        self.schema = schema;
    }

    const SCRIPTED: bool = true;

    fn script(&self) -> Option<&ScriptDef> {
        self.script.as_ref()
    }

    fn compile(&self, id: &str, _cx: &Context) -> Option<Box<dyn crate::document::Compiled>> {
        crate::document::compiled(compile(id, self))
    }

    /// What depends on the menu's type and size, a slot's key, its item's
    /// semantic checks, and the script's file (`MenuValidator.validate`).
    fn validate(&self, cx: &Context, sink: &mut ProblemSink) {
        let kind = self.kind.unwrap_or(MenuType::Chest);
        if kind == MenuType::Chest {
            if self.rows.is_some_and(|r| !(1..=MAX_ROWS).contains(&r)) {
                sink.report(
                    codes::MENU_ROWS,
                    format!("A chest has 1 to {MAX_ROWS} rows"),
                    Some("$.rows".into()),
                );
            }
        } else if self.rows.is_some() {
            sink.report(
                codes::MENU_ROWS_FIXED,
                format!(
                    "A {} is always {} slots; rows only applies to a chest",
                    kind.id(),
                    kind.fixed_slots()
                ),
                Some("$.rows".into()),
            );
        }
        let size = kind.size(self.rows.unwrap_or(DEFAULT_ROWS).clamp(1, MAX_ROWS));
        if let Some(s) = &self.script {
            script::check(s, "$.script", cx, sink);
        }
        for (key, slot) in self.slots.iter() {
            let at = child_path("$.slots", &key.0);
            let index = key.0.parse::<i32>().ok().filter(|i| i.to_string() == key.0);
            let Some(index) = index else {
                sink.report(
                    codes::MENU_SLOT_KEY,
                    "Slots are keyed by index: \"0\", \"13\", …",
                    Some(at),
                );
                continue;
            };
            if !(0..size).contains(&index) {
                sink.report(
                    codes::MENU_SLOT_RANGE,
                    format!("This menu has {size} slots (0–{})", size - 1),
                    Some(at.clone()),
                );
            }
            match &slot.item {
                None => sink.report(
                    codes::MENU_SLOT_EMPTY,
                    format!("Slot {key} has no item"),
                    Some(at),
                ),
                Some(it) => item::check(it, &format!("{at}.item"), sink, cx.game),
            }
        }
    }
}

/// A menu as the server opens it: its type and size decided, slots by index.
#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct CompiledMenu {
    pub id: String,
    pub name: Option<String>,
    #[serde(rename = "type")]
    pub kind: MenuType,
    pub rows: i32,
    pub size: i32,
    pub title: Option<String>,
    pub skin: Option<String>,
    pub shared: bool,
    pub locked: bool,
    /// By index, ascending.
    pub slots: std::collections::BTreeMap<i32, SlotDef>,
    pub script: Option<ScriptDef>,
}

impl CompiledMenu {
    pub fn slot(&self, index: i32) -> Option<&SlotDef> {
        self.slots.get(&index)
    }
}

/// `file` (the menu `id`) as it opens, for a file whose checks found no errors.
pub fn compile(id: &str, file: &MenuFile) -> CompiledMenu {
    let kind = file.kind.unwrap_or(MenuType::Chest);
    let rows = match kind {
        MenuType::Chest => file.rows.unwrap_or(DEFAULT_ROWS),
        _ => kind.fixed_slots() / kind.columns(),
    };
    CompiledMenu {
        id: id.to_owned(),
        name: file.name.clone(),
        kind,
        rows,
        size: kind.size(rows),
        title: file.title.clone(),
        skin: file.skin.clone(),
        shared: file.shared.unwrap_or(false),
        locked: file.locked.unwrap_or(true),
        slots: file
            .slots
            .iter()
            .map(|(key, slot)| {
                let index = key.0.parse().expect("a checked slot's key is its index");
                (index, slot.clone())
            })
            .collect(),
        script: file.script.clone(),
    }
}
