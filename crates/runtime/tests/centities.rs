//! Centities on the Rust runtime against a fake Platform that records what's asked of the server's
//! entities: an instance spawned as an anchor and its displays, synced each tick writing only what changed,
//! moved onto a reloaded definition, and removed.

use std::cell::RefCell;
use std::collections::BTreeMap;

use netherforge_format::kind::centity::DisplayDef;
use netherforge_format::package::NoPackages;
use netherforge_format::project::ProjectSource;
use netherforge_platform::{
    ClickButton, DisplayLook, DisplayPose, EntityOps, EntityRole, EntityTag, FakeEntityOps,
    FakePlatform, FakePlatformLog, FakeWorldOps, Location, PlatformError, PlatformLog, PlayerRef,
    Ray, ServerInfo, Uuid, WorldOps,
};
use netherforge_runtime::session::Session;

/// Game facts that know no registry, and every block as one without properties.
struct NoFacts;

impl netherforge_format::game::GameData for NoFacts {
    fn minecraft_version(&self) -> &str {
        "26.3"
    }
    fn registry(&self, _: &str) -> Option<&std::collections::BTreeSet<String>> {
        None
    }
    fn tag(&self, _: &str, _: &str) -> Option<&std::collections::BTreeSet<String>> {
        None
    }
    fn item_durability(&self, _: &str) -> u32 {
        0
    }
    fn particle(&self, _: &str) -> Option<netherforge_format::game::ParticleData> {
        None
    }
    /// Every block there is, with no properties.
    fn block_properties(&self, _: &str) -> Option<BTreeMap<String, Vec<String>>> {
        Some(BTreeMap::new())
    }
}

struct Fake {
    game: NoFacts,
    /// Whether every chunk's entities are loaded.
    unloaded: std::cell::Cell<bool>,
    /// The one player online, and where they are.
    player: RefCell<Option<Location>>,
    calls: RefCell<Vec<String>>,
    next: RefCell<u128>,
    poses: RefCell<BTreeMap<u128, DisplayPose>>,
    /// Blocks of the floor that were broken.
    broken: RefCell<std::collections::HashSet<(i32, i32, i32)>>,
}

impl Default for Fake {
    fn default() -> Fake {
        Fake {
            game: NoFacts,
            unloaded: std::cell::Cell::new(false),
            player: RefCell::new(None),
            calls: RefCell::default(),
            next: RefCell::default(),
            poses: RefCell::default(),
            broken: RefCell::default(),
        }
    }
}

impl Fake {
    fn take(&self) -> Vec<String> {
        std::mem::take(&mut self.calls.borrow_mut())
    }
    fn call(&self, what: String) {
        self.calls.borrow_mut().push(what);
    }
}

impl FakePlatformLog for Fake {
    fn info(&self, message: String) {
        self.call(format!("info {message}"));
    }
    fn warn(&self, message: String) {
        self.call(format!("warn {message}"));
    }
    fn error(&self, message: String, _: Option<PlatformError>) {
        self.call(format!("error {message}"));
    }
}

fn at(l: &Location) -> String {
    format!("{} {} {} {}", l.world, l.x, l.y, l.z)
}

impl FakeEntityOps for Fake {
    fn spawn_hitbox(
        &self,
        location: Location,
        width: f64,
        height: f64,
        tag: EntityTag,
    ) -> Option<Uuid> {
        *self.next.borrow_mut() += 1;
        let id = *self.next.borrow();
        assert_eq!(tag.role, EntityRole::Hitbox);
        self.call(format!(
            "hitbox {id} {} at {} size {width} {height}",
            tag.node,
            at(&location)
        ));
        Some(Uuid(id))
    }
    fn resize_hitbox(&self, id: Uuid, width: f64, height: f64) {
        self.call(format!("resize {} {width} {height}", id.0));
    }
    fn dismount(&self, id: Uuid) {
        self.call(format!("dismount {}", id.0));
    }
    fn set_persistent(&self, id: Uuid, persistent: bool) {
        self.call(format!("persistent {} {persistent}", id.0));
    }
    fn spawn_anchor(&self, location: Location, tag: EntityTag) -> Option<Uuid> {
        *self.next.borrow_mut() += 1;
        let id = *self.next.borrow();
        assert_eq!(tag.role, EntityRole::Anchor);
        self.call(format!("anchor {id} at {}", at(&location)));
        Some(Uuid(id))
    }
    fn spawn_display(
        &self,
        location: Location,
        display: DisplayDef,
        pose: DisplayPose,
        tag: EntityTag,
    ) -> Option<Uuid> {
        *self.next.borrow_mut() += 1;
        let id = *self.next.borrow();
        let kind = match display {
            DisplayDef::Block { .. } => "block",
            DisplayDef::Text { .. } => "text",
            _ => "other",
        };
        let t = pose.matrix.translation();
        self.call(format!(
            "display {id} {} {kind} at {} offset {} {} {}",
            tag.node,
            at(&location),
            t.0,
            t.1,
            t.2
        ));
        self.poses.borrow_mut().insert(id, pose);
        Some(Uuid(id))
    }
    fn mount(&self, vehicle: Uuid, passenger: Uuid) -> bool {
        self.call(format!("mount {} on {}", passenger.0, vehicle.0));
        true
    }
    fn set_pose(&self, id: Uuid, pose: DisplayPose) {
        let t = pose.matrix.translation();
        self.call(format!(
            "pose {} {} {} {} eased {}",
            id.0, t.0, t.1, t.2, pose.interpolation_ticks
        ));
        self.poses.borrow_mut().insert(id.0, pose);
    }
    fn set_look(&self, id: Uuid, _: DisplayLook) {
        self.call(format!("look {}", id.0));
    }
    fn teleport(&self, id: Uuid, to: Location) {
        self.call(format!("teleport {} to {}", id.0, at(&to)));
    }
    fn update_display(&self, id: Uuid, display: DisplayDef) -> bool {
        if let DisplayDef::Text {
            runs: Some(runs), ..
        } = &display
        {
            let coloured = runs.iter().filter(|r| r.rgb.is_some()).count();
            self.call(format!("update {} runs {coloured}", id.0));
        }
        true
    }
    fn is_loaded(&self, _: Uuid) -> bool {
        true
    }
    fn remove(&self, id: Uuid) -> bool {
        self.call(format!("remove {}", id.0));
        true
    }
}

impl netherforge_platform::FakeClientEntityOps for Fake {
    fn set_viewers(&self, id: Uuid, viewers: Vec<Uuid>) {
        let who: Vec<String> = viewers.iter().map(|v| v.0.to_string()).collect();
        self.call(format!("viewers {} {}", id.0, who.join(",")));
    }
}

impl netherforge_platform::FakeWorldEntityOps for Fake {
    fn boxes(
        &self,
        _: String,
        _: netherforge_format::math::BoundingBox,
    ) -> Vec<netherforge_platform::EntityBox> {
        Vec::new()
    }
    fn info(&self, id: Uuid) -> Option<netherforge_platform::EntityInfo> {
        self.list("world".into()).into_iter().find(|e| e.id == id)
    }
    /// One zombie, standing at (5, 64, 5).
    fn list(&self, _: String) -> Vec<netherforge_platform::EntityInfo> {
        vec![netherforge_platform::EntityInfo {
            id: Uuid(500),
            kind: "minecraft:zombie".into(),
            location: place(5.0, 64.0, 5.0),
            category: netherforge_platform::EntityCategory::Mob,
            player: None,
            tags: vec!["guard".into()],
        }]
    }
    fn raycast(
        &self,
        _: String,
        _: netherforge_format::math::Vec3,
        _: netherforge_format::math::Vec3,
        _: f64,
        _: Vec<Uuid>,
    ) -> Option<netherforge_platform::EntityHit> {
        None
    }
}

impl netherforge_platform::FakeBlockOps for Fake {
    fn set(&self, _: String, x: i32, y: i32, z: i32, state: String, update: bool) -> bool {
        self.call(format!("set {x} {y} {z} {state} {update}"));
        true
    }
    /// The floor's blocks in a box, x fastest, then z, then y.
    fn r#box(
        &self,
        _: String,
        x0: i32,
        y0: i32,
        z0: i32,
        x1: i32,
        y1: i32,
        z1: i32,
    ) -> Option<netherforge_platform::BlockBoxRead> {
        let state = |name: &str, solid: bool| netherforge_platform::BoxState {
            state: name.into(),
            solid,
            liquid: false,
        };
        let mut cells = Vec::new();
        for y in y0..=y1 {
            for _ in z0..=z1 {
                for x in x0..=x1 {
                    cells.push(i32::from(y < 64 || x == 10));
                }
            }
        }
        Some(netherforge_platform::BlockBoxRead {
            states: vec![
                state("minecraft:air", false),
                state("minecraft:stone", true),
            ],
            cells,
        })
    }
    fn get(
        &self,
        _: String,
        x: i32,
        y: i32,
        _: i32,
    ) -> Option<netherforge_platform::BlockSnapshot> {
        let solid = y < 64 || x == 10;
        Some(netherforge_platform::BlockSnapshot {
            state: if solid {
                "minecraft:stone"
            } else {
                "minecraft:air"
            }
            .into(),
            air: !solid,
            solid,
            liquid: false,
            light: if solid { 0 } else { 15 },
            sky_light: 15,
            water: false,
        })
    }
}

