package dev.netherforge.plugin.lua

import dev.netherforge.plugin.binding.LuaHandle
import kotlinx.serialization.json.JsonElement
import party.iroiro.luajava.Lua

/** A Kotlin function scripts reach through the prelude. Reads its arguments from [lua]'s stack from 1, pushes results, returns how many. */
fun interface Primitive {
    fun call(lua: Lua): Int
}

/** A Lua function a primitive was given, kept in the registry by [ref] until [LuaHost.unref]. */
@JvmInline
value class LuaFunction(val ref: LuaRef)

/**
 * A Lua value kept in the registry until [LuaHost.unref]: a function the
 * runtime calls later (a timer's, a command's dispatcher, a mob goal's
 * callback), a menu window's context, a dialog opening's, a `data()` table.
 * Pushed back to Lua as the very value it was, not a copy, so every script
 * that asks gets the same table.
 *
 * It carries the [generation] of the [LuaHost] that kept it, and the host
 * checks it on every use ([LuaHost.pushValue], [LuaHost.invoke],
 * [LuaHost.unref]): a full reload starts a new host, and a registry slot
 * number from the old one names something else (or nothing) in the new one,
 * where using it would corrupt the Lua heap. Such a use is a bug, and throws
 * [StaleLuaRef].
 */
@JvmInline
value class LuaRef private constructor(private val packed: Long) {
    /** Which [LuaHost] kept it: [LuaHost.generation]. */
    val generation: Int get() = (packed ushr 32).toInt()

    /** Its slot in that host's registry. */
    internal val slot: Int get() = packed.toInt()

    override fun toString(): String = "LuaRef($generation:$slot)"

    companion object {
        internal fun of(generation: Int, slot: Int) = LuaRef((generation.toLong() shl 32) or (slot.toLong() and 0xffffffffL))
    }
}

/** A [LuaRef] used with a [LuaHost] other than the one that kept it. */
class StaleLuaRef(ref: LuaRef, generation: Int) :
    IllegalStateException("$ref was kept by Lua state ${ref.generation}, not this one ($generation): a session held it past its end")

/**
 * A [LuaHost] entered from a thread other than the one that owns it. A bug:
 * work off the main thread hands its result back through the runtime's
 * completions (`AsyncWork`), and only there calls into Lua.
 */
class WrongThread(entry: String, owner: Thread, caller: Thread) :
    IllegalStateException(
        "LuaHost.$entry was called on thread \"${caller.name}\", but this Lua state belongs to thread \"${owner.name}\": " +
            "Lua can't be used from two threads. Hand the result to the main thread (AsyncWork, Completions) and call Lua there."
    )

/** A value encoded for saving: its JSON text (null when the value itself can't be saved), and what in it couldn't be, by key path. */
data class EncodedData(val text: String?, val problems: List<String>)

/** An argument of any type, read only if the primitive asks, and only while it runs. */
class LuaValue(private val host: LuaHost, private val lua: Lua, private val index: Int) {
    /**
     * As JSON, by the one Lua↔JSON codec ([LuaHost.json]): typed values tagged.
     * What JSON can't hold is a [LuaApiException] naming where it is, from
     * [root]. A table's keys in [skip] are left out (read them with [field]).
     */
    fun json(root: String = "value", skip: Set<String> = emptySet()): JsonElement = host.json(lua, index, root, skip)

    /** Whether it's `nil` (an optional `any` left out). */
    val isNil: Boolean get() = lua.isNil(index)

    /** Reads it as [codec] would read an argument, named [where] in a mistake. */
    fun <T> read(codec: LuaCodec<T>, where: String): T = codec.read(LuaCall(host, lua), index, where)

    /** Pushes the very value again (onto the same state's stack). */
    fun push(onto: Lua) = onto.pushValue(index)

    /** The field [key] of this table, or null when it's nil. */
    fun field(key: String): LuaValue? {
        host.field(lua, index, key)
        if (lua.isNil(-1)) {
            lua.pop(1)
            return null
        }
        return LuaValue(host, lua, lua.top)
    }

    /** Keeps the value alive past this call, as itself; release it with [LuaHost.unref]. */
    fun keep(): LuaRef = host.ref(lua, index)

    /** Encodes it as saved data (see [LuaHost.encodeData]). */
    fun data(root: String): EncodedData = host.encodeData(lua, index, root)

    /** Reads it with a conversion that works on the stack (`Marshal.itemsByIndex`): [read] gets the state and its index. */
    fun <T> read(read: (Lua, Int) -> T): T = read(lua, index)

    /** Charges the calling script for [entries] of it a [read] walked ([LuaHost.meter]). */
    fun meter(entries: Int) = host.meter(entries)
}

/** Why a call into Lua failed, located in the project when Lua could say where. */
data class ScriptFailure(val message: String, val file: String?, val line: Int?, val traceback: String?)

sealed interface CallResult {
    /** The call returned; [value] is its first result as a Kotlin Boolean, Double, String or null. */
    data class Ok(val value: Any?) : CallResult

    data class Failed(val failure: ScriptFailure) : CallResult

    /** Nothing was called: the scope isn't live, or there's no Lua state. */
    data object Absent : CallResult
}

/** What one scope's code cost since the host last asked: [nanos] of its own time over [calls] calls in. */
data class ScopeCost(val nanos: Long, val calls: Int)

/**
 * What one function a scope ran cost, as the profiler kept it: [kind] is the
 * event a handler is for, or `load`, `timer`, `task`, `command`, `complete`,
 * `goal`, `callback`; [file] and [line] where the function starts (no line
 * for a script's body; neither for code that isn't the project's).
 */
data class CallCost(val scope: Int, val kind: String, val file: String?, val line: Int?, val nanos: Long, val calls: Int, val max: Long)

/** One step of an event's path: the handlers for [event] on [target] (null for `nf`). */
data class EventStage(val event: String, val target: LuaHandle?)

/**
 * How an event ended: whether it's cancelled, and why any writable field a
 * handler left couldn't be read back (an item put into a list in place, which
 * nothing checked): such a field is left as it was.
 */
data class EmitOutcome(val cancelled: Boolean, val problems: List<String> = emptyList())

/**
 * Thrown by a primitive for a mistake in the script calling it. Lua sees the
 * message alone: luajava turns a thrown exception into a Lua error carrying
 * its `toString()`, which for anything else is prefixed with the class name.
 */
open class LuaApiException(message: String) : RuntimeException(message) {
    override fun toString(): String = message ?: "error"

    override fun fillInStackTrace(): Throwable = this
}
