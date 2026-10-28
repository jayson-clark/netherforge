package dev.netherforge.plugin.physics

import dev.netherforge.format.game.Box
import dev.netherforge.format.math.Vec3
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * One point at which two things touch, in the instance's space.
 *
 * A contact is the only thing that can make a body rotate: a point under the
 * middle of a box pushes it straight up, the same point at a corner pushes it
 * up *and* spins it. Tipping, toppling and rolling all follow from getting
 * these positions right, which is why a manifold is generated rather than just
 * a normal.
 */
class Contact(
    /** Where the touch is, in the instance's space. */
    val point: Vec3,
    /** Unit vector out of the second thing towards the first: obstacle to body, or B to A. */
    val normal: Vec3,
    /**
     * How far the two have sunk into each other. Negative while still apart,
     * which the solver reads as a limit on closing speed rather than as
     * something to push out of.
     */
    val depth: Double
) {
    /** Normal impulse accumulated over the solver's iterations. */
    var normalImpulse: Double = 0.0

    /** Separation impulse, accumulated apart so it never becomes real motion. */
    var biasImpulse: Double = 0.0

    /** Friction impulse along each of the two tangents, likewise accumulated. */
    var tangentImpulse1: Double = 0.0
    var tangentImpulse2: Double = 0.0

    /**
     * The closing speed when the contact was found, captured before any impulse:
     * restitution is a fact about the impact, not about what the solver has
     * left by the time it bounces.
     */
    var approach: Double = 0.0
}

/**
 * Where shapes meet.
 *
 * The same machinery answers body-against-world and body-against-body: two
 * convex shapes, an overlap, a normal and some points. Who gets pushed is the
 * solver's business. A static obstacle is a box whose axes are the world's.
 *
 * Detection is discrete: anything found is already slightly overlapping, which
 * is what the solver's positional correction is for, and why a step is sliced.
 */
object Contacts {
    /** How much overlap is ignored, so resting bodies aren't shoved apart. */
    const val SLOP = 0.005

    /**
     * How wide a gap still counts as a contact.
     *
     * Without it a body resting *exactly* on a surface reports separated, falls
     * for a slice, is pushed back to exactly touching, and reports separated
     * again: a hovering, humming, never-still box. A contact across a gap has a
     * negative depth, read by the solver as "may close this far and no
     * further", which also brakes something fast at the surface instead of
     * detecting it once it is through.
     */
    const val SKIN = 0.02

    /** Past this the deepest are kept: a body across a floor of blocks makes four per block. */
    private const val MAX_CONTACTS = 32

    /** The same for one pair of bodies, a far smaller question. */
    private const val MAX_PAIR_CONTACTS = 8

    /** Cross products of near-parallel axes are noise, not separating axes. */
    private const val PARALLEL = 1e-6

    /** Three faces each plus the nine edge products. */
    private const val AXIS_COUNT = 15

    /** Overlaps closer than this to each other are a tie, in blocks. */
    private const val TIE = 1e-3

    /** Overlap across a normal at or below this is a touching edge, not a face. */
    private const val EDGE_ONLY = 1e-4

    /** Past this, a normal is treated as running along that axis. */
    private const val AXIS_ALIGNED = 0.999

