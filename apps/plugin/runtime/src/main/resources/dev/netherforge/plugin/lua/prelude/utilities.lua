-- `nf.random`'s generators and `nf.math`: plain Lua, with nothing to ask the
-- server. These are the bodies of their functions, called by the generated
-- wrappers with every argument checked (a namespace function gets the calling
-- scope first, which these have no use for).
--
-- A generator is xoshiro256** (what Lua 5.4's own math.random is), seeded
-- through splitmix64, in Lua's wrapping 64-bit integers: the same seed gives
-- the same numbers on every server. Its four state words live in a weak table
-- by its handle, so a generator the script lets go of is collected with it.

local std = require("std")
local args = require("args")
local handles = require("handles")
-- The sandbox first: the sort below is the library's capped one, as it is for a
-- script's own work, so sorting a script's big table is charged to it.
require("sandbox")

local format = std.format
local type, pairs, error, tostring, setmetatable = type, pairs, error, tostring, setmetatable
local want_number = args.want_number
local new = handles.new

local utilities = { random = {}, math = {}, Random = {}, test = {} }

do
  local Random, random, math_random, ult, sort, huge =
    utilities.Random, utilities.random, math.random, math.ult, table.sort, math.huge
  local states = setmetatable({}, { __mode = "k" })
  local generated_count = 0

  local function splitmix(x)
    x = x + 0x9E3779B97F4A7C15
    local z = x
    z = (z ~ (z >> 30)) * 0xBF58476D1CE4E5B9
    z = (z ~ (z >> 27)) * 0x94D049BB133111EB
    return x, z ~ (z >> 31)
  end

  -- Rotations written out: this runs for every number a script draws, and
  -- the budget counts each call.
  local function next_word(s)
    local s0, s1, s2, s3 = s[1], s[2], s[3], s[4]
    local m = s1 * 5
    local result = ((m << 7) | (m >> 57)) * 9
    local t = s1 << 17
    s2 = s2 ~ s0
    s3 = s3 ~ s1
    s1 = s1 ~ s2
    s0 = s0 ~ s3
    s2 = s2 ~ t
    s[1], s[2], s[3], s[4] = s0, s1, s2, (s3 << 45) | (s3 >> 19)
    return result
  end

  -- An integer from 0 to n, read as unsigned (so n = -1 is every word):
  -- the low bits that cover n, drawn again until they're within it.
  local function up_to(s, n)
    local mask = n | (n >> 1)
    mask = mask | (mask >> 2)
    mask = mask | (mask >> 4)
    mask = mask | (mask >> 8)
    mask = mask | (mask >> 16)
    mask = mask | (mask >> 32)
    local r = next_word(s) & mask
    while ult(n, r) do
      r = next_word(s) & mask
    end
    return r
  end

  -- From 0 up to 1: the top 53 bits, which a double holds exactly.
  local function next_fraction(s)
    return (next_word(s) >> 11) * 0x1p-53
  end

  -- A method's generator is `states[self]`: its wrapper has checked that
  -- `self` is a genuine Random.

  function random.new(_, seed)
    local x = seed or math_random(0)
    local s = {}
    for i = 1, 4 do
      x, s[i] = splitmix(x)
    end
    generated_count = generated_count + 1
    local generator = new.Random(generated_count)
    states[generator] = s
    return generator
  end

  function Random.integer(self, min, max)
    local s = states[self]
    if min > max then
      error(format("bad argument 'min' (%d is greater than max, %d)", min, max), 2)
    end
    return min + up_to(s, max - min)
  end

  function Random.number(self, min, max)
    local s = states[self]
    if min == nil and max == nil then
      return next_fraction(s)
    end
    want_number(min, "min", 2)
    want_number(max, "max", 2)
    return min + next_fraction(s) * (max - min)
  end

  function Random.pick(self, list)
    local s = states[self]
    local n = #list
    if n == 0 then
      return nil
    end
    return list[1 + up_to(s, n - 1)]
  end

  function Random.shuffle(self, list)
    local s = states[self]
    for i = #list, 2, -1 do
      local j = 1 + up_to(s, i - 1)
      list[i], list[j] = list[j], list[i]
    end
  end

  -- Keys in an order that doesn't depend on the table's insertion history:
  -- numbers, then strings, then booleans, each by value; anything else after.
  local key_ranks = { number = 1, string = 2, boolean = 3 }
  local function key_before(a, b)
    local ta, tb = type(a), type(b)
    if ta ~= tb then
      local ra, rb = key_ranks[ta] or 4, key_ranks[tb] or 4
      if ra ~= rb then
        return ra < rb
      end
      return ta < tb
    end
    if ta == "number" or ta == "string" then
      return a < b
    elseif ta == "boolean" then
      return b and not a
    end
    return tostring(a) < tostring(b)
  end

  function Random.weighted(self, weights)
    local s = states[self]
    local keys, total = {}, 0
    for key, weight in pairs(weights) do
      if type(weight) ~= "number" or not (weight >= 0 and weight < huge) then
        error(
          format(
            "bad argument 'weights' (the weight of %s must be a number from 0 up, not %s)",
            tostring(key),
            tostring(weight)
          ),
          2
        )
      end
      if weight > 0 then
        keys[#keys + 1] = key
        total = total + weight
      end
    end
    if #keys == 0 then
      return nil
    end
    sort(keys, key_before)
    local r = next_fraction(s) * total
    for i = 1, #keys - 1 do
      r = r - weights[keys[i]]
      if r < 0 then
        return keys[i]
      end
    end
    return keys[#keys]
  end

  local maths = utilities.math

  function maths.lerp(_, from, to, fraction)
    return from + (to - from) * fraction
  end

  function maths.clamp(_, value, min, max)
    if min > max then
      error(format("bad argument 'min' (%s is greater than max, %s)", min, max), 2)
    end
    if value < min then
      return min
    elseif value > max then
      return max
    end
    return value
  end

  function maths.remap(_, value, from_min, from_max, to_min, to_max)
    if from_min == from_max then
      error(
        format("bad argument 'from_max' (the range from %s to itself has no size)", from_min),
        2
      )
    end
    return to_min + (value - from_min) * (to_max - to_min) / (from_max - from_min)
  end

  -- The curves animation keyframes use (format's Easing), except that "step"
  -- reaches 1 at the end: a script asks for the value there, a keyframe never does.
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

  function maths.ease(_, easing, fraction)
    if fraction < 0 then
      fraction = 0
    elseif fraction > 1 then
      fraction = 1
    end
    return curves[easing](fraction)
  end
end

-- nf.test.terrain: a stand-in for a terrain script's `terrain`, for testing worldgen modules on their own.
do
  local floor, randomseed = math.floor, math.randomseed

  -- An option that's a value, or a function of the place giving one.
  local function at(option, default, ...)
    if option == nil then
      return default
    end
    if type(option) == "function" then
      return option(...)
    end
    return option
  end

  function utilities.test.terrain(_, options)
    options = options or {}
    if type(options) ~= "table" then
      error(format("bad argument 'options' (table expected, got %s)", type(options)), 2)
    end
    local noises = options.noises or {}
    local seed = options.seed or 0
    local terrain = {}
    function terrain.seed()
      return seed
    end
    function terrain.min_y()
      return options.min_y or -64
    end
    function terrain.max_y()
      return options.max_y or 319
    end
    function terrain.sea_level()
      return options.sea_level or 63
    end
    function terrain.noise(name)
      local value = noises[name]
      return {
        at = function(_, x, y, z)
          return at(value, 0, x, y, z)
        end,
      }
    end
    function terrain.height(x, z)
      return floor(at(options.height, 64, x, z))
    end
    function terrain.area(x, y, z)
      return at(options.area, "default", x, y, z)
    end
    function terrain.biome(x, y, z)
      return at(options.biome, "minecraft:plains", x, y, z)
    end
    function terrain.plan(name, size, make)
      local cells = {}
      local plan = {}
      local function cell(cell_x, cell_z)
        local key = cell_x .. "," .. cell_z
        local made = cells[key]
        if made == nil then
          -- Numbers of the cell's own, the same whenever it's made.
          randomseed(seed + cell_x * 7919 + cell_z * 104729 + #name)
          made = make(cell_x, cell_z)
          if made == nil then
            made = false
          end
          cells[key] = made
        end
        return made or nil
      end
      function plan.get(_, cell_x, cell_z)
        return cell(cell_x, cell_z)
      end
      function plan.at(_, x, z)
        local cell_x, cell_z = x // size, z // size
        return cell(cell_x, cell_z), cell_x, cell_z
      end
      function plan.size()
        return size
      end
      return plan
    end
    return terrain
  end
end

return {
  body = {
    ["nf.random"] = utilities.random,
    ["nf.math"] = utilities.math,
    ["nf.test"] = utilities.test,
    Random = utilities.Random,
  },
}
