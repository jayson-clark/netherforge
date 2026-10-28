package dev.netherforge.plugin.centity
import dev.netherforge.plugin.TestServer
import kotlin.test.Test
import kotlin.test.assertEquals

/** Additive animations, played on top of the rest of the pose, and `node:tween`. */
class AnimationLayerTest {
    private val centity = """
        {
          "nodes": { "rig": { "transform": { "translation": [0, 1, 0] } } },
          "animations": {
            "walk": {
              "length": 1,
              "loop": "loop",
              "tracks": { "rig": { "translation": [ { "time": 0, "value": [0, 2, 0] } ] } }
            },
            "squash": {
              "length": 1,
              "loop": "hold",
              "blend": "additive",
              "tracks": {
                "rig": {
                  "scale": [ { "time": 0, "value": [1.5, 0.5, 1.5] } ],
                  "translation": [ { "time": 0, "value": [0, -0.25, 0] } ]
                }
              }
            }
          }
        }
    """.trimIndent()

    private fun server(script: String) = TestServer(
        mapOf(
            "centities/slime/centity.json" to centity,
            "modules/t/init.lua" to """
                local function show(v) return string.format("%.2f,%.2f,%.2f", v.x, v.y, v.z) end
                nf.commands.register("go", function()
                  $script
                end)
            """.trimIndent()
        )
    )

    @Test
    fun `an additive clip changes the pose under it, whatever started first`() {
        server(
            """
            local slime = nf.centities.spawn("slime", vec3(0, 64, 0))
            local rig = slime:node("rig")
            rig:set_scale(vec3(2, 2, 2))
            slime:play_animation("squash")
            slime:play_animation("walk")
            nf.after(1, function()
              log("squashed", show(rig:scale()), show(rig:translation()))
              slime:stop_animation("squash")
              nf.after(1, function() log("after", show(rig:scale()), show(rig:translation())) end)
            end)
            """
        ).use { server ->
            server.platform.commands.runConsole("go")
            server.tick(3)
            assertEquals(emptyList(), server.errors.map { it.message })
            // The script's size times the squash; the walk's height (it started later, but replaces first) less the dip.
            assertEquals(listOf("squashed\t3.00,1.00,3.00\t0.00,1.75,0.00", "after\t2.00,2.00,2.00\t0.00,2.00,0.00"), server.logs)
        }
    }

    @Test
    fun `a tween eases a channel there and back, and replaces one of the same channel`() {
        server(
            """
            local slime = nf.centities.spawn("slime", vec3(0, 64, 0))
            local rig = slime:node("rig")
            check("tweened", rig:tween({ scale = vec3(3, 3, 3), ticks = 4, easing = "linear", back = true }), true)
            -- A callback runs before the centities tick: after(3) sees two steps of the tween.
            nf.after(3, function() log("half", show(rig:scale()), tostring(rig:is_tweening())) end)
            nf.after(5, function() log("there", show(rig:scale())) end)
            nf.after(9, function() log("back", show(rig:scale()), tostring(rig:is_tweening())) end)
            nf.after(10, function()
              rig:tween({ translation = vec3(0, 5, 0), ticks = 10 })
              nf.after(1, function()
                rig:tween({ translation = vec3(0, 1, 0), ticks = 2, easing = "linear" })
                nf.after(3, function() log("replaced", show(rig:translation()), tostring(rig:stop_tween())) end)
              end)
            end)
            local ok, err = pcall(function() rig:tween({ ticks = 3 }) end)
            log("bad", tostring(ok), tostring(pcall(function() rig:tween({ scale = vec3.one, ticks = 0 }) end)),
              tostring(pcall(function() rig:tween({ scale = vec3.one, ticks = 2, easing = "bouncy" }) end)))
            """.replace("check(\"tweened\", ", "log(\"tweened\", tostring(").replace("back = true }), true)", "back = true })))")
        ).use { server ->
            server.platform.commands.runConsole("go")
            server.tick(15)
            assertEquals(emptyList(), server.errors.map { it.message })
            assertEquals(
                listOf(
                    "tweened\ttrue",
                    "bad\tfalse\tfalse\tfalse",
                    "half\t2.00,2.00,2.00\ttrue",
                    "there\t3.00,3.00,3.00",
                    "back\t1.00,1.00,1.00\tfalse",
                    "replaced\t0.00,1.00,0.00\tfalse"
                ),
                server.logs
            )
        }
    }
}
