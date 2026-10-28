-- Tasks. `nf.task` runs a function as a coroutine that may wait; `nf.after`
-- and `nf.every` are Kotlin timers. Both hand out a `Task`, whose state lives
-- here (`tasks`, by the handle's id) and goes with its scope
-- (`host.drop_subscriptions`).

local std = require("std")
local input = require("input")
local args = require("args")
local handles = require("handles")
local guard_module = require("guard")
local events = require("events")

local prim = input.prim
local co_status, co_resume, co_yield, co_close, co_running, co_isyieldable =
  std.co_status, std.co_resume, std.co_yield, std.co_close, std.co_running, std.co_isyieldable
local xpcall, ipairs, error, tostring, type, setmetatable =
  xpcall, ipairs, error, tostring, type, setmetatable
local getinfo = std.getinfo
local bad = args.bad
local new = handles.new
local guard, timing, describe = guard_module.guard, guard_module.timing, guard_module.describe
local invoke, current, set_current =
  guard_module.invoke, guard_module.current, guard_module.set_current
local budgets, subscriptions = events.budgets, events.subscriptions
local subscribe, unsubscribe, running_scope =
  events.subscribe, events.unsubscribe, events.running_scope
local keys = handles.keys

-- A task's coroutine is resumed only from here (`resume`), each time inside
-- its own budget frame with its scope's budget: the budget applies per
-- resume, not to the task's whole life. What resumes it is a timer (a wait of
-- some ticks) or an event handler (`wait_for`), which carries the task on
-- straight away, inside the event's delivery, so the task can still cancel
-- the event. Every wait has its own token, and only a wake-up carrying the
-- current token resumes the task, so a stale timer or event never does.
--
-- A task can wait only in its own code: `running_task` refuses a wait
-- unless the running coroutine is a task's and the budget frame is the one
-- its resume opened. So an event handler (its own frame, even when the
-- task's code set it off), a function Kotlin calls back (its own frame: a
-- yield there would cross JNI) and a coroutine the script made (another
-- thread) each get a clear error, and a C function's callback (`table.sort`'s
-- comparator), which Lua can't yield across, is caught by `isyieldable`.
-- `pcall` is fine: Lua 5.4 yields across it.
--
-- The coroutine's body runs the task under xpcall with `describe`, on the
-- task's own stack, so an error keeps its file, line and traceback. A
-- failure ends the task and is reported (`events.failed`) as the task's; it
-- never reaches the code that started the task.

-- An async function's two forms (`async` in the spec): the generated bindings
-- call `begin` and `wait`, defined below.
---@class nf.Async
---@field begin fun(what: string, callback: function?): function, table?, table?, integer?
---@field wait fun(task: table, token: table, id: integer): any, any
local async = {}

local WAIT = {} -- what a task yields when it waits
local tasks = {} -- id -> task
local scope_tasks = {} -- scope -> { [id] = true }
local thread_tasks = setmetatable({}, { __mode = "k" }) -- coroutine -> task
local next_task = 1
local stepping = nil -- the task whose resume is running, innermost

local function new_task(scope)
  local id = next_task
  next_task = id + 1
  local task = { id = id, scope = scope, active = true, handle = new.Task(id) }
  tasks[id] = task
  local owned = scope_tasks[scope]
  if owned == nil then
    owned = {}
    scope_tasks[scope] = owned
  end
  owned[id] = true
  return task
end

local function forget_task(task)
  task.active = false
  task.waiting = nil
  tasks[task.id] = nil
  local owned = scope_tasks[task.scope]
  if owned ~= nil then
    owned[task.id] = nil
  end
end

-- Lets go of the events a task waits for.
local function release_waits(task)
  local subs = task.subs
  task.subs = nil
  if subs ~= nil then
    for _, sub in ipairs(subs) do
      sub.gone = nil
      unsubscribe(sub)
    end
  end
end

