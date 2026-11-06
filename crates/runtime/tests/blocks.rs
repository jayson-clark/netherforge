//! The project's blocks on the Rust runtime against a fake world that keeps block states, records and legends,
//! with a game whose note blocks have a few states to hold blocks in.

use std::cell::RefCell;
use std::collections::{BTreeMap, BTreeSet, HashMap};

use netherforge_format::game::GameData;
use netherforge_format::package::NoPackages;
use netherforge_format::project::ProjectSource;
use netherforge_platform::{
    BlockRecord, BlockSnapshot, ChunkGrid, FakePlatform, FakePlatformLog, PlatformError,
    PlatformLog, ServerInfo,
};
use netherforge_runtime::session::Session;

/// Note blocks with two instruments, four notes and power: sixteen states, the harp's the player's own.
struct Game;

impl GameData for Game {
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
    fn block_properties(&self, id: &str) -> Option<BTreeMap<String, Vec<String>>> {
        let list = |v: &[&str]| v.iter().map(|s| s.to_string()).collect();
        Some(if id == "minecraft:note_block" {
            BTreeMap::from([
                ("instrument".to_owned(), list(&["harp", "basedrum"])),
                ("note".to_owned(), list(&["0", "1", "2", "3"])),
                ("powered".to_owned(), list(&["false", "true"])),
            ])
        } else {
            BTreeMap::new()
        })
    }
}

type At = (i32, i32, i32);

#[derive(Default)]
struct Fake {
    lines: RefCell<Vec<String>>,
    /// Every block that isn't air, as its state.
    states: RefCell<HashMap<At, String>>,
    records: RefCell<HashMap<At, String>>,
    legends: RefCell<HashMap<(i32, i32), String>>,
    /// The chunks loaded: only chunk (0, 0).
    loaded: RefCell<Vec<(i32, i32)>>,
}

