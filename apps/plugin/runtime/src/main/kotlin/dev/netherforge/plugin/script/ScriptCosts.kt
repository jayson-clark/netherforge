package dev.netherforge.plugin.script

import dev.netherforge.plugin.lua.LuaHost
import dev.netherforge.plugin.lua.ScopeCost
import dev.netherforge.plugin.session.RuntimeService
import dev.netherforge.plugin.session.TickPhase

/**
 * What each scope's code costs the server, for `/nf scripts`
 * and for warning about a script that's slowing the server down.
 *
 * The prelude's budget frames time every call in (a body, a handler, a
 * timer, a task's resume, a command), each frame its own time less the
 * frames nested in it, and charge it to the frame's scope; [record] takes
 * those totals once a tick and keeps the last [WINDOW] ticks per scope. Work
 * done between ticks (a reload run from the bridge) lands in the next tick.
 *
 * A scope whose average over the last [PerformanceConfig.warnTicks] ticks is
 * above [PerformanceConfig.warnMillis] is reported through [onSlow], at most
 * once per [QUIET_TICKS] per scope. Its first tick (where its body's load
 * lands) is never part of that average: a slow start is a one-off, which
 * `/nf scripts` shows as its max.
 */
class ScriptCosts(
    private val scripts: Scripts,
    private val config: PerformanceConfig,
    private val onSlow: (Slow) -> Unit,
    /**
     * Other things a scope can own, counted per scope for `/nf scripts`, by
     * the word it shows them as (`"effects"` for running particle effects):
     * every service's [RuntimeService.costs].
     */
    private val counters: () -> Map<String, (Scope) -> Int> = { emptyMap() },
    /** The server tick now. */
    private val ticks: () -> Long = { 0 }
) : RuntimeService {
    override val name get() = "script costs"

    /** A scope over its time: its average over [ticks] ticks, and where its slowest recent call's function is (null when it isn't project code). */
    class Slow(val scope: Scope, val averageMillis: Double, val ticks: Int, val file: String?, val line: Int?)

    /**
     * What `/nf scripts` shows for one scope: milliseconds a tick over the
     * window, calls in, subscriptions, tasks, [counters], and about how many
     * bytes it holds (the Lua state's census, [LuaHost.memory]).
     */
    data class Entry(
        val scope: Scope,
        val averageMillis: Double,
        val maxMillis: Double,
        val calls: Int,
        val subscriptions: Int,
        val tasks: Int,
        val counts: Map<String, Int>,
        val bytes: Long
    )

    private val recent = config.warnTicks.coerceIn(1, WINDOW)

    private inner class Window {
        val nanos = LongArray(WINDOW)
        val calls = IntArray(WINDOW)
        var recorded = 0
        var sum = 0L
        var callSum = 0
        var recentSum = 0L
        var quietUntil = Long.MIN_VALUE

        fun push(time: Long, count: Int) {
            val i = recorded % WINDOW
            if (recorded >= recent) recentSum -= nanos[(recorded - recent) % WINDOW]
            if (recorded >= WINDOW) {
                sum -= nanos[i]
                callSum -= calls[i]
            }
            nanos[i] = time
            calls[i] = count
            sum += time
            callSum += count
            recentSum += time
            recorded++
        }

        val ticks: Int get() = recorded.coerceAtMost(WINDOW)
        val average: Double get() = if (ticks == 0) 0.0 else sum / ticks / NANOS_PER_MILLI
        val max: Double get() = (0 until ticks).maxOfOrNull { nanos[it] }?.let { it / NANOS_PER_MILLI } ?: 0.0
    }

    private val windows = HashMap<Int, Window>()

    /** What each scope's code took in the tick [record] last took, by scope id: only scopes that ran (the profiler reads it). */
    var lastTick: Map<Int, ScopeCost> = emptyMap()
        private set

    override fun tick(phase: TickPhase) {
        if (phase == TickPhase.ACCOUNTS) record(ticks())
    }

    /** Takes the time every scope's code took since the last tick, at the end of server tick [tick]. */
    fun record(tick: Long) {
        val host = scripts.host
        if (host == null) {
            windows.clear()
            lastTick = emptyMap()
            return
        }
        val costs = host.takeCosts(rotate = tick % recent == 0L)
        lastTick = costs
        windows.keys.retainAll { scripts.scope(it)?.live == true }
        val limit = config.warnMillis * NANOS_PER_MILLI * recent
        for (scope in scripts.scopes()) {
            if (!scope.live) continue
            val window = windows.getOrPut(scope.id) { Window() }
            val cost = costs[scope.id]
            window.push(cost?.nanos ?: 0L, cost?.calls ?: 0)
            if (config.warnMillis > 0 && window.recorded > recent && window.recentSum > limit && tick >= window.quietUntil) {
                window.quietUntil = tick + QUIET_TICKS
                val spot = host.hotSpot(scope.id)
                onSlow(Slow(scope, window.recentSum / recent / NANOS_PER_MILLI, recent, spot?.first, spot?.second))
            }
        }
    }

    /** Every live scope's costs, the most expensive first. */
    fun report(): List<Entry> {
        val counts = scripts.host?.scopeCounts().orEmpty()
        val counters = counters()
        val memory = scripts.host?.memory().orEmpty()
        return scripts.scopes().filter { it.live }.map { scope ->
            val window = windows[scope.id]
            val (subscriptions, tasks) = counts[scope.id] ?: (0 to 0)
            Entry(
                scope,
                window?.average ?: 0.0,
                window?.max ?: 0.0,
                window?.callSum ?: 0,
                subscriptions,
                tasks,
                counters.mapValues { (_, count) -> count(scope) },
                memory[scope.id] ?: 0L
            )
        }.sortedWith(compareByDescending<Entry> { it.averageMillis }.thenByDescending { it.maxMillis })
    }

    companion object {
        /** Ticks of history kept per scope: what `/nf scripts` averages over. */
        const val WINDOW = 100

        /** Ticks after warning about a scope before it's warned about again. */
        const val QUIET_TICKS = 1200L

        private const val NANOS_PER_MILLI = 1_000_000.0
    }
}
