package dev.netherforge.plugin.centity

import dev.netherforge.format.math.Matrix4
import dev.netherforge.format.math.Vec3
import dev.netherforge.plugin.physics.Quat

/**
 * The XYZ euler rotation (degrees) that points +Z along [forward] (a unit
 * vector) with +X level: the basis right = up × forward, up' = forward × right.
 * Straight up or down, where "level" means nothing, +X stays east.
 */
internal fun lookRotation(forward: Vec3): Vec3 {
    val flat = Vec3(0.0, 1.0, 0.0).cross(forward)
    val right = if (flat.length() < 1e-9) Vec3(1.0, 0.0, 0.0) else flat.normalized()
    val up = forward.cross(right)
    val basis = Matrix4()
    val v = basis.values
    v[0] = right.x
    v[1] = right.y
    v[2] = right.z
    v[4] = up.x
    v[5] = up.y
    v[6] = up.z
    v[8] = forward.x
    v[9] = forward.y
    v[10] = forward.z
    return Quat.fromBasis(basis).toEuler()
}
