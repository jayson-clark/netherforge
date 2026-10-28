package dev.netherforge.plugin.interop

import dev.netherforge.format.Problem
import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.project.PackagePaths
import dev.netherforge.format.project.ProjectManifest
import dev.netherforge.format.project.Requirement
import dev.netherforge.plugin.RuntimeLog
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.platform.Platform
import dev.netherforge.plugin.session.RuntimeService
import dev.netherforge.plugin.session.SessionProject
import dev.netherforge.plugin.session.TickPhase

/**
 * The other plugins the project's packages declared (`requires.plugins`) and
 * whether the server has them: a load problem for each that it doesn't, and the
 * check a function that needs one makes before it calls ([require]).
 *
 * **Presence isn't decided once.** The server enables plugins in an order
 * NetherForge only partly influences (`paper-plugin.yml` loads Vault and
 * PlaceholderAPI before it when they're there, but an economy plugin registers
 * with Vault whenever it likes), so [require] asks the platform at each call,
 * and the problems are worked out again whenever the adapter says a plugin or
 * a service changed ([pluginsChanged], coalesced to the next tick, since the
 * state in the middle of an enable or disable event isn't final) and at the
 * first tick after the session starts. A plugin that enables late clears its
 * problem; one that goes brings it back.
 */
internal class Plugins(
    private val platform: Platform,
    private val log: RuntimeLog,
    /** Every problem this service reports changed. */
    private val problemsChanged: () -> Unit,
    /** The project's own namespace: its manifest is `netherforge.json`, a package's `<namespace>:netherforge.json`. */
    private val home: () -> String
) : RuntimeService {
    override val name get() = "plugins"

    /** Each declared plugin with the packages that declare it, in the order requirements are shown. */
    private var declared: List<Pair<String, List<String>>> = emptyList()

    /** The declared plugins the server doesn't have, as of the last look. */
    private var missing: Set<String> = emptySet()

    /** Something changed since the last look. */
    private var stale = false

    override fun define(project: SessionProject) {
        declared = Requirement.combined(project.snapshot).mapNotNull { use ->
            (Requirement.parse(use.requirement) as? Requirement.Plugin)?.let { it.name to use.packages }
        }
        missing = emptySet()
    }

    override fun start() {
        missing = lookMissing()
        for ((plugin, packages) in declared) {
            if (plugin in
                missing
            ) {
                log.warn("${packages.joinToString()} need the plugin \"$plugin\", which isn't enabled on this server (yet)")
            }
        }
        // Plugins enabled after this one are looked at again on the first tick.
        stale = true
    }

    override fun pluginsChanged() {
        stale = true
    }

    override fun tick(phase: TickPhase) {
        if (phase != TickPhase.UPKEEP || !stale) return
        stale = false
        val now = lookMissing()
        if (now == missing) return
        for (plugin in now - missing) log.warn("The plugin \"$plugin\", which packages declare they need, is no longer enabled")
        for (plugin in missing - now) log.info("The plugin \"$plugin\" is enabled now")
        missing = now
        problemsChanged()
    }

    private fun lookMissing(): Set<String> = declared.map { it.first }.filterNot(platform.plugins::isEnabled).toSet()

    override fun problems(): List<Problem> = declared.filter { it.first in missing }.flatMap { (plugin, packages) ->
        packages.map { namespace ->
            val file = if (namespace == home()) ProjectManifest.FILE_NAME else PackagePaths.of(namespace, ProjectManifest.FILE_NAME)
            ProblemCodes.RUNTIME_PLUGIN_MISSING.at(
                file,
                "The plugin \"$plugin\" isn't enabled on this server: calling what needs it is an error until it is",
                "$.requires.plugins"
            )
        }
    }

    /**
     * Refuses [call] (`nf.economy.balance`) unless the plugin [plugin] (`vault`)
     * is enabled now. The caller has already been checked to have declared it
     * (`Requirements.check`, from the generated binding).
     */
    fun require(plugin: String, call: String) {
        if (platform.plugins.isEnabled(plugin)) return
        throw LuaApiException("$call needs the plugin \"$plugin\", which isn't enabled on this server")
    }
}
