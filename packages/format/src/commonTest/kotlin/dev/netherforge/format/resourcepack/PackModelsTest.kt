package dev.netherforge.format.resourcepack

import dev.netherforge.format.project.MapProjectSource
import dev.netherforge.format.project.Projects
import dev.netherforge.format.testManifest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A resource pack's model files: what the game would refuse, what's only warned about, and how they're built into the pack. */
class PackModelsTest {
    /** A Blockbench export, as it writes one: its texture saved beside the model, its outliner groups and texture size. */
    private val crystal = """
        {
          "credit": "Made with Blockbench",
          "texture_size": [32, 32],
          "textures": { "0": "crystal", "particle": "crystal", "stone": "block/stone" },
          "elements": [
            { "from": [6, 0, 6], "to": [10, 12, 10], "rotation": { "angle": -37.5, "axis": "y", "origin": [8, 0, 8] },
              "faces": { "north": { "uv": [0, 0, 4, 12], "texture": "#0" }, "up": { "uv": [4, 0, 8, 4], "texture": "#0", "rotation": 90 },
                         "down": { "uv": [4, 0, 8, 4], "texture": "#stone", "cullface": "down" } } },
            { "from": [-16, 0, 7], "to": [32, 3, 9], "rotation": { "x": 10, "y": 22.5, "z": -5, "origin": [8, 0, 8] },
              "faces": { "east": { "uv": [0, 0, 2, 3], "texture": "#0", "tintindex": 0 } } }
          ],
          "display": { "head": { "rotation": [0, 90, 0], "translation": [0, 14, 0], "scale": [1.6, 1.6, 1.6] } },
          "groups": [{ "name": "crystal", "origin": [8, 8, 8], "children": [0, 1] }]
        }
    """.trimIndent()

    private fun mistakes(json: String) = PackModels.validate(Json.parseToJsonElement(json)).map { it.code.code to it.path }

    @Test
    fun aBlockbenchExportIsWhatTheGameReads() {
        assertEquals(emptyList(), mistakes(crystal))
    }

    @Test
    fun whatTheGameRefusesOrClampsIsSaid() {
        val bad = """
            {
              "textures": { "0": "crystal", "far": "otherpack:item/thing" },
              "elements": [
                { "from": [-17, 0, 0], "to": [16, 33, 16], "faces": { "north": { "texture": "#1" } } },
                { "from": [0, 0], "to": [1, 1, 1], "rotation": { "axis": "w", "angle": 10, "origin": [0, 0, 0] }, "faces": {} },
                { "from": [0, 0, 0], "to": [1, 1, 1], "rotation": { "axis": "x", "angle": 10, "x": 2, "origin": [0, 0, 0] },
                  "faces": { "front": { "texture": "#0" }, "up": { "texture": "#0", "rotation": 45, "uv": [0, 0, 1] } } }
              ],
              "display": { "fixed": { "translation": [0, 90, 0], "scale": [5, 1, 1] } }
            }
        """.trimIndent()
        assertEquals(
            listOf(
                "resource_pack.model-texture" to "$.textures.far",
                "resource_pack.model-bounds" to "$.elements[0].from",
                "resource_pack.model-bounds" to "$.elements[0].to",
                "resource_pack.model-texture" to "$.elements[0].faces.north.texture",
                "resource_pack.model" to "$.elements[1].from",
                "resource_pack.model" to "$.elements[1].rotation",
                "resource_pack.model" to "$.elements[1].faces",
                "resource_pack.model" to "$.elements[2].rotation",
                "resource_pack.model" to "$.elements[2].faces.front",
                "resource_pack.model" to "$.elements[2].faces.up.uv",
                "resource_pack.model" to "$.elements[2].faces.up.rotation",
                "resource_pack.model-display" to "$.display.fixed.translation",
                "resource_pack.model-display" to "$.display.fixed.scale"
            ),
            mistakes(bad)
        )
        assertEquals(listOf("resource_pack.model-file" to "$"), mistakes("[]"))
        // With a parent, the parent may give a texture the faces name.
        assertEquals(
            emptyList(),
            mistakes(
                """{ "parent": "block/block", "elements": [{ "from": [0, 0, 0], "to": [16, 16, 16], "faces": { "up": { "texture": "#top" } } }] }"""
            )
        )
    }

