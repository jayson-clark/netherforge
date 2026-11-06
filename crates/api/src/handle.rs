//! Handles as userdata. A handle is one userdata type for every class: what it
//! is (its class, which can become more specific) and what identifies the
//! thing (its root class's key). The runtime keeps one userdata per thing (a
//! weak table by key), so two handles to the same thing are the same value and
//! `a == b` works. A userdata can't be `rawset`, so no script can plant a method
//! another's code calls; it's collected with the last script that holds it.
//! See the rust-runtime skill, "Handles".

use std::cell::Cell;
use std::collections::HashMap;

use mlua::{AnyUserData, Lua, MetaMethod, Table, UserData, UserDataFields, UserDataMethods, Value};
use netherforge_luau::{Host, WeakHost};

use crate::handles::{CHAINS, Handle};

/// One part of a handle's key: a UUID, a name, a number.
#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub enum KeyPart {
    Str(String),
    Int(i64),
}

/// What identifies a thing: its root class's key (one or two parts).
pub type Key = Vec<KeyPart>;

/// A handle class, as the generator writes it for each.
pub trait HandleClass: Sized {
    /// The class, as scripts spell it.
    const NAME: &'static str;
    /// The class at the top of its chain, whose key it has.
    const ROOT: &'static str;
    /// It and every class below it: what a handle taken as it may be.
    const CLASSES: &'static [&'static str];
    fn key(&self) -> Key;
    fn from_key(key: &[KeyPart]) -> Option<Self>;
}

/// A handle's userdata.
pub struct HandleData {
    class: Cell<&'static str>,
    root: &'static str,
    key: Key,
}

impl HandleData {
    pub fn class(&self) -> &'static str {
        self.class.get()
    }
    pub fn key(&self) -> &[KeyPart] {
        &self.key
    }
}

/// Each handle class's methods (its own and its chain's), set up once per
/// state by [`crate::install`].
#[derive(Default)]
pub struct Classes {
    pub methods: HashMap<&'static str, Table>,
}

impl UserData for HandleData {
    fn add_methods<M: UserDataMethods<Self>>(methods: &mut M) {
        methods.add_meta_method(MetaMethod::Index, |lua, this, name: Value| {
            let found = method(lua, this.class(), &name)?;
            // A handle of a class others extend may be one the runtime knew less about when it handed it out (a
            // `Player` that's a `Bot`): a method its class lacks asks what it is now, and looks again.
            if !found.is_nil() || !extended(this.class()) {
                return Ok(found);
            }
            match refine(lua, this) {
                Some(class) => method(lua, class, &name),
                None => Ok(Value::Nil),
            }
        });
        methods.add_meta_method(MetaMethod::NewIndex, |_, this, (_, _): (Value, Value)| {
            Err::<(), _>(mlua::Error::runtime(format!(
                "{} is shared by every script and can't be changed",
                a_or_an(&format!("{} handle", this.class()))
            )))
        });
        methods.add_meta_method(MetaMethod::Eq, |_, this, other: AnyUserData| {
            Ok(other
                .borrow::<HandleData>()
                .is_ok_and(|other| other.root == this.root && other.key == this.key))
        });
        methods.add_meta_method(MetaMethod::ToString, |_, this, ()| {
            let parts: Vec<String> = this
                .key
                .iter()
                .map(|part| match part {
                    KeyPart::Str(s) => s.clone(),
                    KeyPart::Int(i) => i.to_string(),
                })
                .collect();
            Ok(format!("{} {}", this.class(), parts.join(" ")))
        });
    }

    fn add_fields<F: UserDataFields<Self>>(fields: &mut F) {
        // `typeof(h)` says what it is. (mlua keeps `__metatable` for itself:
        // `getmetatable(h)` gives scripts nothing they can use.)
        fields.add_meta_field("__type", "handle");
    }
}

/// Method `name` of `class`, or nil.
fn method(lua: &Lua, class: &str, name: &Value) -> mlua::Result<Value> {
    let classes = lua.app_data_ref::<Classes>();
    match classes.as_ref().and_then(|c| c.methods.get(class)) {
        Some(table) => table.raw_get::<Value>(name.clone()),
        None => Ok(Value::Nil),
    }
}

/// Whether other classes extend `class` (an `Entity`, a `Player`): only then can the runtime know more of it.
fn extended(class: &str) -> bool {
    CHAINS
        .iter()
        .any(|chain| chain.len() > 1 && chain[1..].contains(&class))
}

/// Asks the runtime what `data` is now ([`crate::Runtime::refine`]), and makes it that class: its class then, when
/// it changed.
fn refine(lua: &Lua, data: &HandleData) -> Option<&'static str> {
    let class = crate::bind::refine(lua, data.class(), &data.key)?;
    data.class.set(class);
    Some(class)
}

/// A handle userdata as `H` once the runtime has said what it is now, when it was handed out as a class above
/// `H` (a `Player` where a `Bot` is wanted).
pub fn read_refined<H: HandleClass>(lua: &Lua, u: &AnyUserData) -> Option<H> {
    let data = u.borrow::<HandleData>().ok()?;
    if !is_a(H::NAME, data.class()) {
        return None;
    }
    refine(lua, &data)?;
    if !H::CLASSES.contains(&data.class()) {
        return None;
    }
    H::from_key(&data.key)
}

fn a_or_an(word: &str) -> String {
    let vowel = word.starts_with(['A', 'E', 'I', 'O', 'U', 'a', 'e', 'i', 'o', 'u']);
    format!("{} {word}", if vowel { "an" } else { "a" })
}

