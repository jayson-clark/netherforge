-- NetherForge's Lua prelude: the sandbox, the hook (the instruction budget,
-- the time limit, memory), the handle cache, the hand-written part of the
-- API, scopes and `require`, and the entry points the Kotlin host calls. The
-- rest of the API (every function the spec in packages/api implements in
-- Kotlin) is the generated bindings.lua, which the `api` module runs with the
-- core.
--
-- It is a set of modules (prelude/*.lua), each a chunk that returns the table
-- of what it exports, and this entry that loads them. A module gets what it
-- needs with `require`, which here is the prelude's own: it runs each module
-- once and hands every requirer the same table, so what a module exports is
-- what it returns, explicitly. Their order isn't written down anywhere: each
-- requires what it uses. (The real `require` is gone from the globals by the
-- time any script runs; so is this one, when the last module is loaded.)
--
-- It runs once per Lua state, before any project code. This entry is the
-- chunk "=nf", the modules "=nf:<name>" (which is how error locations skip
-- their frames), the generated bindings "=nf/bindings" and the standard
-- library's caps "=nf/caps". It receives the table of Kotlin primitives, the
-- compiled bindings, the compiled caps, the host's limits and the compiled
-- modules (by name) as its arguments, and keeps them where no script can
-- ever reach them. It returns the host table the Kotlin side drives.

local prim, bindings, caps, limits, chunks = ...

---@type table<string, any> Each module's exports, by name; `input` is what this entry was given.
local loaded = {
  input = { prim = prim, bindings = bindings, caps = caps, limits = limits },
}
-- Modules running now, to say so when two need each other.
local loading = {}

---@param name string
---@return any
function require(name)
  local done = loaded[name]
  if done ~= nil then
    return done
  end
  if loading[name] then
    error("prelude modules " .. name .. " and one it requires need each other", 2)
  end
  local chunk = chunks[name]
  if chunk == nil then
    error("no prelude module " .. name, 2)
  end
  loading[name] = true
  local exports = chunk()
  loading[name] = nil
  loaded[name] = exports
  return exports
end

-- The standard library as it is now, before the sandbox changes it.
require("std")

local host = require("host")
require("sandbox").seal()
return host
