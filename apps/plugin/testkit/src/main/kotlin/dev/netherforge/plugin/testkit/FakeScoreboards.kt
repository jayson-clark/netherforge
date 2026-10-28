package dev.netherforge.plugin.testkit

import dev.netherforge.format.bridge.BotEvent
import dev.netherforge.plugin.platform.BossBarLook
import dev.netherforge.plugin.platform.BossBarOps
import dev.netherforge.plugin.platform.PlayerListOps
import dev.netherforge.plugin.platform.SidebarOps
import dev.netherforge.plugin.platform.TeamLook
import dev.netherforge.plugin.platform.TeamOps
import java.util.UUID

// What the fake server shows above and beside the world: boss bars, sidebars, teams and the player list.

class FakeBossBars(private val platform: FakePlatform) : BossBarOps {
    val bars = LinkedHashMap<Int, BossBarLook>()
    val shown = LinkedHashMap<Int, MutableSet<UUID>>()

    override fun create(id: Int, look: BossBarLook) {
        bars[id] = look
        shown[id] = LinkedHashSet()
    }

    override fun update(id: Int, look: BossBarLook) {
        bars[id] = look
    }

    override fun show(id: Int, player: UUID): Boolean {
        if (player !in platform.players.byId) return false
        val viewers = shown[id] ?: return false
        if (viewers.add(player)) shownTo(player, id, true)
        return true
    }

    override fun hide(id: Int, player: UUID) {
        if (shown[id]?.remove(player) == true) shownTo(player, id, false)
    }

    override fun remove(id: Int) {
        for (player in shown[id].orEmpty()) shownTo(player, id, false)
        bars.remove(id)
        shown.remove(id)
    }

    /** What a bot's client hears of a bar coming onto or off its screen, by the bar's plain text. */
    private fun shownTo(player: UUID, id: Int, on: Boolean) {
        val text = platform.text.strip(bars.getValue(id).text)
        platform.bots.sent(player) { BotEvent.BossBar(it, text, on) }
    }

    /** The bars a player sees now. */
    fun of(player: FakePlayer) = shown.filterValues { player.ref.uuid in it }.keys.map { bars.getValue(it) }
}

class FakeSidebars(private val platform: FakePlatform) : SidebarOps {
    /** What each player's sidebar shows, while it shows. */
    val showing = LinkedHashMap<UUID, Pair<String, List<String>>>()

    override fun show(player: UUID, title: String, lines: List<String>): Boolean {
        if (player !in platform.players.byId) return false
        showing[player] = title to lines
        return true
    }

    override fun hide(player: UUID): Boolean {
        showing.remove(player)
        return player in platform.players.byId
    }
}

/** The main scoreboard's teams, entries kept as Minecraft keeps them (players by name, other entities by UUID). */
class FakeTeams : TeamOps {
    class Team(var look: TeamLook) {
        val entries = LinkedHashSet<String>()
    }

    /** Every team, by its name on the scoreboard; tests add other plugins' teams here too. */
    val teams = LinkedHashMap<String, Team>()

    /** The lines under name tags, by player name. */
    val belowNames = LinkedHashMap<String, String>()

    override fun names() = teams.keys.toList()

    override fun create(name: String, look: TeamLook): Boolean {
        if (name in teams) return false
        teams[name] = Team(look)
        return true
    }

    override fun update(name: String, look: TeamLook): Boolean {
        val team = teams[name] ?: return false
        team.look = look
        return true
    }

    override fun remove(name: String) = teams.remove(name) != null

    override fun entries(name: String) = teams[name]?.entries?.toSet()

    override fun addEntry(name: String, entry: String): Boolean {
        val team = teams[name] ?: return false
        for (other in teams.values) other.entries -= entry
        team.entries += entry
        return true
    }

    override fun removeEntry(name: String, entry: String) = teams[name]?.entries?.remove(entry) == true

    override fun teamOf(entry: String) = teams.entries.firstOrNull { entry in it.value.entries }?.key

    override fun setBelowName(player: String, text: String?) {
        if (text == null) belowNames.remove(player) else belowNames[player] = text
    }

    override fun clearBelowNames() = belowNames.clear()
}

/** Each online player's list entry; forgotten when they leave, as the server forgets it. */
class FakePlayerList(private val platform: FakePlatform) : PlayerListOps {
    val names = HashMap<UUID, String>()
    val orders = HashMap<UUID, Int>()

    /** By viewer: who's out of their list now. */
    val unlisted = HashMap<UUID, MutableSet<UUID>>()

    override fun name(player: UUID): String? = platform.players.byId[player]?.let { names[player] ?: it.ref.name }

    override fun setName(player: UUID, miniMessage: String?): Boolean {
        if (player !in platform.players.byId) return false
        if (miniMessage == null) names.remove(player) else names[player] = miniMessage
        return true
    }

    override fun order(player: UUID): Int? = if (player in platform.players.byId) orders[player] ?: 0 else null

    override fun setOrder(player: UUID, order: Int): Boolean {
        if (player !in platform.players.byId) return false
        orders[player] = order
        return true
    }

    override fun isListed(viewer: UUID, player: UUID): Boolean? {
        if (viewer !in platform.players.byId || player !in platform.players.byId) return null
        return unlisted[viewer]?.contains(player) != true
    }

    override fun setListed(viewer: UUID, player: UUID, listed: Boolean): Boolean {
        if (viewer !in platform.players.byId || player !in platform.players.byId) return false
        if (listed) unlisted[viewer]?.remove(player) else unlisted.getOrPut(viewer) { HashSet() } += player
        return true
    }

    /** [player] left: the server forgets their entry and whose lists they were out of. */
    fun quit(player: UUID) {
        names.remove(player)
        orders.remove(player)
        unlisted.remove(player)
        unlisted.values.forEach { it -= player }
    }
}
