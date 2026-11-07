//! What scripts do all the time, in steps of their budget (Kotlin's `ApiCostTest` and `CostGolden`), held to
//! `tests/data/scenario-costs.tsv`: vector maths, a handle's methods, item tables, JSON, events, a handler's way in,
//! subscribing among many others, spawning a scripted centity. A step is one of Luau's interrupts (each call and each
//! loop's turn) or work the runtime charged (`Host::charge`), so the numbers aren't Kotlin's Lua 5.4 instructions.
//!
//! A cost may move a little either way ([`allowance`]); further than that, either way, fails. Dearer is a regression
//! to look at; cheaper means the golden is out of date, and a golden left behind would let the next regression back
//! to the old cost through. So does a cost with no line in the golden and a line with no cost. `UPDATE_GOLDEN=1`
//! rewrites it; review the diff like any golden's.

mod support;
use support::*;

use std::collections::BTreeMap;
use std::rc::Rc;

use netherforge_luau::ManualClock;
use netherforge_runtime::session::Config;

const GOLDEN: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/tests/data/scenario-costs.tsv");

const SCRIPTED: &str = r#"{ "nodes": { "root": { "display": { "type": "block", "block": "minecraft:stone" }, "hitbox": {} } }, "script": { "file": "script.lua" } }"#;

/// Each scenario: its name and the Luau it runs, once to warm up and once counted.
const SCENARIOS: &[(&str, &str)] = &[
    // Vectors.
    ("vec3(x, y, z)", "local _ = vec3(1, 2, 3)"),
    ("v.x", "local _ = v.x"),
    ("v + w", "local _ = v + w"),
    ("v * 2", "local _ = v * 2"),
    ("v:length()", "local _ = v:length()"),
    ("v:distance(w)", "local _ = v:distance(w)"),
    // A handle's methods: its own, one up its chain, a Vec3 either way, a handle back.
    ("centity:id()", "local _ = centity:id()"),
    ("node:translation()", "local _ = node:translation()"),
    ("node:set_translation(v)", "node:set_translation(v)"),
    ("world:block(v)", "local _ = world:block(v)"),
    ("block:kind()", "local _ = block:kind()"),
    ("centity:data().hp", "local _ = centity:data().hp"),
    (
        "6 × 6 × 6 blocks, each world:block():is_solid()",
        "for x = 0, 5 do for y = 0, 5 do for z = 0, 5 do local _ = world:block(v + vec3(x, y, z)):is_solid() end end end",
    ),
    ("player:send_message(text)", "player:send_message('hi')"),
    // Hand-written functions.
    ("nf.math.clamp", "local _ = nf.math.clamp(1, 2, 3)"),
    ("random:integer(1, 6)", "local _ = random:integer(1, 6)"),
    ("random:number()", "local _ = random:number()"),
    // Item tables.
    (
        "set_item({ kind, count })",
        "inventory:set_item(0, { kind = 'minecraft:bread', count = 4 })",
    ),
    ("set_item(a rich item)", "inventory:set_item(1, rich)"),
    (
        "set_item(an item with data)",
        "inventory:set_item(2, { kind = 'minecraft:bread', data = { owner = 'Alex', tier = 2, tags = { 'a', 'b' }, at = 1.5 } })",
    ),
    ("inventory:item(i)", "local _ = inventory:item(1)"),
    (
        "set_item(a stack read back)",
        "inventory:set_item(3, stack)",
    ),
    // JSON.
    (
        "nf.json.encode(a small table)",
        "local _ = nf.json.encode({ a = 1, b = 'x', c = { 1, 2 } })",
    ),
    (
        "nf.json.encode(floats)",
        "local _ = nf.json.encode({ a = 1.5, b = { 0.1, 2.25 } })",
    ),
    (
        "nf.json.encode(a typed value)",
        "local _ = nf.json.encode({ at = v })",
    ),
    (
        "nf.json.decode(a small object)",
        r#"local _ = nf.json.decode('{"a":1,"b":"x","c":[1,2]}')"#,
    ),
    // Events.
    (
        "nf.emit, nobody listening",
        "nf.emit('cost:none', { a = 1 })",
    ),
    ("nf.emit, one listening", "nf.emit('cost:one', { a = 1 })"),
    (
        "centity:emit, nobody listening",
        "centity:emit('cost:none', { a = 1 })",
    ),
    ("nf.emit, ten listening", "nf.emit('cost:ten', { a = 1 })"),
    (
        "nf.emit, a handler reading three fields",
        "nf.emit('cost:read', { a = 1, b = 2, c = 3 })",
    ),
    ("nf.on + cancel", "nf.on('tick', f):cancel()"),
    (
        "nf.on + cancel, among 100 others",
        "nf.on('cost:many', f):cancel()",
    ),
    ("nf.after + cancel", "nf.after(5, f):cancel()"),
    // Scopes.
    (
        "spawn a scripted centity",
        "nf.centities.spawn('scripted', v)",
    ),
    (
        "spawn a centity with no script",
        "nf.centities.spawn('plain', v)",
    ),
];

const SETUP: &str = r#"
local v, w = vec3(1, 64, 2), vec3(3, 65, 4)
local world = nf.worlds.default()
local centity = nf.centities.spawn("scripted", vec3(0, 64, 0))
local node = centity:node("root")
local block = world:block(v)
local random = nf.random.new(1)
local player = world:players()[1]
local inventory = player:inventory()
local rich = {
  kind = "minecraft:diamond_sword", name = "Blade", lore = { "sharp", "old" },
  enchantments = { ["minecraft:sharpness"] = 5 }, unbreakable = true,
  data = { owner = "Alex", tier = 2, tags = { "a", "b" }, nested = { level = 1, done = false } },
}
inventory:set_item(1, rich)
local stack = inventory:item(1)
local function f() end
for _ = 1, 100 do
  nf.on("cost:many", f)
