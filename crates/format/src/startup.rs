//! The datapack NetherForge makes as the server starts (format's
//! `StartupDatapack` and `DatapackContext`): what the game reads from its
//! registries only while it loads them (advancements, the dialogs of the pause
//! screen and quick actions, structures that generate, biomes, dimension types,
//! skies' twin biomes), which no API adds to a running server. A change to
//! anything in it needs a restart, so the server builds it again after a reload
//! to tell whether one is needed.
//!
//! Built from the project's files alone, never from game data (there's none
//! that early), so building it again from the same files gives the same bytes.
//! Each kind says what it puts in through `Document::datapack`; the writers of
//! the game's formats are this module's.

use std::cell::RefCell;
use std::collections::{BTreeMap, BTreeSet, HashMap};

use serde_json::{Map, Value, json};

use crate::kind::datapack::PackFormat;
use crate::project::Project;
use crate::reference::{self, RefKind, ResourceKey};

/// What the server knows the pack by.
pub const ID: &str = "netherforge";
const MCMETA: &str = "pack.mcmeta";

/// One file of the start-up datapack.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Entry {
    /// A file written out: JSON, as text.
    Text(String),
    /// A project file copied in as it is (a structure's `.nbt`), by project or package path.
    Copy(String),
}

impl Entry {
    /// `value` as a file: one way of printing it, so the same JSON is always the same bytes.
    pub fn json(value: &Value) -> Entry {
        Entry::Text(crate::json::print(value) + "\n")
    }
}

/// MiniMessage as the game's JSON text (what a datapack's text fields hold),
/// from the server's own serializer, so it's the form the running version
/// reads. `glyph` answers a `<glyph:…>` tag's reference with its character.
pub trait TextJson {
    fn json(&self, mini: &str, glyph: &dyn Fn(&str) -> Option<String>) -> Value;
}

/// What the game's own data pack has that a kind builds on: its biomes (each
/// its JSON, by key) and the overworld's dimension type. None for the editor or a test.
#[derive(Clone, Debug, Default)]
pub struct GameDatapack {
    pub biomes: Vec<(ResourceKey, Value)>,
    pub overworld: Option<Value>,
}

/// As it crosses from the server: `{"biomes": {"minecraft:plains": {...}}, "overworld": {...}}`.
impl serde::Serialize for GameDatapack {
    fn serialize<S: serde::Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        let biomes: Map<String, Value> = self
            .biomes
            .iter()
            .map(|(key, biome)| (key.to_string(), biome.clone()))
            .collect();
        json!({ "biomes": biomes, "overworld": self.overworld.clone().unwrap_or(Value::Null) })
            .serialize(serializer)
    }
}

impl<'de> serde::Deserialize<'de> for GameDatapack {
    fn deserialize<D: serde::Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        let value = Value::deserialize(deserializer)?;
        let biomes = value
            .get("biomes")
            .and_then(Value::as_object)
            .into_iter()
            .flatten()
            .filter_map(|(key, biome)| {
                let (namespace, path) = key.split_once(':')?;
                biome.is_object().then(|| {
                    (
                        ResourceKey {
                            namespace: namespace.to_owned(),
                            path: path.to_owned(),
                        },
                        biome.clone(),
                    )
                })
            })
            .collect();
        let overworld = value.get("overworld").filter(|o| o.is_object()).cloned();
        Ok(GameDatapack { biomes, overworld })
    }
}

/// What a kind's writer can ask while the pack is built.
pub struct Context<'a> {
    pub project: &'a Project<'a>,
    /// The server's data pack format, `[major, minor]`.
    pub format: [i32; 2],
    text: &'a dyn TextJson,
    /// The server's main world's name, when the pack is built for a server that says.
    pub main_world: Option<&'a str>,
    /// Whether the project's datapacks go in.
    pub pass_through: bool,
    pub game: &'a GameDatapack,
    leave_out: &'a dyn Fn(&ResourceKey) -> bool,
    passed: RefCell<HashMap<String, BTreeSet<ResourceKey>>>,
    biomes: RefCell<Option<BTreeSet<ResourceKey>>>,
}

impl<'a> Context<'a> {
    /// The project's namespace: what a running resource's name and its references resolve in.
    pub fn home(&self) -> &str {
        self.project.namespace()
    }

    /// A running resource's name (`first_steps`, `acme:quest`) as its key.
    pub fn key(&self, name: &str) -> ResourceKey {
        reference::resolve_any(self.home(), name)
            .unwrap_or_else(|| panic!("\"{name}\" isn't a resource's name"))
    }

    /// `text` as the key it names; a running resource's references are written so they resolve here.
    pub fn resolve(&self, text: &str) -> ResourceKey {
        reference::resolve_any(self.home(), text)
            .unwrap_or_else(|| panic!("\"{text}\" isn't a reference"))
    }

    /// Whether the server's format is `major` (and `minor`) or later.
    pub fn format_at_least(&self, major: i32, minor: i32) -> bool {
        let [have, have_minor] = self.format;
        have > major || (have == major && have_minor >= minor)
    }

    pub fn pack_format(&self) -> PackFormat {
        PackFormat::of(self.format)
    }

    /// Whether the sky `sky` names is left out of this pack.
    pub fn left_out(&self, sky: &ResourceKey) -> bool {
        (self.leave_out)(sky)
    }

    /// The entries of `registry` (`worldgen/placed_feature`) the running datapacks put in this pack, for this
    /// format: none without `pass_through`.
    pub fn passed_through(&self, registry: &str) -> BTreeSet<ResourceKey> {
        if let Some(known) = self.passed.borrow().get(registry) {
            return known.clone();
        }
        let mut out = BTreeSet::new();
        if self.pass_through {
            for (_, datapack) in self
                .project
                .compiled::<crate::kind::datapack::CompiledDatapack>()
            {
                for (_, path) in datapack.files_for(self.pack_format()) {
                    if !path.tag && path.registry == registry {
                        out.insert(ResourceKey {
                            namespace: path.namespace.clone(),
                            path: path.path.clone(),
                        });
                    }
                }
            }
        }
        self.passed
            .borrow_mut()
            .insert(registry.to_owned(), out.clone());
        out
    }

    /// Every biome this pack has: the project's (and its packages') that go in it, and the datapacks'. A
    /// structure naming any other would make the game refuse the pack.
    pub fn biomes(&self) -> BTreeSet<ResourceKey> {
        if let Some(known) = &*self.biomes.borrow() {
            return known.clone();
        }
        let mut out: BTreeSet<ResourceKey> = self
            .project
            .running::<crate::kind::biome::BiomeFile>()
            .into_iter()
            .filter(|(_, file)| biome::writes(file, self))
            .map(|(name, _)| self.key(&name))
            .collect();
        out.extend(self.passed_through(biome::FOLDER));
        *self.biomes.borrow_mut() = Some(out.clone());
        out
    }

    /// MiniMessage `text` as the game's JSON text, its glyph tags drawn as the project's packs build them.
    pub fn text(&self, text: &str) -> Value {
        self.text.json(text, &|reference| self.glyph(reference))
    }

    /// The character a `<glyph:…>` tag's reference draws, from the packs as they compiled.
    fn glyph(&self, reference: &str) -> Option<String> {
        let key = reference::resolve_any(self.home(), reference)
            .filter(|k| RefKind::Glyph.is_path(&k.path))?;
        let pack = ResourceKey {
            namespace: key.namespace.clone(),
            path: key.pack().to_owned(),
        };
        let compiled = self
            .project
            .compiled_resource_packs
            .get(&pack.relative_to(self.home()))?;
        compiled.glyphs.get(key.key()).map(|g| g.char.clone())
    }
}

/// Every file of the pack, by path, sorted: `pack.mcmeta` with `format` and
/// each running resource's files, the packages' in their own namespaces.
/// Empty when no resource puts anything in it: then there's no pack. Without
/// `pass_through`, the project's datapacks are left out; the skies `leave_out`
/// says yes to have no twins in it.
pub fn build(
    project: &Project,
    format: [i32; 2],
    text: &dyn TextJson,
    main_world: Option<&str>,
    pass_through: bool,
    game: &GameDatapack,
    leave_out: &dyn Fn(&ResourceKey) -> bool,
) -> BTreeMap<String, Entry> {
    let cx = Context {
        project,
        format,
        text,
        main_world,
        pass_through,
        game,
        leave_out,
        passed: Default::default(),
        biomes: Default::default(),
    };
    let mut files = BTreeMap::new();
    for kind in crate::kinds::ALL {
        let Some(document) = crate::kind::document_kind(kind.id) else {
            continue;
        };
        for (path, entry) in document.datapack(&cx) {
            assert!(
                files.insert(path.clone(), entry).is_none(),
                "two resources put {path} in the start-up datapack"
            );
        }
    }
    if files.is_empty() {
        return files;
    }
    let name = project
        .manifest
        .as_ref()
        .map(|m| m.name.as_str())
        .filter(|n| !n.trim().is_empty())
        .unwrap_or(project.namespace());
    let mcmeta = json!({ "pack": { "description": format!("NetherForge: {name}"), "min_format": format, "max_format": format } });
    files.insert(MCMETA.to_owned(), Entry::json(&mcmeta));
    files
}

