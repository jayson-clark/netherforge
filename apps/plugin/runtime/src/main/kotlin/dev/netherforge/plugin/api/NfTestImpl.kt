package dev.netherforge.plugin.api
import dev.netherforge.format.game.BlockState
import dev.netherforge.network.MapOp
import dev.netherforge.network.NetworkNames
import dev.netherforge.network.ServerFrame
import dev.netherforge.plugin.binding.BlockPosition
import dev.netherforge.plugin.binding.Caller
import dev.netherforge.plugin.binding.IntegerOrChoice
import dev.netherforge.plugin.binding.LuaEvent
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.binding.NetworkPlayer
import dev.netherforge.plugin.binding.NfTestApi
import dev.netherforge.plugin.binding.NoPayload
import dev.netherforge.plugin.binding.Raisable
import dev.netherforge.plugin.binding.RaisableEvents
import dev.netherforge.plugin.binding.RaisedEvent
import dev.netherforge.plugin.binding.TestAcceptOptions
import dev.netherforge.plugin.binding.TestClickOptions
import dev.netherforge.plugin.binding.TestDisconnectOptions
import dev.netherforge.plugin.binding.TestInputKeys
import dev.netherforge.plugin.binding.TestNetworkMessage
import dev.netherforge.plugin.binding.TestRequestOptions
import dev.netherforge.plugin.binding.TestShownOptions
import dev.netherforge.plugin.binding.TestWasmStub
import dev.netherforge.plugin.binding.TestWebResponse
import dev.netherforge.plugin.http.Connection
import dev.netherforge.plugin.http.TestConnections
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.lua.LuaFunction
import dev.netherforge.plugin.lua.LuaValue
import dev.netherforge.plugin.network.NetworkLink
import dev.netherforge.plugin.platform.BlockRef
import dev.netherforge.plugin.platform.ClickButton
import dev.netherforge.plugin.platform.GameEvent
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.session.ProjectSession
import dev.netherforge.plugin.session.handle
import dev.netherforge.plugin.testing.TestHarness
import dev.netherforge.plugin.web.WebAnswer
import dev.netherforge.plugin.web.WebCall
import kotlinx.serialization.json.JsonObject
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * `nf.test`: what a test file runs on. The prelude leaves the namespace out
 * of every scope unless the runtime has a [TestHarness], so a server never
 * gets here; the harness check is only a second lock.
 */
internal class NfTestImpl(private val session: ProjectSession) : NfTestApi {
    private val harness: TestHarness get() = session.tests.harness ?: throw LuaApiException("nf.test is only there in a test run")

    override fun case(caller: Caller, name: String, callback: LuaFunction) {
        try {
            session.tests.register(caller.scope, name, callback)
        } catch (e: LuaApiException) {
            session.scripts.unref(callback.ref)
            throw e
        }
    }

    override fun advance(caller: Caller, ticks: Long) {
        if (ticks < 1) throw LuaApiException("bad argument 'ticks' (at least 1 expected, got $ticks)")
        // Each call in those ticks has its own budget, as on a server; the test's own is for its own code.
        session.scripts.host!!.detached { harness.advance(Math.toIntExact(ticks)) }
    }

    override fun player(caller: Caller, name: String): LuaHandle.Player {
        if (!NAME.matches(name)) throw LuaApiException("bad argument 'name' (3 to 16 letters, digits and _ expected, got \"$name\")")
        return session.handle(harness.join(name))
    }

    override fun raise(caller: Caller, event: String, payload: LuaValue?): RaisedEvent {
        val raisable = RaisableEvents.byName[event] ?: throw LuaApiException("bad argument 'event' (no event \"$event\" can be raised)")
        return raise(raisable, event, payload)
    }

    private fun <P : LuaEvent> raise(raisable: Raisable<P>, name: String, payload: LuaValue?): RaisedEvent {
        val codec = raisable.event.payload
        val value: P = when {
            codec != null -> payload?.read(codec, "payload")
                ?: throw LuaApiException("event \"$name\" needs a payload: a table of ${fields(raisable)}")
            payload == null ->
                @Suppress("UNCHECKED_CAST")
                (NoPayload as P)
            else -> throw LuaApiException("event \"$name\" has no payload")
        }
        val cancelled = session.scripts.emit(raisable.path(value), value)
        return RaisedEvent(cancelled, value)
    }

