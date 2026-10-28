package dev.netherforge.plugin.api

import dev.netherforge.plugin.async.WorkFailed
import dev.netherforge.plugin.binding.DatabaseApi
import dev.netherforge.plugin.binding.DatabaseResult
import dev.netherforge.plugin.binding.DatabaseStatement
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.lua.LuaValue
import dev.netherforge.plugin.session.ProjectSession
import dev.netherforge.plugin.store.Cells
import dev.netherforge.plugin.store.Database
import dev.netherforge.plugin.store.SqlDatabase
import dev.netherforge.plugin.store.SqlText
import dev.netherforge.plugin.store.bind
import party.iroiro.luajava.Lua
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor

/**
 * `Database`: the calling package's own SQLite database (`nf.db()`), or a
 * MySQL or PostgreSQL connection the server's owner named (`nf.db("network")`).
 * The handle is one class and this one implementation: what differs between
 * them is a [SqlDatabase] ([dev.netherforge.plugin.store.Database] or
 * [dev.netherforge.plugin.store.RemoteDatabase]) and the lane the work runs on.
 *
 * Everything a script could get wrong in how it asks (SQL that isn't one
 * statement, a value SQLite can't hold, a database that isn't its package's)
 * is an error at its line, found on the main thread before anything starts.
 * What only the database can say (a syntax error, a constraint, a wrong
 * number of values, a package whose migrations failed) is the work's failure,
 * `nil, err` to the script. The work runs on the database's own lane
 * ([dev.netherforge.plugin.store.PackageDatabases.lane]), so one package's
 * statements run in the order they were given and never in the way of
 * another's, and the workers bound how much of it a script can queue.
 */
internal class DatabaseImpl(private val session: ProjectSession) : DatabaseApi {
    private val databases get() = session.runtime.databases
    private val remoteDatabases get() = session.runtime.remoteDatabases

    override fun query(self: LuaHandle.Database, sql: String, params: LuaValue?): CompletionStage<List<Map<String, Any?>>> {
        val statement = statement(self, "Database:query", sql, params)
        return run(self) { database, connection -> statement.rows(database, connection) }
    }

    override fun execute(self: LuaHandle.Database, sql: String, params: LuaValue?): CompletionStage<DatabaseResult> {
        val statement = statement(self, "Database:execute", sql, params)
        return run(self) { database, connection -> statement.execute(database, connection) }
    }

    override fun transaction(self: LuaHandle.Database, statements: List<DatabaseStatement>): CompletionStage<List<DatabaseResult>> {
        val checked = statements.mapIndexed { index, it ->
            statement(self, "Database:transaction (statement ${index + 1})", it.sql, it.params)
        }
        return run(self) { database, connection ->
            var failed = 0
            try {
                val results = ArrayList<DatabaseResult>()
                Database.transaction(connection) {
                    for ((index, statement) in checked.withIndex()) {
                        failed = index + 1
                        results += statement.execute(database, connection)
                    }
                }
                results
            } catch (e: WorkFailed) {
                throw WorkFailed("statement $failed failed, so nothing was changed: ${e.message}")
            } catch (e: SQLException) {
                throw WorkFailed("statement $failed failed, so nothing was changed: ${message(e)}")
            }
        }
    }

    /** One checked statement: its text and the values it binds. */
    private inner class Statement(val sql: String, val values: List<Any?>) {
        private fun <T> prepared(
            database: SqlDatabase,
            connection: Connection,
            forWrite: Boolean,
            use: (java.sql.PreparedStatement) -> T
        ): T = try {
            database.prepare(connection, sql, forWrite).use { statement ->
                val wanted = statement.parameterMetaData.parameterCount
                if (values.size > wanted) throw WorkFailed("the statement has $wanted ? but ${values.size} values were given")
                // A Lua list can't say its last values are nil (`{ name, nil }` is `{ name }`), so those not given are NULL.
                statement.bind(*(values + List(wanted - values.size) { null }).toTypedArray())
                use(statement)
            }
        } catch (e: SQLException) {
            throw WorkFailed(message(e))
        }

        fun rows(database: SqlDatabase, connection: Connection): List<Map<String, Any?>> =
            prepared(database, connection, false) { statement ->
                if (!statement.execute()) return@prepared emptyList()
                statement.resultSet.use { rows ->
                    val columns = rows.metaData
                    val names = (1..columns.columnCount).map { columns.getColumnLabel(it) }
                    buildList {
                        while (rows.next()) {
                            val row = LinkedHashMap<String, Any?>()
                            for ((index, name) in names.withIndex()) {
                                row[name] = try {
                                    Cells.lua(rows.getObject(index + 1))
                                } catch (_: Cells.Blob) {
                                    throw WorkFailed("column \"$name\" holds a blob, which a script can't read: select hex($name) instead")
                                }
                            }
                            add(row)
                        }
                    }
                }
            }

        fun execute(database: SqlDatabase, connection: Connection): DatabaseResult = prepared(database, connection, true) { statement ->
            val rows = statement.execute()
            val changes = if (rows) 0L else statement.updateCount.toLong()
            DatabaseResult(changes, database.lastInsertId(connection, statement, sql))
        }
    }

