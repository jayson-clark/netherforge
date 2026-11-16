//! Format's `ScreenTest` palette and dither cases, and the editor's converter's.

use netherforge_format::map_colors::{Dither, Palette, TRANSPARENT, distance, parse};

/// A made-up palette (no game's): transparent, then black, white, red, grey.
fn palette() -> Palette {
    Palette::new(&[
        0,
        0,
        0,
        0,
        0xFF00_0000,
        0xFFFF_FFFF,
        0xFFFF_0000,
        0xFF80_8080,
    ])
}

#[test]
fn colours_match_the_nearest_drawable_one() {
    let mut p = palette();
    assert_eq!(p.nearest(0x10_1010), 4);
    assert_eq!(p.nearest(0xF0_F0F0), 5);
    assert_eq!(p.nearest(0xE0_2020), 6);
    assert_eq!(p.nearest(0x7A_7A7A), 7);
    assert_eq!(p.rgb(0), None, "the game's no colour");
    assert_eq!(p.rgb(6), Some(0xFF_0000));
    assert!(Palette::new(&[]).is_empty());
    assert_eq!(Palette::new(&[]).nearest(0x123456), TRANSPARENT);
    assert_eq!(distance(0, 0, 0, 255, 255, 255), 584_970);
    assert_eq!(distance(255, 0, 0, 0, 0, 255), 324_870);
    assert_eq!(parse("#ff8800"), Some(0xFF8800));
    assert_eq!(parse("red"), None);
    assert_eq!(parse("#ff880"), None);
}

#[test]
fn dithering_leaves_whats_less_than_half_opaque_and_spreads_a_grey() {
    let mut p = palette();
    let pixels = [0xFFFF_0000u32, 0x10FF_0000];
    for dither in [Dither::None, Dither::FloydSteinberg, Dither::Ordered] {
        assert_eq!(
            dither.apply(&mut p, 2, 1, |i| pixels[i]),
            [Some(6), None],
            "{dither:?}"
        );
    }
    let grey = |_| 0xFF40_4040;
    let spread = Dither::FloydSteinberg.apply(&mut p, 8, 8, grey);
    // Half way from black to the palette's grey: about half of each.
    assert!(
        spread.iter().all(|i| *i == Some(4) || *i == Some(7)),
        "{spread:?}"
    );
    let greys = spread.iter().filter(|i| **i == Some(7)).count();
    assert!((24..=40).contains(&greys), "{spread:?}");
    assert!(
        Dither::None
            .apply(&mut p, 8, 8, grey)
            .iter()
            .all(|i| *i == Some(7))
    );
}

#[test]
fn floyd_steinberg_spreads_a_grey_into_black_and_white() {
    let mut p = Palette::new(&[0, 0, 0, 0, 0xFF00_0000, 0xFFFF_FFFF, 0xFFFF_0000]);
    let out = Dither::FloydSteinberg.apply(&mut p, 8, 8, |_| 0xFF80_8080);
    let white = out.iter().filter(|i| **i == Some(5)).count();
    // About half each, as the eye averages it.
    assert!((24..=40).contains(&white), "{white} white of 64");
    assert!(out.iter().all(|i| *i == Some(4) || *i == Some(5)));
}

#[test]
fn a_palette_hashes_its_colours() {
    assert_eq!(palette().hash(), palette().hash());
    assert_ne!(palette().hash(), Palette::new(&[]).hash());
    assert_eq!(Dither::default(), Dither::FloydSteinberg);
    assert_eq!(
        [Dither::None, Dither::FloydSteinberg, Dither::Ordered].map(Dither::code),
        [0, 1, 2]
    );
}
