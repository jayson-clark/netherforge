package dev.netherforge.plugin.mob
import dev.netherforge.plugin.lua.CallResult
import dev.netherforge.plugin.lua.LuaRef
import dev.netherforge.plugin.platform.GoalCallbacks
import dev.netherforge.plugin.platform.GoalControl
import dev.netherforge.plugin.platform.GoalInfo
import dev.netherforge.plugin.platform.Platform
import dev.netherforge.plugin.script.Scope
import dev.netherforge.plugin.script.Scripts
import dev.netherforge.plugin.session.RuntimeService
import dev.netherforge.plugin.session.TickPhase
import java.util.UUID

/**
 * Mobs' goals as scripts see them (`mob:goals()`, `remove_goal`,
 * `clear_goals`), and the goals written in Lua (`mob:add_goal`), which the
 * mob's own AI calls through [GoalCallbacks].
 *
 * A Lua goal belongs to the scope whose code added it, like a subscription:
 * when that scope stops (a reload restarts it, its centity is removed) the
 * goal is taken off its mob, so the AI never calls into a scope that's gone
 * or a Lua state that's been replaced. A callback runs in a budget frame of
 * its own with its scope's budget, like a handler. One that fails (an error,
 * or running past its budget) is reported with its line and its goal taken
 * off the mob: it would fail again every tick otherwise.
 *
 * The AI calls a goal while the server goes through the mob's goals, and
 * changing them then would break its loop. So a change made from inside a
 * callback (a goal that removes itself, a goal failing, a script stopping
 * because of what a callback did) waits until the runtime's next tick, which
 * comes before the mob's next AI tick.
 *
 * Goals aren't saved: a mob that unloads comes back with the game's goals,
 * so its Lua goals are forgotten then, and when it dies or is removed.
 */
