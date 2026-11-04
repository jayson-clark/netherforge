//! `biomes/<id>.json`: a biome's climate, colours, particle, sounds, spawns,
//! spawn costs and features. Format's `BiomeFile`, `BiomeKind` and
//! `BiomeValidator`: its ranges, colours and ids' forms are their fields'
//! rules; the game's registries and what depends on several fields are
//! checks. (Its datapack entry comes with the start-up datapack.)

use std::collections::HashSet;

use schemars::JsonSchema;
use serde::{Deserialize, Serialize};

use crate::canonical::{Entries, Map, name_of};
use crate::document::{Context, Document};
use crate::game::{self, GameData, Registry};
use crate::json::child_path;
use crate::manifest::SpawnCategory;
use crate::problem::{ProblemCode, ProblemSink, codes};
use crate::worldgen::{GenerationStep, SpawnRange};

fn is_default<T: Default + PartialEq>(value: &T) -> bool {
    *value == T::default()
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct BiomeFile {
    #[serde(rename = "$schema", skip_serializing_if = "Option::is_none")]
    pub schema: Option<String>,
    #[serde(default, skip_serializing_if = "is_default")]
    pub climate: BiomeClimate,
    #[serde(default, skip_serializing_if = "is_default")]
    pub colors: BiomeColors,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub particle: Option<AmbientParticle>,
    #[serde(default, skip_serializing_if = "is_default")]
    pub sounds: BiomeSounds,
    #[serde(default, skip_serializing_if = "Entries::is_empty")]
    pub spawns: Entries<SpawnCategory, Vec<BiomeSpawn>>,
    #[serde(default, skip_serializing_if = "Entries::is_empty")]
    #[schemars(extend("propertyNames" = {
        "pattern": game::ID_PATTERN,
        "x-problem": "biome.spawn-cost",
        "x-rule": "an entity type id, like \"minecraft:…\""
    }))]
    pub spawn_costs: Map<SpawnCost>,
    /// The placed features in each step: the game's (`minecraft:…`) or the project's.
    #[serde(default, skip_serializing_if = "Entries::is_empty")]
    #[schemars(extend("x-ref" = "placed_feature"))]
    pub features: Entries<GenerationStep, Vec<String>>,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct BiomeClimate {
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = -2, max = 2), extend("default" = 0.8, "x-problem" = "biome.climate"))]
    pub temperature: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 0, max = 1), extend("default" = 0.4, "x-problem" = "biome.climate"))]
    pub downfall: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(extend("default" = true))]
    pub precipitation: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub temperature_modifier: Option<TemperatureModifier>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum TemperatureModifier {
    None,
    Frozen,
}

/// A biome colour field: `#rrggbb`.
macro_rules! biome_colors {
    ($($(#[$m:meta])* $field:ident),* $(,)?) => {
        #[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize, JsonSchema)]
        #[serde(rename_all = "camelCase", deny_unknown_fields)]
        pub struct BiomeColors {
            $(
                $(#[$m])*
                #[serde(skip_serializing_if = "Option::is_none")]
                #[schemars(extend("pattern" = crate::rules::RGB, "x-problem" = "biome.color", "x-rule" = "a colour written #rrggbb, like \"#3f76e4\""))]
                pub $field: Option<String>,
            )*
            #[serde(skip_serializing_if = "Option::is_none")]
            pub grass_modifier: Option<GrassModifier>,
        }
    };
}

biome_colors! {
    sky,
    fog,
    #[schemars(extend("default" = "#3f76e4"))]
    water,
    water_fog,
    grass,
    foliage,
    dry_foliage,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum GrassModifier {
    None,
    DarkForest,
    Swamp,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct AmbientParticle {
    #[schemars(extend("pattern" = game::ID_PATTERN, "x-problem" = "biome.particle", "x-rule" = "a particle id, like \"minecraft:…\""))]
    pub particle: String,
    #[schemars(range(max = 1), extend("exclusiveMinimum" = 0, "x-problem" = "biome.particle"))]
    pub probability: f64,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct BiomeSounds {
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(extend("pattern" = game::ID_PATTERN, "x-problem" = "biome.sound", "x-rule" = "a sound event id, like \"minecraft:…\""))]
    pub ambient: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub mood: Option<MoodSound>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub additions: Option<AdditionsSound>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub music: Option<BiomeMusic>,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct MoodSound {
    #[schemars(extend("pattern" = game::ID_PATTERN, "x-problem" = "biome.sound", "x-rule" = "a sound event id, like \"minecraft:…\""))]
    pub sound: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 1), extend("default" = 6000, "x-problem" = "biome.sound"))]
    pub tick_delay: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 0), extend("default" = 8, "x-problem" = "biome.sound"))]
    pub block_search_extent: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 0), extend("default" = 2, "x-problem" = "biome.sound"))]
    pub offset: Option<f64>,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct AdditionsSound {
    #[schemars(extend("pattern" = game::ID_PATTERN, "x-problem" = "biome.sound", "x-rule" = "a sound event id, like \"minecraft:…\""))]
    pub sound: String,
    #[schemars(range(min = 0, max = 1), extend("x-problem" = "biome.sound"))]
    pub chance: f64,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct BiomeMusic {
    #[schemars(extend("pattern" = game::ID_PATTERN, "x-problem" = "biome.sound", "x-rule" = "a sound event id, like \"minecraft:…\""))]
    pub sound: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 0), extend("default" = 12000, "x-problem" = "biome.sound"))]
    pub min_delay: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(extend("default" = 24000))]
    pub max_delay: Option<i32>,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct BiomeSpawn {
    #[schemars(extend("pattern" = game::ID_PATTERN, "x-problem" = "biome.spawn", "x-rule" = "an entity type id, like \"minecraft:…\""))]
    pub entity: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 1), extend("default" = 10, "x-problem" = "biome.spawn"))]
    pub weight: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub group: Option<SpawnRange>,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SpawnCost {
    pub charge: f64,
    pub energy_budget: f64,
}

