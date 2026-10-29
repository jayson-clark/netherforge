package dev.netherforge.plugin.dialog

import dev.netherforge.format.dialog.AfterAction
import dev.netherforge.format.dialog.BooleanInput
import dev.netherforge.format.dialog.DialogFile
import dev.netherforge.format.dialog.DialogInput
import dev.netherforge.format.dialog.DialogJson
import dev.netherforge.format.dialog.DialogKind
import dev.netherforge.format.dialog.DialogType
import dev.netherforge.format.dialog.ItemBody
import dev.netherforge.format.dialog.MessageBody
import dev.netherforge.format.dialog.OptionInput
import dev.netherforge.format.dialog.RangeInput
import dev.netherforge.format.dialog.TextInput
import dev.netherforge.format.project.KindSpec
import dev.netherforge.format.project.ResourcePlace
import dev.netherforge.format.ref.ResourceRef
import dev.netherforge.plugin.binding.DialogEvent
import dev.netherforge.plugin.binding.DialogPressEvent
import dev.netherforge.plugin.binding.Events
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.binding.StringOrNumber
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.lua.LuaRef
import dev.netherforge.plugin.platform.DialogAnswers
import dev.netherforge.plugin.platform.DialogOps
import dev.netherforge.plugin.platform.DialogSpec
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.project.Resource
import dev.netherforge.plugin.script.Scope
import dev.netherforge.plugin.script.ScopeOwner
import dev.netherforge.plugin.script.Scripts
import dev.netherforge.plugin.session.ReloadBatch
import dev.netherforge.plugin.session.RuntimeService
import dev.netherforge.plugin.session.SessionProject
import dev.netherforge.plugin.session.liveness
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.util.UUID

/**
 * The project's dialogs and the script behind each.
 *
 * A dialog holds nothing (what a player typed arrives with the press and is
 * gone once handled), so there's one of each, not one per viewer: its script
 * starts at load and runs until it's reloaded, and showing it is the only thing
 * to do with it. It's built for the server on every show, never registered,
 * so a reload changes what the next show draws.
 *
 * Every button is a callback that arrives as [pressed], with every input's
 * answer. A press is heard by the button's handlers, then the dialog's, then
 * `nf.on("dialog_press")`, until one stops it.
 *
 * `close`: Minecraft reports leaving a dialog only through
 * its exit action, so the platform gives `multi_action` and `dialog_list`
 * dialogs one that calls back as [closed]; escape on a notice or confirmation
 * is a press of one of its buttons. Closes the server causes nothing reports,
 * so this keeps which dialog each player has open and fires `close` itself
 * when a script closes it ([close]) or opens another over it ([show]). A
 * disconnect fires nothing (`player_quit` covers it): [forget].
 *
 * Each opening may carry a `context`: any Lua value,
 * kept here with the opening, never sent to the player, and handed to every
 * press and the `close` of that opening as `event.context`. It's released
 * when the dialog leaves their screen. The opening's `values`, `title` and
 * `body` change only what this show draws ([opened]).
 *
 * A dialog on the pause screen or the quick actions key (`pauseMenu`,
 * `quickActions`) is in the server's registry, built once at start-up, not
 * per show: its buttons are custom clicks that come back as [registryClicked]
 * and are then presses and closes like any other.
 *
 * A script can also make a dialog from a table (`nf.dialogs.create`): a
 * record like the files', with a made-up id that never
 * names a folder, no script, and an [owner] scope it goes with ([removeOwned]).
 * Everything else (showing it, presses, `close`, `ask`) is the same.
 */
