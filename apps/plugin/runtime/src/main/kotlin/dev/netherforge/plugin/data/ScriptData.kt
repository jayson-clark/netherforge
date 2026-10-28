package dev.netherforge.plugin.data

import dev.netherforge.plugin.lua.LuaHost
import dev.netherforge.plugin.lua.LuaRef
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.session.RuntimeService
import dev.netherforge.plugin.session.TickPhase
import dev.netherforge.plugin.store.Store
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/**
 * Scripts' saved tables: `centity:data()`,
 * `player:data()` and `nf.data(name)`.
 *
 * Each is a live Lua table, made the first time a script asks for it (from
 * the store, when it was saved before) and kept for as long as the session
 * runs, so a hot reload finds it as it was. The whole table is encoded by the
 * prelude's one encoder (`LuaHost.encodeData`: typed values tagged, what
 * can't be saved skipped and reported by key path) and saved when it
 * changed: at autosave, after a reload, when its player leaves, when the
 * session ends (a full reload, the server stopping). A table encoded past
 * [MAX_BYTES] isn't saved; its last save stands.
 *
 * When the session ends, each table's latest encoding is kept ([kept]) and
 * the next session's table starts from it, not from the store.
 *
 * `nf.data(name)` is per package: each package (the project, or a
 * dependency) has its own tables by name, under its namespace. A thing's
 * table (`player:data()`, `centity:data()`) is the thing's, one for every
 * package's scripts.
 *
 * They're kept in the runtime's store ([Store.Tables]): encoding is the main
 * thread's (it's Lua), writing is the store's, committed with the tick.
 */
class ScriptData(
    /** Where the tables are kept. */
    private val store: Store.Tables,
    private val host: () -> LuaHost?,
    /** A line for the log. Called on the main thread. */
    private val warn: (String) -> Unit,
    /** What the last session left ([kept]): each table's encoding when it ended. */
    carried: Map<Owner, Kept> = emptyMap(),
    /** The server tick now, for autosave. */
    private val ticks: () -> Long = { 0 }
) : RuntimeService {
    override val name get() = "saved data"

    /** Whose table it is. */
    sealed interface Owner {
        data class Centity(val instance: UUID) : Owner

        data class Player(val player: UUID) : Owner

        /** `nf.data(name)` of the package [namespace]. */
        data class Named(val namespace: String, val name: String) : Owner
    }

    /** A table as one session left it for the next: its encoding then, and what was last saved. */
    class Kept(val label: String, val text: String?, val saved: String?)

    private class Table(var label: String) {
        var ref: LuaRef? = null

        /** The text last saved or read, so an unchanged table isn't saved again. */
        var saved: String? = null

        /** The table's encoding when the Lua state closed, for the next state's table to start from. */
        var carried: String? = null

        /** What the last save couldn't keep, so the same problems aren't logged at every save. */
        var problems: List<String> = emptyList()
        var tooBig = false
    }

    private val tables = HashMap<Owner, Table>()

    init {
        for ((owner, kept) in carried) {
            tables[owner] = Table(kept.label).apply {
                this.carried = kept.text
                saved = kept.saved
            }
        }
    }

    /** Every table that changed, at autosave. */
    override fun tick(phase: TickPhase) {
        if (phase == TickPhase.SAVE && ticks() % AUTOSAVE_EVERY == 0L) saveAll()
    }

    /** They left: their table is saved now (after their `player_quit` handlers). */
    override fun playerQuit(player: PlayerRef) = save(Owner.Player(player.uuid))

    /** The session is ending, after every script's unload handlers (which may still change their tables): every table is saved. */
    override fun stop() = detach()

    /** What the next session's tables start from: each table's encoding as this session ended. */
    fun kept(): Map<Owner, Kept> = tables.mapValues { (_, table) -> Kept(table.label, table.carried, table.saved) }

    /**
     * [owner]'s live table, made from what the store has (or as an empty
     * table) the first time. [label] names it in the log (`player Steve`).
     */
    fun table(owner: Owner, label: String): LuaRef {
        val host = requireNotNull(host()) { "no Lua state" }
        val table = tables.getOrPut(owner) { Table(label) }
        table.label = label
        table.ref?.let { return it }
        val text = table.carried ?: store.read(owner)?.also { table.saved = it }
        table.carried = null
        val json = text?.let { parse(table, it) }
        return host.keepData(json).also { table.ref = it }
    }

    /** Saves [owner]'s table if it changed since it was last saved. */
    fun save(owner: Owner) {
        tables[owner]?.let { save(owner, it) }
    }

    /** Saves every table that changed. */
    fun saveAll() {
        for ((owner, table) in tables) save(owner, table)
    }

    /**
     * The Lua state is closing: every table is saved, and its latest
     * encoding kept for the next state's table to start from (even one too
     * big to save).
     */
    private fun detach() {
        for ((owner, table) in tables) {
            if (table.ref == null) continue
            val text = save(owner, table)
            table.carried = text ?: table.saved
            table.ref = null
        }
    }

    /** [owner] is gone for good (a removed centity): its table goes, from the store too. */
    fun remove(owner: Owner) {
        tables.remove(owner)?.ref?.let { ref -> host()?.unref(ref) }
        store.delete(owner)
    }

    /** Encodes and saves one table; the text it encoded to, or null when it couldn't be encoded. */
    private fun save(owner: Owner, table: Table): String? {
        val ref = table.ref ?: return table.carried
        val host = host() ?: return null
        val encoded = try {
            host.encodeData(ref, "data")
        } catch (e: Exception) {
            warn("Couldn't save ${table.label}'s data: ${e.message}")
            return null
        }
        if (encoded.problems != table.problems) {
            table.problems = encoded.problems
            for (problem in encoded.problems) warn("Saving ${table.label}'s data: $problem")
        }
        val text = encoded.text ?: return null
        val bytes = text.encodeToByteArray().size
        if (bytes > MAX_BYTES) {
            if (!table.tooBig) {
                warn(tooBig("${table.label}'s data", bytes))
            }
            table.tooBig = true
            return text
        }
        table.tooBig = false
        if (text != table.saved) {
            table.saved = text
            store.put(owner, text)
        }
        return text
    }

    /**
     * What was saved, or null to start empty. Only something other than the
     * runtime could have saved anything else; it's left in the store until
     * the table is saved again.
     */
    private fun parse(table: Table, text: String): JsonElement? {
        val json = runCatching { Json.parseToJsonElement(text) }.getOrNull()
        if (json is JsonObject || json is JsonArray) return json
        warn("What the store has for ${table.label}'s data isn't a saved table; it starts empty")
        return null
    }

    companion object {
        /** The most a table may take, encoded: the same as a file (`File:write`). */
        const val MAX_BYTES = 1 shl 20

        /** How often every table that changed is saved: five minutes, as the server's own autosave. */
        const val AUTOSAVE_EVERY = 6000L

        /** Whether JSON [text] takes more than [MAX_BYTES] encoded. */
        fun isTooBig(text: String) = text.length > MAX_BYTES / 4 && text.encodeToByteArray().size > MAX_BYTES

        /** The log line for [what] (`block:data() at world 0 64 0`) encoding to [bytes], past [MAX_BYTES]. */
        fun tooBig(what: String, bytes: Int) =
            "$what takes ${"%.1f".format(bytes / 1048576.0)} MiB, more than the 1 MiB a table may, so it wasn't saved: the last save stands"
    }
}
