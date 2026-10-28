package dev.netherforge.plugin.store

import dev.netherforge.plugin.centity.InstanceRecord
import dev.netherforge.plugin.cutscene.PlayerState
import dev.netherforge.plugin.data.ScriptData
import dev.netherforge.plugin.platform.Location
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * The runtime's own state on one server: `plugins/NetherForge/netherforge.db`,
 * one SQLite [Database] for the plugin's life. Spawned centities (the
 * instance index), saved tables (`centity:data()`, `player:data()`, each
 * package's `nf.data(name)`), the permission nodes scripts set and the worlds
 * they created, when each schedule last ran, and what players a cutscene holds were before it. Every row a package persists says whose it is (a `namespace`
 * column, or a name written in full).
 *
 * What isn't here: per-block and per-entity script data (`block:data()`,
 * `entity:data()`), which stays in the chunk's and the entity's persistent
 * data so it travels with them, and node poses, which only memory keeps.
 *
 * Services read what they need when they start ([read][Database.read], which
 * waits) and stage what changes (never waiting); the runtime commits at the
 * end of every tick. The schema is `store/migrations/NNN_name.sql`, applied
 * in order.
 */
class Store private constructor(private val database: Database) : AutoCloseable {
    val file: Path get() = database.file

    /** Writes committed so far: what a test checks to see that nothing was written. */
    val written: Long get() = database.written

    val instances = Instances()
    val tables = Tables()
    val permissions = Permissions()
    val worlds = Worlds()
    val scheduleRuns = ScheduleRuns()
    val cutsceneStates = CutsceneStates()
    val screenMaps = ScreenMaps()

    /** Hands what services staged this tick to the store's lane, as one transaction. */
    fun commit() = database.commit()

    /** Waits until everything staged so far is written. */
    fun flush() = database.flush()

    override fun close() = database.close()

    /** What a write is for, in the log's words: `instances 6f1c…`. */
    private data class Key(val table: String, val id: String) {
        override fun toString() = "$table $id"
    }

    // ---- instances ----------------------------------------------------------------

    inner class Instances {
        /** Every spawned centity the store knows. */
        fun all(): List<InstanceRecord> = database.read { connection ->
            val entities = HashMap<String, MutableList<Triple<String, String, String?>>>()
            connection.query("SELECT instance, entity, role, node FROM instance_entities") { rows ->
                while (rows.next()) {
                    entities.getOrPut(rows.getString(1)) { ArrayList() } += Triple(rows.getString(2), rows.getString(3), rows.getString(4))
                }
            }
            connection.query("SELECT id, centity, world, x, y, z, yaw FROM instances ORDER BY rowid") { rows ->
                buildList {
                    while (rows.next()) {
                        val id = rows.getString(1)
                        val mine = entities[id].orEmpty()
                        fun role(role: String) = mine.filter { it.second == role }
                        add(
                            InstanceRecord(
                                id = UUID.fromString(id),
                                centity = rows.getString(2),
                                anchor = Location(rows.getString(3), rows.getDouble(4), rows.getDouble(5), rows.getDouble(6)),
                                yaw = rows.getDouble(7).takeIf { it.isFinite() } ?: 0.0,
                                displays = role(DISPLAY).associate { it.third.orEmpty() to UUID.fromString(it.first) },
                                hitboxes = role(HITBOX).associate { it.third.orEmpty() to UUID.fromString(it.first) },
                                anchorEntity = role(ANCHOR).firstOrNull()?.let { UUID.fromString(it.first) },
                                orphans = role(ORPHAN).map { UUID.fromString(it.first) }
                            )
                        )
                    }
                }
            }
        }

        /** The instance as [record] says, its entities included. */
        fun put(record: InstanceRecord) = database.stage(Key("instances", record.id.toString())) { connection ->
            val id = record.id.toString()
            connection.update(
                "INSERT INTO instances (id, centity, world, x, y, z, yaw) VALUES (?, ?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT (id) DO UPDATE SET centity = excluded.centity, world = excluded.world, " +
                    "x = excluded.x, y = excluded.y, z = excluded.z, yaw = excluded.yaw",
                id,
                record.centity,
                record.anchor.world,
                record.anchor.x,
                record.anchor.y,
                record.anchor.z,
                record.yaw
            )
            connection.update("DELETE FROM instance_entities WHERE instance = ?", id)
            connection.prepareStatement("INSERT INTO instance_entities (instance, entity, role, node) VALUES (?, ?, ?, ?)").use { insert ->
                fun add(entity: UUID, role: String, node: String?) {
                    insert.bind(id, entity.toString(), role, node)
                    insert.addBatch()
                }
                record.displays.forEach { (node, entity) -> add(entity, DISPLAY, node) }
                record.hitboxes.forEach { (node, entity) -> add(entity, HITBOX, node) }
                record.anchorEntity?.let { add(it, ANCHOR, null) }
                record.orphans.forEach { add(it, ORPHAN, null) }
                insert.executeBatch()
            }
        }

        /** The instance is gone for good. */
        fun delete(id: UUID) = database.stage(Key("instances", id.toString())) { connection ->
            connection.update("DELETE FROM instance_entities WHERE instance = ?", id.toString())
            connection.update("DELETE FROM instances WHERE id = ?", id.toString())
        }
    }

