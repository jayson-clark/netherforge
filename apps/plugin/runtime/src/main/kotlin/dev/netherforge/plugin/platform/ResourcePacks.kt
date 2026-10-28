package dev.netherforge.plugin.platform

import java.util.UUID

/** A built resource pack offered to clients: where to fetch it and its SHA-1, so a client that has it skips the download. */
data class PackOffer(val id: UUID, val url: String, val sha1: String, val required: Boolean, val prompt: String?)

interface ResourcePackOps {
    /** Asks a player's client to load [pack], replacing the one sent before under the same id. False when they're offline. */
    fun send(player: UUID, pack: PackOffer): Boolean
}
