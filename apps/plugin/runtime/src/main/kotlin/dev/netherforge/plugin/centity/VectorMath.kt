package dev.netherforge.plugin.centity

import dev.netherforge.format.math.Vec3
import kotlin.math.sqrt

/** A vector's length. */
internal fun Vec3.length() = sqrt(x * x + y * y + z * z)

/** The unit vector along it (not for the zero vector). */
internal fun Vec3.normalized() = this * (1.0 / length())

internal fun Vec3.cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
