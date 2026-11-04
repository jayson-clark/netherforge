package dev.netherforge.plugin.centity

import dev.netherforge.plugin.LuaChecks
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.ClickButton
import dev.netherforge.plugin.platform.EntityRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `nf.centities.spawn`'s `viewers`: private centities, made of client entities only their viewers have. */
class PrivateCentityTest {
    private val page = """
        {
          "nodes": {
            "base": { "display": { "type": "block", "block": "minecraft:stone" }, "hitbox": {} },
            "label": { "parent": "base", "display": { "type": "text", "text": "hi" } }
          },
          "script": { "file": "script.lua" }
        }
    """

    private fun server(module: String, script: String = "") = TestServer(
        mapOf(
            "centities/page/centity.json" to page,
            "centities/page/script.lua" to script,
            "modules/pages/init.lua" to module
        )
    )

    private fun TestServer.live() = runtime.session.centities.all().filter { !it.removed }

    @Test
    fun `a private centity is made of client entities its viewers alone have`() {
        server(
            """
            nf.commands.register("open", function()
              local alex, sam = nf.players.get("Alex"), nf.players.get("Sam")
              local page = nf.centities.spawn("page", vec3(0, 64, 0), { temporary = true, viewers = { alex } })
              local names = {}
              for _, viewer in ipairs(page:viewers()) do names[#names + 1] = viewer:name() end
              log(table.concat(names, ","), tostring(page:is_hidden_from(alex)), tostring(page:is_hidden_from(sam)))
              log(tostring(nf.centities.spawn("page", vec3(4, 64, 0)):viewers()))
            end)
            """
        ).use { server ->
            val alex = server.player("Alex")
            val sam = server.player("Sam")
            server.platform.commands.runConsole("open")
            server.tick()
            assertEquals(listOf("Alex\tfalse\ttrue", "nil"), server.logs)
            val (private, public) = server.live().sortedBy { it.anchor.x }
            val client = server.platform.clientEntities
            assertTrue(server.platform.entities.of(private.id).isEmpty(), "the server has none of a private one's entities")
            assertEquals(setOf(EntityRole.ANCHOR, EntityRole.DISPLAY, EntityRole.HITBOX), client.of(private.id).map { it.role }.toSet())
            assertTrue(client.of(private.id).all { it.viewers == setOf(alex.ref.uuid) }, "every one of them is Alex's alone")
            assertEquals(client.of(private.id).map { it.id }.toSet(), client.seenBy(alex.ref.uuid).map { it.id }.toSet())
            assertEquals(emptyList(), client.seenBy(sam.ref.uuid))
            assertTrue(client.of(public.id).isEmpty() && server.platform.entities.of(public.id).isNotEmpty(), "a public one is as it was")
            // Its block display rides its anchor (the text, a billboard, stands on its own), all posed as any centity's are.
            val anchor = client.anchor(private.id)
            assertEquals(anchor.id, client.display(private.id, "base").vehicle)
            assertTrue(client.displays.filter { it.tag.instance == private.id }.all { it.pose != null })
        }
    }

    @Test
    fun `only its viewers can click it, and show_to and hide_from change who they are`() {
        server(
            """
            nf.commands.register("open", function()
              page = nf.centities.spawn("page", vec3(0, 64, 0), { temporary = true, viewers = { nf.players.get("Alex") } })
            end)
            nf.commands.register("swap", function()
              local alex, sam = nf.players.get("Alex"), nf.players.get("Sam")
              log(tostring(page:show_to(sam)), tostring(page:hide_from(alex)), tostring(page:is_hidden_from(alex)), tostring(page:is_hidden_from(sam)))
            end)
            """,
            """this:node("base"):on("click", function(event) log("clicked by " .. event.player:name()) end)"""
        ).use { server ->
            val alex = server.player("Alex")
            val sam = server.player("Sam")
            server.platform.commands.runConsole("open")
            server.tick()
            val page = server.live().single()
            val hitbox = server.platform.clientEntities.hitbox(page.id, "base")
            server.runtime.events.entityClicked(hitbox.id, alex.ref, ClickButton.RIGHT, null)
            server.runtime.events.entityClicked(hitbox.id, sam.ref, ClickButton.RIGHT, null)
            assertEquals(listOf("clicked by Alex"), server.logs)

            server.platform.commands.runConsole("swap")
            assertEquals("true\ttrue\ttrue\tfalse", server.logs.last())
            assertTrue(server.platform.clientEntities.of(page.id).all { it.viewers == setOf(sam.ref.uuid) })
            server.runtime.events.entityClicked(hitbox.id, alex.ref, ClickButton.RIGHT, null)
            server.runtime.events.entityClicked(hitbox.id, sam.ref, ClickButton.RIGHT, null)
            assertEquals("clicked by Sam", server.logs.last())
            assertEquals(1, server.logs.count { it == "clicked by Alex" })
        }
    }

    @Test
    fun `it moves and changes as any centity does, and goes with its script`() {
        server(
            """
            nf.commands.register("open", function()
              page = nf.centities.spawn("page", vec3(0, 64, 0), { temporary = true, viewers = { nf.players.get("Alex") } })
            end)
            nf.commands.register("move", function()
              page:teleport(vec3(8, 64, 0))
              assert(page:node("label")):set_display_text("bye")
              log(tostring(page:keep()), tostring(page:is_temporary()))
            end)
            """
        ).use { server ->
            server.player("Alex")
            server.platform.commands.runConsole("open")
            server.tick()
            val page = server.live().single()
            val client = server.platform.clientEntities
            server.platform.commands.runConsole("move")
            server.tick()
            assertEquals(listOf("false\ttrue"), server.output(), "a private one can't be kept")
            assertEquals(8.0, client.anchor(page.id).location.x)
            assertEquals("bye", (client.display(page.id, "label").display as dev.netherforge.format.centity.TextDisplay).text)

            server.write("modules/pages/init.lua", "")
            server.reload("modules/pages/init.lua")
            assertEquals(emptyList(), server.live())
            assertTrue(client.all.isEmpty(), "every client entity went with it")
        }
    }

    @Test
    fun `a page each for many players costs the server no entities`() {
        server(
            """
            nf.commands.register("open", function()
              for _, player in ipairs(nf.players.online()) do
                nf.centities.spawn("page", vec3(0, 64, 0), { temporary = true, viewers = { player } })
              end
            end)
            """
        ).use { server ->
            val players = (1..40).map { server.player("P$it") }
            server.platform.commands.runConsole("open")
            server.tick()
            assertEquals(40, server.live().size)
            assertTrue(server.platform.entities.all.isEmpty())
            for (player in players) {
                val seen = server.platform.clientEntities.seenBy(player.ref.uuid)
                assertEquals(1, seen.map { it.tag.instance }.toSet().size, "${player.ref.name} has their own page only")
            }
        }
    }

    @Test
    fun `only a temporary centity can be private`() {
        val result = LuaChecks.run(
            """
            local alex = nf.players.get("Alex")
            fails("normal", function() nf.centities.spawn("page", vec3(0, 64, 0), { viewers = {} }) end, "only a temporary centity can be private")
            local timed = nf.centities.spawn("page", vec3(0, 64, 0), { lifetime = 20, viewers = {} })
            assert(timed:viewers() and #timed:viewers() == 0, "nobody yet")
            """,
            mapOf("centities/page/centity.json" to page, "centities/page/script.lua" to "")
        )
        assertEquals(listOf("done"), result)
        assertFalse(result.isEmpty())
    }
}
