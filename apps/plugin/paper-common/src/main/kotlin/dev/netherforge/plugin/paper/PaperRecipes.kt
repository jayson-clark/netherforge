package dev.netherforge.plugin.paper

import dev.netherforge.format.game.GameIds
import dev.netherforge.format.recipe.Ingredient
import dev.netherforge.format.recipe.RecipeCategory
import dev.netherforge.format.recipe.RecipeFile
import dev.netherforge.format.recipe.RecipeType
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.RecipeOps
import dev.netherforge.plugin.platform.RecipeSpec
import io.papermc.paper.event.player.PlayerStonecutterRecipeSelectEvent
import org.bukkit.Bukkit
import org.bukkit.Keyed
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Tag
import org.bukkit.block.Crafter
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockCookEvent
import org.bukkit.event.block.CrafterCraftEvent
import org.bukkit.event.inventory.PrepareItemCraftEvent
import org.bukkit.event.inventory.PrepareSmithingEvent
import org.bukkit.inventory.BlastingRecipe
import org.bukkit.inventory.CampfireRecipe
import org.bukkit.inventory.CookingRecipe
import org.bukkit.inventory.CraftingRecipe
import org.bukkit.inventory.FurnaceRecipe
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.Recipe
import org.bukkit.inventory.RecipeChoice
import org.bukkit.inventory.ShapedRecipe
import org.bukkit.inventory.ShapelessRecipe
import org.bukkit.inventory.SmithingTransformRecipe
import org.bukkit.inventory.SmokingRecipe
import org.bukkit.inventory.StonecuttingRecipe
import org.bukkit.inventory.recipe.CookingBookCategory
import org.bukkit.inventory.recipe.CraftingBookCategory
import java.util.UUID
import kotlin.math.sqrt

/**
 * [RecipeOps] with Bukkit's recipes, keyed in the project's namespace
 * (`shop:<id>`), and the check
 * that makes a project item in a recipe mean that item.
 *
 * The server matches an ingredient by its item type alone (an `ExactChoice`
 * would compare whole stacks, which a stack's own data, damage or a refreshed
 * look all break), so a project-item ingredient is registered as its kind,
 * and every station's "about to make something" event is checked here first
 * (lowest priority, before scripts hear it): a project-item ingredient must
 * hold a stack carrying that item's id, and a vanilla kind or tag must hold a
 * stack that's no project item's, in the project's recipes and Minecraft's
 * own alike. A crafting grid's result is emptied; a crafter, furnace or
 * campfire doesn't make it; a smithing table shows no result; a stonecutter
 * won't select the recipe.
 *
 * What this can't do: a project recipe whose kinds and shape are those of a
 * vanilla recipe can shadow it (the server picks one recipe for a grid, and
 * when the check refuses that one, the other isn't tried).
 */
