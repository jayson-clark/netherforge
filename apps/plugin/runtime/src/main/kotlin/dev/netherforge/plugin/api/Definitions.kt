package dev.netherforge.plugin.api

import dev.netherforge.format.Problem
import dev.netherforge.format.ProblemSink
import dev.netherforge.format.Severity
import dev.netherforge.format.centity.CentityCompiler
import dev.netherforge.format.centity.CentityValidator
import dev.netherforge.format.centity.CompiledCentity
import dev.netherforge.format.centity.NodeDef
import dev.netherforge.format.dialog.DialogFile
import dev.netherforge.format.dialog.DialogKind
import dev.netherforge.format.dialog.DialogValidator
import dev.netherforge.format.game.GameData
import dev.netherforge.format.item.ItemDef
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.menu.MenuFile
import dev.netherforge.format.menu.MenuKind
import dev.netherforge.format.menu.MenuValidator
import dev.netherforge.format.menu.SlotDef
import dev.netherforge.format.project.Names
import dev.netherforge.format.recipe.RecipeFile
import dev.netherforge.format.recipe.RecipeKind
import dev.netherforge.format.recipe.RecipeValidator
import dev.netherforge.plugin.binding.luaSpelling
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.lua.LuaValue
import dev.netherforge.plugin.platform.ItemData
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * Menus and dialogs made in Lua: a table in the shape of
 * `menu.json` or `dialog.json`, in Lua spelling (snake_case keys, `Item`
 * tables), read into the format's own model and held to the same rules as
 * the files, by the same parser and validator. A mistake is the script's,
 * named by where it is in the table (`definition.inputs[2].max_length`, Lua's
 * 1-based list indices). Each takes `named`, which writes the names in the
 * file-spelled table as the server knows them, checked in the calling
 * package as its files' would be (`PackageNames.definition`); its items are
 * read as every item a script hands over.
 */

/** The parameter's name in every message. */
private const val ROOT = "definition"

/** A dialog's keys whose values are lists. */
private val LIST_KEYS = setOf("body", "inputs", "buttons", "dialogs", "options")

/** The keys a menu definition takes, all one word, so its Lua and file spellings agree. */
private val MENU_KEYS = listOf("type", "rows", "title", "skin", "locked", "slots")

/**
 * The menu [definition] describes, validated as `menu.json` would be.
 * [items] reads `slots` (items by slot number), so item tables are read as
 * every other item a script hands over.
 */
internal fun menuFrom(
    definition: LuaValue,
    items: (LuaValue) -> Map<Long, ItemData>,
    game: GameData?,
    named: (JsonElement) -> JsonElement
): MenuFile {
    val fields = definition.json(ROOT, skip = setOf("slots")) as? JsonObject ?: JsonObject(emptyMap())
    fields.keys.firstOrNull { it !in MENU_KEYS }?.let {
        throw LuaApiException("unknown field '$ROOT.$it' (fields: ${MENU_KEYS.sorted().joinToString(", ")})")
    }
    val parsed = when (val result = MenuKind.parse(named(fields).toString(), ROOT)) {
        is CanonicalJson.Parsed.Ok -> result.value
        is CanonicalJson.Parsed.Failed -> throw LuaApiException(message(result.problem))
    }
    val slots = definition.field("slots")?.let(items).orEmpty()
    val file = parsed.copy(slots = slots.entries.associate { (index, item) -> index.toString() to SlotDef(item.def) })
    val sink = ProblemSink(ROOT)
    MenuValidator.validate(file, sink, null, game)
    sink.problems.firstOrNull { it.severity == Severity.ERROR }?.let { throw LuaApiException(message(it)) }
    return file
}

/**
 * The dialog [definition] describes (read as JSON), validated as
 * `dialog.json` would be. Item bodies' `item` tables are read by [item], the
 * way every item a script hands over is, then written in the file's spelling.
 */
