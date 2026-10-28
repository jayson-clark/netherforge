package dev.netherforge.plugin.lua
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.script.PerformanceConfig
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import party.iroiro.luajava.JFunction
import party.iroiro.luajava.lua54.Lua54
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the sandbox costs a script, under a few choices of its intervals: a
 * loop of plain instructions (the count hook and its checks), a loop that
 * allocates (the memory checks), and the string and table functions the
 * caps wrap. And what the library's C functions cost on a bare Lua state, per
 * unit of the caps' cost model (`caps.lua`'s STEPS_PER_* constants and its
 * pattern steps). Not a pass/fail test: run it by hand around a change to the
 * hook or the caps and compare (`NETHERFORGE_BENCH=1 node tools/gradle.mjs
 * :plugin:runtime:test --tests '*SandboxBenchmark*' -i`); the plugin-sandbox
 * skill has the numbers the defaults were chosen on.
 */
@EnabledIfEnvironmentVariable(named = "NETHERFORGE_BENCH", matches = "1")
class SandboxBenchmark {
    private val calls = 20_000

    private val cases = linkedMapOf(
        "loop (3 instructions)" to "n = n + i",
        "allocating {}" to "local t = { i }",
        "string.format(\"%d-%s\")" to "string.format(\"%d-%s\", i, \"x\")",
        "s:find(\"%d+\")" to "line:find(\"%d+\")",
        "s:match(\"^(%S+)%s+(.*)$\")" to "line:match(\"^(%S+)%s+(.*)$\")",
        "s:gsub(\"%s\", \"_\")" to "line:gsub(\"%s\", \"_\")",
        "gmatch(\"%S+\") loop" to "for w in line:gmatch(\"%S+\") do end",
        "table.concat(four)" to "table.concat(four, \",\")",
        "table.insert(t, v)" to "table.insert(list, i)",
        "(\"x\"):rep(16)" to "(\"x\"):rep(16)"
    )

    /**
     * The intervals compared. The first four look at the clock every 10,000
     * instructions and at memory every 1,000, with fewer, longer hooks; the
     * rest change one check's interval at a time.
     */
    private val configs = linkedMapOf(
        "hook 100 (clock 100, memory 10)" to SandboxLimits(hookEvery = 100, deadlineEvery = 100, memoryEvery = 10),
        "hook 250 (clock 40, memory 4)" to SandboxLimits(hookEvery = 250, deadlineEvery = 40, memoryEvery = 4),
        "hook 500 (clock 20, memory 2)" to SandboxLimits(hookEvery = 500, deadlineEvery = 20, memoryEvery = 2),
        "hook 1000 (clock 10, memory 1)" to SandboxLimits(hookEvery = 1000, deadlineEvery = 10, memoryEvery = 1),
        "hook 1000, budget only" to SandboxLimits(hookEvery = 1000, deadlineEvery = 1_000_000_000, memoryEvery = 1_000_000_000),
        "hook 1000, clock every hook" to SandboxLimits(hookEvery = 1000, deadlineEvery = 1, memoryEvery = 1),
        "hook 1000, clock every 100" to SandboxLimits(hookEvery = 1000, deadlineEvery = 100, memoryEvery = 1),
        "hook 1000, memory every 10" to SandboxLimits(hookEvery = 1000, deadlineEvery = 10, memoryEvery = 10)
    )

    @Test
    fun `the sandbox's overhead`() {
        val script = buildString {
            appendLine("local n = 0")
            appendLine("local line = \"give Alex 64 diamonds please\"")
            appendLine("local four = { \"a\", \"b\", \"c\", \"d\" }")
            appendLine("local list = {}")
            appendLine("local cases = {")
            for (body in cases.values) appendLine("  function() for i = 1, $calls do $body end end,")
            appendLine("}")
            appendLine("local tick = 0")
            appendLine("this:on(\"tick\", function() cases[tick % #cases + 1](); tick = tick + 1; list = {} end)")
        }
        val keys = cases.keys.toList()
        val table = configs.mapValues { (_, limits) -> run(script, keys.size, limits) }
        println("bench ${"".padEnd(30)} ${configs.keys.joinToString(" | ")}")
        for ((k, name) in keys.withIndex()) {
            println("bench ${name.padEnd(30)} ${table.values.joinToString(" | ") { "${it[k] / calls} ns" }}")
        }
    }

