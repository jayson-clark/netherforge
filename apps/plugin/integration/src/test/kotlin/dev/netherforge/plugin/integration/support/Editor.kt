package dev.netherforge.plugin.integration.support

import dev.netherforge.format.bridge.Bridge
import dev.netherforge.format.bridge.BridgeEvent
import dev.netherforge.format.bridge.BridgeMethod
import dev.netherforge.format.bridge.CommandParams
import dev.netherforge.format.bridge.HelloParams
import dev.netherforge.format.bridge.HelloResult
import dev.netherforge.format.bridge.JsonRpc
import dev.netherforge.format.bridge.Log
import dev.netherforge.format.bridge.Problems
import dev.netherforge.format.bridge.ProfileSample
import dev.netherforge.format.bridge.ReloadParams
import dev.netherforge.format.bridge.ReloadResult
import dev.netherforge.format.bridge.RpcFrame
import dev.netherforge.format.bridge.RpcMessage
import dev.netherforge.format.bridge.RpcNotification
import dev.netherforge.format.bridge.RpcRequest
import dev.netherforge.format.bridge.RpcResponse
import dev.netherforge.format.bridge.ScriptError
import dev.netherforge.format.bridge.SourceRef
import dev.netherforge.format.bridge.Status
import kotlinx.serialization.json.JsonPrimitive
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The editor's end of one bridge connection, speaking JSON-RPC as the editor
 * does: it accepts the plugin's hello, and hands a scenario the hello's
 * params, notifications' params and each console entry on its own. Frames
 * that arrive while waiting for another are kept in [seen], so a later
 * [next] still finds them.
 *
 * A wait that times out says what did arrive: the last frames, each with
 * its content, and for [logged] and [line] the log lines that came closest
 * (the same first field), field by field.
 */
class Editor(socket: Socket) {
    /** What a request got back: whether it succeeded and, if not, why. */
    data class Answer(val ok: Boolean, val error: String?, val code: Int?)

