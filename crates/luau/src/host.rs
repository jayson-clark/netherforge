use std::cell::RefCell;
use std::collections::HashMap;
use std::ffi::c_int;
use std::rc::{Rc, Weak};

use mlua::{FromLuaMulti, Function, IntoLuaMulti, Lua, MultiValue, Table, Value, VmState, ffi};

use crate::clock::{Clock, SystemClock};
use crate::failure::{Failure, Location, Stop};
use crate::limits::Limits;
use crate::timing::{FunctionCost, ScopeCost, Timing};

/// What scripts get from Luau itself. A whitelist: anything not named here
/// (`os`, `debug`, `getfenv`, `setfenv`, `loadstring`, `require`,
/// `collectgarbage`, `gcinfo`, `newproxy`, `print`) isn't there. `print` and
/// `require` come from the runtime, per scope.
const BASE: &[&str] = &[
    "_VERSION",
    "assert",
    "error",
    "getmetatable",
    "ipairs",
    "next",
    "pairs",
    "pcall",
    "rawequal",
    "rawget",
    "rawlen",
    "rawset",
    "select",
    "setmetatable",
    "tonumber",
    "tostring",
    "type",
    "typeof",
    "unpack",
    "xpcall",
    "string",
    "table",
    "math",
    "utf8",
    "bit32",
    "buffer",
    "vector",
    "coroutine",
];

/// One scope: a script's environment, its own globals over the shared base.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct ScopeId(u32);

struct Scope {
    name: String,
    env: Table,
    category: u8,
}

/// Luau's memory categories: 256 ids, 0 the host's own. Each scope is charged
/// to its key's category (scopes of one script share one), and once every id
/// is taken new keys share the last.
struct Categories {
    by_key: HashMap<String, u8>,
    next: u16,
}

const HOST_CATEGORY: u8 = 0;
const LAST_CATEGORY: u8 = u8::MAX;

impl Categories {
    fn of(&mut self, key: &str) -> u8 {
        if let Some(&id) = self.by_key.get(key) {
            return id;
        }
        let id = self.next.min(LAST_CATEGORY as u16) as u8;
        self.next = (self.next + 1).min(LAST_CATEGORY as u16);
        self.by_key.insert(key.to_owned(), id);
        id
    }
}

/// A call into a script: what it may spend and what it has.
pub(crate) struct Frame {
    scope: ScopeId,
    category: u8,
    budget: u64,
    used: u64,
    deadline: u64,
    pub(crate) stop: Option<Stop>,
    location: Option<Location>,
    /// When it opened, by the host's clock, and what the frames nested in it took.
    started: u64,
    nested: u64,
    /// What it runs, when its caller said (`Host::next_kind`).
    kind: Option<String>,
    /// Which call in it is, so a debugger's step knows its own.
    pub(crate) serial: u64,
}

pub(crate) struct Guard {
    limits: Limits,
    pub(crate) clock: Rc<dyn Clock>,
    pub(crate) frames: Vec<Frame>,
    since_clock: u32,
    /// Time the debugger held the state paused, left out of every reading of the clock.
    pub(crate) paused: u64,
    timing: Timing,
    serials: u64,
}

impl Guard {
    /// The clock, less the time spent paused.
    fn now(&self) -> u64 {
        self.clock.nanos().saturating_sub(self.paused)
    }

    /// One interrupt: charges the running frame, and says whether to stop it.
    fn interrupt(&mut self) -> Option<&mut Frame> {
        let clock_every = self.limits.clock_every;
        self.since_clock += 1;
        let read_clock = self.since_clock >= clock_every;
        if read_clock {
            self.since_clock = 0;
        }
        let now = if read_clock { Some(self.now()) } else { None };
        let deadline = self.limits.deadline;
        let frame = self.frames.last_mut()?;
        if frame.stop.is_none() {
            frame.used += 1;
            if frame.used > frame.budget {
                frame.stop = Some(Stop::Budget {
                    budget: frame.budget,
                });
            } else if now.is_some_and(|now| now >= frame.deadline) {
                frame.stop = Some(Stop::Time { limit: deadline });
            }
        }
        if frame.stop.is_some() {
            Some(frame)
        } else {
            None
        }
    }
}

