package dev.netherforge.plugin.network

import dev.netherforge.network.Hub
import dev.netherforge.network.HubLog
import dev.netherforge.network.HubProxy
import dev.netherforge.network.HubServer
import dev.netherforge.network.PlayerState
import dev.netherforge.plugin.TestServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A server that waits for its hub (and the network's policy) before its project runs, or runs nothing without it. */
class WaitForHubTest {
    private object Proxy : HubProxy {
        override fun servers() = listOf("lobby")

        override fun players() = emptyList<PlayerState>()

        override fun send(id: String, server: String) = CompletableFuture.completedFuture<String?>(null)
    }

    private object Quiet : HubLog {
        override fun info(message: String) = Unit

        override fun warn(message: String) = Unit
    }

    private val secret = "a-secret-long-enough-for-the-hub"
    private val project = mapOf(
        TestServer.MANIFEST to TestServer.manifest(requires = """{ "network": true, "moderation": true }"""),
        "modules/t/init.lua" to """log("ran")"""
    )

    @Test
    fun `waiting for the hub, the project starts under the network's policy the first time`() {
        val hub = Hub(secret, Proxy, Quiet, policies = mapOf("trusted" to """{ "requires": { "deny": ["moderation"] } }"""))
        val server = HubServer(hub, InetSocketAddress(InetAddress.getLoopbackAddress(), 0), Quiet).also { it.start() }
        try {
            TestServer(
                project,
                network = NetworkConfig(enabled = true, port = server.port, serverName = "lobby", secret = secret, waitForHubSeconds = 10)
            ).use { lobby ->
                assertFalse("ran" in lobby.logs, "the network's policy refused it before it ever ran")
                assertTrue(lobby.runtime.session.problems().any { it.code == "runtime.policy" })
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `requiring the hub, nothing runs until the hub is there, and then the project starts by itself`() {
        val port = java.net.ServerSocket(0).use { it.localPort }
        TestServer(
            project,
            network = NetworkConfig(enabled = true, port = port, serverName = "lobby", secret = secret, requireHub = true)
        ).use { lobby ->
            repeat(40) { lobby.tick() }
            assertFalse("ran" in lobby.logs)
            assertTrue(lobby.runtime.session.waitsForHub)
            val server = HubServer(Hub(secret, Proxy, Quiet), InetSocketAddress(InetAddress.getLoopbackAddress(), port), Quiet).also {
                it.start()
            }
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                while ("ran" !in lobby.logs) {
                    check(System.nanoTime() < deadline) { "never started: ${lobby.logs}" }
                    lobby.tick()
                    Thread.sleep(10)
                }
                assertFalse(lobby.runtime.session.waitsForHub)
            } finally {
                server.stop()
            }
        }
    }
}
