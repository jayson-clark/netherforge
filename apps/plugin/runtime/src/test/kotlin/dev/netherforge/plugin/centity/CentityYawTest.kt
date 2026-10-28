package dev.netherforge.plugin.centity
import dev.netherforge.format.centity.Channel
import dev.netherforge.format.math.Vec3
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.ClickButton
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.platform.Ray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A centity's yaw where scripts don't see it directly: the
 * entities it shows, its hitboxes and clicks, physics, and persistence.
 *
 * Yaw 90 faces west: the centity's +Z is the world's −x and its +X the
 * world's +z, so a centity point (x, y, z) is at anchor + (−z, y, x).
 */
class CentityYawTest {
    private val at = Location("world", 0.0, 64.0, 0.0)

    /** One node two blocks along the centity's +X, a unit-cube hitbox and a stone display. */
    private val bar = mapOf(
        "centities/bar/centity.json" to """
            {
              "script": { "file": "end.lua" },
              "nodes": {
                "end": {
                  "transform": { "translation": [2, 0, 0] },
                  "display": { "type": "block", "block": "minecraft:stone" },
                  "hitbox": { "raycast": true }
                }
              },
              "script": { "file": "script.lua" }
            }
        """,
        "centities/bar/script.lua" to """
            this:node("end"):on("click", function(event)
              log(tostring(event.hit_position), tostring(event.hit_normal))
            end)
        """
    )

    private fun crate(physics: String = "{}", translation: String = "[0, 3, 0]") = mapOf(
        "centities/crate/centity.json" to """
            {
              "nodes": {
                "box": {
                  "transform": { "translation": $translation },
                  "display": { "type": "block", "block": "minecraft:stone" },
                  "hitbox": {},
                  "physics": $physics
                }
              }
            }
        """
    )

    private fun near(want: Vec3, got: Vec3, tolerance: Double = 1e-9) {
        assertTrue(
            kotlin.math.abs(want.x - got.x) <= tolerance &&
                kotlin.math.abs(want.y - got.y) <= tolerance &&
                kotlin.math.abs(want.z - got.z) <= tolerance,
            "want $want, got $got"
        )
    }

    @Test
    fun `yaw wraps the way Minecraft wraps a facing`() {
        assertEquals(0.0, Instance.wrapDegrees(360.0))
        assertEquals(-90.0, Instance.wrapDegrees(270.0))
        assertEquals(-180.0, Instance.wrapDegrees(180.0))
        assertEquals(179.0, Instance.wrapDegrees(-181.0))
        assertEquals(0.0, Instance.wrapDegrees(Double.NaN))
    }

    @Test
    fun `a turned centity shows its displays and hitboxes turned, the entities themselves unturned`() {
        TestServer(bar).use { server ->
            val bar = server.runtime.session.centities.spawn("bar", at, 90.0)!!
            val display = server.platform.entities.display(bar.id, "end")
            assertEquals(at, display.location, "the display stands at the anchor, facing nowhere")
            near(Vec3(0.0, 0.0, 2.0), display.pose!!.matrix.translation())
            // The block's +X edge runs south: the matrix's first column is the world's +z.
            near(Vec3(0.0, 0.0, 1.0), display.pose!!.matrix.transformDirection(Vec3(1.0, 0.0, 0.0)))
            // The unit cube spans x 2..3, z 0..1 in the centity: x −1..0, z 2..3 in the world.
            val hitbox = server.platform.entities.hitbox(bar.id, "end")
            near(Vec3(-0.5, 64.0, 2.5), Vec3(hitbox.location.x, hitbox.location.y, hitbox.location.z))
            assertEquals(1.0, hitbox.width, 1e-9)

            // Turning moves everything round the anchor on the next sync.
            server.runtime.session.centities.turn(bar, 180.0)
            server.tick()
            near(Vec3(-2.0, 0.0, 0.0), display.pose!!.matrix.translation())
            near(Vec3(-2.5, 64.0, -0.5), Vec3(hitbox.location.x, hitbox.location.y, hitbox.location.z))
        }
    }

