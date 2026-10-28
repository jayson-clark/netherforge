package dev.netherforge.format.block

import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.ProblemSink
import dev.netherforge.format.validate.Rules

/**
 * Checks a block on its own: its numbers and its script. That the model, loot
 * table, centity and sounds it names exist is the project's to check, as for
 * every reference, and whether the game has a state left to hold it is the
 * project's too ([dev.netherforge.format.block.BlockKind.crossCheck]).
 */
object BlockValidator {
    /** What a game sound's block kind looks like: the `stone` of `block.stone.place`. */
    private val SOUND = Regex("[a-z0-9_]+")

    fun validate(file: BlockFile, sink: ProblemSink, files: Set<String>?) {
        file.hardness?.let {
            if (!it.isFinite() || (it < 0 && it != BlockFile.UNBREAKABLE)) {
                sink.report(
                    ProblemCodes.BLOCK_HARDNESS,
                    "hardness is how long it takes to mine: 0 or more, or -1 for a block nobody can break",
                    "$.hardness"
                )
            }
        }
        file.tick?.let {
            if (it < 1 || it > BlockFile.MAX_TICK) {
                sink.report(ProblemCodes.BLOCK_TICK, "tick is a whole number of ticks from 1 to ${BlockFile.MAX_TICK}", "$.tick")
            }
        }
        if (file.requiresToolOrDefault && file.tool == null) {
            sink.report(
                ProblemCodes.BLOCK_REQUIRES_TOOL,
                "requiresTool means nothing without a tool: no tool is the right one, so nothing would ever drop",
                "$.requiresTool"
            )
        }
        file.sound?.let {
            if (!SOUND.matches(it)) {
                sink.report(
                    ProblemCodes.BLOCK_SOUND,
                    "sound is the game's kind of block it sounds like, like \"stone\" or \"wood\"",
                    "$.sound"
                )
            }
        }
        if (file.fallsOrDefault) {
            when {
                file.carrierOrDefault == BlockCarrier.TRIPWIRE -> sink.report(
                    ProblemCodes.BLOCK_FALLS,
                    "Tripwire breaks when what's under it goes, so a tripwire block can't fall",
                    "$.falls"
                )
                file.centity != null -> sink.report(
                    ProblemCodes.BLOCK_FALLS,
                    "A falling block is drawn by the game alone, so a block a centity is drawn over can't fall",
                    "$.falls"
                )
            }
        }
        file.script?.let { Rules.script(it, "$.script", sink, files) }
    }
}
