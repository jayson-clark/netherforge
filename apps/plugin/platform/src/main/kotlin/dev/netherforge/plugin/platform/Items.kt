package dev.netherforge.plugin.platform

import dev.netherforge.format.item.ItemDef
import dev.netherforge.format.recipe.RecipeFile
import java.util.UUID

/**
 * An item as the server holds it: the fields NetherForge can describe (script
 * data, [ItemDef.data], included: the adapter keeps it in the stack's
 * persistent data container), plus [raw], whatever else the stack carries (a
 * book's pages, another plugin's data), opaque. [raw] is present only when
 * [def] alone would lose something, and it's what lets a script read an item,
 * change one field and write it back without stripping the rest.
 *
 * A stack of a project item has [ItemDef.item] set, and [look] is the hash of
 * the look it was stamped with (`ProjectItems.hash`), as read from the
 * server: what tells a stale stack from a current one. It's the platform's to
 * stamp, so it's ignored when an item is written (the platform stamps the
 * item's current look, or keeps the stamp [raw] carries).
 */
data class ItemData(val def: ItemDef, val raw: String? = null, val look: String? = null)

/** A project item's look: its definition as an item ([dev.netherforge.format.item.ItemFile.look]) and that look's hash. */
data class ItemLook(val def: ItemDef, val hash: String)

/**
 * Project items on real stacks. A stack stamped with a project item whose
 * look has changed since (its hash isn't the item's current one) is stale;
 * these rewrite stale stacks with `ProjectItems.restyle`, keeping whatever
 * else the stack carries that the item can't say (another plugin's data).
 * Stacks of items the project no longer has are left alone. Checking a stack
 * reads only its stamp, so a whole inventory of current stacks costs little.
 */
interface ProjectItemOps {
    /**
     * Rewrites the stale stacks in [inventory]: a project item's from before its look changed, and any stack
     * whose `canBreak` or `canPlaceOn` names a project block held in another state than it was. How many it
     * rewrote; 0 for one that isn't there.
     */
    fun refresh(inventory: InventoryRef): Int
}

/**
 * How stacks look to each player, apart from what they are.
 *
 * The adapter asks the runtime about every stack it sends a player's game (their inventory, armour and offhand
 * included, the window they have open, their cursor; a whole window or one slot): first, cheaply, whether
 * stacks of its project item may look different to anyone ([PlatformEvents.itemViewing]), and only then how it
 * looks to that player ([PlatformEvents.itemViewed]), whose name and lore it sends in the real ones' place. What
 * the server keeps never changes: the stack, how it stacks, what's saved, what anyone else is sent, what
 * scripts read. What the game sends back is mapped back to what the server holds, so a game that echoes a stack
 * it was shown (a creative-mode click) can't make the shown look real; and the game's idea of a slot it was
 * shown a look for still counts as in step, so a click doesn't resend it.
 */
interface ItemViewOps {
    /**
     * Works out every stack [player]'s game shows again ([PlatformEvents.itemViewed]) and sends those that now
     * look different from what it was last sent: their inventory, the window they have open, their cursor.
     * False when they're offline.
     */
    fun refresh(player: UUID): Boolean
}

/** How a stack looks to one player ([PlatformEvents.itemViewed]): the name (null for the game's own) and lore (null for none) they're shown. */
data class ItemView(val name: String?, val lore: List<String>?)

/**
 * A recipe the project adds: [id] is the project's own (`ruby_sword`), which
 * the adapter keys in the project's namespace (`shop:ruby_sword`), and [file] says what it is
 * (from `recipes/<id>.json` or a script, validated either way). Its result
 * and project-item ingredients name items the adapter resolves through the
 * looks [Platform.bind] gave it.
 */
data class RecipeSpec(val id: String, val file: RecipeFile)

/**
 * The project's recipes on the server.
 *
 * A project item in a recipe is matched by the id its stacks carry, never by
 * looks: the adapter lets the server match the item's kind, then checks every
 * project-item ingredient against the stack in its place when a station is
 * about to make something (a crafting grid's result, a crafter, a furnace,
 * smoker, blast furnace or campfire cooking, a smithing table's result, a
 * stonecutter's choice), and refuses it on a mismatch. A recipe takes a
 * project item only where it names it: a vanilla kind or tag (in the
 * project's recipes or Minecraft's own) never takes a project item's stack.
 * Recipe ids the adapter reports in events (`player_craft`) are the
 * project's own for these, and namespaced (`minecraft:bread`) otherwise.
 */
interface RecipeOps {
    /** Adds [recipe], replacing one with its id, without telling players ([resend] does). False when it can't be built. */
    fun add(recipe: RecipeSpec): Boolean

    /** Takes the project's recipe [id] away, without telling players. False when the server has no such recipe. */
    fun remove(id: String): Boolean

    /** Sends every online player the server's recipes again, after adds and removes. */
    fun resend()

    /** Puts a recipe in [player]'s recipe book: the project's by id, or any by its namespaced id. False when it can't (offline, already there, no such recipe). */
    fun discover(player: UUID, recipe: String): Boolean

    fun undiscover(player: UUID, recipe: String): Boolean

    fun hasDiscovered(player: UUID, recipe: String): Boolean
}