struct Inner {
    lua: Lua,
    guard: Rc<RefCell<Guard>>,
    /// Wraps a Rust function scripts call (`Host::script_function`).
    shim: Function,
    /// What a wrapped function returns first when it failed, then its message.
    failed: Table,
    base: Table,
    scopes: RefCell<HashMap<ScopeId, Scope>>,
    categories: RefCell<Categories>,
    next_scope: RefCell<u32>,
    blamed: RefCell<Option<ScopeId>>,
    /// The names of scopes closed while the profiler was on, until it takes what they ran.
    gone: RefCell<HashMap<ScopeId, String>>,
    /// The debugger's state, and what a call in runs through while it breaks on errors.
    debug: Rc<crate::debug::DebugState>,
    catching: Function,
    error_handler: Function,
}

/// A Luau state and the scopes in it: one per room. Cheap to clone; a clone
/// is the same host, so a function the runtime gives Luau can call back in.
#[derive(Clone)]
pub struct Host(Rc<Inner>);

/// A host as a callback keeps it, so the state doesn't keep itself alive.
#[derive(Clone)]
pub struct WeakHost(Weak<Inner>);

impl WeakHost {
    pub fn upgrade(&self) -> Option<Host> {
        self.0.upgrade().map(Host)
    }
}

impl Host {
    pub fn new(limits: Limits) -> mlua::Result<Host> {
        Self::with_clock(limits, Rc::new(SystemClock::new()))
    }

    pub fn with_clock(limits: Limits, clock: Rc<dyn Clock>) -> mlua::Result<Host> {
        let lua = Lua::new();
        lua.set_memory_limit(limits.memory_bytes)?;
        let guard = Rc::new(RefCell::new(Guard {
            limits,
            clock,
            frames: Vec::new(),
            since_clock: 0,
            paused: 0,
            timing: Timing::default(),
            serials: 0,
        }));
        // The caps go into the real `string` and `table` before they're made
        // read-only, so the string metatable (`("x"):rep(n)`) reaches them too.
        install_caps(&lua, &guard)?;
        // The real globals become the base: everything not whitelisted goes,
        // then they're made read-only (`luaL_sandbox`), so no script can change
        // `string.format` for every other. They must hold only what scripts may
        // have, because Luau resolves a builtin's name (`math`, `os`, `_G`)
        // straight from them in a `safeenv` environment, past the scope's own.
        let base = lua.globals();
        let unlisted: Vec<String> = base
            .pairs::<String, Value>()
            .filter_map(|pair| pair.ok().map(|(name, _)| name))
            .filter(|name| !BASE.contains(&name.as_str()))
            .collect();
        for name in unlisted {
            base.raw_set(name, Value::Nil)?;
        }
        // The string metatable is the library's: `getmetatable("")` says nothing of it, so no script reaches
        // `string` through it (what the Kotlin runtime's sandbox and format's terrain glue did).
        if let Some(strings) = lua
            .load("return getmetatable('')")
            .call::<Option<Table>>(())?
        {
            strings.raw_set("__metatable", false)?;
        }
        lua.sandbox(true)?;

        let interrupt_guard = guard.clone();
        lua.set_interrupt(move |lua| {
            let mut guard = interrupt_guard.borrow_mut();
            let Some(frame) = guard.interrupt() else {
                return Ok(VmState::Continue);
            };
            if frame.location.is_none() {
                frame.location = project_location(lua);
            }
            let stop = frame.stop.clone().expect("a stopped frame has its stop");
            // A script may catch this with `pcall`, so the frame keeps why it
            // stopped: every interrupt from now on raises it again, and the
            // call is reported stopped whatever it returned.
            Err(mlua::Error::runtime(stop.to_string()))
        });

        let failed = lua.create_table()?;
        let shim: Function = lua
            .load(SHIM)
            .set_name("=nf/shim")
            .set_compiler(mlua::chunk::Compiler::new().set_debug_level(0))
            .call((
                failed.clone(),
                locator(&lua)?,
                lua.globals().get::<Function>("error")?,
                lua.globals()
                    .get::<Table>("coroutine")?
                    .get::<Function>("yield")?,
                lua.globals()
                    .get::<Table>("coroutine")?
                    .get::<Function>("isyieldable")?,
            ))?;
        let debug = crate::debug::DebugState::install(&lua, &guard)?;
        let catching: Function = lua
            .load(crate::debug::CATCHING)
            .set_name("=nf/catching")
            .set_compiler(mlua::chunk::Compiler::new().set_debug_level(0))
            .call((
                lua.globals().get::<Function>("xpcall")?,
                lua.globals().get::<Function>("error")?,
                lua.globals()
                    .get::<Table>("table")?
                    .get::<Function>("pack")?,
                lua.globals().get::<Function>("unpack")?,
            ))?;
        let error_handler = debug.error_handler(&lua)?;
        Ok(Host(Rc::new(Inner {
            debug,
            catching,
            error_handler,
            lua,
            guard,
            shim,
            failed,
            base,
            scopes: RefCell::new(HashMap::new()),
            categories: RefCell::new(Categories {
                by_key: HashMap::new(),
                next: 1,
            }),
            next_scope: RefCell::new(0),
            blamed: RefCell::new(None),
            gone: RefCell::new(HashMap::new()),
        })))
    }

