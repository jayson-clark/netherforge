package dev.netherforge.plugin.centity

import dev.netherforge.format.game.Box
import dev.netherforge.format.math.Matrix4
import dev.netherforge.format.math.Vec3
import dev.netherforge.format.math.degreesToRadians
import dev.netherforge.format.math.radiansToDegrees
import dev.netherforge.plugin.pathing.AirSearch
import dev.netherforge.plugin.pathing.GroundSearch
import dev.netherforge.plugin.pathing.PathResult
import dev.netherforge.plugin.pathing.SearchLimits
import dev.netherforge.plugin.pathing.Space
import dev.netherforge.plugin.pathing.Walker
import dev.netherforge.plugin.physics.boundsOf
import dev.netherforge.plugin.platform.Platform
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** What a walk goes to: a place in the centity's world, or something it goes after. */
internal sealed interface PathTarget {
    data class Place(val position: Vec3) : PathTarget

    data class Entity(val id: UUID) : PathTarget

    data class Centity(val id: UUID) : PathTarget
}

/** How a walk goes, from `move_to`'s options, checked. [speed] is in blocks a second. */
internal data class PathSettings(
    val speed: Double = CentityPaths.DEFAULT_SPEED,
    val fly: Boolean = false,
    val face: Boolean = true,
    val width: Double? = null,
    val height: Double? = null,
    val stepHeight: Double = CentityPaths.DEFAULT_STEP_HEIGHT,
    val maxDrop: Double = CentityPaths.DEFAULT_MAX_DROP,
    val range: Double = CentityPaths.DEFAULT_RANGE,
    val openDoors: Boolean = false
)

/**
 * One centity's walk: where it's going, the path it's on and how far along,
 * and where its feet are. The feet are the walk's own, in the world: the
 * anchor is moved by however much they move (and turned about them), so
 * whatever else shifts the anchor without moving anything in the world (the
 * physics pass re-anchoring) doesn't disturb it.
 */
internal class Walk(
    val target: PathTarget,
    val settings: PathSettings,
    val walker: Walker,
    var feet: Vec3,
    var goal: Vec3,
    path: PathResult
) {
    var points: List<Vec3> = path.points
        private set

    /** Whether [points] ends at the goal, rather than as close as the search got. */
    var complete = path.complete
        private set

    /** The point it's heading for. */
    var next = 1

    /** How fast it's falling, in blocks a second (walking only). */
    var falling = 0.0

    /** Ticks it has walked. */
    var age = 0L

    /** Ticks in a row it hasn't moved. */
    var stuck = 0

    /** New paths it has had to find since it last reached a point of one. */
    var repaths = 0

    fun follow(path: PathResult) {
        points = path.points
        complete = path.complete
        next = 1
        stuck = 0
    }
}

/**
 * Centities walking (or flying) where `centity:move_to` sends them: our own
 * A* over the world's collision shapes ([GroundSearch], [AirSearch]), and a
 * step along the path each tick.
 *
 * The step runs in the tick pipeline after animation and **before physics**:
 * moving the anchor carries every node with it (physics bodies included,
 * which simulate relative to it), and physics then settles the bodies where
 * the centity has just been walked to, before scripts see it and before
 * anything is shown. A script's `tick` handler sees where it is after this
 * tick's step, and has the last word (`teleport`, `stop_pathing`).
 *
 * Searching is bounded: [SearchLimits] caps one search at its range and
 * [dev.netherforge.plugin.pathing.NODE_LIMIT] places, answering the closest
 * place it reached; and the new paths walks look for on their own (following
 * something, a block in the way, a partial path walked to its end) share
 * [TICK_NODES] places a tick, waiting for the next tick past that. A long way
 * is so found in pieces as it's walked, over several ticks, rather than in
 * one tick-stalling search. `move_to` always searches at once, since it says
 * whether there's a way.
 *
 * Walks aren't saved; [Instance.redefine] (a reload) and a teleport drop one
 * without `path_end`, as `stop_pathing` does.
 */
class CentityPaths(private val platform: Platform, private val centities: Centities) {
    private val doors = Doors(platform)

    /** Places searched this tick by walks finding a new path on their own. */
    private var spent = 0

