//! The debugger's half in the host (the prelude's `debugger.lua`, on Luau): breakpoints, stepping, pausing, breaking
//! on errors, and what a stopped script holds. The Debug Adapter Protocol is the runtime's (`crates/runtime/src/debug`);
//! this is told what to stop at ([`Host::debug_configure`]) and asks a [`DebugHandler`] what to do once it has.
//!
//! **Lines come from single-stepping.** Luau has no line hook. `lua_singlestep` makes the VM call the `debugstep`
//! callback before every instruction of a thread, and only while it's on: so it's on (on every thread) only while
//! there are breakpoints, or a step or pause is under way; a server nobody debugs runs the plain VM. A line is new
//! when the thread, its stack depth or the line differ from the last instruction's, and only then is the chunk read
//! (`lua_getinfo`). Luau picks the stepping VM as it enters a call from the host, so a change takes effect at the next
//! call in (the next tick at the latest), which is when the runtime hands configuration over anyway. Every thread is
//! known (Luau's `userthread` callback), so a task that waits through the change steps too. Luau's own `lua_breakpoint`
//! patches functions' bytecode instead, but it needs every function a chunk made, and stepping needs single steps
//! regardless.
//!
//! **A stop holds the thread** inside the callback: it calls a Rust function on the stopped thread (so mlua reads its
//! stack, as a callback's), which captures the frames (names, places, locals and functions, copied: levels move as
//! soon as anything runs), then calls the handler, which doesn't return until the editor says how to go on. Nothing of
//! the script runs meanwhile: values are read raw (`rawget`, `next`, no metamethod), and handles are described by the
//! runtime. The time held is left out of the host's clock, so no time limit or cost counts it.
//!
//! **Stepping counts frames.** A step belongs to the thread it started on and, on the main thread, to the call in it
//! started in: a nested call in runs through, and once that call returns the step ends and the server runs on (stepping
//! off the end of a handler doesn't stop in the next). In a task the step follows the coroutine across its waits.
//!
//! **Breaking on errors** stops where an error no `pcall` catches happened, its stack whole: while it's on, a call in
//! runs through `xpcall` with a message handler that stops, then the error goes on as it would have. Not in tasks: a
//! task's error is caught by its resume, which has no handler.

use std::cell::{Cell, RefCell};
use std::collections::{BTreeSet, HashMap, HashSet};
use std::ffi::{CStr, c_int};
use std::rc::{Rc, Weak};

use mlua::{AnyUserData, Function, Lua, MultiValue, Table, Value, ffi};

use crate::host::Guard;

/// How to go on from a stop.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Resume {
    Continue,
    /// Step over: the next line in this function or the one it returns to.
    Next,
    StepIn,
    StepOut,
    /// The editor went: forget everything and run on.
    Detach,
}

/// What to stop at.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct DebugConfig {
    /// Lines by chunk (a project path or a package path: `modules/m/init.lua`, `lib:modules/api/init.lua`).
    pub breakpoints: HashMap<String, BTreeSet<u32>>,
    /// Stop where an error no `pcall` catches happens.
    pub errors: bool,
    /// Stop at the next line any script runs.
    pub pause: bool,
}

/// A handle as the editor shows it: one line, its class, and the facts it expands to.
pub struct HandleView {
    pub summary: String,
    pub class: String,
    pub facts: Vec<(String, String)>,
}

/// What the host asks of the runtime's debugger, on the main thread.
pub trait DebugHandler {
    /// A script stopped: answer what the editor asks of `stop` until it says how to go on. The script holds the
    /// server's main thread meanwhile.
    fn stopped(&self, stop: &mut Stopped) -> Resume;
    /// How a userdata the script holds shows (a handle: `Player Steve`), if the runtime knows it.
    fn describe(&self, value: &AnyUserData) -> Option<HandleView>;
}

/// One frame of the stopped stack, as the editor's stack trace shows it.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct StackRow {
    pub name: String,
    pub file: String,
    pub line: u32,
}

/// One variable: its name, the value as text, its type, and the reference that expands it (0: nothing to expand).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct VariableRow {
    pub name: String,
    pub value: String,
    pub kind: String,
    pub reference: i64,
}

struct Frame {
    name: String,
    file: String,
    line: u32,
    func: Option<Function>,
    locals: Vec<(String, Value)>,
    env: Option<Table>,
}

enum Expand {
    Locals(usize),
    Upvalues(usize),
    Value(Value),
}

/// A stop: why and where, and what the stack holds, readable until the script goes on.
pub struct Stopped<'a> {
    pub reason: String,
    pub file: String,
    pub line: u32,
    /// An error's message, when it stopped for one.
    pub text: Option<String>,
    lua: &'a Lua,
    handler: &'a dyn DebugHandler,
    frames: Vec<Frame>,
    refs: Vec<Expand>,
    ref_of: HashMap<usize, i64>,
}

