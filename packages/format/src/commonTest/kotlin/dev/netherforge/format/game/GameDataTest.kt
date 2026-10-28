package dev.netherforge.format.game

import dev.netherforge.format.ProblemSink
import dev.netherforge.format.TestFiles
import dev.netherforge.format.bridge.Bridge
import dev.netherforge.format.item.ItemDef
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.recipe.Ingredient
import dev.netherforge.format.recipe.RecipeFile
import dev.netherforge.format.recipe.RecipeType
import dev.netherforge.format.recipe.RecipeValidator
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Lookups against `packages/format/testdata/game-data/bundle.json`, a small
 * export in today's shape. The editor's backend reads the same file to check
 * it re-exports a cache of any other [GameDataBundle.SCHEMA].
 */
class GameDataTest {

    private val text = TestFiles.read("packages/format/testdata/game-data/bundle.json")!!
    private val game = CanonicalJson.json.decodeFromString(GameDataBundle.serializer(), text)

    @Test
    fun theFixtureIsTodaysShape() {
        assertEquals(GameDataBundle.SCHEMA, game.schema)
    }

    @Test
    fun theSchemaIsAlwaysWritten() {
        // Format's JSON leaves defaults out; the editor can't tell a cache's shape without it.
        val written = Bridge.json.encodeToString(GameDataBundle.serializer(), GameDataBundle("26.3"))
        assertEquals(GameDataBundle.SCHEMA, Bridge.json.parseToJsonElement(written).jsonObject.getValue("schema").jsonPrimitive.int)
    }

    @Test
    fun registriesAnswerByTheirName() {
        assertEquals(setOf("minecraft:birch_log", "minecraft:oak_log", "minecraft:stone"), game.registry(RegistryKey.ITEM))
        assertEquals(setOf("minecraft:plains", "minecraft:the_void"), game.registry(RegistryKey("minecraft:worldgen/biome")))
        assertEquals(true, game.has(RegistryKey.ITEM, "minecraft:oak_log"))
        assertEquals(false, game.has(RegistryKey.ITEM, "minecraft:ruby"))
    }

    @Test
    fun aRegistryTheDataDoesntHaveIsUnknownNotEmpty() {
        assertNull(game.registry(RegistryKey.ENCHANTMENT))
        assertNull(game.has(RegistryKey.ENCHANTMENT, "minecraft:sharpness"))
    }

    @Test
    fun tagsAnswerByRegistryAndId() {
        assertEquals(setOf("minecraft:birch_log", "minecraft:oak_log"), game.tag(RegistryKey.ITEM, "minecraft:logs"))
        assertEquals(setOf("minecraft:plains"), game.tag(RegistryKey("minecraft:worldgen/biome"), "minecraft:is_overworld"))
        assertNull(game.tag(RegistryKey.ITEM, "minecraft:planks"))
        assertNull(game.tag(RegistryKey.BLOCK, "minecraft:logs"))
    }

    @Test
    fun factsThatArentIdSetsKeepTheirOwnLookups() {
        assertEquals(listOf("x", "y", "z"), game.block("minecraft:oak_log")?.properties?.get("axis"))
        assertNull(game.block("minecraft:dirt"))
        assertEquals(1, game.collisionBoxes(BlockState.parse("stone")!!)?.size)
        assertEquals(ParticleDataKind.DUST, game.particle("minecraft:dust")?.data)
        assertNull(game.particle("minecraft:nope"))
        assertEquals(listOf(97, 1), game.resourcePackFormat)
    }

    @Test
    fun recipesCheckTheirItemsAndTagsAgainstTheGame() {
        val recipe = RecipeFile(
            type = RecipeType.SHAPELESS,
            ingredients = listOf(
                Ingredient(kind = "oak_log"),
                Ingredient(tag = "logs"),
                Ingredient(kind = "ruby"),
                Ingredient(tag = "planks")
            ),
            result = ItemDef("minecraft:stone")
        )
        fun codes(game: GameData?) = ProblemSink("recipes/r.json").also { RecipeValidator.validate(recipe, it, game) }.problems.map {
            it.code to
                it.path
        }
        assertEquals(
            listOf("recipe.unknown-item" to "$.ingredients[2]", "recipe.unknown-tag" to "$.ingredients[3]"),
            codes(game)
        )
        // Data without the item registry says nothing about items or their tags.
        assertEquals(emptyList(), codes(game.copy(registries = emptyMap())))
        assertEquals(emptyList(), codes(null))
    }
}
