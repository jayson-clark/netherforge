package dev.netherforge.plugin.particle

import dev.netherforge.format.math.Vec3
import dev.netherforge.format.particle.EffectSampler
import dev.netherforge.plugin.binding.LuaHandle
import java.util.UUID

/**
 * What an effect can follow: anything with a position and a yaw that can
 * stop existing.
 */
sealed interface FollowTarget {
    data class Centity(val id: String) : FollowTarget

    data class Node(val centity: String, val name: String) : FollowTarget

    data class Player(val uuid: UUID) : FollowTarget

    data class Entity(val uuid: UUID) : FollowTarget
}

/**
 * One playing effect: its timeline ([sampler]), where it is, what it
 * follows, and who may see it.
 */
class ActiveEffect internal constructor(
    /** The handle's key: unique for the runtime's life, never reused. */
    val number: Long,
    /** The effect's id (its folder under `particles/`). */
    val kind: String,
    /** The scope that played it, or null for one the runtime played (the editor's "Play on server"). */
    val owner: Int?,
    internal val sampler: EffectSampler,
    var world: String,
    /** The effect's origin in world space. */
    var origin: Vec3,
    var yaw: Double,
    var pitch: Double,
    val scale: Double,
    /** Only these players (by UUID) may see it; null for everyone in range. */
    val viewers: Set<UUID>?,
    /** The play's `loop` option; null to follow the file. */
    private val loopOverride: Boolean?
) {
    var follow: FollowTarget? = null
        internal set

    /** Where the effect sits from what it follows, in effect space (turned with the target's yaw, not scaled). */
    var offset: Vec3 = Vec3.ZERO
        internal set

    /** Set once it has ended, for any reason. */
    var ended = false
        internal set

    /** Whether it starts again after its last tick: the play's option, else its file's. */
    val loop: Boolean get() = loopOverride ?: sampler.effect.loop

    val handle: LuaHandle.Effect get() = LuaHandle.Effect(number, kind)
}
