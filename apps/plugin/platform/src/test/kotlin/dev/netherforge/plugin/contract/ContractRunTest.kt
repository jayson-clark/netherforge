package dev.netherforge.plugin.contract

import org.junit.jupiter.api.Disabled
import org.junit.platform.engine.discovery.DiscoverySelectors
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import kotlin.test.Test
import kotlin.test.assertEquals

/** How a run of the suites is reported ([ContractRun]): what the contract plugin writes for `ContractScenario`. */
class ContractRunTest {
    private fun run(target: ContractTarget, vararg classes: Class<*>, expected: Set<ContractCase>): List<ContractResult> {
        val run = ContractRun(target)
        val request = LauncherDiscoveryRequestBuilder.request()
            .selectors(classes.map { DiscoverySelectors.selectClass(it) })
            .configurationParameter(ContractTarget.PARAMETER, target.name)
            .build()
        LauncherFactory.create().execute(request, run)
        run.finish(expected)
        return run.all.sortedBy { "${it.suite} ${it.name}" }
    }

    @Test
    fun `a suite's tests are named by the suite, and an expected test that didn't run is missing`() {
        val results = run(
            ContractTarget.FAKE,
            FakePauseOpsTest::class.java,
            expected = ContractSuites.expected(ContractTarget.FAKE).filter { it.suite == "PauseOpsContract" }.toSet() +
                ContractCase("PauseOpsContract", "a test nobody wrote")
        )
        val pause = PauseOpsContract::class.java.declaredMethods.filter { it.isAnnotationPresent(org.junit.jupiter.api.Test::class.java) }
        assertEquals(
            (pause.map { "PauseOpsContract > ${it.name}: SUCCESSFUL" } + "PauseOpsContract > a test nobody wrote: MISSING").sorted(),
            results.map { "${it.suite} > ${it.name}: ${it.status}" }.sorted()
        )
    }

    @Test
    fun `a skip is a failure unless the test says it runs only on the other server`() {
        val results = run(ContractTarget.FAKE, Examples::class.java, expected = emptySet())
        assertEquals(
            listOf("Examples > onlyOnPaper: ONLY_ON_OTHER", "Examples > passes: SUCCESSFUL", "Examples > skipped: SKIPPED"),
            results.map { "${it.suite} > ${it.name}: ${it.status}" }
        )
        assertEquals("only on PAPER: the fake has no such thing", results.first().message)
        // On its own server it runs.
        assertEquals(
            "SUCCESSFUL",
            run(ContractTarget.PAPER, Examples::class.java, expected = emptySet()).single {
                it.name == "onlyOnPaper"
            }.status
        )
    }

    /** Not a suite (no [PlatformContract]): what the report makes of each way a test ends. */
    class Examples {
        @Test
        fun passes() = Unit

        @Disabled("for no stated reason")
        @Test
        fun skipped() = Unit

        @OnlyOn(ContractTarget.PAPER, "the fake has no such thing")
        @Test
        fun onlyOnPaper() = Unit
    }
}
