package dev.netherforge.plugin.wasm

import com.dylibso.chicory.compiler.InterpreterFallback
import com.dylibso.chicory.compiler.MachineFactoryCompiler
import com.dylibso.chicory.runtime.ByteArrayMemory
import com.dylibso.chicory.runtime.ImportFunction
import com.dylibso.chicory.runtime.ImportValues
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.runtime.Machine
import com.dylibso.chicory.wasi.WasiOptions
import com.dylibso.chicory.wasi.WasiPreview1
import com.dylibso.chicory.wasm.ChicoryException
import com.dylibso.chicory.wasm.Parser
import com.dylibso.chicory.wasm.WasmModule
import com.dylibso.chicory.wasm.types.ExternalType
import com.dylibso.chicory.wasm.types.FunctionImport
import com.dylibso.chicory.wasm.types.FunctionType
import com.dylibso.chicory.wasm.types.MemoryLimits
import com.dylibso.chicory.wasm.types.ValType
import dev.netherforge.format.wasm.WasmImports
import dev.netherforge.plugin.async.WorkFailed
import dev.netherforge.plugin.binding.WasmFunction
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.time.Clock
import java.util.function.Function

/**
 * A module the server compiled: Chicory's parsed and validated [module], the [machine] its compiler made of it (JVM
 * classes; null when `wasm.compile` is off, and the interpreter runs it), the [functions] it exports and its [data].
 * Made off the main thread by [compile], once per load of its file, and shared by every instance of it.
 */
