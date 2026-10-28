-- Vec3 and Location: immutable values scripts build and compute with, as
-- opposed to handles to things in the server. Each is a table whose numbers
-- (or parts) sit in its array part and whose fields are read through
-- __index, so `v.x` works and `v.x = 1` errors.
--
-- They cross into Kotlin as themselves: Kotlin tells them by their metatables
-- (`kinds`) and reads their array parts. Only a `Vec3` parameter or return
-- crosses as its three numbers, checked and rebuilt by the bindings themselves,
-- since transforms are set and read every tick. A value is genuine when its
-- real metatable is ours (raw_getmetatable), which a script can't fake by
-- setting `__metatable`.
--
-- The methods scripts call are the generated wrappers (bindings.lua), which
-- check the arguments and call the bodies here (`body`). `Vec3` and `Location`
-- are the tables the wrappers go in, which `__index` reads.

local std = require("std")
local input = require("input")
local args = require("args")
local handles = require("handles")

local prim = input.prim
local raw_getmetatable = std.raw_getmetatable
local format, floor, sqrt, sin, cos, rad, abs =
  std.format, std.floor, std.sqrt, std.sin, std.cos, std.rad, std.abs
local type, error, tostring, setmetatable, next = type, error, tostring, setmetatable, next
local bad, want_number = args.bad, args.want_number

---@class nf.Vec3: {[1]: number, [2]: number, [3]: number}
---@field x number
---@field y number
---@field z number

---@class nf.Location: {[1]: table, [2]: nf.Vec3, [3]: number?, [4]: number?}

--- The methods of each value, as scripts call them (the generated wrappers).
---@type table<string, function>
local Vec3, Location = {}, {}
---@type table
local Vec3_mt, Location_mt

local function is_vec3(value)
  return raw_getmetatable(value) == Vec3_mt
end

local function is_location(value)
  return raw_getmetatable(value) == Location_mt
end

---@param x number
---@param y number
---@param z number
---@return nf.Vec3
local function new_vec3(x, y, z)
  return setmetatable({ x, y, z }, Vec3_mt)
end

---@param world table
---@param position nf.Vec3
---@param yaw number?
---@param pitch number?
---@return nf.Location
local function new_location(world, position, yaw, pitch)
  return setmetatable({ world, position, yaw, pitch }, Location_mt)
end

-- A number as tostring shows it in a vector: whole floats without ".0", so
-- vec3(1, 2, 3) reads the same whether it was built from integers or floats.
local function number_text(n)
  if n == floor(n) and abs(n) < 2 ^ 53 then
    return format("%d", n)
  end
  return tostring(n)
end

local function vector_arg(value, name, level)
  if not is_vec3(value) then
    bad(name, "Vec3", value, level + 1)
  end
  return value
end

-- What the error for a method called with `.` says, by value type.
local self_hints = { Vec3 = "like v:length()", Location = "like location:offset(v)" }

-- A value method's `self`, if it's a genuine one of that type. Called straight
-- from the wrapper, so the error lands on the caller.
local function value_self(self, type_name)
  local mt = type_name == "Vec3" and Vec3_mt or Location_mt
  if raw_getmetatable(self) ~= mt then
    error(
      "call "
        .. type_name
        .. " methods with ':' on a "
        .. type_name
        .. ", "
        .. self_hints[type_name],
      3
    )
  end
end

local vec3_fields = { x = 1, y = 2, z = 3 }

