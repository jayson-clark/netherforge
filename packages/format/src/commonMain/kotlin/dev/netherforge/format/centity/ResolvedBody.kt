package dev.netherforge.format.centity

/**
 * A [BodyDef] as it runs: every absent field filled in and every out-of-range one clamped, as
 * [CentityValidator]'s body warnings say. Resolved once, by [CentityCompiler]; a script's
 * `set_body` changes a copy.
 */
data class ResolvedBody(
    /** Blocks, more than 0. */
    val width: Double,
    /** Blocks, more than 0. */
    val height: Double,
    /** Blocks per second squared. */
    val gravity: Double,
    /** Blocks per second, at least 0. */
    val maxFallSpeed: Double,
    /** 0 to 1: the fraction of speed shed per second. */
    val drag: Double,
    /** 0 to 1. */
    val liquidDrag: Double,
    /** At least 0. */
    val liquidGravity: Double,
    /** Blocks per second squared, at least 0. */
    val acceleration: Double,
    /** Blocks per second squared, at least 0. */
    val airAcceleration: Double,
    /** Blocks per second squared, at least 0. */
    val friction: Double,
    /** Blocks, at least 0. */
    val stepHeight: Double,
    /** 0 to 1. */
    val bounce: Double,
    val fly: Boolean,
    val blocks: Boolean
) {
    companion object {
        /** A player's size. */
        const val DEFAULT_WIDTH = 0.6
        const val DEFAULT_HEIGHT = 1.8

        /** The same as a node's `physics`, and what a centity walking (`move_to`) falls by. */
        const val DEFAULT_GRAVITY = 32.0

        /** Minecraft's own: a mob's speed after a long fall. */
        const val DEFAULT_MAX_FALL_SPEED = 78.4

        /** About Minecraft's: its entities keep 98% of their falling speed a tick, a third shed in a second. */
        const val DEFAULT_DRAG = 0.33

        /** About Minecraft's in water: 80% kept a tick. */
        const val DEFAULT_LIQUID_DRAG = 0.98
        const val DEFAULT_LIQUID_GRAVITY = 0.25
        const val DEFAULT_ACCELERATION = 20.0
        const val DEFAULT_AIR_ACCELERATION = 5.0
        const val DEFAULT_FRICTION = 24.0

        /** Minecraft's own for a mob: half a slab and a bit. */
        const val DEFAULT_STEP_HEIGHT = 0.6
        const val DEFAULT_BOUNCE = 0.0

        /** The biggest a body may be, blocks: wider or taller and every tick reads too many blocks. */
        const val MAX_WIDTH = 16.0
        const val MAX_HEIGHT = 32.0

        fun of(def: BodyDef) = ResolvedBody(
            width = (def.width ?: DEFAULT_WIDTH).coerceIn(MIN_SIZE, MAX_WIDTH),
            height = (def.height ?: DEFAULT_HEIGHT).coerceIn(MIN_SIZE, MAX_HEIGHT),
            gravity = def.gravity ?: DEFAULT_GRAVITY,
            maxFallSpeed = (def.maxFallSpeed ?: DEFAULT_MAX_FALL_SPEED).coerceAtLeast(0.0),
            drag = (def.drag ?: DEFAULT_DRAG).coerceIn(0.0, 1.0),
            liquidDrag = (def.liquidDrag ?: DEFAULT_LIQUID_DRAG).coerceIn(0.0, 1.0),
            liquidGravity = (def.liquidGravity ?: DEFAULT_LIQUID_GRAVITY).coerceAtLeast(0.0),
            acceleration = (def.acceleration ?: DEFAULT_ACCELERATION).coerceAtLeast(0.0),
            airAcceleration = (def.airAcceleration ?: DEFAULT_AIR_ACCELERATION).coerceAtLeast(0.0),
            friction = (def.friction ?: DEFAULT_FRICTION).coerceAtLeast(0.0),
            stepHeight = (def.stepHeight ?: DEFAULT_STEP_HEIGHT).coerceAtLeast(0.0),
            bounce = (def.bounce ?: DEFAULT_BOUNCE).coerceIn(0.0, 1.0),
            fly = def.fly ?: false,
            blocks = def.blocks ?: true
        )

        /** Below this a body is a point the collision can't hold. */
        private const val MIN_SIZE = 0.01
    }
}
