package dev.netherforge.plugin.lua

import dev.netherforge.format.math.Vec3
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.binding.LuaLocation
import dev.netherforge.plugin.item.ItemMatch
import dev.netherforge.plugin.platform.ItemData
import party.iroiro.luajava.Lua
import party.iroiro.luajava.lua54.Lua54Natives
import java.util.IdentityHashMap

/**
 * How one node of the spec's type grammar crosses between Lua and Kotlin:
 * read from one Lua value, pushed as one. The generated bindings build a codec
 * for every type a function takes or returns, an option table holds, or an
 * event carries, out of these, so a type means the same in every position:
 * `Vec3[]` is a list of vectors as a parameter, a return, a field or a
 * payload's writable field alike.
 *
 * Kotlin decides what a value is: a handle's class and a `Vec3` or
 * `Location` are told by their genuine metatables ([LuaHost.kindAt]), which a
 * script can't fake, and a union tries its members in order.
 */
interface LuaCodec<T> {
    /** What an error says was expected: `Vec3`, `list of Player`, `Location or Vec3`. */
    val expected: String

    /**
     * Whether reading leaves the value on the stack, because what it read
     * refers to it there (a [LuaValue]): whoever reads one inside a table
     * mustn't pop it.
     */
    val pinned: Boolean get() = false

    /** Whether the value at [index] is this type's kind of Lua value, for a union choosing a member. [read] may still find it wrong inside. */
    fun accepts(call: LuaCall, index: Int): Boolean

    /** The value at the absolute stack [index]; [where] names it in errors (`target`, `options.near`, `event.drops[2]`). */
    fun read(call: LuaCall, index: Int, where: String): T

    /** Pushes [value] as one Lua value. */
    fun push(call: LuaCall, value: T)
}

/**
 * A value with the codec it crosses by, for [LuaHost.pushValue] (which pushes
 * anything else by what it is): an async function's value, given to its
 * callback as the spec types it.
 */
class Coded<T>(val value: T, val codec: LuaCodec<T>) {
    fun push(call: LuaCall) = codec.push(call, value)
}

/** A value of the wrong type: `bad argument 'where' (expected expected, got got)`. */
class LuaTypeMismatch(val where: String, val expected: String, val got: String) :
    LuaApiException("bad argument '$where' ($expected expected, got $got)")

/**
 * One call's view of the Lua state: the stack a primitive reads its arguments
 * from and pushes its results to, and the host that knows typed values.
 */
class LuaCall(val host: LuaHost, val lua: Lua) {
    /** Reads argument [index] as [codec]; a mistake is an error at the line that called the API function. */
    fun <T> arg(index: Int, name: String, codec: LuaCodec<T>): T = try {
        codec.read(this, index, name)
    } catch (e: LuaApiException) {
        throw LuaApiException(where(2) + e.message)
    }

    /** The handle a method was called on: `self_of` checked its class and passed its key at [index]. */
    fun self(index: Int): LuaHandle = host.handle(lua.toInteger(index))

    /** A vector the bindings passed as three numbers from [index] (the `Vec3` fast path: the Lua side checked it). */
    fun vec3(index: Int): Vec3 = Vec3(lua.toNumber(index), lua.toNumber(index + 1), lua.toNumber(index + 2))

    /** The same, or null when the Lua side passed nil for an optional one. */
    fun vec3OrNull(index: Int): Vec3? = if (lua.isNil(index)) null else vec3(index)

    fun pushVec3(vector: Vec3) {
        lua.push(vector.x as Number)
        lua.push(vector.y as Number)
        lua.push(vector.z as Number)
    }

    fun <T> push(value: T, codec: LuaCodec<T>) = codec.push(this, value)

    /** `"modules/shop/init.lua:12: "`: where the function [level] calls up is, as Lua's `error` would say it; empty for C code. */
    fun where(level: Int): String {
        (lua.luaNatives as Lua54Natives).luaL_where(lua.pointer, level)
        val text = lua.toString(-1).orEmpty()
        lua.pop(1)
        return text
    }