end
nf.on("cost:one", f)
for _ = 1, 10 do
  nf.on("cost:ten", f)
end
nf.on("cost:read", function(event)
  local _ = event.a + event.b + event.c
end)
local function cost(fn)
  local before = nf.instructions_left()
  fn()
  return before - nf.instructions_left()
end
local base = cost(function() end)
"#;

/// How far a cost may move from its golden before it fails.
fn allowance(golden: i64) -> i64 {
    (golden / 10).max(3)
}

fn quoted(text: &str) -> String {
    format!("\"{}\"", text.replace('\\', "\\\\").replace('"', "\\\""))
}

/// The module: the setup, each scenario run twice and its second run's cost printed, and a tick handler saying what
/// its call had used by its first line (a module's calls get `MODULE_BUDGET` steps).
fn module() -> String {
    let mut lua = SETUP.to_owned();
    lua.push_str("local scenarios = {\n");
    for (name, body) in SCENARIOS {
        lua.push_str(&format!(
            "  {{ {}, function() {body} end }},\n",
            quoted(name)
        ));
    }
    lua.push_str(
        r#"}
for _, scenario in ipairs(scenarios) do
  scenario[2]()
  print("COST", scenario[1], cost(scenario[2]) - base)
end
local said = false
nf.on("tick", function()
  local left = nf.instructions_left()
  if said then return end
  said = true
  print("COST", "a tick handler, at its first line", BUDGET - left)
end)
"#,
    );
    lua
}

#[test]
fn what_scripts_do_all_the_time_costs_what_its_golden_says() {
    let platform = Rc::new(Fake::default());
    *platform.player.borrow_mut() = Some(place(0.0, 64.0, 0.0));
    let budget = netherforge_runtime::session::MODULE_BUDGET;
    let module = module().replace("BUDGET", &budget.to_string());
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("centities/scripted/centity.json", SCRIPTED),
        ("centities/scripted/script.lua", "-- nothing"),
        (
            "centities/plain/centity.json",
            r#"{ "nodes": { "root": {} } }"#,
        ),
        ("modules/t/init.lua", &module),
    ]);
    // A clock that never moves: no call runs out of time, however slow the build.
    let session = Session::start_configured(
        platform.clone(),
        &files,
        &NoPackages,
        Rc::new(netherforge_runtime::store::Store::in_memory()),
        Config {
            clock: Some(Rc::new(ManualClock::new())),
            ..Config::default()
        },
    );
    session.tick();
    let lines = platform.take();
    let errors: Vec<&String> = lines
        .iter()
        .filter(|l| l.starts_with("warn") || l.starts_with("error"))
        .collect();
    assert!(errors.is_empty(), "{errors:?}");
    let costs: BTreeMap<String, i64> = lines
        .iter()
        .filter_map(|l| l.split_once("COST\t"))
        .map(|(_, rest)| {
            let rest = rest.rsplit_once(" (modules/").map_or(rest, |(r, _)| r);
            let (name, cost) = rest.rsplit_once('\t').expect("a name and a cost");
            (name.to_owned(), cost.parse().expect("a whole number"))
        })
        .collect();
    assert_eq!(costs.len(), SCENARIOS.len() + 1, "{lines:?}");
    check(&costs);
}

fn check(costs: &BTreeMap<String, i64>) {
    let header = "# Steps what scripts do all the time costs them (crates/runtime/tests/costs.rs). UPDATE_GOLDEN=1 rewrites it.\n";
    if std::env::var("UPDATE_GOLDEN").as_deref() == Ok("1") {
        let mut text = header.to_owned();
        for (name, cost) in costs {
            text.push_str(&format!("{name}\t{cost}\n"));
        }
        std::fs::create_dir_all(std::path::Path::new(GOLDEN).parent().unwrap()).unwrap();
        std::fs::write(GOLDEN, text).unwrap();
        return;
    }
    let text = std::fs::read_to_string(GOLDEN)
        .unwrap_or_else(|_| panic!("{GOLDEN} is missing: run with UPDATE_GOLDEN=1 to write it"));
    let golden: BTreeMap<&str, i64> = text
        .lines()
        .filter(|l| !l.trim().is_empty() && !l.starts_with('#'))
        .map(|l| {
            let (name, cost) = l.rsplit_once('\t').expect("a name and a cost");
            (name, cost.parse().expect("a whole number"))
        })
        .collect();
    let mut wrong = Vec::new();
    for (name, &cost) in costs {
        match golden.get(name.as_str()) {
            None => wrong.push(format!("{name} costs {cost}: not in the golden")),
            Some(&expected) if cost > expected + allowance(expected) => {
                wrong.push(format!("{name} costs {cost}, up from {expected}"))
            }
            Some(&expected) if cost < expected - allowance(expected) => {
                wrong.push(format!("{name} costs {cost}, down from {expected}"))
            }
            _ => {}
        }
    }
    for name in golden.keys() {
        if !costs.contains_key(*name) {
            wrong.push(format!("{name} is in the golden but wasn't measured"));
        }
    }
    assert!(
        wrong.is_empty(),
        "{} costs differ from {GOLDEN} (UPDATE_GOLDEN=1 rewrites it, once each is understood):\n{}",
        wrong.len(),
        wrong.join("\n")
    );
}
