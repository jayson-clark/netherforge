package dev.netherforge.plugin.wasm

import com.dylibso.chicory.runtime.ChicoryInterruptedException
import com.dylibso.chicory.wasi.WasiExitException
import com.dylibso.chicory.wasm.ChicoryException
import dev.netherforge.format.Problem
import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.project.KindSpec
import dev.netherforge.format.project.Kinds
import dev.netherforge.format.project.ResourcePlace
import dev.netherforge.format.wasm.WasmImports
import dev.netherforge.format.wasm.WasmModuleFile
import dev.netherforge.format.wasm.WasmModuleKind
import dev.netherforge.plugin.RuntimeLog
import dev.netherforge.plugin.async.WorkFailed
import dev.netherforge.plugin.binding.Events
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.binding.TestWasmStub
import dev.netherforge.plugin.binding.WasmEndEvent
import dev.netherforge.plugin.binding.WasmFunction
import dev.netherforge.plugin.binding.WasmOutputEvent
import dev.netherforge.plugin.binding.WasmRunResult
import dev.netherforge.plugin.binding.notInProject
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.lua.LuaValue
import dev.netherforge.plugin.project.Resource
import dev.netherforge.plugin.script.Scope
import dev.netherforge.plugin.script.Scripts
import dev.netherforge.plugin.session.PackageNames
import dev.netherforge.plugin.session.ReloadBatch
import dev.netherforge.plugin.session.RuntimeService
import dev.netherforge.plugin.session.SessionProject
import dev.netherforge.plugin.session.TickPhase
import dev.netherforge.plugin.session.liveness
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The project's WebAssembly modules (`wasm/<id>/`) and everything scripts run of them: `nf.wasm`. A session's
 * service, `session.wasm`.
 *
 * - **Compiling.** Every module that read is compiled off the main thread as the session defines it (and again
 *   when its files change), on [WasmThreads.calls]: parsed, validated, its imports held to WASI's, turned into JVM
 *   classes by Chicory's compiler. A failure is the problem `runtime.wasm`, and running the module fails saying so.
 * - **Instances** (`nf.wasm.instantiate`): Chicory instances, each with its own memory and its own lane
 *   of calls. A call that traps or runs out of time closes its instance (its memory may be half-written).
 * - **Runs** (`nf.wasm.run`): an instance made, run to its end with its input and thrown away.
 * - **Programs** (`nf.wasm.start`): an instance on a thread of its own ([WasmThreads.programs]), talking line by line
 *   through [WasmProgram]; the main thread hands their lines to scripts each tick.
 *
 * Everything a scope makes is its own and goes when it's released ([scopeReleased]); the owner's limits
 * ([WasmConfig]) are held here: memory through one [WasmMemoryBudget], counts per package, time by the watchdog.
 * A module a script ran restarts that script when it reloads. In a test run a module can be stood in for
 * ([stub]), and [settle] is what the runner waits on before each tick.
 */
