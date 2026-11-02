package dev.netherforge.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * The network's moderation, kept by the hub: bans (applied by the proxy before a player reaches any server) and mutes
 * (every server holds back a muted player's chat), in two shared maps of the hub's own ([BANS], [MUTES]: by player
 * UUID, a [Sanction] each, expiring as maps' keys do), saved with the network's maps; and players' reports.
 */
object Moderation {
    /** The namespace of the hub's own maps: no package may write them, every server may read them. */
    const val NAMESPACE = "netherforge"
    const val BANS = "$NAMESPACE:bans"
    const val MUTES = "$NAMESPACE:mutes"

    /** The servers and groups taken off the network (`/nfhub disable`), by name in lowercase, with why. */
    const val DISABLED = "$NAMESPACE:disabled"

    fun isReserved(full: String) = NetworkNames.namespaceOf(full) == NAMESPACE

    /** Every server reads these, whatever its grants: what it must enforce. */
    val READABLE = setOf(BANS, MUTES)
}

/** A ban or a mute: who ([name]), why, who did it, and until when (Unix milliseconds; null for good). */
@Serializable
data class Sanction(val name: String, val reason: String? = null, val by: String, val at: Long, val until: Long? = null)

@Serializable
enum class ModerationAction(val word: String) {
    @SerialName("ban")
    BAN("ban"),

    @SerialName("unban")
    UNBAN("unban"),

    @SerialName("mute")
    MUTE("mute"),

    @SerialName("unmute")
    UNMUTE("unmute")
}

/** A player's report of another, as the hub keeps it. */
@Serializable
data class Report(
    val id: Long,
    val player: String,
    val name: String,
    val reason: String,
    val details: JsonElement? = null,
    val by: String? = null,
    val server: String,
    val at: Long
)
