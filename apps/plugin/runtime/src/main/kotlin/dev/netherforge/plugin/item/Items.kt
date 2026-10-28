package dev.netherforge.plugin.item

import dev.netherforge.format.item.ItemDef
import dev.netherforge.format.item.ItemFile
import dev.netherforge.format.item.ItemKind
import dev.netherforge.format.item.ProjectItems
import dev.netherforge.format.project.KindSpec
import dev.netherforge.format.project.ResourcePlace
import dev.netherforge.format.ref.ResourceRef
import dev.netherforge.plugin.binding.EventType
import dev.netherforge.plugin.binding.Events
import dev.netherforge.plugin.binding.ItemRenderEvent
import dev.netherforge.plugin.binding.ItemViewEvent
import dev.netherforge.plugin.binding.LuaEvent
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.platform.InventoryRef
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.ItemLook
import dev.netherforge.plugin.platform.ItemView
import dev.netherforge.plugin.platform.Platform
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.project.Resource
import dev.netherforge.plugin.script.Scope
import dev.netherforge.plugin.script.ScopeOwner
import dev.netherforge.plugin.script.Scripts
import dev.netherforge.plugin.session.ReloadBatch
import dev.netherforge.plugin.session.RuntimeService
import dev.netherforge.plugin.session.SessionProject
import dev.netherforge.plugin.session.TickPhase
import kotlinx.serialization.json.JsonElement
import java.util.UUID

/**
 * The project's items (`items/<id>/item.json`) and the script behind each.
 *
 * An item is data first: its look ([look]) is what the platform resolves
 * every stack naming it against and stamps on it, so it's defined before any
 * window or script builds a stack. Like a dialog, it has one script, not one
 * per stack: it starts at load and runs until the item reloads, and hears
 * what players do with any stack of the item, routed here by the stack's id
 * ([stage]) ahead of the player's own handlers.
 *
 * Stale stacks (stamped with a look the item has since changed from) are
 * rewritten when the server sees them: a player's inventory when they join
 * and when the item reloads, an inventory when someone opens it, and a
 * player's inventory the tick after they pick something up ([refreshSoon]).
 *
 * How a stack looks to one player ([view], the `view` and `item_view` handlers) is
 * the platform's to ask about each stack it sends their game; a player's are
 * sent again ([refreshView]) at the end of a tick, and everyone's once the
 * handlers themselves change (a script reloaded, listening or not any more), so
 * a look a reload took away doesn't stay on anyone's screen.
 */
