//! The colours a map can show, and how a picture's colours become them
//! (format's `screen/MapPalette` and `screen/Dither`): the server's screens and
//! the editor's video converter both match and dither through here, so a
//! picture the server draws and a video the editor converts come out alike.
//!
//! The colours are the game's map colours, each base colour in four shades, so
//! a map's pixel is a byte `base * 4 + shade` (the server's own table, or the
//! dev server's export in the cached game data: nothing here knows a colour of
//! the game's). Indices 0 to 3 are transparent (the frame's map background
//! shows through), and so is any index the game has no colour for.
//!
//! Matching a colour picks the shade nearest it by a weighted distance
//! (`distance`: red and blue weighed by how red the pair is, which tracks what
//! eyes see far better than plain RGB distance at no cost). A match is
//! remembered per 15-bit colour (five bits a channel), so drawing a picture or
//! a video frame costs a table lookup a pixel once the table is warm.
//! Floyd–Steinberg matches exactly instead (the error it carries is what keeps a
//! gradient smooth), remembered by colour. Only whole-number arithmetic, so
//! every side gets the same indices.

use std::collections::HashMap;

pub use crate::kind::video::Dither;

/// How many indices a map pixel can hold.
pub const SIZE: usize = 256;

/// The index that draws nothing.
pub const TRANSPARENT: u8 = 0;

/// Indices below this are the game's "no colour", whatever shade.
pub const FIRST_DRAWABLE: usize = 4;

/// A pixel's alpha at or past which it's drawn; below, it's left out.
const OPAQUE_FROM: u32 = 128;

/// The 8 by 8 Bayer matrix, 0 to 63.
const BAYER: [i32; 64] = [
    0, 32, 8, 40, 2, 34, 10, 42, 48, 16, 56, 24, 50, 18, 58, 26, 12, 44, 4, 36, 14, 46, 6, 38, 60,
    28, 52, 20, 62, 30, 54, 22, 3, 35, 11, 43, 1, 33, 9, 41, 51, 19, 59, 27, 49, 17, 57, 25, 15,
    47, 7, 39, 13, 45, 5, 37, 63, 31, 55, 23, 61, 29, 53, 21,
];

/// The game's map colours, and what each colour asked for matched.
#[derive(Clone)]
pub struct Palette {
    /// Each index's colour as `0xAARRGGBB`; alpha 0 for an index that isn't drawable.
    argb: [u32; SIZE],
    /// The drawable indices, in order: what `search` looks through.
    drawable: Vec<u8>,
    /// The answer for each 15-bit colour, or -1 until it's first asked.
    near: Vec<i16>,
    /// Exact answers by colour, for Floyd–Steinberg: a frame has far fewer colours than pixels.
    exact: HashMap<u32, u8>,
}

impl Palette {
    /// A palette of `colors` (`0xAARRGGBB` by index); one with none draws nothing.
    pub fn new(colors: &[u32]) -> Palette {
        let mut argb = [0u32; SIZE];
        for (i, c) in colors.iter().take(SIZE).enumerate() {
            argb[i] = *c;
        }
        let drawable = (FIRST_DRAWABLE..SIZE)
            .filter(|&i| argb[i] >> 24 != 0)
            .map(|i| i as u8)
            .collect();
        Palette {
            argb,
            drawable,
            near: vec![-1; 1 << 15],
            exact: HashMap::new(),
        }
    }

    /// The game's palette, or an empty one when `game` doesn't say.
    pub fn of(game: Option<&dyn crate::game::GameData>) -> Palette {
        Palette::new(game.and_then(|g| g.map_colors()).unwrap_or_default())
    }

    /// Whether the palette has anything to draw with: a game that told us nothing has none.
    pub fn is_empty(&self) -> bool {
        self.drawable.is_empty()
    }

    /// The drawable indices, in order.
    pub fn drawable(&self) -> &[u8] {
        &self.drawable
    }

