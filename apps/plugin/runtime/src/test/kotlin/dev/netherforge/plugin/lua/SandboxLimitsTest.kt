package dev.netherforge.plugin.lua
import dev.netherforge.format.bridge.SourceRef
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.module.Modules
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the sandbox holds a call in to besides its instruction budget: a time
 * limit, the scripts' memory (and who's blamed for it), and caps on the
 * standard library that leave everything under them as the library does it.
 * The hangs those caps stop are [HangTest].
 */
@Timeout(60)
class SandboxLimitsTest {
    private fun steppingClock(): () -> Long {
        var now = 0L
        return {
            now += 1_000_000
            now
        }
    }

    @Test
    fun `a call that runs past its time limit is stopped where it was`() {
        // The clock moves 1 ms each time it's read, which the hook does every
        // `deadlineEvery` hooks: the sixth read is past 5 ms, long before the budget.
        TestServer(
            mapOf(
                "modules/t/init.lua" to """
                    nf.on("tick", function()
                      local n = 0
                      for i = 1, 60000 do n = n + i end
                    end)
                """.trimIndent()
            ),
            clock = steppingClock(),
            sandbox = SandboxLimits(deadlineMillis = 5)
        ).use { server ->
            server.tick()
            val error = server.errors.single()
            assertTrue("ran past its time limit of 5 ms" in error.message, error.message)
            assertEquals(SourceRef("modules/t/init.lua", 3), error.source)
            assertEquals(Modules.Status.RUNNING, server.runtime.session.modules.status("t"))
        }
    }

    @Test
    fun `a loop of library calls under the caps is stopped by the time limit`() {
        // Each `rep` is one instruction: the budget alone would let this run for a minute.
        TestServer(
            mapOf(
                "modules/t/init.lua" to """
                    nf.on("tick", function()
                      while true do local s = ("x"):rep(1000000) end
                    end)
                """.trimIndent()
            ),
            sandbox = SandboxLimits(deadlineMillis = 100)
        ).use { server ->
            val started = System.nanoTime()
            server.tick()
            val took = (System.nanoTime() - started) / 1_000_000
            val error = server.errors.single()
            assertTrue("ran past its time limit of 100 ms" in error.message, error.message)
            assertEquals(SourceRef("modules/t/init.lua", 2), error.source)
            assertTrue(took < 5_000, "took $took ms")
        }
    }

    @Test
    fun `shifting a big table in a loop is stopped by the time limit`() {
        TestServer(
            mapOf(
                "modules/t/init.lua" to """
                    local t = { ("x"):rep(500000):byte(1, -1) }
                    nf.on("tick", function()
                      while true do table.insert(t, 1, 0) end
                    end)
                """.trimIndent()
            ),
            sandbox = SandboxLimits(deadlineMillis = 100)
        ).use { server ->
            server.tick()
            assertTrue("ran past its time limit of 100 ms" in server.errors.single().message, server.errors.single().message)
        }
    }

    @Test
    fun `a nested call in has what's left of its caller's time, and both are stopped`() {
        TestServer(
            mapOf(
                "centities/c/centity.json" to TestServer.scriptedCentity(),
                "centities/c/script.lua" to "while true do local s = (\"x\"):rep(1000000) end",
                "modules/t/init.lua" to """
                    nf.on("tick", function()
                      nf.centities.spawn("c", vec3(0, 64, 0))
                      log("went on")
                    end)
                """.trimIndent()
            ),
            sandbox = SandboxLimits(deadlineMillis = 200)
        ).use { server ->
            val started = System.nanoTime()
            server.tick()
            val took = (System.nanoTime() - started) / 1_000_000
            // The centity's body stops at the handler's deadline, not 200 ms after it started; the handler then stops too.
            assertTrue(took < 2_000, "took $took ms")
            assertEquals(2, server.errors.count { "ran past its time limit of 200 ms" in it.message }, "${server.errors}")
            assertTrue("went on" !in server.logs)
        }
    }