const MAX_CHILDREN: usize = 1000;
const MAX_STRING: usize = 300;

impl Stopped<'_> {
    /// The project frames, the innermost first.
    pub fn stack(&self) -> Vec<StackRow> {
        self.frames
            .iter()
            .map(|f| StackRow {
                name: f.name.clone(),
                file: f.file.clone(),
                line: f.line,
            })
            .collect()
    }

    /// Frame `frame`'s scopes (1 the innermost): its locals, its upvalues when it has any, and its script's globals.
    pub fn scopes(&mut self, frame: usize) -> Result<Vec<(String, i64)>, String> {
        let index = frame
            .checked_sub(1)
            .filter(|i| *i < self.frames.len())
            .ok_or_else(|| format!("there's no frame {frame}"))?;
        let mut scopes = vec![("Locals".to_owned(), self.reference(Expand::Locals(index)))];
        if !self.upvalues(index).is_empty() {
            scopes.push(("Upvalues".into(), self.reference(Expand::Upvalues(index))));
        }
        if let Some(env) = self.frames[index].env.clone() {
            scopes.push((
                "Globals".into(),
                self.reference(Expand::Value(Value::Table(env))),
            ));
        }
        Ok(scopes)
    }

    /// What expanding `reference` lists.
    pub fn variables(&mut self, reference: i64) -> Result<Vec<VariableRow>, String> {
        let what = match usize::try_from(reference - 1)
            .ok()
            .and_then(|i| self.refs.get(i))
        {
            Some(Expand::Locals(i)) => Children::Pairs(self.frames[*i].locals.clone()),
            Some(Expand::Upvalues(i)) => Children::Pairs(self.upvalues(*i)),
            Some(Expand::Value(v)) => self.children_of(&v.clone()),
            None => {
                return Err(format!("there are no variables {reference} at this stop"));
            }
        };
        let rows = match what {
            Children::Pairs(pairs) => pairs
                .into_iter()
                .map(|(name, value)| self.row(name, &value))
                .collect(),
            Children::Facts(facts) => facts
                .into_iter()
                .map(|(name, value)| VariableRow {
                    name,
                    value,
                    kind: "string".into(),
                    reference: 0,
                })
                .collect(),
        };
        Ok(rows)
    }

    fn row(&mut self, name: String, value: &Value) -> VariableRow {
        let (text, kind, expandable) = self.show(value);
        let reference = if expandable {
            self.reference(Expand::Value(value.clone()))
        } else {
            0
        };
        VariableRow {
            name,
            value: text,
            kind,
            reference,
        }
    }

    /// The reference `what` is expanded by: the same one for the same table at this stop.
    fn reference(&mut self, what: Expand) -> i64 {
        let key = match &what {
            Expand::Value(v) => Some(v.to_pointer() as usize),
            _ => None,
        };
        if let Some(key) = key
            && let Some(r) = self.ref_of.get(&key)
        {
            return *r;
        }
        self.refs.push(what);
        let r = self.refs.len() as i64;
        if let Some(key) = key {
            self.ref_of.insert(key, r);
        }
        r
    }

    fn upvalues(&self, frame: usize) -> Vec<(String, Value)> {
        let Some(func) = &self.frames[frame].func else {
            return Vec::new();
        };
        upvalues_of(self.lua, func)
    }

    /// A value as the editor shows it: its text, its type, and whether it has entries to expand.
    fn show(&self, value: &Value) -> (String, String, bool) {
        match value {
            Value::Nil => ("nil".into(), "nil".into(), false),
            Value::Boolean(b) => (b.to_string(), "boolean".into(), false),
            Value::Integer(n) => (n.to_string(), "number".into(), false),
            Value::Number(n) => (number_text(*n), "number".into(), false),
            Value::Vector(v) => (
                format!(
                    "{}, {}, {}",
                    number_text(v.x() as f64),
                    number_text(v.y() as f64),
                    number_text(v.z() as f64)
                ),
                "vector".into(),
                false,
            ),
            Value::String(s) => (quote(&s.to_string_lossy()), "string".into(), false),
            Value::Function(f) => {
                let info = f.info();
                let text = match info.source.as_deref().and_then(|s| s.strip_prefix('@')) {
                    Some(file) => format!("function {file}:{}", info.line_defined.unwrap_or(0)),
                    None if info.what == "C" => "built-in function".into(),
                    None => "function".into(),
                };
                (text, "function".into(), false)
            }
            Value::Thread(t) => (
                format!("coroutine ({})", thread_status(t)),
                "thread".into(),
                false,
            ),
            Value::Table(t) => {
                if let Some((name, _)) = event_of(t) {
                    return (format!("Event {name}"), "Event".into(), true);
                }
                (preview(t, self), "table".into(), !t.is_empty_raw())
            }
            Value::UserData(u) => match self.handler.describe(u) {
                Some(view) => (view.summary, view.class, true),
                None => ("userdata".into(), "userdata".into(), false),
            },
            Value::Buffer(b) => (
                format!("buffer ({} bytes)", b.len()),
                "buffer".into(),
                false,
            ),
            Value::LightUserData(_) => ("userdata".into(), "userdata".into(), false),
            Value::Error(e) => (quote(&e.to_string()), "error".into(), false),
            other => (other.type_name().into(), other.type_name().into(), false),
        }
    }

    fn children_of(&self, value: &Value) -> Children {
        match value {
            Value::UserData(u) => Children::Facts(
                self.handler
                    .describe(u)
                    .map(|v| v.facts)
                    .unwrap_or_default(),
            ),
            Value::Table(t) => {
                if let Some((name, fields)) = event_of(t) {
                    let mut entries = vec![(
                        "name".to_owned(),
                        Value::String(self.lua.create_string(&name).expect("a string")),
                    )];
                    if let Some(fields) = fields {
                        entries.extend(table_entries(self.lua, &fields));
                    }
                    return Children::Pairs(entries);
                }
                Children::Pairs(table_entries(self.lua, t))
            }
            _ => Children::Pairs(Vec::new()),
        }
    }
}

