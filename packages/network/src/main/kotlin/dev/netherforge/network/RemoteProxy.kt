package dev.netherforge.network

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The proxy of a hub that runs on its own (`NetherForge-<v>-hub.jar`): the proxy plugin connects to the hub as a
 * client with the `proxy` role, tells it its servers and who's on them, and does what it asks (moving players, adding
 * servers, messages). The [Hub] sees it as any [HubProxy]; with no proxy connected, it has no servers and moves nobody.
 */
class RemoteProxy : HubProxy {
    @Volatile
    private var servers: List<String> = emptyList()

    @Volatile
    private var fixed: Set<String> = emptySet()

    /** Sends to the connected proxy; null while none is. */
    @Volatile
    internal var link: ((HubFrame) -> Unit)? = null

    private val moves = ConcurrentHashMap<Long, CompletableFuture<String?>>()
    private val ids = AtomicLong(1)

    /** Whether a proxy is connected now. */
    val connected get() = link != null

    internal fun servers(all: List<String>, fixed: List<String>) {
        servers = all
        this.fixed = fixed.map { it.lowercase() }.toSet()
    }

    internal fun result(id: Long, error: String?) {
        moves.remove(id)?.complete(error)
    }

    /** The proxy went: what it was asked fails, and it has no servers until one is back. */
    internal fun gone() {
        link = null
        servers = emptyList()
        for (move in moves.values) move.complete("the proxy disconnected from the hub")
        moves.clear()
    }

    override fun servers(): List<String> = servers

    override fun players(): List<PlayerState> = emptyList()

    override fun send(id: String, server: String): CompletionStage<String?> {
        val link = link ?: return CompletableFuture.completedFuture("no proxy is connected to the hub")
        val move = ids.getAndIncrement()
        val future = CompletableFuture<String?>()
        moves[move] = future
        link(HubFrame.ProxyMove(move, id, server))
        return future.orTimeout(MOVE_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            .exceptionally { "the proxy didn't move them within $MOVE_SECONDS seconds" }
    }

    override fun register(name: String, host: String, port: Int): String? {
        val link = link ?: return "no proxy is connected to the hub"
        link(HubFrame.ProxyRegister(name, host, port))
        // Until the proxy says its servers again, the hub knows it has it.
        servers = servers + name
        return null
    }

    override fun unregister(name: String) {
        link?.invoke(HubFrame.ProxyUnregister(name))
        servers = servers.filterNot { it.equals(name, ignoreCase = true) }
    }

    override fun isFixed(name: String): Boolean = name.lowercase() in fixed

    override fun message(id: String, kind: String, text: String, subtitle: String?): String? {
        val link = link ?: return "no proxy is connected to the hub"
        link(HubFrame.ProxyMessage(id, kind, text, subtitle))
        return null
    }

    override fun kick(id: String, message: String): String? {
        val link = link ?: return "no proxy is connected to the hub"
        link(HubFrame.ProxyKick(id, message))
        return null
    }

    private companion object {
        const val MOVE_SECONDS = 30L
    }
}
