//! Centities on the Rust runtime against a fake Platform that records what's asked of the server's
//! entities: an instance spawned as an anchor and its displays, synced each tick writing only what changed,
//! moved onto a reloaded definition, and removed.

use std::cell::RefCell;
use std::collections::BTreeMap;

use netherforge_format::kind::centity::DisplayDef;
use netherforge_format::package::NoPackages;
use netherforge_format::project::ProjectSource;
use netherforge_platform::{
    DisplayLook, DisplayPose, EntityOps, EntityRole, EntityTag, FakeEntityOps, FakePlatform,
    FakePlatformLog, FakeWorldOps, Location, PlatformError, PlatformLog, ServerInfo, Uuid,
    WorldOps,
};
use netherforge_runtime::session::Session;

#[derive(Default)]
struct Fake {
    calls: RefCell<Vec<String>>,
    next: RefCell<u128>,
    poses: RefCell<BTreeMap<u128, DisplayPose>>,
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
    fn update_display(&self, _: Uuid, _: DisplayDef) -> bool {
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

impl FakeWorldOps for Fake {
    fn exists(&self, world: String) -> bool {
        world == "world"
    }
    fn default_world(&self) -> String {
        "world".into()
    }
    fn entities_loaded(&self, _: Location) -> bool {
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
    fn entities(&self) -> &dyn EntityOps {
        self
    }
    fn worlds(&self) -> &dyn WorldOps {
        self
    }
}

#[derive(Default)]
struct Files {
    names: std::collections::BTreeSet<String>,
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
    fn write(&self, path: &str, text: &str) {
        self.texts.borrow_mut().insert(path.into(), text.into());
    }
}

impl ProjectSource for Files {
    fn files(&self) -> &std::collections::BTreeSet<String> {
        &self.names
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
    let mut session = Session::start(platform.clone(), &files, &NoPackages);
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
