//! `loot/<id>.json`: a loot table's pools, entries and conditions. Format's
//! `LootTableFile`, `LootTableKind` and `LootValidator`; a pool's name,
//! weights, chances and enchantments are their fields' rules.

use schemars::JsonSchema;
use serde::{Deserialize, Deserializer, Serialize, Serializer};

use super::recipe::{self, Ingredient, IngredientCodes};
use crate::canonical::Map;
use crate::document::{Context, Document};
use crate::game::{self, GameData, Registry};
use crate::item::{self, ItemDef};
use crate::json::child_path;
use crate::problem::{ProblemSink, codes};

pub const MOST_ROLLS: i64 = 100;
pub const MOST_COUNT: i64 = 1000;

const ID: &str = r"^[a-z0-9][a-z0-9_]{0,63}$";

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct LootTableFile {
    #[serde(rename = "$schema", skip_serializing_if = "Option::is_none")]
    pub schema: Option<String>,
    #[serde(default, skip_serializing_if = "Map::is_empty")]
    #[schemars(extend("propertyNames" = {
        "pattern": ID,
        "x-problem": "loot.pool-name",
        "x-rule": "a usable pool name (lowercase letters, digits and _, starting with a letter or digit, at most 64 characters)"
    }))]
    pub pools: Map<LootPool>,
}

fn empty<T>(list: &[T]) -> bool {
    list.is_empty()
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct LootPool {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub rolls: Option<LootRange>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub bonus_rolls: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub conditions: Option<Vec<LootCondition>>,
    #[serde(default, skip_serializing_if = "empty")]
    pub entries: Vec<LootEntry>,
}

/// A whole number (`2`), or `{ "min": 1, "max": 3 }`.
#[derive(Clone, Copy, Debug, PartialEq, JsonSchema)]
#[schemars(with = "serde_json::Value")]
pub struct LootRange {
    pub min: i64,
    pub max: i64,
}

const RANGE: &str = "A range is a whole number (2) or { \"min\": 1, \"max\": 3 }";

impl Serialize for LootRange {
    fn serialize<S: Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
        if self.min == self.max {
            s.serialize_i64(self.min)
        } else {
            serde_json::json!({ "min": self.min, "max": self.max }).serialize(s)
        }
    }
}

impl<'de> Deserialize<'de> for LootRange {
    fn deserialize<D: Deserializer<'de>>(d: D) -> Result<LootRange, D::Error> {
        use serde::de::Error;
        let whole = |v: &serde_json::Value| v.as_i64().ok_or_else(|| D::Error::custom(RANGE));
        match serde_json::Value::deserialize(d)? {
            serde_json::Value::Object(fields) => {
                if let Some(other) = fields.keys().find(|k| *k != "min" && *k != "max") {
                    return Err(D::Error::custom(format!(
                        "A range has only \"min\" and \"max\", not \"{other}\""
                    )));
                }
                let min = whole(fields.get("min").ok_or_else(|| D::Error::custom(RANGE))?)?;
                let max = whole(fields.get("max").ok_or_else(|| D::Error::custom(RANGE))?)?;
                Ok(LootRange { min, max })
            }
            value @ serde_json::Value::Number(_) => {
                let n = whole(&value)?;
                Ok(LootRange { min: n, max: n })
            }
            _ => Err(D::Error::custom(RANGE)),
        }
    }
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(tag = "type", rename_all = "snake_case", deny_unknown_fields)]
pub enum LootEntry {
    Item {
        item: Box<ItemDef>,
        #[serde(skip_serializing_if = "Option::is_none")]
        count: Option<LootRange>,
        #[serde(skip_serializing_if = "Option::is_none")]
        #[schemars(range(min = 1), extend("x-problem" = "loot.weight"))]
        weight: Option<i32>,
        #[serde(skip_serializing_if = "Option::is_none")]
        quality: Option<i32>,
        #[serde(skip_serializing_if = "Option::is_none")]
        conditions: Option<Vec<LootCondition>>,
    },
    Table {
        #[schemars(extend("x-ref" = "loot_table"))]
        table: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        #[schemars(range(min = 1), extend("x-problem" = "loot.weight"))]
        weight: Option<i32>,
        #[serde(skip_serializing_if = "Option::is_none")]
        quality: Option<i32>,
        #[serde(skip_serializing_if = "Option::is_none")]
        conditions: Option<Vec<LootCondition>>,
    },
    Vanilla {
        table: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        #[schemars(range(min = 1), extend("x-problem" = "loot.weight"))]
        weight: Option<i32>,
        #[serde(skip_serializing_if = "Option::is_none")]
        quality: Option<i32>,
        #[serde(skip_serializing_if = "Option::is_none")]
        conditions: Option<Vec<LootCondition>>,
    },
    Empty {
        #[serde(skip_serializing_if = "Option::is_none")]
        #[schemars(range(min = 1), extend("x-problem" = "loot.weight"))]
        weight: Option<i32>,
        #[serde(skip_serializing_if = "Option::is_none")]
        quality: Option<i32>,
        #[serde(skip_serializing_if = "Option::is_none")]
        conditions: Option<Vec<LootCondition>>,
    },
}

