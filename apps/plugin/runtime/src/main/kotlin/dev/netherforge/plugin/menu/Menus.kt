package dev.netherforge.plugin.menu

import dev.netherforge.format.menu.CompiledMenu
import dev.netherforge.format.menu.MenuKind
import dev.netherforge.format.project.DefaultFontKind
import dev.netherforge.format.project.KindSpec
import dev.netherforge.format.project.ResourcePlace
import dev.netherforge.format.resourcepack.ResourcePackKind
import dev.netherforge.plugin.binding.EventType
import dev.netherforge.plugin.binding.Events
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.binding.MenuClickEvent
import dev.netherforge.plugin.binding.MenuDragEvent
import dev.netherforge.plugin.binding.MenuEvent
import dev.netherforge.plugin.lua.LuaRef
import dev.netherforge.plugin.platform.InventoryOps
import dev.netherforge.plugin.platform.InventoryRef
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.MenuClick
import dev.netherforge.plugin.platform.MenuOps
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.platform.WindowSpec
import dev.netherforge.plugin.project.Resource
import dev.netherforge.plugin.script.Scope
import dev.netherforge.plugin.script.ScopeOwner
import dev.netherforge.plugin.script.Scripts
import dev.netherforge.plugin.session.ReloadBatch
import dev.netherforge.plugin.session.RuntimeService
import dev.netherforge.plugin.session.SessionProject
import dev.netherforge.plugin.session.TickPhase
import dev.netherforge.plugin.session.liveness
import java.util.UUID

/**
 * Every open menu window and the script behind each.
 *
 * A **window** is to a menu file what an instance is to a centity. A
 * `shared` menu has exactly one, made at load (before any script runs, so
 * a module's body finds it) and named by the menu's own
 * id, so two players sent to the same shop see the same stock. Any other
 * gets one per opening, named by a fresh UUID, which goes when its last
 * viewer closes it: that's what makes a menu per player cheap.
 *
 * Each window runs its own copy of the menu's script, in its own scope with
 * its own budget, with `this` the window. A click
 * is heard by the clicked slot's handlers first, then the window's, then
 * `nf.on("menu_click")`; `event:stop()` ends that and `event:cancel()`
 * cancels the click. A locked window starts every click that would move items
 * in or out of it already cancelled, which a handler may undo. A window with
 * a [ShiftInto] takes a shift-click from the player's own inventory instead:
 * the engine moves the stack into the bound slots it names, offering it to
 * each slot's click handlers. When a window goes, so does every handler on it
 * and its slots.
 *
 * Closing is reported by the server, and a window is only retired on the next
 * tick: while a close is being handled the closing player can still count as a
 * viewer.
 *
 * A slot of a window that isn't shared can be **bound** to a slot of a
 * player's storage ([InventoryRef.Storage], kept in their own save data): it's
 * a view of it from then on, and whatever goes in or out of it is written
 * there as it happens. A click or drag the game applies is heard straight
 * after ([changed]), before the server can save anyone; a script's writes to
 * either side write the other. So an item is only ever in the storage or out
 * of it, never in the window alone, which is just a picture of it: unbinding
 * empties the slot, and so does the storage's player leaving.
 *
 * A script can also make a menu from a table (`nf.menus.create`): a
 * [Template], which has no file and no script. Its windows are like
 * any other window (one per opening, never shared), and every event on one
 * of them goes on to the template after the window's own handlers. A
 * template belongs to the scope that created it and goes with it, closing
 * its windows.
 */
