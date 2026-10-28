package dev.netherforge.plugin.contract

import dev.netherforge.plugin.platform.InventoryOps
import dev.netherforge.plugin.platform.InventoryRef
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [InventoryOps]: real inventories, wherever they are. */
abstract class InventoryOpsContract : PlatformContract() {
    private val inventories: InventoryOps get() = platform.inventories

    @Test
    fun `a player's own inventory and ender chest`() {
        val player = join()
        main {
            val own = InventoryRef.Player(player.uuid)
            assertEquals("player", inventories.kind(own))
            assertEquals(43, inventories.size(own), "with body armour 41 and a saddle 42")
            assertEquals(List(43) { null }, inventories.contents(own))
            val chest = InventoryRef.EnderChest(player.uuid)
            assertEquals("ender_chest", inventories.kind(chest))
            assertEquals(27, inventories.size(chest))
            val offline = InventoryRef.Player(UUID.randomUUID())
            assertNull(inventories.kind(offline))
            assertNull(inventories.size(offline))
            assertNull(inventories.contents(offline))
            assertFalse(inventories.setItem(offline, 0, item("minecraft:diamond")))
            assertNull(inventories.add(offline, item("minecraft:diamond")))
            assertFalse(inventories.clear(offline))
        }
    }

    @Test
    fun `slots read back as set, items named without their namespace too`() {
        val player = join()
        main {
            val own = InventoryRef.Player(player.uuid)
            assertTrue(inventories.setItem(own, 3, item("diamond", 5)))
            val slot = inventories.contents(own)!![3]!!.def
            assertEquals("minecraft:diamond", slot.kind)
            assertEquals(5, slot.count)
            assertTrue(inventories.setItem(own, 3, null))
            assertNull(inventories.contents(own)!![3])
            assertFalse(inventories.setItem(own, 3, item("minecraft:nf_no_such_item")))
        }
    }

    @Test
    fun `adding tops up matching stacks, then fills empty slots, and says what didn't fit`() {
        val player = join()
        main {
            val chest = InventoryRef.EnderChest(player.uuid)
            assertTrue(inventories.setItem(chest, 2, item("minecraft:bread", 60)))
            assertEquals(0, inventories.add(chest, item("minecraft:bread", 70)))
            val contents = inventories.contents(chest)!!
            assertEquals(64, contents[2]?.def?.count, "topped up first")
            assertEquals(64, contents[0]?.def?.count, "then the first empty slot")
            assertEquals(2, contents[1]?.def?.count)
            for (slot in 0 until 27) inventories.setItem(chest, slot, item("minecraft:stick", 64))
            assertEquals(10, inventories.add(chest, item("minecraft:bread", 10)), "full")
            assertTrue(inventories.clear(chest))
            assertTrue(inventories.contents(chest)!!.all { it == null })
        }
    }

    @Test
    fun `a player's armour and off hand never take what's added`() {
        val player = join()
        main {
            val own = InventoryRef.Player(player.uuid)
            for (slot in 0 until 36) inventories.setItem(own, slot, item("minecraft:stick", 64))
            assertEquals(5, inventories.add(own, item("minecraft:bread", 5)))
            assertTrue(inventories.contents(own)!!.drop(36).all { it == null })
        }
    }

    @Test
    fun `a container block's inventory, and none for other blocks or unloaded chunks`() {
        val (cx, cz) = server.unloadedChunk()
        main {
            val chestAt = at(-6, 0, 6)
            val barrelAt = at(-7, 0, 6)
            place(chestAt, "minecraft:chest")
            place(barrelAt, "minecraft:barrel")
            val (x, y, z) = block(chestAt)
            val chest = InventoryRef.Block(world, x, y, z)
            assertEquals("chest", inventories.kind(chest))
            assertEquals(27, inventories.size(chest))
            assertTrue(inventories.setItem(chest, 0, item("minecraft:diamond")))
            assertEquals("minecraft:diamond", inventories.contents(chest)!![0]?.def?.kind)
            assertTrue(inventories.clear(chest))
            val (bx, by, bz) = block(barrelAt)
            assertEquals("barrel", inventories.kind(InventoryRef.Block(world, bx, by, bz)))
            assertNull(inventories.kind(InventoryRef.Block(world, x, y + 3, z)), "air")
            assertNull(inventories.kind(InventoryRef.Block(world, cx * 16, 70, cz * 16)), "unloaded")
            assertNull(inventories.kind(InventoryRef.Block(MISSING_WORLD, x, y, z)))
        }
    }

    @Test
    fun `an entity's inventory`() {
        val cart = spawn("minecraft:chest_minecart")
        val pig = spawn("minecraft:pig", at(5))
        main {
            val ref = InventoryRef.Entity(cart)
            assertEquals("chest", inventories.kind(ref))
            assertEquals(27, inventories.size(ref))
            assertTrue(inventories.setItem(ref, 1, item("minecraft:bread")))
            assertEquals("minecraft:bread", inventories.contents(ref)!![1]?.def?.kind)
            assertTrue(inventories.clear(ref))
            assertNull(inventories.kind(InventoryRef.Entity(pig)), "a pig carries nothing")
        }
    }

    @Test
    fun `opening one shows it to the player, who's then among its viewers`() {
        val player = join()
        main {
            val chest = InventoryRef.EnderChest(player.uuid)
            assertEquals(emptyList(), inventories.viewers(chest))
            assertTrue(inventories.open(chest, player.uuid))
            assertEquals(listOf(player.uuid), inventories.viewers(chest))
            assertTrue(platform.players.closeInventory(player.uuid))
            assertFalse(inventories.open(chest, UUID.randomUUID()))
        }
        eventually("the window closing") { inventories.viewers(InventoryRef.EnderChest(player.uuid)).isEmpty() }
    }
}
