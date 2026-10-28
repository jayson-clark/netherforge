package dev.netherforge.plugin.debug

import dev.netherforge.format.bridge.Bridge
import dev.netherforge.plugin.NetherForgeRuntime
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.lua.LuaHost
import dev.netherforge.plugin.lua.Primitive
import kotlinx.serialization.json.JsonElement
import org.eclipse.lsp4j.debug.Breakpoint
import org.eclipse.lsp4j.debug.Capabilities
import org.eclipse.lsp4j.debug.ConfigurationDoneArguments
import org.eclipse.lsp4j.debug.ContinueArguments
import org.eclipse.lsp4j.debug.ContinueResponse
import org.eclipse.lsp4j.debug.ContinuedEventArguments
import org.eclipse.lsp4j.debug.DisconnectArguments
import org.eclipse.lsp4j.debug.ExceptionBreakpointsFilter
import org.eclipse.lsp4j.debug.InitializeRequestArguments
import org.eclipse.lsp4j.debug.NextArguments
import org.eclipse.lsp4j.debug.PauseArguments
import org.eclipse.lsp4j.debug.Scope
import org.eclipse.lsp4j.debug.ScopesArguments
import org.eclipse.lsp4j.debug.ScopesResponse
import org.eclipse.lsp4j.debug.SetBreakpointsArguments
import org.eclipse.lsp4j.debug.SetBreakpointsResponse
import org.eclipse.lsp4j.debug.SetExceptionBreakpointsArguments
import org.eclipse.lsp4j.debug.SetExceptionBreakpointsResponse
import org.eclipse.lsp4j.debug.Source
import org.eclipse.lsp4j.debug.StackFrame
import org.eclipse.lsp4j.debug.StackTraceArguments
import org.eclipse.lsp4j.debug.StackTraceResponse
import org.eclipse.lsp4j.debug.StepInArguments
import org.eclipse.lsp4j.debug.StepOutArguments
import org.eclipse.lsp4j.debug.StoppedEventArguments
import org.eclipse.lsp4j.debug.Thread
import org.eclipse.lsp4j.debug.ThreadsResponse
import org.eclipse.lsp4j.debug.Variable
import org.eclipse.lsp4j.debug.VariablesArguments
import org.eclipse.lsp4j.debug.VariablesResponse
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer
import org.eclipse.lsp4j.jsonrpc.MessageConsumer
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.debug.DebugRemoteEndpoint
import org.eclipse.lsp4j.jsonrpc.debug.json.DebugMessageJsonHandler
import org.eclipse.lsp4j.jsonrpc.messages.RequestMessage
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.eclipse.lsp4j.jsonrpc.services.ServiceEndpoints
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.floor

/**
 * The debugger on a dev server: breakpoints, stepping and variables in the
 * project's scripts, spoken as the Debug Adapter Protocol to the editor over
 * the bridge's `dap` channel ([Bridge.dap]). The plugin is the debug adapter;
 * lsp4j's DAP types and endpoint read, dispatch and write the messages.
 *
 * Runtime-level, like the profiler: it outlives sessions, and each new Lua
 * state is told the breakpoints before any script runs in it ([sync]).
 *
 * **Stopping freezes the tick.** The Lua side (`prelude/debugger.lua`) stops
 * from the guard's one hook, on the server's main thread, inside whatever
 * call into a script was running, by calling the `debug.wait` primitive,
 * which doesn't return until the editor says to go on: nothing else on the
 * main thread runs meanwhile (no tick, no event, no request the bridge hands
 * the main thread: those are refused while paused, [refusal]). The held
 * thread pumps this debugger's own queue instead: what the editor asks that
 * only Lua can answer (the stack, scopes, variables) goes back to Lua as a
 * command, and once a second the platform is told the server is alive
 * ([dev.netherforge.plugin.platform.PauseOps.hold]: Paper's watchdog, and an
 * action bar that keeps players' connections from timing out). The time
 * spent paused is left out of the runtime's clock ([pausedNanos]), so no
 * script's time limit, cost or profile counts it, and the budget never moves
 * while nothing runs.
 *
 * DAP messages arrive on the bridge's thread, which never waits for the main
 * thread: configuration (breakpoints, break on errors, attach, detach, pause)
 * is kept here under [lock] and taken by the Lua state at its next [sync]
 * (or at once by a paused one), and requests about a stop are queued for the
 * held main thread and answered when it has.
 */
