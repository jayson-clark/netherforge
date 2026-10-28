package dev.netherforge.plugin.network

import dev.netherforge.network.Hub
import dev.netherforge.network.HubLog
import dev.netherforge.network.HubProxy
import dev.netherforge.network.HubServer
import dev.netherforge.network.NetworkLimits
import dev.netherforge.network.PlayerState
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.GameEvent
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `nf.network` between real servers: a hub on loopback (the proxy plugin's [Hub] over TCP, with a proxy the test
 * plays) and two [TestServer]s connected to it as `lobby` and `survival`, each running scripts. What one publishes,
 * asks, writes and sends reaches the other as the scripts' events, through the same code a network runs.
 */
class NetworkTest {
    private class Proxy : HubProxy {
        val sends = CopyOnWriteArrayList<Pair<String, String>>()

        /** What happens when the hub asks to move a player: null for done. */
        var onSend: (String, String) -> String? = { _, _ -> null }

        override fun servers() = listOf("lobby", "survival", "creative")

        override fun players() = emptyList<PlayerState>()

        override fun send(id: String, server: String): CompletionStage<String?> {
            sends += id to server
            return CompletableFuture.completedFuture(onSend(id, server))
        }
    }

    private object Quiet : HubLog {
        override fun info(message: String) = Unit

        override fun warn(message: String) = Unit
    }

    private val proxy = Proxy()
    private val hub = Hub(SECRET, proxy, Quiet, NetworkLimits(maxValueBytes = 2048))
    private val hubServer = HubServer(hub, InetSocketAddress(InetAddress.getLoopbackAddress(), 0), Quiet).also { it.start() }
    private val servers = ArrayList<TestServer>()

    @AfterTest
    fun stop() {
        servers.forEach { it.close() }
        hubServer.stop()
    }

    private fun server(name: String, script: String, requires: String = """{ "network": true }""", extra: Map<String, Any> = emptyMap()) =
        TestServer(
            mapOf(TestServer.MANIFEST to TestServer.manifest(requires = requires), "modules/t/init.lua" to script) + extra,
            network = NetworkConfig(enabled = true, port = hubServer.port, serverName = name, secret = SECRET)
        ).also { servers += it }

