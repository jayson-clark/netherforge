package dev.netherforge.format.block

import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.ProblemSink
import dev.netherforge.format.game.GameData
import dev.netherforge.format.game.GameIds
import dev.netherforge.format.game.RegistryKey

/**
 * Checks a game block file on its own: its numbers, and that every entry of `blocks` is a block or tag the game has
 * (with game data), named once. Whether another file names one of its blocks, or the project's blocks are held in
 * one, is the project's to check ([dev.netherforge.format.block.GameBlockKind.crossCheck]).
 */
object GameBlockValidator {
    fun validate(file: GameBlockFile, sink: ProblemSink, game: GameData?) {
        if (file.blocks.isEmpty()) {
            sink.report(
                ProblemCodes.GAME_BLOCK_BLOCKS,
                "blocks lists none of the game's blocks, so these rules are for nothing",
                "$.blocks"
            )
        }
        val seen = HashMap<String, Int>()
        file.blocks.forEachIndexed { index, entry ->
            val at = "$.blocks[$index]"
            val tag = entry.startsWith(GameBlockFile.TAG)
            val id = entry.removePrefix(GameBlockFile.TAG)
            if (!GameIds.isValid(id)) {
                sink.report(
                    ProblemCodes.GAME_BLOCK_BLOCKS,
                    "\"$entry\" isn't one of the game's blocks: an id like \"minecraft:stone\", or a tag like \"#minecraft:logs\"",
                    at
                )
                return@forEachIndexed
            }
            val normal = GameIds.normalize(id)
            if (game != null && game.registry(RegistryKey.BLOCK) != null) {
                if (tag && game.tag(RegistryKey.BLOCK, normal) == null) {
                    sink.report(ProblemCodes.GAME_BLOCK_UNKNOWN, "Minecraft ${game.minecraftVersion} has no block tag \"#$normal\"", at)
                    return@forEachIndexed
                } else if (!tag && game.registry(RegistryKey.BLOCK)?.contains(normal) == false) {
                    sink.report(ProblemCodes.GAME_BLOCK_UNKNOWN, "Minecraft ${game.minecraftVersion} has no block \"$normal\"", at)
                    return@forEachIndexed
                }
            }
            // Each block once: a tag counts every block in it, once the game says what they are.
            for (block in GameBlocks.expand(entry) { game?.tag(RegistryKey.BLOCK, it) }) {
                val first = seen.getOrPut(block) { index }
                if (first != index) {
                    sink.report(
                        ProblemCodes.GAME_BLOCK_TWICE,
                        "$block is in blocks already, as \"${file.blocks[first]}\": each block has one set of rules",
                        at
                    )
                    break
                }
            }
        }
        file.hardness?.let {
            if (!it.isFinite() || (it < 0 && it != BlockFile.UNBREAKABLE)) {
                sink.report(
                    ProblemCodes.GAME_BLOCK_HARDNESS,
                    "hardness is how long it takes to mine: 0 or more, or -1 for a block nobody can break",
                    "$.hardness"
                )
            }
        }
        if (file.requiresTool == false && file.drops == null) {
            sink.report(
                ProblemCodes.GAME_BLOCK_REQUIRES_TOOL,
                "requiresTool: false can't make the game's own drops fall for the wrong tool: the game keeps its rule for " +
                    "its loot. Name drops of the project's to drop them for any tool",
                "$.requiresTool"
            )
        }
    }
}
