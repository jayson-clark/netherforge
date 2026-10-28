package dev.netherforge.format.wasm

/**
 * A WebAssembly module: `wasm/<id>/`, whose `module.wasm` ([MAIN]) is the module and every other file in the
 * folder its [data], which the module can read (never write) as files from its own root while it runs: a chess
 * engine's network, a dictionary. Format never reads either: the server compiles and runs the module, and what a
 * host reads of it ([WasmInfo]) is checked through [dev.netherforge.format.project.ProjectSource.wasm].
 */
data class WasmModuleFile(val id: String, val data: List<String>) {
    companion object {
        /** The file that makes a folder a module. */
        const val MAIN = "module.wasm"
    }
}
