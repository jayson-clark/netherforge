package dev.netherforge.plugin.store
import dev.netherforge.format.Severity
import dev.netherforge.plugin.TestServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `nf.db()`: a package's own SQLite database, against the fake platform with
 * a real file in the test's folder. Its migrations, the three calls and their
 * `value, err` answers (in a task and at a callback), what a script gets wrong
 * on the spot versus what the database says later, packages' separate files,
 * and what a reload does to a migration.
 */
class PackageDatabaseTest {
    private val init = """
        CREATE TABLE scores (
          id INTEGER PRIMARY KEY,
          name TEXT NOT NULL UNIQUE,
          score INTEGER NOT NULL,
          note TEXT
        );
        CREATE INDEX scores_by_score ON scores (score);
    """.trimIndent()

    /** Renders a value for the log: rows and results are tables, with their keys in order. */
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

    private fun server(script: String, extra: Map<String, Any> = emptyMap()) = TestServer(
        mapOf(
            TestServer.MANIFEST to TestServer.manifest(requires = """{ "db": true }"""),
            "migrations/001_init.sql" to init,
            "modules/t/init.lua" to "$show\nlocal db = nf.db()\n$script"
        ) + extra
    )

    /** Ticks until the script's waits are over. */
    private fun TestServer.settle(ticks: Int = 8) = tick(ticks)

    @Test
    fun `a task writes and reads its package's database, rows keyed by column`() {
        server(
            """
            nf.task(function()
              local made, err = db:execute("INSERT INTO scores (name, score, note) VALUES (?, ?, ?)", { "alex", 10, "first" })
              log("insert " .. show(made) .. " " .. tostring(err))
              db:execute("INSERT INTO scores (name, score) VALUES (?, ?)", { "sam", 5.5 })
              local updated = db:execute("UPDATE scores SET score = score + ? WHERE name = ?", { 1, "sam" })
              log("update " .. show(updated))
              local rows, err2 = db:query("SELECT name, score, note FROM scores ORDER BY score DESC")
              log("rows " .. #rows .. " " .. tostring(err2))
              for _, row in ipairs(rows) do log(show(row)) end
              local flags, why = db:query("SELECT ? AS yes, ? AS no, ? AS nada, 2.5 AS real, 'text' AS text", { true, false, nil })
              log(show(flags and flags[1] or why))
              local none = db:query("SELECT * FROM scores WHERE score > ?", { 1000 })
              log("none " .. show(none))
            end)
            """
        ).use { server ->
            server.settle(20)
            assertTrue(server.errors.isEmpty(), server.errors.toString())
            assertEquals(
                listOf(
                    "insert {changes=1,last_insert_id=1} nil",
                    "update {changes=1,last_insert_id=2}",
                    "rows 2 nil",
                    "{name=alex,note=first,score=10}",
                    "{name=sam,score=6.5}",
                    "{no=0,real=2.5,text=text,yes=1}",
                    "none {}"
                ),
                server.logs
            )
            assertTrue(server.errors.isEmpty(), server.errors.toString())
        }
    }

    @Test
    fun `a callback hears the answer on a later tick`() {
        server(
            """
            nf.commands.register("go", function()
              local returned = db:query("SELECT 1 AS one", nil, function(rows, err) log("rows " .. show(rows) .. " " .. tostring(err)) end)
              log("returned " .. tostring(returned))
              db:execute("INSERT INTO nowhere VALUES (1)", nil, function(result, err) log("result " .. tostring(result) .. " " .. tostring(err):match("no such table: nowhere")) end)
            end)
            """
        ).use { server ->
            server.platform.commands.runConsole("go")
            assertEquals(listOf("returned nil"), server.logs)
            server.settle()
            assertEquals(listOf("returned nil", "rows {1={one=1}} nil", "result nil no such table: nowhere"), server.logs)
        }
    }

    @Test
    fun `what the database refuses is nil and why, and what a script gets wrong is an error at its line`() {
        server(
            """
            local function try(what, f)
              local ok, problem = pcall(f)
              if not ok then log(what .. ": " .. tostring(problem):gsub("^[^:]+:%d+: ", "")) end
            end
            nf.task(function()
              db:execute("INSERT INTO scores (name, score) VALUES ('alex', 1)")
              local _, duplicate = db:execute("INSERT INTO scores (name, score) VALUES (?, ?)", { "alex", 2 })
              log("duplicate: " .. duplicate:match("UNIQUE constraint failed: scores.name"))
              local _, syntax = db:query("SELEC 1")
              log("syntax: " .. syntax:match("syntax error"))
              local _, count = db:query("SELECT ?", { 1, 2 })
              log("count: " .. count)
              local _, blob = db:query("SELECT x'00ff' AS data")
              log("blob: " .. blob)
              try("two statements", function() db:query("SELECT 1; SELECT 2", nil, print) end)
              try("trailing comment is fine", function() db:query("SELECT 1; -- done", nil, print) end)
              try("empty", function() db:query(" -- nothing", nil, print) end)
              try("a function", function() db:query("SELECT ?", { print }, print) end)
              try("named", function() db:query("SELECT :a", { a = 1 }, print) end)
              try("sql type", function() db:query(7, nil, print) end)
            end)
            """
        ).use { server ->
            server.settle(20)
            assertEquals(
                listOf(
                    "duplicate: UNIQUE constraint failed: scores.name",
                    "syntax: syntax error",
                    "count: the statement has 1 ? but 2 values were given",
                    "blob: column \"data\" holds a blob, which a script can't read: select hex(data) instead",
                    "two statements: Database:query: it has more than one statement: give one statement per call (a transaction takes several)",
                    "empty: Database:query: it is empty",
                    "a function: Database:query: params[1] is a function, which SQL has no value for (a string, number or boolean)",
                    "named: Database:query: params is a list of values, one for each ?, but it has a key that isn't a position from 1 to 32766"
                ),
                server.logs.filterNot { it.startsWith("sql type") }
            )
            assertTrue(server.logs.any { it.startsWith("sql type: bad argument 'sql'") }, server.logs.toString())
        }
    }

