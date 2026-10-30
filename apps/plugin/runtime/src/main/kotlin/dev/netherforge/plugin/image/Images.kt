package dev.netherforge.plugin.image

import dev.netherforge.format.image.Pixels
import dev.netherforge.format.image.PngWriter
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO

/**
 * The pictures scripts hold (`nf.images`), by the number their `Image` handle is keyed by. A picture is let go
 * of when Lua lets go of its handle ([release], from the handle table's sweep), so what's here is what scripts
 * can still reach; an image display keeps the [Pixels] it shows itself. Thread-safe, since a download is
 * decoded and kept off the main thread.
 *
 * [MAX_HELD] bounds what scripts hold at once; [hold] past it first asks [collect] to let go of what Lua has
 * dropped but not yet collected, and only then refuses.
 */
class Images(private val collect: () -> Unit = {}) {
    private val held = ConcurrentHashMap<Long, Pixels>()
    private val next = AtomicLong(1)
    private val pixels = AtomicLong(0)

    /** The picture behind handle number [number], or null once it's let go of. */
    operator fun get(number: Long): Pixels? = held[number]

    /** How many pixels scripts hold now. */
    val heldPixels: Long get() = pixels.get()

    /** Keeps [image] and gives the number its handle is keyed by; [TooMany] when scripts hold too much already. */
    fun hold(image: Pixels): Long {
        val size = image.width.toLong() * image.height
        if (pixels.get() + size > MAX_HELD) collect()
        if (pixels.addAndGet(size) > MAX_HELD) {
            pixels.addAndGet(-size)
            throw TooMany()
        }
        val number = next.getAndIncrement()
        held[number] = image
        return number
    }

    /** Lua let go of handle [number]. */
    fun release(number: Long) {
        held.remove(number)?.let { pixels.addAndGet(-(it.width.toLong() * it.height)) }
    }

    /** Every picture let go of: the session is ending. */
    fun clear() {
        held.clear()
        pixels.set(0)
    }

    /** Scripts hold [MAX_HELD] pixels already. */
    class TooMany :
        RuntimeException(
            "scripts hold ${MAX_HELD / 1_000_000} million pixels of images already: let go of images you no longer need"
        )

    companion object {
        /** The most pixels an image has either way. */
        const val MAX_SIDE = 2048

        /** The most pixels the images scripts hold have in all. */
        const val MAX_HELD = 32_000_000L

        /** Whether [width] by [height] is an image's size. */
        fun fits(width: Long, height: Long): Boolean = width in 1..MAX_SIDE && height in 1..MAX_SIDE

        /**
         * An image file's [bytes] (whatever ImageIO reads: PNG, JPEG, GIF, BMP) as pixels, or null when it isn't one
         * or is bigger than [MAX_SIDE] either way (checked from its header, before its pixels are decoded).
         */
        fun decode(bytes: ByteArray): Pixels? {
            val stream = ImageIO.createImageInputStream(ByteArrayInputStream(bytes)) ?: return null
            stream.use {
                val reader = ImageIO.getImageReaders(it).asSequence().firstOrNull() ?: return null
                try {
                    reader.input = it
                    if (!fits(reader.getWidth(0).toLong(), reader.getHeight(0).toLong())) return null
                    return pixelsOf(reader.read(0))
                } catch (e: Exception) {
                    return null
                } finally {
                    reader.dispose()
                }
            }
        }

        private fun pixelsOf(image: BufferedImage): Pixels =
            Pixels(image.width, image.height, image.getRGB(0, 0, image.width, image.height, null, 0, image.width))

        /** [image] as a PNG file. */
        fun encode(image: Pixels): ByteArray {
            val buffered = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
            buffered.setRGB(0, 0, image.width, image.height, image.toArray(), 0, image.width)
            val out = java.io.ByteArrayOutputStream()
            // ImageIO compresses; the format's writer is for the few tiny pictures the pack builds.
            if (!ImageIO.write(buffered, "png", out)) return PngWriter.write(image)
            return out.toByteArray()
        }

        /** `#RRGGBB` (opaque) or `#AARRGGBB` as a pixel, or null when it's neither. */
        fun color(text: String): Int? {
            if (!text.startsWith("#") || (text.length != 7 && text.length != 9)) return null
            val value = text.substring(1).toLongOrNull(16) ?: return null
            return if (text.length == 7) (0xFF000000L or value).toInt() else value.toInt()
        }

        /** A pixel as `#RRGGBB`, or `#AARRGGBB` when it isn't opaque. */
        fun colorName(pixel: Int): String = if (pixel ushr 24 == 0xFF) {
            "#%06X".format(pixel and 0xFFFFFF)
        } else {
            "#%08X".format(pixel.toLong() and 0xFFFFFFFFL)
        }
    }
}
