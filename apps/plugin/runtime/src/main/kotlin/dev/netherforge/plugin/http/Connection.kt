package dev.netherforge.plugin.http

import dev.netherforge.plugin.binding.LuaHandle
import java.net.URI
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Why a connection ended, as its `end` event's `reason` says it. */
enum class ConnectionEnd(val luaName: String) {
    FINISHED("finished"),
    CLOSED("closed"),
    FAILED("failed"),
    LIMIT("limit"),
    IDLE("idle"),
    UNLOADED("unloaded")
}

/** What a script asked to open, checked on the main thread: where, with which headers and body, and (for a stream) how to split it. */
class ConnectionRequest(
    val url: URI,
    val method: String,
    val headers: Map<String, String>,
    val body: ByteArray?,
    /** A stream's; null for a WebSocket. */
    val format: StreamFormat?,
    /** A WebSocket's subprotocols to offer. */
    val protocols: List<String>,
    /** The host requirement for a redirect's host, bound to the calling package (`Requirements.held`); it throws what a refused check does. */
    val declared: (host: String) -> Unit
)

/** What a transport hears from the far end, for the main thread to hand scripts in order. */
sealed interface Inbound {
    class Opened(val status: Int, val headers: Map<String, String>, val protocol: String?) : Inbound

    class Message(val data: String, val type: String?, val id: String?) : Inbound

    class Ended(val reason: ConnectionEnd, val code: Int? = null, val detail: String? = null) : Inbound
}

/** The transport's half of a connection: what the main thread asks of the far end. Every method may be called on the main thread and must not block. */
interface Link {
    /** Queues a WebSocket text message; false when it can't be sent any more. */
    fun send(text: String): Boolean

    /** Closes a WebSocket with [code] and [reason], or drops a stream: the script or the runtime ended it. */
    fun close(code: Int, reason: String?)
}

/**
 * Where connections go: the network ([OkHttpConnections]), or the test's hands
 * ([TestConnections]) in a script test run, where nothing reaches the network.
 */
interface ConnectionTransport : AutoCloseable {
    fun stream(request: ConnectionRequest, sink: Connection.Sink): Link

    fun webSocket(request: ConnectionRequest, sink: Connection.Sink): Link

    /** Ends every connection it made and whatever threads it holds. */
    override fun close()
}

/**
 * One connection a script opened: `nf.http.stream`'s or `nf.http.websocket`'s.
 * The main thread owns everything here but [inbound], [room] and [ending],
 * which the transport's threads reach through [sink].
 */
class Connection(
    val number: Long,
    val handle: LuaHandle,
    /** The scope that opened it, which it ends with. */
    val owner: Int,
    val namespace: String,
    val url: String,
    val openedAt: Long,
    maxQueued: Int,
    /** Counts bytes moved against the package's limit: false when they'd go past it. From any thread. */
    private val meter: (Long) -> Boolean = { true },
    /** Why the package is over its byte limit, in a script's words. */
    private val meterRefusal: () -> String = { "" }
) {
    val websocket: Boolean get() = handle is LuaHandle.WebSocket

    var opened = false
        internal set
    var status: Int? = null
        internal set
    var protocol: String? = null
        internal set

    /** The tick something was last sent or handed to scripts on it. */
    var lastActivity: Long = openedAt
        internal set

    /** Set when the main thread ends it: `end` is being or has been heard, and nothing more is read. */
    var ended = false
        internal set

    internal var link: Link? = null

    /** Messages scripts sent, kept only in a test run (`nf.test.sent`). */
    internal val sent = ArrayList<String>()

    internal val inbound = ConcurrentLinkedQueue<Inbound>()

    /** Room for messages waiting for the main thread: a reader takes a permit per message and waits for one when there's none. */
    internal val room = Semaphore(maxQueued)

    @Volatile
    internal var ending = false

    private val endHeard = AtomicBoolean()

    /** What the transport tells the main thread, from any thread. */
    val sink = Sink()

    inner class Sink {
        fun opened(status: Int, headers: Map<String, String>, protocol: String?) {
            if (!endHeard.get()) inbound.add(Inbound.Opened(status, headers, protocol))
        }

        /**
         * A message, once there's room for it: a reader waits here while the
         * main thread hasn't taken [room]'s worth, which stops it reading the
         * network. False when the connection ended meanwhile (stop reading).
         */
        fun message(data: String, type: String? = null, id: String? = null): Boolean {
            while (!room.tryAcquire(WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                if (ending || endHeard.get()) return false
            }
            if (ending || endHeard.get()) return false
            inbound.add(Inbound.Message(data, type, id))
            return true
        }

        /** A message if there's room right now: the test's `receive`, on the main thread, which mustn't wait. */
        fun tryMessage(data: String, type: String? = null, id: String? = null): Boolean {
            if (!room.tryAcquire()) return false
            inbound.add(Inbound.Message(data, type, id))
            return true
        }

        /** The far end finished, broke or was refused: heard once, after whatever came before it. */
        fun ended(reason: ConnectionEnd, code: Int? = null, detail: String? = null) {
            if (endHeard.compareAndSet(false, true)) inbound.add(Inbound.Ended(reason, code, detail))
        }

        /**
         * Counts [bytes] read from the network against the package's limit.
         * Past it, the connection ends `"limit"` and this is false: the reader
         * stops (throws) before buffering any more.
         */
        fun received(bytes: Long): Boolean {
            if (meter(bytes)) return true
            ended(ConnectionEnd.LIMIT, detail = meterRefusal())
            return false
        }

        /** Whether the main thread has ended it: a reader stops. */
        val stopped: Boolean get() = ending
    }

    private companion object {
        const val WAIT_MILLIS = 100L
    }
}
