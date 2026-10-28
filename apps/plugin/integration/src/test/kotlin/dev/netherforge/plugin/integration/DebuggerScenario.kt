package dev.netherforge.plugin.integration

import dev.netherforge.format.bridge.BotJoinParams
import dev.netherforge.format.bridge.BotPackAnswer
import dev.netherforge.format.bridge.BotsExtension
import dev.netherforge.format.bridge.Bridge
import dev.netherforge.format.bridge.Log
import dev.netherforge.plugin.integration.support.PaperServer
import dev.netherforge.plugin.integration.support.Scenario
import dev.netherforge.plugin.integration.support.VersionIndependent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The debugger on a real server: a breakpoint holds Paper's main thread for
 * longer than its watchdog waits and longer than a client waits to hear from
 * the server, and the server and its player come through it. The editor
 * reads the stack meanwhile, `ping` is answered, and the main thread's
 * requests are refused with why. DAP is spoken as raw JSON here, over the
 * bridge's `dap` channel, as the editor's client sends it.
 */
@VersionIndependent
class DebuggerScenario : Scenario("debugger") {
    override val server = PaperServer(onlineMode = true)

    private var seq = 0

    /** Sends DAP request [command]; answers its `seq`. */
    private fun request(command: String, arguments: JsonObject = JsonObject(emptyMap())): Int {
        val id = ++seq
        editor.notify(
            Bridge.dap,
            buildJsonObject {
                put("seq", id)
                put("type", "request")
                put("command", command)
                put("arguments", arguments)
            }
        )
        return id
    }

    /** The DAP response to request [id], which must have succeeded; its body. */
    private fun answer(id: Int): JsonObject {
        val response = editor.next {
            it is JsonObject && it["type"] == JsonPrimitive("response") && it["request_seq"] == JsonPrimitive(id)
        } as JsonObject
        assertEquals(JsonPrimitive(true), response["success"], "$response")
        return response["body"] as? JsonObject ?: JsonObject(emptyMap())
    }

    private fun event(name: String): JsonObject =
        editor.next { it is JsonObject && it["type"] == JsonPrimitive("event") && it["event"] == JsonPrimitive(name) } as JsonObject

    @Test
    @Order(1)
    fun `a breakpoint holds the server where the script is, and the editor reads the stack`() {
        editor.run("difficulty peaceful")
        val (joined, _) = editor.request(BotsExtension.join, BotJoinParams("Watcher", resourcePack = BotPackAnswer.DECLINE))
        assertTrue(joined.ok, joined.error)
        answer(request("initialize", buildJsonObject { put("adapterID", "netherforge") }))
        answer(request("attach"))
        val breakpoints = answer(
            request(
                "setBreakpoints",
                buildJsonObject {
                    put("source", buildJsonObject { put("path", "modules/it_debug/init.lua") })
                    put("breakpoints", buildJsonArray { add(buildJsonObject { put("line", 6) }) })
                }
            )
        )
        assertEquals(JsonPrimitive(true), breakpoints["breakpoints"]!!.jsonArray.single().jsonObject["verified"])
        answer(request("configurationDone"))

        val stopped = event("stopped")["body"]!!.jsonObject
        assertEquals("breakpoint", stopped["reason"]!!.jsonPrimitive.content)
        val frames = answer(request("stackTrace", buildJsonObject { put("threadId", 1) }))["stackFrames"] as JsonArray
        val top = frames.first().jsonObject
        assertEquals("modules/it_debug/init.lua", top["source"]!!.jsonObject["path"]!!.jsonPrimitive.content)
        assertEquals(6, top["line"]!!.jsonPrimitive.int)
    }

    @Test
    @Order(2)
    fun `held past the watchdog and a client's timeout, the server and its player run on`() {
        // Longer than Paper's watchdog warns after (10 s) and a client or the server's keep-alive waits (30 s).
        val until = System.nanoTime() + HELD_SECONDS * 1_000_000_000L
        while (System.nanoTime() < until) {
            assertTrue(editor.call(Bridge.ping.name).ok, "ping is answered while paused")
            Thread.sleep(5_000)
        }
        val refused = editor.call(Bridge.instances.name)
        assertFalse(refused.ok)
        assertTrue("paused at a breakpoint (modules/it_debug/init.lua:6)" in refused.error.orEmpty(), refused.error)

        answer(request("disconnect"))
        event("continued")
        editor.next { it is Log && it.message.startsWith("debugged") }
        val (listed, bots) = editor.request(BotsExtension.list, Unit)
        assertTrue(listed.ok, listed.error)
        assertEquals(listOf("Watcher"), bots!!.map { it.name }, "the player stayed connected")
        val log = Files.readString(server.folder.resolve("DebuggerScenario.log"))
        assertFalse("has not responded" in log || "has stopped responding" in log, "the watchdog stayed quiet")
    }

    private companion object {
        const val HELD_SECONDS = 40L
    }
}