    // ---- saved tables ---------------------------------------------------------------

    inner class Tables {
        /** [owner]'s table as it was last saved (the prelude's JSON), or null when it never was. */
        fun read(owner: ScriptData.Owner): String? = database.read { connection ->
            val (sql, args) = select(owner)
            connection.query(sql, *args) { rows -> if (rows.next()) rows.getString(1) else null }
        }

        fun put(owner: ScriptData.Owner, text: String) = database.stage(key(owner)) { connection ->
            when (owner) {
                is ScriptData.Owner.Centity -> connection.update(
                    "INSERT INTO centity_data (instance, value) VALUES (?, ?) ON CONFLICT (instance) DO UPDATE SET value = excluded.value",
                    owner.instance.toString(),
                    text
                )
                is ScriptData.Owner.Player -> connection.update(
                    "INSERT INTO player_data (player, value) VALUES (?, ?) ON CONFLICT (player) DO UPDATE SET value = excluded.value",
                    owner.player.toString(),
                    text
                )
                is ScriptData.Owner.Named -> connection.update(
                    "INSERT INTO named_data (namespace, name, value) VALUES (?, ?, ?) " +
                        "ON CONFLICT (namespace, name) DO UPDATE SET value = excluded.value",
                    owner.namespace,
                    owner.name,
                    text
                )
            }
        }

        fun delete(owner: ScriptData.Owner) = database.stage(key(owner)) { connection ->
            when (owner) {
                is ScriptData.Owner.Centity -> connection.update("DELETE FROM centity_data WHERE instance = ?", owner.instance.toString())
                is ScriptData.Owner.Player -> connection.update("DELETE FROM player_data WHERE player = ?", owner.player.toString())
                is ScriptData.Owner.Named -> connection.update(
                    "DELETE FROM named_data WHERE namespace = ? AND name = ?",
                    owner.namespace,
                    owner.name
                )
            }
        }

        private fun key(owner: ScriptData.Owner) = when (owner) {
            is ScriptData.Owner.Centity -> Key("centity_data", owner.instance.toString())
            is ScriptData.Owner.Player -> Key("player_data", owner.player.toString())
            is ScriptData.Owner.Named -> Key("named_data", "${owner.namespace}:${owner.name}")
        }

        private fun select(owner: ScriptData.Owner): Pair<String, Array<Any?>> = when (owner) {
            is ScriptData.Owner.Centity -> "SELECT value FROM centity_data WHERE instance = ?" to arrayOf(owner.instance.toString())
            is ScriptData.Owner.Player -> "SELECT value FROM player_data WHERE player = ?" to arrayOf(owner.player.toString())
            is ScriptData.Owner.Named ->
                "SELECT value FROM named_data WHERE namespace = ? AND name = ?" to
                    arrayOf(owner.namespace, owner.name)
        }
    }

