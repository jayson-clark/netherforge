//! Screens on the Rust runtime against a fake server that hangs frames and takes map packets, with a game of a few
//! map colours and a two-letter map font.

use std::cell::RefCell;
use std::collections::{BTreeMap, BTreeSet, HashMap};

use netherforge_format::game::GameData;
use netherforge_format::package::NoPackages;
use netherforge_format::project::ProjectSource;
use netherforge_platform::{
    FakePlatform, FakePlatformLog, Location, MapFont, MapGlyph, PlatformError, PlatformLog,
    PlayerRef, ServerInfo, Uuid,
};
use netherforge_runtime::session::Session;

/// Map colours: black, red, green, blue, white from index 4 (0 to 3 draw nothing).
const COLORS: [u32; 9] = [
    0,
    0,
    0,
    0,
    0xFF00_0000,
    0xFFFF_0000,
    0xFF00_FF00,
    0xFF00_00FF,
    0xFFFF_FFFF,
];

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
    fn block_properties(&self, _: &str) -> Option<BTreeMap<String, Vec<String>>> {
        None
    }
    fn map_colors(&self) -> Option<&[u32]> {
        Some(&COLORS)
    }
}

fn alex() -> PlayerRef {
    PlayerRef {
        uuid: Uuid(77),
        name: "Alex".into(),
    }
}

#[derive(Default)]
struct Fake {
    lines: RefCell<Vec<String>>,
    frames: RefCell<u128>,
}

impl Fake {
    fn take(&self) -> Vec<String> {
        std::mem::take(&mut self.lines.borrow_mut())
    }
    fn say(&self, line: String) {
        self.lines.borrow_mut().push(line);
    }
}

impl FakePlatformLog for Fake {
    fn info(&self, message: String) {
        self.say(format!("info {message}"));
    }
    fn warn(&self, message: String) {
        self.say(format!("warn {message}"));
    }
    fn error(&self, message: String, _: Option<PlatformError>) {
        self.say(format!("error {message}"));
    }
}

impl netherforge_platform::FakeTeamOps for Fake {
    fn names(&self) -> Vec<String> {
        Vec::new()
    }
    fn clear_below_names(&self) {}
}

/// Alex, online in `world` near the origin.
impl netherforge_platform::FakePlayerOps for Fake {
    fn online(&self) -> Vec<PlayerRef> {
        vec![alex()]
    }
    fn get(&self, uuid: Uuid) -> Option<PlayerRef> {
        (uuid == alex().uuid).then(alex)
    }
    fn find(&self, name: String) -> Option<PlayerRef> {
        (name == "Alex").then(alex)
    }
    fn known(&self, name: String) -> Option<PlayerRef> {
        (name == "Alex").then(alex)
    }
    fn location(&self, _: Uuid) -> Option<Location> {
        Some(Location {
            world: "world".into(),
            x: 0.0,
            y: 64.0,
            z: 0.0,
            yaw: 0.0,
            pitch: 0.0,
        })
    }
}

impl netherforge_platform::FakePermissionOps for Fake {
    fn apply(&self, _: Uuid, _: HashMap<String, bool>) {}
}

