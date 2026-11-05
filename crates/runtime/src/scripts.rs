//! Scripts: the scopes of a room's Luau state, and what they make that runs
//! later: subscriptions to events, timers and tasks. The prelude's `events`,
//! `tasks` and `scopes` modules and the Kotlin runtime's `Scripts`, in Rust.
//! See the rust-runtime skill, "Scripts".
//!
//! - A **subscription** is filed under its target (a handle, or `nf`) and its
//!   scope, and lives as long as both. Handlers run synchronously, in
//!   registration order, each in its own scope's frame. One that errors is
//!   reported and the rest still run; after [`MAX_ERRORS`] in a row it's
//!   cancelled.
//! - A **timer** (`nf.after`, `nf.every`) runs at the start of a tick.
//! - A **task** (`nf.task`) is a coroutine, started at once and resumed by its
//!   waits (`nf.wait`, `nf.wait_until`, `nf.wait_for`). Only the task's own
//!   code may wait.

use std::cell::RefCell;
use std::collections::{BTreeMap, BTreeSet, HashMap};
use std::rc::Rc;

use mlua::thread::ThreadStatus;
use mlua::{AnyUserData, Function, Lua, MultiValue, Table, Thread, Value};
use netherforge_api::api::{NfApi, SubscriptionApi, TaskApi};
use netherforge_api::events::{self, EventType};
use netherforge_api::unported::Unported;
use netherforge_api::{ApiError, ApiResult, Caller, Waiting, handles, shapes, unions};
use netherforge_luau::{Failure, Host, ScopeId, WeakHost};

/// Failures in a row after which a subscription or a repeating timer is
/// cancelled.
pub const MAX_ERRORS: u32 = 20;

/// Something a script made failed: reported, and the script runs on (unless
/// it was its body).
#[derive(Clone, Debug, PartialEq)]
pub struct Report {
    pub scope: ScopeId,
    /// What failed: `body`, `tick handler`, `shop:bought handler on a Centity`,
    /// `timer`, `task`.
    pub what: String,
    pub failure: Failure,
    /// It was cancelled for failing [`MAX_ERRORS`] times in a row.
    pub gave_up: bool,
}

/// A line a script logged (`log`, `print`).
#[derive(Clone, Debug, PartialEq)]
pub struct Log {
    pub scope: ScopeId,
    pub line: String,
    pub location: Option<netherforge_luau::Location>,
}

/// What an event is raised on: a handle (by its userdata), or `nf`.
#[derive(Clone)]
pub enum Target {
    Nf,
    Handle(AnyUserData),
}

/// The key a target's listeners are filed under.
type TargetKey = Option<usize>;

fn key_of(target: &Target) -> TargetKey {
    match target {
        Target::Nf => None,
        Target::Handle(u) => Some(u.to_pointer() as usize),
    }
}

fn class_of(target: &Target) -> &'static str {
    match target {
        Target::Nf => "nf",
        Target::Handle(u) => netherforge_api::handle::class_of(u).unwrap_or("userdata"),
    }
}

struct ScopeInfo {
    budget: u64,
    /// The package whose code it runs (its namespace), or none for the project's own.
    namespace: Option<String>,
    disabled: bool,
    subs: BTreeSet<u64>,
    timers: BTreeSet<u64>,
    tasks: BTreeSet<u64>,
}

struct Sub {
    scope: ScopeId,
    key: TargetKey,
    /// Keeps the handle (and so its one userdata) alive while it's listened to.
    _target: Option<AnyUserData>,
    class: &'static str,
    event: String,
    handler: Function,
    once: bool,
    /// A `tick` handler's rate and phase: it runs when (tick + phase) % rate == 0.
    every: Option<(u64, u64)>,
    errors: u32,
}

enum TimerKind {
    Once(Function),
    Every(Function, u64),
    /// Wakes a task's `nf.wait`.
    Wake {
        task: u64,
        token: u64,
    },
}

struct Timer {
    scope: ScopeId,
    due: u64,
    kind: TimerKind,
    errors: u32,
}

/// What a waiting task waits for.
enum Wait {
    /// A timer's token.
    Timer(u64),
    /// `nf.wait_until`: the predicate, checked every tick, and its deadline.
    Until {
        predicate: Function,
        deadline: Option<u64>,
    },
    /// `nf.wait_for`: a subscription that wakes it with the event.
    Event { sub: u64, filter: Option<Function> },
    /// An `async` function's waiting form: the wait (`pending`'s id) whose `value, err` wakes it.
    Async(u64),
}

struct Task {
    scope: ScopeId,
    thread: Thread,
    waiting: Option<Wait>,
    /// The host's depth while its own code runs: a wait from deeper (an event
    /// handler the task set off) isn't the task's own.
    depth: Option<usize>,
}

#[derive(Default)]
struct State {
    tick: u64,
    next_id: u64,
    scopes: HashMap<ScopeId, ScopeInfo>,
    subs: HashMap<u64, Sub>,
    listeners: HashMap<(TargetKey, String), Vec<u64>>,
    timers: HashMap<u64, Timer>,
    due: BTreeMap<(u64, u64), ()>,
    tasks: HashMap<u64, Task>,
    reports: Vec<Report>,
    logs: Vec<Log>,
    /// Ids of waits handed to running tasks, which they're woken with.
    next_token: u64,
}

impl State {
    fn id(&mut self) -> u64 {
        self.next_id += 1;
        self.next_id
    }
}

struct Fns {
    resume: Function,
    /// The private key an event's view keeps its state under.
    private: Table,
    cancel: Function,
    stop: Function,
    uncancel: Function,
    newindex: Function,
}

/// Whether the thing a handle names is still there (a centity, a window): a service's answer for the classes
/// it keeps, none for one it doesn't.
pub type Liveness = Box<dyn Fn(&netherforge_api::handles::Handle) -> Option<bool>>;

struct Inner {
    /// Each service's liveness check (Kotlin's `Liveness`): a handle to something gone gets a subscription
    /// that never runs.
    liveness: RefCell<Vec<Liveness>>,
    /// Weak: the room owns the host, and the host's state holds these scripts
    /// (the installed API), so a strong one would keep both alive for good.
    host: WeakHost,
    state: RefCell<State>,
    fns: Fns,
}

/// One room's scripts. Cheap to clone: a clone is the same scripts.
#[derive(Clone)]
pub struct Scripts(Rc<Inner>);

