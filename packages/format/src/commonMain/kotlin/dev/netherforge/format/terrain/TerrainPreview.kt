package dev.netherforge.format.terrain

import dev.netherforge.format.editor.TerrainMap
import dev.netherforge.format.editor.TerrainPaletteEntry
import dev.netherforge.format.editor.TerrainScriptError
import dev.netherforge.format.editor.TerrainSlice

/**
 * What the editor draws of a generator: a top-down map of heights and biome areas, and a vertical slice
 * of real chunks. Both ask the same [TerrainGenerator] the server runs, so a preview is the
 * world a server with that seed makes. The map reads only each column's height and area (the two
 * questions a column answers by itself); the slice generates whole chunks.
 */
object TerrainPreview {
    /** The widest map asked for in one call: the editor draws far less, and a mistake can't take long. */
    const val MAX_CELLS = 256

    /** The widest slice asked for in one call: 256 chunks, what `netherforge preview --width` allows. */
    const val MAX_SLICE = 4096

    /** The most decoration tries a map works out: a close map's (a few hundred chunks); a far one shows none. */
    const val MAX_DECORATION_TRIES = 100_000L

    /**
     * A [cells] by [cells] map of the terrain, a cell every [step] blocks, whose first is the column ([x0], [z0]).
     * [scriptErrors] says, once it's drawn, how the file's script failed while it was.
     */
    fun map(
        generator: TerrainGenerator,
        x0: Int,
        z0: Int,
        cells: Int,
        step: Int,
        scriptErrors: () -> List<TerrainScriptError> = { emptyList() }
    ): TerrainMap {
        val n = cells.coerceIn(1, MAX_CELLS)
        val s = step.coerceAtLeast(1)
        val heights = ArrayList<Int>(n * n)
        val areas = ArrayList<Int>(n * n)
        val sampler = generator.sampler()
        for (row in 0 until n) {
            for (col in 0 until n) {
                val x = x0 + col * s
                val z = z0 + row * s
                heights += sampler.surfaceAt(x, z)
                areas += sampler.areaAt(x, z)
            }
        }
        val terrain = generator.terrain
        // Where the surface's and the sea floor's decorations start, in the chunks the map covers, when there are few enough tries.
        // A structure that wasn't linked in isn't placed, so it isn't marked either.
        val shown = terrain.decorations.withIndex().filter { (_, d) ->
            (d.placement == DecorationPlacement.SURFACE || d.placement == DecorationPlacement.UNDERWATER) &&
                (d.structure == null || d.structure in terrain.templates)
        }
        val chunksFrom = x0.floorDiv(16) to z0.floorDiv(16)
        val chunksTo = (x0 + n * s - 1).floorDiv(16) to (z0 + n * s - 1).floorDiv(16)
        val chunkCount = (chunksTo.first - chunksFrom.first + 1).toLong() * (chunksTo.second - chunksFrom.second + 1)
        val tries = chunkCount * shown.sumOf { it.value.count }
        val decorationsShown = tries <= MAX_DECORATION_TRIES
        val decorations = ArrayList<Int>()
        if (decorationsShown) {
            for ((index, _) in shown) {
                for (cx in chunksFrom.first..chunksTo.first) {
                    for (cz in chunksFrom.second..chunksTo.second) {
                        for (site in generator.decorationSites(index, cx, cz, sampler)) {
                            val col = (site.x - x0).floorDiv(s)
                            val row = (site.z - z0).floorDiv(s)
                            if (col in 0 until n && row in 0 until n) {
                                decorations += row * n + col
                                decorations += index
                            }
                        }
                    }
                }
            }
        }
        return TerrainMap(
            x0,
            z0,
            n,
            s,
            heights,
            areas,
            terrain.areas.map { it.name },
            terrain.areas.map { it.biome },
            terrain.seaLevel,
            generator.minY,
            generator.maxY,
            terrain.decorations.map { it.name },
            decorations,
            decorationsShown,
            scriptErrors()
        )
    }

    /**
     * The blocks along a line: [width] columns from block [from] along the x axis at z [at] (when [alongX]),
     * or along z at x [at]. Each column is its blocks from the bottom as pairs of a palette index and how many
     * in a row, air included. [scriptErrors] is as [map]'s.
     */
    fun slice(
        generator: TerrainGenerator,
        alongX: Boolean,
        at: Int,
        from: Int,
        width: Int,
        scriptErrors: () -> List<TerrainScriptError> = { emptyList() }
    ): TerrainSlice {
        val w = width.coerceIn(1, MAX_SLICE)
        // A line's columns go through each chunk once, one after another, so only the chunk they're in is kept.
        var chunk: ChunkBuffer? = null
        var chunkAlong = 0
        val columns = ArrayList<List<Int>>(w)
        for (i in 0 until w) {
            val along = from + i
            val x = if (alongX) along else at
            val z = if (alongX) at else along
            if (chunk == null || along shr 4 != chunkAlong) {
                chunk = generator.generate(x shr 4, z shr 4)
                chunkAlong = along shr 4
            }
            val buffer = chunk
            val start = buffer.columnStart(x and 15, z and 15)
            val runs = ArrayList<Int>()
            var current = buffer.blocks[start]
            var length = 0
            for (y in 0 until buffer.height) {
                val block = buffer.blocks[start + y]
                if (block == current) {
                    length++
                } else {
                    runs += current
                    runs += length
                    current = block
                    length = 1
                }
            }
            runs += current
            runs += length
            columns += runs
        }
        val terrain = generator.terrain
        return TerrainSlice(
            if (alongX) "x" else "z",
            from,
            at,
            w,
            generator.minY,
            generator.maxY,
            terrain.palette.map { TerrainPaletteEntry(it.label, it is TerrainBlock.Custom) },
            columns,
            terrain.seaLevel,
            scriptErrors()
        )
    }
}
