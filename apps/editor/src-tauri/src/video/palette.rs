//! Map colours, as format's `screen/MapPalette.kt` and `screen/Dither.kt` match
//! and dither them: the same "redmean" distance, the same 8 by 8 Bayer matrix,
//! the same Floyd–Steinberg error in sixteenths, so a picture the server draws
//! and a video the editor converts come out alike. The colours themselves are
//! the game's (the dev server's export, `mapColors` in the cached game data):
//! nothing here knows one.

/// How many bytes a map pixel can be.
pub const SIZE: usize = 256;
/// Indices below this are the game's "no colour".
pub const FIRST_DRAWABLE: usize = 4;

/// How a frame's colours become map colours (format's `Dither`).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Dither {
    None,
    FloydSteinberg,
    Ordered,
}

impl Dither {
    /// As `video.json` spells it; Floyd–Steinberg when it says nothing.
    pub fn parse(text: Option<&str>) -> Option<Self> {
        match text {
            None | Some("floyd_steinberg") => Some(Self::FloydSteinberg),
            Some("none") => Some(Self::None),
            Some("ordered") => Some(Self::Ordered),
            Some(_) => None,
        }
    }

    /// Its number in a converted video's header.
    pub fn code(self) -> u8 {
        match self {
            Self::None => 0,
            Self::FloydSteinberg => 1,
            Self::Ordered => 2,
        }
    }
}

const BAYER: [i32; 64] = [
    0, 32, 8, 40, 2, 34, 10, 42, 48, 16, 56, 24, 50, 18, 58, 26, 12, 44, 4, 36, 14, 46, 6, 38, 60,
    28, 52, 20, 62, 30, 54, 22, 3, 35, 11, 43, 1, 33, 9, 41, 51, 19, 59, 27, 49, 17, 57, 25, 15,
    47, 7, 39, 13, 45, 5, 37, 63, 31, 55, 23, 61, 29, 53, 21,
];

/// The game's map colours: each index's `0xAARRGGBB`, alpha 0 where it draws nothing.
pub struct Palette {
    argb: [u32; SIZE],
    drawable: Vec<usize>,
    near: Vec<i16>,
    /// Exact answers by colour, for Floyd–Steinberg: a frame has far fewer colours than pixels.
    exact: std::collections::HashMap<u32, u8>,
}

impl Palette {
    pub fn new(colors: &[u32]) -> Self {
        let mut argb = [0u32; SIZE];
        for (i, c) in colors.iter().take(SIZE).enumerate() {
            argb[i] = *c;
        }
        let drawable = (FIRST_DRAWABLE..SIZE)
            .filter(|&i| argb[i] >> 24 != 0)
            .collect();
        Self {
            argb,
            drawable,
            near: vec![-1; 1 << 15],
            exact: std::collections::HashMap::new(),
        }
    }

    pub fn is_empty(&self) -> bool {
        self.drawable.is_empty()
    }

    /// Index [index]'s colour as `0xRRGGBB`, or None for a transparent one.
    pub fn rgb(&self, index: usize) -> Option<u32> {
        let c = *self.argb.get(index)?;
        (index >= FIRST_DRAWABLE && c >> 24 != 0).then_some(c & 0xFF_FFFF)
    }

    /// The colours, hashed as a converted video records which it was made for (FNV-1a over each big-endian).
    pub fn hash(&self) -> u32 {
        let mut hash: u32 = 0x811c_9dc5;
        for c in self.argb {
            for byte in c.to_be_bytes() {
                hash ^= byte as u32;
                hash = hash.wrapping_mul(0x0100_0193);
            }
        }
        hash
    }

    /// The drawable index nearest ([distance]) the colour, uncached; 0 when there's none.
    pub fn search(&self, r: i32, g: i32, b: i32) -> u8 {
        let mut best = 0usize;
        let mut best_distance = i64::MAX;
        for &i in &self.drawable {
            let c = self.argb[i];
            let d = distance(
                r,
                g,
                b,
                ((c >> 16) & 0xFF) as i32,
                ((c >> 8) & 0xFF) as i32,
                (c & 0xFF) as i32,
            );
            if d < best_distance {
                best_distance = d;
                best = i;
            }
        }
        best as u8
    }

    /// [search], remembered per 15-bit colour (its cell's middle answers for all of it).
    pub fn nearest(&mut self, rgb: u32) -> u8 {
        let key = (((rgb >> 9) & 0x7C00) | ((rgb >> 6) & 0x03E0) | ((rgb >> 3) & 0x001F)) as usize;
        if self.near[key] >= 0 {
            return self.near[key] as u8;
        }
        let r = ((key >> 10) & 31) as i32 * 8 + 4;
        let g = ((key >> 5) & 31) as i32 * 8 + 4;
        let b = (key & 31) as i32 * 8 + 4;
        let found = self.search(r, g, b);
        self.near[key] = found as i16;
        found
    }

