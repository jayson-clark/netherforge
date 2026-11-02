package dev.netherforge.network

import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Groups of servers: instances, picking one to send players to, and publishing and asking a whole group. */
class GroupsTest {
    private class FakeProxy : HubProxy {
        val names = mutableListOf("lobby", "arena-1", "arena-2", "arena-3", "legacy")
        val sends = ArrayList<Pair<String, String>>()
        lateinit var hub: Hub

        /** Moves players as asked, unless they're [refused]. */
        val refused = HashSet<String>()

        override fun servers() = names

        override fun players() = emptyList<PlayerState>()

        override fun send(id: String, server: String): CompletionStage<String?> {
            if (id in refused) return CompletableFuture.completedFuture("$server is full")
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
        var welcome: HubFrame.Welcome? = null
        val frames = ArrayList<HubFrame>()
        val failures = ArrayList<String>()

        override fun connected(welcome: HubFrame.Welcome) {
            this.welcome = welcome
        }

        override fun received(frame: HubFrame) {
            frames += frame
        }

        override fun disconnected(why: String) = Unit

        override fun failed(why: String) {
            failures += why
        }

        inline fun <reified T : HubFrame> of(): List<T> = frames.filterIsInstance<T>()
    }

    private val proxy = FakeProxy()
    private val hub = Hub("s3cret", proxy, Quiet, groups = mapOf("Arena" to GroupSettings(Balance.LEAST))).also { proxy.hub = it }

    private fun join(name: String, group: String? = null, capacity: Int? = null): Pair<LocalLink, Recorder> {
        val recorder = Recorder()
        val link = LocalLink(hub, name, "s3cret", recorder, group = group, capacity = capacity)
        link.start()
        return link to recorder
    }

    private fun place(vararg players: Pair<String, String>) {
        for ((id, server) in players) hub.playerMoved(id, id.uppercase(), server)
    }

    @Test
    fun `servers say their group and room, and everyone hears what changes`() {
        val (_, lobby) = join("lobby")
        join("arena-1", "arena", 2)
        assertEquals(ServerState("arena-1", true, "arena", 2, state = Lifecycle.READY), lobby.of<HubFrame.Server>().single().server)
        place("a" to "arena-1")
        hub.tick()
        val changed = lobby.of<HubFrame.Servers>().single().servers
        assertEquals(listOf(ServerState("arena-1", true, "arena", 2, players = 1, state = Lifecycle.READY)), changed)
        hub.tick()
        assertEquals(1, lobby.of<HubFrame.Servers>().size, "nothing changed")
        assertEquals("lobby", ServerState("lobby", true).groupName, "a server with no group is a group of one")
    }

    @Test
    fun `a send to a group goes to the instance with the fewest players that has room, or the fullest with fill`() {
        val (lobbyLink, lobby) = join("lobby")
        join("arena-1", "arena", 3)
        join("arena-2", "arena", 3)
        place("a" to "arena-1", "b" to "arena-1", "c" to "arena-2", "p" to "lobby", "q" to "lobby", "r" to "lobby")

        lobbyLink.send(ServerFrame.Send(1, listOf("p"), group = "arena"))
        assertEquals("arena-2", lobby.of<HubFrame.SendResult>().last().server, "least")
        lobbyLink.send(ServerFrame.Send(2, listOf("q"), group = "arena", balance = Balance.FILL))
        assertEquals("arena-1", lobby.of<HubFrame.SendResult>().last().server, "fill: the fullest with room")
        lobbyLink.send(ServerFrame.Send(3, listOf("r"), group = "arena", balance = Balance.FILL))
        assertEquals("arena-2", lobby.of<HubFrame.SendResult>().last().server, "arena-1 is full now")
        hub.playerMoved("s", "S", "lobby")
        lobbyLink.send(ServerFrame.Send(4, listOf("s"), group = "arena"))
        assertEquals("no instance of arena has room for them", lobby.of<HubFrame.SendResult>().last().error)
    }

