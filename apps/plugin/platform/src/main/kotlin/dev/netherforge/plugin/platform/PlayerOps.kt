package dev.netherforge.plugin.platform

import java.util.UUID

interface PlayerOps {
    fun online(): List<PlayerRef>

    /** An online player by exact name (case-insensitive) or UUID string. */
    fun find(nameOrUuid: String): PlayerRef?

    /** Anyone who has ever joined this server, online or not, by name (case-insensitive) or UUID string. */
    fun known(nameOrUuid: String): PlayerRef?

    fun get(uuid: UUID): PlayerRef?

    /** Where they stand, with their facing; null when offline. */
    fun location(uuid: UUID): Location?

    /** Their line of sight from the eye; null when offline. */
    fun eye(uuid: UUID): Ray?

    /** Sends a MiniMessage line to one player. False when they're offline. */
    fun message(uuid: UUID, miniMessage: String): Boolean

    fun teleport(uuid: UUID, to: Location): Boolean

    /** Runs a command as the player, with their permissions. False if it didn't run. */
    fun runCommand(uuid: UUID, line: String): Boolean

    fun hasPermission(uuid: UUID, permission: String): Boolean

    /** Whether they're an operator, online or not. */
    fun isOperator(uuid: UUID): Boolean

    /** What chat calls them, as MiniMessage; null when offline. */
    fun displayName(uuid: UUID): String?

    fun setDisplayName(uuid: UUID, miniMessage: String): Boolean

    fun actionbar(uuid: UUID, miniMessage: String): Boolean

    /** A title and subtitle, with fade-in, stay and fade-out in ticks. */
    fun title(uuid: UUID, title: String, subtitle: String, fadeIn: Int, stay: Int, fadeOut: Int): Boolean

    fun clearTitle(uuid: UUID): Boolean

    /** `survival`, `creative`, `adventure` or `spectator`; null when offline. */
    fun gameMode(uuid: UUID): String?

    fun setGameMode(uuid: UUID, gameMode: String): Boolean

    /** Points, as orbs give them: the level goes up when the bar fills. */
    fun giveExperience(uuid: UUID, points: Int): Boolean

    /** Their game's language, like `en_us`; null when offline. */
    fun locale(uuid: UUID): String?

    fun kick(uuid: UUID, reason: String?): Boolean

    /** Ticks left on the cooldown of a group (an item's kind is its default group); null when offline. */
    fun cooldown(uuid: UUID, key: String): Int?

    fun setCooldown(uuid: UUID, key: String, ticks: Int): Boolean

    /** The player list's header (or footer, with [footer]) as MiniMessage; null when there's none or they're offline. */
    fun tabText(uuid: UUID, footer: Boolean): String?

    fun setTabText(uuid: UUID, footer: Boolean, miniMessage: String): Boolean

    /** Closes whatever container screen they have open. */
    fun closeInventory(uuid: UUID): Boolean

    /** What they carry on their cursor, whatever screen they have open; null for nothing, or when they're offline. */
    fun cursor(uuid: UUID): ItemData?

    /** Puts [item] (null empties it) on their cursor, whatever screen they have open. False when they're offline, or it can't be built. */
    fun setCursor(uuid: UUID, item: ItemData?): Boolean

    /** A MiniMessage line to everyone online and the console. */
    fun broadcast(miniMessage: String)

    /** A MiniMessage line to the server console alone. */
    fun messageConsole(miniMessage: String)
}
