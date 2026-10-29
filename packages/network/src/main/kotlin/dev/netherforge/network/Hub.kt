package dev.netherforge.network

import kotlinx.serialization.json.JsonElement

/**
 * One end of a connection to the hub, as the hub sees it: a TCP socket ([HubServer]) or, in tests, a server in the
 * same JVM ([LocalLink]). [send] never blocks: a peer queues what it can't write yet, and closes itself when it
 * can't keep up ([NetworkLimits.maxQueuedFrames]).
 */
interface Peer {
    /** Where it connects from, for the log. */
    val address: String

    fun send(frame: HubFrame)

    /** Ends the connection; the peer calls [Hub.Session.closed] once it has. */
    fun close(reason: String)

    /**
     * The hello was accepted: from now on every line both ways is sealed with [channel], the [HubFrame.Welcome]
     * included. A connection in the same JVM ([LocalLink]) has no wire to seal.
     */
    fun secure(channel: Channel) {}
}

/**
 * A network's hub: every server's NetherForge connects to it, and it relays between them. It knows who's online
 * where (from the [proxy]), which servers are connected, what each listens to, and the network's [SharedMaps].
 *
 * One lock for all of it: frames arrive on each connection's own thread, the proxy's events on its threads, and
 * [tick] on a timer, and each holds the lock while it changes anything. What it sends is only queued
 * ([Peer.send]), and the proxy is asked to move a player outside the lock, so nothing waits on the network or the
 * proxy while holding it.
 *
 * Delivery is at most once and in order between two servers (one connection each, relayed in the order read). A
 * server that isn't connected misses what's published meanwhile; requests to it fail at once.
 */
