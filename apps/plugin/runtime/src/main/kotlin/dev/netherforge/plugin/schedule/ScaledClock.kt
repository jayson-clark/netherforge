package dev.netherforge.plugin.schedule

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * [base] running [rate] times as fast from [start] (when it's made, by default): at 60, a minute of what it says
 * passes in a real second. Only the integration test's dev servers run on one
 * ([dev.netherforge.plugin.RuntimeConfig.WALL_CLOCK_RATE_PROPERTY]), so a schedule fires on the server's own tick
 * within a second instead of at the next real minute.
 */
internal class ScaledClock(private val base: Clock, val rate: Double, private val start: Instant = base.instant()) : Clock() {
    init {
        require(rate > 0 && rate.isFinite()) { "a clock's rate is a positive number, not $rate" }
    }

    override fun getZone(): ZoneId = base.zone

    override fun withZone(zone: ZoneId): Clock = ScaledClock(base.withZone(zone), rate, start)

    override fun instant(): Instant {
        val real = Duration.between(start, base.instant()).toNanos()
        return start.plusNanos((real * rate).toLong())
    }
}
