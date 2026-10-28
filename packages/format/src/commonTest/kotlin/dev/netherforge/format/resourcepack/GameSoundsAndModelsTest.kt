package dev.netherforge.format.resourcepack

import dev.netherforge.format.block.VanillaFile
import dev.netherforge.format.game.GameDataBundle
import dev.netherforge.format.game.RegistryKey
import dev.netherforge.format.project.MapProjectSource
import dev.netherforge.format.project.Projects
import dev.netherforge.format.ref.RefKind
import dev.netherforge.format.testManifest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A resource pack's `sounds` keyed by one of the game's sound events, and its `models` redrawing the game's. */
class GameSoundsAndModelsTest {
    private val game = GameDataBundle(
        minecraft = "26.3",
        registries = mapOf(RegistryKey.SOUND_EVENT.id to listOf("minecraft:block.gravel.step", "minecraft:block.stone.break"))
    )

    private val pack = """
        {
          "sounds": {
            "minecraft:block.stone.break": { "files": ["blocks/stone_break_1.ogg", "blocks/stone_break_2.ogg"] },
            "minecraft:block.gravel.step": { "files": ["blocks/silt_step.ogg"], "volume": 0.8, "subtitle": "subtitles.block.generic.footsteps" },
            "chime": { "files": ["blocks/silt_step.ogg"] }
          },
          "models": { "block/red_mushroom": "mushroom.json" }
        }
    """.trimIndent()

    private val vanilla = """{ "minecraft": "26.3", "models": ["block/red_mushroom"] }"""

    private fun load(pack: String = this.pack, more: Map<String, String?> = emptyMap(), game: GameDataBundle? = this.game) = Projects.load(
        MapProjectSource(
            mapOf(
                "netherforge.json" to testManifest(),
                "resource_packs/terra/pack.json" to pack,
                "resource_packs/terra/sounds/blocks/stone_break_1.ogg" to null,
                "resource_packs/terra/sounds/blocks/stone_break_2.ogg" to null,
                "resource_packs/terra/sounds/blocks/silt_step.ogg" to null,
                "resource_packs/terra/models/mushroom.json" to """{ "parent": "block/cross", "textures": { "cross": "mushroom" } }""",
                "resource_packs/terra/textures/mushroom.png" to null,
                VanillaFile.FILE to vanilla
            ) + more
        ),
        game
    )

    private fun codes(snapshot: dev.netherforge.format.project.ProjectSnapshot) = snapshot.problems.map {
        it.code to "${it.file} ${it.path}"
    }

    @Test
    fun aGameSoundEventIsBuiltIntoTheGamesSoundsJsonReplacingItsFiles() {
        val snapshot = load()
        assertEquals(emptyList(), codes(snapshot))
        val files = PackLayout.build(snapshot.compiledResourcePacks.values.toList(), listOf(75, 0), "Test", vanilla = snapshot.vanilla) {
            null
        }
        val sounds = Json.parseToJsonElement((files.getValue("assets/minecraft/sounds.json") as PackLayout.Entry.Text).text).jsonObject
        assertEquals(
            Json.parseToJsonElement(
                """{ "replace": true, "sounds": ["test:terra/blocks/stone_break_1", "test:terra/blocks/stone_break_2"] }"""
            ),
            sounds.getValue("block.stone.break")
        )
        assertEquals(
            Json.parseToJsonElement(
                """{ "replace": true, "sounds": [{ "name": "test:terra/blocks/silt_step", "volume": 0.8 }], "subtitle": "subtitles.block.generic.footsteps" }"""
            ),
            sounds.getValue("block.gravel.step")
        )
        // The files play from the pack's own folder, and the pack's own events stay in the project's namespace.
        assertEquals(
            PackLayout.Entry.Copy("resource_packs/terra/sounds/blocks/stone_break_1.ogg"),
            files["assets/test/sounds/terra/blocks/stone_break_1.ogg"]
        )
        val own = Json.parseToJsonElement((files.getValue("assets/test/sounds.json") as PackLayout.Entry.Text).text).jsonObject
        assertEquals(setOf("terra/blocks/silt_step", "terra/blocks/stone_break_1", "terra/blocks/stone_break_2", "terra/chime"), own.keys)
        // A game event isn't one of the pack's sounds a script or block names (each file and `chime` are).
        assertEquals(
            listOf("terra/blocks/silt_step", "terra/blocks/stone_break_1", "terra/blocks/stone_break_2", "terra/chime"),
            snapshot.references.names(RefKind.SOUND).sorted()
        )
    }

