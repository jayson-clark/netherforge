package dev.netherforge.plugin.admin
import dev.netherforge.format.ref.ResourceRef
import dev.netherforge.plugin.NetherForgeRuntime
import dev.netherforge.plugin.data.ScriptData
import dev.netherforge.plugin.platform.CommandSender
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * `/nf data`: what the runtime keeps in its store, for an operator to look
 * at, since a database isn't a file to open in an editor.
 *
 * - `/nf data`: every table and how many rows it has (by namespace where rows say whose they are);
 * - `/nf data <table>`: a table's first rows;
 * - `/nf data show <player|centity|named> <who>`: one saved table's JSON, as it is now;
 * - `/nf data export`: everything, as JSON, into `exports/` in the plugin's folder.
 *
 * What's shown is what the store has once everything changed so far is
 * saved: saved tables and centities' places are saved first, as the world
 * saving would.
 */
internal class DataCommand(private val runtime: NetherForgeRuntime) {
    private val store get() = runtime.store

    fun run(sender: CommandSender, args: List<String>) {
        when (val first = args.firstOrNull()?.lowercase(Locale.ROOT)) {
            null -> sizes(sender)
            "show" -> show(sender, args.drop(1))
            "export" -> export(sender)
            else -> rows(sender, first)
        }
    }

    /** Completions for the words after `data`, the last being typed. */
    fun options(args: List<String>): List<String> = when (args.size) {
        1 -> listOf("show", "export") + store.tableNames()
        2 -> if (args[0].equals("show", ignoreCase = true)) listOf(PLAYER, CENTITY, NAMED) else emptyList()
        3 -> when (args[0].equals("show", ignoreCase = true) to args[1].lowercase(Locale.ROOT)) {
            true to PLAYER -> runtime.platform.players.online().map { it.name }
            true to CENTITY -> runtime.session.centities.records().map { it.id.toString().take(SHORT) }
            else -> emptyList()
        }
        else -> emptyList()
    }

    private fun sizes(sender: CommandSender) {
        save()
        val file = store.file
        val bytes = runCatching { java.nio.file.Files.size(file) }.getOrDefault(0L)
        sender.reply("<gold>${file.fileName} <gray>(${"%.1f".format(Locale.ROOT, bytes / KIB)} KiB, $file)")
        for (size in store.sizes()) {
            val namespaces = size.byNamespace.entries.joinToString(", ") { "${it.key} ${it.value}" }.takeIf { it.isNotEmpty() }
            sender.reply("<yellow>${size.table} <white>${size.rows}" + namespaces?.let { " <gray>($it)" }.orEmpty())
        }
        sender.reply("<gray>/nf data <table>, /nf data show <player|centity|named> <who>, /nf data export")
    }

    private fun rows(sender: CommandSender, table: String) {
        save()
        val (rows, total) = store.rows(table, MAX_ROWS)
        if (rows.isEmpty()) {
            sender.reply("<gray>$table is empty.")
            return
        }
        sender.reply("<gold>$table <gray>($total ${if (total == 1) "row" else "rows"})")
        for (row in rows) {
            sender.reply(
                "<white>" + row.entries.joinToString("<gray>, <white>") { (column, value) -> "<gray>$column=<white>${clip(value)}" }
            )
        }
        if (total > rows.size) sender.reply("<gray>…and ${total - rows.size} more: /nf data export")
    }

    private fun show(sender: CommandSender, args: List<String>) {
        val kind = args.getOrNull(0)?.lowercase(Locale.ROOT)
        val who = args.getOrNull(1) ?: throw IllegalArgumentException("/nf data show <player|centity|named> <who>")
        val (owner, label) = when (kind) {
            PLAYER -> player(who).let { ScriptData.Owner.Player(it) to "player $who" }
            CENTITY -> instance(who).let { ScriptData.Owner.Centity(it) to "centity $it" }
            NAMED -> named(who).let { it to "nf.data(\"${it.name}\") of ${it.namespace}" }
            else -> throw IllegalArgumentException("/nf data show <player|centity|named> <who>")
        }
        runtime.session.data.save(owner)
        val text = store.tables.read(owner)
        if (text == null) {
            sender.reply("<gray>Nothing is saved for $label.")
            return
        }
        val lines = runCatching {
            PRETTY.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(text))
        }.getOrDefault(text).lines()
        sender.reply("<gold>$label <gray>(${text.encodeToByteArray().size} bytes)")
        for (line in lines.take(MAX_LINES)) sender.reply("<white>$line")
        if (lines.size > MAX_LINES) sender.reply("<gray>…and ${lines.size - MAX_LINES} more lines: /nf data export")
    }

    private fun export(sender: CommandSender) {
        save()
        val name = "store-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.json"
        val file = runtime.config.stateDirectory.resolve(EXPORTS).resolve(name)
        sender.reply("<gold>Exporting the store…")
        store.export(file).whenCompleteAsync({ written, failure ->
            if (failure != null) {
                runtime.log.error("Couldn't export the store to $file", failure)
                sender.reply("<red>Couldn't export the store: ${(failure.cause ?: failure).message}")
            } else {
                sender.reply("<green>Exported the store to $written")
            }
        }, runtime.mainThread)
    }

    /** Every saved table that changed and every centity's place, staged, so what's shown is now. */
    private fun save() {
        val session = runtime.session
        if (!session.running) return
        session.data.saveAll()
        session.centities.savePlacements()
    }

    private fun player(who: String): UUID = runtime.platform.players.find(who)?.uuid
        ?: runCatching { UUID.fromString(who) }.getOrNull()
        ?: throw IllegalArgumentException("no player \"$who\" online; give their UUID")

    private fun instance(prefix: String): UUID {
        val matches = runtime.session.centities.records().map { it.id }.filter { it.toString().startsWith(prefix.lowercase(Locale.ROOT)) }
        require(matches.isNotEmpty()) { "no instance \"$prefix\"" }
        require(matches.size == 1) { "\"$prefix\" matches ${matches.size} instances; give more of the id" }
        return matches.single()
    }

    private fun named(who: String): ScriptData.Owner.Named {
        val home = runtime.session.namespace
        val key =
            ResourceRef(who).resolve(home) ?: throw IllegalArgumentException("\"$who\" isn't a data table's name (name, or namespace:name)")
        return ScriptData.Owner.Named(key.namespace, key.path)
    }

    private fun clip(value: String) = if (value.length > MAX_VALUE) value.take(MAX_VALUE) + "…" else value

    companion object {
        const val PLAYER = "player"
        const val CENTITY = "centity"
        const val NAMED = "named"

        /** Where exports go, in the plugin's folder. */
        const val EXPORTS = "exports"

        private const val MAX_ROWS = 20
        private const val MAX_LINES = 40
        private const val MAX_VALUE = 60
        private const val SHORT = 8
        private const val KIB = 1024.0

        private val PRETTY = Json { prettyPrint = true }
    }
}
