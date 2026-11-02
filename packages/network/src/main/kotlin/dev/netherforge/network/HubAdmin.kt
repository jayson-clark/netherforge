package dev.netherforge.network

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * The hub's admin API: HTTP and JSON for services outside the network's servers (an orchestrator, a matchmaker, a
 * moderation tool), off unless the owner gives it a port. Each request carries `Authorization: Bearer <token>` of one of
 * [clients] (compared in constant time; never a server's credential), and may do only what that client's
 * [AdminScope]s allow.
 *
 * - `GET /v1/servers` (servers:read); `POST /v1/servers`, `DELETE /v1/servers/<name>`, `POST /v1/servers/<name>/drain`
 *   (servers:write).
 * - `GET /v1/players` (presence:read).
 * - `POST /v1/players/<id or name>/send` `{ "server" | "group", "balance"?, "data"?, "wait"? }` (players:send): answers
 *   once they're on their way, or why not.
 * - `POST /v1/players/<id or name>/message` `{ "kind": "chat" | "title" | "actionbar", "text", "subtitle"? }`
 *   (players:message), `POST /v1/players/<id or name>/kick` `{ "message" }` (players:kick).
 * - `POST /v1/channels/publish` `{ "channel", "value", "server"?, "group"? }` (channels:publish).
 * - `GET /v1/events?listen=<channels>&place=<groups>` (Server-Sent Events): presence (presence:read), servers
 *   (servers:read), messages on the channels listened to and requests to this client (channels:read), and players to
 *   place for the groups named (`*login` for logins; placement), for as long as it's connected.
 * - `POST /v1/requests/<id>/reply` `{ "value"?, "error"? }` (channels:read), `POST /v1/placements/<id>`
 *   `{ "server" | "hold" | "fail" }` (placement).
 *
 * Plain HTTP, like the hub's own port: keep it on localhost or the servers' network, or put TLS in front.
 */
class HubAdmin(
    private val hub: Hub,
    private val bind: InetSocketAddress,
    private val clients: () -> List<AdminClient>,
    private val log: HubLog
) {
    private var server: HttpServer? = null
    private var executor: ExecutorService? = null
    private val streaming = AtomicInteger()

    /** The port it listens on, once started (the one asked for, or the one picked for 0). */
    val port: Int get() = server?.address?.port ?: -1

    @Serializable
    data class AddServer(val name: String, val address: String, val group: String? = null, val credential: String? = null)

    @Serializable
    data class ServerView(
        val name: String,
        val connected: Boolean,
        val group: String? = null,
        val capacity: Int? = null,
        val accepting: Boolean = true,
        val players: Int = 0,
        /** `starting`, `ready`, `draining` or `stopping`; none when it isn't connected. */
        val state: String? = null,
        val idle: Boolean = false,
        val startupMillis: Long? = null,
        /** Sends waiting for it (or its group) to have room. */
        val waiting: Int = 0,
        /** `config` (the proxy's own), `registered` (it added itself) or `admin` (this API added it). */
        val source: String,
        val address: String? = null
    )

    @Serializable
    data class Servers(val servers: List<ServerView>)

    @Serializable
    data class Players(val players: List<PlayerState>)

    @Serializable
    data class Drain(val draining: Boolean = true)

    @Serializable
    data class Send(
        val server: String? = null,
        val group: String? = null,
        val balance: String? = null,
        val data: JsonElement? = null,
        /** Seconds it may wait for an instance with room. */
        val wait: Double? = null
    )

    @Serializable
    data class Message(val kind: String = "chat", val text: String, val subtitle: String? = null)

    @Serializable
    data class Kick(val message: String = "")

    @Serializable
    data class Publish(val channel: String, val value: JsonElement, val server: String? = null, val group: String? = null)

    @Serializable
    data class Reply(val value: JsonElement? = null, val error: String? = null)

    @Serializable
    data class Answer(val name: String? = null, val server: String? = null, val error: String? = null)

    fun start() {
        val http = HttpServer.create(bind, 32)
        // A thread per open stream as well as for requests, up to a bound.
        val pool = ThreadPoolExecutor(2, MAX_THREADS, 30, TimeUnit.SECONDS, SynchronousQueue()) {
            Thread(it, "NetherForge hub admin").apply { isDaemon = true }
        }
        http.executor = pool
        http.createContext("/v1/") { exchange ->
            try {
                handle(exchange)
            } catch (e: Fail) {
                answer(exchange, e.status, Answer(error = e.message))
            } catch (_: IOException) {
                // The client went.
            } catch (e: Exception) {
                log.warn("NetherForge hub: the admin API failed on ${exchange.requestMethod} ${exchange.requestURI}: ${e.message}")
                try {
                    answer(exchange, 500, Answer(error = "the hub failed: ${e.message}"))
                } catch (_: Exception) {
                }
            } finally {
                exchange.close()
            }
        }
        http.start()
        server = http
        executor = pool
    }

    fun stop() {
        server?.stop(0)
        executor?.shutdownNow()
        server = null
        executor = null
    }

    /** A request's failure, answered with [status] and the message. */
    private class Fail(val status: Int, message: String) : Exception(message)

    private fun client(exchange: HttpExchange): AdminClient {
        val given = exchange.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")?.trim().orEmpty().toByteArray()
        // Every token compared, each in constant time, so which one matched isn't timed either.
        var found: AdminClient? = null
        for (client in clients()) if (MessageDigest.isEqual(given, client.token.toByteArray())) found = client
        return found ?: throw Fail(401, "a request needs Authorization: Bearer <an admin client's token>")
    }

    private fun AdminClient.need(scope: AdminScope) {
        if (scope !in scopes) throw Fail(403, "the admin client $id doesn't have the scope ${scope.word}")
    }

    private fun handle(exchange: HttpExchange) {
        val client = client(exchange)
        val parts = exchange.requestURI.rawPath.removePrefix("/v1/").trim('/').split('/').map { URLDecoder.decode(it, Charsets.UTF_8) }
        val method = exchange.requestMethod
        when {
            parts == listOf("servers") && method == "GET" -> {
                client.need(AdminScope.SERVERS_READ)
                list(exchange)
            }
            parts == listOf("servers") && method == "POST" -> {
                client.need(AdminScope.SERVERS_WRITE)
                add(exchange)
            }
            parts.size == 2 && parts[0] == "servers" && method == "DELETE" -> {
                client.need(AdminScope.SERVERS_WRITE)
                hub.adminRemove(parts[1])?.let { throw Fail(404, it) }
                answer(exchange, 200, Answer(name = parts[1]))
            }
            parts.size == 3 && parts[0] == "servers" && parts[2] == "drain" && method == "POST" -> {
                client.need(AdminScope.SERVERS_WRITE)
                val body = text(exchange)
                val draining = if (body.isBlank()) true else body(body, Drain.serializer()).draining
                hub.drain(parts[1], draining)?.let { throw Fail(404, it) }
                answer(exchange, 200, Answer(name = parts[1]))
            }
            parts == listOf("players") && method == "GET" -> {
                client.need(AdminScope.PRESENCE_READ)
                answer(exchange, 200, Players(hub.adminPlayers()))
            }
            parts.size == 3 && parts[0] == "players" && method == "POST" -> player(exchange, client, parts[1], parts[2])
            parts == listOf("channels", "publish") && method == "POST" -> {
                client.need(AdminScope.CHANNELS_PUBLISH)
                val publish = body(text(exchange), Publish.serializer())
                hub.adminPublish(client.id, publish.channel, publish.value, publish.server, publish.group)?.let { throw Fail(400, it) }
                answer(exchange, 200, Answer())
            }
            parts.size == 3 && parts[0] == "requests" && parts[2] == "reply" && method == "POST" -> {
                client.need(AdminScope.CHANNELS_READ)
                val id = parts[1].toLongOrNull() ?: throw Fail(404, "\"${parts[1]}\" isn't a request")
                val reply = body(text(exchange), Reply.serializer())
                hub.adminReply(client.id, id, reply.value, reply.error)?.let { throw Fail(404, it) }
                answer(exchange, 200, Answer())
            }
            parts.size == 2 && parts[0] == "placements" && method == "POST" -> {
                client.need(AdminScope.PLACEMENT)
                val id = parts[1].toLongOrNull() ?: throw Fail(404, "\"${parts[1]}\" isn't a placement")
                hub.adminPlace(client.id, id, body(text(exchange), PlaceAnswer.serializer()))?.let { throw Fail(409, it) }
                answer(exchange, 200, Answer())
            }
            parts == listOf("events") && method == "GET" -> events(exchange, client)
            else -> throw Fail(404, "there's no $method ${exchange.requestURI.path}")
        }
    }

    private fun player(exchange: HttpExchange, client: AdminClient, player: String, action: String) {
        when (action) {
            "send" -> {
                client.need(AdminScope.PLAYERS_SEND)
                val send = body(text(exchange), Send.serializer())
                if ((send.server == null) == (send.group == null)) throw Fail(400, "a send names a server or a group")
                val balance = send.balance?.let { Balance.of(it) ?: throw Fail(400, "\"$it\" isn't least or fill") }
                val id = hub.findPlayer(player)?.id ?: throw Fail(404, "nobody called $player is online")
                val waitMillis = send.wait?.let { (it.coerceIn(0.0, Hub.MAX_WAIT_MILLIS / 1000.0) * 1000).toLong() }
                val outcome = try {
                    hub.adminSend(client.id, listOf(id), send.server, send.group, balance, send.data, waitMillis)
                        .get((waitMillis ?: 0) + SEND_ANSWER_MILLIS, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    throw Fail(504, "the proxy didn't move them in time")
                }
                if (outcome.error != null) throw Fail(409, outcome.error)
                answer(exchange, 200, Answer(server = outcome.server))
            }
            "message" -> {
                client.need(AdminScope.PLAYERS_MESSAGE)
                val message = body(text(exchange), Message.serializer())
                if (message.kind !in
                    setOf("chat", "title", "actionbar")
                ) {
                    throw Fail(400, "\"${message.kind}\" isn't chat, title or actionbar")
                }
                hub.adminMessage(player, message.kind, message.text, message.subtitle)?.let { throw Fail(404, it) }
                answer(exchange, 200, Answer())
            }
            "kick" -> {
                client.need(AdminScope.PLAYERS_KICK)
                hub.adminKick(player, body(text(exchange).ifBlank { "{}" }, Kick.serializer()).message)?.let { throw Fail(404, it) }
                answer(exchange, 200, Answer())
            }
            else -> throw Fail(404, "there's no POST /v1/players/<player>/$action")
        }
    }

    /** `GET /v1/events`: the client's stream, as Server-Sent Events, until it goes. */
    private fun events(exchange: HttpExchange, client: AdminClient) {
        val query = (exchange.requestURI.rawQuery ?: "").split('&').filter { it.contains('=') }.associate {
            URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
        }
        fun list(key: String) = query[key].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val place = list("place")
        if (place.isNotEmpty()) client.need(AdminScope.PLACEMENT)
        val listen = list("listen")
        if (listen.isNotEmpty()) client.need(AdminScope.CHANNELS_READ)
        if (streaming.incrementAndGet() > MAX_STREAMS) {
            streaming.decrementAndGet()
            throw Fail(503, "$MAX_STREAMS admin streams are open already")
        }
        val stream = hub.attach(client.id, client.scopes, listen, place)
        try {
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.responseHeaders.add("Cache-Control", "no-store")
            exchange.sendResponseHeaders(200, 0)
            val out = exchange.responseBody
            out.write(": connected\n\n".toByteArray())
            out.flush()
            while (!stream.closed) {
                val event = stream.take(PING_MILLIS)
                val text = if (event == null) {
                    ": ping\n\n"
                } else {
                    "event: ${AdminEvent.name(event)}\ndata: ${Protocol.json.encodeToString(AdminEvent.serializer(), event)}\n\n"
                }
                out.write(text.toByteArray())
                out.flush()
            }
        } finally {
            stream.close()
            streaming.decrementAndGet()
        }
    }

    private fun list(exchange: HttpExchange) {
        val registered = hub.registered().associateBy { it.name }
        val waiting = hub.waiting()
        val views = hub.states().map { state ->
            val entry = registered[state.name]
            ServerView(
                name = state.name,
                connected = state.linked,
                group = state.group,
                capacity = state.capacity,
                accepting = state.accepting,
                players = state.players,
                state = state.state?.word,
                idle = state.idle,
                startupMillis = state.startupMillis,
                waiting = (waiting[state.name.lowercase()] ?: 0) + (state.group?.let { waiting[it.lowercase()] } ?: 0),
                source = when {
                    entry == null -> "config"
                    entry.byAdmin -> "admin"
                    else -> "registered"
                },
                address = entry?.let { "${it.host}:${it.port}" }
            )
        }
        answer(exchange, 200, Servers(views))
    }

    private fun add(exchange: HttpExchange) {
        val request = body(text(exchange), AddServer.serializer())
        val colon = request.address.lastIndexOf(':')
        val port = request.address.substring(colon + 1).toIntOrNull()
        if (colon <= 0 || port == null) throw Fail(400, "\"${request.address}\" isn't host:port")
        val host = request.address.substring(0, colon).removePrefix("[").removeSuffix("]")
        hub.adminRegister(request.name, host, port, request.group, request.credential)?.let { throw Fail(409, it) }
        answer(exchange, 201, Answer(name = request.name))
    }

    private fun text(exchange: HttpExchange): String {
        val bytes = exchange.requestBody.use { it.readNBytes(MAX_BODY + 1) }
        if (bytes.size > MAX_BODY) throw Fail(413, "the body is over $MAX_BODY bytes")
        return bytes.toString(Charsets.UTF_8)
    }

    private fun <T> body(text: String, serializer: KSerializer<T>): T = try {
        json.decodeFromString(serializer, text)
    } catch (e: Exception) {
        throw Fail(400, "the body isn't what this takes: ${e.message}")
    }

    private inline fun <reified T> answer(exchange: HttpExchange, status: Int, value: T) {
        val bytes = json.encodeToString(kotlinx.serialization.serializer<T>(), value).toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    companion object {
        private const val MAX_BODY = 64 * 1024
        private const val MAX_THREADS = 48
        private const val MAX_STREAMS = 32
        private const val PING_MILLIS = 15_000L

        /** How long a send's answer may take past its wait: the proxy moving the player. */
        private const val SEND_ANSWER_MILLIS = 35_000L

        private val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }
    }
}
