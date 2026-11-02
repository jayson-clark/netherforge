package dev.netherforge.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

/** What an admin client may do through the hub's admin API. */
enum class AdminScope(val word: String) {
    /** List servers, and hear them change. */
    SERVERS_READ("servers:read"),

    /** Add servers to the proxy, take them off, drain them. */
    SERVERS_WRITE("servers:write"),

    /** List players and where they are, and hear them join, move and leave. */
    PRESENCE_READ("presence:read"),

    /** Send players to a server or a group. */
    PLAYERS_SEND("players:send"),

    /** Show players chat messages, titles and action bars, wherever they are. */
    PLAYERS_MESSAGE("players:message"),

    PLAYERS_KICK("players:kick"),

    /** Publish on a channel, to one server, a group, or every one. */
    CHANNELS_PUBLISH("channels:publish"),

    /** Hear a channel's messages, and answer requests addressed to this client. */
    CHANNELS_READ("channels:read"),

    /** Decide which server players sent to a group (or logging in) go to. */
    PLACEMENT("placement"),

    /** Ban and mute players network-wide, read reports, and take servers and groups off the network. */
    MODERATION("moderation");

    companion object {
        fun of(word: String): AdminScope? = entries.firstOrNull { it.word == word }
    }
}

/** A client of the admin API: its [id], the [token] it sends, and what it may do. */
data class AdminClient(val id: String, val token: String, val scopes: Set<AdminScope>) {
    override fun toString() = "AdminClient($id, ${scopes.joinToString { it.word }})"
}

/**
 * The admin API's clients, `admin.properties` beside `hub.properties`: `<id>.token` and `<id>.scopes` (words, or `*`
 * for every one). `/nfhub admins add|revoke` rewrite it whole.
 */
class AdminClientsFile(val file: Path) {
    fun read(): List<AdminClient> {
        if (!Files.exists(file)) return emptyList()
        val properties = Properties()
        Files.newBufferedReader(file).use(properties::load)
        return properties.stringPropertyNames().map { it.substringBefore('.') }.toSortedSet().map { id ->
            require(NetworkNames.isCredential(id)) { "$file: \"$id\" isn't a client id (letters, digits, _ . -)" }
            val token = properties.getProperty("$id.token")?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("$file: $id has no token")
            require(token.length >= MIN_TOKEN) { "$file: $id's token is shorter than $MIN_TOKEN characters" }
            val words = properties.getProperty("$id.scopes").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val scopes = if (words == listOf("*")) {
                AdminScope.entries.toSet()
            } else {
                words.map {
                    AdminScope.of(it)
                        ?: throw IllegalArgumentException(
                            "$file: $id's scope \"$it\" isn't one (${AdminScope.entries.joinToString { s ->
                                s.word
                            }})"
                        )
                }.toSet()
            }
            AdminClient(id, token, scopes)
        }
    }

    fun write(clients: List<AdminClient>) {
        Files.createDirectories(file.parent)
        val text = buildString {
            append(HEADER)
            for (client in clients.sortedBy { it.id }) {
                append("\n${client.id}.token=${client.token}\n")
                append("${client.id}.scopes=${client.scopes.sortedBy { it.ordinal }.joinToString(", ") { it.word }}\n")
            }
        }
        val temporary = file.resolveSibling("${file.fileName}.tmp")
        Files.writeString(temporary, text)
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val FILE = "admin.properties"
        const val MIN_TOKEN = 24

        private val HEADER = """
            |# The hub's admin API clients (a matchmaker, an orchestrator, a moderation tool): each sends its token as
            |# Authorization: Bearer <token>, and may do what its scopes say: servers:read, servers:write, presence:read,
            |# players:send, players:message, players:kick, channels:publish, channels:read, placement (or * for all).
            |# /nfhub admins add|revoke rewrite this file.
            |
        """.trimMargin()
    }
}

/**
 * What an admin client hears on its stream (`GET /v1/events`, Server-Sent Events: the event's name, then its JSON),
 * as its scopes and what it asked for allow.
 */
@Serializable
sealed interface AdminEvent {
    @Serializable
    @SerialName("player_joined")
    data class PlayerJoined(val player: PlayerState) : AdminEvent

    @Serializable
    @SerialName("player_moved")
    data class PlayerMoved(val id: String, val server: String, val previous: String? = null) : AdminEvent

    @Serializable
    @SerialName("player_left")
    data class PlayerLeft(val id: String) : AdminEvent

    /** A server connected, went, or changed (its state, room, players). */
    @Serializable
    @SerialName("server")
    data class Server(val server: ServerState) : AdminEvent

    /** Something published on a channel it listens to. */
    @Serializable
    @SerialName("message")
    data class Message(val channel: String, val from: String, val value: JsonElement) : AdminEvent

    /** A server asks this client on [channel]: answer with `POST /v1/requests/<id>/reply`. */
    @Serializable
    @SerialName("request")
    data class Request(val id: Long, val channel: String, val from: String, val value: JsonElement) : AdminEvent

    /**
     * Where should [players] go: sent to [group] by [from], or logging in ([login]). Answer with
     * `POST /v1/placements/<id>`: a server, a hold with a status (then answer again), or a failure.
     */
    @Serializable
    @SerialName("place")
    data class Place(
        val id: Long,
        val players: List<PlayerState>,
        val group: String? = null,
        val from: String? = null,
        val login: Boolean = false
    ) : AdminEvent

    companion object {
        fun name(event: AdminEvent): String = when (event) {
            is PlayerJoined -> "player_joined"
            is PlayerMoved -> "player_moved"
            is PlayerLeft -> "player_left"
            is Server -> "server"
            is Message -> "message"
            is Request -> "request"
            is Place -> "place"
        }
    }
}

/** An admin client's answer to a [AdminEvent.Place]: one of a [server], a [hold] with its status, or a [fail] reason. */
@Serializable
data class PlaceAnswer(val server: String? = null, val hold: String? = null, val fail: String? = null)

/** Where a player logging in goes: [server] (null: the proxy's own choice), or turned away with [deny]. */
data class LoginPlace(val server: String? = null, val deny: String? = null)
