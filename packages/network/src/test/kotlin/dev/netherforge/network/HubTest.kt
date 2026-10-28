package dev.netherforge.network

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HubTest {
    private class FakeProxy(val names: MutableList<String> = mutableListOf("lobby", "survival", "creative")) : HubProxy {
        val sends = ArrayList<Pair<String, String>>()
        var answer: String? = null

        override fun servers() = names

        override fun players() = listOf(PlayerState("u-1", "Ann", "lobby"))

        override fun send(id: String, server: String): CompletionStage<String?> {
            sends += id to server
            return CompletableFuture.completedFuture(answer)
        }
    }

    private object Quiet : HubLog {
        override fun info(message: String) = Unit

        override fun warn(message: String) = Unit
    }

    /** A server's end, recording what it hears. */
    private class Recorder : HubListener {
        var welcome: HubFrame.Welcome? = null
        val frames = ArrayList<HubFrame>()
        val failures = ArrayList<String>()
        var disconnects = 0

        override fun connected(welcome: HubFrame.Welcome) {
            this.welcome = welcome
        }

        override fun received(frame: HubFrame) {
            frames += frame
        }

        override fun disconnected(why: String) {
            disconnects++
        }

        override fun failed(why: String) {
            failures += why
        }

        inline fun <reified T : HubFrame> of(): List<T> = frames.filterIsInstance<T>()
    }

    private var time = 1_000L
    private val proxy = FakeProxy()
    private val hub = Hub("s3cret", proxy, Quiet, NetworkLimits(maxValueBytes = 100, maxKeysPerPackage = 3), clock = { time })

    private fun join(name: String, secret: String = "s3cret"): Pair<LocalLink, Recorder> {
        val recorder = Recorder()
        val link = LocalLink(hub, name, secret, recorder)
        link.start()
        return link to recorder
    }

    @Test
    fun `a server with the secret and a name the proxy has is welcomed with the network as it is`() {
        val (link, lobby) = join("lobby")
        assertTrue(link.connected)
        val welcome = assertNotNull(lobby.welcome)
        assertEquals("lobby", welcome.server)
        assertEquals(listOf(ServerState("lobby", true), ServerState("survival", false), ServerState("creative", false)), welcome.servers)
        assertEquals(listOf(PlayerState("u-1", "Ann", "lobby")), welcome.players)

        val (_, survival) = join("Survival")
        assertEquals("survival", survival.welcome?.server, "the proxy's spelling")
        assertEquals<List<HubFrame>>(listOf(HubFrame.Server(ServerState("survival", true))), lobby.frames)
    }

    @Test
    fun `a wrong secret or a server the proxy doesn't have is refused`() {
        val (wrong, recorder) = join("lobby", secret = "guess")
        assertFalse(wrong.connected)
        assertTrue(recorder.failures.single().contains("secret"))
        val (unknown, other) = join("minigames")
        assertFalse(unknown.connected)
        assertTrue(other.failures.single().contains("no server named \"minigames\""), other.failures.single())
    }

    @Test
    fun `a server connecting again under its name replaces the old connection`() {
        val (first, one) = join("lobby")
        val (second, _) = join("lobby")
        assertFalse(first.connected)
        assertTrue(second.connected)
        assertEquals(1, one.disconnects)
        assertEquals(listOf("lobby"), hub.linked())
    }

    @Test
    fun `a message reaches every server listening to its channel, the sender's own included`() {
        val (lobbyLink, lobby) = join("lobby")
        val (survivalLink, survival) = join("survival")
        val (_, creative) = join("creative")
        lobbyLink.send(ServerFrame.Listen(listOf("chat:global"), emptyList()))
        survivalLink.send(ServerFrame.Listen(listOf("chat:global"), emptyList()))
        lobby.frames.clear()
        survival.frames.clear()
        creative.frames.clear()

        survivalLink.send(ServerFrame.Publish("chat:global", JsonPrimitive("hi")))
        assertEquals(listOf(HubFrame.Message("chat:global", "survival", JsonPrimitive("hi"))), lobby.of<HubFrame.Message>())
        assertEquals(1, survival.of<HubFrame.Message>().size)
        assertEquals(emptyList<HubFrame.Message>(), creative.of<HubFrame.Message>())

        survivalLink.send(ServerFrame.Publish("chat:global", JsonPrimitive("just you"), to = "lobby"))
        assertEquals(2, lobby.of<HubFrame.Message>().size)
        assertEquals(1, survival.of<HubFrame.Message>().size)

        survivalLink.send(ServerFrame.Publish("chat:global", JsonPrimitive("x".repeat(200))))
        assertEquals(2, lobby.of<HubFrame.Message>().size, "over the value limit")
        assertTrue(survival.of<HubFrame.Problem>().single().message.contains("over the network's limit"))
    }

    @Test
    fun `a request is relayed to the server asked and its reply back to the asker`() {
        val (lobbyLink, lobby) = join("lobby")
        val (survivalLink, survival) = join("survival")
        survivalLink.send(ServerFrame.Listen(listOf("shop:price"), emptyList()))

        lobbyLink.send(ServerFrame.Request(7, "shop:price", "survival", JsonPrimitive("diamond")))
        val asked = survival.of<HubFrame.Requested>().single()
        assertEquals("lobby", asked.from)
        assertEquals(JsonPrimitive("diamond"), asked.value)
        survivalLink.send(ServerFrame.Reply(asked.id, JsonPrimitive(42)))
        assertEquals(HubFrame.Reply(7, JsonPrimitive(42)), lobby.of<HubFrame.Reply>().single())

        lobbyLink.send(ServerFrame.Request(8, "shop:other", "survival", JsonPrimitive(1)))
        assertTrue(lobby.of<HubFrame.Reply>().last().error!!.contains("nothing on survival answers"))
        lobbyLink.send(ServerFrame.Request(9, "shop:price", "creative", JsonPrimitive(1)))
        assertTrue(lobby.of<HubFrame.Reply>().last().error!!.contains("creative isn't connected"))
    }

    @Test
    fun `a request fails when its server goes or doesn't answer in time`() {
        val (lobbyLink, lobby) = join("lobby")
        val (survivalLink, _) = join("survival")
        survivalLink.send(ServerFrame.Listen(listOf("shop:price"), emptyList()))
        lobbyLink.send(ServerFrame.Request(1, "shop:price", "survival", JsonPrimitive(1)))
        lobbyLink.send(ServerFrame.Request(2, "shop:price", "survival", JsonPrimitive(2)))
        time += 31_000
        hub.tick()
        val replies = lobby.of<HubFrame.Reply>()
        assertEquals(listOf(1L, 2L), replies.map { it.id })
        assertTrue(replies.all { it.error!!.contains("didn't answer within 30 seconds") })

        lobbyLink.send(ServerFrame.Request(3, "shop:price", "survival", JsonPrimitive(3)))
        survivalLink.stop()
        assertTrue(lobby.of<HubFrame.Reply>().last().error!!.contains("disconnected before answering"))
    }

    @Test
    fun `maps hand a new listener what they hold and every listener each change`() {
        val (lobbyLink, lobby) = join("lobby")
        val (survivalLink, survival) = join("survival")
        lobbyLink.send(ServerFrame.Listen(emptyList(), listOf("game:scores")))
        lobbyLink.send(ServerFrame.MapWrite(1, "game:scores", "ann", MapOp.SET, JsonPrimitive(3)))
        assertEquals(HubFrame.MapResult(1, JsonPrimitive(true)), lobby.of<HubFrame.MapResult>().single())
        assertEquals(
            HubFrame.MapChanged("game:scores", "ann", JsonPrimitive(3), "lobby", MapCause.SET),
            lobby.of<HubFrame.MapChanged>().single()
        )

        survivalLink.send(ServerFrame.Listen(emptyList(), listOf("game:scores")))
        assertEquals(HubFrame.MapSnapshot("game:scores", mapOf("ann" to JsonPrimitive(3))), survival.of<HubFrame.MapSnapshot>().single())

        survivalLink.send(ServerFrame.MapWrite(2, "game:scores", "ann", MapOp.ADD, amount = 2.5))
        assertEquals(JsonPrimitive(5.5), survival.of<HubFrame.MapResult>().single().value)
        assertEquals(JsonPrimitive(5.5), lobby.of<HubFrame.MapChanged>().last().value)
        survivalLink.send(ServerFrame.MapWrite(3, "game:scores", "ann", MapOp.ADD, amount = 0.5))
        assertEquals(JsonPrimitive(6), survival.of<HubFrame.MapResult>().last().value, "a whole number stays one")

        survivalLink.send(ServerFrame.MapWrite(4, "game:scores", "ann", MapOp.INSERT, JsonPrimitive(0)))
        assertEquals(JsonPrimitive(false), survival.of<HubFrame.MapResult>().last().value)
        survivalLink.send(ServerFrame.MapWrite(5, "game:scores", "bob", MapOp.INSERT, JsonPrimitive(0)))
        assertEquals(JsonPrimitive(true), survival.of<HubFrame.MapResult>().last().value)

        survivalLink.send(ServerFrame.MapWrite(6, "game:scores", "bob", MapOp.DELETE))
        assertEquals(
            HubFrame.MapChanged("game:scores", "bob", null, "survival", MapCause.DELETE),
            lobby.of<HubFrame.MapChanged>().last()
        )
        survivalLink.send(ServerFrame.MapWrite(7, "game:scores", "bob", MapOp.DELETE))
        assertEquals(JsonPrimitive(false), survival.of<HubFrame.MapResult>().last().value)
    }

    @Test
    fun `keys expire, and each package is held to its limits`() {
        val (link, recorder) = join("lobby")
        link.send(ServerFrame.Listen(emptyList(), listOf("game:locks")))
        link.send(ServerFrame.MapWrite(1, "game:locks", "arena", MapOp.SET, JsonPrimitive("ann"), expiresMillis = 5_000))
        time += 4_000
        hub.tick()
        assertEquals(1, recorder.of<HubFrame.MapChanged>().size)
        time += 1_000
        hub.tick()
        assertEquals(
            HubFrame.MapChanged("game:locks", "arena", null, null, MapCause.EXPIRED),
            recorder.of<HubFrame.MapChanged>().last()
        )

        for (key in listOf("a", "b", "c")) link.send(ServerFrame.MapWrite(2, "game:other", key, MapOp.SET, JsonPrimitive(1)))
        link.send(ServerFrame.MapWrite(3, "game:locks", "d", MapOp.SET, JsonPrimitive(1)))
        assertTrue(recorder.of<HubFrame.MapResult>().last().error!!.contains("already keeps 3 keys"))
        link.send(ServerFrame.MapWrite(4, "other:locks", "d", MapOp.SET, JsonPrimitive(1)))
        assertNull(recorder.of<HubFrame.MapResult>().last().error, "another package has its own")
        link.send(ServerFrame.MapWrite(5, "game:other", "a", MapOp.ADD, amount = 1.0))
        assertEquals(JsonPrimitive(2), recorder.of<HubFrame.MapResult>().last().value, "changing a key it has isn't a new one")
        link.send(ServerFrame.MapWrite(6, "game:other", "a", MapOp.SET, JsonPrimitive("x".repeat(200))))
        assertTrue(recorder.of<HubFrame.MapResult>().last().error!!.contains("over the network's limit"))
        link.send(ServerFrame.MapWrite(7, "game:other", "e", MapOp.SET, buildJsonObject { put("text", "y") }))
        link.send(ServerFrame.MapWrite(8, "game:other", "b", MapOp.ADD, amount = 1.0))
        link.send(ServerFrame.MapWrite(9, "game:other", "a", MapOp.SET, JsonPrimitive("text")))
        link.send(ServerFrame.MapWrite(10, "game:other", "a", MapOp.ADD, amount = 1.0))
        assertTrue(recorder.of<HubFrame.MapResult>().last().error!!.contains("isn't a number"))
    }

    @Test
    fun `maps are saved and read back, without what expired meanwhile`() {
        val file = Files.createTempDirectory("hub").resolve("maps.json")
        val first = Hub("s3cret", proxy, Quiet, store = FileMapStore(file), clock = { time })
        val recorder = Recorder()
        val link = LocalLink(first, "lobby", "s3cret", recorder)
        link.start()
        link.send(ServerFrame.MapWrite(1, "game:scores", "ann", MapOp.SET, JsonPrimitive(3)))
        link.send(ServerFrame.MapWrite(2, "game:locks", "arena", MapOp.SET, JsonPrimitive("ann"), expiresMillis = 10_000))
        first.close()

        time += 60_000
        val second = Hub("s3cret", proxy, Quiet, store = FileMapStore(file), clock = { time })
        assertEquals(mapOf("ann" to JsonPrimitive(3)), second.entries("game:scores"))
        assertEquals(emptyMap<String, Any>(), second.entries("game:locks"))
    }

    @Test
    fun `presence follows the proxy, and a sent player's server hears they're coming`() {
        val (lobbyLink, lobby) = join("lobby")
        val (_, survival) = join("survival")
        lobby.frames.clear()

        hub.playerMoved("u-2", "Bob", "lobby")
        hub.playerMoved("u-2", "Bob", "survival")
        hub.playerLeft("u-2")
        assertEquals(
            listOf(
                HubFrame.PlayerJoined(PlayerState("u-2", "Bob", "lobby")),
                HubFrame.PlayerMoved("u-2", "survival", "lobby"),
                HubFrame.PlayerLeft("u-2")
            ),
            lobby.frames.toList<HubFrame>()
        )

        survival.frames.clear()
        lobbyLink.send(ServerFrame.Send(5, "u-1", "survival", JsonPrimitive("kit")))
        assertEquals(listOf("u-1" to "survival"), proxy.sends)
        assertEquals(HubFrame.Arriving("u-1", "lobby", JsonPrimitive("kit")), survival.frames.single())
        assertEquals(HubFrame.SendResult(5, null), lobby.of<HubFrame.SendResult>().single())

        proxy.answer = "the server is full"
        lobbyLink.send(ServerFrame.Send(6, "u-1", "survival"))
        assertIs<HubFrame.ArrivalCancelled>(survival.frames.last())
        assertEquals("the server is full", lobby.of<HubFrame.SendResult>().last().error)

        lobbyLink.send(ServerFrame.Send(7, "u-9", "survival"))
        assertEquals("they aren't online in the network", lobby.of<HubFrame.SendResult>().last().error)
        lobbyLink.send(ServerFrame.Send(8, "u-1", "lobby"))
        assertEquals("they're already on lobby", lobby.of<HubFrame.SendResult>().last().error)
    }

    @Test
    fun `servers talk to a hub over TCP`() {
        val server = HubServer(hub, InetSocketAddress(InetAddress.getLoopbackAddress(), 0), Quiet)
        server.start()
        try {
            class Queued : HubListener {
                val welcome = CompletableFuture<HubFrame.Welcome>()
                val frames = LinkedBlockingQueue<HubFrame>()

                override fun connected(welcome: HubFrame.Welcome) {
                    this.welcome.complete(welcome)
                }

                override fun received(frame: HubFrame) {
                    frames += frame
                }

                override fun disconnected(why: String) = Unit

                override fun failed(why: String) = Unit
            }
            val lobby = Queued()
            val survival = Queued()
            val lobbyClient = HubClient("127.0.0.1", server.port, "lobby", "s3cret", lobby)
            val survivalClient = HubClient("127.0.0.1", server.port, "survival", "s3cret", survival)
            lobbyClient.start()
            survivalClient.start()
            lobby.welcome.get(10, TimeUnit.SECONDS)
            survival.welcome.get(10, TimeUnit.SECONDS)
            assertTrue(survivalClient.send(ServerFrame.Listen(listOf("chat:global"), emptyList())))
            // The listen is in before the publish: frames from one server are handled in order, but these are two.
            assertTrue(lobbyClient.send(ServerFrame.Ping))
            while (survival.frames.isEmpty() && hub.linked().size < 2) Thread.sleep(10)
            Thread.sleep(100)
            lobbyClient.send(ServerFrame.Publish("chat:global", JsonPrimitive("over the wire")))
            var message: HubFrame? = null
            while (message !is HubFrame.Message) message = survival.frames.poll(10, TimeUnit.SECONDS) ?: break
            assertEquals(HubFrame.Message("chat:global", "lobby", JsonPrimitive("over the wire")), message)
            lobbyClient.stop()
            survivalClient.stop()
        } finally {
            server.stop()
        }
    }
}