/// The project (or package) file that puts the entry `element` (`showcase:rocks`) of the game's `registry`
/// (`minecraft:worldgen/placed_feature`) in the pack for a server of `format`: a datapack's file, or a
/// resource that writes it; none when nothing does. Where the server's own complaint about an entry belongs.
pub fn source_of(
    project: &Project,
    format: [i32; 2],
    registry: &str,
    element: &str,
) -> Option<String> {
    let folder = registry.split_once(':').map_or(registry, |(_, f)| f);
    let key = reference::resolve_any(project.namespace(), element)?;
    if folder == biome::FOLDER
        && let Some(sky) = sky::sky_of(project.namespace(), &key)
    {
        return project
            .everywhere("sky")
            .into_iter()
            .find_map(|(name, resource)| {
                (reference::resolve_any(project.namespace(), &name).as_ref() == Some(&sky)).then(
                    || {
                        resource
                            .found
                            .kind
                            .path_of(&resource.found.id, &resource.found.group)
                    },
                )
            });
    }
    let target =
        crate::kind::datapack::layout::entry_file(&key.namespace, &key.path, folder, false);
    for (name, datapack) in project.compiled::<crate::kind::datapack::CompiledDatapack>() {
        if let Some((_, path)) = datapack
            .files_for(PackFormat::of(format))
            .into_iter()
            .find(|(t, _)| *t == target)
        {
            return Some(format!(
                "{}/{}",
                project.place_of("datapack", &name),
                path.file
            ));
        }
    }
    for kind in crate::kinds::ALL {
        for (name, resource) in project.everywhere(kind.id) {
            let Some(own) = reference::resolve_any(project.namespace(), &name) else {
                continue;
            };
            if own.namespace != key.namespace {
                continue;
            }
            let id = name.rsplit(':').next().unwrap_or(&name);
            let writes = resource.document.as_ref().is_some_and(|d| {
                d.model
                    .datapack_entries(id)
                    .iter()
                    .any(|(f, p)| *f == folder && *p == key.path)
            });
            if writes {
                return Some(
                    resource
                        .found
                        .kind
                        .path_of(&resource.found.id, &resource.found.group),
                );
            }
        }
    }
    None
}

/// `entries` of a kind's running resources, by key, for its writer.
pub fn keyed<'m, T>(cx: &Context, running: Vec<(String, &'m T)>) -> Vec<(ResourceKey, &'m T)> {
    let mut out: Vec<(ResourceKey, &T)> = running
        .into_iter()
        .map(|(name, value)| (cx.key(&name), value))
        .collect();
    out.sort_by_key(|(key, _)| key.to_string());
    out
}

/// A Kotlin `Float` written as JSON: the shortest digits that are that float.
fn float(x: f64) -> Value {
    let shortest: f64 = format!("{}", x as f32)
        .parse()
        .expect("a float prints as a number");
    json!(shortest)
}

/// A JSON object from `(key, value)` pairs in order.
fn object(entries: impl IntoIterator<Item = (String, Value)>) -> Value {
    Value::Object(entries.into_iter().collect::<Map<String, Value>>())
}

pub mod biome {
    //! A biome in the game's own datapack format (`worldgen/biome/<id>.json`, format's `BiomeJson`): its sky,
    //! fog and water fog colours, particles, sounds and music as environment attributes, the rest of its
    //! colours as `effects`; its mobs where the server's format keeps them. Exactly what the file says: no
    //! carvers, the features listed (and no others) in each step, its spawns replacing the world's.

    use serde_json::{Value, json};

    use super::{Context, object};
    use crate::kind::biome::{BiomeFile, GrassModifier, TemperatureModifier};
    use crate::manifest::SpawnCategory;
    use crate::reference::RefKind;
    use crate::resolve::filled;
    use crate::worldgen::GenerationStep;

    /// Where the game reads a datapack's biomes, under `data/<namespace>/`.
    pub const FOLDER: &str = "worldgen/biome";

    /// The data pack format (26.3's) from which a biome's mobs are the `natural_mob_spawns` attribute rather
    /// than `spawners` and `spawn_costs` of its own.
    pub const SPAWNS_ATTRIBUTE_FORMAT: i32 = 121;

    fn given<T>(value: Option<T>) -> T {
        value.expect("a field with a schema default is filled")
    }

    pub fn of(file: &BiomeFile, cx: &Context) -> Value {
        let climate = filled(&file.climate);
        let mut out = vec![
            ("temperature".to_owned(), json!(given(climate.temperature))),
            ("downfall".to_owned(), json!(given(climate.downfall))),
            (
                "has_precipitation".to_owned(),
                json!(given(climate.precipitation)),
            ),
        ];
        if let Some(modifier) = climate
            .temperature_modifier
            .filter(|m| *m != TemperatureModifier::None)
        {
            out.push((
                "temperature_modifier".into(),
                json!(crate::canonical::name_of(&modifier)),
            ));
        }
        let as_attribute = cx.format_at_least(SPAWNS_ATTRIBUTE_FORMAT, 0);
        out.push(("attributes".into(), attributes(file, as_attribute)));
        out.push(("effects".into(), effects(file)));
        if !as_attribute {
            out.push(("spawners".into(), spawners(file, false)));
            out.push(("spawn_costs".into(), spawn_costs(file)));
        }
        out.push(("carvers".into(), json!([])));
        let features: Vec<Value> = GenerationStep::ALL
            .iter()
            .map(|step| {
                let listed = file
                    .features
                    .get(step)
                    .map(Vec::as_slice)
                    .unwrap_or_default();
                Value::Array(
                    listed
                        .iter()
                        .map(|f| json!(cx.resolve(f).to_string()))
                        .collect(),
                )
            })
            .collect();
        out.push(("features".into(), Value::Array(features)));
        object(out)
    }

    /// Whether a running biome goes in the pack: every placed feature it names that isn't the game's is one a
    /// datapack in it defines (the game refuses the whole pack over a biome naming a feature it doesn't have).
    pub fn writes(file: &BiomeFile, cx: &Context) -> bool {
        let features: Vec<&String> = file
            .features
            .iter()
            .flat_map(|(_, list)| list)
            .filter(|f| !RefKind::PlacedFeature.is_game(f))
            .collect();
        if features.is_empty() {
            return true;
        }
        let defined = cx.passed_through(
            RefKind::PlacedFeature
                .spec()
                .registry
                .expect("placed features are a registry"),
        );
        features.iter().all(|f| defined.contains(&cx.resolve(f)))
    }

    fn attributes(file: &BiomeFile, spawns_as_attribute: bool) -> Value {
        let colors = &file.colors;
        let mut out: Vec<(String, Value)> = Vec::new();
        let mut color = |name: &str, value: &Option<String>| {
            if let Some(v) = value {
                out.push((name.to_owned(), json!(v.to_lowercase())));
            }
        };
        color("minecraft:visual/sky_color", &colors.sky);
        color("minecraft:visual/fog_color", &colors.fog);
        color("minecraft:visual/water_fog_color", &colors.water_fog);
        if let Some(particle) = &file.particle {
            out.push((
                "minecraft:visual/ambient_particles".into(),
                json!([{ "particle": { "type": crate::game::normalize(&particle.particle) }, "probability": particle.probability }]),
            ));
        }
        let sounds = &file.sounds;
        if sounds.ambient.is_some() || sounds.mood.is_some() || sounds.additions.is_some() {
            let mut ambient: Vec<(String, Value)> = Vec::new();
            if let Some(a) = &sounds.ambient {
                ambient.push(("loop".into(), json!(crate::game::normalize(a))));
            }
            if let Some(mood) = &sounds.mood {
                let mood = filled(mood);
                ambient.push((
                    "mood".into(),
                    json!({
                        "sound": crate::game::normalize(&mood.sound),
                        "tick_delay": given(mood.tick_delay),
                        "block_search_extent": given(mood.block_search_extent),
                        "offset": given(mood.offset),
                    }),
                ));
            }
            if let Some(additions) = &sounds.additions {
                ambient.push((
                    "additions".into(),
                    json!({ "sound": crate::game::normalize(&additions.sound), "tick_chance": additions.chance }),
                ));
            }
            out.push(("minecraft:audio/ambient_sounds".into(), object(ambient)));
        }
        if let Some(music) = &sounds.music {
            let music = filled(music);
            out.push((
                "minecraft:audio/background_music".into(),
                json!({ "default": {
                    "sound": crate::game::normalize(&music.sound),
                    "min_delay": given(music.min_delay),
                    "max_delay": given(music.max_delay),
                } }),
            ));
        }
        if spawns_as_attribute {
            out.push((
                "minecraft:gameplay/natural_mob_spawns".into(),
                json!({ "spawn_costs": spawn_costs(file), "spawns_by_category": spawners(file, true) }),
            ));
        }
        object(out)
    }

