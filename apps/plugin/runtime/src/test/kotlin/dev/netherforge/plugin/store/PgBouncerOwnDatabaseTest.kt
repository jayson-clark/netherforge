package dev.netherforge.plugin.store

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.sql.Connection
import java.sql.DriverManager
import javax.sql.DataSource
import kotlin.test.BeforeTest

/**
 * [SharedOwnDatabaseTest]'s tests against a real PostgreSQL behind a real PgBouncer in `pool_mode = transaction`,
 * when the environment names them (the nightly workflow starts both in containers); skipped otherwise:
 * - `NETHERFORGE_TEST_PGBOUNCER`: a JDBC URL through PgBouncer, user and password in it;
 * - `NETHERFORGE_TEST_POSTGRES`: one straight to the PostgreSQL behind it, for the test's own checks.
 *
 * The database is the test's: the schemas the tests use are dropped before each one.
 */
class PgBouncerOwnDatabaseTest : SharedOwnDatabaseTest() {
    private val pooled: String? = System.getenv("NETHERFORGE_TEST_PGBOUNCER")
    private val straight: String? = System.getenv("NETHERFORGE_TEST_POSTGRES")

    @BeforeTest
    fun clean() {
        assumeTrue(pooled != null && straight != null, "NETHERFORGE_TEST_PGBOUNCER and NETHERFORGE_TEST_POSTGRES aren't set")
        DriverManager.getConnection(straight).use { connection ->
            connection.createStatement().use { statement ->
                for (schema in listOf("test", "chess", "cards")) statement.execute("DROP SCHEMA IF EXISTS \"$schema\" CASCADE")
            }
        }
    }

    override fun postgres(): (String, ConnectionConfig) -> DataSource {
        // A new client connection to PgBouncer for each unit of work, as a pool's would be over time.
        val source = org.postgresql.ds.PGSimpleDataSource().apply { setURL(pooled) }
        return { _, _ -> source }
    }

    override fun direct(sources: (String, ConnectionConfig) -> DataSource): Connection = DriverManager.getConnection(straight)

    override fun locksHeld(sources: (String, ConnectionConfig) -> DataSource): Int = direct(sources).use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory'").use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    // A real server's locks can't be made to meet, or count who was refused.
    override val meets = false
}
