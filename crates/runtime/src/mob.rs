//! Mobs' walks and goals (Kotlin's `MobPaths` and `MobGoals`): what `mob:move_to` started, watched each tick for
//! its end, and the goals scripts see (`mob:goals()`, `remove_goal`, `clear_goals`) and write in Lua
//! (`mob:add_goal`), which the mob's own AI calls through the Platform's `GoalCallbacks`.
//!
//! **Walks.** The server has no event for a path finishing, so a walk is over when the mob is no longer on a path
//! (reached when it stopped within `PATH_REACH` of its target), or when it's on a path that isn't ours any more
//! (its AI sent it elsewhere). A walk after an entity finds a new path to wherever the entity is every
//! `REPATH_TICKS` ticks. Walks aren't saved: one whose mob goes is forgotten without `path_end`.
//!
//! **Goals.** A Lua goal belongs to the scope whose code added it, like a subscription: when that scope stops its
//! goals come off their mobs, so the AI never calls into a scope that's gone. A callback runs as its scope's code
//! with its scope's budget; one that fails is reported with its line and its goal taken off. The AI calls a goal
//! while the server goes through the mob's goals, which changing them then would break, so a change made from
//! inside a callback waits for the runtime's next tick (which comes before the mob's next AI tick). Goals aren't
//! saved: they go when their mob unloads, dies or is removed.

use std::cell::{Cell, RefCell};
use std::collections::{HashMap, VecDeque};
use std::rc::{Rc, Weak};

use indexmap::IndexMap;
use mlua::{Function, Value};
use netherforge_api::codec::Codec;
use netherforge_api::{codecs, handles, shapes};
use netherforge_format::math::Vec3;
use netherforge_luau::ScopeId;
use netherforge_platform::{GoalCallbacks, GoalControl, GoalInfo, Platform, Uuid};

use crate::Target;
use crate::centity::paths::{PATH_REACH, REPATH_TICKS};
use crate::session::Service;
use crate::{Room, Scripts};

/// How far a path's end may be from ours and still be ours (the game ends paths at block centres).
const SAME_END: f64 = 0.75;

/// How often goals of mobs that went without a word are looked for.
pub const SWEEP_TICKS: u64 = 100;

#[derive(Clone, Copy, Debug)]
enum WalkTarget {
    Place(Vec3),
    Follow(Uuid),
}

struct Walk {
    target: WalkTarget,
    speed: f64,
    started: u64,
    end: Option<Vec3>,
}

/// The functions a Lua goal was given.
#[derive(Default)]
pub struct Callbacks {
    pub should_start: Option<Function>,
    pub should_continue: Option<Function>,
    pub start: Option<Function>,
    pub tick: Option<Function>,
    pub stop: Option<Function>,
}

/// One goal a script added to one mob.
struct LuaGoal {
    scope: ScopeId,
    mob: Uuid,
    key: String,
    /// Gone once it's been dropped: the AI may still hold it until the next tick, but it does nothing.
    callbacks: RefCell<Option<Callbacks>>,
    goals: Weak<Mobs>,
}

impl LuaGoal {
    fn live(&self) -> bool {
        self.callbacks.borrow().is_some()
    }

    /// Runs `body` as the AI's call into this goal: changes to goals meanwhile wait for the next tick.
    fn inside<T>(&self, body: impl FnOnce(&Mobs) -> T, otherwise: T) -> T {
        let Some(mobs) = self.goals.upgrade() else {
            return otherwise;
        };
        mobs.calling.set(mobs.calling.get() + 1);
        let result = body(&mobs);
        mobs.calling.set(mobs.calling.get() - 1);
        result
    }

    fn callback(&self, pick: impl Fn(&Callbacks) -> Option<&Function>) -> Option<Function> {
        self.callbacks
            .borrow()
            .as_ref()
            .and_then(|c| pick(c).cloned())
    }

    /// A yes-or-no callback's answer; no callback is yes.
    fn ask(&self, mobs: &Mobs, f: Option<Function>, name: &str) -> bool {
        if !self.live() {
            return false;
        }
        let Some(f) = f else {
            return true;
        };
        // Truthy is yes, as Lua counts it: `1` is, `nil` isn't.
        !matches!(
            self.call(mobs, &f, name),
            None | Some(Value::Nil) | Some(Value::Boolean(false))
        )
    }

    fn run(&self, mobs: &Mobs, f: Option<Function>, name: &str) {
        if let Some(f) = f
            && self.live()
        {
            self.call(mobs, &f, name);
        }
    }

