package dev.netherforge.plugin.lua

import dev.netherforge.plugin.binding.LuaHandle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import party.iroiro.luajava.Lua
import kotlin.math.ln

/**
 * Lua values read as JSON straight off the stack when they're plain data, and JSON pushed as plain Lua values:
 * what [LuaHost]'s codec does without running the prelude's (`host.json_encode`, `host.json_decode`), so plain
 * data costs a script nothing. Anything the codec has more to say about (a typed value it can't tag, a key with
 * "$", a metatable) is null here and goes through the codec.
 */
internal class PlainJson(private val host: LuaHost, private val savedHandles: Map<String, String>) {
    /**
     * The value at [index] as JSON when it's plain data: a string, a boolean,
     * a finite number, or a table without a metatable that is a list numbered
     * from 1 without holes or an object keyed by strings none of which starts
     * with "$", of plain data in turn. Null for anything else, which only the
     * codec writes (or reports) right.
     *
     * [exact] is for JSON whose text is seen, which must be the codec's
     * very text: its objects' keys in the codec's order (Lua's own `<`, which
     * is the C library's collation, asked of Lua) and ASCII (so they're the
     * same string back in Lua to be compared), its floats spelled as the
     * codec spells them ([CodecNumbers]).
     */
    fun value(lua: Lua, index: Int, depth: Int, exact: Boolean): JsonElement? = when (lua.type(index)) {
        Lua.LuaType.STRING -> JsonPrimitive(lua.toString(index))
        Lua.LuaType.BOOLEAN -> JsonPrimitive(lua.toBoolean(index))
        Lua.LuaType.NUMBER -> when {
            lua.isInteger(index) -> JsonPrimitive(lua.toInteger(index))
            // Parsed from the codec's text, so its content is that text.
            exact -> CodecNumbers.json(lua.toNumber(index))?.let(Json::parseToJsonElement)
            else -> lua.toNumber(index).takeIf { it.isFinite() }?.let { JsonPrimitive(it) }
        }
        Lua.LuaType.TABLE -> table(lua, if (index < 0) lua.top + index + 1 else index, depth, exact)
        else -> null
    }

    /** A table of plain data ([value]), its keys in [skip] left out when it's an object; null when it isn't one. */
    fun table(lua: Lua, at: Int, depth: Int, exact: Boolean, skip: Set<String> = emptySet()): JsonElement? {
        if (depth >= MAX_PLAIN_DEPTH) return null
        if (lua.getMetatable(at) != 0) {
            lua.pop(1)
            return if (exact && skip.isEmpty()) typedJson(lua, at) else null
        }
        // A list has a first item; a table without one is an object (the empty table too, as the codec has it).
        lua.push(1L)
        lua.rawGet(at)
        val list = !lua.isNil(-1)
        lua.pop(1)
        if (!list) return fields(lua, at, depth, exact, skip)?.let(::JsonObject)
        // The codec leaves [skip]'s keys out of whatever table it's given; a list with them is its to write.
        if (skip.isNotEmpty()) return null
        val items = HashMap<Long, JsonElement>()
        lua.checkStack(3)
        lua.pushNil()
        while (lua.next(at) != 0) {
            host.meter(1)
            val position = if (lua.type(-2) == Lua.LuaType.NUMBER && lua.isInteger(-2)) lua.toInteger(-2) else 0
            val item = if (position > 0) value(lua, -1, depth + 1, exact) else null
            if (item == null) {
                lua.pop(2)
                return null
            }
            items[position] = item
            lua.pop(1)
        }
        // As many positions as the highest of them: no holes.
        return JsonArray(List(items.size) { items[it + 1L] ?: return null })
    }

    /**
     * A typed value as the JSON codec tags it: `{"$vec3":[x,y,z]}`,
     * `{"$location":[world,x,y,z,yaw,pitch]}` (the world by its name, a facing
     * left out as null), and a handle whose class (or one up its chain) a saved
     * table may keep as `{"$<tag>":[key]}`. Null for anything else (a handle
     * nothing may keep, a vector holding NaN): the codec says what's wrong.
     */
    private fun typedJson(lua: Lua, at: Int): JsonElement? {
        fun numbers(table: Int, count: Int, from: Int = 1): List<JsonElement>? = (from until from + count).map { i ->
            lua.rawGetI(table, i)
            val number = when {
                lua.isNil(-1) -> JsonNull
                lua.isInteger(-1) -> JsonPrimitive(lua.toInteger(-1))
                else -> CodecNumbers.json(lua.toNumber(-1))?.let(Json::parseToJsonElement)
            }
            lua.pop(1)
            number ?: return null
        }

        fun tagged(tag: String, parts: List<JsonElement>) = JsonObject(mapOf(tag to JsonArray(parts)))

        // A handle the runtime no longer knows is the codec's to report.
        fun keyOf(index: Int): List<Any>? = try {
            host.handleAt(lua, index).keyValues
        } catch (_: LuaApiException) {
            null
        }
        return when (val kind = host.kindAt(lua, at)) {
            null -> null
            "Vec3" -> numbers(at, 3)?.let { tagged("\$vec3", it) }
            "Location" -> {
                lua.checkStack(3)
                lua.rawGetI(at, 1)
                val world = keyOf(-1)?.first()?.toString()
                lua.pop(1)
                lua.rawGetI(at, 2)
                val position = numbers(lua.top, 3)
                lua.pop(1)
                val facing = numbers(at, 2, from = 3)
                if (world == null || position == null || facing == null) {
                    null
                } else {
                    tagged("\$location", listOf(JsonPrimitive(world)) + position + facing)
                }
            }
            else -> {
                var name: String? = kind
                while (name != null && name !in savedHandles) name = LuaHandle.PARENTS[name]
                val tag = name?.let(savedHandles::get) ?: return null
                val key = (keyOf(at) ?: return null).map { part ->
                    when (part) {
                        is String -> JsonPrimitive(part)
                        is Long -> JsonPrimitive(part)
                        else -> return null
                    }
                }
                tagged("\$$tag", key)
            }
        }
    }

