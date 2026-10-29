package dev.netherforge.plugin.http

import dev.netherforge.plugin.async.WorkFailed
import dev.netherforge.plugin.lua.LuaApiException
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.net.UnknownHostException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/** One request as `nf.http.request` checked it: [method] in capitals, a [url] with an `http` or `https` scheme and a host, and the headers to send. */
class HttpCall(val method: String, val url: URI, val headers: Map<String, String>, val body: ByteArray?)

/** What came back: the status, its headers by lowercase name (repeated ones joined with `, `), the decoded body and the URL it came from. */
class HttpReply(val status: Int, val headers: Map<String, String>, val body: String, val url: URI)

/**
 * Makes `nf.http.request`'s requests, off the main thread (it blocks), with
 * OkHttp: HTTP/1.1 and HTTP/2, TLS with the platform's trust and hostname
 * checks, keep-alive in a small pool.
 *
 * **The SSRF guard is the client's [Dns].** [CheckedDns] resolves a name once
 * (through [resolve]), judges every address with [AddressGuard], refuses the
 * whole name if any is not allowed, and hands OkHttp exactly the addresses it
 * checked, which it then connects to: there is no second lookup to rebind.
 * OkHttp never calls its `Dns` for an IP in the URL, so [checkLiteral] holds
 * those to the same guard, for the first URL and each redirect's. The client
 * has `Proxy.NO_PROXY` (the owner's proxy environment and properties are
 * never read), no cache, no cookies, no automatic redirects, and no retry of
 * a failed connection (a POST is never sent twice).
 *
 * **Redirects are followed here**, one OkHttp call per hop, so every hop is
 * held to the same rules: [declared] (the host requirement, as the calling
 * package's), the address check, `https` never downgraded to `http`,
 * `Authorization`, `Cookie` and `Proxy-Authorization` dropped when the host
 * changes, at most [HttpConfig.MAX_REDIRECTS] of them, all inside one
 * [HttpConfig.timeoutSeconds]. Every failure is a [WorkFailed], which a script
 * sees as `nil, err`.
 *
 * [close] stops OkHttp's threads and pooled connections: a session that ends
 * must not leave them behind.
 */
