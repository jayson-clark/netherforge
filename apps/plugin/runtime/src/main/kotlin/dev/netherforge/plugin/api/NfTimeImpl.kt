package dev.netherforge.plugin.api

import dev.netherforge.plugin.binding.Caller
import dev.netherforge.plugin.binding.NfTimeApi
import dev.netherforge.plugin.binding.TimeOptions
import dev.netherforge.plugin.lua.LuaApiException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.time.temporal.TemporalQueries
import java.util.Locale
import kotlin.math.abs

/**
 * `nf.time`: milliseconds since the epoch, like `nf.server.unix_time()`, to and from text. Without
 * a `zone` option, times are in [defaultZone]: the owner's (`schedules.time-zone`), as schedules' are.
 */
internal class NfTimeImpl(private val defaultZone: () -> ZoneId) : NfTimeApi {
    override fun format(caller: Caller, time: Long, pattern: String, options: TimeOptions?): String {
        val zone = zone(options)
        return try {
            formatter(pattern).format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(time), zone))
        } catch (e: DateTimeException) {
            throw LuaApiException("can't write $time as \"$pattern\": ${e.message}")
        }
    }

    override fun parse(caller: Caller, text: String, pattern: String, options: TimeOptions?): Long? {
        val formatter = formatter(pattern)
        val zone = zone(options)
        if (!hasDate(formatter)) throw LuaApiException("the pattern \"$pattern\" needs a year, month and day")
        val parsed = try {
            formatter.parse(text)
        } catch (_: DateTimeParseException) {
            return null
        }
        val date = parsed.query(TemporalQueries.localDate()) ?: return null
        val time = parsed.query(TemporalQueries.localTime()) ?: LocalTime.MIDNIGHT
        return ZonedDateTime.of(date, time, parsed.query(TemporalQueries.zone()) ?: zone).toInstant().toEpochMilli()
    }

    override fun duration(caller: Caller, milliseconds: Long): String {
        // Whole seconds, towards zero: dividing first means even Long.MIN_VALUE negates.
        val seconds = milliseconds / 1000
        var left = abs(seconds)
        val parts = mutableListOf<String>()
        for ((unit, seconds) in DURATION_UNITS) {
            if (left >= seconds) {
                parts += "${left / seconds}$unit"
                left %= seconds
            }
        }
        if (parts.isEmpty()) return "0s"
        return (if (seconds < 0) "-" else "") + parts.joinToString(" ")
    }

    override fun parseDuration(caller: Caller, text: String): Long? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || !DURATION.matches(trimmed)) return null
        var total = BigDecimal.ZERO
        val seen = mutableSetOf<String>()
        for (match in DURATION_PART.findAll(trimmed)) {
            val (amount, unit) = match.destructured
            if (!seen.add(unit)) return null
            total += BigDecimal(amount) * BigDecimal(UNIT_MILLISECONDS.getValue(unit))
        }
        return runCatching { total.setScale(0, RoundingMode.HALF_UP).longValueExact() }.getOrNull()
    }

    private fun zone(options: TimeOptions?): ZoneId {
        val name = options?.zone ?: return defaultZone()
        return try {
            ZoneId.of(name)
        } catch (_: DateTimeException) {
            throw LuaApiException("options.zone: there's no time zone \"$name\" (try \"UTC\", \"Europe/London\" or \"+02:00\")")
        }
    }

    /**
     * A pattern's formatter. `y` (the year of an era) reads as `u` (the year), so strict parsing
     * needn't be told the era, and an impossible date (February 30) doesn't parse rather than being
     * moved to one that exists.
     */
    private fun formatter(pattern: String): DateTimeFormatter = try {
        DateTimeFormatter.ofPattern(yearsAsYears(pattern), Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT)
    } catch (e: IllegalArgumentException) {
        throw LuaApiException("bad time pattern \"$pattern\": ${e.message}")
    }

    /** Whether what the formatter writes reads back with a date: that the pattern has a year, month and day. */
    private fun hasDate(formatter: DateTimeFormatter): Boolean = runCatching {
        formatter.parse(formatter.format(REFERENCE)).query(TemporalQueries.localDate()) != null
    }.getOrDefault(false)

    private companion object {
        val REFERENCE: ZonedDateTime = ZonedDateTime.of(2001, 2, 3, 4, 5, 6, 0, ZoneId.of("UTC"))

        val DURATION_UNITS = listOf("d" to 86_400L, "h" to 3_600L, "m" to 60L, "s" to 1L)

        val UNIT_MILLISECONDS = mapOf("d" to 86_400_000L, "h" to 3_600_000L, "m" to 60_000L, "s" to 1_000L, "ms" to 1L)

        // `ms` before `m`, so "250ms" isn't 250 minutes and a stray "s".
        val DURATION_PART = Regex("""(\d+(?:\.\d+)?)\s*(ms|d|h|m|s)""")

        val DURATION = Regex("""(?:\d+(?:\.\d+)?\s*(?:ms|d|h|m|s)\s*)+""")

        /** [pattern] with each `y` outside quotes made a `u`. */
        fun yearsAsYears(pattern: String): String {
            var quoted = false
            return buildString {
                for (c in pattern) {
                    if (c == '\'') quoted = !quoted
                    append(if (c == 'y' && !quoted) 'u' else c)
                }
            }
        }
    }
}
