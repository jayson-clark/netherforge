package dev.netherforge.plugin.lua
import dev.netherforge.plugin.TestServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Kotlin's `%g` and the codec's float spelling ([CodecNumbers]) against Lua's
 * own: every number a script made is logged with its exact bits (`%a`), what
 * C's `%.14g` and `%.17g` make of it, and what the codec writes (a table with a
 * key starting with "$", which only the codec writes), and Kotlin must make the
 * same of the same bits.
 */
class CodecNumbersTest {
    @Test
    fun `floats are spelled as Lua spells them`() {
        val module = """
            local numbers = {
              0.1, 0.2, 0.1 + 0.2, 1 / 3, 2 / 3, 0.5, 1.5, 2.5, -2.5, 100.0, 1e14, 1e15, 1e16, 1e17, 1e20, 1e21,
              123456789012345.67, 99999999999999.95, 9.99999999999995, 0.00001, 0.0001, 0.001, 1e-5, 1.5e-5, 1e-300,
              5e-324, 2.2250738585072014e-308, 1.7976931348623157e308, -0.0, 0.0, 3.0, -3.0, 64.5, 1e100, 4.35,
              0.30000000000000004, 1234567.0, 12345678901234.0, 0.000123456789012345678,
            }
            math.randomseed(42)
            for _ = 1, 2000 do
              local bits = string.pack("<i8", math.random(math.mininteger, math.maxinteger))
              local n = string.unpack("<d", bits)
              if n == n and n ~= math.huge and n ~= -math.huge then
                numbers[#numbers + 1] = n
              end
            end
            for i = 1, 300 do
              numbers[#numbers + 1] = i / 7 * 10 ^ (i % 40 - 20)
            end
            local done = 0
            nf.commands.register("batch", function()
              for i = done + 1, math.min(done + 100, #numbers) do
                local n = numbers[i]
                -- A key starting with "$" has the codec itself write the table, not Kotlin.
                log(string.format("%a", n), string.format("%.14g", n), string.format("%.17g", n), nf.json.encode({ n = n, ["${'$'}codec"] = true }))
              end
              done = done + 100
            end)
        """.trimIndent()
        TestServer(mapOf("modules/t/init.lua" to module)).use { server ->
            repeat(30) { server.platform.commands.runConsole("batch") }
            assertEquals(emptyList(), server.errors)
            assertTrue(server.logs.size > 2000, "${server.logs.size} numbers")
            for (line in server.logs) {
                val (hex, g14, g17, json) = line.split('\t')
                val value = java.lang.Double.parseDouble(hex)
                assertEquals(g14, CodecNumbers.g(value, 14), "%.14g of $hex")
                assertEquals(g17, CodecNumbers.g(value, 17), "%.17g of $hex")
                assertEquals(json, "{\"${'$'}codec\":true,\"n\":${CodecNumbers.json(value)}}", "the codec's $hex")
            }
        }
    }
}