class Items(
    private val platform: Platform,
    private val scripts: Scripts,
    private val readSource: (String) -> String?,
    /** Where a resource is now (in its group folders), by kind and id: where its script is read from. */
    private val placeOf: (KindSpec<*, *>, String) -> ResourcePlace,
    /** The project's namespace, which references naming none are in. */
    private val namespace: () -> String
) : RuntimeService {
    override val name get() = "items"

    private class Record(val id: String, val file: ItemFile) {
        val look = ItemLook(file.look(), ProjectItems.hash(file.look()))

        /** The item's script's scope, while it runs. */
        var scope: Scope? = null
    }

    private val records = LinkedHashMap<String, Record>()

    /** Players whose inventory to check at the next tick: they just picked something up. */
    private val pending = LinkedHashSet<UUID>()

    /** Whether any item listens for the events whose stack the runtime has to look up (the held one). */
    private val heldWatched = HashMap<EventType<*>, Boolean>()

    /** Whether any project item has a `view` handler, so a stack of one has to be looked up at all. */
    private var itemViewed = false

    /** Players whose games to send their stacks' looks again at the next tick ([refreshView]). */
    private val viewsPending = LinkedHashSet<UUID>()

    /** Whether the `view` handlers changed since everyone's stacks were last sent again: then they all are. */
    private var viewsChanged = false

    init {
        for (event in listOf(Events.PROJECTITEM_HIT, Events.PROJECTITEM_BREAK_BLOCK, Events.PROJECTITEM_INTERACT_ENTITY)) {
            scripts.watch(event) { heldWatched[event] = it }
        }
        scripts.watch(Events.PROJECTITEM_VIEW) { itemViewed = it }
        scripts.counting(Events.PROJECTITEM_VIEW, Events.NF_ITEM_VIEW) { viewsChanged = true }
    }

    /**
     * The id of the project item [reference] names, as a file or a script
     * writes it (`ruby`) or a stack carries it (`shop:ruby`), or null when it
     * names none of the items running: the project's, and its packages' as
     * `acme:coin`.
     */
    fun idOf(reference: String): String? = ResourceRef(reference).nameIn(namespace())?.takeIf { has(it) }

    /** The current look of the item [reference] names ([idOf]), or null. Any thread may ask (the platform's builds do). */
    fun look(reference: String): ItemLook? = idOf(reference)?.let { synchronized(records) { records[it]?.look } }

    fun has(id: String): Boolean = synchronized(records) { id in records }

    /**
     * The project block a stack of one of the project's items places, as the
     * project names it (`ruby_ore`, `library:gem_block`), or null for a
     * stack that places none (any other item, a block the project doesn't have).
     */
    fun blockOf(stack: ItemData?): String? {
        val id = stack?.def?.item?.let { idOf(it.text) } ?: return null
        val block = synchronized(records) { records[id]?.file?.block } ?: return null
        return block.nameIn(namespace())
    }

    /** The project item that places the project block [block] (`ruby_ore`), the first by name when several do; null for none. */
    fun placing(block: String): String? = synchronized(records) {
        records.values.sortedBy { it.id }.firstOrNull { it.file.block?.nameIn(namespace()) == block }?.id
    }

    /** Whether a stack of a project item is a bow that draws and shoots without ammo (its `drawWithoutAmmo`). */
    fun drawsWithoutAmmo(stack: ItemData?): Boolean {
        val id = stack?.def?.item?.let { idOf(it.text) } ?: return false
        return synchronized(records) { records[id]?.file?.drawWithoutAmmo == true }
    }

    fun ids(): List<String> = synchronized(records) { records.keys.sorted() }

    /** [id]'s `properties` from its file (empty when it has none), or null when the project has no such item. */
    fun properties(id: String): Map<String, JsonElement>? = synchronized(records) {
        records[id]?.file?.properties.orEmpty().takeIf {
            id in
                records
        }
    }

    /**
     * [stack], a stack of one of the project's items being made or given its item's new look, as the item's
     * `render` handlers finish it: the name and lore they assign. [stack] itself when it's no project item's
     * or nobody listens, so a stack nobody renders costs one lookup.
     */
    fun render(stack: ItemData): ItemData {
        val id = stack.def.item?.let { idOf(it.text) } ?: return stack
        val handle = LuaHandle.ProjectItem(id)
        if (!scripts.listening(handle, Events.PROJECTITEM_RENDER)) return stack
        val event = ItemRenderEvent(stack, stack.def.name, stack.def.lore)
        scripts.emit(Events.PROJECTITEM_RENDER, handle, event)
        if (event.displayName == stack.def.name && event.lore == stack.def.lore) return stack
        return stack.copy(def = stack.def.copy(name = event.displayName, lore = event.lore))
    }

    /**
     * Whether a stack of the project item [reference] names (null for a stack of none) may look different
     * to some player: someone listens to `item_view`, or to its item's `view`. Asked for every stack sent to a
     * player, so it's two lookups while nobody listens.
     */
    fun viewing(reference: String?): Boolean {
        if (scripts.listening(null, Events.NF_ITEM_VIEW)) return true
        if (!itemViewed || reference == null) return false
        val id = idOf(reference) ?: return false
        return scripts.listening(LuaHandle.ProjectItem(id), Events.PROJECTITEM_VIEW)
    }

    /**
     * How [stack] looks to [player], as its item's `view` handlers and then `nf`'s `item_view` leave its name
     * and lore; null when they're as they were, or when a handler failed (reported as any handler's failure is):
     * the stack is then sent as it is.
     */
    fun view(player: UUID, stack: ItemData): ItemView? {
        val id = stack.def.item?.let { idOf(it.text) }
        val own = if (id != null) listOf(Events.PROJECTITEM_VIEW to LuaHandle.ProjectItem(id)) else emptyList()
        val event = ItemViewEvent(LuaHandle.Player(player.toString()), stack, stack.def.name, stack.def.lore)
        val failures = scripts.handlerFailures
        scripts.emit(own + (Events.NF_ITEM_VIEW to null), event)
        if (scripts.handlerFailures != failures) return null
        if (event.displayName == stack.def.name && event.lore == stack.def.lore) return null
        return ItemView(event.displayName, event.lore)
    }

    /** Sends [player]'s game the stacks it shows that now look different to them, at the end of this tick (`player:refresh_items`). */
    fun refreshView(player: UUID) {
        viewsPending += player
    }

    /** Takes every item: its look, before any window or script builds a stack. Their scripts start in [start]. */
    override fun define(project: SessionProject) {
        synchronized(records) {
            records.clear()
            for ((id, file) in project.running(ItemKind)) records[id] = Record(id, file)
        }
    }

    override fun start() {
        for (record in synchronized(records) { records.values.toList() }) start(record)
        // A new session's handlers aren't the last one's: whatever those showed goes.
        viewsChanged = true
    }

    override fun stop() {
        for (record in synchronized(records) { records.values.toList() }) stop(record)
        pending.clear()
        viewsPending.clear()
    }

    override fun playerQuit(player: PlayerRef) {
        viewsPending -= player.uuid
    }

    /** Stacks they carry from before the last change to an item take its new look. */
    override fun playerJoined(player: PlayerRef) {
        refresh(InventoryRef.Player(player.uuid))
    }

    override fun tick(phase: TickPhase) {
        if (phase != TickPhase.UPKEEP) return
        refreshPending()
        refreshViews()
    }

    override val reloads: Set<KindSpec<*, *>> get() = setOf(ItemKind)

    /** `reattached` is how many online players had stacks rewritten to the new look. */
    override fun reload(kind: KindSpec<*, *>, ids: Set<String>, batch: ReloadBatch) {
        for (id in ids) batch.resource(Resource(ItemKind, id), batch.snapshot.running(ItemKind)[id]) { reload(id, it) }
    }

    /**
     * Moves [id] onto [next] (its script restarts), or forgets it when [next]
     * is null: its handlers go, and stacks of it keep the look they have.
     * Every online player's inventory is checked for stacks the change made
     * stale. How many players' inventories changed.
     */
    fun reload(id: String, next: ItemFile?): Int {
        val previous = synchronized(records) { records.remove(id) }
        previous?.let(::stop)
        if (next == null) {
            scripts.dropTarget(LuaHandle.ProjectItem(id))
            return 0
        }
        val record = Record(id, next)
        synchronized(records) { records[id] = record }
        start(record)
        return if (previous?.look?.hash == record.look.hash) 0 else refreshOnline()
    }

    /**
     * A stack of [id] as a script asked for it: [overrides] (an item read from
     * the script's table, without a kind) on top of the item's look, `count` 1
     * unless they say otherwise. A project item the project doesn't have is
     * the script's mistake.
     */
    fun create(id: String, overrides: ItemDef? = null): ItemData {
        val look = look(id) ?: throw LuaApiException("there's no item \"$id\" (${ItemKind.pathOf(id)})")
        val def = ProjectItems.resolve((overrides ?: ItemDef()).copy(item = ResourceRef(id)), look.def)
        return render(ItemData(def.copy(count = def.count ?: 1)))
    }

    /** Checks the stacks in [inventory] (an inventory someone opened, a player's own). */
    fun refresh(inventory: InventoryRef): Int = platform.projectItems.refresh(inventory)

    /** Checks [player]'s inventory at the next tick: what they just picked up is in it by then. */
    fun refreshSoon(player: UUID) {
        pending += player
    }

    /** Every online player's own inventory. How many changed. */
    fun refreshOnline(): Int = platform.players.online().count { refresh(InventoryRef.Player(it.uuid)) > 0 }

    /** Once a tick: the inventories [refreshSoon] asked for. */
    private fun refreshPending() {
        if (pending.isEmpty()) return
        val players = pending.toList()
        pending.clear()
        for (player in players) refresh(InventoryRef.Player(player))
    }

    /** Once a tick: the players [refreshView] asked for, or everyone when the `view` handlers changed. */
    private fun refreshViews() {
        val players = if (viewsChanged) platform.players.online().map { it.uuid } else viewsPending.toList()
        viewsChanged = false
        viewsPending.clear()
        for (player in players) platform.itemViews.refresh(player)
    }

    /**
     * The item's stage of [event], to go first on its path, when [item] is a
     * stack of one of the project's items; nothing otherwise.
     */
    fun <P : LuaEvent> stage(event: EventType<P>, item: ItemData?): List<Pair<EventType<P>, LuaHandle?>> {
        val id = item?.def?.item?.let { idOf(it.text) } ?: return emptyList()
        return listOf(event to LuaHandle.ProjectItem(id))
    }

    /**
     * The item's stage of [event] for what [player] holds in [hand]
     * (`main_hand` or `off_hand`): looked up only while some item listens for
     * [event], since the event doesn't say what's held.
     */
    fun <P : LuaEvent> heldStage(event: EventType<P>, player: UUID, hand: String): List<Pair<EventType<P>, LuaHandle?>> {
        if (heldWatched[event] != true) return emptyList()
        return stage(event, platform.worldEntities.equipment(player, hand))
    }

    private fun start(record: Record) {
        val script = record.file.script ?: return
        val path = placeOf(ItemKind, record.id).file(script.file)
        val source = readSource(path) ?: return
        record.scope = scripts.start(ScopeOwner.ItemScript(record.id, path), script, source).first
    }

    private fun stop(record: Record) {
        record.scope?.let(scripts::close)
        record.scope = null
    }
}