    /** The best time of each case's tick over ten rounds, after five to warm up. */
    private fun run(script: String, count: Int, limits: SandboxLimits): LongArray {
        TestServer(
            mapOf(
                "centities/c/centity.json" to
                    TestServer.scriptedCentity().replace("\"script.lua\"", "\"script.lua\", \"budget\": 1000000000"),
                "centities/c/script.lua" to script
            ),
            start = false,
            performance = PerformanceConfig(warnMillis = 0.0),
            sandbox = limits
        ).use { server ->
            server.start()
            server.runtime.session.centities.spawn("c", Location("world", 0.0, 64.0, 0.0))
            assertEquals(emptyList(), server.errors.map { it.message })
            val best = LongArray(count) { Long.MAX_VALUE }
            for (round in 0 until 15) {
                for (k in 0 until count) {
                    val start = System.nanoTime()
                    server.tick()
                    val took = System.nanoTime() - start
                    if (round >= 5) best[k] = minOf(best[k], took)
                }
            }
            assertEquals(emptyList(), server.errors.map { it.message })
            return best
        }
    }

    /**
     * The library's C functions on a bare state (no hook, no caps): what one
     * unit of each cost costs, in nanoseconds. Pathological patterns at sizes
     * that end in under a second; their unit is a step of the caps' model, so
     * the worst case per step shows how loose the model's bound is.
     */
    @Test
    fun `the library's own costs`() {
        val script = """
            local function best(fn, reps)
              fn()
              local least = math.huge
              for _ = 1, 5 do
                local started = nano()
                for _ = 1, reps do fn() end
                least = math.min(least, (nano() - started) / reps)
              end
              return least
            end
            local function row(name, ns, units)
              say(string.format("cost %-44s %10.0f us  %8.2f ns/unit", name, ns / 1000, ns / units))
            end
            row("clock primitive (JNI), 1000 reads", best(function() for _ = 1, 1000 do nano() end end, 10), 1000)
            row("collectgarbage('count'), 1000", best(function() for _ = 1, 1000 do collectgarbage("count") end end, 10), 1000)
            for _, n in ipairs({ 1e6, 16e6 }) do
              row("rep, per byte, " .. n, best(function() local _ = ("x"):rep(n) end, 3), n)
            end
            local big = {}
            for i = 1, 1e6 do big[i] = i end
            row("table.move, per element", best(function() table.move(big, 1, 1e6, 1, {}) end, 3), 1e6)
            row("insert+remove at the front, per shift", best(function() table.insert(big, 1, 0) table.remove(big, 1) end, 3), 2e6)
            local numbers, strings = {}, {}
            for i = 1, 1e6 do numbers[i] = math.random() end
            for i = 1, 1e5 do strings[i] = tostring(math.random()) end
            row("sort numbers, per n log2 n", best(function() table.sort(table.move(numbers, 1, 1e6, 1, {})) end, 1), 1e6 * math.log(1e6, 2))
            row("sort strings, per n log2 n", best(function() table.sort(table.move(strings, 1, 1e5, 1, {})) end, 1), 1e5 * math.log(1e5, 2))
            -- Worst cases, against the model's steps (see caps.lua).
            local n = 2000
            local a = ("a"):rep(n)
            row("find %w+= on a's, per step (~3L^2)", best(function() a:find("%w+=") end, 3), 3 * n * n)
            local spaced = "a" .. (" "):rep(n) .. "a"
            row("trim on spaces, per step (~4L^2)", best(function() spaced:match("^%s*(.-)%s*$") end, 3), 4 * n * n)
            -- `.-` four times before a "b": each `.-` multiplies by L + 1 (caps.lua's LAZY), then every start.
            local m = 50
            local lazy = 1
            for _ = 1, 4 do lazy = (m + 1) * (lazy + 2) end
            local short = ("a"):rep(m)
            row(".-.-.-.-b, per step (the model's bound)", best(function() short:find(".-.-.-.-b") end, 1), (m + 1) * lazy)
            local hay, needle = ("a"):rep(1e5), ("a"):rep(5e4) .. "b"
            row("plain find, per step ((L - m) (1 + m/32))", best(function() hay:find(needle, 1, true) end, 1), 5e4 * (1 + 5e4 / 32))
        """.trimIndent()
        Lua54().use { lua ->
            lua.openLibraries()
            lua.push(
                JFunction { l ->
                    l.push(System.nanoTime())
                    1
                }
            )
            lua.setGlobal("nano")
            lua.push(
                JFunction { l ->
                    println(l.toString(1))
                    0
                }
            )
            lua.setGlobal("say")
            lua.load(LuaHost.direct(script), "=bench")
            lua.pCall(0, 0)
        }
    }
}
