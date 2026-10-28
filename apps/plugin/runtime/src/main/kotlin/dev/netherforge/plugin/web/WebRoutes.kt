package dev.netherforge.plugin.web

import dev.netherforge.format.Problem
import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.project.PackagePaths
import dev.netherforge.format.project.ProjectManifest
import dev.netherforge.format.project.ProjectSnapshot
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.lua.CallResult
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.lua.LuaFunction
import dev.netherforge.plugin.script.Scope
import dev.netherforge.plugin.script.Scripts
import dev.netherforge.plugin.session.RuntimeService
import dev.netherforge.plugin.session.TickPhase
import dev.netherforge.plugin.session.liveness
import java.nio.charset.StandardCharsets

/**
 * The routes scripts answer (`nf.web.route`), and the requests waiting for
 * their answers: the main thread's half of web routes ([WebGate] is the
 * listener's).
 *
 * Every tick ([TickPhase.ASYNC]) it takes up to [PER_TICK] requests from the
 * gate's queue and routes each: by namespace (the first segment), then the
 * most specific of that package's routes matching the method and the rest of
 * the path. A script's route calls its handler as the script's code, with the
 * request (a table) and a [LuaHandle.WebResponse] to answer it by, then or
 * later; one a flow made (`nf.web.oauth`) calls Kotlin. Requests are answered
 * `404`/`405` here when nothing matches, `500` when the handler fails, `503`
 * when its script unloads first, `504` when [WebConfig.handlerTimeoutSeconds]
 * pass.
 *
 * Routes belong to the scope that made them ([scopeReleased]). They're kept
 * whether or not the owner turned web routes on: a test's requests
 * (`nf.test.request`) go through them all the same, and with web routes off a
 * package that declares them has the warning `runtime.web-disabled`.
 */
