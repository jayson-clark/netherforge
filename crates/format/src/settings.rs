//! Settings a server's owner may change: reading a value as a setting takes
//! it, and checking the manifest's definitions. Format's `SettingDef` and
//! `SettingsValidator`.

use serde_json::Value;

use crate::json::{child_path, format_number};
use crate::manifest::SettingDef;
use crate::names;
use crate::problem::{ProblemSink, codes};

/// What reading a value as a setting gave: the value as it's kept, or why not.
#[derive(Clone, Debug, PartialEq)]
pub enum SettingRead {
    Ok(Value),
    Bad(String),
}

fn range(min: Option<String>, max: Option<String>) -> String {
    match (min, max) {
        (Some(min), Some(max)) => format!(" from {min} to {max}"),
        (Some(min), None) => format!(" of at least {min}"),
        (None, Some(max)) => format!(" of at most {max}"),
        (None, None) => String::new(),
    }
}

/// How kotlinx prints a JSON value in a message: its JSON text, a whole
/// double as kotlinx's `Double` prints it (`2.0`).
fn shown_json(value: &Value) -> String {
    value.to_string()
}

impl SettingDef {
    pub fn description(&self) -> &str {
        match self {
            SettingDef::Boolean { description, .. }
            | SettingDef::Integer { description, .. }
            | SettingDef::Number { description, .. }
            | SettingDef::String { description, .. }
            | SettingDef::Choice { description, .. } => description,
        }
    }

    /// Its default, as a JSON value.
    pub fn default_value(&self) -> Value {
        match self {
            SettingDef::Boolean { default, .. } => Value::Bool(*default),
            SettingDef::Integer { default, .. } => Value::from(*default),
            SettingDef::Number { default, .. } => Value::from(*default),
            SettingDef::String { default, .. } | SettingDef::Choice { default, .. } => {
                Value::from(default.clone())
            }
        }
    }

    /// What a value must be, in words: `a whole number from 1 to 10`.
    pub fn expects(&self) -> String {
        match self {
            SettingDef::Boolean { .. } => "true or false".into(),
            SettingDef::Integer { min, max, .. } => {
                format!(
                    "a whole number{}",
                    range(min.map(|m| m.to_string()), max.map(|m| m.to_string()))
                )
            }
            SettingDef::Number { min, max, .. } => {
                format!(
                    "a number{}",
                    range(min.map(format_number), max.map(format_number))
                )
            }
            SettingDef::String { .. } => "text".into(),
            SettingDef::Choice { choices, .. } => format!("one of {}", choices.join(", ")),
        }
    }

    /// Reads a value from a file (`settings.json`) as this setting takes it.
    pub fn read(&self, json: &Value) -> SettingRead {
        let bad = || SettingRead::Bad(format!("{} isn't {}", shown_json(json), self.expects()));
        match self {
            SettingDef::Boolean { .. } => match json {
                Value::Bool(b) => SettingRead::Ok(Value::Bool(*b)),
                _ => bad(),
            },
            SettingDef::Integer { min, max, .. } => {
                let Some(number) = json.as_f64().filter(|_| json.is_number()) else {
                    return bad();
                };
                let value = number as i64;
                if !number.is_finite() || value as f64 != number {
                    return SettingRead::Bad(format!(
                        "{} isn't {}",
                        shown_json(json),
                        self.expects()
                    ));
                }
                if min.is_some_and(|m| value < m) || max.is_some_and(|m| value > m) {
                    return SettingRead::Bad(format!("{value} isn't {}", self.expects()));
                }
                SettingRead::Ok(Value::from(value))
            }
            SettingDef::Number { min, max, .. } => {
                let Some(number) = json.as_f64().filter(|_| json.is_number()) else {
                    return bad();
                };
                if min.is_some_and(|m| number < m) || max.is_some_and(|m| number > m) {
                    return SettingRead::Bad(format!(
                        "{} isn't {}",
                        format_number(number),
                        self.expects()
                    ));
                }
                SettingRead::Ok(Value::from(number))
            }
            SettingDef::String { .. } => match json {
                Value::String(_) => SettingRead::Ok(json.clone()),
                _ => bad(),
            },
            SettingDef::Choice { choices, .. } => match json {
                Value::String(s) if choices.contains(s) => SettingRead::Ok(json.clone()),
                Value::String(s) => SettingRead::Bad(format!("\"{s}\" isn't {}", self.expects())),
                _ => bad(),
            },
        }
    }
}

impl SettingDef {
    /// Reads words typed (`/nf settings set`, a dialog's text box) as this setting takes them.
    pub fn parse(&self, text: &str) -> SettingRead {
        let trimmed = text.trim();
        let number = || trimmed.parse::<f64>().ok();
        match self {
            SettingDef::Boolean { .. } => match trimmed.to_lowercase().as_str() {
                "true" | "on" | "yes" => SettingRead::Ok(Value::Bool(true)),
                "false" | "off" | "no" => SettingRead::Ok(Value::Bool(false)),
                _ => SettingRead::Bad(format!("\"{trimmed}\" isn't {}", self.expects())),
            },
            SettingDef::Integer { min, max, .. } => {
                let Some(n) = number() else {
                    return SettingRead::Bad(format!("\"{trimmed}\" isn't {}", self.expects()));
                };
                let value = n as i64;
                if !n.is_finite() || value as f64 != n {
                    return SettingRead::Bad(format!("{trimmed} isn't {}", self.expects()));
                }
                if min.is_some_and(|m| value < m) || max.is_some_and(|m| value > m) {
                    return SettingRead::Bad(format!("{value} isn't {}", self.expects()));
                }
                SettingRead::Ok(Value::from(value))
            }
            SettingDef::Number { .. } => match number() {
                Some(n) if n.is_finite() => self.read(&Value::from(n)),
                Some(n) => SettingRead::Bad(format!("{n} isn't {}", self.expects())),
                None => SettingRead::Bad(format!("\"{trimmed}\" isn't {}", self.expects())),
            },
            SettingDef::String { .. } => SettingRead::Ok(Value::from(text)),
            SettingDef::Choice { choices, .. } => {
                if choices.iter().any(|c| c == text) {
                    SettingRead::Ok(Value::from(text))
                } else {
                    SettingRead::Bad(format!("\"{text}\" isn't {}", self.expects()))
                }
            }
        }
    }

