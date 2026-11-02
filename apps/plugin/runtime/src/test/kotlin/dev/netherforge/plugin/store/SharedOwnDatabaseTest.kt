package dev.netherforge.plugin.store

import dev.netherforge.format.ProblemCodes
import dev.netherforge.plugin.TestServer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A package's own database (`nf.db()`) on a PostgreSQL connection the owner names for it (`own-databases`): H2 in
 * PostgreSQL mode is the server, shared by two [TestServer]s as two servers of one project would share it, with
 * PostgreSQL's advisory lock functions defined on it by [Locks] (they're PostgreSQL's own, which H2 hasn't).
 */
class SharedOwnDatabaseTest {
    private val serial = AtomicInteger()
    private val servers = ArrayList<TestServer>()

    private val executors = ArrayList<java.util.concurrent.ExecutorService>()

    @AfterTest
    fun close() {
        servers.forEach { it.close() }
        executors.forEach { it.shutdownNow() }
        Locks.reset()
    }

    /** PostgreSQL's advisory locks, for H2: held by key across connections, like the server's. */
    object Locks {
        private val held = ConcurrentHashMap<Long, Boolean>()

        /** Counted down by a try that found the lock held: the holder waits for one, so two servers really meet. */
        @Volatile
        var contended = CountDownLatch(1)
        val refusals = AtomicInteger()

        fun reset() {
            held.clear()
            contended = CountDownLatch(1)
            refusals.set(0)
        }

        @JvmStatic
        fun tryLock(key: Long): Boolean {
            if (held.putIfAbsent(key, true) != null) {
                refusals.incrementAndGet()
                contended.countDown()
                return false
            }
            // The first server keeps it until the second has tried (or a while has passed): they start together.
            contended.await(5, TimeUnit.SECONDS)
            return true
        }

        @JvmStatic
        fun unlock(key: Long): Boolean = held.remove(key) != null
    }

