//! Cutscenes on the Rust runtime (Kotlin's `CutsceneTest`) against a fake server with one player, Alex, who
//! spectates through what they're given, is carried with it, and leaves, joins and crashes as a test says.

use std::cell::{Cell, RefCell};
use std::collections::{BTreeMap, BTreeSet, HashMap};
use std::rc::Rc;

use netherforge_format::game::GameData;
use netherforge_format::package::NoPackages;
use netherforge_format::project::ProjectSource;
use netherforge_platform::{
    DisplayLook, DisplayPose, EntityFlag, EntityNumber, EntityTag, FakePlatform, FakePlatformLog,
    Location, PlatformError, PlatformEvents, PlatformLog, PlayerRef, ServerInfo, Uuid, game_event,
};
use netherforge_runtime::server::ServerEvents;
use netherforge_runtime::session::Session;
use netherforge_runtime::store::Store;

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
}

fn alex() -> PlayerRef {
    PlayerRef {
        uuid: Uuid(77),
        name: "Alex".into(),
    }
}

fn home() -> Location {
    Location {
        world: "world".into(),
        x: 100.5,
        y: 70.0,
        z: 100.5,
        yaw: 45.0,
        pitch: 5.0,
    }
}

#[derive(Clone, Debug)]
struct Display {
    at: Location,
    tag: EntityTag,
    look: Option<DisplayLook>,
    persistent: bool,
}

/// One player and the displays made for them to look through.
struct Fake {
    lines: RefCell<Vec<String>>,
    online: Cell<bool>,
    at: RefCell<Location>,
    mode: RefCell<String>,
    flags: RefCell<BTreeMap<EntityFlag, bool>>,
    /// Who looks through what.
    cameras: RefCell<HashMap<Uuid, Uuid>>,
    displays: RefCell<BTreeMap<Uuid, Display>>,
    next: Cell<u128>,
    /// Chunks held, by world and chunk, with how many holds each.
    held: RefCell<BTreeMap<(String, i32, i32), i32>>,
    titles: RefCell<Vec<String>>,
    window: Cell<bool>,
    vehicle: Cell<Option<Uuid>>,
}

