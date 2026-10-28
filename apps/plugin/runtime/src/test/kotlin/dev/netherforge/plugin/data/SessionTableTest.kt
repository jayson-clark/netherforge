package dev.netherforge.plugin.data
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.GameEvent
import kotlin.test.Test
import kotlin.test.assertEquals

/** `player:session()`: one table per online player, shared, kept through a module's reload, gone when they leave. */
class SessionTableTest {
    private val files = mapOf(
        "modules/a/init.lua" to """
            local first
            nf.commands.register("mark", function(event)
              local session = assert(event.player:session())
              session.mark = "a"
              session.handler = function() return "kept" end
              first = session
              log("same table", tostring(event.player:session() == session))
            end)
            nf.commands.register("again", function(event)
              log("still the first", tostring(event.player:session() == first))
            end)
        """,
        "modules/b/init.lua" to """
            local alex
            nf.commands.register("read", function(event)
              alex = alex or event.player
              local session = assert(event.player:session())
              log("b reads", tostring(session.mark), session.handler and session.handler() or "none")
            end)
            nf.commands.register("away", function()
              log("offline", tostring(alex:session()))
            end)
            nf.on("player_quit", function(event)
              log("on quit", tostring(assert(event.player:session()).mark))
            end)
        """
    )

    @Test
    fun `one table per player, shared by every script, kept through a module's reload and gone when they leave`() {
        TestServer(files).use { server ->
            val alex = server.player("Alex")
            server.platform.commands.run(alex, "mark")
            server.platform.commands.run(alex, "read")
            assertEquals(listOf("same table\ttrue", "b reads\ta\tkept"), server.logs)

            // Reloading a module leaves the player's table be.
            server.write("modules/b/init.lua", files.getValue("modules/b/init.lua") + "\n-- changed\n")
            server.reload("modules/b/init.lua")
            server.platform.commands.run(alex, "read")
            server.platform.commands.run(alex, "again")
            assertEquals(listOf("b reads\ta\tkept", "still the first\ttrue"), server.logs.drop(2))

            // Their `player_quit` handlers still find it; then it's gone, and an offline player has none.
            server.platform.players.quit(alex)
            val bo = server.player("Bo")
            server.platform.commands.run(bo, "read")
            server.platform.commands.run(bo, "away")
            assertEquals(listOf("on quit\ta", "b reads\tnil\tnone", "offline\tnil"), server.logs.drop(4))

            // Back again: a new, empty table.
            val back = server.player("Alex")
            server.platform.raise.playerJoin(GameEvent.PlayerJoin(back.ref, false, null))
            server.platform.commands.run(back, "read")
            server.platform.commands.run(back, "again")
            assertEquals(listOf("b reads\tnil\tnone", "still the first\tfalse"), server.logs.drop(7))
            assertEquals(emptyList(), server.errors.map { it.message })
        }
    }

    @Test
    fun `a whole-project reload starts every table empty`() {
        TestServer(files).use { server ->
            val alex = server.player("Alex")
            server.platform.commands.run(alex, "mark")
            server.runtime.reloadAll()
            server.platform.commands.run(alex, "read")
            assertEquals(listOf("same table\ttrue", "b reads\tnil\tnone"), server.logs)
        }
    }
}