    fn call(&self, mobs: &Mobs, f: &Function, name: &str) -> Option<Value> {
        let scripts = mobs.scripts.borrow().clone()?;
        // Each callback is given the mob, as `this`.
        let mob = netherforge_api::handle::push_any(
            scripts.host().lua(),
            &handles::Handle::Mob(handles::Mob {
                id: crate::events::uuid_text(&self.mob),
            }),
        )
        .ok()?;
        match scripts.call_as("goal", self.scope, f, mob) {
            Ok(value) => Some(value),
            Err(failure) => {
                if self.live() {
                    scripts.handler_failed(
                        self.scope,
                        &format!("goal {}'s {name}", self.key),
                        failure,
                    );
                    let label = scripts
                        .host()
                        .scope_name(self.scope)
                        .unwrap_or_else(|| "script".into());
                    mobs.platform.log().warn(format!(
                        "{label}: goal {} was taken off its mob because its {name} failed",
                        self.key
                    ));
                    if let Some(goal) = mobs.find(self) {
                        mobs.drop_goal(&goal);
                    }
                }
                None
            }
        }
    }
}

/// The Platform's view of a Lua goal: the goal it calls, which lives as long as the server holds it.
struct GoalHook(Rc<LuaGoal>);

impl GoalCallbacks for GoalHook {
    fn should_start(&self) -> bool {
        let goal = &self.0;
        goal.inside(
            |mobs| {
                goal.ask(
                    mobs,
                    goal.callback(|c| c.should_start.as_ref()),
                    "should_start",
                )
            },
            false,
        )
    }

    fn should_continue(&self) -> bool {
        let goal = &self.0;
        goal.inside(
            |mobs| {
                let own = goal.callback(|c| c.should_continue.as_ref());
                if own.is_some() {
                    goal.ask(mobs, own, "should_continue")
                } else {
                    goal.ask(
                        mobs,
                        goal.callback(|c| c.should_start.as_ref()),
                        "should_start",
                    )
                }
            },
            false,
        )
    }

    fn start(&self) {
        let goal = &self.0;
        goal.inside(
            |mobs| goal.run(mobs, goal.callback(|c| c.start.as_ref()), "start"),
            (),
        );
    }

    fn tick(&self) {
        let goal = &self.0;
        goal.inside(
            |mobs| goal.run(mobs, goal.callback(|c| c.tick.as_ref()), "tick"),
            (),
        );
    }

    fn stop(&self) {
        let goal = &self.0;
        goal.inside(
            |mobs| goal.run(mobs, goal.callback(|c| c.stop.as_ref()), "stop"),
            (),
        );
    }
}

type Change = Box<dyn FnOnce(&Mobs)>;

/// One mob's Lua goals by key, each with the hook the server was given for it.
type MobGoals = IndexMap<String, (Rc<LuaGoal>, Rc<GoalHook>)>;

/// Every walk scripts started and every goal they added, for one session.
pub struct Mobs {
    platform: Rc<dyn Platform>,
    pub scripts: RefCell<Option<Scripts>>,
    walks: RefCell<IndexMap<Uuid, Walk>>,
    /// Each mob's Lua goals, by key, and the hook the server was given for each.
    goals: RefCell<HashMap<Uuid, MobGoals>>,
    /// How many goal callbacks are running now: while any is, changes to goals wait for the tick.
    calling: Cell<u32>,
    pending: RefCell<VecDeque<Change>>,
    ticks: Cell<u64>,
    me: Weak<Mobs>,
    /// A walk ended: the mob and whether it got there.
    ended: RefCell<Vec<(Uuid, bool)>>,
}

impl Mobs {
    pub fn new(platform: Rc<dyn Platform>) -> Rc<Mobs> {
        Rc::new_cyclic(|me| Mobs {
            platform,
            scripts: RefCell::default(),
            walks: RefCell::default(),
            goals: RefCell::default(),
            calling: Cell::new(0),
            pending: RefCell::default(),
            ticks: Cell::new(0),
            me: me.clone(),
            ended: RefCell::default(),
        })
    }

    fn change(&self, change: Change) {
        if self.calling.get() > 0 {
            self.pending.borrow_mut().push_back(change);
        } else {
            change(self);
        }
    }

    // ---- walks

    /// Sends `mob` to `position` in its own world. False when there's no path.
    pub fn move_to(&self, mob: Uuid, position: Vec3, speed: f64) -> bool {
        self.start_walk(mob, WalkTarget::Place(position), position, speed)
    }

