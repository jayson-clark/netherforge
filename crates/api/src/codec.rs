//! How a value of the API's types crosses between Luau and Rust: one codec per
//! node of the type grammar (`packages/api/scripts/emit/bindings.ts`), the same
//! wherever the type appears. The Kotlin runtime's `LuaCodec.kt`, with its
//! messages.
//!
//! Codecs are zero-sized types composed at compile time
//! (`Opt<List<Str>>`), so a binding reads its arguments without building any.
//! The generator writes one for each shape, union and handle class.

use std::collections::BTreeMap;

use mlua::{Function, Lua, Table, Value};

use crate::handle::{self, HandleClass};
use crate::{ApiError, ApiResult, MAX_SAFE_INTEGER, values};

/// One node of the type grammar: how it's read from Luau and pushed back.
pub trait Codec {
    type T;
    /// What a mistake says was expected: `Vec3`, `list of Player`.
    fn expected(&self) -> String;
    /// Whether `value` is this type's kind of Luau value: what a union asks to
    /// pick a member.
    fn accepts(&self, value: &Value) -> bool;
    /// Reads `value`, naming `at` (`options.near`, `lines[2]`) in a mistake.
    fn read(&self, lua: &Lua, value: Value, at: &str) -> ApiResult<Self::T>;
    fn push(&self, lua: &Lua, value: Self::T) -> mlua::Result<Value>;
}

/// `bad argument 'at' (expected expected, got got)`.
pub fn mismatch(at: &str, expected: &str, got: &Value) -> ApiError {
    ApiError(format!(
        "bad argument '{at}' ({expected} expected, got {})",
        type_name(got)
    ))
}

/// What a mistake says a value is: its Luau type, or a handle's class.
pub fn type_name(value: &Value) -> String {
    match value {
        Value::Nil => "nil".into(),
        Value::Boolean(_) => "boolean".into(),
        Value::Integer(_) | Value::Number(_) => "number".into(),
        Value::Vector(_) => "Vec3".into(),
        Value::String(_) => "string".into(),
        Value::Table(_) => "table".into(),
        Value::Function(_) => "function".into(),
        Value::Thread(_) => "coroutine".into(),
        Value::UserData(u) => handle::class_of(u).map_or_else(|| "userdata".into(), str::to_owned),
        other => other.type_name().into(),
    }
}

fn number_of(value: &Value) -> Option<f64> {
    match value {
        Value::Integer(i) => Some(*i as f64),
        Value::Number(n) => Some(*n),
        _ => None,
    }
}

fn mlua_err(e: ApiError) -> mlua::Error {
    mlua::Error::runtime(e.0)
}

/// Runs `read`, naming `at` in a mistake that doesn't already.
fn naming<T>(at: &str, result: ApiResult<T>) -> ApiResult<T> {
    result.map_err(|e| {
        if e.0.starts_with("bad argument") || e.0.starts_with(at) {
            e
        } else {
            ApiError(format!("{at}: {}", e.0))
        }
    })
}

macro_rules! primitive {
    ($name:ident, $t:ty, $expected:literal, |$v:ident| $accepts:expr, |$lua:ident, $r:ident| $read:expr, |$plua:ident, $p:ident| $push:expr) => {
        #[derive(Clone, Copy, Default)]
        pub struct $name;
        impl Codec for $name {
            type T = $t;
            fn expected(&self) -> String {
                $expected.into()
            }
            fn accepts(&self, $v: &Value) -> bool {
                $accepts
            }
            fn read(&self, $lua: &Lua, $r: Value, at: &str) -> ApiResult<$t> {
                if !self.accepts(&$r) {
                    return Err(mismatch(at, $expected, &$r));
                }
                let _ = $lua;
                Ok($read)
            }
            fn push(&self, $plua: &Lua, $p: $t) -> mlua::Result<Value> {
                let _ = $plua;
                $push
            }
        }
    };
}

primitive!(
    Str,
    String,
    "string",
    |v| matches!(v, Value::String(_)),
    |lua, v| match v {
        Value::String(s) => s.to_string_lossy(),
        _ => unreachable!(),
    },
    |lua, v| lua.create_string(v).map(Value::String)
);

