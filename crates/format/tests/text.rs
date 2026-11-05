//! Format's `MiniMessagePassTest`, `TextMetricsTest` and `TextWidthTest`.

use netherforge_format::font::DefaultFontFile;
use netherforge_format::kind::centity::Billboard;
use netherforge_format::kind::resource_pack::{MAX_OFFSET, fonts};
use netherforge_format::text::minimessage::{Piece, TextStyle, named_color, pieces};
use netherforge_format::text::space::{Align, At};
use netherforge_format::text::{TextMetrics, TextWidth, glyph};

// ---- The MiniMessage pass ----

fn chars(text: &str) -> Vec<(String, usize, u32, TextStyle)> {
    pieces(text)
        .into_iter()
        .filter_map(|p| match p {
            Piece::Char {
                code,
                text,
                at,
                style,
                ..
            } => Some((text, at, code, style)),
            _ => None,
        })
        .collect()
}

fn style_of(text: &str) -> TextStyle {
    chars(text).remove(0).3
}

fn color(c: &str) -> TextStyle {
    TextStyle {
        color: Some(c.into()),
        ..TextStyle::default()
    }
}

fn red() -> String {
    named_color("red").unwrap().into()
}

fn kinds(text: &str) -> Vec<&'static str> {
    pieces(text)
        .iter()
        .map(|p| match p {
            Piece::Char { .. } => "Char",
            Piece::Glyph { .. } => "Glyph",
            Piece::Break { .. } => "Break",
            Piece::Move { .. } => "Move",
            Piece::Overlay { .. } => "Overlay",
            Piece::OverlayEnd { .. } => "OverlayEnd",
        })
        .collect()
}

#[test]
fn styles_characters_and_keeps_their_source_positions() {
    let c = chars("<red>a<bold>b</bold></red>c");
    let placed: Vec<(&str, usize)> = c.iter().map(|(t, at, _, _)| (t.as_str(), *at)).collect();
    assert_eq!(placed, [("a", 5), ("b", 12), ("c", 26)]);
    assert_eq!(c[0].3, color(&red()));
    assert_eq!(
        c[1].3,
        TextStyle {
            bold: Some(true),
            ..color(&red())
        }
    );
    assert_eq!(c[2].3, TextStyle::default());
    // A character past the BMP is one piece, two UTF-16 units long.
    let clef = chars("𝄞x");
    let placed: Vec<(&str, usize)> = clef.iter().map(|(t, at, _, _)| (t.as_str(), *at)).collect();
    assert_eq!(placed, [("𝄞", 0), ("x", 2)]);
    assert_eq!(clef[0].2, 0x1D11E);
}

#[test]
fn reads_colours_hex_decorations_negation_and_reset() {
    assert_eq!(style_of("<#12AB34>x"), color("#12ab34"));
    assert_eq!(style_of("<color:gold>x"), color("#ffaa00"));
    assert_eq!(style_of("<GOLD>x"), color("#ffaa00"));
    assert_eq!(style_of("<italic><!italic>x").italic, Some(false));
    assert_eq!(style_of("<bold:false>x").bold, Some(false));
    assert_eq!(style_of("<gradient:#ff0000:#00ff00>x"), color("#ff0000"));
    assert_eq!(style_of("<rainbow>x"), color(&red()));
    assert_eq!(style_of("<red><bold><reset>x"), TextStyle::default());
    // Unknown tags draw nothing and change nothing, but balance.
    assert_eq!(
        style_of("<bold><hover:show_text:hi></hover></bold>x"),
        TextStyle::default()
    );
    let text: String = chars("a<hover:show_text:hi>b</hover>c")
        .into_iter()
        .map(|c| c.0)
        .collect();
    assert_eq!(text, "abc");
}

#[test]
fn breaks_lines_at_newlines_and_their_tags() {
    let all = pieces("a\n<newline>b<br>");
    let breaks: Vec<(usize, usize)> = all
        .iter()
        .filter_map(|p| match p {
            Piece::Break { at, end } => Some((*at, *end)),
            _ => None,
        })
        .collect();
    assert_eq!(breaks, [(1, 2), (2, 11), (12, 16)]);
    let text: String = chars("a\n<newline>b<br>")
        .into_iter()
        .map(|c| c.0)
        .collect();
    assert_eq!(text, "ab");
}

