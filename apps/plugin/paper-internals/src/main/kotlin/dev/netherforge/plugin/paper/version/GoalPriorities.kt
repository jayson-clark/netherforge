package dev.netherforge.plugin.paper.version

import com.destroystokyo.paper.entity.ai.Goal
import com.destroystokyo.paper.entity.ai.PaperCustomGoal
import org.bukkit.craftbukkit.entity.CraftMob
import org.bukkit.entity.Mob
import java.util.IdentityHashMap

/**
 * Every goal's priority on [mob], keyed by the very [Goal] objects Paper's
 * `MobGoals` hands out for it. Paper's API doesn't say a goal's priority, so
 * this reaches into the server (Mojang-named, through paperweight-userdev, as
 * `bots/` does): the priority lives on the `WrappedGoal` each of the mob's two
 * selectors keeps a goal in. Paper hands out the game's goals as their
 * `asPaperGoal()` (made once per goal, so the same object every time) and a
 * plugin's goal as itself, inside a `PaperCustomGoal`.
 *
 * Like the bots and [MojangRegistries], it depends on the server's internals:
 * the integration test's `it-goals` step says whether a new version still holds
 * (see the minecraft-versions skill).
 */
internal fun goalPriorities(mob: Mob): Map<Goal<Mob>, Int> {
    val handle = (mob as CraftMob).handle
    val priorities = IdentityHashMap<Goal<Mob>, Int>()
    for (selector in listOf(Mojang.goalSelector(handle), handle.targetSelector)) {
        for (wrapped in selector.availableGoals) {
            val goal = wrapped.goal

            @Suppress("UNCHECKED_CAST")
            val api = if (goal is PaperCustomGoal<*>) goal.handle as Goal<Mob> else goal.asPaperGoal<Mob>()
            priorities[api] = wrapped.priority
        }
    }
    return priorities
}
