-- The hand-written functions: those the spec marks `impl: "lua"`, logic rather
-- than a call into the server. These are the bodies. The generated bindings
-- define each function scripts call: the wrapper checks `self` and every
-- argument from the spec's types, then calls the body here with them (as a
-- tail call, so an error the body raises at level 2 is at the script's line).
-- A body can rely on all that, and holds only what a type can't say.
--
-- A namespace function's body gets the calling scope first, as a Kotlin
-- implementation does. The conformance test holds these tables to exactly the
-- spec's `impl: "lua"` set.

local std = require("std")
local input = require("input")
local args = require("args")
local handles = require("handles")
local values = require("values")
local guard_module = require("guard")
local events = require("events")
local schema = require("schema")
local tasks = require("tasks")
local utilities = require("utilities")

local prim = input.prim
local raw = std.raw
local co_create = std.co_create
local error, ipairs, pairs, getmetatable = error, ipairs, pairs, getmetatable
local bad = args.bad
local is_kind, keys = handles.is_kind, handles.keys
local ids = handles.ids
local hooks, timing, current = guard_module.hooks, guard_module.timing, guard_module.current
local subscribe, emit_custom, running_scope =
  events.subscribe, events.emit_custom, events.running_scope
local nf_tables, registry = events.nf_tables, events.registry
local new_task, forget_task, end_task, resume =
  tasks.new_task, tasks.forget_task, tasks.end_task, tasks.resume
local running_task, sleep, wait_events = tasks.running_task, tasks.sleep, tasks.wait_events
local thread_tasks, task_table = tasks.thread_tasks, tasks.tasks

-- Every function by the class or namespace it belongs to (`nf.math` is a
-- namespace under `nf`).
---@type table<string, table<string, function>>
local handwritten = {
  File = {},
  Subscription = events.Subscription_body,
  Task = {},
  Entity = {},
  Mob = {},
  World = {},
  Dialog = {},
  Random = utilities.body.Random,
  nf = {},
  ["nf.random"] = utilities.body["nf.random"],
  ["nf.math"] = utilities.body["nf.math"],
  ["nf.test"] = utilities.body["nf.test"],
}

-- Every class with events of its own has `on`, `once` and, if it takes custom
-- events, `emit`: the same bodies for each (`schema.event_functions`).
do
  local shared = { on = events.on, once = events.once, emit = events.emit }
  for class_name, names in pairs(schema.event_functions) do
    local functions = handwritten[class_name] or {}
    handwritten[class_name] = functions
    for _, name in ipairs(names) do
      functions[name] = shared[name]
    end
  end
end