    override fun click(caller: Caller, player: LuaHandle.Player, button: String, options: TestClickOptions?): Boolean {
        val ref = online(player)
        val hand = options?.hand ?: "main_hand"
        val block = options?.block?.let(::blockRef)
        if (options?.face != null && block == null) throw LuaApiException("bad argument 'options.face' (only with options.block)")
        val face = block?.let { options?.face ?: "up" }
        val item = session.platform.worldEntities.equipment(ref.uuid, hand)
        val events = session.runtime.events
        val clicked = if (button == "left") ClickButton.LEFT else ClickButton.RIGHT
        // As the server does: the click, then (a right one with an item, let through) the item's use.
        if (events.playerInteract(ref, clicked, block, face, item, hand)) return false
        if (clicked == ClickButton.RIGHT && item != null && events.playerUseItem(ref, item, hand)) return false
        return true
    }

    override fun release(caller: Caller, player: LuaHandle.Player, ticks: Long) {
        if (ticks < 0) throw LuaApiException("bad argument 'ticks' (0 or more expected, got $ticks)")
        val ref = online(player)
        val entities = session.platform.worldEntities
        val item = entities.equipment(ref.uuid, "main_hand") ?: entities.equipment(ref.uuid, "off_hand")
            ?: throw LuaApiException("${ref.name} has nothing in their hands to stop using")
        session.runtime.events.game.playerStopUsingItem(GameEvent.PlayerStopUsingItem(ref, item, Math.toIntExact(ticks)))
    }

    override fun input(caller: Caller, player: LuaHandle.Player, held: TestInputKeys) {
        val ref = online(player)
        session.runtime.events.game.playerInput(
            GameEvent.PlayerInput(
                ref,
                held.forward == true,
                held.backward == true,
                held.left == true,
                held.right == true,
                held.jump == true,
                held.sneak == true,
                held.sprint == true
            )
        )
    }

    override fun shown(caller: Caller, player: LuaHandle.Player, slot: IntegerOrChoice, options: TestShownOptions?): ItemData? {
        val ref = online(player)
        val index = when (slot) {
            is IntegerOrChoice.Integer -> Math.toIntExact(slot.value).also {
                if (it < 0) throw LuaApiException("bad argument 'slot' (0 or more expected, got $it)")
            }
            is IntegerOrChoice.Choice -> null
        }
        return harness.shown(ref.uuid, index, options?.menu == true)
    }

    /** [player] as the server knows them: one online, or an error (a test clicks only for someone there). */
    override fun connections(caller: Caller, url: String?): List<LuaHandle> = session.connections.history(url).map { it.handle }

    override fun accept(caller: Caller, connection: LuaHandle, options: TestAcceptOptions?) {
        far(connection).accept(options?.status?.let { Math.toIntExact(it) }, options?.headers.orEmpty(), options?.protocol)
    }

    override fun receive(caller: Caller, connection: LuaHandle, text: String) = far(connection).receive(text)

    override fun sent(caller: Caller, connection: LuaHandle.WebSocket): List<String> = recorded(connection).sent.toList()

    override fun disconnect(caller: Caller, connection: LuaHandle, options: TestDisconnectOptions?) {
        val code = options?.code?.let { Math.toIntExact(it) }
        if (code != null && code !in CLOSE_CODES) throw LuaApiException("bad argument 'options.code' (1000 to 4999 expected, got $code)")
        far(connection).disconnect(code, options?.reason, options?.error)
    }

