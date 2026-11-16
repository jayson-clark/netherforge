//! Format's `DisplayGlyphsTest`, and `Pixels`' resizing.

use netherforge_format::image::Pixels;
use netherforge_format::kind::centity::Anchor;
use netherforge_format::kind::resource_pack::display::{
    self, DisplayBox, RECT_BOX, fit, image_box, image_runs, image_text, run_char, text_box,
};
use netherforge_format::kind::resource_pack::fonts::offset;
use netherforge_format::math::{Matrix4, Vec3};
use netherforge_format::text::metrics::BLOCKS_PER_PIXEL as P;

fn back() -> String {
    offset(-1)
}

fn close(expected: (f64, f64), m: &Matrix4, x: f64, y: f64) {
    let actual = m.transform_position(Vec3(x, y, 0.0));
    assert!((expected.0 - actual.0).abs() < 1e-9, "x of {actual:?}");
    assert!((expected.1 - actual.1).abs() < 1e-9, "y of {actual:?}");
}

fn near(expected: f64, actual: f64) {
    assert!((expected - actual).abs() < 1e-12, "{expected} vs {actual}");
}

#[test]
fn a_run_is_a_glyph_per_set_bit_each_followed_by_a_step_back() {
    let red = Pixels::new(5, 1, vec![0xFFFF0000; 5]).unwrap();
    let glyphs = format!("{}{}{}{}", run_char(2), back(), run_char(0), back());
    assert_eq!(
        image_text(5, 1, Some(&red)),
        format!("<color:#ff0000>{glyphs}</color>")
    );
}

#[test]
fn runs_longer_than_the_biggest_glyph_repeat_it() {
    let white = Pixels::new(259, 1, vec![u32::MAX; 259]).unwrap();
    let big = format!("{}{}", run_char(7), back());
    assert_eq!(
        image_text(259, 1, Some(&white)),
        format!(
            "<color:#ffffff>{big}{big}{}{}{}{}</color>",
            run_char(1),
            back(),
            run_char(0),
            back()
        )
    );
}

#[test]
fn an_images_runs_say_what_its_text_says_and_each_is_short() {
    // A photo: hardly two pixels alike, so its text runs to hundreds of kilobytes, too long to be one
    // piece of the game's text (65535 bytes). Its runs are what's drawn: every one short.
    let mut seed: i32 = 7;
    let noise: Vec<u32> = (0..128 * 72)
        .map(|_| {
            seed = seed.wrapping_mul(1103515245).wrapping_add(12345);
            0xFF000000 | ((seed as u32) >> 8 & 0xFFFFFF)
        })
        .collect();
    let photo = Pixels::new(128, 72, noise).unwrap();
    let runs = image_runs(128, 72, Some(&photo));
    let text = image_text(128, 72, Some(&photo));
    let written: String = runs
        .iter()
        .map(|run| match run.rgb {
            None => run.text.clone(),
            Some(rgb) => format!("<color:#{rgb:06x}>{}</color>", run.text),
        })
        .collect();
    assert_eq!(text, written);
    assert!(
        text.len() > 65535,
        "the whole text is past one piece's limit"
    );
    assert!(
        runs.iter().all(|r| r.text.len() < 1024),
        "every run is a short piece"
    );
    assert_eq!(runs.iter().filter(|r| r.text == "\n").count(), 71);
    // A blank image is moves alone.
    assert!(image_runs(3, 2, None).iter().all(|r| r.rgb.is_none()));
}

#[test]
fn transparent_pixels_and_a_blank_image_are_moves() {
    assert_eq!(
        image_text(3, 2, None),
        format!("{}\n{}", offset(30), offset(30))
    );
    // Below half alpha is left out; a smaller picture leaves the rest blank.
    let faint = Pixels::new(1, 1, vec![0x7FFFFFFF]).unwrap();
    assert_eq!(image_text(3, 1, Some(&faint)), offset(30));
}

#[test]
fn a_fit_puts_the_box_where_the_anchor_says() {
    let b = image_box(96, 54);
    // The text is 10 pixels a picture pixel each way: square.
    near(96.0 * 10.0 * P, b.width());
    near(54.0 * 10.0 * P, b.height());
    let m = fit(b, Anchor::TopLeft, Some(8.4), Some(4.8));
    close((0.0, 0.0), &m, b.min_x, b.max_y);
    close((8.4, -4.8), &m, b.max_x, b.min_y);
    let centred = fit(RECT_BOX, Anchor::Center, Some(2.0), Some(1.0));
    close((-1.0, -0.5), &centred, RECT_BOX.min_x, RECT_BOX.min_y);
    // Without a size, the box keeps its own.
    let text: DisplayBox = text_box(40, 2);
    let right = fit(text, Anchor::BottomRight, None, None);
    close((0.0, 0.0), &right, text.max_x, text.min_y);
    close((-1.0, 0.5), &right, text.min_x, text.max_y);
}

#[test]
fn the_boxes_follow_the_games_text_layout() {
    // A rect's background: one pixel left of its 8-pixel move to its end and a line tall, centred one pixel right.
    near(9.0 * P, RECT_BOX.width());
    near(-4.0 * P, RECT_BOX.min_x);
    assert_eq!(display::rect_text(), offset(8));
    // An image's pixels: from the first line's top, one pixel lower than the background's bottom.
    let image = image_box(1, 1);
    near(-P, image.min_y);
    near(9.0 * P, image.max_y);
    near(-4.0 * P, image.min_x);
    // Text's lines are where an image's rows are: the first line's top a pixel inside the background.
    let text = text_box(10, 1);
    near(image.max_y, text.max_y);
    near(image.min_y, text.min_y);
    assert_eq!(display::image_line_width(96), 970);
}

#[test]
fn a_picture_is_its_pixels_and_resizes_by_area() {
    assert!(Pixels::new(0, 1, vec![]).is_err());
    assert!(Pixels::new(2, 1, vec![0]).is_err());
    let p = Pixels::new(2, 1, vec![0xFFFF0000, 0x000000FF]).unwrap();
    // A transparent pixel's colour doesn't bleed: the average is red, at half alpha.
    let one = p.resized(1, 1);
    assert_eq!(one.argb(), [0x80FF0000]);
    // Larger spreads each pixel over the ones it covers.
    let wide = Pixels::new(1, 1, vec![0xFF123456]).unwrap().resized(3, 2);
    assert!(wide.argb().iter().all(|&c| c == 0xFF123456));
    assert_eq!(p.resized(2, 1), p);
}
