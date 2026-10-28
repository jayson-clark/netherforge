package dev.netherforge.plugin.wasm

import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.wasm.types.FunctionType
import com.dylibso.chicory.wasm.types.ValType
import com.dylibso.chicory.wasm.types.Value
import dev.netherforge.plugin.async.WorkFailed
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.lua.LuaValue
import party.iroiro.luajava.Lua
import java.nio.charset.StandardCharsets

/** An argument to an exported function as a script gave it, read on the main thread. */
sealed interface WasmArgument {
    data class Whole(val value: Long) : WasmArgument

    data class Real(val value: Double) : WasmArgument

    /** Copied into the module's memory and passed as where it starts and how many bytes it is. */
    data class Text(val value: String) : WasmArgument
}

/**
 * Calling a module's exported functions: [arguments] reads a script's `args` and checks them against the function
 * (on the main thread, so a mistake is an error at the script's line), [call] passes them and reads what came back
 * (on the instance's thread). Text crosses through the module's memory: copied in with its `alloc` (or `malloc`),
 * freed after with its `dealloc(pointer, size)` (or `free(pointer)`), and read back from a pointer and length.
 */
object WasmCalls {
    private const val MAX_ARGUMENTS = 64

    /** [table] (the script's `args`, a list) checked against [name]'s [type] in [module]. */
    fun arguments(module: CompiledModule, name: String, type: FunctionType, table: LuaValue?): List<WasmArgument> {
        val given = if (table == null) emptyList() else read(table, "$name's args")
        val params = type.params()
        var at = 0
        for ((index, argument) in given.withIndex()) {
            val where = "args[${index + 1}]"
            val param = params.getOrNull(at)
                ?: throw LuaApiException("$name takes ${describe(params)}, but it was given more arguments ($where is one too many)")
            when (argument) {
                is WasmArgument.Text -> {
                    if (param != ValType.I32 || params.getOrNull(at + 1) != ValType.I32) {
                        throw LuaApiException(
                            "$where is a string, which is passed as two i32s (where it starts and its size), but $name takes " +
                                "${CompiledModule.typeName(param)} there"
                        )
                    }
                    if (allocator(module) == null) {
                        throw LuaApiException(
                            "$where is a string, but module \"${module.key}\" exports no alloc(size) or malloc(size) to copy it into"
                        )
                    }
                    at += 2
                }
                is WasmArgument.Whole -> {
                    when (param) {
                        ValType.I32 -> if (argument.value !in I32_RANGE) {
                            throw LuaApiException("$where is ${argument.value}, which doesn't fit $name's i32 there")
                        }
                        ValType.I64, ValType.F32, ValType.F64 -> Unit
                        else -> throw LuaApiException(
                            "$name takes a ${CompiledModule.typeName(param)} at $where, which a script can't pass"
                        )
                    }
                    at++
                }
                is WasmArgument.Real -> {
                    when (param) {
                        ValType.F32, ValType.F64 -> Unit
                        ValType.I32, ValType.I64 ->
                            throw LuaApiException(
                                "$where is ${argument.value}, but $name takes an ${CompiledModule.typeName(param)} there: give an integer"
                            )
                        else -> throw LuaApiException(
                            "$name takes a ${CompiledModule.typeName(param)} at $where, which a script can't pass"
                        )
                    }
                    at++
                }
            }
        }
        if (at != params.size) throw LuaApiException("$name takes ${describe(params)}, but it was given fewer arguments")
        return given
    }

    private fun describe(params: List<ValType>) =
        if (params.isEmpty()) "no arguments" else "(${params.joinToString(", ") { CompiledModule.typeName(it) }})"

    /** The list at [table]: integers, numbers and strings, with no holes. */
    private fun read(table: LuaValue, what: String): List<WasmArgument> = table.read { lua, index ->
        val found = HashMap<Long, WasmArgument>()
        lua.pushNil()
        while (lua.next(index) != 0) {
            val key = lua.top - 1
            val value = lua.top
            val position = if (lua.type(key) == Lua.LuaType.NUMBER && lua.isInteger(key)) lua.toInteger(key) else 0L
            if (position < 1 || position > MAX_ARGUMENTS) {
                lua.pop(2)
                throw LuaApiException("$what is a list of at most $MAX_ARGUMENTS values, but it has a key that isn't a position in one")
            }
            found[position] = when (lua.type(value)) {
                Lua.LuaType.NUMBER -> if (lua.isInteger(
                        value
                    )
                ) {
                    WasmArgument.Whole(lua.toInteger(value))
                } else {
                    WasmArgument.Real(lua.toNumber(value))
                }
                Lua.LuaType.STRING -> WasmArgument.Text(lua.toString(value)!!)
                else -> {
                    val type = lua.type(value)?.name?.lowercase()
                    lua.pop(2)
                    throw LuaApiException("args[$position] is a $type: a module takes integers, numbers and strings")
                }
            }
            lua.pop(1)
        }
        (1L..found.size.toLong()).map { found[it] ?: throw LuaApiException("$what has a hole at $it: give every argument in order") }
    }