#[test]
fn reads_glyph_tags_as_one_picture_that_opens_nothing() {
    let all = pieces("<gray><glyph:'shop:ui/coin'> 2</gray></glyph><bold>x</bold>");
    let Piece::Glyph {
        reference,
        at,
        end,
        style,
    } = &all[0]
    else {
        panic!("a glyph first: {all:?}")
    };
    assert_eq!(reference, "shop:ui/coin");
    assert_eq!((*at, *end), (6, 28));
    assert_eq!(*style, color(named_color("gray").unwrap()));
    // The </gray> after it still closes the grey, and a stray </glyph> closes nothing.
    let last = chars("<gray><glyph:ui/coin> 2</gray></glyph><bold>x</bold>")
        .pop()
        .unwrap();
    assert_eq!(
        last.3,
        TextStyle {
            bold: Some(true),
            ..TextStyle::default()
        }
    );
}

#[test]
fn bold_is_what_the_measure_counts() {
    let metrics = TextMetrics::new(|_| Some(6));
    // Bold costs a pixel a character; turned off, it doesn't.
    assert_eq!(metrics.line_widths("<b>ab</b>", 200), [14]);
    assert_eq!(metrics.line_widths("<b><!b>ab</b>", 200), [12]);
    assert_eq!(metrics.line_widths("<bold:false>ab", 200), [12]);
}

#[test]
fn reads_spaces_as_moves_and_ats_as_overlays() {
    let all = pieces("<red><at:-4:align=right>ab</red>c<space:-3>");
    let Piece::Overlay { spec, .. } = &all[0] else {
        panic!("an overlay first: {all:?}")
    };
    assert_eq!(
        *spec,
        At {
            align: Align::Right,
            ..At::new(-4)
        }
    );
    // What's inside keeps the colour around it, and the </red> that closes the at ends the overlay.
    let Piece::Char { style, .. } = &all[1] else {
        panic!()
    };
    assert_eq!(*style, color(&red()));
    assert_eq!(
        kinds("<red><at:-4:align=right>ab</red>c<space:-3>"),
        ["Overlay", "Char", "Char", "OverlayEnd", "Char", "Move"]
    );
    assert!(matches!(all.last(), Some(Piece::Move { px: -3, .. })));
    // Never closed, it ends with the text; one that doesn't read only scopes.
    assert_eq!(kinds("<at:1>a").last(), Some(&"OverlayEnd"));
    assert_eq!(kinds("<at:x>a</at>"), ["Char"]);
}

#[test]
fn the_layout_measures_moves_and_not_overlays() {
    let metrics = TextMetrics::new(|_| Some(6));
    assert_eq!(metrics.line_widths("a<space:40>b", 200), [6 + 40 + 6]);
    assert_eq!(metrics.line_widths("<at:-30>xyz</at>ab", 200), [12]);
    assert_eq!(
        metrics.line_widths(&format!("a{}", fonts::offset(9)), 200),
        [6 + 9]
    );
}

// ---- The editor's layout ----

/// A tiny font: every glyph 6 wide, `i` 2, space 4.
fn tiny(code: u32) -> Option<i32> {
    Some(match char::from_u32(code) {
        Some('i') => 2,
        Some(' ') => 4,
        _ => 6,
    })
}

fn coin(reference: &str) -> Option<i32> {
    (reference == "ui:coin").then_some(9)
}

#[test]
fn tags_measure_as_nothing_except_bold() {
    let metrics = TextMetrics::new(tiny);
    assert_eq!(metrics.line_widths("<gold>ab</gold>", 200), [12]);
    assert_eq!(metrics.line_widths("<bold>ab</bold>", 200), [14]);
    // 2 + bold 3 + 2 + 6: an unknown tag balances without turning bold on.
    assert_eq!(
        metrics.line_widths("<red>i</red><b>i</b>i<unknown>a", 200),
        [13]
    );
}

#[test]
fn glyph_tags_measure_as_the_glyphs_advance() {
    let metrics = TextMetrics::new(|_| Some(6)).with_glyphs(coin);
    assert_eq!(metrics.line_widths("<glyph:ui:coin> 2", 200), [9 + 6 + 6]);
    assert_eq!(metrics.line_widths("<glyph:'ui:coin'>", 200), [9]);
    // A glyph no pack has draws nothing; a glyph is never bold; the tag opens nothing to close.
    assert_eq!(metrics.line_widths("<glyph:ui:nope>a", 200), [6]);
    assert_eq!(metrics.line_widths("<b><glyph:ui:coin>a</b>", 200), [9 + 7]);
    // Where it is in the source, so a preview draws it on the right line.
    let lines = metrics.lines("aa <glyph:ui:coin>", 20);
    assert_eq!(lines.iter().map(|l| l.width).collect::<Vec<_>>(), [12, 9]);
    assert_eq!(lines[1].start, 3);
}

