package dev.netherforge.plugin.world

import dev.netherforge.format.centity.DayPeriod
import dev.netherforge.format.centity.SpawnTime
import dev.netherforge.plugin.binding.DaylightEvent
import dev.netherforge.plugin.binding.Events
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.platform.Platform
import dev.netherforge.plugin.script.Scripts
import dev.netherforge.plugin.session.RuntimeService
import dev.netherforge.plugin.session.TickPhase

/**
 * A world's `dusk` and `dawn` (and `nf`'s `world_dusk` and `world_dawn`): each loaded world's clock is
 * looked at once a tick, and a period of the day ([DayPeriod]: night from 13000 to 22999) that differs
 * from the one last seen there is a crossing, whether the clock ran into it or `/time set` jumped there.
 *
 * Only while some script listens to one of them, on any world or on `nf` (`Scripts.watch`): otherwise no
 * clock is read. What was last seen is forgotten when nobody listens and when a world is loaded again, so
 * the first look at a world only notes its period: a script that starts listening at night hears the
 * next dawn, not a dusk that already went by.
 */
internal class Daylight(private val platform: Platform, private val scripts: Scripts) : RuntimeService {
    override val name get() = "daylight"

    /** Whether something listens to any of the four events. */
    private var listening = false

    /** Whether each world was at night when last looked at, while [listening]. */
    private val night = HashMap<String, Boolean>()

    init {
        scripts.watch(Events.WORLD_DUSK, Events.WORLD_DAWN, Events.NF_WORLD_DUSK, Events.NF_WORLD_DAWN) {
            listening = it
            if (!it) night.clear()
        }
    }

    override fun tick(phase: TickPhase) {
        if (phase != TickPhase.EVENTS || !listening) return
        for (world in platform.worlds.names()) {
            val timeOfDay = timeOfDay(platform.worlds.fullTime(world) ?: continue)
            val now = isNight(timeOfDay)
            val before = night.put(world, now) ?: continue
            if (before == now) continue
            val handle = LuaHandle.World(world)
            val path = if (now) {
                listOf(Events.WORLD_DUSK to handle, Events.NF_WORLD_DUSK to null)
            } else {
                listOf(Events.WORLD_DAWN to handle, Events.NF_WORLD_DAWN to null)
            }
            scripts.emit(path, DaylightEvent(handle, timeOfDay))
        }
    }

    override fun worldLoaded(world: String) {
        night.remove(world)
    }

    override fun stop() {
        night.clear()
    }

    companion object {
        /** The time of day in ticks, 0 to 23999, of a world whose clock says [fullTime]. */
        fun timeOfDay(fullTime: Long): Long = Math.floorMod(fullTime, SpawnTime.DAY_TICKS.toLong())

        /** Whether [timeOfDay] (0 to 23999) is night, as format's [DayPeriod.NIGHT] says. */
        fun isNight(timeOfDay: Long): Boolean = timeOfDay in DayPeriod.NIGHT.start..DayPeriod.NIGHT.end
    }
}