    pub fn downgrade(&self) -> WeakHost {
        WeakHost(Rc::downgrade(&self.0))
    }

    /// The Luau state, for making values and functions. Calls into scripts go
    /// through [`Host::invoke`], never a bare `call`, so they're guarded.
    pub fn lua(&self) -> &Lua {
        &self.0.lua
    }

    /// `f` as a function scripts call. mlua raises a Rust function's error as
    /// a userdata of its own, where Lua (and the Kotlin runtime) raise a
    /// string: so a failure here is raised as its message, located at the
    /// script's line (`modules/shop/init.lua:3: no module "x"`), and
    /// `pcall` hands the script a string, as it would for `error`.
    pub fn script_function<F>(&self, f: F) -> mlua::Result<Function>
    where
        F: Fn(&Lua, MultiValue) -> mlua::Result<MultiValue> + 'static,
    {
        self.wrapped(f, None)
    }

    /// `script_function` for a function that waits: when it returns `waiting`, its task yields (and is resumed
    /// with the call's value). One called back from outside Luau (`table.sort`'s comparator) can't yield, and
    /// fails as `called` (`nf.wait`) can't wait there.
    pub fn waiting_function<F>(&self, f: F, waiting: Value, called: &str) -> mlua::Result<Function>
    where
        F: Fn(&Lua, MultiValue) -> mlua::Result<MultiValue> + 'static,
    {
        self.wrapped(f, Some((waiting, called)))
    }

    fn wrapped<F>(&self, f: F, waiting: Option<(Value, &str)>) -> mlua::Result<Function>
    where
        F: Fn(&Lua, MultiValue) -> mlua::Result<MultiValue> + 'static,
    {
        let failed = self.0.failed.clone();
        let raw = self
            .0
            .lua
            .create_function(move |lua, args: MultiValue| match f(lua, args) {
                Ok(values) => Ok(values),
                Err(e) => {
                    let message = lua.create_string(message_of(&e))?;
                    Ok(MultiValue::from_vec(vec![
                        Value::Table(failed.clone()),
                        Value::String(message),
                    ]))
                }
            })?;
        let (waiting, stuck) = match waiting {
            Some((value, called)) => (
                value,
                Some(format!(
                    "{called} can't wait inside a function called back from outside Lua (like table.sort's comparator or string.gsub's replacement): wait in the task's own code"
                )),
            ),
            None => (Value::Nil, None),
        };
        self.0.shim.call((raw, waiting, stuck))
    }

    /// Opens a scope named `name` (for reports), whose memory is charged to
    /// `memory_key` (the scopes of one script share a key, so its instances
    /// are counted together).
    pub fn open_scope(&self, name: &str, memory_key: &str) -> mlua::Result<ScopeId> {
        let lua = &self.0.lua;
        let env = lua.create_table()?;
        let meta = lua.create_table()?;
        meta.raw_set("__index", self.0.base.clone())?;
        meta.raw_set("__metatable", false)?;
        meta.set_readonly(true);
        env.set_metatable(Some(meta))?;
        // Like `luaL_sandboxthread`'s proxy: lets Luau resolve the base's
        // functions at load time (`math.floor` without a lookup), and Luau
        // turns it off by itself for an environment a script writes to.
        env.set_safeenv(true);
        let id = {
            let mut next = self.0.next_scope.borrow_mut();
            *next += 1;
            ScopeId(*next)
        };
        let category = self.0.categories.borrow_mut().of(memory_key);
        self.0.scopes.borrow_mut().insert(
            id,
            Scope {
                name: name.to_owned(),
                env,
                category,
            },
        );
        Ok(id)
    }

