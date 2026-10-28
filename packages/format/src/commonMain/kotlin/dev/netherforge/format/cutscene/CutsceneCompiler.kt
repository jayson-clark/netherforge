package dev.netherforge.format.cutscene

import dev.netherforge.format.Problem
import dev.netherforge.format.ProblemSink
import dev.netherforge.format.hasErrors

object CutsceneCompiler {
    /** Compiles a [file] that validated without errors. */
    fun compile(id: String, file: CutsceneFile): CompiledCutscene {
        val path = CameraPath(file.camera.position.sortedBy { it.time }, file.camera.rotation.sortedBy { it.time })
        val cues = file.cues.sortedBy { it.time }.map {
            CompiledCue(it.time, it.event, it.text, it.duration ?: Cue.DEFAULT_DURATION)
        }
        return CompiledCutscene(id, CutsceneValidator.length(file), file.skippable ?: false, path, cues)
    }

    /** Validates then compiles: the cutscene, or the problems that stop it. */
    fun compileChecked(id: String, file: CutsceneFile, path: String): Pair<CompiledCutscene?, List<Problem>> {
        val sink = ProblemSink(path)
        CutsceneValidator.validate(file, sink)
        return if (sink.problems.hasErrors) null to sink.problems else compile(id, file) to sink.problems
    }
}
