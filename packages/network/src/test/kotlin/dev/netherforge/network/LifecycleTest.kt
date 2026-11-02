package dev.netherforge.network

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Servers starting, ready, draining and stopping; sends that wait for room; players moved off a server that stops. */
class LifecycleTest {
    private class FakeProxy : HubProxy {
        val names = mutableListOf("lobby", "lobby-2", "arena-1", "arena-2")
        val sends = ArrayList<Pair<String, String>>()
        lateinit var hub: Hub

        override fun servers() = names

        override fun players() = emptyList<PlayerState>()

        override fun send(id: String, server: String): CompletionStage<String?> {
            sends += id to server
            hub.playerMoved(id, id.uppercase(), server)
            return CompletableFuture.completedFuture(null)
        }
    }

    private object Quiet : HubLog {
        override fun info(message: String) = Unit

        override fun warn(message: String) = Unit
    }

    private class Recorder : HubListener {
        val frames = ArrayList<HubFrame>()

        override fun connected(welcome: HubFrame.Welcome) = Unit

        override fun received(frame: HubFrame) {
            frames += frame
        }

        override fun disconnected(why: String) = Unit

        override fun failed(why: String) = Unit

        inline fun <reified T : HubFrame> of(): List<T> = frames.filterIsInstance<T>()
    }

    private var time = 1_000L
    private val proxy = FakeProxy()
    private val events = ArrayList<HubEvent>()
    private val hub = Hub(
        "s3cret",
        proxy,
        Quiet,
        clock = { time },
        groups = mapOf("arena" to GroupSettings(maxWaiting = 2), "mini" to GroupSettings(fallback = "lobby")),
        events = { events += it }
    ).also { proxy.hub = it }

    private fun join(
        name: String,
        group: String? = null,
        capacity: Int? = null,
        state: Lifecycle = Lifecycle.READY
    ): Pair<LocalLink, Recorder> {
        val recorder = Recorder()
        val link = LocalLink(hub, name, "s3cret", recorder, group = group, capacity = capacity, state = { state })
        link.start()
        return link to recorder
    }

    @Test
    fun `a starting instance isn't sent players until it's ready, and a send that waits goes then`() {
        val (lobbyLink, lobby) = join("lobby")
        val (arenaLink, _) = join("arena-1", "arena", state = Lifecycle.STARTING)
        hub.playerMoved("p", "P", "lobby")
        lobbyLink.send(ServerFrame.Send(1, listOf("p"), group = "arena"))
        assertEquals("no instance of arena is ready", lobby.of<HubFrame.SendResult>().single().error)

        lobbyLink.send(ServerFrame.Send(2, listOf("p"), group = "arena", waitMillis = 60_000))
        assertEquals(listOf(HubFrame.SendWaiting(2, 1)), lobby.of<HubFrame.SendWaiting>())
        assertEquals(listOf<HubEvent>(HubEvent.GroupWanted("arena", 1)), events)

        arenaLink.send(ServerFrame.Status(state = Lifecycle.READY, startupMillis = 4200))
        assertEquals(HubFrame.SendResult(2, null, "arena-1"), lobby.of<HubFrame.SendResult>().last())
        assertTrue(HubEvent.ServerReady("arena-1", "arena", 4200) in events)
        assertEquals(4200, hub.states().single { it.name == "arena-1" }.startupMillis)
    }

