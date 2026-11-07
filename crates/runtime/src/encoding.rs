//! `nf.encoding` and `nf.crypto` (Kotlin's `EncodingImpls.kt`). Luau strings cross as text, so everything here is
//! over a text's UTF-8 bytes, and decoding to bytes that aren't UTF-8 is nil.

use std::collections::BTreeMap;

use base64::Engine;
use base64::engine::general_purpose::{STANDARD, STANDARD_NO_PAD, URL_SAFE, URL_SAFE_NO_PAD};
use hmac::{Hmac, Mac};
use mlua::{Table, Value};
use netherforge_api::api::{NfCryptoApi, NfEncodingApi};
use netherforge_api::{ApiError, ApiResult, Caller, shapes};
use sha1::Sha1;
use sha2::{Digest, Sha256, Sha512};

fn err(message: impl Into<String>) -> ApiError {
    ApiError(message.into())
}

/// `nf.encoding` and `nf.crypto`: no state of their own.
pub struct Encoding;

impl NfEncodingApi for Encoding {
    fn url_encode(&self, _: &Caller, text: String) -> ApiResult<String> {
        Ok(percent_encode(&text, false))
    }

    fn url_decode(&self, _: &Caller, text: String) -> ApiResult<Option<String>> {
        Ok(percent_decode(&text, false))
    }

    fn form_encode(&self, _: &Caller, fields: Value) -> ApiResult<String> {
        let Value::Table(table) = fields else {
            return Err(err(format!(
                "bad argument 'fields' (table expected, got {})",
                netherforge_api::codec::type_name(&fields)
            )));
        };
        if is_list(&table) {
            return Err(err(
                "bad argument 'fields' (a form's names are strings: a table of them expected, got a list)",
            ));
        }
        let mut pairs = Vec::new();
        entries(&table, "fields", None, 0, &mut pairs)?;
        Ok(pairs
            .iter()
            .map(|(name, value)| {
                format!(
                    "{}={}",
                    percent_encode(name, true),
                    percent_encode(value, true)
                )
            })
            .collect::<Vec<_>>()
            .join("&"))
    }

    /// A name given more than once is its first value, as `nf.web` reads a request's form.
    fn form_decode(&self, _: &Caller, text: String) -> ApiResult<Option<BTreeMap<String, String>>> {
        let mut fields = BTreeMap::new();
        for pair in text.split('&') {
            if pair.is_empty() {
                continue;
            }
            let (name, value) = pair.split_once('=').unwrap_or((pair, ""));
            let Some(name) = percent_decode(name, true) else {
                return Ok(None);
            };
            let Some(value) = percent_decode(value, true) else {
                return Ok(None);
            };
            fields.entry(name).or_insert(value);
        }
        Ok(Some(fields))
    }

    fn base64_encode(
        &self,
        _: &Caller,
        text: String,
        options: Option<shapes::Base64Options>,
    ) -> ApiResult<String> {
        let url_safe = options.as_ref().and_then(|o| o.url_safe) == Some(true);
        let padding = options.as_ref().and_then(|o| o.padding) != Some(false);
        let engine = match (url_safe, padding) {
            (false, true) => &STANDARD,
            (false, false) => &STANDARD_NO_PAD,
            (true, true) => &URL_SAFE,
            (true, false) => &URL_SAFE_NO_PAD,
        };
        Ok(engine.encode(text.as_bytes()))
    }

    /// Either alphabet, padded or not: the URL-safe one's `-_` read as `+/`, and padding put back.
    fn base64_decode(&self, _: &Caller, text: String) -> ApiResult<Option<String>> {
        let mut standard = text.replace('-', "+").replace('_', "/");
        while !standard.len().is_multiple_of(4) {
            standard.push('=');
        }
        Ok(STANDARD
            .decode(standard.as_bytes())
            .ok()
            .and_then(|b| String::from_utf8(b).ok()))
    }

    fn hex_encode(&self, _: &Caller, text: String) -> ApiResult<String> {
        Ok(hex(text.as_bytes()))
    }

