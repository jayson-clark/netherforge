-- Argument checks. They raise at the caller of the API function: `bad` is
-- always called from a check, which is called straight from the function
-- (a generated wrapper: "=nf/bindings"), so level 3 is the script's line.
--
-- The generated bindings call these before a hand-written (`impl: "lua"`)
-- function's body runs, so the bodies themselves hold only the logic.

local std = require("std")

local format, floor, math_type = std.format, std.floor, std.math_type
local type, error = type, error

---@param name string The parameter.
---@param expected string
---@param value any
---@param level integer Counted from the check that calls this: 3 is its caller's caller.
local function bad(name, expected, value, level)
  error(format("bad argument '%s' (%s expected, got %s)", name, expected, type(value)), level + 1)
end

local function want(value, kind, name)
  if type(value) ~= kind then
    bad(name, kind, value, 3)
  end
  return value
end

local function want_opt(value, kind, name)
  if value ~= nil and type(value) ~= kind then
    bad(name, kind .. " or nil", value, 3)
  end
  return value
end

local function whole(value)
  return math_type(value) == "integer" or (type(value) == "number" and value == floor(value))
end

-- A number, for a check that isn't called straight from the function (`level`
-- says how far up the caller is, as for `bad`).
local function want_number(value, name, level)
  if type(value) ~= "number" then
    bad(name, "number", value, level + 1)
  end
  return value
end

-- An `integer` parameter: any whole number, which arrives as an integer.
local function want_integer(value, name)
  if not whole(value) then
    bad(name, "whole number", value, 3)
  end
  return floor(value)
end

local function want_integer_opt(value, name)
  if value == nil then
    return nil
  end
  if not whole(value) then
    bad(name, "whole number or nil", value, 3)
  end
  return floor(value)
end

-- A string that must be one of a set of choices: a union of string literals in
-- the spec. The generated bindings build each set once (`choice.set`), so a
-- check is one lookup.

---@class nf.ChoiceSet
---@field has table<string, true>
---@field list string[]

local choice = {}

---@param list string[] The choices, in the spec's order.
---@return nf.ChoiceSet
function choice.set(list)
  local has = {}
  for _, option in ipairs(list) do
    has[option] = true
  end
  return { has = has, list = list }
end

---@param value any
---@param set nf.ChoiceSet
---@return boolean
function choice.is(value, set)
  return type(value) == "string" and set.has[value] == true
end

-- The message for a value that isn't one of `set`'s choices, at `where`.
function choice.bad(where, set, value)
  return format(
    "bad argument '%s' (one of %s expected, got %s)",
    where,
    '"' .. std.concat(set.list, '", "') .. '"',
    type(value) == "string" and ('"' .. value .. '"') or type(value)
  )
end

function choice.want(value, set, name)
  if not choice.is(value, set) then
    error(choice.bad(name, set, value), 3)
  end
  return value
end

function choice.want_opt(value, set, name)
  if value ~= nil and not choice.is(value, set) then
    error(choice.bad(name, set, value), 3)
  end
  return value
end

return {
  bad = bad,
  want = want,
  want_opt = want_opt,
  whole = whole,
  want_number = want_number,
  want_integer = want_integer,
  want_integer_opt = want_integer_opt,
  choice = choice,
}
