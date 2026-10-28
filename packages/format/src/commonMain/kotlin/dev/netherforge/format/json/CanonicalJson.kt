package dev.netherforge.format.json

import dev.netherforge.format.Problem
import dev.netherforge.format.ProblemCodes
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive

/**
 * The one way NetherForge reads and writes project JSON.
 *
 * Reading is strict: an unknown key is an error naming where it was, not
 * something silently dropped on the next save. Writing is canonical: the same
 * model always produces the same bytes on every platform, so saving a file
 * nobody changed is never a diff.
 */
object CanonicalJson {

    val json: Json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        allowTrailingComma = false
        allowComments = false
        encodeDefaults = false
        explicitNulls = false
        classDiscriminator = "type"
    }

    sealed interface Parsed<out T> {
        data class Ok<T>(val value: T) : Parsed<T>
        data class Failed(val problem: Problem) : Parsed<Nothing>
    }

    fun <T> parse(serializer: KSerializer<T>, text: String, file: String): Parsed<T> = try {
        Parsed.Ok(json.decodeFromString(serializer, text))
    } catch (e: SerializationException) {
        Parsed.Failed(describe(e, text, file, serializer.descriptor))
    } catch (e: IllegalArgumentException) {
        // kotlinx reports some structural mismatches (a missing required field) this way.
        Parsed.Failed(describe(e, text, file, serializer.descriptor))
    }

    /** How [value] (an enum constant) is spelled in a file: its `@SerialName`. */
    inline fun <reified T : Enum<T>> serialName(value: T): String = json.encodeToJsonElement(value).jsonPrimitive.content

    /**
     * Writes [value] canonically. Every map is written in key order (see
     * [ordered]), whatever order it was built in, so no model needs to sort
     * its own maps and a new map field can't be forgotten. A [schema] replaces
     * the `$schema` the value has, when it has one.
     */
    fun <T> write(serializer: KSerializer<T>, value: T, schema: String? = null): String {
        var element = json.encodeToJsonElement(serializer, value)
        if (schema != null && element is JsonObject && SCHEMA_KEY in element) {
            element = JsonObject(element + (SCHEMA_KEY to JsonPrimitive(schema)))
        }
        return print(ordered(element, serializer.descriptor)) + "\n"
    }

    private const val SCHEMA_KEY = "\$schema"

    /**
     * [element] with the entries of every map put in canonical order, found by
     * walking it alongside [descriptor]. Class properties keep their declared
     * order and lists keep theirs: only maps are reordered.
     *
     * Enum keys sort in declaration order (a track's channels: translation,
     * rotation, scale). Other keys sort as numbers when they're whole numbers,
     * so slot "2" comes before "13", and otherwise as strings.
     */
    fun ordered(element: JsonElement, descriptor: SerialDescriptor): JsonElement {
        if (descriptor.isInline) return ordered(element, descriptor.getElementDescriptor(0))
        // Free-form JSON (an item's script data): every object in key order, however deep.
        if (descriptor.serialName.removeSuffix("?") == JSON_ELEMENT) return sortedJson(element)
        return when (descriptor.kind) {
            StructureKind.MAP -> {
                if (element !is JsonObject) return element
                val keys = descriptor.getElementDescriptor(0)
                val values = descriptor.getElementDescriptor(1)
                val comparator: Comparator<String> = if (keys.kind == SerialKind.ENUM) {
                    compareBy { keys.getElementIndex(it).let { index -> if (index < 0) Int.MAX_VALUE else index } }
                } else {
                    compareBy<String>({ it.toIntOrNull() ?: Int.MAX_VALUE }, { it })
                }
                JsonObject(
                    element.keys.sortedWith(comparator).associateWith { ordered(element.getValue(it), values) }
                )
            }
            StructureKind.LIST -> {
                if (element !is JsonArray) return element
                val items = descriptor.getElementDescriptor(0)
                JsonArray(element.map { ordered(it, items) })
            }
            StructureKind.CLASS, StructureKind.OBJECT -> {
                if (element !is JsonObject) return element
                JsonObject(
                    element.mapValues { (key, child) ->
                        val index = descriptor.getElementIndex(key)
                        if (index < 0) child else ordered(child, descriptor.getElementDescriptor(index))
                    }
                )
            }
            PolymorphicKind.SEALED -> {
                if (element !is JsonObject) return element
                val type = (element[json.configuration.classDiscriminator] as? JsonPrimitive)?.content ?: return element
                val subclasses = descriptor.getElementDescriptor(1)
                val index = subclasses.getElementIndex(type)
                if (index < 0) element else ordered(element, subclasses.getElementDescriptor(index))
            }
            else -> element
        }
    }

    private const val JSON_ELEMENT = "kotlinx.serialization.json.JsonElement"

    private fun sortedJson(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.keys.sorted().associateWith { sortedJson(element.getValue(it)) })
        is JsonArray -> JsonArray(element.map(::sortedJson))
        else -> element
    }

    /** Arrays of numbers, strings or booleans up to this long print on one line: vectors, short lore. */
    private const val INLINE_ARRAY = 60

    private fun inline(array: JsonArray) = array.joinToString(", ", "[", "]") { primitive(it as JsonPrimitive) }

    /** Pretty-prints with fixed rules: two-space indent, short primitive arrays on one line. */
    fun print(element: JsonElement): String = buildString { printTo(this, element, 0) }

    private fun printTo(out: StringBuilder, element: JsonElement, indent: Int) {
        when (element) {
            is JsonNull -> out.append("null")
            is JsonPrimitive -> out.append(primitive(element))
            is JsonArray -> {
                if (element.isEmpty()) {
                    out.append("[]")
                } else if (element.all { it is JsonPrimitive } && inline(element).length <= INLINE_ARRAY) {
                    out.append(inline(element))
                } else {
                    out.append("[\n")
                    element.forEachIndexed { index, child ->
                        pad(out, indent + 1)
                        printTo(out, child, indent + 1)
                        if (index < element.size - 1) out.append(',')
                        out.append('\n')
                    }
                    pad(out, indent)
                    out.append(']')
                }
            }
            is JsonObject -> {
                if (element.isEmpty()) {
                    out.append("{}")
                    return
                }
                out.append("{\n")
                val entries = element.entries.toList()
                entries.forEachIndexed { index, (key, child) ->
                    pad(out, indent + 1)
                    out.append(quote(key)).append(": ")
                    printTo(out, child, indent + 1)
                    if (index < entries.size - 1) out.append(',')
                    out.append('\n')
                }
                pad(out, indent)
                out.append('}')
            }
        }
    }

    private fun pad(out: StringBuilder, indent: Int) {
        repeat(indent) { out.append("  ") }
    }

    private fun primitive(value: JsonPrimitive): String = when {
        value.isString -> quote(value.content)
        value.content == "true" || value.content == "false" -> value.content
        else -> formatNumber(value.content)
    }

    /**
     * A number as the same text on the JVM and in JS.
     *
     * The platforms disagree about `Double.toString` (`1.0` vs `1`, `1.0E-5` vs
     * `0.00001`) but agree on the shortest digits that round-trip. So this
     * takes those digits and lays them out itself.
     */
    fun formatNumber(raw: String): String {
        val value = raw.toDoubleOrNull() ?: return raw
        if (value == 0.0) return "0"
        if (value.isNaN() || value.isInfinite()) return raw
        val negative = value < 0
        val text = (if (negative) -value else value).toString()

        // Split into significant digits and a decimal exponent, whatever the platform's layout.
        val mantissa: String
        var exponent: Int
        val e = text.indexOfFirst { it == 'e' || it == 'E' }
        if (e >= 0) {
            mantissa = text.substring(0, e)
            exponent = text.substring(e + 1).removePrefix("+").toInt()
        } else {
            mantissa = text
            exponent = 0
        }
        val dot = mantissa.indexOf('.')
        val intPart = if (dot >= 0) mantissa.substring(0, dot) else mantissa
        val fracPart = if (dot >= 0) mantissa.substring(dot + 1) else ""
        var digits = (intPart + fracPart).trimStart('0')
        // Position of the decimal point relative to the start of `digits`.
        val leadingZeros = (intPart + fracPart).length - (intPart + fracPart).trimStart('0').length
        var point = intPart.length - leadingZeros + exponent
        digits = digits.trimEnd('0')
        if (digits.isEmpty()) return "0"

        val body = when {
            point > 21 || point < -5 -> {
                exponent = point - 1
                val head = digits.substring(0, 1)
                val tail = digits.substring(1)
                (if (tail.isEmpty()) head else "$head.$tail") + "e" + (if (exponent >= 0) "+" else "") + exponent
            }
            point <= 0 -> "0." + "0".repeat(-point) + digits
            point >= digits.length -> digits + "0".repeat(point - digits.length)
            else -> digits.substring(0, point) + "." + digits.substring(point)
        }
        return if (negative) "-$body" else body
    }

    fun quote(text: String): String = buildString {
        append('"')
        for (c in text) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (c < ' ') append("\\u" + c.code.toString(16).padStart(4, '0')) else append(c)
            }
        }
        append('"')
    }

    private val IDENT = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    /** [parent] extended by [key]: `.key` when it's an identifier, `["key"]` otherwise. */
    fun childPath(parent: String, key: String) = if (IDENT.matches(key)) "$parent.$key" else "$parent[${quote(key)}]"

    /** kotlinx writes `$.nodes['root']`; we write `$.nodes.root`, and `["a b"]` when we must. */
    private fun normalizePath(path: String): String {
        var out = "$"
        Regex("""\.([A-Za-z_][A-Za-z0-9_]*)|\['([^']*)'\]|\[(\d+)\]""").findAll(path.removePrefix("$")).forEach { m ->
            val (dotted, quoted, index) = m.destructured
            out = when {
                dotted.isNotEmpty() -> "$out.$dotted"
                index.isNotEmpty() -> "$out[$index]"
                else -> childPath(out, quoted)
            }
        }
        return out
    }

    private val pathPattern = Regex("""at path:? (\$[^\s]*)""")
    private val offsetPattern = Regex("""at offset (\d+)""")
    private val unknownEnum = Regex("""(\S+) does not contain element with name '([^']*)'""")

    /** The descriptor named [serialName] anywhere under [root], sealed subclasses included. */
    private fun findDescriptor(root: SerialDescriptor, serialName: String, seen: MutableSet<String> = mutableSetOf()): SerialDescriptor? {
        val name = root.serialName.removeSuffix("?")
        if (name == serialName.removeSuffix("?")) return root
        if (!seen.add(name)) return null
        for (i in 0 until root.elementsCount) {
            findDescriptor(root.getElementDescriptor(i), serialName, seen)?.let { return it }
        }
        return null
    }

    /** Turns a kotlinx exception into a problem a person can act on. */
    private fun describe(e: Exception, text: String, file: String, root: SerialDescriptor): Problem {
        val raw = e.message ?: "Could not read this file"
        val path = pathPattern.find(raw)?.groupValues?.get(1)?.trimEnd('.')?.let { normalizePath(it) }
        val offset = offsetPattern.find(raw)?.groupValues?.get(1)?.toIntOrNull()
        var message = raw.lineSequence().first()
            .replace(Regex("""^Unexpected JSON token at offset \d+: """), "")
            .replace(pathPattern, "")
            .replace(Regex("""Encountered an unknown key '([^']+)'"""), "Unknown key \"$1\"")
            .replace(Regex("""\s+$"""), "")
            .trimEnd('.', ' ')
        unknownEnum.find(raw)?.let { match ->
            val (enumName, value) = match.destructured
            val allowed = findDescriptor(root, enumName)?.let { e -> (0 until e.elementsCount).map { "\"${e.getElementName(it)}\"" } }
            message = if (allowed == null) "\"$value\" isn't allowed here" else "\"$value\" isn't one of ${allowed.joinToString()}"
        }
        if (raw.contains("Field '") && raw.contains("is required")) {
            val field = Regex("""Field '([^']+)'""").find(raw)?.groupValues?.get(1)
            message = "Missing required key \"$field\""
        }
        var line: Int? = null
        var column: Int? = null
        if (offset != null && offset <= text.length) {
            line = 1
            var lineStart = 0
            for (i in 0 until offset) {
                if (text[i] == '\n') {
                    line++
                    lineStart = i + 1
                }
            }
            column = offset - lineStart + 1
        }
        return ProblemCodes.PARSE.at(file, message, path, line = line, column = column)
    }
}