enum Children {
    Pairs(Vec<(String, Value)>),
    Facts(Vec<(String, String)>),
}

trait RawEmpty {
    fn is_empty_raw(&self) -> bool;
}

impl RawEmpty for Table {
    fn is_empty_raw(&self) -> bool {
        self.pairs::<Value, Value>().next().is_none()
    }
}

/// An event's name and its fields' table, when `t` is one (the runtime's: an empty table whose `__index` is its view,
/// with a name and the methods, whose own `__index` holds its fields), read raw.
fn event_of(t: &Table) -> Option<(String, Option<Table>)> {
    if !t.is_empty_raw() {
        return None;
    }
    let view: Table = t.metatable()?.raw_get("__index").ok()?;
    let name: String = view.raw_get("name").ok()?;
    let Value::Function(_) = view.raw_get::<Value>("cancel").ok()? else {
        return None;
    };
    let fields = view
        .metatable()
        .and_then(|m| m.raw_get::<Table>("__index").ok());
    Some((name, fields))
}

/// A table's entries, read raw: its sequence first, then its other keys in order (numbers, strings, the rest).
fn table_entries(lua: &Lua, t: &Table) -> Vec<(String, Value)> {
    let n = t.raw_len();
    let mut entries: Vec<(String, Value)> = Vec::new();
    for i in 1..=n.min(MAX_CHILDREN) {
        entries.push((format!("[{i}]"), t.raw_get(i).unwrap_or(Value::Nil)));
    }
    let mut keys: Vec<Value> = Vec::new();
    for (k, _) in t.pairs::<Value, Value>().flatten() {
        if let Some(i) = whole(&k)
            && i >= 1
            && i as usize <= n
        {
            continue;
        }
        keys.push(k);
        if keys.len() > MAX_CHILDREN {
            break;
        }
    }
    let rank = |v: &Value| match v {
        Value::Integer(_) | Value::Number(_) => 1,
        Value::String(_) => 2,
        Value::Boolean(_) => 3,
        _ => 4,
    };
    keys.sort_by(|a, b| {
        rank(a).cmp(&rank(b)).then_with(|| match (a, b) {
            (Value::String(x), Value::String(y)) => x.as_bytes().cmp(&y.as_bytes()),
            _ => match (number(a), number(b)) {
                (Some(x), Some(y)) => x.total_cmp(&y),
                _ => std::cmp::Ordering::Equal,
            },
        })
    });
    for k in keys {
        if entries.len() >= MAX_CHILDREN {
            if let Ok(more) = lua.create_string("more entries not shown") {
                entries.push(("…".into(), Value::String(more)));
            }
            break;
        }
        let value = t.raw_get(k.clone()).unwrap_or(Value::Nil);
        entries.push((key_text(&k), value));
    }
    entries
}

fn number(v: &Value) -> Option<f64> {
    match v {
        Value::Integer(i) => Some(*i as f64),
        Value::Number(n) => Some(*n),
        _ => None,
    }
}

fn whole(v: &Value) -> Option<i64> {
    match v {
        Value::Integer(i) => Some(*i),
        Value::Number(n) if n.fract() == 0.0 && n.abs() < 9.0e15 => Some(*n as i64),
        _ => None,
    }
}

