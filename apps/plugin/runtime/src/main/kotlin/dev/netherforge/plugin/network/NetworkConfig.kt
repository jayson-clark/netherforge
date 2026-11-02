package dev.netherforge.plugin.network

import dev.netherforge.network.NetworkNames
import dev.netherforge.network.Protocol
import dev.netherforge.network.Registration

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
    /**
     * The secret this server proves: its [credential]'s (`network.credential.secret`), or the hub's shared one
     * (`network.secret`, from its `hub.properties`) when it has no credential.
     */
    val secret: String? = null,
    /** This server's credential's id (`network.credential.id`, from `/nfhub credentials add`); null: the shared secret. */
    val credential: String? = null,
    /** The group this server is an instance of (`network.group`): null for a group of one, its name. */
    val group: String? = null,
    /** How many players it takes (`network.capacity`): null for any number. */
    val capacity: Int? = null,
    /**
     * Where the proxy reaches this server when it adds itself to the proxy's servers (`network.register.address`):
     * null when it's one of the proxy's own (`velocity.toml`). With [Registration.host] null, the address the hub sees.
     */
    val register: Registration? = null,
    /**
     * How long the server waits as it starts for the hub to welcome it (its grants, the network's policy for it) before
     * it starts its project anyway (`network.wait-for-hub-seconds`); 0: it doesn't wait.
     */
    val waitForHubSeconds: Int = 0,
    /**
     * Whether the project runs only once the hub has welcomed it (`network.require-hub`): a server that can't reach its
     * hub runs nothing rather than run without the network's policy. It starts by itself when the hub is there.
     */
    val requireHub: Boolean = false,
    /** How long this server may be empty before the hub hears it's idle (its webhook, `server_idle`); 0: never. */
    val idleAfterSeconds: Int = 0,
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
        credential != null && !NetworkNames.isCredential(credential) -> "network.credential.id \"$credential\" isn't a credential id"
        credential != null && secret == null -> "network.credential.secret is empty: it's what /nfhub credentials add printed"
        secret == null ->
            "network.secret is empty: copy it from the proxy's plugins/netherforge/hub.properties, or give the server a credential"
        port !in 1..65535 -> "network.hub's port $port isn't a port"
        group != null && !NetworkNames.isServer(group) -> "network.group \"$group\" isn't a group's name (letters, digits, _ . -)"
        serverName == Protocol.AUTO_NAME && register == null ->
            "network.server-name is auto, which only a server that registers itself can be (network.register.address)"
        serverName == Protocol.AUTO_NAME && group == null -> "network.server-name is auto, which needs network.group to number it from"
        register != null && register.port !in 1..65535 -> "network.register.address's port ${register.port} isn't a port"
        else -> null
    }

    companion object {
        /** `host:port`, or a port alone (the host the hub sees): what `network.register.address` says. */
        fun registration(text: String?): Registration? {
            val value = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            value.toIntOrNull()?.let { return Registration(null, it) }
            // host:port, or [v6]:port; anything else has no port, which problem() says.
            val colon = value.lastIndexOf(':')
            val bracketed = value.startsWith("[")
            val hasPort = colon > 0 && (if (bracketed) value[colon - 1] == ']' else value.count { it == ':' } == 1)
            if (!hasPort) return Registration(value, -1)
            val host = value.substring(0, colon).removePrefix("[").removeSuffix("]")
            return Registration(host, value.substring(colon + 1).toIntOrNull() ?: -1)
        }

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
