package dev.netherforge.plugin.contract

import dev.netherforge.plugin.platform.EntityNumber
import dev.netherforge.plugin.platform.GameEvent
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.platform.PlayerOps
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [PlayerOps]: players online, what they're told and shown, and what they are. */
abstract class PlayerOpsContract : PlatformContract() {
    private val players: PlayerOps get() = platform.players
    private val offline = UUID.randomUUID()

    @Test
    fun `a player online is found by name in any case, or by UUID`() {
        val player = join()
        main {
            assertTrue(player in players.online())
            assertEquals(player, players.find(player.name.uppercase()))
            assertEquals(player, players.find(player.uuid.toString()))
            assertEquals(player, players.get(player.uuid))
            assertEquals(player, players.known(player.name))
            assertNull(players.find("nf_nobody"))
            assertNull(players.get(offline))
        }
    }

    @Test
    fun `someone who has left is known but not online`() {
        val player = join()
        leave(player)
        main {
            assertTrue(player !in players.online())
            assertNull(players.find(player.name))
            assertEquals(player, players.known(player.name))
            assertEquals(player, players.known(player.uuid.toString()))
        }
    }

    @Test
    fun `where a player stands and looks from`() {
        val player = join(at(1, 0, 1))
        main {
            val at = assertNotNull(players.location(player.uuid))
            assertEquals(world, at.world)
            assertEquals(block(at(1, 0, 1)), block(at))
            val eye = assertNotNull(players.eye(player.uuid))
            assertNear(at.y + 1.62, eye.y, 1e-3)
            assertNear(1.0, sqrt(eye.dx * eye.dx + eye.dy * eye.dy + eye.dz * eye.dz), 1e-6)
            assertNull(players.location(offline))
            assertNull(players.eye(offline))
        }
    }

    @Test
    fun `a teleport is heard first, and moves them`() {
        val player = join()
        val to = Location(world, origin.x + 3, origin.y, origin.z - 2, 90.0, 0.0)
        main { assertTrue(players.teleport(player.uuid, to)) }
        main {
            val now = assertNotNull(players.location(player.uuid))
            assertEquals(block(to), block(now))
            val heard = events.heard<GameEvent.PlayerTeleport>("playerTeleport").single()
            assertEquals(player, heard.player)
            assertEquals(block(to), block(heard.to))
            assertEquals("plugin", heard.cause)
            assertFalse(players.teleport(offline, to))
        }
    }

    @Test
    fun `a cancelled teleport leaves them where they were`() {
        val player = join()
        events.cancelling += "playerTeleport"
        main {
            val before = assertNotNull(players.location(player.uuid))
            assertFalse(players.teleport(player.uuid, Location(world, origin.x + 5, origin.y, origin.z)))
            assertEquals(block(before), block(players.location(player.uuid)!!))
        }
    }

    @Test
    fun `messages, titles and the action bar reach players online`() {
        val player = join()
        main {
            assertTrue(players.message(player.uuid, "<red>hello"))
            assertTrue(players.actionbar(player.uuid, "<gold>bar"))
            assertTrue(players.title(player.uuid, "<b>Title", "sub", 5, 20, 5))
            assertTrue(players.clearTitle(player.uuid))
            assertFalse(players.message(offline, "x"))
            assertFalse(players.actionbar(offline, "x"))
            assertFalse(players.title(offline, "x", "y", 1, 1, 1))
            assertFalse(players.clearTitle(offline))
            players.broadcast("<green>contract broadcast")
            players.messageConsole("<green>contract console line")
        }
    }

    @Test
    fun `a display name reads back as set`() {
        val player = join()
        main {
            assertEquals(player.name, players.displayName(player.uuid))
            assertTrue(players.setDisplayName(player.uuid, "<red>Red"))
            assertEquals("<red>Red", players.displayName(player.uuid))
            assertNull(players.displayName(offline))
            assertFalse(players.setDisplayName(offline, "x"))
        }
    }

    @Test
    fun `a new player has no permissions and isn't an operator`() {
        val player = join()
        main {
            assertFalse(players.hasPermission(player.uuid, "nf.contract.node"))
            assertFalse(players.isOperator(player.uuid))
            assertFalse(players.hasPermission(offline, "nf.contract.node"))
        }
    }

    @Test
    fun `game mode reads back as set`() {
        val player = join()
        main {
            assertEquals("survival", players.gameMode(player.uuid))
            assertTrue(players.setGameMode(player.uuid, "creative"))
            assertEquals("creative", players.gameMode(player.uuid))
            assertNull(players.gameMode(offline))
            assertFalse(players.setGameMode(offline, "creative"))
        }
        val change = events.heard<GameEvent.PlayerChangeGameMode>("playerChangeGameMode").single()
        assertEquals(Triple(player, "creative", "plugin"), Triple(change.player, change.gameMode, change.cause))
    }

