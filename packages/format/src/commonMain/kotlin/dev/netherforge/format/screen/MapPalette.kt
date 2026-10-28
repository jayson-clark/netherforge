package dev.netherforge.format.screen

import dev.netherforge.format.game.GameData

/**
 * The colours a map can show: the game's map colours, each base colour in four shades, so a map's pixel is a byte
 * `base * 4 + shade` ([GameData.mapColors], the server's own table: nothing here knows a colour of the game's).
 * Indices 0 to 3 are transparent (the frame's map background shows through), and so is any index the game has no
 * colour for.
 *
 * Matching a colour picks the shade nearest it by a weighted distance ([distance]: red and blue weighed by how red
 * the pair is, which tracks what eyes see far better than plain RGB distance at no cost). A match is remembered
 * per 15-bit colour (five bits a channel), so drawing a picture or a video frame costs a table lookup a pixel once
 * the table is warm.
 */
class MapPalette(colors: List<Int>) {
    /** Each index's colour as `0xAARRGGBB`; alpha 0 for an index that isn't drawable. */
    private val argb = IntArray(SIZE) { colors.getOrElse(it) { 0 } }

    /** The drawable indices, in order: what [nearest] searches. */
    val drawable: IntArray = (0 until SIZE).filter { it >= FIRST_DRAWABLE && (argb[it] ushr 24) != 0 }.toIntArray()

    /** The answer for each 15-bit colour, or -1 until it's first asked. */
    private val near = IntArray(1 shl 15) { -1 }

    /** Whether the palette has anything to draw with: a game that told us nothing has none. */
    val isEmpty: Boolean get() = drawable.isEmpty()

    /** The colour of map index [index] as `0xRRGGBB`, or null for a transparent one. */
    fun rgb(index: Int): Int? {
        val at = index and 0xFF
        if (at < FIRST_DRAWABLE) return null
        val c = argb[at]
        return if ((c ushr 24) == 0) null else c and 0xFFFFFF
    }

    /** Whether [index] draws anything. */
    fun isDrawable(index: Int): Boolean = rgb(index) != null

    /** The drawable index nearest the colour [rgb] (`0xRRGGBB`); [TRANSPARENT] when the palette is empty. */
    fun nearest(rgb: Int): Int {
        val key = ((rgb shr 9) and 0x7C00) or ((rgb shr 6) and 0x03E0) or ((rgb shr 3) and 0x001F)
        val known = near[key]
        if (known >= 0) return known
        // The middle of the 15-bit cell, so every colour in it gets the same answer.
        val r = ((key shr 10) and 31) * 8 + 4
        val g = ((key shr 5) and 31) * 8 + 4
        val b = (key and 31) * 8 + 4
        return search(r, g, b).also { near[key] = it }
    }

    /** The drawable index nearest ([distance]) the colour ([r], [g], [b]), each 0 to 255, uncached. */
    fun search(r: Int, g: Int, b: Int): Int {
        var best = TRANSPARENT
        var bestDistance = Long.MAX_VALUE
        for (index in drawable) {
            val c = argb[index]
            val d = distance(r, g, b, (c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
            if (d < bestDistance) {
                bestDistance = d
                best = index
            }
        }
        return best
    }

    companion object {
        /** How many indices a map pixel can hold. */
        const val SIZE = 256

        /** The index that draws nothing. */
        const val TRANSPARENT = 0

        /** Indices below this are the game's "no colour", whatever shade. */
        const val FIRST_DRAWABLE = 4

        /** The game's palette, or an empty one when [game] doesn't say. */
        fun of(game: GameData?): MapPalette = MapPalette(game?.mapColors.orEmpty())

        /**
         * The "redmean" distance between two colours, squared and scaled to stay whole: red and blue count more or
         * less as the pair is redder, which is close to how far apart they look.
         */
        fun distance(r1: Int, g1: Int, b1: Int, r2: Int, g2: Int, b2: Int): Long {
            val mean = (r1 + r2) / 2
            val dr = (r1 - r2).toLong()
            val dg = (g1 - g2).toLong()
            val db = (b1 - b2).toLong()
            return ((512 + mean) * dr * dr shr 8) + 4 * dg * dg + ((767 - mean) * db * db shr 8)
        }

        /** `#rrggbb` as `0xRRGGBB`, or null when it isn't written so. */
        fun parse(color: String): Int? {
            if (!COLOR.matches(color)) return null
            return color.substring(1).toInt(16)
        }

        /** How a colour is written: `#rrggbb`. */
        val COLOR = Regex("^#[0-9a-fA-F]{6}$")
    }
}
