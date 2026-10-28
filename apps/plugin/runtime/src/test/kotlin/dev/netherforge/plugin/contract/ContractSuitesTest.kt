package dev.netherforge.plugin.contract

import org.junit.platform.commons.support.ReflectionSupport
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The fake's half of the contract suites' completeness: every suite
 * [ContractSuites] finds runs here, once. The contract plugin holds Paper to
 * the same list ([ContractSuites.expected]).
 */
class ContractSuitesTest {
    private val fakes = ReflectionSupport.findAllClassesInPackage(
        javaClass.packageName,
        { PlatformContract::class.java.isAssignableFrom(it) && !Modifier.isAbstract(it.modifiers) },
        { true }
    )

    @Test
    fun `every suite runs on the fake, once`() {
        val suites = ContractSuites.suites()
        assertTrue(suites.size > 30, "found only ${suites.map { it.simpleName }}")
        val run = fakes.groupBy { ContractSuites.suiteOf(it) }
        assertEquals(
            emptyList(),
            suites.filter {
                run[it].orEmpty().size != 1
            }.map { "${it.simpleName}: ${run[it]?.map { c -> c.simpleName }}" }
        )
        assertEquals(
            emptyList(),
            fakes.filter {
                ContractSuites.suiteOf(it) == null
            }.map { it.simpleName },
            "a fake test that's no suite's"
        )
    }

    @Test
    fun `a suite's tests are its own test methods, and a test only on one server is left out of the other's`() {
        val fake = ContractSuites.expected(ContractTarget.FAKE)
        val paper = ContractSuites.expected(ContractTarget.PAPER)
        assertTrue(ContractCase("WorldOpsContract", "worlds are known by name, the default first") in fake, "$fake")
        val onlyOn = ContractSuites.suites().flatMap { suite ->
            suite.declaredMethods.mapNotNull { m ->
                m.getAnnotation(OnlyOn::class.java)?.let {
                    ContractCase(suite.simpleName, m.name) to
                        it.target
                }
            }
        }
        for ((case, target) in onlyOn) {
            assertEquals(target == ContractTarget.FAKE, case in fake, "$case")
            assertEquals(target == ContractTarget.PAPER, case in paper, "$case")
        }
    }
}
