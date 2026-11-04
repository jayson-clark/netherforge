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
    project::load(&names, &read, game)
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
