package dev.netherforge.plugin.centity

import dev.netherforge.format.game.Box
import dev.netherforge.format.math.Matrix4
import dev.netherforge.format.math.Vec3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Where a hitbox is clickable, given that Minecraft's interaction entities
 * are axis-aligned boxes with a square footprint and no transform of their
 * own.
 *
 * [bounds] is the broad phase the server can spawn: the extent of the node's
 * boxes after its world transform, widened to a square. A rotated or oblong
 * box gets padding that's clickable too. [distance] is the narrow phase: the
 * player's ray is pushed through the node's inverse world matrix and meets
 * the boxes in node space, where they're still axis-aligned at their authored
 * size. That handles rotation, non-uniform scale and parent shear exactly.
 * Because the inverse is linear, distances come back in world units and
 * compare across nodes.
 *
 * All positions are relative to the instance's anchor, the frame world
 * matrices compose into.
 */
object Hitboxes {
    /** What a hitbox with no boxes covers: the unit cube a block display fills. */
    val UNIT_CUBE = listOf(Box(Vec3.ZERO, Vec3.ONE))

    /** The sliver a degenerate box stays clickable at, on the server and in the ray test alike. */
    const val MIN_SIZE = 0.01

    /** How near the anchor, in blocks, a hitbox counts as standing at it ([Bounds.atAnchor]). */
    const val AT_ANCHOR = 1e-6

    private const val PARALLEL = 1e-12

    data class Bounds(val centerX: Double, val bottomY: Double, val centerZ: Double, val width: Double, val height: Double) {
        fun box(): Box = Box(
            Vec3(centerX - width / 2, bottomY, centerZ - width / 2),
            Vec3(centerX + width / 2, bottomY + height, centerZ + width / 2)
        )

        /**
         * Whether it stands right at the anchor (centred on it, its bottom at
         * its height): where an interaction entity riding the anchor entity is.
         */
        fun atAnchor(): Boolean = abs(centerX) < AT_ANCHOR && abs(bottomY) < AT_ANCHOR && abs(centerZ) < AT_ANCHOR
    }

    fun bounds(boxes: List<Box>, world: Matrix4): Bounds {
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var minZ = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        var maxZ = -Double.MAX_VALUE
        for (box in boxes) {
            for (corner in 0 until 8) {
                val p = world.transformPosition(
                    Vec3(
                        if (corner and 1 == 0) box.min.x else box.max.x,
                        if (corner and 2 == 0) box.min.y else box.max.y,
                        if (corner and 4 == 0) box.min.z else box.max.z
                    )
                )
                minX = minOf(minX, p.x)
                minY = minOf(minY, p.y)
                minZ = minOf(minZ, p.z)
                maxX = maxOf(maxX, p.x)
                maxY = maxOf(maxY, p.y)
                maxZ = maxOf(maxZ, p.z)
            }
        }
        if (minX > maxX) return Bounds(0.0, 0.0, 0.0, MIN_SIZE, MIN_SIZE)
        return Bounds(
            centerX = (minX + maxX) / 2,
            bottomY = minY,
            centerZ = (minZ + maxZ) / 2,
            width = max(max(maxX - minX, maxZ - minZ), MIN_SIZE),
            height = max(maxY - minY, MIN_SIZE)
        )
    }

    /**
     * Where a ray meets a hitbox: how far along it (in units of the ray's
     * direction), and the unit normal of the face it entered through, in the
     * frame the ray was given in. A ray starting inside hits at 0, and its
     * normal points back along the ray.
     */
    data class Hit(val distance: Double, val normal: Vec3)

    /** How far along the ray the nearest of [boxes] is hit, or null for a miss. A ray starting inside hits at 0. */
    fun distance(boxes: List<Box>, world: Matrix4, origin: Vec3, direction: Vec3, maxDistance: Double): Double? =
        hit(boxes, world, origin, direction, maxDistance)?.distance

    /** The same against the interaction entity itself, for a node that doesn't ask for the narrow phase. */
    fun distance(bounds: Bounds, origin: Vec3, direction: Vec3, maxDistance: Double): Double? =
        hit(bounds, origin, direction, maxDistance)?.distance

    /** The nearest of [boxes] the ray hits, with the face's normal turned out of node space by [world]. */
    fun hit(boxes: List<Box>, world: Matrix4, origin: Vec3, direction: Vec3, maxDistance: Double): Hit? {
        // A node scaled to zero on some axis has no inverse, and nothing to hit.
        val inverse = world.invertAffine() ?: return null
        val localOrigin = inverse.transformPosition(origin)
        val localDirection = inverse.transformDirection(direction)
        val nearest = boxes.mapNotNull { slab(it, localOrigin, localDirection, maxDistance) }.minByOrNull { it.distance } ?: return null
        // Normals turn by the inverse transpose, so a stretched box's faces stay perpendicular.
        val n = nearest.normal
        val v = inverse.values
        val turned = Vec3(
            v[0] * n.x + v[1] * n.y + v[2] * n.z,
            v[4] * n.x + v[5] * n.y + v[6] * n.z,
            v[8] * n.x + v[9] * n.y + v[10] * n.z
        )
        return Hit(nearest.distance, unit(turned) ?: unit(direction * -1.0) ?: Vec3(0.0, 1.0, 0.0))
    }

    /** The same against the interaction entity itself. */
    fun hit(bounds: Bounds, origin: Vec3, direction: Vec3, maxDistance: Double): Hit? = slab(bounds.box(), origin, direction, maxDistance)

    private fun unit(v: Vec3): Vec3? {
        val length = sqrt(v.x * v.x + v.y * v.y + v.z * v.z)
        return if (length < PARALLEL) null else Vec3(v.x / length, v.y / length, v.z / length)
    }

    private fun slab(box: Box, origin: Vec3, direction: Vec3, maxDistance: Double): Hit? {
        var enter = 0.0
        var exit = maxDistance
        // The face entered through: the axis whose entry was last, against the ray.
        var normal = unit(direction * -1.0) ?: Vec3(0.0, 1.0, 0.0)
        val axes = listOf(
            Triple(origin.x, direction.x, box.min.x to max(box.max.x, box.min.x + MIN_SIZE)),
            Triple(origin.y, direction.y, box.min.y to max(box.max.y, box.min.y + MIN_SIZE)),
            Triple(origin.z, direction.z, box.min.z to max(box.max.z, box.min.z + MIN_SIZE))
        )
        for ((axis, entry) in axes.withIndex()) {
            val (from, along, range) = entry
            val (low, high) = range
            if (abs(along) < PARALLEL) {
                if (from < low || from > high) return null
                continue
            }
            val first = (low - from) / along
            val second = (high - from) / along
            val near = minOf(first, second)
            if (near > enter) {
                enter = near
                val sign = if (along > 0) -1.0 else 1.0
                normal = when (axis) {
                    0 -> Vec3(sign, 0.0, 0.0)
                    1 -> Vec3(0.0, sign, 0.0)
                    else -> Vec3(0.0, 0.0, sign)
                }
            }
            exit = minOf(exit, maxOf(first, second))
            if (enter > exit) return null
        }
        return Hit(enter, normal)
    }
}
