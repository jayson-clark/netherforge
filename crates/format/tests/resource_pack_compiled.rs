//! A pack built with its pictures known (what the examples' golden can't
//! show, since a test project has none): format's `PackFontsTest` and the
//! compiling and line-drawing cases of `HudTest`.

use std::collections::{BTreeMap, BTreeSet};

use netherforge_format::kind::resource_pack::fonts::{self, Picture};
use netherforge_format::kind::resource_pack::hud::{self, BarFill, CompiledHud, HudState};
use netherforge_format::project::{self, ImageInfo, ProjectSource};

const MANIFEST: &str = r#"{ "formatVersion": 1, "name": "Test", "namespace": "test", "version": "1.0.0", "minecraft": "26.3" }"#;

/// A project in memory, with its pictures' facts.
struct Source {
    files: BTreeSet<String>,
    texts: BTreeMap<String, String>,
    images: BTreeMap<String, ImageInfo>,
}

impl ProjectSource for Source {
    fn files(&self) -> &BTreeSet<String> {
        &self.files
    }

    fn read(&self, path: &str) -> Option<String> {
        self.texts.get(path).cloned()
    }

    fn reads_images(&self) -> bool {
        true
    }

    fn image(&self, path: &str) -> Option<ImageInfo> {
        self.images.get(path).cloned()
    }
}

fn image(width: u32, height: u32, opaque: u32) -> ImageInfo {
    ImageInfo {
        width,
        height,
        opaque_width: opaque,
        opaque_columns: None,
    }
}

/// How far `text` moves the cursor, its characters measured by `advances`.
fn measure(text: &str, advances: &BTreeMap<String, i32>) -> i32 {
    let moves: BTreeMap<String, i32> = fonts::advances().into_iter().collect();
    text.chars()
        .map(|c| {
            let c = c.to_string();
            moves
                .get(&c)
                .or(advances.get(&c))
                .copied()
                .unwrap_or_else(|| panic!("an unmeasured {c:?}"))
        })
        .sum()
}

fn cell_advances(picture: &Picture) -> BTreeMap<String, i32> {
    picture
        .cells
        .chars()
        .map(|c| c.to_string())
        .zip(picture.cell_advances.clone().unwrap())
        .collect()
}

#[test]
fn a_pictures_text_measures_as_its_advance_from_its_characters_alone() {
    let mut next = 1;
    let mut extra = || {
        next += 1;
        Some(fonts::char_at(next - 1))
    };
    // A banner too wide for a font sheet: cut in four, each piece its own character, with moves between.
    let wide = fonts::picture(
        fonts::char_at(0),
        Some(&image(936, 256, 936)),
        256,
        &mut extra,
    );
    assert_eq!(wide.cells.chars().count(), 4);
    assert_eq!(
        measure(&wide.text, &cell_advances(&wide)),
        wide.advance.unwrap()
    );
    // One that fits is one character, whose own advance is the picture's.
    let small = fonts::picture(fonts::char_at(9), Some(&image(16, 16, 14)), 8, &mut || None);
    assert_eq!(small.cell_advances, Some(vec![small.advance.unwrap()]));
    assert_eq!(
        measure(&small.text, &cell_advances(&small)),
        small.advance.unwrap()
    );
    // Unknown pixels: no advances at all.
    let unknown = fonts::picture(fonts::char_at(0), None, 8, &mut || None);
    assert_eq!((unknown.advance, unknown.cell_advances), (None, None));
}

#[test]
fn an_offset_is_its_moves_and_nothing_else() {
    let moves: BTreeMap<String, i32> = fonts::advances().into_iter().collect();
    for px in [-2047, -300, -1, 0, 1, 7, 1024, 2047] {
        assert_eq!(measure(&fonts::offset(px), &BTreeMap::new()), px);
    }
    assert_eq!(
        measure(&fonts::offset(5000), &BTreeMap::new()),
        fonts::MAX_OFFSET
    );
    assert_eq!(moves.len(), 22);
    assert_eq!(fonts::space_advance(0xF800 + 3), Some(-8));
    assert_eq!(fonts::bitmap_advance(7, 8, 8), 8);
}

