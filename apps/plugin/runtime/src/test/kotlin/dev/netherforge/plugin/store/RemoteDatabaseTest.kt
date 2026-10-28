package dev.netherforge.plugin.store
import dev.netherforge.plugin.TestServer
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `nf.db("network")`: a named MySQL or PostgreSQL connection the server's owner
 * set up, against the fake platform with H2 in MySQL or PostgreSQL mode as the
 * server (HikariCP is the production pool; the pool is a seam, see
 * [NetherForgeRuntime.connectionSources]). The handle is `nf.db()`'s class, so
 * the same script is run against both and must answer the same; then who may
 * open a connection, what a script is told when it can't, and what the
 * package's own migrations don't do to a shared server.
 */
class RemoteDatabaseTest {
    private val serial = AtomicInteger()

    /** What every Database function's answer is shown as: tables with their keys in order. */
    private val show = """
        function show(v)
          if type(v) ~= "table" then return tostring(v) end
          local keys = {}
          for k in pairs(v) do keys[#keys + 1] = k end
          table.sort(keys, function(a, b) return tostring(a) < tostring(b) end)
          local out = {}
          for _, k in ipairs(keys) do out[#out + 1] = tostring(k) .. "=" .. show(v[k]) end
          return "{" .. table.concat(out, ",") .. "}"
        end
    """.trimIndent()

    private fun connection(
        type: DatabaseType = DatabaseType.MYSQL,
        packages: Set<String> = setOf("test"),
        password: String = "hunter2-secret"
    ) = ConnectionConfig(type, "db.internal.example", 3306, "netherforge", "nf_user", password, packages = packages)