Vec3_mt = {
  __index = function(v, key)
    local i = vec3_fields[key]
    if i ~= nil then
      return v[i]
    end
    return Vec3[key]
  end,
  __newindex = function(_, key)
    error(
      format(
        "vectors can't be changed (setting '%s'): make a new one, like v:with_x(n)",
        tostring(key)
      ),
      2
    )
  end,
  -- The operators are what vector maths is made of, so each makes its vector
  -- itself rather than through calls (`ApiCostTest` holds them to a few
  -- instructions).
  __add = function(a, b)
    if raw_getmetatable(a) ~= Vec3_mt or raw_getmetatable(b) ~= Vec3_mt then
      error("a Vec3 can only be added to another Vec3", 2)
    end
    return setmetatable({ a[1] + b[1], a[2] + b[2], a[3] + b[3] }, Vec3_mt)
  end,
  __sub = function(a, b)
    if raw_getmetatable(a) ~= Vec3_mt or raw_getmetatable(b) ~= Vec3_mt then
      error("a Vec3 can only be subtracted from another Vec3", 2)
    end
    return setmetatable({ a[1] - b[1], a[2] - b[2], a[3] - b[3] }, Vec3_mt)
  end,
  __unm = function(a)
    return setmetatable({ -a[1], -a[2], -a[3] }, Vec3_mt)
  end,
  __mul = function(a, b)
    if type(a) == "number" then
      a, b = b, a
    end
    if type(b) == "number" then
      return setmetatable({ a[1] * b, a[2] * b, a[3] * b }, Vec3_mt)
    end
    if raw_getmetatable(a) == Vec3_mt and raw_getmetatable(b) == Vec3_mt then
      return setmetatable({ a[1] * b[1], a[2] * b[2], a[3] * b[3] }, Vec3_mt)
    end
    error("a Vec3 can only be multiplied by a number or another Vec3", 2)
  end,
  __div = function(a, b)
    if raw_getmetatable(a) ~= Vec3_mt or type(b) ~= "number" then
      error("a Vec3 can only be divided by a number", 2)
    end
    return setmetatable({ a[1] / b, a[2] / b, a[3] / b }, Vec3_mt)
  end,
  __eq = function(a, b)
    return is_vec3(a) and is_vec3(b) and a[1] == b[1] and a[2] == b[2] and a[3] == b[3]
  end,
  __tostring = function(v)
    return "vec3("
      .. number_text(v[1])
      .. ", "
      .. number_text(v[2])
      .. ", "
      .. number_text(v[3])
      .. ")"
  end,
  __metatable = "Vec3",
}
handles.kind(Vec3_mt, "Vec3")

-- ---- Vec3's method bodies. Called by the generated wrappers with `self` and
-- every argument already checked; a mistake only they can't see (a rotation
-- about no axis) is an error at level 2, the script's line.

---@type table<string, function>
local Vec3_body = {}

function Vec3_body.length(v)
  return sqrt(v[1] * v[1] + v[2] * v[2] + v[3] * v[3])
end

function Vec3_body.length_squared(v)
  return v[1] * v[1] + v[2] * v[2] + v[3] * v[3]
end

function Vec3_body.normalized(v)
  local length = sqrt(v[1] * v[1] + v[2] * v[2] + v[3] * v[3])
  if length == 0 then
    return new_vec3(0, 0, 0)
  end
  return new_vec3(v[1] / length, v[2] / length, v[3] / length)
end

function Vec3_body.dot(a, b)
  return a[1] * b[1] + a[2] * b[2] + a[3] * b[3]
end

function Vec3_body.cross(a, b)
  return new_vec3(a[2] * b[3] - a[3] * b[2], a[3] * b[1] - a[1] * b[3], a[1] * b[2] - a[2] * b[1])
end

function Vec3_body.distance(a, b)
  local dx, dy, dz = a[1] - b[1], a[2] - b[2], a[3] - b[3]
  return sqrt(dx * dx + dy * dy + dz * dz)
end

function Vec3_body.distance_squared(a, b)
  local dx, dy, dz = a[1] - b[1], a[2] - b[2], a[3] - b[3]
  return dx * dx + dy * dy + dz * dz
end

function Vec3_body.lerp(a, b, f)
  return new_vec3(a[1] + (b[1] - a[1]) * f, a[2] + (b[2] - a[2]) * f, a[3] + (b[3] - a[3]) * f)
end

function Vec3_body.clamp_length(v, max_length)
  if max_length < 0 then
    error("bad argument 'max_length' (a length can't be negative)", 2)
  end
  local squared = v[1] * v[1] + v[2] * v[2] + v[3] * v[3]
  if squared <= max_length * max_length then
    return v
  end
  local scale = max_length / sqrt(squared)
  return new_vec3(v[1] * scale, v[2] * scale, v[3] * scale)
