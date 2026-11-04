//! What every document kind shares: its `$schema` (relative to where the file
//! is), its canonical form, writing, and reading a resource with its checks.
//! Format's `DocumentKind` and `DocumentResourceKind`.

use serde::Serialize;
use serde::de::DeserializeOwned;

use std::collections::BTreeSet;

use crate::game::GameData;
use crate::kinds::split_package;
use crate::problem::{Problem, ProblemSink};

pub trait Document: Serialize + DeserializeOwned + Clone + schemars::JsonSchema {
    /// The schema the kind's files name, from a file one folder deep
    /// (`../.netherforge/schema/buff.schema.json`).
    const SCHEMA: &'static str;

    fn set_schema(&mut self, schema: Option<String>);

    /// Its canonical form besides the schema: sets sorted, keyframes in time
    /// order. Most kinds have none.
    fn canonical(&self) -> Self {
        self.clone()
    }

    /// Its checks on itself alone that its fields' rules can't say (see
    /// `rules`): those run first, from its schema.
    fn validate(&self, _cx: &Context, _sink: &mut ProblemSink) {}
}

/// What a resource's checks may look at besides itself: the files in its
/// folder (its paths there; `None` when they aren't known), and the game.
#[derive(Clone, Copy, Default)]
pub struct Context<'a> {
    pub files: Option<&'a BTreeSet<String>>,
    pub game: Option<&'a dyn GameData>,
}

/// The schema a file at `path` names: `SCHEMA` with a `../` for each folder
/// it's in (`schemaAt`).
pub fn schema_at(schema: &str, path: &str) -> String {
    let mut target = schema;
    while let Some(rest) = target.strip_prefix("../") {
        target = rest;
    }
    let local = split_package(path).1;
    format!("{}{target}", "../".repeat(local.matches('/').count()))
}

/// `value` as the file at `path`: canonical, with its schema.
pub fn write<T: Document>(value: &T, path: &str) -> String {
    let mut canonical = value.canonical();
    canonical.set_schema(Some(schema_at(T::SCHEMA, path)));
    crate::canonical::write(&canonical)
}

/// A document read from the file at `path` and checked on its own: the value
/// when it parsed, and its problems.
pub fn load<T: Document>(text: &str, path: &str, cx: &Context) -> (Option<T>, Vec<Problem>) {
    match crate::read::parse::<T>(text, path) {
        Err(problem) => (None, vec![*problem]),
        Ok(value) => {
            let mut sink = ProblemSink::new(path);
            // The file as it's written (its maps in their order), against the model's rules.
            let json: serde_json::Value =
                serde_json::from_str(text).expect("it parsed as the model");
            crate::rules::check::<T>(&json, &mut sink);
            value.validate(cx, &mut sink);
            (Some(value), sink.problems)
        }
    }
}
