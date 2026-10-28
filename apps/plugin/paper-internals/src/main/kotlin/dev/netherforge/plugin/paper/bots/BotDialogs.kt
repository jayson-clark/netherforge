package dev.netherforge.plugin.paper.bots

import dev.netherforge.format.bridge.BotDialog
import dev.netherforge.format.bridge.BotDialogInput
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.ByteTag
import net.minecraft.nbt.FloatTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.ClickEvent
import net.minecraft.server.dialog.ActionButton
import net.minecraft.server.dialog.ConfirmationDialog
import net.minecraft.server.dialog.Dialog
import net.minecraft.server.dialog.DialogListDialog
import net.minecraft.server.dialog.MultiActionDialog
import net.minecraft.server.dialog.NoticeDialog
import net.minecraft.server.dialog.ServerLinksDialog
import net.minecraft.server.dialog.action.Action
import net.minecraft.server.dialog.body.ItemBody
import net.minecraft.server.dialog.body.PlainMessage
import net.minecraft.server.dialog.input.BooleanInput
import net.minecraft.server.dialog.input.NumberRangeInput
import net.minecraft.server.dialog.input.SingleOptionInput
import net.minecraft.server.dialog.input.TextInput
import kotlin.math.floor

/**
 * Dialogs as the client shows and presses them. A dialog arrives whole (the
 * server's own model), so pressing a button is what the client's screen
 * does: read each input's value, let the button's action make its click
 * event from them, and send that.
 */
internal object BotDialogs {
    /** What pressing a dialog button does: run an action, or (in a dialog list) show another dialog. */
    sealed interface Press {
        data class Run(val action: Action?) : Press

        data class Show(val dialog: Dialog) : Press
    }

    fun summary(dialog: Dialog): BotDialog {
        val common = dialog.common()
        return BotDialog(
            type = BuiltInRegistries.DIALOG_TYPE.getKey(dialog.codec())?.path ?: "unknown",
            title = common.title().getString(),
            body = common.body().mapNotNull {
                when (it) {
                    is PlainMessage -> it.contents().getString()
                    is ItemBody -> it.description().map { message ->
                        message.contents().getString()
                    }.orElse(Protocol.item(it).hoverName.getString())
                    else -> null
                }
            },
            inputs = common.inputs().map { input ->
                when (val control = input.control()) {
                    is TextInput -> BotDialogInput(input.key(), "text", control.label().getString(), JsonPrimitive(control.initial()))
                    is BooleanInput -> BotDialogInput(input.key(), "boolean", control.label().getString(), JsonPrimitive(control.initial()))
                    is NumberRangeInput -> BotDialogInput(
                        input.key(),
                        "number",
                        control.label().getString(),
                        JsonPrimitive(initial(control))
                    )
                    is SingleOptionInput -> BotDialogInput(
                        input.key(),
                        "option",
                        control.label().getString(),
                        initial(control)?.let(::JsonPrimitive),
                        control.entries().map { it.id() }
                    )
                    else -> BotDialogInput(input.key(), "unknown", "")
                }
            },
            buttons = buttons(dialog).map { it.first },
            canEscape = common.canCloseWithEscape()
        )
    }

    /** The dialog's buttons in the order the client lays them out (an exit button last), with what each does. */
    fun buttons(dialog: Dialog): List<Pair<String, Press>> {
        fun button(it: ActionButton) = it.button().label().getString() to Press.Run(it.action().orElse(null))
        return when (dialog) {
            is NoticeDialog -> listOf(button(dialog.action()))
            is ConfirmationDialog -> listOf(button(dialog.yesButton()), button(dialog.noButton()))
            is MultiActionDialog -> dialog.actions().map(::button) + listOfNotNull(dialog.exitAction().orElse(null)?.let(::button))
            is DialogListDialog ->
                dialog.dialogs().map { it.value().common().computeExternalTitle().getString() to Press.Show(it.value()) } +
                    listOfNotNull(dialog.exitAction().orElse(null)?.let(::button))
            is ServerLinksDialog -> listOfNotNull(dialog.exitAction().orElse(null)?.let(::button))
            else -> emptyList()
        }
    }

    /**
     * The click event pressing a button with [action] makes, with each input
     * at the value [given] (by key) or its initial one. Unknown keys and
     * values of the wrong kind are errors, as a person couldn't type them.
     */
    fun click(dialog: Dialog, action: Action, given: Map<String, JsonElement>): ClickEvent? {
        val inputs = dialog.common().inputs()
        val unknown = given.keys - inputs.map { it.key() }.toSet()
        require(unknown.isEmpty()) {
            "the dialog has no input ${unknown.joinToString {
                "\"$it\""
            }}; it has ${inputs.joinToString { "\"${it.key()}\"" }.ifEmpty { "none" }}"
        }
        val values = inputs.associate { input -> input.key() to value(input.key(), input.control(), given[input.key()]) }
        return action.createAction(values).orElse(null)
    }

    private fun value(key: String, control: Any, given: JsonElement?): Action.ValueGetter {
        val primitive = given as? JsonPrimitive
        fun wrong(kind: String): Nothing = throw IllegalArgumentException("input \"$key\" takes $kind, not $given")
        return when (control) {
            is TextInput -> {
                val text = if (given == null) control.initial() else primitive?.takeIf { it.isString }?.content ?: wrong("a string")
                require(text.length <= control.maxLength()) { "input \"$key\" takes at most ${control.maxLength()} characters" }
                Action.ValueGetter.of(text)
            }
            is BooleanInput -> {
                val on = if (given == null) control.initial() else primitive?.booleanOrNull ?: wrong("true or false")
                getter(if (on) control.onTrue() else control.onFalse(), ByteTag.valueOf(on))
            }
            is NumberRangeInput -> {
                val number = if (given == null) initial(control) else primitive?.takeIf { !it.isString }?.floatOrNull ?: wrong("a number")
                val range = control.rangeInfo()
                require(number in minOf(range.start(), range.end())..maxOf(range.start(), range.end())) {
                    "input \"$key\" takes a number from ${range.start()} to ${range.end()}"
                }
                // As the client's slider reports it: whole numbers without a fraction.
                getter(if (floor(number) == number) number.toInt().toString() else number.toString(), FloatTag.valueOf(number))
            }
            is SingleOptionInput -> {
                val ids = control.entries().map { it.id() }
                val id = if (given == null) initial(control) else primitive?.takeIf { it.isString }?.content ?: wrong("an option's id")
                require(id != null && id in ids) { "input \"$key\" takes one of ${ids.joinToString { "\"$it\"" }}" }
                Action.ValueGetter.of(id)
            }
            else -> wrong("nothing a bot can set")
        }
    }

    private fun getter(text: String, tag: Tag) = object : Action.ValueGetter {
        override fun asTemplateSubstitution() = text

        override fun asTag() = tag
    }

    private fun initial(control: NumberRangeInput): Float = control.rangeInfo().computeScaledValue(control.rangeInfo().initialSliderValue())

    private fun initial(control: SingleOptionInput): String? = (
        control.entries().firstOrNull { it.initial() }
            ?: control.entries().firstOrNull()
        )?.id()
}
