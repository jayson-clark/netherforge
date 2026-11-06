//! Mobs' walks and goals on the Rust runtime (Kotlin's `MobTest` and `MobGoalTest`), against a fake server whose
//! AI thinks after every tick as the server's does after plugins: the game's goals listed, removed and cleared;
//! goals written in Lua run by the AI, held to their scope and to the AI's own loop; walks and `path_end`.

use std::cell::{Cell, RefCell};
use std::collections::{BTreeMap, BTreeSet};
use std::rc::Rc;

use netherforge_format::math::Vec3;
use netherforge_format::package::NoPackages;
use netherforge_format::project::ProjectSource;
use netherforge_platform::{
    EntityCategory, EntityInfo, FakePlatform, FakePlatformLog, FakeWorldEntityOps, FakeWorldOps,
    GoalCallbacks, GoalControl, GoalInfo, Location, MobGoalOps, PathfindingOps, PlatformError,
    PlatformLog, ServerInfo, Uuid,
};
use netherforge_runtime::session::{Config, Session};

const ZOMBIE: Uuid = Uuid(1);
const PIG: Uuid = Uuid(2);

struct Goal {
    key: String,
    priority: i32,
    controls: Vec<GoalControl>,
    running: bool,
    hook: Option<Rc<dyn GoalCallbacks>>,
}

#[derive(Default)]
struct Fake {
    lines: RefCell<Vec<String>>,
    /// Each mob's place, while it's there.
    mobs: RefCell<BTreeMap<u128, (String, Vec3)>>,
    goals: RefCell<BTreeMap<u128, Vec<Goal>>>,
    /// Whether the AI is going through goals now: a change then would break its loop.
    thinking: Cell<bool>,
    /// Each walking mob's path's end.
    paths: RefCell<BTreeMap<u128, Vec3>>,
}

impl Fake {
    fn with_mobs() -> Rc<Fake> {
        let fake = Fake::default();
        fake.mobs
            .borrow_mut()
            .insert(ZOMBIE.0, ("minecraft:zombie".into(), Vec3(0.5, 64.0, 0.5)));
        fake.mobs
            .borrow_mut()
            .insert(PIG.0, ("minecraft:pig".into(), Vec3(5.5, 64.0, 0.5)));
        let game = |key: &str, priority, controls: &[GoalControl]| Goal {
            key: key.into(),
            priority,
            controls: controls.to_vec(),
            running: false,
            hook: None,
        };
        fake.goals.borrow_mut().insert(
            ZOMBIE.0,
            vec![
                game("minecraft:float", 0, &[GoalControl::Jump]),
                game(
                    "minecraft:melee_attack",
                    2,
                    &[GoalControl::Move, GoalControl::Look],
                ),
                game("minecraft:random_stroll", 7, &[GoalControl::Move]),
                game("minecraft:look_at_player", 8, &[GoalControl::Look]),
                game("minecraft:hurt_by_target", 1, &[GoalControl::Target]),
            ],
        );
        fake.goals.borrow_mut().insert(PIG.0, Vec::new());
        Rc::new(fake)
    }

    /// The AI's turn: each Lua goal is asked to start or go on, ticked, or stopped (no controls are contested).
    fn think(&self) {
        let hooks: Vec<(u128, String, Rc<dyn GoalCallbacks>, bool)> = self
            .goals
            .borrow()
            .iter()
            .flat_map(|(mob, goals)| {
                goals
                    .iter()
                    .filter_map(move |g| Some((*mob, g.key.clone(), g.hook.clone()?, g.running)))
            })
            .collect();
        self.thinking.set(true);
        for (mob, key, hook, running) in hooks {
            let now = if running {
                if hook.should_continue() {
                    hook.tick();
                    true
                } else {
                    hook.stop();
                    false
                }
            } else if hook.should_start() {
                hook.start();
                hook.tick();
                true
            } else {
                false
            };
            if let Some(goal) = self
                .goals
                .borrow_mut()
                .get_mut(&mob)
                .and_then(|g| g.iter_mut().find(|g| g.key == key))
            {
                goal.running = now;
            }
        }
        self.thinking.set(false);
    }

    fn unchanged_while_thinking(&self) {
        assert!(
            !self.thinking.get(),
            "goals changed while the AI went through them"
        );
    }

