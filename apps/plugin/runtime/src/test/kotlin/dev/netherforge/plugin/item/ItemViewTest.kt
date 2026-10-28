package dev.netherforge.plugin.item
import dev.netherforge.format.item.ItemDef
import dev.netherforge.format.ref.ResourceRef
import dev.netherforge.plugin.LuaChecks
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.InventoryRef
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.testkit.FakeItemViews.Place
import dev.netherforge.plugin.testkit.FakePlayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An item's `view` and `nf`'s `item_view`: how a stack looks to the one player it's sent to, apart from what it is. */
class ItemViewTest {
    private val files = mapOf(
        "items/ruby/item.json" to """
            { "kind": "minecraft:emerald", "name": "Ruby", "lore": ["A gem."], "script": { "file": "script.lua" } }
        """,
        "items/ruby/script.lua" to """
            local this = this --[[@as ProjectItem]]
            this:on("view", function(event)
              if event.item.data and event.item.data.boom then
                error("boom")
              end
              local menu = event.player:menu()
              if menu and menu:kind() == "shop" then
                local lore = event.lore or {}
                lore[#lore + 1] = "<gold>Sells for 5"
                event.lore = lore
              end
            end)
        """,
        "menus/shop/menu.json" to """
            {
              "title": "Shop",
              "rows": 1,
              "script": { "file": "script.lua" },
              "slots": { "4": { "item": { "item": "ruby" } } }
            }
        """,
        "menus/shop/script.lua" to """
            local this = this --[[@as Menu]]
            this:on("open", function(event) event.player:refresh_items() end)
            this:on("close", function(event) event.player:refresh_items() end)
        """,
        "modules/m/init.lua" to """
            nf.commands.register("shop", function(ctx) ctx.player:open_menu("shop") end)
        """
    )

    private fun ruby(data: String? = null) = ItemData(
        ItemDef(item = ResourceRef("ruby"), data = data?.let { mapOf(it to kotlinx.serialization.json.JsonPrimitive(true)) })
    )

    private fun TestServer.give(player: FakePlayer, slot: Int, item: ItemData) =
        assertTrue(platform.inventories.setItem(InventoryRef.Player(player.ref.uuid), slot, item))

    private fun TestServer.shown(player: FakePlayer, place: Place) = platform.itemViews.shown(player.ref.uuid, place)

    @Test
    fun `a stack looks different to its player alone while their shop is open, and stays as it is`() {
        TestServer(files).use { server ->
            val alex = server.player("Alex")
            val sam = server.player("Sam")
            server.give(alex, 0, ruby())
            server.give(sam, 0, ruby())
            server.tick()
            assertEquals(listOf("A gem."), server.shown(alex, Place.Inventory(0))?.def?.lore, "no shop open")

            server.platform.commands.run(alex, "shop")
            server.tick()
            val sells = listOf("A gem.", "<gold>Sells for 5")
            assertEquals(sells, server.shown(alex, Place.Inventory(0))?.def?.lore, "their own inventory, refreshed as it opened")
            val window = server.platform.menus.viewing(alex.ref.uuid)!!
            assertEquals(sells, server.shown(alex, Place.Menu(window, 4))?.def?.lore, "the window's stacks")
            assertEquals(listOf("A gem."), server.shown(sam, Place.Inventory(0))?.def?.lore, "someone else sees it as it is")
            assertEquals(listOf("A gem."), alex.inventorySlots[0]!!.def.lore, "the server keeps it as it is")
            assertEquals(listOf("A gem."), server.platform.menus.windows.getValue(window).slots[4]!!.def.lore)
            assertEquals(alex.inventorySlots[0], sam.inventorySlots[0], "so it still stacks with any other")

            // What scripts read is the real stack too.
            server.write("modules/r/init.lua", "nf.after(1, function() log(#nf.players.get('Alex'):inventory():item(0).lore) end)")
            server.reload("modules/r/init.lua")
            server.tick(2)
            assertTrue("1" in server.logs, server.output().joinToString("\n"))

            server.platform.menus.closeAny(alex.ref.uuid)
            server.tick()
            assertEquals(listOf("A gem."), server.shown(alex, Place.Inventory(0))?.def?.lore, "refreshed as it closed")
            assertTrue(server.errors.isEmpty(), server.output().joinToString("\n"))
        }
    }

    @Test
    fun `a stack a player's game echoes back in creative mode comes back as the real one`() {
        TestServer(files).use { server ->
            val alex = server.player("Alex")
            alex.gameMode = "creative"
            server.give(alex, 0, ruby())
            server.platform.commands.run(alex, "shop")
            server.tick()
            val shown = server.shown(alex, Place.Inventory(0))!!
            assertEquals(listOf("A gem.", "<gold>Sells for 5"), shown.def.lore)
            // Their game moves the stack it was shown to another slot.
            server.platform.itemViews.creative(alex, 0, null)
            server.platform.itemViews.creative(alex, 7, shown.copy(def = shown.def.copy(count = 2)))
            assertEquals(listOf("A gem."), alex.inventorySlots[7]!!.def.lore, "no shown lore on the server")
            assertEquals(2, alex.inventorySlots[7]!!.def.count, "the count the game said")
            assertEquals(ResourceRef("ruby"), alex.inventorySlots[7]!!.def.item)
        }
    }

