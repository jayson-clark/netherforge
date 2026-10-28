package dev.netherforge.plugin.physics

import dev.netherforge.format.centity.ResolvedPhysics
import dev.netherforge.format.game.Box
import dev.netherforge.format.math.Vec3
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Moves every rigid body on one instance for one step, and works out what they
 * ran into, including each other.
 *
 * An impulse solver, not clip-and-slide. Every touch becomes a contact point,
 * and the impulse resolving it acts at that point: a box supported under its
 * middle sits still, and the same box supported under one edge is turned by the
 * very impulse holding it up. Tipping, toppling and rolling aren't rules here;
 * they fall out of applying forces where they land.
 *
 * Bodies are stepped **together**, in one shared set of slices. A contact
 * between two bodies applies equal and opposite impulses, and neither half can
 * be worked out without the other body there to receive it.
 *
 * Contacts are solved by sequential impulses: each corrected in turn, the
 * whole set several times over, with each contact's total impulse accumulated
 * and never allowed to pull. That lets four corners of a box on the ground
 * agree on holding it up instead of fighting, and lets a struck ball pass its
 * momentum down a rack inside one tick.
 *
 * Pure: bodies and numbers in, mutated bodies out. The host gathers obstacles
 * and writes the answers onto nodes.
 */
object PhysicsSolver {
    /**
     * One body handed to the solver.
     *
     * [active] tells a simulated body from one merely *there*. A settled body
     * still has to be collided with (a rolling ball would otherwise pass
     * through a resting one) but integrating it costs for nothing. Being hit
     * wakes it.
     */
    class Entry(val physics: ResolvedPhysics, val body: RigidBody) {
        var obstacles: List<Box> = emptyList()

        /** False for a sleeping body: collided with, but not integrated. */
        var active: Boolean = true

        /** True when the body finished the step standing on something. */
        var onGround: Boolean = false

        /** True when anything touched it at all. */
        var touched: Boolean = false

        /** True when another body woke this one by running into it. */
        var wokenByContact: Boolean = false

        /**
         * What it touched this step, by what it touched: another entry, or
         * null for its obstacles, split by which way the surface faces (the
         * floor and a wall are different things to bump into). The first
         * contact with each, at the fastest closing speed seen.
         */
        val touches = LinkedHashMap<Pair<Entry?, Int>, Touch>()
    }

    /**
     * One thing a body touched in a step: where (the instance's space), the
     * normal from it towards the body, and how fast they were closing, in
     * blocks per second.
     */
    class Touch(val point: Vec3, val normal: Vec3, var speed: Double)

    /** What one step did to a single body, for the one-body callers. */
    data class Result(val onGround: Boolean, val touched: Boolean)

    /**
     * How upright a contact has to be to count as ground: about 45 degrees.
     * Steeper and the body is against a slope rather than on it, and grounding
     * it would let a script jump off a wall it is sliding down.
     */
    private const val GROUND_NORMAL = 0.7

    /** Passes over the contact set per slice. More is steadier and costs more. */
    private const val ITERATIONS = 8

    /** Fraction of an overlap corrected per second of simulated time. */
    private const val CORRECTION = 0.2

    /** Blocks per second the correction may add, however deep the overlap. */
    private const val MAX_CORRECTION = 3.0

    /** Closing speed below which an impact doesn't bounce, in blocks per second. */
    private const val BOUNCE_THRESHOLD = 1.0

    /** A slice never covers less than this, however thin the collider is. */
    private const val MIN_SLICE = 0.05

    /**
     * Slices per step. Two at minimum: contacts are found at the start of a
     * slice and integrated at its end, so one slice per tick lets something
     * landing mid-tick sink a little before anything answers.
     */
    private const val MIN_SLICES = 2
    private const val MAX_SLICES = 16

    /** Slack on the obstacle query, in blocks. */
    private const val MARGIN = 0.1

    /**
     * The region a body can reach over one step, for the host to gather
     * obstacles from.
     *
     * Measured from the bounding sphere rather than the box, so it doesn't
     * change as the body turns: a query that shrank as something rotated would
     * let it clip a wall it had already been told about.
     */
    fun regionFor(physics: ResolvedPhysics, body: RigidBody, seconds: Double): Box {
        val reach = body.boundingRadius()
        val r = Vec3(reach, reach, reach)
        val base = Box(body.center - r, body.center + r)
        val startY = body.velocity.y * seconds
        val endY = (body.velocity.y - physics.gravity * seconds) * seconds
        return base
            .sweep(body.velocity.x * seconds, min(startY, endY), body.velocity.z * seconds)
            .sweep(0.0, maxOf(startY, endY), 0.0)
            .expand(MARGIN)
    }

