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