    /** The table at [at] as an object of plain data ([value]), its keys in [skip] left out; null when it isn't one. */
    fun fields(lua: Lua, at: Int, depth: Int, exact: Boolean, skip: Set<String>): Map<String, JsonElement>? {
        if (lua.type(at) != Lua.LuaType.TABLE) return null
        if (depth == 0 && lua.getMetatable(at) != 0) {
            lua.pop(1)
            return null
        }
        val fields = HashMap<String, JsonElement>()
        lua.checkStack(3)
        lua.pushNil()
        while (lua.next(at) != 0) {
            host.meter(1)
            val key = if (lua.type(-2) == Lua.LuaType.STRING) lua.toString(-2) else null
            if (key != null && key in skip) {
                lua.pop(1)
                continue
            }
            val usable = key != null && !key.startsWith("$") && (!exact || key.all { it.code < 0x80 })
            val value = if (usable) value(lua, -1, depth + 1, exact) else null
            if (value == null) {
                lua.pop(2)
                return null
            }
            fields[key!!] = value
            lua.pop(1)
        }
        if (!exact || fields.size < 2) return fields
        val keys = fields.keys.toMutableList()
        if (keys.size > SORT_FREE) {
            // n log2 n of Lua's `<`, each a strcoll in C: charged, as the Lua codec's sort is.
            val compares = keys.size * ln(keys.size.toDouble()) / ln(2.0)
            host.charge(compares.toLong(), compares * STEPS_PER_STRING_COMPARE)
        }
        var tied = false
        lua.checkStack(2)
        keys.sortWith { a, b ->
            when {
                a == b -> 0
                luaLess(lua, a, b) -> -1
                luaLess(lua, b, a) -> 1
                else -> {
                    tied = true
                    0
                }
            }
        }
        // Two keys Lua can't tell apart: whichever order its sort leaves them in is its own.
        if (tied) return null
        return keys.associateWithTo(LinkedHashMap()) { fields.getValue(it) }
    }

    /** Whether Lua's `<` puts [a] before [b], as `table.sort` would. */
    private fun luaLess(lua: Lua, a: String, b: String): Boolean {
        lua.push(a)
        lua.push(b)
        val less = lua.lessThan(-2, -1)
        lua.pop(2)
        return less
    }

    companion object {
        /** Whether [element] has a key starting with "$" anywhere: a tagged value, or a key of a script's written with one more "$". */
        fun tagged(element: JsonElement): Boolean = when (element) {
            is JsonObject -> element.any { (key, value) -> key.startsWith("$") || tagged(value) }
            is JsonArray -> element.any(::tagged)
            else -> false
        }

        /** JSON as plain Lua values, for `host.json_decode` to type: objects and arrays as tables. */
        fun push(lua: Lua, element: JsonElement) {
            lua.checkStack(3)
            when (element) {
                is JsonNull -> lua.pushNil()
                is JsonPrimitive -> when {
                    element.isString -> lua.push(element.content)
                    element.booleanOrNull != null -> lua.push(element.booleanOrNull!!)
                    element.longOrNull != null -> lua.push(element.longOrNull!!)
                    else -> lua.push((element.doubleOrNull ?: 0.0) as Number)
                }
                is JsonArray -> {
                    lua.createTable(element.size, 0)
                    element.forEachIndexed { k, item ->
                        push(lua, item)
                        lua.rawSetI(-2, k + 1)
                    }
                }
                is JsonObject -> {
                    lua.createTable(0, element.size)
                    for ((key, item) in element) {
                        push(lua, item)
                        lua.setField(-2, key)
                    }
                }
            }
        }

        private const val MAX_PLAIN_DEPTH = 64

        /** Keys sorted free of charge: what an ordinary object has. Above it, as the codec's sort ([fields]). */
        private const val SORT_FREE = 64
        private const val STEPS_PER_STRING_COMPARE = 150.0
    }
}
