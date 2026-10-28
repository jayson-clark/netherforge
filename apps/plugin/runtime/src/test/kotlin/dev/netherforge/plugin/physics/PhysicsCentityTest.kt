package dev.netherforge.plugin.physics
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.centity.Physics
import dev.netherforge.plugin.platform.ClickButton
import dev.netherforge.plugin.platform.Location
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Physics bodies on spawned centities against the fake world: the ground is
 * solid below y = 64, and extra blocks can be placed.
 */
class PhysicsCentityTest {
    private fun crate(physics: String = "{}", script: String = "-- nothing", y: Double = 3.0) = mapOf(
        "centities/crate/centity.json" to """
            {
              "nodes": {
                "box": {
                  "transform": { "translation": [0, $y, 0] },
                  "display": { "type": "block", "block": "minecraft:stone" },
                  "hitbox": {},
                  "physics": $physics
                }
              },
              "script": { "file": "box.lua" }
            }
        """,
        "centities/crate/box.lua" to script
    )

    private fun translation(server: TestServer, id: java.util.UUID) =
        server.runtime.session.centities.find(id)!!.get(0, dev.netherforge.format.centity.Channel.TRANSLATION)

    @Test
    fun `a body falls onto the floor, rests on it and falls asleep`() {
        TestServer(crate()).use { server ->
            val crate = server.runtime.session.centities.spawn("crate", Location("world", 0.0, 64.0, 0.0))!!
            server.tick(10)
            assertTrue(translation(server, crate.id).y < 3.0, "falling")
            server.tick(100)
            val rest = translation(server, crate.id)
            assertEquals(0.0, rest.y, 0.02)
            assertEquals(0.0, rest.x, 0.02)
            val body = crate.body(0)!!
            assertTrue(body.onGround)
            assertTrue(body.asleep)
            // The display followed: its matrix stands on the ground.
            val pose = server.platform.entities.display(crate.id, "box").pose!!
            assertEquals(0.0, pose.matrix.translation().y, 0.02)
        }
    }

    @Test
    fun `a body pushed out over a ledge topples off it`() {
        TestServer(crate(y = 0.0)).use { server ->
            server.platform.worlds.groundBelow = 60
            // A one-block-wide ledge under x 0..1, z 0..1, top at y = 64.
            server.platform.worlds.solid += Triple(0, 63, 0)
            val crate = server.runtime.session.centities.spawn("crate", Location("world", 0.0, 64.0, 0.0))!!
            server.tick(5)
            assertEquals(0.0, translation(server, crate.id).y, 0.02, "resting on the ledge")
            // Shove it two thirds of the way off.
            crate.set(0, dev.netherforge.format.centity.Channel.TRANSLATION, dev.netherforge.format.math.Vec3(0.7, 0.0, 0.0))
            crate.body(0)!!.wake()
            server.tick(80)
            val fallen = translation(server, crate.id)
            assertTrue(fallen.y < -2.5, "fell off the ledge, now at ${fallen.y}")
            val rotation = crate.get(0, dev.netherforge.format.centity.Channel.ROTATION)
            assertTrue(abs(rotation.x) + abs(rotation.y) + abs(rotation.z) > 10, "it turned on the way: $rotation")
        }
    }

    @Test
    fun `bounciness bounces, and scripts read and push the body`() {
        val script = """
            local peak = 0
            this:on("tick", function()
              local box = this:node("box")
              local vy = box:velocity().y
              if vy > peak then peak = vy end
            end)
            function report()
              local box = this:node("box")
              log(("peak %.1f mass %.2f ground %s"):format(peak, box:mass(), tostring(box:is_on_ground())))
            end
            nf.after(60, report)
            nf.after(61, function()
              local box = this:node("box")
              assert(box:apply_impulse(vec3(0, 5, 0)))
              assert(not box:is_asleep())
              log(("after impulse %.1f"):format(box:velocity().y))
              assert(this:node("box"):set_angular_velocity(vec3(0, 90, 0)))
              log(("spin %.0f"):format(box:angular_velocity().y))
            end)
        """
        TestServer(crate(physics = """{ "bounciness": 0.6 }""", script = script)).use { server ->
            server.runtime.session.centities.spawn("crate", Location("world", 0.0, 64.0, 0.0))
            server.tick(62)
            val (peak, after, spin) = server.logs
            assertTrue(peak.startsWith("peak "), peak)
            assertTrue(peak.substringAfter("peak ").substringBefore(" ").toDouble() > 3, "bounced back up: $peak")
            assertTrue(peak.endsWith("mass 1.00 ground true"), peak)
            assertTrue(after.startsWith("after impulse 5."), after)
            assertEquals("spin 90", spin)
        }
    }

