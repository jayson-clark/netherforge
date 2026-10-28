package dev.netherforge.plugin.centity
import dev.netherforge.format.math.Vec3
import dev.netherforge.plugin.LuaChecks
import dev.netherforge.plugin.TestServer
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Centities walking where `move_to` sends them, on the fake world: the
 * ground is solid below y = 64, with whatever blocks a test adds.
 */
class CentityPathTest {
    /** A one-block walker whose hitbox is centred on its position, so its feet are its position. */
    private val walkerFile = """
        {
          "nodes": {
            "body": {
              "transform": { "translation": [-0.5, 0, -0.5] },
              "display": { "type": "block", "block": "minecraft:stone" },
              "hitbox": {}
            }
          }
        }
    """

    /** A tower, authored from its corner as the examples are: its feet are half a block in from its position. */
    private val towerFile = """
        {
          "nodes": {
            "base": {
              "display": { "type": "block", "block": "minecraft:stone" },
              "hitbox": {}
            },
            "top": {
              "parent": "base",
              "transform": { "translation": [0, 1, 0] },
              "display": { "type": "block", "block": "minecraft:stone" },
              "hitbox": {}
            }
          }
        }
    """

    /** Runs [body] as a module's body beside a walker and a tower, then [after] with the server. */
    private fun run(body: String, setup: (TestServer) -> Unit = {}, after: (TestServer) -> Unit = {}) = LuaChecks.runModuleBody(
        body,
        mapOf("centities/walker/centity.json" to walkerFile, "centities/tower/centity.json" to towerFile),
        setup,
        after
    )

    private fun TestServer.only(kind: String): Instance = runtime.session.centities.all().single { it.centity == kind }

    private fun Instance.position() = Vec3(anchor.x, anchor.y, anchor.z)

