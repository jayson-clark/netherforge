package dev.netherforge.plugin.world
import dev.netherforge.plugin.LuaChecks
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.GameEvent
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Real inventories (a player's, a container block's, an entity's), item
 * matching, boss bars and sidebars, against the fake server.
 */
class InventoryTest {
    @Test
    fun `a player's inventory takes, counts and finds items by kind or by a partial item`() {
        val result = LuaChecks.run(
            """
            local alex = nf.players.get("Alex")
            local inventory = alex:inventory()
            check("kind", inventory:kind(), "player")
            check("size", inventory:size(), 43)
            check("exists", inventory:exists(), true)
            check("holder", inventory:holder(), alex)
            check("same handle", alex:inventory(), inventory)
            check("add", inventory:add_item({ kind = "minecraft:gold_ingot", count = 10 }), 0)
            check("coins", inventory:add_item({ kind = "minecraft:gold_ingot", count = 5, data = { coin = true, value = 2, owner = alex } }), 0)
            inventory:set_item(20, { kind = "minecraft:paper", name = "<gold>Ticket" })
            inventory:set_item(21, { kind = "minecraft:paper" })
            check("by kind", inventory:count_item("minecraft:gold_ingot"), 15)
            check("short kind", inventory:count_item("gold_ingot"), 15)
            check("by data", inventory:count_item({ data = { coin = true } }), 5)
            check("by typed data", inventory:count_item({ data = { owner = alex } }), 5)
            check("data must all match", inventory:count_item({ data = { coin = true, value = 3 } }), 0)
            check("by name", inventory:count_item({ kind = "minecraft:paper", name = "<gold>Ticket" }), 1)
            check("has", inventory:has_item({ data = { coin = true } }, 5), true)
            check("not that many", inventory:has_item({ data = { coin = true } }, 6), false)
            check("first", inventory:first_slot({ data = { coin = true } }), 1)
            check("first paper", inventory:first_slot("paper"), 20)
            check("none", inventory:first_slot("diamond"), nil)
            check("remove some", inventory:remove_item({ data = { coin = true } }, 3), 3)
            check("left", inventory:item(1).count, 2)
            check("kept data", inventory:item(1).data.coin, true)
            check("remove all", inventory:remove_item("minecraft:gold_ingot"), 12)
            check("emptied", inventory:item(0), nil)
            check("items", next(inventory:items()), 20)
            fails("count in a match", function() inventory:count_item({ kind = "paper", count = 2 }) end, "a match has no count")
            fails("typo in a match", function() inventory:count_item({ knd = "paper" }) end, "items have no field \"knd\"")
            fails("unknown kind", function() inventory:count_item("minecraft:unobtainium") end, "has no item")
            fails("not a match", function() inventory:count_item(5) end, "bad argument 'match' (string or ItemMatch expected, got number)")
            fails("outside", function() inventory:item(43) end, "slot 43 is outside this inventory")
            check("clear", inventory:clear(), true)
            check("cleared", next(inventory:items()), nil)
            """,
            setup = { it.player("Alex") }
        )
        assertEquals(listOf("done"), result)
    }

    @Test
    fun `reading one slot or matching reads only what it must`() {
        TestServer(
            mapOf(
                "items/coin/item.json" to """{ "kind": "minecraft:gold_nugget" }""",
                "modules/t/init.lua" to """
                    nf.commands.register("read", function()
                      local inventory = nf.players.get("Alex"):inventory()
                      inventory:set_item(0, { item = "coin", count = 4 })
                      inventory:set_item(1, { kind = "minecraft:paper" })
                      log(inventory:item(0).count, inventory:count_item({ item = "coin" }), inventory:count_item("paper"),
                        inventory:has_item({ item = "coin" }, 4), inventory:first_slot({ item = "coin" }),
                        inventory:remove_item({ item = "coin" }, 1), inventory:item(0).count)
                    end)
                    nf.commands.register("all", function()
                      local items = nf.players.get("Alex"):inventory():items()
                      log(items[0].count, items[1].kind)
                    end)
                """.trimIndent()
            )
        ).use { server ->
            server.player("Alex")
            val inventories = server.platform.inventories
            server.platform.commands.runConsole("read")
            assertEquals(listOf("4\t4\t1\ttrue\t0\t1\t3"), server.logs)
            // A slot is read on its own, and a match reads only the stacks that could be one.
            assertEquals(0, inventories.fullReads)
            server.platform.commands.runConsole("all")
            assertEquals("3\tminecraft:paper", server.logs.last())
            assertEquals(1, inventories.fullReads)
        }
    }

    @Test
    fun `a chest's inventory is the block's, while it's a chest`() {
        val result = LuaChecks.run(
            """
            local world = nf.worlds.default()
            local block = world:block(vec3(4, 64, 4))
            check("not a container", block:inventory(), nil)
            block:set_state("minecraft:chest")
            local chest = block:inventory()
            check("kind", chest:kind(), "chest")
            check("size", chest:size(), 27)
            check("holder", chest:holder(), block)
            check("add", chest:add_item({ kind = "minecraft:bread", count = 70 }), 0)
            check("two stacks", chest:item(1).count, 6)
            local alex = nf.players.get("Alex")
            check("open", alex:open_inventory(chest), true)
            check("viewer", chest:viewers()[1], alex)
            block:set_state("minecraft:stone")
            check("gone", chest:exists(), false)
            check("gone kind", chest:kind(), nil)
            check("gone size", chest:size(), 0)
            check("gone add", chest:add_item({ kind = "minecraft:bread" }), 1)
            check("gone set", chest:set_item(0, nil), false)
            check("gone count", chest:count_item("bread"), 0)
            """,
            setup = { it.player("Alex") }
        )
        assertEquals(listOf("done"), result)
    }

    @Test
    fun `a boss bar shows to its viewers, again when they come back, and goes with its script`() {
        TestServer(
            mapOf(
                "modules/t/init.lua" to """
                    nf.commands.register("bar", function(event)
                      local bar = nf.bossbars.create({ text = "<red>Boss", color = "red", progress = 0.5 })
                      bar:show_to(event.player)
                      log(bar:text(), bar:color(), bar:style(), bar:progress(), #bar:viewers(), event.player:bossbars()[1] == bar)
                      bar:set_progress(0.25)
                      bar:set_style("notched_10")
                      local ok, err = pcall(bar.set_progress, bar, 2)
                      log(ok, err:find("progress must be from 0 to 1", 1, true) ~= nil)
                      nf.data("t").bar = nil
                      _G.bar = bar
                    end)
                    nf.commands.register("gone", function()
                      log(bar:exists(), bar:remove(), bar:exists(), bar:set_text("x"), bar:text())
                    end)
                """
            )
        ).use { server ->
            val alex = server.player("Alex")
            val commands = server.platform.commands
            commands.run(alex, "bar")
            assertEquals(listOf("<red>Boss\tred\tprogress\t0.5\t1\ttrue", "false\ttrue"), server.logs)
            val bars = server.platform.bossBars
            assertEquals(listOf("notched_10"), bars.of(alex).map { it.style })
            assertEquals(0.25, bars.of(alex).single().progress)
            // The server forgets a bar's viewers when they leave; it shows again when they're back.
            bars.shown.values.forEach { it.clear() }
            server.platform.raise.playerJoin(GameEvent.PlayerJoin(alex.ref, false, null))
            assertEquals(1, bars.of(alex).size)
            // A reload of the module takes its bars with it.
            server.reload("modules/t/init.lua")
            assertEquals(emptyMap(), bars.bars)
        }
    }

    @Test
    fun `a sidebar shows lines without numbers, and gives the main board back when hidden`() {
        val result = LuaChecks.run(
            """
            local alex = nf.players.get("Alex")
            local sidebar = alex:sidebar()
            check("hidden at first", sidebar:is_visible(), false)
            check("lines", sidebar:set_lines({ "<gold>Coins: 5", "", "<gray>example.net" }), true)
            check("shows", sidebar:is_visible(), true)
            check("title", sidebar:set_title("<b>Arena") and sidebar:title(), "<b>Arena")
            check("read lines", #sidebar:lines(), 3)
            local sixteen = {}
            for i = 1, 16 do sixteen[i] = "x" end
            fails("too many", function() sidebar:set_lines(sixteen) end, "at most 15 lines")
            fails("not strings", function() sidebar:set_lines({ "a", 5 }) end, "bad argument 'lines[2]' (string expected, got number)")
            check("hide", sidebar:set_visible(false), true)
            check("hidden", sidebar:is_visible(), false)
            check("kept lines", #sidebar:lines(), 3)
            check("show", sidebar:set_visible(true), true)
            """,
            setup = { it.player("Alex") },
            after = { server ->
                val alex = server.platform.players.byId.values.single()
                val sidebars = server.platform.sidebars
                assertEquals("<b>Arena" to listOf("<gold>Coins: 5", "", "<gray>example.net"), sidebars.showing[alex.ref.uuid])
                server.platform.raise.playerQuit(GameEvent.PlayerQuit(alex.ref, null))
                server.platform.raise.playerJoin(GameEvent.PlayerJoin(alex.ref, false, null))
                assertEquals(null, server.runtime.session.sidebars.state(alex.ref.uuid), "forgotten when they left")
            }
        )
        assertEquals(listOf("done"), result)
    }
}
