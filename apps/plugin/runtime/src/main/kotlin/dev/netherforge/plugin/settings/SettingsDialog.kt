package dev.netherforge.plugin.settings

import dev.netherforge.format.dialog.BooleanInput
import dev.netherforge.format.dialog.DialogButton
import dev.netherforge.format.dialog.DialogFile
import dev.netherforge.format.dialog.DialogInput
import dev.netherforge.format.dialog.DialogOption
import dev.netherforge.format.dialog.DialogType
import dev.netherforge.format.dialog.MessageBody
import dev.netherforge.format.dialog.OptionInput
import dev.netherforge.format.dialog.RangeInput
import dev.netherforge.format.dialog.TextInput
import dev.netherforge.format.settings.BooleanSetting
import dev.netherforge.format.settings.ChoiceSetting
import dev.netherforge.format.settings.IntegerSetting
import dev.netherforge.format.settings.NumberSetting
import dev.netherforge.format.settings.SettingDef
import dev.netherforge.format.settings.SettingRead
import dev.netherforge.format.settings.StringSetting
import dev.netherforge.plugin.dialog.Dialogs
import dev.netherforge.plugin.platform.DialogSpec
import kotlinx.serialization.json.JsonPrimitive

/**
 * The server-owner settings as an in-game dialog (`/nf settings`), for a
 * server with no editor: one form per package, each setting an input of its
 * kind, and Save writes every value that changed ([OwnerSettings.setText]),
 * as `/nf settings set` would. With several packages, a list to pick one from.
 *
 * Built for one showing from the values as they are, never registered: the
 * runtime answers its presses itself ([Dialogs.RuntimeDialog]), not a script.
 */
internal class SettingsDialog(
    private val settings: OwnerSettings,
    /** MiniMessage's escape: package names and descriptions are the authors' words, not markup. */
    private val escape: (String) -> String,
    /** What came of a Save, as lines for whoever pressed it (MiniMessage). */
    private val tell: (List<String>) -> Unit
) {
    /** The dialog for [namespaces] (every package with settings): one package's form, or a list of them. */
    fun build(namespaces: List<String>): Dialogs.RuntimeDialog {
        val forms = namespaces.map(::form)
        val spec = forms.singleOrNull() ?: DialogSpec(
            LIST,
            DialogFile(title = "Settings", type = DialogType.DIALOG_LIST, body = listOf(MessageBody(INTRO))),
            forms
        )
        return Dialogs.RuntimeDialog(spec, ::pressed)
    }

    private fun form(namespace: String): DialogSpec {
        val values = settings.values(namespace)
        val lines = values.joinToString("\n") { (name, definition) -> "<yellow>$name</yellow> <gray>${escape(definition.description)}" }
        val file = DialogFile(
            title = "Settings: ${escape(settings.packageName(namespace))}",
            externalTitle = escape(settings.packageName(namespace)),
            type = DialogType.CONFIRMATION,
            body = listOf(MessageBody(INTRO), MessageBody(lines)),
            inputs = values.map { (name, definition, value) -> input(name, definition, value) },
            buttons = listOf(DialogButton(SAVE, "<green>Save"), DialogButton(CANCEL, "Cancel"))
        )
        return DialogSpec(PREFIX + namespace, file)
    }

    /** The input a setting is changed with, starting at [value]: its kind's, so a new kind of setting needs one here. */
    private fun input(name: String, definition: SettingDef, value: JsonPrimitive): DialogInput = when (definition) {
        is BooleanSetting -> BooleanInput(name, name, initial = definition.value(value) as Boolean, onTrue = "true", onFalse = "false")
        is IntegerSetting -> {
            val min = definition.min
            val max = definition.max
            if (min != null && max != null && max - min in 1..MAX_SLIDER_STEPS) {
                RangeInput(
                    name,
                    name,
                    start = min.toDouble(),
                    end = max.toDouble(),
                    step = 1.0,
                    initial = definition.value(value).toString().toDouble()
                )
            } else {
                text(name, definition, value)
            }
        }
        is NumberSetting, is StringSetting -> text(name, definition, value)
        is ChoiceSetting -> OptionInput(
            name,
            name,
            options = definition.choices.map { DialogOption(it, escape(it), initial = if (it == value.content) true else null) }
        )
    }

    private fun text(name: String, definition: SettingDef, value: JsonPrimitive): TextInput {
        val shown = definition.show(value)
        return TextInput(name, name, initial = shown, maxLength = maxOf(MAX_TEXT, shown.length))
    }

    /** A press in the list or a form: Save sets every setting whose value changed. */
    private fun pressed(dialog: String, button: String, answers: Map<String, Any>) {
        if (button != SAVE || !dialog.startsWith(PREFIX)) return
        val namespace = dialog.removePrefix(PREFIX)
        val lines = mutableListOf<String>()
        val accepted = LinkedHashMap<String, JsonPrimitive?>()
        for ((name, definition, value) in settings.values(namespace)) {
            val answer = answers[name] ?: continue
            // A slider answers a number (12.0 for a whole one); everything else its words.
            val text = if (answer is Number && answer.toDouble() % 1.0 == 0.0) answer.toLong().toString() else answer.toString()
            if (text == definition.show(value)) continue
            when (val read = definition.parse(text)) {
                is SettingRead.Ok -> accepted[name] = read.json
                is SettingRead.Bad -> lines += "<red>$name: ${escape(read.message)}; it stays ${escape(definition.show(value))}"
            }
        }
        if (accepted.isNotEmpty()) {
            try {
                for (name in settings.setAll(namespace, accepted)) {
                    lines += "<green>$name is now ${escape(settings.definition(namespace, name).show(accepted.getValue(name)!!))}"
                }
            } catch (e: IllegalStateException) {
                lines += "<red>${escape(e.message.orEmpty())}"
            }
        }
        tell(lines.ifEmpty { listOf("<gray>No settings changed.") })
    }

    companion object {
        /** The ids its dialogs go by: `netherforge:settings/<namespace>`, and the list. */
        const val PREFIX = "netherforge:settings/"
        const val LIST = "netherforge:settings"

        const val SAVE = "save"
        const val CANCEL = "cancel"

        private const val INTRO = "<gray>What this server's owner sets for the project. Changes apply at once."

        /** The longest a text box allows, unless the value is already longer. */
        private const val MAX_TEXT = 256

        /** A whole number with a range of at most this many steps is a slider; a wider one is typed. */
        private const val MAX_SLIDER_STEPS = 100L
    }
}
