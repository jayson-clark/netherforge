//! Strict reading, with format's messages: an unknown key is an error naming
//! where it was, not something silently dropped on the next save. Format's
//! `CanonicalJson.parse` and `describe`, on serde, saying what kotlinx says
//! (the golden tests hold both sides to the same problems): `Unknown key
//! "colour"` at the object that has it, with the key's line and column;
//! `Missing required key "block"`; `"sideways" isn't one of "fixed", ...` at
//! the value; `Trailing comma before the end of JSON object`.

use serde::de::DeserializeOwned;
use serde_path_to_error::{Path, Segment};

use crate::json::child_path;
use crate::problem::{Problem, codes};

/// The 1-based line and column of byte `offset` in `text`.
fn line_column(text: &str, offset: usize) -> (u32, u32) {
    let before = &text[..offset.min(text.len())];
    let line = before.matches('\n').count() as u32 + 1;
    let start = before.rfind('\n').map_or(0, |i| i + 1);
    (line, before[start..].chars().count() as u32 + 1)
}

/// The byte offset of 1-based `line` and `column` (serde's) in `text`.
fn offset_of(text: &str, line: usize, column: usize) -> usize {
    let start: usize = text
        .split_inclusive('\n')
        .take(line.saturating_sub(1))
        .map(str::len)
        .sum();
    let rest = &text[start..];
    start
        + rest
            .char_indices()
            .nth(column.saturating_sub(1))
            .map_or(rest.len(), |(i, _)| i)
}

/// The quoted names in serde's "expected one of `a`, `b`".
fn backticked(message: &str) -> Vec<&str> {
    message.split('`').skip(1).step_by(2).collect()
}

/// Reads `text` (the file `file`) as `T`, or the one parse problem it has.
pub fn parse<T: DeserializeOwned>(text: &str, file: &str) -> Result<T, Box<Problem>> {
    // Syntax first: a file that isn't JSON at all says so where it broke.
    if let Err(e) = serde_json::from_str::<serde::de::IgnoredAny>(text) {
        return Err(Box::new(syntax(e, text, file)));
    }
    let mut deserializer = serde_json::Deserializer::from_str(text);
    serde_path_to_error::deserialize::<_, T>(&mut deserializer).map_err(|e| {
        let path = e.path().clone();
        let inner = e.into_inner();
        Box::new(shape(inner, &path, text, file))
    })
}

fn syntax(e: serde_json::Error, text: &str, file: &str) -> Problem {
    let message = e.to_string();
    let at = offset_of(text, e.line(), e.column());
    let mut problem = codes::PARSE.at(file, String::new(), Some("$".into()));
    // serde says a comma before `}` is "key must be a string".
    let before = text[..at.min(text.len())]
        .trim_end_matches(|c: char| c.is_whitespace() || c == '}' || c == ']');
    let after_comma = before.ends_with(',');
    if message.starts_with("trailing comma")
        || (message.starts_with("key must be a string") && after_comma)
    {
        // kotlinx points just past the comma.
        let comma = text[..at.min(text.len())].rfind(',').unwrap_or(at);
        let (line, column) = line_column(text, comma + 1);
        let closing = text[at.min(text.len())..].chars().next();
        problem.message = format!(
            "Trailing comma before the end of JSON {}",
            if closing == Some(']') {
                "array"
            } else {
                "object"
            }
        );
        problem.line = Some(line);
        problem.column = Some(column);
        return problem;
    }
    let (line, column) = line_column(text, at.saturating_sub(1));
    let text = message.split(" at line ").next().unwrap_or(&message);
    let mut chars = text.chars();
    problem.message = chars
        .next()
        .map_or_else(String::new, |c| c.to_uppercase().chain(chars).collect());
    problem.line = Some(line);
    problem.column = Some(column);
    problem
}

fn shape(e: serde_json::Error, path: &Path, text: &str, file: &str) -> Problem {
    let message = e.to_string();
    let message = message
        .split(" at line ")
        .next()
        .unwrap_or(&message)
        .to_owned();
    let mut segments: Vec<Segment> = path.iter().cloned().collect();
    if let Some(rest) = message.strip_prefix("unknown field `") {
        let key = rest.split('`').next().unwrap_or("");
        // At the object that has it: the path without the key itself.
        if matches!(segments.last(), Some(Segment::Map { key: k }) if k == key) {
            segments.pop();
        }
        let mut problem = codes::PARSE.at(
            file,
            format!("Unknown key \"{key}\""),
            Some(path_of(&segments)),
        );
        // The key's first character, as kotlinx points at it (serde doesn't say where).
        if let Some(start) = locate(text, &segments, Some(key)) {
            let (line, column) = line_column(text, start + 1);
            problem.line = Some(line);
            problem.column = Some(column);
        }
        return problem;
    }
    if let Some(rest) = message.strip_prefix("missing field `") {
        let key = rest.split('`').next().unwrap_or("");
        return codes::PARSE.at(
            file,
            format!("Missing required key \"{key}\""),
            Some(path_of(&segments)),
        );
    }
    if let Some(rest) = message.strip_prefix("unknown variant `") {
        let value = rest.split('`').next().unwrap_or("");
        let allowed: Vec<String> = backticked(&message)
            .into_iter()
            .skip(1)
            .map(|it| format!("\"{it}\""))
            .collect();
        let text = if allowed.is_empty() {
            format!("\"{value}\" isn't allowed here")
        } else {
            format!("\"{value}\" isn't one of {}", allowed.join(", "))
        };
        return codes::PARSE.at(file, text, Some(path_of(&segments)));
    }
    let at = offset_of(text, e.line(), e.column());
    let (line, column) = line_column(text, at.saturating_sub(1));
    let mut chars = message.chars();
    let message: String = chars
        .next()
        .map_or_else(String::new, |c| c.to_uppercase().chain(chars).collect());
    let mut problem = codes::PARSE.at(file, message, Some(path_of(&segments)));
    problem.line = Some(line);
    problem.column = Some(column);
    problem
}

