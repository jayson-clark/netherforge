package dev.netherforge.plugin.command

import dev.netherforge.plugin.binding.CONSOLE_SENDER
import dev.netherforge.plugin.binding.CommandDefinition
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.lua.CallResult
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.lua.LuaCodecs
import dev.netherforge.plugin.lua.LuaFunction
import dev.netherforge.plugin.lua.LuaList
import dev.netherforge.plugin.lua.LuaTypeMismatch
import dev.netherforge.plugin.lua.ScriptFailure
import dev.netherforge.plugin.platform.ArgumentSyntax
import dev.netherforge.plugin.platform.ArgumentValue
import dev.netherforge.plugin.platform.CommandHandler
import dev.netherforge.plugin.platform.CommandInput
import dev.netherforge.plugin.platform.CommandOps
import dev.netherforge.plugin.platform.CommandSender
import dev.netherforge.plugin.platform.CommandSpec
import dev.netherforge.plugin.platform.CommandSyntax
import dev.netherforge.plugin.platform.Completion
import dev.netherforge.plugin.platform.SuggestionRequest
import dev.netherforge.plugin.script.Scope
import dev.netherforge.plugin.script.ScopeOwner
import dev.netherforge.plugin.script.Scripts
import dev.netherforge.plugin.session.RuntimeService

/**
 * The commands modules declare (`nf.commands.register`), on the server for as
 * long as the module that declared them runs.
 *
 * The server reads what's typed against a command's syntax and hands over
 * what it read ([CommandInput]); this checks what only the runtime knows (the
 * permission and `players_only` of each subcommand typed, the project's own
 * names), answers a mistake there with a red line and the usage, turns each
 * value into what the handler gets, fills in the defaults, and calls the
 * handler with a `CommandEvent`.
 */
