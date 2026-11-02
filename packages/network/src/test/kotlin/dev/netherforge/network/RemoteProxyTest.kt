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
                is HubFrame.ProxySync -> {
                    for (server in frame.servers) if (server.name !in servers) servers += server.name
                    if (::link.isInitialized) link.send(ServerFrame.ProxyServers(servers.toList(), listOf("lobby", "arena-1")))
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

    private fun connectProxy(name: String = "velocity-1", hub: Hub = this.hub): FakeProxy {
        val proxy = FakeProxy()
        proxy.link = LocalLink(hub, name, proxyCredential.secret, proxy, proxyCredential.id, role = "proxy")
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

    @Test
    fun `with several proxies, each player's moves go to the proxy they're on, and servers are added on every one`() {
        val one = connectProxy("velocity-1")
        val two = connectProxy("velocity-2")
        val (lobbyLink, lobby) = join("lobby")
        join("arena-1")
        one.link.send(ServerFrame.ProxyPresence(listOf(PlayerState("u-1", "Ann", "lobby"))))
        two.link.send(ServerFrame.ProxyPresence(listOf(PlayerState("u-2", "Bea", "lobby"))))
        assertEquals(mapOf("u-1" to "velocity-1", "u-2" to "velocity-2"), hub.adminPlayers().associate { it.id to it.proxy })
        assertEquals(listOf(Hub.ProxyView("velocity-1", 1, false, true), Hub.ProxyView("velocity-2", 1, false, true)), hub.proxies())

        lobbyLink.send(ServerFrame.Send(1, listOf("u-2"), "arena-1"))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (lobby.of<HubFrame.SendResult>().isEmpty()) {
            check(System.nanoTime() < deadline) { "no result" }
            Thread.sleep(5)
        }
        assertTrue(one.frames.none { it is HubFrame.ProxyMove })
        assertEquals(1, two.frames.count { it is HubFrame.ProxyMove })

        LocalLink(hub, "arena-7", "s3cret", Recorder(), register = Registration("10.0.0.7", 25565)).start()
        assertEquals(1, one.frames.count { it is HubFrame.ProxyRegister })
        assertEquals(1, two.frames.count { it is HubFrame.ProxyRegister })

        // A proxy's fresh list is its own: the other's players stay.
        one.link.send(ServerFrame.ProxyPresence(emptyList()))
        assertEquals(listOf("u-2"), hub.adminPlayers().map { it.id })

        assertNull(hub.drainProxy("velocity-2", true))
        assertEquals(listOf(HubFrame.ProxyDrain(true)), two.frames.filterIsInstance<HubFrame.ProxyDrain>())
        assertTrue(hub.proxies().single { it.name == "velocity-2" }.draining)
    }

    @Test
    fun `a proxy joining later gets every server added at run time, and adding one again fills in whoever lacks it`() {
        val one = connectProxy("velocity-1")
        assertNull(hub.adminRegister("arena-9", "10.0.0.9", 25565, "arena", null))
        LocalLink(hub, "arena-7", "s3cret", Recorder(), register = Registration("10.0.0.7", 25565)).start()
        assertEquals(listOf("arena-9", "arena-7"), one.frames.filterIsInstance<HubFrame.ProxyRegister>().map { it.name })

        val two = connectProxy("velocity-2")
        assertEquals(
            HubFrame.ProxySync(
                listOf(HubFrame.ProxyServer("arena-9", "10.0.0.9", 25565, "arena"), HubFrame.ProxyServer("arena-7", "10.0.0.7", 25565))
            ),
            two.frames.filterIsInstance<HubFrame.ProxySync>().single()
        )
        assertTrue("arena-9" in two.servers && "arena-7" in two.servers)
        assertEquals(listOf(true, true), hub.proxies().map { it.synced })

        // Proxy two loses arena-9 (it was taken off by hand): it isn't synced, and adding it again fills it in alone.
        two.servers -= "arena-9"
        two.link.send(ServerFrame.ProxyServers(two.servers.toList(), listOf("lobby", "arena-1")))
        assertEquals(listOf(true, false), hub.proxies().map { it.synced })
        assertNull(hub.adminRegister("arena-9", "10.0.0.9", 25565, "arena", null))
        assertEquals(1, one.frames.count { it is HubFrame.ProxyRegister && it.name == "arena-9" }, "the one that has it hears nothing")
        assertEquals(1, two.frames.count { it is HubFrame.ProxyRegister && it.name == "arena-9" })
        assertEquals(listOf(true, true), hub.proxies().map { it.synced })
    }

    private fun answer(proxy: FakeProxy): HubFrame.LoginPlaced {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (proxy.frames.none { it is HubFrame.LoginPlaced }) {
            check(System.nanoTime() < deadline) { "no answer" }
            Thread.sleep(5)
        }
        return proxy.frames.filterIsInstance<HubFrame.LoginPlaced>().last()
    }

    @Test
    fun `a second login through another proxy is refused, or takes over when the network says so`() {
        val one = connectProxy("velocity-1")
        val two = connectProxy("velocity-2")
        one.link.send(ServerFrame.ProxyPresence(listOf(PlayerState("u-1", "Ann", "lobby"))))
        two.link.send(ServerFrame.ProxyLogin(1, "u-1", "Ann"))
        assertTrue(answer(two).deny!!.contains("already on the network"))

        val takeover =
            Hub("s3cret", RemoteProxy(), Quiet, NetworkLimits(takeOverDuplicateLogins = true), credentials = listOf(proxyCredential))
        val three = connectProxy("velocity-1", takeover)
        val four = connectProxy("velocity-2", takeover)
        three.link.send(ServerFrame.ProxyPresence(listOf(PlayerState("u-1", "Ann", "lobby"))))
        four.link.send(ServerFrame.ProxyLogin(1, "u-1", "Ann"))
        assertNull(answer(four).deny)
        assertEquals(
            listOf(HubFrame.ProxyKick("u-1", "You logged in from somewhere else")),
            three.frames.filterIsInstance<HubFrame.ProxyKick>()
        )
    }
}