fn is_identifier(s: &str) -> bool {
    let mut chars = s.chars();
    chars
        .next()
        .is_some_and(|c| c.is_ascii_alphabetic() || c == '_')
        && chars.all(|c| c.is_ascii_alphanumeric() || c == '_')
}

fn key_text(k: &Value) -> String {
    match k {
        Value::String(s) => {
            let s = s.to_string_lossy();
            if is_identifier(&s) {
                s
            } else {
                format!("[{}]", quote(&s))
            }
        }
        Value::Integer(i) => format!("[{i}]"),
        Value::Number(n) => format!("[{}]", number_text(*n)),
        other => format!("[{}]", other.type_name()),
    }
}

/// A table's first few entries, `{1, 2, x = 3, …}`, nested tables not followed.
fn preview(t: &Table, stop: &Stopped) -> String {
    let short = |v: &Value| match v {
        Value::Table(_) => "{…}".to_owned(),
        other => stop.show(other).0,
    };
    let n = t.raw_len();
    let mut parts: Vec<String> = (1..=n.min(4))
        .map(|i| short(&t.raw_get(i).unwrap_or(Value::Nil)))
        .collect();
    let mut more = n > 4;
    for (k, v) in t.pairs::<Value, Value>().flatten() {
        if let Some(i) = whole(&k)
            && i >= 1
            && i as usize <= n
        {
            continue;
        }
        if parts.len() >= 4 {
            more = true;
            break;
        }
        parts.push(format!("{} = {}", key_text(&k), short(&v)));
    }
    if more {
        parts.push("…".into());
    }
    format!("{{{}}}", parts.join(", "))
}

fn number_text(n: f64) -> String {
    if n.fract() == 0.0 && n.abs() < 1e15 {
        format!("{}", n as i64)
    } else if n.is_nan() {
        "nan".into()
    } else if n.is_infinite() {
        if n > 0.0 { "inf".into() } else { "-inf".into() }
    } else {
        // `%.14g`: 14 significant digits, trailing zeros dropped.
        let text = format!("{n:.13e}");
        let (mantissa, exponent) = text.split_once('e').expect("an exponent");
        let exponent: i32 = exponent.parse().expect("a number");
        if (-5..14).contains(&exponent) {
            let decimals = (13 - exponent).max(0) as usize;
            let fixed = format!("{n:.decimals$}");
            fixed.trim_end_matches('0').trim_end_matches('.').to_owned()
        } else {
            let mantissa = mantissa.trim_end_matches('0').trim_end_matches('.');
            format!(
                "{mantissa}e{}{:02}",
                if exponent < 0 { '-' } else { '+' },
                exponent.abs()
            )
        }
    }
}

fn quote(s: &str) -> String {
    let cut = s.chars().count() > MAX_STRING;
    let s: String = s.chars().take(MAX_STRING).collect();
    let mut out = String::from("\"");
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 32 || c as u32 == 127 => out.push_str(&format!("\\{}", c as u32)),
            c => out.push(c),
        }
    }
    out.push_str(if cut { "…\"" } else { "\"" });
    out
}

fn thread_status(t: &mlua::Thread) -> &'static str {
    match t.status() {
        mlua::thread::ThreadStatus::Resumable => "suspended",
        mlua::thread::ThreadStatus::Running => "running",
        mlua::thread::ThreadStatus::Normal => "normal",
        mlua::thread::ThreadStatus::Finished | mlua::thread::ThreadStatus::Error => "dead",
    }
}

fn upvalues_of(lua: &Lua, func: &Function) -> Vec<(String, Value)> {
    let mut names = Vec::new();
    // SAFETY: the function is the closure's one argument, at index 1; each upvalue pushed is returned.
    let values: MultiValue = unsafe {
        lua.exec_raw(func.clone(), |state| {
            let mut n = 1;
            loop {
                ffi::lua_checkstack(state, 2);
                let name = ffi::lua_getupvalue(state, 1, n);
                if name.is_null() {
                    break;
                }
                names.push(CStr::from_ptr(name).to_string_lossy().into_owned());
                n += 1;
            }
            ffi::lua_remove(state, 1);
        })
    }
    .unwrap_or_default();
    names
        .into_iter()
        .zip(values)
        .filter(|(name, _)| !name.is_empty() && name != "_ENV")
        .collect()
}

// ---- the host's state ----------------------------------------------------------------------------------------------

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Mode {
    Pause,
    In,
    Over,
    Out,
}

#[derive(Clone, Copy)]
struct Step {
    thread: usize,
    /// On the main thread, the call in it belongs to.
    serial: Option<u64>,
    depth: c_int,
}