    /** A new tick: the search budget starts again. */
    fun beginTick() {
        spent = 0
    }

    // ---- what scripts ask -------------------------------------------------------

    /**
     * Sends [instance] to [target], replacing any walk it was on (without
     * `path_end`). False when there's no way any closer, or [target] is gone,
     * in another world or out of range: it then stands where it is.
     */
    internal fun start(instance: Instance, target: PathTarget, settings: PathSettings): Boolean {
        if (instance.removed) return false
        instance.walk = null
        val goal = locate(instance, target) ?: return false
        val (feet, walker) = measure(instance, settings)
        if (distance(feet, goal) > settings.range) return false
        val path = search(instance.anchor.world, walker, feet, goal, settings)
        if (path.points.isEmpty()) return false
        instance.walk = Walk(target, settings, walker, feet, goal, path)
        return true
    }

    /** Stops [instance]'s walk without `path_end`. Whether it was walking. */
    fun stop(instance: Instance): Boolean {
        if (instance.walk == null) return false
        instance.walk = null
        return true
    }

    /** Where [instance]'s path ends (its feet), or null when it isn't walking. */
    fun pathEnd(instance: Instance): Vec3? = instance.walk?.points?.lastOrNull()

    // ---- per tick ---------------------------------------------------------------

    /** Moves [instance] one tick along its walk, if it's on one. */
    fun step(instance: Instance) {
        val walk = instance.walk ?: return
        walk.age++
        if (walk.target !is PathTarget.Place) {
            val at = locate(instance, walk.target) ?: return end(instance, walk, false)
            if (distance(walk.feet, at) <= PATH_REACH) return end(instance, walk, true)
            if (walk.age % REPATH_TICKS == 0L && distance(at, walk.goal) > TARGET_MOVED) {
                walk.goal = at
                // Out of budget, it carries on along the old path and tries again in REPATH_TICKS.
                if (budgetLeft() && !repath(instance, walk, needsProgress = false)) return
            }
        }
        move(instance, walk)
    }

    private fun move(instance: Instance, walk: Walk) {
        val settings = walk.settings
        val from = walk.feet
        if (settings.openDoors) doors.pass(instance.anchor.world, walk, instance.openedDoors)
        // Where it passes this tick: each point of the path it reaches, then where it stops.
        val passed = ArrayList<Vec3>(4)
        passed += from
        var next = walk.next
        var left = settings.speed * Centities.SECONDS_PER_TICK
        while (left > 1e-9 && next < walk.points.size) {
            val at = passed.last()
            val to = walk.points[next]
            val d = if (settings.fly) distance(at, to) else across(at.x, at.z, to.x, to.z)
            if (d <= left) {
                passed += if (settings.fly) to else Vec3(to.x, at.y, to.z)
                left -= d
                next++
            } else {
                val s = left / d
                passed += Vec3(at.x + (to.x - at.x) * s, if (settings.fly) at.y + (to.y - at.y) * s else at.y, at.z + (to.z - at.z) * s)
                left = 0.0
            }
        }
        val fall = if (settings.fly) {
            0.0
        } else {
            min(walk.falling + GRAVITY * Centities.SECONDS_PER_TICK, MAX_FALL) *
                Centities.SECONDS_PER_TICK
        }
        val space = space(instance, walk, passed, fall)
        val feet = (if (settings.fly) fly(space, passed) else walkAlong(space, walk, passed, fall)) ?: return blocked(instance, walk)
        val grounded = settings.fly || walk.falling == 0.0
        if (!place(instance, walk, feet)) return blocked(instance, walk)
        if (next > walk.next) walk.repaths = 0
        walk.next = next
        if (distance(from, feet) < STILL && grounded) {
            if (++walk.stuck >= STUCK_TICKS) return blocked(instance, walk)
        } else {
            walk.stuck = 0
        }
        if (walk.next >= walk.points.size && grounded) arrived(instance, walk)
    }

    /** Where a flight along [passed] ends, or null when something is in the way of any stretch of it. */
    private fun fly(space: Space, passed: List<Vec3>): Vec3? {
        for (i in 1 until passed.size) {
            val a = passed[i - 1]
            val b = passed[i]
            if (!space.clearFlying(a.x, a.y, a.z, b.x, b.y, b.z)) return null
        }
        return passed.last()
    }

