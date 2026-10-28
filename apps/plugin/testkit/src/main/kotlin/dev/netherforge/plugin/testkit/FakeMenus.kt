package dev.netherforge.plugin.testkit

import dev.netherforge.format.dialog.DialogType
import dev.netherforge.plugin.platform.DialogAnswers
import dev.netherforge.plugin.platform.DialogOps
import dev.netherforge.plugin.platform.DialogSpec
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.MenuClick
import dev.netherforge.plugin.platform.MenuOps
import dev.netherforge.plugin.platform.WindowSpec
import java.util.UUID

// Menu windows and dialogs on the fake server.

class FakeWindow(val spec: WindowSpec) {
    var title = spec.title
    var retitles = 0
    var refreshes = 0
    val slots = arrayOfNulls<ItemData>(spec.size)
    val viewers = LinkedHashSet<UUID>()
    val cursors = LinkedHashMap<UUID, ItemData>()
}

class FakeMenus(private val platform: FakePlatform) : MenuOps {
    val windows = LinkedHashMap<UUID, FakeWindow>()

    fun of(player: FakePlayer): FakeWindow? = viewing(player.ref.uuid)?.let { windows[it] }

    override fun create(window: UUID, spec: WindowSpec) {
        windows[window] = FakeWindow(spec)
    }

    override fun destroy(window: UUID) {
        windows.remove(window)
    }

    override fun retitle(window: UUID, title: String) {
        val it = windows[window] ?: return
        it.title = title
        it.retitles++
    }

    override fun item(window: UUID, slot: Int): ItemData? = windows[window]?.slots?.getOrNull(slot)

    override fun setItem(window: UUID, slot: Int, item: ItemData?): Boolean {
        val it = windows[window] ?: return false
        if (slot !in it.slots.indices) return false
        it.slots[slot] = item?.let { given -> platform.stack(given) ?: return false }
        return true
    }

    override fun room(window: UUID, slot: Int, item: ItemData): Int {
        val it = windows[window] ?: return 0
        if (slot !in it.slots.indices) return 0
        val key = item.copy(def = item.def.copy(count = null))
        val held = it.slots[slot]
        return when {
            held == null -> 64
            held.copy(def = held.def.copy(count = null)) == key -> 64 - (held.def.count ?: 1)
            else -> 0
        }
    }

    override fun add(window: UUID, given: ItemData): Int {
        val it = windows[window] ?: return given.def.count ?: 1
        val item = platform.stack(given) ?: return given.def.count ?: 1
        var left = item.def.count ?: 1
        val key = item.copy(def = item.def.copy(count = null))
        for (slot in it.slots.indices) {
            val held = it.slots[slot] ?: continue
            if (held.copy(def = held.def.copy(count = null)) != key) continue
            val moved = minOf(64 - (held.def.count ?: 1), left)
            if (moved <= 0) continue
            it.slots[slot] = held.copy(def = held.def.copy(count = (held.def.count ?: 1) + moved))
            left -= moved
        }
        for (slot in it.slots.indices) {
            if (left <= 0) break
            if (it.slots[slot] != null) continue
            val moved = minOf(64, left)
            it.slots[slot] = item.copy(def = item.def.copy(count = moved))
            left -= moved
        }
        return left
    }

    override fun open(window: UUID, player: UUID): Boolean {
        val target = windows[window] ?: return false
        val who = platform.players.byId[player] ?: return false
        // Opening a window closes whatever was open, as the server does.
        viewing(player)?.takeIf { it != window }?.let { close(it, player) }
        target.viewers += who.ref.uuid
        return true
    }

    override fun close(window: UUID, player: UUID?) {
        val it = windows[window] ?: return
        val closing = if (player == null) it.viewers.toList() else listOf(player).filter { p -> p in it.viewers }
        for (uuid in closing) {
            it.viewers.remove(uuid)
            platform.players.byId[uuid]?.let { who -> platform.events?.menuClosed(window, who.ref) }
        }
    }

    override fun viewers(window: UUID): List<UUID> = windows[window]?.viewers?.toList().orEmpty()

    override fun closeAny(player: UUID): Boolean {
        if (player !in platform.players.byId) return false
        viewing(player)?.let { close(it, player) }
        return true
    }

    override fun viewing(player: UUID): UUID? = windows.entries.firstOrNull { player in it.value.viewers }?.key

    override fun cursor(window: UUID, player: UUID): ItemData? = windows[window]?.takeIf { player in it.viewers }?.cursors?.get(player)

    override fun setCursor(window: UUID, player: UUID, item: ItemData?): Boolean {
        val it = windows[window]?.takeIf { player in it.viewers } ?: return false
        if (item == null) it.cursors.remove(player) else it.cursors[player] = platform.stack(item) ?: return false
        return true
    }

