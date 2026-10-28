package dev.netherforge.plugin.async

import dev.netherforge.plugin.lua.LuaCodec
import dev.netherforge.plugin.lua.LuaRef
import dev.netherforge.plugin.script.Scope

/**
 * A script waiting for async work ([AsyncWork.await]): the scope it belongs
 * to, and the function to call with `value, err` once the work is done (the
 * script's callback, or the waker of the task that waits), with how [T]
 * crosses to Lua. [what] names the function in a failure's report.
 */
class Pending<T> internal constructor(val id: Int, val scope: Scope, val waker: LuaRef, val codec: LuaCodec<T>, val what: String)