    // ---- permissions --------------------------------------------------------------

    inner class Permissions {
        /** Every node [namespace]'s scripts set, by player. */
        fun of(namespace: String): Map<UUID, Map<String, Boolean>> = database.read { connection ->
            connection.query("SELECT player, node, value FROM permissions WHERE namespace = ?", namespace) { rows ->
                val nodes = LinkedHashMap<UUID, MutableMap<String, Boolean>>()
                while (rows.next()) {
                    nodes.getOrPut(UUID.fromString(rows.getString(1))) { LinkedHashMap() }[rows.getString(2)] =
                        rows.getInt(3) != 0
                }
                nodes
            }
        }

        /** Sets [node] on [player] for [namespace], or takes it off when [value] is null. */
        fun set(namespace: String, player: UUID, node: String, value: Boolean?) =
            database.stage(Key("permissions", "$namespace $player $node")) { connection ->
                if (value == null) {
                    connection.update(
                        "DELETE FROM permissions WHERE namespace = ? AND player = ? AND node = ?",
                        namespace,
                        player.toString(),
                        node
                    )
                } else {
                    connection.update(
                        "INSERT INTO permissions (namespace, player, node, value) VALUES (?, ?, ?, ?) " +
                            "ON CONFLICT (namespace, player, node) DO UPDATE SET value = excluded.value",
                        namespace,
                        player.toString(),
                        node,
                        if (value) 1 else 0
                    )
                }
            }
    }

    // ---- worlds -------------------------------------------------------------------

    /**
     * A world a project made: the environment it was made as, the project terrain (`terrain/<id>.json`) that
     * generated it, if one did, and the dimension type (`dimension_types/<id>.json`, as the server knows it: `showcase:deep`) it
     * was made with, if it was.
     */
    data class OwnedWorld(val environment: String, val terrain: String? = null, val dimensionType: String? = null)

    inner class Worlds {
        /** The worlds [namespace]'s scripts created, with how each was made. */
        fun of(namespace: String): Map<String, OwnedWorld> = database.read { connection ->
            connection.query(
                "SELECT world, environment, terrain, dimension_type FROM worlds WHERE namespace = ? ORDER BY rowid",
                namespace
            ) { rows ->
                buildMap { while (rows.next()) put(rows.getString(1), OwnedWorld(rows.getString(2), rows.getString(3), rows.getString(4))) }
            }
        }

        /** [world] is [namespace]'s from now on. */
        fun own(namespace: String, world: String, owned: OwnedWorld) = database.stage(Key("worlds", world)) { connection ->
            connection.update(
                "INSERT INTO worlds (world, namespace, environment, terrain, dimension_type) VALUES (?, ?, ?, ?, ?) " +
                    "ON CONFLICT (world) DO UPDATE SET namespace = excluded.namespace, environment = excluded.environment, " +
                    "terrain = excluded.terrain, dimension_type = excluded.dimension_type",
                world,
                namespace,
                owned.environment,
                owned.terrain,
                owned.dimensionType
            )
        }

        /** [world] is nobody's (it was deleted, or never made). */
        fun forget(world: String) = database.stage(Key("worlds", world)) { connection ->
            connection.update("DELETE FROM worlds WHERE world = ?", world)
        }
    }

    // ---- schedules ----------------------------------------------------------------

