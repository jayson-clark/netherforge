//! Simple rules, declared once on a model's fields and checked from its JSON
//! Schema: a field's range, pattern, length or item count is a schemars
//! constraint, and the problem it is when broken is the field's `x-problem`
//! (a code from the catalogue). So the rule is in the schema the editor
//! reads, and checked by one walk instead of a hand-written check apiece.
//! A pattern says what it means in words with `x-rule` (`"#rrggbb"`).
//!
//! What a schema can't say (a slot inside its menu's size, a default among
//! its choices) stays a hand-written check, in the kind's `validate`.

use schemars::{JsonSchema, schema_for};
use serde_json::{Map, Value};

use crate::json::{child_path, format_number};
use crate::problem::{ProblemCode, ProblemSink};

/// Checks `value` (a document as its file has it) against `T`'s rules.
pub fn check<T: JsonSchema>(value: &Value, sink: &mut ProblemSink) {
    let schema = schema_for!(T);
    let root = schema.as_value();
    let defs = root
        .get("$defs")
        .and_then(Value::as_object)
        .cloned()
        .unwrap_or_default();
    walk(value, root, &defs, "$", sink);
}

fn resolve<'a>(schema: &'a Value, defs: &'a Map<String, Value>) -> &'a Value {
    match schema
        .get("$ref")
        .and_then(Value::as_str)
        .and_then(|r| r.strip_prefix("#/$defs/"))
    {
        Some(name) => defs.get(name).map_or(schema, |s| resolve(s, defs)),
        None => schema,
    }
}

fn number(schema: &Value, key: &str) -> Option<f64> {
    schema.get(key).and_then(Value::as_f64)
}

/// `maxTicks`, `slots[3].index`: a path as a message names it.
fn field(path: &str) -> &str {
    path.strip_prefix("$.").unwrap_or(path)
}

fn code_of(schema: &Value) -> Option<ProblemCode> {
    schema
        .get("x-problem")
        .and_then(Value::as_str)
        .and_then(ProblemCode::of)
}

fn walk(
    value: &Value,
    schema: &Value,
    defs: &Map<String, Value>,
    path: &str,
    sink: &mut ProblemSink,
) {
    let schema = resolve(schema, defs);
    // `Option<T>` and tagged enums: the branch the value is.
    for key in ["anyOf", "oneOf"] {
        if let Some(branches) = schema.get(key).and_then(Value::as_array)
            && let Some(branch) = branches
                .iter()
                .map(|b| resolve(b, defs))
                .find(|b| fits(value, b))
        {
            walk(value, branch, defs, path, sink);
        }
    }
    if let Some(all) = schema.get("allOf").and_then(Value::as_array) {
        for branch in all {
            walk(value, branch, defs, path, sink);
        }
    }
    if let Some(code) = code_of(schema)
        && let Some(message) = broken(value, schema)
    {
        sink.report(
            code,
            format!("{} {message}", field(path)),
            Some(path.to_owned()),
        );
    }
    match value {
        Value::Object(entries) => {
            let properties = schema.get("properties").and_then(Value::as_object);
            let names = schema.get("propertyNames").map(|s| resolve(s, defs));
            for (key, child) in entries {
                if let Some(names) = names
                    && let Some(code) = code_of(names)
                    && let Some(message) = broken(&Value::String(key.clone()), names)
                {
                    // At the key itself, as a map's entries are named.
                    sink.report(
                        code,
                        format!("\"{key}\" {message}"),
                        Some(child_path(path, key)),
                    );
                }
                let child_schema = properties
                    .and_then(|p| p.get(key))
                    .or_else(|| schema.get("additionalProperties").filter(|s| s.is_object()));
                if let Some(child_schema) = child_schema {
                    walk(child, child_schema, defs, &child_path(path, key), sink);
                }
            }
        }
        Value::Array(items) => {
            if let Some(item_schema) = schema.get("items") {
                for (k, item) in items.iter().enumerate() {
                    walk(item, item_schema, defs, &format!("{path}[{k}]"), sink);
                }
            }
        }
        _ => {}
    }
}

