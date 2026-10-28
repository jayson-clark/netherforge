package dev.netherforge.plugin.paper

import dev.netherforge.format.dialog.AfterAction
import dev.netherforge.format.dialog.BooleanInput
import dev.netherforge.format.dialog.DialogButton
import dev.netherforge.format.dialog.DialogInput
import dev.netherforge.format.dialog.DialogType
import dev.netherforge.format.dialog.ItemBody
import dev.netherforge.format.dialog.MessageBody
import dev.netherforge.format.dialog.OptionInput
import dev.netherforge.format.dialog.RangeInput
import dev.netherforge.format.dialog.TextInput
import dev.netherforge.plugin.dialog.Dialogs
import dev.netherforge.plugin.platform.DialogAnswers
import dev.netherforge.plugin.platform.DialogOps
import dev.netherforge.plugin.platform.DialogSpec
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.PlatformEvents
import dev.netherforge.plugin.platform.PlayerRef
import io.papermc.paper.connection.PlayerGameConnection
import io.papermc.paper.dialog.Dialog
import io.papermc.paper.dialog.DialogResponseView
import io.papermc.paper.event.player.PlayerCustomClickEvent
import io.papermc.paper.registry.RegistryKey
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput
import io.papermc.paper.registry.data.dialog.input.TextDialogInput
import io.papermc.paper.registry.set.RegistrySet
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickCallback
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.time.Duration
import java.util.UUID
import java.util.logging.Logger
import io.papermc.paper.registry.data.dialog.type.DialogType as PaperDialogType

/**
 * [DialogOps] with Paper's dialog API.
 *
 * A dialog is built on every show and handed to `Player#showDialog`; nothing
 * is put in the dialog registry (that would have to happen at bootstrap, long
 * before a project loads). Every button is `DialogAction.customClick` with a
 * **callback**: Paper hands it the response (every input, by key) and the
 * player, so the press reaches Lua with the answers in hand. No button is a
 * command, so there's no command to register, no string to parse and nothing
 * a player could type to fake a press.
 *
 * Callbacks are unlimited-use and long-lived: a dialog with `afterAction:
 * none` is pressed many times, and Adventure's default (one use, a short
 * expiry) would make the second press silently do nothing.
 *
 * A `dialog_list`'s dialogs are built inline the same way, so their buttons
 * call back too.
 *
 * The one thing registered is what the start-up datapack holds: the dialogs
 * of the pause screen and the quick actions key, whose buttons are
 * `dynamic/custom` actions with no callback to build one from. The game
 * sends their clicks as [customClicked] (a `PlayerCustomClickEvent`), which
 * the runtime routes to the dialog's script.
 *
 * Leaving a dialog reaches the server only through its exit action (escape
 * runs it too), so a `multi_action` dialog gets a "Back" exit button whose
 * callback is [PlatformEvents.dialogClosed], and a `dialog_list`'s own
 * button becomes that exit button (its label, NetherForge's callback). A
 * notice's escape is its button, a confirmation's its second: presses. Where
 * the file leaves one of those out, the exit button stands in for it, so
 * escape there is reported as leaving.
 */
class PaperDialogs(private val plugin: Plugin, private val logger: Logger) : DialogOps {
    private val mini = PaperText.mini

    /** Where presses go; set once the runtime exists. */
    var events: PlatformEvents? = null

    override fun show(player: UUID, dialog: DialogSpec): Boolean {
        val who = Bukkit.getPlayer(player) ?: return false
        val built = runCatching { build(dialog) }.getOrElse {
            logger.warning("Couldn't build dialog ${dialog.id}: ${it.message}")
            return false
        }
        who.showDialog(built)
        return true
    }

    override fun close(player: UUID): Boolean {
        val who = Bukkit.getPlayer(player) ?: return false
        who.closeDialog()
        return true
    }

