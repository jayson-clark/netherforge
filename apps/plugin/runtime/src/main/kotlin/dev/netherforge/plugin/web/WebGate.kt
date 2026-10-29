package dev.netherforge.plugin.web

import com.sun.net.httpserver.HttpExchange
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/** What a request is answered with. */
class WebAnswer(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
    companion object {
        /** A plain-text answer, for the gate's own refusals and the router's `404`s. */
        fun text(status: Int, text: String, headers: Map<String, String> = emptyMap()) =
            WebAnswer(status, mapOf("content-type" to "text/plain; charset=utf-8") + headers, text.toByteArray())
    }
}

/**
 * One request, read and admitted, waiting for (or being handled on) the main
 * thread. [answer] answers it once, from any thread; [reply] writes the
 * answer wherever the request came from (the listener's threads, or a test).
 */
class WebCall(
    val method: String,
    /** The raw path, percent-encoded as it came. */
    val path: String,
    val rawQuery: String?,
    /** By lowercase name, repeated ones joined with `, `. */
    val headers: Map<String, String>,
    val body: ByteArray,
    val ip: String,
    private val reply: (WebAnswer, head: Boolean) -> Unit
) {
    private val answered = AtomicBoolean()

    val isAnswered: Boolean get() = answered.get()

    /** Answers it, unless it has been answered already: false then. */
    fun answer(answer: WebAnswer): Boolean {
        if (!answered.compareAndSet(false, true)) return false
        reply(answer, method == "HEAD")
        return true
    }
}

/**
 * The web routes' door, for the plugin's life: what the listener's threads
 * do with a request before any script sees it, and the queue it waits in for
 * the main thread (the session's [WebRoutes] takes from it every tick).
 *
 * Everything that can be refused without the main thread is refused here, so
 * a flood costs the tick nothing: an address over its requests a minute
 * (`429`), a body over the limit (`413`, read no further than the limit), a
 * path too long (`414`), more waiting than [WebConfig.maxPending] (`503`). The
 * answer is written on the listener's threads, never the main thread: a slow
 * client holds up nobody but itself.
 */
class WebGate(private val config: WebConfig, private val nanoTime: () -> Long = System::nanoTime) {
    val queue = ArrayBlockingQueue<WebCall>(config.maxPending)
    private val rate = AddressRate(config.requestsPerMinutePerIp, nanoTime)

    /**
     * The refusal for a request from [ip] with a body of [length] bytes (null
     * when unknown) to [path], or null when it may go on: what the listener
     * and a test's request are both held to. Counts the request against [ip].
     */
    fun refusal(ip: String, path: String, length: Long?): WebAnswer? = when {
        path.length > MAX_PATH -> WebAnswer.text(414, "That address is too long.")
        !rate.tryStart(ip) -> WebAnswer.text(429, "Too many requests: try again in a minute.", mapOf("retry-after" to "60"))
        length != null && length > config.maxBodyBytes -> tooLarge()
        else -> null
    }

    private fun tooLarge() = WebAnswer.text(413, "The request is larger than this server takes (${config.maxBodyBytes} bytes).")

    /** Queues [call] for the main thread, or answers it `503` when too many already wait. */
    fun admit(call: WebCall) {
        if (!queue.offer(call)) call.answer(WebAnswer.text(503, "The server is busy: try again shortly.", mapOf("retry-after" to "5")))
    }

    /**
     * A request on the listener's thread: refused, or read and queued. Closed
     * by whoever answers it, on [write]'s threads.
     */
    fun accept(exchange: HttpExchange, write: (Runnable) -> Unit) {
        try {
            val ip = clientAddress(exchange)
            val uri = exchange.requestURI
            val length = exchange.requestHeaders.getFirst("Content-Length")?.trim()?.toLongOrNull()
            val headers = LinkedHashMap<String, String>()
            for ((name, values) in exchange.requestHeaders) headers[name.lowercase()] = values.joinToString(", ")
            val writer = { answer: WebAnswer, head: Boolean -> write(Runnable { send(exchange, answer, head) }) }
            refusal(ip, uri.rawPath ?: "/", length)?.let {
                writer(it, exchange.requestMethod == "HEAD")
                return
            }
            val body = read(exchange.requestBody) ?: return writer(tooLarge(), false)
            admit(WebCall(exchange.requestMethod.uppercase(), uri.rawPath ?: "/", uri.rawQuery, headers, body, ip, writer))
        } catch (e: IOException) {
            exchange.close()
        }
    }

    /** The client's address: the connection's, or with [WebConfig.trustProxyHeaders] the one the owner's proxy saw ([forwarded]). */
    private fun clientAddress(exchange: HttpExchange): String {
        val direct = exchange.remoteAddress?.address?.hostAddress ?: "unknown"
        if (!config.trustProxyHeaders) return direct
        return forwarded(exchange.requestHeaders["X-Forwarded-For"]) ?: direct
    }

    /** The body, or null when it's longer than the limit (reading stops there, whatever it said its length was). */
    private fun read(input: InputStream): ByteArray? {
        val limit = config.maxBodyBytes
        val bytes = input.readNBytes(limit + 1)
        return bytes.takeIf { it.size <= limit }
    }

    private fun send(exchange: HttpExchange, answer: WebAnswer, head: Boolean) {
        try {
            for ((name, value) in answer.headers) exchange.responseHeaders.set(name, value)
            val length = if (head ||
                answer.body.isEmpty() ||
                answer.status == 204 ||
                answer.status == 304
            ) {
                -1L
            } else {
                answer.body.size.toLong()
            }
            exchange.sendResponseHeaders(answer.status, length)
            if (length > 0) exchange.responseBody.write(answer.body)
        } catch (e: IOException) {
            // The client went: nothing to tell.
        } finally {
            exchange.close()
        }
    }

    private companion object {
        const val MAX_PATH = 2048
    }
}

/**
 * The client's address from `X-Forwarded-For` (every such header, in order): the
 * last entry, the one the owner's own reverse proxy appended for the connection
 * it took. Every entry before it is whatever the client sent, so trusting the
 * first would let anyone choose their address (and a fresh one per request
 * would never meet the per-address limit).
 */
internal fun forwarded(headers: List<String>?): String? =
    headers.orEmpty().flatMap { it.split(',') }.map { it.trim() }.lastOrNull { it.isNotEmpty() }

/** How many requests each address may make a minute: a sliding window, from the listener's threads. */
internal class AddressRate(private val perMinute: Int, private val nanoTime: () -> Long) {
    private val started = HashMap<String, ArrayDeque<Long>>()
    private var sweeps = 0

    @Synchronized
    fun tryStart(key: String): Boolean {
        val now = nanoTime()
        // Now and then, addresses that went quiet go, so the map can't grow with every address there is.
        if (++sweeps % SWEEP_EVERY == 0) started.values.removeAll { times -> times.isEmpty() || now - times.last() >= MINUTE }
        val times = started.getOrPut(key, ::ArrayDeque)
        while (times.isNotEmpty() && now - times.first() >= MINUTE) times.removeFirst()
        if (times.size >= perMinute) return false
        times.addLast(now)
        return true
    }

    private companion object {
        const val MINUTE = 60_000_000_000L
        const val SWEEP_EVERY = 1024
    }
}
