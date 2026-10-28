package dev.netherforge.plugin.api

import dev.netherforge.format.buff.BuffKind
import dev.netherforge.plugin.binding.ActiveBuff
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.session.ProjectSession
import dev.netherforge.plugin.stats.StatSheets

/** The stat and buff functions `Player` and `Centity` share, for a holder that's there ([holder] null once it's gone). */
internal class StatCalls(private val session: ProjectSession) {
    private val stats get() = session.stats

    /** A buff a script names, as the project names it: an error for one the project doesn't have. */
    private fun buff(name: String): String {
        val id = session.names.resource(BuffKind, name)
        if (!stats.has(id)) throw LuaApiException("there's no buff \"$name\" (buffs/${name.substringAfter(':')}.json)")
        return id
    }

    fun stat(holder: StatSheets.Holder?, name: String): Double = holder?.let { stats.stat(it, name) } ?: 0.0

    fun stats(holder: StatSheets.Holder?): Map<String, Double> = holder?.let(stats::stats).orEmpty()

    fun modifiers(holder: StatSheets.Holder?, source: String): Map<String, Double>? = holder?.let { stats.modifiers(it, source) }

    fun setModifiers(holder: StatSheets.Holder?, source: String, modifiers: Map<String, Double>?): Boolean {
        stats.setModifiers(holder ?: return false, source, modifiers)
        return true
    }

    fun addBuff(holder: StatSheets.Holder?, buff: String, ticks: Long): Boolean {
        if (ticks < 1) throw LuaApiException("bad argument 'ticks' (at least 1 expected, got $ticks)")
        val id = buff(buff)
        return stats.add(holder ?: return false, id, ticks)
    }

    fun removeBuff(holder: StatSheets.Holder?, buff: String): Boolean = stats.remove(holder ?: return false, buff(buff))

    fun hasBuff(holder: StatSheets.Holder?, buff: String): Boolean = holder != null && stats.ticks(holder, buff(buff)) != null

    fun buffTicks(holder: StatSheets.Holder?, buff: String): Long? = holder?.let { stats.ticks(it, buff(buff)) }

    fun buffs(holder: StatSheets.Holder?): List<ActiveBuff> = holder?.let(stats::buffs).orEmpty().map { (active, file) ->
        ActiveBuff(
            id = session.names.spell(active.id),
            ticks = active.ticks,
            name = file?.name,
            icon = file?.icon?.text,
            color = file?.color,
            debuff = file?.debuffOrDefault == true
        )
    }
}
