package dev.netherforge.network

import kotlinx.serialization.json.JsonPrimitive
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The admin API for services outside the network: scopes, presence, sends, messages, channels and placement. */
class AdminApiTest {
    private class FakeProxy : HubProxy {
        val names = mutableListOf("lobby", "arena-1", "arena-2")
        val sends = ArrayList<Pair<String, String>>()
        val shown = ArrayList<String>()
        lateinit var hub: Hub

        override fun servers() = names

        override fun players() = emptyList<PlayerState>()

        override fun send(id: String, server: String): CompletionStage<String?> {
            synchronized(sends) { sends += id to server }
            hub.playerMoved(id, id.uppercase(), server)
            return CompletableFuture.completedFuture(null)
        }

        override fun message(id: String, kind: String, text: String, subtitle: String?): String? {
            shown += "$id $kind $text"
            return null
        }

        override fun kick(id: String, message: String): String? {
            shown += "$id kicked $message"
            return null
        }
    }

    private object Quiet : HubLog {
        override fun info(message: String) = Unit

        override fun warn(message: String) = Unit
    }

    private class Recorder : HubListener {
        val frames = java.util.concurrent.CopyOnWriteArrayList<HubFrame>()

        override fun connected(welcome: HubFrame.Welcome) = Unit

        override fun received(frame: HubFrame) {
            frames += frame
        }

        override fun disconnected(why: String) = Unit

        override fun failed(why: String) = Unit

        inline fun <reified T : HubFrame> of(): List<T> = frames.filterIsInstance<T>()
    }

    private val proxy = FakeProxy()
    private val hub = Hub("s3cret", proxy, Quiet, NetworkLimits(placementTimeoutMillis = 300)).also { proxy.hub = it }
    private val all = AdminClient("ops", "ops-token-long-enough-0123456789", AdminScope.entries.toSet())
    private val reader = AdminClient("watch", "watch-token-long-enough-0123456789", setOf(AdminScope.PRESENCE_READ))
    private val admin = HubAdmin(hub, InetSocketAddress(InetAddress.getLoopbackAddress(), 0), {
        listOf(all, reader)
    }, Quiet).also { it.start() }
    private val http = HttpClient.newHttpClient()

    @AfterTest
    fun stop() = admin.stop()

