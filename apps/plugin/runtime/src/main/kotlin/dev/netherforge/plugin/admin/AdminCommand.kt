package dev.netherforge.plugin.admin
import dev.netherforge.format.project.Kinds
import dev.netherforge.format.project.ProjectManifest
import dev.netherforge.format.settings.BooleanSetting
import dev.netherforge.format.settings.ChoiceSetting
import dev.netherforge.plugin.NetherForgeRuntime
import dev.netherforge.plugin.centity.Instance
import dev.netherforge.plugin.network.NetworkLink
import dev.netherforge.plugin.platform.ArgumentValue
import dev.netherforge.plugin.platform.CommandHandler
import dev.netherforge.plugin.platform.CommandInput
import dev.netherforge.plugin.platform.CommandSender
import dev.netherforge.plugin.platform.Completion
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.platform.SuggestionRequest
import dev.netherforge.plugin.script.ScriptCosts
import dev.netherforge.plugin.settings.SettingsDialog
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.sqrt

/**
 * `/netherforge` (alias `/nf`): what a server operator does by hand.
 *
 * Lives in the runtime rather than the adapter so it's tested against the
 * fake platform and every adapter gets the same command for free.
 */
internal class AdminCommand(private val runtime: NetherForgeRuntime) : CommandHandler {
    private val subcommands = listOf(
        "spawn", "list", "find", "kill", "tp", "reload", "modules", "scripts", "profile", "pack", "data", "settings", "requires",
        "schedules", "network"
    )
    private val data = DataCommand(runtime)

    override fun run(sender: CommandSender, label: String, input: CommandInput) {
        val text = (input.arguments[ARGUMENTS] as? ArgumentValue.Text)?.text.orEmpty()
        val args = text.split(' ').filter { it.isNotEmpty() }
        try {
            when (args.firstOrNull()?.lowercase(Locale.ROOT)) {
                "spawn" -> spawn(sender, args.drop(1))
                "list" -> list(sender, null)
                "find" -> list(sender, args.getOrNull(1) ?: return usage(sender, "find <centity>"))
                "kill" -> kill(sender, args.drop(1))
                "tp" -> teleport(sender, args.getOrNull(1) ?: return usage(sender, "tp <instance>"))
                "reload" -> reload(sender, args.drop(1))
                "modules" -> modules(sender)
                "scripts" -> scripts(sender, args.getOrNull(1).equals("all", ignoreCase = true))
                "profile" -> profile(sender, args.getOrNull(1))
                "pack" -> pack(sender, args.drop(1))
                "data" -> data.run(sender, args.drop(1))
                "settings" -> settings(sender, args.drop(1), text)
                "requires" -> requires(sender)
                "schedules" -> schedules(sender)
                "network" -> network(sender)
                else -> sender.reply("<gray>/nf ${subcommands.joinToString("|")}")
            }
        } catch (e: IllegalArgumentException) {
            sender.reply("<red>${e.message}")
        } catch (e: IllegalStateException) {
            sender.reply("<red>${e.message}")
        }
    }

    private fun usage(sender: CommandSender, text: String) = sender.reply("<gray>/nf $text")

    private fun spawn(sender: CommandSender, args: List<String>) {
        val centity = args.firstOrNull() ?: return usage(sender, "spawn <centity> [player]")
        val player = args.getOrNull(1)?.let { requireNotNull(runtime.platform.players.find(it)) { "no player \"$it\" online" } }
            ?: sender.player
            ?: throw IllegalArgumentException("say which player to spawn it at: /nf spawn $centity <player>")
        val instance = runtime.spawnNear(centity, player)
        sender.reply("<green>Spawned $centity <gray>${short(instance.id)} at ${where(instance.anchor)}")
    }