internal fun dialogFrom(
    definition: JsonElement,
    item: (JsonElement, String) -> ItemDef,
    game: GameData?,
    named: (JsonElement) -> JsonElement
): DialogFile {
    val table = definition as? JsonObject ?: JsonObject(emptyMap())
    if ("script" in table) {
        throw LuaApiException("$ROOT.script: a dialog made in Lua has no script of its own; put handlers on the Dialog it returns")
    }
    for (key in listOf("pause_menu", "quick_actions")) {
        if (key in table) {
            throw LuaApiException(
                "$ROOT.$key: the server's registry takes dialogs only as it starts, so only a dialog file can be in the player's menus"
            )
        }
    }
    val converted = fileSpelling(table, ROOT, item)
    val file = when (val result = DialogKind.parse(named(converted).toString(), ROOT)) {
        is CanonicalJson.Parsed.Ok -> result.value
        is CanonicalJson.Parsed.Failed -> throw LuaApiException(message(result.problem))
    }
    val sink = ProblemSink(ROOT)
    DialogValidator.validate(file, sink, null, game)
    sink.problems.firstOrNull { it.severity == Severity.ERROR }?.let { throw LuaApiException(message(it)) }
    return file
}

/**
 * A dialog table in Lua spelling as the file spells it: every key from
 * snake_case to camelCase (a key that's already camelCase is a mistake: Lua
 * spells them all with `_`), and each item body's `item` read as an `Item`.
 */
private fun fileSpelling(element: JsonElement, where: String, item: (JsonElement, String) -> ItemDef): JsonElement = when (element) {
    is JsonObject -> {
        val itemBody = (element["type"] as? JsonPrimitive)?.contentOrNull == "item" && "item" in element
        JsonObject(
            element.entries.associate { (key, value) ->
                if (key.any { it.isUpperCase() }) {
                    throw LuaApiException("$where.$key: Lua spells keys in snake_case, so it's ${snake(key)}")
                }
                val at = "$where.$key"
                val converted = if (itemBody && key == "item") {
                    CanonicalJson.json.encodeToJsonElement(ItemDef.serializer(), item(value, at))
                } else if (key in LIST_KEYS && value is JsonObject && value.isEmpty()) {
                    // An empty Lua table reads as an empty object; here it's the empty list.
                    JsonArray(emptyList())
                } else {
                    fileSpelling(value, at, item)
                }
                camel(key) to converted
            }
        )
    }
    is JsonArray -> JsonArray(element.mapIndexed { index, value -> fileSpelling(value, "$where[${index + 1}]", item) })
    else -> element
}

/** The keys a recipe definition takes, in Lua's spelling. */
private val RECIPE_KEYS = listOf(
    "type", "pattern", "key", "ingredients", "ingredient", "template", "base", "addition", "result", "experience",
    "cooking_time", "group", "category", "materials", "stations", "station_range"
)

/** A recipe's keys whose values are lists. */
private val RECIPE_LISTS = setOf("pattern", "ingredients", "materials", "stations")

/**
 * The recipe [definition] describes, validated as `recipes/<id>.json` would
 * be. Its `result` is read by [item], the way every item a script hands over
 * is (its `data` may hold `Vec3`s and handles), then written in the file's
 * spelling. Only the top level's keys are snake_case: `key`'s are the
 * pattern's characters, as they are in the file.
 */
internal fun recipeFrom(
    definition: LuaValue,
    item: (LuaValue) -> ItemDef,
    game: GameData?,
    named: (JsonElement) -> JsonElement
): RecipeFile {
    val fields = definition.json(ROOT, skip = setOf("result")) as? JsonObject
        ?: throw LuaApiException("$ROOT must be a table, like { type = \"shapeless\", ingredients = { ... }, result = { ... } }")
    fields.keys.firstOrNull { it !in RECIPE_KEYS && it != "result" }?.let {
        throw LuaApiException("unknown field '$ROOT.$it' (fields: ${RECIPE_KEYS.sorted().joinToString(", ")})")
    }
    val result = definition.field("result")?.let { value ->
        try {
            item(value)
        } catch (e: LuaApiException) {
            // `item.count must be…` is about `definition.result.count`.
            val text = e.message.orEmpty()
            throw LuaApiException(if (text.startsWith("item.")) "$ROOT.result.${text.removePrefix("item.")}" else "$ROOT.result: $text")
        }
    } ?: throw LuaApiException("$ROOT.result: a recipe needs a result, an Item")
    val converted = JsonObject(
        fields.entries.associate { (key, value) ->
            // An empty Lua table reads as an empty object; for a list, it's the empty list.
            camel(key) to if (key in RECIPE_LISTS && value is JsonObject && value.isEmpty()) JsonArray(emptyList()) else value
        } + ("result" to CanonicalJson.json.encodeToJsonElement(ItemDef.serializer(), result))
    )
    val file = when (val parsed = RecipeKind.parse(named(converted).toString(), ROOT)) {
        is CanonicalJson.Parsed.Ok -> parsed.value
        is CanonicalJson.Parsed.Failed -> throw LuaApiException(message(parsed.problem))
    }
    val sink = ProblemSink(ROOT)
    RecipeValidator.validate(file, sink, game)
    sink.problems.firstOrNull { it.severity == Severity.ERROR }?.let { throw LuaApiException(message(it)) }
    return file
}

