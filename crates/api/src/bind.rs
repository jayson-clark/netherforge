//! Putting the bindings into a Luau state: each handle class's methods (its
//! own and its chain's), and each scope's `nf`, which binds a function to the
//! scope the first time the scope reads it.

use std::collections::HashMap;
use std::rc::Rc;

use mlua::{Function, Lua, MultiValue, Table, Value};
use netherforge_luau::{Host, ScopeId};

use crate::api::LuaApi;
use crate::bindings::BINDINGS;
use crate::handle::Classes;
use crate::handles::CHAINS;

/// A binding's body: the state, the API, the calling scope (a namespace
/// function's) and the arguments.
pub type Call = fn(&Lua, &dyn LuaApi, Option<ScopeId>, &[Value]) -> mlua::Result<MultiValue>;

/// One function's binding, as the generator writes it.
pub struct Binding {
    /// Its class or namespace: `Centity`, `nf.server`.
    pub class: &'static str,
    pub name: &'static str,
    /// A handle method (its first argument is the handle); otherwise a
    /// namespace function, which gets the calling scope.
    pub method: bool,
    /// It waits: the task calling it yields once it returns [`crate::Waiting`].
    pub waits: bool,
    pub call: Call,
}

/// The scope whose call is running: who a handle method's work belongs to.
pub fn host_scope(lua: &Lua) -> Option<ScopeId> {
    crate::handle::host(lua)?.running_scope()
}

struct Installed {
    api: Rc<dyn LuaApi>,
    /// What a waiting binding returns to its shim: "yield now".
    waiting: Table,
}

/// The value a waiting binding returns, which its shim answers by yielding.
pub fn waiting(lua: &Lua) -> mlua::Result<Value> {
    lua.app_data_ref::<Installed>()
        .map(|i| Value::Table(i.waiting.clone()))
        .ok_or_else(|| mlua::Error::runtime("the API isn't installed"))
}

/// `binding` as scripts call it, through the host's wrapper (`Host::script_function`): its failure raised as a
/// string at the script's line. A Luau function can yield where a Rust one can't (without mlua's async
/// machinery, which would drive tasks itself), so a waiting binding returns `waiting` and the wrapper yields its
/// task's coroutine, which the scheduler resumes with the call's value.
fn wrapped(
    host: &Host,
    binding: &Binding,
    f: impl Fn(&Lua, MultiValue) -> mlua::Result<MultiValue> + 'static,
) -> mlua::Result<Function> {
    if !binding.waits {
        return host.script_function(f);
    }
    let waiting = waiting(host.lua())?;
    let separator = if binding.method { ":" } else { "." };
    host.waiting_function(
        f,
        waiting,
        &format!("{}{separator}{}", binding.class, binding.name),
    )
}

/// Puts the bindings into `host`'s state, implemented by `api`. Each handle
/// class gets its methods; scopes get their `nf` from [`nf_for`].
pub fn install(host: &Host, api: Rc<dyn LuaApi>) -> mlua::Result<()> {
    let lua = host.lua();
    lua.set_app_data(host.downgrade());
    let waiting = lua.create_table()?;
    lua.set_app_data(Installed {
        api: api.clone(),
        waiting,
    });

    // A method's function is made once and shared by every class that has it.
    let mut own: HashMap<&str, Vec<(&'static str, Function)>> = HashMap::new();
    for binding in BINDINGS.iter().filter(|b| b.method) {
        let api = api.clone();
        let call = binding.call;
        let f = wrapped(host, binding, move |lua, args: MultiValue| {
            let args: Vec<Value> = args.into_vec();
            call(lua, &*api, None, &args)
        })?;
        own.entry(binding.class)
            .or_default()
            .push((binding.name, f));
    }
    let mut classes = Classes::default();
    for chain in CHAINS {
        let methods = lua.create_table()?;
        // Farthest first, so a class's own replaces one of the same name above it.
        for class in chain.iter().rev() {
            for (name, f) in own.get(class).into_iter().flatten() {
                methods.raw_set(*name, f.clone())?;
            }
        }
        classes.methods.insert(chain[0], methods);
    }
    lua.set_app_data(classes);
    Ok(())
}

/// `scope`'s own `nf` (or the namespace `path` under it, `nf.server`): a table
/// that binds each function to the scope the first time it's read, so a
/// function registers into the scope whose code called it, wherever it's
/// called from.
pub fn nf_for(lua: &Lua, scope: ScopeId) -> mlua::Result<Table> {
    namespace(lua, scope, "nf")
}

fn namespace(lua: &Lua, scope: ScopeId, path: &'static str) -> mlua::Result<Table> {
    let table = lua.create_table()?;
    let meta = lua.create_table()?;
    meta.raw_set(
        "__index",
        lua.create_function(move |lua, (table, name): (Table, String)| {
            let value = lookup(lua, scope, path, &name)?;
            if !value.is_nil() {
                table.raw_set(name, value.clone())?;
            }
            Ok(value)
        })?,
    )?;
    let host =
        crate::handle::host(lua).ok_or_else(|| mlua::Error::runtime("the API isn't installed"))?;
    meta.raw_set(
        "__newindex",
        host.script_function(move |_, args: MultiValue| {
            let name = args
                .get(1)
                .and_then(|v| v.as_string().map(|s| s.to_string_lossy()))
                .unwrap_or_default();
            Err::<MultiValue, _>(mlua::Error::runtime(format!(
                "{path}.{name}: {path} is the API's, and can't be changed"
            )))
        })?,
    )?;
    meta.raw_set("__metatable", false)?;
    table.set_metatable(Some(meta))?;
    Ok(table)
}

/// What `path.name` is for `scope`: a function bound to it, a namespace under
/// it, or nil.
fn lookup(lua: &Lua, scope: ScopeId, path: &'static str, name: &str) -> mlua::Result<Value> {
    if let Some(binding) = BINDINGS
        .iter()
        .find(|b| !b.method && b.class == path && b.name == name)
    {
        let api = lua
            .app_data_ref::<Installed>()
            .map(|i| i.api.clone())
            .ok_or_else(|| mlua::Error::runtime("the API isn't installed"))?;
        let call = binding.call;
        let host = crate::handle::host(lua)
            .ok_or_else(|| mlua::Error::runtime("the API isn't installed"))?;
        let f = wrapped(&host, binding, move |lua, args: MultiValue| {
            let args: Vec<Value> = args.into_vec();
            call(lua, &*api, Some(scope), &args)
        })?;
        return Ok(Value::Function(f));
    }
    let child = format!("{path}.{name}");
    if let Some(binding) = BINDINGS.iter().find(|b| !b.method && b.class == child) {
        return namespace(lua, scope, binding.class).map(Value::Table);
    }
    Ok(Value::Nil)
}