class WebRoutes(
    private val scripts: Scripts,
    private val config: WebConfig,
    private val gate: () -> WebGate,
    private val ticks: () -> Long,
    /** The address the world reaches the server's web routes at, without a slash at the end; null while they're off. */
    private val publicBase: () -> String?,
    private val snapshot: () -> ProjectSnapshot?
) : RuntimeService {
    override val name get() = "web routes"

    /** A route: a script's ([handler]) or the runtime's ([internal]). */
    class Route(
        val number: Long,
        val namespace: String,
        val method: String,
        val path: RoutePath,
        /** The scope that made it; null for none (it isn't released with any). */
        val scope: Scope?,
        val handler: LuaFunction?,
        val internal: ((Pending, Map<String, String>) -> Unit)?
    )

    /** A request routed and waiting for its answer. */
    class Pending(val number: Long, val call: WebCall, val route: Route, val deadline: Long)

    private val routes = LinkedHashMap<Long, Route>()
    private val pending = LinkedHashMap<Long, Pending>()
    private var nextRoute = 1L
    private var nextPending = 1L

    val enabled: Boolean get() = config.enabled

    /** The address of [namespace]'s [path] (and [query]), or null while web routes are off. */
    fun url(namespace: String, path: String, query: Map<String, String>): String? {
        val base = publicBase() ?: return null
        val tail = if (path == "/") "" else path
        return "$base/$namespace$tail" + if (query.isEmpty()) "" else "?" + RoutePath.encodeQuery(query)
    }

    /** Every route, by package and path: what `/nf requires` lists. */
    fun all(): List<Route> = routes.values.sortedWith(compareBy({ it.namespace }, { it.path.text }, { it.method }))

    fun find(number: Long): Route? = routes[number]

    /** Adds a route for [scope]'s script (or, with [internal], the runtime's). Throws [LuaApiException] for a path that isn't one or one that's taken. */
    fun add(
        namespace: String,
        method: String,
        path: String,
        scope: Scope?,
        handler: LuaFunction?,
        internal: ((Pending, Map<String, String>) -> Unit)? = null
    ): Route {
        val parsed = RoutePath.parse(path).getOrElse { throw LuaApiException("bad argument 'path' (${it.message}, got \"$path\")") }
        val taken = routes.values.firstOrNull { it.namespace == namespace && it.method == method && it.path.sameAs(parsed) }
        if (taken != null) {
            val whose = if (taken.internal != null) "a login flow (nf.web.oauth)" else "another route"
            throw LuaApiException("$whose of package \"$namespace\" already answers $method ${taken.path.text}")
        }
        return Route(nextRoute++, namespace, method, parsed, scope, handler, internal).also { routes[it.number] = it }
    }

    /** Removes [route]: requests waiting on it are answered `503`. False when it was already gone. */
    fun remove(route: Route): Boolean {
        if (routes.remove(route.number) == null) return false
        route.handler?.let { scripts.unref(it.ref) }
        for (waiting in pending.values.filter { it.route === route }) finish(waiting, unavailable())
        return true
    }

    fun pending(number: Long): Pending? = pending[number]

    /** Answers [request] with [answer], once. False when it was already answered. */
    fun finish(request: Pending, answer: WebAnswer): Boolean {
        pending.remove(request.number)
        return request.call.answer(answer)
    }

    /** A request a test makes (`nf.test.request`): admitted as the gate would, then routed now. */
    fun request(call: WebCall) {
        gate().refusal(call.ip, call.path, call.body.size.toLong())?.let {
            call.answer(it)
            return
        }
        route(call)
    }

    override fun tick(phase: TickPhase) {
        if (phase != TickPhase.ASYNC) return
        val queue = gate().queue
        repeat(PER_TICK) { route(queue.poll() ?: return@repeat) }
        if (pending.isEmpty()) return
        val now = ticks()
        for (waiting in pending.values.filter { now >= it.deadline }) {
            finish(waiting, WebAnswer.text(504, "The server took too long to answer."))
        }
    }

    private fun route(call: WebCall) {
        val segments = RoutePath.segments(call.path) ?: return call.answer(WebAnswer.text(400, "That address can't be read.")).let { }
        val namespace = segments.firstOrNull() ?: return notFound(call)
        val rest = segments.drop(1)
        val method = if (call.method == "HEAD") "GET" else call.method
        val matching = routes.values.filter { it.namespace == namespace }
            .mapNotNull { route -> route.path.matches(rest)?.let { route to it } }
        val chosen = matching.filter {
            it.first.method == method
        }.minWithOrNull { a, b -> compareRanks(a.first.path.rank, b.first.path.rank) }
        if (chosen == null) {
            if (matching.isEmpty()) return notFound(call)
            val allowed = matching.map { it.first.method }.distinct().sorted().let { if ("GET" in it) it + "HEAD" else it }
            call.answer(WebAnswer.text(405, "That address doesn't take $method.", mapOf("allow" to allowed.joinToString(", "))))
            return
        }
        val (route, params) = chosen
        val request = Pending(nextPending++, call, route, ticks() + config.handlerTimeoutSeconds.toLong() * TICKS_PER_SECOND)
        pending[request.number] = request
        route.internal?.let {
            it(request, params)
            return
        }
        val scope = route.scope ?: return finish(request, unavailable()).let { }
        val handler = route.handler ?: return
        val result = scripts.callKept<Any?>(
            scope,
            handler.ref,
            listOf(requestTable(call, params), LuaHandle.WebResponse(request.number)),
            "web"
        )
        when (result) {
            is CallResult.Failed -> {
                scripts.handlerFailed(
                    scope,
                    "${route.method} /${route.namespace}${route.path.text.takeIf {
                        it != "/"
                    }.orEmpty()}",
                    result.failure,
                    false
                )
                finish(request, WebAnswer.text(500, "The server failed answering that."))
            }
            is CallResult.Absent -> finish(request, unavailable())
            else -> Unit
        }
    }

    private fun notFound(call: WebCall) {
        call.answer(WebAnswer.text(404, "Nothing here."))
    }

    private fun unavailable() = WebAnswer.text(503, "That isn't available right now.")

    private fun compareRanks(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until minOf(a.size, b.size)) if (a[i] != b[i]) return a[i] - b[i]
        return b.size - a.size
    }

    /** What a handler's `request` is: a plain table, as `WebRequest` describes it. */
    private fun requestTable(call: WebCall, params: Map<String, String>): Map<String, Any?> {
        val body = String(call.body, StandardCharsets.UTF_8)
        val form = call.headers["content-type"]?.substringBefore(';')?.trim()?.lowercase()
            ?.takeIf { it == "application/x-www-form-urlencoded" }?.let { RoutePath.query(body) }
        return buildMap {
            put("method", call.method)
            put("path", call.path)
            put("params", params)
            put("query", RoutePath.query(call.rawQuery))
            put("headers", call.headers)
            put("body", body)
            if (form != null) put("form", form)
            put("ip", call.ip)
        }
    }

    /** A scope stopped: its routes go, and what waits on them is answered `503`. */
    override fun scopeReleased(scope: Scope) {
        for (route in routes.values.filter { it.scope?.id == scope.id }) remove(route)
    }

    override fun stop() {
        for (route in routes.values.toList()) remove(route)
        for (waiting in pending.values.toList()) finish(waiting, unavailable())
    }

    override fun costs(): Map<String, (Scope) -> Int> = mapOf("routes" to { scope -> routes.values.count { it.scope?.id == scope.id } })

    override fun liveness() = listOf(
        liveness<LuaHandle.Route> { routes.containsKey(it.number) },
        liveness<LuaHandle.WebResponse> { pending.containsKey(it.number) }
    )

    /** With web routes off, each package that declares them has a warning saying so. */
    override fun problems(): List<Problem> {
        if (config.enabled) return emptyList()
        val snapshot = snapshot() ?: return emptyList()
        return (listOf(snapshot.namespace) + snapshot.packages.keys.sorted())
            .filter { snapshot.manifestOf(it)?.requires?.web == true }
            .map { namespace ->
                val file = if (namespace ==
                    snapshot.namespace
                ) {
                    ProjectManifest.FILE_NAME
                } else {
                    PackagePaths.of(namespace, ProjectManifest.FILE_NAME)
                }
                ProblemCodes.RUNTIME_WEB_DISABLED.at(
                    file,
                    "Web routes are off on this server, so this package's routes aren't served: the server owner turns them on with web.enabled in config.yml",
                    "$.requires.web"
                )
            }
    }

    private companion object {
        /** Requests routed a tick, at most: the rest wait (up to the gate's limit) for the next. */
        const val PER_TICK = 32
        const val TICKS_PER_SECOND = 20L
    }
}
