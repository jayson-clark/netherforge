package dev.netherforge.plugin.lua
import dev.netherforge.format.bridge.SourceRef
import dev.netherforge.plugin.TestServer
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ways a script used to hang the server inside one instruction, where
 * the budget's hook can't fire: a C function of the standard library doing
 * unbounded work. Each now fails at the script's line, with the server's
 * default limits, at once. (Without the caps, each of these runs for seconds
 * to hours, or takes gigabytes.)
 */
@Timeout(30)
class HangTest {
    /** The one error running [script] as a module's body gives, which must be at its line [line]. */
    private fun refused(script: String, line: Int = 1): String {
        TestServer(mapOf("modules/t/init.lua" to script)).use { server ->
            val error = server.errors.single()
            assertEquals(SourceRef("modules/t/init.lua", line), error.source, error.message)
            return error.message
        }
    }

    @Test
    fun `string rep with a huge count`() {
        val message = refused("""local s = ("x"):rep(1e10)""")
        assertTrue("string.rep: the result would be 10000000000 bytes, over the limit of 16 MB" in message, message)
    }

    @Test
    fun `string rep of nothing a huge number of times`() {
        // lstrlib loops once per repetition even when each copies nothing.
        val message = refused("""local s = string.rep("", 1e15)""")
        assertTrue("string.rep: repeating 1000000000000000 times is over the limit" in message, message)
    }

    @Test
    fun `string rep with a big separator`() {
        val message = refused("""local s = string.rep("x", 100000, ("y"):rep(1000))""")
        assertTrue("string.rep: the result would be 100099000 bytes" in message, message)
    }

    @Test
    fun `a pattern that backtracks`() {
        val message = refused("""local i = string.find(("a"):rep(10000), ".-.-.-.-b")""")
        assertTrue("string.find: the pattern \".-.-.-.-b\" could take up to" in message, message)
        assertTrue("on 10000 bytes, over the limit of 1e+08" in message, message)
    }

    @Test
    fun `a pattern that backtracks, through each pattern function`() {
        val s = """local s = ("a"):rep(10000)"""
        assertTrue("string.match: the pattern" in refused("$s\nlocal m = s:match(\"(.-)%w+=\")", line = 2))
        assertTrue("string.gmatch: the pattern" in refused("$s\nfor w in s:gmatch(\"%w+=\") do end", line = 2))
        assertTrue("string.gsub: the pattern" in refused("$s\nlocal r = s:gsub(\"(.-)(.-)b\", \"\")", line = 2))
    }

    @Test
    fun `looking for a long string in a longer one`() {
        // A plain find compares the needle at every start: (n/2)^2 bytes here.
        val message = refused("""local i = ("a"):rep(1000000):find(("a"):rep(500000) .. "b", 1, true)""")
        assertTrue("string.find: looking for 500001 bytes in 1000000 could take up to" in message, message)
    }

    @Test
    fun `a format width past what the library allows`() {
        // Lua 5.4 refuses a width of more than two digits itself, before making anything.
        val message = refused("""local s = string.format("%99999d", 1)""")
        assertTrue("invalid conversion specification: '%99999d'" in message, message)
    }

    @Test
    fun `a format string repeating big arguments`() {
        val message = refused(
            """
            local big = ("x"):rep(1000000)
            local args = {}
            for i = 1, 20 do args[i] = big end
            local s = string.format(("%s"):rep(20), table.unpack(args))
            """.trimIndent(),
            line = 4
        )
        assertTrue("string.format: the result would be 20000040 bytes, over the limit of 16 MB" in message, message)
    }

    @Test
    fun `a format string repeating a big tostring`() {
        val message = refused(
            """
            local big = ("x"):rep(1000000)
            local loud = setmetatable({}, { __tostring = function() return big end })
            local s = string.format(("%s"):rep(20), loud, loud, loud, loud, loud, loud, loud, loud, loud, loud, loud, loud, loud, loud, loud, loud, loud, loud, loud, loud)
            """.trimIndent(),
            line = 3
        )
        assertTrue("string.format: the result would be" in message, message)
    }

    @Test
    fun `gsub multiplying a string`() {
        val message = refused("""local s = ("x"):rep(100000):gsub("x", ("y"):rep(1000))""")
        assertTrue("string.gsub: the result would be" in message, message)
    }

