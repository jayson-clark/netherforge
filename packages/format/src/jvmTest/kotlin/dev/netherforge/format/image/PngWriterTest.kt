package dev.netherforge.format.image

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

class PngWriterTest {
    @Test
    fun aWrittenPngReadsBackAsItsPixels() {
        // Big enough for more than one stored block.
        val width = 300
        val height = 80
        val pixels = Pixels(width, height, IntArray(width * height) { (it * 2654435761L).toInt() or 0x01000000 })
        val read = ImageIO.read(ByteArrayInputStream(PngWriter.write(pixels)))
        assertEquals(width, read.width)
        assertEquals(height, read.height)
        for (y in 0 until height) for (x in 0 until width) assertEquals(pixels[x, y], read.getRGB(x, y), "pixel $x, $y")
    }
}