    private fun list(sender: CommandSender, centity: String?) {
        val here = sender.player?.let { runtime.platform.players.location(it.uuid) }
        val instances = runtime.session.centities.all().filter { centity == null || it.centity == centity }
            .sortedBy { distance(here, it.anchor) }
        val centities = runtime.session.centities
        val inert = centities.inertRecords().filter { centity == null || centities.centityOf(it) == centity }
        if (instances.isEmpty() && inert.isEmpty()) {
            sender.reply("<gray>No ${centity ?: "centities"} spawned.")
            return
        }
        sender.reply("<gold>${instances.size + inert.size} spawned:")
        for (instance in instances) {
            val distance = distance(here, instance.anchor).takeIf {
                it < Double.MAX_VALUE
            }?.let { " <gray>(%.0f blocks)".format(it) }.orEmpty()
            sender.reply("<yellow>${short(instance.id)} <white>${instance.centity} <gray>${where(instance.anchor)}$distance")
        }
        for (record in inert) {
            sender.reply("<dark_gray>${short(record.id)} ${centities.centityOf(record)} (definition missing) ${where(record.anchor)}")
        }
    }

    private fun kill(sender: CommandSender, args: List<String>) {
        val target = args.firstOrNull() ?: return usage(sender, "kill <instance|centity|all>")
        val instances = runtime.session.centities.all()
        val chosen: List<Instance> = when {
            target == "all" -> instances
            instances.any { it.centity == target } -> instances.filter { it.centity == target }
            else -> listOf(byPrefix(target))
        }
        chosen.forEach { runtime.session.centities.remove(it) }
        var removed = chosen.size
        if (target == "all") {
            runtime.session.centities.inertRecords().forEach {
                if (runtime.session.centities.removeInert(it.id)) removed++
            }
        }
        sender.reply("<green>Removed $removed.")
    }

    private fun teleport(sender: CommandSender, target: String) {
        val player = sender.player ?: throw IllegalArgumentException("only a player can teleport")
        val instance = byPrefix(target)
        runtime.platform.players.teleport(player.uuid, instance.anchor)
        sender.reply("<green>Teleported to ${instance.centity} ${short(instance.id)}.")
    }

    private fun reload(sender: CommandSender, paths: List<String>) {
        val result = if (paths.isEmpty()) runtime.reloadAll() else runtime.reload(paths)
        if (result.resources.isEmpty()) {
            sender.reply("<gray>Nothing to reload for ${paths.joinToString()}.")
            return
        }
        for (resource in result.resources) {
            val color = if (resource.ok) "green" else "red"
            val extra = if (resource.reattached > 0) " <gray>(${resource.reattached} reattached)" else ""
            sender.reply("<$color>${resource.label}: ${if (resource.ok) "reloaded" else "failed"}$extra")
            for (problem in resource.problems.take(MAX_PROBLEMS)) {
                sender.reply("<gray>  ${problem.file}${problem.line?.let { ":$it" }.orEmpty()}: ${problem.message}")
            }
        }
        if (result.restart) {
            sender.reply(
                "<yellow>The server learns these only as it starts: restart it to run them (until then it runs what it started with)."
            )
        }
    }

    /** What the project and its packages declare they need (`requires`), each with who declares it: what running it asks of this server. */
    private fun requires(sender: CommandSender) {
        val combined = runtime.session.requirements.combined()
        if (combined.isEmpty()) {
            sender.reply("<gray>The project and its packages require nothing beyond what every project may do.")
            return
        }
        sender.reply("<gold>The project and its packages require:")
        for (use in combined) sender.reply("<yellow>${use.requirement} <gray>declared by ${use.packages.joinToString()}")
        val routes = runtime.session.web.all()
        if (routes.isEmpty()) return
        val served = runtime.webBase()?.let { "served at $it" } ?: "not served: web.enabled is off in config.yml"
        sender.reply("<gold>Web routes <gray>($served):")
        for (route in routes) {
            val path = "/${route.namespace}${route.path.text.takeIf { it != "/" }.orEmpty()}"
            sender.reply("<yellow>${route.method} $path <gray>(${route.namespace}${if (route.internal != null) ", login flow" else ""})")
        }
    }

