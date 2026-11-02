package dev.netherforge.proxy

import dev.netherforge.network.Prometheus
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The proxy's own numbers, served at `/metrics` whether or not it runs the hub: who's connected, logins and the ones
 * refused or failed (by reason), moves between servers and the failed ones (by reason), its link to a hub that runs
 * on its own, and the servers it knows. Every sample is labelled with the proxy's name (`remote.name`), so a network
 * of several proxies tells them apart. Counted from the proxy's event threads; read by the metrics thread.
 */
class ProxyMetrics(private val proxy: String) {
    /** Why a login didn't make it onto a server. */
    enum class LoginRefusal(val label: String) {
        /** A network ban. */
        BANNED("banned"),

        /** The hub drains this proxy. */
        DRAINING("draining"),

        /** The hub (or the service placing logins) turned them away: already online elsewhere, a placement's fail. */
        HUB("hub"),

        /** Another plugin, or Velocity itself, denied the login. */
        DENIED("denied"),

        /** It was let in, but the first server it was sent to didn't take it. */
        INITIAL_SERVER("initial_server")
    }

    private val logins = AtomicLong()
    private val refused = ConcurrentHashMap<LoginRefusal, AtomicLong>()
    private val moves = AtomicLong()
    private val moveFailures = ConcurrentHashMap<String, AtomicLong>()
    private val reconnects = AtomicLong()

    @Volatile
    private var hubConnected: Boolean? = null

    private var joinedBefore = false

    /** A player is on their first server. */
    fun loggedIn() {
        logins.incrementAndGet()
    }

    fun refused(why: LoginRefusal) {
        refused.computeIfAbsent(why) { AtomicLong() }.incrementAndGet()
    }

    /** A player moved from one server to another. */
    fun moved() {
        moves.incrementAndGet()
    }

    /** A move didn't happen, [reason] a short label (`kicked`, `cancelled`, `in_progress`, `already_connected`, …). */
    fun moveFailed(reason: String) {
        moveFailures.computeIfAbsent(reason) { AtomicLong() }.incrementAndGet()
    }

    /** The link to a hub running on its own came up: after the first time, it's a reconnect. */
    @Synchronized
    fun hubJoined() {
        if (joinedBefore) reconnects.incrementAndGet()
        joinedBefore = true
        hubConnected = true
    }

    fun hubLost() {
        hubConnected = false
    }

    /** The metrics now, with [players] connected and [servers] known (by where they came from: `config` or `runtime`). */
    fun text(players: Int, servers: Map<String, Int>): String {
        val label = mapOf("proxy" to proxy)
        val out = Prometheus()
        out.sample("netherforge_proxy_players", "Players connected through this proxy", players, label)
        for ((source, count) in servers.toSortedMap()) {
            out.sample(
                "netherforge_proxy_servers",
                "Servers this proxy knows, by where they came from",
                count,
                label + ("source" to source)
            )
        }
        out.sample("netherforge_proxy_logins_total", "Logins that reached their first server", logins.get(), label, counter = true)
        for (why in LoginRefusal.entries) {
            out.sample(
                "netherforge_proxy_logins_refused_total",
                "Logins refused, or that failed before reaching a server, by reason",
                refused[why]?.get() ?: 0,
                label + ("reason" to why.label),
                counter = true
            )
        }
        out.sample("netherforge_proxy_moves_total", "Players moved from one server to another", moves.get(), label, counter = true)
        for ((reason, count) in moveFailures.toSortedMap()) {
            out.sample(
                "netherforge_proxy_move_failures_total",
                "Moves between servers that failed, by reason",
                count.get(),
                label + ("reason" to reason),
                counter = true
            )
        }
        hubConnected?.let { connected ->
            out.sample(
                "netherforge_proxy_hub_connected",
                "Whether the link to the network's hub is up (1) or down (0)",
                if (connected) 1 else 0,
                label
            )
            out.sample(
                "netherforge_proxy_hub_reconnects_total",
                "Times the link to the hub came back after it went",
                reconnects.get(),
                label,
                counter = true
            )
        }
        return out.toString()
    }
}
