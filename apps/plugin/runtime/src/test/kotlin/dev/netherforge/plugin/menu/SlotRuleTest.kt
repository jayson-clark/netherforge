package dev.netherforge.plugin.menu
import dev.netherforge.format.item.EquipSlot
import dev.netherforge.format.item.EquipmentDef
import dev.netherforge.format.item.ItemDef
import dev.netherforge.format.ref.ResourceRef
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.ItemData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Menu slots with a rule (`slot:set_rule`) and slots that show what a player wears (`slot:bind_equipment`). */
class SlotRuleTest {
    private val module = """
        nf.commands.register("gear", function(event)
          local player = event.player
          local bag = assert(player:storage("rings", 2))
          local menu = player:open_menu("gear")
          local ring = menu:slot(0)
          ring:bind(bag, 0)
          ring:set_rule({ property = "accessory", max = 1 })
          local helmet = menu:slot(1)
          assert(helmet:bind_equipment(player, "head"))
          helmet:set_rule({ wearable = "head", max = 1 })
          local gems = menu:slot(2)
          gems:bind(bag, 1)
          gems:set_rule({ kinds = { "minecraft:diamond", "#minecraft:planks" }, items = { "ruby" } })
          menu:shift_into(bag, 0, 1)
          log("rule " .. tostring(ring:rule().max) .. " " .. tostring(ring:rule().property))
          log("accepts " .. tostring(ring:accepts(nf.items.create("band"))) .. " " .. tostring(ring:accepts({ kind = "minecraft:emerald" })))
          log("gems " .. tostring(gems:accepts({ kind = "minecraft:diamond" })) .. " " .. tostring(gems:accepts(nf.items.create("ruby")))
            .. " " .. tostring(gems:accepts(nf.items.create("band"))))
          log("binding " .. helmet:binding().equipment .. " " .. tostring(helmet:binding().storage))
          log("bad " .. tostring(pcall(function() helmet:set_rule({ wearable = "hat" }) end))
            .. " " .. tostring(pcall(function() helmet:bind_equipment(player, "main_hand") end))
            .. " " .. tostring(pcall(function() ring:set_rule({ items = { "nope" } }) end)))
          ring:set_rule()
          log("cleared " .. tostring(ring:rule()) .. " " .. tostring(ring:accepts({ kind = "minecraft:emerald" })))
          ring:set_rule({ property = "accessory", max = 1 })
        end)
    """.trimIndent()

    private fun server() = TestServer(
        mapOf(
            "modules/m/init.lua" to module,
            "menus/gear/menu.json" to """{ "rows": 1, "locked": true }""",
            "items/band/item.json" to """{ "kind": "minecraft:paper", "properties": { "accessory": true } }""",
            "items/ruby/item.json" to """{ "kind": "minecraft:paper" }""",
            "items/cap/item.json" to
                """{ "kind": "minecraft:paper", "equipment": { "asset": "ui/cap", "slot": "head" } }"""
        )
    )

    private fun stack(kind: String, count: Int = 1) = ItemData(ItemDef(kind = kind, count = count))

    private fun project(id: String, kind: String, count: Int = 1) = ItemData(ItemDef(kind = kind, item = ResourceRef(id), count = count))

    @Test
    fun `a rule says what a slot takes, and accepts asks it`() {
        server().use { server ->
            val alex = server.player("Alex")
            server.platform.commands.run(alex, "gear")
            assertEquals(emptyList(), server.errors.map { it.message })
            assertEquals(
                listOf(
                    "rule 1 accessory",
                    "accepts true false",
                    "gems true true false",
                    "binding head nil",
                    "bad false false false",
                    "cleared nil true"
                ),
                server.logs
            )
            assertEquals(emptyList(), server.errors.map { it.message })
        }
    }

