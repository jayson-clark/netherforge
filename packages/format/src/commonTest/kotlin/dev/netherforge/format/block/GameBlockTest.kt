package dev.netherforge.format.block

import dev.netherforge.format.game.BlockInfo
import dev.netherforge.format.game.GameDataBundle
import dev.netherforge.format.game.RegistryKey
import dev.netherforge.format.project.FormatVersion
import dev.netherforge.format.project.MapProjectSource
import dev.netherforge.format.project.OpenedPackage
import dev.netherforge.format.project.PackageLookup
import dev.netherforge.format.project.PackageMissing
import dev.netherforge.format.project.PackageRequest
import dev.netherforge.format.project.PackageSources
import dev.netherforge.format.project.Projects
import dev.netherforge.format.testManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `game_blocks/<id>.json`: the project's mining rules and drops for the game's own blocks. */
class GameBlockTest {
    private val game = GameDataBundle(
        minecraft = "26.3",
        registries = mapOf(
            RegistryKey.BLOCK.id to listOf(
                "minecraft:deepslate_brick_slab",
                "minecraft:deepslate_brick_stairs",
                "minecraft:deepslate_bricks",
                "minecraft:note_block",
                "minecraft:oak_log",
                "minecraft:spruce_log",
                "minecraft:stone"
            )
        ),
        tags = mapOf(RegistryKey.BLOCK.id to mapOf("minecraft:logs" to listOf("minecraft:oak_log", "minecraft:spruce_log"))),
        blocks = mapOf(
            BlockCarriers.BLOCK to BlockInfo(
                properties = mapOf(
                    "instrument" to listOf("harp", "basedrum"),
                    "note" to listOf("0", "1"),
                    "powered" to listOf("true", "false")
                ),
                defaults = mapOf("instrument" to "harp", "note" to "0", "powered" to "false")
            )
        )
    )

    private fun load(files: Map<String, String>, game: GameDataBundle? = this.game) =
        Projects.load(MapProjectSource(mapOf("netherforge.json" to testManifest()) + files), game)

    private fun problems(files: Map<String, String>, game: GameDataBundle? = this.game) =
        load(files, game).problems.map { it.code to "${it.file} ${it.path}" }

    private val loot = "loot/brick.json" to """{ "pools": {} }"""

    @Test
    fun aFileNamesTheGamesBlocksByIdAndByTagAndKeepsTheRestTheGames() {
        val snapshot = load(
            mapOf(
                "game_blocks/bricks.json" to """
                    {
                      "blocks": ["minecraft:deepslate_bricks", "minecraft:deepslate_brick_stairs", "deepslate_brick_slab"],
                      "hardness": 6,
                      "tool": "pickaxe",
                      "requiresTool": true,
                      "drops": "brick"
                    }
                """.trimIndent(),
                "game_blocks/logs.json" to """{ "blocks": ["#minecraft:logs"], "drops": "brick" }""",
                loot
            )
        )
        assertEquals(emptyList(), snapshot.problems)
        val files = snapshot.compiled(GameBlockKind).mapValues { it.value }
        val rules = GameBlocks.resolve(files) { game.tag(RegistryKey.BLOCK, it) }
        assertEquals(
            mapOf(
                "minecraft:deepslate_bricks" to "bricks",
                "minecraft:deepslate_brick_stairs" to "bricks",
                "minecraft:deepslate_brick_slab" to "bricks",
                "minecraft:oak_log" to "logs",
                "minecraft:spruce_log" to "logs"
            ),
            rules
        )
        // Only rules that change how it's mined are the server's to count; drops alone leave the player's game to it.
        assertTrue(files.getValue("bricks").minedByServer)
        assertFalse(files.getValue("logs").minedByServer)
    }

    @Test
    fun anIdOrTagTheGameLacksIsAnErrorWithGameDataAndNothingWithout() {
        val files = mapOf("game_blocks/odd.json" to """{ "blocks": ["minecraft:nothing", "#minecraft:nothings", "Not An Id"] }""")
        assertEquals(
            listOf(
                "game_block.unknown" to "game_blocks/odd.json $.blocks[0]",
                "game_block.unknown" to "game_blocks/odd.json $.blocks[1]",
                "game_block.blocks" to "game_blocks/odd.json $.blocks[2]"
            ),
            problems(files)
        )
        assertEquals(listOf("game_block.blocks" to "game_blocks/odd.json $.blocks[2]"), problems(files, null))
    }

