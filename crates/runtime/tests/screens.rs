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

impl netherforge_platform::FakeSoundOps for Fake {
    fn play_to(&self, player: Uuid, sound: netherforge_platform::SoundPlay) -> bool {
        self.say(format!(
            "sound {} {} {} {} {}",
            player.0, sound.sound, sound.category, sound.volume, sound.pitch
        ));
        true
    }
    fn stop(&self, player: Uuid, sound: Option<String>) -> bool {
        self.say(format!("stop {} {}", player.0, sound.unwrap_or_default()));
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
    fn sounds(&self) -> &dyn netherforge_platform::SoundOps {
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
    texts: RefCell<BTreeMap<String, String>>,
}

impl Files {
    fn new(files: &[(&str, &str)]) -> Files {
        Files {
            names: files.iter().map(|(p, _)| p.to_string()).collect(),
            texts: RefCell::new(
                files
                    .iter()
                    .map(|(p, t)| (p.to_string(), t.to_string()))
                    .collect(),
            ),
        }
    }
}

impl ProjectSource for Files {
    fn files(&self) -> &BTreeSet<String> {
        &self.names
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

/// Map colours 4 (black) and 8 (white) for `frames` frames of a 1 by 1 map video, alternating from black.
fn alternating(frames: usize) -> Vec<Vec<u8>> {
    (0..frames)
        .map(|i| vec![if i % 2 == 0 { 4 } else { 8 }; 128 * 128])
        .collect()
}

fn nfv_header(
    columns: u16,
    rows: u16,
    key_interval: u16,
    audio: bool,
) -> netherforge_format::nfv::Header {
    netherforge_format::nfv::Header {
        columns,
        rows,
        fps: 20,
        frames: 0,
        key_interval,
        dither: 1,
        fit: 0,
        start_ms: 0,
        end_ms: 0,
        audio,
        source_sha256: [0; 32],
        palette_hash: 0,
    }
}

/// A project on disk: its text files read by the session, every file (the binary ones too) in `dir`.
fn on_disk(
    dir: &std::path::Path,
    texts: &[(&str, &str)],
    binary: &[(&str, Vec<u8>)],
) -> (Files, netherforge_runtime::session::Config) {
    let mut files = Files::new(texts);
    for (path, text) in texts {
        let at = dir.join(path);
        std::fs::create_dir_all(at.parent().unwrap()).unwrap();
        std::fs::write(at, text).unwrap();
    }
    for (path, bytes) in binary {
        let at = dir.join(path);
        std::fs::create_dir_all(at.parent().unwrap()).unwrap();
        std::fs::write(at, bytes).unwrap();
        files.names.insert(path.to_string());
    }
    let root = dir.to_owned();
    let config = netherforge_runtime::session::Config {
        screens: netherforge_runtime::screen::ScreensConfig {
            read_inline: true,
            ..Default::default()
        },
        paths: Some(netherforge_runtime::screen::video::ProjectPaths(
            std::rc::Rc::new(move |path| Some(root.join(path)).filter(|p| p.exists())),
        )),
        ..Default::default()
    };
    (files, config)
}

fn sounds(lines: &[String]) -> Vec<&str> {
    lines
        .iter()
        .filter(|l| l.starts_with("sound") || l.starts_with("stop"))
        .map(String::as_str)
        .collect()
}

fn first_map_pixel(lines: &[String]) -> Option<&str> {
    lines
        .iter()
        .rfind(|l| l.starts_with("map 77 1000000000 "))
        .and_then(|l| l.rsplit(' ').next())
}

#[test]
fn a_video_plays_a_frame_a_tick_loops_pauses_seeks_and_ends_with_its_sound() {
    let dir = tempfile::tempdir().unwrap();
    // Six seconds at 20 frames a second, alternating black and white.
    let frames = netherforge_format::nfv::encode(&nfv_header(1, 1, 10, true), &alternating(120));
    let (files, config) = on_disk(
        dir.path(),
        &[
            ("netherforge.json", MANIFEST),
            (
                "videos/clip/video.json",
                r#"{ "source": "source.mp4", "width": 1, "height": 1 }"#,
            ),
            ("screens/tv/screen.json", r#"{ "width": 1, "height": 1 }"#),
            (
                "modules/t/init.lua",
                r#"
local tv = nf.screens.get("tv")
local show
nf.on("demo:play", function()
  show = tv:play("clip")
  show:on("end", function(event) print("end", event.reason, show:exists(), show:time()) end)
  print("length", show:length(), show:video(), show:screen() == tv, tv:playback() == show)
end)
nf.on("demo:pause", function() print("pause", show:pause(), show:is_paused(), show:pause()) end)
nf.on("demo:resume", function() print("resume", show:resume()) end)
nf.on("demo:seek", function() print("seek", show:seek(4.9), show:time()) end)
nf.on("demo:after", function() print("after", show:exists(), show:length(), show:pause(), tv:playback()) end)
nf.on("demo:missing", function() print(pcall(tv.play, tv, "nope")) end)
nf.on("demo:bad", function() print(pcall(tv.play, tv, "clip", { speed = 0 })) end)
"#,
            ),
        ],
        &[
            ("videos/clip/source.mp4", vec![0]),
            ("videos/clip/video.nfv", frames),
            ("videos/clip/audio.ogg", vec![1]),
            ("videos/clip/audio/0.ogg", vec![1]),
            ("videos/clip/audio/1.ogg", vec![1]),
        ],
    );
    let platform = std::rc::Rc::new(Fake::default());
    let events = netherforge_runtime::server::ServerEvents::new(Session::start_configured(
        platform.clone(),
        &files,
        &NoPackages,
        std::rc::Rc::new(netherforge_runtime::store::Store::in_memory()),
        config,
    ));
    let started = platform.take();
    // Its sound is in the resource pack, which this server can't build.
    assert!(
        !started
            .iter()
            .filter(|l| !l.contains("resource pack format"))
            .any(|l| l.starts_with("warn") || l.starts_with("error")),
        "{started:?}"
    );
    use netherforge_platform::PlatformEvents;
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
    events.tick();
    platform.take();
    // What handlers print is logged as the next tick starts.
    let ticks = |n: usize| {
        let mut lines = Vec::new();
        for _ in 0..n {
            events.tick();
            lines.extend(platform.take());
        }
        lines
    };

    emit("demo:play");
    let played = [platform.take(), ticks(1)].concat();
    assert_eq!(printed(&played), ["length\t6\tclip\ttrue\ttrue"]);
    assert_eq!(
        sounds(&played),
        ["sound 77 demo:video.clip record 1 1"],
        "the whole sound, from the start"
    );
    assert_eq!(first_map_pixel(&played), Some("8"), "frame 1");
    assert_eq!(first_map_pixel(&ticks(1)), Some("4"), "frame 2");

    emit("demo:pause");
    let paused = [platform.take(), ticks(4)].concat();
    assert_eq!(printed(&paused), ["pause\ttrue\ttrue\tfalse"]);
    assert_eq!(
        sounds(&paused),
        ["stop 77 demo:video.clip"],
        "paused, silent"
    );
    assert_eq!(first_map_pixel(&paused), None, "held");

    // Moved through, its sound picks up at the next piece: the one starting at 5 seconds.
    emit("demo:resume");
    emit("demo:seek");
    let moved = [platform.take(), ticks(3)].concat();
    assert_eq!(printed(&moved), ["resume\ttrue", "seek\ttrue\t4.9"]);
    assert_eq!(sounds(&moved), ["sound 77 demo:video.clip.1 record 1 1"]);

    let ended = ticks(30);
    assert_eq!(printed(&ended), ["end\tfinished\ttrue\tnil"]);
    assert_eq!(sounds(&ended), ["stop 77 demo:video.clip.1"]);
    emit("demo:after");
    assert_eq!(
        printed(&[platform.take(), ticks(1)].concat()),
        ["after\tfalse\t6\tfalse\tnil"]
    );

    emit("demo:missing");
    let missing = printed(&[platform.take(), ticks(1)].concat());
    assert!(missing[0].contains("no video \"nope\""), "{missing:?}");
    emit("demo:bad");
    assert_eq!(
        printed(&[platform.take(), ticks(1)].concat()),
        ["false\tmodules/t/init.lua:14: options.speed is more than 0, not 0"]
    );
}

#[test]
fn a_screens_video_plays_from_its_file_and_frames_for_other_settings_are_stale() {
    let dir = tempfile::tempdir().unwrap();
    let frames = netherforge_format::nfv::encode(&nfv_header(1, 1, 4, false), &alternating(2));
    let (files, config) = on_disk(
        dir.path(),
        &[
            ("netherforge.json", MANIFEST),
            (
                "videos/clip/video.json",
                r#"{ "source": "source.mp4", "width": 2, "height": 1, "audio": false }"#,
            ),
            (
                "screens/tv/screen.json",
                r#"{ "width": 1, "height": 1, "video": { "video": "clip" } }"#,
            ),
            ("screens/tv/script.lua", ""),
        ],
        &[
            ("videos/clip/source.mp4", vec![0]),
            ("videos/clip/video.nfv", frames),
        ],
    );
    let platform = std::rc::Rc::new(Fake::default());
    let events = netherforge_runtime::server::ServerEvents::new(Session::start_configured(
        platform.clone(),
        &files,
        &NoPackages,
        std::rc::Rc::new(netherforge_runtime::store::Store::in_memory()),
        config,
    ));
    let stale: Vec<_> = events
        .session()
        .problems()
        .into_iter()
        .filter(|p| p.code.as_deref() == Some("video.stale"))
        .collect();
    assert_eq!(stale.len(), 1, "{stale:?}");
    assert_eq!(stale[0].file, "videos/clip/video.json");
    assert!(
        stale[0].message.contains("1 by 1 maps"),
        "{}",
        stale[0].message
    );
    // The screen's own video plays, and loops: frame 1 is white, and two frames on it's white again.
    use netherforge_platform::PlatformEvents;
    platform.take();
    events.tick();
    assert_eq!(first_map_pixel(&platform.take()), Some("8"));
    events.tick();
    assert_eq!(first_map_pixel(&platform.take()), Some("4"), "looped");
    // Matched, it isn't stale any more.
    files.texts.borrow_mut().insert(
        "videos/clip/video.json".into(),
        r#"{ "source": "source.mp4", "width": 1, "height": 1, "audio": false }"#.into(),
    );
    events
        .session_mut()
        .reload(&["videos/clip/video.json".into()]);
    assert!(
        !events
            .session()
            .problems()
            .iter()
            .any(|p| p.code.as_deref() == Some("video.stale")),
    );
}
