package dev.netherforge.proxy

import com.google.inject.Inject
import com.velocitypowered.api.command.SimpleCommand
import com.velocitypowered.api.event.EventTask
import com.velocitypowered.api.event.PostOrder
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.player.KickedFromServerEvent
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent
import com.velocitypowered.api.event.player.ServerConnectedEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ConnectionRequestBuilder
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.player.ResourcePackInfo
import com.velocitypowered.api.proxy.server.ServerInfo
import dev.netherforge.network.AdminClient
import dev.netherforge.network.AdminClientsFile
import dev.netherforge.network.AdminScope
import dev.netherforge.network.Auth
import dev.netherforge.network.CredentialEntry
import dev.netherforge.network.CredentialsFile
import dev.netherforge.network.FileMapStore
import dev.netherforge.network.Grants
import dev.netherforge.network.Hub
import dev.netherforge.network.HubAdmin
import dev.netherforge.network.HubClient
import dev.netherforge.network.HubEvents
import dev.netherforge.network.HubFrame
import dev.netherforge.network.HubListener
import dev.netherforge.network.HubLog
import dev.netherforge.network.HubProxy
import dev.netherforge.network.HubServer
import dev.netherforge.network.HubSettings
import dev.netherforge.network.NetworkNames
import dev.netherforge.network.PlayerState
import dev.netherforge.network.RichText
import dev.netherforge.network.ServerFrame
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.kyori.adventure.title.Title
import org.slf4j.Logger
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * NetherForge on a Velocity proxy: the network's [Hub], listening where `hub.properties` says, told by the proxy
 * who's online where and asking it to move players. Every NetherForge server of the network connects to it with
 * the secret from the same file. `/nfhub` (permission `netherforge.admin`) says what's connected.
 */
