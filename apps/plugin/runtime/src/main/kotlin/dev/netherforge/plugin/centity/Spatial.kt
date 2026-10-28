package dev.netherforge.plugin.centity

import dev.netherforge.format.game.Box
import dev.netherforge.format.math.Vec3
import dev.netherforge.plugin.platform.Platform
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A region the spatial queries (`world:overlap_*`, `sweep`, `nearest`) test hitboxes against, in world
 * coordinates. [region] is the box around it, for the broad phase; [origin] the query's own point, which
 * distances and the line of sight are measured from; [test] the narrow phase, one box at a time.
 */
internal sealed interface QueryShape {
    val region: Box
    val origin: Vec3

    /** Whether [box] reaches into the shape, and where: null when it doesn't. */
    fun test(box: Box): Reach?

    /** How a box reached the shape: [distance] from [origin] (along the way, for a capsule) and the [position] that's measured to. */
    data class Reach(val distance: Double, val position: Vec3)

    /** Everything inside or touching the box from [min] to [max]; distances from its middle. */
    class Aabb(val min: Vec3, val max: Vec3) : QueryShape {
        override val region = Box(min, max)
        override val origin = (min + max) * 0.5

        override fun test(box: Box): Reach? {
            if (!overlaps(box, region)) return null
            val at = closest(box, origin)
            return Reach(length(at - origin), at)
        }
    }

    /** Everything within [radius] of [center]. */
    class Sphere(val center: Vec3, val radius: Double) : QueryShape {
        override val region = Box(center - Vec3(radius, radius, radius), center + Vec3(radius, radius, radius))
        override val origin = center

        override fun test(box: Box): Reach? {
            val at = closest(box, center)
            val distance = length(at - center)
            return if (distance <= radius) Reach(distance, at) else null
        }
    }

    /**
     * A cone with its tip at [tip], opening along the unit [axis] for [length] blocks, [halfAngle] degrees
     * from the axis to its side. A box counts when its point nearest the axis is inside, or the tip is in it.
     */
    class Cone(val tip: Vec3, val axis: Vec3, val length: Double, halfAngle: Double) : QueryShape {
        private val cosLimit = kotlin.math.cos(Math.toRadians(halfAngle.coerceIn(0.0, 180.0)))
        override val origin = tip
        override val region = Box(tip - Vec3(length, length, length), tip + Vec3(length, length, length))

        override fun test(box: Box): Reach? {
            if (inside(box, tip)) return Reach(0.0, tip)
            val t = nearestAlong(box, tip, axis * length)
            val at = closest(box, tip + axis * (length * t))
            val to = at - tip
            val distance = length(to)
            if (distance > length) return null
            if (distance > EPSILON && dot(to, axis) / distance < cosLimit) return null
            return Reach(distance, at)
        }
    }

    /**
     * A sphere of [radius] moved from [from] to [to]: a box counts from the first point along the way
     * where it touches, and [Reach.position] is the sphere's middle there.
     */
    class Capsule(val from: Vec3, val to: Vec3, val radius: Double) : QueryShape {
        private val along = to - from
        private val span = length(along)
        override val origin = from
        override val region = Box(
            Vec3(min(from.x, to.x) - radius, min(from.y, to.y) - radius, min(from.z, to.z) - radius),
            Vec3(max(from.x, to.x) + radius, max(from.y, to.y) + radius, max(from.z, to.z) + radius)
        )

        override fun test(box: Box): Reach? {
            if (distanceTo(box, from) <= radius) return Reach(0.0, from)
            val nearest = nearestAlong(box, from, along)
            if (distanceTo(box, from + along * nearest) > radius) return null
            // Where the line enters the box grown by the radius: exact when the sphere meets a face there,
            // and before it the sphere can't touch, since the grown box holds the sphere's every touch.
            val entry = entry(box, radius)?.takeIf { it <= nearest } ?: 0.0
            if (distanceTo(box, from + along * entry) <= radius + EPSILON) return Reach(span * entry, from + along * entry)
            // It meets an edge or a corner: the distance falls from there to the nearest point, so halve the way.
            var lo = entry
            var hi = nearest
            repeat(SEARCH_STEPS) {
                val mid = (lo + hi) / 2
                if (distanceTo(box, from + along * mid) <= radius) hi = mid else lo = mid
            }
            return Reach(span * hi, from + along * hi)
        }

