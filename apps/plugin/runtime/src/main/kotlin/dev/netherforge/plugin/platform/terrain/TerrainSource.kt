package dev.netherforge.plugin.platform.terrain

/** Which world a generator makes: its seed, and its blocks from [minY] up to but not including [maxY]. */
data class TerrainWorld(val seed: Long, val minY: Int, val maxY: Int)

/**
 * A chunk a generator made: [blocks] are indexes into its generator's palette ([dev.netherforge.plugin.platform.ProjectGenerator.states]),
 * column by column (`x`, then `z`), each from the bottom up ([columnStart]), [height] a column; [loot] the containers it
 * fills from loot tables, as the server knows the tables.
 */
class GeneratedChunk(val minY: Int, val height: Int, val blocks: IntArray, val loot: List<GeneratedLoot>) {
    fun columnStart(localX: Int, localZ: Int): Int = (localX * 16 + localZ) * height
}

/** A container a generator fills: where, and from which loot table. */
data class GeneratedLoot(val x: Int, val y: Int, val z: Int, val table: String)

/**
 * A project generator as the adapter's chunk threads run it: any thread, at once, any number of worlds (each call says
 * which). The Kotlin runtime's is format's compiled terrain bound per world; the Rust runtime's is its own, across FFM.
 * Not part of the Platform spec's generated glue (its calls come from every chunk thread, which the generated glue
 * doesn't allow): its wire is written by hand on each side.
 */
interface TerrainSource {
    /** The block state the palette's stone is: what a state the server doesn't know is written as instead. */
    val stone: String

    val seaLevel: Int

    /** Whether the game's own structures generate in its worlds. */
    val vanillaStructures: Boolean

    /** The biomes its areas name (as [dev.netherforge.plugin.platform.ProjectGenerator.biomes] keys them), in its file's order. */
    val biomes: List<String>

    /** Whether it fills any container from a loot table. */
    val hasLoot: Boolean

    fun generate(world: TerrainWorld, chunkX: Int, chunkZ: Int): GeneratedChunk

    /** The y of the top block of the terrain at a column. */
    fun surfaceAt(world: TerrainWorld, x: Int, z: Int): Int

    /** A column near ([x], [z]) whose top isn't under water, or null when none is near. */
    fun dryColumnNear(world: TerrainWorld, x: Int, z: Int): Pair<Int, Int>?

    /** The biome at a place, as [biomes] names it. */
    fun biomeAt(world: TerrainWorld, x: Int, y: Int, z: Int): String

    /** The heights `/locate biome` looks at from [y] outwards, every [step]. */
    fun locateHeights(world: TerrainWorld, y: Int, step: Int): IntArray

    /**
     * The first of [columns] (`x, z` pairs, in order) with one of [wanted] at one of [ys] (in order): its index among the
     * columns and the y, or null when none has.
     */
    fun firstWhere(world: TerrainWorld, columns: IntArray, ys: IntArray, wanted: Set<String>): Pair<Int, Int>?

    /** Lets go of what it keeps (its scripts' states): a generator published since replaces it. */
    fun close()
}
