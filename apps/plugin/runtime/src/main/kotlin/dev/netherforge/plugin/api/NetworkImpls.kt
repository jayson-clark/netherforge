package dev.netherforge.plugin.api

import dev.netherforge.network.Balance
import dev.netherforge.network.Lifecycle
import dev.netherforge.network.MapOp
import dev.netherforge.network.NetworkNames
import dev.netherforge.network.ServerState
import dev.netherforge.plugin.async.WorkFailed
import dev.netherforge.plugin.binding.Caller
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.binding.NetworkChannelApi
import dev.netherforge.plugin.binding.NetworkGroup
import dev.netherforge.plugin.binding.NetworkMapApi
import dev.netherforge.plugin.binding.NetworkMapWriteOptions
import dev.netherforge.plugin.binding.NetworkPlayer
import dev.netherforge.plugin.binding.NetworkPlayersOptions
import dev.netherforge.plugin.binding.NetworkPublishOptions
import dev.netherforge.plugin.binding.NetworkRole
import dev.netherforge.plugin.binding.NetworkServer
import dev.netherforge.plugin.binding.NetworkShare
import dev.netherforge.plugin.binding.NfNetworkApi
import dev.netherforge.plugin.binding.PlayerOrString
import dev.netherforge.plugin.binding.StringOrNetworkSendOptions
import dev.netherforge.plugin.binding.StringOrNetworkTarget
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

    override fun serverRole(caller: Caller): NetworkRole? {
        if (!link.connected) return null
        val grants = link.grants
        return NetworkRole(
            role = grants.role,
            trusted = grants.trusted,
            owns = grants.owns,
            shares = grants.shares.map { share -> NetworkShare(share.name, share.access.sortedBy { it.ordinal }.map { it.word }) },
            presence = grants.presence,
            sendTo = grants.sendTo
        )
    }

    override fun group(caller: Caller): String? = link.group

    override fun state(caller: Caller): String = link.state.word

    override fun holdReady(caller: Caller): LuaHandle.NetworkHold = network.hold(caller.scope)

    override fun setDraining(caller: Caller, draining: Boolean) = link.setDraining(draining)

    override fun isDraining(caller: Caller): Boolean = link.state == Lifecycle.DRAINING || link.state == Lifecycle.STOPPING

    override fun setAccepting(caller: Caller, accepting: Boolean) = link.setAccepting(accepting)

    override fun isAccepting(caller: Caller): Boolean = link.accepting

    override fun servers(caller: Caller): List<NetworkServer> {
        val counts = link.counts()
        return link.servers.values.map { server(it, counts) }
    }

    override fun groups(caller: Caller): List<NetworkGroup> {
        val counts = link.counts()
        return link.servers.values.groupBy { it.groupName }.map { (name, instances) ->
            NetworkGroup(
                name = name,
                players = instances.sumOf { counts[it.name] ?: 0 }.toLong(),
                capacity = if (instances.any { it.capacity == null }) null else instances.sumOf { it.capacity!! }.toLong(),
                instances = instances.sortedBy { it.name }.map { server(it, counts) }
            )
        }.sortedBy { it.name }
    }

    private fun server(state: ServerState, counts: Map<String, Int>) = NetworkServer(
        name = state.name,
        connected = state.linked,
        players = (counts[state.name] ?: 0).toLong(),
        state = state.state?.word,
        idle = state.idle,
        group = state.groupName,
        capacity = state.capacity?.toLong(),
        accepting = state.accepting
    )

    override fun players(caller: Caller, options: NetworkPlayersOptions?): List<NetworkPlayer> {
        options?.server?.let { checkServer(it, "options.server") }
        options?.group?.let { checkServer(it, "options.group") }
        return link.players.values.filter { player ->
            (options?.server == null || player.server.equals(options.server, ignoreCase = true)) &&
                (
                    options?.group == null ||
                        player.server?.let {
                            link.servers[it]?.groupName ?: it
                        }.equals(options.group, ignoreCase = true)
                    )
        }.map { NetworkPlayer(it.id, it.name, it.server) }
    }

    override fun findPlayer(caller: Caller, player: String): NetworkPlayer? =
        network.findPlayer(player)?.let { NetworkPlayer(it.id, it.name, it.server) }

    override fun channel(caller: Caller, name: String): LuaHandle.NetworkChannel =
        network.channel(network.resolve(caller.scope.namespace, name, "nf.network.channel"))

    override fun map(caller: Caller, name: String): LuaHandle.NetworkMap =
        network.mirror(network.resolve(caller.scope.namespace, name, "nf.network.map")).handle

    override fun sendPlayer(
        caller: Caller,
        player: PlayerOrString,
        target: StringOrNetworkSendOptions,
        data: LuaValue
    ): CompletionStage<String> = send(caller, listOf(player), target, data)

    override fun sendPlayers(
        caller: Caller,
        players: List<PlayerOrString>,
        target: StringOrNetworkSendOptions,
        data: LuaValue
    ): CompletionStage<String> {
        if (players.isEmpty()) throw LuaApiException("bad argument 'players' (at least one player expected)")
        return send(caller, players, target, data)
    }

    private fun send(
        caller: Caller,
        players: List<PlayerOrString>,
        target: StringOrNetworkSendOptions,
        data: LuaValue
    ): CompletionStage<String> {
        val (server, group, options) = when (target) {
            is StringOrNetworkSendOptions.String -> Triple(checkServer(target.value, "target"), null, null)
            is StringOrNetworkSendOptions.NetworkSendOptions -> {
                val options = target.value
                if ((options.server == null) == (options.group == null)) {
                    throw LuaApiException("bad argument 'target' (a table naming a server or a group expected, not both or neither)")
                }
                Triple(
                    options.server?.let {
                        checkServer(it, "target.server")
                    },
                    options.group?.let { checkServer(it, "target.group") },
                    options
                )
            }
        }
        val balance = options?.balance?.let { word ->
            Balance.of(word) ?: throw LuaApiException("bad argument 'target.balance' (\"least\" or \"fill\" expected, got \"$word\")")
        }
        val sent = if (data.isNil) options?.data?.takeUnless { it.isNil } else data
        val json = sent?.let { network.checked(it.json("data"), "data") }
        val ids = players.map { player ->
            when (player) {
                is PlayerOrString.Player -> player.value.id
                is PlayerOrString.String -> network.findPlayer(player.value)?.id
                    ?: return CompletableFuture.failedFuture(WorkFailed("nobody called \"${player.value}\" is online in the network"))
            }
        }
        val wait = options?.wait?.also {
            if (!it.isFinite() ||
                it < 0
            ) {
                throw LuaApiException("bad argument 'target.wait' (seconds, at least 0, expected)")
            }
        }
        val onWait = options?.onWait?.let { caller.scope to it.ref }
        return network.send(caller.scope.namespace, ids, server, json, group, balance, wait, onWait)
    }
}