class PaperRecipes(private val version: PaperVersion) :
    RecipeOps,
    Listener {
    /** The project's recipes the server has, by id. */
    private val specs = HashMap<String, RecipeFile>()

    /** The tick [keepAdvancements] last wrote the players' advancements in. */
    private var savedTick = -1

    /**
     * Changing the server's recipes makes the game reload every online player's
     * advancements from their file (recipes unlock through advancements), which
     * would take back what they've done since it was last written: write it
     * first, once per tick (a reload changes many recipes in one).
     */
    private fun keepAdvancements() {
        if (savedTick == Bukkit.getCurrentTick()) return
        savedTick = Bukkit.getCurrentTick()
        for (player in Bukkit.getOnlinePlayers()) version.saveAdvancements(player)
    }

    override fun add(recipe: RecipeSpec): Boolean {
        keepAdvancements()
        val key = key(recipe.id)
        Bukkit.removeRecipe(key, false)
        specs.remove(recipe.id)
        val built = runCatching { build(key, recipe.file) }.getOrNull() ?: return false
        if (!runCatching { Bukkit.addRecipe(built, false) }.getOrDefault(false)) return false
        specs[recipe.id] = recipe.file
        return true
    }

    override fun remove(id: String): Boolean {
        keepAdvancements()
        specs.remove(id)
        return Bukkit.removeRecipe(key(id), false)
    }

    override fun resend() {
        keepAdvancements()
        Bukkit.updateRecipes()
    }

    override fun discover(player: UUID, recipe: String): Boolean {
        val key = keyOf(recipe) ?: return false
        if (Bukkit.getRecipe(key) == null) return false
        return Bukkit.getPlayer(player)?.discoverRecipe(key) ?: false
    }

    override fun undiscover(player: UUID, recipe: String): Boolean {
        val key = keyOf(recipe) ?: return false
        return Bukkit.getPlayer(player)?.undiscoverRecipe(key) ?: false
    }

    override fun hasDiscovered(player: UUID, recipe: String): Boolean {
        val key = keyOf(recipe) ?: return false
        return Bukkit.getPlayer(player)?.hasDiscoveredRecipe(key) ?: false
    }

    // ---- building -----------------------------------------------------------------

    private fun build(key: NamespacedKey, file: RecipeFile): Recipe? {
        val result = PaperItems.toStack(ItemData(file.result)) ?: return null
        val experience = (file.experience ?: 0.0).toFloat()
        val time = file.cookingTime ?: file.type.defaultCookingTime
        val recipe: Recipe = when (file.type) {
            RecipeType.SHAPED -> ShapedRecipe(key, result).apply {
                shape(*requireNotNull(file.pattern).toTypedArray())
                for ((symbol, ingredient) in requireNotNull(file.key)) setIngredient(symbol[0], choice(ingredient) ?: return null)
            }
            RecipeType.SHAPELESS -> ShapelessRecipe(key, result).apply {
                for (ingredient in requireNotNull(file.ingredients)) addIngredient(choice(ingredient) ?: return null)
            }
            RecipeType.FURNACE -> FurnaceRecipe(key, result, choice(file.ingredient) ?: return null, experience, time)
            RecipeType.BLASTING -> BlastingRecipe(key, result, choice(file.ingredient) ?: return null, experience, time)
            RecipeType.SMOKING -> SmokingRecipe(key, result, choice(file.ingredient) ?: return null, experience, time)
            RecipeType.CAMPFIRE_COOKING -> CampfireRecipe(key, result, choice(file.ingredient) ?: return null, experience, time)
            // Scripts make these (`nf.recipes.craft`); the runtime never hands the game one.
            RecipeType.STATION -> return null
            RecipeType.SMITHING_TRANSFORM -> SmithingTransformRecipe(
                key,
                result,
                choice(file.template) ?: return null,
                choice(file.base) ?: return null,
                choice(file.addition) ?: return null
            )
            RecipeType.STONECUTTING -> StonecuttingRecipe(key, result, choice(file.ingredient) ?: return null)
        }
        when (recipe) {
            is CraftingRecipe -> {
                file.group?.let { recipe.group = it }
                file.category?.let { recipe.category = craftingCategory(it) }
            }
            is CookingRecipe<*> -> {
                file.group?.let { recipe.group = it }
                file.category?.let { recipe.category = cookingCategory(it) }
            }
            is StonecuttingRecipe -> file.group?.let { recipe.group = it }
        }
        return recipe
    }

    /** What the server matches for [ingredient]: its item type, a tag's types, or a project item's kind. */
    private fun choice(ingredient: Ingredient?): RecipeChoice? {
        ingredient ?: return null
        val item = ingredient.item
        val tag = ingredient.tag
        return when {
            item != null -> PaperItems.looks(item.text)?.def?.kind?.let(::material)?.let { RecipeChoice.MaterialChoice(it) }
            tag != null -> itemTag(tag)?.let { RecipeChoice.MaterialChoice(it) }
            else -> ingredient.kind?.let(::material)?.let { RecipeChoice.MaterialChoice(it) }
        }
    }

    private fun craftingCategory(category: RecipeCategory) = when (category) {
        RecipeCategory.BUILDING -> CraftingBookCategory.BUILDING
        RecipeCategory.REDSTONE -> CraftingBookCategory.REDSTONE
        RecipeCategory.EQUIPMENT -> CraftingBookCategory.EQUIPMENT
        else -> CraftingBookCategory.MISC
    }

    private fun cookingCategory(category: RecipeCategory) = when (category) {
        RecipeCategory.FOOD -> CookingBookCategory.FOOD
        RecipeCategory.BLOCKS -> CookingBookCategory.BLOCKS
        else -> CookingBookCategory.MISC
    }

    // ---- matching project items ------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST)
    fun onPrepareCraft(event: PrepareItemCraftEvent) {
        if (!crafts(event.recipe, event.inventory.matrix)) event.inventory.result = null
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onCrafter(event: CrafterCraftEvent) {
        val crafter = event.block.getState(false) as? Crafter ?: return
        if (!crafts(event.recipe, crafter.inventory.contents)) event.isCancelled = true
    }

    /** Furnaces, blast furnaces, smokers and campfires (a furnace's smelt is a cook too). */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onCook(event: BlockCookEvent) {
        val ours = ours(event.recipe)
        val fits = if (ours != null) ours.ingredient?.let { fits(it, event.source) } == true else !isProjectItem(event.source)
        if (!fits) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onPrepareSmithing(event: PrepareSmithingEvent) {
        val inventory = event.inventory
        val inputs = listOf(inventory.inputTemplate, inventory.inputEquipment, inventory.inputMineral)
        val ours = ours(inventory.recipe)
        val fits = if (ours != null) {
            listOf(ours.template, ours.base, ours.addition).zip(inputs).all { (ingredient, stack) ->
                ingredient != null &&
                    fits(ingredient, stack)
            }
        } else {
            inputs.none(::isProjectItem)
        }
        if (!fits) event.result = null
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onStonecutterSelect(event: PlayerStonecutterRecipeSelectEvent) {
        val input = event.stonecutterInventory.inputItem
        val ours = ours(event.stonecuttingRecipe)
        val fits = if (ours != null) ours.ingredient?.let { fits(it, input) } == true else !isProjectItem(input)
        if (!fits) event.isCancelled = true
    }

    /** The project recipe [recipe] is, or null for one of Minecraft's (or another plugin's). */
    private fun ours(recipe: Recipe?): RecipeFile? = (recipe as? Keyed)?.key?.let { specs[idOf(it)] }

    /** Whether a grid ([matrix], row by row) may make [recipe]. */
    private fun crafts(recipe: Recipe?, matrix: Array<ItemStack?>): Boolean {
        val ours = ours(recipe) ?: return matrix.none(::isProjectItem)
        val stacks = matrix.map { it?.takeUnless(ItemStack::isEmpty) }
        return when (ours.type) {
            RecipeType.SHAPED -> shapedFits(ours, stacks, sqrt(matrix.size.toDouble()).toInt())
            RecipeType.SHAPELESS -> shapelessFits(requireNotNull(ours.ingredients), stacks.filterNotNull())
            else -> false
        }
    }

    /**
     * Whether [stacks] (a grid [width] wide) hold the pattern, as it is or
     * mirrored, wherever it sits: the server already matched item types, so
     * this is about which stack each place holds.
     */
    private fun shapedFits(file: RecipeFile, stacks: List<ItemStack?>, width: Int): Boolean {
        val pattern = trimmed(requireNotNull(file.pattern))
        val key = requireNotNull(file.key)
        val filled = stacks.indices.filter { stacks[it] != null }
        if (pattern.isEmpty() || filled.isEmpty()) return false
        val top = filled.minOf { it / width }
        val left = filled.minOf { it % width }
        val height = pattern.size
        val across = pattern[0].length
        if (filled.maxOf { it / width } - top + 1 != height || filled.maxOf { it % width } - left + 1 != across) return false
        fun aligned(mirrored: Boolean) = (0 until height).all { row ->
            (0 until across).all { column ->
                val symbol = pattern[row][if (mirrored) across - 1 - column else column]
                val stack = stacks[(top + row) * width + left + column]
                if (symbol == ' ') stack == null else key[symbol.toString()]?.let { fits(it, stack) } == true
            }
        }
        return aligned(false) || aligned(true)
    }

    /** [pattern] without its empty rows and columns at the edges, as the server reads a shape. */
    private fun trimmed(pattern: List<String>): List<String> {
        val rows = pattern.filter { it.isNotBlank() }
        if (rows.isEmpty()) return rows
        val first = rows.minOf { row -> row.indexOfFirst { it != ' ' }.takeIf { it >= 0 } ?: row.length }
        val last = rows.maxOf { row -> row.indexOfLast { it != ' ' } }
        // Rows inside the pattern stay, blank or not: only the edges go.
        val start = pattern.indexOfFirst { it.isNotBlank() }
        val end = pattern.indexOfLast { it.isNotBlank() }
        return pattern.subList(start, end + 1).map { it.padEnd(last + 1).substring(first, last + 1) }
    }

    /** Whether every ingredient gets a stack of its own that fits it (a matching, since one stack may fit several). */
    private fun shapelessFits(ingredients: List<Ingredient>, stacks: List<ItemStack>): Boolean {
        if (ingredients.size != stacks.size) return false
        val owner = IntArray(stacks.size) { -1 }
        fun place(ingredient: Int, seen: BooleanArray): Boolean {
            for (stack in stacks.indices) {
                if (seen[stack] || !fits(ingredients[ingredient], stacks[stack])) continue
                seen[stack] = true
                if (owner[stack] < 0 || place(owner[stack], seen)) {
                    owner[stack] = ingredient
                    return true
                }
            }
            return false
        }
        return ingredients.indices.all { place(it, BooleanArray(stacks.size)) }
    }

    /** Whether [stack] fits [ingredient]: a project item by the id it carries; anything else by type, and never a project item. */
    private fun fits(ingredient: Ingredient, stack: ItemStack?): Boolean {
        if (stack == null || stack.isEmpty) return false
        val carried = PaperItems.projectItem(stack)
        ingredient.item?.let { return carried != null && carried == PaperItems.resolve(it)?.toString() }
        if (carried != null) return false
        ingredient.tag?.let { tag -> return itemTag(tag)?.isTagged(stack.type) == true }
        return ingredient.kind?.let(::material) == stack.type
    }

    private fun isProjectItem(stack: ItemStack?): Boolean = PaperItems.projectItem(stack) != null

    companion object {
        /** The recipe [id] (the project's, or a package's `acme:<id>`) on the server: `shop:<id>`, `acme:<id>`. */
        fun key(id: String): NamespacedKey = if (":" in id) NamespacedKey.fromString(id)!! else NamespacedKey(PaperItems.namespace(), id)

        /** A recipe a script names: the project's by its bare id, any other by its namespaced id. */
        fun keyOf(recipe: String): NamespacedKey? = if (':' in
            recipe
        ) {
            NamespacedKey.fromString(recipe)
        } else {
            runCatching { key(recipe) }.getOrNull()
        }

        /** What a script calls a recipe the server has: the project's by its id, any other by its namespaced id. */
        fun idOf(key: NamespacedKey): String = if (key.namespace == PaperItems.namespace()) key.key else key.toString()

        private fun material(kind: String): Material? = Material.matchMaterial(GameIds.normalize(kind))

        private fun itemTag(tag: String): Tag<Material>? =
            NamespacedKey.fromString(tag)?.let { Bukkit.getTag(Tag.REGISTRY_ITEMS, it, Material::class.java) }
    }
}