class Hub(
    private val secret: String,
    private val proxy: HubProxy,
    private val log: HubLog,
    private val limits: NetworkLimits = NetworkLimits(),
    private val store: MapStore? = null,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val lock = Any()
    private val sessions = LinkedHashSet<Session>()
    private val byName = LinkedHashMap<String, Session>()
    private val players = LinkedHashMap<String, PlayerState>()
    private val maps = SharedMaps(limits, clock)
    private val routes = HashMap<Long, Route>()
    private var nextRoute = 1L
    private var lastSave = clock()

    /** A request on its way: who asked under which id, who must answer, and until when. */
    private class Route(val origin: Session, val originId: Long, val target: Session, val deadline: Long)

    init {
        require(secret.isNotBlank()) { "a hub needs a secret" }
        try {
            store?.load()?.let(maps::restore)
        } catch (e: Exception) {
            log.warn("NetherForge hub: couldn't read its saved maps (${e.message}); starting with none")
        }
        for (player in proxy.players()) players[player.id] = player
    }

    /** A new connection from [peer]: it's challenged at once, and must say hello within [Protocol.HELLO_MILLIS]. */
    fun open(peer: Peer): Session = synchronized(lock) {
        val session = Session(peer)
        sessions += session
        peer.send(HubFrame.Challenge(Protocol.VERSION, session.nonce))
        session
    }

    /** The servers connected now, by name. */
    fun linked(): List<String> = synchronized(lock) { byName.keys.toList() }

    /** Everything in [map], for the proxy's own commands. */
    fun entries(map: String): Map<String, JsonElement> = synchronized(lock) { maps.entries(map) }

    /** The proxy says player [id] ([name]) is now on [server]: they joined the network, or moved. */
    fun playerMoved(id: String, name: String, server: String) = synchronized(lock) {
        val previous = players[id]
        players[id] = PlayerState(id, name, server)
        if (previous == null) {
            broadcast(HubFrame.PlayerJoined(PlayerState(id, name, server)))
        } else if (previous.server != server) {
            broadcast(HubFrame.PlayerMoved(id, server, previous.server))
        }
    }

    /** The proxy says player [id] left the network. */
    fun playerLeft(id: String) = synchronized(lock) {
        if (players.remove(id) != null) broadcast(HubFrame.PlayerLeft(id))
    }

    /** Once a second or so: expired keys, requests nobody answered, connections that never said hello, saving. */
    fun tick() {
        val save = synchronized(lock) {
            val now = clock()
            for ((map, key) in maps.expire()) {
                changed(HubFrame.MapChanged(map, key, null, null, MapCause.EXPIRED))
            }
            for ((id, route) in routes.entries.filter { it.value.deadline <= now }) {
                routes.remove(id)
                route.origin.send(
                    HubFrame.Reply(
                        route.originId,
                        error = "${route.target.name} didn't answer within ${limits.requestTimeoutMillis / 1000} seconds"
                    )
                )
            }
            for (session in sessions.filter { it.name == null && now - it.openedAt > Protocol.HELLO_MILLIS }) {
                session.peer.close("it didn't say hello in time")
            }
            if (now - lastSave >= SAVE_EVERY_MILLIS && maps.takeDirty()) {
                lastSave = now
                maps.snapshot()
            } else {
                null
            }
        }
        if (save != null) save(save)
    }

    /** Saves the maps and closes every connection. */
    fun close() {
        val (all, snapshot) = synchronized(lock) { sessions.toList() to maps.snapshot() }
        for (session in all) session.peer.close("the hub is stopping")
        save(snapshot)
    }

    private fun save(snapshot: SavedMaps) {
        val store = store ?: return
        try {
            store.save(snapshot)
        } catch (e: Exception) {
            log.warn("NetherForge hub: couldn't save its maps: ${e.message}")
        }
    }

    /** Under the lock: [frame] to every connected server. */
    private fun broadcast(frame: HubFrame) {
        for (session in byName.values) session.send(frame)
    }

    /** Under the lock: a map's change to every server listening to it. */
    private fun changed(change: HubFrame.MapChanged) {
        for (session in byName.values) if (change.map in session.maps) session.send(change)
    }

    private fun servers() = proxy.servers().map { ServerState(it, it in byName) }

    /** The proxy's spelling of [name], when it has such a server (names are matched ignoring case, as Velocity does). */
    private fun serverNamed(name: String) = proxy.servers().firstOrNull { it.equals(name, ignoreCase = true) }

    /** One connection: a server once it has said hello ([name]). */
    inner class Session internal constructor(val peer: Peer) {
        val nonce = Auth.newNonce()
        internal val openedAt = clock()

        /** The server's name once its hello is accepted. */
        var name: String? = null
            private set

        internal var channels: Set<String> = emptySet()
        internal var maps: Set<String> = emptySet()
        private var closed = false

        internal fun send(frame: HubFrame) {
            if (!closed) peer.send(frame)
        }

        /** A frame from this connection, on its reader's thread. */
        fun receive(frame: ServerFrame) {
            val after = synchronized(lock) {
                if (closed) return
                if (name == null) {
                    hello(frame)
                    null
                } else {
                    handle(frame)
                }
            }
            after?.invoke()
        }

        /** The connection ended (it closed, or the peer closed it): what waited on it is told. */
        fun closed(): Unit = synchronized(lock) {
            if (closed) return
            closed = true
            sessions -= this
            val name = name
            if (name != null && byName[name] === this) {
                byName.remove(name)
                log.info("NetherForge hub: $name disconnected")
                broadcast(HubFrame.Server(ServerState(name, false)))
            }
            for ((id, route) in routes.entries.filter { it.value.target === this || it.value.origin === this }) {
                routes.remove(id)
                if (route.origin !== this) route.origin.send(HubFrame.Reply(route.originId, error = "$name disconnected before answering"))
            }
        }

        private fun hello(frame: ServerFrame) {
            if (frame !is ServerFrame.Hello) return refuse("it sent something before saying hello")
            if (frame.version != Protocol.VERSION) {
                return refuse(
                    "it speaks the network's protocol version ${frame.version}, and this hub speaks ${Protocol.VERSION}: " +
                        "update NetherForge on the ${if (frame.version < Protocol.VERSION) "server" else "proxy"}"
                )
            }
            if (!Auth.isNonce(frame.nonce)) return refuse("its hello has no nonce of its own")
            if (!Auth.verify(secret, nonce, frame.server, frame.nonce, frame.proof)) {
                return refuse("its secret isn't the hub's (network.secret in its config.yml must be the proxy plugin's)")
            }
            val server = serverNamed(frame.server)
                ?: return refuse(
                    "the proxy has no server named \"${frame.server}\" (network.server-name in its config.yml must be the " +
                        "server's name in the proxy's config; it has ${proxy.servers().joinToString()})"
                )
            byName[server]?.let { previous ->
                previous.peer.close("$server connected again")
                previous.closed()
            }
            name = server
            byName[server] = this
            log.info("NetherForge hub: $server connected from ${peer.address}")
            // Sealed from the welcome on, under the name the server said (the one its keys are bound to).
            peer.secure(Channel.hub(secret, nonce, frame.nonce, frame.server))
            send(HubFrame.Welcome(server, servers(), players.values.toList(), limits.maxValueBytes))
            for (other in byName.values) if (other !== this) other.send(HubFrame.Server(ServerState(server, true)))
        }

        private fun refuse(why: String) {
            log.warn("NetherForge hub: refused a connection from ${peer.address}: $why")
            peer.send(HubFrame.Refused(why))
            peer.close(why)
        }

        /** Under the lock; what it returns runs after it's let go of. */
        private fun handle(frame: ServerFrame): (() -> Unit)? {
            val from = name!!
            when (frame) {
                is ServerFrame.Hello -> send(HubFrame.Problem("it already said hello"))
                is ServerFrame.Ping -> send(HubFrame.Pong)
                is ServerFrame.Listen -> listen(frame)
                is ServerFrame.Publish -> {
                    if (!checked(frame.channel, frame.value)) return null
                    val targets = if (frame.to == null) byName.values else listOfNotNull(serverNamed(frame.to)?.let(byName::get))
                    val message = HubFrame.Message(frame.channel, from, frame.value)
                    for (target in targets) if (frame.channel in target.channels) target.send(message)
                }
                is ServerFrame.Request -> request(frame, from)
                is ServerFrame.Reply -> {
                    val route = routes[frame.id]?.takeIf { it.target === this } ?: return null
                    routes.remove(frame.id)
                    val size = Protocol.size(frame.value)
                    val reply = if (size > limits.maxValueBytes) {
                        HubFrame.Reply(
                            route.originId,
                            error = "$from's reply is $size bytes, over the network's limit of ${limits.maxValueBytes}"
                        )
                    } else {
                        HubFrame.Reply(route.originId, frame.value, frame.error)
                    }
                    route.origin.send(reply)
                }
                is ServerFrame.MapWrite -> write(frame, from)
                is ServerFrame.Send -> return sendPlayer(frame, from)
            }
            return null
        }

        private fun listen(frame: ServerFrame.Listen) {
            channels = frame.channels.filter(NetworkNames::isFull).toSet()
            val before = maps
            maps = frame.maps.filter(NetworkNames::isFull).toSet()
            for (map in maps - before) send(HubFrame.MapSnapshot(map, this@Hub.maps.entries(map)))
        }

        private fun request(frame: ServerFrame.Request, from: String) {
            fun fail(why: String) = send(HubFrame.Reply(frame.id, error = why))
            if (!NetworkNames.isFull(frame.channel)) return fail("\"${frame.channel}\" isn't a channel")
            val size = Protocol.size(frame.value)
            if (size > limits.maxValueBytes) return fail("the request is $size bytes, over the network's limit of ${limits.maxValueBytes}")
            val target = serverNamed(frame.to)?.let(byName::get)
                ?: return fail("${frame.to} isn't connected to the network")
            if (frame.channel !in target.channels) return fail("nothing on ${target.name} answers requests on \"${frame.channel}\"")
            val id = nextRoute++
            routes[id] = Route(this, frame.id, target, clock() + limits.requestTimeoutMillis)
            target.send(HubFrame.Requested(id, frame.channel, from, frame.value))
        }

        private fun write(frame: ServerFrame.MapWrite, from: String) {
            if (!NetworkNames.isFull(frame.map)) return send(HubFrame.MapResult(frame.id, error = "\"${frame.map}\" isn't a map"))
            if (!NetworkNames.isKey(
                    frame.key
                )
            ) {
                return send(HubFrame.MapResult(frame.id, error = "a key is 1 to ${NetworkNames.MAX_KEY_LENGTH} characters"))
            }
            when (val outcome = this@Hub.maps.write(frame)) {
                is SharedMaps.Outcome.Refused -> send(HubFrame.MapResult(frame.id, error = outcome.why))
                is SharedMaps.Outcome.Unchanged -> send(HubFrame.MapResult(frame.id, outcome.result))
                is SharedMaps.Outcome.Changed -> {
                    val cause = if (outcome.value == null) MapCause.DELETE else MapCause.SET
                    changed(HubFrame.MapChanged(frame.map, frame.key, outcome.value, from, cause))
                    send(HubFrame.MapResult(frame.id, outcome.result))
                }
            }
        }

        private fun sendPlayer(frame: ServerFrame.Send, from: String): (() -> Unit)? {
            fun fail(why: String): (() -> Unit)? {
                send(HubFrame.SendResult(frame.id, why))
                return null
            }
            val player = players[frame.player] ?: return fail("they aren't online in the network")
            val server = serverNamed(frame.server) ?: return fail("the proxy has no server named \"${frame.server}\"")
            val size = Protocol.size(frame.data)
            if (size > limits.maxValueBytes) return fail("the data is $size bytes, over the network's limit of ${limits.maxValueBytes}")
            if (player.server == server) return fail("they're already on $server")
            val target = byName[server]
            target?.send(HubFrame.Arriving(player.id, from, frame.data))
            return {
                val moved = try {
                    proxy.send(player.id, server)
                } catch (e: Exception) {
                    java.util.concurrent.CompletableFuture.completedFuture(e.message ?: e.toString())
                }
                moved.whenComplete { error, failure ->
                    val why = failure?.let { it.message ?: it.toString() } ?: error
                    synchronized(lock) {
                        if (why != null && target != null) target.send(HubFrame.ArrivalCancelled(player.id))
                        send(HubFrame.SendResult(frame.id, why))
                    }
                }
            }
        }

        /** Whether [channel] is a channel name and [value] within the limit; tells the server when not. */
        private fun checked(channel: String, value: JsonElement): Boolean {
            if (!NetworkNames.isFull(channel)) {
                send(HubFrame.Problem("\"$channel\" isn't a channel"))
                return false
            }
            val size = Protocol.size(value)
            if (size > limits.maxValueBytes) {
                send(HubFrame.Problem("a message on \"$channel\" is $size bytes, over the network's limit of ${limits.maxValueBytes}"))
                return false
            }
            return true
        }
    }

    companion object {
        private const val SAVE_EVERY_MILLIS = 5_000L
    }
}
