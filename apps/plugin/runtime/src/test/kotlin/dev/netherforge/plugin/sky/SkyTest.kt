package dev.netherforge.plugin.sky
import dev.netherforge.format.ProblemCodes
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.datapack.DatapackRefusal
import dev.netherforge.plugin.datapack.StartupDatapackFiles
import dev.netherforge.plugin.platform.SkyView
import dev.netherforge.plugin.project.ProjectFiles
import dev.netherforge.plugin.testkit.FakePlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Skies (`skies/<id>.json`) over worlds and players on the fake server: what each player is shown (a sky's twins
 * at a step of its fade), fading in and out over the ticks a script gives, a player's own over their world's, and
 * the mistakes.
 */
class SkyTest {
    private val files = mapOf(
        "skies/blood_moon.json" to """{ "sky": "#5a0a0a", "fog": "#3a0606", "fade": 2 }""",
        "skies/eclipse.json" to """{ "sky": "#000000", "fade": 2 }""",
        "modules/t/init.lua" to """
            local world = nf.worlds.default()
            nf.commands.register("set", function() log("set", world:set_sky("blood_moon"), world:sky()) end)
            nf.commands.register("clear", function() world:clear_sky({ fade_ticks = 4 }) end)
            nf.commands.register("mine", function()
              local alex = nf.players.get("Alex")
              alex:set_sky("eclipse", { fade_ticks = 4 })
              log("mine", alex:sky(), world:sky())
            end)
            nf.commands.register("theirs", function() nf.players.get("Alex"):clear_sky() end)
            nf.commands.register("bad", function()
              log(pcall(world.set_sky, world, "nope"))
              log(pcall(world.set_sky, world, "blood_moon", { fade_ticks = -1 }))
            end)
            nf.commands.register("new", function() log(pcall(world.set_sky, world, "aurora")) end)
        """.trimIndent()
    )

    @Test
    fun `a sky is shown to everyone in its world, fades, and a player's own goes over it`() {
        TestServer(files, start = false).use { server ->
            val alex = server.player("Alex")
            server.start()
            val skies = server.platform.skies
            val view = { sky: String, step: Int -> SkyView("test", "test:$sky", step) }
            fun shown() = skies.shown[alex.ref.uuid]
            server.platform.commands.runConsole("set")
            assertEquals("set\ttrue\tblood_moon", server.logs.last())
            server.tick()
            // Set at once: the last step of its fade.
            assertEquals(view("blood_moon", 2), shown())

            // Cleared over 4 ticks: a step down every 2, then the world's own biomes.
            server.platform.commands.runConsole("clear")
            server.tick()
            assertEquals(view("blood_moon", 1), shown())
            server.tick(2)
            assertNull(shown())

            // Their own sky goes over the world's, and the world's comes back when it's cleared.
            server.platform.commands.runConsole("set")
            server.platform.commands.runConsole("mine")
            assertEquals("mine\teclipse\tblood_moon", server.logs.last())
            server.tick(10)
            assertEquals(view("eclipse", 2), shown())
            server.platform.commands.runConsole("theirs")
            server.tick()
            assertEquals(view("blood_moon", 2), shown())
            assertTrue(skies.changes.any { it.second == view("eclipse", 1) }, "faded in a step at a time: ${skies.changes}")

            // A sky the project hasn't got, and a negative fade, are mistakes.
            server.platform.commands.runConsole("bad")
            assertTrue("there's no sky \"nope\"" in server.logs[server.logs.size - 2], server.logs.toString())
            assertTrue("fade_ticks can't be negative" in server.logs.last(), server.logs.last())

            // One added since the server started has no twins until it restarts.
            server.write("skies/aurora.json", """{ "sky": "#30ff90" }""")
            server.reload("skies/aurora.json")
            server.platform.commands.runConsole("new")
            assertTrue("new since the server started" in server.logs.last(), server.logs.last())

            // A stopped session shows everyone the world's own biomes again.
            server.close()
            assertNull(shown())
        }
    }

    @Test
    fun `a sky whose twins the server refused is left out, the others not, with one problem at its file`() {
        val platform = FakePlatform()
        val project = files + mapOf(
            "biomes/grove.json" to """{ "colors": { "sky": "#f2a7c3" } }""",
            "modules/r/init.lua" to
                """nf.commands.register("refused", function() log(pcall(nf.worlds.default().set_sky, nf.worlds.default(), "blood_moon")) end)"""
        )
        TestServer(project, platform = platform, start = false).use { server ->
            server.player("Alex")
            val source = ProjectFiles(server.project, true)
            val whole = StartupDatapackFiles.forStart(
                source.load(null).snapshot,
                platform.datapacks.format!!,
                platform.datapacks::textJson,
                platform.datapacks.mainWorld,
                source::readBytes,
                null,
                platform.datapacks.game
            )
            assertTrue(whole.canLeaveOut, "a sky's twins are something to leave out")
            // The game's report: an entry per twin of the sky's, each the same complaint.
            val report = (1..2).joinToString("\n") { step ->
                """
                > Errors in registry minecraft:worldgen/biome:
                >> Errors in element test:sky/test/blood_moon/$step/test/grove:
                java.lang.IllegalStateException: Failed to parse: Not a color: #5a0a0a
                """.trimIndent()
            }
            platform.datapacks.refusal = DatapackRefusal(whole.hash, report)
            server.start()

            val twins = platform.datapacks.started.keys.filter { "/worldgen/biome/sky/" in it }
            assertTrue(twins.none { "/blood_moon/" in it }, "$twins")
            assertTrue(twins.any { "/eclipse/" in it }, "$twins")
            val problems = server.runtime.currentProblems().filter { it.code == ProblemCodes.RUNTIME_DATAPACK.code }
            assertEquals(listOf("skies/blood_moon.json"), problems.map { it.file })
            assertTrue("2 of them" in problems.single().message, problems.single().message)

            server.platform.commands.runConsole("refused")
            assertTrue("refused the sky's twin biomes" in server.logs.last(), server.logs.last())
            server.platform.commands.runConsole("mine")
            assertTrue(server.logs.last().startsWith("mine"), server.logs.last())
        }
    }

    @Test
    fun `a sky held at night is shown with its clock, at the step of the fade`() {
        val project = mapOf(
            "skies/blood_moon.json" to """{ "sky": "#5a0a0a", "light": "#ff8080", "darkens": false, "fade": 2 }""",
            "modules/t/init.lua" to
                """nf.commands.register("set", function() nf.worlds.default():set_sky("blood_moon", { fade_ticks = 2 }) end)"""
        )
        // 26.3's data pack format, which has world clocks; and 1.21.11's, which hasn't: the twins alone.
        for ((format, clock) in listOf(listOf(121, 0) to "test:sky/test/blood_moon", listOf(94, 1) to null)) {
            val platform = FakePlatform().also { it.datapacks.format = format }
            TestServer(project, platform = platform, start = false).use { server ->
                val alex = server.player("Alex")
                server.start()
                server.platform.commands.runConsole("set")
                server.tick(10)
                assertEquals(SkyView("test", "test:blood_moon", 2, clock), server.platform.skies.shown[alex.ref.uuid], "$format")
            }
        }
    }
}
