package dev.netherforge.plugin.paper

import dev.netherforge.format.terrain.ChunkBuffer
import dev.netherforge.format.terrain.CompiledTerrain
import dev.netherforge.format.terrain.TerrainGenerator
import dev.netherforge.plugin.platform.ProjectGenerator
import org.bukkit.Bukkit
import org.bukkit.HeightMap
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.World
import org.bukkit.block.Biome
import org.bukkit.block.data.BlockData
import org.bukkit.generator.BiomeProvider
import org.bukkit.generator.ChunkGenerator
import org.bukkit.generator.WorldInfo
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger

/**
 * The project's terrains on Paper (`terrain/<id>.json`, format's
 * [dev.netherforge.format.terrain.CompiledTerrain]): a Bukkit [ChunkGenerator]
 * and [BiomeProvider] per world that ask the published generator by id each
 * time, so hot reload swaps it for the chunks generated afterwards.
 *
 * **Threads.** The server calls a generator from its chunk threads, so
 * [isParallelCapable][ChunkGenerator.isParallelCapable] is true and nothing
 * here touches the main thread's world: a published generator is immutable
 * (format's compiled file, its bound generators' noise only read, the
 * [BlockData] prepared on the main thread when it was published), and the
 * published set is one atomic reference.
 *
 * What a chunk gets is [TerrainGenerator.generate]'s blocks written as chunk
 * data (runs of one block, air left out); a custom block's note block state is
 * written like any, and the runtime's blocks adopt it when the chunk loads.
 * The biomes are the areas' (the project's own from the server's registry,
 * which has them from the start-up datapack), and the game then decorates the
 * chunk with their features. A file's Lua stages run inside [TerrainGenerator.generate],
 * in a Lua state of the generator's for each chunk thread at a time (format's
 * `TerrainScripts`), never the plugin's own.
 */
class PaperWorldGenerators(private val logger: Logger) {
    /** A published generator, ready for the chunk threads: its blocks as [BlockData], its biomes, and the world-bound generators made from it. */
    private class Prepared(val project: ProjectGenerator, val blocks: Array<BlockData?>, val biomes: Map<String, Biome>) {
        private val bound = ConcurrentHashMap<WorldShape, TerrainGenerator>()

        fun boundTo(seed: Long, minY: Int, maxY: Int): TerrainGenerator =
            bound.computeIfAbsent(WorldShape(seed, minY, maxY)) { project.terrain.bind(seed, minY, maxY, project.scripts) }

        /** Lets go of the Lua states its script ran in (chunks being made with it finish, and theirs go then). */
        fun close() = bound.values.forEach { it.close() }

        fun biome(id: String): Biome = biomes[id] ?: Biome.PLAINS
    }

    private data class WorldShape(val seed: Long, val minY: Int, val maxY: Int)

    private class ThreadSampler(val generator: TerrainGenerator, val sampler: TerrainGenerator.Sampler, var uses: Int = 0)

    private companion object {
        /** How many places one thread's sampler answers before it's made again. */
        const val SAMPLER_USES = 1 shl 14

        /** How many generated chunks' containers wait for their chunk to load. */
        const val PENDING_CHUNKS = 16384

        /** The game's `/locate biome`: how far it looks, how far apart its columns are, and its heights. */
        const val LOCATE_RADIUS = 6400
        const val LOCATE_STEP = 32
        const val LOCATE_HEIGHT_STEP = 64

        /** How many columns a search hands its threads at once (whole rings, so the first found is the game's). */
        const val LOCATE_BATCH = 512
    }

    private val published = AtomicReference<Map<String, Prepared>>(emptyMap())

    /** A container a generated chunk fills from a loot table: its place and the table, as the terrain names it. */
    data class LootPlace(val x: Int, val y: Int, val z: Int, val table: String)

    private data class ChunkAt(val world: String, val x: Int, val z: Int)

