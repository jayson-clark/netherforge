package dev.netherforge.plugin.contract

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ConditionEvaluationResult
import org.junit.jupiter.api.extension.ExecutionCondition
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.platform.commons.support.ReflectionSupport
import java.lang.reflect.Modifier

/** The servers the contract suites run against. */
enum class ContractTarget {
    /** The testkit's `FakePlatform`, in the runtime's tests. */
    FAKE,

    /** `PaperPlatform` inside a real server, in the integration test's `ContractScenario`. */
    PAPER;

    companion object {
        /** The JUnit configuration parameter saying which this run is: `paper` on the contract server; the fake when unset. */
        const val PARAMETER = "netherforge.contract.target"

        fun of(context: ExtensionContext): ContractTarget =
            context.getConfigurationParameter(PARAMETER).map { valueOf(it.uppercase()) }.orElse(FAKE)
    }
}

/**
 * A contract test that only one server can run, and why: anything else
 * that runs on one and not the other fails the Paper run ([ContractSuites]).
 * On the other server it's disabled, and reported as such.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ExtendWith(OnlyOnCondition::class)
annotation class OnlyOn(val target: ContractTarget, val reason: String)

/** Runs an [OnlyOn] test only on its own server. */
class OnlyOnCondition : ExecutionCondition {
    override fun evaluateExecutionCondition(context: ExtensionContext): ConditionEvaluationResult {
        val only = context.element.orElse(null)?.getAnnotation(OnlyOn::class.java)
            ?: return ConditionEvaluationResult.enabled("runs on every server")
        val here = ContractTarget.of(context)
        return if (only.target == here) {
            ConditionEvaluationResult.enabled("runs on ${only.target}")
        } else {
            ConditionEvaluationResult.disabled("${ContractSuites.ONLY_ON}${only.target}: ${only.reason}")
        }
    }
}

/** One test of one suite: the suite's class name (`WorldOpsContract`) and the test method's. */
data class ContractCase(val suite: String, val test: String) {
    override fun toString() = "$suite > $test"
}

/**
 * Every contract suite, found from the suites themselves rather than kept in
 * a list: the abstract [PlatformContract]s in this package, and their
 * `@Test` methods. The runtime's tests hold the fake to running every one
 * (`ContractSuitesTest`), and the contract plugin holds Paper to it: a suite
 * or a test that runs on the fake and didn't run on Paper fails the run.
 */
object ContractSuites {
    /** How a skip of an [OnlyOn] test starts its reason, so a report can tell it from any other skip. */
    const val ONLY_ON = "only on "

    /** Every suite, by name. */
    fun suites(): List<Class<out PlatformContract>> = ReflectionSupport.findAllClassesInPackage(
        PlatformContract::class.java.packageName,
        { PlatformContract::class.java.isAssignableFrom(it) && it != PlatformContract::class.java && Modifier.isAbstract(it.modifiers) },
        { true }
    ).map {
        @Suppress("UNCHECKED_CAST")
        (it as Class<out PlatformContract>)
    }.sortedBy { it.simpleName }

    /** The suite [test] (a concrete subclass, or a suite itself) belongs to, or null if it's no suite's. */
    fun suiteOf(test: Class<*>): Class<*>? = generateSequence(test) { it.superclass }.firstOrNull {
        it.superclass == PlatformContract::class.java &&
            Modifier.isAbstract(it.modifiers)
    }

    /** Every test of every suite that [target] must run: all but those [OnlyOn] the other server. */
    fun expected(target: ContractTarget): Set<ContractCase> = suites().flatMapTo(sortedSetOf(compareBy { it.toString() })) { suite ->
        suite.declaredMethods
            .filter { it.isAnnotationPresent(Test::class.java) }
            .filter { it.getAnnotation(OnlyOn::class.java)?.target?.let { only -> only == target } ?: true }
            .map { ContractCase(suite.simpleName, it.name) }
    }
}
