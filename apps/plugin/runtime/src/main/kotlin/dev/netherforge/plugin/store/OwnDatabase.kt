package dev.netherforge.plugin.store

import dev.netherforge.plugin.async.WorkFailed
import java.security.MessageDigest
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.time.Instant

/**
 * A package's own database (`nf.db()`) on a MySQL or PostgreSQL connection the server's owner named for it
 * (`own-databases` on a `databases:` entry), so every server of a project shares one: in a space of the package's own,
 * named from its namespace, which every statement of the package's runs in.
 *
 * - **The space.** PostgreSQL: a schema called the namespace, the connection's schema while the package's work runs
 *   (`Connection.setSchema`, its `search_path`). MySQL: a database called `<database>_<namespace>` beside the
 *   configured one (MySQL's schemas are databases), the connection's catalog while the work runs. Either is made the
 *   first time it's needed, and the connection is put back as it was after, since the pool is the connection's, shared
 *   with `nf.db("<name>")`. The space keeps packages' tables apart by name: they share the connection's user, so one
 *   package's SQL can still name another's tables in full; an owner who doesn't trust a package with another's data
 *   gives each a connection and user of its own.
 * - **Migrations** ([migrate]) run here once across every server: under a lock the database holds for the namespace
 *   (`pg_try_advisory_lock`, tried until a deadline; MySQL's `GET_LOCK`), with what was applied kept in the space's `nf_migrations` table, so two
 *   servers starting together apply each step once (the second waits, then finds it done). A step applied there that
 *   this server's copy of the package doesn't have is [MigrationAhead]: a newer copy ran, and this one would work
 *   against tables it doesn't know. Each step runs in a transaction (PostgreSQL's DDL is transactional; MySQL commits
 *   each DDL statement as it runs, so a failed MySQL step can leave part of itself behind, as MySQL always does).
 */
