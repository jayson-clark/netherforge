//! The canonical printer: the one way project JSON is written. Format's
//! `CanonicalJson.print`, `formatNumber`, `quote` and `childPath`, byte for
//! byte, so a file the Kotlin side wrote reads and writes back unchanged.
//!
//! Fixed rules: two-space indent, an object's entries in the order they're
//! given (the models put maps in canonical order), arrays of primitives up to
//! [`INLINE_ARRAY`] characters on one line, and numbers as [`format_number`]
//! lays them out.

use serde_json::Value;

/// Arrays of numbers, strings or booleans up to this long print on one line:
/// vectors, short lore.
pub const INLINE_ARRAY: usize = 60;

/// Pretty-prints `value` with the fixed rules. A file is this and a newline.
pub fn print(value: &Value) -> String {
    let mut out = String::new();
    print_to(&mut out, value, 0);
    out
}

fn pad(out: &mut String, indent: usize) {
    for _ in 0..indent {
        out.push_str("  ");
    }
}

fn primitive(value: &Value) -> Option<String> {
    match value {
        Value::Null => Some("null".into()),
        Value::Bool(b) => Some(b.to_string()),
        Value::Number(n) => Some(n.as_f64().map_or_else(|| n.to_string(), format_number)),
        Value::String(s) => Some(quote(s)),
        _ => None,
    }
}

fn print_to(out: &mut String, value: &Value, indent: usize) {
    match value {
        Value::Array(items) => {
            if items.is_empty() {
                out.push_str("[]");
                return;
            }
            // `null` isn't a primitive here, as kotlinx's JsonNull is one only by type.
            let inline: Option<Vec<String>> = items
                .iter()
                .map(|it| {
                    if it.is_null() {
                        Some("null".into())
                    } else {
                        primitive(it)
                    }
                })
                .collect();
            if let Some(parts) = inline {
                let text = format!("[{}]", parts.join(", "));
                if text.chars().count() <= INLINE_ARRAY {
                    out.push_str(&text);
                    return;
                }
            }
            out.push_str("[\n");
            for (k, item) in items.iter().enumerate() {
                pad(out, indent + 1);
                print_to(out, item, indent + 1);
                if k + 1 < items.len() {
                    out.push(',');
                }
                out.push('\n');
            }
            pad(out, indent);
            out.push(']');
        }
        Value::Object(entries) => {
            if entries.is_empty() {
                out.push_str("{}");
                return;
            }
            out.push_str("{\n");
            for (k, (key, child)) in entries.iter().enumerate() {
                pad(out, indent + 1);
                out.push_str(&quote(key));
                out.push_str(": ");
                print_to(out, child, indent + 1);
                if k + 1 < entries.len() {
                    out.push(',');
                }
                out.push('\n');
            }
            pad(out, indent);
            out.push('}');
        }
        other => out.push_str(&primitive(other).expect("a primitive")),
    }
}

/// A number as the same text everywhere: the shortest digits that read back
/// as the same double, laid out as format lays them out (`1`, `0.5`,
/// `0.00001`, `1e-7`, `100000000000000000000`, `1e+21`).
pub fn format_number(value: f64) -> String {
    if value == 0.0 {
        return "0".into();
    }
    if !value.is_finite() {
        return value.to_string();
    }
    let negative = value < 0.0;
    // `{:e}` is the shortest round-trip digits: `1.23456e2`.
    let scientific = format!("{:e}", value.abs());
    let (mantissa, exponent) = scientific.split_once('e').expect("{:e} has an exponent");
    let exponent: i32 = exponent.parse().expect("an exponent");
    let digits: String = mantissa.chars().filter(|c| *c != '.').collect();
    let digits = digits.trim_end_matches('0');
    if digits.is_empty() {
        return "0".into();
    }
    // Where the decimal point is, relative to the start of `digits`.
    let point = exponent + 1;
    let len = digits.len() as i32;
    let body = if !(-5..=21).contains(&point) {
        let e = point - 1;
        let (head, tail) = digits.split_at(1);
        let sign = if e >= 0 { "+" } else { "" };
        if tail.is_empty() {
            format!("{head}e{sign}{e}")
        } else {
            format!("{head}.{tail}e{sign}{e}")
        }
    } else if point <= 0 {
        format!("0.{}{digits}", "0".repeat((-point) as usize))
    } else if point >= len {
        format!("{digits}{}", "0".repeat((point - len) as usize))
    } else {
        let (int, frac) = digits.split_at(point as usize);
        format!("{int}.{frac}")
    };
    if negative { format!("-{body}") } else { body }
}

/// A JSON string: quotes, backslashes and control characters escaped (the
/// rest as they are, `✓` included).
pub fn quote(text: &str) -> String {
    let mut out = String::with_capacity(text.len() + 2);
    out.push('"');
    for c in text.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            '\u{8}' => out.push_str("\\b"),
            '\u{c}' => out.push_str("\\f"),
            c if c < ' ' => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out.push('"');
    out
}

/// `parent` extended by `key`: `.key` when it's an identifier, `["key"]`
/// otherwise (`$.nodes.root`, `$.nodes["a b"]`).
pub fn child_path(parent: &str, key: &str) -> String {
    let mut chars = key.chars();
    let ident = chars
        .next()
        .is_some_and(|c| c.is_ascii_alphabetic() || c == '_')
        && chars.all(|c| c.is_ascii_alphanumeric() || c == '_');
    if ident {
        format!("{parent}.{key}")
    } else {
        format!("{parent}[{}]", quote(key))
    }
}