class CompiledModule(
    /** Its name as the server knows it: `chess`, or `library:chess` for a package's. */
    val key: String,
    val module: WasmModule,
    private val machine: Function<Instance, Machine>?,
    val functions: List<WasmFunction>,
    val data: WasmData,
    /** The memory it starts with, in pages (0 for none). */
    val initialPages: Long
) {
    /** Exported functions by name, with Chicory's types. */
    val types: Map<String, FunctionType> = exportTypes(module)

    val command: Boolean get() = WasmImports.COMMAND_ENTRY in types

    /**
     * A new instance, its start function run. Its memory may grow to [maxPages] at most (fewer when the module
     * says so); one that starts bigger fails. [io] is its input, output and arguments. The [Running] owns what the
     * instance holds besides its memory (its WASI state, its open data files) until it's closed.
     */
    fun instantiate(io: WasmIo, maxPages: Long): Running {
        if (initialPages > maxPages) {
            throw WorkFailed(
                "module \"$key\" starts with ${pagesInMb(initialPages)} MiB of memory, more than the server lets one have " +
                    "(wasm.max-memory-mb, ${pagesInMb(maxPages)} MiB)"
            )
        }
        val options = WasiOptions.builder()
            .withStdin(io.stdin)
            .withStdout(io.stdout)
            .withStderr(io.stderr)
            .withArguments(listOf(io.name) + io.arguments)
            .withClock(Clock.systemUTC())
            .withRandom(SecureRandom())
            .apply { for ((name, value) in io.environment) withEnvironment(name, value) }
            .build()
        val wasi = WasiPreview1.builder().withOptions(options).build()
        val functions = wasi.toHostFunctions().associateBy { it.name() }
        val files = WasmFiles(data, functions)
        val replaced = files.functions().associateBy { it.name() }
        val imports = ImportValues.builder().withFunctions(functions.values.map { replaced[it.name()] ?: it as ImportFunction }).build()
        try {
            val builder = Instance.builder(module)
                .withImportValues(imports)
                // The module's start section runs as it's made; a command's _start is ours to call, in its time limit.
                .withStart(false)
                .withMemoryFactory { limits ->
                    val cap = minOf(limits.maximumPages().toLong(), maxPages).toInt()
                    ByteArrayMemory(MemoryLimits(limits.initialPages(), cap))
                }
            machine?.let(builder::withMachineFactory)
            return Running(builder.build(), wasi, files)
        } catch (e: Throwable) {
            files.close()
            wasi.close()
            throw e
        }
    }

    /** An instance and what it holds besides its memory. */
    class Running(val instance: Instance, private val wasi: WasiPreview1, private val files: WasmFiles) : AutoCloseable {
        override fun close() {
            files.close()
            wasi.close()
        }
    }

    companion object {
        /**
         * Parses, validates and (with [compile]) compiles [bytes], the module [key]'s `module.wasm`, refusing what
         * the server can't run: an import that isn't WASI preview 1's, a module over [maxBytes]. A failure is a
         * [WorkFailed] saying why, in words for the problem and the scripts that run it; [warn] hears what it had to
         * make do with (no compiler on this server).
         */
        fun compile(
            key: String,
            bytes: ByteArray,
            data: WasmData,
            compile: Boolean,
            maxBytes: Long,
            warn: (String) -> Unit = {}
        ): CompiledModule {
            if (bytes.size > maxBytes) {
                throw WorkFailed(
                    "module.wasm is ${bytes.size / MIB} MiB, more than the server compiles (wasm.max-module-mb, ${maxBytes / MIB} MiB)"
                )
            }
            val module = try {
                Parser.builder().withValidation(true).build().parse { bytes.inputStream() }
            } catch (e: ChicoryException) {
                throw WorkFailed("module.wasm isn't a valid WebAssembly module: ${e.message}")
            }
            for (import in module.importSection().stream()) {
                val provided = import.importType() == ExternalType.FUNCTION &&
                    import.module() == WasmImports.WASI &&
                    import.name() in WasmImports.FUNCTIONS
                if (!provided) {
                    throw WorkFailed(
                        "module.wasm imports ${import.module()}.${import.name()}, which the server doesn't provide: a module may " +
                            "import only WASI's functions (${WasmImports.WASI}), single-threaded"
                    )
                }
            }
            // Neither the compiler nor the interpreter runs SIMD: refused here, rather than at the call that meets it.
            val simd = module.codeSection().functionBodies().asSequence().flatMap { it.instructions() }
                .firstOrNull { SIMD.containsMatchIn(it.opcode().name) }
            if (simd != null) {
                throw WorkFailed(
                    "module.wasm uses SIMD (${simd.opcode().name.lowercase()}), which the server doesn't run: build it without " +
                        "-msimd128 (or Rust's simd128 target feature)"
                )
            }
            val machine = if (compile) {
                try {
                    // A function too big for one JVM method is interpreted; the rest run as compiled classes.
                    MachineFactoryCompiler.builder(module).withInterpreterFallback(InterpreterFallback.SILENT).compile()
                } catch (e: ChicoryException) {
                    throw WorkFailed("module.wasm couldn't be compiled: ${e.message}")
                } catch (e: LinkageError) {
                    // The compiler writes classes with ASM, which a server that bundles it lends its own of: one too old
                    // for the compiler leaves the module to the interpreter, slower but the same.
                    warn(
                        "module \"$key\" runs in the interpreter: the compiler couldn't run on this server (${e.javaClass.simpleName}: ${e.message})"
                    )
                    null
                }
            } else {
                null
            }
            val initial = module.memorySection().map { if (it.memoryCount() > 0) it.getMemory(0).limits().initialPages().toLong() else 0L }
                .orElse(0L)
            val functions = exportTypes(module).map { (name, type) ->
                WasmFunction(name, type.params().map(::typeName), type.returns().map(::typeName))
            }
            return CompiledModule(key, module, machine, functions, data, initial)
        }

        /** [module]'s exported functions by name, in the order it exports them, with their types. */
        private fun exportTypes(module: WasmModule): Map<String, FunctionType> {
            val imported = module.importSection().stream().filter {
                it is FunctionImport
            }.map { (it as FunctionImport).typeIndex() }.toList()
            val section = module.exportSection()
            return buildMap {
                for (i in 0 until section.exportCount()) {
                    val export = section.getExport(i)
                    if (export.exportType() != ExternalType.FUNCTION) continue
                    val index = export.index()
                    val typeIndex = imported.getOrNull(index) ?: module.functionSection().getFunctionType(index - imported.size)
                    put(export.name(), module.typeSection().getType(typeIndex))
                }
            }
        }

        /** How the API names a value type. */
        fun typeName(type: ValType): String = when (type) {
            ValType.I32 -> "i32"
            ValType.I64 -> "i64"
            ValType.F32 -> "f32"
            ValType.F64 -> "f64"
            ValType.V128 -> "v128"
            ValType.FuncRef -> "funcref"
            ValType.ExternRef -> "externref"
            else -> "ref"
        }

        /** What a SIMD instruction's name starts with in Chicory's opcodes: `V128_LOAD`, `I32x4_SPLAT`, `F64x2_ADD`. */
        private val SIMD = Regex("^(V128|[IF](8|16|32|64)x\\d+)_")

        /** 64 KiB pages in a MiB. */
        const val PAGES_PER_MB = 16L

        fun pagesInMb(pages: Long) = pages / PAGES_PER_MB

        private const val MIB = 1024L * 1024L
    }
}

/** What a running instance reads and writes as its standard streams, and the name and arguments it's run with. */
class WasmIo(
    val name: String,
    val stdin: InputStream = InputStream.nullInputStream(),
    val stdout: OutputStream = OutputStream.nullOutputStream(),
    val stderr: OutputStream = OutputStream.nullOutputStream(),
    val arguments: List<String> = emptyList(),
    val environment: Map<String, String> = emptyMap()
)