end

-- v − 2 (v · n) n, for the unit normal n.
function Vec3_body.reflect(v, normal)
  local length = sqrt(normal[1] * normal[1] + normal[2] * normal[2] + normal[3] * normal[3])
  if length == 0 then
    error("bad argument 'normal' (a surface's normal can't be zero)", 2)
  end
  local nx, ny, nz = normal[1] / length, normal[2] / length, normal[3] / length
  local twice = 2 * (v[1] * nx + v[2] * ny + v[3] * nz)
  return new_vec3(v[1] - twice * nx, v[2] - twice * ny, v[3] - twice * nz)
end

-- The projection of p − start onto the segment, its fraction kept within 0 to 1.
function Vec3_body.closest_on_segment(p, start, finish)
  local dx, dy, dz = finish[1] - start[1], finish[2] - start[2], finish[3] - start[3]
  local squared = dx * dx + dy * dy + dz * dz
  if squared == 0 then
    return start
  end
  local t = ((p[1] - start[1]) * dx + (p[2] - start[2]) * dy + (p[3] - start[3]) * dz) / squared
  if t < 0 then
    t = 0
  elseif t > 1 then
    t = 1
  end
  return new_vec3(start[1] + dx * t, start[2] + dy * t, start[3] + dz * t)
end

function Vec3_body.flat(v)
  return new_vec3(v[1], 0, v[3])
end

function Vec3_body.with_x(v, x)
  return new_vec3(x, v[2], v[3])
end

function Vec3_body.with_y(v, y)
  return new_vec3(v[1], y, v[3])
end

function Vec3_body.with_z(v, z)
  return new_vec3(v[1], v[2], z)
end

function Vec3_body.floor(v)
  return new_vec3(floor(v[1]), floor(v[2]), floor(v[3]))
end

function Vec3_body.round(v)
  return new_vec3(floor(v[1] + 0.5), floor(v[2] + 0.5), floor(v[3] + 0.5))
end

-- Rodrigues' formula: v cos θ + (k × v) sin θ + k (k · v)(1 − cos θ), for a unit axis k.
function Vec3_body.rotated(v, axis, degrees)
  local length = sqrt(axis[1] * axis[1] + axis[2] * axis[2] + axis[3] * axis[3])
  if length == 0 then
    error("bad argument 'axis' (a rotation needs an axis that isn't zero)", 2)
  end
  local kx, ky, kz = axis[1] / length, axis[2] / length, axis[3] / length
  local c, s = cos(rad(degrees)), sin(rad(degrees))
  local dot = (kx * v[1] + ky * v[2] + kz * v[3]) * (1 - c)
  return new_vec3(
    v[1] * c + (ky * v[3] - kz * v[2]) * s + kx * dot,
    v[2] * c + (kz * v[1] - kx * v[3]) * s + ky * dot,
    v[3] * c + (kx * v[2] - ky * v[1]) * s + kz * dot
  )
end

function Vec3_body.unpack(v)
  return v[1], v[2], v[3]
end

-- Minecraft's angles: yaw 0 faces +z and grows towards -x; pitch 90 faces down.
local function from_yaw_pitch(yaw, pitch)
  local y, p = rad(yaw), rad(pitch)
  return new_vec3(-sin(y) * cos(p), -sin(p), cos(y) * cos(p))
end

local location_fields = { world = 1, position = 2, yaw = 3, pitch = 4 }

Location_mt = {
  __index = function(l, key)
    local i = location_fields[key]
    if i ~= nil then
      return l[i]
    end
    return Location[key]
  end,
  __newindex = function(_, key)
    error(
      format(
        "locations can't be changed (setting '%s'): make a new one, like location:with_position(v)",
        tostring(key)
      ),
      2
    )
  end,
  __eq = function(a, b)
    return is_location(a)
      and is_location(b)
      and a[1] == b[1]
      and a[2] == b[2]
      and a[3] == b[3]
      and a[4] == b[4]
  end,
  __tostring = function(l)
    local text = format("location(%q, %s", prim["handles.key"](l[1]), tostring(l[2]))
    if l[3] ~= nil then
      text = text .. ", " .. number_text(l[3]) .. ", " .. number_text(l[4] or 0)
    end
    return text .. ")"
  end,
  __metatable = "Location",
}
handles.kind(Location_mt, "Location")

