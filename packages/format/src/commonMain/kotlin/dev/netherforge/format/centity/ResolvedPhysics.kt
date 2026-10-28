package dev.netherforge.format.centity

import dev.netherforge.format.game.Box
import dev.netherforge.format.math.degreesToRadians

/**
 * A [PhysicsDef] as it runs: every absent field filled in and every
 * out-of-range one clamped. The file keeps fields nullable so an author's
 * "not said" survives a round trip; nothing that runs a body should see that
 * distinction, so [CentityCompiler] resolves it once, here.
 *
 * What [CentityValidator]'s physics warnings say ("clamped to between 0 and
 * 1", "treated as zero") is what [of] does.
 */
data class ResolvedPhysics(
    /** Blocks per second squared. */
    val gravity: Double,
    /** The authored mass, or null to take it from the collider's volume. */
    val mass: Double?,
    /** 0 to 1. */
    val bounciness: Double,
    /** At least 0. */
    val friction: Double,
    /** 0 to 1: the fraction of speed shed per second. */
    val drag: Double,
    /** 0 to 1. */
    val angularDrag: Double,
    /** Blocks per second, at least 0. */
    val maxSpeed: Double,
    /** Degrees per second, at least 0. */
    val maxSpin: Double,
    val collider: Box?,
    /** Null follows the hitbox. */
    val shape: PhysicsShape?,
    val rotates: Boolean,
    val sleeps: Boolean,
    /** Collides with the world's blocks. */
    val blocks: Boolean,
    /** Collides with other centities' hitboxes. */
    val entities: Boolean
) {
    /** [maxSpin] in radians per second. */
    val maxSpinRadians: Double get() = degreesToRadians(maxSpin)

    companion object {
        const val DEFAULT_GRAVITY = 32.0
        const val DEFAULT_BOUNCINESS = 0.0
        const val DEFAULT_FRICTION = 0.6
        const val DEFAULT_DRAG = 0.02
        const val DEFAULT_ANGULAR_DRAG = 0.05
        const val DEFAULT_MAX_SPEED = 60.0
        const val DEFAULT_MAX_SPIN = 3600.0

        fun of(def: PhysicsDef) = ResolvedPhysics(
            gravity = def.gravity ?: DEFAULT_GRAVITY,
            mass = def.mass?.takeIf { it > 0.0 },
            bounciness = (def.bounciness ?: DEFAULT_BOUNCINESS).coerceIn(0.0, 1.0),
            friction = (def.friction ?: DEFAULT_FRICTION).coerceAtLeast(0.0),
            drag = (def.drag ?: DEFAULT_DRAG).coerceIn(0.0, 1.0),
            angularDrag = (def.angularDrag ?: DEFAULT_ANGULAR_DRAG).coerceIn(0.0, 1.0),
            maxSpeed = (def.maxSpeed ?: DEFAULT_MAX_SPEED).coerceAtLeast(0.0),
            maxSpin = (def.maxSpin ?: DEFAULT_MAX_SPIN).coerceAtLeast(0.0),
            collider = def.collider,
            shape = def.shape,
            rotates = def.rotates ?: true,
            sleeps = def.sleeps ?: true,
            blocks = def.blocks ?: true,
            entities = def.entities ?: true
        )
    }
}
