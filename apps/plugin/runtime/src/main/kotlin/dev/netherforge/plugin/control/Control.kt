package dev.netherforge.plugin.control

import dev.netherforge.network.Auth
import dev.netherforge.plugin.NetherForgeRuntime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.UnixDomainSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** `control:` in `config.yml`: where the local control endpoint listens, if anywhere. */
data class ControlConfig(
    /** A Unix domain socket's path: who can open the file may use it. */
    val socket: Path? = null,
    /** A loopback TCP port, for a tool that can't use a socket; requests carry the token in [tokenFile]. */
    val port: Int? = null,
    val tokenFile: Path? = null,
    /** How long without a completed tick before the server is stalled: `/healthz` fails and `/events` says so. */
    val stallSeconds: Int = DEFAULT_STALL_SECONDS
) {
    val enabled get() = socket != null || port != null

    companion object {
        const val DEFAULT_STALL_SECONDS = 10
    }
}

/**
 * The server's local control endpoint, for the tool that runs it (a container's sidecar, a host's agent): its state,
 * a stream of what changes, and draining, shutting down, saving and pausing writes. Only on a Unix socket or on
 * `127.0.0.1` with a token: never reachable from elsewhere. Every action is logged with where it came from.
 *
 * What it reports is a snapshot the main thread makes every few ticks; actions run on the main thread and are
 * answered once done. Its health doesn't: when the last tick ended ([lastTick]) is kept every tick and read by the
 * endpoint's own threads, so a main thread that's stuck (an endless script, a deadlock) shows as stalled, not healthy.
 */
class Control(private val config: ControlConfig, private val runtime: NetherForgeRuntime) {
    private var http: LocalHttp? = null
    private var token: String? = null

    @Volatile
    private var state: JsonObject = JsonObject(emptyMap())
    private val listeners = CopyOnWriteArrayList<LinkedBlockingQueue<String>>()

    /** What the last snapshot held, to tell listeners what changed. */
    private var last: Snapshot? = null

    private data class Snapshot(val state: String, val players: Set<String>, val worlds: Set<String>, val paused: Boolean)

    /** Writes paused (`POST /save` with `pause`): until this tick at the latest. */
    private var pausedUntil: Long? = null

    /** When the last tick ended (Unix milliseconds; 0 before the first), and how long it took. */
    @Volatile
    private var lastTick = 0L

    @Volatile
    private var tickMillis = 0.0

    /** Watches for ticks stopping, off the main thread. */
    private var watchdog: java.util.concurrent.ScheduledExecutorService? = null

    /** Whether the last check found it stalled, so `stalled` is said once a stall. */
    private var stalled = false

    /** When the server last had players on it, by tick. */
    private var emptySince: Long? = null

    val where: String
        get() = config.socket?.let { "the socket $it" }
            ?: "127.0.0.1:${(http?.boundTo as? java.net.InetSocketAddress)?.port ?: config.port}"

