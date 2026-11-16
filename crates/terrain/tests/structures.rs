//! A terrain script's shapes and structures (format's `TerrainStructureTest`):
//! `chunk:ellipsoid`, `terrain.structure`, `chunk:place_structure`,
//! `terrain.scatter`, the blocks a script names in full, `vec3` and `nf.math`.

use std::collections::{BTreeMap, HashSet};
use std::sync::{Arc, Mutex};

use netherforge_format::kind::terrain::TerrainFile;
use netherforge_format::kind::terrain::compiled::{self, CompiledTerrain};
use netherforge_format::kind::terrain::generate::ChunkBuffer;
use netherforge_terrain::LuauScripts;

const SCRIPT: &str = "terrain/t.lua";

fn compiled() -> CompiledTerrain {
    let file: TerrainFile = netherforge_format::read::parse(
        r#"{
            "terrain": { "base": 64, "seaLevel": 40 },
            "layers": [{ "block": "minecraft:grass_block" }],
            "script": { "budget": 2000000, "blocks": ["minecraft:gold_block", "minecraft:glass"] }
        }"#,
        "terrain/t.json",
    )
    .unwrap();
    compiled::compile(&file, Some(SCRIPT))
}

fn scripts(source: &str, namespace: Option<&str>) -> (LuauScripts, Arc<Mutex<Vec<String>>>) {
    let failures: Arc<Mutex<Vec<String>>> = Default::default();
    let told = failures.clone();
    let scripts = LuauScripts {
        sources: Arc::new(BTreeMap::from([(SCRIPT.to_owned(), source.to_owned())])),
        namespace: namespace.map(str::to_owned),
        failed: Arc::new(move |f| told.lock().unwrap().push(f.message)),
    };
    (scripts, failures)
}

/// The chunks made with `source`'s stages, and what failed.
fn generate(
    terrain: &CompiledTerrain,
    source: &str,
    chunks: &[(i32, i32)],
) -> (Vec<ChunkBuffer>, Vec<String>) {
    let (scripts, failures) = scripts(source, None);
    let generator = terrain.bind(7, -64, 320, Some(&scripts));
    let buffers = chunks
        .iter()
        .map(|(x, z)| generator.generate(*x, *z))
        .collect();
    generator.close();
    let told = failures.lock().unwrap().clone();
    (buffers, told)
}

fn label(terrain: &CompiledTerrain, buffer: &ChunkBuffer, x: i32, y: i32, z: i32) -> String {
    terrain.palette[buffer.get(x & 15, y, z & 15) as usize]
        .label()
        .to_owned()
}

#[test]
fn an_ellipsoid_is_round_solid_or_hollow() {
    let terrain = compiled();
    let (buffers, failures) = generate(
        &terrain,
        r#"
        return {
          decorate = function(chunk)
            chunk:ellipsoid(8, 100, 8, 2, 2, 2, "minecraft:gold_block")
            chunk:ellipsoid(8, 120, 8, 3, 3, 3, "minecraft:gold_block", { shell = 1 })
            chunk:fill(0, 140, 0, 15, 140, 15, "minecraft:glass")
            chunk:ellipsoid(8, 140, 8, 3, 0, 3, "minecraft:gold_block", { only = { "minecraft:air" } })
            assert(not pcall(function() chunk:ellipsoid(8, 100, 8, -1, 2, 2, "minecraft:gold_block") end))
            assert(not pcall(function() chunk:ellipsoid(8, 100, 8, 1, 1, 1, "minecraft:gold_block", { hollow = 1 }) end))
          end,
        }
        "#,
        &[(0, 0)],
    );
    assert_eq!(failures, Vec::<String>::new());
    let at = |x, y, z| label(&terrain, &buffers[0], x, y, z);
    assert_eq!(at(8, 100, 8), "minecraft:gold_block");
    assert_eq!(at(10, 100, 8), "minecraft:gold_block", "its radius");
    assert_eq!(at(11, 100, 8), "minecraft:air", "past it");
    assert_eq!(
        at(10, 102, 10),
        "minecraft:air",
        "rounded off at the corners"
    );
    assert_eq!(at(8, 120, 8), "minecraft:air", "a hollow one's middle");
    assert_eq!(at(11, 120, 8), "minecraft:gold_block", "its shell");
    assert_eq!(
        at(8, 140, 8),
        "minecraft:glass",
        "only over air: the glass stays"
    );
}

