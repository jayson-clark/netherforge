package dev.netherforge.plugin.project

import dev.netherforge.plugin.screen.NfvReader
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.file.Files
import java.util.zip.CRC32
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pictures and videos a package holds are read by what they are, not by what their header says. */
class PicturesTest {
    /** A PNG that's only a header saying it's [width] by [height]: what a decompression bomb starts with. */
    private fun pngHeader(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        val ihdr = ByteArrayOutputStream().also { chunk ->
            DataOutputStream(chunk).apply {
                writeBytes("IHDR")
                writeInt(width)
                writeInt(height)
                write(byteArrayOf(8, 6, 0, 0, 0))
            }
        }.toByteArray()
        data.writeInt(ihdr.size - 4)
        data.write(ihdr)
        data.writeInt(CRC32().apply { update(ihdr) }.value.toInt())
        return out.toByteArray()
    }

    @Test
    fun `a picture that says it's huge isn't decoded`() {
        assertNull(Pictures.decode(pngHeader(50_000, 50_000)))
        assertNull(Pictures.decode(pngHeader(Pictures.MAX_SIDE + 1, 1)))
        assertNull(Pictures.decode(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `an ordinary picture is`() {
        val out = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(16, 8, BufferedImage.TYPE_INT_ARGB), "png", out)
        val image = Pictures.decode(out.toByteArray())!!
        assertEquals(16 to 8, image.width to image.height)
    }

    @Test
    fun `a video whose header counts more frames than it holds isn't opened`() {
        val file = Files.createTempFile("nfv", ".nfv")
        val header = java.nio.ByteBuffer.allocate(NfvReader.HEADER_SIZE)
        header.put(NfvReader.MAGIC)
        header.putShort(NfvReader.VERSION.toShort())
        header.putShort(1).putShort(1).putShort(10)
        header.putInt(0x0FFFFFFF)
        header.putShort(1)
        Files.write(file, header.array())
        val e = assertFailsWith<IOException> { NfvReader.open(file).close() }
        assertTrue("more than the file holds" in e.message.orEmpty(), e.message)
    }
}
