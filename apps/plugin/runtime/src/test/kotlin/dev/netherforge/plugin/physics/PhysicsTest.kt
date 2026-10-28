package dev.netherforge.plugin.physics

import dev.netherforge.format.centity.PhysicsShape
import dev.netherforge.format.centity.ResolvedPhysics
import dev.netherforge.format.game.Box
import dev.netherforge.format.math.Matrix4
import dev.netherforge.format.math.Vec3
import dev.netherforge.format.math.degreesToRadians
import dev.netherforge.format.math.radiansToDegrees
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a physics body actually does. Everything is in blocks, seconds and
 * degrees, the units the component is authored in.
 */
class PhysicsTest {
    /** One server tick. */
    private val tick = 0.05

    private fun box(minX: Double, minY: Double, minZ: Double, maxX: Double, maxY: Double, maxZ: Double) =
        Box(Vec3(minX, minY, minZ), Vec3(maxX, maxY, maxZ))

    /** A floor with its surface at [y], wide enough that nothing misses it. */
    private fun floor(y: Double) = box(-64.0, y - 8.0, -64.0, 64.0, y, 64.0)

    private fun cube(
        center: Vec3,
        orientation: Quat = Quat.IDENTITY,
        half: Vec3 = Vec3(0.5, 0.5, 0.5),
        velocity: Vec3 = Vec3.ZERO,
        spin: Vec3 = Vec3.ZERO,
        sphere: Boolean = false,
        locked: Boolean = false
    ) = RigidBody.single(center, orientation, velocity, spin, half, sphere, locked)

    private fun simulate(physics: ResolvedPhysics, body: RigidBody, obstacles: List<Box>, ticks: Int): PhysicsSolver.Result {
        var result = PhysicsSolver.Result(false, false)
        repeat(ticks) { result = PhysicsSolver.step(physics, body, tick, obstacles) }
        return result
    }

    /** How far the body's up-axis has tilted from vertical, in degrees. */
    private fun tilt(body: RigidBody): Double {
        val up = body.orientation.rotate(Vec3(0.0, 1.0, 0.0))
        return radiansToDegrees(acos(up.y.coerceIn(-1.0, 1.0)))
    }

    // ---- falling and landing ----------------------------------------------

    @Test
    fun `a body with nothing under it accelerates at its gravity`() {
        val body = cube(Vec3.ZERO)
        simulate(physics(gravity = 32.0, drag = 0.0), body, emptyList(), ticks = 20)
        assertEquals(-32.0, body.velocity.y, 0.05)
    }

    @Test
    fun `a falling body comes to rest on the surface it lands on`() {
        val body = cube(Vec3(0.0, 10.5, 0.0))
        val result = simulate(physics(gravity = 32.0, drag = 0.0), body, listOf(floor(0.0)), ticks = 120)
        assertEquals(0.5, body.center.y, 0.02)
        assertTrue(result.onGround)
        assertEquals(0.0, body.velocity.y, 0.05)
    }

    @Test
    fun `a body at rest on the floor stays exactly still`() {
        val body = cube(Vec3(0.0, 0.5, 0.0))
        simulate(physics(gravity = 32.0), body, listOf(floor(0.0)), ticks = 200)
        assertEquals(0.5, body.center.y, 0.01)
        assertEquals(0.0, body.center.x, 0.01)
        assertEquals(0.0, body.center.z, 0.01)
        assertTrue(tilt(body) < 1.0, "drifted ${tilt(body)} degrees off vertical while resting")
    }

    @Test
    fun `bounciness returns a fraction of the impact and then settles`() {
        val physics = physics(gravity = 32.0, bounciness = 0.5, drag = 0.0, friction = 0.0)
        val obstacles = listOf(floor(0.0))
        val body = cube(Vec3(0.0, 5.5, 0.0))

        // Dropped from 5 blocks it arrives at sqrt(2 * 32 * 5) ≈ 17.9 and should leave at about half that.
        var rebound = 0.0
        repeat(60) {
            val was = body.velocity.y
            PhysicsSolver.step(physics, body, tick, obstacles)
            if (rebound == 0.0 && was < 0.0 && body.velocity.y > 0.0) rebound = body.velocity.y
        }
        assertTrue(rebound > 6.0 && rebound < 12.0, "bounced back at $rebound, expected about half of 17.9")

        // And it has to stop bouncing, or it costs a packet a tick forever.
        simulate(physics, body, obstacles, ticks = 600)
        assertEquals(0.0, body.velocity.y, 0.05)
        assertEquals(0.5, body.center.y, 0.05)
    }

