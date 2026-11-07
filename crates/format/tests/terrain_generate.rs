//! A seed is the same world in Rust as in format's Kotlin: the summaries
//! Kotlin's terrain tests write (`packages/format/testdata/terrain/golden.txt`,
//! `density.txt` and `areas.txt`: heights, biomes, surfaces and each chunk's
//! blocks hashed), from the same fixtures (`hills.json`, `density.json`,
//! `areas.json` with `tree.json`), line for line.

use std::collections::BTreeMap;

use netherforge_format::kind::terrain::TerrainFile;
use netherforge_format::kind::terrain::compiled::{self, CompiledTerrain, StructureTemplate};
use netherforge_format::kind::terrain::generate::{ChunkBuffer, TerrainGenerator};
use netherforge_format::kind::terrain::preview;
use serde_json::Value;

fn testdata(name: &str) -> String {
    let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../packages/format/testdata/terrain")
        .join(name);
    std::fs::read_to_string(path).unwrap()
}

fn compile(fixture: &str) -> CompiledTerrain {
    let file: TerrainFile =
        netherforge_format::read::parse(&testdata(fixture), "terrain/t.json").unwrap();
    compiled::compile(&file, Some("terrain/t.lua"))
}

fn golden(name: &str) -> Vec<String> {
    testdata(name)
        .lines()
        .filter(|l| !l.trim().is_empty())
        .map(str::to_owned)
        .collect()
}

/// A chunk's blocks hashed (FNV-1a over the palette indexes) and counted per palette entry.
fn chunk_line(
    seed: i64,
    cx: i32,
    cz: i32,
    terrain: &CompiledTerrain,
    buffer: &ChunkBuffer,
) -> String {
    let mut hash = 0x811C_9DC5_u32 as i32;
    let mut counts = vec![0; terrain.palette.len()];
    for &index in &buffer.blocks {
        hash = (hash ^ index).wrapping_mul(0x0100_0193);
        counts[index as usize] += 1;
    }
    let counts: Vec<String> = counts.iter().map(i32::to_string).collect();
    format!(
        "seed {seed} chunk {cx},{cz} hash {hash} counts {}",
        counts.join(",")
    )
}

fn joined<T: ToString>(items: impl IntoIterator<Item = T>, by: &str) -> String {
    items
        .into_iter()
        .map(|i| i.to_string())
        .collect::<Vec<_>>()
        .join(by)
}

fn palette_line(terrain: &CompiledTerrain) -> String {
    format!(
        "palette {}",
        joined(terrain.palette.iter().map(|b| b.label().to_owned()), ",")
    )
}

fn heights(g: &TerrainGenerator, n: i32, x: impl Fn(i32) -> i32, z: impl Fn(i32) -> i32) -> String {
    joined((0..n).map(|i| g.surface_at(x(i), z(i))), ",")
}

#[test]
fn a_hilly_world_is_kotlins() {
    let w = compile("hills.json");
    let mut lines = Vec::new();
    for seed in [1, -987_654_321_012] {
        let g = w.bind(seed, -64, 320, None);
        lines.push(format!(
            "seed {seed} heights {}",
            heights(&g, 6, |i| i * 97 - 200, |i| i * -61 + 40)
        ));
        let biomes = (0..6).map(|i| g.column_biome_at(i * 530 - 900, i * 211 - 300).to_owned());
        lines.push(format!("seed {seed} biomes {}", joined(biomes, ",")));
        for (cx, cz) in [(0, 0), (-3, 2)] {
            lines.push(chunk_line(seed, cx, cz, &w, &g.generate(cx, cz)));
        }
    }
    assert_eq!(lines, golden("golden.txt"));
}

#[test]
fn a_world_with_a_density_is_kotlins() {
    let w = compile("density.json");
    let mut lines = vec![palette_line(&w)];
    for seed in [7, -123_456_789_012_345] {
        let g = w.bind(seed, -64, 320, None);
        let sampler = g.sampler(None);
        lines.push(format!(
            "seed {seed} heights {}",
            heights(&g, 8, |i| i * 131 - 500, |i| i * -77 + 60)
        ));
        let surfaces = (0..8).map(|i| joined(sampler.surfaces_at(i * 53 - 200, i * 31), ","));
        lines.push(format!("seed {seed} surfaces {}", joined(surfaces, ";")));
        for (cx, cz) in [(0, 0), (0, -6), (4, -4), (-6, 10), (-10, 4), (30, 2)] {
            lines.push(chunk_line(seed, cx, cz, &w, &g.generate(cx, cz)));
        }
    }
    assert_eq!(lines, golden("density.txt"));
}

