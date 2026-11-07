//! Worlds on the Rust runtime beyond their blocks (Kotlin's `WorldTest`, `StructureTest`, `ManagedWorldsTest`):
//! biome searches off the main thread, structures saved and placed, gradual placements and fills shared out over
//! ticks, and the worlds scripts create, load, copy, import and unload.

use std::cell::{Cell, RefCell};
use std::collections::{BTreeMap, BTreeSet};
use std::rc::Rc;
use std::time::{Duration, Instant};

use netherforge_format::math::Vec3;
use netherforge_format::package::NoPackages;
use netherforge_format::project::ProjectSource;
use netherforge_platform::{
    BiomeSearch, FakeBiomeSearch, FakePlatform, FakePlatformLog, FakeWorldOps, Location,
    PlatformError, PlatformLog, ServerInfo, Uuid,
};
use netherforge_runtime::session::{Config, Session};

/// Game facts that know no registry, and every block as one without properties.
struct NoFacts;

impl netherforge_format::game::GameData for NoFacts {
    fn minecraft_version(&self) -> &str {
        "26.3"
    }
    fn registry(&self, _: &str) -> Option<&BTreeSet<String>> {
        None
    }
    fn tag(&self, _: &str, _: &str) -> Option<&BTreeSet<String>> {
        None
    }
    fn item_durability(&self, _: &str) -> u32 {
        0
    }
    fn particle(&self, _: &str) -> Option<netherforge_format::game::ParticleData> {
        None
    }
    fn block_properties(&self, _: &str) -> Option<BTreeMap<String, Vec<String>>> {
        Some(BTreeMap::new())
    }
}

#[derive(Default)]
struct Fake {
    lines: RefCell<Vec<String>>,
    /// The worlds there are.
    worlds: RefCell<BTreeSet<String>>,
    /// What each block was set to.
    blocks: RefCell<BTreeMap<(String, i32, i32, i32), String>>,
    /// Where biome searches looked from.
    searched: RefCell<Vec<String>>,
    sets: Cell<usize>,
    /// Structure files' sizes, by file name; a saved one is added as it's saved.
    sizes: RefCell<BTreeMap<String, (i32, i32, i32)>>,
    /// What was done with structures, in order.
    placed: RefCell<Vec<String>>,
    /// Markers of centities in the world.
    markers: RefCell<Vec<netherforge_platform::StructureMarker>>,
    /// Worlds saved but not loaded.
    saved: RefCell<BTreeSet<String>>,
    /// What the world manager was asked, in order (file work included, from its lane).
    managed: std::sync::Arc<std::sync::Mutex<Vec<String>>>,
    /// What the session said it watches, in order.
    watched: RefCell<Vec<String>>,
}

impl Fake {
    fn new() -> Rc<Fake> {
        let fake = Fake::default();
        fake.worlds.borrow_mut().insert("world".into());
        Rc::new(fake)
    }

    fn lines(&self) -> Vec<String> {
        std::mem::take(&mut *self.lines.borrow_mut())
            .into_iter()
            .filter_map(|line| {
                if let Some(rest) = line.strip_prefix("info [module t] ") {
                    let text = rest.rsplit_once(" (modules/").map_or(rest, |(t, _)| t);
                    Some(text.replace('\t', " "))
                } else if line.starts_with("warn") || line.starts_with("error") {
                    Some(line)
                } else {
                    None
                }
            })
            .collect()
    }
}

impl FakePlatformLog for Fake {
    fn info(&self, message: String) {
        self.lines.borrow_mut().push(format!("info {message}"));
    }
    fn warn(&self, message: String) {
        self.lines.borrow_mut().push(format!("warn {message}"));
    }
    fn error(&self, message: String, _: Option<PlatformError>) {
        self.lines.borrow_mut().push(format!("error {message}"));
    }
}

impl netherforge_platform::FakeTeamOps for Fake {
    fn names(&self) -> Vec<String> {
        Vec::new()
    }
    fn clear_below_names(&self) {}
}

