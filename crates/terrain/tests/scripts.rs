//! A terrain's script on Luau makes the world it made on Lua 5.4: format's
//! `TerrainScriptTest`, from its fixture (`testdata/terrain/script`) and golden
//! (`script.txt`), and its cases of the budget, failures, the sandbox and
//! `require`.

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

use netherforge_format::kind::terrain::TerrainFile;
use netherforge_format::kind::terrain::compiled::{self, CompiledTerrain};
use netherforge_format::kind::terrain::generate::ChunkBuffer;
use netherforge_format::kind::terrain::scripts::ScriptFailure;
use netherforge_terrain::LuauScripts;

fn testdata() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../../packages/format/testdata/terrain")
}

fn compile(json: &str, script: &str) -> CompiledTerrain {
    let file: TerrainFile = netherforge_format::read::parse(json, "terrain/t.json").unwrap();
    compiled::compile(&file, Some(script))
}

type Failures = Arc<Mutex<Vec<ScriptFailure>>>;

fn scripts(sources: BTreeMap<String, String>) -> (LuauScripts, Failures) {
    let failures: Failures = Default::default();
    let told = failures.clone();
    let scripts = LuauScripts {
        sources: Arc::new(sources),
        namespace: None,
        failed: Arc::new(move |f| told.lock().unwrap().push(f)),
    };
    (scripts, failures)
}

fn hash(buffer: &ChunkBuffer) -> i32 {
    let mut hash = 0x811C_9DC5_u32 as i32;
    for &index in &buffer.blocks {
        hash = (hash ^ index).wrapping_mul(0x0100_0193);
    }
    hash
}

/// The fixture: `terrain/ridges.json` and every `.lua` beside it, by project path.
fn fixture() -> (CompiledTerrain, BTreeMap<String, String>) {
    let base = testdata().join("script");
    let json = std::fs::read_to_string(base.join("terrain/ridges.json")).unwrap();
    let compiled = compile(&json, "terrain/ridges.lua");
    let mut sources = BTreeMap::new();
    for path in [
        "terrain/ridges.lua",
        "modules/rocks/init.lua",
        "modules/rocks/shapes.lua",
    ] {
        sources.insert(
            path.to_owned(),
            std::fs::read_to_string(base.join(path)).unwrap(),
        );
    }
    (compiled, sources)
}

#[test]
fn a_script_makes_the_world_it_made_on_lua_5_4() {
    let (compiled, sources) = fixture();
    let (scripts, failures) = scripts(sources);
    let mut lines = Vec::new();
    for seed in [7, -123_456_789_012_345] {
        let generator = compiled.bind(seed, -64, 320, Some(&scripts));
        let heights: Vec<String> = (0..8)
            .map(|i| generator.surface_at(i * 41 - 150, i * -29 + 60).to_string())
            .collect();
        lines.push(format!("seed {seed} heights {}", heights.join(",")));
        for (cx, cz) in [(0, 0), (-4, 3), (5, -2)] {
            let buffer = generator.generate(cx, cz);
            let mut counts = vec![0; compiled.palette.len()];
            for &index in &buffer.blocks {
                counts[index as usize] += 1;
            }
            let counts: Vec<String> = counts.iter().map(i32::to_string).collect();
            lines.push(format!(
                "seed {seed} chunk {cx},{cz} hash {} counts {}",
                hash(&buffer),
                counts.join(",")
            ));
        }
        generator.close();
    }
    assert_eq!(*failures.lock().unwrap(), []);
    let golden = std::fs::read_to_string(testdata().join("script.txt")).unwrap();
    let expected: Vec<&str> = golden.lines().filter(|l| !l.trim().is_empty()).collect();
    assert_eq!(lines, expected);
}

#[test]
fn the_fixtures_stages_change_the_world() {
    let (compiled, sources) = fixture();
    let (scripts, _) = scripts(sources);
    let scripted = compiled.bind(7, -64, 320, Some(&scripts));
    let plain = compiled.bind(7, -64, 320, None);
    let heights: Vec<(i32, i32)> = (0..64)
        .map(|i| {
            (
                scripted.surface_at(i * 13, i * 7),
                plain.surface_at(i * 13, i * 7),
            )
        })
        .collect();
    assert!(
        heights.iter().any(|(a, b)| *a > b + 3),
        "ridges rise over the file's ground: {heights:?}"
    );
    let glowstone = compiled
        .palette
        .iter()
        .position(|b| b.label() == "minecraft:glowstone")
        .unwrap() as i32;
    let count = |buffer: &ChunkBuffer| buffer.blocks.iter().filter(|b| **b == glowstone).count();
    assert_eq!(
        count(&scripted.generate(1, 1)),
        1,
        "one glowing block in each chunk"
    );
    assert_eq!(count(&plain.generate(1, 1)), 0);
}