#[test]
fn glyph_tags_follow_minimessage_arguments() {
    assert_eq!(
        glyph::reference("glyph:ui:coin").as_deref(),
        Some("ui:coin")
    );
    assert_eq!(
        glyph::reference("glyph:\"ui:coin\"").as_deref(),
        Some("ui:coin")
    );
    assert_eq!(glyph::reference("glyph").as_deref(), Some(""));
    assert_eq!(glyph::reference("gold"), None);
    // An escaped tag is text.
    assert_eq!(
        glyph::find("<gray>\\<glyph:a:b><glyph:ui:coin>"),
        [glyph::Glyph {
            reference: "ui:coin".into(),
            start: 18,
            end: 33
        }]
    );
    let found: Vec<String> = glyph::find("<glyph:a:b> and \\<glyph:c:d>")
        .into_iter()
        .map(|g| g.reference)
        .collect();
    assert_eq!(found, ["a:b"]);
}

#[test]
fn breaks_at_newlines_and_tags() {
    assert_eq!(
        TextMetrics::new(tiny).line_widths("a\nb<newline>c", 200),
        [6, 6, 6]
    );
}

#[test]
fn wraps_at_the_last_space_then_mid_word() {
    let metrics = TextMetrics::new(tiny);
    // "aa aa" is 6+6+4+6+6 = 28: at 20 it breaks at the space.
    assert_eq!(metrics.line_widths("aa aa", 20), [12, 12]);
    // One long word breaks where it overflows.
    assert_eq!(metrics.line_widths("aaaaa", 20), [18, 12]);
}

#[test]
fn lines_say_which_part_of_the_source_they_show() {
    let metrics = TextMetrics::new(tiny);
    let shown = |text: &str, width: i32| -> Vec<(String, i32)> {
        let units: Vec<u16> = text.encode_utf16().collect();
        metrics
            .lines(text, width)
            .into_iter()
            .map(|l| (String::from_utf16_lossy(&units[l.start..l.end]), l.width))
            .collect()
    };
    let pairs = |p: &[(&str, i32)]| -> Vec<(String, i32)> {
        p.iter().map(|(s, w)| (s.to_string(), *w)).collect()
    };
    assert_eq!(
        shown("<red>aa aa</red>", 20),
        pairs(&[("<red>aa", 12), ("aa</red>", 12)])
    );
    assert_eq!(shown("aaaaa", 20), pairs(&[("aaa", 18), ("aa", 12)]));
    assert_eq!(shown("a\n<br>b", 0), pairs(&[("a", 6), ("", 0), ("b", 6)]));
    assert_eq!(shown("", 200), pairs(&[("", 0)]));
    // Past the BMP, positions are UTF-16 units, as the editor counts.
    assert_eq!(shown("𝄞 a", 10), pairs(&[("𝄞", 6), ("a", 6)]));
}

#[test]
fn box_only_for_fixed_text() {
    let metrics = TextMetrics::new(tiny);
    assert_eq!(metrics.display_box("hi", None, None), None);
    let b = metrics
        .display_box("ab", Some(Billboard::Fixed), None)
        .unwrap();
    assert!((b.min.0 + 0.15).abs() < 1e-9);
    assert!((b.max.0 - 0.175).abs() < 1e-9);
    assert!((b.max.1 - 0.25).abs() < 1e-9);
}

// ---- The server's measure ----

/// A made-up font: `a`–`z` 6 wide, `i` 2, space 4, `<` and `>` 5, `\` and `/` 6. Nothing else.
fn font() -> DefaultFontFile {
    let mut advances = serde_json::Map::new();
    for c in 'a'..='z' {
        advances.insert((c as u32).to_string(), 6.into());
    }
    for (c, w) in [('i', 2), (' ', 4), ('<', 5), ('>', 5), ('\\', 6), ('/', 6)] {
        advances.insert((c as u32).to_string(), w.into());
    }
    serde_json::from_value(serde_json::json!({ "minecraft": "26.3", "advances": advances }))
        .unwrap()
}

fn measure(text: &str) -> Option<i32> {
    let font = font();
    TextWidth::of_font(&font).with_glyphs(coin).measure(text)
}

fn layout(text: &str) -> (String, Vec<String>) {
    let font = font();
    let width = TextWidth::of_font(&font).with_glyphs(coin);
    let out = width.layout(text);
    (out.text.into_owned(), out.unplaced)
}

