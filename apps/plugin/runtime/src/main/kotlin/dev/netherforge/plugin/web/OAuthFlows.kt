package dev.netherforge.plugin.web

import dev.netherforge.format.project.Requirement
import dev.netherforge.plugin.async.AsyncWork
import dev.netherforge.plugin.async.WorkFailed
import dev.netherforge.plugin.binding.Events
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.binding.OAuthCompleteEvent
import dev.netherforge.plugin.binding.OAuthFailEvent
import dev.netherforge.plugin.binding.OAuthOptions
import dev.netherforge.plugin.http.HttpCall
import dev.netherforge.plugin.http.HttpFetcher
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.script.Scope
import dev.netherforge.plugin.script.Scripts
import dev.netherforge.plugin.session.RuntimeService
import dev.netherforge.plugin.session.liveness
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.net.URISyntaxException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * "Log in with…" flows (`nf.web.oauth`): OAuth 2's authorization code grant
 * with PKCE, the security-sensitive parts kept here rather than in scripts.
 *
 * A flow has a route of its own, `GET /<namespace>/oauth/<name>/callback`.
 * [begin] makes a login: a random state and code verifier, tied to the player,
 * kept here (never in the URL but the state and the verifier's challenge),
 * used once and forgotten after [LOGIN_TICKS]. The callback finds the login
 * by its state (an unknown one is refused with no event: it's forged or used
 * already) and asks the browser to confirm which Minecraft player it signs in
 * as: the login is tied to the player who began it, not to the browser that
 * finishes it, so a link handed to someone else would sign *them* in as that
 * player. Only once they confirm (`POST /<namespace>/oauth/<name>/confirm`,
 * with a single-use token from that page) is the code traded for a token at
 * the flow's token endpoint on a worker (through `nf.http`'s guarded client,
 * its host held to the package's `requires.http`); then `complete` or `fail`
 * is raised on the flow and the browser is answered with a page saying so. In a test run ([testing]) the endpoint
 * isn't called: [testTokens] (`nf.test.oauth_token`) answers instead.
 *
 * A flow belongs to the scope that made it; logins under way go with it.
 */
class OAuthFlows(
    private val scripts: Scripts,
    private val web: WebRoutes,
    private val async: AsyncWork,
    private val fetcher: () -> HttpFetcher,
    private val ticks: () -> Long,
    private val testing: Boolean,
    /** A player's name as the server knows it now, for the confirm page; null when they're offline. */
    private val playerName: (LuaHandle.Player) -> String? = { null }
) : RuntimeService {
    override val name get() = "login flows"

    class Flow(
        val number: Long,
        val scope: Scope,
        val namespace: String,
        val options: OAuthOptions,
        /** The host requirement, bound to the package that made the flow: the token endpoint's, and each redirect's. */
        val declared: (Requirement) -> Unit,
        val handle: LuaHandle.OAuthFlow
    ) {
        lateinit var route: WebRoutes.Route
        lateinit var confirmRoute: WebRoutes.Route
        val logins = LinkedHashMap<String, Login>()

        /** Logins back from the service, waiting for the browser to confirm the player, by the confirm page's token. */
        val confirmations = LinkedHashMap<String, Confirmation>()
        var testToken: JsonObject? = null
    }

    class Login(val player: LuaHandle.Player, val name: String, val verifier: String, val expires: Long)

    class Confirmation(val login: Login, val code: String)

    private val flows = LinkedHashMap<Long, Flow>()
    private var nextNumber = 1L
    private val random = SecureRandom()

    fun find(number: Long): Flow? = flows[number]

    /** Makes a flow for [scope] from [options], checked: errors at the script's line. */
    fun create(scope: Scope, options: OAuthOptions, declared: (Requirement) -> Unit, check: (Requirement) -> Unit): Flow {
        if (!NAME.matches(
                options.name
            )
        ) {
            throw LuaApiException("bad argument 'options.name' (lowercase letters, digits, - and _ expected, got \"${options.name}\")")
        }
        https(options.authorizeUrl, "options.authorize_url")
        val token = https(options.tokenUrl, "options.token_url")
        check(Requirement.Http(token.host.lowercase()))
        if (options.clientId.isBlank()) throw LuaApiException("bad argument 'options.client_id' (it's empty)")
        if (flows.values.any { it.namespace == scope.namespace && it.options.name == options.name }) {
            throw LuaApiException("package \"${scope.namespace}\" already has a login flow named \"${options.name}\"")
        }
        val number = nextNumber++
        val flow = Flow(number, scope, scope.namespace, options, declared, LuaHandle.OAuthFlow(number))
        flow.route = web.add(scope.namespace, "GET", callbackPath(options.name), null, null) { request, _ -> callback(flow, request) }
        flow.confirmRoute = web.add(scope.namespace, "POST", confirmPath(options.name), null, null) { request, _ -> confirm(flow, request) }
        flows[number] = flow
        return flow
    }

    private fun https(text: String, where: String): URI {
        val url = try {
            URI(text)
        } catch (e: URISyntaxException) {
            throw LuaApiException("bad argument '$where' (not a URL: ${e.reason})")
        }
        if (url.scheme != "https" ||
            url.host.isNullOrEmpty()
        ) {
            throw LuaApiException("bad argument '$where' (an https:// URL expected, got \"$text\")")
        }
        return url
    }

    /** The address to send [player] to, for a new login of [flow]; null while web routes are off. */
    fun begin(flow: Flow, player: LuaHandle.Player): String? {
        val redirect = web.url(flow.namespace, callbackPath(flow.options.name), emptyMap()) ?: return null
        val now = ticks()
        flow.logins.values.removeAll { it.expires <= now }
        while (flow.logins.size >= MAX_LOGINS) flow.logins.remove(flow.logins.keys.first())
        val state = token()
        val verifier = token()
        flow.logins[state] = Login(player, playerName(player) ?: "this player", verifier, now + LOGIN_TICKS)
        val challenge = base64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)))
        val query = LinkedHashMap<String, String>()
        query.putAll(flow.options.parameters.orEmpty())
        query["response_type"] = "code"
        query["client_id"] = flow.options.clientId
        query["redirect_uri"] = redirect
        flow.options.scopes?.takeIf { it.isNotEmpty() }?.let { query["scope"] = it.joinToString(" ") }
        query["state"] = state
        query["code_challenge"] = challenge
        query["code_challenge_method"] = "S256"
        val base = flow.options.authorizeUrl
        return base + (if ('?' in base) "&" else "?") + RoutePath.encodeQuery(query)
    }

    /** Ends [flow]: its route goes and logins under way are forgotten. False when it had already gone. */
    fun remove(flow: Flow): Boolean {
        if (flows.remove(flow.number) == null) return false
        web.remove(flow.route)
        web.remove(flow.confirmRoute)
        flow.logins.clear()
        flow.confirmations.clear()
        scripts.dropTarget(flow.handle)
        return true
    }

    private fun callback(flow: Flow, request: WebRoutes.Pending) {
        val query = RoutePath.query(request.call.rawQuery)
        val login = query["state"]?.let { flow.logins.remove(it) }
        if (login == null) {
            web.finish(request, page(400, "This sign-in link has expired or was already used. Start again from the game."))
            return
        }
        val failure = when {
            login.expires <= ticks() -> "the login expired before it finished"
            query["error"] != null -> "the service said ${query["error"]}" + (query["error_description"]?.let { ": $it" } ?: "")
            query["code"].isNullOrEmpty() -> "the service sent no code"
            else -> null
        }
        if (failure != null) return failed(flow, login, request, failure)
        val token = token()
        while (flow.confirmations.size >= MAX_LOGINS) flow.confirmations.remove(flow.confirmations.keys.first())
        flow.confirmations[token] = Confirmation(login, query.getValue("code"))
        web.finish(request, confirmPage(flow, login, token))
    }

    /** The browser confirmed the player: the code is traded for a token. */
    private fun confirm(flow: Flow, request: WebRoutes.Pending) {
        val form = RoutePath.query(String(request.call.body, StandardCharsets.UTF_8))
        val confirmation = form["token"]?.let { flow.confirmations.remove(it) }
        if (confirmation == null || confirmation.login.expires <= ticks()) {
            web.finish(request, page(400, "This sign-in has expired or was already used. Start again from the game."))
            return
        }
        exchange(flow, confirmation.login, confirmation.code, request)
    }

    private fun exchange(flow: Flow, login: Login, code: String, request: WebRoutes.Pending) {
        val redirect = web.url(flow.namespace, callbackPath(flow.options.name), emptyMap())
            ?: "http://localhost/${flow.namespace}${callbackPath(flow.options.name)}"
        val answer: CompletableFuture<JsonObject> = if (testing) {
            CompletableFuture.completedFuture(flow.testToken ?: DEFAULT_TEST_TOKEN)
        } else {
            val call = tokenRequest(flow, code, login.verifier, redirect)
            val fetcher = fetcher()
            async.submit {
                val reply = fetcher.fetch(call) { host -> flow.declared(Requirement.Http(host)) }
                Json.parseToJsonElement(reply.body) as? JsonObject
                    ?: throw WorkFailed("the token endpoint answered ${reply.status} with something that isn't a JSON object")
            }
        }
        answer.whenCompleteAsync({ token, error ->
            if (!flows.containsKey(flow.number)) {
                web.finish(request, page(503, "Sign-in isn't available right now."))
                return@whenCompleteAsync
            }
            val access = token?.get("access_token")?.jsonPrimitive?.contentOrNull
            when {
                error != null -> failed(flow, login, request, (error.cause as? WorkFailed ?: error).message ?: "the token request failed")
                access == null -> {
                    val why = token?.string("error_description") ?: token?.string("error") ?: "no access token"
                    failed(flow, login, request, "the service gave no token: $why")
                }
                else -> {
                    web.finish(request, page(200, "Signed in. You can close this tab and go back to the game."))
                    scripts.emit(
                        Events.OAUTHFLOW_COMPLETE,
                        flow.handle,
                        OAuthCompleteEvent(
                            flow.handle,
                            login.player,
                            access,
                            token.string("token_type"),
                            token.string("refresh_token"),
                            token["expires_in"]?.let {
                                (it as? JsonPrimitive)?.longOrNull ?: it.jsonPrimitive.contentOrNull?.toLongOrNull()
                            },
                            token.string("scope"),
                            token.string("id_token")
                        )
                    )
                }
            }
        }, if (testing) Executor(Runnable::run) else async.mainThread)
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun tokenRequest(flow: Flow, code: String, verifier: String, redirect: String): HttpCall {
        val options = flow.options
        val form =
            linkedMapOf("grant_type" to "authorization_code", "code" to code, "redirect_uri" to redirect, "code_verifier" to verifier)
        val headers = linkedMapOf("Content-Type" to "application/x-www-form-urlencoded", "Accept" to "application/json")
        if (options.clientAuth == "basic" && options.clientSecret != null) {
            val pair = "${RoutePath.encode(options.clientId)}:${RoutePath.encode(options.clientSecret)}"
            headers["Authorization"] = "Basic " + Base64.getEncoder().encodeToString(pair.toByteArray(StandardCharsets.UTF_8))
        } else {
            form["client_id"] = options.clientId
            options.clientSecret?.let { form["client_secret"] = it }
        }
        return HttpCall("POST", URI(options.tokenUrl), headers, RoutePath.encodeQuery(form).toByteArray(StandardCharsets.UTF_8))
    }

    private fun failed(flow: Flow, login: Login, request: WebRoutes.Pending, why: String) {
        web.finish(request, page(400, "Signing in didn't work: $why. Start again from the game."))
        scripts.emit(Events.OAUTHFLOW_FAIL, flow.handle, OAuthFailEvent(flow.handle, login.player, why))
    }

    private fun token(): String = ByteArray(TOKEN_BYTES).also(random::nextBytes).let(::base64)

    private fun base64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** A small page for the browser that came back. */
    private fun page(status: Int, text: String, more: String = ""): WebAnswer {
        val html = "<!doctype html><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\">" +
            "<title>Sign-in</title><body style=\"font-family:system-ui,sans-serif;max-width:32em;margin:4em auto;padding:0 1em\">" +
            "<p>${escape(text)}</p>$more</body>"
        return WebAnswer(
            status,
            mapOf("content-type" to "text/html; charset=utf-8", "referrer-policy" to "no-referrer"),
            html.toByteArray()
        )
    }

    /** Asks the browser whether it signs in as [login]'s player, with a form that confirms it once ([confirm]). */
    private fun confirmPage(flow: Flow, login: Login, token: String): WebAnswer {
        val action = "/${flow.namespace}${confirmPath(flow.options.name)}"
        val form = "<form method=\"post\" action=\"${escape(action)}\">" +
            "<input type=\"hidden\" name=\"token\" value=\"${escape(token)}\">" +
            "<button type=\"submit\">Sign in as ${escape(login.name)}</button></form>"
        return page(
            200,
            "This signs your account in for the Minecraft player ${login.name}. Only continue if you are ${login.name} " +
                "and you started this sign-in in the game.",
            form
        )
    }

    override fun scopeReleased(scope: Scope) {
        for (flow in flows.values.filter { it.scope.id == scope.id }) remove(flow)
    }

    override fun stop() {
        for (flow in flows.values.toList()) remove(flow)
    }

    override fun costs(): Map<String, (Scope) -> Int> = mapOf("logins" to { scope -> flows.values.count { it.scope.id == scope.id } })

    override fun liveness() = listOf(liveness<LuaHandle.OAuthFlow> { flows.containsKey(it.number) })

    companion object {
        private val NAME = Regex("^[a-z0-9_-]{1,32}$")
        private const val TOKEN_BYTES = 32
        private const val MAX_LOGINS = 1000

        /** How long a login may take: 10 minutes. */
        const val LOGIN_TICKS = 20L * 60 * 10

        private val DEFAULT_TEST_TOKEN = JsonObject(
            mapOf(
                "access_token" to JsonPrimitive("test-token"),
                "token_type" to JsonPrimitive("Bearer")
            )
        )

        fun callbackPath(name: String) = "/oauth/$name/callback"

        fun confirmPath(name: String) = "/oauth/$name/confirm"
    }
}
