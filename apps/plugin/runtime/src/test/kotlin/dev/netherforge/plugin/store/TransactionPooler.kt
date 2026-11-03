package dev.netherforge.plugin.store

import java.io.PrintWriter
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.Statement
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Logger
import javax.sql.DataSource

/**
 * PostgreSQL behind a transaction pooler (PgBouncer's `pool_mode = transaction`), for tests: one H2 database in
 * PostgreSQL mode with a few backends (H2 sessions) shared by every client connection. Each transaction, and each
 * autocommit statement, gets the next free backend, and nothing a client set on one is reset when it goes back: a
 * client relying on session state finds it on a backend it isn't on, as it would behind PgBouncer.
 *
 * PostgreSQL's transaction-scoped functions are defined on it as H2 aliases, scoped like the server's to the backend's
 * transaction, which the pooler ends: `set_config(name, value, true)` (the `search_path`, put back at the end; H2's
 * own `set_config` does nothing, so a client's calls are sent to `fake_set_config`) and
 * `pg_try_advisory_xact_lock` (released at the end). Their session-scoped kin, `pg_try_advisory_lock` and
 * `pg_advisory_unlock`, are there too, held by the backend that took them, so code that still used them would leave
 * a lock held. With [Locks.meet], a lock's first holder waits until another has tried ([Locks.contended]), so two
 * servers starting together really meet at it.
 */
class TransactionPooler(backends: Int = 4) : DataSource {
    private val url = "jdbc:h2:mem:pooled${serial.incrementAndGet()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"

    /** One H2 session, which a client has for one transaction or one autocommit statement at a time. */
    private class Backend(val connection: Connection) {
        init {
            // The functions are found from any schema, as PostgreSQL's own (pg_catalog's) are.
            connection.createStatement().use { it.execute("SET SCHEMA_SEARCH_PATH public") }
        }

        val session: Int = connection.createStatement().use { s ->
            s.executeQuery("SELECT SESSION_ID()").use {
                it.next()
                it.getInt(1)
            }
        }
    }

    private val all: List<Backend>
    private val free = LinkedBlockingDeque<Backend>()

    init {
        val source = org.h2.jdbcx.JdbcDataSource().apply {
            setURL(this@TransactionPooler.url)
            user = "sa"
        }
        source.connection.use { connection ->
            connection.createStatement().use {
                val functions = Pg::class.java.name
                it.execute("CREATE ALIAS IF NOT EXISTS fake_set_config FOR \"$functions.setConfig\"")
                it.execute("CREATE ALIAS IF NOT EXISTS pg_try_advisory_xact_lock FOR \"$functions.tryXactLock\"")
                it.execute("CREATE ALIAS IF NOT EXISTS pg_try_advisory_lock FOR \"$functions.tryLock\"")
                it.execute("CREATE ALIAS IF NOT EXISTS pg_advisory_unlock FOR \"$functions.unlock\"")
            }
        }
        all = List(backends) { Backend(source.connection) }
        free.addAll(all)
    }

    /** How many locks of any kind are held now. */
    val locksHeld: Int get() = Locks.held.size

    /** A connection straight to the database, past the pooler, for a test's own checks. */
    fun direct(): Connection = java.sql.DriverManager.getConnection(url, "sa", "")

    // The next free one, given back at the end of the queue: one client's next unit of work is on another backend.
    private fun lease(): Backend = free.pollFirst(10, TimeUnit.SECONDS) ?: error("every backend stayed busy for 10 seconds")

    private fun release(backend: Backend) {
        free.addLast(backend)
    }

    /** The backend's transaction is over: what it scoped to the transaction ends with it. */
    private fun ended(backend: Backend) {
        Pg.ended(backend.session, backend.connection)
    }

    override fun getConnection(): Connection = client()

    override fun getConnection(username: String?, password: String?): Connection = client()

    /** One client connection: in a transaction it keeps one backend until commit or rollback, else one per statement. */
    private fun client(): Connection {
        var autoCommit = true
        var leased: Backend? = null
        var closed = false

        fun finish(commit: Boolean) {
            val backend = leased ?: return
            leased = null
            if (commit) backend.connection.commit() else backend.connection.rollback()
            backend.connection.autoCommit = true
            ended(backend)
            release(backend)
        }

        /** A statement made on a backend: the transaction's, or one of its own that goes back when it's closed. */
        fun statement(method: Method, args: Array<out Any?>?): Any {
            val inTransaction = !autoCommit
            val backend = leased ?: lease().also {
                if (inTransaction) {
                    it.connection.autoCommit = false
                    leased = it
                }
            }
            // H2's PostgreSQL mode has a set_config of its own, which does nothing: the client's calls go to the fake one.
            val sql = args?.firstOrNull() as? String
            val given = if (sql != null &&
                "set_config(" in sql
            ) {
                Array(args.size) {
                    if (it ==
                        0
                    ) {
                        sql.replace("set_config(", "fake_set_config(")
                    } else {
                        args[it]
                    }
                }
            } else {
                args
            }
            val made = invoke(backend.connection, method, given) as Statement
            if (inTransaction) return made
            var back = false
            return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(method.returnType)) { _, m, a ->
                if (m.name == "close") {
                    made.close()
                    if (!back) {
                        back = true
                        ended(backend)
                        release(backend)
                    }
                    null
                } else {
                    invoke(made, m, a)
                }
            }
        }