    fn hex_decode(&self, _: &Caller, text: String) -> ApiResult<Option<String>> {
        if !text.len().is_multiple_of(2) {
            return Ok(None);
        }
        let digits = text.as_bytes();
        let mut bytes = Vec::with_capacity(digits.len() / 2);
        for pair in digits.chunks(2) {
            let (Some(high), Some(low)) = (digit(pair[0]), digit(pair[1])) else {
                return Ok(None);
            };
            bytes.push(high << 4 | low);
        }
        Ok(String::from_utf8(bytes).ok())
    }
}

impl NfCryptoApi for Encoding {
    fn hash(
        &self,
        _: &Caller,
        text: String,
        options: Option<shapes::HashOptions>,
    ) -> ApiResult<String> {
        let algorithm = options.as_ref().and_then(|o| o.algorithm.as_deref());
        let digest = match algorithm.unwrap_or("sha256") {
            "sha1" => Sha1::digest(text.as_bytes()).to_vec(),
            "sha512" => Sha512::digest(text.as_bytes()).to_vec(),
            _ => Sha256::digest(text.as_bytes()).to_vec(),
        };
        Ok(written(
            &digest,
            options.and_then(|o| o.encoding).as_deref(),
        ))
    }

    fn sign(&self, _: &Caller, text: String, options: shapes::SigningOptions) -> ApiResult<String> {
        Ok(written(
            &hmac(&text, &options.secret, options.algorithm.as_deref()),
            options.encoding.as_deref(),
        ))
    }
}

/// `text`'s HMAC under `secret`, by `"sha256"` (the default), `"sha1"` or `"sha512"`: `nf.crypto.sign`'s, and what
/// `nf.web.verify_signature` checks.
pub fn hmac(text: &str, secret: &str, algorithm: Option<&str>) -> Vec<u8> {
    fn run<M: Mac + hmac::digest::KeyInit>(text: &str, secret: &str) -> Vec<u8> {
        let mut mac = <M as hmac::digest::KeyInit>::new_from_slice(secret.as_bytes())
            .expect("an HMAC takes a key of any length");
        mac.update(text.as_bytes());
        mac.finalize().into_bytes().to_vec()
    }
    match algorithm.unwrap_or("sha256") {
        "sha1" => run::<Hmac<Sha1>>(text, secret),
        "sha512" => run::<Hmac<Sha512>>(text, secret),
        _ => run::<Hmac<Sha256>>(text, secret),
    }
}

fn written(bytes: &[u8], encoding: Option<&str>) -> String {
    if encoding == Some("base64") {
        STANDARD.encode(bytes)
    } else {
        hex(bytes)
    }
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn digit(c: u8) -> Option<u8> {
    (c as char).to_digit(16).map(|d| d as u8)
}

const HEX_DIGITS: &[u8; 16] = b"0123456789ABCDEF";

/// `text`'s UTF-8 bytes with all but the unreserved characters as `%XX`: in a URL (RFC 3986: letters, digits,
/// `-._~`), or in a form (the WHATWG's: letters, digits, `*-._`, and a space as `+`).
fn percent_encode(text: &str, form: bool) -> String {
    let mut out = String::with_capacity(text.len());
    for &b in text.as_bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'.' | b'_' => out.push(b as char),
            b'~' if !form => out.push('~'),
            b'*' if form => out.push('*'),
            b' ' if form => out.push('+'),
            _ => {
                out.push('%');
                out.push(HEX_DIGITS[(b >> 4) as usize] as char);
                out.push(HEX_DIGITS[(b & 0xF) as usize] as char);
            }
        }
    }
    out
}

