package dev.netherforge.plugin.wasm

/**
 * `wasm:` in `config.yml`, read on dev servers too: how WebAssembly modules (`nf.wasm`) run. Calls, runs and
 * compiling share [threads] threads (each is at most one core); a started program holds a thread of its own while
 * it runs, at most [maxProcessesTotal] in all. An instance or program may use at most [maxMemoryMb] of memory, and
 * the ones running together at most [maxMemoryTotalMb] (each counted at the most it may grow to); a call or run is
 * stopped after [maxCallSeconds]. [compile] off runs every module in the interpreter, many times slower, for a JVM
 * where making classes is a problem.
 */
data class WasmConfig(
    val threads: Int = 2,
    val compile: Boolean = true,
    val maxModuleMb: Int = 64,
    val maxMemoryMb: Int = 256,
    val maxMemoryTotalMb: Int = 1024,
    val maxCallSeconds: Int = 10,
    val maxQueuedCallsPerPackage: Int = 64,
    val maxInstancesPerPackage: Int = 8,
    val maxProcessesPerPackage: Int = 2,
    val maxProcessesTotal: Int = 4,
    val maxOutputBytes: Int = 1_048_576,
    val maxLineBytes: Int = 65_536,
    val maxQueuedLines: Int = 1000,
    val linesPerTick: Int = 100
)
