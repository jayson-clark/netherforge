package dev.netherforge.plugin.wasm

import dev.netherforge.plugin.async.WorkFailed
import dev.netherforge.plugin.binding.TestWasmStub
import dev.netherforge.plugin.binding.WasmRunResult
import dev.netherforge.plugin.lua.LuaValue
import dev.netherforge.plugin.script.Scope
import dev.netherforge.plugin.script.Scripts
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor

/**
 * What a test run stands in for modules with (`nf.test.stub_wasm`): by the server's name, the test's scope and its
 * functions, which answer in the module's place on the main thread (`run`, `functions.<name>`, `input`). [WasmModules]
 * asks [standingIn] before it runs anything of a module, and hands the rest here, so nothing of a stand-in reaches
 * Chicory's path. Nothing is ever stood in for outside a test run: `nf.test` isn't there.
 */
internal class WasmStandIns(private val scripts: Scripts, private val mainThread: Executor) {
    private val stubs = HashMap<String, Pair<Scope, TestWasmStub>>()

    /** Whether the module [key] is stood in for now. */
    fun standingIn(key: String): Boolean = key in stubs

    /** Stands [stub] in for [key] for the rest of [scope] (a test), in place of any stand-in before it. */
    fun stub(scope: Scope, key: String, stub: TestWasmStub) {
        stubs.put(key, scope to stub)?.let { release(it.second) }
    }

    /** A test's scope closed: what it stood in with goes. */
    fun dropScope(scope: Scope) {
        stubs.entries.removeIf { (_, held) -> (held.first.id == scope.id).also { if (it) release(held.second) } }
    }

    /** Lets go of the functions a stand-in kept. */
    private fun release(stub: TestWasmStub) {
        for (function in stub.functions.orEmpty().values + listOfNotNull(stub.run, stub.input)) scripts.unref(function.ref)
    }

    /** A stand-in instance of [key], numbered [number]: made at the next turn of the main thread, as a real one is later. */
    fun instantiate(number: Long, scope: Scope, key: String): CompletionStage<WasmInstanceRecord> {
        val (_, stub) = stubs.getValue(key)
        return CompletableFuture.supplyAsync({ WasmInstanceRecord.StandIn(number, scope.id, scope.namespace, key, stub) }, mainThread)
    }

    fun call(record: WasmInstanceRecord.StandIn, name: String, table: LuaValue?, text: Boolean): CompletionStage<Any?> {
        val (scope, stub) = stubs[record.key]
            ?: return CompletableFuture.failedFuture(WorkFailed("the stand-in for module \"${record.key}\" is gone"))
        val function = stub.functions?.get(name)
            ?: return CompletableFuture.failedFuture(WorkFailed("the stand-in for module \"${record.key}\" has no function \"$name\""))
        val arguments = table?.json("args")
        return CompletableFuture.supplyAsync({
            val answer = WasmStubCalls.call(scripts, scope, function, listOf(arguments), "functions.$name")
            if (text) WasmStubCalls.text(answer, name) else WasmStubCalls.values(answer, name)
        }, mainThread)
    }

    fun run(key: String, stdin: String?, arguments: List<String>, environment: Map<String, String>): CompletionStage<WasmRunResult> {
        val (scope, functions) = stubs.getValue(key)
        val run = functions.run ?: return CompletableFuture.failedFuture(WorkFailed("the stand-in has no run"))
        val options = mapOf("stdin" to stdin, "arguments" to arguments, "environment" to environment).filterValues { it != null }
        return CompletableFuture.supplyAsync({ WasmStubCalls.run(scripts, scope, run, options) }, mainThread)
    }

    /** Answers what scripts sent [program] since the last tick, through the stand-in's `input`; its end once the input closed. */
    fun answer(program: WasmProgram) {
        val input = program.input as StandInInput
        val (scope, stub) = stubs[program.key] ?: return
        while (input.lines.isNotEmpty()) {
            val line = input.lines.removeFirst()
            val answer = stub.input ?: continue
            for (printed in WasmStubCalls.lines(scripts, scope, answer, line, program.handle)) {
                program.output += ProgramOutput.Line(printed, "stdout")
            }
        }
        if (input.finished && !input.answeredEnd) {
            input.answeredEnd = true
            program.output += ProgramOutput.Ended(ProgramEnd.EXITED, exitCode = 0)
        }
    }
}
