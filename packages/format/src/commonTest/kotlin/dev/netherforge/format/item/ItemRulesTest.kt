package dev.netherforge.format.item

import dev.netherforge.format.ProblemSink
import dev.netherforge.format.game.BlockInfo
import dev.netherforge.format.game.GameDataBundle
import dev.netherforge.format.game.RegistryKey
import dev.netherforge.format.ref.RefKind
import dev.netherforge.format.validate.Rules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Item checks a project file can't reach: the ones that need the game's facts (attribute, enchantment
 * and block ids the game has), and a modifier amount that isn't a number, which JSON can't hold but a
 * script building an item can.
 */
class ItemRulesTest {
    private val item = ItemDef(
        "minecraft:diamond_sword",
        enchantments = mapOf("sharpness" to 2, "minecraft:sharpnes" to 1),
        attributeModifiers = listOf(
            AttributeModifierDef(attribute = "attack_damage", amount = 1.0, operation = AttributeOperation.ADD_VALUE),
            AttributeModifierDef(attribute = "minecraft:attack_damag", amount = 1.0, operation = AttributeOperation.ADD_VALUE)
        ),
        canBreak = listOf("minecraft:stone", "minecraft:ston", "ruby_ore", "#basic:soft", "*")
    )

    private fun problems(game: GameDataBundle?): List<Pair<String?, String?>> {
        val sink = ProblemSink("menus/shop/menu.json")
        Rules.item(item, "$.item", sink, game)
        return sink.problems.map { it.code to it.path }
    }

    @Test
    fun `an attribute or enchantment the game doesn't have is an error`() {
        val game = GameDataBundle(
            "26.3",
            registries = mapOf(
                RegistryKey.ITEM.id to listOf("minecraft:diamond_sword"),
                RegistryKey.ATTRIBUTE.id to listOf("minecraft:attack_damage"),
                RegistryKey.ENCHANTMENT.id to listOf("minecraft:sharpness")
            ),
            blocks = mapOf("minecraft:stone" to BlockInfo())
        )
        assertEquals(
            listOf(
                "item.unknown-enchantment" to "$.item.enchantments[\"minecraft:sharpnes\"]",
                "item.unknown-attribute" to "$.item.attributeModifiers[1].attribute",
                "item.unknown-block" to "$.item.canBreak[1]"
            ),
            problems(game)
        )
    }

    @Test
    fun `without those facts nothing is said about them`() {
        assertEquals(emptyList(), problems(null))
        assertEquals(
            emptyList(),
            problems(
                GameDataBundle(
                    "26.3",
                    registries = mapOf(
                        RegistryKey.ITEM.id to listOf("minecraft:diamond_sword")
                    ),
                    // Blocks are known one by one (their properties), so data with blocks knows all of them.
                    blocks = mapOf("minecraft:stone" to BlockInfo(), "minecraft:ston" to BlockInfo())
                )
            )
        )
    }

    @Test
    fun `a modifier amount that isn't a number is an error`() {
        for (amount in listOf(Double.NaN, Double.POSITIVE_INFINITY)) {
            val sink = ProblemSink("menus/shop/menu.json")
            val modifier = AttributeModifierDef(attribute = "minecraft:armor", amount = amount, operation = AttributeOperation.ADD_VALUE)
            Rules.item(ItemDef("minecraft:paper", attributeModifiers = listOf(modifier)), "$.item", sink, null)
            assertEquals(listOf("item.attribute-amount" to "$.item.attributeModifiers[0].amount"), sink.problems.map { it.code to it.path })
        }
    }

    @Test
    fun `adventure blocks are the game's blocks and tags, checked against it, or references left to the loader`() {
        val game = GameDataBundle(
            "26.3",
            blocks = mapOf("minecraft:stone" to BlockInfo(), "minecraft:oak_log" to BlockInfo()),
            registries = mapOf(RegistryKey.BLOCK.id to listOf("minecraft:stone", "minecraft:oak_log")),
            tags = mapOf(RegistryKey.BLOCK.id to mapOf("minecraft:logs" to listOf("minecraft:oak_log")))
        )
        fun problems(vararg blocks: String): List<Pair<String?, String?>> {
            val sink = ProblemSink("items/pick/item.json")
            Rules.item(ItemDef(kind = "minecraft:wooden_pickaxe", canPlaceOn = blocks.toList()), "$", sink, game)
            return sink.problems.map { it.code to it.path }
        }
        assertEquals(emptyList(), problems("*", "#minecraft:logs", "#logs", "minecraft:stone", "ruby_ore", "acme:gem", "#basic:soft"))
        assertEquals(
            listOf(
                "item.unknown-block" to "$.canPlaceOn[0]",
                "item.unknown-block" to "$.canPlaceOn[1]",
                "item.block" to "$.canPlaceOn[2]"
            ),
            problems("#minecraft:leaves", "minecraft:sand", "#Not A Tag")
        )
        assertTrue(
            RefKind.ADVENTURE_BLOCK.isGame("*") && RefKind.ADVENTURE_BLOCK.isGame("#logs") && RefKind.ADVENTURE_BLOCK.isGame("minecraft:x")
        )
        assertTrue(RefKind.ADVENTURE_BLOCK.isDatapackTag("#basic:soft") && !RefKind.ADVENTURE_BLOCK.isGame("ruby_ore"))
        // The biome's tags are still all the game's: only a kind with a tag registry has datapack tags.
        assertTrue(RefKind.BIOME.isGame("#basic:wet") && !RefKind.BIOME.isDatapackTag("#basic:wet"))
    }

    @Test
    fun `a hidden tooltip line is one the game draws, and one the target version has`() {
        fun problems(game: GameDataBundle?, vararg lines: String): List<Pair<String?, String?>> {
            val sink = ProblemSink("items/pick/item.json")
            Rules.item(ItemDef(kind = "minecraft:wooden_pickaxe", hiddenTooltipComponents = lines.map(::TooltipComponent)), "$", sink, game)
            return sink.problems.map { it.code to it.path }
        }
        // Every line there is reads, without game data and with a version that has them all.
        val all = TooltipComponent.entries.map { it.key }
        val game = GameDataBundle("26.3", registries = mapOf(RegistryKey.DATA_COMPONENT_TYPE.id to all))
        assertEquals(emptyList(), problems(null, *TooltipComponent.ALL.toTypedArray()))
        assertEquals(emptyList(), problems(game, *TooltipComponent.ALL.toTypedArray()))
        assertEquals(
            listOf(
                "item.tooltip-component" to "$.hiddenTooltipComponents[1]",
                "item.tooltip-component" to "$.hiddenTooltipComponents[2]",
                "item.tooltip-component" to "$.hiddenTooltipComponents[3]"
            ),
            problems(null, "unbreakable", "tool", "painting_variant", "minecraft:unbreakable")
        )
        // An older version without the component: nothing there to hide.
        val older =
            GameDataBundle("1.21.11", registries = mapOf(RegistryKey.DATA_COMPONENT_TYPE.id to all - "minecraft:sulfur_cube_content"))
        assertEquals(
            listOf("item.unknown-tooltip-component" to "$.hiddenTooltipComponents[1]"),
            problems(older, "unbreakable", "sulfur_cube_content")
        )
        val sink = ProblemSink("items/pick/item.json")
        Rules.item(
            ItemDef(kind = "minecraft:paper", hiddenTooltipComponents = listOf(TooltipComponent("painting_variant"))),
            "$",
            sink,
            null
        )
        assertEquals(
            "\"painting_variant\" isn't a tooltip line hiddenTooltipComponents can hide: did you mean \"painting/variant\"?",
            sink.problems.single().message
        )
    }
}