/**
 * The node a script adds, [definition] a table in `centity.json`'s node shape
 * in Lua spelling (`Vec3` values where the file has `[x, y, z]`), read into
 * the format's own [NodeDef] and held to the validator's rules for a node.
 * [parentIndex] is where its parent stands in the list it joins (-1: a root).
 */
internal fun nodeFrom(name: String, parentIndex: Int, definition: LuaValue?, game: GameData?): CompiledCentity.Node {
    if (!Names.isNodeName(name)) {
        throw LuaApiException("bad argument 'name' (\"$name\" isn't a usable node name: ${Names.NODE_NAME_RULE})")
    }
    val table = definition?.json(ROOT) ?: JsonObject(emptyMap())
    if (table !is JsonObject) throw LuaApiException("$ROOT must be a table")
    if ("parent" in table) {
        throw LuaApiException("$ROOT.parent: a node's parent is the node it's added to (add_node adds a root)")
    }
    if ("rope" in table) throw LuaApiException("$ROOT.rope: a rope's links are built with its centity, so only centity.json can have one")
    val file = when (val result = CanonicalJson.parse(NodeDef.serializer(), nodeSpelling(table, ROOT).toString(), ROOT)) {
        is CanonicalJson.Parsed.Ok -> result.value
        is CanonicalJson.Parsed.Failed -> throw LuaApiException(message(result.problem))
    }
    val sink = ProblemSink(ROOT)
    CentityValidator.validateNode(name, file, sink, game)
    val prefix = CentityValidator.nodePath(name)
    sink.problems.firstOrNull { it.severity == Severity.ERROR }?.let {
        throw LuaApiException(message(it.copy(path = it.path?.replaceFirst(prefix, "$"))))
    }
    return CentityCompiler.compileNode(name, file, parentIndex)
}

/** The keys of a node definition whose values are lists. */
private val NODE_LISTS = setOf("boxes")

/** A node table as the file spells it: snake_case keys to camelCase, a `Vec3` as `[x, y, z]`, an empty list as one. */
private fun nodeSpelling(element: JsonElement, where: String): JsonElement = when (element) {
    is JsonObject -> {
        val vec = element["\$vec3"]
        if (element.size == 1 && vec is JsonArray) {
            vec
        } else {
            JsonObject(
                element.entries.associate { (key, value) ->
                    if (key.any { it.isUpperCase() }) {
                        throw LuaApiException("$where.$key: Lua spells keys in snake_case, so it's ${snake(key)}")
                    }
                    camel(key) to if (key in NODE_LISTS && value is JsonObject && value.isEmpty()) {
                        JsonArray(emptyList())
                    } else {
                        nodeSpelling(value, "$where.$key")
                    }
                }
            )
        }
    }
    is JsonArray -> JsonArray(element.mapIndexed { index, value -> nodeSpelling(value, "$where[${index + 1}]") })
    else -> element
}

/** A format problem as a script's error: its place in the definition in Lua's spelling, then what's wrong. */
private fun message(problem: Problem): String {
    val text = luaSpelling(problem.message.replace(Regex("\"([a-z]+(?:[A-Z][a-z0-9]*)+)\"")) { "\"${snake(it.groupValues[1])}\"" })
    val path = problem.path ?: return "$ROOT: $text"
    return "${luaPath(path)}: $text"
}

/** `$.inputs[0].maxLength` → `definition.inputs[1].max_length`; `$.slots["13"].item` → `definition.slots[13].item`. */
internal fun luaPath(path: String): String {
    val lua = Regex("""\[("?)(\d+)\1]""").replace(path.removePrefix("$")) { match ->
        val (quote, number) = match.destructured
        // A slot number (a quoted key) stays as it is; a list position counts from 1, as Lua's do.
        if (quote.isEmpty()) "[${number.toInt() + 1}]" else "[$number]"
    }
    return ROOT + snake(lua)
}

private fun camel(key: String): String = key.replace(Regex("_([a-z0-9])")) { it.groupValues[1].uppercase() }

private fun snake(key: String): String = key.replace(Regex("([a-z0-9])([A-Z])")) { "${it.groupValues[1]}_${it.groupValues[2].lowercase()}" }
