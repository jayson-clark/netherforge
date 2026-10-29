package dev.netherforge.plugin.api

import dev.netherforge.plugin.binding.Caller
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.binding.NfWebApi
import dev.netherforge.plugin.binding.OAuthFlowApi
import dev.netherforge.plugin.binding.OAuthOptions
import dev.netherforge.plugin.binding.RouteApi
import dev.netherforge.plugin.binding.SignatureOptions
import dev.netherforge.plugin.binding.WebResponseApi
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.lua.LuaFunction
import dev.netherforge.plugin.lua.LuaValue
import dev.netherforge.plugin.session.ProjectSession
import dev.netherforge.plugin.web.OAuthFlows
import dev.netherforge.plugin.web.RoutePath
import dev.netherforge.plugin.web.WebAnswer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat

/**
 * `nf.web`: routes scripts answer, their package's public address, webhook
 * signatures and login flows. The `web` requirement is the generated
 * binding's; what's here holds each call to the calling scope's package.
 */
internal class NfWebImpl(private val session: ProjectSession) : NfWebApi {
    override fun route(caller: Caller, method: String, path: String, handler: LuaFunction): LuaHandle.Route {
        val scope = caller.scope
        // A path a login flow answers is taken like any other route's.
        val route = try {
            session.web.add(scope.namespace, method, path, scope, handler)
        } catch (e: LuaApiException) {
            session.scripts.unref(handler.ref)
            throw e
        }
        return LuaHandle.Route(route.number)
    }

    override fun url(caller: Caller, path: String?, query: Map<String, String>?): String? {
        val where = path ?: "/"
        RoutePath.parse(where).getOrElse { throw LuaApiException("bad argument 'path' (${it.message}, got \"$where\")") }
        return session.web.url(caller.scope.namespace, where, query.orEmpty())
    }

    override fun verifySignature(caller: Caller, body: String, signature: String, options: SignatureOptions): Boolean {
        val trimmed = signature.trim()
        val prefix = options.prefix.orEmpty()
        if (!trimmed.startsWith(prefix)) return false
        val given = trimmed.substring(prefix.length)
        val expected = hmac(body, options.secret, options.algorithm)
        val bytes = try {
            if (options.encoding == "base64") Base64.getDecoder().decode(given) else HexFormat.of().parseHex(given.lowercase())
        } catch (e: IllegalArgumentException) {
            return false
        }
        // Constant time: how much of it matched says nothing.
        return MessageDigest.isEqual(expected, bytes)
    }

    override fun oauth(caller: Caller, options: OAuthOptions): LuaHandle.OAuthFlow {
        val requirements = session.requirements
        val declared = requirements.held("nf.web.oauth")
        val flow = session.oauth.create(caller.scope, options, declared) { requirements.check(it, "nf.web.oauth") }
        return flow.handle
    }
}

/** `Route`: a route by number; once it's gone, false. */
internal class RouteImpl(private val session: ProjectSession) : RouteApi {
    override fun remove(self: LuaHandle.Route): Boolean = session.web.find(self.number)?.let { session.web.remove(it) } == true

    override fun exists(self: LuaHandle.Route): Boolean = session.web.find(self.number) != null
}

/** `WebResponse`: a request waiting for its answer, by number; once answered, false. */
internal class WebResponseImpl(private val session: ProjectSession) : WebResponseApi {
    private val limits get() = session.config.web

    override fun send(self: LuaHandle.WebResponse, status: Long, body: String?, headers: Map<String, String>?): Boolean {
        val code = status(status)
        val bytes = body?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0)
        val given = headers(headers.orEmpty())
        val all = LinkedHashMap<String, String>()
        all["content-type"] = "text/plain; charset=utf-8"
        all.putAll(given)
        return answer(self, WebAnswer(code, all, size(bytes)))
    }

    override fun json(self: LuaHandle.WebResponse, status: Long, value: LuaValue): Boolean {
        val code = status(status)
        val bytes = value.json().toString().toByteArray(StandardCharsets.UTF_8)
        return answer(self, WebAnswer(code, mapOf("content-type" to "application/json"), size(bytes)))
    }

    override fun redirect(self: LuaHandle.WebResponse, url: String, status: Long?): Boolean {
        val code = (status ?: FOUND).toInt()
        if (code !in REDIRECTS) throw LuaApiException("bad argument 'status' (301, 302, 303, 307 or 308 expected, got $code)")
        val absolute = url.startsWith("https://") || url.startsWith("http://")
        if ((!absolute && !url.startsWith("/")) || url.startsWith("//") || url.any { it.code < SPACE || it.code == DELETE }) {
            throw LuaApiException("bad argument 'url' (an http:// or https:// URL, or a path starting with one /, expected, got \"$url\")")
        }
        return answer(self, WebAnswer(code, mapOf("location" to url), ByteArray(0)))
    }

    override fun isPending(self: LuaHandle.WebResponse): Boolean = session.web.pending(self.number) != null

    private fun answer(self: LuaHandle.WebResponse, answer: WebAnswer): Boolean {
        val pending = session.web.pending(self.number) ?: return false
        val headers = LinkedHashMap(answer.headers)
        headers["x-content-type-options"] = "nosniff"
        headers.putIfAbsent("cache-control", "no-store")
        return session.web.finish(pending, WebAnswer(answer.status, headers, answer.body))
    }

    private fun status(status: Long): Int {
        if (status !in STATUSES) throw LuaApiException("bad argument 'status' (100 to 599 expected, got $status)")
        return status.toInt()
    }

    private fun size(bytes: ByteArray): ByteArray {
        if (bytes.size > limits.maxResponseBytes) {
            throw LuaApiException(
                "the body is ${bytes.size} bytes, over the server's limit of ${limits.maxResponseBytes} (web.max-response-bytes in config.yml)"
            )
        }
        return bytes
    }

    private fun headers(given: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((name, value) in given) {
            val lower = name.lowercase()
            if (!TOKEN.matches(name)) throw LuaApiException("bad argument 'headers' (\"$name\" isn't a header name)")
            if (lower in SERVER_SET) throw LuaApiException("bad argument 'headers' (\"$name\" is set by the server)")
            if (value.any { it.code > MAX_ASCII || (it.code < SPACE && it != '\t') }) {
                throw LuaApiException(
                    "bad argument 'headers' (\"$name\" has a character a header can't hold: only printable ASCII and tabs)"
                )
            }
            if (out.put(lower, value) !=
                null
            ) {
                throw LuaApiException("bad argument 'headers' (\"$name\" twice: names are compared without case)")
            }
        }
        return out
    }

    private companion object {
        val STATUSES = 100L..599L
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
        const val FOUND = 302L
        const val SPACE = 0x20
        const val DELETE = 0x7f
        const val MAX_ASCII = 0x7e
        val TOKEN = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")
        val SERVER_SET = setOf("content-length", "transfer-encoding", "connection", "date", "keep-alive", "upgrade", "te", "trailer")
    }
}

/** `OAuthFlow`: a login flow by number; once it's gone, nil and false. */
internal class OAuthFlowImpl(private val session: ProjectSession) : OAuthFlowApi {
    private fun flow(self: LuaHandle.OAuthFlow): OAuthFlows.Flow? = session.oauth.find(self.number)

    override fun begin(self: LuaHandle.OAuthFlow, player: LuaHandle.Player): String? = flow(self)?.let { session.oauth.begin(it, player) }

    override fun remove(self: LuaHandle.OAuthFlow): Boolean = flow(self)?.let { session.oauth.remove(it) } == true
}
