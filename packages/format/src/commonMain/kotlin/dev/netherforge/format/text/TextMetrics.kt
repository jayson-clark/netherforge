package dev.netherforge.format.text

import dev.netherforge.format.centity.Billboard
import dev.netherforge.format.centity.TextDisplay
import dev.netherforge.format.game.Box
import dev.netherforge.format.math.Vec3
import dev.netherforge.format.resourcepack.PackFonts

/**
 * How Minecraft lays out MiniMessage text, in font pixels (1/40 of a block for
 * a text display).
 *
 * Glyph widths come from the player's own client (the default font's bitmaps,
 * read by the editor when it imports a version), passed in as [advance]. So
 * this only runs where client data exists: the editor's previews and its
 * "fit to display" for text hitboxes. The plugin never measures text with it.
 *
 * Why there are two measurers: this one and [TextWidth] answer different
 * questions and must disagree where they do. This is a layout for drawing a
 * preview: it always has an answer (a character the font doesn't cover gets
 * an estimate, a glyph no pack has draws nothing, as the game draws it), it
 * wraps, and every line keeps its range of the source, all over the same
 * [MiniMessagePass] pieces the editor draws. [TextWidth] is the server's
 * `nf.text.width`: one number that's exact or null, never an estimate, so
 * anything it can't know (an uncovered character, an unknown glyph advance, a
 * client-only insert, another font) makes it null, and it reads unknown tags
 * as MiniMessage does (as text). Folding one into the other would make the
 * server guess or the preview go blank.
 */
