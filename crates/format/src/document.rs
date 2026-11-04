//! What every document kind shares: its `$schema` (relative to where the file
//! is), its canonical form, writing, and reading a resource with its checks.
//! Format's `DocumentKind` and `DocumentResourceKind`.

use serde::Serialize;
use serde::de::DeserializeOwned;

use std::collections::BTreeSet;

use crate::game::GameData;
use crate::kinds::split_package;
use crate::problem::{Problem, ProblemSink};

pub trait Document: Serialize + DeserializeOwned + Clone + schemars::JsonSchema + 'static {
    /// The schema the kind's files name, from a file one folder deep
    /// (`../.netherforge/schema/buff.schema.json`).
    const SCHEMA: &'static str;

    fn set_schema(&mut self, schema: Option<String>);

    /// Its canonical form besides the schema: sets sorted, keyframes in time
    /// order. Most kinds have none.
    fn canonical(&self) -> Self {
        self.clone()
    }

    /// Its resources run a script: the `.lua` files beside it are the
    /// script's to `require`, so they're named as a module's are.
    const SCRIPTED: bool = false;

    /// Its script, when it has one.
    fn script(&self) -> Option<&crate::script::ScriptDef> {
        None
    }

    /// Its checks on itself alone that its fields' rules can't say (see
    /// `rules`): those run first, from its schema.
    fn validate(&self, _cx: &Context, _sink: &mut ProblemSink) {}

    /// Its checks against the rest of the project (`crossCheck`), once every
    /// resource has read and been checked on its own: what it means beyond its
    /// references existing, which the loader checks itself. Problems in its
    /// other files go into the sink's problems with their own file.
    fn cross_check(&self, _cx: &crate::project::Across, _sink: &mut ProblemSink) {}
}

/// Reads a file by its path, if it's there.
pub type ReadFile<'a> = &'a dyn Fn(&str) -> Option<String>;

/// What a resource's checks may look at besides itself: its id, the files in
/// its folder (its paths there; `None` when they aren't known), and the game.
#[derive(Clone, Copy, Default)]
pub struct Context<'a> {
    pub id: &'a str,
    /// Where the resource is in the project (its folder, or its file).
    pub location: &'a str,
    /// Reads a file in the resource's folder, by its path there.
    pub read: Option<ReadFile<'a>>,
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

/// A document read and checked on its own: the model and its file's JSON
/// (its maps in the file's order), when it parsed, and its problems.
pub struct Read<T> {
    pub value: Option<T>,
    pub json: Option<serde_json::Value>,
    pub problems: Vec<Problem>,
}

/// A document read from the file at `path` and checked on its own.
pub fn load<T: Document>(text: &str, path: &str, cx: &Context) -> Read<T> {
    // Every rule a kind declares names its problem: checked on every kind any test loads, so
    // no list of kinds can leave one out.
    debug_assert_eq!(
        crate::rules::uncoded::<T>(),
        Vec::<String>::new(),
        "{path}: a rule with no x-problem"
    );
    match crate::read::parse::<T>(text, path) {
        Err(problem) => Read {
            value: None,
            json: None,
            problems: vec![*problem],
        },
        Ok(value) => {
            let mut sink = ProblemSink::new(path);
            // The file as it's written (its maps in their order), against the model's rules.
            let json: serde_json::Value =
                serde_json::from_str(text).expect("it parsed as the model");
            crate::rules::check::<T>(&json, &mut sink);
            value.validate(cx, &mut sink);
            let mut problems = sink.problems;
            if T::SCRIPTED {
                problems.extend(script_files(value.script(), path, cx));
            }
            Read {
                value: Some(value),
                json: Some(json),
                problems,
            }
        }
    }
}

/// Lua beside a resource's script that `require` can't reach.
fn script_files(
    script: Option<&crate::script::ScriptDef>,
    path: &str,
    cx: &Context,
) -> Vec<Problem> {
    let folder = path.rsplit_once('/').map_or("", |(f, _)| f);
    cx.files
        .into_iter()
        .flatten()
        .filter(|f| f.ends_with(".lua") && Some(f.as_str()) != script.map(|s| s.file.as_str()) && !crate::names::is_lua_file(f))
        .map(|f| {
            crate::codes::SCRIPT_FILE_NAME.at(
                &format!("{folder}/{f}"),
                "Lua files beside a script must be named with letters, digits and _ so require() can reach them",
                None,
            )
        })
        .collect()
}
