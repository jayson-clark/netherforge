package dev.netherforge.plugin.wasm

import dev.netherforge.plugin.async.WorkFailed
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.binding.WasmRunResult
import dev.netherforge.plugin.lua.CallResult
import dev.netherforge.plugin.lua.LuaCall
import dev.netherforge.plugin.lua.LuaCodec
import dev.netherforge.plugin.lua.LuaCodecs
import dev.netherforge.plugin.lua.LuaFunction
import dev.netherforge.plugin.lua.LuaList
import dev.netherforge.plugin.lua.LuaOptional
import dev.netherforge.plugin.lua.LuaValue
import dev.netherforge.plugin.script.Scope
import dev.netherforge.plugin.script.Scripts
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Calling what `nf.test.stub_wasm` stood in for a module with, on the main thread as the test's code: each answer
 * read into Kotlin values while the call lasts, as a module's own would be.
 */
internal object WasmStubCalls {
    /** Whatever the function returned, as JSON (the one Lua↔JSON codec), or null for nil. */
    private val ANSWER = object : LuaCodec<JsonElement?> {
        override val expected = "any"

        override fun accepts(call: LuaCall, index: Int) = true

        override fun read(call: LuaCall, index: Int, where: String): JsonElement? =
            if (call.lua.isNil(index)) null else LuaValue(call.host, call.lua, index).json(where)

        override fun push(call: LuaCall, value: JsonElement?) = error("an answer is only read")
    }

    private val LINES = LuaOptional(LuaList(LuaCodecs.STRING))

    fun call(scripts: Scripts, scope: Scope, function: LuaFunction, arguments: List<Any?>, what: String): JsonElement? =
        answer(scripts.callKept(scope, function.ref, arguments, "wasm", ANSWER, "stub.$what"), what)

    fun run(scripts: Scripts, scope: Scope, function: LuaFunction, options: Map<String, Any?>): WasmRunResult =
        answer(scripts.callKept(scope, function.ref, listOf(options), "wasm", WasmRunResult.Codec, "stub.run"), "run")
            ?: throw WorkFailed("the stand-in's run returned nothing: return { exit_code = 0, stdout = \"\", stderr = \"\" }")

    fun lines(scripts: Scripts, scope: Scope, function: LuaFunction, line: String, process: LuaHandle.WasmProcess): List<String> =
        answer<List<String>>(scripts.callKept(scope, function.ref, listOf(line, process), "wasm", LINES, "stub.input"), "input").orEmpty()

    /** A stand-in function's answer as `call` gives it: a list of integers and numbers. */
    fun values(answer: JsonElement?, name: String): List<Any?> {
        // An empty table is no results, whichever way the codec wrote it.
        if (answer is JsonObject && answer.isEmpty()) return emptyList()
        val list = answer as? JsonArray ?: throw WorkFailed("the stand-in's $name returned ${kind(answer)}, not a list of results")
        return list.map { value ->
            val primitive = value as? JsonPrimitive
            primitive?.takeIf { !it.isString }?.let { it.longOrNull ?: it.doubleOrNull }
                ?: throw WorkFailed("the stand-in's $name returned a result that isn't a number")
        }
    }

    /** A stand-in function's answer as `call_text` gives it: text. */
    fun text(answer: JsonElement?, name: String): String = (answer as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw WorkFailed("the stand-in's $name returned ${kind(answer)}, not text")

    private fun kind(answer: JsonElement?) = when (answer) {
        null, JsonNull -> "nothing"
        is JsonArray -> "a list"
        is JsonPrimitive -> if (answer.isString) "text" else "a value"
        else -> "a table"
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> answer(result: CallResult, what: String): T? = when (result) {
        is CallResult.Ok -> result.value as T?
        is CallResult.Failed -> throw WorkFailed("the stand-in's $what failed: ${result.failure.message}")
        CallResult.Absent -> throw WorkFailed("the test that stood in for the module has ended")
    }
}
