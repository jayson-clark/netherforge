package dev.netherforge.plugin.platform

import java.util.UUID

/**
 * What a scoreboard team shows and allows, in the Lua API's words: text is
 * MiniMessage, [color] one of Minecraft's sixteen chat colour names
 * (`"red"`, `"dark_blue"`) or null for none, [nametags] `always`, `never`,
 * `hide_for_other_teams` or `hide_for_own_team`, and [collision] `always`,
 * `never`, `push_other_teams` or `push_own_team`.
 */
data class TeamLook(
    val displayName: String,
    val prefix: String = "",
    val suffix: String = "",
    val color: String? = null,
    val friendlyFire: Boolean = true,
    val seeInvisibleTeammates: Boolean = true,
    val nametags: String = "always",
    val collision: String = "always"
)

/**
 * Teams on the server's main scoreboard, by their full name there, and the
 * line under players' name tags (the below-name slot). A team's members are
 * entries, as Minecraft keeps them: a player's name, or any other entity's
 * UUID. Whatever gives a player a scoreboard of their own (a sidebar) keeps
 * these on it too; the adapter sees to that.
 */
interface TeamOps {
    /** Every team on the main scoreboard, whoever made it. */
    fun names(): List<String>

    /** Makes a team. False when the scoreboard already has one called [name]. */
    fun create(name: String, look: TeamLook): Boolean

    /** False when there's no such team. */
    fun update(name: String, look: TeamLook): Boolean

    /** Takes a team off the scoreboard, members and all. False when there's no such team. */
    fun remove(name: String): Boolean

    /** A team's entries; null when there's no such team. */
    fun entries(name: String): Set<String>?

    /** Puts [entry] in a team, out of any other. False when there's no such team. */
    fun addEntry(name: String, entry: String): Boolean

    /** False when there's no such team, or [entry] wasn't in it. */
    fun removeEntry(name: String, entry: String): Boolean

    /** The team [entry] is in, or null. */
    fun teamOf(entry: String): String?

    /** Shows [text] (MiniMessage) under the name tag of the player called [player], or takes it away for null. */
    fun setBelowName(player: String, text: String?)

    /** Takes every line [setBelowName] put under a name tag away. */
    fun clearBelowNames()
}

/**
 * The player list (what tab shows) beyond its header and footer: each
 * player's entry's name and place, and whose list they're in. The server
 * forgets names and places when the player leaves, and which lists a player
 * is out of when either of them does. Everything answers null or false for a
 * player who's offline.
 */
interface PlayerListOps {
    /** The name their entry shows, MiniMessage. */
    fun name(player: UUID): String?

    /** Changes the name their entry shows; null goes back to their name. */
    fun setName(player: UUID, miniMessage: String?): Boolean

    /** Their entry's place: higher comes first. */
    fun order(player: UUID): Int?

    fun setOrder(player: UUID, order: Int): Boolean

    /** Whether [player] is in [viewer]'s list. */
    fun isListed(viewer: UUID, player: UUID): Boolean?

    /** Takes [player] out of [viewer]'s list, or puts them back. */
    fun setListed(viewer: UUID, player: UUID, listed: Boolean): Boolean
}