class Menus(
    private val ops: MenuOps,
    private val inventories: InventoryOps,
    private val scripts: Scripts,
    private val readSource: (String) -> String?,
    /** Where a resource is now (in its group folders), by kind and id: where its script is read from. */
    private val placeOf: (KindSpec<*, *>, String) -> ResourcePlace,
    /**
     * The MiniMessage that draws a skin, moved by a number of pixels, for
     * the front of a title; null for none or one the packs don't have.
     */
    private val skinTitle: (skin: String, shift: Int) -> String?,
    /** How wide MiniMessage words draw in the default font, exactly, or null when that can't be known. */
    private val measure: (String) -> Int? = { null },
    /** What players wear, for slots bound to it (`bind_equipment`). */
    private val wardrobe: Wardrobe = Wardrobe.NONE
) : RuntimeService {
    override val name get() = "menus"

    private val definitions = LinkedHashMap<String, CompiledMenu>()
    private val windows = LinkedHashMap<String, Window>()
    private val byUuid = HashMap<UUID, Window>()
    private val retiring = LinkedHashSet<Window>()
    private val templates = LinkedHashMap<String, Template>()

    /** Which slot of a storage a window's slot shows. */
    data class Binding(val storage: InventoryRef.Storage, val index: Int)

    /** A slot that shows what [player] wears in [slot] (`head`, `chest`, `legs`, `feet`, `off_hand`). */
    data class Worn(val player: UUID, val slot: String)

    /** What players wear: read and put on, by the Lua name of the place. False when they're offline. */
    interface Wardrobe {
        fun worn(player: UUID, slot: String): ItemData?

        fun wear(player: UUID, slot: String, item: ItemData?): Boolean

        /** Whether they're online, so what they wear can be read. */
        fun online(player: UUID): Boolean

        companion object {
            val NONE = object : Wardrobe {
                override fun worn(player: UUID, slot: String): ItemData? = null

                override fun wear(player: UUID, slot: String, item: ItemData?) = false

                override fun online(player: UUID) = false
            }
        }
    }

    /** What a slot takes: [takes] says which stacks, [max] how many of one (null for a full stack); [source] is as the script gave it. */
    class Rule(val source: Any, val max: Int?, val takes: (ItemData) -> Boolean)

    /** Where a shift-click from the player's inventory goes: the window's slots bound to [storage]'s, from [first] to [last]. */
    data class ShiftInto(val storage: InventoryRef.Storage, val first: Int, val last: Int)

    /** A menu a script made: what its windows start as, for as long as the scope [owner] lasts. */
    class Template(val id: String, val definition: CompiledMenu, val owner: Int)

    /**
     * One live window. [context] is what the script that opened it passed
     * (`player:open_menu(menu, { context = ... })`), kept until it goes.
     */
    inner class Window(
        val id: String,
        definition: CompiledMenu,
        val uuid: UUID,
        val context: LuaRef? = null,
        /** The [Template] it was made from, by id; null for a window of a menu file. */
        val template: String? = null
    ) {
        var definition: CompiledMenu = definition
            internal set

        /** The title without the skin, as MiniMessage. */
        var title: String? = defaultTitle(definition)
        var skin: String? = definition.skin?.text
        var locked: Boolean = definition.locked

        /** The menu's script's scope on this window, while it runs. */
        var scope: Scope? = null

        /** Its slots bound to a storage's, by slot. */
        internal val bindings = HashMap<Int, Binding>()

        /** Its slots bound to what a player wears, by slot. */
        internal val worn = HashMap<Int, Worn>()

        /** What its slots take, for those a script gave a rule, by slot. */
        internal val rules = HashMap<Int, Rule>()

        /** Whether [slot] shows a storage's slot or a player's equipment: theirs to use by hand. */
        internal fun bound(slot: Int) = slot in bindings || slot in worn

        /** Where a shift-click from the player's own inventory goes, when a script set it; null for the game's own rules. */
        var shiftInto: ShiftInto? = null
            internal set

        /** What the server was last told the title is, skin included. */
        internal var pushedTitle: String = ""

        var gone = false
            internal set

        val menu: String get() = definition.id
        val shared: Boolean get() = definition.shared
        val size: Int get() = definition.size
    }

    fun definition(id: String): CompiledMenu? = definitions[id]

    fun ids(): List<String> = definitions.keys.sorted()

    fun window(id: String): Window? = windows[id]?.takeIf { !it.gone }

    fun windowOf(uuid: UUID): Window? = byUuid[uuid]?.takeIf { !it.gone }

    fun all(): List<Window> = windows.values.toList()

    /** Takes every definition, and makes every shared menu's window; their scripts start in [start]. */
    override fun define(project: SessionProject) {
        definitions.clear()
        definitions.putAll(project.running(MenuKind))
        for (definition in definitions.values) if (definition.shared) make(definition.id, definition, start = false)
    }

    /** Starts the menu's script on every shared window. */
    override fun start() {
        for (window in windows.values.toList()) if (window.scope == null && !window.gone) startScripts(window)
    }

    /** Stops every script and takes every window down. Templates go too: the scripts that made them are stopping. */
    override fun stop() {
        for (window in windows.values.toList()) retire(window)
        retiring.clear()
        templates.clear()
    }

    override fun tick(phase: TickPhase) {
        if (phase == TickPhase.UPKEEP) {
            retireUnseen()
            showWorn()
        }
    }

    /** A scope stopped: the templates it made go. */
    override fun scopeReleased(scope: Scope) = removeOwned(scope.id)

    override fun liveness() = listOf(
        liveness<LuaHandle.Menu> { window(it.id) != null },
        liveness<LuaHandle.Slot> { slot -> window(slot.menu)?.let { slot.index in 0 until it.size } == true },
        liveness<LuaHandle.MenuTemplate> { template(it.id) != null }
    )

    override val reloads: Set<KindSpec<*, *>> get() = setOf(MenuKind)

    override fun reload(kind: KindSpec<*, *>, ids: Set<String>, batch: ReloadBatch) {
        for (id in ids) batch.resource(Resource(MenuKind, id), batch.snapshot.running(MenuKind)[id]) { reload(id, it) }
    }

    /** Titles draw the packs' skins, centred by the default font's advances. */
    override val follows: Set<String> get() = setOf(ResourcePackKind.id, DefaultFontKind.id)

    override fun followed(kind: String, ids: Set<String>, batch: ReloadBatch) = refreshTitles()

    // ---- templates -----------------------------------------------------------

    fun template(id: String): Template? = templates[id]

    /** A new template of [definition]'s menu (its id is the template's), owned by the scope [owner]. */
    fun create(definition: (id: String) -> CompiledMenu, owner: Int): Template {
        val id = UUID.randomUUID().toString()
        val template = Template(id, definition(id), owner)
        templates[id] = template
        return template
    }

    /** Every open window made from [template]. */
    fun windowsOf(template: Template): List<Window> = windows.values.filter { it.template == template.id && !it.gone }

    /** Takes [id] away: its windows close and every handler on it goes. False when it was already gone. */
    fun removeTemplate(id: String): Boolean {
        val template = templates.remove(id) ?: return false
        for (window in windowsOf(template)) retire(window)
        scripts.dropTarget(LuaHandle.MenuTemplate(id))
        return true
    }

    /** The scope [owner] stopped: the templates it made go. */
    fun removeOwned(owner: Int) {
        for (template in templates.values.filter { it.owner == owner }) removeTemplate(template.id)
    }

    // ---- opening and closing -----------------------------------------------

    /**
     * Opens [menu] for [player]: the shared window, or a new one of their
     * own (its script's body has run before they see it) holding [context],
     * which is released when the window goes; a shared window takes none.
     * Null when the menu or the player is gone (and [context] is released
     * then too).
     */
    fun open(menu: String, player: PlayerRef, context: LuaRef? = null): Window? {
        val definition = definitions[menu]
        if (definition == null) {
            context?.let(::release)
            return null
        }
        require(context == null || !definition.shared) { "a shared menu's window takes no context" }
        val window = if (definition.shared) {
            window(definition.id) ?: make(definition.id, definition)
        } else {
            make(UUID.randomUUID().toString(), definition, context)
        }
        if (!ops.open(window.uuid, player.uuid)) {
            if (!window.shared) retire(window)
            return null
        }
        opened(window, player)
        return window
    }

    /**
     * Opens a new window of [template] for [player], holding [context] (released
     * when the window goes). Null when the template or the player is gone (and
     * [context] is released then too).
     */
    fun openTemplate(template: String, player: PlayerRef, context: LuaRef? = null): Window? {
        val made = templates[template]
        if (made == null) {
            context?.let(::release)
            return null
        }
        val window = make(UUID.randomUUID().toString(), made.definition, context, made.id)
        if (!ops.open(window.uuid, player.uuid)) {
            retire(window)
            return null
        }
        opened(window, player)
        return window
    }

    /** Shows an existing window to one more player (`menu:open_for`). */
    fun show(window: Window, player: PlayerRef): Boolean {
        if (window.gone || !ops.open(window.uuid, player.uuid)) return false
        opened(window, player)
        return true
    }

    private fun opened(window: Window, player: PlayerRef) {
        menuEvent(window, player, Events.MENU_OPEN, Events.NF_MENU_OPEN)
    }

    /** The server says [player] closed [uuid]. */
    fun closed(uuid: UUID, player: PlayerRef) {
        val window = windowOf(uuid) ?: return
        menuEvent(window, player, Events.MENU_CLOSE, Events.NF_MENU_CLOSE)
        if (!window.shared) retiring += window
    }

    /** `open` or `close` on the window, then on its template, then on `nf`. */
    private fun menuEvent(window: Window, player: PlayerRef, own: EventType<MenuEvent>, server: EventType<MenuEvent>) {
        val handle = LuaHandle.Menu(window.id)
        val template = if (own == Events.MENU_OPEN) Events.MENUTEMPLATE_OPEN else Events.MENUTEMPLATE_CLOSE
        val path = listOfNotNull(own to handle, templateOf(window)?.let { template to it }, server to null)
        scripts.emit(path, MenuEvent(playerHandle(player), handle, window.context))
    }

    /** The handle of the template [window] was made from, while it's there. */
    private fun templateOf(window: Window): LuaHandle.MenuTemplate? =
        window.template?.takeIf { it in templates }?.let { LuaHandle.MenuTemplate(it) }

    /** Retires windows nobody is looking at any more. Once a tick. */
    private fun retireUnseen() {
        if (retiring.isEmpty()) return
        for (window in retiring.toList()) {
            retiring.remove(window)
            if (window.gone || window.shared) continue
            if (ops.viewers(window.uuid).isEmpty()) retire(window)
        }
    }

    // ---- clicks ------------------------------------------------------------

    /** The clicks that a window's `shiftInto` takes over, when they come from the player's own inventory. */
    private val shiftClicks = setOf("shift_left", "shift_right")

    /**
     * A click while [uuid] was open, along its path: the clicked slot (for a
     * click in the window), the window, then `nf`. True means cancel. A
     * locked window starts a click that moves items already cancelled.
     */
    fun click(uuid: UUID, click: MenuClick): Boolean {
        val window = windowOf(uuid) ?: return false
        val shifting = window.shiftInto != null && !click.top && click.click in shiftClicks
        val menu = LuaHandle.Menu(window.id)
        val slot = if (click.top &&
            click.slot != null &&
            click.slot in 0 until window.size
        ) {
            LuaHandle.Slot(window.id, click.slot.toLong())
        } else {
            null
        }
        val event = MenuClickEvent(
            player = playerHandle(click.player),
            menu = menu,
            target = slot,
            index = click.slot?.toLong(),
            inMenu = click.top,
            click = click.click,
            hotbarIndex = click.hotbar?.toLong(),
            item = click.item,
            cursorItem = click.cursor,
            context = window.context
        )
        val path = listOfNotNull(
            slot?.let { Events.SLOT_CLICK to it },
            Events.MENU_CLICK to menu,
            templateOf(window)?.let { Events.MENUTEMPLATE_CLICK to it },
            Events.NF_MENU_CLICK to null
        )
        if (shifting) {
            // The engine moves the stack itself (`shiftInto`), slot by slot, so the game's own move is always stopped.
            if (!scripts.emit(path, event, precancelled = false)) shiftIn(window, click)
            return true
        }
        val rule = click.slot?.takeIf { click.top }?.let { window.rules[it] }
        val limited = rule?.let { ruled(window, click, it) ?: return true }
        // A locked window's bound slots are still the player's to put things in and take them out of, by hand.
        val byHand = click.top && click.slot != null && window.bound(click.slot) && click.click != "double"
        val cancelled = scripts.emit(path, event, precancelled = window.locked && click.movesItems && !byHand)
        if (cancelled || limited == null || limited === ALLOWED) return cancelled
        // More than the rule's max would go in: the engine puts in what fits, and the game's own move is stopped. The
        // client guessed the whole stack went in, so it's sent the window again (the next tick, after the cancel).
        limited()
        ops.refresh(window.uuid)
        return true
    }

    /**
     * What a click on a slot with [rule] does about it: null refuses it (a stack the slot doesn't take); a move to make
     * by hand when only some of the stack may go in; or [ALLOWED] when the game's own move is fine.
     */
    private fun ruled(window: Window, click: MenuClick, rule: Rule): (() -> Unit)? {
        val slot = click.slot ?: return ALLOWED
        val held = item(window, slot)
        when (click.click) {
            "left", "right" -> {
                val cursor = click.cursor ?: return ALLOWED
                if (!rule.takes(cursor)) return null
                val max = rule.max ?: return ALLOWED
                val count = cursor.def.count ?: 1
                val stacks = held != null && ops.room(window.uuid, slot, cursor) > 0
                if (held != null && !stacks) return ALLOWED.takeIf { count <= max }
                val has = held?.def?.count ?: 0
                val wanted = if (click.click == "right") 1 else count
                if (has + wanted <= max) return ALLOWED
                val put = minOf(max - has, wanted, ops.room(window.uuid, slot, cursor))
                if (put <= 0) return null
                return {
                    setItem(window, slot, cursor.copy(def = cursor.def.copy(count = has + put)))
                    val rest = count - put
                    setCursor(window, click.player.uuid, if (rest > 0) cursor.copy(def = cursor.def.copy(count = rest)) else null)
                }
            }
            "number_key" -> {
                val incoming = click.hotbar?.let { inventories.contents(InventoryRef.Player(click.player.uuid))?.getOrNull(it) }
                    ?: return ALLOWED
                return ALLOWED.takeIf { rule.takes(incoming) && (incoming.def.count ?: 1) <= (rule.max ?: Int.MAX_VALUE) }
            }
            else -> return ALLOWED
        }
    }

    /** Whether [rule] (none takes anything) takes [item]. */
    private fun fits(rule: Rule?, item: ItemData): Boolean = rule == null || rule.takes(item)

    /**
     * Moves the stack a shift-click took from the player's inventory into the slots of [window] that show the storage
     * of its `shiftInto`, first slot first: partial stacks it can top up, then empty ones, each taking what the server
     * says it holds. A slot is offered the stack as a click on it (see [offer]). What's left stays in the inventory.
     */
    private fun shiftIn(window: Window, click: MenuClick) {
        val target = window.shiftInto ?: return
        val from = click.slot ?: return
        val moving = click.item ?: return
        val player = click.player
        var left = moving.def.count ?: 1
        val slots = window.bindings.entries
            .filter { it.value.storage.same(target.storage) && it.value.index in target.first..target.last }
            .sortedBy { it.value.index }
            .map { it.key }
        for (partial in listOf(true, false)) {
            for (slot in slots) {
                if (left <= 0) break
                val held = item(window, slot)
                if ((held != null) != partial) continue
                val rule = window.rules[slot]
                if (!fits(rule, moving)) continue
                val room = minOf(ops.room(window.uuid, slot, moving), (rule?.max ?: Int.MAX_VALUE) - (held?.def?.count ?: 0))
                if (room <= 0 || !offer(window, click, slot, moving)) continue
                val amount = minOf(room, left)
                val stack = moving.copy(def = moving.def.copy(count = (held?.def?.count ?: 0) + amount))
                if (!setItem(window, slot, stack)) continue
                left -= amount
            }
        }
        val taken = (moving.def.count ?: 1) - left
        if (taken > 0) {
            val remainder = if (left == 0) null else moving.copy(def = moving.def.copy(count = left))
            inventories.setItem(InventoryRef.Player(player.uuid), from, remainder)
        }
    }

    /** Whether the slot's click handlers let [item] in: they hear it as a click on the slot, and a cancel refuses it there. */
    private fun offer(window: Window, click: MenuClick, slot: Int, item: ItemData): Boolean {
        val target = LuaHandle.Slot(window.id, slot.toLong())
        val event = MenuClickEvent(
            player = playerHandle(click.player),
            menu = LuaHandle.Menu(window.id),
            target = target,
            index = slot.toLong(),
            inMenu = true,
            click = click.click,
            hotbarIndex = null,
            item = item,
            cursorItem = null,
            context = window.context
        )
        return !scripts.emit(listOf(Events.SLOT_CLICK to target), event, precancelled = false)
    }

    /**
     * A drag of [cursor] over [slots] of the window and [ownSlots] of the player's inventory below. A locked window
     * starts one over its own slots already cancelled. True means cancel.
     */
    fun drag(
        uuid: UUID,
        player: PlayerRef,
        slots: List<Int>,
        cursor: ItemData?,
        ownSlots: List<Int> = emptyList(),
        click: String = "left"
    ): Boolean {
        val window = windowOf(uuid) ?: return false
        val menu = LuaHandle.Menu(window.id)
        val event = MenuDragEvent(
            playerHandle(player),
            menu,
            slots.map { it.toLong() },
            ownSlots.map { it.toLong() },
            click,
            cursor,
            window.context
        )
        val path = listOfNotNull(Events.MENU_DRAG to menu, templateOf(window)?.let { Events.MENUTEMPLATE_DRAG to it })
        // A drag puts some of the cursor in each slot: refused over a slot that doesn't take it or holds fewer than it brings.
        val refused = cursor != null &&
            slots.any { slot ->
                val rule = window.rules[slot] ?: return@any false
                !rule.takes(cursor) || (rule.max != null && (cursor.def.count ?: 1) > rule.max)
            }
        if (refused) return true
        return scripts.emit(path, event, precancelled = window.locked && slots.isNotEmpty() && !slots.all { window.bound(it) })
    }

    // ---- storage bindings ------------------------------------------------------

    /**
     * Makes [slot] of [window] show slot [index] of [storage], with what's there now. False when the window is gone or
     * the storage's player is offline. The caller has checked the window isn't shared and the slots are in range.
     */
    fun bind(window: Window, slot: Int, storage: InventoryRef.Storage, index: Int): Boolean {
        if (window.gone) return false
        val held = inventories.contents(storage) ?: return false
        window.worn.remove(slot)
        window.bindings[slot] = Binding(storage, index)
        ops.setItem(window.uuid, slot, held[index])
        return true
    }

    /** Makes [slot] of [window] show what [player] wears in [where]. False when the window is gone or they're offline. */
    fun bindWorn(window: Window, slot: Int, player: UUID, where: String): Boolean {
        if (window.gone || !wardrobe.online(player)) return false
        window.bindings.remove(slot)
        window.worn[slot] = Worn(player, where)
        ops.setItem(window.uuid, slot, wardrobe.worn(player, where))
        return true
    }

    /** Stops [slot] showing a storage's or what someone wears, and empties it: what it showed is still there. False when it wasn't bound. */
    fun unbind(window: Window, slot: Int): Boolean {
        if (window.bindings.remove(slot) == null && window.worn.remove(slot) == null) return false
        ops.setItem(window.uuid, slot, null)
        return true
    }

    fun binding(window: Window, slot: Int): Binding? = window.bindings[slot]

    fun worn(window: Window, slot: Int): Worn? = window.worn[slot]

    /** Gives [slot] of [window] a rule, or takes it away. */
    fun setRule(window: Window, slot: Int, rule: Rule?) {
        if (rule == null) window.rules.remove(slot) else window.rules[slot] = rule
    }

    fun rule(window: Window, slot: Int): Rule? = window.rules[slot]

    /** Every slot bound to what someone wears shows what they wear now: it changes other ways than through the window. */
    private fun showWorn() {
        for (window in windows.values) {
            if (window.gone || window.worn.isEmpty()) continue
            for ((slot, worn) in window.worn.entries.toList()) {
                if (!wardrobe.online(worn.player)) {
                    unbind(window, slot)
                    continue
                }
                val wearing = wardrobe.worn(worn.player, worn.slot)
                if (wearing != ops.item(window.uuid, slot)) ops.setItem(window.uuid, slot, wearing)
            }
        }
    }

    /** The game moved items in [slots] of [uuid]: what the bound ones hold now is the storage's. */
    fun changed(uuid: UUID, slots: Set<Int>) {
        val window = windowOf(uuid) ?: return
        for (slot in slots) wroteThrough(window, slot)
    }

    /** A script changed [storage]: every slot bound to it shows what it has now. */
    fun storageChanged(storage: InventoryRef.Storage) {
        val contents = inventories.contents(storage)
        for (window in windows.values) {
            for ((slot, binding) in window.bindings.entries.toList()) {
                if (!binding.storage.same(storage)) continue
                val held = binding.storage.takeIf { it.size == storage.size }?.let { contents } ?: inventories.contents(binding.storage)
                ops.setItem(window.uuid, slot, held?.getOrNull(binding.index))
            }
        }
    }

    /** Who's looking at a window with a slot bound to [storage]. */
    fun viewersOf(storage: InventoryRef.Storage): List<UUID> = windows.values.filter { window ->
        window.bindings.values.any { it.storage.same(storage) }
    }.flatMap { ops.viewers(it.uuid) }.distinct()

    /** A script wrote [slot] of [window] (already written): when it's bound, the storage holds it too. */
    private fun wroteThrough(window: Window, slot: Int) {
        window.worn[slot]?.let { worn ->
            if (!wardrobe.wear(worn.player, worn.slot, ops.item(window.uuid, slot))) unbind(window, slot)
            return
        }
        val binding = window.bindings[slot] ?: return
        if (!inventories.setItem(binding.storage, binding.index, ops.item(window.uuid, slot))) unbind(window, slot)
    }

    /** Puts every bound slot of [window] back as its storage has it: after it was rebuilt. */
    private fun showBound(window: Window) {
        for ((slot, binding) in window.bindings.entries.toList()) {
            val held = inventories.contents(binding.storage)
            if (held == null) unbind(window, slot) else ops.setItem(window.uuid, slot, held[binding.index])
        }
        for ((slot, worn) in window.worn.entries.toList()) {
            if (wardrobe.online(
                    worn.player
                )
            ) {
                ops.setItem(window.uuid, slot, wardrobe.worn(worn.player, worn.slot))
            } else {
                unbind(window, slot)
            }
        }
    }

    /** [player] left: every slot bound to one of their storages stops showing it. */
    override fun playerQuit(player: PlayerRef) {
        for (window in windows.values) {
            for ((slot, binding) in window.bindings.entries.toList()) if (binding.storage.player == player.uuid) unbind(window, slot)
            for ((slot, worn) in window.worn.entries.toList()) if (worn.player == player.uuid) unbind(window, slot)
        }
    }

    // ---- reload --------------------------------------------------------------

    /**
     * Moves every window of [id] onto [next], or takes them down when [next]
     * is null (the menu was deleted). Returns how many windows kept going.
     *
     * A window whose container and size didn't change is **kept**: its viewers
     * stay, and so do its contents (a shared shop keeps the stock players have
     * bought from it) except the slots whose authored item changed in the
     * file, which take the new item, so editing a slot shows up at once. Title,
     * skin and lock follow the file where the file changed them. A window
     * whose shape changed can't be patched: it's rebuilt from the file and
     * reopened for whoever was looking. Either way its script restarts (it
     * unloads, then its body runs again), and handlers other scripts put on
     * it stay, while its `shiftInto` is forgotten (the body sets it again).
     */
    fun reload(id: String, next: CompiledMenu?): Int {
        val previous = definitions[id]
        val live = windows.values.filter { it.menu == id && !it.gone }
        if (next == null) {
            definitions.remove(id)
            for (window in live) retire(window)
            return 0
        }
        definitions[id] = next
        var kept = 0
        for (window in live) {
            stopScripts(window)
            window.shiftInto = null
            if (previous != null && window.shared && !next.shared) {
                retire(window)
                continue
            }
            if (previous == null || previous.type != next.type || previous.size != next.size) {
                val viewers = ops.viewers(window.uuid)
                ops.destroy(window.uuid)
                window.definition = next
                window.title = defaultTitle(next)
                window.skin = next.skin?.text
                window.locked = next.locked
                build(window)
                window.bindings.keys.removeAll { it >= next.size }
                window.worn.keys.removeAll { it >= next.size }
                window.rules.keys.removeAll { it >= next.size }
                showBound(window)
                for (viewer in viewers) ops.open(window.uuid, viewer)
            } else {
                window.definition = next
                if (previous.title != next.title || previous.name != next.name) window.title = defaultTitle(next)
                if (previous.skin != next.skin) window.skin = next.skin?.text
                if (previous.locked != next.locked) window.locked = next.locked
                for (slot in (previous.slots.keys + next.slots.keys).toSortedSet()) {
                    // A bound slot shows its storage's, whatever the file says.
                    if (window.bound(slot)) continue
                    val before = previous.slots[slot]?.item
                    val after = next.slots[slot]?.item
                    if (before != after) ops.setItem(window.uuid, slot, after?.let { ItemData(it) })
                }
                refreshTitle(window)
                kept++
            }
            startScripts(window)
        }
        if (next.shared && window(id) == null) make(id, next)
        return kept
    }

    /** Re-renders every window's title, for after the packs (and so the skins' characters) were rebuilt. */
    fun refreshTitles() {
        for (window in windows.values) if (!window.gone) refreshTitle(window)
    }

    // ---- the window's own state ----------------------------------------------

    // ---- contents, for scripts ---------------------------------------------------
    // Everything a script does to a window goes through here, never straight
    // to the platform, so there's one place that knows windows.

    fun item(window: Window, slot: Int): ItemData? = ops.item(window.uuid, slot)

    /** Writes a slot (and the storage slot it's bound to); null empties it. False when the item can't be built, or the window is gone. */
    fun setItem(window: Window, slot: Int, item: ItemData?): Boolean {
        if (!ops.setItem(window.uuid, slot, item)) return false
        wroteThrough(window, slot)
        return true
    }

    /** Puts [item] wherever it fits (bound slots too, and so their storage). Returns how many didn't (all of them when the window is gone). */
    fun add(window: Window?, item: ItemData): Int {
        if (window == null) return item.def.count ?: 1
        return ops.add(window.uuid, item).also {
            for (slot in (window.bindings.keys + window.worn.keys).toList()) wroteThrough(window, slot)
        }
    }

    /** Every filled slot. */
    fun items(window: Window): Map<Int, ItemData> =
        (0 until window.size).mapNotNull { slot -> ops.item(window.uuid, slot)?.let { slot to it } }.toMap()

    /** Empties one slot, or every slot when [slot] is null (and the storage slots they're bound to). */
    fun clear(window: Window, slot: Int?): Boolean {
        for (each in slot?.let { listOf(it) } ?: (0 until window.size)) setItem(window, each, null)
        return true
    }

    fun viewers(window: Window): List<UUID> = ops.viewers(window.uuid)

    /** What [player] carries on their cursor while looking at [window]; null for nothing or when they aren't. */
    fun cursor(window: Window, player: UUID): ItemData? = ops.cursor(window.uuid, player)

    /** False when [player] isn't looking at [window], or the item can't be built. */
    fun setCursor(window: Window, player: UUID, item: ItemData?): Boolean = ops.setCursor(window.uuid, player, item)

    fun refresh(window: Window) {
        ops.refresh(window.uuid)
    }

    /** Closes it for [player] (false when they aren't viewing it), or for everyone when null. */
    fun close(window: Window, player: UUID?): Boolean {
        if (player != null && player !in ops.viewers(window.uuid)) return false
        ops.close(window.uuid, player)
        return true
    }

    fun setLocked(window: Window, locked: Boolean) {
        window.locked = locked
    }

    fun setShiftInto(window: Window, target: ShiftInto?) {
        window.shiftInto = target
    }

    fun setTitle(window: Window, title: String?) {
        window.title = title
        refreshTitle(window)
    }

    fun setSkin(window: Window, skin: String?) {
        window.skin = skin
        refreshTitle(window)
    }

    private fun refreshTitle(window: Window) {
        val title = composeTitle(window)
        if (title == window.pushedTitle) return
        window.pushedTitle = title
        ops.retitle(window.uuid, title)
    }

    /**
     * The skin's characters, then the words. A window type that centres its
     * title (a dispenser) moves the zero-wide skin prefix along with the
     * words, so the skin is shifted back by as much: known exactly from the
     * words' width in the default font (none for no words), and left where
     * it falls when that can't be measured (the validator warns about it).
     */
    private fun composeTitle(window: Window): String {
        val words = window.title ?: ""
        val skin = window.skin ?: return words
        val type = window.definition.type
        val width = if (words.isEmpty()) 0 else measure(words)
        val shift = if (width == null) 0 else type.skinShift(width)
        return (skinTitle(skin, shift) ?: "") + words
    }

    // ---- making and retiring windows -----------------------------------------

    private fun make(
        id: String,
        definition: CompiledMenu,
        context: LuaRef? = null,
        template: String? = null,
        start: Boolean = true
    ): Window {
        val window = Window(id, definition, UUID.randomUUID(), context, template)
        windows[id] = window
        byUuid[window.uuid] = window
        build(window)
        if (start) startScripts(window)
        return window
    }

    /** Creates the server's window and fills it from the file. */
    private fun build(window: Window) {
        window.pushedTitle = composeTitle(window)
        ops.create(window.uuid, WindowSpec(window.definition.type, window.definition.size, window.pushedTitle))
        for ((slot, def) in window.definition.slots) {
            def.item?.let { ops.setItem(window.uuid, slot, ItemData(it)) }
        }
    }

    private fun retire(window: Window) {
        if (window.gone) return
        stopScripts(window)
        scripts.dropTarget(LuaHandle.Menu(window.id))
        window.gone = true
        // Only a picture of the storage: nothing to write.
        window.bindings.clear()
        window.worn.clear()
        window.rules.clear()
        windows.remove(window.id)
        byUuid.remove(window.uuid)
        retiring.remove(window)
        ops.destroy(window.uuid)
        window.context?.let(::release)
    }

    private fun release(context: LuaRef) {
        scripts.host?.unref(context)
    }

    /** Runs the menu's script on [window]. */
    private fun startScripts(window: Window) {
        val definition = window.definition
        val script = definition.script ?: return
        val path = placeOf(MenuKind, definition.id).file(script.file)
        val source = readSource(path) ?: return
        window.scope = scripts.start(ScopeOwner.MenuScript(window.id, definition.id, path), script, source).first
    }

    private fun stopScripts(window: Window) {
        window.scope?.let(scripts::close)
        window.scope = null
    }

    private fun playerHandle(player: PlayerRef) = LuaHandle.Player(player.uuid.toString())

    companion object {
        /** A click the game moves itself, as it would without a rule. */
        private val ALLOWED: () -> Unit = {}

        /** The file's title; with no title and no skin, the menu's name, so a plain window isn't blank. */
        fun defaultTitle(definition: CompiledMenu): String? = definition.title ?: definition.name.takeIf { definition.skin == null }
    }
}
