package dev.netherforge.format.cutscene

import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.ProblemSink
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.project.Names
import kotlin.math.abs

object CutsceneValidator {

    fun validate(file: CutsceneFile, sink: ProblemSink) {
        val length = length(file)
        val lengthOk = length > 0.0 && length <= CutsceneFile.MAX_LENGTH
        if (!lengthOk) {
            val said = file.length
            sink.report(
                ProblemCodes.CUTSCENE_LENGTH,
                if (said != null) {
                    "length must be more than 0 and at most ${CutsceneFile.MAX_LENGTH.toInt()} seconds"
                } else {
                    "The keys all sit at time 0, so the cutscene is no time long: set `length`, or key later times"
                },
                "$.length"
            )
        }

        val positions = file.camera.position
        val rotations = file.camera.rotation
        if (positions.isEmpty()) {
            sink.report(ProblemCodes.CUTSCENE_TRACK_EMPTY, "The camera has no position keys, so it has nowhere to be", "$.camera.position")
        }
        if (rotations.isEmpty()) {
            sink.report(ProblemCodes.CUTSCENE_TRACK_EMPTY, "The camera has no rotation keys, so it has no way to look", "$.camera.rotation")
        }
        limit(positions.size, "$.camera.position", "position keys", sink)
        limit(rotations.size, "$.camera.rotation", "rotation keys", sink)
        limit(file.cues.size, "$.cues", "cues", sink)

        keyTimes("$.camera.position", positions.map { it.time }, length, lengthOk, sink)
        keyTimes("$.camera.rotation", rotations.map { it.time }, length, lengthOk, sink)

        positions.forEachIndexed { i, key ->
            val v = key.value
            if (!inWorld(v.x) || !inWorld(v.y) || !inWorld(v.z)) {
                sink.report(
                    ProblemCodes.CUTSCENE_POSITION,
                    "A camera position may be at most ${CutsceneFile.MAX_COORDINATE.toLong()} blocks from the origin",
                    "$.camera.position[$i].value"
                )
            }
        }
        rotations.forEachIndexed { i, key ->
            if (!(key.pitch >= -90.0 && key.pitch <= 90.0)) {
                sink.report(
                    ProblemCodes.CUTSCENE_PITCH,
                    "pitch must be from -90 to 90 degrees, not ${format(key.pitch)}",
                    "$.camera.rotation[$i].pitch"
                )
            }
        }

        file.cues.forEachIndexed { i, cue ->
            val at = "$.cues[$i]"
            if (cue.event == null && cue.text == null) {
                sink.report(ProblemCodes.CUTSCENE_CUE_EMPTY, "A cue needs an `event`, `text`, or both", at)
            }
            cue.event?.let {
                if (!Names.isId(it)) {
                    sink.report(
                        ProblemCodes.CUTSCENE_CUE_EVENT,
                        "\"$it\" isn't a usable event name (${Names.ID_RULE})",
                        CanonicalJson.childPath(at, "event")
                    )
                }
            }
            val duration = cue.duration
            if (duration != null && (cue.text == null || !(duration > 0.0 && duration <= CutsceneFile.MAX_LENGTH))) {
                sink.report(
                    ProblemCodes.CUTSCENE_CUE_DURATION,
                    if (cue.text ==
                        null
                    ) {
                        "`duration` is how long `text` stays, and this cue has none"
                    } else {
                        "duration must be more than 0 seconds"
                    },
                    "$at.duration"
                )
            }
            if (lengthOk && !(cue.time >= 0.0 && cue.time <= length)) {
                sink.report(
                    ProblemCodes.CUTSCENE_KEY_TIME,
                    "A cue's time must be from 0 to the cutscene's length (${format(length)} seconds)",
                    "$at.time"
                )
            }
        }
    }

    /** Seconds the cutscene lasts: its `length`, or the last key's or cue's time. */
    fun length(file: CutsceneFile): Double = file.length ?: maxOf(
        file.camera.position.maxOfOrNull { it.time } ?: 0.0,
        file.camera.rotation.maxOfOrNull { it.time } ?: 0.0,
        file.cues.maxOfOrNull { it.time } ?: 0.0
    )

    private fun inWorld(coordinate: Double) = abs(coordinate) <= CutsceneFile.MAX_COORDINATE

    private fun limit(size: Int, path: String, what: String, sink: ProblemSink) {
        if (size > CutsceneFile.MAX_KEYS) {
            sink.report(ProblemCodes.CUTSCENE_KEY_LIMIT, "At most ${CutsceneFile.MAX_KEYS} $what, not $size", path)
        }
    }

    private fun keyTimes(path: String, times: List<Double>, length: Double, lengthOk: Boolean, sink: ProblemSink) {
        val seen = HashSet<Double>()
        times.forEachIndexed { i, time ->
            if (!(time >= 0.0 && (!lengthOk || time <= length))) {
                sink.report(
                    ProblemCodes.CUTSCENE_KEY_TIME,
                    if (lengthOk) {
                        "A key's time must be from 0 to the cutscene's length (${format(length)} seconds), not ${format(time)}"
                    } else {
                        "A key's time can't be before 0"
                    },
                    "$path[$i].time"
                )
            } else if (!seen.add(time)) {
                sink.report(ProblemCodes.CUTSCENE_KEY_DUPLICATE, "Another key is already at ${format(time)} seconds", "$path[$i].time")
            }
        }
    }

    /** A time in a message: `2`, not `2.0`. The same text on the JVM and in JS. */
    private fun format(seconds: Double) = if (seconds == kotlin.math.floor(seconds) &&
        abs(seconds) < 1e15
    ) {
        seconds.toLong().toString()
    } else {
        seconds.toString()
    }
}
