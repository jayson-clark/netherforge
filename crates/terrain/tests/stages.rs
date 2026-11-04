//! A script's column and place stages (format's `TerrainDensityTest` and
//! `TerrainVolumesTest`, their script cases): `density`, `area` and `biome`,
//! what a stage may ask, and a plan made once per cell.

use std::collections::BTreeMap;
use std::sync::{Arc, Mutex};

use netherforge_format::kind::terrain::TerrainFile;
use netherforge_format::kind::terrain::compiled::{self, CompiledTerrain};
use netherforge_format::kind::terrain::scripts::ScriptFailure;
use netherforge_terrain::LuauScripts;

const SCRIPT: &str = "terrain/t.lua";

fn compile(json: &str) -> CompiledTerrain {
    let file: TerrainFile = netherforge_format::read::parse(json, "terrain/t.json").unwrap();
    compiled::compile(&file, Some(SCRIPT))
}

type Failures = Arc<Mutex<Vec<ScriptFailure>>>;

fn scripts(source: &str) -> (LuauScripts, Failures) {
    let failures: Failures = Default::default();
    let told = failures.clone();
    let scripts = LuauScripts {
        sources: Arc::new(BTreeMap::from([(SCRIPT.to_owned(), source.to_owned())])),
        namespace: None,
        failed: Arc::new(move |f| told.lock().unwrap().push(f)),
    };
    (scripts, failures)
}

const BARE: &str = r#"{
    "terrain": { "base": 70, "seaLevel": 62, "noises": { "hills": { "noise": { "frequency": 0.006, "octaves": 3 }, "amplitude": 16 } },
        "density": {
            "noises": { "overhangs": { "noise": { "frequency": 0.03, "octaves": 2 }, "amplitude": 12, "squash": 2 } },
            "islands": { "y": 150, "thickness": 28, "threshold": 0.3 }
        } },
    "layers": [{ "block": "minecraft:grass_block" }, { "block": "minecraft:dirt", "thickness": 3 }]
}"#;