    @Test
    fun `the script holding the memory is blamed, not the one that crossed the limit`() {
        // `hog` holds 24 MB of the 32; `busy` then needs 12 MB for a moment.
        TestServer(
            mapOf(
                "modules/hog/init.lua" to """
                    kept = {}
                    for i = 1, 24 do kept[i] = ("h"):rep(1000000) .. i end
                """.trimIndent(),
                "modules/busy/init.lua" to """
                    nf.on("tick", function()
                      local scratch = {}
                      for i = 1, 12 do scratch[i] = ("b"):rep(1000000) .. i end
                      for i = 1, 1000 do end
                      log("busy done")
                    end)
                """.trimIndent()
            ),
            sandbox = SandboxLimits(memoryMegabytes = 32, memoryEvery = 1)
        ).use { server ->
            assertEquals(emptyList(), server.errors.map { it.message })
            server.tick()
            val error = server.errors.single()
            assertTrue(error.message.startsWith("module hog: using memory failed: it held about 2"), error.message)
            assertTrue("the most of any script, when scripts went over the 32 MB they may use" in error.message, error.message)
            assertEquals(Modules.Status.FAILED, server.runtime.session.modules.status("hog"))
            assertEquals(Modules.Status.RUNNING, server.runtime.session.modules.status("busy"))
            assertEquals(listOf("busy done"), server.logs)
            // What hog held is gone: busy goes on as before.
            server.tick(3)
            assertEquals(1, server.errors.size, "${server.errors}")
            assertEquals(4, server.logs.count { it == "busy done" })
        }
    }

    @Test
    fun `a script that keeps taking memory is stopped in its own call, and lets go of it`() {
        TestServer(
            mapOf(
                "modules/hog/init.lua" to """
                    local kept = {}
                    nf.on("tick", function()
                      for i = 1, 8 do kept[#kept + 1] = ("h"):rep(1000000) .. #kept end
                    end)
                """.trimIndent(),
                "modules/other/init.lua" to """nf.on("tick", function() log("other") end)"""
            ),
            sandbox = SandboxLimits(memoryMegabytes = 32, memoryEvery = 1)
        ).use { server ->
            server.tick(6)
            val stopped = server.errors.filter { "used more memory than scripts may (32 MB together" in it.message }
            assertEquals(1, stopped.size, "${server.errors}")
            assertEquals(SourceRef("modules/hog/init.lua", 3), stopped.single().source)
            assertTrue(server.errors.any { "module hog: using memory failed" in it.message }, "${server.errors}")
            assertEquals(Modules.Status.FAILED, server.runtime.session.modules.status("hog"))
            assertEquals(6, server.logs.count { it == "other" })
        }
    }

    @Test
    fun `the census says what each script holds, and a shared table is its module's`() {
        TestServer(
            mapOf(
                "modules/store/init.lua" to """
                    local M = { items = {} }
                    return M
                """.trimIndent(),
                "modules/filler/init.lua" to """
                    local store = require("store")
                    store.items[1] = ("x"):rep(2000000)
                    mine = ("y"):rep(3000000)
                """.trimIndent()
            )
        ).use { server ->
            assertEquals(emptyList(), server.errors.map { it.message })
            val memory = server.runtime.session.scripts.host!!.memory()
            val store = server.runtime.session.scripts.scopes().single { it.module == "store" }.id
            val filler = server.runtime.session.scripts.scopes().single { it.module == "filler" }.id
            assertTrue(memory.getValue(store) in 2_000_000L until 2_100_000L, "$memory")
            assertTrue(memory.getValue(filler) in 3_000_000L until 3_100_000L, "$memory")
        }
    }

    @Test
    fun `finalizers aren't allowed`() {
        TestServer(mapOf("modules/t/init.lua" to """local t = setmetatable({}, { __gc = function() end })""")).use { server ->
            val error = server.errors.single()
            assertTrue("setmetatable: __gc isn't allowed" in error.message, error.message)
            assertEquals(SourceRef("modules/t/init.lua", 1), error.source)
        }
    }

