package dev.netherforge.plugin.paper.contract

import dev.netherforge.format.item.ItemDef
import dev.netherforge.format.menu.MenuType
import dev.netherforge.plugin.contract.PlatformContract
import dev.netherforge.plugin.platform.InventoryRef
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.WindowSpec
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Paper only: a player's storage (`player:storage`) is in their persistent data, which the server saves with their
 * inventory; and what changes in a window's slots reaches the runtime through the game's container listener. The fake
 * keeps storages on its player and is told of changes by the test.
 */
class PaperStorageTest : PlatformContract() {
    override fun connect() = PaperContractServer.current

    private fun diamonds(count: Int) = ItemData(ItemDef(kind = "minecraft:diamond", count = count))

    @Test
    fun `a storage is kept in the player's persistent data, a slot past its size too`() {
        val player = join()
        val small = InventoryRef.Storage(player.uuid, "contract:bag", 2)
        val big = small.copy(size = 4)
        main {
            assertEquals("storage", platform.inventories.kind(small))
            assertTrue(platform.inventories.setItem(big, 3, diamonds(5)))
            assertEquals(0, platform.inventories.add(small, diamonds(70)))
            assertEquals(listOf(64, 6), platform.inventories.contents(small)!!.map { it?.def?.count })
            assertTrue(platform.inventories.clear(small))
            assertEquals(listOf(null, null, null, 5), platform.inventories.contents(big)!!.map { it?.def?.count })
            val data = Bukkit.getPlayer(player.uuid)!!.persistentDataContainer
            assertTrue(data.has(NamespacedKey("netherforge", "storage")), "in the player's own data")
            assertNull(platform.inventories.contents(InventoryRef.Storage(UUID.randomUUID(), "contract:bag", 2)), "offline")
        }
    }

    @Test
    fun `what changes in an open window's slots is heard`() {
        val player = join()
        val window = UUID.randomUUID()
        main {
            platform.menus.create(window, WindowSpec(MenuType.CHEST, 9, "Contract"))
            assertTrue(platform.menus.open(window, player.uuid))
        }
        eventually("the window open") { main { platform.menus.viewing(player.uuid) } == window }
        main { assertTrue(platform.menus.setItem(window, 4, diamonds(1))) }
        eventually("the change heard") { events.of("menuChanged").any { it == listOf(window, setOf(4)) } }
        assertNotNull(main { platform.menus.item(window, 4) })
        main { platform.menus.destroy(window) }
    }
}
