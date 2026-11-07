//! The spec's value types as scripts hold them (`packages/api/src/spec/values.ts`; the Kotlin prelude's
//! `values.lua`): `Vec3` is Luau's native `vector`, its methods and `tostring` given by the vector type's
//! metatable; `Location` is a table of `world`, `position`, `yaw` and `pitch` under a metatable with its methods,
//! `==` and `tostring`; and every scope has a `vec3` library, callable to build a vector, with the common ones and
//! `vec3.from_yaw_pitch`. A `Location` keeps its fields in slots 1 to 4 (its names are `__index`'s), so assigning
//! to one is `__newindex`'s error.
//!
//! The operators are the VM's own on vectors (`+`, `-`, `*` by a number or a vector, `/`, unary `-`, `==`), so
//! vector maths makes no call at all. Assigning to `v.x` is the VM's error, as vectors have no fields to set.

use mlua::{Lua, Table, Value};

/// The registry's name for `Location`'s metatable.
const LOCATION: &str = "netherforge.Location";
/// The registry's name for the `vec3` library's metatable.
const LIBRARY: &str = "netherforge.vec3";

/// The method bodies, in Luau: they run with every argument checked here, and a mistake is an error at level 2,
/// the script's line.
const VALUES: &str = r#"
local sqrt, floor, sin, cos, rad, abs = math.sqrt, math.floor, math.sin, math.cos, math.rad, math.abs
local format, tostring, typeof, error, setmetatable, next, type, rawget =
  string.format, tostring, typeof, error, setmetatable, next, type, rawget
local new = vector.create
local world_name = ...

local function kind(value)
  local t = typeof(value)
  if t == "vector" then
    return "Vec3"
  end
  return t
end

local function want_vector(value, name)
  if typeof(value) ~= "vector" then
    error(format("bad argument '%s' (Vec3 expected, got %s)", name, kind(value)), 3)
  end
end

local function want_number(value, name)
  if type(value) ~= "number" then
    error(format("bad argument '%s' (number expected, got %s)", name, kind(value)), 3)
  end
end

local function want_self(value, type_name, hint)
  local ok = if type_name == "Vec3" then typeof(value) == "vector" else getmetatable(value) == "Location"
  if not ok then
    error(format("call %s methods with ':' on a %s, like %s", type_name, type_name, hint), 3)
  end
end

local function vself(v)
  want_self(v, "Vec3", "v:length()")
end

-- A number as tostring shows it in a vector: whole numbers without a point.
local function number_text(n)
  if n == floor(n) and abs(n) < 2 ^ 53 then
    return format("%d", n)
  end
  return tostring(n)
end

local function from_yaw_pitch(yaw, pitch)
  local y, p = rad(yaw), rad(pitch)
  return new(-sin(y) * cos(p), -sin(p), cos(y) * cos(p))
end

local Vec3 = {}

function Vec3.length(v)
  vself(v)
  return sqrt(v.x * v.x + v.y * v.y + v.z * v.z)
end

function Vec3.length_squared(v)
  vself(v)
  return v.x * v.x + v.y * v.y + v.z * v.z
end

function Vec3.normalized(v)
  vself(v)
  local length = sqrt(v.x * v.x + v.y * v.y + v.z * v.z)
  if length == 0 then
    return new(0, 0, 0)
  end
  return new(v.x / length, v.y / length, v.z / length)
end

function Vec3.dot(a, b)
  vself(a)
  want_vector(b, "other")
  return a.x * b.x + a.y * b.y + a.z * b.z
end

function Vec3.cross(a, b)
  vself(a)
  want_vector(b, "other")
  return new(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)
end

function Vec3.distance(a, b)
  vself(a)
  want_vector(b, "other")
  local dx, dy, dz = a.x - b.x, a.y - b.y, a.z - b.z
  return sqrt(dx * dx + dy * dy + dz * dz)
end

function Vec3.distance_squared(a, b)
  vself(a)
  want_vector(b, "other")
  local dx, dy, dz = a.x - b.x, a.y - b.y, a.z - b.z
  return dx * dx + dy * dy + dz * dz
end

function Vec3.lerp(a, b, f)
  vself(a)
  want_vector(b, "other")
  want_number(f, "t")
  return new(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f, a.z + (b.z - a.z) * f)
end

function Vec3.clamp_length(v, max_length)
  vself(v)
  want_number(max_length, "max_length")
  if max_length < 0 then
    error("bad argument 'max_length' (a length can't be negative)", 2)
  end
  local squared = v.x * v.x + v.y * v.y + v.z * v.z
  if squared <= max_length * max_length then
    return v
  end
  local scale = max_length / sqrt(squared)
  return new(v.x * scale, v.y * scale, v.z * scale)
end

