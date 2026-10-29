package dev.netherforge.plugin.binding
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.lua.SandboxLimits
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.test.fail

/**
 * What API calls cost a script, in instructions of its budget, held to a
 * golden in `apps/plugin/runtime/testdata/` (`function-costs.tsv`: every
 * function, from `ConformanceTest`; `scenario-costs.tsv`: what scripts do
 * all the time, from `ApiCostTest`).
 *
 * A cost may move a little either way ([allowance]: Lua's string hashes are
 * seeded per state, so a walk that stops early can take a few more or fewer
 * steps); further than that, either way, fails. Dearer is a regression to
 * look at; cheaper means the golden is out of date, and a golden left behind
 * would let the next regression back to the old cost through. So does a cost
 * with no line in the golden and a line with no cost. `UPDATE_GOLDEN=1`
 * rewrites it; review the diff like any golden's.
 */
object CostGolden {
    /**
     * Counts every instruction, so `nf.instructions_left()` is exact. The per-tick time budget is off
     * (`maxTickMillis = 0`): it defers a scope's handlers by wall-clock time, which would make these
     * instruction-exact costs depend on how fast the machine is (its own test, `SandboxLimitsTest`, covers it).
     */
    val EXACT = SandboxLimits(
        hookEvery = 1,
        deadlineEvery = 1_000_000,
        memoryEvery = 1_000_000,
        deadlineMillis = 100_000,
        maxTickMillis = 0.0
    )

    /** How far a cost may move from its golden before it fails. */
    fun allowance(golden: Int) = maxOf(3, golden / 10)

    fun check(file: String, header: String, costs: Map<String, Int>) {
        val path = TestServer.repo().resolve("apps/plugin/runtime/testdata").resolve(file)
        if (System.getenv("UPDATE_GOLDEN") == "1") {
            val lines = costs.entries.sortedBy { it.key }.joinToString("") { (name, cost) -> "$name\t$cost\n" }
            path.writeText(header.lines().joinToString("") { "# $it\n" } + lines)
            return
        }
        if (!path.exists()) fail("$path is missing: run with UPDATE_GOLDEN=1 to write it")
        val golden = path.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.associate { line ->
            line.substringBefore('\t') to line.substringAfter('\t').toInt()
        }
        val wrong = buildList {
            for ((name, cost) in costs) {
                val expected = golden[name]
                when {
                    expected == null -> add("$name costs $cost: not in the golden")
                    cost > expected + allowance(expected) -> add("$name costs $cost, up from $expected")
                    cost < expected - allowance(expected) -> add("$name costs $cost, down from $expected")
                }
            }
            for (name in golden.keys - costs.keys) add("$name is in the golden but wasn't measured")
        }
        if (wrong.isNotEmpty()) {
            val list = wrong.joinToString("\n")
            fail("${wrong.size} costs differ from $file (UPDATE_GOLDEN=1 rewrites it, once each is understood):\n$list")
        }
    }
}