    /** One in-memory H2 database per test, in the dialect mode of [type], shared by every connection the pool makes. */
    private fun h2(type: DatabaseType): (String, ConnectionConfig) -> DataSource {
        val name = "remote${serial.incrementAndGet()}"
        val mode = if (type == DatabaseType.MYSQL) "MySQL" else "PostgreSQL"
        return { _, _ ->
            org.h2.jdbcx.JdbcDataSource().apply {
                setURL("jdbc:h2:mem:$name;MODE=$mode;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
                user = "sa"
            }
        }
    }

    private val init = "CREATE TABLE scores (id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE, score INTEGER NOT NULL, note TEXT);"

    private fun server(
        script: String,
        databases: Map<String, ConnectionConfig> = mapOf("network" to connection()),
        type: DatabaseType = DatabaseType.MYSQL,
        requires: String? = """{ "db": true }""",
        extra: Map<String, Any> = emptyMap(),
        invalid: Map<String, String> = emptyMap(),
        sources: ((String, ConnectionConfig) -> DataSource)? = null
    ) = TestServer(
        mapOf(
            TestServer.MANIFEST to TestServer.manifest(requires = requires),
            "migrations/001_init.sql" to init,
            "modules/t/init.lua" to "$show\n$script"
        ) + extra,
        databases = DatabasesConfig(databases, invalid),
        connectionSources = sources ?: h2(type)
    )

    private fun TestServer.settle(ticks: Int = 20) = tick(ticks)

    /**
     * What one script does with a database, written once and run against the package's own SQLite file and
     * against a connection: the handle is the same class, so the answers are too.
     */
    private fun parityScript(open: String, create: String) = """
        local db = $open
        nf.task(function()
          $create
          local made, err = db:execute("INSERT INTO scores (name, score, note) VALUES (?, ?, ?)", { "alex", 10, "first" })
          log("insert " .. show(made) .. " " .. tostring(err))
          db:execute("INSERT INTO scores (name, score) VALUES (?, ?)", { "sam", 5 })
          local updated = db:execute("UPDATE scores SET score = score + ? WHERE name = ?", { 1, "sam" })
          log("update changes " .. updated.changes)
          local rows, err2 = db:query("SELECT name, score, note FROM scores ORDER BY score DESC")
          log("rows " .. #rows .. " " .. tostring(err2))
          for _, row in ipairs(rows) do log(show(row)) end
          local flags, why = db:query("SELECT CAST(? AS BIGINT) AS yes, CAST(? AS BIGINT) AS no, CAST(? AS BIGINT) AS nada", { true, false, nil })
          log(show(flags and flags[1] or why))
          log("none " .. show(db:query("SELECT * FROM scores WHERE score > ?", { 1000 })))
          local _, duplicate = db:execute("INSERT INTO scores (name, score) VALUES (?, ?)", { "alex", 2 })
          log("duplicate refused: " .. tostring(duplicate ~= nil))
          local _, syntax = db:query("SELEC 1")
          log("syntax refused: " .. tostring(syntax ~= nil))
          local _, count = db:query("SELECT ?", { 1, 2 })
          log("count: " .. count)
          local ok, tx = db:transaction({
            { sql = "UPDATE scores SET score = score - ? WHERE name = ?", params = { 5, "alex" } },
            { sql = "UPDATE scores SET score = score + ? WHERE name = ?", params = { 5, "sam" } },
          })
          log("transaction " .. #ok .. " " .. tostring(tx))
          local failed, why2 = db:transaction({
            { sql = "UPDATE scores SET score = 0 WHERE name = 'alex'" },
            { sql = "INSERT INTO scores (name, score) VALUES ('sam', 1)" },
          })
          log("failed " .. tostring(failed) .. " " .. why2:match("^statement %d failed, so nothing was changed"))
          log(show(db:query("SELECT name, score FROM scores ORDER BY name")))
          log("empty " .. show(db:transaction({})))
        end)
    """.trimIndent()

    @Test
    fun `a named connection is the same Database as the package's own, so one script answers alike on both`() {
        val own = server(parityScript("nf.db()", ""), databases = emptyMap()).use { server ->
            server.settle()
            assertTrue(server.errors.isEmpty(), server.errors.toString())
            server.logs
        }
        val create = """
            db:execute("CREATE TABLE scores (id INTEGER AUTO_INCREMENT PRIMARY KEY, name VARCHAR(40) NOT NULL UNIQUE, " ..
              "score BIGINT NOT NULL, note VARCHAR(40))")
        """.trimIndent()
        val remote = server(parityScript("""nf.db("network")""", create)).use { server ->
            server.settle()
            assertTrue(server.errors.isEmpty(), server.errors.toString())
            server.logs
        }
        assertEquals(
            listOf(
                "insert {changes=1,last_insert_id=1} nil",
                "update changes 1",
                "rows 2 nil",
                "{name=alex,note=first,score=10}",
                "{name=sam,score=6}",
                "{no=0,yes=1}",
                "none {}",
                "duplicate refused: true",
                "syntax refused: true",
                "count: the statement has 1 ? but 2 values were given",
                "transaction 2 nil",
                "failed nil statement 2 failed, so nothing was changed",
                "{1={name=alex,score=5},2={name=sam,score=11}}",
                "empty {}"
            ),
            own
        )
        assertEquals(own, remote)
    }

    @Test
    fun `a callback hears the answer on a later tick, as on the package's own database`() {
        server(
            """
            nf.commands.register("go", function()
              local db = nf.db("network")
              local returned = db:query("SELECT 1 AS one", nil, function(rows, err) log("rows " .. show(rows) .. " " .. tostring(err)) end)
              log("returned " .. tostring(returned))
              db:execute("INSERT INTO nowhere VALUES (1)", nil, function(result, err) log("result " .. tostring(result) .. " " .. tostring(err ~= nil)) end)
            end)
            """
        ).use { server ->
            server.platform.commands.runConsole("go")
            assertEquals(listOf("returned nil"), server.logs)
            server.settle()
            assertEquals(listOf("returned nil", "rows {1={one=1}} nil", "result nil true"), server.logs)
        }
    }

    @Test
    fun `values a server has beyond SQLite's arrive as an integer, a number or text`() {
        server(
            """
            local db = nf.db("network")
            nf.task(function()
              local rows, err = db:query(
                "SELECT TRUE AS yes, CAST(7 AS SMALLINT) AS small, CAST(1.5 AS DECIMAL(5,2)) AS price, CAST(2.5 AS REAL) AS real, " ..
                "DATE '2026-10-05' AS dt, 'text' AS text, NULL AS nada, CAST(12345678901 AS BIGINT) AS big"
              )
              log(show(rows and rows[1] or err))
              local _, blob = db:query("SELECT X'00ff' AS data")
              log("blob: " .. tostring(blob))
            end)
            """
        ).use { server ->
            server.settle()
            assertEquals(
                listOf(
                    "{big=12345678901,dt=2026-10-05,price=1.5,real=2.5,small=7,text=text,yes=1}",
                    "blob: column \"data\" holds a blob, which a script can't read: select hex(data) instead"
                ),
                server.logs
            )
        }
    }

    @Test
    fun `MySQL gives the key a statement generated, and PostgreSQL gives none`() {
        val script = """
            local db = nf.db("network")
            nf.task(function()
              db:execute("CREATE TABLE notes (id INTEGER AUTO_INCREMENT PRIMARY KEY, text VARCHAR(20))")
              log("first " .. show(db:execute("INSERT INTO notes (text) VALUES (?)", { "a" })))
              log("second " .. show(db:execute("INSERT INTO notes (text) VALUES (?)", { "b" })))
              log("update " .. show(db:execute("UPDATE notes SET text = ? WHERE id = ?", { "c", 1 })))
            end)
        """
        server(script).use { server ->
            server.settle()
            assertEquals(
                listOf(
                    "first {changes=1,last_insert_id=1}",
                    "second {changes=1,last_insert_id=2}",
                    "update {changes=1}"
                ),
                server.logs
            )
        }
        val postgres = script.replace("INTEGER AUTO_INCREMENT PRIMARY KEY", "SERIAL PRIMARY KEY")
        server(postgres, mapOf("network" to connection(DatabaseType.POSTGRES)), DatabaseType.POSTGRES).use { server ->
            server.settle()
            assertEquals(listOf("first {changes=1}", "second {changes=1}", "update {changes=1}"), server.logs)
        }
    }

    @Test
    fun `the package's migrations are its own SQLite database's and build nothing on a shared server`() {
        server(
            """
            local own = nf.db()
            local network = nf.db("network")
            nf.task(function()
              log("own " .. show(own:query("SELECT count(*) AS n FROM scores")))
              local _, err = network:query("SELECT count(*) AS n FROM scores")
              log("network has no scores: " .. tostring(err ~= nil))
            end)
            """
        ).use { server ->
            server.settle()
            assertEquals(listOf("own {1={n=0}}", "network has no scores: true"), server.logs)
        }
    }

    @Test
    fun `packages share a connection, so what one writes the other reads`() {
        val lib = mapOf(
            "../lib/netherforge.json" to
                """{ "formatVersion": 1, "name": "lib", "namespace": "lib", "version": "1.0.0", "minecraft": "26.3", "requires": { "db": true }, "exports": { "modules": ["api"] } }""",
            "../lib/modules/api/init.lua" to
                """
                local db = nf.db("network")
                return {
                  add = function(text, done)
                    db:execute("INSERT INTO shared_notes (text) VALUES (?)", { text }, function(r, err) done(err) end)
                  end,
                }
                """.trimIndent(),
            "netherforge.json" to
                """{ "formatVersion": 1, "name": "Test", "namespace": "test", "version": "1.0.0", "minecraft": "26.3", "requires": { "db": true }, "dependencies": { "lib": { "path": "../lib" } } }"""
        )
        server(
            """
            local api = require("lib:api")
            local db = nf.db("network")
            nf.task(function()
              db:execute("CREATE TABLE IF NOT EXISTS shared_notes (text VARCHAR(20))")
              api.add("from lib", function(err) log("lib added " .. tostring(err)) end)
              db:execute("INSERT INTO shared_notes (text) VALUES (?)", { "from test" })
              nf.wait(3)
              log(show(db:query("SELECT text FROM shared_notes ORDER BY text")))
            end)
            """,
            mapOf("network" to connection(packages = setOf("test", "lib"))),
            extra = lib
        ).use { server ->
            server.settle()
            assertTrue(server.errors.isEmpty(), server.errors.toString())
            assertEquals(listOf("lib added nil", "{1={text=from lib},2={text=from test}}"), server.logs)
        }
    }

    // ---- who may open a connection, and what they're told --------------------------------------------------------

    private fun errorOf(script: String, databases: Map<String, ConnectionConfig>, invalid: Map<String, String> = emptyMap()): String =
        server(
            """
            local ok, problem = pcall(function() $script end)
            log((tostring(problem):gsub("^[^:]+:%d+: ", "")))
            """,
            databases,
            invalid = invalid
        ).use { server ->
            server.settle(2)
            server.logs.single()
        }

    @Test
    fun `a name the owner didn't set up is an error that says which names there are`() {
        assertEquals(
            "nf.db: there's no database connection called \"other\" (the server's owner has set up: network)",
            errorOf("nf.db('other')", mapOf("network" to connection()))
        )
        assertEquals(
            "nf.db: there's no database connection called \"network\": the server's owner hasn't set any up",
            errorOf("nf.db('network')", emptyMap())
        )
        assertTrue(errorOf("nf.db('')", mapOf("network" to connection())).startsWith("nf.db: there's no database connection called \"\""))
    }

    @Test
    fun `a connection the owner didn't list the package for is refused, naming what to add and nothing about the server`() {
        val message = errorOf("nf.db('network')", mapOf("network" to connection(packages = setOf("other"))))
        assertEquals(
            "nf.db: the connection \"network\" isn't open to package \"test\": the server's owner lists the packages that may use it " +
                "under `databases.network.packages` in the plugin's config.yml",
            message
        )
        assertFalse("db.internal" in message || "hunter2" in message || "nf_user" in message, message)
        // With no packages at all: nobody, not everybody.
        assertTrue("isn't open to package" in errorOf("nf.db('network')", mapOf("network" to connection(packages = emptySet()))))
    }

    @Test
    fun `a connection whose configuration couldn't be read says why, without its values`() {
        assertEquals(
            "nf.db: the server's connection \"network\" can't be used: `type` is \"oracle\", which isn't mysql or postgres " +
                "(the owner fixes it in config.yml's databases)",
            errorOf(
                "nf.db('network')",
                emptyMap(),
                invalid = mapOf("network" to "`type` is \"oracle\", which isn't mysql or postgres")
            )
        )
    }

    @Test
    fun `a package that hasn't declared db can't open a named connection either`() {
        server(
            """
            local ok, problem = pcall(function() return (nf.db("network")) end)
            log((tostring(problem):gsub("^[^:]+:%d+: ", "")))
            """,
            requires = null
        ).use { server ->
            server.settle(2)
            assertEquals(
                "nf.db needs db, which package \"test\" hasn't declared: add \"requires\": { \"db\": true } to its netherforge.json",
                server.logs.single()
            )
        }
    }

    @Test
    fun `a server that can't be reached is nil and why, with the address in the log and not in the answer`() {
        val unreachable: (String, ConnectionConfig) -> DataSource = { _, _ ->
            object : DataSource by org.h2.jdbcx.JdbcDataSource() {
                override fun getConnection(): java.sql.Connection = throw SQLException("Connection to db.internal.example:3306 refused")

                override fun getConnection(username: String?, password: String?): java.sql.Connection = getConnection()
            }
        }
        server(
            """
            local db = nf.db("network")
            nf.task(function()
              local rows, err = db:query("SELECT 1")
              log("rows " .. tostring(rows) .. " " .. tostring(err))
            end)
            """,
            sources = unreachable
        ).use { server ->
            server.settle()
            assertEquals(
                listOf("rows nil the database connection \"network\" couldn't be reached (the server's log says why)"),
                server.logs
            )
            // The cause (with the address) is the log's, for whoever runs the server; the password never is.
            val logged = server.sent.filterIsInstance<dev.netherforge.format.bridge.Log>().map { it.message }
            assertTrue(logged.any { "couldn't reach the database connection \"network\"" in it }, logged.toString())
            assertFalse(logged.any { "hunter2-secret" in it }, logged.toString())
        }
    }

    @Test
    fun `the log says what's set up and what isn't, never a password`() {
        server(
            "log('up')",
            mapOf("network" to connection(), "stats" to connection(DatabaseType.POSTGRES, packages = setOf("test", "lib"))),
            invalid = mapOf("broken" to "`database` is missing")
        ).use { server ->
            val lines = server.sent.filterIsInstance<dev.netherforge.format.bridge.Log>().map { it.message }
            assertTrue(
                lines.any {
                    it == "Database connections: network: mysql at db.internal.example:3306, for test; " +
                        "stats: postgres at db.internal.example:3306, for lib, test"
                },
                lines.toString()
            )
            assertTrue(lines.any { it == "config.yml's databases.broken can't be used: `database` is missing" }, lines.toString())
            assertFalse(lines.any { "hunter2" in it }, lines.toString())
        }
    }
}
