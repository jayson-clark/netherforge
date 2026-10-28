package dev.netherforge.network

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** A line longer than the reader's limit; the rest of it is never read. */
class LineTooLongException(val limit: Int) : IOException("a line longer than $limit bytes")

/**
 * Reads newline-delimited lines from [input], never holding more than [limit] bytes of one, so the other end can't
 * make this one buffer an endless line. [limit] can change between lines: it grows once the handshake is done.
 */
class LineReader(input: InputStream, var limit: Int) {
    private val input = BufferedInputStream(input)
    private val line = ByteArrayOutputStream()

    /** The next line without its ending, or null at the end of the stream. @throws LineTooLongException */
    fun readLine(): String? {
        line.reset()
        while (true) {
            val byte = input.read()
            if (byte < 0) {
                if (line.size() == 0) return null
                break
            }
            if (byte == '\n'.code) break
            if (line.size() >= limit) throw LineTooLongException(limit)
            line.write(byte)
        }
        return line.toString(Charsets.UTF_8).removeSuffix("\r")
    }
}