    @Test
    fun aBlockHasOneSetOfRulesWhetherNamedByIdOrThroughATag() {
        val files = mapOf(
            "game_blocks/a.json" to """{ "blocks": ["minecraft:oak_log", "#minecraft:logs"] }""",
            "game_blocks/b.json" to """{ "blocks": ["minecraft:spruce_log", "minecraft:stone"] }""",
            "game_blocks/c.json" to """{ "blocks": ["minecraft:stone"] }"""
        )
        assertEquals(
            listOf(
                // In one file, a tag holding a block already named.
                "game_block.twice" to "game_blocks/a.json $.blocks[1]",
                // Across files, the later one by id: spruce through a's tag, stone through b.
                "game_block.twice" to "game_blocks/b.json $.blocks[0]",
                "game_block.twice" to "game_blocks/c.json $.blocks[0]"
            ),
            problems(files)
        )
    }

    @Test
    fun theBlocksTheProjectsBlocksAreHeldInAreTheProjects() {
        val files = mapOf(
            "game_blocks/notes.json" to """{ "blocks": ["minecraft:note_block"], "hardness": 2 }""",
            "blocks/ruby/block.json" to "{}"
        )
        assertEquals(listOf("game_block.carrier" to "game_blocks/notes.json $.blocks[0]"), problems(files))
        // Without a block held in note blocks, the game's note block is the game's.
        assertEquals(emptyList(), problems(files - "blocks/ruby/block.json"))
    }

    /** A project depending on each of [packages] (namespace → its files), loaded with them. */
    private fun withPackages(files: Map<String, String>, packages: Map<String, Map<String, String>>) = Projects.load(
        MapProjectSource(
            mapOf(
                "netherforge.json" to testManifest(
                    """, "dependencies": { ${packages.keys.joinToString { "\"$it\": { \"path\": \"../$it\" }" }} }"""
                )
            ) + files
        ),
        game,
        object : PackageSources {
            override val checksLock get() = false

            override fun open(namespace: String, request: PackageRequest): PackageLookup {
                val pkg = packages[namespace] ?: return PackageMissing()
                val manifest = """{ "formatVersion": ${FormatVersion.CURRENT}, "name": "$namespace", "namespace": "$namespace", """ +
                    """"version": "1.0.0", "minecraft": "26.3" }"""
                return OpenedPackage(MapProjectSource(mapOf("netherforge.json" to manifest) + pkg))
            }
        }
    )

    @Test
    fun aPackagesRulesRunBesideTheProjectsAndCanBeExported() {
        val lib = mapOf(
            "netherforge.json" to
                """{ "formatVersion": ${FormatVersion.CURRENT}, "name": "Lib", "namespace": "lib", "version": "1.0.0", """ +
                """"minecraft": "26.3", "exports": { "game_blocks": ["bricks"] } }""",
            "game_blocks/bricks.json" to """{ "blocks": ["minecraft:deepslate_bricks"], "hardness": 6, "drops": "brick" }""",
            loot
        )
        val snapshot = withPackages(
            mapOf("game_blocks/stone.json" to """{ "blocks": ["minecraft:stone"], "hardness": 3 }"""),
            mapOf(
                "lib" to lib
            )
        )
        assertEquals(emptyList(), snapshot.problems)
        val running = snapshot.running(GameBlockKind)
        assertEquals(setOf("stone", "lib:bricks"), running.keys)
        // Its drops are the package's loot table, named in full beside the project's.
        assertEquals("lib:brick", running.getValue("lib:bricks").drops?.text)
    }

