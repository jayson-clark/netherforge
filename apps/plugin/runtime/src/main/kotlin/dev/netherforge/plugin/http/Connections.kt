package dev.netherforge.plugin.http

import dev.netherforge.plugin.binding.Events
import dev.netherforge.plugin.binding.HttpStreamEndEvent
import dev.netherforge.plugin.binding.HttpStreamMessageEvent
import dev.netherforge.plugin.binding.HttpStreamOpenEvent
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.binding.WebSocketEndEvent
import dev.netherforge.plugin.binding.WebSocketMessageEvent
import dev.netherforge.plugin.binding.WebSocketOpenEvent
import dev.netherforge.plugin.script.Scope
import dev.netherforge.plugin.script.Scripts
import dev.netherforge.plugin.session.RuntimeService
import dev.netherforge.plugin.session.TickPhase
import dev.netherforge.plugin.session.liveness
import java.nio.charset.StandardCharsets

/**
 * The connections scripts hold open: `nf.http.stream`'s and
 * `nf.http.websocket`'s, each its opening scope's, ended (`"unloaded"`) when
 * that scope is released and when the session stops.
 *
 * The transport ([OkHttpConnections], or [TestConnections] in a test run) works
 * off the main thread and only queues what it hears on the [Connection]. Every
 * tick ([TickPhase.ASYNC]) hands scripts what's queued, in order, at most
 * [ConnectionLimits.messagesPerTick] messages per connection; a reader waits
 * while [ConnectionLimits.maxQueuedMessages] are queued, which is the
 * backpressure: nothing is dropped, the network just isn't read. The same
 * pass ends a connection that has been idle too long, or still hasn't
 * answered after `http.timeout-seconds`.
 *
 * The owner's limits on opening (per package, in all, the request rate) don't
 * error: the connection is made and ends `"limit"` at the next tick, as any
 * other limit does. The byte limit is counted per package ([ByteRate]) by the
 * transport as it reads and by [send] as scripts write.
 */