#[test]
fn a_glyphs_bare_character_measures_as_the_glyph() {
    let font = font();
    // `nf.text.glyph`'s character, which the text's colour tints, measures as its tag does.
    let glyphs = TextWidth::of_font(&font)
        .with_glyph_chars(|c| (c == 0xE000).then_some(9))
        .with_glyphs(coin);
    assert_eq!(
        glyphs.measure("<glyph:ui:coin>"),
        glyphs.measure("<#ff0000>\u{E000}")
    );
    assert_eq!(glyphs.measure("\u{E000}a"), Some(9 + 6));
    // Bold like any character, as the game draws it; a character no glyph has still can't be measured.
    assert_eq!(glyphs.measure("<b>\u{E000}</b>"), Some(10));
    assert_eq!(glyphs.measure("\u{E001}"), None);
    // Without the lookup it's a character the font doesn't cover.
    assert_eq!(measure("\u{E000}"), None);
}

#[test]
fn plain_text_is_the_sum_of_its_advances() {
    assert_eq!(measure(""), Some(0));
    assert_eq!(measure("ai b"), Some(6 + 2 + 4 + 6));
}

#[test]
fn style_tags_draw_nothing_and_bold_adds_a_pixel_per_character() {
    assert_eq!(measure("<gold>ab</gold>"), Some(12));
    assert_eq!(measure("<#ff8800><italic>ab"), Some(12));
    assert_eq!(measure("<bold>ab</bold>"), Some(14));
    assert_eq!(measure("<b>ab</b>a"), Some(14 + 6));
    // Bold stops at its closing tag, at a negation, at <reset>, and when an enclosing tag closes.
    assert_eq!(measure("<bold:true>a<bold:false>a"), Some(7 + 6));
    assert_eq!(measure("<b>a<!b>a"), Some(7 + 6));
    assert_eq!(measure("<b><red>a<reset>a"), Some(7 + 6));
    assert_eq!(measure("<red><b>a</red>a"), Some(7 + 6));
    // A space is a character like any other.
    assert_eq!(measure("<b> </b>"), Some(5));
}

/// Where the server's exact measure and the editor's layout part ways, on
/// purpose: what the server can't know is `None`, what the preview can't know
/// it estimates or draws as the game would. Where both know everything they
/// agree.
#[test]
fn the_servers_measure_is_exact_where_the_preview_estimates() {
    let font = font();
    let preview = TextMetrics::new(|c| font.advance(c)).with_glyphs(coin);
    for text in [
        "ai b",
        "<b>ab</b>a",
        "<glyph:ui:coin> a",
        "<red>a<newline>bb",
    ] {
        let widest = preview.line_widths(text, 0).into_iter().max();
        assert_eq!(widest, measure(text), "{text}");
    }
    // A glyph no pack has: the game draws nothing, but the server can't promise what it isn't told.
    assert_eq!(preview.line_widths("<glyph:ui:nope>a", 200), [6]);
    assert_eq!(measure("<glyph:ui:nope>a"), None);
    // A character the font doesn't cover: estimated for the preview, unknown to the server.
    assert_eq!(preview.line_widths("Z", 200), [6]);
    assert_eq!(measure("Z"), None);
    // A tag MiniMessage doesn't know is drawn as written; the preview's pass reads it as a tag.
    assert_eq!(measure("<nope>a"), Some(5 + 6 * 4 + 5 + 6));
    assert_eq!(preview.line_widths("<nope>a", 200), [6]);
}

#[test]
fn glyph_tags_are_their_glyphs_advance() {
    assert_eq!(measure("<glyph:ui:coin> a"), Some(9 + 4 + 6));
    // A glyph is never drawn bold.
    assert_eq!(measure("<b><glyph:'ui:coin'>"), Some(9));
    // A glyph whose advance isn't known can't be measured.
    assert_eq!(measure("<glyph:ui:nope>"), None);
}

#[test]
fn anything_not_exactly_known_is_none() {
    // A character the font file doesn't cover (here: capitals; really: unifont's).
    assert_eq!(measure("Hi"), None);
    // Text only the client resolves.
    assert_eq!(measure("<lang:block.minecraft.stone>"), None);
    assert_eq!(measure("<key:key.jump>"), None);
    // Another font.
    assert_eq!(measure("<font:uniform>a</font>"), None);
    assert_eq!(measure("<font:minecraft:default>a</font>"), Some(6));
}

#[test]
fn lines_measure_as_the_widest_one() {
    assert_eq!(measure("a\naa<newline>a<br>i"), Some(12));
    let font = font();
    assert_eq!(
        TextWidth::of_font(&font).line_count("a\naa<newline>a<br>i"),
        4
    );
}