    /** One H2 database in PostgreSQL mode, and every connection to it made with the lock functions defined. */
    private fun postgres(): (String, ConnectionConfig) -> DataSource {
        val name = "shared${serial.incrementAndGet()}"
        val source = org.h2.jdbcx.JdbcDataSource().apply {
            setURL("jdbc:h2:mem:$name;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
            user = "sa"
        }
        source.connection.use { connection ->
            connection.createStatement().use {
                it.execute("CREATE ALIAS IF NOT EXISTS pg_try_advisory_lock FOR \"${Locks::class.java.name}.tryLock\"")
                it.execute("CREATE ALIAS IF NOT EXISTS pg_advisory_unlock FOR \"${Locks::class.java.name}.unlock\"")
            }
        }
        return { _, _ -> source }
    }

    private fun connection(own: Set<String> = setOf("test")) =
        ConnectionConfig(DatabaseType.POSTGRES, "db.internal.example", 5432, "game", "nf_user", "secret", ownDatabases = own)

    private val migrations = mapOf(
        "migrations/001_init.sql" to "CREATE TABLE games (id INTEGER PRIMARY KEY AUTOINCREMENT, winner TEXT NOT NULL);",
        "migrations/002_rank.sql" to "ALTER TABLE games ADD COLUMN rank INTEGER;",
        "migrations/postgres/001_init.sql" to "CREATE TABLE games (id SERIAL PRIMARY KEY, winner TEXT NOT NULL);",
        "migrations/postgres/002_rank.sql" to "ALTER TABLE games ADD COLUMN rank INTEGER;"
    )

    private fun server(
        sources: (String, ConnectionConfig) -> DataSource,
        files: Map<String, Any> = migrations,
        own: Set<String> = setOf("test")
    ) = TestServer(
        mapOf(TestServer.MANIFEST to TestServer.manifest(requires = """{ "db": true }"""), "modules/t/init.lua" to "") + files,
        start = false,
        databases = DatabasesConfig(mapOf("game" to connection(own))),
        connectionSources = sources
    ).also { servers += it }

    /** Runs [script] as a module of its own on [server] and gives what it logged once its work is done. */
    private fun run(server: TestServer, script: String): List<String> {
        val before = server.logs.size
        server.write("modules/act/init.lua", script)
        server.reload("modules/act/init.lua")
        server.tick(20)
        assertTrue(server.errors.isEmpty(), server.errors.toString())
        return server.logs.drop(before)
    }

    @Test
    fun `two servers starting together on one PostgreSQL connection apply each migration once and share the data`() {
        val sources = postgres()
        val a = server(sources)
        val b = server(sources)
        // Each server is its own main thread, as two machines would be: a Lua state stays on the thread that made it.
        val threadA = Executors.newSingleThreadExecutor()
        val threadB = Executors.newSingleThreadExecutor()
        executors += listOf(threadA, threadB)
        val start = CountDownLatch(1)
        val started = listOf(threadA to a, threadB to b).map { (executor, server) ->
            executor.submit {
                start.await()
                server.start()
            }
        }
        start.countDown()
        started.forEach { it.get(30, TimeUnit.SECONDS) }
        for (server in listOf(a, b)) {
            assertTrue(server.runtime.session.running, "${server.runtime.currentProblems()}")
            assertEquals(emptyList(), server.runtime.currentProblems().filter { it.code?.startsWith("migration.") == true })
        }
        // They met at the lock: the second waited, then found every step applied.
        assertTrue(Locks.refusals.get() > 0)
        val applied = sources("game", connection()).connection.use { connection ->
            connection.createStatement().use { s ->
                s.executeQuery("SELECT version FROM test.nf_migrations ORDER BY version").use { rows ->
                    buildList { while (rows.next()) add(rows.getInt(1)) }
                }
            }
        }
        assertEquals(listOf(1, 2), applied)

        assertEquals(
            listOf("written nil"),
            threadA.submit<List<String>> {
                run(
                    a,
                    """
                nf.task(function()
                  local _, err = nf.db():execute("INSERT INTO games (winner, rank) VALUES (?, ?)", { "alex", 3 })
                  log("written " .. tostring(err))
                end)
                """
                )
            }.get()
        )
        assertEquals(
            listOf("read alex 3"),
            threadB.submit<List<String>> {
                run(
                    b,
                    """
                nf.task(function()
                  local rows = nf.db():query("SELECT winner, rank FROM games")
                  log("read " .. rows[1].winner .. " " .. rows[1].rank)
                end)
                """
                )
            }.get()
        )
        val grant = threadA.submit<String> { a.runtime.session.requirements.grants().single { "own database" in it } }.get()
        assertTrue("postgres connection \"game\" (schema test)" in grant, grant)
        threadA.submit { a.close() }.get()
        threadB.submit { b.close() }.get()
        servers -= setOf(a, b)
    }

    @Test
    fun `a package without migrations for the connection's dialect is refused, and its nf_db says why`() {
        val server = server(postgres(), files = mapOf("migrations/001_init.sql" to "CREATE TABLE games (id INTEGER);"))
        server.start()
        val problem = server.runtime.currentProblems().single { it.code == ProblemCodes.MIGRATION_DIALECT.code }
        assertEquals("migrations/001_init.sql", problem.file)
        assertTrue("migrations/postgres/001_init.sql" in problem.message, problem.message)
        val said = run(
            server,
            """
            nf.task(function()
              local rows, err = nf.db():query("SELECT 1")
              log(tostring(rows) .. " " .. tostring(err))
            end)
            """
        ).single()
        assertTrue(said.startsWith("nil ") && "no postgres versions" in said, said)
    }

    @Test
    fun `a server whose copy is older than what ran on the shared database refuses the package`() {
        val sources = postgres()
        server(sources).start()
        // Another server's copy has only the first migration.
        val older = server(sources, files = migrations.filterKeys { "001" in it })
        older.start()
        val problem = older.runtime.currentProblems().single { it.code == ProblemCodes.MIGRATION_AHEAD.code }
        assertTrue("002" in problem.message, problem.message)
    }

    @Test
    fun `without own-databases the package's database stays its SQLite file`() {
        val server = server(postgres(), own = emptySet())
        server.start()
        assertTrue(server.runtime.session.running)
        assertTrue(java.nio.file.Files.isRegularFile(server.runtime.databases.fileOf("test")))
        assertEquals(emptyList(), server.runtime.session.requirements.grants())
    }
}
