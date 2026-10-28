package dev.netherforge.plugin.physics

import dev.netherforge.format.math.Matrix4
import dev.netherforge.format.math.Vec3
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The quaternion has to agree with [Matrix4.fromTrs] exactly, or a body's pose and its node's would drift apart. */
class QuatTest {
    private val angles = listOf(
        Vec3.ZERO,
        Vec3(90.0, 0.0, 0.0),
        Vec3(0.0, 90.0, 0.0),
        Vec3(0.0, 0.0, 90.0),
        Vec3(30.0, 45.0, 60.0),
        Vec3(-120.0, 10.0, 170.0),
        Vec3(15.0, -80.0, -35.0),
        Vec3(179.0, 1.0, -179.0)
    )

    private val vectors = listOf(Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, 1.0), Vec3(0.3, -1.2, 2.5))

    private fun assertClose(expected: Vec3, actual: Vec3, tolerance: Double = 1e-9, message: String = "") {
        assertTrue(
            abs(expected.x - actual.x) < tolerance && abs(expected.y - actual.y) < tolerance && abs(expected.z - actual.z) < tolerance,
            "$message expected $expected, got $actual"
        )
    }

    /** q and -q are the same rotation. */
    private fun assertSameRotation(expected: Quat, actual: Quat, message: String) {
        val dot = expected.x * actual.x + expected.y * actual.y + expected.z * actual.z + expected.w * actual.w
        assertEquals(1.0, abs(dot), 1e-9, "$message: $expected vs $actual")
    }

    @Test
    fun `rotating by a quaternion matches the composed matrix`() {
        for (euler in angles) {
            val q = Quat.fromEuler(euler)
            val m = Matrix4.fromTrs(Vec3.ZERO, euler, Vec3.ONE)
            for (v in vectors) assertClose(m.transformPosition(v), q.rotate(v), message = "at $euler, $v:")
        }
    }

    @Test
    fun `euler angles round trip`() {
        for (euler in angles) {
            val back = Quat.fromEuler(euler).toEuler()
            // The angles may come back spelled differently; the rotation may not.
            assertSameRotation(Quat.fromEuler(euler), Quat.fromEuler(back), "round trip of $euler gave $back")
        }
        assertClose(Vec3(30.0, 45.0, 60.0), Quat.fromEuler(Vec3(30.0, 45.0, 60.0)).toEuler())
        assertClose(Vec3(15.0, -80.0, -35.0), Quat.fromEuler(Vec3(15.0, -80.0, -35.0)).toEuler(), 1e-7)
    }

    @Test
    fun `the pole still round trips to the same orientation`() {
        val euler = Vec3(20.0, 90.0, 30.0)
        val back = Quat.fromEuler(euler).toEuler()
        assertEquals(0.0, back.z, 1e-9)
        assertSameRotation(Quat.fromEuler(euler), Quat.fromEuler(back), "pole")
    }

    @Test
    fun `a basis read back is the rotation that built it`() {
        for (euler in angles) {
            val scaled = Matrix4.fromTrs(Vec3(4.0, -2.0, 7.0), euler, Vec3(2.0, 0.5, 3.0))
            assertSameRotation(Quat.fromEuler(euler), Quat.fromBasis(scaled), "basis of $euler")
        }
    }

    @Test
    fun `inverse rotation undoes rotation`() {
        val q = Quat.fromEuler(Vec3(30.0, 45.0, 60.0))
        val v = Vec3(0.3, -1.2, 2.5)
        assertClose(v, q.inverseRotate(q.rotate(v)))
        assertClose(v, (q * q.conjugate()).rotate(v))
    }

    @Test
    fun `products compose right to left`() {
        val a = Quat.fromEuler(Vec3(0.0, 90.0, 0.0))
        val b = Quat.fromEuler(Vec3(90.0, 0.0, 0.0))
        val v = Vec3(0.3, -1.2, 2.5)
        assertClose(a.rotate(b.rotate(v)), (a * b).rotate(v))
    }

    @Test
    fun `integrating a spin turns by speed times time`() {
        val q = Quat.IDENTITY.integrate(Vec3(0.0, 0.0, Math.PI), 0.5)
        assertSameRotation(Quat.fromEuler(Vec3(0.0, 0.0, 90.0)), q, "a quarter turn")
        assertEquals(Quat.IDENTITY, Quat.IDENTITY.integrate(Vec3.ZERO, 1.0))
        assertEquals(1.0, Quat(0.0, 0.0, 2.0, 0.0).normalized().z, 1e-12)
    }
}
