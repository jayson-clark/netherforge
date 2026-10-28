package dev.netherforge.plugin.module
import dev.netherforge.format.bridge.ReloadedResource
import dev.netherforge.format.bridge.SourceRef
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.ClickButton
import dev.netherforge.plugin.platform.Location
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `require` of the `.lua` files beside a resource's script: they run in the
 * requiring script's own scope, once per scope, and reload with the resource.
 */
class SiblingFilesTest {
    private val at = Location("world", 0.0, 64.0, 0.0)

    private fun centity(script: String, vararg files: Pair<String, String>) = mapOf(
        "centities/c/centity.json" to TestServer.scriptedCentity(),
        "centities/c/script.lua" to script
    ) + files

    @Test
    fun `each centity instance gets its own copy of a file beside its script, and a module stays shared`() {
        val files = centity(
            """
                log("before " .. tostring(counter_global))
                local counter = require("counter")
                local shared = require("shared")
                counter.bump()
                counter.bump()
                shared.count = shared.count + 1
                log(counter.report() .. ", same " .. tostring(counter == require("counter")) .. ", shared " .. shared.count)
                log("global " .. tostring(counter_global))
            """,
            "centities/c/counter.lua" to """
                local n = 0
                local mine = this:id()
                counter_global = "from counter"
                log("counter loaded for " .. mine)
                return {
                  bump = function() n = n + 1 end,
                  report = function() return (mine == this:id() and "own this" or "someone else's this") .. ", count " .. n end,
                }
            """,
            "modules/shared/init.lua" to """return { count = 0 }"""
        )
        TestServer(files).use { server ->
            val a = server.runtime.session.centities.spawn("c", at)!!
            val b = server.runtime.session.centities.spawn("c", at)!!
            assertEquals(
                listOf(
                    "before nil",
                    "counter loaded for ${a.id}",
                    "own this, count 2, same true, shared 1",
                    "global from counter",
                    "before nil",
                    "counter loaded for ${b.id}",
                    "own this, count 2, same true, shared 2",
                    "global from counter"
                ),
                server.logs
            )
        }
    }

    @Test
    fun `a file beside the script shadows a module of the same name, and other names fall back to modules`() {
        val files = centity(
            """
                log(require("greeter").who)
                log(require("greeter.messages").who)
                log(require("tools").who)
            """,
            "centities/c/greeter.lua" to """return { who = "the centity's greeter" }""",
            "centities/c/util.lua" to """return { who = "the centity's util" }""",
            "modules/greeter/init.lua" to """return { who = "the greeter module" }""",
            "modules/greeter/messages.lua" to """return { who = "the greeter module's messages" }""",
            "modules/tools/init.lua" to """return { who = "the tools module, with " .. require("util").who }""",
            "modules/tools/util.lua" to """return { who = "its own util" }"""
        )
        TestServer(files).use { server ->
            server.runtime.session.centities.spawn("c", at)
            assertEquals(
                listOf("the centity's greeter", "the greeter module's messages", "the tools module, with its own util"),
                server.logs
            )
        }
    }

    @Test
    fun `nested folders and init files resolve from the script's folder`() {
        val files = centity(
            """
                local ai = require("ai")
                log(ai.describe())
                log("one copy " .. tostring(ai.math == require("lib.math")))
            """,
            "centities/c/ai/init.lua" to """
                local math = require("lib.math")
                return { math = math, describe = function() return "ai doubles 21 to " .. math.double(21) end }
            """,
            "centities/c/lib/math.lua" to """return { double = function(n) return n * 2 end }"""
        )
        TestServer(files).use { server ->
            server.runtime.session.centities.spawn("c", at)
            assertEquals(listOf("ai doubles 21 to 42", "one copy true"), server.logs)
        }
    }

    @Test
    fun `a circular require, and requiring the script itself, are errors`() {
        val files = centity(
            """
                log(pcall(require, "a"))
                log(pcall(require, "script"))
                log(pcall(require, "nothing"))
            """,
            "centities/c/a.lua" to """return require("b")""",
            "centities/c/b.lua" to """return require("a")"""
        )
        TestServer(files).use { server ->
            server.runtime.session.centities.spawn("c", at)
            val (circular, own, missing) = server.logs
            assertTrue(circular.startsWith("false\t") && "circular require of centities/c/a.lua" in circular, circular)
            assertTrue(
                own.startsWith("false\t") && "this script's own file, centities/c/script.lua, which is already running" in own,
                own
            )
            val looked = "centities/c/nothing.lua, centities/c/nothing/init.lua, modules/nothing/init.lua"
            assertTrue("no file or module \"nothing\" (looked for $looked)" in missing, missing)
        }
    }

