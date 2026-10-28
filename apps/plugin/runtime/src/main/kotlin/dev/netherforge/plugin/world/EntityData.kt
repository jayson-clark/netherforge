package dev.netherforge.plugin.world

import dev.netherforge.plugin.RuntimeLog
import dev.netherforge.plugin.data.ScriptData
import dev.netherforge.plugin.data.parseJson
import dev.netherforge.plugin.lua.LuaHost
import dev.netherforge.plugin.lua.LuaRef
import dev.netherforge.plugin.platform.Platform
import dev.netherforge.plugin.session.RuntimeService
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/**
 * The tables `entity:data()` hands out: one per entity, the same table to
 * every script, kept for as long as the Lua state lives (or the entity).
 *
 * A table is read from the entity the first time a script asks for it (JSON
 * the adapter keeps in the entity's persistent data, under the same key an
 * item's script data uses) and written back when the entity's chunk unloads,
 * when a world saves and when the session ends, only when it changed. An
 * entity that dies or is removed takes its table with it.
 *
 * It holds what every `data()` table may, through the prelude's one encoder,
 * the same as `block:data()` ([BlockData]).
 */
internal class EntityData(private val platform: Platform, private val log: RuntimeLog, private val host: () -> LuaHost?) : RuntimeService {
    override val name get() = "entity data"

    /** A table scripts hold, and the JSON it was last read or saved as (null: nothing stored). */
    private class Held(val ref: LuaRef, var saved: String?) {
        var problems: List<String> = emptyList()

        /** The last save found it past the 1 MiB a table may take, so that's logged once while it stays so. */
        var tooBig = false
    }

    private val held = HashMap<UUID, Held>()

    /** The entity's table, or null when it isn't in the world (and hasn't been read before). */
    fun table(id: UUID): LuaRef? {
        held[id]?.let { return it.ref }
        val host = host() ?: return null
        if (platform.worldEntities.info(id) == null) return null
        val stored = platform.worldEntities.data(id)
        val value = stored?.let(::parseJson)?.takeIf { it is JsonObject || it is JsonArray }
        return host.keepData(value).also { held[id] = Held(it, stored) }
    }

    /** Writes back the tables of entities about to unload. */
    override fun entitiesUnloading(entities: Set<UUID>) = save { it in entities }

    override fun worldSaving(world: String) = save { true }

    /** The entity has gone for good: its table goes, unsaved. */
    override fun entityGone(id: UUID) {
        val it = held.remove(id) ?: return
        host()?.unref(it.ref)
    }

    /** Writes back every table, then lets them go: the session is ending, after every script's `unload`. */
    override fun stop() {
        save { true }
        val host = host()
        if (host != null) for (it in held.values) host.unref(it.ref)
        held.clear()
    }

    private fun save(which: (UUID) -> Boolean) {
        val host = host() ?: return
        for ((id, it) in held) {
            if (!which(id)) continue
            val where = "entity:data() of $id"
            val encoded = try {
                host.encodeData(it.ref, "data")
            } catch (e: Exception) {
                log.warn("$where wasn't saved: ${e.message}")
                continue
            }
            if (encoded.problems != it.problems) {
                it.problems = encoded.problems
                for (problem in encoded.problems) log.warn("Saving $where: $problem")
            }
            val json = (encoded.text ?: continue).takeIf { text -> text != "{}" }
            if (json == it.saved) continue
            if (json != null && ScriptData.isTooBig(json)) {
                if (!it.tooBig) log.warn(ScriptData.tooBig(where, json.encodeToByteArray().size))
                it.tooBig = true
                continue
            }
            it.tooBig = false
            if (platform.worldEntities.setData(id, json)) it.saved = json
        }
    }
}