    /// Forgets a scope. What it held is collected once nothing else holds it.
    pub fn close_scope(&self, id: ScopeId) {
        let closed = self.0.scopes.borrow_mut().remove(&id);
        let mut guard = self.0.guard.borrow_mut();
        guard.timing.forget(id);
        if guard.timing.profiling()
            && let Some(scope) = closed
        {
            self.0.gone.borrow_mut().insert(id, scope.name);
        }
    }

    pub fn scope_name(&self, id: ScopeId) -> Option<String> {
        self.0.scopes.borrow().get(&id).map(|s| s.name.clone())
    }

    /// A scope's name, or, while the profiler is on, the name of one closed since it last took what scopes ran.
    pub fn scope_title(&self, id: ScopeId) -> Option<String> {
        self.scope_name(id)
            .or_else(|| self.0.gone.borrow().get(&id).cloned())
    }

    /// A scope's environment: its globals, over the shared base.
    pub fn env(&self, id: ScopeId) -> Option<Table> {
        self.0.scopes.borrow().get(&id).map(|s| s.env.clone())
    }

    /// Compiles `source` as the chunk `chunk` (a project path, which Luau's
    /// messages then say) to run in `scope`.
    pub fn load(&self, scope: ScopeId, chunk: &str, source: &str) -> Result<Function, Failure> {
        let env = self.env(scope).ok_or_else(|| unknown_scope(scope))?;
        self.0
            .lua
            .load(source)
            .set_name(format!("@{chunk}"))
            // Locals' and upvalues' names, for the debugger; nothing at run time.
            .set_compiler(mlua::chunk::Compiler::new().set_debug_level(2))
            .set_environment(env)
            .into_function()
            .map_err(|e| failure_of(&e))
    }

    /// Calls `f` for `scope`, guarded: at most `budget` steps and the time
    /// limit (or what's left of the calling frame's, if that's sooner), its
    /// allocations charged to the scope. A call in from inside another
    /// (a Rust function a script called) is a frame of its own, whose steps are
    /// charged to its caller when it returns.
    pub fn invoke<R: FromLuaMulti>(
        &self,
        scope: ScopeId,
        budget: u64,
        f: &Function,
        args: impl IntoLuaMulti,
    ) -> Result<R, Failure> {
        let category = self
            .0
            .scopes
            .borrow()
            .get(&scope)
            .map(|s| s.category)
            .ok_or_else(|| unknown_scope(scope))?;
        let parent_category = {
            let mut guard = self.0.guard.borrow_mut();
            let budget = guard
                .limits
                .max_budget
                .map_or(budget, |most| budget.min(most));
            let now = guard.now();
            let own = now.saturating_add(guard.limits.deadline.as_nanos() as u64);
            let parent = guard.frames.last();
            let deadline = parent.map_or(own, |p| p.deadline.min(own));
            let parent_category = parent.map_or(HOST_CATEGORY, |p| p.category);
            let kind = guard.timing.next_kind.take();
            guard.serials += 1;
            let serial = guard.serials;
            guard.frames.push(Frame {
                scope,
                category,
                budget,
                used: 0,
                deadline,
                stop: None,
                location: None,
                started: now,
                nested: 0,
                kind,
                serial,
            });
            parent_category
        };
        set_memory_category(&self.0.lua, category);
        let result = if self.0.debug.errors_on() {
            // Breaking on errors: through `xpcall`, whose handler stops where the error happened.
            args.into_lua_multi(&self.0.lua).and_then(|mut args| {
                args.push_front(Value::Function(f.clone()));
                args.push_front(Value::Function(self.0.error_handler.clone()));
                self.0.catching.call::<R>(args)
            })
        } else {
            f.call::<R>(args)
        };
        set_memory_category(&self.0.lua, parent_category);

        let frame = {
            let mut guard = self.0.guard.borrow_mut();
            let mut frame = guard.frames.pop().expect("the call's frame is still there");
            let now = guard.now();
            let elapsed = now.saturating_sub(frame.started);
            if let Some(parent) = guard.frames.last_mut() {
                parent.used = parent.used.saturating_add(frame.used);
                parent.nested = parent.nested.saturating_add(elapsed);
            }
            let own = elapsed.saturating_sub(frame.nested);
            guard.timing.close(scope, f, own, frame.kind.take());
            frame
        };
        if let Some(stop) = frame.stop {
            return Err(Failure::Stopped {
                stop,
                location: frame.location,
            });
        }
        result.map_err(|e| {
            let failure = failure_of(&e);
            if failure == Failure::Memory {
                self.blame();
            }
            failure
        })
    }