primitive!(
    Num,
    f64,
    "number",
    |v| number_of(v).is_some(),
    |lua, v| number_of(&v).expect("accepted"),
    |lua, v| Ok(Value::Number(v))
);

primitive!(
    Bool,
    bool,
    "boolean",
    |v| matches!(v, Value::Boolean(_)),
    |lua, v| matches!(v, Value::Boolean(true)),
    |lua, v| Ok(Value::Boolean(v))
);

primitive!(
    Func,
    Function,
    "function",
    |v| matches!(v, Value::Function(_)),
    |lua, v| match v {
        Value::Function(f) => f,
        _ => unreachable!(),
    },
    |lua, v| Ok(Value::Function(v))
);

/// `integer`: a whole number in the safe range (see "Numbers").
#[derive(Clone, Copy, Default)]
pub struct Int;
impl Codec for Int {
    type T = i64;
    fn expected(&self) -> String {
        "whole number".into()
    }
    fn accepts(&self, value: &Value) -> bool {
        number_of(value).is_some_and(|n| n == n.trunc() && n.abs() <= MAX_SAFE_INTEGER)
    }
    fn read(&self, _: &Lua, value: Value, at: &str) -> ApiResult<i64> {
        match number_of(&value) {
            Some(n) if n == n.trunc() && n.abs() <= MAX_SAFE_INTEGER => Ok(n as i64),
            Some(n) if n.is_finite() && n == n.trunc() => Err(ApiError(format!(
                "bad argument '{at}' (whole number expected, got {n}, past the largest a number holds exactly)"
            ))),
            Some(n) => Err(ApiError(format!(
                "bad argument '{at}' (whole number expected, got {n})"
            ))),
            None => Err(mismatch(at, "whole number", &value)),
        }
    }
    fn push(&self, _: &Lua, value: i64) -> mlua::Result<Value> {
        Ok(Value::Number(value as f64))
    }
}

/// `any` (or `table`, checked to be one): the Luau value itself.
#[derive(Clone, Copy, Default)]
pub struct Any {
    pub table: bool,
}
impl Codec for Any {
    type T = Value;
    fn expected(&self) -> String {
        if self.table { "table" } else { "any" }.into()
    }
    fn accepts(&self, value: &Value) -> bool {
        !self.table || matches!(value, Value::Table(_))
    }
    fn read(&self, _: &Lua, value: Value, at: &str) -> ApiResult<Value> {
        if !self.accepts(&value) {
            return Err(mismatch(at, "table", &value));
        }
        Ok(value)
    }
    fn push(&self, _: &Lua, value: Value) -> mlua::Result<Value> {
        Ok(value)
    }
}

/// `any`.
pub const ANY: Any = Any { table: false };
/// `table`.
pub const TABLE: Any = Any { table: true };

/// A union of string literals: a string that must be one of them.
#[derive(Clone, Copy)]
pub struct Choice(pub &'static [&'static str]);
impl Codec for Choice {
    type T = String;
    fn expected(&self) -> String {
        let quoted: Vec<String> = self.0.iter().map(|c| format!("\"{c}\"")).collect();
        format!("one of {}", quoted.join(", "))
    }
    fn accepts(&self, value: &Value) -> bool {
        matches!(value, Value::String(s) if self.0.iter().any(|c| s.as_bytes() == c.as_bytes()))
    }
    fn read(&self, _: &Lua, value: Value, at: &str) -> ApiResult<String> {
        let Value::String(s) = &value else {
            return Err(mismatch(at, &self.expected(), &value));
        };
        let text = s.to_string_lossy();
        if !self.0.contains(&text.as_str()) {
            return Err(ApiError(format!(
                "bad argument '{at}' ({} expected, got \"{text}\")",
                self.expected()
            )));
        }
        Ok(text)
    }
    fn push(&self, lua: &Lua, value: String) -> mlua::Result<Value> {
        lua.create_string(value).map(Value::String)
    }
}

