package dev.netherforge.plugin.paper.contract

import dev.netherforge.format.item.HoldDef
import dev.netherforge.format.item.ItemDef
import dev.netherforge.format.item.UseAnimation
import dev.netherforge.format.item.UseEffectsDef
import dev.netherforge.plugin.contract.PlatformContract
import dev.netherforge.plugin.platform.InventoryRef
import dev.netherforge.plugin.platform.ItemData
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation
import org.bukkit.Bukkit
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Paper only: a `hold` item as the game's `consumable` component, posed by its animation. The fake keeps items as
 * their fields, so it can't say which component a hold compiles to, or which animations this server has.
 */
class PaperHoldItemsTest : PlatformContract() {
    override fun connect() = PaperContractServer.current

    @Test
    fun `the format's animations are this server's, in its order`() {
        // Paper's enum mirrors the game's ItemUseAnimation (its serialized names, lower case).
        assertEquals(UseAnimation.ALL, ItemUseAnimation.entries.map { it.name.lowercase() })
    }

    @Test
    fun `each animation is the consumable's, with a hold's use effects, and reads back in the shape it was written`() {
        val player = join()
        val inventory = InventoryRef.Player(player.uuid)
        for (animation in UseAnimation.entries) {
            val written = ItemDef(kind = "minecraft:stick", hold = HoldDef(animation = animation))
            main { assertTrue(platform.inventories.setItem(inventory, 0, ItemData(written))) }
            main {
                val stack = Bukkit.getPlayer(player.uuid)!!.inventory.getItem(0)!!
                val consumable = stack.getData(DataComponentTypes.CONSUMABLE)!!
                assertEquals(animation.id, consumable.animation().name.lowercase())
                val effects = stack.getData(DataComponentTypes.USE_EFFECTS)!!
                assertEquals(UseEffectsDef.HOLD.speedMultiplier, effects.speedMultiplier().toDouble(), "$animation")
                assertEquals(UseEffectsDef.HOLD.canSprint, effects.canSprint(), "$animation")
                assertEquals(UseEffectsDef.HOLD.interactVibrations, effects.interactVibrations(), "$animation")
                val back = platform.inventories.contents(inventory)!![0]!!.def
                // `none` is a plain hold: `true`.
                assertEquals(HoldDef(animation = animation.takeIf { it != UseAnimation.NONE }), back.hold, "$animation")
                assertEquals(null, back.useEffects, "$animation: a hold's own")
            }
        }
    }

    @Test
    fun `true is still no animation, and use effects still override a posed hold's`() {
        val player = join()
        val inventory = InventoryRef.Player(player.uuid)
        main {
            assertTrue(platform.inventories.setItem(inventory, 0, ItemData(ItemDef(kind = "minecraft:stick", hold = HoldDef()))))
            val stack = Bukkit.getPlayer(player.uuid)!!.inventory.getItem(0)!!
            assertEquals(ItemUseAnimation.NONE, stack.getData(DataComponentTypes.CONSUMABLE)!!.animation())
            assertEquals(HoldDef(), platform.inventories.contents(inventory)!![0]!!.def.hold)

            val slowed =
                ItemDef(
                    kind = "minecraft:stick",
                    hold = HoldDef(animation = UseAnimation("bow")),
                    useEffects = UseEffectsDef(canSprint = false)
                )
            assertTrue(platform.inventories.setItem(inventory, 1, ItemData(slowed)))
            val effects = Bukkit.getPlayer(player.uuid)!!.inventory.getItem(1)!!.getData(DataComponentTypes.USE_EFFECTS)!!
            assertEquals(false, effects.canSprint())
            assertEquals(1f, effects.speedMultiplier())
            assertEquals(UseEffectsDef(canSprint = false), platform.inventories.contents(inventory)!![1]!!.def.useEffects)
        }
    }
}
