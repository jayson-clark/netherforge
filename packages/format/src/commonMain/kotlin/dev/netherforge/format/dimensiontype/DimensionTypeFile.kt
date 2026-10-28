package dev.netherforge.format.dimensiontype

import dev.netherforge.format.terrain.WorldHeight
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `dimension_types/<id>.json`: one of the project's dimension types, which the
 * game knows as `<namespace>:<id>`: a world's build limits (its bottom and
 * height), its light and sky, and the game's rules that differ between the
 * overworld, the nether and the end (whether beds and respawn anchors work,
 * whether piglins are safe, raids). A world names one when it's made
 * (`nf.worlds.create`, `netherforge.json`'s `worlds.<name>.dimension`, the
 * main world too), and keeps it.
 *
 * Every field is optional: absent, it's what the overworld has (the
 * `...OrDefault` accessors). The game reads dimension types only as it
 * starts, from the start-up datapack ([DimensionTypeJson] writes the game's
 * format), so a change needs a restart.
 */
@Serializable
data class DimensionTypeFile(
    @SerialName("\$schema") val schema: String? = null,
    /** The lowest block a world can hold: a multiple of 16, from -2032. Default -64. */
    val minY: Int? = null,
    /** How many blocks tall from [minY]: a multiple of 16, at least 16, its top at most 2032. Default 384. */
    val height: Int? = null,
    /**
     * How high above [minY] a nether portal, a chorus fruit's teleport or a
     * player sent there can take anyone: at most [height]. Default [height].
     */
    val logicalHeight: Int? = null,
    /** Whether the sky lights it (there's daylight and dark). Default true. */
    val skyLight: Boolean? = null,
    /** Whether it has a ceiling of bedrock overhead, as the nether: maps and weather know. Default false. */
    val ceiling: Boolean? = null,
    /** How bright it is with no light at all, 0 to 1 (the nether's is 0.1). Default 0. */
    val ambientLight: Double? = null,
    /** Whether the sun stands still: it's never day or night, so beds don't skip the night and the moon doesn't change. Default false. */
    val fixedTime: Boolean? = null,
    /** What's drawn overhead: the overworld's sky, the end's, or none (the nether's). Default `overworld`. */
    val sky: DimensionTypeSky? = null,
    /** The colours of its sky, fog and clouds, where its biomes don't say their own. Default the overworld's. */
    val colors: DimensionTypeColors? = null,
    /** The height clouds float at. Default 192.33, the overworld's. */
    val cloudHeight: Double? = null,
    /** Whether players can sleep in a bed and set their spawn with one; when not, a bed blows up when used. Default true. */
    val bedWorks: Boolean? = null,
    /** Whether respawn anchors set a spawn; when not, one blows up when used. Default false. */
    val respawnAnchorWorks: Boolean? = null,
    /** Whether piglins and hoglins stay as they are; when not, they turn into zombified ones. Default false. */
    val piglinSafe: Boolean? = null,
    /** Whether a player with Bad Omen can start a raid. Default true. */
    val raids: Boolean? = null,
    /** Whether it's as hot as the nether: water evaporates, lava flows fast and far, snow golems melt. Default false. */
    val ultrawarm: Boolean? = null,
    /** The light levels (sky and block together) monsters may spawn at, each 0 to 15: one picked between them for each try. Default 0 to 7. */
    val monsterSpawnLight: LightRange? = null,
    /** The most block light monsters may spawn in, 0 to 15. Default 0. */
    val monsterSpawnBlockLight: Int? = null,
    /** The block tag (`#minecraft:infiniburn_overworld`) of the blocks fire on top of never goes out. Default the overworld's. */
    val infiniburn: String? = null,
    /** How far a step here goes in the overworld through a nether portal (the nether's is 8). Default 1. */
    val coordinateScale: Double? = null
) {
    val minYOrDefault: Int get() = minY ?: DEFAULT_MIN_Y
    val heightOrDefault: Int get() = height ?: DEFAULT_HEIGHT
    val logicalHeightOrDefault: Int get() = logicalHeight ?: heightOrDefault
    val skyLightOrDefault: Boolean get() = skyLight ?: true
    val ceilingOrDefault: Boolean get() = ceiling ?: false
    val ambientLightOrDefault: Double get() = ambientLight ?: 0.0
    val fixedTimeOrDefault: Boolean get() = fixedTime ?: false
    val skyOrDefault: DimensionTypeSky get() = sky ?: DimensionTypeSky.OVERWORLD
    val cloudHeightOrDefault: Double get() = cloudHeight ?: DEFAULT_CLOUD_HEIGHT
    val bedWorksOrDefault: Boolean get() = bedWorks ?: true
    val respawnAnchorWorksOrDefault: Boolean get() = respawnAnchorWorks ?: false
    val piglinSafeOrDefault: Boolean get() = piglinSafe ?: false
    val raidsOrDefault: Boolean get() = raids ?: true
    val ultrawarmOrDefault: Boolean get() = ultrawarm ?: false
    val monsterSpawnLightMinOrDefault: Int get() = monsterSpawnLight?.min ?: DEFAULT_MONSTER_LIGHT_MIN
    val monsterSpawnLightMaxOrDefault: Int get() = monsterSpawnLight?.max ?: DEFAULT_MONSTER_LIGHT_MAX
    val monsterSpawnBlockLightOrDefault: Int get() = monsterSpawnBlockLight ?: 0
    val infiniburnOrDefault: String get() = infiniburn ?: DEFAULT_INFINIBURN
    val coordinateScaleOrDefault: Double get() = coordinateScale ?: 1.0

    /** The heights a world of this type has: what a generator and the editor's preview are bound to. */
    val worldHeight: WorldHeight get() = WorldHeight(minYOrDefault, minYOrDefault + heightOrDefault)

    companion object {
        const val SCHEMA = "../.netherforge/schema/dimension_type.schema.json"

        const val DEFAULT_MIN_Y = -64
        const val DEFAULT_HEIGHT = 384

        /** The game's bounds: every world's blocks are within -2032 to 2031, in sections of 16. */
        const val LOWEST_Y = -2032
        const val TOP_Y = 2032
        const val SECTION = 16

        const val DEFAULT_CLOUD_HEIGHT = 192.33
        const val DEFAULT_MONSTER_LIGHT_MIN = 0
        const val DEFAULT_MONSTER_LIGHT_MAX = 7
        const val MAX_LIGHT = 15
        const val DEFAULT_INFINIBURN = "#minecraft:infiniburn_overworld"
        const val MIN_COORDINATE_SCALE = 0.00001
        const val MAX_COORDINATE_SCALE = 30_000_000.0
    }
}

