//! The project's terrains (`terrain/<id>.json`) handed to the server, which runs them on its chunk threads (Kotlin's
//! `WorldGenerators`): `WorldManagerOps::publish_generators`, each generator a [`RustTerrain`] the adapter calls from
//! any thread (`netherforge_platform::TerrainSource`).
//!
//! **Nothing here runs on the chunk threads but [`RustTerrain`]**: format's compiled file (pure, immutable), bound once
//! per world it makes, and its script's Luau states (`netherforge_terrain::LuauScripts`, a pool per thread, never this
//! session's). Publishing gives the server, for each block a file names that is the project's own, the note block state
//! it's held as (the custom blocks adopt those states when a chunk loads), and each biome its areas name as the server
//! knows it. The structures decorations place are read with the server's own loader (`StructureOps::template`) and
//! linked in. A reload publishes the whole set again: chunks made from then on use the new files.
//!
//! What a script fails at on the chunk threads comes back through [`Failures`], said once each on the main thread.
//!
//! It also makes the worlds `netherforge.json` names with a `terrain` or a `dimensionType` as the session starts
//! (Kotlin's `ManagedWorlds.ensureMade`), after the blocks have started, so the chunks they generate are read for custom
//! blocks.
//!
//! Not yet: a package's generator's script (its files are the package's), and the main world's generator, which the
//! server asks for before any session (Kotlin's `StartupGenerators`).

use std::cell::RefCell;
use std::collections::{BTreeMap, BTreeSet, HashMap};
use std::rc::Rc;
use std::sync::{Arc, Mutex};

use netherforge_format::Problem;
use netherforge_format::kind::terrain::compiled::{
    CompiledTerrain, StructureTemplate, TerrainBlock,
};
use netherforge_format::kind::terrain::generate::TerrainGenerator;
use netherforge_format::kind::terrain::scripts::ScriptFailure;
use netherforge_format::reference::{RefKind, resolve};
use netherforge_platform::{
    GeneratedChunk, GeneratedLoot, Platform, ProjectGenerator, SharedTerrain, TerrainSource,
    TerrainWorld, WorldSettings,
};
use netherforge_terrain::LuauScripts;

use crate::Room;
use crate::session::{Define, Service};
use crate::shared::Shared;

/// The stone a file falls back on when it names none the server has.
const DEFAULT_STONE: &str = "minecraft:stone";
/// How far from where it's asked a spawn looks for dry ground.
const DRY_RADIUS: i32 = 512;
/// The most failures of one generator's script said between publishes.
const MAX_FAILURES: usize = 20;

/// A generator the server's chunk threads run: format's compiled file bound per world, and its script's states.
pub struct RustTerrain {
    terrain: CompiledTerrain,
    scripts: Option<LuauScripts>,
    bound: Mutex<HashMap<TerrainWorld, Arc<TerrainGenerator>>>,
}

impl RustTerrain {
    pub fn new(terrain: CompiledTerrain, scripts: Option<LuauScripts>) -> RustTerrain {
        RustTerrain {
            terrain,
            scripts,
            bound: Mutex::new(HashMap::new()),
        }
    }

    /// The generator of `world`, bound the first time it's asked for.
    fn of(&self, world: TerrainWorld) -> Arc<TerrainGenerator> {
        let mut bound = self.bound.lock().unwrap_or_else(|e| e.into_inner());
        bound
            .entry(world)
            .or_insert_with(|| {
                Arc::new(self.terrain.bind(
                    world.seed,
                    world.min_y,
                    world.max_y,
                    self.scripts.as_ref().map(|s| {
                        s as &dyn netherforge_format::kind::terrain::generate::TerrainScripts
                    }),
                ))
            })
            .clone()
    }
}

impl TerrainSource for RustTerrain {
    fn stone(&self) -> String {
        self.terrain
            .palette
            .get(self.terrain.stone as usize)
            .map_or(DEFAULT_STONE, TerrainBlock::label)
            .to_owned()
    }

    fn sea_level(&self) -> i32 {
        self.terrain.sea_level
    }

    fn vanilla_structures(&self) -> bool {
        self.terrain.vanilla_structures
    }

    fn biomes(&self) -> Vec<String> {
        self.terrain
            .biomes()
            .into_iter()
            .map(str::to_owned)
            .collect()
    }

    fn has_loot(&self) -> bool {
        !self.terrain.loot.is_empty()
    }