    /// Runs `f` as if no call were running (Kotlin's `detached`): a call into a script inside it is a frame of
    /// its own, with its own budget and time limit, and none of its steps is charged to the calls running now.
    /// A test's `nf.test.advance` ticks the server this way, as a server would, inside the test's own call.
    pub fn detached<R>(&self, f: impl FnOnce() -> R) -> R {
        let saved = std::mem::take(&mut self.0.guard.borrow_mut().frames);
        let category = saved.last().map_or(HOST_CATEGORY, |f| f.category);
        set_memory_category(&self.0.lua, HOST_CATEGORY);
        let result = f();
        set_memory_category(&self.0.lua, category);
        let mut guard = self.0.guard.borrow_mut();
        let inside = std::mem::replace(&mut guard.frames, saved);
        debug_assert!(inside.is_empty(), "every call inside has returned");
        result
    }

    /// Loads and runs a chunk's body in `scope`.
    pub fn run<R: FromLuaMulti>(
        &self,
        scope: ScopeId,
        budget: u64,
        chunk: &str,
        source: &str,
    ) -> Result<R, Failure> {
        let f = self.load(scope, chunk, source)?;
        self.invoke(scope, budget, &f, ())
    }

    /// Charges the running call `steps` for work done on its behalf that no
    /// interrupt counts (the JSON codec's walk over a script's table, the
    /// runtime's over an option table), and says why it must stop, if it
    /// must. The stop is kept, so it's reported even if the script catches
    /// it. The caller stops its work there.
    pub fn charge(&self, steps: u64) -> Option<Stop> {
        let mut guard = self.0.guard.borrow_mut();
        let frame = guard.frames.last_mut()?;
        if frame.stop.is_none() {
            frame.used = frame.used.saturating_add(steps);
            if frame.used > frame.budget {
                frame.stop = Some(Stop::Budget {
                    budget: frame.budget,
                });
            }
        }
        frame.stop.clone()
    }

    /// Steps the running call may still take before it's stopped: the least
    /// of its own budget's and each caller's, since what it runs is charged to
    /// its callers when it returns. 0 outside a call.
    pub fn steps_left(&self) -> u64 {
        let guard = self.0.guard.borrow();
        let mut used_inside = 0u64;
        let mut least = u64::MAX;
        for frame in guard.frames.iter().rev() {
            let used = frame.used.saturating_add(used_inside);
            least = least.min(frame.budget.saturating_sub(used));
            used_inside = used;
        }
        if guard.frames.is_empty() { 0 } else { least }
    }

    /// How many calls in are running, one inside another: 0 outside any.
    pub fn depth(&self) -> usize {
        self.0.guard.borrow().frames.len()
    }

    /// The scope whose call is running, if any.
    pub fn running_scope(&self) -> Option<ScopeId> {
        self.0.guard.borrow().frames.last().map(|f| f.scope)
    }

    /// Bytes the state uses, every scope and the host together.
    pub fn memory(&self) -> usize {
        self.0.lua.used_memory()
    }

    /// Bytes charged to `scope`'s memory key and not yet freed: what its
    /// script's scopes allocated and still hold. Exact, at any moment, with no
    /// walk of the heap.
    pub fn scope_memory(&self, scope: ScopeId) -> Option<usize> {
        let category = self.0.scopes.borrow().get(&scope)?.category;
        Some(category_bytes(&self.0.lua, category))
    }

