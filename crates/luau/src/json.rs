//! The one Luau↔JSON codec: what saved tables and item data are kept as, what
//! `nf.json` reads and writes, and what the runtime reads script tables as.
//! The prelude's `json.lua`, in Rust, with the numbers of the rust-runtime
//! skill ("Numbers").
//!
//! Plain values are JSON as they are: strings, booleans, numbers, tables keyed
//! by strings (objects, their keys written in byte order so the same table is
//! always the same text) and tables numbered from 1 (lists; a hole is `null`).
//! The API's own values are objects with one tagged key (`{"$vec3":[x,y,z]}`),
//! and a script's own key that would read as a tag gets one more `$`. What
//! can't be written is a problem at its key path (`data.inventory[3]: a
//! function can't be saved`), and skipped.

use mlua::{Lua, Table, Value};

use crate::host::Host;

/// The largest whole number a double holds exactly, and so the largest that's
/// written as an integer.
pub const MAX_SAFE_INTEGER: f64 = 9_007_199_254_740_991.0;

/// Tables nested deeper than this aren't written.
const MAX_DEPTH: usize = 64;

/// Entries the codec walks for free before charging the calling script.
const FREE_ENTRIES: u64 = 4096;

/// The API's own values beyond `vector`: handles and locations, which the
/// runtime knows how to name.
pub trait Tags {
    /// Every tag a value may be written with, without its `$` (`"entity"`).
    fn tags(&self) -> &[&str];

    /// The tag and parts `value` is written as, `Err` with what it is when it's
    /// an API value that can't be (`"a Menu handle"`), or `None` when it isn't
    /// one of the API's.
    fn encode(&self, value: &Value) -> Option<Result<(String, Vec<serde_json::Value>), String>>;

    /// The value a tag and its parts stand for, or `None` when they aren't one
    /// (then the object is read as a plain table).
    fn revive(&self, lua: &Lua, tag: &str, parts: &[serde_json::Value]) -> Option<Value>;
}

/// No tags but the codec's own (`$vec3`).
pub struct NoTags;

impl Tags for NoTags {
    fn tags(&self) -> &[&str] {
        &[]
    }
    fn encode(&self, _: &Value) -> Option<Result<(String, Vec<serde_json::Value>), String>> {
        None
    }
    fn revive(&self, _: &Lua, _: &str, _: &[serde_json::Value]) -> Option<Value> {
        None
    }
}

/// What writing a value said: its text (`None` when the value itself can't
/// be written) and every problem found below it.
#[derive(Debug, Default, PartialEq)]
pub struct Encoded {
    pub text: Option<String>,
    pub problems: Vec<String>,
}

/// Why the codec stopped before the end: the calling script ran past its
/// budget while the codec walked its table.
#[derive(Debug, PartialEq)]
pub struct Stopped(pub crate::Stop);

/// A number as JSON: a whole number in the safe range as an integer, any
/// other as the shortest text that reads back as the same double (an
/// exponent from 1e21 and below 1e-6, as JavaScript writes them), and `None`
/// for NaN and the infinities.
pub fn number(n: f64) -> Option<String> {
    if !n.is_finite() {
        return None;
    }
    if n == n.trunc() && n.abs() <= MAX_SAFE_INTEGER {
        return Some(format!("{}", n as i64));
    }
    let magnitude = n.abs();
    Some(if !(1e-6..1e21).contains(&magnitude) {
        format!("{n:e}")
    } else {
        format!("{n}")
    })
}

/// Writes `value` as JSON. `root` names it in problems (`data`), and `verb`
/// says what couldn't be done (`saved`, `encoded`).
pub fn encode(
    host: &Host,
    value: &Value,
    root: &str,
    verb: &str,
    tags: &dyn Tags,
) -> Result<Encoded, Stopped> {
    let mut encoder = Encoder {
        host,
        tags,
        verb,
        out: String::new(),
        problems: Vec::new(),
        seen: Vec::new(),
        walked: 0,
    };
    let ok = encoder.value(value, root, 0)?;
    Ok(Encoded {
        text: ok.then_some(encoder.out),
        problems: encoder.problems,
    })
}

struct Encoder<'a> {
    host: &'a Host,
    tags: &'a dyn Tags,
    verb: &'a str,
    out: String,
    problems: Vec<String>,
    seen: Vec<Table>,
    walked: u64,
}

