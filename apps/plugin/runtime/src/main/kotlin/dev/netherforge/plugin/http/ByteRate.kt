package dev.netherforge.plugin.http

/**
 * How many bytes each key (a package's namespace) may move a minute: a
 * sliding window, as [RequestRate]'s, counted from any thread (the readers
 * count what arrives, the main thread what scripts send).
 */
class ByteRate(private val perMinute: Long, private val nanoTime: () -> Long = System::nanoTime) {
    private class Moved(val at: Long, val bytes: Long)

    private val moved = HashMap<String, ArrayDeque<Moved>>()
    private val totals = HashMap<String, Long>()

    /** Counts [bytes] for [key] and says whether that's within the limit; bytes past it aren't counted. */
    @Synchronized
    fun tryMove(key: String, bytes: Long): Boolean {
        val now = nanoTime()
        val window = moved.getOrPut(key, ::ArrayDeque)
        var total = totals[key] ?: 0L
        while (window.isNotEmpty() && now - window.first().at >= MINUTE) total -= window.removeFirst().bytes
        if (total + bytes > perMinute) {
            totals[key] = total
            return false
        }
        window.addLast(Moved(now, bytes))
        totals[key] = total + bytes
        return true
    }

    private companion object {
        const val MINUTE = 60_000_000_000L
    }
}