    private val project = mapOf(
        "netherforge.json" to testManifest(),
        "resource_packs/mobs/pack.json" to """
            {
              "items": { "crystal": { "model": "crystal.json" }, "flat": { "texture": "crystal.png" } },
              "blocks": { "crystal": { "model": "crystal.json" } }
            }
        """.trimIndent(),
        "resource_packs/mobs/models/crystal.json" to crystal,
        "resource_packs/mobs/textures/crystal.png" to null
    )

    @Test
    fun aModelIsBuiltAsWrittenItsOwnTexturesThePacks() {
        val snapshot = Projects.load(MapProjectSource(project))
        assertEquals(emptyList(), snapshot.problems)
        val files = PackLayout.build(snapshot.compiledResourcePacks.values.toList(), listOf(75, 0), "Test") { null }
        val item = Json.parseToJsonElement(
            (files.getValue("assets/test/models/item/mobs/crystal.json") as PackLayout.Entry.Text).text
        ).jsonObject
        val textures = item.getValue("textures").jsonObject
        assertEquals("test:mobs/crystal", textures.getValue("0").jsonPrimitive.content, "the pack's own texture")
        assertEquals("block/stone", textures.getValue("stone").jsonPrimitive.content, "the game's, as written")
        assertTrue("elements" in item && "groups" in item && "display" in item, "everything else goes through")
        // Drawn from the blocks atlas, since a block look draws it too; copied once.
        assertEquals(PackLayout.Entry.Copy("resource_packs/mobs/textures/crystal.png"), files["assets/test/textures/mobs/crystal.png"])
        val blocks = (files.getValue("assets/minecraft/atlases/blocks.json") as PackLayout.Entry.Text).text
        assertTrue("test:mobs/crystal" in blocks, blocks)
        assertTrue("assets/minecraft/atlases/items.json" !in files, "a sprite is in one atlas")
        assertEquals(
            item,
            Json.parseToJsonElement((files.getValue("assets/test/models/block/mobs/crystal.json") as PackLayout.Entry.Text).text)
        )
    }

    @Test
    fun aModelThatIsntThereOrIsntOneIsAProblemInItsFile() {
        val missing = project + ("resource_packs/mobs/pack.json" to """{ "items": { "crystal": { "model": "gone.json" } } }""")
        assertEquals(
            listOf(Triple("resource_packs/mobs/pack.json", "resource_pack.model-file", "$.items.crystal.model")),
            Projects.load(MapProjectSource(missing)).problems.map { Triple(it.file, it.code, it.path) }
        )
        val broken =
            project +
                (
                    "resource_packs/mobs/models/crystal.json" to
                        """{ "elements": [{ "from": [0, 0, 0], "to": [40, 1, 1], "faces": { "up": { "texture": "#0" } } }], "textures": { "0": "crystal" } }"""
                    )
        val snapshot = Projects.load(MapProjectSource(broken))
        assertEquals(
            listOf(Triple("resource_packs/mobs/models/crystal.json", "resource_pack.model-bounds", "$.elements[0].to")),
            snapshot.problems.map { Triple(it.file, it.code, it.path) }
        )
        assertTrue(snapshot.compiledResourcePacks.isEmpty(), "an error keeps the packs from building")
        val both =
            project +
                ("resource_packs/mobs/pack.json" to """{ "items": { "crystal": { "model": "crystal.json", "texture": "crystal.png" } } }""")
        assertEquals(listOf("resource_pack.item-look"), Projects.load(MapProjectSource(both)).problems.map { it.code })
    }
}