impl Encoder<'_> {
    fn problem(&mut self, path: &str, what: impl AsRef<str>) {
        self.problems
            .push(format!("{path}: {} can't be {}", what.as_ref(), self.verb));
    }

    /// One more entry walked: past the free allowance, the calling script is
    /// charged for it.
    fn walk(&mut self) -> Result<(), Stopped> {
        self.walked += 1;
        if self.walked > FREE_ENTRIES
            && self.walked.is_multiple_of(256)
            && let Some(stop) = self.host.charge(256)
        {
            return Err(Stopped(stop));
        }
        Ok(())
    }

    /// Appends `value`; `false` (and a problem) when it can't be written.
    fn value(&mut self, value: &Value, path: &str, depth: usize) -> Result<bool, Stopped> {
        match value {
            Value::String(s) => {
                string(&mut self.out, &s.as_bytes());
                Ok(true)
            }
            Value::Boolean(b) => {
                self.out.push_str(if *b { "true" } else { "false" });
                Ok(true)
            }
            Value::Integer(i) => self.number(*i as f64, path),
            Value::Number(n) => self.number(*n, path),
            Value::Vector(v) => {
                let parts = [v.x() as f64, v.y() as f64, v.z() as f64];
                let mut texts = Vec::with_capacity(3);
                for n in parts {
                    match number(n) {
                        Some(text) => texts.push(text),
                        None => {
                            self.problem(path, format!("a vector with {} in it", lua_number(n)));
                            return Ok(false);
                        }
                    }
                }
                self.out.push_str("{\"$vec3\":[");
                self.out.push_str(&texts.join(","));
                self.out.push_str("]}");
                Ok(true)
            }
            Value::Table(t) => self.table(t, path, depth),
            other => {
                if let Some(tagged) = self.tags.encode(other) {
                    return self.tagged(tagged, path);
                }
                let what = match other {
                    Value::Nil => "nil".to_owned(),
                    Value::Thread(_) => "a coroutine".to_owned(),
                    Value::Function(_) => "a function".to_owned(),
                    other => a_or_an(other.type_name()),
                };
                self.problem(path, what);
                Ok(false)
            }
        }
    }

    fn number(&mut self, n: f64, path: &str) -> Result<bool, Stopped> {
        match number(n) {
            Some(text) => {
                self.out.push_str(&text);
                Ok(true)
            }
            None => {
                self.problem(path, lua_number(n));
                Ok(false)
            }
        }
    }

    fn tagged(
        &mut self,
        tagged: Result<(String, Vec<serde_json::Value>), String>,
        path: &str,
    ) -> Result<bool, Stopped> {
        match tagged {
            Ok((tag, parts)) => {
                self.out.push('{');
                string(&mut self.out, format!("${tag}").as_bytes());
                self.out.push(':');
                self.out
                    .push_str(&serde_json::Value::Array(parts).to_string());
                self.out.push('}');
                Ok(true)
            }
            Err(what) => {
                self.problem(path, what);
                Ok(false)
            }
        }
    }

    fn table(&mut self, t: &Table, path: &str, depth: usize) -> Result<bool, Stopped> {
        if let Some(tagged) = self.tags.encode(&Value::Table(t.clone())) {
            return self.tagged(tagged, path);
        }
        if self.seen.iter().any(|s| s == t) {
            self.problem(path, "a table inside itself");
            return Ok(false);
        }
        if depth >= MAX_DEPTH {
            self.problem(path, "tables nested this deep");
            return Ok(false);
        }

        // A list when every key is a position from 1 (a few holes allowed,
        // which come back as nil); otherwise an object, which takes string
        // keys only.
        let mut positions = 0u64;
        let mut max = 0u64;
        let mut strings: Vec<mlua::LuaString> = Vec::new();
        let mut others: Vec<Value> = Vec::new();
        let mut numbered: Vec<Value> = Vec::new();
        for pair in t.pairs::<Value, Value>() {
            self.walk()?;
            let Ok((key, _)) = pair else { continue };
            match key {
                Value::Integer(i) if i > 0 => {
                    positions += 1;
                    max = max.max(i as u64);
                    numbered.push(key);
                }
                Value::Number(n) if n > 0.0 && n == n.trunc() && n <= MAX_SAFE_INTEGER => {
                    positions += 1;
                    max = max.max(n as u64);
                    numbered.push(key);
                }
                Value::String(s) => strings.push(s),
                other => others.push(other),
            }
        }

        self.seen.push(t.clone());
        if positions > 0 && strings.is_empty() && others.is_empty() && max <= 2 * positions {
            self.out.push('[');
            for i in 1..=max {
                self.walk()?;
                if i > 1 {
                    self.out.push(',');
                }
                let item: Value = t.raw_get(i).unwrap_or(Value::Nil);
                let mark = self.out.len();
                if item.is_nil() || !self.value(&item, &format!("{path}[{i}]"), depth + 1)? {
                    self.out.truncate(mark);
                    self.out.push_str("null");
                }
            }
            self.out.push(']');
        } else {
            for key in numbered.iter().chain(&others) {
                let why = match key {
                    Value::Integer(_) => {
                        "a number key is saved only in a list numbered from 1".to_owned()
                    }
                    Value::Number(n) if *n == n.trunc() => {
                        "a number key is saved only in a list numbered from 1".to_owned()
                    }
                    other => format!("{} key can't be {}", a_or_an(other.type_name()), self.verb),
                };
                self.problems
                    .push(format!("{}: {why}", key_path(path, key)));
            }
            if strings.len() as u64 > 64 {
                let n = strings.len() as f64;
                let compares = (n * n.log2()) as u64;
                if let Some(stop) = self.host.charge(compares / 8) {
                    return Err(Stopped(stop));
                }
            }
            // Keys are unique, so an unstable sort is the same order. (A key
            // function can't hand out bytes borrowed from the string.)
            #[allow(clippy::unnecessary_sort_by)]
            strings.sort_unstable_by(|a, b| a.as_bytes().cmp(&b.as_bytes()));
            self.out.push('{');
            let mut first = true;
            for key in strings {
                let mark = self.out.len();
                if !first {
                    self.out.push(',');
                }
                let bytes = key.as_bytes();
                if tag_like(&bytes, self.tags) {
                    let mut escaped = Vec::with_capacity(bytes.len() + 1);
                    escaped.push(b'$');
                    escaped.extend_from_slice(&bytes);
                    string(&mut self.out, &escaped);
                } else {
                    string(&mut self.out, &bytes);
                }
                self.out.push(':');
                let item: Value = t.raw_get(key.clone()).unwrap_or(Value::Nil);
                let item_path = key_path(path, &Value::String(key));
                if self.value(&item, &item_path, depth + 1)? {
                    first = false;
                } else {
                    self.out.truncate(mark);
                }
            }
            self.out.push('}');
        }
        self.seen.pop();
        Ok(true)
    }
}

