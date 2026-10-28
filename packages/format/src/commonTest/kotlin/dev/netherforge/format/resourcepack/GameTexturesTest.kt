package dev.netherforge.format.resourcepack

import dev.netherforge.format.block.VanillaFile
import dev.netherforge.format.project.ImageInfo
import dev.netherforge.format.project.MapProjectSource
import dev.netherforge.format.project.Projects
import dev.netherforge.format.resourcepack.ReplacedTexture.Fit
import dev.netherforge.format.testManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A resource pack's `textures` beyond the screens: the game's blocks, items and mobs redrawn, at any detail. */
class GameTexturesTest {
    private val pack = """
        {
          "textures": {
            "block/stone": "block/stone.png",
            "block/water_still": "block/water.png",
            "entity/zombie/zombie": "mob/zombie.png",
            "item/diamond": "item/diamond.png"
          }
        }
    """.trimIndent()

    /** What the editor writes for [pack]: each name's size, as the client has it. */
    private val vanilla = """
        {
          "minecraft": "26.3",
          "textures": {
            "block/stone": [16, 16],
            "block/water_still": [16, 512],
            "entity/zombie/zombie": [64, 64],
            "item/diamond": [16, 16]
          }
        }
    """.trimIndent()

    private fun load(
        vanilla: String? = this.vanilla,
        sizes: Map<String, Pair<Int, Int>> = mapOf(
            "block/stone.png" to (32 to 32),
            "block/water.png" to (32 to 1024),
            "mob/zombie.png" to (128 to 128),
            "item/diamond.png" to (16 to 16)
        ),
        // The water's an animation, its frames said by its own .mcmeta.
        extra: Map<String, String?> = mapOf("resource_packs/ui/textures/block/water.png.mcmeta" to "{}")
    ) = Projects.load(
        MapProjectSource(
            mapOf("netherforge.json" to testManifest(), "resource_packs/ui/pack.json" to pack, VanillaFile.FILE to vanilla) +
                sizes.keys.associate { "resource_packs/ui/textures/$it" to null } + extra,
            sizes.entries.associate { (path, size) ->
                "resource_packs/ui/textures/$path" to ImageInfo(size.first, size.second, size.first)
            }
        )
    )

    @Test
    fun theBuiltPackDrawsTheGamesBlocksItemsAndMobsWithThePacksPictures() {
        val snapshot = load()
        assertEquals(emptyList(), snapshot.problems.filter { it.code?.startsWith("resource_pack.") == true })
        val files = PackLayout.build(snapshot.compiledResourcePacks.values.toList(), listOf(75, 0), "Test", vanilla = snapshot.vanilla) {
            null
        }
        val game = "assets/minecraft/textures"
        assertEquals(PackLayout.Entry.Copy("resource_packs/ui/textures/block/stone.png"), files["$game/block/stone.png"])
        assertEquals(PackLayout.Entry.Copy("resource_packs/ui/textures/mob/zombie.png"), files["$game/entity/zombie/zombie.png"])
        assertEquals(PackLayout.Entry.Copy("resource_packs/ui/textures/item/diamond.png"), files["$game/item/diamond.png"])
        // An animation brings its own frames; a still picture none, so the game's (in its own pack) doesn't apply.
        assertEquals(
            PackLayout.Entry.Copy("resource_packs/ui/textures/block/water.png.mcmeta"),
            files["$game/block/water_still.png.mcmeta"]
        )
        assertNull(files["$game/block/stone.png.mcmeta"])
        // A texture the game already stitches into an atlas is in it under its own name: no atlas source is added.
        assertNull(files["assets/minecraft/atlases/blocks.json"])
    }

    @Test
    fun aNameTheEditorHasntLookedUpWaitsAndOneTheGameLacksIsAnError() {
        fun codes(vanilla: String?) = load(vanilla).problems.filter { it.code?.startsWith("resource_pack.") == true }.map {
            it.code to
                it.path
        }
        assertEquals(listOf("resource_pack.vanilla-missing" to "$.textures"), codes(null))
        assertEquals(
            listOf("resource_pack.vanilla-missing" to "$.textures"),
            codes(vanilla.replace("\"item/diamond\": [16, 16]", "\"x\": [1, 1]"))
        )
        assertEquals(
            listOf("resource_pack.replace-name" to "$.textures[\"item/diamond\"]"),
            codes(
                vanilla.replace(",\n    \"item/diamond\": [16, 16]", "")
                    .replace("\n  }\n}", "\n  },\n  \"unknownTextures\": [\"item/diamond\"]\n}")
            )
        )
    }

    @Test
    fun aReplacementOfAnotherShapeOrAStillStripIsAWarning() {
        val problems = load(
            sizes = mapOf(
                "block/stone.png" to (32 to 16),
                "block/water.png" to (16 to 512),
                "mob/zombie.png" to (64 to 64),
                "item/diamond.png" to (16 to 16)
            ),
            extra = emptyMap()
        ).problems.filter { it.code?.startsWith("resource_pack.") == true }.map { it.code to it.path }
        assertEquals(
            listOf(
                "resource_pack.replace-shape" to "$.textures[\"block/stone\"]",
                "resource_pack.replace-shape" to "$.textures[\"block/water_still\"]"
            ),
            problems
        )
    }

    @Test
    fun screensAndColourMapsTakeTheGamesSizeAndEverythingElseItsShape() {
        assertEquals(Fit.SIZE, ReplacedTexture.fit("gui/container/inventory", listOf(256, 256), listOf(512, 512), false))
        assertEquals(Fit.SIZE, ReplacedTexture.fit("colormap/grass", listOf(256, 256), listOf(128, 128), false))
        assertEquals(Fit.OK, ReplacedTexture.fit("gui/sprites/hud/hotbar", listOf(182, 22), listOf(364, 44), false))
        assertEquals(Fit.OK, ReplacedTexture.fit("block/stone", listOf(16, 16), listOf(64, 64), false))
        assertEquals(Fit.SHAPE, ReplacedTexture.fit("entity/zombie/zombie", listOf(64, 64), listOf(64, 32), false))
        assertEquals(Fit.OK, ReplacedTexture.fit("block/water_still", listOf(16, 512), listOf(16, 16), false))
        assertEquals(Fit.STILL, ReplacedTexture.fit("block/water_still", listOf(16, 512), listOf(16, 256), false))
        assertEquals(Fit.OK, ReplacedTexture.fit("block/stone", listOf(16, 16), listOf(16, 64), true))
    }
}
