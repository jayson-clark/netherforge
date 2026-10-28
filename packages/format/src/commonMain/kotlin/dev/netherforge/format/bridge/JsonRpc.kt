package dev.netherforge.format.bridge

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * JSON-RPC 2.0 (https://www.jsonrpc.org/specification), the dev bridge's
 * envelope: one message (or batch) per NDJSON line.
 *
 * Written here rather than taken from a library: format is Kotlin
 * Multiplatform common code (the plugin on the JVM, the CLI and editor
 * helpers on JS), and no maintained JSON-RPC library is multiplatform and
 * transport-agnostic (LSP4J is JVM-only with Gson and LSP's framing;
 * kotlinx-rpc speaks its own protocol). The spec is small: this is all of it,
 * batches included. The editor's side is the standard `vscode-jsonrpc`.
 */
sealed interface RpcMessage

/** A call that wants an answer: exactly one [RpcResponse] with the same [id]. */
data class RpcRequest(val id: JsonPrimitive, val method: String, val params: JsonElement? = null) : RpcMessage

/** A call that wants none: never answered, not even with an error. */
data class RpcNotification(val method: String, val params: JsonElement? = null) : RpcMessage

/** The answer to a request: [result] or [error], never both. [id] is [JsonNull] only when the request's couldn't be read. */
data class RpcResponse(val id: JsonPrimitive, val result: JsonElement? = null, val error: RpcError? = null) : RpcMessage {
    init {
        require((result == null) != (error == null)) { "a response has a result or an error" }
    }

    companion object {
        fun ok(id: JsonPrimitive, result: JsonElement?) = RpcResponse(id, result = result ?: JsonNull)

        fun error(id: JsonPrimitive, code: Int, message: String, data: JsonElement? = null) =
            RpcResponse(id, error = RpcError(code, message, data))
    }
}

/**
 * A line that isn't a message: unparseable JSON or an object that isn't a
 * request, notification or response. Whoever reads it answers with [error]
 * (to [id], [JsonNull] when it couldn't be read), as the spec asks.
 */
data class RpcInvalid(val id: JsonPrimitive, val error: RpcError) : RpcMessage

@Serializable
data class RpcError(val code: Int, val message: String, val data: JsonElement? = null)

/** One line: a message, or a batch of them (a JSON array). */
sealed interface RpcFrame {
    data class Single(val message: RpcMessage) : RpcFrame

    data class Batch(val messages: List<RpcMessage>) : RpcFrame
}

object JsonRpc {
    const val VERSION = "2.0"

    // The spec's codes.
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603

    private val json = Json { prettyPrint = false }

    fun encode(message: RpcMessage): JsonObject = buildJsonObject {
        put("jsonrpc", VERSION)
        when (message) {
            is RpcRequest -> {
                put("id", message.id)
                put("method", message.method)
                message.params?.let { put("params", it) }
            }
            is RpcNotification -> {
                put("method", message.method)
                message.params?.let { put("params", it) }
            }
            is RpcResponse -> {
                put("id", message.id)
                message.result?.let { put("result", it) }
                message.error?.let { put("error", error(it)) }
            }
            is RpcInvalid -> error("an invalid message is answered, never sent")
        }
    }

    private fun error(error: RpcError) = buildJsonObject {
        put("code", error.code)
        put("message", error.message)
        error.data?.let { put("data", it) }
    }

    /** [message] as one line, without its newline. */
    fun line(message: RpcMessage): String = json.encodeToString(JsonObject.serializer(), encode(message))

    /** A batch as one line, without its newline. */
    fun line(batch: List<RpcMessage>): String = json.encodeToString(JsonArray.serializer(), JsonArray(batch.map(::encode)))

    /** Reads one line. Never throws: what isn't a message is an [RpcInvalid], to be answered. */
    fun decode(line: String): RpcFrame {
        val element = try {
            json.parseToJsonElement(line)
        } catch (e: Exception) {
            return RpcFrame.Single(RpcInvalid(JsonNull, RpcError(PARSE_ERROR, "Not JSON: ${e.message}")))
        }
        if (element is JsonArray) {
            if (element.isEmpty()) return RpcFrame.Single(RpcInvalid(JsonNull, RpcError(INVALID_REQUEST, "An empty batch")))
            return RpcFrame.Batch(element.map(::message))
        }
        return RpcFrame.Single(message(element))
    }

    /** One message from its JSON. */
    fun message(element: JsonElement): RpcMessage {
        val obj = element as? JsonObject ?: return invalid(JsonNull, "A message is a JSON object")
        val rawId = obj["id"]
        val id = rawId as? JsonPrimitive
        val validId = id != null && (id is JsonNull || id.isString || id.content.toDoubleOrNull() != null)
        if ((obj["jsonrpc"] as? JsonPrimitive)?.takeIf { it.isString }?.content != VERSION) {
            return invalid(if (validId) id!! else JsonNull, "\"jsonrpc\" must be \"$VERSION\"")
        }
        if (rawId != null && !validId) return invalid(JsonNull, "An id is a string, a number or null")
        val method = obj["method"]
        if (method != null) {
            val name = (method as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: return invalid(id ?: JsonNull, "\"method\" must be a string")
            val params = obj["params"]
            if (params != null && params !is JsonObject && params !is JsonArray) {
                return invalid(id ?: JsonNull, "\"params\" must be an object or an array")
            }
            return if (rawId == null) RpcNotification(name, params) else RpcRequest(id!!, name, params)
        }
        if (rawId == null) return invalid(JsonNull, "A message has a \"method\", or an \"id\" and a result")
        val result = obj["result"]
        val error = obj["error"]
        return when {
            result != null && error == null -> RpcResponse(id!!, result = result)
            error != null && result == null -> {
                val fields = error as? JsonObject
                val code = (fields?.get("code") as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                val text = (fields?.get("message") as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (code == null || text == null) return invalid(id!!, "An error has an integer \"code\" and a \"message\"")
                RpcResponse(id!!, error = RpcError(code, text, fields["data"]))
            }
            else -> invalid(id!!, "A response has a \"result\" or an \"error\"")
        }
    }

    private fun invalid(id: JsonPrimitive, why: String) = RpcInvalid(id, RpcError(INVALID_REQUEST, why))
}
