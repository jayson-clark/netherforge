package dev.netherforge.network

import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Credentials and their grants: who may join, and what the hub lets each do. */
class CredentialsTest {
    private class FakeProxy : HubProxy {
        val names = mutableListOf("lobby", "arena-1", "arena-2", "mini")
        val sends = ArrayList<Pair<String, String>>()

        override fun servers() = names

        override fun players() = emptyList<PlayerState>()

        override fun send(id: String, server: String): CompletionStage<String?> {
            sends += id to server
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

    private val shared = listOf(Share.parse("lobby:return(request)"), Share.parse("events:*(read)"))
    private val arena = Credential("arena", "arena-secret-0123456789", Grants.preset("standard", owns = listOf("arena"), shared = shared))
    private val mini =
        Credential("mini", "mini-secret-0123456789", Grants.preset("isolated", shared = listOf(Share.parse("lobby:return(request)"))))
    private val proxy = FakeProxy()
    private val hub = Hub("s3cret", proxy, Quiet, credentials = listOf(arena, mini))

    private fun join(name: String, secret: String = "s3cret", credential: String? = null): Pair<LocalLink, Recorder> {
        val recorder = Recorder()
        val link = LocalLink(hub, name, secret, recorder, credential)
        link.start()
        return link to recorder
    }

    private fun joinArena(name: String = "arena-1") = join(name, arena.secret, "arena")

    @Test
    fun `shares parse as the hub's config spells them`() {
        assertEquals(Share("lobby:return", setOf(Access.REQUEST)), Share.parse("lobby:return(request)"))
        assertEquals(Share("events:*", setOf(Access.READ)), Share.parse("events:*"))
        assertEquals(setOf(Access.READ, Access.WRITE), Share.parse("a:b(read+write)").access)
        assertEquals(
            listOf("lobby:return(request)", "events:announcements(read)"),
            Share.parseList("lobby:return(request), events:announcements(read)").map { it.toString() }
        )
        assertFailsWith<IllegalArgumentException> { Share.parse("lobby:return(steal)") }
        assertFailsWith<IllegalArgumentException> { Share.parse("noname(read)") }
        assertFailsWith<IllegalArgumentException> { Share.parse("a:b(read") }
    }

    @Test
    fun `the credentials file reads back what it wrote, with the role's shares added`() {
        val file = CredentialsFile(kotlin.io.path.createTempDirectory("hub").resolve(CredentialsFile.FILE))
        assertEquals(emptyList(), file.read())
        val entries = listOf(
            CredentialEntry("mini", "mini-secret-0123456789", "isolated", presence = true, sendTo = listOf("lobby", "back")),
            CredentialEntry(
                "arena",
                "arena-secret-0123456789",
                "standard",
                owns = listOf("arena"),
                shares = listOf(Share.parse("shop:prices(request)"))
            )
        )
        file.write(entries)
        assertEquals(entries.sortedBy { it.id }, file.read())
        val credential = file.read().first().credential(mapOf("standard" to shared))
        assertEquals(shared + Share.parse("shop:prices(request)"), credential.grants.shares)
        val mini = file.read().last().credential(emptyMap()).grants
        assertTrue(mini.presence)
        assertEquals(listOf("lobby", "back"), mini.sendTo)

        java.nio.file.Files.writeString(file.file, "x.secret=short\n")
        assertTrue(assertFailsWith<IllegalArgumentException> { file.read() }.message!!.contains("shorter"))
        java.nio.file.Files.writeString(file.file, "x.secret=long-enough-secret-123\nx.role=admin\n")
        assertTrue(assertFailsWith<IllegalArgumentException> { file.read() }.message!!.contains("admin"))
    }

    @Test
    fun `grants allow owned namespaces whole and shares as given`() {
        val grants = arena.grants
        for (access in Access.entries) assertTrue(grants.allows("arena:scores", access))
        assertTrue(grants.allows("lobby:return", Access.REQUEST))
        assertFalse(grants.allows("lobby:return", Access.READ))
        assertTrue(grants.allows("events:announcements", Access.READ))
        assertFalse(grants.allows("events:announcements", Access.WRITE))
        assertFalse(grants.allows("shop:sales", Access.READ))
        assertTrue(Grants(role = "x", shares = listOf(Share.parse("a:b(write)"))).allows("a:b", Access.READ), "write comes with read")
        assertTrue(Grants.TRUSTED.allows("shop:sales", Access.WRITE))
    }

    @Test
    fun `a server joins with its credential and is told what it may do`() {
        val (link, recorder) = joinArena()
        assertTrue(link.connected)
        assertEquals(arena.grants, recorder.welcome?.grants)
        assertEquals("arena", hub.connections().single().credential)
        val (shared, sharedRecorder) = join("lobby")
        assertTrue(shared.connected)
        assertEquals(Grants.TRUSTED, sharedRecorder.welcome?.grants, "the shared secret is trusted")
    }

    @Test
    fun `an unknown credential, a wrong secret for one, or a hub with no shared secret refuses the hello`() {
        val (unknown, one) = join("arena-1", arena.secret, "nobody")
        assertFalse(unknown.connected)
        assertTrue(one.failures.single().contains("doesn't have"), one.failures.single())
        val (wrong, two) = join("arena-1", "s3cret", "arena")
        assertFalse(wrong.connected)
        assertTrue(two.failures.single().contains("credential \"arena\""), two.failures.single())

        val noShared = Hub(null, proxy, Quiet, credentials = listOf(arena))
        val recorder = Recorder()
        LocalLink(noShared, "lobby", "s3cret", recorder).start()
        assertTrue(recorder.failures.single().contains("has none"), recorder.failures.single())
        val ok = Recorder()
        LocalLink(noShared, "arena-1", arena.secret, ok, "arena").start()
        assertNotNull(ok.welcome)
    }

    @Test
    fun `a revoked credential's servers are dropped at once, and a changed one's dial again`() {
        val (link, recorder) = joinArena()
        val (mini, miniRecorder) = join("mini", this.mini.secret, "mini")
        hub.setCredentials(listOf(arena))
        assertFalse(mini.connected)
        assertEquals(1, miniRecorder.disconnects)
        assertTrue(link.connected, "unchanged")
        hub.setCredentials(listOf(arena.copy(grants = Grants.preset("standard"))))
        assertFalse(link.connected)
        assertEquals(1, recorder.disconnects)
        val (again, _) = joinArena()
        assertTrue(again.connected)
    }

    @Test
    fun `publishing, listening and writing hold to the grants`() {
        val (lobbyLink, lobby) = join("lobby")
        val (arenaLink, arenaRecorder) = joinArena()
        lobbyLink.send(ServerFrame.Listen(listOf("lobby:chat", "arena:scores", "events:news"), emptyList()))
        arenaLink.send(ServerFrame.Listen(listOf("lobby:chat", "arena:scores", "events:news"), listOf("lobby:parties")))
        assertTrue(
            arenaRecorder.of<HubFrame.Problem>().single().message.let {
                it.contains("forbidden") &&
                    it.contains("lobby:chat") &&
                    it.contains("lobby:parties")
            }
        )
        assertTrue(arenaRecorder.of<HubFrame.MapSnapshot>().isEmpty())

        lobbyLink.send(ServerFrame.Publish("lobby:chat", JsonPrimitive("hi")))
        lobbyLink.send(ServerFrame.Publish("events:news", JsonPrimitive("news")))
        assertEquals(listOf("events:news"), arenaRecorder.of<HubFrame.Message>().map { it.channel }, "only what it may read")

        arenaRecorder.frames.clear()
        arenaLink.send(ServerFrame.Publish("lobby:chat", JsonPrimitive("spam")))
        arenaLink.send(ServerFrame.Publish("events:news", JsonPrimitive("fake")))
        arenaLink.send(ServerFrame.Publish("arena:scores", JsonPrimitive(3)))
        assertEquals(2, arenaRecorder.of<HubFrame.Problem>().size)
        assertEquals(
            listOf(JsonPrimitive("hi"), JsonPrimitive("news"), JsonPrimitive(3)),
            lobby.of<HubFrame.Message>().map { it.value },
            "the lobby heard its own two and the arena's score, not what the arena may not publish"
        )

        arenaLink.send(ServerFrame.MapWrite(1, "lobby:parties", "k", MapOp.SET, JsonPrimitive(1)))
        arenaLink.send(ServerFrame.MapWrite(2, "arena:state", "k", MapOp.SET, JsonPrimitive(1)))
        val results = arenaRecorder.of<HubFrame.MapResult>().associateBy { it.id }
        assertTrue(results.getValue(1).error!!.startsWith("network.forbidden"))
        assertNull(results.getValue(2).error)
    }

    @Test
    fun `requests need request on the asker's side and answer on the answerer's`() {
        val (lobbyLink, lobby) = join("lobby")
        val (arenaLink, arenaRecorder) = joinArena()
        lobbyLink.send(ServerFrame.Listen(listOf("lobby:return", "lobby:secrets"), emptyList()))
        arenaLink.send(ServerFrame.Request(1, "lobby:return", "lobby", JsonPrimitive("Ann")))
        val asked = lobby.of<HubFrame.Requested>().single()
        lobbyLink.send(ServerFrame.Reply(asked.id, JsonPrimitive("ok")))
        assertEquals(JsonPrimitive("ok"), arenaRecorder.of<HubFrame.Reply>().single().value)

        arenaLink.send(ServerFrame.Request(2, "lobby:secrets", "lobby", JsonPrimitive("?")))
        assertTrue(arenaRecorder.of<HubFrame.Reply>().last().error!!.startsWith("network.forbidden"))
        assertEquals(1, lobby.of<HubFrame.Requested>().size, "never reached the lobby")

        // The arena may ask on lobby:return, not answer it: listening there doesn't make it asked.
        arenaLink.send(ServerFrame.Listen(listOf("lobby:return"), emptyList()))
        lobbyLink.send(ServerFrame.Request(3, "lobby:return", "arena-1", JsonPrimitive("?")))
        assertTrue(lobby.of<HubFrame.Reply>().single().error!!.contains("nothing on arena-1 answers"))
    }

    @Test
    fun `a server without presence sees only the players on it`() {
        hub.playerMoved("u-1", "Ann", "lobby")
        val (_, miniRecorder) = join("mini", mini.secret, "mini")
        val (_, arenaRecorder) = joinArena()
        assertEquals(emptyList(), miniRecorder.welcome!!.players)
        assertEquals(listOf("Ann"), arenaRecorder.welcome!!.players.map { it.name })

        hub.playerMoved("u-2", "Bea", "lobby")
        hub.playerMoved("u-1", "Ann", "mini")
        hub.playerMoved("u-1", "Ann", "lobby")
        hub.playerLeft("u-2")
        assertEquals(
            listOf<HubFrame>(HubFrame.PlayerJoined(PlayerState("u-1", "Ann", "mini")), HubFrame.PlayerLeft("u-1")),
            miniRecorder.frames.filter { it is HubFrame.PlayerJoined || it is HubFrame.PlayerLeft || it is HubFrame.PlayerMoved }
        )
        assertEquals(
            4,
            arenaRecorder.frames.count {
                it is HubFrame.PlayerJoined || it is HubFrame.PlayerLeft || it is HubFrame.PlayerMoved
            }
        )
    }

    @Test
    fun `a less trusted server sends only its own players, and only where it may`() {
        val (lobbyLink, lobby) = join("lobby")
        val (arenaLink, arenaRecorder) = joinArena()
        val (miniLink, miniRecorder) = join("mini", mini.secret, "mini")
        hub.playerMoved("u-1", "Ann", "lobby")
        hub.playerMoved("u-2", "Bea", "arena-1")

        arenaLink.send(ServerFrame.Send(1, "u-1", "arena-1"))
        assertTrue(arenaRecorder.of<HubFrame.SendResult>().single().error!!.contains("only send players who are on arena-1"))
        arenaLink.send(ServerFrame.Send(2, "u-2", "lobby"))
        assertNull(arenaRecorder.of<HubFrame.SendResult>().last().error, "standard sends anywhere")
        hub.playerMoved("u-2", "Bea", "lobby")

        lobbyLink.send(ServerFrame.Send(3, "u-1", "mini"))
        assertNull(lobby.of<HubFrame.SendResult>().single().error, "trusted sends anyone")
        hub.playerMoved("u-1", "Ann", "mini")
        miniLink.send(ServerFrame.Send(4, "u-1", "arena-2"))
        assertTrue(miniRecorder.of<HubFrame.SendResult>().single().error!!.contains("may not send players to arena-2"))
        miniLink.send(ServerFrame.Send(5, "u-1", "lobby"))
        assertNull(miniRecorder.of<HubFrame.SendResult>().last().error, "isolated sends back where they came from")
        assertEquals(listOf("u-2" to "lobby", "u-1" to "mini", "u-1" to "lobby"), proxy.sends)
    }
}
