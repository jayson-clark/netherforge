package dev.netherforge.plugin.api

import dev.netherforge.plugin.binding.Caller
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.binding.NfWasmApi
import dev.netherforge.plugin.binding.WasmFunction
import dev.netherforge.plugin.binding.WasmInstanceApi
import dev.netherforge.plugin.binding.WasmProcessApi
import dev.netherforge.plugin.binding.WasmRunOptions
import dev.netherforge.plugin.binding.WasmRunResult
import dev.netherforge.plugin.binding.WasmStartOptions
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.lua.LuaValue
import dev.netherforge.plugin.session.ProjectSession
import dev.netherforge.plugin.wasm.WasmProgram
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * `nf.wasm`: the project's WebAssembly modules, run by `session.wasm` ([dev.netherforge.plugin.wasm.WasmModules]).
 * What a script got wrong in the call (a module the project doesn't have, a function it doesn't export, arguments
 * that don't fit) is an error at its line, found here on the main thread; what only running it can say (a trap,
 * a limit, a module that didn't compile) is `nil, err` or the program's `end`.
 */
internal class NfWasmImpl(private val session: ProjectSession) : NfWasmApi {
    private val wasm get() = session.wasm

    override fun functions(caller: Caller, module: String): List<WasmFunction> =
        wasm.functions(wasm.resolve(caller.scope, module, "nf.wasm.functions"))

    override fun instantiate(caller: Caller, module: String): CompletionStage<LuaHandle.WasmInstance> =
        wasm.instantiate(caller.scope, wasm.resolve(caller.scope, module, "nf.wasm.instantiate"))

    override fun run(caller: Caller, module: String, options: WasmRunOptions?): CompletionStage<WasmRunResult> {
        val key = wasm.resolve(caller.scope, module, "nf.wasm.run")
        return wasm.run(caller.scope, key, options?.stdin, options?.arguments.orEmpty(), environment(options?.environment))
    }

    override fun start(caller: Caller, module: String, options: WasmStartOptions?): LuaHandle.WasmProcess {
        val key = wasm.resolve(caller.scope, module, "nf.wasm.start")
        return wasm.start(caller.scope, key, options?.arguments.orEmpty(), environment(options?.environment))
    }

    private fun environment(given: Map<String, String>?): Map<String, String> {
        val environment = given.orEmpty()
        for (name in environment.keys) {
            if (name.isEmpty() || '=' in name || '\u0000' in name) {
                throw LuaApiException("options.environment has a name that can't be an environment variable's: \"$name\"")
            }
        }
        return environment
    }
}

/** `WasmInstance`: a handle to an instance by number; once it's closed, its calls fail and `close` is false. */
internal class WasmInstanceImpl(private val session: ProjectSession) : WasmInstanceApi {
    private val wasm get() = session.wasm

    override fun module(self: LuaHandle.WasmInstance): String =
        wasm.instanceModule(self.number)?.let(session.names::spell) ?: throw LuaApiException("the instance's script has unloaded")

    @Suppress("UNCHECKED_CAST")
    override fun call(self: LuaHandle.WasmInstance, name: String, arguments: LuaValue?): CompletionStage<List<Any?>> {
        val record = wasm.instance(self.number) ?: return closed()
        return wasm.call(record, name, arguments, text = false) as CompletionStage<List<Any?>>
    }

    override fun callText(self: LuaHandle.WasmInstance, name: String, arguments: LuaValue?): CompletionStage<String> {
        val record = wasm.instance(self.number) ?: return closed()
        return wasm.call(record, name, arguments, text = true).thenApply { it as String }
    }

    override fun close(self: LuaHandle.WasmInstance): Boolean = wasm.instance(self.number)?.let(wasm::close) == true

    private fun <T> closed(): CompletionStage<T> =
        CompletableFuture.failedFuture(dev.netherforge.plugin.async.WorkFailed("the instance is closed"))
}

/** `WasmProcess`: a handle to a program by number; once it has ended, `"ended"` and false. */
internal class WasmProcessImpl(private val session: ProjectSession) : WasmProcessApi {
    private val wasm get() = session.wasm

    private fun program(self: LuaHandle.WasmProcess): WasmProgram? = wasm.program(self.number)?.takeIf { !it.ended }

    override fun module(self: LuaHandle.WasmProcess): String = session.names.spell(self.module)

    override fun state(self: LuaHandle.WasmProcess): String = if (program(self) == null) "ended" else "running"

    override fun send(self: LuaHandle.WasmProcess, line: String): Boolean {
        val max = session.config.wasm.maxLineBytes
        val size = line.toByteArray(StandardCharsets.UTF_8).size
        if (size >
            max
        ) {
            throw LuaApiException("the line is $size bytes, over the server's limit of $max (wasm.max-line-bytes in config.yml)")
        }
        if ('\n' in line) throw LuaApiException("send takes one line: it has a line break in it (send each line on its own)")
        val program = program(self) ?: return false
        return wasm.send(program, line)
    }

    override fun closeInput(self: LuaHandle.WasmProcess): Boolean = program(self)?.let(wasm::closeInput) == true

    override fun stop(self: LuaHandle.WasmProcess): Boolean = program(self)?.let(wasm::stop) == true
}