    /// Sends `mob` after `entity`, which the caller has checked is in the same world. False when there's no path.
    pub fn follow(&self, mob: Uuid, entity: Uuid, speed: f64) -> bool {
        let Some(at) = self.platform.world_entities().info(entity) else {
            return false;
        };
        let to = Vec3(at.location.x, at.location.y, at.location.z);
        self.start_walk(mob, WalkTarget::Follow(entity), to, speed)
    }

    fn start_walk(&self, mob: Uuid, target: WalkTarget, to: Vec3, speed: f64) -> bool {
        self.walks.borrow_mut().shift_remove(&mob);
        let ops = self.platform.pathfinding();
        if !ops.move_to(mob, to, speed) {
            return false;
        }
        let end = ops.path_end(mob);
        self.walks.borrow_mut().insert(
            mob,
            Walk {
                target,
                speed,
                started: self.ticks.get(),
                end,
            },
        );
        true
    }

    /// Stops `mob` walking, whoever sent it, and forgets its walk without an event.
    pub fn stop_walk(&self, mob: Uuid) -> bool {
        self.walks.borrow_mut().shift_remove(&mob);
        self.platform.pathfinding().stop(mob)
    }

    fn walks_pass(&self) {
        let ops = self.platform.pathfinding();
        let entities = self.platform.world_entities();
        let mobs: Vec<Uuid> = self.walks.borrow().keys().copied().collect();
        let ticks = self.ticks.get();
        for mob in mobs {
            let Some((target, speed, started, ours)) = self
                .walks
                .borrow()
                .get(&mob)
                .map(|w| (w.target, w.speed, w.started, w.end))
            else {
                continue;
            };
            let Some(here) = entities.info(mob).map(|i| i.location) else {
                self.walks.borrow_mut().shift_remove(&mob);
                continue;
            };
            let goal = match target {
                WalkTarget::Place(position) => Some(position),
                WalkTarget::Follow(entity) => entities
                    .info(entity)
                    .map(|i| i.location)
                    .filter(|l| l.world == here.world)
                    .map(|l| Vec3(l.x, l.y, l.z)),
            };
            let Some(goal) = goal else {
                ops.stop(mob);
                self.end_walk(mob, false);
                continue;
            };
            if !ops.has_path(mob) {
                let at = Vec3(here.x, here.y, here.z);
                self.end_walk(mob, (at - goal).length() <= PATH_REACH);
                continue;
            }
            let end = ops.path_end(mob);
            if let (Some(ours), Some(end)) = (ours, end)
                && (end - ours).length() > SAME_END
            {
                self.end_walk(mob, false);
                continue;
            }
            if matches!(target, WalkTarget::Follow(_))
                && (ticks - started).is_multiple_of(REPATH_TICKS as u64)
            {
                if !ops.move_to(mob, goal, speed) {
                    self.end_walk(mob, false);
                    continue;
                }
                if let Some(walk) = self.walks.borrow_mut().get_mut(&mob) {
                    walk.end = ops.path_end(mob);
                }
            }
        }
    }

    fn end_walk(&self, mob: Uuid, reached: bool) {
        self.walks.borrow_mut().shift_remove(&mob);
        self.ended.borrow_mut().push((mob, reached));
    }

    // ---- goals

    /// Every goal `mob` has, or none when it isn't a mob that's there.
    pub fn goals(&self, mob: Uuid) -> Option<Vec<GoalInfo>> {
        self.platform.mob_goals().goals(mob)
    }

    /// Adds a Lua goal for `scope` to `mob`, replacing any with `key`. False when it isn't a mob that's there.
    /// From inside a callback, it's added at the next tick.
    pub fn add(
        &self,
        scope: ScopeId,
        mob: Uuid,
        key: String,
        priority: i32,
        controls: Vec<GoalControl>,
        callbacks: Callbacks,
    ) -> bool {
        let live = self
            .scripts
            .borrow()
            .as_ref()
            .is_some_and(|s| s.live(scope));
        if !live {
            return false;
        }
        let goal = Rc::new(LuaGoal {
            scope,
            mob,
            key,
            callbacks: RefCell::new(Some(callbacks)),
            goals: self.me.clone(),
        });
        if self.calling.get() > 0 {
            if self.platform.mob_goals().goals(mob).is_none() {
                return false;
            }
            self.pending
                .borrow_mut()
                .push_back(Box::new(move |mobs: &Mobs| {
                    mobs.attach(goal, priority, controls);
                }));
            return true;
        }
        self.attach(goal, priority, controls)
    }

