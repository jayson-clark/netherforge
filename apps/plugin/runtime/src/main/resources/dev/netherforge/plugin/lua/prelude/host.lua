-- The host: what the Kotlin side calls. Every call into a script returns the
-- same five values: status ("ok" or "error"), the call's first result or the
-- error message, and for an error the project file, line and traceback.

local std = require("std")
local handles = require("handles")
local values = require("values")
local json = require("json")
local sandbox = require("sandbox")
local guard_module = require("guard")
local events = require("events")
local tasks = require("tasks")
local scopes = require("scopes")
local handwritten = require("handwritten")
local debugger = require("debugger")
require("api")
require("census")

local getinfo, getlocal, format, concat, unpack =
  std.getinfo, std.getlocal, std.format, std.concat, std.unpack
local type, pairs, ipairs, next, tostring, getmetatable =
  type, pairs, ipairs, next, tostring, getmetatable
local load = std.load
local guard, timing, invoke = guard_module.guard, guard_module.timing, guard_module.invoke
local describe, project_frame = guard_module.describe, guard_module.project_frame
local registry, listeners, NF = events.registry, events.listeners, events.NF
local class_of, key_of, new_event, deliver =
  events.class_of, events.key_of, events.new_event, events.deliver
local unsubscribe, subscriptions = events.unsubscribe, events.subscriptions
local set_event = events.set_event
local scope_subscriptions = events.scope_subscriptions
local scope_tasks, tasks_by_id, end_task = tasks.scope_tasks, tasks.tasks, tasks.end_task
local envs, loaded, load_path = scopes.envs, scopes.loaded, scopes.load_path
local encode_data, decode_data = json.encode, json.decode

-- `classes`, `handwritten`, `values`, `event_methods` and `base` are there for the conformance
-- test, which holds the runtime to exactly what packages/api/ declares.
local host = {
  classes = handles.classes,
  handwritten = handwritten,
  values = { Vec3 = values.Vec3, Location = values.Location },
  event_methods = events.Event,
  base = sandbox.base,
}

-- Calls a function the host kept (a timer, a command handler) as `scope`'s code.
host.invoke = invoke

-- `invoke`, timed as a call of `kind` ("command", "complete"): for a function
-- Kotlin keeps and calls for something it knows the kind of.
function host.invoke_as(kind, budget, scope, fn, ...)
  timing.next_kind = kind
  return invoke(budget, scope, fn, ...)
end

-- A scope's globals: see `scopes.new_env`.
host.new_env = scopes.new_env

-- For the conformance test: a function's parameter names, comma-separated,
-- and whether it also takes `...`.
function host.params(fn)
  if type(fn) ~= "function" then
    return nil
  end
  local info = getinfo(fn, "u")
  local names = {}
  for i = 1, info.nparams do
    names[i] = getlocal(fn, i)
  end
  return concat(names, ","), info.isvararg
end

-- Cancels every subscription and ends every task a scope made: it failed,
-- or it's closing. (Its Kotlin timers have gone already.)
function host.drop_subscriptions(scope)
  local owned_tasks = scope_tasks[scope]
  if owned_tasks ~= nil then
    for id in pairs(owned_tasks) do
      local task = tasks_by_id[id]
      if task ~= nil then
        end_task(task)
      end
    end
    scope_tasks[scope] = nil
  end
  local owned = scope_subscriptions[scope]
  if owned == nil then
    return
  end
  for id in pairs(owned) do
    local sub = subscriptions[id]
    if sub ~= nil then
      unsubscribe(sub)
    end
  end
  scope_subscriptions[scope] = nil
end

-- The scope the census blamed for going over the memory limit, and the bytes
-- it holds; nil when none is waiting to be stopped. The host stops it.
function host.take_blamed()
  local blamed = guard.blamed
  guard.blamed = nil
  if blamed == nil then
    return nil
  end
  return blamed.scope, blamed.bytes
end