class NetherForgeProxy @Inject constructor(
    private val proxy: ProxyServer,
    private val logger: Logger,
    @DataDirectory private val folder: Path
) {
    private var hub: Hub? = null
    private var server: HubServer? = null
    private var admin: HubAdmin? = null
    private var metrics: com.sun.net.httpserver.HttpServer? = null

    /** The proxy's own numbers (logins, moves, its link to a remote hub), served with the hub's when it runs one. */
    private var counts = ProxyMetrics("proxy")
    private var clearResourcePacks = true
    private var settings: HubSettings? = null
    private val credentialsFile by lazy { CredentialsFile(folder.resolve(CredentialsFile.FILE)) }
    private val adminFile by lazy { AdminClientsFile(folder.resolve(AdminClientsFile.FILE)) }

    @Volatile
    private var adminClients: List<AdminClient> = emptyList()

    /** The hub running on its own this proxy joined (`remote.hub`), instead of running one. */
    private var remote: HubClient? = null
    private var remoteSettings: HubSettings.Remote? = null
    private val logins = java.util.concurrent.ConcurrentHashMap<Long, CompletableFuture<HubFrame.LoginPlaced>>()
    private val loginIds = java.util.concurrent.atomic.AtomicLong(1)

    /**
     * Completes once the hub has sent this proxy every server added at run time ([HubFrame.ProxySync]) and it has
     * them: logins wait for it (at most [SYNC_WAIT_SECONDS]), so none lands on a proxy missing half the network. A new
     * one each time the link drops, since the hub may have added servers while it was away.
     */
    @Volatile
    private var synced = CompletableFuture<Unit>()

    /** Whether it ever had the hub's servers: until then logins wait even while the hub can't be reached. */
    @Volatile
    private var everSynced = false

    /** The hub asked this proxy to take no new logins (it's about to restart). */
    @Volatile
    private var draining = false

    @Subscribe
    fun onInitialize(event: ProxyInitializeEvent) {
        val settings = try {
            HubSettings.read(folder.resolve(HubSettings.FILE)) { logger.info(it) }
        } catch (e: Exception) {
            logger.error("NetherForge: couldn't read ${folder.resolve(HubSettings.FILE)}; the network's hub isn't running", e)
            return
        }
        val log = object : HubLog {
            override fun info(message: String) = logger.info(message)

            override fun warn(message: String) = logger.warn(message)
        }
        clearResourcePacks = settings.clearResourcePacks
        counts = ProxyMetrics(settings.remote?.name ?: "proxy")
        serveMetrics(settings)
        settings.remote?.let { joining ->
            joinRemote(joining)
            proxy.commandManager.register(proxy.commandManager.metaBuilder("nfhub").plugin(this).build(), StatusCommand())
            return
        }
        val credentials = try {
            credentialsFile.read().map { it.credential(settings.roleShares) }
        } catch (e: Exception) {
            logger.error("NetherForge: couldn't read ${credentialsFile.file}; the network's hub isn't running", e)
            return
        }
        val hub = Hub(
            settings.secret,
            VelocityProxy(),
            log,
            settings.limits,
            FileMapStore(folder.resolve("maps.json")),
            credentials = credentials,
            groups = settings.groups,
            fallback = settings.fallback,
            policies = settings.rolePolicies,
            keepsPacks = !settings.clearResourcePacks,
            audit = AuditLog(folder.resolve("audit.log"))::append,
            events = settings.webhookUrl?.let { url -> HubEvents(url, settings.webhookSecret, log)::send } ?: {}
        )
        val server = HubServer(hub, InetSocketAddress(settings.bind, settings.port), log, settings.limits)
        try {
            server.start()
        } catch (e: Exception) {
            logger.error("NetherForge: the network's hub couldn't listen on ${settings.bind}:${settings.port}", e)
            return
        }
        val adminPort = settings.adminPort
        if (adminPort != null) {
            try {
                adminClients = adminFile.read()
                admin = HubAdmin(hub, InetSocketAddress(settings.adminBind, adminPort), { adminClients }, log).also { it.start() }
                logger.info(
                    "NetherForge: the hub's admin API is listening on ${settings.adminBind}:${settings.adminPort}, " +
                        "with ${adminClients.size} clients (/nfhub admins add)"
                )
            } catch (e: Exception) {
                logger.error("NetherForge: the hub's admin API couldn't start on ${settings.adminBind}:${settings.adminPort}", e)
            }
        }
        this.hub = hub
        this.server = server
        this.settings = settings
        logger.info(
            "NetherForge: the network's hub is listening on ${settings.bind}:${server.port}, with ${credentials.size} credentials" +
                if (settings.secret == null) " and no shared secret" else ""
        )
        proxy.commandManager.register(proxy.commandManager.metaBuilder("nfhub").plugin(this).build(), StatusCommand())
    }

    /** `/metrics` with `metrics.port`: the proxy's own numbers, and the hub's when it runs one. */
    private fun serveMetrics(settings: HubSettings) {
        val port = settings.metricsPort ?: return
        try {
            metrics = dev.netherforge.network.Prometheus.serve(InetSocketAddress(settings.metricsBind, port)) {
                val fixed = proxy.configuration.servers.keys.map { it.lowercase() }.toSet()
                val (config, runtime) = proxy.allServers.partition { it.serverInfo.name.lowercase() in fixed }
                counts.text(proxy.playerCount, mapOf("config" to config.size, "runtime" to runtime.size)) + (hub?.metrics() ?: "")
            }
            logger.info("NetherForge: metrics are at http://${settings.metricsBind}:$port/metrics")
        } catch (e: Exception) {
            logger.error("NetherForge: metrics couldn't listen on ${settings.metricsBind}:$port", e)
        }
    }

    /** Joins the hub running on its own as its proxy: it hears this proxy's servers and players, and asks it to act. */
    private fun joinRemote(joining: HubSettings.Remote) {
        remoteSettings = joining
        val velocity = VelocityProxy()
        lateinit var client: HubClient
        val listener = object : HubListener {
            override fun connected(welcome: HubFrame.Welcome) {
                logger.info("NetherForge: joined the network's hub at ${joining.host}:${joining.port} as ${joining.name}")
                counts.hubJoined()
                reportServers(client)
                client.send(ServerFrame.ProxyPresence(velocity.players()))
            }

            override fun received(frame: HubFrame) {
                when (frame) {
                    is HubFrame.ProxyMove -> velocity.send(frame.player, frame.server).thenAccept {
                        client.send(ServerFrame.ProxyResult(frame.id, it))
                    }
                    is HubFrame.ProxyRegister -> {
                        place(frame.name, frame.host, frame.port)
                        reportServers(client)
                    }
                    is HubFrame.ProxySync -> {
                        val wanted = frame.servers.map { it.name.lowercase() }.toSet()
                        val fixed = proxy.configuration.servers.keys.map { it.lowercase() }.toSet()
                        // Run-time servers the hub no longer holds (taken off while this proxy was away) come off.
                        for (server in proxy.allServers) {
                            val name = server.serverInfo.name.lowercase()
                            if (name !in fixed && name !in wanted) proxy.unregisterServer(server.serverInfo)
                        }
                        for (server in frame.servers) place(server.name, server.host, server.port)
                        reportServers(client)
                        logger.info("NetherForge: has the hub's ${frame.servers.size} servers added at run time; taking logins")
                        everSynced = true
                        synced.complete(Unit)
                    }
                    is HubFrame.ProxyUnregister -> {
                        velocity.unregister(frame.name)
                        reportServers(client)
                    }
                    is HubFrame.ProxyMessage -> velocity.message(frame.player, frame.kind, frame.text, frame.subtitle)
                    is HubFrame.ProxyKick -> velocity.kick(frame.player, frame.message)
                    is HubFrame.LoginPlaced -> logins.remove(frame.id)?.complete(frame)
                    is HubFrame.ProxyDrain -> {
                        draining = frame.draining
                        logger.info(
                            if (frame.draining) "NetherForge: the hub drains this proxy: no new logins" else "NetherForge: logins again"
                        )
                    }
                    else -> Unit
                }
            }

            override fun disconnected(why: String) {
                logger.warn("NetherForge: lost the network's hub ($why); trying again")
                counts.hubLost()
                if (synced.isDone) synced = CompletableFuture()
                for (waiting in logins.values) waiting.complete(HubFrame.LoginPlaced(-1))
                logins.clear()
            }

            override fun failed(why: String) = logger.warn("NetherForge: couldn't join the network's hub: $why")
        }
        client =
            HubClient(
                joining.host,
                joining.port,
                joining.name,
                joining.secret,
                listener,
                credential = joining.credential,
                role = Grants.PROXY_ROLE
            )
        remote = client
        client.start()
    }

    /** Adds [name] at [host]:[port], or moves it there; a server already there is left as it is. */
    private fun place(name: String, host: String, port: Int) {
        val existing = proxy.getServer(name).orElse(null)?.serverInfo
        if (existing != null) {
            val address = existing.address
            if (address.hostString == host && address.port == port) return
            if (proxy.configuration.servers.keys.any { it.equals(name, ignoreCase = true) }) {
                logger.warn("NetherForge: the hub asked to add $name, one of this proxy's own servers; it stays as configured")
                return
            }
            proxy.unregisterServer(existing)
        }
        proxy.registerServer(ServerInfo(name, InetSocketAddress.createUnresolved(host, port)))
    }

    private fun reportServers(client: HubClient) {
        val all = proxy.allServers.map { it.serverInfo.name }
        val fixed = proxy.configuration.servers.keys.toList()
        client.send(ServerFrame.ProxyServers(all, fixed))
    }

    @Subscribe
    fun onShutdown(event: ProxyShutdownEvent) {
        metrics?.stop(0)
        metrics = null
        remote?.stop()
        remote = null
        admin?.stop()
        admin = null
        server?.stop()
        server = null
        hub = null
    }

    /** Last, so what other plugins did about it is settled: the player is on [ServerConnectedEvent.getServer] now. */
    @Subscribe(order = PostOrder.LAST)
    fun onServerConnected(event: ServerConnectedEvent) {
        val player = event.player
        if (clearResourcePacks && event.previousServer.isPresent) {
            // What the server they left sent comes off; the new one's arrive as they join it, after this.
            val left = player.appliedResourcePacks.filter { it.originalOrigin == ResourcePackInfo.Origin.DOWNSTREAM_SERVER }
            for (pack in left) player.removeResourcePacks(pack.id)
        }
        if (event.previousServer.isPresent) counts.moved() else counts.loggedIn()
        hub?.playerMoved(player.uniqueId.toString(), player.username, event.server.serverInfo.name)
        remote?.send(ServerFrame.ProxyPlayer(PlayerState(player.uniqueId.toString(), player.username, event.server.serverInfo.name)))
    }

    /**
     * Where a player logging in goes, when an admin client places logins: the proxy waits for its answer (the player
     * sees the loading screen), or uses its own choice when there's none in time.
     */
    @Subscribe(order = PostOrder.LAST)
    fun onChooseInitialServer(event: PlayerChooseInitialServerEvent): EventTask? {
        val placing = chooseInitialServer(event)
        if (placing == null) {
            countNoServer(event)
            return null
        }
        return EventTask.withContinuation { continuation ->
            placing.execute(object : com.velocitypowered.api.event.Continuation {
                override fun resume() {
                    countNoServer(event)
                    continuation.resume()
                }

                override fun resumeWithException(exception: Throwable) = continuation.resumeWithException(exception)
            })
        }
    }

    /** Players turned away while their login was placed, so the empty choice that follows isn't counted twice. */
    private val turnedAway: MutableSet<java.util.UUID> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Velocity turns away a login it has no server for ("There are no available servers"): counted, as `no_server`. */
    private fun countNoServer(event: PlayerChooseInitialServerEvent) {
        if (turnedAway.remove(event.player.uniqueId)) return
        if (event.initialServer.isEmpty) counts.refused(ProxyMetrics.LoginRefusal.NO_SERVER)
    }

    private fun chooseInitialServer(event: PlayerChooseInitialServerEvent): EventTask? {
        remote?.let { client -> return awaitSync(client, event) }
        val hub = hub ?: return null
        val placed = hub.placeLogin(event.player.uniqueId.toString(), event.player.username)
        if (placed.isDone && placed.join().server == null && placed.join().deny == null) return null
        return EventTask.resumeWhenComplete(
            placed.thenAccept { place ->
                place.server?.let { name -> proxy.getServer(name).ifPresent(event::setInitialServer) }
                place.deny?.let { why -> refuse(event, why) }
            }
        )
    }

    /** Turns away [event]'s player as the hub (or the service placing logins) says. */
    private fun refuse(event: PlayerChooseInitialServerEvent, why: String) {
        counts.refused(ProxyMetrics.LoginRefusal.HUB)
        turnedAway += event.player.uniqueId
        event.player.disconnect(MiniMessage.miniMessage().deserialize(why))
    }

    /** Last, so every plugin has had its say: a login refused is counted, with why. */
    @Subscribe(order = PostOrder.LAST)
    fun countLogin(event: com.velocitypowered.api.event.connection.LoginEvent) {
        if (event.result.isAllowed) return
        counts.refused(
            when {
                hub?.banOf(event.player.uniqueId.toString()) != null -> ProxyMetrics.LoginRefusal.BANNED
                draining -> ProxyMetrics.LoginRefusal.DRAINING
                else -> ProxyMetrics.LoginRefusal.DENIED
            }
        )
    }

    /** A server that didn't take a player connecting to it: their first (the login failed) or the next (a move did). */
    @Subscribe(order = PostOrder.LAST)
    fun countKick(event: KickedFromServerEvent) {
        if (!event.kickedDuringServerConnect()) return
        if (event.player.currentServer.isPresent) {
            counts.moveFailed("server_refused")
        } else {
            counts.refused(ProxyMetrics.LoginRefusal.INITIAL_SERVER)
        }
    }

    /** A proxy the hub drains takes no new logins: they join through another. */
    @Subscribe
    fun onLogin(event: com.velocitypowered.api.event.connection.LoginEvent) {
        hub?.banOf(event.player.uniqueId.toString())?.let { why ->
            event.result = com.velocitypowered.api.event.ResultedEvent.ComponentResult.denied(Component.text(why))
            return
        }
        if (draining) {
            event.result = com.velocitypowered.api.event.ResultedEvent.ComponentResult.denied(
                Component.text("This way in is restarting: join again in a moment.")
            )
        }
    }

    /** A login placed by the hub running on its own: asked, and waited for (no longer than a hold may last). */
    private fun awaitSync(client: HubClient, event: PlayerChooseInitialServerEvent): EventTask? {
        val sync = synced
        if (!sync.isDone && (client.connected || !everSynced)) {
            // Joined (or joining) the hub but without its servers yet: wait for them, then place the login.
            return EventTask.withContinuation { continuation ->
                sync.completeOnTimeout(Unit, SYNC_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS).whenComplete { _, _ ->
                    val placing = askRemote(client, event)
                    if (placing == null) continuation.resume() else placing.execute(continuation)
                }
            }
        }
        return askRemote(client, event)
    }

    private fun askRemote(client: HubClient, event: PlayerChooseInitialServerEvent): EventTask? {
        if (!client.connected) return null
        val id = loginIds.getAndIncrement()
        val placed = CompletableFuture<HubFrame.LoginPlaced>()
        logins[id] = placed
        if (!client.send(ServerFrame.ProxyLogin(id, event.player.uniqueId.toString(), event.player.username))) {
            logins.remove(id)
            return null
        }
        return EventTask.resumeWhenComplete(
            placed.completeOnTimeout(
                HubFrame.LoginPlaced(id),
                LOGIN_WAIT_SECONDS,
                java.util.concurrent.TimeUnit.SECONDS
            ).thenAccept { place ->
                logins.remove(id)
                place.server?.let { name -> proxy.getServer(name).ifPresent(event::setInitialServer) }
                place.deny?.let { why -> refuse(event, why) }
            }
        )
    }

    @Subscribe(order = PostOrder.LAST)
    fun onDisconnect(event: DisconnectEvent) {
        hub?.playerLeft(event.player.uniqueId.toString())
        remote?.send(ServerFrame.ProxyPlayerLeft(event.player.uniqueId.toString()))
    }

    private inner class VelocityProxy : HubProxy {
        override fun servers(): List<String> = proxy.allServers.map { it.serverInfo.name }

        override fun players(): List<PlayerState> = proxy.allPlayers.map { player ->
            PlayerState(player.uniqueId.toString(), player.username, player.currentServer.map { it.serverInfo.name }.orElse(null))
        }

        override fun register(name: String, host: String, port: Int): String? {
            if (proxy.getServer(name).isPresent) return "the proxy already has a server named $name"
            proxy.registerServer(ServerInfo(name, InetSocketAddress.createUnresolved(host, port)))
            return null
        }

        override fun unregister(name: String) {
            proxy.getServer(name).ifPresent { proxy.unregisterServer(it.serverInfo) }
        }

        override fun message(id: String, kind: String, text: String, subtitle: String?): String? {
            val player = proxy.getPlayer(UUID.fromString(id)).orElse(null) ?: return "they aren't on the proxy"
            val shown = MiniMessage.miniMessage().deserialize(text)
            val below = subtitle?.let { MiniMessage.miniMessage().deserialize(it) }
            // The hub held the text to RichText already; what Adventure built is held to it again, so nothing else is shown.
            if (!allowed(shown) || (below != null && !allowed(below))) {
                return "the message has a click or hover action a message may not carry"
            }
            when (kind) {
                "chat" -> player.sendMessage(shown)
                "actionbar" -> player.sendActionBar(shown)
                "title" -> player.showTitle(
                    Title.title(
                        shown,
                        below ?: Component.empty()
                    )
                )
                else -> return "\"$kind\" isn't chat, title or actionbar"
            }
            return null
        }

        /** Whether [component] and everything in it clicks and hovers only as [RichText] allows. */
        private fun allowed(component: Component): Boolean {
            component.clickEvent()?.let { click ->
                if (ClickEvent.Action.NAMES.key(click.action()) !in RichText.ALLOWED_CLICKS) return false
            }
            component.hoverEvent()?.let { hover ->
                if (hover.action() != HoverEvent.Action.SHOW_TEXT) return false
                if (!allowed(hover.value() as Component)) return false
            }
            return component.children().all(::allowed)
        }

        override fun kick(id: String, message: String): String? {
            val player = proxy.getPlayer(UUID.fromString(id)).orElse(null) ?: return "they aren't on the proxy"
            player.disconnect(MiniMessage.miniMessage().deserialize(message))
            return null
        }

        override fun isFixed(name: String): Boolean = proxy.configuration.servers.keys.any { it.equals(name, ignoreCase = true) }

        override fun send(id: String, server: String): CompletionStage<String?> {
            val player = proxy.getPlayer(UUID.fromString(id)).orElse(null)
                ?: return CompletableFuture.completedFuture("they aren't on the proxy")
            val target = proxy.getServer(server).orElse(null)
                ?: return CompletableFuture.completedFuture("the proxy has no server named \"$server\"")
            return player.createConnectionRequest(target).connect().thenApply { result ->
                if (result.isSuccessful) {
                    null
                } else {
                    counts.moveFailed(
                        when (result.status) {
                            ConnectionRequestBuilder.Status.ALREADY_CONNECTED -> "already_connected"
                            ConnectionRequestBuilder.Status.CONNECTION_IN_PROGRESS -> "in_progress"
                            ConnectionRequestBuilder.Status.CONNECTION_CANCELLED -> "cancelled"
                            else -> "server_refused"
                        }
                    )
                    when (result.status) {
                        ConnectionRequestBuilder.Status.ALREADY_CONNECTED -> "they're already on $server"
                        ConnectionRequestBuilder.Status.CONNECTION_IN_PROGRESS -> "they're already on their way to a server"
                        ConnectionRequestBuilder.Status.CONNECTION_CANCELLED -> "a proxy plugin cancelled it"
                        else -> "$server didn't take them" + result.reasonComponent.map { ": " + plain(it) }.orElse("")
                    }
                }
            }
        }
    }

    private fun plain(component: Component) = PlainTextComponentSerializer.plainText().serialize(component)

    /**
     * `/nfhub`: what's connected. `/nfhub credentials add <id> [role] [owns,...] [--to-file]`, `revoke <id>`,
     * `list`, `reload`: the servers' own credentials, kept in `credentials.properties`.
     */
    private inner class StatusCommand : SimpleCommand {
        override fun hasPermission(invocation: SimpleCommand.Invocation) = invocation.source().hasPermission("netherforge.admin")

        override fun suggest(invocation: SimpleCommand.Invocation): List<String> {
            val args = invocation.arguments()
            return when (args.size) {
                0, 1 -> listOf("credentials", "admins", "ban", "unban", "mute", "unmute", "reports", "disable", "enable")
                    .filter { it.startsWith(args.firstOrNull().orEmpty()) }
                2 -> listOf("add", "revoke", "list", "reload").filter { it.startsWith(args[1]) }
                3 -> if (args[1] == "revoke") hub?.credentials()?.map { it.id }.orEmpty().filter { it.startsWith(args[2]) } else emptyList()
                4 -> if (args[1] == "add") Grants.ROLES.filter { it.startsWith(args[3]) } else emptyList()
                else -> emptyList()
            }
        }

        override fun execute(invocation: SimpleCommand.Invocation) {
            val args = invocation.arguments()
            val by = (invocation.source() as? com.velocitypowered.api.proxy.Player)?.username ?: "the proxy's console"
            val text = when {
                remote != null -> status()
                args.firstOrNull() == "credentials" -> credentials(args.drop(1))
                args.firstOrNull() == "admins" -> admins(args.drop(1))
                args.firstOrNull() in setOf(
                    "ban",
                    "unban",
                    "mute",
                    "unmute",
                    "reports",
                    "disable",
                    "enable"
                ) -> moderation(args.toList(), by)
                else -> status()
            }
            invocation.source().sendMessage(Component.text(text))
        }

        private fun status(): String {
            val client = remote
            if (client != null) {
                val joining = remoteSettings!!
                val state = if (client.connected) "connected" else "not connected (trying again)"
                return "This proxy is ${joining.name} of the network's hub at ${joining.host}:${joining.port}: $state. " +
                    "Its credentials and admin clients are the hub's (java -jar NetherForge-<version>-hub.jar credentials ...)."
            }
            val hub = hub ?: return "NetherForge's network hub isn't running (see the proxy's log)."
            val linked = hub.connections().associateBy { it.server }
            val states = hub.states().associateBy { it.name }
            val registered = hub.registered().associateBy { it.name }
            val lines = proxy.allServers.map { server ->
                val name = server.serverInfo.name
                val connection = linked[name]
                val state = states[name]
                val how = when {
                    connection == null -> "not connected"
                    connection.credential == null -> "connected (shared secret)"
                    else -> "connected (${connection.credential}: ${connection.grants.role})"
                }
                val group = state?.group?.let { " in $it" }.orEmpty()
                val room = state?.capacity?.let { " of $it" }.orEmpty()
                val closed = if (state != null && state.linked && !state.accepting) ", not taking players" else ""
                val added = registered[name]?.let { if (it.byAdmin) " (added by the admin API)" else " (registered itself)" }.orEmpty()
                "  $name$added: $how$group, ${server.playersConnected.size}$room online$closed"
            }
            return "NetherForge network hub on port ${server?.port}:\n" + lines.joinToString("\n")
        }

        /**
         * `/nfhub ban|mute <player> [reason]`, `unban|unmute <player>` (network-wide), `reports`, and `disable <server|group>
         * [reason]` / `enable <server|group>`: the network's moderation, kept by the hub.
         */
        private fun moderation(args: List<String>, by: String): String {
            val hub = hub ?: return "NetherForge's network hub isn't running (see the proxy's log)."
            val what = args[0]
            if (what == "reports") {
                val all = hub.reports().take(20)
                return if (all.isEmpty()) {
                    "No reports."
                } else {
                    "Reports, newest first:\n" + all.joinToString("\n") {
                        "  #${it.id} ${it.name} on ${it.server}: ${it.reason}" + it.by?.let { by -> " (by $by)" }.orEmpty()
                    }
                }
            }
            val target =
                args.getOrNull(1)
                    ?: return "Usage: /nfhub $what <${if (what == "disable" || what == "enable") "server|group" else "player"}>" +
                        if (what == "ban" || what == "mute" || what == "disable") " [reason]" else ""
            val reason = args.drop(2).joinToString(" ").ifBlank { null }
            if (what ==
                "disable"
            ) {
                return hub.disable(target, reason, by)
                    ?: "Took $target off the network: its players are sent to its fallback, and it's refused until /nfhub enable $target."
            }
            if (what == "enable") return hub.enable(target, by) ?: "$target may join the network again."
            val player = proxy.getPlayer(target).orElse(null)
            val id = player?.uniqueId?.toString() ?: runCatching { UUID.fromString(target) }.getOrNull()?.toString()
                ?: return "Nobody called $target is on the proxy: name them by UUID."
            val name = player?.username ?: target
            val action = dev.netherforge.network.ModerationAction.entries.first { it.word == what }
            return hub.moderate(action, id, name, reason, by, null) ?: when (action) {
                dev.netherforge.network.ModerationAction.BAN -> "Banned $name from the network."
                dev.netherforge.network.ModerationAction.UNBAN -> "Lifted $name's ban."
                dev.netherforge.network.ModerationAction.MUTE -> "Muted $name across the network."
                dev.netherforge.network.ModerationAction.UNMUTE -> "Lifted $name's mute."
            }
        }

        /** `/nfhub admins add <id> <scopes|*>`, `revoke <id>`, `list`: the admin API's clients, in `admin.properties`. */
        private fun admins(args: List<String>): String = try {
            when (args.firstOrNull()) {
                "list" -> {
                    val all = adminFile.read()
                    if (all.isEmpty()) {
                        "No admin clients. Add one with /nfhub admins add <id> <scopes> (or * for every scope)."
                    } else {
                        "Admin clients:\n" + all.joinToString("\n") { "  ${it.id}: ${it.scopes.joinToString { s -> s.word }}" }
                    }
                }
                "add" -> {
                    val id = args.getOrNull(1) ?: return "Usage: /nfhub admins add <id> <scope,scope,...|*>"
                    if (!NetworkNames.isCredential(id)) return "\"$id\" isn't a client id: letters, digits, _ . -"
                    val words = args.drop(2).joinToString(",").split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    if (words.isEmpty()) return "Say what it may do: ${AdminScope.entries.joinToString { it.word }}, or *"
                    val scopes = if (words == listOf("*")) {
                        AdminScope.entries.toSet()
                    } else {
                        words.map {
                            AdminScope.of(it) ?: return "\"$it\" isn't a scope: ${AdminScope.entries.joinToString { s -> s.word }}"
                        }.toSet()
                    }
                    val all = adminFile.read()
                    if (all.any { it.id == id }) return "There's an admin client \"$id\" already: revoke it first for a new token."
                    val client = AdminClient(id, Auth.newSecret(), scopes)
                    adminFile.write(all + client)
                    adminClients = all + client
                    logger.info("NetherForge: added the admin client $id (${scopes.joinToString { it.word }})")
                    hub?.note("the proxy's console", "admin client added", id, scopes.joinToString { it.word })
                    val off = if (admin == null) " The admin API is off: set admin.port in hub.properties and restart." else ""
                    "Added the admin client $id. Its token, shown only now: ${client.token}$off"
                }
                "revoke" -> {
                    val id = args.getOrNull(1) ?: return "Usage: /nfhub admins revoke <id>"
                    val all = adminFile.read()
                    if (all.none { it.id == id }) return "There's no admin client \"$id\"."
                    adminFile.write(all.filter { it.id != id })
                    adminClients = all.filter { it.id != id }
                    hub?.detach(id)
                    hub?.note("the proxy's console", "admin client revoked", id)
                    logger.info("NetherForge: revoked the admin client $id")
                    "Revoked $id: its token no longer works, and its open streams are closed."
                }
                else -> "Usage: /nfhub admins add <id> <scopes|*> | revoke <id> | list"
            }
        } catch (e: Exception) {
            logger.warn("NetherForge: /nfhub admins failed", e)
            "That failed: ${e.message}"
        }

        private fun credentials(args: List<String>): String {
            val hub = hub ?: return "NetherForge's network hub isn't running (see the proxy's log)."
            val settings = settings!!
            return try {
                when (args.firstOrNull()) {
                    "list" -> {
                        val all = hub.credentials()
                        if (all.isEmpty()) {
                            "No credentials: servers join with the shared secret. Add one with /nfhub credentials add <id> [role]."
                        } else {
                            val connected = hub.connections().groupBy { it.credential }
                            "Credentials:\n" + all.joinToString("\n") { credential ->
                                val on = connected[credential.id].orEmpty().map { it.server }
                                "  ${credential.id}: ${credential.grants.describe()}" +
                                    if (on.isEmpty()) "" else "; connected: ${on.joinToString()}"
                            }
                        }
                    }
                    "add" -> {
                        val id = args.getOrNull(1) ?: return "Usage: /nfhub credentials add <id> [role] [owns,...] [--to-file]"
                        if (!NetworkNames.isCredential(id)) return "\"$id\" isn't a credential id: letters, digits, _ . -"
                        val words = args.drop(2).filter { it != "--to-file" }
                        val role = words.getOrNull(0) ?: Grants.STANDARD_ROLE
                        if (role !in Grants.ROLES) return "\"$role\" isn't a role: trusted, standard or isolated"
                        val owns = words.getOrNull(1)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
                        owns.firstOrNull { !NetworkNames.isNamespace(it) }?.let { return "\"$it\" isn't a namespace" }
                        val entries = credentialsFile.read()
                        if (entries.any {
                                it.id == id
                            }
                        ) {
                            return "There's a credential \"$id\" already: revoke it first to issue a new secret."
                        }
                        val entry = CredentialEntry(id, Auth.newSecret(), role, owns)
                        credentialsFile.write(entries + entry)
                        hub.setCredentials((entries + entry).map { it.credential(settings.roleShares) })
                        logger.info("NetherForge: added the credential $id (${entry.credential(settings.roleShares).grants.describe()})")
                        val config = "network:\n  credential:\n    id: $id\n    secret: ${entry.secret}"
                        if ("--to-file" in args) {
                            val issued = folder.resolve("issued").resolve("$id.yml")
                            java.nio.file.Files.createDirectories(issued.parent)
                            java.nio.file.Files.writeString(issued, config + "\n")
                            "Added the credential $id ($role). Its config is in $issued: hand it to the server, then delete it."
                        } else {
                            "Added the credential $id ($role). It's shown only now; put it in the server's plugins/NetherForge/config.yml:\n$config"
                        }
                    }
                    "revoke" -> {
                        val id = args.getOrNull(1) ?: return "Usage: /nfhub credentials revoke <id>"
                        val entries = credentialsFile.read()
                        if (entries.none { it.id == id }) return "There's no credential \"$id\"."
                        val left = entries.filter { it.id != id }
                        credentialsFile.write(left)
                        hub.setCredentials(left.map { it.credential(settings.roleShares) })
                        logger.info("NetherForge: revoked the credential $id")
                        "Revoked $id: servers using it were disconnected, and can't join again with it."
                    }
                    "reload" -> {
                        val fresh = HubSettings.read(folder.resolve(HubSettings.FILE)) { logger.info(it) }
                        val all = credentialsFile.read().map { it.credential(fresh.roleShares) }
                        this@NetherForgeProxy.settings =
                            settings.copy(roleShares = fresh.roleShares, groups = fresh.groups, fallback = fresh.fallback)
                        hub.setGroups(fresh.groups, fresh.fallback)
                        hub.setCredentials(all)
                        "Read ${all.size} credentials (and hub.properties' shares and groups); servers whose grants changed dial again."
                    }
                    else -> "Usage: /nfhub credentials add <id> [role] [owns,...] [--to-file] | revoke <id> | list | reload"
                }
            } catch (e: Exception) {
                logger.warn("NetherForge: /nfhub credentials failed", e)
                "That failed: ${e.message}"
            }
        }
    }

    private companion object {
        /** How long a login may wait for the hub running on its own to place it: a hold's longest, and a little. */
        const val LOGIN_WAIT_SECONDS = 35L

        /** How long a login waits for the proxy to have the hub's servers before it goes ahead with what it has. */
        const val SYNC_WAIT_SECONDS = 5L
    }
}
