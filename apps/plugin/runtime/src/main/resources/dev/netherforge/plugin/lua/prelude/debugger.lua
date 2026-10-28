-- The debugger (dev servers only): breakpoints, stepping, and what a stopped
-- script holds, for the runtime's Debug Adapter Protocol side
-- (debug/Debugger.kt, which the editor speaks to).
--
-- It listens on the guard's one hook (`hooks.listeners.line`; the mask is
-- "l" only while there are breakpoints or a step or pause is under way, ""
-- otherwise, so a server nobody debugs pays nothing) and, for "break on
-- script errors", on `describe` (`hooks.listeners.error`), which runs where
-- an uncaught error happened with the stack still whole.
--
-- Stopping calls the `debug.wait` primitive, which holds the server's main
-- thread in Kotlin until the editor says to go on (the tick is frozen; Kotlin
-- keeps the watchdog and the players' connections alive meanwhile). While it
-- holds, Kotlin hands back what only Lua can answer, one command at a time
-- (the stack, a frame's scopes, a table's entries, new breakpoints), and this
-- answers each with `debug.answer`. A hook runs with hooks off, so nothing
-- here is charged to the script's budget, and the time limit's clock doesn't
-- count paused time (Kotlin's clock leaves it out), so a call held at a
-- breakpoint isn't stopped for running long.
--
-- Nothing of a script runs while it's stopped: values are read with `next`,
-- `rawlen` and the debug library, never through a metamethod (a script's
-- `__index`, `__len` or `__tostring` could loop forever with no hook left to
-- stop it). Only the prelude's own `__tostring`s (Vec3, Location, Event) are
-- called.
--
-- Stepping counts frames rather than hearing calls and returns: Lua's call
-- and return events fire for every call, the prelude's too, on every thread,
-- while counting the stack is only paid on lines while a step is under way.
-- A step belongs to the thread it started on: on the main thread also to the
-- call in (the guard's frame) it started in, so stepping off the end of a
-- handler carries on running rather than stopping in whatever runs next; in a
-- task (a coroutine) to the task, so stepping over `nf.wait(20)` stops at the
-- next line twenty ticks later.

local std = require("std")
local input = require("input")
local guard_module = require("guard")
local handles = require("handles")
local events = require("events")

local prim = input.prim
local hooks, current = guard_module.hooks, guard_module.current
local getinfo, getlocal, sethook = std.getinfo, std.getlocal, std.sethook
local getupvalue, raw_getmetatable = std.raw.getupvalue, std.raw_getmetatable
local co_running, co_status = std.co_running, std.co_status
local raw, format, concat, math_type = std.raw, std.format, std.concat, std.math_type
local type, tostring, next, rawget, rawlen, pcall, ipairs, error =
  type, tostring, next, rawget, rawlen, pcall, ipairs, error
local byte, sub, tointeger = string.byte, string.sub, math.tointeger

local AT = 64 -- "@": a project file's chunk name
local MAX_CHILDREN = 1000
local MAX_STRING = 300

local dbg = {
  attached = false,
  -- "@<path>" -> { [line] = true }
  breakpoints = {},
  -- Stop on uncaught errors.
  errors = false,
  -- nil (running), "pause", "in", "over" or "out".
  mode = nil,
  -- The step under way: { thread, frame, depth }.
  step = nil,
}

local on_line, on_error

local function update()
  local lines = dbg.attached and (dbg.mode ~= nil or next(dbg.breakpoints) ~= nil)
  hooks.listeners.line = lines and on_line or nil
  hooks.listeners.error = (dbg.attached and dbg.errors) and on_error or nil
  hooks.set_mask(lines and "l" or "")
end

-- "file\tline" lines, as Kotlin sends every breakpoint at once.
local function set_breakpoints(text)
  local breakpoints = {}
  for file, line in raw.gmatch(text or "", "([^\t\n]+)\t(%d+)") do
    local source = "@" .. file
    local lines = breakpoints[source]
    if lines == nil then
      lines = {}
      breakpoints[source] = lines
    end
    lines[tointeger(line + 0)] = true
  end
  dbg.breakpoints = breakpoints
end

local function finish_step()
  dbg.mode, dbg.step = nil, nil
  update()
end

-- ---------------------------------------------------------------- showing values

local ESCAPES = { ["\\"] = "\\\\", ["\t"] = "\\t", ["\n"] = "\\n", ["\r"] = "\\r" }

-- A field of an answer's line: tabs and newlines separate them.
local function field(s)
  return (raw.gsub(s, "[\\\t\n\r]", ESCAPES))
end

local QUOTED = { ['"'] = '\\"', ["\\"] = "\\\\", ["\n"] = "\\n", ["\r"] = "\\r", ["\t"] = "\\t" }

local function quote(s)
  local cut = #s > MAX_STRING
  if cut then
    s = sub(s, 1, MAX_STRING)
  end
  s = raw.gsub(s, '[\0-\31"\\\127]', function(c)
    return QUOTED[c] or format("\\%d", byte(c))
  end)
  return '"' .. s .. (cut and '…"' or '"')
end

local function number_text(n)
  if math_type(n) == "integer" then
    return format("%d", n)
  end
  return format("%.14g", n)
end

-- What kind of the prelude's own a table is: a handle's class, "Vec3",
-- "Location", "Event"; nil for a plain table.
local function kind_of(t)
  local mt = raw_getmetatable(t)
  if mt == nil then
    return nil
  end
  if events.event_state[t] ~= nil then
    return "Event"
  end
  local kind = handles.kinds[mt]
  return kind and handles.kind_names[kind]
end

local function is_identifier(s)
  return raw.match(s, "^[%a_][%w_]*$") ~= nil
end

local function key_text(k)
  if type(k) == "string" then
    return is_identifier(k) and k or "[" .. quote(k) .. "]"
  end
  if type(k) == "number" then
    return "[" .. number_text(k) .. "]"
  end
  return "[" .. type(k) .. "]"
end

local show

-- A table's first few entries, `{1, 2, x = 3, …}`, without following nested tables.
local function preview(t)
  local parts, n = {}, rawlen(t)
  for i = 1, n < 4 and n or 4 do
    local v = rawget(t, i)
    parts[#parts + 1] = type(v) == "table" and "{…}" or (show(v))
  end
  local more = n > 4
  local count = #parts
  local k, v = next(t)
  while k ~= nil do
    if not (math_type(k) == "integer" and k >= 1 and k <= n) then
      if count >= 4 then
        more = true
        break
      end
      count = count + 1
      parts[#parts + 1] = key_text(k) .. " = " .. (type(v) == "table" and "{…}" or (show(v)))
    end
    k, v = next(t, k)
  end
  if more then
    parts[#parts + 1] = "…"
  end
  return "{" .. concat(parts, ", ") .. "}"
end

-- A value as the editor shows it: its text, its type, and whether it has entries to expand.
function show(v)
  local t = type(v)
  if t == "string" then
    return quote(v), "string", false
  elseif t == "number" then
    return number_text(v), "number", false
  elseif t == "boolean" or t == "nil" then
    return tostring(v), t, false
  elseif t == "function" then
    local info = getinfo(v, "S")
    if byte(info.source, 1) == AT then
      return "function " .. sub(info.source, 2) .. ":" .. info.linedefined, "function", false
    end
    return info.what == "C" and "built-in function" or "function", "function", false
  elseif t == "thread" then
    return "coroutine (" .. co_status(v) .. ")", "thread", false
  elseif t == "table" then
    local kind = kind_of(v)
    if handles.ids[v] ~= nil then
      local summary = prim["debug.handle"](v)
      return summary, kind or "handle", true
    elseif kind ~= nil then
      -- The prelude's own `__tostring`: Vec3, Location, Event.
      return tostring(v), kind, true
    end
    return preview(v), "table", next(v) ~= nil
  end
  return t, t, false
end

-- ---------------------------------------------------------------- a stop

-- Everything known about the stack at a stop: each project frame's name,
-- file, line, function and locals (copied now: levels move as soon as
-- anything else is called), and how deep the first one is.
local function capture(marker)
  local frames = {}
  local level, found, top = 1, false, nil
  while true do
    local info = getinfo(level, "Slnf")
    if info == nil then
      break
    end
    if found and byte(info.source, 1) == AT then
      top = top or level
      local locals, i = {}, 1
      while true do
        local name, value = getlocal(level, i)
        if name == nil then
          break
        end
        if byte(name, 1) ~= 40 then -- "(temporary)", "(for state)"…
          locals[#locals + 1] = { name, value }
        end
        i = i + 1
      end
      local caller = getinfo(level + 1, "S")
      frames[#frames + 1] = {
        info = info,
        caller_is_project = caller ~= nil and byte(caller.source, 1) == AT,
        locals = locals,
      }
    elseif info.func == marker then
      found = true
    end
    level = level + 1
  end
  local depth = top and level - top or 0
  -- Stopped in a coroutine (a task, or a script's own): the main thread's
  -- frames that led to it follow.
  local main = hooks.main
  if co_running() ~= main then
    level = 0
    while true do
      local info = getinfo(main, level, "Slnf")
      if info == nil then
        break
      end
      if byte(info.source, 1) == AT then
        local locals, i = {}, 1
        while true do
          local name, value = getlocal(main, level, i)
          if name == nil then
            break
          end
          if byte(name, 1) ~= 40 then
            locals[#locals + 1] = { name, value }
          end
          i = i + 1
        end
        local caller = getinfo(main, level + 1, "S")
        frames[#frames + 1] = {
          info = info,
          caller_is_project = caller ~= nil and byte(caller.source, 1) == AT,
          locals = locals,
        }
      end
      level = level + 1
    end
  end
  for _, frame in ipairs(frames) do
    local info = frame.info
    frame.file = sub(info.source, 2)
    frame.line = info.currentline
    frame.func = info.func
    if info.what == "main" then
      frame.name = "(body)"
    elseif info.name ~= nil and frame.caller_is_project then
      frame.name = info.name
    else
      frame.name = "function (line " .. info.linedefined .. ")"
    end
    local i = 1
    while true do
      local name, value = getupvalue(info.func, i)
      if name == nil then
        break
      end
      if name == "_ENV" then
        frame.env = value
      end
      i = i + 1
    end
  end
  return frames, depth
end

-- The reference the editor expands [what] by, the same one for the same table.
local function ref_for(stop, what)
  local ref = stop.ref_of[what]
  if ref == nil then
    ref = #stop.refs + 1
    stop.refs[ref] = what
    stop.ref_of[what] = ref
  end
  return ref
end

local function scopes_text(stop, index)
  local frame = stop.frames[index]
  if frame == nil then
    error("there's no frame " .. tostring(index))
  end
  frame.scopes = frame.scopes or { locals = { frame = frame, kind = "locals" } }
  local lines = { "Locals\t" .. ref_for(stop, frame.scopes.locals) }
  if getupvalue(frame.func, 1) ~= nil then
    frame.scopes.upvalues = frame.scopes.upvalues or { frame = frame, kind = "upvalues" }
    lines[#lines + 1] = "Upvalues\t" .. ref_for(stop, frame.scopes.upvalues)
  end
  if type(frame.env) == "table" then
    lines[#lines + 1] = "Globals\t" .. ref_for(stop, frame.env)
  end
  return concat(lines, "\n")
end

-- Kotlin's lines about a handle ("name\tvalue"), as entries with text only.
local function handle_fields(h)
  local _, text = prim["debug.handle"](h)
  local entries = {}
  for name, value in raw.gmatch(text or "", "([^\t\n]*)\t([^\n]*)") do
    entries[#entries + 1] = { name = name, text = value, type = "string" }
  end
  return entries
end

local function sorted_keys(t, skip_array)
  local keys = {}
  local k = next(t)
  while k ~= nil do
    if not (skip_array and math_type(k) == "integer" and k >= 1 and k <= skip_array) then
      keys[#keys + 1] = k
      if #keys > MAX_CHILDREN then
        break
      end
    end
    k = next(t, k)
  end
  local rank = { number = 1, string = 2, boolean = 3 }
  raw.sort(keys, function(a, b)
    local ta, tb = type(a), type(b)
    if ta ~= tb then
      return (rank[ta] or 4) < (rank[tb] or 4)
    end
    if ta == "number" or ta == "string" then
      return a < b
    end
    return false
  end)
  return keys
end

-- What expanding [what] lists: { name = ..., value = ... } (or `text` and `type`, for a handle's facts).
local function children(what)
  local entries = {}
  if what.kind == "locals" then
    for _, pair in ipairs(what.frame.locals) do
      entries[#entries + 1] = { name = pair[1], value = pair[2] }
    end
    return entries
  elseif what.kind == "upvalues" then
    local i = 1
    while true do
      local name, value = getupvalue(what.frame.func, i)
      if name == nil then
        break
      end
      if name ~= "_ENV" then
        entries[#entries + 1] = { name = name, value = value }
      end
      i = i + 1
    end
    return entries
  end
  local t = what
  if handles.ids[t] ~= nil then
    return handle_fields(t)
  end
  local kind = kind_of(t)
  if kind == "Vec3" then
    return {
      { name = "x", value = rawget(t, 1) },
      { name = "y", value = rawget(t, 2) },
      { name = "z", value = rawget(t, 3) },
    }
  elseif kind == "Location" then
    return {
      { name = "world", value = rawget(t, 1) },
      { name = "position", value = rawget(t, 2) },
      { name = "yaw", value = rawget(t, 3) },
      { name = "pitch", value = rawget(t, 4) },
    }
  elseif kind == "Event" then
    local state = events.event_state[t]
    entries[1] = { name = "name", value = state and state.name }
    t = state and state.fields or {}
  end
  local n = rawlen(t)
  for i = 1, n < MAX_CHILDREN and n or MAX_CHILDREN do
    entries[#entries + 1] = { name = "[" .. i .. "]", value = rawget(t, i) }
  end
  for _, k in ipairs(sorted_keys(t, n)) do
    if #entries >= MAX_CHILDREN then
      entries[#entries + 1] = { name = "…", text = "more entries not shown", type = "string" }
      break
    end
    entries[#entries + 1] = { name = key_text(k), value = rawget(t, k) }
  end
  return entries
end

local function variables_text(stop, ref)
  local what = stop.refs[ref]
  if what == nil then
    error("there are no variables " .. tostring(ref) .. " at this stop")
  end
  local lines = {}
  for _, entry in ipairs(children(what)) do
    local text, kind, expandable = entry.text, entry.type, false
    if text == nil then
      text, kind, expandable = show(entry.value)
    end
    lines[#lines + 1] = field(entry.name)
      .. "\t"
      .. field(text)
      .. "\t"
      .. kind
      .. "\t"
      .. (expandable and ref_for(stop, entry.value) or 0)
  end
  return concat(lines, "\n")
end

local function stack_text(stop)
  local lines = {}
  for i, frame in ipairs(stop.frames) do
    lines[i] = field(frame.name) .. "\t" .. field(frame.file) .. "\t" .. (frame.line or 0)
  end
  return concat(lines, "\n")
end

local answers = {
  stack = function(stop)
    return stack_text(stop)
  end,
  scopes = function(stop, arg)
    return scopes_text(stop, tointeger(arg + 0))
  end,
  variables = function(stop, arg)
    return variables_text(stop, tointeger(arg + 0))
  end,
  breakpoints = function(_, arg)
    set_breakpoints(arg)
    return ""
  end,
  errors = function(_, arg)
    dbg.errors = arg == "1"
    return ""
  end,
}

-- Stops here: tells Kotlin why and where, answers what it asks until it says
-- how to go on. [marker] is the function the stop was found in (the hook, or
-- `describe`): the frames above it are the script's.
local function stop(reason, marker, text)
  local co = co_running()
  local frames, depth = capture(marker)
  if frames[1] == nil then
    return
  end
  -- A stop from `describe` isn't inside the hook: hooks are still on there,
  -- and the count would charge (and stop) the call for the debugger's own work.
  local in_hook = marker == hooks.run
  if not in_hook then
    sethook(co)
  end
  local at = { frames = frames, refs = {}, ref_of = {} }
  local top = frames[1]
  local command, id, arg = prim["debug.wait"](reason, top.file, top.line or 0, text)
  while answers[command] ~= nil do
    local ok, answer = pcall(answers[command], at, arg)
    if ok then
      prim["debug.answer"](id, answer)
    else
      prim["debug.answer"](id, nil, tostring(answer))
    end
    command, id, arg = prim["debug.wait"]()
  end
  if command == "detach" then
    dbg.attached, dbg.breakpoints, dbg.errors = false, {}, false
    dbg.mode, dbg.step = nil, nil
  elseif
    reason == "exception" or (command ~= "next" and command ~= "stepIn" and command ~= "stepOut")
  then
    -- `continue`. After an error the call is over whatever was asked: it goes on failing.
    dbg.mode, dbg.step = nil, nil
  else
    dbg.mode = command == "next" and "over" or command == "stepIn" and "in" or "out"
    dbg.step = { thread = co, frame = current(), depth = depth }
  end
  if not in_hook then
    hooks.arm(co, co == hooks.fast and 1 or nil)
  end
  update()
end

-- The line hook: a breakpoint, or the step or pause under way.
on_line = function(_, line)
  local info = getinfo(3, "S") -- 1 this, 2 the hook, 3 the line's own function
  local source = info.source
  if byte(source, 1) ~= AT then
    return
  end
  local lines = dbg.breakpoints[source]
  if lines ~= nil and lines[line] then
    return stop("breakpoint", hooks.run)
  end
  local mode = dbg.mode
  if mode == nil then
    return
  elseif mode == "in" then
    return stop("step", hooks.run)
  elseif mode == "pause" then
    return stop("pause", hooks.run)
  end
  local step = dbg.step
  if step == nil then
    -- "over" and "out" always come with their step; this only keeps it so.
    return finish_step()
  end
  local co = co_running()
  if co ~= step.thread then
    if co_status(step.thread) == "dead" then
      finish_step()
    end
    return
  end
  if co == hooks.main and current() ~= step.frame then
    -- A call in nested in the one stepped through runs on; once that one is
    -- over, the step is done and the server runs on.
    if step.frame == nil or step.frame.spent then
      finish_step()
    end
    return
  end
  local level = 4
  while getinfo(level, "l") ~= nil do
    level = level + 1
  end
  local depth = level - 3
  if depth < step.depth or (mode == "over" and depth == step.depth) then
    return stop("step", hooks.run)
  end
end

-- An uncaught error, from `describe`.
on_error = function(message, describe)
  stop("exception", describe, message)
end

-- ---------------------------------------------------------------- the host's side

local debugger = {}

-- Attaches (if it wasn't) with these breakpoints ("file\tline" lines) and
-- whether to stop on uncaught errors; [pause] stops at the next line of any
-- script.
function debugger.configure(breakpoints, errors, pause)
  dbg.attached = true
  set_breakpoints(breakpoints)
  dbg.errors = errors
  if pause and dbg.mode == nil then
    dbg.mode = "pause"
  end
  update()
end

-- Forgets the editor: no breakpoints, no step, nothing listening.
function debugger.detach()
  dbg.attached, dbg.breakpoints, dbg.errors = false, {}, false
  dbg.mode, dbg.step = nil, nil
  update()
end

return debugger
