package dev.netherforge.proxy

import com.google.inject.Inject
import com.velocitypowered.api.command.SimpleCommand
import com.velocitypowered.api.event.PostOrder
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.player.ServerConnectedEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ConnectionRequestBuilder
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.player.ResourcePackInfo
import dev.netherforge.network.FileMapStore
import dev.netherforge.network.Hub
import dev.netherforge.network.HubLog
import dev.netherforge.network.HubProxy
import dev.netherforge.network.HubServer
import dev.netherforge.network.PlayerState
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
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
    private var clearResourcePacks = true

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
        val hub = Hub(settings.secret, VelocityProxy(), log, settings.limits, FileMapStore(folder.resolve("maps.json")))
        val server = HubServer(hub, InetSocketAddress(settings.bind, settings.port), log, settings.limits)
        try {
            server.start()
        } catch (e: Exception) {
            logger.error("NetherForge: the network's hub couldn't listen on ${settings.bind}:${settings.port}", e)
            return
        }
        this.hub = hub
        this.server = server
        clearResourcePacks = settings.clearResourcePacks
        logger.info("NetherForge: the network's hub is listening on ${settings.bind}:${server.port}")
        proxy.commandManager.register(proxy.commandManager.metaBuilder("nfhub").plugin(this).build(), StatusCommand())
    }

    @Subscribe
    fun onShutdown(event: ProxyShutdownEvent) {
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
        hub?.playerMoved(player.uniqueId.toString(), player.username, event.server.serverInfo.name)
    }

    @Subscribe(order = PostOrder.LAST)
    fun onDisconnect(event: DisconnectEvent) {
        hub?.playerLeft(event.player.uniqueId.toString())
    }

    private inner class VelocityProxy : HubProxy {
        override fun servers(): List<String> = proxy.allServers.map { it.serverInfo.name }

        override fun players(): List<PlayerState> = proxy.allPlayers.map { player ->
            PlayerState(player.uniqueId.toString(), player.username, player.currentServer.map { it.serverInfo.name }.orElse(null))
        }

        override fun send(id: String, server: String): CompletionStage<String?> {
            val player = proxy.getPlayer(UUID.fromString(id)).orElse(null)
                ?: return CompletableFuture.completedFuture("they aren't on the proxy")
            val target = proxy.getServer(server).orElse(null)
                ?: return CompletableFuture.completedFuture("the proxy has no server named \"$server\"")
            return player.createConnectionRequest(target).connect().thenApply { result ->
                if (result.isSuccessful) {
                    null
                } else {
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

    private inner class StatusCommand : SimpleCommand {
        override fun hasPermission(invocation: SimpleCommand.Invocation) = invocation.source().hasPermission("netherforge.admin")

        override fun execute(invocation: SimpleCommand.Invocation) {
            val hub = hub
            val text = if (hub == null) {
                "NetherForge's network hub isn't running (see the proxy's log)."
            } else {
                val linked = hub.linked().toSet()
                val lines = proxy.allServers.map { registered ->
                    val name = registered.serverInfo.name
                    "  $name: ${if (name in linked) "connected" else "not connected"}, ${registered.playersConnected.size} online"
                }
                "NetherForge network hub on port ${server?.port}:\n" + lines.joinToString("\n")
            }
            invocation.source().sendMessage(Component.text(text))
        }
    }
}