    override fun refresh(window: UUID) {
        windows[window]?.let { it.refreshes++ }
    }

    /**
     * A player clicks a slot of the window they have open, holding what's on their cursor there (and, for a
     * `number_key`, pressing [hotbar]); true when the click was cancelled.
     */
    fun click(
        player: FakePlayer,
        slot: Int?,
        top: Boolean = true,
        click: String = "left",
        moves: Boolean = top,
        hotbar: Int? = null
    ): Boolean {
        val window = viewing(player.ref.uuid) ?: error("${player.ref.name} has no window open")
        val held = when {
            slot == null -> null
            top -> windows.getValue(window).slots.getOrNull(slot)
            else -> player.inventorySlots.getOrNull(slot)
        }
        val cursor = windows.getValue(window).cursors[player.ref.uuid]
        return platform.events!!.menuClicked(window, MenuClick(player.ref, slot, top, click, hotbar, held, cursor, moves))
    }

    /**
     * The game moves items for a click [player] made that wasn't cancelled: each of [slots] of the window they have
     * open now holds its item (null empties it), and the runtime hears it straight away, as the adapter's does.
     */
    fun moved(player: FakePlayer, slots: Map<Int, ItemData?>) {
        val window = viewing(player.ref.uuid) ?: error("${player.ref.name} has no window open")
        val it = windows.getValue(window)
        for ((slot, item) in slots) it.slots[slot] = item?.let { given -> platform.stack(given) }
        platform.events!!.menuChanged(window, slots.keys)
    }

    /**
     * A player drags [cursor] across [slots] of the window they have open and [ownSlots] of their inventory below,
     * with the [click] button; true when it was cancelled.
     */
    fun drag(
        player: FakePlayer,
        slots: List<Int>,
        cursor: ItemData? = null,
        ownSlots: List<Int> = emptyList(),
        click: String = "left"
    ): Boolean {
        val window = viewing(player.ref.uuid) ?: error("${player.ref.name} has no window open")
        val carried = cursor ?: windows.getValue(window).cursors[player.ref.uuid]
        return platform.events!!.menuDragged(window, player.ref, slots, carried, ownSlots, click)
    }
}

class FakeDialogs(private val platform: FakePlatform) : DialogOps {
    val showing = LinkedHashMap<UUID, DialogSpec>()

    override fun show(player: UUID, dialog: DialogSpec): Boolean {
        if (player !in platform.players.byId) return false
        showing[player] = dialog
        return true
    }

    override fun close(player: UUID): Boolean {
        if (player !in platform.players.byId) return false
        showing.remove(player)
        return true
    }

    /** A player presses a button on the dialog on their screen (or one it lists, by [dialog]). */
    fun press(player: FakePlayer, button: String, values: Map<String, Any> = emptyMap(), dialog: String? = null) {
        val shown = showing[player.ref.uuid] ?: error("${player.ref.name} has no dialog open")
        platform.events!!.dialogPressed(player.ref, dialog ?: shown.id, button, values)
    }

    /**
     * A player presses escape on the dialog on their screen, as the client
     * does: a notice's button, a confirmation's second, or the exit action
     * the adapter gives a multi_action or dialog_list (a dialog_list's own
     * button is that exit action), and where a notice or confirmation has
     * no button of the file's there, the exit button the adapter puts in
     * its place.
     */
    fun escape(player: FakePlayer) {
        val shown = showing[player.ref.uuid] ?: error("${player.ref.name} has no dialog open")
        val buttons = shown.file.buttons
        val button = when (shown.file.type ?: DialogType.NOTICE) {
            DialogType.NOTICE -> buttons.firstOrNull()
            DialogType.CONFIRMATION -> buttons.getOrNull(1)
            DialogType.DIALOG_LIST, DialogType.MULTI_ACTION -> null
        }
        if (button != null) press(player, button.key) else exit(player, shown)
    }

    /**
     * A client sends the custom click action [id] (a button of a dialog in
     * the server's registry: the pause screen's, the quick actions key's),
     * with [answers] by input key (a String, a Boolean or a Number). The
     * game takes it whatever the player has open. True when the project's.
     */
    fun click(player: FakePlayer, id: String, answers: Map<String, Any> = emptyMap()): Boolean = platform.events!!.customClicked(
        player.ref,
        id,
        object : DialogAnswers {
            override fun text(key: String) = answers[key] as? String

            override fun bool(key: String) = answers[key] as? Boolean

            override fun number(key: String) = (answers[key] as? Number)?.toDouble()
        }
    )

    private fun exit(player: FakePlayer, shown: DialogSpec) {
        showing.remove(player.ref.uuid)
        platform.events!!.dialogClosed(player.ref, shown.id)
    }
}