    @Test
    fun `a body that travels far takes its anchor along, without moving in the world`() {
        val sliding = """{ "friction": 0, "drag": 0, "rotates": false }"""
        val push = """this:node("box"):set_velocity(vec3(20, 0, 0))"""
        TestServer(crate(physics = sliding, script = push, y = 0.0)).use { server ->
            val crate = server.runtime.session.centities.spawn("crate", Location("world", 0.5, 64.0, 0.5))!!
            var lastWorldX = crate.anchor.x + translation(server, crate.id).x
            repeat(40) {
                server.tick()
                val worldX = crate.anchor.x + translation(server, crate.id).x
                assertTrue(worldX >= lastWorldX - 1e-9 && worldX - lastWorldX < 1.5, "moves smoothly: $lastWorldX → $worldX")
                lastWorldX = worldX
            }
            assertTrue(lastWorldX > 30.0, "slid about 40 blocks, got to $lastWorldX")
            assertTrue(crate.anchor.x >= 16.0, "the anchor followed: ${crate.anchor}")
            assertTrue(translation(server, crate.id).x < Physics.REANCHOR, "and the node is near it again")
            // The display stands at the new anchor, and the index remembers it.
            assertEquals(crate.anchor.x, server.platform.entities.display(crate.id, "box").location.x)
            assertEquals(crate.anchor.x, server.runtime.session.centities.records().single().anchor.x)
        }
    }

    @Test
    fun `a body lands on another centity's hitbox`() {
        TestServer(
            crate() + mapOf(
                "centities/table/centity.json" to """
                    { "nodes": { "top": { "display": { "type": "block", "block": "minecraft:stone" }, "hitbox": { "boxes": [{ "min": [-1, 0, -1], "max": [2, 1, 2] }] } } } }
                """
            )
        ).use { server ->
            server.runtime.session.centities.spawn("table", Location("world", 0.0, 64.0, 0.0))
            val crate = server.runtime.session.centities.spawn("crate", Location("world", 0.0, 64.0, 0.0))!!
            server.tick(80)
            assertEquals(1.0, translation(server, crate.id).y, 0.02, "on the table, a block up")
        }
    }

    private val table = mapOf(
        "centities/table/centity.json" to """
            { "nodes": { "top": { "display": { "type": "block", "block": "minecraft:stone" }, "hitbox": { "boxes": [{ "min": [-1, 0, -1], "max": [2, 1, 2] }] } } } }
        """
    )

    // A falling crate meets a table's top that only just got under it, each
    // way a hitbox can arrive: the centity teleported, its node moved, its node
    // shown again. Physics finds nearby hitboxes through a grid that must hear
    // of every one of these.

    @Test
    fun `a centity teleported under a falling body catches it, and stops once hidden`() {
        TestServer(crate() + table).use { server ->
            val centities = server.runtime.session.centities
            val table = centities.spawn("table", Location("world", 100.0, 64.0, 0.0))!!
            val crate = centities.spawn("crate", Location("world", 0.0, 64.0, 0.0))!!
            server.tick(2)
            centities.teleport(table, Location("world", 0.0, 64.0, 0.0))
            server.tick(80)
            assertEquals(1.0, translation(server, crate.id).y, 0.02, "landed on the table")
            // Hidden, it's nothing to stand on: the crate notices at its next support check.
            table.setVisible(0, false)
            server.tick(80)
            assertEquals(0.0, translation(server, crate.id).y, 0.02, "fell through to the floor")
        }
    }

