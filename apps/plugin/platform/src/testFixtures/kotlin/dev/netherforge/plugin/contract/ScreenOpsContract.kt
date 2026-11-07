package dev.netherforge.plugin.contract

import dev.netherforge.format.screen.ScreenFacing
import dev.netherforge.plugin.platform.EntityRole
import dev.netherforge.plugin.platform.EntityTag
import dev.netherforge.plugin.platform.ScreenOps
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [ScreenOps]: the frames screens hang in, their map items, the game's map font, and maps sent to a player. */
abstract class ScreenOpsContract : PlatformContract() {
    private val screens: ScreenOps get() = platform.screens

    @Test
    fun `a frame hangs with nothing behind it, tagged, and stays until it's removed`() {
        val tag = EntityTag(UUID.randomUUID(), "0,0", EntityRole.SCREEN)
        // In the air, well above anything: a fixed frame needs no wall.
        val at = at(2, 20)
        val frame = main { screens.hang(at, ScreenFacing.SOUTH, MAP, glowing = true, tag = tag) }
        assertNotNull(frame)
        afterwards { platform.entities.remove(frame) }
        main {
            assertTrue(platform.entities.isLoaded(frame))
            assertEquals(tag, platform.entities.loadedTagged()[frame])
        }
        // The game checks a hanging entity's wall every 100 ticks: a fixed one stays.
        ticks(110)
        main {
            assertTrue(platform.entities.isLoaded(frame))
            assertTrue(platform.entities.remove(frame))
            assertFalse(platform.entities.isLoaded(frame))
        }
    }

    @Test
    fun `a frame in a world that isn't there is null`() {
        val tag = EntityTag(UUID.randomUUID(), "0,0", EntityRole.SCREEN)
        assertNull(main { screens.hang(origin.copy(world = MISSING_WORLD), ScreenFacing.NORTH, MAP, false, tag) })
    }

    @Test
    fun `a map item shows the map it's for`() {
        val item = main { screens.mapItem(MAP) }
        assertEquals("minecraft:filled_map", item.def.kind)
        assertEquals(MAP, item.def.map)
    }

    @Test
    fun `the game's map font has its letters and digits`() {
        val font = main { screens.font() }
        assertTrue(font.height > 0)
        for (char in "Ab0") {
            val glyph = assertNotNull(font.glyphs[char.code], "$char")
            assertTrue(glyph.width > 0 && glyph.height > 0 && glyph.pixels.any { it }, "$char draws something")
        }
    }

    @Test
    fun `a map sent to someone who isn't online goes nowhere`() {
        assertFalse(main { screens.sendMap(UUID.randomUUID(), MAP, 0, 0, 1, 1, ByteArray(1)) })
    }

    private companion object {
        /** A map number the server never made: as screens' are. */
        const val MAP = 1_999_999_999
    }
}
