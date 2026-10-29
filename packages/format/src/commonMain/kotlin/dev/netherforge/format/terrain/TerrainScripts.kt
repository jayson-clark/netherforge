package dev.netherforge.format.terrain

import dev.netherforge.format.game.BlockState
import dev.netherforge.format.lua.LuaArgs
import dev.netherforge.format.lua.LuaCaps
import dev.netherforge.format.lua.LuaChunk
import dev.netherforge.format.lua.LuaFailure
import dev.netherforge.format.lua.LuaHostFunction
import dev.netherforge.format.lua.LuaPlatform
import dev.netherforge.format.lua.LuaState
import dev.netherforge.format.module.ModuleKind
import dev.netherforge.format.noise.FastNoiseLite
import dev.netherforge.format.noise.TerrainSeeds
import dev.netherforge.format.project.Kinds
import kotlin.concurrent.Volatile

/**
 * What a generator's script runs with: the platform's Lua ([platform]), the files it reads ([sources]: the script
 * and the module files it may require, by project path, as text), and who hears when it fails ([failed]). The
 * server reads the files when it publishes the generator, the editor's preview when it draws: a generator never
 * reads a file while it makes a chunk.
 *
 * [failed] is called from whichever thread generates (the server's chunk threads), as often as a call fails: each
 * failure is deterministic, so the same script fails the same way at the same place every time, and the caller says
 * it once.
 */
class TerrainScripts(val platform: LuaPlatform, val sources: Map<String, String>, val failed: (TerrainScriptFailure) -> Unit) {
    /**
     * Loads [terrain]'s script once as a chunk thread would (its body run, its stages found), in a normal overworld
     * of seed 0, and answers how it failed, or null when it loads: a script that doesn't compile, errors in its body
     * or returns no table of stages. What the server checks when it publishes a generator, so a mistake is a problem
     * at once rather than when a chunk is first made. [failed] isn't told.
     */
    fun check(terrain: CompiledTerrain): TerrainScriptFailure? {
        if (terrain.script == null) return null
        var found: TerrainScriptFailure? = null
        val height = WorldHeight.OVERWORLD
        val generator = terrain.bind(0, height.minY, height.maxY, TerrainScripts(platform, sources) { if (found == null) found = it })
        try {
            generator.loadScript()
        } finally {
            generator.close()
        }
        return found
    }

    companion object {
        /** The memory each script's Lua state may hold. */
        const val MEMORY_MB = 64

        /**
         * What [script] may run, from the files of its project (or package) by their own paths ([files], each read by
         * [read]): the script itself and every module's Lua, which it may `require`. One that can't be read is left
         * out (a `require` of it then says there's no such module).
         */
        fun sourcesOf(script: CompiledScript, files: Collection<String>, read: (String) -> String?): Map<String, String> =
            files.filter { it == script.file || (it.startsWith("${ModuleKind.folder}/") && it.endsWith(".lua")) }
                .sorted()
                .mapNotNull { path -> read(path)?.let { path to it } }
                .toMap()

        /**
         * The paths a module name may be ([name] as `require` takes it) for code at [from] (`@modules/terrain/a.lua`, or
         * the script's own), its modules found where [files] has them (`modules/(world)/rocks/init.lua` is the module
         * `rocks`; one [files] lacks is looked for in `modules/` itself).
         */
        fun modulePaths(name: String, from: String, files: Collection<String> = emptyList()): List<String> {
            val parts = name.split('.')
            if (parts.any { !PART.matches(it) }) return emptyList()
            val joined = parts.joinToString("/")
            val modules = files.mapNotNull { Kinds.classify(it) }.filter { it.kind == ModuleKind.id && it.pkg == null }
                .associate { it.id!! to ModuleKind.locationOf(it.id!!, it.group) }

            fun location(id: String) = modules[id] ?: ModuleKind.locationOf(id)
            val own = Kinds.classify(from.removePrefix("@"))?.takeIf { it.kind == ModuleKind.id }?.id
            val ownFiles = own?.let { listOf("${location(it)}/$joined.lua", "${location(it)}/$joined/${ModuleKind.ENTRY}") }.orEmpty()
            val rest = parts.drop(1).joinToString("/")
            val module = location(parts[0])
            val moduleFiles = if (rest.isEmpty()) {
                listOf("$module/${ModuleKind.ENTRY}")
            } else {
                listOf("$module/$rest.lua", "$module/$rest/${ModuleKind.ENTRY}")
            }
            return ownFiles + moduleFiles
        }

        private val PART = Regex("[A-Za-z0-9_]+")
    }
}