fn err(message: impl Into<String>) -> ApiError {
    ApiError(message.into())
}

fn lua_err(e: mlua::Error) -> ApiError {
    ApiError(e.to_string())
}

impl Scripts {
    pub fn new(host: Host) -> mlua::Result<Scripts> {
        let lua = host.lua();
        let coroutine: Table = lua.globals().get("coroutine")?;
        let private = lua.create_table()?;
        let fns = Fns {
            resume: coroutine.get("resume")?,
            cancel: event_method(&host, &private, Some(true))?,
            uncancel: event_method(&host, &private, Some(false))?,
            stop: event_method(&host, &private, None)?,
            newindex: event_newindex(&host, &private)?,
            private,
        };
        Ok(Scripts(Rc::new(Inner {
            liveness: RefCell::new(Vec::new()),
            host: host.downgrade(),
            state: RefCell::new(State::default()),
            fns,
        })))
    }

    /// Adds a service's answer to whether a handle's thing is still there.
    pub fn add_liveness(&self, check: Liveness) {
        self.0.liveness.borrow_mut().push(check);
    }

    /// Whether the thing `target` names is still there (anything no service keeps is).
    pub fn alive(&self, target: &Target) -> bool {
        let Target::Handle(u) = target else {
            return true;
        };
        let Some(handle) = netherforge_api::handle::read_any(u) else {
            return true;
        };
        self.0
            .liveness
            .borrow()
            .iter()
            .find_map(|check| check(&handle))
            .unwrap_or(true)
    }

    /// The host these scripts run in. Its room keeps it for as long as they run.
    pub fn host(&self) -> Host {
        self.0.host.upgrade().expect("the room keeps its host")
    }

    pub fn tick_count(&self) -> u64 {
        self.0.state.borrow().tick
    }

    /// Opens a scope of the project's own code whose calls get `budget` steps, with its own `nf`.
    pub fn open(&self, name: &str, memory_key: &str, budget: u64) -> mlua::Result<ScopeId> {
        self.open_in(name, memory_key, budget, None)
    }

    /// The package whose code `scope` runs (its namespace), or none for the project's own: what its calls are
    /// held to (`requires` in that package's `netherforge.json`).
    pub fn namespace(&self, scope: ScopeId) -> Option<String> {
        self.0
            .state
            .borrow()
            .scopes
            .get(&scope)
            .and_then(|s| s.namespace.clone())
    }

    /// Opens a scope of package `namespace`'s code (none: the project's own) whose calls get `budget` steps.
    pub fn open_in(
        &self,
        name: &str,
        memory_key: &str,
        budget: u64,
        namespace: Option<&str>,
    ) -> mlua::Result<ScopeId> {
        let host = &self.host();
        let scope = host.open_scope(name, memory_key)?;
        let lua = host.lua();
        let env = host.env(scope).expect("just opened");
        env.raw_set("nf", netherforge_api::nf_for(lua, scope)?)?;
        // A scope's own `_G` is its environment.
        env.raw_set("_G", env.clone())?;
        let scripts = Rc::downgrade(&self.0);
        let log = lua.create_function(move |lua, parts: mlua::Variadic<Value>| {
            let Some(inner) = scripts.upgrade() else {
                return Ok(());
            };
            let mut texts = Vec::with_capacity(parts.len());
            for part in parts.iter() {
                let text: String = lua.coerce_string(part.clone())?.map_or_else(
                    || match part {
                        Value::Nil => "nil".to_owned(),
                        Value::Boolean(b) => b.to_string(),
                        other => format!("{}: {:p}", other.type_name(), other.to_pointer()),
                    },
                    |s| s.to_string_lossy(),
                );
                texts.push(text);
            }
            let location = netherforge_luau::script_location(lua);
            inner.state.borrow_mut().logs.push(Log {
                scope,
                line: texts.join("\t"),
                location,
            });
            Ok(())
        })?;
        env.raw_set("log", log.clone())?;
        env.raw_set("print", log)?;
        self.0.state.borrow_mut().scopes.insert(
            scope,
            ScopeInfo {
                budget,
                namespace: namespace.map(str::to_owned),
                disabled: false,
                subs: BTreeSet::new(),
                timers: BTreeSet::new(),
                tasks: BTreeSet::new(),
            },
        );
        Ok(scope)
    }

    /// Runs a script's body in `scope`. A body that fails disables its scope:
    /// what it made goes, until its resource reloads.
    pub fn run_body(&self, scope: ScopeId, chunk: &str, source: &str) -> Result<(), Failure> {
        let budget = self.budget(scope);
        let result = self.host().run::<()>(scope, budget, chunk, source);
        if let Err(failure) = &result {
            self.report(scope, "body", failure.clone(), false);
            self.disable(scope);
        }
        result
    }

    /// Stops everything `scope` made: subscriptions, timers, tasks.
    pub fn disable(&self, scope: ScopeId) {
        let (subs, timers, tasks) = {
            let mut state = self.0.state.borrow_mut();
            let Some(info) = state.scopes.get_mut(&scope) else {
                return;
            };
            info.disabled = true;
            (
                std::mem::take(&mut info.subs),
                std::mem::take(&mut info.timers),
                std::mem::take(&mut info.tasks),
            )
        };
        for id in subs {
            self.unsubscribe(id);
        }
        for id in timers {
            self.cancel_timer(id);
        }
        for id in tasks {
            self.end_task(id);
        }
    }

    /// Closes `scope`: what it made goes, and so does its environment.
    pub fn close(&self, scope: ScopeId) {
        self.disable(scope);
        self.0.state.borrow_mut().scopes.remove(&scope);
        self.host().close_scope(scope);
    }

    /// The thing a handle stands for is gone: every subscription on it goes.
    pub fn drop_target(&self, target: &Target) {
        let key = key_of(target);
        let ids: Vec<u64> = {
            let state = self.0.state.borrow();
            state
                .listeners
                .iter()
                .filter(|((k, _), _)| *k == key)
                .flat_map(|(_, ids)| ids.clone())
                .collect()
        };
        for id in ids {
            self.unsubscribe(id);
        }
    }

    /// What has failed since the last time, oldest first.
    pub fn take_reports(&self) -> Vec<Report> {
        std::mem::take(&mut self.0.state.borrow_mut().reports)
    }

