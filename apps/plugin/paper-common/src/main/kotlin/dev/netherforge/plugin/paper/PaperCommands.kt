package dev.netherforge.plugin.paper

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.LongArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.mojang.brigadier.suggestion.SuggestionProvider
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import com.mojang.brigadier.tree.ArgumentCommandNode
import com.mojang.brigadier.tree.LiteralCommandNode
import com.mojang.brigadier.tree.RootCommandNode
import dev.netherforge.plugin.platform.ArgumentReading
import dev.netherforge.plugin.platform.ArgumentSyntax
import dev.netherforge.plugin.platform.ArgumentValue
import dev.netherforge.plugin.platform.CommandHandler
import dev.netherforge.plugin.platform.CommandInput
import dev.netherforge.plugin.platform.CommandOps
import dev.netherforge.plugin.platform.CommandSender
import dev.netherforge.plugin.platform.CommandSpec
import dev.netherforge.plugin.platform.CommandSyntax
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.platform.SelectedEntity
import dev.netherforge.plugin.platform.SuggestionRequest
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.command.brigadier.argument.ArgumentTypes
import io.papermc.paper.command.brigadier.argument.resolvers.FinePositionResolver
import io.papermc.paper.command.brigadier.argument.resolvers.selector.EntitySelectorArgumentResolver
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.Bukkit
import org.bukkit.block.BlockState
import org.bukkit.command.CommandException
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import java.util.concurrent.CompletableFuture
import com.mojang.brigadier.arguments.ArgumentType as BrigadierType

/**
 * Commands as Brigadier nodes, built from each command's [CommandSyntax]:
 * every argument is a real Brigadier argument (`player` is Minecraft's entity
 * selector, `position` its coordinates, `integer` a bounded long, …), so the
 * player's client highlights, checks and completes what they type, and
 * permissions are node requirements, so a command someone can't use doesn't
 * exist for them. Running one hands the runtime what Brigadier read from the
 * [CommandContext] ([CommandInput]): the subcommands typed and each
 * argument's typed value, selectors and coordinates resolved for the sender.
 * The runtime never reads the line itself.
 *
 * Paper offers its command registrar only inside the `COMMANDS` lifecycle
 * event (when the server starts, and on every datapack reload), but project
 * commands come and go with every module reload. So the event registers
 * every command known at the time through the registrar, and keeps the
 * dispatcher it hands out; between events, commands are added to and
 * removed from that dispatcher's root directly. Adding goes through Paper's
 * own root node, which converts API nodes exactly as the registrar does.
 * Removing uses `removeCommand`, which Paper's Brigadier has but the API's
 * doesn't declare, so it's looked up by reflection (the one place this
 * adapter reaches past the API, and only into Brigadier). Each command is
 * also reachable as `/netherforge:<name>`.
 */
class PaperCommands(private val plugin: Plugin) : CommandOps {
    private val miniMessage = PaperText.mini

    private class Registered(val spec: CommandSpec, val handler: CommandHandler) {
        /** Every label it's reachable by: its name and aliases, each also as `netherforge:<label>`. */
        val labels get() = (listOf(spec.name) + spec.aliases).flatMap { listOf(it, "$NAMESPACE:$it") }
    }

    private val registered = LinkedHashMap<String, Registered>()

    /** The server's dispatcher, as of the last `COMMANDS` event; null before the first. */
    private var dispatcher: CommandDispatcher<CommandSourceStack>? = null
    private var refreshQueued = false