/**
 * A script that failed: its [file] (the script's, `terrain/<id>.lua`), the [stage] it failed in (`load` for its
 * body, `height`, `density`, `area`, `biome`, `terrain` or `decorate`) and Lua's [message], which starts with the file and line it failed at
 * when it knows them (a module's, if it failed in one). What it failed at is the file's own result.
 */
data class TerrainScriptFailure(val file: String, val stage: String, val message: String) {
    /** The file and line [message] names, or [file] and null when it names none. */
    val location: Pair<String, Int?>
        get() {
            val match = LOCATION.find(message) ?: return file to null
            return match.groupValues[1] to match.groupValues[2].toInt()
        }

    private companion object {
        val LOCATION = Regex("""^([^:\s]+\.lua):(\d+):""")
    }
}

/** States handed out to one thread at a time, safe to give back and close from any thread. */
internal expect class StatePool<T : Any>() {
    fun take(): T?

    fun give(item: T)
}

/**
 * A generator's script, run in a pool of Lua states (one per thread generating at once): each made on first need,
 * its body run once, then kept for the next call. [close] lets go of every state: one in use is closed when it's
 * given back, so a generator replaced by a reload stops holding them while chunks it started finish.
 */
internal class ScriptRunner(val generator: TerrainGenerator, val script: CompiledScript, val scripts: TerrainScripts) {
    private val pool = StatePool<TerrainScriptState>()

    @Volatile private var closed = false

    /** The noises the script asks for, in [CompiledScript.noises]' order. */
    val noises: List<FastNoiseLite> = script.noises.map { it.noise.build(TerrainSeeds.forRole(generator.seed, "script:noise:${it.name}")) }

    private val heightSeed = TerrainSeeds.forRole(generator.seed, "script:height")
    private val terrainSeed = TerrainSeeds.forRole(generator.seed, "script:terrain")
    private val decorateSeed = TerrainSeeds.forRole(generator.seed, "script:decorate")
    private val densitySeed = TerrainSeeds.forRole(generator.seed, "script:density")
    private val areaSeed = TerrainSeeds.forRole(generator.seed, "script:area")
    private val biomeSeed = TerrainSeeds.forRole(generator.seed, "script:biome")

    /** The stages the first state made found, so asking [stages] doesn't make another while that one is in use. */
    @Volatile internal var found: Set<String>? = null

    /** The stages the script returns (none when its body fails): the same in every state, so one state's say. */
    val stages: Set<String> by lazy {
        found?.let { return@lazy it }
        val state = take()
        try {
            state.stages
        } finally {
            give(state)
        }
    }

    fun take(): TerrainScriptState = pool.take() ?: TerrainScriptState(this)

    fun give(state: TerrainScriptState) {
        pool.give(state)
        if (closed) drain()
    }

    fun close() {
        closed = true
        drain()
    }

    private fun drain() {
        while (true) (pool.take() ?: return).close()
    }

    fun failed(stage: String, message: String) = scripts.failed(TerrainScriptFailure(script.file, stage, message))

    /** What `math.random` is seeded with in a column's height. */
    fun heightSeed(x: Int, z: Int): Int = place(heightSeed, x, z)

    /** What `math.random` is seeded with in a chunk's [stage]. */
    fun chunkSeed(stage: String, chunkX: Int, chunkZ: Int): Int = place(if (stage == TERRAIN) terrainSeed else decorateSeed, chunkX, chunkZ)

    /** What `math.random` is seeded with in the density at a point of the grid. */
    fun densitySeed(x: Int, y: Int, z: Int): Int = TerrainSeeds.mix(place(densitySeed, x, z) xor (y * 0x5BD1E995))

    /** What `math.random` is seeded with in a column's area. */
    fun areaSeed(x: Int, z: Int): Int = place(areaSeed, x, z)

    /** What `math.random` is seeded with in a place's biome. */
    fun biomeSeed(x: Int, y: Int, z: Int): Int = TerrainSeeds.mix(place(biomeSeed, x, z) xor (y * 0x5BD1E995))

    private fun place(role: Int, x: Int, z: Int): Int = TerrainSeeds.mix(TerrainSeeds.mix(role xor (x * 0x1B873593)) xor (z * 0x2C1B3C6D))

    companion object {
        const val TERRAIN = "terrain"
        const val DECORATE = "decorate"
        const val DENSITY = "density"
        const val AREA = "area"
        const val BIOME = "biome"
    }
}

