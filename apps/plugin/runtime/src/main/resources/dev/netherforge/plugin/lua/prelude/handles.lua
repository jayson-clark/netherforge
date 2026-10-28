-- Handles. A handle is an empty table standing for one key in the runtime's
-- handle table (`ids[handle]`, an integer): Kotlin keeps what the handle is (a
-- UUID, a node's name) and Lua never sees it, so a script can't forge a
-- handle or read what it's made of except through its methods. Kotlin makes
-- the tables (`LuaHost.pushHandle`) and finds a key's table in `cache`, so two
-- handles to the same thing are the same table and compare equal.
--
-- A handle's metatable is its class's, and the runtime may give it a more
-- specific one later (an Entity handed out while its entity was unloaded is
-- the Mob it is once it's back): the same table, with more methods. When Lua
-- lets go of a table, its key goes back to the runtime: `__gc` notes it, and
-- `take_released` hands over those whose key has no table again.
--
-- The typed values (`Vec3`, `Location`) are registered here too, as kinds, so
-- Kotlin can tell what a table is by its genuine metatable (`kinds`): no
-- script can reach those metatables to fake one.

local input = require("input")

local prim = input.prim
local error, tostring, setmetatable, getmetatable, pairs =
  error, tostring, setmetatable, getmetatable, pairs
local bad = require("args").bad

-- Every handle's key in the runtime's handle table. Held weakly.
---@type table<table, integer>
local ids = setmetatable({}, { __mode = "k" })

-- What each genuine typed value is, by its metatable: a handle's class,
-- "Vec3" or "Location", as its position in `kind_names` (a number is cheaper
-- for Kotlin to read than a name). Kotlin reads it (and `ids`) to tell what a
-- value it's given is.
---@type table<table, integer>, string[]
local kinds, kind_names = {}, {}

---@param mt table
---@param name string
local function kind(mt, name)
  kind_names[#kind_names + 1] = name
  kinds[mt] = #kind_names
end

-- The handle classes that extend another (a Mob is a Living is an Entity), by
-- name: the class each extends. Set by `class` below.
---@type table<string, string>
local parents = {}

-- Whether `value` is a handle of `class_name`, or of a class below it in its
-- chain, so a Mob goes wherever a Living or an Entity does.
---@param value any
---@param class_name string
---@return boolean
local function is_a(value, class_name)
  if ids[value] == nil then
    return false
  end
  local name = getmetatable(value)
  while name ~= nil do
    if name == class_name then
      return true
    end
    name = parents[name]
  end
  return false
end

-- The handle constructors by class (generated: `new.World(name)`), filled in
-- when the bindings load: a handle the prelude makes itself (a `Task`, a world
-- named in saved data).
---@type table<string, fun(...): table>
local new = {}

-- Each handle class's key as the runtime holds it (generated, from the spec's
-- `handle`): `keys.Task(task)` is its id. Filled in with `new`.
---@type table<string, fun(handle: table): ...>
local keys = {}

-- Each class's set of the metatable names its handles may have: the class and
-- every class below it (`Entity`'s has `Mob`), so the bindings' inline `:`
-- check is one lookup rather than a walk up the chain (`is_a`). A class's set
-- is made when first asked for, so the bindings can hold it before the classes
-- are all declared; `fill_accepts` fills them once they are.
---@type table<string, table<string, true>>
local accepts = setmetatable({}, {
  __index = function(sets, class_name)
    local set = {}
    sets[class_name] = set
    return set
  end,
})

local cache = setmetatable({}, { __mode = "v" }) -- key -> handle
local released = {} -- keys whose tables were collected
local metatables = {} -- class -> its handles' metatable, for Kotlin
local classes = {} -- class -> its methods

---@param name string
---@param parent string?
---@return table methods The class's methods, which the bindings fill.
local function class(name, parent)
  parents[name] = parent
  local methods = {}
  local mt = {
    __index = methods,
    -- Handles are cached and shared by every scope that holds one, so a
    -- field set on one would change it for all of them.
    __newindex = function()
      error(name .. " handles can't be changed", 2)
    end,
    __metatable = name,
    __name = name,
    -- Its class and the first of its key values: "Player: <uuid>", "Node: <centity>".
    __tostring = function(h)
      return name .. ": " .. tostring((prim["handles.key"](h)))
    end,
    __gc = function(h)
      local key = ids[h]
      if key ~= nil then
        released[#released + 1] = key
      end
    end,
  }
  classes[name] = methods
  metatables[name] = mt
  kind(mt, name)
  return methods
end

-- Puts each class in its own set and in those of every class above it. From
-- then on a name that isn't a class has no set (nil), rather than a new one.
local function fill_accepts()
  for class_name in pairs(classes) do
    local name = class_name
    while name ~= nil do
      accepts[name][class_name] = true
      name = parents[name]
    end
  end
  setmetatable(accepts, nil)
end

-- A method's `self`: its key in the handle table, if it's one of class_name's.
-- Called straight from the method, so the error lands on the method's caller.
---@param self any
---@param class_name string
---@return integer
local function self_of(self, class_name)
  if not is_a(self, class_name) then
    error(
      "call " .. class_name .. " methods with ':' on a " .. class_name .. ", like value:method()",
      3
    )
  end
  return ids[self]
end

local function want_handle(value, class_name, name)
  if not is_a(value, class_name) then
    bad(name, class_name, value, 3)
  end
end

-- Whether `value` is a handle of `class_name`, asking the runtime what it is
-- now when its class is one above `class_name` (an Entity that may be a Mob).
local function is_kind(value, class_name)
  if ids[value] == nil then
    return false
  end
  local accepted = accepts[class_name]
  local name = getmetatable(value)
  if accepted[name] then
    return true
  end
  -- One above it: `class_name` is in its set.
  local above = accepts[name]
  if above ~= nil and above[class_name] then
    return prim["handles.refine"](value) and accepted[getmetatable(value)] == true
  end
  return false
end

-- The keys of the handle tables Lua has let go of since the last time, as a
-- list: those whose key has no table again (Kotlin may have pushed the same
-- thing again since, which made a new table for the same key).
local function take_released()
  local gone = {}
  for _, key in ipairs(released) do
    if cache[key] == nil then
      gone[#gone + 1] = key
    end
  end
  released = {}
  return gone
end

return {
  ids = ids,
  kinds = kinds,
  kind_names = kind_names,
  kind = kind,
  parents = parents,
  is_a = is_a,
  new = new,
  keys = keys,
  cache = cache,
  metatables = metatables,
  classes = classes,
  class = class,
  accepts = accepts,
  fill_accepts = fill_accepts,
  self_of = self_of,
  want_handle = want_handle,
  is_kind = is_kind,
  take_released = take_released,
}
