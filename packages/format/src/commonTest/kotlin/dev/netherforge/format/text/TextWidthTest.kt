package dev.netherforge.format.text

import dev.netherforge.format.ProblemSink
import dev.netherforge.format.menu.MenuType
import dev.netherforge.format.project.DefaultFontKind
import dev.netherforge.format.project.MapProjectSource
import dev.netherforge.format.project.Projects
import dev.netherforge.format.resourcepack.PackFonts
import dev.netherforge.format.testManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextWidthTest {

    /** A made-up font: `a`–`z` 6 wide, `i` 2, space 4, `<` 5, `\` 6. Nothing else. */
    private val font = DefaultFontFile(
        minecraft = "26.3",
        advances = ('a'..'z').associate { it.code.toString() to 6 } +
            mapOf("i".code() to 2, " ".code() to 4, "<".code() to 5, ">".code() to 5, "\\".code() to 6, "/".code() to 6)
    )
    private val width = TextWidth(font) { if (it == "ui:coin") 9 else null }

    private fun String.code() = this[0].code.toString()

    @Test
    fun aGlyphsBareCharacterMeasuresAsTheGlyph() {
        // `nf.text.glyph`'s character, which the text's colour tints, measures as its tag does.
        val glyphs = TextWidth(font, glyphChar = { if (it == 0xE000) 9 else null }) { if (it == "ui:coin") 9 else null }
        assertEquals(glyphs.measure("<glyph:ui:coin>"), glyphs.measure("<#ff0000>\uE000"))
        assertEquals(9 + 6, glyphs.measure("\uE000a"))
        // Bold like any character, as the game draws it; a character no glyph has still can't be measured.
        assertEquals(10, glyphs.measure("<b>\uE000</b>"))
        assertNull(glyphs.measure("\uE001"))
        assertNull(width.measure("\uE000"), "without the lookup it's a character the font doesn't cover")
    }

    @Test
    fun plainTextIsTheSumOfItsAdvances() {
        assertEquals(0, width.measure(""))
        assertEquals(6 + 2 + 4 + 6, width.measure("ai b"))
    }

    @Test
    fun styleTagsDrawNothingAndBoldAddsAPixelPerCharacter() {
        assertEquals(12, width.measure("<gold>ab</gold>"))
        assertEquals(12, width.measure("<#ff8800><italic>ab"))
        assertEquals(14, width.measure("<bold>ab</bold>"))
        assertEquals(14 + 6, width.measure("<b>ab</b>a"))
        // Bold stops at its closing tag, at a negation, at <reset>, and when an enclosing tag closes.
        assertEquals(7 + 6, width.measure("<bold:true>a<bold:false>a"))
        assertEquals(7 + 6, width.measure("<b>a<!b>a"))
        assertEquals(7 + 6, width.measure("<b><red>a<reset>a"))
        assertEquals(7 + 6, width.measure("<red><b>a</red>a"))
        // A space is a character like any other.
        assertEquals(5, width.measure("<b> </b>"))
    }

    /**
     * Where the server's exact measure and the editor's layout ([TextMetrics]) part ways, on
     * purpose (see [TextMetrics]): what the server can't know is null, what the preview can't
     * know it estimates or draws as the game would. Where both know everything they agree.
     */
    @Test
    fun theServersMeasureIsExactWhereThePreviewEstimates() {
        val preview = TextMetrics(glyph = { if (it == "ui:coin") 9 else null }) { code -> font.advance(code) }
        for (text in listOf("ai b", "<b>ab</b>a", "<glyph:ui:coin> a", "<red>a<newline>bb")) {
            assertEquals(preview.lineWidths(text, lineWidth = 0).max(), width.measure(text), text)
        }
        // A glyph no pack has: the game draws nothing, but the server can't promise what it isn't told.
        assertEquals(listOf(6), preview.lineWidths("<glyph:ui:nope>a"))
        assertNull(width.measure("<glyph:ui:nope>a"))
        // A character the font doesn't cover: estimated for the preview, unknown to the server.
        assertEquals(listOf(6), preview.lineWidths("Z"))
        assertNull(width.measure("Z"))
        // A tag MiniMessage doesn't know is drawn as written; the preview's pass reads it as a tag.
        assertEquals(5 + 6 * 4 + 5 + 6, width.measure("<nope>a"))
        assertEquals(listOf(6), preview.lineWidths("<nope>a"))
    }

    @Test
    fun glyphTagsAreTheirGlyphsAdvance() {
        assertEquals(9 + 4 + 6, width.measure("<glyph:ui:coin> a"))
        // A glyph is never drawn bold.
        assertEquals(9, width.measure("<b><glyph:'ui:coin'>"))
        // A glyph whose advance isn't known can't be measured.
        assertNull(width.measure("<glyph:ui:nope>"))
    }

    @Test
    fun anythingNotExactlyKnownIsNull() {
        // A character the font file doesn't cover (here: capitals; really: unifont's).
        assertNull(width.measure("Hi"))
        // Text only the client resolves.
        assertNull(width.measure("<lang:block.minecraft.stone>"))
        assertNull(width.measure("<key:key.jump>"))
        // Another font.
        assertNull(width.measure("<font:uniform>a</font>"))
        assertEquals(6, width.measure("<font:minecraft:default>a</font>"))
    }

    @Test
    fun linesMeasureAsTheWidestOne() {
        assertEquals(12, width.measure("a\naa<newline>a<br>i"))
    }

    @Test
    fun whatMiniMessageDoesntParseIsText() {
        // An escaped tag, and a tag MiniMessage doesn't know, are drawn as written.
        assertEquals(5 + 6 + 5, width.measure("\\<a>"))
        assertEquals(6 + 6 + 2, width.measure("a\\\\i"))
        assertEquals(5 + 6 + 6 + 6 + 5, width.measure("<foo>"))
        // Not a tag at all.
        assertEquals(5 + 4 + 6, width.measure("< a"))
    }

    @Test
    fun theFontFileIsCanonicalAndChecked() {
        val text = DefaultFontKind.write(DefaultFontFile(minecraft = "26.3", advances = mapOf("100" to 6, "32" to 4, "9" to 0)))
        // Code points in numeric order.
        assertTrue(text.indexOf("\"9\"") < text.indexOf("\"32\"") && text.indexOf("\"32\"") < text.indexOf("\"100\""), text)
        assertTrue("\"\$schema\": \"${DefaultFontFile.SCHEMA}\"" in text, text)

        val sink = ProblemSink(DefaultFontFile.FILE)
        DefaultFontValidator.validate(DefaultFontFile(minecraft = "26.4", advances = mapOf("065" to 6, "x" to 1, "66" to -1)), sink, "26.3")
        assertEquals(
            listOf(
                "font.stale" to "$.minecraft",
                "font.code-point" to "$.advances[\"065\"]",
                "font.code-point" to "$.advances.x",
                "font.advance" to "$.advances[\"66\"]"
            ),
            sink.problems.map { it.code to it.path }
        )
    }

    @Test
    fun aStaleFontFileIsWarnedAboutAndAMissingOneIsnt() {
        fun load(font: String?) = Projects.load(
            MapProjectSource(
                buildMap {
                    put(
                        "netherforge.json",
                        testManifest()
                    )
                    if (font != null) put(DefaultFontFile.FILE, font)
                }
            )
        )
        val missing = load(null)
        assertEquals(emptyList(), missing.problems)
        assertNull(missing.defaultFont)

        val current = load("""{ "minecraft": "26.3", "advances": { "97": 6 } }""")
        assertEquals(emptyList(), current.problems)
        assertEquals(6, assertNotNull(current.defaultFont).advance('a'.code))

        val stale = load("""{ "minecraft": "26.2", "advances": {} }""")
        assertEquals(listOf(DefaultFontFile.FILE to "font.stale"), stale.problems.map { it.file to it.code })
    }

    @Test
    fun aSkinnedCentredTitleNeedsACurrentFontFile() {
        fun check(type: MenuType, skin: String?, title: String?, font: DefaultFontFile?): List<String?> {
            val sink = ProblemSink("menus/shop/menu.json")
            DefaultFontValidator.checkCentredTitle(type, skin, title, font, "26.3", sink, "$.title")
            return sink.problems.map { it.code }
        }
        assertEquals(listOf("font.needed"), check(MenuType.DISPENSER, "ui:shop", "Shop", null))
        assertEquals(listOf("font.needed"), check(MenuType.CRAFTER, "ui:shop", "Shop", font.copy(minecraft = "26.2")))
        assertEquals(emptyList(), check(MenuType.DISPENSER, "ui:shop", "Shop", font))
        // Nothing to place from the title's width.
        assertEquals(emptyList(), check(MenuType.DROPPER, null, "Shop", null))
        assertEquals(emptyList(), check(MenuType.DISPENSER, "ui:shop", "", null))
        assertEquals(emptyList(), check(MenuType.CHEST, "ui:shop", "Shop", null))
    }

    @Test
    fun aSpaceMovesByItsPixelsBoldOrNot() {
        assertEquals(6 + 40 + 6, width.measure("a<space:40>a"))
        assertEquals(9 - 3 + 9, width.measure("<glyph:ui:coin><space:-3><glyph:ui:coin>"))
        assertEquals(7 + 10, width.measure("<b>a<space:10></b>"))
        // Held to the largest move; one that doesn't read moves nothing.
        assertEquals(PackFonts.MAX_OFFSET, width.measure("<space:99999>"))
        assertEquals(6, width.measure("a<space:x>"))
        // The pack's space characters, written by hand (nf.text.space), are the moves they make.
        assertEquals(6 + 37, width.measure("a" + PackFonts.offset(37)))
    }

    @Test
    fun whatAnAtHoldsTakesNoWidth() {
        assertEquals(6 * 4, width.measure("<at:-30><glyph:ui:coin></at>abcd"))
        assertEquals(6, width.measure("<at:5>bbb</at>a"))
        // Nested, or closed by what encloses it, or never closed.
        assertEquals(6, width.measure("<at:1>b<at:2>b</at>b</at>a"))
        assertEquals(6, width.measure("<red><at:1>bb</red>a"))
        assertEquals(6, width.measure("a<at:1>bb"))
        // One that doesn't read only scopes what's inside it, which then takes its width.
        assertEquals(18, width.measure("<at:what>bb</at>a"))
        // Unmeasurable inside is unmeasurable.
        assertNull(width.measure("<at:1>\u00e9</at>a"))
    }

    @Test
    fun anAtBecomesTheMovesAroundIt() {
        // From the cursor: there, and back by the inside's width.
        assertEquals("<space:-30><at:-30><glyph:ui:coin><space:21></at>King", width.layout("<at:-30><glyph:ui:coin></at>King").text)
        // 3 left of the line's centre (half of 4 letters = 12), its right edge there.
        assertEquals(
            "ab<space:-12><at:-3:from=center:align=right><glyph:ui:coin><space:3></at>cd",
            width.layout("ab<at:-3:from=center:align=right><glyph:ui:coin></at>cd").text
        )
        // From the end and the start, centred on N.
        assertEquals("a<space:-6><at:0:from=end:align=center>bb<space:-6></at>", width.layout("a<at:0:from=end:align=center>bb</at>").text)
        assertEquals("aa<space:-12><at:0:from=start>b<space:6></at>", width.layout("aa<at:0:from=start>b</at>").text)
        // Bold inside counts; past the largest move takes more than one space.
        assertEquals("<b><space:1><at:1>a<space:-8></at></b>", width.layout("<b><at:1>a</at></b>").text)
        assertEquals("<space:2047><space:953><at:3000>a<space:-2047><space:-959></at>", width.layout("<at:3000>a</at>").text)
        // Closed by what encloses it: the move back goes where that closes it.
        assertEquals("<red><space:4><at:4>a<space:-10></red>z", width.layout("<red><at:4>a</red>z").text)
        // Text without one is as it was.
        val plain = "<red>abc"
        assertTrue(width.layout(plain).text === plain)
    }

    @Test
    fun anAtThatCantBePlacedIsLeftToDrawInLine() {
        val unknownInside = width.layout("<at:4><glyph:ui:missing></at>a")
        assertEquals("<at:4><glyph:ui:missing></at>a", unknownInside.text)
        assertEquals(listOf("<at:4>"), unknownInside.unplaced)
        // From the centre of a line that can't be measured; from the cursor it still works.
        assertEquals(listOf("<at:0:from=center>"), width.layout("<at:0:from=center>a</at><lang:block.stone>").unplaced)
        assertEquals("<space:3><at:3>a<space:-9></at><lang:x>", width.layout("<at:3>a</at><lang:x>").text)
        // Spread over two lines.
        assertEquals(listOf("<at:1>"), width.layout("<at:1>a<br>b</at>").unplaced)
    }
}