/// `T?`: nil, or a `T`.
#[derive(Clone, Copy, Default)]
pub struct Opt<C>(pub C);
impl<C: Codec> Codec for Opt<C> {
    type T = Option<C::T>;
    fn expected(&self) -> String {
        format!("{} or nil", self.0.expected())
    }
    fn accepts(&self, value: &Value) -> bool {
        value.is_nil() || self.0.accepts(value)
    }
    fn read(&self, lua: &Lua, value: Value, at: &str) -> ApiResult<Self::T> {
        if value.is_nil() {
            return Ok(None);
        }
        // The value itself being wrong (not something inside it) says nil would have
        // done too.
        let inner = format!("bad argument '{at}' ({} expected, got ", self.0.expected());
        self.0
            .read(lua, value, at)
            .map(Some)
            .map_err(|e| match e.0.strip_prefix(&inner) {
                Some(got) => ApiError(format!(
                    "bad argument '{at}' ({} expected, got {got}",
                    self.expected()
                )),
                None => e,
            })
    }
    fn push(&self, lua: &Lua, value: Self::T) -> mlua::Result<Value> {
        match value {
            Some(v) => self.0.push(lua, v),
            None => Ok(Value::Nil),
        }
    }
}

/// A plain table: no metatable (a handle, a shared value, a script's object).
fn plain_table(value: &Value) -> Option<&Table> {
    match value {
        Value::Table(t) if t.metatable().is_none() => Some(t),
        _ => None,
    }
}

/// `T[]`: a table numbered from 1. An empty table is the empty list.
#[derive(Clone, Copy, Default)]
pub struct List<C>(pub C);
impl<C: Codec> Codec for List<C> {
    type T = Vec<C::T>;
    fn expected(&self) -> String {
        format!("list of {}", self.0.expected())
    }
    fn accepts(&self, value: &Value) -> bool {
        plain_table(value).is_some()
    }
    fn read(&self, lua: &Lua, value: Value, at: &str) -> ApiResult<Self::T> {
        let Some(t) = plain_table(&value) else {
            return Err(mismatch(at, &self.expected(), &value));
        };
        let length = t.raw_len();
        // Every key must be a position: a table with names in it isn't a list.
        let count = t.pairs::<Value, Value>().count();
        if count != length {
            return Err(ApiError(format!(
                "bad argument '{at}' ({} expected, got a table with other keys)",
                self.expected()
            )));
        }
        (1..=length)
            .map(|k| {
                let item: Value = t.raw_get(k).unwrap_or(Value::Nil);
                self.0.read(lua, item, &format!("{at}[{k}]"))
            })
            .collect()
    }
    fn push(&self, lua: &Lua, value: Self::T) -> mlua::Result<Value> {
        let t = lua.create_table_with_capacity(value.len(), 0)?;
        for (k, item) in value.into_iter().enumerate() {
            t.raw_set(k + 1, self.0.push(lua, item)?)?;
        }
        Ok(Value::Table(t))
    }
}

/// `table<K, V>`: keys of `K` (strings or whole numbers), each value a `V`.
#[derive(Clone, Copy, Default)]
pub struct Map<K, V>(pub K, pub V);
impl<K: Codec, V: Codec> Codec for Map<K, V>
where
    K::T: Ord,
{
    type T = BTreeMap<K::T, V::T>;
    fn expected(&self) -> String {
        format!("table of {} to {}", self.0.expected(), self.1.expected())
    }
    fn accepts(&self, value: &Value) -> bool {
        plain_table(value).is_some()
    }
    fn read(&self, lua: &Lua, value: Value, at: &str) -> ApiResult<Self::T> {
        let Some(t) = plain_table(&value) else {
            return Err(mismatch(at, &self.expected(), &value));
        };
        let mut out = BTreeMap::new();
        for pair in t.pairs::<Value, Value>() {
            let (key, item) = pair.map_err(|e| ApiError(e.to_string()))?;
            if !self.0.accepts(&key) {
                return Err(ApiError(format!(
                    "bad argument '{at}' ({} keys expected, got a {} key)",
                    self.0.expected(),
                    type_name(&key)
                )));
            }
            let item_at = match &key {
                Value::String(s) if is_name(&s.to_string_lossy()) => {
                    format!("{at}.{}", s.to_string_lossy())
                }
                Value::String(s) => format!("{at}[\"{}\"]", s.to_string_lossy()),
                other => format!(
                    "{at}[{}]",
                    number_of(other).map_or_else(|| type_name(other), |n| format!("{n}"))
                ),
            };
            let k = self.0.read(lua, key, at)?;
            out.insert(k, self.1.read(lua, item, &item_at)?);
        }
        Ok(out)
    }
    fn push(&self, lua: &Lua, value: Self::T) -> mlua::Result<Value> {
        let t = lua.create_table_with_capacity(0, value.len())?;
        for (k, v) in value {
            t.raw_set(self.0.push(lua, k)?, self.1.push(lua, v)?)?;
        }
        Ok(Value::Table(t))
    }
}

