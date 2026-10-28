package dev.netherforge.plugin.paper.contract

import dev.netherforge.format.item.AttributeModifierDef
import dev.netherforge.format.item.AttributeOperation
import dev.netherforge.format.item.ItemDef
import dev.netherforge.format.item.TooltipComponent
import dev.netherforge.plugin.contract.PlatformContract
import dev.netherforge.plugin.platform.InventoryRef
import dev.netherforge.plugin.platform.ItemData
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.inventory.tooltip.TooltipContext
import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Paper only: an item's `canBreak` and `canPlaceOn` as the game's block predicates, which Paper's own API
 * can't write a block state into (the adapter parses them from the game's item syntax), and the tooltip lines
 * `hiddenTooltipComponents` hides, as the game draws them. The fake keeps items as their fields, so it has
 * nothing to say about either.
 */
class PaperAdventureItemsTest : PlatformContract() {
    override fun connect() = PaperContractServer.current

    /** The stack's components as the game writes them, without the spaces its SNBT puts after `:` and `,`. */
    private fun components(player: java.util.UUID, slot: Int): String =
        main { Bukkit.getPlayer(player)!!.inventory.getItem(slot)!!.itemMeta.asComponentString.filterNot { it.isWhitespace() } }

    @Test
    fun `a project block is its state, beside the game's blocks and tags and any block, and reads back as written`() {
        val player = join()
        val inventory = InventoryRef.Player(player.uuid)
        server.blockStates["ruby_ore"] = "minecraft:note_block[instrument=banjo,note=3,powered=true]"
        val written = ItemDef(
            kind = "minecraft:wooden_pickaxe",
            canBreak = listOf("ruby_ore", "#minecraft:logs", "minecraft:stone", "gone"),
            canPlaceOn = listOf("*")
        )
        main { assertTrue(platform.inventories.setItem(inventory, 0, ItemData(written))) }
        val text = components(player.uuid, 0)
        assertTrue("state:{instrument:\"banjo\",note:\"3\",powered:\"true\"}" in text, text)
        assertTrue("#minecraft:logs" in text && "minecraft:stone" in text, text)
        main {
            val back = platform.inventories.contents(inventory)!![0]!!.def
            assertEquals(written.canBreak, back.canBreak, "as written, a block with no state too")
            assertEquals(listOf("*"), back.canPlaceOn)
            assertEquals(0, platform.projectItems.refresh(inventory), "current")
        }

        // The project's blocks changed: the state moved, and the stack follows it once it's checked.
        server.blockStates["ruby_ore"] = "minecraft:note_block[instrument=banjo,note=4,powered=true]"
        main {
            assertEquals(1, platform.projectItems.refresh(inventory))
            assertEquals(0, platform.projectItems.refresh(inventory))
            assertEquals(written.canBreak, platform.inventories.contents(inventory)!![0]!!.def.canBreak)
        }
        val moved = components(player.uuid, 0)
        assertTrue("note:\"4\"" in moved && "note:\"3\"" !in moved, moved)
    }

    @Test
    fun `the lines an item's lists write can be hidden from its tooltip, and read back`() {
        val player = join()
        val inventory = InventoryRef.Player(player.uuid)
        val written = ItemDef(
            kind = "minecraft:wooden_pickaxe",
            canBreak = listOf("*"),
            canPlaceOn = listOf("*"),
            hiddenTooltipComponents = listOf(TooltipComponent("can_break"))
        )
        main { assertTrue(platform.inventories.setItem(inventory, 0, ItemData(written))) }
        val text = components(player.uuid, 0)
        val hidden = text.substringAfter("tooltip_display=").substringBefore("}")
        assertTrue("minecraft:can_break" in hidden && "can_place_on" !in hidden, text)
        main {
            val back = platform.inventories.contents(inventory)!![0]!!.def
            assertEquals(listOf(TooltipComponent("can_break")), back.hiddenTooltipComponents)
            assertEquals(listOf("*"), back.canPlaceOn, "the lists still apply")
        }
    }

    @Test
    fun `a hidden line leaves the tooltip while its component still applies`() {
        val player = join()
        val inventory = InventoryRef.Player(player.uuid)
        val tool = ItemDef(
            kind = "minecraft:wooden_pickaxe",
            unbreakable = true,
            attributeModifiers = listOf(
                AttributeModifierDef(
                    attribute = "minecraft:block_break_speed",
                    amount = 0.5,
                    operation = AttributeOperation.ADD_MULTIPLIED_BASE
                )
            )
        )
        fun tooltip(slot: Int): String = main {
            val stack = Bukkit.getPlayer(player.uuid)!!.inventory.getItem(slot)!!
            stack.computeTooltipLines(TooltipContext.create(false, false), null).joinToString("\n") {
                GsonComponentSerializer.gson().serialize(it)
            }
        }
        val hidden = tool.copy(hiddenTooltipComponents = listOf(TooltipComponent("unbreakable"), TooltipComponent("attribute_modifiers")))
        main {
            assertTrue(platform.inventories.setItem(inventory, 0, ItemData(tool)))
            assertTrue(platform.inventories.setItem(inventory, 1, ItemData(hidden)))
        }
        val shown = tooltip(0)
        assertTrue("item.unbreakable" in shown && "attribute.name.block_break_speed" in shown, shown)
        val text = tooltip(1)
        assertTrue("item.unbreakable" !in text && "attribute.name.block_break_speed" !in text, text)
        main {
            val stack = Bukkit.getPlayer(player.uuid)!!.inventory.getItem(1)!!
            assertTrue(stack.hasData(DataComponentTypes.UNBREAKABLE), "still unbreakable")
            assertEquals(1, stack.getData(DataComponentTypes.ATTRIBUTE_MODIFIERS)!!.modifiers().size, "still faster")
            val back = platform.inventories.contents(inventory)!![1]!!.def
            // Read back in the format's order, whatever order they were written in.
            assertEquals(listOf(TooltipComponent("attribute_modifiers"), TooltipComponent("unbreakable")), back.hiddenTooltipComponents)
            assertEquals(true, back.unbreakable)
            assertEquals(tool.attributeModifiers!!.single().attribute, back.attributeModifiers!!.single().attribute)
        }
    }

    @Test
    fun `every line an item can hide is a component this server hides, and reads back`() {
        val player = join()
        val inventory = InventoryRef.Player(player.uuid)
        val all = ItemDef(kind = "minecraft:paper", hiddenTooltipComponents = TooltipComponent.entries)
        main { assertTrue(platform.inventories.setItem(inventory, 0, ItemData(all))) }
        main {
            val components = RegistryAccess.registryAccess().getRegistry(RegistryKey.DATA_COMPONENT_TYPE)
            val here = TooltipComponent.entries.filter { components.get(NamespacedKey.fromString(it.key)!!) != null }
            // Every supported version has nearly all of them: a newer one may add lines, never drop many.
            assertTrue(here.size >= TooltipComponent.entries.size - 3, "$here")
            assertEquals(here, platform.inventories.contents(inventory)!![0]!!.def.hiddenTooltipComponents)
            // Showing every line again reads back as none hidden.
            assertTrue(platform.inventories.setItem(inventory, 0, ItemData(all.copy(hiddenTooltipComponents = emptyList()))))
            assertEquals(null, platform.inventories.contents(inventory)!![0]!!.def.hiddenTooltipComponents)
        }
    }
}
