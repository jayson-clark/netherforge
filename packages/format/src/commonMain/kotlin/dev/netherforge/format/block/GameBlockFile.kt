package dev.netherforge.format.block

import dev.netherforge.format.game.GameIds
import dev.netherforge.format.ref.Ref
import dev.netherforge.format.ref.RefKind
import dev.netherforge.format.ref.ResourceRef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `game_blocks/<id>.json`: mining rules of the project's for some of the game's own blocks (`minecraft:deepslate_bricks`,
 * every `#minecraft:logs`), as a project block has them: how long they take to mine, with what, and what they drop.
 *
 * The game's blocks keep everything else the game gives them (shape, light, sounds, falling, spreading, stairs and
 * slabs), so a resource pack's redrawn textures and these rules together make one stand in for a project block.
 * Every key but [blocks] is optional, and **one left out is the game's own** for the block: a file with only [drops]
 * changes nothing about mining. The id is only the file's name; nothing names a game block file.
 */
@Serializable
data class GameBlockFile(
    @SerialName("\$schema") val schema: String? = null,
    /**
     * The game's blocks these rules are for: ids (`minecraft:deepslate_bricks`, every state of it; stairs and slabs
     * are ids of their own) and block tags (`#minecraft:logs`). No block may be named by two files, or twice in one.
     */
    val blocks: List<String>,
    /**
     * How long it takes to mine, in the game's units, as a project block's `hardness` (stone 1.5, obsidian 50): 0 breaks
     * at once and -1 can't be broken by hand. Default the game's.
     */
    val hardness: Double? = null,
    /** The tool it's mined with, fastest, and the only one that gets its drops when [requiresTool]. Default the game's tools for it. */
    val tool: BlockTool? = null,
    /**
     * Whether it drops nothing unless mined with the right tool: [tool], or the game's own for the block when there's
     * none. Default the game's rule (stone needs a pickaxe, dirt nothing).
     */
    val requiresTool: Boolean? = null,
    /** `<id>`: the loot table rolled when it's broken, instead of the game's. Default the game's drops. */
    @Ref(RefKind.LOOT_TABLE) val drops: ResourceRef? = null
) {
    /**
     * Whether mining it takes something the game wouldn't, so the server counts it ([hardness], [tool] or
     * [requiresTool]); a file with only [drops] leaves the player's own game to mine it.
     */
    val minedByServer: Boolean get() = hardness != null || tool != null || requiresTool != null

    companion object {
        const val SCHEMA = "../.netherforge/schema/game_block.schema.json"

        /** What a tag is written with in [blocks]. */
        const val TAG = "#"
    }
}

/**
 * Which file's rules each of the game's blocks has: the one place [GameBlockFile.blocks] are read, so the editor's
 * checks and the server agree on what a file covers.
 */
object GameBlocks {
    /**
     * Every block [files] (by id) name, by block id, with the file naming it (the first by id, when two do), and the
     * ids each tag holds in [tag] (null for a tag the game doesn't have, which names nothing). An entry that isn't
     * an id is left out.
     */
    fun resolve(files: Map<String, GameBlockFile>, tag: (String) -> Set<String>?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((id, file) in files.entries.sortedBy { it.key }) {
            for (entry in file.blocks) {
                for (block in expand(entry, tag)) out.getOrPut(block) { id }
            }
        }
        return out
    }

    /** The block ids [entry] (an id or a `#tag`) names, namespaced: none for one that isn't an id or a tag the game has. */
    fun expand(entry: String, tag: (String) -> Set<String>?): Set<String> {
        val isTag = entry.startsWith(GameBlockFile.TAG)
        val id = entry.removePrefix(GameBlockFile.TAG)
        if (!GameIds.isValid(id)) return emptySet()
        val normal = GameIds.normalize(id)
        return if (isTag) tag(normal).orEmpty() else setOf(normal)
    }
}
