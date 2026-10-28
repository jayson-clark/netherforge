package dev.netherforge.plugin.store

import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** One write to a [Database]: what it does to the rows it's for, run on the database's lane inside a transaction. */
fun interface Write {
    fun run(connection: Connection)
}

/**
 * One SQLite database file, and the one way the runtime touches it.
 *
 * **One connection, one lane.** The connection is only ever used on [lane]
 * (a lane of the runtime's workers: one piece of work at a time, in order),
 * so nothing here is shared between threads but the queue of staged writes.
 * The main thread never waits on the disk to write; it waits only to read
 * ([read]), which is rare (a table asked for the first time, start-up, an
 * admin looking), and sees every write staged before it.
 *
 * **Writes are staged, then committed together.** [stage] keeps a write
 * under a key (the rows it sets: an instance, a player's table), replacing
 * one staged earlier under the same key, so a value that changed ten times in
 * a tick is written once, as it ended. [commit] (the end of every tick) hands
 * what's staged to the lane as one transaction: at most one tick behind, and
 * nothing at all when nothing changed. Every keyed write sets its rows to
 * their final state, so writes for different keys may run in any order.
 *
 * **Errors are handled once, here.** A write that fails (a bug: a constraint,
 * bad SQL) is rolled back alone (a savepoint each) and dropped with a line in
 * the log; the rest of its batch is written. A batch that can't be committed
 * (the disk is full, the file is locked) stays on the lane and is retried with
 * the next one, newer writes for the same keys winning, so nothing is lost
 * while the server can still run; the log says so once, and again when
 * writing works again. A file that isn't a database is moved aside
 * (`<name>.unreadable`) and a new one started, never overwritten.
 *
 * Migrations ([migrate]) are numbered per scope and recorded in the
 * database's `migrations` table, each applied once in its own transaction.
 * The runtime's store ([Store]) is one database; each package's own
 * (`nf.db`, [PackageDatabases]) is another on the same class.
 */
