package dev.netherforge.plugin.screen

import dev.netherforge.format.screen.MapPalette
import dev.netherforge.format.screen.ScreenFontFile
import dev.netherforge.format.screen.ScreenFontType
import dev.netherforge.format.screen.ScreenFontValidator
import dev.netherforge.format.screen.ScreenFontValidator.codePointList
import dev.netherforge.format.text.MiniMessagePass
import dev.netherforge.plugin.api.layout
import dev.netherforge.plugin.platform.MapFont
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/** One character's picture: how much of each pixel it covers (0 to 255, row by row), and how far it moves the pen. */
class Glyph(val width: Int, val height: Int, val advance: Int, val coverage: IntArray)

/**
 * A font text is drawn in: [height] pixels from the top of a line to the bottom of its characters, [lineHeight]
 * from one line's top to the next's, [spacing] pixels between characters, and [spaceWidth] for a space. Whether
 * its edges blend into what's behind them is [antialias].
 */
class ScreenFont(
    val height: Int,
    val lineHeight: Int,
    val spacing: Int,
    val spaceWidth: Int,
    val antialias: Boolean,
    private val lookup: (Int) -> Glyph?
) {
    private val glyphs = HashMap<Int, Glyph?>()

    /** The character [code], or null when the font has none (it then draws nothing and takes no room). */
    fun glyph(code: Int): Glyph? = glyphs.getOrPut(code) { lookup(code) }

    /** How far [code] moves the pen, its spacing after it included (a space's width for a space). */
    fun advance(code: Int, bold: Boolean): Int = when (code) {
        ' '.code -> spaceWidth + spacing
        else -> glyph(code)?.let { it.advance + spacing + if (bold) 1 else 0 } ?: 0
    }

    companion object {
        /** The game's own map font, as the server has it. */
        fun of(font: MapFont): ScreenFont {
            val glyphs = font.glyphs.mapValues { (_, g) ->
                Glyph(g.width, g.height, g.width, IntArray(g.width * g.height) { if (g.pixels[it]) 255 else 0 })
            }
            val space = glyphs[' '.code]?.advance ?: maxOf(1, font.height / 3)
            return ScreenFont(font.height, font.height + 1, 1, space, false) { glyphs[it] }
        }

        /** A project's font from its file and the bytes of the picture or font file it names (none for a pixels font). */
        fun of(file: ScreenFontFile, bytes: ByteArray?): ScreenFont = when (file.type) {
            ScreenFontType.PIXELS -> pixels(file)
            ScreenFontType.BITMAP -> bitmap(file, requireNotNull(bytes) { "a bitmap font needs its picture" })
            ScreenFontType.TRUETYPE -> trueType(file, requireNotNull(bytes) { "a TrueType font needs its file" })
        }

        private fun pixels(file: ScreenFontFile): ScreenFont {
            val glyphs = file.glyphs.orEmpty().mapNotNull { (char, rows) ->
                val code = char.codePointList().singleOrNull() ?: return@mapNotNull null
                val width = rows.firstOrNull()?.length ?: 0
                val coverage = IntArray(width * rows.size) { i ->
                    if (rows[i / width].getOrNull(i % width) ==
                        ScreenFontFile.INK
                    ) {
                        255
                    } else {
                        0
                    }
                }
                code to Glyph(width, rows.size, width, coverage)
            }.toMap()
            val height = glyphs.values.maxOfOrNull { it.height } ?: 1
            return ScreenFont(
                height,
                file.lineHeight ?: (height + 1),
                file.spacingOrDefault,
                file.spaceWidth ?: maxOf(1, height / 3),
                false
            ) { glyphs[it] }
        }

        private fun bitmap(file: ScreenFontFile, bytes: ByteArray): ScreenFont {
            val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: throw IllegalArgumentException("${file.file} isn't a picture")
            val (across, down) = ScreenFontValidator.grid(file) ?: throw IllegalArgumentException("the font lists no characters")
            val cellWidth = image.width / across
            val cellHeight = image.height / down
            require(cellWidth > 0 && cellHeight > 0) { "${file.file} is too small for its grid" }
            val cells = HashMap<Int, Pair<Int, Int>>()
            file.chars.orEmpty().forEachIndexed { row, line ->
                line.codePointList().forEachIndexed { column, code -> if (code != 0 && code !in cells) cells[code] = column to row }
            }
            val lookup = { code: Int ->
                cells[code]?.let { (column, row) ->
                    val coverage = IntArray(cellWidth * cellHeight) { i ->
                        val alpha = image.getRGB(column * cellWidth + i % cellWidth, row * cellHeight + i / cellWidth) ushr 24
                        if (alpha >= 128) 255 else 0
                    }
                    // As wide as its opaque columns, as the game measures a bitmap font's characters.
                    var width = 0
                    for (x in 0 until cellWidth) if ((0 until cellHeight).any { coverage[it * cellWidth + x] != 0 }) width = x + 1
                    val trimmed = IntArray(width * cellHeight) { coverage[(it / maxOf(width, 1)) * cellWidth + it % maxOf(width, 1)] }
                    Glyph(width, cellHeight, width, trimmed)
                }
            }
            return ScreenFont(
                cellHeight,
                file.lineHeight ?: (cellHeight + 1),
                file.spacingOrDefault,
                file.spaceWidth ?: maxOf(1, cellHeight / 3),
                false,
                lookup
            )
        }

        private fun trueType(file: ScreenFontFile, bytes: ByteArray): ScreenFont {
            val base = java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, ByteArrayInputStream(bytes))
            val font = base.deriveFont(file.sizeOrDefault.toFloat())
            val probe = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics()
            probe.font = font
            val metrics = probe.fontMetrics
            probe.dispose()
            val ascent = metrics.ascent
            val height = ascent + metrics.descent
            val antialias = file.antialiasOrDefault
            val lookup = { code: Int ->
                if (!font.canDisplay(code)) {
                    null
                } else {
                    val text = String(Character.toChars(code))
                    val advance = metrics.stringWidth(text)
                    // Room either side for strokes that reach past the advance (italics, an `f`'s hook).
                    val pad = height / 4 + 1
                    val image = BufferedImage(advance + 2 * pad, height, BufferedImage.TYPE_INT_ARGB)
                    val graphics = image.createGraphics()
                    graphics.setRenderingHint(
                        RenderingHints.KEY_TEXT_ANTIALIASING,
                        if (antialias) RenderingHints.VALUE_TEXT_ANTIALIAS_ON else RenderingHints.VALUE_TEXT_ANTIALIAS_OFF
                    )
                    graphics.font = font
                    graphics.color = java.awt.Color.WHITE
                    graphics.drawString(text, pad, ascent)
                    graphics.dispose()
                    // Kept from the pen's position: what's left of it is dropped, which only an italic's lean loses.
                    val width = advance + pad
                    val coverage = IntArray(width * height) { i ->
                        val alpha = image.getRGB(pad + i % width, i / width) ushr 24
                        if (antialias) {
                            alpha
                        } else if (alpha >= 128) {
                            255
                        } else {
                            0
                        }
                    }
                    Glyph(width, height, advance, coverage)
                }
            }
            return ScreenFont(
                height,
                file.lineHeight ?: (height + 1),
                file.spacingOrDefault,
                file.spaceWidth ?: metrics.charWidth(' '),
                antialias,
                lookup
            )
        }
    }
}