/// `$.nodes.root`, `$.keys[2]`, `$.nodes["a b"]`.
fn path_of(segments: &[Segment]) -> String {
    let mut out = String::from("$");
    for segment in segments {
        out = match segment {
            Segment::Seq { index } => format!("{out}[{index}]"),
            Segment::Map { key } => child_path(&out, key),
            Segment::Enum { variant } => child_path(&out, variant),
            Segment::Unknown => out,
        };
    }
    out
}

/// Where the value at `segments` starts in `text` (a byte offset), or, with
/// `key`, where that key of the object there starts (at its quote). A small
/// scanner over JSON that's already known to be well formed.
fn locate(text: &str, segments: &[Segment], key: Option<&str>) -> Option<usize> {
    let bytes = text.as_bytes();
    let mut at = skip_ws(bytes, 0);
    for segment in segments {
        at = match segment {
            Segment::Map { key } | Segment::Enum { variant: key } => {
                object_entry(bytes, at, key)?.1
            }
            Segment::Seq { index } => array_item(bytes, at, *index)?,
            Segment::Unknown => at,
        };
    }
    match key {
        Some(key) => object_entry(bytes, at, key).map(|(key_at, _)| key_at),
        None => Some(at),
    }
}

fn skip_ws(bytes: &[u8], mut at: usize) -> usize {
    while at < bytes.len() && bytes[at].is_ascii_whitespace() {
        at += 1;
    }
    at
}

/// The end of the string starting at `at` (its opening quote): just past its closing one.
fn string_end(bytes: &[u8], at: usize) -> usize {
    let mut i = at + 1;
    while i < bytes.len() && bytes[i] != b'"' {
        i += if bytes[i] == b'\\' { 2 } else { 1 };
    }
    i + 1
}

/// The end of the value starting at `at`.
fn value_end(bytes: &[u8], at: usize) -> usize {
    match bytes.get(at) {
        Some(b'"') => string_end(bytes, at),
        Some(b'{') | Some(b'[') => {
            let mut depth = 0usize;
            let mut i = at;
            while i < bytes.len() {
                match bytes[i] {
                    b'"' => {
                        i = string_end(bytes, i);
                        continue;
                    }
                    b'{' | b'[' => depth += 1,
                    b'}' | b']' => {
                        depth -= 1;
                        if depth == 0 {
                            return i + 1;
                        }
                    }
                    _ => {}
                }
                i += 1;
            }
            i
        }
        _ => {
            let mut i = at;
            while i < bytes.len()
                && !matches!(bytes[i], b',' | b'}' | b']')
                && !bytes[i].is_ascii_whitespace()
            {
                i += 1;
            }
            i
        }
    }
}

/// In the object at `at`: where entry `key` starts (its quote) and where its value starts.
fn object_entry(bytes: &[u8], at: usize, key: &str) -> Option<(usize, usize)> {
    if bytes.get(at) != Some(&b'{') {
        return None;
    }
    let mut i = skip_ws(bytes, at + 1);
    while bytes.get(i) == Some(&b'"') {
        let key_end = string_end(bytes, i);
        let name: String = serde_json::from_slice(&bytes[i..key_end]).ok()?;
        let colon = skip_ws(bytes, key_end);
        let value = skip_ws(bytes, colon + 1);
        if name == key {
            return Some((i, value));
        }
        i = skip_ws(bytes, value_end(bytes, value));
        if bytes.get(i) == Some(&b',') {
            i = skip_ws(bytes, i + 1);
        }
    }
    None
}

/// In the array at `at`: where item `index` starts.
fn array_item(bytes: &[u8], at: usize, index: usize) -> Option<usize> {
    if bytes.get(at) != Some(&b'[') {
        return None;
    }
    let mut i = skip_ws(bytes, at + 1);
    for _ in 0..index {
        i = skip_ws(bytes, value_end(bytes, i));
        if bytes.get(i) != Some(&b',') {
            return None;
        }
        i = skip_ws(bytes, i + 1);
    }
    Some(i)
}
