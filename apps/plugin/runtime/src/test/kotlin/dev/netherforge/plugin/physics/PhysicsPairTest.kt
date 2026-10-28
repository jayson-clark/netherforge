package dev.netherforge.plugin.physics

import dev.netherforge.format.centity.ResolvedPhysics
import dev.netherforge.format.game.Box
import dev.netherforge.format.math.Vec3
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What happens when two bodies meet, checked against the closed-form results
 * for elastic collisions: those are what "momentum transferred correctly"
 * means, and they leave nowhere to hide.
 */
class PhysicsPairTest {
    private val tick = 0.05

    /** No gravity and no drag, so nothing but the collision is under test. */
    private fun freeSpace(bounciness: Double = 1.0, friction: Double = 0.0) = physics(
        gravity = 0.0,
        drag = 0.0,
        angularDrag = 0.0,
        friction = friction,
        bounciness = bounciness
    )

    private fun ball(x: Double, velocity: Double, mass: Double = 1.0, radius: Double = 0.25) = RigidBody.single(
        center = Vec3(x, 0.0, 0.0),
        orientation = Quat.IDENTITY,
        velocity = Vec3(velocity, 0.0, 0.0),
        angularVelocity = Vec3.ZERO,
        half = Vec3(radius, radius, radius),
        sphere = true,
        mass = mass
    )

    private fun box(x: Double, velocity: Double, mass: Double = 1.0, half: Double = 0.5) = RigidBody.single(
        center = Vec3(x, 0.0, 0.0),
        orientation = Quat.IDENTITY,
        velocity = Vec3(velocity, 0.0, 0.0),
        angularVelocity = Vec3.ZERO,
        half = Vec3(half, half, half),
        mass = mass
    )

    private fun collide(physics: ResolvedPhysics, bodies: List<RigidBody>, ticks: Int = 40) {
        val entries = bodies.map { PhysicsSolver.Entry(physics, it) }
        repeat(ticks) { PhysicsSolver.solve(entries, tick) }
    }

    private fun momentumOf(bodies: List<RigidBody>): Double = bodies.sumOf { it.mass * it.velocity.x }

    private fun energyOf(bodies: List<RigidBody>): Double = bodies.sumOf { 0.5 * it.mass * it.velocity.x * it.velocity.x }

    // ---- the classic results ----------------------------------------------

    @Test
    fun `equal masses head on trade velocities exactly`() {
        val a = ball(-1.0, 5.0)
        val b = ball(0.0, 0.0)
        collide(freeSpace(), listOf(a, b))
        assertTrue(abs(a.velocity.x) < 0.5, "the striker kept ${a.velocity.x}, it should have stopped")
        assertEquals(5.0, b.velocity.x, 0.5)
    }

    @Test
    fun `a heavy body barely notices a light one`() {
        val marble = ball(-1.0, 6.0, mass = 1.0)
        val boulder = ball(0.0, 0.0, mass = 50.0)
        collide(freeSpace(), listOf(marble, boulder))
        assertTrue(marble.velocity.x < -4.0, "the marble rebounded at only ${marble.velocity.x}")
        assertTrue(boulder.velocity.x in 0.05..0.6, "the boulder moved off at ${boulder.velocity.x}")
    }

    @Test
    fun `a heavy body drives a light one off far faster than it came`() {
        val boulder = ball(-1.0, 3.0, mass = 50.0)
        val marble = ball(0.0, 0.0, mass = 1.0)
        collide(freeSpace(), listOf(boulder, marble))
        assertTrue(marble.velocity.x > 5.0, "the marble left at only ${marble.velocity.x}, expected nearly 6")
        assertTrue(boulder.velocity.x > 2.5, "the boulder was slowed to ${boulder.velocity.x}")
    }

    @Test
    fun `momentum is conserved whatever the masses`() {
        for (mass in doubleArrayOf(0.25, 1.0, 4.0, 20.0)) {
            val a = ball(-1.0, 7.0, mass = mass)
            val b = ball(0.0, -1.0, mass = 1.0)
            val before = momentumOf(listOf(a, b))
            collide(freeSpace(), listOf(a, b))
            assertEquals(before, momentumOf(listOf(a, b)), abs(before) * 0.05 + 0.05, "momentum changed at mass $mass")
        }
    }

