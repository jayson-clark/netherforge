package dev.netherforge.plugin.physics

import dev.netherforge.format.centity.ResolvedPhysics
import dev.netherforge.format.math.Vec3
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * One box of a body's collider, in the body's own frame, measured from its
 * centre of mass.
 *
 * A body is a *set* of these because a hitbox often is one: a stair is two
 * slabs, a fence a post and its arms. Collapsing those to the box around them
 * would rest a stair at the height of a full block and tip it as one.
 */
class ColliderPart(val offset: Vec3, val half: Vec3)

/**
 * A body in flight: where it is, which way up, and how it is moving.
 *
 * Mutable and reused within a step, because the solver walks it through
 * several slices per tick for every body on the server.
 *
 * Mass is carried as its inverse, which is what every formula wants and what
 * makes an immovable obstacle expressible (inverse mass zero). Against static
 * geometry mass cancels exactly: the impulse and the inertia resisting it both
 * scale with it. Only between two bodies does it decide anything.
 */
class RigidBody(
    var center: Vec3,
    var orientation: Quat,
    var velocity: Vec3,
    var angularVelocity: Vec3,
    /** The collider, as boxes measured from the centre of mass. */
    val parts: List<ColliderPart>,
    val sphere: Boolean,
    /** The sphere's radius; meaningless when [sphere] is false. */
    val radius: Double,
    /** True when the orientation is pinned and impulses can't turn it. */
    val locked: Boolean,
    /** How heavy it is. Zero would be immovable, and is not a body. */
    val mass: Double = 1.0
) {
    val invMass: Double = if (mass > 0.0) 1.0 / mass else 0.0

    /** The inverse inertia tensor in the body's frame, mass included. See [Inertia.scaled]. */
    val invInertia: Inertia = when {
        locked -> Inertia.LOCKED
        sphere -> Inertia.ofSphere(radius).scaled(invMass)
        else -> Inertia.ofParts(parts).scaled(invMass)
    }

    /**
     * Velocity that exists only to push overlaps apart and never becomes motion
     * the body keeps.
     *
     * Detection is discrete, so shapes are always found slightly inside each
     * other. Separating them with a real impulse feeds energy in: a lively
     * collision ends with more than it started with, and a stack shakes itself
     * apart. Held apart here, the correction moves the body and is thrown away.
     */
    var biasVelocity: Vec3 = Vec3.ZERO
    var biasAngularVelocity: Vec3 = Vec3.ZERO

    fun shape(): BodyShape = BodyShape(center, orientation, parts, sphere, radius)

    /**
     * The inverse inertia applied to a vector in the instance's space:
     * `R · I⁻¹_body · Rᵀ · v`. The tensor never has to be rebuilt as the body
     * tumbles; only the rotation around it changes.
     */
    fun applyInverseInertia(v: Vec3): Vec3 {
        if (locked) return Vec3.ZERO
        return orientation.rotate(invInertia.apply(orientation.inverseRotate(v)))
    }

    /** The velocity of the point of the body at [offset] from its centre. */
    fun velocityAt(offset: Vec3): Vec3 = velocity + cross(angularVelocity, offset)

    /** The same for the separation-only velocity. See [biasVelocity]. */
    fun biasVelocityAt(offset: Vec3): Vec3 = biasVelocity + cross(biasAngularVelocity, offset)

    /** Applies an impulse acting at [offset] from the centre of mass. */
    fun applyImpulse(impulse: Vec3, offset: Vec3) {
        velocity += impulse * invMass
        if (locked) return
        angularVelocity += applyInverseInertia(cross(offset, impulse))
    }

    /** Applies an impulse that only ever separates. See [biasVelocity]. */
    fun applyBiasImpulse(impulse: Vec3, offset: Vec3) {
        biasVelocity += impulse * invMass
        if (locked) return
        biasAngularVelocity += applyInverseInertia(cross(offset, impulse))
    }

    /** Throws away whatever separation is left over at the end of a slice. */
    fun clearBias() {
        biasVelocity = Vec3.ZERO
        biasAngularVelocity = Vec3.ZERO
    }

    /**
     * How much velocity change one unit of impulse along [direction] at
     * [offset] produces: the "effective mass" the solver divides by.
     */
    fun effectiveMass(direction: Vec3, offset: Vec3): Double {
        val total = inverseMassAlong(direction, offset)
        return if (total > 1e-9) 1.0 / total else 0.0
    }

    /**
     * The un-inverted form of [effectiveMass], so a two-body contact can add
     * both sides before inverting. One body's effective mass is not the sum of
     * two bodies' effective masses.
     */
    fun inverseMassAlong(direction: Vec3, offset: Vec3): Double {
        val back = cross(applyInverseInertia(cross(offset, direction)), offset)
        return invMass + dot(back, direction)
    }

    /** The half-extents of the box around the whole collider, for reporting. */
    fun extents(): Vec3 {
        if (sphere) return Vec3(radius, radius, radius)
        var x = 0.0
        var y = 0.0
        var z = 0.0
        for (part in parts) {
            x = maxOf(x, abs(part.offset.x) + part.half.x)
            y = maxOf(y, abs(part.offset.y) + part.half.y)
            z = maxOf(z, abs(part.offset.z) + part.half.z)
        }
        return Vec3(x, y, z)
    }

    /** The radius of the sphere the whole collider fits inside. */
    fun boundingRadius(): Double = boundingRadiusOf(parts, sphere, radius)

    /** The thinnest the collider gets, which is what bounds a step's slices. */
    fun thinnest(): Double {
        if (sphere) return radius * 2.0
        var least = Double.MAX_VALUE
        for (part in parts) {
            least = minOf(least, minOf(part.half.x, part.half.y, part.half.z) * 2.0)
        }
        return if (least == Double.MAX_VALUE) 0.0 else least
    }

    companion object {
        /** A body that is a single box centred on its centre of mass: the common case. */
        fun single(
            center: Vec3,
            orientation: Quat,
            velocity: Vec3,
            angularVelocity: Vec3,
            half: Vec3,
            sphere: Boolean = false,
            locked: Boolean = false,
            mass: Double = 1.0
        ) = RigidBody(
            center,
            orientation,
            velocity,
            angularVelocity,
            listOf(ColliderPart(Vec3.ZERO, half)),
            sphere,
            minOf(half.x, half.y, half.z),
            locked,
            mass
        )
    }
}