    @Test
    fun `a node moved under a falling body catches it`() {
        TestServer(crate() + table).use { server ->
            val centities = server.runtime.session.centities
            val table = centities.spawn("table", Location("world", 0.0, 64.0, 0.0))!!
            table.set(0, dev.netherforge.format.centity.Channel.TRANSLATION, dev.netherforge.format.math.Vec3(20.0, 0.0, 0.0))
            val crate = centities.spawn("crate", Location("world", 0.0, 64.0, 0.0))!!
            server.tick(2)
            table.set(0, dev.netherforge.format.centity.Channel.TRANSLATION, dev.netherforge.format.math.Vec3.ZERO)
            server.tick(80)
            assertEquals(1.0, translation(server, crate.id).y, 0.02, "landed on the top slid under it")
        }
    }

    @Test
    fun `a node shown under a falling body catches it`() {
        TestServer(crate() + table).use { server ->
            val centities = server.runtime.session.centities
            val table = centities.spawn("table", Location("world", 0.0, 64.0, 0.0))!!
            table.setVisible(0, false)
            val crate = centities.spawn("crate", Location("world", 0.0, 64.0, 0.0))!!
            server.tick(2)
            table.setVisible(0, true)
            server.tick(80)
            assertEquals(1.0, translation(server, crate.id).y, 0.02, "landed on the top shown under it")
        }
    }

    @Test
    fun `the crate example settles and says so`() {
        TestServer(TestServer.example("showcase")).use { server ->
            // Away from where the showcase's expo builds (its hub floor is at the origin).
            server.runtime.session.centities.spawn("crate", Location("world", 400.0, 64.0, -400.0))
            server.tick(120)
            val line = server.logs.single { it.startsWith("crate settled at") }
            assertEquals(0.0, line.substringAfter("at ").toDouble(), 0.02)
        }
    }

    @Test
    fun `the pool example breaks away from the player and reracks`() {
        TestServer(TestServer.example("showcase")).use { server ->
            // Standing west of the cue ball, so the shot runs east into the pack.
            val alex = server.player("Alex")
            alex.location = Location("world", 396.0, 64.0, -400.0)
            // The skirt's underside on the ground, away from where the showcase's expo builds.
            val pool = server.runtime.session.centities.spawn("pool", Location("world", 400.0, 64.55, -400.0))!!
            fun at(node: String) = pool.get(pool.indexOf(node)!!, dev.netherforge.format.centity.Channel.TRANSLATION)
            server.tick(20)
            val cue = at("ball_cue")
            val apex = at("ball_1")
            assertEquals(0.125, cue.y, 0.02, "resting on the bed")

            val hitbox = server.platform.entities.hitbox(pool.id, "ball_cue")
            assertTrue(server.runtime.events.entityClicked(hitbox.id, alex.ref, ClickButton.LEFT, null))
            server.tick(10)
            assertTrue(at("ball_cue").x > cue.x + 1.0, "the cue ball runs east")
            assertEquals(0.0, at("ball_cue").z, 0.05, "and straight")
            server.tick(10)
            assertTrue(at("ball_1").x > apex.x + 0.1, "and breaks the rack")
            assertTrue(server.platform.sounds.played.any { "minecraft:block.note_block.hat" in it }, "with a clack")

            val bed = server.platform.entities.hitbox(pool.id, "bed")
            assertTrue(server.runtime.events.entityClicked(bed.id, alex.ref, ClickButton.LEFT, null))
            assertEquals(cue.x, at("ball_cue").x, 1e-9, "reracked")
            assertEquals(apex.x, at("ball_1").x, 1e-9, "reracked")
            assertEquals(emptyList(), server.platform.log.lines.filterNot { it.startsWith("INFO") })
        }
    }

