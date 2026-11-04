//! Lua source as format reads it without running it (format's `LuaStrings`):
//! its string literals, comments skipped, escapes and long brackets understood.
//! (Its tokens, which the editor's network uses read, come with the editor.)

/// Every string literal in `source`, in order, its escapes worked out (`\z`,
/// `\x`, `\u{…}` and decimal ones too).
pub fn literals(source: &str) -> Vec<String> {
    let chars: Vec<char> = source.chars().collect();
    let n = chars.len();
    let mut out = Vec::new();
    let mut i = 0;
    while i < n {
        let c = chars[i];
        if c == '-' && i + 1 < n && chars[i + 1] == '-' {
            i = match long_open(&chars, i + 2) {
                Some(level) => long_close(&chars, i + 2 + level + 2, level).1,
                None => chars[i..]
                    .iter()
                    .position(|c| *c == '\n')
                    .map_or(n, |p| i + p),
            };
        } else if c == '['
            && let Some(level) = long_open(&chars, i)
        {
            let mut start = i + level + 2;
            // A newline straight after the opening bracket isn't part of the string.
            if start < n && chars[start] == '\r' {
                start += 1;
            }
            if start < n && chars[start] == '\n' {
                start += 1;
            }
            let (text, next) = long_close(&chars, start, level);
            out.push(text);
            i = next;
        } else if c == '"' || c == '\'' {
            let (text, next) = quoted(&chars, i + 1, c);
            out.push(text);
            i = next;
        } else {
            i += 1;
        }
    }
    out
}

/// The level of a long bracket opening at `at` (`[[` is 0, `[==[` 2), if one does.
fn long_open(chars: &[char], at: usize) -> Option<usize> {
    if chars.get(at) != Some(&'[') {
        return None;
    }
    let mut j = at + 1;
    while chars.get(j) == Some(&'=') {
        j += 1;
    }
    (chars.get(j) == Some(&'[')).then_some(j - at - 1)
}

/// The text from `start` to the closing long bracket of `level`, and where reading carries on.
fn long_close(chars: &[char], start: usize, level: usize) -> (String, usize) {
    let close: Vec<char> = std::iter::once(']')
        .chain(std::iter::repeat_n('=', level))
        .chain(std::iter::once(']'))
        .collect();
    let start = start.min(chars.len());
    match chars[start..]
        .windows(close.len())
        .position(|w| w == close.as_slice())
    {
        Some(p) => (
            chars[start..start + p].iter().collect(),
            start + p + close.len(),
        ),
        None => (chars[start..].iter().collect(), chars.len()),
    }
}

/// A quoted string's text from `start` (just past its opening `quote`), and where reading carries on.
fn quoted(chars: &[char], start: usize, quote: char) -> (String, usize) {
    let mut text = String::new();
    let mut i = start;
    let hex = |from: usize, to: usize| -> Option<char> {
        let digits: String = chars[from.min(chars.len())..to.min(chars.len())]
            .iter()
            .collect();
        u32::from_str_radix(&digits, 16)
            .ok()
            .and_then(char::from_u32)
    };
    while i < chars.len() {
        let c = chars[i];
        if c == quote {
            return (text, i + 1);
        }
        if c == '\n' {
            return (text, i);
        }
        if c != '\\' || i + 1 >= chars.len() {
            text.push(c);
            i += 1;
            continue;
        }
        let e = chars[i + 1];
        i += 2;
        match e {
            'n' => text.push('\n'),
            't' => text.push('\t'),
            'r' => text.push('\r'),
            'a' => text.push('\u{7}'),
            'b' => text.push('\u{8}'),
            'f' => text.push('\u{c}'),
            'v' => text.push('\u{b}'),
            'z' => {
                while i < chars.len() && chars[i].is_whitespace() {
                    i += 1;
                }
            }
            'x' => {
                text.extend(hex(i, i + 2));
                i += 2;
            }
            'u' => {
                if let Some(close) = chars[i..].iter().position(|c| *c == '}').map(|p| i + p)
                    && close > i
                {
                    text.extend(hex(i + 1, close));
                    i = close + 1;
                }
            }
            _ if e.is_ascii_digit() => {
                let mut j = i - 1;
                while j < chars.len() && j < i + 2 && chars[j].is_ascii_digit() {
                    j += 1;
                }
                let digits: String = chars[i - 1..j].iter().collect();
                text.extend(digits.parse().ok().and_then(char::from_u32));
                i = j;
            }
            _ => text.push(e),
        }
    }
    (text, i)
}

#[cfg(test)]
mod tests {
    use super::literals;

    #[test]
    fn reads_literals_as_lua_does() {
        let source = "local a = \"minecraft:stone\" -- \"not this\"\n\
                      local b = 'it\\'s' .. [[long\nstring]] --[==[ \"nor this\" ]==]\n\
                      local c = \"\\x41\\u{42}\\67\\z   d\" .. [=[\nx]=]";
        assert_eq!(
            literals(source),
            ["minecraft:stone", "it's", "long\nstring", "ABCd", "x"]
        );
    }
}
