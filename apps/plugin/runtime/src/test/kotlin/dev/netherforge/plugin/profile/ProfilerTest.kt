package dev.netherforge.plugin.profile
import dev.netherforge.format.bridge.HandlerTime
import dev.netherforge.format.bridge.ProfileSample
import dev.netherforge.format.bridge.SourceRef
import dev.netherforge.plugin.NetherForgeRuntime
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.GameEvent
import dev.netherforge.plugin.platform.Location
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The profiler: exact time per tick step, scope and function, streamed to
 * the editor once a second, and `/nf profile` writing a report. A clock that
 * moves 1 ms every time it's read makes every call in cost exactly 1 ms of its
 * own (as in `ScriptCostsTest`).
 */
class ProfilerTest {
    private fun steppingClock(): () -> Long {
        var now = 0L
        return {
            now += MILLI
            now
        }
    }

    private fun samples(server: TestServer) = server.sent.filterIsInstance<ProfileSample>()

    private fun List<HandlerTime>.at(file: String, line: Int?) = single { it.source == SourceRef(file, line) }

    @Test
    fun `each function scripts run is timed by what it is and where it starts`() {
        TestServer(
            mapOf(
                "modules/shop/init.lua" to """
                    nf.on("tick", function()
                    end)
                    nf.every(5, function()
                    end)
                    nf.after(3, function()
                    end)
                    nf.task(function()
                      while true do nf.wait(10) end
                    end)
                    nf.commands.register("shop", {}, function(event)
                    end)
                    nf.on("player_join", function(event) end)
                """
            ),
            clock = steppingClock()
        ).use { server ->
            server.runtime.profiler.streaming = true
            server.tick(20)
            val alex = server.player("Alex")
            server.platform.raise.playerJoin(GameEvent.PlayerJoin(alex.ref, false, null))
            server.platform.commands.run(alex, "shop")
            server.tick(20)
            val (first, second) = samples(server)
            assertEquals((1L..20L).toList(), first.ticks.map { it.tick })
            val handlers = first.handlers
            // Every handler, timer and task resume is 1 ms of its own.
            assertEquals(
                HandlerTime("module shop", "tick", SourceRef("modules/shop/init.lua", 1), 20, 20 * MILLI, MILLI),
                handlers.at("modules/shop/init.lua", 1)
            )
            assertEquals(4, handlers.at("modules/shop/init.lua", 3).calls)
            assertEquals("timer", handlers.at("modules/shop/init.lua", 3).kind)
            assertEquals(
                HandlerTime("module shop", "timer", SourceRef("modules/shop/init.lua", 5), 1, MILLI, MILLI),
                handlers.at("modules/shop/init.lua", 5)
            )
            assertEquals("task", handlers.at("modules/shop/init.lua", 7).kind)
            assertEquals(2, handlers.at("modules/shop/init.lua", 7).calls)

            // The command is timed as its handler, not the dispatcher; the join as a player_join handler.
            assertEquals("command", second.handlers.at("modules/shop/init.lua", 10).kind)
            assertEquals("player_join", second.handlers.at("modules/shop/init.lua", 12).kind)

            // The scope's own time per tick, and each tick's steps.
            val scope = first.scopes.single()
            assertEquals("module shop", scope.scope)
            val tick = first.ticks[4]
            assertEquals(listOf("timers", "async", "events", "world", "effects", "upkeep", "accounts", "save"), tick.phases.keys.toList())
            assertEquals("module shop", tick.top.single().scope)
            assertEquals(tick.scripts, tick.top.single().nanos)
            assertTrue(tick.nanos >= tick.phases.values.sum(), "$tick")
            // What the steps are made of: each service's share, within its step.
            val events = assertNotNull(tick.parts["events/scripts"], "${tick.parts}")
            assertTrue(events <= tick.phases.getValue("events"), "$tick")
        }
    }

    @Test
    fun `instances of a centity share their handlers' rows, each a scope of its own`() {
        TestServer(
            mapOf(
                "centities/box/centity.json" to TestServer.scriptedCentity(),
                "centities/box/script.lua" to """this:on("tick", function() end)"""
            ),
            clock = steppingClock()
        ).use { server ->
            server.runtime.profiler.streaming = true
            val a = server.runtime.session.centities.spawn("box", Location("world", 0.0, 64.0, 0.0))!!
            val b = server.runtime.session.centities.spawn("box", Location("world", 4.0, 64.0, 0.0))!!
            server.tick(20)
            val sample = samples(server).single()
            val tick = sample.handlers.at("centities/box/script.lua", 1)
            assertEquals("centity box", tick.script)
            assertEquals(2, tick.scopes)
            assertEquals(40, tick.calls)
            assertEquals(
                setOf("centity box ${a.id.toString().take(8)}", "centity box ${b.id.toString().take(8)}"),
                sample.scopes.map { it.scope }.toSet()
            )
        }
    }