/** How text is laid out and drawn: what `screen:text` and `nf.screens.measure_text` take, checked. */
data class TextLayout(
    val font: ScreenFont,
    /** Where text sets no colour: `0xRRGGBB`. */
    val color: Int = 0xFFFFFF,
    val scale: Int = 1,
    val align: Align = Align.LEFT,
    /** Break lines at spaces past this many pixels; null for none. */
    val width: Int? = null,
    /** A shadow's colour, `0xRRGGBB`, or null for none. */
    val shadow: Int? = null,
    val lineHeight: Int? = null
) {
    enum class Align { LEFT, CENTER, RIGHT }
}

/** Text drawn on a [Canvas]: MiniMessage's colours and decorations, broken into lines, measured and drawn. */
object ScreenText {
    /** One character as it's drawn, in its style. */
    private class Mark(val code: Int, val color: Int?, val bold: Boolean, val underlined: Boolean, val strikethrough: Boolean)

    private class Line(val marks: List<Mark>, val width: Int)

    /** How big [text] is drawn with [layout], in pixels: its widest line, and all its lines. */
    fun measure(text: String, layout: TextLayout): Pair<Int, Int> {
        val lines = lines(text, layout)
        return size(lines, layout)
    }

    private fun size(lines: List<Line>, layout: TextLayout): Pair<Int, Int> {
        if (lines.isEmpty()) return 0 to 0
        val width = lines.maxOf { it.width } * layout.scale
        val step = layout.lineHeight ?: layout.font.lineHeight
        val height = ((lines.size - 1) * step + layout.font.height) * layout.scale
        return width to height
    }

    /**
     * Draws [text] at ([x], [y]) (its top, and its left, middle or right as [TextLayout.align] says) onto [canvas],
     * matching colours in [palette]. Its size, as [measure] says.
     */
    fun draw(canvas: Canvas, palette: MapPalette, x: Int, y: Int, text: String, layout: TextLayout): Pair<Int, Int> {
        val lines = lines(text, layout)
        layout.shadow?.let { shadow -> paint(canvas, palette, x + layout.scale, y + layout.scale, lines, layout, shadow) }
        paint(canvas, palette, x, y, lines, layout, null)
        return size(lines, layout)
    }

