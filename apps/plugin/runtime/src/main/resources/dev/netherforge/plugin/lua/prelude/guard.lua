-- The guard: the hook that watches every script run (the instruction budget,
-- the time limit, memory), the frames a call in from the host runs in, the
-- timing kept per scope, and how an error is told to the host (its project
-- file and line).
--
-- Nothing here is ever handed to a script.

local std = require("std")
local input = require("input")

local prim, limits = input.prim, input.limits
local getinfo, traceback, sethook = std.getinfo, std.traceback, std.sethook
local co_running = std.co_running
local format, raw = std.format, std.raw
local type, tostring, tonumber, pairs, error = type, tostring, tonumber, pairs, error
local setmetatable, xpcall = setmetatable, xpcall

-- ---------------------------------------------------------------- hooks
--
-- One hook function, `hooks.run`, on every thread a script can run on, serves
-- everything that watches scripts run. Its count event, every `hooks.every`
-- VM instructions, drives the guard below (the budget, the time limit and
-- memory). Other events go to `hooks.listeners[event]`: that's where the
-- debugger's line hook plugs in (prelude/debugger.lua), with `hooks.mask`
-- saying which events it wants ("l", or "" while nothing does; set with
-- `hooks.set_mask`), so it shares this hook rather than replace it. (One more
-- listener isn't a hook event: `error`, which `describe` calls with an
-- uncaught error's message.) Hooks are per thread and a new coroutine starts
-- without one, so every coroutine a script makes is armed (`hooks.arm`)
-- before it can run.

local hooks = {
  every = limits.hook_every,
  mask = "",
  listeners = {},
  -- The thread whose hook counts every instruction (see `guard.count_every`), if any.
  fast = nil,
  -- Every thread armed and not collected yet, so a new mask reaches them all (`hooks.rearm`).
  threads = setmetatable({}, { __mode = "k" }),
}

function hooks.arm(co, every)
  sethook(co, hooks.run, hooks.mask, every or hooks.every)
  hooks.threads[co] = true
  return co
end

-- Sets the events listeners want (`hooks.mask`: "l" for lines, or "") on every
-- live thread: hooks are per thread, and a mask is set with the hook.
function hooks.set_mask(mask)
  if mask == hooks.mask then
    return
  end
  hooks.mask = mask
  for co in pairs(hooks.threads) do
    if std.co_status(co) == "dead" then
      hooks.threads[co] = nil
    else
      sethook(co, hooks.run, mask, co == hooks.fast and 1 or hooks.every)
    end
  end
end

-- ---------------------------------------------------------------- guard
--
-- The count hook charges the innermost frame. A frame is opened per call in
-- from the host, so a budget bounds one call in (a script's body, one
-- handler, one timer), never a script's whole life. A nested call in (a spawn
-- whose new centity's script runs inside the spawner's handler) gets its own
-- frame, and what it used is charged to the outer one when it returns: a loop
-- of call-ins can't do more than its own budget's worth of work.
--
-- Instructions aren't the only way to take time: a C function is one
-- instruction however long it runs. The standard library's are capped
-- ("=nf/caps"), and each frame also has a time limit, `deadline_ms` from when
-- it opened (or its outer frame's, if sooner): every `deadline_every` hooks,
-- or sooner when capped functions spend enough work (`guard.spend`), the hook
-- reads the clock.
--
-- Memory: every script shares one Lua state, so past `memory_mb` (checked
-- every `memory_every` hooks, and at the end of every collection cycle) the
-- hook collects fully, and if that wasn't garbage, works out who holds it
-- (`guard.census`). The scope holding the most is blamed, not whichever
-- happened to be running: the host stops it at the end of the tick
-- (`host.take_blamed`), which lets go of what it holds, and if it's the
-- running call's own scope, that call fails there and then.
--
-- Stopping a frame raises a Lua error, which a script could catch with
-- pcall. So the frame remembers why it stopped (`overran`) and the host
-- reports it either way: catching the error doesn't save the script, it
-- only delays the end.
--
-- A frame also knows whose code it runs (`scope`): a handle's `:on` registers
-- into the script that is running, the one whose body, handler or timer
-- called it. (`require` switches it to the module while a file loads.)

local gc = std.gc
local frame = nil
local OVERRUN = setmetatable({}, {
  __tostring = function()
    return frame and frame.overran or "stopped"
  end,
})

local guard = {
  -- Hooks until the next memory check: memory is one pool, so this isn't per frame.
  memory_in = limits.memory_every,
  memory_kb = limits.memory_mb * 1024,
  -- A scope the census blamed, which the host hasn't stopped yet: { scope, bytes }.
  blamed = nil,
  -- Memory (KB) still in use after a collection when the census couldn't place it.
  floor = nil,
  late = "ran past its time limit of " .. limits.deadline_ms .. " ms",
  -- Work (in the caps' steps, about a nanosecond each) worth one hook towards
  -- the next checks: a hook is `every` instructions of a few nanoseconds each.
  steps_per_hook = limits.hook_every * 2.5,
}

-- Each frame also measures its time: its own, less what the
-- frames nested in it took, charged to its scope. The host takes the totals
-- once a tick (`host.take_costs`). Each scope's slowest call is kept too, over
-- this window of ticks and the one before, so a warning that a scope is slow
-- can say which handler it was (`host.hot_spot`).
local timing = {
  clock = prim.clock,
  nanos = {}, -- scope -> nanoseconds since the host last took them
  calls = {}, -- scope -> calls in since then
  slow_ns = {}, -- scope -> this window's slowest call, in nanoseconds
  slow_fn = {}, -- scope -> the function it ran
  last_ns = {}, -- the same for the window before
  last_fn = {},
  -- A prelude wrapper Kotlin calls (an `nf.after` timer) -> the script's function it runs.
  alias = setmetatable({}, { __mode = "k" }),
  -- The profiler (`host.profile`): while it's on, every frame's own time is
  -- also kept per scope and per function it ran, `{ nanos, calls, slowest,
  -- kind }`, until the host takes them (`host.take_profile`).
  profiling = false,
  fns = {}, -- scope -> function (or a module file's path) -> entry
  -- A function Kotlin calls -> what kind of call it is ("timer", "goal").
  kinds = setmetatable({}, { __mode = "k" }),
  -- What the next frame `invoke` opens runs, when the caller knows better
  -- than the function: its kind ("tick", an event's name) and what it's
  -- timed as (a module file's path). Taken by that frame.
  next_kind = nil,
  next_what = nil,
  -- function -> { file, line }, resolved once.
  where = setmetatable({}, { __mode = "k" }),
  -- A scope's code may run this many nanoseconds in a tick (summed in `nanos`, which `take_costs` clears each
  -- tick) before its timers and handlers wait for the next; 0 is off. See `over_budget`.
  max_tick_ns = limits.max_tick_ns,
  -- Scopes whose timers or handlers were deferred this tick, so the host warns once each (drained by `take_costs`).
  deferred = {},
}

-- Whether `scope`'s code has already run its tick's worth (`max_tick_ns`), so a timer or handler of its own waits
-- for the next tick. Its own time this tick is `nanos[scope]` (what `take_costs` clears each tick).
function timing.over_budget(scope)
  if timing.max_tick_ns <= 0 or scope == nil then
    return false
  end
  if (timing.nanos[scope] or 0) < timing.max_tick_ns then
    return false
  end
  timing.deferred[scope] = true
  return true
end

-- A frame: one call in from the host, which a budget and a time limit bound.
---@class nf.Frame
---@field left number Instructions still to run; a frame that goes below 0 is stopped.
---@field budget integer
---@field outer nf.Frame? The frame it's nested in.
---@field overran string|false Why it was stopped, once it has been.
---@field scope integer? Whose code it runs.
---@field nested number Nanoseconds the frames nested in it took.
---@field charged number Instructions the frames nested in it ran.
---@field started integer
---@field deadline integer
---@field checks number Hooks until the next look at the clock.
---@field spent boolean? It has stopped counting (it is returning, or only waking a task).
---@field stop string? Why it must stop at its next instruction.
---@field kind string? What it runs, for the profiler ("tick", an event's name).
---@field what string? What it is timed as, when that isn't its function.
---@field file string? Where it was stopped, when nothing on the stack says by then.
---@field line integer?

-- A new frame running `budget` instructions of `scope`'s code, nested in `outer`.
---@param budget integer
---@param scope integer?
---@param outer nf.Frame?
---@return nf.Frame
function guard.frame(budget, scope, outer)
  local now = timing.clock()
  local deadline = now + limits.deadline_ms * 1000000
  if outer ~= nil and outer.deadline < deadline then
    deadline = outer.deadline
  end
  return {
    left = budget,
    budget = budget,
    -- The frame it's nested in, which is charged what this one runs when it returns.
    outer = outer,
    overran = false,
    scope = scope,
    nested = 0,
    -- Instructions the calls nested in it ran, charged to it (`guard.charge`): what `guard.unbilled` keeps.
    charged = 0,
    started = now,
    deadline = deadline,
    -- Hooks until the next look at the clock.
    checks = limits.deadline_every,
  }
end

-- Closes a frame's timing: `fn` is what it ran, `outer` the frame it was
-- nested in. Returns the clock's reading.
function timing.close(f, fn, outer)
  local now = timing.clock()
  local elapsed = now - f.started
  local own = elapsed - f.nested
  if outer ~= nil then
    outer.nested = outer.nested + elapsed
  end
  local scope = f.scope
  if scope == nil then
    return now
  end
  local nanos, calls, slow_ns = timing.nanos, timing.calls, timing.slow_ns
  nanos[scope] = (nanos[scope] or 0) + own
  calls[scope] = (calls[scope] or 0) + 1
  if own > (slow_ns[scope] or -1) then
    slow_ns[scope] = own
    timing.slow_fn[scope] = fn
  end
  if timing.profiling then
    -- What it ran: a command's handler rather than its dispatcher, a
    -- module's file rather than the loader, the script's function rather than
    -- a prelude wrapper.
    local key = f.what or fn
    key = timing.alias[key] or key
    local per = timing.fns[scope]
    if per == nil then
      per = {}
      timing.fns[scope] = per
    end
    local entry = per[key]
    if entry == nil then
      entry = { 0, 0, 0, f.kind or timing.kinds[fn] }
      per[key] = entry
    end
    entry[1] = entry[1] + own
    entry[2] = entry[2] + 1
    if own > entry[3] then
      entry[3] = own
    end
  end
  return now
end

-- A frame told to stop while the prelude's own code runs for the host
-- (setting up a scope, the end of a nested call in) isn't stopped there: that
-- code isn't the script's, and an error in it would escape the call the host
-- made. Its thread's hook counts every instruction instead (`hooks.fast`),
-- so it stops at the first instruction of a script's.
function guard.count_every(instruction)
  local fast = hooks.fast
  if instruction then
    local thread = co_running()
    if fast ~= thread then
      if fast ~= nil then
        hooks.arm(fast)
      end
      hooks.fast = thread
      hooks.arm(thread, 1)
    end
  elseif fast ~= nil then
    hooks.arm(fast)
    hooks.fast = nil
  end
end

-- Charges `outer`, the frame a nested one ran in, for the `used`
-- instructions the nested one ran; `now` is the clock as it closed. Whatever
-- that put over a limit stops the outer frame at its next instruction.
function guard.charge(outer, used, now)
  outer.left = outer.left - used
  outer.charged = outer.charged + used
  if outer.spent then
    return
  end
  if outer.stop == nil and now > outer.deadline then
    outer.stop = guard.late
  end
  if outer.left < 0 or outer.stop ~= nil then
    guard.count_every(true)
  end
end

-- Runs `fn(a, b, c, d, e)`, the prelude's own work around calls in that a
-- script's call set off (delivering an event to each handler): the running
-- frame, if any, is charged what those calls ran, not this work. A script whose
-- `emit` reaches ten handlers pays for their code, not for ten deliveries.
function guard.unbilled(fn, a, b, c, d, e)
  local f = frame
  if f == nil then
    return fn(a, b, c, d, e)
  end
  local left, charged = f.left, f.charged
  local result = fn(a, b, c, d, e)
  f.left = left - (f.charged - charged)
  return result
end

function guard.deadline(f)
  f.checks = limits.deadline_every
  if f.stop == nil and timing.clock() > f.deadline then
    f.stop = guard.late
  end
end

function guard.memory(f)
  guard.memory_in = limits.memory_every
  local limit = guard.memory_kb
  local kb = gc("count")
  if kb <= limit then
    guard.floor = nil
    return
  end
  if guard.blamed ~= nil or (guard.floor ~= nil and kb < guard.floor + limit / 8) then
    -- Waiting for the host to stop the scope blamed, which frees its memory
    -- at the end of the tick, or for memory the census couldn't place to
    -- grow by an eighth of the limit before looking again: a full collection
    -- and a census are too slow to make at every check. Only if the running
    -- call doubles the limit meanwhile does it stop too.
    if kb > 2 * limit and f.stop == nil then
      f.stop = format("used more memory than scripts may (%d MB together)", limit // 1024)
    end
    return
  end
  gc("collect")
  kb = gc("count")
  if kb <= limit then
    guard.floor = nil
    return
  end
  local sizes = guard.census(f.scope)
  local worst, most = nil, -1
  for scope, bytes in pairs(sizes) do
    if bytes > most or (bytes == most and scope < worst) then
      worst, most = scope, bytes
    end
  end
  -- Blamed only for a fair share of the limit: when the census can't place
  -- most of the memory (what Kotlin keeps for scripts isn't walked), the
  -- running call is stopped, as nothing better is known.
  local fair = worst ~= nil and most * 4 >= limit * 1024
  if fair then
    guard.blamed = { scope = worst, bytes = most }
  else
    guard.floor = kb
  end
  if (worst == f.scope or not fair) and f.stop == nil then
    f.stop = format(
      "used more memory than scripts may (%d MB together; this script holds about %d MB)",
      limit // 1024,
      (sizes[f.scope] or 0) // 1048576
    )
  end
end

-- Counts `steps` of work a capped library function is about to do towards
-- the running frame's next checks, and makes them now if they're due. A
-- check that stops the frame stops it at the script's next instruction.
function guard.spend(steps)
  local f = frame
  if f == nil or f.spent then
    return
  end
  local worth = steps / guard.steps_per_hook
  f.checks = f.checks - worth
  guard.memory_in = guard.memory_in - worth
  if f.checks <= 0 then
    guard.deadline(f)
  end
  if guard.memory_in <= 0 then
    guard.memory(f)
  end
  if f.stop ~= nil then
    guard.count_every(true)
  end
end

-- Charges the running frame for work done on its behalf that no hook counts:
-- the host's walk over a big table a script passed it, the prelude's own sort
-- of a table's keys. `instructions` go against the budget, `steps` (about a
-- nanosecond each) towards the time limit's next check. Returns why the frame
-- must stop (and marks it, so the overrun is reported even if caught), or nil.
-- The caller stops its work there: unlike a script's code, this work can't be
-- stopped by the hook.
function guard.charge_work(instructions, steps)
  local f = frame
  if f == nil or f.spent then
    return nil
  end
  f.left = f.left - instructions
  if steps > 0 then
    guard.spend(steps)
  end
  if f.stop == nil and f.left < 0 then
    f.stop = "ran past its budget of " .. f.budget .. " instructions"
  end
  if f.stop ~= nil then
    guard.count_every(true)
  end
  return f.stop
end

-- For a loop in prelude code whose length a script sets: the hook counts its
-- instructions as it runs but can't stop it there, so before it starts, why
-- the running frame can't afford `instructions` more (and the frame is marked
-- to stop), or nil when it can. Nothing is charged: the loop's own
-- instructions are, as it runs them.
function guard.affords(instructions)
  local f = frame
  if f == nil or f.spent then
    return nil
  end
  if f.stop == nil and f.left - instructions < 0 then
    f.stop = "ran past its budget of " .. f.budget .. " instructions"
    guard.count_every(true)
  end
  return f.stop
end

function hooks.run(event, line)
  if event ~= "count" then
    local listener = hooks.listeners[event]
    if listener ~= nil then
      listener(event, line)
    end
    return
  end
  local f = frame
  if f == nil or f.spent then
    if hooks.fast ~= nil then
      guard.count_every(false)
    end
    return
  end
  local fast = hooks.fast
  if fast == nil or fast ~= co_running() then
    f.left = f.left - hooks.every
    f.checks = f.checks - 1
    guard.memory_in = guard.memory_in - 1
  end
  if f.checks <= 0 then
    guard.deadline(f)
  end
  if guard.memory_in <= 0 then
    guard.memory(f)
  end
  local stop = f.stop
  if stop == nil and f.left < 0 then
    stop = "ran past its budget of " .. f.budget .. " instructions"
  end
  if stop ~= nil then
    local at = getinfo(2, "Sl")
    -- The prelude's own code ("=nf", and its modules "=nf:<name>").
    if at.source == "=nf" or raw.find(at.source, "^=nf:") then
      guard.count_every(true)
      return
    end
    guard.count_every(false)
    f.overran = stop
    -- Where it stopped, in case nothing on the stack says by the time it's
    -- reported (a body's error is re-raised by `require`'s loader).
    if f.file == nil and at.source:sub(1, 1) == "@" then
      f.file, f.line = at.source:sub(2), at.currentline
    end
    error(OVERRUN, 0)
  end
  if fast ~= nil then
    guard.count_every(false)
  end
end

hooks.main = co_running()
hooks.arm(hooks.main)

-- The end of every collection cycle: a table nothing holds, whose finalizer
-- makes the next one. A string doubled with `..` (one instruction a time)
-- can outgrow the memory limit many times over between two count hooks;
-- collection cycles keep pace with it, so this has the next instruction
-- check memory. (Not here: inside a finalizer, collectgarbage does nothing.)
--
-- Arming a thread's hook again (`sethook`) starts its count of `every`
-- instructions over, so the instructions it ran since its last count are
-- never counted. A script that ends collection cycles more often than every
-- `every` instructions (a loop making big strings) would otherwise never
-- reach a count at all: no budget, no time limit. So the switch is charged
-- what the count it throws away could have held: `every` instructions and
-- one hook towards the next checks, an upper bound (more would have counted).
function guard.collected()
  setmetatable({}, { __gc = guard.collected })
  local f = frame
  if f ~= nil and not f.spent then
    if hooks.fast ~= co_running() then
      f.left = f.left - hooks.every
      f.checks = f.checks - 1
    end
    guard.memory_in = 0
    guard.count_every(true)
  end
end
setmetatable({}, { __gc = guard.collected })

-- ---------------------------------------------------------------- errors

-- The first frame running project code: chunk names are "@<project path>",
-- and the prelude's own frames ("=nf") and C frames are skipped.
local function project_frame(level)
  for l = level, 200 do
    local info = getinfo(l, "Sl")
    if info == nil then
      return nil
    end
    if info.source:sub(1, 1) == "@" then
      return info.source:sub(2), info.currentline
    end
  end
  return nil
end

-- The project file whose code made each coroutine a script runs (weak, by
-- thread). A coroutine's own stack starts at its body, so code that runs a
-- primitive as the body (`coroutine.wrap(player.ban)(player)`) leaves no
-- project frame on it, and the package that made the call can only be told by
-- who made the coroutine. Recorded by `note_origin` wherever one is made.
local origins = setmetatable({}, { __mode = "k" })

--- Records where `co` was made, and returns it: the project code making it, or,
--- made where none is on this thread's stack, where this thread was made.
local function note_origin(co)
  origins[co] = project_frame(2) or origins[co_running()]
  return co
end

--- The project file whose code is running on this thread, else the one that
--- made it (`note_origin`); nil when neither is known.
local function calling_file()
  return (project_frame(2)) or origins[co_running()]
end

-- Lua prefixes errors with a *short* source, which loses the start of long
-- paths ("...dules/a/b.lua"). The frame with that short source has the full one.
local function full_source(short)
  for l = 2, 200 do
    local info = getinfo(l, "S")
    if info == nil then
      break
    end
    if info.short_src == short and info.source:sub(1, 1) == "@" then
      return info.source:sub(2)
    end
  end
  return short
end

-- The message handler for every call in: a message without its location
-- prefix, the project file and line it happened at, and a traceback. It runs
-- where the error happened, the stack still whole, so the debugger's
-- `error` listener (break on script errors) stops here.
local function describe(err)
  local message = err == OVERRUN and tostring(OVERRUN) or tostring(err)
  local on_error = hooks.listeners.error
  if on_error ~= nil then
    on_error(message, describe)
  end
  local file, line
  local short, at, rest = raw.match(message, "^(.-%.lua):(%d+): (.*)$")
  if short ~= nil then
    file, line, message = full_source(short), tonumber(at), rest
  else
    file, line = project_frame(3)
  end
  return { message = message, file = file, line = line, traceback = traceback(nil, 2) }
end

-- Puts a location on an error that has none, so it survives being re-raised
-- across a require boundary.
local function locate(err)
  if type(err) == "string" and not raw.match(err, "^.-%.lua:%d+: ") then
    local file, line = project_frame(3)
    if file ~= nil then
      return file .. ":" .. line .. ": " .. err
    end
  end
  return err
end

-- What every call in returns: status ("ok" or "error"), the call's first result
-- or the error message, and for an error the project file, line and traceback.
local function finish(f, ok, value)
  if f.overran then
    local info = ok and {} or value
    return "error", f.overran, info.file or f.file, info.line or f.line, info.traceback
  end
  if ok then
    return "ok", value
  end
  if type(value) ~= "table" then
    -- Lua calls no message handler when an allocation fails: "not enough memory".
    return "error", tostring(value)
  end
  return "error", value.message, value.file, value.line, value.traceback
end

-- Runs a call in and stops its frame counting as it returns, still inside the
-- protected call. A frame whose code caught its overrun (a coroutine that ran
-- past the budget, resumed by a handler that then returned) raises it again
-- at the next count; if that count landed after the call returned, the error
-- would escape the call in and never be reported. Here it's the call's.
local function run_spending(f, fn, ...)
  local value = fn(...)
  f.spent = true
  return value
end

local function invoke(budget, scope, fn, ...)
  local outer = frame
  -- The caller is charged what the call ran, not the prelude's work around
  -- it (making and closing this frame): a script whose `emit` reaches ten
  -- handlers pays for their code, not ten frames'.
  local outer_left = outer and outer.left
  local f = guard.frame(budget, scope, outer)
  f.kind, f.what = timing.next_kind, timing.next_what
  timing.next_kind, timing.next_what = nil, nil
  frame = f
  local ok, value = xpcall(run_spending, describe, f, fn, ...)
  -- Stops counting first: a frame that overran would overrun again in here.
  f.spent = true
  local now = timing.close(f, fn, outer)
  frame = outer
  if outer ~= nil then
    -- Charged to the caller, whose next instruction stops if that was too much.
    outer.left = outer_left
    guard.charge(outer, budget - math.max(f.left, 0), now)
  end
  return finish(f, ok, value)
end

--- The frame running now (nil when no call in from the host is running).
---@return nf.Frame?
local function current()
  return frame
end

--- Makes `f` the running frame, or none (nil): a task's resume opens its own.
---@param f nf.Frame?
local function set_current(f)
  frame = f
end

return {
  hooks = hooks,
  guard = guard,
  timing = timing,
  invoke = invoke,
  describe = describe,
  locate = locate,
  project_frame = project_frame,
  note_origin = note_origin,
  calling_file = calling_file,
  current = current,
  set_current = set_current,
}
