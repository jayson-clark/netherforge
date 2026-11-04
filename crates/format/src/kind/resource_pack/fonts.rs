//! How pictures are drawn as characters of a font (format's `PackFonts`, as
//! far as the checks need it; the pack build lays them out with the rest).

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
