//! Every reference each example makes, as the schema's marks find it, is
//! format's golden (`packages/format/testdata/references/<example>.json`,
//! written by Kotlin's walker): a reference a model doesn't mark (or marks
//! that Kotlin doesn't) shows up as a difference.

use std::path::{Path, PathBuf};

use netherforge_format::manifest::FILE_NAME;
use netherforge_format::project;
use netherforge_format::reference::RefKind;
use netherforge_format::schema;

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
        let source = project::Folder::open(&dir).unwrap();
        let packages = netherforge_format::package::Folders { root: dir.clone() };
        let loaded = project::load(
            &source,
            project::Options {
                packages: &packages,
                ..Default::default()
            },
        );
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
        // An example is a valid project: it loads without errors, its packages and all.
        for p in loaded
            .problems
            .iter()
            .filter(|p| p.severity == netherforge_format::Severity::Error)
        {
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