    @Test
    fun `gsub multiplying a string through a function`() {
        val message = refused(
            """
            local big = ("y"):rep(1000000)
            local s = ("x"):rep(100):gsub("x", function() return big end)
            """.trimIndent(),
            line = 2
        )
        assertTrue("string.gsub: the result would be" in message, message)
    }

    @Test
    fun `table concat of a big table`() {
        val message = refused(
            """
            local big = ("x"):rep(1000000)
            local t = {}
            for i = 1, 1000 do t[i] = big end
            local s = table.concat(t)
            """.trimIndent(),
            line = 4
        )
        assertTrue("table.concat: the result would be 1000000000 bytes, over the limit of 16 MB" in message, message)
    }

    @Test
    fun `string pack padding a huge field`() {
        val message = refused("""local s = string.pack("c2000000000", "")""")
        assertTrue("string.pack: the result would be" in message, message)
    }

    @Test
    fun `table move over a huge range`() {
        val message = refused("""table.move({}, 1, 1e12, 1, {})""")
        assertTrue("table.move: moving 1000000000000 elements could take up to" in message, message)
    }

    @Test
    fun `sorting a huge table`() {
        // `byte` fills the table in one instruction, so only the sort is left to stop.
        val message = refused("""local t = { ("x"):rep(900000):byte(1, -1) } table.sort(t)""")
        assertTrue("table.sort: sorting 900000 values could take up to" in message, message)
    }

    @Test
    fun `a string doubled with concatenation stops at the memory limit`() {
        // `..` is a VM instruction, not a library function: nothing caps it, and
        // doubling outgrows any limit between two count hooks. The end of each
        // collection cycle checks memory.
        TestServer(
            mapOf("modules/t/init.lua" to "local s = \"x\"\nfor i = 1, 30 do s = s .. s end"),
            sandbox = SandboxLimits(memoryMegabytes = 64)
        ).use { server ->
            val error = server.errors.single()
            assertTrue("used more memory than scripts may (64 MB together" in error.message, error.message)
            assertEquals(SourceRef("modules/t/init.lua", 2), error.source)
        }
    }

    @Test
    fun `a loop that ends collection cycles faster than the hook counts is still stopped`() {
        // Arming a thread's hook again starts its count over: a loop making garbage ends a
        // collection cycle every few hundred instructions, and each cycle's check re-armed it.
        val message = refused(
            """
            local s = ("x"):rep(64 * 1024)
            while true do local u = s .. "a" end
            """.trimIndent(),
            line = 2
        )
        assertTrue("ran past its budget" in message || "ran past its time limit" in message, message)
    }

    @Test
    fun `the same loop in a handler is stopped too`() {
        TestServer(
            mapOf(
                "modules/t/init.lua" to """
                    nf.after(1, function()
                      local s = ("x"):rep(64 * 1024)
                      while true do local u = s .. "a" end
                    end)
                """.trimIndent()
            )
        ).use { server ->
            server.tick(2)
            val error = server.errors.single()
            assertEquals(SourceRef("modules/t/init.lua", 3), error.source, error.message)
        }
    }

    /** A table whose `__len` says 1 to the cap and two billion to the library (which then shifts in C). */
    private val lying = """
        local asked = 0
        local t = setmetatable({}, { __len = function() asked = asked + 1; return asked == 1 and 1 or 2000000000 end })
    """.trimIndent()

    @Test
    fun `table remove reads a length that changes only once`() {
        TestServer(mapOf("modules/t/init.lua" to "$lying\ntable.remove(t, 1)\nlog('removed, asked ' .. asked)")).use { server ->
            assertEquals(emptyList(), server.errors.map { it.message })
            assertEquals(listOf("removed, asked 1"), server.logs)
        }
    }

    @Test
    fun `table insert reads a length that changes only once`() {
        TestServer(mapOf("modules/t/init.lua" to "$lying\ntable.insert(t, 1, 'x')\nlog('inserted, asked ' .. asked)")).use { server ->
            assertEquals(emptyList(), server.errors.map { it.message })
            assertEquals(listOf("inserted, asked 1"), server.logs)
        }
    }

    @Test
    fun `table sort reads a length that changes only once`() {
        TestServer(
            mapOf("modules/t/init.lua" to "$lying\nrawset(t, 1, 5)\ntable.sort(t)\nlog('sorted, asked ' .. asked)")
        ).use { server ->
            assertEquals(emptyList(), server.errors.map { it.message })
            assertEquals(listOf("sorted, asked 1"), server.logs)
        }
    }