    /// [rgba] (4 bytes a pixel, [width] by [height]) as map colour indices; a pixel less than half opaque is 0.
    pub fn dither(&mut self, rgba: &[u8], width: usize, height: usize, dither: Dither) -> Vec<u8> {
        let mut out = vec![0u8; width * height];
        match dither {
            Dither::None => {
                let (pixels, _) = rgba.as_chunks::<4>();
                for (i, px) in pixels.iter().take(width * height).enumerate() {
                    if px[3] >= 128 {
                        out[i] = self.nearest(rgb(px[0] as i32, px[1] as i32, px[2] as i32));
                    }
                }
            }
            Dither::Ordered => {
                for y in 0..height {
                    for x in 0..width {
                        let i = y * width + x;
                        let px = &rgba[i * 4..i * 4 + 4];
                        if px[3] < 128 {
                            continue;
                        }
                        let t = BAYER[(y & 7) * 8 + (x & 7)] - 32;
                        out[i] = self.nearest(rgb(
                            clamp(px[0] as i32 + t),
                            clamp(px[1] as i32 + t),
                            clamp(px[2] as i32 + t),
                        ));
                    }
                }
            }
            Dither::FloydSteinberg => self.floyd_steinberg(rgba, width, height, &mut out),
        }
        out
    }

    fn floyd_steinberg(&mut self, rgba: &[u8], width: usize, height: usize, out: &mut [u8]) {
        let mut row = vec![0i32; (width + 2) * 3];
        let mut next = vec![0i32; (width + 2) * 3];
        for y in 0..height {
            for x in 0..width {
                let i = y * width + x;
                let px = &rgba[i * 4..i * 4 + 4];
                let e = (x + 1) * 3;
                if px[3] < 128 {
                    continue;
                }
                let r = clamp(px[0] as i32 + row[e] / 16);
                let g = clamp(px[1] as i32 + row[e + 1] / 16);
                let b = clamp(px[2] as i32 + row[e + 2] / 16);
                let index = self.search_cached(r, g, b);
                out[i] = index;
                let got = self.rgb(index as usize).unwrap_or(rgb(r, g, b));
                let dr = r - ((got >> 16) & 0xFF) as i32;
                let dg = g - ((got >> 8) & 0xFF) as i32;
                let db = b - (got & 0xFF) as i32;
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
            next.iter_mut().for_each(|v| *v = 0);
        }
    }

    /// The exact nearest, as format's Floyd–Steinberg searches it, remembered by colour.
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

/// format's `MapPalette.distance`: squared, weighted by how red the pair is, kept whole.
pub fn distance(r1: i32, g1: i32, b1: i32, r2: i32, g2: i32, b2: i32) -> i64 {
    let mean = ((r1 + r2) / 2) as i64;
    let dr = (r1 - r2) as i64;
    let dg = (g1 - g2) as i64;
    let db = (b1 - b2) as i64;
    (((512 + mean) * dr * dr) >> 8) + 4 * dg * dg + (((767 - mean) * db * db) >> 8)
}

fn clamp(v: i32) -> i32 {
    v.clamp(0, 255)
}

fn rgb(r: i32, g: i32, b: i32) -> u32 {
    ((r as u32) << 16) | ((g as u32) << 8) | b as u32
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A made-up palette (no game's): transparent, then black, white, red.
    fn palette() -> Palette {
        Palette::new(&[0, 0, 0, 0, 0xFF00_0000, 0xFFFF_FFFF, 0xFFFF_0000])
    }

    #[test]
    fn matches_the_nearest_drawable_colour() {
        let mut p = palette();
        assert_eq!(p.nearest(0x10_1010), 4);
        assert_eq!(p.nearest(0xF0_F0F0), 5);
        assert_eq!(p.nearest(0xE0_2020), 6);
        assert_eq!(p.rgb(0), None);
        assert_eq!(p.rgb(6), Some(0xFF_0000));
    }

    #[test]
    fn leaves_what_is_less_than_half_opaque_clear() {
        let mut p = palette();
        let pixels = [255, 0, 0, 255, 255, 0, 0, 10];
        for dither in [Dither::None, Dither::Ordered, Dither::FloydSteinberg] {
            assert_eq!(p.dither(&pixels, 2, 1, dither), vec![6, 0], "{dither:?}");
        }
    }

    #[test]
    fn floyd_steinberg_spreads_a_grey_into_black_and_white() {
        let mut p = palette();
        let grey = [128u8, 128, 128, 255].repeat(64);
        let out = p.dither(&grey, 8, 8, Dither::FloydSteinberg);
        let white = out.iter().filter(|&&i| i == 5).count();
        // About half each, as the eye averages it.
        assert!((24..=40).contains(&white), "{white} white of 64");
        assert!(out.iter().all(|&i| i == 4 || i == 5));
    }

    #[test]
    fn distance_is_formats() {
        // The same numbers MapPaletteTest pins on the Kotlin side.
        assert_eq!(distance(0, 0, 0, 255, 255, 255), 584_970);
        assert_eq!(distance(255, 0, 0, 0, 0, 255), 324_870);
    }
}
