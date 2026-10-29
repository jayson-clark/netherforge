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
 * The handshake: the hub speaks first with a [Challenge] (a nonce); the server answers [Hello] with its name, a nonce
 * of its own and [Auth.proof] of the shared secret over both, never the secret itself; the hub answers [Welcome] (and
 * from then on anything) or [Refused] and closes. From the [Welcome] on, every line both ways is sealed ([Channel],
 * keys derived from the secret and both nonces), so the traffic is private and unaltered, and a server knows its
 * hub holds the secret too. A server that hears nothing for [IDLE_MILLIS] drops the connection and
 * dials again, and sends a [Ping] every [PING_MILLIS] so the hub can do the same.
 */
object Protocol {
    /** This protocol's version: a hub refuses a [Hello] of another, saying which it speaks. */
    const val VERSION = 2

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

/** A server of the network as the hub knows it: one the proxy names, and whether NetherForge on it is connected. */
@Serializable
data class ServerState(val name: String, val linked: Boolean)

/** A player online in the network: their UUID, name and the server they're on (null while they're between servers). */
@Serializable
data class PlayerState(val id: String, val name: String, val server: String? = null)

/** What the hub sends a server. */
@Serializable
sealed interface HubFrame {
    /** The first frame on a connection: prove you know the secret by signing [nonce]. */
    @Serializable
    @SerialName("challenge")
    data class Challenge(val version: Int, val nonce: String) : HubFrame

    /**
     * The hello was accepted: the network as it is now, and the most a value may be ([NetworkLimits.maxValueBytes],
     * so the server can refuse one at the script's line). Everything after it is a change to it.
     */
    @Serializable
    @SerialName("welcome")
    data class Welcome(
        val server: String,
        val servers: List<ServerState>,
        val players: List<PlayerState>,
        val maxValueBytes: Int = NetworkLimits.DEFAULT_MAX_VALUE_BYTES
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
    data class SendResult(val id: Long, val error: String? = null) : HubFrame

    /** A refusal the hub sends instead of acting on a frame (over a limit, a frame it couldn't read), for the log. */
    @Serializable
    @SerialName("problem")
    data class Problem(val message: String) : HubFrame

    @Serializable
    @SerialName("pong")
    data object Pong : HubFrame
}

/** What a server sends the hub. */
@Serializable
sealed interface ServerFrame {
    /** The answer to a [HubFrame.Challenge]: this server's name (as the proxy names it) and [Auth.proof]. */
    @Serializable
    @SerialName("hello")
    data class Hello(val version: Int, val server: String, val proof: String, val nonce: String = "") : ServerFrame

    /** Every channel and map this server listens to now, replacing what it said before. */
    @Serializable
    @SerialName("listen")
    data class Listen(val channels: List<String>, val maps: List<String>) : ServerFrame

    /** Publishes [value] on [channel]: to every server listening, or only [to]. */
    @Serializable
    @SerialName("publish")
    data class Publish(val channel: String, val value: JsonElement, val to: String? = null) : ServerFrame

    /** Asks server [to] on [channel]; the hub answers with a [HubFrame.Reply] to [id]. */
    @Serializable
    @SerialName("request")
    data class Request(val id: Long, val channel: String, val to: String, val value: JsonElement) : ServerFrame

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

    /** Asks the proxy to move [player] (a UUID) to [server], and that server to hear they came with [data]. */
    @Serializable
    @SerialName("send")
    data class Send(val id: Long, val player: String, val server: String, val data: JsonElement? = null) : ServerFrame

    @Serializable
    @SerialName("ping")
    data object Ping : ServerFrame
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