    fn report(&self, scope: ScopeId, what: impl Into<String>, failure: Failure, gave_up: bool) {
        self.0.state.borrow_mut().reports.push(Report {
            scope,
            what: what.into(),
            failure,
            gave_up,
        });
    }

    fn budget(&self, scope: ScopeId) -> u64 {
        self.0
            .state
            .borrow()
            .scopes
            .get(&scope)
            .map_or(0, |s| s.budget)
    }

    /// Whether `scope` is open and its script hasn't failed.
    pub fn is_live(&self, scope: ScopeId) -> bool {
        self.live(scope)
    }

    /// `scope`'s body failed (a module's, while it was required): reported,
    /// and what it made goes.
    pub fn fail_body(&self, scope: ScopeId, failure: Failure) {
        self.report(scope, "body", failure, false);
        self.disable(scope);
    }

    /// What scripts have logged since the last time: the scope, the line, and
    /// where it was logged from.
    pub fn take_logs(&self) -> Vec<Log> {
        std::mem::take(&mut self.0.state.borrow_mut().logs)
    }

    fn live(&self, scope: ScopeId) -> bool {
        self.0
            .state
            .borrow()
            .scopes
            .get(&scope)
            .is_some_and(|s| !s.disabled)
    }

    // ---- the tick

    /// One server tick: timers that are due, waits that are over, then `tick`
    /// handlers on `nf`.
    pub fn tick(&self) {
        let now = {
            let mut state = self.0.state.borrow_mut();
            state.tick += 1;
            state.tick
        };
        self.deliver_async();
        self.run_timers(now);
        self.check_wait_untils(now);
        let host = self.host();
        let lua = host.lua();
        if let Ok(payload) = lua.create_table() {
            self.raise(&Target::Nf, "tick", payload);
        }
    }

    fn run_timers(&self, now: u64) {
        loop {
            let next = {
                let mut state = self.0.state.borrow_mut();
                let Some((&(due, id), _)) = state.due.iter().next() else {
                    break;
                };
                if due > now {
                    break;
                }
                state.due.remove(&(due, id));
                id
            };
            self.run_timer(next, now);
        }
    }

    fn run_timer(&self, id: u64, now: u64) {
        let (scope, kind) = {
            let mut state = self.0.state.borrow_mut();
            let Some(timer) = state.timers.get_mut(&id) else {
                return;
            };
            let scope = timer.scope;
            let kind = match &timer.kind {
                TimerKind::Once(f) => TimerKind::Once(f.clone()),
                TimerKind::Every(f, period) => TimerKind::Every(f.clone(), *period),
                TimerKind::Wake { task, token } => TimerKind::Wake {
                    task: *task,
                    token: *token,
                },
            };
            match kind {
                TimerKind::Every(_, period) => {
                    timer.due = now + period;
                    state.due.insert((now + period, id), ());
                }
                _ => {
                    state.timers.remove(&id);
                    if let Some(info) = state.scopes.get_mut(&scope) {
                        info.timers.remove(&id);
                    }
                }
            }
            (scope, kind)
        };
        match kind {
            TimerKind::Wake { task, token } => {
                self.wake(task, Wait::Timer(token), MultiValue::new())
            }
            TimerKind::Once(f) => {
                if let Err(failure) = self.call(scope, &f, ()) {
                    self.report(scope, "timer", failure, false);
                }
            }
            TimerKind::Every(f, _) => {
                let result = self.call(scope, &f, ());
                let mut state = self.0.state.borrow_mut();
                let Some(timer) = state.timers.get_mut(&id) else {
                    return;
                };
                match result {
                    Ok(()) => timer.errors = 0,
                    Err(failure) => {
                        timer.errors += 1;
                        let gave_up = timer.errors >= MAX_ERRORS;
                        drop(state);
                        if gave_up {
                            self.cancel_timer(id);
                        }
                        self.report(scope, "timer", failure, gave_up);
                    }
                }
            }
        }
    }

    fn call<A: mlua::IntoLuaMulti>(
        &self,
        scope: ScopeId,
        f: &Function,
        args: A,
    ) -> Result<(), Failure> {
        if !self.live(scope) {
            return Ok(());
        }
        let budget = self.budget(scope);
        self.host().invoke::<()>(scope, budget, f, args)
    }

    fn timer(&self, scope: ScopeId, ticks: u64, kind: TimerKind) -> u64 {
        let mut state = self.0.state.borrow_mut();
        let id = state.id();
        let due = state.tick + ticks;
        state.timers.insert(
            id,
            Timer {
                scope,
                due,
                kind,
                errors: 0,
            },
        );
        state.due.insert((due, id), ());
        if let Some(info) = state.scopes.get_mut(&scope) {
            info.timers.insert(id);
        }
        id
    }

    fn cancel_timer(&self, id: u64) -> bool {
        let mut state = self.0.state.borrow_mut();
        let Some(timer) = state.timers.remove(&id) else {
            return false;
        };
        state.due.remove(&(timer.due, id));
        if let Some(info) = state.scopes.get_mut(&timer.scope) {
            info.timers.remove(&id);
        }
        true
    }

    // ---- events

