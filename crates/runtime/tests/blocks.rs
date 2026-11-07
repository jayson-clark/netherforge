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
    fn resource_pack_format(&self) -> Option<[i32; 2]> {
        Some([75, 0])
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
    /// What Alex holds, by slot.
    held: RefCell<HashMap<String, netherforge_platform::ItemData>>,
    /// Whether the falling block entity is in the world.
    falling: RefCell<bool>,
    /// What `block:data()` saved, by position.
    data: RefCell<HashMap<At, String>>,
    /// Whether the server refuses to hold its carriers' states still.
    refuses_freeze: bool,
}

fn alex() -> netherforge_platform::PlayerRef {
    netherforge_platform::PlayerRef {
        uuid: netherforge_platform::Uuid(77),
        name: "Alex".into(),
    }
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

/// Alex, offline to everything that asks who's online, but there for what asks about them by UUID.
impl netherforge_platform::FakePlayerOps for Fake {
    fn online(&self) -> Vec<netherforge_platform::PlayerRef> {
        Vec::new()
    }
    fn get(&self, uuid: netherforge_platform::Uuid) -> Option<netherforge_platform::PlayerRef> {
        (uuid == alex().uuid).then(alex)
    }
    fn game_mode(&self, _: netherforge_platform::Uuid) -> Option<String> {
        Some("survival".into())
    }
    fn location(&self, _: netherforge_platform::Uuid) -> Option<netherforge_platform::Location> {
        Some(netherforge_platform::Location {
            world: "world".into(),
            x: 5.5,
            y: 64.0,
            z: 0.5,
            yaw: 0.0,
            pitch: 0.0,
        })
    }
}

/// What Alex holds, items dropped, and what's falling.
impl netherforge_platform::FakeWorldEntityOps for Fake {
    fn equipment(
        &self,
        _: netherforge_platform::Uuid,
        slot: String,
    ) -> Option<netherforge_platform::ItemData> {
        self.held.borrow().get(&slot).cloned()
    }
    fn set_equipment(
        &self,
        _: netherforge_platform::Uuid,
        slot: String,
        item: Option<netherforge_platform::ItemData>,
    ) -> bool {
        self.lines.borrow_mut().push(format!(
            "held {slot} {}",
            item.as_ref().map_or("nothing".to_owned(), |i| format!(
                "{}x{}",
                i.def.item.clone().unwrap_or(i.def.kind.clone()),
                i.def.count.unwrap_or(1)
            ))
        ));
        match item {
            Some(item) => self.held.borrow_mut().insert(slot, item),
            None => self.held.borrow_mut().remove(&slot),
        };
        true
    }
    fn spawn_item(
        &self,
        _: String,
        at: netherforge_format::math::Vec3,
        item: netherforge_platform::ItemData,
    ) -> Option<netherforge_platform::Uuid> {
        self.lines.borrow_mut().push(format!(
            "dropped {}x{} at {} {} {}",
            item.def.kind,
            item.def.count.unwrap_or(1),
            at.0,
            at.1,
            at.2
        ));
        Some(netherforge_platform::Uuid(9))
    }
    /// The one falling block, while it falls.
    fn info(&self, id: netherforge_platform::Uuid) -> Option<netherforge_platform::EntityInfo> {
        (id == netherforge_platform::Uuid(500) && *self.falling.borrow()).then(|| {
            netherforge_platform::EntityInfo {
                id,
                kind: "minecraft:falling_block".into(),
                location: netherforge_platform::Location {
                    world: "world".into(),
                    x: 9.5,
                    y: 65.0,
                    z: 9.5,
                    yaw: 0.0,
                    pitch: 0.0,
                },
                category: netherforge_platform::EntityCategory::Other,
                player: None,
                tags: Vec::new(),
            }
        })
    }
}

impl netherforge_platform::FakeSoundOps for Fake {
    fn play(
        &self,
        _: String,
        _: netherforge_format::math::Vec3,
        sound: netherforge_platform::SoundPlay,
    ) -> bool {
        self.lines
            .borrow_mut()
            .push(format!("sound {}", sound.sound));
        true
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
    fn heights(&self, _: String) -> Option<(i32, i32)> {
        Some((-64, 320))
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
        !self.refuses_freeze
    }
    fn watch(&self, _: Vec<String>, falling: Vec<String>) {
        self.lines
            .borrow_mut()
            .push(format!("watch falling {}", falling.len()));
    }
    fn can_place(&self, _: String, _: i32, _: i32, _: i32, _: String) -> bool {
        true
    }
    fn place(
        &self,
        _: netherforge_platform::Uuid,
        world: String,
        x: i32,
        y: i32,
        z: i32,
        state: String,
        _: netherforge_platform::BlockVector,
        _: String,
    ) -> bool {
        netherforge_platform::FakeBlockOps::set(self, world, x, y, z, state, true)
    }
    fn data(&self, _: String, x: i32, y: i32, z: i32) -> Option<String> {
        self.data.borrow().get(&(x, y, z)).cloned()
    }
    fn set_data(&self, _: String, x: i32, y: i32, z: i32, json: Option<String>) -> bool {
        match json {
            Some(json) => self.data.borrow_mut().insert((x, y, z), json),
            None => self.data.borrow_mut().remove(&(x, y, z)),
        };
        true
    }
    fn with_data(
        &self,
        _: String,
        chunk_x: i32,
        chunk_z: i32,
    ) -> Option<Vec<netherforge_platform::BlockVector>> {
        Some(
            self.data
                .borrow()
                .keys()
                .filter(|(x, _, z)| (x >> 4, z >> 4) == (chunk_x, chunk_z))
                .map(|(x, y, z)| netherforge_platform::BlockVector {
                    x: *x,
                    y: *y,
                    z: *z,
                })
                .collect(),
        )
    }

    /// A note block is 0.8 hard, everything else 1.5; a bare hand mines at speed 1.
    fn hardness(&self, state: String) -> Option<f64> {
        Some(if state.starts_with("minecraft:note_block") {
            0.8
        } else {
            1.5
        })
    }
    fn break_speed(&self, _: Option<netherforge_platform::ItemData>, _: String) -> Option<f64> {
        Some(1.0)
    }
    /// What a tick of mining makes with the speed turned down a millionfold: a tenth, before it's divided back out.
    fn break_progress(
        &self,
        _: netherforge_platform::Uuid,
        _: String,
        _: i32,
        _: i32,
        _: i32,
    ) -> Option<f64> {
        Some(0.1e-6)
    }
    fn show_breaking(
        &self,
        _: netherforge_platform::Uuid,
        _: String,
        _: i32,
        _: i32,
        _: i32,
        stage: i32,
    ) {
        self.lines.borrow_mut().push(format!("cracks {stage}"));
    }
    fn break_as(&self, _: netherforge_platform::Uuid, _: String, x: i32, y: i32, z: i32) -> bool {
        self.lines
            .borrow_mut()
            .push(format!("break_as {x} {y} {z}"));
        true
    }
    fn targeted(&self, _: netherforge_platform::Uuid) -> Option<netherforge_platform::BlockRef> {
        None
    }

    /// A block falls when there's air under it: it's gone from where it was, an entity now.
    fn fall(&self, _: String, x: i32, y: i32, z: i32) -> Option<netherforge_platform::Uuid> {
        if self.state((x, y - 1, z)).is_some() {
            return None;
        }
        self.states.borrow_mut().remove(&(x, y, z));
        *self.falling.borrow_mut() = true;
        Some(netherforge_platform::Uuid(500))
    }
}

/// Alex's break speed, said as it changes.
impl netherforge_platform::FakeAttributeOps for Fake {
    fn add_modifier(
        &self,
        _: netherforge_platform::Uuid,
        attribute: String,
        modifier: netherforge_platform::AttributeModifierData,
    ) -> bool {
        self.lines.borrow_mut().push(format!(
            "modifier {attribute} {} {}",
            modifier.id, modifier.amount
        ));
        true
    }
    fn remove_modifier(
        &self,
        _: netherforge_platform::Uuid,
        attribute: String,
        modifier: String,
    ) -> bool {
        self.lines
            .borrow_mut()
            .push(format!("unmodified {attribute} {modifier}"));
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

/// No structure has put a marker in the world.
impl netherforge_platform::FakeEntityOps for Fake {
    fn structure_markers(&self) -> Vec<netherforge_platform::StructureMarker> {
        Vec::new()
    }
}

impl FakePlatform for Fake {
    fn entities(&self) -> &dyn netherforge_platform::EntityOps {
        self
    }
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
    fn world_entities(&self) -> &dyn netherforge_platform::WorldEntityOps {
        self
    }
    fn sounds(&self) -> &dyn netherforge_platform::SoundOps {
        self
    }
    fn attributes(&self) -> &dyn netherforge_platform::AttributeOps {
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

fn at_ref(x: i32, y: i32, z: i32, state: &str) -> netherforge_platform::BlockRef {
    netherforge_platform::BlockRef {
        world: "world".into(),
        x,
        y,
        z,
        id: state.split('[').next().unwrap_or(state).into(),
        state: state.into(),
    }
}

#[test]
fn blocks_break_with_their_drops_place_from_items_and_fall() {
    use netherforge_platform::{ClickButton, PlatformEvents, game_event};
    use netherforge_runtime::server::ServerEvents;
    let platform = std::rc::Rc::new(Fake::new());
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        (
            "blocks/ruby_ore/block.json",
            r#"{ "drops": "ore", "script": { "file": "script.lua" } }"#,
        ),
        (
            "blocks/ruby_ore/script.lua",
            r#"
this:on("break", function(e) print("break", e.player:name(), #e.drops, e.drops[1].kind, e.block:custom():id()); table.insert(e.drops, { kind = "minecraft:coal" }) end)
this:on("place", function(e) print("place", e.player:name(), e.item.item, e.facing) end)
"#,
        ),
        ("blocks/sand_block/block.json", r#"{ "falls": true }"#),
        (
            "loot/ore.json",
            r#"{ "pools": { "main": { "entries": [ { "type": "item", "item": { "kind": "minecraft:diamond" } } ] } } }"#,
        ),
        (
            "items/ore_item/item.json",
            r#"{ "kind": "minecraft:stone", "block": "ruby_ore" }"#,
        ),
        (
            "modules/build/init.lua",
            r#"
local w = nf.worlds.default()
nf.blocks.get("ruby_ore"):place({ world = w, position = vector.create(1, 64, 1) })
nf.blocks.get("ruby_ore"):place({ world = w, position = vector.create(2, 64, 1) })
nf.blocks.get("sand_block"):place({ world = w, position = vector.create(9, 70, 9) })
"#,
        ),
    ]);
    platform
        .states
        .borrow_mut()
        .insert((5, 63, 5), "minecraft:stone".into());
    platform
        .states
        .borrow_mut()
        .insert((9, 60, 9), "minecraft:stone".into());
    let events = ServerEvents::new(Session::start(platform.clone(), &files, &NoPackages));
    let started = platform.take();
    assert!(
        !started
            .iter()
            .any(|l| l.starts_with("warn") || l.starts_with("error")),
        "{started:?}"
    );
    let ore = platform.state((1, 64, 1)).expect("placed");
    let sand = platform.state((9, 70, 9)).expect("placed");

    // Alex breaks one: the server's break is cancelled, and the runtime's drops what the block's handler left.
    let answer = events.block_break(alex(), at_ref(1, 64, 1, &ore), Box::new(Vec::new), 0);
    assert!(answer.is_none());
    assert_eq!(platform.state((1, 64, 1)), None);
    events.tick();
    let broke = platform.take();
    assert_eq!(
        printed(&broke),
        ["break\tAlex\t1\tminecraft:diamond\truby_ore"]
    );
    let effects: Vec<&String> = broke
        .iter()
        .filter(|l| l.starts_with("dropped") || l.starts_with("sound"))
        .collect();
    assert_eq!(
        effects,
        [
            "sound minecraft:block.stone.break",
            "dropped minecraft:diamondx1 at 1.5 64.5 1.5",
            "dropped minecraft:coalx1 at 1.5 64.5 1.5",
        ]
    );

    // A right click with the item that places it puts it on the face clicked, and takes one from the hand.
    let mut held = netherforge_platform::ItemData {
        def: netherforge_format::item::ItemDef {
            kind: "minecraft:stone".into(),
            item: Some("ore_item".into()),
            count: Some(2),
            ..Default::default()
        },
        raw: None,
        look: None,
    };
    let cancelled = events.player_interact(
        alex(),
        ClickButton::Right,
        Some(at_ref(5, 63, 5, "minecraft:stone")),
        Some("up".into()),
        Some(held.clone()),
        "main_hand".into(),
    );
    assert!(cancelled);
    assert_eq!(platform.state((5, 64, 5)).as_deref(), Some(ore.as_str()));
    events.tick();
    let placed = platform.take();
    assert_eq!(printed(&placed), ["place\tAlex\tore_item\tnil"]);
    assert!(
        placed.contains(&"held main_hand ore_itemx1".to_owned()),
        "{placed:?}"
    );

    // Picking it finds the stack that places it.
    held.def.count = Some(1);
    let pick = events.pick_block(alex(), at_ref(5, 64, 5, &ore), vec![None, Some(held)]);
    assert_eq!(pick.and_then(|p| p.slot), Some(1));

    // A piston doesn't move it; an explosion breaks it with its drops.
    let pushed = events.game().piston_extend(&mut game_event::Piston {
        block: at_ref(4, 64, 5, "minecraft:piston"),
        direction: "east".into(),
        sticky: false,
        blocks: vec![at_ref(5, 64, 5, &ore)],
    });
    assert!(pushed);
    let exploded = events.game().block_explode(&mut game_event::Explode {
        entity: None,
        block: None,
        state: None,
        location: netherforge_platform::Location {
            world: "world".into(),
            x: 2.0,
            y: 64.0,
            z: 1.0,
            yaw: 0.0,
            pitch: 0.0,
        },
        blocks: Box::new({
            let ore = ore.clone();
            move || vec![at_ref(2, 64, 1, &ore)]
        }),
        breaks_blocks: true,
        r#yield: 1.0,
    });
    assert!(!exploded);
    assert_eq!(platform.state((2, 64, 1)), None);
    assert!(
        platform
            .take()
            .contains(&"dropped minecraft:diamondx1 at 2.5 64.5 1.5".to_owned())
    );

    // The sand block had nothing under it: at the first tick it fell, and where it lands it's itself again.
    assert_eq!(platform.state((9, 70, 9)), None);
    platform
        .states
        .borrow_mut()
        .insert((9, 61, 9), sand.clone());
    events.falling_landed(netherforge_platform::Uuid(500), at_ref(9, 61, 9, &sand));
    let session = events.session();
    assert_eq!(
        session
            .shared()
            .blocks
            .borrow()
            .at("world", 9, 61, 9)
            .as_deref(),
        Some("sand_block")
    );
    assert_eq!(session.shared().blocks.borrow().at("world", 9, 70, 9), None);
}

#[test]
fn the_server_mines_a_block_and_breaks_it_as_the_players() {
    use netherforge_platform::{PlatformEvents, game_event};
    use netherforge_runtime::server::ServerEvents;
    let platform = std::rc::Rc::new(Fake::new());
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("blocks/ruby_ore/block.json", r#"{ "hardness": 1.5 }"#),
        ("blocks/glass/block.json", r#"{ "hardness": -1 }"#),
        (
            "modules/build/init.lua",
            r#"
local w = nf.worlds.default()
nf.blocks.get("ruby_ore"):place({ world = w, position = vector.create(1, 64, 1) })
nf.blocks.get("glass"):place({ world = w, position = vector.create(2, 64, 1) })
"#,
        ),
    ]);
    let events = ServerEvents::new(Session::start(platform.clone(), &files, &NoPackages));
    platform.take();
    let ore = platform.state((1, 64, 1)).expect("placed");
    let glass = platform.state((2, 64, 1)).expect("placed");
    // Glass nobody can break: the hit is refused.
    let refused = events
        .game()
        .block_start_break(&mut game_event::BlockStartBreak {
            player: alex(),
            block: at_ref(2, 64, 1, &glass),
            item: None,
            instant: false,
        });
    assert!(refused);
    // The ore is a note block's 0.8 against its 1.5: each tick makes a tenth times 0.8 / 1.5 of a break.
    let started = events
        .game()
        .block_start_break(&mut game_event::BlockStartBreak {
            player: alex(),
            block: at_ref(1, 64, 1, &ore),
            item: None,
            instant: false,
        });
    assert!(!started);
    assert_eq!(
        platform.take(),
        ["modifier minecraft:block_break_speed demo:mining -0.999999"]
    );
    for _ in 0..19 {
        events.tick();
    }
    let mined: Vec<String> = platform
        .take()
        .into_iter()
        .filter(|l| l.starts_with("cracks") || l.starts_with("break_as"))
        .collect();
    assert_eq!(
        mined,
        [
            "cracks 0",
            "cracks 1",
            "cracks 2",
            "cracks 3",
            "cracks 4",
            "cracks 5",
            "cracks 6",
            "cracks 7",
            "cracks 8",
            "cracks -1",
            "break_as 1 64 1"
        ]
    );
}

#[test]
fn a_blocks_table_is_saved_with_its_chunk_and_goes_with_the_block() {
    let platform = std::rc::Rc::new(Fake::new());
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("blocks/ruby_ore/block.json", "{}"),
        (
            "modules/build/init.lua",
            r#"
local w = nf.worlds.default()
nf.blocks.get("ruby_ore"):place({ world = w, position = vector.create(1, 64, 1) })
local data = w:block(vector.create(1, 64, 1)):data()
data.owner = "Alex"
print(w:block(vector.create(1, 64, 1)):data() == data, w:block(vector.create(1, 64, 1)):data().owner)
nf.on("demo:break", function() w:set_block(vector.create(1, 64, 1), "minecraft:stone") end)
"#,
        ),
    ]);
    let session = Session::start(platform.clone(), &files, &NoPackages);
    assert_eq!(printed(&platform.take()), ["true\tAlex"]);
    assert!(platform.records.borrow().is_empty());
    // The world saves: the table is written, and the block, known by its state alone, keeps a record for it.
    session.world_saving("world");
    assert_eq!(
        platform.data.borrow().get(&(1, 64, 1)).map(String::as_str),
        Some(r#"{"owner":"Alex"}"#)
    );
    assert_eq!(
        platform
            .records
            .borrow()
            .get(&(1, 64, 1))
            .map(String::as_str),
        Some(r#"{"id":"ruby_ore"}"#)
    );
    // Something replaces the block: its table and record go.
    let room = session.room().unwrap();
    room.scripts()
        .emit_custom(
            netherforge_runtime::Target::Nf,
            "demo:break".into(),
            mlua::Value::Table(room.host().lua().create_table().unwrap()),
        )
        .unwrap();
    assert!(platform.data.borrow().is_empty());
    assert!(platform.records.borrow().is_empty());
}

#[test]
fn a_server_that_wont_hold_its_carriers_still_is_a_problem_with_the_blocks() {
    let platform = std::rc::Rc::new(Fake {
        refuses_freeze: true,
        ..Fake::new()
    });
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("blocks/ruby_ore/block.json", "{}"),
    ]);
    let session = Session::start(platform.clone(), &files, &NoPackages);
    let problems = session.problems();
    let problem = problems
        .iter()
        .find(|p| p.code.as_deref() == Some("runtime.blocks"))
        .unwrap_or_else(|| panic!("{problems:?}"));
    assert!(
        problem
            .message
            .contains("won't stop working note block states out"),
        "{problem:?}"
    );
    assert!(
        platform
            .take()
            .iter()
            .any(|l| l.starts_with("error This server won't"))
    );
}
