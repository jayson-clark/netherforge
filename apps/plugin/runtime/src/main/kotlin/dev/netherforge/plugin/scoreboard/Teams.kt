package dev.netherforge.plugin.scoreboard
import dev.netherforge.plugin.platform.Platform
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.platform.TeamLook
import dev.netherforge.plugin.script.Scope
import dev.netherforge.plugin.session.RuntimeService
import dev.netherforge.plugin.session.SessionProject
import java.util.UUID

/**
 * The teams scripts make (`nf.teams.create`), on the server's main scoreboard,
 * and the lines under players' name tags (`player:set_below_name`).
 *
 * A team is called `nf.<name>` there ([PREFIX]), so the project's teams
 * never collide with vanilla's or another plugin's, and only ours are ever
 * removed. Each belongs to the scope that made it and goes when that scope is
 * released, and every one goes when the session ends: the script's body
 * makes them again, as it sets up a centity's nodes. The main scoreboard is
 * saved with the world, so teams a crash left behind are removed before a
 * session's scripts run ([define]).
 *
 * What a team looks like is kept here, as scripts set it (so they read back
 * exactly what they wrote); who's in it is the scoreboard's, since `/team`
 * and joining another team change that too.
 */
internal class Teams(private val platform: Platform) : RuntimeService {
    override val name get() = "teams"

    class Team(val name: String, val scope: Scope, var look: TeamLook) {
        /** Its name on the scoreboard. */
        val full get() = PREFIX + name
    }

    private val teams = LinkedHashMap<String, Team>()
    private val belowNames = HashMap<UUID, String>()

    fun get(name: String): Team? = teams[name]

    fun all(): List<Team> = teams.values.toList()

    /** Makes a team for [scope]; null when the project already has one called [name]. */
    fun create(scope: Scope, name: String, look: TeamLook): Team? {
        if (name in teams) return null
        val team = Team(name, scope, look)
        // Only ever ours: a name with our prefix that a crash left behind.
        if (!platform.teams.create(team.full, look)) {
            platform.teams.remove(team.full)
            platform.teams.create(team.full, look)
        }
        teams[name] = team
        return team
    }

    fun update(team: Team, look: TeamLook) {
        team.look = look
        platform.teams.update(team.full, look)
    }

    fun remove(team: Team): Boolean {
        if (teams[team.name] !== team) return false
        teams.remove(team.name)
        platform.teams.remove(team.full)
        return true
    }

    /** Its members, as the scoreboard has them: players' names and other entities' UUIDs. */
    fun entries(team: Team): Set<String> = platform.teams.entries(team.full).orEmpty()

    fun addEntry(team: Team, entry: String): Boolean = platform.teams.addEntry(team.full, entry)

    fun removeEntry(team: Team, entry: String): Boolean = platform.teams.removeEntry(team.full, entry)

    /** The project's team [entry] is in, or null (in none, or in someone else's). */
    fun of(entry: String): Team? {
        val full = platform.teams.teamOf(entry) ?: return null
        if (!full.startsWith(PREFIX)) return null
        return teams[full.removePrefix(PREFIX)]
    }

    /** A scope is going: its teams go with it. */
    override fun scopeReleased(scope: Scope) {
        for (team in teams.values.filter { it.scope == scope }) remove(team)
    }

    // ---- under name tags ----------------------------------------------------------

    fun belowName(player: UUID): String? = belowNames[player]

    fun setBelowName(player: PlayerRef, text: String?) {
        if (text == null) belowNames.remove(player.uuid) else belowNames[player.uuid] = text
        platform.teams.setBelowName(player.name, text)
    }

    /** They left: their line goes. */
    override fun playerQuit(player: PlayerRef) {
        if (belowNames.remove(player.uuid) != null) platform.teams.setBelowName(player.name, null)
    }

    // ---- lifetime -----------------------------------------------------------------

    /** The session is ending: every team goes, and every line under a name tag. */
    override fun stop() {
        for (team in teams.values.toList()) remove(team)
        if (belowNames.isNotEmpty()) platform.teams.clearBelowNames()
        belowNames.clear()
    }

    /** Before anything runs: teams of ours and lines under name tags a crash left on the scoreboard go. */
    override fun define(project: SessionProject) {
        val ops = platform.teams
        for (name in ops.names()) if (name.startsWith(PREFIX)) ops.remove(name)
        ops.clearBelowNames()
    }

    companion object {
        /** What the project's teams are called by on the scoreboard, before their own names. */
        const val PREFIX = "nf."

        /** What a team's name may be made of: what `/team` commands can type without quotes. */
        val NAME = Regex("[A-Za-z0-9_.+-]+")
    }
}
