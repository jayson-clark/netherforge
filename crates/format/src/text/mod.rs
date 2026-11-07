//! MiniMessage text as format reads it (format's `text`): glyph and space tags,
//! the editor's pass over a text's pieces and its layout (`metrics`), and the
//! server's exact measure (`width`). The default font's advances are `font`.

pub mod glyph;
pub mod metrics;
pub mod minimessage;
pub mod space;
pub mod width;

pub use metrics::TextMetrics;
pub use width::TextWidth;

/// A character's advance by code point, where known.
pub(crate) type CharAdvance<'a> = Box<dyn Fn(u32) -> Option<i32> + 'a>;

/// A pack glyph's advance by reference (`ui:coin`), where known.
pub(crate) type GlyphAdvance<'a> = Box<dyn Fn(&str) -> Option<i32> + 'a>;

/// `text`'s length in UTF-16 code units, as the editor counts.
pub fn utf16_len(text: &str) -> usize {
    text.encode_utf16().count()
}

/// The code point at `at` in UTF-16 `units`, and how many units it takes: a
/// surrogate pair is one, past the BMP; a lone surrogate is itself.
fn code_point(units: &[u16], at: usize) -> (u32, usize) {
    let c = units[at];
    if (0xD800..0xDC00).contains(&c)
        && let Some(&low) = units.get(at + 1)
        && (0xDC00..0xE000).contains(&low)
    {
        let code = 0x10000 + ((u32::from(c) - 0xD800) << 10) + (u32::from(low) - 0xDC00);
        return (code, 2);
    }
    (u32::from(c), 1)
}
