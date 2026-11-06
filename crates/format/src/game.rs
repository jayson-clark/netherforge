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

use indexmap::IndexMap;

use crate::math::BoundingBox;

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
    /// The resource pack format the server's game reads, `[major, minor]`.
    fn resource_pack_format(&self) -> Option<[i32; 2]> {
        None
    }
    /// Each map colour by its index, `0xAARRGGBB` (alpha 0 for an index that draws nothing): the server's
    /// own table. `None` when the facts don't say.
    fn map_colors(&self) -> Option<&[u32]> {
        None
    }

    /// A block state's collision boxes (`state` its canonical string, `id` its block, whose boxes stand in
    /// for a state the facts don't list), each in the block's own unit cube; `None` when the facts don't say.
    fn collision_boxes(&self, _state: &str, _id: &str) -> Option<Vec<crate::math::BoundingBox>> {
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
#[cfg_attr(feature = "specta", derive(specta::Type))]
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

/// A block's properties: each one's allowed values, in the game's order, and the values the game places it with.
#[derive(Clone, Debug, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
pub struct BlockInfo {
    #[serde(default, skip_serializing_if = "IndexMap::is_empty")]
    pub properties: IndexMap<String, Vec<String>>,
    #[serde(default, skip_serializing_if = "IndexMap::is_empty")]
    pub defaults: IndexMap<String, String>,
}

/// The shape the export writes today (`GameDataBundle::schema`).
pub const BUNDLE_SCHEMA: i32 = 3;

fn bundle_schema() -> i32 {
    BUNDLE_SCHEMA
}

/// A `GameData` backed by plain data (format's `GameDataBundle`): what the dev server exports
/// (`export_game_data`), the editor's per-user cache file, or a test fixture.
///
/// `registries` and `tags` hold every registry and tag the server has. A registry missing from them is one the
/// data doesn't know (`registry` answers `None`), which is how a fixture leaves out what a test doesn't need.
/// Collision and model boxes are keyed by the block state's canonical string
/// (`minecraft:oak_stairs[facing=east,half=bottom,...]`, properties sorted); a state with no entry falls back to
/// the block's bare id. `schema` says which shape wrote it: a change to the shape or to what the export puts in
/// it raises `BUNDLE_SCHEMA`, and the editor exports its cache again rather than read one of another shape.
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct GameDataBundle {
    pub minecraft: String,
    /// Each registry's name → its ids, sorted.
    #[serde(default, skip_serializing_if = "IndexMap::is_empty")]
    pub registries: IndexMap<String, BTreeSet<String>>,
    /// Each registry's name → its tags (without `#`) → what each holds, nested tags resolved, sorted.
    #[serde(default, skip_serializing_if = "IndexMap::is_empty")]
    pub tags: IndexMap<String, IndexMap<String, BTreeSet<String>>>,
    #[serde(default, skip_serializing_if = "IndexMap::is_empty")]
    pub blocks: IndexMap<String, BlockInfo>,
    #[serde(default, skip_serializing_if = "IndexMap::is_empty")]
    pub collision: IndexMap<String, Vec<BoundingBox>>,
    /// The boxes a block state is drawn from (client-only; the editor fills it in).
    #[serde(default, skip_serializing_if = "IndexMap::is_empty")]
    pub models: IndexMap<String, Vec<BoundingBox>>,
    #[serde(default, skip_serializing_if = "IndexMap::is_empty")]
    pub glyph_advances: IndexMap<String, i32>,
    /// The resource pack format, `[major, minor]`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub pack_format: Option<Vec<i32>>,
    /// The data pack format, `[major, minor]`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub data_pack_format: Option<Vec<i32>>,
    /// Particle id → its data kind.
    #[serde(default, skip_serializing_if = "IndexMap::is_empty")]
    pub particles: IndexMap<String, ParticleData>,
    /// Each map colour by its index, `0xAARRGGBB` (written as Kotlin's signed `Int`s).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub map_colors: Option<Vec<i32>>,
    #[serde(default = "bundle_schema")]
    pub schema: i32,
    /// `map_colors` as `u32`s, made when the bundle is read.
    #[serde(skip)]
    #[cfg_attr(feature = "specta", specta(skip))]
    map_colors_u32: Option<Vec<u32>>,
}

impl GameDataBundle {
    /// An empty bundle of `minecraft`'s, of today's shape.
    pub fn new(minecraft: &str) -> GameDataBundle {
        GameDataBundle {
            minecraft: minecraft.into(),
            registries: IndexMap::new(),
            tags: IndexMap::new(),
            blocks: IndexMap::new(),
            collision: IndexMap::new(),
            models: IndexMap::new(),
            glyph_advances: IndexMap::new(),
            pack_format: None,
            data_pack_format: None,
            particles: IndexMap::new(),
            map_colors: None,
            schema: BUNDLE_SCHEMA,
            map_colors_u32: None,
        }
    }

    /// `text` as a bundle, its map colours ready.
    pub fn parse(text: &str) -> Result<GameDataBundle, serde_json::Error> {
        let mut bundle: GameDataBundle = serde_json::from_str(text)?;
        bundle.prepare();
        Ok(bundle)
    }

    /// Makes what's worked out from the fields (map colours as `u32`s); `parse` does it.
    pub fn prepare(&mut self) {
        self.map_colors_u32 = self
            .map_colors
            .as_ref()
            .map(|c| c.iter().map(|&v| v as u32).collect());
    }

    /// The collision boxes of a block state (its canonical string), or of its block (`id`), when known.
    pub fn collision_boxes(&self, state: &str, id: &str) -> Option<&[BoundingBox]> {
        self.collision
            .get(state)
            .or_else(|| self.collision.get(id))
            .map(Vec::as_slice)
    }

    /// The boxes a block state is drawn from, or its block's, when known.
    pub fn model_boxes(&self, state: &str, id: &str) -> Option<&[BoundingBox]> {
        self.models
            .get(state)
            .or_else(|| self.models.get(id))
            .map(Vec::as_slice)
    }
}

impl GameData for GameDataBundle {
    fn minecraft_version(&self) -> &str {
        &self.minecraft
    }
    fn registry(&self, key: &str) -> Option<&BTreeSet<String>> {
        self.registries.get(key)
    }
    fn tag(&self, key: &str, id: &str) -> Option<&BTreeSet<String>> {
        self.tags.get(key)?.get(id)
    }
    /// Cached game data doesn't know durability: checks that need it say nothing.
    fn item_durability(&self, _id: &str) -> u32 {
        0
    }
    fn particle(&self, id: &str) -> Option<ParticleData> {
        self.particles.get(id).copied()
    }
    fn block_properties(&self, id: &str) -> Option<BTreeMap<String, Vec<String>>> {
        self.blocks.get(id).map(|b| {
            b.properties
                .iter()
                .map(|(k, v)| (k.clone(), v.clone()))
                .collect()
        })
    }
    fn block_default(&self, id: &str, property: &str) -> Option<String> {
        self.blocks.get(id)?.defaults.get(property).cloned()
    }
    fn data_pack_format(&self) -> Option<[i32; 2]> {
        match self.data_pack_format.as_deref()? {
            [major, minor, ..] => Some([*major, *minor]),
            [major] => Some([*major, 0]),
            [] => None,
        }
    }
    fn resource_pack_format(&self) -> Option<[i32; 2]> {
        match self.pack_format.as_deref()? {
            [major, minor, ..] => Some([*major, *minor]),
            [major] => Some([*major, 0]),
            [] => None,
        }
    }
    fn map_colors(&self) -> Option<&[u32]> {
        self.map_colors_u32.as_deref()
    }
    fn collision_boxes(&self, state: &str, id: &str) -> Option<Vec<BoundingBox>> {
        GameDataBundle::collision_boxes(self, state, id).map(<[BoundingBox]>::to_vec)
    }
}

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