class Dialogs(
    private val ops: DialogOps,
    private val scripts: Scripts,
    private val readSource: (String) -> String?,
    /** Where a resource is now (in its group folders), by kind and id: where its script is read from. */
    private val placeOf: (KindSpec<*, *>, String) -> ResourcePlace,
    /** The project's namespace, which references naming none are in. */
    private val namespace: () -> String,
    private val onIgnored: (String) -> Unit
) : RuntimeService {
    override val name get() = "dialogs"

    private class Record(val id: String, val file: DialogFile, val owner: Int? = null) {
        /** The dialog's script's scope, while it runs. */
        var scope: Scope? = null
    }

    private val records = LinkedHashMap<String, Record>()

    /** One showing of a dialog to a player, and the context it was opened with. */
    private class Opening(val dialog: String, val context: LuaRef?)

    /** The project dialog on each player's screen, as far as anything has told us. */
    private val open = HashMap<UUID, Opening>()

    /**
     * A dialog the runtime shows itself rather than a script (the server-owner
     * settings dialog, `/nf settings`): built by its caller, belonging to no
     * project dialog, and answered by [pressed] alone (with the id of the
     * dialog pressed in, [spec]'s or one it lists). Escape and the exit button
     * just take it away.
     */
    class RuntimeDialog(val spec: DialogSpec, val pressed: (dialog: String, button: String, values: Map<String, Any>) -> Unit) {
        val ids: Set<String> = buildSet {
            fun add(spec: DialogSpec) {
                if (add(spec.id)) spec.listed.forEach(::add)
            }
            add(spec)
        }
    }

    /** The runtime's own dialog on each player's screen ([showRuntime]). */
    private val runtimeDialogs = HashMap<UUID, RuntimeDialog>()

    fun file(id: String): DialogFile? = records[id]?.file

    /** The id of the project dialog [reference] names (`welcome`, `shop:welcome`), or null when it names none. */
    fun idOf(reference: ResourceRef): String? = reference.nameIn(namespace())?.takeIf { it in records }

    /** The project's dialogs (not those scripts made), for completions and messages. */
    fun ids(): List<String> = records.values.filter { it.owner == null }.map { it.id }.sorted()

    /** Whether [id] is a dialog a script made rather than one of the project's. */
    fun isCreated(id: String): Boolean = records[id]?.owner != null

    /** A dialog made from [file] by the scope [owner], until that scope stops. Returns its id. */
    fun create(file: DialogFile, owner: Int): String {
        val id = UUID.randomUUID().toString()
        records[id] = Record(id, file, owner)
        return id
    }

    /** The scope [owner] stopped: the dialogs it made go, and every handler on them. */
    fun removeOwned(owner: Int) {
        for (record in records.values.filter { it.owner == owner }) {
            records.remove(record.id)
            scripts.dropTarget(LuaHandle.Dialog(record.id))
        }
    }

    /** Takes every dialog. Their scripts start in [start]. */
    override fun define(project: SessionProject) {
        records.clear()
        for ((id, file) in project.running(DialogKind)) records[id] = Record(id, file)
    }

    override fun start() {
        for (record in records.values) start(record)
    }

    /** A scope stopped: the dialogs it made go. */
    override fun scopeReleased(scope: Scope) = removeOwned(scope.id)

    override fun playerQuit(player: PlayerRef) = forget(player.uuid)

    override fun liveness() = listOf(
        liveness<LuaHandle.Dialog> { file(it.id) != null },
        liveness<LuaHandle.Button> { button -> file(button.dialog)?.buttons?.any { it.key == button.key } == true }
    )

    override val reloads: Set<KindSpec<*, *>> get() = setOf(DialogKind)

    override fun reload(kind: KindSpec<*, *>, ids: Set<String>, batch: ReloadBatch) {
        for (id in ids) {
            batch.resource(Resource(DialogKind, id), batch.snapshot.running(DialogKind)[id]) {
                reload(id, it)
                0
            }
        }
    }

    override fun stop() {
        for (record in records.values) stop(record)
        // The Lua state goes after this; what the screens were opened with goes with it.
        for (opening in open.values) release(opening)
        open.clear()
        runtimeDialogs.clear()
    }

    /**
     * Moves [id] onto [next] (its script restarts), or forgets it when [next]
     * is null. Someone already looking at the old one keeps the screen they
     * have; their press reaches the new script, and a button the new version
     * doesn't have is ignored.
     */
    fun reload(id: String, next: DialogFile?) {
        val previous = records.remove(id)
        previous?.let(::stop)
        if (next == null) {
            // Gone: so are the handlers anyone put on it.
            scripts.dropTarget(LuaHandle.Dialog(id))
            return
        }
        for (button in previous?.file?.buttons.orEmpty()) {
            if (next.buttons.none { it.key == button.key }) scripts.dropTarget(LuaHandle.Button(id, button.key))
        }
        val record = Record(id, next)
        records[id] = record
        start(record)
    }

    /**
     * Builds [id] and puts it on [player]'s screen, drawn from [file] (the
     * dialog as this opening changes it, [opened]) when given, and holding
     * [context] until it leaves their screen. A project dialog already there
     * is closed first (`close`). When it can't be shown, [context] is
     * released at once.
     */
    fun show(id: String, player: PlayerRef, file: DialogFile? = null, context: LuaRef? = null): Boolean {
        val spec = spec(id, HashSet(), file)
        if (spec == null) {
            context?.let(::release)
            return false
        }
        closedByServer(player)
        if (!ops.show(player.uuid, spec)) {
            context?.let(::release)
            return false
        }
        open[player.uuid] = Opening(id, context)
        return true
    }

    /** Puts the runtime's own [dialog] on [player]'s screen (a project dialog there is closed first, `close`). */
    fun showRuntime(player: PlayerRef, dialog: RuntimeDialog): Boolean {
        closedByServer(player)
        if (!ops.show(player.uuid, dialog.spec)) return false
        runtimeDialogs[player.uuid] = dialog
        return true
    }

    /** Takes whatever dialog is on [player]'s screen away; a project dialog's `close` handlers hear it. */
    fun close(player: PlayerRef): Boolean {
        closedByServer(player)
        return ops.close(player.uuid)
    }

    /**
     * Dialog [id] as one opening draws it: inputs starting at [values] (by
     * input key) instead of the file's `initial`, [title] for its title, and
     * [body] text by body element key (a message's text, an item's
     * description). An input or body element the dialog doesn't have, or a
     * value of the wrong kind for its input, is the script's mistake. Null
     * when the project has no such dialog.
     */
    fun opened(id: String, values: Map<String, JsonPrimitive>?, title: String?, body: Map<String, String>?): DialogFile? {
        val file = records[id]?.file ?: return null
        for (key in values.orEmpty().keys) {
            if (file.inputs.none { it.key == key }) throw LuaApiException(missing(id, "input", key, file.inputs.map { it.key }))
        }
        for (key in body.orEmpty().keys) {
            if (file.body.none { it.key == key }) throw LuaApiException(missing(id, "body element", key, file.body.mapNotNull { it.key }))
        }
        val inputs = file.inputs.map { input ->
            val value = values?.get(input.key) ?: return@map input
            initial(input, value)
        }
        val elements = file.body.map { element ->
            val text = element.key?.let { body?.get(it) } ?: return@map element
            when (element) {
                is MessageBody -> element.copy(text = text)
                is ItemBody -> element.copy(description = text)
            }
        }
        return file.copy(title = title ?: file.title, inputs = inputs, body = elements)
    }

    /** [input] starting at [value]. */
    private fun initial(input: DialogInput, value: JsonPrimitive): DialogInput {
        val where = "options.values.${input.key}"
        return when (input) {
            is TextInput -> {
                val text = value.takeIf { it.isString }?.content ?: throw LuaApiException("$where must be a string (it's a text input)")
                val most = input.maxLength
                if (most != null && text.length > most) {
                    throw LuaApiException("$where is ${text.length} characters, more than the input's max_length of $most")
                }
                input.copy(initial = text)
            }
            is BooleanInput -> input.copy(
                initial =
                value.takeIf { !it.isString }?.booleanOrNull
                    ?: throw LuaApiException("$where must be true or false (it's a boolean input)")
            )
            is OptionInput -> {
                val id =
                    value.takeIf { it.isString }?.content
                        ?: throw LuaApiException("$where must be an option's id (it's a single_option input)")
                if (input.options.none { it.id == id }) {
                    throw LuaApiException("$where: the input has no option \"$id\" (it has: ${input.options.joinToString { it.id }})")
                }
                input.copy(options = input.options.map { it.copy(initial = if (it.id == id) true else null) })
            }
            is RangeInput -> {
                val number =
                    value.takeIf { !it.isString }?.doubleOrNull
                        ?: throw LuaApiException("$where must be a number (it's a number_range input)")
                val low = minOf(input.start, input.end)
                val high = maxOf(input.start, input.end)
                if (number !in low..high) throw LuaApiException("$where is $number, outside the input's range of $low to $high")
                input.copy(initial = number)
            }
        }
    }

    private fun missing(id: String, what: String, key: String, known: List<String>): String =
        "dialog $id has no $what \"$key\"" + if (known.isEmpty()) " (it has no keyed ones)" else " (it has: ${known.joinToString()})"

    /** The player left the server: whatever they had open just goes. */
    private fun forget(player: UUID) {
        runtimeDialogs.remove(player)
        open.remove(player)?.let(::release)
    }

    /** The server is about to take [player]'s dialog away: `close` for the one we know they have. */
    private fun closedByServer(player: PlayerRef) {
        runtimeDialogs.remove(player.uuid)
        val opening = open.remove(player.uuid) ?: return
        if (records[opening.dialog] != null) fireClose(player, opening.dialog, opening.context)
        release(opening)
    }

    /** [player] left [dialog] through its exit action (escape, or the exit button). */
    fun closed(player: PlayerRef, dialog: String, registry: Boolean = false) {
        if (runtimeDialogs[player.uuid]?.ids?.contains(dialog) == true) {
            runtimeDialogs.remove(player.uuid)
            return
        }
        // As for a press: leaving a dialog that isn't on their screen is a client's invention.
        if (!registry && !onScreen(open[player.uuid], dialog)) {
            onIgnored("${player.name} left dialog $dialog, which isn't on their screen")
            return
        }
        val opening = open.remove(player.uuid)
        if (records[dialog] != null) fireClose(player, dialog, opening?.context)
        opening?.let(::release)
    }

    private fun fireClose(player: PlayerRef, dialog: String, context: LuaRef?) {
        val handle = LuaHandle.Dialog(dialog)
        scripts.emit(Events.DIALOG_CLOSE, handle, DialogEvent(LuaHandle.Player(player.uuid.toString()), handle, context))
    }

    private fun release(opening: Opening) {
        opening.context?.let(::release)
    }

    private fun release(context: LuaRef) {
        scripts.host?.unref(context)
    }

    /**
     * The dialog as the platform builds it, with the dialogs a `dialog_list`
     * offers built alongside (each only once along a path, so two lists that
     * offer each other don't recurse forever).
     */
    private fun spec(id: String, path: MutableSet<String>, opened: DialogFile? = null): DialogSpec? {
        val record = records[id] ?: return null
        if (!path.add(id)) return null
        val file = opened ?: record.file
        val listed = if (file.type ==
            DialogType.DIALOG_LIST
        ) {
            file.dialogs.mapNotNull { idOf(it)?.let { other -> spec(other, path) } }
        } else {
            emptyList()
        }
        path.remove(id)
        return DialogSpec(id, file, listed)
    }

    /**
     * Whether [dialog] is on [player]'s screen in [opening]: the dialog shown,
     * or one a `dialog_list` it showed leads to.
     */
    private fun onScreen(opening: Opening?, dialog: String): Boolean {
        if (opening == null) return false
        if (opening.dialog == dialog) return true
        return spec(opening.dialog, HashSet())?.let { shown -> listedIn(shown, dialog) } == true
    }

    private fun listedIn(spec: DialogSpec, dialog: String): Boolean = spec.listed.any { it.id == dialog || listedIn(it, dialog) }

    /**
     * A button was pressed: heard by the button, then the dialog, then `nf.on("dialog_press")`.
     *
     * Only a dialog on the player's screen ([onScreen]) can be pressed: the game's client is the player's, and a
     * modified one can send a button's click again after the dialog closed, or for one it was never shown. Those
     * are ignored. [registry] is a dialog of the server's registry (the pause screen's), which the player opens
     * themselves.
     */
    fun pressed(player: PlayerRef, dialog: String, button: String, values: Map<String, Any>, registry: Boolean = false) {
        val mine = runtimeDialogs[player.uuid]
        if (mine != null && dialog in mine.ids) {
            runtimeDialogs.remove(player.uuid)
            mine.pressed(dialog, button, values)
            return
        }
        val record = records[dialog]
        // The opening it was pressed in (a dialog a `dialog_list` led to is still that opening).
        val opening = open[player.uuid]
        if (!registry && !onScreen(opening, dialog)) {
            onIgnored("${player.name} pressed \"$button\" on dialog $dialog, which isn't on their screen")
            return
        }
        // A press closes the screen unless the dialog says to keep it up; the context goes once it's been heard.
        val closes = (record?.file?.afterAction ?: AfterAction.CLOSE) == AfterAction.CLOSE
        if (closes) open.remove(player.uuid) else open[player.uuid] = Opening(dialog, opening?.context)
        try {
            if (record == null || record.file.buttons.none { it.key == button }) {
                onIgnored(
                    "${player.name} pressed \"$button\" on dialog $dialog, which ${if (record == null) "is gone" else "has no such button any more"}"
                )
                return
            }
            dispatch(player, dialog, button, values, opening?.context)
        } finally {
            if (closes) opening?.let(::release)
        }
    }

    /**
     * A custom click [id] from a dialog in the server's registry (the pause
     * screen's or the quick actions key's, `DialogJson`): its button pressed
     * with [answers], or the dialog left. True when [id] was one of those
     * (even one that's ignored), so nothing else treats it as its own. The
     * game takes a click from a player at any time, so only a dialog that is
     * in the registry is heard: a player can't press one that `show` alone
     * could have put on their screen.
     */
    fun registryClicked(player: PlayerRef, id: String, answers: DialogAnswers): Boolean {
        val click = DialogJson.click(id, namespace()) ?: return false
        val record = records[click.dialog]
        if (record == null || click.dialog !in registry()) {
            onIgnored("${player.name} clicked $id, but dialog ${click.dialog} isn't in the server's dialog registry")
            return true
        }
        when (click) {
            is DialogJson.Click.Exit -> closed(player, click.dialog, registry = true)
            is DialogJson.Click.Press -> {
                val button = record.file.buttons.getOrNull(click.index)
                if (button == null) {
                    onIgnored("${player.name} pressed button ${click.index} on dialog ${click.dialog}, which has no such button any more")
                } else {
                    pressed(player, click.dialog, button.key, values(record.file, answers), registry = true)
                }
            }
        }
        return true
    }

    /** The dialogs the server's registry holds: those on the pause screen or the quick actions key, and the ones they list. */
    private fun registry(): Set<String> {
        val found = HashSet<String>()

        fun add(id: String) {
            val record = records[id] ?: return
            if (!found.add(id)) return
            if (record.file.type == DialogType.DIALOG_LIST) record.file.dialogs.mapNotNull(::idOf).forEach(::add)
        }
        for (record in records.values.filter { it.owner == null && (it.file.pauseMenu == true || it.file.quickActions == true) }) {
            add(record.id)
        }
        return found
    }

    /**
     * Every input's answer by key, typed by the input: text and an option's id
     * as strings, a checkbox as its `onTrue`/`onFalse` string, a slider as a
     * number. What [answers] lacks is left out, and so is an answer the dialog
     * couldn't have given: the game's client is the player's, and a modified
     * one sends whatever it likes (text past the input's length, an option the
     * file doesn't list, a number off the slider).
     */
    companion object {
        /** The game's own most for a text input that doesn't say. */
        private const val DEFAULT_MAX_LENGTH = 32

        fun values(file: DialogFile, answers: DialogAnswers): Map<String, Any> = buildMap {
            for (input in file.inputs) {
                val value: Any? = runCatching {
                    when (input) {
                        is BooleanInput -> answers.bool(input.key)?.let { if (it) input.onTrue ?: "true" else input.onFalse ?: "false" }
                        is RangeInput -> answers.number(input.key)?.takeIf { n ->
                            n.isFinite() && n >= minOf(input.start, input.end) && n <= maxOf(input.start, input.end)
                        }
                        is TextInput -> answers.text(input.key)?.takeIf { it.length <= (input.maxLength ?: DEFAULT_MAX_LENGTH) }
                        is OptionInput -> answers.text(input.key)?.takeIf { id -> input.options.any { it.id == id } }
                    }
                }.getOrNull()
                if (value != null) put(input.key, value)
            }
        }
    }

    private fun dispatch(player: PlayerRef, dialog: String, button: String, values: Map<String, Any>, context: LuaRef?) {
        val dialogHandle = LuaHandle.Dialog(dialog)
        val buttonHandle = LuaHandle.Button(dialog, button)
        val event = DialogPressEvent(
            player = LuaHandle.Player(player.uuid.toString()),
            dialog = dialogHandle,
            target = buttonHandle,
            key = button,
            values = values.mapValues { (_, value) ->
                if (value is Number) StringOrNumber.Number(value.toDouble()) else StringOrNumber.String(value.toString())
            },
            context = context
        )
        scripts.emit(
            listOf(Events.BUTTON_PRESS to buttonHandle, Events.DIALOG_PRESS to dialogHandle, Events.NF_DIALOG_PRESS to null),
            event
        )
    }

    private fun start(record: Record) {
        val script = record.file.script ?: return
        val path = placeOf(DialogKind, record.id).file(script.file)
        val source = readSource(path) ?: return
        record.scope = scripts.start(ScopeOwner.DialogScript(record.id, path), script, source).first
    }

    private fun stop(record: Record) {
        record.scope?.let(scripts::close)
        record.scope = null
    }
}
