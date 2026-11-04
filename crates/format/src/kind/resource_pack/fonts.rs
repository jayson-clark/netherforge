//! How pictures are drawn as characters of a font (format's `PackFonts`): the
//! characters a pack hands out, and the arithmetic for placing them. The pack
//! build writes the font files from it, the server composes a window title
//! with it, and the editor previews that title: all three agree to the pixel.

use crate::project::ImageInfo;

/// The font a pack's skins are in: `<namespace>:<pack>/gui`.
pub const GUI_FONT: &str = "gui";
/// Private-use characters for pictures start here.
pub const PUA_START: u32 = 0xE000;
/// Space characters live at the top of the private-use block, out of the pictures' way.
const NEGATIVE_START: u32 = 0xF800;
const POSITIVE_START: u32 = 0xF820;
/// Pictures one pack can hold: every private-use character below the space characters.
pub const PUA_COUNT: usize = (NEGATIVE_START - PUA_START) as usize;
/// Offsets are composed from powers of two up to this many bits.
const STEPS: u32 = 11;
/// The largest single move either way, in pixels.
pub const MAX_OFFSET: i32 = (1 << STEPS) - 1;

/// The `index`th picture character.
pub fn char_at(index: usize) -> String {
    assert!(
        index < PUA_COUNT,
        "A resource pack holds at most {PUA_COUNT} pictures"
    );
    char::from_u32(PUA_START + index as u32)
        .expect("a private-use character")
        .to_string()
}

/// The `space` provider's table: which character moves the cursor how far.
pub fn advances() -> Vec<(String, i32)> {
    let ch = |c: u32| {
        char::from_u32(c)
            .expect("a private-use character")
            .to_string()
    };
    (0..STEPS)
        .flat_map(|bit| {
            [
                (ch(NEGATIVE_START + bit), -(1 << bit)),
                (ch(POSITIVE_START + bit), 1 << bit),
            ]
        })
        .collect()
}

/// How far space character `code_point` moves the cursor, if it's one of `advances`.
pub fn space_advance(code_point: u32) -> Option<i32> {
    match code_point {
        c if (NEGATIVE_START..NEGATIVE_START + STEPS).contains(&c) => {
            Some(-(1 << (c - NEGATIVE_START)))
        }
        c if (POSITIVE_START..POSITIVE_START + STEPS).contains(&c) => {
            Some(1 << (c - POSITIVE_START))
        }
        _ => None,
    }
}

/// Characters that move the cursor `px` pixels; none for zero, held to `MAX_OFFSET` either way.
pub fn offset(px: i32) -> String {
    let wanted = px.clamp(-MAX_OFFSET, MAX_OFFSET);
    if wanted == 0 {
        return String::new();
    }
    let start = if wanted < 0 {
        NEGATIVE_START
    } else {
        POSITIVE_START
    };
    let mut left = wanted.abs();
    let mut out = String::new();
    for bit in (0..STEPS).rev() {
        let step = 1 << bit;
        if left >= step {
            out.push(char::from_u32(start + bit).expect("a private-use character"));
            left -= step;
        }
    }
    out
}

/// A bitmap glyph's advance as the game's bitmap provider computes it for a
/// one-character provider: the opaque width scaled to the drawn height,
/// rounded, plus a pixel of gap. (The game scales in `float`; this in doubles,
/// as format's Kotlin did, which agrees but for products within a float's error
/// of half a pixel.)
pub fn bitmap_advance(opaque_width: u32, image_height: u32, drawn_height: i32) -> i32 {
    assert!(image_height > 0, "An image is at least one pixel tall");
    (0.5 + f64::from(opaque_width) * f64::from(drawn_height) / f64::from(image_height)).floor()
        as i32
        + 1
}

/// A picture as characters of a font: `cells` is its provider's row of
/// characters, one a piece left to right; `text` draws the whole picture;
/// `advance` is how far `text` moves the cursor (none when its pixels are
/// unknown); `cell_advances` how far each of `cells` moves it on its own.
#[derive(Clone, Debug, PartialEq)]
pub struct Picture {
    pub cells: String,
    pub text: String,
    pub advance: Option<i32>,
    pub cell_advances: Option<Vec<i32>>,
}

