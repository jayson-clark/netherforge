package dev.netherforge.plugin.physics

import dev.netherforge.format.game.Box
import dev.netherforge.format.math.Matrix4
import dev.netherforge.format.math.Vec3

/*
 * Axis-aligned box arithmetic, shared by the solver and by whoever builds its
 * inputs. [Box] is reused rather than a physics-only type: block and hitbox
 * geometry already arrive as one.
 */

internal const val AXIS_X = 0
internal const val AXIS_Y = 1
internal const val AXIS_Z = 2

/** The box's lower bound along [axis]: 0 is X, 1 is Y, 2 is Z. */
internal fun Box.min(axis: Int): Double = when (axis) {
    AXIS_X -> min.x
    AXIS_Y -> min.y
    else -> min.z
}

internal fun Box.max(axis: Int): Double = when (axis) {
    AXIS_X -> max.x
    AXIS_Y -> max.y
    else -> max.z
}

internal fun Box.translate(x: Double, y: Double, z: Double): Box {
    val by = Vec3(x, y, z)
    return Box(min + by, max + by)
}

/** Grows the box outwards by [by] on every axis. */
internal fun Box.expand(by: Double): Box {
    val grow = Vec3(by, by, by)
    return Box(min - grow, max + grow)
}

/** The box grown to also contain itself displaced by ([dx], [dy], [dz]): every position it passes through. */
internal fun Box.sweep(dx: Double, dy: Double, dz: Double): Box = Box(
    Vec3(min.x + minOf(dx, 0.0), min.y + minOf(dy, 0.0), min.z + minOf(dz, 0.0)),
    Vec3(max.x + maxOf(dx, 0.0), max.y + maxOf(dy, 0.0), max.z + maxOf(dz, 0.0))
)

/**
 * Whether the two boxes overlap along one axis by more than [tolerance].
 *
 * The tolerance stops a body resting exactly on a surface from also counting
 * as touching the surface's *sides*, which would wedge it against a flat floor
 * made of separate blocks.
 */
internal fun Box.overlaps(other: Box, axis: Int, tolerance: Double): Boolean =
    min(axis) < other.max(axis) - tolerance && max(axis) > other.min(axis) + tolerance

/**
 * The axis-aligned extent of [boxes] once [matrix] has been applied to them.
 *
 * Unlike [dev.netherforge.plugin.centity.Hitboxes.bounds] this keeps the two
 * horizontal axes apart: an interaction entity has to be square, a collider
 * doesn't, and squaring one would make a plank collide as a post.
 */
fun boundsOf(boxes: List<Box>, matrix: Matrix4): Box {
    var minX = Double.MAX_VALUE
    var maxX = -Double.MAX_VALUE
    var minY = Double.MAX_VALUE
    var maxY = -Double.MAX_VALUE
    var minZ = Double.MAX_VALUE
    var maxZ = -Double.MAX_VALUE

    for (box in boxes) {
        for (corner in 0 until 8) {
            val p = matrix.transformPosition(
                Vec3(
                    if (corner and 1 == 0) box.min.x else box.max.x,
                    if (corner and 2 == 0) box.min.y else box.max.y,
                    if (corner and 4 == 0) box.min.z else box.max.z
                )
            )
            minX = minOf(minX, p.x)
            maxX = maxOf(maxX, p.x)
            minY = minOf(minY, p.y)
            maxY = maxOf(maxY, p.y)
            minZ = minOf(minZ, p.z)
            maxZ = maxOf(maxZ, p.z)
        }
    }

    // No boxes leaves the sentinels, which would be a nonsense collider rather than a small one.
    if (minX > maxX) return Box(Vec3.ZERO, Vec3.ZERO)
    return Box(Vec3(minX, minY, minZ), Vec3(maxX, maxY, maxZ))
}