class HttpFetcher(
    private val limits: HttpConfig,
    /** Where a name leads: called once for each connection made to a host that isn't an IP in the URL. */
    private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    /** Makes the sockets connections are made on; a test's records and redirects them. */
    private val socketFactory: SocketFactory = SocketFactory.getDefault(),
    /**
     * This machine's own addresses (every network interface's), refused like private ones: a public address the
     * server itself has would otherwise reach whatever listens on it (RCON, the pack server, the network's hub).
     */
    private val ownAddresses: () -> Set<InetAddress> = OwnAddresses::current
) : Closeable {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .cache(null)
        .dns(CheckedDns())
        .socketFactory(socketFactory)
        .connectionPool(ConnectionPool(MAX_IDLE, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS))
        .connectTimeout(limits.timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .readTimeout(limits.timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .writeTimeout(limits.timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .build()

    /** The refusal and "doesn't resolve" answers of [CheckedDns], which OkHttp passes through as the failure of the call. */
    private class Unreachable(message: String) : UnknownHostException(message)

    private inner class CheckedDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val addresses = try {
                resolve(hostname)
            } catch (e: UnknownHostException) {
                throw Unreachable("$hostname doesn't resolve")
            }
            if (addresses.isEmpty()) throw Unreachable("$hostname doesn't resolve")
            // Every address it leads to is checked, not just the one used: a name that leads anywhere private is refused whole.
            for (address in addresses) refusal(hostname, address)?.let { throw Unreachable(it) }
            return addresses
        }
    }

    /** Why [address] is out of bounds for a request to [host], in the words a script is told, or null when it may be reached. */
    private fun refusal(host: String, address: InetAddress): String? {
        val why = AddressGuard.refusal(address, limits.allowPrivateAddresses)
            ?: "this server's own address".takeIf { !limits.allowPrivateAddresses && address in ownAddresses() }
            ?: return null
        val leads = if (host == address.hostAddress) "$host is" else "$host leads to ${address.hostAddress},"
        return "$leads $why, which nf.http.request doesn't reach " +
            "(the server owner can allow private addresses with http.allow-private-addresses in config.yml)"
    }

    /**
     * An IP in [url] is never looked up, so it's judged here. What OkHttp takes
     * for an IP (`LITERAL`, its own test) is checked, and it must be one the JDK
     * reads without a lookup: IPv6, or four plain decimal numbers. Spellings
     * like `2130706433`, `127.1` or `0177.0.0.1` are refused rather than guessed
     * at: the JDK would read some of them as addresses and, given others, ask
     * DNS, and OkHttp would then connect to what it read.
     */
    internal fun checkLiteral(url: HttpUrl) {
        val host = url.host
        if (!LITERAL.matches(host)) return
        val address =
            literal(host) ?: throw WorkFailed("$host isn't an IP address nf.http.request can send to (write IPv4 as four plain numbers)")
        refusal(host, address)?.let { throw WorkFailed(it) }
    }

    private fun literal(host: String): InetAddress? {
        if (':' in host) {
            return try {
                InetAddress.getByName(host)
            } catch (e: UnknownHostException) {
                null
            }
        }
        val parts = host.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for ((i, part) in parts.withIndex()) {
            if (part.isEmpty() || part.length > 3 || (part.length > 1 && part[0] == '0') || part.any { it !in '0'..'9' }) return null
            bytes[i] = (part.toIntOrNull()?.takeIf { it <= 255 } ?: return null).toByte()
        }
        return InetAddress.getByAddress(bytes)
    }

    /**
     * Sends [call] and follows its redirects. [declared] is the host
     * requirement for a redirect's host (it throws what `Requirements.check`
     * does), checked here because it happens after the script's call.
     */
    fun fetch(call: HttpCall, declared: (host: String) -> Unit): HttpReply {
        val deadline = System.nanoTime() + limits.timeoutSeconds * NANOS_PER_SECOND
        var current = call
        var redirects = 0
        while (true) {
            val (reply, location) = exchange(current, deadline)
            if (reply.status !in REDIRECTS || location == null) return reply
            if (++redirects > HttpConfig.MAX_REDIRECTS) throw WorkFailed("it was redirected more than ${HttpConfig.MAX_REDIRECTS} times")
            val next = try {
                current.url.resolve(location)
            } catch (e: IllegalArgumentException) {
                throw WorkFailed("it was redirected to \"$location\", which isn't a URL")
            }
            current = redirected(current, next, reply.status, declared)
        }
    }

    internal fun redirected(from: HttpCall, to: URI, status: Int, declared: (String) -> Unit): HttpCall {
        val host = hostOf(to) ?: throw WorkFailed("it was redirected to \"$to\", which has no host or isn't http or https")
        if (from.url.scheme == "https" && to.scheme != "https") throw WorkFailed("it was redirected from https to plain http ($to)")
        try {
            declared(host)
        } catch (e: LuaApiException) {
            throw WorkFailed("it was redirected to $host, and ${e.message}")
        }
        // 303 is always a GET; 301 and 302 turn a POST into one, as every client does; 307 and 308 repeat the request.
        val toGet = (status == 303 && from.method != "HEAD") || ((status == 301 || status == 302) && from.method == "POST")
        val sameHost = hostOf(from.url) == host && from.url.scheme == to.scheme && portOf(from.url) == portOf(to)
        val headers = from.headers.filterKeys { name ->
            (sameHost || name.lowercase() !in CREDENTIALS) && (!toGet || name.lowercase() !in BODY_HEADERS)
        }
        return HttpCall(if (toGet) "GET" else from.method, to, headers, if (toGet) null else from.body)
    }

    /** One hop: the reply, and the `Location` it carries. */
    private fun exchange(call: HttpCall, deadline: Long): Pair<HttpReply, String?> {
        val url = call.url.toString().toHttpUrlOrNull() ?: throw WorkFailed("\"${call.url}\" isn't a URL")
        checkLiteral(url)
        val left = deadline - System.nanoTime()
        if (left <= 0) throw timedOut()
        val hop = client.newBuilder().callTimeout(left, TimeUnit.NANOSECONDS).build()
        try {
            hop.newCall(request(call, url)).execute().use { response ->
                val headers = headersOf(response.headers)
                val body = if (call.method == "HEAD") ByteArray(0) else read(response)
                return HttpReply(response.code, headers, decode(body, headers["content-type"]), call.url) to headers["location"]
            }
        } catch (e: WorkFailed) {
            throw e
        } catch (e: IOException) {
            throw failed(e, url)
        }
    }

    internal fun request(call: HttpCall, url: HttpUrl): Request {
        val headers = Headers.Builder().apply {
            if (call.headers.keys.none { it.equals("user-agent", ignoreCase = true) }) add("User-Agent", USER_AGENT)
            for ((name, value) in call.headers) add(name, value)
        }.build()
        val body = when {
            call.body != null -> call.body.toRequestBody()
            call.method in BODY_METHODS -> ByteArray(0).toRequestBody()
            else -> null
        }
        return Request.Builder().url(url).headers(headers).method(call.method, body).build()
    }

    internal fun headersOf(headers: Headers): Map<String, String> {
        val all = LinkedHashMap<String, String>()
        for (name in headers.names()) all[name.lowercase()] = headers.values(name).joinToString(", ")
        return all
    }

    /** The body, read only as far as [HttpConfig.maxResponseBytes] allows: a longer one fails, however its length was told (or hidden, as gzip does). */
    private fun read(response: Response): ByteArray {
        val cap = limits.maxResponseBytes
        val body = response.body
        if (body.contentLength() > cap) throw tooLarge()
        val source = body.source()
        val out = okio.Buffer()
        while (true) {
            val read = source.read(out, BUFFER)
            if (read < 0) return out.readByteArray()
            if (out.size > cap) throw tooLarge()
        }
    }

    private fun tooLarge() = WorkFailed("the response is larger than http.max-response-bytes (${limits.maxResponseBytes} bytes)")

    private fun timedOut() = WorkFailed("it timed out after ${limits.timeoutSeconds} seconds")

    /** What OkHttp's [e] says, in this API's words: a refusal or a name that doesn't resolve ([Unreachable]) as it was worded, a timeout, a connection, the rest. */
    internal fun failed(e: IOException, url: HttpUrl): WorkFailed {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is Unreachable) return WorkFailed(cause.message.orEmpty())
            cause = cause.cause?.takeIf { it !== cause }
        }
        if (e is InterruptedIOException) return timedOut()
        if (e is ConnectException) return WorkFailed("couldn't connect to ${url.host}:${url.port}: ${e.message ?: "no address answered"}")
        return WorkFailed("the request to ${url.host} failed: ${e.message ?: e.javaClass.simpleName}")
    }

    private fun decode(body: ByteArray, contentType: String?): String {
        val name = contentType?.let { CHARSET.find(it)?.groupValues?.get(1)?.trim('"') }
        val charset = try {
            name?.let(Charset::forName) ?: StandardCharsets.UTF_8
        } catch (e: IllegalArgumentException) {
            StandardCharsets.UTF_8
        }
        return String(body, charset)
    }

    /**
     * A client for one connection that stays open (`nf.http.stream`'s,
     * `nf.http.websocket`'s): this one's guard and rules (the checked [Dns], no
     * proxy, redirects left to the caller), on [dispatcher]'s threads, with a
     * pool of its own that keeps nothing (so no two connections share a socket,
     * and each one's bytes are its own), sockets from [sockets] (given this
     * fetcher's factory), no read or call timeout (the main thread ends one that
     * idles), and a WebSocket ping every [PING_SECONDS].
     */
    internal fun connectionClient(dispatcher: Dispatcher, sockets: (SocketFactory) -> SocketFactory): OkHttpClient = client.newBuilder()
        .dispatcher(dispatcher)
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .socketFactory(sockets(socketFactory))
        .readTimeout(0, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .pingInterval(PING_SECONDS, TimeUnit.SECONDS)
        .build()

    /** Cancels what's in flight, closes pooled connections and ends OkHttp's threads: after this the fetcher makes no more requests. */
    override fun close() {
        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    /** Connections the client holds open right now (for the test of [close]). */
    internal val pooledConnections: Int get() = client.connectionPool.connectionCount()

    /** Whether OkHttp's dispatcher threads were told to end (for the test of [close]). */
    internal val stopped: Boolean get() = client.dispatcher.executorService.isShutdown

    companion object {
        internal val REDIRECTS = setOf(301, 302, 303, 307, 308)
        private const val PING_SECONDS = 30L
        private val CREDENTIALS = setOf("authorization", "cookie", "proxy-authorization")
        private val BODY_HEADERS = setOf("content-type", "content-length", "content-encoding")
        private val BODY_METHODS = setOf("POST", "PUT", "PATCH")
        private val CHARSET = Regex("charset=([^;\\s]+)", RegexOption.IGNORE_CASE)

        /** What OkHttp takes for an IP address in a URL's host, and so never looks up. */
        private val LITERAL = Regex("""([0-9a-fA-F]*:[0-9a-fA-F:.]*)|([\d.]+)""")
        private const val USER_AGENT = "NetherForge"
        private const val MAX_IDLE = 5
        private const val KEEP_ALIVE_SECONDS = 30L
        private const val BUFFER = 8192L
        private const val NANOS_PER_SECOND = 1_000_000_000L

        /** [url]'s host in lowercase, or null when it has none or isn't http or https. */
        fun hostOf(url: URI): String? {
            if (url.scheme != "http" && url.scheme != "https") return null
            return url.host?.lowercase()?.takeIf { it.isNotEmpty() }
        }

        /** [url]'s port, or its scheme's default. */
        fun portOf(url: URI): Int = if (url.port == -1) defaultPort(url.scheme) else url.port

        private fun defaultPort(scheme: String) = if (scheme == "https") 443 else 80
    }
}

/** This machine's addresses, read from its network interfaces at most every [EVERY_NANOS] (they rarely change). */
internal object OwnAddresses {
    private const val EVERY_NANOS = 60_000_000_000L

    @Volatile private var cached: Pair<Long, Set<InetAddress>>? = null

    fun current(): Set<InetAddress> {
        val now = System.nanoTime()
        cached?.let { (at, addresses) -> if (now - at < EVERY_NANOS) return addresses }
        val addresses = try {
            java.net.NetworkInterface.networkInterfaces().toList().flatMap { it.inetAddresses().toList() }.toSet()
        } catch (_: java.net.SocketException) {
            emptySet()
        }
        cached = now to addresses
        return addresses
    }
}
