package dev.netherforge.plugin.store

import dev.netherforge.plugin.async.WorkFailed
import dev.netherforge.plugin.async.Workers
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.Statement
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import javax.sql.DataSource

/**
 * The MySQL and PostgreSQL servers the owner named in `config.yml`'s
 * `databases:` (`nf.db("network")`), one pool of connections each, shared by
 * every package granted it.
 *
 * **Who may use one.** [problem] is the one check, made when a script asks for
 * the connection ([dev.netherforge.plugin.api.NfImpl.db]): the name must be
 * one the owner set up (and could be read), and the calling package's
 * namespace must be in its `packages:` list. A package's own `requires.db` is
 * checked before that, by the generated binding like every capability.
 *
 * **Lanes.** A handle's work runs on a lane of the workers named for its
 * package and connection, so one package's statements to one connection run
 * in the order they were given (as W2.2's `db:execute` before the next
 * `db:query` promises) and never wait for another package's. The pool, not
 * the lane, bounds how many run at once against the server; a statement waits
 * for a free connection (at most ten seconds) before it fails.
 *
 * **Pools are lazy and never block the main thread:** one is made the first
 * time a handle's work asks for it (Hikari connects in the background), and
 * closed with the plugin.
 */
class RemoteDatabases(
    private val config: DatabasesConfig,
    private val workers: () -> Workers,
    private val report: Database.Report,
    /** How a connection's pool is made: HikariCP by default; tests give an embedded database. */
    private val sources: (String, ConnectionConfig) -> DataSource = HikariSources::pool
) : AutoCloseable {
    private val open = ConcurrentHashMap<String, RemoteDatabase>()

    /** The names the owner set up, for the log. */
    fun names(): List<String> = config.connections.keys.toList()

    /** One line per connection for the log when the plugin starts: what it is and who may use it, never the password. */
    fun describe(): List<String> = config.connections.map { (name, connection) ->
        "$name: ${connection.type.id} at ${connection.host}:${connection.port}, for " +
            (connection.packages.sorted().joinToString().ifEmpty { "no package yet" })
    }

    /** What's wrong with the configuration itself, by connection name, for the log. */
    fun invalid(): Map<String, String> = config.invalid

    /**
     * Why [namespace]'s scripts can't open [name], or null when they can. Says
     * what the script needs to hear (never a host, a user or a password).
     */
    fun problem(name: String, namespace: String): String? {
        config.invalid[name]?.let {
            return "the server's connection \"$name\" can't be used: $it (the owner fixes it in config.yml's databases)"
        }
        val connection = config.connections[name]
        if (connection == null) {
            val known = names().sorted().joinToString()
            return "there's no database connection called \"$name\"" +
                if (known.isEmpty()) ": the server's owner hasn't set any up" else " (the server's owner has set up: $known)"
        }
        if (namespace !in connection.packages) {
            return "the connection \"$name\" isn't open to package \"$namespace\": the server's owner lists the packages that may use it " +
                "under `databases.$name.packages` in the plugin's config.yml"
        }
        return null
    }

    /** The connection [name], for work given to [lane]. Only for a name [problem] let through. */
    fun database(name: String): SqlDatabase = open[name] ?: synchronized(open) {
        open.getOrPut(name) { RemoteDatabase(name, config.connections.getValue(name), sources, report) }
    }

    /** The lane [namespace]'s work for connection [name] runs on. */
    fun lane(namespace: String, name: String): Executor = workers().lane("database:$namespace:$name")

    override fun close() {
        synchronized(open) {
            for (database in open.values) database.close()
            open.clear()
        }
    }
}

/**
 * One named connection: a pool, made on first use. Its [SqlDatabase] is
 * server-neutral JDBC, with the two things the servers do differently kept
 * here: the key a statement generated, and a time limit on every statement
 * (a script's query to a server that stopped answering must not hold a
 * worker forever).
 */