    fn generate(&self, world: TerrainWorld, chunk_x: i32, chunk_z: i32) -> GeneratedChunk {
        let buffer = self.of(world).generate(chunk_x, chunk_z);
        let loot = buffer
            .loot_places(chunk_x, chunk_z)
            .into_iter()
            .filter_map(|place| {
                Some(GeneratedLoot {
                    x: place.x,
                    y: place.y,
                    z: place.z,
                    table: self.terrain.loot.get(place.table as usize)?.clone(),
                })
            })
            .collect();
        GeneratedChunk {
            min_y: buffer.min_y,
            height: buffer.height,
            blocks: buffer.blocks,
            loot,
        }
    }

    fn surface_at(&self, world: TerrainWorld, x: i32, z: i32) -> i32 {
        self.of(world).surface_at(x, z)
    }

    fn dry_column_near(&self, world: TerrainWorld, x: i32, z: i32) -> Option<(i32, i32)> {
        self.of(world).dry_column_near(x, z, DRY_RADIUS)
    }

    fn biome_at(&self, world: TerrainWorld, x: i32, y: i32, z: i32) -> String {
        let generator = self.of(world);
        generator.sampling(|sampler| generator.biome_at(x, y, z, sampler).to_owned())
    }

    fn locate_heights(&self, world: TerrainWorld, y: i32, step: i32) -> Vec<i32> {
        self.of(world).locate_heights(y, step)
    }

    fn first_where(
        &self,
        world: TerrainWorld,
        columns: &[(i32, i32)],
        ys: &[i32],
        wanted: &BTreeSet<String>,
    ) -> Option<(i32, i32)> {
        let generator = self.of(world);
        let areas = generator.areas_where(&|biome| wanted.contains(biome));
        if !areas.iter().any(|a| *a) {
            return None;
        }
        generator.sampling(|sampler| {
            columns.iter().enumerate().find_map(|(k, (x, z))| {
                sampler
                    .first_where(*x, *z, ys, &areas)
                    .map(|y| (k as i32, y))
            })
        })
    }

    fn close(&self) {
        for generator in self
            .bound
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .values()
        {
            generator.close();
        }
    }
}

/// What the generators' scripts failed at on the chunk threads, said once each on the main thread.
#[derive(Default)]
pub struct Failures {
    /// Told from any thread: the generator and what failed.
    heard: Arc<Mutex<Vec<(String, ScriptFailure)>>>,
    /// Each generator's said ones (stage and message), and what they are as problems.
    said: HashMap<String, BTreeSet<String>>,
    problems: Vec<Problem>,
}

impl Failures {
    fn reporter(&self, id: &str) -> netherforge_terrain::Failed {
        let heard = self.heard.clone();
        let id = id.to_owned();
        Arc::new(move |failure| {
            heard
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .push((id.clone(), failure));
        })
    }

    /// A publish starts over: what was said is said again if it fails again.
    fn restart(&mut self) {
        self.said.clear();
        self.problems.clear();
        self.heard.lock().unwrap_or_else(|e| e.into_inner()).clear();
    }

    /// Says what was heard since the last time; whether anything new was.
    fn drain(&mut self, platform: &dyn Platform, file_of: &dyn Fn(&str) -> String) -> bool {
        let heard = std::mem::take(&mut *self.heard.lock().unwrap_or_else(|e| e.into_inner()));
        let mut changed = false;
        for (id, failure) in heard {
            self.add(&id, &failure, platform, file_of);
            changed = true;
        }
        changed
    }

    fn add(
        &mut self,
        id: &str,
        failure: &ScriptFailure,
        platform: &dyn Platform,
        file_of: &dyn Fn(&str) -> String,
    ) {
        let said = self.said.entry(id.to_owned()).or_default();
        let key = format!("{}\n{}", failure.stage, failure.message);
        if said.len() >= MAX_FAILURES || !said.insert(key) {
            return;
        }
        let message = format!(
            "the script's {} failed, so the file's own result stands there: {}",
            failure.stage, failure.message
        );
        platform.log().warn(format!("terrain {id}: {message}"));
        self.problems
            .push(netherforge_format::codes::RUNTIME_TERRAIN.at(&file_of(id), message, None));
    }
}

