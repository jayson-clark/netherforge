package dev.netherforge.plugin.platform

import java.util.UUID

/** What a goal claims of a mob while it runs: no less important goal claiming the same runs with it. */
enum class GoalControl {
    MOVE,
    LOOK,
    JUMP,

    /** What the mob is after: a goal claiming it is one of the mob's targeting goals, never one of its regular goals. */
    TARGET;

    val luaName: String get() = name.lowercase()

    companion object {
        fun of(luaName: String): GoalControl? = entries.firstOrNull { it.luaName == luaName }
    }
}

/**
 * One goal a mob has. [key] is namespaced (`minecraft:random_stroll`,
 * `netherforge:guard_post`); a smaller [priority] is more important.
 */
data class GoalInfo(val key: String, val priority: Int, val controls: Set<GoalControl>, val running: Boolean)

/**
 * A goal written in Lua, as the mob's AI calls it (the runtime's
 * `mob/MobGoals.kt`). Called on the main thread from inside the server's
 * own goal loop, so none of these may change a mob's goals directly; and
 * none of them throws.
 */
interface GoalCallbacks {
    fun shouldStart(): Boolean

    fun shouldContinue(): Boolean

    fun start()

    fun tick()

    fun stop()
}

/**
 * Mobs' AI goals (`mob:goals()`, `mob:add_goal`): both the regular goals and
 * the targeting goals, by namespaced key. Everything answers null or false for
 * an entity that isn't a mob, or isn't there now. Goals aren't saved: a mob
 * that unloads comes back with the game's.
 *
 * Never call these from inside [GoalCallbacks]: the server is going through
 * the mob's goals then, and changing them would break its loop. The runtime
 * holds such changes until its next tick.
 */
interface MobGoalOps {
    /** Every goal it has, in no particular order. */
    fun goals(mob: UUID): List<GoalInfo>?

    /** Takes off every goal with [key], stopping one that's running. The keys of the goals taken off: empty when it had none. */
    fun remove(mob: UUID, key: String): List<String>?

    /** Takes off the goal [goal] made, the one [add] was given, if it's still on the mob. */
    fun remove(mob: UUID, goal: GoalCallbacks): Boolean

    /** Takes off every goal but those whose key is in [keep]. The keys of the goals taken off. */
    fun clear(mob: UUID, keep: Set<String>): List<String>?

    /**
     * Adds a goal with [key] (namespaced), replacing any the mob has with
     * that key. One claiming [GoalControl.TARGET] (and nothing else) goes with
     * its targeting goals, any other with its regular goals.
     */
    fun add(mob: UUID, key: String, priority: Int, controls: Set<GoalControl>, goal: GoalCallbacks): Boolean
}