class RemoteDatabase internal constructor(
    private val name: String,
    private val config: ConnectionConfig,
    private val sources: (String, ConnectionConfig) -> DataSource,
    private val report: Database.Report
) : SqlDatabase,
    AutoCloseable {
    private val lock = Any()
    private var source: DataSource? = null

    private fun source(): DataSource = source ?: synchronized(lock) { source ?: sources(name, config).also { source = it } }

    override fun <T> onLane(work: (Connection) -> T): T {
        val connection = try {
            source().connection
        } catch (e: SQLException) {
            // The reason has the server's address in it: the log gets it, the script is told only which connection.
            report.error("NetherForge couldn't reach the database connection \"$name\" ($config)", e)
            throw WorkFailed("the database connection \"$name\" couldn't be reached (the server's log says why)")
        }
        return connection.use(work)
    }

    override fun prepare(connection: Connection, sql: String, forWrite: Boolean): PreparedStatement {
        val keys = forWrite && generatesKeys(sql)
        val statement = if (keys) connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS) else connection.prepareStatement(sql)
        statement.queryTimeout = STATEMENT_SECONDS
        return statement
    }

    override fun lastInsertId(connection: Connection, statement: PreparedStatement, sql: String): Long? {
        if (!generatesKeys(sql)) return null
        return statement.generatedKeys.use { keys -> if (keys.next()) (keys.getObject(1) as? Number)?.toLong() else null }
    }

    /**
     * Whether [sql] is an insert on a server that reports the key it generated. MySQL does, for `INSERT` and `REPLACE` (a
     * statement that inserted nothing may leave an earlier one's key behind, so it isn't asked). PostgreSQL has no such key: its
     * `RETURNING` is a query's rows, and asking for generated keys would rewrite the statement.
     */
    private fun generatesKeys(sql: String) = config.type == DatabaseType.MYSQL && INSERT.containsMatchIn(sql)

    override fun close() {
        synchronized(lock) {
            (source as? AutoCloseable)?.close()
            source = null
        }
    }

    private companion object {
        private val INSERT = Regex("^\\s*(?:insert|replace)\\b", RegexOption.IGNORE_CASE)

        /** A statement that takes longer than this fails: longer than any query a script should run on the server's thread pool. */
        const val STATEMENT_SECONDS = 30
    }
}

/**
 * The pool of every connection a server has: HikariCP, loaded only once something asks for a remote database. Neither it nor
 * PostgreSQL's driver is Paper's: the plugin's loader fetches them at server start, when `config.yml` already names a
 * connection that needs them ([missing] says so when it didn't).
 */
internal object HikariSources {
    private const val POOL = "com.zaxxer.hikari.HikariDataSource"

    /** What's wrong when the libraries a connection of [type] needs aren't on the server, or null when they are. */
    fun missing(type: DatabaseType, classLoader: ClassLoader = HikariSources::class.java.classLoader): String? {
        val absent = listOf("the connection pool (HikariCP)" to POOL, "the ${type.id} driver" to type.driver)
            .filterNot { (_, className) -> classLoader.has(className) }
            .map { (what, _) -> what }
        if (absent.isEmpty()) return null
        return "${absent.joinToString(" and ")} isn't on this server: NetherForge's loader downloads it when the server starts " +
            "with the connection already in config.yml's databases, so restart the server (and check that it can reach Maven Central)"
    }

    private fun ClassLoader.has(className: String) = try {
        Class.forName(className, false, this)
        true
    } catch (_: ClassNotFoundException) {
        false
    }

    fun pool(name: String, config: ConnectionConfig): DataSource {
        missing(config.type)?.let { throw WorkFailed("the database connection \"$name\" can't be opened: $it") }
        val pool = com.zaxxer.hikari.HikariConfig()
        pool.poolName = "netherforge-$name"
        pool.jdbcUrl = config.jdbcUrl
        pool.driverClassName = config.type.driver
        pool.username = config.username
        pool.password = config.password
        pool.maximumPoolSize = config.poolSize
        pool.minimumIdle = 1
        pool.connectionTimeout = CONNECT_MILLIS
        // A server that's down when the plugin starts doesn't fail it: the first statement says so, and the next tries again.
        pool.initializationFailTimeout = -1
        for ((key, value) in config.properties) pool.addDataSourceProperty(key, value)
        return com.zaxxer.hikari.HikariDataSource(pool)
    }

    /** How long a statement waits for a connection, a new one included. */
    private const val CONNECT_MILLIS = 10_000L
}