    @Test
    fun `table concat measures the very values it joins`() {
        // An `__index` that hands the cap empty strings and the library 16 MB ones.
        val message = refused(
            """
            local big = ("x"):rep(16 * 1024 * 1024)
            local reads = 0
            local t = setmetatable({}, { __index = function() reads = reads + 1; return reads <= 200 and "" or big end })
            local s = table.concat(t, "", 1, 400)
            """.trimIndent(),
            line = 4
        )
        assertTrue("table.concat: the result would be" in message, message)
    }

    @Test
    fun `tables with metatables still insert, remove, sort and concat as the library does`() {
        TestServer(
            mapOf(
                "modules/t/init.lua" to """
                    local List = {}
                    List.__index = List
                    local l = setmetatable({ 3, 1, 2 }, List)
                    table.insert(l, 1, 4)
                    table.insert(l, 9)
                    local removed = table.remove(l, 2)
                    table.sort(l)
                    log(table.concat(l, ","), removed, table.remove(l), #l)
                    log(select(2, pcall(table.insert, l, 9, 1)))
                """.trimIndent()
            )
        ).use { server ->
            assertEquals(emptyList(), server.errors.map { it.message })
            assertEquals("1,2,4,9\t3\t9\t3", server.logs[0])
            assertTrue("position out of bounds" in server.logs[1], server.logs[1])
        }
    }

    @Test
    fun `encoding a huge table is charged to the script, not done for free`() {
        // `byte` fills the table in one instruction; the walk over it is the runtime's, in Kotlin.
        val message = refused("""local t = { ("x"):rep(900000):byte(1, -1) } local s = nf.json.encode(t)""")
        assertTrue("ran past its budget" in message, message)
    }

    @Test
    fun `sorting a huge object's keys to encode it is charged too`() {
        TestServer(
            mapOf(
                "modules/t/init.lua" to """
                    local t, n = {}, 0
                    nf.every(1, function()
                      if n < 60000 then
                        for i = 1, 5000 do n = n + 1; t["key" .. n] = n end
                      elseif n == 60000 then
                        n = n + 1
                        local ok, e = pcall(nf.json.encode, t)
                        log(tostring(ok), tostring(e))
                      end
                    end)
                """.trimIndent()
            )
        ).use { server ->
            server.tick(16)
            assertTrue(server.errors.any { "ran past its budget" in it.message }, server.errors.map { it.message }.toString())
        }
    }

    @Test
    fun `shuffling a list that claims to be huge is charged, not looped for free`() {
        // The shuffle's loop is the prelude's, which the hook never stops mid-way: it's charged before it starts.
        val message = refused(
            """local t = setmetatable({}, { __len = function() return 1e12 end }) nf.random.new(1):shuffle(t)"""
        )
        assertTrue("ran past its budget" in message, message)
    }

    @Test
    fun `picking from huge weights is charged as it reads and sorts them`() {
        // `byte` fills the table in one instruction; the weights are read and sorted by the prelude.
        val message = refused("""local k = nf.random.new(1):weighted({ ("x"):rep(300000):byte(1, -1) })""")
        assertTrue("ran past its budget" in message, message)
    }

    @Test
    fun `shuffle and weighted still do their job on ordinary tables`() {
        TestServer(
            mapOf(
                "modules/t/init.lua" to """
                    local r = nf.random.new(7)
                    local t = { 1, 2, 3, 4, 5 }
                    r:shuffle(t)
                    table.sort(t)
                    log(table.concat(t, ","), r:weighted({ a = 1, b = 0 }))
                """.trimIndent()
            )
        ).use { server ->
            assertEquals(emptyList(), server.errors.map { it.message })
            assertEquals(listOf("1,2,3,4,5\ta"), server.logs)
        }
    }

    @Test
    fun `the Lua codec stops encoding a huge table once the script is past its budget`() {
        // A Vec3 in it makes it more than plain data: the prelude's codec walks it, not Kotlin.
        val message = refused("""local t = { ("x"):rep(900000):byte(1, -1) } t[1] = vec3(0, 0, 0) local s = nf.json.encode(t)""")
        assertTrue("ran past its budget" in message, message)
    }
}
