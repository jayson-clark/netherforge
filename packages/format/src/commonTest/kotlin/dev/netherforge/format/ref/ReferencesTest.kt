package dev.netherforge.format.ref

import dev.netherforge.format.Location
import dev.netherforge.format.dialog.DialogKind
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.menu.MenuKind
import dev.netherforge.format.project.MapProjectSource
import dev.netherforge.format.project.Projects
import dev.netherforge.format.recipe.RecipeKind
import dev.netherforge.format.resourcepack.ResourcePackKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReferencesTest {
    private val manifest = """{ "formatVersion": 1, "name": "R", "namespace": "shop", "version": "1.0.0", "minecraft": "26.3" }"""

    @Test
    fun aReferenceResolvesInTheProjectsNamespaceUnlessItNamesAnother() {
        assertEquals(ResourceKey("shop", "ruby"), ResourceRef("ruby").resolve("shop"))
        assertEquals(ResourceKey("acme", "ui/coin"), ResourceRef("acme:ui/coin").resolve("shop"))
        assertEquals(ResourceRef("ui/coin"), ResourceKey("shop", "ui/coin").relativeTo("shop"))
        assertEquals(ResourceRef("acme:ruby"), ResourceKey("acme", "ruby").relativeTo("shop"))
        for (bad in listOf("", "Ruby", "a:b:c", ":ruby", "ruby/", "a b")) assertNull(ResourceRef(bad).resolve("shop"), bad)
        // Each kind narrows the shape: an item is one id, a pack entry a pack and a key.
        assertEquals(true, RefKind.ITEM.isPath("ruby"))
        assertEquals(false, RefKind.ITEM.isPath("ui/ruby"))
        assertEquals(false, RefKind.GLYPH.isPath("coin"))
        assertEquals(true, RefKind.SOUND.isPath("ui/menu/open"))
        assertEquals(false, RefKind.GLYPH.isPath("ui/menu/open"))
    }

    @Test
    fun theWalkerFindsEveryMarkedReferenceAndEveryGlyphTagWithItsPath() {
        val text = """
            {
              "title": "<glyph:ui/a>",
              "type": "dialog_list",
              "dialogs": ["other", "acme:help"],
              "body": [
                { "type": "message", "text": "M <glyph:ui/b> and <glyph:'acme:ui/c'>" },
                { "type": "item", "item": { "kind": "stick", "item": "ruby", "name": "<glyph:ui/d>", "lore": ["L", "<glyph:ui/e>"],
                  "itemModel": "ui/gem", "tooltipStyle": "ui/fancy", "equipment": { "asset": "gear/ruby", "slot": "head" } }, "description": "D" }
              ],
              "inputs": [{ "type": "single_option", "key": "k", "label": "<glyph:ui/f>", "options": [{ "id": "a", "label": "<glyph:ui/g>" }] }],
              "buttons": [{ "key": "ok", "label": "<glyph:ui/h>", "tooltip": "<glyph:ui/i>" }],
              "script": { "file": "script.lua" }
            }
        """
        val file = (DialogKind.parse(text, "x") as CanonicalJson.Parsed.Ok).value
        assertEquals(
            listOf(
                Triple(RefKind.GLYPH, "$.title", "ui/a"),
                Triple(RefKind.GLYPH, "$.body[0].text", "ui/b"),
                Triple(RefKind.GLYPH, "$.body[0].text", "acme:ui/c"),
                Triple(RefKind.ITEM, "$.body[1].item.item", "ruby"),
                Triple(RefKind.GLYPH, "$.body[1].item.name", "ui/d"),
                Triple(RefKind.GLYPH, "$.body[1].item.lore[1]", "ui/e"),
                Triple(RefKind.ITEM_MODEL, "$.body[1].item.itemModel", "ui/gem"),
                Triple(RefKind.TOOLTIP, "$.body[1].item.tooltipStyle", "ui/fancy"),
                Triple(RefKind.EQUIPMENT, "$.body[1].item.equipment.asset", "gear/ruby"),
                Triple(RefKind.GLYPH, "$.inputs[0].label", "ui/f"),
                Triple(RefKind.GLYPH, "$.inputs[0].options[0].label", "ui/g"),
                Triple(RefKind.GLYPH, "$.buttons[0].label", "ui/h"),
                Triple(RefKind.GLYPH, "$.buttons[0].tooltip", "ui/i"),
                Triple(RefKind.DIALOG, "$.dialogs[0]", "other"),
                Triple(RefKind.DIALOG, "$.dialogs[1]", "acme:help"),
                Triple(RefKind.SCRIPT, "$.script.file", "script.lua")
            ),
            RefWalker.find(DialogKind.serializer, file).map { Triple(it.kind, it.path, it.text) }
        )
    }

    @Test
    fun aRecipeIngredientNamingAProjectItemIsAReference() {
        val text = """
            { "type": "shapeless", "ingredients": ["minecraft:stick", { "item": "ruby" }, "#minecraft:planks"], "result": { "item": "gem" } }
        """
        val file = (RecipeKind.parse(text, "x") as CanonicalJson.Parsed.Ok).value
        assertEquals(
            listOf("$.ingredients[1].item" to "ruby", "$.result.item" to "gem"),
            RefWalker.find(RecipeKind.serializer, file).map { it.path to it.text }
        )
    }

    private fun project(vararg files: Pair<String, String?>) =
        Projects.load(MapProjectSource(mapOf("netherforge.json" to manifest, *files)))

    @Test
    fun aBrokenReferencePointsAtBothEnds() {
        val snapshot = project(
            "resource_packs/ui/pack.json" to """{ "glyphs": { "coin": { "texture": "coin.png" } } }""",
            "resource_packs/ui/textures/coin.png" to null,
            "menus/shop/menu.json" to """{ "title": "<glyph:ui/gem> <glyph:ui/coin>", "skin": "ui:shop" }"""
        )
        assertEquals(
            listOf(
                Triple(
                    "reference.resource-pack-key",
                    "<glyph:ui/gem>: Resource pack \"ui\" has no glyph \"gem\" (it has: coin)",
                    listOf(Location("resource_packs/ui/pack.json", "$.glyphs"))
                ),
                Triple(
                    "reference.syntax",
                    "\"ui:shop\" isn't a reference to a skin: write <pack>/<key>, or <namespace>:<pack>/<key> for another package's",
                    emptyList()
                )
            ),
            snapshot.problems.map { Triple(it.code, it.message, it.related) }
        )
    }

    @Test
    fun usagesComeFromOutsideWhatTheyName() {
        val snapshot = project(
            "resource_packs/ui/pack.json" to """{ "glyphs": { "coin": { "texture": "coin.png" } } }""",
            "resource_packs/ui/textures/coin.png" to null,
            "items/ruby/item.json" to """{ "kind": "minecraft:paper", "name": "<glyph:ui/coin>" }""",
            "recipes/ruby.json" to
                """{ "type": "shapeless", "ingredients": [{ "item": "ruby" }], "result": { "item": "ruby", "count": 2 } }"""
        )
        assertEquals(emptyList(), snapshot.problems)
        val references = snapshot.references
        assertEquals(
            listOf("recipes/ruby.json" to "$.ingredients[0].item", "recipes/ruby.json" to "$.result.item"),
            references.usagesOf(RefTarget.Resource("item", "ruby")).map { it.file to it.path }
        )
        // A pack stands for everything in it; its own textures aren't usages of it.
        assertEquals(listOf("items/ruby/item.json"), references.usagesOf(RefTarget.Resource("resource_pack", "ui")).map { it.file })
        assertEquals(
            listOf("resource_packs/ui/pack.json"),
            references.usagesOf(RefTarget.File("resource_packs/ui/textures")).map {
                it.file
            }
        )
        assertEquals(1, references.usagesOf(RefTarget.ResourcePackEntry("ui", RefKind.GLYPH, "coin")).size)
    }

    @Test
    fun renameRewritesEveryReferenceToTheTargetAndNothingElse() {
        val menu = """{ "title": "<glyph:ui/coin> <glyph:shop:ui/coin> <glyph:ui/gem>", "skin": "ui/bg",
            "slots": { "0": { "item": { "item": "ruby", "lore": ["<glyph:ui/coin>"] } }, "1": { "item": { "item": "acme:ruby" } } } }"""
        val path = MenuKind.pathOf("shop")
        val renamed = ReferenceIndex.rename(MenuKind, path, menu, "shop", RefTarget.Resource("item", "ruby"), "garnet")!!
        assertEquals(
            listOf("garnet", "acme:ruby"),
            RefWalker.find(MenuKind.serializer.descriptor, json(renamed)).filter {
                it.kind ==
                    RefKind.ITEM
            }.map { it.text }
        )

        val glyphs = ReferenceIndex.rename(MenuKind, path, menu, "shop", RefTarget.ResourcePackEntry("ui", RefKind.GLYPH, "coin"), "gold")!!
        val title = json(glyphs).let { (it as kotlinx.serialization.json.JsonObject).getValue("title") }
        assertEquals("\"<glyph:ui/gold> <glyph:ui/gold> <glyph:ui/gem>\"", title.toString())

        val pack = ReferenceIndex.rename(MenuKind, path, menu, "shop", RefTarget.Resource("resource_pack", "ui"), "art")!!
        assertEquals(
            listOf("art/coin", "art/coin", "art/gem", "art/bg", "art/coin"),
            RefWalker.find(MenuKind.serializer.descriptor, json(pack)).filter { it.kind != RefKind.ITEM }.map { it.text }
        )
        assertNull(ReferenceIndex.rename(MenuKind, path, menu, "shop", RefTarget.Resource("item", "emerald"), "x"))
    }

    @Test
    fun renamingAFileFollowsItInTheResourceThatNamesIt() {
        val pack = """{ "skins": { "shop": { "texture": "gui/shop.png" } }, "glyphs": { "coin": { "texture": "coin.png" } } }"""
        val path = ResourcePackKind.pathOf("ui")
        val folder = ReferenceIndex.rename(
            ResourcePackKind,
            path,
            pack,
            "shop",
            RefTarget.File("resource_packs/ui/textures/gui"),
            "resource_packs/ui/textures/menus"
        )!!
        assertEquals(
            listOf("menus/shop.png", "coin.png"),
            RefWalker.find(ResourcePackKind.serializer.descriptor, json(folder)).map { it.text }
        )
        // Out of textures/ it can't be named any more: left as it was, for validation to point at.
        assertNull(
            ReferenceIndex.rename(
                ResourcePackKind,
                path,
                pack,
                "shop",
                RefTarget.File("resource_packs/ui/textures/coin.png"),
                "resource_packs/ui/coin.png"
            )
        )
    }

    private fun json(text: String) = CanonicalJson.json.parseToJsonElement(text)
}
