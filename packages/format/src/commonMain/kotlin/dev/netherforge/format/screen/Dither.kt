package dev.netherforge.format.screen

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How a picture's colours become map colours: each pixel the nearest one ([NONE]), or the error of each pushed
 * onto the pixels after it so areas average out to the right colour ([FLOYD_STEINBERG], best for photos and
 * video), or a fixed pattern of thresholds ([ORDERED], which doesn't crawl from one video frame to the next).
 */
@Serializable
enum class Dither {
    @SerialName("none")
    NONE,

    @SerialName("floyd_steinberg")
    FLOYD_STEINBERG,

    @SerialName("ordered")
    ORDERED;

    /**
     * [argb] (`0xAARRGGBB` a pixel, [width] by [height], row by row) as map colours of [palette]: an index a
     * pixel, or [SKIP] for one less than half opaque (which draws nothing, so what was there shows). The same on
     * the JVM and in JS: only whole-number arithmetic.
     */
    fun apply(argb: IntArray, width: Int, height: Int, palette: MapPalette): IntArray {
        require(argb.size >= width * height) { "${argb.size} pixels for $width by $height" }
        val out = IntArray(width * height)
        when (this) {
            NONE -> for (i in out.indices) {
                val c = argb[i]
                out[i] = if (opaque(c)) palette.nearest(c and 0xFFFFFF) else SKIP
            }
            ORDERED -> for (y in 0 until height) {
                for (x in 0 until width) {
                    val i = y * width + x
                    val c = argb[i]
                    if (!opaque(c)) {
                        out[i] = SKIP
                        continue
                    }
                    // A threshold from -32 to +30, the Bayer matrix's cell spread over a quarter of the range.
                    val t = (BAYER[(y and 7) * 8 + (x and 7)] - 32)
                    out[i] = palette.nearest(rgb(clamp(red(c) + t), clamp(green(c) + t), clamp(blue(c) + t)))
                }
            }
            FLOYD_STEINBERG -> floydSteinberg(argb, width, height, palette, out)
        }
        return out
    }

    private fun floydSteinberg(argb: IntArray, width: Int, height: Int, palette: MapPalette, out: IntArray) {
        // The error carried to this row and the next, in sixteenths, per channel.
        var row = IntArray((width + 2) * 3)
        var next = IntArray((width + 2) * 3)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val c = argb[i]
                val e = (x + 1) * 3
                if (!opaque(c)) {
                    out[i] = SKIP
                    continue
                }
                val r = clamp(red(c) + row[e] / 16)
                val g = clamp(green(c) + row[e + 1] / 16)
                val b = clamp(blue(c) + row[e + 2] / 16)
                // Exact, not [MapPalette.nearest]'s 15-bit cells: the error carried is what keeps a gradient smooth.
                val index = palette.search(r, g, b)
                out[i] = index
                val got = palette.rgb(index) ?: rgb(r, g, b)
                val dr = r - ((got shr 16) and 0xFF)
                val dg = g - ((got shr 8) and 0xFF)
                val db = b - (got and 0xFF)
                // 7/16 right, 3/16 below left, 5/16 below, 1/16 below right.
                row[e + 3] += dr * 7
                row[e + 4] += dg * 7
                row[e + 5] += db * 7
                next[e - 3] += dr * 3
                next[e - 2] += dg * 3
                next[e - 1] += db * 3
                next[e] += dr * 5
                next[e + 1] += dg * 5
                next[e + 2] += db * 5
                next[e + 3] += dr
                next[e + 4] += dg
                next[e + 5] += db
            }
            val done = row
            row = next
            next = done
            next.fill(0)
        }
    }

    companion object {
        /** What [apply] gives a pixel that draws nothing. */
        const val SKIP = -1

        /** The 8 by 8 Bayer matrix, 0 to 63. */
        private val BAYER = intArrayOf(
            0, 32, 8, 40, 2, 34, 10, 42,
            48, 16, 56, 24, 50, 18, 58, 26,
            12, 44, 4, 36, 14, 46, 6, 38,
            60, 28, 52, 20, 62, 30, 54, 22,
            3, 35, 11, 43, 1, 33, 9, 41,
            51, 19, 59, 27, 49, 17, 57, 25,
            15, 47, 7, 39, 13, 45, 5, 37,
            63, 31, 55, 23, 61, 29, 53, 21
        )

        private fun opaque(argb: Int) = (argb ushr 24) >= 128

        private fun red(c: Int) = (c shr 16) and 0xFF

        private fun green(c: Int) = (c shr 8) and 0xFF

        private fun blue(c: Int) = c and 0xFF

        private fun clamp(v: Int) = if (v < 0) {
            0
        } else if (v > 255) {
            255
        } else {
            v
        }

        private fun rgb(r: Int, g: Int, b: Int) = (r shl 16) or (g shl 8) or b
    }
}
