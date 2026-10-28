package dev.netherforge.format.dialog

import dev.netherforge.format.datapack.DatapackContext
import dev.netherforge.format.datapack.DatapackEntry
import dev.netherforge.format.game.GameIds
import dev.netherforge.format.item.ItemDef
import dev.netherforge.format.item.ItemKind
import dev.netherforge.format.ref.ResourceKey
import dev.netherforge.format.ref.ResourceRef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The dialogs of the player's own menus, for the start-up datapack: the
 * pause screen's and the quick actions key's. The game lists them from its
 * dialog registry (the `minecraft:pause_screen_additions` and
 * `minecraft:quick_actions` tags), which is read only as the server loads, so
 * they're written in the game's dialog format here and a change restarts the
 * server.
 *
 * A registry dialog is static, so it can't be built per show the way `show`
 * builds one. Its buttons are `minecraft:dynamic/custom` actions instead: the
 * game answers a press with a custom click (the action's id, and every input's
 * answer), the runtime hears it and runs the dialog's one script as it does
 * for a press of a dialog `show` built. The ids say which dialog and which
 * button, see [Click].
 *
 * A dialog the registry needs without it being on a tag (one a `dialog_list`
 * on a tag lists) is written all the same.
 */
object DialogJson {
    /** The game's tags of dialogs: where the pause screen and the quick actions key look. */
    const val PAUSE_TAG = "data/minecraft/tags/dialog/pause_screen_additions.json"
    const val QUICK_ACTIONS_TAG = "data/minecraft/tags/dialog/quick_actions.json"

    /** Where the game reads a datapack's dialogs, under `data/<namespace>/`. */
    const val DATAPACK_FOLDER = "dialog"

    private const val CLICK_FOLDER = "dialog/"
    private const val PRESS = "/press/"
    private const val EXIT = "/exit"

    /** What a custom click from a registry dialog means. */
    sealed interface Click {
        /** The dialog's name as the runtime knows it (`welcome`, `acme:help`). */
        val dialog: String

        /** The [index]th button of the dialog's file was pressed. */
        data class Press(override val dialog: String, val index: Int) : Click

        /** The dialog was left through its exit action (Escape, or the exit button). */
        data class Exit(override val dialog: String) : Click
    }

    /** The id a button's action carries: `<namespace>:dialog/<dialog>/press/<index>`. */
    fun pressId(dialog: ResourceKey, index: Int): String = "${dialog.namespace}:$CLICK_FOLDER${dialog.path}$PRESS$index"

    /** The id an exit action carries. */
    fun exitId(dialog: ResourceKey): String = "${dialog.namespace}:$CLICK_FOLDER${dialog.path}$EXIT"

    /**
     * What custom click [id] means from the project in namespace [home], or
     * null when it isn't one of these (another plugin's, or a button a dialog
     * `show` built).
     */
    fun click(id: String, home: String): Click? {
        val namespace = id.substringBefore(':', "")
        val rest = id.substringAfter(':', "").takeIf { it.startsWith(CLICK_FOLDER) }?.removePrefix(CLICK_FOLDER) ?: return null
        if (namespace.isEmpty()) return null

        fun name(dialog: String): String? = ResourceRef("$namespace:$dialog").nameIn(home)
        if (rest.endsWith(EXIT)) return name(rest.removeSuffix(EXIT))?.let { Click.Exit(it) }
        val split = rest.lastIndexOf(PRESS)
        if (split < 0) return null
        val index = rest.substring(split + PRESS.length).toIntOrNull()?.takeIf { it >= 0 } ?: return null
        return name(rest.substring(0, split))?.let { Click.Press(it, index) }
    }