    /**
     * Where a walk along [passed] ends: each stretch at the height it has
     * reached, stepping up onto what's in the way when it can, then held up
     * where it stops or falling by up to [fall]. Null when a stretch is
     * blocked by more than a step. Sets [Walk.falling].
     */
    private fun walkAlong(space: Space, walk: Walk, passed: List<Vec3>, fall: Double): Vec3? {
        val height = walk.walker.height
        var y = passed[0].y
        for (i in 1 until passed.size) {
            val a = passed[i - 1]
            val b = passed[i]
            if (across(a.x, a.z, b.x, b.z) <= 1e-9 || space.clearBetween(a.x, a.z, b.x, b.z, y, y + height)) continue
            // In the way at this height: a step up, or a wall.
            val up = space.floorAt(b.x, b.z, y, walk.settings.stepHeight, 0.0)
            val fits = !up.isNaN() &&
                space.clearAt(a.x, a.z, y, up + height) &&
                space.clearBetween(a.x, a.z, b.x, b.z, up, up + height)
            if (!fits) return null
            y = up
            walk.falling = 0.0
        }
        val end = passed.last()
        if (!space.supported(end.x, end.z, y)) {
            // Nothing holds it up here: it falls, landing on whatever it reaches this tick.
            val land = space.floorAt(end.x, end.z, y, 0.0, fall)
            if (land.isNaN()) {
                walk.falling = fall / Centities.SECONDS_PER_TICK
                return Vec3(end.x, y - fall, end.z)
            }
            y = land
        }
        walk.falling = 0.0
        return Vec3(end.x, y, end.z)
    }

    /** The blocks one tick of [walk] can touch: round every point it passes, up a step and its height, down a fall. */
    private fun space(instance: Instance, walk: Walk, passed: List<Vec3>, fall: Double): Space {
        val half = walk.walker.width / 2
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var minZ = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        var maxZ = -Double.MAX_VALUE
        for (p in passed) {
            minX = min(minX, p.x)
            minY = min(minY, p.y)
            minZ = min(minZ, p.z)
            maxX = max(maxX, p.x)
            maxY = max(maxY, p.y)
            maxZ = max(maxZ, p.z)
        }
        val climb = if (walk.settings.fly) 0.0 else walk.settings.stepHeight
        val shapes = RegionShapes.around(
            platform.worlds,
            instance.anchor.world,
            minX - half,
            minY - fall - 1,
            minZ - half,
            maxX + half,
            maxY + climb + walk.walker.height + 1,
            maxZ + half
        )
        return Space(shapes, walk.walker)
    }

    /**
     * Puts [instance]'s feet at [feet], turning it towards the way it went
     * (about its feet) when it faces its way. False, moving nothing, when its
     * position would land in a chunk whose entities aren't loaded.
     */
    private fun place(instance: Instance, walk: Walk, feet: Vec3): Boolean {
        val from = walk.feet
        if (feet == from) return true
        var yaw = instance.yaw
        val dx = feet.x - from.x
        val dz = feet.z - from.z
        if (walk.settings.face && dx * dx + dz * dz > 1e-8) {
            val want = radiansToDegrees(atan2(-dx, dz))
            val by = Instance.wrapDegrees(want - yaw).coerceIn(-TURN_PER_TICK, TURN_PER_TICK)
            yaw += by
        }
        val anchor = instance.anchor
        var offset = Vec3(anchor.x - from.x, anchor.y - from.y, anchor.z - from.z)
        if (yaw != instance.yaw) offset = Matrix4().rotateY(-degreesToRadians(yaw - instance.yaw)).transformDirection(offset)
        val next = feet + offset
        val moved = anchor.copy(x = next.x, y = next.y, z = next.z)
        // Where its entities stand: a chunk whose entities aren't loaded would unload the instance under them.
        if (!platform.worlds.entitiesLoaded(moved)) return false
        if (yaw != instance.yaw) instance.turnTo(yaw)
        // The store gets where it ended up when the world saves, as for any move.
        instance.anchor = moved
        walk.feet = feet
        return true
    }

