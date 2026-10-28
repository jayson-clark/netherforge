package dev.netherforge.format.math

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The transform and angle maths every centity pose and cutscene shot goes through, on the JVM and in JS alike:
 * known values worked out by hand, and round trips.
 */
class MathTest {
    private val eps = 1e-12

    private fun assertNear(expected: Vec3, actual: Vec3, message: String? = null) {
        val off = maxOf(abs(expected.x - actual.x), abs(expected.y - actual.y), abs(expected.z - actual.z))
        assertTrue(off < 1e-9, "${message ?: ""} expected $expected, got $actual")
    }

    private fun assertIdentity(m: Matrix4) {
        val identity = Matrix4()
        for (i in 0 until 16) assertTrue(abs(identity.values[i] - m.values[i]) < 1e-9, "element $i: ${m.values.toList()}")
    }

    private val x = Vec3(1.0, 0.0, 0.0)
    private val y = Vec3(0.0, 1.0, 0.0)
    private val z = Vec3(0.0, 0.0, 1.0)

    @Test
    fun aNewMatrixIsTheIdentityStoredColumnMajor() {
        val m = Matrix4()
        assertEquals(listOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0), m.values.toList())
        // The translation is the last column: element(row, 3) == values[12 + row].
        m.translate(4.0, 5.0, 6.0)
        assertEquals(listOf(4.0, 5.0, 6.0), m.values.slice(12..14))
        assertEquals(Vec3(4.0, 5.0, 6.0), m.translation())
    }

    @Test
    fun rotationsTurnTheAxesTheRightHandedWay() {
        val quarter = PI / 2
        // About Y by +90°, x goes to -z; about X, y goes to +z; about Z, x goes to +y.
        assertNear(Vec3(0.0, 0.0, -1.0), Matrix4().rotateY(quarter).transformDirection(x))
        assertNear(z, Matrix4().rotateX(quarter).transformDirection(y))
        assertNear(y, Matrix4().rotateZ(quarter).transformDirection(x))
        // A rotation keeps lengths.
        assertNear(Vec3(1.0, 1.0, 1.0), Matrix4().rotateXYZ(0.3, -1.2, 2.0).scale())
    }

    @Test
    fun buildersPostMultiplySoTheLastOneAppliesFirst() {
        // translate then scale is T * S: a point is scaled, then moved.
        assertNear(Vec3(7.0, 0.0, 0.0), Matrix4().translate(5.0, 0.0, 0.0).scale(2.0, 2.0, 2.0).transformPosition(x))
        // scale then translate is S * T: a point is moved, then scaled with its move.
        assertNear(Vec3(12.0, 0.0, 0.0), Matrix4().scale(2.0, 2.0, 2.0).translate(5.0, 0.0, 0.0).transformPosition(x))
        // rotateXYZ is rotateX, then rotateY, then rotateZ.
        val xyz = Matrix4().rotateXYZ(0.4, 0.7, -1.1)
        val each = Matrix4().rotateX(0.4).rotateY(0.7).rotateZ(-1.1)
        for (i in 0 until 16) assertTrue(abs(xyz.values[i] - each.values[i]) < eps)
    }

    @Test
    fun aDirectionIgnoresTheTranslation() {
        val m = Matrix4().translate(9.0, 9.0, 9.0).scale(2.0, 3.0, 4.0)
        assertNear(Vec3(2.0, 3.0, 4.0), m.transformDirection(Vec3(1.0, 1.0, 1.0)))
        assertNear(Vec3(11.0, 12.0, 13.0), m.transformPosition(Vec3(1.0, 1.0, 1.0)))
    }

    @Test
    fun fromTrsIsTranslateRotateScale() {
        // (1,0,0) scaled by 2 is (2,0,0), turned 90° about Y is (0,0,-2), moved by (1,0,0) is (1,0,-2).
        val m = Matrix4.fromTrs(Vec3(1.0, 0.0, 0.0), Vec3(0.0, 90.0, 0.0), Vec3(2.0, 2.0, 2.0))
        assertNear(Vec3(1.0, 0.0, -2.0), m.transformPosition(x))
        val turned = Matrix4.fromTrs(Vec3(3.0, -1.0, 2.0), Vec3(30.0, 45.0, 60.0), Vec3(2.0, 3.0, 4.0))
        assertNear(Vec3(2.0, 3.0, 4.0), turned.scale())
        assertEquals(Vec3(3.0, -1.0, 2.0), turned.translation())
        // dest is reset first, so reusing one matrix doesn't accumulate.
        val reused = Matrix4().translate(100.0, 0.0, 0.0)
        assertSame(reused, Matrix4.fromTrs(Vec3.ZERO, Vec3.ZERO, Vec3.ONE, reused))
        assertIdentity(reused)
    }

    @Test
    fun mulComposesAndMayAliasItsArguments() {
        val a = Matrix4().translate(1.0, 2.0, 3.0)
        val b = Matrix4().rotateY(0.5).scale(2.0, 2.0, 2.0)
        val p = Vec3(0.3, -0.7, 1.9)
        val product = a.copy().mul(b)
        assertNear(a.transformPosition(b.transformPosition(p)), product.transformPosition(p))
        // a.mul(a) into a reads a before writing it.
        val squared = a.copy().let { it.mul(it) }
        assertNear(Vec3(2.0, 4.0, 6.0), squared.translation())
        // Into another matrix, the operands stay as they were.
        val dest = Matrix4()
        a.mul(b, dest)
        assertEquals(Vec3(1.0, 2.0, 3.0), a.translation())
        assertNear(product.transformPosition(p), dest.transformPosition(p))
    }

    @Test
    fun anAffineInverseUndoesTheMatrix() {
        val m = Matrix4.fromTrs(Vec3(3.0, -1.0, 2.0), Vec3(30.0, 45.0, 60.0), Vec3(2.0, 0.5, 4.0))
        val inverse = m.invertAffine()!!
        assertIdentity(m.copy().mul(inverse))
        assertIdentity(inverse.copy().mul(m))
        val p = Vec3(5.0, 6.0, -7.0)
        assertNear(p, inverse.transformPosition(m.transformPosition(p)))
        // Into itself.
        val same = m.copy()
        assertSame(same, same.invertAffine(same))
        assertNear(p, same.transformPosition(m.transformPosition(p)))
        // A collapsed axis has no inverse.
        assertNull(Matrix4().scale(1.0, 0.0, 1.0).invertAffine())
    }

    @Test
    fun copyIsIndependent() {
        val m = Matrix4().translate(1.0, 0.0, 0.0)
        val copy = m.copy()
        m.translate(1.0, 0.0, 0.0)
        assertEquals(Vec3(1.0, 0.0, 0.0), copy.translation())
        assertEquals(Vec3(2.0, 0.0, 0.0), m.translation())
    }

    @Test
    fun degreesAndRadiansRoundTrip() {
        assertTrue(abs(degreesToRadians(180.0) - PI) < eps)
        assertTrue(abs(radiansToDegrees(PI / 2) - 90.0) < eps)
        for (d in listOf(-720.0, -33.3, 0.0, 12.5, 359.0)) assertTrue(abs(radiansToDegrees(degreesToRadians(d)) - d) < 1e-9)
    }

    @Test
    fun theShortestDeltaGoesTheShortWayRound() {
        assertEquals(20.0, Angles.shortestDelta(350.0, 10.0))
        assertEquals(-20.0, Angles.shortestDelta(10.0, 350.0))
        assertEquals(90.0, Angles.shortestDelta(0.0, 90.0))
        // Exactly opposite is +180, whichever way it's asked.
        assertEquals(180.0, Angles.shortestDelta(0.0, 180.0))
        assertEquals(180.0, Angles.shortestDelta(0.0, -180.0))
        assertEquals(180.0, Angles.shortestDelta(0.0, 540.0))
        // Whole turns are nothing.
        assertEquals(0.0, Angles.shortestDelta(30.0, 30.0 + 720.0))
        assertEquals(-10.0, Angles.shortestDelta(-170.0, 900.0))
    }

    @Test
    fun anglesNormalizeToMinus180UpTo180() {
        assertEquals(-180.0, Angles.normalize(180.0))
        assertEquals(-180.0, Angles.normalize(-180.0))
        assertEquals(-170.0, Angles.normalize(190.0))
        assertEquals(170.0, Angles.normalize(-190.0))
        assertEquals(0.0, Angles.normalize(720.0))
        assertEquals(45.0, Angles.normalize(45.0 - 3600.0))
        // Turning by the shortest delta lands on the target, normalized.
        for ((from, to) in listOf(350.0 to 10.0, -170.0 to 900.0, 0.0 to -180.0, 123.4 to -321.0)) {
            assertTrue(abs(Angles.normalize(from + Angles.shortestDelta(from, to)) - Angles.normalize(to)) < 1e-9, "$from to $to")
        }
    }
}
