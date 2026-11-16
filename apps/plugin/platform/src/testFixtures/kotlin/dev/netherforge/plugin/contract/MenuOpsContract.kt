package dev.netherforge.plugin.contract

import dev.netherforge.format.menu.MenuType
import dev.netherforge.plugin.platform.MenuOps
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.platform.WindowSpec
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [MenuOps]: windows named by the runtime's UUIDs, their contents, and who's looking. */
abstract class MenuOpsContract : PlatformContract() {
    private val menus: MenuOps get() = platform.menus

    private fun window(type: MenuType = MenuType.CHEST, size: Int = 9): UUID {
        val id = UUID.randomUUID()
        menus.create(id, WindowSpec(type, size, "<gold>Contract"))
        afterwards { menus.destroy(id) }
        return id
    }

    private fun closes(window: UUID, player: PlayerRef) = events.of("menuClosed").count { it == listOf(window, player) }

    @Test
    fun `slots read back as set`() {
        main {
            val window = window()
            assertNull(menus.item(window, 0))
            assertTrue(menus.setItem(window, 0, item("diamond", 2)))
            val slot = menus.item(window, 0)!!.def
            assertEquals("minecraft:diamond", slot.kind)
            assertEquals(2, slot.count)
            assertTrue(menus.setItem(window, 0, null))
            assertNull(menus.item(window, 0))
            assertFalse(menus.setItem(window, 9, item("minecraft:diamond")), "past the end")
            assertFalse(menus.setItem(window, 1, item("minecraft:nf_no_such_item")))
            assertFalse(menus.setItem(UUID.randomUUID(), 0, item("minecraft:diamond")), "no such window")
            val hopper = window(MenuType.HOPPER, 5)
            assertTrue(menus.setItem(hopper, 4, item("minecraft:bread")))
            assertFalse(menus.setItem(hopper, 5, item("minecraft:bread")))
        }
    }

    @Test
    fun `room is what a slot takes of an item, by the server's stacking`() {
        main {
            val window = window()
            assertEquals(64, menus.room(window, 0, item("minecraft:bread")), "an empty slot takes a whole stack")
            assertTrue(menus.setItem(window, 0, item("minecraft:bread", 60)))
            assertEquals(4, menus.room(window, 0, item("minecraft:bread", 9)), "what's left of the stack")
            assertEquals(0, menus.room(window, 0, item("minecraft:diamond")), "another item takes none")
            assertEquals(0, menus.room(window, 9, item("minecraft:bread")), "past the end")
            assertEquals(0, menus.room(UUID.randomUUID(), 0, item("minecraft:bread")), "no such window")
        }
    }

    @Test
    fun `adding tops up matching stacks first and says what didn't fit`() {
        main {
            val window = window()
            assertTrue(menus.setItem(window, 4, item("minecraft:bread", 60)))
            assertEquals(0, menus.add(window, item("minecraft:bread", 70)))
            assertEquals(64, menus.item(window, 4)?.def?.count)
            assertEquals(64, menus.item(window, 0)?.def?.count)
            assertEquals(2, menus.item(window, 1)?.def?.count)
            for (slot in 0 until 9) menus.setItem(window, slot, item("minecraft:stick", 64))
            assertEquals(3, menus.add(window, item("minecraft:bread", 3)))
            assertEquals(3, menus.add(UUID.randomUUID(), item("minecraft:bread", 3)), "no such window")
        }
    }

    @Test
    fun `a window opens for a player, who's then looking at it`() {
        val player = join()
        main {
            val window = window()
            assertEquals(emptyList(), menus.viewers(window))
            assertTrue(menus.open(window, player.uuid))
        }
        main {
            val window = menus.viewing(player.uuid)!!
            assertEquals(listOf(player.uuid), menus.viewers(window))
            assertFalse(menus.open(window, UUID.randomUUID()), "offline")
            assertFalse(menus.open(UUID.randomUUID(), player.uuid), "no such window")
        }
    }

    @Test
    fun `closing for a viewer is heard, and they're no longer looking`() {
        val player = join()
        val window = main { window().also { menus.open(it, player.uuid) } }
        main { menus.close(window, player.uuid) }
        eventually("the close") { menus.viewing(player.uuid) == null }
        assertEquals(1, closes(window, player))
        assertEquals(emptyList(), main { menus.viewers(window) })
    }

    @Test
    fun `closing for everyone, or whatever a player has open, is heard too`() {
        val player = join()
        val window = main { window().also { menus.open(it, player.uuid) } }
        main { menus.close(window, null) }
        eventually("the close") { menus.viewing(player.uuid) == null }
        assertEquals(1, closes(window, player))
        main { menus.open(window, player.uuid) }
        main { assertTrue(menus.closeAny(player.uuid)) }
        eventually("the second close") { menus.viewing(player.uuid) == null }
        assertEquals(2, closes(window, player))
        main { assertFalse(menus.closeAny(UUID.randomUUID())) }
    }

    @Test
    fun `opening another window closes the first, and that's heard`() {
        val player = join()
        val (first, second) = main { window() to window() }
        main { menus.open(first, player.uuid) }
        main { menus.open(second, player.uuid) }
        eventually("the second window") { menus.viewing(player.uuid) == second }
        assertEquals(1, closes(first, player))
        assertEquals(0, closes(second, player))
    }

    @Test
    fun `a player leaving with a window open closes it`() {
        val player = join()
        val window = main { window().also { menus.open(it, player.uuid) } }
        leave(player)
        assertEquals(1, closes(window, player))
        assertEquals(emptyList(), main { menus.viewers(window) })
    }

    @Test
    fun `retitling keeps viewers and contents, and isn't heard as a close`() {
        val player = join()
        val window = main {
            window().also {
                menus.setItem(it, 2, item("minecraft:diamond"))
                menus.open(it, player.uuid)
            }
        }
        main { menus.retitle(window, "<red>Renamed") }
        ticks(2)
        main {
            assertEquals(window, menus.viewing(player.uuid))
            assertEquals("minecraft:diamond", menus.item(window, 2)?.def?.kind)
            menus.refresh(window)
        }
        assertEquals(0, closes(window, player))
    }

    @Test
    fun `destroying a window closes it unheard`() {
        val player = join()
        val window = main { window().also { menus.open(it, player.uuid) } }
        main { menus.destroy(window) }
        eventually("the window going") { menus.viewing(player.uuid) == null }
        assertEquals(0, closes(window, player))
        main {
            assertNull(menus.item(window, 0))
            assertFalse(menus.setItem(window, 0, item("minecraft:diamond")))
        }
    }

    @Test
    fun `a viewer's cursor reads back as set`() {
        val player = join()
        val window = main { window().also { menus.open(it, player.uuid) } }
        main {
            assertNull(menus.cursor(window, player.uuid))
            assertTrue(menus.setCursor(window, player.uuid, item("minecraft:emerald", 4)))
            val held = menus.cursor(window, player.uuid)!!.def
            assertEquals("minecraft:emerald", held.kind)
            assertEquals(4, held.count)
            assertTrue(menus.setCursor(window, player.uuid, null))
            assertNull(menus.cursor(window, player.uuid))
            val other = window()
            assertFalse(menus.setCursor(other, player.uuid, item("minecraft:emerald")), "not looking at it")
            assertNull(menus.cursor(other, player.uuid))
        }
    }
}
