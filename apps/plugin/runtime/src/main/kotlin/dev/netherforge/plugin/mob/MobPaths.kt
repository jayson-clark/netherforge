package dev.netherforge.plugin.mob
import dev.netherforge.format.math.Vec3
import dev.netherforge.plugin.platform.Platform
import dev.netherforge.plugin.session.RuntimeService
import dev.netherforge.plugin.session.TickPhase
import java.util.UUID
import kotlin.math.sqrt

/**
 * The walks `mob:move_to` started, watched once a tick for their end: Paper
 * has no event for a path finishing, so a walk is over when the mob is no
 * longer on a path (reached when it stopped within [PATH_REACH] of its
 * target), or when it's on a path that isn't ours any more (its AI sent it
 * elsewhere). A walk after an entity looks for a new path to wherever the
 * entity is every [REPATH_TICKS] ticks, as vanilla's follow goals do.
 *
 * Walks are runtime state, not saved: one whose mob goes (dies, is removed,
 * its chunk unloads) is forgotten without an event, since its handlers go
 * with it.
 */
internal class MobPaths(private val platform: Platform, private val ended: (mob: UUID, reached: Boolean) -> Unit) : RuntimeService {
    override val name get() = "mob paths"

    private sealed interface Target {
        data class Place(val position: Vec3) : Target

        data class Follow(val entity: UUID) : Target
    }

    private class Walk(val target: Target, val speed: Double, val started: Long, var end: Vec3?)

    private val walks = LinkedHashMap<UUID, Walk>()
    private var ticks = 0L

    /** Sends [mob] to [position] in its own world. False when there's no path. */
    fun moveTo(mob: UUID, position: Vec3, speed: Double): Boolean = start(mob, Target.Place(position), position, speed)

    /** Sends [mob] after [entity], which the caller has checked is in the same world. False when there's no path. */
    fun follow(mob: UUID, entity: UUID, speed: Double): Boolean {
        val at = platform.worldEntities.info(entity)?.location ?: return false
        return start(mob, Target.Follow(entity), Vec3(at.x, at.y, at.z), speed)
    }

    private fun start(mob: UUID, target: Target, to: Vec3, speed: Double): Boolean {
        walks.remove(mob)
        val ops = platform.pathfinding
        if (!ops.moveTo(mob, to, speed)) return false
        walks[mob] = Walk(target, speed, ticks, ops.pathEnd(mob))
        return true
    }

    /** Stops [mob] walking, whoever sent it, and forgets its walk without an event. */
    fun stop(mob: UUID): Boolean {
        walks.remove(mob)
        return platform.pathfinding.stop(mob)
    }

    /** Forgets every walk, as the session ends: the mobs walk on, unwatched. */
    override fun stop() = walks.clear()

    override fun tick(phase: TickPhase) {
        if (phase == TickPhase.WORLD) pass()
    }

    private fun pass() {
        ticks++
        if (walks.isEmpty()) return
        val ops = platform.pathfinding
        // A handler may start or stop walks while this goes round.
        for ((mob, walk) in walks.entries.toList()) {
            if (walks[mob] !== walk) continue
            val here = platform.worldEntities.info(mob)?.location
            if (here == null) {
                walks.remove(mob)
                continue
            }
            val goal = when (val target = walk.target) {
                is Target.Place -> target.position
                is Target.Follow -> platform.worldEntities.info(target.entity)?.location?.takeIf { it.world == here.world }?.let {
                    Vec3(it.x, it.y, it.z)
                }
            }
            if (goal == null) {
                ops.stop(mob)
                end(mob, false)
                continue
            }
            if (!ops.hasPath(mob)) {
                end(mob, distance(Vec3(here.x, here.y, here.z), goal) <= PATH_REACH)
                continue
            }
            val end = ops.pathEnd(mob)
            val ours = walk.end
            if (ours != null && end != null && distance(end, ours) > SAME_END) {
                end(mob, false)
                continue
            }
            if (walk.target is Target.Follow && (ticks - walk.started) % REPATH_TICKS == 0L) {
                if (!ops.moveTo(mob, goal, walk.speed)) {
                    end(mob, false)
                    continue
                }
                walk.end = ops.pathEnd(mob)
            }
        }
    }

    private fun end(mob: UUID, reached: Boolean) {
        walks.remove(mob)
        ended(mob, reached)
    }

    companion object {
        /** How close to its target a walk has to end to count as reached: `PATH_REACH` in `spec/constants.ts`. */
        const val PATH_REACH = 2.0

        /** How often a walk after an entity looks for a new path: `REPATH_TICKS` in `spec/constants.ts`. */
        const val REPATH_TICKS = 10L

        /** How far a path's end may be from ours and still be ours (the game ends paths at block centres). */
        private const val SAME_END = 0.75
    }
}

private fun distance(a: Vec3, b: Vec3): Double {
    val d = a - b
    return sqrt(d.x * d.x + d.y * d.y + d.z * d.z)
}