        /** Where (0 to 1) the segment enters [box] grown by [grow] on every side, or null when it never does. */
        private fun entry(box: Box, grow: Double): Double? {
            var enter = 0.0
            var leave = 1.0
            val starts = doubleArrayOf(from.x, from.y, from.z)
            val steps = doubleArrayOf(along.x, along.y, along.z)
            val lows = doubleArrayOf(box.min.x - grow, box.min.y - grow, box.min.z - grow)
            val highs = doubleArrayOf(box.max.x + grow, box.max.y + grow, box.max.z + grow)
            for (axis in 0..2) {
                val d = steps[axis]
                if (d == 0.0) {
                    if (starts[axis] < lows[axis] || starts[axis] > highs[axis]) return null
                    continue
                }
                var t0 = (lows[axis] - starts[axis]) / d
                var t1 = (highs[axis] - starts[axis]) / d
                if (t0 > t1) t0 = t1.also { t1 = t0 }
                enter = max(enter, t0)
                leave = min(leave, t1)
                if (enter > leave) return null
            }
            return enter
        }
    }

    companion object {
        const val EPSILON = 1e-9

        /** Steps of each one-dimensional search: a ternary search closes in by a third a step, halving by a half. */
        const val SEARCH_STEPS = 64

        fun overlaps(a: Box, b: Box) =
            a.min.x <= b.max.x && a.max.x >= b.min.x && a.min.y <= b.max.y && a.max.y >= b.min.y && a.min.z <= b.max.z && a.max.z >= b.min.z

        fun inside(box: Box, p: Vec3) =
            p.x >= box.min.x && p.x <= box.max.x && p.y >= box.min.y && p.y <= box.max.y && p.z >= box.min.z && p.z <= box.max.z

        /** The point of [box] nearest [p]: [p] itself when it's inside. */
        fun closest(box: Box, p: Vec3) =
            Vec3(p.x.coerceIn(box.min.x, box.max.x), p.y.coerceIn(box.min.y, box.max.y), p.z.coerceIn(box.min.z, box.max.z))

        fun distanceTo(box: Box, p: Vec3) = length(closest(box, p) - p)

        /**
         * Where along the segment from [start] by [along] (0 to 1) it comes nearest [box]. The distance from a
         * point moving along a line to a box is convex, so a ternary search finds its least.
         */
        fun nearestAlong(box: Box, start: Vec3, along: Vec3): Double {
            var lo = 0.0
            var hi = 1.0
            repeat(SEARCH_STEPS) {
                val a = lo + (hi - lo) / 3
                val b = hi - (hi - lo) / 3
                if (distanceTo(box, start + along * a) <= distanceTo(box, start + along * b)) hi = b else lo = a
            }
            return (lo + hi) / 2
        }

        fun dot(a: Vec3, b: Vec3) = a.x * b.x + a.y * b.y + a.z * b.z

        fun length(v: Vec3) = sqrt(dot(v, v))
    }
}

/**
 * What a query may find, said once for every query: [kinds] are centities' (as the project names them)
 * and entities' types together; [exclude] are instances and entities by id.
 */
internal data class QueryFilter(
    val centities: Boolean = true,
    val players: Boolean = true,
    val entities: Boolean = true,
    val kinds: Set<String>? = null,
    val excludeCentities: Set<UUID> = emptySet(),
    val excludeEntities: Set<UUID> = emptySet(),
    val lineOfSight: Boolean = false,
    val limit: Int = Int.MAX_VALUE
)

/** One thing a query found: an instance's node, or an entity by id, with how it was reached. */
internal sealed interface Found {
    val reach: QueryShape.Reach

    data class Centity(val instance: Instance, val node: Int, override val reach: QueryShape.Reach) : Found

    data class Entity(val id: UUID, override val reach: QueryShape.Reach) : Found
}

/**
 * The spatial queries: hitboxes of centities (through [Centities.obstacles], so only the instances near
 * the region are looked at) and the server's entities' boxes, tested against a [QueryShape], nearest first.
 */
internal class SpatialQueries(private val platform: Platform, private val centities: Centities) {
    /** Everything in [world] that [shape] reaches, as [filter] allows, nearest first. */
    fun find(world: String, shape: QueryShape, filter: QueryFilter): List<Found> {
        val found = ArrayList<Found>()
        val region = shape.region
        if (filter.centities) {
            for ((instance, node, reach) in centityHits(world, shape)) {
                if (instance.id in filter.excludeCentities) continue
                if (filter.kinds != null && instance.centity !in filter.kinds) continue
                found += Found.Centity(instance, node, reach)
            }
        }
        if (filter.players || filter.entities) {
            for (entity in platform.worldEntities.boxes(world, region)) {
                if (if (entity.player) !filter.players else !filter.entities) continue
                if (entity.id in filter.excludeEntities) continue
                if (filter.kinds != null && entity.kind !in filter.kinds) continue
                val reach = shape.test(entity.box) ?: continue
                found += Found.Entity(entity.id, reach)
            }
        }
        found.sortBy { it.reach.distance }
        val seen = if (filter.lineOfSight) found.filter { sees(world, shape.origin, it.reach.position) } else found
        return if (seen.size > filter.limit) seen.subList(0, filter.limit) else seen
    }

