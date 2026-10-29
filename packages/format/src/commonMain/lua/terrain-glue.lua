-- The nf_* entry points at the bottom are the globals Kotlin calls, so they're globals on purpose.
---@diagnostic disable: lowercase-global

local host_noise, host_fill, host_block = host_noise, host_fill, host_block
local host_file_height, host_area, host_resolve = host_file_height, host_area, host_resolve
local host_point_area, host_set_loot = host_point_area, host_set_loot
local host_module_path, host_load, host_caps = host_module_path, host_load, host_caps
for _, name in ipairs({
  "noise",
  "fill",
  "block",
  "file_height",
  "area",
  "point_area",
  "set_loot",
  "resolve",
  "module_path",
  "load",
  "caps",
}) do
  _G["host_" .. name] = nil
end

local debug_sethook, debug_getinfo, debug_setupvalue =
  debug.sethook, debug.getinfo, debug.setupvalue
local collect = collectgarbage
local error, pcall, type, tostring, tonumber, next = error, pcall, type, tostring, tonumber, next
local setmetatable, getmetatable, rawget = setmetatable, getmetatable, rawget
local tointeger, floor, huge = math.tointeger, math.floor, math.huge
local random, randomseed = math.random, math.randomseed
local create, resume = coroutine.create, coroutine.resume
local pack, unpack, sort, concat = table.pack, table.unpack, table.sort, table.concat
local sub, format, gmatch, rep, byte =
  string.sub, string.format, string.gmatch, string.rep, string.byte

local HOOK_EVERY = 1000
local MAX_STRING = 16 * 1024 * 1024

-- ---- the budget: instructions and memory, for every call in ------------------------------------------------

local budget, memory_kb, memory_mb = 0, 0, 0
local used, step = 0, HOOK_EVERY
local failure = nil
local check_memory = false

-- "file:line: " of the first frame at or above [level] (from the caller) that's a script's.
local function script_line(level)
  level = level + 1
  while true do
    local info = debug_getinfo(level, "Sl")
    if info == nil then
      return ""
    end
    if sub(info.source, 1, 1) == "@" and info.currentline > 0 then
      return info.short_src .. ":" .. info.currentline .. ": "
    end
    level = level + 1
  end
end

-- The one hook: it counts what a call has run, and once that's past its budget (or memory past its limit) every
-- later count fails it again, so a pcall in the script can't run on.
local function hook()
  used = used + step
  if failure == nil then
    if used > budget then
      failure = script_line(2) .. "ran past its budget of " .. budget .. " instructions"
    elseif collect("count") > memory_kb then
      failure = script_line(2) .. "used more than " .. memory_mb .. " MB of memory"
    elseif check_memory then
      check_memory = false
      step = HOOK_EVERY
      debug_sethook(hook, "", HOOK_EVERY)
    end
  end
  if failure ~= nil then
    error(failure, 0)
  end
end