    override fun request(caller: Caller, method: String, path: String, options: TestRequestOptions?): TestWebResponse {
        val verb = method.uppercase()
        if (!VERB.matches(verb)) throw LuaApiException("bad argument 'method' (an HTTP method expected, got \"$method\")")
        if (!path.startsWith("/")) throw LuaApiException("bad argument 'path' (a path starting with / expected, got \"$path\")")
        val headers = options?.headers.orEmpty().mapKeys { it.key.lowercase() }
        var answer: WebAnswer? = null
        var head = false
        val call = WebCall(
            verb,
            path.substringBefore('?'),
            path.substringAfter('?', "").takeIf { '?' in path },
            headers,
            options?.body.orEmpty().toByteArray(StandardCharsets.UTF_8),
            options?.ip ?: "127.0.0.1"
        ) { given, isHead ->
            answer = given
            head = isHead
        }
        session.web.request(call)
        // Answered later: the server runs on, as a client would wait, until the handler answers or times out.
        val limit = session.config.web.handlerTimeoutSeconds * TICKS_PER_SECOND + 1
        var waited = 0
        while (!call.isAnswered && waited++ < limit) session.scripts.host!!.detached { harness.advance(1) }
        val got = answer ?: WebAnswer.text(504, "never answered")
        return TestWebResponse(got.status.toLong(), got.headers, if (head) "" else String(got.body, StandardCharsets.UTF_8))
    }

    override fun oauthToken(caller: Caller, flow: LuaHandle.OAuthFlow, response: LuaValue) {
        val found = session.oauth.find(flow.number) ?: throw LuaApiException("bad argument 'flow' (it has gone)")
        found.testToken = response.json() as? JsonObject ?: throw LuaApiException("bad argument 'response' (a table of fields expected)")
    }

    override fun stubWasm(caller: Caller, module: String, stub: TestWasmStub) {
        harness
        val scope = caller.scope
        session.wasm.stub(scope, session.wasm.resolve(scope, module, "nf.test.stub_wasm"), stub)
    }

    // ---- the test's network: its other servers, which the test plays ---------------

    private val link get() = session.runtime.network

    /** The test's server [name], added to the network if it isn't in it yet. */
    private fun peer(name: String): NetworkLink.TestPeer {
        harness
        if (!NetworkNames.isServer(name)) {
            throw LuaApiException("bad argument 'server' (a server's name expected: letters, digits, _ . -; got \"$name\")")
        }
        if (name == link.name) throw LuaApiException("bad argument 'server' (\"$name\" is this server)")
        return link.peer(name)
    }

    private fun full(caller: Caller, name: String, what: String) = session.network.resolve(caller.scope.namespace, name, what)

    override fun networkServer(caller: Caller, name: String) {
        peer(name)
    }

    override fun networkPublish(caller: Caller, server: String, channel: String, value: LuaValue) {
        if (value.isNil) throw LuaApiException("bad argument 'value' (a value expected, got nil)")
        val json = value.json("value")
        peer(server).send(ServerFrame.Publish(full(caller, channel, "nf.test.network_publish"), json))
    }

    override fun networkReceived(caller: Caller, server: String, channel: String?): List<TestNetworkMessage> {
        val peer = peer(server)
        val wanted = channel?.let { full(caller, it, "nf.test.network_received") }
        wanted?.let(peer::listen)
        val reader = caller.scope.namespace
        return peer.received.filter { wanted == null || it.channel == wanted }
            .map { TestNetworkMessage(session.network.spell(it.channel, reader), it.value, it.from) }
    }

    override fun networkAnswer(caller: Caller, server: String, channel: String, callback: LuaFunction) {
        try {
            session.network.answer(caller.scope, peer(server).name, full(caller, channel, "nf.test.network_answer"), callback.ref)
        } catch (e: LuaApiException) {
            session.scripts.unref(callback.ref)
            throw e
        }
    }

