//! Menus and dialogs made in Lua (Kotlin's `api/Definitions.kt`): a table in the shape of `menu.json` or
//! `dialog.json`, in Lua spelling (snake_case keys, `Item` tables), read into format's own model and held to the
//! same rules as the files, by the same parser and validator. A mistake is the script's, named by where it is
//! in the table (`definition.inputs[2].max_length`, Lua's 1-based list indices). Names in it are written as
//! the server knows them, checked in the calling package as its files' would be (`Calling::definition`); its
//! items are read as every item a script hands over.

use mlua::Value;
use netherforge_api::{ApiError, ApiResult, values};
use netherforge_format::document::{Context, load};
use netherforge_format::game::GameData;
use netherforge_format::kind::dialog::DialogFile;
use netherforge_format::problem::{Problem, Severity};
use netherforge_luau::Host;
use serde_json::{Map, Value as Json};

use crate::centity::definitions::{ROOT, lua_path, lua_spelling, snake};
use crate::names::Calling;

/// A dialog's keys whose values are lists: an empty Lua table there is the empty list.
const DIALOG_LISTS: &[&str] = &["body", "inputs", "buttons", "dialogs", "options"];

fn err(message: impl Into<String>) -> ApiError {
    ApiError(message.into())
}

/// The table a script handed over as JSON (the codec's, so an item in it reads as any item does).
fn table_json(host: &Host, definition: &Value, like: &str) -> ApiResult<Map<String, Json>> {
    if !matches!(definition, Value::Table(_)) {
        return Err(err(format!("{ROOT} must be a table, like {like}")));
    }
    let encoded = netherforge_luau::json::encode(
        host,
        definition,
        ROOT,
        "read",
        &netherforge_luau::json::NoTags,
    )
    .map_err(|stopped| err(stopped.0.to_string()))?;
    if let Some(problem) = encoded.problems.into_iter().next() {
        return Err(err(format!("{ROOT}: {problem}")));
    }
    match serde_json::from_str(&encoded.text.unwrap_or_default()) {
        Ok(Json::Object(map)) => Ok(map),
        // An empty table encodes as an empty list.
        Ok(Json::Array(list)) if list.is_empty() => Ok(Map::new()),
        Ok(_) => Err(err(format!("{ROOT} must be a table, like {like}"))),
        Err(e) => Err(err(e.to_string())),
    }
}

/// The dialog `definition` describes, validated as `dialog.json` would be. Item bodies' `item` tables are read
/// as every item a script hands over is, then written in the file's spelling.
pub fn dialog_from(
    host: &Host,
    definition: &Value,
    game: &dyn GameData,
    names: &Calling,
) -> ApiResult<DialogFile> {
    let table = table_json(host, definition, "{ title = \"Hello\" }")?;
    if table.contains_key("script") {
        return Err(err(format!(
            "{ROOT}.script: a dialog made in Lua has no script of its own; put handlers on the Dialog it returns"
        )));
    }
    for key in ["pause_menu", "quick_actions"] {
        if table.contains_key(key) {
            return Err(err(format!(
                "{ROOT}.{key}: the server's registry takes dialogs only as it starts, so only a dialog file can be in the player's menus"
            )));
        }
    }
    let spelled = file_spelling(Json::Object(table), ROOT, game)?;
    let named = names.definition(
        netherforge_format::schema::of::<DialogFile>(),
        &spelled,
        &lua_path,
    )?;
    checked::<DialogFile>(&named, game)
}

/// `json`, a file of kind `T`, parsed and validated as the file would be; the first error is the script's.
fn checked<T: netherforge_format::document::Document>(
    json: &Json,
    game: &dyn GameData,
) -> ApiResult<T> {
    let cx = Context {
        id: ROOT,
        location: ROOT,
        read: None,
        files: None,
        game: Some(game),
        minecraft: None,
        features: None,
    };
    let read = load::<T>(&json.to_string(), ROOT, &cx);
    if let Some(problem) = read.problems.iter().find(|p| p.severity == Severity::Error) {
        return Err(err(message(problem)));
    }
    read.value
        .ok_or_else(|| err(format!("{ROOT} isn't a usable definition")))
}

/// A format problem as a script's error: its place in the definition in Lua's spelling, then what's wrong.
fn message(problem: &Problem) -> String {
    let text = lua_spelling(&problem.message);
    match &problem.path {
        Some(path) if path != "$" => format!("{}: {text}", lua_path(path)),
        _ => format!("{ROOT}: {text}"),
    }
}

/// A dialog table in Lua spelling as the file spells it: every key from snake_case to camelCase (a key that's
/// already camelCase is a mistake: Lua spells them all with `_`), and each item body's `item` read as an `Item`.
fn file_spelling(value: Json, at: &str, game: &dyn GameData) -> ApiResult<Json> {
    Ok(match value {
        Json::Object(map) => {
            let item_body =
                map.get("type").and_then(Json::as_str) == Some("item") && map.contains_key("item");
            let mut out = Map::new();
            for (key, value) in map {
                if key.chars().any(|c| c.is_ascii_uppercase()) {
                    return Err(err(format!(
                        "{at}.{key}: Lua spells keys in snake_case, so it's {}",
                        snake(&key)
                    )));
                }
                let place = format!("{at}.{key}");
                let value = if item_body && key == "item" {
                    let item =
                        crate::items::from_value(&values::Item(value.to_string()), &place, game)
                            .map_err(err)?;
                    serde_json::to_value(&item.def).map_err(|e| err(e.to_string()))?
                } else if DIALOG_LISTS.contains(&key.as_str())
                    && matches!(&value, Json::Object(m) if m.is_empty())
                {
                    Json::Array(Vec::new())
                } else {
                    file_spelling(value, &place, game)?
                };
                out.insert(camel(&key), value);
            }
            Json::Object(out)
        }
        Json::Array(items) => Json::Array(
            items
                .into_iter()
                .enumerate()
                .map(|(i, v)| file_spelling(v, &format!("{at}[{}]", i + 1), game))
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