class Database private constructor(
    val file: Path,
    private val lane: Executor,
    /** What went wrong or right again, for the log: called on the lane. */
    private val report: Report,
    /** How long a write waits for another connection's lock (someone's sqlite3 on the file) before it fails. */
    private val busyMillis: Int
) : AutoCloseable,
    SqlDatabase {
    /** Where the log hears about the database: called on the lane, so pass it on to the main thread. */
    interface Report {
        fun error(message: String, cause: Throwable?)

        fun info(message: String)
    }

    private var connection: Connection? = null

    private val lock = Any()

    /** Writes staged since the last commit, by key, in the order their keys were first staged. */
    private val staged = LinkedHashMap<Any, Write>()

    /** Lane only: a batch that couldn't be committed, written with the next. */
    private var carried = LinkedHashMap<Any, Write>()

    /** Lane only: the last commit failure's message, so the same failure is logged once. */
    private var failing: String? = null

    private val applied = AtomicLong()

    /** Writes committed so far: what a test checks to see that nothing was written. */
    val written: Long get() = applied.get()

    /** Stages [write] under [key]: committed with the next [commit], in place of anything staged under [key] before. */
    fun stage(key: Any, write: Write) {
        synchronized(lock) { staged[key] = write }
    }

    /** Hands everything staged to the lane, as one transaction. Nothing staged, nothing happens. */
    fun commit() {
        // Taken and queued together, so batches reach the lane in the order they were staged whoever commits.
        synchronized(lock) {
            if (staged.isEmpty()) return
            val batch = LinkedHashMap(staged)
            staged.clear()
            lane.execute { apply(batch) }
        }
    }

    /**
     * Runs [query] on the database once everything staged so far is written,
     * and waits for its answer. For the main thread's rare reads; what it
     * throws, this throws.
     */
    fun <T> read(query: (Connection) -> T): T = wait(submit(query))

    /** Runs [work] on the lane once everything staged so far is written: for work nobody waits on (an export). */
    fun <T> submit(work: (Connection) -> T): CompletableFuture<T> {
        commit()
        return CompletableFuture.supplyAsync({ work(open()) }, lane)
    }

    /**
     * Runs [work] on the connection. **Only on the lane**: for work that was
     * itself given to it (a package's `db:query`, which the workers run on
     * the lane so scripts' work is counted and bounded); [read] and [submit]
     * are for everyone else.
     */
    override fun <T> onLane(work: (Connection) -> T): T = work(open())

    override fun prepare(connection: Connection, sql: String, forWrite: Boolean): PreparedStatement = connection.prepareStatement(sql)

    // SQLite's is the connection's last insert, which every statement leaves for the next to read (0 before any insert).
    override fun lastInsertId(connection: Connection, statement: PreparedStatement, sql: String): Long? = connection.createStatement().use {
        it.executeQuery("SELECT last_insert_rowid()").use { id ->
            id.next()
            id.getLong(1)
        }
    }.takeIf { it != 0L }

    /** Waits until everything staged so far is written. */
    fun flush() {
        wait(submit { })
    }

    /** Commits what's staged, waits for it, and closes the connection. */
    override fun close() {
        try {
            flush()
        } finally {
            wait(CompletableFuture.supplyAsync({ connection?.close() }, lane))
            connection = null
        }
    }

    /**
     * Applies each scope's [migrations] the database hasn't had yet, in
     * version order, each in its own transaction, and records them. Waits.
     */
    fun migrate(scope: String, migrations: List<Migration>) {
        read { connection ->
            connection.createStatement().use {
                it.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS migrations (" +
                        "scope TEXT NOT NULL, version INTEGER NOT NULL, name TEXT NOT NULL, applied TEXT NOT NULL, " +
                        "PRIMARY KEY (scope, version))"
                )
            }
            val done = connection.prepareStatement("SELECT version FROM migrations WHERE scope = ?").use { select ->
                select.setString(1, scope)
                select.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getInt(1)) } }
            }
            val known = migrations.map { it.version }.toSet()
            done.filter { it !in known }.takeIf { it.isNotEmpty() }?.let {
                report.error(
                    "$file has $scope migrations this NetherForge doesn't know (${it.sorted()}): was it written by a newer one?",
                    null
                )
            }
            for (migration in migrations.sortedBy { it.version }) {
                if (migration.version in done) continue
                try {
                    transaction(connection) {
                        connection.createStatement().use { it.executeUpdate(migration.sql) }
                        connection.prepareStatement(
                            "INSERT INTO migrations (scope, version, name, applied) VALUES (?, ?, ?, datetime('now'))"
                        ).use {
                            it.setString(1, scope)
                            it.setInt(2, migration.version)
                            it.setString(3, migration.name)
                            it.executeUpdate()
                        }
                    }
                } catch (e: SQLException) {
                    throw MigrationFailed(migration, e)
                }
            }
        }
    }

    // ---- on the lane -------------------------------------------------------------

    private fun apply(batch: LinkedHashMap<Any, Write>) {
        // What couldn't be committed last time goes first; the same key staged since replaces it.
        val writes = if (carried.isEmpty()) batch else LinkedHashMap(carried).apply { putAll(batch) }
        val connection = try {
            open()
        } catch (e: SQLException) {
            failed(writes, e)
            return
        }
        var done = 0L
        try {
            transaction(connection) {
                for ((key, write) in writes) {
                    val savepoint = connection.setSavepoint()
                    try {
                        write.run(connection)
                        connection.releaseSavepoint(savepoint)
                        done++
                    } catch (e: Exception) {
                        // The database's trouble (busy, full, failing) fails the batch, to be tried again; only the write's own goes alone.
                        if (e is SQLException && !writesFault(e)) throw e
                        connection.rollback(savepoint)
                        report.error("NetherForge couldn't write $key to $file; that write is dropped", e)
                    }
                }
            }
        } catch (e: SQLException) {
            failed(writes, e)
            return
        }
        carried = LinkedHashMap()
        applied.addAndGet(done)
        if (failing != null) {
            failing = null
            report.info("NetherForge can write to $file again; nothing was lost")
        }
    }

    private fun failed(writes: LinkedHashMap<Any, Write>, e: SQLException) {
        carried = writes
        if (failing != e.message) {
            failing = e.message
            report.error("NetherForge couldn't write to $file (${writes.size} writes wait for the next try)", e)
        }
    }

    /** The connection, opened the first time (on the lane). */
    private fun open(): Connection = connection ?: connect().also { connection = it }

    private fun connect(): Connection {
        Files.createDirectories(file.parent)
        try {
            return checked()
        } catch (e: SQLException) {
            if (!unreadable(e)) throw e
        }
        for (suffix in listOf("", "-wal", "-shm")) {
            val from = file.resolveSibling(file.fileName.toString() + suffix)
            if (Files.exists(
                    from
                )
            ) {
                Files.move(from, file.resolveSibling("${file.fileName}$suffix.unreadable"), StandardCopyOption.REPLACE_EXISTING)
            }
        }
        report.error("$file isn't a database NetherForge can read (kept as ${file.fileName}.unreadable); starting a new one", null)
        return checked()
    }

    /** A new connection that has read the schema: a file that isn't a database fails here, not at the first write. */
    private fun checked(): Connection {
        val connection = dataSource().connection
        try {
            connection.createStatement().use { it.executeQuery("SELECT count(*) FROM sqlite_schema").close() }
            return connection
        } catch (e: SQLException) {
            connection.close()
            throw e
        }
    }

    private fun dataSource(): SQLiteDataSource {
        val config = SQLiteConfig().apply {
            // Readers (an admin's export, a person's sqlite3) never block the writer, and a commit is one append.
            setJournalMode(SQLiteConfig.JournalMode.WAL)
            // Durable at every checkpoint; a power cut may lose the last commits, never the file.
            setSynchronous(SQLiteConfig.SynchronousMode.NORMAL)
            setBusyTimeout(busyMillis)
        }
        return SQLiteDataSource(config).apply { url = "jdbc:sqlite:${file.toAbsolutePath()}" }
    }

    /** Whether [e] is the write's own doing (bad SQL, a constraint, a value of the wrong type), not the database's. */
    private fun writesFault(e: SQLException): Boolean {
        val code = (e as? SQLiteException)?.resultCode?.code ?: return false
        return (code and PRIMARY) in WRITES_FAULTS
    }

    private fun unreadable(e: SQLException): Boolean {
        val code = (e as? SQLiteException)?.resultCode ?: return false
        return code == SQLiteErrorCode.SQLITE_NOTADB || code == SQLiteErrorCode.SQLITE_CORRUPT
    }

    companion object {
        /** How long a wait for the lane may take before it's an error: longer than any real write. */
        private const val WAIT_SECONDS = 60L

        /** How long a write waits for another connection's lock by default. */
        private const val BUSY_MILLIS = 5_000

        /** An extended result code's primary code. */
        private const val PRIMARY = 0xff

        /** Primary result codes that are a write's own fault: SQLITE_ERROR, SQLITE_TOOBIG, SQLITE_CONSTRAINT, SQLITE_MISMATCH, SQLITE_RANGE. */
        private val WRITES_FAULTS = setOf(1, 18, 19, 20, 25)

        /**
         * Opens [file] (made when it isn't there), its connection used only on
         * [lane]. Waits until it's open, so a file that can't be opened at all
         * throws here.
         */
        fun open(file: Path, lane: Executor, report: Report, busyMillis: Int = BUSY_MILLIS): Database {
            val database = Database(file, lane, report, busyMillis)
            database.wait(CompletableFuture.supplyAsync({ database.open() }, lane))
            return database
        }

        /** Runs [work] as one transaction on [connection]: committed when it returns, rolled back when it throws. */
        fun transaction(connection: Connection, work: () -> Unit) {
            connection.autoCommit = false
            try {
                work()
                connection.commit()
            } catch (e: Throwable) {
                runCatching { connection.rollback() }
                throw e
            } finally {
                connection.autoCommit = true
            }
        }
    }

    private fun <T> wait(future: CompletableFuture<T>): T = try {
        future.get(WAIT_SECONDS, TimeUnit.SECONDS)
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    }
}

/**
 * One step of a database's schema: `NNN_name.sql`, applied once, in order of
 * [version], by [Database.migrate].
 */
data class Migration(val version: Int, val name: String, val sql: String) {
    /** The file it came from: `001_init.sql`. */
    val fileName: String get() = "${version.toString().padStart(3, '0')}_$name.sql"

    companion object {
        private val FILE = Regex("""^(\d{3})_([a-z0-9_]+)\.sql$""")

        /** The migration a file named `NNN_name.sql` holds. */
        fun of(fileName: String, sql: String): Migration {
            val match = requireNotNull(FILE.matchEntire(fileName)) { "a migration is named NNN_name.sql, not $fileName" }
            return Migration(match.groupValues[1].toInt(), match.groupValues[2], sql)
        }
    }
}

/** [migration] couldn't be applied (it was rolled back, and the ones before it stay applied): [cause] says why. */
class MigrationFailed(val migration: Migration, override val cause: SQLException) :
    Exception("${migration.fileName}: ${cause.message}", cause)