/// The terrains the session publishes.
#[derive(Default)]
pub struct Terrains {
    /// The generators as the project names them (`hills`, a package's `acme:hills`).
    pub ids: BTreeSet<String>,
    problems: Vec<Problem>,
    world_problems: Vec<Problem>,
    failures: Failures,
    /// Each generator's file, for its problems.
    files: HashMap<String, String>,
}

/// The terrains' service.
pub struct TerrainsService {
    pub shared: Rc<Shared>,
    pub terrains: RefCell<Terrains>,
}

/// What publishing the project's generators reads: the project, the server, the states its blocks are held in, and
/// where its files are on disk (a decoration's structure is read from its file).
pub struct Publishing<'p> {
    pub loaded: &'p netherforge_format::project::Project<'p>,
    pub source: &'p dyn netherforge_format::project::ProjectSource,
    pub platform: &'p dyn Platform,
    pub state_of: &'p dyn Fn(&str) -> Option<String>,
    pub paths: Option<crate::screen::video::ProjectPaths>,
}

/// What a publish made: each generator by the name the project gives it, the problems it found, and each one's file.
pub struct Published {
    pub generators: HashMap<String, ProjectGenerator>,
    pub problems: Vec<Problem>,
    pub files: HashMap<String, String>,
}

/// The project's running terrains as the server's generators (Kotlin's `WorldGenerators.resolveGenerators`): each
/// linked to the structures it places, its script's blocks in its palette, every block as the state written into a
/// chunk and every biome as the server's key. What a script fails at goes to `failures`.
pub fn resolve_generators(p: &Publishing, failures: &mut Failures) -> Published {
    let loaded = p.loaded;
    let platform = p.platform;
    let home = loaded.namespace().to_owned();
    let mut problems = Vec::new();
    let biomes: BTreeSet<String> = loaded
        .everywhere("biome")
        .into_iter()
        .filter(|(_, r)| !r.errors)
        .filter_map(|(name, _)| netherforge_format::kind::biome::key_of(&name, &home))
        .collect();
    let own_files: Vec<String> = p.source.files().iter().cloned().collect();
    let mut generators = HashMap::new();
    let mut files = HashMap::new();
    for (id, compiled) in loaded.compiled::<CompiledTerrain>() {
        let file = loaded.place_of("terrain", &id);
        files.insert(id.clone(), file.clone());
        let mut problem = |message: String| {
            platform.log().warn(format!("terrain {id}: {message}"));
            problems.push(netherforge_format::codes::RUNTIME_TERRAIN.at(&file, message, None));
        };
        let terrain = link(compiled, p, &mut problem);
        let namespace = id.split_once(':').map(|(ns, _)| ns.to_owned());
        // A script reads its own files and its package's modules, once, here: a chunk thread never reads a file.
        let sources = match (&terrain.script, &namespace) {
            (Some(script), None) => netherforge_format::kind::terrain::scripts::sources_of(
                script,
                &own_files,
                &|path| p.source.read(path),
            ),
            _ => BTreeMap::new(),
        };
        let terrain = if terrain.script.is_some() {
            let texts: Vec<&str> = sources.values().map(String::as_str).collect();
            terrain.with_script_blocks(
                &texts,
                &|state| platform.game().block_properties(state).is_some(),
                namespace.as_deref(),
                &|name| (p.state_of)(name).is_some(),
            )
        } else {
            terrain
        };
        let scripts = terrain.script.as_ref().map(|_| LuauScripts {
            sources: Arc::new(sources),
            namespace: namespace.clone(),
            failed: failures.reporter(&id),
        });
        if let Some(scripts) = &scripts
            && let Some(failure) = scripts.check(&terrain)
        {
            failures.add(&id, &failure, platform, &|_| file.clone());
        }
        let stone = terrain
            .palette
            .get(terrain.stone as usize)
            .and_then(|b| match b {
                TerrainBlock::Vanilla { state } => Some(state.clone()),
                TerrainBlock::Custom { .. } => None,
            })
            .unwrap_or_else(|| DEFAULT_STONE.to_owned());
        let states: Vec<String> = terrain
            .palette
            .iter()
            .map(|block| match block {
                TerrainBlock::Vanilla { state } => state.clone(),
                TerrainBlock::Custom { name } => (p.state_of)(name).unwrap_or_else(|| {
                    problem(format!(
                        "The block \"{name}\" has no state the server can hold it in (see the problems of its blocks), so {stone} is generated in its place"
                    ));
                    stone.clone()
                }),
            })
            .collect();
        let keys: HashMap<String, String> = terrain
            .biomes()
            .into_iter()
            .filter_map(|biome| {
                let key = netherforge_format::kind::biome::key_of(biome, &home)?;
                if !key.starts_with("minecraft:") && !biomes.contains(&key) {
                    problem(format!(
                        "The biome \"{biome}\" has problems (see its file), so its areas are plains until it's fixed and the server restarts"
                    ));
                }
                Some((biome.to_owned(), key))
            })
            .collect();
        let source = SharedTerrain(Arc::new(RustTerrain::new(terrain, scripts)));
        generators.insert(
            id.clone(),
            ProjectGenerator {
                terrain: None,
                states,
                biomes: keys,
                scripts: None,
                source: Some(source),
            },
        );
    }
    Published {
        generators,
        problems,
        files,
    }
}

