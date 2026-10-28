package dev.netherforge.plugin.profile
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.script.PerformanceConfig
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the profiler costs: a tick of many cheap calls in (empty `tick`
 * handlers, each its own call in, which is the profiler's worst case: its
 * bookkeeping is per call, not per instruction), with it off and on, so the
 * difference per call in shows. Each run is a whole tick, the batch's drain
 * every 20 ticks included. Not a pass/fail test: run it by hand around a
 * change to the prelude's timing (`NETHERFORGE_BENCH=1 node tools/gradle.mjs
 * :plugin:runtime:test --tests '*ProfilerBenchmark*' -i`); the
 * plugin-sandbox skill has the numbers.
 */
@EnabledIfEnvironmentVariable(named = "NETHERFORGE_BENCH", matches = "1")
class ProfilerBenchmark {
    private val cases = linkedMapOf(
        // One handler function in 400 subscriptions: one row in the profile.
        "400 calls, one function" to "local f = function() end\nfor _ = 1, 400 do nf.on(\"tick\", f) end",
        // 400 functions: 400 rows, each looked up and drained.
        "400 calls, 400 functions" to "for _ = 1, 400 do nf.on(\"tick\", function() end) end",
        // Real work in each call: the bookkeeping disappears in it.
        "100 calls of a 1000-step loop" to
            "for _ = 1, 100 do nf.on(\"tick\", function() local n = 0 for i = 1, 1000 do n = n + i end end) end"
    )

    @Test
    fun `the profiler's overhead`() {
        for ((name, script) in cases) {
            val (off, on) = best(script)
            val calls = if (name.startsWith("100 ")) 100 else 400
            println(
                "bench ${name.padEnd(32)} off ${off / 1000} µs a tick, on ${on / 1000} µs a tick: " +
                    "${(on - off) / calls} ns a call in"
            )
        }
    }

    /**
     * The best mean tick time over a batch (20 ticks, the drain included) with
     * the profiler off and on, alternating batch by batch on one server after
     * 1,000 ticks to warm up, best of 30 each.
     */
    private fun best(script: String): Pair<Long, Long> {
        TestServer(mapOf("modules/bench/init.lua" to script), performance = PerformanceConfig(warnMillis = 0.0)).use { server ->
            val profiler = server.runtime.profiler
            repeat(50) {
                profiler.streaming = it % 2 == 0
                server.tick(20)
            }
            val best = longArrayOf(Long.MAX_VALUE, Long.MAX_VALUE)
            repeat(60) { round ->
                val on = round % 2
                profiler.streaming = on == 1
                // The first tick after a switch turns the Lua state's bookkeeping on or off; the batch after it is measured.
                server.tick(20)
                val start = System.nanoTime()
                server.tick(20)
                best[on] = minOf(best[on], (System.nanoTime() - start) / 20)
            }
            assertEquals(emptyList(), server.errors.map { it.message })
            return best[0] to best[1]
        }
    }
}
