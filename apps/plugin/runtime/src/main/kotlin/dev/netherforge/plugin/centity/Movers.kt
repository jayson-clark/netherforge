package dev.netherforge.plugin.centity

import dev.netherforge.format.centity.ResolvedBody
import dev.netherforge.format.game.Box
import dev.netherforge.format.math.Vec3
import dev.netherforge.plugin.platform.Platform
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A centity's `body` as it runs (`centity.json`'s, or one `set_body` gave it): its settings, its velocity
 * and walk, and what its last move found. Runtime state on [Instance.mover], reset by a reload.
 */
class Mover(var settings: ResolvedBody) {
    /** Blocks per second, along the world's axes. */
    var velocity: Vec3 = Vec3.ZERO

    /** The velocity it walks at (`set_walk_velocity`), or null when it isn't walking. */
    var walk: Vec3? = null

    var onGround = false
    var inLiquid = false

    /** The face that stopped it sideways on its last move, pointing back at it; null when nothing did. */
    var wall: Vec3? = null

    /** What one tick's move did that scripts hear: how fast it landed (null if it didn't) and what it ran into. */
    data class Moved(val landed: Double?, val bumps: List<Bump>)

    /** It ran into a face whose [normal] points back at it, going [speed] blocks per second into it. */
    data class Bump(val normal: Vec3, val speed: Double)

    /** The upward speed that rises [height] blocks under its gravity and drag, as [Movers.step] integrates a tick at a time. */
    fun jumpSpeed(height: Double): Double {
        if (height <= 0) return 0.0
        val gravity = settings.gravity * (if (inLiquid) settings.liquidGravity else 1.0)
        if (settings.fly || gravity <= 0) return sqrt(2 * max(gravity, 1.0) * height)
        // The apex of a launch at v, found by halving v: what Movers.step would make of it, tick by tick.
        fun apex(v0: Double): Double {
            val keep = (1 - (if (inLiquid) settings.liquidDrag else settings.drag)).pow(Movers.SECONDS)
            var v = v0
            var y = 0.0
            repeat(MAX_RISE_TICKS) {
                v = v * keep - gravity * Movers.SECONDS
                if (v <= 0) return y
                y += v * Movers.SECONDS
            }
            return y
        }
        var lo = 0.0
        var hi = 1.0
        while (apex(hi) < height && hi < MAX_JUMP_SPEED) hi *= 2
        repeat(48) {
            val mid = (lo + hi) / 2
            if (apex(mid) < height) lo = mid else hi = mid
        }
        return hi
    }

    private companion object {
        /** No jump rises for longer than this many ticks. */
        const val MAX_RISE_TICKS = 2000
        const val MAX_JUMP_SPEED = 1e4
    }
}

/**
 * Moves every centity's `body` a tick: drag, gravity and walking change its velocity, then it moves by it
 * along each axis against the blocks' collision shapes, stepping up what it can, as Minecraft moves an
 * entity. The world's blocks come from one [RegionShapes] read a tick.
 */
