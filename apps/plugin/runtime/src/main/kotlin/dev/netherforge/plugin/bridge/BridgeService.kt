package dev.netherforge.plugin.bridge

import dev.netherforge.format.bridge.BlockPos
import dev.netherforge.format.bridge.Bridge
import dev.netherforge.format.bridge.BridgeEvent
import dev.netherforge.format.bridge.BridgeMethod
import dev.netherforge.format.bridge.BridgeStream
import dev.netherforge.format.bridge.BridgeThread
import dev.netherforge.format.bridge.ConsoleEntry
import dev.netherforge.format.bridge.JsonRpc
import dev.netherforge.format.bridge.LoadedWorld
import dev.netherforge.format.bridge.PlayerPosition
import dev.netherforge.format.bridge.RpcFrame
import dev.netherforge.format.bridge.RpcInvalid
import dev.netherforge.format.bridge.RpcMessage
import dev.netherforge.format.bridge.RpcNotification
import dev.netherforge.format.bridge.RpcRequest
import dev.netherforge.format.bridge.RpcResponse
import dev.netherforge.format.bridge.SavedStructure
import dev.netherforge.format.bridge.SavedWorld
import dev.netherforge.format.bridge.WorldSpawn
import dev.netherforge.plugin.NetherForgeRuntime
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.platform.BlockVector
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.floor

/** How a request's handler answers: once, now or later, with its result or the failure. */
typealias Reply<R> = (Result<R>) -> Unit

/**
 * Answers the editor's requests. Frames arrive on the bridge's thread; each
 * request runs on the thread its method declares ([BridgeThread]): the main
 * thread, where everything the runtime touches lives, unless it's a control
 * request marked to run at once. Every request gets exactly one response:
 * its result, or an error (an unknown method, unreadable params, or the
 * handler's failure, whose sentence the editor shows).
 *
 * Methods are registered with [answer] and [handle]: the core protocol's
 * here, an extension's by whoever provides it ([BotsBridge]).
 */
class BridgeService(private val runtime: NetherForgeRuntime, private val config: BridgeConfig) : BridgeOutput {
    private val client = BridgeClient(
        port = config.port,
        hello = { runtime.hello(config.token) },
        onFrame = ::receive,
        onConnected = { runtime.mainThread.execute { runtime.sendState() } },
        // An editor that went away while the server was paused mustn't leave it paused.
        onDisconnected = { runtime.debugger?.detach() },
        onProblem = { runtime.platform.log.warn(it) },
        onRefused = { runtime.platform.log.error("The NetherForge editor refused this dev server's bridge: $it", null) },
        abandonAfterMillis = config.abandonAfterMillis,
        onAbandoned = {
            val seconds = config.abandonAfterMillis / 1000
            runtime.platform.log.warn(
                "The NetherForge editor that started this dev server has been gone for ${seconds}s; stopping the server"
            )
            runtime.mainThread.execute { runtime.platform.shutdownServer("NetherForge editor closed") }
        }
    )

    private class Handler(val method: BridgeMethod<*, *>, val run: (JsonElement?, (RpcResult) -> Unit) -> Unit)

    /** A handler's outcome, as it goes on the wire. */
    private sealed interface RpcResult {
        data class Ok(val result: JsonElement) : RpcResult

        data class Failed(val code: Int, val message: String) : RpcResult
    }

    private val handlers = ConcurrentHashMap<String, Handler>()

