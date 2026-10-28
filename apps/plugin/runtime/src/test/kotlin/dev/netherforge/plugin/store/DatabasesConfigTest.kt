package dev.netherforge.plugin.store
import com.zaxxer.hikari.HikariDataSource
import dev.netherforge.plugin.RuntimeConfig
import dev.netherforge.plugin.ServerAddress
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `databases:` in `config.yml`: what's read, defaults, what's refused (and said), and that a password stays out of text. */
class DatabasesConfigTest {
    private fun read(section: Map<String, Any?>) = DatabasesConfig.read(section)

    @Test
    fun `a connection is read with its defaults`() {
        val config = read(
            mapOf(
                "network" to mapOf(
                    "type" to "mysql",
                    "host" to "db.example.com",
                    "database" to "nf",
                    "username" to "nf_user",
                    "password" to "secret",
                    "packages" to listOf("shop", "ranks")
                ),
                "stats" to mapOf("type" to "Postgres", "database" to "stats", "port" to 6432, "pool-size" to "8")
            )
        )
        assertTrue(config.invalid.isEmpty(), config.invalid.toString())
        assertEquals(
            ConnectionConfig(DatabaseType.MYSQL, "db.example.com", 3306, "nf", "nf_user", "secret", 4, setOf("shop", "ranks")),
            config.connections["network"]
        )
        assertEquals("jdbc:mysql://db.example.com:3306/nf", config.connections["network"]!!.jdbcUrl)
        // Host defaults to localhost, the port to the type's own, the pool to four, the packages to none (nobody).
        assertEquals(
            ConnectionConfig(DatabaseType.POSTGRES, "localhost", 6432, "stats", "", "", 8, emptySet()),
            config.connections["stats"]
        )
        assertEquals("jdbc:postgresql://localhost:6432/stats", config.connections["stats"]!!.jdbcUrl)
    }

    @Test
    fun `driver properties are kept, and a pool is held to a sane size`() {
        val config = read(
            mapOf(
                "a" to mapOf("type" to "mysql", "database" to "x", "pool-size" to 0, "properties" to mapOf("sslMode" to "REQUIRED")),
                "b" to mapOf("type" to "mysql", "database" to "x", "pool-size" to 5000)
            )
        )
        assertEquals(mapOf("sslMode" to "REQUIRED"), config.connections["a"]!!.properties)
        assertEquals(1, config.connections["a"]!!.poolSize)
        assertEquals(DatabasesConfig.MAX_POOL_SIZE, config.connections["b"]!!.poolSize)
    }

    @Test
    fun `what can't be read is kept with the reason, and the rest still works`() {
        val config = read(
            mapOf(
                "good" to mapOf("type" to "mysql", "database" to "x"),
                "no_type" to mapOf("database" to "x"),
                "oracle" to mapOf("type" to "oracle", "database" to "x"),
                "no_database" to mapOf("type" to "mysql"),
                "bad_port" to mapOf("type" to "mysql", "database" to "x", "port" to 70000),
                "word_port" to mapOf("type" to "mysql", "database" to "x", "port" to "many"),
                "bad_package" to mapOf("type" to "mysql", "database" to "x", "packages" to listOf("Not A Package")),
                "packages_text" to mapOf("type" to "mysql", "database" to "x", "packages" to "shop"),
                "not_a_section" to "mysql://u:p@host/db",
                "Bad Name" to mapOf("type" to "mysql", "database" to "x")
            )
        )
        assertEquals(setOf("good"), config.connections.keys)
        assertEquals(
            setOf("no_type", "oracle", "no_database", "bad_port", "word_port", "bad_package", "packages_text", "not_a_section", "Bad Name"),
            config.invalid.keys
        )
        assertTrue("`type` is missing" in config.invalid.getValue("no_type"))
        assertTrue("isn't mysql or postgres" in config.invalid.getValue("oracle"))
        assertTrue("`database` is missing" in config.invalid.getValue("no_database"))
        assertTrue("`port`" in config.invalid.getValue("bad_port"))
        assertTrue("isn't a package's namespace" in config.invalid.getValue("bad_package"))
        assertTrue("isn't a section" in config.invalid.getValue("not_a_section"))
        // What was written, a URL with a password in it, is never said back.
        assertFalse("mysql://u:p" in config.invalid.getValue("not_a_section"))
    }