/// The debugger's state in one host.
pub(crate) struct DebugState {
    guard: Weak<RefCell<Guard>>,
    handler: RefCell<Option<Rc<dyn DebugHandler>>>,
    attached: Cell<bool>,
    breakpoints: RefCell<HashMap<String, HashSet<u32>>>,
    errors: Cell<bool>,
    mode: Cell<Option<Mode>>,
    step: Cell<Option<Step>>,
    /// The thread, depth and line of the last instruction stepped: a new line is a change in any.
    last: Cell<(usize, c_int, c_int)>,
    /// Every live thread of the state (the main one too), to switch single-stepping on and off.
    threads: RefCell<HashSet<usize>>,
    main: usize,
    stepping: Cell<bool>,
    /// The stack depth of the line a stop is for, handed to the stop.
    depth: Cell<c_int>,
    /// A stop is under way (nothing steps meanwhile).
    stopping: Cell<bool>,
}

thread_local! {
    /// Every host's debugger state on this thread, by its main state: what the VM's callbacks find theirs by.
    static DEBUGGEES: RefCell<Vec<(usize, Weak<DebugState>)>> = const { RefCell::new(Vec::new()) };
}

/// The registry key of the function a stop calls on the stopped thread.
const STOP_KEY: &CStr = c"netherforge.debug.stop";

fn debuggee(l: *mut ffi::lua_State) -> Option<Rc<DebugState>> {
    // SAFETY: `l` is a live thread of a state; its main thread is too.
    let main = unsafe { ffi::lua_mainthread(l) } as usize;
    DEBUGGEES
        .try_with(|all| {
            let all = all.try_borrow().ok()?;
            all.iter()
                .find(|(m, _)| *m == main)
                .and_then(|(_, w)| w.upgrade())
        })
        .ok()
        .flatten()
}

impl DebugState {
    /// Installs the debugger in `lua`: the VM's callbacks and the stop's function.
    pub(crate) fn install(lua: &Lua, guard: &Rc<RefCell<Guard>>) -> mlua::Result<Rc<DebugState>> {
        let main = lua_main(lua);
        let state = Rc::new(DebugState {
            guard: Rc::downgrade(guard),
            handler: RefCell::new(None),
            attached: Cell::new(false),
            breakpoints: RefCell::default(),
            errors: Cell::new(false),
            mode: Cell::new(None),
            step: Cell::new(None),
            last: Cell::new((0, -1, -1)),
            threads: RefCell::new(HashSet::from([main])),
            main,
            stepping: Cell::new(false),
            depth: Cell::new(0),
            stopping: Cell::new(false),
        });
        DEBUGGEES.with(|all| {
            let mut all = all.borrow_mut();
            all.retain(|(_, w)| w.strong_count() > 0);
            all.push((main, Rc::downgrade(&state)));
        });
        let weak = Rc::downgrade(&state);
        let stop = lua.create_function(move |lua, (reason, text): (String, Option<String>)| {
            if let Some(state) = weak.upgrade() {
                state.stop(lua, &reason, text);
            }
            Ok(())
        })?;
        // SAFETY: the function is the closure's argument, at index 1; it's kept in the registry; the callbacks are
        // the state's, set once.
        unsafe {
            lua.exec_raw::<()>(stop, |l| {
                ffi::lua_pushvalue(l, 1);
                ffi::lua_setfield(l, ffi::LUA_REGISTRYINDEX, STOP_KEY.as_ptr());
                let callbacks = ffi::lua_callbacks(l);
                (*callbacks).debugstep = Some(debug_step);
                (*callbacks).userthread = Some(user_thread);
            })?;
        }
        Ok(state)
    }

    pub(crate) fn set_handler(&self, handler: Option<Rc<dyn DebugHandler>>) {
        *self.handler.borrow_mut() = handler;
    }

    pub(crate) fn errors_on(&self) -> bool {
        self.attached.get() && self.errors.get() && self.handler.borrow().is_some()
    }

    pub(crate) fn configure(&self, config: DebugConfig) {
        self.attached.set(true);
        *self.breakpoints.borrow_mut() = config
            .breakpoints
            .into_iter()
            .map(|(file, lines)| (file, lines.into_iter().collect()))
            .collect();
        self.errors.set(config.errors);
        if config.pause && self.mode.get().is_none() {
            self.mode.set(Some(Mode::Pause));
        }
        self.update();
    }

    pub(crate) fn detach(&self) {
        self.attached.set(false);
        self.breakpoints.borrow_mut().clear();
        self.errors.set(false);
        self.mode.set(None);
        self.step.set(None);
        self.update();
    }

