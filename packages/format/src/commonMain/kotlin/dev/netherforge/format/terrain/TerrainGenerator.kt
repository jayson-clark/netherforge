package dev.netherforge.format.terrain

import dev.netherforge.format.noise.FastNoiseLite
import dev.netherforge.format.noise.TerrainSeeds
import kotlin.math.abs
import kotlin.math.floor
import kotlin.random.Random

/**
 * The blocks of one chunk column by column, as indexes into a
 * [CompiledTerrain.palette], from [minY] up to but not including [maxY].
 * 0 is air. A server copies it into the game's chunk data and the editor's
 * preview draws it, so both see what the generator made.
 */
class ChunkBuffer(val minY: Int, val maxY: Int) {
    val height: Int = maxY - minY

    /** Column by column (`x`, then `z`), each from the bottom up. */
    val blocks: IntArray = IntArray(16 * 16 * height)

    /**
     * The containers to fill from a loot table: a block's index in [blocks] to the table's in
     * [CompiledTerrain.loot]. The server fills whichever of them is a container once the chunk's blocks are placed.
     */
    val loot: MutableMap<Int, Int> = HashMap()

    /** Each container [loot] marks: its place (in the world's x and z for a chunk at [chunkX], [chunkZ]) and table. */
    fun lootPlaces(chunkX: Int, chunkZ: Int): List<LootPlace> = loot.entries.sortedBy { it.key }.map { (index, table) ->
        val column = index / height
        LootPlace(chunkX * 16 + column / 16, minY + index % height, chunkZ * 16 + column % 16, table)
    }

    fun columnStart(localX: Int, localZ: Int): Int = (localX * 16 + localZ) * height

    operator fun get(localX: Int, y: Int, localZ: Int): Int = blocks[columnStart(localX, localZ) + y - minY]

    operator fun set(localX: Int, y: Int, localZ: Int, block: Int) {
        blocks[columnStart(localX, localZ) + y - minY] = block
    }

    /** Sets [fromY] to [toY], both included, of one column; the part outside the chunk's height is left out. */
    fun fill(localX: Int, localZ: Int, fromY: Int, toY: Int, block: Int) {
        val start = columnStart(localX, localZ) - minY
        for (y in maxOf(fromY, minY)..minOf(toY, maxY - 1)) blocks[start + y] = block
    }
}

/** A container a generated chunk fills from loot table [table] (an index into [CompiledTerrain.loot]). */
data class LootPlace(val x: Int, val y: Int, val z: Int, val table: Int)

/** One chunk being generated, as each [GenerationStage] sees it. */
class ChunkGeneration internal constructor(
    val generator: TerrainGenerator,
    val chunkX: Int,
    val chunkZ: Int,
    val buffer: ChunkBuffer,
    /**
     * The height of the terrain in each column (`localX * 16 + localZ`): the y of its top block (with a density, its
     * topmost solid one, or one below the world's bottom when it has none).
     */
    val surface: IntArray,
    /** The height of each column the file's 2D terrain and the script's `height` stage make: with a density, what it's shaped around. */
    val base: IntArray,
    /** The index of the biome area of each column, in [CompiledTerrain.areas]. */
    val areas: IntArray,
    /** Asks heights and areas of columns outside the chunk (where a vein or a tree that reaches in starts). */
    val sampler: TerrainGenerator.Sampler,
    /** The state the file's script runs in for this chunk, when it has a script. */
    internal val script: TerrainScriptState? = null,
    /** With a density, which blocks the terrain makes solid, laid out as the [buffer]'s. */
    internal val solid: BooleanArray? = null,
    /**
     * When a place's biome can differ from its column's: the area of each 4x4x4 cell (`(qx * 4 + qz) * cells + qy`,
     * its corner's, as [TerrainGenerator.pointAreaAt] says), null otherwise.
     */
    internal val cells: IntArray? = null
) {
    val minX: Int get() = chunkX * 16
    val minZ: Int get() = chunkZ * 16

    /** The area at a place in the chunk: its cell's when places differ from their column, else its column's. */
    fun areaAt(localX: Int, y: Int, localZ: Int): Int {
        val cells = cells ?: return areas[localX * 16 + localZ]
        val height = (buffer.height + 3) / 4
        return cells[((localX shr 2) * 4 + (localZ shr 2)) * height + ((y - buffer.minY) shr 2)]
    }
}

/**
 * One step of generating a chunk. The generator runs [TerrainGenerator.stages]
 * in order over a [ChunkGeneration]: the terrain fills columns, then caves are
 * carved, the floor laid, ores scattered and decorations placed. A stage reads
 * and changes the buffer and nothing else, and only from what the chunk's
 * position and the world's seed say, so a chunk never depends on the ones
 * generated before it. That is also how a script's stages join the others
 * ([TerrainScripts]): they fill ranges of the same buffer.
 */
fun interface GenerationStage {
    fun run(chunk: ChunkGeneration)
}

/** Facts about decorations every part shares. */
object Decorations {
    /** Where a structure may go: places a column's own numbers decide, the same for every chunk the structure reaches. */
    val STRUCTURE_PLACEMENTS = setOf(DecorationPlacement.SURFACE, DecorationPlacement.UNDERWATER, DecorationPlacement.UNDERGROUND)
}

/**
 * A place a decoration was tried at that passed everything the column alone decides: its chance, its area, its
 * height and (on the surface or the sea floor) the block the terrain put on top. A block is then placed only where
 * the chunk's blocks let it (air, or the sea, where it goes; the block below), a structure always.
 */
class DecorationSite(val x: Int, val y: Int, val z: Int, val turn: Int)

/**
 * The generator of one world: a [CompiledTerrain] with the world's [seed] and
 * its height range. Everything is made in the constructor and only read after
 * it, so one generator serves any number of threads at once. A file's script
 * runs in Lua states of its own, one per thread generating at once, which
 * [close] lets go of.
 */
