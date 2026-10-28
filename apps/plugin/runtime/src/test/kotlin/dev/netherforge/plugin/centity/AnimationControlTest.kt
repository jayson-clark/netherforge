package dev.netherforge.plugin.centity
import dev.netherforge.format.centity.Channel
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.Location
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Playing animations under a script's control: speed,
 * starting part way in, overriding the loop, blending in, pausing, seeking.
 * The clip slides a node one block along x over a second (20 ticks).
 */
class AnimationControlTest {
    private val slider = """
        {
          "nodes": { "block": { "display": { "type": "block", "block": "minecraft:stone" } } },
          "animations": {
            "slide": { "tracks": { "block": { "translation": [{ "time": 0, "value": [0, 0, 0] }, { "time": 1, "value": [1, 0, 0] }] } } },
            "spin": { "loop": "loop", "tracks": { "block": { "rotation": [{ "time": 0, "value": [0, 0, 0] }, { "time": 1, "value": [0, 90, 0] }] } } }
          },
          "script": { "file": "script.lua" }
        }
    """

    private fun server(script: String) = TestServer(
        mapOf("centities/slider/centity.json" to slider, "centities/slider/script.lua" to script)
    )

    private fun TestServer.spawn() = assertNotNull(runtime.session.centities.spawn("slider", Location("world", 0.0, 64.0, 0.0)))

    private fun near(want: Double, got: Double) = assertEquals(want, got, 1e-9)

    @Test
    fun `speed and a starting tick`() {
        server("this:play_animation(\"slide\", { speed = 2, from_tick = 4 })").use { server ->
            val slider = server.spawn()
            near(0.2, slider.get(0, Channel.TRANSLATION).x)
            server.tick(4)
            // Four ticks at twice the speed: eight ticks further.
            near(0.6, slider.get(0, Channel.TRANSLATION).x)
            server.tick(5)
            assertTrue(!slider.animations.isPlaying("slide"), "ended early, at double speed")
        }
    }

    @Test
    fun `pause, resume, seek, read the tick and change speed`() {
        server(
            """
            this:play_animation("slide")
            nf.after(5, function()
              log(this:animation_tick("slide"), this:pause_animation("slide"))
            end)
            nf.after(10, function()
              log(this:animation_tick("slide"), this:is_animation_playing("slide"), this:resume_animation("slide"))
              log(this:seek_animation("slide", 10), this:animation_tick("slide"))
              log(this:set_animation_speed("slide", 0.5), this:animation_speed("slide"))
            end)
            log(select(2, pcall(this.pause_animation, this, "nope")):match("no animation") ~= nil)
            log(this:pause_animation("spin"), tostring(this:animation_tick("spin")), tostring(this:animation_speed("spin")))
            log(select(2, pcall(this.set_animation_speed, this, "slide", -1)):match("negative") ~= nil)
            """
        ).use { server ->
            val slider = server.spawn()
            server.tick(12)
            assertEquals(
                listOf(
                    "true",
                    "false\tnil\tnil",
                    "true",
                    // Timers run before the tick's animation step: four steps by then.
                    "4.0\ttrue",
                    "4.0\ttrue\ttrue",
                    "true\t10.0",
                    "true\t0.5"
                ),
                server.logs.map { it.replace(Regex("(\\d)\\.0000+\\d*"), "$1.0") }
            )
            // Seeked to the middle, then three more steps at half speed.
            near(0.575, slider.get(0, Channel.TRANSLATION).x)
        }
    }

    @Test
    fun `loop overrides the file's loop mode`() {
        server(
            """
            this:play_animation("slide", { loop = true })
            this:play_animation("spin", { loop = false })
            """
        ).use { server ->
            val slider = server.spawn()
            server.tick(30)
            assertTrue(slider.animations.isPlaying("slide"), "loops")
            near(0.5, slider.get(0, Channel.TRANSLATION).x)
            assertTrue(!slider.animations.isPlaying("spin"), "played once")
        }
    }

    @Test
    fun `blending eases from the pose shown into the clip`() {
        server(
            """
            this:node("block"):set_translation(vec3(4, 0, 0))
            this:play_animation("slide", { blend_ticks = 10, from_tick = 10 })
            """
        ).use { server ->
            val slider = server.spawn()
            // Starts where it was shown, not at the clip's frame.
            near(4.0, slider.get(0, Channel.TRANSLATION).x)
            server.tick(5)
            // Half blended, toward the clip's 0.75 (tick 15).
            near(4.0 + (0.75 - 4.0) * 0.5, slider.get(0, Channel.TRANSLATION).x)
            server.tick(5)
            // The clip has ended, and the node is back at the rest pose the script set.
            assertTrue(!slider.animations.isPlaying("slide"))
            near(4.0, slider.get(0, Channel.TRANSLATION).x)
        }
    }

    @Test
    fun `bad options are errors`() {
        server(
            """
            log(select(2, pcall(this.play_animation, this, "slide", { speed = -1 })):match("negative") ~= nil)
            log(select(2, pcall(this.play_animation, this, "slide", { blend = 3 })) ~= nil)
            log(select(2, pcall(this.play_animation, this, "slide", { from_tick = -2 })):match("negative") ~= nil)
            log(select(2, pcall(this.seek_animation, this, "slide", -2)):match("negative") ~= nil)
            """
        ).use { server ->
            server.spawn()
            assertEquals(listOf("true", "true", "true", "true"), server.logs)
        }
    }
}
