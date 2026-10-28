package dev.netherforge.plugin.store

import dev.netherforge.plugin.async.Workers
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.Collections
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The one layer every store sits on: staged writes, batches, failures, migrations, a file that isn't a database. */
class DatabaseTest {
    private val folder: Path = createTempDirectory("netherforge-db")
    private val workers = Workers()
    private val errors: MutableList<String> = Collections.synchronizedList(ArrayList())
    private val infos: MutableList<String> = Collections.synchronizedList(ArrayList())

    private val report = object : Database.Report {
        override fun error(message: String, cause: Throwable?) {
            errors += message
        }

        override fun info(message: String) {
            infos += message
        }
    }

    private val file = folder.resolve("test.db")

    private fun open(busyMillis: Int = 5_000): Database = Database.open(file, workers.lane("db"), report, busyMillis).apply {
        migrate("test", listOf(Migration.of("001_init.sql", "CREATE TABLE things (id TEXT PRIMARY KEY, value INTEGER NOT NULL);")))
    }

    private fun Database.put(id: String, value: Int) = stage("thing $id") {
        it.update("INSERT OR REPLACE INTO things VALUES (?, ?)", id, value)
    }

    private fun Database.things(): Map<String, Int> = read {
        it.query("SELECT id, value FROM things ORDER BY id") { rows ->
            buildMap { while (rows.next()) put(rows.getString(1), rows.getInt(2)) }
        }
    }

    @AfterTest
    fun close() {
        workers.close()
        folder.toFile().deleteRecursively()
    }

    @Test
    fun `a key staged many times is written once, as it ended, and nothing staged writes nothing`() {
        open().use { db ->
            repeat(20) { db.put("a", it) }
            db.put("b", 1)
            db.commit()
            db.flush()
            assertEquals(2, db.written)
            assertEquals(mapOf("a" to 19, "b" to 1), db.things())

            repeat(5) { db.commit() }
            db.flush()
            assertEquals(2, db.written, "an idle tick writes nothing")
        }
    }

    @Test
    fun `a write that fails is dropped alone, and the rest of its batch is written`() {
        open().use { db ->
            db.put("a", 1)
            db.stage("broken") { it.update("INSERT INTO nowhere VALUES (1)") }
            db.put("b", 2)
            db.flush()
            assertEquals(mapOf("a" to 1, "b" to 2), db.things())
            assertEquals(listOf("NetherForge couldn't write broken to $file; that write is dropped"), errors)
        }
    }

    @Test
    fun `a batch that can't be committed waits for the next, and newer writes win`() {
        open(busyMillis = 50).use { db ->
            // Someone else holds the file: nothing can be written for now.
            val other = DriverManager.getConnection("jdbc:sqlite:$file")
            other.createStatement().execute("BEGIN EXCLUSIVE")
            db.put("a", 1)
            db.put("b", 1)
            db.commit()
            // A read waits for the lane, so the failed commit has happened by now; reading itself is refused too.
            runCatching { db.things() }
            assertEquals(1, errors.size, "$errors")
            assertTrue(errors.single().startsWith("NetherForge couldn't write to $file (2 writes wait"), errors.single())

            other.createStatement().execute("ROLLBACK")
            other.close()
            db.put("b", 2)
            db.flush()
            assertEquals(mapOf("a" to 1, "b" to 2), db.things())
            assertEquals(listOf("NetherForge can write to $file again; nothing was lost"), infos)
        }
    }

    @Test
    fun `migrations run once each, in order, and are recorded`() {
        open().close()
        Database.open(file, workers.lane("db"), report).use { db ->
            val migrations = listOf(
                Migration.of("002_more.sql", "ALTER TABLE things ADD COLUMN note TEXT;"),
                Migration.of("001_init.sql", "CREATE TABLE things (id TEXT PRIMARY KEY, value INTEGER NOT NULL);")
            )
            db.migrate("test", migrations)
            db.migrate("test", migrations)
            val applied = db.read {
                it.query("SELECT version, name FROM migrations WHERE scope = 'test' ORDER BY version") { rows ->
                    buildList { while (rows.next()) add("${rows.getInt(1)} ${rows.getString(2)}") }
                }
            }
            assertEquals(listOf("1 init", "2 more"), applied)
        }
        assertEquals(emptyList(), errors)
    }

    @Test
    fun `a file that isn't a database is kept aside and a new one started`() {
        Files.writeString(file, "not a database")
        open().use { db ->
            db.put("a", 1)
            db.flush()
            assertEquals(mapOf("a" to 1), db.things())
        }
        assertEquals("not a database", Files.readString(folder.resolve("test.db.unreadable")))
        assertEquals(1, errors.size)
    }
}
