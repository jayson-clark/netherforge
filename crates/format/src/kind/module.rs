//! `modules/<id>/`: a module's Lua files, which format checks and doesn't
//! read. Format's `ModuleKind`, `ModuleInfo` and `ModuleRequires`.

use std::collections::BTreeSet;
use std::sync::LazyLock;

use regex::Regex;

use super::Source;
use crate::kinds::{by_id, classify};
use crate::names;
use crate::problem::{Problem, codes};
use crate::snapshot::Found;

/// The file a module runs.
pub const ENTRY: &str = "init.lua";

/// A module's own checks, and whether something runs it.
pub fn check(found: &Found, source: &Source) -> Vec<Problem> {
    let kind = found.kind;
    let location = kind.location_of(&found.id, &found.group);
    let files: Vec<&String> = found
        .inside
        .iter()
        .filter(|f| f.ends_with(".lua"))
        .collect();
    let mut problems = Vec::new();
    for file in &files {
        if !names::is_lua_file(file) {
            problems.push(codes::MODULE_FILE_NAME.at(
                &format!("{location}/{file}"),
                "Module files must be named with letters, digits and _ so require() can reach them",
                None,
            ));
        }
    }
    if files.is_empty() {
        problems.push(codes::MODULE_EMPTY.at(
            &location,
            format!("Module \"{}\" has no .lua files", found.id),
            None,
        ));
    }
    let has_init = files.iter().any(|f| *f == ENTRY);
    if !files.is_empty() && !has_init && !source.required_modules.contains(&found.id) {
        problems.push(codes::MODULE_NO_INIT.at(
            &location,
            format!(
                "Module \"{}\" has no {ENTRY} and no script requires it, so it never runs",
                found.id
            ),
            None,
        ));
    }
    problems
}

static REQUIRE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"\brequire\s*\(?\s*(["'])([A-Za-z0-9_.]+)["']"#).expect("a pattern")
});

/// The names `require` is called with in `text`, outside comments.
pub fn required_names(text: &str) -> Vec<String> {
    text.lines()
        .flat_map(|line| {
            let code = line.split("--").next().unwrap_or("");
            REQUIRE
                .captures_iter(code)
                // The regex crate has no backreference: the closing quote must match the opening one.
                .filter(|c| c.get(0).is_some_and(|m| m.as_str().ends_with(&c[1])))
                .map(|c| c[2].to_owned())
                .collect::<Vec<_>>()
        })
        .collect()
}

/// Every module some script in the project requires (`ModuleRequires.of`): a
/// name that a file beside the script answers, or the script's own module,
/// isn't one.
pub fn required(
    files: &BTreeSet<String>,
    read: &dyn Fn(&str) -> Option<String>,
) -> BTreeSet<String> {
    let mut out = BTreeSet::new();
    let module_kind = by_id("module").expect("the module kind");
    for path in files.iter().filter(|p| p.ends_with(".lua")) {
        let Some(text) = read(path) else { continue };
        let from = classify(path);
        let folder = from
            .as_ref()
            .and_then(|c| c.rest.as_ref())
            .filter(|r| !r.is_empty())
            .map(|r| path[..path.len() - r.len() - 1].to_owned());
        for name in required_names(&text) {
            let joined = name.replace('.', "/");
            if let Some(folder) = &folder
                && (files.contains(&format!("{folder}/{joined}.lua"))
                    || files.contains(&format!("{folder}/{joined}/{ENTRY}")))
            {
                continue;
            }
            let id = name.split('.').next().unwrap_or("").to_owned();
            if from.as_ref().is_some_and(|c| {
                c.kind == Some(module_kind.id) && c.id.as_deref() == Some(id.as_str())
            }) {
                continue;
            }
            out.insert(id);
        }
    }
    out
}