    inner class ScheduleRuns {
        /** When each of [namespace]'s schedules last ran (Unix milliseconds), by id. */
        fun of(namespace: String): Map<String, Long> = database.read { connection ->
            connection.query("SELECT id, last_run FROM schedule_runs WHERE namespace = ?", namespace) { rows ->
                buildMap { while (rows.next()) put(rows.getString(1), rows.getLong(2)) }
            }
        }

        /** [namespace]'s schedule [id] last ran at [time] (Unix milliseconds). */
        fun ran(namespace: String, id: String, time: Long) = database.stage(Key("schedule_runs", "$namespace $id")) { connection ->
            connection.update(
                "INSERT INTO schedule_runs (namespace, id, last_run) VALUES (?, ?, ?) " +
                    "ON CONFLICT (namespace, id) DO UPDATE SET last_run = excluded.last_run",
                namespace,
                id,
                time
            )
        }
    }

    // ---- cutscenes ----------------------------------------------------------------

    inner class CutsceneStates {
        /** What every player a cutscene is holding was before it, by player. */
        fun all(): Map<UUID, PlayerState> = database.read { connection ->
            val columns = "player, world, x, y, z, yaw, pitch, game_mode, can_fly, flying, spectating"
            connection.query("SELECT $columns FROM cutscene_states") { rows ->
                buildMap {
                    while (rows.next()) {
                        put(
                            UUID.fromString(rows.getString(1)),
                            PlayerState(
                                location = Location(
                                    rows.getString(2),
                                    rows.getDouble(3),
                                    rows.getDouble(4),
                                    rows.getDouble(5),
                                    rows.getDouble(6),
                                    rows.getDouble(7)
                                ),
                                gameMode = rows.getString(8),
                                canFly = rows.getInt(9) != 0,
                                flying = rows.getInt(10) != 0,
                                spectating = rows.getString(11)?.let(UUID::fromString)
                            )
                        )
                    }
                }
            }
        }

        /** [player] is held by a cutscene, and was [state] before it. */
        fun put(player: UUID, state: PlayerState) = database.stage(Key("cutscene_states", player.toString())) { connection ->
            connection.update(
                "INSERT INTO cutscene_states (player, world, x, y, z, yaw, pitch, game_mode, can_fly, flying, spectating) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (player) DO UPDATE SET world = excluded.world, " +
                    "x = excluded.x, y = excluded.y, z = excluded.z, yaw = excluded.yaw, pitch = excluded.pitch, " +
                    "game_mode = excluded.game_mode, can_fly = excluded.can_fly, flying = excluded.flying, " +
                    "spectating = excluded.spectating",
                player.toString(),
                state.location.world,
                state.location.x,
                state.location.y,
                state.location.z,
                state.location.yaw,
                state.location.pitch,
                state.gameMode,
                state.canFly,
                state.flying,
                state.spectating?.toString()
            )
        }

        /** [player] is back as they were: nothing is left to put back. */
        fun delete(player: UUID) = database.stage(Key("cutscene_states", player.toString())) { connection ->
            connection.update("DELETE FROM cutscene_states WHERE player = ?", player.toString())
        }
    }

