package dev.netherforge.plugin.platform

import java.util.UUID

/**
 * Other plugins on the server, for what a package declares with
 * `requires: { "plugins": [...] }` (`nf.economy`, `nf.placeholders`). The
 * runtime owns the types here; only the adapter knows the plugin's own API
 * (Vault, PlaceholderAPI), and only behind these interfaces. There is no
 * general way to call another plugin.
 */
interface PluginOps {
    /**
     * Whether a plugin called [name] is on the server and enabled. [name] is as
     * a package declares it (`vault`), compared without regard to case.
     * Answers for now: a plugin that enables later (the server enables plugins
     * in its own order) is seen as soon as it has.
     */
    fun isEnabled(name: String): Boolean
}

/**
 * The server's economy (Vault's), in plain numbers. Players are offline or
 * online alike. Looked up at each call ([Platform.economy]), never kept.
 */
interface EconomyOps {
    /** What [player] has: 0 for someone the economy has no account for. */
    fun balance(player: UUID): Double

    /** Gives [player] [amount] (above 0). Their new balance, or null when the economy refused. */
    fun deposit(player: UUID, amount: Double): Double?

    /** Takes [amount] (above 0) from [player]. Their new balance, or null when the economy refused (not enough, say). */
    fun withdraw(player: UUID, amount: Double): Double?
}

/** Answers `%<namespace>_<key>%`: [key] is what follows the namespace and its `_`; null leaves the placeholder as written. */
fun interface PlaceholderResolver {
    /**
     * May be called from any thread, as the plugin asking decides (async chat
     * and scoreboard plugins ask off the main thread): [player] is who it's
     * for, or null for none.
     */
    fun resolve(player: UUID?, key: String): String?
}

/** PlaceholderAPI: filling placeholders in, and offering our own. Only used while the plugin is enabled ([PluginOps.isEnabled]). */
interface PlaceholderOps {
    /** [text] with every placeholder filled in for [player] (none: only those that need nobody). Main thread. */
    fun parse(player: UUID?, text: String): String

    /**
     * Offers `%[namespace]_<key>%`, answered by [resolver], until [unregister]
     * or the plugin goes. False when something else already offers that namespace.
     */
    fun register(namespace: String, resolver: PlaceholderResolver): Boolean

    /** Whether [namespace] is offered now by what [register] gave it (PlaceholderAPI forgets them when it's reloaded or disabled). */
    fun isRegistered(namespace: String): Boolean

    /** Stops offering [namespace], if this registered it. */
    fun unregister(namespace: String)
}
