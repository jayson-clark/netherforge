package dev.netherforge.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Two recorded connections, line for line as they cross the wire (`testdata/session.json`): the challenge, the
 * hello and its proof, then every frame either way, sealed. The Rust runtime's tests read the same file, so the two
 * ends agree on every frame's JSON, the proof and the keys, and a sealed line's bytes. `UPDATE_GOLDEN=1` writes it.
 */
class RecordedSessionTest {
    private val file = File("testdata/session.json")

    private fun value(text: String): JsonElement = Json.parseToJsonElement(text)

    private val hubNonce = "0f1e2d3c4b5a69788796a5b4c3d2e1f00112233445566778"
    private val serverNonce = "8877665544332211ffeeddccbbaa99887766554433221100"

    private val serverFrames: List<ServerFrame> = listOf(
        ServerFrame.Status(accepting = true, state = Lifecycle.READY),
        ServerFrame.Status(accepting = false, state = Lifecycle.DRAINING, idle = true, startupMillis = 4200, tickMillis = 12.5),
        ServerFrame.Listen(listOf("shop:sales", "lobby:queue/eu"), listOf("shop:prices", "netherforge:bans")),
        ServerFrame.Publish("shop:sales", value("""{"item":"apple","price":2.5,"tags":["red",1,true,null]}""")),
        ServerFrame.Publish("shop:sales", value("\"hello\""), to = "survival"),
        ServerFrame.Publish("shop:sales", value("42"), group = "arena"),
        ServerFrame.Request(7, "shop:price", "survival", value("""{"item":"apple"}""")),
        ServerFrame.Request(8, "shop:price", value = value("1"), group = "arena"),
        ServerFrame.Request(9, "shop:price", value = value("true"), admin = "matchmaker"),
        ServerFrame.Reply(3, value("""{"price":2}""")),
        ServerFrame.Reply(4, error = "nothing on lobby answers"),
        ServerFrame.Reply(5),
        ServerFrame.MapWrite(10, "shop:prices", "apple", MapOp.SET, value("2"), expiresMillis = 60000),
        ServerFrame.MapWrite(11, "shop:prices", "apple", MapOp.DELETE),
        ServerFrame.MapWrite(12, "shop:stock", "apples", MapOp.ADD, amount = 5.0),
        ServerFrame.MapWrite(13, "shop:locks", "chest-1", MapOp.INSERT, value("\"lobby\""), expiresMillis = 1000),
        ServerFrame.Send(14, listOf("11111111-2222-3333-4444-555555555555"), "survival", value("""{"kit":"starter"}""")),
        ServerFrame.Send(
            15,
            listOf("11111111-2222-3333-4444-555555555555", "66666666-7777-8888-9999-000000000000"),
            group = "arena",
            balance = Balance.FILL,
            waitMillis = 30000
        ),
        ServerFrame.Moderate(
            16,
            ModerationAction.BAN,
            "11111111-2222-3333-4444-555555555555",
            "Steve",
            "griefing",
            "package shop",
            1900000000000
        ),
        ServerFrame.Moderate(17, ModerationAction.UNMUTE, "11111111-2222-3333-4444-555555555555", "Steve", by = "package shop"),
        ServerFrame.Report("11111111-2222-3333-4444-555555555555", "Steve", "spam", value("""{"lines":3}"""), "Alex"),
        ServerFrame.Report("11111111-2222-3333-4444-555555555555", "Steve", "spam"),
        ServerFrame.PackLoaded("11111111-2222-3333-4444-555555555555", "a".repeat(40)),
        ServerFrame.Evacuate,
        ServerFrame.Leaving,
        ServerFrame.Ping
    )

    private val steve = PlayerState("11111111-2222-3333-4444-555555555555", "Steve", "lobby")

    private val hubFrames: List<HubFrame> = listOf(
        HubFrame.Message("shop:sales", "survival", value("""{"item":"apple","price":2.5}""")),
        HubFrame.Requested(1, "shop:price", "survival", value("""{"item":"apple"}""")),
        HubFrame.Reply(7, value("""{"price":2}""")),
        HubFrame.Reply(8, error = "survival isn't connected to the network"),
        HubFrame.Reply(9),
        HubFrame.MapSnapshot("shop:prices", mapOf("apple" to value("2"), "pear" to value("""{"each":3}"""))),
        HubFrame.MapChanged("shop:prices", "apple", value("3"), "survival", MapCause.SET),
        HubFrame.MapChanged("shop:prices", "apple", null, null, MapCause.EXPIRED),
        HubFrame.MapResult(12, value("5")),
        HubFrame.MapResult(13, value("false")),
        HubFrame.MapResult(10, error = "package \"shop\" already keeps 10000 keys in its maps, the network's limit"),
        HubFrame.PlayerJoined(steve),
        HubFrame.PlayerJoined(PlayerState("66666666-7777-8888-9999-000000000000", "Alex", "survival", "eu-proxy", "b".repeat(40))),
        HubFrame.PlayerMoved(steve.id, "survival", "lobby"),
        HubFrame.PlayerMoved(steve.id, "lobby"),
        HubFrame.PlayerLeft(steve.id),
        HubFrame.Server(ServerState("survival", true, "worlds", 50, true, 3, Lifecycle.READY, false, 5100, 7.5)),
        HubFrame.Server(ServerState("survival", false)),
        HubFrame.Servers(listOf(ServerState("arena-1", true, "arena", 8, false, 8, Lifecycle.STARTING, idle = true))),
        HubFrame.Arriving(steve.id, "survival", value("""{"kit":"starter"}""")),
        HubFrame.Arriving(steve.id, "survival"),
        HubFrame.ArrivalCancelled(steve.id),
        HubFrame.SendResult(14, server = "survival"),
        HubFrame.SendResult(15, "no instance of arena has room for 2 players"),
        HubFrame.SendWaiting(15, 2),
        HubFrame.SendWaiting(15, 0, "finding a match"),
        HubFrame.Drain(true),
        HubFrame.Problem("\"shop\" isn't a channel"),
        HubFrame.ModerateResult(16),
        HubFrame.ModerateResult(17, "Steve wasn't muted"),
        HubFrame.PlayerPack(steve.id, "c".repeat(40)),
        HubFrame.Pong
    )