const PACK: &str = r#"{
  "hud": {
    "hide": ["health", "food"],
    "bars": {
      "health": { "full": "hud/heart.png", "half": "hud/heart_half.png", "empty": "hud/heart_empty.png", "segments": 10, "x": -91, "y": 30 },
      "mana": { "full": "hud/mana.png", "empty": "hud/mana_empty.png", "segments": 5, "x": 10, "y": 30, "gap": 1, "reverse": true }
    },
    "icons": { "effects": { "icons": { "poison": "hud/poison.png", "regen": "hud/regen.png" }, "x": -91, "y": 42 } },
    "texts": { "mana_label": { "x": 50, "align": "center" } }
  }
}"#;

/// The pack's HUD, compiled with every picture 9 pixels tall (hearts 9 wide, the rest 7).
fn hud(pack: &str) -> CompiledHud {
    let textures = [
        "heart",
        "heart_half",
        "heart_empty",
        "mana",
        "mana_empty",
        "poison",
        "regen",
    ];
    let path = |t: &str| format!("resource_packs/ui/textures/hud/{t}.png");
    let mut files: BTreeSet<String> = textures.iter().map(|t| path(t)).collect();
    files.insert("netherforge.json".into());
    files.insert("resource_packs/ui/pack.json".into());
    let source = Source {
        files,
        texts: BTreeMap::from([
            ("netherforge.json".to_owned(), MANIFEST.to_owned()),
            ("resource_packs/ui/pack.json".to_owned(), pack.to_owned()),
        ]),
        images: textures
            .iter()
            .map(|t| {
                let w = if t.starts_with("heart") { 9 } else { 7 };
                (path(t), image(w, 9, w))
            })
            .collect(),
    };
    let loaded = project::load(&source, Default::default());
    // (Hiding the game's HUD warns that `client/vanilla.json` isn't there to say its sprites.)
    let errors: Vec<_> = loaded
        .problems
        .iter()
        .filter(|p| p.severity == netherforge_format::problem::Severity::Error)
        .collect();
    assert_eq!(errors, Vec::<&netherforge_format::problem::Problem>::new());
    loaded.compiled_resource_packs["ui"]
        .hud
        .clone()
        .expect("its HUD")
}

#[test]
fn a_hud_builds_its_own_font() {
    let hud = hud(PACK);
    assert_eq!(hud.font, "test:ui/hud");
    let heart = &hud.bars["health"].full;
    // 30 pixels above the screen's bottom, 9 tall: an ascent of 30 + 9 - 65.
    assert_eq!(
        (heart.ascent, heart.height, heart.advance),
        (-26, 9, Some(10))
    );
    assert_eq!(hud.pieces().len(), 7);
}

