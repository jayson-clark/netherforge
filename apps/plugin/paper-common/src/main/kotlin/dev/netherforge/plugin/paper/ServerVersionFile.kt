package dev.netherforge.plugin.paper

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * The server jar's own `version.json`, which says which resource and data
 * pack formats it reads, so a new version needs no table. Read through a
 * class of the server's (its class loader has the jar), which works before
 * the server is up, as the start-up datapack needs.
 */
internal object ServerVersionFile {
    private val packVersion: JsonElement? by lazy {
        runCatching {
            val text = io.papermc.paper.plugin.bootstrap.BootstrapContext::class.java.classLoader
                .getResourceAsStream("version.json")
                ?.use { it.readBytes().decodeToString() }
                ?: return@runCatching null
            (Json.parseToJsonElement(text) as? JsonObject)?.get("pack_version")
        }.getOrNull()
    }

    /** The resource pack format as `[major, minor]`, or null when the jar doesn't say. */
    val resourcePackFormat: List<Int>? get() = format("resource")

    /** The data pack format as `[major, minor]`, or null when the jar doesn't say. */
    val dataPackFormat: List<Int>? get() = format("data")

    /**
     * `pack_version.<kind>_major` and `<kind>_minor` (from 1.21.9); older
     * jars said `pack_version.<kind>`, or a bare number for the resource pack.
     */
    private fun format(kind: String): List<Int>? = runCatching {
        when (val pack = packVersion) {
            null -> null
            is JsonPrimitive -> if (kind == "resource") listOf(pack.int) else null
            is JsonObject -> pack["${kind}_major"]?.let { major ->
                listOf(major.jsonPrimitive.int, pack["${kind}_minor"]?.jsonPrimitive?.int ?: 0)
            } ?: pack[kind]?.jsonPrimitive?.int?.let { listOf(it) }
            else -> null
        }
    }.getOrNull()
}