    private fun paint(canvas: Canvas, palette: MapPalette, x: Int, y: Int, lines: List<Line>, layout: TextLayout, only: Int?) {
        val font = layout.font
        val scale = layout.scale
        val step = (layout.lineHeight ?: font.lineHeight) * scale
        lines.forEachIndexed { index, line ->
            val top = y + index * step
            var pen = when (layout.align) {
                TextLayout.Align.LEFT -> x
                TextLayout.Align.CENTER -> x - line.width * scale / 2
                TextLayout.Align.RIGHT -> x - line.width * scale
            }
            for (mark in line.marks) {
                val rgb = only ?: mark.color ?: layout.color
                val advance = font.advance(mark.code, mark.bold) * scale
                if (mark.code != ' '.code) {
                    font.glyph(mark.code)?.let { glyph ->
                        glyph(canvas, palette, pen, top, glyph, rgb, scale, font.antialias)
                        if (mark.bold) glyph(canvas, palette, pen + scale, top, glyph, rgb, scale, font.antialias)
                    }
                }
                val color = palette.nearest(rgb)
                if (mark.underlined) canvas.fill(pen, top + font.height * scale, advance, scale, color)
                if (mark.strikethrough) canvas.fill(pen, top + (font.height / 2) * scale, advance, scale, color)
                pen += advance
            }
        }
    }

    private fun glyph(canvas: Canvas, palette: MapPalette, x: Int, y: Int, glyph: Glyph, rgb: Int, scale: Int, antialias: Boolean) {
        val solid = palette.nearest(rgb)
        for (gy in 0 until glyph.height) {
            for (gx in 0 until glyph.width) {
                val cover = glyph.coverage[gy * glyph.width + gx]
                if (cover == 0) continue
                for (sy in 0 until scale) {
                    for (sx in 0 until scale) {
                        val px = x + gx * scale + sx
                        val py = y + gy * scale + sy
                        if (!canvas.inside(px, py)) continue
                        canvas.set(
                            px,
                            py,
                            if (cover >= 255 ||
                                !antialias
                            ) {
                                solid
                            } else {
                                blend(canvas, palette, px, py, rgb, cover) ?: continue
                            }
                        )
                    }
                }
            }
        }
    }

    /** [rgb] over the pixel at ([x], [y]) by [cover] of 255, as the nearest map colour; null to leave it. */
    private fun blend(canvas: Canvas, palette: MapPalette, x: Int, y: Int, rgb: Int, cover: Int): Int? {
        val under = palette.rgb(canvas.get(x, y)) ?: return if (cover >= 128) palette.nearest(rgb) else null
        fun mix(shift: Int) = (((rgb shr shift) and 0xFF) * cover + ((under shr shift) and 0xFF) * (255 - cover)) / 255
        return palette.nearest((mix(16) shl 16) or (mix(8) shl 8) or mix(0))
    }

    /** [text] as lines of styled characters, broken at line breaks and, past [TextLayout.width], at spaces. */
    private fun lines(text: String, layout: TextLayout): List<Line> {
        val font = layout.font
        val out = mutableListOf<Line>()
        var current = mutableListOf<Mark>()
        fun width(marks: List<Mark>) = (marks.sumOf { font.advance(it.code, it.bold) } - font.spacing).coerceAtLeast(0)
        fun finish() {
            out += Line(current, width(current))
            current = mutableListOf()
        }
        for (piece in MiniMessagePass.pieces(text)) {
            when (piece) {
                is MiniMessagePass.Break -> finish()
                is MiniMessagePass.Char -> current += Mark(
                    piece.code,
                    piece.style.color?.let { MapPalette.parse(it) },
                    piece.style.bold == true,
                    piece.style.underlined == true,
                    piece.style.strikethrough == true
                )
                else -> {}
            }
        }
        finish()
        val wrap = layout.width?.let { it / layout.scale } ?: return out
        return out.flatMap { wrapLine(it, wrap, ::width) }
    }

    /** [line] broken at spaces so no part is wider than [limit], a word too long for a line alone left whole. */
    private fun wrapLine(line: Line, limit: Int, width: (List<Mark>) -> Int): List<Line> {
        if (line.width <= limit) return listOf(line)
        val out = mutableListOf<Line>()
        var start = 0
        var lastSpace = -1
        var i = 0
        val marks = line.marks
        while (i < marks.size) {
            if (marks[i].code == ' '.code) lastSpace = i
            val upTo = marks.subList(start, i + 1)
            if (width(upTo) > limit && lastSpace > start) {
                val kept = marks.subList(start, lastSpace)
                out += Line(kept, width(kept))
                start = lastSpace + 1
                lastSpace = -1
                i = start
                continue
            }
            i++
        }
        val rest = marks.subList(start, marks.size)
        out += Line(rest, width(rest))
        return out
    }
}