/**
 * The state a physics body carries between ticks: everything about it that
 * isn't derivable from its pose.
 */
class BodyState {
    /** Along the world's axes, like everything here: physics simulates in the frame of `Instance.placed`. */
    var velocity: Vec3 = Vec3.ZERO
    var angularVelocity: Vec3 = Vec3.ZERO

    /**
     * Orientation along the world's axes (the frame of `Instance.placed`), or
     * null until seeded from the node's authored pose. Null rather than
     * identity because a body authored on its side has to start on its side,
     * and that needs a composed matrix to read.
     */
    var orientation: Quat? = null

    /**
     * The rotation the solver last wrote onto the node. Anything else writing
     * that channel (a script, a clip) leaves the two disagreeing, which is how
     * the host notices it was overruled and reseeds instead of snapping back.
     */
    var writtenRotation: Vec3? = null

    var onGround: Boolean = false

    /** Consecutive steps the body has been too slow to be worth simulating. */
    var stillSteps: Int = 0

    var asleep: Boolean = false

    /** Steps since a sleeping body last checked it still had something under it. */
    var sinceSupportCheck: Int = 0

    /** What it was touching after the last step it was simulated, for telling a new impact from resting contact. */
    var touching: Set<Pair<Int, Int>> = emptySet()

    /** Whether scripts were last told it's asleep (`sleep`) rather than awake (`wake`). */
    var reportedAsleep: Boolean = false

    /** Whether its `physics.gravity` pulls it (`set_gravity`). */
    var gravity: Boolean = true

    /** Moved by its own velocity alone, never by gravity or contacts; other bodies of its centity hit it as a wall (`set_kinematic`). */
    var kinematic: Boolean = false

    /** Simulated at all: a frozen body doesn't move, and other bodies of its centity hit it as a wall (`set_physics_enabled`). */
    var enabled: Boolean = true

    /** Neither simulated nor moved by physics, but solid to the others: frozen, or kinematic. */
    val scenery: Boolean get() = !enabled || kinematic

    /** Puts a body back to work, whatever it was doing. */
    fun wake() {
        asleep = false
        stillSteps = 0
        sinceSupportCheck = 0
    }

    /** Counts still steps and puts the body to sleep once it has been still long enough. */
    fun considerSleeping(physics: ResolvedPhysics) {
        if (!physics.sleeps) {
            stillSteps = 0
            return
        }
        if (dot(velocity, velocity) > SLEEP_SPEED * SLEEP_SPEED ||
            dot(angularVelocity, angularVelocity) > SLEEP_SPIN * SLEEP_SPIN
        ) {
            stillSteps = 0
            return
        }
        stillSteps++
        if (stillSteps < SLEEP_STEPS) return
        asleep = true
        velocity = Vec3.ZERO
        angularVelocity = Vec3.ZERO
    }

    companion object {
        /** Blocks per second below which a body counts as still. */
        const val SLEEP_SPEED = 0.06

        /** Radians per second below which a body counts as not turning. */
        const val SLEEP_SPIN = 0.12

        /** Ticks of stillness before a body stops being simulated. */
        const val SLEEP_STEPS = 30

        /**
         * How often a sleeping body checks it is still supported. Nothing tells
         * it a platform under it was removed; rechecking is far cheaper than
         * staying awake, and a second's hang before it falls goes unnoticed.
         */
        const val SUPPORT_CHECK_STEPS = 20
    }
}

internal fun cross(a: Vec3, b: Vec3): Vec3 = Vec3(
    a.y * b.z - a.z * b.y,
    a.z * b.x - a.x * b.z,
    a.x * b.y - a.y * b.x
)

internal fun dot(a: Vec3, b: Vec3): Double = a.x * b.x + a.y * b.y + a.z * b.z

internal fun boundingRadiusOf(parts: List<ColliderPart>, sphere: Boolean, radius: Double): Double {
    if (sphere) return radius
    var furthest = 0.0
    for (part in parts) {
        val dx = abs(part.offset.x) + part.half.x
        val dy = abs(part.offset.y) + part.half.y
        val dz = abs(part.offset.z) + part.half.z
        furthest = maxOf(furthest, sqrt(dx * dx + dy * dy + dz * dz))
    }
    return furthest
}
