package dev.netherforge.plugin.item
import dev.netherforge.format.item.ItemDef
import dev.netherforge.format.ref.ResourceRef
import dev.netherforge.plugin.LuaChecks
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.GameEvent
import dev.netherforge.plugin.platform.InventoryRef
import dev.netherforge.plugin.platform.ItemData
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** `properties` on items, blocks and centities, and an item's `render` event. */
class PropertiesTest {
    private val files = mapOf(
        "items/sword/item.json" to """
            {
              "kind": "minecraft:iron_sword",
              "name": "Copper Sword",
              "lore": ["A sword."],
              "properties": { "damage": 8, "class": "melee", "tags": ["blade", "copper"], "crit": { "chance": 0.04 } },
              "script": { "file": "script.lua" }
            }
        """,
        "items/sword/script.lua" to """
            local this = this --[[@as ProjectItem]]
            this:on("render", function(event)
              local data = event.item.data
              if data and data.prefix then
                event.display_name = data.prefix .. " " .. event.display_name
                event.lore = { event.lore[1], "<green>+" .. tostring(this:properties().damage) .. " damage" }
              end
            end)
        """,
        "items/plain/item.json" to """{ "kind": "minecraft:stick" }""",
        "blocks/log/block.json" to """{ "tool": "axe", "properties": { "burns": true } }""",
        "centities/slime/centity.json" to """{ "nodes": { "root": {} }, "properties": { "hp": 14, "drops": [{ "item": "gel" }] } }""",
        "centities/rock/centity.json" to """{ "nodes": { "root": {} } }"""
    )

    @Test
    fun `scripts read each resource's properties from its file`() {
        val result = LuaChecks.run(
            """
            local sword = nf.items.get("sword"):properties()
            check("a number", sword.damage, 8)
            check("a string", sword.class, "melee")
            check("a list", sword.tags[2], "copper")
            check("a table", sword.crit.chance, 0.04)
            check("a new table each call", sword == nf.items.get("sword"):properties(), false)
            check("none", next(nf.items.get("plain"):properties()), nil)
            check("a block's", nf.blocks.get("log"):properties().burns, true)
            local slime = nf.centities.spawn("slime", vec3(0, 64, 0))
            check("a centity's", slime:properties().hp, 14)
            check("its lists", slime:properties().drops[1].item, "gel")
            check("by kind", nf.centities.properties("slime").hp, 14)
            check("by kind, none", next(nf.centities.properties("rock")), nil)
            fails("a kind there's none of", function() nf.centities.properties("ghost") end, "ghost")
            slime:remove()
            check("removed", slime:properties(), nil)
            """,
            files
        )
        assertEquals(listOf("done"), result)
    }

    @Test
    fun `render names a stack from its data when it's made, and again when the file changes`() {
        val checks = LuaChecks.command(
            """
            local made = nf.items.create("sword", { data = { prefix = "Legendary" } })
            check("named", made.name, "Legendary Copper Sword")
            check("lore", made.lore[2], "<green>+8 damage")
            check("without a prefix, the file's", nf.items.create("sword").name, "Copper Sword")
            """
        )
        TestServer(files + ("modules/t/init.lua" to checks), start = false).use { server ->
            val alex = server.player("Alex")
            server.start()
            with(LuaChecks) { assertEquals(listOf("done"), server.runChecks()) }
            val slots = server.platform.inventories.slots(InventoryRef.Player(alex.ref.uuid))!!
            // A stack from before the file changed, carrying its own prefix: it takes the new look and is rendered again.
            slots[5] = ItemData(
                ItemDef(
                    kind = "minecraft:iron_sword",
                    item = ResourceRef("sword"),
                    name = "Old",
                    data = mapOf(
                        "prefix" to JsonPrimitive("Keen")
                    )
                ),
                look = "0000000000000000"
            )
            server.platform.raise.playerJoin(GameEvent.PlayerJoin(alex.ref, false, null))
            assertEquals("Keen Copper Sword", slots[5]!!.def.name)
            assertEquals(listOf("A sword.", "<green>+8 damage"), slots[5]!!.def.lore)
            server.write(
                "items/sword/item.json",
                files.getValue("items/sword/item.json").trimIndent().replace("Copper Sword", "Bronze Sword")
            )
            server.reload("items/sword/item.json")
            assertEquals("Keen Bronze Sword", slots[5]!!.def.name)
        }
    }
}
