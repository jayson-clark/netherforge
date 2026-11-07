//! Items as Lua tables, both ways (Kotlin's `LuaItems`, as far as centities and events need it): the table is
//! format's `ItemDef` in Lua's spelling (`hide_tooltip`, `item_model`), plus `raw`, whatever else the stack
//! carries, opaque, so a script that reads an item and writes it back keeps what it never knew about.
//! `values::Item` carries the table as JSON.
//!
//! Reading is strict: an unknown field or one of the wrong type is an error naming it, and so is a kind the
//! server doesn't have. Not yet (with the items area): Kotlin's field-by-field messages, project items'
//! looks, and `data`'s tagged values.

use netherforge_api::values;
use netherforge_format::game::{GameData, Registry};
use netherforge_format::item::ItemDef;
use netherforge_platform::ItemData;
use serde_json::{Map, Value};

/// `hideTooltip` → `hide_tooltip`.
fn snake(key: &str) -> String {
    let mut out = String::with_capacity(key.len() + 4);
    for c in key.chars() {
        if c.is_ascii_uppercase() {
            out.push('_');
            out.push(c.to_ascii_lowercase());
        } else {
            out.push(c);
        }
    }
    out
}

/// `hide_tooltip` → `hideTooltip`.
fn camel(key: &str) -> String {
    let mut out = String::with_capacity(key.len());
    let mut upper = false;
    for c in key.chars() {
        if c == '_' {
            upper = true;
        } else if upper {
            out.push(c.to_ascii_uppercase());
            upper = false;
        } else {
            out.push(c);
        }
    }
    out
}

/// The fields whose keys are the script's or the game's, not field names: left as they are.
const OWN_KEYS: &[&str] = &["data", "enchantments"];

fn respell(value: Value, spell: &dyn Fn(&str) -> String) -> Value {
    match value {
        Value::Object(map) => Value::Object(
            map.into_iter()
                .map(|(k, v)| {
                    let own = OWN_KEYS.contains(&k.as_str());
                    let key = spell(&k);
                    (key, if own { v } else { respell(v, spell) })
                })
                .collect(),
        ),
        Value::Array(items) => Value::Array(items.into_iter().map(|v| respell(v, spell)).collect()),
        other => other,
    }
}

/// A stack as a script's table.
pub fn to_value(item: &ItemData) -> values::Item {
    let mut table = match respell(serde_json::to_value(&item.def).unwrap_or_default(), &snake) {
        Value::Object(map) => map,
        _ => Map::new(),
    };
    if let Some(raw) = &item.raw {
        table.insert("raw".into(), Value::String(raw.clone()));
    }
    values::Item(Value::Object(table).to_string())
}

/// A script's table as a stack; `at` names it in an error (`item`, `event.item`).
pub fn from_value(item: &values::Item, at: &str, game: &dyn GameData) -> Result<ItemData, String> {
    read_item(item, at, game, true)
}

/// [`from_value`] for an item whose kind is a stand-in (an overrides table's, which the look fills in): its kind
/// isn't looked up in the server's registry.
pub fn from_value_any_kind(
    item: &values::Item,
    at: &str,
    game: &dyn GameData,
) -> Result<ItemData, String> {
    read_item(item, at, game, false)
}

fn read_item(
    item: &values::Item,
    at: &str,
    game: &dyn GameData,
    known_kind: bool,
) -> Result<ItemData, String> {
    let parsed: Value =
        serde_json::from_str(&item.0).map_err(|e| format!("bad argument '{at}' ({e})"))?;
    let Value::Object(mut table) = parsed else {
        return Err(format!(
            "bad argument '{at}' (an item is a table like {{ kind = \"minecraft:bread\", count = 4 }})"
        ));
    };
    let raw = match table.remove("raw") {
        None | Some(Value::Null) => None,
        Some(Value::String(raw)) => Some(raw),
        Some(_) => return Err(format!("bad field '{at}.raw' (a string expected)")),
    };
    // An empty Lua table reads as an object: the empty list.
    if table
        .get("lore")
        .is_some_and(|l| l.as_object().is_some_and(Map::is_empty))
    {
        table.insert("lore".into(), Value::Array(Vec::new()));
    }
    let def: ItemDef = serde_json::from_value(respell(Value::Object(table), &camel))
        .map_err(|e| format!("bad argument '{at}' ({})", lua_spelling(&e.to_string())))?;
    if def.kind.is_empty() && def.item.is_none() {
        return Err(format!("bad argument '{at}' (an item needs a kind)"));
    }
    if known_kind
        && !def.kind.is_empty()
        && game.lacks(
            Registry::Item,
            &netherforge_format::game::normalize(&def.kind),
        )
    {
        return Err(format!(
            "bad argument '{at}' (the server has no item \"{}\")",
            def.kind
        ));
    }
    // Format's rules for an item, as a project file's (Kotlin's `Rules.item`): a stackable sword is refused here too.
    let mut sink = netherforge_format::problem::ProblemSink::new("item");
    // A stand-in kind isn't the server's to know, nor what its rules say of one.
    netherforge_format::item::check(&def, "item", &mut sink, known_kind.then_some(game));
    if let Some(problem) = sink
        .problems
        .iter()
        .find(|p| p.severity == netherforge_format::problem::Severity::Error)
    {
        return Err(lua_spelling(&problem.message));
    }
    Ok(ItemData {
        def,
        raw,
        look: None,
    })
}