internal class Movers(private val platform: Platform) {
    /** Moves [instance]'s body one tick, or answers null when it has none. */
    fun step(instance: Instance): Mover.Moved? {
        val mover = instance.mover ?: return null
        val s = mover.settings
        val anchor = instance.anchor
        val world = anchor.world
        val half = s.width / 2
        val middle = platform.blocks.get(world, floor(anchor.x).toInt(), floor(anchor.y + s.height / 2).toInt(), floor(anchor.z).toInt())
        mover.inLiquid = middle?.liquid == true

        var v = mover.velocity
        // Drag first, then gravity, then the walk: so a walk reaches exactly the speed it asks for.
        val keep = (1 - (if (mover.inLiquid) s.liquidDrag else s.drag)).pow(SECONDS)
        v = v * keep
        if (!s.fly) {
            val gravity = s.gravity * (if (mover.inLiquid) s.liquidGravity else 1.0)
            v = Vec3(v.x, max(v.y - gravity * SECONDS, -s.maxFallSpeed), v.z)
        }
        val walk = mover.walk
        if (walk != null) {
            val rate = (if (s.fly || mover.onGround) s.acceleration else s.airAcceleration) * SECONDS
            v = if (s.fly) approach(v, walk, rate) else approachFlat(v, walk, rate)
        } else if (mover.onGround && !s.fly) {
            v = approachFlat(v, Vec3.ZERO, s.friction * SECONDS)
        }

        val want = v * SECONDS
        var box = Box(Vec3(anchor.x - half, anchor.y, anchor.z - half), Vec3(anchor.x + half, anchor.y + s.height, anchor.z + half))
        val wasOnGround = mover.onGround
        val bumps = ArrayList<Mover.Bump>(2)
        var landed: Double? = null
        mover.wall = null
        if (!s.blocks) {
            box = offset(box, want.x, want.y, want.z)
            mover.onGround = false
        } else {
            val reach = s.stepHeight + 1
            val shapes = RegionShapes.around(
                platform.worlds,
                world,
                min(box.min.x, box.min.x + want.x) - 1,
                min(box.min.y, box.min.y + want.y) - 1,
                min(box.min.z, box.min.z + want.z) - 1,
                max(box.max.x, box.max.x + want.x) + 1,
                max(box.max.y, box.max.y + want.y) + reach,
                max(box.max.z, box.max.z + want.z) + 1
            )
            val solids = solidsAround(shapes, box, want, reach)
            if (solids.any { overlaps(it, shrink(box)) }) {
                // Inside blocks (spawned there, or the world changed round it): it rises out rather than freezes.
                box = offset(box, want.x, max(want.y, RISE * SECONDS), want.z)
                v = Vec3(v.x, max(v.y, 0.0), v.z)
                mover.onGround = false
            } else {
                val moved = collide(solids, box, want)
                var result = moved
                // Stepping up: blocked sideways while on the ground, try it from a step higher.
                if (s.stepHeight > 0 && (wasOnGround || moved.hitDown) && (moved.hitX || moved.hitZ)) {
                    val up = clip(solids, box, 1, s.stepHeight)
                    val raised = offset(box, 0.0, up, 0.0)
                    val across = collide(solids, raised, Vec3(want.x, 0.0, want.z))
                    val down = clip(solids, across.box, 1, -(up + max(0.0, -want.y)))
                    val stepped = offset(across.box, 0.0, down, 0.0)
                    val farther = flatDistance(box, stepped) > flatDistance(box, moved.box) + EPSILON
                    if (farther) result = Collision(stepped, across.hitX, false, down > -(up + max(0.0, -want.y)) + EPSILON, false)
                }
                box = result.box
                val bounce = s.bounce
                if (result.hitX) {
                    bumps += Mover.Bump(Vec3(-sign(want.x), 0.0, 0.0), abs(v.x))
                    mover.wall = Vec3(-sign(want.x), 0.0, 0.0)
                    v = Vec3(-v.x * bounce, v.y, v.z)
                }
                if (result.hitZ) {
                    bumps += Mover.Bump(Vec3(0.0, 0.0, -sign(want.z)), abs(v.z))
                    mover.wall = mover.wall?.let { Vec3(it.x, 0.0, -sign(want.z)) } ?: Vec3(0.0, 0.0, -sign(want.z))
                    v = Vec3(v.x, v.y, -v.z * bounce)
                }
                if (result.hitUp) {
                    bumps += Mover.Bump(Vec3(0.0, -1.0, 0.0), abs(v.y))
                    v = Vec3(v.x, -v.y * bounce, v.z)
                }
                if (result.hitDown) {
                    if (!wasOnGround) landed = -v.y
                    // A bounce too small to see would never stop.
                    val up = -v.y * bounce
                    v = Vec3(v.x, if (up > MIN_BOUNCE) up else 0.0, v.z)
                }
                mover.onGround = result.hitDown && v.y <= 0.0
            }
        }
        mover.velocity = v
        val next = anchor.copy(x = (box.min.x + box.max.x) / 2, y = box.min.y, z = (box.min.z + box.max.z) / 2)
        if (next.x != anchor.x || next.y != anchor.y || next.z != anchor.z) {
            // Where its entities stand: a chunk whose entities aren't loaded would unload it under them.
            if (platform.worlds.entitiesLoaded(next)) instance.anchor = next else mover.velocity = Vec3.ZERO
        }
        return Mover.Moved(landed, bumps)
    }

    /** What a move of a box came to: where it ended and which ways something stopped it. */
    private data class Collision(val box: Box, val hitX: Boolean, val hitUp: Boolean, val hitDown: Boolean, val hitZ: Boolean)

    /** Moves [box] by [want] against [solids]: up or down first, then the longer of the two sideways ways first, as Minecraft does. */
    private fun collide(solids: List<Box>, box: Box, want: Vec3): Collision {
        val dy = clip(solids, box, 1, want.y)
        var moved = offset(box, 0.0, dy, 0.0)
        val xFirst = abs(want.x) >= abs(want.z)
        var dx = 0.0
        var dz = 0.0
        if (xFirst) {
            dx = clip(solids, moved, 0, want.x)
            moved = offset(moved, dx, 0.0, 0.0)
            dz = clip(solids, moved, 2, want.z)
            moved = offset(moved, 0.0, 0.0, dz)
        } else {
            dz = clip(solids, moved, 2, want.z)
            moved = offset(moved, 0.0, 0.0, dz)
            dx = clip(solids, moved, 0, want.x)
            moved = offset(moved, dx, 0.0, 0.0)
        }
        return Collision(
            moved,
            hitX = abs(dx - want.x) > EPSILON,
            hitUp = want.y > 0 && abs(dy - want.y) > EPSILON,
            hitDown = want.y < 0 && abs(dy - want.y) > EPSILON,
            hitZ = abs(dz - want.z) > EPSILON
        )
    }

