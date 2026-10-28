package dev.netherforge.plugin.network

import dev.netherforge.network.Auth
import dev.netherforge.network.Hub
import dev.netherforge.network.HubClient
import dev.netherforge.network.HubFrame
import dev.netherforge.network.HubLink
import dev.netherforge.network.HubListener
import dev.netherforge.network.HubLog
import dev.netherforge.network.HubProxy
import dev.netherforge.network.LocalLink
import dev.netherforge.network.NetworkLimits
import dev.netherforge.network.PlayerState
import dev.netherforge.network.ServerFrame
import kotlinx.serialization.json.JsonElement
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore

/**
 * This server's place in its network, for the plugin's life (sessions come and go; the connection doesn't): the
 * link to the hub, what the hub has said of the network (who's online where, which servers are connected), and the
 * players sent here who haven't arrived yet. `session.network` ([Network]) is what scripts reach; it drains what
 * came in once a tick.
 *
 * Three ways to be in a network ([mode]):
 * - **remote**: `network.enabled` in `config.yml`. A [HubClient] to the proxy plugin's hub, on threads of its own.
 *   What it hears is queued for the main thread; past [NetworkConfig.maxQueued] its reader waits, which stops it
 *   reading the socket: backpressure, as for WebSockets, not dropping.
 * - **local**: a dev server not put in a network, and a script test run. A [Hub] in this JVM with this server alone
 *   behind a [LocalProxy] (named `dev`, or `test`), so channels and maps work as they would in a network; a test
 *   adds the other servers ([peer]) and plays them. Everything happens on the main thread.
 * - **off**: a production server not in a network. Nothing connects; `nf.network` answers as a server that isn't
 *   connected does, and packages that declare it have the warning `runtime.network-disabled`.
 *
 * Only the main thread reads the state ([players], [servers], [connected]): it's applied in [drain].
 */