    fn effects(file: &BiomeFile) -> Value {
        let colors = filled(&file.colors);
        let mut out = vec![(
            "water_color".to_owned(),
            json!(given(colors.water).to_lowercase()),
        )];
        for (name, value) in [
            ("grass_color", &colors.grass),
            ("foliage_color", &colors.foliage),
            ("dry_foliage_color", &colors.dry_foliage),
        ] {
            if let Some(v) = value {
                out.push((name.to_owned(), json!(v.to_lowercase())));
            }
        }
        if let Some(modifier) = colors.grass_modifier.filter(|m| *m != GrassModifier::None) {
            out.push((
                "grass_color_modifier".into(),
                json!(crate::canonical::name_of(&modifier)),
            ));
        }
        object(out)
    }

    /// The spawns by the game's own name for each category, in the categories' order.
    fn spawners(file: &BiomeFile, as_attribute: bool) -> Value {
        let mut out: Vec<(String, Value)> = Vec::new();
        for category in SpawnCategory::ALL {
            let Some(spawns) = file.spawns.get(&category) else {
                continue;
            };
            let list = spawns
                .iter()
                .map(|spawn| {
                    let (min, max) = spawn.group_range();
                    let mut entry = vec![
                        ("type".to_owned(), json!(crate::game::normalize(&spawn.entity))),
                        ("weight".to_owned(), json!(given(filled(spawn).weight))),
                    ];
                    if !as_attribute {
                        entry.push(("minCount".into(), json!(min)));
                        entry.push(("maxCount".into(), json!(max)));
                    } else if min == max {
                        entry.push(("count".into(), json!(min)));
                    } else {
                        entry.push(("count".into(), json!({ "type": "minecraft:uniform", "min_inclusive": min, "max_inclusive": max })));
                    }
                    object(entry)
                })
                .collect();
            out.push((mob_category(category).to_owned(), Value::Array(list)));
        }
        object(out)
    }

    fn spawn_costs(file: &BiomeFile) -> Value {
        let mut costs: Vec<(String, Value)> = file
            .spawn_costs
            .iter()
            .map(|(entity, cost)| {
                (
                    crate::game::normalize(&entity.0),
                    json!({ "charge": cost.charge, "energy_budget": cost.energy_budget }),
                )
            })
            .collect();
        costs.sort_by(|a, b| a.0.cmp(&b.0));
        object(costs)
    }

    /// The game's name for a spawn category, as a biome's spawns are keyed (its `MobCategory`).
    fn mob_category(category: SpawnCategory) -> &'static str {
        match category {
            SpawnCategory::Monster => "monster",
            SpawnCategory::Animal => "creature",
            SpawnCategory::WaterAnimal => "water_creature",
            SpawnCategory::WaterAmbient => "water_ambient",
            SpawnCategory::WaterUndergroundCreature => "underground_water_creature",
            SpawnCategory::Ambient => "ambient",
            SpawnCategory::Axolotl => "axolotls",
        }
    }
}

pub mod dimension {
    //! A dimension type in the game's own datapack format (format's `DimensionTypeJson`), read off each
    //! supported server's own `dimension_type/` files: the heights, light, sky and monster light as fields;
    //! the game's rules and the sky's, fog's and clouds' colours as environment attributes. What the file
    //! doesn't set is the overworld's, so `{}` is the game's overworld.

    use serde_json::{Value, json};

    use super::{Context, object};
    use crate::kind::dimension_type::DimensionTypeFile;
    use crate::resolve::filled;

    pub const FOLDER: &str = "dimension_type";
    /// The game's own overworld type, which a main world that names a dimension replaces.
    pub const OVERWORLD: &str = "minecraft:overworld";
    /// 26.1's: a type has a `default_clock` and must say `has_ender_dragon_fight`.
    pub const CLOCK_FORMAT: i32 = 101;
    /// 26.3's: a bed blows up with `destroy_on_use`, and the straw bed has its own rule.
    pub const STRAW_BED_FORMAT: i32 = 121;
    const NO_SLEEP: &str = "block.minecraft.bed.no_sleep";
    const AMBIENT_LIGHT_COLOR: &str = "#0a0a0a";

    fn given<T>(value: Option<T>) -> T {
        value.expect("a field with a schema default is filled")
    }

    pub fn of(file: &DimensionTypeFile, cx: &Context) -> Value {
        let f = filled(file);
        let height = given(f.height);
        let mut out = vec![
            ("ambient_light".to_owned(), json!(given(f.ambient_light))),
            ("attributes".to_owned(), attributes(&f, cx)),
            (
                "coordinate_scale".to_owned(),
                json!(given(f.coordinate_scale)),
            ),
        ];
        if cx.format_at_least(CLOCK_FORMAT, 0) {
            out.push(("default_clock".into(), json!(OVERWORLD)));
            out.push(("has_ender_dragon_fight".into(), json!(false)));
        }
        let fixed = given(f.fixed_time);
        let infiniburn = given(f.infiniburn);
        let (low, high) = match &file.monster_spawn_light {
            Some(light) => {
                let light = filled(light);
                (given(light.min), given(light.max))
            }
            None => (0, 7),
        };
        out.extend([
            ("has_ceiling".to_owned(), json!(given(f.ceiling))),
            ("has_fixed_time".to_owned(), json!(fixed)),
            ("has_skylight".to_owned(), json!(given(f.sky_light))),
            ("height".to_owned(), json!(height)),
            (
                "infiniburn".to_owned(),
                json!(format!("#{}", crate::game::normalize(infiniburn.trim_start_matches('#')))),
            ),
            ("logical_height".to_owned(), json!(file.logical_height.unwrap_or(height))),
            ("min_y".to_owned(), json!(given(f.min_y))),
            ("monster_spawn_block_light_limit".to_owned(), json!(given(f.monster_spawn_block_light))),
            (
                "monster_spawn_light_level".to_owned(),
                if low == high {
                    json!(low)
                } else {
                    json!({ "type": "minecraft:uniform", "max_inclusive": high, "min_inclusive": low })
                },
            ),
            ("skybox".to_owned(), json!(crate::canonical::name_of(&given(f.sky)))),
            ("timelines".to_owned(), json!(if fixed { "#minecraft:universal" } else { "#minecraft:in_overworld" })),
        ]);
        object(out)
    }

    fn attributes(file: &DimensionTypeFile, cx: &Context) -> Value {
        let straw = cx.format_at_least(STRAW_BED_FORMAT, 0);
        let bed = given(file.bed_works);
        let music = |sound: &str| json!({ "max_delay": 24000, "min_delay": 12000, "sound": sound });
        let mut out = vec![
            (
                "minecraft:audio/ambient_sounds".to_owned(),
                json!({ "mood": { "block_search_extent": 8, "offset": 2.0, "sound": "minecraft:ambient.cave", "tick_delay": 6000 } }),
            ),
            (
                "minecraft:audio/background_music".to_owned(),
                json!({ "creative": music("minecraft:music.creative"), "default": music("minecraft:music.game") }),
            ),
            (
                "minecraft:gameplay/bed_rule".to_owned(),
                bed_rule(bed, false, straw),
            ),
            (
                "minecraft:gameplay/can_start_raid".to_owned(),
                json!(given(file.raids)),
            ),
            (
                "minecraft:gameplay/nether_portal_spawns_piglin".to_owned(),
                json!(true),
            ),
            (
                "minecraft:gameplay/piglins_zombify".to_owned(),
                json!(!given(file.piglin_safe)),
            ),
            (
                "minecraft:gameplay/respawn_anchor_works".to_owned(),
                json!(given(file.respawn_anchor_works)),
            ),
        ];
        if straw {
            out.push((
                "minecraft:gameplay/straw_bed_rule".into(),
                bed_rule(bed, true, straw),
            ));
        }
        if given(file.ultrawarm) {
            out.push(("minecraft:gameplay/fast_lava".into(), json!(true)));
            out.push(("minecraft:gameplay/snow_golem_melts".into(), json!(true)));
            out.push(("minecraft:gameplay/water_evaporates".into(), json!(true)));
            out.push((
                "minecraft:visual/default_dripstone_particle".into(),
                json!({ "type": "minecraft:dripping_dripstone_lava" }),
            ));
        }
        if cx.format_at_least(CLOCK_FORMAT, 0) {
            out.push((
                "minecraft:visual/ambient_light_color".into(),
                json!(AMBIENT_LIGHT_COLOR),
            ));
        }
        let colors = filled(&file.colors.clone().unwrap_or_default());
        let clouds = given(colors.clouds);
        let argb = if clouds.len() == 7 {
            format!("#ff{}", &clouds[1..])
        } else {
            clouds
        };
        out.push((
            "minecraft:visual/cloud_color".into(),
            json!(argb.to_lowercase()),
        ));
        out.push((
            "minecraft:visual/cloud_height".into(),
            json!(given(file.cloud_height)),
        ));
        out.push((
            "minecraft:visual/fog_color".into(),
            json!(given(colors.fog).to_lowercase()),
        ));
        out.push((
            "minecraft:visual/sky_color".into(),
            json!(given(colors.sky).to_lowercase()),
        ));
        object(out)
    }