    /** What Lua's `type()` says the value at [index] is, for messages. */
    fun typeName(index: Int): String = when (lua.type(index)) {
        null, Lua.LuaType.NONE, Lua.LuaType.NIL -> "nil"
        Lua.LuaType.LIGHTUSERDATA -> "userdata"
        else -> lua.type(index)!!.name.lowercase()
    }

    fun mismatch(where: String, expected: String, index: Int): Nothing = throw LuaTypeMismatch(where, expected, typeName(index))

    /** Whether the value at [index] is a table that isn't a handle or a value type: what a shape, list or map is. */
    fun isPlainTable(index: Int): Boolean = lua.isTable(index) && kindAt(index) == null

    private var known = 0
    private var knownKind: String? = null

    /** [LuaHost.kindAt], asked once per value while a union picks a member ([choosing]). */
    fun kindAt(index: Int): String? = if (index == known) knownKind else host.kindAt(lua, index)

    /** Runs [choose] with what the value at [index] is worked out once, for every member's [LuaCodec.accepts]. */
    inline fun <T> choosing(index: Int, choose: () -> T): T {
        remember(index)
        try {
            return choose()
        } finally {
            forget()
        }
    }

    fun remember(index: Int) {
        knownKind = host.kindAt(lua, index)
        known = index
    }

    fun forget() {
        known = 0
    }

    /** [index] as an absolute index. */
    fun absolute(index: Int): Int = if (index < 0) lua.top + index + 1 else index

    private val kept = ArrayList<LuaRef>()

    /**
     * Keeps the value at [index] alive in the registry until [LuaHost.unref],
     * for the implementation to hold past the call (a function it calls
     * later, an async function's waker): see [releasing].
     */
    fun keep(index: Int): LuaRef = host.ref(lua, index).also { kept += it }

    /**
     * Runs a primitive's [body]. What its arguments [keep] is the
     * implementation's once the call returns; a call that fails (an argument
     * that doesn't read, halfway through a table, or the implementation
     * refusing it) lets go of all of it, so a mistake leaks nothing.
     */
    fun releasing(body: (LuaCall) -> Int): Int = try {
        body(this)
    } catch (e: Throwable) {
        for (ref in kept) host.unref(ref)
        throw e
    } finally {
        kept.clear()
    }
}

/** The codecs every binding is built from, one per kind of node in the type grammar. */
object LuaCodecs {
    val STRING: LuaCodec<String> = object : LuaCodec<String> {
        override val expected = "string"

        // A number is a string to Lua's `isString` too, so ask its type.
        override fun accepts(call: LuaCall, index: Int) = call.lua.type(index) == Lua.LuaType.STRING
        override fun read(call: LuaCall, index: Int, where: String): String {
            if (!accepts(call, index)) call.mismatch(where, expected, index)
            return call.lua.toString(index)!!
        }
        override fun push(call: LuaCall, value: String) = call.lua.push(value)
    }

    /**
     * `Text`: MiniMessage a player reads, a string whose `<glyph:…>` tags name
     * the writing script's package's glyphs. Read, the tags are written as the
     * server names the glyphs ([LuaMarshal.text]), so the text draws the same
     * wherever and whenever it's parsed; pushed, as the reading script's
     * package writes them.
     */
    val TEXT: LuaCodec<String> = object : LuaCodec<String> {
        override val expected = "string"
        override fun accepts(call: LuaCall, index: Int) = STRING.accepts(call, index)
        override fun read(call: LuaCall, index: Int, where: String): String = call.host.marshal.text(STRING.read(call, index, where))
        override fun push(call: LuaCall, value: String) = call.lua.push(call.host.marshal.spellText(value))
    }

    val NUMBER: LuaCodec<Double> = object : LuaCodec<Double> {
        override val expected = "number"
        override fun accepts(call: LuaCall, index: Int) = call.lua.type(index) == Lua.LuaType.NUMBER
        override fun read(call: LuaCall, index: Int, where: String): Double {
            if (!accepts(call, index)) call.mismatch(where, expected, index)
            return call.lua.toNumber(index)
        }
        override fun push(call: LuaCall, value: Double) = call.lua.push(value as Number)
    }

