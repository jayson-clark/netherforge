package dev.netherforge.plugin.pathing

import dev.netherforge.format.game.Box
import kotlin.math.floor
import kotlin.math.max

/**
 * The world's solid geometry as a path search sees it: each block's collision
 * boxes, the live shapes physics lands on (a fence is 1.5 tall, a torch is
 * nothing), never a client-only fact.
 */
fun interface BlockShapes {
    /**
     * The collision boxes of the block at ([x], [y], [z]), in world
     * coordinates: empty for air, null when its chunk isn't loaded (which a
     * walker treats as a wall, never loading anything).
     */
    fun boxes(x: Int, y: Int, z: Int): List<Box>?
}

/**
 * The box that walks: a square footprint [width] blocks across, centred on
 * its feet, and [height] blocks up from them. It doesn't turn: a centity is
 * taken as square whichever way it faces.
 */
data class Walker(val width: Double, val height: Double) {
    /**
     * Where the grid's cells are centred along x and z: on block centres for a
     * walker one, three, … blocks wide, on block corners for two, four, …, so a
     * walker as wide as a gap is centred in it.
     */
    val offset: Double = if (kotlin.math.ceil(width - Space.EPS).toInt() % 2 == 0) 0.0 else 0.5
}

/**
 * Questions about where a [Walker] fits among the blocks [shapes] gives: the
 * two every search, smoothing pass and step asks. Each block's boxes are
 * assumed to start inside it (only reaching up out of it, as a fence's do), so
 * the block below a region is always looked at too.
 */
class Space(private val shapes: BlockShapes, val walker: Walker) {
    private val half = walker.width / 2
    private val height = walker.height

    /**
     * Whether no block's box overlaps the region from ([minX], [minY],
     * [minZ]) to ([maxX], [maxY], [maxZ]), touching faces aside, and every
     * block it touches is loaded.
     */
    fun clear(minX: Double, minY: Double, minZ: Double, maxX: Double, maxY: Double, maxZ: Double): Boolean {
        val x1 = floor(maxX - EPS).toInt()
        val y1 = floor(maxY - EPS).toInt()
        val z1 = floor(maxZ - EPS).toInt()
        for (x in floor(minX + EPS).toInt()..x1) {
            for (z in floor(minZ + EPS).toInt()..z1) {
                for (y in floor(minY + EPS).toInt() - 1..y1) {
                    val boxes = shapes.boxes(x, y, z) ?: return false
                    for (box in boxes) {
                        if (box.min.x < maxX - EPS &&
                            box.max.x > minX + EPS &&
                            box.min.y < maxY - EPS &&
                            box.max.y > minY + EPS &&
                            box.min.z < maxZ - EPS &&
                            box.max.z > minZ + EPS
                        ) {
                            return false
                        }
                    }
                }
            }
        }
        return true
    }

    /** Whether the walker, feet at ([x], [z]), has room from [from] up to [to]. */
    fun clearAt(x: Double, z: Double, from: Double, to: Double): Boolean = clear(x - half, from, z - half, x + half, to, z + half)

    /**
     * Whether the walker can move straight from feet at ([x1], [z1]) to
     * ([x2], [z2]) at a height of [from] to [to]: the box around both
     * footprints is clear, so it never cuts a corner.
     */
    fun clearBetween(x1: Double, z1: Double, x2: Double, z2: Double, from: Double, to: Double): Boolean =
        clear(minOf(x1, x2) - half, from, minOf(z1, z2) - half, maxOf(x1, x2) + half, to, maxOf(z1, z2) + half)

    /** The same in three dimensions, for flying: the box around the walker at both places. */
    fun clearFlying(x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double): Boolean = clear(
        minOf(x1, x2) - half,
        minOf(y1, y2),
        minOf(z1, z2) - half,
        maxOf(x1, x2) + half,
        maxOf(y1, y2) + height,
        maxOf(z1, z2) + half
    )

    /** Reused by [floorAt]: one search runs on one thread. */
    private var tops = DoubleArray(16)

    /**
     * The highest height from [fromY] − [down] to [fromY] + [up] the walker
     * can stand at with its feet at ([x], [z]): the top of a box under its
     * footprint, with room above it up to whichever is higher of it and
     * [fromY], plus its height (so a drop needs the whole way down clear, and
     * a step up room for its head). NaN when there's none, or a block it
     * needs isn't loaded.
     */
    fun floorAt(x: Double, z: Double, fromY: Double, up: Double, down: Double): Double {
        val minX = x - half
        val maxX = x + half
        val minZ = z - half
        val maxZ = z + half
        val low = fromY - down
        val high = fromY + up
        var count = 0
        for (bx in floor(minX + EPS).toInt()..floor(maxX - EPS).toInt()) {
            for (bz in floor(minZ + EPS).toInt()..floor(maxZ - EPS).toInt()) {
                for (by in floor(low - EPS).toInt() - 1..floor(high + EPS).toInt()) {
                    val boxes = shapes.boxes(bx, by, bz) ?: return Double.NaN
                    for (box in boxes) {
                        val top = box.max.y
                        if (top < low - EPS || top > high + EPS) continue
                        if (box.min.x >= maxX - EPS ||
                            box.max.x <= minX + EPS ||
                            box.min.z >= maxZ - EPS ||
                            box.max.z <= minZ + EPS
                        ) {
                            continue
                        }
                        if (count == tops.size) tops = tops.copyOf(count * 2)
                        tops[count++] = top
                    }
                }
            }
        }
        if (count == 0) return Double.NaN
        java.util.Arrays.sort(tops, 0, count)
        var previous = Double.NaN
        for (i in count - 1 downTo 0) {
            val top = tops[i]
            if (top == previous) continue
            previous = top
            if (clearAt(x, z, top, max(top, fromY) + height)) return top
        }
        return Double.NaN
    }

    /** Whether something under the footprint at ([x], [z]) holds the walker up at exactly [y], with room above. */
    fun supported(x: Double, z: Double, y: Double): Boolean = !floorAt(x, z, y, 0.0, 0.0).isNaN()

    companion object {
        /** Faces closer than this touch rather than overlap. */
        const val EPS = 1e-4
    }
}
