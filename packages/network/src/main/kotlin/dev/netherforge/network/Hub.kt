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

    /** The address it connects from, as an IP address: where a registering server is reached unless it says. */
    val remoteHost: String? get() = null

    fun send(frame: HubFrame)

    /** Ends the connection; the peer calls [Hub.Session.closed] once it has. */
    fun close(reason: String)

    /**
     * The hello was accepted: from now on every line both ways is sealed with [channel], the [HubFrame.Welcome]
     * included. A connection in the same JVM ([LocalLink]) has no wire to seal.
     */
    fun secure(channel: Channel) {}
}

/** Something the hub sends to and sends players for: a server's connection, or an admin client. */
interface HubEndpoint {
    fun deliver(frame: HubFrame)

    /** What it may do when it sends players. */
    val sendGrants: Grants

    /** How refusals name it. */
    fun who(): String
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
    groups: Map<String, GroupSettings> = emptyMap(),
    /** What whatever runs the servers may want to hear of (the webhook); called off the hub's lock. */
    private val events: (HubEvent) -> Unit = {}
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

    /**
     * The servers that added themselves to the proxy, by name: where it reaches them, the process that did ([instance],
     * so the same one dialling again keeps its name), and since when it's been gone (null while connected).
     */
    private val registered = LinkedHashMap<String, Registered>()

    /**
     * A server on the proxy that isn't in its config: one that registered itself, or one the admin API added
     * ([byAdmin]: it stays until the API takes it off, and only [credential], when given, may join under its name).
     */
    data class Registered(
        val name: String,
        val host: String,
        val port: Int,
        val instance: String?,
        val credential: String?,
        var goneSince: Long?,
        val byAdmin: Boolean = false,
        val group: String? = null
    )

    /**
     * The admin API's: adds [name] at [host]:[port] to the proxy, for a server that will join with [credential] (any
     * when null) as an instance of [group]. Null once it's added, or why not.
     */
    fun adminRegister(name: String, host: String, port: Int, group: String?, credential: String?): String? = synchronized(lock) {
        if (!NetworkNames.isServer(name) || name == Protocol.AUTO_NAME) return "\"$name\" isn't a server's name"
        if (group != null && !NetworkNames.isServer(group)) return "\"$group\" isn't a group's name"
        if (port !in 1..65535) return "$port isn't a port"
        if (credential != null && credential !in credentials) return "there's no credential \"$credential\""
        val existing = registered.keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let(registered::get)
        if (existing == null && serverNamed(name)?.let(proxy::isFixed) == true) return "$name is one of the proxy's own servers"
        if (existing != null && !existing.byAdmin) return "$name registered itself: it comes off the proxy as it goes"
        val kept = existing?.name ?: name
        if (existing == null || existing.host != host || existing.port != port) {
            if (existing != null) proxy.unregister(existing.name)
            proxy.register(kept, host, port)?.let { return "the proxy couldn't add it: $it" }
        }
        registered[kept] = Registered(kept, host, port, null, credential, null, byAdmin = true, group = group)
        log.info("NetherForge hub: the admin API added $kept at $host:$port" + group?.let { " in $it" }.orEmpty())
        tellServers()
        null
    }

    /** The admin API's: takes [name] (a server not in the proxy's config) off the proxy, disconnecting it. Null once done, or why not. */
    fun adminRemove(name: String): String? {
        val session = synchronized(lock) {
            val entry = registered.keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let(registered::get)
                ?: return if (serverNamed(name) != null) "$name is one of the proxy's own servers" else "there's no server named $name"
            byName[entry.name]?.let { session ->
                session.state = Lifecycle.STOPPING
                evacuate(session)
            }
            unregister(entry.name, "the admin API took it off")
            tellServers()
            byName[entry.name]
        }
        runLater()
        session?.let {
            it.peer.close("it was taken off the network")
            it.closed()
        }
        return null
    }

    /** The servers that registered themselves, for the proxy's own commands. */
    fun registered(): List<Registered> = synchronized(lock) { registered.values.map { it.copy() } }

    /** The admin API's: asks [name] to drain ([draining]) or stop draining. Null once asked, or why not. */
    fun drain(name: String, draining: Boolean): String? = synchronized(lock) {
        val session = serverNamed(name)?.let(byName::get) ?: return "$name isn't connected to the network"
        session.send(HubFrame.Drain(draining))
        null
    }

    /** What's waiting to be sent where, for the proxy's own commands and the admin API: by target, how many. */
    fun waiting(): Map<String, Int> = synchronized(lock) { queues.mapValues { it.value.size } }

    /** What waits to run once the lock is let go of: the proxy moving players, events for the webhook. */
    private val later = ArrayList<() -> Unit>()

    /** Runs what [later] has, outside the lock. Every way in that changes anything ends with it. */
    private fun runLater() {
        while (true) {
            val todo = synchronized(lock) { later.toList().also { later.clear() } }
            if (todo.isEmpty()) return
            for (action in todo) {
                try {
                    action()
                } catch (e: Exception) {
                    log.warn("NetherForge hub: ${e.message}")
                }
            }
        }
    }

    /** Under the lock: [event] for the webhook, once the lock is let go of. */
    private fun emit(event: HubEvent) {
        later += { events(event) }
    }