    /**
     * Every file for [resources] (the running dialogs by key): one for each
     * dialog that [DialogFile.pauseMenu] or [DialogFile.quickActions] puts on
     * a menu and for the dialogs those list, and the tags naming the menus'.
     * Empty when none is on a menu.
     */
    fun files(resources: Map<ResourceKey, DialogFile>, ctx: DatapackContext): Map<String, DatapackEntry> {
        val menus = resources.filterValues { it.pauseMenu == true || it.quickActions == true }
        if (menus.isEmpty()) return emptyMap()
        val needed = linkedMapOf<ResourceKey, DialogFile>()
        fun need(key: ResourceKey) {
            val file = resources[key] ?: return
            if (needed.put(key, file) != null) return
            if (file.type == DialogType.DIALOG_LIST) file.dialogs.forEach { need(ctx.resolve(it)) }
        }
        menus.keys.forEach(::need)
        val files = HashMap<String, DatapackEntry>()
        for ((key, file) in needed) {
            files["data/${key.namespace}/$DATAPACK_FOLDER/${key.path}.json"] = DatapackEntry.json(of(key, file, ctx, needed.keys))
        }
        tag(menus.filterValues { it.pauseMenu == true }.keys)?.let { files[PAUSE_TAG] = it }
        tag(menus.filterValues { it.quickActions == true }.keys)?.let { files[QUICK_ACTIONS_TAG] = it }
        return files
    }

    private fun tag(keys: Set<ResourceKey>): DatapackEntry? {
        if (keys.isEmpty()) return null
        return DatapackEntry.json(
            buildJsonObject {
                put("replace", false)
                putJsonArray("values") { keys.map { it.toString() }.sorted().forEach { add(JsonPrimitive(it)) } }
            }
        )
    }

    /** [file] (the dialog [key] names) in the game's format; a `dialog_list` lists the ones in [registry]. */
    fun of(key: ResourceKey, file: DialogFile, ctx: DatapackContext, registry: Set<ResourceKey>): JsonObject {
        val buttons = file.buttons.mapIndexed { index, button -> button(button, pressId(key, index), ctx) }
        val columns = file.columns ?: DialogType.DEFAULT_COLUMNS
        val type = file.type ?: DialogType.NOTICE
        return buildJsonObject {
            put("type", "minecraft:${typeName(type)}")
            put("title", ctx.text(file.title))
            file.externalTitle?.let { put("external_title", ctx.text(it)) }
            put("can_close_with_escape", file.canCloseWithEscape ?: true)
            // The game refuses a dialog that pauses (its default) and stays up after a press; a server has nothing to pause.
            put("pause", false)
            put(
                "after_action",
                when (file.afterAction ?: AfterAction.CLOSE) {
                    AfterAction.CLOSE -> "close"
                    AfterAction.NONE -> "none"
                    AfterAction.WAIT_FOR_RESPONSE -> "wait_for_response"
                }
            )
            val body = file.body.mapNotNull { body(it, ctx) }
            if (body.isNotEmpty()) put("body", JsonArray(body))
            if (file.inputs.isNotEmpty()) put("inputs", JsonArray(file.inputs.map { input(it, ctx) }))
            when (type) {
                DialogType.NOTICE -> put("action", buttons.firstOrNull() ?: exit(key, "gui.ok"))
                DialogType.CONFIRMATION -> {
                    put("yes", buttons.getOrElse(0) { exit(key, "gui.yes") })
                    put("no", buttons.getOrElse(1) { exit(key, "gui.no") })
                }
                DialogType.MULTI_ACTION -> {
                    // The game wants at least one action; a dialog with no buttons has just its exit one.
                    put("actions", JsonArray(buttons.ifEmpty { listOf(exit(key, "gui.back")) }))
                    put("columns", columns)
                    put("exit_action", exit(key, "gui.back"))
                }
                DialogType.DIALOG_LIST -> {
                    putJsonArray("dialogs") {
                        file.dialogs.map { ctx.resolve(it) }.filter { it in registry }.forEach { add(JsonPrimitive(it.toString())) }
                    }
                    put("columns", columns)
                    // Its one button is its exit action: labelled as the file says, reported as leaving.
                    put("exit_action", file.buttons.firstOrNull()?.let { button(it, exitId(key), ctx) } ?: exit(key, "gui.back"))
                }
            }
        }
    }

    private fun typeName(type: DialogType): String = when (type) {
        DialogType.NOTICE -> "notice"
        DialogType.CONFIRMATION -> "confirmation"
        DialogType.MULTI_ACTION -> "multi_action"
        DialogType.DIALOG_LIST -> "dialog_list"
    }

    private fun button(button: DialogButton, id: String, ctx: DatapackContext): JsonObject = buildJsonObject {
        put("label", ctx.text(button.label ?: button.key))
        button.tooltip?.let { put("tooltip", ctx.text(it)) }
        button.width?.let { put("width", it) }
        put("action", click(id))
    }

