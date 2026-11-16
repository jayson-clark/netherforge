//! A model's JSON Schema, made once per type, and the one walk of a document
//! alongside it that everything read from the schema shares: the field rules
//! (`rules`), the references (`reference`), the stacks (`item`).
//!
//! The walk goes through a document as it's written, and at each value visits
//! every schema that describes it: the field's own (its `x-problem`, its
//! `x-ref`), an `Option`'s or a tagged enum's branch that the value is, an
//! `allOf`'s parts and a `$ref`'s definition. A visitor keeps what it learns
//! about a value in a state that the value's items and entries start from, so
//! a mark on a list or a map (`x-ref` on `Vec<String>`) holds for what's in it.

use std::any::TypeId;
use std::collections::HashMap;
use std::sync::{LazyLock, Mutex};

use schemars::{JsonSchema, schema_for};
use serde_json::{Map, Value};

use crate::json::child_path;

/// A model's schema: its root and its definitions.
pub struct Schema {
    pub root: Value,
    defs: Map<String, Value>,
}

/// `T`'s schema, made the first time it's asked for and kept.
pub fn of<T: JsonSchema + 'static>() -> &'static Schema {
    static SCHEMAS: LazyLock<Mutex<HashMap<TypeId, &'static Schema>>> =
        LazyLock::new(Default::default);
    let mut schemas = SCHEMAS.lock().expect("no panic holds the lock");
    schemas.entry(TypeId::of::<T>()).or_insert_with(|| {
        let root = schema_for!(T).to_value();
        let defs = root
            .get("$defs")
            .and_then(Value::as_object)
            .cloned()
            .unwrap_or_default();
        Box::leak(Box::new(Schema { root, defs }))
    })
}

/// What the walk shows a visitor.
pub enum Visit<'a> {
    /// One of the schemas that describe the value at `path`, and the value.
    Schema(&'a Value, &'a Value),
    /// A key of the object at `path`, with the schema its keys have (`propertyNames`).
    Key(&'a str, &'a Value),
    /// The value at `path`, once each of its schemas has been visited.
    Value(&'a Value),
}

impl Schema {
    /// The definition `schema` names, if it's a `$ref`, however many deep.
    pub fn resolve<'a>(&'a self, schema: &'a Value) -> &'a Value {
        match schema
            .get("$ref")
            .and_then(Value::as_str)
            .and_then(|r| r.strip_prefix("#/$defs/"))
        {
            Some(name) => self.defs.get(name).map_or(schema, |s| self.resolve(s)),
            None => schema,
        }
    }

    /// Walks `value`, a document of this schema, from `state`.
    pub fn walk<S: Clone>(
        &self,
        value: &Value,
        state: S,
        visit: &mut impl FnMut(Visit, &str, &mut S),
    ) {
        self.walk_at(value, &[&self.root], "$", state, visit);
    }

    fn walk_at<S: Clone>(
        &self,
        value: &Value,
        schemas: &[&Value],
        path: &str,
        mut state: S,
        visit: &mut impl FnMut(Visit, &str, &mut S),
    ) {
        let mut levels = Vec::new();
        for schema in schemas {
            self.levels(value, schema, &mut levels);
        }
        for level in &levels {
            visit(Visit::Schema(level, value), path, &mut state);
        }
        visit(Visit::Value(value), path, &mut state);
        match value {
            Value::Object(entries) => {
                let names: Vec<&Value> = levels
                    .iter()
                    .filter_map(|l| l.get("propertyNames"))
                    .map(|s| self.resolve(s))
                    .collect();
                for (key, child) in entries {
                    for names in &names {
                        visit(Visit::Key(key, names), path, &mut state);
                    }
                    let children: Vec<&Value> = levels
                        .iter()
                        .filter_map(|l| {
                            l.get("properties")
                                .and_then(|p| p.get(key))
                                .or_else(|| l.get("additionalProperties").filter(|s| s.is_object()))
                        })
                        .collect();
                    self.walk_at(
                        child,
                        &children,
                        &child_path(path, key),
                        state.clone(),
                        visit,
                    );
                }
            }
            Value::Array(items) => {
                let children: Vec<&Value> = levels.iter().filter_map(|l| l.get("items")).collect();
                for (k, item) in items.iter().enumerate() {
                    self.walk_at(
                        item,
                        &children,
                        &format!("{path}[{k}]"),
                        state.clone(),
                        visit,
                    );
                }
            }
            _ => {}
        }
    }

    /// Every schema `schema` amounts to for `value`: the branches it is, then `schema` itself.
    fn levels<'a>(&'a self, value: &Value, schema: &'a Value, out: &mut Vec<&'a Value>) {
        let schema = self.resolve(schema);
        // `Option<T>` and tagged enums: the branch the value is.
        for key in ["anyOf", "oneOf"] {
            if let Some(branch) = schema
                .get(key)
                .and_then(Value::as_array)
                .and_then(|branches| {
                    branches
                        .iter()
                        .map(|b| self.resolve(b))
                        .find(|b| fits(value, b))
                })
            {
                self.levels(value, branch, out);
            }
        }
        if let Some(all) = schema.get("allOf").and_then(Value::as_array) {
            for branch in all {
                self.levels(value, branch, out);
            }
        }
        out.push(schema);
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

/// Every place in `schema` (any JSON Schema value) that has `key`, by its
/// pointer from the root: for a test holding a model's marks to what they may say.
pub fn places_with<'a>(schema: &'a Value, key: &str) -> Vec<(String, &'a Value)> {
    fn visit<'a>(value: &'a Value, key: &str, at: &str, out: &mut Vec<(String, &'a Value)>) {
        match value {
            Value::Object(map) => {
                if let Some(found) = map.get(key) {
                    out.push((at.to_owned(), found));
                }
                for (k, v) in map {
                    visit(v, key, &format!("{at}/{k}"), out);
                }
            }
            Value::Array(items) => {
                for (k, v) in items.iter().enumerate() {
                    visit(v, key, &format!("{at}/{k}"), out);
                }
            }
            _ => {}
        }
    }
    let mut out = Vec::new();
    visit(schema, key, "#", &mut out);
    out
}