-- Ends a task where it is: its timer and its subscriptions go, and its
-- coroutine is closed. One that's running (it cancelled itself, or its
-- scope closed under it) is closed when it next waits, by `resume`.
local function end_task(task)
  if not task.active then
    return
  end
  forget_task(task)
  if task.timer ~= nil then
    prim["timer.cancel"](task.timer)
    task.timer = nil
  end
  release_waits(task)
  if task.pending ~= nil then
    -- Waiting on async work: nothing is delivered now (the work itself goes on).
    prim["async.cancel"](task.pending)
    task.pending = nil
  end
  local co = task.co
  if co ~= nil and co_status(co) == "suspended" then
    -- Closing runs the task's pending `<close>` variables: script code, so in a frame.
    local budget = budgets[task.scope]
    if budget ~= nil then
      invoke(budget, task.scope, co_close, co)
    else
      co_close(co)
    end
  end
end

-- The first project frame on a suspended coroutine's stack.
local function thread_frame(co)
  for l = 0, 200 do
    local info = getinfo(co, l, "Sl")
    if info == nil then
      return nil
    end
    if info.source:sub(1, 1) == "@" then
      return info.source:sub(2), info.currentline
    end
  end
  return nil
end

-- Inside the coroutine: the task itself, under xpcall, so a failure is
-- described where it happened.
local function task_body(fn, ...)
  local ok, failure = xpcall(fn, describe, ...)
  current().spent = true
  if not ok then
    -- Lua calls no message handler when an allocation fails.
    return false, type(failure) == "table" and failure or { message = tostring(failure) }
  end
  return true
end

-- Runs a task to its next wait, or to its end, in a budget frame of its
-- own with its scope's budget (what it used is charged to the caller, as for
-- any nested call in). Not through `invoke`: the coroutine's body has described any
-- error already, and the frame stops counting (`spent`) as the coroutine
-- yields or returns, so that the bookkeeping here can't overrun in it after a
-- task that ran past its budget and lose where the task was.
local function resume(task, ...)
  local co = task.co
  if not task.active or co_status(co) ~= "suspended" then
    return
  end
  local budget = budgets[task.scope]
  local outer, outer_task = current(), stepping
  local f = guard.frame(budget, task.scope, outer)
  f.kind = "task"
  set_current(f)
  stepping, task.frame = task, f
  local ok, first, failure = co_resume(co, ...)
  f.spent = true
  local now = timing.close(f, task.fn, outer)
  set_current(outer)
  stepping, task.frame = outer_task, nil
  f.left = math.max(f.left, 0)
  local message, file, line, trace
  if not ok then
    -- Only a fault in the prelude itself: the body catches everything else.
    message = tostring(first)
  elseif co_status(co) == "dead" then
    if first == false then
      message, file, line, trace = failure.message, failure.file, failure.line, failure.traceback
    end
  elseif first ~= WAIT then
    file, line = thread_frame(co)
    message = "coroutine.yield can't pause a task: use nf.wait, nf.wait_until or nf.wait_for"
  elseif f.overran then
    -- It caught the overrun with pcall and went on to wait: it ends anyway.
    file, line = thread_frame(co)
  end
  if f.overran then
    message = f.overran
  end
  if message ~= nil or not task.active or co_status(co) == "dead" then
    -- Finished, failed, or ended while it ran (it stops at this wait).
    end_task(task)
    if co_status(co) == "suspended" then
      co_close(co)
    end
  end
  if outer ~= nil then
    guard.charge(outer, budget - f.left, now)
  end
  if message ~= nil then
    prim["events.failed"](task.scope, "task", message, file, line, trace, false)
  end
end

-- What a timer or a handler calls to carry a task on from the wait `token` stands for.
local function wake(task, token, ...)
  if task.waiting ~= token then
    return
  end
  task.waiting = nil
  resume(task, ...)
end

-- The task whose own code is running, for a wait called `what`; an error
-- anywhere else. Called straight from the API function, so level 3 is its
-- caller (`level` says otherwise, for a helper in between).
local function running_task(what, level)
  level = level or 3
  local task = thread_tasks[co_running()]
  local frame = current()
  if task == nil or task.frame ~= frame then
    if task ~= nil then
      error(
        what
          .. " can't wait in an event handler, even one a task set off: start a task for it with nf.task",
        level
      )
    elseif stepping ~= nil and stepping.frame == frame then
      error(
        what .. " can't wait inside a coroutine of your own: wait in the task's own code",
        level
      )
    end
    error(
      what
        .. " only works inside a task: start one with nf.task(function() ... end), since a script's body, event handlers and timers can't wait",
      level
    )
  end
  if not co_isyieldable() then
    error(
      what
        .. " can't wait inside a function called back from outside Lua (like table.sort's comparator or string.gsub's replacement): wait in the task's own code",
      level
    )
  end
  return task