#[test]
fn a_scripts_density_stage_changes_the_ground() {
    let scripted_json = BARE.replace("\"layers\"", "\"script\": {}, \"layers\"");
    // A floating slab at 200 to 215 over x 0 to 31, and every point's value checked against the file's own.
    let (scripts, failures) = scripts(
        r#"
        return {
          density = function(x, y, z, value)
            -- (Lua 5.4 also said the value is a float; on Luau a whole one can't say so.)
            assert(math.type(x) == "integer" and math.type(y) == "integer" and type(value) == "number")
            assert(x % 4 == 0 and z % 4 == 0 and y % 8 == 0)
            if x >= 0 and x <= 32 and y >= 200 and y <= 216 then return 1 + math.random() * 0.1 end
            return value
          end,
        }
        "#,
    );
    let scripted = compile(&scripted_json).bind(5, -64, 320, Some(&scripts));
    let plain = compile(BARE).bind(5, -64, 320, None);
    let buffer = scripted.generate(0, 0);
    let same = plain.generate(0, 0);
    for lx in 0..16 {
        for lz in 0..16 {
            assert!(
                (200..=216).all(|y| buffer.get(lx, y, lz) != 0),
                "the slab at {lx},{lz}"
            );
            assert_eq!(scripted.surface_at(lx, lz), 216);
            for y in -64..190 {
                assert_eq!(
                    buffer.get(lx, y, lz),
                    same.get(lx, y, lz),
                    "below the slab at {lx},{y},{lz}"
                );
            }
        }
    }
    // Outside it, the file's own ground.
    assert_eq!(
        scripted.generate(-5, 3).blocks,
        plain.generate(-5, 3).blocks
    );
    assert_eq!(*failures.lock().unwrap(), []);
    scripted.close();
}

#[test]
fn a_density_stage_that_fails_leaves_the_files_ground() {
    let scripted_json = BARE.replace("\"layers\"", "\"script\": { \"budget\": 5000 }, \"layers\"");
    let source = r#"
        return {
          density = function(x, y, z, value)
            if y > 100 then error("too high") end
            if y < -40 then while true do end end
            return value
          end,
        }
    "#;
    let (scripts, failures) = scripts(source);
    let scripted = compile(&scripted_json).bind(5, -64, 320, Some(&scripts));
    assert_eq!(
        scripted.generate(1, 1).blocks,
        compile(BARE).bind(5, -64, 320, None).generate(1, 1).blocks
    );
    let told = failures.lock().unwrap().clone();
    assert!(
        told.iter().any(|f| f.stage == "density"
            && f.message.contains("too high")
            && f.location() == (SCRIPT.to_owned(), Some(4))),
        "{told:?}"
    );
    assert!(
        told.iter()
            .any(|f| f.stage == "density" && f.message.contains("budget")),
        "{told:?}"
    );
    scripted.close();
    // A density stage in a file with no density never runs, and says so as the script loads.
    let (quiet, _) = self::scripts(source);
    let said = quiet
        .check(&compile(r#"{ "terrain": { "base": 60 }, "script": {} }"#))
        .expect("it's told");
    assert_eq!(said.stage, "load");
    assert!(said.message.contains("terrain.density"), "{}", said.message);
}

fn scripted() -> String {
    r#"{
        "terrain": { "base": 64, "seaLevel": 40 },
        "biomes": {
          "east": { "biome": "minecraft:plains" },
          "west": { "biome": "minecraft:desert", "terrain": { "base": 80 } },
          "deep": { "biome": "minecraft:lush_caves", "depth": { "min": 20 } }
        },
        "script": { "blocks": ["minecraft:chest"], "loot": ["treasure"] }
    }"#
    .to_owned()
}

#[test]
fn the_area_stage_chooses_columns() {
    let (scripts, failures) = scripts(
        r#"
        local terrain = ...
        return {
          area = function(x, z, area)
            assert(area == "east" or area == "west")
            if x < 0 then return "west" end
            return "east"
          end,
        }
        "#,
    );
    let generator = compile(&scripted()).bind(1, -64, 320, Some(&scripts));
    assert_eq!(generator.column_biome_at(-100, 0), "minecraft:desert");
    assert_eq!(generator.column_biome_at(100, 0), "minecraft:plains");
    // The area shapes the ground: the west's own base.
    assert_eq!(generator.surface_at(-500, 0), 80);
    assert_eq!(generator.surface_at(500, 0), 64);
    generator.close();
    assert_eq!(*failures.lock().unwrap(), []);
}

#[test]
fn an_area_stage_cant_return_a_volume() {
    let compiled = compile(&scripted());
    let (scripts, failures) = scripts("return { area = function(x, z, area) return \"deep\" end }");
    let generator = compiled.bind(1, -64, 320, Some(&scripts));
    assert!(compiled.areas[generator.area_at(0, 0)].volume.is_none());
    generator.close();
    let told = failures.lock().unwrap();
    assert!(
        !told.is_empty()
            && told
                .iter()
                .all(|f| f.stage == "area" && f.message.contains("isn't limited by height")),
        "{told:?}"
    );
}

#[test]
fn the_biome_stage_chooses_places_and_the_chunk_agrees() {
    let (scripts, failures) = scripts(
        r#"
        local terrain = ...
        return {
          biome = function(x, y, z, area)
            if y > terrain.height(x, z) + 50 then return "west" end
            return area
          end,
          decorate = function(chunk)
            local x, z = chunk:min_x(), chunk:min_z()
            assert(terrain.area(x, 200, z) == "west", terrain.area(x, 200, z))
            assert(terrain.biome(x, -40, z) == "minecraft:lush_caves")
            assert(terrain.area(x, z) ~= "deep")
          end,
        }
        "#,
    );
    let generator = compile(&scripted()).bind(1, -64, 320, Some(&scripts));
    let sampler = generator.sampler(None);
    assert_eq!(generator.biome_at(8, 200, 8, &sampler), "minecraft:desert");
    assert_eq!(
        generator.biome_at(8, -40, 8, &sampler),
        "minecraft:lush_caves"
    );
    generator.generate(0, 0);
    generator.close();
    assert_eq!(*failures.lock().unwrap(), []);
}

#[test]
fn the_biome_stage_is_what_a_locate_finds() {
    let (scripts, failures) = scripts(
        r#"
        local terrain = ...
        return {
          biome = function(x, y, z, area)
            if x >= 64 and y > terrain.height(x, z) + 50 then return "west" end
            return area
          end,
        }
        "#,
    );
    let generator = compile(&scripted()).bind(1, -64, 320, Some(&scripts));
    let desert = generator.areas_where(&|b| b == "minecraft:desert");
    let ys = generator.locate_heights(64, 64);
    generator.sampling(|sampler| {
        assert_eq!(sampler.first_where(0, 0, &ys, &desert), None);
        assert_eq!(sampler.first_where(64, 0, &ys, &desert), Some(128));
    });
    generator.close();
    assert_eq!(*failures.lock().unwrap(), []);
}

#[test]
fn stages_cant_ask_what_depends_on_them() {
    let (scripts, failures) = scripts(
        r#"
        local terrain = ...
        return {
          area = function(x, z, area) terrain.height(x, z) return area end,
          height = function(x, z, h) terrain.area(x, 10, z) return h end,
        }
        "#,
    );
    let generator = compile(&scripted()).bind(1, -64, 320, Some(&scripts));
    generator.surface_at(0, 0);
    generator.close();
    let messages: Vec<String> = failures
        .lock()
        .unwrap()
        .iter()
        .map(|f| format!("{}: {}", f.stage, f.message))
        .collect();
    assert!(
        messages.iter().any(|m| m.starts_with("area:")
            && m.contains("terrain.height can't be asked from the area stage")),
        "{messages:?}"
    );
    assert!(messages.iter().any(|m| m.starts_with("height:") && m.contains("can't be asked from the height stage")), "{messages:?}");
}

#[test]
fn a_plan_is_made_once_per_cell_and_the_same_everywhere() {
    let source = r#"
        local terrain = ...
        local made = 0
        local spots = terrain.plan("spot", 64, function(cell_x, cell_z)
          made = made + 1
          return { x = cell_x * 64 + math.random(0, 63), z = cell_z * 64 + math.random(0, 63), made = made }
        end)
        return {
          decorate = function(chunk)
            local before = math.random(1, 1000000)
            local spot = spots:at(chunk:min_x(), chunk:min_z())
            assert(spots:get(chunk:min_x() // 64, chunk:min_z() // 64) == spot)
            assert(spots:size() == 64)
            chunk:set(spot.x, 100, spot.z, "minecraft:chest")
            chunk:set_loot(spot.x, 100, spot.z, "treasure")
            -- How many plans this state made: the first chunk of each cell makes one, the rest reuse it.
            chunk:set(chunk:min_x(), 120 + spot.made, chunk:min_z(), "minecraft:chest")
            -- The stage's own numbers don't move whether the plan was made now or before.
            chunk:set(chunk:min_x() + 1, 0, chunk:min_z(), before % 2 == 0 and "minecraft:chest" or "minecraft:air")
          end,
        }
    "#;
    let compiled = compile(&scripted());
    let (sa, failures) = scripts(source);
    let (sb, _) = scripts(source);
    let a = compiled.bind(4, -64, 320, Some(&sa));
    let b = compiled.bind(4, -64, 320, Some(&sb));
    let chest = compiled
        .palette
        .iter()
        .position(|p| p.label() == "minecraft:chest")
        .unwrap() as i32;
    // Every chunk of a 64-block cell, generated in another order by each.
    let chunks: Vec<(i32, i32)> = (0..4)
        .flat_map(|cx| (0..4).map(move |cz| (cx, cz)))
        .collect();
    let first: Vec<_> = chunks.iter().map(|(x, z)| a.generate(*x, *z)).collect();
    let mut second: Vec<_> = chunks
        .iter()
        .rev()
        .map(|(x, z)| b.generate(*x, *z))
        .collect();
    second.reverse();
    let loot: Vec<_> = first
        .iter()
        .zip(&chunks)
        .flat_map(|(buffer, (x, z))| buffer.loot_places(*x, *z))
        .collect();
    assert_eq!(loot.len(), 1, "one spot in the cell: {loot:?}");
    assert_eq!(
        loot[0].table,
        compiled.loot.iter().position(|t| t == "treasure").unwrap() as i32
    );
    for (one, other) in first.iter().zip(&second) {
        // The spot is the same in both, and each made the cell's plan once (made is 1 in every chunk).
        assert_eq!(one.get(0, 121, 0), chest);
        assert_eq!(other.get(0, 121, 0), chest);
        assert_eq!(one.get(1, 0, 0), other.get(1, 0, 0));
        assert_eq!(one.loot, other.loot);
    }
    a.close();
    b.close();
    assert_eq!(*failures.lock().unwrap(), []);
}
