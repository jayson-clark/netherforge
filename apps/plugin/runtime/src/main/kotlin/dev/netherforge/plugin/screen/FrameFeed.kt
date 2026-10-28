package dev.netherforge.plugin.screen

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * A playback's frames, read and inflated ahead of where it plays on [lane] (the workers' `"videos"` lane), so the
 * main thread never touches the file: it asks for the frames it will draw next ([want]) and draws the ones that
 * are [ready]. One that isn't ready yet holds the picture for a tick, as a slow disk would show. [reader] is the
 * video's, shared by every playback of it, and only ever read on [lane], one frame at a time.
 */
class FrameFeed(private val reader: NfvReader, private val lane: Executor) {
    private val frames = ConcurrentHashMap<Int, NfvReader.Frame>()
    private val window = AtomicReference(IntRange.EMPTY)
    private val reading = AtomicBoolean()

    @Volatile private var closed = false

    /** Why reading stopped, once it has: the main thread ends the playback for it. */
    @Volatile var failure: IOException? = null
        private set

    /** Frame [index], when it has been read. */
    fun ready(index: Int): NfvReader.Frame? = frames[index]

    /** Asks for frames [from] through [through] (main thread): read in order, and the rest forgotten. */
    fun want(from: Int, through: Int) {
        if (closed) return
        val wanted = from..minOf(through, reader.header.frames - 1)
        window.set(wanted)
        frames.keys.removeIf { it !in wanted }
        if (wanted.any { !frames.containsKey(it) }) read()
    }

    /** Stops reading for it and lets go of what it read. */
    fun close() {
        closed = true
        frames.clear()
    }

    private fun read() {
        if (!reading.compareAndSet(false, true)) return
        lane.execute {
            try {
                while (!closed && failure == null) {
                    val next = window.get().firstOrNull { !frames.containsKey(it) } ?: break
                    frames[next] = reader.frame(next)
                }
            } catch (e: IOException) {
                failure = e
            } finally {
                reading.set(false)
            }
            // A want that came as it finished is read now rather than at the next want.
            if (!closed && failure == null && window.get().any { !frames.containsKey(it) }) read()
        }
    }

    companion object {
        /** How many frames past the one drawn are read ahead: a quarter of a second at 20 a second. */
        const val AHEAD = 5
    }
}
