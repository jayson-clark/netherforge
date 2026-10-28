package dev.netherforge.plugin.menu
import dev.netherforge.format.dialog.MessageBody
import dev.netherforge.format.dialog.OptionInput
import dev.netherforge.format.dialog.RangeInput
import dev.netherforge.format.dialog.TextInput
import dev.netherforge.plugin.TestServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `context` and the other options a menu or dialog is opened with. */
class ContextTest {
    private val menus = mapOf(
        "menus/shop/menu.json" to """{ "title": "Shop", "rows": 1, "script": { "file": "script.lua" } }""",
        "menus/shop/script.lua" to """
            local context = this:context()
            log("body", type(context), context and context.label)
            this:on("click", function(event)
              log("click", event.context == this:context(), event.context.label, event.context.callback())
            end)
            this:on("close", function(event) log("close", event.context.label) end)
        """,
        "menus/bank/menu.json" to """{ "title": "Bank", "rows": 1, "shared": true }""",
        "modules/m/init.lua" to """
            nf.on("menu_open", function(event)
              log("nf open", event.context and event.context.label or "none")
            end)
            nf.commands.register("shop", function(event)
              local window = event.player:open_menu("shop", {
                context = { label = "cheap", callback = function() return "called" end, who = event.player },
              })
              log("same", window:context().who == event.player)
            end)
            nf.commands.register("plain", function(event)
              event.player:open_menu("shop")
            end)
        """
    )

    @Test
    fun `a menu window gets the context it was opened with, and every event on it carries it`() {
        TestServer(menus).use { server ->
            val alex = server.player("Alex")
            server.platform.commands.run(alex, "shop")
            server.platform.menus.click(alex, 3)
            server.platform.menus.closeAny(alex.ref.uuid)
            server.platform.commands.run(alex, "plain")
            assertEquals(
                listOf(
                    "body\ttable\tcheap",
                    "nf open\tcheap",
                    "same\ttrue",
                    "click\ttrue\tcheap\tcalled",
                    "close\tcheap",
                    "body\tnil\tnil",
                    "nf open\tnone"
                ),
                server.logs
            )
            assertEquals(emptyList(), server.errors.map { it.message })
        }
    }

    @Test
    fun `a shared menu can't take a context, and a misspelled option is an error`() {
        for ((line, message) in listOf(
            "event.player:open_menu(\"bank\", { context = 1 })" to "menu bank is shared",
            "event.player:open_menu(\"shop\", { contxt = 1 })" to "unknown field 'options.contxt' (fields: context)"
        )) {
            TestServer(
                menus + ("modules/m/init.lua" to "nf.commands.register(\"go\", function(event)\n  $line\nend)")
            ).use { server ->
                server.platform.commands.run(server.player(), "go")
                val error = server.errors.single()
                assertTrue(message in error.message, "${error.message} should say $message")
                assertEquals("modules/m/init.lua", error.source?.file)
                assertEquals(2, error.source?.line)
            }
        }
        // A shared menu opened without one is fine, and has none.
        TestServer(
            menus +
                (
                    "modules/m/init.lua" to
                        "nf.commands.register(\"go\", function(event)\n  log(event.player:open_menu(\"bank\", {}):context())\nend)"
                    )
        ).use { server ->
            server.platform.commands.run(server.player(), "go")
            assertEquals(listOf("nil"), server.logs)
        }
    }

    private val dialogs = mapOf(
        "dialogs/rename/dialog.json" to """
            {
              "type": "multi_action",
              "title": "Rename",
              "script": { "file": "script.lua" },
              "body": [
                { "type": "message", "key": "intro", "text": "Pick a name" },
                { "type": "message", "text": "Unkeyed" }
              ],
              "inputs": [
                { "type": "text", "key": "name", "initial": "nobody", "maxLength": 8 },
                { "type": "boolean", "key": "loud" },
                { "type": "number_range", "key": "size", "start": 1, "end": 5 },
                { "type": "single_option", "key": "color", "options": [{ "id": "red", "initial": true }, { "id": "blue" }] }
              ],
              "buttons": [{ "key": "ok" }],
              "afterAction": "none"
            }
        """,
        "dialogs/rename/script.lua" to """
            local function describe(context)
              if type(context) ~= "table" then
                return tostring(context)
              end
              return context.label .. (context.crate == nf.centities.get(context.id) and " (the same crate)" or "")
            end
            this:button("ok"):on("press", function(event) log("press", describe(event.context)) end)
            this:on("close", function(event) log("close", describe(event.context)) end)
        """,
        "centities/crate/centity.json" to TestServer.scriptedCentity(),
        "centities/crate/script.lua" to "-- nothing",
        "modules/m/init.lua" to """
            nf.commands.register("rename", function(event)
              local crate = nf.centities.spawn("crate", vec3(0, 64, 0))
              event.player:open_dialog("rename", {
                context = { label = "crate", crate = crate, id = crate:id() },
                title = "<gold>Rename the crate",
                body = { intro = "Its name now: Bob" },
                values = { name = "Bob", loud = true, size = 4, color = "blue" },
              })
            end)
            nf.commands.register("again", function(event)
              nf.dialogs.get("rename"):open_for(event.player)
            end)
            nf.commands.register("shut", function(event) event.player:close_dialog() end)
        """
    )

