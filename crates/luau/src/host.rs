use std::cell::RefCell;
use std::collections::HashMap;
use std::ffi::c_int;
use std::rc::{Rc, Weak};

use mlua::{FromLuaMulti, Function, IntoLuaMulti, Lua, Table, Value, VmState, ffi};

use crate::clock::{Clock, SystemClock};
use crate::failure::{Failure, Location, Stop};
use crate::limits::Limits;

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
struct Frame {
    scope: ScopeId,
    category: u8,
    budget: u64,
    used: u64,
    deadline: u64,
    stop: Option<Stop>,
    location: Option<Location>,
}

struct Guard {
    limits: Limits,
    clock: Rc<dyn Clock>,
    frames: Vec<Frame>,
    since_clock: u32,
}

impl Guard {
    /// One interrupt: charges the running frame, and says whether to stop it.
    fn interrupt(&mut self) -> Option<&mut Frame> {
        let clock_every = self.limits.clock_every;
        self.since_clock += 1;
        let read_clock = self.since_clock >= clock_every;
        if read_clock {
            self.since_clock = 0;
        }
        let now = if read_clock {
            Some(self.clock.nanos())
        } else {
            None
        };
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
    base: Table,
    scopes: RefCell<HashMap<ScopeId, Scope>>,
    categories: RefCell<Categories>,
    next_scope: RefCell<u32>,
    blamed: RefCell<Option<ScopeId>>,
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

        Ok(Host(Rc::new(Inner {
            lua,
            guard,
            base,
            scopes: RefCell::new(HashMap::new()),
            categories: RefCell::new(Categories {
                by_key: HashMap::new(),
                next: 1,
            }),
            next_scope: RefCell::new(0),
            blamed: RefCell::new(None),
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
        self.0.scopes.borrow_mut().remove(&id);
    }

    pub fn scope_name(&self, id: ScopeId) -> Option<String> {
        self.0.scopes.borrow().get(&id).map(|s| s.name.clone())
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
            let now = guard.clock.nanos();
            let own = now.saturating_add(guard.limits.deadline.as_nanos() as u64);
            let parent = guard.frames.last();
            let deadline = parent.map_or(own, |p| p.deadline.min(own));
            let parent_category = parent.map_or(HOST_CATEGORY, |p| p.category);
            guard.frames.push(Frame {
                scope,
                category,
                budget,
                used: 0,
                deadline,
                stop: None,
                location: None,
            });
            parent_category
        };
        set_memory_category(&self.0.lua, category);
        let result = f.call::<R>(args);
        set_memory_category(&self.0.lua, parent_category);

        let frame = {
            let mut guard = self.0.guard.borrow_mut();
            let frame = guard.frames.pop().expect("the call's frame is still there");
            if let Some(parent) = guard.frames.last_mut() {
                parent.used = parent.used.saturating_add(frame.used);
            }
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
    let locate = lua.create_function(|lua, message: String| {
        Ok(match project_location(lua) {
            Some(at) if !message.starts_with(&at.chunk) => format!("{at}: {message}"),
            _ => message,
        })
    })?;
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