/// `options.near`, not `options["near"]`.
pub fn is_name(key: &str) -> bool {
    let mut chars = key.chars();
    chars
        .next()
        .is_some_and(|c| c.is_ascii_alphabetic() || c == '_')
        && chars.all(|c| c.is_ascii_alphanumeric() || c == '_')
}

/// A handle class's handles: the class and every class below it.
pub struct HandleOf<H>(std::marker::PhantomData<H>);
impl<H> Default for HandleOf<H> {
    fn default() -> Self {
        HandleOf(std::marker::PhantomData)
    }
}
impl<H> Clone for HandleOf<H> {
    fn clone(&self) -> Self {
        *self
    }
}
impl<H> Copy for HandleOf<H> {}
impl<H: HandleClass> Codec for HandleOf<H> {
    type T = H;
    fn expected(&self) -> String {
        H::NAME.into()
    }
    fn accepts(&self, value: &Value) -> bool {
        match value {
            Value::UserData(u) => handle::class_of(u).is_some_and(|c| H::CLASSES.contains(&c)),
            _ => false,
        }
    }
    fn read(&self, lua: &Lua, value: Value, at: &str) -> ApiResult<H> {
        if let Value::UserData(u) = &value
            && let Some(h) = handle::read::<H>(u).or_else(|| handle::read_refined::<H>(lua, u))
        {
            return Ok(h);
        }
        Err(mismatch(at, H::NAME, &value))
    }
    fn push(&self, lua: &Lua, value: H) -> mlua::Result<Value> {
        handle::push(lua, &value).map(Value::UserData)
    }
}

/// A union of handle classes only: whichever handle it is, pushed back as the
/// class it is.
#[derive(Clone, Copy)]
pub struct AnyHandle(pub &'static [&'static str]);
impl Codec for AnyHandle {
    type T = crate::handles::Handle;
    fn expected(&self) -> String {
        or_list(self.0.iter().map(|s| s.to_string()).collect())
    }
    fn accepts(&self, value: &Value) -> bool {
        match value {
            Value::UserData(u) => handle::class_of(u)
                .is_some_and(|c| self.0.iter().any(|member| crate::handle::is_a(c, member))),
            _ => false,
        }
    }
    fn read(&self, _: &Lua, value: Value, at: &str) -> ApiResult<Self::T> {
        if self.accepts(&value)
            && let Value::UserData(u) = &value
            && let Some(h) = handle::read_any(u)
        {
            return Ok(h);
        }
        Err(mismatch(at, &self.expected(), &value))
    }
    fn push(&self, lua: &Lua, value: Self::T) -> mlua::Result<Value> {
        handle::push_any(lua, &value).map(Value::UserData)
    }
}

/// `a`, `a or b`, `a, b or c`.
pub fn or_list(names: Vec<String>) -> String {
    match names.split_last() {
        Some((last, [])) => last.clone(),
        Some((last, rest)) => format!("{} or {last}", rest.join(", ")),
        None => String::new(),
    }
}

/// `Vec3`: a native `vector` (see "Numbers"). Its components are 32-bit floats.
#[derive(Clone, Copy, Default)]
pub struct Vec3Codec;
impl Codec for Vec3Codec {
    type T = values::Vec3;
    fn expected(&self) -> String {
        "Vec3".into()
    }
    fn accepts(&self, value: &Value) -> bool {
        matches!(value, Value::Vector(_))
    }
    fn read(&self, _: &Lua, value: Value, at: &str) -> ApiResult<values::Vec3> {
        match value {
            Value::Vector(v) => Ok(values::Vec3 {
                x: v.x() as f64,
                y: v.y() as f64,
                z: v.z() as f64,
            }),
            other => Err(mismatch(at, "Vec3", &other)),
        }
    }
    fn push(&self, _: &Lua, v: values::Vec3) -> mlua::Result<Value> {
        Ok(Value::Vector(mlua::Vector::new(
            v.x as f32, v.y as f32, v.z as f32,
        )))
    }
}

