//! `<glyph:ui/coin>` (format's `GlyphTags`): a pack's glyph inside MiniMessage
//! text, named like any reference (`<pack>/<key>`, or `<namespace>:<pack>/<key>`
//! for a package's).
//!
//! MiniMessage splits a tag's arguments at `:` (an argument in `'…'` or `"…"`
//! may hold colons), so `<glyph:acme:ui/coin>` arrives as `acme`, `ui/coin`
//! and `<glyph:'acme:ui/coin'>` as `acme:ui/coin`; both name the glyph
//! `acme:ui/coin`, the arguments joined with `:`. The tag inserts the glyph
//! and closes nothing.
//!
//! One home for the references' walk, text measuring, the MiniMessage pass
//! and the server's tag resolver, so they read the same references out of the
//! same text.

/// The tag's name.
pub const NAME: &str = "glyph";

/// One `<glyph:…>` in a text: the reference it names and where the tag is,
/// `start` at `<` and `end` past `>`, in UTF-16 code units (as the editor
/// counts).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Glyph {
    pub reference: String,
    pub start: usize,
    pub end: usize,
}

/// The reference a tag names, from its inside without the angle brackets
/// (`glyph:ui/coin`), if it's a glyph tag. A glyph tag with no arguments
/// names `""`.
pub fn reference(tag: &str) -> Option<String> {
    let (name, rest) = match tag.split_once(':') {
        Some((n, r)) => (n, Some(r)),
        None => (tag, None),
    };
    if !name.eq_ignore_ascii_case(NAME) {
        return None;
    }
    Some(rest.map_or_else(String::new, |r| arguments(r).join(":")))
}

/// MiniMessage's split of a tag's arguments: at `:`, except inside quotes;
/// quotes removed, `\` escapes the next character inside them.
pub fn arguments(text: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut current = String::new();
    let mut quote: Option<char> = None;
    let mut chars = text.chars().peekable();
    while let Some(c) = chars.next() {
        match quote {
            Some(_) if c == '\\' && chars.peek().is_some() => current.push(chars.next().unwrap()),
            Some(q) if c == q => quote = None,
            None if (c == '\'' || c == '"') && current.is_empty() => quote = Some(c),
            None if c == ':' => out.push(std::mem::take(&mut current)),
            _ => current.push(c),
        }
    }
    out.push(current);
    out
}

/// Every glyph tag in MiniMessage `text`, in order. A tag escaped with `\<` isn't one.
pub fn find(text: &str) -> Vec<Glyph> {
    tags(text)
        .into_iter()
        .map(|(reference, open, close)| {
            let start = super::utf16_len(&text[..open]);
            Glyph {
                reference,
                start,
                end: start + super::utf16_len(&text[open..close]),
            }
        })
        .collect()
}

/// Each glyph tag's reference and where it is in `text`, in bytes (`<` to past `>`).
fn tags(text: &str) -> Vec<(String, usize, usize)> {
    let mut out = Vec::new();
    let mut cursor = 0;
    while let Some(open) = text[cursor..].find('<').map(|i| cursor + i) {
        if text[..open].ends_with('\\') {
            cursor = open + 1;
            continue;
        }
        let Some(close) = text[open..].find('>').map(|i| open + i) else {
            break;
        };
        if let Some(reference) = reference(&text[open + 1..close]) {
            out.push((reference, open, close + 1));
        }
        cursor = close + 1;
    }
    out
}

/// `text` with each glyph tag rewritten as `replace` answers for its reference:
/// new text (the tag becomes `<glyph:new>`), `""` to drop it, `None` to leave
/// it. `None` when nothing changed.
pub fn rewrite(text: &str, replace: &mut dyn FnMut(&str) -> Option<String>) -> Option<String> {
    let mut out = String::new();
    let mut cursor = 0;
    let mut changed = false;
    for (reference, open, close) in tags(text) {
        let Some(next) = replace(&reference) else {
            continue;
        };
        out.push_str(&text[cursor..open]);
        if !next.is_empty() {
            out.push_str(&format!("<{NAME}:{next}>"));
        }
        cursor = close;
        changed = true;
    }
    changed.then(|| out + &text[cursor..])
}
