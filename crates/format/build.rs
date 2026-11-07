//! The problem catalogue as Rust constants, read from `docs/format/problems.md`,
//! which format's Kotlin `ProblemCodes` writes (its golden test). One catalogue:
//! a code that isn't in it doesn't compile. The feature table the same way, from
//! `docs/format/features.md` (Kotlin's `FeatureTable`).

use std::fmt::Write;
use std::path::Path;

fn main() {
    problems();
    features();
}

/// `docs/format/features.md`'s table, a row per feature: `| \`id\` | since | what |`.
fn features() {
    let page = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../docs/format/features.md");
    println!("cargo:rerun-if-changed={}", page.display());
    let text = std::fs::read_to_string(&page).expect("docs/format/features.md");
    let mut out = String::from(
        "// Read from docs/format/features.md by build.rs: every gated feature.\n\n/// Every feature NetherForge uses that the oldest supported version lacks, by id.\npub const ALL: &[Feature] = &[\n",
    );
    for line in text.lines() {
        let cells: Vec<&str> = line.split('|').map(str::trim).collect();
        let [_, id, since, what, _] = cells[..] else {
            continue;
        };
        let Some(id) = id.strip_prefix('`').and_then(|i| i.strip_suffix('`')) else {
            continue;
        };
        writeln!(
            out,
            "    Feature {{ id: {id:?}, since: {since:?}, what: {what:?} }},"
        )
        .unwrap();
    }
    out.push_str("];\n");
    let dest = Path::new(&std::env::var("OUT_DIR").unwrap()).join("features.rs");
    std::fs::write(dest, out).unwrap();
}

fn problems() {
    let page = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../docs/format/problems.md");
    println!("cargo:rerun-if-changed={}", page.display());
    let text = std::fs::read_to_string(&page).expect("docs/format/problems.md");
    let mut out =
        String::from("// Read from docs/format/problems.md by build.rs: every problem code.\n\n");
    let mut all = Vec::new();
    let lines: Vec<&str> = text.lines().collect();
    for (k, line) in lines.iter().enumerate() {
        let Some(rest) = line.strip_prefix("### `") else {
            continue;
        };
        let code = rest.split('`').next().expect("a code");
        let body = lines[k + 1..]
            .iter()
            .find(|l| !l.trim().is_empty())
            .expect("a code's description");
        let (severity, summary) = body.split_once(". ").expect("severity, then the summary");
        let severity = match severity {
            "Error" => "Severity::Error",
            "Warning" => "Severity::Warning",
            other => panic!("{code}: severity {other}"),
        };
        let (summary, page) = match summary.rsplit_once(" See [") {
            Some((summary, link)) => {
                let page = link
                    .split("](")
                    .nth(1)
                    .and_then(|l| l.split(')').next())
                    .unwrap_or("");
                (summary.trim_end_matches('.'), format!("format/{page}"))
            }
            None => (summary.trim_end_matches('.'), String::new()),
        };
        let name: String = code
            .chars()
            .map(|c| {
                if c.is_ascii_alphanumeric() {
                    c.to_ascii_uppercase()
                } else {
                    '_'
                }
            })
            .collect();
        writeln!(
            out,
            "/// {summary}.\npub const {name}: ProblemCode = ProblemCode {{ code: {code:?}, severity: {severity}, summary: {summary:?}, page: {page:?} }};\n"
        )
        .unwrap();
        all.push(name);
    }
    writeln!(
        out,
        "/// Every code, in the catalogue's order.\npub const ALL: &[ProblemCode] = &[{}];",
        all.join(", ")
    )
    .unwrap();
    let dest = Path::new(&std::env::var("OUT_DIR").unwrap()).join("problem_codes.rs");
    std::fs::write(dest, out).unwrap();
}
