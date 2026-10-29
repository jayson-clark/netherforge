package dev.netherforge.plugin.http

import dev.netherforge.plugin.async.WorkFailed
import dev.netherforge.plugin.lua.LuaApiException
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import javax.net.SocketFactory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The request path against OkHttp's MockWebServer on loopback: one lookup per
 * connection and the connection made to the address that was checked, every
 * redirect held to the guard and the host declaration, and the limits.
 *
 * Names are the test's own (`service.test`): [names] maps them to the
 * addresses a test wants (the fetcher's injected resolver), and the socket
 * factory records the address each connection was asked to make, then
 * connects to the local server instead, so a "public" address can be
 * tested without a network.
 */
class HttpFetcherTest {
    private val routes = HashMap<String, (RecordedRequest) -> MockResponse>()
    private val seen = CopyOnWriteArrayList<RecordedRequest>()

    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                return routes[request.url.encodedPath]?.invoke(request) ?: reply(404, "nope")
            }
        }
        start(InetAddress.getLoopbackAddress(), 0)
    }

    /** Where every connection really goes, whatever address it was made to. */
    private var target = server.port
    private val fetchers = ArrayList<HttpFetcher>()
    private val listeners = ArrayList<ServerSocket>()

    /** Name → what it resolves to; a name not in it doesn't resolve. */
    private val names = HashMap<String, List<InetAddress>>()
    private val lookups = CopyOnWriteArrayList<String>()
    private val connected = CopyOnWriteArrayList<InetAddress>()

    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val publicAddress = InetAddress.getByAddress(byteArrayOf(93, 184.toByte(), 216.toByte(), 34))
    private val privateAddress = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 5))

    init {
        names["service.test"] = listOf(loopback)
        names["other.test"] = listOf(loopback)
        names["third.test"] = listOf(loopback)
        names["public.test"] = listOf(publicAddress)
        names["internal.test"] = listOf(privateAddress)
        names["mixed.test"] = listOf(publicAddress, privateAddress)
    }

    @AfterTest
    fun close() {
        fetchers.forEach { it.close() }
        listeners.forEach { it.close() }
        server.close()
    }

    private val sockets = object : SocketFactory() {
        override fun createSocket(): Socket = object : Socket() {
            override fun connect(endpoint: SocketAddress, timeout: Int) {
                connected += (endpoint as InetSocketAddress).address
                super.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), target), timeout)
            }
        }

        override fun createSocket(host: String?, port: Int): Socket = throw UnsupportedOperationException()

        override fun createSocket(host: String?, port: Int, local: InetAddress?, localPort: Int): Socket =
            throw UnsupportedOperationException()

        override fun createSocket(host: InetAddress?, port: Int): Socket = throw UnsupportedOperationException()

        override fun createSocket(address: InetAddress?, port: Int, local: InetAddress?, localPort: Int): Socket =
            throw UnsupportedOperationException()
    }

    private fun fetcher(limits: HttpConfig = HttpConfig(allowPrivateAddresses = true)) = HttpFetcher(
        limits,
        resolve = { host ->
            lookups += host
            names[host] ?: throw UnknownHostException(host)
        },
        socketFactory = sockets
    ).also { fetchers += it }

    private fun call(url: String, method: String = "GET", headers: Map<String, String> = emptyMap(), body: String? = null) =
        HttpCall(method, URI(url), headers, body?.toByteArray())

    private fun route(path: String, handler: (RecordedRequest) -> MockResponse) {
        routes[path] = handler
    }

    private fun reply(status: Int, body: String, vararg headers: Pair<String, String>) = MockResponse.Builder().code(status).apply {
        for ((name, value) in headers) addHeader(name, value)
        body(body)
    }.build()

    private fun redirect(status: Int, to: String) = reply(status, "", "Location" to to)

    private val anyone: (String) -> Unit = {}

    private fun failure(block: () -> Unit) = assertFailsWith<WorkFailed> { block() }.message.orEmpty()

    @Test
    fun `a request resolves its name once and connects to the address it checked`() {
        route("/hello") { reply(200, "world", "X-Thing" to "a", "Content-Type" to "text/plain; charset=utf-8") }
        names["service.test"] = listOf(loopback, privateAddress)
        val reply = fetcher().fetch(call("http://service.test:${server.port}/hello?x=1"), anyone)
        assertEquals(200, reply.status)
        assertEquals("world", reply.body)
        assertEquals("a", reply.headers["x-thing"])
        assertEquals(listOf("service.test"), lookups)
        // The first checked address, and no other was tried (and no other, never looked up, was ever possible).
        assertEquals(listOf(loopback), connected)
        assertEquals("/hello?x=1", seen.single().target)
    }

    @Test
    fun `without the owner's option a name that leads to a private address is refused before any connection`() {
        route("/hello") { reply(200, "world") }
        val strict = fetcher(HttpConfig())
        val message = failure { strict.fetch(call("http://service.test:${server.port}/hello"), anyone) }
        assertTrue("service.test leads to 127.0.0.1, a loopback address" in message, message)
        assertTrue("http.allow-private-addresses" in message, message)
        // One bad address among good ones refuses the whole name: it could lead there next time.
        assertTrue("10.0.0.5, a private address" in failure { strict.fetch(call("http://mixed.test/"), anyone) })
        assertEquals(emptyList(), connected)
        assertEquals(0, server.requestCount)
        // A name that leads nowhere says so.
        assertEquals("nowhere.test doesn't resolve", failure { strict.fetch(call("http://nowhere.test/"), anyone) })
        // A public address is connected to, as the address it is.
        route("/p") { reply(200, "public") }
        assertEquals("public", strict.fetch(call("http://public.test:${server.port}/p"), anyone).body)
        assertEquals(listOf(publicAddress), connected)
    }

    @Test
    fun `this server's own public address is refused like a private one`() {
        val own = HttpFetcher(HttpConfig(), resolve = {
            listOf(publicAddress)
        }, socketFactory = sockets, ownAddresses = { setOf(publicAddress) })
            .also { fetchers += it }
        val message = failure { own.fetch(call("http://me.test:${server.port}/"), anyone) }
        assertTrue("this server's own address" in message, message)
        assertEquals(emptyList(), connected)
    }

    @Test
    fun `an address literal is held to the guard without a lookup`() {
        val strict = fetcher(HttpConfig())
        for (url in listOf(
            "http://127.0.0.1/",
            "http://[::1]/",
            "http://169.254.169.254/latest/meta-data/",
            "http://100.64.0.1/",
            "http://[fd00::1]/",
            "http://[::ffff:127.0.0.1]/"
        )) {
            val message = failure { strict.fetch(call(url), anyone) }
            assertTrue("which nf.http.request doesn't reach" in message, "$url: $message")
        }
        assertEquals(emptyList(), lookups)
        assertEquals(emptyList(), connected)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `spellings of an address OkHttp would read are refused unread, and others go through the lookup`() {
        val strict = fetcher(HttpConfig())
        for (host in listOf("2130706433", "127.1", "0177.0.0.1", "1.2.3.4.5", "256.1.1.1")) {
            names[host] = listOf(publicAddress)
            assertTrue("isn't an IP address" in failure { strict.fetch(call("http://$host/"), anyone) }, host)
        }
        // Not an address by OkHttp's test: a name, looked up and judged like one.
        names["0x7f.1"] = listOf(loopback)
        assertTrue("leads to 127.0.0.1, a loopback address" in failure { strict.fetch(call("http://0x7f.1/"), anyone) })
        assertEquals(listOf("0x7f.1"), lookups)
        assertEquals(emptyList(), connected)
    }

    @Test
    fun `a literal the owner allowed is connected to as it is`() {
        route("/lit") { reply(200, "literal") }
        assertEquals("literal", fetcher().fetch(call("http://127.0.0.1:${server.port}/lit"), anyone).body)
        assertEquals(emptyList(), lookups)
        assertEquals(listOf(loopback), connected)
    }

    @Test
    fun `a post sends its body and headers, and the response is decoded by its charset`() {
        route("/echo") { request ->
            reply(201, "${request.method} ${request.body?.utf8()}", "Content-Type" to "text/plain; charset=ISO-8859-1")
        }
        val reply = fetcher().fetch(
            call("http://service.test:${server.port}/echo", "POST", mapOf("X-Token" to "t", "Content-Type" to "text/plain"), "héllo"),
            anyone
        )
        assertEquals(201, reply.status)
        // The server wrote UTF-8 bytes but said ISO-8859-1: the charset the response names is the one used.
        assertEquals("POST héllo".toByteArray().toString(StandardCharsets.ISO_8859_1), reply.body)
        val request = seen.single()
        assertEquals("service.test:${server.port}", request.headers["Host"])
        assertEquals("NetherForge", request.headers["User-Agent"])
        assertEquals("t", request.headers["X-Token"])
        assertEquals("text/plain", request.headers["Content-Type"])
        assertEquals("6", request.headers["Content-Length"])
        route("/utf8") { reply(200, "é", "Content-Type" to "text/plain; charset=utf-8") }
        assertEquals("é", fetcher().fetch(call("http://service.test:${server.port}/utf8"), anyone).body)
        // A script's own User-Agent wins.
        fetcher().fetch(call("http://service.test:${server.port}/utf8", headers = mapOf("user-agent" to "mine")), anyone)
        assertEquals("mine", seen.last().headers["User-Agent"])
    }

    @Test
    fun `a post without a body still says it has none, and a status that isn't a success is a response`() {
        route("/missing") { reply(404, "nope") }
        route("/empty") { reply(204, "") }
        assertEquals(404, fetcher().fetch(call("http://service.test:${server.port}/missing"), anyone).status)
        assertEquals(204, fetcher().fetch(call("http://service.test:${server.port}/empty", "POST"), anyone).status)
        assertEquals("0", seen.last().headers["Content-Length"])
    }

    @Test
    fun `a head request has no body and repeated headers are joined`() {
        route("/head") { reply(200, "", "X-A" to "1", "X-A" to "2") }
        val head = fetcher().fetch(call("http://service.test:${server.port}/head", "HEAD"), anyone)
        assertEquals("", head.body)
        assertEquals("1, 2", head.headers["x-a"])
    }

    @Test
    fun `chunked bodies are read`() {
        route("/chunked") { MockResponse.Builder().chunkedBody("hello chunks", 4).build() }
        assertEquals("hello chunks", fetcher().fetch(call("http://service.test:${server.port}/chunked"), anyone).body)
    }

    @Test
    fun `an answer that isn't http fails the request`() {
        val raw = rawServer("SSH-2.0-nope\r\n\r\n")
        val message = failure { fetcher().fetch(call("http://service.test:$raw/"), anyone) }
        assertTrue(message.startsWith("the request to service.test failed"), message)
    }

    @Test
    fun `redirects are followed with every hop looked up and checked`() {
        route("/start") { redirect(302, "/middle") }
        route("/middle") { redirect(301, "http://public.test:${server.port}/end") }
        route("/end") { reply(200, "arrived") }
        val reply = fetcher().fetch(call("http://service.test:${server.port}/start"), anyone)
        assertEquals("arrived", reply.body)
        assertEquals("http://public.test:${server.port}/end", reply.url.toString())
        // The first two hops are one host (one connection, kept alive); the third is another name and lookup.
        assertEquals(listOf("service.test", "public.test"), lookups)
        assertEquals(listOf(loopback, publicAddress), connected)
        assertEquals(listOf("/start", "/middle", "/end"), seen.map { it.url.encodedPath })
    }

    @Test
    fun `every hop to another name is a lookup, and a connection reused is the one that was checked`() {
        route("/a") { redirect(302, "http://other.test:${server.port}/b") }
        route("/b") { redirect(302, "http://third.test:${server.port}/c") }
        route("/c") { redirect(302, "http://service.test:${server.port}/d") }
        route("/d") { reply(200, "done") }
        assertEquals("done", fetcher().fetch(call("http://service.test:${server.port}/a"), anyone).body)
        // service.test is back at the end: its connection from the first hop is still the one checked then.
        assertEquals(listOf("service.test", "other.test", "third.test"), lookups)
        assertEquals(listOf(loopback, loopback, loopback), connected)
    }

    @Test
    fun `a redirect to a private address is refused, and never connected to`() {
        route("/start") { redirect(302, "http://internal.test:${server.port}/secret") }
        route("/secret") { reply(200, "secret") }
        // A public first hop (the test's sockets send it to the local server), a private second.
        val strict = fetcher(HttpConfig())
        val message = failure { strict.fetch(call("http://public.test:${server.port}/start"), anyone) }
        assertTrue("internal.test leads to 10.0.0.5, a private address" in message, message)
        assertEquals(listOf(publicAddress), connected)
        assertEquals(listOf("/start"), seen.map { it.url.encodedPath })
    }

    @Test
    fun `a redirect to an address literal is held to the guard`() {
        route("/start") { redirect(302, "http://169.254.169.254/latest/meta-data/") }
        val strict = fetcher(HttpConfig())
        val message = failure { strict.fetch(call("http://public.test:${server.port}/start"), anyone) }
        assertTrue("169.254.169.254" in message && "doesn't reach" in message, message)
        assertEquals(listOf(publicAddress), connected)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a redirect to a host the package didn't declare is refused with the requirement's words`() {
        route("/start") { redirect(307, "http://public.test:${server.port}/end") }
        route("/end") { reply(200, "end") }
        val message = failure {
            fetcher().fetch(call("http://service.test:${server.port}/start")) { host ->
                if (host !=
                    "service.test"
                ) {
                    throw LuaApiException("nf.http.request needs http:$host, which package \"test\" hasn't declared")
                }
            }
        }
        assertEquals(
            "it was redirected to public.test, and nf.http.request needs http:public.test, which package \"test\" hasn't declared",
            message
        )
        assertEquals(listOf("service.test"), lookups)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `redirects keep or change the method as clients do, and credentials stay with their host`() {
        route("/post302") { redirect(302, "/seen") }
        route("/post307") { redirect(307, "/seen") }
        route("/post308") { redirect(308, "/seen") }
        route("/post303") { redirect(303, "http://public.test:${server.port}/seen") }
        route("/seen") { request ->
            reply(
                200,
                "${request.method} '${request.body?.utf8().orEmpty()}' auth=${request.headers["Authorization"]} " +
                    "cookie=${request.headers["Cookie"]} type=${request.headers["Content-Type"]} x=${request.headers["X-Other"]}"
            )
        }
        val headers = mapOf("Authorization" to "secret", "Cookie" to "a=b", "Content-Type" to "text/plain", "X-Other" to "1")
        val fetch = { path: String ->
            fetcher().fetch(call("http://service.test:${server.port}$path", "POST", headers, "data"), anyone).body
        }
        assertEquals("GET '' auth=secret cookie=a=b type=null x=1", fetch("/post302"))
        assertEquals("POST 'data' auth=secret cookie=a=b type=text/plain x=1", fetch("/post307"))
        assertEquals("POST 'data' auth=secret cookie=a=b type=text/plain x=1", fetch("/post308"))
        // To another host: a GET, and the credentials are left behind.
        assertEquals("GET '' auth=null cookie=null type=null x=1", fetch("/post303"))
        // A 307 to another host repeats the request, still without the credentials.
        route("/away307") { redirect(307, "http://public.test:${server.port}/seen") }
        assertEquals("POST 'data' auth=null cookie=null type=text/plain x=1", fetch("/away307"))
    }

    @Test
    fun `a redirect from https to http is refused, and one to https is not`() {
        // No TLS server here (the hop rules don't need one): the rule is the fetcher's own, applied before any connection.
        val fetcher = fetcher()
        val https = call("https://service.test/start", headers = mapOf("Authorization" to "secret"))
        assertEquals(
            "it was redirected from https to plain http (http://service.test/next)",
            failure { fetcher.redirected(https, URI("http://service.test/next"), 302, anyone) }
        )
        val next = fetcher.redirected(https, URI("https://service.test/next"), 302, anyone)
        assertEquals("https://service.test/next", next.url.toString())
        assertEquals(mapOf("Authorization" to "secret"), next.headers)
        // http to https is an upgrade; the port changing is another origin, so the credential stays behind.
        val upgraded = fetcher.redirected(
            call("http://service.test/start", headers = mapOf("Authorization" to "secret")),
            URI("https://service.test/"),
            301,
            anyone
        )
        assertEquals(emptyMap(), upgraded.headers)
    }

    @Test
    fun `redirects past the limit and a redirect that isn't a URL fail`() {
        route("/loop") { redirect(302, "/loop") }
        assertEquals(
            "it was redirected more than ${HttpConfig.MAX_REDIRECTS} times",
            failure { fetcher().fetch(call("http://service.test:${server.port}/loop"), anyone) }
        )
        assertEquals(HttpConfig.MAX_REDIRECTS + 1, server.requestCount)
        route("/bad") { redirect(302, "http://exa mple.com/") }
        assertTrue("which isn't a URL" in failure { fetcher().fetch(call("http://service.test:${server.port}/bad"), anyone) })
        route("/ftp") { redirect(302, "ftp://service.test/file") }
        assertTrue("isn't http or https" in failure { fetcher().fetch(call("http://service.test:${server.port}/ftp"), anyone) })
        // A 3xx without a Location is just a response.
        route("/lost") { reply(302, "no where") }
        assertEquals(302, fetcher().fetch(call("http://service.test:${server.port}/lost"), anyone).status)
    }

    @Test
    fun `a response over the limit fails, whichever way its length is told`() {
        val small = fetcher(HttpConfig(allowPrivateAddresses = true, maxResponseBytes = 10))
        route("/known") { reply(200, "x".repeat(11)) }
        route("/chunked") { MockResponse.Builder().chunkedBody("x".repeat(12), 6).build() }
        route("/exact") { reply(200, "x".repeat(10)) }
        route("/bomb") {
            val packed = Buffer()
            GZIPOutputStream(packed.outputStream()).use { it.write(ByteArray(1024 * 1024)) }
            MockResponse.Builder().addHeader("Content-Encoding", "gzip").body(packed).build()
        }
        for (path in listOf("/known", "/chunked", "/bomb")) {
            val message = failure { small.fetch(call("http://service.test:${server.port}$path"), anyone) }
            assertTrue("larger than http.max-response-bytes (10 bytes)" in message, "$path: $message")
        }
        assertEquals("x".repeat(10), small.fetch(call("http://service.test:${server.port}/exact"), anyone).body)
    }

    @Test
    fun `a slow server times the request out`() {
        route("/slow") { MockResponse.Builder().code(200).body("late").bodyDelay(3, TimeUnit.SECONDS).build() }
        route("/slower") { MockResponse.Builder().code(200).body("late").headersDelay(3, TimeUnit.SECONDS).build() }
        val quick = fetcher(HttpConfig(allowPrivateAddresses = true, timeoutSeconds = 1))
        for (path in listOf("/slow", "/slower")) {
            val started = System.nanoTime()
            assertEquals("it timed out after 1 seconds", failure { quick.fetch(call("http://service.test:${server.port}$path"), anyone) })
            assertTrue(System.nanoTime() - started < 2_500_000_000L, path)
        }
    }

    @Test
    fun `one timeout covers every hop of a redirect chain`() {
        route("/hop1") {
            MockResponse.Builder().code(302).addHeader("Location", "/hop2").headersDelay(700, TimeUnit.MILLISECONDS).build()
        }
        route("/hop2") {
            MockResponse.Builder().code(302).addHeader("Location", "/hop3").headersDelay(700, TimeUnit.MILLISECONDS).build()
        }
        route("/hop3") { reply(200, "end") }
        val quick = fetcher(HttpConfig(allowPrivateAddresses = true, timeoutSeconds = 1))
        assertEquals("it timed out after 1 seconds", failure { quick.fetch(call("http://service.test:${server.port}/hop1"), anyone) })
    }

    @Test
    fun `a refused connection says where`() {
        target = ServerSocket(0).use { it.localPort }
        val message = failure { fetcher().fetch(call("http://service.test:81/"), anyone) }
        assertTrue(message.startsWith("couldn't connect to service.test:81"), message)
    }

    @Test
    fun `proxy settings are never used`() {
        route("/direct") { reply(200, "direct") }
        val before = System.getProperty("http.proxyHost")
        System.setProperty("http.proxyHost", "proxy.invalid")
        System.setProperty("http.proxyPort", "1")
        try {
            // The only connection is to the checked address: a proxy would have been connected to instead (and failed to resolve).
            assertEquals("direct", fetcher().fetch(call("http://service.test:${server.port}/direct"), anyone).body)
            assertEquals(listOf(loopback), connected)
            assertEquals(listOf("service.test"), lookups)
        } finally {
            if (before == null) System.clearProperty("http.proxyHost") else System.setProperty("http.proxyHost", before)
            System.clearProperty("http.proxyPort")
        }
    }

    @Test
    fun `cookies are neither kept nor sent`() {
        route("/set") { reply(200, "", "Set-Cookie" to "session=1") }
        route("/read") { request -> reply(200, request.headers["Cookie"].toString()) }
        val fetcher = fetcher()
        fetcher.fetch(call("http://service.test:${server.port}/set"), anyone)
        assertEquals("null", fetcher.fetch(call("http://service.test:${server.port}/read"), anyone).body)
    }

    @Test
    fun `closing the fetcher closes its connections and ends its threads`() {
        route("/hi") { reply(200, "hi") }
        val fetcher = fetcher()
        fetcher.fetch(call("http://service.test:${server.port}/hi"), anyone)
        assertEquals(1, fetcher.pooledConnections)
        fetcher.close()
        assertEquals(0, fetcher.pooledConnections)
        assertTrue(fetcher.stopped)
    }

    /** A server that answers every connection with [answer] as it is, and closes. Its port. */
    private fun rawServer(answer: String): Int {
        val listener = ServerSocket(0, 10, InetAddress.getLoopbackAddress())
        listeners += listener
        target = listener.localPort
        Thread {
            while (!listener.isClosed) {
                val client = try {
                    listener.accept()
                } catch (e: java.io.IOException) {
                    return@Thread
                }
                client.use {
                    // Read the request head, then answer.
                    val input = it.getInputStream()
                    val tail = StringBuilder()
                    while (!tail.endsWith("\r\n\r\n")) {
                        val byte = input.read()
                        if (byte < 0) break
                        tail.append(byte.toChar())
                    }
                    it.getOutputStream().write(answer.toByteArray())
                }
            }
        }.apply { isDaemon = true }.start()
        return listener.localPort
    }
}