impl netherforge_platform::FakeItemViewOps for Fake {
    fn refresh(&self, _: Uuid) -> bool {
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
    fn entities_loaded(&self, _: Location) -> bool {
        true
    }
}

impl netherforge_platform::FakeEntityOps for Fake {
    fn is_loaded(&self, _: Uuid) -> bool {
        true
    }
    fn remove(&self, id: Uuid) -> bool {
        self.say(format!("removed {}", id.0));
        true
    }
}

impl netherforge_platform::FakeScreenOps for Fake {
    fn send_map(
        &self,
        player: Uuid,
        map: i32,
        x: i32,
        y: i32,
        width: i32,
        height: i32,
        colors: Vec<u8>,
    ) -> bool {
        self.say(format!(
            "map {} {map} {x},{y} {width}x{height} {}",
            player.0, colors[0]
        ));
        true
    }
    fn hang(
        &self,
        at: Location,
        facing: netherforge_format::kind::screen::ScreenFacing,
        map: i32,
        glowing: bool,
        _: netherforge_platform::EntityTag,
    ) -> Option<Uuid> {
        let mut next = self.frames.borrow_mut();
        *next += 1;
        self.say(format!(
            "hang {} {} {} {facing:?} {map} {glowing}",
            at.x, at.y, at.z
        ));
        Some(Uuid(1000 + *next))
    }
    fn map_item(&self, map: i32) -> netherforge_platform::ItemData {
        netherforge_platform::ItemData {
            def: netherforge_format::item::ItemDef {
                kind: "minecraft:filled_map".into(),
                // The map's number, where a test can read it.
                name: Some(map.to_string()),
                ..Default::default()
            },
            raw: None,
            look: None,
        }
    }
    /// Two letters, each two pixels wide and a pixel apart.
    fn font(&self) -> MapFont {
        let glyph = MapGlyph {
            width: 2,
            height: 3,
            pixels: vec![true; 6],
        };
        MapFont {
            height: 3,
            glyphs: HashMap::from([(i32::from(b'H'), glyph.clone()), (i32::from(b'i'), glyph)]),
        }
    }
}

impl FakePlatform for Fake {
    fn players(&self) -> &dyn netherforge_platform::PlayerOps {
        self
    }
    fn teams(&self) -> &dyn netherforge_platform::TeamOps {
        self
    }
    fn permissions(&self) -> &dyn netherforge_platform::PermissionOps {
        self
    }
    fn item_views(&self) -> &dyn netherforge_platform::ItemViewOps {
        self
    }
    fn worlds(&self) -> &dyn netherforge_platform::WorldOps {
        self
    }
    fn entities(&self) -> &dyn netherforge_platform::EntityOps {
        self
    }
    fn screens(&self) -> &dyn netherforge_platform::ScreenOps {
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

struct Files {
    names: BTreeSet<String>,
    texts: BTreeMap<String, String>,
}

impl Files {
    fn new(files: &[(&str, &str)]) -> Files {
        Files {
            names: files.iter().map(|(p, _)| p.to_string()).collect(),
            texts: files
                .iter()
                .map(|(p, t)| (p.to_string(), t.to_string()))
                .collect(),
        }
    }
}

impl ProjectSource for Files {
    fn files(&self) -> &BTreeSet<String> {
        &self.names
    }
    fn read(&self, path: &str) -> Option<String> {
        self.texts.get(path).cloned()
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

fn files() -> Files {
    Files::new(&[
        ("netherforge.json", MANIFEST),
        (
            "screens/lobby/screen.json",
            r##"{ "width": 1, "height": 1, "background": "#ff0000", "place": { "world": "world", "x": 0, "y": 65, "z": -3, "facing": "south" }, "script": { "file": "script.lua" } }"##,
        ),
        (
            "screens/lobby/script.lua",
            r##"
print(this:name(), this:width(), this:pixel(0, 0), this:pixel(200, 0), this:facing())
this:fill(0, 0, 4, 4, "#0000ff")
local size = this:text(10, 10, "Hi")
print(size.width, size.height, this:pixel(10, 10), this:pixel(12, 10))
print(pcall(this.fill, this, 0, 0, 1, 1, "blue"))
print(pcall(this.remove, this))
"##,
        ),
        (
            "modules/boards/init.lua",
            r##"
local board = nf.screens.create({ width = 2, height = 1, key = "board", background = "#00ff00" })
print(board:width(), board:height(), tonumber(board:item(1).name) - tonumber(board:item(0).name), pcall(board.item, board, 2))
print(pcall(nf.screens.create, { width = 2, height = 1, key = "board" }))
print(nf.screens.map_color("#fe0101"), nf.screens.map_color("clear"), #nf.screens.all(), nf.screens.measure_text("Hi Hi").width)
nf.on("demo:own", function()
  local alex = nf.players.get("Alex")
  local own = board:for_player(alex)
  own:set_pixel(0, 0, "#ffffff")
  print(own:player():name(), own:pixel(0, 0), board:pixel(0, 0), board:viewers()[1]:name())
  nf.on("demo:release", function() print(own:release(), own:exists(), board:exists()) end)
end)
"##,
        ),
    ])
}

#[test]
fn screens_hang_draw_and_send_only_what_changed() {
    let platform = std::rc::Rc::new(Fake::default());
    let store = std::rc::Rc::new(netherforge_runtime::store::Store::in_memory());
    let files = files();
    let events = netherforge_runtime::server::ServerEvents::new(Session::start_with(
        platform.clone(),
        &files,
        &NoPackages,
        store.clone(),
    ));
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
            "256\t128\t1\tfalse\tmodules/boards/init.lua:3: the screen is 2 by 1 maps: there's no map at column 2, row 0",
            "false\tmodules/boards/init.lua:4: a screen with the key \"board\" is still there: remove it first, or use another key",
            // "Hi Hi": two-pixel letters a pixel apart, and a space of a pixel and its gap.
            "#ff0000\tclear\t2\t13",
            "lobby\t128\t#ff0000\tnil\tsouth",
            // "Hi" is 5 wide: (10, 10) is the H, (12, 10) the gap before the i.
            "5\t3\t#ffffff\t#ff0000",
            "false\tscreens/lobby/script.lua:6: \"blue\" isn't a colour: write #rrggbb, or \"clear\"",
            "false\tscreens/lobby/script.lua:7: screen \"lobby\" is the project's: its file says it's there, so it can't be removed",
        ]
    );
    // The first tick hangs the lobby's frame and sends Alex every map whole: the lobby's and the board's.
    use netherforge_platform::PlatformEvents;
    events.tick();
    let first = platform.take();
    let hung: Vec<&String> = first.iter().filter(|l| l.starts_with("hang")).collect();
    assert_eq!(hung, ["hang 0 65 -3 South 1000000000 true"]);
    let maps: Vec<&String> = first.iter().filter(|l| l.starts_with("map")).collect();
    assert_eq!(maps.len(), 3, "{maps:?}");
    assert!(maps.iter().all(|m| m.contains(" 0,0 128x128 ")), "{maps:?}");
    // Nothing changed: nothing is sent.
    events.tick();
    assert!(!platform.take().iter().any(|l| l.starts_with("map")));
    // A player's own view: drawn on, only they're sent it; released, they're sent everyone's again.
    let emit = |event: &str| {
        let session = events.session();
        let room = session.room().unwrap();
        room.scripts()
            .emit_custom(
                netherforge_runtime::Target::Nf,
                event.into(),
                mlua::Value::Table(room.host().lua().create_table().unwrap()),
            )
            .unwrap();
    };
    emit("demo:own");
    events.tick();
    let own = platform.take();
    assert_eq!(printed(&own), ["Alex\t#ffffff\t#00ff00\tAlex"]);
    let sent: Vec<&String> = own.iter().filter(|l| l.starts_with("map")).collect();
    assert_eq!(sent, ["map 77 1000000001 0,0 1x1 8"]);
    emit("demo:release");
    events.tick();
    let released = platform.take();
    assert_eq!(printed(&released), ["true\tfalse\ttrue"]);
    assert!(
        released
            .iter()
            .any(|l| l.starts_with("map 77 1000000001 0,0 128x128 ")),
        "{released:?}"
    );
    // A restart keeps the lobby's and the keyed board's map numbers.
    drop(events);
    platform.take();
    let again = netherforge_runtime::server::ServerEvents::new(Session::start_with(
        platform.clone(),
        &files,
        &NoPackages,
        store,
    ));
    again.tick();
    let restarted = platform.take();
    let mut numbers: Vec<&str> = restarted
        .iter()
        .filter(|l| l.starts_with("map"))
        .map(|l| l.split(' ').nth(2).unwrap())
        .collect();
    numbers.sort();
    assert_eq!(
        numbers,
        ["1000000000", "1000000001", "1000000002"],
        "{restarted:?}"
    );
}
