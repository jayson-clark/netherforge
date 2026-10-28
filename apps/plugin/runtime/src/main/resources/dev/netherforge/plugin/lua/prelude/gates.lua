-- Version gates: what the spec marks `since` a Minecraft version newer than
-- this server's. The host lists only those, so on a server new enough for
-- everything these tables are empty and nothing here costs a thing. Calling
-- such a function, listening for such an event or setting such an option is
-- an error naming the version.

local std = require("std")
local input = require("input")

local raw, format = std.raw, std.format
local pairs, error = pairs, error

---@class nf.Gated
---@field functions table<string, table<string, string>> owner -> name -> the version it needs
---@field events table<string, table<string, string>>
---@field options table<string, table<string, string>>

---@type string
local server_version
---@type nf.Gated
local gated = { functions = {}, events = {}, options = {} }
do
  local found = { input.prim.gated() }
  server_version = found[1]
  for i = 2, #found, 3 do
    local kind, key, since = found[i], found[i + 1], found[i + 2]
    local owner, name = raw.match(key, "^(.*)%.([^.]+)$")
    local by_owner = gated[kind][owner] or {}
    by_owner[name] = since
    gated[kind][owner] = by_owner
  end
end

local function needs_version(what, since)
  return format("%s needs Minecraft %s (this server runs %s)", what, since, server_version)
end

-- Replaces each gated function in `target` (the class or namespace `owner`)
-- with one that only says which version it needs. `separator` is how it's
-- called: ":" on a handle, "." on a namespace.
local function gate_functions(target, owner, separator)
  for name, since in pairs(gated.functions[owner] or {}) do
    local message = needs_version(owner .. separator .. name, since)
    target[name] = function()
      error(message, 2)
    end
  end
end

return {
  gated = gated,
  needs_version = needs_version,
  gate_functions = gate_functions,
}