    @Test
    fun `clicks, drags and shift-clicks are held to it, and only max goes in`() {
        server().use { server ->
            val alex = server.player("Alex")
            server.platform.commands.run(alex, "gear")
            val menus = server.platform.menus
            val window = menus.of(alex)!!
            val cursors = window.cursors

            // Stone isn't an accessory: refused, and the game's move stopped.
            cursors[alex.ref.uuid] = stack("minecraft:emerald", 5)
            assertTrue(menus.click(alex, 0))
            assertNull(window.slots[0])

            // Three bands, but a ring slot holds one: one goes in, two stay on the cursor, and the storage has it.
            val band = project("band", "minecraft:paper", 3)
            cursors[alex.ref.uuid] = band
            assertTrue(menus.click(alex, 0))
            assertEquals(1, window.slots[0]?.def?.count)
            assertEquals(2, cursors[alex.ref.uuid]?.def?.count)
            assertEquals(1, alex.storages.getValue("test:rings")[0]?.def?.count)
            // One band, right-clicked into an empty gem slot it doesn't fit: refused.
            assertTrue(menus.click(alex, 2, click = "right"))

            // A drag over a slot that doesn't take the cursor, or more than its max, is refused.
            assertTrue(menus.drag(alex, listOf(0, 2), stack("minecraft:emerald")))
            assertTrue(menus.drag(alex, listOf(0), project("band", "minecraft:paper", 2)))

            // A number key brings the hotbar's stack: diamonds into the gem slot, not into the ring slot.
            alex.inventorySlots[3] = stack("minecraft:diamond", 4)
            cursors.remove(alex.ref.uuid)
            assertFalse(menus.click(alex, 2, click = "number_key", hotbar = 3))
            assertTrue(menus.click(alex, 1, click = "number_key", hotbar = 3))

            // A shift-click from below skips the slot the stack doesn't fit: the planks (by their tag) go to slot 2.
            alex.inventorySlots[5] = stack("minecraft:oak_planks", 6)
            assertTrue(menus.click(alex, 5, top = false, click = "shift_left"))
            assertEquals(6, window.slots[2]?.def?.count)
            assertNull(alex.inventorySlots[5])
            assertEquals(emptyList(), server.errors.map { it.message })
        }
    }

    @Test
    fun `a slot shows what a player wears, and puts on what goes in`() {
        server().use { server ->
            val alex = server.player("Alex")
            val wearing = { server.platform.worldEntities.equipment(alex.ref.uuid, "head") }
            server.platform.worldEntities.setEquipment(alex.ref.uuid, "head", stack("minecraft:iron_helmet"))
            server.platform.commands.run(alex, "gear")
            val menus = server.platform.menus
            val window = menus.of(alex)!!
            assertEquals("minecraft:iron_helmet", window.slots[1]?.def?.kind)

            // The game takes it out: they're bareheaded at once.
            menus.moved(alex, mapOf(1 to null))
            assertNull(wearing())
            // A cap (a project item worn on the head) goes in by hand, and is worn.
            val cap =
                ItemData(
                    ItemDef(
                        kind = "minecraft:paper",
                        item = ResourceRef("cap"),
                        equipment = EquipmentDef(ResourceRef("ui/cap"), EquipSlot.HEAD)
                    )
                )
            window.cursors[alex.ref.uuid] = cap
            assertFalse(menus.click(alex, 1))
            menus.moved(alex, mapOf(1 to cap))
            assertEquals("minecraft:paper", wearing()?.def?.kind)
            // Diamonds aren't worn on the head; the game's helmets are, by their tag.
            window.cursors[alex.ref.uuid] = stack("minecraft:diamond")
            assertTrue(menus.click(alex, 1))
            window.cursors[alex.ref.uuid] = stack("minecraft:iron_helmet")
            assertFalse(menus.click(alex, 1))

            // Something else puts a helmet on them: the slot shows it by the next tick.
            server.platform.worldEntities.setEquipment(alex.ref.uuid, "head", stack("minecraft:golden_helmet"))
            server.tick(1)
            assertEquals("minecraft:golden_helmet", window.slots[1]?.def?.kind)
            assertEquals(emptyList(), server.errors.map { it.message })
        }
    }
}