    /** It walked its path to the end. */
    private fun arrived(instance: Instance, walk: Walk) {
        val distance = distance(walk.feet, walk.goal)
        if (walk.target is PathTarget.Place && (walk.complete || distance <= PATH_REACH)) return end(instance, walk, distance <= PATH_REACH)
        // A partial path, or something it goes after that moved on: the rest of the way, if there's any closer to get.
        if (!budgetLeft()) return
        repath(instance, walk, needsProgress = true)
    }

    /** Something is in its way (a block, an unloaded chunk) or it hasn't moved for a while: another way, or it gives up. */
    private fun blocked(instance: Instance, walk: Walk) {
        if (!budgetLeft()) return
        if (++walk.repaths > MAX_REPATHS) return end(instance, walk, false)
        repath(instance, walk, needsProgress = false)
    }

    /**
     * Finds [walk] a new path from where it is. False, having ended the walk
     * as given up, when there's none (or, with [needsProgress], none that ends
     * any closer to the goal than it is now).
     */
    private fun repath(instance: Instance, walk: Walk, needsProgress: Boolean): Boolean {
        val path = search(instance.anchor.world, walk.walker, walk.feet, walk.goal, walk.settings, budgeted = true)
        val end = path.points.lastOrNull()
        val closer = end != null && distance(end, walk.goal) < distance(walk.feet, walk.goal) - PROGRESS
        if (end == null || (needsProgress && !closer)) {
            end(instance, walk, distance(walk.feet, walk.goal) <= PATH_REACH)
            return false
        }
        walk.follow(path)
        return true
    }

    /**
     * Shuts the doors [instance]'s walks opened that it isn't standing in, now that it isn't walking: a walk stopped
     * or replaced (`stop_pathing`, `move_to` again) on its way through one. One it stands in waits for it to leave.
     */
    fun shutBehind(instance: Instance) {
        if (instance.walk != null || instance.openedDoors.isEmpty()) return
        val feet = feetOf(instance)
        doors.shut(instance.anchor.world, instance.openedDoors) { !it.holds(feet) }
    }

    /** Shuts every door [instance]'s walks opened, wherever it is: it has been teleported away, or removed. */
    fun shutAll(instance: Instance) {
        if (instance.openedDoors.isNotEmpty()) doors.shut(instance.anchor.world, instance.openedDoors) { true }
    }

    private fun end(instance: Instance, walk: Walk, reached: Boolean) {
        doors.shut(instance.anchor.world, instance.openedDoors) { true }
        if (instance.walk === walk) instance.walk = null
        centities.pathEnded(instance, reached)
    }

    private fun budgetLeft() = spent < TICK_NODES

    private fun search(
        world: String,
        walker: Walker,
        from: Vec3,
        goal: Vec3,
        settings: PathSettings,
        budgeted: Boolean = false
    ): PathResult {
        val bricks = BrickShapes(platform.worlds, world)
        val space = Space(if (settings.openDoors) doors.through(world, bricks) else bricks, walker)
        val limits = SearchLimits(settings.range)
        val path = if (settings.fly) {
            AirSearch.find(space, from, goal, limits)
        } else {
            GroundSearch.find(space, from, goal, settings.stepHeight, settings.maxDrop, START_DROP, limits)
        }
        if (budgeted) spent += path.expanded
        return path
    }

    // ---- what it goes to, and what walks -----------------------------------------

    /** Where [target] is now, in [instance]'s world; null when it's gone or elsewhere. */
    private fun locate(instance: Instance, target: PathTarget): Vec3? {
        val world = instance.anchor.world
        return when (target) {
            is PathTarget.Place -> target.position
            is PathTarget.Entity -> platform.worldEntities.info(target.id)?.location?.takeIf {
                it.world == world
            }?.let { Vec3(it.x, it.y, it.z) }
            is PathTarget.Centity -> centities.find(target.id)?.takeIf {
                !it.removed && it !== instance && it.anchor.world == world
            }?.anchor?.let {
                Vec3(it.x, it.y, it.z)
            }
        }
    }