    /** How far [box] may move along [axis] (0 x, 1 y, 2 z) toward [distance] before a solid stops it. */
    private fun clip(solids: List<Box>, box: Box, axis: Int, distance: Double): Double {
        if (distance == 0.0) return 0.0
        var d = distance
        for (s in solids) {
            // Only what it overlaps across the other two axes is in its way.
            if (axis != 0 && (s.max.x <= box.min.x + EPSILON || s.min.x >= box.max.x - EPSILON)) continue
            if (axis != 1 && (s.max.y <= box.min.y + EPSILON || s.min.y >= box.max.y - EPSILON)) continue
            if (axis != 2 && (s.max.z <= box.min.z + EPSILON || s.min.z >= box.max.z - EPSILON)) continue
            val (low, high) = when (axis) {
                0 -> box.min.x to box.max.x
                1 -> box.min.y to box.max.y
                else -> box.min.z to box.max.z
            }
            val (sLow, sHigh) = when (axis) {
                0 -> s.min.x to s.max.x
                1 -> s.min.y to s.max.y
                else -> s.min.z to s.max.z
            }
            if (d > 0 && sLow >= high - EPSILON) d = min(d, sLow - high)
            if (d < 0 && sHigh <= low + EPSILON) d = max(d, sHigh - low)
        }
        return d
    }

    /** Every block box a move of [box] by [want] (and a step [reach] up) could meet; a block in an unloaded chunk is a full cube. */
    private fun solidsAround(shapes: RegionShapes, box: Box, want: Vec3, reach: Double): List<Box> {
        val solids = ArrayList<Box>()
        val x0 = floor(min(box.min.x, box.min.x + want.x) - EPSILON).toInt()
        val x1 = floor(max(box.max.x, box.max.x + want.x) + EPSILON).toInt()
        val y0 = floor(min(box.min.y, box.min.y + want.y) - EPSILON).toInt() - 1
        val y1 = floor(max(box.max.y, box.max.y + want.y) + reach).toInt()
        val z0 = floor(min(box.min.z, box.min.z + want.z) - EPSILON).toInt()
        val z1 = floor(max(box.max.z, box.max.z + want.z) + EPSILON).toInt()
        for (x in x0..x1) {
            for (z in z0..z1) {
                for (y in y0..y1) {
                    val boxes = shapes.boxes(x, y, z)
                    if (boxes == null) {
                        solids += Box(Vec3(x.toDouble(), y.toDouble(), z.toDouble()), Vec3(x + 1.0, y + 1.0, z + 1.0))
                    } else {
                        solids.addAll(boxes)
                    }
                }
            }
        }
        return solids
    }

    companion object {
        /** A tick, in seconds. */
        const val SECONDS = 1.0 / 20

        /** What two faces may be apart and still touch: far below anything a player sees. */
        private const val EPSILON = 1e-7

        /** How fast a body inside blocks rises out of them, blocks per second. */
        private const val RISE = 2.0

        /** A bounce off the ground slower than this, blocks per second, stops instead. */
        private const val MIN_BOUNCE = 1.0

        private fun offset(box: Box, x: Double, y: Double, z: Double) =
            Box(Vec3(box.min.x + x, box.min.y + y, box.min.z + z), Vec3(box.max.x + x, box.max.y + y, box.max.z + z))

        private fun shrink(box: Box) = Box(box.min + Vec3(1e-4, 1e-4, 1e-4), box.max - Vec3(1e-4, 1e-4, 1e-4))

        private fun overlaps(a: Box, b: Box) =
            a.min.x < b.max.x && a.max.x > b.min.x && a.min.y < b.max.y && a.max.y > b.min.y && a.min.z < b.max.z && a.max.z > b.min.z

        private fun flatDistance(from: Box, to: Box): Double {
            val dx = to.min.x - from.min.x
            val dz = to.min.z - from.min.z
            return dx * dx + dz * dz
        }

        private fun sign(v: Double) = if (v > 0) 1.0 else -1.0

        /** [from] moved toward [to] by at most [rate], on every axis together. */
        fun approach(from: Vec3, to: Vec3, rate: Double): Vec3 {
            val d = to - from
            val length = sqrt(d.x * d.x + d.y * d.y + d.z * d.z)
            return if (length <= rate) to else from + d * (rate / length)
        }

        /** [from]'s sideways part moved toward [to]'s by at most [rate]; its up and down part as it is. */
        fun approachFlat(from: Vec3, to: Vec3, rate: Double): Vec3 {
            val dx = to.x - from.x
            val dz = to.z - from.z
            val length = sqrt(dx * dx + dz * dz)
            return if (length <= rate) Vec3(to.x, from.y, to.z) else Vec3(from.x + dx * rate / length, from.y, from.z + dz * rate / length)
        }
    }
}
