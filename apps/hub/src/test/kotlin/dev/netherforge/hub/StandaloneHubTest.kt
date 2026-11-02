package dev.netherforge.hub

import dev.netherforge.network.Auth
import dev.netherforge.network.HubClient
import dev.netherforge.network.HubFrame
import dev.netherforge.network.HubListener
import dev.netherforge.network.HubLog
import dev.netherforge.network.ServerFrame
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The hub on its own: its files, a proxy joining it over TCP, and a server joining through it. */
class StandaloneHubTest {
    private object Quiet : HubLog {
        override fun info(message: String) = Unit

        override fun warn(message: String) = Unit
    }

    private class Events : HubListener {
        val welcomes = LinkedBlockingQueue<HubFrame.Welcome>()
        val failures = LinkedBlockingQueue<String>()

        override fun connected(welcome: HubFrame.Welcome) {
            welcomes += welcome
        }

        override fun received(frame: HubFrame) = Unit

        override fun disconnected(why: String) = Unit

        override fun failed(why: String) {
            failures += why
        }
    }

    @Test
    fun `a proxy joins the hub with its credential, and a server joins once the proxy says it has it`() {
        val folder = createTempDirectory("hub")
        Files.writeString(folder.resolve("hub.properties"), "bind=127.0.0.1\nport=0\nsecret=${Auth.newSecret()}\n")
        val added = credentials(folder, listOf("add", "velocity", "proxy"))
        val secret = added.substringAfter("shown only now: ")
        assertTrue("velocity: proxy" in credentials(folder, listOf("list")))
        val hub = StandaloneHub(folder, Quiet)
        hub.start()
        try {
            val proxy = Events()
            val proxyLink = HubClient("127.0.0.1", hub.port, "velocity-1", secret, proxy, credential = "velocity", role = "proxy")
            proxyLink.start()
            assertNotNull(proxy.welcomes.poll(10, TimeUnit.SECONDS), proxy.failures.toString())
            proxyLink.send(ServerFrame.ProxyServers(listOf("lobby"), listOf("lobby")))

            val shared = Files.readAllLines(folder.resolve("hub.properties")).first { it.startsWith("secret=") }.substringAfter('=')
            val lobby = Events()
            val lobbyLink = HubClient("127.0.0.1", hub.port, "lobby", shared, lobby)
            lobbyLink.start()
            val welcome = assertNotNull(lobby.welcomes.poll(10, TimeUnit.SECONDS), lobby.failures.toString())
            assertEquals("lobby", welcome.server)
            lobbyLink.stop()
            proxyLink.stop()
        } finally {
            hub.stop()
        }
    }

    @Test
    fun `a credential revoked on the command line is refused by the running hub`() {
        val folder = createTempDirectory("hub")
        Files.writeString(folder.resolve("hub.properties"), "bind=127.0.0.1\nport=0\n")
        val secret = credentials(folder, listOf("add", "velocity", "proxy")).substringAfter("shown only now: ")
        val hub = StandaloneHub(folder, Quiet)
        hub.start()
        try {
            credentials(folder, listOf("revoke", "velocity"))
            // The file is read again within seconds.
            Thread.sleep(3_000)
            val proxy = Events()
            val link = HubClient("127.0.0.1", hub.port, "velocity-1", secret, proxy, credential = "velocity", role = "proxy")
            link.start()
            val why = assertNotNull(proxy.failures.poll(10, TimeUnit.SECONDS))
            assertTrue(why.contains("doesn't have"), why)
            link.stop()
        } finally {
            hub.stop()
        }
        assertTrue(admins(folder, listOf("add", "ops", "*")).contains("token"))
        assertTrue("ops: servers:read" in admins(folder, listOf("list")))
    }
}
