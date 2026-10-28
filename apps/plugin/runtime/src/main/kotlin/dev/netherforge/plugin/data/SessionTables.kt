package dev.netherforge.plugin.data

import dev.netherforge.plugin.lua.LuaHost
import dev.netherforge.plugin.lua.LuaRef
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.session.RuntimeService
import java.util.UUID

/**
 * `player:session()`: a plain Lua table per online player, shared by every script and never saved.
 *
 * Made empty the first time a script asks while the player is online, and kept in the registry
 * until they leave (after their `player_quit` handlers, which still find it) or the session ends.
 * A script's or module's reload leaves it be: it belongs to the player, not to the scope that
 * made it. A whole-project reload is a new session, and a new Lua state, so every table starts
 * empty again.
 */
class SessionTables(private val host: () -> LuaHost?) : RuntimeService {
    override val name get() = "session tables"

    private val tables = HashMap<UUID, LuaRef>()

    /** [player]'s table, made empty the first time. The caller checks they're online. */
    fun table(player: UUID): LuaRef = tables.getOrPut(player) { requireNotNull(host()) { "no Lua state" }.keepData(null) }

    /** [player]'s table if a script has made one: what a handler after they left still finds. */
    fun existing(player: UUID): LuaRef? = tables[player]

    override fun playerQuit(player: PlayerRef) {
        tables.remove(player.uuid)?.let { ref -> host()?.unref(ref) }
    }

    override fun stop() {
        val host = host()
        if (host != null) for (ref in tables.values) host.unref(ref)
        tables.clear()
    }
}
