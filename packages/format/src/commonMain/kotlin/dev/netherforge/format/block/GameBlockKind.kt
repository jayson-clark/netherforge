package dev.netherforge.format.block

import dev.netherforge.format.Location
import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.game.GameData
import dev.netherforge.format.game.GameIds
import dev.netherforge.format.game.RegistryKey
import dev.netherforge.format.project.DocumentResourceKind
import dev.netherforge.format.project.KindContext
import dev.netherforge.format.project.Layout
import dev.netherforge.format.project.ResourceContext

/**
 * `game_blocks/<id>.json`: mining rules and drops for some of the game's own blocks ([GameBlockFile]). One file,
 * nothing beside it; the id is only its name. The server mines the blocks a file names as it mines the project's
 * own blocks, and rolls the file's `drops` for them whoever breaks them.
 */
object GameBlockKind : DocumentResourceKind<GameBlockFile, GameBlockFile>(
    "game_block",
    "game_blocks",
    Layout.SingleFile(".json"),
    GameBlockFile.serializer(),
    GameBlockFile.SCHEMA
) {
    override fun canonical(value: GameBlockFile) = value.copy(schema = schemaRef)

    override fun validate(value: GameBlockFile, ctx: ResourceContext) = GameBlockValidator.validate(value, ctx.sink, ctx.game)

    /**
     * A block has one set of rules, so a block another file names first (by id) is refused here. And the blocks the
     * project's own blocks are held in ([BlockCarriers]) are the project's blocks to the server, so they can't have a
     * game block's rules: only the carriers the project's blocks use are refused.
     */
    override fun crossCheck(value: GameBlockFile, ctx: KindContext) {
        val game = ctx.game
        val tag = { id: String -> game?.tag(RegistryKey.BLOCK, id) }
        val earlier = GameBlocks.resolve(ctx.models(GameBlockKind).filterKeys { it < ctx.id }, tag)
        val carriers = ctx.models(BlockKind).values.map { it.carrierOrDefault }.toSet()
            .flatMap { BlockCarriers.pool(game, it)?.blocks?.keys.orEmpty() }.toSet()
        value.blocks.forEachIndexed { index, entry ->
            val at = "$.blocks[$index]"
            val blocks = GameBlocks.expand(entry, tag)
            blocks.sorted().firstOrNull { it in earlier }?.let { block ->
                val other = earlier.getValue(block)
                ctx.sink.report(
                    ProblemCodes.GAME_BLOCK_TWICE,
                    "$block already has the rules of game block \"$other\": each block has one set of rules",
                    at,
                    listOf(Location(ctx.placeOf(this, other).path, "$.blocks"))
                )
            }
            blocks.sorted().firstOrNull { it in carriers }?.let { block ->
                ctx.sink.report(
                    ProblemCodes.GAME_BLOCK_CARRIER,
                    "The project's own blocks are held in $block, so the server takes its states for the project's: " +
                        "it can't mine it as one of the game's",
                    at
                )
            }
        }
    }

    override fun compile(id: String, value: GameBlockFile, ctx: ResourceContext) = value

    /** Rules for the game's blocks are the project's to set: a package's apply only where it's run as the project. */
    override val exportable get() = false

    /** Rules for the game's block of the file's name (`deepslate_bricks.json` is `minecraft:deepslate_bricks`), none set yet. */
    override fun template(id: String, game: GameData?) =
        mapOf(pathOf(id) to write(GameBlockFile(blocks = listOf("${GameIds.NAMESPACE}:$id"))))
}
