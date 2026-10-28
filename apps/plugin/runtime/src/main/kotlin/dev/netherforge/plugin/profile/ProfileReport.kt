package dev.netherforge.plugin.profile

import dev.netherforge.format.bridge.HandlerTime
import dev.netherforge.format.bridge.ProfileTick
import dev.netherforge.format.bridge.ScopeTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What `/nf profile <seconds>` writes: a JSON file with every number (each
 * tick's steps, every scope and every function scripts ran), and a text file
 * beside it that a person reads first. Times in the JSON are nanoseconds.
 */
@Serializable
data class ProfileReport(
    /** When the recording started, ISO-8601 with the server's offset. */
    val started: String,
    val seconds: Int,
    /** `Paper 26.3, NetherForge 0.1.0`. */
    val server: String,
    /** NetherForge's whole tick, over the ticks recorded. */
    val tick: Stats,
    /** Each step of the tick, in the order they run. */
    val phases: Map<String, Stats>,
    /**
     * What the steps are made of, by path, the most expensive first: each service's share of a step
     * (`world/centities`) and the parts a service times itself (`world/centities/sync`), each inside its parent.
     */
    val parts: Map<String, Stats> = emptyMap(),
    /** Scripts' own code, wherever in the tick it ran. */
    val scripts: Stats,
    /** Every scope that ran, the most expensive first. */
    val scopes: List<ScopeTime>,
    /** Every function scripts ran, the most expensive first. */
    val handlers: List<HandlerTime>,
    /** Every tick recorded, in order. */
    val ticks: List<ProfileTick>
) {
    /** A time over the ticks recorded: mean, median, 95th percentile and worst, in nanoseconds; [worstTick] is when. */
    @Serializable
    data class Stats(val mean: Long, val median: Long, val p95: Long, val max: Long, val worstTick: Long? = null) {
        companion object {
            fun of(values: List<Long>, ticks: List<Long>): Stats {
                if (values.isEmpty()) return Stats(0, 0, 0, 0)
                val sorted = values.sorted()
                val worst = values.indices.maxBy { values[it] }
                return Stats(
                    mean = values.sum() / values.size,
                    median = sorted[(sorted.size - 1) / 2],
                    p95 = sorted[((sorted.size - 1) * 95) / 100],
                    max = sorted.last(),
                    worstTick = ticks[worst]
                )
            }
        }
    }

    /**
     * The text summary: the tick and its steps, then the scopes and the
     * functions taking the most time, each with its total, calls, mean per
     * call, worst call and where it is.
     */
    fun summary(): String = buildString {
        val count = ticks.size
        appendLine("NetherForge profile: $seconds s from $started ($count ticks), on $server")
        appendLine()
        appendLine("NetherForge's tick (ms): ${line(tick)}")
        for ((name, stats) in phases) appendLine("  ${name.padEnd(PHASE_WIDTH)} ${line(stats)}")
        if (parts.isNotEmpty()) {
            appendLine("What the steps are made of (ms; each part inside the one its path starts with):")
            for ((name, stats) in parts.entries.take(SHOWN)) appendLine("  $name: ${line(stats)}")
        }
        appendLine("Scripts' own code (ms): ${line(scripts)}")
        appendLine()
        appendLine("Functions by total time (ms; mean and worst per call):")
        appendLine(
            "  ${"total".padStart(
                NUMBER
            )} ${"calls".padStart(NUMBER)} ${"mean".padStart(NUMBER)} ${"worst".padStart(NUMBER)}  script, call, where"
        )
        for (handler in handlers.take(SHOWN)) {
            val where =
                handler.source?.let { source -> source.file + (source.line?.let { ":$it" }.orEmpty()) } ?: "(NetherForge's own code)"
            val scopes = if (handler.scopes > 1) " in ${handler.scopes} scopes" else ""
            appendLine(
                "  ${ms(
                    handler.nanos
                )} ${handler.calls.toString().padStart(NUMBER)} ${ms(handler.nanos / handler.calls.coerceAtLeast(1))} " +
                    "${ms(handler.max)}  ${handler.script}, ${handler.kind}$scopes, $where"
            )
        }
        if (handlers.size > SHOWN) appendLine("  …and ${handlers.size - SHOWN} more in the JSON")
        appendLine()
        appendLine("Scopes by total time (ms; mean a tick and worst tick):")
        appendLine(
            "  ${"total".padStart(NUMBER)} ${"calls".padStart(NUMBER)} ${"mean".padStart(NUMBER)} ${"worst".padStart(NUMBER)}  scope"
        )
        for (scope in scopes.take(SHOWN)) {
            appendLine(
                "  ${ms(scope.nanos)} ${scope.calls.toString().padStart(NUMBER)} ${ms(scope.nanos / count.coerceAtLeast(1))} " +
                    "${ms(scope.max)}  ${scope.scope}"
            )
        }
        if (scopes.size > SHOWN) appendLine("  …and ${scopes.size - SHOWN} more in the JSON")
    }

    /** Writes `<name>.json` and `<name>.txt` into [directory] (each through a temporary file), and answers the text file. */
    fun write(directory: Path, name: String): Path {
        Files.createDirectories(directory)
        val json = directory.resolve("$name.json")
        val text = directory.resolve("$name.txt")
        atomically(json, JSON.encodeToString(serializer(), this) + "\n")
        atomically(text, summary())
        return text
    }

    private fun atomically(file: Path, contents: String) {
        val temp = file.resolveSibling("${file.fileName}.tmp")
        Files.writeString(temp, contents)
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        private val JSON = Json {
            prettyPrint = true
            encodeDefaults = true
        }

        private const val SHOWN = 30
        private const val NUMBER = 9
        private const val PHASE_WIDTH = 9
        private const val NANOS_PER_MILLI = 1_000_000.0

        private fun ms(nanos: Long) = String.format(Locale.ROOT, "%9.3f", nanos / NANOS_PER_MILLI)

        private fun line(stats: Stats) = String.format(
            Locale.ROOT,
            "mean %.3f, median %.3f, 95th %.3f, worst %.3f%s",
            stats.mean / NANOS_PER_MILLI,
            stats.median / NANOS_PER_MILLI,
            stats.p95 / NANOS_PER_MILLI,
            stats.max / NANOS_PER_MILLI,
            stats.worstTick?.let { " (tick $it)" }.orEmpty()
        )

        internal fun of(started: ZonedDateTime, seconds: Int, server: String, totals: Profiler.Totals): ProfileReport {
            val ticks = totals.ticks.toList()
            val numbers = ticks.map { it.tick }
            val phaseNames = ticks.flatMap { it.phases.keys }.distinct()
            val partNames = ticks.flatMap { it.parts.keys }.distinct()
            return ProfileReport(
                started = started.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                seconds = seconds,
                server = server,
                tick = Stats.of(ticks.map { it.nanos }, numbers),
                phases = phaseNames.associateWith { name -> Stats.of(ticks.map { it.phases[name] ?: 0 }, numbers) },
                parts = partNames.associateWith { name -> Stats.of(ticks.map { it.parts[name] ?: 0 }, numbers) }
                    .entries.sortedByDescending { it.value.mean }.associate { it.key to it.value },
                scripts = Stats.of(ticks.map { it.scripts }, numbers),
                scopes = totals.scopeTimes(),
                handlers = totals.handlerTimes(),
                ticks = ticks
            )
        }
    }
}