    /// Where beds work: sleep when it's dark and set the spawn (a straw bed only sleeps, and goes once left);
    /// where they don't: no sleep, no spawn, and the bed blows up when used.
    fn bed_rule(works: bool, straw: bool, destroy_on_use: bool) -> Value {
        let mut out = Vec::new();
        if works {
            out.push((
                "can_set_spawn".to_owned(),
                json!(if straw { "never" } else { "always" }),
            ));
            out.push(("can_sleep".to_owned(), json!("when_dark")));
            if straw {
                out.push(("destroy_on_leave".to_owned(), json!(true)));
            }
            out.push(("error_message".to_owned(), json!({ "translate": NO_SLEEP })));
        } else {
            out.push(("can_set_spawn".to_owned(), json!("never")));
            out.push(("can_sleep".to_owned(), json!("never")));
            out.push((
                (if destroy_on_use {
                    "destroy_on_use"
                } else {
                    "explodes"
                })
                .to_owned(),
                json!(true),
            ));
        }
        object(out)
    }
}

pub mod structure {
    //! Structures that generate, in the game's own datapack format (format's `StructureJson`): a structure
    //! `ns:id` is a `minecraft:jigsaw` structure from the pool `ns:id/start`, a `random_spread` set of just it,
    //! its pools, and a copy of every template they pick from.

    use std::collections::BTreeSet;

    use serde_json::{Value, json};

    use super::{Context, Entry, object};
    use crate::kind::structure::{
        PoolProjection, StructureGeneration, StructureHeightmap, TerrainAdaptation,
    };
    use crate::reference::{RefKind, ResourceKey};
    use crate::worldgen::GenerationStep;

    pub const TEMPLATE_FOLDER: &str = "structure";
    const STRUCTURES: &str = "worldgen/structure";
    const STRUCTURE_SETS: &str = "worldgen/structure_set";
    const POOLS: &str = "worldgen/template_pool";
    pub const START_POOL: &str = "start";
    const DEFAULT_DEPTH_WITH_POOLS: i32 = 7;

    /// The registry entries a structure that generates is, as `id` in its namespace: its structure, set and pools.
    pub fn entries(id: &str, generation: &StructureGeneration) -> Vec<(&'static str, String)> {
        let mut out = vec![(STRUCTURES, id.to_owned()), (STRUCTURE_SETS, id.to_owned())];
        out.push((POOLS, format!("{id}/{START_POOL}")));
        out.extend(
            generation
                .pools
                .iter()
                .map(|(name, _)| (POOLS, format!("{id}/{}", name.0))),
        );
        out
    }

    /// The files of the structures that generate (`resources`), whose pieces must be among `running` (every
    /// running structure, generating or not).
    pub fn files(
        resources: &[(ResourceKey, &StructureGeneration)],
        running_structures: &BTreeSet<ResourceKey>,
        cx: &Context,
    ) -> Vec<(String, Entry)> {
        let known = running_structures;
        let running = cx.biomes();
        let mut out = Vec::new();
        let mut templates: Vec<ResourceKey> = Vec::new();
        let mut template = |key: ResourceKey| {
            if !templates.contains(&key) {
                templates.push(key);
            }
        };
        for (key, generation) in resources {
            // A piece that isn't running (it has errors) would leave a pool the game can't load: the structure waits for it.
            let pieces: Vec<ResourceKey> = generation
                .pools
                .iter()
                .flat_map(|(_, pool)| pool.elements.iter())
                .map(|e| ResourceKey {
                    namespace: key.namespace.clone(),
                    path: e.structure.clone(),
                })
                .collect();
            if pieces.iter().any(|p| !known.contains(p)) {
                continue;
            }
            // So would a biome of the project's that isn't in the pack: the game refuses the whole pack over it.
            let biomes_ok = generation
                .biomes
                .iter()
                .filter(|b| !RefKind::Biome.is_game(b))
                .all(|b| {
                    crate::reference::resolve_any(&key.namespace, b)
                        .is_some_and(|k| running.contains(&k))
                });
            if !biomes_ok {
                continue;
            }
            let data = format!("data/{}", key.namespace);
            let id = &key.path;
            out.push((
                format!("{data}/{STRUCTURES}/{id}.json"),
                Entry::json(&structure(key, generation)),
            ));
            out.push((
                format!("{data}/{STRUCTURE_SETS}/{id}.json"),
                Entry::json(&structure_set(key, generation)),
            ));
            out.push((
                format!("{data}/{POOLS}/{id}/{START_POOL}.json"),
                Entry::json(&pool(&[(key.clone(), 1)], PoolProjection::Rigid)),
            ));
            for (name, pool_def) in generation.pools.iter() {
                let elements: Vec<(ResourceKey, i32)> = pool_def
                    .elements
                    .iter()
                    .map(|e| {
                        (
                            ResourceKey {
                                namespace: key.namespace.clone(),
                                path: e.structure.clone(),
                            },
                            e.weight.unwrap_or(1),
                        )
                    })
                    .collect();
                let projection = pool_def.projection.unwrap_or(PoolProjection::Rigid);
                out.push((
                    format!("{data}/{POOLS}/{id}/{}.json", name.0),
                    Entry::json(&pool(&elements, projection)),
                ));
            }
            template(key.clone());
            pieces.into_iter().for_each(&mut template);
        }
        for key in templates {
            // A structure's place is its `.nbt`.
            let path = cx
                .project
                .place_of("structure", &key.relative_to(cx.home()));
            out.push((
                format!("data/{}/{TEMPLATE_FOLDER}/{}.nbt", key.namespace, key.path),
                Entry::Copy(path),
            ));
        }
        out
    }

    fn structure(key: &ResourceKey, generation: &StructureGeneration) -> Value {
        let mut out = vec![
            ("type".to_owned(), json!("minecraft:jigsaw")),
            (
                "biomes".to_owned(),
                biomes(&generation.biomes, &key.namespace),
            ),
            (
                "step".to_owned(),
                json!(crate::canonical::name_of(
                    &generation.step.unwrap_or(GenerationStep::SurfaceStructures)
                )),
            ),
            (
                "terrain_adaptation".to_owned(),
                json!(crate::canonical::name_of(
                    &generation
                        .terrain_adaptation
                        .unwrap_or(TerrainAdaptation::None)
                )),
            ),
            ("spawn_overrides".to_owned(), json!({})),
            (
                "start_pool".to_owned(),
                json!(format!("{key}/{START_POOL}")),
            ),
            (
                "size".to_owned(),
                json!(generation.depth.unwrap_or(if generation.pools.is_empty() {
                    1
                } else {
                    DEFAULT_DEPTH_WITH_POOLS
                })),
            ),
            (
                "start_height".to_owned(),
                json!({ "absolute": generation.start_height.unwrap_or(0) }),
            ),
        ];
        if let Some(heightmap) = generation
            .heightmap
            .unwrap_or(StructureHeightmap::WorldSurfaceWg)
            .game()
        {
            out.push(("project_start_to_heightmap".into(), json!(heightmap)));
        }
        out.push((
            "max_distance_from_center".into(),
            json!(generation.max_distance.unwrap_or(80)),
        ));
        out.push(("use_expansion_hack".into(), json!(false)));
        object(out)
    }

    /// A tag stands alone as a string; ids are a list, a project biome's its key in the game.
    fn biomes(biomes: &[String], home: &str) -> Value {
        let key = |b: &String| {
            crate::kind::biome::key_of(b, home).unwrap_or_else(|| panic!("\"{b}\" isn't a biome"))
        };
        match biomes.first() {
            Some(first) if first.starts_with('#') => json!(key(first)),
            _ => Value::Array(biomes.iter().map(|b| json!(key(b))).collect()),
        }
    }

