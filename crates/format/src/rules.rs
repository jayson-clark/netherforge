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
use serde_json::Value;

use crate::json::{child_path, format_number};
use crate::problem::{ProblemCode, ProblemSink};
use crate::schema::Visit;

/// A string with a rule of its own, wherever a model has one: a newtype whose
/// schema is the pattern, the problem it is when broken, and the rule in
/// words, so every field (and map key) of it shares the one definition.
/// Ordered as a map key is (`canonical::key_order`). More of its schema
/// follows as `"key" = value` (a path's `"x-ref" = "texture"`).
#[macro_export]
macro_rules! ruled_string {
    ($(#[$m:meta])* $name:ident, $pattern:expr, $problem:literal, $rule:expr $(, $key:literal = $value:expr)* $(,)?) => {
        $(#[$m])*
        #[derive(Clone, Debug, PartialEq, Eq, Hash, serde::Serialize, serde::Deserialize)]
        #[serde(transparent)]
        pub struct $name(pub String);

        impl Ord for $name {
            fn cmp(&self, other: &Self) -> std::cmp::Ordering {
                $crate::canonical::key_order(&self.0, &other.0)
            }
        }

        impl PartialOrd for $name {
            fn partial_cmp(&self, other: &Self) -> Option<std::cmp::Ordering> {
                Some(self.cmp(other))
            }
        }

        impl std::ops::Deref for $name {
            type Target = str;
            fn deref(&self) -> &str {
                &self.0
            }
        }

        impl std::fmt::Display for $name {
            fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
                f.write_str(&self.0)
            }
        }

        impl schemars::JsonSchema for $name {
            fn schema_name() -> std::borrow::Cow<'static, str> {
                stringify!($name).into()
            }
            fn json_schema(_: &mut schemars::SchemaGenerator) -> schemars::Schema {
                #[allow(unused_mut)]
                let mut schema = schemars::json_schema!({
                    "type": "string",
                    "pattern": $pattern,
                    "x-problem": $problem,
                    "x-rule": $rule,
                });
                $(schema.insert($key.into(), serde_json::json!($value));)*
                schema
            }
        }
    };
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

/// Checks `value` (a document as its file has it) against `T`'s rules.
pub fn check<T: JsonSchema + 'static>(value: &Value, sink: &mut ProblemSink) {
    crate::schema::of::<T>().walk(value, (), &mut |visit, path, _| match visit {
        Visit::Schema(schema, value) => {
            if let Some(code) = code_of(schema)
                && let Some(message) = broken(value, schema)
            {
                sink.report(
                    code,
                    format!("{} {message}", field(path)),
                    Some(path.to_owned()),
                );
            }
        }
        // At the key itself, as a map's entries are named.
        Visit::Key(key, names) => {
            if let Some(code) = code_of(names)
                && let Some(message) = broken(&Value::String(key.to_owned()), names)
            {
                sink.report(
                    code,
                    format!("\"{key}\" {message}"),
                    Some(child_path(path, key)),
                );
            }
        }
        Visit::Value(_) => {}
    });
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
            if let Some(step) = number(schema, "multipleOf")
                && (n / step).fract() != 0.0
            {
                return Some(format!("must be a multiple of {}", format_number(step)));
            }
            if let Some(over) = number(schema, "exclusiveMinimum")
                && n <= over
            {
                return Some(format!("must be more than {}", format_number(over)));
            }
            if let Some(under) = number(schema, "exclusiveMaximum")
                && n >= under
            {
                return Some(format!("must be less than {}", format_number(under)));
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
            if let Some(pattern) = schema.get("pattern").and_then(Value::as_str)
                && !matches(pattern, s)?
            {
                return Some(match rule {
                    Some(rule) => format!("must be {rule}"),
                    None => format!("doesn't match {pattern}"),
                });
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
        Value::Object(entries) => {
            let count = entries.len() as f64;
            if let Some(max) = number(schema, "maxProperties")
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
    "multipleOf",
    "minimum",
    "maximum",
    "exclusiveMinimum",
    "exclusiveMaximum",
    "pattern",
    "minLength",
    "maxLength",
    "minItems",
    "maxItems",
    "maxProperties",
];

/// The places in `T`'s schema with a constraint but no `x-problem` (or one
/// that isn't in the catalogue): a rule that would never be reported.
pub fn uncoded<T: JsonSchema>() -> Vec<String> {
    let schema = schema_for!(T);
    let mut out = Vec::new();
    fn visit(value: &Value, at: &str, out: &mut Vec<String>) {
        match value {
            Value::Object(map) => {
                // A tuple's length (`[x, y, z]`) is its shape, which reading it holds it to.
                let tuple = map.contains_key("prefixItems");
                if !tuple
                    && CONSTRAINTS.iter().any(|c| map.contains_key(*c))
                    && code_of(value).is_none()
                {
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

/// Whether `text` matches `pattern`, each pattern compiled once.
fn matches(pattern: &str, text: &str) -> Option<bool> {
    use std::cell::RefCell;
    use std::collections::HashMap;
    thread_local! {
        static COMPILED: RefCell<HashMap<String, regex::Regex>> = RefCell::new(HashMap::new());
    }
    COMPILED.with(|compiled| {
        let mut compiled = compiled.borrow_mut();
        if !compiled.contains_key(pattern) {
            compiled.insert(pattern.to_owned(), regex::Regex::new(pattern).ok()?);
        }
        Some(compiled[pattern].is_match(text))
    })
}

/// A colour, `#rrggbb`: the one definition every field that takes one names.
pub const RGB: &str = r"^#[0-9a-fA-F]{6}$";
/// A colour in lowercase, `#rrggbb`, as particle effects write theirs.
pub const RGB_LOWERCASE: &str = r"^#[0-9a-f]{6}$";
/// A colour with alpha allowed, `#rrggbb` or `#aarrggbb` (clouds).
pub const ARGB: &str = r"^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$";

/// Whether `text` matches `pattern` (a valid one), compiled once: for a
/// hand-written check that shares a field's pattern.
pub fn matches_pattern(pattern: &str, text: &str) -> bool {
    matches(pattern, text).expect("a valid pattern")
}
