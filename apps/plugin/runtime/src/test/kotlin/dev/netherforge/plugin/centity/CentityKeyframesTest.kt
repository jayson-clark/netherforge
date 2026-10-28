package dev.netherforge.plugin.centity
import dev.netherforge.format.math.Matrix4
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.DisplayPose
import dev.netherforge.plugin.platform.Location
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * While only clips move a node, its display is sent the pose some ticks on
 * with that many ticks to ease there, as far as the client's straight-line
 * easing follows the clip; anything else is sent every tick as before.
 */
class CentityKeyframesTest {
    private fun centity(easing: String = "linear", loop: String = "hold", channel: String = "translation", to: String = "[2, 0, 0]") = """
        {
          "nodes": {
            "body": { "display": { "type": "block", "block": "minecraft:stone" } },
            "arm": { "parent": "body", "transform": { "translation": [1, 0, 0] }, "display": { "type": "block", "block": "minecraft:stone" } }
          },
          "animations": {
            "move": {
              "loop": "$loop",
              "tracks": { "body": { "$channel": [{ "time": 0, "value": [0, 0, 0], "easing": "$easing" }, { "time": 1, "value": $to }] } }
            }
          },
          "script": { "file": "script.lua" }
        }
    """

    /** Each pose a display was sent, with the tick it was sent on. */
    private data class Sent(val tick: Long, val pose: DisplayPose)

    /** Spawns the centity, runs [ticks] ticks, and returns the poses node [node] was sent and where the server had it each tick. */
    private fun run(
        json: String,
        script: String = "this:play_animation(\"move\")",
        ticks: Int = 30,
        node: String = "arm"
    ): Pair<List<Sent>, Map<Long, Matrix4>> {
        TestServer(mapOf("centities/c/centity.json" to json, "centities/c/script.lua" to script)).use { server ->
            val instance = server.runtime.session.centities.spawn("c", Location("world", 0.0, 64.0, 0.0))!!
            val display = server.platform.entities.display(instance.id, node)
            val index = instance.indexOf(node)!!
            val sent = mutableListOf<Sent>()
            val placed = HashMap<Long, Matrix4>()
            var seen = display.poses
            repeat(ticks) {
                server.tick()
                placed[instance.age] = instance.placed()[index].copy()
                if (display.poses != seen) sent += Sent(instance.age, display.pose!!)
                seen = display.poses
            }
            return sent to placed
        }
    }

    /**
     * Every pose eased over more than a tick is where the clip has the node
     * by the time the client gets there; one over a tick is where it is
     * then or a tick on.
     */
    private fun assertLands(sent: List<Sent>, placed: Map<Long, Matrix4>) {
        for ((tick, pose) in sent) {
            val ahead = placed[tick + pose.interpolationTicks] ?: continue
            val what = "sent on tick $tick over ${pose.interpolationTicks}"
            if (pose.interpolationTicks > 1) {
                assertTrue(near(ahead, pose.matrix), what)
            } else {
                assertTrue(near(ahead, pose.matrix) || near(placed.getValue(tick), pose.matrix), what)
            }
        }
    }

    private fun near(want: Matrix4, got: Matrix4) = (0..15).all { kotlin.math.abs(want.values[it] - got.values[it]) < 1e-9 }

    @Test
    fun `a linear move is sent once, eased over its whole length`() {
        val (sent, placed) = run(centity())
        assertLands(sent, placed)
        // The first tick sends where it is (the clip was started); then one move to the end.
        assertTrue(sent.size <= 3, "$sent")
        assertTrue(sent.any { it.pose.interpolationTicks >= 15 }, "$sent")
    }

    @Test
    fun `a child of a turning node follows an arc, in short straight pieces`() {
        val (sent, placed) = run(centity(channel = "rotation", to = "[0, 90, 0]"))
        assertLands(sent, placed)
        // The arm swings a quarter circle a block out: straight lines a few ticks long stay within a hundredth.
        assertTrue(sent.size in 3..19, "${sent.size} sent")
    }