    /** A send waiting for room: who asked, what, until when, and why it can't go yet. */
    private class Wait(val origin: HubEndpoint, val frame: ServerFrame.Send, val from: String, val deadline: Long, var why: String) {
        var position = 0
    }

    /** Sends waiting for room, by target (a group's or server's name in lowercase), first come first served. */
    private val queues = LinkedHashMap<String, ArrayDeque<Wait>>()

    /** When the webhook last heard each target is wanted. */
    private val wanted = HashMap<String, Long>()

    /** A request on its way: who asked under which id, who must answer, and until when. */
    private class Route(val origin: Session, val originId: Long, val target: HubEndpoint, val deadline: Long)

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
    fun playerMoved(id: String, name: String, server: String) {
        synchronized(lock) { moved(id, name, server) }
        runLater()
    }

    private fun moved(id: String, name: String, server: String) {
        val previous = players[id]
        val now = PlayerState(id, name, server)
        players[id] = now
        if (previous == null) {
            cameFrom.remove(id)
            for (session in byName.values) session.presence(null, now)
            tellAdmins(AdminScope.PRESENCE_READ, AdminEvent.PlayerJoined(now))
        } else if (previous.server != server) {
            previous.server?.let { cameFrom[id] = it }
            for (session in byName.values) session.presence(previous, now)
            tellAdmins(AdminScope.PRESENCE_READ, AdminEvent.PlayerMoved(id, server, previous.server))
        }
        dispatch()
    }

    /** The proxy says player [id] left the network. */
    fun playerLeft(id: String) {
        synchronized(lock) { left(id) }
        runLater()
    }

