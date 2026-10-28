package dev.netherforge.plugin.script
import dev.netherforge.format.Severity
import dev.netherforge.format.bridge.SourceRef
import dev.netherforge.plugin.NetherForgeRuntime
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.Location
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Time per scope per tick: `/nf scripts` and slow-script
 * warnings. Most tests use a clock that moves on 1 ms every time it's read,
 * so each call in costs 1 ms of its own (a call with calls nested in it, the
 * reads between theirs), whatever machine runs them.
 */
class ScriptCostsTest {
    private fun steppingClock(): () -> Long {
        var now = 0L
        return {
            now += MILLI
            now
        }
    }

    private fun warnings(server: TestServer) = server.platform.log.lines.filter { "is slow" in it }

    @Test
    fun `nf scripts lists scopes by cost with what they hold`() {
        TestServer(
            mapOf(
                // 12 tick handlers: 12 ms a tick.
                "modules/hot/init.lua" to """
                    for i = 1, 12 do nf.on("tick", function() end) end
                """,
                // One handler (1 ms a tick); the task's first resume is nested in the body.
                "modules/cool/init.lua" to """
                    nf.on("tick", function() end)
                    nf.task(function() nf.wait(1000) end)
                    nf.every(100, function() end)
                    -- What else a scope holds is counted by the service that keeps it: three effects.
                    for _ = 1, 3 do nf.particles.play("glow", vec3(0, 64, 0), { loop = true }) end
                """,
                "particles/glow/effect.json" to """
                    { "duration": 20, "emitters": { "spark": { "particle": "minecraft:flame", "burst": 1, "every": 20 } } }
                """
            ),
            clock = steppingClock()
        ).use { server ->
            server.tick(10)
            val alex = server.player("Alex")
            assertEquals(
                listOf(
                    "<gold>2 scripts, 13.40 ms a tick <gray>(average / max ms a tick over the last 100 ticks)",
                    // The first tick also carries its body: 13 ms, then 12 a tick.
                    "<yellow>12.10 <gray>/ 13.00 <white>module hot <gray>121 calls, 12 subscriptions, 0 tasks",
                    // Body 2 ms of its own, the task 1, the handler 1; then 1 a tick.
                    "<yellow>1.30 <gray>/ 4.00 <white>module cool <gray>12 calls, 1 subscription, 2 tasks, 3 effects"
                ),
                server.platform.commands.run(alex, "nf scripts")
            )
            // Completion, like the server's, only offers what they may use.
            alex.permissions += NetherForgeRuntime.PERMISSION
            assertEquals(listOf("scripts"), server.platform.commands.complete(alex, "nf scr"))
            assertEquals(listOf("all"), server.platform.commands.complete(alex, "nf scripts "))
        }
    }

    @Test
    fun `a centity instance is named by its id, and the list stops at fifteen unless all`() {
        TestServer(
            mapOf(
                "centities/box/centity.json" to TestServer.scriptedCentity(),
                "centities/box/script.lua" to """this:on("tick", function() end)"""
            ),
            clock = steppingClock()
        ).use { server ->
            val instances = (0 until 16).map {
                server.runtime.session.centities.spawn("box", Location("world", it.toDouble(), 64.0, 0.0))!!
            }
            server.tick()
            val alex = server.player("Alex")
            val listed = server.platform.commands.run(alex, "nf scripts")
            assertEquals(17, listed.size)
            assertEquals("<gray>…and 1 more: /nf scripts all", listed.last())
            assertTrue(instances.any { listed[1].contains("<white>centity box ${it.id.toString().take(8)} ") }, listed[1])
            assertEquals(17, server.platform.commands.run(alex, "nf scripts all").size)
        }
    }

    @Test
    fun `time in a nested call is its own scope's, and a warning names the slowest handler`() {
        TestServer(
            mapOf(
                "modules/caller/init.lua" to """
                    nf.on("tick", function() end)
                    nf.on("tick", function() for i = 1, 10 do nf.emit("test:ping") end end)
                """,
                "modules/listener/init.lua" to """
                    nf.on("test:ping", function() end)
                """
            ),
            performance = PerformanceConfig(warnMillis = 11.0, warnTicks = 20),
            clock = steppingClock()
        ).use { server ->
            server.tick(20)
            // A tick: the light handler 1 ms; the heavy one 21 ms, 10 of them the listener's
            // (10 ms a tick, not over the limit; its body's 1 ms is in its average, never in a warning's).
            val report = server.runtime.session.costs.report().associateBy { it.scope.module }
            assertEquals(10.05, report.getValue("listener").averageMillis, 1e-9)
            assertTrue(warnings(server).isEmpty(), "${warnings(server)}")

            server.tick()
            assertEquals(
                listOf(
                    "WARN module caller is slow: 12.0 ms a tick on average over the last 20 ticks, over the 11 ms limit; " +
                        "see /nf scripts (slowest: modules/caller/init.lua:2)"
                ),
                warnings(server)
            )
            val error = server.errors.single { "is slow" in it.message }
            assertEquals(SourceRef("modules/caller/init.lua", 2), error.source)
            val problem = assertNotNull(server.runtime.currentProblems().singleOrNull { it.code == "script.slow" })
            assertEquals(Severity.WARNING, problem.severity)
            assertEquals("modules/caller/init.lua", problem.file)
            assertEquals(2, problem.line)
        }
    }

