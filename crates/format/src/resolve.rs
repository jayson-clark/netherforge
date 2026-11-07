//! A model's fields as they run, from its schema: each field the file leaves
//! out takes its schema's `default`, and each number is held inside its
//! schema's range. So a part of a compiled form (a centity's body, a node's
//! physics) says each default and bound once, on the model's field, where the
//! editor reads them too, instead of a constant and a clamp per field (format's
//! `ResolvedBody.of`).
//!
//! A value outside an inclusive bound is only a warning ("clamped to between 0
//! and 1"), which is what holding it there does. An exclusive bound is an error,
//! so a resource that breaks one never compiles; a value is still held at it.

use schemars::JsonSchema;
use serde::Serialize;
use serde::de::DeserializeOwned;
use serde_json::{Number, Value};

/// `value` as `R`: `R` has `T`'s fields (by their names in the file), required
/// where `T`'s schema has a default for them.
pub fn resolved<T: Serialize + JsonSchema + 'static, R: DeserializeOwned>(value: &T) -> R {
    let schema = crate::schema::of::<T>();
    let mut json = serde_json::to_value(value).expect("a model writes as JSON");
    let properties = schema
        .resolve(&schema.root)
        .get("properties")
        .and_then(Value::as_object);
    if let (Some(object), Some(properties)) = (json.as_object_mut(), properties) {
        for (name, field) in properties {
            let field = schema.resolve(field);
            match object.get_mut(name) {
                Some(Value::Number(n)) => *n = held(n, field),
                Some(_) => {}
                None => {
                    if let Some(default) = field.get("default") {
                        object.insert(name.clone(), default.clone());
                    }
                }
            }
        }
    }
    serde_json::from_value(json).unwrap_or_else(|e| {
        panic!(
            "{} resolves to {}: {e}",
            std::any::type_name::<T>(),
            std::any::type_name::<R>()
        )
    })
}

/// `value` with every field it leaves out that has a default given it, and
/// every number held in its range: for a model whose compiled form reads it
/// field by field (each such field is then `Some`).
pub fn filled<T: Serialize + DeserializeOwned + JsonSchema + 'static>(value: &T) -> T {
    resolved(value)
}

/// `n` inside `field`'s range, a whole number staying one.
fn held(n: &Number, field: &Value) -> Number {
    let bound = |keys: [&str; 2]| {
        keys.iter()
            .find_map(|k| field.get(*k).and_then(Value::as_f64))
    };
    let x = n.as_f64().expect("a JSON number is a double");
    let mut y = x;
    if let Some(low) = bound(["minimum", "exclusiveMinimum"]) {
        y = y.max(low);
    }
    if let Some(high) = bound(["maximum", "exclusiveMaximum"]) {
        y = y.min(high);
    }
    if y == x {
        return n.clone();
    }
    match n.is_f64() {
        true => Number::from_f64(y).expect("a bound is finite"),
        false => Number::from(y as i64),
    }
}
