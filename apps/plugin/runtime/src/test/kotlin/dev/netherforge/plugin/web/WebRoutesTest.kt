package dev.netherforge.plugin.web
import dev.netherforge.plugin.TestServer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `nf.web` from scripts, through the plugin's own listener over real HTTP on
 * loopback: what a handler is handed and answers, now or later; the router's
 * own answers; the owner's limits; a script's routes going with it; and the
 * mistakes that are errors at the script's line. Paths and queries are
 * [dev.netherforge.plugin.web.RoutePathTest]'s.
 */
class WebRoutesTest {
    private val client = HttpClient.newHttpClient()

    private fun server(script: String, web: WebConfig = WebConfig(enabled = true), requires: String = """{ "web": true }""") = TestServer(
        mapOf(
            TestServer.MANIFEST to TestServer.manifest(requires = requires),
            "modules/t/init.lua" to script
        ),
        web = web
    )

    /** Sends a request to the server's listener, ticking the server while it waits, as a client waits on a running server. */
    private fun TestServer.fetch(
        method: String,
        path: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap()
    ): HttpResponse<String> {
        val base = checkNotNull(runtime.webBase()) { "web routes are off" }
        val request = HttpRequest.newBuilder(URI("$base$path"))
            .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        val answer = client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!answer.isDone) {
            check(System.nanoTime() < deadline) { "no answer to $method $path; errors: $errors" }
            tick()
            Thread.sleep(2)
        }
        return answer.get()
    }

    @Test
    fun `a route is handed its params, query, headers, body and form, and answers through the listener`() {
        server(
            """
            nf.web.route("GET", "/hello/:name", function(request, response)
              response:json(200, { name = request.params.name, greet = request.query.greet, agent = request.headers["x-agent"], method = request.method })
            end)
            nf.web.route("POST", "/form", function(request, response)
              response:send(201, request.form.a .. "|" .. request.body, { ["X-Seen"] = "yes" })
            end)
            """
        ).use { server ->
            val hello = server.fetch("GET", "/test/hello/Al%20ex?greet=hi", headers = mapOf("X-Agent" to "tester"))
            assertEquals(200, hello.statusCode(), hello.body())
            assertEquals("""{"agent":"tester","greet":"hi","method":"GET","name":"Al ex"}""", hello.body())
            assertEquals("application/json", hello.headers().firstValue("content-type").get())
            assertEquals("nosniff", hello.headers().firstValue("x-content-type-options").get())
            assertEquals("no-store", hello.headers().firstValue("cache-control").get())

            val form = server.fetch("POST", "/test/form", "a=x+y&b=2", mapOf("Content-Type" to "application/x-www-form-urlencoded"))
            assertEquals(201, form.statusCode())
            assertEquals("x y|a=x+y&b=2", form.body())
            assertEquals("yes", form.headers().firstValue("x-seen").get())

            val head = server.fetch("HEAD", "/test/hello/x")
            assertEquals(200, head.statusCode())
            assertEquals("", head.body())
            assertTrue(server.errors.isEmpty(), server.errors.toString())
        }
    }

    @Test
    fun `the router answers what no route does, and the most literal route wins`() {
        server(
            """
            nf.web.route("GET", "/items/:id", function(request, response) response:send(200, "item " .. request.params.id) end)
            nf.web.route("GET", "/items/new", function(request, response) response:send(200, "the form") end)
            nf.web.route("GET", "/files/*rest", function(request, response) response:send(200, request.params.rest) end)
            nf.web.route("GET", "/", function(request, response) response:send(200, "home") end)
            """
        ).use { server ->
            assertEquals("item 7", server.fetch("GET", "/test/items/7").body())
            assertEquals("the form", server.fetch("GET", "/test/items/new").body())
            assertEquals("a/b.txt", server.fetch("GET", "/test/files/a/b.txt").body())
            assertEquals("home", server.fetch("GET", "/test").body())
            assertEquals(404, server.fetch("GET", "/test/nothing").statusCode())
            assertEquals(404, server.fetch("GET", "/other/items/7").statusCode())
            val wrong = server.fetch("DELETE", "/test/items/7")
            assertEquals(405, wrong.statusCode())
            assertEquals("GET, HEAD", wrong.headers().firstValue("allow").get())
        }
    }

    @Test
    fun `a handler may answer later, and one that never does is answered 504`() {
        server(
            """
            nf.web.route("GET", "/later", function(request, response)
              log("pending " .. tostring(response:is_pending()))
              nf.after(5, function() log("answered " .. tostring(response:send(200, "done")) .. " " .. tostring(response:send(200, "again"))) end)
            end)
            nf.web.route("GET", "/never", function(request, response) end)
            """,
            web = WebConfig(enabled = true, handlerTimeoutSeconds = 1)
        ).use { server ->
            assertEquals("done", server.fetch("GET", "/test/later").body())
            assertEquals(504, server.fetch("GET", "/test/never").statusCode())
            server.tick()
            assertEquals(listOf("pending true", "answered true false"), server.logs)
        }
    }

    @Test
    fun `a handler that fails is answered 500 and reported at its line`() {
        server(
            """
            nf.web.route("GET", "/boom", function(request, response)
              error("kaboom")
            end)
            """
        ).use { server ->
            assertEquals(500, server.fetch("GET", "/test/boom").statusCode())
            assertTrue(server.errors.any { "kaboom" in it.message }, server.errors.toString())
        }
    }

    @Test
    fun `the owner's limits refuse before any script runs`() {
        server(
            """nf.web.route("POST", "/in", function(request, response) log("ran") response:send(204) end)""",
            web = WebConfig(enabled = true, maxBodyBytes = 8, requestsPerMinutePerIp = 3)
        ).use { server ->
            assertEquals(413, server.fetch("POST", "/test/in", "123456789").statusCode())
            assertEquals(204, server.fetch("POST", "/test/in", "1234").statusCode())
            assertEquals(204, server.fetch("POST", "/test/in", "").statusCode())
            val limited = server.fetch("POST", "/test/in", "")
            assertEquals(429, limited.statusCode())
            assertEquals("60", limited.headers().firstValue("retry-after").get())
            assertEquals(listOf("ran", "ran"), server.logs)
        }
    }

    @Test
    fun `a script's routes go with it, and what waited on them is answered 503`() {
        server("""nf.web.route("GET", "/wait", function(request, response) end)""").use { server ->
            val base = server.runtime.webBase()
            val waiting = client.sendAsync(HttpRequest.newBuilder(URI("$base/test/wait")).build(), HttpResponse.BodyHandlers.ofString())
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (server.runtime.session.web.pending(1) == null) {
                check(System.nanoTime() < deadline) { "the request never reached the handler" }
                server.tick()
                Thread.sleep(2)
            }
            server.write("modules/t/init.lua", "log(\"reloaded\")")
            server.reload("modules/t/init.lua")
            assertEquals(503, waiting.get(5, TimeUnit.SECONDS).statusCode())
            assertEquals(404, server.fetch("GET", "/test/wait").statusCode())
        }
    }

    @Test
    fun `a package's address is the public url and its namespace, and with web routes off there is none and a warning`() {
        server(
            """log(nf.web.url("/r/12", { b = "x y", a = "1" }))""",
            web = WebConfig(enabled = true, publicUrl = "https://play.example.com")
        ).use { server ->
            assertEquals(listOf("https://play.example.com/test/r/12?a=1&b=x%20y"), server.logs)
            assertTrue(server.runtime.session.problems().none { it.code == "runtime.web-disabled" })
        }
        server("""log(tostring(nf.web.url()))""", web = WebConfig()).use { server ->
            assertEquals(listOf("nil"), server.logs)
            val warning = server.runtime.session.problems().single { it.code == "runtime.web-disabled" }
            assertEquals("$.requires.web", warning.path)
        }
    }

    @Test
    fun `mistakes are errors at the line, a missing requirement included`() {
        server(
            """
            local function try(f, ...)
              local ok, err = pcall(f, ...)
              log(tostring(err))
            end
            local noop = function() end
            try(nf.web.route, "GET", "no-slash", noop)
            try(nf.web.route, "GET", "/a//b", noop)
            nf.web.route("GET", "/taken/:x", noop)
            try(nf.web.route, "GET", "/taken/:y", noop)
            try(nf.web.route, "TRACE", "/x", noop)
            nf.web.route("GET", "/check", function(request, response)
              try(response.send, response, 99)
              try(response.send, response, 200, "", { ["Content-Length"] = "3" })
              try(response.redirect, response, "javascript:alert(1)")
              try(response.redirect, response, "/elsewhere", 200)
              log(tostring(response:redirect("/elsewhere")))
              log(tostring(response:send(200)))
            end)
            """
        ).use { server ->
            val answer = server.fetch("GET", "/test/check")
            assertEquals(302, answer.statusCode())
            assertEquals("/elsewhere", answer.headers().firstValue("location").get())
            val logs = server.logs
            assertEquals(10, logs.size, logs.toString())
            assertTrue("a path starts with /" in logs[0], logs[0])
            assertTrue("empty segment" in logs[1], logs[1])
            assertTrue("already answers GET /taken/:x" in logs[2], logs[2])
            assertTrue("TRACE" in logs[3], logs[3])
            assertTrue("100 to 599" in logs[4], logs[4])
            assertTrue("set by the server" in logs[5], logs[5])
            assertTrue("bad argument 'url'" in logs[6], logs[6])
            assertTrue("301, 302" in logs[7], logs[7])
            assertEquals(listOf("true", "false"), logs.subList(8, 10))
        }
        server("""log(select(2, pcall(nf.web.route, "GET", "/x", function() end)))""", requires = """{ "db": true }""").use { server ->
            assertTrue("\"requires\": { \"web\": true }" in server.logs.single(), server.logs.toString())
        }
    }

    @Test
    fun `a webhook's signature is checked in constant time, and a wrong or unreadable one is false`() {
        // GitHub's own example: https://docs.github.com/en/webhooks/using-webhooks/validating-webhook-deliveries
        server(
            """
            local options = { secret = "It's a Secret to Everybody", prefix = "sha256=" }
            local good = "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17"
            log(tostring(nf.web.verify_signature("Hello, World!", good, options)))
            log(tostring(nf.web.verify_signature("Hello, World?", good, options)))
            log(tostring(nf.web.verify_signature("Hello, World!", "757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17", options)))
            log(tostring(nf.web.verify_signature("Hello, World!", "sha256=zz", options)))
            log(tostring(nf.web.verify_signature("Hello, World!", "dXEH6g6yUJ/CESIczphLijdXC211hsIsRvQ3nIsEPhc=", { secret = options.secret, encoding = "base64" })))
            """,
            requires = "{}"
        ).use { server ->
            assertEquals(listOf("true", "false", "false", "false", "true"), server.logs)
        }
    }

    @Test
    fun `with web routes off nothing listens for them`() {
        server("""nf.web.route("GET", "/x", function(request, response) response:send(200) end)""", web = WebConfig()).use { server ->
            assertNull(server.runtime.webBase())
            assertEquals(1, server.runtime.session.web.all().size, "kept, for tests and for when they're turned on")
        }
    }
}
