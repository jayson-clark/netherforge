package dev.netherforge.plugin.command

import dev.netherforge.plugin.binding.CommandArgument
import dev.netherforge.plugin.binding.CommandDefinition
import dev.netherforge.plugin.binding.Subcommand
import dev.netherforge.plugin.binding.codec
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.lua.LuaFunction
import dev.netherforge.plugin.lua.LuaTypeMismatch
import dev.netherforge.plugin.platform.ArgumentSyntax
import dev.netherforge.plugin.platform.ArgumentType
import dev.netherforge.plugin.platform.CommandSpec
import dev.netherforge.plugin.platform.CommandSyntax
import party.iroiro.luajava.Lua

/** An argument as its command keeps it: its syntax, the value it has when left out, and the script's own completion. */
internal class DeclaredArgument(val syntax: ArgumentSyntax, val default: Any?, val complete: LuaFunction?)

/**
 * A command or subcommand as its module declared it: the [syntax] the server
 * reads it by, and what only the runtime holds: the [handler], each
 * argument's default and completion, and the subcommands under it.
 */
internal class DeclaredNode(
    val syntax: CommandSyntax,
    val handler: LuaFunction?,
    val arguments: List<DeclaredArgument>,
    val subcommands: Map<String, DeclaredNode>
) {
    /** The node [path]'s subcommands lead to; null when one isn't there. */
    fun at(path: List<String>): DeclaredNode? = path.fold(this as DeclaredNode?) { node, name -> node?.subcommands?.get(name) }

    /** Every function it and the nodes under it hold, for letting go of them together. */
    fun functions(): List<LuaFunction> =
        listOfNotNull(handler) + arguments.mapNotNull { it.complete } + subcommands.values.flatMap { it.functions() }
}

/**
 * `nf.commands.register`'s definition, checked: its shape was read strictly by
 * its generated codec (unknown keys, each field's type, the argument types
 * there are), and this holds it to what the codec can't say, naming the
 * mistake by its place in the definition (`definition.arguments[2]`,
 * `definition.subcommands.add`). Thrown as a [LuaApiException], which the
 * call reports at the script's line.
 */
internal object CommandDefinitions {
    private val ARGUMENT_NAME = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
    private val SUBCOMMAND_NAME = Regex("^[a-z][a-z0-9_-]*$")

    /** What Brigadier's `word` takes, which is what each of a `choice` argument's choices must be. */
    private val WORD = Regex("^[A-Za-z0-9_.+-]+$")

    /** The command [name] from its [definition] (null for none) and [handler]: its spec and its tree of declared nodes. */
    fun declare(name: String, definition: CommandDefinition?, handler: LuaFunction?): Pair<CommandSpec, DeclaredNode> {
        val root = node(
            Node(
                definition?.description,
                definition?.permission,
                definition?.playersOnly,
                definition?.arguments,
                definition?.subcommands,
                handler
            ),
            "definition"
        )
        return CommandSpec(name, definition?.aliases.orEmpty(), root.syntax) to root
    }

    /** A command's or a subcommand's fields, which are the same but for where the handler comes from. */
    private class Node(
        val description: String?,
        val permission: String?,
        val playersOnly: Boolean?,
        val arguments: List<CommandArgument>?,
        val subcommands: Map<String, Subcommand>?,
        val handler: LuaFunction?
    )

