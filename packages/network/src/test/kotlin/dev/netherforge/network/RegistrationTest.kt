package dev.netherforge.network

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Servers adding themselves to the proxy as they join, and coming off it as they go. */
class RegistrationTest {
    /** A proxy with the fixed servers of its config, and those added while it runs. */
    private class FakeProxy : HubProxy {
        val fixed = listOf("lobby")
        val added = LinkedHashMap<String, Pair<String, Int>>()

        override fun servers() = fixed + added.keys

        override fun players() = emptyList<PlayerState>()

        override fun send(id: String, server: String): CompletionStage<String?> = CompletableFuture.completedFuture(null)

        override fun register(name: String, host: String, port: Int): String? {
            added[name] = host to port
            return null
        }

        override fun unregister(name: String) {
            added.remove(name)
        }

        override fun isFixed(name: String) = name in fixed
    }

    private object Quiet : HubLog {
        override fun info(message: String) = Unit

        override fun warn(message: String) = Unit
    }

    private class Recorder : HubListener {
        var welcome: HubFrame.Welcome? = null
        val failures = ArrayList<String>()
        var disconnects = 0

        override fun connected(welcome: HubFrame.Welcome) {
            this.welcome = welcome
        }

        override fun received(frame: HubFrame) = Unit

        override fun disconnected(why: String) {
            disconnects++
        }

        override fun failed(why: String) {
            failures += why
        }
    }

    private var time = 1_000L
    private val proxy = FakeProxy()
    private val arena = Credential(
        "arena",
        "arena-secret-0123456789",
        Grants.preset("standard", owns = listOf("arena")),
        group = "arena",
        register = RegisterRule(names = listOf("arena-*"), addresses = listOf("10.0.0.0/8"))
    )
    private val plain = Credential("plain", "plain-secret-0123456789", Grants.preset("standard"))
    private val hub = Hub(
        "s3cret",
        proxy,
        Quiet,
        NetworkLimits(unregisterAfterMillis = 30_000),
        clock = { time },
        credentials = listOf(arena, plain)
    )

    private fun register(
        name: String,
        host: String? = "10.0.0.7",
        port: Int = 25601,
        credential: Credential? = arena,
        instance: String = Auth.newNonce()
    ): Triple<LocalLink, Recorder, String> {
        val recorder = Recorder()
        val link = LocalLink(
            hub,
            name,
            credential?.secret ?: "s3cret",
            recorder,
            credential?.id,
            register = Registration(host, port),
            instance = instance
        )
        link.start()
        return Triple(link, recorder, instance)
    }

    @Test
    fun `a server adds itself to the proxy as it joins, and comes off as it shuts down`() {
        val (link, recorder, _) = register("arena-7")
        assertTrue(link.connected, recorder.failures.toString())
        assertEquals("arena-7", recorder.welcome!!.server)
        assertEquals("10.0.0.7" to 25601, proxy.added["arena-7"])
        assertEquals("arena", hub.states().single { it.name == "arena-7" }.group)
        link.send(ServerFrame.Leaving)
        link.stop()
        assertEquals(emptyMap(), proxy.added)
        assertEquals(emptyList(), hub.registered())
    }

    @Test
    fun `automatic names are the group's, numbered, and a server dialling again keeps its own`() {
        val (one, first, instance) = register(Protocol.AUTO_NAME)
        val (_, second, _) = register(Protocol.AUTO_NAME)
        assertEquals("arena-1", first.welcome!!.server)
        assertEquals("arena-2", second.welcome!!.server)
        one.stop()
        assertEquals(setOf("arena-1", "arena-2"), proxy.added.keys, "a dropped server keeps its place for a while")
        val (_, again, _) = register(Protocol.AUTO_NAME, instance = instance)
        assertEquals("arena-1", again.welcome!!.server)
        val (_, third, _) = register(Protocol.AUTO_NAME)
        assertEquals("arena-3", third.welcome!!.server)
    }

    @Test
    fun `a server gone without saying so comes off the proxy after a while, unless it's back`() {
        val (link, _, instance) = register("arena-1")
        link.stop()
        time += 20_000
        hub.tick()
        assertTrue("arena-1" in proxy.added)
        val (back, _, _) = register("arena-1", instance = instance)
        assertTrue(back.connected)
        time += 40_000
        hub.tick()
        assertTrue("arena-1" in proxy.added, "it came back")
        back.stop()
        time += 30_000
        hub.tick()
        assertFalse("arena-1" in proxy.added)
    }

