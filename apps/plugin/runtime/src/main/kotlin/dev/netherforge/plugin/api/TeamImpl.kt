package dev.netherforge.plugin.api

import dev.netherforge.plugin.binding.Caller
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.binding.NfTeamsApi
import dev.netherforge.plugin.binding.TeamApi
import dev.netherforge.plugin.binding.TeamOptions
import dev.netherforge.plugin.binding.entityHandle
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.platform.TeamLook
import dev.netherforge.plugin.scoreboard.Teams
import dev.netherforge.plugin.session.ProjectSession
import java.util.UUID

/**
 * What [member] is called on a scoreboard: a player's name, any other
 * entity's UUID. Null for a handle that names nothing, and, when [present],
 * for an entity that isn't in the world (a player counts offline too: the
 * scoreboard keeps them by name).
 */
internal fun ProjectSession.scoreboardEntry(member: LuaHandle.Entity, present: Boolean): String? {
    val id = member.uuidOrNull() ?: return null
    platform.players.get(id)?.let { return it.name }
    if (member is LuaHandle.Player) return platform.players.known(member.id)?.name
    if (present && platform.worldEntities.info(id) == null) return null
    return id.toString()
}

/** `nf.teams`. */
internal class NfTeamsImpl(private val session: ProjectSession) : NfTeamsApi {
    override fun create(caller: Caller, name: String, options: TeamOptions?): LuaHandle.Team {
        if (!Teams.NAME.matches(name)) {
            throw LuaApiException("a team's name is letters, digits and _ - . +, not \"$name\"")
        }
        val look = TeamLook(
            displayName = options?.displayName ?: name,
            prefix = options?.prefix.orEmpty(),
            suffix = options?.suffix.orEmpty(),
            color = options?.color,
            friendlyFire = options?.friendlyFire ?: true,
            seeInvisibleTeammates = options?.seeInvisibleTeammates ?: true,
            nametags = options?.nametags ?: "always",
            collision = options?.collision ?: "always"
        )
        session.teams.create(caller.scope, name, look)
            ?: throw LuaApiException("the project already has a team called \"$name\" (nf.teams.get finds it)")
        return LuaHandle.Team(name)
    }

    override fun get(caller: Caller, name: String): LuaHandle.Team? = session.teams.get(name)?.let { LuaHandle.Team(it.name) }

    override fun all(caller: Caller): List<LuaHandle.Team> = session.teams.all().map { LuaHandle.Team(it.name) }
}

/** `Team`: answers nil, false or nothing once it's removed or its script has unloaded. */
internal class TeamImpl(private val session: ProjectSession) : TeamApi {
    private val teams get() = session.teams

    private fun team(self: LuaHandle.Team): Teams.Team? = teams.get(self.name)

    override fun name(self: LuaHandle.Team): String = self.name

    private fun change(self: LuaHandle.Team, change: (TeamLook) -> TeamLook): Boolean {
        val team = team(self) ?: return false
        teams.update(team, change(team.look))
        return true
    }

    override fun exists(self: LuaHandle.Team): Boolean = team(self) != null

    override fun addMember(self: LuaHandle.Team, member: LuaHandle.Entity): Boolean {
        val team = team(self) ?: return false
        val entry = session.scoreboardEntry(member, present = true) ?: return false
        return teams.addEntry(team, entry)
    }

    override fun removeMember(self: LuaHandle.Team, member: LuaHandle.Entity): Boolean {
        val team = team(self) ?: return false
        val entry = session.scoreboardEntry(member, present = false) ?: return false
        return teams.removeEntry(team, entry)
    }

    override fun hasMember(self: LuaHandle.Team, member: LuaHandle.Entity): Boolean {
        val team = team(self) ?: return false
        val entry = session.scoreboardEntry(member, present = false) ?: return false
        return entry in teams.entries(team)
    }

    override fun members(self: LuaHandle.Team): List<LuaHandle.Entity> {
        val team = team(self) ?: return emptyList()
        val platform = session.platform
        return teams.entries(team).sorted().mapNotNull { entry ->
            val id = runCatching { UUID.fromString(entry) }.getOrNull()
            if (id != null) {
                platform.worldEntities.info(id)?.let { session.entityHandle(it) }
            } else {
                platform.players.find(entry)?.takeIf { it.name == entry }?.let { LuaHandle.Player(it.uuid.toString()) }
            }
        }
    }

    override fun displayName(self: LuaHandle.Team): String? = team(self)?.look?.displayName

    override fun setDisplayName(self: LuaHandle.Team, text: String): Boolean = change(self) { it.copy(displayName = text) }

    override fun prefix(self: LuaHandle.Team): String? = team(self)?.look?.prefix

    override fun setPrefix(self: LuaHandle.Team, text: String): Boolean = change(self) { it.copy(prefix = text) }

    override fun suffix(self: LuaHandle.Team): String? = team(self)?.look?.suffix

    override fun setSuffix(self: LuaHandle.Team, text: String): Boolean = change(self) { it.copy(suffix = text) }

    override fun color(self: LuaHandle.Team): String? = team(self)?.look?.color

    override fun setColor(self: LuaHandle.Team, color: String?): Boolean = change(self) { it.copy(color = color) }

    override fun hasFriendlyFire(self: LuaHandle.Team): Boolean = team(self)?.look?.friendlyFire == true

    override fun setFriendlyFire(self: LuaHandle.Team, enabled: Boolean): Boolean = change(self) { it.copy(friendlyFire = enabled) }

    override fun canSeeInvisibleTeammates(self: LuaHandle.Team): Boolean = team(self)?.look?.seeInvisibleTeammates == true

    override fun setCanSeeInvisibleTeammates(self: LuaHandle.Team, enabled: Boolean): Boolean =
        change(self) { it.copy(seeInvisibleTeammates = enabled) }

    override fun nametags(self: LuaHandle.Team): String? = team(self)?.look?.nametags

    override fun setNametags(self: LuaHandle.Team, visibility: String): Boolean = change(self) { it.copy(nametags = visibility) }

    override fun collision(self: LuaHandle.Team): String? = team(self)?.look?.collision

    override fun setCollision(self: LuaHandle.Team, rule: String): Boolean = change(self) { it.copy(collision = rule) }

    override fun remove(self: LuaHandle.Team): Boolean {
        val team = team(self) ?: return false
        return teams.remove(team)
    }
}
