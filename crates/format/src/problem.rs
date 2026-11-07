//! Problems: one thing wrong with a project, located as precisely as format
//! can say, and the catalogue of their kinds. Format's `Problem.kt`.

use serde::{Deserialize, Serialize};
use serde_json::Value;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "lowercase")]
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
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
pub struct Location {
    pub file: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
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
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
pub struct Problem {
    pub severity: Severity,
    pub file: String,
    pub message: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub path: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub line: Option<u32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub column: Option<u32>,
    /// Stable identifier for the kind of problem (the catalogue's), for tests and for filtering in the editor.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub code: Option<String>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub related: Vec<Location>,
}

impl Problem {
    /// As format serializes it: the fields that are set.
    pub fn to_json(&self) -> Value {
        serde_json::to_value(self).expect("a problem serializes")
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
