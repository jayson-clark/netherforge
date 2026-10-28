package dev.netherforge.plugin.item
import dev.netherforge.plugin.LuaChecks
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.InventoryRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An item's `canBreak` and `canPlaceOn` naming the project's blocks (and the game's tags, and any block): what
 * a script may write, what it reads back, and the stacks being checked again when the blocks' states move.
 */
class AdventureBlocksTest {
    private val files = mapOf(
        "blocks/ruby_ore/block.json" to "{}",
        "blocks/amber/block.json" to "{}",
        "items/pick/item.json" to """{ "kind": "minecraft:paper", "canBreak": ["ruby_ore", "#minecraft:logs"] }"""
    )

    @Test
    fun `a script names the project's blocks bare, the game's with their namespace, tags and any block`() {
        val result = LuaChecks.run(
            """
            local inventory = nf.players.get("Alex"):inventory()
            inventory:set_item(0, { kind = "minecraft:paper", can_break = { "ruby_ore", "#minecraft:logs", "minecraft:stone" }, can_place_on = { "*" } })
            local item = inventory:item(0)
            check("project block", item.can_break[1], "ruby_ore")
            check("tag", item.can_break[2], "#minecraft:logs")
            check("game block", item.can_break[3], "minecraft:stone")
            check("any", item.can_place_on[1], "*")
            check("the item's own", nf.items.create("pick").can_break[1], "ruby_ore")
            fails("bare game block", function() inventory:set_item(1, { kind = "minecraft:paper", can_break = { "stone" } }) end,
              "item.can_break[1]: there's no block \"stone\" (the game's are written \"minecraft:stone\"")
            fails("missing block", function() inventory:set_item(1, { kind = "minecraft:paper", can_place_on = { "*", "emerald_ore" } }) end,
              "item.can_place_on[2]: there's no block \"emerald_ore\"")
            fails("another namespace", function() inventory:set_item(1, { kind = "minecraft:paper", can_break = { "acme:gem" } }) end,
              "no package on this server has the namespace \"acme\"")
            """.trimIndent(),
            files,
            setup = { it.platform.players.add("Alex") }
        )
        assertEquals(LuaChecks.DONE, result)
    }

    @Test
    fun `a change that moves the blocks' states has every online inventory checked again`() {
        TestServer(files).use { server ->
            server.platform.players.add("Alex")
            val refreshes = { server.platform.projectItems.checked.count { it is InventoryRef.Player } }
            val before = refreshes()
            assertEquals("minecraft:note_block", server.platform.blockStates("ruby_ore")?.substringBefore('['))
            val ruby = server.platform.blockStates("ruby_ore")

            // A block whose name sorts first takes the first state: every other block's moves.
            server.write("blocks/agate/block.json", "{}")
            server.reload("blocks/agate/block.json")
            assertTrue(server.platform.blockStates("ruby_ore") != ruby, "the state moved")
            assertTrue(refreshes() > before, "inventories were checked")

            // A change that moves nothing checks nothing.
            val after = refreshes()
            server.write("blocks/agate/block.json", """{ "hardness": 2 }""")
            server.reload("blocks/agate/block.json")
            assertEquals(after, refreshes())
        }
    }
}
