package dev.netherforge.plugin.scoreboard
import dev.netherforge.plugin.platform.Platform
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.session.RuntimeService
import java.util.UUID

/**
 * Whose player list each player is out of (`player:set_listed_for`), kept
 * here because the server forgets it when either of them leaves: it's
 * applied again when they join. Like what's hidden ([HiddenEntities]), it
 * lasts as long as the session: everyone is listed again when it ends.
 */
internal class PlayerListings(private val platform: Platform) : RuntimeService {
    override val name get() = "player lists"

    /** By viewer: the players out of their list. */
    private val unlisted = HashMap<UUID, MutableSet<UUID>>()

    fun isListed(viewer: UUID, player: UUID): Boolean = unlisted[viewer]?.contains(player) != true

    fun setListed(viewer: UUID, player: UUID, listed: Boolean): Boolean {
        if (!platform.playerList.setListed(viewer, player, listed)) return false
        if (listed) {
            unlisted[viewer]?.let {
                it -= player
                if (it.isEmpty()) unlisted.remove(viewer)
            }
        } else {
            unlisted.getOrPut(viewer) { HashSet() } += player
        }
        return true
    }

    /** Someone joined: out of the lists they were out of, and the players out of theirs out again. */
    override fun playerJoined(player: PlayerRef) {
        val ops = platform.playerList
        for ((viewer, players) in unlisted) {
            if (viewer == player.uuid) {
                for (other in players) ops.setListed(viewer, other, false)
            } else if (player.uuid in players) {
                ops.setListed(viewer, player.uuid, false)
            }
        }
    }

    /** The session is ending: everyone is back in every list. */
    override fun stop() {
        val ops = platform.playerList
        for ((viewer, players) in unlisted) for (player in players) ops.setListed(viewer, player, true)
        unlisted.clear()
    }
}
