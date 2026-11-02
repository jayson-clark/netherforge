package dev.netherforge.proxy

import dev.netherforge.network.Auth
import dev.netherforge.network.Balance
import dev.netherforge.network.Grants
import dev.netherforge.network.GroupSettings
import dev.netherforge.network.NetworkLimits
import dev.netherforge.network.NetworkNames
import dev.netherforge.network.Protocol
import dev.netherforge.network.Share
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * The hub's settings, `plugins/netherforge/hub.properties` on the proxy: where it listens, the shared secret a
 * trusted server may join with, what the network shares with less trusted servers (`share.<role>`), and the
 * network's limits. Written with a new random secret the first time the proxy starts, so a hub is never open without
 * one; an owner who wants every server to have its own credential empties it. A value that isn't one (a negative
 * number) is the default.
 */
data class HubSettings(
    val bind: String,
    val port: Int,
    /** The shared secret, which joins a server as `trusted`; null when the owner turned it off. */
    val secret: String?,
    val limits: NetworkLimits,
    /** What's shared with every credential of a role (`share.standard`, `share.isolated`). */
    val roleShares: Map<String, List<Share>> = emptyMap(),
    /** Each group's settings (`group.<name>.balance`), by the group's name. */
    val groups: Map<String, GroupSettings> = emptyMap(),
    /** Where the admin API listens (`admin.bind`, `admin.port`): off without a port. Its clients are in `admin.properties`. */
    val adminBind: String = "127.0.0.1",
    val adminPort: Int? = null,
    /** Where the hub POSTs its events (`webhook.url`), signed with `webhook.secret`; none without a URL. */
    val webhookUrl: java.net.URI? = null,
    val webhookSecret: String? = null,
    /**
     * Whether a server's resource packs come off a player as they move to another server, so one server's (a lobby's
     * hidden HUD, its replaced sprites) never follows them to the next. Default true; a file written before this
     * setting existed has it on too.
     */
    val clearResourcePacks: Boolean = true
) {
    companion object {
        const val FILE = "hub.properties"

        fun read(file: Path, info: (String) -> Unit): HubSettings {
            if (!Files.exists(file)) {
                Files.createDirectories(file.parent)
                Files.writeString(file, template(Auth.newSecret()))
                info(
                    "NetherForge: wrote $file with a new secret; give it to each server as network.secret in its plugins/NetherForge/config.yml"
                )
            }
            val properties = Properties()
            Files.newBufferedReader(file).use(properties::load)
            fun text(key: String) = properties.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
            fun positive(key: String) = text(key)?.toLongOrNull()?.takeIf { it > 0 }
            fun flag(key: String) = text(key)?.lowercase()?.let { it == "true" }
            val defaults = NetworkLimits()
            val secret = text("secret")
            require(secret == null || secret.length >= MIN_SECRET) { "$file's secret is shorter than $MIN_SECRET characters" }
            val roleShares = Grants.ROLES.filter { it != Grants.TRUSTED_ROLE }.associateWith { role ->
                try {
                    Share.parseList(text("share.$role"))
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("$file's share.$role: ${e.message}")
                }
            }
            val groups = properties.stringPropertyNames().filter { it.startsWith("group.") }
                .map { it.removePrefix("group.").substringBeforeLast('.') }.toSortedSet()
                .associateWith { group ->
                    val balance = text("group.$group.balance")?.let { word ->
                        Balance.of(word) ?: throw IllegalArgumentException("$file's group.$group.balance \"$word\" isn't least or fill")
                    }
                    val fallback = text("group.$group.fallback")?.also {
                        require(NetworkNames.isServer(it)) { "$file's group.$group.fallback \"$it\" isn't a group's or server's name" }
                    }
                    GroupSettings(
                        balance = balance ?: Balance.LEAST,
                        fallback = fallback ?: GroupSettings.DEFAULT_FALLBACK,
                        maxWaiting = positive("group.$group.max-waiting")?.toInt() ?: GroupSettings.DEFAULT_MAX_WAITING
                    )
                }
            return HubSettings(
                bind = text("bind") ?: "0.0.0.0",
                port = positive("port")?.toInt() ?: Protocol.DEFAULT_PORT,
                secret = secret,
                roleShares = roleShares,
                groups = groups,
                limits = NetworkLimits(
                    maxValueBytes = positive("max-value-bytes")?.toInt() ?: defaults.maxValueBytes,
                    maxKeysPerPackage = positive("max-keys-per-package")?.toInt() ?: defaults.maxKeysPerPackage,
                    maxBytesPerPackage = positive("max-bytes-per-package") ?: defaults.maxBytesPerPackage,
                    maxQueuedFrames = positive("max-queued-frames")?.toInt() ?: defaults.maxQueuedFrames,
                    requestTimeoutMillis = positive("request-timeout-seconds")?.let { it * 1000 } ?: defaults.requestTimeoutMillis,
                    unregisterAfterMillis = positive("unregister-after-seconds")?.let { it * 1000 } ?: defaults.unregisterAfterMillis,
                    placementTimeoutMillis = positive("placement-timeout-millis") ?: defaults.placementTimeoutMillis
                ),
                webhookUrl = text("webhook.url")?.let { url ->
                    val uri = try {
                        java.net.URI(url)
                    } catch (e: Exception) {
                        throw IllegalArgumentException("$file's webhook.url \"$url\" isn't a URL")
                    }
                    require(uri.scheme == "http" || uri.scheme == "https") { "$file's webhook.url \"$url\" isn't http(s)" }
                    uri
                },
                webhookSecret = text("webhook.secret"),
                adminBind = text("admin.bind") ?: "127.0.0.1",
                adminPort = positive("admin.port")?.toInt(),
                clearResourcePacks = flag("clear-resource-packs") ?: true
            )
        }

        private const val MIN_SECRET = 16

        private fun template(secret: String): String {
            val limits = NetworkLimits()
            return """
                |# NetherForge's network hub. Every NetherForge server of the network connects here: in each server's
                |# plugins/NetherForge/config.yml, set network.enabled to true, network.hub to this proxy's address and
                |# the port below, network.server-name to the server's name in velocity.toml's [servers], and
                |# network.secret to the secret below. Keep the port off the internet: firewall it to your servers.
                |
                |# Where the hub listens.
                |bind=0.0.0.0
                |port=${Protocol.DEFAULT_PORT}
                |
                |# What a server proves it knows (never sent as it is) to join as a trusted server, which may do anything.
                |# Anyone with it can join the network. Servers can have credentials of their own instead, each with what
                |# it may do (/nfhub credentials add, credentials.properties); empty this to allow only those.
                |secret=$secret
                |
                |# What the network shares with servers whose credential has a less trusted role, besides their own
                |# namespaces: channels and maps by full name (or namespace:* for all of one), each with what they may do
                |# (read, write, request, answer; joined with +). For example:
                |#   share.standard=lobby:return(request), events:announcements(read)
                |share.standard=
                |share.isolated=
                |
                |# Groups: servers that are copies of one game (network.group in their config.yml), which scripts send
                |# players to as one ({ group = "arena" }). How the hub picks the instance: least (the one with the fewest
                |# players that has room, the default) or fill (the fullest with room, so players end up together).
                |#   group.arena.balance=fill
                |# Where an instance's players go when it stops (a group or server; lobby unless you say), and how many
                |# sends may wait at once for an instance with room (a send that asks to wait, while one starts).
                |#   group.arena.fallback=lobby
                |#   group.arena.max-waiting=500
                |
                |# A URL the hub POSTs events to, for whatever starts and stops your servers: server_ready, server_idle,
                |# server_gone and group_wanted (players waiting for a group with no room: start an instance). Each is
                |# JSON, signed with webhook.secret in X-NetherForge-Signature (sha256=<hex HMAC of the body>).
                |webhook.url=
                |webhook.secret=
                |
                |# The most one value may be as JSON: a message, a request or its reply, a map's value, a sent player's data.
                |max-value-bytes=${limits.maxValueBytes}
                |# What one package may keep in the network's shared maps, across all of them.
                |max-keys-per-package=${limits.maxKeysPerPackage}
                |max-bytes-per-package=${limits.maxBytesPerPackage}
                |# How long a request waits for the server asked before it fails.
                |request-timeout-seconds=${limits.requestTimeoutMillis / 1000}
                |# Frames waiting to be sent to one server; past it the server is too slow and is disconnected.
                |max-queued-frames=${limits.maxQueuedFrames}
                |# How long a server that added itself to the proxy (network.register in its config.yml) may be gone
                |# without shutting down cleanly before it comes off the proxy's servers.
                |unregister-after-seconds=${limits.unregisterAfterMillis / 1000}
                |
                |# The admin API: HTTP for services outside the servers (an orchestrator, a matchmaker, a moderation tool)
                |# to see the network and act on it: add and remove servers, follow players, send and message them,
                |# publish on channels, place players. Off without a port. Its clients are in admin.properties, each with
                |# its token and scopes (/nfhub admins add). Keep it on localhost or your servers' network.
                |admin.bind=127.0.0.1
                |admin.port=
                |# How long the hub waits for a client placing a group's players to answer, before the group's balancing.
                |placement-timeout-millis=2000
                |
                |# Whether the resource packs a server sent a player come off as they move to another server, so a
                |# lobby's (its hidden HUD, its replaced sprites) never follows them into a game. The next server sends
                |# its own as they join (NetherForge servers do). Off, Velocity leaves them on until a server replaces them.
                |clear-resource-packs=true
                |
            """.trimMargin()
        }
    }
}