    @Test
    fun `a registration the credential doesn't allow, or a name taken, is refused`() {
        val (_, noRight, _) = register("arena-1", credential = plain)
        assertTrue(noRight.failures.single().contains("may not register servers"), noRight.failures.single())
        val (_, wrongName, _) = register("lobby-2")
        assertTrue(wrongName.failures.single().contains("may not register the name lobby-2"), wrongName.failures.single())
        val (_, wrongAddress, _) = register("arena-1", host = "192.168.1.4")
        assertTrue(wrongAddress.failures.single().contains("may not register the address 192.168.1.4"), wrongAddress.failures.single())
        val (_, aName, _) = register("arena-1", host = "arena.internal")
        assertTrue(aName.failures.single().contains("address"), "a name is never looked up against the ranges")
        val (_, fixed, _) = register("lobby", credential = null)
        assertTrue(fixed.failures.single().contains("one of the proxy's own servers"), fixed.failures.single())

        val (taken, _, _) = register("arena-1")
        assertTrue(taken.connected)
        val (_, clash, _) = register("arena-1")
        assertTrue(clash.failures.single().contains("another server registered as arena-1 is connected"), clash.failures.single())
        assertTrue(taken.connected, "the first keeps it")
        assertEquals(listOf("arena-1"), proxy.added.keys.toList())
    }

    @Test
    fun `a server that comes back at a new address is moved there`() {
        val (link, _, instance) = register("arena-1")
        link.stop()
        register("arena-1", host = "10.0.0.9", instance = instance)
        assertEquals("10.0.0.9" to 25601, proxy.added["arena-1"])
        assertNull(hub.registered().single().goneSince)
    }

    @Test
    fun `ranges match addresses byte by byte`() {
        val range = Cidr.parse("10.1.0.0/16")!!
        assertTrue(range.contains(Cidr.literal("10.1.200.3")!!))
        assertFalse(range.contains(Cidr.literal("10.2.0.1")!!))
        assertTrue(Cidr.parse("fd00::/8")!!.contains(Cidr.literal("fd12::5")!!))
        assertFalse(Cidr.parse("fd00::/8")!!.contains(Cidr.literal("10.0.0.1")!!))
        assertNull(Cidr.literal("example.com"))
        assertNull(Cidr.parse("10.0.0.0/40"))
    }

    @Test
    fun `the admin API adds and removes servers, and only the named credential joins as one`() {
        val token = "an-admin-token-long-enough-1234"
        val admin = HubAdmin(hub, java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), token, Quiet)
        admin.start()
        try {
            val client = java.net.http.HttpClient.newHttpClient()
            fun call(method: String, path: String, body: String? = null, auth: String = token): Pair<Int, String> {
                val request = java.net.http.HttpRequest.newBuilder(java.net.URI("http://127.0.0.1:${admin.port}$path"))
                    .header("Authorization", "Bearer $auth")
                    .method(
                        method,
                        body?.let { java.net.http.HttpRequest.BodyPublishers.ofString(it) }
                            ?: java.net.http.HttpRequest.BodyPublishers.noBody()
                    )
                    .build()
                val response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
                return response.statusCode() to response.body()
            }
            assertEquals(401, call("GET", "/v1/servers", auth = "guess").first)
            val added =
                call(
                    "POST",
                    "/v1/servers",
                    """{ "name": "arena-9", "address": "10.0.0.9:25601", "group": "arena", "credential": "plain" }"""
                )
            assertEquals(201, added.first, added.second)
            assertEquals("10.0.0.9" to 25601, proxy.added["arena-9"])
            assertEquals(409, call("POST", "/v1/servers", """{ "name": "lobby", "address": "10.0.0.9:25601" }""").first)
            assertEquals(400, call("POST", "/v1/servers", """{ "name": "x", "address": "nowhere" }""").first)

            // Only the credential it was added for joins as it, and it's put in the group it was added in.
            val wrong = Recorder()
            LocalLink(hub, "arena-9", arena.secret, wrong, arena.id).start()
            assertTrue(wrong.failures.single().contains("added for the credential \"plain\""), wrong.failures.single())
            val right = Recorder()
            val link = LocalLink(hub, "arena-9", plain.secret, right, plain.id)
            link.start()
            assertTrue(link.connected, right.failures.toString())
            assertEquals("arena", hub.states().single { it.name == "arena-9" }.group)

            // It doesn't come off the proxy when it drops: the orchestrator says when.
            link.stop()
            time += 120_000
            hub.tick()
            assertTrue("arena-9" in proxy.added)
            val listed = call("GET", "/v1/servers").second
            assertTrue(listed.contains("\"name\":\"arena-9\"") && listed.contains("\"source\":\"admin\""), listed)

            assertEquals(200, call("DELETE", "/v1/servers/arena-9").first)
            assertFalse("arena-9" in proxy.added)
            assertEquals(404, call("DELETE", "/v1/servers/arena-9").first)
            assertEquals(404, call("DELETE", "/v1/servers/lobby").first)
        } finally {
            admin.stop()
        }
    }
}