    /** Any whole number, `2.0` included, which arrives as an integer. */
    val INTEGER: LuaCodec<Long> = object : LuaCodec<Long> {
        override val expected = "whole number"
        override fun accepts(call: LuaCall, index: Int): Boolean {
            if (call.lua.type(index) != Lua.LuaType.NUMBER) return false
            if (call.lua.isInteger(index)) return true
            val number = call.lua.toNumber(index)
            return number == Math.floor(number) && number in LONG_RANGE
        }
        override fun read(call: LuaCall, index: Int, where: String): Long {
            if (!accepts(call, index)) call.mismatch(where, expected, index)
            return if (call.lua.isInteger(index)) call.lua.toInteger(index) else call.lua.toNumber(index).toLong()
        }
        override fun push(call: LuaCall, value: Long) = call.lua.push(value)
    }

    val BOOLEAN: LuaCodec<Boolean> = object : LuaCodec<Boolean> {
        override val expected = "boolean"
        override fun accepts(call: LuaCall, index: Int) = call.lua.isBoolean(index)
        override fun read(call: LuaCall, index: Int, where: String): Boolean {
            if (!accepts(call, index)) call.mismatch(where, expected, index)
            return call.lua.toBoolean(index)
        }
        override fun push(call: LuaCall, value: Boolean) = call.lua.push(value)
    }

    /**
     * A Lua function, kept alive in the registry until the runtime releases it
     * ([LuaHost.unref]); a call that fails releases it itself ([LuaCall.releasing]).
     */
    val FUNCTION: LuaCodec<LuaFunction> = object : LuaCodec<LuaFunction> {
        override val expected = "function"
        override fun accepts(call: LuaCall, index: Int) = call.lua.isFunction(index)
        override fun read(call: LuaCall, index: Int, where: String): LuaFunction {
            if (!accepts(call, index)) call.mismatch(where, expected, index)
            return LuaFunction(call.keep(index))
        }
        override fun push(call: LuaCall, value: LuaFunction) = call.host.pushValue(call.lua, value.ref)
    }

    /** `any` taken: the value itself, read only if the implementation asks, while the call lasts. */
    val VALUE: LuaCodec<LuaValue> = value("any") { _, _ -> true }

    /** `table` taken: the same, checked to be a table. */
    val TABLE: LuaCodec<LuaValue> = value("table") { call, index -> call.lua.isTable(index) }

    private fun value(expected: String, accepts: (LuaCall, Int) -> Boolean): LuaCodec<LuaValue> = object : LuaCodec<LuaValue> {
        override val expected = expected
        override val pinned = true
        override fun accepts(call: LuaCall, index: Int) = accepts(call, index)
        override fun read(call: LuaCall, index: Int, where: String): LuaValue {
            if (!accepts(call, index)) call.mismatch(where, expected, index)
            return LuaValue(call.host, call.lua, index)
        }
        override fun push(call: LuaCall, value: LuaValue) = value.push(call.lua)
    }

    /**
     * `any` or `table` given back: whatever Kotlin has, pushed by what it is
     * ([LuaHost.pushValue]). Read, it's a [LuaValue], like [VALUE].
     */
    val DYNAMIC: LuaCodec<Any?> = object : LuaCodec<Any?> {
        override val expected = "any"
        override val pinned = true
        override fun accepts(call: LuaCall, index: Int) = true
        override fun read(call: LuaCall, index: Int, where: String): Any? =
            if (call.lua.isNil(index)) null else LuaValue(call.host, call.lua, index)
        override fun push(call: LuaCall, value: Any?) = call.host.pushValue(call.lua, value)
    }

