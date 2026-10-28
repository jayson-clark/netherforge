package dev.netherforge.format.loot

import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.ProblemSink
import dev.netherforge.format.game.GameDataBundle
import dev.netherforge.format.item.ItemDef
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.project.MapProjectSource
import dev.netherforge.format.project.Projects
import dev.netherforge.format.recipe.Ingredient
import dev.netherforge.format.ref.RefKind
import dev.netherforge.format.ref.RefScope
import dev.netherforge.format.ref.RefTarget
import dev.netherforge.format.ref.ReferenceIndex
import dev.netherforge.format.ref.ResourceRef
import dev.netherforge.format.testManifest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LootTest {
    private fun table(vararg pools: Pair<String, LootPool>) = LootTableFile(pools = mapOf(*pools))

    private fun item(kind: String, count: LootRange? = null, weight: Int? = null, quality: Int? = null) =
        ItemEntry(ItemDef(kind = kind), count = count, weight = weight, quality = quality)

    private val noTables = LootRoller { null }

    private fun kinds(drops: List<LootDrop>) = drops.map { (it as LootDrop.Stack).item.kind to it.count }

    private class Context(
        override val luck: Double = 0.0,
        override val hasPlayer: Boolean = false,
        val tool: String? = null,
        val enchantments: Map<String, Int> = emptyMap()
    ) : LootContext {
        override fun toolIs(tool: Ingredient) = tool.kind == this.tool

        override fun enchantmentLevel(enchantment: String) = enchantments[enchantment] ?: 0
    }

    @Test
    fun aRangeIsANumberOrMinAndMaxAndWritesItsShortestForm() {
        val text = """{ "pools": { "a": { "rolls": 2, "entries": [{ "type": "item", "item": { "kind": "minecraft:stick" }, "count": { "min": 3, "max": 3 } }] },
            "b": { "rolls": { "min": 1, "max": 4 }, "entries": [{ "type": "empty" }] } } }"""
        val file = (LootTableKind.parse(text, "loot/x.json") as CanonicalJson.Parsed.Ok).value
        assertEquals(LootRange(2), file.pools.getValue("a").rolls)
        assertEquals(LootRange(1, 4), file.pools.getValue("b").rolls)
        val written = LootTableKind.write(file)
        assertTrue("\"rolls\": 2" in written, written)
        assertTrue("\"count\": 3" in written, written)
        assertTrue("\"rolls\": {\n        \"min\": 1,\n        \"max\": 4\n      }" in written, written)
        for (bad in listOf("1.5", "\"2\"", "{ \"min\": 1 }", "{ \"min\": 1, \"max\": 2, \"step\": 1 }")) {
            val failed = LootTableKind.parse("""{ "pools": { "a": { "rolls": $bad } } }""", "loot/x.json")
            assertTrue(failed is CanonicalJson.Parsed.Failed, bad)
        }
    }

    @Test
    fun theSameSeedGivesTheSameDropsAndPicksFollowTheWeights() {
        val file = table(
            "main" to
                LootPool(rolls = LootRange(1), entries = listOf(item("minecraft:stick", weight = 3), item("minecraft:stone", weight = 1)))
        )
        val first = noTables.roll(file, LootContext.NONE, Random(42))
        assertEquals(first, noTables.roll(file, LootContext.NONE, Random(42)))
        val counts = (0 until 4000).map {
            kinds(noTables.roll(file, LootContext.NONE, Random(it))).single().first
        }.groupingBy { it }.eachCount()
        val sticks = counts.getValue("minecraft:stick")
        assertTrue(sticks in 2800..3200, "about 3 in 4 picks are sticks: $counts")
    }

    @Test
    fun aSeedRollsTheSameOnEveryPlatform() {
        // The same numbers on the JVM and in JS: kotlin.random's XorWow, the roller's only source.
        val file = table(
            "main" to LootPool(
                rolls = LootRange(2, 5),
                entries = listOf(item("minecraft:stick", count = LootRange(1, 9), weight = 2), item("minecraft:stone"), EmptyEntry())
            )
        )
        assertEquals(
            listOf("minecraft:stick" to 7, "minecraft:stick" to 9, "minecraft:stone" to 1),
            kinds(noTables.roll(file, LootContext.NONE, Random(7)))
        )
    }

    @Test
    fun rollsCountsAndLuckFollowVanillasRules() {
        val file = table(
            "main" to LootPool(
                rolls = LootRange(2),
                bonusRolls = 1.0,
                entries = listOf(item("minecraft:stick", count = LootRange(5)))
            )
        )
        assertEquals(2, noTables.roll(file, LootContext.NONE, Random(1)).size)
        assertEquals(5, noTables.roll(file, Context(luck = 3.5), Random(1)).size, "2 rolls, and 3 more for 3.5 luck")
        assertEquals(listOf(5), noTables.roll(file, LootContext.NONE, Random(1)).map { (it as LootDrop.Stack).count }.distinct())
        // Quality moves weight with luck: with enough bad luck an entry can't be picked at all.
        val unlucky =
            table("main" to LootPool(entries = listOf(item("minecraft:stick", weight = 1, quality = -1), item("minecraft:stone"))))
        repeat(20) { assertEquals(listOf("minecraft:stone" to 1), kinds(noTables.roll(unlucky, Context(luck = 1.0), Random(it)))) }
        // A count of 0 gives nothing.
        val none = table("main" to LootPool(entries = listOf(item("minecraft:stick", count = LootRange(0)))))
        assertEquals(emptyList(), noTables.roll(none, LootContext.NONE, Random(1)))
    }

    @Test
    fun conditionsDecideWhatMayBePicked() {
        val file = table(
            "player" to LootPool(conditions = listOf(PlayerCondition()), entries = listOf(item("minecraft:diamond"))),
            "tool" to LootPool(
                entries = listOf(
                    ItemEntry(
                        ItemDef(kind = "minecraft:emerald"),
                        conditions = listOf(ToolCondition(Ingredient(kind = "minecraft:shears")))
                    ),
                    ItemEntry(
                        ItemDef(kind = "minecraft:string"),
                        conditions = listOf(ToolCondition(Ingredient(kind = "minecraft:shears"), invert = true))
                    )
                )
            ),
            "touch" to LootPool(
                entries = listOf(
                    ItemEntry(ItemDef(kind = "minecraft:glass"), conditions = listOf(EnchantmentCondition("silk_touch", level = 2)))
                )
            )
        )
        assertEquals(listOf("minecraft:string" to 1), kinds(noTables.roll(file, LootContext.NONE, Random(1))))
        assertEquals(
            listOf("minecraft:diamond" to 1, "minecraft:emerald" to 1, "minecraft:glass" to 1),
            kinds(
                noTables.roll(
                    file,
                    Context(hasPlayer = true, tool = "minecraft:shears", enchantments = mapOf("minecraft:silk_touch" to 2)),
                    Random(1)
                )
            )
        )
        // A level below the condition's doesn't pass.
        assertEquals(
            listOf("minecraft:string" to 1),
            kinds(noTables.roll(file, Context(enchantments = mapOf("minecraft:silk_touch" to 1)), Random(1)))
        )
        // A chance passes about as often as it says.
        val chance = table("main" to LootPool(conditions = listOf(ChanceCondition(0.25)), entries = listOf(item("minecraft:stick"))))
        val hits = (0 until 4000).count { noTables.roll(chance, LootContext.NONE, Random(it)).isNotEmpty() }
        assertTrue(hits in 850..1150, "about 1 in 4 rolls: $hits")
    }

    @Test
    fun tableEntriesRollTheirTableAndGameTablesAreHandedBack() {
        val junk = table("main" to LootPool(entries = listOf(item("minecraft:stick"))))
        val file = table(
            "a" to LootPool(entries = listOf(TableEntry(ResourceRef("junk")))),
            "b" to LootPool(entries = listOf(VanillaEntry("minecraft:chests/simple_dungeon")))
        )
        val roller = LootRoller { if (it.text == "junk") junk else null }
        val drops = roller.roll(file, LootContext.NONE, Random(3))
        assertEquals(LootDrop.Stack(ItemDef(kind = "minecraft:stick"), 1), drops[0])
        val vanilla = drops[1] as LootDrop.Vanilla
        assertEquals("minecraft:chests/simple_dungeon", vanilla.table)
        assertEquals(vanilla, roller.roll(file, LootContext.NONE, Random(3))[1], "the game's roll gets a seed from the same draws")
        assertFailsWith<IllegalStateException> { noTables.roll(file, LootContext.NONE, Random(3)) }
        // A cycle the project couldn't see stops instead of rolling forever.
        lateinit var loop: LootTableFile
        loop = table("main" to LootPool(entries = listOf(TableEntry(ResourceRef("loop")))))
        assertFailsWith<IllegalStateException> { LootRoller { loop }.roll(loop, LootContext.NONE, Random(1)) }
    }

    @Test
    fun gameIdsAreCheckedAgainstTheGamesData() {
        val game = GameDataBundle(
            minecraft = "26.3",
            registries = mapOf(
                "minecraft:item" to listOf("minecraft:stick"),
                "minecraft:loot_table" to listOf("minecraft:chests/simple_dungeon"),
                "minecraft:enchantment" to listOf("minecraft:fortune")
            )
        )
        val file = table(
            "main" to LootPool(
                entries = listOf(
                    VanillaEntry("minecraft:chests/simple_dungeon"),
                    VanillaEntry("minecraft:chests/nowhere"),
                    ItemEntry(
                        ItemDef(kind = "minecraft:stick"),
                        conditions = listOf(EnchantmentCondition("minecraft:fortune"), EnchantmentCondition("minecraft:unbreaking"))
                    ),
                    ItemEntry(ItemDef(kind = "minecraft:stick"), conditions = listOf(ToolCondition(Ingredient(kind = "minecraft:shears"))))
                )
            )
        )
        fun codes(game: GameDataBundle?) = ProblemSink("loot/x.json").also { LootValidator.validate(file, it, game) }.problems.map {
            it.code to it.path
        }
        assertEquals(emptyList(), codes(null), "without game data, only shapes are checked")
        assertEquals(
            listOf(
                "loot.unknown-vanilla" to "$.pools.main.entries[1].table",
                "loot.unknown-enchantment" to "$.pools.main.entries[2].conditions[1].enchantment",
                "loot.unknown-tool" to "$.pools.main.entries[3].conditions[0].tool"
            ),
            codes(game)
        )
    }

    @Test
    fun aLootTableIsAReferenceLikeAnyResource() {
        val files = mapOf(
            "netherforge.json" to testManifest(),
            "loot/junk.json" to """{ "pools": { "main": { "entries": [{ "type": "empty" }] } } }""",
            "loot/treasure.json" to
                """{ "pools": { "main": { "entries": [{ "type": "table", "table": "junk" }, { "type": "table", "table": "test:junk" }] } } }"""
        )
        val snapshot = Projects.load(MapProjectSource(files))
        assertEquals(emptyList(), snapshot.problems)
        val usages = snapshot.references.usagesOf(RefTarget.Resource("loot_table", "junk"))
        assertEquals(listOf("$.pools.main.entries[0].table", "$.pools.main.entries[1].table"), usages.map { it.path })
        val renamed = ReferenceIndex.rename(
            LootTableKind,
            "loot/treasure.json",
            files.getValue("loot/treasure.json"),
            "test",
            RefTarget.Resource("loot_table", "junk"),
            "scraps"
        )!!
        assertTrue("\"table\": \"scraps\"" in renamed && "junk" !in renamed, renamed)
    }

    @Test
    fun everyResourceReferenceHasItsMissingCode() {
        for (kind in RefKind.entries.filter { it.scope == RefScope.RESOURCE }) {
            assertEquals("reference.${kind.resourceKind!!.replace('_', '-')}", ProblemCodes.missing(kind).code)
        }
    }
}
