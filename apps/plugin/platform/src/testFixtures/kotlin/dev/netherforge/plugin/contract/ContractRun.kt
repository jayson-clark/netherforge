package dev.netherforge.plugin.contract

import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.support.descriptor.ClassSource
import org.junit.platform.engine.support.descriptor.MethodSource
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestIdentifier
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Collections

/** One test's outcome in a run of the suites: a [ContractCase] and how it ended. */
data class ContractResult(val suite: String, val name: String, val status: String, val message: String? = null, val trace: String? = null) {
    /** Whether the report counts it as passed; [ONLY_ON_OTHER] is skipped, as it says it should be. */
    val passed: Boolean get() = status == SUCCESSFUL

    companion object {
        const val SUCCESSFUL = "SUCCESSFUL"

        /** A test that says it runs only on the other server ([OnlyOn]), skipped as it should be. */
        const val ONLY_ON_OTHER = "ONLY_ON_OTHER"

        /** Skipped with no [OnlyOn] to say why: a failure. */
        const val SKIPPED = "SKIPPED"

        /** A suite's test this server must run that never ran: a failure. */
        const val MISSING = "MISSING"
    }
}

/**
 * Collects a JUnit run of the contract suites against [target] into
 * [ContractResult]s, and finds what didn't run ([finish]): what the contract
 * plugin writes for `ContractScenario` to report. Each suite's test is named
 * as [ContractSuites] names it (`WorldOpsContract` and the method's name),
 * whichever subclass ran it; a test that isn't a suite's keeps its class's
 * name. Besides passes and failures (`FAILED`, `ABORTED`), a skip is a
 * failure ([ContractResult.SKIPPED]) unless the test is [OnlyOn] the other
 * server, and [finish] adds every expected test that never ran as
 * [ContractResult.MISSING].
 */
class ContractRun(private val target: ContractTarget, private val warn: (String, Throwable?) -> Unit = { _, _ -> }) :
    TestExecutionListener {
    private val results: MutableList<ContractResult> = Collections.synchronizedList(mutableListOf())

    val all: List<ContractResult> get() = synchronized(results) { results.toList() }

    override fun executionFinished(test: TestIdentifier, result: TestExecutionResult) {
        if (!test.isTest && result.status == TestExecutionResult.Status.SUCCESSFUL) return
        val failure = result.throwable.orElse(null)
        val (suite, name) = names(test)
        if (failure != null) warn("Contract $suite > $name: ${result.status}", failure)
        add(suite, name, result.status.name, failure)
    }

    override fun executionSkipped(test: TestIdentifier, reason: String) {
        val (suite, name) = names(test)
        val declared = reason.startsWith(ContractSuites.ONLY_ON) && !reason.startsWith(ContractSuites.ONLY_ON + target)
        if (!declared) warn("Contract $suite > $name was skipped: $reason", null)
        results += ContractResult(suite, name, if (declared) ContractResult.ONLY_ON_OTHER else ContractResult.SKIPPED, reason)
    }

    /** Records that the run itself couldn't go on, as [suite] > [name]. */
    fun failed(suite: String, name: String, cause: Throwable) = add(suite, name, TestExecutionResult.Status.FAILED.name, cause)

    /** Adds every test of [expected] (by default every suite's on [target]) that has no result as [ContractResult.MISSING]. */
    fun finish(expected: Set<ContractCase> = ContractSuites.expected(target)) {
        val ran = all.map { ContractCase(it.suite, it.name) }.toSet()
        for (case in expected - ran) {
            warn("Contract $case runs on the other server but didn't run here", null)
            results +=
                ContractResult(
                    case.suite,
                    case.test,
                    ContractResult.MISSING,
                    "it didn't run on $target: is its suite's subclass in the run?"
                )
        }
    }

    private fun add(suite: String, name: String, status: String, failure: Throwable?) {
        results += ContractResult(
            suite,
            name,
            status,
            failure?.toString(),
            failure?.let { StringWriter().also { out -> it.printStackTrace(PrintWriter(out)) }.toString() }
        )
    }

    private fun names(test: TestIdentifier): Pair<String, String> {
        val source = test.source.orElse(null)
        val type = when (source) {
            is MethodSource -> source.getJavaClass()
            is ClassSource -> source.getJavaClass()
            else -> null
        }
        val suite = type?.let { ContractSuites.suiteOf(it) ?: it }?.simpleName ?: test.displayName
        return suite to ((source as? MethodSource)?.methodName ?: test.displayName)
    }
}