    @Test
    fun `a transaction is all or nothing, and says which statement failed`() {
        server(
            """
            nf.task(function()
              db:execute("INSERT INTO scores (name, score) VALUES ('alex', 100), ('sam', 0)")
              local ok, err = db:transaction({
                { sql = "UPDATE scores SET score = score - ? WHERE name = ?", params = { 50, "alex" } },
                { sql = "UPDATE scores SET score = score + ? WHERE name = ?", params = { 50, "sam" } },
              })
              log("ok " .. show(ok) .. " " .. tostring(err))
              local failed, why = db:transaction({
                { sql = "UPDATE scores SET score = 0 WHERE name = 'alex'" },
                { sql = "INSERT INTO scores (name, score) VALUES ('sam', 1)" },
              })
              log("failed " .. tostring(failed) .. " " .. why:gsub(":.*", ""))
              log(show(db:query("SELECT name, score FROM scores ORDER BY name")))
              log("empty " .. show(db:transaction({})))
            end)
            """
        ).use { server ->
            server.settle(20)
            assertEquals(
                listOf(
                    "ok {1={changes=1,last_insert_id=2},2={changes=1,last_insert_id=2}} nil",
                    "failed nil statement 2 failed, so nothing was changed",
                    "{1={name=alex,score=50},2={name=sam,score=50}}",
                    "empty {}"
                ),
                server.logs
            )
        }
    }

    @Test
    fun `the database is a file per package, kept across restarts`() {
        server(
            """
            nf.task(function()
              local rows = db:query("SELECT count(*) AS total FROM scores")
              log("before " .. rows[1].total)
              db:execute("INSERT INTO scores (name, score) VALUES (?, 1)", { "run" .. rows[1].total })
            end)
            """
        ).use { server ->
            server.settle()
            assertEquals(listOf("before 0"), server.logs)
            assertTrue(java.nio.file.Files.exists(server.state.resolve("databases/test.db")))
            server.restart()
            server.settle()
            assertEquals(listOf("before 0", "before 1"), server.logs)
        }
    }

    @Test
    fun `a package has its own database`() {
        val lib = mapOf(
            "../lib/netherforge.json" to
                """{ "formatVersion": 1, "name": "lib", "namespace": "lib", "version": "1.0.0", "minecraft": "26.3", "requires": { "db": true }, "exports": { "modules": ["api"] } }""",
            "../lib/migrations/001_init.sql" to "CREATE TABLE notes (text TEXT);",
            "../lib/modules/api/init.lua" to
                """
                local db = nf.db()
                return {
                  add = function(text) db:execute("INSERT INTO notes (text) VALUES (?)", { text }, function(r, err) log("lib add " .. tostring(err)) end) end,
                  count = function(done) db:query("SELECT count(*) AS n FROM notes", nil, function(rows) done(rows[1].n) end) end,
                }
                """.trimIndent()
        )
        server(
            """
            local api = require("lib:api")
            api.add("hello")
            nf.task(function()
              local _, missing = db:query("SELECT count(*) FROM notes")
              log("project has no notes: " .. tostring(missing):match("no such table: notes"))
              local rows = db:query("SELECT count(*) AS n FROM scores")
              log("scores " .. rows[1].n)
            end)
            """,
            lib + mapOf(
                "netherforge.json" to
                    """{ "formatVersion": 1, "name": "Test", "namespace": "test", "version": "1.0.0", "minecraft": "26.3", "requires": { "db": true }, "dependencies": { "lib": { "path": "../lib" } } }"""
            )
        ).use { server ->
            server.settle(20)
            assertTrue(server.errors.isEmpty(), server.errors.toString())
            // The library's insert and the project's query are on different databases, so which answers first is the pool's to say.
            assertEquals(
                listOf("lib add nil", "project has no notes: no such table: notes", "scores 0"),
                server.logs.take(2).sorted() + server.logs.drop(2)
            )
            assertTrue(java.nio.file.Files.exists(server.state.resolve("databases/lib.db")))
            assertTrue(java.nio.file.Files.exists(server.state.resolve("databases/test.db")))
        }
    }

