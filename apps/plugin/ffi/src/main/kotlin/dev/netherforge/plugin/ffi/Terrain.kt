package dev.netherforge.plugin.ffi

import dev.netherforge.plugin.platform.terrain.GeneratedChunk
import dev.netherforge.plugin.platform.terrain.GeneratedLoot
import dev.netherforge.plugin.platform.terrain.TerrainSource
import dev.netherforge.plugin.platform.terrain.TerrainWorld

// A project generator of the Rust runtime's, which the server's chunk threads call: its wire is written by hand here
// and in crates/ffi's terrain.rs (the methods by number, in that file's order), since the generated glue's objects
// live on the main thread and this one answers any thread.

/** The Rust runtime's generators only cross from Rust: one going back is the same reference. */
internal fun putTerrainSource(w: Writer, v: TerrainSource) {
    val rust = v as? RustTerrainSource ?: throw IllegalArgumentException("only the Rust runtime's generators go to it")
    w.rust(rust.rustId)
}

internal fun takeTerrainSource(r: Reader): TerrainSource = RustTerrainSource(r.rust())

private fun Writer.world(world: TerrainWorld) {
    long(world.seed)
    int(world.minY)
    int(world.maxY)
}

/** One of the Rust runtime's generators, called from whichever thread asks. */
internal class RustTerrainSource(val rustId: Long) : TerrainSource {
    init {
        Native.track(this, rustId)
    }

    // What doesn't change for a generator is asked once.
    override val stone: String by lazy { Native.call(rustId, 0, {}) { it.string() } }
    override val seaLevel: Int by lazy { Native.call(rustId, 1, {}) { it.int() } }
    override val vanillaStructures: Boolean by lazy { Native.call(rustId, 2, {}) { it.bool() } }
    override val biomes: List<String> by lazy { Native.call(rustId, 3, {}) { r -> r.list { r.string() } } }
    override val hasLoot: Boolean by lazy { Native.call(rustId, 4, {}) { it.bool() } }

    override fun generate(world: TerrainWorld, chunkX: Int, chunkZ: Int): GeneratedChunk = Native.call(rustId, 5, { w ->
        w.world(world)
        w.int(chunkX)
        w.int(chunkZ)
    }) { r ->
        val minY = r.int()
        val height = r.int()
        val blocks = r.ints()
        val loot = r.list { GeneratedLoot(r.int(), r.int(), r.int(), r.string()) }
        GeneratedChunk(minY, height, blocks, loot)
    }

    override fun surfaceAt(world: TerrainWorld, x: Int, z: Int): Int = Native.call(rustId, 6, { w ->
        w.world(world)
        w.int(x)
        w.int(z)
    }) { it.int() }

    override fun dryColumnNear(world: TerrainWorld, x: Int, z: Int): Pair<Int, Int>? = Native.call(rustId, 7, { w ->
        w.world(world)
        w.int(x)
        w.int(z)
    }) { r -> r.opt { r.int() to r.int() } }

    override fun biomeAt(world: TerrainWorld, x: Int, y: Int, z: Int): String = Native.call(rustId, 8, { w ->
        w.world(world)
        w.int(x)
        w.int(y)
        w.int(z)
    }) { it.string() }

    override fun locateHeights(world: TerrainWorld, y: Int, step: Int): IntArray = Native.call(rustId, 9, { w ->
        w.world(world)
        w.int(y)
        w.int(step)
    }) { it.ints() }

    override fun firstWhere(world: TerrainWorld, columns: IntArray, ys: IntArray, wanted: Set<String>): Pair<Int, Int>? =
        Native.call(rustId, 10, { w ->
            w.world(world)
            w.count(columns.size / 2)
            for (k in 0 until columns.size / 2) {
                w.int(columns[2 * k])
                w.int(columns[2 * k + 1])
            }
            w.ints(ys)
            w.count(wanted.size)
            for (biome in wanted) w.string(biome)
        }) { r -> r.opt { r.int() to r.int() } }

    override fun close() = Native.call(rustId, 11, {}) { }
}
