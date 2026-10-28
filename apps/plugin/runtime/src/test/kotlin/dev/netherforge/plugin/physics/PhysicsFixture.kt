package dev.netherforge.plugin.physics

import dev.netherforge.format.centity.PhysicsDef
import dev.netherforge.format.centity.PhysicsShape
import dev.netherforge.format.centity.ResolvedPhysics
import dev.netherforge.format.game.Box

/** Physics as a file would say it, resolved the way the compiler does. */
fun physics(
    gravity: Double? = null,
    mass: Double? = null,
    bounciness: Double? = null,
    friction: Double? = null,
    drag: Double? = null,
    angularDrag: Double? = null,
    maxSpeed: Double? = null,
    maxSpin: Double? = null,
    collider: Box? = null,
    shape: PhysicsShape? = null,
    rotates: Boolean? = null,
    sleeps: Boolean? = null,
    blocks: Boolean? = null,
    entities: Boolean? = null
): ResolvedPhysics = ResolvedPhysics.of(
    PhysicsDef(
        gravity, mass, bounciness, friction, drag, angularDrag, maxSpeed, maxSpin,
        collider, shape, rotates, sleeps, blocks, entities
    )
)