    /**
     * Any value, read as data there and then: JSON by the one Lua↔JSON codec ([LuaHost.json]; what JSON can't hold is
     * an error naming [read]'s `where`), null for nil. What an event's writable `any` field is read back with: the
     * handlers have run by the time the payload is used, and the Lua value itself is gone from the stack.
     */
    val DATA: LuaCodec<Any?> = object : LuaCodec<Any?> {
        override val expected = "any"
        override fun accepts(call: LuaCall, index: Int) = true
        override fun read(call: LuaCall, index: Int, where: String): Any? =
            if (call.lua.isNil(index)) null else call.host.json(call.lua, index, where)
        override fun push(call: LuaCall, value: Any?) = call.host.pushValue(call.lua, value)
    }

    val VEC3: LuaCodec<Vec3> = object : LuaCodec<Vec3> {
        override val expected = "Vec3"
        override fun accepts(call: LuaCall, index: Int) = call.kindAt(index) == "Vec3"
        override fun read(call: LuaCall, index: Int, where: String): Vec3 {
            if (!accepts(call, index)) call.mismatch(where, expected, index)
            return call.host.vec3At(call.lua, index)
        }
        override fun push(call: LuaCall, value: Vec3) = call.host.pushValue(call.lua, value)
    }

    val LOCATION: LuaCodec<LuaLocation> = object : LuaCodec<LuaLocation> {
        override val expected = "Location"
        override fun accepts(call: LuaCall, index: Int) = call.kindAt(index) == "Location"
        override fun read(call: LuaCall, index: Int, where: String): LuaLocation {
            if (!accepts(call, index)) call.mismatch(where, expected, index)
            return call.host.locationAt(call.lua, index)
        }
        override fun push(call: LuaCall, value: LuaLocation) = call.host.pushValue(call.lua, value)
    }

    /** An `Item` table, read strictly by the runtime ([LuaMarshal.item]); a mistake names where it was. */
    val ITEM: LuaCodec<ItemData> = object : LuaCodec<ItemData> {
        override val expected = "Item"
        override fun accepts(call: LuaCall, index: Int) = call.isPlainTable(index)
        override fun read(call: LuaCall, index: Int, where: String): ItemData {
            if (!accepts(call, index)) call.mismatch(where, expected, index)
            return located(where) { call.host.marshal.item(call.lua, index) }
        }
        override fun push(call: LuaCall, value: ItemData) = call.host.marshal.pushItem(call.lua, value)
    }

    /** An `ItemMatch` table: a partial item, read by the runtime ([LuaMarshal.match]). */
    val ITEM_MATCH: LuaCodec<ItemMatch> = object : LuaCodec<ItemMatch> {
        override val expected = "ItemMatch"
        override fun accepts(call: LuaCall, index: Int) = call.isPlainTable(index)
        override fun read(call: LuaCall, index: Int, where: String): ItemMatch {
            if (!accepts(call, index)) call.mismatch(where, expected, index)
            return located(where) { call.host.marshal.match(call.lua, index) }
        }
        override fun push(call: LuaCall, value: ItemMatch) = throw LuaApiException("an item match can't be handed to Lua")
    }

    /** Runs [read], naming [where] on a mistake that doesn't already. */
    private inline fun <T> located(where: String, read: () -> T): T = try {
        read()
    } catch (e: LuaTypeMismatch) {
        throw e
    } catch (e: LuaApiException) {
        val message = e.message.orEmpty()
        throw if (message.startsWith(where)) e else LuaApiException("$where: $message")
    }

    private val LONG_RANGE = Long.MIN_VALUE.toDouble()..Long.MAX_VALUE.toDouble()
}

/** A union of string literals (`"clear"|"rain"`): a string that must be one of [choices]. */
class LuaChoice(private val choices: List<String>) : LuaCodec<String> {
    override val expected = "one of " + choices.joinToString(", ") { "\"$it\"" }
    override fun accepts(call: LuaCall, index: Int) = call.lua.type(index) == Lua.LuaType.STRING && call.lua.toString(index) in choices
    override fun read(call: LuaCall, index: Int, where: String): String {
        if (call.lua.type(index) != Lua.LuaType.STRING) call.mismatch(where, expected, index)
        val value = call.lua.toString(index)!!
        if (value !in choices) throw LuaTypeMismatch(where, expected, "\"$value\"")
        return value
    }
    override fun push(call: LuaCall, value: String) = call.lua.push(value)
}

