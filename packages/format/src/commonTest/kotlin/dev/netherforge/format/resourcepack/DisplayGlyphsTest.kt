package dev.netherforge.format.resourcepack

import dev.netherforge.format.centity.Anchor
import dev.netherforge.format.image.Pixels
import dev.netherforge.format.math.Vec3
import dev.netherforge.format.text.TextMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DisplayGlyphsTest {
    private val back = PackFonts.offset(-1)

    private fun close(expected: Vec3, actual: Vec3) {
        assertEquals(expected.x, actual.x, 1e-9, "x of $actual")
        assertEquals(expected.y, actual.y, 1e-9, "y of $actual")
    }

    @Test
    fun aRunIsAGlyphPerSetBitEachFollowedByAStepBack() {
        val red = 0xFFFF0000.toInt()
        val text = DisplayGlyphs.imageText(5, 1, Pixels(5, 1, IntArray(5) { red }))
        val glyphs = "${DisplayGlyphs.runChar(2)}$back${DisplayGlyphs.runChar(0)}$back"
        assertEquals("<color:#ff0000>$glyphs</color>", text)
    }

    @Test
    fun runsLongerThanTheBiggestGlyphRepeatIt() {
        val text = DisplayGlyphs.imageText(256 + 3, 1, Pixels(259, 1, IntArray(259) { -1 }))
        val big = "${DisplayGlyphs.runChar(7)}$back"
        assertEquals("<color:#ffffff>$big$big${DisplayGlyphs.runChar(1)}$back${DisplayGlyphs.runChar(0)}$back</color>", text)
    }

    @Test
    fun anImagesRunsSayWhatItsTextSaysAndEachIsShort() {
        // A photo: hardly two pixels alike, so its text runs to hundreds of kilobytes, too long to be one
        // piece of the game's text (65535 bytes). Its runs are what's drawn: every one short.
        var seed = 7
        val noise = IntArray(128 * 72) {
            seed = seed * 1103515245 + 12345
            0xFF000000.toInt() or (seed ushr 8 and 0xFFFFFF)
        }
        val photo = Pixels(128, 72, noise)
        val runs = DisplayGlyphs.imageRuns(128, 72, photo)
        val text = DisplayGlyphs.imageText(128, 72, photo)
        val written = runs.joinToString("") { run ->
            val rgb = run.rgb
            if (rgb == null) run.text else "<color:#${rgb.toString(16).padStart(6, '0')}>${run.text}</color>"
        }
        assertEquals(text, written)
        assertTrue(text.encodeToByteArray().size > 65535, "the whole text is past one piece's limit")
        assertTrue(runs.all { it.text.encodeToByteArray().size < 1024 }, "every run is a short piece")
        assertEquals(71, runs.count { it.text == "\n" }, "a line break between rows")
        // A blank image is moves alone.
        assertTrue(DisplayGlyphs.imageRuns(3, 2, null).all { it.rgb == null })
    }

    @Test
    fun transparentPixelsAndABlankImageAreMoves() {
        assertEquals(PackFonts.offset(30) + "\n" + PackFonts.offset(30), DisplayGlyphs.imageText(3, 2, null))
        // Below half alpha is left out; a smaller picture leaves the rest blank.
        val text = DisplayGlyphs.imageText(3, 1, Pixels(1, 1, intArrayOf(0x7FFFFFFF)))
        assertEquals(PackFonts.offset(30), text)
    }

    @Test
    fun aFitPutsTheBoxWhereTheAnchorSays() {
        val box = DisplayGlyphs.imageBox(96, 54)
        // The text is 10 pixels a picture pixel each way: square.
        assertEquals(96 * 10 * TextMetrics.BLOCKS_PER_PIXEL, box.width, 1e-12)
        assertEquals(54 * 10 * TextMetrics.BLOCKS_PER_PIXEL, box.height, 1e-12)
        val fit = DisplayGlyphs.fit(box, Anchor.TOP_LEFT, 8.4, 4.8)
        close(Vec3(0.0, 0.0, 0.0), fit.transformPosition(Vec3(box.minX, box.maxY, 0.0)))
        close(Vec3(8.4, -4.8, 0.0), fit.transformPosition(Vec3(box.maxX, box.minY, 0.0)))
        val centred = DisplayGlyphs.fit(DisplayGlyphs.RECT_BOX, Anchor.CENTER, 2.0, 1.0)
        close(Vec3(-1.0, -0.5, 0.0), centred.transformPosition(Vec3(DisplayGlyphs.RECT_BOX.minX, DisplayGlyphs.RECT_BOX.minY, 0.0)))
        // Without a size, the box keeps its own.
        val text = DisplayGlyphs.textBox(40, 2)
        val right = DisplayGlyphs.fit(text, Anchor.BOTTOM_RIGHT)
        close(Vec3(0.0, 0.0, 0.0), right.transformPosition(Vec3(text.maxX, text.minY, 0.0)))
        close(Vec3(-1.0, 0.5, 0.0), right.transformPosition(Vec3(text.minX, text.maxY, 0.0)))
    }

    @Test
    fun theBoxesFollowTheGamesTextLayout() {
        // A rect's background: one pixel left of its 8-pixel move to its end and a line tall, centred one pixel right.
        assertEquals(9 * TextMetrics.BLOCKS_PER_PIXEL, DisplayGlyphs.RECT_BOX.width, 1e-12)
        assertEquals(-4 * TextMetrics.BLOCKS_PER_PIXEL, DisplayGlyphs.RECT_BOX.minX, 1e-12)
        // An image's pixels: from the first line's top, one pixel lower than the background's bottom.
        val image = DisplayGlyphs.imageBox(1, 1)
        assertEquals(-TextMetrics.BLOCKS_PER_PIXEL, image.minY, 1e-12)
        assertEquals(9 * TextMetrics.BLOCKS_PER_PIXEL, image.maxY, 1e-12)
        assertEquals(-4 * TextMetrics.BLOCKS_PER_PIXEL, image.minX, 1e-12)
        // Text's lines are where an image's rows are: the first line's top a pixel inside the background.
        val text = DisplayGlyphs.textBox(10, 1)
        assertEquals(image.maxY, text.maxY, 1e-12)
        assertEquals(image.minY, text.minY, 1e-12)
    }
}
