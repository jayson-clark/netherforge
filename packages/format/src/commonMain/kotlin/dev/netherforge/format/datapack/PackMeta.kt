package dev.netherforge.format.datapack

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * `datapacks/<id>/pack.mcmeta`: a datapack of the game's own, passed through into the start-up datapack. It's the
 * game's file, in the game's spelling: [pack] says which data pack formats the pack's files are written for, and
 * [overlays] which of its folders replace files of `data/` for other formats (the game's own way of making one pack
 * work on several versions). The files themselves are `data/<namespace>/worldgen/…` beside it, which format reads
 * as JSON but doesn't model ([dev.netherforge.format.datapack.DatapackKind]).
 */
@Serializable
data class PackMeta(@SerialName("\$schema") val schema: String? = null, val pack: PackSection, val overlays: PackOverlays? = null) {
    companion object {
        const val SCHEMA = "../../.netherforge/schema/datapack.schema.json"

        /** The file that makes a folder of `datapacks/` a datapack, as it does any datapack. */
        const val FILE_NAME = "pack.mcmeta"
    }
}

/** The pack's `pack` section. */
@Serializable
data class PackSection(
    /** What the game's pack list says about it: a text component. NetherForge only keeps it. */
    val description: JsonElement? = null,
    /** The oldest data pack format the pack's files are written for: a number (`94`), or `[major, minor]`. */
    @SerialName("min_format") val minFormat: JsonElement,
    /** The newest data pack format the pack's files are written for: a number (any minor of it), or `[major, minor]`. */
    @SerialName("max_format") val maxFormat: JsonElement
)

/** The pack's `overlays` section. */
@Serializable
data class PackOverlays(val entries: List<PackOverlay> = emptyList())

/**
 * A folder beside `data/` ([directory], holding a `data/` of its own) whose files replace the pack's, or add to them,
 * on a server whose data pack format is from [minFormat] to [maxFormat]. Later entries win over earlier ones.
 */
@Serializable
data class PackOverlay(
    @SerialName("min_format") val minFormat: JsonElement,
    @SerialName("max_format") val maxFormat: JsonElement,
    val directory: String
)

/** A data pack format, `major.minor`, as the game compares them. */
data class PackFormat(val major: Int, val minor: Int) : Comparable<PackFormat> {
    override fun compareTo(other: PackFormat): Int = compareValuesBy(this, other, { it.major }, { it.minor })

    override fun toString(): String = if (minor == Int.MAX_VALUE) "$major" else "$major.$minor"

    companion object {
        /** The server's format as `[major, minor]` (what `version.json` says and the start-up datapack is built for). */
        fun of(format: List<Int>): PackFormat = PackFormat(format.getOrElse(0) { 0 }, format.getOrElse(1) { 0 })

        /**
         * [element] as the lower ([upper] false) or upper end of a range, as the game reads `min_format` and
         * `max_format`: a whole number is that major format, from its first minor or to its last; `[major]` the
         * same; `[major, minor]` exactly that. Null when it's none of these.
         */
        fun read(element: JsonElement, upper: Boolean): PackFormat? {
            val parts = when (element) {
                is JsonPrimitive -> listOf(element)
                is JsonArray -> element.toList()
                else -> return null
            }
            if (parts.isEmpty() || parts.size > 2) return null
            val numbers = parts.map { part ->
                (part as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it >= 0 }
                    ?: return null
            }
            val minor = numbers.getOrNull(1) ?: if (upper) Int.MAX_VALUE else 0
            return PackFormat(numbers[0], minor)
        }

        /** The formats from [min] to [max] (as the pack writes them), or null when either end isn't one or they're the wrong way round. */
        fun range(min: JsonElement, max: JsonElement): ClosedRange<PackFormat>? {
            val from = read(min, upper = false) ?: return null
            val to = read(max, upper = true) ?: return null
            return if (from <= to) from..to else null
        }
    }
}
