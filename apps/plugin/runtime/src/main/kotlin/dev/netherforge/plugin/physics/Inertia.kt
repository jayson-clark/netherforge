package dev.netherforge.plugin.physics

import dev.netherforge.format.math.Vec3

/**
 * The inverse inertia tensor of a body, in its own frame.
 *
 * A single box's tensor is diagonal; a compound one's isn't. The
 * parallel-axis theorem, which carries each part's inertia to the shared centre
 * of mass, introduces products of inertia as soon as a part sits off-axis. A
 * stair is exactly that, and dropping those terms would have it resist turning
 * as though it were the box around it. Symmetric, so six numbers.
 */
class Inertia(
    private val xx: Double,
    private val yy: Double,
    private val zz: Double,
    private val xy: Double,
    private val xz: Double,
    private val yz: Double
) {
    /** `this · v`, for a vector already in the body's frame. */
    fun apply(v: Vec3): Vec3 = Vec3(
        xx * v.x + xy * v.y + xz * v.z,
        xy * v.x + yy * v.y + yz * v.z,
        xz * v.x + yz * v.y + zz * v.z
    )

    /** How readily the body turns about each of its own axes, for reporting. */
    fun diagonal(): Vec3 = Vec3(xx, yy, zz)

    /**
     * This tensor for a body of inverse mass [invMass]. The tensors built here
     * are per unit mass (the shape of the resistance is a fact about the
     * shape), so `I⁻¹ = I_unit⁻¹ / m`.
     */
    fun scaled(invMass: Double): Inertia = Inertia(xx * invMass, yy * invMass, zz * invMass, xy * invMass, xz * invMass, yz * invMass)

    companion object {
        /** A body that cannot turn at all: every impulse is pure translation. */
        val LOCKED = Inertia(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

        /** The least inertia any axis is allowed, per unit mass. */
        private const val MINIMUM = 1e-4

        private const val SINGULAR = 1e-18

        /**
         * The inverse inertia of a compound of boxes, per unit mass, about the
         * centre of mass [parts] are measured from. Mass is spread by volume:
         * one material is what a builder means by one body.
         */
        fun ofParts(parts: List<ColliderPart>): Inertia {
            var total = 0.0
            for (part in parts) total += volumeOf(part)
            if (total <= 0.0) return degenerate()

            var xx = 0.0
            var yy = 0.0
            var zz = 0.0
            var xy = 0.0
            var xz = 0.0
            var yz = 0.0

            for (part in parts) {
                val mass = volumeOf(part) / total
                val h = part.half
                val d = part.offset

                // The part's own inertia about its own centre...
                xx += mass * (h.y * h.y + h.z * h.z) / 3.0
                yy += mass * (h.x * h.x + h.z * h.z) / 3.0
                zz += mass * (h.x * h.x + h.y * h.y) / 3.0

                // ...then carried to the body's centre of mass, which is the
                // part a diagonal tensor cannot express.
                xx += mass * (d.y * d.y + d.z * d.z)
                yy += mass * (d.x * d.x + d.z * d.z)
                zz += mass * (d.x * d.x + d.y * d.y)
                xy -= mass * d.x * d.y
                xz -= mass * d.x * d.z
                yz -= mass * d.y * d.z
            }

            return invert(xx, yy, zz, xy, xz, yz)
        }

        /** A solid sphere: `2/5 m r²` about every axis. */
        fun ofSphere(radius: Double): Inertia {
            val inverse = 1.0 / (0.4 * radius * radius).coerceAtLeast(MINIMUM)
            return Inertia(inverse, inverse, inverse, 0.0, 0.0, 0.0)
        }

        private fun volumeOf(part: ColliderPart): Double =
            8.0 * part.half.x.coerceAtLeast(0.0) * part.half.y.coerceAtLeast(0.0) * part.half.z.coerceAtLeast(0.0)

        /**
         * The inverse of a symmetric 3x3, by cofactors.
         *
         * A collider flattened on two axes has almost no inertia about the
         * third, and inverting that faithfully would let the faintest touch spin
         * it beyond any ceiling. Flooring the diagonal first bounds the result
         * without distorting anything a real shape produces.
         */
        private fun invert(xx: Double, yy: Double, zz: Double, xy: Double, xz: Double, yz: Double): Inertia {
            val a = xx.coerceAtLeast(MINIMUM)
            val d = yy.coerceAtLeast(MINIMUM)
            val f = zz.coerceAtLeast(MINIMUM)

            val determinant = a * (d * f - yz * yz) - xy * (xy * f - yz * xz) + xz * (xy * yz - d * xz)
            if (determinant > -SINGULAR && determinant < SINGULAR) return degenerate()

            val scale = 1.0 / determinant
            return Inertia(
                (d * f - yz * yz) * scale,
                (a * f - xz * xz) * scale,
                (a * d - xy * xy) * scale,
                (xz * yz - xy * f) * scale,
                (xy * yz - xz * d) * scale,
                (xy * xz - a * yz) * scale
            )
        }

        /** What a collider with no volume gets: turnable, but only just. */
        private fun degenerate(): Inertia {
            val inverse = 1.0 / MINIMUM
            return Inertia(inverse, inverse, inverse, 0.0, 0.0, 0.0)
        }
    }
}
