-- The standard library as it is before the sandbox touches it. The sandbox
-- empties the real globals (`debug`, `load`, `collectgarbage` and the rest),
-- and caps the unbounded string and table functions for scripts
-- ("=nf/caps"); everything below the sandbox in the prelude calls these
-- instead, so a cap refusing, or the budget stopping a call inside one, can
-- never happen in the prelude's own code. Nothing here is ever handed to a
-- script.
--
-- This must be the first module to run.

---@class nf.Std
local std = {
  getinfo = debug.getinfo,
  getlocal = debug.getlocal,
  traceback = debug.traceback,
  sethook = debug.sethook,
  raw_getmetatable = debug.getmetatable,
  load = load,
  gc = collectgarbage,
  dump = string.dump,
  concat = table.concat,
  unpack = table.unpack,
  co_create = coroutine.create,
  co_resume = coroutine.resume,
  co_status = coroutine.status,
  co_yield = coroutine.yield,
  co_running = coroutine.running,
  co_isyieldable = coroutine.isyieldable,
  co_close = coroutine.close,
  math_type = math.type,
  floor = math.floor,
  sqrt = math.sqrt,
  sin = math.sin,
  cos = math.cos,
  rad = math.rad,
  abs = math.abs,
  format = string.format,
  --- The library's own functions that the sandbox caps for scripts. The prelude's
  --- own string and table work calls these, never `s:match(p)` or `table.sort`.
  raw = {
    rep = string.rep,
    format = string.format,
    find = string.find,
    match = string.match,
    gmatch = string.gmatch,
    gsub = string.gsub,
    pack = string.pack,
    concat = table.concat,
    move = table.move,
    insert = table.insert,
    remove = table.remove,
    sort = table.sort,
    getupvalue = debug.getupvalue,
  },
}

return std