// ---- format's `TerrainScriptTest`: one script at a time -------------------------------------------------------

const SCRIPT: &str = "terrain/t.lua";

/// A small world with a script, its blocks listed, and `extra` more of the file's keys.
fn world(extra: &str) -> String {
    format!(
        r#"{{
        "terrain": {{ "base": 64, "seaLevel": 40, "noises": {{ "n": {{ "noise": {{ "frequency": 0.02 }}, "amplitude": 4 }} }} }},
        "layers": [{{ "block": "minecraft:grass_block" }}],
        "script": {{ "budget": 20000, "blocks": ["minecraft:gold_block"], "noises": {{ "a": {{}} }} }}{extra}
    }}"#
    )
}

fn one(source: &str) -> (LuauScripts, Failures) {
    scripts(BTreeMap::from([(SCRIPT.to_owned(), source.to_owned())]))
}

/// What goes wrong generating chunk (0, 0) of `world("")` with `source`, as "stage: message".
fn failures(source: &str) -> Vec<String> {
    let (scripts, failures) = one(source);
    compile(&world(""), SCRIPT)
        .bind(1, -64, 320, Some(&scripts))
        .generate(0, 0);
    let told = failures.lock().unwrap();
    told.iter()
        .map(|f| format!("{}: {}", f.stage, f.message))
        .collect()
}

#[test]
fn a_chunk_is_the_same_whichever_order_and_state_made_it() {
    let compiled = compile(&world(""), SCRIPT);
    let source = r#"
        local terrain = ...
        return {
          decorate = function(chunk)
            for _ = 1, 20 do
              local x, z = chunk:min_x() + math.random(0, 15), chunk:min_z() + math.random(0, 15)
              chunk:set(x, terrain.height(x, z) + 1, z, "minecraft:gold_block")
            end
          end,
        }
    "#;
    let first = compiled.bind(3, -64, 320, Some(&one(source).0));
    let a = first.generate(2, 5).blocks;
    first.generate(-7, 1);
    let b = first.generate(2, 5).blocks;
    let c = compiled
        .bind(3, -64, 320, Some(&one(source).0))
        .generate(2, 5)
        .blocks;
    assert_eq!(a, b);
    assert_eq!(a, c);
    assert_ne!(
        a,
        compiled
            .bind(4, -64, 320, Some(&one(source).0))
            .generate(2, 5)
            .blocks
    );
}