    private fun build(spec: DialogSpec): Dialog = Dialog.create { factory ->
        val file = spec.file
        val base = DialogBase.builder(text(file.title))
            .canCloseWithEscape(file.canCloseWithEscape ?: true)
            .afterAction(
                when (file.afterAction ?: AfterAction.CLOSE) {
                    AfterAction.CLOSE -> DialogBase.DialogAfterAction.CLOSE
                    AfterAction.NONE -> DialogBase.DialogAfterAction.NONE
                    AfterAction.WAIT_FOR_RESPONSE -> DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE
                }
            )
        file.externalTitle?.let { base.externalTitle(text(it)) }
        if (file.body.isNotEmpty()) base.body(file.body.mapNotNull(::body))
        if (file.inputs.isNotEmpty()) base.inputs(file.inputs.map(::input))
        val buttons = file.buttons.map { button(spec, it) }
        val columns = (file.columns ?: dev.netherforge.format.dialog.DialogType.DEFAULT_COLUMNS)
        val type = when (file.type ?: DialogType.NOTICE) {
            DialogType.NOTICE -> PaperDialogType.notice(buttons.firstOrNull() ?: exit(spec, translated("gui.ok")))
            DialogType.CONFIRMATION -> PaperDialogType.confirmation(
                buttons.getOrElse(0) { exit(spec, translated("gui.yes")) },
                buttons.getOrElse(1) { exit(spec, translated("gui.no")) }
            )
            DialogType.MULTI_ACTION -> PaperDialogType.multiAction(buttons).columns(columns).exitAction(exit(spec)).build()
            // Its one button is its exit action, so it reports leaving: labelled as the file says.
            DialogType.DIALOG_LIST -> PaperDialogType.dialogList(RegistrySet.valueSet(RegistryKey.DIALOG, spec.listed.map(::build)))
                .exitAction(file.buttons.firstOrNull()?.let { exit(spec, it) } ?: exit(spec))
                .columns(columns)
                .build()
        }
        factory.empty().base(base.build()).type(type)
    }

    private fun body(entry: dev.netherforge.format.dialog.DialogBody): DialogBody? = when (entry) {
        is MessageBody -> entry.width?.let { DialogBody.plainMessage(text(entry.text), it) } ?: DialogBody.plainMessage(text(entry.text))
        is ItemBody -> PaperItems.toStack(ItemData(entry.item))?.let { stack ->
            DialogBody.item(stack).apply {
                entry.description?.let { description(DialogBody.plainMessage(text(it))) }
                entry.showTooltip?.let { showTooltip(it) }
                entry.width?.let { width(it) }
                entry.height?.let { height(it) }
            }.build()
        }
    }

    private fun input(entry: DialogInput): io.papermc.paper.registry.data.dialog.input.DialogInput {
        val label = text(entry.label ?: entry.key)
        return when (entry) {
            is TextInput -> io.papermc.paper.registry.data.dialog.input.DialogInput.text(entry.key, label).apply {
                entry.width?.let { width(it) }
                entry.initial?.let { initial(it) }
                entry.maxLength?.let { maxLength(it) }
                entry.labelVisible?.let { labelVisible(it) }
                entry.lines?.takeIf { it > 1 }?.let { multiline(TextDialogInput.MultilineOptions.create(it, null)) }
            }.build()
            is BooleanInput -> io.papermc.paper.registry.data.dialog.input.DialogInput.bool(entry.key, label)
                .initial(entry.initial ?: false)
                .onTrue(entry.onTrue ?: "true")
                .onFalse(entry.onFalse ?: "false")
                .build()
            is OptionInput -> io.papermc.paper.registry.data.dialog.input.DialogInput.singleOption(
                entry.key,
                label,
                entry.options.map { SingleOptionDialogInput.OptionEntry.create(it.id, text(it.label ?: it.id), it.initial ?: false) }
            ).apply { entry.width?.let { width(it) } }.build()
            is RangeInput -> io.papermc.paper.registry.data.dialog.input.DialogInput.numberRange(
                entry.key,
                label,
                entry.start.toFloat(),
                entry.end.toFloat()
            ).apply {
                entry.width?.let { width(it) }
                entry.step?.let { step(it.toFloat()) }
                entry.initial?.let { initial(it.toFloat()) }
            }.build()
        }
    }

