package dev.netherforge.plugin.control

import dev.netherforge.plugin.TestServer
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The local control endpoint: state, a stream of changes, and draining, saving and pausing writes, shutting down. */
class ControlTest {
    // Unix socket paths are short (about 100 bytes): a folder of its own under the system's temp.
    private val folder = createTempDirectory("nfc")

    /** One request over [address]; the main thread is ticked meanwhile, since actions run there. */
    private fun call(
        server: TestServer,
        address: SocketAddress,
        method: String,
        path: String,
        body: String = "",
        token: String? = null,
        ticking: Boolean = true
    ): Pair<Int, String> {
        val family = if (address is UnixDomainSocketAddress) StandardProtocolFamily.UNIX else StandardProtocolFamily.INET
        val channel = SocketChannel.open(family)
        val answer = CompletableFuture<Pair<Int, String>>()
        // A thread of its own, named so a timed-out request's dump shows where it waited.
        Thread({
            try {
                channel.use {
                    channel.connect(address)
                    val out = Channels.newOutputStream(channel)
                    val auth = token?.let { "Authorization: Bearer $it\r\n" }.orEmpty()
                    out.write(
                        "$method $path HTTP/1.1\r\nHost: x\r\n${auth}Content-Length: ${body.toByteArray().size}\r\n\r\n$body".toByteArray()
                    )
                    out.flush()
                    answer.complete(response(Channels.newInputStream(channel)))
                }
            } catch (e: Throwable) {
                answer.completeExceptionally(e)
            }
        }, "NetherForge test client $method $path").apply {
            isDaemon = true
            start()
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!answer.isDone) {
            if (System.nanoTime() >= deadline) {
                val what = whatWasGoingOn(server)
                channel.close()
                error("$method $path timed out\n$what")
            }
            if (ticking) server.tick()
            Thread.sleep(5)
        }
        return answer.join()
    }

