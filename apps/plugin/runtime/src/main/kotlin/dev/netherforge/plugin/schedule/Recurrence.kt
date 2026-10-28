package dev.netherforge.plugin.schedule

import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.Locale

/**
 * When a schedule runs, as a rule: the first time after any instant, in a
 * time zone. Daylight saving is java.time's: a wall-clock time the clocks
 * skip is the instant the gap ends at (`02:30` on the night clocks jump from
 * 02:00 to 03:00 is 03:30: [ZonedDateTime.of] shifts it by the gap), and one
 * that happens twice is the first (the earlier offset), so a daily time runs
 * once a day, every day.
 */
internal sealed interface Recurrence {
    /** The first run strictly after [after], or null when there never is one (a cron expression for 31 February). */
    fun next(after: Instant, zone: ZoneId): Instant?

    /** The rule in words, as `/nf schedules` shows it. */
    fun describe(): String

    /** Every day at [time]. */
    data class Daily(val time: LocalTime) : Recurrence {
        override fun describe() = "daily $time"

        override fun next(after: Instant, zone: ZoneId): Instant? = firstDay(after, zone, time) { true }
    }

    /** Every week on [day] at [time]. */
    data class Weekly(val day: DayOfWeek, val time: LocalTime) : Recurrence {
        override fun describe() = "weekly ${day.name.lowercase().take(3)} $time"

        override fun next(after: Instant, zone: ZoneId): Instant? = firstDay(after, zone, time) { it.dayOfWeek == day }
    }

    /** Whenever [expression] (Unix cron, five fields) matches. */
    class Cron(val expression: String, private val time: ExecutionTime) : Recurrence {
        override fun describe() = "cron $expression"

        override fun next(after: Instant, zone: ZoneId): Instant? =
            time.nextExecution(after.atZone(zone)).map { it.toInstant() }.orElse(null)

        override fun equals(other: Any?) = other is Cron && other.expression == expression

        override fun hashCode() = expression.hashCode()
    }

    companion object {
        private val CLOCK = DateTimeFormatter.ofPattern("H:mm", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT)

        private val CRON = CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX))

        private val DAYS = DayOfWeek.entries.flatMap { day ->
            val name = day.name.lowercase()
            listOf(name to day, name.take(3) to day)
        }.toMap()

        /** A time of day written `18:00` or `6:30`, or null when it isn't one. */
        fun time(text: String): LocalTime? = try {
            if (text.length in 4..5) LocalTime.parse(text, CLOCK) else null
        } catch (_: DateTimeParseException) {
            null
        }

        /** A weekday written `sat` or `saturday`, in any case, or null. */
        fun day(text: String): DayOfWeek? = DAYS[text.lowercase()]

        /** The cron expression [text], or its problem in words. */
        fun cron(text: String): Result<Cron> = runCatching {
            Cron(text, ExecutionTime.forCron(CRON.parse(text)))
        }.recoverCatching { throw IllegalArgumentException(it.message ?: "not a cron expression") }

        /** The first of [time] on a day [accepts] whose moment is after [after]. */
        private inline fun firstDay(after: Instant, zone: ZoneId, time: LocalTime, accepts: (LocalDate) -> Boolean): Instant? {
            // One day back, in case a gap pushed yesterday's time past midnight; and a week and a day forward is enough for any weekday.
            var date = after.atZone(zone).toLocalDate().minusDays(1)
            repeat(9) {
                if (accepts(date)) {
                    val at = ZonedDateTime.of(date, time, zone).toInstant()
                    if (at > after) return at
                }
                date = date.plusDays(1)
            }
            return null
        }
    }
}
