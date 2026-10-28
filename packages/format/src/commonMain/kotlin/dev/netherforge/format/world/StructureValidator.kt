package dev.netherforge.format.world

import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.ProblemSink
import dev.netherforge.format.game.GameData
import dev.netherforge.format.game.GameIds
import dev.netherforge.format.game.RegistryKey
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.project.Names
import dev.netherforge.format.ref.RefKind

/**
 * Checks where a structure generates ([StructureGeneration]) on its own:
 * its biomes (against the game's, with game data), its spacing and ranges
 * against the limits the game's own codecs hold them to, and its pools'
 * names. That a pool element's structure exists is the kind's cross check.
 */
object StructureValidator {
    fun validate(file: StructureGeneration, sink: ProblemSink, game: GameData?) {
        biomes(file.biomes, sink, game)
        spread(file, sink)
        file.depth?.let { range(it, 1..StructureGeneration.MAX_DEPTH, "depth", sink) }
        file.maxDistance?.let { range(it, 1..StructureGeneration.MAX_DISTANCE, "maxDistance", sink) }
        for ((name, pool) in file.pools) pool(name, pool, sink)
    }

    /** A biome list is ids, or one `#tag`: the game takes a tag only on its own. */
    private fun biomes(biomes: List<String>, sink: ProblemSink, game: GameData?) {
        if (biomes.isEmpty()) {
            sink.report(
                ProblemCodes.STRUCTURE_BIOMES,
                "A structure generates in at least one biome, or in a tag's: biomes is empty",
                "$.biomes"
            )
            return
        }
        val tagged = biomes.any { it.startsWith("#") }
        if (tagged && biomes.size > 1) {
            sink.report(ProblemCodes.STRUCTURE_BIOMES, "A tag (#…) stands alone in biomes: list biome ids, or name one tag", "$.biomes")
            return
        }
        biomes.forEachIndexed { index, text ->
            // The project's own biomes are references, which the loader checks; the game's are checked here.
            if (!RefKind.BIOME.isGame(text)) return@forEachIndexed
            val at = "$.biomes[$index]"
            val id = text.removePrefix("#")
            if (!GameIds.isValid(id)) {
                sink.report(
                    ProblemCodes.STRUCTURE_BIOMES,
                    "\"$text\" isn't a biome id, like \"minecraft:plains\", or a tag, like \"#minecraft:is_forest\"",
                    at
                )
                return@forEachIndexed
            }
            val normal = GameIds.normalize(id)
            val known = game?.registry(RegistryKey.BIOME) ?: return@forEachIndexed
            val missing = if (tagged) game.tag(RegistryKey.BIOME, normal) == null else normal !in known
            if (missing) {
                val noun = if (tagged) "tag" else "biome"
                sink.report(ProblemCodes.STRUCTURE_UNKNOWN_BIOME, "Minecraft ${game.minecraftVersion} has no $noun \"$text\"", at)
            }
        }
    }

    private fun spread(file: StructureGeneration, sink: ProblemSink) {
        val spacing = file.spacing ?: StructureGeneration.DEFAULT_SPACING
        val separation = file.separation ?: StructureGeneration.DEFAULT_SEPARATION
        val most = StructureGeneration.MAX_SPACING
        if (file.spacing != null && file.spacing !in 1..most) {
            sink.report(ProblemCodes.STRUCTURE_SPREAD, "spacing is from 1 to $most chunks, not ${file.spacing}", "$.spacing")
        } else if (file.separation != null && file.separation !in 0 until most) {
            sink.report(ProblemCodes.STRUCTURE_SPREAD, "separation is from 0 to ${most - 1} chunks, not ${file.separation}", "$.separation")
        } else if (separation >= spacing) {
            sink.report(
                ProblemCodes.STRUCTURE_SPREAD,
                "separation ($separation) must be below spacing ($spacing): it's the gap kept inside each square",
                if (file.separation != null) "$.separation" else "$.spacing"
            )
        }
        if (file.salt != null && file.salt < 0) {
            sink.report(ProblemCodes.STRUCTURE_SPREAD, "salt can't be negative", "$.salt")
        }
    }

    private fun range(value: Int, allowed: IntRange, name: String, sink: ProblemSink) {
        if (value !in allowed) {
            sink.report(ProblemCodes.STRUCTURE_RANGE, "$name is from ${allowed.first} to ${allowed.last}, not $value", "$.$name")
        }
    }

    private fun pool(name: String, pool: StructurePool, sink: ProblemSink) {
        val at = CanonicalJson.childPath("$.pools", name)
        if (!Names.isId(name)) {
            sink.report(ProblemCodes.STRUCTURE_POOL, "\"$name\" isn't a usable pool name (${Names.ID_RULE})", at)
        } else if (name == StructureGeneration.START_POOL) {
            sink.report(
                ProblemCodes.STRUCTURE_POOL,
                "\"${StructureGeneration.START_POOL}\" is the pool the structure's own template starts from: name this one something else",
                at
            )
        }
        if (pool.elements.isEmpty()) {
            sink.report(ProblemCodes.STRUCTURE_POOL, "A pool picks from at least one structure", "$at.elements")
        }
        pool.elements.forEachIndexed { index, element ->
            val weight = element.weight ?: return@forEachIndexed
            if (weight !in 1..StructureGeneration.MAX_WEIGHT) {
                sink.report(
                    ProblemCodes.STRUCTURE_RANGE,
                    "weight is from 1 to ${StructureGeneration.MAX_WEIGHT}, not $weight",
                    "$at.elements[$index].weight"
                )
            }
        }
    }
}