    fn keys(&self, mob: Uuid) -> Vec<String> {
        self.goals.borrow()[&mob.0]
            .iter()
            .map(|g| g.key.clone())
            .collect()
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

impl FakeWorldOps for Fake {
    fn default_world(&self) -> String {
        "world".into()
    }
    fn exists(&self, world: String) -> bool {
        world == "world"
    }
}

impl FakeWorldEntityOps for Fake {
    fn info(&self, id: Uuid) -> Option<EntityInfo> {
        let mobs = self.mobs.borrow();
        let (kind, at) = mobs.get(&id.0)?;
        Some(EntityInfo {
            id,
            kind: kind.clone(),
            location: Location {
                world: "world".into(),
                x: at.0,
                y: at.1,
                z: at.2,
                yaw: 0.0,
                pitch: 0.0,
            },
            category: EntityCategory::Mob,
            player: None,
            tags: Vec::new(),
        })
    }
    fn list(&self, _world: String) -> Vec<EntityInfo> {
        let ids: Vec<u128> = self.mobs.borrow().keys().copied().collect();
        ids.into_iter()
            .filter_map(|id| FakeWorldEntityOps::info(self, Uuid(id)))
            .collect()
    }
}

impl MobGoalOps for Fake {
    fn goals(&self, mob: Uuid) -> Option<Vec<GoalInfo>> {
        if !self.mobs.borrow().contains_key(&mob.0) {
            return None;
        }
        Some(
            self.goals
                .borrow()
                .get(&mob.0)?
                .iter()
                .map(|g| GoalInfo {
                    key: g.key.clone(),
                    priority: g.priority,
                    controls: g.controls.clone(),
                    running: g.running,
                })
                .collect(),
        )
    }
    fn remove(&self, mob: Uuid, key: String) -> Option<Vec<String>> {
        self.unchanged_while_thinking();
        let mut all = self.goals.borrow_mut();
        let goals = all.get_mut(&mob.0)?;
        let (gone, kept): (Vec<Goal>, Vec<Goal>) = std::mem::take(goals)
            .into_iter()
            .partition(|g| g.key == key);
        *goals = kept;
        drop(all);
        Some(self.stopped(gone))
    }
    fn remove_with_goal(&self, mob: Uuid, goal: Rc<dyn GoalCallbacks>) -> bool {
        self.unchanged_while_thinking();
        let mut all = self.goals.borrow_mut();
        let Some(goals) = all.get_mut(&mob.0) else {
            return false;
        };
        let before = goals.len();
        goals.retain(|g| g.hook.as_ref().is_none_or(|h| !Rc::ptr_eq(h, &goal)));
        before != goals.len()
    }
    fn clear(&self, mob: Uuid, keep: Vec<String>) -> Option<Vec<String>> {
        self.unchanged_while_thinking();
        let mut all = self.goals.borrow_mut();
        let goals = all.get_mut(&mob.0)?;
        let (kept, gone): (Vec<Goal>, Vec<Goal>) = std::mem::take(goals)
            .into_iter()
            .partition(|g| keep.contains(&g.key));
        *goals = kept;
        drop(all);
        Some(self.stopped(gone))
    }
    fn add(
        &self,
        mob: Uuid,
        key: String,
        priority: i32,
        controls: Vec<GoalControl>,
        goal: Rc<dyn GoalCallbacks>,
    ) -> bool {
        self.unchanged_while_thinking();
        if !self.mobs.borrow().contains_key(&mob.0) {
            return false;
        }
        let replaced = MobGoalOps::remove(self, mob, key.clone());
        drop(replaced);
        self.goals
            .borrow_mut()
            .entry(mob.0)
            .or_default()
            .push(Goal {
                key,
                priority,
                controls,
                running: false,
                hook: Some(goal),
            });
        true
    }
}

impl Fake {
    /// Goals taken off: a running one stops.
    fn stopped(&self, gone: Vec<Goal>) -> Vec<String> {
        for goal in &gone {
            if goal.running
                && let Some(hook) = &goal.hook
            {
                hook.stop();
            }
        }
        gone.into_iter().map(|g| g.key).collect()
    }
}

impl PathfindingOps for Fake {
    fn move_to(&self, id: Uuid, to: Vec3, _speed: f64) -> bool {
        if !self.mobs.borrow().contains_key(&id.0) || to.0 > 100.0 {
            return false;
        }
        // Paths end at block centres.
        self.paths.borrow_mut().insert(
            id.0,
            Vec3(to.0.floor() + 0.5, to.1.floor(), to.2.floor() + 0.5),
        );
        true
    }
    fn stop(&self, id: Uuid) -> bool {
        self.paths.borrow_mut().remove(&id.0).is_some()
    }
    fn has_path(&self, id: Uuid) -> bool {
        self.paths.borrow().contains_key(&id.0)
    }
    fn path_end(&self, id: Uuid) -> Option<Vec3> {
        self.paths.borrow().get(&id.0).copied()
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
    fn worlds(&self) -> &dyn netherforge_platform::WorldOps {
        self
    }
    fn world_entities(&self) -> &dyn netherforge_platform::WorldEntityOps {
        self
    }
    fn mob_goals(&self) -> &dyn MobGoalOps {
        self
    }
    fn pathfinding(&self) -> &dyn PathfindingOps {
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
    names: RefCell<&'static BTreeSet<String>>,
    texts: RefCell<BTreeMap<String, String>>,
}

impl Files {
    fn new(script: &str) -> Files {
        let manifest = r#"{ "formatVersion": 1, "name": "Test", "namespace": "test", "version": "1.0.0", "minecraft": "26.3" }"#;
        let files = [
            ("netherforge.json".to_owned(), manifest.to_owned()),
            ("modules/t/init.lua".to_owned(), script.to_owned()),
        ];
        Files {
            names: RefCell::new(Box::leak(Box::new(
                files.iter().map(|(p, _)| p.clone()).collect(),
            ))),
            texts: RefCell::new(files.into_iter().collect()),
        }
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

fn start<'a>(files: &'a Files, platform: &Rc<Fake>) -> Session<'a> {
    Session::start_configured(
        platform.clone(),
        files,
        &NoPackages,
        Rc::new(netherforge_runtime::store::Store::in_memory()),
        Config::default(),
    )
}

/// One server tick: the mobs' AI, then the runtime's (which logs what the callbacks printed).
fn tick(session: &Session, platform: &Fake) {
    platform.think();
    session.tick();
}

const PRELUDE: &str = r#"
local function keys(goals)
  local out = {}
  for _, goal in ipairs(goals) do out[#out + 1] = goal.key end
  return table.concat(out, ",")
end
local function try(f, ...) local ok, a = pcall(f, ...) print(ok, (tostring(a):gsub("^[^:]+:%d+: ", ""))) end
local world = nf.worlds.default()
local zombie = world:entities({ kind = "minecraft:zombie" })[1]
local pig = world:entities({ kind = "minecraft:pig" })[1]
"#;

#[test]
fn a_mobs_goals_are_listed_removed_and_cleared() {
    let files = Files::new(&format!(
        r#"{PRELUDE}
local goals = zombie:goals()
print(keys(goals))
print(goals[3].key, goals[3].priority, table.concat(goals[3].controls, ","), goals[3].running, table.concat(goals[2].controls, ","))
print(zombie:remove_goal("minecraft:random_stroll"), zombie:remove_goal("look_at_player"), zombie:remove_goal("random_stroll"))
print(keys(zombie:goals()))
print(zombie:clear_goals({{ keep = {{ "float", "minecraft:hurt_by_target" }} }}), keys(zombie:goals()))
print(zombie:clear_goals(), #zombie:goals())
try(zombie.remove_goal, zombie, "Random Stroll")
try(zombie.clear_goals, zombie, {{ keep = {{ "ok", "Not OK" }} }})
"#
    ));
    let platform = Fake::with_mobs();
    let _session = start(&files, &platform);
    assert_eq!(
        platform.lines(),
        [
            "minecraft:float,minecraft:hurt_by_target,minecraft:look_at_player,minecraft:melee_attack,minecraft:random_stroll",
            "minecraft:look_at_player 8 look false target",
            "true true false",
            "minecraft:float,minecraft:hurt_by_target,minecraft:melee_attack",
            "true minecraft:float,minecraft:hurt_by_target",
            "true 0",
            "false key: \"Random Stroll\" isn't a goal key: lowercase letters, digits, _, -, . and /, with a namespace or not",
            "false options.keep[2]: \"Not OK\" isn't a goal key: lowercase letters, digits, _, -, . and /, with a namespace or not",
        ]
    );
}

#[test]
fn a_goal_written_in_lua_runs_when_the_ai_starts_it_and_stops() {
    let files = Files::new(&format!(
        r#"{PRELUDE}
local wanted, ticks = false, 0
print(zombie:add_goal("guard_post", {{
  priority = 1,
  controls = {{ "move", "look" }},
  should_start = function(mob) print("asked", mob == zombie) return wanted end,
  should_continue = function() return ticks < 3 and 1 end,
  start = function() print("start") end,
  tick = function() ticks = ticks + 1; print("tick " .. ticks) end,
  stop = function() print("stop") end,
}}))
for _, it in ipairs(zombie:goals()) do
  if it.key == "test:guard_post" then print(it.priority, table.concat(it.controls, ",")) end
end
nf.after(2, function() wanted = true end)
try(zombie.add_goal, zombie, "both", {{ priority = 1, controls = {{ "target", "move" }} }})
try(zombie.add_goal, zombie, "fly", {{ priority = 1, controls = {{ "move", "fly" }} }})
try(zombie.add_goal, zombie, "twice", {{ priority = 1, controls = {{ "move", "move" }} }})
try(zombie.add_goal, zombie, "minecraft:float", {{ priority = 1 }})
try(zombie.add_goal, zombie, "Guard Post", {{ priority = 1 }})
"#
    ));
    let platform = Fake::with_mobs();
    let session = start(&files, &platform);
    assert_eq!(
        platform.lines(),
        [
            "true",
            "1 move,look",
            "false a goal that claims \"target\" is a targeting goal, so it can't claim anything else",
            "false bad argument 'definition.controls[2]' (one of \"move\", \"look\", \"jump\", \"target\" expected, got \"fly\")",
            "false definition.controls names a control twice",
            "false \"minecraft:float\" isn't one of the project's goal ids: \"<name>\" or \"test:<name>\"",
            "false \"Guard Post\" isn't a goal id: lowercase letters, digits, _, -, . and /",
        ]
    );
    for _ in 0..7 {
        tick(&session, &platform);
    }
    // should_continue's answer counts as Lua counts it: 1 is yes, nil is no.
    assert_eq!(
        platform.lines(),
        [
            "asked true",
            "asked true",
            "asked true",
            "start",
            "tick 1",
            "tick 2",
            "tick 3",
            "stop",
            "asked true",
            "start",
            "tick 4",
        ]
    );
}

#[test]
fn a_failing_callback_is_reported_and_its_goal_comes_off_at_the_next_tick() {
    let files = Files::new(&format!(
        r#"{PRELUDE}
zombie:add_goal("broken", {{ priority = 1, tick = function() error("boom") end }})
zombie:add_goal("fine", {{ priority = 2, tick = function() print("fine") end }})
"#
    ));
    let platform = Fake::with_mobs();
    let session = start(&files, &platform);
    platform.think();
    // Not changed while the AI went through the goals: at the runtime's next tick.
    assert!(platform.keys(ZOMBIE).contains(&"test:broken".to_owned()));
    session.tick();
    let lines = platform.lines();
    assert!(
        lines
            .iter()
            .any(|l| l.contains("goal test:broken's tick") && l.contains("boom")),
        "{lines:?}"
    );
    assert!(
        lines
            .iter()
            .any(|l| l.contains("goal test:broken was taken off its mob because its tick failed")),
        "{lines:?}"
    );
    assert!(!platform.keys(ZOMBIE).contains(&"test:broken".to_owned()));
    assert!(platform.keys(ZOMBIE).contains(&"test:fine".to_owned()));
    tick(&session, &platform);
    tick(&session, &platform);
    let lines = platform.lines();
    assert_eq!(lines, ["fine", "fine"]);
    assert!(
        !lines.iter().any(|l| l.contains("boom")),
        "never called again: {lines:?}"
    );
}

#[test]
fn changes_from_inside_a_goals_callbacks_wait_for_the_next_tick() {
    let files = Files::new(&format!(
        r#"{PRELUDE}
zombie:add_goal("once", {{
  priority = 1,
  start = function(mob)
    print("start once")
    print(mob:remove_goal("test:once"), mob:remove_goal("random_stroll"))
    print(mob:add_goal("next", {{ priority = 1, start = function() print("start next") end }}))
    print(mob:clear_goals({{ keep = {{ "test:next", "test:once" }} }}))
  end,
}})
"#
    ));
    let platform = Fake::with_mobs();
    let session = start(&files, &platform);
    for _ in 0..3 {
        tick(&session, &platform);
    }
    assert_eq!(platform.keys(ZOMBIE), ["test:next"]);
    assert_eq!(
        platform.lines(),
        ["start once", "true true", "true", "true", "start next"]
    );
}

#[test]
fn goals_go_with_their_scope_and_their_mob() {
    let files = Files::new(&format!(
        r#"{PRELUDE}
zombie:add_goal("guard", {{ priority = 1, tick = function() print("v1") end, stop = function() print("v1 stop") end }})
pig:add_goal("guard", {{ priority = 1 }})
"#
    ));
    let platform = Fake::with_mobs();
    let mut session = start(&files, &platform);
    tick(&session, &platform);
    assert_eq!(platform.lines(), ["v1"]);
    files.texts.borrow_mut().insert(
        "modules/t/init.lua".into(),
        format!(
            r#"{PRELUDE}
zombie:add_goal("guard_v2", {{ priority = 1, tick = function() print("v2") end }})
"#
        ),
    );
    session.reload(&["modules/t/init.lua".to_owned()]);
    tick(&session, &platform);
    let ours = |mob| -> Vec<String> {
        platform
            .keys(mob)
            .into_iter()
            .filter(|k| k.starts_with("test:"))
            .collect()
    };
    assert_eq!(ours(ZOMBIE), ["test:guard_v2"]);
    assert!(ours(PIG).is_empty());
    // The old scope is never called again: not even its stop, as its goal comes off.
    assert_eq!(platform.lines(), ["v2"]);
    platform.mobs.borrow_mut().remove(&ZOMBIE.0);
    for _ in 0..netherforge_runtime::mob::SWEEP_TICKS {
        session.tick();
    }
    assert!(platform.lines().is_empty());
}

#[test]
fn a_walk_ends_with_path_end() {
    let files = Files::new(&format!(
        r#"{PRELUDE}
zombie:on("path_end", function(e) print("path_end", e.entity == zombie, e.reached) end)
pig:on("path_end", function(e) print("pig path_end", e.reached) end)
print(zombie:move_to(vector.create(10, 64, 0)), zombie:has_path(), zombie:path_target().x)
print(zombie:move_to(vector.create(500, 64, 0)), zombie:has_path())
print(zombie:move_to(vector.create(10, 64, 0)))
print(pig:move_to(zombie, {{ speed = 1.5 }}))
try(zombie.move_to, zombie, vector.create(1, 64, 0), {{ speed = 0 }})
print(zombie:move_to(zombie))
"#
    ));
    let platform = Fake::with_mobs();
    let session = start(&files, &platform);
    assert_eq!(
        platform.lines(),
        [
            "true true 10.5",
            "false true",
            "true",
            "true",
            "false options.speed must be more than 0, not 0",
            "false",
        ]
    );
    // It walks there: the path ends, near enough.
    platform.paths.borrow_mut().remove(&ZOMBIE.0);
    platform.mobs.borrow_mut().get_mut(&ZOMBIE.0).unwrap().1 = Vec3(9.5, 64.0, 0.5);
    tick(&session, &platform);
    assert_eq!(platform.lines(), ["path_end true true"]);
    // The pig follows it, finding a new path every 10 ticks to where it is now.
    for _ in 0..10 {
        tick(&session, &platform);
    }
    assert_eq!(platform.paths.borrow()[&PIG.0], Vec3(9.5, 64.0, 0.5));
    // Its AI sends it elsewhere: the walk isn't ours any more.
    platform
        .paths
        .borrow_mut()
        .insert(PIG.0, Vec3(40.5, 64.0, 0.5));
    tick(&session, &platform);
    assert_eq!(platform.lines(), ["pig path_end false"]);
}