    @Test
    fun `waiting sends go in order, are told their place, and give up at their time`() {
        val (lobbyLink, lobby) = join("lobby")
        val (arenaLink, _) = join("arena-1", "arena", capacity = 1)
        for (id in listOf("a", "p", "q", "r")) hub.playerMoved(id, id.uppercase(), "lobby")
        proxy.hub.playerMoved("a", "A", "arena-1")
        lobbyLink.send(ServerFrame.Send(1, listOf("p"), group = "arena", waitMillis = 60_000))
        lobbyLink.send(ServerFrame.Send(2, listOf("q"), group = "arena", waitMillis = 10_000))
        lobbyLink.send(ServerFrame.Send(3, listOf("r"), group = "arena", waitMillis = 60_000))
        assertEquals(
            "2 sends already wait for arena: no instance of arena has room for them",
            lobby.of<HubFrame.SendResult>().single().error
        )
        assertEquals(listOf(HubFrame.SendWaiting(1, 1), HubFrame.SendWaiting(2, 2)), lobby.of<HubFrame.SendWaiting>())

        time += 11_000
        hub.tick()
        assertEquals("waited 10 seconds: no instance of arena has room for them", lobby.of<HubFrame.SendResult>().last().error)

        hub.playerLeft("a")
        assertEquals(HubFrame.SendResult(1, null, "arena-1"), lobby.of<HubFrame.SendResult>().last(), "room again: the first goes")
        arenaLink.stop()
    }

    @Test
    fun `a player leaving the network stops their send waiting`() {
        val (lobbyLink, lobby) = join("lobby")
        hub.playerMoved("p", "P", "lobby")
        lobbyLink.send(ServerFrame.Send(1, listOf("p"), group = "arena", waitMillis = 60_000))
        hub.playerLeft("p")
        assertEquals("P left the network while waiting", lobby.of<HubFrame.SendResult>().single().error)
        assertEquals(emptyMap(), hub.waiting())
    }

    @Test
    fun `a draining or stopping server takes no new players, and one that stops sends its own to the fallback`() {
        val (lobbyLink, lobby) = join("lobby")
        join("lobby-2", "lobby")
        val (miniLink, _) = join("arena-2", "mini")
        hub.playerMoved("p", "P", "lobby")
        hub.playerMoved("q", "Q", "arena-2")
        hub.playerMoved("r", "R", "arena-2")
        miniLink.send(ServerFrame.Status(state = Lifecycle.DRAINING))
        lobbyLink.send(ServerFrame.Send(1, listOf("p"), "arena-2"))
        assertEquals("arena-2 is draining: it takes no new players", lobby.of<HubFrame.SendResult>().single().error)

        miniLink.send(ServerFrame.Evacuate)
        assertEquals(setOf("q", "r"), proxy.sends.filter { it.first != "p" }.map { it.first }.toSet())
        assertTrue(proxy.sends.all { it.second.startsWith("lobby") }, proxy.sends.toString())
        assertEquals(Lifecycle.STOPPING, hub.states().single { it.name == "arena-2" }.state)
    }

    @Test
    fun `idle servers and servers going are heard by the webhook, and the admin API can drain one`() {
        val (miniLink, mini) = join("arena-2", "mini")
        miniLink.send(ServerFrame.Status(idle = true))
        assertTrue(HubEvent.ServerIdle("arena-2", "mini") in events)
        assertNull(hub.drain("arena-2", true))
        assertEquals(listOf(HubFrame.Drain(true)), mini.of<HubFrame.Drain>())
        assertTrue(hub.drain("nowhere", true)!!.contains("isn't connected"))
        miniLink.stop()
        assertTrue(HubEvent.ServerGone("arena-2", "mini") in events)
    }

    @Test
    fun `the webhook gets each event as signed JSON`() {
        val got = java.util.concurrent.LinkedBlockingQueue<Pair<String, String>>()
        val receiver = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0)
        receiver.createContext("/hook") { exchange ->
            val body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            got += exchange.requestHeaders.getFirst("X-NetherForge-Signature") to body
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        receiver.start()
        try {
            val webhook = HubEvents(java.net.URI("http://127.0.0.1:${receiver.address.port}/hook"), "hook-secret", Quiet) { 42 }
            webhook.send(HubEvent.GroupWanted("arena", 3))
            val (signature, body) = got.poll(10, java.util.concurrent.TimeUnit.SECONDS)!!
            assertEquals("""{"event":"group_wanted","at":42,"group":"arena","waiting":3}""", body)
            assertEquals("sha256=" + HubEvents.sign("hook-secret", body), signature)
        } finally {
            receiver.stop(0)
        }
    }
}
