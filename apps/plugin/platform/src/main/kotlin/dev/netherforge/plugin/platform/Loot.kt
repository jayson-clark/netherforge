package dev.netherforge.plugin.platform

import java.util.UUID

/**
 * Where and for whom the game rolls one of its own loot tables: what a
 * script's roll context says, as the server takes it.
 */
data class GameLootContext(
    /** Where it's rolled: a chest's tables ask where they are (a treasure map's nearest structure). */
    val location: Location,
    val luck: Double = 0.0,
    /** The player behind the roll (who killed, broke or opened it), online. */
    val player: UUID? = null,
    /** The entity whose loot it is: a mob's table needs it. */
    val looted: UUID? = null
)

/**
 * The game's own loot tables (`minecraft:chests/simple_dungeon`, a datapack's),
 * rolled by the server as the game rolls them. The project's own tables are
 * the runtime's (format's `LootRoller`); a table of theirs that names one of
 * the game's hands it here.
 */
interface LootOps {
    /** Whether the server has the loot table [table], a namespaced id. */
    fun exists(table: String): Boolean

    /**
     * What one roll of the game's loot table [table] gives at [context],
     * drawn from a generator seeded with [seed]: the same seed in the same
     * world gives the same items. Null when the server has no such table. A
     * table that needs something [context] doesn't say (a mob's, without
     * [GameLootContext.looted]), or a location in a world that isn't there,
     * is an [IllegalArgumentException] saying so.
     */
    fun roll(table: String, context: GameLootContext, seed: Long): List<ItemData>?
}