impl netherforge_platform::FakePlayerOps for Fake {
    fn online(&self) -> Vec<PlayerRef> {
        match &*self.player.borrow() {
            Some(_) => vec![PlayerRef {
                uuid: Uuid(77),
                name: "Alex".into(),
            }],
            None => Vec::new(),
        }
    }
    fn location(&self, uuid: Uuid) -> Option<Location> {
        (uuid == Uuid(77))
            .then(|| self.player.borrow().clone())
            .flatten()
    }
}

impl FakeWorldOps for Fake {
    fn names(&self) -> Vec<String> {
        vec!["world".into()]
    }
    fn exists(&self, world: String) -> bool {
        world == "world"
    }
    fn default_world(&self) -> String {
        "world".into()
    }
    fn entities_loaded(&self, _: Location) -> bool {
        !self.unloaded.get()
    }
    fn is_chunk_loaded(&self, _: String, _: i32, _: i32) -> bool {
        true
    }
    fn full_time(&self, _: String) -> Option<i64> {
        Some(6000)
    }
    fn moon_phase(&self, _: String) -> Option<i32> {
        Some(0)
    }
    fn weather(&self, _: String) -> Option<String> {
        Some("clear".into())
    }
    fn heights(&self, _: String) -> Option<(i32, i32)> {
        Some((-64, 320))
    }
    fn biome(&self, _: String, _: i32, _: i32, _: i32) -> Option<String> {
        Some("minecraft:plains".into())
    }
    fn game_rule_type(&self, rule: String) -> Option<netherforge_platform::GameRuleType> {
        (rule == "minecraft:keep_inventory").then_some(netherforge_platform::GameRuleType::Boolean)
    }
    fn game_rule(&self, _: String, _: String) -> Option<netherforge_platform::Dynamic> {
        Some(netherforge_platform::Dynamic::Bool(true))
    }
    /// The first solid block along a ray, by small steps.
    fn raycast_blocks(
        &self,
        _: String,
        origin: netherforge_format::math::Vec3,
        direction: netherforge_format::math::Vec3,
        max_distance: f64,
        _: bool,
    ) -> Option<netherforge_platform::BlockHit> {
        let solid = |x: i32, y: i32, _: i32| y < 64 || x == 10;
        let cell = |p: netherforge_format::math::Vec3| {
            (p.0.floor() as i32, p.1.floor() as i32, p.2.floor() as i32)
        };
        let mut previous = cell(origin);
        let mut t = 0.0;
        while t <= max_distance {
            let p = origin + direction * t;
            let c = cell(p);
            if c != previous && solid(c.0, c.1, c.2) {
                let normal = netherforge_format::math::Vec3(
                    f64::from(previous.0 - c.0),
                    f64::from(previous.1 - c.1),
                    f64::from(previous.2 - c.2),
                );
                return Some(netherforge_platform::BlockHit {
                    x: c.0,
                    y: c.1,
                    z: c.2,
                    position: p,
                    normal,
                });
            }
            previous = c;
            t += 0.01;
        }
        None
    }
    /// A floor: every block below y 64 is stone (but those broken), and a wall of it at x 10.
    fn collision_boxes(
        &self,
        _: String,
        min_x: f64,
        min_y: f64,
        min_z: f64,
        max_x: f64,
        max_y: f64,
        max_z: f64,
    ) -> Vec<netherforge_format::math::BoundingBox> {
        let mut boxes = Vec::new();
        for x in min_x.floor() as i32..=max_x.floor() as i32 {
            for y in min_y.floor() as i32..=max_y.floor() as i32 {
                for z in min_z.floor() as i32..=max_z.floor() as i32 {
                    if (y < 64 || x == 10) && !self.broken.borrow().contains(&(x, y, z)) {
                        let (x, y, z) = (f64::from(x), f64::from(y), f64::from(z));
                        boxes.push(netherforge_format::math::BoundingBox {
                            min: netherforge_format::math::Vec3(x, y, z),
                            max: netherforge_format::math::Vec3(x + 1.0, y + 1.0, z + 1.0),
                        });
                    }
                }
            }
        }
        boxes
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
        &self.game
    }
    fn entities(&self) -> &dyn EntityOps {
        self
    }
    fn worlds(&self) -> &dyn WorldOps {
        self
    }
    fn client_entities(&self) -> &dyn netherforge_platform::ClientEntityOps {
        self
    }
    fn blocks(&self) -> &dyn netherforge_platform::BlockOps {
        self
    }
    fn world_entities(&self) -> &dyn netherforge_platform::WorldEntityOps {
        self
    }
    fn players(&self) -> &dyn netherforge_platform::PlayerOps {
        self
    }
}

struct Files {
    /// Leaked anew on each change: a test's few lists, so a running session can see files come and go.
    names: RefCell<&'static std::collections::BTreeSet<String>>,
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
    fn write(&self, path: &str, text: &str) {
        self.texts.borrow_mut().insert(path.into(), text.into());
    }
}

impl Files {
    fn delete(&self, path: &str) {
        let mut names = (*self.names.borrow()).clone();
        names.remove(path);
        *self.names.borrow_mut() = Box::leak(Box::new(names));
        self.texts.borrow_mut().remove(path);
    }
    fn add(&self, path: &str, text: &str) {
        let mut names = (*self.names.borrow()).clone();
        names.insert(path.into());
        *self.names.borrow_mut() = Box::leak(Box::new(names));
        self.write(path, text);
    }
}

impl ProjectSource for Files {
    fn files(&self) -> &std::collections::BTreeSet<String> {
        *self.names.borrow()
    }
    fn read(&self, path: &str) -> Option<String> {
        self.texts.borrow().get(path).cloned()
    }
}

const MANIFEST: &str = r#"{ "formatVersion": 1, "name": "Demo", "namespace": "demo", "version": "1.0.0", "minecraft": "26.3" }"#;

fn lamp(head_y: f64) -> String {
    format!(
        r#"{{ "nodes": {{
  "base": {{ "display": {{ "type": "block", "block": "minecraft:stone" }} }},
  "head": {{ "parent": "base", "transform": {{ "translation": [0, {head_y}, 0] }}, "display": {{ "type": "block", "block": "minecraft:glowstone" }} }}
}} }}"#
    )
}

fn place(x: f64, y: f64, z: f64) -> Location {
    Location {
        world: "world".into(),
        x,
        y,
        z,
        yaw: 0.0,
        pitch: 0.0,
    }
}

#[test]
fn an_instance_is_drawn_synced_reloaded_and_removed() {
    let platform = std::rc::Rc::new(Fake::default());
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("centities/lamp/centity.json", &lamp(1.0)),
    ]);
    let mut session = Session::start(platform.clone(), &files, &NoPackages);
    assert!(session.refusal().is_none(), "{:?}", session.problems());
    platform.take();

    // Spawned: its anchor, a display per node with its offset in its pose, each riding the anchor.
    let lamp = session
        .spawn("lamp", place(10.0, 64.0, 5.0), 0.0)
        .expect("spawned");
    let calls = platform.take();
    assert_eq!(calls[0], "anchor 1 at world 10 64 5");
    assert_eq!(
        calls[1],
        "display 2 base block at world 10 64 5 offset 0 0 0"
    );
    assert_eq!(
        calls[2],
        "display 3 head block at world 10 64 5 offset 0 1 0"
    );
    assert!(calls.contains(&"mount 2 on 1".to_owned()), "{calls:?}");
    assert!(calls.contains(&"mount 3 on 1".to_owned()), "{calls:?}");
    assert!(
        session
            .spawn("nothing", place(0.0, 0.0, 0.0), 0.0)
            .is_none()
    );
    assert!(
        session
            .spawn(
                "lamp",
                Location {
                    world: "nether".into(),
                    ..place(0.0, 0.0, 0.0)
                },
                0.0
            )
            .is_none()
    );

    // Nothing changed: nothing is sent.
    session.tick();
    session.tick();
    assert_eq!(platform.take(), Vec::<String>::new());

    // Turned a quarter: every display turns (its rotation is in its pose); the head, straight above, stays above.
    session.centities().get_mut(lamp).unwrap().turn_to(90.0);
    session.tick();
    assert_eq!(
        platform.take(),
        ["pose 2 0 0 0 eased 1", "pose 3 0 1 0 eased 1"]
    );

    // A reload moves the instance onto the new definition: only what changed is re-posed.
    files.write("centities/lamp/centity.json", &self::lamp(2.0));
    session.reload(&["centities/lamp/centity.json".into()]);
    let calls = platform.take();
    let poses: Vec<&String> = calls.iter().filter(|c| c.starts_with("pose")).collect();
    assert!(
        poses.iter().any(|c| c.starts_with("pose 3 0 2 0")),
        "{calls:?}"
    );
    assert!(
        !calls.iter().any(|c| c.starts_with("display")),
        "kept: {calls:?}"
    );

    // Removed: its entities go.
    assert!(session.centities().remove(&*platform, lamp));
    let calls = platform.take();
    for id in [1, 2, 3] {
        assert!(calls.contains(&format!("remove {id}")), "{calls:?}");
    }
    assert!(session.centities().get(lamp).is_none());
}

