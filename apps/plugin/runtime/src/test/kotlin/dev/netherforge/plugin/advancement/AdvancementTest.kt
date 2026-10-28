package dev.netherforge.plugin.advancement
import dev.netherforge.format.Problem
import dev.netherforge.format.bridge.Problems
import dev.netherforge.plugin.TestServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The project's advancements: in the start-up datapack the server started
 * with, granted and read by scripts by id, and a reload that changes them
 * telling the editor the server must restart.
 */
class AdvancementTest {
    private val root = """
        { "display": { "icon": { "kind": "minecraft:book" }, "title": "Quests", "background": "minecraft:block/stone" },
          "criteria": { "joined": {} } }
    """.trimIndent()

    private val hunt = """
        { "parent": "quests", "display": { "icon": { "kind": "minecraft:diamond" }, "title": "<glyph:ui/coin> Hunt" },
          "criteria": { "first": {}, "second": {}, "either": {}, "or": {} },
          "requirements": [["first"], ["second"], ["either", "or"]] }
    """.trimIndent()

    private val pack = """{ "name": "UI", "glyphs": { "coin": { "texture": "glyph/coin.png" } } }"""

    private fun server(module: String) = TestServer(
        mapOf(
            "advancements/quests.json" to root,
            "advancements/hunt.json" to hunt,
            "resource_packs/ui/pack.json" to pack,
            "resource_packs/ui/textures/glyph/coin.png" to PNG,
            "modules/t/init.lua" to CHECK + module
        ),
        start = false
    ).also {
        it.player("Alex")
        it.start()
    }

    @Test
    fun `the server starts with the project's advancements, in the game's format`() {
        server("").use { server ->
            val started = server.platform.datapacks.started
            assertEquals(
                listOf("data/test/advancement/hunt.json", "data/test/advancement/quests.json", "pack.mcmeta"),
                started.keys.sorted()
            )
            val hunt = started.getValue("data/test/advancement/hunt.json").decodeToString()
            assertTrue("\"parent\": \"test:quests\"" in hunt, hunt)
            // The glyph drawn as the project's packs built it.
            assertTrue("\"text\": \"\uE000 Hunt\"" in hunt, hunt)
            assertTrue("\"trigger\": \"minecraft:impossible\"" in hunt, hunt)
        }
    }

    @Test
    fun `scripts grant and read the project's advancements by id, a criterion at a time`() {
        server(
            """
            local completed = {}
            nf.on("player_complete_advancement", function(event) completed[#completed + 1] = event.advancement end)
            nf.commands.register("run", function()
              local alex = nf.players.get("Alex")
              check("not yet", alex:has_advancement("hunt"), false)
              check("first", alex:grant_advancement("hunt", "first"), true)
              check("again", alex:grant_advancement("hunt", "first"), false)
              check("second", alex:grant_advancement("hunt", "second"), true)
              check("half done", alex:has_advancement("hunt"), false)
              local progress = alex:advancement_progress("hunt")
              check("met", table.concat(progress.done, ","), "first,second")
              check("one of a group", alex:grant_advancement("hunt", "or"), true)
              check("done", alex:has_advancement("hunt"), true)
              check("heard", completed[1], "test:hunt")
              check("written in full", alex:has_advancement("test:hunt"), true)
              check("take one back", alex:revoke_advancement("hunt", "or"), true)
              check("not done", alex:has_advancement("hunt"), false)
              check("all of the root", alex:grant_advancement("quests"), true)
              fails("no criterion", function() alex:grant_advancement("hunt", "third") end,
                "advancement \"test:hunt\" has no criterion \"third\" (it has \"either\", \"first\", \"or\", \"second\")")
              fails("no advancement", function() alex:grant_advancement("nothing") end, "the server has no advancement \"test:nothing\"")
              log("done")
            end)
            """
        ).use { server ->
            server.platform.commands.runConsole("run")
            assertEquals(listOf("done"), server.errors.map { it.message } + server.logs)
        }
    }

    @Test
    fun `changing an advancement asks for a restart, which brings it in`() {
        server(
            """
            nf.commands.register("run", function()
              local alex = nf.players.get("Alex")
              fails("added since the start", function() alex:grant_advancement("late") end,
                "the server has no advancement \"test:late\" yet: it learns the project's advancements as it starts, so restart it")
              log("done")
            end)
            """
        ).use { server ->
            // Saving it as it is changes nothing the server started with.
            assertFalse(server.reload("advancements/hunt.json").restart)
            assertFalse(server.reload("netherforge.json").restart)

            server.write("advancements/late.json", """{ "parent": "quests", "criteria": { "done": {} } }""")
            val result = server.reload("advancements/late.json")
            assertTrue(result.restart)
            assertEquals(listOf("advancement:late" to true), result.resources.map { it.label to it.ok })
            assertTrue(restartProblem(server) != null, "${server.runtime.currentProblems()}")
            server.platform.commands.runConsole("run")
            assertEquals(listOf("done"), server.errors.map { it.message } + server.logs)
            // The editor hears it with the problems: the server runs what it started with.
            assertTrue(server.sent.filterIsInstance<Problems>().last().problems.any { it.code == "runtime.restart" })

            // A change to a script reloads as ever, and still says the server is out of date.
            assertFalse(server.reload("modules/t/init.lua").restart)
            assertTrue(restartProblem(server) != null)

            server.restart()
            assertTrue("data/test/advancement/late.json" in server.platform.datapacks.started)
            assertEquals(null, restartProblem(server))
            assertFalse(server.reload("advancements/late.json").restart)
        }
    }

    @Test
    fun `an advancement with errors stays out of the datapack, and the example's are all in it`() {
        server("").use { server ->
            server.write("advancements/hunt.json", """{ "parent": "nowhere", "criteria": { "first": {} } }""")
            val result = server.reload("advancements/hunt.json")
            assertFalse(result.resources.single().ok)
            assertTrue(result.restart, "hunt left the datapack")
        }
        TestServer(TestServer.example("showcase")).use { server ->
            val advancements = server.platform.datapacks.started.keys.filter { "/advancement/" in it }.sorted()
            assertEquals(
                listOf(
                    "data/library/advancement/gem_collector.json",
                    "data/showcase/advancement/adventurer.json",
                    "data/showcase/advancement/diamonds.json",
                    "data/showcase/advancement/gem_hoarder.json",
                    "data/showcase/advancement/tour.json",
                    "data/showcase/advancement/treasure_hunter.json"
                ),
                advancements
            )
            assertFalse(server.reload("netherforge.json").restart)
        }
    }

    private fun restartProblem(server: TestServer): Problem? = server.runtime.currentProblems().firstOrNull { it.code == "runtime.restart" }

    private companion object {
        /** A 1×1 opaque PNG. */
        val PNG: ByteArray = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg=="
        )

        const val CHECK = """
            function check(label, actual, expected)
              if actual ~= expected then log("FAIL " .. label .. ": " .. tostring(actual) .. " ~= " .. tostring(expected)) end
            end
            function fails(label, fn, message)
              local ok, err = pcall(fn)
              if ok or not tostring(err):find(message, 1, true) then log("FAIL " .. label .. ": " .. tostring(err)) end
            end
        """
    }
}
