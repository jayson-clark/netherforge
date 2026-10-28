package dev.netherforge.format.particle

import dev.netherforge.format.ProblemSink
import dev.netherforge.format.game.BlockInfo
import dev.netherforge.format.game.GameDataBundle
import dev.netherforge.format.game.ParticleDataKind
import dev.netherforge.format.game.RegistryKey
import dev.netherforge.format.item.ItemDef
import dev.netherforge.format.math.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParticleEffectValidatorTest {

    private val game = GameDataBundle(
        minecraft = "26.3",
        blocks = mapOf(
            "minecraft:stone" to BlockInfo(),
            "minecraft:oak_log" to BlockInfo(properties = mapOf("axis" to listOf("x", "y", "z")))
        ),
        registries = mapOf(RegistryKey.ITEM.id to listOf("minecraft:apple")),
        particles = mapOf(
            "minecraft:flame" to ParticleDataKind.NONE,
            "minecraft:dust" to ParticleDataKind.DUST,
            "minecraft:dust_color_transition" to ParticleDataKind.DUST_TRANSITION,
            "minecraft:entity_effect" to ParticleDataKind.COLOR,
            "minecraft:block" to ParticleDataKind.BLOCK,
            "minecraft:item" to ParticleDataKind.ITEM,
            "minecraft:vibration" to ParticleDataKind.OTHER
        )
    )

    private fun codes(vararg emitters: Pair<String, EmitterDef>, duration: Int = 20, game: GameDataBundle? = null): List<String?> {
        val sink = ProblemSink("particles/x/effect.json")
        ParticleEffectValidator.validate(ParticleEffectFile(duration = duration, emitters = mapOf(*emitters)), sink, game)
        return sink.problems.map { it.code }
    }

    private fun emitter(particle: String = "minecraft:flame") = EmitterDef(particle = particle, burst = 1)

    @Test
    fun aValidEffectHasNoProblems() {
        val ring = emitter("dust").copy(
            every = 5,
            end = 20,
            shape = RingShape(),
            distribution = Distribution.EVEN,
            size = 1.5,
            curves = Curves(
                color = listOf(ColorKey(0, "#ffd34d"), ColorKey(20, "#ff3b1f")),
                radius = listOf(NumberKey(0, 0.5), NumberKey(20, 4.0))
            )
        )
        val sparks = EmitterDef(
            particle = "end_rod",
            rate = 3.0,
            shape = SphereShape(radius = 0.3),
            motion = Motion.OUTWARD,
            speed = 0.15,
            curves = Curves(rate = listOf(NumberKey(2, 6.0), NumberKey(20, 0.0)))
        )
        assertEquals(emptyList(), codes("ring" to ring, "sparks" to sparks))
        assertEquals(
            emptyList(),
            codes(
                "ring" to ring,
                game = game.copy(
                    particles =
                    game.particles + ("minecraft:end_rod" to ParticleDataKind.NONE)
                )
            )
        )
    }

    @Test
    fun unknownParticlesAreOnlyFlaggedWithGameData() {
        assertEquals(emptyList(), codes("a" to emitter("minecraft:nope")))
        assertEquals(listOf("particle.unknown"), codes("a" to emitter("minecraft:nope"), game = game))
        assertEquals(listOf("particle.id"), codes("a" to emitter("Not An Id")))
    }

    @Test
    fun otherDataKindsCantBePlayed() {
        assertEquals(listOf("particle.unsupported"), codes("a" to emitter("vibration"), game = game))
    }

    @Test
    fun dataFieldsFollowTheParticlesKind() {
        // Without a known kind, nothing is said about which fields belong.
        val flameWithColor = emitter("flame").copy(color = "#ff0000", size = 2.0)
        assertEquals(emptyList(), codes("a" to flameWithColor))
        assertEquals(listOf("particle.option", "particle.option"), codes("a" to flameWithColor, game = game))

        // Required fields, reported at the emitter.
        val sink = ProblemSink("p")
        ParticleEffectValidator.validate(ParticleEffectFile(duration = 5, emitters = mapOf("a" to emitter("block"))), sink, game)
        assertEquals(listOf("particle.option" to "$.emitters.a"), sink.problems.map { it.code to it.path })
        assertEquals(listOf("particle.option"), codes("a" to emitter("item"), game = game))

        // What each kind takes.
        assertEquals(
            emptyList(),
            codes("a" to emitter("dust_color_transition").copy(color = "#000000", toColor = "#ffffff", size = 2.0), game = game)
        )
        assertEquals(listOf("particle.option"), codes("a" to emitter("dust").copy(toColor = "#ffffff"), game = game))
        assertEquals(listOf("particle.option"), codes("a" to emitter("entity_effect").copy(color = "#ffffff", size = 1.0), game = game))
        assertEquals(emptyList(), codes("a" to emitter("item").copy(item = ItemDef("apple")), game = game))
        assertEquals(emptyList(), codes("a" to emitter("block").copy(blockState = "oak_log[axis=x]"), game = game))
    }

    @Test
    fun blockStatesAndItemsAreCheckedLikeEverywhereElse() {
        assertEquals(listOf("particle.unknown-block"), codes("a" to emitter("block").copy(blockState = "minecraft:nope"), game = game))
        assertEquals(listOf("particle.block-property"), codes("a" to emitter("block").copy(blockState = "oak_log[axis=w]"), game = game))
        assertEquals(listOf("item.unknown"), codes("a" to emitter("item").copy(item = ItemDef("nope")), game = game))
    }

    @Test
    fun curveChannelsFollowTheKindToo() {
        val sized = emitter("flame").copy(curves = Curves(size = listOf(NumberKey(0, 1.0))))
        assertEquals(emptyList(), codes("a" to sized))
        assertEquals(listOf("particle.curve-channel"), codes("a" to sized, game = game))
        val colored = emitter("flame").copy(curves = Curves(color = listOf(ColorKey(0, "#ffffff"))))
        assertEquals(listOf("particle.curve-channel"), codes("a" to colored, game = game))
        assertEquals(emptyList(), codes("a" to emitter("dust").copy(curves = Curves(size = listOf(NumberKey(0, 1.0)))), game = game))
    }

    // Each code's plain case is in testdata/invalid/particle-semantics; these are the other ways into it, and what isn't one.

    @Test
    fun emissionAndWindow() {
        assertEquals(listOf("particle.emission"), codes("a" to EmitterDef(particle = "flame")))
        assertEquals(listOf("particle.window"), codes("a" to emitter().copy(start = 5, end = 5)))
    }

    @Test
    fun shapes() {
        assertEquals(listOf("particle.shape"), codes("a" to emitter().copy(shape = RingShape())))
        assertEquals(listOf("particle.shape"), codes("a" to emitter().copy(shape = DiscShape(radius = 0.0))))
        assertEquals(listOf("particle.curve-channel"), codes("a" to emitter().copy(curves = Curves(radius = listOf(NumberKey(0, 1.0))))))
        assertEquals(
            listOf("particle.distribution"),
            codes(
                "a" to emitter().copy(shape = SphereShape(1.0), distribution = Distribution.EVEN)
            )
        )
        assertEquals(emptyList(), codes("a" to emitter().copy(shape = SphereShape(1.0, surface = true), distribution = Distribution.EVEN)))
    }

    @Test
    fun motion() {
        assertEquals(listOf("particle.direction"), codes("a" to emitter().copy(motion = Motion.DIRECTION, direction = Vec3.ZERO)))
        assertEquals(listOf("particle.count"), codes("a" to emitter().copy(count = 101)))
        assertEquals(listOf("particle.spread"), codes("a" to emitter().copy(spread = Vec3(0.0, -1.0, 0.0))))
    }

    @Test
    fun curves() {
        assertEquals(
            listOf("particle.curve-conflict"),
            codes(
                "a" to emitter().copy(speed = 1.0, curves = Curves(speed = listOf(NumberKey(0, 1.0))))
            )
        )
        // A rate curve sits beside its rate: rate says the emitter is rate-driven, the curve gives the value.
        assertEquals(
            emptyList(),
            codes("a" to EmitterDef(particle = "flame", rate = 1.0, curves = Curves(rate = listOf(NumberKey(0, 2.0)))))
        )
        assertEquals(listOf("particle.color"), codes("a" to emitter().copy(curves = Curves(color = listOf(ColorKey(0, "#FFFFFF"))))))
        // An empty channel is the same as none.
        assertEquals(emptyList(), codes("a" to emitter().copy(speed = 1.0, curves = Curves(speed = emptyList()))))
    }

    @Test
    fun theBudgetSumsEachEmittersWorstTick() {
        val rate = EmitterDef(particle = "flame", rate = 1.0, curves = Curves(rate = listOf(NumberKey(0, 100.5))))
        assertEquals(101, ParticleEffectValidator.worstPointsPerTick(rate))
        assertEquals(emptyList(), codes("a" to emitter().copy(burst = 155), "b" to rate))
        assertEquals(listOf("particle.budget"), codes("a" to emitter().copy(burst = 156), "b" to rate))
    }

    @Test
    fun theWriterSortsCurveKeys() {
        val keys = """[{"time": 5, "value": 1}, {"time": 0, "value": 2}]"""
        val text = """{"duration": 10, "emitters": {"a": {"particle": "flame", "burst": 1, "curves": {"speed": $keys}}}}"""
        val parsed = ParticleEffectKind.parse(text, "p") as dev.netherforge.format.json.CanonicalJson.Parsed.Ok
        val written = ParticleEffectKind.write(parsed.value)
        assertTrue(written.indexOf("\"time\": 0") < written.indexOf("\"time\": 5"), written)
        assertTrue(written.startsWith("{\n  \"\$schema\": \"../../.netherforge/schema/particle_effect.schema.json\""), written)
    }
}
