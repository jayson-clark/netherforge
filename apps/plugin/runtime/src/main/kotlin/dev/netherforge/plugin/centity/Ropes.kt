package dev.netherforge.plugin.centity

import dev.netherforge.format.centity.Channel
import dev.netherforge.format.math.Matrix4
import dev.netherforge.format.math.Vec3
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.sqrt

/** One end of a strung rope: a point, a node of a centity (its origin, as shown), or an entity. */
sealed interface RopeEnd {
    data class Point(val at: Vec3) : RopeEnd

    data class OfNode(val centity: UUID, val node: String) : RopeEnd

    data class OfEntity(val entity: UUID) : RopeEnd
}

/** A rope node strung between two ends, kept laid each tick (`node:rope_between`); [length] null is always taut. */
class StrungRope(val from: RopeEnd, val to: RopeEnd, val length: Double?)

/**
 * Laying out ropes and stretched nodes: the shapes, apart from what the ends are, so they can be worked out and
 * tested on their own.
 */
object Ropes {
    /**
     * How far a rope [length] long hangs below the straight line at its middle, between ends [distance] apart: none
     * when it's taut ([length] null or no longer than the distance), and as a string pinched in the middle would
     * hang (half the slack's leg) when it isn't.
     */
    fun sag(length: Double?, distance: Double): Double =
        if (length == null || distance >= length) 0.0 else 0.5 * sqrt(length * length - distance * distance)

    /**
     * [count] + 1 points from [a] to [b] along a curve sagging [sag] blocks at its middle: a quadratic curve whose
     * control point is twice the sag below the halfway point, so its middle dips by just the sag.
     */
    fun points(a: Vec3, b: Vec3, sag: Double, count: Int): List<Vec3> {
        val control = Vec3((a.x + b.x) * 0.5, (a.y + b.y) * 0.5 - 2 * sag, (a.z + b.z) * 0.5)
        return (0..count).map { i ->
            val t = i.toDouble() / count
            val u = 1 - t
            Vec3(
                u * u * a.x + 2 * u * t * control.x + t * t * b.x,
                u * u * a.y + 2 * u * t * control.y + t * t * b.y,
                u * u * a.z + 2 * u * t * control.z + t * t * b.z
            )
        }
    }

    /** How many of a rope's [links] links, each [linkLength] long, its ends [distance] apart need: its length's worth, when it sags. */
    fun shown(links: Int, linkLength: Double, length: Double?, distance: Double): Int =
        ceil(maxOf(distance, length ?: distance) / linkLength - 1e-9).toInt().coerceIn(1, links)

    /**
     * Lays node [index] of [instance] from world point [from] to [to]: its origin halfway, its Z along the way, its
     * Z scale the distance; X and Y scale [thickness], or as they are. [parentInverse] is the inverse of its parent's
     * world matrix (null works it out). False when its parent's space can't be inverted.
     */
    fun stretch(instance: Instance, index: Int, from: Vec3, to: Vec3, thickness: Double?, parentInverse: Matrix4? = null): Boolean {
        val inverse = parentInverse ?: instance.parentWorldMatrix(index).invertAffine() ?: return false
        val a = inverse.transformPosition(from)
        val b = inverse.transformPosition(to)
        val along = b - a
        val distance = sqrt(along.x * along.x + along.y * along.y + along.z * along.z)
        instance.set(index, Channel.TRANSLATION, Vec3((a.x + b.x) * 0.5, (a.y + b.y) * 0.5, (a.z + b.z) * 0.5))
        if (distance > 1e-9) instance.set(index, Channel.ROTATION, lookRotation(along * (1 / distance)))
        val scale = instance.get(index, Channel.SCALE)
        instance.set(index, Channel.SCALE, Vec3(thickness ?: scale.x, thickness ?: scale.y, maxOf(distance, MIN_LENGTH)))
        return true
    }

    /** Lays rope node [rope] of [instance] between world points [from] and [to], [length] long (null: taut). */
    fun lay(instance: Instance, rope: Int, from: Vec3, to: Vec3, length: Double?) {
        val def = instance.definition.nodes[rope].rope ?: return
        val inverse = instance.worldMatrix(rope).invertAffine() ?: return
        val distance = sqrt((to.x - from.x).let { it * it } + (to.y - from.y).let { it * it } + (to.z - from.z).let { it * it })
        val count = shown(def.links.size, def.linkLength, length, distance)
        val points = points(from, to, sag(length, distance), count)
        for ((i, link) in def.links.withIndex()) {
            val shown = i < count
            if (shown) stretch(instance, link, points[i], points[i + 1], null, inverse)
            if (instance.visible(link) != shown) instance.setVisible(link, shown)
        }
    }

    /** The least a stretched node's Z scale gets, so a node laid between two points at one place doesn't vanish into a plane. */
    private const val MIN_LENGTH = 0.01
}
