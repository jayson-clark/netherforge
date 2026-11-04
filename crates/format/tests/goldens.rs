//! Format's golden cases (`packages/format/testdata/invalid/<case>/expected.json`),
//! as far as the Rust port reaches: each case's problems in the files it
//! loads, against what the Kotlin side reports.

use std::collections::BTreeSet;
use std::path::{Path, PathBuf};

use netherforge_format::manifest::{self, FILE_NAME};
use serde_json::Value;

fn invalid() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../../packages/format/testdata/invalid")
}

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

/// Codes the manifest's own checks report; others about netherforge.json come
/// from checks across files (references, packages), not ported yet.
fn manifests_own(code: &str) -> bool {
    code == "parse"
        || (code.starts_with("project.")
            && !matches!(
                code,
                "project.dependency" | "project.export" | "project.world-height"
            ))
}

/// The codes of a project's layout: what the scan reports, in any file.
fn layouts(code: &str) -> bool {
    matches!(
        code,
        "project.id"
            | "project.missing-file"
            | "project.stray-file"
            | "project.group"
            | "project.duplicate-id"
    )
}

#[test]
fn the_layouts_problems_are_formats() {
    let mut failures = Vec::new();
    let mut checked = 0;
    for entry in std::fs::read_dir(invalid()).unwrap() {
        let dir = entry.unwrap().path();
        let expected: Vec<Value> =
            serde_json::from_str(&std::fs::read_to_string(dir.join("expected.json")).unwrap())
                .unwrap();
        let expected: Vec<String> = expected
            .into_iter()
            .filter(|p| layouts(p["code"].as_str().unwrap_or("")))
            .map(|p| p.to_string())
            .collect();
        let mut files = BTreeSet::new();
        files_in(&dir, &dir, &mut files);
        files.remove("expected.json");
        let mut problems = netherforge_format::snapshot::scan(&files).problems;
        // The loader's order: by file, then line (none here), keeping the scan's order otherwise.
        problems.sort_by(|a, b| a.file.cmp(&b.file));
        let actual: Vec<String> = problems.iter().map(|p| p.to_json().to_string()).collect();
        checked += expected.len();
        if actual != expected {
            failures.push(format!(
                "{}:\n  actual:   {actual:#?}\n  expected: {expected:#?}",
                dir.file_name().unwrap().to_string_lossy()
            ));
        }
    }
    assert!(checked > 5, "{checked} problems");
    assert!(
        failures.is_empty(),
        "{} cases differ:\n{}",
        failures.len(),
        failures.join("\n")
    );
}

#[test]
fn the_manifests_problems_are_formats() {
    let mut failures = Vec::new();
    let mut cases = 0;
    for entry in std::fs::read_dir(invalid()).unwrap() {
        let dir = entry.unwrap().path();
        let expected: Vec<Value> =
            serde_json::from_str(&std::fs::read_to_string(dir.join("expected.json")).unwrap())
                .unwrap();
        let expected: Vec<String> = expected
            .into_iter()
            .filter(|p| p["file"] == FILE_NAME && manifests_own(p["code"].as_str().unwrap_or("")))
            .map(|p| p.to_string())
            .collect();
        let text = std::fs::read_to_string(dir.join(FILE_NAME)).ok();
        let (manifest, mut problems) = manifest::load(text.as_deref());
        let mut files = BTreeSet::new();
        files_in(&dir, &dir, &mut files);
        problems.extend(manifest::listing_files(manifest.as_ref(), &files));
        let actual: Vec<String> = problems
            .iter()
            .map(|p| p.to_json())
            .filter(|p| manifests_own(p["code"].as_str().unwrap_or("")))
            .map(|p| p.to_string())
            .collect();
        cases += 1;
        if actual != expected {
            failures.push(format!(
                "{}:\n  actual:   {actual:#?}\n  expected: {expected:#?}",
                dir.file_name().unwrap().to_string_lossy()
            ));
        }
    }
    assert!(cases > 40, "{cases} cases");
    assert!(
        failures.is_empty(),
        "{} of {cases} cases differ:\n{}",
        failures.len(),
        failures.join("\n")
    );
}