    @Test
    fun `a body with no bounciness lands dead`() {
        val body = cube(Vec3(0.0, 5.5, 0.0))
        simulate(physics(gravity = 32.0, bounciness = 0.0), body, listOf(floor(0.0)), ticks = 80)
        assertEquals(0.0, body.velocity.y, 0.05)
    }

    // ---- rotation ---------------------------------------------------------

    @Test
    fun `a box mostly over an edge topples off it`() {
        val physics = physics(gravity = 32.0, friction = 0.6, bounciness = 0.0)
        // Only a quarter of the base is supported, so the rest has to go over.
        val ledge = box(-8.0, -8.0, -8.0, 0.0, 0.0, 8.0)
        val body = cube(Vec3(0.25, 0.5, 0.0))
        simulate(physics, body, listOf(ledge), ticks = 60)
        assertTrue(tilt(body) > 25.0, "only tilted ${tilt(body)} degrees before falling off the edge")
        assertTrue(body.center.y < 0.0, "never actually fell off, centre is at ${body.center.y}")
    }

    @Test
    fun `a box sitting squarely on a ledge stays put`() {
        // Tipping has to depend on where the support is, not on there being an edge nearby.
        val physics = physics(gravity = 32.0, friction = 0.6, bounciness = 0.0)
        val ledge = box(-8.0, -8.0, -8.0, 0.0, 0.0, 8.0)
        val body = cube(Vec3(-1.0, 0.5, 0.0))
        simulate(physics, body, listOf(ledge), ticks = 60)
        assertTrue(tilt(body) < 5.0, "tilted ${tilt(body)} degrees while fully supported")
        assertEquals(0.5, body.center.y, 0.05)
    }

    @Test
    fun `a box dropped on a corner turns as it lands`() {
        val physics = physics(gravity = 32.0, friction = 0.6, bounciness = 0.0)
        val block = box(0.4, -1.0, -1.0, 1.4, 0.0, 1.0)
        val body = cube(Vec3(0.0, 3.0, 0.0))
        simulate(physics, body, listOf(block), ticks = 40)
        assertTrue(tilt(body) > 10.0, "landed on a corner without turning at all")
    }

    @Test
    fun `a tilted box landing flat rocks onto a face`() {
        val physics = physics(gravity = 32.0, friction = 0.6, bounciness = 0.0)
        val body = cube(Vec3(0.0, 3.0, 0.0), orientation = Quat.fromEuler(Vec3(0.0, 0.0, 20.0)))
        simulate(physics, body, listOf(floor(0.0)), ticks = 120)
        assertTrue(tilt(body) < 2.0, "came to rest tilted ${tilt(body)} degrees")
        assertEquals(0.5, body.center.y, 0.05)
    }

    @Test
    fun `a body that cannot rotate stays square however it lands`() {
        val ledge = box(-8.0, -8.0, -8.0, 0.0, 0.0, 8.0)
        val body = cube(Vec3(0.25, 0.5, 0.0), locked = true)
        simulate(physics(gravity = 32.0, rotates = false), body, listOf(ledge), ticks = 60)
        assertEquals(0.0, tilt(body), 1e-3)
        assertEquals(0.0, body.angularVelocity.x, 1e-9)
        assertEquals(0.0, body.angularVelocity.z, 1e-9)
    }

    @Test
    fun `a spinning box keeps spinning in the air`() {
        val body = cube(Vec3.ZERO, spin = Vec3(0.0, 4.0, 0.0))
        simulate(physics(gravity = 0.0, drag = 0.0, angularDrag = 0.0), body, emptyList(), ticks = 20)
        assertEquals(4.0, body.angularVelocity.y, 1e-3)
        // Four radians a second for a second is four radians of turn.
        val turned = body.orientation.rotate(Vec3(1.0, 0.0, 0.0))
        val expected = Quat.fromEuler(Vec3(0.0, radiansToDegrees(4.0), 0.0)).rotate(Vec3(1.0, 0.0, 0.0))
        assertEquals(expected.x, turned.x, 1e-2)
        assertEquals(expected.z, turned.z, 1e-2)
    }

    @Test
    fun `angular drag brings a free spin to a stop`() {
        val body = cube(Vec3.ZERO, spin = Vec3(0.0, 8.0, 0.0))
        simulate(physics(gravity = 0.0, angularDrag = 0.75), body, emptyList(), ticks = 20)
        // A quarter of the spin left after a second.
        assertEquals(2.0, body.angularVelocity.y, 0.05)
    }