---@type table<string, function>
local Location_body = {}

function Location_body.with_position(l, position)
  return new_location(l[1], position, l[3], l[4])
end

function Location_body.offset(l, offset)
  return new_location(l[1], l[2] + offset, l[3], l[4])
end

function Location_body.direction(l)
  if l[3] == nil then
    return nil
  end
  return from_yaw_pitch(l[3], l[4] or 0)
end

-- `vec3`'s functions (`vec3.from_yaw_pitch`): the wrappers' bodies.
---@type table<string, function>
local vec3_body = {}

function vec3_body.from_yaw_pitch(yaw, pitch)
  return from_yaw_pitch(yaw, pitch)
end

-- A scope's `vec3`: callable to build a vector, with the common ones and
-- from_yaw_pitch on it. Each scope gets its own, constants included, for the
-- same reason each gets its own library proxies (`rawset` reaches only it).
-- `functions` are the generated wrappers (`vec3.from_yaw_pitch`).
-- The `vec3` global's metatable, by the functions table it was made with:
-- one for every scope (a script can't reach it), so a new scope makes only an
-- empty table for its `vec3`. `pairs(vec3)` walks the library without handing
-- it out, so no script can change another's constants.
local vec3_mts = setmetatable({}, { __mode = "k" })

local function make_vec3_library(functions)
  local mt = vec3_mts[functions]
  if mt == nil then
    local library = {
      zero = new_vec3(0, 0, 0),
      one = new_vec3(1, 1, 1),
      up = new_vec3(0, 1, 0),
      down = new_vec3(0, -1, 0),
      north = new_vec3(0, 0, -1),
      south = new_vec3(0, 0, 1),
      east = new_vec3(1, 0, 0),
      west = new_vec3(-1, 0, 0),
    }
    for name, fn in pairs(functions) do
      library[name] = fn
    end
    mt = {
      __index = library,
      __call = function(_, x, y, z)
        if type(x) ~= "number" or type(y) ~= "number" or type(z) ~= "number" then
          want_number(x, "x", 2)
          want_number(y, "y", 2)
          want_number(z, "z", 2)
        end
        return setmetatable({ x, y, z }, Vec3_mt)
      end,
      __newindex = function()
        error("vec3 is shared by every script and can't be changed", 2)
      end,
      __pairs = function()
        return function(_, key)
          return next(library, key)
        end, nil, nil
      end,
      __metatable = false,
    }
    vec3_mts[functions] = mt
  end
  return setmetatable({}, mt)
end

-- The `Vec3` fast path: a parameter crosses as its three numbers, and a
-- return is rebuilt from them.

local function want_vec3(value, name)
  if not is_vec3(value) then
    bad(name, "Vec3", value, 3)
  end
  return value[1], value[2], value[3]
end

local function want_vec3_opt(value, name)
  if value == nil then
    return nil
  end
  if not is_vec3(value) then
    bad(name, "Vec3 or nil", value, 3)
  end
  return value[1], value[2], value[3]
end

return {
  Vec3 = Vec3,
  Location = Location,
  -- The genuine metatables, for the bindings' inline checks (`raw_getmetatable(v) == Vec3_mt`).
  Vec3_mt = Vec3_mt,
  Location_mt = Location_mt,
  body = { Vec3 = Vec3_body, Location = Location_body, vec3 = vec3_body },
  is_vec3 = is_vec3,
  is_location = is_location,
  new_vec3 = new_vec3,
  new_location = new_location,
  vector_arg = vector_arg,
  value_self = value_self,
  make_vec3_library = make_vec3_library,
  want_vec3 = want_vec3,
  want_vec3_opt = want_vec3_opt,
}