/// The class a userdata is, if it's a handle.
pub fn class_of(u: &AnyUserData) -> Option<&'static str> {
    u.borrow::<HandleData>().ok().map(|h| h.class())
}

/// Whether `class` is a kind of `member` (`Mob` is a kind of `Entity`).
pub fn is_a(class: &str, member: &str) -> bool {
    CHAINS
        .iter()
        .find(|chain| chain[0] == class)
        .is_some_and(|chain| chain.contains(&member))
}

/// A handle userdata as `H`, if it's one of `H`'s classes.
pub fn read<H: HandleClass>(u: &AnyUserData) -> Option<H> {
    let data = u.borrow::<HandleData>().ok()?;
    if !H::CLASSES.contains(&data.class()) {
        return None;
    }
    H::from_key(&data.key)
}

/// A handle userdata as whichever class it is.
pub fn read_any(u: &AnyUserData) -> Option<Handle> {
    let data = u.borrow::<HandleData>().ok()?;
    Handle::from_key(data.class(), &data.key)
}

const CACHE: &str = "netherforge.handles";

/// The weak table of every handle userdata by key, so a thing is one value.
fn cache(lua: &Lua) -> mlua::Result<Table> {
    if let Ok(t) = lua.named_registry_value::<Table>(CACHE) {
        return Ok(t);
    }
    let t = lua.create_table()?;
    let meta = lua.create_table()?;
    meta.raw_set("__mode", "v")?;
    t.set_metatable(Some(meta))?;
    lua.set_named_registry_value(CACHE, &t)?;
    Ok(t)
}

fn cache_key(root: &str, key: &[KeyPart]) -> String {
    let mut out = String::from(root);
    for part in key {
        out.push('\0');
        match part {
            KeyPart::Str(s) => out.push_str(s),
            KeyPart::Int(i) => {
                out.push('#');
                out.push_str(&i.to_string());
            }
        }
    }
    out
}

fn push_key(
    lua: &Lua,
    class: &'static str,
    root: &'static str,
    key: Key,
) -> mlua::Result<AnyUserData> {
    let cache = cache(lua)?;
    let name = cache_key(root, &key);
    if let Ok(Value::UserData(existing)) = cache.raw_get::<Value>(name.as_str())
        && let Ok(data) = existing.borrow::<HandleData>()
    {
        // Handed out again as a more specific class (an `Entity` that turned
        // out to be a `Mob`): the same value, now that class.
        if is_a(class, data.class()) {
            data.class.set(class);
        }
        drop(data);
        return Ok(existing);
    }
    let u = lua.create_userdata(HandleData {
        class: Cell::new(class),
        root,
        key,
    })?;
    cache.raw_set(name, &u)?;
    Ok(u)
}

/// `value` as its handle userdata: the one there already, if any.
pub fn push<H: HandleClass>(lua: &Lua, value: &H) -> mlua::Result<AnyUserData> {
    push_key(lua, H::NAME, H::ROOT, value.key())
}

/// Any handle as its userdata.
pub fn push_any(lua: &Lua, value: &Handle) -> mlua::Result<AnyUserData> {
    let class = value.class();
    let root = CHAINS
        .iter()
        .find(|chain| chain[0] == class)
        .and_then(|chain| chain.last())
        .copied()
        .unwrap_or(class);
    push_key(lua, class, root, value.key())
}

/// The host a state belongs to, as [`crate::install`] recorded it.
pub fn host(lua: &Lua) -> Option<Host> {
    lua.app_data_ref::<WeakHost>()?.upgrade()
}

/// Handles in saved tables and `nf.json` (the prelude's `saved_handles`): a saveable class (the spec's
/// `saveable`: each at the top of its chain) is written by its tag and key, `{"$entity":["<uuid>"]}`, so a
/// `Player` or a `Mob` is saved as the `Entity` it is and comes back as whatever the server then says it is.
/// Any other handle is a problem at its key path (`a Menu handle can't be saved`).
pub struct SavedHandles;

impl netherforge_luau::json::Tags for SavedHandles {
    fn tags(&self) -> &[&str] {
        static TAGS: std::sync::LazyLock<Vec<&'static str>> = std::sync::LazyLock::new(|| {
            crate::handles::SAVEABLE
                .iter()
                .map(|(_, tag)| *tag)
                .collect()
        });
        &TAGS
    }

    fn encode(&self, value: &Value) -> Option<Result<(String, Vec<serde_json::Value>), String>> {
        let Value::UserData(u) = value else {
            return None;
        };
        let data = u.borrow::<HandleData>().ok()?;
        let saved = crate::handles::SAVEABLE
            .iter()
            .find(|(class, _)| is_a(data.class(), class));
        Some(match saved {
            Some((_, tag)) => Ok((
                (*tag).to_owned(),
                data.key()
                    .iter()
                    .map(|part| match part {
                        KeyPart::Str(s) => serde_json::Value::String(s.clone()),
                        KeyPart::Int(i) => serde_json::Value::from(*i),
                    })
                    .collect(),
            )),
            None => Err(a_or_an(&format!("{} handle", data.class()))),
        })
    }

    fn revive(&self, lua: &Lua, tag: &str, parts: &[serde_json::Value]) -> Option<Value> {
        let (class, _) = crate::handles::SAVEABLE.iter().find(|(_, t)| *t == tag)?;
        // Each saved class is keyed by one string.
        let [serde_json::Value::String(key)] = parts else {
            return None;
        };
        push_key(lua, class, class, vec![KeyPart::Str(key.clone())])
            .ok()
            .map(Value::UserData)
    }
}