impl netherforge_platform::FakePlayerOps for Fake {
    fn online(&self) -> Vec<netherforge_platform::PlayerRef> {
        Vec::new()
    }
    fn get(&self, _: Uuid) -> Option<netherforge_platform::PlayerRef> {
        None
    }
}

/// Finds plains 100 blocks east of where it starts, and nothing else.
struct Search {
    from: (i32, i32, i32),
    biome: String,
}

impl FakeBiomeSearch for Search {
    fn run(&self) -> Option<Vec3> {
        std::thread::sleep(Duration::from_millis(20));
        (self.biome == "minecraft:plains")
            .then(|| Vec3(f64::from(self.from.0 + 100), 64.0, f64::from(self.from.2)))
    }
}

fn file_name(file: &std::path::Path) -> String {
    file.file_name().unwrap().to_string_lossy().into_owned()
}

/// Goes through a file's blocks, each one placed.
struct Placer {
    size: (i32, i32, i32),
    left: Cell<i32>,
    entities: bool,
    calls: Rc<RefCell<Vec<String>>>,
    name: String,
}

impl netherforge_platform::FakeStructurePlacer for Placer {
    fn size(&self) -> netherforge_platform::BlockVector {
        netherforge_platform::BlockVector {
            x: self.size.0,
            y: self.size.1,
            z: self.size.2,
        }
    }
    fn has_entities(&self) -> bool {
        self.entities
    }
    fn remaining(&self) -> i32 {
        self.left.get()
    }
    fn placed(&self) -> i64 {
        i64::from(self.size.0 * self.size.1 * self.size.2)
    }
    fn place(&self, budget: i32) -> Option<i32> {
        let went = budget.min(self.left.get());
        self.left.set(self.left.get() - went);
        Some(went)
    }
    fn entities(&self) -> bool {
        self.calls
            .borrow_mut()
            .push(format!("entities {}", self.name));
        true
    }
}

impl netherforge_platform::FakeStructureOps for Fake {
    fn size(&self, file: std::path::PathBuf) -> Option<netherforge_platform::BlockVector> {
        let (x, y, z) = *self.sizes.borrow().get(&file_name(&file))?;
        Some(netherforge_platform::BlockVector { x, y, z })
    }
    fn place(
        &self,
        file: std::path::PathBuf,
        world: String,
        at: netherforge_platform::BlockVector,
        placement: netherforge_platform::StructurePlacement,
    ) -> bool {
        self.placed.borrow_mut().push(format!(
            "place {} {world} {} {} {} {} {}",
            file_name(&file),
            at.x,
            at.y,
            at.z,
            placement.rotation,
            placement.mirror
        ));
        true
    }
    fn placer(
        &self,
        file: std::path::PathBuf,
        _world: String,
        transform: netherforge_platform::StructureTransform,
        _placement: netherforge_platform::StructurePlacement,
        _seed: i64,
    ) -> Option<Box<dyn netherforge_platform::StructurePlacer>> {
        let name = file_name(&file);
        let size = *self.sizes.borrow().get(&name)?;
        self.placed.borrow_mut().push(format!(
            "open {name} at {} {} {} + {} {} {}",
            transform.origin.x,
            transform.origin.y,
            transform.origin.z,
            transform.offset.x,
            transform.offset.y,
            transform.offset.z
        ));
        Some(Box::new(Placer {
            size,
            left: Cell::new(size.0 * size.1 * size.2),
            entities: name == "0_0_0.nbt",
            calls: Rc::new(RefCell::new(Vec::new())),
            name,
        }))
    }
    fn save(
        &self,
        file: std::path::PathBuf,
        _world: String,
        min: netherforge_platform::BlockVector,
        max: netherforge_platform::BlockVector,
        _entities: bool,
    ) -> bool {
        std::fs::write(&file, b"nbt").unwrap();
        self.sizes.borrow_mut().insert(
            file_name(&file),
            (max.x - min.x + 1, max.y - min.y + 1, max.z - min.z + 1),
        );
        true
    }
    fn forget(&self, file: std::path::PathBuf) {
        self.placed
            .borrow_mut()
            .push(format!("forget {}", file_name(&file)));
    }
}

