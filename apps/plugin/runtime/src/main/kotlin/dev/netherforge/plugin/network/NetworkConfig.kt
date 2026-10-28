package dev.netherforge.plugin.network

import dev.netherforge.network.NetworkNames
import dev.netherforge.network.Protocol

/**
 * `network:` in `config.yml`: whether this server is part of a network, how it reaches the network's hub (the proxy
 * plugin), what it's called there, and the owner's limits on what its packages send. Read on dev servers too: a dev
 * server that isn't put in a network is a network of its own ([NetworkLink]).
 */
data class NetworkConfig(
    val enabled: Boolean = false,
    /** The hub's address: the proxy's host, and the port its `hub.properties` says. */
    val host: String = "127.0.0.1",
    val port: Int = Protocol.DEFAULT_PORT,
    /** This server's name as the proxy names it (`velocity.toml`'s `[servers]`). */
    val serverName: String? = null,
    /** The hub's secret, from its `hub.properties`. */
    val secret: String? = null,
    /** What each package may send a minute: messages, requests, map writes and players sent. */
    val messagesPerMinute: Int = 1200,
    /** How long a request waits for its answer, here (the hub has its own, longer, limit). */
    val requestTimeoutSeconds: Int = 10,
    /** What reaches scripts a tick at most (messages, changes, requests, presence); the rest waits for the next. */
    val messagesPerTick: Int = 1000,
    /** What may wait for the main thread before the server stops reading from the hub. */
    val maxQueued: Int = 10_000
) {
    /** Why [enabled] can't be acted on, or null when it can. */
    fun problem(): String? = when {
        !enabled -> null
        serverName == null -> "network.server-name is empty: set it to this server's name in the proxy's config"
        !NetworkNames.isServer(serverName) -> "network.server-name \"$serverName\" isn't a server name (letters, digits, _ . -)"
        secret == null -> "network.secret is empty: copy it from the proxy's plugins/netherforge/hub.properties"
        port !in 1..65535 -> "network.hub's port $port isn't a port"
        else -> null
    }

    companion object {
        /** `host:port`, or a host alone (the default port): what `network.hub` says. */
        fun hub(text: String?): Pair<String, Int>? {
            val value = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val colon = value.lastIndexOf(':')
            // A bare IPv6 address has colons of its own; one with a port is in brackets.
            if (colon < 0 || (value.count { it == ':' } > 1 && !value.startsWith("["))) return value to Protocol.DEFAULT_PORT
            val port = value.substring(colon + 1).toIntOrNull() ?: return null
            return value.substring(0, colon).removePrefix("[").removeSuffix("]") to port
        }
    }
}