    @Test
    fun `a fully elastic collision keeps its energy`() {
        val a = ball(-1.0, 6.0)
        val b = ball(0.0, 0.0)
        val before = energyOf(listOf(a, b))
        collide(freeSpace(bounciness = 1.0), listOf(a, b))
        val after = energyOf(listOf(a, b))
        // Some is always lost to a discrete solver; not much, and never gained.
        assertTrue(after <= before * 1.02, "energy grew from $before to $after")
        assertTrue(after > before * 0.85, "energy fell from $before to $after")
    }

    @Test
    fun `a dead collision leaves them moving together`() {
        val a = ball(-1.0, 6.0, mass = 3.0)
        val b = ball(0.0, 0.0, mass = 1.0)
        collide(freeSpace(bounciness = 0.0), listOf(a, b))
        val together = 6.0 * 3.0 / 4.0
        assertEquals(together, a.velocity.x, 0.6)
        assertEquals(together, b.velocity.x, 0.6)
    }

    // ---- it is not only spheres -------------------------------------------

    @Test
    fun `two boxes trade momentum as well`() {
        val a = box(-1.2, 5.0)
        val b = box(0.0, 0.0)
        val before = momentumOf(listOf(a, b))
        collide(freeSpace(), listOf(a, b))
        assertTrue(b.velocity.x > 3.0, "the struck box only reached ${b.velocity.x}")
        assertEquals(before, momentumOf(listOf(a, b)), 0.5)
    }

    @Test
    fun `a ball bounces off a box and moves it`() {
        val ball = ball(-1.0, 5.0, mass = 1.0)
        val crate = box(0.0, 0.0, mass = 2.0)
        collide(freeSpace(bounciness = 0.8), listOf(ball, crate))
        assertTrue(crate.velocity.x > 1.0, "the crate was pushed to only ${crate.velocity.x}")
        assertTrue(ball.velocity.x < 1.0, "the ball carried on at ${ball.velocity.x}")
    }

    @Test
    fun `an off-centre strike sets the struck body spinning`() {
        val striker = ball(-1.0, 6.0)
        val target = box(0.0, 0.0)
        target.center = Vec3(0.0, -0.35, 0.0)
        collide(freeSpace(bounciness = 0.5), listOf(striker, target))
        val spin = target.angularVelocity
        assertTrue(abs(spin.x) + abs(spin.y) + abs(spin.z) > 0.5, "an off-centre hit produced no spin: $spin")
    }

    // ---- against the world, mass still cancels ----------------------------

    @Test
    fun `mass changes nothing about a body falling on its own`() {
        val floor = listOf(Box(Vec3(-64.0, -8.0, -64.0), Vec3(64.0, 0.0, 64.0)))
        val physics = physics(gravity = 32.0, drag = 0.0, bounciness = 0.5)
        val half = Vec3(0.5, 0.5, 0.5)
        val light = RigidBody.single(Vec3(0.0, 5.0, 0.0), Quat.IDENTITY, Vec3.ZERO, Vec3.ZERO, half, mass = 0.2)
        val heavy = RigidBody.single(Vec3(0.0, 5.0, 0.0), Quat.IDENTITY, Vec3.ZERO, Vec3.ZERO, half, mass = 200.0)
        repeat(60) { PhysicsSolver.step(physics, light, tick, floor) }
        repeat(60) { PhysicsSolver.step(physics, heavy, tick, floor) }
        assertEquals(light.center.y, heavy.center.y, 1e-6)
        assertEquals(light.velocity.y, heavy.velocity.y, 1e-6)
    }

    // ---- a rack ------------------------------------------------------------

    @Test
    fun `an impulse carries through a line of touching balls`() {
        val cue = ball(-1.0, 6.0)
        val row = (0..3).map { ball(it * 0.505, 0.0) }
        val all = listOf(cue) + row
        collide(freeSpace(bounciness = 0.95), all, ticks = 60)

        for ((index, moved) in row.withIndex()) {
            assertTrue(moved.velocity.x > 1.0, "ball $index was left behind at ${moved.velocity.x}")
        }
        assertTrue(cue.velocity.x < 2.0, "the cue ball carried straight on at ${cue.velocity.x}")
        assertEquals(6.0, momentumOf(all), 0.6)
        assertTrue(energyOf(all) <= 18.0 * 1.02, "the rack gained energy")
    }

    @Test
    fun `a solved-at-once chain shares the impulse rather than passing it along`() {
        // Pinned down rather than left as a surprise: a solver resolving every
        // contact in one go sees one event, not four collisions in sequence, so
        // a touching row moves off together instead of behaving like a
        // Newton's cradle. Every impulse engine does this; momentum and energy
        // are honest, the choreography isn't.
        val cue = ball(-1.0, 6.0)
        val row = (0..3).map { ball(it * 0.505, 0.0) }
        collide(freeSpace(bounciness = 0.95), listOf(cue) + row, ticks = 60)
        val spread = row.maxOf { it.velocity.x } - row.minOf { it.velocity.x }
        assertTrue(spread < 0.5, "the row did not move off together: spread $spread")
    }