    fn structure_set(key: &ResourceKey, generation: &StructureGeneration) -> Value {
        json!({
            "structures": [{ "structure": key.to_string(), "weight": 1 }],
            "placement": {
                "type": "minecraft:random_spread",
                "salt": generation.salt.unwrap_or_else(|| salt_of(key)),
                "separation": generation.separation.unwrap_or(8),
                "spacing": generation.spacing.unwrap_or(32),
            }
        })
    }

    fn pool(elements: &[(ResourceKey, i32)], projection: PoolProjection) -> Value {
        let elements: Vec<Value> = elements
            .iter()
            .map(|(structure, weight)| {
                json!({
                    "weight": weight,
                    "element": {
                        "element_type": "minecraft:single_pool_element",
                        "location": structure.to_string(),
                        "processors": "minecraft:empty",
                        "projection": crate::canonical::name_of(&projection),
                    }
                })
            })
            .collect();
        json!({ "fallback": "minecraft:empty", "elements": elements })
    }

    /// A structure's own salt: FNV-1a over its name, not negative, the same everywhere.
    pub fn salt_of(key: &ResourceKey) -> i32 {
        let mut hash = 0x811C_9DC5_u32 as i32;
        for byte in key.to_string().bytes() {
            hash = (hash ^ i32::from(byte)).wrapping_mul(0x0100_0193);
        }
        hash & i32::MAX
    }
}

pub mod advancement {
    //! An advancement in the game's own datapack format (format's `AdvancementJson`): its parent and icon
    //! resolved in the project's namespace, its text the server's JSON text, a criterion without a trigger
    //! one only a grant meets.

    use serde_json::{Value, json};

    use super::{Context, object};
    use crate::kind::advancement::{AdvancementDisplay, AdvancementFile, AdvancementIcon};

    pub const SCRIPT_TRIGGER: &str = "minecraft:impossible";
    pub const FOLDER: &str = "advancement";

    /// `file` in the game's format; none when its icon names a project item that can't be drawn.
    pub fn of(file: &AdvancementFile, cx: &Context) -> Option<Value> {
        let display = match &file.display {
            Some(display) => Some(self::display(display, cx)?),
            None => None,
        };
        let mut out = Vec::new();
        if let Some(parent) = &file.parent {
            out.push(("parent".to_owned(), json!(cx.resolve(parent).to_string())));
        }
        if let Some(display) = display {
            out.push(("display".into(), display));
        }
        let mut criteria: Vec<(&crate::canonical::Key, _)> = file.criteria.iter().collect();
        criteria.sort_by(|a, b| a.0.0.cmp(&b.0.0));
        let criteria = criteria.into_iter().map(|(name, criterion)| {
            let mut entry = vec![(
                "trigger".to_owned(),
                json!(criterion.trigger.as_deref().unwrap_or(SCRIPT_TRIGGER)),
            )];
            if let Some(conditions) = &criterion.conditions {
                entry.push((
                    "conditions".into(),
                    serde_json::to_value(conditions).expect("conditions are JSON"),
                ));
            }
            (name.0.clone(), object(entry))
        });
        out.push(("criteria".into(), object(criteria)));
        if let Some(groups) = &file.requirements {
            out.push((
                "requirements".into(),
                json!(groups.iter().map(|g| g.0.clone()).collect::<Vec<_>>()),
            ));
        }
        if let Some(experience) = file.experience {
            out.push(("rewards".into(), json!({ "experience": experience })));
        }
        Some(object(out))
    }

    fn display(display: &AdvancementDisplay, cx: &Context) -> Option<Value> {
        let icon = icon(&display.icon, cx)?;
        let mut out = vec![
            ("icon".to_owned(), icon),
            ("title".to_owned(), cx.text(&display.title)),
            // The game requires a description; an empty one draws nothing.
            (
                "description".to_owned(),
                cx.text(display.description.as_deref().unwrap_or("")),
            ),
        ];
        if let Some(frame) = display.frame {
            out.push(("frame".into(), json!(crate::canonical::name_of(&frame))));
        }
        if let Some(background) = &display.background {
            out.push(("background".into(), json!(background)));
        }
        for (name, value) in [
            ("show_toast", display.toast),
            ("announce_to_chat", display.announce),
            ("hidden", display.hidden),
        ] {
            if let Some(v) = value {
                out.push((name.to_owned(), json!(v)));
            }
        }
        Some(object(out))
    }

    /// An item stack as the game writes one: its kind and the components its look needs.
    fn icon(icon: &AdvancementIcon, cx: &Context) -> Option<Value> {
        let item = match &icon.item {
            Some(reference) => Some(super::running_item(cx, reference)?),
            None => None,
        };
        let kind = icon.kind.clone().or_else(|| item.map(|i| i.kind.clone()))?;
        let model = icon
            .item_model
            .clone()
            .or_else(|| item.and_then(|i| i.item_model.clone()));
        let glint = icon.glint.or_else(|| item.and_then(|i| i.glint));
        let mut out = vec![("id".to_owned(), json!(crate::game::normalize(&kind)))];
        if model.is_some() || glint.is_some() {
            let mut components = Vec::new();
            if let Some(model) = model {
                components.push((
                    "minecraft:item_model".to_owned(),
                    json!(cx.resolve(&model).to_string()),
                ));
            }
            if let Some(glint) = glint {
                components.push((
                    "minecraft:enchantment_glint_override".to_owned(),
                    json!(glint),
                ));
            }
            out.push(("components".into(), object(components)));
        }
        Some(object(out))
    }
}

/// The running project item `reference` names, by the name `running` gives it.
fn running_item<'m>(cx: &'m Context, reference: &str) -> Option<&'m crate::kind::item::ItemFile> {
    let name = cx.resolve(reference).relative_to(cx.home());
    cx.project
        .running::<crate::kind::item::ItemFile>()
        .into_iter()
        .find(|(n, _)| *n == name)
        .map(|(_, item)| item)
}

pub mod dialog {
    //! The dialogs of the player's own menus, the pause screen's and the quick actions key's (format's
    //! `DialogJson`): the game lists them from its dialog registry, read only as the server loads. Their
    //! buttons are `minecraft:dynamic/custom` actions, whose ids say which dialog and which button.

    use std::collections::BTreeSet;

    use serde_json::{Value, json};

    use super::{Context, Entry, float, object};
    use crate::item::ItemDef;
    use crate::kind::dialog::{
        AfterAction, DialogBody, DialogButton, DialogFile, DialogInput, DialogType,
    };
    use crate::reference::ResourceKey;

    pub const PAUSE_TAG: &str = "data/minecraft/tags/dialog/pause_screen_additions.json";
    pub const QUICK_ACTIONS_TAG: &str = "data/minecraft/tags/dialog/quick_actions.json";
    pub const FOLDER: &str = "dialog";
    const CLICK_FOLDER: &str = "dialog/";
    const DEFAULT_COLUMNS: i32 = 2;

    /// What a custom click from a registry dialog means.
    #[derive(Clone, Debug, PartialEq, Eq)]
    pub enum Click {
        /// The `index`th button of the dialog's file was pressed.
        Press { dialog: String, index: usize },
        /// The dialog was left through its exit action.
        Exit { dialog: String },
    }

    /// The id a button's action carries: `<namespace>:dialog/<dialog>/press/<index>`.
    pub fn press_id(dialog: &ResourceKey, index: usize) -> String {
        format!(
            "{}:{CLICK_FOLDER}{}/press/{index}",
            dialog.namespace, dialog.path
        )
    }

    /// The id an exit action carries.
    pub fn exit_id(dialog: &ResourceKey) -> String {
        format!("{}:{CLICK_FOLDER}{}/exit", dialog.namespace, dialog.path)
    }

    /// What custom click `id` means from the project in namespace `home`, if it's one of these.
    pub fn click(id: &str, home: &str) -> Option<Click> {
        let (namespace, rest) = id.split_once(':')?;
        let rest = rest.strip_prefix(CLICK_FOLDER)?;
        if namespace.is_empty() {
            return None;
        }
        let name = |dialog: &str| {
            crate::reference::resolve_any(home, &format!("{namespace}:{dialog}"))
                .map(|k| k.relative_to(home))
        };
        if let Some(dialog) = rest.strip_suffix("/exit") {
            return name(dialog).map(|dialog| Click::Exit { dialog });
        }
        let (dialog, index) = rest.rsplit_once("/press/")?;
        let index = index.parse().ok()?;
        name(dialog).map(|dialog| Click::Press { dialog, index })
    }