    private fun call(method: String, path: String, body: String? = null, client: AdminClient = all): Pair<Int, String> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${admin.port}$path"))
            .header("Authorization", "Bearer ${client.token}")
            .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to response.body()
    }

    /** An admin stream, its events (name and JSON) read on a thread of its own. */
    private fun stream(query: String = "", client: AdminClient = all): LinkedBlockingQueue<Pair<String, String>> {
        val events = LinkedBlockingQueue<Pair<String, String>>()
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${admin.port}/v1/events$query"))
            .header("Authorization", "Bearer ${client.token}").build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        Thread {
            BufferedReader(InputStreamReader(response.body())).use { lines ->
                var name: String? = null
                for (line in lines.lineSequence()) {
                    when {
                        line.startsWith("event: ") -> name = line.removePrefix("event: ")
                        line.startsWith("data: ") -> events += name!! to line.removePrefix("data: ")
                    }
                }
            }
        }.apply { isDaemon = true }.start()
        return events
    }

    /** The next event named [name] (any, when null), skipping others. */
    private fun LinkedBlockingQueue<Pair<String, String>>.next(name: String? = null): Pair<String, String> {
        while (true) {
            val event = assertNotNull(poll(10, TimeUnit.SECONDS))
            if (name == null || event.first == name) return event
        }
    }

    private fun join(name: String, group: String? = null): Pair<LocalLink, Recorder> {
        val recorder = Recorder()
        val link = LocalLink(hub, name, "s3cret", recorder, group = group)
        link.start()
        return link to recorder
    }

    private fun waitFor(done: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!done()) {
            check(System.nanoTime() < deadline) { "timed out" }
            hub.tick()
            Thread.sleep(10)
        }
    }

    @Test
    fun `a client may do only what its scopes allow`() {
        assertEquals(401, call("GET", "/v1/players", client = all.copy(token = "wrong-token-long-enough-0123456")).first)
        assertEquals(200, call("GET", "/v1/players", client = reader).first)
        assertEquals(403, call("GET", "/v1/servers", client = reader).first)
        assertTrue(call("POST", "/v1/players/Ann/kick", "{}", client = reader).second.contains("players:kick"))
    }

    @Test
    fun `presence is listed, then streamed as it changes`() {
        hub.playerMoved("u-1", "Ann", "lobby")
        assertTrue(call("GET", "/v1/players").second.contains("\"name\":\"Ann\""))
        val events = stream(client = reader)
        Thread.sleep(200)
        hub.playerMoved("u-2", "Bea", "lobby")
        hub.playerMoved("u-2", "Bea", "arena-1")
        hub.playerLeft("u-2")
        assertEquals("player_joined", events.next().first)
        assertEquals("player_moved" to """{"t":"player_moved","id":"u-2","server":"arena-1","previous":"lobby"}""", events.next())
        assertEquals("player_left", events.next().first)
    }

    @Test
    fun `a client sends players, shows them messages and kicks them`() {
        join("lobby")
        join("arena-1", "arena")
        hub.playerMoved("u-1", "Ann", "lobby")
        val (status, body) = call("POST", "/v1/players/Ann/send", """{ "group": "arena", "data": { "team": "red" } }""")
        assertEquals(200, status, body)
        assertTrue(body.contains("\"server\":\"arena-1\""), body)
        assertEquals(listOf("u-1" to "arena-1"), proxy.sends)
        assertEquals(409, call("POST", "/v1/players/u-1/send", """{ "server": "arena-1" }""").first, "already there")

        assertEquals(200, call("POST", "/v1/players/u-1/message", """{ "kind": "title", "text": "<gold>Hi" }""").first)
        assertEquals(200, call("POST", "/v1/players/u-1/kick", """{ "message": "bye" }""").first)
        assertEquals(listOf("u-1 title <gold>Hi", "u-1 kicked bye"), proxy.shown)
        assertEquals(404, call("POST", "/v1/players/Nobody/message", """{ "text": "?" }""").first)
    }

    @Test
    fun `a client publishes on channels, hears them, and answers requests addressed to it`() {
        val (lobbyLink, lobby) = join("lobby")
        lobbyLink.send(ServerFrame.Listen(listOf("lobby:news"), emptyList()))
        val events = stream("?listen=lobby:news")
        Thread.sleep(200)
        assertEquals(200, call("POST", "/v1/channels/publish", """{ "channel": "lobby:news", "value": "restart" }""").first)
        assertEquals(HubFrame.Message("lobby:news", "admin:ops", JsonPrimitive("restart")), lobby.of<HubFrame.Message>().single())
        assertTrue(events.next("message").second.contains("admin:ops"), "it hears its own, as servers do")

        lobbyLink.send(ServerFrame.Publish("lobby:news", JsonPrimitive("hi")))
        assertTrue(events.next("message").second.contains("\"from\":\"lobby\""))

        lobbyLink.send(ServerFrame.Request(7, "lobby:match", value = JsonPrimitive("Ann"), admin = "ops"))
        val (name, json) = events.next("request")
        assertEquals("request", name)
        val id = Regex("\"id\":(\\d+)").find(json)!!.groupValues[1]
        assertEquals(200, call("POST", "/v1/requests/$id/reply", """{ "value": "arena-2" }""").first)
        assertEquals(HubFrame.Reply(7, JsonPrimitive("arena-2")), lobby.of<HubFrame.Reply>().single())
        lobbyLink.send(ServerFrame.Request(8, "lobby:match", value = JsonPrimitive("Ann"), admin = "nobody"))
        assertTrue(lobby.of<HubFrame.Reply>().last().error!!.contains("isn't connected"))
    }

    @Test
    fun `a placing client decides where a group's players go, holds them, or turns them away`() {
        val (lobbyLink, lobby) = join("lobby")
        join("arena-1", "arena")
        join("arena-2", "arena")
        for (id in listOf("p", "q", "r")) hub.playerMoved(id, id.uppercase(), "lobby")
        val events = stream("?place=arena")
        Thread.sleep(200)

        lobbyLink.send(ServerFrame.Send(1, listOf("p"), group = "arena"))
        val ask = events.next("place")
        val first = Regex("\"id\":(\\d+)").find(ask.second)!!.groupValues[1]
        assertEquals(200, call("POST", "/v1/placements/$first", """{ "server": "arena-2" }""").first)
        waitFor { lobby.of<HubFrame.SendResult>().isNotEmpty() }
        assertEquals(HubFrame.SendResult(1, null, "arena-2"), lobby.of<HubFrame.SendResult>().single(), "not least: the client's pick")

        lobbyLink.send(ServerFrame.Send(2, listOf("q"), group = "arena", waitMillis = 60_000))
        val held = Regex("\"id\":(\\d+)").find(events.next("place").second)!!.groupValues[1]
        call("POST", "/v1/placements/$held", """{ "hold": "Finding you a match" }""")
        waitFor { lobby.of<HubFrame.SendWaiting>().isNotEmpty() }
        assertEquals(HubFrame.SendWaiting(2, 0, "Finding you a match"), lobby.of<HubFrame.SendWaiting>().single())
        call("POST", "/v1/placements/$held", """{ "fail": "no match tonight" }""")
        waitFor { lobby.of<HubFrame.SendResult>().size == 2 }
        assertEquals("no match tonight", lobby.of<HubFrame.SendResult>().last().error)

        // No answer in time: the group's own balancing.
        lobbyLink.send(ServerFrame.Send(3, listOf("r"), group = "arena"))
        events.next("place")
        waitFor { lobby.of<HubFrame.SendResult>().size == 3 }
        assertEquals("arena-1", lobby.of<HubFrame.SendResult>().last().server)
    }

    @Test
    fun `logins are placed by the client placing them, or by the proxy without one`() {
        assertEquals(LoginPlace(), hub.placeLogin("u-9", "Zed").get(1, TimeUnit.SECONDS))
        val events = stream("?place=${Hub.LOGINS}")
        Thread.sleep(200)
        val placed = hub.placeLogin("u-9", "Zed")
        val ask = events.next("place")
        assertTrue(ask.second.contains("\"login\":true"), ask.second)
        val id = Regex("\"id\":(\\d+)").find(ask.second)!!.groupValues[1]
        call("POST", "/v1/placements/$id", """{ "server": "lobby" }""")
        assertEquals(LoginPlace("lobby"), placed.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun `a revoked client's streams end`() {
        val events = stream("?place=arena")
        Thread.sleep(200)
        hub.detach("ops")
        join("lobby").first.send(ServerFrame.Send(1, listOf("nobody"), group = "arena"))
        assertTrue(events.poll(500, TimeUnit.MILLISECONDS) == null, "it isn't asked any more")
    }
}