    private val WORLD_AXES = arrayOf(Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, 1.0))

    // ---- against the world ------------------------------------------------

    /** Every contact between [shape] and the static [obstacles], deepest first when trimmed. */
    fun generate(shape: BodyShape, obstacles: List<Box>): List<Contact> {
        if (obstacles.isEmpty()) return emptyList()
        val out = mutableListOf<Contact>()
        val placed = Placed(shape)
        for (obstacle in obstacles) {
            against(shape, placed, WORLD_AXES, obstacle.center, obstacle.size * 0.5, out, worldBox = true)
        }
        return trimmed(out, MAX_CONTACTS)
    }

    /** A shape's axes and each part's centre in the instance's space: the same for every box it's met against, so worked out once. */
    private class Placed(shape: BodyShape) {
        val axes: Array<Vec3> = if (shape.sphere) WORLD_AXES else shape.axes()
        val centers: Array<Vec3> = if (shape.sphere) emptyArray() else Array(shape.parts.size) { shape.centerOf(axes, shape.parts[it]) }

        /** [boxPair]'s candidate axes, reused across every box this shape meets: each pair fills all it reads. */
        val directions = arrayOfNulls<Vec3>(AXIS_COUNT)
        val overlap = DoubleArray(AXIS_COUNT)
    }

    /** One shape against one oriented box, whoever that box belongs to. */
    private fun against(
        shape: BodyShape,
        placed: Placed,
        axesB: Array<Vec3>,
        centerB: Vec3,
        halfB: Vec3,
        out: MutableList<Contact>,
        worldBox: Boolean = false
    ) {
        if (shape.sphere) {
            sphereAgainstBox(shape.center, shape.radius, axesB, centerB, halfB, out)
            return
        }
        // Each part is met separately, all measured from the one centre of
        // mass. That is what lets a stair rest on its lower slab and topple
        // about it: the impulses are under the part actually touching.
        val axesA = placed.axes
        for ((index, part) in shape.parts.withIndex()) {
            if (worldBox && apartOnWorldAxes(axesA, placed.centers[index], part.half, centerB, halfB)) continue
            boxPair(axesA, placed.centers[index], part.half, axesB, centerB, halfB, placed.directions, placed.overlap, out)
        }
    }

    // ---- between two bodies -----------------------------------------------

    /**
     * Every contact between two bodies, normals out of [b] towards [a].
     *
     * Bounding spheres first: most pairs are nowhere near each other, and one
     * distance check is far cheaper than fifteen axes per pair of parts.
     */
    fun between(a: BodyShape, b: BodyShape): List<Contact> {
        val d = a.center - b.center
        val reach = a.boundingRadius() + b.boundingRadius() + SKIN
        if (dot(d, d) > reach * reach) return emptyList()

        val out = mutableListOf<Contact>()

        if (a.sphere && b.sphere) {
            sphereAgainstSphere(a, b, out)
            return trimmed(out, MAX_PAIR_CONTACTS)
        }

        if (b.sphere) {
            // Meet the sphere as the box's obstacle, then turn the answer
            // round: a contact always points out of B towards A.
            val axesA = a.axes()
            for (part in a.parts) {
                val found = mutableListOf<Contact>()
                sphereAgainstBox(b.center, b.radius, axesA, a.centerOf(axesA, part), part.half, found)
                for (contact in found) out += reversed(contact)
            }
            return trimmed(out, MAX_PAIR_CONTACTS)
        }

        val axesB = b.axes()
        val placedA = Placed(a)
        for (part in b.parts) {
            against(a, placedA, axesB, b.centerOf(axesB, part), part.half, out)
        }
        return trimmed(out, MAX_PAIR_CONTACTS)
    }

    private fun reversed(contact: Contact): Contact = Contact(contact.point, contact.normal * -1.0, contact.depth)

    private fun trimmed(out: MutableList<Contact>, limit: Int): List<Contact> {
        if (out.size <= limit) return out
        out.sortByDescending { it.depth }
        return out.subList(0, limit)
    }

    // ---- spheres ----------------------------------------------------------

    private fun sphereAgainstSphere(a: BodyShape, b: BodyShape, out: MutableList<Contact>) {
        val d = a.center - b.center
        val distanceSquared = dot(d, d)
        val reach = a.radius + b.radius

        if (distanceSquared > (reach + SKIN) * (reach + SKIN)) return

        // Exactly coincident spheres have no direction to separate along;
        // straight up is as good as any.
        if (distanceSquared < 1e-12) {
            out += Contact(a.center, Vec3(0.0, 1.0, 0.0), reach)
            return
        }

        val distance = sqrt(distanceSquared)
        val normal = d * (1.0 / distance)
        // Halfway into the overlap, where the two surfaces meet.
        val along = b.radius + (distance - reach) * 0.5
        out += Contact(b.center + normal * along, normal, reach - distance)
    }

    /** A sphere against an oriented box, normal pointing at the sphere. */
    private fun sphereAgainstBox(
        center: Vec3,
        radius: Double,
        axes: Array<Vec3>,
        boxCenter: Vec3,
        boxHalf: Vec3,
        out: MutableList<Contact>
    ) {
        // In the box's own frame the question is trivial, so the sphere is
        // taken there and the answer brought back.
        val d = center - boxCenter
        val localX = dot(d, axes[0])
        val localY = dot(d, axes[1])
        val localZ = dot(d, axes[2])

        val clampedX = localX.coerceIn(-boxHalf.x, boxHalf.x)
        val clampedY = localY.coerceIn(-boxHalf.y, boxHalf.y)
        val clampedZ = localZ.coerceIn(-boxHalf.z, boxHalf.z)

        val offX = localX - clampedX
        val offY = localY - clampedY
        val offZ = localZ - clampedZ
        val distanceSquared = offX * offX + offY * offY + offZ * offZ

        val reach = radius + SKIN
        if (distanceSquared > reach * reach) return

        val closest = boxCenter + axes[0] * clampedX + axes[1] * clampedY + axes[2] * clampedZ

        if (distanceSquared > 1e-12) {
            val distance = sqrt(distanceSquared)
            val normal = (axes[0] * offX + axes[1] * offY + axes[2] * offZ) * (1.0 / distance)
            out += Contact(closest, normal, radius - distance)
            return
        }

        // The centre is inside the box, with no direction to push along. The
        // nearest face is the least wrong answer and gets the sphere out fastest.
        var best = boxHalf.x - abs(localX)
        var index = 0
        var sign = if (localX >= 0.0) 1.0 else -1.0

        val toY = boxHalf.y - abs(localY)
        if (toY < best) {
            best = toY
            index = 1
            sign = if (localY >= 0.0) 1.0 else -1.0
        }
        val toZ = boxHalf.z - abs(localZ)
        if (toZ < best) {
            best = toZ
            index = 2
            sign = if (localZ >= 0.0) 1.0 else -1.0
        }

        out += Contact(center, axes[index] * sign, radius + best)
    }

    // ---- boxes ------------------------------------------------------------

    /**
     * Two oriented boxes, by the separating axis theorem.
     *
     * Fifteen axes: three faces of each box, and the nine products of one's
     * edges with the other's. Miss the products and two boxes meeting edge to
     * edge report a deep overlap along a face normal they never crossed, which
     * reads as a box flicked sideways by a corner it merely brushed.
     *
     * The normal comes back pointing out of B towards A.
     */
    private fun boxPair(
        axesA: Array<Vec3>,
        centerA: Vec3,
        halfA: Vec3,
        axesB: Array<Vec3>,
        centerB: Vec3,
        halfB: Vec3,
        directions: Array<Vec3?>,
        overlap: DoubleArray,
        out: MutableList<Contact>
    ) {
        val toA = centerA - centerB

        // Gathered rather than compared as they go, because choosing between
        // them needs to see them all. See [pick].
        var slot = 0

        fun test(axis: Vec3): Boolean {
            val lengthSquared = dot(axis, axis)
            if (lengthSquared < PARALLEL) {
                // A degenerate cross product is no axis at all. Recorded as
                // hopeless rather than skipped, so the slots stay lined up
                // with the frames [pick] reasons about.
                overlap[slot++] = Double.MAX_VALUE
                return true
            }
            val unit = axis * (1.0 / sqrt(lengthSquared))

            val reachA = projectionRadius(axesA, halfA, unit)
            val reachB = projectionRadius(axesB, halfB, unit)
            val distance = dot(toA, unit)

            val found = reachA + reachB - abs(distance)
            if (found <= -SKIN) return false

            // Always out of B towards A, so every caller knows which way a contact pushes.
            directions[slot] = if (distance < 0.0) unit * -1.0 else unit
            overlap[slot] = found
            slot++
            return true
        }

        // B's frame first, then A's, then the products. [pick] and [supportOf]
        // both depend on that order.
        for (axis in axesB) if (!test(axis)) return
        for (axis in axesA) if (!test(axis)) return
        for (b in axesB) {
            for (a in axesA) {
                if (!test(cross(b, a))) return
            }
        }

        val chosen = pick(overlap)

        // A face contact needs the shapes to genuinely overlap across the
        // normal, not merely touch. A cube on a floor of separate blocks is
        // flush with the *next* block on two axes at once, and those are edges
        // it slides over, not faces it is pressed against. Answering them as
        // faces stops a body dead on the seams of its own floor. Edge products
        // are exempt: an edge-to-edge touch is all they ever describe.
        if (chosen < 6 && supportOf(overlap, chosen) <= EDGE_ONLY) return

        manifold(axesA, centerA, halfA, axesB, centerB, halfB, directions[chosen]!!, overlap[chosen], out)
    }

    /**
     * Whether a part is clear of a world-aligned box along one of the world
     * axes: the first three axes [boxPair] tries for such a box, worked out with
     * the very same arithmetic (a world axis dotted with anything is just that
     * component). When this says apart, [boxPair] would have returned on that
     * axis with nothing; this just gets there without building its axes.
     */
    private fun apartOnWorldAxes(axesA: Array<Vec3>, centerA: Vec3, halfA: Vec3, centerB: Vec3, halfB: Vec3): Boolean {
        val a0 = axesA[0]
        val a1 = axesA[1]
        val a2 = axesA[2]
        if (abs(a0.x) * halfA.x + abs(a1.x) * halfA.y + abs(a2.x) * halfA.z + halfB.x - abs(centerA.x - centerB.x) <= -SKIN) return true
        if (abs(a0.y) * halfA.x + abs(a1.y) * halfA.y + abs(a2.y) * halfA.z + halfB.y - abs(centerA.y - centerB.y) <= -SKIN) return true
        return abs(a0.z) * halfA.x + abs(a1.z) * halfA.y + abs(a2.z) * halfA.z + halfB.z - abs(centerA.z - centerB.z) <= -SKIN
    }

    /**
     * The points to apply the impulse at, once the normal is known.
     *
     * Every corner of one box inside the other counts. A box flat on the ground
     * gives four and stays put; pushed out over a ledge it gives two, both on
     * one side of its centre, and tips. The behaviour falls out of where the
     * points are, not out of any rule about tipping.
     */
    private fun manifold(
        axesA: Array<Vec3>,
        centerA: Vec3,
        halfA: Vec3,
        axesB: Array<Vec3>,
        centerB: Vec3,
        halfB: Vec3,
        normal: Vec3,
        depth: Double,
        out: MutableList<Contact>
    ) {
        val before = out.size

        // B's surface, measured along the normal: everything of A past it is inside.
        val surface = dot(centerB, normal) + projectionRadius(axesB, halfB, normal)

        // A corner counts when it is over the face, a question about the two
        // axes *across* the normal only. Requiring it within B along the normal
        // too would lose every corner of a shape that went right through
        // something thin, exactly when they most need catching.
        for (corner in cornersOf(axesA, centerA, halfA)) {
            if (!across(corner, axesB, centerB, halfB, normal)) continue
            val sunk = surface - dot(corner, normal)
            if (sunk <= -SKIN) continue
            out += Contact(corner, normal, minOf(sunk, depth))
        }

        // And the other way about: a small box poking into a big one's face
        // has no corner of the big one inside it.
        for (corner in cornersOf(axesB, centerB, halfB)) {
            if (!contains(axesA, centerA, halfA, corner)) continue
            out += Contact(corner, normal, depth)
        }

        if (out.size > before) return

        // Neither has a corner in the other: the boxes meet edge to edge. One
        // point at A's deepest extremity is a fair stand-in.
        out += Contact(support(axesA, centerA, halfA, normal * -1.0), normal, depth)
    }

    /**
     * Which candidate axis is the contact normal.
     *
     * The shallowest overlap, but not naively: two axes are constantly tied at
     * zero, and picking the wrong one wedges a box against a floor of separate
     * blocks. A body resting on such a floor exactly touches the next tile on
     * *two* axes: the top face it is about to slide onto and the side face it
     * is flush against. Take the side and it is stopped dead by a seam.
     *
     * Ties are broken by how much the boxes overlap on the axes *across* the
     * candidate: a real face contact overlaps there, the spurious side contact
     * merely touches. Cross-product axes never win a tie, because an edge
     * normal beating a face normal by nothing is always wrong.
     */
    private fun pick(overlap: DoubleArray): Int {
        var best = 0
        var bestOverlap = Double.MAX_VALUE
        var bestSupport = -Double.MAX_VALUE

        for (index in 0 until AXIS_COUNT) {
            val found = overlap[index]
            if (found == Double.MAX_VALUE) continue

            val support = if (index < 6) supportOf(overlap, index) else -Double.MAX_VALUE
            val better = found < bestOverlap - TIE || (found < bestOverlap + TIE && support > bestSupport)
            if (!better) continue
            best = index
            bestOverlap = minOf(found, bestOverlap)
            bestSupport = support
        }
        return best
    }

    /** How much the shapes overlap on the two axes across [index], within its own frame (B's for 0..2, A's for 3..5). */
    private fun supportOf(overlap: DoubleArray, index: Int): Double {
        val base = if (index < 3) 0 else 3
        return minOf(
            overlap[base + (index - base + 1) % 3],
            overlap[base + (index - base + 2) % 3]
        )
    }

    /** How far a box reaches from its centre along a unit axis. */
    private fun projectionRadius(axes: Array<Vec3>, half: Vec3, axis: Vec3): Double =
        abs(dot(axes[0], axis)) * half.x + abs(dot(axes[1], axis)) * half.y + abs(dot(axes[2], axis)) * half.z

    private fun extent(half: Vec3, index: Int): Double = when (index) {
        0 -> half.x
        1 -> half.y
        else -> half.z
    }

    /**
     * Whether a point lies over a box's face, ignoring the axis the normal runs
     * along. A normal off a cross product runs along none, and is tested on all
     * three: the conservative answer.
     */
    private fun across(point: Vec3, axes: Array<Vec3>, center: Vec3, half: Vec3, normal: Vec3): Boolean {
        val d = point - center
        for (index in 0..2) {
            val axis = axes[index]
            if (abs(dot(normal, axis)) >= AXIS_ALIGNED) continue
            val e = extent(half, index)
            val along = dot(d, axis)
            if (along < -e - SLOP || along > e + SLOP) return false
        }
        return true
    }

    /** The eight corners of one box, in the instance's space. */
    private fun cornersOf(axes: Array<Vec3>, center: Vec3, half: Vec3): List<Vec3> {
        val out = ArrayList<Vec3>(8)
        for (sx in SIGNS) {
            for (sy in SIGNS) {
                for (sz in SIGNS) {
                    out += center + axes[0] * (sx * half.x) + axes[1] * (sy * half.y) + axes[2] * (sz * half.z)
                }
            }
        }
        return out
    }

    private val SIGNS = doubleArrayOf(-1.0, 1.0)

    /** Whether a point in the instance's space is inside one box. */
    private fun contains(axes: Array<Vec3>, center: Vec3, half: Vec3, point: Vec3): Boolean {
        val d = point - center
        for (index in 0..2) {
            val e = extent(half, index)
            val along = dot(d, axes[index])
            if (along < -e - SLOP || along > e + SLOP) return false
        }
        return true
    }

    /** The point of one box furthest along a unit direction. */
    private fun support(axes: Array<Vec3>, center: Vec3, half: Vec3, direction: Vec3): Vec3 {
        var point = center
        for (index in 0..2) {
            val axis = axes[index]
            val e = extent(half, index)
            point += axis * (if (dot(axis, direction) >= 0.0) e else -e)
        }
        return point
    }
}