    private fun near(a: Vec3, b: Vec3, within: Double = 1e-3) =
        sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z)) <= within

    @Test
    fun `a centity walks where move_to sends it, at its speed, and hears path_end`() {
        val result = run(
            """
            local walker = nf.centities.spawn("walker", vec3(0.5, 64, 0.5))
            walker:on("path_end", function(event)
              log("path_end " .. tostring(event.reached) .. " " .. tostring(event.centity == walker))
            end)
            check("sets off", walker:move_to(vec3(10.5, 64, 0.5), { speed = 4 }), true)
            check("walking", walker:has_path(), true)
            check("heading", walker:path_target(), vec3(10.5, 64, 0.5))
            """,
            after = { server ->
                val walker = server.only("walker")
                server.tick(20)
                assertTrue(near(walker.position(), Vec3(4.5, 64.0, 0.5)), "four blocks in a second: ${walker.position()}")
                assertEquals(-90.0, walker.yaw, 1e-9, "facing east, the way it goes")
                server.tick(60)
                assertTrue(near(walker.position(), Vec3(10.5, 64.0, 0.5)), "there: ${walker.position()}")
                assertNull(walker.walk)
                // The displays went with it.
                val display = server.platform.entities.display(walker.id, "body")
                assertEquals(10.5, display.location.x, 1e-6)
            }
        )
        assertEquals(listOf("done", "path_end true true"), result)
    }

    @Test
    fun `it goes round walls, up steps and down drops, and falls when nothing holds it up`() {
        val result = run(
            """
            local walker = nf.centities.spawn("walker", vec3(0.5, 70, 0.5))
            walker:on("path_end", function(event) log("path_end " .. tostring(event.reached)) end)
            check("sets off from the air", walker:move_to(vec3(12.5, 65, 0.5), { face = false }), true)
            """,
            setup = { server ->
                val solid = server.platform.worlds.solid
                // A wall to go round, then a step up onto a platform.
                for (z in -3..3) for (y in 64..66) solid += Triple(5, y, z)
                for (x in 9..14) for (z in -3..3) solid += Triple(x, 64, z)
            },
            after = { server ->
                val walker = server.only("walker")
                server.tick(5)
                assertTrue(walker.position().y < 70 && walker.position().y > 64, "falling first: ${walker.position()}")
                var highest = 0.0
                repeat(200) {
                    server.tick()
                    val at = walker.position()
                    // Never inside the wall.
                    // The wall is x 5 to 6, z -3 to 4; the walker a block wide.
                    if (at.y <
                        67
                    ) {
                        assertTrue(abs(at.x - 5.5) >= 1 - 1e-6 || at.z <= -3.5 + 1e-6 || at.z >= 4.5 - 1e-6, "inside the wall at $at")
                    }
                    highest = maxOf(highest, abs(at.z - 0.5))
                }
                assertTrue(highest > 3.5, "went round the end of the wall")
                assertTrue(near(walker.position(), Vec3(12.5, 65.0, 0.5)), "up on the platform: ${walker.position()}")
                assertEquals(0.0, walker.yaw, "face = false keeps its facing")
            }
        )
        assertEquals(listOf("done", "path_end true"), result)
    }

    @Test
    fun `it turns about its feet, and its feet are the middle of its hitboxes`() {
        val result = run(
            """
            local tower = nf.centities.spawn("tower", vec3(0, 64, 0))
            check("sets off", tower:move_to(vec3(0.5, 64, 8.5)), true)
            check("its feet's path", tower:path_target(), vec3(0.5, 64, 8.5))
            """,
            after = { server ->
                val tower = server.only("tower")
                server.tick(60)
                assertNull(tower.walk, "arrived")
                assertEquals(0.0, tower.yaw, 1e-9, "faces south, the way it went")
                // Its middle is over the target, wherever its corner is.
                val middle = tower.toWorld(Vec3(0.5, 0.0, 0.5))
                assertTrue(near(middle, Vec3(0.5, 64.0, 8.5)), "middle at $middle")
            }
        )
        assertEquals(listOf("done"), result)
    }

    @Test
    fun `it flies when asked, and never falls`() {
        val result = run(
            """
            local walker = nf.centities.spawn("walker", vec3(0.5, 70, 0.5))
            walker:on("path_end", function(event) log("path_end " .. tostring(event.reached)) end)
            check("takes off", walker:move_to(vec3(6.5, 75, 4.5), { fly = true, speed = 10 }), true)
            """,
            setup = { server ->
                // A wall of stone it has to fly over or round.
                for (z in -10..10) for (y in 64..73) server.platform.worlds.solid += Triple(3, y, z)
            },
            after = { server ->
                val walker = server.only("walker")
                repeat(40) {
                    server.tick()
                    assertTrue(walker.position().y >= 70 - 1e-9, "never sank: ${walker.position()}")
                }
                assertTrue(near(walker.position(), Vec3(6.5, 75.0, 4.5)), "there: ${walker.position()}")
            }
        )
        assertEquals(listOf("done", "path_end true"), result)
    }

    @Test
    fun `a block put in its way is walked round`() {
        val result = run(
            """
            local walker = nf.centities.spawn("walker", vec3(0.5, 64, 0.5))
            walker:on("path_end", function(event) log("path_end " .. tostring(event.reached)) end)
            walker:move_to(vec3(12.5, 64, 0.5))
            """,
            after = { server ->
                val walker = server.only("walker")
                server.tick(10)
                // A wall across its straight line, after it set off.
                for (z in -3..3) for (y in 64..66) server.platform.worlds.solid += Triple(7, y, z)
                server.tick(150)
                assertTrue(near(walker.position(), Vec3(12.5, 64.0, 0.5)), "got there anyway: ${walker.position()}")
            }
        )
        assertEquals(listOf("done", "path_end true"), result)
    }

    @Test
    fun `no way there is false, and a way only part of the way gives up at its end`() {
        val result = run(
            """
            local walker = nf.centities.spawn("walker", vec3(0.5, 64, 0.5))
            walker:on("path_end", function(event) log("path_end " .. tostring(event.reached)) end)
            nf.commands.register("boxed", function()
              check("boxed in", walker:move_to(vec3(-10.5, 64, 0.5)), false)
              check("not walking", walker:has_path(), false)
            end)
            nf.commands.register("trench", function()
              check("as close as it gets", walker:move_to(vec3(12.5, 64, 0.5), { range = 16 }), true)
            end)
            nf.commands.register("far", function()
              check("out of range", walker:move_to(vec3(100.5, 64, 0.5), { range = 32 }), false)
            end)
            """,
            setup = { server ->
                server.platform.worlds.groundBelow = 40
                for (x in -30..30) for (z in -30..30) if (x !in 5..6) server.platform.worlds.solid += Triple(x, 63, z)
            },
            after = { server ->
                val walker = server.only("walker")
                server.platform.commands.runConsole("far")
                server.platform.commands.runConsole("trench")
                server.tick(100)
                assertTrue(walker.position().x in 3.0..5.0, "stopped at the trench: ${walker.position()}")
                assertEquals(64.0, walker.position().y)
                // Boxed in where it stands.
                val x = kotlin.math.floor(walker.position().x).toInt()
                for (dx in -1..1) {
                    for (dz in -1..1) {
                        for (y in 64..66) {
                            if (dx != 0 ||
                                dz != 0
                            ) {
                                server.platform.worlds.solid += Triple(x + dx, y, dz)
                            }
                        }
                    }
                }
                server.platform.commands.runConsole("boxed")
            }
        )
        assertEquals(listOf("done", "path_end false"), result)
    }

    @Test
    fun `stop_pathing, teleport and a new move_to end a walk without path_end`() {
        val result = run(
            """
            local walker = nf.centities.spawn("walker", vec3(0.5, 64, 0.5))
            walker:on("path_end", function(event) log("path_end " .. tostring(event.reached)) end)
            nf.commands.register("go", function() check("go", walker:move_to(vec3(20.5, 64, 0.5)), true) end)
            nf.commands.register("other", function() check("other", walker:move_to(vec3(0.5, 64, 20.5)), true) end)
            nf.commands.register("stop", function()
              check("stopped", walker:stop_pathing(), true)
              check("again", walker:stop_pathing(), false)
              check("not walking", walker:has_path(), false)
              check("nowhere", walker:path_target(), nil)
            end)
            nf.commands.register("jump", function() walker:teleport(vec3(0.5, 64, 0.5)) end)
            nf.commands.register("check", function() check("teleport ended it", walker:has_path(), false) end)
            fails("too slow", function() walker:move_to(vec3(1, 64, 1), { speed = 0 }) end, "options.speed must be more than 0")
            fails("too wide", function() walker:move_to(vec3(1, 64, 1), { width = 9 }) end, "options.width must be more than 0 and at most 8")
            fails("too far", function() walker:move_to(vec3(1, 64, 1), { range = 1000 }) end, "options.range must be more than 0 and at most 128")
            fails("a typo", function() walker:move_to(vec3(1, 64, 1), { sped = 2 }) end, "sped")
            fails("not a place", function() walker:move_to("there") end, "Location, Vec3, Entity or Centity")
            check("not itself", walker:move_to(walker), false)
            check("another world", walker:move_to(nf.worlds.get("nether"):location(vec3(0, 64, 0))), false)
            """,
            after = { server ->
                val walker = server.only("walker")
                val commands = server.platform.commands
                commands.runConsole("go")
                server.tick(5)
                commands.runConsole("other")
                server.tick(5)
                commands.runConsole("stop")
                val stopped = walker.position()
                server.tick(5)
                assertEquals(stopped, walker.position(), "stays where it stopped")
                commands.runConsole("go")
                server.tick(5)
                commands.runConsole("jump")
                server.tick(5)
                assertEquals(Vec3(0.5, 64.0, 0.5), walker.position(), "the teleport ended the walk")
                commands.runConsole("check")
            }
        )
        assertEquals(listOf("done"), result)
    }

    @Test
    fun `a door it opened is shut behind it when its walk is stopped or replaced, and at once when it's teleported`() {
        val result = run(
            """
            local world = nf.worlds.get("world")
            world:fill_blocks(vec3(5, 64, -3), vec3(5, 65, 3), "minecraft:stone")
            world:set_block(vec3(5, 64, 0), "minecraft:oak_door[half=lower]")
            world:set_block(vec3(5, 65, 0), "minecraft:oak_door[half=upper]")
            local walker = nf.centities.spawn("walker", vec3(0.5, 64, 0.5))
            local through = { speed = 4, width = 0.6, open_doors = true }
            nf.commands.register("go", function() check("go", walker:move_to(vec3(10.5, 64, 0.5), through), true) end)
            nf.commands.register("other", function() check("other", walker:move_to(vec3(8.5, 64, 5.5), through), true) end)
            nf.commands.register("stop", function() walker:stop_pathing() end)
            nf.commands.register("back", function() walker:teleport(vec3(0.5, 64, 0.5)) end)
            """,
            after = { server ->
                val walker = server.only("walker")
                val commands = server.platform.commands
                fun open() = server.platform.blocks.get("world", 5, 64, 0)!!.state.contains("open=true")
                // Stopped short of the door it has just opened: it shuts.
                commands.runConsole("go")
                server.tick(18)
                assertTrue(open(), "opened as it came: ${walker.position()}")
                commands.runConsole("stop")
                server.tick(1)
                assertTrue(!open(), "shut once it stopped outside it")
                // Sent somewhere else from the doorway: it shuts once that walk has taken it away.
                commands.runConsole("go")
                server.tick(7)
                assertTrue(open() && walker.position().x in 5.0..6.0, "in the doorway: ${walker.position()}")
                commands.runConsole("other")
                server.tick(1)
                assertTrue(open(), "still in it")
                server.tick(30)
                assertTrue(!open(), "shut behind it: ${walker.position()}")
                // Stopped in the doorway, it waits for it to leave; a teleport takes it away at once.
                commands.runConsole("back")
                commands.runConsole("go")
                server.tick(25)
                assertTrue(walker.position().x in 5.0..6.0, "in the doorway: ${walker.position()}")
                commands.runConsole("stop")
                server.tick(5)
                assertTrue(open(), "held open while it stands in it")
                commands.runConsole("back")
                assertTrue(!open(), "shut as it went")
                assertTrue(walker.openedDoors.isEmpty())
            }
        )
        assertEquals(listOf("done"), result)
    }

    @Test
    fun `it follows an entity or a centity, until it's within reach or it has gone`() {
        val result = run(
            """
            local world = nf.worlds.default()
            local walker = nf.centities.spawn("walker", vec3(0.5, 64, 0.5))
            local pig = world:spawn_entity("minecraft:pig", vec3(10.5, 64, 0.5))
            local other = nf.centities.spawn("walker", vec3(0.5, 64, 12.5))
            walker:on("path_end", function(event) log("path_end " .. tostring(event.reached)) end)
            check("follows the pig", walker:move_to(pig), true)
            nf.commands.register("centity", function() check("follows the centity", walker:move_to(other), true) end)
            nf.commands.register("remove", function() other:remove() end)
            """,
            after = { server ->
                val walker = server.runtime.session.centities.all().first { it.centity == "walker" }
                val pig = server.platform.worldEntities.mobs.values.single { it.kind == "minecraft:pig" }
                server.tick(10)
                // The pig wanders off; the walk finds it again.
                pig.location = pig.location.copy(x = 4.5, z = 8.5)
                server.tick(60)
                val there = walker.position()
                assertTrue(
                    sqrt((there.x - 4.5) * (there.x - 4.5) + (there.z - 8.5) * (there.z - 8.5)) <= CentityPaths.PATH_REACH + 1e-9,
                    "within reach: $there"
                )
                server.platform.commands.runConsole("centity")
                server.tick(5)
                server.platform.commands.runConsole("remove")
                server.tick()
            }
        )
        assertEquals(listOf("done", "path_end true", "path_end false"), result)
    }

    @Test
    fun `it never walks into a chunk that isn't loaded`() {
        val result = run(
            """
            local walker = nf.centities.spawn("walker", vec3(8.5, 64, 8.5))
            walker:on("path_end", function(event) log("path_end " .. tostring(event.reached)) end)
            check("as far as it can", walker:move_to(vec3(24.5, 64, 8.5)), true)
            """,
            setup = { server -> server.platform.worlds.unloaded += Triple("world", 1, 0) },
            after = { server ->
                val walker = server.only("walker")
                repeat(200) {
                    server.tick()
                    // Chunk (1, 0) is x 16 to 32, z 0 to 16; the walker is a block wide.
                    val at = walker.position()
                    val inside = at.x + 0.5 > 16 + 1e-6 && at.z - 0.5 < 16 - 1e-6 && at.z + 0.5 > 1e-6
                    assertTrue(!inside, "stayed out: $at")
                }
            }
        )
        assertEquals(listOf("done", "path_end false"), result)
    }

    @Test
    fun `its physics bodies are carried along`() {
        val crate = walkerFile.replace("\"hitbox\": {}", "\"hitbox\": {}, \"physics\": { \"rotates\": false }")
        TestServer(
            mapOf(
                "modules/t/init.lua" to """
                    local crate = nf.centities.spawn("crate", vec3(0.5, 64, 0.5))
                    nf.commands.register("go", function() log(tostring(crate:move_to(vec3(6.5, 64, 0.5), { face = false }))) end)
                """.trimIndent(),
                "centities/crate/centity.json" to crate
            )
        ).use { server ->
            val instance = server.only("crate")
            server.tick(20)
            server.platform.commands.runConsole("go")
            server.tick(60)
            assertEquals(listOf("true"), server.logs)
            assertNull(instance.walk, "arrived")
            val body = instance.worldMatrix(0).translation()
            assertEquals(6.0, body.x, 0.05, "the body went too: $body")
            assertEquals(64.0, body.y, 0.05, "resting on the ground there: $body")
        }
    }

    @Test
    fun `a reload drops the walk, an unloaded chunk holds it, and removal forgets it`() {
        val result = run(
            """
            local walker = nf.centities.spawn("walker", vec3(0.5, 64, 0.5))
            walker:on("path_end", function(event) log("path_end " .. tostring(event.reached)) end)
            nf.commands.register("go", function() check("go", walker:move_to(vec3(0.5, 64, 12.5)), true) end)
            nf.commands.register("remove", function() walker:remove() end)
            """,
            after = { server ->
                val walker = server.only("walker")
                val commands = server.platform.commands
                commands.runConsole("go")
                server.tick(5)
                // Its chunk unloads: it waits where it is.
                server.platform.worlds.unloaded += Triple("world", 0, 0)
                val held = walker.position()
                server.tick(10)
                assertEquals(held, walker.position())
                assertNotNull(walker.walk, "still on its way")
                server.platform.worlds.unloaded.clear()
                server.tick(5)
                assertTrue(walker.position().z > held.z, "carried on")
                // A reload of its centity ends the walk, without path_end.
                server.write("centities/walker/centity.json", walkerFile.replace("\"nodes\"", "\"name\": \"Walker\", \"nodes\""))
                val reloaded = server.reload("centities/walker/centity.json")
                assertTrue(reloaded.resources.single().ok, "$reloaded")
                assertNull(server.only("walker").walk)
                commands.runConsole("go")
                server.tick(2)
                commands.runConsole("remove")
                server.tick(5)
            }
        )
        assertEquals(listOf("done"), result)
    }
}
