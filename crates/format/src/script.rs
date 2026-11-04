//! A resource's one script (`script` in a centity's, menu's, dialog's or
//! item's file): format's `ScriptDef` and `Rules.script`.

use schemars::JsonSchema;
use serde::{Deserialize, Serialize};

use crate::document::Context;
use crate::problem::{ProblemSink, codes};

/// The budget a script gets when its file doesn't say.
pub const DEFAULT_BUDGET: i64 = 200_000;
/// The least a budget can be; one below it is raised to it.
pub const MIN_BUDGET: i64 = 500;

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct ScriptDef {
    /// Its file, a `.lua` inside the resource's folder.
    #[schemars(
        regex(pattern = r"^([A-Za-z0-9_][A-Za-z0-9_.-]*/)*[A-Za-z0-9_][A-Za-z0-9_.-]*\.lua$"),
        extend("x-problem" = "script.path", "x-rule" = "a .lua file inside this resource's folder")
    )]
    pub file: String,
    /// Steps (calls and loop iterations) one call into it may take.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 500), extend("x-problem" = "script.budget"))]
    pub budget: Option<i64>,
}

impl ScriptDef {
    pub fn effective_budget(&self) -> i64 {
        self.budget.unwrap_or(DEFAULT_BUDGET).max(MIN_BUDGET)
    }
}

/// What a script's rules can't say: that its file is there.
pub fn check(script: &ScriptDef, at: &str, cx: &Context, sink: &mut ProblemSink) {
    let shaped = crate::names::is_relative_file(&script.file) && script.file.ends_with(".lua");
    if shaped && cx.files.is_some_and(|files| !files.contains(&script.file)) {
        sink.report(
            codes::SCRIPT_MISSING,
            format!("Script file \"{}\" doesn't exist", script.file),
            Some(format!("{at}.file")),
        );
    }
}
