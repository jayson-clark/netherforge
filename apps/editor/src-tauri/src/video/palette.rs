//! Map colours are format's (`netherforge_format::map_colors`): the same
//! palette, matching and dithering the server draws pictures with, so a video
//! the editor converts and a picture the server draws come out alike. The
//! colours themselves are the game's (the dev server's export, `mapColors` in
//! the cached game data): nothing here knows one. This is only the converter's
//! side of it: frames as ffmpeg hands them over.

pub use netherforge_format::map_colors::{Dither, Palette, TRANSPARENT};

/// A dither as `video.json` spells it; Floyd–Steinberg when it says nothing, none for a name there isn't.
pub fn parse_dither(text: Option<&str>) -> Option<Dither> {
    match text {
        None => Some(Dither::default()),
        Some(name) => serde_json::from_value(serde_json::Value::String(name.into())).ok(),
    }
}

/// An RGBA frame (4 bytes a pixel, `width` by `height`) as map colour indices: a pixel less than half opaque is
/// `TRANSPARENT`, which a map draws as nothing.
pub fn dither(
    palette: &mut Palette,
    rgba: &[u8],
    width: usize,
    height: usize,
    dither: Dither,
) -> Vec<u8> {
    let argb = |i: usize| {
        let px = &rgba[i * 4..i * 4 + 4];
        u32::from_be_bytes([px[3], px[0], px[1], px[2]])
    };
    dither
        .apply(palette, width, height, argb)
        .into_iter()
        .map(|i| i.unwrap_or(TRANSPARENT))
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_frame_is_dithered_by_formats_rules_with_clear_pixels_transparent() {
        let mut p = Palette::new(&[0, 0, 0, 0, 0xFF00_0000, 0xFFFF_FFFF, 0xFFFF_0000]);
        let pixels = [255, 0, 0, 255, 255, 0, 0, 10];
        for d in [Dither::None, Dither::Ordered, Dither::FloydSteinberg] {
            assert_eq!(dither(&mut p, &pixels, 2, 1, d), vec![6, 0], "{d:?}");
        }
        assert_eq!(parse_dither(None), Some(Dither::FloydSteinberg));
        assert_eq!(parse_dither(Some("ordered")), Some(Dither::Ordered));
        assert_eq!(parse_dither(Some("bayer")), None);
    }
}