    @Test
    fun `an off-centre impulse spins the body as well as moving it`() {
        val body = cube(Vec3.ZERO)
        body.applyImpulse(Vec3(0.0, 4.0, 0.0), Vec3(0.5, 0.5, 0.0))
        assertEquals(4.0, body.velocity.y, 1e-9)
        // Torque is r × F: (0.5, 0.5, 0) × (0, 4, 0) is up the +Z axis.
        assertTrue(body.angularVelocity.z > 1.0, "corner impulse produced no spin: ${body.angularVelocity}")
        assertEquals(0.0, body.angularVelocity.x, 1e-9)
    }

    @Test
    fun `an impulse through the centre does not spin the body`() {
        val body = cube(Vec3.ZERO)
        body.applyImpulse(Vec3(0.0, 4.0, 0.0), Vec3.ZERO)
        assertEquals(4.0, body.velocity.y, 1e-9)
        assertEquals(Vec3.ZERO, body.angularVelocity)
    }

    @Test
    fun `a long box is harder to spin end over end than about its length`() {
        val inverse = cube(Vec3.ZERO, half = Vec3(2.0, 0.1, 0.25)).invInertia.diagonal()
        // About Z is end over end; about X is along the length.
        assertTrue(inverse.x > inverse.z * 10.0, "a plank should spin far more easily about its own length")
    }

    // ---- friction and rolling ---------------------------------------------

    @Test
    fun `friction brings a sliding body to a halt`() {
        val physics = physics(gravity = 32.0, friction = 0.6, drag = 0.0, bounciness = 0.0)
        val body = cube(Vec3(0.0, 0.5, 0.0), velocity = Vec3(6.0, 0.0, 0.0))
        simulate(physics, body, listOf(floor(0.0)), ticks = 100)
        assertTrue(abs(body.velocity.x) < 0.5, "still sliding at ${body.velocity.x}")
    }

    @Test
    fun `a frictionless body slides on forever`() {
        val physics = physics(gravity = 32.0, friction = 0.0, drag = 0.0, bounciness = 0.0)
        val body = cube(Vec3(0.0, 0.5, 0.0), velocity = Vec3(6.0, 0.0, 0.0))
        simulate(physics, body, listOf(floor(0.0)), ticks = 40)
        assertEquals(6.0, body.velocity.x, 0.2)
    }

    @Test
    fun `a sphere set rolling keeps rolling and travels`() {
        val physics = physics(gravity = 32.0, friction = 0.6, drag = 0.0, angularDrag = 0.0, bounciness = 0.0)
        // Spinning about -Z sends a ball along +X: friction at the contact underneath converts spin into travel.
        val ball = cube(Vec3(0.0, 0.5, 0.0), spin = Vec3(0.0, 0.0, -8.0), sphere = true)
        simulate(physics, ball, listOf(floor(0.0)), ticks = 40)
        assertTrue(ball.center.x > 0.5, "a rolling ball went nowhere, ended at ${ball.center.x}")
        assertTrue(ball.angularVelocity.z < -1.0, "the roll died out immediately: ${ball.angularVelocity.z}")
    }

    @Test
    fun `a rolling sphere and a sliding box behave differently`() {
        val physics = physics(gravity = 32.0, friction = 0.8, drag = 0.0, angularDrag = 0.0, bounciness = 0.0)
        val obstacles = listOf(floor(0.0))
        val ball = cube(Vec3(0.0, 0.5, 0.0), velocity = Vec3(6.0, 0.0, 0.0), sphere = true)
        val box = cube(Vec3(0.0, 0.5, 0.0), velocity = Vec3(6.0, 0.0, 0.0))
        simulate(physics, ball, obstacles, ticks = 60)
        simulate(physics, box, obstacles, ticks = 60)
        // The ball converts travel into roll; the box is scrubbed off by friction.
        assertTrue(ball.center.x > box.center.x + 1.0, "ball reached ${ball.center.x}, box ${box.center.x}")
    }

    // ---- robustness -------------------------------------------------------

    @Test
    fun `a fast body is stopped by a thin wall rather than passing through it`() {
        val physics = physics(gravity = 0.0, drag = 0.0, friction = 0.0, bounciness = 0.0)
        val pane = box(2.0, -10.0, -10.0, 2.1, 10.0, 10.0)
        val body = cube(Vec3.ZERO, velocity = Vec3(60.0, 0.0, 0.0))
        simulate(physics, body, listOf(pane), ticks = 4)
        assertTrue(body.center.x < 2.0, "tunnelled through to ${body.center.x}")
    }

