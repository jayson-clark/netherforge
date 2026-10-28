package dev.netherforge.plugin.bridge

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** A frame longer than the cap the reader was given. The line is never fully read. */
class FrameTooLargeException(val limit: Int) : IOException("a frame longer than $limit bytes")

/**
 * Reads newline-delimited frames from [input], never holding more than
 * [limit] bytes of one: a peer can't make the plugin buffer an endless line
 * (BufferedReader.readLine would). [limit] can be changed between frames, as
 * the cap grows once the hello is accepted.
 */
class FrameReader(input: InputStream, var limit: Int) {
    private val input = BufferedInputStream(input)
    private val line = ByteArrayOutputStream()

    /** The next frame without its line ending, or null at the end of the stream. @throws FrameTooLargeException */
    fun readFrame(): String? {
        line.reset()
        while (true) {
            val byte = input.read()
            if (byte < 0) {
                if (line.size() == 0) return null
                break
            }
            if (byte == '\n'.code) break
            if (line.size() >= limit) throw FrameTooLargeException(limit)
            line.write(byte)
        }
        return line.toString(Charsets.UTF_8).removeSuffix("\r")
    }
}
