//! An item stack as a project describes it (a menu's slot, a loot entry, a
//! recipe's result, a project item's look): format's `ItemDef`. Its simple
//! rules are its fields'; `check` is what a schema can't say.

use schemars::JsonSchema;
use serde::{Deserialize, Serialize};

use crate::canonical::{Free, Map};
use crate::game::{self, GameData, Registry};
use crate::json::child_path;
use crate::problem::{ProblemSink, codes};

/// An enchantment's level: 1 to 255.
#[derive(Clone, Copy, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(transparent)]
#[schemars(extend("minimum" = 1, "maximum" = 255, "x-problem" = "item.enchantment-level"))]
pub struct EnchantmentLevel(pub i32);

/// How an item looks and behaves, the fields a stack and a project item's
/// file share, declared once: `item_with_look!` lays them, with their rules,
/// where a struct says `..look,` among its own fields (a stack's count, a
/// project item's `$schema` and script).
macro_rules! item_with_look {
    ($(#[$attr:meta])* pub struct $name:ident {
        $($(#[$before_attr:meta])* pub $before:ident: $before_ty:ty,)*
        ..look,
        $($(#[$field_attr:meta])* pub $field:ident: $ty:ty,)*
    }) => {
        $(#[$attr])*
        #[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize, JsonSchema)]
        #[serde(rename_all = "camelCase", deny_unknown_fields)]
        pub struct $name {
            $($(#[$before_attr])* pub $before: $before_ty,)*
            /// The game's item it is (`minecraft:bread`).
            #[serde(default, skip_serializing_if = "String::is_empty")]
            pub kind: String,
            #[serde(skip_serializing_if = "Option::is_none")]
            pub name: Option<String>,
            #[serde(skip_serializing_if = "Option::is_none")]
            pub lore: Option<Vec<String>>,
            /// Enchantments by id, each with its level.
            #[serde(skip_serializing_if = "Option::is_none")]
            #[schemars(extend("propertyNames" = {
                "pattern": game::ID_PATTERN,
                "x-problem": "item.enchantment",
                "x-rule": "an enchantment id"
            }))]
            pub enchantments: Option<Map<EnchantmentLevel>>,
            #[serde(skip_serializing_if = "Option::is_none")]
            pub glint: Option<bool>,
            #[serde(skip_serializing_if = "Option::is_none")]
            pub hide_tooltip: Option<bool>,
            #[serde(skip_serializing_if = "Option::is_none")]
            pub unbreakable: Option<bool>,
            #[serde(skip_serializing_if = "Option::is_none")]
            pub item_model: Option<String>,
            #[serde(skip_serializing_if = "Option::is_none")]
            pub tooltip_style: Option<String>,
            #[serde(skip_serializing_if = "Option::is_none")]
            pub equipment: Option<EquipmentDef>,
            #[serde(skip_serializing_if = "Option::is_none")]
            #[schemars(extend("pattern" = crate::rules::RGB, "x-problem" = "item.color", "x-rule" = "#RRGGBB"))]
            pub color: Option<String>,
            #[serde(skip_serializing_if = "Option::is_none")]
            pub profile: Option<String>,
            #[serde(skip_serializing_if = "Option::is_none")]
            #[schemars(range(min = 1, max = 99), extend("x-problem" = "item.max-stack-size"))]
            pub max_stack_size: Option<i32>,
            #[serde(skip_serializing_if = "Option::is_none")]
            pub rarity: Option<ItemRarity>,
            #[serde(skip_serializing_if = "Option::is_none")]
            pub attribute_modifiers: Option<Vec<AttributeModifierDef>>,
            #[serde(skip_serializing_if = "Option::is_none")]
            pub can_break: Option<Vec<String>>,
            #[serde(skip_serializing_if = "Option::is_none")]
            pub can_place_on: Option<Vec<String>>,
            #[serde(skip_serializing_if = "Option::is_none")]
            pub invisible: Option<bool>,
            #[serde(skip_serializing_if = "Option::is_none")]
            pub hidden_tooltip_components: Option<Vec<String>>,
            #[serde(skip_serializing_if = "Option::is_none")]
            pub food: Option<FoodDef>,
            #[serde(skip_serializing_if = "Option::is_none")]
            pub cooldown: Option<CooldownDef>,
            #[serde(skip_serializing_if = "Option::is_none")]
            pub hold: Option<Hold>,
            #[serde(skip_serializing_if = "Option::is_none")]
            pub use_effects: Option<UseEffectsDef>,
            $($(#[$field_attr])* pub $field: $ty,)*
        }

        impl $name {
            /// Its look, as a stack has it (a project item's file is the look of its stacks).
            pub fn look(&self) -> ItemDef {
                ItemDef {
                    kind: self.kind.clone(),
                    name: self.name.clone(),
                    lore: self.lore.clone(),
                    enchantments: self.enchantments.clone(),
                    glint: self.glint,
                    hide_tooltip: self.hide_tooltip,
                    unbreakable: self.unbreakable,
                    item_model: self.item_model.clone(),
                    tooltip_style: self.tooltip_style.clone(),
                    equipment: self.equipment.clone(),
                    color: self.color.clone(),
                    profile: self.profile.clone(),
                    max_stack_size: self.max_stack_size,
                    rarity: self.rarity,
                    attribute_modifiers: self.attribute_modifiers.clone(),
                    can_break: self.can_break.clone(),
                    can_place_on: self.can_place_on.clone(),
                    invisible: self.invisible,
                    hidden_tooltip_components: self.hidden_tooltip_components.clone(),
                    food: self.food.clone(),
                    cooldown: self.cooldown.clone(),
                    hold: self.hold.clone(),
                    use_effects: self.use_effects.clone(),
                    ..ItemDef::default()
                }
            }
        }
    };
}

pub(crate) use item_with_look;

item_with_look! {
    /// An item stack: its look, and what's the stack's own.
    pub struct ItemDef {
        ..look,
        /// A project item: its stack is that item's.
        #[serde(skip_serializing_if = "Option::is_none")]
        pub item: Option<String>,
        #[serde(skip_serializing_if = "Option::is_none")]
        #[schemars(range(min = 1, max = 99), extend("x-problem" = "item.count"))]
        pub count: Option<i32>,
        #[serde(skip_serializing_if = "Option::is_none")]
        #[schemars(range(min = 0), extend("x-problem" = "item.damage"))]
        pub damage: Option<i32>,
        #[serde(skip_serializing_if = "Option::is_none")]
        #[schemars(range(min = 0), extend("x-problem" = "item.map"))]
        pub map: Option<i32>,
        /// What scripts keep on the stack.
        #[serde(skip_serializing_if = "Option::is_none")]
        pub data: Option<Map<Free>>,
    }
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct EquipmentDef {
    pub asset: String,
    pub slot: EquipSlot,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "lowercase")]
pub enum EquipSlot {
    Head,
    Chest,
    Legs,
    Feet,
    Body,
    Saddle,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "lowercase")]
pub enum ItemRarity {
    Common,
    Uncommon,
    Rare,
    Epic,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct AttributeModifierDef {
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(regex(pattern = r"^([a-z0-9_.-]+:)?[a-z0-9_./-]+$"), extend("x-problem" = "item.attribute-id", "x-rule" = "a namespaced id"))]
    pub id: Option<String>,
    #[schemars(regex(pattern = r"^([a-z0-9_.-]+:)?[a-z0-9_./-]+$"), extend("x-problem" = "item.attribute", "x-rule" = "an attribute id"))]
    pub attribute: String,
    pub amount: f64,
    pub operation: AttributeOperation,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub slot: Option<AttributeSlot>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum AttributeOperation {
    AddValue,
    AddMultipliedBase,
    AddMultipliedTotal,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum AttributeSlot {
    Any,
    Hand,
    MainHand,
    OffHand,
    Armor,
    Head,
    Chest,
    Legs,
    Feet,
    Body,
    Saddle,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct FoodDef {
    #[schemars(range(min = 0), extend("x-problem" = "item.food"))]
    pub nutrition: i32,
    #[schemars(range(min = 0), extend("x-problem" = "item.food"))]
    pub saturation: f64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub can_always_eat: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(extend("exclusiveMinimum" = 0, "x-problem" = "item.food"))]
    pub eat_seconds: Option<f64>,
}

/// A cooldown after using an item.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct CooldownDef {
    /// How long, more than 0.
    #[schemars(extend("exclusiveMinimum" = 0, "x-problem" = "item.cooldown"))]
    pub seconds: f64,
    /// Items sharing a group share a cooldown: a namespaced id.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(regex(pattern = r"^([a-z0-9_.-]+:)?[a-z0-9_./-]+$"), extend("x-problem" = "item.cooldown-group", "x-rule" = "a namespaced id"))]
    pub group: Option<String>,
}

/// Holding the item to use it: `true`, or `{ "animation": "trident" }`.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(untagged)]
pub enum Hold {
    On(bool),
    Animation(HoldAnimation),
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct HoldAnimation {
    pub animation: String,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct UseEffectsDef {
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 0, max = 1), extend("x-problem" = "item.use-effects"))]
    pub speed_multiplier: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub can_sprint: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub interact_vibrations: Option<bool>,
}

/// The tooltip lines `hiddenTooltipComponents` can hide.
pub const TOOLTIP_COMPONENTS: &[&str] = &[
    "attribute_modifiers",
    "banner_patterns",
    "bees",
    "block_entity_data",
    "block_state",
    "bundle_contents",
    "can_break",
    "can_place_on",
    "charged_projectiles",
    "container",
    "container_loot",
    "damage",
    "dyed_color",
    "enchantments",
    "entity_data",
    "firework_explosion",
    "fireworks",
    "instrument",
    "intangible_projectile",
    "jukebox_playable",
    "lore",
    "map_id",
    "ominous_bottle_amplifier",
    "painting/variant",
    "pot_decorations",
    "potion_contents",
    "profile",
    "sign_text_back",
    "sign_text_front",
    "stored_enchantments",
    "sulfur_cube_content",
    "suspicious_stew_effects",
    "trim",
    "tropical_fish/pattern",
    "unbreakable",
    "written_book_content",
];

/// Every use animation, in the game's order.
pub const USE_ANIMATIONS: &[&str] = &[
    "none",
    "eat",
    "drink",
    "block",
    "bow",
    "trident",
    "crossbow",
    "spyglass",
    "toot_horn",
    "brush",
    "bundle",
    "spear",
];

fn edit_distance(a: &str, b: &str) -> usize {
    let b: Vec<char> = b.chars().collect();
    let mut row: Vec<usize> = (0..=b.len()).collect();
    for (i, ca) in a.chars().enumerate() {
        let mut next = vec![i + 1; b.len() + 1];
        for j in 1..=b.len() {
            next[j] = (row[j] + 1)
                .min(next[j - 1] + 1)
                .min(row[j - 1] + usize::from(ca != b[j - 1]));
        }
        row = next;
    }
    row[b.len()]
}

/// The animation `id` was probably meant to be.
fn nearest_animation(id: &str) -> Option<&'static str> {
    let written = id
        .to_lowercase()
        .trim_start_matches("minecraft:")
        .replace(['-', ' '], "_");
    if let Some(exact) = USE_ANIMATIONS.iter().find(|a| **a == written) {
        return Some(exact);
    }
    USE_ANIMATIONS
        .iter()
        .map(|a| (*a, edit_distance(a, &written)))
        .filter(|(a, d)| *d <= 2.min(a.len() / 3))
        .min_by_key(|(_, d)| *d)
        .map(|(a, _)| a)
}

/// What an item's fields' rules can't say (`Rules.item`): ids the game must
/// have, a kind or a project item, a durable item that can't stack, ids two
/// modifiers share, and names near enough to say which was meant.
pub fn check(item: &ItemDef, at: &str, sink: &mut ProblemSink, game: Option<&dyn GameData>) {
    let id_valid = |id: &str| game::is_id(id);
    if item.kind.is_empty() {
        // A project item's kind is its definition's, checked where the project can see it.
        if item.item.is_none() {
            sink.report(
                codes::ITEM_KIND,
                "An item needs a kind, like \"minecraft:bread\", or an item: one of the project's own",
                Some(format!("{at}.kind")),
            );
        }
    } else if !id_valid(&item.kind) {
        sink.report(
            codes::ITEM_KIND,
            format!("\"{}\" isn't an item id", item.kind),
            Some(format!("{at}.kind")),
        );
    } else if let Some(game) = game
        && !game.has(Registry::Item, &game::normalize(&item.kind))
    {
        sink.report(
            codes::ITEM_UNKNOWN,
            format!(
                "Minecraft {} has no item \"{}\"",
                game.minecraft_version(),
                game::normalize(&item.kind)
            ),
            Some(format!("{at}.kind")),
        );
    }
    if let Some(game) = game {
        for (id, _) in item.enchantments.iter().flat_map(|e| e.iter()) {
            if id_valid(&id.0) && !game.has(Registry::Enchantment, &game::normalize(&id.0)) {
                sink.report(
                    codes::ITEM_UNKNOWN_ENCHANTMENT,
                    format!(
                        "Minecraft {} has no enchantment \"{}\"",
                        game.minecraft_version(),
                        game::normalize(&id.0)
                    ),
                    Some(child_path(&format!("{at}.enchantments"), &id.0)),
                );
            }
        }
    }
    if let Some(size) = item.max_stack_size
        && size > 1
        && (1..=99).contains(&size)
    {
        let durable = item.damage.is_some()
            || (!item.kind.is_empty()
                && game.is_some_and(|g| g.item_durability(&game::normalize(&item.kind)) > 0));
        if durable {
            sink.report(
                codes::ITEM_MAX_STACK_SIZE_DURABLE,
                "An item with durability can't stack: maxStackSize must be 1",
                Some(format!("{at}.maxStackSize")),
            );
        }
    }
    if let (Some(count), Some(size)) = (item.count, item.max_stack_size)
        && count > size
    {
        sink.report(
            codes::ITEM_COUNT,
            "count can't be more than maxStackSize",
            Some(format!("{at}.count")),
        );
    }
    if let Some(modifiers) = &item.attribute_modifiers {
        if let Some(game) = game {
            for (index, m) in modifiers.iter().enumerate() {
                if id_valid(&m.attribute)
                    && !game.has(Registry::Attribute, &game::normalize(&m.attribute))
                {
                    sink.report(
                        codes::ITEM_UNKNOWN_ATTRIBUTE,
                        format!(
                            "Minecraft {} has no attribute \"{}\"",
                            game.minecraft_version(),
                            game::normalize(&m.attribute)
                        ),
                        Some(format!("{at}.attributeModifiers[{index}].attribute")),
                    );
                }
            }
        }
        // Defaults are made distinct by their index; whatever the namespace, only a given id can clash.
        let ids: Vec<String> = modifiers
            .iter()
            .enumerate()
            .map(|(k, m)| {
                m.id.as_deref()
                    .map_or_else(|| format!("#{k}"), game::normalize)
            })
            .collect();
        for (index, id) in ids.iter().enumerate() {
            if ids.iter().position(|it| it == id) != Some(index) {
                sink.report(
                    codes::ITEM_ATTRIBUTE_ID,
                    format!("Two modifiers have the id \"{id}\""),
                    Some(format!("{at}.attributeModifiers[{index}].id")),
                );
            }
        }
    }
    for (field, blocks) in [
        ("canBreak", &item.can_break),
        ("canPlaceOn", &item.can_place_on),
    ] {
        for (index, block) in blocks.iter().flatten().enumerate() {
            adventure_block(block, &format!("{at}.{field}[{index}]"), sink, game);
        }
    }
    for (index, line) in item.hidden_tooltip_components.iter().flatten().enumerate() {
        let path = format!("{at}.hiddenTooltipComponents[{index}]");
        if !TOOLTIP_COMPONENTS.contains(&line.as_str()) {
            let bare = line.trim_start_matches("minecraft:").replace('/', "_");
            let meant = TOOLTIP_COMPONENTS
                .iter()
                .find(|c| c.replace('/', "_") == bare);
            let hint = meant.map_or_else(
                || "the item docs list the ones there are".to_owned(),
                |m| format!("did you mean \"{m}\"?"),
            );
            sink.report(
                codes::ITEM_TOOLTIP_COMPONENT,
                format!("\"{line}\" isn't a tooltip line hiddenTooltipComponents can hide: {hint}"),
                Some(path),
            );
        } else if let Some(game) = game
            && !game.has(Registry::DataComponentType, &format!("minecraft:{line}"))
        {
            sink.report(
                codes::ITEM_UNKNOWN_TOOLTIP_COMPONENT,
                format!(
                    "Minecraft {} has no \"{line}\" component, so hiddenTooltipComponents has no line of it to hide",
                    game.minecraft_version()
                ),
                Some(path),
            );
        }
    }
    if let Some(Hold::Animation(HoldAnimation { animation })) = &item.hold
        && !USE_ANIMATIONS.contains(&animation.as_str())
    {
        let hint = nearest_animation(animation).map_or_else(
            || format!("it's one of {}", USE_ANIMATIONS.join(", ")),
            |m| format!("did you mean \"{m}\"?"),
        );
        sink.report(
            codes::ITEM_HOLD_ANIMATION,
            format!("\"{animation}\" isn't a use animation: {hint}"),
            Some(format!("{at}.hold.animation")),
        );
    }
    let held = matches!(item.hold, Some(Hold::On(true)) | Some(Hold::Animation(_)));
    if held && item.food.is_some() {
        sink.report(
            codes::ITEM_HOLD_FOOD,
            "An item that's held can't be eaten too: hold and food both make it a consumable",
            Some(format!("{at}.hold")),
        );
    }
}

/// A block an adventure-mode item may break or be placed on: a project
/// block or tag (a reference, checked across resources), `*`, or the game's.
fn adventure_block(block: &str, path: &str, sink: &mut ProblemSink, game: Option<&dyn GameData>) {
    let tag = block.starts_with('#');
    let id = block.trim_start_matches('#');
    let namespace = id.split_once(':').map(|(n, _)| n);
    // `*`, and the project's own (references): only the game's are checked here.
    let is_game = block == "*"
        || if tag {
            namespace.is_none_or(|n| n == game::NAMESPACE)
        } else {
            namespace == Some(game::NAMESPACE)
        };
    if block == "*" || !is_game {
        return;
    }
    if !game::is_id(id) {
        sink.report(
            codes::ITEM_BLOCK,
            format!(
                "\"{block}\" isn't a block: write a project block (ruby_ore), the game's ({}:stone), a #tag of blocks, or * for any block",
                game::NAMESPACE
            ),
            Some(path.to_owned()),
        );
        return;
    }
    let Some(game) = game else { return };
    let normal = game::normalize(id);
    let missing = if tag {
        !game.has_tag(Registry::Block, &normal)
    } else {
        !game.has(Registry::Block, &normal)
    };
    if missing {
        let what = if tag {
            format!("tag \"#{normal}\"")
        } else {
            format!("\"{normal}\"")
        };
        sink.report(
            codes::ITEM_UNKNOWN_BLOCK,
            format!("Minecraft {} has no block {what}", game.minecraft_version()),
            Some(path.to_owned()),
        );
    }
}
