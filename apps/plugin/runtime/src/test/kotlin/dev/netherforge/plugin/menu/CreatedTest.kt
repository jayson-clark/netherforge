package dev.netherforge.plugin.menu
import dev.netherforge.format.dialog.ItemBody
import dev.netherforge.format.dialog.TextInput
import dev.netherforge.format.menu.MenuType
import dev.netherforge.plugin.TestServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Menus and dialogs made in Lua (`nf.menus.create`, `nf.dialogs.create`),
 * and `nf.text.width`: held to the files' rules, opened like
 * the files', and gone with the script that made them.
 */
class CreatedTest {
    /** A module that keeps one template and one dialog in globals, with commands to open them. */
    private val module = """
        picker = nf.menus.create({
          rows = 1,
          title = "<gold>Pick",
          slots = { [0] = { kind = "minecraft:bread", name = "Stone" }, [4] = { kind = "minecraft:diamond" } },
        })
        picker:on("open", function(event) log("template open " .. tostring(event.menu:template() == picker)) end)
        picker:on("click", function(event)
          log("template click " .. tostring(event.index) .. " " .. tostring(event.cancelled) .. " " .. tostring(event.context and event.context.why))
        end)
        picker:on("close", function(event) log("template close") end)
        nf.on("menu_click", function(event) log("nf click") end)

        rename = nf.dialogs.create({
          type = "confirmation",
          title = "Rename",
          after_action = "close",
          inputs = { { type = "text", key = "name", label = "Name", max_length = 16 } },
          buttons = { { key = "ok", label = "Rename" }, { key = "cancel" } },
        })
        rename:button("ok"):on("press", function(event)
          log("ok " .. event.values.name .. " " .. tostring(event.context))
        end)
        rename:on("press", function(event) log("dialog heard " .. event.key) end)

        nf.commands.register("pick", function(event)
          local menu = event.player:open_menu(picker, { context = { why = "test" } })
          log("kind " .. tostring(menu:kind()) .. ", size " .. menu:size() .. ", " .. menu:item(0).name .. ", windows " .. #picker:windows())
        end)
        nf.commands.register("rename", function(event)
          log("opened " .. tostring(event.player:open_dialog(rename, { context = "ctx", values = { name = "Rex" } })))
        end)
        nf.commands.register("ask", function(event)
          nf.task(function()
            local answer = rename:ask(event.player)
            log("asked " .. tostring(answer and answer.key))
          end)
        end)
        nf.commands.register("forget", function()
          log("removed " .. tostring(picker:remove()) .. " " .. tostring(picker:remove()) .. " " .. tostring(picker:exists()))
        end)
    """

    @Test
    fun `a template opens windows of its own, and its handlers hear every one after the window's`() {
        TestServer(mapOf("modules/m/init.lua" to module)).use { server ->
            val alex = server.player("Alex")
            server.platform.commands.run(alex, "pick")
            assertEquals(emptyList(), server.errors.map { it.message })
            val window = server.platform.menus.windows.values.single()
            assertEquals(MenuType.CHEST, window.spec.type)
            assertEquals(9, window.spec.size)
            assertEquals("<gold>Pick", window.title)
            assertEquals("minecraft:diamond", window.slots[4]?.def?.kind)
            assertTrue(server.platform.menus.click(alex, 0), "a template is locked unless it says otherwise")
            server.platform.menus.close(server.platform.menus.windows.keys.single(), alex.ref.uuid)
            server.tick()
            assertEquals(
                listOf(
                    "template open true",
                    "kind nil, size 9, Stone, windows 1",
                    "template click 0 true test",
                    "nf click",
                    "template close"
                ),
                server.logs
            )
            assertTrue(server.platform.menus.windows.isEmpty(), "its window goes with its last viewer")
        }
    }