    private fun button(spec: DialogSpec, button: DialogButton): ActionButton = ActionButton.builder(text(button.label ?: button.key))
        .apply {
            button.tooltip?.let { tooltip(text(it)) }
            button.width?.let { width(it) }
        }
        .action(
            DialogAction.customClick(
                { view, audience ->
                    val player = audience as? Player ?: return@customClick
                    val ref = PlayerRef(player.uniqueId, player.name)
                    val values = values(spec, view)
                    // Scripts run on the main thread; a callback may arrive on a network thread.
                    val press = { events?.dialogPressed(ref, spec.id, button.key, values) }
                    if (Bukkit.isPrimaryThread()) press() else Bukkit.getScheduler().runTask(plugin, Runnable { press() })
                },
                ClickCallback.Options.builder().uses(ClickCallback.UNLIMITED_USES).lifetime(CALLBACK_LIFETIME).build()
            )
        )
        .build()

    /** An exit button labelled as the file's [button] says (a `dialog_list`'s own), reported as leaving. */
    private fun exit(spec: DialogSpec, button: DialogButton): ActionButton = exit(spec, text(button.label ?: button.key)) {
        button.tooltip?.let { tooltip(text(it)) }
        button.width?.let { width(it) }
    }

    /**
     * The exit button NetherForge adds, so leaving the dialog (Escape runs it
     * too) is reported as [PlatformEvents.dialogClosed]. Also where a notice or
     * confirmation lacks a button of the file's, so every way out of it is.
     */
    private fun exit(
        spec: DialogSpec,
        label: Component = translated("gui.back"),
        style: ActionButton.Builder.() -> Unit = {}
    ): ActionButton = ActionButton.builder(label)
        .apply(style)
        .action(
            DialogAction.customClick(
                { _, audience ->
                    val player = audience as? Player ?: return@customClick
                    val ref = PlayerRef(player.uniqueId, player.name)
                    val close = { events?.dialogClosed(ref, spec.id) }
                    if (Bukkit.isPrimaryThread()) close() else Bukkit.getScheduler().runTask(plugin, Runnable { close() })
                },
                ClickCallback.Options.builder().uses(ClickCallback.UNLIMITED_USES).lifetime(CALLBACK_LIFETIME).build()
            )
        )
        .build()

    /**
     * A custom click from the game: the button of a dialog in the server's
     * registry (the pause screen's, the quick actions key's, which the
     * start-up datapack holds), which the runtime answers when it's the
     * project's.
     */
    fun customClicked(event: PlayerCustomClickEvent) {
        val player = (event.commonConnection as? PlayerGameConnection)?.player ?: return
        val view = event.dialogResponseView
        val id = event.identifier.asString()
        val ref = PlayerRef(player.uniqueId, player.name)
        val answers = object : DialogAnswers {
            override fun text(key: String) = view?.getText(key)

            override fun bool(key: String) = view?.getBoolean(key)

            override fun number(key: String) = view?.getFloat(key)?.toDouble()
        }
        // Scripts run on the main thread; the click may arrive on a network thread.
        if (Bukkit.isPrimaryThread()) {
            events?.customClicked(ref, id, answers)
        } else {
            Bukkit.getScheduler().runTask(plugin, Runnable { events?.customClicked(ref, id, answers) })
        }
    }

    /** What the player answered, by input key ([Dialogs.values]). */
    private fun values(spec: DialogSpec, view: DialogResponseView): Map<String, Any> = Dialogs.values(
        spec.file,
        object : DialogAnswers {
            override fun text(key: String) = view.getText(key)

            override fun bool(key: String) = view.getBoolean(key)

            override fun number(key: String) = view.getFloat(key)?.toDouble()
        }
    )

    private fun translated(key: String): Component = Component.translatable(key)

    private fun text(miniMessage: String): Component = mini.deserialize(miniMessage)

    private companion object {
        val CALLBACK_LIFETIME: Duration = Duration.ofDays(1)
    }
}
