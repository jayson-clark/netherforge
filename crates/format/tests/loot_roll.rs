//! Format's `LootTest`, its rolls: the roller on Kotlin's `Random`, so the
//! numbers it gives are the JVM's and JS's.

use std::collections::BTreeMap;

use netherforge_format::kind::loot::LootTableFile;
use netherforge_format::kind::loot_roll::{LootContext, LootDrop, NoContext, roll};
use netherforge_format::kind::recipe::Ingredient;
use netherforge_format::random::Random;

fn table(json: serde_json::Value) -> LootTableFile {
    serde_json::from_value(json).unwrap()
}

fn none(_: &str) -> Option<LootTableFile> {
    None
}

fn kinds(drops: &[LootDrop]) -> Vec<(String, i32)> {
    drops
        .iter()
        .map(|d| match d {
            LootDrop::Stack { item, count } => (item.kind.clone(), *count),
            other => panic!("not a stack: {other:?}"),
        })
        .collect()
}

fn rolled(file: &LootTableFile, context: &dyn LootContext, seed: i64) -> Vec<LootDrop> {
    roll(file, &none, context, &mut Random::new(seed)).unwrap()
}

#[derive(Default)]
struct Context {
    luck: f64,
    has_player: bool,
    tool: Option<&'static str>,
    enchantments: BTreeMap<&'static str, i32>,
}

impl LootContext for Context {
    fn luck(&self) -> f64 {
        self.luck
    }
    fn has_player(&self) -> bool {
        self.has_player
    }
    fn tool_is(&self, tool: &Ingredient) -> bool {
        matches!(tool, Ingredient::Kind(k) if Some(k.as_str()) == self.tool)
    }
    fn enchantment_level(&self, enchantment: &str) -> i32 {
        self.enchantments.get(enchantment).copied().unwrap_or(0)
    }
}

fn pairs(p: &[(&str, i32)]) -> Vec<(String, i32)> {
    p.iter().map(|(k, c)| (k.to_string(), *c)).collect()
}

#[test]
fn the_same_seed_gives_the_same_drops_and_picks_follow_the_weights() {
    let file = table(
        serde_json::json!({ "pools": { "main": { "rolls": 1, "entries": [
        { "type": "item", "item": { "kind": "minecraft:stick" }, "weight": 3 },
        { "type": "item", "item": { "kind": "minecraft:stone" }, "weight": 1 }
    ] } } }),
    );
    assert_eq!(rolled(&file, &NoContext, 42), rolled(&file, &NoContext, 42));
    let sticks = (0..4000)
        .filter(|&seed| kinds(&rolled(&file, &NoContext, seed))[0].0 == "minecraft:stick")
        .count();
    assert!(
        (2800..=3200).contains(&sticks),
        "about 3 in 4 picks are sticks: {sticks}"
    );
}

/// The numbers Kotlin's test pins on the JVM and in JS.
#[test]
fn a_seed_rolls_the_same_on_every_platform() {
    let file = table(
        serde_json::json!({ "pools": { "main": { "rolls": { "min": 2, "max": 5 }, "entries": [
        { "type": "item", "item": { "kind": "minecraft:stick" }, "count": { "min": 1, "max": 9 }, "weight": 2 },
        { "type": "item", "item": { "kind": "minecraft:stone" } },
        { "type": "empty" }
    ] } } }),
    );
    assert_eq!(
        kinds(&rolled(&file, &NoContext, 7)),
        pairs(&[
            ("minecraft:stick", 7),
            ("minecraft:stick", 9),
            ("minecraft:stone", 1)
        ])
    );
}

#[test]
fn rolls_counts_and_luck_follow_vanillas_rules() {
    let file = table(
        serde_json::json!({ "pools": { "main": { "rolls": 2, "bonusRolls": 1.0, "entries": [
        { "type": "item", "item": { "kind": "minecraft:stick" }, "count": 5 }
    ] } } }),
    );
    assert_eq!(rolled(&file, &NoContext, 1).len(), 2);
    let lucky = Context {
        luck: 3.5,
        ..Default::default()
    };
    assert_eq!(
        rolled(&file, &lucky, 1).len(),
        5,
        "2 rolls, and 3 more for 3.5 luck"
    );
    assert!(
        kinds(&rolled(&file, &NoContext, 1))
            .iter()
            .all(|(_, c)| *c == 5)
    );
    // Quality moves weight with luck: with enough bad luck an entry can't be picked at all.
    let unlucky = table(serde_json::json!({ "pools": { "main": { "entries": [
        { "type": "item", "item": { "kind": "minecraft:stick" }, "weight": 1, "quality": -1 },
        { "type": "item", "item": { "kind": "minecraft:stone" } }
    ] } } }));
    let one = Context {
        luck: 1.0,
        ..Default::default()
    };
    for seed in 0..20 {
        assert_eq!(
            kinds(&rolled(&unlucky, &one, seed)),
            pairs(&[("minecraft:stone", 1)])
        );
    }
    // A count of 0 gives nothing.
    let nothing = table(serde_json::json!({ "pools": { "main": { "entries": [
        { "type": "item", "item": { "kind": "minecraft:stick" }, "count": 0 }
    ] } } }));
    assert_eq!(rolled(&nothing, &NoContext, 1), []);
}

