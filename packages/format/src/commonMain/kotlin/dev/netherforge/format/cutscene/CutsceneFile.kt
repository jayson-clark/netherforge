package dev.netherforge.format.cutscene

import dev.netherforge.format.centity.Easing
import dev.netherforge.format.centity.Keyframe
import dev.netherforge.format.text.MiniMessage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `cutscenes/<id>.json`: a camera path a script plays for a player.
 *
 * The camera is keyed the way a centity's animation is (seconds, a
 * [Keyframe] per pose, [Easing] shaping the segment that leaves each key), on
 * two tracks: where it is ([Camera.position]) and which way it looks
 * ([Camera.rotation]). [cues] are things that happen at a time: a named event
 * scripts hear, or a line of text shown to the player.
 *
 * A cutscene is data only: no script. [CameraPath] is its sampling, shared by
 * the server and the editor's preview, so a preview can't disagree with what
 * the player sees.
 */
@Serializable
data class CutsceneFile(
    @SerialName("\$schema") val schema: String? = null,
    /** Seconds the cutscene lasts (`> 0`, at most [MAX_LENGTH]). Default: the last key's or cue's time. */
    val length: Double? = null,
    /** Whether the player may end it early by sneaking. Default false: they can't until it ends. A script's `skippable` option wins. */
    val skippable: Boolean? = null,
    val camera: Camera = Camera(),
    /** What happens when, in time order. */
    val cues: List<Cue> = emptyList()
) {
    companion object {
        const val SCHEMA = "../.netherforge/schema/cutscene.schema.json"

        /** The longest a cutscene may be, in seconds. */
        const val MAX_LENGTH = 3600.0

        /** Keys on one track, and cues in one cutscene, at most. */
        const val MAX_KEYS = 4096

        /** How far from the origin a position may be: the world's own limit. */
        const val MAX_COORDINATE = 3.0e7
    }
}

/** The camera's two tracks. Each needs at least one key. */
@Serializable
data class Camera(
    /** Where the camera is, in world coordinates (or relative to the `origin` a script plays it at). Keys are `{ time, value: [x, y, z], easing? }`. */
    val position: List<Keyframe> = emptyList(),
    /** Which way it looks, in degrees. */
    val rotation: List<RotationKey> = emptyList()
)

/** One look on the camera's rotation track. [easing] shapes the segment leaving this key, as for a [Keyframe]. */
@Serializable
data class RotationKey(
    val time: Double,
    /** Degrees; 0 faces south and 90 west, as the game's yaw does. Any number: the camera turns the short way round. */
    val yaw: Double,
    /** Degrees, -90 (straight up) to 90 (straight down). */
    val pitch: Double,
    val easing: Easing? = null
)

/**
 * Something that happens at [time]: a [event] scripts hear on the running
 * cutscene (`cue`), and/or a line of [text] shown to the player. At least one.
 */
@Serializable
data class Cue(
    val time: Double,
    /** A name for scripts: `event.name` of the cutscene's `cue` event. */
    val event: String? = null,
    /** MiniMessage shown as the subtitle while the cue lasts. */
    @MiniMessage val text: String? = null,
    /** Seconds the text stays (`> 0`). Default [DEFAULT_DURATION]. */
    val duration: Double? = null
) {
    companion object {
        const val DEFAULT_DURATION = 3.0
    }
}
