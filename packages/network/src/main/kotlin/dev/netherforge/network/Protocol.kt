package dev.netherforge.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * What a server and its network's hub say to each other: one JSON object per line (NDJSON) over TCP, its kind in
 * `"t"`. Written as plain frames rather than JSON-RPC (the dev bridge's): almost everything is a notification both
 * ways, the few answers ([Reply], [MapResult], [SendResult]) carry their own ids, and the hub relays a request it
 * didn't make, under an id of its own.
 *
 * The handshake: the hub speaks first with a [Challenge] (a nonce); the server answers [Hello] with its name, the
 * credential it uses (none: the network's shared secret), a nonce of its own and [Auth.proof] of that credential's
 * secret over both, never the secret itself; the hub answers [Welcome] (and
 * from then on anything) or [Refused] and closes. From the [Welcome] on, every line both ways is sealed ([Channel],
 * keys derived from the secret and both nonces), so the traffic is private and unaltered, and a server knows its
 * hub holds the secret too. A server that hears nothing for [IDLE_MILLIS] drops the connection and
 * dials again, and sends a [Ping] every [PING_MILLIS] so the hub can do the same.
 */
object Protocol {
    /** This protocol's version: a hub refuses a [Hello] of another, saying which it speaks. */
    const val VERSION = 3

    /** A registering server's name that asks the hub for one: its group's, numbered (`arena-3`). */
    const val AUTO_NAME = "auto"

    /** The port a hub listens on unless its owner says otherwise. */
    const val DEFAULT_PORT = 25590

    const val PING_MILLIS = 15_000L
    const val IDLE_MILLIS = 60_000L

    /** How long the hub waits for a [Hello], and a server for the hub's answer to one. */
    const val HELLO_MILLIS = 10_000L

    /** The most a frame may be before the handshake is done: a hello is a few names. */
    const val MAX_FRAME_BEFORE_WELCOME = 16 * 1024

    /**
     * The most a frame may be once the handshake is done, either way: a [HubFrame.Welcome] lists every player of
     * the network, and a [HubFrame.MapSnapshot] a whole map. Values are held to far less ([NetworkLimits]).
     */
    const val MAX_FRAME = 16 * 1024 * 1024

    /** The most a sealed line may be: [MAX_FRAME] of JSON, in base64, with its tag. */
    const val MAX_SEALED_LINE = MAX_FRAME / 3 * 4 + 64

    val json = Json {
        classDiscriminator = "t"
        encodeDefaults = false
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    fun encode(frame: HubFrame): String = json.encodeToString(HubFrame.serializer(), frame)

    fun encode(frame: ServerFrame): String = json.encodeToString(ServerFrame.serializer(), frame)

    /** One line from a server, or null when it isn't a frame this version knows. */
    fun decodeServerFrame(line: String): ServerFrame? = try {
        json.decodeFromString(ServerFrame.serializer(), line)
    } catch (_: Exception) {
        null
    }

    /** One line from the hub, or null when it isn't a frame this version knows. */
    fun decodeHubFrame(line: String): HubFrame? = try {
        json.decodeFromString(HubFrame.serializer(), line)
    } catch (_: Exception) {
        null
    }

    /** How many bytes [value] takes as JSON: what the limits on values count. */
    fun size(value: JsonElement?): Int = if (value == null) 0 else json.encodeToString(JsonElement.serializer(), value).toByteArray().size
}

/**
 * A server of the network as the hub knows it: one the proxy names, whether NetherForge on it is connected, the
 * [group] it's an instance of (null: a group of one, its own name), how many players it takes ([capacity], null for
 * any), whether it takes players now, and how many are on it.
 */
@Serializable
data class ServerState(
    val name: String,
    val linked: Boolean,
    val group: String? = null,
    val capacity: Int? = null,
    val accepting: Boolean = true,
    val players: Int = 0,
    /** Where it is in its life, as NetherForge on it says; null when it isn't connected. */
    val state: Lifecycle? = null,
    /** Whether it's been empty longer than its `network.idle-after-seconds`. */
    val idle: Boolean = false,
    /** How long it took from its process starting to [Lifecycle.READY], once it got there. */
    val startupMillis: Long? = null
) {
    /** Its group's name: its [group], or its own [name] when it's a group of one. */
    val groupName get() = group ?: name

    /** Whether new players may be sent to it by its group: connected, ready and taking them. */
    val open get() = linked && state == Lifecycle.READY && accepting
}

/**
 * Where a server is in its life. Only a [READY] one is picked for players sent to its group; a [DRAINING] or
 * [STOPPING] one takes no new players at all.
 */
@Serializable
enum class Lifecycle(val word: String) {
    /** Its project is loading, or a script holds it back (`nf.network.hold_ready`). */
    @SerialName("starting")
    STARTING("starting"),

    @SerialName("ready")
    READY("ready"),

    /** It takes no new players and finishes what it's doing (`/nf drain`, the admin API, a script). */
    @SerialName("draining")
    DRAINING("draining"),

    /** It's shutting down: its players are being sent to its group's fallback. */
    @SerialName("stopping")
    STOPPING("stopping")
}

/**
 * A server adding itself to the proxy as it joins (it isn't in `velocity.toml`): where the proxy reaches it. With no
 * [host], the address the hub sees it connect from.
 */
@Serializable
data class Registration(val host: String? = null, val port: Int)

/** How the hub picks an instance of a group to send players to. */
@Serializable
enum class Balance(val word: String) {
    /** The instance with the fewest players that has room. */
    @SerialName("least")
    LEAST("least"),

    /** The fullest instance that still has room, so players end up together. */
    @SerialName("fill")
    FILL("fill");

    companion object {
        fun of(word: String): Balance? = entries.firstOrNull { it.word == word }
    }
}

/** A player online in the network: their UUID, name and the server they're on (null while they're between servers). */
@Serializable
data class PlayerState(
    val id: String,
    val name: String,
    val server: String? = null,
    /** The proxy they're on, when the hub runs on its own (with several proxies, which one). */
    val proxy: String? = null
)

/** What the hub sends a server. */
@Serializable
sealed interface HubFrame {
    /** The first frame on a connection: prove you know the secret by signing [nonce]. */
    @Serializable
    @SerialName("challenge")
    data class Challenge(val version: Int, val nonce: String) : HubFrame

    /**
     * The hello was accepted: the network as it is now (as far as [grants] let this server see it), the most a value
     * may be ([NetworkLimits.maxValueBytes]) and what this server may do ([grants]), so the server can refuse a value
     * or a call at the script's line. Everything after it is a change to it.
     */
    @Serializable
    @SerialName("welcome")
    data class Welcome(
        val server: String,
        val servers: List<ServerState>,
        val players: List<PlayerState>,
        val maxValueBytes: Int = NetworkLimits.DEFAULT_MAX_VALUE_BYTES,
        val grants: Grants = Grants.TRUSTED
    ) : HubFrame

    /** The hello wasn't accepted, and why; the hub closes the connection after it. */
    @Serializable
    @SerialName("refused")
    data class Refused(val reason: String) : HubFrame

    /** Something published on a channel this server listens to ([from] published it, this server included). */
    @Serializable
    @SerialName("message")
    data class Message(val channel: String, val from: String, val value: JsonElement) : HubFrame

    /** A request [from] another server for this one to answer with a [ServerFrame.Reply] to [id]. */
    @Serializable
    @SerialName("requested")
    data class Requested(val id: Long, val channel: String, val from: String, val value: JsonElement) : HubFrame

    /** The answer to this server's [ServerFrame.Request] [id]: [value], or [error] when there's none. */
    @Serializable
    @SerialName("reply")
    data class Reply(val id: Long, val value: JsonElement? = null, val error: String? = null) : HubFrame

    /** Everything in [map] now, after this server began listening to it. */
    @Serializable
    @SerialName("map_snapshot")
    data class MapSnapshot(val map: String, val entries: Map<String, JsonElement>) : HubFrame

    /** [key] of [map] changed: its [value] now (null when it's gone), written by [from] (null when it expired). */
    @Serializable
    @SerialName("map_changed")
    data class MapChanged(val map: String, val key: String, val value: JsonElement? = null, val from: String? = null, val cause: MapCause) :
        HubFrame

    /** The answer to a [ServerFrame.MapWrite] [id]: [value] (a number for `add`, a boolean otherwise) or [error]. */
    @Serializable
    @SerialName("map_result")
    data class MapResult(val id: Long, val value: JsonElement? = null, val error: String? = null) : HubFrame

    @Serializable
    @SerialName("player_joined")
    data class PlayerJoined(val player: PlayerState) : HubFrame

    /** A player went from [previous] (null: they've just joined the network) to [server]. */
    @Serializable
    @SerialName("player_moved")
    data class PlayerMoved(val id: String, val server: String, val previous: String? = null) : HubFrame

    @Serializable
    @SerialName("player_left")
    data class PlayerLeft(val id: String) : HubFrame

    /** NetherForge on [server] connected to the hub, or (with [linked] false) went. */
    @Serializable
    @SerialName("server")
    data class Server(val server: ServerState) : HubFrame

    /** Servers whose players, room or whether they take players changed: sent once a second at most, with what changed. */
    @Serializable
    @SerialName("servers")
    data class Servers(val servers: List<ServerState>) : HubFrame

    /** [player] is on their way here from [from], sent by a script there with [data]. */
    @Serializable
    @SerialName("arriving")
    data class Arriving(val player: String, val from: String, val data: JsonElement? = null) : HubFrame

    /** The [Arriving] for [player] won't happen after all: the proxy couldn't move them. */
    @Serializable
    @SerialName("arrival_cancelled")
    data class ArrivalCancelled(val player: String) : HubFrame

    /** How this server's [ServerFrame.Send] [id] went: moved, or [error] saying why not. */
    @Serializable
    @SerialName("send_result")
    data class SendResult(val id: Long, val error: String? = null, val server: String? = null) : HubFrame

    /**
     * This server's [ServerFrame.Send] [id] waits: [position] in its queue for an instance with room (from 1), or
     * held by an admin client deciding where it goes (position 0), with what it says ([status]).
     */
    @Serializable
    @SerialName("send_waiting")
    data class SendWaiting(val id: Long, val position: Int, val status: String? = null) : HubFrame

    /** The admin API asks this server to drain ([draining]) or to stop draining. */
    @Serializable
    @SerialName("drain")
    data class Drain(val draining: Boolean) : HubFrame

    /** A refusal the hub sends instead of acting on a frame (over a limit, a frame it couldn't read), for the log. */
    @Serializable
    @SerialName("problem")
    data class Problem(val message: String) : HubFrame

    @Serializable
    @SerialName("pong")
    data object Pong : HubFrame

    // ---- to a proxy, from a hub that runs on its own ----------------------------

    /** Move player [player] to [server]; answer [ServerFrame.ProxyResult] [id]. */
    @Serializable
    @SerialName("proxy_move")
    data class ProxyMove(val id: Long, val player: String, val server: String) : HubFrame

    /** Add [name] at [host]:[port] to the proxy's servers. */
    @Serializable
    @SerialName("proxy_register")
    data class ProxyRegister(val name: String, val host: String, val port: Int) : HubFrame

    @Serializable
    @SerialName("proxy_unregister")
    data class ProxyUnregister(val name: String) : HubFrame

    /** Show [player] [text] as [kind] (`chat`, `title`, `actionbar`). */
    @Serializable
    @SerialName("proxy_message")
    data class ProxyMessage(val player: String, val kind: String, val text: String, val subtitle: String? = null) : HubFrame

    @Serializable
    @SerialName("proxy_kick")
    data class ProxyKick(val player: String, val message: String) : HubFrame

    /** Take no new logins ([draining]), as a proxy about to restart; or take them again. */
    @Serializable
    @SerialName("proxy_drain")
    data class ProxyDrain(val draining: Boolean) : HubFrame

    /** Where the player of [ServerFrame.ProxyLogin] [id] goes: [server] (null: the proxy's own choice), or away with [deny]. */
    @Serializable
    @SerialName("login_placed")
    data class LoginPlaced(val id: Long, val server: String? = null, val deny: String? = null) : HubFrame
}

/** What a server sends the hub. */
@Serializable
sealed interface ServerFrame {
    /**
     * The answer to a [HubFrame.Challenge]: this server's name (as the proxy names it), the [credential] it proves
     * (null: the network's shared secret) and [Auth.proof], and what it is: the [group] it's an instance of and how
     * many players it takes ([capacity]).
     */
    @Serializable
    @SerialName("hello")
    data class Hello(
        val version: Int,
        val server: String,
        val proof: String,
        val nonce: String = "",
        val credential: String? = null,
        val group: String? = null,
        val capacity: Int? = null,
        /** Adds this server to the proxy, which doesn't have it: [server] may then be [Protocol.AUTO_NAME]. */
        val register: Registration? = null,
        /** This process's own random id: the same server dialling again keeps its name, another can't take it. */
        val instance: String? = null,
        /** Where it is in its life as it joins: a server still starting isn't sent players until it's ready. */
        val state: Lifecycle = Lifecycle.READY,
        /** `proxy` for a proxy joining a hub that runs on its own; a server says none. */
        val role: String? = null
    ) : ServerFrame

    /** This server is shutting down: a server that registered itself comes off the proxy at once. */
    @Serializable
    @SerialName("leaving")
    data object Leaving : ServerFrame

    /** Whether this server takes players now (a match running: not), where it is in its life, and whether it's idle. */
    @Serializable
    @SerialName("status")
    data class Status(
        val accepting: Boolean = true,
        val state: Lifecycle = Lifecycle.READY,
        val idle: Boolean = false,
        val startupMillis: Long? = null
    ) : ServerFrame

    /** Asks the hub to send every player on this server to its group's fallback: it's stopping. */
    @Serializable
    @SerialName("evacuate")
    data object Evacuate : ServerFrame

    /** Every channel and map this server listens to now, replacing what it said before. */
    @Serializable
    @SerialName("listen")
    data class Listen(val channels: List<String>, val maps: List<String>) : ServerFrame

    /** Publishes [value] on [channel]: to every server listening, or only [to], or only [group]'s instances. */
    @Serializable
    @SerialName("publish")
    data class Publish(val channel: String, val value: JsonElement, val to: String? = null, val group: String? = null) : ServerFrame

    /**
     * Asks server [to] on [channel], or one instance of [group] that answers on it (the hub picks); the hub answers
     * with a [HubFrame.Reply] to [id].
     */
    @Serializable
    @SerialName("request")
    data class Request(
        val id: Long,
        val channel: String,
        val to: String? = null,
        val value: JsonElement,
        val group: String? = null,
        /** An admin client of the hub's, by its id, in place of a server. */
        val admin: String? = null
    ) : ServerFrame

    /** This server's answer to a [HubFrame.Requested] [id]. */
    @Serializable
    @SerialName("reply")
    data class Reply(val id: Long, val value: JsonElement? = null, val error: String? = null) : ServerFrame

    /**
     * A write to [key] of [map]: [op] with [value] (`set`, `insert`) or [amount] (`add`), kept [expiresMillis] when
     * it's set. Answered with a [HubFrame.MapResult] to [id].
     */
    @Serializable
    @SerialName("map_write")
    data class MapWrite(
        val id: Long,
        val map: String,
        val key: String,
        val op: MapOp,
        val value: JsonElement? = null,
        val amount: Double? = null,
        val expiresMillis: Long? = null
    ) : ServerFrame

    /**
     * Asks the proxy to move [players] (UUIDs) together to [server], or to one instance of [group] with room for them
     * all (picked by [balance], or the group's own), and that server to hear they came with [data].
     */
    @Serializable
    @SerialName("send")
    data class Send(
        val id: Long,
        val players: List<String>,
        val server: String? = null,
        val data: JsonElement? = null,
        val group: String? = null,
        val balance: Balance? = null,
        /** How long it may wait for an instance with room (or a server that's starting) before it fails. */
        val waitMillis: Long? = null
    ) : ServerFrame

    @Serializable
    @SerialName("ping")
    data object Ping : ServerFrame

    // ---- from a proxy, to a hub that runs on its own -----------------------------

    /** The proxy's servers now: every one, and which are its config's own ([fixed]). */
    @Serializable
    @SerialName("proxy_servers")
    data class ProxyServers(val servers: List<String>, val fixed: List<String>) : ServerFrame

    /** [player] is on [PlayerState.server] now: they joined, or moved. */
    @Serializable
    @SerialName("proxy_player")
    data class ProxyPlayer(val player: PlayerState) : ServerFrame

    /** Everyone on the proxy now, when it connects: the hub's presence becomes this. */
    @Serializable
    @SerialName("proxy_presence")
    data class ProxyPresence(val players: List<PlayerState>) : ServerFrame

    @Serializable
    @SerialName("proxy_player_left")
    data class ProxyPlayerLeft(val id: String) : ServerFrame

    /** How [HubFrame.ProxyMove] [id] went: null once they're there, or why not. */
    @Serializable
    @SerialName("proxy_result")
    data class ProxyResult(val id: Long, val error: String? = null) : ServerFrame

    /** [player] ([name]) is logging in: where should they go? Answered with [HubFrame.LoginPlaced] [id]. */
    @Serializable
    @SerialName("proxy_login")
    data class ProxyLogin(val id: Long, val player: String, val name: String) : ServerFrame
}

@Serializable
enum class MapOp {
    @SerialName("set")
    SET,

    @SerialName("delete")
    DELETE,

    @SerialName("add")
    ADD,

    @SerialName("insert")
    INSERT
}

/** Why a map's key changed, as scripts read it. */
@Serializable
enum class MapCause(val luaName: String) {
    @SerialName("set")
    SET("set"),

    @SerialName("delete")
    DELETE("delete"),

    @SerialName("expired")
    EXPIRED("expired")
}
