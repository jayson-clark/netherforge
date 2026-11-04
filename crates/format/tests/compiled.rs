//! What each example compiles to is format's golden
//! (`packages/format/testdata/compiled/<example>.json`, written by Kotlin's
//! `CompiledGoldenTest`): every resource whose kind compiles it, by kind and the
//! name a server knows it by, and the resource packs built together.
//!
//! Compared as JSON values, a null and an absent field alike, numbers by value
//! (`1` is `1.0`): Kotlin writes a compiled form by reflection, the port by serde.

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

use netherforge_format::manifest::FILE_NAME;
use netherforge_format::project;
use serde_json::Value;

fn root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../..")
}

/// The kinds whose compilers aren't ported yet: the golden's entries for them are left out,
/// and a kind here that compiles anyway is a failure (this list only shrinks).
const NOT_YET: &[&str] = &["datapack", "resource_pack", "resourcePacks"];

#[test]
fn the_examples_compile_as_the_goldens_say() {
    let mut different = Vec::new();
    let mut compared = 0;
    for entry in std::fs::read_dir(root().join("examples")).unwrap() {
        let dir = entry.unwrap().path();
        if !dir.join(FILE_NAME).exists() {
            continue;
        }
        let name = dir.file_name().unwrap().to_string_lossy().to_string();
        let golden = root().join(format!("packages/format/testdata/compiled/{name}.json"));
        let expected: Value =
            serde_json::from_str(&std::fs::read_to_string(&golden).unwrap()).unwrap();
        let source = project::Folder::open(&dir).unwrap();
        let packages = netherforge_format::package::Folders { root: dir.clone() };
        let loaded = project::load(
            &source,
            project::Options {
                packages: &packages,
                ..Default::default()
            },
        );
        let mut actual = BTreeMap::new();
        for kind in netherforge_format::kinds::ALL {
            let resources: serde_json::Map<String, Value> = loaded
                .compiled_of(kind.id)
                .into_iter()
                .map(|(name, compiled)| (name, compiled.json()))
                .collect();
            if !resources.is_empty() {
                assert!(
                    !NOT_YET.contains(&kind.id),
                    "{} compiles now: take it off NOT_YET",
                    kind.id
                );
                actual.insert(kind.id.to_owned(), Value::Object(resources));
            }
        }
        for (kind, resources) in expected.as_object().unwrap() {
            if NOT_YET.contains(&kind.as_str()) {
                continue;
            }
            for (id, want) in resources.as_object().unwrap() {
                compared += 1;
                let got = actual.get(kind).and_then(|r| r.get(id));
                match got {
                    None => different.push(format!("{name}: {kind} {id} didn't compile")),
                    Some(got) => {
                        if let Some(at) = difference(want, got, "$") {
                            different.push(format!("{name}: {kind} {id}: {at}"));
                        }
                    }
                }
            }
        }
        for (kind, resources) in &actual {
            for id in resources.as_object().unwrap().keys() {
                if expected.get(kind).and_then(|r| r.get(id)).is_none() {
                    different.push(format!("{name}: {kind} {id} compiled, the golden has none"));
                }
            }
        }
    }
    eprintln!("compared {compared}");
    assert!(compared > 0, "no compiled goldens compared");
    assert!(
        different.is_empty(),
        "{} of {compared} differ:\n{}",
        different.len(),
        different.join("\n")
    );
}

/// Where `got` first differs from `want`, if it does.
fn difference(want: &Value, got: &Value, at: &str) -> Option<String> {
    match (want, got) {
        (Value::Number(a), Value::Number(b)) if a.as_f64() == b.as_f64() => None,
        (Value::Object(a), Value::Object(b)) => {
            let keys: std::collections::BTreeSet<&String> = a
                .iter()
                .chain(b.iter())
                .filter(|(_, v)| !v.is_null())
                .map(|(k, _)| k)
                .collect();
            keys.into_iter().find_map(|k| {
                let null = Value::Null;
                difference(
                    a.get(k).unwrap_or(&null),
                    b.get(k).unwrap_or(&null),
                    &format!("{at}.{k}"),
                )
            })
        }
        (Value::Array(a), Value::Array(b)) => {
            if a.len() != b.len() {
                return Some(format!("{at}: {} items, not {}", b.len(), a.len()));
            }
            a.iter()
                .zip(b)
                .enumerate()
                .find_map(|(i, (x, y))| difference(x, y, &format!("{at}[{i}]")))
        }
        _ if want == got => None,
        _ => Some(format!("{at}: {got}, not {want}")),
    }
}
