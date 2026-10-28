package dev.netherforge.plugin.item

import dev.netherforge.format.game.GameIds
import dev.netherforge.format.game.RegistryKey
import dev.netherforge.format.math.Vec3
import dev.netherforge.format.recipe.Ingredient
import dev.netherforge.format.recipe.Material
import dev.netherforge.format.recipe.RecipeFile
import dev.netherforge.plugin.block.CustomBlocks
import dev.netherforge.plugin.platform.InventoryRef
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.Platform
import java.util.UUID
import kotlin.math.floor

/**
 * `station` recipes made by scripts (`nf.recipes.available`, `craft`): which stations stand near a place (one
 * box of blocks read round it), how many times a player's inventory holds a recipe's materials, and making it.
 */
internal class StationCrafting(
    private val platform: Platform,
    private val items: Items,
    private val customBlocks: CustomBlocks,
    /** A name a recipe's `stations` or a script gives, in the project's terms (a package's block in full). */
    private val blockName: (String) -> String
) {
    /** What stands near a place: block ids and project blocks, each with how far (blocks, on the furthest axis). */
    class Near(private val distances: Map<String, Int>, private val extra: Set<String>) {
        fun within(name: String, range: Int) = name in extra || (distances[name] ?: Int.MAX_VALUE) <= range

        fun ids(): Set<String> = distances.keys
    }

    /** The stations within [range] of [center] in [world], plus [extra] counted as near whatever stands there. */
    fun near(world: String, center: Vec3, range: Int, extra: Collection<String>): Near {
        val cx = floor(center.x).toInt()
        val cy = floor(center.y).toInt()
        val cz = floor(center.z).toInt()
        val distances = HashMap<String, Int>()
        val heights = platform.worlds.heights(world)
        val y0 = maxOf(cy - range, heights?.first ?: (cy - range))
        val y1 = minOf(cy + range, (heights?.second ?: (cy + range + 1)) - 1)
        // Each chunk column on its own: a box over an unloaded chunk reads as nothing.
        for (chunkX in Math.floorDiv(cx - range, 16)..Math.floorDiv(cx + range, 16)) {
            for (chunkZ in Math.floorDiv(cz - range, 16)..Math.floorDiv(cz + range, 16)) {
                val x0 = maxOf(cx - range, chunkX * 16)
                val x1 = minOf(cx + range, chunkX * 16 + 15)
                val z0 = maxOf(cz - range, chunkZ * 16)
                val z1 = minOf(cz + range, chunkZ * 16 + 15)
                if (y1 < y0) continue
                val read = platform.blocks.box(world, x0, y0, z0, x1, y1, z1) ?: continue
                val sizeX = x1 - x0 + 1
                val sizeZ = z1 - z0 + 1
                for ((i, index) in read.cells.withIndex()) {
                    val state = read.states[index]
                    if (state.state.startsWith("minecraft:air") || state.state.startsWith("minecraft:cave_air")) continue
                    val x = x0 + i % sizeX
                    val z = z0 + (i / sizeX) % sizeZ
                    val y = y0 + i / (sizeX * sizeZ)
                    val distance = maxOf(kotlin.math.abs(x - cx), kotlin.math.abs(y - cy), kotlin.math.abs(z - cz))
                    val id = state.state.substringBefore('[')
                    distances.merge(id, distance, ::minOf)
                    customBlocks.nameOfState(state.state)?.let { distances.merge(it, distance, ::minOf) }
                }
            }
        }
        return Near(distances, extra.map(blockName).toSet())
    }

    /** Whether every station [recipe] needs is near. */
    fun stationsNear(recipe: RecipeFile, near: Near): Boolean {
        val range = recipe.stationRange ?: Material.DEFAULT_STATION_RANGE
        // An entry with `|` is near when any of its choices is.
        return recipe.stations.orEmpty().all { entry -> entry.split('|').any { stationNear(it.trim(), near, range) } }
    }

    /** Whether one station (an id, a `#tag` or a project block) is within [range] of [near]. */
    private fun stationNear(station: String, near: Near, range: Int): Boolean = if (station.startsWith("#")) {
        val tag = station.removePrefix("#")
        val members = if (GameIds.isValid(tag)) platform.game.tag(RegistryKey.BLOCK, GameIds.normalize(tag)).orEmpty() else emptySet()
        members.any { near.within(it, range) } || near.within(station, range)
    } else {
        near.within(blockName(station), range) || (GameIds.isValid(station) && near.within(GameIds.normalize(station), range))
    }

    /** The largest station range any of [recipes] asks for: how far round a place [near] must look. */
    fun range(recipes: Collection<RecipeFile>): Int = recipes.maxOfOrNull { it.stationRange ?: Material.DEFAULT_STATION_RANGE } ?: 0

    /** Whether [stack] is what [ingredient] takes: a project item's own stacks only for it, a kind or tag never a project item's. */
    fun matches(ingredient: Ingredient, stack: ItemData): Boolean {
        val project = stack.def.item?.let { items.idOf(it.text) }
        ingredient.item?.let { return project != null && project == items.idOf(it.text) }
        if (project != null) return false
        val kind = GameIds.normalize(stack.def.kind)
        ingredient.tag?.let { tag -> return kind in platform.game.tag(RegistryKey.ITEM, GameIds.normalize(tag)).orEmpty() }
        return ingredient.kind?.let { GameIds.normalize(it) == kind } == true
    }

    /** How many times over [player]'s inventory holds [recipe]'s materials. */
    fun times(recipe: RecipeFile, player: UUID): Int {
        val slots = platform.inventories.contents(InventoryRef.Player(player)) ?: return 0
        var times = Int.MAX_VALUE
        for (material in recipe.materials.orEmpty()) {
            val held = slots.sumOf { stack -> if (stack != null && matches(material.ingredient, stack)) stack.def.count ?: 1 else 0 }
            times = minOf(times, held / material.countOrDefault)
        }
        return if (times == Int.MAX_VALUE) 0 else times
    }

    /** Makes [recipe] [times] over for [player]: takes its materials, gives its result (what doesn't fit drops at their feet). */
    /** Makes [recipe] [times] over for [player]; [each], when given, is what each making gives, from the result. */
    fun make(recipe: RecipeFile, player: UUID, times: Int, each: ((ItemData) -> ItemData)? = null) {
        val ref = InventoryRef.Player(player)
        val slots = platform.inventories.contents(ref)?.toMutableList() ?: return
        for (material in recipe.materials.orEmpty()) {
            var owed = material.countOrDefault * times
            for ((slot, stack) in slots.withIndex()) {
                if (owed <= 0) break
                if (stack == null || !matches(material.ingredient, stack)) continue
                val count = stack.def.count ?: 1
                val taken = minOf(count, owed)
                owed -= taken
                val left = if (taken == count) null else stack.copy(def = stack.def.copy(count = count - taken))
                slots[slot] = left
                platform.inventories.setItem(ref, slot, left)
            }
        }
        val one = result(recipe)
        if (each != null) {
            repeat(times) { give(ref, player, each(one)) }
            return
        }
        val perStack = one.def.count ?: 1
        var left = perStack * times
        val most = 64
        while (left > 0) {
            val n = minOf(left, most)
            left -= n
            give(ref, player, one.copy(def = one.def.copy(count = n)))
        }
    }

    /** Puts [stack] in [player]'s inventory, dropping what doesn't fit at their feet. */
    private fun give(ref: InventoryRef, player: UUID, stack: ItemData) {
        val count = stack.def.count ?: 1
        val over = platform.inventories.add(ref, stack) ?: count
        if (over > 0) {
            val at = platform.players.location(player) ?: return
            platform.worldEntities.spawnItem(at.world, Vec3(at.x, at.y, at.z), stack.copy(def = stack.def.copy(count = over)))
        }
    }

    /** One making's result: a project item's built from its look (and rendered), anything else as the file says. */
    fun result(recipe: RecipeFile): ItemData {
        val result = recipe.result
        val project = result.item?.let { items.idOf(it.text) } ?: return ItemData(result.copy(count = result.count ?: 1))
        return items.create(project, result)
    }
}