#[test]
fn a_stage_that_runs_past_its_budget_leaves_the_files_chunk() {
    let compiled = compile(&world(""), SCRIPT);
    let source = "
        return {
          terrain = function(chunk)
            chunk:fill(chunk:min_x(), 0, chunk:min_z(), chunk:min_x() + 15, 200, chunk:min_z() + 15, \"minecraft:gold_block\")
            -- A pcall can't catch its way past the budget.
            pcall(function() while true do end end)
            while true do end
          end,
        }
    ";
    let (scripts, failures) = one(source);
    let buffer = compiled.bind(1, -64, 320, Some(&scripts)).generate(0, 0);
    assert_eq!(
        compiled.bind(1, -64, 320, None).generate(0, 0).blocks,
        buffer.blocks,
        "the file's own chunk"
    );
    let told = failures.lock().unwrap();
    assert_eq!(told.len(), 1, "{told:?}");
    assert_eq!(told[0].stage, "terrain");
    assert_eq!(
        told[0].message,
        "terrain/t.lua:6: ran past its budget of 20000 instructions"
    );
    assert_eq!(told[0].location(), (SCRIPT.to_owned(), Some(6)));
}

#[test]
fn a_pattern_that_would_backtrack_for_minutes_is_refused_at_the_scripts_line() {
    let told = failures(
        "
        return {
          terrain = function(chunk)
            local s = (\"a\"):rep(10000)
            local found = s:find(\".-.-.-.-b\")
          end,
        }
    ",
    );
    assert_eq!(told.len(), 1);
    assert!(
        told[0]
            .contains("terrain/t.lua:5: string.find: the pattern \".-.-.-.-b\" could take up to"),
        "{told:?}"
    );
}

#[test]
fn a_loop_that_makes_garbage_is_still_held_to_its_budget() {
    let told = failures(
        "
        return {
          terrain = function(chunk)
            local s = (\"x\"):rep(64 * 1024)
            while true do local u = s .. \"a\" end
          end,
        }
    ",
    );
    assert_eq!(
        told,
        ["terrain: terrain/t.lua:5: ran past its budget of 20000 instructions"]
    );
}

#[test]
fn a_height_that_fails_is_the_files_height_there() {
    let compiled = compile(&world(""), SCRIPT);
    let source = r#"
        return {
          height = function(x, z, height)
            if x < 0 then error("west of 0") end
            if x > 100 then return "high" end
            return height + 5
          end,
        }
    "#;
    let (scripts, failures) = one(source);
    let generator = compiled.bind(1, -64, 320, Some(&scripts));
    let plain = compiled.bind(1, -64, 320, None);
    assert_eq!(generator.surface_at(10, 4), plain.surface_at(10, 4) + 5);
    assert_eq!(generator.surface_at(-10, 4), plain.surface_at(-10, 4));
    assert_eq!(generator.surface_at(110, 4), plain.surface_at(110, 4));
    let told: Vec<String> = failures
        .lock()
        .unwrap()
        .iter()
        .map(|f| f.message.clone())
        .collect();
    assert_eq!(
        told,
        [
            "terrain/t.lua:4: west of 0",
            "terrain/t.lua:3: the height stage must return a number, not string"
        ]
    );
}

#[test]
fn a_script_sees_no_server_and_no_way_out() {
    let told = failures(
        r#"
        -- Of nf, only nf.math: nothing of the server.
        assert(next(nf) == "math" and next(nf, "math") == nil)
        for _, name in ipairs({ "io", "os", "debug", "load", "loadfile", "dofile", "collectgarbage", "print", "package", "getfenv", "setfenv", "loadstring" }) do
          if _G[name] ~= nil then error(name .. " is there") end
        end
        assert(getmetatable("") == false, "the string metatable is hidden")
        assert(math.randomseed == nil and string.dump == nil)
        assert(not pcall(setmetatable, {}, { __gc = function() end }))
        assert(not pcall(string.rep, "x", 1e9))
        local ok, problem = pcall(require, "nowhere")
        assert(not ok and problem:find("no module \"nowhere\""), problem)
        return {}
    "#,
    );
    assert_eq!(told, Vec::<String>::new());
}

#[test]
fn what_a_script_gets_wrong_is_said_at_its_line() {
    // Luau's own words for what doesn't compile.
    let syntax = failures("+");
    assert_eq!(syntax.len(), 1);
    assert!(
        syntax[0].starts_with("load: terrain/t.lua:1: "),
        "{syntax:?}"
    );
    assert_eq!(
        failures("return { heigth = function() end }"),
        [
            "load: terrain/t.lua: \"heigth\" isn't a stage: a script's stages are height, density, area, biome, terrain and decorate"
        ]
    );
    assert_eq!(
        failures("local terrain = ... local b = terrain.noise('b') return {}"),
        ["load: terrain/t.lua:1: \"b\" isn't one of the noises the file's script.noises declares"]
    );
    let placed = failures(
        "return { terrain = function(chunk)\n chunk:set(0, 0, 0, 'minecraft:diamond_block') end }",
    );
    assert_eq!(placed.len(), 1);
    assert!(placed[0].starts_with("terrain: terrain/t.lua:2: \"minecraft:diamond_block\" isn't a block this generator places"), "{placed:?}");
    assert_eq!(
        failures(
            "return { terrain = function(chunk) chunk:fill(0, 0.5, 0, 1, 1, 1, 'minecraft:gold_block') end }"
        ),
        [
            "terrain: terrain/t.lua:1: bad argument #2 to 'fill' (whole number expected, got a number with a fraction)"
        ]
    );
    assert_eq!(
        failures(
            "local terrain = ... return { height = function(x, z) local h = terrain.height(x, z) return h end }"
        )[0],
        "height: terrain/t.lua:1: terrain.height can't be asked from the height stage: use the height the stage is given"
    );
}

#[test]
fn a_script_that_takes_too_much_memory_fails() {
    let (scripts, failures) =
        one("return { terrain = function()\n local s = 'x'\n while true do s = s .. s end\n end }");
    let json = world("").replace("\"budget\": 20000", "\"budget\": 100000000");
    compile(&json, SCRIPT)
        .bind(1, -64, 320, Some(&scripts))
        .generate(0, 0);
    let told = failures.lock().unwrap();
    assert_eq!(told.len(), 1, "{told:?}");
    assert!(
        told[0].message.ends_with("MB of memory"),
        "{}",
        told[0].message
    );
}

#[test]
fn the_area_biome_and_blocks_are_the_generators() {
    let compiled = compile(
        &world(
            r#", "biomes": { "only": { "biome": "minecraft:desert" } }, "stone": { "block": "minecraft:deepslate" }"#,
        ),
        SCRIPT,
    );
    let source = r#"
        local terrain = ...
        return {
          decorate = function(chunk)
            local x, z = chunk:min_x(), chunk:min_z()
            local top = terrain.height(x, z)
            assert(terrain.area(x, z) == "only" and terrain.biome(x, z) == "minecraft:desert")
            assert(chunk:block(x, top, z) == "minecraft:grass_block", chunk:block(x, top, z))
            assert(chunk:block(x, top - 1, z) == "minecraft:deepslate")
            assert(chunk:block(x, top + 1, z) == "minecraft:air")
            assert(chunk:block(x - 1, top, z) == nil and chunk:block(x, terrain.max_y() + 1, z) == nil)
            assert(terrain.min_y() == -64 and terrain.max_y() == 319 and terrain.sea_level() == 40)
            assert(terrain.seed() == -9007199254740993, tostring(terrain.seed()))
            assert(math.type(top) == "integer")
            local a = terrain.noise("a")
            assert(a:at(x, z) == a:at(x, z) and a:at(x, 0, z) ~= a:at(x, 1, z))
            chunk:fill(x, top + 1, z, x, top + 1, z, "minecraft:deepslate")
          end,
        }
    "#;
    let (scripts, failures) = one(source);
    let generator = compiled.bind(-9_007_199_254_740_993, -64, 320, Some(&scripts));
    let buffer = generator.generate(0, 0);
    assert_eq!(*failures.lock().unwrap(), []);
    let top = generator.surface_at(0, 0);
    assert_eq!(
        compiled.palette[buffer.get(0, top + 1, 0) as usize].label(),
        "minecraft:deepslate"
    );
}

#[test]
fn a_check_loads_the_script_once() {
    let compiled = compile(&world(""), SCRIPT);
    let (fine, told) = one("return { height = function(x, z, h) return h end }");
    assert_eq!(fine.check(&compiled), None);
    let (broken, told_too) = one("local x = = 1");
    let failure = broken.check(&compiled).expect("it fails to load");
    assert_eq!(failure.stage, "load");
    assert_eq!(failure.location(), (SCRIPT.to_owned(), Some(1)));
    let (plus, _) = one("+");
    assert_eq!(
        plus.check(&compile("{}", SCRIPT)),
        None,
        "a file with no script has nothing to check"
    );
    assert!(
        told.lock().unwrap().is_empty() && told_too.lock().unwrap().is_empty(),
        "a check tells no one else"
    );
}

#[test]
fn a_closed_generator_still_works() {
    let compiled = compile(&world(""), SCRIPT);
    let generator = compiled.bind(
        1,
        -64,
        320,
        Some(&one("return { height = function(x, z, h) return h + 1 end }").0),
    );
    let before = generator.surface_at(3, 3);
    generator.close();
    assert_eq!(generator.surface_at(3, 3), before);
    generator.close();
}

#[test]
fn a_scripts_sources_are_itself_and_the_modules() {
    use netherforge_format::kind::terrain::scripts::{module_paths, sources_of};
    let compiled = compile(&world(""), SCRIPT);
    let files: Vec<String> = [
        "terrain/t.lua",
        "terrain/other.lua",
        "modules/a/init.lua",
        "modules/a/b.lua",
        "modules/a/data.json",
        "x.lua",
    ]
    .map(str::to_owned)
    .to_vec();
    let sources = sources_of(compiled.script.as_ref().unwrap(), &files, &|p| {
        Some(format!("{p}!"))
    });
    let paths: Vec<&str> = sources.keys().map(String::as_str).collect();
    assert_eq!(
        paths,
        ["modules/a/b.lua", "modules/a/init.lua", "terrain/t.lua"]
    );
    let none: Vec<String> = Vec::new();
    assert_eq!(
        module_paths("terrain", "@terrain/t.lua", &none),
        ["modules/terrain/init.lua"]
    );
    assert_eq!(
        module_paths("terrain.a.b", "@terrain/t.lua", &none),
        ["modules/terrain/a/b.lua", "modules/terrain/a/b/init.lua"]
    );
    assert_eq!(
        module_paths("shapes", "@modules/rocks/init.lua", &none),
        [
            "modules/rocks/shapes.lua",
            "modules/rocks/shapes/init.lua",
            "modules/shapes/init.lua"
        ]
    );
    assert!(module_paths("../x", "@terrain/t.lua", &none).is_empty());
    assert!(module_paths("acme:x", "@terrain/t.lua", &none).is_empty());
}

#[test]
fn the_scripts_blocks_join_the_palette_after_the_files() {
    let compiled = compile(&world(""), SCRIPT);
    let plain = compile(
        &world("").replace(r#""blocks": ["minecraft:gold_block"], "#, ""),
        SCRIPT,
    );
    assert_eq!(
        plain.palette,
        compiled.palette[..compiled.palette.len() - 1]
    );
    assert_eq!(
        compiled.palette.last().unwrap().label(),
        "minecraft:gold_block"
    );
    assert_eq!(compiled.script.as_ref().unwrap().file, SCRIPT);
}

// ---- format's `TerrainVolumesTest`: plans, areas and places --------------------------------------------------

fn scripted(extra: &str) -> String {
    format!(
        r#"{{
        "terrain": {{ "base": 64, "seaLevel": 40 }},
        "biomes": {{
          "east": {{ "biome": "minecraft:plains" }},
          "west": {{ "biome": "minecraft:desert", "terrain": {{ "base": 80 }} }},
          "deep": {{ "biome": "minecraft:lush_caves", "depth": {{ "min": 20 }} }}
        }},
        "script": {{ "blocks": ["minecraft:chest"], "loot": ["treasure"] }}{extra}
    }}"#
    )
}

/// A plan's numbers (Lua 5.4's 64-bit splitmix in Rust) are the ones they were: `testdata/terrain/plans.txt`.
#[test]
fn a_plans_numbers_are_the_ones_lua_5_4_drew() {
    let source = r#"
        local terrain = ...
        local plan = terrain.plan("numbers", 16, function(cell_x, cell_z)
          return { math.random(), math.random(1, 6), math.random(-1000000, 1000000), math.random(2 ^ 40) }
        end)
        return {
          terrain = function(chunk)
            local p = plan:get(chunk:x(), chunk:z())
            local text = string.format("%.17g %d %d %d", p[1], p[2], p[3], p[4])
            -- Its numbers, as blocks: each digit of the text is a block's height.
            for i = 1, #text do
              local digit = tonumber(text:sub(i, i))
              if digit then chunk:set(chunk:min_x() + i % 16, 100 + digit, chunk:min_z() + i // 16, "minecraft:chest") end
            end
          end,
        }
    "#;
    let compiled = compile(&scripted(""), SCRIPT);
    let (scripts, failures) = one(source);
    let mut lines = Vec::new();
    for seed in [7, -123_456_789_012_345] {
        let generator = compiled.bind(seed, -64, 320, Some(&scripts));
        for (cx, cz) in [(0, 0), (-3, 5)] {
            lines.push(format!(
                "seed {seed} chunk {cx},{cz} hash {}",
                hash(&generator.generate(cx, cz))
            ));
        }
        generator.close();
    }
    assert_eq!(*failures.lock().unwrap(), []);
    let golden = std::fs::read_to_string(testdata().join("plans.txt")).unwrap();
    let expected: Vec<&str> = golden.lines().filter(|l| !l.trim().is_empty()).collect();
    assert_eq!(lines, expected);
}

#[test]
fn set_loot_takes_only_the_scripts_tables() {
    let compiled = compile(&scripted(""), SCRIPT);
    let (scripts, failures) = one(
        "return { decorate = function(chunk) chunk:set_loot(chunk:min_x(), 70, chunk:min_z(), \"nope\") end }",
    );
    let generator = compiled.bind(1, -64, 320, Some(&scripts));
    let buffer = generator.generate(0, 0);
    assert!(buffer.loot.is_empty());
    let told = failures.lock().unwrap();
    assert!(
        told.len() == 1
            && told[0]
                .message
                .contains("isn't a loot table this generator fills"),
        "{told:?}"
    );
}

#[test]
fn the_chunks_cells_are_what_a_place_answers() {
    let compiled = compile(&scripted(""), SCRIPT);
    let (scripts, _) = one(
        "return { biome = function(x, y, z, area) if (x // 4 + y // 4) % 3 == 0 then return \"west\" end return area end }",
    );
    let generator = compiled.bind(2, -64, 320, Some(&scripts));
    let sampler = generator.sampler(None);
    let mut expected = Vec::new();
    let mut again = Vec::new();
    for x in (0..16).step_by(4) {
        for y in (-64..320).step_by(4) {
            expected.push(generator.point_area_at(16 + x, y, 32, &sampler));
            again.push(generator.point_area_at(16 + x + 3, y + 3, 34, &generator.sampler(None)));
        }
    }
    assert_eq!(expected, again);
    assert!(
        expected.contains(&1),
        "the biome stage chose the west somewhere"
    );
}