    @Test
    fun `a migration that fails is a problem on its file, and the database is unavailable until it's fixed`() {
        server(
            """
            nf.commands.register("count", function()
              nf.task(function()
                local rows, err = db:query("SELECT count(*) AS n FROM scores")
                log("count " .. (rows and rows[1].n or "nil") .. " " .. tostring(err))
              end)
            end)
            """,
            mapOf("migrations/002_more.sql" to "ALTER TABLE missing ADD COLUMN rank INTEGER;")
        ).use { server ->
            val problem = server.runtime.session.problems().single { it.code == "migration.failed" }
            assertEquals("migrations/002_more.sql", problem.file)
            assertEquals(Severity.ERROR, problem.severity)
            assertTrue("002_more.sql" in problem.message && "unavailable" in problem.message, problem.message)
            server.platform.commands.runConsole("count")
            server.settle()
            assertTrue(server.logs.single().startsWith("count nil The database of test is unavailable"), server.logs.toString())

            // Fixing the file migrates the database on the reload (001 had been applied already).
            server.write("migrations/002_more.sql", "ALTER TABLE scores ADD COLUMN rank INTEGER;")
            val result = server.reload("migrations/002_more.sql")
            assertTrue(result.resources.single().ok, result.toString())
            assertNull(server.runtime.session.problems().singleOrNull { it.code == "migration.failed" })
            server.platform.commands.runConsole("count")
            server.settle()
            assertEquals("count 0 nil", server.logs.last())
        }
    }

    @Test
    fun `a migration added on a reload is applied without restarting scripts, and an applied one isn't run again`() {
        server(
            """
            nf.commands.register("columns", function()
              nf.task(function()
                local rows = db:query("SELECT name FROM pragma_table_info('scores') ORDER BY cid")
                local names = {}
                for _, row in ipairs(rows) do names[#names + 1] = row.name end
                log(table.concat(names, ","))
              end)
            end)
            """
        ).use { server ->
            server.platform.commands.runConsole("columns")
            server.settle()
            assertEquals("id,name,score,note", server.logs.last())
            // New migration: applied now. The module isn't reloaded (there's nothing to restart for a schema).
            server.write("migrations/002_rank.sql", "ALTER TABLE scores ADD COLUMN rank INTEGER;")
            val added = server.reload("migrations/002_rank.sql")
            assertTrue(added.resources.single().ok, added.toString())
            assertEquals(listOf("migration"), added.resources.map { it.kind })
            server.platform.commands.runConsole("columns")
            server.settle()
            assertEquals("id,name,score,note,rank", server.logs.last())
            // Editing one that has been applied changes nothing: migrations only go forward.
            server.write("migrations/002_rank.sql", "ALTER TABLE scores ADD COLUMN level INTEGER;")
            assertTrue(server.reload("migrations/002_rank.sql").resources.single().ok)
            server.platform.commands.runConsole("columns")
            server.settle()
            assertEquals("id,name,score,note,rank", server.logs.last())
            assertTrue(server.errors.isEmpty(), server.errors.toString())
        }
    }

    @Test
    fun `migration files that are misnumbered are problems, and build no database`() {
        server(
            "nf.task(function() local _, err = db:query('SELECT 1'); log(tostring(err)) end)",
            mapOf(
                "migrations/003_late.sql" to "CREATE TABLE late (x);",
                "migrations/init.sql" to "CREATE TABLE named (x);",
                "migrations/001_again.sql" to "CREATE TABLE again (x);"
            )
        ).use { server ->
            val found = server.runtime.session.projectProblems.filter {
                it.code?.startsWith("migration.") == true
            }.map { it.code to it.file }
            assertEquals(
                setOf(
                    "migration.name" to "migrations/init.sql",
                    "migration.duplicate" to "migrations/001_init.sql",
                    "migration.gap" to "migrations/003_late.sql"
                ),
                found.toSet()
            )
            server.settle()
            assertTrue(server.logs.single().contains("its migrations have problems"), server.logs.toString())
        }
    }

    @Test
    fun `sql text is one statement`() {
        assertNull(SqlText.problem("SELECT 1"))
        assertNull(SqlText.problem("SELECT ';' || \"a;b\" || `c;d` || [e;f]; -- done"))
        assertNull(SqlText.problem("/* head; */ SELECT 1 /* tail */;  "))
        assertNull(SqlText.problem("SELECT 'it''s; fine'"))
        assertNotNull(SqlText.problem("SELECT 1; SELECT 2"))
        assertNotNull(SqlText.problem("SELECT 1;SELECT 2"))
        assertNotNull(SqlText.problem("SELECT 1; 'x'"))
        assertEquals("it is empty", SqlText.problem(""))
        assertEquals("it is empty", SqlText.problem("  -- just a comment\n ;"))
    }
}
