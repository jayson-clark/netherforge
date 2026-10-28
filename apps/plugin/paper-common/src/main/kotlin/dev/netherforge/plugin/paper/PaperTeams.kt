package dev.netherforge.plugin.paper

import dev.netherforge.plugin.platform.PlayerListOps
import dev.netherforge.plugin.platform.TeamLook
import dev.netherforge.plugin.platform.TeamOps
import io.papermc.paper.scoreboard.numbers.NumberFormat
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.scoreboard.Criteria
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Objective
import org.bukkit.scoreboard.Scoreboard
import org.bukkit.scoreboard.Team
import java.util.UUID

/**
 * The server's main scoreboard, and copying what NetherForge keeps there onto
 * a player's own scoreboard (a sidebar's): every team, and the below-name
 * objective's lines.
 */
internal object MainBoard {
    /** The objective whose fixed-format scores are the lines under name tags. */
    const val BELOW_NAME = "nf.below_name"

    val board: Scoreboard get() = Bukkit.getScoreboardManager().mainScoreboard

    /** Makes [board]'s teams and below-name lines the main scoreboard's. */
    fun copyTo(board: Scoreboard) {
        val main = this.board
        for (team in board.teams.toList()) if (main.getTeam(team.name) == null) team.unregister()
        for (source in main.teams) {
            val copy = board.getTeam(source.name) ?: board.registerNewTeam(source.name)
            copy.displayName(source.displayName())
            copy.prefix(source.prefix())
            copy.suffix(source.suffix())
            copy.color(if (source.hasColor()) source.color() as? NamedTextColor else null)
            copy.setAllowFriendlyFire(source.allowFriendlyFire())
            copy.setCanSeeFriendlyInvisibles(source.canSeeFriendlyInvisibles())
            for (option in Team.Option.entries) copy.setOption(option, source.getOption(option))
            val entries = source.entries
            for (entry in copy.entries.toList()) if (entry !in entries) copy.removeEntry(entry)
            for (entry in entries) if (!copy.hasEntry(entry)) copy.addEntry(entry)
        }
        copyBelowName(main, board)
    }

    private fun copyBelowName(main: Scoreboard, board: Scoreboard) {
        val source = main.getObjective(BELOW_NAME)
        val copy = board.getObjective(BELOW_NAME)
        if (source == null) {
            copy?.unregister()
            return
        }
        val target = copy ?: belowNameObjective(board)
        val lines = main.entries.mapNotNull { entry ->
            val score = source.getScore(entry)
            if (score.isScoreSet) entry to score.numberFormat() else null
        }.toMap()
        for (entry in board.entries) if (entry !in lines && target.getScore(entry).isScoreSet) target.getScore(entry).resetScore()
        for ((entry, format) in lines) {
            val score = target.getScore(entry)
            if (!score.isScoreSet || score.numberFormat() != format) {
                score.score = 0
                score.numberFormat(format)
            }
        }
    }

    /**
     * The below-name objective on [board]: blank numbers and an empty title,
     * so a player's line is only their score's fixed text (a player without
     * one shows an empty line).
     */
    fun belowNameObjective(board: Scoreboard): Objective = board.registerNewObjective(BELOW_NAME, Criteria.DUMMY, Component.empty()).also {
        it.displaySlot = DisplaySlot.BELOW_NAME
        it.numberFormat(NumberFormat.blank())
    }
}

/**
 * [TeamOps] on the main scoreboard. Every change is copied onto the
 * players' own scoreboards on the next tick ([PaperSidebars.copySoon]),
 * rather than waiting for the copy each second that catches `/team`.
 */
class PaperTeams(private val sidebars: PaperSidebars) : TeamOps {
    private fun team(name: String): Team? = MainBoard.board.getTeam(name)

    /** Applies a change, then has it copied onto players' own scoreboards. */
    private inline fun <T> changing(change: () -> T): T = change().also { sidebars.copySoon() }

    override fun names(): List<String> = MainBoard.board.teams.map { it.name }

    override fun create(name: String, look: TeamLook): Boolean = changing {
        if (team(name) != null) return@changing false
        apply(MainBoard.board.registerNewTeam(name), look)
        true
    }