    /**
     * Where [instance]'s feet are (the middle of the bottom of its shown
     * hitboxes, in the world), and the walker it is: the hitboxes' wider side
     * and height, unless [settings] says otherwise.
     */
    private fun measure(instance: Instance, settings: PathSettings): Pair<Vec3, Walker> {
        val bounds = boundsOf(instance)
        val size = bounds?.let { it.max - it.min }
        val width = settings.width ?: size?.let { max(it.x, it.z) }?.coerceAtLeast(MIN_SIZE) ?: 1.0
        val height = settings.height ?: size?.y?.coerceAtLeast(MIN_SIZE) ?: 1.0
        return feetOf(instance, bounds) to Walker(width, height)
    }

    /** The box round [instance]'s shown hitboxes, in its own space, or null when it has none. */
    private fun boundsOf(instance: Instance): Box? {
        val composed = instance.compose()
        var bounds: Box? = null
        for ((index, node) in instance.definition.nodes.withIndex()) {
            if (node.hitbox == null || !instance.shown(index)) continue
            val box = boundsOf(centities.boxesOf(instance, index), composed[index])
            bounds = bounds?.let { union(it, box) } ?: box
        }
        return bounds
    }

    /** Where [instance]'s feet are in the world: the middle of the bottom of its hitboxes, or its anchor without any. */
    private fun feetOf(instance: Instance, bounds: Box? = boundsOf(instance)): Vec3 {
        val anchor = instance.anchor
        val middle =
            bounds?.let { instance.turn().transformDirection(Vec3((it.min.x + it.max.x) / 2, 0.0, (it.min.z + it.max.z) / 2)) } ?: Vec3.ZERO
        return Vec3(anchor.x + middle.x, anchor.y + (bounds?.min?.y ?: 0.0), anchor.z + middle.z)
    }

    companion object {
        /** How close it has to come to count as reached: `PATH_REACH` in `spec/constants.ts`. */
        const val PATH_REACH = 2.0

        /** How often a walk after something looks for a new path to it: `REPATH_TICKS` in `spec/constants.ts`. */
        const val REPATH_TICKS = 10L

        /** The defaults and limits of `CentityPathOptions`: the constants of the same names in `spec/constants.ts`. */
        const val DEFAULT_SPEED = 4.3
        const val MAX_SPEED = 20.0
        const val DEFAULT_STEP_HEIGHT = 1.0
        const val DEFAULT_MAX_DROP = 3.0
        const val DEFAULT_RANGE = 48.0
        const val MAX_RANGE = 128.0
        const val MAX_WIDTH = 8.0
        const val MAX_HEIGHT = 16.0

        /** How far below its feet a walk looks for ground to start from: `START_DROP` in `spec/constants.ts`. */
        const val START_DROP = 64.0

        /** Places the walks finding new paths on their own may search in one tick, together. */
        const val TICK_NODES = 16384

        /** How far it turns a tick when it faces its way, in degrees: half a turn in 0.3 s. */
        const val TURN_PER_TICK = 30.0

        /** Minecraft's gravity and fastest fall, in blocks a second (squared). */
        private const val GRAVITY = 32.0
        private const val MAX_FALL = 78.4

        /** How far what it goes after must move before it looks for a new path to it. */
        private const val TARGET_MOVED = 1.0

        /** How much closer a new path must end for a partial walk to carry on. */
        private const val PROGRESS = 0.5

        /** Moving less than this in a tick is standing still. */
        private const val STILL = 1e-3

        /** Ticks standing still before it looks for another way. */
        private const val STUCK_TICKS = 40

        /** New ways in a row it may look for without reaching a point of one, before it gives up. */
        private const val MAX_REPATHS = 3

        /** The smallest measured walker, so hitboxes scaled to nothing still walk as something. */
        private const val MIN_SIZE = 0.1
    }
}

private fun distance(a: Vec3, b: Vec3): Double {
    val d = a - b
    return sqrt(d.x * d.x + d.y * d.y + d.z * d.z)
}

private fun union(a: Box, b: Box) = Box(
    Vec3(min(a.min.x, b.min.x), min(a.min.y, b.min.y), min(a.min.z, b.min.z)),
    Vec3(max(a.max.x, b.max.x), max(a.max.y, b.max.y), max(a.max.z, b.max.z))
)

private fun across(ax: Double, az: Double, bx: Double, bz: Double): Double {
    val dx = bx - ax
    val dz = bz - az
    return sqrt(dx * dx + dz * dz)
}