class TerrainGenerator internal constructor(
    val terrain: CompiledTerrain,
    val seed: Long,
    val minY: Int,
    val maxY: Int,
    scripts: TerrainScripts? = null
) {
    private val areas = terrain.areas
    private val columnAreas: IntArray = terrain.columnAreas.toIntArray()
    private val volumeAreas: IntArray = terrain.volumeAreas.toIntArray()
    private val climateNoises: List<FastNoiseLite> = terrain.climate.map {
        it.noise.build(TerrainSeeds.forRole(seed, "climate:noise:${it.name}"))
    }
    private val heightNoises: List<FastNoiseLite> =
        terrain.noises.map { it.noise.build(TerrainSeeds.forRole(seed, "height:${it.name}")) }
    private val areaNoises: List<List<FastNoiseLite>> = areas.map { area ->
        area.terrain.noises.map { it.noise.build(TerrainSeeds.forRole(seed, "area:${area.name}:height:${it.name}")) }
    }
    private val temperatureNoise: FastNoiseLite = terrain.temperature.build(TerrainSeeds.forRole(seed, "climate:temperature"))
    private val humidityNoise: FastNoiseLite = terrain.humidity.build(TerrainSeeds.forRole(seed, "climate:humidity"))
    private val jittered: Boolean = areas.size > 1 && terrain.jitterAmplitude > 0.0
    private val jitterX: FastNoiseLite = terrain.jitter.build(TerrainSeeds.forRole(seed, "climate:jitter:x"))
    private val jitterZ: FastNoiseLite = terrain.jitter.build(TerrainSeeds.forRole(seed, "climate:jitter:z"))

    /** Every area has the same heights (its 3D noises aside): a column's height doesn't depend on its area. */
    private val oneTerrain: Boolean = areas.all {
        it.terrain.base == areas[0].terrain.base &&
            it.terrain.scale == areas[0].terrain.scale &&
            it.terrain.noises == areas[0].terrain.noises
    }
    private val blended: Boolean = !oneTerrain && terrain.blend > 0

    /** The lattice points around a grid point whose areas make its weights, and how much each counts: a tent, highest in the middle. */
    private val kernel: List<Triple<Int, Int, Double>> = run {
        val reach = (terrain.blend + BLEND_CELL - 1) / BLEND_CELL
        val weights = (-reach..reach).map { maxOf(0, terrain.blend + BLEND_CELL - abs(it) * BLEND_CELL).toDouble() }
        val total = weights.sum() * weights.sum()
        (-reach..reach).flatMap { i -> (-reach..reach).map { j -> Triple(i, j, weights[i + reach] * weights[j + reach] / total) } }
            .filter { it.third > 0.0 }
    }

    private val caveNoises: List<Pair<FastNoiseLite, FastNoiseLite?>> = terrain.caves.map {
        it.noise.build(TerrainSeeds.forRole(seed, "cave:${it.name}")) to
            if (it.type == CaveType.SPAGHETTI) it.noise.build(TerrainSeeds.forRole(seed, "cave:${it.name}:b")) else null
    }
    private val caveAreas: List<BooleanArray?> = terrain.caves.map { mask(it.areas) }
    private val oreAreas: List<BooleanArray?> = terrain.ores.map { mask(it.areas) }
    private val floorSeed: Int = TerrainSeeds.forRole(seed, "floor")

    private val decorationSeeds: List<Int> = terrain.decorations.map { TerrainSeeds.forRole(seed, "decoration:${it.name}") }
    private val decorationNoises: List<FastNoiseLite?> =
        terrain.decorations.map { d -> d.noise?.build(TerrainSeeds.forRole(seed, "decoration:${d.name}:noise")) }
    private val decorationAreas: List<BooleanArray?> = terrain.decorations.map { mask(it.areas) }

    /** For each decoration, which palette entries it may sit on, hang from or replace. */
    private val decorationOn: List<BooleanArray> = terrain.decorations.map { d ->
        val palette = terrain.palette
        BooleanArray(palette.size) { i ->
            val block = palette[i]
            when {
                d.on.isNotEmpty() || d.onCustom.isNotEmpty() -> when (block) {
                    is TerrainBlock.Vanilla -> block.state.substringBefore('[') in d.on
                    is TerrainBlock.Custom -> block.name in d.onCustom
                }
                d.placement == DecorationPlacement.UNDERGROUND -> i == terrain.stone
                else -> i != 0 && i != terrain.fluid
            }
        }
    }

    /** How many chunks a decoration's structure may reach beyond the one it starts in (0 for a block). */
    private val decorationReach: List<Int> = terrain.decorations.map { d ->
        val template = d.structure?.let { terrain.templates[it] } ?: return@map 0
        (maxOf(template.sizeX, template.sizeZ) + 15) / 16
    }

    private fun mask(indexes: List<Int>): BooleanArray? =
        if (indexes.isEmpty()) null else BooleanArray(areas.size).also { mask -> indexes.forEach { mask[it] = true } }

    /** The file's script, when it has one and was bound with what it runs with. */
    private val runner: ScriptRunner? = terrain.script?.let { script -> scripts?.let { ScriptRunner(this, script, it) } }

    private val areaStage: Boolean by lazy { runner?.stages?.contains(ScriptRunner.AREA) == true }
    private val biomeStage: Boolean by lazy { runner?.stages?.contains(ScriptRunner.BIOME) == true }

    /** Whether a place's biome can differ from its column's: the file has volume areas, or its script a `biome` stage. */
    val pointAreas: Boolean get() = volumeAreas.isNotEmpty() || biomeStage

    /** The file's 3D terrain, when it has a density. */
    private val density: DensityField? = terrain.density?.let { d ->
        DensityField(terrain, d, seed, minY, maxY) { runner?.stages?.contains(ScriptRunner.DENSITY) == true }
    }

    /** What generating a chunk does, in order: the script's stages after the file's terrain and after its decorations. */
    val stages: List<GenerationStage> = listOf(
        GenerationStage(::terrain),
        GenerationStage { scriptStage(it, ScriptRunner.TERRAIN) },
        GenerationStage(::carve),
        GenerationStage(::floor),
        GenerationStage(::ores),
        GenerationStage(::decorate),
        GenerationStage { scriptStage(it, ScriptRunner.DECORATE) }
    )

    /**
     * Lets go of the Lua states the file's script ran in (the ones a thread is using as it's called go when they're
     * given back). The generator still works afterwards, making a state for each call: call it when nothing will ask
     * it again, as when a reload replaces it.
     */
    fun close() {
        runner?.close()
    }

    /** Makes one of the script's Lua states (its body run) and keeps it for the next call: what [TerrainScripts.check] does. */
    internal fun loadScript() {
        val runner = runner ?: return
        runner.give(runner.take())
    }

    /**
     * Asks the heights and areas of columns, keeping the areas of the points heights are blended from: one
     * serves a chunk, or a picture of many columns. Not for sharing between threads; its answers are the same as
     * a new one's. [script] is the state a chunk's script runs in, which its heights use too; without one, a
     * height the script gives takes a state for the call.
     */
    inner class Sampler internal constructor(private val script: TerrainScriptState? = null) {
        private val lattice = HashMap<Long, Int>()
        private val grid = HashMap<Long, DoubleArray>()
        private val corners = HashMap<Long, CornerColumn>()
        private val columnArea = HashMap<Long, Int>()
        private val surfaces = HashMap<Long, Int>()
        private val fileHeights = HashMap<Long, Int>()

        /** The area of a column: its climate's, then the script's `area` stage. */
        fun areaAt(x: Int, z: Int): Int {
            if (!areaStage) return fileAreaAt(x, z)
            val key = key(x, z)
            columnArea[key]?.let { return it }
            val area = areaAt(x, z, script)
            if (columnArea.size >= CACHE_LIMIT) columnArea.clear()
            columnArea[key] = area
            return area
        }

        /** The area at a place: see [TerrainGenerator.pointAreaAt]. */
        fun pointAreaAt(x: Int, y: Int, z: Int): Int = this@TerrainGenerator.pointAreaAt(x, y, z, this)

        /**
         * The first of [ys] at column ([x], [z]) whose place's area ([pointAreaAt]) is one [wanted] has, or null. Without
         * volume areas or a biome stage every place of a column is its column's area, so that's all it asks.
         */
        fun firstWhere(x: Int, z: Int, ys: IntArray, wanted: BooleanArray): Int? {
            if (!pointAreas) return ys.firstOrNull()?.takeIf { wanted[areaAt(x, z)] }
            for (y in ys) if (wanted[pointAreaAt(x, y, z)]) return y
            return null
        }

        /** [surfaceAt], kept: what a volume's depth and surface are measured from. */
        internal fun keptSurfaceAt(x: Int, z: Int): Int {
            val key = key(x, z)
            surfaces[key]?.let { return it }
            val surface = surfaceAt(x, z)
            if (surfaces.size >= CACHE_LIMIT) surfaces.clear()
            surfaces[key] = surface
            return surface
        }

        internal val scriptState: TerrainScriptState? get() = script

        /**
         * The y of the top block of the terrain at a column: its [baseHeight], or with a density the topmost solid
         * block (one below the world's bottom when the column has none).
         */
        fun surfaceAt(x: Int, z: Int): Int = if (density == null) baseHeight(x, z) else densityColumn(x, z).top()

        /**
         * The y of every top surface of a column (a solid block with nothing solid on it), from the highest down: the
         * ground under an overhang, a ledge, an island. Without a density, only the [surfaceAt].
         */
        fun surfacesAt(x: Int, z: Int): IntArray = if (density == null) intArrayOf(baseHeight(x, z)) else densityColumn(x, z).surfaces()

        /**
         * The height the file's 2D terrain and the script's height stage make at a column, kept inside the world:
         * without a density the top block, with one what the density is shaped around.
         */
        fun baseHeight(x: Int, z: Int): Int {
            val file = fileHeight(x, z)
            val height = runner?.let { scriptHeight(it, x, z, file) } ?: file
            return height.coerceIn(minY, maxY - 2)
        }

        internal fun densityColumn(x: Int, z: Int, height: Int = baseHeight(x, z)): DensityColumn =
            DensityColumn(density!!, height, x, z, ::corner)

        private fun corner(gx: Int, gz: Int): CornerColumn {
            val key = key(gx, gz)
            corners[key]?.let { return it }
            val field = density!!
            val x = gx * DensityField.CELL_XZ
            val z = gz * DensityField.CELL_XZ
            val weights = if (field.weighed) weightsAt(x, z) else null
            val area = if (weights == null && areas.size > 1) areaAt(x, z) else 0
            val stage = runner?.takeIf { field.scripted }?.let { runner ->
                { cx: Int, cy: Int, cz: Int, value: Double -> scriptDensity(runner, cx, cy, cz, value) }
            }
            val column = CornerColumn(field, x, z, weights, field.islandsWeight(weights, area), { baseHeight(x, z) }, stage)
            if (corners.size >= CACHE_LIMIT) corners.clear()
            corners[key] = column
            return column
        }

        /** How much each area counts at a column: blended across borders, or all of it the column's own area's. */
        private fun weightsAt(x: Int, z: Int): DoubleArray {
            if (terrain.blend > 0) return blendWeights(x, z)
            return DoubleArray(areas.size).also { it[areaAt(x, z)] = 1.0 }
        }

        /** What the script's density stage makes of the file's [value] at a point; null when it failed there. */
        private fun scriptDensity(runner: ScriptRunner, x: Int, y: Int, z: Int, value: Double): Double? {
            val state = script ?: runner.take()
            try {
                return if (ScriptRunner.DENSITY in state.stages) state.density(x, y, z, value) else null
            } finally {
                if (script == null) runner.give(state)
            }
        }

        /** What the script's height stage makes of the file's [height] at a column; null when it has none or it failed there. */
        private fun scriptHeight(runner: ScriptRunner, x: Int, z: Int, height: Int): Int? {
            val state = script ?: runner.take()
            try {
                return if ("height" in state.stages) state.height(x, z, height) else null
            } finally {
                if (script == null) runner.give(state)
            }
        }

        /** The y of the top block the file's terrain puts at a column, before the script's height stage, and not kept inside the world. */
        fun fileHeight(x: Int, z: Int): Int {
            // The game asks a column's biome at every 4 blocks of its height, and a biome stage may ask its height each time.
            val key = key(x, z)
            fileHeights[key]?.let { return it }
            val height = newFileHeight(x, z)
            if (fileHeights.size >= CACHE_LIMIT) fileHeights.clear()
            fileHeights[key] = height
            return height
        }

        private fun newFileHeight(x: Int, z: Int): Int {
            val dx = x.toDouble()
            val dz = z.toDouble()
            var fileSum = 0.0
            for ((i, noise) in terrain.noises.withIndex()) fileSum += heightNoises[i].getNoise(dx, dz) * noise.amplitude
            val height = when {
                oneTerrain -> areaHeight(0, dx, dz, fileSum)
                !blended -> areaHeight(areaAt(x, z), dx, dz, fileSum)
                else -> blendedHeight(x, z, fileSum)
            }
            return floor(height).toInt()
        }

        /** The areas' heights weighed by the grid points around the column, each weight smoothly interpolated between them. */
        private fun blendedHeight(x: Int, z: Int, fileSum: Double): Double {
            val weights = blendWeights(x, z)
            var height = 0.0
            for (a in areas.indices) {
                val w = weights[a]
                if (w > 0.0) height += w * areaHeight(a, x.toDouble(), z.toDouble(), fileSum)
            }
            return height
        }

        /** How much each area counts at a column: the weights of the grid points around it, interpolated bilinearly. */
        private fun blendWeights(x: Int, z: Int): DoubleArray {
            val gx = x.floorDiv(BLEND_CELL)
            val gz = z.floorDiv(BLEND_CELL)
            val fx = (x - gx * BLEND_CELL).toDouble() / BLEND_CELL
            val fz = (z - gz * BLEND_CELL).toDouble() / BLEND_CELL
            val w00 = weights(gx, gz)
            val w10 = weights(gx + 1, gz)
            val w01 = weights(gx, gz + 1)
            val w11 = weights(gx + 1, gz + 1)
            return DoubleArray(areas.size) { a ->
                (1 - fx) * (1 - fz) * w00[a] + fx * (1 - fz) * w10[a] + (1 - fx) * fz * w01[a] + fx * fz * w11[a]
            }
        }

        /** How much each area counts at grid point ([gx], [gz]): the share of the lattice points around it in each, by the kernel. */
        private fun weights(gx: Int, gz: Int): DoubleArray {
            val key = key(gx, gz)
            grid[key]?.let { return it }
            val out = DoubleArray(areas.size)
            for ((i, j, w) in kernel) out[latticeArea(gx + i, gz + j)] += w
            if (grid.size >= CACHE_LIMIT) grid.clear()
            grid[key] = out
            return out
        }

        private fun latticeArea(i: Int, j: Int): Int {
            val key = key(i, j)
            lattice[key]?.let { return it }
            val area = areaAt(i * BLEND_CELL, j * BLEND_CELL)
            if (lattice.size >= CACHE_LIMIT) lattice.clear()
            lattice[key] = area
            return area
        }

        private fun key(i: Int, j: Int): Long = (i.toLong() shl 32) or (j.toLong() and 0xFFFFFFFFL)
    }

    /** A new [Sampler]. */
    fun sampler(): Sampler = Sampler()

    private fun areaHeight(area: Int, x: Double, z: Double, fileSum: Double): Double {
        val terrain = areas[area].terrain
        var height = terrain.base + terrain.scale * fileSum
        val own = areaNoises[area]
        for (i in own.indices) height += own[i].getNoise(x, z) * terrain.noises[i].amplitude
        return height
    }

    /** The y of the top block of the terrain at a column. */
    fun surfaceAt(x: Int, z: Int): Int = Sampler().surfaceAt(x, z)

    /**
     * The index in [CompiledTerrain.areas] of the biome area at a column: the climate's, where the jitter moves the
     * column to, then the script's `area` stage. A [Sampler] keeps what it's asked; this asks afresh.
     */
    fun areaAt(x: Int, z: Int): Int = areaAt(x, z, null)

    internal fun areaAt(x: Int, z: Int, script: TerrainScriptState?): Int {
        val file = fileAreaAt(x, z)
        val runner = runner ?: return file
        if (!areaStage) return file
        val state = script ?: runner.take()
        try {
            return state.area(x, z, file) ?: file
        } finally {
            if (script == null) runner.give(state)
        }
    }

    /** Where the climate is read for a column: the column, moved by the jitter. */
    private fun climatePoint(x: Int, z: Int): Pair<Double, Double> {
        var cx = x.toDouble()
        var cz = z.toDouble()
        if (jittered) {
            val dx = jitterX.getNoise(cx, cz) * terrain.jitterAmplitude
            val dz = jitterZ.getNoise(cx, cz) * terrain.jitterAmplitude
            cx += dx
            cz += dz
        }
        return cx to cz
    }

    /** The climate at a column: its temperature, its humidity, then each of the file's own values. */
    fun climateAt(x: Int, z: Int): DoubleArray {
        val (cx, cz) = climatePoint(x, z)
        val out = DoubleArray(2 + climateNoises.size)
        out[0] = temperatureNoise.getNoise(cx, cz)
        out[1] = humidityNoise.getNoise(cx, cz)
        for (i in climateNoises.indices) out[2 + i] = climateNoises[i].getNoise(cx, cz)
        return out
    }

    /** How far [climate] is outside [area]'s box, squared: 0 inside it. */
    private fun climateCost(area: CompiledArea, temperature: Double, humidity: Double, climate: DoubleArray?): Double {
        val dt = outside(temperature, area.temperatureMin, area.temperatureMax)
        val dh = outside(humidity, area.humidityMin, area.humidityMax)
        var cost = dt * dt + dh * dh
        if (climate != null) {
            for (i in climateNoises.indices) {
                val d = outside(climate[2 + i], area.climate[2 * i], area.climate[2 * i + 1])
                cost += d * d
            }
        }
        return cost
    }

    /** The column's area by its climate alone, before the script's `area` stage. */
    fun fileAreaAt(x: Int, z: Int): Int {
        if (columnAreas.size == 1) return columnAreas[0]
        val climate: DoubleArray?
        val temperature: Double
        val humidity: Double
        if (climateNoises.isEmpty()) {
            val (cx, cz) = climatePoint(x, z)
            temperature = temperatureNoise.getNoise(cx, cz)
            humidity = humidityNoise.getNoise(cx, cz)
            climate = null
        } else {
            climate = climateAt(x, z)
            temperature = climate[0]
            humidity = climate[1]
        }
        var best = columnAreas[0]
        var bestCost = Double.MAX_VALUE
        var bestSpan = Double.MAX_VALUE
        for (i in columnAreas) {
            val area = areas[i]
            val cost = climateCost(area, temperature, humidity, climate)
            val span = area.span
            if (cost < bestCost || (cost == bestCost && span < bestSpan)) {
                best = i
                bestCost = cost
                bestSpan = span
            }
        }
        return best
    }

    /**
     * The index in [CompiledTerrain.areas] of the biome area at a place: the most specific volume area whose ranges
     * and climate box hold it exactly, else its column's area, then the script's `biome` stage. A place is read at the
     * corner of its 4x4x4 cell (the game keeps biomes per cell): every place of a cell has the same area.
     */
    fun pointAreaAt(x: Int, y: Int, z: Int, sampler: Sampler = Sampler()): Int {
        val qx = x and -4
        val qy = y and -4
        val qz = z and -4
        val column = sampler.areaAt(qx, qz)
        if (!pointAreas) return column
        var chosen = column
        if (volumeAreas.isNotEmpty()) {
            val surface = sampler.keptSurfaceAt(qx, qz)
            var climate: DoubleArray? = null
            var bestSpan = Double.MAX_VALUE
            for (i in volumeAreas) {
                val area = areas[i]
                if (!area.volume!!.contains(qy, surface)) continue
                val at = climate ?: climateAt(qx, qz).also { climate = it }
                if (climateCost(area, at[0], at[1], at) > 0.0) continue
                if (area.span < bestSpan) {
                    chosen = i
                    bestSpan = area.span
                }
            }
        }
        val runner = runner
        if (runner != null && biomeStage) {
            val state = sampler.scriptState ?: runner.take()
            try {
                chosen = state.biome(qx, qy, qz, chosen) ?: chosen
            } finally {
                if (sampler.scriptState == null) runner.give(state)
            }
        }
        return chosen
    }

    /** The biome at a place, as the file writes it: its [pointAreaAt]'s. */
    fun biomeAt(x: Int, y: Int, z: Int, sampler: Sampler = Sampler()): String = areas[pointAreaAt(x, y, z, sampler)].biome

    // ---- looking for a biome, as `/locate biome` does --------------------------------

    /** Which areas (by index in [CompiledTerrain.areas]) have a biome [wanted] says yes to. */
    fun areasWhere(wanted: (String) -> Boolean): BooleanArray = BooleanArray(areas.size) { wanted(areas[it].biome) }

    /**
     * Runs [block] with a sampler of its own Lua state (the script's, when it has one), given back after: what a
     * thread asking many places in a row uses, so each stage it runs doesn't take a state of the pool's and give it back.
     */
    fun <T> sampling(block: (Sampler) -> T): T {
        val runner = runner ?: return block(Sampler())
        val state = runner.take()
        try {
            return block(Sampler(state))
        } finally {
            runner.give(state)
        }
    }

    /** The heights a column is looked at: [y] first (kept inside the world), then [step] above and below it in turn, out to the world's top and bottom. */
    fun locateHeights(y: Int, step: Int): IntArray {
        val origin = y.coerceIn(minY, maxY - 1)
        return buildList {
            add(origin)
            var d = step
            while (origin + d < maxY || origin - d >= minY) {
                if (origin + d < maxY) add(origin + d)
                if (origin - d >= minY) add(origin - d)
                d += step
            }
        }.toIntArray()
    }

    /**
     * The column nearest ([x], [z]) whose terrain is above the sea, looked for in steps of 8 blocks
     * outwards up to [radius] blocks: where a world's spawn is put. Null when there's only sea.
     */
    fun dryColumnNear(x: Int, z: Int, radius: Int = 512): Pair<Int, Int>? {
        val sampler = Sampler()
        if (sampler.surfaceAt(x, z) >= terrain.seaLevel) return x to z
        var r = STEP
        while (r <= radius) {
            for (d in -r..r step STEP) {
                for ((cx, cz) in listOf(x + d to z - r, x + d to z + r, x - r to z + d, x + r to z + d)) {
                    if (sampler.surfaceAt(cx, cz) >= terrain.seaLevel) return cx to cz
                }
            }
            r += STEP
        }
        return null
    }

    /** The vanilla biome id at a column. */
    fun biomeAt(x: Int, z: Int): String = areas[areaAt(x, z)].biome

    private fun outside(value: Double, min: Double, max: Double): Double = when {
        value < min -> min - value
        value > max -> value - max
        else -> 0.0
    }

    /** Generates chunk ([chunkX], [chunkZ]) into a new buffer. */
    fun generate(chunkX: Int, chunkZ: Int): ChunkBuffer {
        val buffer = ChunkBuffer(minY, maxY)
        generate(chunkX, chunkZ, buffer)
        return buffer
    }

    /** Generates chunk ([chunkX], [chunkZ]) into [buffer], which must be empty and of this world's height. */
    fun generate(chunkX: Int, chunkZ: Int, buffer: ChunkBuffer) {
        require(buffer.minY == minY && buffer.maxY == maxY) { "the buffer isn't this world's height" }
        // The script's state is this chunk's while it's made: its heights and its stages run in it.
        val script = runner?.take()
        try {
            val sampler = Sampler(script)
            val base = IntArray(256)
            val areas = IntArray(256)
            for (lx in 0 until 16) {
                for (lz in 0 until 16) {
                    base[lx * 16 + lz] = sampler.baseHeight(chunkX * 16 + lx, chunkZ * 16 + lz)
                    areas[lx * 16 + lz] = sampler.areaAt(chunkX * 16 + lx, chunkZ * 16 + lz)
                }
            }
            val solid = if (density != null) BooleanArray(buffer.blocks.size) else null
            val surface = if (solid == null) base else IntArray(256)
            if (solid != null) {
                for (lx in 0 until 16) {
                    for (lz in 0 until 16) {
                        surface[lx * 16 + lz] =
                            solidColumn(
                                sampler.densityColumn(chunkX * 16 + lx, chunkZ * 16 + lz, base[lx * 16 + lz]),
                                solid,
                                buffer.columnStart(lx, lz)
                            )
                    }
                }
            }
            val cells = if (pointAreas) cellAreas(chunkX, chunkZ, sampler) else null
            val chunk = ChunkGeneration(this, chunkX, chunkZ, buffer, surface, base, areas, sampler, script, solid, cells)
            for (stage in stages) stage.run(chunk)
        } finally {
            if (script != null) runner?.give(script)
        }
    }

    /** The area of each 4x4x4 cell of a chunk, laid out as [ChunkGeneration.cells]. */
    private fun cellAreas(chunkX: Int, chunkZ: Int, sampler: Sampler): IntArray {
        val height = (maxY - minY + 3) / 4
        val out = IntArray(16 * height)
        for (qx in 0 until 4) {
            for (qz in 0 until 4) {
                for (qy in 0 until height) {
                    out[(qx * 4 + qz) * height + qy] = pointAreaAt(chunkX * 16 + qx * 4, minY + qy * 4, chunkZ * 16 + qz * 4, sampler)
                }
            }
        }
        return out
    }

    /** Whether [mask] (null for every area) lets something be at a place in the chunk: its column's area or its own. */
    private fun allowedAt(mask: BooleanArray?, chunk: ChunkGeneration, localX: Int, y: Int, localZ: Int): Boolean =
        mask == null || mask[chunk.areas[localX * 16 + localZ]] || (chunk.cells != null && mask[chunk.areaAt(localX, y, localZ)])

    /** Marks a column's solid blocks in [solid] from [start] (the column's place in the buffer); answers its topmost one's y. */
    private fun solidColumn(column: DensityColumn, solid: BooleanArray, start: Int): Int {
        var top = minY - 1
        for (y in column.highest() downTo minY) {
            val at = start + y - minY
            if (column.solidBelow(y)) {
                // Everything from here down is solid, whatever the noises say.
                solid.fill(true, start, at + 1)
                return if (top < minY) y else top
            }
            if (column.solid(y)) {
                solid[at] = true
                if (top < minY) top = y
            }
        }
        return top
    }

    /**
     * The script's chunk stage [stage], when it has one. One that fails leaves the chunk as it found it: what it
     * changed is put back, so the chunk is the file's own (the failure is the script's to report, once).
     */
    private fun scriptStage(chunk: ChunkGeneration, stage: String) {
        val script = chunk.script ?: return
        if (stage !in script.stages) return
        val before = chunk.buffer.blocks.copyOf()
        val loot = HashMap(chunk.buffer.loot)
        if (!script.chunkStage(stage, chunk)) {
            before.copyInto(chunk.buffer.blocks)
            chunk.buffer.loot.clear()
            chunk.buffer.loot.putAll(loot)
        }
    }

    /** The layers of a column of [area], under the sea or not. */
    private fun layersOf(area: CompiledArea, sea: Boolean): List<CompiledLayer> =
        (if (sea) area.underwater ?: area.layers else area.layers) ?: (if (sea) terrain.underwater else terrain.layers)

    /** The block the terrain puts at the top of a column of [area] (before caves and ores). */
    private fun topBlock(area: Int, sea: Boolean): Int = layersOf(areas[area], sea).firstOrNull()?.block ?: terrain.stone

    // ---- the stages ---------------------------------------------------------------

    /** Stone, the layers on it and the sea over it. */
    private fun terrain(chunk: ChunkGeneration) {
        if (chunk.solid != null) return densityTerrain(chunk, chunk.solid)
        val w = terrain
        val buffer = chunk.buffer
        for (lx in 0 until 16) {
            for (lz in 0 until 16) {
                val column = lx * 16 + lz
                val top = chunk.surface[column]
                val sea = top < w.seaLevel
                var y = top
                for (layer in layersOf(areas[chunk.areas[column]], sea)) {
                    if (y < minY) break
                    buffer.fill(lx, lz, y - layer.thickness + 1, y, layer.block)
                    y -= layer.thickness
                }
                if (y >= minY) buffer.fill(lx, lz, minY, y, w.stone)
                if (sea) buffer.fill(lx, lz, top + 1, w.seaLevel, w.fluid)
            }
        }
    }

    /**
     * With a density: every solid block, topped by the layers from each surface down (so the top of an overhang, a
     * ledge and an island each get them, and the ground under an overhang too), and the sea in every space at or
     * below its level.
     */
    private fun densityTerrain(chunk: ChunkGeneration, solid: BooleanArray) {
        val w = terrain
        val blocks = chunk.buffer.blocks
        val height = chunk.buffer.height
        for (lx in 0 until 16) {
            for (lz in 0 until 16) {
                val column = lx * 16 + lz
                val start = chunk.buffer.columnStart(lx, lz)
                val area = areas[chunk.areas[column]]
                var layers: List<CompiledLayer> = emptyList()
                var layer = 0
                var left = 0
                for (i in height - 1 downTo 0) {
                    val y = minY + i
                    if (!solid[start + i]) {
                        if (y <= w.seaLevel) blocks[start + i] = w.fluid
                        continue
                    }
                    if (i == height - 1 || !solid[start + i + 1]) {
                        layers = layersOf(area, y < w.seaLevel)
                        layer = 0
                        left = layers.firstOrNull()?.thickness ?: 0
                    }
                    if (layer < layers.size) {
                        blocks[start + i] = layers[layer].block
                        if (--left == 0 && ++layer < layers.size) left = layers[layer].thickness
                    } else {
                        blocks[start + i] = w.stone
                    }
                }
            }
        }
    }

    /** Caves, never nearer the surface than they say, in the areas they name. */
    private fun carve(chunk: ChunkGeneration) {
        if (terrain.caves.isEmpty()) return
        if (chunk.solid != null) return densityCarve(chunk, chunk.solid)
        val buffer = chunk.buffer
        for ((i, cave) in terrain.caves.withIndex()) {
            val (noise, second) = caveNoises[i]
            val allowed = caveAreas[i]
            for (lx in 0 until 16) {
                for (lz in 0 until 16) {
                    // The whole column when its area is allowed; with volumes, the places whose own area is.
                    val column = allowed == null || allowed[chunk.areas[lx * 16 + lz]]
                    if (!column && chunk.cells == null) continue
                    val x = (chunk.minX + lx).toDouble()
                    val z = (chunk.minZ + lz).toDouble()
                    val to = minOf(cave.maxY, chunk.surface[lx * 16 + lz] - cave.depth, maxY - 2)
                    for (y in maxOf(cave.minY, minY + 1)..to) {
                        if (!column && !allowed[chunk.areaAt(lx, y, lz)]) continue
                        if (opens(cave, noise, second, x, y, z)) buffer[lx, y, lz] = 0
                    }
                }
            }
        }
    }

    /**
     * Caves with a density: carved in what the terrain made solid, wherever at least each cave's `depth` of solid
     * blocks is on it (so a cave doesn't open the ground, an overhang's underside or an island).
     */
    private fun densityCarve(chunk: ChunkGeneration, solid: BooleanArray) {
        val buffer = chunk.buffer
        for ((i, cave) in terrain.caves.withIndex()) {
            val (noise, second) = caveNoises[i]
            val allowed = caveAreas[i]
            val from = maxOf(cave.minY, minY + 1)
            val to = minOf(cave.maxY, maxY - 2)
            if (from > to) continue
            for (lx in 0 until 16) {
                for (lz in 0 until 16) {
                    val column = allowed == null || allowed[chunk.areas[lx * 16 + lz]]
                    if (!column && chunk.cells == null) continue
                    val x = (chunk.minX + lx).toDouble()
                    val z = (chunk.minZ + lz).toDouble()
                    val start = buffer.columnStart(lx, lz) - minY
                    // How many solid blocks are on the one at y, counted down from the top of the world.
                    var above = 0
                    for (y in maxY - 1 downTo from) {
                        val here = solid[start + y]
                        if (here && y <= to && above >= cave.depth && (column || allowed[chunk.areaAt(lx, y, lz)])) {
                            if (opens(cave, noise, second, x, y, z)) buffer.blocks[start + y] = 0
                        }
                        above = if (here) above + 1 else 0
                    }
                }
            }
        }
    }

    /** Whether [cave] carves the block at [x], [y], [z], with its threshold there ([CompiledCave.thresholdAt]). */
    private fun opens(cave: CompiledCave, noise: FastNoiseLite, second: FastNoiseLite?, x: Double, y: Int, z: Double): Boolean {
        val threshold = cave.thresholdAt(y)
        return when (cave.type) {
            CaveType.CHEESE -> noise.getNoise(x, y.toDouble(), z) > threshold
            CaveType.SPAGHETTI ->
                abs(noise.getNoise(x, y.toDouble(), z)) < threshold && abs(second!!.getNoise(x, y.toDouble(), z)) < threshold
        }
    }

    /** The bottom of the world: solid at its lowest layer, thinning out above. */
    private fun floor(chunk: ChunkGeneration) {
        val thickness = terrain.floorThickness
        if (thickness <= 0) return
        val buffer = chunk.buffer
        for (lx in 0 until 16) {
            for (lz in 0 until 16) {
                for (offset in 0 until minOf(thickness, buffer.height)) {
                    val y = minY + offset
                    val chance = 1.0 - offset.toDouble() / thickness
                    if (offset == 0 || TerrainSeeds.unit(floorSeed, chunk.minX + lx, y, chunk.minZ + lz) < chance) {
                        buffer[lx, y, lz] = terrain.floorBlock
                    }
                }
            }
        }
    }

    /** The random numbers of one role in one chunk: the same whichever chunk asks. */
    private fun chunkRandom(roleSeed: Int, cx: Int, cz: Int): Random =
        Random(TerrainSeeds.mix(TerrainSeeds.mix(roleSeed xor (cx * 0x1B873593)) xor (cz * 0x2C1B3C6D)))

    /**
     * Veins. Each chunk works out the veins that start in the chunks around it,
     * from their own positions' randomness, and keeps what lands in itself, so
     * a vein crossing a chunk's edge is whole on both sides whichever is
     * generated first.
     */
    private fun ores(chunk: ChunkGeneration) {
        val buffer = chunk.buffer
        for ((index, ore) in terrain.ores.withIndex()) {
            if (ore.veins == 0) continue
            val roleSeed = TerrainSeeds.forRole(seed, "ore:${ore.name}")
            val allowed = oreAreas[index]
            val low = maxOf(ore.minY, minY)
            val high = minOf(ore.maxY, maxY - 1)
            if (low > high) continue
            for (dx in -1..1) {
                for (dz in -1..1) {
                    val cx = chunk.chunkX + dx
                    val cz = chunk.chunkZ + dz
                    val random = chunkRandom(roleSeed, cx, cz)
                    repeat(ore.veins) {
                        val ox = cx * 16 + random.nextInt(16)
                        val oz = cz * 16 + random.nextInt(16)
                        val oy = when (ore.distribution) {
                            OreDistribution.UNIFORM -> low + random.nextInt(high - low + 1)
                            OreDistribution.TRIANGLE -> low + (random.nextInt(high - low + 1) + random.nextInt(high - low + 1) + 1) / 2
                        }
                        // A vein that doesn't start in its areas isn't grown (nor are its numbers drawn: every chunk skips it alike).
                        if (allowed == null ||
                            allowed[chunk.sampler.areaAt(ox, oz)] ||
                            (pointAreas && allowed[chunk.sampler.pointAreaAt(ox, oy, oz)])
                        ) {
                            vein(chunk, buffer, ore, ox, oy, oz, random)
                        }
                    }
                }
            }
        }
    }

    /** One vein grown from a block by adding a neighbour of a random block of it, [CompiledOre.size] blocks in all, none further than a chunk from its start. */
    private fun vein(chunk: ChunkGeneration, buffer: ChunkBuffer, ore: CompiledOre, ox: Int, oy: Int, oz: Int, random: Random) {
        val size = ore.size
        val cells = IntArray(size)
        var count = 1
        cells[0] = pack(0, 0, 0)
        var attempts = 0
        while (count < size && attempts < size * 24) {
            attempts++
            val from = cells[random.nextInt(count)]
            var dx = unpackX(from)
            var dy = unpackY(from)
            var dz = unpackZ(from)
            when (random.nextInt(6)) {
                0 -> dx++
                1 -> dx--
                2 -> dy++
                3 -> dy--
                4 -> dz++
                else -> dz--
            }
            if (abs(dx) > REACH || abs(dy) > REACH || abs(dz) > REACH) continue
            val packed = pack(dx, dy, dz)
            var known = false
            for (i in 0 until count) if (cells[i] == packed) known = true
            if (!known) cells[count++] = packed
        }
        for (i in 0 until count) {
            val x = ox + unpackX(cells[i])
            val y = oy + unpackY(cells[i])
            val z = oz + unpackZ(cells[i])
            val lx = x - chunk.minX
            val lz = z - chunk.minZ
            if (lx !in 0..15 || lz !in 0..15 || y < minY || y >= maxY) continue
            val current = buffer[lx, y, lz]
            if (ore.replace.contains(current)) buffer[lx, y, lz] = ore.block
        }
    }

    /**
     * Where decoration [index] is tried in chunk ([cx], [cz]) for its next try, from [random], or null when the
     * column says no. Every try draws the same five numbers, so the tries after it don't depend on this one's
     * answer, and nothing but the column's own numbers is asked, so every chunk a structure reaches decides alike.
     */
    fun decorationSite(index: Int, cx: Int, cz: Int, random: Random, sampler: Sampler): DecorationSite? {
        val d = terrain.decorations[index]
        val x = cx * 16 + random.nextInt(16)
        val z = cz * 16 + random.nextInt(16)
        val roll = random.nextDouble()
        val pick = random.nextDouble()
        val turn = random.nextInt(4)
        var chance = d.chance
        decorationNoises[index]?.let { noise ->
            val n = noise.getNoise(x.toDouble(), z.toDouble())
            chance *= ((n - d.threshold) / (1.0 - d.threshold)).coerceIn(0.0, 1.0)
        }
        if (roll >= chance) return null
        val area = sampler.areaAt(x, z)
        val allowed = decorationAreas[index]
        // With volumes, a place whose column isn't allowed may still be: its own area is asked once it has a height.
        val column = allowed == null || allowed[area]
        if (!column && !pointAreas) return null
        val low = maxOf(d.minY ?: minY, minY)
        val high = minOf(d.maxY ?: (maxY - 1), maxY - 1)
        val y = when (d.placement) {
            DecorationPlacement.SURFACE, DecorationPlacement.UNDERWATER -> {
                val sea = d.placement == DecorationPlacement.UNDERWATER
                // Any of the column's dry tops (or sea floors), the try's number says which: the ground, a ledge, an island.
                val tops = sampler.surfacesAt(x, z).filter { (it < terrain.seaLevel) == sea }
                if (tops.isEmpty()) return null
                if (!decorationOn[index][topBlock(area, sea)]) return null
                tops[minOf(tops.size - 1, floor(pick * tops.size).toInt())] + 1
            }
            DecorationPlacement.UNDERGROUND -> pickY(low, minOf(high, sampler.surfaceAt(x, z)), pick) ?: return null
            DecorationPlacement.CAVE_FLOOR, DecorationPlacement.CAVE_CEILING ->
                pickY(low, minOf(high, sampler.surfaceAt(x, z) - 1), pick) ?: return null
        }
        if (y !in low..high) return null
        if (!column && !allowed[sampler.pointAreaAt(x, y, z)]) return null
        return DecorationSite(x, y, z, if (d.rotate) turn else 0)
    }

    private fun pickY(low: Int, high: Int, pick: Double): Int? = if (low > high) null else low + floor(pick * (high - low + 1)).toInt()

    /** Decorations, in name order: each chunk places those starting in it and the parts of structures starting near it. */
    private fun decorate(chunk: ChunkGeneration) {
        for ((index, d) in terrain.decorations.withIndex()) {
            if (d.count == 0) continue
            val template = d.structure?.let { terrain.templates[it] }
            if (d.structure != null && template == null) continue
            val reach = decorationReach[index]
            for (cx in chunk.chunkX - reach..chunk.chunkX + reach) {
                for (cz in chunk.chunkZ - reach..chunk.chunkZ + reach) {
                    val random = chunkRandom(decorationSeeds[index], cx, cz)
                    repeat(d.count) {
                        val site = decorationSite(index, cx, cz, random, chunk.sampler)
                        if (site != null) {
                            if (template != null) structure(chunk, template, site, d.loot) else block(chunk, index, d, site)
                        }
                    }
                }
            }
        }
    }

    /** A block decoration at its site, if the chunk's blocks there let it. */
    private fun block(chunk: ChunkGeneration, index: Int, d: CompiledDecoration, site: DecorationSite) {
        val buffer = chunk.buffer
        val lx = site.x - chunk.minX
        val lz = site.z - chunk.minZ
        val on = decorationOn[index]
        fun solid(y: Int) = y in minY until maxY && buffer[lx, y, lz].let { it != 0 && it != terrain.fluid }
        val y = when (d.placement) {
            DecorationPlacement.SURFACE -> site.y.takeIf { it < maxY && buffer[lx, it, lz] == 0 && on[buffer[lx, it - 1, lz]] }
            DecorationPlacement.UNDERWATER ->
                site.y.takeIf { it < maxY && buffer[lx, it, lz] == terrain.fluid && on[buffer[lx, it - 1, lz]] }
            DecorationPlacement.UNDERGROUND -> site.y.takeIf { on[buffer[lx, it, lz]] }
            DecorationPlacement.CAVE_FLOOR -> {
                // The first floor at or below the height tried: air with ground under it.
                var y = site.y
                val low = maxOf(d.minY ?: minY, minY + 1)
                while (y >= low && !(buffer[lx, y, lz] == 0 && solid(y - 1))) y--
                y.takeIf { it >= low && on[buffer[lx, it - 1, lz]] }
            }
            DecorationPlacement.CAVE_CEILING -> {
                var y = site.y
                val high = minOf(d.maxY ?: (maxY - 2), chunk.surface[lx * 16 + lz] - 1, maxY - 2)
                while (y <= high && !(buffer[lx, y, lz] == 0 && solid(y + 1))) y++
                y.takeIf { it <= high && on[buffer[lx, it + 1, lz]] }
            }
        } ?: return
        buffer[lx, y, lz] = d.block
        val at = buffer.columnStart(lx, lz) + y - minY
        if (d.loot >= 0) buffer.loot[at] = d.loot else buffer.loot.remove(at)
    }

    /** The part of a structure decoration at its site that's in this chunk: its lowest layer at the site, centred on it. */
    private fun structure(chunk: ChunkGeneration, template: LinkedTemplate, site: DecorationSite, loot: Int) {
        val turn = site.turn
        val width = if (turn % 2 == 0) template.sizeX else template.sizeZ
        val depth = if (turn % 2 == 0) template.sizeZ else template.sizeX
        val x0 = site.x - width / 2
        val z0 = site.z - depth / 2
        if (x0 + width <= chunk.minX || x0 >= chunk.minX + 16 || z0 + depth <= chunk.minZ || z0 >= chunk.minZ + 16) return
        val states = template.turns[turn]
        val blocks = template.blocks
        val buffer = chunk.buffer
        var i = 0
        while (i < blocks.size) {
            val bx = blocks[i]
            val by = blocks[i + 1]
            val bz = blocks[i + 2]
            val entry = blocks[i + 3]
            val state = states[entry]
            i += 4
            if (state == 0) continue
            val rx = when (turn) {
                0 -> bx
                1 -> template.sizeZ - 1 - bz
                2 -> template.sizeX - 1 - bx
                else -> bz
            }
            val rz = when (turn) {
                0 -> bz
                1 -> bx
                2 -> template.sizeZ - 1 - bz
                else -> template.sizeX - 1 - bx
            }
            val lx = x0 + rx - chunk.minX
            val lz = z0 + rz - chunk.minZ
            val y = site.y + by
            if (lx !in 0..15 || lz !in 0..15 || y < minY || y >= maxY) continue
            buffer[lx, y, lz] = state
            val at = buffer.columnStart(lx, lz) + y - minY
            if (loot >= 0 && template.withEntity.getOrElse(entry) { false }) buffer.loot[at] = loot else buffer.loot.remove(at)
        }
    }

    /**
     * The decoration sites starting in chunk ([cx], [cz]) that the columns decide on alone, for decoration [index]:
     * what the preview's map marks. A block's is then placed where the chunk's blocks let it.
     */
    fun decorationSites(index: Int, cx: Int, cz: Int, sampler: Sampler): List<DecorationSite> {
        val d = terrain.decorations[index]
        val random = chunkRandom(decorationSeeds[index], cx, cz)
        return (0 until d.count).mapNotNull { decorationSite(index, cx, cz, random, sampler) }
    }

    private fun pack(x: Int, y: Int, z: Int): Int = ((x + 64) shl 16) or ((y + 64) shl 8) or (z + 64)

    private fun unpackX(packed: Int): Int = (packed ushr 16) - 64

    private fun unpackY(packed: Int): Int = ((packed ushr 8) and 0xFF) - 64

    private fun unpackZ(packed: Int): Int = (packed and 0xFF) - 64

    companion object {
        /**
         * The columns of ring [r] (a square [r] blocks out, [step] blocks apart) as offsets, in the order the game's
         * `/locate biome` looks at them: x from west to east, each x's whole column of z on the ring's west and
         * east sides and its two ends between.
         */
        fun locateRing(r: Int, step: Int): List<Pair<Int, Int>> = buildList {
            if (r == 0) {
                add(0 to 0)
                return@buildList
            }
            for (dx in -r..r step step) {
                val side = abs(dx) == r
                for (dz in -r..r step step) if (side || abs(dz) == r) add(dx to dz)
            }
        }

        /** How far a vein reaches from where it starts along any axis: less than a chunk, which is what lets neighbours be generated alone. */
        private const val REACH = 15

        /** How far apart [dryColumnNear] looks. */
        private const val STEP = 8

        /** The spacing of the points biome areas are read at for blending heights, and of the grid their weights are kept on. */
        const val BLEND_CELL = 8

        /** How many points a [Sampler] keeps before it starts again. */
        private const val CACHE_LIMIT = 1 shl 16
    }
}
