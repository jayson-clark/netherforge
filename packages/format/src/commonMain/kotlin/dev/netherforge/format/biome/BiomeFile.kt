package dev.netherforge.format.biome

import dev.netherforge.format.centity.SpawnRange
import dev.netherforge.format.project.SpawnCategory
import dev.netherforge.format.ref.Ref
import dev.netherforge.format.ref.RefKind
import dev.netherforge.format.ref.ResourceRef
import dev.netherforge.format.world.GenerationStep
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `biomes/<id>.json`: one of the project's own biomes, which the game knows
 * as `<namespace>:<id>` beside its own. Its climate, the colours of its sky,
 * water and plants, its particles, sounds and music, the mobs that spawn in
 * it, and the game's placed features it's decorated with (none but those
 * listed). A terrain's area, a centity's `spawning`, a structure that
 * generates and `/locate biome` name it like one of the game's.
 *
 * The game reads biomes only as it starts, from the start-up datapack
 * ([BiomeJson] writes the game's format), so a change needs a restart.
 */
@Serializable
data class BiomeFile(
    @SerialName("\$schema") val schema: String? = null,
    val climate: BiomeClimate = BiomeClimate(),
    val colors: BiomeColors = BiomeColors(),
    /** Particles that drift through the air in it. Default none. */
    val particle: AmbientParticle? = null,
    val sounds: BiomeSounds = BiomeSounds(),
    /** The game's mobs that spawn in it, by spawn category. Default none. */
    val spawns: Map<SpawnCategory, List<BiomeSpawn>> = emptyMap(),
    /**
     * How much each spawn of an entity type (`minecraft:enderman`) uses up of how many may spawn near it: the game's
     * spawn costs, which keep a mob from crowding. Default none.
     */
    val spawnCosts: Map<String, SpawnCost> = emptyMap(),
    /**
     * The placed features it's decorated with, by the step they're placed in, in order: the game's
     * (`minecraft:trees_plains`), or one a project datapack defines (`my_trees`, a package's `acme:trees`). Default none.
     */
    @Ref(RefKind.PLACED_FEATURE)
    val features: Map<GenerationStep, List<ResourceRef>> = emptyMap()
) {
    companion object {
        const val SCHEMA = "../.netherforge/schema/biome.schema.json"
    }
}

/** How warm and wet it is, which decides rain or snow, and (without colours of their own) the grass and leaves. */
@Serializable
data class BiomeClimate(
    /** -2 to 2: below 0.15 it snows instead of raining, and water freezes. Default 0.8. */
    val temperature: Double? = null,
    /** How wet, 0 to 1: with the temperature, the colour of grass and leaves without colours of their own. Default 0.4. */
    val downfall: Double? = null,
    /** Whether it rains (or snows) here in the world's weather. Default true. */
    val precipitation: Boolean? = null,
    /** `frozen`: patches of it are colder, as the game's frozen oceans. Default `none`. */
    val temperatureModifier: TemperatureModifier? = null
) {
    val temperatureOrDefault: Double get() = temperature ?: DEFAULT_TEMPERATURE
    val downfallOrDefault: Double get() = downfall ?: DEFAULT_DOWNFALL
    val precipitationOrDefault: Boolean get() = precipitation ?: true

    companion object {
        const val DEFAULT_TEMPERATURE = 0.8
        const val DEFAULT_DOWNFALL = 0.4
        const val MIN_TEMPERATURE = -2.0
        const val MAX_TEMPERATURE = 2.0
    }
}

@Serializable
enum class TemperatureModifier(val id: String) {
    @SerialName("none")
    NONE("none"),

    @SerialName("frozen")
    FROZEN("frozen")
}

/**
 * The colours it's drawn in, each `#rrggbb`. Only [water] is always set (the
 * game needs one); without the others the world's own sky and fog are drawn,
 * and grass and leaves take the climate's colour.
 */
@Serializable
data class BiomeColors(
    val sky: String? = null,
    val fog: String? = null,
    /** Default `#3f76e4`, the game's usual water. */
    val water: String? = null,
    /** The fog seen under water. */
    val waterFog: String? = null,
    val grass: String? = null,
    /** Leaves and vines. */
    val foliage: String? = null,
    /** Leaf litter and other dry leaves. */
    val dryFoliage: String? = null,
    /** How the grass colour is changed where it's drawn: `dark_forest` darkens it, `swamp` mottles it. Default `none`. */
    val grassModifier: GrassModifier? = null
) {
    val waterOrDefault: String get() = water ?: DEFAULT_WATER

    companion object {
        const val DEFAULT_WATER = "#3f76e4"

        /** How a colour is written. */
        val COLOR = Regex("^#[0-9a-fA-F]{6}$")
    }
}

@Serializable
enum class GrassModifier(val id: String) {
    @SerialName("none")
    NONE("none"),

    @SerialName("dark_forest")
    DARK_FOREST("dark_forest"),

    @SerialName("swamp")
    SWAMP("swamp")
}

/** A particle of the game's that takes no options (`minecraft:white_ash`), drifting through the air at [probability] per block a tick. */
@Serializable
data class AmbientParticle(
    val particle: String,
    /** More than 0, at most 1: the game's own are around 0.01 or less. */
    val probability: Double
)

/** What's heard in it: sound events of the game's (`minecraft:ambient.cave`). */
@Serializable
data class BiomeSounds(
    /** Played over and over while a player is in it. */
    val ambient: String? = null,
    /** Played now and then in the dark, near a cave. */
    val mood: MoodSound? = null,
    /** Played at random, each tick by its chance. */
    val additions: AdditionsSound? = null,
    /** The music that plays in it, in place of the world's. */
    val music: BiomeMusic? = null
)

/** The game's cave sounds: played after [tickDelay] ticks in the dark within [blockSearchExtent] blocks, [offset] blocks away. */
@Serializable
data class MoodSound(
    val sound: String,
    /** Default 6000. */
    val tickDelay: Int? = null,
    /** Default 8. */
    val blockSearchExtent: Int? = null,
    /** Default 2. */
    val offset: Double? = null
) {
    val tickDelayOrDefault: Int get() = tickDelay ?: DEFAULT_TICK_DELAY
    val blockSearchExtentOrDefault: Int get() = blockSearchExtent ?: DEFAULT_BLOCK_SEARCH_EXTENT
    val offsetOrDefault: Double get() = offset ?: DEFAULT_OFFSET

    companion object {
        const val DEFAULT_TICK_DELAY = 6000
        const val DEFAULT_BLOCK_SEARCH_EXTENT = 8
        const val DEFAULT_OFFSET = 2.0
    }
}

/** A sound played each tick at [chance] (0 to 1). */
@Serializable
data class AdditionsSound(val sound: String, val chance: Double)

/** A track, played again after [minDelay] to [maxDelay] ticks. */
@Serializable
data class BiomeMusic(
    val sound: String,
    /** Default 12000 (ten minutes). */
    val minDelay: Int? = null,
    /** Default 24000. */
    val maxDelay: Int? = null
) {
    val minDelayOrDefault: Int get() = minDelay ?: DEFAULT_MIN_DELAY
    val maxDelayOrDefault: Int get() = maxDelay ?: maxOf(minDelayOrDefault, DEFAULT_MAX_DELAY)

    companion object {
        const val DEFAULT_MIN_DELAY = 12000
        const val DEFAULT_MAX_DELAY = 24000
    }
}

/** One of the game's mobs ([entity], `minecraft:zombie`) spawning in it: how likely against the rest of its category, and how many at once. */
@Serializable
data class BiomeSpawn(
    val entity: String,
    /** At least 1. Default 10. */
    val weight: Int? = null,
    /** How many spawn together, `{ "min": 2, "max": 4 }`. Default one. */
    val group: SpawnRange? = null
) {
    val weightOrDefault: Int get() = weight ?: DEFAULT_WEIGHT
    val groupMin: Int get() = group?.min ?: 1
    val groupMax: Int get() = maxOf(groupMin, group?.max ?: groupMin)

    companion object {
        const val DEFAULT_WEIGHT = 10
    }
}

/** The game's spawn cost of one entity type: what each one [charge]s, against the [energyBudget] near it. Both more than 0. */
@Serializable
data class SpawnCost(val charge: Double, val energyBudget: Double)
