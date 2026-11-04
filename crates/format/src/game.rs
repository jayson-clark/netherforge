//! The game's facts a check asks for, from the live server's registries (or
//! the editor's cache of them): format's `GameData`, as far as the ported
//! checks need it. A project loads without it, and the checks that need it
//! say nothing then.

use std::collections::{BTreeMap, BTreeSet};

/// A registry of the game's the checks name often; any other is named by its
/// key (`minecraft:worldgen/placed_feature`).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Registry {
    Item,
    Block,
    Enchantment,
    Attribute,
    DataComponentType,
    LootTable,
    TriggerType,
    EntityType,
    ParticleType,
    PlacedFeature,
    SoundEvent,
    Biome,
}

impl Registry {
    /// Its key, as the game names it: `minecraft:worldgen/biome`.
    pub fn key(self) -> &'static str {
        match self {
            Registry::Item => "minecraft:item",
            Registry::Block => "minecraft:block",
            Registry::Enchantment => "minecraft:enchantment",
            Registry::Attribute => "minecraft:attribute",
            Registry::DataComponentType => "minecraft:data_component_type",
            Registry::LootTable => "minecraft:loot_table",
            Registry::TriggerType => "minecraft:trigger_type",
            Registry::EntityType => "minecraft:entity_type",
            Registry::ParticleType => "minecraft:particle_type",
            Registry::PlacedFeature => "minecraft:worldgen/placed_feature",
            Registry::SoundEvent => "minecraft:sound_event",
            Registry::Biome => "minecraft:worldgen/biome",
        }
    }
}

pub trait GameData {
    /// The Minecraft version the facts are from.
    fn minecraft_version(&self) -> &str;
    /// The ids (namespaced) in the registry of `key`; `None` for one the facts don't have.
    fn registry(&self, key: &str) -> Option<&BTreeSet<String>>;
    /// The ids the registry of `key`'s tag `id` holds (namespaced, without `#`); `None` for a tag it doesn't have.
    fn tag(&self, key: &str, id: &str) -> Option<&BTreeSet<String>>;
    /// How much damage an item takes before it breaks; 0 for one that doesn't.
    fn item_durability(&self, id: &str) -> u32;
    /// What options a particle takes; `None` for one the game doesn't have.
    fn particle(&self, id: &str) -> Option<ParticleData>;
    /// A block's properties and the values each may be; `None` for a block the game doesn't have.
    fn block_properties(&self, id: &str) -> Option<BTreeMap<String, Vec<String>>>;
    /// A block's default value of `property`; `None` when the facts don't say.
    fn block_default(&self, _id: &str, _property: &str) -> Option<String> {
        None
    }
    /// The data pack format the server reads, `[major, minor]`.
    fn data_pack_format(&self) -> Option<[i32; 2]> {
        None
    }

    /// Whether `registry` has `id` (namespaced).
    fn has(&self, registry: Registry, id: &str) -> bool {
        self.registry(registry.key())
            .is_some_and(|r| r.contains(id))
    }

    /// Whether the facts know `registry` and it hasn't got `id`: what a check reports on.
    fn lacks(&self, registry: Registry, id: &str) -> bool {
        self.registry(registry.key())
            .is_some_and(|r| !r.contains(id))
    }

    /// `registry`'s tag `id`, as `tag` gives it.
    fn tag_of(&self, registry: Registry, id: &str) -> Option<&BTreeSet<String>> {
        self.tag(registry.key(), id)
    }
}

/// What a particle type is: which options a spawn of it carries (Paper's `Particle.dataType`).
#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ParticleData {
    /// No options: flame, end rod, flash.
    None,
    /// A colour and a size.
    Dust,
    /// A colour, the colour it fades to, and a size.
    DustTransition,
    /// A colour: entity effects, tinted leaves.
    Color,
    /// A block state: block, falling dust, block markers.
    Block,
    /// An item stack.
    Item,
    /// Options of another kind (a vibration's path, a shriek's delay).
    Other,
}

pub const NAMESPACE: &str = "minecraft";

/// `stone` → `minecraft:stone`; an id with a namespace is left alone.
pub fn normalize(id: &str) -> String {
    if id.contains(':') {
        id.to_owned()
    } else {
        format!("{NAMESPACE}:{id}")
    }
}

/// Whether a reference that may be the project's names the game's instead
/// (format's `RefKind.isGame`): `minecraft:…`, or a `#tag` in no other namespace.
pub fn is_games(text: &str) -> bool {
    match text.strip_prefix('#') {
        Some(tag) => tag.split_once(':').is_none_or(|(n, _)| n == NAMESPACE),
        None => text.split_once(':').is_some_and(|(n, _)| n == NAMESPACE),
    }
}

/// The pattern of a game id, namespaced or not (`GameIds.isValid`), for a field's rule.
pub const ID_PATTERN: &str = r"^([a-z0-9_.-]+:)?[a-z0-9_./-]+$";

/// Whether `id` is a game id, namespaced or not (`GameIds.isValid`).
pub fn is_id(id: &str) -> bool {
    crate::names::is_game_id(id)
}