impl netherforge_platform::FakeEntityOps for Fake {
    fn structure_markers(&self) -> Vec<netherforge_platform::StructureMarker> {
        self.markers.borrow().clone()
    }
    fn remove(&self, id: Uuid) -> bool {
        let mut markers = self.markers.borrow_mut();
        let before = markers.len();
        markers.retain(|m| m.id != id);
        before != markers.len()
    }
}

/// Copies or deletes a world's files: a copy of a map named `broken` fails.
struct Work {
    what: String,
    log: std::sync::Arc<std::sync::Mutex<Vec<String>>>,
}

impl netherforge_platform::FakeFileWork for Work {
    fn run(&self) {
        std::thread::sleep(Duration::from_millis(5));
        if self.what.contains("broken") {
            panic!("the disk is full");
        }
        self.log.lock().unwrap().push(self.what.clone());
    }
}

impl netherforge_platform::FakeWorldManagerOps for Fake {
    fn is_saved(&self, name: String) -> bool {
        self.saved.borrow().contains(&name) || self.worlds.borrow().contains(&name)
    }
    fn create(&self, name: String, settings: netherforge_platform::WorldSettings) -> bool {
        self.managed.lock().unwrap().push(format!(
            "create {name} {} {} {:?} {:?}",
            settings.generator, settings.environment, settings.terrain, settings.dimension_type
        ));
        self.worlds.borrow_mut().insert(name);
        true
    }
    fn dimension_types(&self) -> Vec<String> {
        vec!["minecraft:overworld".into(), "test:deep".into()]
    }
    fn load(&self, name: String, environment: String, terrain: Option<String>) -> bool {
        if !self.saved.borrow_mut().remove(&name) {
            return false;
        }
        self.managed
            .lock()
            .unwrap()
            .push(format!("load {name} {environment} {terrain:?}"));
        self.worlds.borrow_mut().insert(name);
        true
    }
    fn copy(
        &self,
        map: std::path::PathBuf,
        name: String,
    ) -> Box<dyn netherforge_platform::FileWork> {
        // The files are in place once the work has run: as the fake has it, at once.
        self.saved.borrow_mut().insert(name.clone());
        Box::new(Work {
            what: format!("copy {} {name}", map.display()),
            log: self.managed.clone(),
        })
    }
    fn unload(&self, name: String, save: bool) -> bool {
        self.managed
            .lock()
            .unwrap()
            .push(format!("unload {name} {save}"));
        self.worlds.borrow_mut().remove(&name);
        self.saved.borrow_mut().insert(name);
        true
    }
    fn delete(&self, name: String) -> Box<dyn netherforge_platform::FileWork> {
        self.saved.borrow_mut().remove(&name);
        Box::new(Work {
            what: format!("delete {name}"),
            log: self.managed.clone(),
        })
    }
}

impl FakeWorldOps for Fake {
    fn default_world(&self) -> String {
        "world".into()
    }
    fn exists(&self, world: String) -> bool {
        self.worlds.borrow().contains(&world)
    }
    fn names(&self) -> Vec<String> {
        self.worlds.borrow().iter().cloned().collect()
    }
    fn spawn_location(&self, world: String) -> Option<Location> {
        self.exists(world.clone()).then(|| Location {
            world,
            x: 8.5,
            y: 70.0,
            z: -3.5,
            yaw: 0.0,
            pitch: 0.0,
        })
    }
    fn heights(&self, world: String) -> Option<(i32, i32)> {
        self.exists(world).then_some((-64, 320))
    }
    fn is_chunk_loaded(&self, world: String, _: i32, _: i32) -> bool {
        self.exists(world)
    }
    fn load_chunk(&self, world: String, _: i32, _: i32) -> bool {
        self.exists(world)
    }
    fn biome_search(
        &self,
        world: String,
        x: i32,
        y: i32,
        z: i32,
        radius: i32,
        biome: String,
    ) -> Option<Box<dyn BiomeSearch>> {
        if !self.exists(world.clone()) {
            return None;
        }
        self.searched
            .borrow_mut()
            .push(format!("{world} {x} {y} {z} {radius} {biome}"));
        Some(Box::new(Search {
            from: (x, y, z),
            biome,
        }))
    }
}

