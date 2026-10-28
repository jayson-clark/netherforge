package dev.netherforge.plugin.screen

import dev.netherforge.format.screen.Dither
import dev.netherforge.format.screen.MapPalette
import java.awt.Image
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * Pictures as map colours: a PNG's pixels scaled to a size and dithered ([Dither]) into [MapPalette]'s colours,
 * an index a pixel ([Canvas.SKIP] where it's less than half opaque). The last few are kept, since a script tends
 * to draw the same picture again (every restart, every frame of a menu); a reload of the project forgets them.
 */
class ScreenImages(private val read: (path: String) -> ByteArray?) {
    /** A picture as drawn: [width] by [height], an index (or [Canvas.SKIP]) a pixel. */
    class Picture(val width: Int, val height: Int, val colors: IntArray)

    private data class Key(val path: String, val width: Int?, val height: Int?, val dither: Dither)

    private val kept = object : LinkedHashMap<Key, Picture>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Picture>?) = size > KEPT
    }

    /**
     * The picture at project path [path] at [width] by [height] (either or both null: its own, or kept in shape),
     * or null when there's no such file or it isn't a picture.
     */
    fun load(path: String, width: Int?, height: Int?, dither: Dither, palette: MapPalette): Picture? {
        val key = Key(path, width, height, dither)
        kept[key]?.let { return it }
        val bytes = read(path) ?: return null
        val image = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull() ?: return null
        val (w, h) = size(image.width, image.height, width, height)
        val scaled = scale(image, w, h)
        val argb = scaled.getRGB(0, 0, w, h, null, 0, w)
        return Picture(w, h, dither.apply(argb, w, h, palette)).also { kept[key] = it }
    }

    fun forget() = kept.clear()

    companion object {
        private const val KEPT = 32

        /** The biggest side a picture may be drawn at: a screen's widest. */
        const val MAX_SIDE = 2048

        /** The size to draw a [width] by [height] picture at, asked for as [wantWidth] by [wantHeight]. */
        fun size(width: Int, height: Int, wantWidth: Int?, wantHeight: Int?): Pair<Int, Int> {
            val w = when {
                wantWidth != null -> wantWidth
                wantHeight != null -> maxOf(1, (width.toLong() * wantHeight / maxOf(1, height)).toInt())
                else -> width
            }
            val h = when {
                wantHeight != null -> wantHeight
                wantWidth != null -> maxOf(1, (height.toLong() * wantWidth / maxOf(1, width)).toInt())
                else -> height
            }
            return w.coerceIn(1, MAX_SIDE) to h.coerceIn(1, MAX_SIDE)
        }

        /**
         * [image] at [width] by [height]: blown up by whole pixels when it's an exact multiple (pixel art stays
         * crisp), else smoothly, averaging what shrinks.
         */
        private fun scale(image: BufferedImage, width: Int, height: Int): BufferedImage {
            val out = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            if (width == image.width && height == image.height) {
                out.createGraphics().apply {
                    drawImage(image, 0, 0, null)
                    dispose()
                }
                return out
            }
            val exact = width % image.width == 0 && height % image.height == 0 && width >= image.width && height >= image.height
            val graphics = out.createGraphics()
            if (exact) {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
                graphics.drawImage(image, 0, 0, width, height, null)
            } else if (width < image.width || height < image.height) {
                graphics.drawImage(image.getScaledInstance(width, height, Image.SCALE_AREA_AVERAGING), 0, 0, null)
            } else {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                graphics.drawImage(image, 0, 0, width, height, null)
            }
            graphics.dispose()
            return out
        }
    }
}
