package dev.netherforge.plugin.network

import dev.netherforge.format.Problem
import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.project.PackagePaths
import dev.netherforge.format.project.ProjectManifest
import dev.netherforge.format.project.ProjectSnapshot
import dev.netherforge.network.HubFrame
import dev.netherforge.network.MapCause
import dev.netherforge.network.MapOp
import dev.netherforge.network.NetworkNames
import dev.netherforge.network.PlayerState
import dev.netherforge.network.Protocol
import dev.netherforge.network.ServerFrame
import dev.netherforge.plugin.RuntimeLog
import dev.netherforge.plugin.async.WorkFailed
import dev.netherforge.plugin.binding.Events
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.binding.NetworkConnectEvent
import dev.netherforge.plugin.binding.NetworkDisconnectEvent
import dev.netherforge.plugin.binding.NetworkMapChangeEvent
import dev.netherforge.plugin.binding.NetworkMapReadyEvent
import dev.netherforge.plugin.binding.NetworkMessageEvent
import dev.netherforge.plugin.binding.NetworkPlayer
import dev.netherforge.plugin.binding.NetworkPlayerEvent
import dev.netherforge.plugin.binding.NetworkPlayerSwitchEvent
import dev.netherforge.plugin.binding.NetworkRequestEvent
import dev.netherforge.plugin.binding.NetworkServerEvent
import dev.netherforge.plugin.binding.PlayerArriveEvent
import dev.netherforge.plugin.http.RequestRate
import dev.netherforge.plugin.lua.CallResult
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.lua.LuaCodecs
import dev.netherforge.plugin.lua.LuaRef
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.script.Scope
import dev.netherforge.plugin.script.Scripts
import dev.netherforge.plugin.session.RuntimeService
import dev.netherforge.plugin.session.TickPhase
import dev.netherforge.plugin.session.liveness
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * `nf.network` for a session: the channels and maps its scripts named, what's on its way to the hub and waiting for
 * an answer, and what came in, handed to scripts as events. The connection itself is [NetworkLink]'s, for the
 * plugin's life: a reload doesn't reconnect, it only says again what this session listens to.
 *
 * Once a tick ([TickPhase.ASYNC]) it drains what the link heard (at most [NetworkConfig.messagesPerTick]), raises
 * the events, answers requests, delivers arrivals, and tells the hub what it listens to when that changed: every
 * channel some script has a `message` or `request` handler on ([Scripts.counting]), and every map a script has got.
 *
 * Answers ([HubFrame.Reply], [HubFrame.MapResult], [HubFrame.SendResult]) complete the stages scripts wait on here,
 * on the main thread; a lost connection fails every one still waiting.
 */
