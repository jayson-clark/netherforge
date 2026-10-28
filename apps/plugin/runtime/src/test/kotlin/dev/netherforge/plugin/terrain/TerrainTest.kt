package dev.netherforge.plugin.terrain
import dev.netherforge.format.terrain.TerrainBlock
import dev.netherforge.plugin.LuaChecks.runChecks
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.testkit.FakePlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The project's terrains on the fake server: what's published to the adapter (the compiled file, and the state each
 * ore's custom block is held as), a world made or loaded with one by a script or by `netherforge.json`, a changed file or block
 * published again, and the mistakes that are errors. The chunk generation itself is format's (`TerrainTest`) and the Paper
 * adapter's (the integration scenario).
 */
class TerrainTest {
    private val hills = """
        { "terrain": { "base": 70 }, "layers": [{ "block": "minecraft:dirt", "thickness": 2 }],
          "ores": { "gold": { "block": "minecraft:gold_block", "veins": 3 }, "ruby": { "customBlock": "ruby_ore", "veins": 2 } },
          "biomes": { "plains": { "biome": "minecraft:plains" } } }
    """.trimIndent()

    private val files = mapOf(
        "terrain/hills.json" to hills,
        "blocks/ruby_ore/block.json" to "{}",
        "blocks/amber/block.json" to "{}"
    )

    private val module = """
        nf.commands.register("run", function(event)
          local world = nf.worlds.create("realm", { terrain = "hills", seed = 42 })
          log("made " .. tostring(world ~= nil))
        end)
    """.trimIndent()

    private fun manifest(worlds: String = "") =
        """{ "formatVersion": 1, "name": "Test", "namespace": "test", "version": "1.0.0", "minecraft": "26.3"$worlds }"""

    private fun server(extra: Map<String, Any> = emptyMap(), worlds: String = "") =
        TestServer(files + mapOf("netherforge.json" to manifest(worlds), "modules/t/init.lua" to module) + extra)

    @Test
    fun `the compiled generators are published, with the state an ore's custom block is held as`() {
        server().use { server ->
            val manager = server.platform.worldManager
            val published = assertNotNull(manager.generators["hills"])
            assertEquals(listOf("hills"), manager.generators.keys.toList())
            // Every block of the palette is written as a state: vanilla blocks as they are, the ore as its note block state.
            val palette = published.terrain.palette
            assertEquals(palette.size, published.states.size)
            val ruby = published.states[palette.indexOf(TerrainBlock.Custom("ruby_ore"))]
            assertEquals(server.runtime.session.customBlocks.stateOf("ruby_ore"), ruby)
            assertTrue(ruby.startsWith("minecraft:note_block["), ruby)
            assertEquals("minecraft:gold_block", published.states[palette.indexOf(TerrainBlock.Vanilla("minecraft:gold_block"))])
            assertEquals(emptyList(), server.runtime.session.problems().filter { it.file.startsWith("terrain/") })
        }
    }

    @Test
    fun `a script makes a world with one, and the world keeps it across a restart`() {
        server().use { server ->
            assertEquals(listOf("made true"), server.runChecks())
            val made = assertNotNull(server.platform.worldManager.created["realm"])
            assertEquals("hills", made.terrain)
            assertEquals("normal", made.generator)
            assertEquals(42L, made.seed)
            assertEquals(mapOf("realm" to "hills"), server.runtime.store.worlds.of("test").mapValues { it.value.terrain })
            server.platform.worldManager.unload("realm", save = true)
            server.restart()
            server.write(
                "modules/t/init.lua",
                """nf.commands.register("run", function() log("loaded " .. tostring(nf.worlds.load("realm") ~= nil)) end)"""
            )
            server.restart()
            assertEquals("loaded true", server.runChecks().last())
            assertEquals("realm normal hills", server.platform.worldManager.loads.last())
        }
    }

    @Test
    fun `a terrain that isn't the project's, or with a generator or another environment, is an error that says what there is`() {
        server(
            mapOf(
                "modules/t/init.lua" to """
                    nf.commands.register("run", function()
                      local ok, err = pcall(nf.worlds.create, "a", { terrain = "moon" })
                      log(tostring(ok) .. " " .. tostring(err))
                      ok, err = pcall(nf.worlds.create, "b", { terrain = "hills", environment = "nether" })
                      log(tostring(ok) .. " " .. tostring(err))
                      log(tostring(nf.worlds.create("c", { generator = "flat" }) ~= nil))
                      ok, err = pcall(nf.worlds.create, "d", { terrain = "hills", generator = "flat" })
                      log(tostring(ok) .. " " .. tostring(err))
                      ok, err = pcall(nf.worlds.create, "e", { generator = "hills" })
                      log(tostring(ok) .. " " .. tostring(err))
                    end)
                """.trimIndent()
            )
        ).use { server ->
            val out = server.runChecks()
            assertTrue(
                out[0].startsWith("false ") && "\"moon\" isn't one of the project's terrains" in out[0] && "it has: hills" in out[0],
                out[0]
            )
            assertTrue("normal world, not a nether" in out[1], out[1])
            assertEquals("true", out[2])
            assertTrue(out[3].startsWith("false ") && "not both" in out[3], out[3])
            assertTrue(out[4].startsWith("false ") && "generator" in out[4], out[4])
            assertEquals("flat", server.platform.worldManager.created["c"]?.generator)
            assertEquals(null, server.platform.worldManager.created["c"]?.terrain)
            assertFalse("a" in server.platform.worldManager.created)
        }
    }

    @Test
    fun `saving the file or a block publishes the generators again`() {
        server().use { server ->
            val manager = server.platform.worldManager
            val before = manager.publications
            val old = manager.generators.getValue("hills")
            server.write("terrain/hills.json", hills.replace("\"base\": 70", "\"base\": 90"))
            val result = server.reload("terrain/hills.json")
            assertTrue(result.resources.single().ok)
            assertEquals(before + 1, manager.publications)
            assertEquals(90, manager.generators.getValue("hills").terrain.base)
            assertEquals(70, old.terrain.base, "what a running generator holds is never changed under it")
            // A block added before the ore's moves its state: the ore is written as the new one.
            val rubyBefore = server.runtime.session.customBlocks.stateOf("ruby_ore")
            server.write("blocks/aaa/block.json", "{}")
            assertTrue(server.reload("blocks/aaa/block.json").resources.all { it.ok })
            val rubyAfter = server.runtime.session.customBlocks.stateOf("ruby_ore")
            assertTrue(rubyBefore != rubyAfter)
            val terrain = manager.generators.getValue("hills")
            assertEquals(rubyAfter, terrain.states[terrain.terrain.palette.indexOf(TerrainBlock.Custom("ruby_ore"))])
            // A file with errors keeps the last good generator published.
            server.write("terrain/hills.json", hills.replace("\"base\": 70", "\"base\": 90000"))
            server.reload("terrain/hills.json")
            assertEquals(90, manager.generators["hills"]?.terrain?.base ?: 90)
        }
    }

    @Test
    fun `an ore of a block the server can't hold is left out and said so`() {
        // A game with no note block states to hold the project's blocks in.
        val game = FakePlatform.GAME.copy(blocks = FakePlatform.GAME.blocks - "minecraft:note_block")
        TestServer(files + mapOf("modules/t/init.lua" to module), FakePlatform(game = game)).use { server ->
            val published = assertNotNull(server.platform.worldManager.generators["hills"])
            val ruby = published.terrain.palette.indexOf(TerrainBlock.Custom("ruby_ore"))
            assertEquals("minecraft:stone", published.states[ruby], "the ore is stone replacing stone")
            val problem = server.runtime.session.problems().single { it.code == "runtime.terrain" }
            assertEquals("terrain/hills.json", problem.file)
            assertTrue("ruby_ore" in problem.message)
        }
    }

    @Test
    fun `a decoration's structure is read with the server's loader and linked, again when it's saved`() {
        val trees = hills.replace(
            "\"biomes\":",
            "\"decorations\": { \"trees\": { \"structure\": \"tree\" }, \"rocks\": { \"structure\": \"rock\" } }, \"biomes\":"
        )
        val extra = mapOf(
            "terrain/hills.json" to trees,
            "structures/tree.nbt" to "size 1 2 1\n0 0 0 minecraft:oak_log[axis=y]\n0 1 0 minecraft:oak_leaves",
            "structures/rock.nbt" to "not a structure"
        )
        server(extra).use { server ->
            val manager = server.platform.worldManager
            val linked = manager.generators.getValue("hills").terrain
            val tree = assertNotNull(linked.templates["tree"])
            assertEquals(listOf(1, 2, 1), listOf(tree.sizeX, tree.sizeY, tree.sizeZ))
            assertTrue(TerrainBlock.Vanilla("minecraft:oak_log[axis=y]") in linked.palette, "its blocks are in the palette")
            // The one that can't be read isn't placed, and says so.
            assertEquals(null, linked.templates["rock"])
            val problem = server.runtime.session.problems().single { it.code == "runtime.terrain" }
            assertEquals("terrain/hills.json", problem.file)
            assertTrue("\"rock\"" in problem.message, problem.message)
            // Saving a structure links it afresh.
            server.write("structures/tree.nbt", "size 1 3 1\n0 0 0 minecraft:birch_log[axis=y]")
            assertTrue(server.reload("structures/tree.nbt").resources.all { it.ok })
            val again = manager.generators.getValue("hills").terrain
            assertEquals(3, again.templates.getValue("tree").sizeY)
            assertTrue(TerrainBlock.Vanilla("minecraft:birch_log[axis=y]") in again.palette)
        }
    }

    @Test
    fun `netherforge json makes a world with a generator when the project loads, and loads it with one when it's saved`() {
        val worlds = """, "worlds": { "realm": { "terrain": "hills", "seed": 7 } }"""
        server(worlds = worlds).use { server ->
            val manager = server.platform.worldManager
            val made = assertNotNull(manager.created["realm"])
            assertEquals("hills", made.terrain)
            assertEquals(7L, made.seed)
            assertTrue("realm" in server.runtime.store.worlds.of("test"))
            // Another start: the world is loaded already, so nothing is made again.
            manager.created.clear()
            server.restart()
            assertTrue(manager.created.isEmpty())
            // Saved but not loaded: it's loaded with its generator.
            manager.unload("realm", save = true)
            server.restart()
            assertEquals("realm normal hills", manager.loads.last())
            assertTrue(server.platform.worlds.exists("realm"))
        }
    }

    private fun mainWorld(worlds: String, startupWorlds: List<String> = listOf("world")) =
        TestServer(files + mapOf("netherforge.json" to manifest(worlds), "modules/t/init.lua" to module), startupWorlds = startupWorlds)

    private fun TestServer.problems(code: String) = runtime.session.problems().filter { it.code == code }

    @Test
    fun `the main world gets the generator netherforge json names, published before the world loads`() {
        val worlds = """, "worlds": { "world": { "terrain": "hills" } }"""
        val server = mainWorld(worlds, startupWorlds = emptyList())
        server.use {
            // As the server starts, before the session: it asks, and the generators are published for the world's first chunks.
            server.platform.worldManager.publishGenerators(emptyMap())
            assertEquals("hills", server.runtime.defaultWorldGenerator("world"))
            val published = assertNotNull(server.platform.worldManager.generators["hills"])
            // The ore's state is the one the session holds the block in: the same plan, from the same files.
            val ruby = published.states[published.terrain.palette.indexOf(TerrainBlock.Custom("ruby_ore"))]
            assertEquals(server.runtime.session.customBlocks.stateOf("ruby_ore"), ruby)
        }
        mainWorld(worlds).use { started ->
            assertEquals(mapOf("world" to "hills"), started.startupGeneratorIds)
            assertEquals(emptyList(), started.runtime.session.problems().filter { it.file == "netherforge.json" })
            // The main world is the server's: nothing made, nothing claimed.
            assertTrue("world" !in started.platform.worldManager.created)
            assertTrue("world" !in started.runtime.store.worlds.of("test"))
            // Saving the file is the generator's new chunks, as for any world: no restart.
            started.write("terrain/hills.json", hills.replace("\"base\": 70", "\"base\": 80"))
            val result = started.reload("terrain/hills.json")
            assertFalse(result.restart)
            assertEquals(80, started.platform.worldManager.generators.getValue("hills").terrain.base)
        }
    }

    @Test
    fun `naming another generator for the main world needs a restart, which takes it`() {
        val server = mainWorld(""", "worlds": { "world": { "terrain": "hills" } }""")
        server.use {
            server.write("terrain/plateau.json", hills.replace("\"base\": 70", "\"base\": 110"))
            server.write("netherforge.json", manifest(""", "worlds": { "world": { "terrain": "plateau" } }"""))
            assertTrue(server.reload("netherforge.json").restart)
            val problem = server.problems("runtime.restart").single()
            assertEquals("netherforge.json", problem.file)
            assertEquals("$.worlds.world.terrain", problem.path)
            assertTrue("\"hills\"" in problem.message && "\"plateau\"" in problem.message, problem.message)
            // Until it restarts, the world keeps generating with the one it started with, which is still published.
            assertTrue("hills" in server.platform.worldManager.generators)

            // Back as it started: nothing to restart for.
            server.write("netherforge.json", manifest(""", "worlds": { "world": { "terrain": "hills" } }"""))
            assertFalse(server.reload("netherforge.json").restart)
            assertEquals(emptyList(), server.problems("runtime.restart"))

            // No generator at all is a change too, and so is one for a main world that started without.
            server.write("netherforge.json", manifest(""))
            assertTrue(server.reload("netherforge.json").restart)
            assertTrue("names none" in server.problems("runtime.restart").single().message)

            server.write("netherforge.json", manifest(""", "worlds": { "world": { "terrain": "plateau" } }"""))
            server.restart()
            assertEquals(mapOf("world" to "plateau"), server.startupGeneratorIds)
            assertEquals(emptyList(), server.problems("runtime.restart"))
        }
    }

    @Test
    fun `a main world the server doesn't ask about says how to make it ask`() {
        mainWorld(""", "worlds": { "world": { "terrain": "hills" } }""", startupWorlds = emptyList()).use { server ->
            val problem = server.problems("runtime.restart").single()
            assertTrue("bukkit.yml" in problem.message && "generator: NetherForge" in problem.message, problem.message)
            assertTrue(server.reload("netherforge.json").restart)
        }
        // Nothing named and nothing asked: nothing to say.
        server().use { server -> assertEquals(emptyList(), server.runtime.session.problems().filter { it.file == "netherforge.json" }) }
    }

    @Test
    fun `asked about a main world it names nothing for, the server generates it itself`() {
        mainWorld("").use { server ->
            assertEquals(mapOf("world" to null), server.startupGeneratorIds)
            val problem = server.problems("runtime.terrain").single { it.file == "netherforge.json" }
            assertTrue("names no terrain" in problem.message, problem.message)
            assertEquals(emptyList(), server.problems("runtime.restart"))
        }
        // A generator the project doesn't have is the reference's problem; restarting wouldn't change it.
        mainWorld(""", "worlds": { "world": { "terrain": "moon" } }""").use { server ->
            assertEquals(mapOf("world" to null), server.startupGeneratorIds)
            assertEquals(emptyList(), server.problems("runtime.restart"))
            assertTrue(server.runtime.session.problems().any { it.code == "reference.terrain" })
        }
    }

    @Test
    fun `the main world's seed is the server's`() {
        mainWorld(""", "worlds": { "world": { "terrain": "hills", "seed": 9 } }""").use { server ->
            val problem = server.problems("runtime.terrain").single { it.file == "netherforge.json" }
            assertEquals("$.worlds.world.seed", problem.path)
            assertTrue("level-seed" in problem.message, problem.message)
        }
    }

    @Test
    fun `an area's biome is published as the server knows it, and a project biome with problems is said so`() {
        val areas = hills.replace(
            """"biomes": { "plains": { "biome": "minecraft:plains" } }""",
            """"biomes": { "plains": { "biome": "minecraft:plains" }, "grove": { "biome": "grove", "temperature": { "min": 0.5 } } }"""
        )
        server(mapOf("terrain/hills.json" to areas, "biomes/grove.json" to "{}")).use { server ->
            val manager = server.platform.worldManager
            assertEquals(
                mapOf("grove" to "test:grove", "minecraft:plains" to "minecraft:plains"),
                manager.generators.getValue("hills").biomes
            )
            assertEquals(emptyList(), server.runtime.session.problems().filter { it.code == "runtime.terrain" })
            // A biome's file is data the start-up datapack carries: a change asks for a restart, and one with problems
            // isn't in it, so its areas are plains (the adapter's), which the generator's file is told.
            server.write("biomes/grove.json", """{ "climate": { "downfall": 4 } }""")
            val result = server.reload("biomes/grove.json")
            assertEquals(listOf("biome:grove" to false), result.resources.map { it.label to it.ok })
            assertTrue(result.restart, "the datapack the server started with has the biome as it was")
            val problem = server.runtime.session.problems().single { it.code == "runtime.terrain" }
            assertEquals("terrain/hills.json", problem.file)
            assertTrue("\"grove\"" in problem.message, problem.message)
            server.write("biomes/grove.json", "{}")
            assertFalse(server.reload("biomes/grove.json").restart)
            assertEquals(emptyList(), server.runtime.session.problems().filter { it.code == "runtime.terrain" })
        }
    }

    // ---- a file's Lua stages (W5.6) --------------------------------------------------------------------------

    private val scripted = hills.replace(
        "\"biomes\":",
        "\"script\": { \"budget\": 50000, \"blocks\": [\"minecraft:sea_lantern\"] }, \"biomes\":"
    )

    private val stages = """
        local terrain = ...
        local lift = require("lift")
        return {
          height = function(x, z, height) return height + lift.by end,
          decorate = function(chunk)
            chunk:set(chunk:min_x(), terrain.height(chunk:min_x(), chunk:min_z()) + 1, chunk:min_z(), "minecraft:sea_lantern")
          end,
        }
    """.trimIndent()

    private fun scriptedServer(script: String = stages) = server(
        mapOf("terrain/hills.json" to scripted, "terrain/hills.lua" to script, "modules/lift/init.lua" to "return { by = 5 }")
    )

    private fun TestServer.generator(id: String = "hills") = platform.worldManager.generators.getValue(id).let { published ->
        published.terrain.bind(42, -64, 320, published.scripts)
    }

    @Test
    fun `a script's stages are published with its generator and run in Lua states of their own`() {
        scriptedServer().use { server ->
            val published = server.platform.worldManager.generators.getValue("hills")
            assertEquals(setOf("terrain/hills.lua", "modules/lift/init.lua", "modules/t/init.lua"), published.scripts?.sources?.keys)
            val generator = server.generator()
            val plain = published.terrain.bind(42, -64, 320)
            assertEquals(plain.surfaceAt(3, 9) + 5, generator.surfaceAt(3, 9))
            val glowstone = published.terrain.palette.indexOf(TerrainBlock.Vanilla("minecraft:sea_lantern"))
            assertEquals(glowstone, generator.generate(0, 0)[0, generator.surfaceAt(0, 0) + 1, 0])
            generator.close()
            assertEquals(emptyList(), server.runtime.session.problems().filter { it.file.startsWith("terrain/") })
            // It never ran in the session's own state: nothing of the server heard it.
            assertEquals(emptyList(), server.errors)
        }
    }

    @Test
    fun `saving the script or a module it requires publishes the generator again`() {
        scriptedServer().use { server ->
            val manager = server.platform.worldManager
            val before = manager.publications
            server.write("modules/lift/init.lua", "return { by = 9 }")
            assertTrue(server.reload("modules/lift/init.lua").resources.all { it.ok })
            assertEquals(before + 1, manager.publications)
            assertEquals(server.generator().let { it.surfaceAt(3, 9) - it.terrain.bind(42, -64, 320).surfaceAt(3, 9) }, 9)
            server.write("terrain/hills.lua", "return { height = function(x, z, h) return h - 2 end }")
            assertTrue(server.reload("terrain/hills.lua").resources.all { it.ok })
            assertEquals(before + 2, manager.publications)
            assertEquals(server.generator().let { it.surfaceAt(3, 9) - it.terrain.bind(42, -64, 320).surfaceAt(3, 9) }, -2)
        }
    }

    @Test
    fun `a script that doesn't load is a problem at its line as it's published`() {
        scriptedServer("local x = \n= 1").use { server ->
            val problem = server.problems("terrain.script-failed").single()
            assertEquals("terrain/hills.lua", problem.file)
            assertEquals(2, problem.line)
            assertTrue("didn't load" in problem.message, problem.message)
            // Fixed and saved: the problem goes with the publish.
            server.write("terrain/hills.lua", stages)
            server.reload("terrain/hills.lua")
            assertEquals(emptyList(), server.problems("terrain.script-failed"))
        }
    }

    @Test
    fun `a stage that runs past its budget on a chunk thread is said once, on the main thread, and the chunk is the file's`() {
        val looping = """
            return {
              terrain = function(chunk)
                chunk:fill(chunk:min_x(), 0, chunk:min_z(), chunk:min_x() + 15, 100, chunk:min_z() + 15, "minecraft:sea_lantern")
                while true do end
              end,
            }
        """.trimIndent()
        scriptedServer(looping).use { server ->
            val generator = server.generator()
            val plain = generator.terrain.bind(42, -64, 320)
            // A chunk thread of the server's, not the main one.
            val chunks = java.util.concurrent.Executors.newFixedThreadPool(2)
            try {
                val made = listOf(0 to 0, 1 to 0, 0 to 1).map { (x, z) -> chunks.submit<IntArray> { generator.generate(x, z).blocks } }
                    .map { it.get() }
                assertTrue(made[0].contentEquals(plain.generate(0, 0).blocks), "the file's own chunk")
            } finally {
                chunks.shutdown()
            }
            generator.close()
            assertEquals(emptyList(), server.problems("terrain.script-failed"), "said on the main thread, as it ticks")
            server.tick()
            val problem = server.problems("terrain.script-failed").single()
            assertEquals("terrain/hills.lua", problem.file)
            assertEquals(4, problem.line)
            assertTrue("ran past its budget of 50000 instructions" in problem.message, problem.message)
            assertEquals(1, server.platform.log.lines.count { "ran past its budget" in it }, "logged once")
        }
    }

    // ---- 3D terrain (W5.11) ------------------------------------------------------------------------------------

    private val threeD = hills.replace(
        "\"terrain\": { \"base\": 70 }",
        """"terrain": { "base": 70, "density": { "noises": { "o": { "noise": { "frequency": 0.03 }, "amplitude": 10, "squash": 2 } },
            "islands": { "y": 140, "thickness": 24, "threshold": 0 } } }, "script": { "budget": 50000, "blocks": ["minecraft:sea_lantern"] }"""
    )

    /** A density stage hollowing out a room of the ground, high up, and a lantern on every chunk's topmost ground. */
    private val densityStages = """
        local terrain = ...
        return {
          density = function(x, y, z, value)
            if x >= 0 and x <= 16 and z >= 0 and z <= 16 and y >= 136 and y <= 144 then return -5 end
            return value
          end,
        }
    """.trimIndent()

    @Test
    fun `a 3D generator and its density stage are published and make the same chunks on every chunk thread`() {
        server(mapOf("terrain/hills.json" to threeD, "terrain/hills.lua" to densityStages)).use { server ->
            assertEquals(emptyList(), server.runtime.session.problems().filter { it.file.startsWith("terrain/") })
            val published = server.platform.worldManager.generators.getValue("hills")
            assertNotNull(published.terrain.density)
            val generator = server.generator()
            val chunks = java.util.concurrent.Executors.newFixedThreadPool(3)
            try {
                val places = (-2..2).flatMap { x -> (-2..2).map { z -> x to z } }
                val made = places.map { (x, z) -> chunks.submit<IntArray> { generator.generate(x, z).blocks } }.map { it.get() }
                val alone = published.terrain.bind(42, -64, 320, published.scripts)
                for ((i, place) in places.withIndex()) {
                    assertTrue(made[i].contentEquals(alone.generate(place.first, place.second).blocks), "chunk $place")
                }
                alone.close()
            } finally {
                chunks.shutdown()
            }
            // The room the stage hollows out: air where the islands' band would be solid, in the chunk at 0, 0.
            val chunk = generator.generate(0, 0)
            assertTrue((0 until 16).all { x -> (0 until 16).all { z -> chunk[x, 140, z] == 0 } })
            generator.close()
            assertEquals(emptyList(), server.errors)
        }
    }

    @Test
    fun `a density stage in a file without a density is a problem as it's published`() {
        scriptedServer(densityStages).use { server ->
            val problem = server.problems("terrain.script-failed").single()
            assertEquals("terrain/hills.lua", problem.file)
            assertTrue("terrain.density" in problem.message, problem.message)
        }
    }
}