class OwnDatabase internal constructor(
    val namespace: String,
    /** The connection's name in `config.yml`. */
    val connectionName: String,
    private val config: ConnectionConfig,
    private val remote: RemoteDatabase
) : SqlDatabase {
    /** Which server's SQL this is, as migration folders name it: `postgres`, `mysql`. */
    val dialect: String get() = config.type.id

    /** The space's name: the PostgreSQL schema, or the MySQL database. */
    val space: String = if (config.type == DatabaseType.POSTGRES) namespace else "${config.database}_$namespace"

    /** Where it is, as the log and `/nf requires` say it. */
    fun describe() =
        "${config.type.id} connection \"$connectionName\" (${if (config.type == DatabaseType.POSTGRES) "schema" else "database"} $space)"

    @Volatile
    private var made = false

    override fun <T> onLane(work: (Connection) -> T): T = remote.onLane { connection -> inSpace(connection) { work(connection) } }

    private fun <T> inSpace(connection: Connection, work: () -> T): T {
        if (!made) {
            connection.createStatement().use {
                it.execute(
                    if (config.type == DatabaseType.POSTGRES) {
                        "CREATE SCHEMA IF NOT EXISTS ${quoted(space)}"
                    } else {
                        "CREATE DATABASE IF NOT EXISTS ${quoted(space)}"
                    }
                )
            }
            made = true
        }
        return if (config.type == DatabaseType.POSTGRES) {
            val before = connection.schema
            connection.schema = space
            try {
                work()
            } finally {
                runCatching { connection.schema = before }
            }
        } else {
            val before = connection.catalog
            connection.catalog = space
            try {
                work()
            } finally {
                runCatching { connection.catalog = before }
            }
        }
    }

    override fun prepare(connection: Connection, sql: String, forWrite: Boolean): PreparedStatement =
        remote.prepare(connection, sql, forWrite)

    override fun lastInsertId(connection: Connection, statement: PreparedStatement, sql: String): Long? =
        remote.lastInsertId(connection, statement, sql)

    /**
     * Brings the space up to date with [migrations] (this dialect's versions, every one the package has), each once
     * across every server sharing the connection. Throws [MigrationFailed] for a step the database refused (rolled
     * back, the ones before it kept) and [MigrationAhead] for steps applied that [migrations] doesn't have.
     */
    fun migrate(migrations: List<Migration>) {
        // The lock first, on the connection as the pool gave it, then the space: the lock is the database's, not the space's.
        remote.onLane { connection ->
            locked(connection) {
                inSpace(connection) { migrateLocked(connection, migrations) }
            }
        }
    }

    /** [migrate]'s work, holding the lock, in the space. */
    private fun migrateLocked(connection: Connection, migrations: List<Migration>) {
        connection.createStatement().use {
            it.execute(
                "CREATE TABLE IF NOT EXISTS nf_migrations (" +
                    "version INTEGER PRIMARY KEY, name VARCHAR(255) NOT NULL, applied VARCHAR(64) NOT NULL)"
            )
        }
        val done = connection.createStatement().use { statement ->
            statement.executeQuery("SELECT version FROM nf_migrations").use { rows -> buildSet { while (rows.next()) add(rows.getInt(1)) } }
        }
        val known = migrations.map { it.version }.toSet()
        val ahead = done.filter { it !in known }.sorted()
        if (ahead.isNotEmpty()) throw MigrationAhead(ahead)
        for (migration in migrations.sortedBy { it.version }) {
            if (migration.version in done) continue
            try {
                Database.transaction(connection) {
                    for (sql in statementsOf(migration.sql)) connection.createStatement().use { it.execute(sql) }
                    connection.prepareStatement("INSERT INTO nf_migrations (version, name, applied) VALUES (?, ?, ?)").use {
                        it.setInt(1, migration.version)
                        it.setString(2, migration.name)
                        it.setString(3, Instant.now().toString())
                        it.executeUpdate()
                    }
                }
            } catch (e: SQLException) {
                throw MigrationFailed(migration, e)
            }
        }
    }

    /** PostgreSQL runs a text of several statements as one (dollar quotes and all); MySQL takes one at a time. */
    private fun statementsOf(sql: String): List<String> = if (config.type == DatabaseType.POSTGRES) listOf(sql) else SqlText.statements(sql)

    /** [work] while holding the database's lock for this package's migrations, which another server waits for. */
    private fun locked(connection: Connection, work: () -> Unit) {
        if (config.type == DatabaseType.POSTGRES) {
            // Tried, not waited on: a server that hangs mid-migration must not hold this one's start for ever.
            val deadline = System.nanoTime() + LOCK_SECONDS * NANOS_PER_SECOND
            while (true) {
                val got = connection.prepareStatement("SELECT pg_try_advisory_lock(?)").use { statement ->
                    statement.setLong(1, lockKey)
                    statement.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
                }
                if (got) break
                if (System.nanoTime() > deadline) {
                    throw WorkFailed("another server held the migration lock for $namespace for $LOCK_SECONDS seconds")
                }
                Thread.sleep(LOCK_POLL_MILLIS)
            }
            try {
                work()
            } finally {
                runCatching {
                    connection.prepareStatement("SELECT pg_advisory_unlock(?)").use {
                        it.setLong(1, lockKey)
                        it.executeQuery().close()
                    }
                }
            }
        } else {
            val got = connection.prepareStatement("SELECT GET_LOCK(?, ?)").use { statement ->
                statement.setString(1, lockName)
                statement.setInt(2, LOCK_SECONDS)
                statement.executeQuery().use { rows -> rows.next() && rows.getInt(1) == 1 }
            }
            if (!got) throw WorkFailed("another server held the migration lock for $namespace for $LOCK_SECONDS seconds")
            try {
                work()
            } finally {
                runCatching {
                    connection.prepareStatement("SELECT RELEASE_LOCK(?)").use {
                        it.setString(1, lockName)
                        it.executeQuery().close()
                    }
                }
            }
        }
    }

    private val digest: ByteArray by lazy {
        MessageDigest.getInstance("SHA-256").digest("netherforge migrations ${config.database} $namespace".encodeToByteArray())
    }

    /** PostgreSQL's advisory lock key: the first eight bytes of a hash of the database and namespace (a lock is per database there anyway). */
    private val lockKey: Long by lazy { digest.take(8).fold(0L) { key, byte -> (key shl 8) or (byte.toLong() and 0xff) } }

    /** MySQL's lock name, which is server-wide and at most 64 characters: the hash, so two databases' packages never share one. */
    private val lockName: String by lazy { "netherforge." + digest.take(16).joinToString("") { "%02x".format(it) } }

    private fun quoted(name: String) = if (config.type == DatabaseType.POSTGRES) "\"$name\"" else "`$name`"

    private companion object {
        /** How long a MySQL server waits for another to finish migrating before giving up. */
        const val LOCK_SECONDS = 120
        const val LOCK_POLL_MILLIS = 100L
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}

/** Migrations [versions] were applied to a shared database that this server's copy of the package doesn't have. */
class MigrationAhead(val versions: List<Int>) : Exception("migrations ${versions.joinToString { it.toString().padStart(3, '0') }}")