impl netherforge_platform::FakeBlockOps for Fake {
    fn set(&self, world: String, x: i32, y: i32, z: i32, state: String, _: bool) -> bool {
        if !self.exists(world.clone()) {
            return false;
        }
        self.sets.set(self.sets.get() + 1);
        self.blocks.borrow_mut().insert((world, x, y, z), state);
        true
    }
}

impl FakePlatform for Fake {
    fn watch(&self, event: netherforge_platform::WatchedEvent, listening: bool) {
        self.watched
            .borrow_mut()
            .push(format!("{event:?} {listening}"));
    }
    fn info(&self) -> ServerInfo {
        ServerInfo {
            minecraft_version: "26.3".into(),
            plugin_version: "0.0.0".into(),
            supported_targets: vec!["26.3".into()],
        }
    }
    fn log(&self) -> &dyn PlatformLog {
        self
    }
    fn game(&self) -> &dyn netherforge_format::game::GameData {
        &NoFacts
    }
    fn worlds(&self) -> &dyn netherforge_platform::WorldOps {
        self
    }
    fn blocks(&self) -> &dyn netherforge_platform::BlockOps {
        self
    }
    fn structures(&self) -> &dyn netherforge_platform::StructureOps {
        self
    }
    fn world_manager(&self) -> &dyn netherforge_platform::WorldManagerOps {
        self
    }
    fn entities(&self) -> &dyn netherforge_platform::EntityOps {
        self
    }
    fn players(&self) -> &dyn netherforge_platform::PlayerOps {
        self
    }
    fn teams(&self) -> &dyn netherforge_platform::TeamOps {
        self
    }
}

struct Files {
    names: &'static BTreeSet<String>,
    texts: RefCell<BTreeMap<String, String>>,
}

impl Files {
    fn new(extra: &[(&str, &str)], script: &str) -> Files {
        let manifest = r#"{ "formatVersion": 1, "name": "Test", "namespace": "test", "version": "1.0.0", "minecraft": "26.3" }"#;
        let mut files = vec![
            ("netherforge.json".to_owned(), manifest.to_owned()),
            ("modules/t/init.lua".to_owned(), script.to_owned()),
        ];
        files.extend(extra.iter().map(|(p, t)| (p.to_string(), t.to_string())));
        Files {
            names: Box::leak(Box::new(files.iter().map(|(p, _)| p.clone()).collect())),
            texts: RefCell::new(files.into_iter().collect()),
        }
    }
}

impl ProjectSource for Files {
    fn files(&self) -> &BTreeSet<String> {
        self.names
    }
    fn read(&self, path: &str) -> Option<String> {
        self.texts.borrow().get(path).cloned()
    }
}

fn start<'a>(files: &'a Files, platform: &Rc<Fake>, config: Config) -> Session<'a> {
    Session::start_configured(
        platform.clone(),
        files,
        &NoPackages,
        Rc::new(netherforge_runtime::store::Store::in_memory()),
        config,
    )
}

/// Ticks until the scripts have said `count` lines (or five seconds go by): what they said.
fn printed(session: &Session, platform: &Fake, count: usize) -> Vec<String> {
    let mut seen = platform.lines();
    let started = Instant::now();
    while seen.len() < count && started.elapsed() < Duration::from_secs(5) {
        session.tick();
        seen.extend(platform.lines());
        std::thread::sleep(Duration::from_millis(1));
    }
    seen
}

const TRY: &str = r#"local function try(f, ...) local ok, a = pcall(f, ...) print(ok, (tostring(a):gsub("^[^:]+:%d+: ", ""))) end
local world = nf.worlds.default()
"#;