    @Test
    fun `a template goes with its script or remove, closing its windows`() {
        TestServer(mapOf("modules/m/init.lua" to module)).use { server ->
            val alex = server.player("Alex")
            server.platform.commands.run(alex, "pick")
            server.platform.commands.run(alex, "forget")
            assertEquals("removed true false false", server.logs.last())
            assertTrue(server.platform.menus.windows.isEmpty())

            server.platform.commands.run(alex, "pick")
            assertTrue(server.platform.menus.windows.isEmpty(), "a template that's gone opens nothing")
            assertTrue(server.errors.single().message.contains("attempt to index a nil value"), server.errors.single().message)

            // Reloading the module makes a new template; the old one's windows close with the old script.
            server.reload("modules/m/init.lua")
            server.platform.commands.run(alex, "pick")
            assertEquals(1, server.platform.menus.windows.size)
            server.reload("modules/m/init.lua")
            assertTrue(server.platform.menus.windows.isEmpty())
        }
    }

    @Test
    fun `a menu definition is held to menu json's rules`() {
        val result = mistakes(
            "nf.menus.create",
            mapOf(
                "{ rowz = 1 }" to "unknown field 'definition.rowz' (fields: locked, rows, skin, slots, title, type)",
                "{ rows = 7 }" to "definition.rows: A chest has 1 to 6 rows",
                "{ type = \"hopper\", rows = 1 }" to "definition.rows: A hopper is always 5 slots",
                "{ type = \"oven\" }" to "definition.type: \"oven\" isn't one of",
                "{ rows = \"two\" }" to "definition",
                "{ rows = 1, slots = { [9] = { kind = \"minecraft:bread\" } } }" to "definition.slots[9]: This menu has 9 slots (0–8)",
                "{ slots = { [0] = { kind = \"minecraft:stonee\" } } }" to "definition.slots[0]",
                "{ skin = \"ui/nope\" }" to "there's no resource pack \"ui\""
            )
        )
        assertEquals(emptyList(), result)
    }

    @Test
    fun `a dialog made in Lua opens, answers presses and asks like a file's`() {
        TestServer(mapOf("modules/m/init.lua" to module)).use { server ->
            val alex = server.player("Alex")
            server.platform.commands.run(alex, "rename")
            val shown = server.platform.dialogs.showing.getValue(alex.ref.uuid)
            assertEquals("Rename", shown.file.title)
            assertEquals("Rex", (shown.file.inputs.single() as TextInput).initial)
            assertEquals(16, (shown.file.inputs.single() as TextInput).maxLength)
            server.platform.dialogs.press(alex, "ok", mapOf("name" to "Max"))

            server.platform.commands.run(alex, "ask")
            server.platform.dialogs.press(alex, "cancel")
            assertEquals(
                listOf("opened true", "ok Max ctx", "dialog heard ok", "dialog heard cancel", "asked cancel"),
                server.logs
            )
            assertEquals(emptyList(), server.errors.map { it.message })
        }
    }

    @Test
    fun `a dialog made in Lua goes with its script`() {
        TestServer(mapOf("modules/m/init.lua" to module + "\nnf.on('unload', function() kept = rename end)")).use { server ->
            val alex = server.player("Alex")
            val id = server.runtime.session.dialogs.ids()
            assertEquals(emptyList(), id, "made dialogs aren't the project's")
            server.platform.commands.run(alex, "rename")
            server.reload("modules/m/init.lua")
            // The old dialog is gone: a press for it is ignored, and the new script's handlers don't hear it.
            server.platform.dialogs.press(alex, "ok", mapOf("name" to "Max"))
            assertEquals(listOf("opened true"), server.logs)
        }
    }