/**
 * One Lua state running a generator's script, used by one thread at a time: the host functions the glue
 * ([TerrainScriptGlue]) calls, and the calls into its stages. The chunk being generated is [chunk] while a chunk
 * stage runs. What the script asks of other columns (heights, areas) is a sampler's of its own with no state of
 * its own, so a script stage that answers it (the `area` stage of the column asked about) runs in another state:
 * a host function never calls back into the state that called it.
 */
internal class TerrainScriptState(private val runner: ScriptRunner) {
    private val generator = runner.generator
    private val terrain = generator.terrain
    private val sampler = generator.sampler()
    private var chunk: ChunkGeneration? = null
    private val state: LuaState

    /** The script's stages (`height`, `density`, `terrain`, `decorate`), or none when its body failed. */
    val stages: Set<String>

    init {
        state = runner.scripts.platform.open(hostFunctions())
        stages = try {
            state.run(TerrainScriptGlue.CHUNK_NAME, TerrainScriptGlue.SOURCE)
            // Whole numbers cross as plain numbers (the glue makes them integers): a Long is slow to make in JS.
            val names = state.call(
                "nf_load",
                runner.script.file,
                runner.script.budget,
                TerrainScripts.MEMORY_MB,
                generator.seed.toString(),
                generator.minY,
                generator.maxY - 1,
                terrain.seaLevel,
                runner.script.noises.joinToString("\n") { it.name },
                terrain.areas.joinToString("\n") { it.name },
                terrain.areas.joinToString("\n") { it.biome },
                terrain.palette.joinToString("\n") { it.label },
                terrain.areas.filter { it.volume != null }.joinToString("\n") { it.name },
                terrain.loot.joinToString("\n")
            ) as String
            names.split(',').filter { it.isNotEmpty() }.toSet()
        } catch (e: LuaFailure) {
            runner.failed("load", e.message)
            emptySet()
        }
        if (runner.found == null) runner.found = stages
        if (ScriptRunner.DENSITY in stages && terrain.density == null) {
            runner.failed(
                "load",
                "${runner.script.file}: the density stage runs only in a file with a terrain.density: add one (`{}` for no noises of its own)"
            )
        }
    }

    /** The height of column ([x], [z]) from the file's [height], or null when the script's stage failed there. */
    fun height(x: Int, z: Int, height: Int): Int? = try {
        // A whole number, as a float (see nf_height).
        (state.call("nf_height", x, z, height, runner.heightSeed(x, z)) as? Double)
            ?.coerceIn(Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble())?.toInt()
    } catch (e: LuaFailure) {
        runner.failed("height", e.message)
        null
    }

    /** The density at the grid's point ([x], [y], [z]) from the file's [value] there, or null when the script's stage failed there. */
    fun density(x: Int, y: Int, z: Int, value: Double): Double? = try {
        state.call("nf_density", x, y, z, value, runner.densitySeed(x, y, z)) as? Double
    } catch (e: LuaFailure) {
        runner.failed("density", e.message)
        null
    }

    /** The area (an index in [CompiledTerrain.areas]) of column ([x], [z]) from the file's [file], or null when the stage failed there. */
    fun area(x: Int, z: Int, file: Int): Int? = try {
        (state.call("nf_area", x, z, file, runner.areaSeed(x, z)) as? Number)?.toInt()
    } catch (e: LuaFailure) {
        runner.failed(ScriptRunner.AREA, e.message)
        null
    }

    /** The area of the place ([x], [y], [z]) from the file's [file], or null when the stage failed there. */
    fun biome(x: Int, y: Int, z: Int, file: Int): Int? = try {
        (state.call("nf_biome", x, y, z, file, runner.biomeSeed(x, y, z)) as? Number)?.toInt()
    } catch (e: LuaFailure) {
        runner.failed(ScriptRunner.BIOME, e.message)
        null
    }

    /** Runs chunk stage [stage] on [chunk]; false (and its changes are the caller's to undo) when it failed. */
    fun chunkStage(stage: String, chunk: ChunkGeneration): Boolean {
        this.chunk = chunk
        return try {
            state.call("nf_chunk", stage, chunk.chunkX, chunk.chunkZ, runner.chunkSeed(stage, chunk.chunkX, chunk.chunkZ))
            true
        } catch (e: LuaFailure) {
            runner.failed(stage, e.message)
            false
        } finally {
            this.chunk = null
        }
    }