    @Test
    fun `clicks on a turned centity land where its boxes really are`() {
        TestServer(bar).use { server ->
            val bar = server.runtime.session.centities.spawn("bar", at, 90.0)!!
            val hitbox = server.platform.entities.hitbox(bar.id, "end")
            val alex = server.player()
            // From the south, looking north into the box's south face (z = 3).
            server.runtime.events.entityClicked(hitbox.id, alex.ref, ClickButton.RIGHT, Ray("world", -0.5, 64.5, 10.0, 0.0, 0.0, -1.0))
            // Down through where the box would be unturned (x 2..3, z 0..1): nothing there.
            server.runtime.events.entityClicked(hitbox.id, alex.ref, ClickButton.RIGHT, Ray("world", 2.5, 66.0, 0.5, 0.0, -1.0, 0.0))
            assertEquals(listOf("vec3(-0.5, 64.5, 3)\tvec3(0, 0, 1)"), server.logs)
        }
    }

    @Test
    fun `a body on a turned centity lands on the world block under it`() {
        TestServer(crate(translation = "[2, 3, 0]")).use { server ->
            server.platform.worlds.groundBelow = 50
            // Only under the turned box: centity x 2..3, z 0..1 is world x −1..0, z 2..3.
            server.platform.worlds.solid += Triple(-1, 63, 2)
            val crate = server.runtime.session.centities.spawn("crate", at, 90.0)!!
            server.tick(80)
            near(Vec3(2.0, 0.0, 0.0), crate.get(0, Channel.TRANSLATION), 0.02)
            near(Vec3(0.0, 0.0, 0.0), crate.get(0, Channel.ROTATION), 0.5)
            near(Vec3(0.0, 64.0, 2.0), crate.worldMatrix(0).translation(), 0.02)
            assertTrue(crate.body(0)!!.onGround)
        }
    }

    @Test
    fun `an impulse along the world's +X moves a turned body along the world's +X`() {
        val sliding = """{ "friction": 0, "drag": 0, "rotates": false }"""
        TestServer(
            crate(physics = sliding, translation = "[0, 0, 0]") + mapOf(
                "modules/t/init.lua" to """
                    nf.commands.register("push", function()
                      local box = nf.centities.all()[1]:node("box")
                      assert(box:apply_impulse(vec3(4, 0, 0)))
                      local v = box:velocity()
                      log(("%.3f %s %s"):format(v.x, tostring(math.abs(v.z) < 1e-6), tostring(math.abs(v.y) < 0.1)))
                    end)
                """
            )
        ).use { server ->
            val crate = server.runtime.session.centities.spawn("crate", at, 90.0)!!
            server.tick(5)
            val start = crate.worldMatrix(0).translation()
            server.platform.commands.runConsole("push")
            assertEquals(listOf("4.000 true true"), server.logs, "velocity reads back in the world's axes")
            server.tick(10)
            val moved = crate.worldMatrix(0).translation() - start
            near(Vec3(2.0, 0.0, 0.0), moved, 0.05)
            // In the centity's own space, the world's +X is its −Z.
            assertTrue(crate.get(0, Channel.TRANSLATION).z < -1.9, "${crate.get(0, Channel.TRANSLATION)}")

            // Turning the centity turns the moving body with it: +X becomes +Z.
            server.runtime.session.centities.turn(crate, 180.0)
            near(Vec3(0.0, 0.0, 4.0), crate.body(0)!!.velocity, 1e-9)
        }
    }

    @Test
    fun `the yaw is saved, and survives a restart and a reload`() {
        val project = bar
        TestServer(project).use { server ->
            val bar = server.runtime.session.centities.spawn("bar", at, 90.0)!!
            server.runtime.session.centities.turn(bar, 45.0)
            assertEquals(45.0, server.runtime.session.centities.records().single().yaw)

            server.restart()
            val back = server.runtime.session.centities.all().single()
            assertEquals(45.0, back.yaw)
            server.tick()
            val pose = server.platform.entities.display(back.id, "end").pose!!.matrix
            val s = kotlin.math.sqrt(2.0)
            near(Vec3(s, 0.0, s), pose.translation())

            server.reload("centities/bar/centity.json")
            assertEquals(45.0, server.runtime.session.centities.all().single().yaw)
        }
    }
}
