package dev.netherforge.plugin.profile

import dev.netherforge.format.bridge.Bridge
import dev.netherforge.format.bridge.HandlerTime
import dev.netherforge.format.bridge.ProfileSample
import dev.netherforge.format.bridge.ProfileTick
import dev.netherforge.format.bridge.ScopeTime
import dev.netherforge.format.bridge.SourceRef
import dev.netherforge.plugin.RuntimeLog
import java.nio.file.Path
import java.time.ZonedDateTime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * NetherForge's profiler: exact time per tick step, per script scope and per
 * function scripts run, measured with the same clock as `/nf scripts`
 * (`System.nanoTime` around every step of the tick and every call into Lua),
 * never sampled.
 *
 * It outlives sessions, so a recording goes on across a full reload. The
 * session feeds it: each tick's steps ([tick]), each scope's own time that
 * tick ([scopes]) and, about once a second ([due]), what every function cost
 * ([calls]). Every [BATCH_TICKS] ticks that becomes a [ProfileSample]: sent
 * on the bridge's `profiler` stream while the editor subscribes
 * ([streaming]), and added to the recording `/nf profile` started, which
 * writes a report when it ends.
 *
 * **When it measures** ([active]): always on a dev server (the editor's), and
 * on a production server only while `/nf profile` records, since it costs a
 * little on every call into a script (see the plugin-sandbox skill for how
 * much).
 */
class Profiler(
    /** A dev server: always measuring. */
    private val dev: Boolean,
    private val log: RuntimeLog,
    /** Where reports go: `plugins/NetherForge/profiles`. */
    private val directory: Path,
    /** Where reports are written: a lane of the runtime's workers. */
    private val writer: () -> Executor,
    /** Runs on the main thread, from any thread. */
    private val onMain: Executor,
    /** The server and plugin, for a report's heading. */
    private val server: () -> String,
    /** The wall clock, for a report's name and start. */
    private val now: () -> ZonedDateTime = ZonedDateTime::now,
    /** What the tick's parts are timed with ([parts]). */
    clock: () -> Long = System::nanoTime
) {
    /** What each step of the tick is made of ([TickParts]): services time their own parts into it. */
    val parts = TickParts(clock)

    /** Whether the editor wants the stream. Set from the bridge's thread. */
    @Volatile
    var streaming: Boolean = false

    private var recording: Recording? = null

    /** Whether to measure at all. */
    val active: Boolean get() = dev || streaming || recording != null

    private var batch = Totals()

    /** This tick's scopes so far (from [scopes]), for the tick's own sample. */
    private var tickScopes: List<ScopeTime> = emptyList()

    /** One `/nf profile` run: the ticks after [firstTick] up to [lastTick]. */
    private class Recording(
        val firstTick: Long,
        val lastTick: Long,
        val seconds: Int,
        val started: ZonedDateTime,
        val done: (String) -> Unit
    ) {
        val totals = Totals()
    }

    /** Whether [calls] is wanted at the end of tick [tick]: a batch ends there. */
    fun due(tick: Long): Boolean = tick % BATCH_TICKS == 0L || recording?.lastTick == tick

    /** Each scope's own time this tick, named ([ScopeTime.calls] its calls in, [ScopeTime.max] unused). */
    fun scopes(scopes: List<ScopeTime>) {
        tickScopes = scopes
        for (scope in scopes) {
            batch.scope(scope)
            recording?.totals?.scope(scope)
        }
    }

    /** What each function cost since the last time, one entry per scope and function. */
    fun calls(calls: List<Call>) {
        for (call in calls) {
            batch.call(call)
            recording?.totals?.call(call)
        }
    }

    /** One tick ended: [nanos] in all, [phases] by step. Ends a batch when it's [due]. */
    fun tick(tick: Long, nanos: Long, phases: Map<String, Long>) {
        val scopes = tickScopes
        tickScopes = emptyList()
        val sample = ProfileTick(
            tick,
            nanos,
            phases,
            scripts = scopes.sumOf { it.nanos },
            top = scopes.sortedByDescending { it.nanos }.take(TOP_SCOPES).map { it.copy(max = it.nanos) },
            parts = parts.take()
        )
        batch.ticks += sample
        val recording = recording
        if (recording != null && tick > recording.firstTick) recording.totals.ticks += sample
        if (due(tick)) flush()
        if (recording != null && tick >= recording.lastTick) finish(recording)
    }

    /** Sends what's gathered since the last batch, if the editor wants it, and starts a new batch. */
    fun flush() {
        val sample = batch.sample()
        batch = Totals()
        if (streaming && sample.ticks.isNotEmpty()) log.stream(Bridge.profiler, sample)
    }

    /** How many seconds the recording running has left, or null when none is. */
    fun recordingLeft(tick: Long): Int? = recording?.let {
        ((it.lastTick - tick).coerceAtLeast(0) + TICKS_PER_SECOND - 1).toInt() /
            TICKS_PER_SECOND
    }

    /**
     * Starts recording the [seconds] after server tick [tick]; [done] hears
     * where the report went (or why it couldn't be written), on the main
     * thread. Refused while another recording runs. What was gathered before
     * isn't part of it: the caller has the session hand over what's pending
     * first, and the batch is flushed.
     */
    fun record(tick: Long, seconds: Int, done: (String) -> Unit) {
        require(seconds in 1..MAX_SECONDS) { "a profile runs 1 to $MAX_SECONDS seconds" }
        recordingLeft(tick)?.let { throw IllegalStateException("a profile is already running ($it s left); wait for its report") }
        flush()
        recording = Recording(tick, tick + seconds.toLong() * TICKS_PER_SECOND, seconds, now(), done)
    }

    private fun finish(recording: Recording) {
        this.recording = null
        val report = ProfileReport.of(recording.started, recording.seconds, server(), recording.totals)
        val stamp = recording.started.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        writer().execute {
            val outcome = runCatching { report.write(directory, "profile-$stamp") }
            onMain.execute {
                val message = outcome.fold(
                    { "Profile written to $it (and ${it.fileName.toString().removeSuffix(".txt")}.json beside it)" },
                    {
                        log.error("Couldn't write the profile into $directory", it)
                        "Couldn't write the profile into $directory: ${it.message}"
                    }
                )
                log.info(message)
                recording.done(message)
            }
        }
    }

    /** Waits for reports being written (for tests): their messages are then waiting for the main thread. */
    fun awaitWrites() {
        CompletableFuture.runAsync({}, writer()).get(WRITE_WAIT_SECONDS, TimeUnit.SECONDS)
    }

    /** One function's cost in one scope, named: [script] its scope's script (`centity tower`), [scope] the scope itself. */
    data class Call(
        val script: String,
        val scope: String,
        val kind: String,
        val source: SourceRef?,
        val nanos: Long,
        val calls: Int,
        val max: Long
    )

    /** Times summed over a run of ticks: a batch, or a whole recording. */
    internal class Totals {
        val ticks = ArrayList<ProfileTick>()

        private class ScopeSum(var nanos: Long = 0, var calls: Int = 0, var max: Long = 0)

        private data class HandlerKey(val script: String, val kind: String, val source: SourceRef?)

        private class HandlerSum(var nanos: Long = 0, var calls: Int = 0, var max: Long = 0, val scopes: MutableSet<String> = HashSet())

        private val scopes = HashMap<String, ScopeSum>()
        private val handlers = HashMap<HandlerKey, HandlerSum>()

        fun scope(time: ScopeTime) {
            val sum = scopes.getOrPut(time.scope) { ScopeSum() }
            sum.nanos += time.nanos
            sum.calls += time.calls
            sum.max = maxOf(sum.max, time.nanos)
        }

        fun call(call: Call) {
            val sum = handlers.getOrPut(HandlerKey(call.script, call.kind, call.source)) { HandlerSum() }
            sum.nanos += call.nanos
            sum.calls += call.calls
            sum.max = maxOf(sum.max, call.max)
            sum.scopes += call.scope
        }

        fun scopeTimes(): List<ScopeTime> = scopes.map { (name, sum) -> ScopeTime(name, sum.nanos, sum.calls, sum.max) }
            .sortedWith(compareByDescending<ScopeTime> { it.nanos }.thenBy { it.scope })

        fun handlerTimes(): List<HandlerTime> = handlers.map { (key, sum) ->
            HandlerTime(key.script, key.kind, key.source, sum.calls, sum.nanos, sum.max, sum.scopes.size)
        }.sortedWith(
            compareByDescending<HandlerTime> { it.nanos }.thenBy { it.script }.thenBy { it.kind }.thenBy { it.source?.file }
                .thenBy { it.source?.line }
        )

        fun sample() = ProfileSample(ticks.toList(), scopeTimes(), handlerTimes())
    }

    companion object {
        /** Ticks in a batch of the stream: a second's. */
        const val BATCH_TICKS = 20

        const val TICKS_PER_SECOND = 20

        /** The longest `/nf profile`: ten minutes keeps a report a few megabytes. */
        const val MAX_SECONDS = 600

        /** Scopes named per tick, the most expensive first. */
        const val TOP_SCOPES = 3

        private const val WRITE_WAIT_SECONDS = 30L
    }
}
