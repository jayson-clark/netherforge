package dev.netherforge.format.block

import dev.netherforge.format.game.BlockState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * `client/vanilla.json`: what the built pack needs of the game's own assets, which only the client has, so the editor
 * writes it from the player's own Minecraft (the import), as it writes `fonts/default.json`, whenever the project
 * needs it; it's committed and deployed with the project.
 *
 * - [blockstates]: the blockstate files of the blocks the project's blocks are held in, where drawing that block's
 *   other states as the game does takes more than one model (tripwire draws a model per shape). The built pack
 *   replaces the carrier's blockstate file, so it must say how every state looks: its blocks' states as their looks,
 *   the rest as this says.
 * - [sprites]: the GUI sprites of the game's HUD (`hud/heart/full`), which a resource pack's `hud.hide` blanks and
 *   `hud.replace` redraws.
 * - [textures]: the game's textures a resource pack's `textures` redraws, with their sizes: every container screen's
 *   (`gui/container/`), and every other one the project's resource packs name (`block/stone`, `entity/zombie/zombie`).
 *   The game has thousands, so the file holds only those asked about, and [unknownTextures] the names asked about that
 *   the game doesn't have: a name in neither is one the editor hasn't looked up yet.
 * - [models]: the game's models a resource pack's `models` redraws, as [textures] are listed: only those asked about,
 *   and [unknownModels] the names the game doesn't have.
 */
@Serializable
data class VanillaFile(
    @SerialName("\$schema") val schema: String? = null,
    /** The Minecraft version it was read from. */
    val minecraft: String,
    /** Each block's blockstate file as the client has it, by block id (`minecraft:tripwire`). */
    val blockstates: Map<String, JsonElement> = emptyMap(),
    /** The game's HUD sprites, by their path under `textures/gui/sprites/` without `.png` (`hud/heart/full`), sorted. */
    val sprites: List<String> = emptyList(),
    /**
     * The game's textures (`gui/container/inventory`, `block/stone`), by their path under `textures/` without `.png`, to
     * their pixel size `[width, height]`: every container screen's, and the others the project's resource packs redraw.
     */
    val textures: Map<String, List<Int>> = emptyMap(),
    /** Names the project's resource packs redraw that the game has no texture by, sorted. */
    val unknownTextures: List<String> = emptyList(),
    /** The game's models the project's resource packs redraw (`block/red_mushroom`), by their path under `models/` without `.json`, sorted. */
    val models: List<String> = emptyList(),
    /** Names the project's resource packs redraw that the game has no model by, sorted. */
    val unknownModels: List<String> = emptyList()
) {
    companion object {
        const val FILE = "client/vanilla.json"
        const val SCHEMA = "../.netherforge/schema/vanilla.schema.json"
    }
}

/** What a [VanillaFile] says, read as the game reads a blockstate file. */
object VanillaBlockStates {
    /** The blocks [plan] holds blocks in whose other states only the client's file can draw: what the file must have. */
    fun needed(plan: BlockCarriers.Plan): Set<String> = plan.uses.map { it.state.id }.filter { id ->
        plan.pools.values.any { pool -> id in pool.blocks && pool.vanillaModel(id) == null }
    }.sorted().toSet()

    /**
     * The variant the client's blockstate [file] draws [state] with (its model and rotations, or a list of them
     * picked from at random), as the game picks it: the last `variants` entry whose key names only properties
     * [state] has, with those values (`""` matches every state). Null when no entry does, or the file isn't
     * `variants` (a `multipart` one draws parts, not one variant).
     */
    fun variant(file: JsonElement, state: BlockState): JsonElement? {
        val variants = (file as? JsonObject)?.get("variants") as? JsonObject ?: return null
        var found: JsonElement? = null
        for ((key, value) in variants) {
            if (matches(key, state) && (value is JsonObject || value is JsonArray)) found = value
        }
        return found
    }

    /** Whether a `variants` key (`attached=false,east=true`) holds for [state]. */
    private fun matches(key: String, state: BlockState): Boolean {
        if (key.isEmpty()) return true
        return key.split(',').all { condition ->
            val name = condition.substringBefore('=')
            val value = condition.substringAfter('=', "")
            state.properties[name] == value
        }
    }
}
