package dev.netherforge.plugin.http

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** How `nf.http.stream` splits a response into messages (`HttpStreamOptions.format`). */
enum class StreamFormat(val luaName: String) {
    LINES("lines"),
    EVENTS("events"),
    CHUNKS("chunks");

    companion object {
        fun of(luaName: String?): StreamFormat = entries.firstOrNull { it.luaName == luaName } ?: LINES
    }
}

/** A line or event longer than the owner allows: the stream ends `"limit"`. */
class MessageTooLarge(val limit: Int) : Exception("a message is larger than http.connections.max-message-bytes ($limit bytes)")

/**
 * Splits a response body, fed as it arrives in pieces of any size, into the
 * messages a stream hands scripts: lines (LF, CRLF or a lone CR end one),
 * Server-Sent Events (the `text/event-stream` rules: `data:` lines joined,
 * `event:` and `id:` kept, comments and `retry:` skipped, an event at each
 * blank line) or decoded chunks. Text is UTF-8, decoded leniently (a bad byte
 * is U+FFFD), and a character split between two pieces is decoded whole.
 *
 * Used by one thread at a time: the reader's, or the test's on the main thread.
 */
class StreamSplitter(
    private val format: StreamFormat,
    private val maxBytes: Int,
    /** One message: its data, and for events its type and the stream's last id. */
    private val emit: (data: String, type: String?, id: String?) -> Unit
) {
    private val line = ByteArrayOutputStream()
    private var afterCr = false

    // Server-Sent Events: the event being built and the stream's last id.
    private val data = StringBuilder()
    private var hasData = false
    private var dataBytes = 0
    private var type: String? = null
    private var lastId: String? = null

    private val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var pending = ByteBuffer.allocate(0)

    /** Takes [count] more bytes of the body from [bytes]. Throws [MessageTooLarge]. */
    fun feed(bytes: ByteArray, count: Int = bytes.size) {
        if (format == StreamFormat.CHUNKS) {
            chunk(bytes, count, false)
            return
        }
        for (i in 0 until count) {
            val b = bytes[i]
            when {
                b == CR -> {
                    endLine()
                    afterCr = true
                }
                b == LF && afterCr -> afterCr = false
                b == LF -> endLine()
                else -> {
                    afterCr = false
                    if (line.size() >= maxBytes) throw MessageTooLarge(maxBytes)
                    line.write(b.toInt())
                }
            }
        }
    }

    /** The body ended: a last line without its ending is a line; a half-built event is dropped, as the SSE rules say. */
    fun finish() {
        when (format) {
            StreamFormat.CHUNKS -> chunk(ByteArray(0), 0, true)
            StreamFormat.LINES -> if (line.size() > 0) endLine()
            StreamFormat.EVENTS -> Unit
        }
    }

    private fun endLine() {
        val text = String(line.toByteArray(), StandardCharsets.UTF_8)
        line.reset()
        if (format == StreamFormat.LINES) emit(text, null, null) else eventLine(text)
    }

    private fun eventLine(text: String) {
        if (text.isEmpty()) {
            dispatch()
            return
        }
        if (text.startsWith(":")) return
        val colon = text.indexOf(':')
        val field = if (colon < 0) text else text.substring(0, colon)
        val value = if (colon < 0) "" else text.substring(colon + 1).removePrefix(" ")
        when (field) {
            "data" -> {
                if (hasData) data.append('\n')
                dataBytes += value.toByteArray(StandardCharsets.UTF_8).size + if (hasData) 1 else 0
                if (dataBytes > maxBytes) throw MessageTooLarge(maxBytes)
                data.append(value)
                hasData = true
            }
            "event" -> type = value
            "id" -> if ('\u0000' !in value) lastId = value
        }
    }

    private fun dispatch() {
        if (hasData) emit(data.toString(), type?.takeIf { it.isNotEmpty() } ?: "message", lastId)
        data.setLength(0)
        dataBytes = 0
        hasData = false
        type = null
    }

    private fun chunk(bytes: ByteArray, count: Int, last: Boolean) {
        val input = ByteBuffer.allocate(pending.remaining() + count)
        input.put(pending).put(bytes, 0, count).flip()
        val out = CharBuffer.allocate(input.remaining() + 1)
        decoder.decode(input, out, last)
        if (last) decoder.flush(out)
        pending = ByteBuffer.allocate(input.remaining()).put(input).flip()
        out.flip()
        if (out.hasRemaining()) emit(out.toString(), null, null)
    }

    private companion object {
        const val CR: Byte = '\r'.code.toByte()
        const val LF: Byte = '\n'.code.toByte()
    }
}