    @Test
    fun `without gravity a body floats, and a force holds one up`() {
        val script = """
            local box = this:node("box")
            log(box:has_gravity(), box:set_gravity(false), box:has_gravity())
        """
        TestServer(crate(script = script)).use { server ->
            val crate = server.runtime.session.centities.spawn("crate", Location("world", 0.0, 64.0, 0.0))!!
            assertEquals(listOf("true\ttrue\tfalse"), server.logs)
            server.tick(20)
            assertEquals(3.0, translation(server, crate.id).y, 1e-6)
        }
        val held = """
            local box = this:node("box")
            this:on("tick", function()
              box:apply_force(vec3(0, 32, 0) * box:mass())
            end)
        """
        TestServer(crate(physics = "{ \"drag\": 0 }", script = held)).use { server ->
            val crate = server.runtime.session.centities.spawn("crate", Location("world", 0.0, 64.0, 0.0))!!
            server.tick(20)
            // A force against gravity each tick: it stops speeding up (the push comes a step after
            // gravity's first pull) instead of falling the 3 blocks to the ground.
            assertTrue(translation(server, crate.id).y > 1.0)
        }
    }

    @Test
    fun `a frozen body stays put and is solid to its centity's other bodies`() {
        val files = mapOf(
            "centities/stack/centity.json" to """
                {
                  "nodes": {
                    "shelf": { "transform": { "translation": [0, 2, 0] }, "hitbox": {}, "physics": {} },
                    "crate": { "transform": { "translation": [0, 4, 0] }, "hitbox": {}, "physics": {} }
                  },
                  "script": { "file": "script.lua" }
                }
            """,
            "centities/stack/script.lua" to """
                local shelf = this:node("shelf")
                log(shelf:is_physics_enabled(), shelf:set_physics_enabled(false), shelf:is_physics_enabled())
                log(this:node("crate"):is_kinematic(), shelf:set_velocity(vec3(0, -5, 0)))
            """
        )
        TestServer(files).use { server ->
            val stack = server.runtime.session.centities.spawn("stack", Location("world", 0.0, 64.0, 0.0))!!
            assertEquals(listOf("true\ttrue\tfalse", "false\ttrue"), server.logs)
            server.tick(60)
            val shelf = stack.get(stack.indexOf("shelf")!!, dev.netherforge.format.centity.Channel.TRANSLATION)
            val crate = stack.get(stack.indexOf("crate")!!, dev.netherforge.format.centity.Channel.TRANSLATION)
            assertEquals(2.0, shelf.y, 1e-6, "frozen, velocity or not")
            assertEquals(3.0, crate.y, 0.05, "resting on the frozen shelf")
        }
    }

    @Test
    fun `a kinematic body moves at the velocity a script gives it, ignoring gravity`() {
        val script = """
            local box = this:node("box")
            log(box:set_kinematic(true), box:is_kinematic())
            box:set_velocity(vec3(2, 0, 0))
        """
        TestServer(crate(script = script)).use { server ->
            val crate = server.runtime.session.centities.spawn("crate", Location("world", 0.0, 64.0, 0.0))!!
            server.tick(10)
            val at = translation(server, crate.id)
            assertEquals(1.0, at.x, 1e-6)
            assertEquals(3.0, at.y, 1e-6)
            assertEquals(listOf("true\ttrue"), server.logs)
        }
    }

    @Test
    fun `body setters say false for a node without physics`() {
        val files = mapOf(
            "centities/c/centity.json" to TestServer.scriptedCentity(),
            "centities/c/script.lua" to """
                local root = this:node("root")
                log(root:apply_force(vec3.up), root:set_gravity(false), root:set_kinematic(true), root:set_physics_enabled(false))
                log(root:has_gravity(), root:is_kinematic(), root:is_physics_enabled())
            """
        )
        TestServer(files).use { server ->
            server.runtime.session.centities.spawn("c", Location("world", 0.0, 64.0, 0.0))!!
            assertEquals(listOf("false\tfalse\tfalse\tfalse", "false\tfalse\tfalse"), server.logs)
        }
    }
}