impl LootEntry {
    pub fn conditions(&self) -> Option<&Vec<LootCondition>> {
        match self {
            LootEntry::Item { conditions, .. }
            | LootEntry::Table { conditions, .. }
            | LootEntry::Vanilla { conditions, .. }
            | LootEntry::Empty { conditions, .. } => conditions.as_ref(),
        }
    }
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(tag = "type", rename_all = "snake_case", deny_unknown_fields)]
pub enum LootCondition {
    Chance {
        #[schemars(range(min = 0, max = 1), extend("x-problem" = "loot.chance"))]
        chance: f64,
        #[serde(skip_serializing_if = "Option::is_none")]
        invert: Option<bool>,
    },
    Player {
        #[serde(skip_serializing_if = "Option::is_none")]
        invert: Option<bool>,
    },
    Tool {
        tool: Ingredient,
        #[serde(skip_serializing_if = "Option::is_none")]
        invert: Option<bool>,
    },
    Enchantment {
        #[schemars(regex(pattern = r"^([a-z0-9_.-]+:)?[a-z0-9_./-]+$"), extend("x-problem" = "loot.enchantment", "x-rule" = "an enchantment id"))]
        enchantment: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        #[schemars(range(min = 1), extend("x-problem" = "loot.enchantment"))]
        level: Option<i32>,
        #[serde(skip_serializing_if = "Option::is_none")]
        invert: Option<bool>,
    },
}

const LOOT_TOOL: IngredientCodes = IngredientCodes {
    invalid: codes::LOOT_TOOL,
    unknown_item: codes::LOOT_UNKNOWN_TOOL,
    unknown_tag: codes::LOOT_UNKNOWN_TOOL,
};

fn range(range: &LootRange, at: &str, most: i64, sink: &mut ProblemSink) {
    let message = if range.min < 0 {
        "can't be negative".to_owned()
    } else if range.max < range.min {
        "max can't be below min".to_owned()
    } else if range.max > most {
        format!("can be at most {most}")
    } else {
        return;
    };
    let name = at.rsplit('.').next().unwrap_or(at);
    sink.report(
        codes::LOOT_RANGE,
        format!("{name} {message}"),
        Some(at.to_owned()),
    );
}

fn conditions(
    conditions: Option<&Vec<LootCondition>>,
    at: &str,
    sink: &mut ProblemSink,
    game: Option<&dyn GameData>,
) {
    for (index, condition) in conditions.into_iter().flatten().enumerate() {
        let path = format!("{at}[{index}]");
        match condition {
            LootCondition::Tool { tool, .. } => {
                recipe::check_ingredient(tool, &format!("{path}.tool"), &LOOT_TOOL, sink, game)
            }
            LootCondition::Enchantment { enchantment, .. } => {
                let valid = game::is_id(enchantment);
                if let Some(game) = game
                    && valid
                    && game.lacks(Registry::Enchantment, &game::normalize(enchantment))
                {
                    sink.report(
                        codes::LOOT_UNKNOWN_ENCHANTMENT,
                        format!(
                            "Minecraft {} has no enchantment \"{}\"",
                            game.minecraft_version(),
                            game::normalize(enchantment)
                        ),
                        Some(format!("{path}.enchantment")),
                    );
                }
            }
            _ => {}
        }
    }
}

impl Document for LootTableFile {
    fn cross_check(&self, cx: &crate::project::Across, sink: &mut ProblemSink) {
        self.across(cx, sink);
    }

    const SCHEMA: &'static str = "../.netherforge/schema/loot_table.schema.json";

