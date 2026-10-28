package dev.netherforge.plugin.testkit

import dev.netherforge.format.game.has
import dev.netherforge.plugin.platform.CommandHandler
import dev.netherforge.plugin.platform.CommandOps
import dev.netherforge.plugin.platform.CommandSender
import dev.netherforge.plugin.platform.CommandSpec
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.SelectedEntity

// Commands on the fake server, read by `FakeBrigadier`.

class FakeCommands(private val platform: FakePlatform) : CommandOps {
    val registered = LinkedHashMap<String, Pair<CommandSpec, CommandHandler>>()
    val console = mutableListOf<String>()
    val consoleReplies = mutableListOf<String>()

    /** Names the "server" already has, which a project can't take. */
    val taken = mutableSetOf("help", "tp")

    override fun register(spec: CommandSpec, handler: CommandHandler): Boolean {
        if (spec.name in taken || spec.name in registered) return false
        registered[spec.name] = spec to handler
        for (alias in spec.aliases) registered.putIfAbsent(alias, spec to handler)
        return true
    }

    override fun unregister(name: String) {
        val spec = registered[name]?.first ?: return
        registered.remove(name)
        for (alias in spec.aliases) registered.remove(alias)
    }

    /** Item text the "server" reads with components, as `item` arguments do on Paper; a bare item id it reads itself. */
    val items = mutableMapOf<String, ItemData>()

    /** The fake server's command parser: what Brigadier is on Paper. */
    private val brigadier = FakeBrigadier(platform, { items[it] }) { sender, selector ->
        select(sender.player?.let { platform.players.byId[it.uuid] }, selector)
    }

    override fun runConsole(line: String): Boolean {
        console += line
        return dispatch(consoleSender(), line)
    }

    /** Who the console is: at the world spawn, with every permission, its replies in [consoleReplies]. */
    fun consoleSender() = CommandSender(null, "CONSOLE", platform.worlds.spawnLocation("world"), { true }) { consoleReplies += it }

    fun sender(player: FakePlayer) = CommandSender(player.ref, player.ref.name, player.location, { player.has(it) }) {
        player.messages += it
    }

    /**
     * A selector as the fake reads it: `@a`, `@e` (everyone online: the fake
     * has no other entities to pick), `@p` (the nearest player), `@r` (the
     * first) and `@s`, ignoring anything in brackets.
     */
    private fun select(self: FakePlayer?, selector: String): List<SelectedEntity> {
        val online = platform.players.byId.values.toList()
        val picked = when (selector.substringBefore('[')) {
            "@a", "@e" -> online
            "@p" -> listOfNotNull(
                online.minByOrNull { other ->
                    val from = self?.location ?: platform.worlds.spawnLocation(platform.worlds.defaultWorld())!!
                    (other.location.x - from.x).let { it * it } + (other.location.z - from.z).let { it * it }
                }
            )
            "@r" -> online.take(1)
            "@s" -> listOfNotNull(self)
            else -> throw IllegalArgumentException("Unknown selector type '$selector'")
        }
        return picked.map { SelectedEntity(it.ref.uuid, it.ref) }
    }

    /**
     * Runs [line] as [sender], as the server would: read against the
     * command's syntax, a mistake there answered in red by the "server"
     * itself, what it read handed to the handler. False when there's no
     * such command.
     */
    fun dispatch(sender: CommandSender, line: String): Boolean {
        val text = line.removePrefix("/").trimStart()
        val label = text.substringBefore(' ')
        val (spec, handler) = registered[label] ?: return false
        when (val read = brigadier.read(spec.syntax, sender, text.substringAfter(' ', ""))) {
            is FakeBrigadier.Read.Ready -> handler.run(sender, label, read.input)
            is FakeBrigadier.Read.Refused -> sender.reply("<red>${read.message}")
        }
        return true
    }

    /** Runs a command as [player], returning what they were told. */
    fun run(player: FakePlayer, line: String): List<String> {
        val before = player.messages.size
        check(dispatch(sender(player), line)) { "no command for $line" }
        return player.messages.drop(before)
    }

    /** Runs a command as the console, returning what it was told. */
    fun runAsConsole(line: String): List<String> {
        val before = consoleReplies.size
        check(dispatch(consoleSender(), line)) { "no command for $line" }
        return consoleReplies.drop(before)
    }

    /** What tab completion offers [player] at the end of [line] (a command line without its slash). */
    fun complete(player: FakePlayer, line: String): List<String> {
        val label = line.substringBefore(' ')
        val (spec, handler) = registered[label] ?: error("no command for $line")
        val sender = sender(player)
        return brigadier.complete(spec.syntax, sender, line.substringAfter(' ', "")) { handler.suggest(sender, it) }
    }
}
