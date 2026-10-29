package dev.netherforge.plugin.lua

import dev.netherforge.plugin.TestServer
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Kotlin reading a table a script passed it (a shape's fields): a script's
 * `__index` runs, but whatever it does (an error, running out of budget) is the
 * call's error at the script's line, never a Lua error thrown through JNI, which
 * crashed the JVM.
 */
@Timeout(30)
class TableReadsTest {
    @Test
    fun `an __index that raises an error is the call's error`() {
        TestServer(
            mapOf(
                "modules/t/init.lua" to """
                    local opts = setmetatable({}, { __index = function(_, k) error("no " .. k) end })
                    local ok, e = pcall(function() nf.worlds.default():set_weather("clear", opts) end)
                    log(tostring(ok), e)
                """.trimIndent()
            )
        ).use { server ->
            assertEquals(emptyList(), server.errors.map { it.message })
            assertTrue(server.logs.single().startsWith("false\t"), server.logs.single())
            assertTrue("no ticks" in server.logs.single(), server.logs.single())
        }
    }

    @Test
    fun `an __index that runs past the budget stops the call`() {
        TestServer(
            mapOf(
                "modules/t/init.lua" to """
                    local opts = setmetatable({}, { __index = function() while true do end end })
                    pcall(function() nf.worlds.default():set_weather("clear", opts) end)
                    log("caught it")
                """.trimIndent()
            )
        ).use { server ->
            // The overrun is reported even though pcall caught it, and the server is still there to say so.
            assertTrue("ran past its budget" in server.errors.single().message, server.errors.single().message)
        }
    }

    @Test
    fun `an __index that answers is read as the field`() {
        TestServer(
            mapOf(
                "modules/t/init.lua" to """
                    local opts = setmetatable({}, { __index = function(_, k) if k == "ticks" then return 0 end end })
                    local ok, e = pcall(function() nf.worlds.default():set_weather("clear", opts) end)
                    log(tostring(ok), e)
                """.trimIndent()
            )
        ).use { server ->
            // A ticks below 1 is the API's own refusal: so the field was read through __index.
            assertTrue("ticks" in server.logs.single() && server.logs.single().startsWith("false"), server.logs.single())
        }
    }
}