function Vec3.reflect(v, normal)
  vself(v)
  want_vector(normal, "normal")
  local length = sqrt(normal.x * normal.x + normal.y * normal.y + normal.z * normal.z)
  if length == 0 then
    error("bad argument 'normal' (a surface's normal can't be zero)", 2)
  end
  local nx, ny, nz = normal.x / length, normal.y / length, normal.z / length
  local twice = 2 * (v.x * nx + v.y * ny + v.z * nz)
  return new(v.x - twice * nx, v.y - twice * ny, v.z - twice * nz)
end

function Vec3.closest_on_segment(p, start, finish)
  vself(p)
  want_vector(start, "start")
  want_vector(finish, "finish")
  local dx, dy, dz = finish.x - start.x, finish.y - start.y, finish.z - start.z
  local squared = dx * dx + dy * dy + dz * dz
  if squared == 0 then
    return start
  end
  local t = ((p.x - start.x) * dx + (p.y - start.y) * dy + (p.z - start.z) * dz) / squared
  if t < 0 then
    t = 0
  elseif t > 1 then
    t = 1
  end
  return new(start.x + dx * t, start.y + dy * t, start.z + dz * t)
end

function Vec3.flat(v)
  vself(v)
  return new(v.x, 0, v.z)
end

function Vec3.with_x(v, x)
  vself(v)
  want_number(x, "x")
  return new(x, v.y, v.z)
end

function Vec3.with_y(v, y)
  vself(v)
  want_number(y, "y")
  return new(v.x, y, v.z)
end

function Vec3.with_z(v, z)
  vself(v)
  want_number(z, "z")
  return new(v.x, v.y, z)
end

function Vec3.floor(v)
  vself(v)
  return new(floor(v.x), floor(v.y), floor(v.z))
end

function Vec3.round(v)
  vself(v)
  return new(floor(v.x + 0.5), floor(v.y + 0.5), floor(v.z + 0.5))
end

-- Rodrigues' formula: v cos θ + (k × v) sin θ + k (k · v)(1 − cos θ), for a unit axis k.
function Vec3.rotated(v, axis, degrees)
  vself(v)
  want_vector(axis, "axis")
  want_number(degrees, "degrees")
  local length = sqrt(axis.x * axis.x + axis.y * axis.y + axis.z * axis.z)
  if length == 0 then
    error("bad argument 'axis' (a rotation needs an axis that isn't zero)", 2)
  end
  local kx, ky, kz = axis.x / length, axis.y / length, axis.z / length
  local c, s = cos(rad(degrees)), sin(rad(degrees))
  local dot = (kx * v.x + ky * v.y + kz * v.z) * (1 - c)
  return new(
    v.x * c + (ky * v.z - kz * v.y) * s + kx * dot,
    v.y * c + (kz * v.x - kx * v.z) * s + ky * dot,
    v.z * c + (kx * v.y - ky * v.x) * s + kz * dot
  )
end

function Vec3.unpack(v)
  vself(v)
  return v.x, v.y, v.z
end

local function vector_text(v)
  return "vec3(" .. number_text(v.x) .. ", " .. number_text(v.y) .. ", " .. number_text(v.z) .. ")"
end

local vector_mt = {
  __index = Vec3,
  __tostring = vector_text,
  __metatable = "Vec3",
}

local Location = {}
local location_mt

local function lself(l)
  want_self(l, "Location", "location:offset(v)")
end

local function new_location(world, position, yaw, pitch)
  return setmetatable({ world, position, yaw, pitch }, location_mt)
end

function Location.with_position(l, position)
  lself(l)
  want_vector(position, "position")
  return new_location(l.world, position, l.yaw, l.pitch)
end

function Location.offset(l, offset)
  lself(l)
  want_vector(offset, "offset")
  return new_location(l.world, l.position + offset, l.yaw, l.pitch)
end

function Location.direction(l)
  lself(l)
  if l.yaw == nil then
    return nil
  end
  return from_yaw_pitch(l.yaw, l.pitch or 0)
end

local location_fields = { world = 1, position = 2, yaw = 3, pitch = 4 }

location_mt = {
  __index = function(l, key)
    local i = location_fields[key]
    if i ~= nil then
      return rawget(l, i)
    end
    return Location[key]
  end,
  __newindex = function(_, key)
    error(
      format("locations can't be changed (setting '%s'): make a new one, like location:with_position(v)", tostring(key)),
      2
    )
  end,
  __eq = function(a, b)
    return a.world == b.world and a.position == b.position and a.yaw == b.yaw and a.pitch == b.pitch
  end,
  __tostring = function(l)
    local text = format("location(%q, %s", world_name(l.world), vector_text(l.position))
    if l.yaw ~= nil then
      text = text .. ", " .. number_text(l.yaw) .. ", " .. number_text(l.pitch or 0)
    end
    return text .. ")"
  end,
  __metatable = "Location",
}