class WasmModules(
    /** The owner's limits (`wasm:` in `config.yml`). */
    private val config: WasmConfig,
    private val scripts: Scripts,
    private val log: RuntimeLog,
    private val names: PackageNames,
    /** Back to the main thread, for what a module's threads hand over. */
    private val mainThread: Executor,
    private val readBytes: (String) -> ByteArray?,
    private val resolveFile: (String) -> Path?,
    private val placeOf: (KindSpec<*, *>, String) -> ResourcePlace,
    private val problemsChanged: () -> Unit
) : RuntimeService {
    override val name get() = "wasm"

    private val threads by lazy { WasmThreads(config.threads) }
    private val budget by lazy { WasmMemoryBudget(config.maxMemoryTotalMb * CompiledModule.PAGES_PER_MB) }

    /** Every module that read (errors or not), by the server's name: what scripts may name. */
    private var known: Map<String, WasmModuleFile> = emptyMap()

    /** Each running module's compile, by the server's name: done, failed, or still going. */
    private val compiled = HashMap<String, CompletableFuture<CompiledModule>>()

    /** The scopes that ran each module, by the server's name: what its reload restarts. */
    private val users = HashMap<String, MutableSet<Int>>()

    private val instances = LinkedHashMap<Long, WasmInstanceRecord>()
    private val programs = LinkedHashMap<Long, WasmProgram>()
    private var nextNumber = 1L

    /** Calls waiting or running, by package: held to `max-queued-calls-per-package`. From any thread. */
    private val queued = HashMap<String, AtomicInteger>()

    /** Instantiations not yet done, by package: they count against `max-instances-per-package` (main thread). */
    private val instantiating = HashMap<String, Int>()

    /** What stands in for modules in this test run. */
    private val standIns by lazy { WasmStandIns(scripts, mainThread) }

    // ---- lifecycle ----------------------------------------------------------------

    override fun define(project: SessionProject) {
        known = project.everywhere(WasmModuleKind).mapValues { it.value.value }
        for ((key, file) in project.running(WasmModuleKind)) compile(key, file)
    }

    override val reloads: Set<KindSpec<*, *>> get() = setOf(WasmModuleKind)

    override fun reload(kind: KindSpec<*, *>, ids: Set<String>, batch: ReloadBatch) {
        known = batch.snapshot.everywhere(WasmModuleKind).mapValues { it.value.value }
        val running = batch.snapshot.running(WasmModuleKind)
        val order = Kinds.all.indexOf(WasmModuleKind)
        for (id in ids) {
            compiled.remove(id)?.cancel(false)
            running[id]?.let { compile(id, it) }
            // The scripts that ran it start again on the new one, in their kinds' turns (which come after this one).
            for (scopeId in users.remove(id).orEmpty()) {
                val resource = scripts.scope(scopeId)?.owner?.resource ?: continue
                if (Kinds.all.indexOf(resource.kind) > order) batch.add(resource)
            }
            batch.data(Resource(WasmModuleKind, id))
        }
        problemsChanged()
    }

    override fun stop() {
        for (program in programs.values.toList()) end(program, ProgramEnd.UNLOADED)
        for (record in instances.values.toList()) close(record)
        threads.close()
    }

    override fun scopeReleased(scope: Scope) {
        for (program in programs.values.filter { it.owner == scope.id }) end(program, ProgramEnd.UNLOADED)
        for (record in instances.values.filter { it.owner == scope.id }) close(record)
        instances.values.removeIf { it.owner == scope.id }
        for (scopes in users.values) scopes.remove(scope.id)
        standIns.dropScope(scope)
    }

    override fun tick(phase: TickPhase) {
        if (phase == TickPhase.ASYNC && programs.isNotEmpty()) deliver()
    }

    override fun problems(): List<Problem> = compiled.mapNotNull { (key, future) ->
        if (!future.isCompletedExceptionally) return@mapNotNull null
        val message = failure(future) ?: return@mapNotNull null
        ProblemCodes.RUNTIME_WASM.at(placeOf(WasmModuleKind, key).path, "The server couldn't compile module \"$key\": $message")
    }

    override fun costs(): Map<String, (Scope) -> Int> = mapOf(
        "wasm instances" to { scope -> instances.values.count { it.owner == scope.id && !it.closed } },
        "wasm programs" to { scope -> programs.values.count { it.owner == scope.id } }
    )

    override fun liveness() = listOf(
        liveness<LuaHandle.WasmInstance> { instances[it.number]?.closed == false },
        liveness<LuaHandle.WasmProcess> { it.number in programs }
    )

    /**
     * Waits until what scripts started has gone as far as it can on its own: no call, run or compile waiting or
     * running (false if [millis] pass first: each is held to `max-call-seconds` anyway), and every program waiting
     * for input or for scripts to take its lines, or done. A program still computing is waited for at most
     * [PROGRAM_SETTLE_MILLIS] a tick, since nothing bounds how long it may: one that never stops (or thinks for
     * longer) answers at a later tick. A test run's ticks wait on it, so a test sees an answer land at a known tick.
     */
    fun settle(millis: Long): Boolean {
        val start = System.nanoTime()
        val deadline = start + TimeUnit.MILLISECONDS.toNanos(millis)
        val programsDeadline = start + TimeUnit.MILLISECONDS.toNanos(PROGRAM_SETTLE_MILLIS)
        while (true) {
            val now = System.nanoTime()
            val programsQuiet = now > programsDeadline || programs.values.all { it.standIn || it.quiet }
            if (threads.idle && programsQuiet) return true
            if (now > deadline) return false
            Thread.sleep(1)
        }
    }

    // ---- what nf.wasm calls -------------------------------------------------------------

    /** The module [name] names (resolved in the calling package), as the server names it; recorded as [scope]'s. */
    fun resolve(scope: Scope, name: String, what: String): String {
        val key = names.resource(WasmModuleKind, name)
        if (key !in known) throw LuaApiException("$what: ${notInProject("WebAssembly module", name, known.keys.sorted())}")
        users.getOrPut(key, ::HashSet) += scope.id
        return key
    }

    /** What [key] exports: nothing until it has compiled, or when it couldn't be. */
    fun functions(key: String): List<WasmFunction> = done(key)?.functions.orEmpty()

    /** The compiled [key], when it's done; null while it's compiling and when it couldn't be. */
    private fun done(key: String): CompiledModule? = compiled[key]?.takeIf { it.isDone && !it.isCompletedExceptionally }?.join()

    fun instantiate(scope: Scope, key: String): CompletionStage<LuaHandle.WasmInstance> {
        val namespace = scope.namespace
        val held = instances.values.count { it.namespace == namespace && !it.closed } + instantiating.getOrDefault(namespace, 0)
        if (held >= config.maxInstancesPerPackage) {
            return CompletableFuture.failedFuture(
                WorkFailed(
                    "package \"$namespace\" already holds ${config.maxInstancesPerPackage} WebAssembly instances, the server's limit " +
                        "(wasm.max-instances-per-package in config.yml): close some"
                )
            )
        }
        val future = compiled[key]
        instantiating.merge(namespace, 1, Int::plus)
        val number = nextNumber++
        val made = if (standIns.standingIn(key)) standIns.instantiate(number, scope, key) else live(number, scope, key, future)
        return made.handleAsync({ record, failure ->
            instantiating.merge(namespace, -1, Int::plus)
            if (failure != null) throw unwrap(failure)
            if (!scope.live || scripts.scope(scope.id) == null) {
                record.close(budget)
                throw WorkFailed("the script that made it has unloaded")
            }
            instances[number] = record
            LuaHandle.WasmInstance(number)
        }, mainThread)
    }

    /** A Chicory instance of [key] for [scope], made on the module threads once it has compiled. */
    private fun live(
        number: Long,
        scope: Scope,
        key: String,
        future: CompletableFuture<CompiledModule>?
    ): CompletionStage<WasmInstanceRecord> {
        val namespace = scope.namespace
        return submit(namespace) {
            val module = awaitCompiled(key, future)
            val pages = reserve()
            try {
                // Its start function runs as it's made, and a reactor (a library built for WASI) sets itself up once,
                // before its first call: both held to the time a call has.
                val running = threads.limited(config.maxCallSeconds, { tooLong("instantiating module \"$key\"") }) {
                    module.instantiate(WasmIo(key), maxPages()).also { made ->
                        try {
                            if ("_initialize" in module.types) made.instance.export("_initialize").apply()
                        } catch (e: Throwable) {
                            made.close()
                            throw e
                        }
                    }
                }
                WasmInstanceRecord.Live(number, scope.id, namespace, key, module, running, pages, threads)
            } catch (e: Throwable) {
                budget.release(pages)
                throw failed(e, "instantiating module \"$key\"")
            }
        }
    }

    /** The instance [number], open; null once it's closed. */
    fun instance(number: Long): WasmInstanceRecord? = instances[number]?.takeIf { !it.closed }

    /** The module the instance [number] is of, closed or not, while its scope lasts. */
    fun instanceModule(number: Long): String? = instances[number]?.key

    /** Calls [name] in [record] with [table] (the script's `args`); [text] for `call_text`. */
    fun call(record: WasmInstanceRecord, name: String, table: LuaValue?, text: Boolean): CompletionStage<Any?> {
        val what = if (text) "WasmInstance:call_text" else "WasmInstance:call"
        when (record) {
            is WasmInstanceRecord.StandIn -> return standIns.call(record, name, table, text)
            is WasmInstanceRecord.Live -> Unit
        }
        val module = record.module
        val type = module.types[name]
            ?: throw LuaApiException(
                "$what: module \"${record.key}\" exports no function \"$name\" (it exports ${module.types.keys.sorted()})"
            )
        if (text && !WasmCalls.answersText(type)) {
            throw LuaApiException(
                "$what: $name returns (${type.returns().joinToString(", ") { CompiledModule.typeName(it) }}), not text: " +
                    "text comes back as two i32s (where it starts and its size) or one i64 holding both"
            )
        }
        if (!text && type.returns().any { it !in WasmCalls.VALUE_TYPES }) {
            throw LuaApiException("$what: $name returns a ${type.returns().map { CompiledModule.typeName(it) }}, which a script can't take")
        }
        val arguments = WasmCalls.arguments(module, name, type, table)
        val maxText = config.maxOutputBytes
        return submit(record.namespace, record.lane) {
            if (record.closed) throw WorkFailed("the instance is closed")
            try {
                record.running {
                    threads.limited(config.maxCallSeconds, { "${tooLong(name)} and the instance closed" }) {
                        WasmCalls.call(record.instance, module, name, arguments) { results, instance ->
                            if (text) WasmCalls.text(instance, module, type, results, maxText) else WasmCalls.values(type, results)
                        }
                    }
                }
            } catch (e: Throwable) {
                // Its memory may be half-written: nothing more runs in it.
                record.close(budget)
                throw failed(e, name)
            }
        }
    }

    /** Closes [record] now; false when it was already closed. */
    fun close(record: WasmInstanceRecord): Boolean {
        if (record.closed) return false
        record.close(budget)
        return true
    }

    fun run(
        scope: Scope,
        key: String,
        stdin: String?,
        arguments: List<String>,
        environment: Map<String, String>
    ): CompletionStage<WasmRunResult> {
        if (standIns.standingIn(key)) return standIns.run(key, stdin, arguments, environment)
        val future = compiled[key]
        val limit = config.maxOutputBytes
        return submit(scope.namespace) {
            val module = awaitCompiled(key, future)
            if (!module.command) {
                throw WorkFailed(
                    "module \"$key\" isn't a program: it exports no _start (call its functions with nf.wasm.instantiate)"
                )
            }
            val pages = reserve()
            val stdout = CappedOutput(limit)
            val stderr = CappedOutput(limit)
            val io = WasmIo(key, (stdin ?: "").byteInputStream(), stdout, stderr, arguments, environment)
            try {
                // Made and run within the one time limit: its start function runs as it's made.
                val code = threads.limited(config.maxCallSeconds, { tooLong("module \"$key\"") }) {
                    module.instantiate(io, maxPages()).use { running ->
                        exitCode { running.instance.export(WasmImports.COMMAND_ENTRY).apply() }
                    }
                }
                WasmRunResult(code.toLong(), stdout.text(), stderr.text())
            } catch (e: Throwable) {
                throw failed(e, "module \"$key\"")
            } finally {
                budget.release(pages)
            }
        }
    }

    fun start(scope: Scope, key: String, arguments: List<String>, environment: Map<String, String>): LuaHandle.WasmProcess {
        val number = nextNumber++
        val handle = LuaHandle.WasmProcess(number, key)
        val program =
            WasmProgram(
                number,
                handle,
                scope.id,
                scope.namespace,
                key,
                config.maxQueuedLines,
                config.maxLineBytes,
                standIns.standingIn(key)
            )
        programs[number] = program
        if (program.standIn) return handle
        val running = programs.values.filter { !it.ended && !it.standIn && it !== program }
        val refusal = when {
            running.count { it.namespace == scope.namespace } >= config.maxProcessesPerPackage ->
                "package \"${scope.namespace}\" already has ${config.maxProcessesPerPackage} WebAssembly programs running, the server's " +
                    "limit (wasm.max-processes-per-package in config.yml)"
            running.size >= config.maxProcessesTotal ->
                "the server already has ${config.maxProcessesTotal} WebAssembly programs running, its limit " +
                    "(wasm.max-processes-total in config.yml)"
            else -> null
        }
        if (refusal != null) {
            program.finished = true
            program.output += ProgramOutput.Ended(ProgramEnd.LIMIT, detail = refusal)
            return handle
        }
        val future = compiled[key]
        try {
            threads.programs.execute { runProgram(program, future, arguments, environment) }
        } catch (_: RejectedExecutionException) {
            program.finished = true
            program.output += ProgramOutput.Ended(ProgramEnd.FAILED, detail = "the server is stopping")
        }
        return handle
    }

    /** The program [number], ended or not, while scripts can still reach it. */
    fun program(number: Long): WasmProgram? = programs[number]

    /** A script sends [line] to [program]; false when it has ended or its input was closed. */
    fun send(program: WasmProgram, line: String): Boolean {
        if (program.ended || !program.input.open) return false
        return program.input.send(line)
    }

    fun closeInput(program: WasmProgram): Boolean {
        if (program.ended || !program.input.open) return false
        program.input.finish()
        return true
    }

    fun stop(program: WasmProgram): Boolean {
        if (program.ended) return false
        end(program, ProgramEnd.STOPPED)
        return true
    }

    // ---- inside ---------------------------------------------------------------------

    private fun compile(key: String, file: WasmModuleFile) {
        val place = placeOf(WasmModuleKind, key)
        val bytes = readBytes(place.path)
        val data = WasmData(file.data.mapNotNull { name -> resolveFile(place.file(name))?.let { name to it } }.toMap())
        val future = CompletableFuture<CompiledModule>()
        compiled[key] = future
        if (bytes == null) {
            future.completeExceptionally(WorkFailed("module.wasm can't be read"))
            return
        }
        val compile = config.compile
        val maxBytes = config.maxModuleMb * 1024L * 1024L
        // The log is the main thread's.
        val warn = { message: String -> mainThread.execute { log.warn(message) } }
        try {
            threads.calls.execute {
                try {
                    future.complete(CompiledModule.compile(key, bytes, data, compile, maxBytes, warn))
                } catch (e: Throwable) {
                    future.completeExceptionally(e)
                }
            }
        } catch (e: RejectedExecutionException) {
            future.completeExceptionally(WorkFailed("the server is stopping"))
        }
        // A module that didn't compile is a problem the editor should hear about once it's known.
        future.whenCompleteAsync({ _, failure ->
            if (failure != null && compiled[key] === future) {
                log.warn("WebAssembly module \"$key\" couldn't be compiled: ${failureText(failure)}")
                problemsChanged()
            }
        }, mainThread)
    }

    private fun awaitCompiled(key: String, future: CompletableFuture<CompiledModule>?): CompiledModule {
        if (future == null) throw WorkFailed("module \"$key\" has errors, so it can't run: see the problems")
        return try {
            future.get()
        } catch (e: ExecutionException) {
            throw WorkFailed("module \"$key\" couldn't be compiled: ${failureText(e.cause)}")
        }
    }

    /** Why [what] was stopped at the time limit. */
    private fun tooLong(what: String) =
        "$what took longer than ${config.maxCallSeconds} seconds (wasm.max-call-seconds in config.yml), so it was stopped"

    /** The most memory one instance may have, in pages. */
    private fun maxPages(): Long = config.maxMemoryMb * CompiledModule.PAGES_PER_MB

    /** Reserves what an instance may grow to, or fails saying the server's memory for modules is taken. */
    private fun reserve(): Long = reserveOrNull() ?: throw WorkFailed(memoryTaken())

    /** [reserve], or null when the memory is taken: what a program ends `"limit"` on. */
    private fun reserveOrNull(): Long? = maxPages().takeIf(budget::reserve)

    private fun memoryTaken() = "the server's WebAssembly memory is taken: its modules may hold ${config.maxMemoryTotalMb} MiB together " +
        "(wasm.max-memory-total-mb in config.yml), and each instance or program counts as ${config.maxMemoryMb} MiB " +
        "(wasm.max-memory-mb); close one"

    /** Runs [work] for [namespace] on [on], held to `max-queued-calls-per-package`. */
    private fun <T> submit(namespace: String, on: Executor = threads.calls, work: () -> T): CompletableFuture<T> {
        val count = synchronized(queued) { queued.getOrPut(namespace) { AtomicInteger() } }
        if (count.incrementAndGet() > config.maxQueuedCallsPerPackage) {
            count.decrementAndGet()
            return CompletableFuture.failedFuture(
                WorkFailed(
                    "package \"$namespace\" already has ${config.maxQueuedCallsPerPackage} WebAssembly calls waiting, the server's limit " +
                        "(wasm.max-queued-calls-per-package in config.yml)"
                )
            )
        }
        val future = CompletableFuture<T>()
        try {
            on.execute {
                try {
                    future.complete(work())
                } catch (e: Throwable) {
                    future.completeExceptionally(e)
                } finally {
                    count.decrementAndGet()
                }
            }
        } catch (_: RejectedExecutionException) {
            count.decrementAndGet()
            future.completeExceptionally(WorkFailed("the server is stopping"))
        }
        return future
    }

    private fun runProgram(
        program: WasmProgram,
        future: CompletableFuture<CompiledModule>?,
        arguments: List<String>,
        environment: Map<String, String>
    ) {
        var pages: Long? = null
        val ending = program.running {
            try {
                val module = awaitCompiled(program.key, future)
                if (!module.command) {
                    throw WorkFailed(
                        "module \"${program.key}\" isn't a program: it exports no _start (call its functions with nf.wasm.instantiate)"
                    )
                }
                pages = reserveOrNull() ?: return@running ProgramOutput.Ended(ProgramEnd.LIMIT, detail = memoryTaken())
                val stdout = program.LineOutput("stdout")
                val stderr = program.LineOutput("stderr")
                val io = WasmIo(program.key, program.input as WasmProgram.ProgramInput, stdout, stderr, arguments, environment)
                module.instantiate(io, maxPages()).use { running ->
                    val code = try {
                        exitCode { running.instance.export(WasmImports.COMMAND_ENTRY).apply() }
                    } finally {
                        stdout.finish()
                        stderr.finish()
                    }
                    ProgramOutput.Ended(ProgramEnd.EXITED, exitCode = code)
                }
            } catch (_: ChicoryInterruptedException) {
                // Stopped from the main thread, which says why itself.
                ProgramOutput.Ended(ProgramEnd.STOPPED)
            } catch (e: Throwable) {
                ProgramOutput.Ended(ProgramEnd.FAILED, detail = failureText(e))
            } finally {
                pages?.let(budget::release)
            }
        }
        program.output += ending
        program.finished = true
    }

    /** Hands each program's lines to scripts, up to `lines-per-tick` each, then its end; and answers those a test stands in for. */
    private fun deliver() {
        val perTick = config.linesPerTick
        for (program in programs.values.toList()) {
            if (program.standIn) standIns.answer(program)
            var lines = 0
            while (!program.ended && lines < perTick) {
                when (val item = program.output.poll() ?: break) {
                    is ProgramOutput.Line -> {
                        if (!program.standIn) program.room.release()
                        lines++
                        scripts.emit(
                            Events.WASMPROCESS_OUTPUT,
                            program.handle,
                            WasmOutputEvent(program.handle, item.text, item.stream)
                        )
                    }
                    is ProgramOutput.Ended -> end(program, item.reason, item.exitCode, item.detail)
                }
            }
        }
    }

    /**
     * Ends [program]: its thread is stopped (interrupted, its input closed), what it printed but wasn't handed over
     * is dropped, then its `end` handlers hear it while the handle still answers, and it's gone with every handler.
     */
    private fun end(program: WasmProgram, reason: ProgramEnd, exitCode: Int? = null, detail: String? = null) {
        if (program.ended) return
        program.ended = true
        program.input.finish()
        program.stopThread()
        program.output.clear()
        // Its thread may be waiting for room: let it go, so it sees the interrupt.
        program.room.release(Int.MAX_VALUE / 2)
        val handle = program.handle
        try {
            scripts.emit(Events.WASMPROCESS_END, handle, WasmEndEvent(handle, reason.luaName, exitCode?.toLong(), detail))
        } finally {
            programs.remove(program.number)
            scripts.dropTarget(handle)
        }
    }

    /** What a WASI command's run of [start] exits with: 0 when it returns, its code when it calls `proc_exit`. */
    private fun exitCode(start: () -> Unit): Int = try {
        start()
        0
    } catch (e: ChicoryException) {
        // Compiled code hands a host function's exception on wrapped; the exit is in there somewhere.
        generateSequence<Throwable>(e) { it.cause }.filterIsInstance<WasiExitException>().firstOrNull()?.exitCode() ?: throw e
    }

    /** [e], thrown running [what], as the failure a script reads. */
    private fun failed(e: Throwable, what: String): Throwable = when (e) {
        is WorkFailed -> e
        is ChicoryInterruptedException -> WorkFailed("$what was stopped: the instance was closed")
        is ChicoryException -> WorkFailed("$what failed: ${e.message}")
        is StackOverflowError -> WorkFailed("$what failed: it recursed too deeply")
        is OutOfMemoryError -> WorkFailed("$what failed: the server ran out of memory")
        else -> e
    }

    private fun unwrap(failure: Throwable): Throwable =
        if (failure is CompletionException && failure.cause != null) failure.cause!! else failure

    private fun failure(future: CompletableFuture<*>): String? = try {
        future.join()
        null
    } catch (e: Throwable) {
        failureText(unwrap(e))
    }

    private fun failureText(e: Throwable?): String = when (e) {
        null -> "it failed"
        is CompletionException, is ExecutionException -> failureText(e.cause)
        is WorkFailed -> e.message.orEmpty()
        else -> e.message ?: e.javaClass.simpleName
    }

    // ---- standing in for a module (test runs) -------------------------------------------

    /** Stands [stub] in for [key] for the rest of [scope] (a test), in place of any stand-in before it. */
    fun stub(scope: Scope, key: String, stub: TestWasmStub) = standIns.stub(scope, key, stub)

    /** What a run printed to one stream, kept up to [limit] bytes. */
    private class CappedOutput(private val limit: Int) : OutputStream() {
        private val bytes = ByteArrayOutputStream()

        override fun write(b: Int) {
            if (bytes.size() < limit) bytes.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            bytes.write(b, off, minOf(len, limit - bytes.size()).coerceAtLeast(0))
        }

        fun text(): String = bytes.toString(Charsets.UTF_8)
    }

    private companion object {
        /** How long [settle] waits a tick for a program that's still computing. */
        const val PROGRAM_SETTLE_MILLIS = 1000L
    }
}
