package dev.netherforge.format.screen

import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.ProblemSink
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.project.Names
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `videos/<id>/video.json`: a video screens play. The server can't decode a video file, so the editor converts
 * [source] (any file ffmpeg reads) into [FRAMES], the frames already in map colours at the size and rate this file
 * asks for, and its sound into [AUDIO], which the resource pack carries as a sound. Both are files of the video's
 * folder that the editor writes and the server reads; format only checks they're there ([VideoValidator]), since
 * what's in them is binary (`docs/format/video.md` describes [FRAMES]).
 */
@Serializable
data class VideoFile(
    @SerialName("\$schema") val schema: String? = null,
    /** The video it's converted from, a file in its folder (`source.mp4`). */
    val source: String,
    /** Maps across the frames fill, 1 to [MapScreen.MAX_TILES]. */
    val width: Int,
    /** Maps down, 1 to [MapScreen.MAX_TILES]. */
    val height: Int,
    /** Frames a second, 1 to [MAX_FPS] (default [DEFAULT_FPS]): a server ticks 20 times a second, so 20 is the most. */
    val fps: Int? = null,
    /** How colours become map colours (default Floyd–Steinberg, best for film; `ordered` flickers less). */
    val dither: Dither? = null,
    /** How the picture fills the maps when its shape differs (default [VideoFit.CONTAIN]). */
    val fit: VideoFit? = null,
    /** Where in [source] to start, in seconds (default 0). */
    val start: Double? = null,
    /** Where to stop, in seconds; absent: at its end. */
    val end: Double? = null,
    /** Whether its sound is kept (default true), as the sound event `video.<id>` in the resource pack. */
    val audio: Boolean? = null
) {
    val fpsOrDefault: Int get() = fps ?: DEFAULT_FPS
    val ditherOrDefault: Dither get() = dither ?: Dither.FLOYD_STEINBERG
    val fitOrDefault: VideoFit get() = fit ?: VideoFit.CONTAIN
    val audioOrDefault: Boolean get() = audio ?: true

    companion object {
        const val FILE_NAME = "video.json"
        const val SCHEMA = "../../.netherforge/schema/video.schema.json"

        /** The converted frames, beside [FILE_NAME]. */
        const val FRAMES = "video.nfv"

        /** The converted sound, beside [FILE_NAME]: all of it, for a video played through from its start. */
        const val AUDIO = "audio.ogg"

        /**
         * The converted sound again in pieces of [AUDIO_CHUNK_SECONDS], `audio/0.ogg`, `audio/1.ogg`, …: the game
         * can only start a sound at its beginning, so a video that's been paused or moved through picks its sound
         * up again at the next piece.
         */
        const val AUDIO_CHUNKS = "audio"

        /** How long each piece of [AUDIO_CHUNKS] is, in seconds. */
        const val AUDIO_CHUNK_SECONDS = 5

        /** The path of piece [index] of a video's sound, in its folder. */
        fun chunkFile(index: Int): String = "$AUDIO_CHUNKS/$index.ogg"

        /** Which piece [file] (a path in a video's folder) is, or null for any other file. */
        fun chunkOf(file: String): Int? {
            val name = file.removePrefix("$AUDIO_CHUNKS/").takeIf { it != file && it.endsWith(".ogg") } ?: return null
            return name.removeSuffix(".ogg").toIntOrNull()?.takeIf { it >= 0 && name == "$it.ogg" }
        }

        const val DEFAULT_FPS = 20
        const val MAX_FPS = 20

        /** What a video's sound is in the resource pack: `video.<id>` in its project's namespace, never a pack's `<pack>/<key>`. */
        fun soundKey(id: String): String = "video.$id"

        /** What piece [index] of a video's sound is in the resource pack: `video.<id>.<index>`. */
        fun chunkKey(id: String, index: Int): String = "video.$id.$index"
    }
}

/** How a picture of another shape than a video's maps fills them. */
@Serializable
enum class VideoFit {
    /** All of it, as big as fits, with black bars where it doesn't reach. */
    @SerialName("contain")
    CONTAIN,

    /** All the maps, the picture cut where it overflows. */
    @SerialName("cover")
    COVER,

    /** All the maps, the picture squashed or stretched to them. */
    @SerialName("stretch")
    STRETCH
}

object VideoValidator {
    fun validate(file: VideoFile, sink: ProblemSink, files: Set<String>?) {
        ScreenValidator.size(file.width, file.height, sink)
        file.fps?.let {
            if (it !in 1..VideoFile.MAX_FPS) sink.report(ProblemCodes.VIDEO_FPS, "fps is 1 to ${VideoFile.MAX_FPS}, not $it", "$.fps")
        }
        val start = file.start
        if (start != null && (start.isNaN() || start < 0)) {
            sink.report(
                ProblemCodes.VIDEO_RANGE,
                "start is a time in seconds, 0 or more, not ${CanonicalJson.formatNumber(start.toString())}",
                "$.start"
            )
        }
        val end = file.end
        if (end != null && (end.isNaN() || end <= (start ?: 0.0))) {
            sink.report(
                ProblemCodes.VIDEO_RANGE,
                "end is a time in seconds after start, not ${CanonicalJson.formatNumber(end.toString())}",
                "$.end"
            )
        }
        if (!Names.isRelativeFile(file.source) || file.source in setOf(VideoFile.FRAMES, VideoFile.AUDIO, VideoFile.FILE_NAME)) {
            sink.report(ProblemCodes.VIDEO_SOURCE, "source is the video file in the video's folder, like \"source.mp4\"", "$.source")
            return
        }
        if (files == null) return
        val converted = VideoFile.FRAMES in files
        // One with neither its source nor its frames yet is being made: there's nothing to convert.
        if (!converted && file.source in files) {
            sink.report(
                ProblemCodes.VIDEO_NOT_CONVERTED,
                "The video hasn't been converted for screens, so it can't play: convert it in the editor",
                "$"
            )
        } else if (converted && file.audioOrDefault && VideoFile.AUDIO !in files) {
            sink.report(
                ProblemCodes.VIDEO_AUDIO_MISSING,
                "audio is on, but there's no ${VideoFile.AUDIO}: convert it again in the editor, or set audio to false",
                "$.audio"
            )
        }
    }
}