/// Whether `value` could be what `schema` describes: its type, and a tagged
/// enum's `type` constant.
fn fits(value: &Value, schema: &Value) -> bool {
    let kind = match value {
        Value::Null => "null",
        Value::Bool(_) => "boolean",
        Value::Number(n) if n.is_i64() || n.is_u64() => "integer",
        Value::Number(_) => "number",
        Value::String(_) => "string",
        Value::Array(_) => "array",
        Value::Object(_) => "object",
    };
    let typed = match schema.get("type") {
        Some(Value::String(t)) => t == kind || (t == "number" && kind == "integer"),
        Some(Value::Array(ts)) => ts
            .iter()
            .any(|t| t == kind || (t == "number" && kind == "integer")),
        _ => true,
    };
    let tagged = schema
        .get("properties")
        .and_then(|p| p.get("type"))
        .and_then(|t| t.get("const"))
        .is_none_or(|tag| value.get("type") == Some(tag));
    typed && tagged
}

/// What's wrong with `value` by `schema`'s constraints, as the rest of a
/// sentence starting with the field's name.
fn broken(value: &Value, schema: &Value) -> Option<String> {
    let rule = schema.get("x-rule").and_then(Value::as_str);
    match value {
        Value::Number(n) => {
            let n = n.as_f64()?;
            let min = number(schema, "minimum");
            let max = number(schema, "maximum");
            let out_of_range = min.is_some_and(|m| n < m) || max.is_some_and(|m| n > m);
            if out_of_range {
                return Some(match (min, max) {
                    (Some(min), Some(max)) => format!(
                        "must be from {} to {}",
                        format_number(min),
                        format_number(max)
                    ),
                    (Some(min), None) => format!("must be at least {}", format_number(min)),
                    (None, Some(max)) => format!("must be at most {}", format_number(max)),
                    (None, None) => unreachable!(),
                });
            }
            if let Some(over) = number(schema, "exclusiveMinimum")
                && n <= over
            {
                return Some(format!("must be more than {}", format_number(over)));
            }
            None
        }
        Value::String(s) => {
            let length = s.chars().count() as f64;
            if let Some(min) = number(schema, "minLength")
                && length < min
            {
                return Some(if min == 1.0 {
                    "can't be empty".into()
                } else {
                    format!("must be at least {} characters", format_number(min))
                });
            }
            if let Some(max) = number(schema, "maxLength")
                && length > max
            {
                return Some(format!("must be at most {} characters", format_number(max)));
            }
            if let Some(pattern) = schema.get("pattern").and_then(Value::as_str) {
                let re = regex::Regex::new(pattern).ok()?;
                if !re.is_match(s) {
                    return Some(match rule {
                        Some(rule) => format!("must be {rule}"),
                        None => format!("doesn't match {pattern}"),
                    });
                }
            }
            None
        }
        Value::Array(items) => {
            let count = items.len() as f64;
            if let Some(min) = number(schema, "minItems")
                && count < min
            {
                return Some(if min == 1.0 {
                    "can't be empty".into()
                } else {
                    format!("must have at least {}", format_number(min))
                });
            }
            if let Some(max) = number(schema, "maxItems")
                && count > max
            {
                return Some(format!("must have at most {}", format_number(max)));
            }
            None
        }
        _ => None,
    }
}

const CONSTRAINTS: &[&str] = &[
    "minimum",
    "maximum",
    "exclusiveMinimum",
    "exclusiveMaximum",
    "pattern",
    "minLength",
    "maxLength",
    "minItems",
    "maxItems",
];

/// The places in `T`'s schema with a constraint but no `x-problem` (or one
/// that isn't in the catalogue): a rule that would never be reported.
pub fn uncoded<T: JsonSchema>() -> Vec<String> {
    let schema = schema_for!(T);
    let mut out = Vec::new();
    fn visit(value: &Value, at: &str, out: &mut Vec<String>) {
        match value {
            Value::Object(map) => {
                if CONSTRAINTS.iter().any(|c| map.contains_key(*c)) && code_of(value).is_none() {
                    out.push(at.to_owned());
                }
                for (k, v) in map {
                    let child = format!("{at}/{k}");
                    // `properties` and `$defs` map names to schemas: a name isn't a constraint.
                    if k == "properties" || k == "$defs" {
                        for (name, schema) in v.as_object().into_iter().flatten() {
                            visit(schema, &format!("{child}/{name}"), out);
                        }
                    } else {
                        visit(v, &child, out);
                    }
                }
            }
            Value::Array(items) => {
                for (k, v) in items.iter().enumerate() {
                    visit(v, &format!("{at}/{k}"), out);
                }
            }
            _ => {}
        }
    }
    visit(schema.as_value(), "#", &mut out);
    out
}
