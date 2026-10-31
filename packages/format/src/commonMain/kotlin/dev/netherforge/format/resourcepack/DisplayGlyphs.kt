package dev.netherforge.format.resourcepack

import dev.netherforge.format.centity.Anchor
import dev.netherforge.format.centity.TextRun
import dev.netherforge.format.image.Pixels
import dev.netherforge.format.image.PngWriter
import dev.netherforge.format.math.Matrix4
import dev.netherforge.format.text.TextMetrics

/**
 * How rect and image displays (and anchored text) are drawn by a text display, to the pixel: the engine's own
 * glyphs in the default font, the text that draws each, and where on the entity that text lands.
 *
 * **The client's geometry** (what [TextMetrics] already says, from the game's text display renderer): a font pixel
 * is [TextMetrics.BLOCKS_PER_PIXEL] blocks; a display of `n` lines whose widest is `l` pixels (advances, the gap
 * after each glyph included) shifts its text `1 - l/2` pixels across and stands it on the origin, `10n - 1` pixels
 * of lines above; line `k` starts `10k` pixels down from the top; a bitmap glyph's top is `7 - ascent` pixels below
 * its line's top; and the background reaches from one pixel left of the text and one above the first line to the
 * right end of the text and the bottom of the last line, so from `-l/2` to `l/2 + 1` across and from 0 to `10n` up.
 *
 * **Pixels.** A pixel glyph is a white bitmap 2^k by 1 drawn [PIXEL_HEIGHT] tall (one line exactly) with ascent 7,
 * so its top is its line's top and it fills the line; it's [PIXEL] font pixels wide per picture pixel and advances
 * one more, which a one-pixel move back takes away. So a run of `n` pixels of one colour is a glyph per set bit of
 * `n`, tinted by the text colour, and a row is exactly `10 × width` pixels: rows meet without a gap and pixels are
 * square. A transparent run is a move. Every row is padded to the full width, so the alignment changes nothing.
 */
object DisplayGlyphs {
    /** The engine's pixel glyphs: run `2^k` is this plus `k`. Above the space characters ([PackFonts]). */
    const val PIXEL_START = 0xF840

    /** Run glyphs: 1, 2, 4, … 128 pixels. */
    const val RUNS = 8

    /** One picture pixel, in font pixels, across and down: a whole line. */
    const val PIXEL = TextMetrics.LINE_HEIGHT

    /** A pixel glyph's drawn height. */
    const val PIXEL_HEIGHT = TextMetrics.LINE_HEIGHT

    /** Ascent that puts a glyph's top at its line's top. */
    const val TOP_ASCENT = 7

    /** Where the pixel glyphs' pictures are, as resource keys `netherforge:font/pixel_<n>.png`. */
    const val NAMESPACE = "netherforge"

    /** The move a rect's text is: its width in pixels, one space character. */
    private const val RECT_ADVANCE = 8

    private const val P = TextMetrics.BLOCKS_PER_PIXEL

    /** The character of the run glyph for `2^k` pixels. */
    fun runChar(k: Int): Char = (PIXEL_START + k).toChar()

    /** Every file the pixel glyphs need in a pack, path to bytes: one white picture per run. */
    fun pictures(): Map<String, ByteArray> = (0 until RUNS).associate { k ->
        val width = 1 shl k
        "assets/$NAMESPACE/textures/font/pixel_$width.png" to PngWriter.write(Pixels(width, 1, IntArray(width) { -1 }))
    }

    /** Each run glyph's provider: its picture's resource key and its character. */
    fun providers(): List<Pair<String, Char>> = (0 until RUNS).map { k -> "$NAMESPACE:font/pixel_${1 shl k}.png" to runChar(k) }

    /** A rect's text: a move of [RECT_ADVANCE] pixels, so the background is that plus one across and a line tall. */
    val RECT_TEXT: String = PackFonts.offset(RECT_ADVANCE)

    /** MiniMessage that draws [pixels]: [imageRuns] written out, each colour run in a `<color>` of its own. */
    fun imageText(width: Int, height: Int, pixels: Pixels?): String = buildString {
        for (run in imageRuns(width, height, pixels)) {
            val rgb = run.rgb
            if (rgb == null) {
                append(run.text)
            } else {
                append("<color:#").append(hex(rgb)).append('>').append(run.text).append("</color>")
            }
        }
    }