    /// Subscribes `handler` to `event` on `target`, as `scope`'s code (`:on`, `:once`).
    pub fn subscribe(
        &self,
        scope: ScopeId,
        target: Target,
        event: String,
        handler: Function,
        options: Option<shapes::EventOptions>,
        once: bool,
    ) -> ApiResult<handles::Subscription> {
        let class = class_of(&target);
        let spec = events::of(class, &event);
        if spec.is_none() && !(events::is_custom(&event) && takes_custom(class)) {
            let where_ = if class == "nf" { "nf.on" } else { class };
            let hint = if takes_custom(class) {
                "; a custom event's name has a ':' in it"
            } else if events::is_custom(&event) {
                "; only nf and Centity take custom events"
            } else {
                ""
            };
            let mut names = events::names(class);
            names.sort_unstable();
            return Err(err(format!(
                "no event \"{event}\" for {where_} (events: {}{hint})",
                names.join(", ")
            )));
        }
        let every = match options.and_then(|o| o.every) {
            None => None,
            Some(_) if !spec.is_some_and(|s| s.options.contains(&"every")) => {
                return Err(err(format!(
                    "option 'every' doesn't apply to the {event} event"
                )));
            }
            Some(n) if n < 1 => {
                return Err(err(
                    "bad field 'options.every' (a whole number of ticks, at least 1, expected)",
                ));
            }
            Some(n) => Some(n as u64),
        };
        // A handle to something already gone gets a subscription that never runs.
        if !self.alive(&target) {
            let id = self.0.state.borrow_mut().id();
            return Ok(handles::Subscription { id: id as i64 });
        }
        let mut state = self.0.state.borrow_mut();
        let id = state.id();
        let key = key_of(&target);
        let every = every.map(|rate| {
            // Phased by the thing it's on, so one centity's handlers agree and a
            // crowd of them spreads, or by its id on nf.
            let phase = key.map_or(id, |k| (k as u64) >> 4) % rate;
            (rate, phase)
        });
        let target_value = match &target {
            Target::Nf => None,
            Target::Handle(u) => Some(u.clone()),
        };
        state.subs.insert(
            id,
            Sub {
                scope,
                key,
                _target: target_value,
                class,
                event: event.clone(),
                handler,
                once,
                every,
                errors: 0,
            },
        );
        state.listeners.entry((key, event)).or_default().push(id);
        if let Some(info) = state.scopes.get_mut(&scope) {
            info.subs.insert(id);
        }
        Ok(handles::Subscription { id: id as i64 })
    }

    fn unsubscribe(&self, id: u64) -> bool {
        let mut state = self.0.state.borrow_mut();
        let Some(sub) = state.subs.remove(&id) else {
            return false;
        };
        if let Some(list) = state.listeners.get_mut(&(sub.key, sub.event.clone())) {
            list.retain(|it| *it != id);
            if list.is_empty() {
                state.listeners.remove(&(sub.key, sub.event));
            }
        }
        if let Some(info) = state.scopes.get_mut(&sub.scope) {
            info.subs.remove(&id);
        }
        true
    }

    /// Whether anything listens for `event` on `target`, so the server can
    /// skip work nobody listens to.
    pub fn listening(&self, target: &Target, event: &str) -> bool {
        self.0
            .state
            .borrow()
            .listeners
            .contains_key(&(key_of(target), event.to_owned()))
    }

    /// Raises built-in `event` on `target` with `payload` (its fields), and
    /// returns what handlers made of it: whether it ended cancelled, and the
    /// payload, whose writable fields they may have set.
    pub fn raise(&self, target: &Target, event: &str, payload: Table) -> Delivered {
        self.raise_path(&[(target.clone(), event)], payload, false)
    }

    /// Raises a built-in event along a path (Kotlin's `Scripts.emit`): each stage's event on its target in
    /// turn (a player's `player_join`, then `nf`'s), sharing one event and its payload, until a handler calls
    /// `stop()`. The first stage's event says what's cancellable and writable. A stage whose target's class
    /// has no such event (a `Player`'s `death` is `player_death`, not a `Living`'s) is left out.
    /// `precancelled` is where cancelling starts.
    pub fn raise_path(
        &self,
        path: &[(Target, &str)],
        payload: Table,
        precancelled: bool,
    ) -> Delivered {
        let Some((first_target, first_event)) = path.first() else {
            return Delivered {
                cancelled: precancelled,
                fields: payload,
            };
        };
        let spec = events::of(class_of(first_target), first_event);
        let stages: Vec<(Target, String)> = path
            .iter()
            .filter(|(target, event)| match target {
                Target::Nf => true,
                Target::Handle(_) => events::of(class_of(target), event).is_some(),
            })
            .map(|(t, e)| (t.clone(), (*e).to_owned()))
            .collect();
        self.deliver_path(&stages, payload, spec, false, precancelled)
    }

    fn deliver(
        &self,
        target: &Target,
        event: &str,
        fields: Table,
        spec: Option<&'static EventType>,
        custom: bool,
    ) -> Delivered {
        self.deliver_path(
            &[(target.clone(), event.to_owned())],
            fields,
            spec,
            custom,
            false,
        )
    }

