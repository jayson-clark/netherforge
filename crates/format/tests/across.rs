//! Checks across the project that no golden case reaches: migrations' numbers
//! and dialects, a carrier's states running out (which needs the game's
//! blocks), a stack's kind against its project item's.

use std::collections::{BTreeMap, BTreeSet};

use netherforge_format::game::{self, GameData};
use netherforge_format::project;

fn problems(files: &[(&str, &str)], game: Option<&dyn GameData>) -> Vec<String> {
    let names: BTreeSet<String> = files.iter().map(|(p, _)| p.to_string()).collect();
    let read = |p: &str| {
        files
            .iter()
            .find(|(q, _)| *q == p)
            .map(|(_, t)| t.to_string())
    };
    project::load(
        &project::Files {
            files: &names,
            read: &read,
        },
        game,
    )
    .problems
    .iter()
    .filter(|p| p.code.as_deref() != Some("project.no-manifest"))
    .map(|p| {
        format!(
            "{} {}{}: {}",
            p.code.as_deref().unwrap(),
            p.file,
            p.path
                .as_deref()
                .map(|a| format!(" {a}"))
                .unwrap_or_default(),
            p.message
        )
    })
    .collect()
}

#[test]
fn migrations_run_in_order_with_every_dialect() {
    assert_eq!(
        problems(
            &[
                ("migrations/001_init.sql", ""),
                ("migrations/001_again.sql", ""),
                ("migrations/003_late.sql", ""),
                ("migrations/postgres/003_late.sql", ""),
            ],
            None
        ),
        [
            "migration.duplicate migrations/001_init.sql: Migration 001 is also migrations/001_again.sql; the numbers must be unique",
            "migration.gap migrations/003_late.sql: Migration 003 follows 001: the numbers run 001, 002, 003 with none skipped",
        ]
    );
    assert_eq!(
        problems(
            &[
                ("migrations/001_init.sql", ""),
                ("migrations/002_more.sql", ""),
                ("migrations/postgres/002_more.sql", ""),
            ],
            None
        ),
        [
            "migration.dialects migrations/001_init.sql: Other migrations have a postgres version and this one hasn't: add migrations/postgres/001_init.sql, so every server runs the same steps"
        ]
    );
}

/// The game's tripwire, with two sides only: 2 × 2 × 2 × 2 × 2 = 32 states, 24 of them blocks'.
struct Game;

impl GameData for Game {
    fn minecraft_version(&self) -> &str {
        "26.3"
    }
    fn registry(&self, _: &str) -> Option<&BTreeSet<String>> {
        None
    }
    fn tag(&self, _: &str, _: &str) -> Option<&BTreeSet<String>> {
        None
    }
    fn item_durability(&self, _: &str) -> u32 {
        0
    }
    fn particle(&self, _: &str) -> Option<game::ParticleData> {
        None
    }
    fn block_properties(&self, id: &str) -> Option<BTreeMap<String, Vec<String>>> {
        let binary = || vec!["false".to_owned(), "true".to_owned()];
        (id == "minecraft:tripwire").then(|| {
            ["attached", "disarmed", "east", "north", "powered"]
                .into_iter()
                .map(|p| (p.to_owned(), binary()))
                .collect()
        })
    }
}

#[test]
fn a_carrier_holds_so_many_blocks() {
    let block = r#"{ "carrier": "tripwire", "hardness": 0, "rotation": "horizontal" }"#;
    let ids: Vec<String> = (0..7).map(|i| format!("blocks/b{i}/block.json")).collect();
    let files: Vec<(&str, &str)> = ids.iter().map(|p| (p.as_str(), block)).collect();
    let found: Vec<String> = problems(&files, Some(&Game))
        .into_iter()
        .filter(|p| p.starts_with("block.carriers"))
        .collect();
    assert_eq!(
        found,
        [
            "block.carriers blocks/b6/block.json $: The game has 24 tripwire states to hold blocks in, and the tripwire blocks up to this one take 28 (it turns, which takes a state for each of its 4 ways)"
        ]
    );
}

#[test]
fn a_stack_of_a_project_item_is_that_items_kind() {
    assert_eq!(
        problems(
            &[
                ("items/ruby/item.json", r#"{ "kind": "minecraft:emerald" }"#),
                (
                    "menus/shop/menu.json",
                    r#"{ "rows": 1, "slots": { "0": { "item": { "item": "ruby", "kind": "diamond" } } } }"#
                ),
            ],
            None
        ),
        [
            r#"item.kind-mismatch menus/shop/menu.json $.slots["0"].item.kind: Item "ruby" is a minecraft:emerald, not a minecraft:diamond: leave kind out"#
        ]
    );
}

/// A project whose pictures' sizes are known (as the CLI measures their headers).
struct Sized<'a> {
    files: BTreeSet<String>,
    texts: &'a [(&'a str, &'a str)],
    sizes: &'a [(&'a str, [u32; 2])],
}