/// `Text`: a string, its glyph tags spelled in the server's terms. The
/// spelling is ported with resource packs; until then it crosses as it is.
#[derive(Clone, Copy, Default)]
pub struct TextCodec;
impl Codec for TextCodec {
    type T = values::Text;
    fn expected(&self) -> String {
        "string".into()
    }
    fn accepts(&self, value: &Value) -> bool {
        Str.accepts(value)
    }
    fn read(&self, lua: &Lua, value: Value, at: &str) -> ApiResult<values::Text> {
        Str.read(lua, value, at).map(values::Text)
    }
    fn push(&self, lua: &Lua, value: values::Text) -> mlua::Result<Value> {
        Str.push(lua, value.0)
    }
}

/// `Location`: a table `{ world = World, position = Vec3, yaw = number?, pitch = number? }`.
/// The roadmap's `Position` (64-bit components) replaces its position when
/// the spec changes; until then it's a `Vec3`.
#[derive(Clone, Copy, Default)]
pub struct LocationCodec;
impl Codec for LocationCodec {
    type T = values::Location;
    fn expected(&self) -> String {
        "Location".into()
    }
    fn accepts(&self, value: &Value) -> bool {
        matches!(value, Value::Table(t) if t.raw_get::<Value>("world").is_ok_and(|w| HandleOf::<crate::handles::World>::default().accepts(&w)))
    }
    fn read(&self, lua: &Lua, value: Value, at: &str) -> ApiResult<values::Location> {
        let Value::Table(t) = &value else {
            return Err(mismatch(at, "Location", &value));
        };
        let field = |name: &str| t.raw_get::<Value>(name).unwrap_or(Value::Nil);
        Ok(values::Location {
            world: HandleOf::<crate::handles::World>::default().read(
                lua,
                field("world"),
                &format!("{at}.world"),
            )?,
            position: Vec3Codec.read(lua, field("position"), &format!("{at}.position"))?,
            yaw: Opt(Num).read(lua, field("yaw"), &format!("{at}.yaw"))?,
            pitch: Opt(Num).read(lua, field("pitch"), &format!("{at}.pitch"))?,
        })
    }
    fn push(&self, lua: &Lua, value: values::Location) -> mlua::Result<Value> {
        let t = lua.create_table()?;
        t.raw_set(
            "world",
            HandleOf::<crate::handles::World>::default().push(lua, value.world)?,
        )?;
        t.raw_set("position", Vec3Codec.push(lua, value.position)?)?;
        t.raw_set("yaw", value.yaw)?;
        t.raw_set("pitch", value.pitch)?;
        Ok(Value::Table(t))
    }
}

/// `Item` and `ItemMatch`: a table, kept as the codec's JSON until items are
/// ported.
#[derive(Clone, Copy, Default)]
pub struct ItemCodec;
impl Codec for ItemCodec {
    type T = values::Item;
    fn expected(&self) -> String {
        "Item".into()
    }
    fn accepts(&self, value: &Value) -> bool {
        matches!(value, Value::Table(_))
    }
    fn read(&self, lua: &Lua, value: Value, at: &str) -> ApiResult<values::Item> {
        json_of(lua, &value, at, "Item").map(values::Item)
    }
    fn push(&self, lua: &Lua, value: values::Item) -> mlua::Result<Value> {
        netherforge_luau::json::decode(lua, &value.0, &netherforge_luau::json::NoTags)
            .map_err(mlua::Error::runtime)
    }
}

#[derive(Clone, Copy, Default)]
pub struct ItemMatchCodec;
impl Codec for ItemMatchCodec {
    type T = values::ItemMatch;
    fn expected(&self) -> String {
        "ItemMatch".into()
    }
    fn accepts(&self, value: &Value) -> bool {
        matches!(value, Value::Table(_) | Value::String(_))
    }
    fn read(&self, lua: &Lua, value: Value, at: &str) -> ApiResult<values::ItemMatch> {
        json_of(lua, &value, at, "ItemMatch").map(values::ItemMatch)
    }
    fn push(&self, _: &Lua, _: values::ItemMatch) -> mlua::Result<Value> {
        Err(mlua::Error::runtime("an item match can't be handed to Lua"))
    }
}

