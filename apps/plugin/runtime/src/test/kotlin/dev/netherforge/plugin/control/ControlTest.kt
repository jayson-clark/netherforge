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
        val answer = CompletableFuture.supplyAsync {
            val family = if (address is UnixDomainSocketAddress) StandardProtocolFamily.UNIX else StandardProtocolFamily.INET
            SocketChannel.open(family).use { channel ->
                channel.connect(address)
                val out = Channels.newOutputStream(channel)
                val auth = token?.let { "Authorization: Bearer $it\r\n" }.orEmpty()
                out.write(
                    "$method $path HTTP/1.1\r\nHost: x\r\n${auth}Content-Length: ${body.toByteArray().size}\r\n\r\n$body".toByteArray()
                )
                out.flush()
                val text = Channels.newInputStream(channel).readAllBytes().toString(Charsets.UTF_8)
                text.substringAfter(' ').substringBefore(' ').toInt() to text.substringAfter("\r\n\r\n")
            }
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!answer.isDone) {
            check(System.nanoTime() < deadline) { "timed out" }
            if (ticking) server.tick()
            Thread.sleep(5)
        }
        return answer.join()
    }

    @Test
    fun `on a socket, the state is read, writes are paused for a copy and resumed, and the server drains and shuts down`() {
        val socket = folder.resolve("c.sock")
        TestServer(mapOf(TestServer.MANIFEST to TestServer.manifest()), control = ControlConfig(socket = socket)).use { server ->
            val address = UnixDomainSocketAddress.of(socket)
            repeat(10) { server.tick() }
            val (status, state) = call(server, address, "GET", "/state")
            assertEquals(200, status, state)
            assertTrue(state.contains("\"state\":\"ready\"") && state.contains("\"worlds\":[\"nether\",\"world\"]"), state)

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
}
