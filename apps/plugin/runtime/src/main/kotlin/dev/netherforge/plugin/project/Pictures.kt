package dev.netherforge.plugin.project

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.IOException
import javax.imageio.ImageIO

/**
 * Decoding a picture a project or package holds, never trusting what its
 * header says it is. ImageIO makes the whole image before reading a pixel, so
 * a PNG of a few kilobytes that says it's 50,000 pixels square asks for ten
 * gigabytes: the size is read first ([MAX_SIDE], [MAX_PIXELS]), and a picture
 * past it is refused as if it weren't one.
 */
object Pictures {
    /** The widest or tallest picture read: past what any texture, font sheet or screen picture needs. */
    const val MAX_SIDE = 8192

    /** The most pixels a picture read may have (a 4096 by 4096 one, at four bytes each, is 64 MB). */
    const val MAX_PIXELS = 4096L * 4096L

    /** [bytes] as a picture, or null when it isn't one, or says it's bigger than this reads. */
    fun decode(bytes: ByteArray): BufferedImage? {
        val input = ImageIO.createImageInputStream(ByteArrayInputStream(bytes)) ?: return null
        input.use {
            val readers = ImageIO.getImageReaders(input)
            if (!readers.hasNext()) return null
            val reader = readers.next()
            try {
                reader.setInput(input, true, true)
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                if (width <= 0 || height <= 0 || width > MAX_SIDE || height > MAX_SIDE) return null
                if (width.toLong() * height > MAX_PIXELS) return null
                return reader.read(0)
            } catch (_: IOException) {
                return null
            } catch (_: RuntimeException) {
                return null
            } finally {
                reader.dispose()
            }
        }
    }
}