/** [name] when it's a server's or group's name; an error naming [where] otherwise. */
internal fun checkServer(name: String, where: String): String {
    if (!NetworkNames.isServer(name)) throw LuaApiException("bad argument '$where' (a server's or group's name expected, got \"$name\")")
    return name
}

/** `NetworkHold`: a hold on this server being ready. */
internal class NetworkHoldImpl(private val session: ProjectSession) : dev.netherforge.plugin.binding.NetworkHoldApi {
    override fun release(self: LuaHandle.NetworkHold) = session.network.release(self)

    override fun isReleased(self: LuaHandle.NetworkHold): Boolean = session.network.isReleased(self)
}

/** `NetworkChannel`: a channel by its full name. */
internal class NetworkChannelImpl(private val session: ProjectSession) : NetworkChannelApi {
    private val network get() = session.network

    override fun name(self: LuaHandle.NetworkChannel): String = network.spell(self.name, session.names.calling())

    override fun publish(self: LuaHandle.NetworkChannel, value: LuaValue, options: NetworkPublishOptions?): Boolean {
        val server = options?.server?.let { checkServer(it, "options.server") }
        val group = options?.group?.let { checkServer(it, "options.group") }
        if (server != null && group != null) throw LuaApiException("bad argument 'options' (a server or a group, not both)")
        val json = network.checked(value(value, "value"), "value")
        return network.publish(session.names.calling(), self.name, json, server, group)
    }

    override fun request(self: LuaHandle.NetworkChannel, target: StringOrNetworkTarget, data: LuaValue): CompletionStage<Any?> {
        val (server, group) = when (target) {
            is StringOrNetworkTarget.String -> checkServer(target.value, "target") to null
            is StringOrNetworkTarget.NetworkTarget -> {
                val it = target.value
                if (listOfNotNull(it.server, it.group, it.admin).size != 1) {
                    throw LuaApiException("bad argument 'target' (a table naming one of a server, a group or an admin client expected)")
                }
                if (it.admin != null) {
                    if (!NetworkNames.isCredential(it.admin)) throw LuaApiException("bad argument 'target.admin' (a client's id expected)")
                    val json = network.checked(value(data, "data"), "data")
                    return network.request(session.names.calling(), self.name, null, json, admin = it.admin)
                }
                it.server?.let { name -> checkServer(name, "target.server") } to it.group?.let { name -> checkServer(name, "target.group") }
            }
        }
        val json = network.checked(value(data, "data"), "data")
        return network.request(session.names.calling(), self.name, server, json, group)
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
