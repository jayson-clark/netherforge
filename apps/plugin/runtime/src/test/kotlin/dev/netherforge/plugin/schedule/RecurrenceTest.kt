package dev.netherforge.plugin.schedule

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** When a schedule runs next: times of day, weekdays, cron, and what daylight saving does to them. */
class RecurrenceTest {
    private val london = ZoneId.of("Europe/London")
    private val newYork = ZoneId.of("America/New_York")

    private fun at(text: String, zone: ZoneId): Instant = ZonedDateTime.parse("$text[${zone.id}]").toInstant()

    private fun Recurrence.runs(from: Instant, zone: ZoneId, count: Int): List<String> {
        var now = from
        return List(count) {
            val next = checkNotNull(next(now, zone)) { "no next run after $now" }
            now = next
            next.atZone(zone).toString()
        }
    }

    @Test
    fun `a daily time runs at that time in the zone, strictly after now`() {
        val daily = Recurrence.Daily(LocalTime.of(18, 0))
        assertEquals(at("2026-10-05T18:00:00+01:00", london), daily.next(at("2026-10-05T17:59:59.999+01:00", london), london))
        // Exactly at the time is already run: the next one is tomorrow's.
        assertEquals(at("2026-10-06T18:00:00+01:00", london), daily.next(at("2026-10-05T18:00:00+01:00", london), london))
        // The same instant is a different local time elsewhere.
        assertEquals(at("2026-10-05T18:00:00-04:00", newYork), daily.next(at("2026-10-05T17:00:00+01:00", london), newYork))
    }

    @Test
    fun `a weekly time runs on that weekday`() {
        val saturday = Recurrence.Weekly(DayOfWeek.SATURDAY, LocalTime.of(18, 0))
        // 2026-10-05 is a Monday.
        assertEquals(
            listOf("2026-10-10T18:00+01:00[Europe/London]", "2026-10-17T18:00+01:00[Europe/London]"),
            saturday.runs(at("2026-10-05T12:00:00+01:00", london), london, 2)
        )
        // Saturday before the time: today; after it: next week.
        assertEquals(at("2026-10-10T18:00:00+01:00", london), saturday.next(at("2026-10-10T09:00:00+01:00", london), london))
        assertEquals(at("2026-10-17T18:00:00+01:00", london), saturday.next(at("2026-10-10T18:00:00+01:00", london), london))
    }

    @Test
    fun `times and days are read strictly`() {
        assertEquals(LocalTime.of(18, 0), Recurrence.time("18:00"))
        assertEquals(LocalTime.of(6, 30), Recurrence.time("6:30"))
        assertEquals(LocalTime.MIDNIGHT, Recurrence.time("00:00"))
        for (bad in listOf("", "18", "24:00", "18:60", "6pm", "18:00:00", "1:5", " 18:00")) assertNull(Recurrence.time(bad), bad)
        assertEquals(DayOfWeek.SATURDAY, Recurrence.day("sat"))
        assertEquals(DayOfWeek.SATURDAY, Recurrence.day("Saturday"))
        assertEquals(DayOfWeek.MONDAY, Recurrence.day("MON"))
        assertNull(Recurrence.day("satur"))
    }

    @Test
    fun `a time the clocks skip runs when the gap ends, once`() {
        // London springs forward on 2026-03-29: 01:00 becomes 02:00, so 01:30 doesn't exist.
        val daily = Recurrence.Daily(LocalTime.of(1, 30))
        assertEquals(
            listOf("2026-03-28T01:30Z[Europe/London]", "2026-03-29T02:30+01:00[Europe/London]", "2026-03-30T01:30+01:00[Europe/London]"),
            daily.runs(at("2026-03-27T12:00:00Z", london), london, 3)
        )
    }

    @Test
    fun `a time that happens twice runs the first time only`() {
        // London falls back on 2026-10-25: 02:00 becomes 01:00, so 01:30 happens twice.
        val daily = Recurrence.Daily(LocalTime.of(1, 30))
        assertEquals(
            listOf("2026-10-24T01:30+01:00[Europe/London]", "2026-10-25T01:30+01:00[Europe/London]", "2026-10-26T01:30Z[Europe/London]"),
            daily.runs(at("2026-10-23T12:00:00+01:00", london), london, 3)
        )
        // Asked again from after the first 01:30, the repeated one doesn't count.
        assertEquals(
            at("2026-10-26T01:30:00Z", london),
            daily.next(at("2026-10-25T01:30:00+01:00", london).plusSeconds(1), london)
        )
    }

