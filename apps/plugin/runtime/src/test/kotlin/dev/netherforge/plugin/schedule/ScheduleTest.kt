package dev.netherforge.plugin.schedule
import dev.netherforge.format.bridge.Log
import dev.netherforge.format.bridge.ProfileSample
import dev.netherforge.format.bridge.SourceRef
import dev.netherforge.plugin.TestServer
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `nf.schedule`: daily, weekly and cron schedules against a clock the test moves, in the owner's time
 * zone; last runs kept in the store; catch-up on and off; reload cancelling.
 */
class ScheduleTest {
    /** A wall clock the test sets. */
    private class TestClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = now

        fun advance(by: Duration) {
            now += by
        }
    }

    private fun at(text: String, zone: String = "UTC"): Instant = ZonedDateTime.parse("$text[$zone]").toInstant()

    private fun module(init: String) = mapOf<String, Any>("modules/s/init.lua" to init)

    private fun server(clock: Clock, files: Map<String, Any>, zone: String? = "UTC") =
        TestServer(files, wallClock = clock, scheduleZone = zone)

    @Test
    fun `a daily schedule runs once a day at its time, in the owner's zone`() {
        val clock = TestClock(at("2026-10-05T12:00:00Z"))
        server(
            clock,
            module("""nf.schedule.daily("18:00", function() log("ran", nf.server.unix_time()) end)"""),
            zone = "America/New_York"
        ).use { server ->
            // 18:00 in New York (EDT, UTC-4) is 22:00 UTC.
            server.tick(5)
            assertEquals(emptyList(), server.logs)
            clock.now = at("2026-10-05T21:59:59Z")
            server.tick(3)
            assertEquals(emptyList(), server.logs)
            clock.now = at("2026-10-05T22:00:00Z")
            server.tick(1)
            assertEquals(listOf("ran\t${clock.now.toEpochMilli()}"), server.logs)
            // Not again that day, however long it is.
            clock.now = at("2026-10-06T21:59:00Z")
            server.tick(5)
            assertEquals(1, server.logs.size)
            clock.now = at("2026-10-06T22:00:00Z")
            server.tick(1)
            assertEquals(2, server.logs.size)
            assertEquals(emptyList(), server.errors)
        }
    }

    @Test
    fun `a weekly schedule runs on its weekday`() {
        // A Monday.
        val clock = TestClock(at("2026-10-05T12:00:00Z"))
        server(clock, module("""nf.schedule.weekly("Sat", "18:00", function() log("ran") end)""")).use { server ->
            clock.now = at("2026-10-09T23:00:00Z")
            server.tick(2)
            assertEquals(emptyList(), server.logs)
            clock.now = at("2026-10-10T18:00:00Z")
            server.tick(1)
            assertEquals(listOf("ran"), server.logs)
            clock.now = at("2026-10-16T18:00:00Z")
            server.tick(2)
            assertEquals(1, server.logs.size)
            clock.now = at("2026-10-17T18:00:01Z")
            server.tick(1)
            assertEquals(2, server.logs.size)
        }
    }

    @Test
    fun `a cron schedule runs when its expression matches`() {
        val clock = TestClock(at("2026-10-05T12:10:00Z"))
        server(
            clock,
            module(
                """nf.schedule.cron("*/30 * * * *", function() log("ran", nf.time.format(nf.server.unix_time(), "HH:mm", { zone = "UTC" })) end)"""
            )
        ).use { server ->
            for (minutes in listOf(15, 20, 25, 30, 31, 59, 60)) {
                clock.now = at("2026-10-05T12:00:00Z").plus(Duration.ofMinutes(minutes.toLong()))
                server.tick(1)
            }
            assertEquals(listOf("ran\t12:30", "ran\t13:00"), server.logs)
        }
    }

    @Test
    fun `a time the clocks skip runs when the gap ends, and one they repeat runs once`() {
        val clock = TestClock(at("2026-03-28T12:00:00Z"))
        server(
            clock,
            module(
                """nf.schedule.daily("01:30", function() log("ran", nf.time.format(nf.server.unix_time(), "yyyy-MM-dd HH:mm xxx", { zone = "Europe/London" })) end)"""
            ),
            zone = "Europe/London"
        ).use { server ->
            // Every half hour of three days across the spring change.
            var minute = at("2026-03-28T12:00:00Z")
            while (minute < at("2026-03-30T12:00:00Z")) {
                clock.now = minute
                server.tick(1)
                minute += Duration.ofMinutes(30)
            }
            assertEquals(listOf("ran\t2026-03-29 02:30 +01:00", "ran\t2026-03-30 01:30 +01:00"), server.logs)
        }
        val fall = TestClock(at("2026-10-24T12:00:00Z"))
        server(
            fall,
            module(
                """nf.schedule.daily("01:30", function() log("ran", nf.time.format(nf.server.unix_time(), "yyyy-MM-dd HH:mm xxx", { zone = "Europe/London" })) end)"""
            ),
            zone = "Europe/London"
        ).use { server ->
            var minute = at("2026-10-24T12:00:00Z")
            while (minute < at("2026-10-26T12:00:00Z")) {
                fall.now = minute
                server.tick(1)
                minute += Duration.ofMinutes(30)
            }
            assertEquals(listOf("ran\t2026-10-25 01:30 +01:00", "ran\t2026-10-26 01:30 +00:00"), server.logs)
        }
    }

    @Test
    fun `a time that passed while the server lagged runs once, not once for each tick`() {
        val clock = TestClock(at("2026-10-05T12:00:00Z"))
        server(clock, module("""nf.schedule.cron("* * * * *", function() log("ran") end)""")).use { server ->
            clock.advance(Duration.ofHours(3))
            server.tick(4)
            assertEquals(listOf("ran"), server.logs)
        }
    }

    @Test
    fun `a missed run is skipped without catch_up`() {
        val clock = TestClock(at("2026-10-05T12:00:00Z"))
        val files = module("""nf.schedule.daily("18:00", function() log("ran") end, { id = "reward" })""")
        server(clock, files).use { server ->
            server.tick(2)
            // Off over 18:00.
            clock.now = at("2026-10-06T09:00:00Z")
            server.restart()
            server.tick(5)
            assertEquals(emptyList(), server.logs)
            clock.now = at("2026-10-06T18:00:00Z")
            server.tick(1)
            assertEquals(listOf("ran"), server.logs)
        }
    }

    @Test
    fun `catch_up runs a run the server missed once on start, then carries on`() {
        val clock = TestClock(at("2026-10-05T12:00:00Z"))
        val files =
            module("""nf.schedule.daily("18:00", function() log("ran", nf.server.unix_time()) end, { id = "reward", catch_up = true })""")
        server(clock, files).use { server ->
            server.tick(2)
            assertEquals(emptyList(), server.logs)
            clock.now = at("2026-10-05T18:00:00Z")
            server.tick(1)
            assertEquals(1, server.logs.size)
            // Off over the next day's 18:00 and the one after: one catch-up, not two.
            clock.now = at("2026-10-07T20:00:00Z")
            server.restart()
            server.tick(3)
            assertEquals(2, server.logs.size)
            assertEquals("ran\t${clock.now.toEpochMilli()}", server.logs.last())
            // Caught up: a restart now owes nothing, and the next run is the next 18:00.
            server.restart()
            server.tick(3)
            assertEquals(2, server.logs.size)
            clock.now = at("2026-10-08T18:00:00Z")
            server.tick(1)
            assertEquals(3, server.logs.size)
            assertEquals(emptyList(), server.errors)
        }
    }

    @Test
    fun `catch_up has nothing to run for a schedule seen for the first time, unless the server stopped before its first run`() {
        val clock = TestClock(at("2026-10-05T12:00:00Z"))
        val files = module("""nf.schedule.daily("18:00", function() log("ran") end, { id = "reward", catch_up = true })""")
        server(clock, files).use { server ->
            server.tick(2)
            assertEquals(emptyList(), server.logs)
            // Stopped before its first 18:00 and started after it: first sight counted as its last run.
            clock.now = at("2026-10-05T19:00:00Z")
            server.restart()
            server.tick(2)
            assertEquals(listOf("ran"), server.logs)
        }
    }

    @Test
    fun `a reload doesn't catch up a run that was made, and doesn't lose the last run`() {
        val clock = TestClock(at("2026-10-05T17:59:00Z"))
        val files = module("""nf.schedule.daily("18:00", function() log("ran") end, { id = "reward", catch_up = true })""")
        server(clock, files).use { server ->
            clock.now = at("2026-10-05T18:00:00Z")
            server.tick(1)
            assertEquals(listOf("ran"), server.logs)
            clock.now = at("2026-10-05T20:00:00Z")
            server.reload("modules/s/init.lua")
            server.tick(3)
            assertEquals(listOf("ran"), server.logs)
        }
    }

    @Test
    fun `a reload cancels the schedules the script made, and the new script makes its own`() {
        val clock = TestClock(at("2026-10-05T12:00:00Z"))
        server(clock, module("""nf.schedule.cron("0 * * * *", function() log("old") end)""")).use { server ->
            server.write("modules/s/init.lua", """nf.schedule.cron("0 * * * *", function() log("new") end)""")
            server.reload("modules/s/init.lua")
            clock.now = at("2026-10-05T13:00:00Z")
            server.tick(2)
            assertEquals(listOf("new"), server.logs)
        }
    }

    @Test
    fun `cancel stops a schedule, and next_run says when the next run is`() {
        val clock = TestClock(at("2026-10-05T12:00:00Z"))
        server(
            clock,
            module(
                """
                local schedule = nf.schedule.daily("18:00", function() log("ran") end)
                log("next", schedule:next_run(), schedule:is_active())
                nf.commands.register("stop", {}, function() schedule:cancel() end)
                nf.on("tick", function()
                  if nf.server.tick() == 2 then
                    log("later", schedule:next_run(), schedule:is_active())
                  end
                end)
                """
            )
        ).use { server ->
            server.tick(1)
            assertEquals(listOf("next\t${at("2026-10-05T18:00:00Z").toEpochMilli()}\ttrue"), server.logs)
            server.platform.commands.run(server.player("Alex"), "stop")
            server.tick(3)
            assertEquals("later\tnil\tfalse", server.logs.last())
            clock.now = at("2026-10-05T18:00:00Z")
            server.tick(2)
            assertEquals(2, server.logs.size)
        }
    }

    @Test
    fun `a callback that errors is reported with its line, and the schedule carries on`() {
        val clock = TestClock(at("2026-10-05T12:00:00Z"))
        server(clock, module("nf.schedule.daily(\"18:00\", function()\n  log(\"run\")\n  error(\"boom\")\nend)")).use { server ->
            clock.now = at("2026-10-05T18:00:00Z")
            server.tick(1)
            val error = server.errors.single()
            assertTrue("boom" in error.message, error.message)
            assertEquals("modules/s/init.lua", error.source?.file)
            assertEquals(3, error.source?.line)
            clock.now = at("2026-10-06T18:00:00Z")
            server.tick(1)
            assertEquals(listOf("run", "run"), server.logs)
        }
    }

    @Test
    fun `the profiler times a schedule's callback as a schedule`() {
        var now = 0L
        val clock = TestClock(at("2026-10-05T12:00:00Z"))
        TestServer(
            module("nf.schedule.cron(\"* * * * *\", function()\nend)"),
            wallClock = clock,
            clock = {
                now += 1_000_000
                now
            }
        ).use { server ->
            server.runtime.profiler.streaming = true
            clock.advance(Duration.ofMinutes(2))
            server.tick(20)
            val sample = server.sent.filterIsInstance<ProfileSample>().first()
            val handler = sample.handlers.single { it.source == SourceRef("modules/s/init.lua", 1) }
            assertEquals("schedule", handler.kind)
            assertEquals("module s", handler.script)
            assertEquals(1, handler.calls)
        }
    }

    @Test
    fun `mistakes are errors at the script's line`() {
        val clock = TestClock(at("2026-10-05T12:00:00Z"))
        val bad = mapOf(
            "nf.schedule.daily(\"6pm\", function() end)" to "isn't a time of day",
            "nf.schedule.daily(\"24:00\", function() end)" to "isn't a time of day",
            "nf.schedule.weekly(\"someday\", \"18:00\", function() end)" to "isn't a weekday",
            "nf.schedule.cron(\"every day\", function() end)" to "isn't a cron expression",
            "nf.schedule.cron(\"* * * * * *\", function() end)" to "isn't a cron expression",
            "nf.schedule.daily(\"18:00\", function() end, { catch_up = true })" to "catch_up needs an options.id",
            "nf.schedule.daily(\"18:00\", function() end, { id = \"Bad Id\" })" to "can't name a schedule",
            "nf.schedule.daily(\"18:00\", function() end, { speed = 1 })" to "speed",
            "nf.schedule.daily(\"18:00\")" to "callback",
            "nf.schedule.daily(\"18:00\", function() end, { id = \"a\" }); nf.schedule.daily(\"19:00\", function() end, { id = \"a\" })" to
                "already a schedule"
        )
        for ((code, message) in bad) {
            server(clock, module(code)).use { server ->
                val error = server.errors.firstOrNull()
                assertTrue(error != null && message in error.message, "$code gave ${server.errors}")
            }
        }
    }

    @Test
    fun `an unknown time zone in the config falls back to the server's own, with a warning`() {
        val clock = TestClock(at("2026-10-05T12:00:00Z"))
        server(clock, module("""nf.schedule.daily("18:00", function() end)"""), zone = "Mars/Olympus").use { server ->
            assertTrue(
                server.sent.filterIsInstance<Log>().any { "Mars/Olympus" in it.message && "time-zone" in it.message },
                "${server.sent}"
            )
        }
    }

    @Test
    fun `nf time writes and reads in the schedules' configured zone unless given its own`() {
        val clock = TestClock(at("2026-10-05T12:00:00Z"))
        val code = """
            local now = nf.server.unix_time()
            log(nf.time.format(now, "yyyy-MM-dd HH:mm XXX"))
            log(nf.time.format(now, "HH:mm", { zone = "UTC" }))
            log(nf.time.parse("2026-10-05 12:00", "yyyy-MM-dd HH:mm"))
        """
        server(clock, module(code), zone = "America/New_York").use { server ->
            assertEquals(listOf("2026-10-05 08:00 -04:00", "12:00", "${at("2026-10-05T16:00:00Z").toEpochMilli()}"), server.logs)
        }
        // A zone the config gets wrong falls back to the server's own, for nf.time as for schedules.
        server(clock, module(code), zone = "Mars/Olympus").use { server ->
            val own = ZoneId.systemDefault()
            val expected = clock.now.atZone(own).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm XXX"))
            assertEquals(expected, server.logs.first())
        }
    }

    @Test
    fun `nf schedules lists the live schedules soonest first, with their next run in the owner's zone`() {
        val clock = TestClock(at("2026-10-05T12:00:00Z"))
        val code = """
            nf.schedule.weekly("sat", "18:00", function() end)
            nf.schedule.daily("18:00", function() end, { id = "daily_reward" })
            nf.schedule.cron("*/30 * * * *", function() end)
            nf.schedule.cron("0 0 31 2 *", function() end)
        """
        server(clock, module(code), zone = "America/New_York").use { server ->
            assertEquals(
                listOf(
                    "<gold>4 schedules <gray>(times in America/New_York)",
                    "<yellow>cron */30 * * * * <white>module s <gray>next <white>2026-10-05 08:30 EDT <gray>(in 30m)",
                    "<yellow>daily 18:00 <gray>daily_reward <white>module s <gray>next <white>2026-10-05 18:00 EDT <gray>(in 10h)",
                    "<yellow>weekly sat 18:00 <white>module s <gray>next <white>2026-10-10 18:00 EDT <gray>(in 5d 10h)",
                    "<yellow>cron 0 0 31 2 * <white>module s <gray>next <white>never"
                ),
                server.platform.commands.run(server.player("Alex"), "nf schedules")
            )
        }
    }

    @Test
    fun `nf schedules says so when there are none`() {
        TestServer(mapOf(TestServer.MANIFEST to TestServer.manifest())).use { server ->
            assertEquals(listOf("<gray>No schedules are running."), server.platform.commands.run(server.player("Alex"), "nf schedules"))
        }
    }
}
