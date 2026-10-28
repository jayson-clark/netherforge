package dev.netherforge.format.datapack

import dev.netherforge.format.json.CanonicalJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Where the game's worldgen JSON names other registry entries by id, for the few places that are the same in every
 * supported version: a placed feature's feature, a biome's features and carvers, a structure's biomes and start pool,
 * a structure set's structures, a pool's fallback, processors and features, a world preset's dimension types, noise
 * settings and biomes, a flat preset's biome and structure sets, a noise settings' density functions (its noise
 * router's) and material rule (26.3's), and the noises density functions and noise settings sample.
 * Everything else in a file (a density function's arguments, a feature's configuration) is the server's to check.
 *
 * A place names an entry of the first of its [Rule.targets] the game has: registries are sometimes renamed between
 * versions (a configured feature is `worldgen/configured_feature` up to 26.2 and `worldgen/feature` in 26.3), so a
 * rule lists every name its registry has had. The contract test runs these rules over each supported server's own
 * worldgen files with its own registries, so a rule that's wrong for a version fails there.
 */
object WorldgenReferences {
    /**
     * In a file of [registry], the value at [pattern] (keys, `*` for every element or value, `**` for any depth)
     * names entries of [targets]: a string (`#` before a tag), or a list of them. An object there is an entry
     * written in place, which names nothing by itself.
     */
    class Rule(val registry: String, val pattern: List<String>, val targets: List<String>)

    /** A reference a file makes: at [path] (a JSON path), [text] as written, an entry of the first of [targets] the game has. */
    data class Found(val path: String, val text: String, val targets: List<String>)

    private const val ANY = "*"
    private const val DEEP = "**"

    private const val BIOME = "worldgen/biome"
    private const val PLACED_FEATURE = "worldgen/placed_feature"
    private val CONFIGURED_FEATURE = listOf("worldgen/configured_feature", "worldgen/feature")
    private val CONFIGURED_CARVER = listOf("worldgen/configured_carver", "worldgen/carver")
    private const val STRUCTURE = "worldgen/structure"
    private const val STRUCTURE_SET = "worldgen/structure_set"
    private const val TEMPLATE_POOL = "worldgen/template_pool"
    private const val PROCESSOR_LIST = "worldgen/processor_list"
    private const val NOISE = "worldgen/noise"
    private const val NOISE_SETTINGS = "worldgen/noise_settings"
    private const val DENSITY_FUNCTION = "worldgen/density_function"
    private const val PARAMETER_LIST = "worldgen/multi_noise_biome_source_parameter_list"
    private const val WORLD_PRESET = "worldgen/world_preset"
    private const val FLAT_PRESET = "worldgen/flat_level_generator_preset"
    private const val MATERIAL_RULE = "worldgen/material_rule"
    private const val DIMENSION_TYPE = "dimension_type"

    private fun rule(registry: String, pattern: String, vararg targets: String) = Rule(registry, pattern.split('.'), targets.toList())

    private fun rule(registry: String, pattern: String, targets: List<String>) = Rule(registry, pattern.split('.'), targets)

    val RULES: List<Rule> = listOf(
        rule(PLACED_FEATURE, "feature", CONFIGURED_FEATURE),
        rule(BIOME, "features.*", PLACED_FEATURE),
        rule(BIOME, "carvers", CONFIGURED_CARVER),
        rule(STRUCTURE, "biomes", BIOME),
        rule(STRUCTURE, "start_pool", TEMPLATE_POOL),
        rule(STRUCTURE_SET, "structures.*.structure", STRUCTURE),
        rule(TEMPLATE_POOL, "fallback", TEMPLATE_POOL),
        rule(TEMPLATE_POOL, "elements.*.element.processors", PROCESSOR_LIST),
        rule(TEMPLATE_POOL, "elements.*.element.feature", PLACED_FEATURE),
        rule(WORLD_PRESET, "dimensions.*.type", DIMENSION_TYPE),
        rule(WORLD_PRESET, "dimensions.*.generator.settings", NOISE_SETTINGS),
        rule(WORLD_PRESET, "dimensions.*.generator.biome_source.preset", PARAMETER_LIST),
        rule(WORLD_PRESET, "dimensions.*.generator.biome_source.biome", BIOME),
        rule(WORLD_PRESET, "dimensions.*.generator.biome_source.biomes.*.biome", BIOME),
        rule(FLAT_PRESET, "settings.biome", BIOME),
        rule(FLAT_PRESET, "settings.structure_overrides", STRUCTURE_SET),
        rule(NOISE_SETTINGS, "noise_router.*", DENSITY_FUNCTION),
        rule(NOISE_SETTINGS, "material_rule", MATERIAL_RULE),
        rule(NOISE_SETTINGS, "**.noise", NOISE),
        rule(DENSITY_FUNCTION, "**.noise", NOISE)
    )

    private val byRegistry = RULES.groupBy { it.registry }

    /** Every reference [element], an entry of [registry], makes where the [RULES] look, in document order per rule. */
    fun find(registry: String, element: JsonElement): List<Found> {
        val out = mutableListOf<Found>()
        for (rule in byRegistry[registry].orEmpty()) match(element, rule, 0, "$", out)
        return out.distinct()
    }

    /** The references a tag file's [element] makes: its values, ids or `#tags` of its own registry, [registry]. */
    fun tagValues(registry: String, element: JsonElement): List<Found>? {
        val values = (element as? JsonObject)?.get("values") as? JsonArray ?: return null
        val replace = element["replace"]
        if (replace != null && (replace !is JsonPrimitive || replace.isString || replace.content !in setOf("true", "false"))) return null
        if (element.keys.any { it != "values" && it != "replace" }) return null
        return values.mapIndexed { index, value ->
            val at = "$.values[$index]"
            when {
                value is JsonPrimitive && value.isString -> Found(at, value.content, listOf(registry))
                value is JsonObject -> {
                    val id = value["id"] as? JsonPrimitive ?: return null
                    if (!id.isString || value.keys.any { it != "id" && it != "required" }) return null
                    Found("$at.id", id.content, listOf(registry))
                }
                else -> return null
            }
        }
    }

    private fun match(element: JsonElement, rule: Rule, at: Int, path: String, out: MutableList<Found>) {
        if (at == rule.pattern.size) return collect(element, rule, path, out)
        when (val segment = rule.pattern[at]) {
            ANY -> children(element, path) { child, childPath -> match(child, rule, at + 1, childPath, out) }
            DEEP -> {
                match(element, rule, at + 1, path, out)
                children(element, path) { child, childPath -> match(child, rule, at, childPath, out) }
            }
            else -> (element as? JsonObject)?.get(segment)?.let { match(it, rule, at + 1, CanonicalJson.childPath(path, segment), out) }
        }
    }

    private inline fun children(element: JsonElement, path: String, each: (JsonElement, String) -> Unit) {
        when (element) {
            is JsonArray -> element.forEachIndexed { index, child -> each(child, "$path[$index]") }
            is JsonObject -> for ((key, child) in element) each(child, CanonicalJson.childPath(path, key))
            else -> {}
        }
    }

    private fun collect(element: JsonElement, rule: Rule, path: String, out: MutableList<Found>) {
        when {
            element is JsonPrimitive && element.isString -> out += Found(path, element.content, rule.targets)
            element is JsonArray -> element.forEachIndexed { index, item ->
                if (item is JsonPrimitive && item.isString) out += Found("$path[$index]", item.content, rule.targets)
            }
            else -> {}
        }
    }
}