    fn deliver_path(
        &self,
        stages: &[(Target, String)],
        fields: Table,
        spec: Option<&'static EventType>,
        custom: bool,
        precancelled: bool,
    ) -> Delivered {
        let listened: Vec<(Target, String, Vec<u64>)> = {
            let state = self.0.state.borrow();
            stages
                .iter()
                .filter_map(|(target, event)| {
                    let ids = state
                        .listeners
                        .get(&(key_of(target), event.clone()))
                        .cloned()
                        .unwrap_or_default();
                    (!ids.is_empty()).then(|| (target.clone(), event.clone(), ids))
                })
                .collect()
        };
        let Some((first_target, first_event, _)) = listened.first() else {
            return Delivered {
                cancelled: precancelled,
                fields,
            };
        };
        let cancellable = custom || spec.is_some_and(|s| s.cancellable);
        let writable: Option<&'static [&'static str]> = if custom {
            None
        } else {
            Some(spec.map_or(&[], |s| s.writable))
        };
        let payload = spec.and_then(|s| s.payload);
        let (ev, view) = match self.new_event(
            first_target,
            first_event,
            &fields,
            cancellable,
            writable,
            payload,
        ) {
            Ok(it) => it,
            Err(_) => {
                return Delivered {
                    cancelled: precancelled,
                    fields,
                };
            }
        };
        if precancelled {
            let _ = view.raw_set("cancelled", true);
        }
        for (target, event, ids) in &listened {
            let _ = view.raw_set("name", event.as_str());
            let _ = match target {
                Target::Handle(u) => view.raw_set("current", u.clone()),
                Target::Nf => view.raw_set("current", Value::Nil),
            };
            self.run_handlers(ids, &ev, &view);
            if view.raw_get::<bool>("stopped").unwrap_or(false) {
                break;
            }
        }
        Delivered {
            cancelled: view.raw_get::<bool>("cancelled").unwrap_or(false),
            fields,
        }
    }

    /// One stage's handlers, in order, on `ev`, until one stops it.
    fn run_handlers(&self, ids: &[u64], ev: &Table, view: &Table) {
        let tick = self.tick_count();
        for &id in ids {
            let (scope, handler, once, what) = {
                let state = self.0.state.borrow();
                let Some(sub) = state.subs.get(&id) else {
                    continue;
                };
                if let Some((rate, phase)) = sub.every
                    && !(tick + phase).is_multiple_of(rate)
                {
                    continue;
                }
                let on = if sub.class == "nf" {
                    String::new()
                } else {
                    format!(" on a {}", sub.class)
                };
                (
                    sub.scope,
                    sub.handler.clone(),
                    sub.once,
                    format!("{} handler{on}", sub.event),
                )
            };
            if once {
                self.unsubscribe(id);
            }
            let result = self.call(scope, &handler, ev.clone());
            match result {
                Ok(()) => {
                    if let Some(sub) = self.0.state.borrow_mut().subs.get_mut(&id) {
                        sub.errors = 0;
                    }
                }
                Err(failure) => {
                    let gave_up = {
                        let mut state = self.0.state.borrow_mut();
                        match state.subs.get_mut(&id) {
                            Some(sub) => {
                                sub.errors += 1;
                                sub.errors >= MAX_ERRORS
                            }
                            None => false,
                        }
                    };
                    if gave_up {
                        self.unsubscribe(id);
                    }
                    self.report(scope, what, failure, gave_up);
                }
            }
            if view.raw_get::<bool>("stopped").unwrap_or(false) {
                break;
            }
        }
    }

    /// An event: an empty table whose `__index` is its view (its own members:
    /// `name`, `current`, `cancelled`, `stopped`, the methods), whose `__index`
    /// is its fields, a chain the VM follows itself, so a read runs nothing.
    /// Every write finds the event empty, so `__newindex` checks each.
    fn new_event(
        &self,
        target: &Target,
        name: &str,
        fields: &Table,
        cancellable: bool,
        writable: Option<&'static [&'static str]>,
        payload: Option<&'static str>,
    ) -> mlua::Result<(Table, Table)> {
        let host = self.host();
        let lua = host.lua();
        let fns = &self.0.fns;
        let view = lua.create_table()?;
        view.raw_set("name", name)?;
        if let Target::Handle(u) = target {
            view.raw_set("current", u.clone())?;
        }
        view.raw_set("cancelled", false)?;
        view.raw_set("stopped", false)?;
        view.raw_set("cancel", fns.cancel.clone())?;
        view.raw_set("uncancel", fns.uncancel.clone())?;
        view.raw_set("stop", fns.stop.clone())?;
        let state = lua.create_table()?;
        state.raw_set("cancellable", cancellable)?;
        match writable {
            None => state.raw_set("writable", true)?,
            Some(names) => {
                let set = lua.create_table()?;
                for n in names {
                    set.raw_set(*n, true)?;
                }
                state.raw_set("writable", set)?;
            }
        }
        if let Some(payload) = payload {
            state.raw_set("payload", payload)?;
        }
        state.raw_set("fields", fields.clone())?;
        view.raw_set(fns.private.clone(), state)?;
        let view_meta = lua.create_table()?;
        view_meta.raw_set("__index", fields.clone())?;
        view.set_metatable(Some(view_meta))?;
        let ev = lua.create_table()?;
        let meta = lua.create_table()?;
        meta.raw_set("__index", view.clone())?;
        meta.raw_set("__newindex", fns.newindex.clone())?;
        meta.raw_set("__metatable", false)?;
        meta.raw_set("__type", "Event")?;
        ev.set_metatable(Some(meta))?;
        Ok((ev, view))
    }

    /// Raises custom `event` on `target` (`:emit`): whether anything heard it.
    pub fn emit_custom(&self, target: Target, event: String, payload: Value) -> ApiResult<bool> {
        if !events::is_custom(&event) {
            return Err(err(format!(
                "\"{event}\" is a built-in event name: only the server raises those; a custom event's name has a ':' in it"
            )));
        }
        if !self.listening(&target, &event) {
            return Ok(false);
        }
        let fields = match payload {
            Value::Table(t) => t,
            Value::Nil => self.host().lua().create_table().map_err(lua_err)?,
            other => {
                return Err(err(format!(
                    "bad argument 'payload' (table or nil expected, got {})",
                    netherforge_api::codec::type_name(&other)
                )));
            }
        };
        Ok(self.deliver(&target, &event, fields, None, true).cancelled)
    }

    // ---- tasks

    fn start_task(
        &self,
        scope: ScopeId,
        callback: Function,
        args: Vec<Value>,
    ) -> ApiResult<handles::Task> {
        let host = self.host();
        let lua = host.lua();
        let thread = lua.create_thread(callback).map_err(lua_err)?;
        let id = {
            let mut state = self.0.state.borrow_mut();
            let id = state.id();
            state.tasks.insert(
                id,
                Task {
                    scope,
                    thread,
                    waiting: None,
                    depth: None,
                },
            );
            if let Some(info) = state.scopes.get_mut(&scope) {
                info.tasks.insert(id);
            }
            id
        };
        self.resume(id, MultiValue::from_vec(args));
        Ok(handles::Task { id: id as i64 })
    }

    /// Runs task `id` on from where it waited (or from the start), with
    /// `values` as what its wait returns.
    fn resume(&self, id: u64, values: MultiValue) {
        let Some((scope, thread)) = self
            .0
            .state
            .borrow()
            .tasks
            .get(&id)
            .map(|t| (t.scope, t.thread.clone()))
        else {
            return;
        };
        if !self.live(scope) || thread.status() != ThreadStatus::Resumable {
            return;
        }
        let depth = self.host().depth() + 1;
        if let Some(task) = self.0.state.borrow_mut().tasks.get_mut(&id) {
            task.depth = Some(depth);
        }
        let mut args = vec![Value::Thread(thread.clone())];
        args.extend(values);
        let budget = self.budget(scope);
        let result = self.host().invoke::<MultiValue>(
            scope,
            budget,
            &self.0.fns.resume,
            MultiValue::from_vec(args),
        );
        if let Some(task) = self.0.state.borrow_mut().tasks.get_mut(&id) {
            task.depth = None;
        }
        let failure = match result {
            Err(failure) => Some(failure),
            Ok(returned) => {
                let mut returned = returned.into_iter();
                let ok = matches!(returned.next(), Some(Value::Boolean(true)));
                if !ok {
                    Some(match returned.next() {
                        Some(Value::Error(e)) => Failure::of(&e),
                        Some(other) => Failure::of_message(&other.to_string().unwrap_or_default()),
                        None => Failure::of_message("the task failed"),
                    })
                } else if thread.status() == ThreadStatus::Resumable {
                    let waiting = self
                        .0
                        .state
                        .borrow()
                        .tasks
                        .get(&id)
                        .is_some_and(|t| t.waiting.is_some());
                    (!waiting).then(|| Failure::Error {
                        message: "coroutine.yield can't pause a task: use nf.wait, nf.wait_until or nf.wait_for".into(),
                        traceback: None,
                    })
                } else {
                    None
                }
            }
        };
        let ended = failure.is_some() || thread.status() != ThreadStatus::Resumable;
        if ended {
            self.end_task(id);
        }
        if let Some(failure) = failure {
            self.report(scope, "task", failure, false);
        }
    }

    /// Ends task `id`: what it waits on goes, and it never runs again.
    fn end_task(&self, id: u64) -> bool {
        let task = {
            let mut state = self.0.state.borrow_mut();
            let Some(task) = state.tasks.remove(&id) else {
                return false;
            };
            if let Some(info) = state.scopes.get_mut(&task.scope) {
                info.tasks.remove(&id);
            }
            task
        };
        match task.waiting {
            Some(Wait::Timer(token)) => {
                let timer = self
                    .0
                    .state
                    .borrow()
                    .timers
                    .iter()
                    .find(|(_, t)| matches!(t.kind, TimerKind::Wake { token: k, .. } if k == token))
                    .map(|(id, _)| *id);
                if let Some(timer) = timer {
                    self.cancel_timer(timer);
                }
            }
            Some(Wait::Event { sub, .. }) => {
                self.unsubscribe(sub);
            }
            // Nothing is delivered now (the work itself goes on).
            Some(Wait::Async(wait)) => {
                netherforge_api::pending::cancel(self.host().lua(), wait);
            }
            _ => {}
        }
        true
    }

    /// Carries task `id` on, if it's still waiting on `wait`.
    fn wake(&self, id: u64, wait: Wait, values: MultiValue) {
        let matches = {
            let state = self.0.state.borrow();
            match (state.tasks.get(&id).and_then(|t| t.waiting.as_ref()), &wait) {
                (Some(Wait::Timer(a)), Wait::Timer(b)) => a == b,
                (Some(Wait::Event { sub: a, .. }), Wait::Event { sub: b, .. }) => a == b,
                (Some(Wait::Until { .. }), Wait::Until { .. }) => true,
                (Some(Wait::Async(a)), Wait::Async(b)) => a == b,
                _ => false,
            }
        };
        if !matches {
            return;
        }
        if let Some(task) = self.0.state.borrow_mut().tasks.get_mut(&id) {
            task.waiting = None;
        }
        self.resume(id, values);
    }

    /// Hands every async value that's ready to its callback (as its scope's code, unless the scope has
    /// stopped by then) or to the task waiting on it.
    fn deliver_async(&self) {
        let host = self.host();
        let Ok(ready) = netherforge_api::pending::deliver(host.lua()) else {
            return;
        };
        for it in ready {
            match it.callback {
                Some(callback) => {
                    let Some(scope) = it.scope.filter(|s| self.is_live(*s)) else {
                        continue;
                    };
                    let budget = self.budget(scope);
                    if let Err(failure) =
                        host.invoke::<()>(scope, budget, &callback, (it.value, it.err))
                    {
                        self.report(scope, "callback", failure, false);
                    }
                }
                None => {
                    let task = self
                        .0
                        .state
                        .borrow()
                        .tasks
                        .iter()
                        .find(|(_, t)| matches!(t.waiting, Some(Wait::Async(w)) if w == it.id))
                        .map(|(id, _)| *id);
                    if let Some(task) = task {
                        self.wake(
                            task,
                            Wait::Async(it.id),
                            MultiValue::from_vec(vec![it.value, it.err]),
                        );
                    }
                }
            }
        }
    }

    /// The task waiting form of an async function `called` runs in: the running task's own code, or an error.
    pub fn waiting_task(&self, called: &str) -> ApiResult<u64> {
        self.running_task(called)
    }

    /// Task `task` waits for async wait `wait`.
    pub fn wait_on(&self, task: u64, wait: u64) {
        if let Some(t) = self.0.state.borrow_mut().tasks.get_mut(&task) {
            t.waiting = Some(Wait::Async(wait));
        }
    }

    /// The task whose own code is running, for a wait called `what`; an error
    /// anywhere else.
    fn running_task(&self, what: &str) -> ApiResult<u64> {
        let host = self.host();
        let lua = host.lua();
        let current = lua.current_thread();
        let depth = self.host().depth();
        let state = self.0.state.borrow();
        let task = state.tasks.iter().find(|(_, t)| t.thread == current);
        match task {
            // Whether it can yield here (not in `table.sort`'s comparator) is the
            // binding's shim's to check: only Luau can tell.
            Some((id, task)) if task.depth == Some(depth) => Ok(*id),
            Some(_) => Err(err(format!(
                "{what} can't wait in an event handler, even one a task set off: start a task for it with nf.task"
            ))),
            None => Err(err(format!(
                "{what} only works inside a task: start one with nf.task(function() ... end), since a script's body, event handlers and timers can't wait"
            ))),
        }
    }

    fn sleep(&self, task: u64, ticks: u64) {
        let (scope, token) = {
            let mut state = self.0.state.borrow_mut();
            state.next_token += 1;
            let token = state.next_token;
            let scope = state.tasks.get(&task).map(|t| t.scope);
            if let Some(t) = state.tasks.get_mut(&task) {
                t.waiting = Some(Wait::Timer(token));
            }
            (scope, token)
        };
        if let Some(scope) = scope {
            self.timer(scope, ticks.max(1), TimerKind::Wake { task, token });
        }
    }

    fn check_wait_untils(&self, now: u64) {
        let waiting: Vec<(u64, ScopeId, Function, Option<u64>)> = self
            .0
            .state
            .borrow()
            .tasks
            .iter()
            .filter_map(|(id, t)| match &t.waiting {
                Some(Wait::Until {
                    predicate,
                    deadline,
                }) => Some((*id, t.scope, predicate.clone(), *deadline)),
                _ => None,
            })
            .collect();
        for (id, scope, predicate, deadline) in waiting {
            // The predicate runs as the task's code: its error is the task's.
            let budget = self.budget(scope);
            match self.host().invoke::<Value>(scope, budget, &predicate, ()) {
                Ok(v) if !matches!(v, Value::Nil | Value::Boolean(false)) => {
                    self.wake(
                        id,
                        Wait::Until {
                            predicate,
                            deadline,
                        },
                        MultiValue::from_vec(vec![Value::Boolean(true)]),
                    );
                }
                Ok(_) if deadline.is_some_and(|d| now >= d) => {
                    self.wake(
                        id,
                        Wait::Until {
                            predicate,
                            deadline,
                        },
                        MultiValue::from_vec(vec![Value::Boolean(false)]),
                    );
                }
                Ok(_) => {}
                Err(failure) => {
                    self.end_task(id);
                    self.report(scope, "task", failure, false);
                }
            }
        }
    }
}

