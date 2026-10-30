package dev.netherforge.plugin.centity

import dev.netherforge.format.centity.TextDisplay
import dev.netherforge.format.math.Vec3
import dev.netherforge.format.resourcepack.DisplayGlyphs
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.Location
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Rect and image displays, anchored text, and `nf.images`: drawn as text displays fitted exactly to their box. */
class CentityImageTest {
    private val at = Location("world", 10.0, 64.0, 10.0)

    private val page = """
        {
          "nodes": {
            "panel": { "display": { "type": "rect", "color": "#80112233", "size": [8.4, 4.8], "anchor": "top_left" } },
            "thumb": { "display": { "type": "image", "width": 4, "height": 2, "size": [2, 1] } },
            "label": { "display": { "type": "text", "text": "AB", "billboard": "fixed", "anchor": "top_left" } }
          },
          "script": { "file": "script.lua" }
        }
    """

    private val font = """{ "minecraft": "26.3", "advances": { "65": 6, "66": 5 } }"""

    private fun server(script: String) = TestServer(
        mapOf(
            "centities/page/centity.json" to page,
            "centities/page/script.lua" to script,
            "fonts/default.json" to font
        )
    )

    private fun TestServer.spawn() = assertNotNull(runtime.session.centities.spawn("page", at))

    /** Where the display's own point [x], [y] (in blocks, its own space) lands relative to the anchor. */
    private fun TestServer.lands(instance: Instance, node: String, x: Double, y: Double): Vec3 =
        platform.entities.display(instance.id, node).pose!!.matrix.transformPosition(Vec3(x, y, 0.0))

    private fun close(expected: Vec3, actual: Vec3) {
        assertEquals(expected.x, actual.x, 1e-9, "x of $actual")
        assertEquals(expected.y, actual.y, 1e-9, "y of $actual")
    }

    @Test
    fun `a rect is a text display's background, fitted to its size by its anchor`() {
        server("").use { server ->
            val page = server.spawn()
            server.tick()
            val panel = server.platform.entities.display(page.id, "panel")
            val shown = panel.display as TextDisplay
            assertEquals(DisplayGlyphs.RECT_TEXT, shown.text)
            assertEquals("#80112233", shown.background)
            // The background's corners land on the box: its top left at the origin, 8.4 across and 4.8 down.
            val box = DisplayGlyphs.RECT_BOX
            close(Vec3(0.0, 0.0, 0.0), server.lands(page, "panel", box.minX, box.maxY))
            close(Vec3(8.4, -4.8, 0.0), server.lands(page, "panel", box.maxX, box.minY))
        }
    }

    @Test
    fun `an anchored text's top left lines up with a rect anchored at the same point`() {
        server("").use { server ->
            val page = server.spawn()
            server.tick()
            val label = server.platform.entities.display(page.id, "label").display as TextDisplay
            // A and B: 6 + 5 pixels, one line.
            val box = DisplayGlyphs.textBox(11, 1)
            close(Vec3(0.0, 0.0, 0.0), server.lands(page, "label", box.minX, box.maxY))
            close(Vec3(11 * 0.025, -0.25, 0.0), server.lands(page, "label", box.maxX, box.minY))
            assertTrue(label.lineWidth!! >= 11)
        }
    }

    @Test
    fun `an image draws its pixels as runs of pixel glyphs, and fills its box`() {
        server(
            """
            local image = nf.images.create(4, 2, {
              "#FF0000", "#FF0000", "#FF0000", "#00FF00",
              "#00000000", "#0000FF", "#0000FF", "#0000FF",
            })
            log(image:width(), image:height(), image:pixel(3, 0), tostring(image:pixel(0, 1)), tostring(image:pixel(4, 0)))
            log(this:node("thumb"):set_image(image), this:node("thumb"):display_kind())
            """
        ).use { server ->
            val page = server.spawn()
            server.tick()
            assertEquals(listOf("4\t2\t#00FF00\t#00000000\tnil", "true\timage"), server.logs)
            val shown = server.platform.entities.display(page.id, "thumb").display as TextDisplay
            val back = dev.netherforge.format.resourcepack.PackFonts.offset(-1)
            val one = DisplayGlyphs.runChar(0) + back
            val two = DisplayGlyphs.runChar(1) + back
            val firstRow = "<color:#ff0000>$two$one</color><color:#00ff00>$one</color>"
            val secondRow = dev.netherforge.format.resourcepack.PackFonts.offset(10) + "<color:#0000ff>$two$one</color>"
            assertEquals("$firstRow\n$secondRow", shown.text)
            // What a platform draws: the same, run by run.
            val runs = assertNotNull(shown.runs)
            assertEquals(listOf(0xFF0000, 0x00FF00, null, null, 0x0000FF), runs.map { it.rgb })
            // Centred on the origin, 2 by 1 blocks.
            val box = DisplayGlyphs.imageBox(4, 2)
            close(Vec3(-1.0, 0.5, 0.0), server.lands(page, "thumb", box.minX, box.maxY))
            close(Vec3(1.0, -0.5, 0.0), server.lands(page, "thumb", box.maxX, box.minY))
        }
    }

