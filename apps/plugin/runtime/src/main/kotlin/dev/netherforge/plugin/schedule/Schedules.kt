package dev.netherforge.plugin.schedule

import dev.netherforge.format.project.Names
import dev.netherforge.plugin.RuntimeLog
import dev.netherforge.plugin.lua.CallResult
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.lua.LuaFunction
import dev.netherforge.plugin.script.Scope
import dev.netherforge.plugin.script.Scripts
import dev.netherforge.plugin.session.RuntimeService
import dev.netherforge.plugin.session.SessionProject
import dev.netherforge.plugin.session.TickPhase
import dev.netherforge.plugin.store.Store
import java.time.Clock
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId

/**
 * `nf.schedule`: functions that run at a time of day, a weekday or a cron
 * expression, in the server owner's time zone ([ScheduleConfig]).
 *
 * Each tick's [TickPhase.TIMERS] step compares the wall clock with every
 * schedule's next run and calls those that are due, on the main thread, as
 * their script's code (the profiler times them as `schedule`). A schedule
 * that is due runs once however late the tick is: the runs it slept through
 * are skipped, and its next is the first one after now.
 *
 * A schedule with an id remembers when it last ran, per package, in the store
 * ([Store.ScheduleRuns]). With `catch_up` a schedule that finds its next run
 * after that already past, when its script starts, runs once at once: the
 * server was off at its time. A schedule seen for the first time counts as
 * run when it's made, so a restart before its first run catches up that one.
 * Without `catch_up` the missed run is skipped (the last-run time is still
 * kept, so turning it on later starts from what really happened).
 */
internal class Schedules(
    private val scripts: Scripts,
    private val store: Store.ScheduleRuns,
    private val clock: () -> Clock,
    private val timeZone: String?,
    private val log: RuntimeLog
) : RuntimeService {
    override val name get() = "schedules"

    /** One schedule: [next] is when it runs next, in the time zone it was made in. */
    class Entry(
        val number: Long,
        val scope: Scope,
        val id: String?,
        val recurrence: Recurrence,
        val callback: LuaFunction,
        var next: Instant
    )

    private val entries = LinkedHashMap<Long, Entry>()
    private var counter = 0L

    /**
     * The server owner's zone, the server's own when none is set (or the one set isn't one). `nf.time`
     * writes and reads clock times in it too, unless given a zone.
     */
    var zone: ZoneId = ZoneId.systemDefault()
        private set

    /** When each package's schedules last ran, read from the store the first time the package makes one. */
    private val lastRuns = HashMap<String, MutableMap<String, Long>>()

    override fun define(project: SessionProject) {
        zone = resolveZone() ?: ZoneId.systemDefault()
        lastRuns.clear()
    }

    private fun resolveZone(): ZoneId? {
        val name = timeZone ?: return null
        return try {
            ZoneId.of(name)
        } catch (e: DateTimeException) {
            log.warn(
                "config.yml's schedules.time-zone \"$name\" isn't a time zone (try \"Europe/London\", \"UTC\" or \"+02:00\"): " +
                    "schedules use the server's own, $zone"
            )
            null
        }
    }

    /**
     * Starts a schedule for [scope]. A mistake in [id] or [catchUp] is a
     * [LuaApiException]; [callback] is let go of when that is thrown.
     */
    fun create(scope: Scope, recurrence: Recurrence, callback: LuaFunction, id: String?, catchUp: Boolean): Entry {
        try {
            if (id != null && !Names.isId(id)) {
                throw LuaApiException("options.id: \"$id\" can't name a schedule (${Names.ID_RULE})")
            }
            if (catchUp && id == null) {
                throw LuaApiException("options.catch_up needs an options.id: that's what the schedule's last run is kept under")
            }
            if (id != null && entries.values.any { it.id == id && it.scope.namespace == scope.namespace }) {
                throw LuaApiException(
                    "there's already a schedule with the id \"$id\" in this package (cancel it first, or give this one another)"
                )
            }
        } catch (e: LuaApiException) {
            scripts.unref(callback.ref)
            throw e
        }
        val now = clock().instant()
        val runs = id?.let { lastRuns.getOrPut(scope.namespace) { store.of(scope.namespace).toMutableMap() } }
        val last = id?.let { runs?.get(it) }
        if (id != null && last == null) record(scope.namespace, id, now)
        val missed = catchUp && last != null && recurrence.next(Instant.ofEpochMilli(last), zone)?.let { it <= now } == true
        val next = if (missed) now else recurrence.next(now, zone)
        // A rule with no next time (a cron expression for 31 February) never runs; it's still a live schedule to cancel.
        val entry = Entry(++counter, scope, id, recurrence, callback, next ?: Instant.MAX)
        entries[entry.number] = entry
        return entry
    }

    fun get(number: Long): Entry? = entries[number]

    /** [entry]'s next run, or null when it never will. */
    fun nextRun(entry: Entry): Instant? = entry.next.takeIf { it != Instant.MAX }

    /** Every live schedule, in the order they were made. */
    fun all(): List<Entry> = entries.values.toList()

    /** Ends [entry]: it doesn't run again. */
    fun cancel(entry: Entry) {
        if (entries.remove(entry.number) == null) return
        scripts.unref(entry.callback.ref)
    }

    override fun tick(phase: TickPhase) {
        if (phase != TickPhase.TIMERS || entries.isEmpty()) return
        val now = clock().instant()
        for (entry in entries.values.filter { it.next <= now }) {
            // A schedule an earlier one in this step cancelled (or whose script it stopped) isn't run.
            if (entries[entry.number] !== entry) continue
            entry.next = entry.recurrence.next(now, zone) ?: Instant.MAX
            entry.id?.let { record(entry.scope.namespace, it, now) }
            val result = scripts.callKept<Any?>(entry.scope, entry.callback.ref, emptyList(), "schedule")
            if (result is CallResult.Failed) scripts.handlerFailed(entry.scope, "schedule", result.failure, false)
        }
    }

    private fun record(namespace: String, id: String, time: Instant) {
        lastRuns.getOrPut(namespace) { store.of(namespace).toMutableMap() }[id] = time.toEpochMilli()
        store.ran(namespace, id, time.toEpochMilli())
    }

    override fun scopeReleased(scope: Scope) {
        for (entry in entries.values.filter { it.scope == scope }) cancel(entry)
    }

    override fun stop() {
        for (entry in entries.values.toList()) cancel(entry)
    }

    override fun costs(): Map<String, (Scope) -> Int> = mapOf("schedules" to { scope -> entries.values.count { it.scope == scope } })
}