    override fun networkRequest(caller: Caller, server: String, channel: String, value: LuaValue): Any? {
        if (value.isNil) throw LuaApiException("bad argument 'value' (a value expected, got nil)")
        val peer = peer(server)
        val id = link.nextId()
        peer.send(ServerFrame.Request(id, full(caller, channel, "nf.test.network_request"), link.name!!, value.json("value")))
        val host = session.scripts.host!!
        val most = session.config.network.requestTimeoutSeconds * TICKS_PER_SECOND
        repeat(most) {
            peer.replies.remove(id)?.let { reply ->
                reply.error?.let { throw LuaApiException("nf.test.network_request: the request failed: $it") }
                return reply.value
            }
            host.detached { harness.advance(1) }
        }
        throw LuaApiException("nf.test.network_request: this server didn't answer within $most ticks")
    }

    override fun networkWrite(caller: Caller, server: String, map: String, key: String, value: LuaValue) {
        val json = if (value.isNil) null else value.json("value")
        val op = if (json == null) MapOp.DELETE else MapOp.SET
        peer(server).send(ServerFrame.MapWrite(0, full(caller, map, "nf.test.network_write"), key, op, json))
    }

    override fun networkJoin(caller: Caller, server: String, name: String): NetworkPlayer {
        if (!NAME.matches(name)) throw LuaApiException("bad argument 'name' (3 to 16 letters, digits and _ expected, got \"$name\")")
        val peer = peer(server)
        val id = testId(name)
        link.place(id, name, peer.name)
        return NetworkPlayer(id, name, peer.name)
    }

    override fun networkQuit(caller: Caller, name: String) {
        harness
        link.leave(testId(name))
    }

    override fun networkSend(caller: Caller, server: String, name: String, data: LuaValue): LuaHandle.Player {
        if (!NAME.matches(name)) throw LuaApiException("bad argument 'name' (3 to 16 letters, digits and _ expected, got \"$name\")")
        val peer = peer(server)
        val json = if (data.isNil) null else data.json("data")
        val id = testId(name)
        // They're on the sending server first, as a player sent from it is.
        link.place(id, name, peer.name)
        peer.send(ServerFrame.Send(0, id, link.name!!, json))
        val joined = session.platform.players.find(name) ?: throw LuaApiException("$name couldn't be sent here")
        return session.handle(joined)
    }

    /** A test player's UUID from their name, as the fake server makes it. */
    private fun testId(name: String) = UUID.nameUUIDFromBytes(name.toByteArray()).toString()

    /** The connection [handle] names, from `nf.test.connections`. */
    private fun recorded(handle: LuaHandle): Connection = session.connections.recorded(handle)
        ?: throw LuaApiException("bad argument 'connection' (a connection scripts opened in this test expected)")

    /** Its far end, which the test plays. */
    private fun far(handle: LuaHandle): TestConnections.Far {
        val connection = recorded(handle)
        val transport = session.http.connections as? TestConnections ?: throw LuaApiException("nf.test is only there in a test run")
        // One the owner's limits refused never reached the transport: it's already ending.
        return transport.far(connection.sink) ?: throw LuaApiException("bad argument 'connection' (it never connected: it ended \"limit\")")
    }

    private fun online(player: LuaHandle.Player): PlayerRef {
        val uuid = player.uuidOrNull() ?: throw LuaApiException("bad argument 'player' (${player.id} isn't a player's id)")
        return session.platform.players.get(uuid) ?: throw LuaApiException("bad argument 'player' (they aren't online)")
    }

    /** The block a click is on, as the server says it: what's there now, in a loaded chunk. */
    private fun blockRef(block: LuaHandle.Block): BlockRef {
        val at = BlockPosition.unpack(block.position)
        val state = session.platform.blocks.get(block.world, at.x, at.y, at.z)?.state
            ?: throw LuaApiException("bad argument 'options.block' (its chunk isn't loaded)")
        return BlockRef(block.world, at.x, at.y, at.z, BlockState.parse(state)?.id ?: state, state)
    }

    private fun fields(raisable: Raisable<*>) = raisable.event.payload?.fields?.keys?.sorted()?.joinToString(", ").orEmpty()

    private companion object {
        val CLOSE_CODES = 1000..4999
        val VERB = Regex("^[A-Z]{1,16}$")
        const val TICKS_PER_SECOND = 20
        val NAME = Regex("[A-Za-z0-9_]{3,16}")
    }
}
