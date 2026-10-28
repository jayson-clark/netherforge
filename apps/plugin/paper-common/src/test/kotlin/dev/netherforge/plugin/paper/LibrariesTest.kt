package dev.netherforge.plugin.paper

import dev.netherforge.plugin.store.DatabaseType
import java.net.URLClassLoader
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LibrariesTest {
    /** A class loader that reaches only [dir], as a server that doesn't bundle the driver. */
    private fun withoutDriver(dir: Path) = URLClassLoader(arrayOf(dir.toUri().toURL()), null)

    @Test
    fun `fetches nothing when the server bundles the driver`() {
        assertEquals(emptyList(), Libraries.toFetch(javaClass.classLoader))
    }

    @Test
    fun `fetches the coordinates the jar names when the driver is missing`() {
        val dir = createTempDirectory("r3_libs")
        dir.resolve(Libraries.FILE).writeText(
            "sqlite-jdbc=org.xerial:sqlite-jdbc:9.9.9\nhikari=com.zaxxer:HikariCP:8.8.8\npostgresql=org.postgresql:postgresql:7.7.7\n"
        )
        withoutDriver(dir).use {
            assertEquals(
                listOf("org.xerial:sqlite-jdbc:9.9.9", "com.zaxxer:HikariCP:8.8.8", "org.postgresql:postgresql:7.7.7"),
                Libraries.toFetch(it, setOf(DatabaseType.POSTGRES))
            )
        }
    }

    @Test
    fun `fetches the pool and the postgres driver only for the connections configured`() {
        val dir = createTempDirectory("r3_libs")
        dir.resolve(Libraries.FILE).writeText(
            "sqlite-jdbc=org.xerial:sqlite-jdbc:9.9.9\nhikari=com.zaxxer:HikariCP:8.8.8\npostgresql=org.postgresql:postgresql:7.7.7\n"
        )
        withoutDriver(dir).use {
            assertEquals(listOf("org.xerial:sqlite-jdbc:9.9.9"), Libraries.toFetch(it))
            // MySQL's driver is Paper's own: it needs the pool and nothing more.
            assertEquals(
                listOf("org.xerial:sqlite-jdbc:9.9.9", "com.zaxxer:HikariCP:8.8.8"),
                Libraries.toFetch(it, setOf(DatabaseType.MYSQL))
            )
        }
    }

    @Test
    fun `a jar without its libraries file is an error`() {
        val dir = createTempDirectory("r3_libs")
        withoutDriver(dir).use { assertFailsWith<IllegalStateException> { Libraries.toFetch(it) } }
    }

    @Test
    fun `fetches only what the server lacks`() {
        // The test class path has the pool and drivers; a loader that lacks only SQLite's gets only SQLite's.
        val dir = createTempDirectory("r3_libs")
        dir.resolve(Libraries.FILE).writeText("sqlite-jdbc=org.xerial:sqlite-jdbc:9.9.9\n")
        val withoutSqlite = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> =
                if (name == Libraries.SQLITE_DRIVER) throw ClassNotFoundException(name) else super.loadClass(name, resolve)

            override fun getResourceAsStream(name: String) = dir.resolve(name).toFile().takeIf { it.exists() }?.inputStream()
        }
        assertEquals(listOf("org.xerial:sqlite-jdbc:9.9.9"), Libraries.toFetch(withoutSqlite))
    }

    @Test
    fun `a libraries file without the driver is an error`() {
        val dir = createTempDirectory("r3_libs")
        dir.resolve(Libraries.FILE).writeText("other=x:y:1\n")
        withoutDriver(dir).use { assertFailsWith<IllegalStateException> { Libraries.toFetch(it) } }
    }

    @Test
    fun `the built jar's file names the catalog's libraries`() {
        val text = javaClass.classLoader.getResource(Libraries.FILE)!!.readText()
        assertEquals(true, text.contains("sqlite-jdbc=org.xerial:sqlite-jdbc:"))
        assertEquals(true, text.contains("hikari=com.zaxxer:HikariCP:"))
        assertEquals(true, text.contains("postgresql=org.postgresql:postgresql:"))
    }

    @Test
    fun `reads the kinds of server config yml connects to`() {
        val folder = createTempDirectory("r3_libs")
        assertEquals(emptySet(), Libraries.remoteTypes(folder))
        folder.resolve("config.yml").writeText("project: ./p\n")
        assertEquals(emptySet(), Libraries.remoteTypes(folder))
        folder.resolve("config.yml").writeText(
            "databases:\n  a:\n    type: mysql\n    database: x\n    username: u\n    password: p\n" +
                "  b:\n    type: postgres\n    database: y\n    username: u\n    password: p\n" +
                "  broken:\n    type: oracle\n"
        )
        assertEquals(setOf(DatabaseType.MYSQL, DatabaseType.POSTGRES), Libraries.remoteTypes(folder))
    }
}