    @Test
    fun `a cancelled game mode change leaves it`() {
        val player = join()
        events.cancelling += "playerChangeGameMode"
        main {
            players.setGameMode(player.uuid, "adventure")
            assertEquals("survival", players.gameMode(player.uuid))
        }
        assertEquals(1, events.heard<GameEvent.PlayerChangeGameMode>("playerChangeGameMode").size)
    }

    @Test
    fun `experience fills the bar and raises the level as orbs do`() {
        val player = join()
        main {
            val numbers = platform.worldEntities
            assertTrue(players.giveExperience(player.uuid, 3))
            assertEquals(0.0, numbers.number(player.uuid, EntityNumber.LEVEL))
            assertNear(3.0 / 7.0, numbers.number(player.uuid, EntityNumber.EXPERIENCE_PROGRESS), 1e-4)
            assertTrue(players.giveExperience(player.uuid, 4 + 9 + 2))
            assertEquals(2.0, numbers.number(player.uuid, EntityNumber.LEVEL), "7 points to level 1, 9 to level 2")
            assertNear(2.0 / 11.0, numbers.number(player.uuid, EntityNumber.EXPERIENCE_PROGRESS), 1e-4)
            assertFalse(players.giveExperience(offline, 1))
        }
        // The server notices the new level on the player's next tick.
        eventually("the new level heard") { events.heard<GameEvent.PlayerChangeLevel>("playerChangeLevel").isNotEmpty() }
        val level = events.heard<GameEvent.PlayerChangeLevel>("playerChangeLevel").single()
        assertEquals(Triple(player, 0, 2), Triple(level.player, level.from, level.to))
    }

    @Test
    fun `cooldowns read back as set`() {
        val player = join()
        main {
            assertEquals(0, players.cooldown(player.uuid, "minecraft:ender_pearl"))
            assertTrue(players.setCooldown(player.uuid, "minecraft:ender_pearl", 40))
            assertEquals(40, players.cooldown(player.uuid, "minecraft:ender_pearl"))
            assertEquals(0, players.cooldown(player.uuid, "minecraft:snowball"))
            assertNull(players.cooldown(offline, "minecraft:ender_pearl"))
            assertFalse(players.setCooldown(offline, "minecraft:ender_pearl", 1))
        }
    }

    @Test
    fun `the player list's header and footer read back as set`() {
        val player = join()
        main {
            assertNull(players.tabText(player.uuid, footer = false))
            assertNull(players.tabText(player.uuid, footer = true))
            assertTrue(players.setTabText(player.uuid, footer = false, "<gold>Head"))
            assertTrue(players.setTabText(player.uuid, footer = true, "Foot"))
            assertEquals("<gold>Head", players.tabText(player.uuid, footer = false))
            assertEquals("Foot", players.tabText(player.uuid, footer = true))
            assertNull(players.tabText(offline, footer = false))
            assertFalse(players.setTabText(offline, footer = false, "x"))
        }
    }

    @Test
    fun `a locale, and closing whatever they have open`() {
        val player = join()
        main {
            assertEquals("en_us", players.locale(player.uuid))
            assertTrue(players.closeInventory(player.uuid))
            assertNull(players.locale(offline))
            assertFalse(players.closeInventory(offline))
        }
    }

    @Test
    fun `what's on the cursor reads back as set`() {
        val player = join()
        main {
            assertNull(players.cursor(player.uuid))
            assertTrue(players.setCursor(player.uuid, item("minecraft:stick", 3)))
            val carried = players.cursor(player.uuid)
            assertEquals("minecraft:stick" to 3, carried?.def?.kind to carried?.def?.count)
            assertTrue(players.setCursor(player.uuid, null))
            assertNull(players.cursor(player.uuid))
            assertNull(players.cursor(offline))
            assertFalse(players.setCursor(offline, item("minecraft:stick")))
        }
    }

    @Test
    fun `a kicked player leaves, and the server hears them quit`() {
        val player = join()
        main { assertTrue(players.kick(player.uuid, "<red>Bye")) }
        eventually("${player.name} leaving") { players.get(player.uuid) == null }
        val kick = events.heard<GameEvent.PlayerKick>("playerKick").single()
        assertEquals(player to "plugin", kick.player to kick.cause)
        assertTrue("Bye" in kick.text, kick.text)
        assertEquals(player, events.heard<GameEvent.PlayerQuit>("playerQuit").single().player)
        main { assertFalse(players.kick(offline, null)) }
    }

    @Test
    fun `a cancelled kick keeps them online`() {
        val player = join()
        events.cancelling += "playerKick"
        main { players.kick(player.uuid, "<red>Bye") }
        ticks(2)
        main { assertEquals(player, players.get(player.uuid)) }
        assertEquals(emptyList(), events.heard<GameEvent.PlayerQuit>("playerQuit"))
    }
}