/** `T?`: nil, or a [T]. */
class LuaOptional<T>(private val inner: LuaCodec<T>) : LuaCodec<T?> {
    override val expected = "${inner.expected} or nil"
    override val pinned get() = inner.pinned
    override fun accepts(call: LuaCall, index: Int) = call.lua.isNoneOrNil(index) || inner.accepts(call, index)
    override fun read(call: LuaCall, index: Int, where: String): T? {
        if (call.lua.isNoneOrNil(index)) return null
        return try {
            inner.read(call, index, where)
        } catch (e: LuaTypeMismatch) {
            // The value itself is wrong (not something inside it): nil would have done too.
            throw if (e.where == where) LuaTypeMismatch(where, expected, e.got) else e
        }
    }
    override fun push(call: LuaCall, value: T?) = if (value == null) call.lua.pushNil() else inner.push(call, value)
}

/** `T[]`: a table numbered from 1. An empty table is the empty list. */
class LuaList<T>(private val item: LuaCodec<T>) : LuaCodec<List<T>> {
    override val expected = "list of ${item.expected}"
    override val pinned get() = item.pinned
    override fun accepts(call: LuaCall, index: Int) = call.isPlainTable(index)
    override fun read(call: LuaCall, index: Int, where: String): List<T> {
        if (!accepts(call, index)) call.mismatch(where, expected, index)
        val lua = call.lua
        val length = lua.rawLength(index)
        // Every key must be a position: a table with names in it isn't a list.
        var count = 0
        lua.pushNil()
        while (lua.next(index) != 0) {
            call.host.meter(1)
            lua.pop(1)
            count++
        }
        if (count != length) throw LuaApiException("bad argument '$where' ($expected expected, got a table with other keys)")
        return (1..length).map { k ->
            lua.checkStack(2)
            lua.rawGetI(index, k)
            val value = item.read(call, lua.top, "$where[$k]")
            if (!item.pinned) lua.pop(1)
            value
        }
    }
    override fun push(call: LuaCall, value: List<T>) {
        val lua = call.lua
        lua.createTable(value.size, 0)
        if (value.size < SHARED_FROM) {
            value.forEachIndexed { k, it ->
                lua.checkStack(2)
                item.push(call, it)
                lua.rawSetI(-2, k + 1)
            }
            return
        }
        // A long list repeats its values (each block of a box is one of a few kinds): a string, the one value
        // that costs to push, is pushed once and copied from then on. Only a string: a table pushed twice
        // would be one table in two places.
        val list = lua.top
        lua.createTable(0, 0)
        val strings = lua.top
        val seen = IdentityHashMap<Any, Int>()
        value.forEachIndexed { k, it ->
            lua.checkStack(3)
            val kept = if (it != null) seen[it] else null
            if (kept != null) {
                lua.rawGetI(strings, kept)
            } else {
                item.push(call, it)
                if (it != null && seen.size < MAX_SHARED && lua.type(-1) == Lua.LuaType.STRING) {
                    seen[it] = seen.size + 1
                    lua.pushValue(-1)
                    lua.rawSetI(strings, seen.size)
                }
            }
            lua.rawSetI(list, k + 1)
        }
        lua.pop(1)
    }

    private companion object {
        /** How long a list is before its strings are pushed once each. */
        const val SHARED_FROM = 64

        /** How many different strings it keeps to copy: one list of mostly different strings gains nothing. */
        const val MAX_SHARED = 1024
    }
}