-- A string doubled with `..` outgrows any limit between two counts, but a collection runs while it grows: at the
-- end of each cycle this asks the very next instruction to look at the memory (collectgarbage can't, in here).
--
-- Arming the hook again starts its count over, throwing away what ran since the
-- last count: a loop that ends a cycle more often than every HOOK_EVERY
-- instructions would barely be counted. So the switch is charged what that count
-- could have held, HOOK_EVERY (the plugin's sandbox does the same).
local sentinel
sentinel = function()
  setmetatable({}, {
    __gc = function()
      if step ~= 1 then
        used = used + HOOK_EVERY
      end
      check_memory = true
      step = 1
      debug_sethook(hook, "", 1)
      sentinel()
    end,
  })
end

-- ---- the sandbox ---------------------------------------------------------------------------------------

-- One call of a library function is one instruction, however long it runs, so
-- every one whose work isn't bounded by its arguments' size (string.rep, the
-- patterns, string.format, table.concat, the table shifts and sorts) is the
-- plugin sandbox's capped one (`caps.lua`, which format holds for both): it
-- refuses a call that could make more than MAX_STRING bytes or take too long,
-- and counts what it allows towards the budget (about 2.5 of its steps an
-- instruction). Installed in the real tables, which the copies below and the
-- string metatable read; this glue's own work uses the library's, taken above.
do
  local STEPS_PER_INSTRUCTION, MAX_WORK = 2.5, 1e8
  local function spend(steps)
    used = used + steps / STEPS_PER_INSTRUCTION
    if failure == nil and used > budget then
      failure = script_line(2) .. "ran past its budget of " .. budget .. " instructions"
    end
    if failure ~= nil then
      error(failure, 0)
    end
  end
  local function refuse(message)
    error(script_line(2) .. message, 0)
  end
  local raw = {
    rep = rep,
    format = format,
    find = string.find,
    match = string.match,
    gmatch = gmatch,
    gsub = string.gsub,
    pack = string.pack,
    concat = concat,
    move = table.move,
    insert = table.insert,
    remove = table.remove,
    sort = sort,
    getmetatable = debug.getmetatable,
  }
  local caps = host_caps()(raw, { max_string = MAX_STRING, max_work = MAX_WORK }, spend, refuse)
  for name, fn in next, caps.string do
    string[name] = fn
  end
  for name, fn in next, caps.table do
    table[name] = fn
  end
end
getmetatable("").__metatable = false

local function copy(t)
  local out = {}
  for key, value in next, t do
    out[key] = value
  end
  return out
end

local env = {}
for _, name in ipairs({
  "assert",
  "error",
  "ipairs",
  "next",
  "pairs",
  "pcall",
  "rawequal",
  "rawget",
  "rawlen",
  "rawset",
  "select",
  "tonumber",
  "tostring",
  "type",
  "xpcall",
  "getmetatable",
}) do
  env[name] = _G[name]
end
env._VERSION = _VERSION
env._G = env
env.setmetatable = function(t, metatable)
  if type(metatable) == "table" and rawget(metatable, "__gc") ~= nil then
    error("a terrain script can't set __gc", 2)
  end
  return setmetatable(t, metatable)
end
env.string = copy(string)
env.string.dump = nil
env.table = copy(table)
env.utf8 = copy(utf8)

-- math.random is seeded for each call in from the world's seed and where it is (the chunk, the column), the
-- first time it's asked, so the same place always gets the same numbers.
-- A plan is made with numbers of its own (current_rng), whenever it's first asked for, so it doesn't move the
-- numbers of the stage that asked.
local call_seed, seeded = 0, true
local current_rng = nil
env.math = copy(math)
env.math.randomseed = nil
env.math.random = function(...)
  if current_rng ~= nil then
    return current_rng(...)
  end
  if not seeded then
    randomseed(call_seed)
    seeded = true
  end
  return random(...)
end

-- `vec3` and `nf.math`, as other scripts have them, so a module of maths works in both (the rest of `nf` is the
-- server's, and isn't here).
do
  local sqrt, sin, cos, rad, abs = math.sqrt, math.sin, math.cos, math.rad, math.abs
  local Vec3 = {}
  local fields = { x = 1, y = 2, z = 3 }
  local Vec3_mt
  local function new(x, y, z)
    return setmetatable({ x, y, z }, Vec3_mt)
  end
  local function is_vec3(v)
    return getmetatable(v) == "Vec3"
  end
  local function number_text(n)
    if n == floor(n) and abs(n) < 2 ^ 53 then
      return format("%d", n)
    end
    return tostring(n)
  end
  local function want_vec3(v, name)
    if not is_vec3(v) then
      error(format("bad argument '%s' (Vec3 expected, got %s)", name, type(v)), 3)
    end
  end
  local function want_num(v, name)
    if type(v) ~= "number" then
      error(format("bad argument '%s' (number expected, got %s)", name, type(v)), 3)
    end
  end
  Vec3_mt = {
    __index = function(v, key)
      local i = fields[key]
      if i ~= nil then
        return rawget(v, i)
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
    __add = function(a, b)
      if not is_vec3(a) or not is_vec3(b) then
        error("a Vec3 can only be added to another Vec3", 2)
      end
      return new(a[1] + b[1], a[2] + b[2], a[3] + b[3])
    end,
    __sub = function(a, b)
      if not is_vec3(a) or not is_vec3(b) then
        error("a Vec3 can only be subtracted from another Vec3", 2)
      end
      return new(a[1] - b[1], a[2] - b[2], a[3] - b[3])
    end,
    __unm = function(a)
      return new(-a[1], -a[2], -a[3])
    end,
    __mul = function(a, b)
      if type(a) == "number" then
        a, b = b, a
      end
      if type(b) == "number" then
        return new(a[1] * b, a[2] * b, a[3] * b)
      end
      if is_vec3(a) and is_vec3(b) then
        return new(a[1] * b[1], a[2] * b[2], a[3] * b[3])
      end
      error("a Vec3 can only be multiplied by a number or another Vec3", 2)
    end,
    __div = function(a, b)
      if not is_vec3(a) or type(b) ~= "number" then
        error("a Vec3 can only be divided by a number", 2)
      end
      return new(a[1] / b, a[2] / b, a[3] / b)
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
  function Vec3.length(v)
    return sqrt(v[1] * v[1] + v[2] * v[2] + v[3] * v[3])
  end
  function Vec3.length_squared(v)
    return v[1] * v[1] + v[2] * v[2] + v[3] * v[3]
  end
  function Vec3.normalized(v)
    local length = sqrt(v[1] * v[1] + v[2] * v[2] + v[3] * v[3])
    if length == 0 then
      return new(0, 0, 0)
    end
    return new(v[1] / length, v[2] / length, v[3] / length)
  end
  function Vec3.dot(a, b)
    want_vec3(b, "other")
    return a[1] * b[1] + a[2] * b[2] + a[3] * b[3]
  end
  function Vec3.cross(a, b)
    want_vec3(b, "other")
    return new(a[2] * b[3] - a[3] * b[2], a[3] * b[1] - a[1] * b[3], a[1] * b[2] - a[2] * b[1])
  end
  function Vec3.distance(a, b)
    want_vec3(b, "other")
    local dx, dy, dz = a[1] - b[1], a[2] - b[2], a[3] - b[3]
    return sqrt(dx * dx + dy * dy + dz * dz)
  end
  function Vec3.distance_squared(a, b)
    want_vec3(b, "other")
    local dx, dy, dz = a[1] - b[1], a[2] - b[2], a[3] - b[3]
    return dx * dx + dy * dy + dz * dz
  end
  function Vec3.lerp(a, b, f)
    want_vec3(b, "to")
    want_num(f, "fraction")
    return new(a[1] + (b[1] - a[1]) * f, a[2] + (b[2] - a[2]) * f, a[3] + (b[3] - a[3]) * f)
  end
  function Vec3.clamp_length(v, max_length)
    want_num(max_length, "max_length")
    if max_length < 0 then
      error("bad argument 'max_length' (a length can't be negative)", 2)
    end
    local squared = v[1] * v[1] + v[2] * v[2] + v[3] * v[3]
    if squared <= max_length * max_length then
      return v
    end
    local scale = max_length / sqrt(squared)
    return new(v[1] * scale, v[2] * scale, v[3] * scale)
  end
  function Vec3.reflect(v, normal)
    want_vec3(normal, "normal")
    local length = sqrt(normal[1] * normal[1] + normal[2] * normal[2] + normal[3] * normal[3])
    if length == 0 then
      error("bad argument 'normal' (a surface's normal can't be zero)", 2)
    end
    local nx, ny, nz = normal[1] / length, normal[2] / length, normal[3] / length
    local twice = 2 * (v[1] * nx + v[2] * ny + v[3] * nz)
    return new(v[1] - twice * nx, v[2] - twice * ny, v[3] - twice * nz)
  end
  function Vec3.closest_on_segment(p, start, finish)
    want_vec3(start, "start")
    want_vec3(finish, "finish")
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
    return new(start[1] + dx * t, start[2] + dy * t, start[3] + dz * t)
  end
  function Vec3.flat(v)
    return new(v[1], 0, v[3])
  end
  function Vec3.with_x(v, x)
    return new(x, v[2], v[3])
  end
  function Vec3.with_y(v, y)
    return new(v[1], y, v[3])
  end
  function Vec3.with_z(v, z)
    return new(v[1], v[2], z)
  end
  function Vec3.floor(v)
    return new(floor(v[1]), floor(v[2]), floor(v[3]))
  end
  function Vec3.round(v)
    return new(floor(v[1] + 0.5), floor(v[2] + 0.5), floor(v[3] + 0.5))
  end
  function Vec3.rotated(v, axis, degrees)
    want_vec3(axis, "axis")
    want_num(degrees, "degrees")
    local length = sqrt(axis[1] * axis[1] + axis[2] * axis[2] + axis[3] * axis[3])
    if length == 0 then
      error("bad argument 'axis' (a rotation needs an axis that isn't zero)", 2)
    end
    local kx, ky, kz = axis[1] / length, axis[2] / length, axis[3] / length
    local c, s = cos(rad(degrees)), sin(rad(degrees))
    local dot = (kx * v[1] + ky * v[2] + kz * v[3]) * (1 - c)
    return new(
      v[1] * c + (ky * v[3] - kz * v[2]) * s + kx * dot,
      v[2] * c + (kz * v[1] - kx * v[3]) * s + ky * dot,
      v[3] * c + (kx * v[2] - ky * v[1]) * s + kz * dot
    )
  end
  function Vec3.unpack(v)
    return v[1], v[2], v[3]
  end
  local library = {
    zero = new(0, 0, 0),
    one = new(1, 1, 1),
    up = new(0, 1, 0),
    down = new(0, -1, 0),
    north = new(0, 0, -1),
    south = new(0, 0, 1),
    east = new(1, 0, 0),
    west = new(-1, 0, 0),
  }
  function library.from_yaw_pitch(yaw, pitch)
    want_num(yaw, "yaw")
    want_num(pitch, "pitch")
    local y, p = rad(yaw), rad(pitch)
    return new(-sin(y) * cos(p), -sin(p), cos(y) * cos(p))
  end
  env.vec3 = setmetatable({}, {
    __index = library,
    __call = function(_, x, y, z)
      want_num(x, "x")
      want_num(y, "y")
      want_num(z, "z")
      return new(x, y, z)
    end,
    __newindex = function()
      error("vec3 can't be changed", 2)
    end,
    __metatable = false,
  })

  local maths = {}
  function maths.lerp(from, to, fraction)
    return from + (to - from) * fraction
  end
  function maths.clamp(value, low, high)
    if low > high then
      error(format("bad argument 'min' (%s is greater than max, %s)", low, high), 2)
    end
    if value < low then
      return low
    elseif value > high then
      return high
    end
    return value
  end
  function maths.remap(value, from_min, from_max, to_min, to_max)
    if from_min == from_max then
      error(
        format("bad argument 'from_max' (the range from %s to itself has no size)", from_min),
        2
      )
    end
    return to_min + (value - from_min) * (to_max - to_min) / (from_max - from_min)
  end
  local curves = {
    linear = function(t)
      return t
    end,
    step = function(t)
      return t >= 1 and 1 or 0
    end,
    ease_in = function(t)
      return t * t
    end,
    ease_out = function(t)
      return 1 - (1 - t) * (1 - t)
    end,
    ease_in_out = function(t)
      if t < 0.5 then
        return 2 * t * t
      end
      return 1 - 2 * (1 - t) * (1 - t)
    end,
  }
  function maths.ease(easing, fraction)
    local curve = curves[easing]
    if curve == nil then
      error(
        format(
          "bad argument 'easing' (linear, step, ease_in, ease_out or ease_in_out, got %s)",
          tostring(easing)
        ),
        2
      )
    end
    if fraction < 0 then
      fraction = 0
    elseif fraction > 1 then
      fraction = 1
    end
    return curve(fraction)
  end
  env.nf = {
    math = setmetatable({}, {
      __index = maths,
      __newindex = function()
        error("nf.math can't be changed", 2)
      end,
      __metatable = false,
    }),
  }
end

-- A coroutine is counted like the call that made it.
env.coroutine = copy(coroutine)
env.coroutine.create = function(body)
  local thread = create(body)
  debug_sethook(thread, hook, "", HOOK_EVERY)
  return thread
end
env.coroutine.wrap = function(body)
  local thread = env.coroutine.create(body)
  return function(...)
    local results = pack(resume(thread, ...))
    if not results[1] then
      error(results[2], 0)
    end
    return unpack(results, 2, results.n)
  end
end

-- `require` of the project's modules, in this script's environment: the requiring module's own files first
-- ("noise" in modules/terrain/ is modules/terrain/noise.lua), then a module by name ("terrain" is its init.lua,
-- "terrain.noise" its noise.lua).
local LOADING = {}
local loaded = {}
env.require = function(name)
  if type(name) ~= "string" then
    error(format("bad argument #1 to 'require' (a module's name expected, got %s)", type(name)), 2)
  end
  local caller = debug_getinfo(2, "S")
  local path = host_module_path(name, caller and caller.source or "")
  if path == nil then
    error(
      format(
        'no module "%s": a terrain\'s script requires the project\'s own modules, like "terrain" '
          .. '(modules/terrain/init.lua) or "terrain.noise" (modules/terrain/noise.lua)',
        name
      ),
      2
    )
  end
  local value = loaded[path]
  if value == LOADING then
    error(format('"%s" requires itself: %s is still loading', name, path), 2)
  end
  if value ~= nil then
    return value
  end
  local body = host_load(path)
  debug_setupvalue(body, 1, env)
  loaded[path] = LOADING
  local ok, result = pcall(body, name)
  if not ok then
    loaded[path] = nil
    error(result, 0)
  end
  if result == nil then
    result = true
  end
  loaded[path] = result
  return result
end

-- ---- checking what scripts pass ----------------------------------------------------------------------------

local function name_of(value)
  local kind = type(value)
  if kind == "number" and tointeger(value) == nil then
    return "a number with a fraction"
  end
  return kind
end

local function want_number(value, position, function_name)
  if type(value) ~= "number" then
    error(
      format(
        "bad argument #%d to '%s' (number expected, got %s)",
        position,
        function_name,
        name_of(value)
      ),
      3
    )
  end
  return value
end

local function want_integer(value, position, function_name)
  local integer = type(value) == "number" and tointeger(value) or nil
  if integer == nil then
    error(
      format(
        "bad argument #%d to '%s' (whole number expected, got %s)",
        position,
        function_name,
        name_of(value)
      ),
      3
    )
  end
  return integer
end

local function lines(text)
  local out = {}
  if text ~= "" then
    for line in gmatch(text, "[^\n]+") do
      out[#out + 1] = line
    end
  end
  return out
end

-- ---- blocks ------------------------------------------------------------------------------------------------

local labels = {}
local block_ids = {}

local function block_index(block, position, function_name)
  if type(block) ~= "string" then
    error(
      format(
        "bad argument #%d to '%s' (block id expected, got %s)",
        position,
        function_name,
        name_of(block)
      ),
      3
    )
  end
  local index = block_ids[block]
  if index == nil then
    index = host_resolve(block)
    if index < 0 then
      error(
        format(
          '"%s" isn\'t a block this generator places: name the game\'s in full ("minecraft:stone"), the project\'s '
            .. "by id, and list any the file doesn't use in its script.blocks or script.customBlocks",
          block
        ),
        3
      )
    end
    block_ids[block] = index
  end
  return index
end

-- ---- the API -------------------------------------------------------------------------------------------------

---@class nf.TerrainStages The stages the script returned: each one it has.
---@field height fun(x: integer, z: integer, height: integer): number
---@field density fun(x: integer, y: integer, z: integer, value: number): number
---@field area fun(x: integer, z: integer, file: string): string
---@field biome fun(x: integer, y: integer, z: integer, file: string): string
---@field terrain fun(chunk: table)
---@field decorate fun(chunk: table)

---@type nf.TerrainStages
local stages = nil --[[@as nf.TerrainStages]]
local seed, min_y, max_y, sea_level = 0, 0, 0, 0
local area_names, biome_names, area_index, volume_set = {}, {}, {}, {}
local loot_index = {}
local in_height = false
-- The heights terrain.height gave, by column (the game asks a column's biome all the way up it), started again past HEIGHTS_KEPT.
local heights, kept_heights, HEIGHTS_KEPT = {}, 0, 16384
-- The stage Kotlin called in ("load", "height", "area"...), while it runs.
local running = nil

local Noise = { __metatable = false }
Noise.__index = Noise
local noise_index = setmetatable({}, { __mode = "k" })
local noises = {}

function Noise.at(self, x, y, z)
  local index = noise_index[self]
  if index == nil then
    error("call at with ':' on a noise: noise:at(x, z)", 2)
  end
  want_number(x, 1, "at")
  want_number(y, 2, "at")
  if z == nil then
    return host_noise(index, x, y)
  end
  return host_noise(index, x, y, want_number(z, 3, "at"))
end

local Chunk = { __metatable = false }
Chunk.__index = Chunk
local chunk = setmetatable({}, Chunk)
local chunk_x, chunk_z, in_chunk = 0, 0, false

local function want_chunk(self, function_name)
  if self ~= chunk then
    error(
      format(
        "call %s with ':' on the chunk a stage is given: chunk:%s(...)",
        function_name,
        function_name
      ),
      3
    )
  end
  if not in_chunk then
    error("a chunk can only be used during the stage it's given to", 3)
  end
end

function Chunk.x(self)
  want_chunk(self, "x")
  return chunk_x
end

function Chunk.z(self)
  want_chunk(self, "z")
  return chunk_z
end

function Chunk.min_x(self)
  want_chunk(self, "min_x")
  return chunk_x * 16
end

function Chunk.min_z(self)
  want_chunk(self, "min_z")
  return chunk_z * 16
end

function Chunk.fill(self, x1, y1, z1, x2, y2, z2, block)
  want_chunk(self, "fill")
  host_fill(
    want_integer(x1, 1, "fill"),
    want_integer(y1, 2, "fill"),
    want_integer(z1, 3, "fill"),
    want_integer(x2, 4, "fill"),
    want_integer(y2, 5, "fill"),
    want_integer(z2, 6, "fill"),
    block_index(block, 7, "fill")
  )
end

function Chunk.set(self, x, y, z, block)
  want_chunk(self, "set")
  x, y, z = want_integer(x, 1, "set"), want_integer(y, 2, "set"), want_integer(z, 3, "set")
  host_fill(x, y, z, x, y, z, block_index(block, 4, "set"))
end

function Chunk.set_loot(self, x, y, z, table_name)
  want_chunk(self, "set_loot")
  x, y, z =
    want_integer(x, 1, "set_loot"), want_integer(y, 2, "set_loot"), want_integer(z, 3, "set_loot")
  if type(table_name) ~= "string" then
    error(
      format("bad argument #4 to 'set_loot' (loot table expected, got %s)", name_of(table_name)),
      2
    )
  end
  local index = loot_index[table_name]
  if index == nil then
    error(
      format(
        "\"%s\" isn't a loot table this generator fills: list it in the file's script.loot",
        table_name
      ),
      2
    )
  end
  host_set_loot(x, y, z, index)
end

function Chunk.block(self, x, y, z)
  want_chunk(self, "block")
  local index = host_block(
    want_integer(x, 1, "block"),
    want_integer(y, 2, "block"),
    want_integer(z, 3, "block")
  )
  if index < 0 then
    return nil
  end
  return labels[index]
end

local function clamp(height)
  if height < min_y then
    return min_y
  end
  if height > max_y - 1 then
    return max_y - 1
  end
  return height
end

local function finite(value)
  return type(value) == "number" and value == value and value ~= huge and value ~= -huge
end

local terrain = {}

function terrain.seed()
  return seed
end

function terrain.min_y()
  return min_y
end

function terrain.max_y()
  return max_y
end

function terrain.sea_level()
  return sea_level
end

function terrain.noise(name)
  if type(name) ~= "string" then
    error(format("bad argument #1 to 'noise' (a noise's name expected, got %s)", name_of(name)), 2)
  end
  local noise = noises[name]
  if noise == nil then
    error(format("\"%s\" isn't one of the noises the file's script.noises declares", name), 2)
  end
  return noise
end

-- A column's height as a chunk is generated with it: the file's, then this script's height stage. One that fails
-- (or gives no number) leaves the file's, as the column itself does.
function terrain.height(x, z)
  x, z = want_integer(x, 1, "height"), want_integer(z, 2, "height")
  if in_height then
    error(
      "terrain.height can't be asked from the height stage: use the height the stage is given",
      2
    )
  end
  if running == "area" then
    error(
      "terrain.height can't be asked from the area stage: a column's height depends on the areas",
      2
    )
  end
  local key = x * 134217728 + z
  local kept = heights[key]
  if kept ~= nil then
    return kept
  end
  local height = tointeger(host_file_height(x, z))
  local stage = stages and stages.height
  if stage ~= nil then
    in_height = true
    local ok, result = pcall(stage, x, z, height)
    in_height = false
    if ok and finite(result) then
      height = floor(result)
    end
  end
  height = clamp(height)
  if kept_heights >= HEIGHTS_KEPT then
    heights, kept_heights = {}, 0
  end
  heights[key], kept_heights = height, kept_heights + 1
  return height
end

-- The area of a column (x, z), or with three numbers of a place (x, y, z), as an index in the file's areas.
local function area_of(x, y, z, function_name)
  if running == "area" then
    error(
      format(
        "terrain.%s can't be asked from the area stage: use the area the stage is given",
        function_name
      ),
      3
    )
  end
  if z == nil then
    return host_area(want_integer(x, 1, function_name), want_integer(y, 2, function_name))
  end
  if in_height or running == "height" or running == "density" or running == "biome" then
    error(
      format(
        "terrain.%s of a place (x, y, z) can't be asked from the %s stage: a place's area depends on the heights",
        function_name,
        running == "biome" and "biome" or (running == "density" and "density" or "height")
      ),
      3
    )
  end
  return host_point_area(
    want_integer(x, 1, function_name),
    want_integer(y, 2, function_name),
    want_integer(z, 3, function_name)
  )
end

function terrain.area(x, y, z)
  return area_names[area_of(x, y, z, "area") + 1]
end

function terrain.biome(x, y, z)
  return biome_names[area_of(x, y, z, "biome") + 1]
end

-- ---- plans: what a big structure is, worked out once per cell rather than in every chunk it touches ----------

-- 64-bit mixing (splitmix64's), the same in every Lua 5.4: integers wrap.
local function mix64(z)
  z = (z ~ (z >> 30)) * 0xbf58476d1ce4e5b9
  z = (z ~ (z >> 27)) * 0x94d049bb133111eb
  return z ~ (z >> 31)
end

local function hash_name(text)
  local h = 0xcbf29ce484222325
  for i = 1, #text do
    h = (h ~ byte(text, i)) * 0x100000001b3
  end
  return h
end

-- math.random's three forms, from numbers of the plan's own.
local function make_random(state)
  return function(m, n)
    state = state + 0x9e3779b97f4a7c15
    local r = mix64(state)
    if m == nil then
      return (r >> 11) * (1.0 / 9007199254740992.0)
    end
    m = want_integer(m, 1, "random")
    if n == nil then
      m, n = 1, m
    else
      n = want_integer(n, 2, "random")
    end
    local span = n - m + 1
    if span <= 0 then
      error("bad argument to 'random' (interval is empty)", 2)
    end
    return m + (r >> 1) % span
  end
end

local PLAN_CACHE = 256
local NONE = {}
local Plan = { __metatable = false }
Plan.__index = Plan
local plan_data = setmetatable({}, { __mode = "k" })
local plans_by_name = {}

local function plan_of(self, function_name)
  local data = plan_data[self]
  if data == nil then
    error(format("call %s with ':' on a plan: plan:%s(...)", function_name, function_name), 3)
  end
  return data
end

local function plan_cell(data, cell_x, cell_z)
  local key = cell_x .. "," .. cell_z
  local cached = data.cache[key]
  if cached ~= nil then
    if cached == NONE then
      return nil
    end
    return cached
  end
  local saved = current_rng
  current_rng =
    make_random(mix64(data.seed ~ mix64(cell_x * 0x1B873593) ~ mix64(cell_z * 0x2C1B3C6D)))
  local ok, result = pcall(data.make, cell_x, cell_z)
  current_rng = saved
  if not ok then
    error(result, 0)
  end
  if data.count >= PLAN_CACHE then
    data.cache, data.count = {}, 0
  end
  data.cache[key] = result == nil and NONE or result
  data.count = data.count + 1
  return result
end

function Plan.get(self, cell_x, cell_z)
  local data = plan_of(self, "get")
  return plan_cell(data, want_integer(cell_x, 1, "get"), want_integer(cell_z, 2, "get"))
end

function Plan.at(self, x, z)
  local data = plan_of(self, "at")
  local cell_x, cell_z =
    want_integer(x, 1, "at") // data.size, want_integer(z, 2, "at") // data.size
  return plan_cell(data, cell_x, cell_z), cell_x, cell_z
end

function Plan.size(self)
  return plan_of(self, "size").size
end

function terrain.plan(name, size, make)
  if running ~= "load" then
    error("terrain.plan is made in the script's body, once, not in a stage", 2)
  end
  if type(name) ~= "string" or name == "" then
    error(format("bad argument #1 to 'plan' (a plan's name expected, got %s)", name_of(name)), 2)
  end
  if plans_by_name[name] then
    error(format('there\'s already a plan called "%s"', name), 2)
  end
  size = want_integer(size, 2, "plan")
  if size < 1 or size > 65536 then
    error(
      format("bad argument #2 to 'plan' (a cell's size is from 1 to 65536 blocks, not %d)", size),
      2
    )
  end
  if type(make) ~= "function" then
    error(format("bad argument #3 to 'plan' (function expected, got %s)", name_of(make)), 2)
  end
  local plan = setmetatable({}, Plan)
  plan_data[plan] =
    { size = size, make = make, seed = mix64(seed ~ hash_name(name)), cache = {}, count = 0 }
  plans_by_name[name] = true
  return plan
end

-- ---- shapes and structures ----------------------------------------------------------------------------------

local sqrt, min, max = math.sqrt, math.min, math.max

-- Calls each(x, y, z1, z2) for the rows along z of an ellipsoid centred on cx, cy, cz with radii rx, ry, rz
-- (whole blocks: 0 is one block thick) whose x is from x_lo to x_hi and z from z_lo to z_hi. Each radius is
-- taken half a block out, so a ball of radius 2 is round rather than a diamond.
local function ellipsoid_rows(cx, cy, cz, rx, ry, rz, x_lo, x_hi, z_lo, z_hi, each)
  local ex, ey, ez = rx + 0.5, ry + 0.5, rz + 0.5
  for dy = -ry, ry do
    local fy = dy / ey
    local ky = 1 - fy * fy
    for dx = max(-rx, x_lo - cx), min(rx, x_hi - cx) do
      local fx = dx / ex
      local k = ky - fx * fx
      if k >= 0 then
        local half = floor(ez * sqrt(k))
        each(cx + dx, cy + dy, max(cz - half, z_lo), min(cz + half, z_hi), dx, dy)
      end
    end
  end
end

-- The z half-width of an ellipsoid's row at dx, dy, or nil when the row misses it.
local function ellipsoid_half(rx, ry, rz, dx, dy)
  if rx < 0 or ry < 0 or rz < 0 then
    return nil
  end
  local fx, fy = dx / (rx + 0.5), dy / (ry + 0.5)
  local k = 1 - fx * fx - fy * fy
  if k < 0 then
    return nil
  end
  return floor((rz + 0.5) * sqrt(k))
end

-- Fills z1..z2 of a row with block (a palette index), or only its blocks that `only` (a set of labels) has.
local function fill_row(x, y, z1, z2, block, only)
  if z1 > z2 then
    return
  end
  if only == nil then
    host_fill(x, y, z1, x, y, z2, block)
    return
  end
  for z = z1, z2 do
    local here = host_block(x, y, z)
    if here >= 0 and only[labels[here]] then
      host_fill(x, y, z, x, y, z, block)
    end
  end
end

-- Draws an ellipsoid in the chunk: solid, or a shell `shell` thick; only over `only`'s blocks when given.
local function draw_ellipsoid(cx, cy, cz, rx, ry, rz, block, shell, only)
  local x0, z0 = chunk_x * 16, chunk_z * 16
  if cx + rx < x0 or cx - rx > x0 + 15 or cz + rz < z0 or cz - rz > z0 + 15 then
    return
  end
  ellipsoid_rows(cx, cy, cz, rx, ry, rz, x0, x0 + 15, z0, z0 + 15, function(x, y, z1, z2, dx, dy)
    local inner = shell and ellipsoid_half(rx - shell, ry - shell, rz - shell, dx, dy)
    if inner == nil then
      fill_row(x, y, z1, z2, block, only)
    else
      fill_row(x, y, z1, min(z2, cz - inner - 1), block, only)
      fill_row(x, y, max(z1, cz + inner + 1), z2, block, only)
    end
  end)
end

-- A list of block ids as a set, checked; nil for nil.
local function block_set(list, what, function_name)
  if list == nil then
    return nil
  end
  if type(list) ~= "table" then
    error(
      format(
        "bad argument to '%s' (%s: a list of block ids expected, got %s)",
        function_name,
        what,
        name_of(list)
      ),
      3
    )
  end
  local set = {}
  for _, id in ipairs(list) do
    if type(id) ~= "string" then
      error(
        format(
          "bad argument to '%s' (%s: block ids expected, got %s)",
          function_name,
          what,
          name_of(id)
        ),
        3
      )
    end
    set[id] = true
  end
  return set
end

-- Options a shape takes, checked: a table of these keys and nothing else.
local function options_of(options, keys, function_name)
  if options == nil then
    return {}
  end
  if type(options) ~= "table" then
    error(
      format(
        "bad argument to '%s' (options: table expected, got %s)",
        function_name,
        name_of(options)
      ),
      3
    )
  end
  for key in pairs(options) do
    if not keys[key] then
      error(format("bad argument to '%s' (options: no key '%s')", function_name, tostring(key)), 3)
    end
  end
  return options
end

local ELLIPSOID_KEYS = { shell = true, only = true }
local SPRINKLE_KEYS = { only = true, seed = true }

local function want_radius(value, position, function_name)
  value = want_integer(value, position, function_name)
  if value < 0 then
    error(
      format(
        "bad argument #%d to '%s' (a radius is 0 or more, got %d)",
        position,
        function_name,
        value
      ),
      3
    )
  end
  return value
end

local function ellipsoid_args(cx, cy, cz, rx, ry, rz, block, options, function_name)
  cx, cy, cz =
    want_integer(cx, 1, function_name),
    want_integer(cy, 2, function_name),
    want_integer(cz, 3, function_name)
  rx, ry, rz =
    want_radius(rx, 4, function_name),
    want_radius(ry, 5, function_name),
    want_radius(rz, 6, function_name)
  local index = block_index(block, 7, function_name)
  options = options_of(options, ELLIPSOID_KEYS, function_name)
  local shell = options.shell
  if shell ~= nil then
    shell = want_integer(shell, 8, function_name)
    if shell < 1 then
      error(
        format("bad argument to '%s' (options.shell: 1 or more, got %d)", function_name, shell),
        3
      )
    end
  end
  return cx,
    cy,
    cz,
    rx,
    ry,
    rz,
    index,
    shell,
    block_set(options.only, "options.only", function_name)
end

function Chunk.ellipsoid(self, cx, cy, cz, rx, ry, rz, block, options)
  want_chunk(self, "ellipsoid")
  draw_ellipsoid(ellipsoid_args(cx, cy, cz, rx, ry, rz, block, options, "ellipsoid"))
end

-- A structure: what to draw, recorded once, drawn chunk by chunk (each draws the part inside it).

local Structure = { __metatable = false }
Structure.__index = Structure
local structure_data = setmetatable({}, { __mode = "k" })

local FILL, ELLIPSOID, LOOT, SPRINKLE = 1, 2, 3, 4

local function structure_of(self, function_name)
  local data = structure_data[self]
  if data == nil then
    error(
      format("call %s with ':' on a structure: structure:%s(...)", function_name, function_name),
      3
    )
  end
  return data
end

-- Records an operation over the box x1..x2, y1..y2, z1..z2 and grows the structure's bounds to take it in.
local function record(data, op, x1, y1, z1, x2, y2, z2)
  op.x1, op.y1, op.z1 = min(x1, x2), min(y1, y2), min(z1, z2)
  op.x2, op.y2, op.z2 = max(x1, x2), max(y1, y2), max(z1, z2)
  local ops = data.ops
  ops[#ops + 1] = op
  if data.x1 == nil then
    data.x1, data.y1, data.z1, data.x2, data.y2, data.z2 = op.x1, op.y1, op.z1, op.x2, op.y2, op.z2
  else
    data.x1, data.y1, data.z1 = min(data.x1, op.x1), min(data.y1, op.y1), min(data.z1, op.z1)
    data.x2, data.y2, data.z2 = max(data.x2, op.x2), max(data.y2, op.y2), max(data.z2, op.z2)
  end
end

function terrain.structure()
  local structure = setmetatable({}, Structure)
  structure_data[structure] = { ops = {} }
  return structure
end

function Structure.fill(self, x1, y1, z1, x2, y2, z2, block)
  local data = structure_of(self, "fill")
  x1, y1, z1 = want_integer(x1, 1, "fill"), want_integer(y1, 2, "fill"), want_integer(z1, 3, "fill")
  x2, y2, z2 = want_integer(x2, 4, "fill"), want_integer(y2, 5, "fill"), want_integer(z2, 6, "fill")
  record(data, { FILL, block_index(block, 7, "fill") }, x1, y1, z1, x2, y2, z2)
  return self
end

function Structure.set(self, x, y, z, block)
  local data = structure_of(self, "set")
  x, y, z = want_integer(x, 1, "set"), want_integer(y, 2, "set"), want_integer(z, 3, "set")
  record(data, { FILL, block_index(block, 4, "set") }, x, y, z, x, y, z)
  return self
end

function Structure.ellipsoid(self, cx, cy, cz, rx, ry, rz, block, options)
  local data = structure_of(self, "ellipsoid")
  local x, y, z, a, b, c, index, shell, only =
    ellipsoid_args(cx, cy, cz, rx, ry, rz, block, options, "ellipsoid")
  record(
    data,
    { ELLIPSOID, x, y, z, a, b, c, index, shell, only },
    x - a,
    y - b,
    z - c,
    x + a,
    y + b,
    z + c
  )
  return self
end

function Structure.loot(self, x, y, z, table_name)
  local data = structure_of(self, "loot")
  x, y, z = want_integer(x, 1, "loot"), want_integer(y, 2, "loot"), want_integer(z, 3, "loot")
  if type(table_name) ~= "string" then
    error(format("bad argument #4 to 'loot' (loot table expected, got %s)", name_of(table_name)), 2)
  end
  local index = loot_index[table_name]
  if index == nil then
    error(
      format(
        "\"%s\" isn't a loot table this generator fills: list it in the file's script.loot",
        table_name
      ),
      2
    )
  end
  record(data, { LOOT, index }, x, y, z, x, y, z)
  return self
end

function Structure.sprinkle(self, x1, y1, z1, x2, y2, z2, block, count, options)
  local data = structure_of(self, "sprinkle")
  x1, y1, z1 =
    want_integer(x1, 1, "sprinkle"),
    want_integer(y1, 2, "sprinkle"),
    want_integer(z1, 3, "sprinkle")
  x2, y2, z2 =
    want_integer(x2, 4, "sprinkle"),
    want_integer(y2, 5, "sprinkle"),
    want_integer(z2, 6, "sprinkle")
  local index = block_index(block, 7, "sprinkle")
  count = want_integer(count, 8, "sprinkle")
  if count < 0 then
    error(format("bad argument #8 to 'sprinkle' (a count is 0 or more, got %d)", count), 2)
  end
  options = options_of(options, SPRINKLE_KEYS, "sprinkle")
  -- Its places are drawn from a number of its own, the same whichever chunk draws it.
  local own = options.seed ~= nil and want_integer(options.seed, 9, "sprinkle")
    or env.math.random(0, 2147483647)
  record(
    data,
    { SPRINKLE, index, count, own, block_set(options.only, "options.only", "sprinkle") },
    x1,
    y1,
    z1,
    x2,
    y2,
    z2
  )
  return self
end

function Structure.bounds(self)
  local data = structure_of(self, "bounds")
  if data.x1 == nil then
    return nil
  end
  return data.x1, data.y1, data.z1, data.x2, data.y2, data.z2
end

-- Whether a box (offset by dx, dz) reaches into the chunk.
local function touches(x1, z1, x2, z2, dx, dz)
  local x0, z0 = chunk_x * 16, chunk_z * 16
  return x2 + dx >= x0 and x1 + dx <= x0 + 15 and z2 + dz >= z0 and z1 + dz <= z0 + 15
end

local function place(data, dx, dy, dz)
  if data.x1 == nil or not touches(data.x1, data.z1, data.x2, data.z2, dx, dz) then
    return
  end
  local x0, z0 = chunk_x * 16, chunk_z * 16
  for _, op in ipairs(data.ops) do
    if touches(op.x1, op.z1, op.x2, op.z2, dx, dz) then
      local kind = op[1]
      if kind == FILL then
        host_fill(op.x1 + dx, op.y1 + dy, op.z1 + dz, op.x2 + dx, op.y2 + dy, op.z2 + dz, op[2])
      elseif kind == ELLIPSOID then
        draw_ellipsoid(
          op[2] + dx,
          op[3] + dy,
          op[4] + dz,
          op[5],
          op[6],
          op[7],
          op[8],
          op[9],
          op[10]
        )
      elseif kind == LOOT then
        host_set_loot(op.x1 + dx, op.y1 + dy, op.z1 + dz, op[2])
      else
        local n, only = op[4], op[5]
        local w, h, d = op.x2 - op.x1 + 1, op.y2 - op.y1 + 1, op.z2 - op.z1 + 1
        for _ = 1, op[3] do
          n = (n * 1103515245 + 12345) % 2147483648
          local x = op.x1 + n % w + dx
          n = (n * 1103515245 + 12345) % 2147483648
          local y = op.y1 + n % h + dy
          n = (n * 1103515245 + 12345) % 2147483648
          local z = op.z1 + n % d + dz
          if x >= x0 and x <= x0 + 15 and z >= z0 and z <= z0 + 15 then
            fill_row(x, y, z, z, op[2], only)
          end
        end
      end
    end
  end
end

function Chunk.place_structure(self, structure, x, y, z)
  want_chunk(self, "place_structure")
  local data = structure_data[structure]
  if data == nil then
    error(
      format(
        "bad argument #1 to 'place_structure' (structure expected, got %s)",
        name_of(structure)
      ),
      2
    )
  end
  place(
    data,
    x == nil and 0 or want_integer(x, 2, "place_structure"),
    y == nil and 0 or want_integer(y, 3, "place_structure"),
    z == nil and 0 or want_integer(z, 4, "place_structure")
  )
end

-- At most one structure in each cell of a grid, each within `reach` cells of its own, drawn where they reach.

local Scatter = { __metatable = false }
Scatter.__index = Scatter
local scatter_data = setmetatable({}, { __mode = "k" })

local function scatter_of(self, function_name)
  local data = scatter_data[self]
  if data == nil then
    error(format("call %s with ':' on a scatter: scatter:%s(...)", function_name, function_name), 3)
  end
  return data
end

function terrain.scatter(name, size, make, reach)
  if running ~= "load" then
    error("terrain.scatter is made in the script's body, once, not in a stage", 2)
  end
  if type(make) ~= "function" then
    error(format("bad argument #3 to 'scatter' (function expected, got %s)", name_of(make)), 2)
  end
  reach = reach == nil and 1 or want_integer(reach, 4, "scatter")
  if reach < 0 or reach > 8 then
    error(
      format(
        "bad argument #4 to 'scatter' (how many cells a structure reaches past its own, 0 to 8, not %d)",
        reach
      ),
      2
    )
  end
  local plan = terrain.plan(name, size, function(cell_x, cell_z)
    local made = make(cell_x, cell_z)
    if made ~= nil and structure_data[made] == nil then
      error(
        format(
          "a scatter's make returns a structure (terrain.structure()) or nil, not %s",
          name_of(made)
        ),
        0
      )
    end
    return made
  end)
  local scatter = setmetatable({}, Scatter)
  scatter_data[scatter] = { plan = plan, size = want_integer(size, 2, "scatter"), reach = reach }
  return scatter
end

function Scatter.place(self, target)
  local data = scatter_of(self, "place")
  want_chunk(target, "place")
  local size, reach = data.size, data.reach
  local x0, z0 = chunk_x * 16, chunk_z * 16
  for cell_x = x0 // size - reach, (x0 + 15) // size + reach do
    for cell_z = z0 // size - reach, (z0 + 15) // size + reach do
      local structure = data.plan:get(cell_x, cell_z)
      if structure ~= nil then
        place(structure_data[structure], 0, 0, 0)
      end
    end
  end
end

function Scatter.get(self, cell_x, cell_z)
  return scatter_of(self, "get").plan:get(cell_x, cell_z)
end

function Scatter.at(self, x, z)
  return scatter_of(self, "at").plan:at(x, z)
end

-- ---- what Kotlin calls --------------------------------------------------------------------------------------
-- Kotlin passes whole numbers as plain numbers (a 64-bit integer is slow to make in JS), so each is made an
-- integer here before a script sees it; a height goes back as a float for the same reason.

local function begin(seed_of_call, stage)
  used, step, failure, check_memory = 0, HOOK_EVERY, nil, false
  call_seed = tointeger(seed_of_call) --[[@as integer]]
  seeded, current_rng = false, nil
  running = stage
  debug_sethook(hook, "", HOOK_EVERY)
end

local function finish(ok, result)
  debug_sethook()
  in_chunk, in_height, running, current_rng = false, false, nil, nil
  if failure ~= nil then
    error(failure, 0)
  end
  if not ok then
    error(
      type(result) == "string" and result or "an error that isn't a message: " .. tostring(result),
      0
    )
  end
  return result
end

local STAGES =
  { height = true, density = true, area = true, biome = true, terrain = true, decorate = true }

-- Loads the script at [path] and runs its body; answers the stages it has, "height,terrain" (sorted). The lists are
-- newline-separated names, in the file's order.
---@param path string
---@param budget_of_call number
---@param memory number
---@param seed_text string
---@param lowest number
---@param highest number
---@param sea number
---@param noise_names string
---@param areas string
---@param biomes string
---@param palette string
---@param volumes string
---@param loot string
---@return string
function nf_load(
  path,
  budget_of_call,
  memory,
  seed_text,
  lowest,
  highest,
  sea,
  noise_names,
  areas,
  biomes,
  palette,
  volumes,
  loot
)
  budget = tointeger(budget_of_call) --[[@as integer]]
  memory_mb = tointeger(memory) --[[@as integer]]
  memory_kb = memory_mb * 1024
  seed = tonumber(seed_text) --[[@as number]]
  min_y = tointeger(lowest) --[[@as integer]]
  max_y = tointeger(highest) --[[@as integer]]
  sea_level = tointeger(sea) --[[@as integer]]
  area_names, biome_names = lines(areas), lines(biomes)
  for index, name in ipairs(area_names) do
    area_index[name] = index - 1
  end
  for _, name in ipairs(lines(volumes)) do
    volume_set[name] = true
  end
  for index, name in ipairs(lines(loot)) do
    loot_index[name] = index - 1
  end
  for index, label in ipairs(lines(palette)) do
    labels[index - 1] = label
  end
  for index, name in ipairs(lines(noise_names)) do
    local noise = setmetatable({}, Noise)
    noise_index[noise] = index - 1
    noises[name] = noise
  end
  sentinel()
  local body = host_load(path)
  debug_setupvalue(body, 1, env)
  begin(0, "load")
  local ok, result = pcall(body, terrain)
  result = finish(ok, result)
  if type(result) ~= "table" then
    error(
      path
        .. ": the script must return a table of its stages (height, density, area, biome, terrain, decorate), not "
        .. type(result),
      0
    )
  end
  local names = {}
  for key, value in next, result do
    if not STAGES[key] then
      error(
        path
          .. ': "'
          .. tostring(key)
          .. "\" isn't a stage: a script's stages are height, density, area, biome, terrain and decorate",
        0
      )
    end
    if type(value) ~= "function" then
      error(path .. ": the stage " .. key .. " must be a function, not " .. type(value), 0)
    end
    names[#names + 1] = key
  end
  sort(names)
  stages = {
    height = rawget(result, "height"),
    density = rawget(result, "density"),
    area = rawget(result, "area"),
    biome = rawget(result, "biome"),
    terrain = rawget(result, "terrain"),
    decorate = rawget(result, "decorate"),
  }
  return concat(names, ",")
end

-- The height of column ([x], [z]) from the file's [height]: a whole number, as a float.
---@param x number
---@param z number
---@param height number
---@param seed_of_call number
---@return number
function nf_height(x, z, height, seed_of_call)
  begin(seed_of_call, "height")
  in_height = true
  local ok, result = pcall(stages.height, tointeger(x), tointeger(z), tointeger(height))
  result = finish(ok, result)
  if not finite(result) then
    local info = debug_getinfo(stages.height, "S")
    error(
      format(
        "%s:%d: the height stage must return a number, not %s",
        info.short_src,
        info.linedefined,
        name_of(result)
      ),
      0
    )
  end
  return floor(result) + 0.0
end

-- The density at the grid's point ([x], [y], [z]) from the file's [value] there: a float.
---@param x number
---@param y number
---@param z number
---@param value number
---@param seed_of_call number
---@return number
function nf_density(x, y, z, value, seed_of_call)
  begin(seed_of_call, "density")
  local ok, result = pcall(stages.density, tointeger(x), tointeger(y), tointeger(z), value + 0.0)
  result = finish(ok, result)
  if not finite(result) then
    local info = debug_getinfo(stages.density, "S")
    error(
      format(
        "%s:%d: the density stage must return a number, not %s",
        info.short_src,
        info.linedefined,
        name_of(result)
      ),
      0
    )
  end
  return result + 0.0
end

-- What an area stage returned, as an index in the file's areas: [columns] when it must be a column's own.
local function area_result(stage, result, columns)
  local index = type(result) == "string" and area_index[result] or nil
  if index == nil or (columns and volume_set[result]) then
    local info = debug_getinfo(stage, "S")
    local given = type(result) == "string" and format('"%s"', result) or name_of(result)
    local which = columns and "one of the file's biome areas that isn't limited by height"
      or "one of the file's biome areas"
    error(
      format(
        "%s:%d: the %s stage must return the name of %s, not %s",
        info.short_src,
        info.linedefined,
        columns and "area" or "biome",
        which,
        given
      ),
      0
    )
  end
  return index + 0.0
end

-- The area of column ([x], [z]) from the file's (an index): an index, as a float.
---@param x number
---@param z number
---@param file number
---@param seed_of_call number
---@return number
function nf_area(x, z, file, seed_of_call)
  begin(seed_of_call, "area")
  local ok, result = pcall(stages.area, tointeger(x), tointeger(z), area_names[tointeger(file) + 1])
  return area_result(stages.area, finish(ok, result), true)
end

-- The area of the place ([x], [y], [z]) from the file's: an index, as a float.
---@param x number
---@param y number
---@param z number
---@param file number
---@param seed_of_call number
---@return number
function nf_biome(x, y, z, file, seed_of_call)
  begin(seed_of_call, "biome")
  local ok, result =
    pcall(stages.biome, tointeger(x), tointeger(y), tointeger(z), area_names[tointeger(file) + 1])
  return area_result(stages.biome, finish(ok, result), false)
end

-- Runs the chunk stage [stage] on chunk ([x], [z]).
---@param stage string
---@param x number
---@param z number
---@param seed_of_call number
function nf_chunk(stage, x, z, seed_of_call)
  begin(seed_of_call, stage)
  chunk_x = tointeger(x) --[[@as integer]]
  chunk_z = tointeger(z) --[[@as integer]]
  in_chunk = true
  finish(pcall(stages[stage], chunk))
end
