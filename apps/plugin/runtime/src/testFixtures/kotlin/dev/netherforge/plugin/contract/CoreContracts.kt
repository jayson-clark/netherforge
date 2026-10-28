package dev.netherforge.plugin.contract

import dev.netherforge.format.game.GameDataBundle
import dev.netherforge.format.game.RegistryKey
import dev.netherforge.format.game.has
import dev.netherforge.plugin.platform.PauseOps
import dev.netherforge.plugin.platform.PerformanceOps
import dev.netherforge.plugin.platform.Platform
import dev.netherforge.plugin.platform.TextOps
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [Platform]'s own: what the server is, its game facts, and its log. */
abstract class PlatformInfoContract : PlatformContract() {
    @Test
    fun `the server says its version and runs projects for it`() {
        main {
            val info = platform.info
            assertTrue(info.minecraftVersion.isNotBlank())
            assertTrue(info.supportedTargets.isNotEmpty(), "it runs some target")
            assertTrue(info.pluginVersion.isNotBlank())
        }
    }

    @Test
    fun `game facts come from the server`() {
        main {
            val game = platform.game
            assertNotNull(game.block("minecraft:stone"))
            assertNull(game.block("minecraft:nf_no_such_block"))
            // Ids are lookups in the server's registries, by registry name.
            assertEquals(true, game.has(RegistryKey.ITEM, "minecraft:diamond"))
            assertEquals(false, game.has(RegistryKey.ITEM, "minecraft:nf_no_such_item"))
            assertEquals(true, game.has(RegistryKey.SOUND_EVENT, "minecraft:ui.button.click"))
            assertEquals(false, game.has(RegistryKey.SOUND_EVENT, "minecraft:nf.no_such_sound"))
            assertNull(game.registry(RegistryKey("minecraft:nf_no_such_registry")))
            assertTrue("minecraft:oak_planks" in game.tag(RegistryKey.ITEM, "minecraft:planks").orEmpty())
            val bundle = platform.exportGameData()
            assertEquals(GameDataBundle.SCHEMA, bundle.schema)
            assertTrue("minecraft:stone" in bundle.blocks)
            assertEquals(true, bundle.has(RegistryKey.ITEM, "minecraft:diamond"))
        }
    }

    @Test
    fun `the log takes every level`() {
        main {
            platform.log.info("contract: info")
            platform.log.warn("contract: warn")
            platform.log.error("contract: error (expected)", IllegalStateException("expected"))
        }
    }
}

/** [TextOps]: MiniMessage as the adapter reads it. */
abstract class TextOpsContract : PlatformContract() {
    @Test
    fun `stripping leaves the words`() {
        main {
            assertEquals("Hello world", platform.text.strip("<red>Hello</red> <bold>world"))
            assertEquals("plain", platform.text.strip("plain"))
        }
    }

    @Test
    fun `escaped text has no tags left`() {
        main {
            val escaped = platform.text.escape("a <red>tag</red>")
            assertEquals("a \\<red>tag\\</red>", escaped)
            assertEquals(escaped, platform.text.strip(escaped), "nothing in it is a tag any more")
        }
    }
}

/**
 * [PauseOps]: a server held at a breakpoint. The watchdog's half can't be
 * seen from here (only a stall long enough to trip it would show it); what
 * its players see can.
 */
abstract class PauseOpsContract : PlatformContract() {
    @Test
    fun `holding shows every player why on their action bar`() {
        val player = join()
        main { platform.pause.hold("Paused at modules/shop/init.lua:12") }
        eventually("the action bar shown") { bots.state(player.name).actionBar == "Paused at modules/shop/init.lua:12" }
        // Held again, a second later: the same words, and the server is none the worse.
        main { platform.pause.hold("Paused at modules/shop/init.lua:13") }
        eventually("the action bar updated") { bots.state(player.name).actionBar == "Paused at modules/shop/init.lua:13" }
    }

    @Test
    fun `holding with nobody online shows nothing, even to who joins after`() {
        main { platform.pause.hold("Paused at modules/shop/init.lua:12") }
        val player = join()
        settled()
        assertNull(screen(player).actionBar, "held before they came")
    }
}

/** [PerformanceOps]: how the server keeps up. */
abstract class PerformanceOpsContract : PlatformContract() {
    @Test
    fun `ticks per second and tick time are sensible`() {
        main {
            val tps = platform.performance.ticksPerSecond()
            assertTrue(tps > 0 && tps <= 21, "ticks per second: $tps")
            assertTrue(platform.performance.tickMilliseconds() >= 0)
        }
    }

    /**
     * Only what any server running these suites must say: a tick here takes
     * far less than a second, and the averages stay sensible as ticks pass.
     * How close to 20 a loaded CI machine keeps up isn't the platform's promise.
     */
    @Test
    fun `tick time is an average of real ticks, well under a second here, and stays sensible as the server ticks`() {
        repeat(3) {
            ticks(5)
            main {
                val millis = platform.performance.tickMilliseconds()
                assertTrue(millis.isFinite() && millis >= 0 && millis < 1000, "tick time: $millis ms")
                val tps = platform.performance.ticksPerSecond()
                assertTrue(tps.isFinite() && tps > 0 && tps <= 21, "ticks per second: $tps")
            }
        }
    }
}