    private fun welcome(grants: Grants) = HubFrame.Welcome(
        "lobby",
        listOf(ServerState("lobby", true, players = 1, state = Lifecycle.STARTING), ServerState("survival", false)),
        listOf(steve),
        32768,
        grants,
        """{"deny":["wasm"]}""",
        true
    )

    /** One connection: its lines in order, each with who sent it and, for a sealed one, the frame it holds. */
    private fun session(server: String, secret: String, credential: String?, grants: Grants): JsonObject {
        val hubChannel = Channel.hub(secret, hubNonce, serverNonce, server, credential)
        val serverChannel = Channel.server(secret, hubNonce, serverNonce, server, credential)
        val lines = ArrayList<JsonObject>()
        fun plain(from: String, text: String) {
            lines += buildJsonObject {
                put("from", from)
                put("line", text)
            }
        }
        fun sealed(from: String, text: String) {
            val line = if (from == "hub") hubChannel.seal(text) else serverChannel.seal(text)
            lines += buildJsonObject {
                put("from", from)
                put("line", line)
                put("frame", text)
            }
        }
        plain("hub", Protocol.encode(HubFrame.Challenge(Protocol.VERSION, hubNonce)))
        val proof = Auth.proof(secret, hubNonce, server, serverNonce, credential)
        plain(
            "server",
            Protocol.encode(
                ServerFrame.Hello(
                    Protocol.VERSION,
                    server,
                    proof,
                    serverNonce,
                    credential,
                    group = if (credential == null) null else "lobbies",
                    capacity = if (credential == null) null else 100,
                    register = if (credential == null) null else Registration(null, 25566),
                    instance = "9f8e7d6c5b4a39281706f5e4d3c2b1a0",
                    state = Lifecycle.STARTING
                )
            )
        )
        sealed("hub", Protocol.encode(welcome(grants)))
        if (credential == null) {
            for (frame in hubFrames) sealed("hub", Protocol.encode(frame))
            for (frame in serverFrames) sealed("server", Protocol.encode(frame))
        } else {
            sealed("server", Protocol.encode(ServerFrame.Ping))
            sealed("hub", Protocol.encode(HubFrame.Pong))
        }
        return buildJsonObject {
            put("server", server)
            put("secret", secret)
            put("credential", credential?.let(::JsonPrimitive) ?: JsonNull)
            put("hubNonce", hubNonce)
            put("serverNonce", serverNonce)
            put("proof", proof)
            put("lines", JsonArray(lines))
        }
    }

    private fun recorded(): String {
        val standard = Grants.preset(
            Grants.STANDARD_ROLE,
            owns = listOf("lobby"),
            shared = listOf(Share("shop:*", setOf(Access.READ, Access.REQUEST)), Share("events:news", setOf(Access.WRITE))),
            presence = false,
            sendTo = listOf("survival", Grants.BACK),
            moderation = true
        )
        val sessions = JsonArray(
            listOf(
                session("lobby", "s3cret-shared-for-tests", null, Grants.TRUSTED),
                session("lobby", "s3cret-of-a-credential", "dev-lobby", standard)
            )
        )
        val pretty = Json { prettyPrint = true }
        return pretty.encodeToString(JsonElement.serializer(), buildJsonObject { put("sessions", sessions) }) + "\n"
    }

    @Test
    fun `the recorded sessions are what this protocol says, line for line`() {
        val now = recorded()
        if (System.getenv("UPDATE_GOLDEN") == "1") file.writeText(now)
        assertEquals(file.readText(), now, "testdata/session.json is stale: UPDATE_GOLDEN=1 rewrites it")
    }

    @Test
    fun `every recorded line reads back as the frame it was`() {
        val sessions = Json.parseToJsonElement(file.readText()).jsonObject["sessions"]!!.jsonArray
        for (session in sessions) {
            for (entry in session.jsonObject["lines"]!!.jsonArray) {
                val text = entry.jsonObject["frame"]?.jsonPrimitive?.content ?: entry.jsonObject["line"]!!.jsonPrimitive.content
                when (entry.jsonObject["from"]!!.jsonPrimitive.content) {
                    "hub" -> assertEquals(text, Protocol.encode(Protocol.decodeHubFrame(text)!!))
                    else -> assertEquals(text, Protocol.encode(Protocol.decodeServerFrame(text)!!))
                }
            }
        }
    }
}
