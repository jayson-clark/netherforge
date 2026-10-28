package dev.netherforge.plugin.dialog
import dev.netherforge.format.dialog.DialogJson
import dev.netherforge.format.ref.ResourceKey
import dev.netherforge.plugin.TestServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Dialogs on the pause screen and the quick actions key: in the start-up
 * datapack the server started with, their buttons custom clicks the runtime
 * routes to the dialog's one script, and a change asking for a restart.
 */
class DialogMenusTest {
    private val files = mapOf(
        "dialogs/pause/dialog.json" to """
            {
              "type": "multi_action", "title": "Pause", "pauseMenu": true,
              "script": { "file": "script.lua" },
              "inputs": [
                { "type": "text", "key": "name" },
                { "type": "boolean", "key": "sure", "onTrue": "yes", "onFalse": "no" },
                { "type": "number_range", "key": "amount", "start": 0, "end": 10 }
              ],
              "buttons": [{ "key": "ok" }, { "key": "cancel" }]
            }
        """,
        "dialogs/pause/script.lua" to """
            this:on("press", function(event) log("dialog heard " .. event.key) end)
            this:on("close", function(event) log("dialog closed for " .. event.player:name()) end)
            this:button("ok"):on("press", function(event)
              log("ok " .. event.player:name() .. " " .. event.values.name .. " " .. event.values.sure .. " " .. event.values.amount)
            end)
        """,
        "dialogs/plain/dialog.json" to """{ "title": "Plain", "script": { "file": "script.lua" }, "buttons": [{ "key": "go" }] }""",
        "dialogs/plain/script.lua" to """this:on("press", function(event) log("plain heard " .. event.key) end)"""
    )

    @Test
    fun `the server starts with the flagged dialogs and the tags that put them on the menus`() {
        TestServer(files + ("dialogs/quick/dialog.json" to """{ "title": "Quick", "quickActions": true }""")).use { server ->
            assertEquals(
                listOf(
                    "data/minecraft/tags/dialog/pause_screen_additions.json",
                    "data/minecraft/tags/dialog/quick_actions.json",
                    "data/test/dialog/pause.json",
                    "data/test/dialog/quick.json",
                    "pack.mcmeta"
                ),
                server.platform.datapacks.started.keys.sorted()
            )
            val pause = server.platform.datapacks.started.getValue("data/test/dialog/pause.json").decodeToString()
            assertTrue("\"id\": \"test:dialog/pause/press/1\"" in pause, pause)
        }
    }

    @Test
    fun `a button of a registry dialog reaches the dialog's script like a press of a shown one`() {
        TestServer(files).use { server ->
            val alex = server.player("Alex")
            val key = ResourceKey("test", "pause")
            val dialogs = server.platform.dialogs
            assertTrue(dialogs.click(alex, DialogJson.pressId(key, 0), mapOf("name" to "Al", "sure" to true, "amount" to 3.5)))
            assertTrue(dialogs.click(alex, DialogJson.pressId(key, 1)))
            assertTrue(dialogs.click(alex, DialogJson.exitId(key)))
            assertEquals(
                listOf("ok Alex Al yes 3.5", "dialog heard ok", "dialog heard cancel", "dialog closed for Alex"),
                server.logs
            )
        }
    }

    @Test
    fun `a click that isn't a registry dialog's, or whose dialog isn't in the registry, does nothing`() {
        TestServer(files).use { server ->
            val alex = server.player("Alex")
            // Not ours at all.
            assertFalse(server.platform.dialogs.click(alex, "paper:click/1234"))
            // A project dialog that's on no menu: a player can't press it by forging the click.
            assertTrue(server.platform.dialogs.click(alex, DialogJson.pressId(ResourceKey("test", "plain"), 0)))
            // A button the dialog doesn't have.
            assertTrue(server.platform.dialogs.click(alex, DialogJson.pressId(ResourceKey("test", "pause"), 7)))
            assertEquals(emptyList(), server.logs)
        }
    }

    @Test
    fun `a dialog made in Lua can't be put on a menu`() {
        TestServer(
            files + (
                "modules/m/init.lua" to """
                    log(pcall(nf.dialogs.create, { title = "Mine", pause_menu = true }))
                """
                )
        ).use { server ->
            assertTrue(
                server.logs.last().startsWith("false\t") && "only a dialog file can be in the player's menus" in server.logs.last(),
                server.logs.last()
            )
        }
    }

    @Test
    fun `changing a dialog on a menu asks for a restart, and one that's on none doesn't`() {
        TestServer(files).use { server ->
            assertFalse(server.reload("dialogs/pause/dialog.json").restart)
            assertFalse(server.reload("dialogs/plain/dialog.json").restart)
            server.write(
                "dialogs/plain/dialog.json",
                """{ "title": "Plainer", "script": { "file": "script.lua" }, "buttons": [{ "key": "go" }] }"""
            )
            assertFalse(server.reload("dialogs/plain/dialog.json").restart)

            server.write("dialogs/pause/dialog.json", files.getValue("dialogs/pause/dialog.json").replace("\"Pause\"", "\"Paused\""))
            assertTrue(server.reload("dialogs/pause/dialog.json").restart)
            // Putting another on a menu is a change too.
            server.restart()
            assertFalse(server.reload("dialogs/pause/dialog.json").restart)
            server.write("dialogs/plain/dialog.json", """{ "title": "Plain", "quickActions": true, "buttons": [{ "key": "go" }] }""")
            assertTrue(server.reload("dialogs/plain/dialog.json").restart)
        }
    }
}