    @Test
    fun `balls with a gap between them collide one pair at a time`() {
        val cue = ball(-1.0, 6.0)
        val row = (0..3).map { ball(0.2 + it * 0.9, 0.0) }
        val all = listOf(cue) + row
        collide(freeSpace(bounciness = 1.0), all, ticks = 120)

        assertTrue(row.last().velocity.x > 4.0, "the far ball left at only ${row.last().velocity.x}")
        for (still in row.dropLast(1)) {
            assertTrue(abs(still.velocity.x) < 1.5, "a middle ball kept ${still.velocity.x}")
        }
        assertEquals(6.0, momentumOf(all), 0.6)
    }

    // ---- sleeping bodies ----------------------------------------------------

    @Test
    fun `a sleeping body is woken by being hit`() {
        val striker = ball(-1.0, 5.0)
        val sleeper = ball(0.0, 0.0)
        val entries = listOf(PhysicsSolver.Entry(freeSpace(), striker), PhysicsSolver.Entry(freeSpace(), sleeper))
        entries[1].active = false
        repeat(40) { PhysicsSolver.solve(entries, tick) }

        assertTrue(entries[1].active, "the struck body stayed asleep")
        assertTrue(entries[1].wokenByContact)
        assertEquals(5.0, sleeper.velocity.x, 0.5)
    }

    @Test
    fun `a sleeping body is not woken by a neighbour merely resting against it`() {
        // Waking on any touch would livelock a pile: each sleeper roused by one that just dozed off.
        val physics = physics(gravity = 32.0, bounciness = 0.0)
        val floor = listOf(Box(Vec3(-8.0, -8.0, -8.0), Vec3(8.0, 0.0, 8.0)))
        val resting = box(0.0, 0.0)
        resting.center = Vec3(0.0, 0.5, 0.0)
        val sleeper = box(1.0, 0.0)
        sleeper.center = Vec3(1.0, 0.5, 0.0)

        val entries = listOf(PhysicsSolver.Entry(physics, resting), PhysicsSolver.Entry(physics, sleeper))
        for (entry in entries) entry.obstacles = floor
        entries[1].active = false
        repeat(40) { PhysicsSolver.solve(entries, tick) }

        assertFalse(entries[1].active, "a still neighbour woke the sleeper")
        assertFalse(entries[1].wokenByContact)
        assertEquals(Vec3(1.0, 0.5, 0.0), sleeper.center, "a sleeping body must not be integrated")
    }

    @Test
    fun `a body drifting slower than the sleep speed does not wake what it touches`() {
        // Below the sleep threshold it counts as still, so it is met as scenery.
        val drifter = box(-1.0, BodyState.SLEEP_SPEED * 0.5)
        val sleeper = box(0.0, 0.0)
        val entries =
            listOf(PhysicsSolver.Entry(freeSpace(bounciness = 0.0), drifter), PhysicsSolver.Entry(freeSpace(bounciness = 0.0), sleeper))
        entries[1].active = false
        repeat(40) { PhysicsSolver.solve(entries, tick) }

        assertFalse(entries[1].active)
        assertTrue(entries[0].touched)
        assertEquals(Vec3.ZERO, sleeper.center, "a sleeping body must not be integrated")
    }

    @Test
    fun `a box landing on another reports ground`() {
        val physics = physics(gravity = 32.0, bounciness = 0.0)
        val floor = listOf(Box(Vec3(-8.0, -8.0, -8.0), Vec3(8.0, 0.0, 8.0)))
        val bottom = box(0.0, 0.0)
        bottom.center = Vec3(0.0, 0.5, 0.0)
        val top = box(0.0, 0.0)
        top.center = Vec3(0.0, 3.0, 0.0)

        val entries = listOf(PhysicsSolver.Entry(physics, bottom), PhysicsSolver.Entry(physics, top))
        for (entry in entries) entry.obstacles = floor
        entries[0].active = false
        repeat(10) { PhysicsSolver.solve(entries, tick) }

        assertTrue(entries[0].wokenByContact, "the sleeper was never woken by the impact")
        assertTrue(entries[1].onGround, "standing on another body counts as ground")
        assertTrue(top.center.y > 1.3, "the falling box went through the sleeper to ${top.center.y}")
    }
}
