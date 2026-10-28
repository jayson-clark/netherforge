package dev.netherforge.plugin.api

import dev.netherforge.network.MapOp
import dev.netherforge.network.NetworkNames
import dev.netherforge.plugin.async.WorkFailed
import dev.netherforge.plugin.binding.Caller
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.binding.NetworkChannelApi
import dev.netherforge.plugin.binding.NetworkMapApi
import dev.netherforge.plugin.binding.NetworkMapWriteOptions
import dev.netherforge.plugin.binding.NetworkPlayer
import dev.netherforge.plugin.binding.NetworkPublishOptions
import dev.netherforge.plugin.binding.NetworkServer
import dev.netherforge.plugin.binding.NfNetworkApi
import dev.netherforge.plugin.binding.PlayerOrString
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.lua.LuaValue
import dev.netherforge.plugin.session.ProjectSession
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * `nf.network`: this server's network, through `session.network` ([dev.netherforge.plugin.network.Network]) and
 * the link it keeps. A mistake in the call (a name that isn't one, another package's channel the caller can't use,
 * a value over the network's limit or that JSON can't hold) is an error at the script's line; what only the
 * network can say (not connected, nobody answering, the proxy refusing) is `false` or `nil, err`.
 */
internal class NfNetworkImpl(private val session: ProjectSession) : NfNetworkApi {
    private val network get() = session.network
    private val link get() = session.runtime.network

    override fun serverName(caller: Caller): String? = link.name

    override fun isConnected(caller: Caller): Boolean = link.connected

    override fun servers(caller: Caller): List<NetworkServer> {
        val counts = link.players.values.groupingBy { it.server }.eachCount()
        return link.servers.map { (name, linked) -> NetworkServer(name, linked, (counts[name] ?: 0).toLong()) }
    }

    override fun players(caller: Caller): List<NetworkPlayer> = link.players.values.map { NetworkPlayer(it.id, it.name, it.server) }

    override fun findPlayer(caller: Caller, player: String): NetworkPlayer? =
        network.findPlayer(player)?.let { NetworkPlayer(it.id, it.name, it.server) }

    override fun channel(caller: Caller, name: String): LuaHandle.NetworkChannel =
        network.channel(network.resolve(caller.scope.namespace, name, "nf.network.channel"))

    override fun map(caller: Caller, name: String): LuaHandle.NetworkMap =
        network.mirror(network.resolve(caller.scope.namespace, name, "nf.network.map")).handle

    override fun sendPlayer(caller: Caller, player: PlayerOrString, server: String, data: LuaValue): CompletionStage<Boolean> {
        if (!NetworkNames.isServer(server)) throw LuaApiException("bad argument 'server' (a server's name expected, got \"$server\")")
        val json = if (data.isNil) null else network.checked(data.json("data"), "data")
        val id = when (player) {
            is PlayerOrString.Player -> player.value.id
            is PlayerOrString.String -> network.findPlayer(player.value)?.id
                ?: return CompletableFuture.failedFuture(WorkFailed("nobody called \"${player.value}\" is online in the network"))
        }
        return network.send(caller.scope.namespace, id, server, json)
    }
}

/** `NetworkChannel`: a channel by its full name. */
internal class NetworkChannelImpl(private val session: ProjectSession) : NetworkChannelApi {
    private val network get() = session.network

    override fun name(self: LuaHandle.NetworkChannel): String = network.spell(self.name, session.names.calling())

    override fun publish(self: LuaHandle.NetworkChannel, value: LuaValue, options: NetworkPublishOptions?): Boolean {
        val server = options?.server
        if (server != null && !NetworkNames.isServer(server)) {
            throw LuaApiException("bad argument 'options.server' (a server's name expected, got \"$server\")")
        }
        val json = network.checked(value(value, "value"), "value")
        return network.publish(session.names.calling(), self.name, json, server)
    }

    override fun request(self: LuaHandle.NetworkChannel, server: String, data: LuaValue): CompletionStage<Any?> {
        if (!NetworkNames.isServer(server)) throw LuaApiException("bad argument 'server' (a server's name expected, got \"$server\")")
        val json = network.checked(value(data, "data"), "data")
        return network.request(session.names.calling(), self.name, server, json)
    }
}

/** `NetworkMap`: a shared map by its full name; reads from this server's copy, writes through the hub. */
internal class NetworkMapImpl(private val session: ProjectSession) : NetworkMapApi {
    private val network get() = session.network

    private fun mirror(self: LuaHandle.NetworkMap) = network.mirror(self.name)

    override fun name(self: LuaHandle.NetworkMap): String = network.spell(self.name, session.names.calling())

    override fun isReady(self: LuaHandle.NetworkMap): Boolean = mirror(self).ready

    override fun get(self: LuaHandle.NetworkMap, key: String): Any? = mirror(self).entries[key]

    override fun all(self: LuaHandle.NetworkMap): Map<String, Any?> = LinkedHashMap(mirror(self).entries)

    override fun set(self: LuaHandle.NetworkMap, key: String, value: LuaValue, options: NetworkMapWriteOptions?): Boolean {
        if (value.isNil) return write(self, key, MapOp.DELETE, null, options)
        return write(self, key, MapOp.SET, network.checked(value.json("value"), "value"), options)
    }

    override fun delete(self: LuaHandle.NetworkMap, key: String): Boolean = write(self, key, MapOp.DELETE, null, null)

    private fun write(self: LuaHandle.NetworkMap, key: String, op: MapOp, value: JsonElement?, options: NetworkMapWriteOptions?): Boolean =
        network.write(session.names.calling(), self.name, key, op, value, expires = options?.expires) != null

    @Suppress("UNCHECKED_CAST")
    override fun add(self: LuaHandle.NetworkMap, key: String, amount: Double, options: NetworkMapWriteOptions?): CompletionStage<Double> {
        if (!amount.isFinite()) throw LuaApiException("bad argument 'amount' (a finite number expected, got $amount)")
        return network.write(session.names.calling(), self.name, key, MapOp.ADD, amount = amount, expires = options?.expires)
            as CompletionStage<Double>
    }

    @Suppress("UNCHECKED_CAST")
    override fun insert(
        self: LuaHandle.NetworkMap,
        key: String,
        data: LuaValue,
        options: NetworkMapWriteOptions?
    ): CompletionStage<Boolean> {
        val json = value(data, "data")
        return network.write(
            session.names.calling(),
            self.name,
            key,
            MapOp.INSERT,
            network.checked(json, "data"),
            expires = options?.expires
        )
            as CompletionStage<Boolean>
    }
}

/** [value] as JSON for the network, which has no nil to send: `nil` is an error naming [where]. */
private fun value(value: LuaValue, where: String): JsonElement {
    if (value.isNil) throw LuaApiException("bad argument '$where' (a value expected, got nil)")
    return value.json(where)
}
