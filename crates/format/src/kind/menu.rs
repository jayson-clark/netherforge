//! `menus/<id>/menu.json`: a menu window, its slots and its script. Format's
//! `MenuFile`, `MenuKind` and `MenuValidator`.

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
    pub title: Option<String>,
    /// `<pack>/<key>`: a picture drawn as the window's background.
    #[serde(skip_serializing_if = "Option::is_none")]
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
    pub fn id(self) -> &'static str {
        match self {
            MenuType::Chest => "chest",
            MenuType::Barrel => "barrel",
            MenuType::ShulkerBox => "shulker_box",
            MenuType::Hopper => "hopper",
            MenuType::Dispenser => "dispenser",
            MenuType::Dropper => "dropper",
            MenuType::Crafter => "crafter",
        }
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

    pub fn size(self, rows: i32) -> i32 {
        if self == MenuType::Chest {
            rows * 9
        } else {
            self.fixed_slots()
        }
    }
}

impl Document for MenuFile {
    const SCHEMA: &'static str = "../../.netherforge/schema/menu.schema.json";

    fn set_schema(&mut self, schema: Option<String>) {
        self.schema = schema;
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
