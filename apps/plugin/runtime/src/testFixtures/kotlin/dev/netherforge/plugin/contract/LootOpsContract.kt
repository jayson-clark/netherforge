package dev.netherforge.plugin.contract

import dev.netherforge.plugin.platform.GameLootContext
import dev.netherforge.plugin.platform.LootOps
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [LootOps]: the game's own loot tables, rolled by the server. */
abstract class LootOpsContract : PlatformContract() {
    private val loot: LootOps get() = platform.loot

    @Test
    fun `the game's tables are there by id, and a table that isn't is null`() {
        main {
            assertTrue(loot.exists(CHEST))
            assertTrue(loot.exists(ZOMBIE))
            assertFalse(loot.exists("minecraft:chests/nf_no_such_table"))
            assertNull(loot.roll("minecraft:chests/nf_no_such_table", GameLootContext(origin), 1))
        }
    }

    @Test
    fun `the same seed rolls the same items`() {
        main {
            val first = loot.roll(CHEST, GameLootContext(origin), 42)!!
            assertTrue(first.isNotEmpty(), "a dungeon chest always has something")
            assertTrue(first.all { it.def.kind.startsWith("minecraft:") && (it.def.count ?: 1) >= 1 }, "$first")
            assertEquals(first, loot.roll(CHEST, GameLootContext(origin), 42))
        }
    }

    @Test
    fun `a table that needs what the roll doesn't say, or a world that isn't there, is refused`() {
        main {
            assertFailsWith<IllegalArgumentException> { loot.roll(ZOMBIE, GameLootContext(origin), 1) }
            assertFailsWith<IllegalArgumentException> { loot.roll(CHEST, GameLootContext(origin.copy(world = MISSING_WORLD)), 1) }
        }
    }

    @Test
    fun `a mob's table rolls with the mob`() {
        val zombie = spawn("minecraft:zombie")
        main { assertTrue(loot.roll(ZOMBIE, GameLootContext(origin, looted = zombie), 7) != null) }
    }

    private companion object {
        const val CHEST = "minecraft:chests/simple_dungeon"
        const val ZOMBIE = "minecraft:entities/zombie"
    }
}
