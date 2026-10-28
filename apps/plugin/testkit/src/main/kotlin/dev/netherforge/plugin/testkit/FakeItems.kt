package dev.netherforge.plugin.testkit

import dev.netherforge.format.item.ItemDef
import dev.netherforge.format.item.ProjectItems
import dev.netherforge.plugin.platform.GameLootContext
import dev.netherforge.plugin.platform.InventoryRef
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.ItemViewOps
import dev.netherforge.plugin.platform.LootOps
import dev.netherforge.plugin.platform.ProjectItemOps
import dev.netherforge.plugin.platform.RecipeOps
import dev.netherforge.plugin.platform.RecipeSpec
import java.util.UUID

// The fake server's project items, recipes and loot tables.

/**
 * The game's loot tables: a few made up, each roll drawn from its seed
 * alone as the game's are, and a mob's needing the mob.
 */
class FakeLoot(private val platform: FakePlatform) : LootOps {
    val tables = linkedMapOf(
        "minecraft:chests/simple_dungeon" to FakeLootTable { random, _ ->
            listOf(
                ItemData(ItemDef(kind = "minecraft:bread", count = 1 + random.nextInt(3))),
                ItemData(ItemDef(kind = "minecraft:gold_ingot", count = 1 + random.nextInt(4)))
            )
        },
        "minecraft:entities/zombie" to FakeLootTable(needsLooted = true) { random, _ ->
            listOf(ItemData(ItemDef(kind = "minecraft:paper", count = random.nextInt(3)))).filter { it.def.count!! > 0 }
        }
    )

    /** Every roll, in order: `<table> <seed>`. */
    val rolls = mutableListOf<String>()

    override fun exists(table: String): Boolean = table in tables

    override fun roll(table: String, context: GameLootContext, seed: Long): List<ItemData>? {
        val found = tables[table] ?: return null
        require(platform.worlds.exists(context.location.world)) { "there's no world \"${context.location.world}\"" }
        require(!found.needsLooted || context.looted != null) { "$table needs the entity whose loot it is" }
        rolls += "$table $seed"
        return found.roll(java.util.Random(seed), context)
    }
}

/** The server's recipes from the project, and players' recipe books. */
class FakeRecipes(private val platform: FakePlatform) : RecipeOps {
    /** What the server has now, by the project's id. */
    val added = LinkedHashMap<String, RecipeSpec>()

    /** How many times players were sent the recipe list. */
    var resends = 0

    /** Every add and remove, in order: `add ruby_sword`, `remove ruby_sword`. */
    val changes = mutableListOf<String>()

    /** Each player's recipe book: the ids discovered. */
    val books = LinkedHashMap<UUID, MutableSet<String>>()

    override fun add(recipe: RecipeSpec): Boolean {
        if (platform.stack(ItemData(recipe.file.result)) == null) return false
        added[recipe.id] = recipe
        changes += "add ${recipe.id}"
        return true
    }

    override fun remove(id: String): Boolean = (added.remove(id) != null).also { if (it) changes += "remove $id" }

    override fun resend() {
        resends++
    }

    private fun known(recipe: String) = recipe in added || recipe.startsWith("minecraft:")

    override fun discover(player: UUID, recipe: String): Boolean =
        player in platform.players.byId && known(recipe) && books.getOrPut(player) { linkedSetOf() }.add(recipe)

    override fun undiscover(player: UUID, recipe: String): Boolean =
        player in platform.players.byId && books[player]?.remove(recipe) == true

    override fun hasDiscovered(player: UUID, recipe: String): Boolean = books[player]?.contains(recipe) == true
}

/** Rewrites stale project items in real inventories, as the Paper adapter does. */
class FakeProjectItems(private val platform: FakePlatform) : ProjectItemOps {
    /** Every inventory the runtime asked to check, in order. */
    val checked = mutableListOf<InventoryRef>()

    override fun refresh(inventory: InventoryRef): Int {
        checked += inventory
        val slots = platform.inventories.slots(inventory) ?: return 0
        var changed = 0
        for ((index, held) in slots.withIndex()) {
            val id = held?.def?.item ?: continue
            val look = platform.itemLooks(id.text) ?: continue
            if (held.look == look.hash) continue
            val restyled = ItemData(ProjectItems.restyle(held.def, id, look.def), held.raw, look.hash)
            slots[index] = platform.events?.itemRendered(restyled) ?: restyled
            changed++
        }
        return changed
    }
}

/** One of [FakeLoot]'s tables: whether it needs the entity whose loot it is, and what a roll of it gives. */
class FakeLootTable(val needsLooted: Boolean = false, val roll: (java.util.Random, GameLootContext) -> List<ItemData>)