    init {
        answer(Bridge.reload) { runtime.reload(it.paths) }
        answer(Bridge.spawn) { params ->
            val player = params.player?.let { name ->
                runtime.platform.players.find(name) ?: throw IllegalArgumentException("no player \"$name\" online")
            }
            runtime.session.centities.info(runtime.spawnNear(params.centity, player))
        }
        answer(Bridge.instances) { runtime.instances() }
        answer(Bridge.exportGameData) { runtime.platform.exportGameData() }
        answer(Bridge.command) {
            check(runtime.platform.commands.runConsole(it.line.removePrefix("/"))) { "the server didn't run \"${it.line}\"" }
        }
        answer(Bridge.playParticleEffect) { params ->
            val player = params.player?.let { name ->
                runtime.platform.players.find(name) ?: throw IllegalArgumentException("no player \"$name\" online")
            }
            runtime.playParticleEffect(params.effect, player, params.loop)
        }
        answer(Bridge.stopParticleEffects) { runtime.stopParticleEffects() }
        answer(Bridge.playerPosition) { playerPosition(it.player) }
        answer(Bridge.worlds) { loadedWorlds() }
        answer(Bridge.saveStructure) { params ->
            val (bytes, size) = runtime.session.structures.capture(params.world, params.from.vector, params.to.vector, params.entities)
            SavedStructure(Base64.getEncoder().encodeToString(bytes), BlockPos(size.x, size.y, size.z))
        }
        answer(Bridge.saveWorld) { saveWorld(it.world) }
        answer(Bridge.settings) { runtime.settings() }
        answer(Bridge.setSetting) { params ->
            runtime.session.settings.set(params.namespace, params.setting, params.value)
            runtime.settings()
        }
        answer(Bridge.ping) { }
        // On the bridge's thread: it only sets a flag the main thread reads.
        answer(Bridge.profilerSubscribe) { runtime.profiler.streaming = it.on }
        // An extension, outside the core protocol: `bots/…`.
        BotsBridge(runtime, runtime.platform.bots).install(this)
    }

    fun start() = client.start()

    fun stop() = client.stop()

    override fun console(entry: ConsoleEntry) = client.stream(Bridge.console, entry)

    override fun <T> stream(stream: BridgeStream<T>, item: T) = client.stream(stream, item)

    override fun <P> notify(event: BridgeEvent<P>, params: P) = client.notify(event, params)

    /**
     * Sends one of the debugger's DAP messages. Only to an editor that's
     * connected: a DAP session is the connection's, and the next one starts
     * its own, so nothing is kept for it.
     */
    fun dap(message: JsonElement) {
        if (client.connected) client.notify(Bridge.dap, message)
    }

    /** Answers [method] with what [block] returns (or throws), on the method's thread. */
    fun <P, R> answer(method: BridgeMethod<P, R>, block: (P) -> R) =
        handle(method) { params, reply -> reply(runCatching { block(params) }) }

    /** Answers [method] when [block] calls its reply, now or ticks later (on the main thread). */
    fun <P, R> handle(method: BridgeMethod<P, R>, block: (P, Reply<R>) -> Unit) {
        check(handlers.putIfAbsent(method.name, Handler(method) { json, done -> run(method, json, block, done) }) == null) {
            "$method is handled twice"
        }
    }

    private fun <P, R> run(method: BridgeMethod<P, R>, json: JsonElement?, block: (P, Reply<R>) -> Unit, done: (RpcResult) -> Unit) {
        val params = try {
            method.decodeParams(json)
        } catch (e: Exception) {
            return done(RpcResult.Failed(JsonRpc.INVALID_PARAMS, "Invalid params for $method: ${e.message}"))
        }
        val answered = java.util.concurrent.atomic.AtomicBoolean(false)
        val reply: Reply<R> = { result ->
            if (answered.compareAndSet(false, true)) {
                done(result.fold({ RpcResult.Ok(method.encodeResult(it)) }, { failure(method, it) }))
            }
        }
        try {
            block(params, reply)
        } catch (e: Exception) {
            reply(Result.failure(e))
        }
    }

    private fun failure(method: BridgeMethod<*, *>, e: Throwable): RpcResult {
        val message = e.message ?: e::class.simpleName ?: "failed"
        return if (e is IllegalArgumentException || e is IllegalStateException || e is LuaApiException) {
            RpcResult.Failed(Bridge.REQUEST_FAILED, message)
        } else {
            runtime.log.error("Bridge request $method failed", e)
            RpcResult.Failed(JsonRpc.INTERNAL_ERROR, message)
        }
    }

    /** One frame from the editor, on the bridge's thread. Internal for tests, which drive it without a socket. */
    internal fun receive(frame: RpcFrame) {
        when (frame) {
            is RpcFrame.Single -> dispatch(frame.message, client::send)
            is RpcFrame.Batch -> {
                // A batch is answered with one array of its responses, once all are in; notifications have none.
                val expected = frame.messages.count { it is RpcRequest || it is RpcInvalid }
                if (expected == 0) {
                    frame.messages.forEach { dispatch(it) {} }
                    return
                }
                val responses = arrayOfNulls<RpcResponse>(frame.messages.size)
                val left = AtomicInteger(expected)
                frame.messages.forEachIndexed { index, message ->
                    dispatch(message) { response ->
                        responses[index] = response
                        if (left.decrementAndGet() == 0) client.send(responses.filterNotNull())
                    }
                }
            }
        }
    }

