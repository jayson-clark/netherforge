package dev.netherforge.format.centity

import dev.netherforge.format.math.Matrix4
import dev.netherforge.format.math.Vec3

/**
 * Folds local transforms down the tree. Shared so a viewport can never
 * disagree with the server about where a node is.
 */
object Composer {

    /** World matrices for each node in [centity] order, given each node's local transform. */
    fun world(centity: CompiledCentity, locals: List<Transform> = centity.nodes.map { it.transform }): List<Matrix4> {
        require(locals.size == centity.nodes.size) { "expected ${centity.nodes.size} transforms, got ${locals.size}" }
        val world = ArrayList<Matrix4>(locals.size)
        centity.nodes.forEachIndexed { index, node ->
            val local = local(locals[index])
            world += if (node.isRoot) local else world[node.parentIndex].copy().mul(local)
        }
        return world
    }

    /** Fills [world] from [local] in place, for a live tree in arbitrary slot order. */
    fun compose(order: IntArray, parents: IntArray, local: Array<Matrix4>, world: Array<Matrix4>) {
        for (index in order) {
            val parent = parents[index]
            if (parent < 0) world[index].set(local[index]) else world[parent].mul(local[index], world[index])
        }
    }

    fun local(transform: Transform, dest: Matrix4 = Matrix4()): Matrix4 =
        Matrix4.fromTrs(transform.translationOrDefault, transform.rotationOrDefault, transform.scaleOrDefault, dest)

    /**
     * The local transforms after sampling [animation] at [time] over the
     * authored pose. A track owns its whole channel, or for an additive clip,
     * adds to it (scale multiplies).
     */
    fun pose(centity: CompiledCentity, animation: CompiledAnimation, time: Double): List<Transform> {
        val at = animation.resolve(time).time
        val locals = centity.nodes.map { it.transform }.toMutableList()
        for (track in animation.tracks) {
            locals[track.nodeIndex] = locals[track.nodeIndex].posed(track.channel, track.sample(at), animation.blend)
        }
        return locals
    }
}

/**
 * This transform with [channel] set to [value] ([AnimationBlend.REPLACE]) or changed by it
 * ([AnimationBlend.ADDITIVE]: translation and rotation added, scale multiplied).
 */
fun Transform.posed(channel: Channel, value: Vec3, blend: AnimationBlend = AnimationBlend.REPLACE): Transform {
    val additive = blend == AnimationBlend.ADDITIVE
    return when (channel) {
        Channel.TRANSLATION -> copy(translation = if (additive) translationOrDefault + value else value)
        Channel.ROTATION -> copy(rotation = if (additive) rotationOrDefault + value else value)
        Channel.SCALE -> copy(
            scale = if (additive) Vec3(scaleOrDefault.x * value.x, scaleOrDefault.y * value.y, scaleOrDefault.z * value.z) else value
        )
    }
}