    /** Each loaded instance in [world] that [shape] reaches, by its nearest box. */
    fun centityHits(world: String, shape: QueryShape): List<Triple<Instance, Int, QueryShape.Reach>> {
        val region = shape.region
        val candidates = ArrayList<Instance>()
        centities.obstacles.near(world, region.min.x, region.min.y, region.min.z, region.max.x, region.max.y, region.max.z, candidates)
        val hits = ArrayList<Triple<Instance, Int, QueryShape.Reach>>()
        for (instance in candidates) {
            if (instance.removed || !centities.loaded(instance)) continue
            val obstacles = centities.obstacles.of(instance)
            val a = instance.anchor
            var best: QueryShape.Reach? = null
            var bestNode = -1
            for ((i, local) in obstacles.boxes.withIndex()) {
                val box = Box(
                    Vec3(local.min.x + a.x, local.min.y + a.y, local.min.z + a.z),
                    Vec3(
                        local.max.x + a.x,
                        local.max.y + a.y,
                        local.max.z + a.z
                    )
                )
                if (!QueryShape.overlaps(box, region)) continue
                val reach = shape.test(box) ?: continue
                if (best == null || reach.distance < best.distance) {
                    best = reach
                    bestNode = obstacles.nodes[i]
                }
            }
            if (best != null) hits += Triple(instance, bestNode, best)
        }
        return hits
    }

    /** What one of an instance's nodes touches: another instance's node ([otherNode]), or an entity. */
    data class Contact(val node: Int, val other: Instance?, val otherNode: Int, val entity: UUID?)

    /**
     * Everything [instance]'s shown hitboxes touch now (box against box, touching faces included), by the
     * other instance's or entity's id: its own other nodes don't count. What `touch` compares tick to tick.
     */
    fun contacts(instance: Instance): Map<UUID, Contact> {
        val own = centities.obstacles.of(instance)
        val bounds = own.bounds ?: return emptyMap()
        val a = instance.anchor
        val world = a.world
        fun placed(box: Box, at: dev.netherforge.plugin.platform.Location) =
            Box(Vec3(box.min.x + at.x, box.min.y + at.y, box.min.z + at.z), Vec3(box.max.x + at.x, box.max.y + at.y, box.max.z + at.z))
        val mine = own.boxes.map { placed(it, a) }
        val region = placed(bounds, a)

        /** The first of my nodes whose box touches [box], or -1. */
        fun touchedBy(box: Box): Int {
            if (!QueryShape.overlaps(box, region)) return -1
            for ((i, m) in mine.withIndex()) if (QueryShape.overlaps(m, box)) return own.nodes[i]
            return -1
        }
        val found = LinkedHashMap<UUID, Contact>()
        val candidates = ArrayList<Instance>()
        centities.obstacles.near(world, region.min.x, region.min.y, region.min.z, region.max.x, region.max.y, region.max.z, candidates)
        for (other in candidates) {
            if (other === instance || other.removed || !centities.loaded(other)) continue
            val theirs = centities.obstacles.of(other)
            for ((i, box) in theirs.boxes.withIndex()) {
                val node = touchedBy(placed(box, other.anchor))
                if (node >= 0) {
                    found[other.id] = Contact(node, other, theirs.nodes[i], null)
                    break
                }
            }
        }
        for (entity in platform.worldEntities.boxes(world, region)) {
            val node = touchedBy(entity.box)
            if (node >= 0) found[entity.id] = Contact(node, null, -1, entity.id)
        }
        return found
    }

    /** Whether a ray from [from] reaches [to] with no block's collision shape in the way. */
    private fun sees(world: String, from: Vec3, to: Vec3): Boolean {
        val d = to - from
        val distance = QueryShape.length(d)
        if (distance < 1e-6) return true
        val hit = platform.worlds.raycastBlocks(world, from, d * (1 / distance), distance, false) ?: return true
        return QueryShape.length(hit.position - from) >= distance - 1e-4
    }
}
