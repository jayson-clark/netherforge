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
 * - **The space.** PostgreSQL: a schema called the namespace. MySQL: a database called `<database>_<namespace>` beside
 *   the configured one (MySQL's schemas are databases), the connection's catalog while the work runs, put back after
 *   since the pool is the connection's, shared with `nf.db("<name>")`. Either is made the first time it's needed. The
 *   space keeps packages' tables apart by name: they share the connection's user, so one package's SQL can still name
 *   another's tables in full; an owner who doesn't trust a package with another's data gives each a connection and
 *   user of its own.
 * - **Behind a transaction pooler.** On PostgreSQL nothing the package's work needs is session state, because a
 *   pooler (PgBouncer's `pool_mode = transaction`) may run each transaction on another backend and never resets one:
 *   every unit of work ([onLane]: a `db:query`, a `db:execute`, a whole `db:transaction`) is one transaction that
 *   starts by scoping the schema to itself (`set_config('search_path', …, true)`, which is `SET LOCAL`), and every
 *   lock is a transaction's (`pg_try_advisory_xact_lock`), gone at commit or rollback on the backend that took it.
 *   The same holds on a direct connection, so there's one way for both. MySQL keeps per-connection state (its catalog,
 *   `GET_LOCK`): a pooler there isn't common, and ProxySQL pins a connection that changes it.
 * - **Migrations** ([migrate]) run here once across every server: under a lock the database holds for the namespace,
 *   with what was applied kept in the space's `nf_migrations` table, so two servers starting together apply each step
 *   once (the second waits, then finds it done). A step applied there that this server's copy of the package doesn't
 *   have is [MigrationAhead]: a newer copy ran, and this one would work against tables it doesn't know. Each step runs
 *   in a transaction, the ones before a failed step kept. PostgreSQL: each step's transaction takes the lock (tried
 *   until a deadline), reads `nf_migrations`, applies the step if it's still missing and records it, so the lock,
 *   the step and its row commit together. MySQL: `GET_LOCK` around them all, and since MySQL commits each DDL
 *   statement as it runs, a failed MySQL step can leave part of itself behind, as MySQL always does.
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
    fun describe() = "${config.type.id} connection \"$connectionName\" (${if (postgres) "schema" else "database"} $space)"

    @Volatile
    private var made = false

    private val postgres = config.type == DatabaseType.POSTGRES

    override fun <T> onLane(work: (Connection) -> T): T = remote.onLane { connection ->
        if (postgres) inSchema(connection) { work(connection) } else inSpace(connection) { work(connection) }
    }

    /**
     * [work] as one PostgreSQL transaction in the schema (made first, under the lock, when this server hasn't yet).
     * `db:transaction`'s own transaction joins it ([Database.transaction]).
     */
    private fun <T> inSchema(connection: Connection, work: () -> T): T {
        if (!made) {
            Database.transaction(connection) {
                lockForTransaction(connection)
                makeSchema(connection)
            }
            made = true
        }
        return Database.transaction(connection) {
            scope(connection)
            work()
        }
    }

    /** The schema, made if it isn't there: under [lockForTransaction], so two servers making it at once can't collide. */
    private fun makeSchema(connection: Connection) {
        connection.createStatement().use { it.execute("CREATE SCHEMA IF NOT EXISTS ${quoted(space)}") }
    }

    /** The schema as the open transaction's `search_path`, for that transaction only (`SET LOCAL`, with the name bound). */
    private fun scope(connection: Connection) {
        connection.prepareStatement("SELECT set_config('search_path', ?, true)").use {
            it.setString(1, quoted(space))
            it.executeQuery().close()
        }
    }

    /** MySQL: [work] with the space as the connection's catalog, put back after. */
    private fun <T> inSpace(connection: Connection, work: () -> T): T {
        if (!made) {
            connection.createStatement().use { it.execute("CREATE DATABASE IF NOT EXISTS ${quoted(space)}") }
            made = true
        }
        val before = connection.catalog
        connection.catalog = space
        return try {
            work()
        } finally {
            runCatching { connection.catalog = before }
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
        remote.onLane { connection ->
            if (postgres) {
                migratePostgres(connection, migrations)
            } else {
                // The lock first, on the connection as the pool gave it, then the space: the lock is the database's, not the space's.
                locked(connection) {
                    inSpace(connection) {
                        makeTable(connection)
                        val done = applied(connection)
                        checkAhead(done, migrations)
                        for (migration in migrations.sortedBy { it.version }) {
                            if (migration.version in done) continue
                            Database.transaction(connection) { apply(connection, migration) }
                        }
                    }
                }
            }
        }
    }

    /**
     * [migrate] on PostgreSQL: a transaction that makes the schema and `nf_migrations`, then one per step this server
     * found missing, each under the lock and checking again (another server may have applied it in between).
     */
    private fun migratePostgres(connection: Connection, migrations: List<Migration>) {
        val missing = locked(connection, make = true) { done ->
            checkAhead(done, migrations)
            migrations.filter { it.version !in done }.sortedBy { it.version }
        }
        made = true
        for (migration in missing) {
            locked(connection, make = false) { done ->
                checkAhead(done, migrations)
                if (migration.version !in done) apply(connection, migration)
            }
        }
    }

    /**
     * [work] in a PostgreSQL transaction of its own, holding the migration lock, in the schema ([make]: made first, with
     * `nf_migrations`), given the versions applied: committed with what it did, or rolled back.
     */
    private fun <T> locked(connection: Connection, make: Boolean, work: (Set<Int>) -> T): T = Database.transaction(connection) {
        lockForTransaction(connection)
        if (make) makeSchema(connection)
        scope(connection)
        if (make) makeTable(connection)
        work(applied(connection))
    }

    private fun makeTable(connection: Connection) {
        connection.createStatement().use {
            it.execute(
                "CREATE TABLE IF NOT EXISTS nf_migrations (" +
                    "version INTEGER PRIMARY KEY, name VARCHAR(255) NOT NULL, applied VARCHAR(64) NOT NULL)"
            )
        }
    }

    /** The versions applied in the space. */
    private fun applied(connection: Connection): Set<Int> = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT version FROM nf_migrations").use { rows -> buildSet { while (rows.next()) add(rows.getInt(1)) } }
    }

    /** Throws [MigrationAhead] for versions in [done] that [migrations] doesn't have. */
    private fun checkAhead(done: Set<Int>, migrations: List<Migration>) {
        val known = migrations.map { it.version }.toSet()
        val ahead = done.filter { it !in known }.sorted()
        if (ahead.isNotEmpty()) throw MigrationAhead(ahead)
    }

    /** [migration]'s statements and its `nf_migrations` row, in the transaction open on [connection]. */
    private fun apply(connection: Connection, migration: Migration) {
        try {
            for (sql in statementsOf(migration.sql)) connection.createStatement().use { it.execute(sql) }
            connection.prepareStatement("INSERT INTO nf_migrations (version, name, applied) VALUES (?, ?, ?)").use {
                it.setInt(1, migration.version)
                it.setString(2, migration.name)
                it.setString(3, Instant.now().toString())
                it.executeUpdate()
            }
        } catch (e: SQLException) {
            throw MigrationFailed(migration, e)
        }
    }

    /** PostgreSQL runs a text of several statements as one (dollar quotes and all); MySQL takes one at a time. */
    private fun statementsOf(sql: String): List<String> = if (postgres) listOf(sql) else SqlText.statements(sql)

    /**
     * The database's lock for this package's migrations (and for making its schema), held by the transaction open on
     * [connection] until it commits or rolls back, on whichever backend that is. Tried, not waited on: a server that
     * hangs mid-migration must not hold this one's start for ever.
     */
    private fun lockForTransaction(connection: Connection) {
        val deadline = System.nanoTime() + LOCK_SECONDS * NANOS_PER_SECOND
        while (true) {
            val got = connection.prepareStatement("SELECT pg_try_advisory_xact_lock(?)").use { statement ->
                statement.setLong(1, lockKey)
                statement.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
            }
            if (got) return
            if (System.nanoTime() > deadline) {
                throw WorkFailed("another server held the migration lock for $namespace for $LOCK_SECONDS seconds")
            }
            Thread.sleep(LOCK_POLL_MILLIS)
        }
    }

    /** MySQL: [work] while holding the database's lock for this package's migrations, which another server waits for. */
    private fun locked(connection: Connection, work: () -> Unit) {
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

    private val digest: ByteArray by lazy {
        MessageDigest.getInstance("SHA-256").digest("netherforge migrations ${config.database} $namespace".encodeToByteArray())
    }

    /** PostgreSQL's advisory lock key: the first eight bytes of a hash of the database and namespace (a lock is per database there anyway). */
    private val lockKey: Long by lazy { digest.take(8).fold(0L) { key, byte -> (key shl 8) or (byte.toLong() and 0xff) } }

    /** MySQL's lock name, which is server-wide and at most 64 characters: the hash, so two databases' packages never share one. */
    private val lockName: String by lazy { "netherforge." + digest.take(16).joinToString("") { "%02x".format(it) } }

    private fun quoted(name: String) = if (postgres) "\"$name\"" else "`$name`"

    private companion object {
        /** How long a server waits for another to finish migrating before giving up. */
        const val LOCK_SECONDS = 120
        const val LOCK_POLL_MILLIS = 100L
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}

/** Migrations [versions] were applied to a shared database that this server's copy of the package doesn't have. */
class MigrationAhead(val versions: List<Int>) : Exception("migrations ${versions.joinToString { it.toString().padStart(3, '0') }}")
