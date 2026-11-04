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