#[test]
fn what_minimessage_doesnt_parse_is_text() {
    // An escaped tag, and a tag MiniMessage doesn't know, are drawn as written.
    assert_eq!(measure("\\<a>"), Some(5 + 6 + 5));
    assert_eq!(measure("a\\\\i"), Some(6 + 6 + 2));
    assert_eq!(measure("<foo>"), Some(5 + 6 + 6 + 6 + 5));
    // Not a tag at all.
    assert_eq!(measure("< a"), Some(5 + 4 + 6));
}

#[test]
fn a_space_moves_by_its_pixels_bold_or_not() {
    assert_eq!(measure("a<space:40>a"), Some(6 + 40 + 6));
    assert_eq!(
        measure("<glyph:ui:coin><space:-3><glyph:ui:coin>"),
        Some(9 - 3 + 9)
    );
    assert_eq!(measure("<b>a<space:10></b>"), Some(7 + 10));
    // Held to the largest move; one that doesn't read moves nothing.
    assert_eq!(measure("<space:99999>"), Some(MAX_OFFSET));
    assert_eq!(measure("a<space:x>"), Some(6));
    // The pack's space characters, written by hand (nf.text.space), are the moves they make.
    assert_eq!(measure(&format!("a{}", fonts::offset(37))), Some(6 + 37));
}

#[test]
fn what_an_at_holds_takes_no_width() {
    assert_eq!(measure("<at:-30><glyph:ui:coin></at>abcd"), Some(6 * 4));
    assert_eq!(measure("<at:5>bbb</at>a"), Some(6));
    // Nested, or closed by what encloses it, or never closed.
    assert_eq!(measure("<at:1>b<at:2>b</at>b</at>a"), Some(6));
    assert_eq!(measure("<red><at:1>bb</red>a"), Some(6));
    assert_eq!(measure("a<at:1>bb"), Some(6));
    // One that doesn't read only scopes what's inside it, which then takes its width.
    assert_eq!(measure("<at:what>bb</at>a"), Some(18));
    // Unmeasurable inside is unmeasurable.
    assert_eq!(measure("<at:1>\u{e9}</at>a"), None);
}

#[test]
fn an_at_becomes_the_moves_around_it() {
    // From the cursor: there, and back by the inside's width.
    assert_eq!(
        layout("<at:-30><glyph:ui:coin></at>King").0,
        "<space:-30><at:-30><glyph:ui:coin><space:21></at>King"
    );
    // 3 left of the line's centre (half of 4 letters = 12), its right edge there.
    assert_eq!(
        layout("ab<at:-3:from=center:align=right><glyph:ui:coin></at>cd").0,
        "ab<space:-12><at:-3:from=center:align=right><glyph:ui:coin><space:3></at>cd"
    );
    // From the end and the start, centred on N.
    assert_eq!(
        layout("a<at:0:from=end:align=center>bb</at>").0,
        "a<space:-6><at:0:from=end:align=center>bb<space:-6></at>"
    );
    assert_eq!(
        layout("aa<at:0:from=start>b</at>").0,
        "aa<space:-12><at:0:from=start>b<space:6></at>"
    );
    // Bold inside counts; past the largest move takes more than one space.
    assert_eq!(
        layout("<b><at:1>a</at></b>").0,
        "<b><space:1><at:1>a<space:-8></at></b>"
    );
    assert_eq!(
        layout("<at:3000>a</at>").0,
        "<space:2047><space:953><at:3000>a<space:-2047><space:-959></at>"
    );
    // Closed by what encloses it: the move back goes where that closes it.
    assert_eq!(
        layout("<red><at:4>a</red>z").0,
        "<red><space:4><at:4>a<space:-10></red>z"
    );
    // Text without one is as it was.
    let font = font();
    let width = TextWidth::of_font(&font);
    assert!(matches!(
        width.layout("<red>abc").text,
        std::borrow::Cow::Borrowed(_)
    ));
}

#[test]
fn an_at_that_cant_be_placed_is_left_to_draw_in_line() {
    let (text, unplaced) = layout("<at:4><glyph:ui:missing></at>a");
    assert_eq!(text, "<at:4><glyph:ui:missing></at>a");
    assert_eq!(unplaced, ["<at:4>"]);
    // From the centre of a line that can't be measured; from the cursor it still works.
    assert_eq!(
        layout("<at:0:from=center>a</at><lang:block.stone>").1,
        ["<at:0:from=center>"]
    );
    assert_eq!(
        layout("<at:3>a</at><lang:x>").0,
        "<space:3><at:3>a<space:-9></at><lang:x>"
    );
    // Spread over two lines.
    assert_eq!(layout("<at:1>a<br>b</at>").1, ["<at:1>"]);
}