    @Test
    fun `a slow scope is warned about once a minute, and many alike in one line`() {
        TestServer(
            mapOf(
                "centities/hot/centity.json" to TestServer.scriptedCentity(),
                "centities/hot/script.lua" to """
                    for i = 1, 12 do this:on("tick", function() end) end
                """,
                "modules/cool/init.lua" to """nf.on("tick", function() end)"""
            ),
            performance = PerformanceConfig(warnMillis = 10.0, warnTicks = 20),
            clock = steppingClock()
        ).use { server ->
            server.runtime.session.centities.spawn("hot", Location("world", 0.0, 64.0, 0.0))!!
            server.runtime.session.centities.spawn("hot", Location("world", 5.0, 64.0, 0.0))!!
            server.tick(20)
            assertTrue(warnings(server).isEmpty())
            server.tick()
            // Both instances are slow in the same place: one line.
            val first = warnings(server).single()
            assertTrue(first.startsWith("WARN centity hot is slow: 12.0 ms a tick"), first)
            assertTrue(first.endsWith("(slowest: centities/hot/script.lua:1)"), first)
            // One problem for both.
            assertEquals(1, server.runtime.currentProblems().count { it.code == "script.slow" })

            server.tick((ScriptCosts.QUIET_TICKS - 1).toInt())
            assertEquals(1, warnings(server).size)
            server.tick()
            assertEquals(2, warnings(server).size)
            assertTrue(warnings(server).last().endsWith("(and 1 more like it)"), warnings(server).last())
            // The cheap module never is.
            assertTrue(warnings(server).none { "cool" in it })

            // Its problem goes with its scope.
            server.runtime.session.centities.all().forEach { server.runtime.session.centities.remove(it) }
            assertEquals(0, server.runtime.currentProblems().count { it.code == "script.slow" })
        }
    }

    @Test
    fun `nf scripts counts each scope's playing particle effects`() {
        TestServer(
            mapOf(
                "particles/pulse/effect.json" to """
                    { "duration": 3, "emitters": { "flame": { "particle": "minecraft:flame", "burst": 1, "every": 1 } } }
                """,
                "modules/one/init.lua" to """nf.particles.play("pulse", vec3(0, 64, 0))""",
                "modules/two/init.lua" to """
                    nf.particles.play("pulse", vec3(0, 64, 0), { loop = true })
                    nf.particles.play("pulse", vec3(4, 64, 0), { loop = true })
                """,
                "modules/none/init.lua" to """nf.on("tick", function() end)"""
            ),
            clock = steppingClock()
        ).use { server ->
            val alex = server.player("Alex")

            // What a scope's line says it holds after its tasks.
            fun holds(module: String): String {
                val line = server.platform.commands.run(alex, "nf scripts").single { "module $module " in it }
                return line.substringAfter(" tasks")
            }
            server.tick()
            assertEquals(", 1 effect", holds("one"))
            assertEquals(", 2 effects", holds("two"))
            assertEquals("", holds("none"))
            server.tick(3)
            // The one that doesn't loop has finished; the looping ones play on.
            assertEquals("", holds("one"))
            assertEquals(2, server.runtime.session.costs.report().single { it.scope.module == "two" }.counts["effects"])
        }
    }

    @Test
    fun `nf scripts says what a script holds once it's a megabyte`() {
        TestServer(
            mapOf(
                "modules/big/init.lua" to """kept = { ("x"):rep(3 * 1024 * 1024) }""",
                "modules/small/init.lua" to """kept = { "x" }"""
            ),
            clock = steppingClock()
        ).use { server ->
            server.tick()
            val listed = server.platform.commands.run(server.player("Alex"), "nf scripts")
            assertTrue(listed.single { "module big " in it }.endsWith("0 tasks, 3 MB"), "$listed")
            assertTrue(listed.single { "module small " in it }.endsWith("0 tasks"), "$listed")
        }
    }

    @Test
    fun `warn-ms 0 turns the warning off`() {
        TestServer(
            mapOf("modules/hot/init.lua" to """for i = 1, 50 do nf.on("tick", function() end) end"""),
            performance = PerformanceConfig(warnMillis = 0.0, warnTicks = 20),
            clock = steppingClock()
        ).use { server ->
            server.tick(50)
            assertTrue(warnings(server).isEmpty())
            assertEquals(50.0, server.runtime.session.costs.report().single().averageMillis, 1.0)
        }
    }

    @Test
    fun `the real clock measures real work`() {
        TestServer(
            mapOf(
                "modules/busy/init.lua" to """
                    nf.on("tick", function()
                      local t = 0
                      for i = 1, 20000 do t = t + i % 7 end
                    end)
                """
            )
        ).use { server ->
            server.tick(5)
            val entry = server.runtime.session.costs.report().single()
            assertTrue(entry.averageMillis > 0.0, "$entry")
            assertTrue(entry.maxMillis >= entry.averageMillis, "$entry")
            assertEquals(6, entry.calls)
        }
    }

    private companion object {
        const val MILLI = 1_000_000L
    }
}
