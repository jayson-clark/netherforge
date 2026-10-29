package dev.netherforge.plugin.dialog
import dev.netherforge.format.dialog.TextInput
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.GameEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DialogTest {
    private val files = mapOf(
        "dialogs/ask/dialog.json" to """
            {
              "type": "multi_action",
              "title": "<gold>Ask",
              "script": { "file": "script.lua" },
              "inputs": [
                { "type": "text", "key": "name" },
                { "type": "boolean", "key": "sure", "onTrue": "yes", "onFalse": "no" },
                { "type": "number_range", "key": "amount", "start": 0, "end": 10 }
              ],
              "buttons": [
                { "key": "ok" },
                { "key": "cancel" }
              ]
            }
        """,
        "dialogs/ask/script.lua" to """
            log("dialog load " .. this:id())
            this:on("press", function(event) log("dialog heard " .. event.key) end)
            this:on("close", function(event) log("dialog closed for " .. event.player:name()) end)
            this:button("ok"):on("press", function(event)
              log("ok " .. event.player:name() .. " " .. event.values.name .. " " .. event.values.sure .. " " .. event.values.amount)
              if event.values.sure == "yes" then
                event:stop()
              end
            end)
        """,
        "dialogs/menu/dialog.json" to """{ "type": "dialog_list", "title": "Menu", "dialogs": ["ask"] }""",
        "modules/m/init.lua" to """
            nf.commands.register("ask", function(ctx) ctx.player:open_dialog("ask") end)
            nf.commands.register("menu", function(ctx) nf.dialogs.get("menu"):open_for(ctx.player) end)
            nf.commands.register("shut", function(ctx) ctx.player:close_dialog() end)
            nf.on("dialog_press", function(event)
              log("nf heard " .. event.key .. " on " .. event.dialog:id() .. ", target " .. event.target:key())
            end)
        """
    )

    @Test
    fun `a press goes from the button to the dialog to nf, until a handler stops it`() {
        TestServer(files).use { server ->
            val alex = server.player("Alex")
            server.platform.commands.run(alex, "ask")
            assertEquals("ask", server.platform.dialogs.showing.getValue(alex.ref.uuid).id)
            server.platform.dialogs.press(alex, "ok", mapOf("name" to "Al", "sure" to "no", "amount" to 3.5))
            // A press closes it: each press is on a dialog opened again.
            server.platform.commands.run(alex, "ask")
            server.platform.dialogs.press(alex, "ok", mapOf("name" to "Al", "sure" to "yes", "amount" to 3.0))
            server.platform.commands.run(alex, "ask")
            server.platform.dialogs.press(alex, "cancel")
            assertEquals(
                listOf(
                    "dialog load ask",
                    "ok Alex Al no 3.5",
                    "dialog heard ok",
                    "nf heard ok on ask, target ok",
                    "ok Alex Al yes 3.0",
                    "dialog heard cancel",
                    "nf heard cancel on ask, target cancel"
                ),
                server.logs
            )
        }
    }

    /**
     * The client is the player's: a modified one can send a button's click after its dialog closed, or for a
     * dialog it was never shown. Only a dialog on their screen is heard.
     */
    @Test
    fun `a press of a dialog that isn't on the player's screen is ignored`() {
        TestServer(files).use { server ->
            val alex = server.player("Alex")
            val dialogs = server.platform.dialogs
            dialogs.forgePress(alex, "ask", "ok", mapOf("name" to "x", "sure" to "yes", "amount" to 1.0))
            server.platform.commands.run(alex, "ask")
            dialogs.press(alex, "cancel")
            // Pressed again after it closed: a replay.
            dialogs.forgePress(alex, "ask", "cancel")
            // A dialog the menu on screen doesn't lead to, pressed and left.
            server.platform.commands.run(alex, "menu")
            dialogs.forgePress(alex, "other", "ok")
            server.platform.events!!.dialogClosed(alex.ref, "other")
            assertEquals(listOf("dialog load ask", "dialog heard cancel", "nf heard cancel on ask, target cancel"), server.logs)
        }
    }

    /** A dialog a `dialog_list` on screen leads to is on screen too: its presses are heard. */
    @Test
    fun `a press on a dialog the list on screen leads to is heard`() {
        TestServer(files).use { server ->
            val alex = server.player("Alex")
            server.platform.commands.run(alex, "menu")
            server.platform.dialogs.press(alex, "cancel", dialog = "ask")
            assertTrue("dialog heard cancel" in server.logs, server.logs.toString())
        }
    }

    @Test
    fun `a dialog hands out its buttons by key, and an unknown key is an error`() {
        TestServer(
            files + (
                "modules/b/init.lua" to """
                    local ask = nf.dialogs.get("ask")
                    local ok = ask:button("ok")
                    log(ok:key(), ok:dialog() == ask, ok == ask:button("ok"), ok:label(), ask:button("cancel"):label())
                    log(pcall(ask.button, ask, "okay"))
                """
                )
        ).use { server ->
            assertEquals(
                listOf("ok\ttrue\ttrue\tok\tcancel", "false\tdialog ask has no button \"okay\" (it has: ok, cancel)"),
                server.logs.filter { "load" !in it }
            )
        }
    }

    @Test
    fun `a dialog list is built with the dialogs it offers, whose buttons reach their own script`() {
        TestServer(files).use { server ->
            val alex = server.player("Alex")
            server.platform.commands.run(alex, "menu")
            val shown = server.platform.dialogs.showing.getValue(alex.ref.uuid)
            assertEquals(listOf("ask"), shown.listed.map { it.id })
            server.platform.dialogs.press(alex, "cancel", dialog = "ask")
            assertEquals("dialog heard cancel", server.logs[server.logs.size - 2])
        }
    }

    @Test
    fun `reloading a dialog restarts its script, and a press for a button that's gone is ignored`() {
        TestServer(files).use { server ->
            val alex = server.player("Alex")
            server.platform.commands.run(alex, "ask")
            server.write(
                "dialogs/ask/dialog.json",
                """{ "title": "Ask", "script": { "file": "script.lua" }, "buttons": [{ "key": "fine" }] }"""
            )
            server.write("dialogs/ask/script.lua", """this:on("press", function(event) log("dialog heard " .. event.key) end)""")
            val result = server.reload("dialogs/ask/dialog.json", "dialogs/ask/script.lua").resources.single()
            assertEquals("dialog:ask", result.label)
            assertTrue(result.ok)
            server.platform.dialogs.press(alex, "ok")
            server.platform.commands.run(alex, "ask")
            server.platform.dialogs.press(alex, "fine")
            assertEquals("dialog heard fine", server.logs[server.logs.size - 2])
            assertFalse(server.logs.any { it.startsWith("ok Alex") })
        }
    }

    @Test
    fun `close fires for escape through the exit action and for closes a script causes, not for presses`() {
        TestServer(files).use { server ->
            val alex = server.player("Alex")
            // Escape on a multi_action dialog is its exit action.
            server.platform.commands.run(alex, "ask")
            server.platform.dialogs.escape(alex)
            // A script closing it.
            server.platform.commands.run(alex, "ask")
            server.platform.commands.run(alex, "shut")
            // Another dialog opened over it.
            server.platform.commands.run(alex, "ask")
            server.platform.commands.run(alex, "menu")
            // A press that closes it is a press, not a close; nor is shutting nothing.
            server.platform.commands.run(alex, "ask")
            server.platform.dialogs.press(alex, "cancel")
            server.platform.commands.run(alex, "shut")
            // Leaving the server: nothing.
            server.platform.commands.run(alex, "ask")
            server.platform.raise.playerQuit(GameEvent.PlayerQuit(alex.ref, null))
            assertEquals(
                listOf("dialog closed for Alex", "dialog closed for Alex", "dialog closed for Alex", "dialog heard cancel"),
                server.logs.filter { "closed" in it || "heard cancel" in it }.filter { !it.startsWith("nf") }
            )
        }
    }

    @Test
    fun `escape on a notice is a press of its button, on a confirmation of its second`() {
        TestServer(
            mapOf(
                "dialogs/note/dialog.json" to """{ "title": "Note", "buttons": [{ "key": "ok" }], "script": { "file": "d.lua" } }""",
                "dialogs/sure/dialog.json" to
                    """{ "type": "confirmation", "title": "Sure?", "buttons": [{ "key": "yes" }, { "key": "no" }], "script": { "file": "d.lua" } }""",
                "dialogs/note/d.lua" to
                    "this:on('press', function(e) log('note ' .. e.key) end)\nthis:on('close', function() log('note closed') end)",
                "dialogs/sure/d.lua" to
                    "this:on('press', function(e) log('sure ' .. e.key) end)\nthis:on('close', function() log('sure closed') end)",
                "modules/m/init.lua" to
                    "nf.commands.register('show', { arguments = { { name = 'id', type = 'word' } } }, function(ctx) ctx.player:open_dialog(ctx.arguments.id) end)"
            )
        ).use { server ->
            val alex = server.player("Alex")
            server.platform.commands.run(alex, "show note")
            server.platform.dialogs.escape(alex)
            server.platform.commands.run(alex, "show sure")
            server.platform.dialogs.escape(alex)
            assertEquals(listOf("note ok", "sure no"), server.logs)
        }
    }

    @Test
    fun `escape is close where the file gives no button for it, and always on a dialog_list`() {
        val script = "this:on('press', function(e) log('press ' .. e.key) end)\n" +
            "this:on('close', function() log('closed ' .. this:id()) end)"
        TestServer(
            mapOf(
                "dialogs/bare/dialog.json" to """{ "title": "Bare", "script": { "file": "d.lua" } }""",
                "dialogs/half/dialog.json" to
                    """{ "type": "confirmation", "title": "Sure?", "buttons": [{ "key": "yes" }], "script": { "file": "d.lua" } }""",
                "dialogs/list/dialog.json" to
                    """{ "type": "dialog_list", "title": "Pick", "dialogs": ["bare"], "buttons": [{ "key": "done", "label": "Done" }], "script": { "file": "d.lua" } }""",
                "dialogs/bare/d.lua" to script,
                "dialogs/half/d.lua" to script,
                "dialogs/list/d.lua" to script,
                "modules/m/init.lua" to
                    "nf.commands.register('show', { arguments = { { name = 'id', type = 'word' } } }, function(ctx) ctx.player:open_dialog(ctx.arguments.id) end)"
            )
        ).use { server ->
            val alex = server.player("Alex")
            for (id in listOf("bare", "half", "list")) {
                server.platform.commands.run(alex, "show $id")
                server.platform.dialogs.escape(alex)
            }
            assertEquals(listOf("closed bare", "closed half", "closed list"), server.logs)
        }
    }

    @Test
    fun `an unknown dialog is an error where it's named`() {
        TestServer(mapOf("modules/m/init.lua" to "nf.dialogs.get(\"welcom\")")).use { server ->
            assertTrue("no dialog \"welcom\"" in server.errors.single().message)
        }
    }

    @Test
    fun `the welcome example greets a player with its dialog the first time only, even after a restart`() {
        TestServer(TestServer.example("showcase")).use { server ->
            val alex = server.player("Alex")
            server.platform.raise.playerJoin(GameEvent.PlayerJoin(alex.ref, false, null))
            val shown = server.platform.dialogs.showing[alex.ref.uuid]
            assertEquals("welcome", shown?.id)
            assertEquals("Alex", (shown!!.file.inputs.single() as TextInput).initial, "the nickname starts at their name")
            server.platform.dialogs.press(alex, "done", mapOf("nickname" to "Lex"))
            assertEquals("<green>Nice to meet you, Lex!", alex.messages.last())
            server.platform.dialogs.close(alex.ref.uuid)

            // The shop is opened with the nickname as its context.
            server.platform.commands.run(alex, "shop")
            assertEquals("<gold>Welcome to the shop, Lex!", alex.messages.last())

            server.restart()
            server.platform.raise.playerJoin(GameEvent.PlayerJoin(alex.ref, false, null))
            assertEquals(null, server.platform.dialogs.showing[alex.ref.uuid], "only the first join")
        }
    }
}
