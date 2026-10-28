package dev.netherforge.plugin.contract

import dev.netherforge.format.bridge.BotBossBar
import dev.netherforge.format.bridge.BotEvent
import dev.netherforge.format.bridge.BotSidebar
import dev.netherforge.format.bridge.BotState
import dev.netherforge.plugin.platform.BossBarLook
import dev.netherforge.plugin.platform.BossBarOps
import dev.netherforge.plugin.platform.PlayerListOps
import dev.netherforge.plugin.platform.SidebarOps
import dev.netherforge.plugin.platform.TeamLook
import dev.netherforge.plugin.platform.TeamOps
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [BossBarOps]: bars by the runtime's ids, shown to players online. */
abstract class BossBarOpsContract : PlatformContract() {
    private val bars: BossBarOps get() = platform.bossBars

    @Test
    fun `a bar shows to players online, and only once it exists`() {
        val player = join()
        main {
            val id = 9001
            afterwards { bars.remove(id) }
            assertFalse(bars.show(id, player.uuid), "no such bar yet")
            bars.create(id, BossBarLook("<red>Boss", 0.5, "red", "notched_6"))
            assertTrue(bars.show(id, player.uuid))
            assertFalse(bars.show(id, UUID.randomUUID()))
            bars.update(id, BossBarLook("<blue>Boss", 1.0, "blue", "progress"))
            bars.hide(id, player.uuid)
            bars.hide(id, UUID.randomUUID())
            bars.remove(id)
            assertFalse(bars.show(id, player.uuid), "removed")
            bars.update(id, BossBarLook("gone", 0.0, "white", "progress"))
            bars.remove(id)
        }
    }

    @Test
    fun `a player sees the bar shown to them as it looks, and it goes when hidden`() {
        val player = join()
        val since = mark(player)
        main {
            afterwards { bars.remove(BAR) }
            bars.create(BAR, BossBarLook("<red>Boss", 0.5, "red", "notched_6"))
            assertTrue(bars.show(BAR, player.uuid))
        }
        awaitScreen(player, "the bar") { it.bossBars == listOf(BotBossBar("Boss", 0.5, "red", "notched_6")) }
        main { bars.update(BAR, BossBarLook("<blue>Boss <b>two", 1.0, "blue", "progress")) }
        awaitScreen(player, "the bar changed") { it.bossBars == listOf(BotBossBar("Boss two", 1.0, "blue", "progress")) }
        main { bars.hide(BAR, player.uuid) }
        awaitScreen(player, "no bar") { it.bossBars.isEmpty() }
        val shown = sentSince(player, since).filterIsInstance<BotEvent.BossBar>()
        assertEquals(listOf("Boss" to true, "Boss two" to false), shown.map { it.name to it.shown })
    }

    @Test
    fun `a bar shows only to those it's shown to, and goes from every screen when removed`() {
        val shown = join()
        val other = join(at(2))
        main {
            afterwards { bars.remove(BAR) }
            bars.create(BAR, BossBarLook("Only you", 0.25, "green", "notched_10"))
            assertTrue(bars.show(BAR, shown.uuid))
        }
        awaitScreen(shown, "the bar") { it.bossBars.map(BotBossBar::name) == listOf("Only you") }
        settled()
        assertEquals(emptyList(), screen(other).bossBars, "not shown to them")
        main { assertTrue(bars.show(BAR, other.uuid)) }
        awaitScreen(other, "the bar") { it.bossBars.map(BotBossBar::name) == listOf("Only you") }
        main { bars.remove(BAR) }
        awaitScreen(shown, "no bar") { it.bossBars.isEmpty() }
        awaitScreen(other, "no bar") { it.bossBars.isEmpty() }
    }

    private companion object {
        const val BAR = 9002
    }
}

/** [SidebarOps]: one sidebar per player online. */
abstract class SidebarOpsContract : PlatformContract() {
    private val sidebars: SidebarOps get() = platform.sidebars

