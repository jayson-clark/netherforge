package dev.netherforge.format.world

import dev.netherforge.format.ref.Ref
import dev.netherforge.format.ref.RefKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `structures/<id>.json`, beside `structures/<id>.nbt`: where the structure
 * generates in the world by itself, the way the game's villages and ruins
 * do. Without this file a structure is only placed by scripts.
 *
 * It maps onto the game's own `worldgen/structure`, `worldgen/structure_set`
 * and `worldgen/template_pool`, written into the start-up datapack
 * ([dev.netherforge.format.datapack.StartupDatapack]), so the game
 * generates it naturally in every world (chunk by chunk, `/locate` finds
 * it) and a change needs a restart. The structure is the game's `jigsaw`
 * kind: one piece (the template itself) unless [pools] adds more.
 *
 * Centities in a structure aren't listed here: they're `marker` entities
 * the template holds (see docs/format/structures.md).
 */
@Serializable
data class StructureGeneration(
    @SerialName("\$schema") val schema: String? = null,
    /**
     * Where it generates: biomes, the game's (`minecraft:plains`) or the project's (`ruby_grove`, `acme:grove` for a
     * package's), or exactly one of the game's tags (`#minecraft:is_forest`).
     */
    @Ref(RefKind.BIOME) val biomes: List<String>,
    /** The side, in chunks, of the squares the world is divided in, each of which may hold one of it. Default 32. */
    val spacing: Int? = null,
    /** Chunks kept clear between two squares' structures: below [spacing]. Default 8. */
    val separation: Int? = null,
    /** Mixes this structure's squares apart from every other's. Default: made from the structure's name. */
    val salt: Int? = null,
    /** When in the world's generation it's placed. Default `surface_structures`. */
    val step: GenerationStep? = null,
    /** How the terrain around it is shaped to it. Default `none`. */
    val terrainAdaptation: TerrainAdaptation? = null,
    /** What [startHeight] counts from: a heightmap (the ground, usually), or `none` for an absolute height. Default `world_surface_wg`. */
    val heightmap: StructureHeightmap? = null,
    /** Blocks above the [heightmap]'s ground (below, when negative), or the height itself with `none`. Default 0. */
    val startHeight: Int? = null,
    /** More pieces: pools of project structures the template's jigsaw blocks name. Empty: the structure is one piece. */
    val pools: Map<String, StructurePool> = emptyMap(),
    /** How many pieces deep jigsaw blocks connect from the template, 1 to 20. Default 7 with [pools], else 1. */
    val depth: Int? = null,
    /** How far from where it starts a piece may reach, in blocks, 1 to 128. Default 80. */
    val maxDistance: Int? = null
) {
    companion object {
        const val SCHEMA = "../.netherforge/schema/structure_generation.schema.json"

        const val DEFAULT_SPACING = 32
        const val DEFAULT_SEPARATION = 8
        const val DEFAULT_MAX_DISTANCE = 80
        const val DEFAULT_DEPTH_WITH_POOLS = 7
        const val MAX_SPACING = 4096
        const val MAX_DEPTH = 20
        const val MAX_DISTANCE = 128
        const val MAX_WEIGHT = 150

        /** The pool a structure's own template starts from, named beside the pools [pools] names: `<namespace>:<id>/start`. */
        const val START_POOL = "start"
    }
}

/** A jigsaw pool: what a jigsaw block of the structure's pieces may connect a piece from, picked by weight. */
@Serializable
data class StructurePool(
    /** The structures it picks from. */
    val elements: List<PoolElement>,
    /** `rigid` places a piece as saved; `terrain_matching` bends it down to the ground (roads). Default `rigid`. */
    val projection: PoolProjection? = null
)

/** One piece a pool may pick: a structure of the project, chosen with probability [weight] over the pool's total. */
@Serializable
data class PoolElement(
    /** The project's own structure (`ruins_tower`), whose template is the piece. */
    val structure: String,
    /** Its share of the pool, 1 to 150. Default 1. */
    val weight: Int? = null
)

@Serializable
enum class GenerationStep(val id: String) {
    @SerialName("raw_generation")
    RAW_GENERATION("raw_generation"),

    @SerialName("lakes")
    LAKES("lakes"),

    @SerialName("local_modifications")
    LOCAL_MODIFICATIONS("local_modifications"),

    @SerialName("underground_structures")
    UNDERGROUND_STRUCTURES("underground_structures"),

    @SerialName("surface_structures")
    SURFACE_STRUCTURES("surface_structures"),

    @SerialName("strongholds")
    STRONGHOLDS("strongholds"),

    @SerialName("underground_ores")
    UNDERGROUND_ORES("underground_ores"),

    @SerialName("underground_decoration")
    UNDERGROUND_DECORATION("underground_decoration"),

    @SerialName("fluid_springs")
    FLUID_SPRINGS("fluid_springs"),

    @SerialName("vegetal_decoration")
    VEGETAL_DECORATION("vegetal_decoration"),

    @SerialName("top_layer_modification")
    TOP_LAYER_MODIFICATION("top_layer_modification")
}

@Serializable
enum class TerrainAdaptation(val id: String) {
    @SerialName("none")
    NONE("none"),

    @SerialName("beard_thin")
    BEARD_THIN("beard_thin"),

    @SerialName("beard_box")
    BEARD_BOX("beard_box"),

    @SerialName("bury")
    BURY("bury"),

    @SerialName("encapsulate")
    ENCAPSULATE("encapsulate")
}

/** The ground a structure's start height counts from. [game] is how the game spells it (null: none). */
@Serializable
enum class StructureHeightmap(val game: String?) {
    @SerialName("none")
    NONE(null),

    @SerialName("world_surface_wg")
    WORLD_SURFACE_WG("WORLD_SURFACE_WG"),

    @SerialName("world_surface")
    WORLD_SURFACE("WORLD_SURFACE"),

    @SerialName("ocean_floor_wg")
    OCEAN_FLOOR_WG("OCEAN_FLOOR_WG"),

    @SerialName("ocean_floor")
    OCEAN_FLOOR("OCEAN_FLOOR"),

    @SerialName("motion_blocking")
    MOTION_BLOCKING("MOTION_BLOCKING"),

    @SerialName("motion_blocking_no_leaves")
    MOTION_BLOCKING_NO_LEAVES("MOTION_BLOCKING_NO_LEAVES")
}

@Serializable
enum class PoolProjection(val id: String) {
    @SerialName("rigid")
    RIGID("rigid"),

    @SerialName("terrain_matching")
    TERRAIN_MATCHING("terrain_matching")
}