    init {
        plugin.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            val commands = event.registrar()
            dispatcher = commands.dispatcher
            for (command in registered.values) {
                val labels = commands.register(node(command.spec.name, command), command.spec.description, command.spec.aliases)
                if (command.spec.name !in labels) plugin.logger.warning("/${command.spec.name} is another plugin's command too; theirs won")
            }
        }
    }

    override fun register(spec: CommandSpec, handler: CommandHandler): Boolean {
        if (spec.name in registered || Bukkit.getCommandMap().getCommand(spec.name) != null) return false
        if (dispatcher?.root?.getChild(spec.name) != null) return false
        val command = Registered(spec, handler)
        registered[spec.name] = command
        dispatcher?.root?.let { root ->
            for (label in command.labels) {
                if (root.getChild(label) == null) root.addChild(node(label, command))
            }
        }
        refreshClients()
        return true
    }

    override fun unregister(name: String) {
        val command = registered.remove(name) ?: return
        dispatcher?.root?.let { root ->
            for (label in command.labels) remove(root, label)
        }
        refreshClients()
    }

    override fun runConsole(line: String): Boolean = dispatch(Bukkit.getConsoleSender(), line, plugin)

    private fun remove(root: RootCommandNode<CommandSourceStack>, label: String) {
        if (root.getChild(label) == null) return
        try {
            root.javaClass.getMethod("removeCommand", String::class.java).invoke(root, label)
        } catch (e: ReflectiveOperationException) {
            plugin.logger.warning("Couldn't remove /$label from the server's commands ($e); it stays until the next restart")
        }
    }

    /**
     * Sends connected players the new command tree, on the next tick and once
     * per batch of changes: Paper builds the tree on its async pool, and a
     * dispatcher still being changed on the main thread meanwhile is a
     * ConcurrentModificationException in a thread nothing here owns.
     */
    private fun refreshClients() {
        if (refreshQueued || !plugin.isEnabled) return
        refreshQueued = true
        Bukkit.getScheduler().runTask(
            plugin,
            Runnable {
                refreshQueued = false
                for (player in Bukkit.getOnlinePlayers()) player.updateCommands()
            }
        )
    }

    // ---- nodes ------------------------------------------------------------------

    private fun node(label: String, command: Registered): LiteralCommandNode<CommandSourceStack> {
        val root = Commands.literal(label)
        build(root, command.spec.syntax, emptyList(), command)
        return root.build()
    }

    /**
     * Fills [builder] from [syntax]: its permission as a requirement, its
     * subcommands as literals, and its arguments as a chain, each one able to
     * run once every required argument is in. A node that only leads to
     * subcommands runs too, so typing it alone gets the runtime's usage
     * rather than Brigadier's "incomplete command".
     */
    private fun build(builder: ArgumentBuilder<CommandSourceStack, *>, syntax: CommandSyntax, path: List<String>, command: Registered) {
        syntax.permission?.let { permission -> builder.requires { it.sender.hasPermission(permission) } }
        val run = Command<CommandSourceStack> { context ->
            execute(context, command)
            Command.SINGLE_SUCCESS
        }
        if (syntax.required == 0 || !syntax.runs) builder.executes(run)
        for ((name, sub) in syntax.subcommands) {
            val literal = Commands.literal(name)
            build(literal, sub, path + name, command)
            builder.then(literal)
        }
        var next: ArgumentBuilder<CommandSourceStack, *>? = null
        for ((index, argument) in syntax.arguments.withIndex().reversed()) {
            val node = Commands.argument(argument.name, type(argument))
            suggestions(argument, path, command)?.let { node.suggests(it) }
            if (index + 1 >= syntax.required) node.executes(run)
            next?.let { node.then(it) }
            next = node
        }
        next?.let { builder.then(it) }
    }

    private fun type(argument: ArgumentSyntax): BrigadierType<*> = when (argument.type.reading) {
        ArgumentReading.WORD, ArgumentReading.CHOICE -> StringArgumentType.word()
        ArgumentReading.TEXT -> StringArgumentType.greedyString()
        ArgumentReading.INTEGER -> LongArgumentType.longArg(
            argument.min?.toLong() ?: Long.MIN_VALUE,
            argument.max?.toLong() ?: Long.MAX_VALUE
        )
        ArgumentReading.NUMBER -> DoubleArgumentType.doubleArg(argument.min ?: -Double.MAX_VALUE, argument.max ?: Double.MAX_VALUE)
        ArgumentReading.BOOLEAN -> BoolArgumentType.bool()
        ArgumentReading.PLAYER -> ArgumentTypes.player()
        ArgumentReading.PLAYERS -> ArgumentTypes.players()
        ArgumentReading.ENTITY -> ArgumentTypes.entity()
        ArgumentReading.ENTITIES -> ArgumentTypes.entities()
        ArgumentReading.POSITION -> ArgumentTypes.finePosition(true)
        ArgumentReading.BLOCK_STATE -> ArgumentTypes.blockState()
        ArgumentReading.ITEM -> ArgumentTypes.itemStack()
    }

    /**
     * Who suggests what for [argument]: a `choice` its own choices; the
     * runtime anything only it knows ([ArgumentSyntax.suggestedByRuntime]);
     * Brigadier the rest, from its type.
     */
    private fun suggestions(argument: ArgumentSyntax, path: List<String>, command: Registered): SuggestionProvider<CommandSourceStack>? =
        when {
            argument.suggestedByRuntime -> SuggestionProvider { context, builder -> suggest(context, builder, path, argument, command) }
            argument.type.reading == ArgumentReading.CHOICE -> SuggestionProvider { _, builder ->
                for (choice in argument.choices) {
                    if (choice.startsWith(builder.remainingLowerCase)) builder.suggest(choice)
                }
                builder.buildFuture()
            }
            else -> null
        }

    private fun suggest(
        context: CommandContext<CommandSourceStack>,
        builder: SuggestionsBuilder,
        path: List<String>,
        argument: ArgumentSyntax,
        command: Registered
    ): CompletableFuture<Suggestions> {
        val compute = {
            val source = context.source
            val syntax = command.spec.syntax.at(path)
            // What's typed before it, read as running would; one that doesn't read yet is left out.
            val before = syntax?.arguments?.takeWhile { it.name != argument.name }.orEmpty()
                .mapNotNull { earlier -> runCatching { value(context, earlier) }.getOrNull()?.let { earlier.name to it } }
                .toMap()
            val completion = command.handler.suggest(sender(source), SuggestionRequest(path, argument.name, before, builder.remaining))
            val offset = builder.createOffset(builder.start + completion.start)
            for (suggestion in completion.suggestions) offset.suggest(suggestion)
            offset.build()
        }
        // Scripts run on the main thread; Paper asks for suggestions from its own pool.
        if (Bukkit.isPrimaryThread()) return CompletableFuture.completedFuture(compute())
        val future = CompletableFuture<Suggestions>()
        Bukkit.getScheduler().runTask(
            plugin,
            Runnable {
                future.complete(runCatching { compute() }.getOrElse { builder.build() })
            }
        )
        return future
    }

    /**
     * Runs [command] with what Brigadier read. A value Brigadier's type took
     * but that doesn't stand (a selector that picks no one, a word that isn't
     * one of the choices) is a [CommandSyntaxException], which the server
     * answers in red like any of its own.
     */
    private fun execute(context: CommandContext<CommandSourceStack>, command: Registered) {
        // Our command's own nodes. Reached through a redirect (an alias or
        // `/netherforge:<name>` the registrar made), the context is the
        // redirect's child: its root is our command's node, and its nodes are
        // what follows the label, which is the word typed just before them.
        // Otherwise the first node is the label. After `execute … run`, the
        // input holds more before either.
        val redirected = context.rootNode !is RootCommandNode<*>
        val after = if (redirected) context.nodes else context.nodes.drop(1)
        val label = if (redirected) {
            val end = context.nodes.firstOrNull()?.range?.start ?: context.range.start
            context.input.substring(0, end).trimEnd().substringAfterLast(' ').removePrefix("/")
        } else {
            context.nodes.first().node.name
        }.substringAfter("$NAMESPACE:")
        val textStart = after.firstOrNull()?.range?.start ?: context.input.length
        val path = after.map { it.node }.filterIsInstance<LiteralCommandNode<*>>().map { it.literal }
        val syntax = command.spec.syntax.at(path) ?: error("/${command.spec.name} has no subcommand ${path.joinToString(" ")}")
        val typed = after.map { it.node }.filterIsInstance<ArgumentCommandNode<*, *>>().mapTo(HashSet()) { it.name }
        val arguments = LinkedHashMap<String, ArgumentValue>()
        for (argument in syntax.arguments) {
            if (argument.name in typed) arguments[argument.name] = value(context, argument)
        }
        val text = context.input.substring(textStart)
        val sender = sender(context.source)
        try {
            command.handler.run(sender, label, CommandInput(path, arguments, text))
        } catch (e: Exception) {
            plugin.logger.severe("/${command.spec.name} failed: $e")
            sender.reply("<red>/${command.spec.name} failed; see the server log.")
        }
    }

    /** [argument]'s value as Brigadier read it, resolved for the sender: who a selector picks, where `~` is. */
    private fun value(context: CommandContext<CommandSourceStack>, argument: ArgumentSyntax): ArgumentValue {
        val source = context.source
        val name = argument.name
        return when (argument.type.reading) {
            ArgumentReading.WORD, ArgumentReading.TEXT -> ArgumentValue.Text(StringArgumentType.getString(context, name))
            ArgumentReading.CHOICE -> {
                val word = StringArgumentType.getString(context, name)
                if (word !in argument.choices) {
                    throw SimpleCommandExceptionType(
                        LiteralMessage("<$name> must be one of ${argument.choices.joinToString(", ")}, not \"$word\"")
                    ).create()
                }
                ArgumentValue.Text(word)
            }
            ArgumentReading.INTEGER -> ArgumentValue.Integer(LongArgumentType.getLong(context, name))
            ArgumentReading.NUMBER -> ArgumentValue.Decimal(DoubleArgumentType.getDouble(context, name))
            ArgumentReading.BOOLEAN -> ArgumentValue.Bool(BoolArgumentType.getBool(context, name))
            ArgumentReading.PLAYER, ArgumentReading.PLAYERS -> ArgumentValue.Players(
                context.getArgument(name, PlayerSelectorArgumentResolver::class.java).resolve(source).map {
                    PlayerRef(it.uniqueId, it.name)
                }
            )
            ArgumentReading.ENTITY, ArgumentReading.ENTITIES -> ArgumentValue.Entities(
                context.getArgument(name, EntitySelectorArgumentResolver::class.java).resolve(source).map { entity ->
                    SelectedEntity(entity.uniqueId, (entity as? Player)?.let { PlayerRef(it.uniqueId, it.name) })
                }
            )
            ArgumentReading.POSITION -> context.getArgument(name, FinePositionResolver::class.java).resolve(source).let {
                ArgumentValue.Position(it.x(), it.y(), it.z())
            }
            ArgumentReading.BLOCK_STATE -> ArgumentValue.BlockState(stateOf(context.getArgument(name, BlockState::class.java).blockData))
            ArgumentReading.ITEM -> ArgumentValue.Item(
                PaperItems.toItem(context.getArgument(name, ItemStack::class.java))
                    ?: throw SimpleCommandExceptionType(LiteralMessage("<$name> isn't an item")).create()
            )
        }
    }

    private fun sender(source: CommandSourceStack): CommandSender {
        val bukkit = source.sender
        return CommandSender(
            (bukkit as? Player)?.let { PlayerRef(it.uniqueId, it.name) },
            bukkit.name,
            PaperPlatform.location(source.location),
            { bukkit.hasPermission(it) }
        ) { bukkit.sendMessage(miniMessage.deserialize(it)) }
    }

    companion object {
        private const val NAMESPACE = "netherforge"

        /**
         * Runs [line] as [sender]. False when it didn't run, including a line
         * Brigadier can't parse, which `dispatchCommand` throws for rather
         * than answering false.
         */
        fun dispatch(sender: org.bukkit.command.CommandSender, line: String, plugin: Plugin): Boolean = try {
            Bukkit.dispatchCommand(sender, line)
        } catch (e: CommandException) {
            plugin.logger.warning("/$line didn't run: ${e.cause?.message ?: e.message}")
            false
        }
    }
}
