//! `GameDataBundle`: what the dev server exports and the editor caches, read as format's `GameData`
//! (`testdata/game-data/bundle.json`, which Kotlin's `GameDataTest` reads too).

use netherforge_format::game::{BUNDLE_SCHEMA, GameData, GameDataBundle, ParticleData, Registry};

fn bundle() -> GameDataBundle {
    let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../packages/format/testdata/game-data/bundle.json");
    GameDataBundle::parse(&std::fs::read_to_string(path).unwrap()).unwrap()
}

#[test]
fn a_bundle_answers_as_the_game_would() {
    let game = bundle();
    assert_eq!(game.minecraft_version(), "26.3");
    assert!(game.has(Registry::Item, "minecraft:oak_log"));
    assert!(game.lacks(Registry::Item, "minecraft:ruby"));
    assert!(
        !game.lacks(Registry::Enchantment, "minecraft:sharpness"),
        "a registry it doesn't know says nothing"
    );
    assert_eq!(
        game.tag("minecraft:item", "minecraft:logs")
            .map(|t| t.len()),
        Some(2)
    );
    assert_eq!(
        game.block_properties("minecraft:oak_log").unwrap()["axis"],
        ["x", "y", "z"]
    );
    assert_eq!(
        game.block_default("minecraft:oak_log", "axis").as_deref(),
        Some("y")
    );
    assert_eq!(game.particle("minecraft:dust"), Some(ParticleData::Dust));
    assert_eq!(game.data_pack_format(), Some([121, 0]));
    assert_eq!(
        game.collision_boxes("minecraft:stone[]", "minecraft:stone")
            .map(<[_]>::len),
        Some(1)
    );
    let colors = game.map_colors().unwrap();
    assert_eq!((colors[4], colors[5]), (0xFF00_0000, 0xFFFF_FFFF));
    assert_eq!(game.schema, BUNDLE_SCHEMA);
}

#[test]
fn a_bundle_writes_back_what_it_read() {
    let game = bundle();
    let written: serde_json::Value = serde_json::to_value(&game).unwrap();
    let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../packages/format/testdata/game-data/bundle.json");
    let read: serde_json::Value =
        serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap();
    // Boxes are doubles: `0` reads back as `0.0`, the same number.
    fn numbers(v: serde_json::Value) -> serde_json::Value {
        match v {
            serde_json::Value::Number(n) if n.is_f64() && n.as_f64().unwrap().fract() == 0.0 => {
                serde_json::Value::from(n.as_f64().unwrap() as i64)
            }
            serde_json::Value::Array(a) => a.into_iter().map(numbers).collect(),
            serde_json::Value::Object(o) => o.into_iter().map(|(k, v)| (k, numbers(v))).collect(),
            other => other,
        }
    }
    assert_eq!(numbers(written), read);
    // An empty one says its shape and nothing else.
    assert_eq!(
        serde_json::to_string(&GameDataBundle::new("26.3")).unwrap(),
        r#"{"minecraft":"26.3","schema":3}"#
    );
}
