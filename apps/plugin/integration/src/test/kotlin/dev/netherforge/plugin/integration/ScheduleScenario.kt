package dev.netherforge.plugin.integration

import dev.netherforge.plugin.RuntimeConfig
import dev.netherforge.plugin.integration.support.PaperServer
import dev.netherforge.plugin.integration.support.Scenario
import dev.netherforge.plugin.integration.support.VersionIndependent
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test

/**
 * `nf.schedule` on a real server, in the shipped jar (cron-utils and all): the schedules are made
 * with their next runs ahead, and a cron schedule for every minute runs on the main thread's tick,
 * again and again. The server's wall clock runs 60 times as fast
 * ([RuntimeConfig.WALL_CLOCK_RATE_PROPERTY], test-only), so a minute is a second and the run doesn't
 * wait for the next real minute; what fires it is the real tick. The rules themselves (zones,
 * daylight saving, catching up) are tested against a clock the test moves, in the runtime's
 * `ScheduleTest`.
 */
@VersionIndependent
class ScheduleScenario : Scenario("schedules") {
    override val server = PaperServer(jvmProperties = listOf("-D${RuntimeConfig.WALL_CLOCK_RATE_PROPERTY}=60"))

    @Test
    @Order(1)
    fun `daily, weekly and cron schedules are made with a next run ahead`() {
        editor.logged("scheduled", "true", "true", "true")
    }

    @Test
    @Order(2)
    fun `a cron schedule runs on the server's tick, every time it comes round`() {
        editor.logged("minute", "1")
        editor.logged("minute", "2")
    }
}