impl Fake {
    /// Alex online at home, a flying creative player.
    fn new() -> Fake {
        Fake {
            lines: RefCell::default(),
            online: Cell::new(true),
            at: RefCell::new(home()),
            mode: RefCell::new("creative".into()),
            flags: RefCell::new(BTreeMap::from([
                (EntityFlag::CanFly, true),
                (EntityFlag::Flying, true),
            ])),
            cameras: RefCell::default(),
            displays: RefCell::default(),
            next: Cell::new(1000),
            held: RefCell::default(),
            titles: RefCell::default(),
            window: Cell::new(false),
            vehicle: Cell::new(None),
        }
    }
    fn take(&self) -> Vec<String> {
        std::mem::take(&mut self.lines.borrow_mut())
    }
    fn camera(&self) -> Option<(Uuid, Display)> {
        self.displays
            .borrow()
            .iter()
            .find(|(_, d)| d.tag.node == "cutscene:camera")
            .map(|(id, d)| (*id, d.clone()))
    }
    fn cameras_made(&self) -> usize {
        self.displays
            .borrow()
            .values()
            .filter(|d| d.tag.node == "cutscene:camera")
            .count()
    }
    fn mode(&self) -> String {
        self.mode.borrow().clone()
    }
    fn at(&self) -> Location {
        self.at.borrow().clone()
    }
    fn flag(&self, flag: EntityFlag) -> bool {
        self.flags.borrow().get(&flag).copied().unwrap_or(false)
    }
    /// What the server does when the player presses sneak.
    fn sneak(&self) {
        self.cameras.borrow_mut().remove(&alex().uuid);
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

impl netherforge_platform::FakePermissionOps for Fake {
    fn apply(&self, _: Uuid, _: HashMap<String, bool>) {}
}

impl netherforge_platform::FakeItemViewOps for Fake {
    fn refresh(&self, _: Uuid) -> bool {
        true
    }
}

impl netherforge_platform::FakePlayerOps for Fake {
    fn online(&self) -> Vec<PlayerRef> {
        if self.online.get() {
            vec![alex()]
        } else {
            Vec::new()
        }
    }
    fn get(&self, uuid: Uuid) -> Option<PlayerRef> {
        (self.online.get() && uuid == alex().uuid).then(alex)
    }
    fn find(&self, name: String) -> Option<PlayerRef> {
        (self.online.get() && name == "Alex").then(alex)
    }
    fn known(&self, name: String) -> Option<PlayerRef> {
        (name == "Alex").then(alex)
    }
    fn location(&self, uuid: Uuid) -> Option<Location> {
        self.get(uuid).map(|_| self.at())
    }
    fn teleport(&self, uuid: Uuid, to: Location) -> bool {
        if self.get(uuid).is_none() {
            return false;
        }
        *self.at.borrow_mut() = to;
        true
    }
    fn title(
        &self,
        uuid: Uuid,
        title: String,
        subtitle: String,
        fade_in: i32,
        stay: i32,
        fade_out: i32,
    ) -> bool {
        self.titles.borrow_mut().push(format!(
            "{} [{title}] [{subtitle}] {fade_in} {stay} {fade_out}",
            uuid.0
        ));
        true
    }
    fn clear_title(&self, uuid: Uuid) -> bool {
        self.titles.borrow_mut().push(format!("{} cleared", uuid.0));
        true
    }
    fn game_mode(&self, uuid: Uuid) -> Option<String> {
        self.get(uuid).map(|_| self.mode())
    }
    /// Leaving spectator mode lets go of the camera, as the server does.
    fn set_game_mode(&self, uuid: Uuid, game_mode: String) -> bool {
        if self.get(uuid).is_none() {
            return false;
        }
        if game_mode != "spectator" {
            self.cameras.borrow_mut().remove(&uuid);
        }
        *self.mode.borrow_mut() = game_mode;
        true
    }
    fn close_inventory(&self, _: Uuid) -> bool {
        self.window.set(false);
        true
    }
}

impl netherforge_platform::FakeWorldEntityOps for Fake {
    fn flag(&self, id: Uuid, flag: EntityFlag) -> Option<bool> {
        (id == alex().uuid).then(|| self.flag(flag))
    }
    fn set_flag(&self, _: Uuid, flag: EntityFlag, value: bool) -> bool {
        self.flags.borrow_mut().insert(flag, value);
        true
    }
    fn number(&self, _: Uuid, number: EntityNumber) -> Option<f64> {
        (number == EntityNumber::Health).then_some(20.0)
    }
    fn vehicle(&self, _: Uuid) -> Option<Uuid> {
        self.vehicle.get()
    }
    fn remove_passenger(&self, _: Uuid, _: Uuid) -> bool {
        self.vehicle.set(None);
        true
    }
}

/// The server carries a spectator to what they look through.
impl netherforge_platform::FakePlayerViewOps for Fake {
    fn camera(&self, player: Uuid) -> Option<Uuid> {
        self.cameras.borrow().get(&player).copied()
    }
    fn set_camera(&self, player: Uuid, entity: Option<Uuid>) -> bool {
        if !self.online.get() || player != alex().uuid || self.mode() != "spectator" {
            return false;
        }
        match entity {
            None => {
                self.cameras.borrow_mut().remove(&player);
                true
            }
            Some(entity) => {
                let Some(at) = self.displays.borrow().get(&entity).map(|d| d.at.clone()) else {
                    return false;
                };
                self.cameras.borrow_mut().insert(player, entity);
                *self.at.borrow_mut() = at;
                true
            }
        }
    }
}

impl netherforge_platform::FakeEntityOps for Fake {
    fn spawn_display(
        &self,
        at: Location,
        _: netherforge_format::kind::centity::DisplayDef,
        _: DisplayPose,
        tag: EntityTag,
    ) -> Option<Uuid> {
        self.next.set(self.next.get() + 1);
        let id = Uuid(self.next.get());
        self.displays.borrow_mut().insert(
            id,
            Display {
                at,
                tag,
                look: None,
                persistent: true,
            },
        );
        Some(id)
    }
    fn is_loaded(&self, id: Uuid) -> bool {
        self.displays.borrow().contains_key(&id)
    }
    fn teleport(&self, id: Uuid, to: Location) {
        if let Some(d) = self.displays.borrow_mut().get_mut(&id) {
            d.at = to.clone();
        }
        if self.cameras.borrow().get(&alex().uuid) == Some(&id) {
            *self.at.borrow_mut() = to;
        }
    }
    fn location(&self, id: Uuid) -> Option<Location> {
        self.displays.borrow().get(&id).map(|d| d.at.clone())
    }
    fn set_persistent(&self, id: Uuid, persistent: bool) {
        if let Some(d) = self.displays.borrow_mut().get_mut(&id) {
            d.persistent = persistent;
        }
    }
    fn set_look(&self, id: Uuid, look: DisplayLook) {
        if let Some(d) = self.displays.borrow_mut().get_mut(&id) {
            d.look = Some(look);
        }
    }
    fn remove(&self, id: Uuid) -> bool {
        self.displays.borrow_mut().remove(&id).is_some()
    }
}

impl netherforge_platform::FakeWorldOps for Fake {
    fn names(&self) -> Vec<String> {
        vec!["world".into(), "nether".into()]
    }
    fn default_world(&self) -> String {
        "world".into()
    }
    fn exists(&self, world: String) -> bool {
        world == "world" || world == "nether"
    }
    fn entities_loaded(&self, _: Location) -> bool {
        true
    }
    fn hold_chunk(&self, world: String, chunk_x: i32, chunk_z: i32) -> bool {
        *self
            .held
            .borrow_mut()
            .entry((world, chunk_x, chunk_z))
            .or_default() += 1;
        true
    }
    fn release_chunk(&self, world: String, chunk_x: i32, chunk_z: i32) {
        let mut held = self.held.borrow_mut();
        let key = (world, chunk_x, chunk_z);
        if let Some(n) = held.get_mut(&key) {
            *n -= 1;
            if *n <= 0 {
                held.remove(&key);
            }
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
    fn world_entities(&self) -> &dyn netherforge_platform::WorldEntityOps {
        self
    }
    fn player_views(&self) -> &dyn netherforge_platform::PlayerViewOps {
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
    names: RefCell<BTreeSet<String>>,
    texts: RefCell<BTreeMap<String, String>>,
    /// What `files()` hands out: the names as they were last set.
    listed: BTreeSet<String>,
}

impl Files {
    fn new(files: &[(&str, &str)]) -> Files {
        let names: BTreeSet<String> = files.iter().map(|(p, _)| p.to_string()).collect();
        Files {
            listed: names.clone(),
            names: RefCell::new(names),
            texts: RefCell::new(
                files
                    .iter()
                    .map(|(p, t)| (p.to_string(), t.to_string()))
                    .collect(),
            ),
        }
    }
    fn write(&self, path: &str, text: &str) {
        assert!(self.names.borrow().contains(path), "only existing files");
        self.texts.borrow_mut().insert(path.into(), text.into());
    }
}

impl ProjectSource for Files {
    fn files(&self) -> &BTreeSet<String> {
        &self.listed
    }
    fn read(&self, path: &str) -> Option<String> {
        self.texts.borrow().get(path).cloned()
    }
}

const MANIFEST: &str = r#"{ "formatVersion": 1, "name": "Demo", "namespace": "demo", "version": "1.0.0", "minecraft": "26.3" }"#;

/// One second (20 ticks): 20 blocks east, turning a quarter and tilting, a cue with an event and text halfway.
const INTRO: &str = r#"{
  "camera": {
    "position": [
      { "time": 0, "value": [0, 64, 0] },
      { "time": 1, "value": [20, 64, 0] }
    ],
    "rotation": [
      { "time": 0, "yaw": 0, "pitch": 0 },
      { "time": 1, "yaw": 90, "pitch": 10 }
    ]
  },
  "cues": [{ "time": 0.5, "event": "half", "text": "<gold>Halfway", "duration": 0.25 }]
}"#;

/// `demo:go` plays the intro for Alex and tells what happens to it.
const PLAY: &str = r#"
local scene
nf.on("demo:go", function()
  scene = nf.cutscenes.play(nf.players.get("Alex"), "intro")
  scene:on("end", function(event)
    print("end " .. event.reason .. " active=" .. tostring(event.cutscene:is_active()) .. " mode=" .. tostring(event.player:game_mode()))
  end)
  scene:on("cue", function(event) print("cue " .. event.cue .. " at " .. tostring(scene:time())) end)
end)
nf.on("demo:stop", function() print("stop " .. tostring(scene:stop())) end)
nf.on("demo:state", function()
  print("state " .. tostring(scene:is_active()) .. " " .. tostring(scene:time()) .. " " .. tostring(scene:player() ~= nil))
end)
"#;

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

fn problems(lines: &[String]) -> Vec<&String> {
    lines
        .iter()
        .filter(|l| l.starts_with("warn") || l.starts_with("error"))
        .collect()
}

fn files(module: &str, more: &[(&str, &str)]) -> Files {
    let mut all = vec![
        ("netherforge.json", MANIFEST),
        ("cutscenes/intro.json", INTRO),
        ("modules/t/init.lua", module),
    ];
    all.extend_from_slice(more);
    Files::new(&all)
}

fn start<'a>(platform: &Rc<Fake>, files: &'a Files, store: &Rc<Store>) -> ServerEvents<'a> {
    ServerEvents::new(Session::start_with(
        platform.clone(),
        files,
        &NoPackages,
        store.clone(),
    ))
}

fn emit(events: &ServerEvents, event: &str) {
    let session = events.session();
    let room = session.room().unwrap();
    room.scripts()
        .emit_custom(
            netherforge_runtime::Target::Nf,
            event.into(),
            mlua::Value::Table(room.host().lua().create_table().unwrap()),
        )
        .unwrap();
}

/// Each tick, then what scripts printed in them.
fn ticks(events: &ServerEvents, platform: &Fake, n: usize) -> Vec<String> {
    let mut out = Vec::new();
    for _ in 0..n {
        events.tick();
        out.extend(platform.take());
    }
    out
}

fn close(a: f64, b: f64) -> bool {
    (a - b).abs() < 1e-9
}

#[test]
fn a_cutscene_holds_the_player_moves_its_camera_fires_its_cue_and_puts_them_back() {
    let platform = Rc::new(Fake::new());
    let store = Rc::new(Store::in_memory());
    let files = files(PLAY, &[]);
    let events = start(&platform, &files, &store);
    assert!(problems(&platform.take()).is_empty());

    emit(&events, "demo:go");
    let (camera, display) = platform.camera().expect("a camera was made");
    assert_eq!(platform.mode(), "spectator");
    assert_eq!(platform.cameras.borrow().get(&alex().uuid), Some(&camera));
    assert_eq!(
        display.at,
        Location {
            world: "world".into(),
            x: 0.0,
            y: 64.0,
            z: 0.0,
            yaw: 0.0,
            pitch: 0.0
        }
    );
    assert_eq!(display.look.as_ref().map(|l| l.teleport_ticks), Some(1));
    assert!(!display.persistent, "a crash can't leave it behind");
    assert_eq!(
        platform.at(),
        display.at,
        "the server moves a spectator to what they look through"
    );
    assert_eq!(
        store.cutscene_states().keys().copied().collect::<Vec<_>>(),
        [alex().uuid],
        "what to put back is kept"
    );

    // Each tick along the path, the player with it; the cue halfway.
    let first = ticks(&events, &platform, 1);
    assert!(printed(&first).is_empty());
    let at = platform.camera().unwrap().1.at;
    assert!(close(at.x, 1.0) && close(at.yaw, 4.5), "{at:?}");
    let halfway = ticks(&events, &platform, 9);
    let at = platform.camera().unwrap().1.at;
    assert!(
        close(at.x, 10.0) && close(at.yaw, 45.0) && close(at.pitch, 5.0),
        "{at:?}"
    );
    assert_eq!(platform.at(), at);
    assert_eq!(printed(&halfway), ["cue half at 0.5"]);
    // The cue's text is the subtitle for its duration, then cleared.
    assert_eq!(*platform.titles.borrow(), ["77 [] [<gold>Halfway] 0 5 10"]);
    ticks(&events, &platform, 5);
    assert_eq!(
        *platform.titles.borrow(),
        ["77 [] [<gold>Halfway] 0 5 10", "77 cleared"]
    );

    // The last pose is drawn for a tick, then it ends, the player put back before the end handlers hear it.
    let rest = ticks(&events, &platform, 5);
    assert!(printed(&rest).is_empty());
    assert!(close(platform.camera().unwrap().1.at.x, 20.0));
    let end = ticks(&events, &platform, 1);
    assert_eq!(printed(&end), ["end finished active=true mode=creative"]);
    assert!(platform.camera().is_none());
    assert_eq!(platform.mode(), "creative");
    assert_eq!(platform.at(), home());
    assert!(platform.flag(EntityFlag::Flying) && platform.flag(EntityFlag::CanFly));
    assert!(platform.cameras.borrow().is_empty());
    assert!(
        platform.held.borrow().is_empty(),
        "nothing held once it's over"
    );
    assert!(
        store.cutscene_states().is_empty(),
        "nothing left to put back"
    );
    emit(&events, "demo:state");
    events.tick();
    assert_eq!(printed(&platform.take()), ["state false nil false"]);
}

#[test]
fn stop_ends_it_at_once_and_a_survival_player_is_put_back_unable_to_fly() {
    let platform = Rc::new(Fake::new());
    *platform.mode.borrow_mut() = "survival".into();
    platform.flags.borrow_mut().clear();
    let store = Rc::new(Store::in_memory());
    let files = files(PLAY, &[]);
    let events = start(&platform, &files, &store);
    platform.take();
    emit(&events, "demo:go");
    ticks(&events, &platform, 3);
    emit(&events, "demo:stop");
    events.tick();
    assert_eq!(
        printed(&platform.take()),
        ["end stopped active=true mode=survival", "stop true"]
    );
    assert!(platform.camera().is_none());
    assert_eq!(platform.at(), home());
    assert_eq!(platform.mode(), "survival");
    assert!(!platform.flag(EntityFlag::CanFly) && !platform.flag(EntityFlag::Flying));
    ticks(&events, &platform, 30);
    assert_eq!(platform.at(), home(), "nothing moves them after");
}

#[test]
fn sneaking_is_held_unless_skippable_and_the_scripts_option_wins_over_the_file() {
    let platform = Rc::new(Fake::new());
    let store = Rc::new(Store::in_memory());
    let skippy = INTRO.replacen("{\n  \"camera\"", "{ \"skippable\": true,\n  \"camera\"", 1);
    let files = files(
        r#"
nf.on("demo:held", function()
  nf.cutscenes.play(nf.players.get("Alex"), "intro"):on("end", function(event) print("held " .. event.reason) end)
end)
nf.on("demo:skip", function()
  nf.cutscenes.play(nf.players.get("Alex"), "intro", { skippable = true }):on("end", function(event) print("skip " .. event.reason) end)
end)
nf.on("demo:file", function()
  nf.cutscenes.play(nf.players.get("Alex"), "skippy"):on("end", function(event) print("file " .. event.reason) end)
end)
nf.on("demo:option", function()
  nf.cutscenes.play(nf.players.get("Alex"), "skippy", { skippable = false }):on("end", function(event) print("option " .. event.reason) end)
end)
"#,
        &[("cutscenes/skippy.json", &skippy)],
    );
    let events = start(&platform, &files, &store);
    assert!(problems(&platform.take()).is_empty());

    emit(&events, "demo:held");
    ticks(&events, &platform, 2);
    platform.sneak();
    let held = ticks(&events, &platform, 1);
    assert!(printed(&held).is_empty());
    let camera = platform.camera().unwrap().0;
    assert_eq!(
        platform.cameras.borrow().get(&alex().uuid),
        Some(&camera),
        "put back in it"
    );

    // Played over: the held one is replaced.
    emit(&events, "demo:skip");
    let mut skipped = printed(&ticks(&events, &platform, 2));
    platform.sneak();
    skipped.extend(printed(&ticks(&events, &platform, 1)));
    assert_eq!(skipped, ["held replaced", "skip skipped"]);
    assert_eq!(platform.at(), home());
    assert_eq!(platform.mode(), "creative");

    emit(&events, "demo:file");
    platform.sneak();
    assert_eq!(printed(&ticks(&events, &platform, 1)), ["file skipped"]);
    emit(&events, "demo:option");
    platform.sneak();
    assert!(printed(&ticks(&events, &platform, 1)).is_empty(), "held");
}

#[test]
fn a_player_who_quits_or_vanishes_ends_it_player_left() {
    let platform = Rc::new(Fake::new());
    let store = Rc::new(Store::in_memory());
    let files = files(PLAY, &[]);
    let events = start(&platform, &files, &store);
    platform.take();
    emit(&events, "demo:go");
    ticks(&events, &platform, 3);
    // The quit: the server still has them while the services hear it.
    events.game().player_quit(&mut game_event::PlayerQuit {
        player: alex(),
        message: None,
    });
    events.tick();
    assert_eq!(
        printed(&platform.take()),
        ["end player_left active=true mode=creative"]
    );
    assert!(platform.camera().is_none());
    assert_eq!(platform.at(), home());

    // Gone without a quit the runtime heard.
    emit(&events, "demo:go");
    ticks(&events, &platform, 2);
    platform.online.set(false);
    let gone = printed(&ticks(&events, &platform, 1));
    assert_eq!(gone, ["end player_left active=true mode=nil"]);
    assert!(platform.camera().is_none());
    assert!(problems(&ticks(&events, &platform, 30)).is_empty());
}

#[test]
fn a_crash_mid_cutscene_is_put_right_when_the_player_next_joins_before_scripts_hear() {
    let platform = Rc::new(Fake::new());
    let store = Rc::new(Store::in_memory());
    let module = format!(
        "{PLAY}\nnf.on(\"player_join\", function(event) print(\"join mode=\" .. tostring(event.player:game_mode())) end)"
    );
    let files = files(&module, &[]);
    // A chain: the second would capture the camera's state; the first's is what a crash puts back.
    let events = start(&platform, &files, &store);
    platform.take();
    emit(&events, "demo:go");
    ticks(&events, &platform, 2);
    emit(&events, "demo:go");
    ticks(&events, &platform, 2);
    assert_eq!(platform.mode(), "spectator");
    // What a crash leaves: the camera was never saved, the player's mode was.
    platform.displays.borrow_mut().clear();
    platform.sneak();
    std::mem::forget(events);
    platform.take();

    let again = start(&platform, &files, &store);
    assert_eq!(
        platform.mode(),
        "spectator",
        "nobody is put back before they join"
    );
    platform.take();
    again.game().player_join(&mut game_event::PlayerJoin {
        player: alex(),
        first_join: false,
        message: None,
    });
    again.tick();
    let joined = platform.take();
    assert_eq!(platform.mode(), "creative");
    assert_eq!(platform.at(), home());
    assert!(platform.flag(EntityFlag::CanFly) && platform.flag(EntityFlag::Flying));
    assert_eq!(printed(&joined), ["join mode=creative"]);
    assert!(
        joined.contains(
            &"warn Alex was in a cutscene when the server stopped; putting them back".to_owned()
        ),
        "{joined:?}"
    );
    // Once is enough: the next start finds nothing to put back.
    drop(again);
    let third = start(&platform, &files, &store);
    *platform.mode.borrow_mut() = "survival".into();
    third.game().player_join(&mut game_event::PlayerJoin {
        player: alex(),
        first_join: false,
        message: None,
    });
    assert_eq!(platform.mode(), "survival");
}

#[test]
fn a_cutscene_that_ended_leaves_nothing_for_a_crash_and_the_server_stopping_puts_everyone_back() {
    let platform = Rc::new(Fake::new());
    let store = Rc::new(Store::in_memory());
    let files = files(PLAY, &[]);
    let events = start(&platform, &files, &store);
    emit(&events, "demo:go");
    ticks(&events, &platform, 3);
    emit(&events, "demo:stop");
    events.tick();
    std::mem::forget(events);
    assert!(store.cutscene_states().is_empty());

    let events = start(&platform, &files, &store);
    platform.take();
    emit(&events, "demo:go");
    ticks(&events, &platform, 3);
    drop(events);
    assert_eq!(
        printed(&platform.take()),
        ["end unloaded active=true mode=creative"]
    );
    assert_eq!(platform.at(), home());
    assert_eq!(platform.mode(), "creative");
    assert!(platform.camera().is_none());
    assert!(store.cutscene_states().is_empty());
}

#[test]
fn a_script_that_unloads_ends_what_it_played_unloaded() {
    let platform = Rc::new(Fake::new());
    let store = Rc::new(Store::in_memory());
    let files = files(
        r#"nf.on("demo:go", function() nf.cutscenes.play(nf.players.get("Alex"), "intro") end)"#,
        &[],
    );
    let events = start(&platform, &files, &store);
    emit(&events, "demo:go");
    ticks(&events, &platform, 2);
    assert_eq!(platform.mode(), "spectator");
    files.write("modules/t/init.lua", "-- nothing now");
    events
        .session_mut()
        .reload(&["modules/t/init.lua".to_owned()]);
    assert!(platform.camera().is_none());
    assert_eq!(platform.mode(), "creative");
    assert_eq!(platform.at(), home());
}

#[test]
fn playing_over_a_cutscene_replaces_it_without_putting_the_player_back_between() {
    let platform = Rc::new(Fake::new());
    let store = Rc::new(Store::in_memory());
    let files = files(
        r#"
nf.on("demo:go", function()
  local alex = nf.players.get("Alex")
  local first = nf.cutscenes.play(alex, "intro")
  first:on("end", function(event) print("first " .. event.reason .. " mode=" .. tostring(event.player:game_mode())) end)
  local second = nf.cutscenes.play(alex, "intro")
  second:on("end", function(event) print("second " .. event.reason) end)
  print("current is second: " .. tostring(nf.cutscenes.current(alex) == second) .. " first active: " .. tostring(first:is_active()))
end)
"#,
        &[],
    );
    let events = start(&platform, &files, &store);
    platform.take();
    emit(&events, "demo:go");
    events.tick();
    assert_eq!(
        printed(&platform.take()),
        [
            "first replaced mode=spectator",
            "current is second: true first active: false"
        ]
    );
    assert_eq!(platform.cameras_made(), 1);
    assert_eq!(printed(&ticks(&events, &platform, 21)), ["second finished"]);
    assert_eq!(platform.at(), home());
    assert_eq!(
        platform.mode(),
        "creative",
        "the state from before the first"
    );
}

#[test]
fn nf_cutscenes_stop_current_kind_and_length() {
    let platform = Rc::new(Fake::new());
    let store = Rc::new(Store::in_memory());
    let files = files(
        r#"
nf.on("demo:go", function()
  local alex = nf.players.get("Alex")
  print("before " .. tostring(nf.cutscenes.current(alex)) .. " " .. tostring(nf.cutscenes.stop(alex)))
  local scene = nf.cutscenes.play(alex, "intro")
  print("during " .. tostring(nf.cutscenes.current(alex) == scene) .. " " .. scene:kind() .. " " .. scene:length())
  print("stop " .. tostring(nf.cutscenes.stop(alex)) .. " " .. tostring(nf.cutscenes.current(alex)) .. " " .. scene:kind())
end)
"#,
        &[],
    );
    let events = start(&platform, &files, &store);
    platform.take();
    emit(&events, "demo:go");
    events.tick();
    assert_eq!(
        printed(&platform.take()),
        [
            "before nil false",
            "during true intro 1",
            "stop true nil intro"
        ]
    );
    assert_eq!(platform.at(), home());
}

#[test]
fn origin_moves_the_path_a_location_picks_its_world_and_the_cameras_chunk_is_held() {
    let platform = Rc::new(Fake::new());
    let store = Rc::new(Store::in_memory());
    let files = files(
        r#"
nf.on("demo:go", function()
  nf.cutscenes.play(nf.players.get("Alex"), "intro", { origin = vector.create(1000, 10, -50) })
end)
nf.on("demo:there", function()
  nf.cutscenes.play(nf.players.get("Alex"), "intro", { origin = nf.worlds.get("nether"):location(vector.create(0, 0, 0)) })
end)
"#,
        &[],
    );
    let events = start(&platform, &files, &store);
    assert!(problems(&platform.take()).is_empty());
    emit(&events, "demo:go");
    let at = platform.camera().unwrap().1.at;
    assert_eq!(
        (at.world.as_str(), at.x, at.y, at.z),
        ("world", 1000.0, 74.0, -50.0)
    );
    // 1000, 74, -50 is chunk 62, -4.
    assert_eq!(
        *platform.held.borrow(),
        BTreeMap::from([(("world".to_owned(), 62, -4), 1)])
    );
    // Moving 10 blocks along x over the second, it crosses into chunk 63 and the hold goes with it.
    ticks(&events, &platform, 10);
    assert!(close(platform.camera().unwrap().1.at.x, 1010.0));
    assert_eq!(
        *platform.held.borrow(),
        BTreeMap::from([(("world".to_owned(), 63, -4), 1)])
    );
    emit(&events, "demo:there");
    let at = platform.camera().unwrap().1.at;
    assert_eq!((at.world.as_str(), at.y), ("nether", 64.0));
    assert_eq!(platform.at().world, "nether");
    assert_eq!(
        *platform.held.borrow(),
        BTreeMap::from([(("nether".to_owned(), 0, 0), 1)]),
        "the replaced one's hold let go"
    );
    ticks(&events, &platform, 21);
    assert_eq!(platform.at(), home(), "back in their own world");
    assert!(platform.held.borrow().is_empty());
}

#[test]
fn a_rider_is_dismounted_a_window_closed_and_a_game_mode_change_stops_it() {
    let platform = Rc::new(Fake::new());
    platform.vehicle.set(Some(Uuid(5)));
    platform.window.set(true);
    let store = Rc::new(Store::in_memory());
    let files = files(PLAY, &[]);
    let events = start(&platform, &files, &store);
    platform.take();
    emit(&events, "demo:go");
    assert_eq!(platform.vehicle.get(), None);
    assert!(!platform.window.get());
    ticks(&events, &platform, 2);
    *platform.mode.borrow_mut() = "adventure".into();
    assert_eq!(
        printed(&ticks(&events, &platform, 1)),
        ["end stopped active=true mode=creative"]
    );
    assert!(platform.camera().is_none());
}

#[test]
fn a_cutscene_may_be_stopped_from_its_own_cue() {
    let platform = Rc::new(Fake::new());
    let store = Rc::new(Store::in_memory());
    let files = files(
        r#"
nf.on("demo:go", function()
  local scene = nf.cutscenes.play(nf.players.get("Alex"), "intro")
  scene:on("cue", function(event)
    print("cue " .. event.cue)
    scene:stop()
  end)
  scene:on("end", function(event) print("end " .. event.reason) end)
end)
"#,
        &[],
    );
    let events = start(&platform, &files, &store);
    platform.take();
    emit(&events, "demo:go");
    assert_eq!(
        printed(&ticks(&events, &platform, 30)),
        ["cue half", "end stopped"]
    );
    assert_eq!(platform.at(), home());
    // Its text went with it.
    assert_eq!(
        *platform.titles.borrow(),
        ["77 [] [<gold>Halfway] 0 5 10", "77 cleared"]
    );
}

#[test]
fn the_mistakes_offline_unknown_broken_and_a_bad_option() {
    let platform = Rc::new(Fake::new());
    let store = Rc::new(Store::in_memory());
    let files = files(
        r#"
local ghost = nf.players.get("Alex")
nf.on("demo:late", function() print("late " .. tostring(nf.cutscenes.play(ghost, "intro"))) end)
nf.on("demo:unknown", function() print(pcall(nf.cutscenes.play, ghost, "intor")) end)
nf.on("demo:broken", function() print(pcall(nf.cutscenes.play, nf.players.get("Alex"), "broken")) end)
nf.on("demo:option", function() print(pcall(nf.cutscenes.play, nf.players.get("Alex"), "intro", { speed = 2 })) end)
"#,
        &[(
            "cutscenes/broken.json",
            r#"{ "length": 2, "camera": { "position": [{ "time": 0, "value": [0, 0, 0] }] } }"#,
        )],
    );
    let events = start(&platform, &files, &store);
    platform.take();
    emit(&events, "demo:broken");
    emit(&events, "demo:option");
    platform.online.set(false);
    emit(&events, "demo:late");
    emit(&events, "demo:unknown");
    events.tick();
    let lines = printed(&platform.take());
    assert_eq!(lines.len(), 4, "{lines:?}");
    assert!(
        lines[0].starts_with("false")
            && lines[0].contains("has errors")
            && lines[0].contains("rotation"),
        "{lines:?}"
    );
    assert!(
        lines[1].starts_with("false") && lines[1].contains("speed"),
        "{lines:?}"
    );
    assert_eq!(lines[2], "late nil");
    assert!(
        lines[3].contains("no cutscene \"intor\" in this project (did you mean \"intro\"?)"),
        "{lines:?}"
    );
    assert!(platform.camera().is_none());
}

#[test]
fn a_reload_of_the_file_leaves_one_playing_as_it_was_and_the_next_play_takes_the_new() {
    let platform = Rc::new(Fake::new());
    let store = Rc::new(Store::in_memory());
    let files = files(PLAY, &[]);
    let events = start(&platform, &files, &store);
    platform.take();
    emit(&events, "demo:go");
    ticks(&events, &platform, 2);
    files.write(
        "cutscenes/intro.json",
        &INTRO.replace("[20, 64, 0]", "[40, 64, 0]"),
    );
    events
        .session_mut()
        .reload(&["cutscenes/intro.json".to_owned()]);
    ticks(&events, &platform, 1);
    assert!(
        close(platform.camera().unwrap().1.at.x, 3.0),
        "the version it started with"
    );
    emit(&events, "demo:stop");
    emit(&events, "demo:go");
    ticks(&events, &platform, 20);
    assert!(close(platform.camera().unwrap().1.at.x, 40.0));
}
