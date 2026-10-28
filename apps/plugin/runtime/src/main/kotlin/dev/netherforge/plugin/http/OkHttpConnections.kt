package dev.netherforge.plugin.http

import dev.netherforge.plugin.async.WorkFailed
import okhttp3.Call
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Connections over the network, with OkHttp and [HttpFetcher]'s guard (its
 * checked `Dns`, the literal check, its request rules and redirect rules).
 *
 * A connection holds a thread for as long as it's open, blocked reading its
 * socket, so connections get threads of their own ([threads], a cached pool
 * that is also OkHttp's [Dispatcher] here) rather than the runtime's workers,
 * as the bridge and the resource pack server do: four of them held open
 * would starve every script's work. How many there are is bounded by the
 * owner's `http.connections.max-total`. A stream reads its body on its own
 * thread (redirects followed first, one hop at a time, as `nf.http.request`
 * does); a WebSocket reads on the thread OkHttp's call runs on. Either waits
 * in [Connection.Sink.message] while the main thread hasn't taken what's
 * queued, so a fast far end is slowed by TCP, not buffered without end.
 *
 * [close] cancels every call and ends the threads: a session that stops
 * leaves no socket open.
 */
class OkHttpConnections(private val fetcher: () -> HttpFetcher, private val limits: () -> HttpConfig) : ConnectionTransport {
    private val numbers = AtomicInteger()

    private val threads: ExecutorService =
        ThreadPoolExecutor(0, Int.MAX_VALUE, IDLE_SECONDS, TimeUnit.SECONDS, SynchronousQueue()) { work ->
            Thread(work, "NetherForge connection ${numbers.incrementAndGet()}").apply { isDaemon = true }
        }

    private val dispatcher = Dispatcher(threads).apply {
        maxRequests = Int.MAX_VALUE
        maxRequestsPerHost = Int.MAX_VALUE
    }

    private val connectionLimits: ConnectionLimits get() = limits().connections

    /** Every WebSocket made: once upgraded, a socket's call is done, so [Dispatcher.cancelAll] no longer reaches it. */
    private val sockets: MutableSet<WebSocket> = Collections.newSetFromMap(ConcurrentHashMap())

    override fun stream(request: ConnectionRequest, sink: Connection.Sink): Link {
        val stream = StreamLink(request, sink)
        threads.execute(stream::run)
        return stream
    }

    private inner class StreamLink(private val request: ConnectionRequest, private val sink: Connection.Sink) : Link {
        @Volatile
        private var current: Call? = null

        @Volatile
        private var closed = false

        override fun send(text: String) = false

        override fun close(code: Int, reason: String?) {
            closed = true
            current?.cancel()
        }

        fun run() {
            val fetcher = fetcher()
            val client = fetcher.connectionClient(dispatcher) { base -> CountingSocketFactory(base, sink) }
            var call = HttpCall(request.method, request.url, request.headers, request.body)
            var redirects = 0
            var url: HttpUrl? = null
            try {
                while (true) {
                    url = call.url.toString().toHttpUrlOrNull() ?: throw WorkFailed("\"${call.url}\" isn't a URL")
                    fetcher.checkLiteral(url)
                    val next = client.newCall(fetcher.request(call, url))
                    current = next
                    if (closed) return
                    val response = next.execute()
                    val location = response.use { read(fetcher, it) } ?: return
                    if (++redirects >
                        HttpConfig.MAX_REDIRECTS
                    ) {
                        throw WorkFailed("it was redirected more than ${HttpConfig.MAX_REDIRECTS} times")
                    }
                    val to = try {
                        call.url.resolve(location.second)
                    } catch (e: IllegalArgumentException) {
                        throw WorkFailed("it was redirected to \"${location.second}\", which isn't a URL")
                    }
                    call = fetcher.redirected(call, to, location.first, request.declared)
                }
            } catch (e: Stopped) {
                // The main thread ended it: nothing more to say.
            } catch (e: MessageTooLarge) {
                sink.ended(ConnectionEnd.LIMIT, detail = e.message)
            } catch (e: WorkFailed) {
                sink.ended(ConnectionEnd.FAILED, detail = e.message)
            } catch (e: IOException) {
                // Over the byte limit, the sink has already ended it; closed by the script, nobody's listening.
                if (!closed && !sink.stopped) sink.ended(ConnectionEnd.FAILED, detail = describe(fetcher, e, url))
            }
        }

        /** Hands over [response]'s body as messages; or, for a redirect, its status and `Location` to follow. */
        private fun read(fetcher: HttpFetcher, response: Response): Pair<Int, String>? {
            val location = response.header("location")
            if (response.code in HttpFetcher.REDIRECTS && location != null) return response.code to location
            sink.opened(response.code, fetcher.headersOf(response.headers), null)
            val splitter = StreamSplitter(request.format ?: StreamFormat.LINES, connectionLimits.maxMessageBytes) { data, type, id ->
                if (!sink.message(data, type, id)) throw Stopped()
            }
            val source = response.body.source()
            val buffer = ByteArray(BUFFER)
            while (true) {
                if (closed || sink.stopped) throw Stopped()
                val read = source.read(buffer)
                if (read < 0) break
                splitter.feed(buffer, read)
            }
            splitter.finish()
            sink.ended(ConnectionEnd.FINISHED)
            return null
        }
    }

    /** The main thread ended a connection while its reader was handing something over. */
    private class Stopped : RuntimeException(null, null, false, false)

    override fun webSocket(request: ConnectionRequest, sink: Connection.Sink): Link {
        val fetcher = fetcher()
        val url = request.url.toString().replaceFirst(WS, "http").toHttpUrlOrNull()
        try {
            url ?: throw WorkFailed("\"${request.url}\" isn't a URL")
            fetcher.checkLiteral(url)
        } catch (e: WorkFailed) {
            sink.ended(ConnectionEnd.FAILED, detail = e.message)
            return Unconnected
        }
        val headers = request.headers.toMutableMap()
        if (request.protocols.isNotEmpty()) headers["Sec-WebSocket-Protocol"] = request.protocols.joinToString(", ")
        val opening = fetcher.request(HttpCall("GET", URI(url.toString()), headers, null), url)
        val client = fetcher.connectionClient(dispatcher) { base -> CountingSocketFactory(base, sink) }
        val socket = client.newWebSocket(opening, SocketListener(fetcher, url, sink))
        sockets += socket
        return object : Link {
            override fun send(text: String) = socket.send(text)

            override fun close(code: Int, reason: String?) {
                if (!socket.close(code, reason)) socket.cancel()
            }
        }
    }

    private inner class SocketListener(private val fetcher: HttpFetcher, private val url: HttpUrl, private val sink: Connection.Sink) :
        WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            sink.opened(response.code, fetcher.headersOf(response.headers), response.header("Sec-WebSocket-Protocol"))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val max = connectionLimits.maxMessageBytes
            if (text.length > max / UTF16_TO_UTF8 && text.toByteArray(StandardCharsets.UTF_8).size > max) {
                webSocket.close(TOO_BIG, "message too big")
                sink.ended(ConnectionEnd.LIMIT, detail = "a message is larger than http.connections.max-message-bytes ($max bytes)")
                return
            }
            // Waiting here is what stops OkHttp reading: the backpressure.
            if (!sink.message(text)) webSocket.cancel()
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            webSocket.close(UNSUPPORTED, "binary messages aren't taken")
            sink.ended(ConnectionEnd.FAILED, detail = "the other side sent a binary message, which nf.http.websocket doesn't take")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            sockets -= webSocket
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(Connections.NORMAL, null)
            sink.ended(ConnectionEnd.FINISHED, code, reason.ifEmpty { null })
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            sockets -= webSocket
            if (sink.stopped) return
            val detail = when {
                response != null && response.code != SWITCHING ->
                    "${url.host} answered ${response.code} instead of opening a WebSocket" +
                        (if (response.code in HttpFetcher.REDIRECTS) " (WebSockets don't follow redirects)" else "")
                t is IOException -> describe(fetcher, t, url)
                else -> t.message ?: t.javaClass.simpleName
            }
            sink.ended(ConnectionEnd.FAILED, detail = detail)
        }
    }

    private fun describe(fetcher: HttpFetcher, e: IOException, url: HttpUrl?): String =
        if (url == null) e.message ?: e.javaClass.simpleName else fetcher.failed(e, url).message.orEmpty()

    /** A WebSocket refused before it was made: there's nothing to send on or close. */
    private object Unconnected : Link {
        override fun send(text: String) = false

        override fun close(code: Int, reason: String?) {}
    }

    override fun close() {
        for (socket in sockets) socket.cancel()
        sockets.clear()
        dispatcher.cancelAll()
        threads.shutdownNow()
    }

    private companion object {
        val WS = Regex("^ws(?=s?://)")
        const val IDLE_SECONDS = 30L
        const val BUFFER = 8192

        /** The most UTF-8 bytes one UTF-16 char becomes. */
        const val UTF16_TO_UTF8 = 3
        const val SWITCHING = 101
        const val UNSUPPORTED = 1003
        const val TOO_BIG = 1009
    }
}
