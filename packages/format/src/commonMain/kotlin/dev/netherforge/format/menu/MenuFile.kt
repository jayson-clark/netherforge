package dev.netherforge.format.menu

import dev.netherforge.format.item.ItemDef
import dev.netherforge.format.ref.Ref
import dev.netherforge.format.ref.RefKind
import dev.netherforge.format.ref.ResourceRef
import dev.netherforge.format.script.ScriptDef
import dev.netherforge.format.text.MiniMessage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `menus/<id>/menu.json`: a window you can put in front of a player.
 *
 * Slots are an object keyed by slot index (`"13"`), so two people filling
 * different squares merge cleanly and a square can't be described twice.
 */
@Serializable
data class MenuFile(
    @SerialName("\$schema") val schema: String? = null,
    val name: String? = null,
    /** The container. Absent is a chest. */
    val type: MenuType? = null,
    /** Rows of nine, for a chest (1–6, default 3). Not allowed on other types. */
    val rows: Int? = null,
    /** The bar along the top, MiniMessage. With a [skin], it's drawn over the artwork. */
    @MiniMessage val title: String? = null,
    /** `<pack>/<key>`: a GUI background from one of the project's resource packs. */
    @Ref(RefKind.SKIN) val skin: ResourceRef? = null,
    /**
     * One instance for the whole server (a shop's stock) instead of one per
     * player (a menu). Default false.
     */
    val shared: Boolean? = null,
    /** Clicks never move items; scripts hear them instead. Default true: these are menus more often than chests. */
    val locked: Boolean? = null,
    val slots: Map<String, SlotDef> = emptyMap(),
    /** The menu's one script: a copy runs per window, with `this` the window. */
    val script: ScriptDef? = null
) {
    companion object {
        const val FILE_NAME = "menu.json"
        const val SCHEMA = "../../.netherforge/schema/menu.schema.json"
    }
}

/** What starts in one square. */
@Serializable
data class SlotDef(val item: ItemDef? = null)

/**
 * The container types a menu may be: only those whose every slot is
 * plain storage. A furnace has slots the server itself has opinions about,
 * and a menu built on one fights vanilla behaviour in half its squares.
 */
@Serializable
enum class MenuType(val id: String, val fixedSlots: Int, val columns: Int) {
    @SerialName("chest")
    CHEST("chest", 0, 9),

    @SerialName("barrel")
    BARREL("barrel", 27, 9),

    @SerialName("shulker_box")
    SHULKER_BOX("shulker_box", 27, 9),

    @SerialName("hopper")
    HOPPER("hopper", 5, 5),

    @SerialName("dispenser")
    DISPENSER("dispenser", 9, 3),

    @SerialName("dropper")
    DROPPER("dropper", 9, 3),

    @SerialName("crafter")
    CRAFTER("crafter", 9, 3);

    /** Slots for a window of this type with [rows] rows (only a chest uses it). */
    fun size(rows: Int): Int = if (this == CHEST) rows * 9 else fixedSlots

    /** Whether the game centres this window's title on its width (the others start it at the left). */
    val centresTitle: Boolean get() = this == DISPENSER || this == DROPPER || this == CRAFTER

    /**
     * Where the title starts, in pixels from the window's left edge, when its
     * words are [wordsWidth] wide: [TITLE_X], or centred on [WINDOW_WIDTH] the
     * way the game centres it (integer division, as the client does).
     */
    fun titleX(wordsWidth: Int): Int = if (centresTitle) (WINDOW_WIDTH - wordsWidth) / 2 else TITLE_X

    /**
     * How far a skin must move so it lands where it would on a window whose
     * title starts at [TITLE_X]: 0 unless the title is centred, which moves the
     * zero-wide skin prefix along with the words.
     */
    fun skinShift(wordsWidth: Int): Int = TITLE_X - titleX(wordsWidth)

    companion object {
        const val DEFAULT_ROWS = 3
        const val MAX_ROWS = 6

        /** Every container screen this allows is this many GUI pixels wide. */
        const val WINDOW_WIDTH = 176

        /** Where a title that isn't centred starts. */
        const val TITLE_X = 8
    }
}

/** A validated menu, slots resolved to indices. */
class CompiledMenu(
    val id: String,
    val name: String?,
    val type: MenuType,
    val rows: Int,
    val size: Int,
    val title: String?,
    val skin: ResourceRef?,
    val shared: Boolean,
    val locked: Boolean,
    /** By index, ascending. */
    val slots: Map<Int, SlotDef>,
    val script: ScriptDef?
) {
    fun slot(index: Int): SlotDef? = slots[index]
}
