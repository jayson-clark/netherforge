//! What every document kind shares: its `$schema` (relative to where the file
//! is), its canonical form, writing, and reading a resource with its checks.
//! Format's `DocumentKind` and `DocumentResourceKind`.

use serde::Serialize;
use serde::de::DeserializeOwned;

use crate::kinds::split_package;
use crate::problem::{Problem, ProblemSink};

pub trait Document: Serialize + DeserializeOwned + Clone {
    /// The schema the kind's files name, from a file one folder deep
    /// (`../.netherforge/schema/buff.schema.json`).
    const SCHEMA: &'static str;

    fn set_schema(&mut self, schema: Option<String>);

    /// Its canonical form besides the schema: sets sorted, keyframes in time
    /// order. Most kinds have none.
    fn canonical(&self) -> Self {
        self.clone()
    }

    /// Its own checks, on itself alone.
    fn validate(&self, _sink: &mut ProblemSink) {}
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
pub fn load<T: Document>(text: &str, path: &str) -> (Option<T>, Vec<Problem>) {
    match crate::read::parse::<T>(text, path) {
        Err(problem) => (None, vec![*problem]),
        Ok(value) => {
            let mut sink = ProblemSink::new(path);
            value.validate(&mut sink);
            (Some(value), sink.problems)
        }
    }
}