/**
 * What each player's game was last sent, and how each stack looked to them, as the Paper adapter keeps it in
 * step: at the end of a tick ([broadcast], from [FakePlatform.tickWorld]) every place whose stack changed is sent
 * again, worked out by the runtime's `view` handlers first; [refresh] works every place out again and sends
 * those that look different.
 */
class FakeItemViews(private val platform: FakePlatform) : ItemViewOps {
    /** Where a player's game shows a stack. */
    sealed interface Place {
        /** A slot of their own inventory, numbered as the runtime numbers it. */
        data class Inventory(val slot: Int) : Place

        /** A slot of the window they have open. */
        data class Menu(val window: UUID, val slot: Int) : Place

        /** The stack on their cursor in the window they have open. */
        data object Cursor : Place
    }

    /** What a place was last sent: the real stack, and the one shown for it. */
    private class Sent(val real: ItemData?, val shown: ItemData?)

    private val sent = HashMap<UUID, HashMap<Place, Sent>>()

    /** How many stacks were sent to players' games, for a test. */
    var sends = 0

    /** How many stacks the runtime was asked how they look, for a test. */
    var viewed = 0

    /** Every player whose stacks the runtime asked to send again ([refresh]), in order. */
    val refreshes = mutableListOf<UUID>()

    /** The stack [player]'s game was last sent for [place], as it shows it; null for an empty place, or one it hasn't been sent. */
    fun shown(player: UUID, place: Place): ItemData? = sent[player]?.get(place)?.shown

    /**
     * What [player]'s game shows for [place], which holds [real] now: how it was last sent, when that was
     * [real]; [real] itself when the change hasn't been sent yet (it will be by the end of the tick).
     */
    fun display(player: UUID, place: Place, real: ItemData?): ItemData? =
        sent[player]?.get(place)?.takeIf { it.real == real }?.shown ?: real

    /** Every place [player]'s game shows now, with the real stack there. */
    private fun places(player: FakePlayer): Map<Place, ItemData?> {
        val places = LinkedHashMap<Place, ItemData?>()
        for ((slot, item) in player.inventorySlots.withIndex()) places[Place.Inventory(slot)] = item
        val window = platform.menus.viewing(player.ref.uuid)
        val open = window?.let { platform.menus.windows[it] }
        if (window != null && open != null) {
            for ((slot, item) in open.slots.withIndex()) places[Place.Menu(window, slot)] = item
            places[Place.Cursor] = open.cursors[player.ref.uuid]
        }
        return places
    }

    /** The end of a tick: each player's game is sent each stack that changed since it was last sent it. */
    fun broadcast() {
        sent.keys.retainAll(platform.players.byId.keys)
        for (player in platform.players.byId.values) {
            val known = sent.getOrPut(player.ref.uuid) { HashMap() }
            val now = places(player)
            known.keys.retainAll(now.keys)
            for ((place, real) in now) {
                val last = known[place]
                if (last != null && last.real == real) continue
                known[place] = Sent(real, real?.let { view(player.ref.uuid, it) })
                sends++
            }
        }
    }

    override fun refresh(player: UUID): Boolean {
        val who = platform.players.byId[player] ?: return false
        refreshes += player
        val known = sent.getOrPut(player) { HashMap() }
        for ((place, real) in places(who)) {
            val shown = real?.let { view(player, it) }
            val last = known[place]
            if (last != null && last.real == real && last.shown == shown) continue
            known[place] = Sent(real, shown)
            sends++
        }
        return true
    }

    /** [real] as [player] is shown it: asked of the runtime only when its item may look different to anyone. */
    private fun view(player: UUID, real: ItemData): ItemData {
        val events = platform.events ?: return real
        if (!events.itemViewing(real.def.item?.text)) return real
        viewed++
        val view = events.itemViewed(player, real) ?: return real
        return real.copy(def = real.def.copy(name = view.name, lore = view.lore))
    }

    /**
     * [player]'s game, in creative mode, puts [item] in [slot] of their inventory, as their game shows it. A
     * stack it was shown comes back as the stack the server holds (its count as the game says), as the adapter
     * maps it back, so a look shown never becomes real.
     */
    fun creative(player: FakePlayer, slot: Int, item: ItemData?) {
        player.inventorySlots[slot] = item?.let { platform.stack(realOf(player.ref.uuid, it)) }
    }

    /** The real stack [player]'s game was shown as [item], with [item]'s count; [item] itself when it was shown none. */
    fun realOf(player: UUID, item: ItemData): ItemData {
        val uncounted = item.def.copy(count = null)
        val real =
            sent[player]?.values?.firstOrNull { it.real != null && it.shown?.def?.copy(count = null) == uncounted }?.real ?: return item
        return real.copy(def = real.def.copy(count = item.def.count))
    }
}
