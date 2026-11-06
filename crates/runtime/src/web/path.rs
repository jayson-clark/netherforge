//! A route's path under its package's own (Kotlin's `RoutePath`): literal segments, `:name` for any one segment, and
//! a last `*name` for the rest; and the request side's decoding of paths and queries.

use std::collections::BTreeMap;

use indexmap::IndexMap;

#[derive(Clone, Debug, PartialEq, Eq)]
enum Segment {
    Literal(String),
    Param(String),
    Rest(String),
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RoutePath {
    pub text: String,
    segments: Vec<Segment>,
}

fn is_literal(part: &str) -> bool {
    !part.is_empty()
        && part
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"._~-".contains(&b))
}

fn is_name(name: &str) -> bool {
    let mut bytes = name.bytes();
    bytes
        .next()
        .is_some_and(|b| b.is_ascii_lowercase() || b == b'_')
        && bytes.all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'_')
}

impl RoutePath {
    /// `text` as a route's path, or why it isn't one.
    pub fn parse(text: &str) -> Result<RoutePath, String> {
        if !text.starts_with('/') {
            return Err("a path starts with /".into());
        }
        if text == "/" {
            return Ok(RoutePath {
                text: text.into(),
                segments: Vec::new(),
            });
        }
        let parts: Vec<&str> = text[1..].split('/').collect();
        let mut segments = Vec::new();
        let mut names = std::collections::BTreeSet::new();
        for (index, part) in parts.iter().enumerate() {
            let segment = if part.is_empty() {
                return Err("it has an empty segment (two slashes, or one at the end)".into());
            } else if part.starts_with(':') || part.starts_with('*') {
                let name = &part[1..];
                if !is_name(name) {
                    return Err(format!(
                        "\"{part}\": a parameter's name is lowercase letters, digits and _"
                    ));
                }
                if !names.insert(name.to_owned()) {
                    return Err(format!("it names \"{name}\" twice"));
                }
                if part.starts_with('*') {
                    if index != parts.len() - 1 {
                        return Err(format!(
                            "\"{part}\" takes the rest of the path, so it comes last"
                        ));
                    }
                    Segment::Rest(name.into())
                } else {
                    Segment::Param(name.into())
                }
            } else if !is_literal(part) || *part == "." || *part == ".." {
                return Err(format!(
                    "\"{part}\" isn't a segment: letters, digits, -, _, . and ~, or :name or *name"
                ));
            } else {
                Segment::Literal((*part).into())
            };
            segments.push(segment);
        }
        Ok(RoutePath {
            text: text.into(),
            segments,
        })
    }

    /// The parameters when `path` (a request's decoded segments after the namespace) matches.
    pub fn matches(&self, path: &[String]) -> Option<BTreeMap<String, String>> {
        let mut params = BTreeMap::new();
        for (index, segment) in self.segments.iter().enumerate() {
            match segment {
                Segment::Rest(name) => {
                    if index >= path.len() {
                        return None;
                    }
                    params.insert(name.clone(), path[index..].join("/"));
                    return Some(params);
                }
                Segment::Literal(text) => {
                    if path.get(index) != Some(text) {
                        return None;
                    }
                }
                Segment::Param(name) => {
                    params.insert(name.clone(), path.get(index)?.clone());
                }
            }
        }
        (path.len() == self.segments.len()).then_some(params)
    }

    /// How specific it is: a literal segment beats a `:name` there, which beats a `*name`.
    pub fn rank(&self) -> Vec<u8> {
        self.segments
            .iter()
            .map(|s| match s {
                Segment::Literal(_) => 0,
                Segment::Param(_) => 1,
                Segment::Rest(_) => 2,
            })
            .collect()
    }

    /// Whether this and `other` answer exactly the same paths (`/a/:x` and `/a/:y` do).
    pub fn same_as(&self, other: &RoutePath) -> bool {
        self.segments.len() == other.segments.len()
            && self
                .segments
                .iter()
                .zip(&other.segments)
                .all(|(a, b)| match (a, b) {
                    (Segment::Literal(x), Segment::Literal(y)) => x == y,
                    (Segment::Param(_), Segment::Param(_))
                    | (Segment::Rest(_), Segment::Rest(_)) => true,
                    _ => false,
                })
    }
}

/// Which of two ranks is more specific: the first segment they differ at, then the longer.
pub fn compare_ranks(a: &[u8], b: &[u8]) -> std::cmp::Ordering {
    for (x, y) in a.iter().zip(b) {
        if x != y {
            return x.cmp(y);
        }
    }
    b.len().cmp(&a.len())
}

/// A request's raw path as decoded segments (`/a%20b/c` is `a b`, `c`), or none when it can't be decoded. A trailing
/// slash is dropped.
pub fn segments(raw: &str) -> Option<Vec<String>> {
    let raw = raw.strip_prefix('/').unwrap_or(raw);
    let mut parts: Vec<&str> = raw.split('/').collect();
    if parts.last() == Some(&"") {
        parts.pop();
    }
    if parts.iter().any(|p| p.is_empty()) {
        return None;
    }
    let decoded: Vec<String> = parts
        .into_iter()
        .map(|p| decode(p, false))
        .collect::<Option<_>>()?;
    (!decoded.iter().any(|d| d == ".." || d == ".")).then_some(decoded)
}

/// Percent-decodes `text` as UTF-8 (and, in a query, `+` as a space); none when a `%` isn't followed by two hex
/// digits.
pub fn decode(text: &str, plus_is_space: bool) -> Option<String> {
    if !text.contains('%') && !(plus_is_space && text.contains('+')) {
        return Some(text.to_owned());
    }
    let bytes = text.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        match bytes[i] {
            b'%' => {
                let hex = text.get(i + 1..i + 3)?;
                out.push(u8::from_str_radix(hex, 16).ok()?);
                i += 3;
                continue;
            }
            b'+' if plus_is_space => out.push(b' '),
            b => out.push(b),
        }
        i += 1;
    }
    Some(String::from_utf8_lossy(&out).into_owned())
}

/// `a=1&b=x+y` as decoded names and values, the first of a repeated name kept; undecodable pairs skipped.
pub fn query(raw: Option<&str>) -> IndexMap<String, String> {
    let mut out = IndexMap::new();
    for pair in raw.unwrap_or_default().split('&') {
        if pair.is_empty() {
            continue;
        }
        let (name, value) = pair.split_once('=').unwrap_or((pair, ""));
        let (Some(name), Some(value)) = (decode(name, true), decode(value, true)) else {
            continue;
        };
        out.entry(name).or_insert(value);
    }
    out
}

/// `values` as a query string, names sorted, each part encoded.
pub fn encode_query<'a>(values: impl IntoIterator<Item = (&'a String, &'a String)>) -> String {
    let mut pairs: Vec<(&String, &String)> = values.into_iter().collect();
    pairs.sort_by(|a, b| a.0.cmp(b.0));
    pairs
        .iter()
        .map(|(n, v)| format!("{}={}", encode(n), encode(v)))
        .collect::<Vec<_>>()
        .join("&")
}

/// Java's `URLEncoder.encode` with a space as `%20`: letters, digits, `.`, `-`, `*` and `_` as they are.
pub fn encode(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    for b in text.bytes() {
        if b.is_ascii_alphanumeric() || b".-*_".contains(&b) {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{b:02X}"));
        }
    }
    out
}