    /** The exit button NetherForge adds where the file has none: its label is the game's own words. */
    private fun exit(key: ResourceKey, translation: String): JsonObject = buildJsonObject {
        putJsonObject("label") { put("translate", translation) }
        put("action", click(exitId(key)))
    }

    private fun click(id: String): JsonObject = buildJsonObject {
        put("type", "minecraft:dynamic/custom")
        put("id", id)
    }

    private fun body(body: DialogBody, ctx: DatapackContext): JsonObject? = when (body) {
        is MessageBody -> buildJsonObject {
            put("type", "minecraft:plain_message")
            put("contents", ctx.text(body.text))
            body.width?.let { put("width", it) }
        }
        is ItemBody -> stack(body.item, ctx)?.let { stack ->
            buildJsonObject {
                put("type", "minecraft:item")
                put("item", stack)
                body.description?.let { description ->
                    putJsonObject("description") { put("contents", ctx.text(description)) }
                }
                body.showTooltip?.let { put("show_tooltip", it) }
                body.width?.let { put("width", it) }
                body.height?.let { put("height", it) }
            }
        }
    }

    /**
     * An item body's stack: its kind and count, and the components its look
     * needs (a project item's pack look, the glint, a name). What a stack
     * keeps in its persistent data a static dialog can't hold. Null when its
     * project item isn't running.
     */
    private fun stack(def: ItemDef, ctx: DatapackContext): JsonObject? {
        val item = def.item?.let { ref -> ctx.snapshot.running(ItemKind)[ctx.resolve(ref).relativeTo(ctx.home).text] ?: return null }
        val kind = def.kind.takeIf { it.isNotEmpty() } ?: item?.kind ?: return null
        val model = def.itemModel ?: item?.itemModel
        val glint = def.glint ?: item?.glint
        val name = def.name ?: item?.name
        return buildJsonObject {
            put("id", GameIds.normalize(kind))
            def.count?.let { put("count", it) }
            if (model != null || glint != null || name != null) {
                putJsonObject("components") {
                    model?.let { put("minecraft:item_model", ctx.resolve(it).toString()) }
                    glint?.let { put("minecraft:enchantment_glint_override", it) }
                    name?.let { put("minecraft:custom_name", ctx.text(it)) }
                }
            }
        }
    }

    private fun input(input: DialogInput, ctx: DatapackContext): JsonObject {
        val label = ctx.text(input.label ?: input.key)
        return buildJsonObject {
            when (input) {
                is TextInput -> {
                    put("type", "minecraft:text")
                    put("key", input.key)
                    put("label", label)
                    input.width?.let { put("width", it) }
                    input.labelVisible?.let { put("label_visible", it) }
                    input.initial?.let { put("initial", it) }
                    input.maxLength?.let { put("max_length", it) }
                    input.lines?.takeIf { it > 1 }?.let { lines -> putJsonObject("multiline") { put("max_lines", lines) } }
                }
                is BooleanInput -> {
                    put("type", "minecraft:boolean")
                    put("key", input.key)
                    put("label", label)
                    put("initial", input.initial ?: false)
                    put("on_true", input.onTrue ?: "true")
                    put("on_false", input.onFalse ?: "false")
                }
                is OptionInput -> {
                    put("type", "minecraft:single_option")
                    put("key", input.key)
                    put("label", label)
                    input.width?.let { put("width", it) }
                    put(
                        "options",
                        buildJsonArray {
                            for (option in input.options) {
                                add(
                                    buildJsonObject {
                                        put("id", option.id)
                                        put("display", ctx.text(option.label ?: option.id))
                                        put("initial", option.initial ?: false)
                                    }
                                )
                            }
                        }
                    )
                }
                is RangeInput -> {
                    put("type", "minecraft:number_range")
                    put("key", input.key)
                    put("label", label)
                    input.width?.let { put("width", it) }
                    put("start", input.start.toFloat())
                    put("end", input.end.toFloat())
                    input.step?.let { put("step", it.toFloat()) }
                    input.initial?.let { put("initial", it.toFloat()) }
                }
            }
        }
    }
}
