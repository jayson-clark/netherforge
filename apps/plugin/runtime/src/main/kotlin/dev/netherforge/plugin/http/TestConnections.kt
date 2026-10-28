package dev.netherforge.plugin.http

import dev.netherforge.plugin.lua.LuaApiException
import java.nio.charset.StandardCharsets

/**
 * Connections in a script test run (`nf.test`): nothing reaches the network.
 * Each connection's far end is a [Far] the test plays with `nf.test.accept`,
 * `receive` and `disconnect`, all on the main thread. What it says goes
 * through the same [Connection.Sink], [StreamSplitter] and byte count as the
 * network's would, so scripts see exactly what they would on a server, at the
 * next tick.
 */
class TestConnections(private val limits: () -> HttpConfig) : ConnectionTransport {
    private val ends = HashMap<Connection.Sink, Far>()

    /** The far end of the connection [sink] belongs to. */
    fun far(sink: Connection.Sink): Far? = ends[sink]

    override fun stream(request: ConnectionRequest, sink: Connection.Sink): Link = Far(request, sink, false).also { ends[sink] = it }

    override fun webSocket(request: ConnectionRequest, sink: Connection.Sink): Link = Far(request, sink, true).also { ends[sink] = it }

    override fun close() = ends.clear()

    /** One connection's far end, as the test plays it. */
    inner class Far(val request: ConnectionRequest, private val sink: Connection.Sink, private val websocket: Boolean) : Link {
        var accepted = false
            private set

        /** It finished, broke or was closed: the test can't say any more on it. */
        var finished = false
            private set

        private val maxBytes get() = limits().connections.maxMessageBytes

        private val splitter by lazy {
            StreamSplitter(request.format ?: StreamFormat.LINES, maxBytes) { data, type, id ->
                if (!sink.tryMessage(data, type, id)) throw LuaApiException(full())
            }
        }

        override fun send(text: String) = !finished

        override fun close(code: Int, reason: String?) {
            finished = true
        }

        fun accept(status: Int?, headers: Map<String, String>, protocol: String?) {
            if (accepted || finished) throw LuaApiException("bad argument 'connection' (it isn't connecting any more)")
            if (status != null && websocket) throw LuaApiException("bad argument 'options.status' (a WebSocket opens without one)")
            if (status != null && status !in STATUSES) {
                throw LuaApiException("bad argument 'options.status' (100 to 599 expected, got $status)")
            }
            if (protocol != null && (!websocket || protocol !in request.protocols)) {
                throw LuaApiException("bad argument 'options.protocol' (one of the protocols it offered expected, got \"$protocol\")")
            }
            accepted = true
            sink.opened(status ?: if (websocket) SWITCHING else OK, headers.mapKeys { it.key.lowercase() }, protocol)
        }

        fun receive(text: String) {
            if (finished) throw LuaApiException("bad argument 'connection' (it has ended)")
            if (!accepted) accept(null, emptyMap(), null)
            val bytes = text.toByteArray(StandardCharsets.UTF_8)
            if (!sink.received(bytes.size.toLong())) {
                finished = true
                return
            }
            try {
                if (!websocket) {
                    splitter.feed(bytes)
                } else if (bytes.size > maxBytes) {
                    throw MessageTooLarge(maxBytes)
                } else if (!sink.tryMessage(text)) {
                    throw LuaApiException(full())
                }
            } catch (e: MessageTooLarge) {
                finished = true
                sink.ended(ConnectionEnd.LIMIT, detail = e.message)
            }
        }

        fun disconnect(code: Int?, reason: String?, error: String?) {
            if (finished) return
            finished = true
            when {
                error != null -> sink.ended(ConnectionEnd.FAILED, detail = error)
                websocket -> sink.ended(ConnectionEnd.FINISHED, code ?: Connections.NORMAL, reason)
                else -> {
                    try {
                        if (accepted) splitter.finish()
                        sink.ended(ConnectionEnd.FINISHED)
                    } catch (e: MessageTooLarge) {
                        sink.ended(ConnectionEnd.LIMIT, detail = e.message)
                    }
                }
            }
        }

        private fun full() = "the connection already has ${limits().connections.maxQueuedMessages} messages waiting for the scripts " +
            "(http.connections.max-queued-messages): nf.test.advance to let them in"
    }

    private companion object {
        val STATUSES = 100..599
        const val OK = 200
        const val SWITCHING = 101
    }
}
