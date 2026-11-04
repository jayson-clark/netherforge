//! Problems: one thing wrong with a project, located as precisely as format
//! can say, and the catalogue of their kinds. Format's `Problem.kt`.

use serde_json::{Value, json};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Severity {
    Error,
    Warning,
}

impl Severity {
    pub fn name(self) -> &'static str {
        match self {
            Severity::Error => "error",
            Severity::Warning => "warning",
        }
    }
}

/// A place in a project: a file, and a JSON path into it when it's about a value.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Location {
    pub file: String,
    pub path: Option<String>,
}

/// One kind of problem: its stable code (what tests and the editor's filters
/// key on), its severity, what it means, and the page that explains it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ProblemCode {
    pub code: &'static str,
    pub severity: Severity,
    pub summary: &'static str,
    pub page: &'static str,
}

impl ProblemCode {
    /// A problem of this kind in `file`.
    pub fn at(self, file: &str, message: impl Into<String>, path: Option<String>) -> Problem {
        Problem {
            severity: self.severity,
            file: file.to_owned(),
            message: message.into(),
            path,
            line: None,
            column: None,
            code: Some(self.code.to_owned()),
            related: Vec::new(),
        }
    }

    /// The code `code` names, if the catalogue has one.
    pub fn of(code: &str) -> Option<ProblemCode> {
        codes::ALL.iter().copied().find(|c| c.code == code)
    }
}

impl Problem {
    /// The same problem, about these other places too.
    pub fn with_related(mut self, related: Vec<Location>) -> Problem {
        self.related = related;
        self
    }
}

/// One thing wrong with a project. `file` is project-relative with `/`
/// separators; `path` a JSON path into it (`$.nodes.top.display`) when it's
/// about a value; `line` and `column` are 1-based and known for a parse error.
/// `related` are the other places it's about (a reference's other end).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Problem {
    pub severity: Severity,
    pub file: String,
    pub message: String,
    pub path: Option<String>,
    pub line: Option<u32>,
    pub column: Option<u32>,
    pub code: Option<String>,
    pub related: Vec<Location>,
}

impl Problem {
    /// As format serializes it (`Problem`'s fields, the ones that are set).
    pub fn to_json(&self) -> Value {
        let mut out = serde_json::Map::new();
        out.insert("severity".into(), json!(self.severity.name()));
        out.insert("file".into(), json!(self.file));
        out.insert("message".into(), json!(self.message));
        if let Some(path) = &self.path {
            out.insert("path".into(), json!(path));
        }
        if let Some(line) = self.line {
            out.insert("line".into(), json!(line));
        }
        if let Some(column) = self.column {
            out.insert("column".into(), json!(column));
        }
        if let Some(code) = &self.code {
            out.insert("code".into(), json!(code));
        }
        if !self.related.is_empty() {
            let related: Vec<Value> = self
                .related
                .iter()
                .map(|l| match &l.path {
                    Some(p) => json!({ "file": l.file, "path": p }),
                    None => json!({ "file": l.file }),
                })
                .collect();
            out.insert("related".into(), Value::Array(related));
        }
        Value::Object(out)
    }
}

/// Collects problems for one file without repeating the file name at every call.
#[derive(Debug, Default)]
pub struct ProblemSink {
    pub file: String,
    pub problems: Vec<Problem>,
}

impl ProblemSink {
    pub fn new(file: &str) -> ProblemSink {
        ProblemSink {
            file: file.to_owned(),
            problems: Vec::new(),
        }
    }

    /// A problem of kind `code` (its severity is the code's), at `path` in this file.
    pub fn report(&mut self, code: ProblemCode, message: impl Into<String>, path: Option<String>) {
        self.problems.push(code.at(&self.file, message, path));
    }
}

/// Whether any of `problems` is an error.
pub fn has_errors(problems: &[Problem]) -> bool {
    problems.iter().any(|p| p.severity == Severity::Error)
}

/// Every problem code, as constants: `codes::PROJECT_NAMESPACE`.
pub mod codes {
    use super::{ProblemCode, Severity};
    include!(concat!(env!("OUT_DIR"), "/problem_codes.rs"));
}