    /** The numbers screens' maps keep for good (`screen/Screens.kt`), by package and screen. */
    inner class ScreenMaps {
        /** Every screen's maps: (namespace, screen) to its map numbers by tile. */
        fun all(): Map<Pair<String, String>, Map<Int, Int>> = database.read { connection ->
            connection.query("SELECT namespace, screen, tile, map FROM screen_maps") { rows ->
                val out = HashMap<Pair<String, String>, MutableMap<Int, Int>>()
                while (rows.next()) out.getOrPut(rows.getString(1) to rows.getString(2)) { HashMap() }[rows.getInt(3)] = rows.getInt(4)
                out
            }
        }

        /** The next map number to give: past every one ever given; null before the first. */
        fun next(): Int? = database.read { connection ->
            connection.query("SELECT next FROM screen_map_next WHERE id = 0") { rows -> if (rows.next()) rows.getInt(1) else null }
        }

        /** Every number below [next] has been given. */
        fun setNext(next: Int) = database.stage(Key("screen_map_next", "0")) { connection ->
            connection.update(
                "INSERT INTO screen_map_next (id, next) VALUES (0, ?) ON CONFLICT (id) DO UPDATE SET next = excluded.next",
                next
            )
        }

        /**
         * Map [tile] of [screen] (in [namespace]) gives up its number, which is never given again. Keyed as [put] is,
         * so a screen made again with the key in the same tick keeps the numbers it gets then.
         */
        fun forget(namespace: String, screen: String, tile: Int) =
            database.stage(Key("screen_maps", "$namespace:$screen:$tile")) { connection ->
                connection.update("DELETE FROM screen_maps WHERE namespace = ? AND screen = ? AND tile = ?", namespace, screen, tile)
            }

        /** Map [tile] of [screen] (in [namespace]) is [map], for good. */
        fun put(namespace: String, screen: String, tile: Int, map: Int) =
            database.stage(Key("screen_maps", "$namespace:$screen:$tile")) { connection ->
                connection.update(
                    "INSERT INTO screen_maps (namespace, screen, tile, map) VALUES (?, ?, ?, ?) " +
                        "ON CONFLICT (namespace, screen, tile) DO UPDATE SET map = excluded.map",
                    namespace,
                    screen,
                    tile,
                    map
                )
            }
    }

    // ---- looking at it: /nf data ------------------------------------------------------

    /** A table's size: its rows, and how many are each namespace's where rows say. */
    data class TableSize(val table: String, val rows: Int, val byNamespace: Map<String, Int>)

    /** Every table and how many rows it has, after what's staged is written. */
    fun sizes(): List<TableSize> = database.read { connection ->
        tables(connection).map { table ->
            val rows = connection.query("SELECT count(*) FROM \"$table\"") {
                it.next()
                it.getInt(1)
            }
            val byNamespace = if ("namespace" in columns(connection, table)) {
                connection.query("SELECT namespace, count(*) FROM \"$table\" GROUP BY namespace ORDER BY namespace") { result ->
                    buildMap { while (result.next()) put(result.getString(1), result.getInt(2)) }
                }
            } else {
                emptyMap()
            }
            TableSize(table, rows, byNamespace)
        }
    }

    /** The tables there are (the migrations' record included), by name. */
    fun tableNames(): List<String> = database.read(::tables)

    /** Up to [limit] of [table]'s rows, each column's value as a string (null as `null`), and how many there are in all. */
    fun rows(table: String, limit: Int): Pair<List<Map<String, String>>, Int> = database.read { connection ->
        require(table in tables(connection)) { "there's no table \"$table\" in the store" }
        val total = connection.query("SELECT count(*) FROM \"$table\"") {
            it.next()
            it.getInt(1)
        }
        val rows = connection.query("SELECT * FROM \"$table\" ORDER BY rowid LIMIT ?", limit) { result ->
            buildList {
                val meta = result.metaData
                while (result.next()) {
                    add((1..meta.columnCount).associate { meta.getColumnName(it) to (result.getString(it) ?: "null") })
                }
            }
        }
        rows to total
    }

    /**
     * Writes every table, every row, into [file] as JSON (saved tables' values
     * as the JSON they are), off the main thread, after what's staged is
     * written. The stage completes with [file].
     */
    fun export(file: Path): CompletableFuture<Path> = database.submit { connection ->
        val tables = JsonObject(
            tables(connection).associateWith { table ->
                connection.query("SELECT * FROM \"$table\" ORDER BY rowid") { result ->
                    JsonArray(buildList { while (result.next()) add(row(table, result)) })
                }
            }
        )
        val export = JsonObject(
            mapOf(
                "store" to JsonPrimitive(database.file.toString()),
                "exported" to JsonPrimitive(Instant.now().toString()),
                "tables" to tables
            )
        )
        Files.createDirectories(file.parent)
        val temp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.writeString(temp, PRETTY.encodeToString(JsonElement.serializer(), export))
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        file
    }