-- What each scope holds, about (the census): one line per scope, "<scope> <bytes>".
function host.memory()
  local lines = {}
  for scope, bytes in pairs(guard.census(nil)) do
    lines[#lines + 1] = format("%d %d", scope, bytes)
  end
  return concat(lines, "\n")
end

function host.drop_env(scope)
  host.drop_subscriptions(scope)
  scopes.drop_env(scope)
  timing.slow_ns[scope], timing.slow_fn[scope], timing.last_ns[scope], timing.last_fn[scope] =
    nil, nil, nil, nil
end

-- The time each scope's code took since the last time the host asked, one
-- line per scope that ran: "<scope> <nanoseconds> <calls>". `rotate` starts a
-- new window for slowest calls (`host.hot_spot`).
function host.take_costs(rotate)
  local lines, calls = {}, timing.calls
  for scope, ns in pairs(timing.nanos) do
    lines[#lines + 1] = format("%d %d %d", scope, ns, calls[scope] or 0)
  end
  timing.nanos, timing.calls = {}, {}
  if rotate then
    timing.last_ns, timing.last_fn = timing.slow_ns, timing.slow_fn
    timing.slow_ns, timing.slow_fn = {}, {}
  end
  return concat(lines, "\n")
end

-- Turns the profiler on or off (see `timing.fns`); off forgets what it kept.
function host.profile(on)
  timing.profiling = on
  if not on then
    timing.fns = {}
  end
end

-- Where a profiled function starts: its project file and line, or the path
-- it was timed as (a module's file), or "" and 0 when it isn't project code.
local function profiled_at(key)
  if type(key) == "string" then
    return key, 0
  end
  local at = timing.where[key]
  if at == nil then
    local info = getinfo(key, "S")
    if info.source:sub(1, 1) == "@" then
      at = { info.source:sub(2), info.linedefined }
    else
      at = { "", 0 }
    end
    timing.where[key] = at
  end
  return at[1], at[2]
end

-- What the profiler kept since the host last asked, one line per scope and
-- function, tab-separated: "<scope> <kind> <file> <line> <nanoseconds> <calls>
-- <slowest call's nanoseconds>". A function whose kind no caller said is a
-- script's body when it starts at line 0, else a "callback".
function host.take_profile()
  local lines = {}
  for scope, per in pairs(timing.fns) do
    for key, entry in pairs(per) do
      local file, line = profiled_at(key)
      local kind = entry[4] or (line == 0 and file ~= "" and "load" or "callback")
      lines[#lines + 1] =
        format("%d\t%s\t%s\t%d\t%d\t%d\t%d", scope, kind, file, line, entry[1], entry[2], entry[3])
    end
  end
  timing.fns = {}
  return concat(lines, "\n")
end

-- Where the slowest call `scope` made lately is: the file and the line its
-- function starts on (nil for a script's body), or nil when that isn't
-- project code (a command's dispatcher, the prelude's own).
function host.hot_spot(scope)
  local fn = timing.slow_fn[scope]
  if fn == nil or (timing.last_ns[scope] or -1) > timing.slow_ns[scope] then
    fn = timing.last_fn[scope]
  end
  fn = timing.alias[fn] or fn
  if type(fn) ~= "function" then
    return nil
  end
  local info = getinfo(fn, "S")
  if info.source:sub(1, 1) ~= "@" then
    return nil
  end
  return info.source:sub(2), info.linedefined > 0 and info.linedefined or nil
end

-- How many subscriptions and tasks each scope has, one line per scope that
-- has any: "<scope> <subscriptions> <tasks>".
function host.scope_counts()
  local counts = {}
  for scope, owned in pairs(scope_subscriptions) do
    local n = 0
    for _ in pairs(owned) do
      n = n + 1
    end
    counts[scope] = { n, 0 }
  end
  for scope, owned in pairs(scope_tasks) do
    local n = 0
    for _ in pairs(owned) do
      n = n + 1
    end
    counts[scope] = counts[scope] or { 0, 0 }
    counts[scope][2] = n
  end
  local lines = {}
  for scope, c in pairs(counts) do
    lines[#lines + 1] = format("%d %d %d", scope, c[1], c[2])
  end
  return concat(lines, "\n")
end

function host.env(scope)
  return envs[scope]
end

-- The project file whose code is running (the innermost "@" frame: a package's
-- at its package path), or nil when no script's code is: the runtime resolves
-- a bare id a script passes it in that file's package.
function host.calling_file()
  return (project_frame(2))
end

-- Compiles a resource or module script into its scope and runs its top level.
function host.run_file(budget, scope, path, source)
  local chunk, problem = load(source, "@" .. path, "t", envs[scope])
  if chunk == nil then
    local info = describe(problem)
    return "error", info.message, info.file, info.line, nil
  end
  timing.next_kind = "load"
  return invoke(budget, scope, chunk)
end

-- Starts a module file through the require cache, so a module another one
-- already required isn't run twice.
function host.start(budget, scope, path)
  timing.next_kind, timing.next_what = "load", path
  return invoke(budget, scope, load_path, scope, path)
end

-- Raises a built-in event along `stages` (each `{ event = name, target = handle }`,
-- the target nil for nf): the handlers on each stage's target in turn, until
-- one stops it. `fields` is the payload, `precancelled` whether it starts
-- cancelled (a locked menu), `writable` the payload fields to hand back,
-- `home` the package `fields` is spelled for (the project's): before the first
-- handler of another package's, the fields are spelled again for it
-- (`events.respell`), so the ids in them read as its own code writes them.
-- `only`, when it's set, is the one package whose handlers hear it (a
-- package's own `setting_changed`). Returns whether it ended cancelled, the
-- package the fields are spelled for by then, then those fields' values.
function host.emit(stages, fields, precancelled, writable, home, only)
  local first = stages[1]
  local owner = class_of(first.target)
  local spec = registry[owner][first.event]
  local ev, state = new_event(first.event, fields, spec.cancellable, spec.writable)
  -- What a handler assigns is checked as the first stage's payload field.
  state.owner, state.event = owner, first.event
  set_event(state, "cancelled", precancelled == true)
  state.spelled = home
  state.only = only
  for i = 1, #stages do
    if state.stopped then
      break
    end
    local stage = stages[i]
    set_event(state, "name", stage.event)
    set_event(state, "current", stage.target)
    guard.unbilled(deliver, key_of(stage.target), stage.event, ev, state)
  end
  local written = {}
  for i, name in ipairs(writable) do
    written[i] = state.fields[name]
  end
  return state.cancelled, state.spelled, unpack(written, 1, #writable)
end

-- The scopes with a live handler for `event` on nf, one a line.
function host.scopes_listening(event)
  local lines = {}
  local seen = {}
  for _, sub in ipairs((listeners[NF] or {})[event] or {}) do
    if sub.active and not seen[sub.scope] then
      seen[sub.scope] = true
      lines[#lines + 1] = tostring(sub.scope)
    end
  end
  return concat(lines, "\n")
end

-- The script-local `unload` event: only `scope`'s own `nf.on("unload")`
-- handlers hear it, as it is about to close.
function host.unload(scope)
  local ev, state = new_event("unload", {}, false, registry[NF].unload.writable)
  deliver(NF, "unload", ev, state, scope)
end

-- The scope whose code is running now (the innermost budget frame's), or nil: what a handle method that makes
-- something (`screen:play`) files it under, as `:on` files a subscription.
---@return integer?
function host.running_scope()
  return events.running_scope()
end

-- Things are gone (a removed centity and its nodes, a closed menu window and
-- its slots, a deleted dialog and its buttons): every subscription on each of
-- `targets` (a list of handles) goes.
function host.drop_targets(targets)
  local doomed = {}
  for _, target in ipairs(targets) do
    for _, list in pairs(listeners[target] or {}) do
      for _, sub in ipairs(list) do
        doomed[#doomed + 1] = sub
      end
    end
  end
  for _, sub in ipairs(doomed) do
    unsubscribe(sub)
  end
  -- A task waiting on it ends (`wait_events`).
  for _, sub in ipairs(doomed) do
    if sub.gone ~= nil then
      sub.gone()
    end
  end
end

-- A value as JSON text (a data table to save, an item's `data`, `nf.json`'s),
-- with problem paths starting at `root`, each saying what can't be `verb`
-- ("saved"), and a table's keys in `skip` (a set, or nil) left out: the text
-- (nil when the value itself can't be), then each problem found, one per line.
function host.json_encode(value, root, verb, skip)
  if skip ~= nil and type(value) == "table" and getmetatable(value) == nil then
    local kept = {}
    for key, item in next, value do
      if not skip[key] then
        kept[key] = item
      end
    end
    value = kept
  end
  local out, problems = {}, { verb = verb }
  local ok = encode_data(value, root, out, problems, {}, 0)
  return ok and concat(out) or nil, concat(problems, "\n")
end

-- A value read from JSON, with its tagged values typed again.
function host.json_decode(tree)
  return decode_data(tree)
end

-- Takes the running frame off while the host runs a stretch of the server's own
-- ticks inside it (`nf.test.advance`): each handler, timer and centity tick in
-- them is then a call in of its own, with its own budget and time limit, as on
-- a server, rather than nested in (and charged to) the frame that asked. Returns
-- the frame and the clock, for `host.reattach`.
function host.detach()
  local f = guard_module.current()
  guard_module.set_current(nil)
  return f, timing.clock()
end

-- Puts back what `host.detach` took off. Its time limit is moved on by the time
-- it was off, and that time is its nested calls', not its own.
function host.reattach(f, from)
  if f ~= nil then
    local elapsed = timing.clock() - from
    f.deadline = f.deadline + elapsed
    f.nested = f.nested + elapsed
  end
  guard_module.set_current(f)
end

-- The genuine metatables of a vector and a location: Kotlin builds those it
-- hands over itself (`LuaHost.pushVec3`, `pushLocation`), as `new_vec3` and
-- `new_location` would, so a returned position costs a script nothing.
host.vec3_mt, host.location_mt = values.Vec3_mt, values.Location_mt

-- The handle classes a saved table may keep, each with its tag (`entity` for
-- `{"$entity":[id]}`): Kotlin writes plain data that holds them itself, as the
-- JSON codec would (`LuaHost.plainJson`).
host.saved_handles = require("schema").saved_handles

-- Forgets every required file under a path prefix, in every scope, for a module reload.
function host.forget(prefix)
  for _, done in pairs(loaded) do
    for path in pairs(done) do
      if path:sub(1, #prefix) == prefix then
        done[path] = nil
      end
    end
  end
end

-- The keys of the handle tables Lua has let go of since the last time, as a
-- list: those whose key has no table again (Kotlin may have pushed the same
-- thing again since, which made a new table for the same key).
host.take_released = handles.take_released

-- What Kotlin reads to tell a typed value: each genuine metatable's kind, and a handle's key.
host.kinds, host.kind_names, host.ids = handles.kinds, handles.kind_names, handles.ids

-- Each key's handle table, and each handle class's metatable: Kotlin makes handles with them.
host.cache, host.metatables = handles.cache, handles.metatables

-- The debugger (dev servers only): its breakpoints, whether it stops on
-- errors, a pause; and forgetting the editor. See prelude/debugger.lua.
host.debug_configure, host.debug_detach = debugger.configure, debugger.detach

-- The package each item table Kotlin handed a script is spelled for (weak):
-- Kotlin reads it back in that package's words, whichever package's code
-- hands it back.
host.spelled = setmetatable({}, { __mode = "k" })

return host
