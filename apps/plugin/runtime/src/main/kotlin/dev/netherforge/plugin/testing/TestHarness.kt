package dev.netherforge.plugin.testing

import dev.netherforge.plugin.lua.ScriptFailure
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.PlayerRef
import java.util.UUID

/**
 * What a test run gives the runtime: the fake server's clock and who is on
 * it. Set as [dev.netherforge.plugin.RuntimeConfig.testing] by the script test
 * runner (the `:plugin:test-runner` module, on the testkit's fake platform);
 * a server never has one, which is why `nf.test` doesn't exist there.
 */
interface TestHarness {
    /** Runs [ticks] whole server ticks, scripts' background work finished before each. */
    fun advance(ticks: Int)

    /** Joins a fake player named [name], the join events raised, and returns them: the one already online by that name, if there is one. */
    fun join(name: String): PlayerRef

    /**
     * The stack [player]'s game was last sent for [slot] of their inventory (of the window they have open, with
     * [menu]; their cursor when [slot] is null), as they're shown it; null for none.
     */
    fun shown(player: UUID, slot: Int?, menu: Boolean): ItemData?
}

/** How one test ended. */
sealed interface TestOutcome {
    data object Passed : TestOutcome

    /** It raised an error: a failed `assert`, an `error` call, a mistake calling the API. */
    data class Failed(val failure: ScriptFailure) : TestOutcome
}

/** A test file run, and the tests it declared. */
class LoadedTests(
    val file: String,
    /** The tests' names, in the order the file declared them. */
    val names: List<String>,
    /** Why the file's own code failed (a syntax error, an error before it finished declaring tests), when it did. */
    val failure: ScriptFailure?
)