/// The lamp, with a script.
fn scripted_lamp() -> String {
    let lamp = lamp(1.0);
    let end = lamp.rfind('}').expect("an object");
    format!(
        r#"{}, "script": {{ "file": "script.lua" }} }}"#,
        lamp[..end].trim_end()
    )
}

/// What scripts printed, in order: the log's `info [who] line` without the location.
fn printed(calls: &[String]) -> Vec<String> {
    calls
        .iter()
        .filter_map(|c| c.strip_prefix("info ["))
        .map(|c| {
            let (_, rest) = c.split_once("] ").expect("a label");
            rest.rsplit_once(" (")
                .map_or(rest, |(line, _)| line)
                .to_owned()
        })
        .collect()
}

#[test]
fn scripts_spawn_instances_and_an_instance_runs_its_own_script() {
    let platform = std::rc::Rc::new(Fake::default());
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("centities/lamp/centity.json", &scripted_lamp()),
        (
            "centities/lamp/script.lua",
            r#"
print("body", this:kind(), this:node("head"):translation().y)
this:node("head"):set_translation(vector.create(0, 3, 0), { instant = true })
local ticks = 0
this:on("spawn", function(e) print("spawn", e.centity == this) end)
this:on("tick", function() ticks += 1 end)
this:on("remove", function() print("remove after", ticks, "ticks", this:exists()) end)
"#,
        ),
        (
            "modules/game/init.lua",
            r#"
local lamp = nf.centities.spawn("lamp", vector.create(1, 64, 2))
print("spawned", lamp:exists(), lamp:position().x, #lamp:nodes(), lamp:node("nope"))
print("found", nf.centities.get(lamp:id()) == lamp, #nf.centities.all({ kind = "lamp" }))
print("world", lamp:node("head"):world_position().y)
print(pcall(nf.centities.spawn, "lmap", vector.create(0, 0, 0)))
nf.after(3, function()
    lamp:remove()
    print("gone", lamp:exists(), lamp:position(), nf.centities.get(lamp:id()))
    lamp:on("tick", function() print("never") end)
end)
"#,
        ),
    ]);
    let session = Session::start(platform.clone(), &files, &NoPackages);
    assert!(session.refusal().is_none(), "{:?}", session.problems());
    assert_eq!(
        printed(&platform.take()),
        [
            "body\tlamp\t1",
            "spawn\ttrue",
            "spawned\ttrue\t1\t2\tnil",
            "found\ttrue\t1",
            "world\t67",
            "false\tmodules/game/init.lua:6: no centity \"lmap\" in this project (did you mean \"lamp\"?)",
        ]
    );
    for _ in 0..4 {
        session.tick();
    }
    let calls = platform.take();
    assert_eq!(
        printed(&calls),
        ["remove after\t2\tticks\ttrue", "gone\tfalse\tnil\tnil"],
        "{calls:?}"
    );
    assert!(calls.contains(&"remove 1".to_owned()), "{calls:?}");
    assert!(session.centities().all().next().is_none());
}

#[test]
fn a_reload_restarts_an_instances_script_without_spawn() {
    let platform = std::rc::Rc::new(Fake::default());
    let centity = scripted_lamp();
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("centities/lamp/centity.json", &centity),
        (
            "centities/lamp/script.lua",
            r#"print("v1"); this:on("spawn", function() print("spawn") end)"#,
        ),
        (
            "modules/game/init.lua",
            r#"local lamp = nf.centities.spawn("lamp", vector.create(0, 64, 0))
lamp:node("head"):on("click", function() end)"#,
        ),
    ]);
    let mut session = Session::start(platform.clone(), &files, &NoPackages);
    let start = platform.take();
    assert_eq!(printed(&start), ["v1", "spawn"], "{start:?}");
    files.write(
        "centities/lamp/script.lua",
        r#"print("v2", this:node("head") ~= nil); this:on("spawn", function() print("spawn") end)"#,
    );
    session.reload(&["centities/lamp/script.lua".into()]);
    assert_eq!(printed(&platform.take()), ["v2\ttrue"]);
}

#[test]
fn a_clip_is_sent_ahead_as_keyframes_and_its_end_is_heard() {
    let platform = std::rc::Rc::new(Fake::default());
    let lamp = lamp(1.0);
    let end = lamp.rfind('}').expect("an object");
    let centity = format!(
        r#"{}, "animations": {{ "rise": {{ "loop": "once", "tracks": {{ "head": {{ "translation": [
  {{ "time": 0, "value": [0, 1, 0] }}, {{ "time": 1, "value": [0, 3, 0] }} ] }} }} }} }}, "script": {{ "file": "script.lua" }} }}"#,
        lamp[..end].trim_end()
    );
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("centities/lamp/centity.json", &centity),
        (
            "centities/lamp/script.lua",
            r#"
this:on("animation_start", function(e) print("start", e.animation) end)
this:on("animation_end", function(e) print("end", e.animation) end)
"#,
        ),
        (
            "modules/game/init.lua",
            r#"
local lamp = nf.centities.spawn("lamp", vector.create(0, 64, 0))
print(lamp:play_animation("rise"), lamp:is_animation_playing("rise"), table.concat(lamp:animations(), ","))
print(pcall(lamp.play_animation, lamp, "fall"))
nf.after(10, function() print("at", string.format("%.3f", lamp:animation_tick("rise"))) end)
"#,
        ),
    ]);
    let session = Session::start(platform.clone(), &files, &NoPackages);
    let start = platform.take();
    assert_eq!(
        printed(&start),
        [
            "start\trise",
            "true\ttrue\trise",
            "false\tmodules/game/init.lua:4: lamp has no animation \"fall\" (it has: rise)",
        ],
        "{start:?}"
    );
    let mut poses = Vec::new();
    let mut lines = Vec::new();
    for _ in 0..25 {
        session.tick();
        let calls = platform.take();
        lines.extend(printed(&calls));
        poses.extend(calls.into_iter().filter(|c| c.starts_with("pose 3 ")));
    }
    // Timers run before the clips move on: nine ticks in.
    assert_eq!(lines[0], "at\t9.000", "{lines:?}");
    assert_eq!(lines.len(), 2, "{lines:?}");
    assert_eq!(lines[1], "end\trise", "{lines:?}");
    // A pose now (the clip just started), one planned most of the way, and back to rest at the end.
    assert!(poses.len() <= 4, "{poses:?}");
    assert!(poses.iter().any(|p| p.ends_with("eased 17")), "{poses:?}");
    assert!(
        poses.last().unwrap().starts_with("pose 3 0 1 0"),
        "{poses:?}"
    );
}