    /** [sql] and [params] as the one statement they must be, for [self] (the calling package's own database), before anything starts. */
    private fun statement(self: LuaHandle.Database, what: String, sql: String, params: LuaValue?): Statement {
        // No check of who calls: a handle can only come from its package's own `nf.db()` (or a package that was handed one),
        // and a package's module is called from other packages' scripts all the time.
        SqlText.problem(sql)?.let { throw LuaApiException("$what: $it") }
        return Statement(sql, values(params, what))
    }

    /** What `params` binds, in order: a hole is NULL. Anything SQLite has no value for is an error. */
    private fun values(params: LuaValue?, what: String): List<Any?> {
        if (params == null) return emptyList()
        return params.read { lua, index ->
            val found = HashMap<Int, Any?>()
            var last = 0
            lua.pushNil()
            while (lua.next(index) != 0) {
                val key = lua.top - 1
                val value = lua.top
                val position = if (lua.type(key) == Lua.LuaType.NUMBER && lua.isInteger(key)) lua.toInteger(key) else 0
                if (position < 1 || position > MAX_VALUES) {
                    lua.pop(2)
                    throw LuaApiException(
                        "$what: params is a list of values, one for each ?, but it has a key that isn't a position from 1 to $MAX_VALUES"
                    )
                }
                found[position.toInt()] = when (lua.type(value)) {
                    Lua.LuaType.STRING -> lua.toString(value)
                    Lua.LuaType.NUMBER -> if (lua.isInteger(value)) lua.toInteger(value) else lua.toNumber(value)
                    Lua.LuaType.BOOLEAN -> lua.toBoolean(value)
                    else -> {
                        val type = lua.type(value)?.name?.lowercase()
                        lua.pop(2)
                        throw LuaApiException(
                            "$what: params[$position] is a $type, which SQL has no value for (a string, number or boolean)"
                        )
                    }
                }
                last = maxOf(last, position.toInt())
                lua.pop(1)
            }
            (1..last).map { found[it] }
        }
    }

    /**
     * Runs [work] on [self]'s database's lane: the package's own, once its migrations have been applied, or a named connection
     * (which the handle's `nf.db(name)` already checked). Its [SQLException]s are the failure.
     */
    private fun <T> run(self: LuaHandle.Database, work: (SqlDatabase, Connection) -> T): CompletionStage<T> {
        val remote = self.connection.isNotEmpty()
        val database: SqlDatabase
        val lane: Executor
        if (remote) {
            database = remoteDatabases.database(self.connection)
            lane = remoteDatabases.lane(self.namespace, self.connection)
        } else {
            session.schemas.unavailable(self.namespace)?.let { return CompletableFuture.failedFuture(WorkFailed(it)) }
            database = try {
                databases.database(self.namespace)
            } catch (e: Exception) {
                return CompletableFuture.failedFuture(WorkFailed("the database of ${self.namespace} couldn't be opened: ${e.message}"))
            }
            lane = databases.lane(self.namespace)
        }
        return session.async.submit(lane) {
            try {
                database.onLane { connection -> work(database, connection) }
            } catch (e: SQLException) {
                throw WorkFailed(message(e))
            }
        }
    }

    private fun message(e: SQLException): String = e.message.orEmpty()

    private companion object {
        /** SQLite's default limit on `?`s in one statement. */
        const val MAX_VALUES = 32_766L
    }
}
