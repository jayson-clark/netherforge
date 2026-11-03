package dev.netherforge.plugin.network

import dev.netherforge.network.Credential
import dev.netherforge.network.Grants
import dev.netherforge.network.Hub
import dev.netherforge.network.HubLog
import dev.netherforge.network.HubProxy
import dev.netherforge.network.HubServer
import dev.netherforge.network.Lifecycle
import dev.netherforge.network.NetworkLimits
import dev.netherforge.network.PlayerState
import dev.netherforge.network.Registration
import dev.netherforge.network.Share
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

        val added = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Int>>()

        override fun servers() = listOf("lobby", "survival", "creative") + added.keys.sorted()

        override fun register(name: String, host: String, port: Int): String? {
            added[name] = host to port
            return null
        }

        override fun unregister(name: String) {
            added.remove(name)
        }

        override fun isFixed(name: String) = name in listOf("lobby", "survival", "creative")

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

    private fun server(
        name: String,
        script: String,
        requires: String = """{ "network": true }""",
        extra: Map<String, Any> = emptyMap(),
        credential: Credential? = null,
        group: String? = null,
        capacity: Int? = null,
        register: Registration? = null,
        idleAfterSeconds: Int = 0
    ) = TestServer(
        mapOf(TestServer.MANIFEST to TestServer.manifest(requires = requires), "modules/t/init.lua" to script) + extra,
        network = NetworkConfig(
            enabled = true,
            port = hubServer.port,
            serverName = name,
            secret = credential?.secret ?: SECRET,
            credential = credential?.id,
            group = group,
            capacity = capacity,
            register = register,
            idleAfterSeconds = idleAfterSeconds
        )
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

    /**
     * Until the hub knows [server] listens to (or answers on) each of [channels] (`test:` names): a server says so on a
     * tick after it connects, and the hub reads it on that connection's thread, so no count of ticks is sure to be
     * enough. A message published or a request made before then doesn't reach it, as on a real network.
     */
    private fun listening(server: String, vararg channels: String) =
        tickUntil { hub.channelsOf(server).containsAll(channels.map { "test:$it" }) }

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
        listening("lobby", "chat")
        listening("survival", "chat")
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
        listening("survival", "prices")
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
        assertTrue("sent survival" in lobby.logs, lobby.logs.toString())
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
        listening("lobby", "chat")
        tickUntil { lobby.runtime.network.servers["survival"]?.linked == true }
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
    fun `players, messages and requests go to a group, and the hub picks the instance`() {
        val arena = """
            nf.network.channel("state"):on("message", function(event) log("told " .. event.value) end)
            nf.network.channel("state"):on("request", function(event) event.reply = nf.network.server_name() end)
            nf.commands.register("close", function() nf.network.set_accepting(false) end)
        """
        val lobby = server(
            "lobby",
            """
            nf.commands.register("groups", function()
              for _, group in ipairs(nf.network.groups()) do
                local names = {}
                for _, instance in ipairs(group.instances) do names[#names + 1] = instance.name end
                log("group " .. group.name .. " " .. group.players .. "/" .. tostring(group.capacity) .. " " .. table.concat(names, ","))
              end
              log("in arena " .. #nf.network.players({ group = "arena" }))
            end)
            nf.commands.register("send", function(event)
              nf.task(function()
                local server, err = nf.network.send_players({ "Ann", "Bea" }, { group = "arena", data = { party = 1 } })
                log("party " .. tostring(server or err))
              end)
            end)
            nf.commands.register("ask", function()
              nf.network.channel("state"):publish("go", { group = "arena" })
              nf.task(function()
                local a = nf.network.channel("state"):request({ group = "arena" }, "?")
                local b = nf.network.channel("state"):request({ group = "arena" }, "?")
                log("answered " .. a .. " " .. b)
              end)
            end)
            """
        )
        val survival = server("survival", arena, group = "arena", capacity = 4)
        val creative = server("creative", arena, group = "arena", capacity = 4)
        connected()
        assertEquals(listOf("arena", "arena"), listOf(survival, creative).map { it.runtime.network.group })
        for (name in listOf("Ann", "Bea")) hub.playerMoved(UUID.nameUUIDFromBytes(name.toByteArray()).toString(), name, "lobby")
        hub.playerMoved("u-3", "Cid", "survival")
        tickUntil { lobby.runtime.network.players.size == 3 }

        lobby.platform.commands.runConsole("groups")
        assertEquals(listOf("group arena 1/8 creative,survival", "group lobby 2/nil lobby", "in arena 1"), lobby.logs)

        proxy.onSend = { id, server ->
            hub.playerMoved(id, id, server)
            null
        }
        lobby.platform.commands.runConsole("send")
        tickUntil { lobby.logs.any { it.startsWith("party") } }
        assertEquals("party creative", lobby.logs.last(), "the instance with the fewest players")

        creative.platform.commands.runConsole("close")
        survival.platform.commands.runConsole("close")
        tickUntil { hub.states().filter { it.group == "arena" }.none { it.accepting } }
        hub.playerMoved("u-4", "Dee", "lobby")
        tickUntil { lobby.runtime.network.players.size == 4 }
        val before = lobby.logs.size
        lobby.platform.commands.runConsole("send")
        tickUntil { lobby.logs.size > before }
        assertEquals("party no instance of arena takes players now", lobby.logs.last())

        listening("survival", "state")
        listening("creative", "state")
        lobby.platform.commands.runConsole("ask")
        tickUntil { lobby.logs.last().startsWith("answered") }
        assertTrue(lobby.logs.last() in setOf("answered creative survival", "answered survival creative"), "one each: ${lobby.logs.last()}")
        tickUntil { "told go" in survival.logs && "told go" in creative.logs }
        assertTrue("told go" !in lobby.logs)
    }

    @Test
    fun `a server adds itself to the proxy with a name the hub gives it, and comes off as it stops`() {
        val arena = server(
            "auto",
            """nf.on("network_connect", function(event) log("joined as " .. event.server .. " " .. nf.network.server_name()) end)""",
            group = "arena",
            register = Registration(null, 25999)
        )
        connected()
        tickUntil { arena.logs.isNotEmpty() }
        assertEquals(listOf("joined as arena-1 arena-1"), arena.logs)
        assertEquals("127.0.0.1" to 25999, proxy.added["arena-1"], "where the hub saw it connect from")
        arena.close()
        servers.remove(arena)
        tickUntil { proxy.added.isEmpty() }
    }

    @Test
    fun `a held server starts, then is ready, and a send that waits for it goes once it is`() {
        val arena = server(
            "survival",
            """
            local hold = nf.network.hold_ready()
            log("state " .. nf.network.state())
            nf.on("network_state", function(event) log(event.previous .. " -> " .. event.state) end)
            nf.commands.register("release", function() hold:release() end)
            """,
            group = "arena"
        )
        val lobby = server(
            "lobby",
            """
            nf.commands.register("send", function()
              nf.task(function()
                local server, err = nf.network.send_player("Ann", {
                  group = "arena",
                  wait = 30,
                  on_wait = function(position) log("waiting #" .. position) end,
                })
                log("sent " .. tostring(server or err))
              end)
            end)
            """
        )
        connected()
        val ann = UUID.nameUUIDFromBytes("Ann".toByteArray()).toString()
        hub.playerMoved(ann, "Ann", "lobby")
        tickUntil { lobby.runtime.network.players.isNotEmpty() }
        assertEquals(Lifecycle.STARTING, hub.states().single { it.name == "survival" }.state)
        proxy.onSend = { id, server ->
            hub.playerMoved(id, "Ann", server)
            null
        }
        lobby.platform.commands.runConsole("send")
        tickUntil { "waiting #1" in lobby.logs }
        arena.platform.commands.runConsole("release")
        tickUntil { lobby.logs.any { it.startsWith("sent") } }
        assertEquals("sent survival", lobby.logs.last())
        assertEquals(listOf("state starting", "starting -> ready"), arena.logs)
        assertEquals(Lifecycle.READY, hub.states().single { it.name == "survival" }.state)
        assertTrue(arena.runtime.network.startupMillis != null)
    }

    @Test
    fun `an empty server is idle once its time is up, and the hub hears it`() {
        server("survival", "", idleAfterSeconds = 1)
        connected()
        tickUntil { hub.states().single { it.name == "survival" }.idle }
    }

    @Test
    fun `a drained server takes no players, and shutting down sends its players away and stops`() {
        val survival = server("survival", "", group = "arena")
        val lobby = server(
            "lobby",
            """
            nf.commands.register("send", function()
              nf.task(function()
                local server, err = nf.network.send_player("Ann", "survival")
                log("sent " .. tostring(server or err))
              end)
            end)
            """
        )
        connected()
        tickUntil { hub.states().single { it.name == "survival" }.state == Lifecycle.READY }
        survival.platform.commands.runAsConsole("nf drain")
        tickUntil { hub.states().single { it.name == "survival" }.state == Lifecycle.DRAINING }
        val ann = UUID.nameUUIDFromBytes("Ann".toByteArray())
        hub.playerMoved(ann.toString(), "Ann", "lobby")
        tickUntil { lobby.runtime.network.players.isNotEmpty() }
        lobby.platform.commands.runConsole("send")
        tickUntil { lobby.logs.any { it.startsWith("sent") } }
        assertEquals("sent survival is draining: it takes no new players", lobby.logs.last())

        // Ann is on survival when it shuts down: the hub sends her to the fallback (lobby).
        hub.playerMoved(ann.toString(), "Ann", "survival")
        val joined = survival.platform.players.add("Ann", uuid = ann)
        survival.platform.raise.playerJoin(GameEvent.PlayerJoin(joined.ref, true, null))
        proxy.onSend = { id, server ->
            hub.playerMoved(id, "Ann", server)
            null
        }
        survival.platform.commands.runAsConsole("nf shutdown")
        tickUntil { proxy.sends.isNotEmpty() }
        // The proxy took her: she's gone from survival.
        survival.platform.players.quit(joined)
        tickUntil { survival.platform.shutdownReason != null }
        assertEquals(listOf(ann.toString() to "lobby"), proxy.sends)
        assertEquals(Lifecycle.STOPPING, hub.states().single { it.name == "survival" }.state)
    }

    @Test
    fun `a script asks a service on the hub's admin API as it asks a server`() {
        val stream = hub.attach("matchmaker", setOf(dev.netherforge.network.AdminScope.CHANNELS_READ))
        val lobby = server(
            "lobby",
            """
            nf.on("network_connect", function()
              nf.task(function()
                local match, err = nf.network.channel("match"):request({ admin = "matchmaker" }, "Ann")
                log("match " .. tostring(match or err))
              end)
            end)
            """
        )
        connected()
        val asked = generateSequence { stream.take(10_000) }.first { it is dev.netherforge.network.AdminEvent.Request }
            as dev.netherforge.network.AdminEvent.Request
        assertEquals("test:match", asked.channel)
        assertEquals(null, hub.adminReply("matchmaker", asked.id, kotlinx.serialization.json.JsonPrimitive("arena-2"), null))
        tickUntil { lobby.logs.isNotEmpty() }
        assertEquals(listOf("match arena-2"), lobby.logs)
        stream.close()
    }

    @Test
    fun `a server with a credential knows its role, and what it may not do is refused at the script's line`() {
        val credential = Credential(
            "survival-cred",
            "survival-secret-0123456789",
            Grants.preset("standard", shared = listOf(Share.parse("test:news(read)"), Share.parse("test:ask(request)")))
        )
        hub.setCredentials(listOf(credential))
        val lobby = server(
            "lobby",
            """
            nf.network.channel("news"):on("message", function(event) log("lobby heard " .. event.value) end)
            nf.network.channel("ask"):on("request", function(event) event.reply = "yes" end)
            """
        )
        val survival = server(
            "survival",
            """
            nf.network.channel("news"):on("message", function(event) log("survival heard " .. event.value) end)
            nf.on("network_connect", function()
              local role = nf.network.server_role()
              log(role.role .. " " .. tostring(role.trusted) .. " " .. role.shares[1].name .. " " .. role.shares[1].access[1])
              local ok, err = pcall(function() nf.network.channel("news"):publish("fake") end)
              log(tostring(ok) .. " " .. err)
              ok, err = pcall(function() nf.network.map("scores"):set("k", 1) end)
              log(tostring(ok) .. " " .. err)
            end)
            nf.commands.register("ask", function()
              nf.task(function()
                local answer, err = nf.network.channel("ask"):request("lobby", "?")
                log("answer " .. tostring(answer or err))
              end)
            end)
            """,
            credential = credential
        )
        connected()
        // Asked only once lobby answers on the channel: before the hub knows it does, a request fails at once (as it
        // would on a real network), and which server's connection the hub hears first is up to the threads.
        tickUntil { survival.logs.size == 3 }
        listening("lobby", "ask")
        survival.platform.commands.runConsole("ask")
        tickUntil { survival.logs.size == 4 }
        assertEquals("standard false test:news read", survival.logs[0])
        assertTrue(survival.logs[1].startsWith("false ") && survival.logs[1].contains("network.forbidden"), survival.logs[1])
        assertTrue(survival.logs[1].contains("may not write to \"test:news\""), survival.logs[1])
        assertTrue(survival.logs[2].contains("network.forbidden") && survival.logs[2].contains("test:scores"), survival.logs[2])
        assertEquals("answer yes", survival.logs[3])
        assertEquals("trusted", hub.connections().single { it.server == "lobby" }.grants.role)
        assertEquals("survival-cred", hub.connections().single { it.server == "survival" }.credential)
        assertTrue(lobby.errors.isEmpty(), lobby.errors.toString())
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