    @Test
    fun `a sidebar shows and hides for players online`() {
        val player = join()
        val offline = UUID.randomUUID()
        main {
            afterwards { sidebars.hide(player.uuid) }
            assertTrue(sidebars.show(player.uuid, "<gold>Title", listOf("one", "<red>two")))
            assertTrue(sidebars.show(player.uuid, "<gold>Title", (1..15).map { "line $it" }), "updated")
            assertTrue(sidebars.hide(player.uuid))
            assertFalse(sidebars.show(offline, "x", emptyList()))
            assertFalse(sidebars.hide(offline))
        }
    }

    @Test
    fun `a player sees their sidebar's title and lines, top first, as text, and nobody else's`() {
        val player = join()
        val other = join(at(2))
        main {
            afterwards { sidebars.hide(player.uuid) }
            assertTrue(sidebars.show(player.uuid, "<gold>Title", listOf("one", "<red>two", "<b>three</b>")))
        }
        awaitScreen(player, "the sidebar") { it.sidebar == BotSidebar("Title", listOf("one", "two", "three")) }
        val fifteen = (1..15).map { "line $it" }
        main { assertTrue(sidebars.show(player.uuid, "<gold>Longer", fifteen)) }
        awaitScreen(player, "the sidebar changed") { it.sidebar == BotSidebar("Longer", fifteen) }
        settled()
        assertNull(screen(other).sidebar, "theirs is their own")
        main { assertTrue(sidebars.hide(player.uuid)) }
        awaitScreen(player, "no sidebar") { it.sidebar == null }
    }
}

/** [TeamOps]: the main scoreboard's teams and the line under name tags. */
abstract class TeamOpsContract : PlatformContract() {
    private val teams: TeamOps get() = platform.teams
    private val red = "nf.contract_red"
    private val blue = "nf.contract_blue"

    private fun made(name: String, look: TeamLook = TeamLook(name)) {
        assertTrue(teams.create(name, look))
        afterwards { teams.remove(name) }
    }

    @Test
    fun `a team is made once, listed, changed and taken away`() {
        main {
            made(red, TeamLook("<red>Red", prefix = "[R] ", color = "red", friendlyFire = false, nametags = "hide_for_other_teams"))
            assertFalse(teams.create(red, TeamLook("again")), "already there")
            assertTrue(red in teams.names())
            assertEquals(emptySet(), teams.entries(red))
            assertTrue(teams.update(red, TeamLook("<red>Reds", collision = "never")))
            assertTrue(teams.remove(red))
            assertFalse(red in teams.names())
            assertFalse(teams.remove(red))
            assertFalse(teams.update(red, TeamLook("x")))
            assertNull(teams.entries(red))
        }
    }

    @Test
    fun `an entry is in one team at a time`() {
        val pig = UUID.randomUUID().toString()
        main {
            made(red)
            made(blue)
            assertTrue(teams.addEntry(red, "Alex"))
            assertTrue(teams.addEntry(red, pig))
            assertEquals(setOf("Alex", pig), teams.entries(red))
            assertEquals(red, teams.teamOf("Alex"))
            assertTrue(teams.addEntry(blue, "Alex"))
            assertEquals(blue, teams.teamOf("Alex"))
            assertEquals(setOf(pig), teams.entries(red), "moved out")
            assertTrue(teams.removeEntry(blue, "Alex"))
            assertFalse(teams.removeEntry(blue, "Alex"))
            assertNull(teams.teamOf("Alex"))
            assertFalse(teams.addEntry("nf.contract_none", "Alex"))
            assertFalse(teams.removeEntry("nf.contract_none", "Alex"))
        }
    }

