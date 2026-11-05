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
    Ok(ItemData {
        def,
        raw,
        look: None,
    })
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