    private fun node(node: Node, where: String): DeclaredNode {
        val arguments = node.arguments.orEmpty().mapIndexed { i, argument -> argument(argument, "$where.arguments[${i + 1}]") }
        var optionalFrom: String? = null
        val seen = HashSet<String>()
        arguments.forEachIndexed { i, declared ->
            val argument = declared.syntax
            if (!seen.add(argument.name)) fail("'$where': two arguments called '${argument.name}'")
            if (argument.optional) {
                optionalFrom = optionalFrom ?: argument.name
            } else if (optionalFrom != null) {
                fail(
                    "argument '${argument.name}' in '$where' has no default but comes after '$optionalFrom', which has one: " +
                        "optional arguments must come last"
                )
            }
            if (i < arguments.size - 1 && argument.type == ArgumentType.TEXT) {
                fail("argument '${argument.name}' in '$where' is text, which takes the rest of the line, so it must be the last")
            }
        }
        if (arguments.isNotEmpty() && node.handler == null) fail("'$where' has arguments but no handler to give them to")
        val subcommands = node.subcommands.orEmpty().toSortedMap().mapValues { (name, sub) ->
            if (!SUBCOMMAND_NAME.matches(name)) {
                fail(
                    "'$where.subcommands' has $name: subcommand names are lowercase letters, digits, _ and -, starting with a letter"
                )
            }
            node(
                Node(sub.description, sub.permission, sub.playersOnly, sub.arguments, sub.subcommands, sub.handler),
                "$where.subcommands.$name"
            )
        }
        if (node.handler == null && subcommands.isEmpty()) fail("'$where' has neither a handler nor subcommands")
        val syntax = CommandSyntax(
            description = node.description.orEmpty(),
            permission = node.permission,
            playersOnly = node.playersOnly == true,
            arguments = arguments.map { it.syntax },
            subcommands = subcommands.mapValues { it.value.syntax },
            runs = node.handler != null
        )
        return DeclaredNode(syntax, node.handler, arguments, subcommands)
    }

    private fun argument(argument: CommandArgument, where: String): DeclaredArgument {
        if (!ARGUMENT_NAME.matches(argument.name)) {
            fail("'$where.name' (\"${argument.name}\") must be letters, digits and _, not starting with a digit")
        }
        // The codec only lets through the types the spec declares.
        val type = ArgumentType.of(argument.type) ?: error("no argument type ${argument.type}")
        val numeric = type == ArgumentType.INTEGER || type == ArgumentType.NUMBER
        for ((bound, value) in listOf("min" to argument.min, "max" to argument.max)) {
            if (value == null) continue
            if (!numeric) fail("'$where.$bound' is only for integer and number arguments")
            if (type == ArgumentType.INTEGER && value != Math.floor(value)) {
                fail("'$where.$bound' must be a whole number for an integer argument")
            }
        }
        if (argument.min != null && argument.max != null && argument.min > argument.max) fail("'$where.min' is more than its max")
        val choices = argument.choices.orEmpty()
        if (type == ArgumentType.CHOICE) {
            if (choices.isEmpty()) fail("'$where' is a choice, so it needs choices")
            choices.firstOrNull { !WORD.matches(it) }?.let {
                fail("'$where.choices' has \"$it\": choices are single words (letters, digits, _ - . +)")
            }
        } else if (argument.choices != null) {
            fail("'$where.choices' is only for choice arguments")
        }
        val default = argument.default?.let { default(it, argument, type, "$where.default") }
        val syntax = ArgumentSyntax(
            name = argument.name,
            type = type,
            optional = argument.default != null,
            min = argument.min,
            max = argument.max,
            choices = choices,
            completes = argument.complete != null
        )
        return DeclaredArgument(syntax, default, argument.complete)
    }

    /** A `default`: `false` (nothing), or a value of the type its handler gets, within the argument's bounds and choices. */
    private fun default(value: dev.netherforge.plugin.lua.LuaValue, argument: CommandArgument, type: ArgumentType, where: String): Any? {
        if (value.read { lua, index -> lua.type(index) == Lua.LuaType.BOOLEAN && !lua.toBoolean(index) }) return false
        val codec = type.codec
        val read = try {
            value.read(codec, where)
        } catch (e: LuaTypeMismatch) {
            throw if (e.where == where) LuaTypeMismatch(where, "${codec.expected} or false", e.got) else e
        }
        if (read is Number) {
            val number = read.toDouble()
            if (argument.min != null && number < argument.min || argument.max != null && number > argument.max) {
                fail("'$where' ($read) is outside min and max")
            }
        }
        if (type == ArgumentType.CHOICE && read !in argument.choices.orEmpty()) fail("'$where' (\"$read\") isn't one of its choices")
        return read
    }

    private fun fail(message: String): Nothing = throw LuaApiException(message)
}