    /**
     * `/nf network`: this server's network: how it's in one (or why not), whether it's connected, every server with
     * its players, and what this session listens to.
     */
    private fun network(sender: CommandSender) {
        val link = runtime.network
        when (link.mode) {
            NetworkLink.Mode.OFF -> {
                val why = runtime.config.network.problem() ?: "network.enabled is off in config.yml"
                return sender.reply("<gray>This server isn't part of a network: $why.")
            }
            NetworkLink.Mode.LOCAL -> sender.reply(
                "<gold>A network of this server alone, <yellow>${link.name}<gold>, as on every dev server not in one."
            )
            NetworkLink.Mode.REMOTE -> {
                val config = runtime.config.network
                val state = if (link.connected) "<green>connected" else "<red>not connected (trying again)"
                sender.reply("<gold>In the network as <yellow>${link.name}<gold> through its hub at ${config.host}:${config.port}: $state")
            }
        }
        val counts = link.players.values.groupingBy { it.server }.eachCount()
        for ((server, linked) in link.servers) {
            val shown = if (linked) "<green>connected" else "<gray>not connected"
            sender.reply("<yellow>$server <gray>$shown, ${counts[server] ?: 0} online")
        }
        val session = runtime.session.network
        sender.reply("<gray>Listening to ${session.listening().first.size} channels and ${session.listening().second.size} maps.")
    }

    /**
     * `/nf schedules`: every live `nf.schedule`, soonest first: its rule, its id if it has one, the script that
     * made it, and when it runs next in the server owner's time zone (`schedules.time-zone`).
     */
    private fun schedules(sender: CommandSender) {
        val service = runtime.session.schedules
        val entries = service.all()
        if (entries.isEmpty()) {
            sender.reply("<gray>No schedules are running.")
            return
        }
        val now = runtime.wallClock.instant()
        val zone = service.zone
        sender.reply("<gold>${count(entries.size, "schedule")} <gray>(times in $zone)")
        for (entry in entries.sortedBy { service.nextRun(it) ?: Instant.MAX }) {
            val next = service.nextRun(entry)
            val whenText = if (next == null) {
                "never"
            } else {
                val left = Duration.between(now, next).coerceAtLeast(Duration.ZERO).toMinutes()
                "${next.atZone(zone).format(NEXT_RUN)} <gray>(in ${minutes(left)})"
            }
            val id = entry.id?.let { " <gray>$it" }.orEmpty()
            sender.reply("<yellow>${entry.recurrence.describe()}$id <white>${entry.scope.title} <gray>next <white>$whenText")
        }
    }

    /** A length of whole minutes as `2d 3h 5m`, units that are 0 left out; under a minute is `under a minute`. */
    private fun minutes(total: Long): String {
        if (total < 1) return "under a minute"
        val parts = listOf(total / 1440 to "d", total % 1440 / 60 to "h", total % 60 to "m")
        return parts.filter { it.first > 0 }.joinToString(" ") { "${it.first}${it.second}" }
    }

    private fun modules(sender: CommandSender) {
        val ids = runtime.session.modules.ids()
        if (ids.isEmpty()) {
            sender.reply("<gray>This project has no modules.")
            return
        }
        val commands = runtime.session.commands.all().entries.groupBy({ it.value.module }, { "/" + it.key })
        for (id in ids) {
            val status = runtime.session.modules.status(id)
            val color = when (status) {
                dev.netherforge.plugin.module.Modules.Status.RUNNING -> "green"
                dev.netherforge.plugin.module.Modules.Status.FAILED -> "red"
                dev.netherforge.plugin.module.Modules.Status.IDLE -> "gray"
            }
            val declared = commands[id]?.joinToString(" ")?.let { " <gray>$it" }.orEmpty()
            sender.reply("<$color>$id <white>${status.label}$declared")
        }
    }

