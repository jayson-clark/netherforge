package dev.netherforge.plugin.stats
import dev.netherforge.format.buff.BuffFile
import dev.netherforge.format.buff.BuffKind
import dev.netherforge.format.buff.BuffStacking
import dev.netherforge.format.project.KindSpec
import dev.netherforge.plugin.binding.BuffEndEvent
import dev.netherforge.plugin.binding.BuffEvent
import dev.netherforge.plugin.binding.Events
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.script.Scripts
import dev.netherforge.plugin.session.ReloadBatch
import dev.netherforge.plugin.session.RuntimeService
import dev.netherforge.plugin.session.SessionProject
import dev.netherforge.plugin.session.TickPhase
import java.util.UUID

/**
 * Stats, modifiers and buffs (`buffs/<id>.json`) on players and centities: each holder's modifiers by source (what
 * scripts set) and its buffs with the ticks they have left, counted down in [TickPhase.WORLD] (a player only while
 * online, a centity while it exists), and a stat the sum of every buff's and every source's number for it, worked
 * out once per change. Runtime state: none of it is saved.
 */
class StatSheets(
    private val scripts: Scripts,
    /** Whether a holder still counts down: a player online, a centity in the world. False drops a centity's. */
    private val present: (Holder) -> Presence,
    /** A buff id as the reading package spells it. */
    private val spell: (String) -> String
) : RuntimeService {
    override val name get() = "stats"

    /** Whose stats: a player's or a centity's, by UUID. */
    sealed interface Holder {
        val id: UUID

        data class Player(override val id: UUID) : Holder

        data class Centity(override val id: UUID) : Holder
    }

    /** Whether a holder's buffs count down now ([ACTIVE]), wait ([WAITING]: a player offline), or go ([GONE]). */
    enum class Presence { ACTIVE, WAITING, GONE }

    /** A buff someone has, with the ticks it has left. */
    class Active(val id: String, var ticks: Long)

    private class State {
        val modifiers = LinkedHashMap<String, Map<String, Double>>()
        val buffs = LinkedHashMap<String, Active>()
        var sums: Map<String, Double>? = null
    }

    private val files = LinkedHashMap<String, BuffFile>()
    private val states = HashMap<Holder, State>()

    override fun define(project: SessionProject) {
        files.clear()
        files += project.running(BuffKind)
    }

    override val reloads: Set<KindSpec<*, *>> get() = setOf(BuffKind)

    /** A changed buff changes what it adds for everyone who has it; a deleted one goes from them. */
    override fun reload(kind: KindSpec<*, *>, ids: Set<String>, batch: ReloadBatch) {
        for (id in ids) {
            val file = batch.snapshot.running(BuffKind)[id]
            if (file != null) files[id] = file else files -= id
        }
        for ((holder, state) in states.entries.toList()) {
            state.sums = null
            for (buff in state.buffs.keys.filter { it in ids && it !in files }) end(holder, state, buff, "removed")
        }
    }

    override fun stop() = states.clear()

    fun has(buff: String): Boolean = buff in files

    /** [buff]'s file, or an error naming the file it would be. */
    private fun file(buff: String): BuffFile =
        files[buff] ?: throw LuaApiException("there's no buff \"$buff\" (buffs/${buff.substringAfter(':')}.json)")

    fun stat(holder: Holder, name: String): Double = sums(holder)[name] ?: 0.0

    fun stats(holder: Holder): Map<String, Double> = sums(holder).filterValues { it != 0.0 }

    private fun sums(holder: Holder): Map<String, Double> {
        val state = states[holder] ?: return emptyMap()
        state.sums?.let { return it }
        val sums = HashMap<String, Double>()
        for (active in state.buffs.values) {
            for ((stat, value) in files[active.id]?.stats.orEmpty()) sums.merge(stat, value, Double::plus)
        }
        for (modifiers in state.modifiers.values) for ((stat, value) in modifiers) sums.merge(stat, value, Double::plus)
        state.sums = sums
        return sums
    }

    fun modifiers(holder: Holder, source: String): Map<String, Double>? = states[holder]?.modifiers?.get(source)

    fun setModifiers(holder: Holder, source: String, modifiers: Map<String, Double>?) {
        for ((stat, value) in modifiers.orEmpty()) {
            if (!BuffFile.STAT_NAME.matches(
                    stat
                )
            ) {
                throw LuaApiException("bad argument 'modifiers' (\"$stat\" isn't a stat name: lowercase letters, digits and _)")
            }
            if (!value.isFinite()) throw LuaApiException("bad argument 'modifiers.$stat' (a finite number expected)")
        }
        if (modifiers.isNullOrEmpty()) {
            val state = states[holder] ?: return
            state.modifiers -= source
            state.sums = null
            return
        }
        val state = states.getOrPut(holder, ::State)
        state.modifiers[source] = LinkedHashMap(modifiers)
        state.sums = null
    }

    /** Gives [holder] [buff] for [ticks], through its rules and its `buff_add` handlers: whether it took hold. */
    fun add(holder: Holder, buff: String, ticks: Long): Boolean {
        val file = file(buff)
        val state = states.getOrPut(holder, ::State)
        for (blocker in file.blockedBy.orEmpty()) {
            if (state.buffs.containsKey(blocker.text)) return false
        }
        val event = BuffEvent(spell(buff), ticks, holder.player(), holder.centity())
        if (scripts.emit(holder.event(Events.PLAYER_BUFF_ADD, Events.CENTITY_BUFF_ADD), holder.handle(), event)) return false
        val given = event.ticks.coerceAtLeast(1)
        for (cured in file.cures.orEmpty()) {
            val name = cured.text
            if (name != buff && state.buffs.containsKey(name)) end(holder, state, name, "cured")
        }
        val active = state.buffs[buff]
        val next = when {
            active == null -> given
            file.stackingOrDefault == BuffStacking.ADD -> active.ticks + given
            file.stackingOrDefault == BuffStacking.REPLACE -> given
            else -> maxOf(active.ticks, given)
        }.let { t -> file.maxTicks?.let { t.coerceAtMost(it.toLong()) } ?: t }
        if (active == null) state.buffs[buff] = Active(buff, next) else active.ticks = next
        state.sums = null
        return true
    }

    fun remove(holder: Holder, buff: String): Boolean {
        val state = states[holder] ?: return false
        if (!state.buffs.containsKey(buff)) return false
        end(holder, state, buff, "removed")
        return true
    }

    fun ticks(holder: Holder, buff: String): Long? = states[holder]?.buffs?.get(buff)?.ticks

    /** Every buff [holder] has, the first given first, with its file. */
    fun buffs(holder: Holder): List<Pair<Active, BuffFile?>> = states[holder]?.buffs?.values?.map { it to files[it.id] }.orEmpty()

    override fun tick(phase: TickPhase) {
        if (phase != TickPhase.WORLD) return
        for ((holder, state) in states.entries.toList()) {
            when (present(holder)) {
                Presence.GONE -> {
                    states -= holder
                    continue
                }
                Presence.WAITING -> continue
                Presence.ACTIVE -> {}
            }
            for (active in state.buffs.values.toList()) {
                active.ticks--
                if (active.ticks <= 0 && state.buffs[active.id] === active) end(holder, state, active.id, "expired")
            }
        }
    }

    private fun end(holder: Holder, state: State, buff: String, reason: String) {
        state.buffs.remove(buff) ?: return
        state.sums = null
        scripts.emit(
            holder.event(Events.PLAYER_BUFF_END, Events.CENTITY_BUFF_END),
            holder.handle(),
            BuffEndEvent(spell(buff), reason, holder.player(), holder.centity())
        )
    }

    private fun Holder.player() = (this as? Holder.Player)?.let { LuaHandle.Player(it.id.toString()) }

    private fun Holder.centity() = (this as? Holder.Centity)?.let { LuaHandle.Centity(it.id.toString()) }

    private fun Holder.handle(): LuaHandle = player() ?: centity()!!

    private fun <P : dev.netherforge.plugin.binding.LuaEvent> Holder.event(
        player: dev.netherforge.plugin.binding.EventType<P>,
        centity: dev.netherforge.plugin.binding.EventType<P>
    ) = if (this is Holder.Player) player else centity
}
