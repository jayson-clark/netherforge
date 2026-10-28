package dev.netherforge.plugin

import dev.netherforge.plugin.screen.NfvReader
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.Deflater

/**
 * Converted videos for tests, written as the editor writes them (`docs/format/video.md`): [frames] each a whole
 * screen of map colours ([columns] by [rows] maps, row by row), a key frame every [keyInterval] and the
 * rectangle that changed in between.
 */
object NfvFiles {
    fun write(columns: Int, rows: Int, fps: Int, frames: List<ByteArray>, keyInterval: Int = 4, audio: Boolean = false): ByteArray {
        val width = columns * 128
        val height = rows * 128
        val records = frames.mapIndexed { index, frame ->
            require(frame.size == width * height) { "frame $index is ${frame.size} bytes, not ${width * height}" }
            if (index % keyInterval == 0) {
                NfvReader.KEY to frame
            } else {
                NfvReader.DELTA to delta(frames[index - 1], frame, width, height)
            }
        }
        val out = ByteArrayOutputStream()
        val header = ByteBuffer.allocate(NfvReader.HEADER_SIZE)
        header.put(NfvReader.MAGIC)
        header.putShort(NfvReader.VERSION.toShort())
        header.putShort(columns.toShort())
        header.putShort(rows.toShort())
        header.putShort(fps.toShort())
        header.putInt(frames.size)
        header.putShort(keyInterval.toShort())
        header.put(1)
        header.put(0)
        header.putInt(0)
        header.putInt(0)
        header.put(if (audio) 1 else 0)
        out.write(header.array())
        val packed = records.map { (kind, payload) -> kind to deflate(payload) }
        var offset = NfvReader.HEADER_SIZE.toLong() + frames.size * 8L
        val index = ByteBuffer.allocate(frames.size * 8)
        for ((_, payload) in packed) {
            index.putLong(offset)
            offset += 5 + payload.size
        }
        out.write(index.array())
        for ((kind, payload) in packed) {
            out.write(kind)
            out.write(ByteBuffer.allocate(4).putInt(payload.size).array())
            out.write(payload)
        }
        return out.toByteArray()
    }

    /** One rectangle round every pixel that changed (none when nothing did). */
    private fun delta(before: ByteArray, after: ByteArray, width: Int, height: Int): ByteArray {
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (i in after.indices) {
            if (after[i] == before[i]) continue
            val x = i % width
            val y = i / width
            left = minOf(left, x)
            right = maxOf(right, x)
            top = minOf(top, y)
            bottom = maxOf(bottom, y)
        }
        if (right < 0) return ByteArray(0)
        val w = right - left + 1
        val h = bottom - top + 1
        val buffer = ByteBuffer.allocate(8 + w * h)
        buffer.putShort(left.toShort()).putShort(top.toShort()).putShort(w.toShort()).putShort(h.toShort())
        for (y in top..bottom) buffer.put(after, y * width + left, w)
        return buffer.array()
    }

    private fun deflate(bytes: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(bytes)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (!deflater.finished()) out.write(chunk, 0, deflater.deflate(chunk))
        deflater.end()
        return out.toByteArray()
    }

    /** [count] frames of one map each a single colour index, frame i colour [colors] (cycled). */
    fun solid(count: Int, vararg colors: Int, columns: Int = 1, rows: Int = 1): List<ByteArray> =
        (0 until count).map { i -> ByteArray(columns * rows * 128 * 128) { colors[i % colors.size].toByte() } }
}