/// What handlers made of an event.
pub struct Delivered {
    pub cancelled: bool,
    /// The payload, with whatever writable fields handlers set.
    pub fields: Table,
}

fn takes_custom(class: &str) -> bool {
    class == "nf" || class == "Centity"
}

/// `event:cancel()`, `:uncancel()` (`cancel` set) and `:stop()` (`None`).
fn event_method(host: &Host, private: &Table, cancel: Option<bool>) -> mlua::Result<Function> {
    let private = private.clone();
    let body = move |ev: Value| -> mlua::Result<()> {
        let view = match &ev {
            Value::Table(t) => t
                .metatable()
                .and_then(|m| m.raw_get::<Table>("__index").ok())
                .filter(|v| v.raw_get::<Table>(private.clone()).is_ok()),
            _ => None,
        };
        let Some(view) = view else {
            return Err(mlua::Error::runtime(
                "call Event methods with ':' on an event, like event:stop()",
            ));
        };
        let state: Table = view.raw_get(private.clone())?;
        match cancel {
            None => view.raw_set("stopped", true),
            Some(on) => {
                if !state.raw_get::<bool>("cancellable")? {
                    let name: String = view.raw_get("name")?;
                    return Err(mlua::Error::runtime(format!(
                        "the {name} event can't be cancelled"
                    )));
                }
                view.raw_set("cancelled", on)
            }
        }
    };
    host.script_function(move |_, args: MultiValue| {
        body(args.into_iter().next().unwrap_or(Value::Nil)).map(|_| MultiValue::new())
    })
}

