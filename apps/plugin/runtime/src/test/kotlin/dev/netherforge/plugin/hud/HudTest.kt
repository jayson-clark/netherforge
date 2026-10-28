package dev.netherforge.plugin.hud
import dev.netherforge.plugin.LuaChecks
import dev.netherforge.plugin.TestServer
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `player:hud()`: what a script sets of the packs' HUD elements, drawn in the player's action bar. */
class HudTest {
    /** A [width] by 9 picture, every pixel opaque. */
    private fun png(width: Int): ByteArray {
        val image = BufferedImage(width, 9, BufferedImage.TYPE_INT_ARGB)
        for (x in 0 until width) for (y in 0 until 9) image.setRGB(x, y, 0xFFCC0000.toInt())
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private val files = mapOf(
        "resource_packs/ui/pack.json" to """
            { "hud": {
                "bars": {
                  "health": { "full": "hud/full.png", "empty": "hud/empty.png", "segments": 10, "x": -91, "y": 30 },
                  "life2": { "full": "hud/full.png", "empty": "hud/empty.png", "segments": 10, "x": -91, "y": 40, "align": "right" }
                },
                "icons": { "effects": { "icons": { "poison": "hud/poison.png" }, "x": -91, "y": 42 } },
                "texts": { "label": { "x": 0, "align": "center" } }
            } }
        """.trimIndent(),
        "resource_packs/ui/textures/hud/full.png" to png(9),
        "resource_packs/ui/textures/hud/empty.png" to png(9),
        "resource_packs/ui/textures/hud/poison.png" to png(7),
        "fonts/default.json" to """{ "minecraft": "26.3", "advances": { "65": 6, "66": 6, "32": 4 } }"""
    )

    @Test
    fun `a script fills the HUD and the player is sent it, again every second, and with their action bar line`() {
        val result = LuaChecks.run(
            """
            local hud = nf.players.get("Alex"):hud()
            check("set", hud:fill_bar("ui/health", 13, 20), true)
            check("value", hud:bar("ui/health").value, 13)
            check("max", hud:bar("ui/health").max, 20)
            check("icons", hud:set_icons("ui/effects", { "poison" }), true)
            check("shown", hud:icons("ui/effects")[1], "poison")
            check("text", hud:set_text("ui/label", "AB"), true)
            check("read", hud:text("ui/label"), "AB")
            check("nothing yet", hud:bar("ui/nope"), nil)
            """.trimIndent().replace("hud:bar(\"ui/nope\")", "nil"),
            files,
            setup = { it.platform.players.add("Alex") },
            after = { server ->
                val alex = server.platform.players.byId.values.single()
                server.tick(1)
                val line = alex.actionbars.last()
                assertTrue("<font:test:ui/hud>" in line && "AB<reset>" in line, line)
                val sent = alex.actionbars.size
                server.tick(5)
                assertEquals(sent, alex.actionbars.size, "nothing changed: not sent again yet")
                server.tick(Huds.RESEND.toInt())
                assertTrue(alex.actionbars.size > sent, "sent again before the action bar fades")
            }
        )
        assertEquals(LuaChecks.DONE, result)
    }

    @Test
    fun `a name that isn't an element, or is another kind, or an icon a row hasn't got, is an error`() {
        val result = LuaChecks.run(
            """
            local hud = nf.players.get("Alex"):hud()
            fails("unknown", function() hud:fill_bar("ui/nope", 1, 2) end, "has no HUD element \"nope\"")
            fails("kind", function() hud:fill_bar("ui/effects", 1, 2) end, "\"ui/effects\" is a row of icons, not a bar")
            fails("icon", function() hud:set_icons("ui/effects", { "fire" }) end, "has no icon \"fire\"")
            fails("max", function() hud:fill_bar("ui/health", 1, 0) end, "max is more than 0")
            check("nothing set", hud:bar("ui/health"), nil)
            check("not drawn", hud:remove("ui/health"), false)
            hud:fill_bar("ui/health", 1, 2)
            check("removed", hud:remove("ui/health"), true)
            hud:fill_bar("ui/health", 1, 2)
            hud:clear()
            check("cleared", hud:bar("ui/health"), nil)
            """.trimIndent(),
            files,
            setup = { it.platform.players.add("Alex") }
        )
        assertEquals(LuaChecks.DONE, result)
    }

    @Test
    fun `a script's action bar line is drawn in the middle of the HUD while there is one`() {
        TestServer(files + ("modules/t/init.lua" to LuaChecks.command("event.player:send_actionbar(\"AB\")"))).use { server ->
            val alex = server.platform.players.add("Alex")
            // Without a HUD it's sent as it is.
            server.platform.commands.run(alex, "run")
            assertEquals("AB", alex.actionbars.last())
            server.runtime.session.huds.change(alex.ref.uuid) {
                it.copy(
                    bars = mapOf(
                        "ui/health" to dev.netherforge.format.resourcepack.BarFill(5.0, 10.0)
                    )
                )
            }
            server.platform.commands.run(alex, "run")
            server.tick(1)
            val line = alex.actionbars.last()
            assertTrue(line.contains("AB<reset>") && line.contains("<font:test:ui/hud>"), line)
            // It goes again after a few seconds; the HUD stays.
            server.tick(Huds.MESSAGE.toInt() + 1)
            assertTrue("AB" !in alex.actionbars.last() && "<font:test:ui/hud>" in alex.actionbars.last())
        }
    }

    @Test
    fun `fewer segments, and several bars filled as one`() {
        val result = LuaChecks.run(
            """
            local hud = nf.players.get("Alex"):hud()
            check("four of ten", hud:fill_bar("ui/health", 3, 4, { segments = 4 }), true)
            check("its segments", hud:bar("ui/health").segments, 4)
            check("all by default", hud:fill_bar("ui/life2", 1, 2) and hud:bar("ui/life2").segments, 10)
            fails("too many", function() hud:fill_bar("ui/health", 1, 2, { segments = 11 }) end, "1 to 10")

            -- 20 a segment: 260 needs 13, ten in the first bar and three in the second.
            check("drawn", hud:fill_bars({ "ui/health", "ui/life2" }, 230, 260, 20), 13)
            local first, second = hud:bar("ui/health"), hud:bar("ui/life2")
            check("the first, full", first.value == 200 and first.max == 200 and first.segments, 10)
            check("the rest", second.value == 30 and second.max == 60 and second.segments, 3)
            -- 100: five segments, so the second bar goes.
            check("fewer", hud:fill_bars({ "ui/health", "ui/life2" }, 100, 100, 20), 5)
            check("removed", hud:bar("ui/life2"), nil)
            check("as far as the bars go", hud:fill_bars({ "ui/health" }, 1, 1000, 20), 10)
            fails("per segment", function() hud:fill_bars({ "ui/health" }, 1, 2, 0) end, "per_segment")
            fails("not a bar", function() hud:fill_bars({ "ui/effects" }, 1, 2, 1) end, "not a bar")
            """.trimIndent(),
            files,
            setup = { it.platform.players.add("Alex") }
        )
        assertEquals(listOf("done"), result)
    }
}
