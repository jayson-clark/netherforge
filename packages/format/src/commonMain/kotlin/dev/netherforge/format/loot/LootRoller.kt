package dev.netherforge.format.loot

import dev.netherforge.format.game.GameIds
import dev.netherforge.format.item.ItemDef
import dev.netherforge.format.recipe.Ingredient
import dev.netherforge.format.ref.ResourceRef
import kotlin.math.floor
import kotlin.random.Random

/**
 * What a roll knows about why it's rolled, for its tables' conditions: the
 * server answers it from a script's roll context, the editor's preview with
 * nothing ([NONE]).
 */
interface LootContext {
    /** The roll's luck: more [LootPool.bonusRolls], and each entry's weight shifted by its [LootEntry.quality]. */
    val luck: Double

    /** Whether a player is behind the roll ([PlayerCondition]). */
    val hasPlayer: Boolean

    /** Whether the roll's tool is [tool] ([ToolCondition]); false when it has none. */
    fun toolIs(tool: Ingredient): Boolean

    /** The level of [enchantment] (namespaced) on the roll's tool, 0 when it has none ([EnchantmentCondition]). */
    fun enchantmentLevel(enchantment: String): Int

    companion object {
        /** No luck, no player, no tool. */
        val NONE: LootContext = object : LootContext {
            override val luck get() = 0.0
            override val hasPlayer get() = false

            override fun toolIs(tool: Ingredient) = false

            override fun enchantmentLevel(enchantment: String) = 0
        }
    }
}

/** One thing a roll gives, in the order it was picked. */
sealed interface LootDrop {
    /** [count] of [item] ([item] has no count of its own), however many stacks that makes. */
    data class Stack(val item: ItemDef, val count: Int) : LootDrop

    /** One of the game's loot tables, to roll on the server with [seed] (so it's as deterministic as the rest). */
    data class Vanilla(val table: String, val seed: Long) : LootDrop
}

/**
 * Rolls loot tables: the one place their meaning is written down, shared by
 * the server (which rolls what scripts ask for) and the editor. Everything
 * random comes from the [Random] a roll is given, so a seed gives the same
 * drops on the JVM and in JS.
 *
 * It follows vanilla's rules: each pool, if its conditions pass, makes
 * `rolls + floor(bonusRolls * luck)` picks; each pick weighs the entries
 * whose conditions pass by `floor(weight + quality * luck)` (an entry at 0 or
 * below can't be picked), and takes the only one without a draw. A table
 * entry rolls that table whole, with the same random; a game loot table is
 * handed back as a [LootDrop.Vanilla] for the server to roll.
 *
 * [table] finds the loot table a [TableEntry] names, as it's written in the
 * table being rolled.
 */
class LootRoller(private val table: (ResourceRef) -> LootTableFile?) {
    /** What one roll of [file] gives, in order. A table that names one [table] can't find is an [IllegalStateException]. */
    fun roll(file: LootTableFile, context: LootContext, random: Random): List<LootDrop> {
        val roll = Roll(context, random)
        roll.table(file, 0)
        return roll.drops
    }

    private inner class Roll(val context: LootContext, val random: Random) {
        val drops = mutableListOf<LootDrop>()
        private var picks = 0

        fun table(file: LootTableFile, depth: Int) {
            check(depth <= MOST_DEPTH) { "loot tables include each other more than $MOST_DEPTH deep" }
            for ((_, pool) in file.pools.entries.sortedBy { it.key }) pool(pool, depth)
        }

        private fun pool(pool: LootPool, depth: Int) {
            if (!passes(pool.conditions)) return
            val rolls = (pool.rolls ?: ONE).roll() + floor((pool.bonusRolls ?: 0.0) * context.luck).toInt()
            repeat(rolls) { pick(pool.entries, depth) }
        }

        private fun pick(entries: List<LootEntry>, depth: Int) {
            picks++
            check(picks <= MOST_PICKS) { "a roll made more than $MOST_PICKS picks" }
            val weighed = entries.mapNotNull { entry ->
                if (!passes(entry.conditions)) return@mapNotNull null
                val weight = floor((entry.weight ?: 1) + (entry.quality ?: 0) * context.luck).toLong()
                if (weight > 0) entry to weight else null
            }
            val chosen = when (weighed.size) {
                0 -> return
                1 -> weighed[0].first
                else -> {
                    var left = random.nextLong(weighed.sumOf { it.second })
                    weighed.first { (_, weight) -> (left < weight).also { left -= weight } }.first
                }
            }
            give(chosen, depth)
        }

        private fun give(entry: LootEntry, depth: Int) {
            when (entry) {
                is ItemEntry -> {
                    val count = (entry.count ?: ONE).roll()
                    if (count > 0) drops += LootDrop.Stack(entry.item, count)
                }
                is TableEntry -> {
                    val next = table(entry.table) ?: throw IllegalStateException("there's no loot table \"${entry.table}\"")
                    table(next, depth + 1)
                }
                is VanillaEntry -> drops += LootDrop.Vanilla(entry.table, random.nextLong())
                is EmptyEntry -> Unit
            }
        }

        private fun passes(conditions: List<LootCondition>?): Boolean = conditions.orEmpty().all { condition ->
            val holds = when (condition) {
                is ChanceCondition -> random.nextDouble() < condition.chance
                is PlayerCondition -> context.hasPlayer
                is ToolCondition -> context.toolIs(condition.tool)
                is EnchantmentCondition -> context.enchantmentLevel(GameIds.normalize(condition.enchantment)) >= (condition.level ?: 1)
            }
            holds != (condition.invert == true)
        }

        private fun LootRange.roll(): Int = if (min >= max) min else random.nextInt(min, max + 1)
    }

    companion object {
        /** How deep tables may include each other in one roll: a cycle the project couldn't see (it's checked) stops here. */
        const val MOST_DEPTH = 16

        /** The most picks one roll makes, across every table it includes. */
        const val MOST_PICKS = 10_000

        private val ONE = LootRange(1)
    }
}