/// What a problem says is wrong and where, not its wording: the Rust port words
/// its field rules from their schema (`rules`), so only these must agree.
fn what_and_where(p: &Value) -> String {
    // A parse problem's position is the reader's to say (`tests/read.rs` holds it): kotlinx
    // gives none for a custom type's error, where serde does.
    let keep: &[&str] = if p["code"] == "parse" {
        &["severity", "file", "code"]
    } else {
        &[
            "severity", "file", "path", "line", "column", "code", "related",
        ]
    };
    let o: serde_json::Map<String, Value> = p
        .as_object()
        .unwrap()
        .iter()
        .filter(|(k, _)| keep.contains(&k.as_str()))
        .map(|(k, v)| (k.clone(), v.clone()))
        .collect();
    Value::Object(o).to_string()
}

/// Problems about one resource alone; a reference's (`reference.*`) is a check across resources.
fn own(code: &str) -> bool {
    // A project item's own kind, the project's default font: checks across resources too.
    // Each kind's `crossCheck` in format: they come with the checks across resources.
    const ACROSS: &[&str] = &[
        "item.kind-mismatch",
        "loot.cycle",
        "advancement.cycle",
        "build.id-taken",
        "biome.feature-order",
    ];
    let across =
        code.starts_with("reference.") || code.starts_with("font.") || ACROSS.contains(&code);
    !across && !layouts(code)
}

#[test]
fn the_ported_kinds_problems_are_formats() {
    use netherforge_format::kind;
    let mut failures = Vec::new();
    let mut checked = 0;
    for entry in std::fs::read_dir(invalid()).unwrap() {
        let dir = entry.unwrap().path();
        let expected: Vec<Value> =
            serde_json::from_str(&std::fs::read_to_string(dir.join("expected.json")).unwrap())
                .unwrap();
        let mut files = BTreeSet::new();
        files_in(&dir, &dir, &mut files);
        files.remove("expected.json");
        let read = |p: &str| std::fs::read_to_string(dir.join(p)).ok();
        let source = kind::Source::new(&files, &read);
        for found in netherforge_format::snapshot::scan(&files).resources {
            // A kind not ported yet answers `None`.
            let Some(problems) = kind::read(&found, &source) else {
                continue;
            };
            let path = found.kind.path_of(&found.id, &found.group);
            let location = found.kind.location_of(&found.id, &found.group);
            let mut actual: Vec<String> = problems
                .iter()
                .map(|p| p.to_json())
                .filter(|p| own(p["code"].as_str().unwrap_or("")))
                .map(|p| what_and_where(&p))
                .collect();
            actual.sort();
            let mut wanted: Vec<String> = expected
                .iter()
                .filter(|p| {
                    let file = p["file"].as_str().unwrap_or("");
                    (file == path || file == location || file.starts_with(&format!("{location}/")))
                        && own(p["code"].as_str().unwrap_or(""))
                })
                .map(what_and_where)
                .collect();
            wanted.sort();
            checked += wanted.len();
            if actual != wanted {
                failures.push(format!(
                    "{} {path}:\n  actual:   {actual:#?}\n  expected: {wanted:#?}",
                    dir.file_name().unwrap().to_string_lossy()
                ));
            }
        }
    }
    assert!(checked > 0, "no problems checked");
    assert!(
        failures.is_empty(),
        "{} files differ:\n{}",
        failures.len(),
        failures.join("\n")
    );
}

/// Every resource of a ported kind in the examples (valid projects) has no
/// problems, and a document writes back to the same bytes.
#[test]
fn the_ported_kinds_examples_write_back() {
    use netherforge_format::kind;
    let examples = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../examples");
    let mut different = Vec::new();
    let mut written = 0;
    for entry in std::fs::read_dir(&examples).unwrap() {
        let dir = entry.unwrap().path();
        if !dir.join(FILE_NAME).exists() {
            continue;
        }
        let mut files = BTreeSet::new();
        files_in(&dir, &dir, &mut files);
        let read = |p: &str| std::fs::read_to_string(dir.join(p)).ok();
        let source = kind::Source::new(&files, &read);
        for found in netherforge_format::snapshot::scan(&files).resources {
            let Some(problems) = kind::read(&found, &source) else {
                continue;
            };
            for p in problems
                .iter()
                .filter(|p| p.severity == netherforge_format::Severity::Error)
            {
                different.push(format!("{}: {}", dir.display(), p.to_json()));
            }
            let path = found.kind.path_of(&found.id, &found.group);
            let Some(text) = read(&path) else { continue };
            let Some(again) = kind::write_back(&found, &text) else {
                continue;
            };
            let again = again.expect("an example parses");
            written += 1;
            if again != text {
                different.push(format!("{}/{path}:\n{again}", dir.display()));
            }
        }
    }
    assert!(written > 0, "no examples of ported kinds");
    assert!(different.is_empty(), "{different:#?}");
}