    /** Ticks every server until [done], as servers would while the network catches up. */
    private fun tickUntil(done: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!done()) {
            check(System.nanoTime() < deadline) { "timed out; " + servers.joinToString { "logs: ${it.logs}, errors: ${it.errors}" } }
            servers.forEach { it.tick() }
            Thread.sleep(5)
        }
    }

    private fun connected() = tickUntil { servers.all { it.runtime.network.connected } && hub.linked().size == servers.size }

    @Test
    fun `a message reaches every server listening, typed values and all, the sender's included`() {
        val listening = """
            nf.network.channel("chat"):on("message", function(event)
              log("heard " .. event.value.text .. " at " .. tostring(event.value.at) .. " from " .. event.server .. " on " .. event.channel:name())
            end)
        """
        val lobby = server(
            "lobby",
            listening + """
            nf.on("network_server_connect", function(event) log("up " .. event.server) end)
            nf.on("network_connect", function(event) log("connected as " .. event.server .. " " .. tostring(nf.network.is_connected())) end)
            nf.commands.register("say", function()
              log("sent " .. tostring(nf.network.channel("chat"):publish({ text = "hi", at = vec3(1, 2, 3) })))
            end)
            """
        )
        server("survival", listening)
        connected()
        tickUntil { lobby.logs.any { it.startsWith("up survival") } }
        // Each says what it listens to on its next tick.
        repeat(3) { servers.forEach { it.tick() } }
        lobby.platform.commands.runConsole("say")
        tickUntil { servers.all { server -> server.logs.any { it.startsWith("heard") } } }
        for (server in servers) {
            assertEquals(listOf("heard hi at vec3(1, 2, 3) from lobby on chat"), server.logs.filter { it.startsWith("heard") })
            assertTrue(server.errors.isEmpty(), server.errors.toString())
        }
        assertTrue("sent true" in lobby.logs)
        assertTrue("connected as lobby true" in lobby.logs)
    }

    @Test
    fun `a request is answered by the server asked, and fails saying why when it can't be`() {
        server(
            "survival",
            """
            local prices = nf.network.channel("prices")
            prices:on("request", function(event)
              if event.value == "nothing" then return end
              event.reply = { item = event.value, price = 42, asker = event.server }
            end)
            """
        )
        val lobby = server(
            "lobby",
            """
            nf.commands.register("ask", function()
              nf.task(function()
                local prices = nf.network.channel("prices")
                local answer, err = prices:request("survival", "diamond")
                log("answer " .. answer.item .. " " .. answer.price .. " " .. answer.asker .. " " .. tostring(err))
                local none, why = prices:request("survival", "nothing")
                log("none " .. tostring(none) .. " " .. tostring(why))
                local _, missing = nf.network.channel("other"):request("survival", 1)
                log("other " .. missing)
                local _, away = prices:request("creative", 1)
                log("away " .. away)
              end)
            end)
            """
        )
        connected()
        repeat(3) { servers.forEach { it.tick() } }
        lobby.platform.commands.runConsole("ask")
        tickUntil { lobby.logs.any { it.startsWith("away") } }
        assertEquals(
            listOf(
                "answer diamond 42 lobby nil",
                "none nil nil",
                "other nothing on survival answers requests on \"test:other\"",
                "away creative isn't connected to the network"
            ),
            lobby.logs
        )
    }

    @Test
    fun `a shared map is the same on every server, changes heard everywhere, adds and inserts made once`() {
        val script = """
            local scores = nf.network.map("scores")
            scores:on("ready", function() log("ready " .. tostring(scores:get("ann"))) end)
            scores:on("change", function(event)
              log("change " .. event.key .. " " .. tostring(event.value) .. " was " .. tostring(event.previous) .. " by " .. tostring(event.server) .. " " .. event.cause)
            end)
            nf.commands.register("play", function()
              nf.task(function()
                log(string.format("total %d", scores:add("games", 1)))
                log("claimed " .. tostring(scores:insert("arena", nf.network.server_name())))
              end)
            end)
            nf.commands.register("set", function()
              log("set " .. tostring(scores:set("ann", 3)))
            end)
            nf.commands.register("drop", function()
              scores:delete("ann")
            end)
        """
        val lobby = server("lobby", script)
        val survival = server("survival", script)
        connected()
        tickUntil { servers.all { server -> server.logs.any { it.startsWith("ready") } } }
        lobby.platform.commands.runConsole("set")
        tickUntil { servers.all { server -> server.logs.any { it.startsWith("change ann") } } }
        for (server in servers) assertEquals("change ann 3 was nil by lobby set", server.logs.first { it.startsWith("change ann") })

        lobby.platform.commands.runConsole("play")
        survival.platform.commands.runConsole("play")
        tickUntil { servers.all { server -> server.logs.any { it.startsWith("claimed") } } }
        val totals = servers.map { server -> server.logs.single { it.startsWith("total") } }.sorted()
        assertEquals(listOf("total 1", "total 2"), totals)
        val claims = servers.map { server -> server.logs.single { it.startsWith("claimed") } }.sorted()
        assertEquals(listOf("claimed false", "claimed true"), claims)

        survival.platform.commands.runConsole("drop")
        tickUntil { lobby.logs.any { it.startsWith("change ann nil") } }
        assertEquals("change ann nil was 3 by survival delete", lobby.logs.last { it.startsWith("change ann") })
        assertTrue(servers.all { it.errors.isEmpty() }, servers.map { it.errors }.toString())
    }

    @Test
    fun `who's online where follows the proxy, and a sent player arrives with their data`() {
        val lobby = server(
            "lobby",
            """
            nf.on("network_player_join", function(event) log("join " .. event.player.name .. " on " .. event.player.server) end)
            nf.on("network_player_switch", function(event) log("switch " .. event.player.name .. " " .. event.previous .. " -> " .. event.player.server) end)
            nf.on("network_player_quit", function(event) log("quit " .. event.player.name .. " from " .. event.player.server) end)
            nf.commands.register("who", function()
              local ann = nf.network.find_player("ann")
              log("who " .. #nf.network.players() .. " " .. ann.server .. " " .. tostring(nf.network.find_player(ann.id).name))
              for _, server in ipairs(nf.network.servers()) do
                log("server " .. server.name .. " " .. tostring(server.connected) .. " " .. server.players)
              end
            end)
            nf.commands.register("send", function()
              nf.task(function()
                log("sent " .. tostring(nf.network.send_player("Ann", "survival", { kit = "archer" })))
                local _, err = nf.network.send_player("Nobody", "survival")
                log("nobody " .. err)
              end)
            end)
            """
        )
        val survival = server(
            "survival",
            """
            nf.on("player_arrive", function(event)
              log("arrived " .. event.player:name() .. " from " .. event.from .. " with " .. event.data.kit)
            end)
            """
        )
        connected()
        val ann = UUID.nameUUIDFromBytes("Ann".toByteArray())
        hub.playerMoved(ann.toString(), "Ann", "lobby")
        tickUntil { lobby.logs.any { it.startsWith("join") } }
        lobby.platform.commands.runConsole("who")
        assertEquals(
            listOf(
                "join Ann on lobby",
                "who 1 lobby Ann",
                "server lobby true 1",
                "server survival true 0",
                "server creative false 0"
            ),
            lobby.logs
        )

        proxy.onSend = { id, server ->
            hub.playerMoved(id, "Ann", server)
            null
        }
        lobby.platform.commands.runConsole("send")
        tickUntil { lobby.logs.any { it.startsWith("nobody") } }
        // The proxy moved them; survival sees them join.
        val joined = survival.platform.players.add("Ann", uuid = ann)
        survival.platform.raise.playerJoin(GameEvent.PlayerJoin(joined.ref, true, null))
        tickUntil { survival.logs.any { it.startsWith("arrived") } }
        assertEquals(listOf("arrived Ann from lobby with archer"), survival.logs)
        assertEquals(listOf(ann.toString() to "survival"), proxy.sends)
        assertTrue("sent true" in lobby.logs)
        assertTrue("nobody nobody called \"Nobody\" is online in the network" in lobby.logs, lobby.logs.toString())
        tickUntil { lobby.logs.any { it.startsWith("switch") } }
        assertTrue("switch Ann lobby -> survival" in lobby.logs)

        hub.playerLeft(ann.toString())
        tickUntil { lobby.logs.any { it.startsWith("quit") } }
        assertTrue("quit Ann from survival" in lobby.logs)
    }

    @Test
    fun `nf network says how the server is in its network`() {
        val lobby = server("lobby", """nf.network.channel("chat"):on("message", function() end)""")
        server("survival", "")
        connected()
        repeat(3) { servers.forEach { it.tick() } }
        val said = lobby.platform.commands.runAsConsole("nf network").joinToString("\n").replace(Regex("<[^>]+>"), "")
        assertTrue(said.contains("In the network as lobby through its hub at 127.0.0.1:${hubServer.port}: connected"), said)
        assertTrue(said.contains("survival connected, 0 online"), said)
        assertTrue(said.contains("creative not connected"), said)
        assertTrue(said.contains("Listening to 1 channels and 0 maps."), said)
    }

    @Test
    fun `a lost hub is heard, fails what waits on it, and is joined again when it's back`() {
        val lobby = server(
            "lobby",
            """
            nf.on("network_disconnect", function() log("lost " .. tostring(nf.network.is_connected())) end)
            nf.on("network_connect", function() log("connected") end)
            nf.commands.register("publish", function()
              log("published " .. tostring(nf.network.channel("chat"):publish("x")))
            end)
            """
        )
        connected()
        tickUntil { "connected" in lobby.logs }
        hubServer.stop()
        tickUntil { lobby.logs.any { it.startsWith("lost") } }
        lobby.platform.commands.runConsole("publish")
        assertEquals(listOf("connected", "lost false", "published false"), lobby.logs)
    }

    @Test
    fun `a server stopping ends its link quietly`() {
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
        try {
            server("lobby", "")
            connected()
            // The link's thread is interrupted as it says it's disconnected: nothing may escape it.
            servers.forEach { it.close() }
            servers.clear()
            assertEquals(emptyList(), uncaught)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test
    fun `values over the network's limit and other packages' channels are mistakes at the script's line`() {
        val lobby = server(
            "lobby",
            """
            nf.on("network_connect", function()
              local ok, err = pcall(function() nf.network.channel("chat"):publish(string.rep("x", 5000)) end)
              log(tostring(ok) .. " " .. err)
              ok, err = pcall(function() nf.network.channel("shop:sales") end)
              log(tostring(ok) .. " " .. err)
              ok, err = pcall(function() nf.network.map("Bad Name") end)
              log(tostring(ok) .. " " .. err)
              ok, err = pcall(function() nf.network.channel("chat"):publish(function() end) end)
              log(tostring(ok) .. " " .. err)
            end)
            """
        )
        connected()
        tickUntil { lobby.logs.size == 4 }
        assertTrue(
            lobby.logs[0].startsWith("false ") && lobby.logs[0].contains("is 5002 bytes as JSON, over the network's limit of 2048"),
            lobby.logs[0]
        )
        assertTrue(lobby.logs[1].contains("package \"test\" doesn't depend on \"shop\", so it can't use \"shop:sales\""), lobby.logs[1])
        assertTrue(lobby.logs[2].contains("\"Bad Name\" isn't a name"), lobby.logs[2])
        assertTrue(lobby.logs[3].contains("a function can't be written as JSON"), lobby.logs[3])
    }

    @Test
    fun `a package must declare the network, and a server in none says so to the packages that do`() {
        val undeclared = server("lobby", """log(select(2, pcall(nf.network.server_name)))""", requires = """{ "db": true }""")
        assertTrue(undeclared.logs.single().contains("needs network"), undeclared.logs.single())

        TestServer(
            mapOf(
                TestServer.MANIFEST to TestServer.manifest(requires = """{ "network": true }"""),
                "modules/t/init.lua" to """
                    log(tostring(nf.network.server_name()) .. " " .. tostring(nf.network.is_connected()) .. " " .. tostring(nf.network.channel("x"):publish(1)))
                """
            )
        ).use { off ->
            assertEquals(listOf("nil false false"), off.logs)
            val problem = off.runtime.session.problems().single { it.code == "runtime.network-disabled" }
            assertEquals("netherforge.json", problem.file)
        }
    }

    private companion object {
        const val SECRET = "a-secret-long-enough-for-the-hub"
    }
}
