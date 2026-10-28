package dev.netherforge.plugin.world
import dev.netherforge.plugin.LuaChecks
import dev.netherforge.plugin.TestServer
import kotlin.test.Test
import kotlin.test.assertEquals

/** A world's `dusk` and `dawn`, `nf`'s `world_dusk` and `world_dawn`, and `world:is_night()` / `is_day()`. */
class DaylightTest {
    private val listener = """
        local world = nf.worlds.default()
        world:on("dusk", function(event) log("dusk", event.world:name(), event.time_of_day, tostring(event.world:is_night())) end)
        world:on("dawn", function(event) log("dawn", event.world:name(), event.time_of_day, tostring(event.world:is_day())) end)
        nf.on("world_dusk", function(event) log("nf dusk", event.time_of_day) end)
        nf.on("world_dawn", function(event) log("nf dawn", event.time_of_day) end)
    """

    private fun TestServer.setTime(ticks: Long) {
        platform.worlds.fullTimes["world"] = ticks
    }

    @Test
    fun `the clock running into night and day is heard on the world, then nf`() {
        TestServer(mapOf("modules/sky/init.lua" to listener), start = false).use { server ->
            server.setTime(12_998)
            server.start()
            server.tick(1)
            assertEquals(emptyList(), server.logs, "the first look only notes the period")
            server.setTime(12_999)
            server.tick(1)
            assertEquals(emptyList(), server.logs, "still day")
            server.setTime(13_000)
            server.tick(1)
            assertEquals(listOf("dusk\tworld\t13000\ttrue", "nf dusk\t13000"), server.logs)
            server.setTime(22_999)
            server.tick(1)
            server.setTime(23_000)
            server.tick(1)
            assertEquals(listOf("dawn\tworld\t23000\ttrue", "nf dawn\t23000"), server.logs.drop(2))
        }
    }

    @Test
    fun `a time set jump is a crossing when the period changed, and none when it didn't`() {
        TestServer(mapOf("modules/sky/init.lua" to listener), start = false).use { server ->
            server.setTime(1000)
            server.start()
            server.tick(1)
            server.setTime(3 * 24_000L + 18_000)
            server.tick(1)
            assertEquals(listOf("dusk\tworld\t18000\ttrue", "nf dusk\t18000"), server.logs)
            // From one night to the next, a day skipped: still night, so nothing.
            server.setTime(4 * 24_000L + 14_000)
            server.tick(1)
            assertEquals(2, server.logs.size)
            // Backwards to the morning, as `/time set day` does.
            server.setTime(4 * 24_000L + 1000)
            server.tick(1)
            assertEquals(listOf("dawn\tworld\t1000\ttrue", "nf dawn\t1000"), server.logs.drop(2))
        }
    }

    @Test
    fun `nothing is watched while nobody listens, and listening again starts afresh`() {
        TestServer(
            mapOf(
                "modules/sky/init.lua" to """
                    local subscription
                    nf.commands.register("listen", function()
                      subscription = nf.on("world_dusk", function(event) log("dusk", event.time_of_day) end)
                    end)
                    nf.commands.register("stop", function() subscription:cancel() end)
                """
            ),
            start = false
        ).use { server ->
            server.setTime(1000)
            server.start()
            server.tick(1)
            server.setTime(14_000)
            server.tick(1)
            server.platform.commands.runConsole("listen")
            server.tick(1)
            assertEquals(emptyList(), server.logs, "a dusk that went by before anyone listened isn't heard")
            server.setTime(24_000)
            server.tick(1)
            server.setTime(24_000 + 13_500)
            server.tick(1)
            assertEquals(listOf("dusk\t13500"), server.logs)
            server.platform.commands.runConsole("stop")
            server.setTime(2 * 24_000L)
            server.tick(1)
            server.platform.commands.runConsole("listen")
            server.setTime(2 * 24_000L + 13_000)
            server.tick(1)
            assertEquals(listOf("dusk\t13500"), server.logs, "listening again only notes the period first")
        }
    }

    @Test
    fun `is_night and is_day follow the same boundaries, and are false once the world has gone`() {
        val result = LuaChecks.run(
            """
            local world = nf.worlds.default()
            for _, case in ipairs({ { 0, false }, { 12999, false }, { 13000, true }, { 18000, true }, { 22999, true }, { 23000, false } }) do
              world:set_time_of_day(case[1])
              check("night at " .. case[1], world:is_night(), case[2])
              check("day at " .. case[1], world:is_day(), not case[2])
            end
            """
        )
        assertEquals(LuaChecks.DONE, result)

        TestServer(
            mapOf(
                "modules/sky/init.lua" to """
                    local nether = assert(nf.worlds.get("nether"))
                    nf.commands.register("ask", function() log(tostring(nether:is_night()), tostring(nether:is_day())) end)
                """
            )
        ).use { server ->
            server.platform.worlds.worldNames.remove("nether")
            server.platform.commands.runConsole("ask")
            assertEquals(listOf("false\tfalse"), server.logs)
        }
    }
}