/// Reads JSON into a script's values, with tagged values made typed again. An
/// integer literal outside the safe range reads as a string of its digits, so
/// an id from another service survives; one past 64 bits reads as the nearest
/// double, as serde_json reads it.
pub fn decode(lua: &Lua, text: &str, tags: &dyn Tags) -> Result<Value, String> {
    let parsed: serde_json::Value = serde_json::from_str(text).map_err(|e| e.to_string())?;
    to_lua(lua, &parsed, tags).map_err(|e| e.to_string())
}

/// A parsed JSON value as a script's.
pub fn to_lua(lua: &Lua, json: &serde_json::Value, tags: &dyn Tags) -> mlua::Result<Value> {
    use serde_json::Value as J;
    Ok(match json {
        J::Null => Value::Nil,
        J::Bool(b) => Value::Boolean(*b),
        J::Number(n) => {
            if let Some(i) = n.as_i64() {
                if (i as f64).abs() <= MAX_SAFE_INTEGER {
                    Value::Number(i as f64)
                } else {
                    Value::String(lua.create_string(i.to_string())?)
                }
            } else if let Some(u) = n.as_u64() {
                Value::String(lua.create_string(u.to_string())?)
            } else {
                Value::Number(n.as_f64().unwrap_or(f64::NAN))
            }
        }
        J::String(s) => Value::String(lua.create_string(s)?),
        J::Array(items) => {
            let t = lua.create_table_with_capacity(items.len(), 0)?;
            for (i, item) in items.iter().enumerate() {
                let v = to_lua(lua, item, tags)?;
                if !v.is_nil() {
                    t.raw_set(i + 1, v)?;
                }
            }
            Value::Table(t)
        }
        J::Object(fields) => {
            if fields.len() == 1 {
                let (key, parts) = fields.iter().next().expect("one field");
                if let Some(typed) = revive(lua, key, parts, tags) {
                    return Ok(typed);
                }
            }
            let t = lua.create_table_with_capacity(0, fields.len())?;
            for (key, item) in fields {
                let key = match key.strip_prefix('$') {
                    Some(rest) if rest.starts_with('$') && tag_like(key.as_bytes(), tags) => rest,
                    _ => key.as_str(),
                };
                let v = to_lua(lua, item, tags)?;
                if !v.is_nil() {
                    t.raw_set(key, v)?;
                }
            }
            Value::Table(t)
        }
    })
}