/// What the game's registry must have, when the game is known.
fn known(
    id: &str,
    what: &str,
    registry: Registry,
    code: ProblemCode,
    at: String,
    sink: &mut ProblemSink,
    game: Option<&dyn GameData>,
) {
    if let Some(game) = game
        && game::is_id(id)
        && game.lacks(registry, &game::normalize(id))
    {
        sink.report(
            code,
            format!(
                "Minecraft {} has no {what} \"{}\"",
                game.minecraft_version(),
                game::normalize(id)
            ),
            Some(at),
        );
    }
}

/// The registry a biome is in, as a datapack's folder.
pub const FOLDER: &str = "worldgen/biome";

impl Document for BiomeFile {
    /// Its file in the game's format; none while a datapack's feature it names isn't in the pack.
    fn datapack(
        running: &[(crate::reference::ResourceKey, &Self)],
        cx: &crate::startup::Context,
    ) -> Vec<(String, crate::startup::Entry)> {
        use crate::startup::biome;
        running
            .iter()
            .filter(|(_, file)| biome::writes(file, cx))
            .map(|(key, file)| {
                (
                    format!("data/{}/{}/{}.json", key.namespace, biome::FOLDER, key.path),
                    crate::startup::Entry::json(&biome::of(file, cx)),
                )
            })
            .collect()
    }

    fn datapack_entries(&self, id: &str) -> Vec<(&'static str, String)> {
        vec![(FOLDER, id.to_owned())]
    }

    fn cross_check(&self, cx: &crate::project::Across, sink: &mut ProblemSink) {
        self.across(cx, sink);
    }

    const SCHEMA: &'static str = "../.netherforge/schema/biome.schema.json";

    fn set_schema(&mut self, schema: Option<String>) {
        self.schema = schema;
    }

    fn validate(&self, cx: &Context, sink: &mut ProblemSink) {
        let game = cx.game;
        if let Some(particle) = &self.particle {
            known(
                &particle.particle,
                "particle",
                Registry::ParticleType,
                codes::BIOME_PARTICLE,
                "$.particle.particle".into(),
                sink,
                game,
            );
            if let Some(options) = game
                .and_then(|g| g.particle(&game::normalize(&particle.particle)))
                .filter(|d| *d != game::ParticleData::None)
            {
                let options = name_of(&options);
                sink.report(
                    codes::BIOME_PARTICLE,
                    format!("\"{}\" takes options ({options}), which a biome's particle can't give: use one that takes none", particle.particle),
                    Some("$.particle.particle".into()),
                );
            }
        }
        let sounds = &self.sounds;
        let sound = |id: &str, at: &str, sink: &mut ProblemSink| {
            known(
                id,
                "sound event",
                Registry::SoundEvent,
                codes::BIOME_SOUND,
                at.into(),
                sink,
                game,
            )
        };
        if let Some(ambient) = &sounds.ambient {
            sound(ambient, "$.sounds.ambient", sink);
        }
        if let Some(mood) = &sounds.mood {
            sound(&mood.sound, "$.sounds.mood.sound", sink);
        }
        if let Some(additions) = &sounds.additions {
            sound(&additions.sound, "$.sounds.additions.sound", sink);
        }
        if let Some(music) = &sounds.music {
            sound(&music.sound, "$.sounds.music.sound", sink);
            if let Some(max) = music.max_delay
                && max < music.min_delay.unwrap_or(12000)
            {
                sink.report(
                    codes::BIOME_SOUND,
                    "maxDelay is below minDelay",
                    Some("$.sounds.music.maxDelay".into()),
                );
            }
        }
        for (category, spawns) in self.spawns.iter() {
            let at = child_path("$.spawns", &name_of(category));
            for (index, spawn) in spawns.iter().enumerate() {
                let at = format!("{at}[{index}]");
                known(
                    &spawn.entity,
                    "entity type",
                    Registry::EntityType,
                    codes::BIOME_SPAWN,
                    format!("{at}.entity"),
                    sink,
                    game,
                );
                if let Some(group) = &spawn.group {
                    if group.min.unwrap_or(1) < 1 || group.max.unwrap_or(1) < 1 {
                        sink.report(
                            codes::BIOME_SPAWN,
                            "A group is at least 1",
                            Some(format!("{at}.group")),
                        );
                    } else if let (Some(min), Some(max)) = (group.min, group.max)
                        && min > max
                    {
                        sink.report(
                            codes::BIOME_SPAWN,
                            "The group's min is above its max",
                            Some(format!("{at}.group")),
                        );
                    }
                }
            }
        }
        for (entity, cost) in self.spawn_costs.iter() {
            let at = child_path("$.spawnCosts", &entity.0);
            known(
                &entity.0,
                "entity type",
                Registry::EntityType,
                codes::BIOME_SPAWN_COST,
                at.clone(),
                sink,
                game,
            );
            if cost.charge <= 0.0 || cost.energy_budget <= 0.0 {
                sink.report(
                    codes::BIOME_SPAWN_COST,
                    "A spawn cost's charge and energyBudget are each more than 0",
                    Some(at),
                );
            }
        }
        for (step, features) in self.features.iter() {
            let at = child_path("$.features", &step.id());
            let mut seen = HashSet::new();
            for (index, feature) in features.iter().enumerate() {
                let at = format!("{at}[{index}]");
                // The game's are checked here; the project's (and packages') are references.
                let of_game = feature
                    .split_once(':')
                    .is_some_and(|(n, _)| n == game::NAMESPACE);
                let shaped = if of_game {
                    if !game::is_id(feature) {
                        sink.report(
                            codes::BIOME_FEATURE,
                            format!(
                                "\"{feature}\" isn't a placed feature id, like \"minecraft:…\""
                            ),
                            Some(at.clone()),
                        );
                        false
                    } else {
                        known(
                            feature,
                            "placed feature",
                            Registry::PlacedFeature,
                            codes::BIOME_FEATURE,
                            at.clone(),
                            sink,
                            game,
                        );
                        true
                    }
                } else {
                    true
                };
                if shaped && !seen.insert(feature.as_str()) {
                    sink.report(
                        codes::BIOME_FEATURE,
                        format!("\"{feature}\" is already in {}", step.id()),
                        Some(at),
                    );
                }
            }
        }
    }
}

