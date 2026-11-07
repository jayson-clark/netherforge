package dev.netherforge.plugin.platform

import java.util.UUID

/**
 * Who ran a command and how to answer them.
 *
 * The adapter builds one per command run or completion, with what only the
 * server knows about the sender: where they stand and which way they face
 * (for a `location` argument's world and facing), and what they may do.
 */
class CommandSender(
    val player: PlayerRef?,
    val name: String,
    /** Where they are and which way they face; null when they're nowhere. */
    val location: Location?,
    private val permissions: (String) -> Boolean,
    private val replyTo: (String) -> Unit
) {
    /** Sends a MiniMessage line back to whoever ran the command. */
    fun reply(miniMessage: String) = replyTo(miniMessage)

    /** Whether they have a permission node; the console has every one. */
    fun hasPermission(permission: String): Boolean = permissions(permission)
}

/** An entity a selector, a name or a UUID picked: [player] is set when it's a player. */
data class SelectedEntity(val uuid: UUID, val player: PlayerRef?)

/**
 * What the server reads an argument as, before the runtime sees it: the
 * argument type the adapter gives the server's command tree (a Brigadier
 * type on Paper), and so the [ArgumentValue] it hands over. Each
 * [ArgumentType] (generated from the spec's `commandArguments`) names one;
 * the runtime turns the [ArgumentValue] into the handler's value
 * ([dev.netherforge.plugin.command.ArgumentValues]), and [ArgumentType.names]
 * marks the word-typed ones only the runtime can resolve and complete (the
 * project's own things and the server's worlds).
 */
enum class ArgumentReading {
    /** One word (Brigadier's `word`): an [ArgumentValue.Text]. */
    WORD,

    /** The rest of the line, spaces and all: an [ArgumentValue.Text]. */
    TEXT,

    /** A whole number within the argument's `min` and `max`: an [ArgumentValue.Integer]. */
    INTEGER,

    /** Any number within its `min` and `max`: an [ArgumentValue.Decimal]. */
    NUMBER,

    /** `true` or `false`: an [ArgumentValue.Bool]. */
    BOOLEAN,

    /** One of the argument's `choices`: an [ArgumentValue.Text]. */
    CHOICE,

    /** A player's name or a selector picking exactly one player: an [ArgumentValue.Players] of one. */
    PLAYER,

    /** A name or a selector picking at least one player: an [ArgumentValue.Players]. */
    PLAYERS,

    /** A name, UUID or selector picking exactly one entity: an [ArgumentValue.Entities] of one. */
    ENTITY,

    /** A name, UUID or selector picking at least one entity: an [ArgumentValue.Entities]. */
    ENTITIES,

    /** Three coordinates, absolute, `~` or `^`, resolved against the sender: an [ArgumentValue.Position]. */
    POSITION,

    /** A block state the server has: an [ArgumentValue.BlockState], every property filled in. */
    BLOCK_STATE,

    /** An item, components and all: an [ArgumentValue.Item]. */
    ITEM
}

/**
 * One argument: its name, its type, whether it can be left out, the bounds
 * of an `integer` or `number`, the words a `choice` takes, and whether the
 * script completes it itself (then [CommandHandler.suggest] answers for it).
 */
data class ArgumentSyntax(
    val name: String,
    val type: ArgumentType,
    val optional: Boolean = false,
    val min: Double? = null,
    val max: Double? = null,
    val choices: List<String> = emptyList(),
    val completes: Boolean = false
) {
    /** Whether the server asks the runtime what to suggest for it: the script completes it, or only the runtime knows its words. */
    val suggestedByRuntime get() = completes || type.names
}

/**
 * A command or one of its subcommands: the arguments that follow it, the
 * subcommands that can follow instead, who may use it, and whether typing it
 * (with its required arguments) runs anything. One with [runs] false only
 * leads to its subcommands; typed alone, it answers with their usage.
 */
