package dev.netherforge.plugin.bridge
import dev.netherforge.format.bridge.BlockPos
import dev.netherforge.format.bridge.BotActParams
import dev.netherforge.format.bridge.BotAction
import dev.netherforge.format.bridge.BotJoinParams
import dev.netherforge.format.bridge.BotParams
import dev.netherforge.format.bridge.BotPosition
import dev.netherforge.format.bridge.BotsExtension
import dev.netherforge.format.bridge.Bridge
import dev.netherforge.format.bridge.BridgeAuth
import dev.netherforge.format.bridge.BridgeMethod
import dev.netherforge.format.bridge.CommandParams
import dev.netherforge.format.bridge.HelloParams
import dev.netherforge.format.bridge.JsonRpc
import dev.netherforge.format.bridge.LoadedWorld
import dev.netherforge.format.bridge.Log
import dev.netherforge.format.bridge.PlayParticleEffectParams
import dev.netherforge.format.bridge.PlayerPosition
import dev.netherforge.format.bridge.PlayerPositionParams
import dev.netherforge.format.bridge.Problems
import dev.netherforge.format.bridge.ProfileSample
import dev.netherforge.format.bridge.ProfilerSubscribeParams
import dev.netherforge.format.bridge.ReloadParams
import dev.netherforge.format.bridge.RpcError
import dev.netherforge.format.bridge.RpcFrame
import dev.netherforge.format.bridge.RpcNotification
import dev.netherforge.format.bridge.RpcRequest
import dev.netherforge.format.bridge.RpcResponse
import dev.netherforge.format.bridge.SaveStructureParams
import dev.netherforge.format.bridge.SaveWorldParams
import dev.netherforge.format.bridge.SavedWorld
import dev.netherforge.format.bridge.SourceRef
import dev.netherforge.format.bridge.SpawnParams
import dev.netherforge.format.bridge.Status
import dev.netherforge.format.bridge.WorldSpawn
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.testkit.BlockAt
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** The plugin's side of the dev bridge, driven by a test playing the editor over a real socket. */
class BridgeTest {

    /** What a request got back: its result, or the error. */
    private data class Answer<R>(val result: R?, val error: RpcError?) {
        val ok get() = error == null
    }

