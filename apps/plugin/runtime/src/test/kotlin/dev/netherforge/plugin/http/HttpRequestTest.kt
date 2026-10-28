package dev.netherforge.plugin.http
import com.sun.net.httpserver.HttpServer
import dev.netherforge.plugin.TestServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `nf.http.request` from a script, against a real server on loopback (the
 * owner's option allows it): the declared host, the `value, err` answers in a
 * task and at a callback, what a script gets wrong on the spot, the owner's
 * limits, and a redirect to a host the package didn't declare. The address
 * guard, redirects and the wire are [dev.netherforge.plugin.http.HttpFetcherTest]'s.
 */
class HttpRequestTest {
    private val web = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        executor = Executors.newCachedThreadPool()
        createContext("/hi") { exchange ->
            val body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            val bytes = "${exchange.requestMethod}:$body:${exchange.requestHeaders.getFirst("X-Token")}".toByteArray()
            exchange.responseHeaders.add("X-Reply", "yes")
            exchange.sendResponseHeaders(201, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.close()
        }
        createContext("/away") { exchange ->
            exchange.responseHeaders.add("Location", "http://127.0.0.1:${address.port}/hi")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        createContext("/missing") { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        start()
    }
    private val port get() = web.address.port

    @AfterTest
    fun stop() {
        web.stop(0)
        (web.executor as java.util.concurrent.ExecutorService).shutdownNow()
    }

    private fun server(script: String, hosts: String = """["localhost"]""", http: HttpConfig = HttpConfig(allowPrivateAddresses = true)) =
        TestServer(
            mapOf(
                TestServer.MANIFEST to TestServer.manifest(requires = """{ "http": $hosts }"""),
                "modules/t/init.lua" to script.replace("PORT", port.toString())
            ),
            http = http
        )

    @Test
    fun `a task sends a request and gets the response, its status, headers, body and final url`() {
        server(
            """
            nf.task(function()
              local r, err = nf.http.request({
                method = "POST", url = "http://localhost:PORT/hi", body = "ping", headers = { ["X-Token"] = "abc" },
              })
              log(r.status .. " " .. r.body .. " " .. r.headers["x-reply"] .. " " .. r.url .. " " .. tostring(err))
              local missing = nf.http.request({ url = "http://localhost:PORT/missing" })
              log("missing " .. missing.status .. " [" .. missing.body .. "]")
            end)
            """
        ).use { server ->
            server.tick(10)
            assertTrue(server.errors.isEmpty(), server.errors.toString())
            assertEquals(
                listOf("201 POST:ping:abc yes http://localhost:$port/hi nil", "missing 404 []"),
                server.logs
            )
        }
    }

    @Test
    fun `a callback gets response, err on the main thread`() {
        server(
            """
            nf.http.request({ url = "http://localhost:PORT/hi" }, function(response, err)
              log("callback " .. response.status .. " " .. tostring(err))
            end)
            nf.http.request({ url = "http://localhost:1/never" }, function(response, err)
              log("failed " .. tostring(response) .. " " .. (err:find("connect") and "connect" or err))
            end)
            """
        ).use { server ->
            server.tick(10)
            assertTrue(server.errors.isEmpty(), server.errors.toString())
            assertEquals(setOf("callback 201 nil", "failed nil connect"), server.logs.toSet())
            assertEquals(2, server.logs.size)
        }
    }

    @Test
    fun `a mistake in the call is an error at the line, a host the package didn't declare included`() {
        server(
            """
            local function try(options)
              local ok, err = pcall(nf.http.request, options, function() end)
              log(tostring(err))
            end
            try({ url = "http://localhost:PORT/hi", callback = 1 })
            try({ url = "http://other.example/" })
            try({ url = "ftp://localhost/file" })
            try({ url = "not a url" })
            try({ url = "http://user:pw@localhost/" })
            try({ url = "http://localhost:PORT/hi", body = "x" })
            try({ url = "http://localhost:PORT/hi", method = "POST", headers = { Host = "evil" } })
            try({ url = "http://localhost:PORT/hi", method = "POST", headers = { A = "line\nbreak" } })
            try({ url = "http://localhost:PORT/hi", method = "POST", headers = { A = "1", a = "2" } })
            try({ url = "http://localhost:PORT/hi", method = "TRACE" })
            try({ method = "GET" })
            """
        ).use { server ->
            server.tick(2)
            assertTrue(server.errors.isEmpty(), server.errors.toString())
            val logs = server.logs
            assertTrue(logs.size == 11, logs.toString())
            assertTrue("hasn't declared" in logs[1] && "http:other.example" in logs[1], logs[1])
            assertTrue("must be an http:// or https:// URL" in logs[2], logs[2])
            assertTrue("isn't a valid URL" in logs[3], logs[3])
            assertTrue("user name or password" in logs[4], logs[4])
            assertTrue("a GET request can't have a body" in logs[5], logs[5])
            assertTrue("can't set \"Host\"" in logs[6], logs[6])
            assertTrue("character a header can't hold" in logs[7], logs[7])
            assertTrue("\"a\" twice" in logs[8] || "twice" in logs[8], logs[8])
            assertTrue("TRACE" in logs[9], logs[9])
            assertTrue("url" in logs[10], logs[10])
        }
    }

    @Test
    fun `a package that declared no host can't call at all`() {
        TestServer(
            mapOf(
                TestServer.MANIFEST to TestServer.manifest(),
                "modules/t/init.lua" to
                    "local ok, err = pcall(nf.http.request, { url = 'http://localhost:$port/hi' }, function() end)\nlog(tostring(err))"
            )
        ).use { server ->
            server.tick(2)
            assertTrue("nf.http.request needs http, which package \"test\" hasn't declared" in server.logs.single(), server.logs.toString())
        }
    }

    @Test
    fun `a request to a loopback address fails as nil, err unless the owner allowed it`() {
        server(
            """
            nf.task(function()
              local r, err = nf.http.request({ url = "http://localhost:PORT/hi" })
              log(tostring(r) .. " " .. err)
            end)
            """,
            http = HttpConfig()
        ).use { server ->
            server.tick(10)
            assertTrue(server.errors.isEmpty(), server.errors.toString())
            val line = server.logs.single()
            assertTrue(line.startsWith("nil localhost leads to "), line)
            assertTrue("a loopback address" in line && "http.allow-private-addresses" in line, line)
        }
    }

    @Test
    fun `a redirect to a host the package didn't declare is nil, err naming the requirement`() {
        server(
            """
            nf.task(function()
              local r, err = nf.http.request({ url = "http://localhost:PORT/away" })
              log(tostring(r) .. " " .. err)
            end)
            """
        ).use { server ->
            server.tick(10)
            assertTrue(server.errors.isEmpty(), server.errors.toString())
            val line = server.logs.single()
            assertTrue(
                line.startsWith(
                    "nil it was redirected to 127.0.0.1, and nf.http.request needs http:127.0.0.1, which package \"test\" hasn't declared"
                ),
                line
            )
        }
        // Declared, the same redirect is followed.
        server(
            """
            nf.task(function()
              local r = nf.http.request({ url = "http://localhost:PORT/away" })
              log(r.status .. " " .. r.url)
            end)
            """,
            hosts = """["localhost", "127.0.0.1"]"""
        ).use { server ->
            server.tick(10)
            assertEquals(listOf("201 http://127.0.0.1:$port/hi"), server.logs)
        }
    }

    @Test
    fun `a body over the owner's limit is an error, a package over its rate gets nil, err`() {
        server(
            """
            local ok, err = pcall(nf.http.request, { url = "http://localhost:PORT/hi", method = "POST", body = "12345678901" }, function() end)
            log(tostring(err))
            nf.task(function()
              for i = 1, 3 do
                local r, why = nf.http.request({ url = "http://localhost:PORT/hi" })
                log(i .. " " .. (r and r.status or why))
              end
            end)
            """,
            http = HttpConfig(allowPrivateAddresses = true, maxRequestBytes = 10, requestsPerMinute = 2)
        ).use { server ->
            server.tick(15)
            assertTrue(server.errors.isEmpty(), server.errors.toString())
            val logs = server.logs
            assertTrue("11 bytes, over the server's limit of 10" in logs[0], logs[0])
            assertEquals(listOf("1 201", "2 201"), logs.subList(1, 3))
            assertTrue(logs[3].startsWith("3 package \"test\" has made 2 requests in the last minute"), logs[3])
        }
    }

    @Test
    fun `the rate is a sliding minute per key`() {
        var now = 0L
        val rate = RequestRate(2) { now }
        assertTrue(rate.tryStart("a"))
        now = 30_000_000_000L
        assertTrue(rate.tryStart("a"))
        assertFalse(rate.tryStart("a"))
        // Another package has its own.
        assertTrue(rate.tryStart("b"))
        // The first one leaves the window at a minute after it; a refused one wasn't counted.
        now = 59_999_999_999L
        assertFalse(rate.tryStart("a"))
        now = 60_000_000_000L
        assertTrue(rate.tryStart("a"))
        assertFalse(rate.tryStart("a"))
        now = 90_000_000_000L
        assertTrue(rate.tryStart("a"))
    }
}