    @Test
    fun `a dialog opening carries its context to every press and its close, and changes only what it draws`() {
        TestServer(dialogs).use { server ->
            val alex = server.player("Alex")
            server.platform.commands.run(alex, "rename")
            val shown = server.platform.dialogs.showing.getValue(alex.ref.uuid).file
            assertEquals("<gold>Rename the crate", shown.title)
            assertEquals("Its name now: Bob", (shown.body[0] as MessageBody).text)
            assertEquals("Unkeyed", (shown.body[1] as MessageBody).text)
            assertEquals("Bob", (shown.inputs[0] as TextInput).initial)
            assertEquals(true, (shown.inputs[1] as dev.netherforge.format.dialog.BooleanInput).initial)
            assertEquals(4.0, (shown.inputs[2] as RangeInput).initial)
            assertEquals(listOf(null, true), (shown.inputs[3] as OptionInput).options.map { it.initial })

            // The dialog stays up after a press (afterAction none): the same opening, the same context.
            server.platform.dialogs.press(alex, "ok")
            server.platform.dialogs.press(alex, "ok")
            // Opening it again over itself closes the first opening, with its context; the new one has none.
            server.platform.commands.run(alex, "again")
            assertEquals("Rename", server.platform.dialogs.showing.getValue(alex.ref.uuid).file.title, "the file is unchanged")
            assertEquals("nobody", (server.platform.dialogs.showing.getValue(alex.ref.uuid).file.inputs[0] as TextInput).initial)
            server.platform.dialogs.press(alex, "ok")
            server.platform.commands.run(alex, "shut")
            assertEquals(
                listOf(
                    "press\tcrate (the same crate)",
                    "press\tcrate (the same crate)",
                    "close\tcrate (the same crate)",
                    "press\tnil",
                    "close\tnil"
                ),
                server.logs
            )
            assertEquals(emptyList(), server.errors.map { it.message })
        }
    }

    @Test
    fun `an option the dialog has no input or body element for, or a value of the wrong kind, is an error`() {
        for ((options, message) in listOf(
            "{ values = { nmae = \"x\" } }" to "dialog rename has no input \"nmae\" (it has: name, loud, size, color)",
            "{ body = { outro = \"x\" } }" to "dialog rename has no body element \"outro\" (it has: intro)",
            "{ values = { name = 3 } }" to "options.values.name must be a string (it's a text input)",
            "{ values = { name = \"far too long\" } }" to "more than the input's max_length of 8",
            "{ values = { loud = \"yes\" } }" to "options.values.loud must be true or false",
            "{ values = { size = 9 } }" to "options.values.size is 9.0, outside the input's range of 1.0 to 5.0",
            "{ values = { color = \"green\" } }" to "the input has no option \"green\" (it has: red, blue)",
            "{ values = { name = {} } }" to "bad argument 'options.values.name' (string, number or boolean expected, got table)",
            "{ titel = \"x\" }" to "unknown field 'options.titel'"
        )) {
            TestServer(
                dialogs + (
                    "modules/m/init.lua" to
                        "nf.commands.register(\"go\", function(event)\n  nf.dialogs.get(\"rename\"):open_for(nf.players.get(\"Alex\"), $options)\nend)"
                    )
            ).use { server ->
                // Checked even when they're offline, so there's nothing to show: a mistake is a mistake.
                server.platform.players.byId.remove(server.player("Alex").ref.uuid)
                server.platform.commands.runAsConsole("go")
                val error = server.errors.single()
                assertTrue(message in error.message, "${error.message} should say $message")
                assertEquals(2, error.source?.line)
                assertTrue(server.platform.dialogs.showing.isEmpty())
            }
        }
    }

    @Test
    fun `ask takes the same options, and the press it returns carries the context`() {
        TestServer(
            dialogs + (
                "modules/m/init.lua" to """
                    nf.commands.register("ask", function(event)
                      local player = event.player
                      nf.task(function()
                        local answer = nf.dialogs.get("rename"):ask(player, { context = "from ask", values = { name = "Al" } })
                        log("answer", answer.key, answer.context)
                      end)
                    end)
                    nf.commands.register("typo", function(event)
                      local player = event.player
                      nf.task(function()
                        nf.dialogs.get("rename"):ask(player, { contxt = 1 })
                      end)
                    end)
                """
                )
        ).use { server ->
            val alex = server.player("Alex")
            server.platform.commands.run(alex, "ask")
            assertEquals("Al", (server.platform.dialogs.showing.getValue(alex.ref.uuid).file.inputs[0] as TextInput).initial)
            server.platform.dialogs.press(alex, "ok")
            server.platform.commands.run(alex, "typo")
            assertEquals(listOf("press\tfrom ask", "answer\tok\tfrom ask"), server.logs)
            assertTrue("unknown field 'options.contxt'" in server.errors.single().message)
        }
    }
}