    /// The colour of map index `index` as `0xRRGGBB`, or none for a transparent one.
    pub fn rgb(&self, index: u8) -> Option<u32> {
        let c = self.argb[usize::from(index)];
        (usize::from(index) >= FIRST_DRAWABLE && c >> 24 != 0).then_some(c & 0xFF_FFFF)
    }

    /// Whether `index` draws anything.
    pub fn is_drawable(&self, index: u8) -> bool {
        self.rgb(index).is_some()
    }

    /// The colours, hashed as a converted video records which it was made for (FNV-1a over each, big-endian).
    pub fn hash(&self) -> u32 {
        let mut hash: u32 = 0x811c_9dc5;
        for c in self.argb {
            for byte in c.to_be_bytes() {
                hash ^= u32::from(byte);
                hash = hash.wrapping_mul(0x0100_0193);
            }
        }
        hash
    }

    /// The drawable index nearest (`distance`) the colour (`r`, `g`, `b`, each 0 to 255), uncached;
    /// `TRANSPARENT` when the palette is empty.
    pub fn search(&self, r: i32, g: i32, b: i32) -> u8 {
        let mut best = TRANSPARENT;
        let mut best_distance = i64::MAX;
        for &i in &self.drawable {
            let c = self.argb[usize::from(i)];
            let d = distance(r, g, b, red(c), green(c), blue(c));
            if d < best_distance {
                best_distance = d;
                best = i;
            }
        }
        best
    }

    /// The drawable index nearest the colour `rgb` (`0xRRGGBB`), remembered per 15-bit colour (its cell's
    /// middle answers for all of it); `TRANSPARENT` when the palette is empty.
    pub fn nearest(&mut self, rgb: u32) -> u8 {
        let key = (((rgb >> 9) & 0x7C00) | ((rgb >> 6) & 0x03E0) | ((rgb >> 3) & 0x001F)) as usize;
        if let Ok(known) = u8::try_from(self.near[key]) {
            return known;
        }
        // The middle of the 15-bit cell, so every colour in it gets the same answer.
        let r = ((key >> 10) & 31) as i32 * 8 + 4;
        let g = ((key >> 5) & 31) as i32 * 8 + 4;
        let b = (key & 31) as i32 * 8 + 4;
        let found = self.search(r, g, b);
        self.near[key] = i16::from(found);
        found
    }

    /// The exact nearest, as Floyd–Steinberg searches it, remembered by colour.
    fn search_cached(&mut self, r: i32, g: i32, b: i32) -> u8 {
        let key = rgb(r, g, b);
        if let Some(&found) = self.exact.get(&key) {
            return found;
        }
        let found = self.search(r, g, b);
        if self.exact.len() > 1 << 20 {
            self.exact.clear();
        }
        self.exact.insert(key, found);
        found
    }
}

impl Dither {
    /// A picture `width` by `height` as map colours of `palette`: `pixel(i)` is
    /// pixel `i`'s `0xAARRGGBB`, row by row, and each answer an index, or none
    /// for a pixel less than half opaque (which draws nothing: the server leaves
    /// what was there, a converted video draws `TRANSPARENT`).
    pub fn apply(
        self,
        palette: &mut Palette,
        width: usize,
        height: usize,
        pixel: impl Fn(usize) -> u32,
    ) -> Vec<Option<u8>> {
        let mut out = vec![None; width * height];
        match self {
            Dither::None => {
                for (i, slot) in out.iter_mut().enumerate() {
                    let c = pixel(i);
                    if opaque(c) {
                        *slot = Some(palette.nearest(c & 0xFF_FFFF));
                    }
                }
            }
            Dither::Ordered => {
                for y in 0..height {
                    for x in 0..width {
                        let i = y * width + x;
                        let c = pixel(i);
                        if !opaque(c) {
                            continue;
                        }
                        // A threshold from -32 to +30, the Bayer matrix's cell spread over a quarter of the range.
                        let t = BAYER[(y & 7) * 8 + (x & 7)] - 32;
                        out[i] = Some(palette.nearest(rgb(
                            clamp(red(c) + t),
                            clamp(green(c) + t),
                            clamp(blue(c) + t),
                        )));
                    }
                }
            }
            Dither::FloydSteinberg => floyd_steinberg(palette, width, height, &pixel, &mut out),
        }
        out
    }

