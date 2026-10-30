package dev.netherforge.plugin.centity

import dev.netherforge.format.centity.Billboard
import dev.netherforge.format.centity.DisplayDef
import dev.netherforge.format.centity.ImageDisplay
import dev.netherforge.format.centity.RectDisplay
import dev.netherforge.format.centity.TextAlignment
import dev.netherforge.format.centity.TextDisplay
import dev.netherforge.format.math.Matrix4
import dev.netherforge.format.resourcepack.DisplayGlyphs

/**
 * What a node's display is drawn as on the server's entities: block, item, model and plain text displays as they
 * are; a rect, an image and anchored text as a text display ([DisplayGlyphs]) with a [fit], the matrix in the
 * display's own space that puts its text exactly where the node's box is. Worked out once per display (an image's
 * text is the costly part) and kept for the last [CACHED] displays drawn.
 *
 * [textSize] measures text as the game does: its widest line in pixels and its number of lines, or null when it
 * can't (no `fonts/default.json`, a character it doesn't cover).
 */
internal class DisplayDrawing(private val textSize: (String) -> Pair<Int, Int>?, private val warn: (String) -> Unit) {
    /** A display as drawn: what the entity shows, the matrix it's fitted by (null: none), and what showing it costs. */
    class Drawn(val shown: DisplayDef, val fit: Matrix4?, val bytes: Int)

    private val cache = object : LinkedHashMap<DisplayDef, Drawn>(CACHED, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<DisplayDef, Drawn>?) = size > CACHED
    }

    /** Anchored texts already warned about, so one set every tick warns once. */
    private val unmeasured = HashSet<String>()

    fun drawn(display: DisplayDef): Drawn = cache.getOrPut(display) { draw(display) }

    private fun draw(display: DisplayDef): Drawn = when (display) {
        is RectDisplay -> {
            val size = display.size ?: RectDisplay.DEFAULT_SIZE
            Drawn(
                TextDisplay(
                    DisplayGlyphs.RECT_TEXT,
                    billboard = Billboard.FIXED,
                    background = argb(display.color),
                    shadow = false
                ),
                DisplayGlyphs.fit(DisplayGlyphs.RECT_BOX, display.anchor ?: RectDisplay.DEFAULT_ANCHOR, size[0], size[1]),
                DisplayGlyphs.RECT_TEXT.length
            )
        }
        is ImageDisplay -> {
            val size = display.sizeOrDefault
            val text = DisplayGlyphs.imageText(display.width, display.height, display.pixels)
            Drawn(
                TextDisplay(
                    text,
                    billboard = Billboard.FIXED,
                    alignment = TextAlignment.LEFT,
                    background = TRANSPARENT,
                    lineWidth = DisplayGlyphs.imageLineWidth(display.width),
                    shadow = false,
                    // What a platform draws: a picture's text is too long to be one piece of the game's text.
                    runs = DisplayGlyphs.imageRuns(display.width, display.height, display.pixels)
                ),
                DisplayGlyphs.fit(
                    DisplayGlyphs.imageBox(display.width, display.height),
                    display.anchor ?: ImageDisplay.DEFAULT_ANCHOR,
                    size[0],
                    size[1]
                ),
                text.length
            )
        }
        is TextDisplay -> {
            val anchor = display.anchor
            val size = anchor?.let { textSize(display.text) }
            if (anchor != null && size == null && unmeasured.add(display.text)) {
                warn(
                    "a text display anchored ${anchor.name.lowercase()} can't be placed: its text can't be measured " +
                        "(is fonts/default.json missing, or does it use a character the font doesn't cover?), so it's drawn unanchored"
                )
            }
            if (anchor == null || size == null) {
                Drawn(display, null, display.text.length)
            } else {
                val (width, lines) = size
                // Lines break only where the text breaks them: the box was measured that way.
                Drawn(
                    display.copy(lineWidth = maxOf(width, 1) + UNWRAPPED_SLACK),
                    DisplayGlyphs.fit(DisplayGlyphs.textBox(width, lines), anchor),
                    display.text.length
                )
            }
        }
        else -> Drawn(display, null, 0)
    }

    companion object {
        /** Displays whose drawing is kept. */
        const val CACHED = 512

        /** What an anchored text's line width is past its widest line, so a line the game measures a pixel wider still doesn't wrap. */
        private const val UNWRAPPED_SLACK = 64

        private const val TRANSPARENT = "#00000000"

        /** `#RRGGBB` (opaque) or `#AARRGGBB` as `#AARRGGBB`. */
        fun argb(color: String): String = if (color.length == 7) "#FF" + color.substring(1) else color
    }
}
