package dev.netherforge.format.particle

import dev.netherforge.format.ProblemSink
import dev.netherforge.format.centity.Easing
import dev.netherforge.format.game.GameDataBundle
import dev.netherforge.format.game.ParticleDataKind
import dev.netherforge.format.math.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class EffectSamplerTest {

    private fun compile(
        duration: Int,
        vararg emitters: Pair<String, EmitterDef>,
        loop: Boolean = false,
        game: GameDataBundle? = null
    ): CompiledEffect {
        val file = ParticleEffectFile(duration = duration, loop = loop, emitters = mapOf(*emitters))
        val sink = ProblemSink("p")
        ParticleEffectValidator.validate(file, sink, game)
        assertEquals(emptyList(), sink.problems, "the test's effect should be valid")
        return ParticleEffectCompiler.compile("test", file, game)
    }

    /** How many points each tick spawns, for [ticks] steps. */
    private fun counts(effect: CompiledEffect, ticks: Int = effect.duration, loop: Boolean = false, seed: Long = 1): List<Int> {
        val sampler = EffectSampler(effect, seed)
        return (0 until ticks).map { sampler.step(loop).size }
    }

    private fun burst(n: Int) = EmitterDef(particle = "flame", burst = n)

    private fun near(expected: Double, actual: Double, what: String = "") {
        if (abs(expected - actual) > 1e-9) fail("$what: expected $expected, got $actual")
    }

    private fun near(expected: Vec3, actual: Vec3, what: String = "") {
        near(expected.x, actual.x, "$what x")
        near(expected.y, actual.y, "$what y")
        near(expected.z, actual.z, "$what z")
    }

    @Test
    fun oneBurstAtStart() {
        assertEquals(listOf(0, 0, 3, 0, 0), counts(compile(5, "a" to burst(3).copy(start = 2))))
    }

    @Test
    fun everyPulsesStopBeforeEnd() {
        val effect = compile(10, "a" to burst(1).copy(start = 1, every = 3, end = 7))
        assertEquals(listOf(0, 1, 0, 0, 1, 0, 0, 0, 0, 0), counts(effect))
    }

    @Test
    fun fractionalRatesAccumulate() {
        val effect = compile(12, "a" to EmitterDef(particle = "flame", rate = 0.25))
        val ticks = counts(effect).withIndex().filter { it.value > 0 }.map { it.index }
        assertEquals(listOf(3, 7, 11), ticks)
    }

    @Test
    fun aRateCurveRampsCounts() {
        val effect = compile(
            6,
            "a" to EmitterDef(particle = "flame", rate = 1.0, curves = Curves(rate = listOf(NumberKey(0, 0.0), NumberKey(5, 5.0))))
        )
        assertEquals(listOf(0, 1, 2, 3, 4, 5), counts(effect))
    }

    @Test
    fun loopingWrapsAndResetsAccumulators() {
        val effect = compile(4, "a" to EmitterDef(particle = "flame", rate = 0.4))
        // 0.4, 0.8, 1.2 → 1 (0.2 left), 0.6; then from nothing again, not from 0.6.
        assertEquals(listOf(0, 0, 1, 0, 0, 0, 1, 0), counts(effect, ticks = 8, loop = true))
        val sampler = EffectSampler(effect, 1)
        repeat(4) { sampler.step(true) }
        assertEquals(0, sampler.tick)
        assertFalse(sampler.finished)
    }

    @Test
    fun aNonLoopingEffectFinishesAfterItsLastTick() {
        val sampler = EffectSampler(compile(3, "a" to burst(1).copy(every = 1)), 1)
        repeat(2) { assertEquals(1, sampler.step(false).size) }
        assertFalse(sampler.finished)
        assertEquals(1, sampler.step(false).size)
        assertTrue(sampler.finished)
        assertEquals(0, sampler.step(false).size)
    }

    @Test
    fun curvesInterpolateWithEachEasingAndHoldOutside() {
        fun curve(easing: Easing) = NumberCurve.of(listOf(NumberKey(10, 0.0, easing), NumberKey(20, 10.0)))
        near(0.0, curve(Easing.LINEAR).at(0.0), "before the first key")
        near(10.0, curve(Easing.LINEAR).at(99.0), "after the last key")
        near(5.0, curve(Easing.LINEAR).at(15.0), "linear")
        near(0.0, curve(Easing.STEP).at(19.0), "step")
        near(2.5, curve(Easing.EASE_IN).at(15.0), "ease in")
        near(7.5, curve(Easing.EASE_OUT).at(15.0), "ease out")
        near(5.0, curve(Easing.EASE_IN_OUT).at(15.0), "ease in-out")
        near(10.0, curve(Easing.STEP).at(20.0), "step lands on the next key")
        // Keys arrive in any order.
        near(5.0, NumberCurve.of(listOf(NumberKey(20, 10.0), NumberKey(10, 0.0))).at(15.0), "unsorted")

        val colors = ColorCurve.of(listOf(ColorKey(0, "#000000"), ColorKey(10, "#ff8000")))
        assertEquals(0x804000, colors.at(5.0))
        assertEquals(0xff8000, colors.at(30.0))
    }

    @Test
    fun evenRingsAreEvenlySpacedAndSpin() {
        val ring =
            EmitterDef(
                particle = "flame",
                burst = 4,
                shape = RingShape(radius = 2.0),
                distribution = Distribution.EVEN,
                spin = 10.0,
                every = 1
            )
        val sampler = EffectSampler(compile(5, "a" to ring), 1)
        val first = sampler.step(false)
        near(Vec3(2.0, 0.0, 0.0), first[0].position, "tick 0, point 0")
        near(Vec3(0.0, 0.0, 2.0), first[1].position, "tick 0, point 1")
        near(Vec3(-2.0, 0.0, 0.0), first[2].position, "tick 0, point 2")
        // Tick 1: everything turned 10° about +Y.
        val second = sampler.step(false)
        val angles = second.map { degrees(atan2(it.position.z, it.position.x)) }
        near(-10.0, angles[0], "spun point 0")
        near(80.0, angles[1], "spun point 1")
    }

    @Test
    fun evenLinesAndFibonacciSpheres() {
        val line = EmitterDef(particle = "flame", burst = 3, shape = LineShape(Vec3(0.0, 4.0, 0.0)), distribution = Distribution.EVEN)
        val points = EffectSampler(compile(1, "a" to line), 1).step(false).map { it.position.y }
        assertEquals(listOf(0.0, 2.0, 4.0), points)
        val one = line.copy(burst = 1)
        assertEquals(listOf(2.0), EffectSampler(compile(1, "a" to one), 1).step(false).map { it.position.y })

        val sphere = EmitterDef(particle = "flame", burst = 50, shape = SphereShape(1.5, surface = true), distribution = Distribution.EVEN)
        val shell = EffectSampler(compile(1, "a" to sphere), 1).step(false)
        assertEquals(50, shell.size)
        for (spawn in shell) near(1.5, length(spawn.position), "on the shell")
        assertEquals(50, shell.map { it.position }.toSet().size)
    }

    @Test
    fun randomShapesStayInside() {
        val disc = EmitterDef(particle = "flame", burst = 100, shape = DiscShape(radius = 2.0))
        for (spawn in EffectSampler(compile(1, "a" to disc), 7).step(false)) {
            assertEquals(0.0, spawn.position.y)
            assertTrue(length(spawn.position) <= 2.0)
        }
        val ball = EmitterDef(particle = "flame", burst = 100, shape = SphereShape(radius = 1.0))
        for (spawn in EffectSampler(compile(1, "a" to ball), 7).step(false)) assertTrue(length(spawn.position) <= 1.0)
    }

    @Test
    fun aRadiusCurveGrowsTheShape() {
        val ring = EmitterDef(
            particle = "flame",
            burst = 1,
            every = 1,
            shape = RingShape(),
            curves = Curves(radius = listOf(NumberKey(0, 1.0), NumberKey(4, 5.0)))
        )
        val sampler = EffectSampler(compile(5, "a" to ring), 1)
        val radii = (0 until 5).map { length(sampler.step(false).single().position) }
        radii.zip(listOf(1.0, 2.0, 3.0, 4.0, 5.0)).forEach { (actual, expected) -> near(expected, actual, "radius") }
    }

    @Test
    fun motionSetsCountAndOffset() {
        val base =
            EmitterDef(particle = "flame", burst = 1, shape = LineShape(Vec3(2.0, 0.0, 0.0)), distribution = Distribution.EVEN, speed = 0.5)
        fun one(emitter: EmitterDef): EffectSpawn = EffectSampler(compile(1, "a" to emitter), 1).step(false).single()

        val random = one(base.copy(count = 4, spread = Vec3(0.1, 0.2, 0.3)))
        assertEquals(4, random.count)
        assertEquals(Vec3(0.1, 0.2, 0.3), random.offset)
        near(0.5, random.speed, "speed")

        val outward = one(base.copy(motion = Motion.OUTWARD))
        assertEquals(0, outward.count)
        near(Vec3(1.0, 0.0, 0.0), outward.offset, "outward")
        near(Vec3(-1.0, 0.0, 0.0), one(base.copy(motion = Motion.INWARD)).offset, "inward")
        // A point at the centre flies straight up the shape's axis.
        near(Vec3(0.0, 1.0, 0.0), one(EmitterDef(particle = "flame", burst = 1, motion = Motion.OUTWARD)).offset, "from the centre")

        val stream = one(base.copy(motion = Motion.DIRECTION, direction = Vec3(0.0, 0.0, 3.0), rotation = Vec3(0.0, 90.0, 0.0)))
        near(Vec3(1.0, 0.0, 0.0), stream.offset, "turned direction")
        near(Vec3(0.0, 0.0, -1.0), stream.position, "turned point")
    }

    @Test
    fun spawnsCarryTheirDataAtTheTick() {
        val game = GameDataBundle(minecraft = "26.3", particles = mapOf("minecraft:dust" to ParticleDataKind.DUST))
        val dust = EmitterDef(
            particle = "dust",
            burst = 1,
            every = 1,
            size = 2.0,
            curves = Curves(color = listOf(ColorKey(0, "#000000"), ColorKey(2, "#ffffff")))
        )
        val sampler = EffectSampler(compile(3, "a" to dust, game = game), 1)
        assertEquals(
            listOf(DustData(0x000000, 2.0), DustData(0x808080, 2.0), DustData(0xffffff, 2.0)),
            (0 until 3).map {
                sampler.step(false).single().data
            }
        )
    }

    @Test
    fun theFramePlacesEffectSpaceInTheWorld() {
        near(Vec3(-1.0, 0.0, 0.0), EffectFrame(Vec3.ZERO, yaw = 90.0).direction(Vec3(0.0, 0.0, 1.0)), "yaw 90")
        near(Vec3(0.0, -1.0, 0.0), EffectFrame(Vec3.ZERO, pitch = 90.0).direction(Vec3(0.0, 0.0, 1.0)), "pitch 90")
        val frame = EffectFrame(Vec3(10.0, 64.0, 10.0), yaw = 180.0, scale = 2.0)
        near(Vec3(10.0, 64.0, 8.0), frame.position(Vec3(0.0, 0.0, 1.0)), "position")
        val spawn = EffectSpawn("a", "minecraft:flame", Vec3(0.0, 0.0, 1.0), 0, Vec3(0.0, 0.0, 1.0), 0.5)
        val world = frame.toWorld(spawn)
        near(Vec3(0.0, 0.0, -1.0), world.offset, "direction turns, doesn't scale")
        near(1.0, world.speed, "speed scales")
        val spread = frame.toWorld(spawn.copy(count = 2, offset = Vec3(0.1, 0.2, 0.3)))
        near(Vec3(0.2, 0.4, 0.6), spread.offset, "spread scales, doesn't turn")
    }

    @Test
    fun aSeedGivesTheSamePointsOnEveryPlatform() {
        val effect = compile(2, "a" to EmitterDef(particle = "flame", burst = 2, shape = SphereShape(radius = 1.0)))
        val points = EffectSampler(effect, 42).step(false).map { it.position }
        // Literal numbers: this test runs on the JVM and in JS, and both must agree.
        assertEquals(SEEDED.size, points.size)
        SEEDED.zip(points).forEachIndexed { i, (e, a) -> near(e, a, "point $i") }
        assertEquals(points, EffectSampler(effect, 42).step(false).map { it.position }, "a replay draws the same points")
    }

    @Test
    fun redefineKeepsTheTickAndSameNamedAccumulators() {
        val before = compile(10, "a" to EmitterDef(particle = "flame", rate = 0.5), "b" to EmitterDef(particle = "flame", rate = 0.5))
        val sampler = EffectSampler(before, 1)
        sampler.step(false) // a and b each hold 0.5
        val after = compile(10, "a" to EmitterDef(particle = "flame", rate = 0.5), "c" to EmitterDef(particle = "flame", rate = 0.5))
        sampler.redefine(after)
        assertEquals(1, sampler.tick)
        // a reaches 1.0 and spawns; c starts from nothing.
        assertEquals(listOf("a"), sampler.step(false).map { it.emitter })

        // A shorter timeline under a non-looping effect finishes it; a looping one wraps.
        repeat(5) { sampler.step(false) }
        sampler.redefine(compile(4, "a" to burst(1).copy(every = 1)))
        assertEquals(0, sampler.step(false).size)
        assertTrue(sampler.finished)

        val looping = EffectSampler(before, 1)
        repeat(6) { looping.step(true) }
        looping.redefine(compile(4, "a" to burst(1).copy(start = 2)))
        assertEquals(1, looping.step(true).size, "tick 6 wraps to tick 2")
        assertEquals(3, looping.tick)
    }

    private fun length(v: Vec3) = sqrt(v.x * v.x + v.y * v.y + v.z * v.z)

    private fun degrees(radians: Double) = radians * 180.0 / PI

    private companion object {
        val SEEDED = listOf(
            Vec3(0.44769385706171666, -0.3540992878138615, -0.30443003637016897),
            Vec3(0.11519145330098937, 0.7903864069309029, 0.2599122264879563)
        )
    }
}
