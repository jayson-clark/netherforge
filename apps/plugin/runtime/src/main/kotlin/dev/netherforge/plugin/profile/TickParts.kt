package dev.netherforge.plugin.profile

/**
 * What a step of the tick is made of, by name (`world/centities`,
 * `world/centities/sync`): each service's share of each step, and finer parts
 * a service times itself. Only while the profiler measures ([active]); then
 * every part costs two reads of the clock. A part's time is the sum over the
 * tick of every [time] (or [add]) under its name, so parts that nest add up to
 * no more than their parent.
 */
class TickParts(private val clock: () -> Long = System::nanoTime) {
    /** Whether to time parts at all: set by the session at the start of each tick. */
    var active: Boolean = false

    private val nanos = LinkedHashMap<String, Long>()

    /** Runs [block], adding its time to [name] while [active]. */
    inline fun <T> time(name: String, block: () -> T): T {
        if (!active) return block()
        val from = now()
        try {
            return block()
        } finally {
            add(name, now() - from)
        }
    }

    fun now(): Long = clock()

    fun add(name: String, time: Long) {
        nanos[name] = (nanos[name] ?: 0L) + time
    }

    /** This tick's parts, emptied for the next. */
    fun take(): Map<String, Long> {
        if (nanos.isEmpty()) return emptyMap()
        val taken = LinkedHashMap(nanos)
        nanos.clear()
        return taken
    }

    companion object {
        /** Parts nobody measures: for a service made without a profiler (a test's). */
        val OFF = TickParts()
    }
}
