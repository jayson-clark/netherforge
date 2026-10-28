package dev.netherforge.format.resourcepack

import dev.netherforge.format.project.ImageInfo
import kotlin.math.floor

/**
 * The characters a pack hands out, and the arithmetic for placing them.
 *
 * Shared because three parties must agree to the pixel: the pack builder writes
 * the font files from it, the plugin composes a window title with it, and the
 * editor draws a preview of that same title.
 */
object PackFonts {
    /** The font a pack's skins are in: `<namespace>:<pack>/gui`. */
    const val GUI_FONT = "gui"

    /** Private-use characters for pictures start here. */
    const val PUA_START = 0xE000

    /** Space characters live at the top of the private-use block, out of the pictures' way. */
    private const val NEGATIVE_START = 0xF800
    private const val POSITIVE_START = 0xF820

    /** Pictures one pack can hold: every private-use character below the space characters. */
    const val PUA_COUNT = NEGATIVE_START - PUA_START

    /** Offsets are composed from powers of two up to this many bits. */
    private const val STEPS = 11

    /** The largest single move either way, in pixels. */
    val MAX_OFFSET: Int = (1 shl STEPS) - 1

    fun charAt(index: Int): String {
        require(index in 0 until PUA_COUNT) { "A resource pack holds at most $PUA_COUNT pictures" }
        return (PUA_START + index).toChar().toString()
    }

    /** The `space` provider's table: which character moves the cursor how far. */
    fun advances(): Map<String, Int> {
        val table = LinkedHashMap<String, Int>()
        for (bit in 0 until STEPS) {
            table[(NEGATIVE_START + bit).toChar().toString()] = -(1 shl bit)
            table[(POSITIVE_START + bit).toChar().toString()] = 1 shl bit
        }
        return table
    }

    /** How far space character [codePoint] moves the cursor, or null when it isn't one of [advances]. */
    fun spaceAdvance(codePoint: Int): Int? = when (codePoint) {
        in NEGATIVE_START until NEGATIVE_START + STEPS -> -(1 shl (codePoint - NEGATIVE_START))
        in POSITIVE_START until POSITIVE_START + STEPS -> 1 shl (codePoint - POSITIVE_START)
        else -> null
    }

    /** Characters that move the cursor [px] pixels; empty for zero, clamped beyond [MAX_OFFSET]. */
    fun offset(px: Int): String {
        val wanted = px.coerceIn(-MAX_OFFSET, MAX_OFFSET)
        if (wanted == 0) return ""
        val start = if (wanted < 0) NEGATIVE_START else POSITIVE_START
        var left = if (wanted < 0) -wanted else wanted
        return buildString {
            for (bit in STEPS - 1 downTo 0) {
                val step = 1 shl bit
                if (left >= step) {
                    append((start + bit).toChar())
                    left -= step
                }
            }
        }
    }

    /**
     * A bitmap glyph's advance, as Minecraft's bitmap font provider computes it
     * for a one-character provider (the cell is the whole image): the opaque
     * width scaled to the drawn height, rounded, plus one pixel of gap.
     * [opaqueWidth] is the rightmost column holding any non-transparent pixel,
     * plus one (0 for a fully transparent picture).
     *
     * The game scales in `float`; this uses `Double` (Kotlin/JS has no
     * float32), which agrees except for products landing within float error of
     * a half pixel.
     */
    fun bitmapAdvance(opaqueWidth: Int, imageHeight: Int, drawnHeight: Int): Int {
        require(imageHeight > 0) { "An image is at least one pixel tall" }
        return floor(0.5 + opaqueWidth.toDouble() * drawnHeight / imageHeight).toInt() + 1
    }

    /**
     * The most pixels one character's picture can have either way: the client stitches every font picture into
     * sheets this big (`FontTexture.SIZE`, 256 in Minecraft 26.3) and draws one that doesn't fit blank, with no
     * message. A wider picture is cut into pieces ([pieces]); a taller one can't be.
     */
    const val SHEET = 256

    /** The most pieces [pieces] tries per piece a picture needs at least, looking for an even cut. */
    private const val CUT_TRIES = 4

    /**
     * How many equal pieces, side by side, a picture [width] pixels wide is drawn as so each fits a font sheet:
     * 1 when it fits whole, null when no even cut does (the game's grid cuts a picture into equal cells and
     * drops what's left over on the right). Of the cuts that fit, the first whose pieces are a whole number of
     * pixels wide when drawn [drawnHeight] tall (from [imageHeight]), so they meet without a seam.
     */
    fun pieces(width: Int, imageHeight: Int, drawnHeight: Int): Int? {
        if (width <= SHEET) return 1
        val least = (width + SHEET - 1) / SHEET
        val even = (least..least * CUT_TRIES).filter { width % it == 0 }
        return even.firstOrNull { (width / it).toLong() * drawnHeight % imageHeight == 0L } ?: even.firstOrNull()
    }

    /**
     * A picture as characters of a font: [cells] is its provider's one row of characters, one per piece left to
     * right; [text] draws the whole picture (its one character, or the pieces and the moves between them);
     * [advance] is how far [text] moves the cursor, null when the picture's pixels are unknown.
     */
    class Picture(val cells: String, val text: String, val advance: Int?)

    /**
     * Picture [image] drawn [drawnHeight] tall as [first], its own character, and when it's too wide for a font
     * sheet, pieces cut by [pieces] with a character each from [extra] (null when there are none left: the
     * picture then stays whole). Its [Picture.advance] is the whole picture's either way. Unknown pixels, or a
     * picture that can't be cut (too tall, or no even cut), stay one character; the validator reports those.
     */
    fun picture(first: String, image: ImageInfo?, drawnHeight: Int, extra: () -> String?): Picture {
        if (image == null || image.height <= 0) return Picture(first, first, null)
        val advance = bitmapAdvance(image.opaqueWidth, image.height, drawnHeight)
        val whole = Picture(first, first, advance)
        val count = pieces(image.width, image.height, drawnHeight)
        if (count == null || count == 1 || image.height > SHEET) return whole
        val chars = listOf(first) + List(count - 1) { extra() ?: return whole }
        val cell = image.width / count
        val advances = List(count) { bitmapAdvance(image.opaqueWidth(it * cell, (it + 1) * cell), image.height, drawnHeight) }
        return Picture(chars.joinToString(""), lay(chars, cell, image.height, drawnHeight, advances, advance), advance)
    }

    /** The narrowest a picture can be padded to so it cuts into [pieces] (for a [width] that doesn't). */
    fun evenWidth(width: Int): Int {
        val least = (width + SHEET - 1) / SHEET
        return (width + least - 1) / least * least
    }

    /**
     * The text that draws a picture cut into pieces ([chars], left to right, each [cellWidth] pixels of a
     * picture [imageHeight] tall, drawn [drawnHeight] tall): each piece where it is in the whole picture,
     * then a move so the whole moves the cursor [advance], as the picture would in one piece. [advances] is
     * each piece's own advance ([bitmapAdvance] of its own opaque width), which the cursor moves after it.
     */
    fun lay(chars: List<String>, cellWidth: Int, imageHeight: Int, drawnHeight: Int, advances: List<Int>, advance: Int): String =
        buildString {
            var cursor = 0
            for ((i, char) in chars.withIndex()) {
                val at = floor(0.5 + i.toDouble() * cellWidth * drawnHeight / imageHeight).toInt()
                append(offset(at - cursor)).append(char)
                cursor = at + advances[i]
            }
            append(offset(advance - cursor))
        }
}
