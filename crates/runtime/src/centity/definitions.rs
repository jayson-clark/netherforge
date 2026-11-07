//! A node a script describes (`centity:add_node`, `node:add_child`), read as `centity.json` would read it
//! (Kotlin's `Definitions.nodeFrom`): the table in the file's spelling (snake_case keys to camelCase, a
//! vector as `[x, y, z]`), read strictly by format, checked by format's `check_node`, and compiled by
//! `compile_node`. A mistake is an error naming its place in Lua's spelling (`definition.display.color`).

use mlua::Value;
use netherforge_format::game::GameData;
use netherforge_format::kind::centity::compiled::{Node, compile_node};
use netherforge_format::kind::centity::{NodeDef, check_node, node_path};
use netherforge_format::names::{NODE_NAME_RULE, is_node_name};
use netherforge_format::problem::{Problem, ProblemSink, Severity};
use netherforge_luau::Host;
use serde_json::{Map, Value as Json};

/// What a definition is called in messages.
pub const ROOT: &str = "definition";

/// The keys of a node definition whose values are lists: an empty Lua table there is the empty list.
const NODE_LISTS: &[&str] = &["boxes"];

/// The node a script described as `name`, under the node at `parent_index` (-1 for a root).
pub fn node_from(
    host: &Host,
    name: &str,
    parent_index: i32,
    definition: &Value,
    game: Option<&dyn GameData>,
) -> Result<Node, String> {
    if !is_node_name(name) {
        return Err(format!(
            "bad argument 'name' (\"{name}\" isn't a usable node name: {NODE_NAME_RULE})"
        ));
    }
    let table = match definition {
        Value::Nil => Json::Object(Map::new()),
        Value::Table(_) => {
            let encoded = netherforge_luau::json::encode(
                host,
                definition,
                ROOT,
                "read",
                &netherforge_luau::json::NoTags,
            )
            .map_err(|stopped| stopped.0.to_string())?;
            if let Some(problem) = encoded.problems.into_iter().next() {
                return Err(format!("{ROOT}: {problem}"));
            }
            serde_json::from_str(&encoded.text.unwrap_or_default()).map_err(|e| e.to_string())?
        }
        _ => return Err(format!("{ROOT} must be a table")),
    };
    let Json::Object(object) = &table else {
        return Err(format!("{ROOT} must be a table"));
    };
    if object.contains_key("parent") {
        return Err(format!(
            "{ROOT}.parent: a node's parent is the node it's added to (add_node adds a root)"
        ));
    }
    if object.contains_key("rope") {
        return Err(format!(
            "{ROOT}.rope: a rope's links are built with its centity, so only centity.json can have one"
        ));
    }
    let file = spelled(table, ROOT)?.to_string();
    let def: NodeDef = netherforge_format::read::parse(&file, ROOT).map_err(|p| message(&p))?;
    let mut sink = ProblemSink::new(ROOT);
    check_node(name, &def, &mut sink, game);
    let prefix = node_path(name);
    if let Some(problem) = sink.problems.iter().find(|p| p.severity == Severity::Error) {
        let mut problem = problem.clone();
        problem.path = problem.path.map(|p| {
            p.strip_prefix(&prefix)
                .map_or(p.clone(), |rest| format!("${rest}"))
        });
        return Err(message(&problem));
    }
    Ok(compile_node(name, &def, parent_index))
}

/// A node table as the file spells it: snake_case keys to camelCase, a vector as `[x, y, z]`, an empty
/// list as one.
fn spelled(value: Json, at: &str) -> Result<Json, String> {
    Ok(match value {
        Json::Object(map) => {
            if map.len() == 1
                && let Some(Json::Array(parts)) = map.get("$vec3")
            {
                return Ok(Json::Array(parts.clone()));
            }
            let mut out = Map::new();
            for (key, value) in map {
                if key.chars().any(|c| c.is_ascii_uppercase()) {
                    return Err(format!(
                        "{at}.{key}: Lua spells keys in snake_case, so it's {}",
                        snake(&key)
                    ));
                }
                let value = if NODE_LISTS.contains(&key.as_str())
                    && matches!(&value, Json::Object(m) if m.is_empty())
                {
                    Json::Array(Vec::new())
                } else {
                    spelled(value, &format!("{at}.{key}"))?
                };
                out.insert(camel(&key), value);
            }
            Json::Object(out)
        }
        Json::Array(items) => Json::Array(
            items
                .into_iter()
                .enumerate()
                .map(|(i, v)| spelled(v, &format!("{at}[{}]", i + 1)))
                .collect::<Result<_, _>>()?,
        ),
        other => other,
    })
}

