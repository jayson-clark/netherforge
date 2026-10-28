package dev.netherforge.plugin.scoreboard
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.Location
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `bossbar:attach`: shown to who's near a centity, following its health, removed with it. */
class BossBarAttachTest {
    private val files = mapOf(
        "centities/boss/centity.json" to """{ "nodes": { "root": {} }, "health": { "max": 100 } }""",
        "modules/t/init.lua" to """
            nf.commands.register("go", function()
              local boss = nf.centities.spawn("boss", vec3(0, 64, 0))
              local bar = nf.bossbars.create({ text = "Boss" })
              log("attached", tostring(bar:attach(boss, { range = 10 })), tostring(bar:attached() == boss))
              nf.after(1, function() log("viewers", bar:viewers()[1]:name(), #bar:viewers()) end)
              nf.after(2, function() boss:damage(25) end)
              nf.after(4, function() log("progress", bar:progress()) end)
              nf.after(6, function() boss:damage(100) end)
              nf.after(8, function() log("after", tostring(bar:exists())) end)
            end)
        """
    )

    @Test
    fun `it shows to who's near, follows the health, and goes with the centity`() {
        TestServer(files, start = false).use { server ->
            val near = server.platform.players.add("Near", Location("world", 3.0, 64.0, 0.0))
            val far = server.platform.players.add("Far", Location("world", 50.0, 64.0, 0.0))
            server.start()
            server.platform.commands.runConsole("go")
            server.tick(1)
            val bars = server.platform.bossBars
            val id = bars.shown.keys.single()
            assertEquals(setOf(near.ref.uuid), bars.shown.getValue(id), "only the player near it")
            far.location = Location("world", 5.0, 64.0, 0.0)
            near.location = Location("world", 40.0, 64.0, 0.0)
            server.tick(1)
            assertEquals(setOf(far.ref.uuid), bars.shown.getValue(id), "it follows who's near")
            server.tick(8)
            assertEquals(listOf("attached\ttrue\ttrue", "viewers\tNear\t1", "progress\t0.75", "after\tfalse"), server.logs)
            assertTrue(id !in bars.bars, "removed with its centity")
        }
    }
}