    /** Advances one body against static obstacles only. */
    fun step(physics: ResolvedPhysics, body: RigidBody, seconds: Double, obstacles: List<Box>): Result {
        val entry = Entry(physics, body)
        entry.obstacles = obstacles
        solve(listOf(entry), seconds)
        return Result(entry.onGround, entry.touched)
    }

    /** Whether anything is touching the body where it currently stands. */
    fun supported(body: RigidBody, obstacles: List<Box>): Boolean = Contacts.generate(body.shape(), obstacles).isNotEmpty()

    /** Advances every entry by [seconds], against their obstacles and each other. */
    fun solve(entries: List<Entry>, seconds: Double) {
        if (seconds <= 0.0 || entries.isEmpty()) return

        // One slice count for all, since they move in step. The fastest or
        // thinnest body sets the pace: a slice stepping further than a body is
        // thick could put it through a wall it never overlapped.
        var slices = MIN_SLICES
        for (entry in entries) {
            if (!entry.active) continue
            slices = maxOf(slices, sliceCount(entry.physics, entry.body, seconds))
        }
        val dt = seconds / slices

        val constraints = mutableListOf<Constraint>()

        repeat(slices) {
            constraints.clear()
            gather(entries, constraints)

            // Impact speeds are measured *before* this slice's gravity. The
            // speed something arrived at a surface with is the speed it had
            // when it got there; measured after, a body sitting still reads as
            // arriving at one slice of gravity, and anything bouncy hops on
            // the spot forever.
            for (constraint in constraints) constraint.prepare()

            for (entry in entries) {
                if (entry.active) integrateVelocity(entry.physics, entry.body, dt)
            }

            // A pass that applies no impulse at all leaves every velocity and
            // every accumulated impulse as it was, so each pass after it would
            // do exactly the same nothing: stop there. Separation works on its
            // own velocities alone, so it settles (and stops) on its own.
            var solving = true
            var separating = true
            for (iteration in 0 until ITERATIONS) {
                if (solving) {
                    var applied = false
                    for (constraint in constraints) applied = constraint.solveNormal(dt) or applied
                    for (constraint in constraints) applied = constraint.solveFriction() or applied
                    solving = applied
                }
                if (separating) {
                    var applied = false
                    for (constraint in constraints) applied = constraint.solveSeparation(dt) or applied
                    separating = applied
                }
                if (!solving && !separating) break
            }

            for (entry in entries) {
                if (!entry.active) continue
                val body = entry.body
                // Real motion plus whatever separation the overlaps called for.
                // The separation moves the body and is then forgotten, so a
                // lively collision can't end with more energy than it began.
                body.center += (body.velocity + body.biasVelocity) * dt
                body.orientation = body.orientation.integrate(body.angularVelocity + body.biasAngularVelocity, dt)
                body.clearBias()
            }
        }
    }

    /**
     * Every contact in play this slice: each active body against its own
     * obstacles, and each pair of bodies. A pair both asleep is skipped,
     * nothing can have changed between two still things.
     */
    private fun gather(entries: List<Entry>, out: MutableList<Constraint>) {
        for (entry in entries) {
            if (!entry.active) continue
            val contacts = Contacts.generate(entry.body.shape(), entry.obstacles)
            if (contacts.isEmpty()) continue
            entry.touched = true
            for (contact in contacts) {
                if (contact.normal.y > GROUND_NORMAL) entry.onGround = true
                touch(entry, null to facing(contact.normal), contact.point, contact.normal, -dot(entry.body.velocity, contact.normal))
                out += Constraint(contact, entry, null, entry.physics.bounciness, entry.physics.friction)
            }
        }

        if (entries.size < 2) return

        for (i in entries.indices) {
            for (j in i + 1 until entries.size) {
                val a = entries[i]
                val b = entries[j]
                if (!a.active && !b.active) continue

                val contacts = Contacts.between(a.body.shape(), b.body.shape())
                if (contacts.isEmpty()) continue

                // A settled body is woken by being *hit*, not merely touched.
                // Waking on contact alone livelocks a pile: each sleeper is
                // roused by a neighbour that has just fallen asleep, and
                // nothing in the heap ever settles.
                if (!a.active && stirring(b)) {
                    a.active = true
                    a.wokenByContact = true
                }
                if (!b.active && stirring(a)) {
                    b.active = true
                    b.wokenByContact = true
                }
                if (!a.active && !b.active) continue

                a.touched = true
                b.touched = true

                // One bounciness and one grip between two surfaces. The
                // livelier wins the bounce (a rubber ball off a dead wall still
                // bounces); grip is the geometric mean, so anything on ice slides.
                val restitution = maxOf(a.physics.bounciness, b.physics.bounciness)
                val friction = sqrt(a.physics.friction * b.physics.friction)

                for (contact in contacts) {
                    if (contact.normal.y > GROUND_NORMAL) a.onGround = true
                    if (contact.normal.y < -GROUND_NORMAL) b.onGround = true
                    val closing = -dot(a.body.velocity - b.body.velocity, contact.normal)
                    touch(a, b to 0, contact.point, contact.normal, closing)
                    touch(b, a to 0, contact.point, contact.normal * -1.0, closing)
                    out += Constraint(contact, a, b, restitution, friction)
                }
            }
        }
    }

