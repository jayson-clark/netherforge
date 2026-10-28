package dev.netherforge.plugin.centity
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.binding.Events
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.platform.Location
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `tick` handlers with `every`: each runs on the ticks where
 * `(tick + phase) % every == 0`, the phase the centity's, so a crowd of
 * centities spreads over the ticks, and the runtime only calls into Lua on
 * the ticks some handler is due.
 */
class TickRateTest {
    private val centity =
        """{ "nodes": { "root": { "display": { "type": "block", "block": "minecraft:stone" } } }, "script": { "file": "script.lua" } }"""

    private fun server(script: String) = TestServer(mapOf("centities/c/centity.json" to centity, "centities/c/script.lua" to script))

    @Test
    fun `a crowd of centities with every = 4 spreads over the four ticks, each once in four`() {
        server("""this:on("tick", function(event) log(tostring(this:id()), event.tick) end, { every = 4 })""").use { server ->
            val instances = (0 until 200).map { server.runtime.session.centities.spawn("c", Location("world", it * 2.0, 64.0, 0.0))!! }
            server.tick(8)
            val calls = server.logs.map { it.split('\t') }
            val byInstance = calls.groupBy({ it[0] }, { it[1].toLong() })
            assertEquals(instances.map { it.id.toString() }.toSet(), byInstance.keys)
            for ((_, ticks) in byInstance) {
                assertEquals(2, ticks.size, "twice in eight ticks")
                assertEquals(4, ticks[1] - ticks[0])
            }
            val perTick = calls.groupingBy { it[1].toLong() % 4 }.eachCount()
            assertEquals(4, perTick.size, "every phase used: $perTick")
            assertTrue(perTick.values.all { it in 50..150 }, "spread out: $perTick")
        }
    }

    @Test
    fun `the runtime raises tick only when a handler is due, and every handler on a centity agrees`() {
        server(
            """
            this:on("tick", function(event) log("first", event.tick) end, { every = 4 })
            nf.after(3, function()
              this:on("tick", function(event) log("second", event.tick) end, { every = 4 })
            end)
            """
        ).use { server ->
            val instance = server.runtime.session.centities.spawn("c", Location("world", 0.0, 64.0, 0.0))!!
            server.tick(12)
            val handle = LuaHandle.Centity(instance.id.toString())
            val scripts = server.runtime.session.scripts
            val first = server.logs.filter { it.startsWith("first") }.map { it.split('\t')[1].toLong() }
            val second = server.logs.filter { it.startsWith("second") }.map { it.split('\t')[1].toLong() }
            assertEquals(3, first.size)
            assertEquals(first.filter { it >= 3 }, second, "added later (before tick 3's handlers), on the same ticks")
            val now = scripts.now
            val due = (now - 7..now).filter { scripts.due(handle, Events.CENTITY_TICK, it) }
            assertEquals(2, due.size, "two of eight ticks: $due")
            assertTrue(due.all { (it - first.last()) % 4 == 0L })
        }
    }

    @Test
    fun `a handler without every makes every tick due`() {
        server(
            """
            this:on("tick", function() end, { every = 5 })
            this:on("tick", function() end)
            """
        ).use { server ->
            val instance = server.runtime.session.centities.spawn("c", Location("world", 0.0, 64.0, 0.0))!!
            val handle = LuaHandle.Centity(instance.id.toString())
            assertTrue((0L until 10L).all { server.runtime.session.scripts.due(handle, Events.CENTITY_TICK, it) })
        }
    }
}
