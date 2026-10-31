package dev.netherforge.format.resourcepack

import dev.netherforge.format.project.ImageInfo
import dev.netherforge.format.text.TextWidth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PackFontsTest {
    /** [picture]'s characters' own advances, by code point: what the server measures bare glyph characters by. */
    private fun charAdvances(picture: PackFonts.Picture): Map<Int, Int> = picture.cells.map { it.code }.zip(picture.cellAdvances!!).toMap()

    @Test
    fun aPicturesTextMeasuresAsItsAdvanceFromItsCharactersAlone() {
        var next = 1
        // A banner too wide for a font sheet: cut in four, each piece its own character, with moves between.
        val wide = PackFonts.picture(PackFonts.charAt(0), ImageInfo(936, 256, 936), 256) { PackFonts.charAt(next++) }
        assertEquals(4, wide.cells.length)
        assertEquals(4, wide.cellAdvances!!.size)
        val advances = charAdvances(wide)
        val measure = TextWidth({ null }, glyphChar = { advances[it] })
        assertEquals(wide.advance, measure.measure(wide.text))

        // One that fits is one character, whose own advance is the picture's.
        val small = PackFonts.picture(PackFonts.charAt(9), ImageInfo(16, 16, 14), 8) { null }
        assertEquals(listOf(small.advance!!), small.cellAdvances)
        assertEquals(small.advance, TextWidth({ null }, glyphChar = { charAdvances(small)[it] }).measure(small.text))
    }

    @Test
    fun aPictureWhosePixelsAreUnknownHasNoAdvances() {
        val unknown = PackFonts.picture(PackFonts.charAt(0), null, 8) { null }
        assertNull(unknown.advance)
        assertNull(unknown.cellAdvances)
    }
}
