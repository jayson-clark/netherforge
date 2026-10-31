package dev.netherforge.plugin.api

import dev.netherforge.format.project.Requirement
import dev.netherforge.plugin.async.WorkFailed
import dev.netherforge.plugin.binding.Caller
import dev.netherforge.plugin.binding.HttpRequestOptions
import dev.netherforge.plugin.binding.HttpResponse
import dev.netherforge.plugin.binding.HttpStreamOptions
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.binding.NfHttpApi
import dev.netherforge.plugin.binding.WebSocketOptions
import dev.netherforge.plugin.http.ConnectionRequest
import dev.netherforge.plugin.http.HttpCall
import dev.netherforge.plugin.http.HttpFetcher
import dev.netherforge.plugin.http.HttpReply
import dev.netherforge.plugin.http.RequestRate
import dev.netherforge.plugin.http.StreamFormat
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.session.ProjectSession
import java.net.URI
import java.net.URISyntaxException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * `nf.http`: requests to web services.
 *
 * What a script got wrong in the call (a URL that isn't http or https, a
 * host its package didn't declare, a header that can't be sent, a body over
 * the limit) is an error at its line, found on the main thread before
 * anything starts. What only the network can say, or the owner's limits (a
 * name that doesn't resolve, an address that isn't allowed, a timeout, the
 * rate, a redirect to an undeclared host), is the work's failure: `nil, err`.
 * The request runs on a worker ([HttpFetcher]); its redirects' hosts are held
 * to the calling package's declaration with the check bound here, on the
 * main thread, to the package whose code made the call.
 *
 * `stream` and `websocket` check their call the same way, then hand the
 * connection to `session.connections` ([dev.netherforge.plugin.http.Connections]),
 * which owns it from there. Opening one counts against the same request rate.
 */
internal class NfHttpImpl(private val session: ProjectSession) : NfHttpApi {
    private val limits get() = session.config.http
    private val rate by lazy { RequestRate(limits.requestsPerMinute) }

    override fun request(caller: Caller, options: HttpRequestOptions): CompletionStage<HttpResponse> =
        fetch(caller, options, "nf.http.request") { reply ->
            HttpResponse(reply.status.toLong(), reply.headers, reply.body, reply.url.toString())
        }

    /**
     * A request as `nf.http.request` makes it, checked the same way for [what] (the function a script called), with
     * [read] turning the reply into its result on the worker, after the request: `nf.images.download` decodes there.
     */
    internal fun <T> fetch(caller: Caller, options: HttpRequestOptions, what: String, read: (HttpReply) -> T): CompletionStage<T> {
        val url = url(options.url, HTTP)
        val host = HttpFetcher.hostOf(url)!!
        val method = options.method ?: "GET"
        val body = body(method, options.body)
        val headers = headers(options.headers.orEmpty())
        // The narrow check: this host, for the package whose code is running. Redirects are held to the same one, bound to it here.
        session.requirements.check(Requirement.Http(host), what)
        val held = session.requirements.held(what)
        rateRefusal(caller)?.let { return CompletableFuture.failedFuture(WorkFailed(it)) }
        val call = HttpCall(method, url, headers, body)
        val fetcher = session.http.fetcher
        return session.async.submit {
            read(fetcher.fetch(call) { redirectedTo -> held(Requirement.Http(redirectedTo)) })
        }
    }

    override fun stream(caller: Caller, options: HttpStreamOptions): LuaHandle.HttpStream {
        val url = url(options.url, HTTP)
        val method = options.method ?: "GET"
        val body = body(method, options.body)
        val format = StreamFormat.of(options.format)
        val headers = headers(options.headers.orEmpty()).toMutableMap()
        if (format == StreamFormat.EVENTS &&
            headers.keys.none { it.equals("accept", ignoreCase = true) }
        ) {
            headers["Accept"] = "text/event-stream"
        }
        session.requirements.check(Requirement.Http(HttpFetcher.hostOf(url)!!), "nf.http.stream")
        val held = session.requirements.held("nf.http.stream")
        val request = ConnectionRequest(url, method, headers, body, format, emptyList()) { host -> held(Requirement.Http(host)) }
        return session.connections.open(caller.scope, request, options.url, false, rateRefusal(caller)).handle as LuaHandle.HttpStream
    }