#[test]
fn a_line_moves_the_cursor_nowhere_so_every_element_is_where_it_says() {
    let hud = hud(PACK);
    let chars: BTreeMap<String, i32> = hud
        .pieces()
        .iter()
        .map(|p| (p.char.clone(), p.advance.unwrap()))
        .collect();
    let tags = regex::Regex::new("<[^>]+>").unwrap();
    let state = HudState {
        bars: BTreeMap::from([
            (
                "health".to_owned(),
                BarFill {
                    value: 13.0,
                    max: 20.0,
                    segments: None,
                },
            ),
            (
                "mana".to_owned(),
                BarFill {
                    value: 2.0,
                    max: 5.0,
                    segments: None,
                },
            ),
        ]),
        icons: BTreeMap::from([(
            "effects".to_owned(),
            vec!["regen".to_owned(), "poison".to_owned()],
        )]),
        ..Default::default()
    };
    let line = hud::compose(&hud, &state, &|_| None);
    assert_eq!(measure(&tags.replace_all(&line, ""), &chars), 0);
    assert!(line.contains("<font:test:ui/hud>") && line.contains("<!shadow>"));

    // Six and a half hearts of ten: six full, one half, three empty.
    let health = &hud.bars["health"];
    let pieces = hud::segments(health, 13.0, 20.0, health.def.segments);
    let count = |p: &hud::Piece| pieces.iter().filter(|q| std::ptr::eq(**q, p)).count();
    assert_eq!(
        [
            count(&health.full),
            count(health.half.as_ref().unwrap()),
            count(&health.empty)
        ],
        [6, 1, 3]
    );
    // A bar without a half picture rounds to whole segments, and a reversed one fills from the right.
    let mana = &hud.bars["mana"];
    let names = |v: f64| -> Vec<String> {
        hud::segments(mana, v, 5.0, 5)
            .iter()
            .map(|p| p.texture.clone())
            .collect()
    };
    let (e, f) = ("hud/mana_empty.png".to_owned(), "hud/mana.png".to_owned());
    assert_eq!(
        names(2.0),
        [e.clone(), e.clone(), e.clone(), f.clone(), f.clone()]
    );
    assert_eq!(names(-3.0), vec![e; 5]);
    assert_eq!(names(99.0), vec![f; 5]);

    // A line of text, measured, centred on its x; one that can't be measured isn't drawn.
    let text = HudState {
        texts: BTreeMap::from([("mana_label".to_owned(), "AB".to_owned())]),
        ..Default::default()
    };
    assert_eq!(hud::compose(&hud, &text, &|_| None), "");
    let drawn = hud::compose(&hud, &text, &|_| Some(12));
    assert!(drawn.contains("AB<reset>"), "{drawn}");
    let runs = regex::Regex::new("<font:test:ui/hud>(.*?)</font>").unwrap();
    let moves: Vec<i32> = runs
        .captures_iter(&drawn)
        .map(|c| measure(&c[1], &BTreeMap::new()))
        .collect();
    assert_eq!(moves, [50 - 6, -(50 - 6 + 12)]);
}

#[test]
fn aligned_bars_and_rows_are_placed_by_what_they_draw() {
    let aligned = PACK
        .replace(
            r#""x": -91, "y": 30 }"#,
            r#""x": -91, "y": 30, "align": "right" }"#,
        )
        .replace(
            r#""x": -91, "y": 42 }"#,
            r#""x": -91, "y": 42, "align": "center" }"#,
        );
    let hud = hud(&aligned);
    let moves: BTreeMap<String, i32> = fonts::advances().into_iter().collect();
    let runs = regex::Regex::new("<font:test:ui/hud>(.*?)</font>").unwrap();
    let lefts = |line: &str| -> Vec<i32> {
        runs.captures_iter(line)
            .map(|c| {
                c[1].chars()
                    .map(|ch| ch.to_string())
                    .take_while(|ch| moves.contains_key(ch))
                    .map(|ch| moves[&ch])
                    .sum()
            })
            .collect()
    };
    let health = &hud.bars["health"];
    let slot = health.slot().unwrap();
    // Four of the ten hearts, their right edge at -91; then the one icon, its middle at -91.
    let state = HudState {
        bars: BTreeMap::from([(
            "health".to_owned(),
            BarFill {
                value: 30.0,
                max: 40.0,
                segments: Some(4),
            },
        )]),
        icons: BTreeMap::from([("effects".to_owned(), vec!["poison".to_owned()])]),
        ..Default::default()
    };
    let line = hud::compose(&hud, &state, &|_| None);
    let poison = hud.icons["effects"].icons["poison"].advance.unwrap();
    assert_eq!(lefts(&line)[..2], [-91 - 4 * slot, -91 - poison / 2]);
    assert_eq!(hud::segments(health, 30.0, 40.0, 4).len(), 4);
    // Three of four full: six halves of eight.
    assert_eq!(
        hud::segments(health, 30.0, 40.0, 4)
            .iter()
            .filter(|p| std::ptr::eq(**p, &health.full))
            .count(),
        3
    );
    // Never more than the bar has, nor fewer than one.
    assert_eq!(hud::segments(health, 1.0, 2.0, 99).len(), 10);
    assert_eq!(hud::segments(health, 1.0, 2.0, 0).len(), 1);
}