#[test]
fn a_structure_crosses_chunks_and_is_the_same_in_each() {
    let terrain = compiled();
    let (buffers, failures) = generate(
        &terrain,
        r#"
        local terrain = ...
        local wall = terrain.structure()
          :fill(10, 100, 4, 20, 104, 4, "minecraft:glass")
          :sprinkle(10, 100, 4, 20, 104, 4, "minecraft:gold_block", 20, { only = { "minecraft:glass" } })
          :ellipsoid(16, 110, 4, 2, 2, 2, "minecraft:gold_block")
        local x1, y1, z1, x2, y2, z2 = wall:bounds()
        assert(x1 == 10 and y1 == 100 and z1 == 2 and x2 == 20 and y2 == 112 and z2 == 6, table.concat({ wall:bounds() }, ","))
        assert(terrain.structure():bounds() == nil)
        local post = terrain.structure():fill(0, 0, 0, 0, 2, 0, "minecraft:gold_block")
        return {
          decorate = function(chunk)
            chunk:place_structure(wall)
            chunk:place_structure(post, 15, 130, 15)
            chunk:place_structure(post, 16, 130, 15)
          end,
        }
        "#,
        &[(0, 0), (1, 0)],
    );
    assert_eq!(failures, Vec::<String>::new());
    let stone = |x: i32, y: i32| label(&terrain, &buffers[if x < 16 { 0 } else { 1 }], x, y, 4);
    // The wall runs on into the next chunk, and the ball on the border is in both.
    let seen: HashSet<String> = [stone(15, 102), stone(16, 102), stone(20, 104)].into();
    assert_eq!(
        seen,
        HashSet::from([
            "minecraft:glass".to_owned(),
            "minecraft:gold_block".to_owned()
        ])
    );
    assert_eq!(stone(15, 110), "minecraft:gold_block");
    assert_eq!(stone(17, 110), "minecraft:gold_block");
    let gold: usize = (100..=104)
        .map(|y| {
            (10..=20)
                .filter(|x| stone(*x, y) == "minecraft:gold_block")
                .count()
        })
        .sum();
    assert!(
        (10..=20).contains(&gold),
        "sprinkled over the glass, never outside it: {gold}"
    );
    // A structure placed by an offset: a post in each chunk.
    assert_eq!(
        label(&terrain, &buffers[0], 15, 132, 15),
        "minecraft:gold_block"
    );
    assert_eq!(
        label(&terrain, &buffers[1], 16, 130, 15),
        "minecraft:gold_block"
    );
}

#[test]
fn a_scatter_draws_what_reaches_the_chunk_from_the_cells_around() {
    let terrain = compiled();
    let (buffers, failures) = generate(
        &terrain,
        r#"
        local terrain = ...
        local towers = terrain.scatter("towers", 32, function(cell_x, cell_z)
          if cell_x ~= 0 or cell_z ~= 0 then return nil end
          return terrain.structure():fill(28, 100, 8, 36, 100, 8, "minecraft:gold_block")
        end)
        assert(towers:get(0, 0) ~= nil and towers:get(1, 0) == nil)
        assert(towers:at(40, 8) == nil and select(2, towers:at(40, 8)) == 1)
        assert(not pcall(terrain.scatter, "late", 16, function() end, 9))
        return {
          decorate = function(chunk) towers:place(chunk) end,
        }
        "#,
        &[(2, 0)],
    );
    assert_eq!(failures, Vec::<String>::new());
    // Chunk 2 is x 32 to 47, in cell 1: the tower of cell 0 reaches into it.
    assert_eq!(
        label(&terrain, &buffers[0], 36, 100, 8),
        "minecraft:gold_block"
    );
    assert_eq!(label(&terrain, &buffers[0], 37, 100, 8), "minecraft:air");
}

