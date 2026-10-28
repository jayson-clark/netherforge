package dev.netherforge.plugin.testkit

import dev.netherforge.plugin.platform.EconomyOps
import dev.netherforge.plugin.platform.PlaceholderOps
import dev.netherforge.plugin.platform.PlaceholderResolver
import dev.netherforge.plugin.platform.PluginOps
import java.util.UUID

/*
 * The fake server's other plugins (`platform/Interop.kt`): which are enabled,
 * a Vault economy that registers when a test says, and PlaceholderAPI's
 * expansions. A test enables things after the runtime has started to see what a
 * late-enabling plugin does; each change tells the runtime as the adapter does.
 */

/** Which plugins are enabled. None are, until a test enables them. */
class FakePlugins(private val platform: FakePlatform) : PluginOps {
    private val enabled = LinkedHashSet<String>()

    override fun isEnabled(name: String) = name.lowercase() in enabled

    /** Enables [name], and tells the runtime as the server's plugin event would. */
    fun enable(name: String) {
        enabled += name.lowercase()
        platform.events?.pluginsChanged()
    }

    /** Disables [name], and tells the runtime. */
    fun disable(name: String) {
        enabled -= name.lowercase()
        platform.events?.pluginsChanged()
    }
}

/** A Vault economy: accounts by player, and what it refuses. */
class FakeEconomy(private val platform: FakePlatform) : EconomyOps {
    val accounts = HashMap<UUID, Double>()

    /** Whether an economy plugin has registered with Vault: [Platform.economy] is null until one has. */
    var registered = false
        private set

    /** Registers (or unregisters) the economy with Vault, telling the runtime as the server's service event would. */
    fun register(registered: Boolean = true) {
        this.registered = registered
        platform.events?.pluginsChanged()
    }

    override fun balance(player: UUID) = accounts[player] ?: 0.0

    override fun deposit(player: UUID, amount: Double): Double? {
        val account = accounts[player] ?: return null
        return (account + amount).also { accounts[player] = it }
    }

    override fun withdraw(player: UUID, amount: Double): Double? {
        val account = accounts[player] ?: return null
        if (account < amount) return null
        return (account - amount).also { accounts[player] = it }
    }

    /** The economy [Platform.economy] gives: Vault must be enabled and an economy registered. */
    internal fun lookup(): EconomyOps? = this.takeIf { registered && platform.plugins.isEnabled("vault") }
}

/** PlaceholderAPI's expansions, and the placeholders other plugins offer. */
class FakePlaceholders : PlaceholderOps {
    private val expansions = LinkedHashMap<String, PlaceholderResolver>()

    /** Placeholders another plugin offers, by their text without the `%`s (`player_name`): they don't need the runtime. */
    val others = HashMap<String, String>()

    /** Namespaces another plugin already offers: [register] refuses them. */
    val taken = HashSet<String>()

    override fun parse(player: UUID?, text: String): String = PLACEHOLDER.replace(text) { match ->
        val placeholder = match.groupValues[1]
        others[placeholder] ?: answer(placeholder, player) ?: match.value
    }

    /** What the placeholder `%[placeholder]%` gives, asked on the calling thread as PlaceholderAPI asks; null for none. */
    fun request(placeholder: String, player: UUID? = null): String? = answer(placeholder, player)

    private fun answer(placeholder: String, player: UUID?): String? {
        val namespace = placeholder.substringBefore('_')
        val key = placeholder.substringAfter('_', "")
        return expansions[namespace]?.resolve(player, key)
    }

    override fun register(namespace: String, resolver: PlaceholderResolver): Boolean {
        if (namespace in taken || namespace in expansions) return false
        expansions[namespace] = resolver
        return true
    }

    override fun isRegistered(namespace: String) = namespace in expansions

    override fun unregister(namespace: String) {
        expansions.remove(namespace)
    }

    /** The namespaces offered through this. */
    val registered: Set<String> get() = expansions.keys

    /** PlaceholderAPI forgetting every expansion, as when it's reloaded. */
    fun forgetAll() = expansions.clear()

    private companion object {
        val PLACEHOLDER = Regex("%([^%\\s]+)%")
    }
}