#[test]
fn a_click_goes_to_the_hitbox_the_line_of_sight_meets_and_up_its_path() {
    let platform = std::rc::Rc::new(Fake::default());
    let centity = r#"{ "nodes": {
  "base": { "display": { "type": "block", "block": "minecraft:stone" }, "hitbox": {} },
  "head": { "parent": "base", "transform": { "translation": [0, 1, 0] }, "hitbox": {} }
}, "script": { "file": "script.lua" } }"#;
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("centities/lamp/centity.json", centity),
        (
            "centities/lamp/script.lua",
            r#"
local function said(where)
    return function(e)
        print(where, e.target:name(), e.click, e.hit_position and e.hit_position.y)
    end
end
this:node("head"):on("click", said("head"))
this:node("base"):on("click", said("base"))
this:on("click", function(e)
    said("centity")(e)
    if e.target:name() == "base" then e:stop() end
end)
nf.on("centity_click", said("nf"))
nf.after(3, function() this:node("head"):set_clickable(false) end)
"#,
        ),
        (
            "modules/game/init.lua",
            r#"
nf.centities.spawn("lamp", vector.create(10, 64, 0))
"#,
        ),
    ]);
    let session = Session::start(platform.clone(), &files, &NoPackages);
    let start = platform.take();
    // A hitbox for each node, each a unit cube where its node is: off the anchor, so standing on its own.
    let hitboxes: Vec<&String> = start.iter().filter(|c| c.starts_with("hitbox")).collect();
    assert_eq!(
        hitboxes,
        [
            "hitbox 2 base at world 10.5 64 0.5 size 1 1",
            "hitbox 3 head at world 10.5 65 0.5 size 1 1"
        ],
        "{start:?}"
    );
    let player = PlayerRef {
        uuid: Uuid(77),
        name: "Alex".into(),
    };
    let down = |x: f64, z: f64| Ray {
        world: "world".into(),
        x,
        y: 70.0,
        z,
        dx: 0.0,
        dy: -1.0,
        dz: 0.0,
    };
    // The client picked the base, but the line of sight meets the head's top first.
    assert!(session.entity_clicked(Uuid(2), &player, ClickButton::Right, Some(down(10.5, 0.5))));
    // What scripts print is logged with the next tick's report.
    session.tick();
    assert_eq!(
        printed(&platform.take()),
        [
            "head\thead\tright\t66",
            "base\thead\tright\t66",
            "centity\thead\tright\t66",
            "nf\thead\tright\t66"
        ]
    );
    // Without a line of sight, the entity the client picked; the centity's handler stops it there.
    assert!(session.entity_clicked(Uuid(2), &player, ClickButton::Left, None));
    session.tick();
    assert_eq!(
        printed(&platform.take()),
        ["base\tbase\tleft\tnil", "centity\tbase\tleft\tnil"]
    );
    // Not one of ours.
    assert!(!session.entity_clicked(Uuid(999), &player, ClickButton::Left, None));
    // The head takes no clicks: its hitbox collapses, and the same line of sight reaches the base.
    session.tick();
    let calls = platform.take();
    assert!(
        calls.iter().any(|c| c.starts_with("resize 3 0.01 0.01")),
        "{calls:?}"
    );
    assert!(session.entity_clicked(Uuid(3), &player, ClickButton::Right, Some(down(10.5, 0.5))));
    session.tick();
    assert_eq!(
        printed(&platform.take()),
        ["base\tbase\tright\t65", "centity\tbase\tright\t65"]
    );
}

#[test]
fn a_script_changes_looks_displays_and_turns() {
    let platform = std::rc::Rc::new(Fake::default());
    let centity = r##"{ "nodes": {
  "base": { "display": { "type": "block", "block": "minecraft:stone" }, "hitbox": {} },
  "label": { "parent": "base", "transform": { "translation": [0, 2, 0] }, "display": { "type": "text", "text": "hi" } },
  "panel": { "display": { "type": "rect", "color": "#ff0000" } }
} }"##;
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("centities/sign/centity.json", centity),
        (
            "modules/game/init.lua",
            r##"
local sign = nf.centities.spawn("sign", vector.create(0, 64, 0))
print(sign:is_glowing(), sign:set_glowing(true), sign:is_glowing())
print(sign:set_glow_color("#00ff00"), sign:glow_color(), sign:node("label"):glow_color())
print(pcall(sign.set_glow_color, sign, "green"))
local label = sign:node("label")
print(label:set_display_text_style({ alignment = "left", opacity = 128 }))
local style = label:display_text_style()
print(style.alignment, style.opacity, style.line_width, style.shadow)
local panel = sign:node("panel")
print(panel:display_size().x, panel:set_display_size(vector.create(2, 0.5, 0)), panel:display_size().y)
print(pcall(panel.set_display_size, panel, vector.create(0, 1, 0)))
print(panel:display_anchor(), panel:set_display_anchor("bottom"), panel:display_anchor())
print(sign:node("base"):set_brightness({ block_light = 15, sky_light = 3 }), sign:node("base"):brightness().sky_light)
local b = sign:bounds()
print(b.min.y, b.max.y)
sign:look_at(vector.create(10, 64, 0))
print(sign:yaw())
label:rotate(vector.create(0, 1, 0), 90)
print(string.format("%.3f", label:rotation().y), string.format("%.3f", label:world_rotation().y))
label:look_at(vector.create(0, 66, 5))
print(string.format("%.1f %.1f", label:rotation().x, label:rotation().y))
"##,
        ),
    ]);
    let _session = Session::start(platform.clone(), &files, &NoPackages);
    let start = platform.take();
    assert_eq!(
        printed(&start),
        [
            "false\ttrue\ttrue",
            "true\t#00ff00\tnil",
            "false\tmodules/game/init.lua:5: bad argument 'color' (#RRGGBB expected, got \"green\")",
            "true",
            "left\t128\t200\tfalse",
            "1\ttrue\t0.5",
            "false\tmodules/game/init.lua:12: bad argument 'size' (a width and height more than 0, got 0 by 1)",
            "center\ttrue\tbottom",
            "true\t3",
            "64\t65",
            "-90",
            "90.000\t0.000",
            "0.0 -90.0",
        ],
        "{start:?}"
    );
}

#[test]
fn a_script_adds_and_removes_nodes_and_tweens_one() {
    let platform = std::rc::Rc::new(Fake::default());
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("centities/lamp/centity.json", &lamp(1.0)),
        (
            "modules/game/init.lua",
            r#"
local lamp = nf.centities.spawn("lamp", vector.create(0, 64, 0))
local arm = lamp:add_node("arm", { transform = { translation = vector.create(1, 0, 0) }, display = { type = "block", block = "minecraft:oak_log" } })
local hand = arm:add_child("hand", { display = { type = "block", block = "minecraft:glass" }, hitbox = {} })
print(#lamp:nodes(), hand:parent():name(), hand:world_position().x)
print(pcall(lamp.add_node, lamp, "arm", {}))
print(pcall(lamp.add_node, lamp, "x", { display = { type = "block", blok = "minecraft:stone" } }))
print(pcall(lamp.add_node, lamp, "y", { itemTransform = 1 }))
print(pcall(lamp.node(lamp, "base").remove, lamp:node("base")))
arm:tween({ translation = vector.create(3, 0, 0), ticks = 4, easing = "linear" })
nf.after(2, function() print("tween", arm:translation().x, arm:is_tweening()) end)
nf.after(6, function()
    print("tween", arm:translation().x, arm:is_tweening())
    print(arm:remove(), #lamp:nodes(), lamp:node("hand"))
end)
"#,
        ),
    ]);
    let session = Session::start(platform.clone(), &files, &NoPackages);
    let start = platform.take();
    assert_eq!(
        printed(&start)
            .get(..5)
            .unwrap_or_else(|| panic!("{start:?}")),
        [
            "4\tarm\t1",
            "false\tmodules/game/init.lua:6: bad argument 'name' (the centity already has a node called \"arm\")",
            "false\tmodules/game/init.lua:7: definition.display: Unknown key \"blok\"",
            "false\tmodules/game/init.lua:8: definition.itemTransform: Lua spells keys in snake_case, so it's item_transform",
            "false\tmodules/game/init.lua:9: node \"base\" is declared by centity.json; only nodes a script added can be removed",
        ],
        "{start:?}"
    );
    // The added nodes' entities are made at the next sync.
    session.tick();
    let calls = platform.take();
    assert!(calls.iter().any(|c| c.contains(" arm block")), "{calls:?}");
    assert!(
        calls
            .iter()
            .any(|c| c.starts_with("hitbox") && c.contains("hand")),
        "{calls:?}"
    );
    for _ in 0..6 {
        session.tick();
    }
    let calls = platform.take();
    assert_eq!(
        printed(&calls),
        ["tween\t1.5\ttrue", "tween\t3\tfalse", "true\t2\tnil"],
        "{calls:?}"
    );
}

