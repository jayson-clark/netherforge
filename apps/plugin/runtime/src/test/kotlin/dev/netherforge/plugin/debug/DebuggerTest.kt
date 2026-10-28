package dev.netherforge.plugin.debug
import dev.netherforge.format.bridge.Bridge
import dev.netherforge.format.bridge.HelloResult
import dev.netherforge.format.bridge.JsonRpc
import dev.netherforge.format.bridge.Log
import dev.netherforge.format.bridge.RpcFrame
import dev.netherforge.format.bridge.RpcNotification
import dev.netherforge.format.bridge.RpcRequest
import dev.netherforge.format.bridge.RpcResponse
import dev.netherforge.format.bridge.ScriptError
import dev.netherforge.format.project.FormatVersion
import dev.netherforge.format.project.LockFile
import dev.netherforge.format.project.LockKind
import dev.netherforge.format.project.LockedPackage
import dev.netherforge.format.project.PackageSource
import dev.netherforge.format.project.Packages
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.bridge.BridgeConfig
import dev.netherforge.plugin.lua.SandboxLimits
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.project.DiskProjectSource
import dev.netherforge.plugin.project.ProjectFiles
import org.eclipse.lsp4j.debug.ContinueArguments
import org.eclipse.lsp4j.debug.ContinuedEventArguments
import org.eclipse.lsp4j.debug.InitializeRequestArguments
import org.eclipse.lsp4j.debug.NextArguments
import org.eclipse.lsp4j.debug.PauseArguments
import org.eclipse.lsp4j.debug.ScopesArguments
import org.eclipse.lsp4j.debug.SetBreakpointsArguments
import org.eclipse.lsp4j.debug.SetExceptionBreakpointsArguments
import org.eclipse.lsp4j.debug.Source
import org.eclipse.lsp4j.debug.SourceBreakpoint
import org.eclipse.lsp4j.debug.StackFrame
import org.eclipse.lsp4j.debug.StackTraceArguments
import org.eclipse.lsp4j.debug.StepInArguments
import org.eclipse.lsp4j.debug.StepOutArguments
import org.eclipse.lsp4j.debug.StoppedEventArguments
import org.eclipse.lsp4j.debug.Variable
import org.eclipse.lsp4j.debug.VariablesArguments
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer
import org.eclipse.lsp4j.jsonrpc.MessageConsumer
import org.eclipse.lsp4j.jsonrpc.debug.DebugRemoteEndpoint
import org.eclipse.lsp4j.jsonrpc.debug.json.DebugMessageJsonHandler
import org.eclipse.lsp4j.jsonrpc.services.ServiceEndpoints
import org.junit.jupiter.api.Timeout
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The debugger, end to end: a test plays the editor over a real bridge socket,
 * speaking DAP through lsp4j's client side, against the fake platform. A
 * stop holds the test's own thread (it runs the server's ticks), so what the
 * editor does while the server is paused runs on a [Watcher] thread.
 */
@Timeout(60)
class DebuggerTest {

    /** The editor: answers the hello, and speaks DAP on the bridge's `dap` channel. */
    private class Editor(listener: ServerSocket) : IDebugProtocolClient {
        private val socket: Socket
        private val writer: BufferedWriter
        val stops = LinkedBlockingQueue<StoppedEventArguments>()
        val continued = LinkedBlockingQueue<ContinuedEventArguments>()
        private val responses = LinkedBlockingQueue<RpcResponse>()
        private val hello = CountDownLatch(1)

        /** What scripts logged, and the script errors reported, as the editor's console hears them. */
        val logs: MutableList<String> = CopyOnWriteArrayList()
        val errors: MutableList<String> = CopyOnWriteArrayList()

        /** [logs] once the console has caught up with what the main thread did (it arrives on the bridge's thread). */
        fun logged(count: Int): List<String> {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (logs.size < count && System.nanoTime() < deadline) Thread.sleep(10)
            return logs.toList()
        }

