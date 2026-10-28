package dev.netherforge.plugin.paper

import dev.netherforge.plugin.platform.EconomyOps
import dev.netherforge.plugin.platform.PlaceholderOps
import dev.netherforge.plugin.platform.PlaceholderResolver
import dev.netherforge.plugin.platform.PluginOps
import me.clip.placeholderapi.PlaceholderAPI
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

/*
 * Other plugins, behind the runtime's own types (`platform/Interop.kt`): this
 * file is the only place Vault's and PlaceholderAPI's classes are named. Both are
 * soft dependencies (`paper-plugin.yml`): they may be absent, so nothing here
 * that names one runs unless the plugin is enabled (the runtime asks
 * [PaperPlugins] first, and [PaperEconomy.lookup] does itself), which keeps the
 * JVM from ever loading a class of a plugin that isn't there.
 */

/** Plugins by name, as `plugin.yml` calls them; the server compares names without regard to case. */
class PaperPlugins : PluginOps {
    override fun isEnabled(name: String): Boolean = Bukkit.getPluginManager().getPlugin(name)?.isEnabled == true
}

/** Vault's economy: whichever provider is registered with the services manager, found at each call and never kept. */
internal object PaperEconomy {
    fun lookup(plugins: PluginOps): EconomyOps? {
        if (!plugins.isEnabled("vault")) return null
        val provider = Bukkit.getServicesManager().getRegistration(Economy::class.java)?.provider ?: return null
        return VaultEconomy(provider)
    }
}

private class VaultEconomy(private val economy: Economy) : EconomyOps {
    private fun offline(player: UUID): OfflinePlayer = Bukkit.getOfflinePlayer(player)

    override fun balance(player: UUID): Double = economy.getBalance(offline(player))

    override fun deposit(player: UUID, amount: Double): Double? {
        val who = offline(player)
        // The provider's own answer to "did it work"; its response's balance isn't every provider's to fill in, so ask for the balance.
        return if (economy.depositPlayer(who, amount).transactionSuccess()) economy.getBalance(who) else null
    }

    override fun withdraw(player: UUID, amount: Double): Double? {
        val who = offline(player)
        return if (economy.withdrawPlayer(who, amount).transactionSuccess()) economy.getBalance(who) else null
    }
}

/**
 * PlaceholderAPI: one [PlaceholderExpansion] per namespace the runtime registers, kept here
 * so [unregister] and [isRegistered] mean ours. PlaceholderAPI calls an expansion from any
 * thread; the runtime's resolver is built for that.
 */
class PaperPlaceholders(private val plugin: JavaPlugin) : PlaceholderOps {
    private val ours = HashMap<String, ScriptExpansion>()

    override fun parse(player: UUID?, text: String): String =
        PlaceholderAPI.setPlaceholders(player?.let { Bukkit.getOfflinePlayer(it) }, text)

    override fun register(namespace: String, resolver: PlaceholderResolver): Boolean {
        // Taken by another plugin (or still ours: the runtime asks only for what it doesn't have).
        if (PlaceholderAPI.isRegistered(namespace)) return false
        val expansion = ScriptExpansion(namespace, plugin.pluginMeta.version, resolver)
        if (!expansion.register()) return false
        ours[namespace] = expansion
        return true
    }

    override fun isRegistered(namespace: String): Boolean = namespace in ours && PlaceholderAPI.isRegistered(namespace)

    override fun unregister(namespace: String) {
        val expansion = ours.remove(namespace) ?: return
        if (PlaceholderAPI.isRegistered(namespace)) expansion.unregister()
    }
}

/** `%[namespace]_<params>%`, answered by the runtime. */
private class ScriptExpansion(private val namespace: String, private val release: String, private val resolver: PlaceholderResolver) :
    PlaceholderExpansion() {
    override fun getIdentifier(): String = namespace

    override fun getAuthor(): String = "NetherForge"

    override fun getVersion(): String = release

    // Not a file in PlaceholderAPI's expansions folder: it must survive `/papi reload`.
    override fun persist(): Boolean = true

    override fun onRequest(player: OfflinePlayer?, params: String): String? = resolver.resolve(player?.uniqueId, params)
}
