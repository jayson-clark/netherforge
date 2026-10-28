package dev.netherforge.format.text

import dev.netherforge.format.centity.Billboard
import dev.netherforge.format.centity.TextDisplay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TextMetricsTest {

    /** A tiny font: every glyph 6 wide, `i` 2, space 4. */
    private val metrics = TextMetrics { code ->
        when (code) {
            'i'.code -> 2
            ' '.code -> 4
            else -> 6
        }
    }

    @Test
    fun tagsMeasureAsNothingExceptBold() {
        assertEquals(listOf(12), metrics.lineWidths("<gold>ab</gold>"))
        assertEquals(listOf(14), metrics.lineWidths("<bold>ab</bold>"))
        // 2 + bold 3 + 2 + 6: an unknown tag balances without turning bold on.
        assertEquals(listOf(13), metrics.lineWidths("<red>i</red><b>i</b>i<unknown>a"))
    }

    @Test
    fun glyphTagsMeasureAsTheGlyphsAdvance() {
        val withGlyphs = TextMetrics(glyph = { if (it == "ui:coin") 9 else null }) { 6 }
        assertEquals(listOf(9 + 6 + 6), withGlyphs.lineWidths("<glyph:ui:coin> 2"))
        assertEquals(listOf(9), withGlyphs.lineWidths("<glyph:'ui:coin'>"))
        // A glyph no pack has draws nothing; a glyph is never bold; the tag opens nothing to close.
        assertEquals(listOf(6), withGlyphs.lineWidths("<glyph:ui:nope>a"))
        assertEquals(listOf(9 + 7), withGlyphs.lineWidths("<b><glyph:ui:coin>a</b>"))
        // Where it is in the source, so a preview draws it on the right line.
        val lines = withGlyphs.lines("aa <glyph:ui:coin>", 20)
        assertEquals(listOf(12, 9), lines.map { it.width })
        assertEquals(3, lines[1].start)
    }

    @Test
    fun glyphTagsFollowMiniMessageArguments() {
        assertEquals("ui:coin", GlyphTags.reference("glyph:ui:coin"))
        assertEquals("ui:coin", GlyphTags.reference("glyph:\"ui:coin\""))
        assertEquals("", GlyphTags.reference("glyph"))
        assertNull(GlyphTags.reference("gold"))
        // An escaped tag is text.
        assertEquals(listOf(GlyphTags.Found("ui:coin", 18, 33)), GlyphTags.find("<gray>\\<glyph:a:b><glyph:ui:coin>"))
        assertEquals(listOf("a:b"), GlyphTags.find("<glyph:a:b> and \\<glyph:c:d>").map { it.reference })
    }

    @Test
    fun breaksAtNewlinesAndTags() {
        assertEquals(listOf(6, 6, 6), metrics.lineWidths("a\nb<newline>c"))
    }

    @Test
    fun wrapsAtTheLastSpaceThenMidWord() {
        // "aa aa" is 6+6+4+6+6 = 28: at 20 it breaks at the space.
        assertEquals(listOf(12, 12), metrics.lineWidths("aa aa", 20))
        // One long word breaks where it overflows.
        assertEquals(listOf(18, 12), metrics.lineWidths("aaaaa", 20))
    }

    @Test
    fun linesSayWhichPartOfTheSourceTheyShow() {
        fun shown(text: String, width: Int) = metrics.lines(text, width).map { text.substring(it.start, it.end) to it.width }
        assertEquals(listOf("<red>aa" to 12, "aa</red>" to 12), shown("<red>aa aa</red>", 20))
        assertEquals(listOf("aaa" to 18, "aa" to 12), shown("aaaaa", 20))
        assertEquals(listOf("a" to 6, "" to 0, "b" to 6), shown("a\n<br>b", 0))
        assertEquals(listOf("" to 0), shown("", 200))
    }

    @Test
    fun boxOnlyForFixedText() {
        assertNull(metrics.box(TextDisplay("hi")))
        val box = metrics.box(TextDisplay("ab", billboard = Billboard.FIXED))!!
        assertEquals(-0.15, box.min.x, 1e-9)
        assertEquals(0.175, box.max.x, 1e-9)
        assertEquals(0.25, box.max.y, 1e-9)
    }
}
