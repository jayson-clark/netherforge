package dev.netherforge.format.terrain

import dev.netherforge.format.datapack.DatapackEntry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * How a container a terrain generates gets its loot ([Decoration.loot], a script's `chunk:set_loot`): the server marks
 * it with the project's table and gives it the game's own loot table [KEY], an empty one the start-up datapack holds,
 * so the game unpacks it as it does its own chests: the first time anyone opens it, breaks it or a hopper takes from
 * it. The plugin hears the game roll [KEY] and puts one roll of the project's table there instead.
 */
object GeneratedLoot {
    /** The game's loot table generated containers are given. */
    const val KEY = "netherforge:generated_container"

    /** Where the start-up datapack holds it. */
    const val DATAPACK_PATH = "data/netherforge/loot_table/generated_container.json"

    /** The table: nothing in it. The game's chest context, so it's rolled as a chest's would be. */
    val entry: DatapackEntry = DatapackEntry.json(
        buildJsonObject {
            put("type", "minecraft:chest")
            put("pools", JsonArray(emptyList()))
        }
    )
}