    fun close() = state.close()

    private fun int(args: LuaArgs, index: Int): Int {
        val value = args.number(index)
        if (value.isNaN()) throw LuaFailure("bad argument #$index (number expected)")
        return value.coerceIn(Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble()).toInt()
    }

    private fun current(): ChunkGeneration = chunk ?: throw LuaFailure("a chunk can only be used during the stage it's given to")

    private fun hostFunctions(): Map<String, LuaHostFunction> = mapOf(
        "host_noise" to LuaHostFunction { args ->
            val noise = runner.noises[int(args, 1)]
            if (args.count >= 4) {
                noise.getNoise(args.number(2), args.number(3), args.number(4))
            } else {
                noise.getNoise(args.number(2), args.number(3))
            }
        },
        "host_fill" to LuaHostFunction { args ->
            fill(current(), int(args, 1), int(args, 2), int(args, 3), int(args, 4), int(args, 5), int(args, 6), int(args, 7))
            null
        },
        "host_block" to LuaHostFunction { args ->
            val at = current()
            val lx = int(args, 1) - at.minX
            val y = int(args, 2)
            val lz = int(args, 3) - at.minZ
            if (lx !in 0..15 || lz !in 0..15 || y < generator.minY || y >= generator.maxY) -1 else at.buffer[lx, y, lz]
        },
        "host_file_height" to LuaHostFunction { args -> sampler.fileHeight(int(args, 1), int(args, 2)) },
        "host_area" to LuaHostFunction { args -> sampler.areaAt(int(args, 1), int(args, 2)) },
        "host_point_area" to LuaHostFunction { args -> sampler.pointAreaAt(int(args, 1), int(args, 2), int(args, 3)) },
        "host_set_loot" to LuaHostFunction { args ->
            val at = current()
            val lx = int(args, 1) - at.minX
            val y = int(args, 2)
            val lz = int(args, 3) - at.minZ
            val table = int(args, 4)
            if (table !in terrain.loot.indices) throw LuaFailure("no loot table $table")
            if (lx in 0..15 && lz in 0..15 && y >= generator.minY && y < generator.maxY) {
                at.buffer.loot[at.buffer.columnStart(lx, lz) + y - generator.minY] = table
            }
            null
        },
        "host_resolve" to LuaHostFunction { args -> resolve(args.string(1).orEmpty()) },
        "host_module_path" to LuaHostFunction { args ->
            val sources = runner.scripts.sources
            TerrainScripts.modulePaths(args.string(1).orEmpty(), args.string(2).orEmpty(), sources.keys).firstOrNull { it in sources }
        },
        "host_load" to LuaHostFunction { args ->
            val path = args.string(1).orEmpty()
            val source = runner.scripts.sources[path] ?: throw LuaFailure("$path isn't there to run")
            LuaChunk("@$path", source)
        },
        // The standard library's caps, the plugin sandbox's own: the glue runs them before the script.
        "host_caps" to LuaHostFunction { LuaChunk(LuaCaps.CHUNK_NAME, LuaCaps.SOURCE) }
    )

    /** Fills the box from ([x1], [y1], [z1]) to ([x2], [y2], [z2]), both corners included, where it's in [at] and the world. */
    private fun fill(at: ChunkGeneration, x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int, block: Int) {
        if (block !in terrain.palette.indices) throw LuaFailure("no block $block")
        val fromX = maxOf(minOf(x1, x2), at.minX)
        val toX = minOf(maxOf(x1, x2), at.minX + 15)
        val fromZ = maxOf(minOf(z1, z2), at.minZ)
        val toZ = minOf(maxOf(z1, z2), at.minZ + 15)
        val fromY = minOf(y1, y2)
        val toY = maxOf(y1, y2)
        for (x in fromX..toX) for (z in fromZ..toZ) at.buffer.fill(x - at.minX, z - at.minZ, fromY, toY, block)
    }

    /**
     * The palette entry a script's block id is: a palette entry's own label (`minecraft:stone`, a project block as the
     * file names it), or the game's block state written another way (properties in another order); -1 when the
     * generator doesn't place it.
     */
    private fun resolve(text: String): Int {
        val palette = terrain.palette
        val exact = palette.indexOfFirst { it.label == text }
        if (exact >= 0) return exact
        if (!text.startsWith("minecraft:")) return -1
        val state = BlockState.parse(text)?.toString() ?: return -1
        return palette.indexOf(TerrainBlock.Vanilla(state))
    }
}
