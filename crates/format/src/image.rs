//! A picture's pixels (format's `image/Pixels`).

/// A picture's pixels, `0xAARRGGBB` row by row from the top left: what an
/// image display shows and what `nf.images` makes. Never changed once made, and
/// equal to another with the same pixels, so showing the same picture again
/// sends nothing.
#[derive(Clone, PartialEq, Eq, Hash)]
pub struct Pixels {
    width: u32,
    height: u32,
    argb: Vec<u32>,
}

/// A pixel's alpha at or past which an image display draws it; below, it's left out.
pub const OPAQUE_FROM: u32 = 128;

impl std::fmt::Debug for Pixels {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "Pixels({}x{})", self.width, self.height)
    }
}

impl Pixels {
    /// A picture `width` by `height` of `argb`; an error when it's empty or the pixels don't fill it.
    pub fn new(width: u32, height: u32, argb: Vec<u32>) -> Result<Pixels, String> {
        if width == 0 || height == 0 {
            return Err(format!(
                "a picture is at least 1 by 1 pixels, not {width} by {height}"
            ));
        }
        let wanted = width as usize * height as usize;
        if argb.len() != wanted {
            return Err(format!(
                "a {width} by {height} picture has {wanted} pixels, not {}",
                argb.len()
            ));
        }
        Ok(Pixels {
            width,
            height,
            argb,
        })
    }

    pub fn width(&self) -> u32 {
        self.width
    }

    pub fn height(&self) -> u32 {
        self.height
    }

    /// The pixel at column `x`, row `y` (0 at the top).
    pub fn get(&self, x: u32, y: u32) -> u32 {
        self.argb[(y * self.width + x) as usize]
    }

    /// Every pixel, row by row.
    pub fn argb(&self) -> &[u32] {
        &self.argb
    }

    /// Whether an image display draws `pixel` (its alpha is at least `OPAQUE_FROM`).
    pub fn opaque(pixel: u32) -> bool {
        pixel >> 24 >= OPAQUE_FROM
    }

    /// This picture `to_width` by `to_height`: each new pixel the average of
    /// the old ones it covers, weighted by how much of each it covers and by
    /// their alpha (so a transparent pixel's colour never bleeds into its
    /// neighbours). Smaller is smooth; larger is each old pixel spread over
    /// the new ones it covers.
    pub fn resized(&self, to_width: u32, to_height: u32) -> Pixels {
        if to_width == self.width && to_height == self.height {
            return self.clone();
        }
        let (w, h) = (self.width as usize, self.height as usize);
        let (tw, th) = (to_width as usize, to_height as usize);
        // Premultiplied channels, resized across, then down.
        let mut channels = [
            vec![0.0; w * h],
            vec![0.0; w * h],
            vec![0.0; w * h],
            vec![0.0; w * h],
        ];
        for (i, &p) in self.argb.iter().enumerate() {
            let alpha = f64::from(p >> 24 & 0xFF) / 255.0;
            channels[0][i] = alpha;
            channels[1][i] = f64::from(p >> 16 & 0xFF) * alpha;
            channels[2][i] = f64::from(p >> 8 & 0xFF) * alpha;
            channels[3][i] = f64::from(p & 0xFF) * alpha;
        }
        let down = channels.map(|c| {
            let across = resample(&c, w, h, tw, true);
            resample(&across, tw, h, th, false)
        });
        let argb = (0..tw * th)
            .map(|i| {
                let alpha = down[0][i];
                if alpha <= 0.0 {
                    return 0;
                }
                let byte = |v: f64| v.round_ties_even().clamp(0.0, 255.0) as u32;
                let channel = |v: f64| byte(v / alpha);
                byte(alpha * 255.0) << 24
                    | channel(down[1][i]) << 16
                    | channel(down[2][i]) << 8
                    | channel(down[3][i])
            })
            .collect();
        Pixels {
            width: to_width,
            height: to_height,
            argb,
        }
    }
}

/// Rows (or columns, `across`) of `values`, `w` by `h`, resampled to `to` along that axis by area.
fn resample(values: &[f64], w: usize, h: usize, to: usize, across: bool) -> Vec<f64> {
    let from = if across { w } else { h };
    let mut out = vec![0.0; if across { to * h } else { w * to }];
    let scale = from as f64 / to as f64;
    for n in 0..to {
        let start = n as f64 * scale;
        let end = start + scale;
        let mut k = start as usize;
        while (k as f64) < end && k < from {
            let weight = (end.min(k as f64 + 1.0) - start.max(k as f64)) / scale;
            if across {
                for y in 0..h {
                    out[y * to + n] += values[y * w + k] * weight;
                }
            } else {
                for x in 0..w {
                    out[n * w + x] += values[k * w + x] * weight;
                }
            }
            k += 1;
        }
    }
    out
}
