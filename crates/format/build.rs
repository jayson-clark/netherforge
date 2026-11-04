//! The problem catalogue as Rust constants, read from `docs/format/problems.md`,
//! which format's Kotlin `ProblemCodes` writes (its golden test). One catalogue:
//! a code that isn't in it doesn't compile.

use std::fmt::Write;
use std::path::Path;

fn main() {
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