class NetworkLink(
    private val config: NetworkConfig,
    /** The name of a network of this server alone, or null for none: `dev` on a dev server, `test` in a test run. */
    private val alone: String?,
    private val kick: (UUID, String) -> Unit,
    /** A test run's: joins the player named here, as they would arrive (the fake's join). */
    private val joinHere: ((String) -> Unit)? = null
) {
    enum class Mode { REMOTE, LOCAL, OFF }

    val mode: Mode = when {
        config.enabled && config.problem() == null -> Mode.REMOTE
        alone != null && !config.enabled -> Mode.LOCAL
        else -> Mode.OFF
    }

    /** This server's name in the network, or null when it isn't in one. */
    val name: String? = when (mode) {
        Mode.REMOTE -> config.serverName
        Mode.LOCAL -> alone
        Mode.OFF -> null
    }

    /** What came in, for the main thread. */
    sealed interface Inbound {
        data class Connected(val welcome: HubFrame.Welcome) : Inbound

        data class Disconnected(val why: String) : Inbound

        data class Failed(val why: String) : Inbound

        data class Frame(val frame: HubFrame) : Inbound

        /** A player left the network: who they were, since [players] no longer has them. */
        data class Left(val player: PlayerState) : Inbound
    }

    /** A player sent here, waiting to arrive: who sent them, with what, and the tick it was heard. */
    class Arrival(val from: String, val data: JsonElement?, val tick: Long)

    private val inbound = LinkedBlockingQueue<Inbound>()
    private val room = Semaphore(config.maxQueued)
    private var link: HubLink? = null

    /** The local network's hub, its proxy, and the secret its servers prove they know. */
    private var hub: Hub? = null
    private val secret = Auth.newSecret()
    private var proxy: LocalProxy? = null
    private var nextId = 1L

    // The network as the hub last said, the main thread's alone.
    var connected = false
        private set
    val players = LinkedHashMap<String, PlayerState>()
    val servers = LinkedHashMap<String, Boolean>()
    var maxValueBytes = NetworkLimits.DEFAULT_MAX_VALUE_BYTES
        private set
    val arrivals = HashMap<String, Arrival>()

    /** The last failure logged, and when, so a hub that's down is said once, not every few seconds. */
    private var lastFailure: String? = null
    private var lastFailureAt = 0L

    private val listener = object : HubListener {
        override fun connected(welcome: HubFrame.Welcome) {
            // A local link connects as it starts, on the main thread: the network is there before the first tick, whose
            // drain raises `network_connect`.
            if (mode == Mode.LOCAL) welcomed(welcome)
            queue(Inbound.Connected(welcome))
        }

        override fun received(frame: HubFrame) = queue(Inbound.Frame(frame))

        override fun disconnected(why: String) = queue(Inbound.Disconnected(why))

        override fun failed(why: String) = queue(Inbound.Failed(why))
    }

    /**
     * Queues [item]; on a remote link, waits for room first (the link's reader thread). A wait interrupted is the
     * link stopping (its last word is `disconnected`, said with the interrupt pending): nothing drains what's said
     * then, so it's dropped, and the thread stays interrupted to end.
     */
    private fun queue(item: Inbound) {
        if (mode == Mode.REMOTE) {
            try {
                room.acquire()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
        inbound += item
    }

    /**
     * An id for a request, write or send: one sequence for the plugin's life, so an answer to an old session's never
     * completes a new one's.
     */
    fun nextId(): Long = nextId++

    fun start(log: (String) -> Unit) {
        when (mode) {
            Mode.OFF -> if (config.enabled) log("NetherForge isn't joining its network: ${config.problem()}")
            Mode.REMOTE -> {
                log("Joining the network as ${config.serverName} through its hub at ${config.host}:${config.port}")
                link = HubClient(config.host, config.port, config.serverName!!, config.secret!!, listener).also { it.start() }
            }
            Mode.LOCAL -> {
                val proxy = LocalProxy(alone!!)
                val hub = Hub(secret, proxy, Quiet)
                proxy.hub = hub
                this.proxy = proxy
                this.hub = hub
                link = LocalLink(hub, alone, secret, listener).also { it.start() }
            }
        }
    }

    fun stop() {
        link?.stop()
        link = null
        hub?.close()
        hub = null
    }

    /** Sends [frame] to the hub; false when not connected. */
    fun send(frame: ServerFrame): Boolean = link?.send(frame) == true

    /** The main thread, once a tick: a local hub's own tick (expiry, timeouts) once a second. */
    fun tick(tick: Long) {
        if (tick % 20 == 0L) hub?.tick()
    }

    /**
     * Up to [max] of what came in, oldest first, with the state they change ([connected], [players], [servers],
     * [arrivals]) already changed. A failure to connect is logged here, once per new reason (or every few minutes).
     */
    fun drain(max: Int, tick: Long, info: (String) -> Unit, warn: (String) -> Unit): List<Inbound> {
        val out = ArrayList<Inbound>()
        while (out.size < max) {
            val item = inbound.poll() ?: break
            if (mode == Mode.REMOTE) room.release()
            when (item) {
                is Inbound.Connected -> {
                    if (mode == Mode.REMOTE) {
                        welcomed(item.welcome)
                        info("Joined the network as ${item.welcome.server}: ${item.welcome.servers.count { it.linked }} servers connected")
                    }
                }
                is Inbound.Disconnected -> {
                    connected = false
                    players.clear()
                    servers.clear()
                    if (mode == Mode.REMOTE) warn("Lost the connection to the network's hub (${item.why}); trying again")
                }
                is Inbound.Failed -> {
                    val now = System.nanoTime()
                    if (item.why != lastFailure || now - lastFailureAt > FAILURE_EVERY_NANOS) {
                        lastFailure = item.why
                        lastFailureAt = now
                        warn("Couldn't join the network: ${item.why}; trying again")
                    }
                }
                is Inbound.Frame -> {
                    val frame = item.frame
                    if (frame is HubFrame.PlayerLeft) {
                        players.remove(frame.id)?.let { out += Inbound.Left(it) }
                        continue
                    }
                    apply(frame, tick)
                }
                is Inbound.Left -> Unit
            }
            out += item
        }
        return out
    }

    /** The network as [welcome] says it is: connected, with everyone and every server in it. */
    private fun welcomed(welcome: HubFrame.Welcome) {
        connected = true
        lastFailure = null
        maxValueBytes = welcome.maxValueBytes
        players.clear()
        for (player in welcome.players) players[player.id] = player
        servers.clear()
        for (server in welcome.servers) servers[server.name] = server.linked
    }

    private fun apply(frame: HubFrame, tick: Long) {
        when (frame) {
            is HubFrame.PlayerJoined -> players[frame.player.id] = frame.player
            is HubFrame.PlayerMoved -> players[frame.id]?.let { players[frame.id] = it.copy(server = frame.server) }
            is HubFrame.Server -> servers[frame.server.name] = frame.server.linked
            is HubFrame.Arriving -> arrivals[frame.player] = Arrival(frame.from, frame.data, tick)
            is HubFrame.ArrivalCancelled -> arrivals.remove(frame.player)
            else -> Unit
        }
    }

    // ---- this server's own players, in a local network -------------------------

    /** A player joined this server: in a local network the proxy is this server, and says so. */
    fun joinedHere(id: UUID, name: String) {
        proxy?.place(id.toString(), name, this.name!!)
    }

    /** A player left this server: in a local network they leave it, unless they were sent elsewhere. */
    fun quitHere(id: UUID) {
        val proxy = proxy ?: return
        if (proxy.serverOf(id.toString()) == this.name) proxy.leave(id.toString())
    }

    // ---- a test's other servers --------------------------------------------------

    private val peers = LinkedHashMap<String, TestPeer>()

    /** The test's server [name], added to the network the first time it's named. Only in a local network. */
    fun peer(name: String): TestPeer {
        val hub = hub ?: error("only a local network has the test's servers")
        peers[name]?.let { return it }
        proxy!!.peers += name
        val peer = TestPeer(name, hub)
        peers[name] = peer
        peer.start()
        return peer
    }

    fun peers(): Collection<TestPeer> = peers.values

    /** A player joins (or moves to) [server] of a local network, as the proxy says it. */
    fun place(id: String, player: String, server: String) {
        proxy?.place(id, player, server)
    }

    fun leave(id: String) {
        proxy?.leave(id)
    }

    /** Another server of a test's network, played by the test: what it hears is kept, requests to it queued. */
    inner class TestPeer internal constructor(val name: String, hub: Hub) : HubListener {
        private val link = LocalLink(hub, name, secret, this)
        val received = ArrayList<HubFrame.Message>()
        val requests = ArrayDeque<HubFrame.Requested>()

        /** The answers to what it asked, by id. */
        val replies = HashMap<Long, HubFrame.Reply>()
        private val channels = LinkedHashSet<String>()

        internal fun start() = link.start()

        /** Listens to [channel] from now on. */
        fun listen(channel: String) {
            if (channels.add(channel)) link.send(ServerFrame.Listen(channels.toList(), emptyList()))
        }

        fun send(frame: ServerFrame) = link.send(frame)

        override fun connected(welcome: HubFrame.Welcome) = Unit

        override fun received(frame: HubFrame) {
            when (frame) {
                is HubFrame.Message -> received += frame
                is HubFrame.Requested -> requests += frame
                is HubFrame.Reply -> replies[frame.id] = frame
                else -> Unit
            }
        }

        override fun disconnected(why: String) = Unit

        override fun failed(why: String) = Unit
    }

    /**
     * The proxy of a local network: this server, and a test's other servers. Moving a player here joins them (a
     * test's), and moving one of this server's elsewhere kicks them from it, as the proxy taking them would.
     */
    private inner class LocalProxy(private val here: String) : HubProxy {
        lateinit var hub: Hub
        val peers = LinkedHashSet<String>()

        /** Where each player is: what a quit here needs, to tell leaving the network from being sent elsewhere. */
        private val where = HashMap<String, String>()
        private val names = HashMap<String, String>()

        fun serverOf(id: String): String? = where[id]

        fun place(id: String, name: String, server: String) {
            where[id] = server
            names[id] = name
            hub.playerMoved(id, name, server)
        }

        fun leave(id: String) {
            where.remove(id)
            names.remove(id)
            hub.playerLeft(id)
        }

        override fun servers(): List<String> = listOf(here) + peers

        override fun players(): List<PlayerState> = emptyList()

        override fun send(id: String, server: String): CompletionStage<String?> {
            when {
                server == here -> {
                    val join = joinHere ?: return CompletableFuture.completedFuture("this server is a network of its own")
                    val name = names[id] ?: return CompletableFuture.completedFuture("they aren't online in the network")
                    // The join here says where they are.
                    join(name)
                }
                else -> {
                    val from = where[id]
                    place(id, names[id] ?: id, server)
                    if (from == here) kick(UUID.fromString(id), "Sent to $server")
                }
            }
            return CompletableFuture.completedFuture(null)
        }
    }

    private object Quiet : HubLog {
        override fun info(message: String) = Unit

        override fun warn(message: String) = Unit
    }

    private companion object {
        const val FAILURE_EVERY_NANOS = 5 * 60 * 1_000_000_000L
    }
}
