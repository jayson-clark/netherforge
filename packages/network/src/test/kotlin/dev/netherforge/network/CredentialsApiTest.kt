package dev.netherforge.network

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Credentials put and removed through the admin API: usable at once, kept in the store, never told back. */
class CredentialsApiTest {
    private class FakeProxy : HubProxy {
        val added = LinkedHashMap<String, Pair<String, Int>>()

        override fun servers() = listOf("lobby") + added.keys

        override fun players() = emptyList<PlayerState>()

        override fun send(id: String, server: String): CompletionStage<String?> = CompletableFuture.completedFuture(null)

        override fun register(name: String, host: String, port: Int): String? {
            added[name] = host to port
            return null
        }

        override fun unregister(name: String) {
            added.remove(name)
        }

        override fun isFixed(name: String) = name == "lobby"
    }

    private class Logged : HubLog {
        val lines = java.util.concurrent.CopyOnWriteArrayList<String>()

        override fun info(message: String) {
            lines += message
        }

        override fun warn(message: String) {
            lines += message
        }
    }

    /** A store in memory, standing for the file or PostgreSQL: what a hub that starts again reads. */
    private class MemoryStore : MapStore {
        @Volatile
        var saved: SavedMaps? = null

        override fun load() = saved

        override fun save(maps: SavedMaps) {
            saved = maps
        }
    }

    private class Recorder : HubListener {
        val failures = java.util.concurrent.CopyOnWriteArrayList<String>()

        @Volatile
        var disconnects = 0

        override fun connected(welcome: HubFrame.Welcome) = Unit

        override fun received(frame: HubFrame) = Unit

        override fun disconnected(why: String) {
            disconnects++
        }

        override fun failed(why: String) {
            failures += why
        }
    }

    private val secret = "made-on-demand-secret-0123456789"
    private val store = MemoryStore()
    private val log = Logged()
    private val audit = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val proxy = FakeProxy()
    private val fileShares = mapOf("standard" to listOf(Share.parse("lobby:return(request)")))
    private var hub = hub()
    private val ops = AdminClient("ops", "ops-token-long-enough-0123456789", setOf(AdminScope.CREDENTIALS, AdminScope.SERVERS_WRITE))
    private val star = AdminClient("star", "star-token-long-enough-0123456789", AdminScope.STAR)
    private var admin = admin()
    private val http = HttpClient.newHttpClient()

    private fun hub() = Hub("s3cret", proxy, log, store = store, roleShares = fileShares, audit = { audit += it })

    private fun admin() = HubAdmin(hub, InetSocketAddress(InetAddress.getLoopbackAddress(), 0), {
        listOf(ops, star)
    }, log).also { it.start() }

    @AfterTest
    fun stop() = admin.stop()

