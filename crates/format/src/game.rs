//! The game's facts a check asks for, from the live server's registries (or
//! the editor's cache of them): format's `GameData`, as far as the ported
//! checks need it. A project loads without it, and the checks that need it
//! say nothing then.

/// A registry of the game's.
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
}

use std::collections::BTreeMap;

pub trait GameData {
    /// The Minecraft version the facts are from.
    fn minecraft_version(&self) -> &str;
    /// Whether `registry` has `id` (namespaced).
    fn has(&self, registry: Registry, id: &str) -> bool;
    /// The ids `registry`'s tag `id` holds (namespaced, without `#`); `None` for a tag it doesn't have.
    fn tag(&self, registry: Registry, id: &str) -> Option<Vec<String>>;
    /// How much damage an item takes before it breaks; 0 for one that doesn't.
    fn item_durability(&self, id: &str) -> u32;
    /// What options a particle takes; `None` for one the game doesn't have.
    fn particle(&self, id: &str) -> Option<ParticleData>;
    /// A block's properties and the values each may be; `None` for a block the game doesn't have.
    fn block_properties(&self, id: &str) -> Option<BTreeMap<String, Vec<String>>>;
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

/// The pattern of a game id, namespaced or not (`GameIds.isValid`), for a field's rule.
pub const ID_PATTERN: &str = r"^([a-z0-9_.-]+:)?[a-z0-9_./-]+$";

/// Whether `id` is a game id, namespaced or not (`GameIds.isValid`).
pub fn is_id(id: &str) -> bool {
    crate::names::is_game_id(id)
}