#[test]
fn a_rope_is_laid_between_its_ends_each_tick() {
    let platform = std::rc::Rc::new(Fake::default());
    let centity = r#"{ "nodes": {
  "line": { "display": { "type": "block", "block": "minecraft:chain" }, "rope": { "links": 4, "linkLength": 1 } }
} }"#;
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("centities/line/centity.json", centity),
        (
            "modules/game/init.lua",
            r#"
local line = nf.centities.spawn("line", vector.create(0, 64, 0))
local rope = line:node("line")
print(rope:is_rope(), line:node("base") == nil, #line:nodes())
local link = line:node("line[1]")
print(pcall(link.rope_between, link, vector.create(0, 64, 0), vector.create(1, 64, 0)))
rope:rope_between(vector.create(0, 65, 0), vector.create(2, 65, 0))
nf.after(2, function()
    local shown = 0
    for _, node in line:nodes() do
        if node:name() ~= "line" and node:is_visible() then shown += 1 end
    end
    print("shown", shown, string.format("%.2f", link:world_position().x))
end)
"#,
        ),
    ]);
    let session = Session::start(platform.clone(), &files, &NoPackages);
    let start = platform.take();
    let lines = printed(&start);
    assert_eq!(
        lines.first().map(String::as_str),
        Some("true\ttrue\t5"),
        "{start:?}"
    );
    assert!(
        lines.get(1).is_some_and(|l| l.contains("isn't a rope")),
        "{start:?}"
    );
    for _ in 0..3 {
        session.tick();
    }
    // Taut over two blocks: two of its four links, the first halfway along its block.
    assert_eq!(printed(&platform.take()), ["shown\t2\t0.50"]);
}

#[test]
fn temporary_centities_go_with_their_scope_or_their_lifetime_and_a_private_one_is_its_viewers_alone()
 {
    let platform = std::rc::Rc::new(Fake::default());
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("centities/lamp/centity.json", &lamp(1.0)),
        (
            "modules/game/init.lua",
            r#"
local timed = nf.centities.spawn("lamp", vector.create(0, 64, 0), { lifetime = 3 })
timed:on("remove", function() print("timed gone") end)
local kept = nf.centities.spawn("lamp", vector.create(5, 64, 0), { temporary = true })
print(kept:is_temporary(), kept:keep(), kept:is_temporary(), timed:lifetime())
local scoped = nf.centities.spawn("lamp", vector.create(9, 64, 0), { temporary = true })
-- Private, with no viewers yet: its client entities are sent to nobody.
local mine = nf.centities.spawn("lamp", vector.create(0, 70, 0), { temporary = true, viewers = {} })
print(#mine:viewers())
print(pcall(nf.centities.spawn, "lamp", vector.create(0, 0, 0), { viewers = {} }))
print(pcall(nf.centities.spawn, "lamp", vector.create(0, 0, 0), { lifetime = 0 }))
"#,
        ),
    ]);
    let mut session = Session::start(platform.clone(), &files, &NoPackages);
    let start = platform.take();
    let lines = printed(&start);
    assert_eq!(
        lines.first().map(String::as_str),
        Some("true\ttrue\tfalse\t3"),
        "{start:?}"
    );
    assert_eq!(lines.get(1).map(String::as_str), Some("0"), "{start:?}");
    assert!(
        lines
            .get(2)
            .is_some_and(|l| l.contains("only a temporary centity can be private")),
        "{start:?}"
    );
    assert!(
        lines
            .get(3)
            .is_some_and(|l| l.contains("at least 1 expected, got 0")),
        "{start:?}"
    );
    assert!(
        start
            .iter()
            .any(|c| c.starts_with("viewers ") && c.ends_with(' ')),
        "{start:?}"
    );
    // A temporary one's entities aren't saved; keeping one makes them so again.
    assert!(
        start.contains(&"persistent 1 false".to_owned()),
        "{start:?}"
    );
    assert!(start.contains(&"persistent 4 true".to_owned()), "{start:?}");
    for _ in 0..3 {
        session.tick();
    }
    assert_eq!(printed(&platform.take()), ["timed gone"]);
    assert_eq!(session.centities().all().count(), 3);
    // The module reloads: what it spawned temporary goes with its scope; the kept one stays.
    files.write("modules/game/init.lua", "");
    session.reload(&["modules/game/init.lua".into()]);
    platform.take();
    assert_eq!(session.centities().all().count(), 1);
}

#[test]
fn a_centity_takes_buffs_and_damage_and_dies() {
    let platform = std::rc::Rc::new(Fake::default());
    let centity = lamp(1.0);
    let end = centity.rfind('}').expect("an object");
    let centity = format!(
        r#"{}, "health": {{ "max": 10, "bar": {{ "segments": 5 }} }} }}"#,
        centity[..end].trim_end()
    );
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("centities/dummy/centity.json", &centity),
        (
            "buffs/frail.json",
            r#"{ "stats": { "defense": -4 }, "blockedBy": ["ironskin"], "stacking": "add" }"#,
        ),
        (
            "buffs/ironskin.json",
            r#"{ "stats": { "defense": 6 }, "cures": ["frail"] }"#,
        ),
        (
            "modules/game/init.lua",
            r#"
local d = nf.centities.spawn("dummy", vector.create(0, 64, 0))
d:on("buff_end", function(e) print("ended", e.buff, e.reason) end)
d:on("damage", function(e) e.amount = e.amount / 2 end)
d:on("death", function(e) print("died", e.cause) end)
d:on("remove", function() print("removed") end)
print(d:add_buff("frail", 5), d:stat("defense"), d:buff_ticks("frail"))
print(d:add_buff("ironskin", 2), d:stat("defense"), d:has_buff("frail"), d:add_buff("frail", 5))
print(pcall(d.add_buff, d, "slow", 5))
d:set_modifiers("gear", { defense = 1 })
print(d:stat("defense"), d:node("health_bar") ~= nil, d:node("health_bar"):display_text())
print(d:damage(4), d:health(), d:node("health_bar"):is_visible())
nf.after(4, function()
    print(#d:buffs(), d:damage(100, { cause = "fall" }), d:exists())
end)
"#,
        ),
    ]);
    let session = Session::start(platform.clone(), &files, &NoPackages);
    let start = platform.take();
    assert_eq!(
        printed(&start),
        [
            "true\t-4\t5",
            "ended\tfrail\tcured",
            "true\t6\tfalse\tfalse",
            "false\tmodules/game/init.lua:9: there's no buff \"slow\" (buffs/slow.json)",
            "7\ttrue\t<red>▮▮▮▮▮<dark_gray>",
            "2\t8\ttrue",
        ],
        "{start:?}"
    );
    for _ in 0..5 {
        session.tick();
    }
    let lines = printed(&platform.take());
    assert_eq!(
        lines,
        [
            "ended\tironskin\texpired",
            "died\tfall",
            "removed",
            "0\t8\tfalse"
        ],
        "{lines:?}"
    );
}

#[test]
fn a_body_falls_lands_walks_into_a_wall_and_jumps() {
    let platform = std::rc::Rc::new(Fake::default());
    let centity = lamp(1.0);
    let end = centity.rfind('}').expect("an object");
    let centity = format!(
        r#"{}, "body": {{ "width": 0.6, "height": 1.8 }} }}"#,
        centity[..end].trim_end()
    );
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("centities/walker/centity.json", &centity),
        (
            "modules/game/init.lua",
            r#"
local w = nf.centities.spawn("walker", vector.create(8.5, 66, 0.5))
w:on("land", function(e) print("landed", e.speed > 0, w:position().y, w:is_on_ground()) end)
w:on("bump", function(e) print("bump", e.normal.x) end)
print(w:has_body(), w:body().height, w:is_on_ground())
nf.after(30, function()
    w:set_walk_velocity(vector.create(4, 0, 0))
end)
nf.after(50, function()
    print("at wall", string.format("%.1f", w:position().x), w:wall() and w:wall().x)
    w:set_walk_velocity(nil)
    print(w:jump(1.25), w:is_on_ground(), w:velocity().y > 0)
end)
"#,
        ),
    ]);
    let session = Session::start(platform.clone(), &files, &NoPackages);
    let start = platform.take();
    assert_eq!(printed(&start), ["true\t1.8\tfalse"], "{start:?}");
    let mut lines = Vec::new();
    for _ in 0..52 {
        session.tick();
        lines.extend(printed(&platform.take()));
    }
    assert_eq!(lines[0], "landed\ttrue\t64\ttrue", "{lines:?}");
    assert!(lines.iter().any(|l| l == "bump\t-1"), "{lines:?}");
    // It stops with its side against the wall's face, at x 10.
    assert!(lines.iter().any(|l| l == "at wall\t9.7\t-1"), "{lines:?}");
    assert!(lines.iter().any(|l| l == "true\tfalse\ttrue"), "{lines:?}");
}