/// The value one tagged object stands for, or `None` for a tag that isn't one
/// of ours (or a `$$` key, which is a script's own).
fn revive(lua: &Lua, key: &str, parts: &serde_json::Value, tags: &dyn Tags) -> Option<Value> {
    let tag = key.strip_prefix('$')?;
    if tag.starts_with('$') {
        return None;
    }
    let parts = parts.as_array()?;
    if tag == "vec3" {
        let [x, y, z] = parts.as_slice() else {
            return None;
        };
        let (x, y, z) = (x.as_f64()?, y.as_f64()?, z.as_f64()?);
        return Some(Value::Vector(mlua::Vector::new(
            x as f32, y as f32, z as f32,
        )));
    }
    tags.revive(lua, tag, parts)
}

/// Whether a key reads as a tag, with any number of `$` in front (`$vec3`,
/// `$$vec3`).
fn tag_like(key: &[u8], tags: &dyn Tags) -> bool {
    let word = match key.iter().position(|&b| b != b'$') {
        Some(0) | None => return false,
        Some(at) => &key[at..],
    };
    let starts = word
        .first()
        .is_some_and(|b| b.is_ascii_alphabetic() || *b == b'_');
    let rest = word.iter().all(|b| b.is_ascii_alphanumeric() || *b == b'_');
    starts
        && rest
        && (word == b"vec3"
            || word == b"location"
            || tags.tags().iter().any(|t| t.as_bytes() == word))
}

/// `data.items[3]`, `data.owner`, `data["two words"]`.
fn key_path(path: &str, key: &Value) -> String {
    match key {
        Value::Integer(i) => format!("{path}[{i}]"),
        Value::Number(n) if *n == n.trunc() && n.abs() <= MAX_SAFE_INTEGER => {
            format!("{path}[{}]", *n as i64)
        }
        Value::Number(n) => format!("{path}[{}]", lua_number(*n)),
        Value::String(s) => {
            let bytes = s.as_bytes();
            let name = bytes
                .first()
                .is_some_and(|b| b.is_ascii_alphabetic() || *b == b'_')
                && bytes
                    .iter()
                    .all(|b| b.is_ascii_alphanumeric() || *b == b'_');
            let text = String::from_utf8_lossy(&bytes);
            if name {
                format!("{path}.{text}")
            } else {
                format!("{path}[{text:?}]")
            }
        }
        other => format!("{path}[{}]", other.type_name()),
    }
}

/// How Luau prints a number in a message: `nan`, `inf`, `-inf`, or the number.
fn lua_number(n: f64) -> String {
    if n.is_nan() {
        "nan".to_owned()
    } else if n.is_infinite() {
        if n > 0.0 { "inf" } else { "-inf" }.to_owned()
    } else {
        format!("{n}")
    }
}

fn a_or_an(word: &str) -> String {
    let vowel = word
        .chars()
        .next()
        .is_some_and(|c| "AEIOUaeiou".contains(c));
    format!("{} {word}", if vowel { "an" } else { "a" })
}

/// A JSON string: ASCII's control characters, quotes, backslashes and DEL
/// escaped, everything else as it is. JSON is UTF-8, so bytes of a script's
/// string that aren't are written as U+FFFD.
fn string(out: &mut String, bytes: &[u8]) {
    out.push('"');
    let text = String::from_utf8_lossy(bytes);
    for c in text.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\u{8}' => out.push_str("\\b"),
            '\u{c}' => out.push_str("\\f"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 || c as u32 == 0x7f => {
                out.push_str(&format!("\\u{:04x}", c as u32))
            }
            c => out.push(c),
        }
    }
    out.push('"');
}