    /// Every file for the running dialogs: each one on a menu and the ones those list, and the menus' tags.
    pub fn files(resources: &[(ResourceKey, &DialogFile)], cx: &Context) -> Vec<(String, Entry)> {
        let menus: Vec<&(ResourceKey, &DialogFile)> = resources
            .iter()
            .filter(|(_, f)| f.pause_menu == Some(true) || f.quick_actions == Some(true))
            .collect();
        if menus.is_empty() {
            return Vec::new();
        }
        let mut needed: Vec<(ResourceKey, &DialogFile)> = Vec::new();
        fn need<'a>(
            key: &ResourceKey,
            resources: &[(ResourceKey, &'a DialogFile)],
            needed: &mut Vec<(ResourceKey, &'a DialogFile)>,
            cx: &Context,
        ) {
            let Some((_, file)) = resources.iter().find(|(k, _)| k == key) else {
                return;
            };
            if needed.iter().any(|(k, _)| k == key) {
                return;
            }
            needed.push((key.clone(), file));
            if file.kind == Some(DialogType::DialogList) {
                for other in &file.dialogs {
                    need(&cx.resolve(other), resources, needed, cx);
                }
            }
        }
        for (key, _) in &menus {
            need(key, resources, &mut needed, cx);
        }
        let registry: BTreeSet<ResourceKey> = needed.iter().map(|(k, _)| k.clone()).collect();
        let mut files: Vec<(String, Entry)> = needed
            .iter()
            .map(|(key, file)| {
                (
                    format!("data/{}/{FOLDER}/{}.json", key.namespace, key.path),
                    Entry::json(&of(key, file, cx, &registry)),
                )
            })
            .collect();
        let tag = |which: fn(&DialogFile) -> bool| {
            let mut keys: Vec<String> = menus
                .iter()
                .filter(|(_, f)| which(f))
                .map(|(k, _)| k.to_string())
                .collect();
            keys.sort();
            (!keys.is_empty()).then(|| Entry::json(&json!({ "replace": false, "values": keys })))
        };
        if let Some(entry) = tag(|f| f.pause_menu == Some(true)) {
            files.push((PAUSE_TAG.to_owned(), entry));
        }
        if let Some(entry) = tag(|f| f.quick_actions == Some(true)) {
            files.push((QUICK_ACTIONS_TAG.to_owned(), entry));
        }
        files
    }

    /// `file` (the dialog `key` names) in the game's format; a `dialog_list` lists the ones in `registry`.
    pub fn of(
        key: &ResourceKey,
        file: &DialogFile,
        cx: &Context,
        registry: &BTreeSet<ResourceKey>,
    ) -> Value {
        let buttons: Vec<Value> = file
            .buttons
            .iter()
            .enumerate()
            .map(|(i, b)| button(b, &press_id(key, i), cx))
            .collect();
        let columns = file.columns.unwrap_or(DEFAULT_COLUMNS);
        let kind = file.kind.unwrap_or(DialogType::Notice);
        let mut out = vec![
            (
                "type".to_owned(),
                json!(format!("minecraft:{}", crate::canonical::name_of(&kind))),
            ),
            ("title".to_owned(), cx.text(&file.title)),
        ];
        if let Some(external) = &file.external_title {
            out.push(("external_title".into(), cx.text(external)));
        }
        out.push((
            "can_close_with_escape".into(),
            json!(file.can_close_with_escape.unwrap_or(true)),
        ));
        // The game refuses a dialog that pauses (its default) and stays up after a press; a server has nothing to pause.
        out.push(("pause".into(), json!(false)));
        let after = match file.after_action.unwrap_or(AfterAction::Close) {
            AfterAction::Close => "close",
            AfterAction::None => "none",
            AfterAction::WaitForResponse => "wait_for_response",
        };
        out.push(("after_action".into(), json!(after)));
        let body: Vec<Value> = file.body.iter().filter_map(|b| self::body(b, cx)).collect();
        if !body.is_empty() {
            out.push(("body".into(), Value::Array(body)));
        }
        if !file.inputs.is_empty() {
            out.push((
                "inputs".into(),
                Value::Array(file.inputs.iter().map(|i| input(i, cx)).collect()),
            ));
        }
        match kind {
            DialogType::Notice => out.push((
                "action".into(),
                buttons
                    .first()
                    .cloned()
                    .unwrap_or_else(|| exit(key, "gui.ok")),
            )),
            DialogType::Confirmation => {
                out.push((
                    "yes".into(),
                    buttons
                        .first()
                        .cloned()
                        .unwrap_or_else(|| exit(key, "gui.yes")),
                ));
                out.push((
                    "no".into(),
                    buttons
                        .get(1)
                        .cloned()
                        .unwrap_or_else(|| exit(key, "gui.no")),
                ));
            }
            DialogType::MultiAction => {
                // The game wants at least one action; a dialog with no buttons has just its exit one.
                let actions = if buttons.is_empty() {
                    vec![exit(key, "gui.back")]
                } else {
                    buttons
                };
                out.push(("actions".into(), Value::Array(actions)));
                out.push(("columns".into(), json!(columns)));
                out.push(("exit_action".into(), exit(key, "gui.back")));
            }
            DialogType::DialogList => {
                let listed: Vec<Value> = file
                    .dialogs
                    .iter()
                    .map(|d| cx.resolve(d))
                    .filter(|k| registry.contains(k))
                    .map(|k| json!(k.to_string()))
                    .collect();
                out.push(("dialogs".into(), Value::Array(listed)));
                out.push(("columns".into(), json!(columns)));
                // Its one button is its exit action: labelled as the file says, reported as leaving.
                let exit_action = file
                    .buttons
                    .first()
                    .map_or_else(|| exit(key, "gui.back"), |b| button(b, &exit_id(key), cx));
                out.push(("exit_action".into(), exit_action));
            }
        }
        object(out)
    }

    fn button(button: &DialogButton, id: &str, cx: &Context) -> Value {
        let mut out = vec![(
            "label".to_owned(),
            cx.text(button.label.as_deref().unwrap_or(&button.key)),
        )];
        if let Some(tooltip) = &button.tooltip {
            out.push(("tooltip".into(), cx.text(tooltip)));
        }
        if let Some(width) = button.width {
            out.push(("width".into(), json!(width)));
        }
        out.push(("action".into(), click_action(id)));
        object(out)
    }

    /// The exit button NetherForge adds where the file has none: its label is the game's own words.
    fn exit(key: &ResourceKey, translation: &str) -> Value {
        json!({ "label": { "translate": translation }, "action": click_action(&exit_id(key)) })
    }

    fn click_action(id: &str) -> Value {
        json!({ "type": "minecraft:dynamic/custom", "id": id })
    }

    fn body(body: &DialogBody, cx: &Context) -> Option<Value> {
        Some(match body {
            DialogBody::Message { text, width, .. } => {
                let mut out = vec![
                    ("type".to_owned(), json!("minecraft:plain_message")),
                    ("contents".to_owned(), cx.text(text)),
                ];
                if let Some(w) = width {
                    out.push(("width".into(), json!(w)));
                }
                object(out)
            }
            DialogBody::Item {
                item,
                description,
                show_tooltip,
                width,
                height,
                ..
            } => {
                let mut out = vec![
                    ("type".to_owned(), json!("minecraft:item")),
                    ("item".to_owned(), stack(item, cx)?),
                ];
                if let Some(d) = description {
                    out.push(("description".into(), json!({ "contents": cx.text(d) })));
                }
                if let Some(s) = show_tooltip {
                    out.push(("show_tooltip".into(), json!(s)));
                }
                for (name, value) in [("width", width), ("height", height)] {
                    if let Some(v) = value {
                        out.push((name.to_owned(), json!(v)));
                    }
                }
                object(out)
            }
        })
    }

    /// An item body's stack: its kind and count, and the components its look needs.
    fn stack(def: &ItemDef, cx: &Context) -> Option<Value> {
        let item = match &def.item {
            Some(reference) => Some(super::running_item(cx, reference)?),
            None => None,
        };
        let kind = Some(def.kind.clone())
            .filter(|k| !k.is_empty())
            .or_else(|| item.map(|i| i.kind.clone()))?;
        let model = def
            .item_model
            .clone()
            .or_else(|| item.and_then(|i| i.item_model.clone()));
        let glint = def.glint.or_else(|| item.and_then(|i| i.glint));
        let name = def
            .name
            .clone()
            .or_else(|| item.and_then(|i| i.name.clone()));
        let mut out = vec![("id".to_owned(), json!(crate::game::normalize(&kind)))];
        if let Some(count) = def.count {
            out.push(("count".into(), json!(count)));
        }
        if model.is_some() || glint.is_some() || name.is_some() {
            let mut components = Vec::new();
            if let Some(model) = model {
                components.push((
                    "minecraft:item_model".to_owned(),
                    json!(cx.resolve(&model).to_string()),
                ));
            }
            if let Some(glint) = glint {
                components.push((
                    "minecraft:enchantment_glint_override".to_owned(),
                    json!(glint),
                ));
            }
            if let Some(name) = name {
                components.push(("minecraft:custom_name".to_owned(), cx.text(&name)));
            }
            out.push(("components".into(), object(components)));
        }
        Some(object(out))
    }

    fn input(input: &DialogInput, cx: &Context) -> Value {
        let mut out: Vec<(String, Value)> = Vec::new();
        let mut put = |name: &str, value: Value| out.push((name.to_owned(), value));
        let label = |label: &Option<String>, key: &str| cx.text(label.as_deref().unwrap_or(key));
        match input {
            DialogInput::Text {
                key,
                label: l,
                width,
                initial,
                max_length,
                lines,
                label_visible,
            } => {
                put("type", json!("minecraft:text"));
                put("key", json!(key));
                put("label", label(l, key));
                if let Some(w) = width {
                    put("width", json!(w));
                }
                if let Some(v) = label_visible {
                    put("label_visible", json!(v));
                }
                if let Some(i) = initial {
                    put("initial", json!(i));
                }
                if let Some(m) = max_length {
                    put("max_length", json!(m));
                }
                if let Some(lines) = lines.filter(|l| *l > 1) {
                    put("multiline", json!({ "max_lines": lines }));
                }
            }
            DialogInput::Boolean {
                key,
                label: l,
                initial,
                on_true,
                on_false,
            } => {
                put("type", json!("minecraft:boolean"));
                put("key", json!(key));
                put("label", label(l, key));
                put("initial", json!(initial.unwrap_or(false)));
                put("on_true", json!(on_true.as_deref().unwrap_or("true")));
                put("on_false", json!(on_false.as_deref().unwrap_or("false")));
            }
            DialogInput::SingleOption {
                key,
                label: l,
                width,
                options,
            } => {
                put("type", json!("minecraft:single_option"));
                put("key", json!(key));
                put("label", label(l, key));
                if let Some(w) = width {
                    put("width", json!(w));
                }
                let options: Vec<Value> = options
                    .iter()
                    .map(|o| json!({ "id": o.id, "display": cx.text(o.label.as_deref().unwrap_or(&o.id)), "initial": o.initial.unwrap_or(false) }))
                    .collect();
                put("options", Value::Array(options));
            }
            DialogInput::NumberRange {
                key,
                label: l,
                width,
                start,
                end,
                step,
                initial,
            } => {
                put("type", json!("minecraft:number_range"));
                put("key", json!(key));
                put("label", label(l, key));
                if let Some(w) = width {
                    put("width", json!(w));
                }
                put("start", float(*start));
                put("end", float(*end));
                if let Some(s) = step {
                    put("step", float(*s));
                }
                if let Some(i) = initial {
                    put("initial", float(*i));
                }
            }
        }
        object(out)
    }
}

pub mod sky {
    //! Each sky's twin of every biome the server has, per step of its fade (format's `SkyTwins`), and on 26.1
    //! and later a clock and timeline for a sky held through the night (`SkyNight`).

    use serde_json::{Map, Value, json};

    use super::{Context, Entry, object};
    use crate::kind::sky::SkyFile;
    use crate::reference::ResourceKey;

    /// The folder a sky's twins go in, under the project's namespace.
    pub const FOLDER: &str = "sky";
    pub const CLOCK_FOLDER: &str = "world_clock";
    pub const TIMELINE_FOLDER: &str = "timeline";
    /// The timeline tags the game's dimensions list theirs in: a sky's is added to each.
    pub const TAGS: [&str; 3] = ["in_overworld", "in_nether", "in_end"];
    const SPAWNS: &str = "minecraft:gameplay/natural_mob_spawns";

    /// A colour a sky gives, and where a biome keeps it: an attribute, or water's own in `effects`.
    #[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
    pub enum SkyColor {
        Sky,
        Fog,
        Clouds,
        Water,
        WaterFog,
        Light,
    }

    impl SkyColor {
        pub const ALL: [SkyColor; 6] = [
            SkyColor::Sky,
            SkyColor::Fog,
            SkyColor::Clouds,
            SkyColor::Water,
            SkyColor::WaterFog,
            SkyColor::Light,
        ];

        pub fn attribute(self) -> Option<&'static str> {
            match self {
                SkyColor::Sky => Some("minecraft:visual/sky_color"),
                SkyColor::Fog => Some("minecraft:visual/fog_color"),
                SkyColor::Clouds => Some("minecraft:visual/cloud_color"),
                SkyColor::Water => None,
                SkyColor::WaterFog => Some("minecraft:visual/water_fog_color"),
                SkyColor::Light => Some("minecraft:visual/sky_light_color"),
            }
        }

        pub fn effect(self) -> Option<&'static str> {
            (self == SkyColor::Water).then_some("water_color")
        }

        /// The clouds' is read only as `#aarrggbb`.
        pub fn alpha(self) -> bool {
            self == SkyColor::Clouds
        }

        /// What a biome and its dimension that give none of it have, where it isn't the dimension's to say.
        pub fn fallback(self) -> Option<&'static str> {
            (self == SkyColor::Light).then_some("#ffffff")
        }