/// An event's `__newindex`: only its writable fields, into its fields.
fn event_newindex(host: &Host, private: &Table) -> mlua::Result<Function> {
    let private = private.clone();
    let body = move |lua: &Lua, ev: Table, key: Value, value: Value| -> mlua::Result<()> {
        let view: Table = ev
            .metatable()
            .expect("an event has its metatable")
            .raw_get("__index")?;
        let state: Table = view.raw_get(private.clone())?;
        let name: String = view.raw_get("name")?;
        let key_text = key
            .to_string()
            .unwrap_or_else(|_| key.type_name().to_owned());
        let reserved = [
            "name",
            "current",
            "cancelled",
            "stopped",
            "cancel",
            "uncancel",
            "stop",
        ];
        if reserved.contains(&key_text.as_str()) {
            return Err(mlua::Error::runtime(format!(
                "event.{key_text} can't be assigned"
            )));
        }
        let fields: Table = state.raw_get("fields")?;
        match state.raw_get::<Value>("writable")? {
            Value::Table(writable) => {
                if !writable.raw_get::<bool>(key.clone()).unwrap_or(false) {
                    let mut names: Vec<String> = writable
                        .pairs::<String, bool>()
                        .filter_map(|p| p.ok().map(|(n, _)| n))
                        .collect();
                    names.sort_unstable();
                    let listed = if names.is_empty() {
                        "it has no writable fields".to_owned()
                    } else {
                        format!("writable: {}", names.join(", "))
                    };
                    return Err(mlua::Error::runtime(format!(
                        "event.{key_text} can't be assigned on a {name} event ({listed})"
                    )));
                }
                // Read as the payload's field will be read back, so a mistake (an item in a list included) is
                // an error at this line rather than a dropped value later.
                if let Some(payload) = state.raw_get::<Option<String>>("payload")?
                    && let Some(Err(mistake)) = netherforge_api::codecs::check_field(
                        lua,
                        &payload,
                        &key_text,
                        value.clone(),
                        &format!("event.{key_text}"),
                    )
                {
                    return Err(mlua::Error::runtime(as_value_mistake(&mistake.0)));
                }
                fields.raw_set(key, value)
            }
            _ => fields.raw_set(key, value),
        }
    };
    host.script_function(move |lua, args: MultiValue| {
        let mut args = args.into_iter();
        let Some(Value::Table(ev)) = args.next() else {
            return Err(mlua::Error::runtime(
                "an event's fields are assigned on the event",
            ));
        };
        let key = args.next().unwrap_or(Value::Nil);
        let value = args.next().unwrap_or(Value::Nil);
        body(lua, ev, key, value).map(|_| MultiValue::new())
    })
}

/// A codec's mistake about an argument, said of an assigned value as Kotlin's runtime says it:
/// `bad argument 'event.message' (…)` is `bad value for event.message (…)`.
fn as_value_mistake(message: &str) -> String {
    if let Some(rest) = message.strip_prefix("bad argument '")
        && let Some((at, why)) = rest.split_once("' ")
    {
        return format!("bad value for {at} {why}");
    }
    message.to_owned()
}

impl NfApi for Scripts {
    fn on(
        &self,
        caller: &Caller,
        event: String,
        handler: Function,
        options: Option<shapes::EventOptions>,
    ) -> ApiResult<handles::Subscription> {
        self.subscribe(caller.scope, Target::Nf, event, handler, options, false)
    }

    fn once(
        &self,
        caller: &Caller,
        event: String,
        handler: Function,
    ) -> ApiResult<handles::Subscription> {
        self.subscribe(caller.scope, Target::Nf, event, handler, None, true)
    }

    fn emit(&self, _: &Caller, event: String, payload: Value) -> ApiResult<bool> {
        self.emit_custom(Target::Nf, event, payload)
    }

    fn after(&self, caller: &Caller, ticks: i64, callback: Function) -> ApiResult<handles::Task> {
        if ticks < 0 {
            return Err(err("bad argument 'ticks' (can't be negative)"));
        }
        let id = self.timer(caller.scope, ticks as u64, TimerKind::Once(callback));
        Ok(handles::Task { id: id as i64 })
    }