impl Fake {
    fn new() -> Fake {
        Fake {
            loaded: RefCell::new(vec![(0, 0)]),
            ..Default::default()
        }
    }
    fn take(&self) -> Vec<String> {
        std::mem::take(&mut self.lines.borrow_mut())
    }
    fn state(&self, at: At) -> Option<String> {
        self.states.borrow().get(&at).cloned()
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
}

impl netherforge_platform::FakeWorldOps for Fake {
    fn names(&self) -> Vec<String> {
        vec!["world".into()]
    }
    fn default_world(&self) -> String {
        "world".into()
    }
    fn exists(&self, world: String) -> bool {
        world == "world"
    }
    fn loaded_chunks(&self, _: String) -> Vec<(i32, i32)> {
        self.loaded.borrow().clone()
    }
}

impl netherforge_platform::FakeBlockOps for Fake {
    fn get(&self, _: String, x: i32, y: i32, z: i32) -> Option<BlockSnapshot> {
        let state = self.state((x, y, z));
        Some(BlockSnapshot {
            air: state.is_none(),
            state: state.unwrap_or_else(|| "minecraft:air".into()),
            solid: true,
            ..default_snapshot()
        })
    }
    fn set(&self, _: String, x: i32, y: i32, z: i32, state: String, _: bool) -> bool {
        if !self.loaded.borrow().contains(&(x >> 4, z >> 4)) {
            return false;
        }
        if state == "minecraft:air" {
            self.states.borrow_mut().remove(&(x, y, z));
        } else {
            self.states.borrow_mut().insert((x, y, z), state);
        }
        true
    }
    fn records(&self, _: String, chunk_x: i32, chunk_z: i32) -> Option<Vec<BlockRecord>> {
        let mut records: Vec<BlockRecord> = self
            .records
            .borrow()
            .iter()
            .filter(|((x, _, z), _)| (x >> 4, z >> 4) == (chunk_x, chunk_z))
            .map(|(at, json)| BlockRecord {
                x: at.0,
                y: at.1,
                z: at.2,
                json: json.clone(),
            })
            .collect();
        records.sort_by_key(|r| (r.x, r.y, r.z));
        Some(records)
    }
    fn set_record(&self, _: String, x: i32, y: i32, z: i32, json: Option<String>) -> bool {
        match json {
            Some(json) => self.records.borrow_mut().insert((x, y, z), json),
            None => self.records.borrow_mut().remove(&(x, y, z)),
        };
        true
    }
    fn legend(&self, _: String, chunk_x: i32, chunk_z: i32) -> Option<String> {
        self.legends.borrow().get(&(chunk_x, chunk_z)).cloned()
    }
    fn set_legend(&self, _: String, chunk_x: i32, chunk_z: i32, json: Option<String>) -> bool {
        match json {
            Some(json) => self.legends.borrow_mut().insert((chunk_x, chunk_z), json),
            None => self.legends.borrow_mut().remove(&(chunk_x, chunk_z)),
        };
        true
    }
    fn find(
        &self,
        _: String,
        chunk_x: i32,
        chunk_z: i32,
        states: Vec<String>,
    ) -> Option<ChunkGrid> {
        let mut grid = ChunkGrid::new();
        for (at, state) in self.states.borrow().iter() {
            if (at.0 >> 4, at.2 >> 4) != (chunk_x, chunk_z) {
                continue;
            }
            if let Some(i) = states.iter().position(|s| s == state) {
                grid.set(at.0 & 15, at.1, at.2 & 15, (i + 1) as u16);
            }
        }
        Some(grid)
    }
    /// Never the same twice: the sweep always reads the chunk.
    fn fingerprint(&self, _: String, _: i32, _: i32, _: Vec<String>) -> Option<i64> {
        None
    }
    fn freeze(&self, _: netherforge_format::kind::block::BlockCarrier) -> bool {
        true
    }
}

fn default_snapshot() -> BlockSnapshot {
    BlockSnapshot {
        state: String::new(),
        air: true,
        solid: false,
        liquid: false,
        light: 0,
        sky_light: 0,
        water: false,
    }
}

impl FakePlatform for Fake {
    fn players(&self) -> &dyn netherforge_platform::PlayerOps {
        self
    }
    fn teams(&self) -> &dyn netherforge_platform::TeamOps {
        self
    }
    fn worlds(&self) -> &dyn netherforge_platform::WorldOps {
        self
    }
    fn blocks(&self) -> &dyn netherforge_platform::BlockOps {
        self
    }
    fn game(&self) -> &dyn GameData {
        &Game
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
}

/// A project in memory, whose files can change.
struct Files {
    names: RefCell<&'static BTreeSet<String>>,
    texts: RefCell<BTreeMap<String, String>>,
}

impl Files {
    fn new(files: &[(&str, &str)]) -> Files {
        Files {
            names: RefCell::new(Box::leak(Box::new(
                files.iter().map(|(p, _)| p.to_string()).collect(),
            ))),
            texts: RefCell::new(
                files
                    .iter()
                    .map(|(p, t)| (p.to_string(), t.to_string()))
                    .collect(),
            ),
        }
    }
    fn add(&self, path: &str, text: &str) {
        let mut names = (*self.names.borrow()).clone();
        names.insert(path.into());
        *self.names.borrow_mut() = Box::leak(Box::new(names));
        self.texts.borrow_mut().insert(path.into(), text.into());
    }
}

impl ProjectSource for Files {
    fn files(&self) -> &BTreeSet<String> {
        *self.names.borrow()
    }
    fn read(&self, path: &str) -> Option<String> {
        self.texts.borrow().get(path).cloned()
    }
}

const MANIFEST: &str = r#"{ "formatVersion": 1, "name": "Demo", "namespace": "demo", "version": "1.0.0", "minecraft": "26.3" }"#;

fn printed(lines: &[String]) -> Vec<String> {
    lines
        .iter()
        .filter_map(|l| l.strip_prefix("info ["))
        .map(|l| {
            let text = l.split_once("] ").map_or(l, |(_, t)| t);
            text.rsplit_once(" (").map_or(text, |(t, _)| t).to_owned()
        })
        .collect()
}

#[test]
fn a_placed_block_is_held_in_its_state_and_read_back_through_the_legend() {
    let platform = std::rc::Rc::new(Fake::new());
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        (
            "blocks/ruby_ore/block.json",
            r#"{ "tick": 2, "properties": { "worth": 5 }, "script": { "file": "script.lua" } }"#,
        ),
        (
            "blocks/ruby_ore/script.lua",
            r#"this:on("tick", function(e) print("tick", e.block:id()) end)"#,
        ),
        ("blocks/chair/block.json", r#"{ "rotation": "horizontal" }"#),
        (
            "modules/build/init.lua",
            r#"
local w = nf.worlds.default()
local ore = nf.blocks.get("ruby_ore")
local at = { world = w, position = vector.create(1, 64, 1) }
local placed = ore:place(at)
print(placed:id(), placed:project() == ore, ore:properties().worth, #nf.blocks.all(), nf.blocks.get("nope"))
print(w:block(vector.create(1, 64, 1)):custom() == placed, w:block(vector.create(2, 64, 1)):custom())
local chair = nf.blocks.get("chair"):place({ world = w, position = vector.create(3, 64, 3) }, { facing = "east" })
print(chair:facing(), chair:set_facing("south"), chair:facing(), chair:set_facing("up"))
print(pcall(ore.place, ore, at, { facing = "east" }))
print(ore:place({ world = w, position = vector.create(100, 64, 100) }))
"#,
        ),
    ]);
    let mut session = Session::start(platform.clone(), &files, &NoPackages);
    let started = platform.take();
    assert!(
        !started
            .iter()
            .any(|l| l.starts_with("warn") || l.starts_with("error")),
        "{started:?}"
    );
    assert_eq!(
        printed(&started),
        [
            "ruby_ore\ttrue\t5\t2\tnil",
            "true\tnil",
            "east\ttrue\tsouth\tfalse",
            "false\tmodules/build/init.lua:10: options.facing: block ruby_ore can't face east (it doesn't turn: its block.json has no rotation)",
            "nil",
        ]
    );
    // Held as note block states: the chair takes four (one a way it faces), the ore one, in name order.
    let ore = platform.state((1, 64, 1)).expect("placed");
    let chair = platform.state((3, 64, 3)).expect("placed");
    assert!(ore.starts_with("minecraft:note_block["), "{ore}");
    assert_ne!(ore, chair);
    let legend: BTreeMap<String, String> =
        serde_json::from_str(&platform.legends.borrow()[&(0, 0)]).expect("a legend");
    assert_eq!(legend.get(&ore).map(String::as_str), Some("ruby_ore"));
    assert_eq!(legend.get(&chair).map(String::as_str), Some("chair#south"));
    assert!(
        platform.records.borrow().is_empty(),
        "{:?}",
        platform.records.borrow()
    );
    // It ticks every 2 ticks, heard by its script.
    for _ in 0..4 {
        session.tick();
    }
    let raw = platform.take();
    let ticks: Vec<String> = printed(&raw);
    assert_eq!(
        ticks,
        ["tick\truby_ore", "tick\truby_ore"],
        "{raw:?} {started:?}"
    );

    // A block added before them in name order moves their states; reading the chunk again puts them right.
    files.add("blocks/amber/block.json", "{}");
    session.reload(&["blocks/amber/block.json".to_owned()]);
    let ore_now = platform.state((1, 64, 1)).expect("still there");
    assert_ne!(ore_now, ore, "the ore's state moved");
    let legend: BTreeMap<String, String> =
        serde_json::from_str(&platform.legends.borrow()[&(0, 0)]).expect("a legend");
    assert_eq!(legend.get(&ore_now).map(String::as_str), Some("ruby_ore"));
    assert!(!legend.values().any(|v| v == "amber"), "{legend:?}");

    // A script's set_block takes it away: forgotten, so it's no longer custom.
    files.add(
        "modules/build/init.lua",
        r#"
local w = nf.worlds.default()
print(w:block(vector.create(1, 64, 1)):custom():id())
w:set_block(vector.create(1, 64, 1), "minecraft:stone")
print(w:block(vector.create(1, 64, 1)):custom(), w:block(vector.create(3, 64, 3)):custom():facing())
"#,
    );
    session.reload(&["modules/build/init.lua".to_owned()]);
    assert_eq!(printed(&platform.take()), ["ruby_ore", "nil\tsouth"]);
}

#[test]
fn a_chunk_that_loads_takes_up_the_blocks_its_states_are() {
    let platform = std::rc::Rc::new(Fake::new());
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("blocks/ruby_ore/block.json", "{}"),
        (
            "modules/look/init.lua",
            r#"
nf.on("demo:look", function()
  local w = nf.worlds.default()
  local function at(x) local b = w:block(vector.create(x, 64, 0)):custom() return b and b:id() end
  print(at(20), at(21))
end)
"#,
        ),
    ]);
    let session = Session::start(platform.clone(), &files, &NoPackages);
    // Where the ore would be: place one in the loaded chunk to learn its state.
    let room = session.room().unwrap();
    let look = || {
        let lua = room.host().lua();
        room.scripts()
            .emit_custom(
                netherforge_runtime::Target::Nf,
                "demo:look".into(),
                mlua::Value::Table(lua.create_table().unwrap()),
            )
            .unwrap();
        session.tick();
        printed(&platform.take())
    };
    netherforge_runtime::custom_blocks::BlocksContext {
        shared: session.shared().clone(),
        scripts: room.scripts().clone(),
    }
    .place("ruby_ore", "world", 1, 64, 0, None);
    let state = platform.state((1, 64, 0)).expect("placed");
    // Chunk (1, 0) holds that state twice, a paste with no legend: both are taken up as the ore.
    platform
        .states
        .borrow_mut()
        .insert((20, 64, 0), state.clone());
    platform
        .states
        .borrow_mut()
        .insert((21, 64, 0), state.clone());
    platform.loaded.borrow_mut().push((1, 0));
    session.chunk_loaded("world", 1, 0, false);
    assert_eq!(look(), ["ruby_ore\truby_ore"]);
    assert!(platform.legends.borrow().contains_key(&(1, 0)));
    // It unloads and loads again: its legend says what they are.
    session.chunk_unloading("world", 1, 0);
    assert_eq!(look(), ["nil\tnil"]);
    session.chunk_loaded("world", 1, 0, false);
    assert_eq!(look(), ["ruby_ore\truby_ore"]);
}
