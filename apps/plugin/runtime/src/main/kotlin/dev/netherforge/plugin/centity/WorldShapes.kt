package dev.netherforge.plugin.centity

import dev.netherforge.format.game.Box
import dev.netherforge.plugin.pathing.BlockShapes
import dev.netherforge.plugin.platform.WorldOps
import kotlin.math.floor

/**
 * The blocks of one region of a world, read from the platform in one
 * [WorldOps.collisionBoxes] call and filed by the block each box belongs to:
 * what one tick of a walk needs. A block outside the region is asked for on
 * its own. Blocks in chunks that aren't loaded answer null, never loaded.
 * Kept for one look only: the world changes between ticks.
 */
internal class RegionShapes(
    private val worlds: WorldOps,
    private val world: String,
    private val x0: Int,
    private val y0: Int,
    private val z0: Int,
    x1: Int,
    y1: Int,
    z1: Int
) : BlockShapes {
    private val sizeX = x1 - x0 + 1
    private val sizeY = y1 - y0 + 1
    private val sizeZ = z1 - z0 + 1

    /** Each block's boxes, x then z then y; null for a block whose chunk isn't loaded. */
    private val cells = arrayOfNulls<List<Box>>(sizeX * sizeY * sizeZ)

    init {
        val loaded = HashMap<Long, Boolean>()
        for (x in 0 until sizeX) {
            for (z in 0 until sizeZ) {
                val cx = (x0 + x) shr 4
                val cz = (z0 + z) shr 4
                val isLoaded = loaded.getOrPut((cx.toLong() shl 32) or (cz.toLong() and 0xffffffffL)) {
                    worlds.isChunkLoaded(world, cx, cz)
                }
                if (!isLoaded) continue
                for (y in 0 until sizeY) cells[index(x, y, z)] = emptyList()
            }
        }
        val boxes = worlds.collisionBoxes(
            world,
            x0.toDouble(),
            y0.toDouble(),
            z0.toDouble(),
            x1 + 1 - INSIDE,
            y1 + 1 - INSIDE,
            z1 + 1 - INSIDE
        )
        for (box in boxes) {
            // A block's boxes start inside it (a fence's only reach up out of it).
            val x = floor(box.min.x + INSIDE).toInt() - x0
            val y = floor(box.min.y + INSIDE).toInt() - y0
            val z = floor(box.min.z + INSIDE).toInt() - z0
            if (x !in 0 until sizeX || y !in 0 until sizeY || z !in 0 until sizeZ) continue
            val at = index(x, y, z)
            val known = cells[at] ?: continue
            cells[at] = if (known.isEmpty()) listOf(box) else known + box
        }
    }

    private fun index(x: Int, y: Int, z: Int) = (x * sizeZ + z) * sizeY + y

    override fun boxes(x: Int, y: Int, z: Int): List<Box>? {
        val rx = x - x0
        val ry = y - y0
        val rz = z - z0
        if (rx in 0 until sizeX && ry in 0 until sizeY && rz in 0 until sizeZ) return cells[index(rx, ry, rz)]
        if (!worlds.isChunkLoaded(world, x shr 4, z shr 4)) return null
        return worlds.collisionBoxes(world, x.toDouble(), y.toDouble(), z.toDouble(), x + 1 - INSIDE, y + 1 - INSIDE, z + 1 - INSIDE)
    }

    companion object {
        /** Pulls a region's far faces in, so the platform's whole-block loops stop at the last block. */
        private const val INSIDE = 1e-6

        /** The blocks around the region from ([minX], [minY], [minZ]) to ([maxX], [maxY], [maxZ]), and the block below it. */
        fun around(worlds: WorldOps, world: String, minX: Double, minY: Double, minZ: Double, maxX: Double, maxY: Double, maxZ: Double) =
            RegionShapes(
                worlds,
                world,
                floor(minX).toInt(),
                floor(minY).toInt() - 1,
                floor(minZ).toInt(),
                floor(maxX).toInt(),
                floor(maxY).toInt(),
                floor(maxZ).toInt()
            )
    }
}

/**
 * A world's blocks for one search: read [BRICK] blocks a side at a time (a
 * brick never crosses a chunk) as the search reaches them, and kept until it's
 * done. A search looks at the same blocks over and over; the world can't
 * change while it runs.
 */
internal class BrickShapes(private val worlds: WorldOps, private val world: String) : BlockShapes {
    private val bricks = HashMap<Long, RegionShapes>()

    override fun boxes(x: Int, y: Int, z: Int): List<Box>? {
        val bx = Math.floorDiv(x, BRICK)
        val by = Math.floorDiv(y, BRICK)
        val bz = Math.floorDiv(z, BRICK)
        val key = ((bx.toLong() and MASK) shl 42) or ((by.toLong() and MASK) shl 21) or (bz.toLong() and MASK)
        val brick = bricks.getOrPut(key) {
            RegionShapes(
                worlds,
                world,
                bx * BRICK,
                by * BRICK,
                bz * BRICK,
                bx * BRICK + BRICK - 1,
                by * BRICK + BRICK - 1,
                bz * BRICK + BRICK - 1
            )
        }
        return brick.boxes(x, y, z)
    }

    private companion object {
        const val BRICK = 8
        const val MASK = (1L shl 21) - 1
    }
}
