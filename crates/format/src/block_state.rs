//! A block state as files write it, `minecraft:oak_stairs[facing=east]`, and
//! its check: format's `BlockState` and `Rules.blockState`.

use std::fmt;

use crate::game::{self, GameData};
use crate::problem::{ProblemCode, ProblemSink};

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct BlockState {
    /// Namespaced.
    pub id: String,
    /// In the order written.
    pub properties: Vec<(String, String)>,
}

impl BlockState {
    /// `None` when `text` isn't shaped like a block state at all.
    pub fn parse(text: &str) -> Option<BlockState> {
        let text = text.trim();
        let (id, body) = match text.split_once('[') {
            Some((id, rest)) => (id, Some(rest.strip_suffix(']')?)),
            None => (text, None),
        };
        let id = id.trim();
        if id.is_empty()
            || id.contains(']')
            || body.is_some_and(|b| b.contains(['[', ']']))
            || !game::is_id(id)
        {
            return None;
        }
        let mut properties: Vec<(String, String)> = Vec::new();
        if let Some(body) = body.filter(|b| !b.trim().is_empty()) {
            for pair in body.split(',') {
                let parts: Vec<&str> = pair.split('=').collect();
                let [key, value] = parts[..] else { return None };
                let (key, value) = (key.trim(), value.trim());
                if key.is_empty() || value.is_empty() {
                    return None;
                }
                // A key written twice keeps its first place and its last value, as a map does.
                match properties.iter_mut().find(|(k, _)| k == key) {
                    Some(entry) => entry.1 = value.to_owned(),
                    None => properties.push((key.to_owned(), value.to_owned())),
                }
            }
        }
        Some(BlockState {
            id: game::normalize(id),
            properties,
        })
    }
}

/// Its id and its properties, as an object (`{"id": …, "properties": {…}}`).
impl serde::Serialize for BlockState {
    fn serialize<S: serde::Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        use serde::ser::SerializeStruct;
        let properties: serde_json::Map<String, serde_json::Value> = self
            .properties
            .iter()
            .map(|(k, v)| (k.clone(), serde_json::Value::String(v.clone())))
            .collect();
        let mut out = serializer.serialize_struct("BlockState", 2)?;
        out.serialize_field("id", &self.id)?;
        out.serialize_field("properties", &properties)?;
        out.end()
    }
}

/// `id[key=value,…]`, its properties sorted.
impl fmt::Display for BlockState {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.id)?;
        if !self.properties.is_empty() {
            let mut sorted: Vec<_> = self.properties.iter().collect();
            sorted.sort();
            let body: Vec<String> = sorted.iter().map(|(k, v)| format!("{k}={v}")).collect();
            write!(f, "[{}]", body.join(","))?;
        }
        Ok(())
    }
}

/// The codes a block state check reports in, so each kind keeps its own.
#[derive(Clone, Copy)]
pub struct BlockStateCodes {
    pub state: ProblemCode,
    pub unknown: ProblemCode,
    pub property: ProblemCode,
}

/// A block state: well formed, and with game data, a real block with real property values.
pub fn check(
    text: &str,
    at: &str,
    codes: BlockStateCodes,
    sink: &mut ProblemSink,
    game: Option<&dyn GameData>,
) {
    let Some(state) = BlockState::parse(text) else {
        sink.report(
            codes.state,
            format!("\"{text}\" isn't a block state"),
            Some(at.into()),
        );
        return;
    };
    let Some(game) = game else { return };
    let Some(info) = game.block_properties(&state.id) else {
        sink.report(
            codes.unknown,
            format!(
                "Minecraft {} has no block \"{}\"",
                game.minecraft_version(),
                state.id
            ),
            Some(at.into()),
        );
        return;
    };
    for (property, value) in &state.properties {
        match info.get(property) {
            None => sink.report(
                codes.property,
                format!("{} has no property \"{property}\"", state.id),
                Some(at.into()),
            ),
            Some(allowed) if !allowed.contains(value) => sink.report(
                codes.property,
                format!(
                    "{}'s \"{property}\" can't be \"{value}\" (allowed: {})",
                    state.id,
                    allowed.join(", ")
                ),
                Some(at.into()),
            ),
            Some(_) => {}
        }
    }
}
