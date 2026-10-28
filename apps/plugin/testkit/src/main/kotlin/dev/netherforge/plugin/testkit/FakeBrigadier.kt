package dev.netherforge.plugin.testkit

import dev.netherforge.format.game.BlockState
import dev.netherforge.format.game.GameIds
import dev.netherforge.format.game.RegistryKey
import dev.netherforge.format.game.has
import dev.netherforge.format.item.ItemDef
import dev.netherforge.plugin.platform.ArgumentReading
import dev.netherforge.plugin.platform.ArgumentSyntax
import dev.netherforge.plugin.platform.ArgumentValue
import dev.netherforge.plugin.platform.CommandInput
import dev.netherforge.plugin.platform.CommandSender
import dev.netherforge.plugin.platform.CommandSyntax
import dev.netherforge.plugin.platform.Completion
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.platform.Platform
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.platform.SelectedEntity
import dev.netherforge.plugin.platform.SuggestionRequest
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

/** A word of a command line, and where it starts and ends in it. */
internal data class Word(val text: String, val start: Int, val end: Int)

/**
 * Splits what was typed after a command's label into words: on spaces, except
 * inside brackets, braces and quotes, so a selector (`@a[tag=a,distance=..5]`),
 * a block state or an item with components (`diamond_sword[enchantments={…}]`)
 * is one word even with spaces in it. Something left open runs to the end.
 */
internal fun words(input: String): List<Word> {
    val out = mutableListOf<Word>()
    var i = 0
    while (i < input.length) {
        if (input[i] == ' ') {
            i++
            continue
        }
        val start = i
        var depth = 0
        var quote: Char? = null
        while (i < input.length) {
            val c = input[i]
            when {
                quote != null -> if (c == '\\') {
                    i++
                } else if (c == quote) {
                    quote = null
                }
                c == '"' || c == '\'' -> quote = c
                c == '[' || c == '{' -> depth++
                c == ']' || c == '}' -> depth = (depth - 1).coerceAtLeast(0)
                c == ' ' && depth == 0 -> break
            }
            i++
        }
        val end = i.coerceAtMost(input.length)
        out += Word(input.substring(start, end), start, end)
    }
    return out
}

/** Something typed the fake server can't read, said to whoever typed it as Brigadier's message would be. */
internal class FakeSyntaxError(message: String) : Exception(message) {
    override fun fillInStackTrace(): Throwable = this
}

/**
 * The fake server's Brigadier: reads a command line against its
 * [CommandSyntax] the way Paper's command tree does, into what the server
 * hands a [dev.netherforge.plugin.platform.CommandHandler] ([CommandInput]),
 * and completes one the way the client's tab completion asks. It knows only
 * what the server knows (who's online, the game's blocks and items, where the
 * sender stands); the runtime checks the rest (permissions, `players_only`,
 * the project's names) as it does on Paper.
 *
 * Unlike Brigadier it doesn't hide a subcommand the sender lacks the
 * permission for when reading (only when completing), so the runtime's own
 * check of permissions, which on Paper only an `execute as` could reach, is
 * what tests see.
 */