    /// A value `read` accepted, as people read it: `true`, `12`, `0.5`, `easy`.
    pub fn show(&self, json: &Value) -> String {
        match json {
            Value::String(s) => s.clone(),
            Value::Number(n) => n.as_f64().map_or_else(|| n.to_string(), format_number),
            other => other.to_string(),
        }
    }
}

/// A key of a server's values file that doesn't fit: `unknown` when no setting has its name, else a value of the
/// wrong kind.
#[derive(Clone, Debug, PartialEq)]
pub struct ValueProblem {
    pub setting: String,
    pub message: String,
    pub unknown: bool,
}

/// What a server's values file for one package read as (`SettingValues.Read`): the owner's values that fit, everything
/// the file holds, and what didn't fit.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct ValuesRead {
    pub values: serde_json::Map<String, Value>,
    pub file: serde_json::Map<String, Value>,
    pub problems: Vec<ValueProblem>,
}

/// Reads a server's values file (`plugins/NetherForge/settings/<namespace>.json`) against the package's declared
/// settings; none when it isn't a JSON object (`SettingValues.read`). Everything it holds is kept, what doesn't fit
/// too, so writing it back drops nothing the owner wrote.
pub fn read_values<'a>(
    text: &str,
    declared: impl Fn(&str) -> Option<&'a SettingDef>,
) -> Option<ValuesRead> {
    let Ok(Value::Object(file)) = serde_json::from_str::<Value>(text) else {
        return None;
    };
    let mut read = ValuesRead::default();
    for (name, value) in &file {
        let Some(setting) = declared(name) else {
            read.problems.push(ValueProblem {
                setting: name.clone(),
                message: format!("there's no setting \"{name}\""),
                unknown: true,
            });
            continue;
        };
        match setting.read(value) {
            SettingRead::Ok(json) => {
                read.values.insert(name.clone(), json);
            }
            SettingRead::Bad(message) => read.problems.push(ValueProblem {
                setting: name.clone(),
                message,
                unknown: false,
            }),
        }
    }
    read.file = file;
    Some(read)
}

/// A values file holding `values`, canonical: keys sorted, numbers as everywhere (`SettingValues.write`).
pub fn write_values(values: &serde_json::Map<String, Value>) -> String {
    let mut keys: Vec<&String> = values.keys().collect();
    keys.sort();
    let sorted: serde_json::Map<String, Value> = keys
        .into_iter()
        .map(|k| (k.clone(), values[k].clone()))
        .collect();
    format!("{}\n", crate::json::print(&Value::Object(sorted)))
}

/// Checks the manifest's `settings` (`SettingsValidator.validate`).
pub fn validate<'a>(
    settings: impl Iterator<Item = (&'a str, &'a SettingDef)>,
    sink: &mut ProblemSink,
) {
    for (name, setting) in settings {
        let path = child_path("$.settings", name);
        if !names::is_id(name) {
            sink.report(
                codes::PROJECT_SETTING_NAME,
                format!("\"{name}\" isn't a setting name ({})", names::ID_RULE),
                Some(path.clone()),
            );
        }
        if setting.description().trim().is_empty() {
            sink.report(
                codes::PROJECT_SETTING_DESCRIPTION,
                format!("Setting \"{name}\" has no description: whoever runs the project reads it beside the value"),
                Some(format!("{path}.description")),
            );
        }
        let backwards = match setting {
            SettingDef::Integer {
                min: Some(min),
                max: Some(max),
                ..
            } => min > max,
            SettingDef::Number {
                min: Some(min),
                max: Some(max),
                ..
            } => min > max,
            _ => false,
        };
        if backwards {
            sink.report(
                codes::PROJECT_SETTING_RANGE,
                format!("Setting \"{name}\"'s min is more than its max"),
                Some(format!("{path}.min")),
            );
            continue;
        }
        if let SettingDef::Choice { choices, .. } = setting {
            if choices.is_empty() {
                sink.report(
                    codes::PROJECT_SETTING_CHOICES,
                    format!("Setting \"{name}\" has no choices"),
                    Some(format!("{path}.choices")),
                );
                continue;
            }
            let mut ok = true;
            let mut seen = std::collections::HashSet::new();
            for (index, choice) in choices.iter().enumerate() {
                let message = if choice.trim().is_empty() {
                    Some(format!(
                        "Setting \"{name}\"'s choice {} is empty",
                        index + 1
                    ))
                } else if !seen.insert(choice.as_str()) {
                    Some(format!("Setting \"{name}\" offers \"{choice}\" twice"))
                } else {
                    None
                };
                if let Some(message) = message {
                    ok = false;
                    sink.report(
                        codes::PROJECT_SETTING_CHOICES,
                        message,
                        Some(format!("{path}.choices[{index}]")),
                    );
                }
            }
            if !ok {
                continue;
            }
        }
        if let SettingRead::Bad(message) = setting.read(&setting.default_value()) {
            sink.report(
                codes::PROJECT_SETTING_DEFAULT,
                format!("Setting \"{name}\"'s default: {message}"),
                Some(format!("{path}.default")),
            );
        }
    }
}
