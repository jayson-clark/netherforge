package dev.netherforge.plugin.metrics

import com.sun.net.httpserver.HttpServer
import dev.netherforge.network.Lifecycle
import dev.netherforge.network.Prometheus
import dev.netherforge.plugin.NetherForgeRuntime
import java.net.InetSocketAddress

/** `metrics:` in `config.yml`: where the Prometheus endpoint listens, if anywhere. */
data class MetricsConfig(val port: Int? = null, val bind: String = "127.0.0.1")

/**
 * The server's metrics, in Prometheus's text format at `/metrics`: how it keeps up (TPS, the tick's time, NetherForge's
 * share of it), players, entities, centities and chunks, what each package's scripts cost and hold, what each package
 * sends and hears on the network, its state and how long it took to be ready. Gathered on the main thread once a
 * second (all of it counted, nothing listed), served from that.
 */
class ServerMetrics(private val config: MetricsConfig, private val runtime: NetherForgeRuntime) {
    private var server: HttpServer? = null

    @Volatile
    private var text = ""

    /** NetherForge's own share of each tick lately: its average and the worst, in nanoseconds. */
    private var tickNanos = 0L
    private var tickCount = 0
    private var worstNanos = 0L

    val port: Int get() = server?.address?.port ?: -1

    fun start() {
        val port = config.port ?: return
        try {
            server = Prometheus.serve(InetSocketAddress(config.bind, port)) { text }
            runtime.log.info("NetherForge's metrics are at http://${config.bind}:${this.port}/metrics")
        } catch (e: Exception) {
            runtime.log.error("NetherForge's metrics couldn't listen on ${config.bind}:$port", e)
        }
    }

    fun stop() {
        server?.stop(0)
        server = null
    }

    /** What NetherForge's part of this tick took. */
    fun record(nanos: Long) {
        tickNanos += nanos
        tickCount++
        if (nanos > worstNanos) worstNanos = nanos
    }

    /** NetherForge's share of the tick lately, in milliseconds (the average since the last gathering). */
    val runtimeTickMillis: Double get() = if (tickCount == 0) 0.0 else tickNanos / tickCount / 1e6

    /** The main thread, each tick: once a second, the metrics gathered again. */
    fun tick(tick: Long) {
        if (server == null || tick % GATHER_TICKS != 0L) return
        text = gather()
        tickNanos = 0
        tickCount = 0
        worstNanos = 0
    }

    private fun gather(): String {
        val platform = runtime.platform
        val out = Prometheus()
        out.sample("netherforge_tps", "Ticks a second, averaged over the last minute", platform.performance.ticksPerSecond())
        out.sample(
            "netherforge_tick_milliseconds",
            "How long a tick takes, averaged over the last 100",
            platform.performance.tickMilliseconds()
        )
        out.sample("netherforge_runtime_tick_milliseconds", "NetherForge's share of a tick", runtimeTickMillis, mapOf("stat" to "average"))
        out.sample("netherforge_runtime_tick_milliseconds", "NetherForge's share of a tick", worstNanos / 1e6, mapOf("stat" to "worst"))
        out.sample("netherforge_players", "Players on this server", platform.players.online().size)
        for (world in platform.worlds.names()) {
            out.sample("netherforge_entities", "Entities in a world", platform.worlds.entityCount(world), mapOf("world" to world))
            out.sample("netherforge_chunks", "Loaded chunks in a world", platform.worlds.chunkCount(world), mapOf("world" to world))
        }
        val session = runtime.session
        out.sample("netherforge_centities", "Centities alive", session.centities.all().size)
        val costs = session.costs.report().groupBy { it.scope.namespace }
        for ((namespace, entries) in costs) {
            val labels = mapOf("package" to namespace)
            out.sample(
                "netherforge_script_milliseconds",
                "What a package's scripts take a tick, on average",
                entries.sumOf {
                    it.averageMillis
                },
                labels
            )
            out.sample("netherforge_script_bytes", "What a package's scripts hold in memory", entries.sumOf { it.bytes }, labels)
            out.sample("netherforge_scripts", "A package's scripts running", entries.size, labels)
        }
        for ((namespace, count) in session.network.sentCounts()) {
            out.sample(
                "netherforge_network_sent_total",
                "What a package sent to the network",
                count,
                mapOf("package" to namespace),
                counter = true
            )
        }
        for ((namespace, count) in session.network.heardCounts()) {
            out.sample(
                "netherforge_network_heard_total",
                "What reached a package from the network",
                count,
                mapOf("package" to namespace),
                counter = true
            )
        }
        val link = runtime.network
        out.sample("netherforge_network_connected", "Whether this server is connected to its network's hub", if (link.connected) 1 else 0)
        for (state in Lifecycle.entries) {
            out.sample(
                "netherforge_state",
                "Where this server is in its life",
                if (link.state ==
                    state
                ) {
                    1
                } else {
                    0
                },
                mapOf("state" to state.word)
            )
        }
        link.startupMillis?.let { out.sample("netherforge_startup_milliseconds", "From the process starting to ready", it) }
        return out.toString()
    }

    private companion object {
        const val GATHER_TICKS = 20L
    }
}
