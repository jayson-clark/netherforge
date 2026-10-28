package dev.netherforge.plugin.paper

import com.destroystokyo.paper.entity.ai.Goal
import com.destroystokyo.paper.entity.ai.GoalKey
import com.destroystokyo.paper.entity.ai.GoalType
import dev.netherforge.plugin.platform.GoalCallbacks
import dev.netherforge.plugin.platform.GoalControl
import dev.netherforge.plugin.platform.GoalInfo
import dev.netherforge.plugin.platform.MobGoalOps
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.entity.Mob
import org.bukkit.plugin.java.JavaPlugin
import java.util.EnumSet
import java.util.UUID
import java.util.logging.Level

/**
 * [MobGoalOps] with Paper's mob goal API ([Bukkit.getMobGoals]): a mob's
 * goals in both its selectors, the game's wrapped as Paper's goals and ours
 * as [LuaGoal]s. Paper picks the selector from a goal's types: one with
 * [GoalType.TARGET] goes in the mob's target selector, any other in its goal
 * selector, which is why the runtime lets a goal claim `target` only alone.
 *
 * Paper's API doesn't say a goal's priority, so every goal's is read from
 * the server's own selectors (the adapter's [PaperVersion.goalPriorities]). A goal claiming nothing gets [GoalType.UNKNOWN_BEHAVIOR] from Paper, which isn't a
 * control scripts name, so it's left out of the controls listed.
 */
class PaperMobGoals(private val plugin: JavaPlugin, private val entities: PaperWorldEntities, private val version: PaperVersion) :
    MobGoalOps {
    private val api get() = Bukkit.getMobGoals()

    private fun mob(id: UUID): Mob? = (entities.entity(id) as? Mob)?.takeIf { it.isValid }

    /**
     * A goal written in Lua, as Paper calls it from the mob's goal selector.
     * Nothing here may throw into the server's tick: a failure is the
     * runtime's to report, and anything else is logged and answered with no.
     */
    private inner class LuaGoal(private val key: GoalKey<Mob>, private val types: EnumSet<GoalType>, val callbacks: GoalCallbacks) :
        Goal<Mob> {
        override fun shouldActivate(): Boolean = guard(false) { callbacks.shouldStart() }

        override fun shouldStayActive(): Boolean = guard(false) { callbacks.shouldContinue() }

        override fun start() = guard(Unit) { callbacks.start() }

        override fun tick() = guard(Unit) { callbacks.tick() }

        override fun stop() = guard(Unit) { callbacks.stop() }

        override fun getKey(): GoalKey<Mob> = key

        override fun getTypes(): EnumSet<GoalType> = types

        private inline fun <T> guard(otherwise: T, body: () -> T): T = try {
            body()
        } catch (e: Exception) {
            plugin.logger.log(Level.SEVERE, "Mob goal ${key.namespacedKey} failed", e)
            otherwise
        }
    }

    override fun goals(mob: UUID): List<GoalInfo>? {
        val entity = mob(mob) ?: return null
        val running = api.getRunningGoals(entity).toSet()
        val priorities = version.goalPriorities(entity)
        return api.getAllGoals(entity).map { goal ->
            GoalInfo(
                key = goal.key.namespacedKey.toString(),
                priority = priorities.getValue(goal),
                controls = goal.types.mapNotNull(::control).toSet(),
                running = goal in running
            )
        }
    }

    private fun control(type: GoalType): GoalControl? = when (type) {
        GoalType.MOVE -> GoalControl.MOVE
        GoalType.LOOK -> GoalControl.LOOK
        GoalType.JUMP -> GoalControl.JUMP
        GoalType.TARGET -> GoalControl.TARGET
        GoalType.UNKNOWN_BEHAVIOR -> null
    }

    private fun type(control: GoalControl): GoalType = when (control) {
        GoalControl.MOVE -> GoalType.MOVE
        GoalControl.LOOK -> GoalType.LOOK
        GoalControl.JUMP -> GoalType.JUMP
        GoalControl.TARGET -> GoalType.TARGET
    }

    /** Takes off the goals [which] picks (each found first: removing one stops it, which may run a script). Their keys. */
    private fun removeWhere(entity: Mob, which: (Goal<Mob>) -> Boolean): List<String> {
        val goals = api.getAllGoals(entity).filter(which)
        for (goal in goals) api.removeGoal(entity, goal)
        return goals.map { it.key.namespacedKey.toString() }
    }

    override fun remove(mob: UUID, key: String): List<String>? {
        val entity = mob(mob) ?: return null
        val namespaced = NamespacedKey.fromString(key) ?: return emptyList()
        return removeWhere(entity) { it.key.namespacedKey == namespaced }
    }

    override fun remove(mob: UUID, goal: GoalCallbacks): Boolean {
        val entity = mob(mob) ?: return false
        return removeWhere(entity) { (it as? LuaGoal)?.callbacks === goal }.isNotEmpty()
    }

    override fun clear(mob: UUID, keep: Set<String>): List<String>? {
        val entity = mob(mob) ?: return null
        return removeWhere(entity) { it.key.namespacedKey.toString() !in keep }
    }

    override fun add(mob: UUID, key: String, priority: Int, controls: Set<GoalControl>, goal: GoalCallbacks): Boolean {
        val entity = mob(mob) ?: return false
        val namespaced = NamespacedKey.fromString(key) ?: return false
        removeWhere(entity) { it.key.namespacedKey == namespaced }
        val types = EnumSet.noneOf(GoalType::class.java).apply { controls.mapTo(this, ::type) }
        api.addGoal(entity, priority, LuaGoal(GoalKey.of(Mob::class.java, namespaced), types, goal))
        return true
    }
}