internal class FakeBrigadier(
    private val platform: Platform,
    /** Item text the server reads with components, as `item` arguments do on Paper. */
    private val items: (String) -> ItemData?,
    /** The entities a selector (`@a`, `@e[…]`) picks for [CommandSender]; [IllegalArgumentException] for one it can't read. */
    private val select: (CommandSender, String) -> List<SelectedEntity>
) {
    /** What was typed, read: the subcommands and arguments, or the server's refusal of it. */
    sealed interface Read {
        data class Ready(val input: CommandInput) : Read

        data class Refused(val message: String) : Read
    }

    fun read(root: CommandSyntax, sender: CommandSender, input: String): Read {
        val words = words(input)
        val (path, node, consumed) = walk(root, words, sender, permissions = false)
        var i = consumed
        if (!node.runs) {
            return words.getOrNull(i)?.let { Read.Refused("Unknown subcommand \"${it.text}\".") }
                ?: Read.Ready(CommandInput(path, emptyMap(), input))
        }
        val values = LinkedHashMap<String, ArgumentValue>()
        for (argument in node.arguments) {
            val left = words.size - i
            if (left == 0) {
                if (argument.optional) break
                return Read.Refused("Missing <${argument.name}>.")
            }
            try {
                when {
                    argument.type.reading == ArgumentReading.TEXT -> {
                        values[argument.name] = ArgumentValue.Text(input.substring(words[i].start).trimEnd())
                        i = words.size
                    }
                    argument.type.reading == ArgumentReading.POSITION -> {
                        if (left < 3) throw FakeSyntaxError("<${argument.name}> needs three coordinates, like 10 64 -5 or ~ ~1 ~.")
                        values[argument.name] = value(argument, words.subList(i, i + 3).map { it.text }, sender)
                        i += 3
                    }
                    else -> {
                        values[argument.name] = value(argument, listOf(words[i].text), sender)
                        i++
                    }
                }
            } catch (problem: FakeSyntaxError) {
                return Read.Refused(problem.message!!)
            }
        }
        if (i < words.size) return Read.Refused("Too many arguments: \"${input.substring(words[i].start).trimEnd()}\".")
        return Read.Ready(CommandInput(path, values, input))
    }

    /**
     * What tab completion offers at the end of [input]: subcommand names
     * where one can come (those the sender may use), then what the argument
     * being typed offers: its type's own suggestions, or the runtime's
     * ([suggest]) for one only it can complete. Only those starting with
     * what's typed.
     */
    fun complete(root: CommandSyntax, sender: CommandSender, input: String, suggest: (SuggestionRequest) -> Completion): List<String> {
        val all = words(input)
        val open = all.lastOrNull()?.takeIf { it.end == input.length }
        val done = if (open == null) all else all.dropLast(1)
        val partial = open?.text.orEmpty()

        val (path, node, consumed, denied) = walk(root, done, sender, permissions = true)
        if (denied) return emptyList()
        val out = mutableListOf<String>()
        if (consumed == done.size) {
            out += node.subcommands.filter { (_, sub) -> sub.permission?.let(sender::hasPermission) ?: true }.keys
        }
        var i = consumed
        val values = LinkedHashMap<String, ArgumentValue>()
        for (argument in node.arguments) {
            val left = done.size - i
            if (argument.type.reading == ArgumentReading.TEXT) {
                // The rest of the line, from where it starts: the runtime completes all of it (its last word, say).
                val start = if (left > 0) done[i].start else open?.start ?: input.length
                val offered = if (argument.suggestedByRuntime) {
                    suggest(SuggestionRequest(path, argument.name, values, input.substring(start))).suggestions
                } else {
                    emptyList()
                }
                return if (left > 0) offered else (out + offered).matching(partial)
            }
            val needs = if (argument.type.reading == ArgumentReading.POSITION) 3 else 1
            if (left < needs) {
                out += if (argument.suggestedByRuntime) {
                    suggest(SuggestionRequest(path, argument.name, values, partial)).suggestions
                } else {
                    suggestions(argument, left)
                }
                break
            }
            values[argument.name] = try {
                value(argument, done.subList(i, i + needs).map { it.text }, sender)
            } catch (_: FakeSyntaxError) {
                return out.matching(partial)
            }
            i += needs
        }
        return out.distinct().matching(partial)
    }

    private fun List<String>.matching(typed: String) = filter { it.startsWith(typed, ignoreCase = true) }

    private data class Walk(val path: List<String>, val node: CommandSyntax, val consumed: Int, val denied: Boolean = false)

    /** Follows the subcommands [words] name from [root]; with [permissions], stops at one the sender lacks, as Brigadier hides it. */
    private fun walk(root: CommandSyntax, words: List<Word>, sender: CommandSender, permissions: Boolean): Walk {
        val path = mutableListOf<String>()
        var node = root
        var i = 0
        while (true) {
            if (permissions && node.permission?.let(sender::hasPermission) == false) return Walk(path, node, i, denied = true)
            val next = words.getOrNull(i)?.text?.let { node.subcommands[it] } ?: break
            path += words[i].text
            node = next
            i++
        }
        return Walk(path, node, i)
    }

    /** What the client offers for a type it completes itself; [coordinate] is which of three coordinates is next. */
    private fun suggestions(argument: ArgumentSyntax, coordinate: Int): List<String> {
        val names by lazy { platform.players.online().map { it.name } }
        return when (argument.type.reading) {
            ArgumentReading.BOOLEAN -> listOf("true", "false")
            ArgumentReading.CHOICE -> argument.choices
            ArgumentReading.PLAYER -> names + listOf("@p", "@r", "@s")
            ArgumentReading.PLAYERS -> names + listOf("@a", "@p", "@r", "@s")
            ArgumentReading.ENTITY -> names + listOf("@e", "@p", "@r", "@s")
            ArgumentReading.ENTITIES -> names + listOf("@a", "@e", "@p", "@r", "@s")
            ArgumentReading.POSITION -> listOf(List(3 - coordinate) { "~" }.joinToString(" "))
            else -> emptyList()
        }
    }

    // ---- reading each type ------------------------------------------------------------

    private fun value(argument: ArgumentSyntax, words: List<String>, sender: CommandSender): ArgumentValue {
        val text = words.first()
        val name = "<${argument.name}>"
        return when (argument.type.reading) {
            ArgumentReading.WORD, ArgumentReading.TEXT -> ArgumentValue.Text(text)
            ArgumentReading.INTEGER -> {
                val value = text.toLongOrNull() ?: throw FakeSyntaxError("$name must be a whole number, not \"$text\".")
                bounded(argument, value.toDouble(), text)
                ArgumentValue.Integer(value)
            }
            ArgumentReading.NUMBER -> {
                val value =
                    text.toDoubleOrNull()?.takeIf { it.isFinite() } ?: throw FakeSyntaxError("$name must be a number, not \"$text\".")
                bounded(argument, value, text)
                ArgumentValue.Decimal(value)
            }
            ArgumentReading.BOOLEAN -> when (text) {
                "true" -> ArgumentValue.Bool(true)
                "false" -> ArgumentValue.Bool(false)
                else -> throw FakeSyntaxError("$name must be true or false, not \"$text\".")
            }
            ArgumentReading.CHOICE -> text.takeIf { it in argument.choices }?.let(ArgumentValue::Text)
                ?: throw FakeSyntaxError("$name must be one of ${argument.choices.joinToString(", ")}, not \"$text\".")
            ArgumentReading.PLAYER -> ArgumentValue.Players(listOf(players(text, sender, name).single(name, "player")))
            ArgumentReading.PLAYERS -> ArgumentValue.Players(players(text, sender, name))
            ArgumentReading.ENTITY -> ArgumentValue.Entities(listOf(entities(text, sender, name).single(name, "entity")))
            ArgumentReading.ENTITIES -> ArgumentValue.Entities(entities(text, sender, name))
            ArgumentReading.POSITION -> coordinates(words, sender, name)
            ArgumentReading.BLOCK_STATE -> ArgumentValue.BlockState(
                blockState(text) ?: throw FakeSyntaxError("$name: \"$text\" isn't a block state this server has.")
            )
            ArgumentReading.ITEM -> ArgumentValue.Item(
                item(text) ?: throw FakeSyntaxError("$name: \"$text\" isn't an item this server has.")
            )
        }
    }

    private fun bounded(argument: ArgumentSyntax, value: Double, text: String) {
        val name = "<${argument.name}>"
        argument.min?.let { if (value < it) throw FakeSyntaxError("$name must be at least ${number(it)}, not $text.") }
        argument.max?.let { if (value > it) throw FakeSyntaxError("$name must be at most ${number(it)}, not $text.") }
    }

    private fun number(value: Double) = if (value == Math.floor(value)) value.toLong().toString() else value.toString()

    private fun <T> List<T>.single(name: String, what: String): T = when (size) {
        0 -> throw FakeSyntaxError("$name: no $what found.")
        1 -> single()
        else -> throw FakeSyntaxError("$name: that picks $size, and it takes one $what.")
    }

    private fun selected(text: String, sender: CommandSender, name: String): List<SelectedEntity> = try {
        select(sender, text)
    } catch (e: IllegalArgumentException) {
        throw FakeSyntaxError("$name: ${e.message ?: "\"$text\" isn't a selector"}")
    }

    /** A selector's players, or the one online player with that name. */
    private fun players(text: String, sender: CommandSender, name: String): List<PlayerRef> {
        if (text.startsWith("@")) {
            return selected(text, sender, name).mapNotNull { it.player }.ifEmpty { throw FakeSyntaxError("$name: no player found.") }
        }
        return listOf(platform.players.find(text) ?: throw FakeSyntaxError("$name: no player \"$text\" is online."))
    }

    /** A selector's entities, the one online player with that name, or the loaded entity with that UUID. */
    private fun entities(text: String, sender: CommandSender, name: String): List<SelectedEntity> {
        if (text.startsWith("@")) return selected(text, sender, name).ifEmpty { throw FakeSyntaxError("$name: no entity found.") }
        platform.players.find(text)?.let { return listOf(SelectedEntity(it.uuid, it)) }
        val uuid = runCatching { UUID.fromString(text) }.getOrNull()
            ?: throw FakeSyntaxError("$name: no player \"$text\" is online, and it isn't a UUID.")
        val info = platform.worldEntities.info(uuid) ?: throw FakeSyntaxError("$name: no entity with the UUID $text is loaded.")
        return listOf(SelectedEntity(uuid, info.player))
    }

    /**
     * Three coordinates as vanilla reads them: numbers (whole ones are block
     * centres in x and z), `~` relative to the sender, or `^` local to where
     * they look (left, up, forwards), all three or none.
     */
    private fun coordinates(words: List<String>, sender: CommandSender, name: String): ArgumentValue.Position {
        val local = words.map { it.startsWith("^") }
        if (local.any { it } && !local.all { it }) {
            throw FakeSyntaxError("$name: ^ coordinates can't be mixed with others; use ^ for all three.")
        }
        val from = sender.location
        fun base(): Location =
            from ?: throw FakeSyntaxError("$name: ~ and ^ count from where the sender is, and the console isn't anywhere.")
        fun offset(word: String): Double = word.substring(1).ifEmpty { "0" }.toDoubleOrNull()?.takeIf { it.isFinite() }
            ?: throw FakeSyntaxError("$name: \"$word\" isn't a coordinate.")
        if (local.all { it }) {
            val (left, up, forwards) = words.map(::offset)
            return localPoint(base(), left, up, forwards)
        }
        val origin = listOf({ base().x }, { base().y }, { base().z })
        val parts = words.mapIndexed { axis, word ->
            if (word.startsWith("~")) {
                origin[axis]() + offset(word)
            } else {
                val value = word.toDoubleOrNull()?.takeIf { it.isFinite() } ?: throw FakeSyntaxError("$name: \"$word\" isn't a coordinate.")
                // Like vanilla: a whole x or z is the middle of that block.
                if (axis != 1 && '.' !in word) value + 0.5 else value
            }
        }
        return ArgumentValue.Position(parts[0], parts[1], parts[2])
    }

    /** Vanilla's local coordinates (`LocalCoordinates.getPosition`): from the feet, along the sender's facing. */
    private fun localPoint(from: Location, left: Double, up: Double, forwards: Double): ArgumentValue.Position {
        val radians = Math.PI / 180.0
        val f = cos((from.yaw + 90.0) * radians)
        val g = sin((from.yaw + 90.0) * radians)
        val h = cos(-from.pitch * radians)
        val i = sin(-from.pitch * radians)
        val j = cos((-from.pitch + 90.0) * radians)
        val k = sin((-from.pitch + 90.0) * radians)
        // forward = (f*h, i, g*h), up = (f*j, k, g*j), left = -(forward × up)
        val fx = f * h
        val fy = i
        val fz = g * h
        val ux = f * j
        val uy = k
        val uz = g * j
        val lx = -(fy * uz - fz * uy)
        val ly = -(fz * ux - fx * uz)
        val lz = -(fx * uy - fy * ux)
        return ArgumentValue.Position(
            from.x + fx * forwards + ux * up + lx * left,
            from.y + fy * forwards + uy * up + ly * left,
            from.z + fz * forwards + uz * up + lz * left
        )
    }

    /** A block state the server has, in full: every property the block has, defaults filled in. */
    private fun blockState(text: String): String? {
        val state = BlockState.parse(text) ?: return null
        val info = platform.game.block(state.id) ?: return null
        if (state.properties.any { (property, value) -> info.properties[property]?.contains(value) != true }) return null
        return state.withDefaults(info).toString()
    }

    /** An item: item text the server reads with components, else a bare item id. */
    private fun item(text: String): ItemData? {
        items(text)?.let { return it }
        if (!GameIds.isValid(text)) return null
        val id = GameIds.normalize(text)
        return if (platform.game.has(RegistryKey.ITEM, id) == true) ItemData(ItemDef(id)) else null
    }
}
