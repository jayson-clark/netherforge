-- Checked tables. The tables hand-written functions take (a goal's definition,
-- a dialog's options) are checked against the spec's shapes (`schema.shapes`):
-- an unknown key is an error, as in every option table. Mistakes are raised
-- with level 0: no prefix, so the error lands on the first project frame, the
-- line that made the call, however deep in the table the mistake is.

local std = require("std")
local args = require("args")
local gates = require("gates")
local schema = require("schema")
local values = require("values")

local raw, format, concat = std.raw, std.format, std.concat
local type, pairs, ipairs, tostring, error = type, pairs, ipairs, tostring, error
local whole = args.whole
local gated, needs_version = gates.gated, gates.needs_version
local is_vec3 = values.is_vec3

local function definition_error(message)
  error(message, 0)
end

local function sorted_keys(t)
  local keys = {}
  for key in pairs(t) do
    keys[#keys + 1] = key
  end
  raw.sort(keys)
  return keys
end

-- A table of shape `shape_name` from the spec: only its fields, each of its kind, the required ones there.
---@param value any
---@param where string The parameter's name, which an error starts with.
---@param shape_name string
---@return table value
local function check_shape(value, where, shape_name)
  if type(value) ~= "table" then
    definition_error(format("bad argument '%s' (table expected, got %s)", where, type(value)))
  end
  local shape = schema.shapes[shape_name]
  for key, since in pairs(gated.options[shape_name] or {}) do
    if value[key] ~= nil then
      definition_error(needs_version(format("'%s.%s'", where, key), since))
    end
  end
  for key, field in pairs(value) do
    local kind = type(key) == "string" and shape.fields[key] or nil
    if kind == nil then
      definition_error(
        format(
          "unknown field '%s.%s' (fields: %s)",
          where,
          tostring(key),
          concat(sorted_keys(shape.fields), ", ")
        )
      )
    end
    local ok
    if kind == "any" then
      ok = true
    elseif kind == "integer" then
      ok = whole(field)
    elseif kind == "Vec3" then
      ok = is_vec3(field)
    else
      ok = type(field) == kind
    end
    if not ok then
      definition_error(
        format("bad argument '%s.%s' (%s expected, got %s)", where, key, kind, type(field))
      )
    end
  end
  for _, key in ipairs(shape.required) do
    if value[key] == nil then
      definition_error(format("'%s.%s' is missing", where, key))
    end
  end
  return value
end

return { check_shape = check_shape }