fn camel(key: &str) -> String {
    let mut out = String::new();
    let mut chars = key.chars().peekable();
    while let Some(c) = chars.next() {
        if c == '_'
            && let Some(next) = chars.peek()
            && (next.is_ascii_lowercase() || next.is_ascii_digit())
        {
            out.push(next.to_ascii_uppercase());
            chars.next();
        } else {
            out.push(c);
        }
    }
    out
}

/// `maxLength` → `max_length`.
pub fn snake(key: &str) -> String {
    let mut out = String::new();
    let mut previous: Option<char> = None;
    for c in key.chars() {
        if c.is_ascii_uppercase()
            && previous.is_some_and(|p| p.is_ascii_lowercase() || p.is_ascii_digit())
        {
            out.push('_');
            out.push(c.to_ascii_lowercase());
        } else {
            out.push(c);
        }
        previous = Some(c);
    }
    out
}

/// A message's camelCase words (outside quotes) in snake_case, as Lua spells them (Kotlin's `luaSpelling`).
pub fn lua_spelling(message: &str) -> String {
    message
        .split('"')
        .enumerate()
        .map(|(i, part)| {
            if i % 2 == 1 {
                return part.to_owned();
            }
            part.split_inclusive(|c: char| !c.is_ascii_alphanumeric())
                .map(|word| {
                    let (body, tail) = word.split_at(
                        word.trim_end_matches(|c: char| !c.is_ascii_alphanumeric())
                            .len(),
                    );
                    let camel_word = body.starts_with(|c: char| c.is_ascii_lowercase())
                        && body.chars().any(|c| c.is_ascii_uppercase());
                    if camel_word {
                        format!("{}{tail}", snake(body))
                    } else {
                        word.to_owned()
                    }
                })
                .collect()
        })
        .collect::<Vec<String>>()
        .join("\"")
}

/// `$.inputs[0].maxLength` → `definition.inputs[1].max_length`; a quoted key (`$.slots["13"]`) stays as it is.
pub fn lua_path(path: &str) -> String {
    let path = path.strip_prefix('$').unwrap_or(path);
    let mut out = String::from(ROOT);
    let mut rest = path;
    while let Some(open) = rest.find('[') {
        out.push_str(&snake(&rest[..open]));
        let Some(close) = rest[open..].find(']') else {
            break;
        };
        let inside = &rest[open + 1..open + close];
        match inside.parse::<usize>() {
            // A list position counts from 1, as Lua's do.
            Ok(n) => out.push_str(&format!("[{}]", n + 1)),
            Err(_) => out.push_str(&format!("[{}]", inside.trim_matches('"'))),
        }
        rest = &rest[open + close + 1..];
    }
    out.push_str(&snake(rest));
    out
}

/// A format problem as a script's error: its place in the definition in Lua's spelling, then what's wrong.
fn message(problem: &Problem) -> String {
    let text = lua_spelling(&problem.message);
    match &problem.path {
        Some(path) => format!("{}: {text}", lua_path(path)),
        None => format!("{ROOT}: {text}"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn paths_and_words_are_spelled_as_lua_spells_them() {
        assert_eq!(
            lua_path("$.inputs[0].maxLength"),
            "definition.inputs[1].max_length"
        );
        assert_eq!(
            lua_path("$.slots[\"13\"].item"),
            "definition.slots[13].item"
        );
        assert_eq!(
            lua_spelling("lineWidth must be \"lineWidth\""),
            "line_width must be \"lineWidth\""
        );
        assert_eq!(camel("item_transform"), "itemTransform");
    }
}
