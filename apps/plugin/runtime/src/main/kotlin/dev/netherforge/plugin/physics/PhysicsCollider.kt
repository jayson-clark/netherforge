package dev.netherforge.plugin.physics

import dev.netherforge.format.centity.PhysicsShape
import dev.netherforge.format.centity.ResolvedPhysics
import dev.netherforge.format.game.Box
import dev.netherforge.format.math.Matrix4
import dev.netherforge.format.math.Vec3
import kotlin.math.PI

/**
 * The shape a physics body collides as.
 *
 * One place decides it because two others must agree: the solver moves this
 * shape, and the host gathers obstacles from the region it sweeps. A collider
 * that came out differently in either would walk through walls or stop short.
 *
 * A body is a *set* of boxes because a hitbox often is: a stair is two slabs, a
 * fence a post and its arms. Following the hitbox's real shape also keeps the
 * two from drifting: a node lands where it is clickable.
 */
object PhysicsCollider {
    /** The cube a block display fills, which is what a body with nothing else collides as. */
    private val UNIT = Box(Vec3.ZERO, Vec3.ONE)

    /** The least a body may weigh. A collider flattened to nothing would otherwise be infinitely easy to shove. */
    private const val MIN_MASS = 1e-3

    /**
     * The boxes the body collides as, in the node's own space.
     *
     * An explicit [ResolvedPhysics.collider] means it and is exactly that one box.
     * Otherwise the node's [hitboxBoxes] (already resolved), or the unit cube a
     * block display fills, so `"physics": {}` next to a block display just
     * works. `shape: "box"` asks for the cheap approximation and collapses a
     * multi-box hitbox to its extent.
     */
    fun boxesOf(physics: ResolvedPhysics, hitboxBoxes: List<Box>?): List<Box> {
        physics.collider?.let { return listOf(it) }
        val boxes = hitboxBoxes?.takeIf { it.isNotEmpty() } ?: listOf(UNIT)
        if (physics.shape == PhysicsShape.BOX && boxes.size > 1) return listOf(extentOf(boxes))
        return boxes
    }

    /**
     * The centre of mass in the node's own space: the volume-weighted middle of
     * the collider. Everything rotates about it, and weighting by volume is
     * what makes a stair pivot forward of the middle of its cube (its lower
     * slab is the bigger half) and tip off a ledge sooner than a plain block.
     */
    fun centerOf(boxes: List<Box>): Vec3 {
        var total = 0.0
        var sum = Vec3.ZERO
        for (box in boxes) {
            val volume = volumeOf(box)
            total += volume
            sum += box.center * volume
        }
        // Every box flat leaves nothing to weight by: use the middle of the extent.
        if (total <= 0.0) return extentOf(boxes).center
        return sum * (1.0 / total)
    }

    /**
     * A body positioned and oriented in the instance's space.
     *
     * [orientation] is the body's own, which the solver owns and integrates;
     * [worldMatrix] supplies only where the node is and how it is scaled.
     * Reading rotation back out of the matrix would throw away what the solver
     * knew once a turn took it past where euler angles can say.
     */
    fun bodyFor(
        physics: ResolvedPhysics,
        hitboxBoxes: List<Box>?,
        worldMatrix: Matrix4,
        orientation: Quat,
        velocity: Vec3,
        angularVelocity: Vec3
    ): RigidBody {
        val boxes = boxesOf(physics, hitboxBoxes)
        val scale = worldMatrix.scale()
        val local = centerOf(boxes)

        val parts = boxes.map { box ->
            val offset = box.center - local
            val half = box.size * 0.5
            ColliderPart(
                offset = Vec3(offset.x * scale.x, offset.y * scale.y, offset.z * scale.z),
                half = Vec3(half.x * scale.x, half.y * scale.y, half.z * scale.z)
            )
        }
        val radius = sphereRadius(parts)

        return RigidBody(
            center = worldMatrix.transformPosition(local),
            orientation = orientation,
            velocity = velocity,
            angularVelocity = angularVelocity,
            parts = parts,
            sphere = physics.shape == PhysicsShape.SPHERE,
            // A sphere fits inside whatever the collider would have been, so it
            // is never bigger than the shape the author was looking at.
            radius = radius,
            locked = !physics.rotates,
            mass = physics.mass ?: volumeOf(parts, physics.shape == PhysicsShape.SPHERE, radius)
        )
    }

    /**
     * The node origin that puts a body's centre of mass where the solver left
     * it. The origin sits at a fixed offset from the centre in the body's own
     * frame; turning that offset by the new orientation makes a toppling crate
     * pivot about its middle rather than swing about its corner.
     */
    fun originFor(physics: ResolvedPhysics, hitboxBoxes: List<Box>?, body: RigidBody, scale: Vec3): Vec3 {
        val local = centerOf(boxesOf(physics, hitboxBoxes))
        return body.center - body.orientation.rotate(Vec3(local.x * scale.x, local.y * scale.y, local.z * scale.z))
    }

    /** The one box around a whole set of them. */
    fun extentOf(boxes: List<Box>): Box {
        if (boxes.isEmpty()) return UNIT
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var minZ = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        var maxZ = -Double.MAX_VALUE
        for (box in boxes) {
            minX = minOf(minX, box.min.x)
            minY = minOf(minY, box.min.y)
            minZ = minOf(minZ, box.min.z)
            maxX = maxOf(maxX, box.max.x)
            maxY = maxOf(maxY, box.max.y)
            maxZ = maxOf(maxZ, box.max.z)
        }
        return Box(Vec3(minX, minY, minZ), Vec3(maxX, maxY, maxZ))
    }

    /**
     * What a body weighs when nothing said: its collider's volume in blocks.
     * One material, so size is weight; a balloon and a cannonball are the same
     * size and the author says so.
     */
    private fun volumeOf(parts: List<ColliderPart>, sphere: Boolean, radius: Double): Double {
        if (sphere) return (4.0 / 3.0 * PI * radius * radius * radius).coerceAtLeast(MIN_MASS)
        var total = 0.0
        for (part in parts) {
            total += 8.0 * part.half.x.coerceAtLeast(0.0) * part.half.y.coerceAtLeast(0.0) * part.half.z.coerceAtLeast(0.0)
        }
        return total.coerceAtLeast(MIN_MASS)
    }

    /** The smallest half-extent of any part: the sphere that fits inside the collider. */
    private fun sphereRadius(parts: List<ColliderPart>): Double {
        var least = Double.MAX_VALUE
        for (part in parts) least = minOf(least, part.half.x, part.half.y, part.half.z)
        return if (least == Double.MAX_VALUE) 0.5 else least
    }

    private fun volumeOf(box: Box): Double =
        (box.max.x - box.min.x).coerceAtLeast(0.0) * (box.max.y - box.min.y).coerceAtLeast(0.0) * (box.max.z - box.min.z).coerceAtLeast(0.0)
}
