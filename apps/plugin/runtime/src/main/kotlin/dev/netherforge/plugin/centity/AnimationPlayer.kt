package dev.netherforge.plugin.centity

import dev.netherforge.format.centity.AnimationBlend
import dev.netherforge.format.centity.Channel
import dev.netherforge.format.centity.CompiledAnimation
import dev.netherforge.format.centity.LoopMode
import dev.netherforge.format.centity.Transform
import dev.netherforge.format.centity.posed
import dev.netherforge.format.math.Vec3

/**
 * The clips playing on one instance, and what they do to its pose.
 *
 * A clip owns the channels its tracks drive while it plays; everything else
 * stays at the rest pose (the authored transform, or what a script last set).
 * Clips are applied in the order they were started, so the newest wins where
 * two drive the same channel. Additive clips come after all of those, each
 * changing the pose they leave (translation and rotation added, scale
 * multiplied), so a squash plays on top of a walk. When a `once` clip ends its channels fall back
 * to rest; a `hold` clip keeps its last pose until stopped; a `loop` clip
 * never ends.
 *
 * Each play can run at its own speed, start part way in, override the
 * clip's loop mode, and blend in: for its first ticks its channels ease from
 * the pose shown when it started into the clip, instead of jumping.
 */
class AnimationPlayer {
    private class Playing(val clip: CompiledAnimation, val loop: LoopMode, var speed: Double, val blend: Blend?) {
        /** Seconds into the clip, as authored (speed already applied). */
        var time = 0.0
        var ended = false
        var paused = false
    }

    /** Easing in from [from] (the shown pose when the clip started) over [seconds]. */
    private class Blend(val from: Array<Transform>, val seconds: Double) {
        var elapsed = 0.0
        val weight: Double get() = if (seconds <= 0.0) 1.0 else (elapsed / seconds).coerceIn(0.0, 1.0)
    }

    private val playing = LinkedHashMap<String, Playing>()

    val active: Boolean get() = playing.isNotEmpty()

    /**
     * Counts every change a script makes to the clips (started, stopped,
     * paused, moved, sped up): what was worked out ahead from them
     * ([applyAhead]) no longer holds once it moves. Advancing doesn't count.
     */
    var version = 0L
        private set

    /**
     * Starts [clip], restarting it if it was already playing: at [speed]
     * times its authored rate, [from] seconds in, under [loop] (its own mode
     * when null), easing from [pose] (the pose shown now) over
     * [blendSeconds].
     */
    fun play(
        clip: CompiledAnimation,
        speed: Double = 1.0,
        from: Double = 0.0,
        loop: LoopMode? = null,
        blendSeconds: Double = 0.0,
        pose: Array<Transform>? = null
    ) {
        version++
        playing.remove(clip.name)
        val blend = if (blendSeconds > 0.0 && pose != null) Blend(pose.copyOf(), blendSeconds) else null
        playing[clip.name] = Playing(clip, loop ?: clip.loop, speed, blend).also { it.time = from }
    }

    fun stop(name: String): Boolean = (playing.remove(name) != null).also { if (it) version++ }

    fun stopAll() {
        if (playing.isNotEmpty()) version++
        playing.clear()
    }

    fun isPlaying(name: String): Boolean = name in playing

    fun names(): List<String> = playing.keys.toList()

    /** Holds a clip where it is. False when it isn't playing. */
    fun pause(name: String): Boolean {
        val entry = playing[name] ?: return false
        entry.paused = true
        version++
        return true
    }

    fun resume(name: String): Boolean {
        val entry = playing[name] ?: return false
        entry.paused = false
        version++
        return true
    }

    /** Where a playing clip is, in seconds within its length; null when it isn't playing. */
    fun position(name: String): Double? = playing[name]?.let { it.clip.resolve(it.time, it.loop).time }

