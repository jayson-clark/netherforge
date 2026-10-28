package dev.netherforge.format.dimensiontype

import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.ProblemSink
import dev.netherforge.format.game.GameData
import dev.netherforge.format.game.GameIds
import dev.netherforge.format.game.RegistryKey

/**
 * Checks a dimension type on its own: the game's own rules for its heights
 * (which it would refuse the whole datapack over: sections of 16, inside
 * -2032 to 2032, a logical height no taller than the height), its light
 * levels, colours and numbers, and, with game data, that its infiniburn tag
 * is one the game has.
 */
object DimensionTypeValidator {
    fun validate(file: DimensionTypeFile, sink: ProblemSink, game: GameData?) {
        heights(file, sink)
        light(file, sink)
        file.colors?.let { colors ->
            colors.sky?.let { color(it, DimensionTypeColors.COLOR, "$.colors.sky", "#rrggbb", sink) }
            colors.fog?.let { color(it, DimensionTypeColors.COLOR, "$.colors.fog", "#rrggbb", sink) }
            colors.clouds?.let { color(it, DimensionTypeColors.CLOUD_COLOR, "$.colors.clouds", "#rrggbb or #aarrggbb", sink) }
        }
        file.cloudHeight?.let {
            if (!it.isFinite() || it < DimensionTypeFile.LOWEST_Y || it > DimensionTypeFile.TOP_Y) {
                sink.report(
                    ProblemCodes.DIMENSION_TYPE_NUMBER,
                    "cloudHeight is from ${DimensionTypeFile.LOWEST_Y} to ${DimensionTypeFile.TOP_Y}",
                    "$.cloudHeight"
                )
            }
        }
        file.coordinateScale?.let {
            if (!it.isFinite() || it < DimensionTypeFile.MIN_COORDINATE_SCALE || it > DimensionTypeFile.MAX_COORDINATE_SCALE) {
                sink.report(ProblemCodes.DIMENSION_TYPE_NUMBER, "coordinateScale is from 0.00001 to 30000000", "$.coordinateScale")
            }
        }
        file.infiniburn?.let { infiniburn(it, sink, game) }
    }

    private fun heights(file: DimensionTypeFile, sink: ProblemSink) {
        val section = DimensionTypeFile.SECTION
        file.minY?.let {
            if (it % section != 0) {
                sink.report(ProblemCodes.DIMENSION_TYPE_HEIGHT, "minY must be a multiple of $section, not $it", "$.minY")
            } else if (it < DimensionTypeFile.LOWEST_Y) {
                sink.report(
                    ProblemCodes.DIMENSION_TYPE_HEIGHT,
                    "minY can't be below ${DimensionTypeFile.LOWEST_Y}, the lowest any world goes",
                    "$.minY"
                )
            }
        }
        file.height?.let {
            if (it < section || it % section != 0) {
                sink.report(
                    ProblemCodes.DIMENSION_TYPE_HEIGHT,
                    "height must be a multiple of $section, at least $section, not $it",
                    "$.height"
                )
            }
        }
        val top = file.minYOrDefault.toLong() + file.heightOrDefault
        if (top > DimensionTypeFile.TOP_Y) {
            sink.report(
                ProblemCodes.DIMENSION_TYPE_HEIGHT,
                "minY + height is $top: the top of a world can be at most ${DimensionTypeFile.TOP_Y}",
                if (file.height != null) "$.height" else "$.minY"
            )
        }
        file.logicalHeight?.let {
            if (it < 0 || it > file.heightOrDefault) {
                sink.report(
                    ProblemCodes.DIMENSION_TYPE_LOGICAL_HEIGHT,
                    "logicalHeight is from 0 to the height (${file.heightOrDefault}), not $it",
                    "$.logicalHeight"
                )
            }
        }
    }

    private fun light(file: DimensionTypeFile, sink: ProblemSink) {
        file.ambientLight?.let {
            if (!it.isFinite() || it !in 0.0..1.0) {
                sink.report(ProblemCodes.DIMENSION_TYPE_LIGHT, "ambientLight is from 0 to 1", "$.ambientLight")
            }
        }
        val max = DimensionTypeFile.MAX_LIGHT
        file.monsterSpawnLight?.let { range ->
            for ((value, key) in listOf(range.min to "min", range.max to "max")) {
                if (value != null && value !in 0..max) {
                    sink.report(
                        ProblemCodes.DIMENSION_TYPE_LIGHT,
                        "a light level is from 0 to $max, not $value",
                        "$.monsterSpawnLight.$key"
                    )
                }
            }
            if (file.monsterSpawnLightMinOrDefault > file.monsterSpawnLightMaxOrDefault) {
                sink.report(
                    ProblemCodes.DIMENSION_TYPE_LIGHT,
                    "min (${file.monsterSpawnLightMinOrDefault}) is above max (${file.monsterSpawnLightMaxOrDefault})",
                    "$.monsterSpawnLight.min"
                )
            }
        }
        file.monsterSpawnBlockLight?.let {
            if (it !in 0..max) {
                sink.report(ProblemCodes.DIMENSION_TYPE_LIGHT, "a light level is from 0 to $max, not $it", "$.monsterSpawnBlockLight")
            }
        }
    }

    private fun color(text: String, pattern: Regex, at: String, shape: String, sink: ProblemSink) {
        if (!pattern.matches(text)) sink.report(ProblemCodes.DIMENSION_TYPE_COLOR, "\"$text\" isn't a colour written $shape", at)
    }

    private fun infiniburn(text: String, sink: ProblemSink, game: GameData?) {
        val id = text.removePrefix("#")
        if (!text.startsWith("#") || !GameIds.isValid(id)) {
            sink.report(ProblemCodes.DIMENSION_TYPE_INFINIBURN, "\"$text\" isn't a block tag, written #namespace:path", "$.infiniburn")
            return
        }
        if (game?.registry(RegistryKey.BLOCK) == null) return
        if (game.tag(RegistryKey.BLOCK, GameIds.normalize(id)) == null) {
            sink.report(
                ProblemCodes.DIMENSION_TYPE_INFINIBURN_TAG,
                "Minecraft ${game.minecraftVersion} has no block tag \"$text\"",
                "$.infiniburn"
            )
        }
    }
}