data class CommandSyntax(
    val description: String = "",
    val permission: String? = null,
    val playersOnly: Boolean = false,
    val arguments: List<ArgumentSyntax> = emptyList(),
    /** By name, in the order they're listed in usage messages. */
    val subcommands: Map<String, CommandSyntax> = emptyMap(),
    val runs: Boolean = true
) {
    /** The arguments that must be typed: every one before the first optional one. */
    val required get() = arguments.count { !it.optional }

    /** The syntax [path]'s subcommands lead to from here; null when one of them isn't there. */
    fun at(path: List<String>): CommandSyntax? = path.fold(this as CommandSyntax?) { node, name -> node?.subcommands?.get(name) }
}

/** A command: its name, other names for it, and its [syntax] (whose description and permission are the command's). */
data class CommandSpec(val name: String, val aliases: List<String> = emptyList(), val syntax: CommandSyntax = CommandSyntax()) {
    val description get() = syntax.description
    val permission get() = syntax.permission
}

/**
 * An argument's value as the server read it ([ArgumentReading]): typed and
 * checked against what the server knows (who's online, which blocks and items
 * it has, where the sender stands), not yet against the project.
 */
sealed interface ArgumentValue {
    /** A word, the rest of the line, or a choice: as typed. */
    data class Text(val text: String) : ArgumentValue

    data class Integer(val value: Long) : ArgumentValue

    data class Decimal(val value: Double) : ArgumentValue

    data class Bool(val value: Boolean) : ArgumentValue

    /** Who a name or a selector picked: never empty. */
    data class Players(val players: List<PlayerRef>) : ArgumentValue

    /** What a name, UUID or selector picked: never empty. */
    data class Entities(val entities: List<SelectedEntity>) : ArgumentValue

    /** A point, `~` and `^` already counted from the sender; whole x and z are block centres, as in vanilla. */
    data class Position(val x: Double, val y: Double, val z: Double) : ArgumentValue

    /** A block state in full: `minecraft:oak_stairs[facing=east,half=bottom,…]`. */
    data class BlockState(val state: String) : ArgumentValue

    data class Item(val item: ItemData) : ArgumentValue
}

/**
 * A command as the server read it: the subcommands typed ([path]), the syntax
 * they lead to, each argument typed there by name (an optional one left out
 * isn't in [arguments]), and everything typed after the label ([text]).
 */
data class CommandInput(val path: List<String>, val arguments: Map<String, ArgumentValue>, val text: String)

/**
 * Tab completion asking about [argument] of the syntax [path] leads to:
 * the arguments before it as the server read them, and what's typed of it
 * so far ([partial]; for a `text` argument, everything after its start).
 */
data class SuggestionRequest(val path: List<String>, val argument: String, val arguments: Map<String, ArgumentValue>, val partial: String)

/** Suggestions for the word being typed, which starts at [start] within what was asked about. */
data class Completion(val start: Int, val suggestions: List<String>)

interface CommandHandler {
    /** [label] is what was typed to run it, the name or an alias; [input] is what the server read after it. */
    fun run(sender: CommandSender, label: String, input: CommandInput)

    /**
     * Suggestions for an argument whose suggestions come from the runtime
     * ([ArgumentSyntax.suggestedByRuntime]): [Completion.start] is within
     * [SuggestionRequest.partial]. Every other argument the server completes itself.
     */
    fun suggest(sender: CommandSender, request: SuggestionRequest): Completion = Completion(request.partial.length, emptyList())
}

/**
 * The project's commands on the server. The server reads what's typed against
 * a command's [CommandSyntax] (its own parser: Brigadier on Paper), answers a
 * mistake there itself, and hands the handler what it read; the runtime never
 * reads a command line.
 */
interface CommandOps {
    /** Adds `/name` to the server. False when the name is already taken by the server or another plugin. */
    fun register(spec: CommandSpec, handler: CommandHandler): Boolean

    fun unregister(name: String)

    /** Runs a command as the console. False when there's no such command. */
    fun runConsole(line: String): Boolean
}