#[test]
fn the_blocks_a_script_names_in_full_are_placed_without_being_listed() {
    let terrain = compiled();
    let source = r#"
        local SHAPES = { lamp = "minecraft:sea_lantern", stairs = "minecraft:oak_stairs[facing=east]" }
        return {
          decorate = function(chunk)
            chunk:set(1, 100, 1, SHAPES.lamp)
            chunk:set(2, 100, 1, SHAPES.stairs)
            chunk:set(3, 100, 1, "ruby_ore")
            assert(not pcall(function() chunk:set(4, 100, 1, "minecraft:" .. "glowstone") end))
          end,
        }
    "#;
    let scripted =
        terrain.with_script_blocks(&[source], &|id| id != "minecraft:diamond", None, &|id| {
            id == "ruby_ore"
        });
    assert_eq!(
        terrain.palette,
        scripted.palette[..terrain.palette.len()],
        "the file's own keep their places"
    );
    let (scripts, failures) = scripts(source, None);
    let generator = scripted.bind(7, -64, 320, Some(&scripts));
    let chunk = generator.generate(0, 0);
    generator.close();
    assert_eq!(*failures.lock().unwrap(), Vec::<String>::new());
    let at = |x| {
        scripted.palette[chunk.get(x, 100, 1) as usize]
            .label()
            .to_owned()
    };
    assert_eq!(at(1), "minecraft:sea_lantern");
    assert_eq!(at(2), "minecraft:oak_stairs[facing=east]");
    assert_eq!(at(3), "ruby_ore");
}

#[test]
fn a_packages_script_places_another_packages_blocks_in_full_and_its_own_bare() {
    let terrain = compiled();
    let source = r#"
        return {
          decorate = function(chunk)
            chunk:set(1, 100, 1, "acme:altar")
            chunk:set(2, 100, 1, "ruby_ore")
          end,
        }
    "#;
    let known = ["acme:altar", "lib:ruby_ore"];
    let scripted =
        terrain.with_script_blocks(&[source], &|_| true, Some("lib"), &|id| known.contains(&id));
    let (scripts, failures) = scripts(source, Some("lib"));
    let generator = scripted.bind(7, -64, 320, Some(&scripts));
    let chunk = generator.generate(0, 0);
    generator.close();
    assert_eq!(*failures.lock().unwrap(), Vec::<String>::new());
    assert_eq!(
        scripted.palette[chunk.get(1, 100, 1) as usize].label(),
        "acme:altar"
    );
    assert_eq!(
        scripted.palette[chunk.get(2, 100, 1) as usize].label(),
        "lib:ruby_ore"
    );
}

#[test]
fn vec3_and_nf_math_are_there_as_in_other_scripts() {
    let terrain = compiled();
    let (_, failures) = generate(
        &terrain,
        r#"
        local v = vec3(1, 2, 2)
        assert(v:length() == 3 and v.x == 1 and tostring(v) == "vec3(1, 2, 2)", tostring(v))
        assert((v + vec3.one) == vec3(2, 3, 3) and (v * 2).z == 4 and vec3.up:cross(vec3.east) == vec3(0, 0, -1))
        assert(vec3(0, -3, 0):reflect(vec3.up) == vec3(0, 3, 0) and vec3(5, 0, 0):clamp_length(2) == vec3(2, 0, 0))
        assert(not pcall(function() v.x = 3 end))
        assert(nf.math.clamp(5, 0, 2) == 2 and nf.math.lerp(0, 10, 0.5) == 5 and nf.math.remap(5, 0, 10, 0, 1) == 0.5)
        assert(nf.math.ease("ease_in", 0.5) == 0.25 and not pcall(nf.math.ease, "bouncy", 0.5))
        assert(nf.players == nil and nf.random == nil)
        return {
          decorate = function(chunk)
            local p = vec3(chunk:min_x(), 100, chunk:min_z()):floor()
            chunk:set(p.x, p.y, p.z, "minecraft:gold_block")
          end,
        }
        "#,
        &[(0, 0)],
    );
    assert_eq!(failures, Vec::<String>::new());
}
