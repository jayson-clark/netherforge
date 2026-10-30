package dev.netherforge.format.image

/**
 * A picture's pixels, `0xAARRGGBB` row by row from the top left: what an image display shows and what `nf.images`
 * makes. Never changed once made (an image display's drawn text is worked out once per picture), and equal to
 * another with the same pixels, so showing the same picture again sends nothing.
 */
class Pixels(val width: Int, val height: Int, private val argb: IntArray) {
    init {
        require(width > 0 && height > 0) { "a picture is at least 1 by 1 pixels, not $width by $height" }
        require(argb.size == width * height) { "a $width by $height picture has ${width * height} pixels, not ${argb.size}" }
    }

    private val hash: Int by lazy { 31 * (31 * width + height) + argb.contentHashCode() }

    /** The pixel at column [x], row [y] (0 at the top). */
    operator fun get(x: Int, y: Int): Int = argb[y * width + x]

    /** Every pixel, a copy. */
    fun toArray(): IntArray = argb.copyOf()

    /**
     * This picture [toWidth] by [toHeight]: each new pixel the average of the old ones it covers, weighted by how
     * much of each it covers and by their alpha (so a transparent pixel's colour never bleeds into its
     * neighbours). Smaller is smooth; larger is each old pixel spread over the new ones it covers.
     */
    fun resized(toWidth: Int, toHeight: Int): Pixels {
        if (toWidth == width && toHeight == height) return this
        // Premultiplied channels, resized across, then down.
        val a = DoubleArray(width * height)
        val r = DoubleArray(width * height)
        val g = DoubleArray(width * height)
        val b = DoubleArray(width * height)
        for (i in argb.indices) {
            val p = argb[i]
            val alpha = (p ushr 24 and 0xFF) / 255.0
            a[i] = alpha
            r[i] = (p shr 16 and 0xFF) * alpha
            g[i] = (p shr 8 and 0xFF) * alpha
            b[i] = (p and 0xFF) * alpha
        }
        val across = listOf(a, r, g, b).map { resample(it, width, height, toWidth, true) }
        val down = across.map { resample(it, toWidth, height, toHeight, false) }
        val out = IntArray(toWidth * toHeight) { i ->
            val alpha = down[0][i]
            if (alpha <= 0.0) {
                0
            } else {
                fun channel(v: Double) = (v / alpha).let { kotlin.math.round(it).toInt().coerceIn(0, 255) }
                (kotlin.math.round(alpha * 255).toInt().coerceIn(0, 255) shl 24) or
                    (channel(down[1][i]) shl 16) or (channel(down[2][i]) shl 8) or channel(down[3][i])
            }
        }
        return Pixels(toWidth, toHeight, out)
    }

    override fun equals(other: Any?): Boolean = other is Pixels &&
        other.width == width &&
        other.height == height &&
        other.hash == hash &&
        other.argb.contentEquals(argb)

    override fun hashCode(): Int = hash

    override fun toString(): String = "Pixels(${width}x$height)"

    companion object {
        /** A pixel's alpha at or past which an image display draws it; below, it's left out. */
        const val OPAQUE_FROM = 128

        /** Rows (or columns, [across]) of [values], [w] by [h], resampled to [to] along that axis by area. */
        private fun resample(values: DoubleArray, w: Int, h: Int, to: Int, across: Boolean): DoubleArray {
            val from = if (across) w else h
            val out = DoubleArray(if (across) to * h else w * to)
            val scale = from.toDouble() / to
            for (n in 0 until to) {
                val start = n * scale
                val end = start + scale
                var k = start.toInt()
                while (k < end && k < from) {
                    val weight = (minOf(end, k + 1.0) - maxOf(start, k.toDouble())) / scale
                    if (across) {
                        for (y in 0 until h) out[y * to + n] += values[y * w + k] * weight
                    } else {
                        for (x in 0 until w) out[n * w + x] += values[k * w + x] * weight
                    }
                    k++
                }
            }
            return out
        }
    }
}