/** What's drawn overhead (the game's `skybox`). */
@Serializable
enum class DimensionTypeSky(val id: String) {
    /** The sun, the moon, the stars and the blue sky. */
    @SerialName("overworld")
    OVERWORLD("overworld"),

    /** The end's dark, streaked sky. */
    @SerialName("end")
    END("end"),

    /** Nothing: the fog's colour, as in the nether. */
    @SerialName("none")
    NONE("none")
}

/**
 * The colours of the sky, the fog and the clouds, where the biome a player
 * stands in doesn't set its own (the game's biomes set the sky and the fog;
 * a project biome may not). Absent, each is the overworld's.
 */
@Serializable
data class DimensionTypeColors(
    /** `#rrggbb`. Default `#78a7ff`. */
    val sky: String? = null,
    /** `#rrggbb`. Default `#c0d8ff`. */
    val fog: String? = null,
    /** `#rrggbb`, or `#aarrggbb` with how opaque they are first. Default `#ccffffff`, a little see-through white. */
    val clouds: String? = null
) {
    val skyOrDefault: String get() = sky ?: DEFAULT_SKY
    val fogOrDefault: String get() = fog ?: DEFAULT_FOG
    val cloudsOrDefault: String get() = clouds ?: DEFAULT_CLOUDS

    companion object {
        const val DEFAULT_SKY = "#78a7ff"
        const val DEFAULT_FOG = "#c0d8ff"
        const val DEFAULT_CLOUDS = "#ccffffff"

        /** How the sky's and the fog's colour is written. */
        val COLOR = Regex("^#[0-9a-fA-F]{6}$")

        /** How the clouds' colour is written: opaque, or with its alpha first. */
        val CLOUD_COLOR = Regex("^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")
    }
}

/** A range of light levels, each 0 to 15: either end absent is the default's. */
@Serializable
data class LightRange(val min: Int? = null, val max: Int? = null)