/// `compiled` with the structures its decorations place; one that can't be read isn't placed, and `problem` is told.
fn link(
    compiled: &CompiledTerrain,
    p: &Publishing,
    problem: &mut dyn FnMut(String),
) -> CompiledTerrain {
    let names = compiled.structures();
    if names.is_empty() {
        return compiled.clone();
    }
    let loaded = p.loaded;
    let mut templates: BTreeMap<String, StructureTemplate> = BTreeMap::new();
    for name in names {
        let template = resolve(loaded.namespace(), RefKind::Structure, name)
            .map(|key| loaded.place_of("structure", &key.relative_to(loaded.namespace())))
            .and_then(|path| p.paths.as_ref().and_then(|paths| (paths.0)(&path)))
            .and_then(|file| p.platform.structures().template(file));
        match template {
            Some(template) => {
                templates.insert(name.to_owned(), template);
            }
            None => problem(format!(
                "The structure \"{name}\" can't be read as one (see the problems of its file), so it isn't placed"
            )),
        }
    }
    compiled.with_structures(&templates)
}

/// The id a `netherforge.json` reference to a generator names, as the generators are published (`hills`, a package's
/// `acme:hills`); none when it names none.
pub fn generator_of(
    loaded: &netherforge_format::project::Project,
    reference: &str,
) -> Option<String> {
    let key = resolve(loaded.namespace(), RefKind::Terrain, reference)?;
    Some(if key.namespace == loaded.namespace() {
        key.path
    } else {
        key.to_string()
    })
}

impl TerrainsService {
    /// Publishes every running terrain of `project` to the server.
    fn publish(&self, project: &Define) {
        let platform = project.platform;
        let paths = self.shared.structures.borrow().paths.clone();
        let shared = self.shared.clone();
        let state_of = move |name: &str| shared.blocks.borrow().state_of(name, None);
        let publishing = Publishing {
            loaded: project.project,
            source: project.source,
            platform,
            state_of: &state_of,
            paths,
        };
        let mut terrains = self.terrains.borrow_mut();
        terrains.failures.restart();
        let published = resolve_generators(&publishing, &mut terrains.failures);
        // A project with no terrains, now or before, has nothing to tell the server.
        let had = !terrains.ids.is_empty();
        terrains.ids = published.generators.keys().cloned().collect();
        terrains.problems = published.problems;
        terrains.files = published.files;
        drop(terrains);
        if had || !published.generators.is_empty() {
            platform
                .world_manager()
                .publish_generators(published.generators);
        }
    }

