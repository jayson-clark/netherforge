-- The API: runs the generated bindings (src/generated/lua/.../bindings.lua,
-- from packages/api) with the prelude's core, which defines every function
-- scripts call: the classes and their constructors, each Kotlin-implemented
-- function's argument checks and call, and the wrapper that checks a
-- hand-written (`impl: "lua"`) function's arguments before calling its body
-- (`handwritten`). Then it finishes the classes: version gates, and the
-- methods a class takes from the ones it extends.

local std = require("std")
local input = require("input")
local args = require("args")
local handles = require("handles")
local gates = require("gates")
local values = require("values")
local checks = require("checks")
local tasks = require("tasks")
local handwritten = require("handwritten")

local prim = input.prim
local raw = std.raw
local pairs, ipairs, error, getmetatable, setmetatable, type =
  pairs, ipairs, error, getmetatable, setmetatable, type
local classes, parents, metatables = handles.classes, handles.parents, handles.metatables
local gated, needs_version, gate_functions = gates.gated, gates.needs_version, gates.gate_functions

---@class nf.Bindings
---@field fill_nf fun(scope: integer, nf: table) Fills a scope's `nf` with its own functions.
---@field namespaces table<string, fun(scope: integer, ns: table)> Fills a namespace's table (`nf.server`), by path.
---@field vec3 table<string, function> The functions on the `vec3` global (`vec3.from_yaw_pitch`).

---@type nf.Bindings
local generated = input.bindings({
  prim = prim,
  class = handles.class,
  self_of = handles.self_of,
  new = handles.new,
  keys = handles.keys,
  async = tasks.async,
  -- What the wrappers check arguments with.
  want = args.want,
  want_opt = args.want_opt,
  want_integer = args.want_integer,
  want_integer_opt = args.want_integer_opt,
  want_handle = handles.want_handle,
  choice = args.choice,
  check_shape = checks.check_shape,
  vector_arg = values.vector_arg,
  value_self = values.value_self,
  want_vec3 = values.want_vec3,
  want_vec3_opt = values.want_vec3_opt,
  -- What the inline checks read.
  ids = handles.ids,
  accepts = handles.accepts,
  type_of = type,
  class_of = getmetatable,
  number_kind = std.math_type,
  genuine_mt = std.raw_getmetatable,
  with_mt = setmetatable,
  Vec3_mt = values.Vec3_mt,
  Location_mt = values.Location_mt,
  -- What the wrappers call: the hand-written bodies, and the tables a value's methods go in.
  hand = handwritten,
  value_body = values.body,
  values = { Vec3 = values.Vec3, Location = values.Location },
})

handles.fill_accepts()

for class_name, methods in pairs(classes) do
  gate_functions(methods, class_name, ":")
end

-- A class that extends another (a Mob is a Living is an Entity) gets every
-- method it doesn't define itself from up its chain, generated or
-- hand-written: from its parent once the parent has its own parent's, so
-- each class is filled after those above it.
do
  local function depth(name)
    local n = 0
    while parents[name] ~= nil do
      name, n = parents[name], n + 1
    end
    return n
  end
  local order = {}
  for class_name in pairs(parents) do
    order[#order + 1] = class_name
  end
  raw.sort(order, function(a, b)
    local da, db = depth(a), depth(b)
    return da < db or (da == db and a < b)
  end)
  for _, class_name in ipairs(order) do
    local own, parent = classes[class_name], parents[class_name]
    for name, fn in pairs(classes[parent]) do
      if own[name] == nil then
        -- A gated one is named for the class it's called on: `Mob:health`, not `Living:health`.
        local since
        local up = parent
        while up ~= nil and since == nil and classes[up][name] == fn do
          since = (gated.functions[up] or {})[name]
          up = parents[up]
        end
        if since ~= nil then
          local message = needs_version(class_name .. ":" .. name, since)
          own[name] = function()
            error(message, 2)
          end
        else
          own[name] = fn
        end
      end
    end
  end

  -- A handle of a class others extend may be one the runtime knew less about
  -- when it handed it out (an Entity whose entity was unloaded then): a
  -- method its class doesn't have asks the runtime what it is now, which may
  -- give the table a more specific class, and looks again.
  for _, parent in pairs(parents) do
    local methods = classes[parent]
    metatables[parent].__index = function(h, key)
      local found = methods[key]
      if found == nil and prim["handles.refine"](h) then
        found = classes[getmetatable(h)][key]
      end
      return found
    end
  end
end

return generated
