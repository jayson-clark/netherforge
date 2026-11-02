package dev.netherforge.network

import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Network-wide bans, mutes and reports, servers and groups taken off the network, and the audit log. */
class ModerationTest {
    private class FakeProxy : HubProxy {
        val names = mutableListOf("lobby", "arena-1", "arena-2")
        val kicked = ArrayList<Pair<String, String>>()
        lateinit var hub: Hub

        override fun servers() = names

        override fun players() = emptyList<PlayerState>()

        override fun send(id: String, server: String): CompletionStage<String?> {
            hub.playerMoved(id, id, server)
            return CompletableFuture.completedFuture(null)
        }

        override fun kick(id: String, message: String): String? {
            kicked += id to message
            return null
        }
    }

    private object Quiet : HubLog {
        override fun info(message: String) = Unit

        override fun warn(message: String) = Unit
    }

    private class Recorder : HubListener {
        val frames = ArrayList<HubFrame>()
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

    private var time = 1_000_000L
    private val proxy = FakeProxy()
    private val events = ArrayList<HubEvent>()
    private val audit = ArrayList<String>()
    private val plain = Credential("plain", "plain-secret-0123456789", Grants.preset("standard", owns = listOf("arena")))
    private val mod = Credential("mod", "mod-secret-0123456789", Grants.preset("standard", moderation = true))
    private val hub = Hub(
        "s3cret",
        proxy,
        Quiet,
        clock = { time },
        credentials = listOf(plain, mod),
        events = { events += it },
        audit = { audit += it }
    ).also { proxy.hub = it }

    private fun join(name: String, credential: Credential? = null, group: String? = null): Pair<LocalLink, Recorder> {
        val recorder = Recorder()
        val link = LocalLink(hub, name, credential?.secret ?: "s3cret", recorder, credential?.id, group = group)
        link.start()
        return link to recorder
    }

    @Test
    fun `a ban keeps a player off the network and kicks them, and only a server allowed to moderate bans`() {
        val (plainLink, plainRecorder) = join("arena-1", plain)
        val (modLink, modRecorder) = join("lobby", mod)
        hub.playerMoved("u-1", "Ann", "lobby")
        plainLink.send(ServerFrame.Moderate(1, ModerationAction.BAN, "u-1", "Ann", "griefing", "Bob"))
        assertTrue(plainRecorder.of<HubFrame.ModerateResult>().single().error!!.startsWith("network.forbidden"))
        assertNull(hub.banOf("u-1"))

        modLink.send(ServerFrame.Moderate(2, ModerationAction.BAN, "u-1", "Ann", "griefing", "Bob", until = time + 60_000))
        assertNull(modRecorder.of<HubFrame.ModerateResult>().single().error)
        assertTrue(hub.banOf("u-1")!!.contains("griefing"))
        assertEquals("u-1", proxy.kicked.single().first)
        assertTrue(hub.placeLogin("u-1", "Ann").get(1, TimeUnit.SECONDS).deny!!.contains("banned"))
        assertTrue(audit.single { it.contains("\"ban\"") }.contains("Bob on lobby"))

        time += 61_000
        assertNull(hub.banOf("u-1"), "it ran out")
        assertNull(hub.placeLogin("u-1", "Ann").get(1, TimeUnit.SECONDS).deny)
    }

    @Test
    fun `every server hears mutes, and no server writes the hub's own maps`() {
        val (plainLink, plainRecorder) = join("arena-1", plain)
        plainLink.send(ServerFrame.Listen(emptyList(), listOf(Moderation.MUTES, Moderation.BANS)))
        assertEquals(2, plainRecorder.of<HubFrame.MapSnapshot>().size, "a standard server reads them")
        assertNull(hub.moderate(ModerationAction.MUTE, "u-2", "Bea", "spam", "the console", null))
        assertEquals(Moderation.MUTES, plainRecorder.of<HubFrame.MapChanged>().single().map)
        assertEquals(setOf("u-2"), hub.mutes().keys)

        val (trustedLink, trusted) = join("lobby")
        trustedLink.send(ServerFrame.MapWrite(9, Moderation.BANS, "u-3", MapOp.SET, JsonPrimitive("x")))
        assertTrue(trusted.of<HubFrame.MapResult>().single().error!!.startsWith("network.forbidden"), "not even a trusted server")
        assertEquals("u-2 wasn't banned".replace("u-2", "Bea"), hub.moderate(ModerationAction.UNBAN, "u-2", "Bea", null, "x", null))
    }

    @Test
    fun `a report is kept and heard by the webhook`() {
        val (link, _) = join("arena-1", plain)
        link.send(ServerFrame.Report("u-1", "Ann", "cheating", JsonPrimitive("flying"), by = "Bea"))
        val report = hub.reports().single()
        assertEquals("cheating", report.reason)
        assertEquals("arena-1", report.server)
        assertTrue(events.any { it is HubEvent.Reported })
    }

    @Test
    fun `a group taken off the network drains, sends its players away, and is refused until it's back`() {
        join("lobby")
        val (_, arena) = join("arena-1", group = "arena")
        hub.playerMoved("u-1", "Ann", "arena-1")
        assertNull(hub.disable("arena", "a cheat found", "the console"))
        assertEquals(listOf(HubFrame.Drain(true)), arena.of<HubFrame.Drain>())
        assertFalse("arena-1" in hub.linked())
        assertEquals("lobby", hub.adminPlayers().single().server, "sent to the fallback")
        val (_, again) = join("arena-1", group = "arena")
        assertTrue(again.failures.single().contains("taken off the network: a cheat found"), again.failures.toString())
        assertEquals(mapOf("arena" to "a cheat found"), hub.disabled())
        assertNull(hub.enable("arena", "the console"))
        val (back, _) = join("arena-1", group = "arena")
        assertTrue(back.connected)
        assertEquals(listOf("disable", "enable"), audit.map { Regex("\"action\":\"(\\w+)\"").find(it)!!.groupValues[1] })
    }

    @Test
    fun `revoking credentials is in the audit log`() {
        hub.setCredentials(listOf(mod))
        assertNotNull(audit.singleOrNull { it.contains("credentials") && it.contains("revoked [plain]") }, audit.toString())
    }
}
