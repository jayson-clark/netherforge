package dev.netherforge.format.loot

import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.ProblemSink
import dev.netherforge.format.game.GameData
import dev.netherforge.format.game.GameIds
import dev.netherforge.format.game.RegistryKey
import dev.netherforge.format.game.has
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.project.Names
import dev.netherforge.format.validate.Rules

/**
 * Checks a loot table on its own: pool names, ranges, weights, every item's
 * stack, the game's loot tables and enchantments it names (with game data)
 * and every condition's values. That a project item or loot table it names
 * exists is the project's to check, as for every reference.
 */
object LootValidator {
    /** The most picks one pool makes in a roll (its `rolls`' max). */
    const val MOST_ROLLS = 100

    /** The most items one entry gives in a pick (its `count`'s max). */
    const val MOST_COUNT = 1000

    fun validate(file: LootTableFile, sink: ProblemSink, game: GameData?) {
        for ((name, pool) in file.pools) {
            val at = CanonicalJson.childPath("$.pools", name)
            if (!Names.isId(name)) sink.report(ProblemCodes.LOOT_POOL_NAME, "\"$name\" isn't a usable pool name (${Names.ID_RULE})", at)
            pool.rolls?.let { range(it, "$at.rolls", MOST_ROLLS, sink) }
            pool.bonusRolls?.let {
                if (!it.isFinite()) sink.report(ProblemCodes.LOOT_RANGE, "bonusRolls must be a number", "$at.bonusRolls")
            }
            conditions(pool.conditions, "$at.conditions", sink, game)
            if (pool.entries.isEmpty()) sink.report(ProblemCodes.LOOT_NO_ENTRIES, "Pool \"$name\" has no entries, so it gives nothing", at)
            pool.entries.forEachIndexed { index, entry -> entry(entry, "$at.entries[$index]", sink, game) }
        }
    }

    /** Every entry in [file], with its JSON path, pools in name order. */
    fun entries(file: LootTableFile): List<Pair<String, LootEntry>> = file.pools.entries.sortedBy { it.key }.flatMap { (name, pool) ->
        pool.entries.mapIndexed { index, entry -> "${CanonicalJson.childPath("$.pools", name)}.entries[$index]" to entry }
    }

    private fun entry(entry: LootEntry, at: String, sink: ProblemSink, game: GameData?) {
        entry.weight?.let { if (it < 1) sink.report(ProblemCodes.LOOT_WEIGHT, "weight must be at least 1", "$at.weight") }
        conditions(entry.conditions, "$at.conditions", sink, game)
        when (entry) {
            is ItemEntry -> {
                Rules.item(entry.item, "$at.item", sink, game)
                if (entry.item.count != null) {
                    sink.report(ProblemCodes.LOOT_ITEM_COUNT, "Say how many with the entry's count, which can be a range", "$at.item.count")
                }
                entry.count?.let { range(it, "$at.count", MOST_COUNT, sink) }
            }
            is VanillaEntry -> if (!GameIds.isValid(entry.table) || ':' !in entry.table) {
                sink.report(
                    ProblemCodes.LOOT_VANILLA,
                    "\"${entry.table}\" isn't a game loot table's id, like \"minecraft:chests/simple_dungeon\"",
                    "$at.table"
                )
            } else if (game?.has(RegistryKey.LOOT_TABLE, entry.table) == false) {
                sink.report(
                    ProblemCodes.LOOT_UNKNOWN_VANILLA,
                    "Minecraft ${game.minecraftVersion} has no loot table \"${entry.table}\"",
                    "$at.table"
                )
            }
            is TableEntry, is EmptyEntry -> Unit
        }
    }

    private fun conditions(conditions: List<LootCondition>?, at: String, sink: ProblemSink, game: GameData?) {
        conditions?.forEachIndexed { index, condition ->
            val path = "$at[$index]"
            when (condition) {
                is ChanceCondition -> if (!(condition.chance in 0.0..1.0)) {
                    sink.report(ProblemCodes.LOOT_CHANCE, "A chance is from 0 to 1", "$path.chance")
                }
                is ToolCondition -> Rules.ingredient(condition.tool, "$path.tool", Rules.LOOT_TOOL, sink, game)
                is EnchantmentCondition -> {
                    val id = condition.enchantment
                    if (!GameIds.isValid(id)) {
                        sink.report(ProblemCodes.LOOT_ENCHANTMENT, "\"$id\" isn't an enchantment id", "$path.enchantment")
                    } else if (game?.has(RegistryKey.ENCHANTMENT, GameIds.normalize(id)) == false) {
                        sink.report(
                            ProblemCodes.LOOT_UNKNOWN_ENCHANTMENT,
                            "Minecraft ${game.minecraftVersion} has no enchantment \"${GameIds.normalize(id)}\"",
                            "$path.enchantment"
                        )
                    }
                    condition.level?.let {
                        if (it <
                            1
                        ) {
                            sink.report(ProblemCodes.LOOT_ENCHANTMENT, "level must be at least 1", "$path.level")
                        }
                    }
                }
                is PlayerCondition -> Unit
            }
        }
    }

    private fun range(range: LootRange, at: String, most: Int, sink: ProblemSink) {
        val message = when {
            range.min < 0 -> "can't be negative"
            range.max < range.min -> "max can't be below min"
            range.max > most -> "can be at most $most"
            else -> return
        }
        sink.report(ProblemCodes.LOOT_RANGE, "${at.substringAfterLast('.')} $message", at)
    }
}
