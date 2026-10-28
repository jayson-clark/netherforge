-- The census: what each scope holds, estimated by walking what it can reach:
-- its globals, the files it required, its handlers, its tasks and timers, and,
-- for the scope that's running, the locals of project code on the stack.
-- Scopes are walked oldest first and a value reached twice is counted once,
-- for the first, so a module's table that every script requiring it shares
-- is the module's. Only project code is walked into: the prelude's and the
-- library's functions, and the metatables of handles and values, aren't the
-- scripts'. Sizes are Lua 5.4's on 64 bits, roughly: enough to say which
-- script holds the memory, and about how much. A walk is as slow as what it
-- walks is big, so it runs only when memory is over its limit and when
-- `/nf scripts` asks (`host.memory`).

local std = require("std")
local handles = require("handles")
local sandbox = require("sandbox")
local guard_module = require("guard")
local events = require("events")
local tasks = require("tasks")
local scopes = require("scopes")

local raw, getinfo, getlocal, raw_getmetatable =
  std.raw, std.getinfo, std.getlocal, std.raw_getmetatable
local co_running = std.co_running
local type, next, pairs, ipairs = type, next, pairs, ipairs
local kinds = handles.kinds
local base, libraries = sandbox.base, sandbox.libraries
local hooks, guard = guard_module.hooks, guard_module.guard
local envs, loaded = scopes.envs, scopes.loaded
local scope_subscriptions, subscriptions = events.scope_subscriptions, events.subscriptions
local scope_tasks, tasks_by_id = tasks.scope_tasks, tasks.tasks

-- The bytes `root` holds that `seen` hasn't counted yet; `stack` is scratch space.
local function holds(root, seen, stack)
  local total, top = 0, 1
  stack[1] = root
  while top > 0 do
    local value = stack[top]
    stack[top] = nil
    top = top - 1
    local kind = type(value)
    if kind ~= "nil" and kind ~= "number" and kind ~= "boolean" and not seen[value] then
      seen[value] = true
      if kind == "string" then
        total = total + 24 + #value
      elseif kind == "table" then
        total = total + 56
        local mt = raw_getmetatable(value)
        if kinds[mt] == nil then
          for k, v in next, value do
            total = total + 32
            stack[top + 1], stack[top + 2] = k, v
            top = top + 2
          end
          if mt ~= nil then
            top = top + 1
            stack[top] = mt
          end
        end
      elseif kind == "function" then
        local info = getinfo(value, "Su")
        if info.source:sub(1, 1) == "@" then
          total = total + 40 + 16 * info.nups
          for i = 1, info.nups do
            local _, up = raw.getupvalue(value, i)
            top = top + 1
            stack[top] = up
          end
        end
      elseif kind == "thread" then
        total = total + 1024
        for level = 0, 200 do
          local info = getinfo(value, level, "Sf")
          if info == nil then
            break
          end
          if info.source:sub(1, 1) == "@" then
            top = top + 1
            stack[top] = info.func
            for i = 1, 255 do
              local name, local_value = getlocal(value, level, i)
              if name == nil then
                break
              end
              top = top + 1
              stack[top] = local_value
            end
          end
        end
      end
    end
  end
  return total
end

-- Bytes held, by scope; `running`, the scope whose call is running, also
-- holds what its project code has in locals on the running threads.
function guard.census(running)
  local seen, stack, sizes = {}, {}, {}
  for _, library in pairs(libraries) do
    seen[library] = true
  end
  seen[base] = true
  local order = {}
  for scope in pairs(envs) do
    order[#order + 1] = scope
  end
  raw.sort(order)
  for _, scope in ipairs(order) do
    local total = holds(envs[scope], seen, stack) + holds(loaded[scope], seen, stack)
    for id in pairs(scope_subscriptions[scope] or {}) do
      local sub = subscriptions[id]
      if sub ~= nil then
        total = total + holds(sub.fn, seen, stack)
      end
    end
    for id in pairs(scope_tasks[scope] or {}) do
      local task = tasks_by_id[id]
      if task ~= nil then
        total = total + holds(task.fn, seen, stack) + holds(task.co, seen, stack)
      end
    end
    sizes[scope] = total
  end
  if running ~= nil then
    local threads = { co_running() }
    if threads[1] ~= hooks.main then
      threads[2] = hooks.main
    end
    seen[threads[1]], seen[threads[2] or threads[1]] = nil, nil
    local total = 0
    for _, thread in ipairs(threads) do
      total = total + holds(thread, seen, stack) - 1024
    end
    sizes[running] = (sizes[running] or 0) + total
  end
  return sizes
end

return { census = guard.census, holds = holds }
