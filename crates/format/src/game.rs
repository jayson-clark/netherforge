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

pub trait GameData {
    /// The Minecraft version the facts are from.
    fn minecraft_version(&self) -> &str;
    /// Whether `registry` has `id` (namespaced).
    fn has(&self, registry: Registry, id: &str) -> bool;
    /// Whether the block tag `id` exists (namespaced, without `#`).
    fn has_tag(&self, registry: Registry, id: &str) -> bool;
    /// How much damage an item takes before it breaks; 0 for one that doesn't.
    fn item_durability(&self, id: &str) -> u32;
    /// The options a particle takes (`dust`'s colour, `block`'s state), by their kind's name;
    /// `None` for one that takes none or isn't known.
    fn particle_options(&self, id: &str) -> Option<String>;
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
