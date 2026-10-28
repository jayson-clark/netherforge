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
}