#[test]
fn conditions_decide_what_may_be_picked() {
    let file = table(serde_json::json!({ "pools": {
        "player": { "conditions": [{ "type": "player" }], "entries": [{ "type": "item", "item": { "kind": "minecraft:diamond" } }] },
        "tool": { "entries": [
            { "type": "item", "item": { "kind": "minecraft:emerald" }, "conditions": [{ "type": "tool", "tool": "minecraft:shears" }] },
            { "type": "item", "item": { "kind": "minecraft:string" }, "conditions": [{ "type": "tool", "tool": "minecraft:shears", "invert": true }] }
        ] },
        "touch": { "entries": [
            { "type": "item", "item": { "kind": "minecraft:glass" }, "conditions": [{ "type": "enchantment", "enchantment": "silk_touch", "level": 2 }] }
        ] }
    } }));
    assert_eq!(
        kinds(&rolled(&file, &NoContext, 1)),
        pairs(&[("minecraft:string", 1)])
    );
    let everything = Context {
        has_player: true,
        tool: Some("minecraft:shears"),
        enchantments: BTreeMap::from([("minecraft:silk_touch", 2)]),
        ..Default::default()
    };
    assert_eq!(
        kinds(&rolled(&file, &everything, 1)),
        pairs(&[
            ("minecraft:diamond", 1),
            ("minecraft:emerald", 1),
            ("minecraft:glass", 1)
        ])
    );
    // A level below the condition's doesn't pass.
    let weak = Context {
        enchantments: BTreeMap::from([("minecraft:silk_touch", 1)]),
        ..Default::default()
    };
    assert_eq!(
        kinds(&rolled(&file, &weak, 1)),
        pairs(&[("minecraft:string", 1)])
    );
    // A chance passes about as often as it says.
    let chance = table(serde_json::json!({ "pools": { "main": {
        "conditions": [{ "type": "chance", "chance": 0.25 }],
        "entries": [{ "type": "item", "item": { "kind": "minecraft:stick" } }]
    } } }));
    let hits = (0..4000)
        .filter(|&seed| !rolled(&chance, &NoContext, seed).is_empty())
        .count();
    assert!((850..=1150).contains(&hits), "about 1 in 4 rolls: {hits}");
}

#[test]
fn table_entries_roll_their_table_and_game_tables_are_handed_back() {
    let junk = table(serde_json::json!({ "pools": { "main": { "entries": [
        { "type": "item", "item": { "kind": "minecraft:stick" } }
    ] } } }));
    let file = table(serde_json::json!({ "pools": {
        "a": { "entries": [{ "type": "table", "table": "junk" }] },
        "b": { "entries": [{ "type": "vanilla", "table": "minecraft:chests/simple_dungeon" }] }
    } }));
    let find = |name: &str| (name == "junk").then(|| junk.clone());
    let drops = roll(&file, &find, &NoContext, &mut Random::new(3)).unwrap();
    assert_eq!(kinds(&drops[..1]), pairs(&[("minecraft:stick", 1)]));
    let LootDrop::Vanilla { table: name, .. } = &drops[1] else {
        panic!("a game table: {drops:?}")
    };
    assert_eq!(name, "minecraft:chests/simple_dungeon");
    let again = roll(&file, &find, &NoContext, &mut Random::new(3)).unwrap();
    assert_eq!(
        again[1], drops[1],
        "the game's roll gets a seed from the same draws"
    );
    assert!(roll(&file, &none, &NoContext, &mut Random::new(3)).is_err());
    // A cycle the project couldn't see stops instead of rolling forever.
    let looped = table(
        serde_json::json!({ "pools": { "main": { "entries": [{ "type": "table", "table": "loop" }] } } }),
    );
    let again = looped.clone();
    let error = roll(
        &looped,
        &move |_| Some(again.clone()),
        &NoContext,
        &mut Random::new(1),
    )
    .unwrap_err();
    assert!(error.contains("deep"), "{error}");
}
