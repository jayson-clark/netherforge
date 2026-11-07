package dev.netherforge.plugin.platform

import dev.netherforge.format.dialog.DialogFile
import dev.netherforge.format.menu.MenuType
import java.util.UUID

/** What a window looks like: its container, its slot count, and its whole title (skin included) as MiniMessage. */
data class WindowSpec(val type: MenuType, val size: Int, val title: String)

/**
 * Menu windows, each named by a UUID the runtime chooses.
 *
 * A window exists from [create] until [destroy], whether or not anyone is
 * looking. Clicks, drags and closes come back through
 * [PlatformEvents.menuClicked] and friends.
 */
interface MenuOps {
    /** Makes a window nobody is looking at yet. */
    fun create(window: UUID, spec: WindowSpec)

    /** Closes it for everyone and forgets it. No close events are delivered for this. */
    fun destroy(window: UUID)

    /**
     * Changes the title. Minecraft can't retitle an open window, so the adapter
     * rebuilds it, contents and all, and reopens it for every viewer, without
     * reporting those closes and opens as events.
     */
    fun retitle(window: UUID, title: String)

    fun item(window: UUID, slot: Int): ItemData?

    /** Writes a slot; null empties it. False when the item can't be built (an unknown item). */
    fun setItem(window: UUID, slot: Int, item: ItemData?): Boolean

    /** Puts [item] wherever it fits, topping up matching stacks first. Returns how many didn't fit. */
    fun add(window: UUID, item: ItemData): Int

    /**
     * How many of [item]'s count slot [slot] of [window] takes, by the server's own stacking: what's left of a stack of
     * the same item, the whole stack limit for an empty slot, 0 for another item. Only that count, not the item's.
     */
    fun room(window: UUID, slot: Int, item: ItemData): Int

    /** Shows the window to a player. False when they're offline or the window is gone. */
    fun open(window: UUID, player: UUID): Boolean

    /** Closes it for one viewer, or all of them when [player] is null. Close events are delivered. */
    fun close(window: UUID, player: UUID?)

    fun viewers(window: UUID): List<UUID>

    /** Closes whatever window a player has open, ours or not. False when they're offline. */
    fun closeAny(player: UUID): Boolean

    /** The window of ours a player is looking at, or null. */
    fun viewing(player: UUID): UUID?

    /** What [player] carries on their cursor while looking at [window]; null for nothing, or when they aren't looking at it. */
    fun cursor(window: UUID, player: UUID): ItemData?

    /** Puts [item] (null empties it) on the cursor of [player], who must be looking at [window]. False when they aren't, or it can't be built. */
    fun setCursor(window: UUID, player: UUID, item: ItemData?): Boolean

    /** Sends the whole window to its viewers again. */
    fun refresh(window: UUID)
}

/** One click while one of our windows is open, as the adapter saw it. */
data class MenuClick(
    val player: PlayerRef,
    /** In the window when [top], in the player's own inventory otherwise; null for a click outside both. */
    val slot: Int?,
    val top: Boolean,
    /** `left`, `right`, `shift_left`, `shift_right`, `middle`, `number_key`, `double`, `drop`, `control_drop` or `other`. */
    val click: String,
    val hotbar: Int?,
    val item: ItemData?,
    val cursor: ItemData?,
    /** Whether the click would move items into or out of the window: one in it, or a shift-click or collect from below. */
    val movesItems: Boolean
)

/** A dialog to build and show, with the dialogs a `dialog_list` offers (built the same way). */
data class DialogSpec(val id: String, val file: DialogFile, val listed: List<DialogSpec> = emptyList())

/**
 * What a player answered in a dialog, by input key; null for a key the answer
 * lacks or that isn't that kind of input. Any thread.
 */
interface DialogAnswers {
    fun text(key: String): String?

    fun bool(key: String): Boolean?

    fun number(key: String): Double?
}

/**
 * Dialog screens, built per show and never registered. Every button is a
 * callback that comes back as [PlatformEvents.dialogPressed]; none is a
 * command. Every dialog type with an exit action gets one whose callback is
 * [PlatformEvents.dialogClosed] (a `dialog_list`'s own button becomes it), and
 * so does a notice's or confirmation's missing button, so escape is reported
 * wherever it isn't a press of the file's button.
 *
 * The dialogs of the pause screen and the quick actions key are the one
 * exception: they're in the server's dialog registry (the start-up datapack),
 * and their buttons are custom click actions the game sends back as
 * [PlatformEvents.customClicked].
 */
interface DialogOps {
    /** False when the player is offline or the dialog couldn't be built. */
    fun show(player: UUID, dialog: DialogSpec): Boolean

    /** Takes whatever dialog is on their screen away. False when they're offline. */
    fun close(player: UUID): Boolean
}