    override fun websocket(caller: Caller, options: WebSocketOptions): LuaHandle.WebSocket {
        val url = url(options.url, WEBSOCKET)
        val headers = headers(options.headers.orEmpty())
        headers.keys.firstOrNull { it.lowercase().startsWith("sec-websocket-") }?.let {
            throw LuaApiException("options.headers can't set \"$it\": the server sets it (offer subprotocols with options.protocols)")
        }
        val protocols = options.protocols.orEmpty()
        protocols.firstOrNull {
            !TOKEN.matches(it)
        }?.let { throw LuaApiException("options.protocols has \"$it\", which isn't a protocol name") }
        session.requirements.check(Requirement.Http(url.host.lowercase()), "nf.http.websocket")
        val request = ConnectionRequest(url, "GET", headers, null, null, protocols) { host ->
            throw LuaApiException("WebSockets don't follow redirects (to $host)")
        }
        return session.connections.open(caller.scope, request, options.url, true, rateRefusal(caller)).handle as LuaHandle.WebSocket
    }

    /** [text] as a URL of one of [schemes] with a host, no credentials and a port that is one; else an error at the script's line. */
    private fun url(text: String, schemes: Set<String>): URI {
        val url = try {
            URI(text)
        } catch (e: URISyntaxException) {
            throw LuaApiException("options.url isn't a valid URL: ${e.reason}")
        }
        val written = schemes.joinToString(" or ") { "$it://" }
        if (url.scheme !in schemes || url.host.isNullOrEmpty()) {
            throw LuaApiException("options.url must be an $written URL with a host (got \"$text\")")
        }
        if (url.rawUserInfo !=
            null
        ) {
            throw LuaApiException("options.url can't have a user name or password in it: send credentials in a header")
        }
        val port = if (url.port == -1) 1 else url.port
        if (port !in 1..MAX_PORT) throw LuaApiException("options.url has a port that isn't one")
        return url
    }

    private fun body(method: String, text: String?): ByteArray? {
        val body = text?.toByteArray(StandardCharsets.UTF_8) ?: return null
        if (method in NO_BODY) throw LuaApiException("a $method request can't have a body")
        if (body.size > limits.maxRequestBytes) {
            throw LuaApiException(
                "options.body is ${body.size} bytes, over the server's limit of ${limits.maxRequestBytes} (http.max-request-bytes in config.yml)"
            )
        }
        return body
    }

    /** Why the package can't start another request this minute, or null when it may (and it's counted). */
    private fun rateRefusal(caller: Caller): String? {
        if (rate.tryStart(caller.scope.namespace)) return null
        return "package \"${caller.scope.namespace}\" has made ${limits.requestsPerMinute} requests in the last minute, " +
            "the server's limit (http.requests-per-minute in config.yml)"
    }

    /** [given] as headers that can be sent as they are: names that are tokens, values of printable ASCII (and tabs), none the server sets itself, none twice. */
    private fun headers(given: Map<String, String>): Map<String, String> {
        val seen = HashSet<String>()
        for ((name, value) in given) {
            if (!TOKEN.matches(name)) throw LuaApiException("options.headers has a name that isn't a header name: \"$name\"")
            if (!seen.add(name.lowercase())) throw LuaApiException("options.headers has \"$name\" twice (names are compared without case)")
            if (name.lowercase() in SERVER_SET) throw LuaApiException("options.headers can't set \"$name\": the server sets it")
            if (value.any { it.code > 0x7e || (it.code < 0x20 && it != '\t') || it.code == 0x7f }) {
                throw LuaApiException("options.headers[\"$name\"] has a character a header can't hold (only printable ASCII and tabs)")
            }
        }
        return given
    }

    private companion object {
        const val MAX_PORT = 65535
        val NO_BODY = setOf("GET", "HEAD", "OPTIONS")
        val HTTP = setOf("http", "https")
        val WEBSOCKET = setOf("ws", "wss")
        val TOKEN = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")
        val SERVER_SET = setOf(
            "host", "content-length", "transfer-encoding", "connection", "upgrade", "expect", "te", "trailer",
            "keep-alive", "proxy-connection", "accept-encoding"
        )
    }
}
