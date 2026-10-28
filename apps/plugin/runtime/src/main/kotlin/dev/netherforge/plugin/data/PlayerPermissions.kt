package dev.netherforge.plugin.data

import dev.netherforge.plugin.platform.Platform
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.session.RuntimeService
import dev.netherforge.plugin.store.Store
import java.util.TreeMap
import java.util.UUID

/**
 * The permission nodes scripts set on players (`player:set_permission`),
 * kept in the store under the project's namespace (by player, then node) so
 * they outlast leaving and restarts, and held on each online player through
 * [Platform.permissions].
 *
 * What applies is what's kept **and** what a `netherforge.json`'s
 * `allow.permissions` allows now ([allowed]: the project's or any package's): a node a later edit no longer
 * allows stays kept but stops applying, and applies again if the edit is
 * undone. Nothing applies while the project isn't running ([start] and
 * [stop] bracket a run), so a refused project grants nothing.
 */
internal class PlayerPermissions(
    private val platform: Platform,
    private val store: Store.Permissions,
    /** Whose grants these are: the project's namespace. */
    private val namespace: String,
    private val allowed: (String) -> Boolean
) : RuntimeService {
    override val name get() = "permissions"

    /** Every player's nodes. */
    private val nodes = TreeMap<UUID, TreeMap<String, Boolean>>()

    private var running = false

    init {
        for ((player, its) in store.of(namespace)) nodes[player] = TreeMap(its)
    }

    /** What the project set on [player], allowed or not. */
    fun of(player: UUID): Map<String, Boolean> = nodes[player].orEmpty()

    fun set(player: UUID, node: String, value: Boolean) {
        val mine = nodes.getOrPut(player) { TreeMap() }
        if (mine.put(node, value) == value) return
        store.set(namespace, player, node, value)
        apply(player)
    }

    /** False when nothing was set for [node]. */
    fun unset(player: UUID, node: String): Boolean {
        val mine = nodes[player] ?: return false
        if (mine.remove(node) == null) return false
        if (mine.isEmpty()) nodes.remove(player)
        store.set(namespace, player, node, null)
        apply(player)
        return true
    }

    /** The project runs: everyone online gets what's allowed now (before any script, so `has_permission` already answers). */
    override fun start() {
        running = true
        for (player in platform.players.online()) apply(player.uuid)
    }

    /** The project stops (a reload, a refusal, the server stopping): everyone online loses what the project set. */
    override fun stop() {
        running = false
        for (player in platform.players.online()) platform.permissions.apply(player.uuid, emptyMap())
    }

    /** Someone joined: what they're kept to have, again (the server forgot it when they left). */
    override fun playerJoined(player: PlayerRef) {
        if (nodes.containsKey(player.uuid)) apply(player.uuid)
    }

    private fun apply(player: UUID) {
        if (!running) return
        platform.permissions.apply(player, of(player).filterKeys(allowed))
    }
}