    @Test
    fun `the capped functions answer as the library does`() {
        TestServer(
            mapOf(
                "modules/t/init.lua" to """
                    log(("ab"):rep(3, ","), string.format("%5.1f|%s|%q|%d|%s", 2.25, true, "a\n", 7, setmetatable({}, { __tostring = function() return "T" end })))
                    log(("key=value"):match("(%w+)=(%w+)"))
                    log(("a,b,,c"):find(",", 1, true), ("a,b"):find("(,)"), ("abc"):find("b", -1), ("abc"):find("x"))
                    local words = {}
                    for w in ("one two  three"):gmatch("%S+") do words[#words + 1] = w end
                    log(table.concat(words, "|"), ("  trim me  "):match("^%s*(.-)%s*$"))
                    log(("hello world"):gsub("o", "0"))
                    log(("hello"):gsub("l", { l = "L" }), ("abc"):gsub("%w", function(c) return c:upper() end), ("abc"):gsub("", "-"))
                    local t = { 3, 1, 2 }
                    table.sort(t)
                    table.insert(t, 1, 0)
                    table.insert(t, 9)
                    log(table.concat(t, ","), table.remove(t, 1), table.remove(t), table.concat(table.move({ 1, 2, 3 }, 1, 3, 2, {}), ",", 2, 4))
                    log(#string.pack("i4c3", 7, "abc"), string.format("%%"), string.format("%5s|%-3d|", "x", 1))
                    log(select(2, pcall(string.rep, "x")))
                    log(select(2, pcall(string.format, "%d", "x")))
                """.trimIndent()
            )
        ).use { server ->
            assertEquals(emptyList(), server.errors.map { it.message })
            assertEquals(
                listOf(
                    "ab,ab,ab\t  2.2|true|\"a\\\n\"|7|T",
                    "key\tvalue",
                    "2\t2\tnil\tnil",
                    "one|two|three\ttrim me",
                    "hell0 w0rld\t2",
                    "heLLo\tABC\t-a-b-c-\t4",
                    "0,1,2,3,9\t0\t9\t1,2,3",
                    "7\t%\t    x|1  |",
                    "bad argument #2 to 'rep' (number expected, got nil)",
                    "bad argument #2 to 'format' (number expected, got string)"
                ),
                server.logs
            )
        }
    }

    @Test
    fun `a pattern's cost is its worst case, linear or not`() {
        // `[^,]+`, a plain search and a gsub are linear: any string under the size cap. A trim
        // (`^%s*(.-)%s*$`) backtracks quadratically on a string like "a   ...   a", so it runs
        // on 4000 bytes and is refused on 6000, however quick this string would have been.
        TestServer(
            mapOf(
                "modules/t/init.lua" to """
                    local csv = ("field,"):rep(20000)
                    local n = 0
                    for _ in csv:gmatch("[^,]+") do n = n + 1 end
                    log(n, csv:find("d,$"), select(2, csv:gsub(",", ";")))
                    log((" "):rep(4000):match("^%s*(.-)%s*$") == "")
                    log(pcall(string.match, (" "):rep(6000), "^%s*(.-)%s*$"))
                """.trimIndent()
            )
        ).use { server ->
            assertEquals(emptyList(), server.errors.map { it.message })
            assertEquals("20000\t119999\t20000", server.logs[0])
            assertEquals("true", server.logs[1])
            assertTrue(
                server.logs[2].startsWith("false\tmodules/t/init.lua:6: string.match: the pattern \"^%s*(.-)%s*$\" could take up to"),
                server.logs[2]
            )
        }
    }

    @Test
    fun `backtracking that fails at once isn't counted as a full try`() {
        // A run of `%s` given back one character at a time is tried against `[%w_]`, which can't
        // match a space: each try fails at its first item, so the tag reader below is linear,
        // while an item followed by one that can match what it gave back (`a*a*b`) still
        // backtracks quadratically and is refused.
        TestServer(
            mapOf(
                "modules/t/init.lua" to """
                    local tag = '^%[%s*([%w_]+)%s*"()'
                    local pgn = ('[Site "' .. ("x"):rep(20000) .. '"]\n'):rep(4)
                    log(("[" .. (" "):rep(100000)):match(tag) == nil, pgn:match(tag, 1))
                    log(("key = value\n"):rep(10000):match("^%s*([%w_]+)%s*=%s*"))
                    log(pcall(string.match, ("a"):rep(100000), "^a*a*b"))
                """.trimIndent()
            )
        ).use { server ->
            assertEquals(emptyList(), server.errors.map { it.message })
            assertEquals("true\tSite\t8", server.logs[0])
            assertEquals("key", server.logs[1])
            assertTrue(
                server.logs[2].startsWith("false\tmodules/t/init.lua:5: string.match: the pattern \"^a*a*b\" could take up to"),
                server.logs[2]
            )
            assertTrue(server.logs[2].endsWith("(match a shorter string, or simplify the pattern)"), server.logs[2])
        }
    }
}
