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
 *
 * A server joins with the network's shared [secret] (it's trusted with everything) or a [Credential] of its own, whose
 * [Grants] the hub holds every frame from it to: what it may publish, ask, answer, read and write, whose players it
 * sees, and where it may send them. A refusal is answered as any failure is, saying it's forbidden, and never reaches
 * another server.
 */
class Hub(
    private val secret: String?,
    private val proxy: HubProxy,
    private val log: HubLog,
    private val limits: NetworkLimits = NetworkLimits(),
    private val store: MapStore? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    credentials: Collection<Credential> = emptyList(),
    groups: Map<String, GroupSettings> = emptyMap()
) {
    private val lock = Any()
    private val sessions = LinkedHashSet<Session>()
    private val byName = LinkedHashMap<String, Session>()
    private val players = LinkedHashMap<String, PlayerState>()
    private val maps = SharedMaps(limits, clock)
    private val routes = HashMap<Long, Route>()
    private var nextRoute = 1L
    private var lastSave = clock()
    private var credentials: Map<String, Credential> = credentials.associateBy { it.id }

    /** The server each online player was on before the one they're on now: where `back` sends them. */
    private val cameFrom = HashMap<String, String>()

    /** The owner's settings for each group, by its name in lowercase. */
    private var groups: Map<String, GroupSettings> = groups.mapKeys { it.key.lowercase() }

    /** Players on their way to each server and not there yet: counted as there when picking an instance. */
    private val incoming = HashMap<String, Int>()

    /** Each server as the last [HubFrame.Servers] (or welcome) told everyone, to send only what changed. */
    private var told: Map<String, ServerState> = emptyMap()

    /** Turns, for spreading requests to a group across its instances. */
    private var nextTurn = 0

    /** A request on its way: who asked under which id, who must answer, and until when. */
    private class Route(val origin: Session, val originId: Long, val target: Session, val deadline: Long)

    init {
        require(secret == null || secret.isNotBlank()) { "a hub's shared secret can't be blank" }
        try {
            store?.load()?.let(maps::restore)
        } catch (e: Exception) {
            log.warn("NetherForge hub: couldn't read its saved maps (${e.message}); starting with none")
        }
        for (player in proxy.players()) players[player.id] = player
        told = servers().associateBy { it.name }
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

    /** Each server connected now, with the credential it joined with (null: the shared secret) and its grants. */
    fun connections(): List<Connection> = synchronized(lock) {
        byName.values.map { Connection(it.name!!, it.credential, it.grants, it.peer.address) }
    }

    data class Connection(val server: String, val credential: String?, val grants: Grants, val address: String)

    /** The credentials servers may join with now. */
    fun credentials(): List<Credential> = synchronized(lock) { credentials.values.toList() }

    /**
     * Replaces the credentials servers may join with: a server whose credential is gone (revoked) or changed is
     * disconnected at once, and one whose grants changed dials again under the new ones.
     */
    fun setCredentials(all: Collection<Credential>) {
        val dropped = synchronized(lock) {
            val next = all.associateBy { it.id }
            credentials = next
            byName.values.filter { session -> session.credential != null && next[session.credential] != session.joinedWith }
        }
        for (session in dropped) {
            val why = if (session.credential !in credentials) "its credential was revoked" else "its credential changed"
            log.info("NetherForge hub: disconnected ${session.name}: $why")
            session.peer.close(why)
            session.closed()
        }
    }

    /** Replaces the owner's settings for groups. */
    fun setGroups(all: Map<String, GroupSettings>) = synchronized(lock) { groups = all.mapKeys { it.key.lowercase() } }

    /** Every server of the network as the hub knows it now: its group, its room, its players. */
    fun states(): List<ServerState> = synchronized(lock) { servers() }

    /** Everything in [map], for the proxy's own commands. */
    fun entries(map: String): Map<String, JsonElement> = synchronized(lock) { maps.entries(map) }

    /** The proxy says player [id] ([name]) is now on [server]: they joined the network, or moved. */
    fun playerMoved(id: String, name: String, server: String) = synchronized(lock) {
        val previous = players[id]
        val now = PlayerState(id, name, server)
        players[id] = now
        if (previous == null) {
            cameFrom.remove(id)
            for (session in byName.values) session.presence(null, now)
        } else if (previous.server != server) {
            previous.server?.let { cameFrom[id] = it }
            for (session in byName.values) session.presence(previous, now)
        }
    }

    /** The proxy says player [id] left the network. */
    fun playerLeft(id: String) = synchronized(lock) {
        val previous = players.remove(id) ?: return@synchronized
        cameFrom.remove(id)
        for (session in byName.values) session.presence(previous, null)
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
            tellServers()
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

    /** Under the lock: every server the proxy has, with what its NetherForge said of it and who's on it. */
    private fun servers(): List<ServerState> {
        val counts = players.values.groupingBy { it.server }.eachCount()
        return proxy.servers().map { state(it, counts[it] ?: 0) }
    }

    private fun state(name: String, players: Int): ServerState {
        val session = byName[name] ?: return ServerState(name, false, players = players)
        return ServerState(name, true, session.group, session.capacity, session.accepting, players)
    }

    /** Under the lock: what changed of the servers since everyone was last told, to everyone. */
    private fun tellServers() {
        val now = servers().associateBy { it.name }
        val changed = now.values.filter { told[it.name] != it }
        told = now
        if (changed.isNotEmpty()) broadcast(HubFrame.Servers(changed))
    }

    /** Under the lock: the connected instances of [group] (a server with no group is a group of one, its name). */
    private fun members(group: String): List<Session> =
        byName.values.filter { (it.group ?: it.name!!).equals(group, ignoreCase = true) }.sortedBy { it.name }

    /** Players on [session]'s server and on their way to it. */
    private fun occupancy(session: Session, counts: Map<String?, Int>) = (counts[session.name] ?: 0) + (incoming[session.name] ?: 0)

    /**
     * Under the lock: the instance of [group] to send [size] players to, as [balance] (or the group's own) picks it
     * among those that take players and have room for them all; null when none has.
     */
    private fun pick(group: String, balance: Balance?, size: Int): Session? {
        val counts = players.values.groupingBy { it.server }.eachCount()
        val room = members(group).filter { session ->
            session.accepting && session.capacity.let { it == null || occupancy(session, counts) + size <= it }
        }
        return when (balance ?: groups[group.lowercase()]?.balance ?: Balance.LEAST) {
            Balance.LEAST -> room.minByOrNull { occupancy(it, counts) }
            Balance.FILL -> room.maxByOrNull { occupancy(it, counts) }
        }
    }

    /** Who [session] may see: everyone, or with no presence only those on it. */
    private fun visible(session: Session): List<PlayerState> =
        if (session.grants.presence) players.values.toList() else players.values.filter { it.server == session.name }

    /** The proxy's spelling of [name], when it has such a server (names are matched ignoring case, as Velocity does). */
    private fun serverNamed(name: String) = proxy.servers().firstOrNull { it.equals(name, ignoreCase = true) }

    /** One connection: a server once it has said hello ([name]). */
    inner class Session internal constructor(val peer: Peer) {
        val nonce = Auth.newNonce()
        internal val openedAt = clock()

        /** The server's name once its hello is accepted. */
        var name: String? = null
            private set

        /** The credential it joined with (null: the network's shared secret), as it was then, and what it may do. */
        internal var credential: String? = null
        internal var joinedWith: Credential? = null
        internal var grants: Grants = Grants.TRUSTED

        /** The group it's an instance of (null: a group of one), how many players it takes, and whether it takes them now. */
        internal var group: String? = null
        internal var capacity: Int? = null
        internal var accepting = true

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
                val state = state(name, players.values.count { it.server == name })
                broadcast(HubFrame.Server(state))
                told = told + (name to state)
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
            val credential = frame.credential?.let { id ->
                credentials[id] ?: return refuse("it uses the credential \"$id\", which the hub doesn't have (revoked, or never added)")
            }
            val proven = if (credential != null) credential.secret else secret
            if (proven == null) {
                return refuse("it uses the network's shared secret, and this hub has none: give it a credential (/nfhub credentials add)")
            }
            if (!Auth.verify(proven, nonce, frame.server, frame.nonce, frame.credential, frame.proof)) {
                return refuse(
                    if (credential != null) {
                        "its secret isn't credential \"${credential.id}\"'s (network.credential in its config.yml)"
                    } else {
                        "its secret isn't the hub's (network.secret in its config.yml must be the proxy plugin's)"
                    }
                )
            }
            if (frame.group != null &&
                !NetworkNames.isServer(frame.group)
            ) {
                return refuse("its group \"${frame.group}\" isn't a group's name")
            }
            if (frame.capacity != null && frame.capacity < 1) return refuse("its capacity ${frame.capacity} isn't at least 1")
            val group = credential?.group?.let { bound ->
                if (frame.group != null && !frame.group.equals(bound, ignoreCase = true)) {
                    return refuse("its credential \"${credential.id}\" is for the group $bound, and it says it's in ${frame.group}")
                }
                bound
            } ?: frame.group
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
            this.credential = credential?.id
            joinedWith = credential
            grants = credential?.grants ?: Grants.TRUSTED
            this.group = group
            capacity = frame.capacity
            accepting = true
            byName[server] = this
            val how = if (credential == null) "the shared secret" else "credential ${credential.id} (${grants.describe()})"
            log.info("NetherForge hub: $server connected from ${peer.address} with $how")
            // Sealed from the welcome on, under the name the server said (the one its keys are bound to).
            peer.secure(Channel.hub(proven, nonce, frame.nonce, frame.server, frame.credential))
            send(HubFrame.Welcome(server, servers(), visible(this), limits.maxValueBytes, grants))
            val state = state(server, players.values.count { it.server == server })
            for (other in byName.values) if (other !== this) other.send(HubFrame.Server(state))
            told = told + (server to state)
        }

        /**
         * Under the lock: a player went from [before] to [after] (null: not in the network). A server with presence
         * hears every move; one without hears a player join when they come to it and leave when they go.
         */
        internal fun presence(before: PlayerState?, after: PlayerState?) {
            if (grants.presence) {
                when {
                    before == null && after != null -> send(HubFrame.PlayerJoined(after))
                    after == null && before != null -> send(HubFrame.PlayerLeft(before.id))
                    after != null -> send(HubFrame.PlayerMoved(after.id, after.server!!, before?.server))
                }
                return
            }
            val was = before?.server == name
            val isHere = after?.server == name
            if (isHere && !was) send(HubFrame.PlayerJoined(after!!))
            if (was && !isHere) send(HubFrame.PlayerLeft(before!!.id))
        }

        /** Why this server may not do [access] on [full], or null when it may. */
        private fun forbidden(full: String, access: Access): String? {
            if (grants.allows(full, access)) return null
            val verb = when (access) {
                Access.READ -> "listen to"
                Access.WRITE -> "write to"
                Access.REQUEST -> "ask on"
                Access.ANSWER -> "answer on"
            }
            return "network.forbidden: ${credentialName()} may not $verb \"$full\" (its owns and shares in the hub's credentials)"
        }

        private fun credentialName() = "credential \"$credential\" (${grants.role})"

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
                is ServerFrame.Status -> accepting = frame.accepting
                is ServerFrame.Listen -> listen(frame)
                is ServerFrame.Publish -> {
                    if (!checked(frame.channel, frame.value)) return null
                    forbidden(frame.channel, Access.WRITE)?.let {
                        send(HubFrame.Problem(it))
                        return null
                    }
                    val targets = when {
                        frame.to != null -> listOfNotNull(serverNamed(frame.to)?.let(byName::get))
                        frame.group != null -> members(frame.group)
                        else -> byName.values
                    }
                    val message = HubFrame.Message(frame.channel, from, frame.value)
                    for (target in targets) {
                        if (frame.channel in target.channels && target.grants.allows(frame.channel, Access.READ)) target.send(message)
                    }
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
            val asked = frame.channels.filter(NetworkNames::isFull)
            channels = asked.filter { grants.allows(it, Access.READ) || grants.allows(it, Access.ANSWER) }.toSet()
            val before = maps
            val askedMaps = frame.maps.filter(NetworkNames::isFull)
            maps = askedMaps.filter { grants.allows(it, Access.READ) }.toSet()
            val refused = (asked - channels) + (askedMaps - maps)
            if (refused.isNotEmpty()) {
                send(HubFrame.Problem("network.forbidden: ${credentialName()} may not listen to ${refused.joinToString { "\"$it\"" }}"))
            }
            for (map in maps - before) send(HubFrame.MapSnapshot(map, this@Hub.maps.entries(map)))
        }

        private fun request(frame: ServerFrame.Request, from: String) {
            fun fail(why: String) = send(HubFrame.Reply(frame.id, error = why))
            if (!NetworkNames.isFull(frame.channel)) return fail("\"${frame.channel}\" isn't a channel")
            val size = Protocol.size(frame.value)
            if (size > limits.maxValueBytes) return fail("the request is $size bytes, over the network's limit of ${limits.maxValueBytes}")
            forbidden(frame.channel, Access.REQUEST)?.let { return fail(it) }
            fun answers(session: Session) = frame.channel in session.channels && session.grants.allows(frame.channel, Access.ANSWER)
            val target = if (frame.group != null) {
                val answering = members(frame.group).filter(::answers)
                if (answering.isEmpty()) {
                    return fail(
                        if (members(frame.group).isEmpty()) {
                            "no instance of ${frame.group} is connected to the network"
                        } else {
                            "nothing on any instance of ${frame.group} answers requests on \"${frame.channel}\""
                        }
                    )
                }
                answering[Math.floorMod(nextTurn++, answering.size)]
            } else {
                val to = frame.to ?: return fail("a request names a server or a group")
                val target = serverNamed(to)?.let(byName::get) ?: return fail("$to isn't connected to the network")
                if (!answers(target)) return fail("nothing on ${target.name} answers requests on \"${frame.channel}\"")
                target
            }
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
            forbidden(frame.map, Access.WRITE)?.let { return send(HubFrame.MapResult(frame.id, error = it)) }
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
            if (frame.players.isEmpty()) return fail("there's nobody to send")
            if ((frame.server == null) == (frame.group == null)) return fail("a send names a server or a group")
            val party = frame.players.distinct().map { id ->
                players[id]
                    ?: return fail(if (frame.players.size == 1) "they aren't online in the network" else "$id isn't online in the network")
            }
            fun who(player: PlayerState) = if (party.size == 1) "they" else player.name
            val size = Protocol.size(frame.data)
            if (size > limits.maxValueBytes) return fail("the data is $size bytes, over the network's limit of ${limits.maxValueBytes}")
            if (!grants.trusted) {
                party.firstOrNull { it.server != from }?.let {
                    return fail("network.forbidden: ${credentialName()} may only send players who are on $from")
                }
            }
            val server = if (frame.server != null) {
                serverNamed(frame.server) ?: return fail("the proxy has no server named \"${frame.server}\"")
            } else {
                val group = frame.group!!
                val moving = party.count { player -> members(group).none { it.name == player.server } }
                pick(group, frame.balance, moving.coerceAtLeast(1))?.name
                    ?: serverNamed(group)?.takeIf { members(group).isEmpty() && byName[it] == null }
                    ?: return fail(
                        when {
                            members(group).isEmpty() -> "the network has no group or server named \"$group\" connected"
                            members(group).none { it.accepting } -> "no instance of $group takes players now"
                            else -> "no instance of $group has room for ${if (party.size == 1) "them" else "${party.size} players"}"
                        }
                    )
            }
            val target = byName[server]
            val moving = party.filter { it.server != server }
            if (moving.isEmpty()) return fail(if (party.size == 1) "they're already on $server" else "they're all already on $server")
            for (player in moving) {
                if (!grants.maySendTo(server, target?.group ?: frame.group, cameFrom[player.id])) {
                    return fail(
                        "network.forbidden: ${credentialName()} may not send ${if (party.size == 1) "players" else player.name} to $server"
                    )
                }
            }
            for (player in moving) target?.send(HubFrame.Arriving(player.id, from, frame.data))
            incoming.merge(server, moving.size, Int::plus)
            return {
                val results = moving.map { player ->
                    val moved = try {
                        proxy.send(player.id, server)
                    } catch (e: Exception) {
                        java.util.concurrent.CompletableFuture.completedFuture(e.message ?: e.toString())
                    }
                    moved.toCompletableFuture().handle { error, failure -> failure?.let { it.message ?: it.toString() } ?: error }
                }
                java.util.concurrent.CompletableFuture.allOf(*results.toTypedArray()).whenComplete { _, _ ->
                    synchronized(lock) {
                        incoming.computeIfPresent(server) { _, count -> (count - moving.size).takeIf { it > 0 } }
                        val failed = moving.zip(results.map { it.join() }).filter { it.second != null }
                        for ((player, _) in failed) target?.send(HubFrame.ArrivalCancelled(player.id))
                        val why = when {
                            failed.isEmpty() -> null
                            party.size == 1 -> failed.single().second
                            else -> failed.joinToString("; ") { (player, error) -> "${who(player)}: $error" }
                        }
                        send(HubFrame.SendResult(frame.id, why, server))
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