    /** Moves a playing clip to [seconds] in. A hold clip that had ended plays on from there. False when it isn't playing. */
    fun seek(name: String, seconds: Double): Boolean {
        val entry = playing[name] ?: return false
        entry.time = seconds
        entry.ended = false
        version++
        return true
    }

    fun speed(name: String): Double? = playing[name]?.speed

    fun setSpeed(name: String, speed: Double): Boolean {
        val entry = playing[name] ?: return false
        entry.speed = speed
        version++
        return true
    }

    /**
     * Moves every clip on by [seconds] and returns the ones that reached
     * their end on this step. `once` clips are dropped as they end. A
     * blend's easing counts in real time, whatever the clip's speed or
     * whether it's paused.
     */
    fun advance(seconds: Double): List<String> {
        val ended = mutableListOf<String>()
        val iterator = playing.values.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            entry.blend?.let { it.elapsed += seconds }
            if (entry.ended || entry.paused) continue
            entry.time += seconds * entry.speed
            if (entry.clip.resolve(entry.time, entry.loop).ended) {
                ended += entry.clip.name
                if (entry.loop == LoopMode.ONCE) iterator.remove() else entry.ended = true
            }
        }
        return ended
    }

    /** Writes every playing clip's channels over [pose], which starts as a copy of the rest pose. */
    fun apply(pose: Array<Transform>) = apply(pose, 0.0)

    /**
     * What [apply] will write after [advance] by [seconds], without moving
     * anything: each clip that far on (a `once` clip that will have ended
     * no longer writes), each blend that much further in.
     */
    fun applyAhead(pose: Array<Transform>, seconds: Double) = apply(pose, seconds)

    private fun apply(pose: Array<Transform>, ahead: Double) {
        for (blend in AnimationBlend.entries) {
            for (entry in playing.values) if (entry.clip.blend == blend) apply(pose, ahead, entry)
        }
    }

    private fun apply(pose: Array<Transform>, ahead: Double, entry: Playing) {
        val time = if (entry.ended || entry.paused) entry.time else entry.time + ahead * entry.speed
        val position = entry.clip.resolve(time, entry.loop)
        if (position.ended && entry.loop == LoopMode.ONCE && ahead > 0.0) return
        val at = position.time
        val weight = entry.blend?.let { blend ->
            if (blend.seconds <= 0.0) 1.0 else ((blend.elapsed + ahead) / blend.seconds).coerceIn(0.0, 1.0)
        } ?: 1.0
        val additive = entry.clip.blend == AnimationBlend.ADDITIVE
        for (track in entry.clip.tracks) {
            if (track.nodeIndex !in pose.indices) continue
            var value = track.sample(at)
            if (weight < 1.0) {
                // An additive clip eases in from no change at all; a replacing one from the pose shown when it started.
                val from = if (additive) {
                    track.channel.identity
                } else {
                    entry.blend!!.from.getOrNull(track.nodeIndex)?.channel(track.channel) ?: track.channel.identity
                }
                value = blend(track.channel, from, value, weight)
            }
            pose[track.nodeIndex] = pose[track.nodeIndex].posed(track.channel, value, entry.clip.blend)
        }
    }

    private fun Transform.channel(channel: Channel): Vec3 = when (channel) {
        Channel.TRANSLATION -> translationOrDefault
        Channel.ROTATION -> rotationOrDefault
        Channel.SCALE -> scaleOrDefault
    }

    /** From [from] toward [to] by [t]; rotations the short way round each axis, as clips interpolate them. */
    private fun blend(channel: Channel, from: Vec3, to: Vec3, t: Double): Vec3 {
        fun step(a: Double, b: Double): Double {
            if (channel != Channel.ROTATION) return a + (b - a) * t
            var delta = (b - a) % 360.0
            if (delta > 180.0) delta -= 360.0
            if (delta <= -180.0) delta += 360.0
            return a + delta * t
        }
        return Vec3(step(from.x, to.x), step(from.y, to.y), step(from.z, to.z))
    }
}
