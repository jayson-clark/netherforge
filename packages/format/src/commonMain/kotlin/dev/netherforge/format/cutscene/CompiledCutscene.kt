package dev.netherforge.format.cutscene

import dev.netherforge.format.centity.Easing
import dev.netherforge.format.centity.Keyframe
import dev.netherforge.format.math.Angles
import dev.netherforge.format.math.Vec3

/**
 * A cutscene ready to play or draw: keys sorted, the length decided, cues in
 * time order. Built only from a file that validated without errors, by
 * [CutsceneCompiler].
 */
class CompiledCutscene(
    val id: String,
    /** Seconds, always more than 0. */
    val length: Double,
    /** Whether the player may end it early by sneaking, when a script says nothing. */
    val skippable: Boolean,
    val path: CameraPath,
    /** In time order; cues at the same time keep the file's order. */
    val cues: List<CompiledCue>
) {
    /** Where the camera is at [time] seconds, held at the first and last keys outside the keyed range. */
    fun poseAt(time: Double): CameraPose = path.at(time)
}

class CompiledCue(
    val time: Double,
    val event: String?,
    val text: String?,
    /** Seconds [text] stays. */
    val duration: Double
)

/** Where the camera is and which way it looks: yaw and pitch in degrees, yaw not wrapped (a turn through 180 stays continuous). */
data class CameraPose(val position: Vec3, val yaw: Double, val pitch: Double)

/**
 * The camera's two tracks, sampled the way a centity's animation channel is:
 * the segment leaving a key is shaped by its [Easing], and a yaw turns the
 * short way round ([Angles.shortestDelta]). Shared by the server and the
 * editor so a preview can't disagree with the cutscene.
 *
 * Both lists are sorted by time and never empty.
 */
class CameraPath(private val positions: List<Keyframe>, private val rotations: List<RotationKey>) {
    init {
        require(positions.isNotEmpty() && rotations.isNotEmpty()) { "a camera path needs at least one key on each track" }
    }

    private val positionTimes = positions.map { it.time }
    private val positionEasings = positions.map { it.easing }
    private val rotationTimes = rotations.map { it.time }
    private val rotationEasings = rotations.map { it.easing }

    fun at(time: Double): CameraPose {
        val position = positionAt(time)
        val (yaw, pitch) = rotationAt(time)
        return CameraPose(position, yaw, pitch)
    }

    private fun positionAt(time: Double): Vec3 {
        val (from, to, t) = segment(positionTimes, positionEasings, time)
        val a = positions[from].value
        val b = positions[to].value
        return if (from == to) a else Vec3(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t)
    }

    private fun rotationAt(time: Double): Pair<Double, Double> {
        val (from, to, t) = segment(rotationTimes, rotationEasings, time)
        val a = rotations[from]
        val b = rotations[to]
        if (from == to) return a.yaw to a.pitch
        return (a.yaw + Angles.shortestDelta(a.yaw, b.yaw) * t) to (a.pitch + (b.pitch - a.pitch) * t)
    }

    private data class Segment(val from: Int, val to: Int, val t: Double)

    /** Which two keys [time] lies between and how far along, eased; the same key twice outside the keyed range. */
    private fun segment(times: List<Double>, easings: List<Easing?>, time: Double): Segment {
        if (time <= times.first()) return Segment(0, 0, 0.0)
        if (time >= times.last()) return Segment(times.size - 1, times.size - 1, 0.0)
        var low = 0
        var high = times.size - 1
        while (low < high) {
            val mid = (low + high) / 2
            if (times[mid] <= time) low = mid + 1 else high = mid
        }
        val span = times[low] - times[low - 1]
        if (span <= 0.0) return Segment(low, low, 0.0)
        val t = (easings[low - 1] ?: Easing.LINEAR).apply((time - times[low - 1]) / span)
        return Segment(low - 1, low, t)
    }
}
