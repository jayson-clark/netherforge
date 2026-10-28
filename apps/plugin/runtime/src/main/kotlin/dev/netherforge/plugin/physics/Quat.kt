package dev.netherforge.plugin.physics

import dev.netherforge.format.math.Matrix4
import dev.netherforge.format.math.Vec3
import dev.netherforge.format.math.degreesToRadians
import dev.netherforge.format.math.radiansToDegrees
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A unit quaternion, for orientation that is being *integrated* rather than
 * authored.
 *
 * Files spell rotation as XYZ euler degrees because a builder can read them.
 * Euler angles are a poor thing to spin though: they gimbal lock, they don't
 * compose, and adding an angular velocity to them is not a rotation. So a body
 * carries its orientation here and converts back to euler on the way out.
 *
 * The euler convention matches [Matrix4.rotateXYZ] exactly, `R = Rx · Ry · Rz`,
 * because that is what composition will do with the numbers this hands back.
 */
data class Quat(val x: Double, val y: Double, val z: Double, val w: Double) {
    /** `this * other`: the rotation [other] followed by the rotation this. */
    operator fun times(other: Quat): Quat = Quat(
        w * other.x + x * other.w + y * other.z - z * other.y,
        w * other.y - x * other.z + y * other.w + z * other.x,
        w * other.z + x * other.y - y * other.x + z * other.w,
        w * other.w - x * other.x - y * other.y - z * other.z
    )

    /** The inverse rotation, which for a unit quaternion is its conjugate. */
    fun conjugate(): Quat = Quat(-x, -y, -z, w)

    /** Rotates [v] via `v + 2w(q × v) + 2(q × (q × v))`, without building a matrix. */
    fun rotate(v: Vec3): Vec3 {
        val cx = y * v.z - z * v.y
        val cy = z * v.x - x * v.z
        val cz = x * v.y - y * v.x

        val ccx = y * cz - z * cy
        val ccy = z * cx - x * cz
        val ccz = x * cy - y * cx

        return Vec3(
            v.x + 2.0 * (w * cx + ccx),
            v.y + 2.0 * (w * cy + ccy),
            v.z + 2.0 * (w * cz + ccz)
        )
    }

    /** A world direction expressed in the body's frame. */
    fun inverseRotate(v: Vec3): Vec3 = conjugate().rotate(v)

    /** Integration drifts a quaternion off the unit sphere, and an un-normalised one shears what it rotates. */
    fun normalized(): Quat {
        val length = sqrt(x * x + y * y + z * z + w * w)
        if (length < 1e-12) return IDENTITY
        val scale = 1.0 / length
        return Quat(x * scale, y * scale, z * scale, w * scale)
    }

    /**
     * This orientation after spinning at [angularVelocity] radians per second
     * for [seconds].
     *
     * The exponential map rather than `q += 0.5 · ω · q · dt`: for a body
     * turning a good fraction of a revolution in one step the linear form
     * visibly shrinks the rotation.
     */
    fun integrate(angularVelocity: Vec3, seconds: Double): Quat {
        val speed = sqrt(
            angularVelocity.x * angularVelocity.x +
                angularVelocity.y * angularVelocity.y +
                angularVelocity.z * angularVelocity.z
        )
        if (speed < 1e-8) return this
        val angle = speed * seconds * 0.5
        val scale = sin(angle) / speed
        val spin = Quat(angularVelocity.x * scale, angularVelocity.y * scale, angularVelocity.z * scale, cos(angle))
        return (spin * this).normalized()
    }