    private val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))

    /** Frames as they arrive, and [CLOSED] when the plugin hangs up. */
    private val frames = LinkedBlockingQueue<Any>()
    private var nextId = 1

    /** Whether [CLOSED] has been taken from [frames]: nothing more can arrive. */
    private var closed = false

    /** The last frames taken from [frames], matched or not, for a failure's message. */
    private val recent = ArrayDeque<Any>()

    /** The last log lines, matched or not: where a failed [logged] looks for the ones that came closest. */
    private val recentLogs = ArrayDeque<Log>()

    init {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        Thread {
            runCatching {
                while (true) {
                    when (val message = (JsonRpc.decode(reader.readLine() ?: break) as RpcFrame.Single).message) {
                        is RpcRequest -> {
                            val hello = Bridge.hello.decodeParams(message.params)
                            write(RpcResponse.ok(message.id, Bridge.hello.encodeResult(HelloResult(Bridge.PROTOCOL))))
                            frames += hello
                        }
                        is RpcNotification ->
                            if (message.method == Bridge.console.name) {
                                frames.addAll(Bridge.console.decode(message.params))
                            } else if (message.method == Bridge.profiler.name) {
                                frames.addAll(Bridge.profiler.decode(message.params))
                            } else {
                                frames += Bridge.events.single { it.name == message.method }.decode(message.params)!!
                            }
                        else -> frames += message
                    }
                }
            }
            frames += CLOSED
        }.apply {
            isDaemon = true
            start()
        }
    }

    /** Frames that arrived while waiting for something else, oldest first. */
    val seen = mutableListOf<Any>()

    private fun write(message: RpcMessage) {
        synchronized(writer) {
            writer.write(JsonRpc.line(message) + "\n")
            writer.flush()
        }
    }

    private fun remember(frame: Any) {
        recent.addLast(frame)
        if (recent.size > RECENT_FRAMES) recent.removeFirst()
        if (frame is Log) {
            recentLogs.addLast(frame)
            if (recentLogs.size > RECENT_LOGS) recentLogs.removeFirst()
        }
        if (frame === CLOSED) closed = true
    }

    /**
     * The first frame [matching] accepts, among those [seen] already or arriving within [seconds]. On a timeout (or
     * once the plugin has hung up, when nothing more can come) it fails naming [what] it waited for, with [closest]'s
     * account of what came near and the last frames that arrived.
     */
    fun next(seconds: Long = WAIT_SECONDS, what: String = "a frame", closest: () -> String = { "" }, matching: (Any) -> Boolean): Any {
        seen.firstOrNull(matching)?.let {
            seen.remove(it)
            return it
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (System.nanoTime() < deadline && !closed) {
            val frame = frames.poll(100, TimeUnit.MILLISECONDS) ?: continue
            remember(frame)
            if (matching(frame)) return frame
            seen += frame
        }
        val why = if (closed) "the plugin closed the bridge before" else "nothing within ${seconds}s:"
        val near = closest()
        fail(
            buildString {
                append("$why $what\n")
                if (near.isNotEmpty()) append(near).append('\n')
                append("the last ${recent.size} frames, oldest first:\n")
                recent.forEach { append("  ").append(describe(it)).append('\n') }
            }
        )
    }

    /** Sends a notification: the debugger's DAP messages ([Bridge.dap]) are the editor's only ones. */
    fun <P> notify(event: BridgeEvent<P>, params: P) = write(event.notification(params))

    /** Sends [method] with [params] and waits for its answer, and its result when it succeeded. */
    fun <P, R> request(method: BridgeMethod<P, R>, params: P, seconds: Long = WAIT_SECONDS): Pair<Answer, R?> {
        val (response, answer) = send(method.request(nextId++, params), seconds)
        return answer to response.result?.let(method::decodeResult)
    }

    /** Sends [method] whatever it is, known or not, and waits for its answer. */
    fun call(method: String, seconds: Long = WAIT_SECONDS): Answer = send(RpcRequest(JsonPrimitive(nextId++), method), seconds).second

    private fun send(request: RpcRequest, seconds: Long): Pair<RpcResponse, Answer> {
        write(request)
        val response = next(seconds, "the answer to ${request.method} #${request.id}") {
            it is RpcResponse && it.id == request.id
        } as RpcResponse
        return response to Answer(response.error == null, response.error?.message, response.error?.code)
    }

    /** Runs a console command, which must be accepted. */
    fun run(line: String) {
        val (answer, _) = request(Bridge.command, CommandParams(line))
        assertTrue(answer.ok, "$line: ${answer.error}")
    }

    /** Runs a console command, whatever it answers. */
    fun tryRun(line: String): Answer = request(Bridge.command, CommandParams(line)).first

    /** Waits for a script's `log(...)` of exactly [parts], tab-separated as `log` joins them. */
    fun logged(vararg parts: String, seconds: Long = WAIT_SECONDS): Log {
        val want = parts.joinToString("\t")
        return next(seconds, "the log line ${fields(want)}", { closest(parts.toList()) }) { it is Log && it.message == want } as Log
    }

    /**
     * Waits for a script's `log(first, ...)`, a line whose first field is [first], and answers the fields after it:
     * what a step's probe reports.
     */
    fun line(first: String, seconds: Long = WAIT_SECONDS): List<String> {
        val log = next(seconds, "a log line starting ${fields(first)}", { closest(listOf(first)) }) {
            it is Log && it.message.substringBefore('\t') == first
        } as Log
        return log.message.split('\t').drop(1)
    }

    /** The logged lines with [want]'s first field, and how each differs from [want]; or the last lines when none has it. */
    private fun closest(want: List<String>): String {
        val same = recentLogs.filter { it.message.substringBefore('\t') == want.first() }
        if (same.isEmpty()) {
            val last = recentLogs.takeLast(CLOSEST_LINES)
            return "no log line started \"${want.first()}\"; the last ${last.size}:\n" +
                last.joinToString("\n") { "  ${fields(it.message)}" }
        }
        return "the log lines starting \"${want.first()}\":\n" + same.takeLast(CLOSEST_LINES).joinToString("\n") { log ->
            val got = log.message.split('\t')
            if (want.size == 1) return@joinToString "  ${fields(log.message)}"
            val differs = (0 until maxOf(got.size, want.size)).filter { got.getOrNull(it) != want.getOrNull(it) }
            "  ${fields(log.message)}" + differs.joinToString("", prefix = if (differs.isEmpty()) "" else " ") {
                "[field $it: \"${got.getOrNull(it) ?: "(none)"}\", not \"${want.getOrNull(it) ?: "(none)"}\"]"
            }
        }
    }

    /** Reloads [paths] as a save in the editor does, and answers what reloaded. */
    fun reload(vararg paths: String): ReloadResult {
        val (answer, result) = request(Bridge.reload, ReloadParams(paths.toList()))
        assertTrue(answer.ok, answer.error)
        return result!!
    }

    /**
     * Waits until everything the server reported before now has arrived (into [seen], unless a wait took it): a
     * request the main thread answers, whose answer is sent after whatever the main thread sent before it. A server
     * the debugger holds refuses it at once, which is as good: its main thread sends nothing meanwhile. Nothing to
     * wait for once the plugin has hung up.
     */
    fun settle() {
        if (closed) return
        val request = Bridge.instances.request(nextId++, Unit)
        runCatching { write(request) }.onFailure { return }
        next(what = "the answer to the settling request") { it === CLOSED || (it is RpcResponse && it.id == request.id) }
        while (true) {
            val frame = frames.poll() ?: break
            remember(frame)
            seen += frame
        }
    }

    /** Fails if anything the server has reported matches [predicate] and no wait took it; [what] it would be. */
    fun assertNone(what: String, predicate: (Any) -> Boolean) {
        settle()
        val found = seen.filter(predicate)
        if (found.isNotEmpty()) fail("$what:\n" + found.joinToString("\n") { "  ${describe(it)}" })
    }

    /** The script errors nobody waited for, taken from [seen] (each is reported once); [settle] first, unless [settled]. */
    fun takeScriptErrors(settled: Boolean = false): List<ScriptError> {
        if (!settled) settle()
        val errors = seen.filterIsInstance<ScriptError>()
        seen.removeAll { it is ScriptError }
        return errors
    }

    companion object {
        /** What [next] sees once the plugin has closed the connection. */
        val CLOSED = Any()

        private const val RECENT_FRAMES = 40
        private const val RECENT_LOGS = 400
        private const val CLOSEST_LINES = 8

        /** A log line's tab-separated fields, as a list. */
        private fun fields(message: String) = message.split('\t').joinToString(", ", "[", "]") { "\"$it\"" }

        private fun at(source: SourceRef?) = source?.let { " at ${it.file}" + (it.line?.let { line -> ":$line" } ?: "") } ?: ""

        /** A frame in a line: what it says, not only what it is. */
        fun describe(frame: Any): String = when (frame) {
            CLOSED -> "(the plugin closed the bridge)"
            is Log -> "log ${frame.level.name.lowercase()}${at(frame.source)}: ${fields(frame.message)}"
            is ScriptError -> "script error${at(frame.source)}: ${frame.message}"
            is Problems -> "problems: " + frame.problems.joinToString(", ", "[", "]") { "${it.code} ${it.file}: ${it.message}" }
            is RpcResponse -> "answer to #${frame.id}: " + (frame.error?.let { "error ${it.code} ${it.message}" } ?: "ok")
            is HelloParams -> "hello: Minecraft ${frame.minecraft}, protocol ${frame.protocol}"
            is ProfileSample -> "profiler sample of ${frame.ticks.size} ticks"
            is Status -> "status: ${frame.players.size} players, ${frame.instances} instances"
            else -> "${frame::class.simpleName}: ${frame.toString().take(300)}"
        }
    }
}
