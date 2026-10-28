package dev.netherforge.plugin.scoreboard
import dev.netherforge.format.resourcepack.PackFonts
import dev.netherforge.plugin.TestServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `<space:N>`, `<at:N>` and `nf.text.space`: moving the cursor in text, and a boss bar's icon placed with it. */
class TextSpacingTest {
    @Test
    fun `spaces are the pack's characters, and nothing while there's no pack`() {
        TestServer(
            TestServer.example("showcase") + ("modules/s/init.lua" to "log(nf.text.space(37) == nf.text.space(40 - 3))")
        ).use { server ->
            assertEquals(PackFonts.offset(-3), server.platform.spaces(-3))
            assertEquals(listOf("true"), server.logs)
        }
        TestServer(mapOf("modules/s/init.lua" to "log(tostring(nf.text.space(4)))")).use { server ->
            assertNull(server.platform.spaces(4))
            assertEquals(listOf("nil"), server.logs)
        }
    }

    @Test
    fun `an at becomes the moves around it, and one that can't be placed draws in line, reported once`() {
        TestServer(TestServer.example("showcase")).use { server ->
            val coin = assertNotNull(server.runtime.packs.glyphAdvance("ui/coin"), "the coin's picture is measured")
            // Only glyphs inside: no font file needed.
            assertEquals(
                "<space:-30><at:-30><glyph:ui/coin><space:${30 - coin}></at>King",
                server.platform.layout("<at:-30><glyph:ui/coin></at>King")
            )
            // Counted from the centre of a line only the client can measure.
            val unplaced = "<at:0:from=center><glyph:ui/coin></at><lang:block.minecraft.stone>"
            assertEquals(unplaced, server.platform.layout(unplaced))
            assertEquals(unplaced, server.platform.layout(unplaced))
            val warnings = server.platform.log.lines.filter { "draws what it holds in line" in it }
            assertEquals(1, warnings.size, warnings.toString())
            assertEquals("plain", server.platform.layout("plain"))
        }
    }

    @Test
    fun `a boss bar's icon sits left of the bar, whatever its text`() {
        val script = """
            local bar = nf.bossbars.create({ text = "King Slime", icon = "ui/coin" })
            log(bar:icon())
            bar:set_text("Eye")
            nf.after(1, function()
              bar:set_icon(nil)
              log(tostring(bar:icon()))
              log(tostring(pcall(bar.set_icon, bar, "ui/nothing")))
            end)
        """
        TestServer(TestServer.example("showcase") + ("modules/b/init.lua" to script)).use { server ->
            val bars = server.platform.bossBars.bars
            assertEquals("<at:-92:from=center:align=right><glyph:ui/coin></at>Eye", bars.values.single().text)
            server.tick(1)
            assertEquals("Eye", bars.values.single().text)
            assertEquals(listOf("ui/coin", "nil", "false"), server.logs)
            assertTrue(server.errors.isEmpty(), server.errors.toString())
        }
    }
}
