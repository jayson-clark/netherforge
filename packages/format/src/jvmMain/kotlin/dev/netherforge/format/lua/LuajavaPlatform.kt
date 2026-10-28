package dev.netherforge.format.lua

import party.iroiro.luajava.JFunction
import party.iroiro.luajava.Lua
import party.iroiro.luajava.LuaException
import party.iroiro.luajava.lua54.Lua54
import java.nio.ByteBuffer

/**
 * Lua 5.4 through luajava, the same native Lua the plugin's own state is: one [Lua54] per state. Each is used by
 * one thread at a time ([LuaState]'s contract), so states on several of the server's chunk threads at once never
 * share anything native.
 *
 * Every call into Lua is protected (`pCall`): an error never unwinds through the JVM's frames (see the
 * plugin-runtime skill, "luajava's sharp edges"). A host function's [LuaFailure] becomes the Lua error luajava
 * raises for a thrown exception, its text the message alone ([LuaFailure.toString]).
 */
object LuajavaPlatform : LuaPlatform {
    override fun open(functions: Map<String, LuaHostFunction>): LuaState = LuajavaState(functions)
}

private class LuajavaState(functions: Map<String, LuaHostFunction>) : LuaState {
    private val lua: Lua = Lua54()

    /** The functions [call] has called, kept on the stack from the bottom up (by name, their slot). */
    private val slots = HashMap<String, Int>()

    init {
        lua.openLibraries()
        for ((name, function) in functions) {
            lua.push(
                JFunction { called ->
                    val result = function.call(Args(called))
                    if (result is LuaChunk) {
                        try {
                            called.load(direct(result.source), result.name)
                        } catch (e: LuaException) {
                            throw LuaFailure(e.message.orEmpty())
                        }
                    } else {
                        push(called, result)
                    }
                    1
                }
            )
            lua.setGlobal(name)
        }
    }

    override fun run(name: String, source: String) {
        protect {
            lua.load(direct(source), name)
            lua.pCall(0, 0)
        }
    }

    override fun call(function: String, vararg args: Any?): Any? = protect {
        val slot = slots[function] ?: run {
            // Kept below everything a call pushes: found once, by name, then by its place.
            lua.getGlobal(function)
            (slots.size + 1).also { slots[function] = it }
        }
        lua.pushValue(slot)
        for (arg in args) push(lua, arg)
        lua.pCall(args.size, 1)
        read(lua, -1)
    }

    override fun close() = lua.close()

    private fun <T> protect(body: () -> T): T = try {
        body()
    } catch (e: LuaException) {
        throw LuaFailure(e.message.orEmpty())
    } finally {
        lua.setTop(slots.size)
    }

    private class Args(private val lua: Lua) : LuaArgs {
        override val count: Int get() = lua.top

        override fun number(index: Int): Double = if (lua.type(index) == Lua.LuaType.NUMBER) lua.toNumber(index) else Double.NaN

        override fun string(index: Int): String? = if (lua.type(index) == Lua.LuaType.STRING) lua.toString(index) else null
    }

    companion object {
        fun push(lua: Lua, value: Any?) {
            when (value) {
                null -> lua.pushNil()
                // As in JS, where a Long is the one number that can be told from the rest: only a Long is an integer.
                is Long -> lua.push(value)
                is Number -> lua.push(value.toDouble() as Number)
                is String -> lua.push(value)
                is Boolean -> lua.push(value)
                else -> error("Lua takes no ${value::class.simpleName}")
            }
        }

        fun read(lua: Lua, index: Int): Any? = when (lua.type(index)) {
            Lua.LuaType.NUMBER -> if (lua.isInteger(index)) lua.toInteger(index) else lua.toNumber(index)
            Lua.LuaType.STRING -> lua.toString(index)
            Lua.LuaType.BOOLEAN -> lua.toBoolean(index)
            else -> null
        }

        fun direct(text: String): ByteBuffer {
            val bytes = text.encodeToByteArray()
            return ByteBuffer.allocateDirect(bytes.size).put(bytes).flip()
        }
    }
}
