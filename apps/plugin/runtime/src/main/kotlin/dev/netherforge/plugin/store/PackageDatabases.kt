package dev.netherforge.plugin.store

import dev.netherforge.plugin.async.Workers
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/**
 * The packages' own databases (`nf.db()`): one SQLite file each,
 * `<plugin folder>/databases/<namespace>.db`, on the same [Database] layer as
 * the runtime's [Store] but a file apart, so a package's schema, size and
 * failures are its own and an owner can back one up or delete it alone.
 *
 * They belong to the plugin, not a session: a full reload opens no new
 * connection, and each file is opened the first time its package asks (or
 * migrates) and closed when the plugin is disabled. Every database runs on a
 * lane of its own in the workers (`database:<namespace>`), so one package's
 * slow query never holds up another's.
 */
class PackageDatabases(private val directory: Path, private val workers: () -> Workers, private val report: Database.Report) :
    AutoCloseable {
    private val open = ConcurrentHashMap<String, Database>()

    /** The lane [namespace]'s database runs on, which scripts' work for it is given to. */
    fun lane(namespace: String): Executor = workers().lane("$LANE_PREFIX$namespace")

    /** The file [namespace]'s database is (or will be) in. */
    fun fileOf(namespace: String): Path = directory.resolve("$namespace.db")

    /** [namespace]'s database, opened (and the file made) the first time. Waits for the file to open; a file that can't be is thrown. */
    fun database(namespace: String): Database = open[namespace] ?: synchronized(open) {
        open.getOrPut(namespace) { Database.open(fileOf(namespace), lane(namespace), report) }
    }

    override fun close() {
        synchronized(open) {
            for (database in open.values) database.close()
            open.clear()
        }
    }

    private companion object {
        const val LANE_PREFIX = "database:"
    }
}