/** `table<K, V>`: keys of [key] (strings or whole numbers), each value a [value]. */
class LuaMap<K, V>(private val key: LuaCodec<K>, private val value: LuaCodec<V>) : LuaCodec<Map<K, V>> {
    override val expected = "table of ${key.expected} to ${value.expected}"
    override val pinned get() = value.pinned
    override fun accepts(call: LuaCall, index: Int) = call.isPlainTable(index)
    override fun read(call: LuaCall, index: Int, where: String): Map<K, V> {
        if (!accepts(call, index)) call.mismatch(where, expected, index)
        val lua = call.lua
        // The keys first, then each value by its key: a value read off the stack may have to stay there.
        val keys = mutableListOf<K>()
        lua.pushNil()
        while (lua.next(index) != 0) {
            call.host.meter(1)
            val at = lua.top - 1
            if (!key.accepts(call, at)) {
                val got = call.typeName(at)
                lua.pop(2)
                throw LuaApiException("bad argument '$where' (${key.expected} keys expected, got a $got key)")
            }
            keys += key.read(call, at, where)
            lua.pop(1)
        }
        val out = LinkedHashMap<K, V>()
        for (k in keys) {
            lua.checkStack(3)
            key.push(call, k)
            lua.rawGet(index)
            out[k] = value.read(call, lua.top, if (k is String && NAME.matches(k)) "$where.$k" else "$where[${quoted(k)}]")
            if (!value.pinned) lua.pop(1)
        }
        return out
    }
    override fun push(call: LuaCall, value: Map<K, V>) {
        val lua = call.lua
        lua.createTable(0, value.size)
        for ((k, v) in value) {
            lua.checkStack(3)
            key.push(call, k)
            this.value.push(call, v)
            lua.rawSet(-3)
        }
    }

    private fun quoted(key: Any?) = if (key is String) "\"$key\"" else key.toString()

    private companion object {
        val NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}

/**
 * A union that isn't only string literals: [members] are tried in the
 * type's order, and the first whose kind of Lua value it is reads it. Kotlin
 * gets [T], what each member's value is wrapped as (a generated sealed
 * interface, or [LuaHandle] when every member is a handle class).
 */
class LuaUnion<T : Any>(private val members: List<Member<T, *>>) : LuaCodec<T> {
    class Member<T : Any, V : Any>(val codec: LuaCodec<V>, val wrap: (V) -> T, val unwrap: (T) -> V?) {
        fun read(call: LuaCall, index: Int, where: String): T = wrap(codec.read(call, index, where))

        /** Pushes [value] if it's this member's; false when it isn't. */
        fun push(call: LuaCall, value: T): Boolean {
            codec.push(call, unwrap(value) ?: return false)
            return true
        }
    }

    override val expected = members.map { it.codec.expected }.let { names ->
        if (names.size == 1) names[0] else names.dropLast(1).joinToString(", ") + " or " + names.last()
    }
    override val pinned get() = members.any { it.codec.pinned }
    override fun accepts(call: LuaCall, index: Int) = members.any { it.codec.accepts(call, index) }
    override fun read(call: LuaCall, index: Int, where: String): T {
        val member = call.choosing(index) { members.firstOrNull { it.codec.accepts(call, index) } }
            ?: call.mismatch(where, expected, index)
        return member.read(call, index, where)
    }
    override fun push(call: LuaCall, value: T) {
        if (members.none { it.push(call, value) }) throw IllegalArgumentException("$value isn't any of $expected")
    }
}

/**
 * A union of handle classes only (`Centity|Node|Player|Entity`): Kotlin gets
 * whichever [LuaHandle] it is (the first of [members] it's a handle of: a
 * `Player` is an `Entity` too), and pushes any back as the handle it is.
 */
class LuaHandleUnion(private val members: List<HandleCodec<*>>) : LuaCodec<LuaHandle> {
    override val expected = members.map { it.expected }.let { it.dropLast(1).joinToString(", ") + " or " + it.last() }
    override fun accepts(call: LuaCall, index: Int) = members.any { it.accepts(call, index) }
    override fun read(call: LuaCall, index: Int, where: String): LuaHandle {
        val member = call.choosing(index) { members.firstOrNull { it.accepts(call, index) } } ?: call.mismatch(where, expected, index)
        return member.read(call, index, where)
    }
    override fun push(call: LuaCall, value: LuaHandle) = call.host.pushValue(call.lua, value)
}

/**
 * A handle class's handles ([classes]: the class and every class below it,
 * since a `Mob` goes wherever an `Entity` does). Read, the handle is the
 * entry the Lua table's key names in the host's [HandleTable]; pushed, it's
 * that entry's table, so two of the same thing are the same table.
 *
 * A handle of a class above this one (an `Entity` where a `Mob` is wanted)
 * may only be one the runtime knew less about when it handed it out (its
 * entity was unloaded): it's asked again ([LuaHost.refineAt]) before that's
 * a mistake.
 */
open class HandleCodec<H : LuaHandle>(override val expected: String, private val classes: Set<String>) : LuaCodec<H> {
    /** The classes this one is a kind of, up its chain. */
    private val above: Set<String> = generateSequence(LuaHandle.PARENTS[expected]) { LuaHandle.PARENTS[it] }.toSet()

