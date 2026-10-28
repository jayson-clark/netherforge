package dev.netherforge.format.centity

import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.ProblemSink
import dev.netherforge.format.game.BlockInfo
import dev.netherforge.format.game.BlockState
import dev.netherforge.format.game.GameDataBundle
import dev.netherforge.format.game.MinecraftVersion
import dev.netherforge.format.game.RegistryKey
import dev.netherforge.format.math.Vec3
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CentityTest {

    private val tower = CentityFile(
        nodes = mapOf(
            "top" to NodeDef(parent = "root", transform = Transform(translation = Vec3(0.0, 1.0, 0.0))),
            "root" to NodeDef(transform = Transform(rotation = Vec3(0.0, 90.0, 0.0))),
            "tip" to NodeDef(parent = "top", transform = Transform(translation = Vec3(1.0, 0.0, 0.0)))
        ),
        animations = mapOf(
            "spin" to AnimationDef(
                loop = LoopMode.LOOP,
                tracks = mapOf(
                    "top" to mapOf(
                        Channel.ROTATION to listOf(Keyframe(0.0, Vec3.ZERO), Keyframe(1.0, Vec3(0.0, 180.0, 0.0)))
                    )
                )
            )
        )
    )

    @Test
    fun compilesParentsFirst() {
        val compiled = CentityCompiler.compile("tower", tower)
        assertEquals(listOf("root", "top", "tip"), compiled.nodes.map { it.name })
        assertEquals(listOf(-1, 0, 1), compiled.nodes.map { it.parentIndex })
    }

    @Test
    fun composesDownTheTree() {
        val compiled = CentityCompiler.compile("tower", tower)
        val world = Composer.world(compiled)
        // root turns 90° about Y, so the tip's +X offset ends up along -Z.
        val tip = world[2].translation()
        assertClose(Vec3(0.0, 1.0, -1.0), tip)
    }

    @Test
    fun samplesAnimationsWithLooping() {
        val compiled = CentityCompiler.compile("tower", tower)
        val spin = compiled.animation("spin")!!
        assertClose(Vec3(0.0, 90.0, 0.0), spin.tracks.single().sample(0.5))
        assertEquals(0.25, spin.resolve(1.25).time, 1e-9)
        val posed = Composer.pose(compiled, spin, 0.5)
        assertClose(Vec3(0.0, 90.0, 0.0), posed[1].rotation!!)
    }

    @Test
    fun anAdditiveClipChangesTheAuthoredPose() {
        val file = tower.copy(
            animations = mapOf(
                "bump" to AnimationDef(
                    blend = AnimationBlend.ADDITIVE,
                    tracks = mapOf(
                        "top" to mapOf(
                            Channel.TRANSLATION to listOf(Keyframe(0.0, Vec3(0.0, 0.5, 0.0))),
                            Channel.SCALE to listOf(Keyframe(0.0, Vec3(2.0, 0.5, 2.0)))
                        )
                    )
                )
            )
        )
        val compiled = CentityCompiler.compile("tower", file)
        val posed = Composer.pose(compiled, compiled.animation("bump")!!, 0.0)
        // top rests at y 1: the clip lifts it by half a block, and halves its height (its scale was 1).
        assertClose(Vec3(0.0, 1.5, 0.0), posed[1].translation!!)
        assertClose(Vec3(2.0, 0.5, 2.0), posed[1].scale!!)
    }

    @Test
    fun rotationTakesTheShortWayRound() {
        val track = CentityCompiler.compile(
            "x",
            CentityFile(
                nodes = mapOf("a" to NodeDef()),
                animations = mapOf(
                    "a" to
                        AnimationDef(
                            tracks = mapOf(
                                "a" to
                                    mapOf(
                                        Channel.ROTATION to
                                            listOf(Keyframe(0.0, Vec3(0.0, 350.0, 0.0)), Keyframe(1.0, Vec3(0.0, 10.0, 0.0)))
                                    )
                            )
                        )
                )
            )
        ).animations.single().tracks.single()
        assertClose(Vec3(0.0, 360.0, 0.0), track.sample(0.5))
    }

    @Test
    fun cyclesAreReported() {
        val file = CentityFile(nodes = mapOf("a" to NodeDef(parent = "b"), "b" to NodeDef(parent = "a"), "c" to NodeDef()))
        assertEquals(listOf("a", "b"), CentityValidator.cyclic(file))
    }

    @Test
    fun gameDataChecksBlocksAndProperties() {
        val game = GameDataBundle(
            minecraft = "26.3",
            blocks = mapOf("minecraft:oak_stairs" to BlockInfo(properties = mapOf("facing" to listOf("north", "east", "south", "west"))))
        )
        val file = CentityFile(
            nodes = mapOf(
                "a" to NodeDef(display = BlockDisplay("oak_stairs[facing=up]")),
                "b" to NodeDef(display = BlockDisplay("minecraft:nope"))
            )
        )
        val sink = ProblemSink("c")
        CentityValidator.validate(file, sink, emptySet(), game)
        assertEquals(listOf("centity.block-property", "centity.unknown-block"), sink.problems.map { it.code })
    }

    @Test
    fun gameDataChecksItems() {
        val game = GameDataBundle(minecraft = "26.3", registries = mapOf(RegistryKey.ITEM.id to listOf("minecraft:apple")))
        val file = CentityFile(nodes = mapOf("a" to NodeDef(display = ItemDisplay("apple")), "b" to NodeDef(display = ItemDisplay("pear"))))
        fun problems(game: GameDataBundle?) = ProblemSink("c").also { CentityValidator.validate(file, it, emptySet(), game) }.problems.map {
            it.code to it.message
        }
        assertEquals(listOf("centity.unknown-item" to "Minecraft 26.3 has no item \"minecraft:pear\""), problems(game))
        // Without the game's items, an id is checked for its shape only.
        assertEquals(emptyList(), problems(null))
    }

    @Test
    fun blockStatesParseAndPrintCanonically() {
        val state = BlockState.parse("oak_stairs[half=top, facing=east]")!!
        assertEquals("minecraft:oak_stairs[facing=east,half=top]", state.toString())
        assertNull(BlockState.parse("Oak Stairs"))
        assertNull(BlockState.parse("stone[facing]"))
    }

    @Test
    fun versionsCompareNumerically() {
        assertTrue(MinecraftVersion.of("26.3") > MinecraftVersion.of("1.21.11"))
        assertEquals(MinecraftVersion.of("26.3"), MinecraftVersion.of("26.3.0"))
        assertNull(MinecraftVersion.parse("26.3-rc-1"))
    }

    @Test
    fun aSingleNodeIsValidatedAndCompiledAsInAFile() {
        val ok = NodeDef(transform = Transform(translation = Vec3(0.0, 2.0, 0.0)), display = BlockDisplay("minecraft:stone"))
        val sink = ProblemSink("node")
        CentityValidator.validateNode("lamp", ok, sink, null)
        assertTrue(sink.problems.isEmpty(), "${sink.problems}")
        val node = CentityCompiler.compileNode("lamp", ok, 1)
        assertEquals("lamp", node.name)
        assertEquals(1, node.parentIndex)
        assertEquals(Vec3(0.0, 2.0, 0.0), node.transform.translationOrDefault)

        val bad = NodeDef(hitbox = dev.netherforge.format.centity.HitboxDef(shape = dev.netherforge.format.centity.HitboxShape.COLLISION))
        val badSink = ProblemSink("node")
        CentityValidator.validateNode("bad name", bad, badSink, null)
        assertEquals(
            setOf(ProblemCodes.CENTITY_NODE_NAME.code, ProblemCodes.CENTITY_HITBOX_COLLISION.code),
            badSink.problems.map { it.code }.toSet()
        )
        assertEquals("$.nodes[\"bad name\"].hitbox.shape", badSink.problems.last().path)
    }

    private fun assertClose(expected: Vec3, actual: Vec3) {
        val ok = abs(expected.x - actual.x) < 1e-9 && abs(expected.y - actual.y) < 1e-9 && abs(expected.z - actual.z) < 1e-9
        assertTrue(ok, "expected $expected, got $actual")
    }
}
