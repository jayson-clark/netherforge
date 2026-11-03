package dev.netherforge.plugin.testing

import dev.netherforge.format.script.ScriptDef
import dev.netherforge.plugin.async.WorkFailed
import dev.netherforge.plugin.http.HttpReply
import dev.netherforge.plugin.lua.CallResult
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.lua.LuaFunction
import dev.netherforge.plugin.lua.ScriptFailure
import dev.netherforge.plugin.script.Scope
import dev.netherforge.plugin.script.ScopeOwner
import dev.netherforge.plugin.script.Scripts
import dev.netherforge.plugin.session.RuntimeService

/**
 * The project's script tests (`*_test.lua` files), which `nf.test.case`
 * registers in. A test file runs in a scope of its own, like a module's
 * (`require` reaches the project's modules, and the files beside it); its
 * body declares the tests, and [run] calls one. The runtime holds one
 * file's tests at a time, since the runner starts a fresh runtime for every
 * test (so nothing one test did can reach the next).
 *
 * The runner is the testkit's (`ScriptTestRunner`); this is the runtime's
 * half, which only a session built with a [TestHarness] has any use for.
 */
class ScriptTests internal constructor(
    private val scripts: Scripts,
    private val projectFiles: () -> Set<String>,
    private val read: (String) -> String?,
    /** The harness the runtime runs under, or null on a server. */
    val harness: TestHarness?
) : RuntimeService {
    override val name get() = "tests"

    private class Case(val scope: Scope, val callback: LuaFunction)

    private val cases = LinkedHashMap<String, Case>()
    private var scope: Scope? = null

    /** Whether a test file's own code is running: the only time tests are declared. */
    private var declaring = false

    /** What answers the test's `nf.http.request` calls (`nf.test.http`), in the scope that set it. */
    private var httpHandler: Pair<Scope, LuaFunction>? = null

    /** `nf.test.allow_private`: the owner's `http.allow-private` entries this test stands in for, until it ends. */
    internal val privateAllowances = ArrayList<dev.netherforge.plugin.http.PrivateAllowance>()

    /** Why each request the test left unanswered failed it, in order; [reported] of them already raised. */
    private val unanswered = ArrayList<String>()
    private var reported = 0

    /** `nf.test.http`: [handler] answers the test's requests from now on, in place of any before it. */
    internal fun answerHttp(scope: Scope, handler: LuaFunction) {
        httpHandler?.let { scripts.unref(it.second.ref) }
        httpHandler = scope to handler
    }

    /**
     * A request a script made in a test, answered on the main thread by the test's handler: the reply, or a
     * [WorkFailed] when it has none (which fails the test too, naming [url]).
     */
    internal fun answer(method: String, url: java.net.URI, headers: Map<String, String>, body: ByteArray?): HttpReply {
        val (scope, handler) = httpHandler ?: throw unanswered(url, "no nf.test.http handler answers requests in this test")
        val request = buildMap<String, Any?> {
            put("method", method)
            put("url", url.toString())
            put("headers", headers)
            body?.let { put("body", it.toString(Charsets.UTF_8)) }
        }
        val answer = when (val result = scripts.callKept(scope, handler.ref, listOf(request), "http", ANSWER, "the handler's answer")) {
            is CallResult.Ok -> result.value as kotlinx.serialization.json.JsonElement?
            is CallResult.Failed -> throw unanswered(url, "nf.test.http's handler failed: ${result.failure.message}")
            CallResult.Absent -> null
        }
        if (answer == null || answer is kotlinx.serialization.json.JsonNull) throw unanswered(url, "nf.test.http's handler answered nil")
        return reply(answer, url) ?: throw unanswered(url, "nf.test.http's handler answered something that isn't { status, headers, body }")
    }

    /** [answer] as the reply the script gets; null when it isn't one. */
    private fun reply(answer: kotlinx.serialization.json.JsonElement, url: java.net.URI): HttpReply? {
        val fields = answer as? kotlinx.serialization.json.JsonObject ?: return null
        if (fields.keys.any { it !in ANSWER_KEYS }) return null
        fun text(value: kotlinx.serialization.json.JsonElement?) =
            (value as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
        val status = when (val given = fields["status"]) {
            null -> 200
            else -> (given as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()
                ?.takeIf { it in 100..599 } ?: return null
        }
        val headers = when (val given = fields["headers"]) {
            null -> emptyMap()
            is kotlinx.serialization.json.JsonObject -> given.mapKeys { it.key.lowercase() }.mapValues { text(it.value) ?: return null }
            else -> if (given is kotlinx.serialization.json.JsonArray && given.isEmpty()) emptyMap() else return null
        }
        val body = fields["body"]?.let { text(it) ?: return null }.orEmpty()
        return HttpReply(status, headers, body, url, body.toByteArray(Charsets.UTF_8))
    }

    private fun unanswered(url: java.net.URI, why: String): WorkFailed {
        val message = "the request to $url wasn't answered: $why"
        unanswered += message
        return WorkFailed(message)
    }

    /**
     * The first request left unanswered since this was last asked, as an error at the test's line (`nf.test.advance`
     * raises it): a test can't pass with one, even when the script took its `nil, err`.
     */
    internal fun raiseUnanswered() {
        if (reported < unanswered.size) {
            val message = unanswered[reported]
            reported = unanswered.size
            throw LuaApiException(message)
        }
    }

    /** The project's own test files, by project path: `*_test.lua` anywhere (a package's tests are its own to run). */
    fun files(): List<String> = projectFiles().filter { it.endsWith(SUFFIX) && ':' !in it }.sorted()

    /** `nf.test.case`: [scope]'s test [name]. */
    internal fun register(scope: Scope, name: String, callback: LuaFunction) {
        if (scope !== this.scope ||
            !declaring
        ) {
            throw LuaApiException("nf.test.case is for a test file's own code, which is where its tests are declared")
        }
        if (name.isBlank()) throw LuaApiException("a test needs a name")
        if (name in cases) throw LuaApiException("there is already a test named \"$name\" in ${scope.owner.file}")
        cases[name] = Case(scope, callback)
    }

    /**
     * Runs the test file [file] (a project path) in a scope of its own, which declares its tests.
     * A file that fails halfway leaves the tests it declared before that.
     */
    fun load(file: String): LoadedTests {
        check(harness != null) { "tests run only under a test harness" }
        cases.clear()
        val source = read(file) ?: return LoadedTests(file, emptyList(), ScriptFailure("can't read $file", file, null, null))
        val scope = scripts.open(ScopeOwner.TestScript(file), ScriptDef.DEFAULT_BUDGET)
        this.scope = scope
        val host = requireNotNull(scripts.host) { "no Lua state" }
        declaring = true
        val failure = try {
            (host.runFile(scope.budget, scope.id, file, source) as? CallResult.Failed)?.failure
        } finally {
            declaring = false
        }
        return LoadedTests(file, cases.keys.toList(), failure)
    }

    /** Calls test [name], which [load] declared, as its file's code: how it ended. */
    fun run(name: String): TestOutcome {
        val case = requireNotNull(cases[name]) { "no test named \"$name\"" }
        return when (val result = scripts.callKept(case.scope, case.callback.ref)) {
            is CallResult.Ok -> if (reported < unanswered.size) {
                TestOutcome.Failed(ScriptFailure(unanswered[reported], case.scope.owner.file, null, null))
            } else {
                TestOutcome.Passed
            }
            is CallResult.Failed -> TestOutcome.Failed(result.failure)
            CallResult.Absent -> TestOutcome.Failed(ScriptFailure("the test's file stopped running", case.scope.owner.file, null, null))
        }
    }

    override fun stop() {
        cases.clear()
        scope = null
        httpHandler?.let { scripts.unref(it.second.ref) }
        httpHandler = null
        privateAllowances.clear()
    }

    companion object {
        /** What a handler's answer may hold. */
        private val ANSWER_KEYS = setOf("status", "headers", "body")

        /** A handler's answer, as JSON (the one Lua↔JSON codec), or null for nil. */
        private val ANSWER = object : dev.netherforge.plugin.lua.LuaCodec<kotlinx.serialization.json.JsonElement?> {
            override val expected = "TestHttpAnswer?"

            override fun accepts(call: dev.netherforge.plugin.lua.LuaCall, index: Int) = true

            override fun read(call: dev.netherforge.plugin.lua.LuaCall, index: Int, where: String) =
                if (call.lua.isNil(index)) null else dev.netherforge.plugin.lua.LuaValue(call.host, call.lua, index).json(where)

            override fun push(call: dev.netherforge.plugin.lua.LuaCall, value: kotlinx.serialization.json.JsonElement?) =
                error("an answer is only read")
        }

        /** What names a test file. */
        const val SUFFIX = "_test.lua"
    }
}
