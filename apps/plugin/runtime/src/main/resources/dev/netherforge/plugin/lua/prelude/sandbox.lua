-- The sandbox: what a script gets from Lua itself, and what it never does.
--
-- Scripts never see the real global table. Each scope (a module, or one node
-- script of one centity instance, or a menu or dialog script) gets its own
-- environment whose missing keys fall back to its own copy of `base`, a
-- whitelist of the standard library. Library tables are read-only proxies, and
-- each scope has its own, so one script can't redefine `string.format` for
-- every other.

local std = require("std")
local input = require("input")
local guard_module = require("guard")

local limits = input.limits
local hooks, guard, locate = guard_module.hooks, guard_module.guard, guard_module.locate
local raw, load, dump = std.raw, std.load, std.dump
local co_create, co_resume, co_status, co_yield =
  std.co_create, std.co_resume, std.co_status, std.co_yield
local co_running, co_isyieldable, co_close = std.co_running, std.co_isyieldable, std.co_close
local unpack = std.unpack
local pairs, ipairs, next, error, setmetatable, getmetatable =
  pairs, ipairs, next, error, setmetatable, getmetatable

-- A library proxy's metatable: one per library, shared by every scope's
-- proxy of it (no script can reach a metatable that says `__metatable =
-- false`), so a new scope makes only its proxies' empty tables.
local function readonly_mt(source, name)
  return {
    __index = source,
    __newindex = function()
      error(name .. " is shared by every script and can't be changed", 2)
    end,
    __pairs = function()
      return next, source, nil
    end,
    __len = function()
      return #source
    end,
    __metatable = false,
  }
end

local sandboxed_coroutine = {
  create = function(fn)
    return hooks.arm(co_create(fn))
  end,
  wrap = function(fn)
    local co = hooks.arm(co_create(fn))
    return function(...)
      local results = { co_resume(co, ...) }
      if not results[1] then
        error(results[2], 2)
      end
      return unpack(results, 2)
    end
  end,
  resume = co_resume,
  status = co_status,
  yield = co_yield,
  running = co_running,
  isyieldable = co_isyieldable,
  close = co_close,
}

-- Everything a script gets from Lua itself. A whitelist: anything not named
-- here (io, os, package, load, dofile, loadfile, debug, collectgarbage, warn,
-- and luajava's `java`) simply isn't there. This is the template; every scope
-- gets its own copy (`scope_base`), so nothing here is ever shared.
---@type table<string, any>
local base = {
  _VERSION = _VERSION,
  assert = assert,
  error = error,
  getmetatable = getmetatable,
  ipairs = ipairs,
  next = next,
  pairs = pairs,
  pcall = pcall,
  rawequal = rawequal,
  rawget = rawget,
  rawlen = rawlen,
  rawset = rawset,
  select = select,
  setmetatable = setmetatable,
  tonumber = tonumber,
  tostring = tostring,
  type = type,
  xpcall = xpcall,
}

-- The library tables, behind read-only proxies.
local libraries = {
  string = string,
  table = table,
  math = math,
  utf8 = utf8,
  coroutine = sandboxed_coroutine,
}
for name in pairs(libraries) do
  base[name] = true
end

-- A cap's refusal: an error at the script's line.
function guard.refuse(message)
  error(locate(message), 0)
end

-- The standard library's caps ("=nf/caps") go into the real tables, which
-- the proxies and the string metatable read through. They run without their
-- debug information: the library locates its own errors (a bad argument) at
-- its caller, which is then a function with no lines, like a C function's
-- caller, so the error is located at the script's line instead.
do
  local caps = load(dump(input.caps, true), "=nf/caps", "b")(raw, limits, guard.spend, guard.refuse)
  for name, fn in pairs(caps.string) do
    string[name] = fn
  end
  for name, fn in pairs(caps.table) do
    table[name] = fn
  end
  base.setmetatable = caps.setmetatable
end

-- A scope's own copy of `base`, with its own library proxies. The proxies
-- stop plain assignment, but `rawset` still writes onto a proxy, so each
-- scope must hold its own: then that write reaches only the scope that made it.
---@type string[], table[]
local library_names, library_mts = {}, {}
for name, library in pairs(libraries) do
  library_names[#library_names + 1] = name
  library_mts[#library_mts + 1] = readonly_mt(library, name)
end

-- Where a scope's copy finds the rest of `base`: the library's own functions,
-- which nothing can change, so they're shared (a table `__index` costs a
-- script no instructions, where copying them cost every new scope).
local base_mt = { __index = base, __metatable = false }

local function scope_base()
  local copy = setmetatable({}, base_mt)
  for i = 1, #library_names do
    copy[library_names[i]] = setmetatable({}, library_mts[i])
  end
  return copy
end

-- `("x"):upper()` goes through the string metatable, which would otherwise
-- hand out the real, writable string table.
getmetatable("").__metatable = false
-- Defence in depth: the real globals lose everything dangerous too, in case
-- anything ever leaks a reference to them. The prelude's last step, once every
-- module is loaded (they load each other through `require`, which goes too).
local function seal()
  for _, name in ipairs({
    "io",
    "os",
    "package",
    "load",
    "loadstring",
    "dofile",
    "loadfile",
    "debug",
    "java",
    "collectgarbage",
    "require",
    "print",
    "warn",
  }) do
    _G[name] = nil
  end
end

return {
  base = base,
  libraries = libraries,
  scope_base = scope_base,
  seal = seal,
}
