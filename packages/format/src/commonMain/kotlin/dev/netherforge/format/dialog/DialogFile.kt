package dev.netherforge.format.dialog

import dev.netherforge.format.item.ItemDef
import dev.netherforge.format.ref.Ref
import dev.netherforge.format.ref.RefKind
import dev.netherforge.format.ref.ResourceRef
import dev.netherforge.format.script.ScriptDef
import dev.netherforge.format.text.MiniMessage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `dialogs/<id>/dialog.json`: a screen of text, questions and buttons.
 *
 * A dialog holds nothing: what a player typed arrives with the button press
 * and is gone once handled. So there's one of each dialog, shown to anyone,
 * and no per-player instance. Every button is a callback into Lua, never a
 * command.
 *
 * Body, inputs and buttons are lists because their order is what the player
 * sees. Inputs and buttons have unique `key`s, which are what scripts read.
 */
@Serializable
data class DialogFile(
    @SerialName("\$schema") val schema: String? = null,
    val name: String? = null,
    /** Where the buttons go. Absent is a notice. */
    val type: DialogType? = null,
    /** The bar along the top, MiniMessage. */
    @MiniMessage val title: String,
    /** The label when another dialog lists this one. Defaults to [title]. */
    @MiniMessage val externalTitle: String? = null,
    /** Escape dismisses it. Default true. */
    val canCloseWithEscape: Boolean? = null,
    /** What the screen does after a button press. Default close. */
    val afterAction: AfterAction? = null,
    /** Button columns, for `multi_action` only (1–8, default 2). */
    val columns: Int? = null,
    val body: List<DialogBody> = emptyList(),
    val inputs: List<DialogInput> = emptyList(),
    val buttons: List<DialogButton> = emptyList(),
    /** The dialogs a `dialog_list` offers, by reference (`shop`, `acme:help`). Only for `dialog_list`. */
    @Ref(RefKind.DIALOG) val dialogs: List<ResourceRef> = emptyList(),
    /** The dialog's one script, with `this` the dialog: it hears every button. */
    val script: ScriptDef? = null,
    /**
     * Put on the pause screen: the game lists it with the buttons it adds there.
     * The server reads it from its dialog registry as it starts, so a change restarts the dev server.
     */
    val pauseMenu: Boolean? = null,
    /** Put on the quick actions key's dialog, with the same rules as [pauseMenu]. */
    val quickActions: Boolean? = null
) {
    companion object {
        const val FILE_NAME = "dialog.json"
        const val SCHEMA = "../../.netherforge/schema/dialog.schema.json"
    }
}

@Serializable
enum class DialogType(val buttonLimit: Int?) {
    /** One button: say something, dismiss it. */
    @SerialName("notice")
    NOTICE(1),

    /** Two buttons: yes, then no. */
    @SerialName("confirmation")
    CONFIRMATION(2),

    /** Any number of buttons, in columns. */
    @SerialName("multi_action")
    MULTI_ACTION(null),

    /** A button per listed dialog, built by the game; `buttons` holds at most the exit button. */
    @SerialName("dialog_list")
    DIALOG_LIST(1);

    companion object {
        const val MIN_COLUMNS = 1
        const val MAX_COLUMNS = 8
        const val DEFAULT_COLUMNS = 2
    }
}

@Serializable
enum class AfterAction {
    @SerialName("close")
    CLOSE,

    /** Leave it up, for menus pressed repeatedly. */
    @SerialName("none")
    NONE,

    /** Show the waiting screen until the server sends something else. */
    @SerialName("wait_for_response")
    WAIT_FOR_RESPONSE
}

/**
 * One element of the text above the inputs. A [key] names it, so a script can
 * tell it apart (unique within the dialog when set).
 */
@Serializable
sealed interface DialogBody {
    val key: String?
}

@Serializable
@SerialName("message")
data class MessageBody(
    /** MiniMessage. */
    @MiniMessage val text: String,
    /** Pixels. */
    val width: Int? = null,
    override val key: String? = null
) : DialogBody

@Serializable
@SerialName("item")
data class ItemBody(
    val item: ItemDef,
    /** Text beside the item, MiniMessage. */
    @MiniMessage val description: String? = null,
    val showTooltip: Boolean? = null,
    /** Drawn size in pixels. */
    val width: Int? = null,
    val height: Int? = null,
    override val key: String? = null
) : DialogBody

/** A question answered before pressing a button; its answer arrives under [key]. */
@Serializable
sealed interface DialogInput {
    val key: String
    val label: String?
}

@Serializable
@SerialName("text")
data class TextInput(
    override val key: String,
    @MiniMessage override val label: String? = null,
    val width: Int? = null,
    val initial: String? = null,
    val maxLength: Int? = null,
    /** Rows of a multi-line box; absent or 1 is a single-line field. */
    val lines: Int? = null,
    val labelVisible: Boolean? = null
) : DialogInput

@Serializable
@SerialName("boolean")
data class BooleanInput(
    override val key: String,
    @MiniMessage override val label: String? = null,
    val initial: Boolean? = null,
    /** What a ticked box reads back as. Default `"true"`. */
    val onTrue: String? = null,
    val onFalse: String? = null
) : DialogInput

@Serializable
@SerialName("single_option")
data class OptionInput(
    override val key: String,
    @MiniMessage override val label: String? = null,
    val width: Int? = null,
    val options: List<DialogOption>
) : DialogInput

@Serializable
data class DialogOption(
    /** What the script reads. Unique within the input. */
    val id: String,
    /** What the player reads. Defaults to [id]. */
    @MiniMessage val label: String? = null,
    val initial: Boolean? = null
)

@Serializable
@SerialName("number_range")
data class RangeInput(
    override val key: String,
    @MiniMessage override val label: String? = null,
    val width: Int? = null,
    val start: Double,
    val end: Double,
    val step: Double? = null,
    /** Where the slider starts. Defaults to the middle. */
    val initial: Double? = null
) : DialogInput

@Serializable
data class DialogButton(
    /** What scripts call it: `event.key`, `this:button(key)`. Unique within the dialog. */
    val key: String,
    /** What the player reads. Defaults to [key]. */
    @MiniMessage val label: String? = null,
    @MiniMessage val tooltip: String? = null,
    val width: Int? = null
)
