package dev.netherforge.plugin.wasm

import dev.netherforge.plugin.binding.LuaHandle
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/** Why a program ended, as its `end` event's `reason` says it. */
enum class ProgramEnd(val luaName: String) {
    EXITED("exited"),
    STOPPED("stopped"),
    FAILED("failed"),
    LIMIT("limit"),
    UNLOADED("unloaded")
}

/** What a program's thread hands the main thread: a line it printed, or its end. */
sealed interface ProgramOutput {
    data class Line(val text: String, val stream: String) : ProgramOutput

    data class Ended(val reason: ProgramEnd, val exitCode: Int? = null, val detail: String? = null) : ProgramOutput
}

/** Where the lines scripts `send` a program go, until its input is closed. */
sealed interface ProgramInputs {
    /** Whether scripts may still send (main thread's view). */
    val open: Boolean

    /**
     * Queues [line] for the program; false when it already holds as many lines as it may that it hasn't read
     * (`wasm.max-queued-lines`): a program that doesn't read mustn't make the server keep everything sent to it.
     */
    fun send(line: String): Boolean

    /** The end of its input, after what's queued. False when it was already closed. */
    fun finish(): Boolean
}

/**
 * What a test's stand-in hears in place of a program's standard input: lines waiting for its `input` function,
 * which [WasmStandIns] hands them to each tick (main thread only).
 */
class StandInInput(private val capacity: Int) : ProgramInputs {
    val lines = ArrayDeque<String>()

    /** Whether the input was closed, and whether the stand-in's program has been ended for it. */
    var finished = false
        private set
    var answeredEnd = false

    override val open: Boolean get() = !finished

    override fun send(line: String): Boolean {
        if (lines.size >= capacity) return false
        lines += line
        return true
    }

    override fun finish(): Boolean {
        if (finished) return false
        finished = true
        return true
    }
}

/**
 * One program started with `nf.wasm.start`, its [owner] scope's. Its thread ([WasmModules] runs it) reads [input]
 * and writes lines into [output], waiting for [room] (one permit per line, `wasm.max-queued-lines`) while scripts
 * haven't taken them: that wait is the backpressure, so nothing it prints is dropped. The main thread takes lines
 * each tick, up to `wasm.lines-per-tick`, and ends it. Only the main thread touches [ended].
 *
 * In a test run a stand-in may answer for its module: [input] is then a [StandInInput], nothing runs, and the
 * stand-in's answers are queued in [output] on the main thread.
 */
