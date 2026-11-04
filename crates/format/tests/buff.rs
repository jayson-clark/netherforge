//! Buffs' rules, worded from their schema.

use netherforge_format::document::load;
use netherforge_format::kind::buff::BuffFile;

fn problems(text: &str) -> Vec<String> {
    load::<BuffFile>(text, "buffs/x.json")
        .1
        .iter()
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
fn a_buffs_rules_are_its_fields() {
    assert_eq!(
        problems(r##"{"color":"red","maxTicks":0,"stats":{"Defense":1,"move":0.5}}"##),
        [
            "buff.color $.color: color must be #rrggbb",
            "buff.ticks $.maxTicks: maxTicks must be at least 1",
            "buff.stat $.stats: \"Defense\" must be a stat name: lowercase letters, digits and _",
        ]
    );
    assert!(problems(r##"{"color":"#aabbcc","maxTicks":20,"stats":{"defense":8}}"##).is_empty());
}

#[test]
fn its_schema_says_its_rules_to_the_editor() {
    let schema = serde_json::to_value(schemars::schema_for!(BuffFile)).unwrap();
    let ticks = &schema["properties"]["maxTicks"];
    assert_eq!(ticks["minimum"], 1);
    assert_eq!(ticks["x-problem"], "buff.ticks");
    assert_eq!(
        schema["properties"]["color"]["pattern"],
        "^#[0-9a-fA-F]{6}$"
    );
}

#[test]
fn every_rule_names_its_problem() {
    assert_eq!(
        netherforge_format::rules::uncoded::<BuffFile>(),
        Vec::<String>::new()
    );
}