class Debugger(
    private val runtime: NetherForgeRuntime,
    /** Sends one DAP message to the editor. Any thread. */
    private val send: (JsonElement) -> Unit,
    /** How often the held main thread tells the platform the server is alive. */
    private val holdEveryMillis: Long = HOLD_EVERY_MILLIS
) {
    /** Why and where the server is stopped. */
    data class Stop(val reason: String, val file: String, val line: Int, val text: String?) {
        val where: String get() = "$file:$line"
    }

    /** What the held main thread is asked to do. */
    private sealed class Command(val name: String, val arg: String?) {
        val id: Int = IDS.incrementAndGet()

        /** Something only Lua can answer ([LUA_ANSWERS]): the answer completes [answer]. */
        class Ask(name: String, arg: String?, val answer: CompletableFuture<String>) : Command(name, arg)

        /** Go on: `continue`, `next`, `stepIn`, `stepOut` or `detach`. */
        class Resume(name: String) : Command(name, null)
    }

    private val lock = Any()

    // Guarded by [lock]: what the editor configured, and its version.
    private var attached = false
    private val breakpoints = HashMap<String, Set<Int>>()
    private var errors = false
    private var pauseWanted = false
    private var version = 0

    /** Why and where the main thread is held, or null while it runs. Written under [lock] by the main thread. */
    @Volatile
    var stopped: Stop? = null
        private set

    /** What the held main thread is to do next. */
    private val inbox = LinkedBlockingQueue<Command>()

    // The main thread's own.
    private var appliedTo: LuaHost? = null
    private var appliedVersion = -1
    private var answering: Command.Ask? = null
    private var pausedAt = 0L

    /** Nanoseconds the server has been paused in all, by the runtime's base clock: left out of its clock. */
    @Volatile
    var pausedNanos = 0L
        private set

    // ---- DAP --------------------------------------------------------------------

    private val adapter = Adapter()
    private val json = DebugMessageJsonHandler(
        ServiceEndpoints.getSupportedMethods(IDebugProtocolServer::class.java) +
            ServiceEndpoints.getSupportedMethods(IDebugProtocolClient::class.java)
    )
    private val endpoint = DebugRemoteEndpoint(
        MessageConsumer { message -> send(Bridge.json.parseToJsonElement(json.serialize(message))) },
        ServiceEndpoints.toEndpoint(adapter)
    )
    private val client: IDebugProtocolClient = ServiceEndpoints.toServiceObject(endpoint, IDebugProtocolClient::class.java)

    init {
        json.methodProvider = endpoint
    }

    /** One DAP message from the editor, on the bridge's thread. */
    fun receive(message: JsonElement) {
        val parsed = try {
            json.parseMessage(message.toString())
        } catch (e: RuntimeException) {
            runtime.log.warn("The editor sent the debugger something that isn't a DAP message: ${e.message}")
            return
        }
        endpoint.consume(parsed)
        // DAP: the adapter says it's ready for breakpoints once it has answered `initialize`.
        if (parsed is RequestMessage && parsed.method == "initialize") client.initialized()
    }

    /**
     * Forgets the editor: no breakpoints, nothing to stop on, and a held
     * server goes on. The bridge calls it when its connection ends, so an
     * editor that quits (or crashes) while the server is paused never leaves
     * it paused.
     */
    fun detach() = synchronized(lock) {
        attached = false
        breakpoints.clear()
        errors = false
        pauseWanted = false
        version++
        if (stopped != null) inbox.add(Command.Resume(DETACH))
    }

    /** Why a request for the main thread is refused while the server is paused; null while it runs. */
    fun refusal(): String? = stopped?.let {
        "the dev server is paused at a breakpoint (${it.where}); continue it in the editor first"
    }

    // ---- the Lua state ------------------------------------------------------------

    /**
     * Brings [host] up to date with what the editor configured: on the main
     * thread, at the start of every tick and as soon as a session's Lua state
     * exists (before any of its scripts run).
     */
    fun sync(host: LuaHost) {
        val pause: Boolean
        val config = synchronized(lock) {
            pause = pauseWanted
            pauseWanted = false
            if (host === appliedTo && version == appliedVersion && !pause) return
            appliedTo = host
            appliedVersion = version
            Triple(attached, breakpoints.toMap(), errors)
        }
        val (on, lines, breakOnErrors) = config
        if (on) host.debugConfigure(lines, breakOnErrors, pause) else host.debugDetach()
    }

    /** The primitives the Lua side stops through; [host] is the session's Lua state. */
    fun primitives(host: () -> LuaHost): Map<String, Primitive> = mapOf(
        // (reason, file, line, text) for a new stop, nothing for the next command: holds the main thread
        // until there is one, and answers its name, id and argument.
        "debug.wait" to Primitive { lua ->
            if (lua.top >= 3 && lua.isString(1)) {
                begin(
                    Stop(
                        lua.toString(1)!!,
                        lua.toString(2)!!,
                        lua.toInteger(3).toInt(),
                        if (lua.top >= 4 &&
                            lua.isString(4)
                        ) {
                            lua.toString(4)
                        } else {
                            null
                        }
                    )
                )
            }
            val command = hold()
            lua.push(command.name)
            lua.push(command.id.toLong())
            if (command.arg != null) lua.push(command.arg) else lua.pushNil()
            3
        },
        // (id, text) answers the command asked; (id, nil, message) says why it couldn't.
        "debug.answer" to Primitive { lua ->
            val asked = answering
            if (asked != null && lua.toInteger(1).toInt() == asked.id) {
                answering = null
                if (lua.isString(2)) {
                    asked.answer.complete(lua.toString(2)!!)
                } else {
                    asked.answer.completeExceptionally(failed(lua.toString(3) ?: "the script's state couldn't be read"))
                }
            }
            0
        },
        // (handle): how the editor shows it, a line, and its facts, "name\tvalue" lines.
        "debug.handle" to Primitive { lua ->
            val (summary, facts) = describe(host().handleAt(lua, 1))
            lua.push(summary)
            lua.push(facts.joinToString("\n") { (name, value) -> "${line(name)}\t${line(value)}" })
            2
        }
    )

    private fun begin(stop: Stop) {
        pausedAt = runtime.clock()
        synchronized(lock) { stopped = stop }
        client.stopped(
            StoppedEventArguments().apply {
                reason = stop.reason
                description = when (stop.reason) {
                    "breakpoint" -> "Paused on breakpoint"
                    "exception" -> "Paused on script error"
                    "pause" -> "Paused"
                    else -> "Paused after step"
                }
                text = stop.text
                threadId = THREAD
                allThreadsStopped = true
            }
        )
    }

    /** Holds the main thread until there's something for it to do, keeping the server alive meanwhile. */
    private fun hold(): Command {
        var nextHold = 0L
        while (true) {
            val now = System.nanoTime()
            if (now >= nextHold) {
                holdServer()
                nextHold = now + TimeUnit.MILLISECONDS.toNanos(holdEveryMillis)
            }
            val command = inbox.poll(TimeUnit.NANOSECONDS.toMillis(nextHold - now).coerceAtLeast(1), TimeUnit.MILLISECONDS) ?: continue
            when (command) {
                is Command.Ask -> {
                    answering = command
                    return command
                }
                is Command.Resume -> {
                    end()
                    return command
                }
            }
        }
    }

    private fun holdServer() {
        val stop = stopped ?: return
        try {
            runtime.platform.pause.hold("Paused by the NetherForge debugger at ${stop.where}")
        } catch (e: Exception) {
            runtime.log.error("NetherForge couldn't keep the paused server alive", e)
        }
    }

    private fun end() {
        val unanswered = synchronized(lock) {
            stopped = null
            val left = ArrayList<Command>()
            inbox.drainTo(left)
            left.filterIsInstance<Command.Ask>()
        }
        for (ask in unanswered) ask.answer.completeExceptionally(failed(NOT_STOPPED))
        pausedNanos += runtime.clock() - pausedAt
        client.continued(
            ContinuedEventArguments().apply {
                threadId = THREAD
                allThreadsContinued = true
            }
        )
    }

    /** Asks the held main thread for what only Lua knows; fails when the server isn't stopped. */
    private fun ask(name: String, arg: String? = null): CompletableFuture<String> {
        val future = CompletableFuture<String>()
        synchronized(lock) {
            if (stopped == null) future.completeExceptionally(failed(NOT_STOPPED)) else inbox.add(Command.Ask(name, arg, future))
        }
        return future
    }

    /** Has the held main thread go on ([name] says how); false when it isn't stopped. */
    private fun resume(name: String): Boolean = synchronized(lock) {
        if (stopped == null) return false
        inbox.add(Command.Resume(name))
        true
    }

    /** Under [lock]: the configuration changed; a held Lua state takes it at once. */
    private fun configured(command: String? = null, arg: String? = null) {
        version++
        if (stopped != null && command != null) inbox.add(Command.Ask(command, arg, CompletableFuture()))
    }

    private fun breakpointLines() = breakpoints.flatMap { (file, lines) -> lines.sorted().map { "$file\t$it" } }.joinToString("\n")

    /**
     * A source's path as a script's chunk is named: the project path (`modules/m/init.lua`) or package path
     * (`library:modules/greetings/init.lua`), which is also what stacks, errors and the editor's documents say. An editor
     * that sees the file on disk says its absolute path (the package's folder beside the project, or in the package cache);
     * that's where it's mapped, the one place, so a breakpoint binds whichever way it's named.
     */
    internal fun sourcePath(path: String): String {
        val slashed = path.replace('\\', '/').removePrefix("./")
        val file = runCatching { Path.of(slashed) }.getOrNull()?.takeIf { it.isAbsolute } ?: return slashed
        return runtime.source.pathOf(file) ?: slashed
    }

    // ---- how handles show ---------------------------------------------------------

    /** A handle as the editor shows it: one line, and the facts it expands to. */
    private fun describe(handle: LuaHandle): Pair<String, List<Pair<String, String>>> {
        val keys = handle.keyValues.map { it.toString() }
        val generic = "${handle.luaClass}: ${keys.joinToString(", ")}" to keys.mapIndexed { i, key -> "key ${i + 1}" to key }
        return try {
            when (handle.luaClass) {
                "Player" -> {
                    val uuid = UUID.fromString(keys[0])
                    val player = runtime.platform.players.online().firstOrNull { it.uuid == uuid }
                    val at = runtime.platform.players.location(uuid)
                    "Player ${player?.name ?: "(offline) ${keys[0]}"}" to
                        listOfNotNull(player?.let { "name" to it.name }, "uuid" to keys[0]) + position(at)
                }
                "Centity" -> {
                    val instance = runtime.session.centities.find(keys[0])
                    if (instance == null) {
                        "Centity (gone) ${keys[0]}" to listOf("uuid" to keys[0])
                    } else {
                        "Centity ${instance.centity} ${keys[0].take(8)}" to
                            listOf("centity" to instance.centity, "uuid" to keys[0]) + position(instance.anchor)
                    }
                }
                "Node" -> {
                    val centity = runtime.session.centities.find(keys[0])?.centity ?: "(gone)"
                    "Node ${keys[1]} of $centity ${keys[0].take(8)}" to listOf("node" to keys[1], "centity" to centity, "uuid" to keys[0])
                }
                "World" -> "World ${keys[0]}" to listOf("name" to keys[0])
                else -> generic
            }
        } catch (e: RuntimeException) {
            generic
        }
    }

    private fun position(at: dev.netherforge.plugin.platform.Location?): List<Pair<String, String>> = if (at == null) {
        emptyList()
    } else {
        listOf("world" to at.world, "position" to "${floor(at.x * 100) / 100}, ${floor(at.y * 100) / 100}, ${floor(at.z * 100) / 100}")
    }

    // ---- the adapter: what the editor asks ----------------------------------------

    private inner class Adapter : IDebugProtocolServer {
        override fun initialize(args: InitializeRequestArguments): CompletableFuture<Capabilities> = done(
            Capabilities().apply {
                supportsConfigurationDoneRequest = true
                exceptionBreakpointFilters = arrayOf(
                    ExceptionBreakpointsFilter().apply {
                        filter = ERRORS
                        label = "Script errors"
                        description = "Pause where an error no pcall catches happens"
                        default_ = false
                    }
                )
            }
        )

        override fun attach(args: MutableMap<String, Any>?): CompletableFuture<Void> {
            synchronized(lock) {
                attached = true
                configured()
            }
            return DONE
        }

        override fun configurationDone(args: ConfigurationDoneArguments?): CompletableFuture<Void> = DONE

        override fun disconnect(args: DisconnectArguments?): CompletableFuture<Void> {
            detach()
            return DONE
        }

        override fun setBreakpoints(args: SetBreakpointsArguments): CompletableFuture<SetBreakpointsResponse> {
            val path = args.source?.path?.let(::sourcePath) ?: return failedFuture("setBreakpoints needs the source's path")
            val lines = args.breakpoints?.map { it.line } ?: args.lines?.toList() ?: emptyList()
            synchronized(lock) {
                if (lines.isEmpty()) breakpoints.remove(path) else breakpoints[path] = lines.toSet()
                configured(BREAKPOINTS, breakpointLines())
            }
            return done(
                SetBreakpointsResponse().apply {
                    breakpoints = lines.map { line ->
                        Breakpoint().apply {
                            isVerified = true
                            this.line = line
                            source = Source().apply {
                                this.path = path
                                name = path.substringAfterLast('/')
                            }
                        }
                    }.toTypedArray()
                }
            )
        }

        override fun setExceptionBreakpoints(args: SetExceptionBreakpointsArguments): CompletableFuture<SetExceptionBreakpointsResponse> {
            val on = args.filters?.contains(ERRORS) == true
            synchronized(lock) {
                errors = on
                configured(ERRORS, if (on) "1" else "0")
            }
            return done(SetExceptionBreakpointsResponse())
        }

        override fun threads(): CompletableFuture<ThreadsResponse> = done(
            ThreadsResponse().apply {
                threads = arrayOf(
                    Thread().apply {
                        id = THREAD
                        name = "Server thread"
                    }
                )
            }
        )

        override fun pause(args: PauseArguments?): CompletableFuture<Void> {
            synchronized(lock) {
                if (stopped == null) {
                    pauseWanted = true
                    configured()
                }
            }
            return DONE
        }

        override fun continue_(args: ContinueArguments?): CompletableFuture<ContinueResponse> {
            resume(CONTINUE)
            return done(ContinueResponse().apply { allThreadsContinued = true })
        }

        override fun next(args: NextArguments?) = step("next")

        override fun stepIn(args: StepInArguments?) = step("stepIn")

        override fun stepOut(args: StepOutArguments?) = step("stepOut")

        private fun step(name: String): CompletableFuture<Void> = if (resume(name)) DONE else failedFuture(NOT_STOPPED)

        override fun stackTrace(args: StackTraceArguments): CompletableFuture<StackTraceResponse> = ask(STACK).thenApply { text ->
            val all = rows(text).mapIndexed { i, (name, file, line) ->
                StackFrame().apply {
                    id = i + 1
                    this.name = name
                    if (file.isNotEmpty()) {
                        source = Source().apply {
                            path = file
                            this.name = file.substringAfterLast('/')
                        }
                    }
                    this.line = line.toIntOrNull() ?: 0
                    column = if (file.isNotEmpty()) 1 else 0
                }
            }
            val from = (args.startFrame ?: 0).coerceIn(0, all.size)
            val levels = args.levels?.takeIf { it > 0 } ?: all.size
            StackTraceResponse().apply {
                stackFrames = all.drop(from).take(levels).toTypedArray()
                totalFrames = all.size
            }
        }

        override fun scopes(args: ScopesArguments): CompletableFuture<ScopesResponse> =
            ask(SCOPES, args.frameId.toString()).thenApply { text ->
                ScopesResponse().apply {
                    scopes = rows(text).map { (name, reference) ->
                        Scope().apply {
                            this.name = name
                            variablesReference = reference.toInt()
                            isExpensive = false
                            if (name == "Locals") presentationHint = "locals"
                        }
                    }.toTypedArray()
                }
            }

        override fun variables(args: VariablesArguments): CompletableFuture<VariablesResponse> =
            ask(VARIABLES, args.variablesReference.toString()).thenApply { text ->
                VariablesResponse().apply {
                    variables = rows(text).map { (name, value, type, reference) ->
                        Variable().apply {
                            this.name = name
                            this.value = value
                            this.type = type
                            variablesReference = reference.toIntOrNull() ?: 0
                        }
                    }.toTypedArray()
                }
            }
    }

    companion object {
        /** The one thread scripts run on, as DAP names threads. */
        const val THREAD = 1

        /** The exception filter that stops on uncaught script errors. */
        const val ERRORS = "errors"

        const val HOLD_EVERY_MILLIS = 1000L

        private const val CONTINUE = "continue"
        private const val DETACH = "detach"
        private const val STACK = "stack"
        private const val SCOPES = "scopes"
        private const val VARIABLES = "variables"
        private const val BREAKPOINTS = "breakpoints"
        private const val NOT_STOPPED = "the server isn't paused"

        private val IDS = AtomicInteger()

        private fun <T> done(value: T): CompletableFuture<T> = CompletableFuture.completedFuture(value)

        /** A request without a result, answered. */
        private val DONE: CompletableFuture<Void> get() = CompletableFuture.completedFuture<Void>(null)

        private fun failed(message: String) = ResponseErrorException(ResponseError(ResponseErrorCode.RequestFailed, message, null))

        private fun <T> failedFuture(message: String): CompletableFuture<T> = CompletableFuture.failedFuture(failed(message))

        /** The Lua side's answer: lines of tab-separated fields, each with `\t`, `\n`, `\r` and `\\` escaped. */
        internal fun rows(text: String): List<List<String>> =
            if (text.isEmpty()) emptyList() else text.split('\n').map { line -> line.split('\t').map(::unescape) }

        private fun unescape(field: String): String {
            if ('\\' !in field) return field
            val out = StringBuilder(field.length)
            var i = 0
            while (i < field.length) {
                val c = field[i]
                if (c == '\\' && i + 1 < field.length) {
                    out.append(
                        when (field[i + 1]) {
                            't' -> '\t'
                            'n' -> '\n'
                            'r' -> '\r'
                            else -> field[i + 1]
                        }
                    )
                    i += 2
                } else {
                    out.append(c)
                    i++
                }
            }
            return out.toString()
        }

        /** A field of a line the Lua side reads: no tabs or newlines. */
        private fun line(text: String) = text.replace('\t', ' ').replace('\n', ' ')
    }
}
