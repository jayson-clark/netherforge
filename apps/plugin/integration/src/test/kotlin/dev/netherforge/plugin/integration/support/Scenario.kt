package dev.netherforge.plugin.integration.support

import dev.netherforge.format.bridge.Problems
import dev.netherforge.format.bridge.ScriptError
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.extension.AfterTestExecutionCallback
import org.junit.jupiter.api.extension.ConditionEvaluationResult
import org.junit.jupiter.api.extension.ExecutionCondition
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.TestWatcher
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * One integration scenario: a class whose tests are its steps, in `@Order`,
 * against one server running a copy of `examples/showcase` with [fixtures]
 * added. The server starts before the first step and stops (cleanly, or the
 * scenario fails) after the last. A step that fails skips the steps after it,
 * which build on it; other scenarios run on regardless.
 *
 * No script may fail unless a step said it would: after every step that
 * passed, everything the server reported until then is waited for
 * ([Editor.settle]) and a script error no step waited for, and none
 * [expectScriptErrors] allowed, fails the step.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@ExtendWith(Scenario.StopAfterFailure::class, Scenario.NoScriptErrors::class)
abstract class Scenario(private vararg val fixtures: String) {
    lateinit var project: TestProject
        private set

    open val server = PaperServer()

    /** The current run of [server]. */
    lateinit var running: PaperServer.Running
        private set

    val editor: Editor get() = running.editor

    /** The server's output, in its folder: a file per scenario, a restart's appended. */
    private val log: String get() = "${javaClass.simpleName}.log"

    /** That output: what the console printed, for what only the console says. */
    val serverLog: java.nio.file.Path get() = server.folder.resolve(log)

    /** Script errors steps said they'd cause, by why. */
    private val expected = mutableListOf<Pair<String, (ScriptError) -> Boolean>>()

    /** Script errors a run that has stopped left, checked with the current run's after the step. */
    private val left = mutableListOf<ScriptError>()

    /** Makes the project this scenario runs: a copy of `examples/showcase` with its fixtures, by default. */
    open fun makeProject(): TestProject = TestProject(*fixtures)

    /** Where the scenario runs at all: JUnit assumptions, checked before anything starts (a failed one skips it). */
    open fun assumptions() {}

    /** Changes the project before the server first starts. */
    open fun prepare(project: TestProject) {}

    @BeforeAll
    fun startServer() {
        assumptions()
        project = makeProject()
        prepare(project)
        server.prepare()
        java.nio.file.Files.deleteIfExists(server.folder.resolve(log))
        running = server.start(project.root, log)
        // The project loads clean: every resource of the example and the fixtures alike.
        assertEquals(emptyList(), (editor.next(what = "the first problems") { it is Problems } as Problems).problems)
    }

    /** Stops the server, runs [stopped], and starts it again on the same world: what survives a restart. */
    fun restart(stopped: () -> Unit = {}) {
        val before = editor
        running.stop()
        // Stopping waited for the bridge to close: everything that run said has arrived.
        left += before.takeScriptErrors(settled = true)
        stopped()
        running = server.start(project.root, log)
    }

    /**
     * Allows, for the rest of the scenario, the script errors [matching] accepts, which a step causes on purpose
     * ([why]). Match by source file or message, never every error: a wait that takes one ([Editor.next]) needs
     * nothing here, but a deliberate failure may be reported again later, or more than once.
     */
    fun expectScriptErrors(why: String, matching: (ScriptError) -> Boolean) {
        expected += why to matching
    }

    /** The script errors since the last check that no step waited for and none expected. */
    private fun unexpectedScriptErrors(): List<ScriptError> {
        val errors = left + if (::running.isInitialized) editor.takeScriptErrors() else emptyList()
        left.clear()
        return errors.filterNot { error -> expected.any { (_, matching) -> matching(error) } }
    }

    @AfterAll
    fun stopServer() {
        try {
            if (::running.isInitialized) running.stop()
        } finally {
            if (::project.isInitialized) project.close()
        }
    }

    /** Fails a step that passed when a script failed meanwhile and the step didn't say it would. */
    class NoScriptErrors : AfterTestExecutionCallback {
        override fun afterTestExecution(context: ExtensionContext) {
            if (context.executionException.isPresent) return
            val scenario = context.requiredTestInstance as Scenario
            val errors = scenario.unexpectedScriptErrors()
            if (errors.isNotEmpty()) {
                fail("a script failed and no step expected it:\n" + errors.joinToString("\n") { "  " + Editor.describe(it) })
            }
        }
    }

    /** Skips a scenario's steps after one fails: they'd only fail for what it left undone. */
    class StopAfterFailure :
        TestWatcher,
        ExecutionCondition {
        private fun store(context: ExtensionContext) =
            context.root.getStore(ExtensionContext.Namespace.create(StopAfterFailure::class.java, context.requiredTestClass))

        override fun testFailed(context: ExtensionContext, cause: Throwable?) {
            store(context).put(FAILED, context.displayName)
        }

        override fun evaluateExecutionCondition(context: ExtensionContext): ConditionEvaluationResult {
            if (context.testMethod.isEmpty) return ConditionEvaluationResult.enabled("a scenario")
            val failed = store(context).get(FAILED, String::class.java)
            return if (failed == null) {
                ConditionEvaluationResult.enabled("no step failed")
            } else {
                ConditionEvaluationResult.disabled("an earlier step failed: $failed")
            }
        }

        private companion object {
            const val FAILED = "failed"
        }
    }
}