    /// Single-stepping on every thread while there's something to stop at, off otherwise.
    fn update(&self) {
        let wanted = self.attached.get()
            && (self.mode.get().is_some() || !self.breakpoints.borrow().is_empty());
        self.stepping.set(wanted);
        self.last.set((0, -1, -1));
        let threads: Vec<usize> = self.threads.borrow().iter().copied().collect();
        for thread in threads {
            // SAFETY: every thread in the set is alive: a thread leaves it as Luau frees it.
            unsafe { ffi::lua_singlestep(thread as *mut ffi::lua_State, wanted as c_int) };
        }
    }

    fn finish_step(&self) {
        self.mode.set(None);
        self.step.set(None);
        self.update();
    }

    /// The serial of the call in running now, and whether `serial` is still on the stack of calls in.
    fn calls(&self, serial: Option<u64>) -> (Option<u64>, bool) {
        let Some(guard) = self.guard.upgrade() else {
            return (None, false);
        };
        let Ok(guard) = guard.try_borrow() else {
            return (None, true);
        };
        let top = guard.frames.last().map(|f| f.serial);
        let on = serial.is_some_and(|s| guard.frames.iter().any(|f| f.serial == s));
        (top, on)
    }

    /// One instruction of thread `l`, at `line`: why to stop there, if it should.
    unsafe fn line(&self, l: *mut ffi::lua_State, line: c_int) -> Option<&'static str> {
        if !self.attached.get() || self.stopping.get() || line < 0 {
            return None;
        }
        let thread = l as usize;
        // SAFETY: `l` is the running thread.
        let depth = unsafe { ffi::lua_stackdepth(l) };
        if self.last.get() == (thread, depth, line) {
            return None;
        }
        self.last.set((thread, depth, line));
        // SAFETY: level 0 is the function running the instruction.
        let source = unsafe {
            let mut ar: ffi::lua_Debug = std::mem::zeroed();
            if ffi::lua_getinfo(l, 0, c"s".as_ptr(), &mut ar) == 0 || ar.source.is_null() {
                return None;
            }
            CStr::from_ptr(ar.source)
        };
        let chunk = source.to_bytes().strip_prefix(b"@")?;
        self.depth.set(depth);
        let chunk = std::str::from_utf8(chunk).ok()?;
        if self
            .breakpoints
            .borrow()
            .get(chunk)
            .is_some_and(|lines| lines.contains(&(line as u32)))
        {
            return Some("breakpoint");
        }
        match self.mode.get()? {
            Mode::Pause => Some("pause"),
            Mode::In => Some("step"),
            mode @ (Mode::Over | Mode::Out) => {
                let Some(step) = self.step.get() else {
                    self.finish_step();
                    return None;
                };
                if thread != step.thread {
                    return None;
                }
                if thread == self.main {
                    let (top, on) = self.calls(step.serial);
                    if top != step.serial {
                        // A call in nested in the one stepped through runs on; once that one is over, the step is
                        // done and the server runs on.
                        if !on {
                            self.finish_step();
                        }
                        return None;
                    }
                }
                (depth < step.depth || (mode == Mode::Over && depth == step.depth))
                    .then_some("step")
            }
        }
    }

    /// Stops on the running thread (`lua`'s state now): captures the stack, asks the handler, and sets up what it
    /// said to do. The time it held is left out of the host's clock.
    fn stop(&self, lua: &Lua, reason: &str, text: Option<String>) {
        let Some(handler) = self.handler.borrow().clone() else {
            return;
        };
        let frames = capture(lua);
        let Some(top) = frames.first() else {
            return;
        };
        let thread = lua_current(lua);
        let depth = self.depth.get();
        self.stopping.set(true);
        let guard = self.guard.upgrade();
        let started = guard.as_ref().map(|g| g.borrow().clock.nanos());
        let mut stopped = Stopped {
            reason: reason.to_owned(),
            file: top.file.clone(),
            line: top.line,
            text,
            lua,
            handler: &*handler,
            frames,
            refs: Vec::new(),
            ref_of: HashMap::new(),
        };
        let resume = handler.stopped(&mut stopped);
        drop(stopped);
        if let (Some(guard), Some(started)) = (&guard, started) {
            let mut guard = guard.borrow_mut();
            let held = guard.clock.nanos().saturating_sub(started);
            guard.paused = guard.paused.saturating_add(held);
        }
        self.stopping.set(false);
        match resume {
            Resume::Detach => {
                self.attached.set(false);
                self.breakpoints.borrow_mut().clear();
                self.errors.set(false);
                self.mode.set(None);
                self.step.set(None);
            }
            // After an error the call is over whatever was asked: it goes on failing.
            Resume::Continue => {
                self.mode.set(None);
                self.step.set(None);
            }
            _ if reason == "exception" => {
                self.mode.set(None);
                self.step.set(None);
            }
            Resume::Next | Resume::StepIn | Resume::StepOut => {
                self.mode.set(Some(match resume {
                    Resume::Next => Mode::Over,
                    Resume::StepIn => Mode::In,
                    _ => Mode::Out,
                }));
                let serial = (thread == self.main).then(|| self.calls(None).0).flatten();
                self.step.set(Some(Step {
                    thread,
                    serial,
                    depth,
                }));
            }
        }
        self.update();
        // The line stopped at isn't a new line when it goes on.
        if let Some(main) = Some(thread) {
            self.last.set((main, depth, top_line(lua)));
        }
    }

    /// A thread was made (`parent` its maker) or freed (no parent).
    fn thread(&self, parent: *mut ffi::lua_State, l: *mut ffi::lua_State) {
        let Ok(mut threads) = self.threads.try_borrow_mut() else {
            return;
        };
        if parent.is_null() {
            threads.remove(&(l as usize));
            if self.step.get().is_some_and(|s| s.thread == l as usize) {
                self.step.set(None);
                self.mode.set(None);
            }
        } else {
            threads.insert(l as usize);
            // SAFETY: `l` was just made.
            unsafe { ffi::lua_singlestep(l, self.stepping.get() as c_int) };
        }
    }

    /// A message handler that stops where an uncaught error happened (`call_catching`).
    pub(crate) fn error_handler(self: &Rc<Self>, lua: &Lua) -> mlua::Result<Function> {
        let weak = Rc::downgrade(self);
        lua.create_function(move |lua, message: Value| {
            let Some(state) = weak.upgrade() else {
                return Ok(message);
            };
            // A stop the host made (the budget, the time limit) isn't the script's error.
            let stopped = state
                .guard
                .upgrade()
                .and_then(|g| {
                    g.try_borrow()
                        .ok()
                        .map(|g| g.frames.last().is_some_and(|f| f.stop.is_some()))
                })
                .unwrap_or(false);
            if stopped || !state.errors_on() || state.stopping.get() {
                return Ok(message);
            }
            let text = match &message {
                Value::String(s) => Some(s.to_string_lossy()),
                Value::Error(e) => Some(e.to_string()),
                _ => None,
            };
            state.depth.set(-1);
            state.stop(lua, "exception", text.clone());
            Ok(match text {
                Some(text) => {
                    let trace = traceback(lua);
                    Value::String(lua.create_string(format!("{text}\nstack traceback:\n{trace}"))?)
                }
                None => message,
            })
        })
    }
}