    /**
     * The containers of chunks made since the server started, from their blocks being made (a chunk thread) until
     * the chunk first loads whole (the main thread), when they're given their tables ([takeLoot]). Only a terrain
     * with loot keeps any, an empty list for a chunk with none, so a chunk that isn't here was made before a restart
     * (or long ago) and is made again to find them. The oldest go first past [PENDING_CHUNKS].
     */
    private val pendingLoot = object : LinkedHashMap<ChunkAt, List<LootPlace>>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ChunkAt, List<LootPlace>>?) = size > PENDING_CHUNKS
    }

    private fun lootOf(terrain: CompiledTerrain, buffer: ChunkBuffer, x: Int, z: Int) =
        buffer.lootPlaces(x, z).map { LootPlace(it.x, it.y, it.z, terrain.loot[it.table]) }

    /**
     * The containers chunk ([x], [z]) of [world] fills from loot tables, once, as it first loads: what its making
     * found, or (made before the server started) what making it again finds. Empty for a world that isn't a
     * project terrain's, or a terrain that fills none. On the main thread.
     */
    fun takeLoot(world: World, x: Int, z: Int): List<LootPlace> {
        val chunks = world.generator as? ProjectChunks ?: return emptyList()
        synchronized(pendingLoot) { pendingLoot.remove(ChunkAt(world.name, x, z)) }?.let { return it }
        val prepared = chunks.holder.current() ?: return emptyList()
        if (prepared.project.terrain.loot.isEmpty()) return emptyList()
        val buffer = prepared.boundTo(world.seed, world.minHeight, world.maxHeight).generate(x, z)
        return lootOf(prepared.project.terrain, buffer, x, z)
    }

    /** Publishes [generators] (on the main thread: block data is made here, once, not on the chunk threads). */
    fun publish(generators: Map<String, ProjectGenerator>) {
        val before = published.getAndSet(generators.mapValues { (id, project) -> prepare(id, project) })
        // A replaced generator's script states go; one no longer published stays, since its worlds keep generating with it.
        for ((id, prepared) in before) if (id in generators) prepared.close()
    }

    private fun prepare(id: String, project: ProjectGenerator): Prepared {
        val stone = project.terrain.palette[project.terrain.stone].label
        val blocks = project.states.mapIndexed { index, state ->
            if (index == 0) {
                null
            } else {
                try {
                    Bukkit.createBlockData(state)
                } catch (e: IllegalArgumentException) {
                    logger.warning("Terrain $id: \"$state\" isn't a block this server knows (${e.message}); it's left as $stone")
                    Bukkit.createBlockData(stone)
                }
            }
        }.toTypedArray()
        // A project biome is in the registry from the start-up datapack: one added since the server started isn't yet.
        val biomes = project.biomes.mapValues { (_, key) ->
            NamespacedKey.fromString(key)?.let(Registry.BIOME::get) ?: Biome.PLAINS.also {
                logger.warning("Terrain $id: this server has no biome \"$key\" (until it restarts, for a new one); it's plains")
            }
        }
        return Prepared(project, blocks, biomes)
    }

    /** What one world holds of its generator: the last one published under its id, so a generator that's gone keeps generating. */
    private inner class Holder(val id: String) {
        @Volatile private var last: Prepared? = null

        /** The generator now, or the last one this world had; null when it never had one. */
        fun current(): Prepared? {
            val now = published.get()[id]
            if (now != null) {
                last = now
                return now
            }
            return last
        }
    }

    /** Where a `/locate biome` found its biome, or that it didn't (null [at]), or that it was stopped ([stopped]). */
    class Located(val at: Triple<Int, Int, Int>?, val stopped: Boolean = false)

    /**
     * A `/locate biome` of [biome] in [world], made on the main thread, or null when the world isn't a project
     * terrain's (the game's own command does). [Locator.search] runs off it.
     */
    fun locator(world: World, biome: Biome): Locator? {
        val chunks = world.generator as? ProjectChunks ?: return null
        val prepared = chunks.holder.current() ?: return null
        val generator = prepared.boundTo(world.seed, world.minHeight, world.maxHeight)
        return ProjectLocator(chunks.holder, prepared, generator, generator.areasWhere { prepared.biome(it) == biome })
    }

    /** A `/locate biome` of one world: see [locator]. */
    interface Locator {
        /** Looks around ([x], [y], [z]) with [threads] of [pool]; [stop] ends it, as a generator published since it started does (its states are going). */
        fun search(x: Int, y: Int, z: Int, pool: ExecutorService, threads: Int, stop: () -> Boolean): Located
    }

    /**
     * Looks for a biome as the game's `/locate biome` does: columns [LOCATE_STEP] blocks apart, ring by ring out to
     * [LOCATE_RADIUS], each from the asker's height outwards every [LOCATE_HEIGHT_STEP], the first found in that order
     * winning. A project's stages can take minutes over that many places, so it isn't the main thread's: the caller's
     * thread hands each batch of rings to a pool, each of whose threads takes one run of columns (neighbours share the
     * areas their heights are blended from).
     */
    private inner class ProjectLocator(
        private val holder: Holder,
        private val prepared: Prepared,
        private val generator: TerrainGenerator,
        private val wanted: BooleanArray
    ) : Locator {
        override fun search(x: Int, y: Int, z: Int, pool: ExecutorService, threads: Int, stop: () -> Boolean): Located {
            if (wanted.none { it }) return Located(null)
            val ys = generator.locateHeights(y, LOCATE_HEIGHT_STEP)
            var r = 0
            while (r <= LOCATE_RADIUS) {
                val batch = ArrayList<Pair<Int, Int>>()
                while (r <= LOCATE_RADIUS && batch.size < LOCATE_BATCH) {
                    batch += TerrainGenerator.locateRing(r, LOCATE_STEP)
                    r += LOCATE_STEP
                }
                val best = AtomicInteger(Int.MAX_VALUE)
                val found = arrayOfNulls<Int>(batch.size)
                val run = (batch.size + threads - 1) / threads
                val tasks = (batch.indices step run).map { start ->
                    pool.submit {
                        generator.sampling { sampler ->
                            for (i in start until minOf(start + run, batch.size)) {
                                if (i > best.get() || stop()) return@sampling
                                val (dx, dz) = batch[i]
                                val at = sampler.firstWhere(x + dx, z + dz, ys, wanted) ?: continue
                                found[i] = at
                                best.accumulateAndGet(i, ::minOf)
                                return@sampling
                            }
                        }
                    }
                }
                for (task in tasks) task.get()
                val i = best.get()
                if (i != Int.MAX_VALUE) return Located(Triple(x + batch[i].first, found[i]!!, z + batch[i].second))
                if (stop() || holder.current() !== prepared) return Located(null, stopped = true)
            }
            return Located(null)
        }
    }

    /** The chunk generator and biome provider of a world made with the project generator [id]: one pair per world, sharing what they hold. */
    fun forWorld(id: String): Pair<ChunkGenerator, BiomeProvider> {
        val holder = Holder(id)
        val biomes = ProjectBiomes(holder)
        return ProjectChunks(holder, biomes) to biomes
    }

    private inner class ProjectChunks(val holder: Holder, private val biomes: ProjectBiomes) : ChunkGenerator() {
        override fun generateNoise(info: WorldInfo, random: Random, chunkX: Int, chunkZ: Int, data: ChunkGenerator.ChunkData) {
            val prepared = holder.current() ?: return
            val buffer = prepared.boundTo(info.seed, data.minHeight, data.maxHeight).generate(chunkX, chunkZ)
            if (prepared.project.terrain.loot.isNotEmpty()) {
                val loot = if (buffer.loot.isEmpty()) emptyList() else lootOf(prepared.project.terrain, buffer, chunkX, chunkZ)
                synchronized(pendingLoot) { pendingLoot[ChunkAt(info.name, chunkX, chunkZ)] = loot }
            }
            val height = buffer.height
            for (lx in 0 until 16) {
                for (lz in 0 until 16) {
                    val start = buffer.columnStart(lx, lz)
                    var y = 0
                    while (y < height) {
                        val block = buffer.blocks[start + y]
                        var end = y
                        while (end + 1 < height && buffer.blocks[start + end + 1] == block) end++
                        // Air is what a new chunk holds already.
                        prepared.blocks[block]?.let { data.setRegion(lx, buffer.minY + y, lz, lx + 1, buffer.minY + end + 1, lz + 1, it) }
                        y = end + 1
                    }
                }
            }
        }

        override fun getDefaultBiomeProvider(worldInfo: WorldInfo): BiomeProvider = biomes

        override fun getBaseHeight(info: WorldInfo, random: Random, x: Int, z: Int, heightMap: HeightMap): Int {
            val prepared = holder.current() ?: return info.minHeight
            val generator = prepared.boundTo(info.seed, info.minHeight, info.maxHeight)
            val top = generator.surfaceAt(x, z)
            // The first free block above what the map counts: the ground, or the sea over it for the maps that see water.
            val sea = prepared.project.terrain.seaLevel
            return when (heightMap) {
                HeightMap.OCEAN_FLOOR, HeightMap.OCEAN_FLOOR_WG -> top + 1
                else -> maxOf(top, sea) + 1
            }
        }

        override fun getFixedSpawnLocation(world: World, random: Random): Location? {
            val prepared = holder.current() ?: return null
            val generator = prepared.boundTo(world.seed, world.minHeight, world.maxHeight)
            val (x, z) = generator.dryColumnNear(0, 0) ?: return null
            return Location(world, x + 0.5, generator.surfaceAt(x, z) + 1.0, z + 0.5)
        }

        // Each of these asks for the game's own stage to run *after* ours, filling in what ours left (the game's noise
        // would put its stone wherever there's air): the terrain is all of it ours, so none of them runs.
        override fun shouldGenerateNoise() = false

        override fun shouldGenerateSurface() = false

        override fun shouldGenerateBedrock() = false

        override fun shouldGenerateCaves() = false

        // The game's decoration stage places each biome's features (a game biome's own trees, flowers, ores and lakes; a
        // project biome exactly the ones its file lists) and the pieces of the structures that started: it always runs,
        // so what decorates the world is the biomes', and `structures.vanilla` is only about structures.
        override fun shouldGenerateDecorations() = true

        override fun shouldGenerateMobs() = false

        override fun shouldGenerateStructures() = vanillaStructures()

        private fun vanillaStructures() = holder.current()?.project?.terrain?.vanillaStructures == true

        override fun isParallelCapable() = true
    }

    /**
     * A world's biomes. The server asks [getBiomes] once, as it makes the world, and works out from that list everything
     * it decorates with (the features of every step, in one order) and what `/locate biome` searches: a biome outside it
     * would have features the server can't place. So a generator published since, whose areas name a biome the world
     * didn't start with, has that biome's areas as the world's first one until the world is loaded again (a restart).
     */
    private inner class ProjectBiomes(private val holder: Holder) : BiomeProvider() {
        @Volatile private var possible: Set<Biome>? = null
        private val outside = ConcurrentHashMap.newKeySet<Biome>()

        /**
         * What this thread last asked biomes of: one sampler per chunk thread, kept while it asks the same generator
         * (the server asks every 4x4x4 cell of a chunk in turn, and a place's area needs its column's height), and
         * started again now and then so what it keeps stays small.
         */
        private val samplers = ThreadLocal<ThreadSampler>()

        private fun samplerFor(generator: TerrainGenerator): TerrainGenerator.Sampler {
            val kept = samplers.get()
            if (kept != null && kept.generator === generator && kept.uses++ < SAMPLER_USES) return kept.sampler
            return ThreadSampler(generator, generator.sampler()).also(samplers::set).sampler
        }

        override fun getBiome(info: WorldInfo, x: Int, y: Int, z: Int): Biome {
            val prepared = holder.current() ?: return Biome.PLAINS
            val generator = prepared.boundTo(info.seed, info.minHeight, info.maxHeight)
            // The game asks the corner of each 4x4x4 cell: a place's own area (a volume, the script's biome stage), else its column's.
            val biome = prepared.biome(generator.biomeAt(x, y, z, samplerFor(generator)))
            val started = possible ?: return biome
            if (biome in started) return biome
            if (outside.add(biome)) {
                logger.warning(
                    "World ${info.name}: its generator ${holder.id} now has the biome ${biome.key().asString()} in an area, which the " +
                        "world didn't start with; it's ${started.first().key().asString()} there until the world is loaded again (restart the server)"
                )
            }
            return started.first()
        }

        override fun getBiomes(info: WorldInfo): List<Biome> {
            val prepared = holder.current() ?: return listOf(Biome.PLAINS)
            val started = possible ?: LinkedHashSet(prepared.project.terrain.biomes.map { prepared.biome(it) }).also { possible = it }
            return started.toList()
        }
    }
}