    /// Its number in a converted video's header.
    pub fn code(self) -> u8 {
        match self {
            Dither::None => 0,
            Dither::FloydSteinberg => 1,
            Dither::Ordered => 2,
        }
    }
}

fn floyd_steinberg(
    palette: &mut Palette,
    width: usize,
    height: usize,
    pixel: &dyn Fn(usize) -> u32,
    out: &mut [Option<u8>],
) {
    // The error carried to this row and the next, in sixteenths, per channel.
    let mut row = vec![0i32; (width + 2) * 3];
    let mut next = vec![0i32; (width + 2) * 3];
    for y in 0..height {
        for x in 0..width {
            let i = y * width + x;
            let c = pixel(i);
            let e = (x + 1) * 3;
            if !opaque(c) {
                continue;
            }
            let r = clamp(red(c) + row[e] / 16);
            let g = clamp(green(c) + row[e + 1] / 16);
            let b = clamp(blue(c) + row[e + 2] / 16);
            let index = palette.search_cached(r, g, b);
            out[i] = Some(index);
            let got = palette.rgb(index).unwrap_or(rgb(r, g, b));
            let (dr, dg, db) = (r - red(got), g - green(got), b - blue(got));
            // 7/16 right, 3/16 below left, 5/16 below, 1/16 below right.
            row[e + 3] += dr * 7;
            row[e + 4] += dg * 7;
            row[e + 5] += db * 7;
            next[e - 3] += dr * 3;
            next[e - 2] += dg * 3;
            next[e - 1] += db * 3;
            next[e] += dr * 5;
            next[e + 1] += dg * 5;
            next[e + 2] += db * 5;
            next[e + 3] += dr;
            next[e + 4] += dg;
            next[e + 5] += db;
        }
        std::mem::swap(&mut row, &mut next);
        next.fill(0);
    }
}

/// The "redmean" distance between two colours, squared and scaled to stay
/// whole: red and blue count more or less as the pair is redder, which is
/// close to how far apart they look.
pub fn distance(r1: i32, g1: i32, b1: i32, r2: i32, g2: i32, b2: i32) -> i64 {
    let mean = i64::from((r1 + r2) / 2);
    let (dr, dg, db) = (i64::from(r1 - r2), i64::from(g1 - g2), i64::from(b1 - b2));
    (((512 + mean) * dr * dr) >> 8) + 4 * dg * dg + (((767 - mean) * db * db) >> 8)
}

/// `#rrggbb` as `0xRRGGBB`, or none when it isn't written so.
pub fn parse(color: &str) -> Option<u32> {
    let hex = color.strip_prefix('#')?;
    if hex.len() != 6 || !hex.bytes().all(|b| b.is_ascii_hexdigit()) {
        return None;
    }
    u32::from_str_radix(hex, 16).ok()
}

fn opaque(argb: u32) -> bool {
    argb >> 24 >= OPAQUE_FROM
}

fn red(c: u32) -> i32 {
    ((c >> 16) & 0xFF) as i32
}

fn green(c: u32) -> i32 {
    ((c >> 8) & 0xFF) as i32
}

fn blue(c: u32) -> i32 {
    (c & 0xFF) as i32
}

fn clamp(v: i32) -> i32 {
    v.clamp(0, 255)
}

fn rgb(r: i32, g: i32, b: i32) -> u32 {
    ((r as u32) << 16) | ((g as u32) << 8) | b as u32
}