class WasmProgram(
    val number: Long,
    val handle: LuaHandle.WasmProcess,
    val owner: Int,
    val namespace: String,
    /** The module, as the server names it. */
    val key: String,
    maxQueuedLines: Int,
    val maxLineBytes: Int,
    standIn: Boolean = false
) {
    val output = ConcurrentLinkedQueue<ProgramOutput>()
    val room = Semaphore(maxQueuedLines)
    val input: ProgramInputs = if (standIn) StandInInput(maxQueuedLines) else ProgramInput(maxQueuedLines)

    /** Whether a test's stand-in answers for it, so no thread runs it. */
    val standIn: Boolean get() = input is StandInInput

    /** Set once its end has been handed to scripts (main thread). */
    var ended = false

    /** Its thread, while it runs; set and cleared under the program's lock, so [stopThread] never reaches another program. */
    private var thread: Thread? = null

    /** Runs [body] on this thread as the program's run; an interrupt that came as it finished is cleared. */
    fun <T> running(body: () -> T): T {
        synchronized(this) { thread = Thread.currentThread() }
        try {
            return body()
        } finally {
            synchronized(this) { thread = null }
            Thread.interrupted()
        }
    }

    /** Interrupts its thread, while it runs. */
    fun stopThread() = synchronized(this) { thread?.interrupt() }

    /** Whether the thread is waiting for [room] to print. */
    @Volatile private var printing = false

    /** Whether the thread has finished (or never started). */
    @Volatile var finished = false

    /**
     * Whether it can go no further on its own: done, waiting for scripts to take its lines, or waiting for input
     * when none has been sent (a line just sent, which it hasn't woken for yet, doesn't count). Tests wait for it.
     */
    val quiet: Boolean get() = finished || printing || (input as? ProgramInput)?.let { it.reading && it.drained } == true

    /** Queues a line from the thread, waiting for [room]. */
    fun printed(text: String, stream: String) {
        printing = true
        try {
            room.acquire()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("the program was stopped")
        } finally {
            printing = false
        }
        output += ProgramOutput.Line(text, stream)
    }

    /** What the program reads: the lines scripts `send`, until its input is closed. From the program's thread. */
    class ProgramInput(private val capacity: Int) :
        InputStream(),
        ProgramInputs {
        // One more than the lines it may hold: the end of its input always has room.
        private val queue = LinkedBlockingQueue<ByteArray>(capacity + 1)
        private val closed = AtomicBoolean()
        private var current: ByteArray? = null
        private var at = 0

        /** Whether the thread is waiting to read. */
        @Volatile var reading = false
            private set

        override val open: Boolean get() = !closed.get()

        /** Whether nothing sent is waiting to be read. */
        val drained: Boolean get() = queue.isEmpty()

        override fun send(line: String): Boolean {
            if (queue.size >= capacity) return false
            return queue.offer((line + "\n").toByteArray(StandardCharsets.UTF_8))
        }

        override fun finish(): Boolean {
            if (!closed.compareAndSet(false, true)) return false
            queue.put(END)
            return true
        }

        override fun read(): Int {
            val buffer = ByteArray(1)
            return if (read(buffer, 0, 1) < 0) -1 else buffer[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            var chunk = current
            if (chunk == null || at >= chunk.size) {
                chunk = queue.poll() ?: run {
                    reading = true
                    try {
                        queue.take()
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw InterruptedIOException("the program was stopped")
                    } finally {
                        reading = false
                    }
                }
                if (chunk === END) {
                    // Every later read is the end too.
                    queue.put(END)
                    return -1
                }
                current = chunk
                at = 0
            }
            val n = minOf(len, chunk.size - at)
            System.arraycopy(chunk, at, b, off, n)
            at += n
            return n
        }

        override fun available(): Int = current?.let { it.size - at } ?: 0

        private companion object {
            /** What's queued where the input was closed. */
            val END = ByteArray(0)
        }
    }

    /**
     * One of the program's output streams, cut into lines (LF or CRLF; a long one into pieces of at most
     * [maxLineBytes], never through a UTF-8 character: what's left of one starts the next piece).
     */
    inner class LineOutput(private val stream: String) : OutputStream() {
        private val line = ByteArrayOutputStream()

        override fun write(b: Int) {
            when {
                b == '\n'.code -> emit()
                else -> {
                    line.write(b)
                    if (line.size() >= maxLineBytes) piece()
                }
            }
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            for (i in off until off + len) write(b[i].toInt())
        }

        private fun emit() {
            val bytes = line.toByteArray()
            line.reset()
            val end = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
            printed(String(bytes, 0, end, StandardCharsets.UTF_8), stream)
        }

        /** A line too long for one: as much of it as ends on a whole character, the rest kept for the next piece. */
        private fun piece() {
            val bytes = line.toByteArray()
            val cut = Utf8.wholeCharacters(bytes)
            line.reset()
            line.write(bytes, cut, bytes.size - cut)
            printed(String(bytes, 0, cut, StandardCharsets.UTF_8), stream)
        }

        /** What's left without a line ending, at the end. */
        fun finish() {
            if (line.size() > 0) emit()
        }
    }
}

/** UTF-8's shape, for cutting bytes without cutting a character. */
internal object Utf8 {
    /**
     * How many of [bytes] end on a whole character: all of them, or fewer when the last character is cut off
     * (its lead byte and what came of it are left out). Bytes that aren't UTF-8 at all are cut where they are.
     */
    fun wholeCharacters(bytes: ByteArray): Int {
        var lead = bytes.size - 1
        // A character is at most four bytes: its lead and up to three continuations (10xxxxxx).
        while (lead >= 0 && bytes.size - lead <= 4 && bytes[lead].toInt() and 0xC0 == 0x80) lead--
        if (lead < 0 || bytes.size - lead > 4) return bytes.size
        val first = bytes[lead].toInt() and 0xFF
        val length = when {
            first < 0x80 -> 1
            first shr 5 == 0b110 -> 2
            first shr 4 == 0b1110 -> 3
            first shr 3 == 0b11110 -> 4
            else -> return bytes.size
        }
        // A whole first character is never held back, so a piece always moves on.
        return if (lead + length > bytes.size && lead > 0) lead else bytes.size
    }
}