    @Test
    fun `a weekly time on the day of a gap or an overlap runs once that day`() {
        val sunday = Recurrence.Weekly(DayOfWeek.SUNDAY, LocalTime.of(1, 30))
        assertEquals(
            listOf("2026-03-29T02:30+01:00[Europe/London]", "2026-04-05T01:30+01:00[Europe/London]"),
            sunday.runs(at("2026-03-23T12:00:00Z", london), london, 2)
        )
        assertEquals(
            listOf("2026-10-25T01:30+01:00[Europe/London]", "2026-11-01T01:30Z[Europe/London]"),
            sunday.runs(at("2026-10-19T12:00:00+01:00", london), london, 2)
        )
    }

    @Test
    fun `cron expressions run when they match`() {
        val everyHalfHour = Recurrence.cron("*/30 * * * *").getOrThrow()
        assertEquals(
            listOf(
                "2026-10-05T12:30+01:00[Europe/London]",
                "2026-10-05T13:00+01:00[Europe/London]",
                "2026-10-05T13:30+01:00[Europe/London]"
            ),
            everyHalfHour.runs(at("2026-10-05T12:00:00+01:00", london), london, 3)
        )
        val weekdays = Recurrence.cron("0 4 * * 1-5").getOrThrow()
        // Friday 2026-10-09 04:00 is followed by Monday's.
        assertEquals(
            listOf("2026-10-09T04:00+01:00[Europe/London]", "2026-10-12T04:00+01:00[Europe/London]"),
            weekdays.runs(at("2026-10-08T12:00:00+01:00", london), london, 2)
        )
        // Names, lists and 7 for Sunday.
        assertEquals(
            listOf("2026-10-11T09:15+01:00[Europe/London]", "2026-10-11T17:15+01:00[Europe/London]"),
            Recurrence.cron("15 9,17 * OCT SUN").getOrThrow().runs(at("2026-10-09T12:00:00+01:00", london), london, 2)
        )
        assertEquals(
            at("2026-10-11T09:00:00+01:00", london),
            Recurrence.cron("0 9 * * 7").getOrThrow().next(at("2026-10-09T12:00:00+01:00", london), london)
        )
    }

    @Test
    fun `cron in the zone's own clock, whatever the server's`() {
        val daily = Recurrence.cron("0 18 * * *").getOrThrow()
        assertEquals(at("2026-10-05T18:00:00-04:00", newYork), daily.next(at("2026-10-05T12:00:00-04:00", newYork), newYork))
        assertEquals(at("2026-10-05T18:00:00+01:00", london), daily.next(at("2026-10-05T12:00:00-04:00", newYork), london))
    }

    @Test
    fun `a cron time the clocks skip doesn't happen that day, and one they repeat runs once`() {
        // Cron matches the clock, not a wall-clock time shifted by a gap (unlike daily and weekly): 01:30 on the day London springs forward never shows.
        val daily = Recurrence.cron("30 1 * * *").getOrThrow()
        assertEquals(
            listOf("2026-03-28T01:30Z[Europe/London]", "2026-03-30T01:30+01:00[Europe/London]"),
            daily.runs(at("2026-03-27T12:00:00Z", london), london, 2)
        )
        assertEquals(
            listOf("2026-10-24T01:30+01:00[Europe/London]", "2026-10-25T01:30+01:00[Europe/London]", "2026-10-26T01:30Z[Europe/London]"),
            daily.runs(at("2026-10-23T12:00:00+01:00", london), london, 3)
        )
    }

    @Test
    fun `a frequent cron expression keeps running through the repeated hour`() {
        val half = Recurrence.cron("*/30 * * * *").getOrThrow()
        assertEquals(
            listOf(
                "2026-10-25T01:00+01:00[Europe/London]",
                "2026-10-25T01:30+01:00[Europe/London]",
                "2026-10-25T01:00Z[Europe/London]",
                "2026-10-25T01:30Z[Europe/London]",
                "2026-10-25T02:00Z[Europe/London]"
            ),
            half.runs(at("2026-10-24T23:45:00Z", london), london, 5)
        )
    }

    @Test
    fun `a cron expression that never matches has no next run`() {
        val never = Recurrence.cron("0 0 31 2 *").getOrThrow()
        assertNull(runCatching { never.next(Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("UTC")) }.getOrNull())
    }

    @Test
    fun `a cron expression that isn't one is a problem in words`() {
        for (bad in listOf("", "every day", "* * * *", "61 * * * *", "* 25 * * *", "0 0 0 * *", "0 0 * * * *")) {
            val failure = assertFailsWith<IllegalArgumentException>(bad) { Recurrence.cron(bad).getOrThrow() }
            assertTrue(failure.message!!.isNotBlank(), bad)
        }
    }
}