    /// The scope blamed for the last allocation failure, once: the one
    /// holding the most, if that's at least a quarter of the limit. The
    /// runtime stops it at the end of the tick.
    pub fn take_blamed(&self) -> Option<ScopeId> {
        self.0.blamed.borrow_mut().take()
    }

    /// The runtime's debugger, which the host asks what to do once a script stops.
    pub fn set_debug_handler(&self, handler: Option<Rc<dyn crate::debug::DebugHandler>>) {
        self.0.debug.set_handler(handler);
    }

    /// Attaches the debugger (if it wasn't) with what to stop at; a pause stops at the next line any script runs.
    /// Taken at the next call in.
    pub fn debug_configure(&self, config: crate::debug::DebugConfig) {
        self.0.debug.configure(config);
    }

    /// Forgets the debugger: nothing to stop at, no step, nothing stepping.
    pub fn debug_detach(&self) {
        self.0.debug.detach();
    }

    /// The host's clock: the injected one, less the time a debugger held the state paused. What every time limit
    /// and cost reads, and what the profiler should time the tick with, so a paused tick isn't a slow one.
    pub fn now(&self) -> u64 {
        self.0.guard.borrow().now()
    }

    /// `nanos` passed while a debugger held the state paused: left out of the clock from now on.
    pub fn add_paused(&self, nanos: u64) {
        let mut guard = self.0.guard.borrow_mut();
        guard.paused = guard.paused.saturating_add(nanos);
    }

    /// What the next call in runs, for the profiler: `tick`, an event's name, `timer`, `task`, `command`.
    pub fn next_kind(&self, kind: &str) {
        self.0.guard.borrow_mut().timing.next_kind = Some(kind.to_owned());
    }

    /// What each scope's code took since the last time, by scope (only scopes that ran). Once a tick: it also
    /// starts the tick over for `over_tick`. `rotate` starts a new window for the slowest calls (`hot_spot`).
    pub fn take_costs(&self, rotate: bool) -> HashMap<ScopeId, ScopeCost> {
        self.0.guard.borrow_mut().timing.take_costs(rotate)
    }

    /// Where `scope`'s slowest recent call's function starts (its file and line), when that's project code.
    pub fn hot_spot(&self, scope: ScopeId) -> Option<Location> {
        let f = self.0.guard.borrow().timing.hot_spot(scope)?;
        let info = f.info();
        let chunk = info.source.as_deref()?.strip_prefix('@')?.to_owned();
        // A body (Luau's main function) is its file, at no line of its own: line 0.
        let line = if info.what == "main" {
            0
        } else {
            info.line_defined.unwrap_or(0) as u32
        };
        Some(Location { chunk, line })
    }

    /// Whether `scope`'s code already ran its tick's worth (`Limits::max_tick`), so a timer or `tick` handler of
    /// its own waits for the next tick; one that is is marked deferred, for `take_deferred`.
    pub fn over_tick(&self, scope: ScopeId) -> bool {
        let mut guard = self.0.guard.borrow_mut();
        let limit = guard.limits.max_tick.as_nanos() as u64;
        guard.timing.over_tick(scope, limit)
    }

    /// The scopes whose timers or `tick` handlers waited since the last time.
    pub fn take_deferred(&self) -> Vec<ScopeId> {
        self.0.guard.borrow_mut().timing.take_deferred()
    }

    /// Turns the profiler's per-function times on or off; off forgets what it kept.
    pub fn set_profiling(&self, on: bool) {
        self.0.guard.borrow_mut().timing.set_profiling(on);
        if !on {
            self.0.gone.borrow_mut().clear();
        }
    }

    /// Forgets the names of scopes closed since the profiler last took what they ran (`scope_title`).
    pub fn forget_gone(&self) {
        self.0.gone.borrow_mut().clear();
    }

    /// What each function cost each scope since the last time, while the profiler was on.
    pub fn take_profile(&self) -> Vec<FunctionCost> {
        // Taken out first: saying where a function starts reads Luau, which mustn't happen under the guard's borrow.
        let functions = self.0.guard.borrow_mut().timing.take_functions();
        crate::timing::resolve(functions)
    }

