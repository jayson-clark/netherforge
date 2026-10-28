package dev.netherforge.plugin.loot

import dev.netherforge.format.game.GameIds
import dev.netherforge.format.game.RegistryKey
import dev.netherforge.format.item.ItemDef
import dev.netherforge.format.loot.LootContext
import dev.netherforge.format.loot.LootDrop
import dev.netherforge.format.loot.LootRoller
import dev.netherforge.format.loot.LootTableFile
import dev.netherforge.format.loot.LootTableKind
import dev.netherforge.format.project.KindSpec
import dev.netherforge.format.recipe.Ingredient
import dev.netherforge.format.ref.ResourceRef
import dev.netherforge.plugin.item.Items
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.platform.GameLootContext
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.platform.Platform
import dev.netherforge.plugin.project.Resource
import dev.netherforge.plugin.session.ReloadBatch
import dev.netherforge.plugin.session.RuntimeService
import dev.netherforge.plugin.session.SessionProject
import java.util.UUID
import kotlin.random.Random

/**
 * What a roll is for: who and what its tables' conditions ask about, and
 * where a game table is rolled. A script's `LootContext`, and what blocks
 * (W4.1) and natural spawning (W5.3) will roll their drops with.
 */
data class LootRoll(
    /** The player behind it: who killed the mob, broke the block or opened the chest. */
    val player: UUID? = null,
    /** What they killed or broke it with. */
    val tool: ItemData? = null,
    val luck: Double = 0.0,
    /** The entity whose loot it is. */
    val looted: UUID? = null,
    /** Where a game table is rolled; else the player's place, else the looted entity's. */
    val location: Location? = null
)

/**
 * The project's loot tables (`loot/<id>.json`), its packages' included, and
 * rolling them: format's [LootRoller] picks, from a generator seeded per
 * roll, and the game's tables a pick lands on are rolled by the server
 * ([dev.netherforge.plugin.platform.LootOps]) with a seed from the same draws, so a seed gives the same
 * items every time.
 *
 * A table is data: a reload swaps it, and the next roll uses it. A file with
 * errors keeps its last good version.
 */
class LootTables(
    private val platform: Platform,
    private val items: Items,
    /** The project's namespace, which references naming none are in. */
    private val namespace: () -> String,
    /** Every namespace the session runs: the project's and its packages'. */
    private val namespaces: () -> Set<String>
) : RuntimeService {
    override val name get() = "loot tables"

    /** Every table, as the project names it (`treasure`, `library:junk`). */
    private val tables = HashMap<String, LootTableFile>()

    private val roller = LootRoller { reference -> reference.nameIn(namespace())?.let(tables::get) }

    fun has(id: String): Boolean = id in tables

    /**
     * The table [reference] names as the server knows it (`treasure`,
     * `library:junk`; the project's own written `showcase:treasure` too), or null
     * when no table running is called that.
     */
    fun idOf(reference: String): String? = ResourceRef(reference).nameIn(namespace())?.takeIf { it in tables }

    fun ids(): List<String> = tables.keys.sorted()

    override fun define(project: SessionProject) {
        tables.clear()
        tables.putAll(project.running(LootTableKind))
    }

    override val reloads: Set<KindSpec<*, *>> get() = setOf(LootTableKind)

    override fun reload(kind: KindSpec<*, *>, ids: Set<String>, batch: ReloadBatch) {
        for (id in ids) {
            batch.resource(Resource(LootTableKind, id), batch.snapshot.running(LootTableKind)[id]) { next ->
                if (next == null) tables.remove(id) else tables[id] = next
                0
            }
        }
    }

    /**
     * What one roll of [table] gives: a project table as the project names it
     * (`treasure`, `library:junk`), or one of the game's by its id. [seed]
     * makes it give the same every time. A table nobody has, or a game table
     * that can't be rolled with what [roll] says, is the script's mistake.
     */
    fun roll(table: String, roll: LootRoll, seed: Long): List<ItemData> {
        val random = Random(seed)
        val own = tables[table]
        if (own != null) return roller.roll(own, Context(roll), random).flatMap { drop(it, roll) }
        // A name in the project's namespace or a package's is a project table's, which isn't there.
        if ((ResourceRef(table).namespace ?: namespace()) in namespaces()) {
            throw LuaApiException("there's no loot table \"$table\" (${LootTableKind.pathOf(table)})")
        }
        return game(table, roll, seed)
    }

    /** What one drop is as stacks: as many as its count takes, each at most a stack. */
    private fun drop(drop: LootDrop, roll: LootRoll): List<ItemData> = when (drop) {
        is LootDrop.Vanilla -> game(drop.table, roll, drop.seed)
        is LootDrop.Stack -> {
            val stack = stack(drop.item)
            val most = (stack.def.maxStackSize ?: MOST_IN_A_STACK).coerceAtLeast(1)
            (0 until drop.count step most).map { from -> stack.copy(def = stack.def.copy(count = minOf(most, drop.count - from))) }
        }
    }

    /** One of [item]: a project item's look with what the entry adds, or a vanilla item. */
    private fun stack(item: ItemDef): ItemData {
        val reference = item.item ?: return ItemData(item.copy(kind = GameIds.normalize(item.kind), count = 1))
        val id = items.idOf(reference.text) ?: throw LuaApiException("there's no item \"$reference\"")
        return items.create(id, item.copy(item = null, count = 1))
    }

    private fun game(table: String, roll: LootRoll, seed: Long): List<ItemData> {
        if (!platform.loot.exists(table)) throw LuaApiException("neither the project nor the server has a loot table \"$table\"")
        val at = roll.location
            ?: roll.player?.let(platform.players::location)
            ?: roll.looted?.let { platform.worldEntities.info(it)?.location }
            ?: throw LuaApiException(
                "the game's loot table \"$table\" is rolled at a place: give the roll a location, a player or a looted entity"
            )
        val context = GameLootContext(at, roll.luck, roll.player, roll.looted)
        return try {
            platform.loot.roll(table, context, seed) ?: throw LuaApiException("the server has no loot table \"$table\"")
        } catch (e: IllegalArgumentException) {
            throw LuaApiException("the game's loot table \"$table\" can't be rolled here: ${e.message}")
        }
    }

    /** What a roll's conditions ask, answered from what the script said. */
    private inner class Context(private val roll: LootRoll) : LootContext {
        override val luck: Double get() = roll.luck
        override val hasPlayer: Boolean get() = roll.player != null

        /** As a recipe matches an ingredient: a project item by its id, a vanilla kind or tag only a stack that isn't one. */
        override fun toolIs(tool: Ingredient): Boolean {
            val held = roll.tool?.def ?: return false
            val project = held.item
            tool.item?.let { wanted -> return project != null && project.nameIn(namespace()) == wanted.nameIn(namespace()) }
            if (project != null || held.kind.isEmpty()) return false
            val kind = GameIds.normalize(held.kind)
            tool.tag?.let { return platform.game.tag(RegistryKey.ITEM, GameIds.normalize(it))?.contains(kind) == true }
            return tool.kind?.let { GameIds.normalize(it) == kind } == true
        }

        override fun enchantmentLevel(enchantment: String): Int =
            roll.tool?.def?.enchantments.orEmpty().entries.firstOrNull { GameIds.normalize(it.key) == enchantment }?.value ?: 0
    }

    private companion object {
        /** A stack's size when its item doesn't say. */
        const val MOST_IN_A_STACK = 64
    }
}