    @Test
    fun `a body does not sink into the floor under a heavy landing`() {
        val body = cube(Vec3(0.0, 30.0, 0.0))
        simulate(physics(gravity = 32.0, drag = 0.0, bounciness = 0.0), body, listOf(floor(0.0)), ticks = 200)
        assertTrue(body.center.y > 0.45, "sank to ${body.center.y}, should be resting near 0.5")
    }

    @Test
    fun `a body wedged inside something works its way out`() {
        val body = cube(Vec3.ZERO)
        simulate(physics(gravity = 32.0, drag = 0.0, bounciness = 0.0), body, listOf(floor(0.0)), ticks = 60)
        assertTrue(body.center.y > 0.4, "still buried at ${body.center.y}")
    }

    @Test
    fun `a body slides along a floor made of separate blocks`() {
        // The seams between tiles must not read as walls.
        val physics = physics(gravity = 32.0, friction = 0.0, drag = 0.0, bounciness = 0.0)
        val tiles = (0..5).map { box(it.toDouble(), -1.0, -1.0, it + 1.0, 0.0, 2.0) }
        val body = cube(Vec3(0.5, 0.5, 0.5), velocity = Vec3(2.0, 0.0, 0.0))
        simulate(physics, body, tiles, ticks = 20)
        assertTrue(body.center.x > 2.2, "only reached ${body.center.x} along a flat floor")
    }

    @Test
    fun `the speed ceiling bounds how fast a body can get`() {
        val body = cube(Vec3.ZERO)
        simulate(physics(gravity = 32.0, maxSpeed = 10.0, drag = 0.0), body, emptyList(), ticks = 200)
        assertEquals(-10.0, body.velocity.y, 0.05)
    }

    @Test
    fun `the spin ceiling bounds how fast a body can turn`() {
        val body = cube(Vec3.ZERO, spin = Vec3(0.0, 40.0, 0.0))
        simulate(physics(gravity = 0.0, angularDrag = 0.0, maxSpin = 180.0), body, emptyList(), ticks = 2)
        assertEquals(degreesToRadians(180.0), body.angularVelocity.y, 1e-3)
    }

    @Test
    fun `the obstacle region covers the whole step`() {
        val body = cube(Vec3.ZERO, velocity = Vec3(20.0, 0.0, 0.0))
        val region = PhysicsSolver.regionFor(physics(gravity = 32.0), body, tick)
        assertTrue(region.max.x >= 0.5 + 20.0 * tick, "region stops at ${region.max.x}")
        assertTrue(region.min.y < -0.5 - 32.0 * tick * tick, "region doesn't reach where gravity takes it")
    }

    // ---- sleeping ---------------------------------------------------------

    @Test
    fun `a body that has been still long enough falls asleep`() {
        val state = BodyState()
        state.velocity = Vec3(0.01, 0.0, 0.0)
        repeat(BodyState.SLEEP_STEPS - 1) { state.considerSleeping(physics()) }
        assertFalse(state.asleep)
        state.considerSleeping(physics())
        assertTrue(state.asleep)
        assertEquals(Vec3.ZERO, state.velocity, "a sleeping body keeps no residual drift")

        state.wake()
        assertFalse(state.asleep)
        assertEquals(0, state.stillSteps)
    }

    @Test
    fun `a moving body or one that may not sleep never does`() {
        val moving = BodyState()
        moving.velocity = Vec3(0.0, 1.0, 0.0)
        repeat(100) { moving.considerSleeping(physics()) }
        assertFalse(moving.asleep)

        val restless = BodyState()
        repeat(100) { restless.considerSleeping(physics(sleeps = false)) }
        assertFalse(restless.asleep)
    }

    @Test
    fun `a body resting on the floor settles below the sleep thresholds`() {
        val physics = physics(gravity = 32.0, bounciness = 0.3)
        val body = cube(Vec3(0.0, 3.0, 0.0), orientation = Quat.fromEuler(Vec3(10.0, 0.0, 15.0)))
        val state = BodyState()
        repeat(300) {
            if (state.asleep) return@repeat
            PhysicsSolver.step(physics, body, tick, listOf(floor(0.0)))
            state.velocity = body.velocity
            state.angularVelocity = body.angularVelocity
            state.considerSleeping(physics)
        }
        assertTrue(state.asleep, "never settled: v=${body.velocity} w=${body.angularVelocity}")
        assertTrue(PhysicsSolver.supported(body, listOf(floor(0.0))))
        assertFalse(PhysicsSolver.supported(body, emptyList()))
    }