    fn blame(&self) {
        let limit = self.0.guard.borrow().limits.memory_bytes;
        let scopes = self.0.scopes.borrow();
        let heaviest = scopes
            .iter()
            .map(|(id, s)| (category_bytes(&self.0.lua, s.category), *id))
            .max();
        if let Some((bytes, id)) = heaviest
            && bytes >= limit / 4
        {
            *self.0.blamed.borrow_mut() = Some(id);
        }
    }
}

fn unknown_scope(scope: ScopeId) -> Failure {
    Failure::Error {
        message: format!("no scope {}", scope.0),
        traceback: None,
    }
}

/// Wraps a Rust function scripts call: a failure (`failed`, then its message) is raised as the message located
/// at the script's line; a waiting function's `waiting` yields its task, where it can.
const SHIM: &str = r#"
local failed, locate, error, yield, isyieldable = ...
return function(f, waiting, stuck)
  -- What the function returned, as many values as it returned (none is none, not one nil).
  local function finish(...)
    local first = ...
    if first == failed then
      error(locate((select(2, ...))), 0)
    end
    if waiting ~= nil and first == waiting then
      if not isyieldable() then
        error(locate(stuck), 0)
      end
      return yield()
    end
    return ...
  end
  return function(...)
    return finish(f(...))
  end
end
"#;

/// The message of an error a Rust function returned: the error itself, not mlua's wrapping of it.
pub fn message_of(e: &mlua::Error) -> String {
    match e {
        mlua::Error::RuntimeError(message) => message.clone(),
        mlua::Error::CallbackError { cause, .. } => message_of(cause),
        mlua::Error::WithContext { cause, .. } => message_of(cause),
        other => other.to_string(),
    }
}

/// A message located at the script's line (`modules/shop/init.lua:3: ...`), unless it's already there.
fn locator(lua: &Lua) -> mlua::Result<Function> {
    lua.create_function(|lua, message: String| {
        Ok(match project_location(lua) {
            Some(at) if !message.starts_with(&at.chunk) => format!("{at}: {message}"),
            _ => message,
        })
    })
}

/// Where the innermost script frame on the stack is: the first function from
/// a project chunk (`@path`), past the host's own and the library's.
pub fn project_location(lua: &Lua) -> Option<Location> {
    (0..32).find_map(|level| {
        lua.inspect_stack(level, |debug| {
            let source = debug.source();
            let chunk = source.source.as_deref()?.strip_prefix('@')?.to_owned();
            let line = debug.current_line()?;
            Some(Location {
                chunk,
                line: line as u32,
            })
        })
        .flatten()
    })
}

/// The caps on the standard library (format's `caps.lua`, shared with the
/// Kotlin runtime): the functions whose work isn't bounded by their
/// arguments' size, which no interrupt can stop once they're running, are
/// replaced by ones that work out what the call could cost first.
fn install_caps(lua: &Lua, guard: &Rc<RefCell<Guard>>) -> mlua::Result<()> {
    let globals = lua.globals();
    let string: Table = globals.get("string")?;
    let table: Table = globals.get("table")?;
    let raw = lua.create_table()?;
    for name in ["rep", "format", "find", "match", "gmatch", "gsub", "pack"] {
        raw.raw_set(name, string.raw_get::<Value>(name)?)?;
    }
    for name in ["concat", "move", "insert", "remove", "sort"] {
        raw.raw_set(name, table.raw_get::<Value>(name)?)?;
    }
    // What the caps ask of a table: whether it has a metatable at all, even
    // one that hides itself with `__metatable` (Luau has no
    // `debug.getmetatable`).
    raw.raw_set(
        "getmetatable",
        lua.create_function(|_, value: Value| {
            Ok(match value {
                Value::Table(t) => t.metatable(),
                _ => None,
            })
        })?,
    )?;

    let (max_string, max_work) = {
        let guard = guard.borrow();
        (guard.limits.max_string_bytes, guard.limits.max_work)
    };
    let limits = lua.create_table()?;
    limits.raw_set("max_string", max_string as f64)?;
    limits.raw_set("max_work", max_work)?;

    // Work a capped call is about to do counts towards the next look at the
    // clock, so a loop of calls each under the caps still meets the time
    // limit.
    let spend_guard = guard.clone();
    let spend = lua.create_function(move |_, steps: f64| {
        let mut guard = spend_guard.borrow_mut();
        let interrupts = (steps / STEPS_PER_INTERRUPT).min(u32::MAX as f64) as u32;
        guard.since_clock = guard.since_clock.saturating_add(interrupts);
        Ok(())
    })?;
    let locate = locator(lua)?;
    let stripped = || mlua::chunk::Compiler::new().set_debug_level(0);
    let refuse: Function = lua
        .load("local locate, error = ...\nreturn function(message) error(locate(message), 0) end")
        .set_name("=nf/refuse")
        .set_compiler(stripped())
        .call((locate, globals.get::<Function>("error")?))?;
    // Without its debug information, the library's own errors (a bad
    // argument), which it locates at its caller, aren't located inside the
    // caps, and the host finds the script's line instead.
    let caps: Table = lua
        .load(CAPS)
        .set_name("=nf/caps")
        .set_compiler(stripped())
        .call((raw, limits, spend, refuse))?;
    for (name, f) in caps
        .get::<Table>("string")?
        .pairs::<String, Function>()
        .flatten()
    {
        string.raw_set(name, f)?;
    }
    for (name, f) in caps
        .get::<Table>("table")?
        .pairs::<String, Function>()
        .flatten()
    {
        table.raw_set(name, f)?;
    }
    globals.raw_set("setmetatable", caps.get::<Function>("setmetatable")?)?;
    Ok(())
}