#[test]
fn centities_hear_what_their_hitboxes_start_touching() {
    let platform = std::rc::Rc::new(Fake::default());
    let block = r#"{ "nodes": { "cube": { "display": { "type": "block", "block": "minecraft:stone" }, "hitbox": {} } } }"#;
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("centities/cube/centity.json", block),
        (
            "modules/game/init.lua",
            r#"
local a = nf.centities.spawn("cube", vector.create(0, 64, 0))
local b = nf.centities.spawn("cube", vector.create(0.5, 64, 0))
a:on("touch", function(e) print("touch", e.node:name(), e.other == b, e.other_node:name(), e.entity) end)
nf.after(3, function() b:teleport(vector.create(5, 64, 0)) end)
nf.after(5, function() b:teleport(vector.create(1, 64, 0)) end)
"#,
        ),
    ]);
    let session = Session::start(platform.clone(), &files, &NoPackages);
    platform.take();
    let mut lines = Vec::new();
    for _ in 0..7 {
        session.tick();
        lines.extend(printed(&platform.take()));
    }
    // Once as they start touching, not again while they do; again after they part and meet (faces touching).
    assert_eq!(
        lines,
        [
            "touch\tcube\ttrue\tcube\tnil",
            "touch\tcube\ttrue\tcube\tnil"
        ],
        "{lines:?}"
    );
}

#[test]
fn a_launched_centity_hits_what_it_meets_and_ends_on_a_block() {
    let platform = std::rc::Rc::new(Fake::default());
    let cube = r#"{ "nodes": { "cube": { "display": { "type": "block", "block": "minecraft:stone" }, "hitbox": {} } } }"#;
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("centities/cube/centity.json", cube),
        (
            "modules/game/init.lua",
            r#"
local target = nf.centities.spawn("cube", vector.create(5, 64, 0))
local shot = nf.centities.spawn("cube", vector.create(0, 64.5, 0.5))
shot:on("hit", function(e) print("hit", e.target == target, e.node:name()) end)
shot:on("flight_end", function(e) print("end", e.reason, e.target == target) end)
print(shot:launch({ velocity = vector.create(20, 0, 0) }), shot:is_launched(), shot:velocity().x)
local ball = nf.centities.spawn("cube", vector.create(-5, 66, 0.5))
ball:on("flight_end", function(e) print("ball", e.reason, e.normal and e.normal.y, e.block ~= nil) end)
ball:launch({ velocity = vector.create(0, -10, 0), radius = 0 })
print(pcall(ball.launch, ball, { velocity = vector.create(1, 0, 0), bounce = 2 }))
"#,
        ),
    ]);
    let session = Session::start(platform.clone(), &files, &NoPackages);
    let start = platform.take();
    let lines = printed(&start);
    assert_eq!(
        lines.first().map(String::as_str),
        Some("true\ttrue\t20"),
        "{start:?}"
    );
    assert!(
        lines
            .get(1)
            .is_some_and(|l| l.contains("0 to 1 expected, got 2")),
        "{start:?}"
    );
    let mut lines = Vec::new();
    for _ in 0..20 {
        session.tick();
        lines.extend(printed(&platform.take()));
    }
    assert!(lines.contains(&"hit\ttrue\tcube".to_owned()), "{lines:?}");
    assert!(lines.contains(&"end\thit\ttrue".to_owned()), "{lines:?}");
    assert!(
        lines.contains(&"ball\tblock\t1\ttrue".to_owned()),
        "{lines:?}"
    );
    // Both flights removed what flew; the target stays.
    assert_eq!(session.centities().all().count(), 1);
}

#[test]
fn a_centity_walks_to_a_place() {
    let platform = std::rc::Rc::new(Fake::default());
    let walker = r#"{ "nodes": { "body": { "display": { "type": "block", "block": "minecraft:stone" }, "hitbox": { "boxes": [{ "min": [-0.3, 0, -0.3], "max": [0.3, 1.8, 0.3] }] } } } }"#;
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("centities/walker/centity.json", walker),
        (
            "modules/game/init.lua",
            r#"
local w = nf.centities.spawn("walker", vector.create(8.5, 64, 0.5))
w:on("path_end", function(e) print("arrived", e.reached, math.floor(w:position().x)) end)
print(w:move_to(vector.create(2.5, 64, 0.5)), w:has_path(), w:path_target().x)
print(w:move_to(vector.create(12.5, 64, 0.5)), w:has_path())
print(w:move_to(vector.create(2.5, 64, 0.5), { speed = 8 }))
print(pcall(w.move_to, w, vector.create(0, 64, 0), { speed = 50 }))
"#,
        ),
    ]);
    let session = Session::start(platform.clone(), &files, &NoPackages);
    let start = platform.take();
    let lines = printed(&start);
    assert_eq!(
        lines.first().map(String::as_str),
        Some("true\ttrue\t2.5"),
        "{start:?}"
    );
    // The wall at x 10 is endless: a partial path, as close as it gets (Kotlin walks it too).
    assert_eq!(
        lines.get(1).map(String::as_str),
        Some("true\ttrue"),
        "{start:?}"
    );
    assert!(lines.get(3).is_some_and(|l| l.contains("options.speed must be more than 0 and at most 20, not 50")), "{start:?}");
    let mut lines = Vec::new();
    for _ in 0..40 {
        session.tick();
        lines.extend(printed(&platform.take()));
    }
    assert_eq!(lines, ["arrived\ttrue\t2"], "{lines:?}");
}

#[test]
fn a_physics_body_falls_lands_settles_and_is_pushed() {
    let platform = std::rc::Rc::new(Fake::default());
    let crate_ = r#"{ "nodes": { "box": { "display": { "type": "block", "block": "minecraft:barrel" }, "physics": {} } } }"#;
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("centities/crate/centity.json", crate_),
        (
            "modules/game/init.lua",
            r#"
local c = nf.centities.spawn("crate", vector.create(0.5, 67, 0.5))
local b = c:node("box")
b:on("collide", function(e) print("collide", e.other, e.hit_normal.y > 0.5, e.speed > 1) end)
b:on("sleep", function() print("sleep", string.format("%.2f", b:world_position().y), b:is_on_ground()) end)
b:on("wake", function() print("wake") end)
print(b:mass(), b:has_gravity(), b:is_physics_enabled())
nf.after(120, function()
    print(b:apply_impulse(vector.create(0, 5, 0)), b:velocity().y > 0)
end)
"#,
        ),
    ]);
    let session = Session::start(platform.clone(), &files, &NoPackages);
    let start = platform.take();
    assert_eq!(printed(&start), ["1\ttrue\ttrue"], "{start:?}");
    let mut lines = Vec::new();
    for _ in 0..130 {
        session.tick();
        lines.extend(printed(&platform.take()));
    }
    assert!(
        lines
            .first()
            .is_some_and(|l| l == "collide\tnil\ttrue\ttrue"),
        "{lines:?}"
    );
    // It comes to rest on the floor at y 64 (its unit cube's bottom is its origin).
    assert!(lines.iter().any(|l| l == "sleep\t64.00\ttrue"), "{lines:?}");
    assert!(lines.iter().any(|l| l == "true\ttrue"), "{lines:?}");
    assert!(lines.iter().any(|l| l == "wake"), "{lines:?}");
}

#[test]
fn a_body_lands_on_another_centitys_hitbox_and_on_another_centitys_body() {
    let platform = std::rc::Rc::new(Fake::default());
    let crate_ = r#"{ "nodes": { "box": { "display": { "type": "block", "block": "minecraft:barrel" }, "physics": {} } } }"#;
    let table = r#"{ "nodes": { "top": { "display": { "type": "block", "block": "minecraft:stone" }, "hitbox": {} } } }"#;
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("centities/crate/centity.json", crate_),
        ("centities/table/centity.json", table),
        (
            "modules/game/init.lua",
            r#"
nf.centities.spawn("table", vector.create(0, 64, 0))
local a = nf.centities.spawn("crate", vector.create(0, 66, 0))
local b = nf.centities.spawn("crate", vector.create(5, 64, 0))
local c = nf.centities.spawn("crate", vector.create(5, 67, 0))
nf.after(100, function()
    print(string.format("%.2f %.2f %.2f",
        a:node("box"):world_position().y, b:node("box"):world_position().y, c:node("box"):world_position().y))
end)
"#,
        ),
    ]);
    let session = Session::start(platform.clone(), &files, &NoPackages);
    platform.take();
    let mut lines = Vec::new();
    for _ in 0..101 {
        session.tick();
        lines.extend(printed(&platform.take()));
    }
    // On the table's hitbox; on the floor; on the other crate.
    assert_eq!(lines, ["65.00 64.00 65.00"], "{lines:?}");
}