fn json_of(lua: &Lua, value: &Value, at: &str, expected: &str) -> ApiResult<String> {
    if !matches!(value, Value::Table(_) | Value::String(_)) {
        return Err(mismatch(at, expected, value));
    }
    let host = crate::handle::host(lua).ok_or_else(|| ApiError("no host".into()))?;
    let encoded =
        netherforge_luau::json::encode(&host, value, at, "read", &netherforge_luau::json::NoTags)
            .map_err(|stopped| ApiError(stopped.0.to_string()))?;
    if let Some(problem) = encoded.problems.into_iter().next() {
        return Err(ApiError(format!("bad argument '{at}' ({problem})")));
    }
    encoded.text.ok_or_else(|| mismatch(at, expected, value))
}

/// Reads a shape's table strictly: a key that isn't one of its fields is a
/// mistake. Generated shape codecs call this first.
pub fn shape_table<'a>(
    value: &'a Value,
    at: &str,
    expected: &str,
    fields: &[&str],
) -> ApiResult<&'a Table> {
    let Some(t) = plain_table(value) else {
        return Err(mismatch(at, expected, value));
    };
    for pair in t.pairs::<Value, Value>() {
        let (key, _) = pair.map_err(|e| ApiError(e.to_string()))?;
        let known =
            matches!(&key, Value::String(s) if fields.iter().any(|f| s.as_bytes() == f.as_bytes()));
        if !known {
            let name = match &key {
                Value::String(s) => s.to_string_lossy(),
                other => type_name(other),
            };
            let mut sorted = fields.to_vec();
            sorted.sort_unstable();
            return Err(ApiError(format!(
                "unknown field '{at}.{name}' (fields: {})",
                sorted.join(", ")
            )));
        }
    }
    Ok(t)
}

/// A shape's field, read with its codec and named `at.field`.
pub fn field<C: Codec>(lua: &Lua, t: &Table, at: &str, name: &str, codec: C) -> ApiResult<C::T> {
    let value: Value = t.raw_get(name).unwrap_or(Value::Nil);
    let at = format!("{at}.{name}");
    naming(&at, codec.read(lua, value, &at))
}

/// Sets a shape's field when it's there (a `None` leaves it out).
pub fn set_field<C: Codec>(
    lua: &Lua,
    t: &Table,
    name: &str,
    codec: C,
    value: C::T,
) -> mlua::Result<()> {
    let pushed = codec.push(lua, value)?;
    if !pushed.is_nil() {
        t.raw_set(name, pushed)?;
    }
    Ok(())
}

/// An argument of a binding: the `index`th value, named `name`.
pub fn arg<C: Codec>(
    lua: &Lua,
    args: &[Value],
    index: usize,
    name: &str,
    codec: C,
) -> mlua::Result<C::T> {
    let value = args.get(index).cloned().unwrap_or(Value::Nil);
    codec.read(lua, value, name).map_err(mlua_err)
}

/// A binding's result, pushed with its codec.
pub fn result<C: Codec>(lua: &Lua, codec: C, value: ApiResult<C::T>) -> mlua::Result<Value> {
    let value = value.map_err(mlua_err)?;
    codec.push(lua, value)
}

/// A shape inside itself, behind a box so the struct has a size.
#[derive(Clone, Copy, Default)]
pub struct Boxed<C>(pub C);
impl<C: Codec> Codec for Boxed<C> {
    type T = Box<C::T>;
    fn expected(&self) -> String {
        self.0.expected()
    }
    fn accepts(&self, value: &Value) -> bool {
        self.0.accepts(value)
    }
    fn read(&self, lua: &Lua, value: Value, at: &str) -> ApiResult<Self::T> {
        self.0.read(lua, value, at).map(Box::new)
    }
    fn push(&self, lua: &Lua, value: Self::T) -> mlua::Result<Value> {
        self.0.push(lua, *value)
    }
}
