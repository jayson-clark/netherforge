package dev.netherforge.plugin.http
import dev.netherforge.plugin.TestServer
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockResponseBody
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.BufferedSink
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `nf.http.stream` and `nf.http.websocket` from scripts, over OkHttp to a
 * MockWebServer on loopback (the owner's option allows it): what arrives as
 * events, in order and on the main thread; what scripts send; how each ends;
 * the owner's limits; backpressure; and that a script's connections end with
 * it. The splitting itself is [dev.netherforge.plugin.http.StreamSplitterTest]'s.
 */
class HttpConnectionsTest {
    /** A chunked body the test writes as it goes: each string is a chunk, flushed at once; [END] ends the body. */
    private class Feed : MockResponseBody {
        val pieces = LinkedBlockingQueue<String>()
        override val contentLength = -1L

        override fun writeTo(sink: BufferedSink) {
            while (true) {
                val piece = pieces.poll(10, TimeUnit.SECONDS) ?: END
                if (piece == END) {
                    sink.writeUtf8("0\r\n\r\n").flush()
                    return
                }
                val bytes = piece.toByteArray()
                sink.writeUtf8(Integer.toHexString(bytes.size) + "\r\n").write(bytes).writeUtf8("\r\n").flush()
            }
        }
    }

    private val routes = HashMap<String, () -> MockResponse>()
    private val seen = CopyOnWriteArrayList<RecordedRequest>()
    private val web = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                return routes[request.url.encodedPath]?.invoke() ?: MockResponse.Builder().code(404).build()
            }
        }
        start(InetAddress.getLoopbackAddress(), 0)
    }
    private val port get() = web.port

    @AfterTest
    fun stop() = web.close()

    private fun server(
        script: String,
        hosts: String = """["localhost"]""",
        limits: ConnectionLimits = ConnectionLimits(),
        timeoutSeconds: Int = 15
    ) = TestServer(
        mapOf(
            TestServer.MANIFEST to TestServer.manifest(requires = """{ "http": $hosts }"""),
            "modules/t/init.lua" to script.replace("PORT", port.toString())
        ),
        http = HttpConfig(allowPrivateAddresses = true, timeoutSeconds = timeoutSeconds, connections = limits)
    )

    /** Ticks until [done], as a server would while the network catches up. */
    private fun TestServer.tickUntil(done: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!done()) {
            check(System.nanoTime() < deadline) { "timed out; logs: $logs, errors: $errors" }
            tick()
            Thread.sleep(5)
        }
    }

    private fun stream(feed: Feed, status: Int = 200, contentType: String = "application/x-ndjson") = {
        MockResponse.Builder().code(
            status
        ).addHeader("Content-Type", contentType).addHeader("Transfer-Encoding", "chunked").body(feed).build()
    }

    @Test
    fun `a stream hands over its lines as they come, in order, then ends finished`() {
        val feed = Feed()
        routes["/feed"] = stream(feed)
        server(
            """
            local stream = nf.http.stream({ url = "http://localhost:PORT/feed", headers = { ["X-Token"] = "abc" } })
            log("state " .. stream:state() .. " " .. stream:url())
            stream:on("open", function(event)
              log("open " .. event.status .. " " .. event.headers["content-type"] .. " " .. stream:state() .. " " .. stream:status())
            end)
            stream:on("message", function(event) log("line " .. event.data) end)
            stream:on("end", function(event)
              log("end " .. event.reason .. " " .. tostring(event.status) .. " " .. tostring(event.detail))
            end)
            """
        ).use { server ->
            server.tickUntil { server.logs.any { it.startsWith("open") } }
            feed.pieces += "{\"n\":1}\n{\"n\""
            feed.pieces += ":2}\n"
            server.tickUntil { server.logs.count { it.startsWith("line") } == 2 }
            feed.pieces += "last"
            feed.pieces += END
            server.tickUntil { server.logs.any { it.startsWith("end") } }
            assertTrue(server.errors.isEmpty(), server.errors.toString())
            assertEquals(
                listOf(
                    "state connecting http://localhost:$port/feed",
                    "open 200 application/x-ndjson open 200",
                    "line {\"n\":1}",
                    "line {\"n\":2}",
                    "line last",
                    "end finished 200 nil"
                ),
                server.logs
            )
            assertEquals("abc", seen.single().headers["X-Token"])
            assertEquals(
                0,
                server.runtime.session.scripts.scopes().sumOf(server.runtime.session.connections.costs().getValue("connections"))
            )
        }
    }

    @Test
    fun `a stream of server-sent events asks for them and hands each over with its type and id`() {
        val feed = Feed()
        routes["/events"] = stream(feed, contentType = "text/event-stream")
        server(
            """
            local stream = nf.http.stream({ url = "http://localhost:PORT/events", format = "events" })
            stream:on("message", function(event) log(event.type .. " " .. tostring(event.id) .. " " .. event.data) end)
            """
        ).use { server ->
            feed.pieces += ": hello\n\nevent: goal\nid: 3\ndata: {\"team\":\"red\"}\n\ndata: a\ndata: b\n\n"
            server.tickUntil { server.logs.size == 2 }
            assertEquals(listOf("goal 3 {\"team\":\"red\"}", "message 3 a\nb"), server.logs)
            assertEquals("text/event-stream", seen.single().headers["Accept"])
            feed.pieces += END
        }
    }

    @Test
    fun `a stream follows a redirect to a declared host, and an error status still opens`() {
        routes["/moved"] = { MockResponse.Builder().code(302).addHeader("Location", "http://localhost:$port/missing").build() }
        server(
            """
            local stream = nf.http.stream({ url = "http://localhost:PORT/moved" })
            stream:on("open", function(event) log("open " .. event.status) end)
            stream:on("end", function(event) log("end " .. event.reason .. " " .. event.status) end)
            """
        ).use { server ->
            server.tickUntil { server.logs.any { it.startsWith("end") } }
            assertEquals(listOf("open 404", "end finished 404"), server.logs)
            assertEquals(listOf("/moved", "/missing"), seen.map { it.url.encodedPath })
        }
    }

    @Test
    fun `a stream that can't connect ends failed, saying why`() {
        server(
            """
            local stream = nf.http.stream({ url = "http://localhost:1/never" })
            stream:on("end", function(event) log(event.reason .. " " .. (event.detail:find("connect") and "connect" or event.detail)) end)
            """
        ).use { server ->
            server.tickUntil { server.logs.isNotEmpty() }
            assertEquals(listOf("failed connect"), server.logs)
        }
    }

    @Test
    fun `messages wait for the main thread a few a tick, and the reader waits for room, so none is lost`() {
        val feed = Feed()
        routes["/fast"] = stream(feed)
        server(
            """
            local stream = nf.http.stream({ url = "http://localhost:PORT/fast" })
            nf.on("tick", function() log("tick") end)
            stream:on("message", function(event) log(event.data) end)
            """,
            limits = ConnectionLimits(messagesPerTick = 2, maxQueuedMessages = 3)
        ).use { server ->
            feed.pieces += (1..20).joinToString("") { "$it\n" }
            feed.pieces += END
            server.tickUntil { "20" in server.logs }
            val lines = server.logs.filter { it != "tick" }
            assertEquals((1..20).map { "$it" }, lines)
            // Never more than two between ticks.
            val runs = server.logs.joinToString(",").split("tick").map { run -> run.split(",").count { it.isNotEmpty() } }
            assertTrue(runs.all { it <= 2 }, server.logs.toString())
        }
    }

    @Test
    fun `a WebSocket sends what was sent before it opened, hears each message, and ends as the far end closes it`() {
        val heard = LinkedBlockingQueue<String>()
        routes["/socket"] = {
            MockResponse.Builder().addHeader("Sec-WebSocket-Protocol", "chat.v2").webSocketUpgrade(
                object : Polite() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        heard += text
                        webSocket.send("echo $text")
                        if (text == "bye") webSocket.close(4000, "see you")
                    }
                }
            ).build()
        }
        server(
            """
            local socket = nf.http.websocket({ url = "ws://localhost:PORT/socket", protocols = { "chat.v2", "chat.v1" } })
            assert(socket:send("early"))
            socket:on("open", function(event) log("open " .. tostring(event.protocol) .. " " .. socket:state() .. " " .. socket:protocol()) end)
            socket:on("message", function(event)
              log(event.data)
              if event.data == "echo early" then socket:send("bye") end
            end)
            socket:on("end", function(event)
              log("end " .. event.reason .. " " .. tostring(event.code) .. " " .. tostring(event.detail))
              nf.after(1, function() log("after " .. socket:state() .. " " .. tostring(socket:send("x")) .. " " .. tostring(socket:close())) end)
            end)
            """
        ).use { server ->
            server.tickUntil { server.logs.any { it.startsWith("after") } }
            assertTrue(server.errors.isEmpty(), server.errors.toString())
            assertEquals(
                listOf("open chat.v2 open chat.v2", "echo early", "echo bye", "end finished 4000 see you", "after ended false false"),
                server.logs
            )
            assertEquals(listOf("early", "bye"), listOf(heard.poll(1, TimeUnit.SECONDS), heard.poll(1, TimeUnit.SECONDS)))
        }
    }

    @Test
    fun `a script closing a WebSocket tells the far end, and its end is heard at once`() {
        val closed = LinkedBlockingQueue<String>()
        routes["/socket"] = {
            MockResponse.Builder().webSocketUpgrade(
                object : Polite() {
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        closed += "$code $reason"
                        super.onClosing(webSocket, code, reason)
                    }
                }
            ).build()
        }
        server(
            """
            local socket = nf.http.websocket({ url = "ws://localhost:PORT/socket" })
            socket:on("open", function()
              log("closing " .. tostring(socket:close(4001, "done")))
              log("again " .. tostring(socket:close()))
            end)
            socket:on("end", function(event) log("end " .. event.reason) end)
            """
        ).use { server ->
            server.tickUntil { server.logs.size == 3 }
            // Its end is heard inside close(), as an effect's is inside stop().
            assertEquals(listOf("end closed", "closing true", "again false"), server.logs)
            assertEquals("4001 done", closed.poll(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `a WebSocket a server won't open ends failed, saying what it answered`() {
        server(
            """
            local socket = nf.http.websocket({ url = "ws://localhost:PORT/nothing" })
            socket:on("end", function(event) log(event.reason .. ": " .. event.detail) end)
            """
        ).use { server ->
            server.tickUntil { server.logs.isNotEmpty() }
            assertEquals(listOf("failed: localhost answered 404 instead of opening a WebSocket"), server.logs)
        }
    }

    @Test
    fun `a mistake in the call is an error at the line`() {
        server(
            """
            local function try(open, options)
              local ok, err = pcall(open, options)
              log(tostring(err))
            end
            try(nf.http.stream, { url = "http://other.example/" })
            try(nf.http.stream, { url = "ws://localhost/" })
            try(nf.http.stream, { url = "http://localhost/", body = "x" })
            try(nf.http.stream, { url = "http://localhost/", format = "xml" })
            try(nf.http.websocket, { url = "http://localhost/" })
            try(nf.http.websocket, { url = "wss://other.example/" })
            try(nf.http.websocket, { url = "ws://localhost/", headers = { ["Sec-WebSocket-Key"] = "x" } })
            try(nf.http.websocket, { url = "ws://localhost/", protocols = { "a b" } })
            local socket = nf.http.websocket({ url = "ws://localhost:PORT/" })
            log(tostring(select(2, pcall(socket.close, socket, 1006))))
            log(tostring(select(2, pcall(socket.close, socket, 1000, string.rep("x", 124)))))
            """
        ).use { server ->
            server.tick(2)
            assertTrue(server.errors.isEmpty(), server.errors.toString())
            val logs = server.logs
            assertEquals(10, logs.size, logs.toString())
            assertTrue("http:other.example" in logs[0], logs[0])
            assertTrue("must be an http:// or https:// URL" in logs[1], logs[1])
            assertTrue("can't have a body" in logs[2], logs[2])
            assertTrue("format" in logs[3], logs[3])
            assertTrue("must be an ws:// or wss:// URL" in logs[4], logs[4])
            assertTrue("http:other.example" in logs[5], logs[5])
            assertTrue("Sec-WebSocket-Key" in logs[6], logs[6])
            assertTrue("isn't a protocol name" in logs[7], logs[7])
            assertTrue("1000, or 3000 to 4999" in logs[8], logs[8])
            assertTrue("at most 123 bytes" in logs[9], logs[9])
        }
    }

    @Test
    fun `past the owner's limits a connection ends limit at the next tick, and a message too large is an error`() {
        routes["/socket"] = { MockResponse.Builder().webSocketUpgrade(Polite()).build() }
        server(
            """
            local first = nf.http.websocket({ url = "ws://localhost:PORT/socket" })
            local second = nf.http.websocket({ url = "ws://localhost:PORT/socket" })
            second:on("end", function(event) log("second " .. event.reason .. " " .. event.detail) end)
            log(tostring(select(2, pcall(first.send, first, "12345678901"))))
            """,
            limits = ConnectionLimits(maxPerPackage = 1, maxMessageBytes = 10)
        ).use { server ->
            server.tickUntil { server.logs.size == 2 }
            assertTrue("over the server's limit of 10" in server.logs[0], server.logs[0])
            assertTrue(server.logs[1].startsWith("second limit") && "max-per-package" in server.logs[1], server.logs[1])
        }
    }

    @Test
    fun `a message larger than the limit from the far end ends the connection limit`() {
        val feed = Feed()
        routes["/feed"] = stream(feed)
        server(
            """
            local stream = nf.http.stream({ url = "http://localhost:PORT/feed" })
            stream:on("message", function(event) log("line " .. event.data) end)
            stream:on("end", function(event) log("end " .. event.reason .. " " .. event.detail) end)
            """,
            limits = ConnectionLimits(maxMessageBytes = 8)
        ).use { server ->
            feed.pieces += "short\n"
            feed.pieces += "much too long a line\n"
            server.tickUntil { server.logs.any { it.startsWith("end") } }
            assertEquals("line short", server.logs[0])
            assertTrue(server.logs[1].startsWith("end limit") && "max-message-bytes" in server.logs[1], server.logs[1])
            feed.pieces += END
        }
    }

    @Test
    fun `a package past its bytes a minute has its connection ended limit`() {
        val feed = Feed()
        routes["/feed"] = stream(feed)
        server(
            """
            local stream = nf.http.stream({ url = "http://localhost:PORT/feed" })
            stream:on("end", function(event) log("end " .. event.reason .. " " .. event.detail) end)
            """,
            limits = ConnectionLimits(bytesPerMinute = 2048)
        ).use { server ->
            feed.pieces += "x".repeat(4096) + "\n"
            server.tickUntil { server.logs.isNotEmpty() }
            assertTrue(server.logs[0].startsWith("end limit") && "bytes-per-minute" in server.logs[0], server.logs[0])
            feed.pieces += END
        }
    }

    @Test
    fun `a connection with nothing moving ends idle, and one never answered ends failed`() {
        val feed = Feed()
        routes["/quiet"] = stream(feed)
        routes["/never"] = { MockResponse.Builder().headersDelay(30, TimeUnit.SECONDS).build() }
        server(
            """
            local quiet = nf.http.stream({ url = "http://localhost:PORT/quiet" })
            quiet:on("end", function(event) log("quiet " .. event.reason) end)
            local never = nf.http.stream({ url = "http://localhost:PORT/never" })
            never:on("end", function(event) log("never " .. event.reason .. " " .. event.detail) end)
            """,
            limits = ConnectionLimits(idleSeconds = 1),
            timeoutSeconds = 1
        ).use { server ->
            server.tickUntil { server.logs.size == 2 }
            assertTrue("quiet idle" in server.logs, server.logs.toString())
            assertTrue(server.logs.any { it.startsWith("never failed") && "timeout-seconds" in it }, server.logs.toString())
            feed.pieces += END
        }
    }

    @Test
    fun `a script's connections end with it, a reload drops them, the far end hears it, and nothing is left open`() {
        val closed = LinkedBlockingQueue<Int>()
        routes["/socket"] = {
            MockResponse.Builder().webSocketUpgrade(
                object : Polite() {
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        closed += code
                        super.onClosing(webSocket, code, reason)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        closed += -1
                    }
                }
            ).build()
        }
        server(
            """
            local socket = nf.http.websocket({ url = "ws://localhost:PORT/socket" })
            socket:on("open", function() log("open") end)
            """
        ).use { server ->
            server.tickUntil { "open" in server.logs }
            val connections = server.runtime.session.connections
            assertEquals(
                1,
                connections.costs().getValue("connections").let { count ->
                    server.runtime.session.scripts.scopes().sumOf(count)
                }
            )
            server.write("modules/t/init.lua", "log(\"reloaded\")")
            server.reload("modules/t/init.lua")
            server.tick()
            assertEquals(0, server.runtime.session.scripts.scopes().sumOf(connections.costs().getValue("connections")))
            assertEquals(1001, closed.poll(5, TimeUnit.SECONDS))
        }
    }

    /** A far end that answers a close as servers do, so neither side waits out OkHttp's close timeout. */
    private open class Polite : WebSocketListener() {
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
        }
    }

    private companion object {
        const val END = "\u0000end"
    }
}