    private fun row(table: String, result: ResultSet): JsonObject {
        val meta = result.metaData
        return JsonObject(
            (1..meta.columnCount).associate { column ->
                val name = meta.getColumnName(column)
                name to when {
                    result.getObject(column) == null -> JsonNull
                    "$table.$name" in JSON_COLUMNS -> runCatching { Json.parseToJsonElement(result.getString(column)) }
                        .getOrElse { JsonPrimitive(result.getString(column)) }
                    meta.getColumnType(column) in NUMBERS -> JsonPrimitive(
                        result.getDouble(column).let {
                            if (it % 1.0 ==
                                0.0
                            ) {
                                it.toLong()
                            } else {
                                it
                            }
                        }
                    )
                    else -> JsonPrimitive(result.getString(column))
                }
            }
        )
    }

    private fun tables(connection: Connection): List<String> =
        connection.query("SELECT name FROM sqlite_schema WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name") { rows ->
            buildList { while (rows.next()) add(rows.getString(1)) }
        }

    private fun columns(connection: Connection, table: String): List<String> =
        connection.query("SELECT name FROM pragma_table_info(?)", table) { rows ->
            buildList { while (rows.next()) add(rows.getString(1)) }
        }

    companion object {
        /** The store's file, in the plugin's folder. */
        const val FILE = "netherforge.db"

        /** The migrations' scope for the runtime's own tables. */
        private const val SCOPE = "netherforge"

        /** The runtime's schema, in order: `store/migrations/` in the jar. */
        private val MIGRATIONS = listOf("001_init.sql")

        private const val DISPLAY = "display"
        private const val HITBOX = "hitbox"
        private const val ORPHAN = "orphan"
        private const val ANCHOR = "anchor"

        /** Columns holding JSON, exported as the JSON they hold. */
        private val JSON_COLUMNS = setOf("centity_data.value", "player_data.value", "named_data.value")

        private val NUMBERS = setOf(Types.INTEGER, Types.BIGINT, Types.REAL, Types.DOUBLE, Types.FLOAT, Types.NUMERIC)

        private val PRETTY = Json { prettyPrint = true }

        /** Opens (or makes) the store in [directory], its schema brought up to date, its connection used only on [lane]. */
        fun open(directory: Path, lane: Executor, report: Database.Report): Store {
            val database = Database.open(directory.resolve(FILE), lane, report)
            try {
                database.migrate(SCOPE, MIGRATIONS.map { Migration.of(it, resource("migrations/$it")) })
            } catch (e: Exception) {
                database.close()
                throw e
            }
            return Store(database)
        }

        private fun resource(path: String): String =
            requireNotNull(Store::class.java.getResourceAsStream(path)) { "the store's $path is missing from the jar" }.use {
                it.readBytes().decodeToString()
            }
    }
}

/** Binds [args] to [this]'s parameters in order (null as SQL NULL). */
internal fun java.sql.PreparedStatement.bind(vararg args: Any?) {
    args.forEachIndexed { index, value ->
        when (value) {
            null -> setNull(index + 1, Types.NULL)
            is String -> setString(index + 1, value)
            is Int -> setInt(index + 1, value)
            is Long -> setLong(index + 1, value)
            is Double -> setDouble(index + 1, value)
            is Boolean -> setInt(index + 1, if (value) 1 else 0)
            else -> error("can't bind a ${value::class.simpleName}")
        }
    }
}

/** Runs [sql] with [args] bound; how many rows it changed. */
internal fun Connection.update(sql: String, vararg args: Any?): Int = prepareStatement(sql).use {
    it.bind(*args)
    it.executeUpdate()
}

/** Runs the query [sql] with [args] bound and reads its rows with [read]. */
internal fun <T> Connection.query(sql: String, vararg args: Any?, read: (ResultSet) -> T): T = prepareStatement(sql).use { statement ->
    statement.bind(*args)
    statement.executeQuery().use(read)
}