local library = {
  zero = new(0, 0, 0),
  one = new(1, 1, 1),
  up = new(0, 1, 0),
  down = new(0, -1, 0),
  north = new(0, 0, -1),
  south = new(0, 0, 1),
  east = new(1, 0, 0),
  west = new(-1, 0, 0),
  from_yaw_pitch = function(yaw, pitch)
    want_number(yaw, "yaw")
    want_number(pitch, "pitch")
    return from_yaw_pitch(yaw, pitch)
  end,
}

local library_mt = {
  __index = library,
  __call = function(_, x, y, z)
    want_number(x, "x")
    want_number(y, "y")
    want_number(z, "z")
    return new(x, y, z)
  end,
  __newindex = function()
    error("vec3 is shared by every script and can't be changed", 2)
  end,
  __iter = function()
    return next, library
  end,
  __metatable = false,
}

return vector_mt, location_mt, library_mt
"#;

/// Puts the value types into `lua`: the vector type's metatable, and `Location`'s and the `vec3` library's kept
/// for [`location_metatable`] and [`vec3_for`].
pub fn install(lua: &Lua) -> mlua::Result<()> {
    let world_name = lua.create_function(|_, world: Value| {
        let name = match &world {
            Value::UserData(u) => crate::handle::read::<crate::handles::World>(u).map(|w| w.name),
            _ => None,
        };
        Ok(name.unwrap_or_else(|| "?".to_owned()))
    })?;
    let (vector, location, library): (Table, Table, Table) =
        lua.load(VALUES).set_name("=nf/values").call(world_name)?;
    lua.set_type_metatable::<mlua::Vector>(Some(vector));
    lua.set_named_registry_value(LOCATION, location)?;
    lua.set_named_registry_value(LIBRARY, library)?;
    Ok(())
}

/// `Location`'s metatable, once [`install`] ran.
pub fn location_metatable(lua: &Lua) -> Option<Table> {
    lua.named_registry_value::<Option<Table>>(LOCATION)
        .ok()
        .flatten()
}

/// A scope's own `vec3`: an empty table over the shared library, so `rawset` on it reaches only that scope.
pub fn vec3_for(lua: &Lua) -> mlua::Result<Table> {
    let table = lua.create_table()?;
    let meta: Table = lua.named_registry_value(LIBRARY)?;
    table.set_metatable(Some(meta))?;
    Ok(table)
}

/// `{"$location":[world,x,y,z,yaw,pitch]}`'s parts for a `Location` (the world by name; yaw and pitch null when
/// unset), or `None` for any other table.
pub fn encode_location(t: &Table) -> Option<Vec<serde_json::Value>> {
    let meta = t.metatable()?;
    if meta
        .raw_get::<Option<String>>("__metatable")
        .ok()?
        .as_deref()
        != Some("Location")
    {
        return None;
    }
    let Value::UserData(world) = t.raw_get::<Value>(1).ok()? else {
        return None;
    };
    let world = crate::handle::read::<crate::handles::World>(&world)?;
    let Value::Vector(p) = t.raw_get::<Value>(2).ok()? else {
        return None;
    };
    // Whole numbers as integers, as json writes them everywhere else.
    let whole = |n: f64| match netherforge_luau::json::number(n) {
        Some(text) => serde_json::from_str(&text).unwrap_or(serde_json::Value::Null),
        None => serde_json::Value::Null,
    };
    let number = |n: Option<f64>| n.map_or(serde_json::Value::Null, whole);
    Some(vec![
        serde_json::Value::String(world.name),
        whole(f64::from(p.x())),
        whole(f64::from(p.y())),
        whole(f64::from(p.z())),
        number(t.raw_get::<Option<f64>>(3).ok()?),
        number(t.raw_get::<Option<f64>>(4).ok()?),
    ])
}

/// The `Location` a `$location` tag's parts stand for.
pub fn revive_location(lua: &Lua, parts: &[serde_json::Value]) -> Option<Value> {
    let [serde_json::Value::String(world), x, y, z, rest @ ..] = parts else {
        return None;
    };
    let (yaw, pitch) = match rest {
        [] => (None, None),
        [yaw] => (yaw.as_f64(), None),
        [yaw, pitch] => (yaw.as_f64(), pitch.as_f64()),
        _ => return None,
    };
    let location = crate::values::Location {
        world: crate::handles::World {
            name: world.clone(),
        },
        position: crate::values::Vec3 {
            x: x.as_f64()?,
            y: y.as_f64()?,
            z: z.as_f64()?,
        },
        yaw,
        pitch,
    };
    crate::codec::Codec::push(&crate::codec::LocationCodec, lua, location).ok()
}