class Network(
    private val scripts: Scripts,
    private val config: NetworkConfig,
    private val link: () -> NetworkLink,
    private val ticks: () -> Long,
    private val online: (UUID) -> PlayerRef?,
    private val snapshot: () -> ProjectSnapshot?,
    private val log: RuntimeLog
) : RuntimeService {
    override val name get() = "network"

    /** A map's copy on this server. */
    class Mirror(val handle: LuaHandle.NetworkMap) {
        var ready = false
        val entries = LinkedHashMap<String, JsonElement>()
    }

    private class Waiting<T>(val future: CompletableFuture<T>, val deadline: Long, val what: String)

    private val channels = LinkedHashSet<String>()
    private val mirrors = LinkedHashMap<String, Mirror>()
    private val requests = HashMap<Long, Waiting<Any?>>()
    private val writes = HashMap<Long, Pair<MapOp, Waiting<Any?>>>()
    private val sends = HashMap<Long, Waiting<Boolean>>()
    private val rate by lazy { RequestRate(config.messagesPerMinute) }
    private var listenChanged = true
    private var listened: Pair<List<String>, List<String>>? = null

    /** A test's answers for its other servers: by server and channel, the test's function and its scope. */
    private val answers = HashMap<Pair<String, String>, Pair<Scope, LuaRef>>()

    override fun start() {
        scripts.counting(Events.NETWORKCHANNEL_MESSAGE, Events.NETWORKCHANNEL_REQUEST) { listenChanged = true }
    }

    // ---- names -----------------------------------------------------------------

    /**
     * [name] (a channel's or map's, as [namespace]'s script wrote it) in full: bare is the package's own, `ns:name`
     * one of a package it depends on. A mistake is an error at the script's line, [what] naming the call.
     */
    fun resolve(namespace: String, name: String, what: String): String {
        val colon = name.indexOf(':')
        val owner = if (colon < 0) namespace else name.substring(0, colon)
        val bare = if (colon < 0) name else name.substring(colon + 1)
        if (!NetworkNames.isName(bare)) {
            throw LuaApiException(
                "$what: \"$name\" isn't a name: lowercase letters, digits and _ . - /, at most 64 characters (and a namespace before a :)"
            )
        }
        if (owner != namespace && snapshot()?.manifestOf(namespace)?.dependencies?.containsKey(owner) != true) {
            throw LuaApiException("$what: package \"$namespace\" doesn't depend on \"$owner\", so it can't use \"$name\"")
        }
        return "$owner:$bare"
    }

    /** [full] as package [reader] writes it: bare for its own. */
    fun spell(full: String, reader: String): String = if (NetworkNames.namespaceOf(full) == reader) full.substringAfter(':') else full

    fun channel(full: String): LuaHandle.NetworkChannel {
        channels += full
        return LuaHandle.NetworkChannel(full)
    }

    /** [full]'s copy, made (and listened for) the first time a script gets it. */
    fun mirror(full: String): Mirror = mirrors.getOrPut(full) {
        listenChanged = true
        Mirror(LuaHandle.NetworkMap(full))
    }

    fun mirrorOf(full: String): Mirror? = mirrors[full]

    // ---- sending -----------------------------------------------------------------

    /** A value a script gave, as JSON within the hub's limit: over it is an error at the script's line. */
    fun checked(value: JsonElement, what: String): JsonElement {
        val size = Protocol.size(value)
        val limit = link().maxValueBytes
        if (size >
            limit
        ) {
            throw LuaApiException(
                "$what is $size bytes as JSON, over the network's limit of $limit (max-value-bytes in the proxy's hub.properties)"
            )
        }
        return value
    }

    /** Whether [namespace] may send one more this minute; says so in the log when it may not. */
    private fun allowed(namespace: String): Boolean {
        if (rate.tryStart(namespace)) return true
        warnOnce(
            "rate:$namespace",
            "package \"$namespace\" sent more than ${config.messagesPerMinute} times to the network in a minute, the server's limit " +
                "(network.messages-per-minute in config.yml); what's over it isn't sent"
        )
        return false
    }

    private fun refusal(namespace: String): String? = when {
        !link().connected -> notConnected()
        !allowed(namespace) ->
            "package \"$namespace\" is over the server's limit of ${config.messagesPerMinute} a minute " +
                "(network.messages-per-minute in config.yml)"
        else -> null
    }

    private fun notConnected() = when (link().mode) {
        NetworkLink.Mode.OFF -> "this server isn't part of a network (network.enabled in config.yml)"
        else -> "this server isn't connected to the network's hub"
    }

    fun publish(namespace: String, channel: String, value: JsonElement, server: String?): Boolean {
        if (!link().connected || !allowed(namespace)) return false
        return link().send(ServerFrame.Publish(channel, value, server))
    }

    fun request(namespace: String, channel: String, server: String, value: JsonElement): CompletionStage<Any?> {
        refusal(namespace)?.let { return CompletableFuture.failedFuture(WorkFailed(it)) }
        val id = link().nextId()
        val future = CompletableFuture<Any?>()
        requests[id] = Waiting(future, ticks() + config.requestTimeoutSeconds * TICKS_PER_SECOND, "$server didn't answer on \"$channel\"")
        if (!link().send(ServerFrame.Request(id, channel, server, value))) {
            requests.remove(id)
            return CompletableFuture.failedFuture(WorkFailed(notConnected()))
        }
        return future
    }

    /** A map write: `set` and `delete` answer whether it went, `add` and `insert` with the hub's answer. */
    fun write(
        namespace: String,
        map: String,
        key: String,
        op: MapOp,
        value: JsonElement? = null,
        amount: Double? = null,
        expires: Long? = null
    ): CompletionStage<Any?>? {
        if (!NetworkNames.isKey(
                key
            )
        ) {
            throw LuaApiException("bad argument 'key' (1 to ${NetworkNames.MAX_KEY_LENGTH} characters, none a control character)")
        }
        if (expires != null && expires < 1) throw LuaApiException("bad argument 'options.expires' (at least 1 tick expected, got $expires)")
        val waits = op == MapOp.ADD || op == MapOp.INSERT
        refusal(namespace)?.let { return if (waits) CompletableFuture.failedFuture(WorkFailed(it)) else null }
        val id = link().nextId()
        val future = CompletableFuture<Any?>()
        writes[id] = op to Waiting(future, ticks() + config.requestTimeoutSeconds * TICKS_PER_SECOND, "the network's hub didn't answer")
        val frame = ServerFrame.MapWrite(id, map, key, op, value, amount, expires?.let { it * MILLIS_PER_TICK })
        if (!link().send(frame)) {
            writes.remove(id)
            return if (waits) CompletableFuture.failedFuture(WorkFailed(notConnected())) else null
        }
        return future
    }

    fun send(namespace: String, player: String, server: String, data: JsonElement?): CompletionStage<Boolean> {
        refusal(namespace)?.let { return CompletableFuture.failedFuture(WorkFailed(it)) }
        val id = link().nextId()
        val future = CompletableFuture<Boolean>()
        sends[id] =
            Waiting(
                future,
                ticks() + SEND_TIMEOUT_SECONDS * TICKS_PER_SECOND,
                "the proxy didn't move them within $SEND_TIMEOUT_SECONDS seconds"
            )
        if (!link().send(ServerFrame.Send(id, player, server, data))) {
            sends.remove(id)
            return CompletableFuture.failedFuture(WorkFailed(notConnected()))
        }
        return future
    }

    /** A player online in the network by name (any case) or UUID. */
    fun findPlayer(player: String): PlayerState? {
        val players = link().players
        return players[player.lowercase()] ?: players.values.firstOrNull { it.name.equals(player, ignoreCase = true) }
    }

    // ---- a test's other servers ----------------------------------------------------

    /** The test's function [callback] answers requests to [server] on [channel], until [scope] ends. */
    fun answer(scope: Scope, server: String, channel: String, callback: LuaRef) {
        val peer = link().peer(server)
        peer.listen(channel)
        answers.put(server to channel, scope to callback)?.let { (_, previous) -> scripts.unref(previous) }
    }

    // ---- what came in ------------------------------------------------------------

    override fun tick(phase: TickPhase) {
        if (phase != TickPhase.ASYNC) return
        val link = link()
        val now = ticks()
        link.tick(now)
        for (item in link.drain(config.messagesPerTick, now, log::info, log::warn)) received(item)
        if (listenChanged && link.connected) listen()
        timeOut(now)
        arrive(now)
        answerPeers()
    }

    private fun received(item: NetworkLink.Inbound) {
        when (item) {
            is NetworkLink.Inbound.Connected -> {
                listenChanged = true
                listened = null
                scripts.emit(Events.NF_NETWORK_CONNECT, null, NetworkConnectEvent(item.welcome.server))
            }
            is NetworkLink.Inbound.Disconnected -> {
                failAll(WorkFailed("the connection to the network's hub was lost (${item.why})"))
                scripts.emit(Events.NF_NETWORK_DISCONNECT, null, NetworkDisconnectEvent(item.why))
            }
            is NetworkLink.Inbound.Failed -> Unit
            is NetworkLink.Inbound.Frame -> frame(item.frame)
            is NetworkLink.Inbound.Left -> scripts.emit(Events.NF_NETWORK_PLAYER_QUIT, null, NetworkPlayerEvent(player(item.player)))
        }
    }

    private fun frame(frame: HubFrame) {
        when (frame) {
            is HubFrame.Message -> if (frame.channel in channels) {
                val handle = LuaHandle.NetworkChannel(frame.channel)
                scripts.emit(Events.NETWORKCHANNEL_MESSAGE, handle, NetworkMessageEvent(handle, frame.value, frame.from))
            }
            is HubFrame.Requested -> answer(frame)
            is HubFrame.Reply -> requests.remove(frame.id)?.let { waiting ->
                if (frame.error != null) {
                    waiting.future.completeExceptionally(WorkFailed(frame.error!!))
                } else {
                    waiting.future.complete(frame.value?.takeUnless { it is JsonNull })
                }
            }
            is HubFrame.MapSnapshot -> snapshot(frame)
            is HubFrame.MapChanged -> changed(frame)
            is HubFrame.MapResult -> writes.remove(frame.id)?.let { (op, waiting) -> written(frame, op, waiting) }
            is HubFrame.SendResult -> sends.remove(frame.id)?.let { waiting ->
                if (frame.error != null) waiting.future.completeExceptionally(WorkFailed(frame.error!!)) else waiting.future.complete(true)
            }
            is HubFrame.PlayerJoined -> scripts.emit(Events.NF_NETWORK_PLAYER_JOIN, null, NetworkPlayerEvent(player(frame.player)))
            is HubFrame.PlayerMoved -> {
                val player = link().players[frame.id] ?: return
                val previous = frame.previous ?: return
                scripts.emit(Events.NF_NETWORK_PLAYER_SWITCH, null, NetworkPlayerSwitchEvent(player(player), previous))
            }
            is HubFrame.PlayerLeft -> Unit
            is HubFrame.Server -> if (frame.server.name != link().name) {
                val event = if (frame.server.linked) Events.NF_NETWORK_SERVER_CONNECT else Events.NF_NETWORK_SERVER_DISCONNECT
                scripts.emit(event, null, NetworkServerEvent(frame.server.name))
            }
            is HubFrame.Problem -> warnOnce(
                "problem:${frame.message}",
                "The network's hub refused something this server sent: ${frame.message}"
            )
            else -> Unit
        }
    }

    private fun answer(frame: HubFrame.Requested) {
        fun reply(value: JsonElement?, error: String? = null) {
            link().send(ServerFrame.Reply(frame.id, value, error))
        }
        val handle = LuaHandle.NetworkChannel(frame.channel)
        val here = link().name
        if (frame.channel !in channels || !scripts.listening(handle, Events.NETWORKCHANNEL_REQUEST)) {
            return reply(null, "nothing on $here answers requests on \"${frame.channel}\"")
        }
        val event = NetworkRequestEvent(handle, frame.value, frame.from)
        scripts.emit(Events.NETWORKCHANNEL_REQUEST, handle, event)
        val answer = event.reply as JsonElement?
        val size = Protocol.size(answer)
        if (size >
            link().maxValueBytes
        ) {
            return reply(null, "$here's answer is $size bytes as JSON, over the network's limit of ${link().maxValueBytes}")
        }
        reply(answer)
    }

    private fun snapshot(frame: HubFrame.MapSnapshot) {
        val mirror = mirrors[frame.map] ?: return
        if (!mirror.ready) {
            mirror.entries.clear()
            mirror.entries.putAll(frame.entries)
            mirror.ready = true
            scripts.emit(Events.NETWORKMAP_READY, mirror.handle, NetworkMapReadyEvent(mirror.handle))
            return
        }
        // Again, after a reconnect: what changed meanwhile is heard as changes, by no one in particular.
        val before = LinkedHashMap(mirror.entries)
        mirror.entries.clear()
        mirror.entries.putAll(frame.entries)
        for (key in (before.keys + frame.entries.keys).distinct()) {
            val previous = before[key]
            val value = frame.entries[key]
            if (previous == value) continue
            val cause = if (value == null) MapCause.DELETE else MapCause.SET
            scripts.emit(
                Events.NETWORKMAP_CHANGE,
                mirror.handle,
                NetworkMapChangeEvent(mirror.handle, key, value, previous, null, cause.luaName)
            )
        }
    }

    private fun changed(frame: HubFrame.MapChanged) {
        val mirror = mirrors[frame.map] ?: return
        val previous = if (frame.value == null) mirror.entries.remove(frame.key) else mirror.entries.put(frame.key, frame.value!!)
        scripts.emit(
            Events.NETWORKMAP_CHANGE,
            mirror.handle,
            NetworkMapChangeEvent(mirror.handle, frame.key, frame.value, previous, frame.from, frame.cause.luaName)
        )
    }

    private fun written(frame: HubFrame.MapResult, op: MapOp, waiting: Waiting<Any?>) {
        val error = frame.error
        if (error != null) {
            waiting.future.completeExceptionally(WorkFailed(error))
            if (op == MapOp.SET || op == MapOp.DELETE) warnOnce("map:$error", "The network's hub refused a write to a shared map: $error")
            return
        }
        val value = frame.value as? JsonPrimitive
        waiting.future.complete(if (op == MapOp.ADD) value?.doubleOrNull else value?.booleanOrNull)
    }

    /** The channels and maps this session told the hub it listens to, for `/nf network`. */
    fun listening(): Pair<List<String>, List<String>> = listened ?: (emptyList<String>() to emptyList())

    /** Tells the hub what this session listens to, when it changed. */
    private fun listen() {
        listenChanged = false
        val channels = channels.filter {
            val handle = LuaHandle.NetworkChannel(it)
            scripts.listening(handle, Events.NETWORKCHANNEL_MESSAGE) || scripts.listening(handle, Events.NETWORKCHANNEL_REQUEST)
        }
        val now = channels to mirrors.keys.toList()
        if (now == listened) return
        if (link().send(ServerFrame.Listen(now.first, now.second))) listened = now
    }

    private fun timeOut(now: Long) {
        fun <T> expire(waiting: MutableMap<Long, Waiting<T>>) {
            val late = waiting.filterValues { it.deadline <= now }
            for ((id, it) in late) {
                waiting.remove(id)
                it.future.completeExceptionally(WorkFailed("${it.what} within the time allowed"))
            }
        }
        expire(requests)
        expire(sends)
        for ((id, entry) in writes.filterValues { it.second.deadline <= now }) {
            writes.remove(id)
            entry.second.future.completeExceptionally(WorkFailed("${entry.second.what} within the time allowed"))
        }
    }

    /** Players sent here who are online now: their `player_arrive`, the tick after they joined at the earliest. */
    private fun arrive(now: Long) {
        val arrivals = link().arrivals
        if (arrivals.isEmpty()) return
        for ((id, arrival) in arrivals.entries.toList()) {
            val player = runCatching { UUID.fromString(id) }.getOrNull()?.let(online)
            if (player == null) {
                if (now - arrival.tick > ARRIVAL_TICKS) arrivals.remove(id)
                continue
            }
            if (joinedAt[player.uuid]?.let { it >= now } == true) continue
            arrivals.remove(id)
            val handle = LuaHandle.Player(player.uuid.toString())
            scripts.emit(
                listOf(Events.PLAYER_ARRIVE to handle, Events.NF_PLAYER_ARRIVE to null),
                PlayerArriveEvent(handle, arrival.from, arrival.data?.takeUnless { it is JsonNull })
            )
        }
    }

    /** The tick each player online joined at: an arrival is heard the tick after. */
    private val joinedAt = HashMap<UUID, Long>()

    override fun playerJoined(player: PlayerRef) {
        joinedAt[player.uuid] = ticks()
        link().joinedHere(player.uuid, player.name)
    }

    override fun playerQuit(player: PlayerRef) {
        joinedAt.remove(player.uuid)
        link().quitHere(player.uuid)
    }

    /** A test's other servers answer what they were asked, as the test's functions say. */
    private fun answerPeers() {
        if (link().mode != NetworkLink.Mode.LOCAL) return
        for (peer in link().peers()) {
            while (true) {
                val asked = peer.requests.removeFirstOrNull() ?: break
                val (scope, callback) = answers[peer.name to asked.channel] ?: run {
                    peer.send(ServerFrame.Reply(asked.id, error = "nothing on ${peer.name} answers requests on \"${asked.channel}\""))
                    null
                } ?: continue
                val reply = when (
                    val result = scripts.callKept(
                        scope,
                        callback,
                        listOf(asked.value, asked.from),
                        "network",
                        LuaCodecs.DATA,
                        "answer"
                    )
                ) {
                    is CallResult.Ok -> ServerFrame.Reply(asked.id, result.value as JsonElement?)
                    is CallResult.Failed -> ServerFrame.Reply(asked.id, error = "${peer.name} failed to answer: ${result.failure.message}")
                    CallResult.Absent -> ServerFrame.Reply(asked.id, error = "the test that answered for ${peer.name} has ended")
                }
                peer.send(reply)
            }
        }
    }

    private fun failAll(failure: WorkFailed) {
        for (waiting in requests.values) waiting.future.completeExceptionally(failure)
        for (waiting in sends.values) waiting.future.completeExceptionally(failure)
        for ((_, waiting) in writes.values) waiting.future.completeExceptionally(failure)
        requests.clear()
        sends.clear()
        writes.clear()
    }

    private fun player(state: PlayerState) = NetworkPlayer(state.id, state.name, state.server)

    private val warned = HashMap<String, Long>()

    private fun warnOnce(key: String, message: String) {
        val now = ticks()
        val last = warned[key]
        if (last != null && now - last < WARN_EVERY_TICKS) return
        warned[key] = now
        log.warn(message)
    }

    override fun scopeReleased(scope: Scope) {
        for ((key, entry) in answers.entries.filter { it.value.first == scope }) {
            answers.remove(key)
            scripts.unref(entry.second)
        }
    }

    override fun stop() {
        failAll(WorkFailed("the project stopped"))
        for ((_, callback) in answers.values) scripts.unref(callback)
        answers.clear()
        // The next session says what it listens to; until then, nothing.
        if (link().connected) link().send(ServerFrame.Listen(emptyList(), emptyList()))
    }

    override fun liveness() = listOf(
        liveness<LuaHandle.NetworkChannel> { true },
        liveness<LuaHandle.NetworkMap> { true }
    )

    /** With the server in no network, each package that declares it has a warning saying so. */
    override fun problems(): List<Problem> {
        if (link().mode != NetworkLink.Mode.OFF) return emptyList()
        val snapshot = snapshot() ?: return emptyList()
        return (listOf(snapshot.namespace) + snapshot.packages.keys.sorted())
            .filter { snapshot.manifestOf(it)?.requires?.network == true }
            .map { namespace ->
                val file = if (namespace ==
                    snapshot.namespace
                ) {
                    ProjectManifest.FILE_NAME
                } else {
                    PackagePaths.of(namespace, ProjectManifest.FILE_NAME)
                }
                val why = config.problem() ?: "the server owner puts it in one with network: in config.yml"
                ProblemCodes.RUNTIME_NETWORK_DISABLED.at(
                    file,
                    "This server isn't part of a network, so this package's nf.network has no other servers: $why",
                    "$.requires.network"
                )
            }
    }

    companion object {
        const val TICKS_PER_SECOND = 20L
        const val MILLIS_PER_TICK = 50L
        const val SEND_TIMEOUT_SECONDS = 30L

        /** How long a player sent here may take to arrive before what was sent with them is let go. */
        const val ARRIVAL_TICKS = 60 * TICKS_PER_SECOND
        const val WARN_EVERY_TICKS = 60 * TICKS_PER_SECOND
    }
}