    /** Calls [name] in [instance] with [arguments], already checked; what it returned, as Chicory gives it. */
    fun call(
        instance: Instance,
        module: CompiledModule,
        name: String,
        arguments: List<WasmArgument>,
        read: (LongArray, Instance) -> Any?
    ): Any? {
        val type = module.types.getValue(name)
        val copied = ArrayList<Pair<Int, Int>>()
        val raw = ArrayList<Long>()
        var at = 0
        for (argument in arguments) {
            when (argument) {
                is WasmArgument.Text -> {
                    val bytes = argument.value.toByteArray(StandardCharsets.UTF_8)
                    val pointer = copyIn(instance, module, bytes)
                    copied += pointer to bytes.size
                    raw += pointer.toLong()
                    raw += bytes.size.toLong()
                    at += 2
                }
                is WasmArgument.Whole -> raw += whole(type.params()[at++], argument.value)
                is WasmArgument.Real -> raw += real(type.params()[at++], argument.value)
            }
        }
        val results = instance.export(name).apply(*raw.toLongArray()) ?: LongArray(0)
        // Only after a call that returned: one that trapped or was stopped leaves its instance to be closed, and
        // running its code again (its free, with no time limit left) could only fail or hide why it failed.
        for ((pointer, size) in copied) free(instance, module, pointer, size)
        return read(results, instance)
    }

    /** [results] of a call to a function of [type]: each `i32`/`i64` a `Long`, each `f32`/`f64` a `Double`. */
    fun values(type: FunctionType, results: LongArray): List<Any?> = type.returns().mapIndexed { i, result ->
        val raw = results[i]
        when (result) {
            ValType.I32 -> raw.toInt().toLong()
            ValType.I64 -> raw
            ValType.F32 -> Value.longToFloat(raw).toDouble()
            ValType.F64 -> Value.longToDouble(raw)
            else -> throw WorkFailed("it returned a ${CompiledModule.typeName(result)}, which a script can't take")
        }
    }

    /** Whether [type] answers with text (two `i32`s, or one `i64` holding both): what `call_text` checks first. */
    fun answersText(type: FunctionType): Boolean =
        type.returns() == listOf(ValType.I32, ValType.I32) || type.returns() == listOf(ValType.I64)

    /** The text [results] point at in [instance]'s memory (at most [maxBytes]), then freed as an argument would be. */
    fun text(instance: Instance, module: CompiledModule, type: FunctionType, results: LongArray, maxBytes: Int): String {
        val (pointer, size) = if (type.returns().size == 2) {
            results[0].toInt() to results[1].toInt()
        } else {
            (results[0] ushr 32).toInt() to results[0].toInt()
        }
        if (size < 0 || size > maxBytes) {
            free(instance, module, pointer, size)
            throw WorkFailed(
                "it answered ${Integer.toUnsignedString(size)} bytes of text, more than a call may (wasm.max-output-bytes, $maxBytes)"
            )
        }
        // Freed once read: a pointer its memory doesn't have fails the read, and isn't handed to its free.
        val text = String(instance.memory().readBytes(pointer, size), StandardCharsets.UTF_8)
        free(instance, module, pointer, size)
        return text
    }

    private fun whole(type: ValType, value: Long): Long = when (type) {
        ValType.I32 -> value.toInt().toLong()
        ValType.F32 -> Value.floatToLong(value.toFloat())
        ValType.F64 -> Value.doubleToLong(value.toDouble())
        else -> value
    }

    private fun real(type: ValType, value: Double): Long = if (type ==
        ValType.F32
    ) {
        Value.floatToLong(value.toFloat())
    } else {
        Value.doubleToLong(value)
    }

    private fun allocator(module: CompiledModule): String? = ALLOCATORS.firstOrNull { module.types[it] == ONE_I32_TO_I32 }

    private fun copyIn(instance: Instance, module: CompiledModule, bytes: ByteArray): Int {
        val alloc = allocator(module) ?: throw WorkFailed("the module exports no alloc(size) or malloc(size)")
        val pointer = instance.export(alloc).apply(bytes.size.toLong())[0].toInt()
        if (pointer == 0 && bytes.isNotEmpty()) throw WorkFailed("$alloc(${bytes.size}) gave no memory")
        instance.memory().write(pointer, bytes)
        return pointer
    }

    private fun free(instance: Instance, module: CompiledModule, pointer: Int, size: Int) {
        when {
            module.types["dealloc"]?.params() == listOf(
                ValType.I32,
                ValType.I32
            ) -> instance.export("dealloc").apply(pointer.toLong(), size.toLong())
            module.types["free"]?.params() == listOf(ValType.I32) -> instance.export("free").apply(pointer.toLong())
        }
    }

    /** The types a result may be for `call`: numbers a script can take. */
    val VALUE_TYPES: Set<ValType> = setOf(ValType.I32, ValType.I64, ValType.F32, ValType.F64)

    private val ALLOCATORS = listOf("alloc", "malloc")
    private val ONE_I32_TO_I32 = FunctionType.of(listOf(ValType.I32), listOf(ValType.I32))
    private val I32_RANGE = Int.MIN_VALUE.toLong()..0xFFFF_FFFFL
}
