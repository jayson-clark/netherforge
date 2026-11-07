//! Every code the plugin reports while a project runs (format's `ProblemCodes.reportedAtRuntime`, read from its file
//! here so the two can't drift) is produced by one of the runtime's tests, so one can't lose its last case unseen.
//! format's own `ProblemCoverageTest` holds every other code to a golden or a unit test there.
//!
//! The test named for a code must exist and name the code (its string or its `codes::` constant). A code in
//! [`UNTESTED`] is a gap to close, never a place to park a new one.

const TESTS: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/tests");

/// Each code, and the test file (under `crates/runtime/tests`) that produces it.
const PRODUCED_BY: &[(&str, &str)] = &[
    ("runtime.wasm", "wasm.rs"),
    ("migration.failed", "databases.rs"),
    ("migration.dialect", "databases.rs"),
    ("video.stale", "screens.rs"),
    ("runtime.minecraft-unsupported", "session.rs"),
    ("runtime.unbundled", "project_files.rs"),
    ("runtime.bundle-hash", "project_files.rs"),
    ("runtime.provided-package", "project_files.rs"),
    ("runtime.policy", "network.rs"),
    ("runtime.bundle-signature", "project_files.rs"),
    ("runtime.plugin-missing", "interop.rs"),
    ("runtime.web-disabled", "web.rs"),
    ("runtime.network-disabled", "network.rs"),
    ("runtime.bots-disabled", "centities.rs"),
    ("runtime.restart", "reload.rs"),
    ("settings.file", "settings.rs"),
    ("settings.value", "settings.rs"),
    ("settings.unknown", "settings.rs"),
    ("script.error", "session.rs"),
    ("script.slow", "profile.rs"),
    ("particles.budget", "centities.rs"),
    ("runtime.written-pack", "project_files.rs"),
];

/// Codes no Rust test produces yet (the integration scenarios do, on Paper): each a gap to close.
const UNTESTED: &[&str] = &[
    "terrain.script-failed",
    "migration.ahead",
    "runtime.blocks",
    "runtime.terrain",
    "runtime.dimension-type",
    "runtime.datapack",
    "runtime.pack-format",
];

/// `reportedAtRuntime`'s codes, from format's `ProblemCodes.kt`: each constant's code string.
fn reported_at_runtime() -> Vec<String> {
    let text = std::fs::read_to_string(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../../packages/format/src/commonMain/kotlin/dev/netherforge/format/ProblemCodes.kt"
    ))
    .expect("format's ProblemCodes.kt");
    let set = text
        .split("val reportedAtRuntime: Set<ProblemCode> = setOf(")
        .nth(1)
        .and_then(|rest| rest.split(')').next())
        .expect("reportedAtRuntime");
    let mut codes: Vec<String> = set
        .split(',')
        .map(str::trim)
        .filter(|c| !c.is_empty())
        .map(|constant| {
            let declared = format!("val {constant} =");
            let at = text
                .find(&declared)
                .unwrap_or_else(|| panic!("{constant} isn't declared"));
            let quoted = &text[at..];
            let start = quoted.find('"').expect("a code") + 1;
            let end = start + quoted[start..].find('"').expect("a code's end");
            quoted[start..end].to_owned()
        })
        .collect();
    codes.sort();
    codes
}

#[test]
fn every_runtime_code_has_a_test_or_is_a_known_gap() {
    let mut listed: Vec<String> = PRODUCED_BY
        .iter()
        .map(|(code, _)| code.to_string())
        .chain(UNTESTED.iter().map(|c| c.to_string()))
        .collect();
    listed.sort();
    assert_eq!(reported_at_runtime(), listed);
}

#[test]
fn the_tests_named_produce_their_codes() {
    for (code, file) in PRODUCED_BY {
        let text = std::fs::read_to_string(format!("{TESTS}/{file}"))
            .unwrap_or_else(|_| panic!("{code}: {file} doesn't exist"));
        let constant = code.to_uppercase().replace(['.', '-'], "_");
        assert!(
            text.contains(&format!("\"{code}")) || text.contains(&constant),
            "{code}: {file} doesn't name it, so it can't be what tests it"
        );
    }
}