    @Test
    fun `a party lands together on one instance with room for all of them`() {
        val (lobbyLink, lobby) = join("lobby")
        val (_, one) = join("arena-1", "arena", 4)
        join("arena-2", "arena", 4)
        place("a" to "arena-2", "b" to "arena-2", "p" to "lobby", "q" to "lobby", "r" to "lobby")
        lobbyLink.send(ServerFrame.Send(1, listOf("p", "q", "r"), group = "arena", data = JsonPrimitive("party")))
        val result = lobby.of<HubFrame.SendResult>().single()
        assertNull(result.error)
        assertEquals("arena-1", result.server)
        assertEquals(listOf("p", "q", "r"), one.of<HubFrame.Arriving>().map { it.player })
        assertEquals(listOf("p" to "arena-1", "q" to "arena-1", "r" to "arena-1"), proxy.sends)

        place("x" to "lobby", "y" to "lobby", "z" to "lobby")
        lobbyLink.send(ServerFrame.Send(2, listOf("x", "y", "z"), group = "arena"))
        assertEquals("no instance of arena has room for 3 players", lobby.of<HubFrame.SendResult>().last().error)
    }

    @Test
    fun `a party member the proxy couldn't move is named, and their arrival is called off`() {
        val (lobbyLink, lobby) = join("lobby")
        val (_, arena) = join("arena-1", "arena")
        place("p" to "lobby", "q" to "lobby")
        proxy.refused += "q"
        lobbyLink.send(ServerFrame.Send(1, listOf("p", "q"), group = "arena"))
        assertEquals("Q: arena-1 is full", lobby.of<HubFrame.SendResult>().single().error)
        assertEquals(listOf("q"), arena.of<HubFrame.ArrivalCancelled>().map { it.player })
    }

    @Test
    fun `instances that don't take players are passed over, and a group of one is a plain server`() {
        val (lobbyLink, lobby) = join("lobby")
        val (oneLink, _) = join("arena-1", "arena")
        place("p" to "lobby", "q" to "lobby")
        oneLink.send(ServerFrame.Status(accepting = false))
        lobbyLink.send(ServerFrame.Send(1, listOf("p"), group = "arena"))
        assertEquals("no instance of arena takes players now", lobby.of<HubFrame.SendResult>().last().error)
        oneLink.send(ServerFrame.Status(accepting = true))
        lobbyLink.send(ServerFrame.Send(2, listOf("p"), group = "arena"))
        assertEquals("arena-1", lobby.of<HubFrame.SendResult>().last().server)

        // legacy runs no NetherForge: it's still somewhere to send players by its name, as a group.
        lobbyLink.send(ServerFrame.Send(3, listOf("q"), group = "legacy"))
        assertEquals("legacy", lobby.of<HubFrame.SendResult>().last().server)
        lobbyLink.send(ServerFrame.Send(4, listOf("q"), group = "nowhere"))
        assertTrue(lobby.of<HubFrame.SendResult>().last().error!!.contains("no group or server named \"nowhere\""))
    }

    @Test
    fun `a message to a group reaches each instance listening, and a request goes to one, in turn`() {
        val (lobbyLink, lobby) = join("lobby")
        val (oneLink, one) = join("arena-1", "arena")
        val (twoLink, two) = join("arena-2", "arena")
        for (link in listOf(lobbyLink, oneLink, twoLink)) link.send(ServerFrame.Listen(listOf("arena:state"), emptyList()))
        lobbyLink.send(ServerFrame.Publish("arena:state", JsonPrimitive("go"), group = "arena"))
        assertEquals(1, one.of<HubFrame.Message>().size)
        assertEquals(1, two.of<HubFrame.Message>().size)
        assertEquals(0, lobby.of<HubFrame.Message>().size)

        lobbyLink.send(ServerFrame.Request(1, "arena:state", value = JsonPrimitive("?"), group = "arena"))
        lobbyLink.send(ServerFrame.Request(2, "arena:state", value = JsonPrimitive("?"), group = "arena"))
        assertEquals(1, one.of<HubFrame.Requested>().size)
        assertEquals(1, two.of<HubFrame.Requested>().size)
        lobbyLink.send(ServerFrame.Request(3, "arena:nothing", value = JsonPrimitive("?"), group = "arena"))
        assertEquals(
            "nothing on any instance of arena answers requests on \"arena:nothing\"",
            lobby.of<HubFrame.Reply>().single().error
        )
    }

    @Test
    fun `a group's credential puts its servers in the group, and refuses one that says another`() {
        val credential = Credential("arena", "arena-secret-0123456789", Grants.TRUSTED, group = "arena")
        hub.setCredentials(listOf(credential))
        val ok = Recorder()
        LocalLink(hub, "arena-1", credential.secret, ok, "arena").start()
        assertEquals("arena", hub.states().single { it.name == "arena-1" }.group)
        val wrong = Recorder()
        LocalLink(hub, "arena-2", credential.secret, wrong, "arena", group = "lobby").start()
        assertTrue(wrong.failures.single().contains("for the group arena"), wrong.failures.single())
    }
}
