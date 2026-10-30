package dev.netherforge.format.block

import dev.netherforge.format.Location
import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.ProblemSink
import dev.netherforge.format.game.GameData
import dev.netherforge.format.game.GameIds
import dev.netherforge.format.game.RegistryKey
import dev.netherforge.format.project.DocumentResourceKind
import dev.netherforge.format.project.KindContext
import dev.netherforge.format.project.Layout
import dev.netherforge.format.project.LoadedPackage
import dev.netherforge.format.project.PackagePaths
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
     *
     * Every package on a server shares the game's blocks, so the project's files are held to its packages' too: a
     * block a package already has rules for, or one a package's blocks are held in, is refused in the project's file
     * (a package is checked on its own, so it can't be the one in the wrong). Packages that clash with each other, or
     * with the project's blocks, are [acrossPackages]'.
     */
    override fun crossCheck(value: GameBlockFile, ctx: KindContext) {
        val game = ctx.game
        val tag = { id: String -> game?.tag(RegistryKey.BLOCK, id) }
        val earlier = GameBlocks.resolve(ctx.models(GameBlockKind).filterKeys { it < ctx.id }, tag)
        val packages = ctx.packageModels(GameBlockKind)
        val packaged = GameBlocks.resolve(packages.mapValues { it.value.value }, tag)
        val carriers = carriersOf(ctx.models(BlockKind).values, game)
        val packageCarriers = ctx.packageModels(BlockKind).entries.groupBy({ PackagePaths.split(it.key).first!! }, { it.value.value })
            .mapValues { carriersOf(it.value, game) }
        value.blocks.forEachIndexed { index, entry ->
            val at = "$.blocks[$index]"
            val blocks = GameBlocks.expand(entry, tag)
            val own = blocks.sorted().firstOrNull { it in earlier }
            if (own != null) {
                val other = earlier.getValue(own)
                ctx.sink.report(
                    ProblemCodes.GAME_BLOCK_TWICE,
                    "$own already has the rules of game block \"$other\": each block has one set of rules",
                    at,
                    listOf(Location(ctx.placeOf(this, other).path, "$.blocks"))
                )
            } else {
                blocks.sorted().firstOrNull { it in packaged }?.let { block ->
                    val other = packaged.getValue(block)
                    val (namespace, id) = PackagePaths.split(other)
                    ctx.sink.report(
                        ProblemCodes.GAME_BLOCK_TWICE,
                        "$block already has the rules of package \"$namespace\"'s game block \"$id\": each block has one " +
                            "set of rules on a server, its packages' included",
                        at,
                        listOf(Location(place(other, packages.getValue(other).group).path, "$.blocks"))
                    )
                }
            }
            val held = blocks.sorted().firstOrNull { it in carriers }
            if (held != null) {
                ctx.sink.report(
                    ProblemCodes.GAME_BLOCK_CARRIER,
                    "The project's own blocks are held in $held, so the server takes its states for the project's: " +
                        "it can't mine it as one of the game's",
                    at
                )
            } else {
                for ((namespace, used) in packageCarriers) {
                    val block = blocks.sorted().firstOrNull { it in used } ?: continue
                    ctx.sink.report(
                        ProblemCodes.GAME_BLOCK_CARRIER,
                        "Package \"$namespace\"'s blocks are held in $block, so the server takes its states for the " +
                            "package's: it can't mine it as one of the game's",
                        at
                    )
                    break
                }
            }
        }
    }

    /**
     * What a project's packages' game block files say against each other and against the project's own blocks, at
     * [manifest] (`$.dependencies`): two packages with rules for one block (the later by `ns:id` is reported, as a
     * project's later file is), or a package's rules for a block another package's blocks, or the project's, are
     * held in. Each package is right on its own, so neither file is: it's the project that runs them together, as
     * for packages that clash ([ProblemCodes.PACKAGE_CONFLICT]). Problems between the project's own files and its
     * packages are [crossCheck]'s, in the project's file.
     */
    fun acrossPackages(packages: Map<String, LoadedPackage>, blocks: Collection<BlockFile>, game: GameData?, manifest: ProblemSink) {
        val tag = { id: String -> game?.tag(RegistryKey.BLOCK, id) }
        val files = packages.entries.sortedBy { it.key }.flatMap { (namespace, pkg) ->
            pkg.snapshot[GameBlockKind].map { (id, loaded) -> PackagePaths.of(namespace, id) to loaded }
        }.toMap()
        // Whose blocks are held in which of the game's blocks: the project's, and each package's.
        val carriers = mapOf<String?, Set<String>>(null to carriersOf(blocks, game)) +
            packages.mapValues { (_, pkg) -> carriersOf(pkg.snapshot[BlockKind].values.map { it.value }, game) }
        val owner = GameBlocks.resolve(files.mapValues { it.value.value }, tag)
        val at = "$.dependencies"
        for ((key, loaded) in files) {
            val (namespace, id) = PackagePaths.split(key)
            val path = place(key, loaded.group).path
            val named = loaded.value.blocks.flatMap { GameBlocks.expand(it, tag) }.distinct().sorted()
            named.firstOrNull { owner[it] != key && PackagePaths.split(owner.getValue(it)).first != namespace }?.let { block ->
                val other = owner.getValue(block)
                val (otherNamespace, otherId) = PackagePaths.split(other)
                manifest.report(
                    ProblemCodes.GAME_BLOCK_TWICE,
                    "Packages \"$otherNamespace\" (game block \"$otherId\") and \"$namespace\" (game block \"$id\") both " +
                        "have rules for $block, and a block has one set of rules on a server",
                    at,
                    listOf(Location(place(other, files.getValue(other).group).path, "$.blocks"), Location(path, "$.blocks"))
                )
            }
            for ((whose, used) in carriers) {
                if (whose == namespace) continue
                val block = named.firstOrNull { it in used } ?: continue
                val blocksOf = whose?.let { "package \"$it\"'s blocks are" } ?: "the project's own blocks are"
                manifest.report(
                    ProblemCodes.GAME_BLOCK_CARRIER,
                    "Package \"$namespace\"'s game block \"$id\" has rules for $block, but $blocksOf held in it, so the " +
                        "server takes its states for those blocks: it can't mine it as one of the game's",
                    at,
                    listOf(Location(path, "$.blocks"))
                )
                break
            }
        }
    }

    /** The game's blocks [blocks] are held in: every state of each carrier they use. */
    private fun carriersOf(blocks: Collection<BlockFile>, game: GameData?): Set<String> =
        blocks.map { it.carrierOrDefault }.toSet().flatMap { BlockCarriers.pool(game, it)?.blocks?.keys.orEmpty() }.toSet()

    override fun compile(id: String, value: GameBlockFile, ctx: ResourceContext) = value

    /** Rules for the game's block of the file's name (`deepslate_bricks.json` is `minecraft:deepslate_bricks`), none set yet. */
    override fun template(id: String, game: GameData?) =
        mapOf(pathOf(id) to write(GameBlockFile(blocks = listOf("${GameIds.NAMESPACE}:$id"))))
}
