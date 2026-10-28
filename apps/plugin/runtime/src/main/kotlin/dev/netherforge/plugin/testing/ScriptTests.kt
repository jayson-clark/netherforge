package dev.netherforge.plugin.testing

import dev.netherforge.format.script.ScriptDef
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
            is CallResult.Ok -> TestOutcome.Passed
            is CallResult.Failed -> TestOutcome.Failed(result.failure)
            CallResult.Absent -> TestOutcome.Failed(ScriptFailure("the test's file stopped running", case.scope.owner.file, null, null))
        }
    }

    override fun stop() {
        cases.clear()
        scope = null
    }

    companion object {
        /** What names a test file. */
        const val SUFFIX = "_test.lua"
    }
}
