package dev.netherforge.plugin.pack

import com.sun.net.httpserver.HttpServer
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.GameEvent
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Packs uploaded to an S3-compatible store and fetched from there: once per hash, sent once they're there. */
class PackStorageTest {
    /** A store that keeps what's PUT, answers HEAD for what it has, and records what it was asked. */
    private val objects = ConcurrentHashMap<String, ByteArray>()
    private val asked = CopyOnWriteArrayList<String>()
    private val store = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { exchange ->
            val path = exchange.requestURI.path
            asked += "${exchange.requestMethod} $path ${exchange.requestHeaders.getFirst("Authorization")?.substringBefore(' ')}"
            when (exchange.requestMethod) {
                "HEAD" -> exchange.sendResponseHeaders(if (objects.containsKey(path)) 200 else 404, -1)
                "PUT" -> {
                    objects[path] = exchange.requestBody.readAllBytes()
                    exchange.sendResponseHeaders(200, -1)
                }
                else -> exchange.sendResponseHeaders(405, -1)
            }
            exchange.close()
        }
        start()
    }

    @AfterTest
    fun stop() = store.stop(0)

    private fun config() = PackConfig(
        storage = PackStorageConfig(
            endpoint = "http://127.0.0.1:${store.address.port}",
            bucket = "packs",
            prefix = "net",
            publicUrl = "https://packs.example.com/",
            accessKey = "AKID",
            secretKey = "secret"
        )
    )

    private fun waitFor(server: TestServer, done: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!done()) {
            check(System.nanoTime() < deadline) { "timed out: $asked" }
            server.tick()
            Thread.sleep(5)
        }
    }

    @Test
    fun `a pack is uploaded under its hash, sent from the store once it's there, and never uploaded twice`() {
        TestServer(TestServer.example("showcase"), pack = config()).use { server ->
            val sha1 = server.runtime.packs.built!!.sha1
            assertEquals("https://packs.example.com/net/$sha1.zip", server.runtime.packs.built!!.url)
            val alex = server.player("Alex")
            server.platform.raise.playerJoin(GameEvent.PlayerJoin(alex.ref, false, null))
            waitFor(server) { server.platform.resourcePacks.sent.isNotEmpty() }
            assertEquals("https://packs.example.com/net/$sha1.zip", server.platform.resourcePacks.sent.single().second.url)
            assertEquals(sha1, Packs.sha1(objects.getValue("/packs/net/$sha1.zip")))
            assertTrue(asked.any { it.startsWith("HEAD /packs/net/$sha1.zip AWS4-HMAC-SHA256") }, asked.toString())
        }
        val puts = asked.count { it.startsWith("PUT") }
        // Another server with the same pack: it's in the store, so only asked about.
        TestServer(TestServer.example("showcase"), pack = config()).use { server ->
            val sha1 = server.runtime.packs.built!!.sha1
            waitFor(server) { asked.count { it.startsWith("HEAD /packs/net/$sha1.zip") } >= 2 }
            repeat(5) { server.tick() }
        }
        assertEquals(puts, asked.count { it.startsWith("PUT") }, "not uploaded again")
    }
}
