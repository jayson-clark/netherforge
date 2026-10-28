package dev.netherforge.format.lua

/**
 * A Lua 5.4 of the platform's, for Lua that runs apart from the server's own state: luajava's on the JVM
 * ([dev.netherforge.format.lua.LuajavaPlatform]), wasmoon's (Lua 5.4 compiled to WebAssembly) in JS. Everything
 * format runs on it goes through this small surface, so the Lua side of it (the sandbox, the budget, the API a
 * script sees) is one text that runs the same on both.
 */
interface LuaPlatform {
    /**
     * A new state with Lua's standard library, and [functions] as globals by name. A state is used by one thread
     * at a time, and only through the calls here.
     */
    fun open(functions: Map<String, LuaHostFunction>): LuaState
}

/**
 * A function Lua calls. Its arguments are read through [LuaArgs]; it returns one value (a [Long] as a Lua integer,
 * any other number as a float, a [String], a [Boolean], a [LuaChunk] to load, or null for nil), or throws
 * [LuaFailure] to raise that message as a Lua error. Only a [Long] is an integer because only a Long can be told
 * from a Double in JS, where both are numbers.
 */
fun interface LuaHostFunction {
    fun call(args: LuaArgs): Any?
}

/** The arguments of a call into a [LuaHostFunction]. */
interface LuaArgs {
    val count: Int

    /** Argument [index] (from 1) as a number, or NaN when it isn't one. */
    fun number(index: Int): Double

    /** Argument [index] as a string, or null when it isn't one. */
    fun string(index: Int): String?
}

/**
 * What a [LuaHostFunction] returns to hand Lua a chunk of code: [source] loaded as a function named [name] (`@path`,
 * which messages and tracebacks show), not run. A chunk that doesn't compile raises its message as the error. This
 * is how a script's text reaches Lua without crossing as a string argument (wasmoon passes those on a small stack).
 */
class LuaChunk(val name: String, val source: String)

/** One Lua state of a [LuaPlatform]. */
interface LuaState {
    /** Runs [source] as the chunk [name]. Throws [LuaFailure] with Lua's message when it doesn't compile or fails. */
    fun run(name: String, source: String)

    /**
     * Calls the global function [function] with [args] (each a [Long], another number, a [String], a [Boolean] or null)
     * and gives its first result: a [Long] for a Lua integer, a [Double], a [String], a [Boolean] or null. Throws
     * [LuaFailure] with the error's message when it fails.
     */
    fun call(function: String, vararg args: Any?): Any?

    fun close()
}

/** A Lua error, as its message. */
class LuaFailure(override val message: String) : RuntimeException(message) {
    // Lua (luajava) takes a thrown exception's text as the error: the message alone, not the class name.
    override fun toString(): String = message
}
