package dev.netherforge.plugin.platform

import java.util.UUID

/**
 * A built resource pack offered to clients: where to fetch it and its SHA-1, so a client that has it skips the
 * download. [replacing] takes every other pack the player has off first (what the project's pack does as they join,
 * so nothing another server sent stays); otherwise it's added beside them, replacing only one of the same id.
 */
data class PackOffer(
    val id: UUID,
    val url: String,
    val sha1: String,
    val required: Boolean,
    val prompt: String?,
    val replacing: Boolean = true
)

interface ResourcePackOps {
    /** Asks a player's client to load [pack], replacing the one sent before under the same id. False when they're offline. */
    fun send(player: UUID, pack: PackOffer): Boolean

    /** Asks a player's client to take off the pack sent under [id]. False when they're offline. */
    fun remove(player: UUID, id: UUID): Boolean
}