/// Which stacks `inventory:remove_item` and friends pick (Kotlin's `ItemMatch`): an item kind, or a partial item
/// that matches a stack when every field it gives matches. Fields compare as a script reads them (`to_value`'s
/// table); `data` matches as a subset, so `{ data = { coin = true } }` finds coins whatever else they carry.
#[derive(Clone, Debug)]
pub struct ItemMatch {
    kind: Option<String>,
    fields: Map<String, Value>,
    data: Option<Value>,
}

impl ItemMatch {
    /// A match a script gave (`string|ItemMatch`, as the codec's JSON): a kind the server doesn't have, or a
    /// field items don't have, is an error at `at`.
    pub fn of(
        given: &values::ItemMatch,
        at: &str,
        game: &dyn GameData,
    ) -> Result<ItemMatch, String> {
        let parsed: Value =
            serde_json::from_str(&given.0).map_err(|e| format!("bad argument '{at}' ({e})"))?;
        match parsed {
            Value::String(kind) => {
                let id = netherforge_format::game::normalize(&kind);
                if game.lacks(Registry::Item, &id) {
                    return Err(format!(
                        "bad argument '{at}' (the server has no item \"{kind}\")"
                    ));
                }
                Ok(ItemMatch {
                    kind: Some(id),
                    fields: Map::new(),
                    data: None,
                })
            }
            Value::Object(table) => {
                let given: Vec<String> = table.keys().cloned().collect();
                // Read as an item is, but partial: nothing it needs.
                let def: ItemDef =
                    serde_json::from_value(respell(Value::Object(table.clone()), &camel)).map_err(
                        |e| format!("bad argument '{at}' ({})", lua_spelling(&e.to_string())),
                    )?;
                if !def.kind.is_empty()
                    && game.lacks(
                        Registry::Item,
                        &netherforge_format::game::normalize(&def.kind),
                    )
                {
                    return Err(format!(
                        "bad argument '{at}' (the server has no item \"{}\")",
                        def.kind
                    ));
                }
                let written = written(&ItemData {
                    def: def.clone(),
                    raw: None,
                    look: None,
                });
                let fields = given
                    .iter()
                    .filter(|k| !["kind", "data", "count", "raw"].contains(&k.as_str()))
                    .map(|k| (k.clone(), written.get(k).cloned().unwrap_or(Value::Null)))
                    .collect();
                Ok(ItemMatch {
                    kind: given
                        .iter()
                        .any(|k| k == "kind")
                        .then(|| netherforge_format::game::normalize(&def.kind)),
                    fields,
                    data: given.iter().any(|k| k == "data").then(|| {
                        serde_json::to_value(def.data.unwrap_or_default()).unwrap_or(Value::Null)
                    }),
                })
            }
            _ => Err(format!(
                "bad argument '{at}' (a match is an item kind or a table like {{ kind = \"minecraft:paper\" }})"
            )),
        }
    }

    /// What a stack must be to be worth reading in full: its kind and project item, when the match gives them.
    pub fn filter(&self) -> netherforge_platform::StackFilter {
        netherforge_platform::StackFilter {
            kind: self.kind.clone(),
            item: self
                .fields
                .get("item")
                .and_then(Value::as_str)
                .map(str::to_owned),
        }
    }

    pub fn matches(&self, item: &ItemData) -> bool {
        if let Some(kind) = &self.kind
            && netherforge_format::game::normalize(&item.def.kind) != *kind
        {
            return false;
        }
        if self.fields.is_empty() && self.data.is_none() {
            return true;
        }
        let written = written(item);
        if self
            .fields
            .iter()
            .any(|(k, v)| written.get(k).unwrap_or(&Value::Null) != v)
        {
            return false;
        }
        match &self.data {
            Some(data) => subset(
                data,
                &serde_json::to_value(item.def.data.clone().unwrap_or_default())
                    .unwrap_or(Value::Null),
            ),
            None => true,
        }
    }

