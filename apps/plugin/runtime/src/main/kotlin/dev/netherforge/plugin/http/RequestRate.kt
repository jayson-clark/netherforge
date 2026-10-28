package dev.netherforge.plugin.http

/**
 * How many requests each key (a package's namespace) may start a minute: a
 * sliding window, so a burst at the end of one minute doesn't get a second
 * one at the start of the next. Used on the main thread only.
 */
class RequestRate(private val perMinute: Int, private val nanoTime: () -> Long = System::nanoTime) {
    private val started = HashMap<String, ArrayDeque<Long>>()

    /** Counts a request for [key] and says whether it's within the limit; one past it isn't counted. */
    fun tryStart(key: String): Boolean {
        val now = nanoTime()
        val times = started.getOrPut(key, ::ArrayDeque)
        while (times.isNotEmpty() && now - times.first() >= MINUTE) times.removeFirst()
        if (times.size >= perMinute) return false
        times.addLast(now)
        return true
    }

    private companion object {
        const val MINUTE = 60_000_000_000L
    }
}
