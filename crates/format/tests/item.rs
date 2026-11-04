use netherforge_format::game::{self, GameData, Registry};
use netherforge_format::item::{self, ItemDef};
use netherforge_format::{ProblemSink, rules};

struct Game;
impl GameData for Game {
    fn minecraft_version(&self) -> &str {
        "26.3"
    }
    fn has(&self, registry: Registry, id: &str) -> bool {
        matches!(
            (registry, id),
            (
                Registry::Item,
                "minecraft:bread" | "minecraft:diamond_sword"
            ) | (Registry::Enchantment, "minecraft:sharpness")
                | (Registry::Block, "minecraft:stone")
        )
    }
    fn tag(&self, _: Registry, id: &str) -> Option<Vec<String>> {
        (id == "minecraft:logs").then(|| vec!["minecraft:oak_log".into()])
    }
    fn item_durability(&self, id: &str) -> u32 {
        if id == "minecraft:diamond_sword" {
            1561
        } else {
            0
        }
    }
    fn particle(&self, _: &str) -> Option<game::ParticleData> {
        None
    }
    fn block_properties(&self, _: &str) -> Option<std::collections::BTreeMap<String, Vec<String>>> {
        None
    }
}

fn problems(json: &str, game: Option<&dyn GameData>) -> Vec<String> {
    let value: serde_json::Value = serde_json::from_str(json).unwrap();
    let item: ItemDef = serde_json::from_value(value.clone()).unwrap();
    let mut sink = ProblemSink::new("menus/shop/menu.json");
    rules::check::<ItemDef>(&value, &mut sink);
    item::check(&item, "$", &mut sink, game);
    sink.problems
        .iter()
        .map(|p| {
            format!(
                "{} {}: {}",
                p.code.as_deref().unwrap(),
                p.path.as_deref().unwrap(),
                p.message
            )
        })
        .collect()
}

#[test]
fn an_items_field_rules() {
    assert_eq!(
        problems(
            r##"{"kind":"minecraft:bread","count":120,"color":"red","enchantments":{"Sharp!":3,"minecraft:sharpness":300},
                "food":{"nutrition":-1,"saturation":1,"eatSeconds":0},"useEffects":{"speedMultiplier":2}}"##,
            None
        ),
        [
            // In the file's order.
            "item.count $.count: count must be from 1 to 99",
            "item.color $.color: color must be #RRGGBB",
            "item.enchantment $.enchantments[\"Sharp!\"]: \"Sharp!\" must be an enchantment id",
            "item.enchantment-level $.enchantments[\"minecraft:sharpness\"]: enchantments[\"minecraft:sharpness\"] must be from 1 to 255",
            "item.food $.food.nutrition: food.nutrition must be at least 0",
            "item.food $.food.eatSeconds: food.eatSeconds must be more than 0",
            "item.use-effects $.useEffects.speedMultiplier: useEffects.speedMultiplier must be from 0 to 1",
        ]
    );
}

#[test]
fn what_a_schema_cant_say() {
    assert_eq!(
        problems(r#"{"count":3}"#, None),
        [
            "item.kind $.kind: An item needs a kind, like \"minecraft:bread\", or an item: one of the project's own"
        ]
    );
    assert_eq!(
        problems(
            r##"{"kind":"minecraft:diamond_sword","maxStackSize":16,"count":20,"hold":{"animation":"tridnet"},"food":{"nutrition":1,"saturation":1},
                "attributeModifiers":[{"id":"a","attribute":"minecraft:speed","amount":1,"operation":"add_value"},{"id":"minecraft:a","attribute":"minecraft:speed","amount":1,"operation":"add_value"}],
                "canBreak":["minecraft:stone","minecraft:air","#minecraft:logs","#minecraft:none","ruby_ore"],
                "hiddenTooltipComponents":["painting_variant"]}"##,
            Some(&Game)
        ),
        [
            "item.max-stack-size-durable $.maxStackSize: An item with durability can't stack: maxStackSize must be 1",
            "item.count $.count: count can't be more than maxStackSize",
            "item.unknown-attribute $.attributeModifiers[0].attribute: Minecraft 26.3 has no attribute \"minecraft:speed\"",
            "item.unknown-attribute $.attributeModifiers[1].attribute: Minecraft 26.3 has no attribute \"minecraft:speed\"",
            "item.attribute-id $.attributeModifiers[1].id: Two modifiers have the id \"minecraft:a\"",
            "item.unknown-block $.canBreak[1]: Minecraft 26.3 has no block \"minecraft:air\"",
            "item.unknown-block $.canBreak[3]: Minecraft 26.3 has no block tag \"#minecraft:none\"",
            "item.tooltip-component $.hiddenTooltipComponents[0]: \"painting_variant\" isn't a tooltip line hiddenTooltipComponents can hide: did you mean \"painting/variant\"?",
            "item.hold-animation $.hold.animation: \"tridnet\" isn't a use animation: did you mean \"trident\"?",
            "item.hold-food $.hold: An item that's held can't be eaten too: hold and food both make it a consumable",
        ]
    );
}

#[test]
fn every_rule_names_its_problem() {
    assert_eq!(rules::uncoded::<ItemDef>(), Vec::<String>::new());
}

#[test]
fn an_items_data_is_written_sorted_however_deep() {
    let item: ItemDef = serde_json::from_str(
        r#"{"kind":"minecraft:bread","data":{"z":1,"a":{"y":true,"b":[{"d":1,"c":2}]}}}"#,
    )
    .unwrap();
    assert_eq!(
        serde_json::to_string(&item).unwrap(),
        r#"{"kind":"minecraft:bread","data":{"a":{"b":[{"c":2,"d":1}],"y":true},"z":1}}"#
    );
}

#[test]
fn a_project_items_look_is_every_field_it_shares_with_a_stack() {
    use netherforge_format::kind::item::ItemFile;
    // Every look field set, plus the file's own: `look()` carries exactly the look.
    let look = serde_json::json!({
        "kind": "minecraft:bread", "name": "n", "lore": ["l"], "enchantments": {"minecraft:sharpness": 2},
        "glint": true, "hideTooltip": true, "unbreakable": true, "itemModel": "ui/m", "tooltipStyle": "ui/t",
        "equipment": {"asset": "ui/e", "slot": "head"}, "color": "#aabbcc", "profile": "p", "maxStackSize": 1,
        "rarity": "rare", "attributeModifiers": [{"attribute": "minecraft:speed", "amount": 1.5, "operation": "add_value"}],
        "canBreak": ["minecraft:stone"], "canPlaceOn": ["minecraft:dirt"], "invisible": true,
        "hiddenTooltipComponents": ["lore"], "food": {"nutrition": 1, "saturation": 0.5},
        "cooldown": {"seconds": 2.5}, "hold": true, "useEffects": {"canSprint": true}
    });
    let mut file_json = look.clone();
    file_json["drawWithoutAmmo"] = true.into();
    file_json["script"] = serde_json::json!({"file": "item.lua"});
    let file: ItemFile = serde_json::from_value(file_json).unwrap();
    let schema = serde_json::to_value(schemars::schema_for!(ItemDef)).unwrap();
    let stack_only = ["item", "count", "damage", "map", "data"];
    let look_fields: Vec<&String> = schema["properties"]
        .as_object()
        .unwrap()
        .keys()
        .filter(|k| !stack_only.contains(&k.as_str()))
        .collect();
    assert_eq!(
        look_fields.len(),
        look.as_object().unwrap().len(),
        "the test sets every look field"
    );
    assert_eq!(serde_json::to_value(file.look()).unwrap(), look);
}