    @Test
    fun `an eased move is sent as a few straight pieces`() {
        val (linear) = run(centity())
        val (eased, placed) = run(centity(easing = "ease_in_out"))
        assertLands(eased, placed)
        assertTrue(eased.size > linear.size, "${eased.size} vs ${linear.size}")
        assertTrue(eased.size < 20, "fewer than one a tick: ${eased.size}")
    }

    @Test
    fun `a node a script moves every tick is sent every tick, as the script eases it`() {
        val (sent) = run(
            centity(),
            script = """
                this:play_animation("move")
                local arm = this:node("arm")
                this:on("tick", function(event) arm:set_rotation(vec3(0, event.tick, 0)) end)
            """
        )
        val after = sent.filter { it.tick > 1 }
        assertEquals(29, after.size)
        assertTrue(after.all { it.pose.interpolationTicks == Instance.DEFAULT_INTERPOLATION_TICKS })
    }

    @Test
    fun `a node beside one a script moves is still planned`() {
        val json = centity().replace(
            "\"arm\":",
            "\"head\": { \"display\": { \"type\": \"block\", \"block\": \"minecraft:stone\" } }, \"arm\":"
        )
        val script = """
            this:play_animation("move")
            local head = this:node("head")
            this:on("tick", function(event) head:set_rotation(vec3(0, event.tick, 0)) end)
        """
        val (arm, placed) = run(json, script)
        assertLands(arm, placed)
        assertTrue(arm.size <= 3, "$arm")
        val (head) = run(json, script, node = "head")
        assertTrue(head.size >= 28, "${head.size}")
    }

    @Test
    fun `a step is sent when it happens`() {
        val (sent, placed) = run(centity(easing = "step"))
        assertLands(sent, placed)
        assertTrue(sent.size <= 4, "$sent")
    }

    // ---- the maths alone

    private fun spin(degrees: Double) = Matrix4().rotateY(degrees * PI / 180)

    @Test
    fun `reach follows a steady turn until it's half a turn, never further`() {
        // 7 degrees a tick: the client's turn is exact, the short way round, so up to 175 degrees.
        assertEquals(25, Keyframes.reach(spin(0.0), 40) { spin(it * 7.0) })
        // Not past the ticks asked for.
        assertEquals(20, Keyframes.reach(spin(0.0), 20) { spin(it * 1.0) })
    }

    @Test
    fun `a point carried round a turn is followed only while the chord stays near the arc`() {
        val carried = { tick: Int -> Matrix4().rotateY(tick * 4.5 * PI / 180).translate(1.0, 0.0, 0.0) }
        // A block out, 4.5 degrees a tick: three ticks' chord is within a hundredth of the arc, four's isn't.
        assertEquals(3, Keyframes.reach(carried(0), 20, carried))
    }

    @Test
    fun `reach stops where the motion bends`() {
        val path = { tick: Int -> Matrix4().translate(minOf(tick, 5) * 0.5, 0.0, 0.0) }
        assertEquals(5, Keyframes.reach(path(0), 20, path))
    }

    @Test
    fun `nothing the client couldn't ease is planned`() {
        val sheared = { tick: Int -> Matrix4().scale(1.0 + tick * 0.1, 1.0, 1.0).rotateY(0.3).translate(tick.toDouble(), 0.0, 0.0) }
        assertEquals(1, Keyframes.reach(sheared(0), 20, sheared))
        val collapsing = { tick: Int -> Matrix4().scale(maxOf(0.0, 1.0 - tick), 1.0, 1.0) }
        assertEquals(1, Keyframes.reach(collapsing(0), 20, collapsing))
        // Something that stands still is sent once, as far ahead as asked.
        assertEquals(20, Keyframes.reach(Matrix4(), 20) { Matrix4() })
    }
}
