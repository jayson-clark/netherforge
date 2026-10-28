package dev.netherforge.format.block

import dev.netherforge.format.game.BlockInfo
import dev.netherforge.format.game.GameDataBundle
import dev.netherforge.format.game.RegistryKey
import dev.netherforge.format.project.MapProjectSource
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