    override fun accepts(call: LuaCall, index: Int) = call.kindAt(index) in classes
    override fun read(call: LuaCall, index: Int, where: String): H {
        if (!accepts(call, index)) {
            val refined = call.kindAt(index) in above && call.host.refineAt(call.lua, index)
            if (!refined || call.host.kindAt(call.lua, index) !in classes) call.mismatch(where, expected, index)
        }
        @Suppress("UNCHECKED_CAST")
        return call.host.handleAt(call.lua, index) as H
    }
    override fun push(call: LuaCall, value: H) = call.host.pushValue(call.lua, value)
}

/**
 * A plain table Kotlin builds or reads as a generated data class: an option
 * table, a returned record, an event's payload. Read strictly: a key that
 * isn't one of [fields] is an error naming them all, and so is a field the
 * server's Minecraft is too old for ([LuaHost.gates]).
 */
abstract class ShapeCodec<T>(private val shape: String) : LuaCodec<T> {
    /** Each field's codec, by its Lua name. */
    abstract val fields: Map<String, LuaCodec<*>>

    override val expected = "table"

    /** Whether any field, however deep, pins what it reads: the generator works it out, so a shape that holds itself needn't ask itself. */
    abstract override val pinned: Boolean

    /** The data class from its fields. */
    abstract fun read(fields: ShapeReader): T

    /** Pushes each field of [value] that's set. */
    abstract fun write(fields: ShapeWriter, value: T)

    override fun accepts(call: LuaCall, index: Int) = call.isPlainTable(index)

    override fun read(call: LuaCall, index: Int, where: String): T {
        if (!accepts(call, index)) call.mismatch(where, expected, index)
        val lua = call.lua
        lua.pushNil()
        while (lua.next(index) != 0) {
            call.host.meter(1)
            val name = if (lua.type(-2) == Lua.LuaType.STRING) lua.toString(-2) else null
            if (name == null || name !in fields) {
                val key = name ?: lua.toString(-2) ?: call.typeName(lua.top - 1)
                lua.pop(2)
                throw LuaApiException("unknown field '$where.$key' (fields: ${fields.keys.sorted().joinToString(", ")})")
            }
            lua.pop(1)
            call.host.gates.option(shape, name)?.let { since ->
                lua.pop(1)
                throw LuaApiException(call.host.gates.needs("'$where.$name'", since))
            }
        }
        return read(ShapeReader(call, index, where))
    }

    override fun push(call: LuaCall, value: T) {
        call.lua.createTable(0, fields.size)
        write(ShapeWriter(call), value)
    }