    @Test
    fun `refresh_items works every stack out again, and sends only what now looks different`() {
        val script = """
            local shop = false
            nf.on("item_view", function(event)
              if shop and event.item.kind == "minecraft:diamond" then
                event.display_name = "<aqua>Diamond <gray>(50)"
              end
            end)
            nf.commands.register("toggle", function(ctx)
              shop = not shop
              check("online", ctx.player:refresh_items(), true)
            end)
            nf.commands.register("offline", function()
              check("offline", nf.players.get("Sam"):refresh_items(), false)
              log("offline checked")
            end)
        """
        TestServer(files + ("modules/v/init.lua" to LuaChecks.module(script))).use { server ->
            val alex = server.player("Alex")
            server.give(alex, 0, ItemData(ItemDef(kind = "minecraft:diamond")))
            server.give(alex, 1, ItemData(ItemDef(kind = "minecraft:stick")))
            server.tick()
            assertNull(server.shown(alex, Place.Inventory(0))?.def?.name)
            val sends = server.platform.itemViews.sends
            server.tick(3)
            assertEquals(sends, server.platform.itemViews.sends, "nothing changed, nothing sent")

            val refreshes = server.platform.itemViews.refreshes.size
            server.platform.commands.run(alex, "toggle")
            assertNull(server.shown(alex, Place.Inventory(0))?.def?.name, "at the end of the tick")
            server.tick()
            assertEquals("<aqua>Diamond <gray>(50)", server.shown(alex, Place.Inventory(0))?.def?.name)
            assertNull(alex.inventorySlots[0]!!.def.name)
            assertEquals(sends + 1, server.platform.itemViews.sends, "only the diamond was sent again")
            assertEquals(listOf(alex.ref.uuid), server.platform.itemViews.refreshes.drop(refreshes))

            server.platform.commands.run(alex, "toggle")
            server.platform.commands.run(alex, "toggle")
            server.platform.commands.run(alex, "toggle")
            server.tick()
            assertEquals(refreshes + 2, server.platform.itemViews.refreshes.size, "once a tick, however often it's asked")
            assertNull(server.shown(alex, Place.Inventory(0))?.def?.name)
            assertEquals(ItemData(ItemDef(kind = "minecraft:stick", count = 1)), server.shown(alex, Place.Inventory(1)))

            server.platform.players.quit(server.player("Sam"))
            server.platform.commands.run(alex, "offline")
            assertEquals(listOf("done", "offline checked"), server.output())
        }
    }

    @Test
    fun `a failing handler is reported at its line, and the stack is sent as it is`() {
        TestServer(files).use { server ->
            val alex = server.player("Alex")
            server.give(alex, 0, ruby("boom"))
            server.platform.commands.run(alex, "shop")
            server.tick()
            assertEquals(listOf("A gem."), server.shown(alex, Place.Inventory(0))?.def?.lore)
            val error = server.errors.first()
            assertTrue("boom" in error.message, error.message)
            assertEquals("items/ruby/script.lua", error.source?.file)
            assertEquals(4, error.source?.line)
        }
    }

    @Test
    fun `nobody listening, no stack is worked out`() {
        TestServer(files - "items/ruby/script.lua" + ("items/ruby/item.json" to """{ "kind": "minecraft:emerald" }""")).use { server ->
            val alex = server.player("Alex")
            server.give(alex, 0, ruby())
            server.give(alex, 1, ItemData(ItemDef(kind = "minecraft:stick")))
            server.platform.commands.run(alex, "shop")
            server.tick(2)
            assertEquals(0, server.platform.itemViews.viewed)
        }
    }

    @Test
    fun `a reload that takes a handler away sends everyone their stacks as they are again`() {
        val module = """nf.on("item_view", function(event) event.lore = { "<red>Cursed" } end)"""
        TestServer(files + ("modules/curse/init.lua" to module)).use { server ->
            val alex = server.player("Alex")
            server.give(alex, 0, ItemData(ItemDef(kind = "minecraft:stick")))
            server.tick()
            assertEquals(listOf("<red>Cursed"), server.shown(alex, Place.Inventory(0))?.def?.lore)
            server.write("modules/curse/init.lua", "-- lifted")
            server.reload("modules/curse/init.lua")
            server.tick()
            assertNull(server.shown(alex, Place.Inventory(0))?.def?.lore)
            assertFalse(server.platform.itemViews.refreshes.isEmpty())
        }
    }
}
