package dev.netherforge.format.cutscene

import dev.netherforge.format.Problem
import dev.netherforge.format.ProblemSink
import dev.netherforge.format.centity.Easing
import dev.netherforge.format.centity.Keyframe
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.math.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Cutscenes: the camera path's sampling, the file's rules, and its canonical form. */
class CutsceneTest {
    private fun position(time: Double, x: Double, easing: Easing? = null) = Keyframe(time, Vec3(x, 0.0, 0.0), easing)

    private fun look(time: Double, yaw: Double, pitch: Double = 0.0, easing: Easing? = null) = RotationKey(time, yaw, pitch, easing)

    private fun file(
        positions: List<Keyframe> = listOf(position(0.0, 0.0), position(2.0, 10.0)),
        rotations: List<RotationKey> = listOf(look(0.0, 0.0)),
        length: Double? = null,
        cues: List<Cue> = emptyList()
    ) = CutsceneFile(length = length, camera = Camera(positions, rotations), cues = cues)

    private fun problems(file: CutsceneFile): List<Problem> = ProblemSink("cutscenes/x.json").also {
        CutsceneValidator.validate(file, it)
    }.problems

    private fun codes(file: CutsceneFile) = problems(file).map { it.code }

    // ---- sampling ---------------------------------------------------------------

    @Test
    fun `position is linear between keys, and held before the first and after the last`() {
        val path = CameraPath(listOf(position(1.0, 10.0), position(3.0, 30.0)), listOf(look(0.0, 0.0)))
        assertEquals(10.0, path.at(0.0).position.x)
        assertEquals(10.0, path.at(1.0).position.x)
        assertEquals(20.0, path.at(2.0).position.x)
        assertEquals(30.0, path.at(3.0).position.x)
        assertEquals(30.0, path.at(99.0).position.x)
    }

    @Test
    fun `the easing on a key shapes the segment that leaves it`() {
        val eased = CameraPath(listOf(position(0.0, 0.0, Easing.EASE_IN), position(2.0, 100.0)), listOf(look(0.0, 0.0)))
        // Halfway through the segment ease_in is a quarter of the way.
        assertEquals(25.0, eased.at(1.0).position.x)
        val stepped = CameraPath(listOf(position(0.0, 0.0, Easing.STEP), position(2.0, 100.0)), listOf(look(0.0, 0.0)))
        assertEquals(0.0, stepped.at(1.99).position.x)
        assertEquals(100.0, stepped.at(2.0).position.x)
    }

    @Test
    fun `yaw turns the short way round and pitch is plain, each with its own easing`() {
        val path = CameraPath(
            listOf(position(0.0, 0.0)),
            listOf(look(0.0, 350.0, pitch = -10.0), look(2.0, 10.0, pitch = 30.0))
        )
        val half = path.at(1.0)
        // 350 to 10 is 20 degrees forwards, not 340 back.
        assertEquals(360.0, half.yaw)
        assertEquals(10.0, half.pitch)
        assertEquals(350.0, path.at(0.0).yaw)
        assertEquals(10.0, path.at(5.0).yaw)
    }

    @Test
    fun `two keys at one time don't divide by nothing`() {
        val path = CameraPath(listOf(position(1.0, 0.0), position(1.0, 5.0)), listOf(look(0.0, 0.0)))
        assertEquals(5.0, path.at(2.0).position.x)
    }

    @Test
    fun `a compiled cutscene's length is the file's, else the last key or cue`() {
        assertEquals(2.0, CutsceneCompiler.compile("x", file()).length)
        assertEquals(5.0, CutsceneCompiler.compile("x", file(length = 5.0)).length)
        val cued = file(cues = listOf(Cue(time = 3.0, event = "late")))
        assertEquals(3.0, CutsceneCompiler.compile("x", cued).length)
        assertEquals(3.0, CutsceneCompiler.compile("x", cued).cues.single().time)
    }

    @Test
    fun `cues come out in time order with their default duration`() {
        val compiled = CutsceneCompiler.compile(
            "x",
            file(cues = listOf(Cue(time = 1.5, text = "b"), Cue(time = 0.5, text = "a", duration = 1.0)))
        )
        assertEquals(listOf("a", "b"), compiled.cues.map { it.text })
        assertEquals(listOf(1.0, Cue.DEFAULT_DURATION), compiled.cues.map { it.duration })
    }

    // ---- validation -------------------------------------------------------------

    @Test
    fun `a good file has no problems`() {
        assertEquals(emptyList(), problems(file(cues = listOf(Cue(time = 1.0, event = "boom", text = "<red>Hi", duration = 2.0)))))
    }

    // What each code is for is testdata/invalid/cutscene-semantics; the key limit is too many keys for a golden.

    @Test
    fun `too many keys are refused`() {
        val many = (0..CutsceneFile.MAX_KEYS).map { position(it.toDouble(), 0.0) }
        assertTrue("cutscene.key-limit" in codes(file(positions = many)))
    }

    // ---- the written form -------------------------------------------------------

    @Test
    fun `keys and cues are written in time order, and what's written reads back the same`() {
        val shuffled = file(
            positions = listOf(position(2.0, 10.0), position(0.0, 0.0, Easing.EASE_OUT)),
            rotations = listOf(look(1.0, 90.0), look(0.0, 0.0)),
            cues = listOf(Cue(time = 2.0, event = "b"), Cue(time = 1.0, event = "a"))
        )
        val text = CutsceneKind.write(shuffled)
        assertTrue(text.startsWith("{\n  \"\$schema\": \"../.netherforge/schema/cutscene.schema.json\""), text)
        val parsed = CutsceneKind.parse(text, "cutscenes/x.json") as CanonicalJson.Parsed.Ok
        assertEquals(listOf(0.0, 2.0), parsed.value.camera.position.map { it.time })
        assertEquals(listOf(0.0, 1.0), parsed.value.camera.rotation.map { it.time })
        assertEquals(listOf("a", "b"), parsed.value.cues.map { it.event })
        assertEquals(text, CutsceneKind.write(parsed.value))
    }

    @Test
    fun `the template is a cutscene that plays`() {
        val files = CutsceneKind.template("start")
        val text = files.getValue(CutsceneKind.pathOf("start"))
        val parsed = CutsceneKind.parse(text, "cutscenes/start.json") as CanonicalJson.Parsed.Ok
        assertEquals(emptyList(), problems(parsed.value))
    }
}
