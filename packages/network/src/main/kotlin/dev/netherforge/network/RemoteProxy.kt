package dev.netherforge.network

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The proxies of a hub that runs on its own (`NetherForge-<v>-hub.jar`): each proxy plugin connects to the hub as a
 * client with the `proxy` role, tells it its servers and who's on them, and does what it asks (moving its players,
 * adding servers, messages). The [Hub] sees them together as one [HubProxy]: their servers together, each player's
 * moves, messages and kicks done by the proxy they're on, servers added on every one. With none connected, the network
 * has no servers and moves nobody.
 */
class RemoteProxy : HubProxy {
    /** One proxy: how to reach it, its servers and its own ones, and whether it's taking no new logins. */
    private class Joined(val link: (HubFrame) -> Unit) {
        @Volatile var servers: List<String> = emptyList()

        @Volatile var fixed: Set<String> = emptySet()

        /** The servers the proxy itself last said it has (lowercase), not what the hub expects it to have. */
        @Volatile var reported: Set<String> = emptySet()

        @Volatile var draining = false
    }

    private val proxies = ConcurrentHashMap<String, Joined>()

    /** The proxy a player is on, by their UUID: the hub's to say. */
    @Volatile
    internal var proxyOf: (String) -> String? = { null }

    private val moves = ConcurrentHashMap<Long, Pair<String, CompletableFuture<String?>>>()
    private val ids = AtomicLong(1)

    /** Whether any proxy is connected now. */
    val connected get() = proxies.isNotEmpty()

    /** The proxies connected now, by name. */
    fun names(): List<String> = proxies.keys.sorted()

    fun isDraining(name: String) = proxies[name]?.draining == true

    /** Whether proxy [name] said it has every one of [servers]: the hub's full list of servers added at run time. */
    fun has(name: String, servers: Collection<String>): Boolean {
        val reported = proxies[name]?.reported ?: return false
        return servers.all { it.lowercase() in reported }
    }

    internal fun attach(name: String, link: (HubFrame) -> Unit) {
        proxies.put(name, Joined(link))?.let { gone(name, it) }
    }

    internal fun detach(name: String) {
        proxies.remove(name)?.let { gone(name, it) }
    }

    internal fun servers(name: String, all: List<String>, fixed: List<String>) {
        val proxy = proxies[name] ?: return
        proxy.servers = all
        proxy.reported = all.map { it.lowercase() }.toSet()
        proxy.fixed = fixed.map { it.lowercase() }.toSet()
    }

    internal fun drain(name: String, draining: Boolean): Boolean {
        val proxy = proxies[name] ?: return false
        proxy.draining = draining
        proxy.link(HubFrame.ProxyDrain(draining))
        return true
    }

    internal fun result(id: Long, error: String?) {
        moves.remove(id)?.second?.complete(error)
    }

    /** A proxy went: the moves it was asked for fail. */
    private fun gone(name: String, proxy: Joined) {
        for ((id, move) in moves.entries.filter { it.value.first == name }) {
            moves.remove(id)
            move.second.complete("the proxy $name disconnected from the hub")
        }
    }

    /** The proxy [player] is on, or (when the hub doesn't know) any one. */
    private fun proxyFor(player: String): Pair<String, Joined>? {
        val name = proxyOf(player)
        return name?.let { proxies[it]?.let { proxy -> name to proxy } } ?: proxies.entries.firstOrNull()?.toPair()
    }

    override fun servers(): List<String> = proxies.values.flatMap { it.servers }.distinctBy { it.lowercase() }

    override fun players(): List<PlayerState> = emptyList()

    override fun send(id: String, server: String): CompletionStage<String?> {
        val (name, proxy) = proxyFor(id) ?: return CompletableFuture.completedFuture("no proxy is connected to the hub")
        val move = ids.getAndIncrement()
        val future = CompletableFuture<String?>()
        moves[move] = name to future
        proxy.link(HubFrame.ProxyMove(move, id, server))
        return future.orTimeout(MOVE_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            .exceptionally { "the proxy didn't move them within $MOVE_SECONDS seconds" }
    }

    override fun register(name: String, host: String, port: Int): String? {
        if (proxies.isEmpty()) return "no proxy is connected to the hub"
        fill(name, host, port)
        return null
    }

    /** Sends [name] to every proxy that lacks it; one that has it is left alone, so adding it again changes nothing. */
    override fun fill(name: String, host: String, port: Int) {
        for (proxy in proxies.values) {
            if (proxy.servers.any { it.equals(name, ignoreCase = true) }) continue
            proxy.link(HubFrame.ProxyRegister(name, host, port))
            // Until the proxy says its servers again, the hub knows it has it.
            proxy.servers = proxy.servers + name
        }
    }

    override fun unregister(name: String) {
        for (proxy in proxies.values) {
            proxy.link(HubFrame.ProxyUnregister(name))
            proxy.servers = proxy.servers.filterNot { it.equals(name, ignoreCase = true) }
            proxy.reported = proxy.reported - name.lowercase()
        }
    }

    override fun isFixed(name: String): Boolean = proxies.values.any { name.lowercase() in it.fixed }

    override fun message(id: String, kind: String, text: String, subtitle: String?): String? {
        val (_, proxy) = proxyFor(id) ?: return "no proxy is connected to the hub"
        proxy.link(HubFrame.ProxyMessage(id, kind, text, subtitle))
        return null
    }

    override fun kick(id: String, message: String): String? {
        val (_, proxy) = proxyFor(id) ?: return "no proxy is connected to the hub"
        proxy.link(HubFrame.ProxyKick(id, message))
        return null
    }

    /** Kicks [id] off proxy [name] (a login through another took over their session). */
    internal fun kickFrom(name: String, id: String, message: String) {
        proxies[name]?.link?.invoke(HubFrame.ProxyKick(id, message))
    }

    private companion object {
        const val MOVE_SECONDS = 30L
    }
}