/// The current line of the innermost project frame on the running thread.
fn top_line(lua: &Lua) -> c_int {
    capture_rows(lua)
        .first()
        .map_or(-1, |row| row.line as c_int)
}

fn traceback(lua: &Lua) -> String {
    // SAFETY: `lua_debugtrace` reads the running thread's stack into a static buffer, copied here.
    unsafe {
        lua.exec_raw::<String>((), |state| {
            let trace = ffi::lua_debugtrace(state);
            ffi::lua_pushstring(state, trace);
        })
    }
    .unwrap_or_default()
}

fn lua_main(lua: &Lua) -> usize {
    // SAFETY: reading the state's main thread.
    unsafe {
        lua.exec_raw::<mlua::LightUserData>((), |state| {
            ffi::lua_pushlightuserdata(state, ffi::lua_mainthread(state).cast());
        })
    }
    .map_or(0, |p| p.0 as usize)
}

fn lua_current(lua: &Lua) -> usize {
    // SAFETY: reading the running thread.
    unsafe {
        lua.exec_raw::<mlua::LightUserData>((), |state| {
            ffi::lua_pushlightuserdata(state, state.cast());
        })
    }
    .map_or(0, |p| p.0 as usize)
}

struct FrameMeta {
    name: Option<String>,
    file: String,
    line: u32,
    defined: c_int,
    vararg: bool,
    locals: Vec<String>,
}

fn capture_rows(lua: &Lua) -> Vec<FrameMeta> {
    let mut rows = Vec::new();
    // SAFETY: walks the running thread's levels with `lua_getinfo`, pushing nothing.
    let _ = unsafe {
        lua.exec_raw::<()>((), |state| {
            let mut level = 0;
            loop {
                let mut ar: ffi::lua_Debug = std::mem::zeroed();
                if ffi::lua_getinfo(state, level, c"sl".as_ptr(), &mut ar) == 0 {
                    break;
                }
                if let Some(file) = project_file(&ar) {
                    rows.push(FrameMeta {
                        name: None,
                        file,
                        line: ar.currentline.max(0) as u32,
                        defined: ar.linedefined,
                        vararg: ar.isvararg != 0,
                        locals: Vec::new(),
                    });
                }
                level += 1;
            }
        })
    };
    rows
}

