package dev.netherforge.plugin.platform

import java.util.UUID

/**
 * What one player's game is shown that isn't so (`player:send_block_change`,
 * `send_equipment_change`, `open_book`), and their own view of the world: a
 * camera, a compass target, a view distance. Every call answers false (or
 * null) when [player] is offline.
 */
interface PlayerViewOps {
    /** Shows [player] a canonical block state the server has at a block, which the world doesn't have. */
    fun sendBlock(player: UUID, world: String, x: Int, y: Int, z: Int, state: String): Boolean

    /** Shows [player] the real block again. False also when [world] isn't loaded. */
    fun resetBlock(player: UUID, world: String, x: Int, y: Int, z: Int): Boolean

    /** Shows [player] [entity] with [item] (null: nothing) in an equipment slot. False also when the entity has gone or has no equipment. */
    fun sendEquipment(player: UUID, entity: UUID, slot: String, item: ItemData?): Boolean

    /** The entity [player] is spectating through, or null for their own eyes. */
    fun camera(player: UUID): UUID?

    /**
     * Spectates through [entity] (null: back to their own eyes): any entity the server has, the displays
     * centities and cutscenes are made of included. The server moves the player to it, and with it each
     * tick after. False also when they aren't in spectator mode, or the entity has gone.
     */
    fun setCamera(player: UUID, entity: UUID?): Boolean

    fun compassTarget(player: UUID): Location?

    fun setCompassTarget(player: UUID, target: Location): Boolean

    /** Opens a written book of MiniMessage [pages] on their screen. */
    fun openBook(player: UUID, pages: List<String>): Boolean

    /** The distance they're sent now, in chunks: a change shows from the next tick, lowered by their own game's setting. */
    fun viewDistance(player: UUID): Int?

    /** In chunks, already checked to be 2 to 32. */
    fun setViewDistance(player: UUID, chunks: Int): Boolean
}

/** A ban as the server keeps it: why, when it runs out (Unix ms, null for never), and who made it. */
data class BanSpec(val reason: String?, val expires: Long?, val source: String)

/**
 * The server's own settings and lists: who has played, the most players
 * allowed, the message of the day, the whitelist and bans. Whatever changes
 * one lasts until the server restarts unless the server keeps it in its own
 * files (the whitelist and bans do).
 */
interface ServerAdminOps {
    /** Everyone who has ever joined, online or not, who has a name. */
    fun known(): List<PlayerRef>

    /** When they first joined and were last here, Unix ms (now while online); null for someone who never has. */
    fun firstPlayed(player: UUID): Long?

    fun lastSeen(player: UUID): Long?

    fun maxPlayers(): Int

    fun setMaxPlayers(count: Int)

    /** The message of the day, as MiniMessage. */
    fun motd(): String

    fun setMotd(miniMessage: String)

    fun isWhitelistEnabled(): Boolean

    fun setWhitelistEnabled(enabled: Boolean)

    fun whitelisted(): List<PlayerRef>

    fun isWhitelisted(player: UUID): Boolean

    fun setWhitelisted(player: UUID, whitelisted: Boolean)

    /** Everyone banned whose ban hasn't run out. */
    fun banned(): List<PlayerRef>

    fun isBanned(player: UUID): Boolean

    /** Bans them, replacing any ban they had, and kicks them with the reason when they're online. */
    fun ban(player: UUID, ban: BanSpec)

    /** False when they weren't banned. */
    fun unban(player: UUID): Boolean
}

/**
 * The permission nodes the project sets on players, on top of whatever else
 * gives them permissions (operators, a permissions plugin). The runtime keeps
 * them and decides which apply; the platform only holds them on the online
 * player, which the server forgets when they leave.
 */
interface PermissionOps {
    /** Replaces everything the project set on an online [player] with [nodes] (true: granted, false: denied). Nothing when offline. */
    fun apply(player: UUID, nodes: Map<String, Boolean>)
}

/** Advancements, by namespaced key. Calls about a player answer false (or null) when they're offline. */
interface AdvancementOps {
    /** The names of the server's advancement [key]'s criteria; null when it has no such advancement. */
    fun criteria(key: String): List<String>?

    /**
     * Awards [criterion], one of the advancement's (every criterion left when
     * null). False when there was nothing left to award.
     */
    fun grant(player: UUID, key: String, criterion: String? = null): Boolean

    /** Takes back [criterion] (every criterion they have when null). False when they didn't have it. */
    fun revoke(player: UUID, key: String, criterion: String? = null): Boolean

    fun has(player: UUID, key: String): Boolean

    /** The criteria they've met and those left. */
    fun progress(player: UUID, key: String): Pair<List<String>, List<String>>?
}