do
  local Entity, Mob, World, Dialog, File, Task =
    handwritten.Entity,
    handwritten.Mob,
    handwritten.World,
    handwritten.Dialog,
    handwritten.File,
    handwritten.Task

  function Entity.is_player(self)
    return is_kind(self, "Player")
  end

  function Entity.is_living(self)
    return is_kind(self, "Living")
  end

  function Entity.is_mob(self)
    return is_kind(self, "Mob")
  end

  -- A goal belongs to the scope whose code adds it, like a handle's `:on`. Its
  -- callbacks are called with the mob, and the yes-or-no ones answer a
  -- boolean whatever the script returns; the runtime keeps them until the goal
  -- comes off. (Slow-script warnings name the script's function, not these.)
  local GOAL_CALLBACKS = { "should_start", "should_continue", "start", "tick", "stop" }
  local GOAL_ANSWERS = { should_start = true, should_continue = true }

  function Mob.add_goal(self, id, definition)
    local callbacks = {}
    for i, key in ipairs(GOAL_CALLBACKS) do
      local fn = definition[key]
      if fn ~= nil then
        local call
        if GOAL_ANSWERS[key] then
          call = function()
            return fn(self) and true or false
          end
        else
          call = function()
            fn(self)
          end
        end
        timing.alias[call] = fn
        timing.kinds[call] = "goal"
        callbacks[i] = call
      end
    end
    local added = prim["goals.add"](
      running_scope(),
      self,
      id,
      definition.priority,
      definition.controls,
      callbacks[1],
      callbacks[2],
      callbacks[3],
      callbacks[4],
      callbacks[5]
    )
    return added
  end

  function World.location(self, position, yaw, pitch)
    if pitch ~= nil and yaw == nil then
      error("bad argument 'pitch' (a pitch needs a yaw)", 2)
    end
    return values.new_location(self, position, yaw, pitch)
  end

  -- Read once, then handed out a line at a time: files are capped at 1 MiB.
  function File.lines(self)
    local text = self:read()
    if text == nil or text == "" then
      return function()
        return nil
      end
    end
    local at = 1
    return function()
      if at > #text then
        return nil
      end
      local stop = raw.find(text, "\n", at, true)
      local line
      if stop == nil then
        line = text:sub(at)
        at = #text + 1
      else
        line = text:sub(at, stop - 1)
        at = stop + 1
      end
      if line:sub(-1) == "\r" then
        line = line:sub(1, -2)
      end
      return line
    end
  end

  function Task.cancel(self)
    local task = task_table[keys.Task(self)]
    if task ~= nil then
      end_task(task)
    end
  end

  function Task.is_active(self)
    local task = task_table[keys.Task(self)]
    if task == nil then
      return false
    end
    -- An `nf.every` callback the runtime gave up on after 20 errors in a row.
    if task.co == nil and not prim["timer.active"](task.timer) then
      forget_task(task)
      return false
    end
    return true
  end

  -- Listens before it opens the dialog, so nothing the opening causes is missed
  -- (a close it fires for this same dialog is before the wait, so ignored).
  function Dialog.ask(self, player, options)
    local task = running_task("dialog:ask")
    local function theirs(answer)
      return function(event)
        if event.player == player then
          return true, answer and event or nil
        end
        return false
      end
    end
    if not self:exists() then
      return nil
    end
    local press = wait_events(
      task,
      {
        { target = self, event = "press", accept = theirs(true) },
        { target = self, event = "close", accept = theirs(false) },
        { target = nil, event = "player_quit", accept = theirs(false) },
      },
      4,
      function()
        return self:open_for(player, options)
      end
    )
    return press
  end
end

-- ---- `nf` and its namespaces
--
-- Each takes the calling scope first (the one whose `nf` it is called on).

do
  local nf = handwritten.nf

  function nf.on(scope, event, handler, options)
    local subscription = subscribe(scope, nil, event, handler, options, false, 3)
    return subscription
  end

  function nf.once(scope, event, handler)
    local subscription = subscribe(scope, nil, event, handler, nil, true, 3)
    return subscription
  end

  function nf.emit(_, event, payload)
    local cancelled = emit_custom(nil, event, payload, 3)
    return cancelled
  end

  function nf.after(scope, ticks, callback)
    local task = new_task(scope)
    local function run()
      forget_task(task)
      callback()
    end
    timing.alias[run] = callback
    timing.kinds[run] = "timer"
    task.fn = callback
    task.timer = prim.timer(scope, ticks, 0, run)
    return task.handle
  end

  function nf.every(scope, ticks, callback)
    if ticks < 1 then
      error("bad argument 'ticks' (nf.every needs at least 1)", 2)
    end
    local task = new_task(scope)
    task.fn = callback
    timing.kinds[callback] = "timer"
    task.timer = prim.timer(scope, ticks, ticks, callback)
    return task.handle
  end

  function nf.task(scope, callback, ...)
    local task = new_task(scope)
    task.co = guard_module.note_origin(hooks.arm(co_create(tasks.task_body)))
    task.fn = callback
    thread_tasks[task.co] = task
    resume(task, callback, ...)
    return task.handle
  end

  function nf.wait(_, ticks)
    local task = running_task("nf.wait")
    if ticks < 0 then
      error("bad argument 'ticks' (can't be negative)", 2)
    end
    sleep(task, ticks)
  end

  function nf.wait_until(_, predicate, timeout)
    local task = running_task("nf.wait_until")
    if timeout ~= nil and timeout < 0 then
      error("bad argument 'timeout' (can't be negative)", 2)
    end
    local waited = 0
    while not predicate() do
      if timeout ~= nil and waited >= timeout then
        return false
      end
      sleep(task, 1)
      waited = waited + 1
    end
    return true
  end

  function nf.wait_for(_, handle, event, filter)
    local task = running_task("nf.wait_for")
    local target = handle
    if nf_tables[handle] then
      target = nil
    elseif ids[handle] == nil or registry[getmetatable(handle)] == nil then
      bad(
        "handle",
        "Centity, Node, Menu, Slot, MenuTemplate, Dialog, Button, ProjectItem, Effect, World, Entity or nf",
        handle,
        2
      )
    end
    local ev = wait_events(task, {
      {
        target = target,
        event = event,
        accept = function(ev)
          if filter == nil or filter(ev) then
            return true, ev
          end
          return false
        end,
      },
    }, 4)
    return ev
  end

  -- A nested call in (a task started by a handler, a centity spawned by one)
  -- has its own budget, but what it runs is charged to its caller when it
  -- returns: what's left is the least of its own and each caller's, less what
  -- the calls inside that caller have run so far.
  function nf.instructions_left()
    local f = current()
    if f == nil then
      return 0
    end
    local left, used, outer = f.left, f.budget - f.left, f.outer
    while outer ~= nil do
      local outer_left = outer.left - used
      if outer_left < left then
        left = outer_left
      end
      used, outer = outer.budget - outer_left, outer.outer
    end
    if left < 0 then
      return 0
    end
    return left
  end
end

return handwritten
