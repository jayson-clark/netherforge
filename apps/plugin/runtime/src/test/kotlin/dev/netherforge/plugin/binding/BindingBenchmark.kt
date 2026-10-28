package dev.netherforge.plugin.binding
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.script.PerformanceConfig
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How long a call through the generated bindings takes, for the hot ones a
 * script makes every tick: a node's transform (the `Vec3` fast path both
 * ways), a handle back, a handle and a union as arguments, an option table.
 * Not a pass/fail test: run it by hand around a change to the bindings and
 * compare (`NETHERFORGE_BENCH=1 node tools/gradle.mjs :plugin:runtime:test
 * --tests '*BindingBenchmark*' -i`).
 */
@EnabledIfEnvironmentVariable(named = "NETHERFORGE_BENCH", matches = "1")
class BindingBenchmark {
    private val calls = 20_000

    private val cases = linkedMapOf(
        "node:set_translation(v)" to "node:set_translation(v)",
        "node:translation()" to "node:translation()",
        "this:node(name)" to "this:node(\"root\")",
        "this:is_hidden_from(player)" to "this:is_hidden_from(player)",
        "border:contains(v)" to "border:contains(v)",
        "nf.centities.all(options)" to "nf.centities.all({ radius = 0.5, near = far })"
    )

    @Test
    fun `calls through the bindings`() {
        val script = buildString {
            appendLine("local node = this:node(\"root\")")
            appendLine("local v = vec3(1, 2, 3)")
            appendLine("local far = vec3(1000, 64, 1000)")
            appendLine("local player = nf.players.get(\"Alex\")")
            appendLine("local border = nf.worlds.default():border()")
            appendLine("local cases = {")
            for ((name, call) in cases) appendLine("  [\"$name\"] = function() for _ = 1, $calls do $call end end,")
            appendLine("}")
            appendLine("local order = { ${cases.keys.joinToString(", ") { "\"$it\"" }} }")
            appendLine("local tick = 0")
            appendLine("this:on(\"tick\", function() cases[order[tick % #order + 1]](); tick = tick + 1 end)")
        }
        TestServer(
            mapOf(
                "centities/c/centity.json" to
                    TestServer.scriptedCentity().replace("\"script.lua\"", "\"script.lua\", \"budget\": 1000000000"),
                "centities/c/script.lua" to script
            ),
            start = false,
            // Every case is slow on purpose.
            performance = PerformanceConfig(warnMillis = 0.0)
        ).use { server ->
            server.player("Alex")
            server.start()
            server.runtime.session.centities.spawn("c", Location("world", 0.0, 64.0, 0.0))
            assertEquals(emptyList(), server.errors.map { it.message })
            // Each tick runs the next case, so tick k is case k mod n; the best of ten runs after a warm-up.
            val names = cases.keys.toList()
            val best = LongArray(names.size) { Long.MAX_VALUE }
            for (round in 0 until 15) {
                for (k in names.indices) {
                    val start = System.nanoTime()
                    server.tick()
                    val took = System.nanoTime() - start
                    if (round >= 5) best[k] = minOf(best[k], took)
                }
            }
            val results = names.mapIndexed { k, name -> name to best[k] / calls }
            assertEquals(emptyList(), server.errors.map { it.message })
            for ((name, nanos) in results) println("bench ${name.padEnd(32)} $nanos ns/call")
        }
    }
}