#[test]
fn a_biome_search_runs_off_the_main_thread_and_answers_at_a_tick() {
    let files = Files::new(
        &[],
        &format!(
            r#"{TRY}
world:locate_biome("minecraft:plains", nil, function(at, err) print("plains", at and at.x, err) end)
world:locate_biome("minecraft:desert", {{ near = vector.create(0, 64, 0), radius = 50 }}, function(at, err) print("desert", at, err) end)
nf.task(function()
  local at = world:locate_biome("minecraft:plains", {{ near = vector.create(10.7, 64, 2) }})
  print("waited", at.x, at.z)
end)
try(world.locate_biome, world, "minecraft:plains", {{ radius = 0 }}, print)
try(world.locate_biome, world, "minecraft:plains", {{ radius = 6401 }}, print)
"#
        ),
    );
    let platform = Fake::new();
    let session = start(&files, &platform, Config::default());
    let mut lines = printed(&session, &platform, 5);
    lines.sort();
    assert_eq!(
        lines,
        [
            "desert nil no minecraft:desert within 50 blocks",
            "false options.radius must be 1 to 6400, not 0",
            "false options.radius must be 1 to 6400, not 6401",
            "plains 108 nil",
            "waited 110 2",
        ]
    );
    // Near the world's spawn, unless the script says where.
    assert_eq!(
        platform.searched.borrow()[0],
        "world 8 70 -4 6400 minecraft:plains"
    );
}

/// A config whose project files are under `/project` and whose data folder is `data`.
fn on_disk(data: &std::path::Path) -> Config {
    Config {
        data_dir: Some(data.to_owned()),
        paths: Some(netherforge_runtime::screen::video::ProjectPaths(Rc::new(
            |path: &str| Some(std::path::PathBuf::from(format!("/project/{path}"))),
        ))),
        structures: netherforge_runtime::structures::StructuresConfig {
            blocks_per_tick: 1000,
            max_jobs_per_package: 2,
        },
        ..Config::default()
    }
}

#[test]
fn structures_are_the_projects_its_builds_and_scripts_saves() {
    let data = tempfile::tempdir().unwrap();
    let files = Files::new(
        &[
            ("structures/arena.nbt", ""),
            ("builds/castle/0_0_0.nbt", ""),
            ("builds/castle/1_0_0.nbt", ""),
        ],
        &format!(
            r#"{TRY}
print(nf.structures.exists("arena"), nf.structures.exists("castle"), nf.structures.exists("hut"))
local size = nf.structures.size("castle")
print(size.x, size.y, size.z, nf.structures.size("arena").x, nf.structures.size("hut"))
print(world:place_structure("arena", vector.create(1.5, 64, -2), {{ rotation = -90, mirror = "left_right" }}))
try(world.place_structure, world, "castle", vector.create(0, 64, 0))
try(world.place_structure, world, "arena", vector.create(0, 64, 0), {{ rotation = 45 }})
try(world.place_structure, world, "arenx", vector.create(0, 64, 0))
print(world:save_structure("hut", vector.create(0, 64, 0), vector.create(3, 66, 4)))
print(nf.structures.exists("hut"), nf.structures.size("hut").z)
try(world.save_structure, world, "arena", vector.create(0, 64, 0), vector.create(1, 1, 1))
try(world.save_structure, world, "big", vector.create(0, 0, 0), vector.create(48, 1, 1))
try(world.save_structure, world, "Bad Name", vector.create(0, 0, 0), vector.create(1, 1, 1))
"#
        ),
    );
    let platform = Fake::new();
    platform.sizes.borrow_mut().extend([
        ("arena.nbt".to_owned(), (5, 3, 5)),
        ("0_0_0.nbt".to_owned(), (48, 10, 20)),
        ("1_0_0.nbt".to_owned(), (12, 10, 20)),
    ]);
    let _session = start(&files, &platform, on_disk(data.path()));
    assert_eq!(
        platform.lines(),
        [
            "true true false",
            "60 10 20 5 nil",
            "true",
            "false \"castle\" is a build (builds/castle/), too big to place in one tick: place it with world:place_gradually",
            "false rotation must be a multiple of 90, not 45",
            "false no structure \"arenx\" in this project (did you mean \"arena\"?) (the project's are structures/<id>.nbt and builds/<id>/; scripts add more with world:save_structure)",
            "true",
            "true 5",
            "false \"arena\" is one of the project's structures (structures/arena.nbt): save under another id",
            "false that box is 49×2×2 blocks; a structure is at most 48 along each side",
            "false \"Bad Name\" can't name a structure (lowercase letters, digits and _, starting with a letter or digit, at most 64 characters)",
        ]
    );
    assert!(data.path().join(".nf/structures/hut.nbt").is_file());
    assert_eq!(
        platform.placed.borrow()[0],
        "place arena.nbt world 1 64 -2 270 left_right"
    );
}