    private fun dispatch(message: RpcMessage, reply: (RpcResponse) -> Unit) {
        when (message) {
            is RpcRequest -> request(message, reply)
            is RpcInvalid -> reply(RpcResponse(message.id, error = message.error))
            // The debugger's channel is the only notification the plugin acts on; the editor answers only the hello.
            is RpcNotification -> if (message.method == Bridge.dap.name) message.params?.let { runtime.debugger?.receive(it) }
            is RpcResponse -> Unit
        }
    }

    private fun request(request: RpcRequest, reply: (RpcResponse) -> Unit) {
        val handler = handlers[request.method]
            ?: return reply(RpcResponse.error(request.id, JsonRpc.METHOD_NOT_FOUND, "Unknown method \"${request.method}\""))
        val respond: (RpcResult) -> Unit = { result -> reply(response(request.id, result)) }
        when (handler.method.thread) {
            BridgeThread.BRIDGE -> handler.run(request.params, respond)
            // While the debugger holds the main thread nothing queued for it would run: say why at once.
            BridgeThread.MAIN -> when (val paused = runtime.debugger?.refusal()) {
                null -> runtime.mainThread.execute { handler.run(request.params, respond) }
                else -> respond(RpcResult.Failed(Bridge.REQUEST_FAILED, paused))
            }
        }
    }

    private fun response(id: JsonPrimitive, result: RpcResult) = when (result) {
        is RpcResult.Ok -> RpcResponse.ok(id, result.result)
        is RpcResult.Failed -> RpcResponse.error(id, result.code, result.message)
    }

    /** Where [name] (or the first player online) stands, to the block. */
    private fun playerPosition(name: String?): PlayerPosition {
        val players = runtime.platform.players
        val player = if (name != null) {
            players.find(name) ?: throw IllegalArgumentException("no player \"$name\" online")
        } else {
            players.online().firstOrNull() ?: throw IllegalStateException("nobody is online")
        }
        val at = players.location(player.uuid) ?: throw IllegalStateException("${player.name} isn't in a world")
        return PlayerPosition(player.name, at.world, floor(at.x).toInt(), floor(at.y).toInt(), floor(at.z).toInt())
    }

    /** The loaded worlds, the main one first. */
    private fun loadedWorlds(): List<LoadedWorld> {
        val worlds = runtime.platform.worlds
        val main = worlds.defaultWorld()
        return worlds.names().sortedBy { it != main }.map { LoadedWorld(it, worlds.environment(it) ?: "normal", it == main) }
    }

    /**
     * Saves [name] to disk and answers where its files are, relative to the
     * server's folder. Files elsewhere (another world container) are refused:
     * the editor only copies from its dev server's folder.
     */
    private fun saveWorld(name: String): SavedWorld {
        val worlds = runtime.platform.worlds
        val files = runtime.platform.worldManager.saveFiles(name) ?: throw IllegalArgumentException("no world \"$name\" is loaded")
        val spawn = worlds.spawnLocation(name) ?: throw IllegalStateException("world \"$name\" has no spawn")
        return SavedWorld(
            level = relative(files.level),
            dimension = relative(files.dimension),
            main = name == worlds.defaultWorld(),
            spawn = WorldSpawn(floor(spawn.x).toInt(), floor(spawn.y).toInt(), floor(spawn.z).toInt(), spawn.yaw, spawn.pitch)
        )
    }

    private fun relative(path: Path): String {
        val root = config.serverDirectory.toAbsolutePath().normalize()
        val absolute = path.toAbsolutePath().normalize()
        check(absolute.startsWith(root) && absolute != root) { "$absolute isn't in the server's folder ($root)" }
        return root.relativize(absolute).joinToString("/")
    }

    private val BlockPos.vector get() = BlockVector(x, y, z)
}