unsafe fn project_file(ar: &ffi::lua_Debug) -> Option<String> {
    if ar.source.is_null() {
        return None;
    }
    // SAFETY: Luau's source string lives as long as its function.
    let source = unsafe { CStr::from_ptr(ar.source) }.to_string_lossy();
    source.strip_prefix('@').map(str::to_owned)
}

/// Every project frame on the running thread, the innermost first: its name, place, function, locals and globals.
fn capture(lua: &Lua) -> Vec<Frame> {
    let mut metas: Vec<FrameMeta> = Vec::new();
    // SAFETY: for each project level, pushes its function (`f`) and then its locals, each with its name kept.
    let values: MultiValue = unsafe {
        lua.exec_raw((), |state| {
            let mut level = 0;
            loop {
                let mut ar: ffi::lua_Debug = std::mem::zeroed();
                if ffi::lua_getinfo(state, level, c"slna".as_ptr(), &mut ar) == 0 {
                    break;
                }
                if let Some(file) = project_file(&ar) {
                    ffi::lua_checkstack(state, 4);
                    let mut far: ffi::lua_Debug = std::mem::zeroed();
                    ffi::lua_getinfo(state, level, c"f".as_ptr(), &mut far);
                    let mut locals = Vec::new();
                    let mut n = 1;
                    loop {
                        ffi::lua_checkstack(state, 2);
                        let name = ffi::lua_getlocal(state, level, n);
                        if name.is_null() {
                            break;
                        }
                        locals.push(CStr::from_ptr(name).to_string_lossy().into_owned());
                        n += 1;
                    }
                    metas.push(FrameMeta {
                        name: (!ar.name.is_null())
                            .then(|| CStr::from_ptr(ar.name).to_string_lossy().into_owned()),
                        file,
                        line: ar.currentline.max(0) as u32,
                        defined: ar.linedefined,
                        vararg: ar.isvararg != 0,
                        locals,
                    });
                }
                level += 1;
            }
        })
    }
    .unwrap_or_default();
    let mut values = values.into_iter();
    metas
        .into_iter()
        .map(|meta| {
            let func = match values.next() {
                Some(Value::Function(f)) => Some(f),
                _ => None,
            };
            let locals: Vec<(String, Value)> = meta
                .locals
                .iter()
                .map(|name| (name.clone(), values.next().unwrap_or(Value::Nil)))
                .filter(|(name, _)| !name.starts_with('('))
                .collect();
            let env = func.as_ref().and_then(|f| environment(lua, f));
            let name = match meta.name {
                Some(name) if !name.is_empty() => name,
                _ if meta.vararg && meta.defined <= 1 => "(body)".into(),
                _ => format!("function (line {})", meta.defined.max(0)),
            };
            Frame {
                name,
                file: meta.file,
                line: meta.line,
                func,
                locals,
                env,
            }
        })
        .collect()
}

fn environment(lua: &Lua, f: &Function) -> Option<Table> {
    // SAFETY: the function is the closure's argument at index 1; its environment is pushed and returned.
    unsafe {
        lua.exec_raw::<Option<Table>>(f.clone(), |state| {
            ffi::lua_getfenv(state, 1);
            ffi::lua_remove(state, 1);
        })
    }
    .ok()
    .flatten()
}

unsafe extern "C-unwind" fn debug_step(l: *mut ffi::lua_State, ar: *mut ffi::lua_Debug) {
    let Some(state) = debuggee(l) else { return };
    // SAFETY: Luau hands the hook the line it's at.
    let line = unsafe { (*ar).currentline };
    // SAFETY: `l` is the running thread.
    let Some(reason) = (unsafe { state.line(l, line) }) else {
        return;
    };
    drop(state);
    // SAFETY: the stop's function is in the registry; calling it from the hook is how a hook calls into the VM.
    unsafe {
        ffi::lua_checkstack(l, 3);
        ffi::lua_getfield(l, ffi::LUA_REGISTRYINDEX, STOP_KEY.as_ptr());
        ffi::lua_pushlstring(l, reason.as_ptr().cast(), reason.len());
        ffi::lua_call(l, 1, 0);
    }
}

unsafe extern "C-unwind" fn user_thread(parent: *mut ffi::lua_State, l: *mut ffi::lua_State) {
    if let Some(state) = debuggee(if parent.is_null() { l } else { parent }) {
        state.thread(parent, l);
    }
}

/// Runs `f` through `xpcall` with `handler` as its message handler (`Host::invoke` while breaking on errors).
pub(crate) const CATCHING: &str = r#"
local xpcall, error, pack, unpack = ...
return function(handler, f, ...)
  local results = pack(xpcall(f, handler, ...))
  if results[1] then
    return unpack(results, 2, results.n)
  end
  error(results[2], 0)
end
"#;