/// `text` with its `%XX` decoded (and in a form `+` as a space); none for a `%` without two hex digits, or bytes
/// that aren't UTF-8.
fn percent_decode(text: &str, form: bool) -> Option<String> {
    if !text.contains('%') && !(form && text.contains('+')) {
        return Some(text.to_owned());
    }
    let utf8 = text.as_bytes();
    let mut bytes = Vec::with_capacity(utf8.len());
    let mut i = 0;
    while i < utf8.len() {
        match utf8[i] {
            b'%' => {
                let high = digit(*utf8.get(i + 1)?)?;
                let low = digit(*utf8.get(i + 2)?)?;
                bytes.push(high << 4 | low);
                i += 3;
                continue;
            }
            b'+' if form => bytes.push(b' '),
            b => bytes.push(b),
        }
        i += 1;
    }
    String::from_utf8(bytes).ok()
}

/// How deep tables in a form may go: deeper is a table inside itself, or near enough.
const MAX_FORM_DEPTH: usize = 32;

/// A list has a first item; any other table is an object (the empty table too).
fn is_list(table: &Table) -> bool {
    !matches!(table.raw_get::<Value>(1), Ok(Value::Nil) | Err(_))
}

/// A table's entries under `prefix` (none at the top), raw (no metamethod runs, so no script code): a list's by
/// position from 0, an object's by its keys, sorted, so the same table is the same text.
fn entries(
    table: &Table,
    at: &str,
    prefix: Option<&str>,
    depth: usize,
    pairs: &mut Vec<(String, String)>,
) -> ApiResult<()> {
    if depth > MAX_FORM_DEPTH {
        return Err(err(format!(
            "{at}: tables nested more than {MAX_FORM_DEPTH} deep (a table inside itself?) can't be put in a form"
        )));
    }
    if table.metatable().is_some() {
        return Err(err(format!(
            "{at}: a value with a metatable (a Vec3, a Location, a handle) can't be put in a form"
        )));
    }
    let lua_err = |e: mlua::Error| err(e.to_string());
    if is_list(table) {
        let size = table.raw_len();
        let count = table.pairs::<Value, Value>().count();
        if count != size {
            return Err(err(format!(
                "{at}: a list with holes or other keys can't be put in a form"
            )));
        }
        for position in 1..=size {
            let item: Value = table.raw_get(position).map_err(lua_err)?;
            let name = format!("{}[{}]", prefix.unwrap_or(""), position - 1);
            value(item, &format!("{at}[{position}]"), &name, depth, pairs)?;
        }
        return Ok(());
    }
    let mut keys = Vec::new();
    for pair in table.pairs::<Value, Value>() {
        let (key, _) = pair.map_err(lua_err)?;
        let Value::String(key) = key else {
            return Err(err(format!(
                "{at}: a key that isn't a string, outside a list, can't be put in a form"
            )));
        };
        keys.push(key.to_string_lossy());
    }
    keys.sort();
    for key in keys {
        let item: Value = table.raw_get(key.as_str()).map_err(lua_err)?;
        let name = match prefix {
            None => key.clone(),
            Some(prefix) => format!("{prefix}[{key}]"),
        };
        value(item, &format!("{at}.{key}"), &name, depth, pairs)?;
    }
    Ok(())
}

fn value(
    item: Value,
    at: &str,
    name: &str,
    depth: usize,
    pairs: &mut Vec<(String, String)>,
) -> ApiResult<()> {
    match item {
        Value::String(s) => pairs.push((name.to_owned(), s.to_string_lossy())),
        Value::Boolean(b) => pairs.push((name.to_owned(), b.to_string())),
        Value::Integer(n) => pairs.push((name.to_owned(), n.to_string())),
        Value::Number(n) => {
            if !n.is_finite() {
                let text = if n.is_nan() {
                    "NaN"
                } else if n > 0.0 {
                    "Infinity"
                } else {
                    "-Infinity"
                };
                return Err(err(format!("{at}: {text} can't be put in a form")));
            }
            // A whole number as itself, any other as plain decimal (never an exponent).
            pairs.push((name.to_owned(), format!("{n}")));
        }
        Value::Table(t) => entries(&t, at, Some(name), depth + 1, pairs)?,
        other => {
            return Err(err(format!(
                "{at}: a {} can't be put in a form",
                netherforge_api::codec::type_name(&other)
            )));
        }
    }
    Ok(())
}