impl BiomeFile {
    /// Its features in each step in an order no other biome contradicts: the game can't place both otherwise.
    fn across(&self, cx: &crate::project::Across, sink: &mut ProblemSink) {
        let references = cx.references();
        let keys = |features: &[String]| -> Vec<String> {
            features
                .iter()
                .map(|f| {
                    references
                        .resolve(crate::reference::RefKind::PlacedFeature, f)
                        .map_or_else(|| f.clone(), |k| k.to_string())
                })
                .collect()
        };
        for (step, features) in self.features.iter() {
            let mine = keys(features);
            let at = crate::json::child_path("$.features", &crate::canonical::name_of(step));
            for (other, file) in cx.models::<BiomeFile>() {
                if other == cx.id() {
                    continue;
                }
                let theirs = keys(file.features.get(step).map_or(&[][..], |f| f));
                let Some((first, second)) = inversion(&mine, &theirs) else {
                    continue;
                };
                sink.problems.push(
                    crate::codes::BIOME_FEATURE_ORDER
                        .at(
                            &sink.file,
                            format!("\"{first}\" comes before \"{second}\" here, but after it in biome \"{other}\": the game can't place both in one world, so list them in the same order"),
                            Some(at.clone()),
                        )
                        .with_related(vec![crate::Location {
                            file: cx.path_of("biome", other),
                            path: Some(at.clone()),
                        }]),
                );
                break;
            }
        }
    }
}

/// Two of `mine` that `theirs` has the other way round, the first such pair.
fn inversion(mine: &[String], theirs: &[String]) -> Option<(String, String)> {
    let at: std::collections::HashMap<&str, usize> = theirs
        .iter()
        .enumerate()
        .map(|(i, f)| (f.as_str(), i))
        .collect();
    for (i, first) in mine.iter().enumerate() {
        let Some(a) = at.get(first.as_str()) else {
            continue;
        };
        for second in &mine[i + 1..] {
            if at.get(second.as_str()).is_some_and(|b| b < a) {
                return Some((first.clone(), second.clone()));
            }
        }
    }
    None
}

impl BiomeSpawn {
    /// Its group's size: at least 1, and its max never below its min.
    pub fn group_range(&self) -> (i32, i32) {
        let min = self.group.as_ref().and_then(|g| g.min).unwrap_or(1);
        let max = self
            .group
            .as_ref()
            .and_then(|g| g.max)
            .unwrap_or(min)
            .max(min);
        (min, max)
    }
}

/// The name the server knows a biome by, as a file in namespace `home` writes it: a tag with the game's
/// namespace, the game's own as it is, the project's in full (`ruby_grove` in `showcase` is `showcase:ruby_grove`).
pub fn key_of(text: &str, home: &str) -> Option<String> {
    if let Some(tag) = text.strip_prefix('#') {
        return Some(format!("#{}", game::normalize(tag)));
    }
    if crate::reference::RefKind::Biome.is_game(text) {
        return Some(text.to_owned());
    }
    crate::reference::resolve_any(home, text).map(|k| k.to_string())
}