    /** Checks a value for field [name] (a handler assigning an event's writable field), reading it as the field would be. */
    fun check(call: LuaCall, name: String, index: Int, where: String) {
        val codec = fields[name] ?: throw LuaApiException("$shape has no field $name")
        val top = call.lua.top
        codec.read(call, index, where)
        call.lua.setTop(top)
    }
}

/**
 * A codec named before it's built: a shape's own, inside a shape that holds
 * itself (`Subcommand.subcommands`), looked up when it's first used. [expected]
 * is known up front, since the codecs around it say it as they're built.
 */
class LuaLazy<T>(override val expected: String, resolve: () -> LuaCodec<T>) : LuaCodec<T> {
    private val codec by lazy(resolve)
    override val pinned get() = codec.pinned
    override fun accepts(call: LuaCall, index: Int) = codec.accepts(call, index)
    override fun read(call: LuaCall, index: Int, where: String): T = codec.read(call, index, where)
    override fun push(call: LuaCall, value: T) = codec.push(call, value)
}

/** A shape's table being read: each field by its codec. */
class ShapeReader(val call: LuaCall, private val index: Int, private val where: String) {
    fun <T> field(name: String, codec: LuaCodec<T>): T {
        val lua = call.lua
        call.host.field(lua, index, name)
        val value = codec.read(call, lua.top, "$where.$name")
        if (!codec.pinned) lua.pop(1)
        return value
    }
}

/** A shape's table being built, on top of the stack: each field set by its codec, absent when null. */
class ShapeWriter(val call: LuaCall) {
    fun <T> field(name: String, value: T, codec: LuaCodec<T>) {
        if (value == null) return
        call.lua.checkStack(2)
        codec.push(call, value)
        call.lua.setField(-2, name)
    }
}

/** A generated data class Lua sees as a plain table: what [LuaHost.pushValue] pushes it as. */
interface LuaShape {
    fun push(call: LuaCall)
}

/**
 * What the spec marks `since` a Minecraft version newer than this server's,
 * for the codecs: an option table's field set on such a server is an error
 * naming the version, as `nf`'s gated functions are in the prelude.
 */
class LuaGates(private val server: String, private val options: Map<String, Map<String, String>>) {
    /** The version field [name] of [shape] needs, if this server is older. */
    fun option(shape: String, name: String): String? = options[shape]?.get(name)

    fun needs(what: String, since: String) = "$what needs Minecraft $since (this server runs $server)"

    companion object {
        val NONE = LuaGates("", emptyMap())
    }
}

/**
 * A resource's name as the server knows it (`ruby`, `library:shop`), handed
 * to a script ([LuaHost.pushValue]) as the reading script's package writes it:
 * bare for its own, `ns:id` for another's ([LuaMarshal.spell]).
 */
@JvmInline
value class ResourceName(val name: String)

/**
 * What the codecs need from the runtime: items are checked against the
 * server and the project, and handed back in Lua spelling.
 */
interface LuaMarshal {
    /** The `Item` table at [index]; a misspelled field or an item the server doesn't have is a [LuaApiException]. */
    fun item(lua: Lua, index: Int): ItemData

    /** The `ItemMatch` table at [index]: a partial item. */
    fun match(lua: Lua, index: Int): ItemMatch

    fun pushItem(lua: Lua, item: ItemData)

    /** MiniMessage [text] a script wrote, its glyph tags as the server names the glyphs (`Text`, read). */
    fun text(text: String): String = text

    /** MiniMessage [text] as the server holds it, its glyph tags as the reading script's package writes them (`Text`, pushed). */
    fun spellText(text: String): String = text

    /** A name as the server knows it ([ResourceName]), as the reading script's package writes it. */
    fun spell(name: String): String = name

    /**
     * What [handle] is now, as the most specific class the runtime knows: an
     * `Entity` handed out while its entity was unloaded is the `Mob` it is once
     * it's back. The handle itself when there's nothing more to say.
     */
    fun refine(handle: LuaHandle): LuaHandle = handle

    companion object {
        /** For a host with no runtime (a test of the host itself): items can't cross. */
        val NONE = object : LuaMarshal {
            override fun item(lua: Lua, index: Int): ItemData = throw LuaApiException("items can't be read here")
            override fun match(lua: Lua, index: Int): ItemMatch = throw LuaApiException("items can't be read here")
            override fun pushItem(lua: Lua, item: ItemData) = throw LuaApiException("items can't be written here")
        }
    }
}
