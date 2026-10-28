package dev.netherforge.format.centity

import dev.netherforge.format.math.Angles
import dev.netherforge.format.math.Vec3
import dev.netherforge.format.script.ScriptDef
import kotlin.math.floor

/**
 * A centity ready to run or draw: nodes flat and sorted so every parent
 * precedes its children, references resolved to indices, keyframes sorted.
 *
 * Built only from a file that validated without errors, by [CentityCompiler].
 */
class CompiledCentity(
    val id: String,
    val name: String?,
    val nodes: List<Node>,
    val animations: List<CompiledAnimation>,
    /** The centity's one script, run once per instance. */
    val script: ScriptDef? = null,
    /** The rules for natural spawns, when the file has any. */
    val spawning: SpawningDef? = null,
    /** Its `body`, resolved, when it has one. */
    val body: ResolvedBody? = null,
    /** Its `health`, resolved, when it has any. */
    val health: ResolvedHealth? = null,
    /** Its `properties`, as the file has them. */
    val properties: Map<String, kotlinx.serialization.json.JsonElement>? = null
) {
    private val indexByName = nodes.withIndex().associate { (i, node) -> node.name to i }
    private val animationByName = animations.associateBy { it.name }

    fun indexOf(nodeName: String): Int? = indexByName[nodeName]

    fun node(nodeName: String): Node? = indexOf(nodeName)?.let { nodes[it] }

    fun animation(name: String): CompiledAnimation? = animationByName[name]

    val autoplay: List<CompiledAnimation> get() = animations.filter { it.autoplay }

    class Node(
        val name: String,
        /** Index into [nodes], or -1 for a root. */
        val parentIndex: Int,
        val transform: Transform,
        val display: DisplayDef?,
        val hitbox: HitboxDef?,
        val physics: ResolvedPhysics?,
        /** For a rope node: its links' indices into [nodes], in order from its start, and how long each is. */
        val rope: Rope? = null
    ) {
        val isRoot: Boolean get() = parentIndex < 0
    }

    class Rope(val links: List<Int>, val linkLength: Double)
}

class CompiledAnimation(
    val name: String,
    /** Seconds, always > 0. */
    val length: Double,
    val loop: LoopMode,
    val autoplay: Boolean,
    val tracks: List<Track>,
    val blend: AnimationBlend = AnimationBlend.REPLACE
) {
    /** Keys sorted by time, never empty. */
    class Track(val nodeIndex: Int, val channel: Channel, val keys: List<Keyframe>) {

        /** The pose at [time], holding the first/last key outside the keyed range. */
        fun sample(time: Double): Vec3 {
            if (time <= keys.first().time) return keys.first().value
            if (time >= keys.last().time) return keys.last().value
            var low = 0
            var high = keys.size - 1
            while (low < high) {
                val mid = (low + high) / 2
                if (keys[mid].time <= time) low = mid + 1 else high = mid
            }
            val from = keys[low - 1]
            val to = keys[low]
            val span = to.time - from.time
            if (span <= 0.0) return to.value
            val t = (from.easing ?: Easing.LINEAR).apply((time - from.time) / span)
            return if (channel == Channel.ROTATION) {
                Vec3(
                    from.value.x + Angles.shortestDelta(from.value.x, to.value.x) * t,
                    from.value.y + Angles.shortestDelta(from.value.y, to.value.y) * t,
                    from.value.z + Angles.shortestDelta(from.value.z, to.value.z) * t
                )
            } else {
                Vec3(
                    from.value.x + (to.value.x - from.value.x) * t,
                    from.value.y + (to.value.y - from.value.y) * t,
                    from.value.z + (to.value.z - from.value.z) * t
                )
            }
        }
    }

    /** Where [time] lands in the clip under [loop] (its own mode unless a player overrides it), and whether that's past the end. */
    fun resolve(time: Double, loop: LoopMode = this.loop): Position = when (loop) {
        LoopMode.LOOP -> Position(time - length * floor(time / length), ended = false)
        LoopMode.ONCE, LoopMode.HOLD -> if (time >= length) Position(length, true) else Position(time, false)
    }

    data class Position(val time: Double, val ended: Boolean)
}
