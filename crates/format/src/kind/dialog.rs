//! `dialogs/<id>/dialog.json`: a dialog's body, inputs and buttons, and its
//! script. Format's `DialogFile`, `DialogKind` and `DialogValidator`: the keys'
//! form, input lengths and steps are their fields' rules; `validate` is what
//! depends on the dialog's type or on other fields.

use std::collections::HashSet;

use schemars::JsonSchema;
use serde::{Deserialize, Serialize};

use crate::document::{Context, Document};
use crate::item::{self, ItemDef};
use crate::problem::{ProblemSink, codes};
use crate::script::{self, ScriptDef};

pub const MIN_COLUMNS: i32 = 1;
pub const MAX_COLUMNS: i32 = 8;

fn empty<T>(list: &[T]) -> bool {
    list.is_empty()
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct DialogFile {
    #[serde(rename = "$schema", skip_serializing_if = "Option::is_none")]
    pub schema: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    #[serde(rename = "type", skip_serializing_if = "Option::is_none")]
    pub kind: Option<DialogType>,
    pub title: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub external_title: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub can_close_with_escape: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub after_action: Option<AfterAction>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub columns: Option<i32>,
    #[serde(default, skip_serializing_if = "empty")]
    pub body: Vec<DialogBody>,
    #[serde(default, skip_serializing_if = "empty")]
    pub inputs: Vec<DialogInput>,
    #[serde(default, skip_serializing_if = "empty")]
    pub buttons: Vec<DialogButton>,
    /// The dialogs a `dialog_list` lists.
    #[serde(default, skip_serializing_if = "empty")]
    pub dialogs: Vec<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub script: Option<ScriptDef>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub pause_menu: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub quick_actions: Option<bool>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum DialogType {
    Notice,
    Confirmation,
    MultiAction,
    DialogList,
}

impl DialogType {
    pub fn id(self) -> &'static str {
        match self {
            DialogType::Notice => "notice",
            DialogType::Confirmation => "confirmation",
            DialogType::MultiAction => "multi_action",
            DialogType::DialogList => "dialog_list",
        }
    }

    /// The most buttons it shows, when there's a limit.
    pub fn button_limit(self) -> Option<usize> {
        match self {
            DialogType::Notice | DialogType::DialogList => Some(1),
            DialogType::Confirmation => Some(2),
            DialogType::MultiAction => None,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum AfterAction {
    Close,
    None,
    WaitForResponse,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(tag = "type", rename_all = "snake_case", deny_unknown_fields)]
pub enum DialogBody {
    Message {
        text: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        width: Option<i32>,
        #[serde(skip_serializing_if = "Option::is_none")]
        #[schemars(regex(pattern = r"^[A-Za-z0-9_-]{1,64}$"), extend("x-problem" = "dialog.body-key", "x-rule" = "letters, digits, _ and -, at most 64 characters"))]
        key: Option<String>,
    },
    #[serde(rename_all = "camelCase")]
    Item {
        item: Box<ItemDef>,
        #[serde(skip_serializing_if = "Option::is_none")]
        description: Option<String>,
        #[serde(skip_serializing_if = "Option::is_none")]
        show_tooltip: Option<bool>,
        #[serde(skip_serializing_if = "Option::is_none")]
        width: Option<i32>,
        #[serde(skip_serializing_if = "Option::is_none")]
        height: Option<i32>,
        #[serde(skip_serializing_if = "Option::is_none")]
        #[schemars(regex(pattern = r"^[A-Za-z0-9_-]{1,64}$"), extend("x-problem" = "dialog.body-key", "x-rule" = "letters, digits, _ and -, at most 64 characters"))]
        key: Option<String>,
    },
}

impl DialogBody {
    fn key(&self) -> Option<&str> {
        match self {
            DialogBody::Message { key, .. } | DialogBody::Item { key, .. } => key.as_deref(),
        }
    }
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(tag = "type", rename_all = "snake_case", deny_unknown_fields)]
pub enum DialogInput {
    #[serde(rename_all = "camelCase")]
    Text {
        #[schemars(regex(pattern = r"^[A-Za-z0-9_-]{1,64}$"), extend("x-problem" = "dialog.input-key", "x-rule" = "letters, digits, _ and -, at most 64 characters"))]
        key: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        label: Option<String>,
        #[serde(skip_serializing_if = "Option::is_none")]
        width: Option<i32>,
        #[serde(skip_serializing_if = "Option::is_none")]
        initial: Option<String>,
        #[serde(skip_serializing_if = "Option::is_none")]
        #[schemars(range(min = 1), extend("x-problem" = "dialog.max-length"))]
        max_length: Option<i32>,
        #[serde(skip_serializing_if = "Option::is_none")]
        #[schemars(range(min = 1), extend("x-problem" = "dialog.lines"))]
        lines: Option<i32>,
        #[serde(skip_serializing_if = "Option::is_none")]
        label_visible: Option<bool>,
    },
    #[serde(rename_all = "camelCase")]
    Boolean {
        #[schemars(regex(pattern = r"^[A-Za-z0-9_-]{1,64}$"), extend("x-problem" = "dialog.input-key", "x-rule" = "letters, digits, _ and -, at most 64 characters"))]
        key: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        label: Option<String>,
        #[serde(skip_serializing_if = "Option::is_none")]
        initial: Option<bool>,
        #[serde(skip_serializing_if = "Option::is_none")]
        on_true: Option<String>,
        #[serde(skip_serializing_if = "Option::is_none")]
        on_false: Option<String>,
    },
    SingleOption {
        #[schemars(regex(pattern = r"^[A-Za-z0-9_-]{1,64}$"), extend("x-problem" = "dialog.input-key", "x-rule" = "letters, digits, _ and -, at most 64 characters"))]
        key: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        label: Option<String>,
        #[serde(skip_serializing_if = "Option::is_none")]
        width: Option<i32>,
        #[schemars(length(min = 1), extend("x-problem" = "dialog.options-empty"))]
        options: Vec<DialogOption>,
    },
    NumberRange {
        #[schemars(regex(pattern = r"^[A-Za-z0-9_-]{1,64}$"), extend("x-problem" = "dialog.input-key", "x-rule" = "letters, digits, _ and -, at most 64 characters"))]
        key: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        label: Option<String>,
        #[serde(skip_serializing_if = "Option::is_none")]
        width: Option<i32>,
        start: f64,
        end: f64,
        #[serde(skip_serializing_if = "Option::is_none")]
        #[schemars(extend("exclusiveMinimum" = 0, "x-problem" = "dialog.range-step"))]
        step: Option<f64>,
        #[serde(skip_serializing_if = "Option::is_none")]
        initial: Option<f64>,
    },
}

impl DialogInput {
    fn key(&self) -> &str {
        match self {
            DialogInput::Text { key, .. }
            | DialogInput::Boolean { key, .. }
            | DialogInput::SingleOption { key, .. }
            | DialogInput::NumberRange { key, .. } => key,
        }
    }
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct DialogOption {
    pub id: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub label: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub initial: Option<bool>,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct DialogButton {
    #[schemars(regex(pattern = r"^[A-Za-z0-9_-]{1,64}$"), extend("x-problem" = "dialog.button-key", "x-rule" = "letters, digits, _ and -, at most 64 characters"))]
    pub key: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub label: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tooltip: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub width: Option<i32>,
}

fn usable(key: &str) -> bool {
    crate::names::is_node_name(key)
}

/// The second of two usable keys that are the same, at their path.
fn duplicates<'a>(keys: impl Iterator<Item = (usize, Option<&'a str>)>) -> Vec<(usize, &'a str)> {
    let mut seen = HashSet::new();
    keys.filter_map(|(i, k)| k.filter(|k| usable(k) && !seen.insert(*k)).map(|k| (i, k)))
        .collect()
}

impl Document for DialogFile {
    const SCHEMA: &'static str = "../../.netherforge/schema/dialog.schema.json";
    const SCRIPTED: bool = true;

    fn set_schema(&mut self, schema: Option<String>) {
        self.schema = schema;
    }

    fn script(&self) -> Option<&ScriptDef> {
        self.script.as_ref()
    }

    fn validate(&self, cx: &Context, sink: &mut ProblemSink) {
        let kind = self.kind.unwrap_or(DialogType::Notice);
        if let Some(columns) = self.columns {
            if kind != DialogType::MultiAction {
                sink.report(
                    codes::DIALOG_COLUMNS_TYPE,
                    "Only a multi_action dialog lays its buttons out in columns",
                    Some("$.columns".into()),
                );
            } else if !(MIN_COLUMNS..=MAX_COLUMNS).contains(&columns) {
                sink.report(
                    codes::DIALOG_COLUMNS,
                    format!("A dialog has {MIN_COLUMNS} to {MAX_COLUMNS} columns"),
                    Some("$.columns".into()),
                );
            }
        }
        for (i, key) in duplicates(self.body.iter().enumerate().map(|(i, b)| (i, b.key()))) {
            sink.report(
                codes::DIALOG_BODY_DUPLICATE,
                format!("Two body elements are called \"{key}\""),
                Some(format!("$.body[{i}].key")),
            );
        }
        for (i, body) in self.body.iter().enumerate() {
            match body {
                DialogBody::Message { text, .. } if text.trim().is_empty() => {
                    sink.report(
                        codes::DIALOG_BODY_EMPTY,
                        "This message has no text",
                        Some(format!("$.body[{i}].text")),
                    );
                }
                DialogBody::Item { item: stack, .. } => {
                    item::check(stack, &format!("$.body[{i}].item"), sink, cx.game)
                }
                _ => {}
            }
        }
        for (i, key) in duplicates(
            self.inputs
                .iter()
                .enumerate()
                .map(|(i, input)| (i, Some(input.key()))),
        ) {
            sink.report(
                codes::DIALOG_INPUT_DUPLICATE,
                format!("Two inputs are called \"{key}\""),
                Some(format!("$.inputs[{i}].key")),
            );
        }
        for (i, input) in self.inputs.iter().enumerate() {
            let at = format!("$.inputs[{i}]");
            match input {
                DialogInput::SingleOption { options, .. } => {
                    for (k, id) in duplicates(
                        options
                            .iter()
                            .enumerate()
                            .map(|(k, o)| (k, Some(o.id.as_str()))),
                    ) {
                        sink.report(
                            codes::DIALOG_OPTION_DUPLICATE,
                            format!("Two options are called \"{id}\""),
                            Some(format!("{at}.options[{k}].id")),
                        );
                    }
                    if options.iter().filter(|o| o.initial == Some(true)).count() > 1 {
                        sink.report(
                            codes::DIALOG_OPTION_INITIAL,
                            "More than one option starts selected; the first wins",
                            Some(format!("{at}.options")),
                        );
                    }
                }
                DialogInput::NumberRange {
                    start,
                    end,
                    initial,
                    ..
                } => {
                    if end <= start {
                        sink.report(
                            codes::DIALOG_RANGE,
                            "end must be greater than start",
                            Some(format!("{at}.end")),
                        );
                    }
                    if initial.is_some_and(|v| v < *start || v > *end) {
                        sink.report(
                            codes::DIALOG_RANGE_INITIAL,
                            "initial must be between start and end",
                            Some(format!("{at}.initial")),
                        );
                    }
                }
                _ => {}
            }
        }
        for (i, key) in duplicates(
            self.buttons
                .iter()
                .enumerate()
                .map(|(i, b)| (i, Some(b.key.as_str()))),
        ) {
            sink.report(
                codes::DIALOG_BUTTON_DUPLICATE,
                format!("Two buttons are called \"{key}\""),
                Some(format!("$.buttons[{i}].key")),
            );
        }
        if let Some(limit) = kind.button_limit()
            && self.buttons.len() > limit
        {
            let what = if kind == DialogType::DialogList {
                "only its exit button".to_owned()
            } else {
                format!("{limit} button{}", if limit == 1 { "" } else { "s" })
            };
            sink.report(
                codes::DIALOG_BUTTON_COUNT,
                format!("A {} dialog shows {what}", kind.id()),
                Some("$.buttons".into()),
            );
        }
        if kind == DialogType::Confirmation && self.buttons.len() < 2 {
            sink.report(
                codes::DIALOG_CONFIRMATION,
                "A confirmation dialog has a yes and a no button",
                Some("$.buttons".into()),
            );
        }
        if kind == DialogType::DialogList {
            if self.dialogs.is_empty() {
                sink.report(
                    codes::DIALOG_LIST_EMPTY,
                    "This dialog_list lists no dialogs",
                    Some("$.dialogs".into()),
                );
            }
        } else if !self.dialogs.is_empty() {
            sink.report(
                codes::DIALOG_LIST_TYPE,
                "Only a dialog_list lists other dialogs",
                Some("$.dialogs".into()),
            );
        }
        if let Some(s) = &self.script {
            script::check(s, "$.script", cx, sink);
        }
    }
}
