//! Every reference each example makes, as the schema's marks find it, is
//! format's golden (`packages/format/testdata/references/<example>.json`,
//! written by Kotlin's walker): a reference a model doesn't mark (or marks
//! that Kotlin doesn't) shows up as a difference.

use std::collections::BTreeSet;
use std::path::{Path, PathBuf};

use netherforge_format::manifest::FILE_NAME;
use netherforge_format::project;
use netherforge_format::reference::RefKind;
use netherforge_format::schema;

fn files_in(dir: &Path, base: &Path, out: &mut BTreeSet<String>) {
    for entry in std::fs::read_dir(dir).unwrap() {
        let path = entry.unwrap().path();
        if path.is_dir() {
            files_in(&path, base, out);
        } else {
            out.insert(
                path.strip_prefix(base)
                    .unwrap()
                    .to_string_lossy()
                    .replace('\\', "/"),
            );
        }
    }
}

fn root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../..")
}

#[test]
fn every_reference_in_the_examples_is_found() {
    let mut different = Vec::new();
    let mut compared = 0;
    for entry in std::fs::read_dir(root().join("examples")).unwrap() {
        let dir = entry.unwrap().path();
        if !dir.join(FILE_NAME).exists() {
            continue;
        }
        let name = dir.file_name().unwrap().to_string_lossy().to_string();
        let golden = root().join(format!("packages/format/testdata/references/{name}.json"));
        let Ok(expected) = std::fs::read_to_string(&golden) else {
            continue;
        };
        let mut files = BTreeSet::new();
        files_in(&dir, &dir, &mut files);
        let read = |p: &str| std::fs::read_to_string(dir.join(p)).ok();
        let loaded = project::load(&files, &read, None);
        let actual = serde_json::to_value(&loaded.references.uses).unwrap();
        let expected: serde_json::Value = serde_json::from_str(&expected).unwrap();
        compared += 1;
        if actual != expected {
            let a: Vec<String> = actual
                .as_array()
                .unwrap()
                .iter()
                .map(|v| v.to_string())
                .collect();
            let e: Vec<String> = expected
                .as_array()
                .unwrap()
                .iter()
                .map(|v| v.to_string())
                .collect();
            let missing: Vec<&String> = e.iter().filter(|x| !a.contains(x)).collect();
            let extra: Vec<&String> = a.iter().filter(|x| !e.contains(x)).collect();
            different.push(format!(
                "{name}:\n  missing: {missing:#?}\n  extra: {extra:#?}"
            ));
        }
        // An example is a valid project: it loads without errors. Packages
        // aren't loaded yet (`project`'s note), so a reference into a dependency is set aside.
        let dependencies: Vec<String> = loaded
            .manifest
            .iter()
            .flat_map(|m| {
                m.dependencies
                    .iter()
                    .flat_map(|d| d.iter().map(|(k, _)| k.to_string()))
            })
            .collect();
        let into_dependency = |p: &netherforge_format::Problem| {
            p.code.as_deref() == Some("reference.namespace")
                && dependencies
                    .iter()
                    .any(|d| p.message.starts_with(&format!("\"{d}\"")))
        };
        for p in loaded.problems.iter().filter(|p| {
            p.code
                .as_deref()
                .is_some_and(|c| c.starts_with("reference."))
                && !into_dependency(p)
        }) {
            different.push(format!("{name}: {}", p.to_json()));
        }
    }
    assert!(compared > 3, "{compared} examples");
    assert!(different.is_empty(), "{}", different.join("\n"));
}

/// Every mark in every kind's schema names a kind of reference.
#[test]
fn every_mark_names_a_kind_of_reference() {
    let ids = netherforge_format::kinds::ALL.iter().map(|k| k.id);
    let mut marks = 0;
    for kind in ids.filter_map(netherforge_format::kind::document_kind) {
        for (at, mark) in schema::places_with(&kind.schema().root, "x-ref") {
            marks += 1;
            assert!(
                mark.as_str().and_then(RefKind::of).is_some(),
                "{at}: x-ref {mark}"
            );
        }
    }
    assert!(marks > 50, "{marks} marks");
}

#[test]
fn glyph_tags_are_found_as_minimessage_splits_them() {
    use netherforge_format::reference::glyphs;
    let found: Vec<(String, usize, usize)> =
        glyphs("é <glyph:acme:ui/coin> \\<glyph:no> <GLYPH:'a:b/c'> <bold>")
            .into_iter()
            .map(|g| (g.reference, g.start, g.end))
            .collect();
    assert_eq!(
        found,
        [("acme:ui/coin".into(), 2, 22), ("a:b/c".into(), 35, 50)]
    );
}