internal class ProjectCommands(private val ops: CommandOps, private val scripts: Scripts, private val values: ArgumentValues) :
    RuntimeService {
    override val name get() = "commands"

    private class Declared(val spec: CommandSpec, val scope: Scope, val root: DeclaredNode)

    private val commands = LinkedHashMap<String, Declared>()

    /**
     * Declares `/name` for [scope] from its [definition] and [handler] (a
     * handler alone in the definition's place is the same as no definition).
     * False, with nothing registered, when the name is already a command
     * (another module's, or the server's). A mistake in the definition is a
     * [LuaApiException].
     */
    fun declare(scope: Scope, name: String, definition: CommandDefinition?, handler: LuaFunction?): Boolean {
        if (scope.owner !is ScopeOwner.Module) {
            throw LuaApiException("only modules can declare commands; a centity can be removed while someone is typing one")
        }
        if (!COMMAND_NAME.matches(name) || name in RESERVED) {
            throw LuaApiException("\"$name\" can't be a command name (lowercase letters, digits, _ and -, starting with a letter)")
        }
        val (spec, root) = CommandDefinitions.declare(name, definition, handler)
        spec.aliases.firstOrNull { !COMMAND_NAME.matches(it) || it in RESERVED }?.let {
            throw LuaApiException("\"$it\" can't be an alias (lowercase letters, digits, _ and -, starting with a letter)")
        }
        if (name in commands || !ops.register(spec, Handler(name))) {
            release(root)
            return false
        }
        commands[name] = Declared(spec, scope, root)
        return true
    }

    /** Names of every project command, with the scope that declared it. */
    fun all(): Map<String, Scope> = commands.mapValues { it.value.scope }

    override fun scopeReleased(scope: Scope) {
        for ((name, declared) in commands.filterValues { it.scope == scope }) {
            commands.remove(name)
            ops.unregister(name)
            release(declared.root)
        }
    }

    private fun release(root: DeclaredNode) {
        for (function in root.functions()) scripts.unref(function.ref)
    }

    /** A project command as the server calls it. */
    private inner class Handler(private val name: String) : CommandHandler {
        override fun run(sender: CommandSender, label: String, input: CommandInput) {
            val declared = commands[name]
            if (declared == null || !declared.scope.live) {
                sender.reply("<red>/$name isn't available right now: the module that declared it has stopped.")
                return
            }
            val node = declared.root.at(input.path) ?: error("/$name has no subcommand ${input.path.joinToString(" ")}")
            refusal(declared.spec.syntax, input.path, sender)?.let { (refusal, upTo) ->
                val typed = (listOf(label) + input.path.take(upTo)).joinToString(" ")
                sender.reply(
                    when (refusal) {
                        Refusal.PERMISSION -> "<red>You don't have permission to use /$typed."
                        Refusal.PLAYERS_ONLY -> "<red>Only players can use /$typed."
                    }
                )
                return
            }
            val handler = node.handler
            if (handler == null) {
                for (line in usage(label, input.path, node.syntax, sender)) sender.reply("<gray>Usage: $line")
                return
            }
            val arguments = try {
                arguments(node, input.arguments, sender, defaults = true)
            } catch (problem: CommandProblem) {
                sender.reply("<red>${problem.message}")
                for (line in usage(label, input.path, node.syntax, sender)) sender.reply("<gray>Usage: $line")
                return
            }
            val result = scripts.callKept<Any?>(declared.scope, handler.ref, listOf(event(sender, label, input.text, arguments)), "command")
            if (result is CallResult.Failed) {
                scripts.handlerFailed(declared.scope, "/$name", result.failure, false)
                sender.reply("<red>/$name failed: ${result.failure.message}")
            }
        }

        override fun suggest(sender: CommandSender, request: SuggestionRequest): Completion {
            val none = Completion(request.partial.length, emptyList())
            val declared = commands[name]?.takeIf { it.scope.live } ?: return none
            val node = declared.root.at(request.path) ?: return none
            val argument = node.arguments.firstOrNull { it.syntax.name == request.argument } ?: return none
            val complete = argument.complete
                ?: return Completion(0, values.suggestions(argument.syntax.type).matching(request.partial))
            val before = try {
                arguments(node, request.arguments, sender, defaults = false)
            } catch (_: CommandProblem) {
                return none
            }
            val event = event(sender, name, request.partial, before)
            val what = "completing /$name <${argument.syntax.name}>"
            val result = try {
                scripts.callKept(declared.scope, complete.ref, listOf(event, request.partial), "complete", SUGGESTIONS, SUGGESTIONS_WHERE)
            } catch (e: LuaApiException) {
                // An answer that isn't a list of strings is the script's mistake, reported like any.
                val argumentName = argument.syntax.name
                val message = when {
                    e !is LuaTypeMismatch -> "complete for argument '$argumentName' must return a list of strings (${e.message})"
                    e.where == SUGGESTIONS_WHERE -> "complete for argument '$argumentName' must return a list of strings, not a ${e.got}"
                    else -> "complete for argument '$argumentName' returned a ${e.got} at ${e.where.removePrefix(SUGGESTIONS_WHERE)}: " +
                        "suggestions are strings"
                }
                CallResult.Failed(ScriptFailure(message, null, null, null))
            }
            if (result is CallResult.Failed) scripts.handlerFailed(declared.scope, what, result.failure, false)
            @Suppress("UNCHECKED_CAST")
            val offered = (result as? CallResult.Ok)?.value as? List<String> ?: return none
            return Completion(0, offered.matching(request.partial))
        }

        private fun event(sender: CommandSender, label: String, input: String, arguments: Map<String, Any?>): Map<String, Any?> {
            val player = sender.player?.let { LuaHandle.Player(it.uuid.toString()) }
            return mapOf(
                "name" to name,
                "label" to label,
                "input" to input,
                "arguments" to arguments,
                "sender" to LuaHandle.Sender(player?.id ?: CONSOLE_SENDER),
                "player" to player
            )
        }
    }

    /** Each argument of [node] the server read, as its handler gets it, and (with [defaults]) the default of each left out. */
    private fun arguments(
        node: DeclaredNode,
        read: Map<String, ArgumentValue>,
        sender: CommandSender,
        defaults: Boolean
    ): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for (argument in node.arguments) {
            val value = read[argument.syntax.name]
            when {
                value != null -> out[argument.syntax.name] = values.resolve(argument.syntax, value, sender)
                defaults -> out[argument.syntax.name] = argument.default
            }
        }
        return out
    }

    private enum class Refusal { PERMISSION, PLAYERS_ONLY }

    /**
     * Why [sender] can't use what [path] leads to from [root], and how much of
     * [path] that's about: a permission along the way (up to the node that
     * needs it), or `players_only` on any of it (all of it).
     */
    private fun refusal(root: CommandSyntax, path: List<String>, sender: CommandSender): Pair<Refusal, Int>? {
        var node = root
        var playersOnly = node.playersOnly
        if (node.permission?.let(sender::hasPermission) == false) return Refusal.PERMISSION to 0
        for ((i, name) in path.withIndex()) {
            node = node.subcommands.getValue(name)
            if (node.permission?.let(sender::hasPermission) == false) return Refusal.PERMISSION to i + 1
            playersOnly = playersOnly || node.playersOnly
        }
        return if (playersOnly && sender.player == null) Refusal.PLAYERS_ONLY to path.size else null
    }

    private fun List<String>.matching(typed: String) = filter { it.startsWith(typed, ignoreCase = true) }

    companion object {
        private val COMMAND_NAME = Regex("^[a-z][a-z0-9_-]{0,31}$")
        private val RESERVED = setOf("nf", "netherforge")

        private val SUGGESTIONS = LuaList(LuaCodecs.STRING)
        private const val SUGGESTIONS_WHERE = "suggestions"

        /**
         * How [node] (reached by [path] from `/label`) can be typed: one line for
         * itself when it runs, and one per subcommand the sender may use.
         */
        fun usage(label: String, path: List<String>, node: CommandSyntax, sender: CommandSender): List<String> {
            val prefix = (listOf("/$label") + path).joinToString(" ")
            val lines = mutableListOf<String>()
            if (node.runs) lines += (listOf(prefix) + node.arguments.map(::shape)).joinToString(" ")
            for ((name, sub) in node.subcommands) {
                if (sub.permission != null && !sender.hasPermission(sub.permission)) continue
                val parts = listOf("$prefix $name") + if (sub.runs) sub.arguments.map(::shape) else emptyList()
                lines += parts.joinToString(" ") + if (sub.subcommands.isNotEmpty()) " …" else ""
            }
            return lines
        }

        private fun shape(argument: ArgumentSyntax) = if (argument.optional) "[${argument.name}]" else "<${argument.name}>"
    }
}