    fn set_schema(&mut self, schema: Option<String>) {
        self.schema = schema;
    }

    fn validate(&self, cx: &Context, sink: &mut ProblemSink) {
        for (name, pool) in self.pools.iter() {
            let at = child_path("$.pools", &name.0);
            if let Some(rolls) = &pool.rolls {
                range(rolls, &format!("{at}.rolls"), MOST_ROLLS, sink);
            }
            conditions(
                pool.conditions.as_ref(),
                &format!("{at}.conditions"),
                sink,
                cx.game,
            );
            if pool.entries.is_empty() {
                sink.report(
                    codes::LOOT_NO_ENTRIES,
                    format!("Pool \"{name}\" has no entries, so it gives nothing"),
                    Some(at.clone()),
                );
            }
            for (index, entry) in pool.entries.iter().enumerate() {
                let at = format!("{at}.entries[{index}]");
                conditions(
                    entry.conditions(),
                    &format!("{at}.conditions"),
                    sink,
                    cx.game,
                );
                match entry {
                    LootEntry::Item {
                        item: stack, count, ..
                    } => {
                        item::check(stack, &format!("{at}.item"), sink, cx.game);
                        if stack.count.is_some() {
                            sink.report(
                                codes::LOOT_ITEM_COUNT,
                                "Say how many with the entry's count, which can be a range",
                                Some(format!("{at}.item.count")),
                            );
                        }
                        if let Some(count) = count {
                            range(count, &format!("{at}.count"), MOST_COUNT, sink);
                        }
                    }
                    LootEntry::Vanilla { table, .. } => {
                        let valid = game::is_id(table);
                        if !valid || !table.contains(':') {
                            sink.report(
                                codes::LOOT_VANILLA,
                                format!("\"{table}\" isn't a game loot table's id, like \"minecraft:chests/simple_dungeon\""),
                                Some(format!("{at}.table")),
                            );
                        } else if let Some(game) = cx.game
                            && game.lacks(Registry::LootTable, table)
                        {
                            sink.report(
                                codes::LOOT_UNKNOWN_VANILLA,
                                format!(
                                    "Minecraft {} has no loot table \"{table}\"",
                                    game.minecraft_version()
                                ),
                                Some(format!("{at}.table")),
                            );
                        }
                    }
                    _ => {}
                }
            }
        }
    }
}

impl LootTableFile {
    /// Every entry, with its path: pools in name order, entries in theirs.
    pub fn entries(&self) -> Vec<(String, &LootEntry)> {
        let mut pools: Vec<_> = self.pools.iter().collect();
        pools.sort_by(|a, b| a.0.0.cmp(&b.0.0));
        pools
            .into_iter()
            .flat_map(|(name, pool)| {
                let at = child_path("$.pools", &name.0);
                pool.entries
                    .iter()
                    .enumerate()
                    .map(move |(i, e)| (format!("{at}.entries[{i}]"), e))
            })
            .collect()
    }

    /// No table it includes includes it again.
    fn across(&self, cx: &crate::project::Across, sink: &mut ProblemSink) {
        let own = |table: &str| {
            cx.references()
                .own(crate::reference::RefKind::LootTable, table)
        };
        for (at, entry) in self.entries() {
            let LootEntry::Table { table, .. } = entry else {
                continue;
            };
            let Some(start) = own(table) else { continue };
            let mut seen = std::collections::BTreeSet::new();
            let mut queue = std::collections::VecDeque::from([start.clone()]);
            while let Some(id) = queue.pop_front() {
                if id == cx.id() {
                    let through = if start == cx.id() {
                        ""
                    } else {
                        " (through its entries)"
                    };
                    sink.problems.push(
                        codes::LOOT_CYCLE
                            .at(
                                &sink.file,
                                format!("\"{table}\" includes this table again{through}, so a roll would never end"),
                                Some(format!("{at}.table")),
                            )
                            .with_related(vec![crate::Location {
                                file: cx.path_of("loot_table", &start),
                                path: None,
                            }]),
                    );
                    break;
                }
                if !seen.insert(id.clone()) {
                    continue;
                }
                let Some(next) = cx.model::<LootTableFile>(&id) else {
                    continue;
                };
                for (_, inner) in next.entries() {
                    if let LootEntry::Table { table, .. } = inner
                        && let Some(id) = own(table)
                    {
                        queue.push_back(id);
                    }
                }
            }
        }
    }
}
