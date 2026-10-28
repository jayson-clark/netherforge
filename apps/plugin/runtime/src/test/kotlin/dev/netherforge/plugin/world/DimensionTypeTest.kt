package dev.netherforge.plugin.world
import dev.netherforge.plugin.LuaChecks.runChecks
import dev.netherforge.plugin.TestServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The project's dimension types on the fake server: a world made with one by a script or by `netherforge.json` (its
 * heights are the type's, as the fake reads them from the start-up datapack it started with), kept with the world in the
 * store, the main world's (the overworld type the datapack replaces), and what the server can't do until it restarts.
 * The game's format is format's (`DimensionTypeTest`); the real server's worlds are the Paper contract's and the scenario's.
 */
class DimensionTypeTest {
    private val deep = """{ "minY": -128, "height": 512 }"""

    private fun manifest(worlds: String = "") =
        """{ "formatVersion": 1, "name": "Test", "namespace": "test", "version": "1.0.0", "minecraft": "26.3"$worlds }"""

    private fun server(module: String, worlds: String = "", files: Map<String, String> = mapOf("dimension_types/deep.json" to deep)) =
        TestServer(files + mapOf("netherforge.json" to manifest(worlds), "modules/t/init.lua" to module))

    private fun TestServer.problems(code: String) = runtime.session.problems().filter { it.code == code }

    @Test
    fun `a script makes a world of a dimension, with its heights, kept across a restart`() {
        val module = """
            nf.commands.register("run", function()
              local world = nf.worlds.create("mine", { dimension_type = "deep", generator = "void" })
              log(world:min_height() .. " " .. world:max_height())
              log(world:fill_blocks(vec3(0, -129, 0), vec3(0, -128, 0), "minecraft:stone") .. " " ..
                world:fill_blocks(vec3(0, 383, 0), vec3(0, 384, 0), "minecraft:stone"))
            end)
        """.trimIndent()
        server(module).use { server ->
            assertEquals(listOf("-128 384", "1 1"), server.runChecks())
            val made = assertNotNull(server.platform.worldManager.created["mine"])
            assertEquals("test:deep", made.dimensionType)
            assertEquals("test:deep", server.runtime.store.worlds.of("test").getValue("mine").dimensionType)
            server.platform.worldManager.unload("mine", save = true)
            server.write("modules/t/init.lua", """nf.commands.register("run", function() log(nf.worlds.load("mine"):min_height()) end)""")
            server.restart()
            // The server keeps the type with the world; loading it is loading it.
            assertEquals("-128", server.runChecks().last())
        }
    }

    @Test
    fun `a dimension that isn't the project's, has errors, or the server didn't start with is an error`() {
        val module = """
            nf.commands.register("run", function()
              for _, name in ipairs({ "moon", "broken", "later" }) do
                local ok, err = pcall(nf.worlds.create, name .. "_world", { dimension_type = name })
                log(tostring(ok) .. " " .. tostring(err))
              end
            end)
        """.trimIndent()
        server(
            module,
            files = mapOf("dimension_types/deep.json" to deep, "dimension_types/broken.json" to """{ "minY": -7 }""")
        ).use { server ->
            // Added after the server started: the project has it, the server doesn't until it restarts.
            server.write("dimension_types/later.json", deep)
            assertTrue(server.reload("dimension_types/later.json").restart, "a dimension type is start-up datapack data")
            val out = server.runChecks()
            assertTrue(out[0].startsWith("false ") && "\"moon\" isn't one of the project's dimension types (it has: deep" in out[0], out[0])
            assertTrue("\"broken\" has errors" in out[1], out[1])
            assertTrue("no dimension type \"test:later\"" in out[2] && "restart" in out[2], out[2])
            assertEquals(emptySet(), server.platform.worldManager.created.keys)
            server.restart()
            assertTrue("test:later" in server.platform.worldManager.dimensionTypes())
        }
    }

    @Test
    fun `netherforge json makes a world of a dimension, and says when a world it names has another`() {
        val worlds = """, "worlds": { "below": { "dimensionType": "deep" }, "lobby": { "dimensionType": "deep" } }"""
        server("", worlds).use { server ->
            val manager = server.platform.worldManager
            assertEquals("test:deep", manager.created["below"]?.dimensionType)
            assertEquals(-128 to 384, server.platform.worlds.heights("below"))
            assertEquals("test:deep", server.runtime.store.worlds.of("test").getValue("below").dimensionType)
            // A world the server had made without it keeps what it has.
            assertEquals("test:deep", manager.created["lobby"]?.dimensionType)
            assertEquals(emptyList(), server.problems("runtime.dimension-type"))
            manager.unload("lobby", save = true)
            server.runtime.store.worlds.forget("lobby")
            server.restart()
            val problem = server.problems("runtime.dimension-type").single()
            assertEquals("netherforge.json", problem.file)
            assertEquals("$.worlds.lobby.dimensionType", problem.path)
            assertTrue("without a dimension type of the project's" in problem.message, problem.message)
            assertTrue("lobby" in server.platform.worlds.names(), "it's loaded as it was")
        }
    }

    @Test
    fun `a world the project made with a dimension the server lost isn't loaded at the overworld's height`() {
        val module = """
            nf.commands.register("run", function()
              local ok, err = pcall(nf.worlds.load, "mine")
              log(tostring(ok) .. " " .. tostring(err))
            end)
        """.trimIndent()
        server(module, """, "worlds": { "mine": { "dimensionType": "deep" } }""").use { server ->
            server.platform.worldManager.unload("mine", save = true)
            server.delete("dimension_types/deep.json")
            server.write("netherforge.json", manifest())
            server.restart()
            assertFalse("mine" in server.platform.worlds.names())
            val out = server.runChecks().single()
            assertTrue(out.startsWith("false ") && "made with dimension type \"test:deep\", which the server doesn't have" in out, out)
        }
    }

    @Test
    fun `the main world's dimension is the overworld type the start-up datapack replaces`() {
        server("", """, "worlds": { "world": { "dimensionType": "deep" } }""").use { server ->
            assertEquals(-128 to 384, server.platform.worlds.heights("world"))
            assertTrue("data/minecraft/dimension_type/overworld.json" in server.platform.datapacks.started)
            // Nothing is made for it, and nothing's wrong.
            assertFalse("world" in server.platform.worldManager.created)
            assertEquals(emptyList(), server.problems("runtime.dimension-type"))
            // Taking it out asks for a restart, as any change to the datapack does.
            server.write("netherforge.json", manifest())
            assertTrue(server.reload("netherforge.json").restart)
            server.restart()
            assertEquals(-64 to 320, server.platform.worlds.heights("world"))
        }
    }
}