        /** [errors] once one has arrived. */
        fun reported(): List<String> {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (errors.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
            return errors.toList()
        }

        private val json = DebugMessageJsonHandler(
            ServiceEndpoints.getSupportedMethods(IDebugProtocolServer::class.java) +
                ServiceEndpoints.getSupportedMethods(IDebugProtocolClient::class.java)
        )
        private val endpoint = DebugRemoteEndpoint(
            MessageConsumer { message ->
                writeLine(JsonRpc.line(Bridge.dap.notification(Bridge.json.parseToJsonElement(json.serialize(message)))))
            },
            ServiceEndpoints.toEndpoint(this)
        )

        /** The plugin's debug adapter, as DAP requests. */
        val dap: IDebugProtocolServer = ServiceEndpoints.toServiceObject(endpoint, IDebugProtocolServer::class.java)

        init {
            json.methodProvider = endpoint
            listener.soTimeout = 10_000
            socket = listener.accept()
            writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            Thread {
                runCatching {
                    while (true) {
                        val line = reader.readLine() ?: break
                        val message = (JsonRpc.decode(line) as? RpcFrame.Single)?.message ?: continue
                        when {
                            message is RpcRequest && message.method == Bridge.hello.name -> {
                                write(RpcResponse.ok(message.id, Bridge.hello.encodeResult(HelloResult(Bridge.PROTOCOL))))
                                hello.countDown()
                            }
                            message is RpcNotification && message.method == Bridge.dap.name ->
                                endpoint.consume(json.parseMessage(message.params.toString()))
                            message is RpcNotification && message.method == Bridge.console.name ->
                                for (entry in Bridge.console.decode(message.params)) {
                                    when (entry) {
                                        is Log -> if (entry.source != null) logs += entry.message
                                        is ScriptError -> errors += entry.message
                                    }
                                }
                            message is RpcResponse -> responses += message
                        }
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }
            // Nothing goes to the plugin before its hello is answered.
            assertTrue(hello.await(10, TimeUnit.SECONDS), "no hello within 10 s")
            dap.initialize(
                InitializeRequestArguments().apply {
                    clientID = "test"
                    adapterID = "netherforge"
                    linesStartAt1 = true
                }
            ).done()
            dap.attach(emptyMap()).done()
        }

        override fun stopped(args: StoppedEventArguments) {
            stops += args
        }

        override fun continued(args: ContinuedEventArguments) {
            continued += args
        }

        fun write(message: dev.netherforge.format.bridge.RpcMessage) = writeLine(JsonRpc.line(message))

        fun writeLine(line: String) = synchronized(writer) {
            writer.write(line + "\n")
            writer.flush()
        }

        fun close() = socket.close()

        fun breakpoints(path: String, vararg lines: Int) = dap.setBreakpoints(
            SetBreakpointsArguments().apply {
                source = Source().apply { this.path = path }
                breakpoints = lines.map { line -> SourceBreakpoint().apply { this.line = line } }.toTypedArray()
            }
        ).done()

        fun nextStop(): StoppedEventArguments = stops.poll(10, TimeUnit.SECONDS) ?: fail("the server didn't stop within 10 s")

        fun stack(): List<StackFrame> = dap.stackTrace(StackTraceArguments().apply { threadId = 1 }).done().stackFrames.toList()

        /** The top frame's (or [frame]'s) scope [name]'s variables, by name. */
        fun scope(name: String, frame: Int = 1): Map<String, Variable> {
            val scope = dap.scopes(ScopesArguments().apply { frameId = frame }).done().scopes.single { it.name == name }
            return variables(scope.variablesReference)
        }

        fun variables(reference: Int): Map<String, Variable> =
            dap.variables(VariablesArguments().apply { variablesReference = reference }).done().variables.associateBy { it.name }

        fun goOn() = dap.continue_(ContinueArguments().apply { threadId = 1 }).done()

        /** A bridge request the plugin answers on the main thread, sent raw; [pump] runs what the main thread was handed meanwhile. */
        fun instances(pump: () -> Unit = {}): RpcResponse {
            write(Bridge.instances.request(99, Unit))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (System.nanoTime() < deadline) {
                pump()
                responses.poll(20, TimeUnit.MILLISECONDS)?.let { return it }
            }
            fail("no answer within 10 s")
        }

        /** Runs [block] on a thread of its own, as an editor acts while the server's thread is held. */
        fun watch(block: Editor.() -> Unit): Watcher = Watcher(this, block)
    }

    private class Watcher(editor: Editor, block: Editor.() -> Unit) {
        @Volatile
        private var failure: Throwable? = null
        private val thread = Thread {
            try {
                editor.block()
            } catch (t: Throwable) {
                failure = t
                // Never leave the test's thread held.
                runCatching { editor.dap.disconnect(null).get(5, TimeUnit.SECONDS) }
            }
        }.apply {
            isDaemon = true
            start()
        }

        /** Waits for the editor's part to end, and fails as it did. */
        fun check() {
            thread.join(15_000)
            failure?.let { throw it }
            assertTrue(!thread.isAlive, "the editor's part is still running")
        }
    }

    private fun debugging(
        files: Map<String, Any>,
        sandbox: SandboxLimits = SandboxLimits(),
        clock: (() -> Long)? = null,
        test: (TestServer, Editor) -> Unit
    ) {
        ServerSocket(0).use { listener ->
            TestServer(files, bridge = BridgeConfig(listener.localPort, "secret"), sandbox = sandbox, clock = clock).use { server ->
                test(server, Editor(listener))
            }
        }
    }

    private val module = """
        VERSION = 2
        local count = 0
        local function add(a, b)
          local sum = a + b
          return sum
        end
        nf.on("tick", function()
          count = count + 1
          local t = { x = 1, list = { 1, 2, 3 }, name = "hi" }
          local total = add(count, 10)
          log("total " .. total)
        end)
    """

    @Test
    fun `a breakpoint stops the tick, and the editor reads the stack, the scopes and the variables`() {
        debugging(mapOf("modules/m/init.lua" to module)) { server, editor ->
            val verified = editor.breakpoints("modules/m/init.lua", 10)
            assertTrue(verified.breakpoints.single().isVerified)
            val watcher = editor.watch {
                val stop = nextStop()
                assertEquals("breakpoint", stop.reason)
                assertEquals(1, stop.threadId)
                val top = stack().first()
                assertEquals("modules/m/init.lua", top.source.path)
                assertEquals(10, top.line)

                val locals = scope("Locals")
                assertEquals("table", locals.getValue("t").type)
                val t = variables(locals.getValue("t").variablesReference)
                assertEquals(listOf("list", "name", "x"), t.keys.toList())
                assertEquals("\"hi\"", t.getValue("name").value)
                assertEquals("1", t.getValue("x").value)
                assertEquals("{1, 2, 3}", t.getValue("list").value)
                assertEquals(
                    listOf("2"),
                    variables(t.getValue("list").variablesReference).filterKeys {
                        it == "[2]"
                    }.values.map { it.value }
                )

                val upvalues = scope("Upvalues")
                assertEquals("1", upvalues.getValue("count").value)
                assertEquals("function", upvalues.getValue("add").type)
                assertEquals("2", scope("Globals").getValue("VERSION").value)

                // Held: the platform keeps the server alive, and the main thread's requests are refused with why.
                assertTrue(server.platform.pause.holds.first().endsWith("modules/m/init.lua:10"), "${server.platform.pause.holds}")
                val refused = instances()
                assertEquals(Bridge.REQUEST_FAILED, refused.error?.code)
                assertTrue("paused at a breakpoint (modules/m/init.lua:10)" in refused.error!!.message, refused.error!!.message)
                assertTrue(logs.none { it.startsWith("total") }, "nothing after the breakpoint ran yet")
                goOn()
            }
            server.tick()
            watcher.check()
            assertEquals(listOf("total 11"), editor.logged(1))
            assertNotNull(editor.continued.poll(5, TimeUnit.SECONDS), "the editor heard it go on")
            // Without the breakpoint the tick runs through, and the main thread's requests are answered again.
            editor.breakpoints("modules/m/init.lua")
            server.tick()
            assertEquals(listOf("total 11", "total 12"), editor.logged(2))
            assertEquals(null, editor.instances(server::runMain).error)
        }
    }

    @Test
    fun `stepping in, out and over follows the script`() {
        debugging(mapOf("modules/m/init.lua" to module)) { server, editor ->
            editor.breakpoints("modules/m/init.lua", 10)
            val watcher = editor.watch {
                nextStop()
                dap.stepIn(StepInArguments().apply { threadId = 1 }).done()
                assertEquals("step", nextStop().reason)
                val inside = stack()
                assertEquals(4, inside[0].line)
                assertEquals("add", inside[0].name)
                assertEquals(10, inside[1].line, "the caller is the next frame")
                assertEquals("11", scope("Locals").let { it.getValue("a").value.toInt().plus(it.getValue("b").value.toInt()).toString() })

                dap.stepOut(StepOutArguments().apply { threadId = 1 }).done()
                nextStop()
                assertEquals(11, stack()[0].line, "back in the handler, after the call")
                assertEquals("11", scope("Locals").getValue("total").value)

                dap.next(NextArguments().apply { threadId = 1 }).done()
                // The handler's last line, `end`: then the call is over and the server runs on.
                assertEquals(12, nextStop().let { stack()[0].line })
                dap.next(NextArguments().apply { threadId = 1 }).done()
            }
            server.tick()
            watcher.check()
            assertEquals(listOf("total 11"), editor.logged(1))
            // Stepping off the end of the handler didn't stop in the next tick's call: only the breakpoint does.
            editor.breakpoints("modules/m/init.lua")
            server.tick()
            assertTrue(editor.stops.isEmpty())
        }
    }

    @Test
    fun `stepping over a task's wait stops at its next line when it wakes`() {
        val files = mapOf(
            "modules/m/init.lua" to """
                local started = false
                nf.on("tick", function()
                  if started then return end
                  started = true
                  nf.task(function()
                    local a = 1
                    nf.wait(2)
                    local b = a + 1
                    log("b " .. b)
                  end)
                end)
            """
        )
        debugging(files) { server, editor ->
            editor.breakpoints("modules/m/init.lua", 7)
            val watcher = editor.watch {
                nextStop()
                dap.next(NextArguments().apply { threadId = 1 }).done()
                val stop = nextStop()
                assertEquals("step", stop.reason)
                assertEquals(8, stack()[0].line)
                assertEquals("1", scope("Locals").getValue("a").value)
                goOn()
            }
            server.tick(4)
            watcher.check()
            assertEquals(listOf("b 2"), editor.logged(1))
        }
    }

    @Test
    fun `pause stops at the next line any script runs`() {
        debugging(mapOf("modules/m/init.lua" to module)) { server, editor ->
            editor.dap.pause(PauseArguments().apply { threadId = 1 }).done()
            val watcher = editor.watch {
                assertEquals("pause", nextStop().reason)
                assertEquals(8, stack()[0].line, "the handler's first line")
                goOn()
            }
            server.tick()
            watcher.check()
            server.tick()
            assertTrue(editor.stops.isEmpty(), "a pause stops once")
        }
    }

    @Test
    fun `break on script errors stops where the error happened, and it's reported after`() {
        val files = mapOf(
            "modules/m/init.lua" to """
                nf.on("tick", function()
                  local missing = nil
                  local value = missing.field
                end)
            """
        )
        debugging(files) { server, editor ->
            editor.dap.setExceptionBreakpoints(SetExceptionBreakpointsArguments().apply { filters = arrayOf("errors") }).done()
            val watcher = editor.watch {
                val stop = nextStop()
                assertEquals("exception", stop.reason)
                assertTrue("attempt to index" in stop.text, stop.text)
                assertEquals(3, stack()[0].line)
                assertEquals("nil", scope("Locals").getValue("missing").value)
                goOn()
            }
            server.tick()
            watcher.check()
            assertTrue(editor.reported().single().contains("attempt to index"), "${editor.errors}")
        }
    }

    @Test
    fun `time paused isn't the script's, its time limit and its costs leave it out`() {
        // A clock only the test moves: the stop holds it 400 ms, and nothing else takes any time at all.
        val now = AtomicLong()
        debugging(mapOf("modules/m/init.lua" to module), sandbox = SandboxLimits(deadlineMillis = 100), clock = now::get) {
                server,
                editor
            ->
            editor.breakpoints("modules/m/init.lua", 10)
            val watcher = editor.watch {
                nextStop()
                now.addAndGet(TimeUnit.MILLISECONDS.toNanos(400))
                goOn()
            }
            server.tick()
            watcher.check()
            assertTrue(editor.errors.isEmpty(), "held 400 ms with a 100 ms limit: ${editor.errors}")
            assertEquals(listOf("total 11"), editor.logged(1))
            val cost = server.runtime.session.costs.report().single { it.scope.module == "m" }
            assertEquals(0.0, cost.maxMillis, "the paused tick cost ${cost.maxMillis} ms")
        }
    }

    @Test
    fun `an editor that goes away while the server is paused leaves it running, without breakpoints`() {
        debugging(mapOf("modules/m/init.lua" to module)) { server, editor ->
            editor.breakpoints("modules/m/init.lua", 10)
            val watcher = editor.watch {
                nextStop()
                close()
            }
            server.tick()
            watcher.check()
            assertEquals(null, server.runtime.debugger?.stopped)
            val holds = server.platform.pause.holds.size
            // The breakpoint went with the editor: these ticks run through (held, the test would time out).
            server.tick(2)
            assertEquals(holds, server.platform.pause.holds.size, "held only while it was paused")
        }
    }

    @Test
    fun `breakpoints outlive a full reload, and hold in scripts' bodies`() {
        val files = mapOf(
            "modules/m/init.lua" to """
                local loaded = "body"
                log(loaded)
            """
        )
        debugging(files) { server, editor ->
            editor.breakpoints("modules/m/init.lua", 2)
            server.tick()
            val watcher = editor.watch {
                nextStop()
                assertEquals("\"body\"", scope("Locals").getValue("loaded").value)
                assertEquals("(body)", stack()[0].name)
                goOn()
            }
            server.reload("netherforge.json")
            watcher.check()
            assertEquals(listOf("body", "body"), editor.logged(2))
        }
    }

    private fun manifest(namespace: String, more: String) =
        """{ "formatVersion": ${FormatVersion.CURRENT}, "name": "$namespace", "namespace": "$namespace", "version": "1.0.0", "minecraft": "26.3", $more }"""

    /** The package's files by path inside it: its module `api` runs a tick handler, whose log line is line 4. */
    private val library = mapOf(
        "netherforge.json" to manifest("lib", """"exports": { "modules": ["api"] }"""),
        "modules/api/init.lua" to """
            local count = 0
            nf.on("tick", function()
              count = count + 1
              log("lib " .. count)
            end)
        """.trimIndent()
    )

    /** One stop in the package's file, from a breakpoint the editor named [path] (however it names a file). */
    private fun stopsInPackage(path: (TestServer) -> String) {
        val files = mapOf(
            "netherforge.json" to manifest("test", """"dependencies": { "lib": { "path": "../lib" } }"""),
            "modules/main/init.lua" to """require("lib:api")"""
        ) + library.mapKeys { "../lib/${it.key}" }
        debugging(files) { server, editor ->
            val bound = editor.breakpoints(path(server), 4).breakpoints.single()
            assertTrue(bound.isVerified)
            // However the file was named, it's answered as the chunk is: by its package path.
            assertEquals("lib:modules/api/init.lua", bound.source.path)
            val watcher = editor.watch {
                assertEquals("breakpoint", nextStop().reason)
                val top = stack().first()
                assertEquals("lib:modules/api/init.lua", top.source.path)
                assertEquals(4, top.line)
                goOn()
            }
            server.tick()
            watcher.check()
            assertEquals(listOf("lib 1"), editor.logged(1))
        }
    }

    @Test
    fun `a breakpoint in a package's file binds by its package path`() = stopsInPackage { "lib:modules/api/init.lua" }

    @Test
    fun `a breakpoint in a package's file binds by its place on disk beside the project`() =
        stopsInPackage { it.project.resolveSibling("lib/modules/api/init.lua").toString() }

    @Test
    fun `a breakpoint in a project file binds by its place on disk too`() {
        debugging(mapOf("modules/m/init.lua" to module)) { server, editor ->
            val set = editor.breakpoints(server.project.resolve("modules/m/init.lua").toString(), 10)
            assertEquals("modules/m/init.lua", set.breakpoints.single().source.path)
        }
    }

    @Test
    fun `a breakpoint in a cached package's file binds by its place in the package cache`() {
        val commit = "c".repeat(40)
        val cache = createTempDirectory("netherforge-packages")
        val checkout = cache.resolve(Packages.gitCheckout(commit))
        for ((path, text) in library) checkout.resolve(path).also { it.parent.createDirectories() }.writeText(text)
        val url = "https://example.com/lib.git"
        val lock = LockFile(
            LockFile.SCHEMA,
            mapOf("lib" to LockedPackage("1.0.0", PackageSource.Git(url, "v1", commit), ProjectFiles.hash(DiskProjectSource(checkout))))
        )
        val files = mapOf(
            "netherforge.json" to manifest("test", """"dependencies": { "lib": { "git": "$url", "rev": "v1" } }"""),
            "modules/main/init.lua" to """require("lib:api")""",
            "netherforge.lock" to LockKind.write(lock)
        )
        ServerSocket(0).use { listener ->
            TestServer(files, bridge = BridgeConfig(listener.localPort, "secret"), packageCache = cache).use { server ->
                val editor = Editor(listener)
                val bound = editor.breakpoints(checkout.resolve("modules/api/init.lua").toString(), 4).breakpoints.single()
                assertEquals("lib:modules/api/init.lua", bound.source.path)
                val watcher = editor.watch {
                    assertEquals("breakpoint", nextStop().reason)
                    assertEquals("lib:modules/api/init.lua", stack().first().source.path)
                    goOn()
                }
                server.tick()
                watcher.check()
            }
        }
    }

    @Test
    fun `a handle shows what it stands for`() {
        val files = mapOf(
            "centities/crate/centity.json" to TestServer.scriptedCentity(),
            "centities/crate/script.lua" to """
                this:on("tick", function()
                  local me = this
                  log("ticked")
                end)
            """
        )
        debugging(files) { server, editor ->
            val crate = assertNotNull(server.runtime.session.centities.spawn("crate", Location("world", 10.0, 64.0, 10.0)))
            editor.breakpoints("centities/crate/script.lua", 3)
            val watcher = editor.watch {
                nextStop()
                val me = scope("Locals").getValue("me")
                assertEquals("Centity", me.type)
                assertEquals("Centity crate ${crate.id.toString().take(8)}", me.value)
                val facts = variables(me.variablesReference)
                assertEquals("crate", facts.getValue("centity").value)
                assertEquals(crate.id.toString(), facts.getValue("uuid").value)
                assertEquals("world", facts.getValue("world").value)
                goOn()
            }
            server.tick()
            watcher.check()
        }
    }

    private companion object {
        fun <T> CompletableFuture<T>.done(): T = get(10, TimeUnit.SECONDS)
    }
}