/**
 * A body's collider where it currently is: a set of boxes sharing one
 * orientation, or a sphere.
 *
 * Every part turns together and every contact is measured from the one centre
 * of mass, which makes a compound body behave as one rigid object rather than
 * a bag of loose ones. Held apart from velocity because collision only asks
 * where something is.
 */
class BodyShape(
    /** The centre of mass, in the instance's space. */
    val center: Vec3,
    val orientation: Quat,
    /** The collider's boxes, measured from the centre of mass in the body frame. */
    val parts: List<ColliderPart>,
    val sphere: Boolean,
    val radius: Double
) {
    /** The body's three axis directions in the instance's space. */
    fun axes(): Array<Vec3> = arrayOf(
        orientation.rotate(Vec3(1.0, 0.0, 0.0)),
        orientation.rotate(Vec3(0.0, 1.0, 0.0)),
        orientation.rotate(Vec3(0.0, 0.0, 1.0))
    )

    /** Where one part's own centre sits in the instance's space. */
    fun centerOf(axes: Array<Vec3>, part: ColliderPart): Vec3 {
        val o = part.offset
        return center + axes[0] * o.x + axes[1] * o.y + axes[2] * o.z
    }

    /** The radius of the sphere the whole shape fits inside, for broad queries. */
    fun boundingRadius(): Double = boundingRadiusOf(parts, sphere, radius)
}
