package dev.netherforge.plugin.centity
import dev.netherforge.format.game.Box
import dev.netherforge.format.math.Matrix4
import dev.netherforge.format.math.Vec3
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class HitboxesTest {
    private val cube = Hitboxes.UNIT_CUBE

    @Test
    fun `an untransformed cube is its own interaction box`() {
        assertEquals(Hitboxes.Bounds(0.5, 0.0, 0.5, 1.0, 1.0), Hitboxes.bounds(cube, Matrix4()))
    }

    @Test
    fun `a rotated box widens to the extent of its corners, on a square footprint`() {
        val spun = Matrix4.fromTrs(Vec3.ZERO, Vec3(0.0, 45.0, 0.0), Vec3.ONE)
        val bounds = Hitboxes.bounds(cube, spun)
        assertEquals(sqrt(2.0), bounds.width, 1e-9)
        assertEquals(1.0, bounds.height, 1e-9)

        val plank = listOf(Box(Vec3.ZERO, Vec3(2.0, 0.2, 0.2)))
        assertEquals(2.0, Hitboxes.bounds(plank, Matrix4()).width, 1e-9, "square: the long side wins")
    }

    @Test
    fun `the ray meets boxes in node space, in world distances`() {
        val scaled = Matrix4.fromTrs(Vec3(0.0, 0.0, 5.0), Vec3.ZERO, Vec3(2.0, 2.0, 2.0))
        val hit = assertNotNull(Hitboxes.distance(cube, scaled, Vec3(1.0, 1.0, 0.0), Vec3(0.0, 0.0, 1.0), 10.0))
        assertEquals(5.0, hit, 1e-9)
        assertNull(Hitboxes.distance(cube, scaled, Vec3(3.0, 1.0, 0.0), Vec3(0.0, 0.0, 1.0), 10.0), "beside it")
        assertNull(Hitboxes.distance(cube, scaled, Vec3(1.0, 1.0, 0.0), Vec3(0.0, 0.0, 1.0), 4.0), "out of reach")
    }

    @Test
    fun `a ray starting inside hits at zero, and a collapsed node can't be hit`() {
        assertEquals(0.0, Hitboxes.distance(cube, Matrix4(), Vec3(0.5, 0.5, 0.5), Vec3(1.0, 0.0, 0.0), 5.0))
        assertNull(Hitboxes.distance(cube, Matrix4().scale(0.0, 1.0, 1.0), Vec3(0.0, 0.5, -1.0), Vec3(0.0, 0.0, 1.0), 5.0))
    }
}