    fn attach(&self, goal: Rc<LuaGoal>, priority: i32, controls: Vec<GoalControl>) -> bool {
        let live = goal.live()
            && self
                .scripts
                .borrow()
                .as_ref()
                .is_some_and(|s| s.live(goal.scope));
        if !live {
            goal.callbacks.borrow_mut().take();
            return false;
        }
        let replaced = self
            .goals
            .borrow()
            .get(&goal.mob)
            .and_then(|g| g.get(&goal.key))
            .map(|(g, _)| g.clone());
        let hook = Rc::new(GoalHook(goal.clone()));
        // The goal it replaces stops (its `stop` runs) as it's taken off, before it's forgotten.
        if !self.platform.mob_goals().add(
            goal.mob,
            goal.key.clone(),
            priority,
            controls,
            hook.clone(),
        ) {
            goal.callbacks.borrow_mut().take();
            return false;
        }
        if let Some(replaced) = replaced {
            self.release(&replaced);
        }
        self.goals
            .borrow_mut()
            .entry(goal.mob)
            .or_default()
            .insert(goal.key.clone(), (goal, hook));
        true
    }

    /// Takes every goal with `key` off `mob`. Whether it had one; from inside a callback, whether it has one now.
    pub fn remove(&self, mob: Uuid, key: String) -> bool {
        if self.calling.get() > 0 {
            let has = self
                .platform
                .mob_goals()
                .goals(mob)
                .is_some_and(|g| g.iter().any(|g| g.key == key));
            self.pending
                .borrow_mut()
                .push_back(Box::new(move |mobs: &Mobs| {
                    mobs.remove(mob, key);
                }));
            return has;
        }
        let Some(removed) = self.platform.mob_goals().remove(mob, key) else {
            return false;
        };
        self.forget(mob, &removed);
        !removed.is_empty()
    }

    /// Takes every goal off `mob` but those with a key in `keep`. False when it isn't a mob that's there.
    pub fn clear(&self, mob: Uuid, keep: Vec<String>) -> bool {
        if self.calling.get() > 0 {
            let is_mob = self.platform.mob_goals().goals(mob).is_some();
            if is_mob {
                self.pending
                    .borrow_mut()
                    .push_back(Box::new(move |mobs: &Mobs| {
                        mobs.clear(mob, keep);
                    }));
            }
            return is_mob;
        }
        let Some(removed) = self.platform.mob_goals().clear(mob, keep) else {
            return false;
        };
        self.forget(mob, &removed);
        true
    }

    /// The Lua goals with these keys were taken off `mob`: their functions are let go of.
    fn forget(&self, mob: Uuid, keys: &[String]) {
        let goals: Vec<Rc<LuaGoal>> = {
            let all = self.goals.borrow();
            let Some(own) = all.get(&mob) else {
                return;
            };
            keys.iter()
                .filter_map(|k| own.get(k).map(|(g, _)| g.clone()))
                .collect()
        };
        for goal in goals {
            self.release(&goal);
        }
    }

    fn find(&self, goal: &LuaGoal) -> Option<Rc<LuaGoal>> {
        self.goals
            .borrow()
            .get(&goal.mob)
            .and_then(|g| g.get(&goal.key))
            .filter(|(g, _)| std::ptr::eq(Rc::as_ptr(g), goal))
            .map(|(g, _)| g.clone())
    }

    /// Takes `goal` off its mob (at the next tick, from inside a callback) and lets go of its functions.
    fn drop_goal(&self, goal: &Rc<LuaGoal>) {
        if !goal.live() {
            return;
        }
        let hook = self
            .goals
            .borrow()
            .get(&goal.mob)
            .and_then(|g| g.get(&goal.key))
            .filter(|(g, _)| Rc::ptr_eq(g, goal))
            .map(|(_, h)| h.clone());
        self.release(goal);
        if let Some(hook) = hook {
            let mob = goal.mob;
            self.change(Box::new(move |mobs: &Mobs| {
                mobs.platform.mob_goals().remove_with_goal(mob, hook);
            }));
        }
    }

    /// Forgets `goal` and lets go of its functions; it does nothing from now on.
    fn release(&self, goal: &Rc<LuaGoal>) {
        if goal.callbacks.borrow_mut().take().is_none() {
            return;
        }
        let mut all = self.goals.borrow_mut();
        if let Some(own) = all.get_mut(&goal.mob) {
            if own.get(&goal.key).is_some_and(|(g, _)| Rc::ptr_eq(g, goal)) {
                own.shift_remove(&goal.key);
            }
            if own.is_empty() {
                all.remove(&goal.mob);
            }
        }
    }

