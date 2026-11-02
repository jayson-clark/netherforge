package dev.netherforge.network

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The hub's webhook: each [HubEvent] POSTed as JSON to [url], signed with [secret] (`X-NetherForge-Signature:
 * sha256=<hex HMAC-SHA256 of the body>`) so the receiver knows it's the hub's. Sent off the hub's threads, once each;
 * a failure is logged, never retried (the next event says the state again).
 */
class HubEvents(
    private val url: URI,
    private val secret: String?,
    private val log: HubLog,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    fun send(event: HubEvent) {
        val body = Protocol.json.encodeToString(JsonObject.serializer(), json(event))
        val request = HttpRequest.newBuilder(url)
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("User-Agent", "NetherForge hub")
            .apply { if (secret != null) header("X-NetherForge-Signature", "sha256=" + sign(secret, body)) }
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).whenComplete { response, failure ->
            when {
                failure != null -> log.warn("NetherForge hub: the webhook at $url failed for ${event.name}: ${failure.message}")
                response.statusCode() !in 200..299 -> log.warn(
                    "NetherForge hub: the webhook at $url answered ${response.statusCode()} for ${event.name}"
                )
            }
        }
    }

    /** [event] as the webhook's body: `{ "event": "server_idle", "at": <epoch millis>, ... }`. */
    fun json(event: HubEvent): JsonObject = buildJsonObject {
        put("event", event.name)
        put("at", clock())
        when (event) {
            is HubEvent.ServerReady -> {
                put("server", event.server)
                event.group?.let { put("group", it) }
                event.startupMillis?.let { put("startup_millis", it) }
            }
            is HubEvent.ServerIdle -> {
                put("server", event.server)
                event.group?.let { put("group", it) }
            }
            is HubEvent.ServerGone -> {
                put("server", event.server)
                event.group?.let { put("group", it) }
            }
            is HubEvent.Reported -> {
                put("report", Protocol.json.encodeToJsonElement(Report.serializer(), event.report))
            }
            is HubEvent.GroupWanted -> {
                put("group", event.group)
                put("waiting", JsonPrimitive(event.waiting))
            }
        }
    }

    companion object {
        /** The signature of [body] under [secret], as the header carries it (after `sha256=`). */
        fun sign(secret: String, body: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            return mac.doFinal(body.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }
    }
}