#[test]
fn gradual_placements_and_fills_share_the_ticks_blocks() {
    let data = tempfile::tempdir().unwrap();
    let files = Files::new(
        &[
            ("builds/castle/0_0_0.nbt", ""),
            ("builds/castle/1_0_0.nbt", ""),
        ],
        &format!(
            r#"{TRY}
world:place_gradually("castle", vector.create(100, 64, 0), nil, function(placed, err) print("castle", placed, err) end)
world:fill_gradually(vector.create(0, 64, 0), vector.create(9, 64, 9), "minecraft:stone", {{ per_tick = 30 }}, function(changed, err) print("fill", changed, err) end)
world:fill_gradually(vector.create(0, 0, 0), vector.create(1, 1, 1), "minecraft:dirt", nil, function(changed, err) print("third", changed, err) end)
try(world.fill_gradually, world, vector.create(0, 0, 0), vector.create(1, 1, 1), "minecraft:dirt", {{ per_tick = 0 }}, print)
try(world.place_gradually, world, "nope", vector.create(0, 0, 0), nil, print)
"#
        ),
    );
    let platform = Fake::new();
    platform.sizes.borrow_mut().extend([
        ("0_0_0.nbt".to_owned(), (48, 10, 20)),
        ("1_0_0.nbt".to_owned(), (12, 10, 20)),
    ]);
    let session = start(&files, &platform, on_disk(data.path()));
    assert_eq!(
        platform.lines(),
        [
            "false options.per_tick must be 1 or more, not 0",
            "false no structure \"nope\" in this project (the project's are structures/<id>.nbt and builds/<id>/; scripts add more with world:save_structure)",
        ]
    );
    // Two at most a package: the third fails at once (at the next tick, as every async value comes).
    session.tick();
    assert_eq!(
        platform.lines(),
        [
            "third nil package \"test\" already has 2 gradual placements and fills going, the server's limit (structures.max-jobs-per-package in config.yml)"
        ]
    );
    // The fill goes 30 a tick, the castle the rest of the tick's thousand.
    let before = platform.sets.get();
    session.tick();
    assert_eq!(platform.sets.get() - before, 30);
    let mut lines = printed(&session, &platform, 2);
    lines.sort();
    assert_eq!(lines, ["castle 12000 nil", "fill 100 nil"]);
    assert_eq!(
        platform.placed.borrow().as_slice(),
        [
            "open 0_0_0.nbt at 100 64 0 + 0 0 0",
            "open 1_0_0.nbt at 100 64 0 + 48 0 0",
            // Its entities, last: only the parts that have any, read again.
            "open 0_0_0.nbt at 100 64 0 + 0 0 0",
        ]
    );
    assert_eq!(
        platform.blocks.borrow()[&("world".to_owned(), 9, 64, 9)],
        "minecraft:stone"
    );
}

#[test]
fn a_marker_naming_a_centity_the_project_lacks_stays() {
    let files = Files::new(&[], "");
    let platform = Fake::new();
    platform
        .markers
        .borrow_mut()
        .push(netherforge_platform::StructureMarker {
            id: Uuid(9),
            centity: "test:guard".into(),
            at: Location {
                world: "world".into(),
                x: 1.0,
                y: 64.0,
                z: 1.0,
                yaw: 0.0,
                pitch: 0.0,
            },
        });
    let _session = start(&files, &platform, Config::default());
    assert_eq!(
        platform.lines(),
        [
            "warn A structure has a marker for the centity \"test:guard\", which the project doesn't have: the marker stays in the world, and spawns it once it does"
        ]
    );
    assert_eq!(platform.markers.borrow().len(), 1);
}

