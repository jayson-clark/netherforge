package dev.netherforge.plugin.screen

import dev.netherforge.format.screen.Dither
import dev.netherforge.format.screen.VideoFit
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.Path
import java.util.zip.Inflater

/**
 * A video's converted frames (`videos/<id>/video.nfv`, written by the editor; `docs/format/video.md` describes
 * the layout): a header, an index of where each frame starts, and the frames, each a key frame (every pixel) or
 * the rectangles that changed since the one before, deflated. Read a frame at a time, from the file, so a long
 * video costs memory for one frame, not all of them.
 */
class NfvReader private constructor(private val file: RandomAccessFile, val header: Header, private val offsets: LongArray) : Closeable {
    /** What the editor converted the video as. */
    data class Header(
        val columns: Int,
        val rows: Int,
        val fps: Int,
        val frames: Int,
        val keyInterval: Int,
        val dither: Dither,
        val fit: VideoFit,
        val startMillis: Long,
        val endMillis: Long,
        val audio: Boolean
    ) {
        val width: Int get() = columns * Canvas.TILE
        val height: Int get() = rows * Canvas.TILE

        /** Seconds it lasts. */
        val length: Double get() = frames.toDouble() / fps
    }

    /** One frame: every pixel ([key]), or rectangles that changed, each `x, y, width, height` and its pixels. */
    class Frame(val key: Boolean, val payload: ByteArray)

    /** Frame [index], read and inflated. */
    fun frame(index: Int): Frame {
        require(index in offsets.indices) { "no frame $index of ${offsets.size}" }
        file.seek(offsets[index])
        val kind = file.readUnsignedByte()
        val length = file.readInt()
        if (length < 0 || length > MAX_FRAME) throw IOException("frame $index says it's $length bytes")
        val packed = ByteArray(length)
        file.readFully(packed)
        return Frame(kind == KEY, inflate(packed))
    }

    /** The key frame at or before [index]: where drawing frame [index] starts from. */
    fun keyBefore(index: Int): Int = index - index % header.keyInterval

    /** Draws [frame] onto [canvas] with the video's top left at ([x], [y]); what falls off the canvas is left out. */
    fun draw(frame: Frame, canvas: Canvas, x: Int, y: Int) {
        val payload = frame.payload
        if (frame.key) {
            val w = header.width
            val row = IntArray(w)
            for (py in 0 until header.height) {
                for (px in 0 until w) row[px] = payload[py * w + px].toInt() and 0xFF
                canvas.write(x, y + py, row, 0, w)
            }
            return
        }
        val buffer = ByteBuffer.wrap(payload)
        while (buffer.remaining() >= 8) {
            val rx = buffer.short.toInt() and 0xFFFF
            val ry = buffer.short.toInt() and 0xFFFF
            val rw = buffer.short.toInt() and 0xFFFF
            val rh = buffer.short.toInt() and 0xFFFF
            if (rw * rh > buffer.remaining()) throw IOException("a frame's rectangle runs past its end")
            val row = IntArray(rw)
            for (py in 0 until rh) {
                for (px in 0 until rw) row[px] = buffer.get().toInt() and 0xFF
                canvas.write(x + rx, y + ry + py, row, 0, rw)
            }
        }
    }

    override fun close() = file.close()

    companion object {
        val MAGIC = byteArrayOf('N'.code.toByte(), 'F'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte())
        const val VERSION = 1
        const val HEADER_SIZE = 72
        const val KEY = 0
        const val DELTA = 1

        /** No frame of a 16 by 16 map video is bigger, deflated or not. */
        private const val MAX_FRAME = 16 * 16 * Canvas.TILE * Canvas.TILE + 1024 * 1024

        /** Opens [path], reading its header and index; an [IOException] when it isn't a video file this reads. */
        fun open(path: Path): NfvReader {
            val file = RandomAccessFile(path.toFile(), "r")
            try {
                val head = ByteArray(HEADER_SIZE)
                file.readFully(head)
                val buffer = ByteBuffer.wrap(head)
                val magic = ByteArray(4).also(buffer::get)
                if (!magic.contentEquals(MAGIC)) throw IOException("it isn't a converted video (no NFV1 at its start)")
                val version = buffer.short.toInt() and 0xFFFF
                if (version !=
                    VERSION
                ) {
                    throw IOException("it's a version $version video file, and this reads version $VERSION: convert it again")
                }
                val columns = buffer.short.toInt() and 0xFFFF
                val rows = buffer.short.toInt() and 0xFFFF
                val fps = buffer.short.toInt() and 0xFFFF
                val frames = buffer.int
                val keyInterval = buffer.short.toInt() and 0xFFFF
                val dither = Dither.entries.getOrNull(buffer.get().toInt()) ?: Dither.NONE
                val fit = VideoFit.entries.getOrNull(buffer.get().toInt()) ?: VideoFit.CONTAIN
                val start = buffer.int.toLong() and 0xFFFFFFFFL
                val end = buffer.int.toLong() and 0xFFFFFFFFL
                val audio = buffer.get().toInt() != 0
                if (columns !in 1..16 || rows !in 1..16 || fps !in 1..20 || frames < 0 || keyInterval < 1) {
                    throw IOException("its header doesn't make sense ($columns by $rows maps, $fps fps, $frames frames)")
                }
                val index = ByteArray(frames * 8)
                file.seek(HEADER_SIZE.toLong())
                file.readFully(index)
                val offsets = ByteBuffer.wrap(index).let { b -> LongArray(frames) { b.long } }
                val header = Header(columns, rows, fps, frames, keyInterval, dither, fit, start, end, audio)
                return NfvReader(file, header, offsets)
            } catch (e: Exception) {
                file.close()
                throw e as? IOException ?: IOException(e.message, e)
            }
        }

        private fun inflate(packed: ByteArray): ByteArray {
            val inflater = Inflater()
            try {
                inflater.setInput(packed)
                val out = ByteArrayOutputStream(packed.size * 4)
                val chunk = ByteArray(64 * 1024)
                while (!inflater.finished()) {
                    val n = inflater.inflate(chunk)
                    if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) throw IOException("a frame is cut short")
                    out.write(chunk, 0, n)
                    if (out.size() > MAX_FRAME) throw IOException("a frame inflates past what a video can hold")
                }
                return out.toByteArray()
            } finally {
                inflater.end()
            }
        }
    }
}
