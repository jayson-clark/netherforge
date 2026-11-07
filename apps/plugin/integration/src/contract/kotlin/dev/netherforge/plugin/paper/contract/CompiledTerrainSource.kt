package dev.netherforge.plugin.paper.contract

import dev.netherforge.format.terrain.CompiledTerrain
import dev.netherforge.format.terrain.TerrainGenerator
import dev.netherforge.format.terrain.TerrainScripts
import dev.netherforge.plugin.platform.terrain.GeneratedChunk
import dev.netherforge.plugin.platform.terrain.GeneratedLoot
import dev.netherforge.plugin.platform.terrain.TerrainSource
import dev.netherforge.plugin.platform.terrain.TerrainWorld
import java.util.concurrent.ConcurrentHashMap

/**
 * Format's own generator as the adapter drives every generator ([TerrainSource]): the compiled file, bound once per
 * world it makes ([CompiledTerrain.bind], its script's states the bound generator's, one per chunk thread at a time).
 * The runtime hands the adapter its own (Rust) generator; the contract suites hand it format's, so what a world gets
 * can be held to what format's generator makes.
 */
class CompiledTerrainSource(val terrain: CompiledTerrain, private val scripts: TerrainScripts?) : TerrainSource {
    private val bound = ConcurrentHashMap<TerrainWorld, TerrainGenerator>()

    private fun of(world: TerrainWorld): TerrainGenerator =
        bound.computeIfAbsent(world) { terrain.bind(it.seed, it.minY, it.maxY, scripts) }

    private class ThreadSampler(val generator: TerrainGenerator, val sampler: TerrainGenerator.Sampler, var uses: Int = 0)

    /**
     * What this thread last asked biomes of: one sampler per chunk thread, kept while it asks the same generator (the
     * server asks every 4x4x4 cell of a chunk in turn, and a place's area needs its column's height), and started again
     * now and then so what it keeps stays small.
     */
    private val samplers = ThreadLocal<ThreadSampler>()

    private fun samplerFor(generator: TerrainGenerator): TerrainGenerator.Sampler {
        val kept = samplers.get()
        if (kept != null && kept.generator === generator && kept.uses++ < SAMPLER_USES) return kept.sampler
        return ThreadSampler(generator, generator.sampler()).also(samplers::set).sampler
    }

    override val stone: String get() = terrain.palette[terrain.stone].label
    override val seaLevel: Int get() = terrain.seaLevel
    override val vanillaStructures: Boolean get() = terrain.vanillaStructures
    override val biomes: List<String> get() = terrain.biomes
    override val hasLoot: Boolean get() = terrain.loot.isNotEmpty()

    override fun generate(world: TerrainWorld, chunkX: Int, chunkZ: Int): GeneratedChunk {
        val buffer = of(world).generate(chunkX, chunkZ)
        val loot = if (buffer.loot.isEmpty()) {
            emptyList()
        } else {
            buffer.lootPlaces(chunkX, chunkZ).map { GeneratedLoot(it.x, it.y, it.z, terrain.loot[it.table]) }
        }
        return GeneratedChunk(buffer.minY, buffer.height, buffer.blocks, loot)
    }

    override fun surfaceAt(world: TerrainWorld, x: Int, z: Int): Int = of(world).surfaceAt(x, z)

    override fun dryColumnNear(world: TerrainWorld, x: Int, z: Int): Pair<Int, Int>? = of(world).dryColumnNear(x, z)

    override fun biomeAt(world: TerrainWorld, x: Int, y: Int, z: Int): String {
        val generator = of(world)
        return generator.biomeAt(x, y, z, samplerFor(generator))
    }

    override fun locateHeights(world: TerrainWorld, y: Int, step: Int): IntArray = of(world).locateHeights(y, step)

    override fun firstWhere(world: TerrainWorld, columns: IntArray, ys: IntArray, wanted: Set<String>): Pair<Int, Int>? {
        val generator = of(world)
        val areas = generator.areasWhere { it in wanted }
        if (areas.none { it }) return null
        return generator.sampling { sampler ->
            for (k in 0 until columns.size / 2) {
                val y = sampler.firstWhere(columns[2 * k], columns[2 * k + 1], ys, areas) ?: continue
                return@sampling k to y
            }
            null
        }
    }

    /** Lets go of the Lua states its script ran in (chunks being made with it finish, and theirs go then). */
    override fun close() = bound.values.forEach { it.close() }

    private companion object {
        /** How many places one thread's sampler answers before it's made again. */
        const val SAMPLER_USES = 1 shl 14
    }
}

/** The compiled terrain a contract suite's generator runs ([CompiledTerrainSource]'s). */
val dev.netherforge.plugin.platform.ProjectGenerator.terrain: CompiledTerrain get() = (source as CompiledTerrainSource).terrain