internal class MobGoals(private val platform: Platform, private val scripts: Scripts, private val warn: (String) -> Unit) :
    RuntimeService {
    override val name get() = "mob goals"

    /** The functions a Lua goal was given, kept in the Lua state; null for those it wasn't. */
    class Callbacks(val shouldStart: LuaRef?, val shouldContinue: LuaRef?, val start: LuaRef?, val tick: LuaRef?, val stop: LuaRef?) {
        fun all(): List<LuaRef> = listOfNotNull(shouldStart, shouldContinue, start, tick, stop)
    }

    /** One goal a script added to one mob. */
    private inner class LuaGoal(val scope: Scope, val mob: UUID, val key: String, val callbacks: Callbacks) : GoalCallbacks {
        /** False once it's been dropped: the AI may still hold it until the next tick, but it does nothing. */
        var live = true

        override fun shouldStart(): Boolean = inside { ask(callbacks.shouldStart, "should_start") }

        override fun shouldContinue(): Boolean = inside {
            if (callbacks.shouldContinue !=
                null
            ) {
                ask(callbacks.shouldContinue, "should_continue")
            } else {
                ask(callbacks.shouldStart, "should_start")
            }
        }

        override fun start() = inside { run(callbacks.start, "start") }

        override fun tick() = inside { run(callbacks.tick, "tick") }

        override fun stop() = inside { run(callbacks.stop, "stop") }

        /** Runs [body] as the AI's call into this goal: changes to goals meanwhile wait for the next tick. */
        private inline fun <T> inside(body: () -> T): T {
            calling++
            try {
                return body()
            } finally {
                calling--
            }
        }

        /** A yes-or-no callback's answer; no callback is yes. */
        private fun ask(ref: LuaRef?, name: String): Boolean {
            if (!live) return false
            if (ref == null) return true
            val result = call(ref, name)
            return result is CallResult.Ok && result.value == true
        }

        private fun run(ref: LuaRef?, name: String) {
            if (live && ref != null) call(ref, name)
        }

        private fun call(ref: LuaRef, name: String): CallResult {
            val result = scripts.callKept(scope, ref)
            if (result is CallResult.Failed && live) {
                scripts.handlerFailed(scope, "goal $key's $name", result.failure, false)
                warn("${scope.owner.label}: goal $key was taken off its mob because its $name failed")
                drop(this)
            }
            return result
        }
    }

    /** Each mob's Lua goals, by key. */
    private val byMob = HashMap<UUID, LinkedHashMap<String, LuaGoal>>()

    /** How many goal callbacks are running now: while any is, changes to goals wait for [tick]. */
    private var calling = 0

    /** Changes made from inside a callback, for the next [tick]. */
    private val pending = ArrayDeque<() -> Unit>()

    private var ticks = 0L

    private val ops get() = platform.mobGoals

    /** Runs [change] now, or at the next tick when a goal callback is running. */
    private fun change(change: () -> Unit) {
        if (calling > 0) pending.addLast(change) else change()
    }

    /** Every goal [mob] has, or null when it isn't a mob that's there. */
    fun goals(mob: UUID): List<GoalInfo>? = ops.goals(mob)

    /**
     * Adds a Lua goal for [scope] to [mob], replacing any with [key]. False,
     * with [callbacks] let go of, when it isn't a mob that's there. From
     * inside a callback, it's added at the next tick.
     */
    fun add(scope: Scope, mob: UUID, key: String, priority: Int, controls: Set<GoalControl>, callbacks: Callbacks): Boolean {
        if (!scope.live) {
            callbacks.all().forEach(scripts::unref)
            return false
        }
        val goal = LuaGoal(scope, mob, key, callbacks)
        if (calling > 0) {
            if (ops.goals(mob) == null) {
                release(goal)
                return false
            }
            pending.addLast { attach(goal, priority, controls) }
            return true
        }
        return attach(goal, priority, controls)
    }

    private fun attach(goal: LuaGoal, priority: Int, controls: Set<GoalControl>): Boolean {
        if (!goal.live || goal.scope.released) {
            release(goal)
            return false
        }
        // The goal it replaces stops (its `stop` runs) as it's taken off, before it's forgotten.
        val replaced = byMob[goal.mob]?.get(goal.key)
        if (!ops.add(goal.mob, goal.key, priority, controls, goal)) {
            release(goal)
            return false
        }
        replaced?.let(::release)
        byMob.getOrPut(goal.mob) { LinkedHashMap() }[goal.key] = goal
        return true
    }

    /** Takes every goal with [key] off [mob]. Whether it had one; from inside a callback, whether it has one now. */
    fun remove(mob: UUID, key: String): Boolean {
        if (calling > 0) {
            val has = ops.goals(mob)?.any { it.key == key } == true
            pending.addLast { remove(mob, key) }
            return has
        }
        val removed = ops.remove(mob, key) ?: return false
        forget(mob, removed)
        return removed.isNotEmpty()
    }

    /** Takes every goal off [mob] but those with a key in [keep]. False when it isn't a mob that's there. */
    fun clear(mob: UUID, keep: Set<String>): Boolean {
        if (calling > 0) {
            val isMob = ops.goals(mob) != null
            if (isMob) pending.addLast { clear(mob, keep) }
            return isMob
        }
        val removed = ops.clear(mob, keep) ?: return false
        forget(mob, removed)
        return true
    }

    /** The Lua goals with these keys were taken off [mob]: their functions are let go of. */
    private fun forget(mob: UUID, keys: List<String>) {
        val goals = byMob[mob] ?: return
        for (key in keys) goals[key]?.let(::release)
    }

    /** Takes [goal] off its mob (at the next tick, from inside a callback) and lets go of its functions. */
    private fun drop(goal: LuaGoal) {
        if (!goal.live) return
        release(goal)
        change { ops.remove(goal.mob, goal) }
    }

    /** Forgets [goal] and lets go of its functions; it does nothing from now on. */
    private fun release(goal: LuaGoal) {
        if (!goal.live) return
        goal.live = false
        val goals = byMob[goal.mob]
        if (goals != null && goals[goal.key] === goal) {
            goals.remove(goal.key)
            if (goals.isEmpty()) byMob.remove(goal.mob)
        }
        goal.callbacks.all().forEach(scripts::unref)
    }

    /** [scope] stopped: every goal it added comes off its mob. */
    override fun scopeReleased(scope: Scope) {
        for (goal in owned(scope)) drop(goal)
    }

    private fun owned(scope: Scope): List<LuaGoal> = byMob.values.flatMap { goals -> goals.values.filter { it.scope === scope } }

    /** How many goals [scope] has on mobs now, for `/nf scripts`. */
    fun countOwned(scope: Scope): Int = byMob.values.sumOf { goals -> goals.values.count { it.scope === scope } }

    override fun costs(): Map<String, (Scope) -> Int> = mapOf("goals" to ::countOwned)

    /** [mob] died, was removed or unloaded: it has none of its Lua goals any more. */
    fun mobGone(mob: UUID) {
        byMob[mob]?.values?.toList()?.forEach(::release)
    }

    override fun entityGone(id: UUID) = mobGone(id)

    /** A mob comes back from its chunk with the game's goals only. */
    override fun entitiesUnloading(entities: Set<UUID>) {
        for (id in entities) mobGone(id)
    }

    /** A step of the runtime's tick, which on Paper comes before entities tick (so before the mobs' AI). */
    override fun tick(phase: TickPhase) {
        if (phase == TickPhase.WORLD) pass()
    }

    /** Makes the changes callbacks made, and forgets now and then the goals of mobs that went without a word. */
    private fun pass() {
        ticks++
        while (pending.isNotEmpty()) pending.removeFirst()()
        if (ticks % SWEEP_TICKS == 0L && byMob.isNotEmpty()) {
            for (mob in byMob.keys.toList()) {
                if (platform.worldEntities.info(mob) == null) mobGone(mob)
            }
        }
    }

    /** Forgets everything as the session ends; its scopes have taken their goals off already. */
    override fun stop() {
        pending.clear()
        for (goal in byMob.values.flatMap { it.values }) release(goal)
        byMob.clear()
    }

    companion object {
        /** How often goals of mobs that went (no death or unload heard) are looked for. */
        const val SWEEP_TICKS = 100L
    }
}