    /**
     * XYZ euler angles in degrees, in the convention [Matrix4.rotateXYZ] reads.
     *
     * Lossy at a pitch of ±90°, where X and Z become the same rotation. That
     * costs the read-back precision and the simulation nothing, because the
     * quaternion stays the authority and is never round-tripped through this.
     */
    fun toEuler(): Vec3 {
        val m00 = 1.0 - 2.0 * (y * y + z * z)
        val m01 = 2.0 * (x * y - z * w)
        val m02 = 2.0 * (x * z + y * w)
        val m10 = 2.0 * (x * y + z * w)
        val m11 = 1.0 - 2.0 * (x * x + z * z)
        val m12 = 2.0 * (y * z - x * w)
        val m22 = 1.0 - 2.0 * (x * x + y * y)

        val sinY = m02.coerceIn(-1.0, 1.0)
        val angleY = asin(sinY)

        // At the pole Z takes zero and X the whole merged rotation, which at
        // least round-trips to the same orientation.
        if (abs(sinY) > POLE) {
            return Vec3(radiansToDegrees(atan2(sinY * m10, m11)), radiansToDegrees(angleY), 0.0)
        }

        return Vec3(
            radiansToDegrees(atan2(-m12, m22)),
            radiansToDegrees(angleY),
            radiansToDegrees(atan2(-m01, m00))
        )
    }

    companion object {
        val IDENTITY = Quat(0.0, 0.0, 0.0, 1.0)

        private const val POLE = 0.99999

        /** A turn of [radians] about +Y, by the right-hand rule (as [Matrix4.rotateY]). */
        fun aboutY(radians: Double): Quat = Quat(0.0, sin(radians * 0.5), 0.0, cos(radians * 0.5))

        /** From XYZ euler degrees, `R = Rx · Ry · Rz`. */
        fun fromEuler(degrees: Vec3): Quat {
            val hx = degreesToRadians(degrees.x) * 0.5
            val hy = degreesToRadians(degrees.y) * 0.5
            val hz = degreesToRadians(degrees.z) * 0.5

            val qx = Quat(sin(hx), 0.0, 0.0, cos(hx))
            val qy = Quat(0.0, sin(hy), 0.0, cos(hy))
            val qz = Quat(0.0, 0.0, sin(hz), cos(hz))
            return qx * qy * qz
        }

        /**
         * The rotation part of an affine matrix, with any scale divided out.
         *
         * A basis that isn't a rotation (uneven scale or shear from a parent
         * chain) is orthonormalised into the nearest one rather than refused:
         * otherwise a body would stop rotating because of a node above it.
         */
        fun fromBasis(matrix: Matrix4): Quat {
            val v = matrix.values

            // Gram-Schmidt over the first two basis columns.
            var ax = v[0]
            var ay = v[1]
            var az = v[2]
            var length = sqrt(ax * ax + ay * ay + az * az)
            if (length < 1e-9) return IDENTITY
            ax /= length
            ay /= length
            az /= length

            var bx = v[4]
            var by = v[5]
            var bz = v[6]
            val dot = bx * ax + by * ay + bz * az
            bx -= ax * dot
            by -= ay * dot
            bz -= az * dot
            length = sqrt(bx * bx + by * by + bz * bz)
            if (length < 1e-9) return IDENTITY
            bx /= length
            by /= length
            bz /= length

            // The third column is whatever makes the frame right-handed, which
            // also drops any reflection a negative scale put in.
            val cx = ay * bz - az * by
            val cy = az * bx - ax * bz
            val cz = ax * by - ay * bx

            return fromRotationColumns(ax, ay, az, bx, by, bz, cx, cy, cz)
        }

        /** The standard branch-on-trace conversion, on an orthonormal basis. */
        private fun fromRotationColumns(
            m00: Double,
            m10: Double,
            m20: Double,
            m01: Double,
            m11: Double,
            m21: Double,
            m02: Double,
            m12: Double,
            m22: Double
        ): Quat {
            val trace = m00 + m11 + m22
            if (trace > 0.0) {
                val s = sqrt(trace + 1.0) * 2.0
                return Quat((m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s, 0.25 * s).normalized()
            }
            if (m00 > m11 && m00 > m22) {
                val s = sqrt(1.0 + m00 - m11 - m22) * 2.0
                return Quat(0.25 * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s).normalized()
            }
            if (m11 > m22) {
                val s = sqrt(1.0 + m11 - m00 - m22) * 2.0
                return Quat((m01 + m10) / s, 0.25 * s, (m12 + m21) / s, (m02 - m20) / s).normalized()
            }
            val s = sqrt(1.0 + m22 - m00 - m11) * 2.0
            return Quat((m02 + m20) / s, (m12 + m21) / s, 0.25 * s, (m10 - m01) / s).normalized()
        }
    }
}