    /** Under the lock: player [id] left the network. */
    private fun left(id: String) {
        run {
            val previous = players.remove(id) ?: return
            cameFrom.remove(id)
            for (session in byName.values) session.presence(previous, null)
            tellAdmins(AdminScope.PRESENCE_READ, AdminEvent.PlayerLeft(id))
            for (queue in queues.values) {
                for (wait in queue.filter { id in it.frame.players }) {
                    queue.remove(wait)
                    wait.origin.deliver(HubFrame.SendResult(wait.frame.id, "${previous.name} left the network while waiting"))
                }
            }
            dispatch()
        }
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
                val who = (route.target as? Session)?.name ?: route.target.who()
                route.origin.send(
                    HubFrame.Reply(route.originId, error = "$who didn't answer within ${limits.requestTimeoutMillis / 1000} seconds")
                )
            }
            for (session in sessions.filter { it.name == null && now - it.openedAt > Protocol.HELLO_MILLIS }) {
                session.peer.close("it didn't say hello in time")
            }
            for (entry in registered.values.filter { it.goneSince != null && now - it.goneSince!! >= limits.unregisterAfterMillis }) {
                unregister(entry.name, "gone for ${limits.unregisterAfterMillis / 1000} seconds")
            }
            for ((key, queue) in queues) {
                for (wait in queue.filter { it.deadline <= now }) {
                    queue.remove(wait)
                    val seconds = (wait.frame.waitMillis ?: 0) / 1000
                    wait.origin.deliver(HubFrame.SendResult(wait.frame.id, "waited $seconds seconds: ${wait.why}"))
                }
                if (queue.isNotEmpty() && now - (wanted[key] ?: 0) >= WANTED_EVERY_MILLIS) {
                    wanted[key] = now
                    emit(HubEvent.GroupWanted(queue.first().frame.group ?: queue.first().frame.server ?: key, queue.size))
                }
            }
            for (entry in pending.values.filter { it.deadline <= now }) expire(entry)
            dispatch()
            tellServers()
            if (now - lastSave >= SAVE_EVERY_MILLIS && maps.takeDirty()) {
                lastSave = now
                maps.snapshot()
            } else {
                null
            }
        }
        if (save != null) save(save)
        runLater()
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
        return ServerState(
            name,
            true,
            session.group,
            session.capacity,
            session.accepting,
            players,
            session.state,
            session.idle,
            session.startupMillis
        )
    }

    /** Under the lock: takes a server that registered itself off the proxy. */
    private fun unregister(name: String, why: String) {
        registered.remove(name) ?: return
        log.info("NetherForge hub: $name is off the proxy's servers: $why")
        try {
            proxy.unregister(name)
        } catch (e: Exception) {
            log.warn("NetherForge hub: the proxy couldn't take $name off its servers: ${e.message}")
        }
    }

    /** Under the lock: `<group>-<n>`, the first not taken (the same [instance] gets back the name it had). */
    private fun autoName(group: String, instance: String?): String {
        registered.values.firstOrNull {
            instance != null && it.instance == instance && it.name.startsWith("$group-")
        }?.let { return it.name }
        val taken = proxy.servers().map { it.lowercase() }.toSet() + registered.keys.map { it.lowercase() }
        return generateSequence(1) { it + 1 }.map { "$group-$it" }.first { it.lowercase() !in taken }
    }

    /** Under the lock: what changed of the servers since everyone was last told, to everyone. */
    private fun tellServers() {
        val now = servers().associateBy { it.name }
        val changed = now.values.filter { told[it.name] != it }
        told = now
        if (changed.isNotEmpty()) broadcast(HubFrame.Servers(changed))
        for (state in changed) tellAdmins(AdminScope.SERVERS_READ, AdminEvent.Server(state))
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
            session.open && session.capacity.let { it == null || occupancy(session, counts) + size <= it }
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
    inner class Session internal constructor(val peer: Peer) : HubEndpoint {
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

        /** It said it's shutting down: a server that registered itself comes off the proxy as it goes. */
        internal var leaving = false

        /** A proxy's connection (the hub running on its own), not a server's. */
        internal var proxySession = false

        /** Where it is in its life, whether it's idle, and how long it took to be ready, as it last said. */
        internal var state = Lifecycle.READY
        internal var idle = false
        internal var startupMillis: Long? = null

        /** Whether its group may send it new players. */
        internal val open get() = state == Lifecycle.READY && accepting

        internal var channels: Set<String> = emptySet()
        internal var maps: Set<String> = emptySet()
        private var closed = false

        internal fun send(frame: HubFrame) {
            if (!closed) peer.send(frame)
        }

        override fun deliver(frame: HubFrame) = send(frame)

        override val sendGrants get() = grants

        override fun who() = credentialName()

        /** A frame from this connection, on its reader's thread. */
        fun receive(frame: ServerFrame) {
            synchronized(lock) {
                if (closed) return
                if (name == null) hello(frame) else handle(frame)
            }
            runLater()
        }

        /** The connection ended (it closed, or the peer closed it): what waited on it is told. */
        fun closed() {
            synchronized(lock) { close() }
            runLater()
        }

        internal fun close() {
            if (closed) return
            closed = true
            if (proxySession) {
                proxies -= this
                (proxy as? RemoteProxy)?.gone()
                log.info("NetherForge hub: the proxy $name disconnected")
                tellServers()
            }
            for (queue in queues.values) queue.removeAll { it.origin === this }
            sessions -= this
            val name = name
            if (name != null && byName[name] === this) {
                byName.remove(name)
                log.info("NetherForge hub: $name disconnected")
                emit(HubEvent.ServerGone(name, group))
                val state = state(name, players.values.count { it.server == name })
                broadcast(HubFrame.Server(state))
                told = told + (name to state)
                registered[name]?.takeIf { !it.byAdmin }?.let { entry ->
                    if (leaving) unregister(name, "it shut down") else entry.goneSince = clock()
                }
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
            if (frame.role == Grants.PROXY_ROLE) return proxyHello(frame, credential, proven)
            val byAdmin = registered.values.firstOrNull { it.byAdmin && it.name.equals(frame.server, ignoreCase = true) }
            if (byAdmin?.credential != null && byAdmin.credential != credential?.id) {
                return refuse("${byAdmin.name} was added for the credential \"${byAdmin.credential}\", and it joins with another")
            }
            if (byAdmin != null &&
                frame.register != null
            ) {
                return refuse("${byAdmin.name} was added by the admin API: it doesn't register itself")
            }
            val registering = frame.register
            var said = frame.server
            if (registering != null) {
                val rule = if (credential == null) {
                    RegisterRule.ANY
                } else {
                    credential.register
                        ?: return refuse(
                            "its credential \"${credential.id}\" may not register servers (register=true in credentials.properties)"
                        )
                }
                val name = if (frame.server == Protocol.AUTO_NAME) {
                    autoName(group ?: return refuse("an automatic name needs a group (network.group)"), frame.instance)
                } else {
                    frame.server
                }
                if (!rule.allowsName(
                        name
                    )
                ) {
                    return refuse("its credential may not register the name $name (only ${rule.names.joinToString()})")
                }
                val host = registering.host ?: peer.remoteHost ?: return refuse("it didn't say its address, and the hub can't tell it")
                if (registering.port !in 1..65535) return refuse("its port ${registering.port} isn't a port")
                if (!rule.allowsAddress(
                        host
                    )
                ) {
                    return refuse("its credential may not register the address $host (only ${rule.addresses.joinToString()})")
                }
                val existing = registered.keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let(registered::get)
                if (existing == null && serverNamed(name) != null && proxy.isFixed(serverNamed(name)!!)) {
                    return refuse("$name is one of the proxy's own servers (velocity.toml): it can't be registered over")
                }
                if (existing != null &&
                    existing.goneSince == null &&
                    byName[existing.name] != null &&
                    existing.instance != frame.instance
                ) {
                    return refuse("another server registered as $name is connected: pick another name, or stop that one")
                }
                if (existing == null || existing.host != host || existing.port != registering.port) {
                    if (existing != null) {
                        try {
                            proxy.unregister(existing.name)
                        } catch (_: Exception) {
                        }
                    }
                    proxy.register(name, host, registering.port)?.let { why ->
                        registered.remove(existing?.name)
                        return refuse("the proxy couldn't add it: $why")
                    }
                    log.info("NetherForge hub: $name added itself to the proxy's servers at $host:${registering.port}")
                }
                said = existing?.name ?: name
                registered[said] = Registered(said, host, registering.port, frame.instance, credential?.id, null)
            }
            val server = serverNamed(said)
                ?: return refuse(
                    "the proxy has no server named \"$said\" (network.server-name in its config.yml must be the " +
                        "server's name in the proxy's config, or the server must register itself; it has ${proxy.servers().joinToString()})"
                )
            byName[server]?.let { previous ->
                previous.peer.close("$server connected again")
                previous.closed()
            }
            name = server
            this.credential = credential?.id
            joinedWith = credential
            grants = credential?.grants ?: Grants.TRUSTED
            this.group = group ?: byAdmin?.group
            capacity = frame.capacity
            accepting = true
            state = frame.state
            byName[server] = this
            val how = if (credential == null) "the shared secret" else "credential ${credential.id} (${grants.describe()})"
            log.info("NetherForge hub: $server connected from ${peer.address} with $how")
            // Sealed from the welcome on, under the name the server said (the one its keys are bound to).
            peer.secure(Channel.hub(proven, nonce, frame.nonce, frame.server, frame.credential))
            send(HubFrame.Welcome(server, servers(), visible(this), limits.maxValueBytes, grants))
            val state = state(server, players.values.count { it.server == server })
            for (other in byName.values) if (other !== this) other.send(HubFrame.Server(state))
            told = told + (server to state)
            dispatch()
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

        internal fun credentialName() = "credential \"$credential\" (${grants.role})"

        /** A proxy joining a hub that runs on its own: the hub's one proxy from now on (a new one replaces it). */
        private fun proxyHello(frame: ServerFrame.Hello, credential: Credential?, proven: String) {
            val remote = proxy as? RemoteProxy ?: return refuse("this hub runs inside a proxy: it takes no other proxy")
            if (credential != null && credential.grants.role != Grants.PROXY_ROLE) {
                return refuse("its credential \"${credential.id}\" isn't a proxy's (role=proxy in credentials.properties)")
            }
            proxies.forEach { previous ->
                previous.peer.close("another proxy connected")
                previous.close()
            }
            name = frame.server
            this.credential = credential?.id
            joinedWith = credential
            grants = Grants.TRUSTED
            proxySession = true
            proxies += this
            remote.link = { frame -> send(frame) }
            log.info("NetherForge hub: the proxy ${frame.server} connected from ${peer.address}")
            peer.secure(Channel.hub(proven, nonce, frame.nonce, frame.server, frame.credential))
            send(HubFrame.Welcome(frame.server, servers(), players.values.toList(), limits.maxValueBytes, grants))
        }

        /** Under the lock: what a proxy says, and asks. */
        private fun proxyFrame(frame: ServerFrame) {
            val remote = proxy as RemoteProxy
            when (frame) {
                is ServerFrame.Ping -> send(HubFrame.Pong)
                is ServerFrame.ProxyServers -> {
                    remote.servers(frame.servers, frame.fixed)
                    tellServers()
                    dispatch()
                }
                is ServerFrame.ProxyPlayer -> frame.player.server?.let { moved(frame.player.id, frame.player.name, it) }
                is ServerFrame.ProxyPresence -> {
                    val online = frame.players.associateBy { it.id }
                    for (id in players.keys.filter { it !in online }) left(id)
                    for (player in frame.players) player.server?.let { moved(player.id, player.name, it) }
                }
                is ServerFrame.ProxyPlayerLeft -> left(frame.id)
                is ServerFrame.ProxyResult -> later += { remote.result(frame.id, frame.error) }
                is ServerFrame.ProxyLogin -> {
                    val placed = placeLogin(frame.player, frame.name)
                    later += { placed.thenAccept { place -> send(HubFrame.LoginPlaced(frame.id, place.server, place.deny)) } }
                }
                else -> send(HubFrame.Problem("a proxy's connection says only what a proxy says"))
            }
        }

        private fun refuse(why: String) {
            log.warn("NetherForge hub: refused a connection from ${peer.address}: $why")
            peer.send(HubFrame.Refused(why))
            peer.close(why)
        }

        /** Under the lock; what must wait for the lock to be let go of goes in [later]. */
        private fun handle(frame: ServerFrame) {
            if (proxySession) return proxyFrame(frame)
            val from = name!!
            when (frame) {
                is ServerFrame.Hello -> send(HubFrame.Problem("it already said hello"))
                is ServerFrame.Ping -> send(HubFrame.Pong)
                is ServerFrame.Status -> status(frame)
                is ServerFrame.Evacuate -> {
                    state = Lifecycle.STOPPING
                    evacuate(this)
                }
                is ServerFrame.Leaving -> leaving = true
                is ServerFrame.Listen -> listen(frame)
                is ServerFrame.Publish -> {
                    if (!checked(frame.channel, frame.value)) return
                    forbidden(frame.channel, Access.WRITE)?.let {
                        send(HubFrame.Problem(it))
                        return
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
                    if (frame.to == null && frame.group == null) heard(message)
                }
                is ServerFrame.Request -> request(frame, from)
                is ServerFrame.Reply -> {
                    val route = routes[frame.id]?.takeIf { it.target === this } ?: return
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
                is ServerFrame.Send -> sendPlayer(this, frame, from)
                is ServerFrame.ProxyServers, is ServerFrame.ProxyPlayer, is ServerFrame.ProxyPresence, is ServerFrame.ProxyPlayerLeft,
                is ServerFrame.ProxyResult, is ServerFrame.ProxyLogin -> send(HubFrame.Problem("only a proxy says that"))
            }
        }

        /** What it says of itself now: the webhook hears it become ready and idle, and waiting sends may go. */
        private fun status(frame: ServerFrame.Status) {
            val wasReady = state == Lifecycle.READY
            val wasIdle = idle
            accepting = frame.accepting
            state = frame.state
            idle = frame.idle
            startupMillis = frame.startupMillis ?: startupMillis
            if (state == Lifecycle.READY && !wasReady) emit(HubEvent.ServerReady(name!!, group, startupMillis))
            if (idle && !wasIdle) emit(HubEvent.ServerIdle(name!!, group))
            dispatch()
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
            val target: HubEndpoint = if (frame.admin != null) {
                streams.lastOrNull { it.client == frame.admin && AdminScope.CHANNELS_READ in it.scopes }
                    ?: return fail("the admin client ${frame.admin} isn't connected to the hub")
            } else if (frame.group != null) {
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
            target.deliver(HubFrame.Requested(id, frame.channel, from, frame.value))
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

    // ---- admin clients -------------------------------------------------------------

    /** The proxy connected to a hub that runs on its own (one, for now). */
    private val proxies = ArrayList<Session>()

    /** The admin clients' streams, oldest first. */
    private val streams = ArrayList<AdminStream>()

    /** Placement hooks: by group (lowercase; [LOGINS] for players logging in), the streams that place, first active. */
    private val hooks = HashMap<String, MutableList<AdminStream>>()

    /** Placements an admin client was asked for and hasn't settled. */
    private class Pending(
        val id: Long,
        var asked: AdminStream,
        val origin: HubEndpoint?,
        val frame: ServerFrame.Send,
        val from: String?,
        /** A login's: completed with where they go. */
        val login: java.util.concurrent.CompletableFuture<LoginPlace>?,
        var deadline: Long,
        var held: Boolean = false
    )

    private val pending = LinkedHashMap<Long, Pending>()
    private var nextPlace = 1L

    /**
     * An admin client's stream: what it hears, queued for its connection to take ([take]). Past [MAX_ADMIN_QUEUED]
     * waiting, it's too slow and is closed. It may answer requests addressed to its [client] and place players.
     */
    inner class AdminStream internal constructor(val client: String, val scopes: Set<AdminScope>) : HubEndpoint {
        private val events = java.util.concurrent.LinkedBlockingQueue<AdminEvent>(MAX_ADMIN_QUEUED)

        @Volatile
        var closed = false
            private set
        internal var channels: Set<String> = emptySet()

        internal fun push(event: AdminEvent) {
            if (!closed && !events.offer(event)) {
                log.warn("NetherForge hub: the admin client $client isn't reading its stream; closing it")
                close()
            }
        }

        override fun deliver(frame: HubFrame) {
            if (frame is HubFrame.Requested) push(AdminEvent.Request(frame.id, frame.channel, frame.from, frame.value))
        }

        override val sendGrants get() = Grants.TRUSTED

        override fun who() = "the admin client $client"

        /** The next event, waiting up to [millis]; null when there's none yet. */
        fun take(millis: Long): AdminEvent? = events.poll(millis, java.util.concurrent.TimeUnit.MILLISECONDS)

        /** Ends the stream: what it was asked and hasn't answered goes to the next hook, or the group's own balancing. */
        fun close() {
            synchronized(lock) {
                if (closed) return@synchronized
                closed = true
                streams.remove(this)
                for (list in hooks.values) list.remove(this)
                hooks.values.removeAll { it.isEmpty() }
                for ((id, route) in routes.entries.filter { it.value.target === this }) {
                    routes.remove(id)
                    route.origin.send(HubFrame.Reply(route.originId, error = "the admin client $client went before answering"))
                }
                for (entry in pending.values.filter { it.asked === this }) expire(entry)
            }
            runLater()
        }
    }

    /**
     * Opens a stream for admin client [client]: presence and servers as its scopes allow, messages on [channels], and
     * players to place for [place] (groups; [LOGINS] for logins).
     */
    fun attach(client: String, scopes: Set<AdminScope>, channels: Set<String> = emptySet(), place: Set<String> = emptySet()): AdminStream =
        synchronized(lock) {
            val stream = AdminStream(client, scopes)
            if (AdminScope.CHANNELS_READ in scopes) stream.channels = channels.filter(NetworkNames::isFull).toSet()
            if (AdminScope.PLACEMENT in scopes) {
                for (group in place) hooks.getOrPut(group.lowercase()) { ArrayList() } += stream
            }
            streams += stream
            log.info(
                "NetherForge hub: the admin client $client is listening" +
                    if (place.isEmpty()) "" else ", placing for ${place.joinToString()}"
            )
            stream
        }

    /** Closes every stream of admin client [client]: it was revoked. */
    fun detach(client: String) {
        val open = synchronized(lock) { streams.filter { it.client == client } }
        for (stream in open) stream.close()
    }

    /** Under the lock: [event] to each stream allowed [scope]. */
    private fun tellAdmins(scope: AdminScope, event: AdminEvent) {
        for (stream in streams) if (scope in stream.scopes) stream.push(event)
    }

    /** Under the lock: a message published to every server, to the admin streams listening to its channel. */
    private fun heard(message: HubFrame.Message) {
        for (stream in streams) {
            if (message.channel in
                stream.channels
            ) {
                stream.push(AdminEvent.Message(message.channel, message.from, message.value))
            }
        }
    }

    /** Under the lock: the stream that places players for [key], if any is connected. */
    private fun hookFor(key: String): AdminStream? = hooks[key]?.firstOrNull { !it.closed }

    /** Under the lock: [entry] wasn't settled in time (or its client went): the next hook, or the hub decides. */
    private fun expire(entry: Pending) {
        if (entry.held) {
            pending.remove(entry.id)
            entry.login?.complete(LoginPlace(deny = "no server was found in time"))
            entry.origin?.deliver(HubFrame.SendResult(entry.frame.id, "held, and no server was found in time"))
            return
        }
        val key = entry.frame.group?.lowercase() ?: LOGINS
        val next = hooks[key]?.firstOrNull { !it.closed && it !== entry.asked }
        // A client that went has its asks passed to the next; one that didn't answer in time, to the hub.
        if (next != null && entry.asked.closed) {
            entry.asked = next
            entry.deadline = clock() + limits.placementTimeoutMillis
            next.push(
                AdminEvent.Place(
                    entry.id,
                    entry.frame.players.mapNotNull { players[it] },
                    entry.frame.group,
                    entry.from,
                    entry.login != null
                )
            )
            return
        }
        pending.remove(entry.id)
        if (entry.login != null) entry.login.complete(LoginPlace()) else sendPlayer(entry.origin!!, entry.frame, entry.from!!, asked = true)
    }

    /** The admin API's: every player online, and where. */
    fun adminPlayers(): List<PlayerState> = synchronized(lock) { players.values.toList() }

    /** The admin API's: a player by UUID or name (any case). */
    fun findPlayer(player: String): PlayerState? = synchronized(lock) {
        players[player] ?: players.values.firstOrNull { it.name.equals(player, ignoreCase = true) }
    }

    /** How an admin client's send went: the server they're on their way to, or why not. */
    data class SendOutcome(val server: String? = null, val error: String? = null)

    /** The admin API's: sends [players] to [server] or an instance of [group], as a server's send does (it may wait). */
    fun adminSend(
        client: String,
        players: List<String>,
        server: String?,
        group: String?,
        balance: Balance?,
        data: JsonElement?,
        waitMillis: Long?
    ): java.util.concurrent.CompletableFuture<SendOutcome> {
        val outcome = java.util.concurrent.CompletableFuture<SendOutcome>()
        val origin = object : HubEndpoint {
            override fun deliver(frame: HubFrame) {
                if (frame is HubFrame.SendResult) outcome.complete(SendOutcome(frame.server.takeIf { frame.error == null }, frame.error))
            }

            override val sendGrants get() = Grants.TRUSTED

            override fun who() = "the admin client $client"
        }
        synchronized(lock) {
            sendPlayer(origin, ServerFrame.Send(nextPlace++, players, server, data, group, balance, waitMillis), "admin:$client")
        }
        runLater()
        return outcome
    }

    /** The admin API's: shows a player a chat message, title or action bar through the proxy. Null once shown, or why not. */
    fun adminMessage(player: String, kind: String, text: String, subtitle: String?): String? {
        val id = findPlayer(player)?.id ?: return "nobody called $player is online"
        return proxy.message(id, kind, text, subtitle)
    }

    fun adminKick(player: String, message: String): String? {
        val id = findPlayer(player)?.id ?: return "nobody called $player is online"
        return proxy.kick(id, message)
    }

    /** The admin API's: publishes on [channel], to [server], [group]'s instances, or every server listening. */
    fun adminPublish(client: String, channel: String, value: JsonElement, server: String?, group: String?): String? = synchronized(lock) {
        if (!NetworkNames.isFull(channel)) return "\"$channel\" isn't a channel's full name (namespace:name)"
        val size = Protocol.size(value)
        if (size > limits.maxValueBytes) return "the value is $size bytes, over the network's limit of ${limits.maxValueBytes}"
        val targets = when {
            server != null -> listOfNotNull(serverNamed(server)?.let(byName::get))
            group != null -> members(group)
            else -> byName.values.toList()
        }
        val message = HubFrame.Message(channel, "admin:$client", value)
        for (target in targets) if (channel in target.channels && target.grants.allows(channel, Access.READ)) target.send(message)
        if (server == null && group == null) heard(message)
        null
    }

    /** The admin API's: [stream]'s answer to request [id]. Null once passed on, or why not. */
    fun adminReply(stream: String, id: Long, value: JsonElement?, error: String?): String? = synchronized(lock) {
        val route =
            routes[id]?.takeIf { (it.target as? AdminStream)?.client == stream } ?: return "there's no request $id waiting for $stream"
        routes.remove(id)
        route.origin.send(HubFrame.Reply(route.originId, value, error))
        null
    }

    /** The admin API's: [client]'s answer to placement [id]. Null once acted on, or why not. */
    fun adminPlace(client: String, id: Long, answer: PlaceAnswer): String? {
        val why = synchronized(lock) {
            val entry = pending[id]?.takeIf { it.asked.client == client } ?: return "there's no placement $id waiting for $client"
            when {
                answer.server != null -> {
                    val server = serverNamed(answer.server) ?: return "the proxy has no server named ${answer.server}"
                    pending.remove(id)
                    if (entry.login != null) {
                        entry.login.complete(LoginPlace(server))
                    } else {
                        sendPlayer(entry.origin!!, entry.frame.copy(server = server, group = null), entry.from!!, asked = true)
                    }
                    null
                }
                answer.fail != null -> {
                    pending.remove(id)
                    entry.login?.complete(LoginPlace(deny = answer.fail))
                    entry.origin?.deliver(HubFrame.SendResult(entry.frame.id, answer.fail))
                    null
                }
                answer.hold != null -> {
                    if (!entry.held) {
                        entry.held = true
                        val most = if (entry.login != null) LOGIN_HOLD_MILLIS else entry.frame.waitMillis ?: DEFAULT_HOLD_MILLIS
                        entry.deadline = clock() + most.coerceAtMost(MAX_WAIT_MILLIS)
                    }
                    entry.origin?.deliver(HubFrame.SendWaiting(entry.frame.id, 0, answer.hold))
                    null
                }
                else -> "an answer names a server, a hold or a fail"
            }
        }
        runLater()
        return why
    }

    /**
     * Where player [id] ([name]) logging in goes, as the admin client placing logins says (null: the proxy's own
     * choice), within the placement timeout, or longer if it holds them.
     */
    fun placeLogin(id: String, name: String): java.util.concurrent.CompletableFuture<LoginPlace> = synchronized(lock) {
        val hook = hookFor(LOGINS) ?: return java.util.concurrent.CompletableFuture.completedFuture(LoginPlace())
        val future = java.util.concurrent.CompletableFuture<LoginPlace>()
        val place = nextPlace++
        pending[place] = Pending(place, hook, null, ServerFrame.Send(-1, listOf(id)), null, future, clock() + limits.placementTimeoutMillis)
        hook.push(AdminEvent.Place(place, listOf(PlayerState(id, name)), login = true))
        future
    }

    // ---- sending players ----------------------------------------------------------

    /** Where a send can go now: nowhere ([Failed]), not yet ([Later]), or to [server] ([Go]). */
    private sealed interface Placement {
        data class Failed(val why: String) : Placement

        /** It may go once [target] (a group's or server's name) has room. */
        data class Later(val target: String, val why: String) : Placement

        class Go(val server: String, val target: Session?, val party: List<PlayerState>, val moving: List<PlayerState>) : Placement
    }

    /** Under the lock: where [origin]'s [frame] can go now, held to its grants unless the hub itself is sending ([byHub]). */
    private fun place(origin: HubEndpoint, frame: ServerFrame.Send, from: String, byHub: Boolean = false): Placement {
        if (frame.players.isEmpty()) return Placement.Failed("there's nobody to send")
        if ((frame.server == null) == (frame.group == null)) return Placement.Failed("a send names a server or a group")
        val party = frame.players.distinct().map { id ->
            players[id] ?: return Placement.Failed(
                if (frame.players.size == 1) "they aren't online in the network" else "$id isn't online in the network"
            )
        }
        val size = Protocol.size(frame.data)
        if (size >
            limits.maxValueBytes
        ) {
            return Placement.Failed("the data is $size bytes, over the network's limit of ${limits.maxValueBytes}")
        }
        val grants = if (byHub) Grants.TRUSTED else origin.sendGrants
        if (!grants.trusted) {
            party.firstOrNull { it.server != from }?.let {
                return Placement.Failed("network.forbidden: ${origin.who()} may only send players who are on $from")
            }
        }
        val server = if (frame.server != null) {
            val named =
                serverNamed(frame.server)
                    ?: return Placement.Later(frame.server.lowercase(), "the proxy has no server named \"${frame.server}\"")
            val session = byName[named]
            when (session?.state) {
                Lifecycle.DRAINING, Lifecycle.STOPPING -> return Placement.Failed(
                    "$named is ${session.state.word}: it takes no new players"
                )
                Lifecycle.STARTING -> return Placement.Later(named.lowercase(), "$named is starting")
                else -> named
            }
        } else {
            val group = frame.group!!
            val moving = party.count { player -> members(group).none { it.name == player.server } }
            pick(group, frame.balance, moving.coerceAtLeast(1))?.name
                ?: serverNamed(group)?.takeIf { members(group).isEmpty() && byName[it] == null }
                ?: return Placement.Later(
                    group.lowercase(),
                    when {
                        members(group).isEmpty() -> "the network has no group or server named \"$group\" connected"
                        members(group).none { it.state == Lifecycle.READY } -> "no instance of $group is ready"
                        members(group).none { it.open } -> "no instance of $group takes players now"
                        else -> "no instance of $group has room for ${if (party.size == 1) "them" else "${party.size} players"}"
                    }
                )
        }
        val target = byName[server]
        val moving = party.filter { it.server != server }
        if (moving.isEmpty()) {
            return Placement.Failed(if (party.size == 1) "they're already on $server" else "they're all already on $server")
        }
        for (player in moving) {
            if (!grants.maySendTo(server, target?.group ?: frame.group, cameFrom[player.id])) {
                return Placement.Failed(
                    "network.forbidden: ${origin.who()} may not send ${if (party.size == 1) "players" else player.name} to $server"
                )
            }
        }
        return Placement.Go(server, target, party, moving)
    }

    /**
     * Under the lock: [origin]'s [frame], sent now, waiting, or refused; to a group an admin client places players
     * for, that client is asked first (unless it has been: [asked]).
     */
    private fun sendPlayer(origin: HubEndpoint, frame: ServerFrame.Send, from: String, asked: Boolean = false) {
        val hook = if (asked || frame.group == null) null else hookFor(frame.group.lowercase())
        if (hook != null) {
            val party = frame.players.mapNotNull { players[it] }
            if (party.size == frame.players.size) {
                val id = nextPlace++
                pending[id] = Pending(id, hook, origin, frame, from, null, clock() + limits.placementTimeoutMillis)
                hook.push(AdminEvent.Place(id, party, frame.group, from))
                return
            }
        }
        when (val placement = place(origin, frame, from)) {
            is Placement.Failed -> origin.deliver(HubFrame.SendResult(frame.id, placement.why))
            is Placement.Go -> go(origin, frame, from, placement)
            is Placement.Later -> {
                val wait = frame.waitMillis?.coerceAtMost(MAX_WAIT_MILLIS)
                if (wait == null || wait <= 0) return origin.deliver(HubFrame.SendResult(frame.id, placement.why))
                val queue = queues.getOrPut(placement.target) { ArrayDeque() }
                val cap = groups[placement.target]?.maxWaiting ?: GroupSettings.DEFAULT_MAX_WAITING
                if (queue.size >=
                    cap
                ) {
                    return origin.deliver(
                        HubFrame.SendResult(frame.id, "$cap sends already wait for ${placement.target}: ${placement.why}")
                    )
                }
                queue.addLast(Wait(origin, frame, from, clock() + wait, placement.why))
                if (queue.size == 1) {
                    wanted[placement.target] = clock()
                    emit(HubEvent.GroupWanted(frame.group ?: frame.server ?: placement.target, 1))
                }
                positions()
            }
        }
    }

    /** Under the lock: moves [placement]'s players, telling [origin] how it went once the proxy has (after the lock). */
    private fun go(origin: HubEndpoint, frame: ServerFrame.Send, from: String, placement: Placement.Go) {
        val server = placement.server
        val target = placement.target
        val party = placement.party
        val moving = placement.moving
        for (player in moving) target?.send(HubFrame.Arriving(player.id, from, frame.data))
        incoming.merge(server, moving.size, Int::plus)
        later += {
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
                        else -> failed.joinToString("; ") { (player, error) -> "${player.name}: $error" }
                    }
                    if (frame.id >= 0) origin.deliver(HubFrame.SendResult(frame.id, why, server))
                    dispatch()
                }
                runLater()
            }
        }
    }

    /** Under the lock: what waits and can go now goes, in order; a target's first that can't yet holds the rest back. */
    private fun dispatch() {
        if (queues.isEmpty()) return
        var changed = false
        for (queue in queues.values) {
            while (queue.isNotEmpty()) {
                val wait = queue.first()
                when (val placement = place(wait.origin, wait.frame, wait.from)) {
                    is Placement.Later -> {
                        wait.why = placement.why
                        break
                    }
                    is Placement.Failed -> wait.origin.deliver(HubFrame.SendResult(wait.frame.id, placement.why))
                    is Placement.Go -> go(wait.origin, wait.frame, wait.from, placement)
                }
                queue.removeFirst()
                changed = true
            }
        }
        queues.entries.removeAll { it.value.isEmpty() }
        if (changed) positions()
    }

    /** Under the lock: each waiting send told its place, when it changed. */
    private fun positions() {
        for (queue in queues.values) {
            queue.forEachIndexed { index, wait ->
                if (wait.position != index + 1) {
                    wait.position = index + 1
                    wait.origin.deliver(HubFrame.SendWaiting(wait.frame.id, wait.position))
                }
            }
        }
    }

    /** Under the lock: every player on [session]'s server to its group's fallback, each to an instance with room. */
    private fun evacuate(session: Session) {
        val name = session.name ?: return
        val group = session.group ?: name
        val fallback = groups[group.lowercase()]?.fallback ?: GroupSettings.DEFAULT_FALLBACK
        val on = players.values.filter { it.server == name }
        if (on.isEmpty()) return
        log.info("NetherForge hub: sending ${on.size} players from $name to $fallback")
        for (player in on) {
            val frame = ServerFrame.Send(-1, listOf(player.id), group = fallback)
            when (val placement = place(session, frame, name, byHub = true)) {
                is Placement.Go -> go(session, frame, name, placement)
                is Placement.Failed -> log.warn("NetherForge hub: couldn't send ${player.name} from $name to $fallback: ${placement.why}")
                is Placement.Later -> log.warn("NetherForge hub: couldn't send ${player.name} from $name to $fallback: ${placement.why}")
            }
        }
    }

    companion object {
        private const val SAVE_EVERY_MILLIS = 5_000L

        /** How long a send may wait for room, at most. */
        const val MAX_WAIT_MILLIS = 10 * 60_000L

        /** The placement hook for players logging in, as [attach] takes it. */
        const val LOGINS = "*login"

        /** What may wait for an admin stream before it's too slow and is closed. */
        const val MAX_ADMIN_QUEUED = 10_000

        /** How long a hold may keep a player logging in waiting (the proxy keeps them on the loading screen). */
        const val LOGIN_HOLD_MILLIS = 30_000L

        /** How long a hold lasts when the send didn't say how long it may wait. */
        const val DEFAULT_HOLD_MILLIS = 60_000L

        /** How often the webhook hears a target is still wanted while sends wait for it. */
        private const val WANTED_EVERY_MILLIS = 30_000L
    }
}