    // ---- colliders --------------------------------------------------------

    @Test
    fun `a body with no collider of its own falls back to the hitbox`() {
        val hitbox = listOf(box(-1.0, 0.0, -1.5, 1.0, 0.5, 1.5))
        assertEquals(hitbox, PhysicsCollider.boxesOf(physics(), hitbox))
    }

    @Test
    fun `an explicit collider wins over the hitbox`() {
        val collider = box(-0.5, -0.5, -0.5, 0.5, 0.5, 0.5)
        val boxes = PhysicsCollider.boxesOf(physics(collider = collider), listOf(box(0.0, 0.0, 0.0, 8.0, 8.0, 8.0)))
        assertEquals(listOf(collider), boxes)
    }

    @Test
    fun `a body with neither collides as the unit cube`() {
        assertEquals(listOf(Box(Vec3.ZERO, Vec3.ONE)), PhysicsCollider.boxesOf(physics(), null))
        assertEquals(listOf(Box(Vec3.ZERO, Vec3.ONE)), PhysicsCollider.boxesOf(physics(), emptyList()))
    }

    @Test
    fun `a body spins about the middle of its collider, not the node origin`() {
        // A block display's box hangs off one corner of its node; rotating about that would look hinged.
        val body = PhysicsCollider.bodyFor(physics(), null, Matrix4(), Quat.IDENTITY, Vec3.ZERO, Vec3.ZERO)
        assertEquals(Vec3(0.5, 0.5, 0.5), body.center)
    }

    @Test
    fun `the node origin follows the collider centre back through a turn`() {
        val physics = physics()
        val body = PhysicsCollider.bodyFor(physics, null, Matrix4(), Quat.IDENTITY, Vec3.ZERO, Vec3.ZERO)

        val still = PhysicsCollider.originFor(physics, null, body, Vec3.ONE)
        assertEquals(0.0, still.x, 1e-9)
        assertEquals(0.0, still.y, 1e-9)
        assertEquals(0.0, still.z, 1e-9)

        // A half turn about Y swings the origin's corner to the opposite side of the centre.
        body.orientation = Quat.fromEuler(Vec3(0.0, 180.0, 0.0))
        val turned = PhysicsCollider.originFor(physics, null, body, Vec3.ONE)
        assertEquals(1.0, turned.x, 1e-9)
        assertEquals(0.0, turned.y, 1e-9)
        assertEquals(1.0, turned.z, 1e-9)
    }

    @Test
    fun `node scale grows the collider`() {
        val body = PhysicsCollider.bodyFor(physics(), null, Matrix4().scale(2.0, 3.0, 4.0), Quat.IDENTITY, Vec3.ZERO, Vec3.ZERO)
        assertEquals(Vec3(1.0, 1.5, 2.0), body.extents())
        assertEquals(24.0, body.mass, 1e-9, "unauthored mass is the collider's volume")
    }

    @Test
    fun `the component's flags reach the body`() {
        val ball = PhysicsCollider.bodyFor(
            physics(shape = PhysicsShape.SPHERE, mass = 3.0),
            null,
            Matrix4(),
            Quat.IDENTITY,
            Vec3.ZERO,
            Vec3.ZERO
        )
        assertTrue(ball.sphere)
        assertEquals(0.5, ball.radius, 1e-9)
        assertEquals(3.0, ball.mass, 1e-9)

        val pinned = PhysicsCollider.bodyFor(physics(rotates = false), null, Matrix4(), Quat.IDENTITY, Vec3.ZERO, Vec3.ZERO)
        assertTrue(pinned.locked)
    }

    @Test
    fun `a rotated body collides at its rotated corners`() {
        val physics = physics(gravity = 32.0, friction = 0.6, bounciness = 0.0)
        // Balanced on an edge, its lowest point is sqrt(2)/2 below the centre.
        val body = cube(Vec3(0.0, 4.0, 0.0), orientation = Quat.fromEuler(Vec3(0.0, 0.0, 45.0)))
        simulate(physics, body, listOf(floor(0.0)), ticks = 10)
        assertTrue(body.center.y > sqrt(2.0) / 2.0 - 0.1, "sank past its own corner to ${body.center.y}")

        // A cube cannot balance on an edge: it has to end up on a face. Exactly
        // balanced is an unstable equilibrium that only solver noise breaks, and
        // in doubles that takes longer than it did in floats (about 6 s here).
        simulate(physics, body, listOf(floor(0.0)), ticks = 300)
        assertTrue(tilt(body) < 15.0 || tilt(body) > 75.0, "settled at an impossible ${tilt(body)} degrees")
    }

