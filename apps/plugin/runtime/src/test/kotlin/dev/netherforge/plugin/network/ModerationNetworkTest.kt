package dev.netherforge.plugin.network

import dev.netherforge.network.Hub
import dev.netherforge.network.HubLog
import dev.netherforge.network.HubProxy
import dev.netherforge.network.HubServer
import dev.netherforge.network.PlayerState
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.DEFAULT_CHAT_FORMAT
import dev.netherforge.plugin.platform.GameEvent
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Scripts moderating the network: bans and mutes through the hub, and a muted player's chat held back here. */
class ModerationNetworkTest {
    private class Proxy : HubProxy {
        val kicked = CopyOnWriteArrayList<String>()

        override fun servers() = listOf("lobby")

        override fun players() = emptyList<PlayerState>()

        override fun send(id: String, server: String) = CompletableFuture.completedFuture<String?>(null)

        override fun kick(id: String, message: String): String? {
            kicked += id
            return null
        }
    }

    private object Quiet : HubLog {
        override fun info(message: String) = Unit

        override fun warn(message: String) = Unit
    }

    @Test
    fun `a script bans and mutes across the network, and a muted player's chat goes nowhere`() {
        val secret = "a-secret-long-enough-for-the-hub"
        val proxy = Proxy()
        val hub = Hub(secret, proxy, Quiet)
        val hubServer = HubServer(hub, InetSocketAddress(InetAddress.getLoopbackAddress(), 0), Quiet).also { it.start() }
        try {
            TestServer(
                mapOf(
                    TestServer.MANIFEST to TestServer.manifest(requires = """{ "network": true, "moderation": true }"""),
                    "modules/t/init.lua" to """
                        nf.commands.register("go", function()
                          nf.task(function()
                            log("ban " .. tostring(nf.network.ban("Ann", { reason = "griefing" })))
                            log("mute " .. tostring(nf.network.mute("Bea", { reason = "spam" })))
                            log("report " .. tostring(nf.network.report("Bea", "rude", { said = "x" })))
                            local _, err = nf.network.unban("Cid")
                            log("unban " .. tostring(err))
                          end)
                        end)
                        nf.commands.register("check", function()
                          log("muted " .. tostring(nf.network.is_muted("Bea")) .. " " .. tostring(nf.network.is_banned("Ann")))
                        end)
                        nf.on("player_chat", function(event) log("heard " .. event.message) end)
                    """
                ),
                network = NetworkConfig(enabled = true, port = hubServer.port, serverName = "lobby", secret = secret)
            ).use { lobby ->
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                fun tickUntil(done: () -> Boolean) {
                    while (!done()) {
                        check(System.nanoTime() < deadline) { "timed out: ${lobby.logs} ${lobby.errors}" }
                        lobby.tick()
                        Thread.sleep(5)
                    }
                }
                tickUntil { lobby.runtime.network.connected }
                val ann = UUID.nameUUIDFromBytes("Ann".toByteArray()).toString()
                val bea = UUID.nameUUIDFromBytes("Bea".toByteArray())
                val cid = UUID.nameUUIDFromBytes("Cid".toByteArray()).toString()
                for ((id, name) in listOf(ann to "Ann", bea.toString() to "Bea", cid to "Cid")) hub.playerMoved(id, name, "lobby")
                tickUntil { lobby.runtime.network.players.size == 3 }
                lobby.platform.commands.runConsole("go")
                tickUntil { lobby.logs.any { it.startsWith("unban") } }
                assertEquals(listOf("ban true", "mute true", "report true", "unban Cid wasn't banned"), lobby.logs)
                assertEquals(setOf(ann), hub.bans().keys)
                assertEquals(listOf(ann), proxy.kicked)
                assertEquals("rude", hub.reports().single().reason)

                tickUntil { lobby.runtime.session.network.muted(bea) != null }
                lobby.platform.commands.runConsole("check")
                assertEquals("muted true true", lobby.logs.last())
                val player = lobby.platform.players.add("Bea", uuid = bea)
                lobby.platform.raise.playerJoin(GameEvent.PlayerJoin(player.ref, true, null))
                assertTrue(lobby.platform.raise.playerChat(GameEvent.PlayerChat(player.ref, "buy gold", DEFAULT_CHAT_FORMAT)))
                assertFalse(lobby.logs.any { it.startsWith("heard") }, "scripts never hear it")
                assertTrue(player.messages.last().contains("muted on this network: spam"), player.messages.toString())
            }
        } finally {
            hubServer.stop()
        }
    }
}