    @Test
    fun `lines under name tags come and go, as players see them`() {
        val player = join()
        val viewer = join(at(2))
        fun line(state: BotState) = state.playerList.firstOrNull { it.name == player.name }?.belowName
        main {
            afterwards { teams.clearBelowNames() }
            teams.setBelowName(player.name, "<red>10 hp")
            // Anyone's, online or not.
            teams.setBelowName("Alex", "<blue>away")
        }
        awaitScreen(viewer, "the line under ${player.name}'s name") { line(it) == "10 hp" }
        main { teams.setBelowName(player.name, "<green>20 hp") }
        awaitScreen(viewer, "the line changed") { line(it) == "20 hp" }
        main { teams.setBelowName(player.name, null) }
        awaitScreen(viewer, "no line") { line(it) == null }
        main {
            teams.setBelowName(player.name, "back")
            teams.clearBelowNames()
            teams.setBelowName("Alex", null)
        }
        settled()
        assertNull(line(screen(viewer)), "cleared")
    }
}

/** [PlayerListOps]: each player's entry in the player list, and whose list they're in. */
abstract class PlayerListOpsContract : PlatformContract() {
    private val list: PlayerListOps get() = platform.playerList

    @Test
    fun `an entry's name and place read back as set`() {
        val player = join()
        val offline = UUID.randomUUID()
        main {
            assertEquals(player.name, list.name(player.uuid))
            assertTrue(list.setName(player.uuid, "<gold>Gold"))
            assertEquals("<gold>Gold", list.name(player.uuid))
            assertTrue(list.setName(player.uuid, null))
            assertEquals(player.name, list.name(player.uuid), "back to their name")
            assertEquals(0, list.order(player.uuid))
            assertTrue(list.setOrder(player.uuid, 5))
            assertEquals(5, list.order(player.uuid))
            assertNull(list.name(offline))
            assertFalse(list.setName(offline, "x"))
            assertNull(list.order(offline))
            assertFalse(list.setOrder(offline, 1))
        }
    }

    @Test
    fun `an entry's name and place are what other players' lists show`() {
        val player = join()
        val viewer = join(at(2))
        fun entry(state: BotState) = state.playerList.firstOrNull { it.name == player.name }
        main {
            assertTrue(list.setName(player.uuid, "<gold>Gold <b>star"))
            assertTrue(list.setOrder(player.uuid, 5))
        }
        awaitScreen(viewer, "the entry's name and place") { entry(it)?.displayName == "Gold star" && entry(it)?.order == 5 }
        main { assertTrue(list.setName(player.uuid, null)) }
        awaitScreen(viewer, "the entry's own name") { entry(it)?.displayName == null }
    }

    @Test
    fun `one player can be out of another's list`() {
        val viewer = join()
        val other = join(at(2))
        main {
            assertEquals(true, list.isListed(viewer.uuid, other.uuid))
            assertTrue(list.setListed(viewer.uuid, other.uuid, false))
            assertEquals(false, list.isListed(viewer.uuid, other.uuid))
            assertEquals(true, list.isListed(other.uuid, viewer.uuid), "only that way")
            assertTrue(list.setListed(viewer.uuid, other.uuid, true))
            assertEquals(true, list.isListed(viewer.uuid, other.uuid))
            assertNull(list.isListed(viewer.uuid, UUID.randomUUID()))
            assertFalse(list.setListed(viewer.uuid, UUID.randomUUID(), false))
        }
    }

    @Test
    fun `a player out of someone's list is unlisted on their screen only`() {
        val viewer = join()
        val other = join(at(2))
        fun listed(state: BotState, name: String) = state.playerList.firstOrNull { it.name == name }?.listed
        main { assertTrue(list.setListed(viewer.uuid, other.uuid, false)) }
        awaitScreen(viewer, "${other.name} unlisted") { listed(it, other.name) == false }
        assertEquals(true, listed(screen(other), viewer.name), "only that way")
        main { assertTrue(list.setListed(viewer.uuid, other.uuid, true)) }
        awaitScreen(viewer, "${other.name} listed again") { listed(it, other.name) == true }
    }
}
