package dev.netherforge.format.image

/**
 * Writes a PNG, for the pictures a build makes itself (the engine's pixel glyphs). Uncompressed (stored deflate
 * blocks), which costs nothing for the few small pictures it's for and needs no zlib on either platform.
 */
object PngWriter {
    /** [pixels] as an 8-bit RGBA PNG. */
    fun write(pixels: Pixels): ByteArray {
        val raw = ByteBuilder()
        for (y in 0 until pixels.height) {
            // Filter type 0 (none) before each row.
            raw.byte(0)
            for (x in 0 until pixels.width) {
                val p = pixels[x, y]
                raw.byte(p shr 16)
                raw.byte(p shr 8)
                raw.byte(p)
                raw.byte(p ushr 24)
            }
        }
        val out = ByteBuilder()
        SIGNATURE.forEach { out.byte(it) }
        out.chunk("IHDR") {
            int(pixels.width)
            int(pixels.height)
            // 8 bits per channel, colour type 6 (RGBA), deflate, adaptive filtering, no interlace.
            byte(8)
            byte(6)
            byte(0)
            byte(0)
            byte(0)
        }
        out.chunk("IDAT") { zlibStored(raw.toByteArray()) }
        out.chunk("IEND") {}
        return out.toByteArray()
    }

    private val SIGNATURE = intArrayOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private const val STORED_BLOCK = 65535

    private class ByteBuilder {
        private var bytes = ByteArray(64)
        var size = 0
            private set

        fun byte(value: Int) {
            if (size == bytes.size) bytes = bytes.copyOf(bytes.size * 2)
            bytes[size++] = value.toByte()
        }

        fun int(value: Int) {
            byte(value ushr 24)
            byte(value ushr 16)
            byte(value ushr 8)
            byte(value)
        }

        fun bytes(from: ByteArray, start: Int = 0, end: Int = from.size) {
            for (i in start until end) byte(from[i].toInt())
        }

        fun toByteArray(): ByteArray = bytes.copyOf(size)

        /** A chunk: its length, its type and [body], and the CRC of the type and body. */
        fun chunk(type: String, body: ByteBuilder.() -> Unit) {
            val data = ByteBuilder().apply(body).toByteArray()
            int(data.size)
            val typed = ByteBuilder()
            type.forEach { typed.byte(it.code) }
            typed.bytes(data)
            val crcd = typed.toByteArray()
            bytes(crcd)
            int(crc32(crcd))
        }

        /** [data] as a zlib stream of stored (uncompressed) deflate blocks. */
        fun zlibStored(data: ByteArray) {
            byte(0x78)
            byte(0x01)
            var at = 0
            do {
                val length = minOf(STORED_BLOCK, data.size - at)
                val last = at + length >= data.size
                byte(if (last) 1 else 0)
                byte(length)
                byte(length shr 8)
                byte(length.inv())
                byte(length.inv() shr 8)
                bytes(data, at, at + length)
                at += length
            } while (!last)
            int(adler32(data))
        }
    }

    private val CRC_TABLE = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) (0xEDB88320.toInt() xor (c ushr 1)) else c ushr 1 }
        c
    }

    private fun crc32(data: ByteArray): Int {
        var c = -1
        for (b in data) c = CRC_TABLE[(c xor b.toInt()) and 0xFF] xor (c ushr 8)
        return c.inv()
    }

    private fun adler32(data: ByteArray): Int {
        var a = 1
        var b = 0
        for (byte in data) {
            a = (a + (byte.toInt() and 0xFF)) % 65521
            b = (b + a) % 65521
        }
        return (b shl 16) or a
    }
}
