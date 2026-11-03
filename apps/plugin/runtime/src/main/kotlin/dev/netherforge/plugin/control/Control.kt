package dev.netherforge.plugin.control

import dev.netherforge.network.Auth
import dev.netherforge.plugin.NetherForgeRuntime
import dev.netherforge.plugin.session.RefusedProject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
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

    /** A player online: their name and the world they're in (null: not known yet). */
    private data class PlayerView(val name: String, val world: String?)

    private data class Snapshot(
        val state: String,
        /** Whether the project's scripts run, and why not when it's refused. */
        val running: Boolean,
        val refused: RefusedProject?,
        val players: Map<java.util.UUID, PlayerView>,
        val worlds: Set<String>,
        /** Whether every world's writes are paused (`POST /save` with `pause` and no world). */
        val paused: Boolean,
        /** The worlds paused on their own (`POST /save` naming one). */
        val pausedWorlds: Set<String>
    )

    /** Every world's writes paused (`POST /save` with `pause`): until this tick at the latest. */
    private var pausedUntil: Long? = null

    /**
     * Worlds whose writes are paused on their own, each until its tick at the latest. A world's writes go on when
     * neither this nor [pausedUntil] holds it.
     */
    private val worldPauses = HashMap<String, Long>()

    /** When each loaded world last had no players in it, by tick (absent while it has some). */
    private val worldEmptySince = HashMap<String, Long>()

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
        if (pausedUntil != null || worldPauses.isNotEmpty()) {
            for (world in worldPauses.keys.toList()) resumeWorld(world, null)
            resume("the server is stopping")
        }
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
        val players = runtime.platform.players.online().associate {
            it.uuid to PlayerView(it.name, runtime.platform.players.location(it.uuid)?.world)
        }
        emptySince = if (players.isEmpty()) emptySince ?: tick else null
        val worlds = runtime.platform.worlds.names().toSet()
        val occupied = players.values.mapNotNull { it.world }.toSet()
        worldEmptySince.keys.retainAll(worlds)
        for (world in worlds) if (world in occupied) worldEmptySince.remove(world) else worldEmptySince.putIfAbsent(world, tick)
        // A world unloaded while paused: its pause ends with it.
        worldPauses.keys.retainAll(worlds)
        for ((world, until) in worldPauses.entries.toList()) if (tick >= until) resumeWorld(world, "its timeout ran out")
        val until = pausedUntil
        if (until != null) {
            if (tick >= until) {
                resume("its timeout ran out")
            } else {
                // A world loaded while writes are paused is paused too.
                for (world in worlds - last?.worlds.orEmpty()) runtime.platform.worldManager.setSaving(world, false)
            }
        }
        val session = runtime.session
        val now = Snapshot(
            runtime.network.state.word,
            session.running,
            session.refused,
            players,
            worlds,
            pausedUntil != null,
            worldPauses.keys.toSet()
        )
        state = snapshot(now, tick)
        val before = last
        last = now
        if (before == null) return
        if (before.state != now.state || before.running != now.running || before.refused != now.refused) {
            tell(
                "state",
                buildJsonObject {
                    put("state", now.state)
                    put("previous", before.state)
                    running(now)
                }
            )
        }
        fun player(uuid: java.util.UUID, view: PlayerView) = buildJsonObject {
            put("player", view.name)
            put("uuid", uuid.toString())
            view.world?.let { put("world", it) }
        }
        for ((uuid, view) in now.players) {
            val was = before.players[uuid]
            when {
                was == null -> tell("player_join", player(uuid, view))
                was.world != null && view.world != null && was.world != view.world -> tell(
                    "player_world",
                    buildJsonObject {
                        put("player", view.name)
                        put("uuid", uuid.toString())
                        put("from", was.world)
                        put("to", view.world)
                    }
                )
            }
        }
        for ((uuid, view) in before.players) if (uuid !in now.players) tell("player_quit", player(uuid, view))
        for (world in now.worlds - before.worlds) tell("world_load", buildJsonObject { put("world", world) })
        for (world in before.worlds - now.worlds) tell("world_unload", buildJsonObject { put("world", world) })
        if (before.paused != now.paused) tell("writes", buildJsonObject { put("paused", now.paused) })
        for (world in (now.pausedWorlds - before.pausedWorlds) + (before.pausedWorlds - now.pausedWorlds)) {
            tell(
                "writes",
                buildJsonObject {
                    put("world", world)
                    put("paused", world in now.pausedWorlds)
                }
            )
        }
    }

    private fun snapshot(now: Snapshot, tick: Long): JsonObject {
        val manifest = runtime.session.snapshot.manifest
        return buildJsonObject {
            put("state", now.state)
            put("player_count", now.players.size)
            putJsonArray("players") {
                for ((uuid, view) in now.players.entries.sortedBy { it.value.name.lowercase() }) {
                    add(
                        buildJsonObject {
                            put("uuid", uuid.toString())
                            put("name", view.name)
                            view.world?.let { put("world", it) }
                        }
                    )
                }
            }
            putJsonArray("player_names") { now.players.values.map { it.name }.sorted().forEach { add(JsonPrimitive(it)) } }
            emptySince?.let { put("empty_seconds", (tick - it) / TICKS_PER_SECOND) }
            put("idle", runtime.network.idle)
            runtime.network.startupMillis?.let { put("startup_millis", it) }
            runtime.network.name?.let { put("server", it) }
            runtime.network.group?.let { put("group", it) }
            manifest?.let {
                put("project", it.name)
                put("version", it.version)
            }
            running(now)
            val counts = now.players.values.mapNotNull { it.world }.groupingBy { it }.eachCount()
            putJsonArray("worlds") {
                for (world in now.worlds.sorted()) {
                    add(
                        buildJsonObject {
                            put("name", world)
                            put("players", counts[world] ?: 0)
                            worldEmptySince[world]?.let { put("empty_seconds", (tick - it) / TICKS_PER_SECOND) }
                            if (now.paused || world in now.pausedWorlds) put("writes_paused", true)
                        }
                    )
                }
            }
            put("writes_paused", now.paused || now.pausedWorlds.isNotEmpty())
            putJsonArray("paused_worlds") {
                (if (now.paused) now.worlds else now.pausedWorlds).sorted().forEach { add(JsonPrimitive(it)) }
            }
        }
    }

    /** Whether the project runs (`running`), and why not when it's refused (`refused`: its problem's code and message). */
    private fun JsonObjectBuilder.running(now: Snapshot) {
        put("running", now.running)
        now.refused?.let { refused ->
            put(
                "refused",
                buildJsonObject {
                    refused.code?.let { put("code", it) }
                    put("message", refused.message)
                }
            )
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

    /** What the request names isn't there: answered 404. */
    private class Missing(message: String) : Exception(message)

    /** A request that's wrong in itself: answered 400. */
    private class Wrong(message: String) : Exception(message)

    private fun handle(request: LocalHttp.Request, respond: LocalHttp.Responder) {
        try {
            route(request, respond)
        } catch (e: Refused) {
            respond.send(409, """{"error":${LocalHttp.quote(e.message.orEmpty())}}""")
        } catch (e: Missing) {
            respond.send(404, """{"error":${LocalHttp.quote(e.message.orEmpty())}}""")
        } catch (e: Wrong) {
            respond.send(400, """{"error":${LocalHttp.quote(e.message.orEmpty())}}""")
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
        fun text(key: String) = (body[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
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
                val world = text("world")
                val timeout = number("timeout")?.toLong()?.takeIf { it > 0 } ?: DEFAULT_PAUSE_SECONDS
                val what = "save${world?.let { " world $it" }.orEmpty()}" +
                    if (pause) ", pausing ${if (world == null) "writes" else "its writes"} for up to $timeout seconds" else ""
                val saved = act(what) { save(world, pause, timeout) }
                respond.send(
                    200,
                    buildJsonObject {
                        world?.let { put("world", it) }
                        putJsonArray("saved") { saved.forEach { add(JsonPrimitive(it)) } }
                        put("writes_paused", pause)
                    }.toString()
                )
            }
            "POST" to "/worlds/create" -> {
                val name = text("name") ?: return respond.send(400, """{"error":"a create names its world"}""")
                val options = createOptions(body)
                act("create world $name") {
                    val session = runtime.session
                    // What's wrong with the request is a 400; a world that can't be made now (its name taken, too
                    // many loaded) is a 409, as nf.worlds.create's errors say.
                    try {
                        options.terrain?.let { dev.netherforge.plugin.api.projectTerrain(session, it) }
                        options.dimensionType?.let { dev.netherforge.plugin.api.projectDimensionType(session, it) }
                    } catch (e: dev.netherforge.plugin.lua.LuaApiException) {
                        throw Wrong(e.message.orEmpty())
                    }
                    dev.netherforge.plugin.api.createWorld(session, name, options)
                        ?: throw Refused("the server couldn't make world $name")
                }
                respond.send(200, """{"world":${LocalHttp.quote(name)}}""")
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
                    // Its own pause ends, so it's saved as an unload saves it.
                    if (worldPauses.remove(name) != null && pausedUntil == null) runtime.platform.worldManager.setSaving(name, true)
                    runtime.session.managedWorlds.unload(name, flag("save") ?: true, flag("delete") ?: false, moveTo)
                }
                if (!unloaded) return respond.send(409, """{"error":"$name had gone, or the server kept it"}""")
                respond.send(200, """{"world":${LocalHttp.quote(name)}}""")
            }
            "POST" to "/resume" -> {
                val world = text("world")
                // Whether writes are still paused where it asked: worlds paused on their own, or the whole server's.
                val paused = if (world == null) {
                    act("resume writes") {
                        resume(null)
                        worldPauses.isNotEmpty()
                    }
                } else {
                    act("resume world $world's writes") {
                        if (world !in runtime.platform.worlds.names()) throw Missing("no world \"$world\" is loaded")
                        resumeWorld(world, null)
                        pausedUntil != null
                    }
                }
                respond.send(
                    200,
                    buildJsonObject {
                        world?.let { put("world", it) }
                        put("writes_paused", paused)
                    }.toString()
                )
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
            when (val cause = e.cause) {
                is Missing, is Wrong, is Refused -> throw cause
                else -> throw Refused(cause?.message ?: e.toString())
            }
        }
        // The snapshot shows it at once.
        runtime.mainThread.execute {
            last?.let {
                state =
                    snapshot(
                        it.copy(state = runtime.network.state.word, paused = pausedUntil != null, pausedWorlds = worldPauses.keys.toSet()),
                        runtime.ticks
                    )
            }
        }
        return result
    }

    /**
     * On the main thread: every world (or only [world]) saved and flushed, and with [pause] its writes paused for up to
     * [seconds]. A world's pause and the whole server's are kept apart, each ending on its own.
     */
    private fun save(world: String?, pause: Boolean, seconds: Long): List<String> {
        val loaded = runtime.platform.worlds.names()
        if (world != null && world !in loaded) throw Missing("no world \"$world\" is loaded")
        val worlds = if (world == null) loaded else listOf(world)
        for (each in worlds) runtime.platform.worldManager.saveFiles(each)
        if (pause) {
            for (each in worlds) runtime.platform.worldManager.setSaving(each, false)
            val until = runtime.ticks + seconds * TICKS_PER_SECOND
            if (world == null) pausedUntil = until else worldPauses[world] = until
        }
        return worlds
    }

    /** On the main thread: every world's pause ends (said in the log when not asked: [why]); worlds paused on their own stay. */
    private fun resume(why: String?) {
        if (pausedUntil == null) return
        pausedUntil = null
        for (world in runtime.platform.worlds.names()) {
            if (world !in worldPauses) runtime.platform.worldManager.setSaving(world, true)
        }
        if (why != null) runtime.log.info("Control endpoint: writes resume: $why")
    }

    /** On the main thread: [world]'s own pause ends; its writes go on unless the whole server's are paused. */
    private fun resumeWorld(world: String, why: String?) {
        if (worldPauses.remove(world) == null) return
        if (pausedUntil == null) runtime.platform.worldManager.setSaving(world, true)
        if (why != null) runtime.log.info("Control endpoint: world $world's writes resume: $why")
    }

    /** `POST /worlds/create`'s body as `nf.worlds.create` takes it; a value that isn't one is a 400. */
    private fun createOptions(body: JsonObject): dev.netherforge.plugin.binding.WorldCreateOptions {
        fun text(key: String): String? {
            val value = body[key] ?: return null
            return (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw Wrong("$key is a string")
        }
        fun flag(key: String): Boolean? {
            val value = body[key] ?: return null
            return (value as? JsonPrimitive)?.booleanOrNull ?: throw Wrong("$key is true or false")
        }
        fun choice(key: String, choices: List<String>) = text(key)?.also {
            if (it !in choices) throw Wrong("$key \"$it\" isn't ${choices.joinToString(", ")}")
        }
        val seed = body["seed"]?.let { value ->
            (value as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull() ?: throw Wrong("seed is a whole number")
        }
        return dev.netherforge.plugin.binding.WorldCreateOptions(
            generator = choice("generator", listOf("normal", "flat", "void", "amplified", "large_biomes")),
            terrain = text("terrain"),
            environment = choice("environment", listOf("normal", "nether", "end")),
            dimensionType = text("dimension_type"),
            seed = seed,
            structures = flag("structures"),
            keepSpawnLoaded = flag("keep_spawn_loaded")
        )
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