    /**
     * `/nf scripts [all]`: every running script (each module, and each
     * centity instance's, menu window's and dialog's script), the most
     * expensive first: its average and worst milliseconds a tick over the last
     * [ScriptCosts.WINDOW] ticks, then what it holds (memory once it's a
     * megabyte or more). The top [MAX_SCRIPTS] unless `all`.
     */
    private fun scripts(sender: CommandSender, all: Boolean) {
        val entries = runtime.session.costs.report()
        if (entries.isEmpty()) {
            sender.reply("<gray>No scripts are running.")
            return
        }
        val total = entries.sumOf { it.averageMillis }
        sender.reply(
            "<gold>${entries.size} ${if (entries.size == 1) "script" else "scripts"}, ${ms(total)} ms a tick " +
                "<gray>(average / max ms a tick over the last ${ScriptCosts.WINDOW} ticks)"
        )
        val shown = if (all) entries else entries.take(MAX_SCRIPTS)
        for (entry in shown) {
            val holds = listOf(
                count(entry.calls, "call"),
                count(entry.subscriptions, "subscription"),
                count(entry.tasks, "task")
            ) + entry.counts.filterValues { it > 0 }.map { (name, n) -> if (n == 1) "1 ${name.removeSuffix("s")}" else "$n $name" } +
                listOfNotNull((entry.bytes / BYTES_PER_MB).takeIf { it > 0 }?.let { "$it MB" })
            sender.reply(
                "<yellow>${ms(
                    entry.averageMillis
                )} <gray>/ ${ms(entry.maxMillis)} <white>${entry.scope.title} <gray>${holds.joinToString(", ")}"
            )
        }
        if (shown.size < entries.size) sender.reply("<gray>…and ${entries.size - shown.size} more: /nf scripts all")
    }

    private fun ms(value: Double) = "%.2f".format(Locale.ROOT, value)

    private fun count(n: Int, noun: String) = "$n $noun${if (n == 1) "" else "s"}"

    /**
     * `/nf profile <seconds>`: records every tick for that long (what each
     * step of the tick, each scope and each function scripts ran took,
     * exactly) and writes a report into the plugin's `profiles` folder, then
     * says where. One at a time.
     */
    private fun profile(sender: CommandSender, seconds: String?) {
        val left = runtime.profiler.recordingLeft(runtime.ticks)
        if (seconds == null) {
            return usage(sender, "profile <seconds>" + (left?.let { " (one is recording: $it s left)" }.orEmpty()))
        }
        val count = seconds.toIntOrNull() ?: throw IllegalArgumentException("say how many seconds to profile: /nf profile 30")
        runtime.profile(count) { sender.reply("<green>$it") }
        sender.reply("<gold>Profiling for $count s<gray>; the report goes into the plugin's profiles folder.")
    }

    /** `/nf pack` says what's built and where it's served; `/nf pack send [player]` sends it again (even to a client that has it). */
    private fun pack(sender: CommandSender, args: List<String>) {
        val packs = runtime.packs
        val built = packs.built
        if (args.firstOrNull()?.lowercase(Locale.ROOT) == "send") {
            val target = args.getOrNull(1)?.let { requireNotNull(runtime.platform.players.find(it)) { "no player \"$it\" online" } }
                ?: sender.player
                ?: throw IllegalArgumentException("say who to send it to: /nf pack send <player>")
            packs.forget(target.uuid)
            sender.reply(
                if (packs.send(
                        target.uuid
                    )
                ) {
                    "<green>Sent the resource pack to ${target.name}."
                } else {
                    "<red>There's no resource pack to send."
                }
            )
            return
        }
        if (built == null) {
            sender.reply("<gray>No resource pack: the project has no resource packs, or they couldn't be built.")
            return
        }
        sender.reply("<gold>Resource pack <white>${built.sha1} <gray>(${built.bytes.size} bytes)")
        sender.reply("<gray>${built.url ?: "not delivered (resource-pack.enabled is off)"}")
    }

    /**
     * `/nf settings`: the packages' server-owner settings. A player gets the
     * dialog to change them; `list` shows them, `set <setting> <value>` and
     * `reset <setting>` change one (a setting is `name` for the project's,
     * `namespace:name` for a package's), and `reload` reads the settings
     * files again after they were edited by hand.
     */
    private fun settings(sender: CommandSender, args: List<String>, text: String) {
        val settings = runtime.session.settings
        check(runtime.session.running) { "the project isn't running" }
        when (args.firstOrNull()?.lowercase(Locale.ROOT)) {
            null -> {
                val player = sender.player ?: return listSettings(sender)
                require(settings.namespaces().isNotEmpty()) { "no package here has settings" }
                val dialog = SettingsDialog(settings, runtime.platform.text::escape) { lines -> lines.forEach(sender::reply) }
                check(runtime.session.dialogs.showRuntime(player, dialog.build(settings.namespaces()))) {
                    "couldn't show the settings dialog"
                }
            }
            "list" -> listSettings(sender, args.getOrNull(1))
            "set" -> {
                val (namespace, name) = settingName(args.getOrNull(1) ?: return usage(sender, "settings set <setting> <value>"))
                // The value is the rest of the line as typed, spaces and all.
                val value = Regex("^\\s*\\S+\\s+\\S+\\s+\\S+\\s").find(text)?.let { text.substring(it.range.last + 1) }
                if (value == null || args.size < 3) return usage(sender, "settings set ${args[1]} <value>")
                val changed = settings.setText(namespace, name, value)
                val shown = settings.definition(namespace, name).show(settings.values(namespace).first { it.first == name }.third)
                sender.reply(
                    if (changed) "<green>${args[1]} is now ${escape(shown)}." else "<gray>${args[1]} was already ${escape(shown)}."
                )
            }
            "reset" -> {
                val setting = args.getOrNull(1) ?: return usage(sender, "settings reset <setting>")
                val (namespace, name) = settingName(setting)
                settings.set(namespace, name, null)
                val shown = settings.definition(namespace, name).show(settings.definition(namespace, name).defaultValue)
                sender.reply("<green>$setting is back to its default, ${escape(shown)}.")
            }
            "reload" -> {
                val changed = settings.reload()
                sender.reply("<green>Read the settings files again: ${if (changed == 1) "1 setting" else "$changed settings"} changed.")
            }
            else -> usage(sender, "settings [list|set <setting> <value>|reset <setting>|reload]")
        }
    }

    private fun escape(text: String) = runtime.platform.text.escape(text)

    /** A setting as `/nf settings` takes it: `name` for the project's own, `namespace:name` for a package's. */
    private fun settingName(text: String): Pair<String, String> {
        val colon = text.indexOf(':')
        return if (colon < 0) runtime.session.namespace to text else text.substring(0, colon) to text.substring(colon + 1)
    }

    /** Every package's settings (or [namespace]'s): each value, whether it's the default, and what it's for. */
    private fun listSettings(sender: CommandSender, namespace: String? = null) {
        val settings = runtime.session.settings
        val namespaces = namespace?.let(::listOf) ?: settings.namespaces()
        if (namespaces.isEmpty()) {
            sender.reply("<gray>No package here has settings.")
            return
        }
        for (ns in namespaces) {
            sender.reply("<gold>${escape(settings.packageName(ns))} <gray>($ns, settings/$ns.json)")
            for ((name, definition, value) in settings.values(ns)) {
                val set = settings.isSet(ns, name)
                val shown = escape(definition.show(value))
                val default = if (set) " <gray>(default ${escape(definition.show(definition.defaultValue))})" else " <gray>(default)"
                sender.reply("<yellow>${settingLabel(ns, name)} <white>$shown$default <dark_gray>${escape(definition.description)}")
            }
        }
    }

    private fun settingLabel(namespace: String, name: String) = if (namespace == runtime.session.namespace) name else "$namespace:$name"

    /** Every setting as `/nf settings set` takes it. */
    private fun settingLabels(): List<String> {
        val settings = runtime.session.settings
        return settings.namespaces().flatMap { ns -> settings.values(ns).map { settingLabel(ns, it.first) } }
    }

    /** What [label]'s value could be, where there's a short list: a choice's choices, true and false. */
    private fun settingValues(label: String): List<String> {
        val (namespace, name) = settingName(label)
        val definition = runCatching { runtime.session.settings.definition(namespace, name) }.getOrNull() ?: return emptyList()
        return when (definition) {
            is ChoiceSetting -> definition.choices
            is BooleanSetting -> listOf("true", "false")
            else -> emptyList()
        }
    }

    override fun suggest(sender: CommandSender, request: SuggestionRequest): Completion =
        Completion(request.partial.lastIndexOf(' ') + 1, options(request.partial.trimStart().split(' ')))

    /** Suggestions for the last of [args], the words typed so far with the one being typed last. */
    private fun options(args: List<String>): List<String> {
        val prefix = args.lastOrNull().orEmpty()
        val options = if (args.size >= 2 && args[0].equals("data", ignoreCase = true)) {
            data.options(args.drop(1))
        } else {
            when (args.size) {
                0, 1 -> subcommands
                2 -> when (args[0].lowercase(Locale.ROOT)) {
                    "spawn", "find" -> runtime.session.centities.definitionIds()
                    "kill" -> listOf("all") + runtime.session.centities.definitionIds() +
                        runtime.session.centities.all().map { short(it.id) }
                    "tp" -> runtime.session.centities.all().map { short(it.id) }
                    "reload" -> listOf(ProjectManifest.FILE_NAME) +
                        runtime.session.snapshot.let { snapshot ->
                            Kinds.all.flatMap { kind -> snapshot[kind].keys.map { snapshot.placeOf(kind, it).location } }
                        }.orEmpty()
                    "pack" -> listOf("send")
                    "scripts" -> listOf("all")
                    "profile" -> listOf("10", "30", "60")
                    "settings" -> listOf("list", "set", "reset", "reload")
                    else -> emptyList()
                }
                3 -> when {
                    args[0].equals("spawn", true) -> runtime.platform.players.online().map { it.name }
                    args[0].equals("settings", true) && args[1].lowercase(Locale.ROOT) in setOf("set", "reset") -> settingLabels()
                    args[0].equals("settings", true) && args[1].equals("list", true) -> runtime.session.settings.namespaces()
                    else -> emptyList()
                }
                4 -> if (args[0].equals("settings", true) && args[1].equals("set", true)) settingValues(args[2]) else emptyList()
                else -> emptyList()
            }
        }
        return options.filter { it.startsWith(prefix, ignoreCase = true) }
    }

    private fun byPrefix(prefix: String): Instance {
        val matches = runtime.session.centities.all().filter { it.id.toString().startsWith(prefix.lowercase(Locale.ROOT)) }
        require(matches.isNotEmpty()) { "no instance or centity \"$prefix\"" }
        require(matches.size == 1) { "\"$prefix\" matches ${matches.size} instances; give more of the id" }
        return matches.single()
    }

    private fun short(id: UUID) = id.toString().take(8)

    private fun where(location: Location) = "${location.world} %.1f %.1f %.1f".format(location.x, location.y, location.z)

    private fun distance(from: Location?, to: Location): Double {
        if (from == null || from.world != to.world) return Double.MAX_VALUE
        val dx = from.x - to.x
        val dy = from.y - to.y
        val dz = from.z - to.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    companion object {
        /** `/nf`'s one argument: everything after it, which it reads and completes itself. */
        const val ARGUMENTS = "arguments"

        private val NEXT_RUN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm zzz", Locale.ENGLISH)
        private const val MAX_PROBLEMS = 5
        private const val MAX_SCRIPTS = 15
        private const val BYTES_PER_MB = 1024L * 1024
    }
}