    @Test
    fun `a scope that closes before the batch ends keeps its time`() {
        TestServer(mapOf("modules/a/init.lua" to """nf.on("tick", function() end)"""), clock = steppingClock()).use { server ->
            server.runtime.profiler.streaming = true
            server.tick(5)
            server.write("modules/a/init.lua", """nf.on("tick", function() end) -- again""")
            server.reload("modules/a/init.lua")
            server.tick(15)
            // The old scope's 5 ticks and the new one's 15, in one row: same script, same place.
            assertEquals(20, samples(server).single().handlers.at("modules/a/init.lua", 1).calls)
            // The new body, timed as the module's file.
            assertEquals(
                HandlerTime("module a", "load", SourceRef("modules/a/init.lua", null), 1, MILLI, MILLI),
                samples(server).single().handlers.at("modules/a/init.lua", null)
            )
        }
    }

    @Test
    fun `a production server measures nothing until asked`() {
        TestServer(mapOf("modules/a/init.lua" to """nf.on("tick", function() end)""")).use { server ->
            assertTrue(!server.runtime.profiler.active)
            server.tick(20)
            assertEquals(emptyList(), samples(server))
            assertEquals(emptyList(), server.runtime.session.scripts.host!!.takeProfile())
        }
    }

    @Test
    fun `nf profile records for that long, writes a report and says where`() {
        TestServer(
            mapOf(
                "modules/a/init.lua" to """
                    nf.on("tick", function()
                      local n = 0
                      for i = 1, 1000 do n = n + i end
                    end)
                """
            ),
            clock = steppingClock()
        ).use { server ->
            val alex = server.player("Alex")
            server.tick(7)
            assertEquals(
                listOf("<gold>Profiling for 2 s<gray>; the report goes into the plugin's profiles folder."),
                server.platform.commands.run(alex, "nf profile 2")
            )
            assertTrue(server.runtime.profiler.active)
            // One at a time.
            assertEquals(
                listOf("<red>a profile is already running (2 s left); wait for its report"),
                server.platform.commands.run(alex, "nf profile 5")
            )
            assertEquals(
                listOf("<gray>/nf profile <seconds> (one is recording: 2 s left)"),
                server.platform.commands.run(alex, "nf profile")
            )
            assertEquals(listOf("<red>a profile runs 1 to 600 seconds"), server.platform.commands.run(alex, "nf profile 0"))
            assertEquals(
                listOf("<red>say how many seconds to profile: /nf profile 30"),
                server.platform.commands.run(alex, "nf profile soon")
            )
            // Completion, like the server's, only offers what they may use.
            alex.permissions += NetherForgeRuntime.PERMISSION
            assertEquals(listOf("10", "30", "60"), server.platform.commands.complete(alex, "nf profile "))

            server.tick(40)
            server.runtime.profiler.awaitWrites()
            server.runMain()
            assertTrue(!server.runtime.profiler.active)

            val folder = server.state.resolve("profiles")
            val names = folder.listDirectoryEntries().map { it.name }.sorted()
            assertEquals(2, names.size, "$names")
            val text = folder.resolve(names.single { it.endsWith(".txt") })
            val said = alex.messages.last()
            assertTrue(said.startsWith("<green>Profile written to $text"), said)
            assertTrue(server.platform.log.lines.any { it.contains("Profile written to $text") })

            val report = Json { ignoreUnknownKeys = true }.decodeFromString(
                ProfileReport.serializer(),
                folder.resolve(names.single { it.endsWith(".json") }).readText()
            )
            assertEquals(2, report.seconds)
            // The ticks after the command, 40 of them.
            assertEquals((8L..47L).toList(), report.ticks.map { it.tick })
            val handler = report.handlers.single()
            assertEquals(40, handler.calls)
            assertEquals(SourceRef("modules/a/init.lua", 1), handler.source)
            assertEquals(listOf("timers", "async", "events", "world", "effects", "upkeep", "accounts", "save"), report.phases.keys.toList())
            val summary = text.readText()
            assertTrue(summary.startsWith("NetherForge profile: 2 s from "), summary)
            assertTrue(summary.contains("module a, tick, modules/a/init.lua:1"), summary)

            // Afterwards, another may start; nothing is measured meanwhile.
            server.tick(5)
            assertEquals(emptyList(), server.runtime.session.scripts.host!!.takeProfile())
        }
    }

    @Test
    fun `a recording goes on across a full reload`() {
        TestServer(mapOf("modules/a/init.lua" to """nf.on("tick", function() end)"""), clock = steppingClock()).use { server ->
            var said: String? = null
            server.runtime.profile(1) { said = it }
            server.tick(10)
            server.reload("netherforge.json")
            server.tick(10)
            server.runtime.profiler.awaitWrites()
            server.runMain()
            val written = assertNotNull(said)
            val text = Files.readString(server.state.resolve("profiles").listDirectoryEntries("*.txt").single())
            assertTrue(written.contains("profile-"), written)
            // Ten ticks on each Lua state, and the second state's body.
            assertTrue(text.contains("  20  "), text)
        }
    }

    companion object {
        const val MILLI = 1_000_000L
    }
}
