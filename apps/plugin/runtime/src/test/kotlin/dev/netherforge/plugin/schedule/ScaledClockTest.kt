package dev.netherforge.plugin.schedule

import dev.netherforge.format.bridge.Bridge
import dev.netherforge.plugin.RuntimeConfig
import dev.netherforge.plugin.ServerAddress
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ScaledClockTest {
    /** A clock the test moves. */
    private class Moving(var now: Instant, private val zone: ZoneId = ZoneOffset.UTC) : Clock() {
        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId): Clock = Moving(now, zone)

        override fun instant(): Instant = now
    }

    @Test
    fun `it starts where its base is and runs rate times as fast`() {
        val start = Instant.parse("2026-10-07T12:00:00Z")
        val base = Moving(start)
        val clock = ScaledClock(base, 60.0)
        assertEquals(start, clock.instant())
        base.now = start.plusSeconds(1)
        assertEquals(start.plusSeconds(60), clock.instant())
        base.now = start.plus(Duration.ofMillis(2500))
        assertEquals(start.plusSeconds(150), clock.instant())
        assertEquals(ZoneId.of("Europe/Paris"), clock.withZone(ZoneId.of("Europe/Paris")).zone)
        assertFailsWith<IllegalArgumentException> { ScaledClock(base, 0.0) }
    }

    @Test
    fun `only a dev server takes a rate from its system property, and only a positive one`() {
        val plugin = Path.of("/data/plugins/NetherForge").toAbsolutePath()
        val server = ServerAddress(Path.of("/srv").toAbsolutePath(), "")
        fun rate(settings: Map<String, Any?>, properties: Map<String, String>) =
            RuntimeConfig.read(plugin, settings, server, properties::get) { null }!!.wallClockRate
        val dev = mapOf(Bridge.PROJECT_PROPERTY to "/work/project", Bridge.PORT_PROPERTY to "4100")
        assertEquals(1.0, rate(emptyMap(), dev))
        assertEquals(60.0, rate(emptyMap(), dev + (RuntimeConfig.WALL_CLOCK_RATE_PROPERTY to "60")))
        assertEquals(1.0, rate(emptyMap(), dev + (RuntimeConfig.WALL_CLOCK_RATE_PROPERTY to "-3")))
        // A production server never reads it.
        assertEquals(1.0, rate(mapOf("project" to "project"), mapOf(RuntimeConfig.WALL_CLOCK_RATE_PROPERTY to "60")))
    }
}
