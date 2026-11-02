package dev.netherforge.network

import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A hub running on its own, with its proxy connected to it as a client. */
class RemoteProxyTest {
    private object Quiet : HubLog {
        override fun info(message: String) = Unit

        override fun warn(message: String) = Unit
    }

    /** The proxy's end: does what the hub asks, as the proxy plugin does, and records it. */
    private inner class FakeProxy : HubListener {
        val frames = java.util.concurrent.CopyOnWriteArrayList<HubFrame>()
        val servers = mutableListOf("lobby", "arena-1")
        lateinit var link: LocalLink
        var welcomed = false

        override fun connected(welcome: HubFrame.Welcome) {
            welcomed = true
        }

        override fun received(frame: HubFrame) {
            frames += frame
            when (frame) {
                is HubFrame.ProxyMove -> {
                    link.send(ServerFrame.ProxyPlayer(PlayerState(frame.player, frame.player.uppercase(), frame.server)))
                    link.send(ServerFrame.ProxyResult(frame.id))
                }
                is HubFrame.ProxyRegister -> {
                    servers += frame.name
                    link.send(ServerFrame.ProxyServers(servers.toList(), listOf("lobby", "arena-1")))
                }
                else -> Unit
            }
        }

        override fun disconnected(why: String) = Unit

        override fun failed(why: String) = Unit
    }

    private class Recorder : HubListener {
        val frames = java.util.concurrent.CopyOnWriteArrayList<HubFrame>()
        val failures = ArrayList<String>()

        override fun connected(welcome: HubFrame.Welcome) = Unit

        override fun received(frame: HubFrame) {
            frames += frame
        }

        override fun disconnected(why: String) = Unit

        override fun failed(why: String) {
            failures += why
        }

        inline fun <reified T : HubFrame> of(): List<T> = frames.filterIsInstance<T>()
    }

    private val remote = RemoteProxy()
    private val proxyCredential = Credential("velocity", "velocity-secret-0123456789", Grants.preset("proxy"))
    private val hub = Hub("s3cret", remote, Quiet, credentials = listOf(proxyCredential))

    private fun connectProxy(): FakeProxy {
        val proxy = FakeProxy()
        proxy.link = LocalLink(hub, "velocity-1", proxyCredential.secret, proxy, proxyCredential.id, role = "proxy")
        proxy.link.start()
        proxy.link.send(ServerFrame.ProxyServers(proxy.servers.toList(), listOf("lobby", "arena-1")))
        return proxy
    }

    private fun join(name: String): Pair<LocalLink, Recorder> {
        val recorder = Recorder()
        val link = LocalLink(hub, name, "s3cret", recorder)
        link.start()
        return link to recorder
    }

    @Test
    fun `with no proxy the hub has no servers, and a proxy joining gives it its own`() {
        val (_, early) = join("lobby")
        assertTrue(early.failures.single().contains("no server named"), early.failures.toString())
        val proxy = connectProxy()
        assertTrue(proxy.welcomed)
        assertTrue(remote.connected)
        val (lobby, _) = join("lobby")
        assertTrue(lobby.connected)
        assertEquals(listOf("lobby", "arena-1"), hub.states().map { it.name })
    }

    @Test
    fun `the proxy says who's where, and moves players for the hub`() {
        val proxy = connectProxy()
        val (lobbyLink, lobby) = join("lobby")
        join("arena-1")
        proxy.link.send(ServerFrame.ProxyPresence(listOf(PlayerState("u-1", "Ann", "lobby"))))
        assertEquals(listOf("Ann"), hub.adminPlayers().map { it.name })
        assertTrue(lobby.of<HubFrame.PlayerJoined>().isNotEmpty())

        lobbyLink.send(ServerFrame.Send(1, listOf("u-1"), "arena-1", JsonPrimitive("kit")))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (lobby.of<HubFrame.SendResult>().isEmpty()) {
            check(System.nanoTime() < deadline) { "no result" }
            Thread.sleep(5)
        }
        assertNull(lobby.of<HubFrame.SendResult>().single().error)
        assertEquals(listOf(HubFrame.ProxyMove(1, "u-1", "arena-1")), proxy.frames.filterIsInstance<HubFrame.ProxyMove>())

        proxy.link.send(ServerFrame.ProxyPresence(emptyList()))
        assertEquals(emptyList(), hub.adminPlayers(), "a fresh list replaces the hub's")
    }

    @Test
    fun `a server registering itself is added on the proxy, and only a proxy's credential joins as one`() {
        val proxy = connectProxy()
        val recorder = Recorder()
        LocalLink(hub, "arena-7", "s3cret", recorder, register = Registration("10.0.0.7", 25565)).start()
        assertEquals(listOf(HubFrame.ProxyRegister("arena-7", "10.0.0.7", 25565)), proxy.frames.filterIsInstance<HubFrame.ProxyRegister>())
        assertTrue("arena-7" in hub.states().map { it.name })

        val server = Credential("arena", "arena-secret-0123456789", Grants.preset("standard"))
        hub.setCredentials(listOf(proxyCredential, server))
        val impostor = Recorder()
        LocalLink(hub, "evil", server.secret, impostor, server.id, role = "proxy").start()
        assertTrue(impostor.failures.single().contains("isn't a proxy's"), impostor.failures.toString())
        assertTrue(remote.connected, "the real proxy stays")
    }

    @Test
    fun `a login the proxy asks about is placed, and the proxy going fails what waits on it`() {
        val proxy = connectProxy()
        proxy.link.send(ServerFrame.ProxyLogin(9, "u-9", "Zed"))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (proxy.frames.none { it is HubFrame.LoginPlaced }) {
            check(System.nanoTime() < deadline) { "no answer" }
            Thread.sleep(5)
        }
        assertEquals(HubFrame.LoginPlaced(9), proxy.frames.filterIsInstance<HubFrame.LoginPlaced>().single(), "no service places logins")

        proxy.link.stop()
        assertFalse(remote.connected)
        assertEquals(emptyList(), hub.states(), "no proxy, no servers")
        assertEquals("no proxy is connected to the hub", remote.send("u-1", "lobby").toCompletableFuture().join())
    }
}