class TextMetrics(
    /**
     * A pack glyph's advance by reference (`ui:coin`), for `<glyph:ui:coin>`
     * tags; null for a glyph the packs don't have, which the game draws as
     * nothing. First so [advance] can stay a trailing lambda.
     */
    private val glyph: (reference: String) -> Int? = { null },
    /** A glyph's advance in pixels, including the gap after it; null when unknown. */
    private val advance: (codePoint: Int) -> Int?
) {

    /**
     * Width of every rendered line of [text], wrapped greedily at [lineWidth]
     * (at the last space that fits, mid-word if one word doesn't). Tags measure
     * as nothing except `<bold>`, which costs a pixel per glyph,
     * `<newline>`/`<br>`, `<glyph:pack:key>`, which is that glyph's
     * advance, and `<space:N>`, which is N; what an `<at>` holds is nothing.
     * Always at least one line.
     */
    fun lineWidths(text: String, lineWidth: Int = TextDisplay.DEFAULT_LINE_WIDTH): List<Int> = lines(text, lineWidth).map { it.width }

    /**
     * The rendered lines of [text], as [lineWidths] wraps them, each with the
     * range of [text] it shows (`start` inclusive, `end` exclusive, tags
     * included), so a preview can draw exactly the lines the game will.
     */
    fun lines(text: String, lineWidth: Int = TextDisplay.DEFAULT_LINE_WIDTH): List<Line> {
        val out = mutableListOf<Line>()
        for (line in measure(text)) wrap(line, lineWidth, out)
        return out
    }

    /** One rendered line: its width in pixels and the part of the source it shows. */
    class Line(val width: Int, val start: Int, val end: Int)

    /**
     * The quad a `fixed` text display fills, in its node's space, or null for
     * any other billboard (those turn to face each viewer, so they have no one
     * shape). Centred across the origin and standing on it; a sliver deep on
     * the -Z side the text faces.
     */
    fun box(display: TextDisplay): Box? {
        if ((display.billboard ?: TextDisplay.DEFAULT_BILLBOARD) != Billboard.FIXED) return null
        val widths = lineWidths(display.text, display.lineWidth ?: TextDisplay.DEFAULT_LINE_WIDTH)
        val halfWidth = widths.max() * BLOCKS_PER_PIXEL / 2
        val height = widths.size * LINE_HEIGHT * BLOCKS_PER_PIXEL
        return Box(Vec3(-halfWidth, 0.0, -SLIVER), Vec3(halfWidth + PADDING * BLOCKS_PER_PIXEL, height, 0.0))
    }

    /** A glyph's width, whether it's a space, and where it is in the source (`at` to `end`). */
    private class Glyph(val width: Int, val isSpace: Boolean, val at: Int, val end: Int)

    /** A source line before wrapping: its glyphs and where it starts and ends in the source. */
    private class SourceLine(val glyphs: MutableList<Glyph>, val start: Int, var end: Int)

    private fun width(code: Int, bold: Boolean): Int {
        val base = PackFonts.spaceAdvance(code) ?: advance(code) ?: if (isWide(code)) WIDE_ADVANCE else UNKNOWN_ADVANCE
        return if (bold) base + 1 else base
    }

    /**
     * MiniMessage flattened to glyph widths ([MiniMessagePass]'s pieces), split
     * at line breaks. What an `<at>` holds takes no width (it's drawn over the
     * line), and a `<space:N>` is N.
     */
    private fun measure(input: String): List<SourceLine> {
        val lines = mutableListOf(SourceLine(mutableListOf(), 0, input.length))
        var overlays = 0
        for (piece in MiniMessagePass.pieces(input)) {
            when (piece) {
                is MiniMessagePass.Overlay -> overlays++
                is MiniMessagePass.OverlayEnd -> overlays--
                is MiniMessagePass.Move ->
                    lines.last().glyphs.add(Glyph(if (overlays > 0) 0 else piece.px, false, piece.at, piece.end))
                is MiniMessagePass.Break -> {
                    lines.last().end = piece.at
                    lines.add(SourceLine(mutableListOf(), piece.end, input.length))
                }
                // A pack glyph is never drawn bold: it's a picture, maybe in pieces ([PackFonts.picture]).
                is MiniMessagePass.Glyph -> glyph(piece.reference)?.let { width ->
                    lines.last().glyphs.add(Glyph(if (overlays > 0) 0 else width, false, piece.at, piece.end))
                }
                is MiniMessagePass.Char -> {
                    val width = if (overlays > 0) 0 else width(piece.code, piece.style.bold == true)
                    lines.last().glyphs.add(Glyph(width, piece.code == ' '.code && overlays == 0, piece.at, piece.end))
                }
            }
        }
        return lines
    }

    private fun wrap(source: SourceLine, lineWidth: Int, out: MutableList<Line>) {
        val line = source.glyphs

        // A wrapped line shows the source from where its first glyph's tags start.
        fun emit(from: Int, to: Int, start: Int, end: Int) {
            out.add(Line(line.subList(from, to).sumOf { it.width }, start, end))
        }
        if (lineWidth <= 0) {
            emit(0, line.size, source.start, source.end)
            return
        }
        var start = 0
        var sourceStart = source.start
        var width = 0
        var lastSpace = -1
        for (index in line.indices) {
            val advance = line[index].width
            if (width + advance > lineWidth && index > start) {
                val atSpace = lastSpace >= start
                val breakAt = if (atSpace) lastSpace else index
                // A breaking space is dropped: it ends one line and starts neither.
                emit(start, breakAt, sourceStart, line[breakAt].at)
                start = if (atSpace) breakAt + 1 else breakAt
                sourceStart = if (atSpace) line[breakAt].end else line[breakAt].at
                lastSpace = -1
                width = line.subList(start, index).sumOf { it.width }
            }
            if (line[index].isSpace) lastSpace = index
            width += advance
        }
        emit(start, line.size, sourceStart, source.end)
    }

    companion object {
        /** A font pixel on a text display, in blocks. */
        const val BLOCKS_PER_PIXEL = 0.025

        /** One line: glyphs plus the gap after them. A display is exactly this times its line count. */
        const val LINE_HEIGHT = 10

        /** The background reaches this far past the text at the +X end only. */
        const val PADDING = 1

        /** Depth given to a flat text quad so it has a side to be clicked from. */
        const val SLIVER = 0.01

        /** Assumed for a glyph the imported font doesn't cover. Most Latin glyphs are 6 (5 + gap). */
        private const val UNKNOWN_ADVANCE = 6

        /** Full-width CJK glyphs. */
        private const val WIDE_ADVANCE = 9

        private fun isWide(code: Int) = code in 0x1100..0x115F ||
            code in 0x2E80..0xA4CF ||
            code in 0xAC00..0xD7A3 ||
            code in 0xF900..0xFAFF ||
            code in 0xFE30..0xFE4F ||
            code in 0xFF00..0xFF60 ||
            code in 0xFFE0..0xFFE6
    }
}
