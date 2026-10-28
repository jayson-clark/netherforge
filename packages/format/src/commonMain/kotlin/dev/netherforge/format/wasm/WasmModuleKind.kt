package dev.netherforge.format.wasm

import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.project.Contents
import dev.netherforge.format.project.FilesKind
import dev.netherforge.format.project.KindContext
import dev.netherforge.format.project.Layout

/**
 * `wasm/<id>/`: a WebAssembly module scripts run with `nf.wasm` (heavy computation: a chess engine, pathfinding,
 * noise), and the data files it reads. Built for WASI preview 1 and single-threaded, it imports nothing but WASI's
 * functions: whatever it computes, it can only answer the script that called it. Format checks what its host read
 * of it (what it imports and exports); the server compiles it.
 */
object WasmModuleKind : FilesKind<WasmModuleFile>("wasm", "wasm", Layout.Folder(WasmModuleFile.MAIN), Contents.BINARY) {
    override fun of(id: String, files: List<String>) = WasmModuleFile(id, files.filter { it != WasmModuleFile.MAIN })

    override fun crossCheck(value: WasmModuleFile, ctx: KindContext) {
        if (!ctx.readsWasm) return
        val info = ctx.wasm(ctx.placeOf(this, ctx.id).path)
        if (info == null) {
            ctx.sink.report(ProblemCodes.WASM_INVALID, "${WasmModuleFile.MAIN} can't be read")
            return
        }
        info.error?.let {
            ctx.sink.report(ProblemCodes.WASM_INVALID, "${WasmModuleFile.MAIN} can't be run: $it")
            return
        }
        val threaded = info.imports.filter(WasmImports::threaded)
        if (threaded.isNotEmpty() || info.memory?.shared == true) {
            val what = threaded.firstOrNull()?.let { "it imports ${it.module}.${it.name}" } ?: "its memory is shared"
            ctx.sink.report(
                ProblemCodes.WASM_THREADS,
                "The module was built for threads ($what), which the server doesn't run: build it single-threaded " +
                    "(for wasm32-wasip1, not wasm32-wasip1-threads)"
            )
        }
        for (import in info.imports) {
            if (WasmImports.provided(import) || WasmImports.threaded(import)) continue
            val what = "${import.module}.${import.name}"
            ctx.sink.report(
                ProblemCodes.WASM_IMPORT,
                if (import.module == WasmImports.WASI) {
                    "The module imports $what, which isn't a function of WASI preview 1"
                } else {
                    "The module imports $what, which the server doesn't provide: a module may import only WASI's functions " +
                        "(${WasmImports.WASI})"
                }
            )
        }
        if (info.memory?.memory64 == true) {
            ctx.sink.report(
                ProblemCodes.WASM_MEMORY,
                "The module's memory is 64-bit (memory64), which the server doesn't run: build it for wasm32"
            )
        }
        if (info.exports.none { it.kind == WasmExtern.FUNCTION }) {
            ctx.sink.report(ProblemCodes.WASM_EXPORTS, "The module exports no function: nothing can call or run it")
        }
    }
}