/// `areas.json`'s world, with `tree.json` linked in.
fn everything() -> CompiledTerrain {
    let tree: Value = serde_json::from_str(&testdata("tree.json")).unwrap();
    let ints = |v: &Value| {
        v.as_array()
            .unwrap()
            .iter()
            .map(|n| n.as_i64().unwrap() as i32)
            .collect::<Vec<_>>()
    };
    let template = StructureTemplate {
        size_x: tree["sizeX"].as_i64().unwrap() as i32,
        size_y: tree["sizeY"].as_i64().unwrap() as i32,
        size_z: tree["sizeZ"].as_i64().unwrap() as i32,
        palette: tree["palette"]
            .as_array()
            .unwrap()
            .iter()
            .map(|s| s.as_str().unwrap().to_owned())
            .collect(),
        blocks: ints(&tree["blocks"]),
        with_entity: Default::default(),
    };
    compile("areas.json").with_structures(&BTreeMap::from([("tree".to_owned(), template)]))
}

#[test]
fn a_world_of_everything_is_kotlins() {
    let w = everything();
    let mut lines = vec![palette_line(&w)];
    for seed in [7, -123_456_789_012_345] {
        let g = w.bind(seed, -64, 320, None);
        lines.push(format!(
            "seed {seed} heights {}",
            heights(&g, 8, |i| i * 131 - 500, |i| i * -77 + 60)
        ));
        lines.push(format!(
            "seed {seed} areas {}",
            joined((0..8).map(|i| g.area_at(i * 257 - 900, i * 191 - 300)), ",")
        ));
        for (cx, cz) in [(0, 0), (-5, 3), (12, -9)] {
            lines.push(chunk_line(seed, cx, cz, &w, &g.generate(cx, cz)));
        }
    }
    assert_eq!(lines, golden("areas.txt"));
}

/// The editor's preview of that world: a close map with its decorations, a far
/// one without, and a slice along each axis, as `preview.json` (Kotlin's
/// `TerrainAreasTest`) says.
#[test]
fn a_preview_is_kotlins() {
    let g = everything().bind(7, -64, 320, None);
    let none = Vec::new;
    let actual = serde_json::json!({
        "map": preview::map(&g, -40, -40, 24, 4, none),
        "far": preview::map(&g, -50_000, -50_000, 32, 400, none),
        "x": preview::slice(&g, true, 5, -20, 40, none),
        "z": preview::slice(&g, false, -9, 10, 24, none),
    });
    let expected: Value = serde_json::from_str(&testdata("preview.json")).unwrap();
    for (name, want) in expected.as_object().unwrap() {
        for (field, value) in want.as_object().unwrap() {
            assert_eq!(&actual[name][field], value, "{name}.{field}");
        }
        assert_eq!(&actual[name], want, "{name}");
    }
    assert!(actual["map"]["decorationsShown"].as_bool().unwrap());
    assert!(!actual["map"]["decorations"].as_array().unwrap().is_empty());
}

#[test]
fn a_scripts_failure_is_shown_where_the_message_says() {
    use netherforge_format::kind::terrain::scripts::ScriptFailure;
    let failure = ScriptFailure {
        file: "terrain/t.lua".into(),
        stage: "height".into(),
        message: "lib/hills.lua:12: attempt to index nil".into(),
    };
    assert_eq!(
        serde_json::to_value(&failure).unwrap(),
        serde_json::json!({ "stage": "height", "message": "lib/hills.lua:12: attempt to index nil", "file": "lib/hills.lua", "line": 12 })
    );
    let bare = ScriptFailure {
        message: "out of memory".into(),
        ..failure
    };
    assert_eq!(
        serde_json::to_value(&bare).unwrap()["file"],
        "terrain/t.lua"
    );
    assert!(serde_json::to_value(&bare).unwrap()["line"].is_null());
}