    // ---- compound colliders -----------------------------------------------

    /** A stair: a full-width slab below, and half a slab on top at the back. */
    private fun stairBoxes() = listOf(
        box(0.0, 0.0, 0.0, 1.0, 0.5, 1.0),
        box(0.0, 0.5, 0.5, 1.0, 1.0, 1.0)
    )

    private fun compound(center: Vec3, boxes: List<Box>): RigidBody {
        val local = PhysicsCollider.centerOf(boxes)
        val parts = boxes.map { ColliderPart(it.center - local, it.size * 0.5) }
        return RigidBody(center, Quat.IDENTITY, Vec3.ZERO, Vec3.ZERO, parts, false, 0.25, false)
    }

    @Test
    fun `a shaped hitbox collides as its real boxes, not as the cube around them`() {
        val boxes = PhysicsCollider.boxesOf(physics(), stairBoxes())
        assertEquals(2, boxes.size)
        val body = PhysicsCollider.bodyFor(physics(), stairBoxes(), Matrix4(), Quat.IDENTITY, Vec3.ZERO, Vec3.ZERO)
        assertEquals(2, body.parts.size)
    }

    @Test
    fun `asking for a box collapses a shaped hitbox to one`() {
        val collapsed = PhysicsCollider.boxesOf(physics(shape = PhysicsShape.BOX), stairBoxes())
        assertEquals(listOf(PhysicsCollider.extentOf(stairBoxes())), collapsed)
        assertEquals(Box(Vec3.ZERO, Vec3.ONE), collapsed.single())
    }

    @Test
    fun `a stair rests on its lower slab rather than floating on the cube around it`() {
        val body = compound(Vec3(0.5, 4.0, 0.5), stairBoxes())
        simulate(physics(gravity = 32.0, bounciness = 0.0), body, listOf(floor(0.0)), ticks = 120)
        val lowest = body.parts.minOf { body.center.y + it.offset.y - it.half.y }
        assertEquals(0.0, lowest, 0.05)
        assertTrue(tilt(body) < 10.0, "a stair landing flat tilted ${tilt(body)} degrees")
    }

    @Test
    fun `a compound body's centre of mass is weighted by volume`() {
        // The lower slab is twice the upper one.
        val center = PhysicsCollider.centerOf(stairBoxes())
        assertTrue(center.y < 0.5, "centre of mass at ${center.y}, should be below the middle")
        assertTrue(center.z > 0.5, "centre of mass at ${center.z}, should be back towards the tall half")
    }

    @Test
    fun `a compound body resists turning differently about each axis`() {
        val inverse = compound(Vec3.ZERO, stairBoxes()).invInertia.diagonal()
        assertTrue(inverse.x > 0.0 && inverse.y > 0.0 && inverse.z > 0.0)
        assertTrue(inverse.y != inverse.x || inverse.y != inverse.z, "a stair resisted turning identically about every axis")
    }

    @Test
    fun `a compound body trips over a wall only its lower part reaches`() {
        // Only the lower slab is in line with the kerb. A shove below the centre of mass is a trip.
        val physics = physics(gravity = 0.0, drag = 0.0, friction = 0.0, bounciness = 0.0)
        val kerb = box(3.0, -1.0, -10.0, 4.0, 0.4, 10.0)
        val body = compound(Vec3(0.5, 0.5, 0.5), stairBoxes())
        body.velocity = Vec3(4.0, 0.0, 0.0)
        simulate(physics, body, listOf(kerb), ticks = 40)
        assertTrue(body.center.x < 3.0, "the stair went straight through to ${body.center.x}")
        assertTrue(tilt(body) > 30.0, "it was stopped without tripping: ${tilt(body)} degrees")
    }

    @Test
    fun `every part of a compound body is solid`() {
        // A block landing on the tall half of a stair is held at 1.0, not at the low half's 0.5.
        val stair = box(0.0, 0.5, 0.5, 1.0, 1.0, 1.0)
        val body = cube(Vec3(0.5, 4.0, 0.75), half = Vec3(0.25, 0.25, 0.25))
        simulate(physics(gravity = 32.0, bounciness = 0.0), body, listOf(stair), ticks = 120)
        assertEquals(1.25, body.center.y, 0.05)
    }
}
