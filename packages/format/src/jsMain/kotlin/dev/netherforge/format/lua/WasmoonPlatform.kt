package dev.netherforge.format.lua

import kotlin.js.Promise

@JsModule("wasmoon")
@JsNonModule
private external object Wasmoon {
    class LuaFactory(customWasmUri: String?) {
        fun getLuaModule(): Promise<dynamic>
    }
}

private val bigInt: dynamic = js("BigInt")
private val toNumber: dynamic = js("Number")

/**
 * Lua 5.4 compiled to WebAssembly, by wasmoon (MIT, `wasmoon` on npm): the editor's preview runs a terrain's
 * script on it, the same Lua the server's luajava is. Only wasmoon's raw C API is used, not its JS conversions, so
 * values cross exactly as luajava's do (a Lua integer is a [Long], a float a [Double]). What's called for every
 * column (pushing and reading numbers, the call itself) goes straight to the module's exported C functions
 * (`_lua_pushnumber`); the rest through wasmoon's `cwrap`ped ones, which convert strings.
 *
 * The WebAssembly module loads asynchronously, once ([load]); states are then made synchronously.
 */
class WasmoonPlatform private constructor(private val wasm: dynamic) : LuaPlatform {
    override fun open(functions: Map<String, LuaHostFunction>): LuaState = WasmoonState(wasm, functions)

    companion object {
        /**
         * Loads the WebAssembly module: from [wasmUri] (the editor's bundle has its own copy of wasmoon's
         * `glue.wasm`), or, when it's null, from beside wasmoon's own script (Node).
         */
        fun load(wasmUri: String?): Promise<WasmoonPlatform> {
            val module: Promise<dynamic> = Wasmoon.LuaFactory(wasmUri ?: undefined).getLuaModule()
            return module.then { wasm: dynamic -> WasmoonPlatform(wasm) }
        }
    }
}

private const val LUA_TNIL = 0
private const val LUA_TBOOLEAN = 1
private const val LUA_TNUMBER = 3
private const val LUA_TSTRING = 4

/** The largest whole number a Double holds exactly. */
private const val SAFE = 9007199254740991L

private class WasmoonState(private val wasm: dynamic, functions: Map<String, LuaHostFunction>) : LuaState {
    /** The module's exported C functions, called directly. */
    private val c: dynamic = wasm.module
    private val state: dynamic = wasm.luaL_newstate()
    private val pointers = mutableListOf<Int>()

    /** The functions [call] has called, kept on the stack from the bottom up (by name, their slot). */
    private val slots = HashMap<String, Int>()

    init {
        wasm.luaL_openlibs(state)
        for ((name, function) in functions) {
            val callback: (dynamic) -> Int = { called -> host(called, function) }
            val pointer = c.addFunction(callback, "ii") as Int
            pointers.add(pointer)
            wasm.lua_pushcclosure(state, pointer, 0)
            wasm.lua_setglobal(state, name)
        }
    }

    /** A host function's call: its answer pushed, or its failure raised as a Lua error (outside the try, so the unwind isn't caught). */
    private fun host(called: dynamic, function: LuaHostFunction): Int {
        val message: String = try {
            val result = function.call(Args(called))
            if (result is LuaChunk) {
                val status = load(called, result.name, result.source)
                if (status == 0) return 1
                wasm.lua_tolstring(called, -1, null) as String
            } else {
                push(called, result)
                return 1
            }
        } catch (e: LuaFailure) {
            e.message
        } catch (e: Throwable) {
            "${e.message}"
        }
        c._lua_settop(called, 0)
        wasm.lua_pushstring(called, message)
        return wasm.lua_error(called) as Int
    }

    override fun run(name: String, source: String) {
        protect {
            if (load(state, name, source) != 0) fail()
            if (c._lua_pcallk(state, 0, 0, 0, 0, 0) as Int != 0) fail()
        }
    }

    override fun call(function: String, vararg args: Any?): Any? = protect {
        val slot = slots[function] ?: run {
            // Kept below everything a call pushes: found once, by name, then by its place.
            wasm.lua_getglobal(state, function)
            (slots.size + 1).also { slots[function] = it }
        }
        c._lua_pushvalue(state, slot)
        for (arg in args) push(state, arg)
        if (c._lua_pcallk(state, args.size, 1, 0, 0, 0) as Int != 0) fail()
        read(state, -1)
    }

    override fun close() {
        wasm.lua_close(state)
        for (pointer in pointers) c.removeFunction(pointer)
        pointers.clear()
    }

    private fun fail(): Nothing = throw LuaFailure((wasm.lua_tolstring(state, -1, null) as String?).orEmpty())

    private fun <T> protect(body: () -> T): T = try {
        body()
    } finally {
        c._lua_settop(state, slots.size)
    }

    /** Loads [source] as a chunk named [name] onto [on]'s stack, through the heap (a long text can't go on the C stack). */
    private fun load(on: dynamic, name: String, source: String): Int {
        val size = c.lengthBytesUTF8(source) as Int
        val buffer = c._malloc(size + 1)
        try {
            c.stringToUTF8(source, buffer, size + 1)
            return wasm.luaL_loadbufferx(on, buffer, size, name, null) as Int
        } finally {
            c._free(buffer)
        }
    }

    private fun push(on: dynamic, value: Any?) {
        when (value) {
            null -> c._lua_pushnil(on)
            // In JS every number but a Long is a JS number, so `is Int` can't tell an Int from a Double: only a Long is an integer.
            is Long -> c._lua_pushinteger(on, if (value in -SAFE..SAFE) bigInt(value.toDouble()) else bigInt(value.toString()))
            is Number -> c._lua_pushnumber(on, value.toDouble())
            is String -> wasm.lua_pushstring(on, value)
            is Boolean -> c._lua_pushboolean(on, if (value) 1 else 0)
            else -> error("Lua takes no ${value::class.simpleName}")
        }
    }

    private fun read(on: dynamic, index: Int): Any? = when (c._lua_type(on, index) as Int) {
        LUA_TNUMBER -> if (c._lua_isinteger(on, index) as Int != 0) {
            val integer: dynamic = c._lua_tointegerx(on, index, 0)
            val exact = toNumber(integer) as Double
            if (exact >= -SAFE && exact <= SAFE) exact.toLong() else integer.toString().unsafeCast<String>().toLong()
        } else {
            c._lua_tonumberx(on, index, 0) as Double
        }
        LUA_TSTRING -> wasm.lua_tolstring(on, index, null) as String
        LUA_TBOOLEAN -> c._lua_toboolean(on, index) as Int != 0
        LUA_TNIL -> null
        else -> null
    }

    private inner class Args(private val on: dynamic) : LuaArgs {
        override val count: Int get() = c._lua_gettop(on) as Int

        override fun number(index: Int): Double =
            if (c._lua_type(on, index) as Int == LUA_TNUMBER) c._lua_tonumberx(on, index, 0) as Double else Double.NaN

        override fun string(index: Int): String? =
            if (c._lua_type(on, index) as Int == LUA_TSTRING) wasm.lua_tolstring(on, index, null) as String else null
    }
}