/// `image` drawn `drawn_height` tall as `first`, its own character, and when
/// it's too wide for a sheet, pieces with a character each from `extra` (none
/// left: it stays whole). Unknown pixels, or a picture that can't be cut, stay
/// one character; the checks report those.
pub fn picture(
    first: String,
    image: Option<&ImageInfo>,
    drawn_height: i32,
    extra: &mut dyn FnMut() -> Option<String>,
) -> Picture {
    let Some(image) = image.filter(|i| i.height > 0) else {
        return Picture {
            cells: first.clone(),
            text: first,
            advance: None,
            cell_advances: None,
        };
    };
    let advance = bitmap_advance(image.opaque_width, image.height, drawn_height);
    let whole = Picture {
        cells: first.clone(),
        text: first.clone(),
        advance: Some(advance),
        cell_advances: Some(vec![advance]),
    };
    let count = match pieces(image.width, image.height, drawn_height.max(0) as u32) {
        Some(n) if n > 1 && image.height <= SHEET => n,
        _ => return whole,
    };
    let mut chars = vec![first];
    for _ in 1..count {
        match extra() {
            Some(c) => chars.push(c),
            None => return whole,
        }
    }
    let cell = image.width / count;
    let advances: Vec<i32> = (0..count)
        .map(|i| {
            bitmap_advance(
                image.opaque_width_between(i * cell, (i + 1) * cell),
                image.height,
                drawn_height,
            )
        })
        .collect();
    let text = lay(&chars, cell, image.height, drawn_height, &advances, advance);
    Picture {
        cells: chars.concat(),
        text,
        advance: Some(advance),
        cell_advances: Some(advances),
    }
}

/// The text that draws a picture cut into pieces (`chars`, left to right, each
/// `cell_width` pixels of a picture `image_height` tall, drawn `drawn_height`
/// tall): each piece where it is in the whole, then a move so the whole moves
/// the cursor `advance`, as the picture would in one piece.
pub fn lay(
    chars: &[String],
    cell_width: u32,
    image_height: u32,
    drawn_height: i32,
    advances: &[i32],
    advance: i32,
) -> String {
    let mut out = String::new();
    let mut cursor = 0;
    for (i, ch) in chars.iter().enumerate() {
        let at = (0.5
            + i as f64 * f64::from(cell_width) * f64::from(drawn_height) / f64::from(image_height))
        .floor() as i32;
        out.push_str(&offset(at - cursor));
        out.push_str(ch);
        cursor = at + advances[i];
    }
    out.push_str(&offset(advance - cursor));
    out
}

/// The widest and tallest picture a font sheet draws: one too tall is drawn
/// blank, one too wide is cut into equal pieces.
pub const SHEET: u32 = 256;

/// The most pieces `pieces` tries per piece a picture needs at least, looking for an even cut.
const CUT_TRIES: u32 = 4;

/// How many equal pieces, side by side, a picture `width` pixels wide is drawn
/// as so each fits a sheet: 1 when it fits whole, `None` when no even cut does.
/// Of the cuts that fit, the first whose pieces are a whole number of pixels
/// wide drawn `drawn_height` tall (from `image_height`), so they meet without a seam.
pub fn pieces(width: u32, image_height: u32, drawn_height: u32) -> Option<u32> {
    if width <= SHEET {
        return Some(1);
    }
    let least = width.div_ceil(SHEET);
    let even: Vec<u32> = (least..=least * CUT_TRIES)
        .filter(|n| width.is_multiple_of(*n))
        .collect();
    even.iter()
        .find(|n| u64::from(width / *n) * u64::from(drawn_height) % u64::from(image_height) == 0)
        .or(even.first())
        .copied()
}

/// The width a picture `width` wide pads to with transparent columns so it cuts into even pieces.
pub fn even_width(width: u32) -> u32 {
    let least = width.div_ceil(SHEET);
    width.div_ceil(least) * least
}