    fn every(&self, caller: &Caller, ticks: i64, callback: Function) -> ApiResult<handles::Task> {
        if ticks < 1 {
            return Err(err("bad argument 'ticks' (nf.every needs at least 1)"));
        }
        let period = ticks as u64;
        let id = self.timer(caller.scope, period, TimerKind::Every(callback, period));
        Ok(handles::Task { id: id as i64 })
    }

    fn task(
        &self,
        caller: &Caller,
        callback: Function,
        rest: Vec<Value>,
    ) -> ApiResult<handles::Task> {
        self.start_task(caller.scope, callback, rest)
    }

    fn wait(&self, _: &Caller, ticks: i64) -> ApiResult<Waiting> {
        let task = self.running_task("nf.wait")?;
        if ticks < 0 {
            return Err(err("bad argument 'ticks' (can't be negative)"));
        }
        self.sleep(task, ticks as u64);
        Ok(Waiting)
    }

    fn wait_until(
        &self,
        _: &Caller,
        predicate: Function,
        timeout: Option<i64>,
    ) -> ApiResult<Waiting> {
        let task = self.running_task("nf.wait_until")?;
        if timeout.is_some_and(|t| t < 0) {
            return Err(err("bad argument 'timeout' (can't be negative)"));
        }
        let deadline = timeout.map(|t| self.tick_count() + t as u64);
        if let Some(t) = self.0.state.borrow_mut().tasks.get_mut(&task) {
            t.waiting = Some(Wait::Until {
                predicate,
                deadline,
            });
        }
        Ok(Waiting)
    }

    fn wait_for(
        &self,
        caller: &Caller,
        handle: unions::CentityOrNodeOrEntityOrWorldOrProjectBlockOrMenuOrSlotOrMenuTemplateOrDialogOrButtonOrProjectItemOrEffectOrHttpStreamOrWebSocketOrOAuthFlowOrWasmProcessOrNetworkChannelOrNetworkMapOrCutsceneOrScreenOrPlaybackOrTable,
        event: String,
        filter: Option<Function>,
    ) -> ApiResult<Waiting> {
        let task = self.running_task("nf.wait_for")?;
        let host = self.host();
        let lua = host.lua();
        let target = target_of(lua, handle)?;
        // A handler in the task's scope that wakes it with the event, once the
        // filter (run as the task's code) accepts it.
        let scripts = Rc::downgrade(&self.0);
        let waker = lua
            .create_function(move |_, ev: Value| {
                let Some(inner) = scripts.upgrade() else {
                    return Ok(());
                };
                let scripts = Scripts(inner);
                let (sub, filter) = match scripts
                    .0
                    .state
                    .borrow()
                    .tasks
                    .get(&task)
                    .and_then(|t| t.waiting.as_ref())
                {
                    Some(Wait::Event { sub, filter }) => (*sub, filter.clone()),
                    _ => return Ok(()),
                };
                if let Some(filter) = filter {
                    let accepted: Value = filter.call(ev.clone())?;
                    if matches!(accepted, Value::Nil | Value::Boolean(false)) {
                        return Ok(());
                    }
                }
                scripts.unsubscribe(sub);
                scripts.wake(
                    task,
                    Wait::Event { sub, filter: None },
                    MultiValue::from_vec(vec![ev]),
                );
                Ok(())
            })
            .map_err(lua_err)?;
        let sub = self.subscribe(caller.scope, target, event, waker, None, false)?;
        if let Some(t) = self.0.state.borrow_mut().tasks.get_mut(&task) {
            t.waiting = Some(Wait::Event {
                sub: sub.id as u64,
                filter,
            });
        }
        Ok(Waiting)
    }

    fn data(&self, caller: &Caller, name: String) -> ApiResult<Value> {
        Unported.data(caller, name)
    }

    fn db(&self, caller: &Caller, name: Option<String>) -> ApiResult<handles::Database> {
        Unported.db(caller, name)
    }

    fn config(&self, caller: &Caller, name: String) -> ApiResult<Value> {
        Unported.config(caller, name)
    }

    fn instructions_left(&self, _: &Caller) -> ApiResult<i64> {
        Ok(self
            .host()
            .steps_left()
            .min(netherforge_api::MAX_SAFE_INTEGER as u64) as i64)
    }
}

/// What `nf.wait_for` waits on: a handle, or a scope's `nf` (a table).
fn target_of(
    _: &Lua,
    handle: unions::CentityOrNodeOrEntityOrWorldOrProjectBlockOrMenuOrSlotOrMenuTemplateOrDialogOrButtonOrProjectItemOrEffectOrHttpStreamOrWebSocketOrOAuthFlowOrWasmProcessOrNetworkChannelOrNetworkMapOrCutsceneOrScreenOrPlaybackOrTable,
) -> ApiResult<Target> {
    use unions::CentityOrNodeOrEntityOrWorldOrProjectBlockOrMenuOrSlotOrMenuTemplateOrDialogOrButtonOrProjectItemOrEffectOrHttpStreamOrWebSocketOrOAuthFlowOrWasmProcessOrNetworkChannelOrNetworkMapOrCutsceneOrScreenOrPlaybackOrTable as U;
    match handle {
        U::Table(_) => Ok(Target::Nf),
        _ => Err(err(
            "nf.wait_for on a handle isn't on the Rust runtime yet: only nf",
        )),
    }
}

impl SubscriptionApi for Scripts {
    fn cancel(&self, this: &handles::Subscription) -> ApiResult<()> {
        self.unsubscribe(this.id as u64);
        Ok(())
    }

    fn is_active(&self, this: &handles::Subscription) -> ApiResult<bool> {
        Ok(self.0.state.borrow().subs.contains_key(&(this.id as u64)))
    }
}

impl TaskApi for Scripts {
    fn cancel(&self, this: &handles::Task) -> ApiResult<()> {
        let id = this.id as u64;
        if !self.cancel_timer(id) {
            self.end_task(id);
        }
        Ok(())
    }

    fn is_active(&self, this: &handles::Task) -> ApiResult<bool> {
        let id = this.id as u64;
        let state = self.0.state.borrow();
        Ok(state.timers.contains_key(&id) || state.tasks.contains_key(&id))
    }
}