end

-- Suspends the running task until it's woken with this wait's token, and
-- returns what it was woken with. A task ended meanwhile never returns.
local function suspend(task, token)
  task.waiting = token
  -- This resume's frame stops counting here, before the yield: the few
  -- instructions `resume` runs after it mustn't overrun in it.
  current().spent = true
  return co_yield(WAIT)
end

-- An async function's two forms (`async` in the spec), for the generated
-- bindings: `begin` hands back what Kotlin keeps to call with `value, err`
-- once the work is done (on the main thread, `AsyncWork`). That's the
-- script's callback; or, without one, a waker for the running task (an error
-- outside a task, before any work starts), with the task and the wait's token.
-- Last, the scope whose code is calling: a handle method's wait belongs to it
-- (a namespace function's, to the scope whose `nf` it is). `wait` suspends the
-- task until the waker runs, noting the wait's id meanwhile so that ending the
-- task cancels it (`end_task`). Both are called straight from the API
-- function: level 3 is its caller's line.
async.begin = function(what, callback)
  if callback ~= nil then
    if type(callback) ~= "function" then
      bad("callback", "function or nil", callback, 3)
    end
    return callback, nil, nil, running_scope()
  end
  local task = running_task(what, 4)
  local token = {}
  return function(value, err)
    -- As for a wait's timer: this frame is only here to wake the task.
    current().spent = true
    wake(task, token, value, err)
  end,
    task,
    token,
    task.scope
end

async.wait = function(task, token, id)
  task.pending = id
  local value, err = suspend(task, token)
  task.pending = nil
  return value, err
end

local function sleep(task, ticks)
  local token = {}
  task.timer = prim.timer(task.scope, ticks, 0, function()
    task.timer = nil
    -- This frame is the timer's own, there only to wake the task, whose
    -- resume has a frame of its own: the resume's use is charged to it, but it
    -- stops counting, so it can't fail for what the task did.
    current().spent = true
    wake(task, token)
  end)
  suspend(task, token)
end

-- Suspends the task until one of `waits` (each `{ target, event, accept }`)
-- is answered. Every such event wakes the task, which runs `accept(event)`
-- as its own code (so a filter's error is the task's) and keeps waiting
-- until one returns true and a value, which this returns. `opening`, if
-- given, runs once everything is listened to; when it returns false the wait
-- is over before it began, with nil. A target that's gone, already or while
-- waiting, ends the task. `level` is where a bad event name points, as
-- `subscribe` counts it.
local function wait_events(task, waits, level, opening)
  local token = {}
  local subs = {}
  task.subs = subs
  for i, wait in ipairs(waits) do
    local handle = subscribe(task.scope, wait.target, wait.event, function(ev)
      -- As for a wait's timer: this frame is only here to wake the task. What
      -- the task uses still reaches whoever raised the event, as their cost.
      current().spent = true
      wake(task, token, i, ev)
    end, nil, false, level)
    local sub = subscriptions[keys.Subscription(handle)]
    if sub == nil then
      end_task(task)
      suspend(task, token)
    end
    sub.gone = function()
      end_task(task)
    end
    subs[#subs + 1] = sub
  end
  if opening ~= nil and not opening() then
    release_waits(task)
    return nil
  end
  while true do
    local i, ev = suspend(task, token)
    local done, value = waits[i].accept(ev)
    if done then
      release_waits(task)
      return value
    end
  end
end

return {
  WAIT = WAIT,
  tasks = tasks,
  scope_tasks = scope_tasks,
  thread_tasks = thread_tasks,
  async = async,
  task_body = task_body,
  new_task = new_task,
  forget_task = forget_task,
  end_task = end_task,
  resume = resume,
  running_task = running_task,
  sleep = sleep,
  wait_events = wait_events,
}