    /**
     * What draws [pixels], run by run: a line per row (each break a run of its own), each colour run its glyphs
     * tinted [TextRun.rgb], pixels below [Pixels.OPAQUE_FROM] alpha left out as moves. Null pixels (a blank image)
     * are rows of moves only. No run holds more than a row's worth of characters, so each is a short piece of text.
     */
    fun imageRuns(width: Int, height: Int, pixels: Pixels?): List<TextRun> = buildList {
        for (y in 0 until height) {
            if (y > 0) add(TextRun("\n"))
            var x = 0
            while (x < width) {
                val color = pixels?.takeIf { x < it.width && y < it.height }?.get(x, y)
                val opaque = color != null && (color ushr 24) >= Pixels.OPAQUE_FROM
                var end = x + 1
                while (end < width) {
                    val next = pixels?.takeIf { end < it.width && y < it.height }?.get(end, y)
                    val nextOpaque = next != null && (next ushr 24) >= Pixels.OPAQUE_FROM
                    if (nextOpaque != opaque || (opaque && (next!! and 0xFFFFFF) != (color!! and 0xFFFFFF))) break
                    end++
                }
                val run = end - x
                val text = buildString {
                    if (opaque) {
                        for (k in RUNS - 1 downTo 0) {
                            var left = run
                            // A run longer than the biggest glyph uses it as many times as it takes.
                            if (k == RUNS - 1) {
                                while (left >= 1 shl k) {
                                    append(runChar(k)).append(BACK)
                                    left -= 1 shl k
                                }
                            } else if (left and (1 shl k) != 0) {
                                append(runChar(k)).append(BACK)
                            }
                        }
                    } else {
                        var left = run * PIXEL
                        while (left > 0) {
                            val step = minOf(left, PackFonts.MAX_OFFSET)
                            append(PackFonts.offset(step))
                            left -= step
                        }
                    }
                }
                add(TextRun(text, if (opaque) color!! and 0xFFFFFF else null))
                x = end
            }
        }
    }

    /** The one-pixel move back after each glyph. */
    private val BACK = PackFonts.offset(-1)

    private fun hex(rgb: Int): String = rgb.toString(16).padStart(6, '0')

    /** The line width an image's text needs so no row ever wraps. */
    fun imageLineWidth(width: Int): Int = width * PIXEL + PIXEL

    /** A box in the display's own space, in blocks: where its text draws, before any fitting. */
    data class Box(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
        val width: Double get() = maxX - minX
        val height: Double get() = maxY - minY
    }

    /** Where a rect's background draws: [RECT_TEXT] is the one line's whole text. */
    val RECT_BOX: Box = Box(-RECT_ADVANCE / 2.0 * P, 0.0, (RECT_ADVANCE / 2.0 + 1) * P, TextMetrics.LINE_HEIGHT * P)

    /** Where an image's pixels draw: rows from the top of the first line, one pixel low of the background's. */
    fun imageBox(width: Int, height: Int): Box {
        val l = width * PIXEL
        val m = height * TextMetrics.LINE_HEIGHT - 1
        return Box((1 - l / 2.0) * P, (m - height * PIXEL) * P, (1 + l / 2.0) * P, m * P)
    }

    /**
     * Where text [lineWidth] pixels wide (its widest line) in [lines] lines draws: its advances across, and from the
     * top of its first line (a glyph of ascent 7 has its top there) to the bottom of its last, which is a pixel inside
     * the background at the top and a pixel past it at the bottom, as an image's rows are.
     */
    fun textBox(lineWidth: Int, lines: Int): Box {
        val m = lines * TextMetrics.LINE_HEIGHT - 1
        return Box((1 - lineWidth / 2.0) * P, (m - lines * TextMetrics.LINE_HEIGHT) * P, (1 + lineWidth / 2.0) * P, m * P)
    }

    /**
     * The matrix, in the display's own space, that puts [box] where [anchor] says on a rectangle [width] by [height]
     * blocks around the origin (stretched to it: null [width] and [height] keep the box's own size).
     */
    fun fit(box: Box, anchor: Anchor, width: Double? = null, height: Double? = null): Matrix4 {
        val w = width ?: box.width
        val h = height ?: box.height
        return Matrix4()
            .translate(-anchor.x * w, -anchor.y * h, 0.0)
            .scale(w / box.width, h / box.height, 1.0)
            .translate(-box.minX, -box.minY, 0.0)
    }
}