impl project::ProjectSource for Sized<'_> {
    fn files(&self) -> &BTreeSet<String> {
        &self.files
    }
    fn read(&self, path: &str) -> Option<String> {
        self.texts
            .iter()
            .find(|(p, _)| *p == path)
            .map(|(_, t)| t.to_string())
    }
    fn reads_sizes(&self) -> bool {
        true
    }
    fn size(&self, path: &str) -> Option<[u32; 2]> {
        self.sizes.iter().find(|(p, _)| *p == path).map(|(_, s)| *s)
    }
}

fn sized(texts: &[(&str, &str)], pictures: &[(&str, [u32; 2])]) -> Vec<String> {
    let files = texts
        .iter()
        .map(|(p, _)| p.to_string())
        .chain(pictures.iter().map(|(p, _)| p.to_string()))
        .collect();
    let source = Sized {
        files,
        texts,
        sizes: pictures,
    };
    project::load(&source, None)
        .problems
        .iter()
        .filter(|p| !matches!(p.code.as_deref(), Some("project.no-manifest")))
        .map(|p| {
            format!(
                "{} {}: {}",
                p.code.as_deref().unwrap(),
                p.path.as_deref().unwrap_or(""),
                p.message
            )
        })
        .collect()
}

#[test]
fn a_picture_fits_a_fonts_sheet() {
    let pack = r#"{ "glyphs": { "tall": { "texture": "tall.png" }, "wide": { "texture": "wide.png", "height": 8 } } }"#;
    assert_eq!(
        sized(
            &[("resource_packs/ui/pack.json", pack)],
            &[
                ("resource_packs/ui/textures/tall.png", [16, 300]),
                ("resource_packs/ui/textures/wide.png", [257, 8]),
            ]
        ),
        [
            "resource_pack.picture-size $.glyphs.tall.texture: Glyph tall is 16×300 pixels; Minecraft draws a font picture over 256 pixels tall blank, so use one at most 256 tall",
            "resource_pack.picture-size $.glyphs.wide.texture: Glyph wide is 257×8 pixels; a picture over 256 pixels wide is drawn in equal pieces of 256 or less, and 257 doesn't cut into any. Pad it with transparent columns to 258 pixels wide",
        ]
    );
}

#[test]
fn a_bitmap_fonts_picture_cuts_into_its_grid() {
    let font = r#"{ "type": "bitmap", "file": "font.png", "chars": ["ab", "cd"] }"#;
    assert_eq!(
        sized(
            &[("screen_fonts/big/font.json", font)],
            &[("screen_fonts/big/font.png", [17, 16])]
        ),
        [
            "screen_font.grid $.chars: The picture is 17 by 16 pixels, which doesn't cut into 2 characters across and 2 rows"
        ]
    );
}

#[test]
fn a_package_exports_kinds_it_has() {
    let manifest = r#"{ "formatVersion": 1, "name": "Lib", "namespace": "lib", "version": "1.0.0", "minecraft": "26.3",
        "exports": { "items": ["gem", "ghost"], "migrations": ["001_init"], "things": ["x"] } }"#;
    let found: Vec<String> = problems(
        &[
            ("netherforge.json", manifest),
            ("items/gem/item.json", "{}"),
        ],
        None,
    )
    .into_iter()
    .filter(|p| p.starts_with("package."))
    .collect();
    assert_eq!(
        found,
        [
            "package.export-missing netherforge.json $.exports.items[1]: There's no item \"ghost\" (items/ghost) to export",
            "package.export-kind netherforge.json $.exports.migrations: \"migrations\" isn't a kind of resource: export from resource_packs, particles, cutscenes, screen_fonts, videos, wasm, modules, structures, builds, maps, items, recipes, loot, buffs, blocks, game_blocks, datapacks, biomes, skies, dimension_types, terrain, advancements, menus, dialogs, screens, centities",
            "package.export-kind netherforge.json $.exports.things: \"things\" isn't a kind of resource: export from resource_packs, particles, cutscenes, screen_fonts, videos, wasm, modules, structures, builds, maps, items, recipes, loot, buffs, blocks, game_blocks, datapacks, biomes, skies, dimension_types, terrain, advancements, menus, dialogs, screens, centities",
        ]
    );
}