    @Test
    fun `an error in a file beside the script reports that file and line`() {
        val files = centity(
            """
                local helper = require("helper")
                this:on("click", helper.click)
            """,
            "centities/c/helper.lua" to """
                return {
                  click = function()
                    error("helper broke")
                  end,
                }
            """
        )
        TestServer(files).use { server ->
            val instance = server.runtime.session.centities.spawn("c", at)!!
            server.runtime.events.entityClicked(
                server.platform.entities.hitbox(instance.id, "root").id,
                server.player("Alex").ref,
                ClickButton.RIGHT,
                null
            )
            val error = server.errors.single()
            assertEquals(SourceRef("centities/c/helper.lua", 3), error.source)
            assertTrue("helper broke" in error.message, error.message)

            // In its body: the script fails to load, at the helper's line.
            server.write("centities/c/helper.lua", "local x = 1\nerror('body broke')\n")
            val result = server.reload("centities/c/helper.lua").resources.single()
            assertFalse(result.ok)
            val problem = result.problems.single()
            assertEquals("centities/c/helper.lua" to 2, problem.file to problem.line)
            assertEquals(SourceRef("centities/c/helper.lua", 2), server.errors.last().source)
        }
    }

    @Test
    fun `saving, adding or deleting a file beside the script reloads the resource`() {
        val files = centity(
            """
                log("loaded " .. require("helper").version)
                local ok = pcall(require, "extra")
                log("extra " .. tostring(ok))
            """,
            "centities/c/helper.lua" to """require("m") return { version = 1 }""",
            "modules/m/init.lua" to """return {}"""
        )
        TestServer(files).use { server ->
            server.runtime.session.centities.spawn("c", at)
            assertEquals(listOf("loaded 1", "extra false"), server.logs)

            server.write("centities/c/helper.lua", """require("m") return { version = 2 }""")
            assertEquals(
                listOf(ReloadedResource("test", "centity", "c", ok = true, reattached = 1)),
                server.reload("centities/c/helper.lua").resources
            )
            assertEquals(listOf("loaded 2", "extra false"), server.logs.drop(2))

            server.write("centities/c/extra.lua", "return true")
            assertEquals(listOf("centity:c"), server.reload("centities/c/extra.lua").resources.map { it.label })
            assertEquals(listOf("loaded 2", "extra true"), server.logs.drop(4))

            server.delete("centities/c/extra.lua")
            assertEquals(listOf("centity:c"), server.reload("centities/c/extra.lua").resources.map { it.label })
            assertEquals(listOf("loaded 2", "extra false"), server.logs.drop(6))

            // A module the helper required restarts the centity with it, as one its script required would.
            assertEquals(listOf("module:m", "centity:c"), server.reload("modules/m/init.lua").resources.map { it.label })
            assertEquals(listOf("loaded 2", "extra false"), server.logs.drop(8))
        }
    }

    @Test
    fun `each menu window gets its own copy`() {
        val files = mapOf(
            "menus/menu/menu.json" to """{ "title": "Menu", "rows": 1, "script": { "file": "script.lua" } }""",
            "menus/menu/script.lua" to """
                local opens = require("opens")
                this:on("open", function(event) log(event.player:name() .. " opened, count " .. opens.bump()) end)
            """,
            "menus/menu/opens.lua" to """
                local n = 0
                return { bump = function() n = n + 1 return n end }
            """,
            "modules/m/init.lua" to """nf.commands.register("menu", function(ctx) ctx.player:open_menu("menu") end)"""
        )
        TestServer(files).use { server ->
            server.platform.commands.run(server.player("Alex"), "menu")
            server.platform.commands.run(server.player("Sam"), "menu")
            assertEquals(listOf("Alex opened, count 1", "Sam opened, count 1"), server.logs)
        }
    }

    @Test
    fun `dialogs and project items require the files beside their scripts too`() {
        val files = mapOf(
            "dialogs/ask/dialog.json" to """{ "type": "notice", "title": "Ask", "script": { "file": "script.lua" } }""",
            "dialogs/ask/script.lua" to """log(require("words").greet())""",
            "dialogs/ask/words.lua" to """return { greet = function() return "dialog " .. this:id() .. " says hi" end }""",
            "items/gem/item.json" to """{ "kind": "minecraft:emerald", "script": { "file": "main.lua" } }""",
            "items/gem/main.lua" to """log(require("words").greet())""",
            "items/gem/words.lua" to """return { greet = function() return "item " .. this:id() .. " says hi" end }"""
        )
        TestServer(files).use { server ->
            assertEquals(setOf("dialog ask says hi", "item gem says hi"), server.logs.toSet())
            assertTrue(server.errors.isEmpty(), "${server.errors}")
        }
    }
}