    private fun call(method: String, path: String, body: String? = null, client: AdminClient = ops): Pair<Int, String> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${admin.port}$path"))
            .header("Authorization", "Bearer ${client.token}")
            .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to response.body()
    }

    private fun join(name: String, credential: String, secret: String = this.secret): Pair<LocalLink, Recorder> {
        val recorder = Recorder()
        val link = LocalLink(hub, name, secret, recorder, credential)
        link.start()
        return link to recorder
    }

    /** Everything a secret could have leaked into: the log, the audit log, what's listed. */
    private fun assertNoSecret(vararg also: String) {
        for (text in log.lines + audit + also) assertFalse(secret in text, "a secret leaked: $text")
    }

    @Test
    fun `a credential put through the API lets a server be added and connect at once`() {
        val started = System.nanoTime()
        assertEquals(204, call("PUT", "/v1/credentials/arena-9", """{ "secret": "$secret", "role": "standard" }""").first)
        val added = call("POST", "/v1/servers", """{ "name": "arena-9", "address": "10.0.0.9:25601", "credential": "arena-9" }""")
        assertEquals(201, added.first, added.second)
        val (link, recorder) = join("arena-9", "arena-9")
        assertTrue(link.connected, recorder.failures.toString())
        assertTrue(System.nanoTime() - started < 1_000_000_000L, "within a second")
        // Its role's shares apply, as the file's would.
        assertEquals(Share.parse("lobby:return(request)"), hub.connections().single().grants.shares.single())

        val listed = call("GET", "/v1/credentials").second
        assertEquals("""{"credentials":[{"id":"arena-9","role":"standard","source":"api"}]}""", listed)
        assertTrue(audit.any { it.contains("added [arena-9]") && it.contains("the admin client ops") }, audit.toString())
        assertNoSecret(listed)
    }

    @Test
    fun `removing it, or putting a new secret, closes its connections`() {
        call("PUT", "/v1/credentials/arena-9", """{ "secret": "$secret" }""")
        val (first, firstRecorder) = join("lobby", "arena-9")
        assertTrue(first.connected)

        val replaced = "a-replacing-secret-0123456789"
        assertEquals(204, call("PUT", "/v1/credentials/arena-9", """{ "secret": "$replaced", "role": "isolated" }""").first)
        assertFalse(first.connected, "the old secret's connection closed")
        assertEquals(1, firstRecorder.disconnects)
        assertFalse(join("lobby", "arena-9").first.connected, "the old secret no longer joins")
        val (second, _) = join("lobby", "arena-9", replaced)
        assertTrue(second.connected)
        assertEquals("isolated", hub.connections().single().grants.role)

        assertEquals(204, call("DELETE", "/v1/credentials/arena-9").first)
        assertFalse(second.connected)
        assertEquals(404, call("DELETE", "/v1/credentials/arena-9").first)
        assertEquals("""{"credentials":[]}""", call("GET", "/v1/credentials").second)
        assertNull(store.saved!!.credentials["arena-9"], "the store forgot it")
    }

    @Test
    fun `it's still there when the hub starts again on the same store`() {
        call("PUT", "/v1/credentials/arena-9", """{ "secret": "$secret", "owns": ["arena"], "group": "arena" }""")
        admin.stop()
        hub.close()
        hub = hub()
        admin = admin()
        val (link, recorder) = join("lobby", "arena-9")
        assertTrue(link.connected, recorder.failures.toString())
        assertEquals("arena", hub.states().single { it.name == "lobby" }.group)
        assertTrue(hub.connections().single().grants.allows("arena:scores", Access.WRITE))
        assertTrue(call("GET", "/v1/credentials").second.contains("\"source\":\"api\""))
    }

    @Test
    fun `a reload of the file leaves the API's in place, and an id in both is the API's`() {
        call("PUT", "/v1/credentials/arena-9", """{ "secret": "$secret" }""")
        val (link, _) = join("lobby", "arena-9")
        val fileOnly = Credential("lobby", "lobby-file-secret-0123456789", Grants.preset("trusted"))
        val both = Credential("arena-9", "file-secret-for-arena-0123456789", Grants.preset("trusted"))
        hub.setCredentials(listOf(fileOnly, both))
        assertTrue(link.connected, "the API's credential won, so nothing changed for it")
        assertEquals(
            """{"credentials":[{"id":"arena-9","role":"standard","source":"api"},{"id":"lobby","role":"trusted","source":"file"}]}""",
            call("GET", "/v1/credentials").second
        )
        hub.setCredentials(emptyList())
        assertTrue(link.connected)
        assertEquals(404, call("DELETE", "/v1/credentials/lobby").first, "the file's isn't the API's to remove")

        // Removed from the API, the file's is the one left: its server dials again under it.
        hub.setCredentials(listOf(both))
        call("DELETE", "/v1/credentials/arena-9")
        assertFalse(link.connected)
        assertTrue(join("lobby", "arena-9", both.secret).first.connected)
    }

    @Test
    fun `only a client with the credentials scope may call, and star doesn't give it`() {
        assertFalse(AdminScope.CREDENTIALS in AdminScope.STAR)
        assertEquals(403, call("GET", "/v1/credentials", client = star).first)
        assertEquals(403, call("PUT", "/v1/credentials/x", """{ "secret": "$secret" }""", client = star).first)
        assertEquals(403, call("DELETE", "/v1/credentials/x", client = star).first)
        assertEquals("""{"credentials":[]}""", call("GET", "/v1/credentials").second)

        val file = AdminClientsFile(kotlin.io.path.createTempDirectory("hub").resolve(AdminClientsFile.FILE))
        java.nio.file.Files.writeString(
            file.file,
            "a.token=a-token-long-enough-0123456789\na.scopes=*\nb.token=b-token-long-enough-0123456789\nb.scopes=*, credentials\n"
        )
        val (a, b) = file.read()
        assertEquals(AdminScope.STAR, a.scopes)
        assertEquals(AdminScope.entries.toSet(), b.scopes)
    }

    @Test
    fun `what the file refuses, the API refuses, without saying the secret`() {
        for (body in listOf(
            """{ "secret": "short" }""",
            """{ "secret": "$secret", "role": "admin" }""",
            """{ "secret": "$secret", "owns": ["Not A Namespace"] }""",
            """{ "secret": "$secret", "shares": ["lobby:return(steal)"] }""",
            """{ "secret": "$secret", "addresses": ["nowhere"] }""",
            """{ "secret": "$secret", "role": 5, "x": """
        )) {
            val (status, answer) = call("PUT", "/v1/credentials/arena-9", body)
            assertEquals(400, status, "$body: $answer")
            assertNoSecret(answer)
        }
        assertEquals(400, call("PUT", "/v1/credentials/bad%20id", """{ "secret": "$secret" }""").first)
        assertEquals("""{"credentials":[]}""", call("GET", "/v1/credentials").second)
        assertNull(store.saved)
    }
}