const CAPS: &str = include_str!("../../../packages/format/src/commonMain/lua/caps.lua");

/// About how many steps of a capped call's work (each about a nanosecond)
/// one interrupt stands for: a loop iteration or a call.
const STEPS_PER_INTERRUPT: f64 = 5.0;

pub(crate) fn failure_of(error: &mlua::Error) -> Failure {
    match error {
        mlua::Error::MemoryError(_) => Failure::Memory,
        mlua::Error::CallbackError { cause, .. } => failure_of(cause),
        mlua::Error::WithContext { cause, .. } => failure_of(cause),
        mlua::Error::RuntimeError(message) | mlua::Error::SyntaxError { message, .. } => {
            error_with_traceback(message)
        }
        other => error_with_traceback(&other.to_string()),
    }
}

/// Luau's message, split from the traceback mlua appends to it. A message
/// that doesn't say where it's from (the library's, raised inside the caps,
/// which have no lines) is located at the traceback's first script frame.
pub(crate) fn error_with_traceback(text: &str) -> Failure {
    let (message, traceback) = match text.split_once("\nstack traceback:\n") {
        Some((message, traceback)) => (message, Some(traceback)),
        None => (text, None),
    };
    let message = match traceback.and_then(first_script_frame) {
        Some(at) if located(message).is_none() => format!("{at}: {message}"),
        _ => message.to_owned(),
    };
    Failure::Error {
        message,
        traceback: traceback.map(str::to_owned),
    }
}

/// The `path.lua:12` a message or a traceback line starts with, if it's a
/// script's (a project path, `.lua` or `.luau`).
fn located(text: &str) -> Option<&str> {
    let text = text.trim_start();
    let (path, rest) = text.split_once(':')?;
    let digits = rest
        .find(|c: char| !c.is_ascii_digit())
        .unwrap_or(rest.len());
    let script = path.ends_with(".lua") || path.ends_with(".luau");
    (script && digits > 0 && rest[digits..].starts_with(':'))
        .then(|| &text[..path.len() + 1 + digits])
}

fn first_script_frame(traceback: &str) -> Option<&str> {
    traceback.lines().find_map(located)
}

fn set_memory_category(lua: &Lua, category: u8) {
    // SAFETY: `lua_setmemcat` only stores the id on the running thread; it
    // can't raise.
    let _ = unsafe { lua.exec_raw::<()>((), |state| ffi::lua_setmemcat(state, category as c_int)) };
}

fn category_bytes(lua: &Lua, category: u8) -> usize {
    // SAFETY: `lua_totalbytes` reads a counter; pushing a number can't fail
    // past the stack check `exec_raw` does.
    unsafe {
        lua.exec_raw::<f64>((), |state| {
            let bytes = ffi::lua_totalbytes(state, category as c_int);
            ffi::lua_pushnumber(state, bytes as f64);
        })
    }
    .unwrap_or(0.0) as usize
}