        val handler = InvocationHandler { proxy, method, args ->
            when (method.name) {
                "getAutoCommit" -> autoCommit
                "setAutoCommit" -> {
                    val on = args!![0] as Boolean
                    if (on && !autoCommit) finish(commit = true)
                    autoCommit = on
                    null
                }
                "commit" -> finish(commit = true)
                "rollback" -> if (args.isNullOrEmpty()) finish(commit = false) else error("savepoints aren't pooled here")
                "close" -> {
                    if (!closed) finish(commit = false)
                    closed = true
                    null
                }
                "isClosed" -> closed
                "isValid" -> !closed
                "unwrap" -> proxy
                "isWrapperFor" -> false
                "toString" -> "pooled client"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args!![0]
                "createStatement", "prepareStatement", "prepareCall" -> statement(method, args)
                else -> {
                    // Anything else (setSchema, getCatalog…) is a statement of its own on whichever backend is free.
                    val backend = leased ?: lease()
                    try {
                        invoke(backend.connection, method, args)
                    } finally {
                        if (backend !== leased) release(backend)
                    }
                }
            }
        }
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java), handler) as Connection
    }

    private fun invoke(target: Any, method: Method, args: Array<out Any?>?): Any? = try {
        method.invoke(target, *(args ?: emptyArray()))
    } catch (e: InvocationTargetException) {
        throw e.targetException
    }

    fun close() {
        all.forEach { runCatching { it.connection.close() } }
    }

    override fun getLogWriter(): PrintWriter? = null

    override fun setLogWriter(out: PrintWriter?) = Unit

    override fun setLoginTimeout(seconds: Int) = Unit

    override fun getLoginTimeout(): Int = 0

    override fun getParentLogger(): Logger = Logger.getGlobal()

    override fun <T : Any?> unwrap(iface: Class<T>?): T = throw java.sql.SQLException("not a wrapper")

    override fun isWrapperFor(iface: Class<*>?): Boolean = false

    /** Advisory locks across every pooler: by key, each held by one H2 session, for the transaction or the session. */
    object Locks {
        class Hold(val session: Int, val transaction: Boolean)

        val held = ConcurrentHashMap<Long, Hold>()

        /** Whether a lock's first holder waits (up to 5 seconds) for another to try it. */
        @Volatile
        var meet = false

        /** Counted down by a try that found the lock held: the first holder waits for one, so two servers really meet. */
        @Volatile
        var contended = CountDownLatch(1)
        val refusals = AtomicInteger()

        fun reset() {
            held.clear()
            meet = false
            contended = CountDownLatch(1)
            refusals.set(0)
        }

        fun tryLock(session: Int, key: Long, transaction: Boolean): Boolean {
            val mine = Hold(session, transaction)
            val holder = held.putIfAbsent(key, mine)
            if (holder != null) {
                // PostgreSQL's advisory locks are reentrant for the session that holds them.
                if (holder.session == session) return true
                refusals.incrementAndGet()
                contended.countDown()
                return false
            }
            if (meet) contended.await(5, TimeUnit.SECONDS)
            return true
        }
    }

    /** The PostgreSQL functions, as H2 calls them: given the calling session's connection first. */
    object Pg {
        /** Per session, the schema to put back when its transaction ends (set by `set_config(…, true)`). */
        private val restore = ConcurrentHashMap<Int, String>()

        private fun session(connection: Connection): Int = connection.createStatement().use { s ->
            s.executeQuery("SELECT SESSION_ID()").use {
                it.next()
                it.getInt(1)
            }
        }

        @JvmStatic
        fun setConfig(connection: Connection, name: String, value: String, local: Boolean): String {
            require(name == "search_path") { "only search_path is faked" }
            val schema = value.trim().removeSurrounding("\"")
            val session = session(connection)
            if (local) restore.putIfAbsent(session, connection.schema) else restore.remove(session)
            connection.schema = schema
            return value
        }

        @JvmStatic
        fun tryXactLock(connection: Connection, key: Long): Boolean = Locks.tryLock(session(connection), key, transaction = true)

        @JvmStatic
        fun tryLock(connection: Connection, key: Long): Boolean = Locks.tryLock(session(connection), key, transaction = false)

        @JvmStatic
        fun unlock(connection: Connection, key: Long): Boolean {
            val session = session(connection)
            val hold = Locks.held[key] ?: return false
            return hold.session == session && !hold.transaction && Locks.held.remove(key, hold)
        }

        /** [session]'s transaction is over: its transaction locks go, and its `search_path` is put back. */
        fun ended(session: Int, connection: Connection) {
            Locks.held.entries.removeIf { it.value.session == session && it.value.transaction }
            restore.remove(session)?.let { connection.schema = it }
        }
    }

    private companion object {
        val serial = AtomicInteger()
    }
}
