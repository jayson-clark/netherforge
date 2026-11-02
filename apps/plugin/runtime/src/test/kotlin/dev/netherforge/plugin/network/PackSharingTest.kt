package dev.netherforge.plugin.network

import dev.netherforge.network.Hub
import dev.netherforge.network.HubFrame
import dev.netherforge.network.HubListener
import dev.netherforge.network.HubLog
import dev.netherforge.network.HubProxy
import dev.netherforge.network.HubServer
import dev.netherforge.network.LocalLink
import dev.netherforge.network.PlayerState
import dev.netherforge.network.ServerFrame
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.GameEvent
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A pack a player's game loaded on one server isn't sent again by the next, when the proxy keeps packs. */
class PackSharingTest {
    private object Proxy : HubProxy {
        override fun servers() = listOf("lobby", "survival")

        override fun players() = emptyList<PlayerState>()

        override fun send(id: String, server: String) = CompletableFuture.completedFuture<String?>(null)
    }

    private object Quiet : HubLog {
        override fun info(message: String) = Unit

        override fun warn(message: String) = Unit
    }

    private object Nobody : HubListener {
        override fun connected(welcome: HubFrame.Welcome) = Unit

        override fun received(frame: HubFrame) = Unit

        override fun disconnected(why: String) = Unit

        override fun failed(why: String) = Unit
    }

    private fun run(keepsPacks: Boolean): Int {
        val secret = "a-secret-long-enough-for-the-hub"
        val hub = Hub(secret, Proxy, Quiet, keepsPacks = keepsPacks)
        val hubServer = HubServer(hub, InetSocketAddress(InetAddress.getLoopbackAddress(), 0), Quiet).also { it.start() }
        try {
            TestServer(
                TestServer.example("showcase"),
                network = NetworkConfig(enabled = true, port = hubServer.port, serverName = "lobby", secret = secret)
            ).use { lobby ->
                val survival = LocalLink(hub, "survival", secret, Nobody).also { it.start() }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                fun tickUntil(done: () -> Boolean) {
                    while (!done()) {
                        check(System.nanoTime() < deadline) { "timed out" }
                        lobby.tick()
                        Thread.sleep(5)
                    }
                }
                tickUntil { lobby.runtime.network.connected }
                // Ann loaded the same pack on survival, then the proxy moves her to the lobby.
                val ann = UUID.nameUUIDFromBytes("Ann".toByteArray())
                hub.playerMoved(ann.toString(), "Ann", "survival")
                survival.send(ServerFrame.PackLoaded(ann.toString(), lobby.runtime.packs.built!!.sha1))
                tickUntil { lobby.runtime.network.players[ann.toString()]?.pack != null }
                hub.playerMoved(ann.toString(), "Ann", "lobby")
                val joined = lobby.platform.players.add("Ann", uuid = ann)
                lobby.platform.raise.playerJoin(GameEvent.PlayerJoin(joined.ref, false, null))
                repeat(5) { lobby.tick() }
                return lobby.platform.resourcePacks.sent.size
            }
        } finally {
            hubServer.stop()
        }
    }

    @Test
    fun `a pack loaded on the server a player came from isn't sent again when the proxy keeps packs`() {
        assertEquals(0, run(keepsPacks = true), "loaded already, kept by the proxy")
        assertEquals(1, run(keepsPacks = false), "the proxy took it off: sent again")
    }

    @Test
    fun `the hub tells servers what a player loaded`() {
        val hub = Hub("s3cret-s3cret-s3cret", Proxy, Quiet, keepsPacks = true)
        val heard = ArrayList<HubFrame>()
        var welcome: HubFrame.Welcome? = null
        val listener = object : HubListener {
            override fun connected(welcome: HubFrame.Welcome) = run { welcomed(welcome) }

            override fun received(frame: HubFrame) {
                heard += frame
            }

            override fun disconnected(why: String) = Unit

            override fun failed(why: String) = Unit

            private fun welcomed(frame: HubFrame.Welcome) {
                welcome = frame
            }
        }
        LocalLink(hub, "lobby", "s3cret-s3cret-s3cret", listener).start()
        val survival = LocalLink(hub, "survival", "s3cret-s3cret-s3cret", Nobody).also { it.start() }
        assertTrue(welcome!!.keepsPacks)
        hub.playerMoved("u-1", "Ann", "survival")
        survival.send(ServerFrame.PackLoaded("u-1", "a".repeat(40)))
        assertEquals(HubFrame.PlayerPack("u-1", "a".repeat(40)), heard.filterIsInstance<HubFrame.PlayerPack>().single())
        hub.playerMoved("u-1", "Ann", "lobby")
        assertEquals("a".repeat(40), hub.adminPlayers().single().pack, "it stays with them as they move")
    }
}