    @Test
    fun `items in a made dialog or menu keep typed data, as every item a script hands over does`() {
        val script = """
            nf.commands.register("show", function(event)
              local data = { at = vec3(1, 2, 3), by = event.player }
              local shown = nf.dialogs.create({
                title = "Loot",
                body = { { type = "message", text = "Hi" }, { type = "item", item = { kind = "minecraft:diamond", data = data } } },
              })
              local picker = nf.menus.create({ rows = 1, slots = { [0] = { kind = "minecraft:diamond", data = data } } })
              local menu = event.player:open_menu(picker)
              log(tostring(menu:item(0).data.at), menu:item(0).data.by == event.player)
              event.player:open_dialog(shown)
            end)
            nf.commands.register("bad", function()
              nf.dialogs.create({ title = "T", body = { { type = "item", item = { kind = "minecraft:diamond", data = { f = print } } } } })
            end)
        """
        TestServer(mapOf("modules/m/init.lua" to script)).use { server ->
            val alex = server.player("Alex")
            server.platform.commands.run(alex, "show")
            assertEquals(emptyList(), server.errors.map { it.message })
            assertEquals(listOf("vec3(1, 2, 3)\ttrue"), server.logs)
            val body = server.platform.dialogs.showing.getValue(alex.ref.uuid).file.body
            val item = (body[1] as ItemBody).item
            assertTrue(item.data?.get("at").toString().contains("vec3"), item.data.toString())
            server.platform.commands.run(alex, "bad")
            val error = server.errors.single().message
            assertTrue("definition.body[1].item" in error && "can't be written as JSON" in error, error)
        }
    }

    @Test
    fun `a dialog definition is held to dialog json's rules`() {
        val result = mistakes(
            "nf.dialogs.create",
            mapOf(
                "{}" to "definition: Missing required key \"title\"",
                "{ title = \"T\", afterAction = \"close\" }" to
                    "definition.afterAction: Lua spells keys in snake_case, so it's after_action",
                "{ title = \"T\", colour = 1 }" to "Unknown key \"colour\"",
                "{ title = \"T\", script = { file = \"x.lua\" } }" to "definition.script: a dialog made in Lua has no script",
                "{ title = \"T\", type = \"notice\", buttons = { { key = \"a\" }, { key = \"b\" } } }" to
                    "definition.buttons: A notice dialog shows 1 button",
                "{ title = \"T\", type = \"multi_action\", buttons = { { key = \"a\" }, { key = \"a\" } } }" to
                    "definition.buttons[2].key: Two buttons are called \"a\"",
                "{ title = \"T\", inputs = { { type = \"text\", key = \"n\", max_length = 0 } } }" to
                    "definition.inputs[1].max_length: max_length must be at least 1",
                "{ title = \"T\", type = \"dialog_list\", dialogs = { \"nope\" } }" to
                    "definition.dialogs[1]: no dialog \"nope\" in this project",
                "{ title = \"T\", body = { { type = \"item\", item = { kind = \"minecraft:stonee\" } } } }" to "definition.body[1].item"
            )
        )
        assertEquals(emptyList(), result)
    }

    @Test
    fun `nf text width measures with the project's default font, and is nil without it`() {
        val script = """
            log(tostring(nf.text.width("AB")), tostring(nf.text.width("<bold>A</bold>")), tostring(nf.text.width("ABC")))
        """
        val font = """{ "minecraft": "26.3", "advances": { "65": 6, "66": 5 } }"""
        TestServer(mapOf("modules/m/init.lua" to script, "fonts/default.json" to font)).use { server ->
            // C isn't in the font: not measurable exactly.
            assertEquals(listOf("11\t7\tnil"), server.logs)
        }
        TestServer(mapOf("modules/m/init.lua" to script)).use { server ->
            assertEquals(listOf("nil\tnil\tnil"), server.logs)
        }
    }

    /** Calls [function] with each definition and returns the cases whose error didn't contain the expected text. */
    private fun mistakes(function: String, cases: Map<String, String>): List<String> {
        val checks = cases.entries.joinToString("\n") { (definition, message) ->
            val quoted = message.replace("\\", "\\\\").replace("\"", "\\\"")
            """
            do
              local ok, err = pcall($function, $definition)
              if ok or not tostring(err):find("$quoted", 1, true) then
                log("FAIL ${definition.replace("\"", "'")}: " .. tostring(err))
              end
            end
            """.trimIndent()
        }
        TestServer(mapOf("modules/m/init.lua" to checks)).use { server ->
            return server.errors.map { it.message } + server.logs
        }
    }
}