    override fun update(name: String, look: TeamLook): Boolean = changing {
        apply(team(name) ?: return@changing false, look)
        true
    }

    override fun remove(name: String): Boolean = changing {
        val team = team(name) ?: return@changing false
        team.unregister()
        true
    }

    override fun entries(name: String): Set<String>? = team(name)?.entries?.toSet()

    override fun addEntry(name: String, entry: String): Boolean = changing {
        // Minecraft takes the entry out of any other team first.
        team(name)?.addEntry(entry) ?: return@changing false
        true
    }

    override fun removeEntry(name: String, entry: String): Boolean = changing { team(name)?.removeEntry(entry) == true }

    override fun teamOf(entry: String): String? = MainBoard.board.getEntryTeam(entry)?.name

    override fun setBelowName(player: String, text: String?) = changing {
        val board = MainBoard.board
        val objective = board.getObjective(MainBoard.BELOW_NAME)
        if (text == null) {
            if (objective == null) return@changing
            objective.getScore(player).resetScore()
            // Nobody has a line any more: take the slot away, so nobody shows an empty one.
            if (board.entries.none { objective.getScore(it).isScoreSet }) objective.unregister()
            return@changing
        }
        val score = (objective ?: MainBoard.belowNameObjective(board)).getScore(player)
        score.score = 0
        score.numberFormat(NumberFormat.fixed(PaperText.mini.deserialize(text)))
    }

    override fun clearBelowNames() = changing { MainBoard.board.getObjective(MainBoard.BELOW_NAME)?.unregister() ?: Unit }

    private fun apply(team: Team, look: TeamLook) {
        team.displayName(PaperText.mini.deserialize(look.displayName))
        team.prefix(PaperText.mini.deserialize(look.prefix))
        team.suffix(PaperText.mini.deserialize(look.suffix))
        team.color(look.color?.let { NamedTextColor.NAMES.value(it) })
        team.setAllowFriendlyFire(look.friendlyFire)
        team.setCanSeeFriendlyInvisibles(look.seeInvisibleTeammates)
        team.setOption(Team.Option.NAME_TAG_VISIBILITY, status(look.nametags))
        team.setOption(Team.Option.COLLISION_RULE, status(look.collision))
    }

    /**
     * Bukkit's status for an option, from the Lua API's words: `hide_for_other_teams`
     * and `push_other_teams` apply the option (hiding, pushing) to other teams.
     */
    private fun status(value: String): Team.OptionStatus = when (value) {
        "never" -> Team.OptionStatus.NEVER
        "hide_for_other_teams", "push_other_teams" -> Team.OptionStatus.FOR_OTHER_TEAMS
        "hide_for_own_team", "push_own_team" -> Team.OptionStatus.FOR_OWN_TEAM
        else -> Team.OptionStatus.ALWAYS
    }
}

/** [PlayerListOps] with Paper's player list API. */
class PaperPlayerList : PlayerListOps {
    private fun player(uuid: UUID): Player? = Bukkit.getPlayer(uuid)

    override fun name(player: UUID): String? = player(player)?.playerListName()?.let(PaperText.mini::serialize)

    override fun setName(player: UUID, miniMessage: String?): Boolean {
        val who = player(player) ?: return false
        who.playerListName(miniMessage?.let(PaperText.mini::deserialize))
        return true
    }

    override fun order(player: UUID): Int? = player(player)?.playerListOrder

    override fun setOrder(player: UUID, order: Int): Boolean {
        val who = player(player) ?: return false
        who.playerListOrder = order
        return true
    }

    override fun isListed(viewer: UUID, player: UUID): Boolean? {
        val who = player(player) ?: return null
        return player(viewer)?.isListed(who)
    }

    override fun setListed(viewer: UUID, player: UUID, listed: Boolean): Boolean {
        val who = player(player) ?: return false
        val seer = player(viewer) ?: return false
        // Each answers false when nothing changed (already so, or the viewer can't see them at all); either way it's as asked.
        if (listed) seer.listPlayer(who) else seer.unlistPlayer(who)
        return true
    }
}
