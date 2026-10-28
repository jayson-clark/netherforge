package dev.netherforge.plugin.api
import dev.netherforge.plugin.binding.Caller
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.binding.NfScheduleApi
import dev.netherforge.plugin.binding.ScheduleApi
import dev.netherforge.plugin.binding.ScheduleOptions
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.lua.LuaFunction
import dev.netherforge.plugin.schedule.Recurrence
import dev.netherforge.plugin.session.ProjectSession

/** `nf.schedule`: what a script's times are made into, and handed to [Schedules]. */
internal class NfScheduleImpl(private val session: ProjectSession) : NfScheduleApi {
    override fun daily(caller: Caller, time: String, callback: LuaFunction, options: ScheduleOptions?): LuaHandle.Schedule {
        val at = Recurrence.time(time) ?: fail(callback, badTime(time))
        return start(caller, Recurrence.Daily(at), callback, options)
    }

    override fun weekly(caller: Caller, day: String, time: String, callback: LuaFunction, options: ScheduleOptions?): LuaHandle.Schedule {
        val weekday = Recurrence.day(day)
            ?: fail(
                callback,
                "bad argument 'day' (\"$day\" isn't a weekday: \"mon\", \"tue\", \"wed\", \"thu\", \"fri\", \"sat\" or \"sun\")"
            )
        val at = Recurrence.time(time) ?: fail(callback, badTime(time))
        return start(caller, Recurrence.Weekly(weekday, at), callback, options)
    }

    override fun cron(caller: Caller, expression: String, callback: LuaFunction, options: ScheduleOptions?): LuaHandle.Schedule {
        val cron = Recurrence.cron(expression).getOrElse {
            fail(callback, "bad argument 'expression' (\"$expression\" isn't a cron expression: ${it.message})")
        }
        return start(caller, cron, callback, options)
    }

    /** A mistake in an argument: [callback] is let go of, since nothing will call it. */
    private fun fail(callback: LuaFunction, message: String): Nothing {
        session.scripts.unref(callback.ref)
        throw LuaApiException(message)
    }

    private fun badTime(time: String) = "bad argument 'time' (\"$time\" isn't a time of day: \"HH:mm\" on a 24-hour clock, like \"18:00\")"

    private fun start(caller: Caller, recurrence: Recurrence, callback: LuaFunction, options: ScheduleOptions?): LuaHandle.Schedule {
        val entry = session.schedules.create(caller.scope, recurrence, callback, options?.id, options?.catchUp ?: false)
        return LuaHandle.Schedule(entry.number)
    }
}

/** `Schedule`: answers `false` or `nil` once it has been cancelled or its script has unloaded. */
internal class ScheduleImpl(private val session: ProjectSession) : ScheduleApi {
    private val schedules get() = session.schedules

    override fun cancel(self: LuaHandle.Schedule) {
        schedules.get(self.number)?.let(schedules::cancel)
    }

    override fun isActive(self: LuaHandle.Schedule): Boolean = schedules.get(self.number) != null

    override fun nextRun(self: LuaHandle.Schedule): Long? = schedules.get(self.number)?.let(schedules::nextRun)?.toEpochMilli()
}