#[test]
fn the_worlds_a_project_makes_are_its_own_to_unload_and_the_store_remembers_them() {
    let files = Files::new(
        &[
            ("maps/lobby/level.dat", ""),
            ("maps/broken/level.dat", ""),
            (
                "dimension_types/deep.json",
                r#"{ "height": 384, "minY": -64 }"#,
            ),
        ],
        &format!(
            r#"{TRY}
local arena = nf.worlds.create("arena", {{ seed = 7 }})
print(arena:name(), arena:is_managed(), world:is_managed())
print(nf.worlds.create("deep_one", {{ dimension_type = "deep" }}) ~= nil)
try(nf.worlds.create, "arena")
try(nf.worlds.create, "Bad Name")
try(nf.worlds.create, "x", {{ terrain = "hills" }})
try(nf.worlds.create, "x", {{ dimension_type = "shallow" }})
print(nf.worlds.load("old") ~= nil, nf.worlds.load("nowhere"))
nf.worlds.copy("lobby", "lobby_1", function(w, err) print("copied", w and w:name(), err, w and w:is_managed()) end)
nf.worlds.copy("broken", "broken_1", function(w, err) print("copied", w, err) end)
try(nf.worlds.copy, "lobbi", "x", print)
try(nf.worlds.import, "somewhere", "x", nil, print)
try(world.unload, world)
try(nf.worlds.get("old").unload, nf.worlds.get("old"))
print(arena:unload({{ delete = true }}), arena:is_managed())
"#
        ),
    );
    let platform = Fake::new();
    platform.saved.borrow_mut().insert("old".into());
    let store = Rc::new(netherforge_runtime::store::Store::in_memory());
    let config = || Config {
        paths: Some(netherforge_runtime::screen::video::ProjectPaths(Rc::new(
            |path: &str| Some(std::path::PathBuf::from(format!("/project/{path}"))),
        ))),
        ..Config::default()
    };
    let mut session = Session::start_configured(
        platform.clone(),
        &files,
        &NoPackages,
        store.clone(),
        config(),
    );
    let mut lines = printed(&session, &platform, 15);
    lines.sort();
    assert_eq!(
        lines,
        [
            "arena true false",
            "copied lobby_1 nil true",
            "copied nil couldn't copy map \"broken\" into world \"broken_1\": couldn't copy its files (the disk is full)",
            "false \"Bad Name\" can't be a new world's name (lowercase letters, digits and _, starting with a letter or digit, at most 64 characters)",
            "false \"hills\" isn't one of the project's terrains (it has none)",
            "false \"old\" isn't this project's to unload: only worlds its scripts created, or that netherforge.json names in managedWorlds, can be",
            "false \"shallow\" isn't one of the project's dimension types (it has: deep)",
            "false importing worlds is off on this server (worlds.import-root in config.yml)",
            "false no map \"lobbi\" in this project (did you mean \"lobby\"?)",
            "false the server's main world can't be unloaded",
            "false there's already a world named \"arena\" (nf.worlds.load loads a saved one)",
            "true",
            "true false",
            "true nil",
            "warn Couldn't copy map \"broken\" into world \"broken_1\": couldn't copy its files (the disk is full)",
        ]
    );
    let managed = platform.managed.lock().unwrap().clone();
    assert!(
        managed.contains(&"create arena normal normal None None".to_owned()),
        "{managed:?}"
    );
    assert!(
        managed.contains(&"create deep_one normal normal None Some(\"test:deep\")".to_owned()),
        "{managed:?}"
    );
    assert!(
        managed.contains(&"copy /project/maps/lobby lobby_1".to_owned()),
        "{managed:?}"
    );
    assert!(
        managed.contains(&"load lobby_1 normal None".to_owned()),
        "{managed:?}"
    );
    // The world deleted went from the store; the others are the project's after a restart.
    session.stop();
    drop(session);
    assert!(
        platform
            .managed
            .lock()
            .unwrap()
            .contains(&"delete arena".to_owned())
            || {
                std::thread::sleep(Duration::from_millis(50));
                platform
                    .managed
                    .lock()
                    .unwrap()
                    .contains(&"delete arena".to_owned())
            }
    );
    let again = Files::new(
        &[],
        r#"print(nf.worlds.get("deep_one"):is_managed(), nf.worlds.get("lobby_1"):is_managed())"#,
    );
    let _session =
        Session::start_configured(platform.clone(), &again, &NoPackages, store, config());
    assert_eq!(platform.lines(), ["true true"]);
}