    /**
     * One accepted connection, read on a background thread so the test can
     * pump the main thread meanwhile. It answers the plugin's hello (or
     * refuses it with [refuse]) and unpacks console batches into their entries.
     */
    private class Editor(socket: Socket, private val server: TestServer, refuse: String? = null) {
        private val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))

        /** The handshake's editor end: every test's server has the token "secret". */
        private val auth = BridgeAuth.EditorSide("secret")

        /** The plugin's hello, until its proof completes the handshake. */
        private var hello: HelloParams? = null

        /** Hellos, console entries, notifications' params and responses, as they arrive. */
        private val frames = LinkedBlockingQueue<Any>()
        private var nextId = 1

        /** How many console notifications carried how many entries. */
        val consoleBatches = mutableListOf<Int>()

        init {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            Thread {
                runCatching {
                    while (true) {
                        val line = reader.readLine() ?: break
                        val frame = JsonRpc.decode(line)
                        val messages = (frame as? RpcFrame.Batch)?.messages ?: listOf((frame as RpcFrame.Single).message)
                        if (frame is RpcFrame.Batch) frames += frame
                        for (message in messages) receive(message, refuse)
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }
        }

        private fun receive(message: Any, refuse: String?) {
            when (message) {
                is RpcRequest if message.method == Bridge.authenticate.name -> {
                    val proof = Bridge.authenticate.decodeParams(message.params)
                    assertTrue(auth.accepts(proof), "the plugin proves the token")
                    write(RpcResponse.ok(message.id, Bridge.authenticate.encodeResult(Unit)))
                    // The test sees the hello once the handshake is done: a request it sends next comes after.
                    frames += checkNotNull(hello)
                }
                is RpcRequest -> {
                    assertEquals(Bridge.hello.name, message.method)
                    val hello = Bridge.hello.decodeParams(message.params)
                    if (refuse != null) {
                        write(RpcResponse.error(message.id, Bridge.PROTOCOL_MISMATCH, refuse))
                        frames += hello
                    } else {
                        this.hello = hello
                        write(RpcResponse.ok(message.id, Bridge.hello.encodeResult(auth.answer(hello))))
                    }
                }
                is RpcNotification -> when (message.method) {
                    Bridge.console.name -> Bridge.console.decode(message.params).also {
                        synchronized(consoleBatches) { consoleBatches += it.size }
                        frames.add(it)
                    }
                    Bridge.profiler.name -> frames.addAll(Bridge.profiler.decode(message.params))
                    else -> frames += Bridge.events.single { it.name == message.method }.decode(message.params)!!
                }
                else -> frames += message
            }
        }

        fun write(message: dev.netherforge.format.bridge.RpcMessage) = writeLine(JsonRpc.line(message))

        fun writeLine(line: String) {
            synchronized(writer) {
                writer.write(line + "\n")
                writer.flush()
            }
        }

        /** Frames a [next] passed over, for a later one. */
        val seen = mutableListOf<Any>()

        inline fun <reified T : Any> next(crossinline matching: (T) -> Boolean = { true }): T {
            seen.firstOrNull { it is T && matching(it) }?.let {
                seen.remove(it)
                return it as T
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (System.nanoTime() < deadline) {
                server.runMain()
                val frame = frames.poll(20, TimeUnit.MILLISECONDS) ?: continue
                if (frame is T && matching(frame)) return frame
                seen += frame
            }
            fail("no ${T::class.simpleName} within 10s")
        }

        /** The responses that arrive within [millis] while nothing runs the main thread's tasks; other frames are dropped. */
        fun responsesWithoutRunningTheMainThread(millis: Long): List<RpcResponse> {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis)
            val responses = mutableListOf<RpcResponse>()
            while (System.nanoTime() < deadline) {
                (frames.poll(20, TimeUnit.MILLISECONDS) as? RpcResponse)?.let { responses += it }
            }
            return responses
        }

        fun <P, R> request(method: BridgeMethod<P, R>, params: P): Answer<R> {
            val id = nextId++
            write(method.request(id, params))
            return answer(method, id)
        }

        fun <R> answer(method: BridgeMethod<*, R>, id: Int): Answer<R> {
            val response = next<RpcResponse> { it.id == JsonPrimitive(id) }
            return Answer(response.result?.let(method::decodeResult), response.error)
        }
    }

    private fun connect(listener: ServerSocket, server: TestServer, refuse: String? = null): Editor {
        listener.soTimeout = 10_000
        return Editor(listener.accept(), server, refuse)
    }

    @Test
    fun `the plugin says hello, then answers every request with its id`() {
        ServerSocket(0).use { listener ->
            TestServer(TestServer.example("showcase"), bridge = BridgeConfig(listener.localPort, "secret")).use { server ->
                val editor = connect(listener, server)
                val hello = editor.next<HelloParams>()
                // A nonce, never the token: the plugin proves it once the editor has proved it too.
                assertTrue(hello.nonce.length >= 32 && "secret" !in hello.nonce, hello.nonce)
                assertEquals(Bridge.PROTOCOL, hello.protocol)
                assertEquals("26.3", hello.minecraft)
                assertEquals(server.project.toString(), hello.project)
                assertEquals(emptyList(), editor.next<Problems>().problems)
                assertEquals(Status(emptyList(), 0), editor.next<Status>())

                val spawned = editor.request(Bridge.spawn, SpawnParams("tower"))
                assertTrue(spawned.ok, "${spawned.error}")
                assertEquals("tower", spawned.result!!.centity)

                assertEquals(listOf(spawned.result), editor.request(Bridge.instances, Unit).result)

                val reload = editor.request(Bridge.reload, ReloadParams(listOf("centities/tower/script.lua"))).result!!.resources.single()
                assertEquals(listOf("showcase", "centity", "tower"), listOf(reload.pkg, reload.kind, reload.id))
                assertEquals(1, reload.reattached)

                val game = editor.request(Bridge.exportGameData, Unit)
                assertTrue("minecraft:stone" in game.result!!.blocks)

                assertTrue(editor.request(Bridge.command, CommandParams("/nf list")).ok)
                val notRun = editor.request(Bridge.command, CommandParams("nothing here")).error!!
                assertEquals(Bridge.REQUEST_FAILED, notRun.code)
                assertEquals("the server didn't run \"nothing here\"", notRun.message)

                val unknown = editor.request(Bridge.spawn, SpawnParams("nope")).error!!
                assertEquals(Bridge.REQUEST_FAILED, unknown.code)
                assertTrue("no centity \"nope\"" in unknown.message, unknown.message)

                // A script's log line arrives on the console stream with where it was written.
                server.write("modules/greeter/init.lua", "-- now it only says hello\nlog('hello from greeter')\n")
                editor.request(Bridge.reload, ReloadParams(listOf("modules/greeter/init.lua")))
                val line = editor.next<List<*>> { batch -> batch.any { it is Log && it.message == "hello from greeter" } }
                    .filterIsInstance<Log>().single { it.message == "hello from greeter" }
                assertEquals(SourceRef("modules/greeter/init.lua", 2), line.source)
            }
        }
    }

    @Test
    fun `an unknown method, unreadable params and a broken frame are answered as JSON-RPC says`() {
        ServerSocket(0).use { listener ->
            TestServer(TestServer.example("showcase"), bridge = BridgeConfig(listener.localPort, "secret")).use { server ->
                val editor = connect(listener, server)
                editor.next<HelloParams>()

                editor.write(RpcRequest(JsonPrimitive(1), "debugger/attach"))
                assertEquals(RpcError(JsonRpc.METHOD_NOT_FOUND, "Unknown method \"debugger/attach\""), editor.answer(Bridge.ping, 1).error)

                editor.writeLine("""{"jsonrpc":"2.0","id":2,"method":"spawn","params":{"player":"Steve"}}""")
                val invalid = editor.answer(Bridge.spawn, 2).error!!
                assertEquals(JsonRpc.INVALID_PARAMS, invalid.code)
                assertTrue(invalid.message.startsWith("Invalid params for spawn"), invalid.message)

                editor.writeLine("""{"jsonrpc":"2.0","id":3,"method":""")
                val broken = editor.next<RpcResponse> { it.id == JsonNull }
                assertEquals(JsonRpc.PARSE_ERROR, broken.error!!.code)

                // A notification is never answered, not even an unknown one; the next request still is.
                editor.write(RpcNotification("nobody/listens"))
                assertTrue(editor.request(Bridge.ping, Unit).ok)

                // A batch gets one array back, in order, once every request in it is answered.
                editor.writeLine(
                    """[{"jsonrpc":"2.0","id":10,"method":"instances"},{"jsonrpc":"2.0","method":"x"},{"jsonrpc":"2.0","id":11,"method":"nope"}]"""
                )
                val batch = editor.next<RpcFrame.Batch>()
                assertEquals(listOf(JsonPrimitive(10), JsonPrimitive(11)), batch.messages.map { (it as RpcResponse).id })
                assertEquals(JsonRpc.METHOD_NOT_FOUND, (batch.messages[1] as RpcResponse).error!!.code)
            }
        }
    }

    @Test
    fun `a request marked for the bridge's thread is answered while the main thread is busy`() {
        ServerSocket(0).use { listener ->
            TestServer(TestServer.example("showcase"), bridge = BridgeConfig(listener.localPort, "secret")).use { server ->
                val editor = connect(listener, server)
                editor.next<HelloParams>()
                // The test doesn't run main-thread tasks from here on: `instances` waits, `ping` doesn't.
                editor.write(Bridge.instances.request(1, Unit))
                editor.write(Bridge.ping.request(2, Unit))
                editor.write(Bridge.profilerSubscribe.request(3, ProfilerSubscribeParams(on = true)))
                val responses = editor.responsesWithoutRunningTheMainThread(millis = 3_000)
                assertEquals(
                    listOf(RpcResponse.ok(JsonPrimitive(2), JsonNull), RpcResponse.ok(JsonPrimitive(3), JsonNull)),
                    responses.sortedBy { it.id.content }
                )
                assertTrue(server.runtime.profiler.streaming)
                // Now the main thread runs, and the other is answered.
                assertTrue(editor.answer(Bridge.instances, 1).ok)
            }
        }
    }

    @Test
    fun `a subscribed editor gets the profiler's samples once a second, and none after it stops`() {
        ServerSocket(0).use { listener ->
            TestServer(TestServer.example("showcase"), bridge = BridgeConfig(listener.localPort, "secret")).use { server ->
                val editor = connect(listener, server)
                editor.next<HelloParams>()
                assertTrue(editor.request(Bridge.profilerSubscribe, ProfilerSubscribeParams(on = true)).ok)
                server.tick(40)
                val first = editor.next<ProfileSample>()
                val second = editor.next<ProfileSample>()
                assertEquals((1L..20L).toList(), first.ticks.map { it.tick })
                assertEquals((21L..40L).toList(), second.ticks.map { it.tick })
                assertEquals(
                    listOf("timers", "async", "events", "world", "effects", "upkeep", "accounts", "save"),
                    first.ticks[0].phases.keys.toList()
                )

                assertTrue(editor.request(Bridge.profilerSubscribe, ProfilerSubscribeParams(on = false)).ok)
                server.tick(40)
                assertTrue(editor.request(Bridge.ping, Unit).ok)
                assertTrue(editor.seen.none { it is ProfileSample }, "${editor.seen}")
            }
        }
    }

    @Test
    fun `a burst of log lines goes out in a few batches, in order`() {
        ServerSocket(0).use { listener ->
            TestServer(TestServer.example("showcase"), bridge = BridgeConfig(listener.localPort, "secret")).use { server ->
                val editor = connect(listener, server)
                editor.next<HelloParams>()
                editor.next<Status>()
                repeat(2_000) { server.runtime.log.info("line $it") }
                val seen = mutableListOf<String>()
                while (seen.size < 2_000) {
                    seen += editor.next<List<*>>().filterIsInstance<Log>().map { it.message }.filter { it.startsWith("line ") }
                }
                assertEquals((0 until 2_000).map { "line $it" }, seen)
                assertTrue(synchronized(editor.consoleBatches) { editor.consoleBatches.size } < 100, "${editor.consoleBatches}")
            }
        }
    }

    @Test
    fun `an editor that refuses the hello is final, and the console says why`() {
        ServerSocket(0).use { listener ->
            TestServer(TestServer.example("showcase"), bridge = BridgeConfig(listener.localPort, "secret")).use { server ->
                val why = "This server's NetherForge plugin speaks dev bridge protocol 1 and the editor speaks 2"
                val editor = connect(listener, server, refuse = why)
                editor.next<HelloParams>()
                val refused = "The NetherForge editor refused this dev server's bridge: $why"
                assertTrue(eventually(server) { server.platform.log.lines.any { refused in it } }, "${server.platform.log.lines}")
                // No retry: nothing else connects.
                listener.soTimeout = 1_000
                assertTrue(runCatching { listener.accept() }.isFailure, "the plugin shouldn't reconnect")
            }
        }
    }

    @Test
    fun `the editor plays a particle effect in front of a player, and stops what it played`() {
        ServerSocket(0).use { listener ->
            TestServer(TestServer.example("showcase"), start = false, bridge = BridgeConfig(listener.localPort, "secret")).use { server ->
                // Facing east (yaw -90): three blocks in front of the eyes is +X.
                server.platform.players.add("Steve", Location("world", 10.0, 64.0, 0.0, -90.0, 0.0))
                server.start()
                val editor = connect(listener, server)
                editor.next<HelloParams>()

                // What the editor played: the runtime's own (the expo's booths play effects of their own, a script's).
                fun editorEffects() = server.runtime.session.particles.all().filter { it.owner == null }
                val played = editor.request(Bridge.playParticleEffect, PlayParticleEffectParams("sparkle", "Steve", loop = true))
                assertTrue(played.ok, "${played.error}")
                assertTrue(server.platform.log.lines.any { "Playing particle effect sparkle" in it }, "${server.platform.log.lines}")
                val effect = editorEffects().single()
                assertEquals(null, effect.owner, "the runtime's, not a script's")
                assertEquals(13.0, effect.origin.x, 1e-9)
                assertEquals(65.62, effect.origin.y, 1e-9)
                assertEquals(90.0, effect.yaw, 1e-9, "facing the player")
                server.tick(30)
                assertEquals(1, editorEffects().size, "the timeline's loop toggle loops it")

                val broken = editor.request(Bridge.playParticleEffect, PlayParticleEffectParams("nope"))
                assertTrue("no particle effect \"nope\"" in broken.error!!.message, "${broken.error}")

                assertTrue(editor.request(Bridge.stopParticleEffects, Unit).ok)
                assertEquals(emptyList(), editorEffects())
            }
        }
    }

    @Test
    fun `bot requests reach the platform's bots, a centity named by node aimed at its hitbox`() {
        ServerSocket(0).use { listener ->
            TestServer(TestServer.example("showcase"), bridge = BridgeConfig(listener.localPort, "secret")).use { server ->
                val editor = connect(listener, server)
                editor.next<HelloParams>()

                // Joining finishes ticks later; the answer waits for it.
                val joined = editor.request(BotsExtension.join, BotJoinParams("Tester", BotPosition(1.5, 64.5, 2.5)))
                assertTrue(joined.ok, "${joined.error}")
                assertEquals("Tester", joined.result!!.name)
                assertEquals(listOf("Tester"), editor.request(BotsExtension.list, Unit).result!!.map { it.name })

                val tower = server.runtime.spawnNear("tower", null)
                val top = tower.hitboxes.getValue("cap")
                val clicked = editor.request(
                    BotsExtension.act,
                    BotActParams("Tester", BotAction.Interact(centity = tower.id.toString(), node = "cap"))
                )
                assertTrue(clicked.ok, "${clicked.error}")
                val (_, _, aim) = server.platform.bots.acted.single()
                assertEquals(top, aim!!.entity)

                // Without a node: the first one that can be clicked.
                assertTrue(editor.request(BotsExtension.act, BotActParams("Tester", BotAction.Attack(centity = tower.id.toString()))).ok)
                assertEquals(tower.hitboxes.getValue("base"), server.platform.bots.acted.last().third!!.entity)

                val noNode = editor.request(
                    BotsExtension.act,
                    BotActParams("Tester", BotAction.Interact(centity = tower.id.toString(), node = "flag"))
                )
                assertEquals("node \"flag\" of centity tower has no hitbox to click", noNode.error!!.message)
                val noBot = editor.request(BotsExtension.act, BotActParams("Nobody", BotAction.Jump))
                assertEquals("no bot named \"Nobody\"", noBot.error!!.message)

                // What it looks at: a centity's hitbox is named by its instance and the node a click there reaches.
                val box = server.platform.entities.all.getValue(top)
                val look = BotAction.LookAt(box.location.x, box.location.y + box.height / 2, box.location.z)
                assertTrue(editor.request(BotsExtension.act, BotActParams("Tester", look)).ok)
                val seen = editor.request(BotsExtension.state, BotParams("Tester")).result!!.target
                assertEquals(top.toString(), seen?.entity, "$seen")
                assertEquals(tower.id.toString(), seen?.centity)
                assertEquals("cap", seen?.node)

                assertTrue(editor.request(BotsExtension.leave, BotParams("Tester")).ok)
                assertEquals(emptyList(), server.platform.bots.list())
            }
        }
    }

    @Test
    fun `the editor captures structures and worlds from player positions, loaded worlds, a box as bytes and a world as files`() {
        ServerSocket(0).use { listener ->
            val serverFolder = kotlin.io.path.createTempDirectory("nf-server")
            val config = BridgeConfig(listener.localPort, "secret", serverDirectory = serverFolder)
            TestServer(TestServer.example("showcase"), start = false, bridge = config).use { server ->
                server.platform.worldManager.storage = serverFolder.resolve("world")
                server.start()
                val editor = connect(listener, server)
                editor.next<HelloParams>()

                // Nobody online: no position to use.
                assertEquals("nobody is online", editor.request(Bridge.playerPosition, PlayerPositionParams()).error!!.message)
                server.platform.players.add("Steve", Location("world", 10.7, 64.0, -3.2))
                server.platform.players.add("Alex", Location("arena", -0.5, 70.9, 2.0))
                server.platform.worlds.worldNames += "arena"
                assertEquals(
                    PlayerPosition("Steve", "world", 10, 64, -4),
                    editor.request(Bridge.playerPosition, PlayerPositionParams()).result
                )
                assertEquals(
                    PlayerPosition("Alex", "arena", -1, 70, 2),
                    editor.request(Bridge.playerPosition, PlayerPositionParams("Alex")).result
                )
                assertEquals(
                    "no player \"Herobrine\" online",
                    editor.request(Bridge.playerPosition, PlayerPositionParams("Herobrine")).error!!.message
                )

                val loaded = editor.request(Bridge.worlds, Unit).result!!
                assertEquals(LoadedWorld("world", "normal", true), loaded.first())
                assertTrue(LoadedWorld("arena", "normal", false) in loaded, "$loaded")

                // A box's structure comes back as bytes; nothing is left in the data folder or the project.
                server.platform.worlds.blocks[BlockAt("world", 1, 64, 1)] = "minecraft:stone"
                val saved = editor.request(
                    Bridge.saveStructure,
                    SaveStructureParams("world", BlockPos(2, 65, 2), BlockPos(1, 64, 0), entities = true)
                )
                assertTrue(saved.ok, "${saved.error}")
                assertEquals(BlockPos(2, 2, 3), saved.result!!.size)
                assertEquals(
                    listOf("size 2 2 3", "0 0 1 minecraft:stone"),
                    String(java.util.Base64.getDecoder().decode(saved.result!!.nbt)).lines().filter { it.isNotEmpty() }
                )
                assertEquals(emptyList(), server.platform.log.lines.filter { "failed" in it })
                assertTrue(
                    java.nio.file.Files.list(server.project.resolve(".netherforge/data/.nf/captures")).use { !it.findAny().isPresent }
                )
                val tooBig = editor.request(Bridge.saveStructure, SaveStructureParams("world", BlockPos(0, 0, 0), BlockPos(48, 1, 1)))
                assertEquals("that box is 49×2×2 blocks; a structure is at most 48 along each side", tooBig.error!!.message)
                assertEquals(
                    "no world \"gone\" is loaded",
                    editor.request(Bridge.saveStructure, SaveStructureParams("gone", BlockPos(0, 0, 0), BlockPos(1, 1, 1))).error!!.message
                )

                // A world is saved, and where its files are is relative to the server's folder.
                server.platform.worlds.spawns["arena"] = Location("arena", 3.5, 71.0, -8.5, 90.0, 0.0)
                assertEquals(
                    SavedWorld("world/level.dat", "world/dimensions/minecraft/arena", false, WorldSpawn(3, 71, -9, 90.0, 0.0)),
                    editor.request(Bridge.saveWorld, SaveWorldParams("arena")).result
                )
                val main = editor.request(Bridge.saveWorld, SaveWorldParams("world")).result!!
                assertEquals("world/dimensions/minecraft/overworld" to true, main.dimension to main.main)
                assertEquals(listOf("arena", "world"), server.platform.worldManager.savedFiles)
                assertEquals("no world \"gone\" is loaded", editor.request(Bridge.saveWorld, SaveWorldParams("gone")).error!!.message)

                // Files outside the server's folder are never offered to the editor.
                server.platform.worldManager.storage = kotlin.io.path.createTempDirectory("elsewhere")
                assertTrue("isn't in the server's folder" in editor.request(Bridge.saveWorld, SaveWorldParams("world")).error!!.message)
            }
        }
    }

    @Test
    fun `a dropped connection is retried, and the editor gets a fresh hello`() {
        ServerSocket(0).use { listener ->
            TestServer(TestServer.example("showcase"), bridge = BridgeConfig(listener.localPort, "secret")).use { server ->
                listener.soTimeout = 10_000
                val first = listener.accept()
                val firstNonce = Editor(first, server).next<HelloParams>().nonce
                first.close()
                val again = connect(listener, server)
                assertTrue(again.next<HelloParams>().nonce != firstNonce, "a fresh nonce for each connection")
                assertTrue(again.request(Bridge.instances, Unit).ok)
            }
        }
    }

    @Test
    fun `a dev server whose editor is gone stops itself`() {
        ServerSocket(0).use { listener ->
            val config = BridgeConfig(listener.localPort, "secret", abandonAfterMillis = 500)
            TestServer(TestServer.example("showcase"), bridge = config).use { server ->
                listener.soTimeout = 10_000
                val editor = listener.accept()
                Editor(editor, server).next<HelloParams>()
                // The editor quits: the connection drops and its port goes away with it.
                editor.close()
                listener.close()
                assertTrue(eventually(server) { server.platform.shutdownReason != null }, "the server should have stopped itself")
                assertTrue(server.platform.log.lines.any { "has been gone" in it }, server.platform.log.lines.toString())
            }
        }
    }

    @Test
    fun `a dev server that never reaches its editor stops itself too`() {
        val port = ServerSocket(0).use { it.localPort }
        TestServer(TestServer.example("showcase"), bridge = BridgeConfig(port, "t", abandonAfterMillis = 500)).use { server ->
            assertTrue(eventually(server) { server.platform.shutdownReason != null }, "the server should have stopped itself")
        }
    }

    /** Polls [condition], running main-thread tasks as a real server would, for up to 10 s. */
    private fun eventually(server: TestServer, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            server.runMain()
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }
}