    private fun touch(entry: Entry, what: Pair<Entry?, Int>, point: Vec3, normal: Vec3, speed: Double) {
        val known = entry.touches[what]
        if (known == null) entry.touches[what] = Touch(point, normal, maxOf(speed, 0.0)) else known.speed = maxOf(known.speed, speed)
    }

    /** Which of the six ways a surface faces, by its normal's largest component: 0..5 for +x, −x, +y, −y, +z, −z. */
    private fun facing(normal: Vec3): Int {
        val x = abs(normal.x)
        val y = abs(normal.y)
        val z = abs(normal.z)
        return when {
            y >= x && y >= z -> if (normal.y >= 0) 2 else 3
            x >= z -> if (normal.x >= 0) 0 else 1
            else -> if (normal.z >= 0) 4 else 5
        }
    }

    /** Whether a body is moving enough to be worth waking a neighbour for. */
    private fun stirring(entry: Entry): Boolean {
        if (!entry.active) return false
        val v = entry.body.velocity
        val w = entry.body.angularVelocity
        return dot(v, v) > BodyState.SLEEP_SPEED * BodyState.SLEEP_SPEED ||
            dot(w, w) > BodyState.SLEEP_SPIN * BodyState.SLEEP_SPIN
    }

    // ---- integration ------------------------------------------------------

    private fun integrateVelocity(physics: ResolvedPhysics, body: RigidBody, dt: Double) {
        // Rates are per second and applied per slice, so a step in one slice or
        // six comes out at the same speed.
        var velocity = Vec3(body.velocity.x, body.velocity.y - physics.gravity * dt, body.velocity.z)
        velocity *= (1.0 - physics.drag).pow(dt)
        body.velocity = capped(velocity, physics.maxSpeed)

        if (body.locked) {
            body.angularVelocity = Vec3.ZERO
            return
        }

        val spin = body.angularVelocity * (1.0 - physics.angularDrag).pow(dt)
        body.angularVelocity = capped(spin, physics.maxSpinRadians)
    }

    private fun capped(v: Vec3, ceiling: Double): Vec3 {
        val length = sqrt(dot(v, v))
        return if (length > ceiling && length > 0.0) v * (ceiling / length) else v
    }

    // ---- contact solving --------------------------------------------------

    /**
     * One contact, with whoever it acts on.
     *
     * [b] is null against the world, which is a body of infinite mass: it takes
     * an equal and opposite impulse and is unmoved, so there is nothing to apply.
     */
    private class Constraint(val contact: Contact, val a: Entry, val b: Entry?, val restitution: Double, val friction: Double) {
        private val offsetA = contact.point - a.body.center
        private val offsetB = b?.let { contact.point - it.body.center }

        // Neither body turns until the slice's iterations are over, so how
        // readily they yield along each direction is worked out once, not per
        // iteration.
        private val normalInverse = inverseMass(contact.normal)
        private var tangent1: Vec3? = null
        private var tangent2: Vec3 = Vec3.ZERO
        private var tangentInverse1 = 0.0
        private var tangentInverse2 = 0.0

        /** How fast the two are closing along [direction], right now. */
        private fun approach(direction: Vec3): Double {
            var relative = a.body.velocityAt(offsetA)
            if (b != null) relative -= b.body.velocityAt(offsetB!!)
            return dot(relative, direction)
        }

        /** The same for the separation-only velocities. */
        private fun separating(): Double {
            var relative = a.body.biasVelocityAt(offsetA)
            if (b != null) relative -= b.body.biasVelocityAt(offsetB!!)
            return dot(relative, contact.normal)
        }

        /** Both sides' resistance to an impulse along [direction], added up. */
        private fun inverseMass(direction: Vec3): Double =
            a.body.inverseMassAlong(direction, offsetA) + (b?.body?.inverseMassAlong(direction, offsetB!!) ?: 0.0)

        /** Equal and opposite: what A gains, B loses. This is the whole of it. */
        private fun apply(impulse: Vec3) {
            a.body.applyImpulse(impulse, offsetA)
            b?.body?.applyImpulse(impulse * -1.0, offsetB!!)
        }

        fun prepare() {
            contact.approach = approach(contact.normal)
        }