#[test]
fn an_instance_and_its_data_outlive_the_session_and_its_centity_going_away() {
    let platform = std::rc::Rc::new(Fake::default());
    let store = std::rc::Rc::new(netherforge_runtime::store::Store::in_memory());
    let centity = scripted_lamp();
    let script = r#"
this:on("spawn", function() print("spawn") end)
local data = this:data()
data.visits = (data.visits or 0) + 1
print("visits", data.visits)
"#;
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("centities/lamp/centity.json", &centity),
        ("centities/lamp/script.lua", script),
        ("modules/game/init.lua", ""),
    ]);
    {
        let session = Session::start_with(platform.clone(), &files, &NoPackages, store.clone());
        session
            .spawn("lamp", place(3.0, 64.0, 3.0), 90.0)
            .expect("spawned");
        session.tick();
        let calls = platform.take();
        assert_eq!(printed(&calls), ["visits\t1", "spawn"], "{calls:?}");
    }
    // The session stopped: its instance stays in the world, its entities with it.
    let calls = platform.take();
    assert!(!calls.iter().any(|c| c.starts_with("remove")), "{calls:?}");
    assert_eq!(store.instances().len(), 1);
    let id = store.instances()[0].id;
    {
        let mut session = Session::start_with(platform.clone(), &files, &NoPackages, store.clone());
        let calls = platform.take();
        // Restored, not spawned: no new entities, no `spawn`, the data as it was saved.
        assert_eq!(printed(&calls), ["visits\t2"], "{calls:?}");
        session.tick();
        let calls = platform.take();
        assert!(
            !calls
                .iter()
                .any(|c| c.starts_with("anchor") || c.starts_with("display")),
            "{calls:?}"
        );
        assert_eq!(session.centities().get(id).map(|i| i.yaw()), Some(90.0));
        // Its centity goes: it's inert, kept; back, it's an instance again.
        files.delete("centities/lamp/centity.json");
        session.reload(&["centities/lamp/centity.json".into()]);
        assert!(session.centities().get(id).is_none());
        assert_eq!(session.centities().inert().count(), 1);
        session.tick();
        assert_eq!(store.instances().len(), 1);
        files.add("centities/lamp/centity.json", &centity);
        session.reload(&["centities/lamp/centity.json".into()]);
        session.tick();
        assert!(session.centities().get(id).is_some());
        assert_eq!(printed(&platform.take()), ["visits\t3"]);
    }
}

#[test]
fn a_script_draws_a_picture_on_an_image_display() {
    let platform = std::rc::Rc::new(Fake::default());
    let centity =
        r#"{ "nodes": { "screen": { "display": { "type": "image", "width": 2, "height": 2 } } } }"#;
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("centities/screen/centity.json", centity),
        (
            "modules/game/init.lua",
            r##"
local s = nf.centities.spawn("screen", vector.create(0, 64, 0)):node("screen")
local red = nf.images.fill(2, 2, "#ff0000")
print(red:width(), red:pixel(1, 1), red:pixel(5, 5), s:image())
print(s:set_image(red), s:image():pixel(0, 0))
local copy = nf.images.decode(red:encode())
print(copy:height(), copy:pixel(0, 1), red:cropped(1, 0, 1, 2):width())
print(pcall(nf.images.fill, 0, 2, "#ff0000"))
"##,
        ),
    ]);
    let session = Session::start(platform.clone(), &files, &NoPackages);
    let start = platform.take();
    assert_eq!(
        printed(&start),
        [
            "2\t#FF0000\tnil\tnil",
            "true\t#FF0000",
            "2\t#FF0000\t1",
            "false\tmodules/game/init.lua:8: an image is 1 to 2048 pixels each way, not 0 by 2",
        ],
        "{start:?}"
    );
    session.tick();
    let calls = platform.take();
    assert!(
        calls
            .iter()
            .any(|c| c.starts_with("update ") && !c.ends_with("runs 0")),
        "{calls:?}"
    );
}

#[test]
fn an_instance_hears_its_chunk_unload_and_load_and_strays_go() {
    let platform = std::rc::Rc::new(Fake::default());
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("centities/lamp/centity.json", &scripted_lamp()),
        (
            "centities/lamp/script.lua",
            r#"
this:on("chunk_unload", function() print("unload") end)
this:on("chunk_load", function() print("load") end)
local ticks = 0
this:on("tick", function() ticks += 1 end)
nf.every(1, function() if ticks == 5 then print("five") end end)
"#,
        ),
    ]);
    let session = Session::start(platform.clone(), &files, &NoPackages);
    session
        .spawn("lamp", place(0.0, 64.0, 0.0), 0.0)
        .expect("spawned");
    platform.take();
    session.tick();
    platform.unloaded.set(true);
    for _ in 0..10 {
        session.tick();
    }
    platform.unloaded.set(false);
    for _ in 0..5 {
        session.tick();
    }
    // One tick, ten without (unloaded), then five more: its tick handler ran six times.
    let lines = printed(&platform.take());
    assert_eq!(lines, ["unload", "load", "five"], "{lines:?}");
    // A tagged entity no instance knows is a stray.
    let stray = netherforge_platform::EntityTag {
        instance: Uuid(12345),
        node: "x".into(),
        role: EntityRole::Display,
    };
    session.entities_loaded(&std::collections::HashMap::from([(Uuid(999), stray)]), &[]);
    assert!(platform.take().contains(&"remove 999".to_owned()));
}

#[test]
fn centities_appear_near_a_player_by_their_rules_and_go_when_nobody_is_near() {
    let platform = std::rc::Rc::new(Fake::default());
    let wisp = r#"{ "nodes": { "glow": { "display": { "type": "block", "block": "minecraft:glowstone" } } },
  "spawning": { "cap": 2, "light": { "min": 10 }, "blocks": ["minecraft:stone"], "when": "calm" } }"#;
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("centities/wisp/centity.json", wisp),
        (
            "modules/game/init.lua",
            r#"
local calm = false
nf.spawning.condition("calm", function(check) return calm and check.centity == "wisp" end)
nf.after(20, function() calm = true end)
nf.on("centity_natural_spawn", function(e) print("appears", e.centity, e.location.position.y) end)
print(pcall(nf.spawning.condition, "calm", function() end))
"#,
        ),
    ]);
    *platform.player.borrow_mut() = Some(place(0.5, 64.0, 0.5));
    let session = Session::start(platform.clone(), &files, &NoPackages);
    let start = printed(&platform.take());
    assert!(
        start
            .first()
            .is_some_and(|l| l.contains("already registered")),
        "{start:?}"
    );
    // Not calm yet: nothing appears (calm comes at tick 20's start, before its pass).
    for _ in 0..19 {
        session.tick();
    }
    let early = platform.take();
    assert_eq!(session.centities().all().count(), 0, "{early:?}");
    for _ in 0..61 {
        session.tick();
    }
    let lines = printed(&platform.take());
    let count = session.centities().all().count();
    assert!((1..=2).contains(&count), "{count} {lines:?}");
    assert!(lines.iter().all(|l| l == "appears\twisp\t64"), "{lines:?}");
    for instance in session.centities().all() {
        let a = instance.anchor();
        let distance = (a.x * a.x + a.z * a.z).sqrt();
        assert!(
            instance.natural && a.y == 64.0 && (23.0..=49.0).contains(&distance),
            "{a:?}"
        );
    }
    // The player goes far away: at the next sweep these go (others may appear where they are now).
    let old: Vec<Uuid> = session.centities().all().map(|i| i.id).collect();
    *platform.player.borrow_mut() = Some(place(5000.5, 64.0, 0.5));
    for _ in 0..20 {
        session.tick();
    }
    assert!(old.iter().all(|id| session.centities().get(*id).is_none()));
    assert!(session.centities().all().all(|i| i.anchor().x > 4000.0));
}

fn alex() -> netherforge_platform::PlayerRef {
    netherforge_platform::PlayerRef {
        uuid: Uuid(0x0f1b_0c7e_6a43_3d1f_9a8c_2b5e_8f1a_0c11),
        name: "Alex".into(),
    }
}

fn stack(item: &str) -> netherforge_platform::ItemData {
    netherforge_platform::ItemData {
        def: netherforge_format::item::ItemDef {
            item: Some(item.into()),
            ..Default::default()
        },
        raw: None,
        look: None,
    }
}

