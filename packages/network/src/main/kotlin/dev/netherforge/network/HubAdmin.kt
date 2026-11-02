package dev.netherforge.network

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The hub's admin API: HTTP for what runs the network's servers (an orchestrator starting and stopping them), off
 * unless the owner gives it a port and a [token]. Every request carries `Authorization: Bearer <token>`, compared in
 * constant time; it's never a server's credential. JSON in and out:
 *
 * - `GET /v1/servers`: every server, as the hub knows it.
 * - `POST /v1/servers` `{ "name", "address": "host:port", "group"?, "credential"? }`: adds a server to the proxy, for a
 *   server that joins with that credential (any, when left out) as an instance of that group. Again with a new address
 *   moves it.
 * - `DELETE /v1/servers/<name>`: takes it off the proxy, disconnecting it (its players sent to its group's fallback first).
 * - `POST /v1/servers/<name>/drain` (`{ "draining": false }` to stop): asks the server to take no new players.
 *
 * Plain HTTP, like the hub's own port: keep it on the servers' network, or put TLS in front.
 */
class HubAdmin(private val hub: Hub, private val bind: InetSocketAddress, private val token: String, private val log: HubLog) {
    private var server: HttpServer? = null
    private var executor: ExecutorService? = null

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
    data class Drain(val draining: Boolean = true)

    @Serializable
    data class Answer(val name: String? = null, val error: String? = null)

    fun start() {
        require(token.length >= MIN_TOKEN) { "the admin API's token is shorter than $MIN_TOKEN characters" }
        val http = HttpServer.create(bind, 16)
        val pool = Executors.newFixedThreadPool(2) { Thread(it, "NetherForge hub admin").apply { isDaemon = true } }
        http.executor = pool
        http.createContext("/v1/servers") { exchange ->
            try {
                handle(exchange)
            } catch (e: Exception) {
                log.warn("NetherForge hub: the admin API failed on ${exchange.requestMethod} ${exchange.requestURI}: ${e.message}")
                answer(exchange, 500, Answer(error = "the hub failed: ${e.message}"))
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

    private fun handle(exchange: HttpExchange) {
        val given = exchange.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")?.trim().orEmpty()
        if (!MessageDigest.isEqual(given.toByteArray(), token.toByteArray())) {
            return answer(exchange, 401, Answer(error = "a request needs Authorization: Bearer <the admin token>"))
        }
        val rest = exchange.requestURI.path.removePrefix("/v1/servers").trim('/')
        when {
            rest.isEmpty() && exchange.requestMethod == "GET" -> list(exchange)
            rest.isEmpty() && exchange.requestMethod == "POST" -> add(exchange)
            rest.endsWith("/drain") && exchange.requestMethod == "POST" -> {
                val name = rest.removeSuffix("/drain")
                val body = exchange.requestBody.use { it.readNBytes(MAX_BODY) }.toString(Charsets.UTF_8)
                val draining = try {
                    if (body.isBlank()) true else json.decodeFromString(Drain.serializer(), body).draining
                } catch (e: Exception) {
                    return answer(exchange, 400, Answer(error = "the body isn't { \"draining\": true|false }: ${e.message}"))
                }
                val why = hub.drain(name, draining)
                if (why == null) answer(exchange, 200, Answer(name = name)) else answer(exchange, 404, Answer(error = why))
            }
            rest.isNotEmpty() && exchange.requestMethod == "DELETE" -> {
                val why = hub.adminRemove(rest)
                if (why == null) answer(exchange, 200, Answer(name = rest)) else answer(exchange, 404, Answer(error = why))
            }
            else -> answer(exchange, 405, Answer(error = "${exchange.requestMethod} isn't allowed here"))
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
        val body = exchange.requestBody.use { it.readNBytes(MAX_BODY + 1) }
        if (body.size > MAX_BODY) return answer(exchange, 413, Answer(error = "the body is over $MAX_BODY bytes"))
        val request = try {
            json.decodeFromString(AddServer.serializer(), body.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            return answer(
                exchange,
                400,
                Answer(error = "the body isn't { \"name\", \"address\", \"group\"?, \"credential\"? }: ${e.message}")
            )
        }
        val colon = request.address.lastIndexOf(':')
        val port = request.address.substring(colon + 1).toIntOrNull()
        if (colon <= 0 || port == null) return answer(exchange, 400, Answer(error = "\"${request.address}\" isn't host:port"))
        val host = request.address.substring(0, colon).removePrefix("[").removeSuffix("]")
        val why = hub.adminRegister(request.name, host, port, request.group, request.credential)
        if (why == null) answer(exchange, 201, Answer(name = request.name)) else answer(exchange, 409, Answer(error = why))
    }

    private inline fun <reified T> answer(exchange: HttpExchange, status: Int, value: T) {
        val bytes = json.encodeToString(kotlinx.serialization.serializer<T>(), value).toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    companion object {
        const val MIN_TOKEN = 24
        private const val MAX_BODY = 64 * 1024
        private val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }
    }
}