class Connections(
    private val scripts: Scripts,
    private val limits: () -> HttpConfig,
    private val transport: () -> ConnectionTransport,
    private val ticks: () -> Long,
    /** A test run: every connection is remembered, for `nf.test.connections`. */
    private val keepHistory: Boolean = false
) : RuntimeService {
    override val name get() = "connections"

    private val active = LinkedHashMap<Long, Connection>()
    private val history = ArrayList<Connection>()
    private var nextNumber = 1L
    private val bytes by lazy { ByteRate(limits().connections.bytesPerMinute.toLong()) }

    fun find(number: Long): Connection? = active[number]

    /** Every connection opened in this test run, oldest first, whose URL starts with [prefix]. */
    fun history(prefix: String?): List<Connection> = history.filter { prefix == null || it.url.startsWith(prefix) }

    /** The connection [handle] names, ended or not, in a test run. */
    fun recorded(handle: LuaHandle): Connection? = history.firstOrNull { it.handle == handle }

    /**
     * Opens a connection for [scope] to [request]'s URL ([url] as the script
     * wrote it). [rateRefusal] is why the request rate refused it, if it did.
     * Over a limit, it ends `"limit"` at the next tick instead of connecting.
     */
    fun open(scope: Scope, request: ConnectionRequest, url: String, websocket: Boolean, rateRefusal: String?): Connection {
        val number = nextNumber++
        val handle = if (websocket) LuaHandle.WebSocket(number, url) else LuaHandle.HttpStream(number, url)
        val config = limits().connections
        val namespace = scope.namespace
        val connection = Connection(
            number, handle, scope.id, namespace, url, ticks(), config.maxQueuedMessages,
            meter = { bytes -> count(namespace, bytes) },
            meterRefusal = { bytesRefusal(namespace) }
        )
        val refusal = rateRefusal ?: refusal(scope.namespace, config)
        active[number] = connection
        if (keepHistory) history += connection
        if (refusal != null) {
            connection.sink.ended(ConnectionEnd.LIMIT, detail = refusal)
            return connection
        }
        connection.link = if (websocket) transport().webSocket(request, connection.sink) else transport().stream(request, connection.sink)
        return connection
    }

    private fun refusal(namespace: String, config: ConnectionLimits): String? {
        val open = active.values.filter { !it.ended }
        return when {
            open.count { it.namespace == namespace } >= config.maxPerPackage ->
                "package \"$namespace\" already has ${config.maxPerPackage} connections open, the server's limit " +
                    "(http.connections.max-per-package in config.yml)"
            open.size >= config.maxTotal ->
                "the server already has ${config.maxTotal} connections open, its limit (http.connections.max-total in config.yml)"
            else -> null
        }
    }

    /** Whether [bytes] more may move for [namespace] this minute; counts them if so. From any thread. */
    fun count(namespace: String, bytes: Long): Boolean = this.bytes.tryMove(namespace, bytes)

    /** Why [namespace] went over the byte limit, as a connection that hit it says. */
    fun bytesRefusal(namespace: String) =
        "package \"$namespace\" moved more than ${limits().connections.bytesPerMinute} bytes over its connections in the last minute, " +
            "the server's limit (http.connections.bytes-per-minute in config.yml)"

    /** A script sends [text] on [connection]: false when it has ended, or ends it now over the byte limit. */
    fun send(connection: Connection, text: String): Boolean {
        if (connection.ended) return false
        val size = text.toByteArray(StandardCharsets.UTF_8).size.toLong()
        if (!count(connection.namespace, size)) {
            end(connection, ConnectionEnd.LIMIT, detail = bytesRefusal(connection.namespace))
            return false
        }
        if (keepHistory) connection.sent += text
        connection.lastActivity = ticks()
        return connection.link?.send(text) ?: true
    }

    /** A script closes [connection] ([code] and [reason] for a WebSocket's far end). False when it had already ended. */
    fun close(connection: Connection, code: Int = NORMAL, reason: String? = null): Boolean {
        if (connection.ended) return false
        end(connection, ConnectionEnd.CLOSED, closeCode = code, closeReason = reason)
        return true
    }

    override fun tick(phase: TickPhase) {
        if (phase == TickPhase.ASYNC && active.isNotEmpty()) deliver()
    }

    /** A scope stopped: what it opened ends `unloaded` (its own handlers are already gone). */
    override fun scopeReleased(scope: Scope) {
        for (connection in active.values.filter { it.owner == scope.id }) end(connection, ConnectionEnd.UNLOADED)
    }

    override fun stop() {
        for (connection in active.values.toList()) end(connection, ConnectionEnd.UNLOADED)
    }

    override fun costs(): Map<String, (Scope) -> Int> = mapOf("connections" to { scope -> active.values.count { it.owner == scope.id } })

    override fun liveness() = listOf(
        liveness<LuaHandle.HttpStream> { find(it.number) != null },
        liveness<LuaHandle.WebSocket> { find(it.number) != null }
    )

    private fun deliver() {
        val config = limits()
        val now = ticks()
        val idleTicks = config.connections.idleSeconds.toLong() * TICKS_PER_SECOND
        val answerTicks = config.timeoutSeconds.toLong() * TICKS_PER_SECOND
        for (connection in active.values.toList()) {
            var messages = 0
            while (!connection.ended && messages < config.connections.messagesPerTick) {
                when (val item = connection.inbound.poll() ?: break) {
                    is Inbound.Opened -> opened(connection, item, now)
                    is Inbound.Message -> {
                        connection.room.release()
                        messages++
                        connection.lastActivity = now
                        message(connection, item)
                    }
                    is Inbound.Ended -> end(connection, item.reason, item.code, item.detail)
                }
            }
            if (connection.ended) continue
            when {
                !connection.opened && now - connection.openedAt >= answerTicks ->
                    end(
                        connection,
                        ConnectionEnd.FAILED,
                        detail = "it wasn't answered within ${config.timeoutSeconds} seconds (http.timeout-seconds)"
                    )
                connection.opened && now - connection.lastActivity >= idleTicks && connection.inbound.isEmpty() ->
                    end(connection, ConnectionEnd.IDLE)
            }
        }
    }

    private fun opened(connection: Connection, item: Inbound.Opened, now: Long) {
        connection.opened = true
        connection.status = item.status
        connection.protocol = item.protocol
        connection.lastActivity = now
        when (val handle = connection.handle) {
            is LuaHandle.WebSocket -> scripts.emit(Events.WEBSOCKET_OPEN, handle, WebSocketOpenEvent(handle, item.protocol, item.headers))
            is LuaHandle.HttpStream ->
                scripts.emit(Events.HTTPSTREAM_OPEN, handle, HttpStreamOpenEvent(handle, item.status.toLong(), item.headers))
            else -> Unit
        }
    }

    private fun message(connection: Connection, item: Inbound.Message) {
        when (val handle = connection.handle) {
            is LuaHandle.WebSocket -> scripts.emit(Events.WEBSOCKET_MESSAGE, handle, WebSocketMessageEvent(handle, item.data))
            is LuaHandle.HttpStream ->
                scripts.emit(Events.HTTPSTREAM_MESSAGE, handle, HttpStreamMessageEvent(handle, item.data, item.type, item.id))
            else -> Unit
        }
    }

    /**
     * Ends [connection]: the far end is let go of (a WebSocket told why, with
     * [closeCode]), what's still queued is dropped, then its `end` handlers hear
     * it while the handle still answers, and it's gone with every handler on it.
     */
    private fun end(
        connection: Connection,
        reason: ConnectionEnd,
        code: Int? = null,
        detail: String? = null,
        closeCode: Int = closeCodeFor(reason),
        closeReason: String? = null
    ) {
        if (connection.ended) return
        connection.ended = true
        connection.ending = true
        try {
            connection.link?.close(closeCode, closeReason)
        } finally {
            connection.inbound.clear()
        }
        val handle = connection.handle
        try {
            when (handle) {
                is LuaHandle.WebSocket ->
                    scripts.emit(Events.WEBSOCKET_END, handle, WebSocketEndEvent(handle, reason.luaName, code?.toLong(), detail))
                is LuaHandle.HttpStream ->
                    scripts.emit(
                        Events.HTTPSTREAM_END,
                        handle,
                        HttpStreamEndEvent(handle, reason.luaName, connection.status?.toLong(), detail)
                    )
                else -> Unit
            }
        } finally {
            active.remove(connection.number)
            scripts.dropTarget(handle)
        }
    }

    private fun closeCodeFor(reason: ConnectionEnd) = when (reason) {
        ConnectionEnd.UNLOADED -> GOING_AWAY
        ConnectionEnd.LIMIT -> POLICY
        else -> NORMAL
    }

    companion object {
        const val NORMAL = 1000
        const val GOING_AWAY = 1001
        const val POLICY = 1008
        private const val TICKS_PER_SECOND = 20L
    }
}