#[test]
fn the_servers_own_events_reach_scripts_and_their_answers_go_back() {
    use netherforge_platform::{BlockRef, PlatformEvents};
    use netherforge_runtime::server::ServerEvents;
    let platform = std::rc::Rc::new(Fake::default());
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        (
            "modules/rules/init.lua",
            r#"
nf.on("player_interact", function(e) print("interact", e.click, e.block ~= nil, e.face, e.hand); e:cancel() end)
nf.on("block_break", function(e)
    print("break", e.state, #e.drops, e.drops[1].item, e.experience)
    e.drops = {}
    e.experience = 7
end)
nf.on("entity_damage", function(e) print("damage", e.amount, e.cause, e.attacker ~= nil); e.amount = e.amount * 2 end)
nf.on("player_death", function(e) print("death", e.cause, e.message); e.message = nil; e.keep_inventory = true end)
nf.on("entity_death", function(e) print("entity death", #e.drops, e.experience) end)
"#,
        ),
    ]);
    let server = ServerEvents::new(Session::start(platform.clone(), &files, &NoPackages));
    platform.take();
    let block = BlockRef {
        world: "world".into(),
        x: 3,
        y: 63,
        z: 4,
        id: "minecraft:dirt".into(),
        state: "minecraft:dirt".into(),
    };
    assert!(server.player_interact(
        alex(),
        ClickButton::Right,
        Some(block.clone()),
        Some("up".into()),
        None,
        "main_hand".into()
    ));
    let answer = server
        .block_break(
            alex(),
            block.clone(),
            Box::new(|| vec![stack("minecraft:dirt")]),
            0,
        )
        .expect("not cancelled");
    assert_eq!(answer.drops, Some(vec![]));
    assert_eq!(answer.experience, 7);
    assert_eq!(
        server.entity_damaged(Uuid(5), 3.0, "entity_attack".into(), Some(alex().uuid)),
        Some(6.0)
    );
    // Keeping the inventory drops nothing from it; the message is gone.
    let death = server.player_died(
        alex(),
        None,
        "fall".into(),
        Some("Alex fell".into()),
        false,
        vec![stack("minecraft:dirt")],
        10,
    );
    assert_eq!(death.message, None);
    assert!(death.keep_inventory);
    assert_eq!(death.drops, Some(vec![]));
    assert_eq!(death.experience, 10);
    // What handlers printed is logged with the next tick.
    server.tick();
    let lines = printed(&platform.take());
    assert_eq!(
        lines,
        [
            "interact\tright\ttrue\tup\tmain_hand",
            "break\tminecraft:dirt\t1\tminecraft:dirt\t0",
            "damage\t3\tentity_attack\ttrue",
            "death\tfall\tAlex fell",
            "entity death\t0\t10",
        ],
        "{lines:?}"
    );
    // Nothing listens to dropping an item: it goes through, and nothing was built for it.
    assert!(!server.player_drop_item(alex(), stack("minecraft:dirt")));
    // Once the session stops, every answer is "unchanged".
    server.session_mut().stop();
    assert!(!server.player_interact(
        alex(),
        ClickButton::Left,
        None,
        None,
        None,
        "main_hand".into()
    ));
    assert_eq!(
        server.entity_damaged(Uuid(5), 3.0, "fall".into(), None),
        Some(3.0)
    );
}

#[test]
fn a_body_resting_on_a_block_falls_when_the_block_is_broken() {
    use netherforge_platform::{BlockRef, PlatformEvents};
    use netherforge_runtime::server::ServerEvents;
    let platform = std::rc::Rc::new(Fake::default());
    let crate_ = r#"{ "nodes": { "box": { "display": { "type": "block", "block": "minecraft:barrel" }, "physics": {} } } }"#;
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("centities/crate/centity.json", crate_),
        (
            "modules/game/init.lua",
            r#"
local b = nf.centities.spawn("crate", vector.create(0.5, 65, 0.5)):node("box")
b:on("sleep", function() print("sleep", string.format("%.2f", b:world_position().y)) end)
b:on("wake", function() print("wake") end)
"#,
        ),
    ]);
    let server = ServerEvents::new(Session::start(platform.clone(), &files, &NoPackages));
    let mut lines = Vec::new();
    for _ in 0..120 {
        server.tick();
        lines.extend(printed(&platform.take()));
    }
    assert_eq!(lines, ["sleep\t64.00"], "{lines:?}");
    // The floor under it goes, wider than the crate.
    for x in -1..=1 {
        for z in -1..=1 {
            let block = BlockRef {
                world: "world".into(),
                x,
                y: 63,
                z,
                id: "minecraft:stone".into(),
                state: "minecraft:stone".into(),
            };
            server.block_break(alex(), block, Box::new(Vec::new), 0);
            platform.broken.borrow_mut().insert((x, 63, z));
        }
    }
    // It wakes at once, not when the blocks would next have been read again.
    server.tick();
    server.tick();
    assert_eq!(printed(&platform.take()), ["wake"]);
    let mut lines = Vec::new();
    for _ in 0..120 {
        server.tick();
        lines.extend(printed(&platform.take()));
    }
    assert_eq!(lines, ["sleep\t63.00"], "{lines:?}");
}

#[test]
fn a_world_answers_from_the_server_and_its_centities() {
    let platform = std::rc::Rc::new(Fake::default());
    let table = r#"{ "nodes": { "top": { "display": { "type": "block", "block": "minecraft:stone" }, "hitbox": {} } } }"#;
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("centities/table/centity.json", table),
        (
            "modules/look/init.lua",
            r#"
local w = nf.worlds.default()
print(w:name(), w:exists(), w:time_of_day(), w:is_day(), w:is_night(), w:day_count(), w:moon_phase(), w:weather())
print(w:min_height(), w:max_height(), w:block(vector.create(1.5, 63.2, -0.5)):world():name(), w:border() ~= nil)
local box = w:blocks(vector.create(9, 63, 0), vector.create(10, 64, 0), { what = "class" })
print(box.size_x, box.size_y, box.size_z, table.concat(box.cells, ","))
local f = w:flood(vector.create(0, 64, 0), { through = { "minecraft:air" }, bounds = { min = vector.create(0, 64, 0), max = vector.create(12, 64, 0) }, kinds = true })
print(#f.positions, #f.edges, f.edge_kinds[1], f.stopped)
local down = w:raycast(vector.create(0.5, 70, 0.5), vector.create(0, -1, 0), 20)
print(down.block ~= nil, string.format("%.1f", down.distance), down.normal.y)
local t = nf.centities.spawn("table", vector.create(3.5, 64, 0.5))
local hit = w:raycast(vector.create(0.5, 64.5, 0.5), vector.create(1, 0, 0), 20)
print(hit.centity == t, hit.node:name(), string.format("%.2f", hit.distance))
local near = w:overlap_sphere(vector.create(3.5, 64.5, 0.5), 2, { entities = false })
print(#near, near[1].centity == t, w:nearest(vector.create(3.5, 64.5, 0.5), 2).node:name())
print(#w:centities(), #w:centities({ kind = "nope" }), #w:entities({ kind = "zombie", tag = "guard" }), #w:entities({ living = false }))
print(w:game_rule("keep_inventory"), pcall(w.game_rule, w, "nope"))
print(#nf.worlds.all(), nf.worlds.get("nether"))
w:on("dusk", function() end)
local b = w:block(vector.create(2, 63, 2))
print(b:kind(), b:is_solid(), b:relative("up"):is_air(), b:relative(vector.create(0, 1, 0)) == b:relative("up"), b:position().y)
print(b:set_state("stone", { update = false }), pcall(b.set_state, b, "minecraft:stone[facing=up]"))
"#,
        ),
    ]);
    let _session = Session::start(platform.clone(), &files, &NoPackages);
    let all = platform.take();
    let lines = printed(&all);
    assert!(!all.iter().any(|l| l.starts_with("warn")), "{all:?}");
    assert_eq!(
        lines,
        [
            "world\ttrue\t6000\ttrue\tfalse\t0\tfull_moon\tclear",
            "-64\t320\tworld\ttrue",
            "2\t2\t1\t2,2,0,2",
            "10\t1\tminecraft:stone\tbounds",
            "true\t6.0\t1",
            "true\ttop\t3.00",
            "1\ttrue\ttop",
            "1\t0\t1\t0",
            "true\tfalse\tmodules/look/init.lua:17: no game rule \"nope\" on this server",
            "1\tnil",
            "minecraft:stone\ttrue\ttrue\ttrue\t63",
            "true\tfalse\tmodules/look/init.lua:22: state: minecraft:stone has no property \"facing\" (it has: none)",
        ],
        "{lines:?}"
    );
    assert!(
        all.contains(&"set 2 63 2 minecraft:stone false".to_owned()),
        "{all:?}"
    );
}