        /// Whether a sky that doesn't darken holds it through the night.
        pub fn held_at_night(self) -> bool {
            matches!(self, SkyColor::Sky | SkyColor::Fog)
        }

        /// How the game writes it: `#aarrggbb` with an alpha (`#rrggbb` opaque), else `#rrggbb`; none for neither.
        pub fn normalize(self, value: &str) -> Option<String> {
            let hex = |s: &str, n: usize| s.len() == n && s.bytes().all(|b| b.is_ascii_hexdigit());
            let body = value.strip_prefix('#')?;
            match (self.alpha(), body.len()) {
                (true, 6) if hex(body, 6) => Some(format!("#ff{}", body.to_lowercase())),
                (true, 8) if hex(body, 8) => Some(value.to_lowercase()),
                (false, 6) if hex(body, 6) => Some(value.to_lowercase()),
                _ => None,
            }
        }
    }

    /// Its colours by what they colour, in their order.
    pub fn colors(file: &SkyFile) -> Vec<(SkyColor, &str)> {
        let all = [
            (SkyColor::Sky, &file.sky),
            (SkyColor::Fog, &file.fog),
            (SkyColor::Clouds, &file.clouds),
            (SkyColor::Water, &file.water),
            (SkyColor::WaterFog, &file.water_fog),
            (SkyColor::Light, &file.light),
        ];
        all.into_iter()
            .filter_map(|(c, v)| v.as_deref().map(|v| (c, v)))
            .collect()
    }

    fn fade(file: &SkyFile) -> i32 {
        file.fade.unwrap_or(crate::kind::sky::DEFAULT_FADE)
    }

    /// The colours night would darken that it holds instead; none when it darkens.
    pub fn held_at_night(file: &SkyFile) -> Vec<(SkyColor, &str)> {
        if file.darkens.unwrap_or(true) {
            return Vec::new();
        }
        colors(file)
            .into_iter()
            .filter(|(c, _)| c.held_at_night())
            .collect()
    }

    /// The key of `biome`'s twin at `step` of `sky`, in the project's namespace `home`.
    pub fn key_of(home: &str, sky: &ResourceKey, step: i32, biome: &ResourceKey) -> ResourceKey {
        ResourceKey {
            namespace: home.to_owned(),
            path: format!(
                "{FOLDER}/{}/{}/{step}/{}/{}",
                sky.namespace, sky.path, biome.namespace, biome.path
            ),
        }
    }

    /// The sky `twin` is a twin of, when it's one.
    pub fn sky_of(home: &str, twin: &ResourceKey) -> Option<ResourceKey> {
        if twin.namespace != home {
            return None;
        }
        let parts: Vec<&str> = twin.path.split('/').collect();
        if parts.len() < 6 || parts[0] != FOLDER || parts[parts.len() - 3].parse::<i32>().is_err() {
            return None;
        }
        Some(ResourceKey {
            namespace: parts[1].to_owned(),
            path: parts[2..parts.len() - 3].join("/"),
        })
    }

    /// The colours an overworld biome that gives none has: the dimension type's own.
    pub fn defaults_of(dimension_type: Option<&Value>) -> Vec<(SkyColor, String)> {
        let Some(attributes) = dimension_type
            .and_then(|d| d.get("attributes"))
            .and_then(Value::as_object)
        else {
            return Vec::new();
        };
        SkyColor::ALL
            .into_iter()
            .filter_map(|c| {
                let value = attributes.get(c.attribute()?)?.as_str()?;
                c.normalize(value).map(|v| (c, v))
            })
            .collect()
    }

    /// `fraction` of the way from `from` to `to`, each channel rounded (half to even, as Kotlin's `round`).
    pub fn mix(from: &str, to: &str, fraction: f64) -> String {
        assert_eq!(
            from.len(),
            to.len(),
            "\"{from}\" and \"{to}\" aren't written the same way"
        );
        if fraction >= 1.0 {
            return to.to_lowercase();
        }
        let a = i64::from_str_radix(&from[1..], 16).expect("a colour");
        let b = i64::from_str_radix(&to[1..], 16).expect("a colour");
        let digits = from.len() - 1;
        let mut mixed = 0i64;
        for shift in (0..digits / 2).rev().map(|i| i * 8) {
            let (x, y) = ((a >> shift) & 0xff, (b >> shift) & 0xff);
            let channel = (x as f64 + (y - x) as f64 * fraction).round_ties_even() as i64;
            mixed |= channel.clamp(0, 255) << shift;
        }
        format!("#{mixed:0digits$x}")
    }

    /// `biome`'s JSON as its twin at `step` of `file`'s fade.
    pub fn twin(
        biome: &Value,
        file: &SkyFile,
        step: i32,
        defaults: &[(SkyColor, String)],
    ) -> Value {
        let fraction = f64::from(step) / f64::from(fade(file));
        let empty = Map::new();
        let mut attributes: Map<String, Value> = biome
            .get("attributes")
            .and_then(Value::as_object)
            .unwrap_or(&empty)
            .iter()
            .filter(|(k, _)| *k != SPAWNS)
            .map(|(k, v)| (k.clone(), v.clone()))
            .collect();
        let mut effects: Map<String, Value> = biome
            .get("effects")
            .and_then(Value::as_object)
            .unwrap_or(&empty)
            .clone();
        for (color, given) in colors(file) {
            let Some(target) = color.normalize(given) else {
                continue;
            };
            let own = color
                .attribute()
                .and_then(|a| attributes.get(a))
                .or_else(|| color.effect().and_then(|e| effects.get(e)));
            let from = own
                .and_then(Value::as_str)
                .and_then(|s| color.normalize(s))
                .or_else(|| {
                    defaults
                        .iter()
                        .find(|(c, _)| *c == color)
                        .map(|(_, v)| v.clone())
                })
                .or_else(|| color.fallback().map(str::to_owned))
                .unwrap_or_else(|| target.clone());
            let value = json!(mix(&from, &target, fraction));
            if let Some(a) = color.attribute() {
                attributes.insert(a.to_owned(), value.clone());
            }
            if let Some(e) = color.effect() {
                effects.insert(e.to_owned(), value);
            }
        }
        let mut out = Map::new();
        for (key, value) in biome.as_object().into_iter().flatten() {
            let changed = match key.as_str() {
                "attributes" => Value::Object(attributes.clone()),
                "effects" => Value::Object(effects.clone()),
                // Nothing to generate: each step's list empty, as many as there were.
                "features" => Value::Array(
                    value
                        .as_array()
                        .map_or(0, Vec::len)
                        .pipe(|n| vec![json!([]); n]),
                ),
                "carvers" => json!([]),
                "spawners" | "spawn_costs" => json!({}),
                _ => value.clone(),
            };
            out.insert(key.clone(), changed);
        }
        if biome.get("effects").is_none() && !effects.is_empty() {
            out.insert("effects".into(), Value::Object(effects));
        }
        if biome.get("attributes").is_none() && !attributes.is_empty() {
            out.insert("attributes".into(), Value::Object(attributes));
        }
        Value::Object(out)
    }

    trait Pipe: Sized {
        fn pipe<T>(self, f: impl FnOnce(Self) -> T) -> T {
            f(self)
        }
    }
    impl Pipe for usize {}

    /// The key of `sky`'s clock, and of its timeline, in the project's namespace `home`.
    pub fn night_key(home: &str, sky: &ResourceKey) -> ResourceKey {
        ResourceKey {
            namespace: home.to_owned(),
            path: format!("{FOLDER}/{}/{}", sky.namespace, sky.path),
        }
    }

    /// The key of the clock the pack's file `path` is, when it's a sky's.
    pub fn clock_in(path: &str) -> Option<String> {
        let rest = path.strip_prefix("data/")?;
        let (namespace, rest) = rest.split_once('/')?;
        let clock = rest
            .strip_prefix(&format!("{CLOCK_FOLDER}/"))?
            .strip_suffix(".json")?;
        clock
            .starts_with(&format!("{FOLDER}/"))
            .then(|| format!("{namespace}:{clock}"))
    }

    /// The game's JSON of `file`'s timeline on `clock`: each colour it holds from unseen (tick 0) to all there.
    pub fn timeline(clock: &ResourceKey, file: &SkyFile) -> Value {
        let mut held = held_at_night(file);
        held.sort_by_key(|(c, _)| c.attribute());
        let tracks = held.into_iter().filter_map(|(color, given)| {
            let rgb = color.normalize(given)?[1..].to_owned();
            let keyframes = json!([{ "ticks": 0, "value": format!("#00{rgb}") }, { "ticks": fade(file), "value": format!("#ff{rgb}") }]);
            Some((color.attribute()?.to_owned(), json!({ "keyframes": keyframes, "modifier": "alpha_blend" })))
        });
        json!({ "clock": clock.to_string(), "tracks": object(tracks) })
    }

    /// Every sky's twins and nights, for the running skies.
    pub fn files(skies: &[(ResourceKey, &SkyFile)], cx: &Context) -> Vec<(String, Entry)> {
        if skies.is_empty() {
            return Vec::new();
        }
        let mut biomes: Vec<(ResourceKey, Value)> = cx.game.biomes.clone();
        for (name, file) in cx.project.running::<crate::kind::biome::BiomeFile>() {
            if super::biome::writes(file, cx) {
                let key = cx.key(&name);
                let json = super::biome::of(file, cx);
                match biomes.iter_mut().find(|(k, _)| *k == key) {
                    Some(slot) => slot.1 = json,
                    None => biomes.push((key, json)),
                }
            }
        }
        let defaults = defaults_of(cx.game.overworld.as_ref());
        let clocks = cx.format_at_least(super::dimension::CLOCK_FORMAT, 0);
        let mut timelines = Vec::new();
        let mut out = Vec::new();
        for (sky, file) in skies {
            if cx.left_out(sky) {
                continue;
            }
            if clocks && !held_at_night(file).is_empty() {
                let key = night_key(cx.home(), sky);
                out.push((
                    format!("data/{}/{CLOCK_FOLDER}/{}.json", key.namespace, key.path),
                    Entry::json(&json!({})),
                ));
                out.push((
                    format!("data/{}/{TIMELINE_FOLDER}/{}.json", key.namespace, key.path),
                    Entry::json(&timeline(&key, file)),
                ));
                timelines.push(key);
            }
            for step in 1..=fade(file) {
                for (biome, json) in &biomes {
                    let twin_key = key_of(cx.home(), sky, step, biome);
                    let path = format!(
                        "data/{}/{}/{}.json",
                        twin_key.namespace,
                        super::biome::FOLDER,
                        twin_key.path
                    );
                    out.push((path, Entry::json(&twin(json, file, step, &defaults))));
                }
            }
        }
        // Each added after the game's own (the day's), as a tag's values from another pack are.
        if !timelines.is_empty() {
            let values =
                json!({ "values": timelines.iter().map(|k| k.to_string()).collect::<Vec<_>>() });
            for tag in TAGS {
                out.push((
                    format!("data/minecraft/tags/{TIMELINE_FOLDER}/{tag}.json"),
                    Entry::json(&values),
                ));
            }
        }
        out
    }
}

/// How a terrain's container gets its loot: the game's own (empty) loot table the start-up datapack holds,
/// which the game unpacks as it does its own chests (format's `GeneratedLoot`).
pub mod generated_loot {
    use super::Entry;

    /// The game's loot table generated containers are given.
    pub const KEY: &str = "netherforge:generated_container";
    pub const DATAPACK_PATH: &str = "data/netherforge/loot_table/generated_container.json";

    /// The table: nothing in it, rolled as a chest's would be.
    pub fn entry() -> Entry {
        Entry::json(&serde_json::json!({ "type": "minecraft:chest", "pools": [] }))
    }
}

/// Where the resource of `kind` a server names `name` is (its folder, or its file), at its package path for a
/// package's.
pub fn location(project: &Project, kind: &str, name: &str) -> Option<String> {
    let (_, resource) = project
        .everywhere(kind)
        .into_iter()
        .find(|(n, _)| n == name)?;
    let location = resource
        .found
        .kind
        .location_of(&resource.found.id, &resource.found.group);
    Some(match name.split_once(':') {
        Some((namespace, _)) => crate::kinds::package_path(namespace, &location),
        None => location,
    })
}