    @Test
    fun `a host or database name can't smuggle driver settings into the URL`() {
        val config = read(
            mapOf(
                "host" to mapOf("type" to "mysql", "host" to "db.example.com/x?allowLoadLocalInfile=true", "database" to "x"),
                "at" to mapOf("type" to "mysql", "host" to "user@evil.example", "database" to "x"),
                "database" to mapOf("type" to "mysql", "database" to "x?allowMultiQueries=true"),
                "semicolon" to mapOf("type" to "postgres", "database" to "x;y"),
                "ipv6" to mapOf("type" to "postgres", "host" to "[::1]", "database" to "x")
            )
        )
        assertEquals(setOf("ipv6"), config.connections.keys)
        assertEquals(setOf("host", "at", "database", "semicolon"), config.invalid.keys)
    }

    @Test
    fun `a connection never prints its password`() {
        val connection = ConnectionConfig(DatabaseType.MYSQL, "h", 3306, "d", "u", "hunter2")
        assertFalse("hunter2" in connection.toString())
        assertEquals("mysql connection to h:3306/d as u", connection.toString())
    }

    @Test
    fun `the section is read from config yml, on a production and a dev server`() {
        val section = mapOf("network" to mapOf("type" to "mysql", "database" to "nf", "packages" to listOf("shop")))
        val server = ServerAddress(Path.of("/srv"), "")
        val production = RuntimeConfig.read(Path.of("/srv/plugins/NetherForge"), mapOf("project" to "p", "databases" to section), server)!!
        assertEquals(setOf("network"), production.databases.connections.keys)
        val dev = RuntimeConfig.read(
            Path.of("/srv/plugins/NetherForge"),
            mapOf("databases" to section),
            server,
            properties = { if (it == "netherforge.project") "/p" else null }
        )!!
        assertEquals(setOf("network"), dev.databases.connections.keys)
        assertTrue(
            RuntimeConfig.read(Path.of("/srv/plugins/NetherForge"), mapOf("project" to "p"), server)!!.databases.connections.isEmpty()
        )
    }

    @Test
    fun `the pool is HikariCP, configured from the connection and not connecting until asked`() {
        val connection = ConnectionConfig(
            DatabaseType.POSTGRES,
            "localhost",
            1,
            "nf",
            "u",
            "p",
            poolSize = 3,
            properties = mapOf("ssl" to "false")
        )
        (HikariSources.pool("network", connection) as HikariDataSource).use { pool ->
            assertEquals("netherforge-network", pool.poolName)
            assertEquals("jdbc:postgresql://localhost:1/nf", pool.jdbcUrl)
            assertEquals("org.postgresql.Driver", pool.driverClassName)
            assertEquals(3, pool.maximumPoolSize)
            assertEquals("u", pool.username)
            // A server that's down at start-up doesn't fail making the pool: the first statement says so.
            assertEquals(-1, pool.initializationFailTimeout)
            assertEquals("false", pool.dataSourceProperties.getProperty("ssl"))
        }
    }

    @Test
    fun `a connection whose libraries the server lacks is told to restart, naming what's missing`() {
        assertEquals(null, HikariSources.missing(DatabaseType.POSTGRES))
        val bare = java.net.URLClassLoader(emptyArray(), null)
        val message = HikariSources.missing(DatabaseType.POSTGRES, bare)!!
        assertTrue("HikariCP" in message && "postgres driver" in message && "restart" in message, message)
        assertTrue("mysql driver" in HikariSources.missing(DatabaseType.MYSQL, bare)!!)
    }
}