    fun start() {
        if (!config.enabled) return
        val address = config.socket?.let { UnixDomainSocketAddress.of(it) } ?: LocalHttp.loopback(config.port!!)
        if (config.socket == null) token = readToken(config.tokenFile!!)
        val server = LocalHttp(address, ::handle)
        try {
            server.start()
        } catch (e: Exception) {
            runtime.log.error("NetherForge's control endpoint couldn't listen on ${config.socket ?: "127.0.0.1:${config.port}"}", e)
            return
        }
        http = server
        watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "NetherForge control watchdog").apply { isDaemon = true }
        }.also { it.scheduleWithFixedDelay(::watch, 1, 1, TimeUnit.SECONDS) }
        runtime.log.info("NetherForge's control endpoint is listening on $where")
    }

    fun stop() {
        if (pausedUntil != null) resume("the server is stopping")
        watchdog?.shutdownNow()
        watchdog = null
        http?.stop()
        http = null
        for (queue in listeners) queue.offer(END)
        listeners.clear()
    }

    /** The main thread, every few ticks: a new snapshot, what changed to listeners, and an expired pause ended. */
    fun tick(tick: Long) {
        if (http == null) return
        tickMillis = runtime.platform.performance.lastTickMilliseconds()
        lastTick = System.currentTimeMillis()
        if (tick % SNAPSHOT_TICKS != 0L) return
        val players = runtime.platform.players.online().map { it.name }.toSet()
        emptySince = if (players.isEmpty()) emptySince ?: tick else null
        val worlds = runtime.platform.worlds.names().toSet()
        val until = pausedUntil
        if (until != null) {
            if (tick >= until) {
                resume("its timeout ran out")
            } else {
                // A world loaded while writes are paused is paused too.
                for (world in worlds - last?.worlds.orEmpty()) runtime.platform.worldManager.setSaving(world, false)
            }
        }
        val now = Snapshot(runtime.network.state.word, players, worlds, pausedUntil != null)
        state = snapshot(now, tick)
        val before = last
        last = now
        if (before == null) return
        if (before.state != now.state) {
            tell(
                "state",
                buildJsonObject {
                    put("state", now.state)
                    put("previous", before.state)
                }
            )
        }
        for (player in now.players - before.players) tell("player_join", buildJsonObject { put("player", player) })
        for (player in before.players - now.players) tell("player_quit", buildJsonObject { put("player", player) })
        for (world in now.worlds - before.worlds) tell("world_load", buildJsonObject { put("world", world) })
        for (world in before.worlds - now.worlds) tell("world_unload", buildJsonObject { put("world", world) })
        if (before.paused != now.paused) tell("writes", buildJsonObject { put("paused", now.paused) })
    }

    private fun snapshot(now: Snapshot, tick: Long): JsonObject {
        val manifest = runtime.session.snapshot.manifest
        return buildJsonObject {
            put("state", now.state)
            put("players", now.players.size)
            putJsonArray("player_names") { now.players.sorted().forEach { add(JsonPrimitive(it)) } }
            emptySince?.let { put("empty_seconds", (tick - it) / TICKS_PER_SECOND) }
            put("idle", runtime.network.idle)
            runtime.network.startupMillis?.let { put("startup_millis", it) }
            runtime.network.name?.let { put("server", it) }
            runtime.network.group?.let { put("group", it) }
            manifest?.let {
                put("project", it.name)
                put("version", it.version)
            }
            putJsonArray("worlds") { now.worlds.sorted().forEach { add(JsonPrimitive(it)) } }
            put("writes_paused", now.paused)
        }
    }

    /** Whether a tick has ended within [ControlConfig.stallSeconds]. */
    private fun ticking(now: Long = System.currentTimeMillis()) = lastTick != 0L && now - lastTick < config.stallSeconds * 1000L

    /** The watchdog's thread, every second: `stalled` once ticks stop (after the first), and back to ticking. */
    private fun watch() {
        if (lastTick == 0L) return
        val now = ticking()
        if (!now && !stalled) {
            runtime.log.warn("NetherForge: no tick has completed for ${config.stallSeconds} seconds; the server is stalled")
            tell(
                "stalled",
                buildJsonObject {
                    put("last_tick", lastTick)
                    put("seconds", (System.currentTimeMillis() - lastTick) / 1000)
                }
            )
        }
        stalled = !now
    }

    /** The snapshot, with the tick's health as it is now (the snapshot may be old if ticks stopped). */
    private fun stateNow(): JsonObject = JsonObject(
        state + buildJsonObject {
            if (lastTick != 0L) put("last_tick", lastTick)
            put("tick_ms", Math.round(tickMillis * 100) / 100.0)
            put("stalled", lastTick != 0L && !ticking())
        }
    )

    private fun tell(event: String, data: JsonObject) {
        val text = "event: $event\ndata: $data\n\n"
        for (queue in listeners) if (!queue.offer(text)) listeners.remove(queue)
    }

    // ---- requests ----------------------------------------------------------------

    /** An action the server refused: answered 409 with why. */
    private class Refused(message: String) : Exception(message)

    private fun handle(request: LocalHttp.Request, respond: LocalHttp.Responder) {
        try {
            route(request, respond)
        } catch (e: Refused) {
            respond.send(409, """{"error":${LocalHttp.quote(e.message.orEmpty())}}""")
        } catch (e: java.util.concurrent.ExecutionException) {
            respond.send(409, """{"error":${LocalHttp.quote(e.cause?.message ?: e.toString())}}""")
        }
    }

    private fun route(request: LocalHttp.Request, respond: LocalHttp.Responder) {
        // Health is for probes, which may send no token, and says only whether the server ticks.
        if (request.method == "GET" && request.path == "/healthz") {
            return if (ticking()) {
                respond.send(200, """{"healthy":true}""")
            } else {
                respond.send(503, """{"healthy":false,"why":"${if (lastTick == 0L) "starting" else "stalled"}"}""")
            }
        }
        token?.let { expected ->
            val given = request.header("authorization")?.removePrefix("Bearer ")?.trim().orEmpty()
            if (!MessageDigest.isEqual(given.toByteArray(), expected.toByteArray())) {
                return respond.send(401, """{"error":"a request needs Authorization: Bearer <the token in ${config.tokenFile}>"}""")
            }
        }
        val body = request.body.takeIf { it.isNotBlank() }?.let {
            try {
                Json.parseToJsonElement(it).jsonObject
            } catch (_: Exception) {
                return respond.send(400, """{"error":"the body isn't a JSON object"}""")
            }
        } ?: JsonObject(emptyMap())
        fun flag(key: String) = (body[key] as? JsonPrimitive)?.booleanOrNull
        fun number(key: String) = (body[key] as? JsonPrimitive)?.doubleOrNull
        when (request.method to request.path) {
            "GET" to "/state" -> respond.send(200, stateNow().toString())
            "GET" to "/events" -> events(respond)
            "POST" to "/drain" -> {
                val draining = flag("draining") ?: true
                act("drain${if (draining) "" else " off"}") { runtime.network.setDraining(draining) }
                respond.send(200, """{"state":"${runtime.network.state.word}"}""")
            }
            "POST" to "/shutdown" -> {
                val afterEmpty = flag("after_empty") ?: false
                val timeout = number("timeout")?.toLong()?.takeIf { it > 0 } ?: DEFAULT_SHUTDOWN_SECONDS
                act("shutdown${if (afterEmpty) " after empty" else ""}, $timeout seconds") {
                    runtime.session.network.shutdown(afterEmpty, timeout)
                }
                respond.send(200, """{"state":"${runtime.network.state.word}"}""")
            }
            "POST" to "/save" -> {
                val pause = flag("pause") ?: false
                val timeout = number("timeout")?.toLong()?.takeIf { it > 0 } ?: DEFAULT_PAUSE_SECONDS
                val saved = act("save${if (pause) ", pausing writes for up to $timeout seconds" else ""}") { save(pause, timeout) }
                respond.send(
                    200,
                    buildJsonObject {
                        putJsonArray("saved") { saved.forEach { add(JsonPrimitive(it)) } }
                        put("writes_paused", pause)
                    }.toString()
                )
            }
            "POST" to "/worlds/load" -> {
                val name = (body["name"] as? JsonPrimitive)?.content ?: return respond.send(400, """{"error":"a load names its world"}""")
                val folder = (body["folder"] as? JsonPrimitive)?.content
                val terrain = (body["terrain"] as? JsonPrimitive)?.content
                val loading = act("load world $name" + folder?.let { " from $it" }.orEmpty()) {
                    val session = runtime.session
                    if (folder != null) {
                        session.managedWorlds.import(folder, name, terrain?.let { dev.netherforge.plugin.api.projectTerrain(session, it) })
                            .toCompletableFuture()
                    } else {
                        CompletableFuture.completedFuture(session.managedWorlds.load(name))
                    }
                }
                val loaded = loading.get(LOAD_SECONDS, TimeUnit.SECONDS)
                if (loaded == false) return respond.send(404, """{"error":"the server has no world saved as $name"}""")
                respond.send(200, """{"world":${LocalHttp.quote(name)}}""")
            }
            "POST" to "/worlds/unload" -> {
                val name =
                    (body["name"] as? JsonPrimitive)?.content ?: return respond.send(400, """{"error":"an unload names its world"}""")
                val moveTo = (body["move_players_to"] as? JsonPrimitive)?.content
                val unloaded = act("unload world $name") {
                    runtime.session.managedWorlds.unload(name, flag("save") ?: true, flag("delete") ?: false, moveTo)
                }
                if (!unloaded) return respond.send(409, """{"error":"$name had gone, or the server kept it"}""")
                respond.send(200, """{"world":${LocalHttp.quote(name)}}""")
            }
            "POST" to "/resume" -> {
                act("resume writes") { resume(null) }
                respond.send(200, """{"writes_paused":false}""")
            }
            else -> respond.send(404, """{"error":"there's no ${request.method} ${request.path}"}""")
        }
    }

    /** The stream of changes, as Server-Sent Events, until the tool goes or the server stops. */
    private fun events(respond: LocalHttp.Responder) {
        val queue = LinkedBlockingQueue<String>(MAX_QUEUED)
        listeners += queue
        try {
            val out = respond.stream()
            out.write("event: state\ndata: ${stateNow()}\n\n".toByteArray())
            out.flush()
            while (true) {
                val next = queue.poll(PING_SECONDS, TimeUnit.SECONDS) ?: ": ping\n\n"
                if (next === END) break
                out.write(next.toByteArray())
                out.flush()
            }
        } finally {
            listeners.remove(queue)
        }
    }

    /** [what], logged with where it came from, done on the main thread; what refuses it is the request's failure. */
    private fun <T> act(what: String, block: () -> T): T {
        val done = CompletableFuture.supplyAsync({
            runtime.log.info("Control endpoint ($where): $what")
            block()
        }, runtime.mainThread)
        val result = try {
            done.get(ACT_SECONDS, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw Refused(e.cause?.message ?: e.toString())
        }
        // The snapshot shows it at once.
        runtime.mainThread.execute {
            last?.let {
                state =
                    snapshot(it.copy(state = runtime.network.state.word, paused = pausedUntil != null), runtime.ticks)
            }
        }
        return result
    }

    /** On the main thread: every world saved and flushed, and with [pause] its writes paused for up to [seconds]. */
    private fun save(pause: Boolean, seconds: Long): List<String> {
        val worlds = runtime.platform.worlds.names()
        for (world in worlds) runtime.platform.worldManager.saveFiles(world)
        if (pause) {
            for (world in worlds) runtime.platform.worldManager.setSaving(world, false)
            pausedUntil = runtime.ticks + seconds * TICKS_PER_SECOND
        }
        return worlds
    }

    /** On the main thread: writes resume (said in the log when not asked: [why]). */
    private fun resume(why: String?) {
        if (pausedUntil == null) return
        pausedUntil = null
        for (world in runtime.platform.worlds.names()) runtime.platform.worldManager.setSaving(world, true)
        if (why != null) runtime.log.info("Control endpoint: writes resume: $why")
    }

    /** The token in [file], made (readable by the server's own user only) when there's none yet. */
    private fun readToken(file: Path): String {
        if (Files.exists(file)) {
            return Files.readString(file).trim().also {
                require(it.length >= 24) { "$file's token is shorter than 24 characters" }
            }
        }
        Files.createDirectories(file.toAbsolutePath().parent)
        val token = Auth.newSecret()
        Files.writeString(file, token + "\n")
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
        } catch (_: UnsupportedOperationException) {
        }
        runtime.log.info("NetherForge: wrote a new control token to $file")
        return token
    }

    private companion object {
        const val TICKS_PER_SECOND = 20L
        const val SNAPSHOT_TICKS = 5L
        const val MAX_QUEUED = 1000
        const val PING_SECONDS = 15L
        const val ACT_SECONDS = 30L

        /** How long a world's import may take, its files' copy included. */
        const val LOAD_SECONDS = 120L
        const val DEFAULT_SHUTDOWN_SECONDS = 60L
        const val DEFAULT_PAUSE_SECONDS = 300L

        /** What ends a stream: the server stopping. */
        val END = String(charArrayOf('\u0000'))
    }
}