        /** Returns whether it applied an impulse, as do the other two. */
        fun solveNormal(dt: Double): Boolean {
            val inverse = normalInverse
            if (inverse <= 1e-9) return false

            // A contact across a gap may close that gap this slice and no
            // further: braking at the surface rather than after passing
            // through stops something thin being crossed in one step. A gap
            // under the slop is no gap; left in, a resting body closes a hair
            // every slice, gravity obliges, and it hums against the floor.
            val gap = -contact.depth
            val allowed = if (gap > Contacts.SLOP) -gap / dt else 0.0

            // Only a real impact bounces. Without this floor a resting body
            // re-bounces off its own settling velocity every step.
            val bounce = if (contact.approach < -BOUNCE_THRESHOLD) -restitution * contact.approach else 0.0

            var lambda = (allowed - approach(contact.normal) + bounce) / inverse

            // Accumulated and clamped, not clamped per iteration: a contact
            // may pull *this* iteration to undo an overshoot from the last, so
            // long as its total never becomes a pull.
            val previous = contact.normalImpulse
            contact.normalImpulse = (previous + lambda).coerceAtLeast(0.0)
            lambda = contact.normalImpulse - previous
            if (lambda == 0.0) return false

            apply(contact.normal * lambda)
            return true
        }

        /**
         * Pushes apart an overlap detection has already let happen, into the
         * separation-only velocity. Gently: something wedged deep would
         * otherwise be fired out.
         */
        fun solveSeparation(dt: Double): Boolean {
            val excess = contact.depth - Contacts.SLOP
            if (excess <= 0.0) return false
            val inverse = normalInverse
            if (inverse <= 1e-9) return false

            val target = minOf(CORRECTION * excess / dt, MAX_CORRECTION)
            var lambda = (target - separating()) / inverse

            val previous = contact.biasImpulse
            contact.biasImpulse = (previous + lambda).coerceAtLeast(0.0)
            lambda = contact.biasImpulse - previous
            if (lambda == 0.0) return false

            val impulse = contact.normal * lambda
            a.body.applyBiasImpulse(impulse, offsetA)
            b?.body?.applyBiasImpulse(impulse * -1.0, offsetB!!)
            return true
        }

        /**
         * Coulomb friction along both directions across the contact.
         *
         * Capped by the normal impulse actually applied, so something pressed
         * hard grips harder, and a contact holding nothing up has no friction:
         * a body isn't dragged to a halt by a wall it merely slides past.
         */
        fun solveFriction(): Boolean {
            if (contact.normalImpulse <= 0.0 || friction <= 0.0) return false
            val limit = friction * contact.normalImpulse
            val first = tangent1 ?: tangents(contact.normal).let { (t1, t2) ->
                tangent2 = t2
                tangentInverse1 = inverseMass(t1)
                tangentInverse2 = inverseMass(t2)
                t1.also { tangent1 = it }
            }
            val before1 = contact.tangentImpulse1
            val before2 = contact.tangentImpulse2
            contact.tangentImpulse1 = applyFriction(first, tangentInverse1, before1, limit)
            contact.tangentImpulse2 = applyFriction(tangent2, tangentInverse2, before2, limit)
            // An impulse was applied exactly when an accumulated total moved.
            return contact.tangentImpulse1 != before1 || contact.tangentImpulse2 != before2
        }

        private fun applyFriction(tangent: Vec3, inverse: Double, accumulated: Double, limit: Double): Double {
            if (inverse <= 1e-9) return accumulated

            val total = (accumulated - approach(tangent) / inverse).coerceIn(-limit, limit)
            val lambda = total - accumulated
            if (lambda != 0.0) apply(tangent * lambda)
            return total
        }
    }

    /** Two unit directions across the contact, perpendicular to the normal. */
    private fun tangents(normal: Vec3): Pair<Vec3, Vec3> {
        // Crossed with whichever world axis the normal is least aligned to, so
        // the result is never the degenerate zero vector.
        val seed = if (abs(normal.y) < 0.7) Vec3(0.0, 1.0, 0.0) else Vec3(1.0, 0.0, 0.0)
        val t = cross(normal, seed)
        val length = sqrt(dot(t, t))
        if (length < 1e-9) return Vec3(1.0, 0.0, 0.0) to Vec3(0.0, 0.0, 1.0)
        val tangent = t * (1.0 / length)
        return tangent to cross(normal, tangent)
    }

    private fun sliceCount(physics: ResolvedPhysics, body: RigidBody, seconds: Double): Int {
        val limit = maxOf(body.thinnest() * 0.5, MIN_SLICE)
        val endY = body.velocity.y - physics.gravity * seconds
        val reach = maxOf(abs(body.velocity.x), abs(body.velocity.z), abs(body.velocity.y), abs(endY)) * seconds
        return ceil(reach / limit).toInt().coerceIn(MIN_SLICES, MAX_SLICES)
    }
}
