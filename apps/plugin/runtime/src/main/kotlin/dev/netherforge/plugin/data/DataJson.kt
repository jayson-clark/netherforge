package dev.netherforge.plugin.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * [text] as JSON, or null when it isn't. Stricter than kotlinx's parser, which
 * takes a bare word (`nope`) as a literal: only `true`, `false`, `null` and
 * numbers go unquoted.
 */
internal fun parseJson(text: String): JsonElement? {
    val element = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return null
    fun valid(element: JsonElement): Boolean = when (element) {
        is JsonObject -> element.values.all(::valid)
        is JsonArray -> element.all(::valid)
        is JsonPrimitive -> element.isString || element is JsonNull || element.booleanOrNull != null || element.doubleOrNull != null
    }
    return element.takeIf(::valid)
}