    @Test
    fun `showing the same pixels again sends nothing`() {
        server(
            """
            local red = nf.images.fill(4, 2, "#FF0000")
            local thumb = this:node("thumb")
            thumb:set_image(red)
            nf.after(2, function() thumb:set_image(nf.images.fill(4, 2, "#FF0000")) end)
            """
        ).use { server ->
            val page = server.spawn()
            server.tick()
            val display = server.platform.entities.display(page.id, "thumb")
            val first = display.display
            repeat(3) { server.tick() }
            // Equal pixels make an equal display, so nothing changed and nothing was sent.
            assertTrue(first === display.display)
        }
    }

    @Test
    fun `images resize, crop, encode and decode`() {
        server(
            """
            local image = nf.images.create(2, 2, { "#FF0000", "#FF0000", "#0000FF", "#0000FF" })
            local small = image:resized(1, 2)
            log(small:width(), small:height(), small:pixel(0, 0), small:pixel(0, 1))
            local half = image:resized(2, 1)
            log(half:pixel(0, 0))
            local corner = assert(image:cropped(0, 1, 2, 1))
            log(corner:pixel(1, 0), tostring(image:cropped(1, 1, 2, 2)))
            local again = assert(nf.images.decode(image:encode()))
            log(again:width(), again:pixel(0, 1), tostring(nf.images.decode("not an image")))
            local packed = nf.images.create(1, 1, "AAEC")
            log(packed:pixel(0, 0))
            """
        ).use { server ->
            server.spawn()
            assertEquals(
                listOf("1\t2\t#FF0000\t#0000FF", "#800080", "#0000FF\tnil", "2\t#0000FF\tnil", "#000102"),
                server.logs
            )
        }
    }

    @Test
    fun `text is measured in blocks`() {
        server(
            """
            local size = assert(nf.text.measure("AB\nA", { scale = 2 }))
            log(size.width, size.height, tostring(nf.text.measure("C")))
            """
        ).use { server ->
            server.spawn()
            assertEquals(listOf("0.55\t1.0\tnil"), server.logs)
        }
    }

    @Test
    fun `a rect is recoloured and resized in place`() {
        server(
            """
            local panel = this:node("panel")
            log(panel:display_color(), tostring(panel:display_size()), panel:display_anchor())
            panel:set_display_color("#00FF00")
            panel:set_display_size(vec3(2, 1, 0))
            panel:set_display_anchor("center")
            log(panel:display_color(), tostring(panel:display_size()), panel:display_anchor())
            """
        ).use { server ->
            val page = server.spawn()
            server.tick()
            assertEquals(listOf("#80112233\tvec3(8.4, 4.8, 0)\ttop_left", "#00FF00\tvec3(2, 1, 0)\tcenter"), server.logs)
            assertEquals("#FF00FF00", (server.platform.entities.display(page.id, "panel").display as TextDisplay).background)
            val box = DisplayGlyphs.RECT_BOX
            close(Vec3(-1.0, 0.5, 0.0), server.lands(page, "panel", box.minX, box.maxY))
        }
    }

    @Test
    fun `an image costs what the connection sends for it, compressed, not its text's length`() {
        // A photo: a gradient with grain, hardly two neighbouring pixels alike.
        var seed = 11
        val argb = IntArray(128 * 72) { i ->
            seed = seed * 1103515245 + 12345
            val grain = (seed ushr 16 and 15) - 8
            val shade = (i / 128 * 3 + grain).coerceIn(0, 255)
            0xFF000000.toInt() or (shade shl 16) or ((shade + 40).coerceAtMost(255) shl 8) or 200
        }
        val photo = dev.netherforge.format.image.Pixels(128, 72, argb)
        val text = DisplayGlyphs.imageText(128, 72, photo)
        val wire = DisplayDrawing.wireBytes(text)
        assertTrue(text.length > 100_000, "a photo is a lot of text (${text.length})")
        assertTrue(wire < text.length / 5, "but compresses to a fraction of it ($wire of ${text.length})")
        assertTrue(wire < Centities.IMAGE_BYTES_PER_TICK, "so one tick sends more than one")
    }

    @Test
    fun `changed images past a tick's budget wait for the next tick`() {
        val many = (1..8).joinToString(",\n") { """"img$it": { "display": { "type": "image", "width": 128, "height": 128 } }""" }
        val centity = """{ "nodes": { $many }, "script": { "file": "script.lua" } }"""
        // Every pixel a colour of its own, at random: the most text an image can be, and it barely compresses.
        val bytes = ByteArray(128 * 128 * 3).also { java.util.Random(42).nextBytes(it) }
        val packed = java.util.Base64.getEncoder().encodeToString(bytes)
        val script = """
            local noisy = nf.images.create(128, 128, "$packed")
            for i = 1, 8 do this:node("img" .. i):set_image(noisy) end
        """
        TestServer(mapOf("centities/wall/centity.json" to centity, "centities/wall/script.lua" to script)).use { server ->
            val wall = assertNotNull(server.runtime.session.centities.spawn("wall", at))
            fun drawn() = (1..8).count {
                (server.platform.entities.display(wall.id, "img$it").display as TextDisplay).text.contains("<color")
            }
            server.tick()
            val first = drawn()
            assertTrue(first in 1..7, "some, not all, the first tick ($first)")
            repeat(10) { server.tick() }
            assertEquals(8, drawn())
        }
    }
}