#[test]
fn vectors_and_locations_have_their_methods_and_vec3_builds_them() {
    let files = Files::new(
        &[],
        &format!(
            r#"{TRY}
local v = vec3(3, 4, 0)
print(tostring(v), v:length(), v.x, (v + vec3.up):unpack())
print(tostring(vec3(1, 2, 3) * 2), tostring(-vec3.east), v == vec3(3, 4, 0))
print(tostring(v:normalized()), tostring(v:flat():with_y(7)), v:dot(vec3.one))
print(tostring(vec3.from_yaw_pitch(0, 0):round()), tostring(vec3(1, 0, 0):rotated(vec3.up, 90):round()))
try(v.length, 5)
try(v.dot, v, 1)
try(function() vec3("a", 1, 2) end)
try(function() vec3.zero = 1 end)
local here = world:location(vec3(1, 2, 3), 90, 0)
print(tostring(here), tostring(here:offset(vec3(0, 10, 0)).position), tostring(here:direction():round()))
print(here == world:location(vec3(1, 2, 3), 90, 0), here:with_position(vec3.zero).yaw)
try(function() here.yaw = 1 end)
"#
        ),
    );
    let platform = Fake::new();
    let session = start(&files, &platform, Config::default());
    let lines = printed(&session, &platform, 11);
    assert_eq!(
        lines,
        [
            "vec3(3, 4, 0) 5 3 3 5 0",
            "vec3(2, 4, 6) vec3(-1, 0, 0) true",
            "vec3(0.6000000238418579, 0.800000011920929, 0) vec3(3, 7, 0) 7",
            "vec3(0, 0, 1) vec3(0, 0, -1)",
            "false call Vec3 methods with ':' on a Vec3, like v:length()",
            "false bad argument 'other' (Vec3 expected, got number)",
            "false bad argument 'x' (number expected, got string)",
            "false vec3 is shared by every script and can't be changed",
            "location(\"world\", vec3(1, 2, 3), 90, 0) vec3(1, 12, 3) vec3(-1, 0, 0)",
            "true 90",
            "false locations can't be changed (setting 'yaw'): make a new one, like location:with_position(v)",
        ]
    );
}

#[test]
fn a_location_goes_through_json_by_its_tag() {
    let files = Files::new(
        &[],
        &format!(
            r#"{TRY}
local text = nf.json.encode({{ at = world:location(vec3(1, 2.5, 3), 90) }})
print(text)
local back = nf.json.decode(text).at
print(tostring(back), back == world:location(vec3(1, 2.5, 3), 90))
"#
        ),
    );
    let platform = Fake::new();
    let session = start(&files, &platform, Config::default());
    assert_eq!(
        printed(&session, &platform, 2),
        [
            r#"{"at":{"$location":["world",1,2.5,3,90,null]}}"#,
            r#"location("world", vec3(1, 2.5, 3), 90, 0) true"#,
        ]
    );
}

#[test]
fn the_server_is_told_which_watched_events_scripts_listen_to() {
    let files = Files::new(
        &[],
        &format!(
            r#"{TRY}
local chat = nf.on("player_chat", function() end)
world:on("block_grow", function() end)
nf.after(2, function() chat:cancel() print("cancelled") end)
"#
        ),
    );
    let platform = Fake::new();
    let mut session = start(&files, &platform, Config::default());
    assert_eq!(
        *platform.watched.borrow(),
        ["PlayerChat true", "BlockGrow true"]
    );
    printed(&session, &platform, 1);
    session.tick();
    assert_eq!(
        platform.watched.borrow().last().unwrap(),
        "PlayerChat false"
    );
    session.stop();
    assert_eq!(platform.watched.borrow().last().unwrap(), "BlockGrow false");
}
