package dev.netherforge.proxy

import dev.netherforge.network.Balance
import dev.netherforge.network.GroupSettings
import dev.netherforge.network.NetworkLimits
import dev.netherforge.network.Protocol
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HubSettingsTest {
    private val folder = createTempDirectory("hub")
    private val file = folder.resolve("netherforge").resolve(HubSettings.FILE)

    @Test
    fun `the first start writes the settings with a new secret, and the next reads them back`() {
        val said = ArrayList<String>()
        val first = HubSettings.read(file) { said += it }
        assertTrue(Files.exists(file))
        assertEquals(64, first.secret!!.length)
        assertEquals("0.0.0.0", first.bind)
        assertEquals(Protocol.DEFAULT_PORT, first.port)
        assertEquals(NetworkLimits(), first.limits)
        assertTrue(said.single().contains("new secret"))
        val again = HubSettings.read(file) { said += it }
        assertEquals(first, again)
        assertEquals(1, said.size, "written once")
        assertTrue(file.readText().contains("secret=${first.secret}"))
    }

    @Test
    fun `values the owner sets are read, and ones that aren't numbers are the defaults`() {
        Files.createDirectories(file.parent)
        file.writeText(
            """
            bind=10.0.0.2
            port=26000
            secret=0123456789abcdef0123
            max-value-bytes=1024
            max-keys-per-package=nope
            request-timeout-seconds=5
            """.trimIndent()
        )
        val settings = HubSettings.read(file) {}
        assertEquals("10.0.0.2", settings.bind)
        assertEquals(26000, settings.port)
        assertEquals(NetworkLimits(maxValueBytes = 1024, requestTimeoutMillis = 5000), settings.limits)
    }

    @Test
    fun `a short secret is refused, and none means only credentials join`() {
        Files.createDirectories(file.parent)
        file.writeText("port=26000\n")
        assertEquals(null, HubSettings.read(file) {}.secret)
        file.writeText("secret=short\n")
        assertTrue(assertFailsWith<IllegalArgumentException> { HubSettings.read(file) {} }.message!!.contains("shorter than 16"))
    }

    @Test
    fun `what's shared with each role is read, and a share that isn't one says so`() {
        Files.createDirectories(file.parent)
        file.writeText("share.standard=lobby:return(request), events:*(read)\nshare.isolated=\n")
        val settings = HubSettings.read(file) {}
        assertEquals(listOf("lobby:return(request)", "events:*(read)"), settings.roleShares["standard"]!!.map { it.toString() })
        assertEquals(emptyList(), settings.roleShares["isolated"])
        file.writeText("share.standard=lobby(read)\n")
        assertTrue(assertFailsWith<IllegalArgumentException> { HubSettings.read(file) {} }.message!!.contains("share.standard"))
    }

    @Test
    fun `each group's balancing is read`() {
        Files.createDirectories(file.parent)
        file.writeText("group.arena.balance=fill\ngroup.lobby.balance=least\n")
        val groups = HubSettings.read(file) {}.groups
        assertEquals(mapOf("arena" to GroupSettings(Balance.FILL), "lobby" to GroupSettings(Balance.LEAST)), groups)
        file.writeText("group.arena.balance=random\n")
        assertTrue(assertFailsWith<IllegalArgumentException> { HubSettings.read(file) {} }.message!!.contains("isn't least or fill"))
    }

    @Test
    fun `a group's fallback and queue, and the webhook, are read`() {
        Files.createDirectories(file.parent)
        file.writeText("group.arena.fallback=hub\ngroup.arena.max-waiting=20\nwebhook.url=https://ops.example.com/nf\nwebhook.secret=s\n")
        val settings = HubSettings.read(file) {}
        assertEquals(GroupSettings(fallback = "hub", maxWaiting = 20), settings.groups["arena"])
        assertEquals("https://ops.example.com/nf", settings.webhookUrl.toString())
        file.writeText("webhook.url=ftp://x\n")
        assertTrue(assertFailsWith<IllegalArgumentException> { HubSettings.read(file) {} }.message!!.contains("isn't http(s)"))
    }
}