    /// How many of `contents`' items match.
    pub fn count(&self, contents: &[Option<ItemData>]) -> i64 {
        contents
            .iter()
            .flatten()
            .filter(|i| self.matches(i))
            .map(|i| i64::from(i.def.count.unwrap_or(1)))
            .sum()
    }

    /// The first slot holding a match.
    pub fn first(&self, contents: &[Option<ItemData>]) -> Option<usize> {
        contents
            .iter()
            .position(|i| i.as_ref().is_some_and(|i| self.matches(i)))
    }

    /// Takes up to `limit` (every one, for none) matching items from `contents`, first slot first, writing each
    /// changed slot with `write`: how many it took.
    pub fn remove(
        &self,
        contents: &[Option<ItemData>],
        limit: Option<i64>,
        mut write: impl FnMut(usize, Option<ItemData>),
    ) -> i64 {
        let mut left = limit.unwrap_or(i64::MAX);
        let mut taken = 0;
        for (slot, item) in contents.iter().enumerate() {
            if left <= 0 {
                break;
            }
            let Some(item) = item.as_ref().filter(|i| self.matches(i)) else {
                continue;
            };
            let count = i64::from(item.def.count.unwrap_or(1));
            let take = count.min(left);
            let rest = (take < count).then(|| {
                let mut rest = item.clone();
                rest.def.count = Some((count - take) as i32);
                rest
            });
            write(slot, rest);
            left -= take;
            taken += take;
        }
        taken
    }
}

/// A stack as a script reads it, as a JSON object.
fn written(item: &ItemData) -> Map<String, Value> {
    match serde_json::from_str(&to_value(item).0) {
        Ok(Value::Object(map)) => map,
        _ => Map::new(),
    }
}

/// Whether every key of `part` is in `whole` with the same value, objects compared the same way all the way down.
fn subset(part: &Value, whole: &Value) -> bool {
    match (part, whole) {
        (Value::Object(part), Value::Object(whole)) => part
            .iter()
            .all(|(k, v)| whole.get(k).is_some_and(|w| subset(v, w))),
        (Value::Object(_), _) => false,
        _ => part == whole,
    }
}

/// serde's message, its field names in Lua's spelling (`unknown field `hideTooltp``).
fn lua_spelling(message: &str) -> String {
    let mut out = String::new();
    let mut rest = message;
    while let Some(start) = rest.find('`') {
        out.push_str(&rest[..start]);
        let after = &rest[start + 1..];
        let Some(end) = after.find('`') else {
            out.push_str(&rest[start..]);
            return out;
        };
        out.push('`');
        out.push_str(&snake(&after[..end]));
        out.push('`');
        rest = &after[end + 1..];
    }
    out.push_str(rest);
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    struct NoFacts;
    impl GameData for NoFacts {
        fn minecraft_version(&self) -> &str {
            "26.3"
        }
        fn registry(&self, _: &str) -> Option<&std::collections::BTreeSet<String>> {
            None
        }
        fn tag(&self, _: &str, _: &str) -> Option<&std::collections::BTreeSet<String>> {
            None
        }
        fn item_durability(&self, _: &str) -> u32 {
            0
        }
        fn particle(&self, _: &str) -> Option<netherforge_format::game::ParticleData> {
            None
        }
        fn block_properties(
            &self,
            _: &str,
        ) -> Option<std::collections::BTreeMap<String, Vec<String>>> {
            None
        }
    }

    #[test]
    fn an_item_crosses_in_lua_spelling_and_back() {
        let item = ItemData {
            def: ItemDef {
                kind: "minecraft:bread".into(),
                hide_tooltip: Some(true),
                count: Some(3),
                ..ItemDef::default()
            },
            raw: Some("opaque".into()),
            look: None,
        };
        let table = to_value(&item);
        assert!(table.0.contains("\"hide_tooltip\":true"), "{}", table.0);
        assert_eq!(from_value(&table, "item", &NoFacts).unwrap(), item);
        let typo = values::Item(r#"{"kind":"minecraft:bread","hide_tooltp":true}"#.into());
        let message = from_value(&typo, "item", &NoFacts).unwrap_err();
        assert!(
            message.contains("`hide_tooltp`") && message.contains("`hide_tooltip`"),
            "{message}"
        );
    }
}