    /// `mob` died, was removed or unloaded: it has none of its Lua goals any more, and its walk is forgotten.
    pub fn mob_gone(&self, mob: Uuid) {
        let goals: Vec<Rc<LuaGoal>> = self
            .goals
            .borrow()
            .get(&mob)
            .map(|g| g.values().map(|(g, _)| g.clone()).collect())
            .unwrap_or_default();
        for goal in goals {
            self.release(&goal);
        }
    }

    /// Scopes stopped: every goal they added comes off its mob.
    pub fn scopes_released(&self, scopes: &[ScopeId]) {
        let owned: Vec<Rc<LuaGoal>> = self
            .goals
            .borrow()
            .values()
            .flat_map(|g| g.values().map(|(g, _)| g.clone()))
            .filter(|g| scopes.contains(&g.scope))
            .collect();
        for goal in owned {
            self.drop_goal(&goal);
        }
    }

    /// How many goals `scope` has on mobs now.
    pub fn count_owned(&self, scope: ScopeId) -> usize {
        self.goals
            .borrow()
            .values()
            .map(|g| g.values().filter(|(g, _)| g.scope == scope).count())
            .sum()
    }

    /// One tick: the changes callbacks made, the walks' ends, now and then the goals of mobs that went unheard.
    pub fn pass(&self) -> Vec<(Uuid, bool)> {
        self.ticks.set(self.ticks.get() + 1);
        loop {
            let next = self.pending.borrow_mut().pop_front();
            match next {
                Some(change) => change(self),
                None => break,
            }
        }
        if self.ticks.get().is_multiple_of(SWEEP_TICKS) {
            let mobs: Vec<Uuid> = self.goals.borrow().keys().copied().collect();
            for mob in mobs {
                if self.platform.world_entities().info(mob).is_none() {
                    self.mob_gone(mob);
                }
            }
        }
        if !self.walks.borrow().is_empty() {
            self.walks_pass();
        }
        std::mem::take(&mut *self.ended.borrow_mut())
    }

    /// Forgets everything as the session ends: the mobs walk on, unwatched.
    pub fn stop(&self) {
        self.pending.borrow_mut().clear();
        self.walks.borrow_mut().clear();
        let all: Vec<Rc<LuaGoal>> = self
            .goals
            .borrow()
            .values()
            .flat_map(|g| g.values().map(|(g, _)| g.clone()))
            .collect();
        for goal in all {
            goal.callbacks.borrow_mut().take();
        }
        self.goals.borrow_mut().clear();
        self.scripts.borrow_mut().take();
    }
}

/// Walks and goals as a session's service.
pub struct MobsService {
    pub mobs: Rc<Mobs>,
}

impl Service for MobsService {
    fn costs(&self, scope: netherforge_luau::ScopeId) -> Vec<(&'static str, usize)> {
        vec![("goals", self.mobs.count_owned(scope))]
    }

    fn name(&self) -> &'static str {
        "mobs"
    }

    fn start(&self, room: &Room, _project: &crate::session::Define) {
        *self.mobs.scripts.borrow_mut() = Some(room.scripts().clone());
    }

    fn tick(&self, room: &Room, _platform: &dyn Platform) {
        let ended = self.mobs.pass();
        if ended.is_empty() {
            return;
        }
        let scripts = room.scripts();
        let lua = scripts.host().lua().clone();
        for (mob, reached) in ended {
            let handle = handles::Mob {
                id: crate::events::uuid_text(&mob),
            };
            let Ok(target) =
                netherforge_api::handle::push_any(&lua, &handles::Handle::Mob(handle.clone()))
            else {
                continue;
            };
            let target = Target::Handle(target);
            if !scripts.listening(&target, "path_end") {
                continue;
            }
            let payload = shapes::EntityPathEndEvent {
                entity: handle,
                reached,
            };
            if let Ok(Value::Table(fields)) = codecs::EntityPathEndEvent.push(&lua, payload) {
                scripts.raise(&target, "path_end", fields);
            }
        }
    }

    fn stop(&self, _room: &Room, _platform: &dyn Platform) {
        self.mobs.stop();
    }

    fn scope_released(&self, scopes: &[ScopeId]) {
        self.mobs.scopes_released(scopes);
    }

    fn entity_gone(&self, entity: Uuid) {
        self.mobs.mob_gone(entity);
    }

    fn entities_unloading(&self, entities: &std::collections::HashSet<Uuid>) {
        for id in entities {
            self.mobs.mob_gone(*id);
        }
    }
}