    /**
     * The status and body of one response, read by its Content-Length rather than to the connection's end: on
     * Windows, a Unix socket's close doesn't always reach a blocked read.
     */
    private fun response(input: java.io.InputStream): Pair<Int, String> {
        val head = java.io.ByteArrayOutputStream()
        while (!head.toString(Charsets.UTF_8).endsWith("\r\n\r\n")) {
            val b = input.read()
            check(b >= 0) { "the connection ended before the response's head did: $head" }
            head.write(b)
        }
        val lines = head.toString(Charsets.UTF_8).split("\r\n")
        val status = lines.first().substringAfter(' ').substringBefore(' ').toInt()
        val length = lines.firstOrNull { it.startsWith("content-length:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toInt()
        val body = if (length != null) input.readNBytes(length) else input.readAllBytes()
        return status to body.toString(Charsets.UTF_8)
    }

    /** The server's log and where its threads were: what a timed-out request was waiting for. */
    private fun whatWasGoingOn(server: TestServer): String {
        val threads = Thread.getAllStackTraces().entries
            .filter { (thread, _) -> thread.name.startsWith("NetherForge") || thread == Thread.currentThread() }
            .joinToString("\n") { (thread, stack) ->
                "${thread.name} (${thread.state}):\n" + stack.take(25).joinToString("\n") { "    at $it" }
            }
        return "log:\n${server.platform.log.lines.joinToString("\n")}\nthreads:\n$threads"
    }

    @Test
    fun `on a socket, the state is read, writes are paused for a copy and resumed, and the server drains and shuts down`() {
        val socket = folder.resolve("c.sock")
        TestServer(mapOf(TestServer.MANIFEST to TestServer.manifest()), control = ControlConfig(socket = socket)).use { server ->
            val address = UnixDomainSocketAddress.of(socket)
            repeat(10) { server.tick() }
            val (status, state) = call(server, address, "GET", "/state")
            assertEquals(200, status, state)
            assertTrue(state.contains("\"state\":\"ready\"") && state.contains("{\"name\":\"nether\",\"players\":0"), state)

            val saved = call(server, address, "POST", "/save", """{ "pause": true, "timeout": 60 }""").second
            assertTrue(saved.contains("\"writes_paused\":true"), saved)
            assertTrue("world" in server.platform.worldManager.savedFiles)
            assertEquals(setOf("nether", "world"), server.platform.worldManager.notSaving)
            call(server, address, "POST", "/resume")
            assertEquals(emptySet(), server.platform.worldManager.notSaving)

            call(server, address, "POST", "/drain")
            assertEquals("draining", server.runtime.network.state.word)
            call(server, address, "POST", "/shutdown", """{ "after_empty": true }""")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (server.platform.shutdownReason == null) {
                check(System.nanoTime() < deadline) { "didn't stop" }
                server.tick()
            }
            assertTrue(
                server.platform.log.lines.any { it.contains("Control endpoint (the socket $socket): drain") },
                server.platform.log.lines.toString()
            )
        }
    }

    @Test
    fun `a pause ends by itself at its timeout, and on TCP the token is needed`() {
        val tokenFile = folder.resolve("control.token")
        TestServer(
            mapOf(TestServer.MANIFEST to TestServer.manifest()),
            control = ControlConfig(port = freePort(), tokenFile = tokenFile)
        ).use { server ->
            val port = server.runtime.control.where.substringAfterLast(':').toInt()
            val address = InetSocketAddress("127.0.0.1", port)
            val token = Files.readString(tokenFile).trim()
            repeat(10) { server.tick() }
            assertEquals(401, call(server, address, "GET", "/state").first)
            assertEquals(200, call(server, address, "GET", "/state", token = token).first)
            call(server, address, "POST", "/save", """{ "pause": true, "timeout": 1 }""", token)
            assertEquals(setOf("nether", "world"), server.platform.worldManager.notSaving)
            repeat(30) { server.tick() }
            assertEquals(emptySet(), server.platform.worldManager.notSaving)
        }
    }

    @Test
    fun `the stream says what changes`() {
        val socket = folder.resolve("e.sock")
        TestServer(mapOf(TestServer.MANIFEST to TestServer.manifest()), control = ControlConfig(socket = socket)).use { server ->
            repeat(10) { server.tick() }
            val lines = LinkedBlockingQueue<String>()
            Thread {
                SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                    channel.connect(UnixDomainSocketAddress.of(socket))
                    val out = Channels.newOutputStream(channel)
                    out.write("GET /events HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                    out.flush()
                    BufferedReader(InputStreamReader(Channels.newInputStream(channel))).lineSequence().forEach { lines += it }
                }
            }.apply { isDaemon = true }.start()
            Thread.sleep(200)
            server.platform.players.add("Ann")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (lines.none { it == "event: player_join" }) {
                check(System.nanoTime() < deadline) { "no event: $lines" }
                server.tick()
                Thread.sleep(5)
            }
        }
    }

    @Test
    fun `health comes from ticks, and a server whose ticks stop is stalled though it still answers`() {
        val tokenFile = folder.resolve("health.token")
        TestServer(
            mapOf(TestServer.MANIFEST to TestServer.manifest()),
            control = ControlConfig(port = freePort(), tokenFile = tokenFile, stallSeconds = 1)
        ).use { server ->
            val port = server.runtime.control.where.substringAfterLast(':').toInt()
            val address = InetSocketAddress("127.0.0.1", port)
            val token = Files.readString(tokenFile).trim()
            assertEquals(503, call(server, address, "GET", "/healthz", ticking = false).first, "no tick yet")
            server.platform.performance.lastTickMilliseconds = 7.25
            repeat(10) { server.tick() }
            assertEquals(200 to """{"healthy":true}""", call(server, address, "GET", "/healthz"), "probes need no token")
            val state = call(server, address, "GET", "/state", token = token).second
            assertTrue(Regex("\"last_tick\":\\d{13}").containsMatchIn(state) && state.contains("\"tick_ms\":7.25"), state)
            assertTrue(state.contains("\"stalled\":false"), state)

            val lines = LinkedBlockingQueue<String>()
            Thread {
                SocketChannel.open(StandardProtocolFamily.INET).use { channel ->
                    channel.connect(address)
                    val out = Channels.newOutputStream(channel)
                    out.write("GET /events HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer $token\r\n\r\n".toByteArray())
                    out.flush()
                    BufferedReader(InputStreamReader(Channels.newInputStream(channel))).lineSequence().forEach { lines += it }
                }
            }.apply { isDaemon = true }.start()
            // No more ticks: the main thread is "stuck".
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (lines.none { it == "event: stalled" }) {
                check(System.nanoTime() < deadline) { "no stalled event: $lines" }
                Thread.sleep(20)
            }
            assertEquals(503, call(server, address, "GET", "/healthz", ticking = false).first)
            assertTrue(call(server, address, "GET", "/state", token = token, ticking = false).second.contains("\"stalled\":true"))
            server.tick()
            assertEquals(200, call(server, address, "GET", "/healthz", ticking = false).first, "ticking again, healthy again")
        }
    }

    private fun freePort() = java.net.ServerSocket(0).use { it.localPort }

    @Test
    fun `worlds are imported and unloaded from the endpoint`() {
        val socket = folder.resolve("w.sock")
        val root = folder.resolve("imports")
        Files.createDirectories(root.resolve("islands/a1"))
        Files.writeString(root.resolve("islands/a1/level.dat"), "a level")
        TestServer(
            mapOf(TestServer.MANIFEST to TestServer.manifest()),
            control = ControlConfig(socket = socket),
            worlds = dev.netherforge.plugin.world.WorldsConfig(importRoot = root)
        ).use { server ->
            val address = UnixDomainSocketAddress.of(socket)
            repeat(10) { server.tick() }
            val (status, body) = call(server, address, "POST", "/worlds/load", """{ "name": "island_a1", "folder": "islands/a1" }""")
            assertEquals(200, status, body)
            assertTrue("island_a1" in server.platform.worlds.names())
            assertEquals(409, call(server, address, "POST", "/worlds/load", """{ "name": "x", "folder": "../out" }""").first)
            assertEquals(200, call(server, address, "POST", "/worlds/unload", """{ "name": "island_a1" }""").first)
            assertTrue("island_a1" !in server.platform.worlds.names())
            assertEquals(409, call(server, address, "POST", "/worlds/unload", """{ "name": "world" }""").first)
        }
    }

    private val terrainFiles = mapOf(
        TestServer.MANIFEST to TestServer.manifest(),
        "terrain/hills.json" to """{ "terrain": { "base": 70 }, "layers": [{ "block": "minecraft:dirt", "thickness": 2 }] }""",
        "modules/t/init.lua" to """
            nf.on("world_load", function(event) log("load", event.world:name()) end)
        """.trimIndent()
    )

    @Test
    fun `a world is made from the endpoint with a project terrain, heard, unloaded and loaded again`() {
        val socket = folder.resolve("m.sock")
        TestServer(terrainFiles, control = ControlConfig(socket = socket)).use { server ->
            val address = UnixDomainSocketAddress.of(socket)
            repeat(10) { server.tick() }
            val (status, body) = call(server, address, "POST", "/worlds/create", """{ "name": "island_7f2c", "terrain": "hills", "seed": 1234 }""")
            assertEquals(200, status, body)
            val made = server.platform.worldManager.created.getValue("island_7f2c")
            assertEquals("hills", made.terrain)
            assertEquals(1234L, made.seed)
            assertTrue(server.logs.contains("load\tisland_7f2c"), server.logs.toString())

            val taken = call(server, address, "POST", "/worlds/create", """{ "name": "island_7f2c" }""")
            assertEquals(409, taken.first, taken.second)
            assertTrue(taken.second.contains("already a world named"), taken.second)
            val moon = call(server, address, "POST", "/worlds/create", """{ "name": "moon_1", "terrain": "moon" }""")
            assertEquals(400, moon.first, moon.second)
            assertEquals(400, call(server, address, "POST", "/worlds/create", """{ "name": "x", "generator": "cubes" }""").first)
            assertEquals(400, call(server, address, "POST", "/worlds/create", """{ "terrain": "hills" }""").first)

            assertEquals(200, call(server, address, "POST", "/worlds/unload", """{ "name": "island_7f2c" }""").first)
            assertTrue("island_7f2c" !in server.platform.worlds.names())
            assertEquals(200, call(server, address, "POST", "/worlds/load", """{ "name": "island_7f2c" }""").first)
            assertTrue("island_7f2c" in server.platform.worlds.names())
            assertTrue(server.platform.worldManager.loads.last().endsWith(" hills"), "loaded again with its terrain")
        }
    }

    @Test
    fun `one world is paused and copied while another takes block changes, and each pause ends on its own`() {
        val socket = folder.resolve("p.sock")
        TestServer(terrainFiles, control = ControlConfig(socket = socket)).use { server ->
            val address = UnixDomainSocketAddress.of(socket)
            repeat(10) { server.tick() }
            for (name in listOf("island_a", "island_b")) call(server, address, "POST", "/worlds/create", """{ "name": "$name" }""")
            val manager = server.platform.worldManager

            val (status, saved) = call(server, address, "POST", "/save", """{ "world": "island_a", "pause": true, "timeout": 1 }""")
            assertEquals(200, status, saved)
            assertTrue(saved.contains("\"saved\":[\"island_a\"]"), saved)
            assertEquals(setOf("island_a"), manager.notSaving)
            assertTrue(server.platform.blocks.set("island_b", 0, 64, 0, "minecraft:stone", false), "the other world takes changes")
            repeat(10) { server.tick() }
            val state = call(server, address, "GET", "/state").second
            assertTrue(state.contains("\"paused_worlds\":[\"island_a\"]") && state.contains("\"writes_paused\":true"), state)

            // Two at once, and the whole server's on top: each ends on its own.
            call(server, address, "POST", "/save", """{ "world": "island_b", "pause": true, "timeout": 60 }""")
            call(server, address, "POST", "/save", """{ "pause": true, "timeout": 60 }""")
            repeat(30) { server.tick() }
            assertEquals(setOf("nether", "world", "island_a", "island_b"), manager.notSaving, "island_a's timeout passed, the server's holds it")
            call(server, address, "POST", "/resume")
            assertEquals(setOf("island_b"), manager.notSaving, "island_a's timeout ran out on its own; island_b still holds")
            call(server, address, "POST", "/resume", """{ "world": "island_b" }""")
            assertEquals(emptySet(), manager.notSaving)

            assertEquals(404, call(server, address, "POST", "/save", """{ "world": "nowhere", "pause": true }""").first)
            assertEquals(404, call(server, address, "POST", "/resume", """{ "world": "nowhere" }""").first)

            // Unloading a paused world ends its pause.
            call(server, address, "POST", "/save", """{ "world": "island_a", "pause": true }""")
            assertEquals(200, call(server, address, "POST", "/worlds/unload", """{ "name": "island_a" }""").first)
            repeat(10) { server.tick() }
            assertTrue(call(server, address, "GET", "/state").second.contains("\"paused_worlds\":[]"))
        }
    }

    @Test
    fun `who is in which world is reported, as they move, and a world counts how long it's been empty`() {
        val socket = folder.resolve("u.sock")
        TestServer(terrainFiles, control = ControlConfig(socket = socket)).use { server ->
            val address = UnixDomainSocketAddress.of(socket)
            repeat(10) { server.tick() }
            call(server, address, "POST", "/worlds/create", """{ "name": "island_a" }""")
            val lines = LinkedBlockingQueue<String>()
            Thread {
                SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                    channel.connect(UnixDomainSocketAddress.of(socket))
                    val out = Channels.newOutputStream(channel)
                    out.write("GET /events HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                    out.flush()
                    BufferedReader(InputStreamReader(Channels.newInputStream(channel))).lineSequence().forEach { lines += it }
                }
            }.apply { isDaemon = true }.start()
            Thread.sleep(200)
            fun await(what: (String) -> Boolean): String {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (true) {
                    lines.firstOrNull(what)?.let { return it }
                    check(System.nanoTime() < deadline) { "not heard: $lines" }
                    server.tick()
                    Thread.sleep(5)
                }
            }
            val ann = server.platform.players.add("Ann", dev.netherforge.plugin.platform.Location("island_a", 0.5, 64.0, 0.5))
            val joined = await { it.startsWith("data: ") && it.contains("\"world\":\"island_a\"") }
            assertTrue(joined.contains("\"uuid\":\"${ann.ref.uuid}\""), joined)
            var state = call(server, address, "GET", "/state").second
            assertTrue(state.contains("{\"uuid\":\"${ann.ref.uuid}\",\"name\":\"Ann\",\"world\":\"island_a\"}"), state)
            assertTrue(state.contains("{\"name\":\"island_a\",\"players\":1}"), state)

            server.platform.players.teleport(ann.ref.uuid, dev.netherforge.plugin.platform.Location("world", 0.5, 64.0, 0.5))
            val moved = await { it.contains("\"from\":\"island_a\"") }
            assertTrue(moved.contains("\"to\":\"world\"") && moved.contains("\"uuid\":\"${ann.ref.uuid}\""), moved)
            assertTrue(lines.contains("event: player_world"), lines.toString())
            repeat(10) { server.tick() }
            state = call(server, address, "GET", "/state").second
            assertTrue(state.contains("\"world\":\"world\"}") && state.contains("{\"name\":\"island_a\",\"players\":0,\"empty_seconds\":0"), state)
            repeat(45) { server.tick() }
            state = call(server, address, "GET", "/state").second
            assertTrue(Regex("\\{\"name\":\"island_a\",\"players\":0,\"empty_seconds\":[2-9]").containsMatchIn(state), state)
        }
    }
}
