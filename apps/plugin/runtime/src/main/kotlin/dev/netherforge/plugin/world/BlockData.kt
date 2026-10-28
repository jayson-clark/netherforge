package dev.netherforge.plugin.world

import dev.netherforge.plugin.RuntimeLog
import dev.netherforge.plugin.data.ScriptData
import dev.netherforge.plugin.data.parseJson
import dev.netherforge.plugin.lua.LuaHost
import dev.netherforge.plugin.lua.LuaRef
import dev.netherforge.plugin.platform.BlockVector
import dev.netherforge.plugin.platform.Platform
import dev.netherforge.plugin.session.RuntimeService
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * The tables `block:data()` hands out: one per block position, the same table
 * to every script, kept for as long as the Lua state lives.
 *
 * A table is read from its chunk the first time a script asks for it (as JSON
 * the adapter keeps in the chunk's persistent data) and written back when the
 * chunk unloads, when the world saves and when the session ends, only when
 * it changed. A table outlives its chunk being unloaded: a script holding it
 * keeps the same table, and it's written again once the chunk is back.
 *
 * A table holds what every `data()` table may: it's
 * encoded by the prelude's one encoder (`LuaHost.encodeData`: typed values
 * tagged, what can't be saved skipped and reported by key path) and read back
 * typed (`LuaHost.keepData`), the same as `centity:data()` (`ScriptData`).
 */
internal class BlockData(private val platform: Platform, private val log: RuntimeLog, private val host: () -> LuaHost?) : RuntimeService {
    override val name get() = "block data"

    private data class Key(val world: String, val x: Int, val y: Int, val z: Int)

    /** A table scripts hold, and the JSON it was last read or saved as (null: nothing stored). */
    private class Held(val ref: LuaRef, var saved: String?) {
        /** What the last save couldn't keep, so the same problems aren't logged at every save. */
        var problems: List<String> = emptyList()

        /** The last save found it past the 1 MiB a table may take, so that's logged once while it stays so. */
        var tooBig = false
    }

    private val held = HashMap<Key, Held>()

    /** The table for a block position, or null when its chunk isn't loaded (and it hasn't been read before). */
    fun table(world: String, x: Int, y: Int, z: Int): LuaRef? {
        val key = Key(world, x, y, z)
        held[key]?.let { return it.ref }
        val host = host() ?: return null
        if (platform.blocks.get(world, x, y, z) == null) return null
        val stored = platform.blocks.data(world, x, y, z)
        val value = stored?.let(::parseJson)?.takeIf { it is JsonObject || it is JsonArray }
        return host.keepData(value).also { held[key] = Held(it, stored) }
    }

    /** Whether scripts gave the block at a position a table: one is held, or one is stored. */
    fun has(world: String, x: Int, y: Int, z: Int): Boolean = Key(world, x, y, z) in held || platform.blocks.data(world, x, y, z) != null

    /** The positions in a loaded chunk that scripts gave a table, as [has] says: held, or stored. */
    fun tablesIn(world: String, chunkX: Int, chunkZ: Int): Set<BlockVector> {
        val found = LinkedHashSet<BlockVector>()
        for (key in held.keys) {
            if (key.world == world && key.x shr 4 == chunkX && key.z shr 4 == chunkZ) found += BlockVector(key.x, key.y, key.z)
        }
        platform.blocks.withData(world, chunkX, chunkZ)?.let(found::addAll)
        return found
    }

    /**
     * A custom block went: its table goes with it, from the world and from
     * what scripts hold (a script still holding the table keeps one that's no
     * longer saved; the position's next one starts empty).
     */
    fun discard(world: String, x: Int, y: Int, z: Int) {
        val held = held.remove(Key(world, x, y, z))
        if (held != null) host()?.unref(held.ref)
        platform.blocks.setData(world, x, y, z, null)
    }

    /** Writes back the tables in one chunk, which is about to unload. */
    override fun chunkUnloading(world: String, chunkX: Int, chunkZ: Int) =
        save { it.world == world && it.x shr 4 == chunkX && it.z shr 4 == chunkZ }

    override fun worldSaving(world: String) = save { it.world == world }

    /** Writes back every table, then lets them go: the session is ending, after every script's `unload`. */
    override fun stop() {
        save { true }
        val host = host()
        if (host != null) for (it in held.values) host.unref(it.ref)
        held.clear()
    }

    private fun save(which: (Key) -> Boolean) {
        val host = host() ?: return
        for ((key, it) in held) {
            if (!which(key)) continue
            val where = "block:data() at ${key.world} ${key.x} ${key.y} ${key.z}"
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
            // An empty table is no data: the block's entry goes.
            val json = (encoded.text ?: continue).takeIf { text -> text != "{}" }
            if (json == it.saved) continue
            if (json != null && ScriptData.isTooBig(json)) {
                if (!it.tooBig) log.warn(ScriptData.tooBig(where, json.encodeToByteArray().size))
                it.tooBig = true
                continue
            }
            it.tooBig = false
            if (platform.blocks.setData(key.world, key.x, key.y, key.z, json)) it.saved = json
        }
    }
}