    /// Makes the worlds `netherforge.json` names with a `terrain` or a `dimensionType` (Kotlin's `ensureMade`).
    fn make_worlds(&self, project: &Define) -> Vec<Problem> {
        let loaded = project.project;
        let platform = project.platform;
        let Some(worlds) = loaded.manifest.as_ref().and_then(|m| m.worlds.clone()) else {
            return Vec::new();
        };
        let mut problems = Vec::new();
        let main = platform.worlds().default_world();
        let ops = platform.world_manager();
        for (name, config) in worlds.0 {
            let name = name.0;
            let id = config
                .terrain
                .as_deref()
                .and_then(|t| generator_of(loaded, t));
            let dimension = config
                .dimension_type
                .as_deref()
                .and_then(|d| resolve(loaded.namespace(), RefKind::DimensionType, d))
                .map(|k| k.to_string());
            if id.is_none() && dimension.is_none() {
                continue;
            }
            let at = format!("$.worlds.{name}.dimensionType");
            let mut problem = |message: String| {
                platform.log().warn(message.clone());
                problems.push(netherforge_format::codes::RUNTIME_DIMENSION_TYPE.at(
                    netherforge_format::manifest::FILE_NAME,
                    message,
                    Some(at.clone()),
                ));
            };
            if name == main {
                continue;
            }
            let made = self.shared.managed_worlds.borrow().made_with(&name);
            if platform.worlds().exists(name.clone()) || ops.is_saved(name.clone()) {
                let made_type = made.as_ref().and_then(|m| m.dimension_type.clone());
                if dimension.is_some() && made_type != dimension {
                    let how = match &made_type {
                        Some(t) => format!("with dimension type \"{t}\""),
                        None => "without a dimension type of the project's".to_owned(),
                    };
                    problem(format!(
                        "World \"{name}\" was made {how}, and keeps it: netherforge.json's \"{}\" is for a world made afresh (delete this one to have it made so)",
                        dimension.clone().unwrap_or_default()
                    ));
                }
                if platform.worlds().exists(name.clone()) {
                    continue;
                }
                if let Some(t) = &made_type
                    && !ops.dimension_types().contains(t)
                {
                    problem(format!(
                        "World \"{name}\" isn't loaded: it was made with dimension type \"{t}\", which the server doesn't have (it learns them only as it starts, so one added or fixed since needs a restart)"
                    ));
                    continue;
                }
                if ops.load(name.clone(), "normal".into(), id.clone()) {
                    self.shared.managed_worlds.borrow_mut().remember(
                        &self.shared,
                        &name,
                        "normal",
                        id.clone(),
                        made_type,
                    );
                } else {
                    platform
                        .log()
                        .warn(format!("Couldn't load world \"{name}\""));
                }
                continue;
            }
            if let Some(d) = &dimension
                && !ops.dimension_types().contains(d)
            {
                problem(format!(
                    "World \"{name}\" isn't made: the server has no dimension type \"{d}\" (it learns them only as it starts, so one added or fixed since needs a restart)"
                ));
                continue;
            }
            let settings = WorldSettings {
                generator: "normal".into(),
                environment: "normal".into(),
                seed: config.seed,
                structures: true,
                keep_spawn_loaded: false,
                terrain: id.clone(),
                dimension_type: dimension.clone(),
            };
            if ops.create(name.clone(), settings) {
                self.shared.managed_worlds.borrow_mut().remember(
                    &self.shared,
                    &name,
                    "normal",
                    id,
                    dimension,
                );
            } else {
                platform
                    .log()
                    .warn(format!("Couldn't make world \"{name}\""));
            }
        }
        problems
    }
}

impl Service for TerrainsService {
    fn name(&self) -> &'static str {
        "terrains"
    }

    fn reloads(&self) -> &'static [&'static str] {
        &["terrain"]
    }

    /// After the blocks have started (the service runs after theirs), so the chunks the worlds it makes generate are
    /// read for custom blocks.
    fn start(&self, _room: &Room, project: &Define) {
        self.publish(project);
        self.make_worlds_now(project);
    }

    fn reload(&self, _room: &Room, _kind: &str, _ids: &BTreeSet<String>, project: &Define) {
        self.publish(project);
    }

    fn reloaded(&self, _room: &Room, kinds: &BTreeSet<&str>, project: &Define) {
        // A block's state can move when the project's blocks change; a structure a decoration places, or a module a
        // script requires, is read again when it's saved.
        if kinds.contains("block") || kinds.contains("structure") || kinds.contains("module") {
            self.publish(project);
        }
    }

    fn tick(&self, _room: &Room, platform: &dyn Platform) {
        let mut terrains = self.terrains.borrow_mut();
        let files = terrains.files.clone();
        terrains.failures.drain(platform, &|id| {
            files
                .get(id)
                .cloned()
                .unwrap_or_else(|| format!("terrain/{id}.json"))
        });
    }

    fn problems(&self) -> Vec<Problem> {
        let terrains = self.terrains.borrow();
        terrains
            .problems
            .iter()
            .chain(&terrains.world_problems)
            .chain(&terrains.failures.problems)
            .cloned()
            .collect()
    }
}

impl TerrainsService {
    /// After the blocks have started (so the chunks they generate are read for custom blocks): the worlds
    /// `netherforge.json` makes.
    pub fn make_worlds_now(&self, project: &Define) {
        let problems = self.make_worlds(project);
        self.terrains.borrow_mut().world_problems = problems;
    }
}