    @Test
    fun anEventTheGameLacksOrOneTwoPacksReplaceIsAnError() {
        val unknown = load(pack.replace("minecraft:block.gravel.step", "minecraft:block.silt.step"))
        assertEquals(
            listOf("resource_pack.sound-event" to "resource_packs/terra/pack.json $.sounds[\"minecraft:block.silt.step\"]"),
            codes(unknown)
        )
        // Without game data, nothing says what the game has.
        assertEquals(emptyList(), codes(load(pack.replace("minecraft:block.gravel.step", "minecraft:block.silt.step"), game = null)))
        val twice = load(
            more = mapOf(
                "resource_packs/more/pack.json" to """{ "sounds": { "minecraft:block.stone.break": { "files": ["x.ogg"] } } }""",
                "resource_packs/more/sounds/x.ogg" to null
            )
        )
        // The later pack by id: "more" comes before "terra".
        assertEquals(
            listOf("resource_pack.sound-event" to "resource_packs/terra/pack.json $.sounds[\"minecraft:block.stone.break\"]"),
            codes(twice)
        )
    }

    @Test
    fun aReplacementNeedsFilesAndAGameKeyItsShape() {
        val bad = load(
            """
            {
              "sounds": {
                "minecraft:block.stone.break": { "volume": 0.5 },
                "minecraft:Block Stone": { "files": ["blocks/silt_step.ogg"] },
                "other:block.stone.break": { "files": ["blocks/silt_step.ogg"] }
              }
            }
            """.trimIndent()
        )
        assertEquals(
            listOf(
                "resource_pack.sound-key" to "resource_packs/terra/pack.json $.sounds[\"minecraft:Block Stone\"]",
                "resource_pack.sound-replace" to "resource_packs/terra/pack.json $.sounds[\"minecraft:block.stone.break\"]",
                "resource_pack.sound-key" to "resource_packs/terra/pack.json $.sounds[\"other:block.stone.break\"]"
            ),
            codes(bad).sortedBy { it.second }
        )
    }

    @Test
    fun aGameModelIsWrittenOverTheGamesWithThePacksTextures() {
        val snapshot = load()
        val files = PackLayout.build(snapshot.compiledResourcePacks.values.toList(), listOf(75, 0), "Test", vanilla = snapshot.vanilla) {
            null
        }
        assertEquals(
            Json.parseToJsonElement("""{ "parent": "block/cross", "textures": { "cross": "test:terra/mushroom" } }"""),
            Json.parseToJsonElement((files.getValue("assets/minecraft/models/block/red_mushroom.json") as PackLayout.Entry.Text).text)
        )
        assertEquals(PackLayout.Entry.Copy("resource_packs/terra/textures/mushroom.png"), files["assets/test/textures/terra/mushroom.png"])
        // A block model's textures are stitched into the blocks atlas.
        val atlas = (files.getValue("assets/minecraft/atlases/blocks.json") as PackLayout.Entry.Text).text
        assertEquals(true, "test:terra/mushroom" in atlas)
    }

    @Test
    fun aModelNameWaitsForTheEditorAndOneTheGameLacksIsAnError() {
        val waiting = load(more = mapOf(VanillaFile.FILE to """{ "minecraft": "26.3" }"""))
        assertEquals(listOf("resource_pack.vanilla-missing" to "resource_packs/terra/pack.json $.models"), codes(waiting))
        val files = PackLayout.build(waiting.compiledResourcePacks.values.toList(), listOf(75, 0), "Test", vanilla = waiting.vanilla) {
            null
        }
        assertNull(files["assets/minecraft/models/block/red_mushroom.json"])
        val unknown = load(more = mapOf(VanillaFile.FILE to """{ "minecraft": "26.3", "unknownModels": ["block/red_mushroom"] }"""))
        assertEquals(
            listOf("resource_pack.replace-name" to "resource_packs/terra/pack.json $.models[\"block/red_mushroom\"]"),
            codes(unknown)
        )
    }
}