    @Test
    fun theProjectsRulesAreHeldToItsPackagesAtTheProjectsFile() {
        val lib = mapOf(
            "game_blocks/stone.json" to """{ "blocks": ["minecraft:stone"] , "hardness": 3 }""",
            "blocks/gem/block.json" to "{}"
        )
        val snapshot = withPackages(
            mapOf(
                "game_blocks/mine.json" to
                    """{ "blocks": ["minecraft:stone", "minecraft:note_block", "minecraft:oak_log"], "hardness": 2 }"""
            ),
            mapOf("lib" to lib)
        )
        assertEquals(
            listOf(
                "game_block.twice" to "game_blocks/mine.json $.blocks[0]",
                "game_block.carrier" to "game_blocks/mine.json $.blocks[1]"
            ),
            snapshot.problems.map { it.code to "${it.file} ${it.path}" }
        )
        assertEquals(listOf("lib:game_blocks/stone.json"), snapshot.problems.first().related.map { it.file })
        // The project's file doesn't run; the package's rules hold.
        assertEquals(setOf("lib:stone"), snapshot.running(GameBlockKind).keys)
    }

    @Test
    fun packagesThatClashAreTheProjectsToResolveAtItsDependencies() {
        val a = mapOf("game_blocks/rocks.json" to """{ "blocks": ["minecraft:stone"], "hardness": 3 }""", "blocks/gem/block.json" to "{}")
        val b = mapOf(
            "game_blocks/more.json" to """{ "blocks": ["minecraft:stone", "minecraft:oak_log"], "hardness": 4 }""",
            "game_blocks/notes.json" to """{ "blocks": ["minecraft:note_block"], "hardness": 1 }"""
        )
        val snapshot = withPackages(emptyMap(), mapOf("a" to a, "b" to b))
        assertEquals(
            listOf(
                "game_block.twice" to "netherforge.json $.dependencies",
                "game_block.carrier" to "netherforge.json $.dependencies"
            ),
            snapshot.problems.map { it.code to "${it.file} ${it.path}" }
        )
        assertEquals(
            listOf("a:game_blocks/rocks.json", "b:game_blocks/more.json"),
            snapshot.problems.first { it.code == "game_block.twice" }.related.map { it.file }
        )
        // Each package on its own is fine; so are both without a's blocks and with another block for b.
        val apart = withPackages(
            emptyMap(),
            mapOf("a" to a - "blocks/gem/block.json", "b" to b + ("game_blocks/more.json" to """{ "blocks": ["minecraft:oak_log"] }"""))
        )
        assertEquals(emptyList(), apart.problems)
    }

    @Test
    fun aPackagesRulesForABlockTheProjectsBlocksAreHeldInAreRefused() {
        val lib = mapOf("game_blocks/notes.json" to """{ "blocks": ["minecraft:note_block"], "hardness": 1 }""")
        val snapshot = withPackages(mapOf("blocks/ruby/block.json" to "{}"), mapOf("lib" to lib))
        assertEquals(
            listOf("game_block.carrier" to "netherforge.json $.dependencies"),
            snapshot.problems.map { it.code to "${it.file} ${it.path}" }
        )
    }

    @Test
    fun hardnessDropsAndTheToolRuleAreChecked() {
        val files = mapOf(
            "game_blocks/odd.json" to """{ "blocks": ["minecraft:stone"], "hardness": -2, "requiresTool": false, "drops": "nothing" }""",
            "game_blocks/loose.json" to """{ "blocks": ["minecraft:oak_log"], "requiresTool": false }"""
        )
        assertEquals(
            listOf(
                "game_block.requires-tool" to "game_blocks/loose.json $.requiresTool",
                "reference.loot-table" to "game_blocks/odd.json $.drops",
                "game_block.hardness" to "game_blocks/odd.json $.hardness"
            ),
            problems(files).sortedBy { it.second }
        )
    }

    @Test
    fun aNewFileIsForTheGamesBlockOfItsName() {
        val made = GameBlockKind.template("deepslate_bricks", game)!!
        val snapshot = load(made)
        assertEquals(emptyList(), snapshot.problems)
        assertEquals(listOf("minecraft:deepslate_bricks"), snapshot.compiled(GameBlockKind).getValue("deepslate_bricks").blocks)
        assertEquals(
            GameBlockFile(blocks = listOf("minecraft:deepslate_bricks"), schema = GameBlockFile.SCHEMA),
            snapshot.compiled(GameBlockKind)["deepslate_bricks"]
        )
    }
}
