package dev.netherforge.plugin.wasm
import dev.netherforge.plugin.WasmFixtures
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/**
 * How fast modules run on Chicory, its compiler against its interpreter: a tight loop of integer maths, and, given
 * a single-threaded WASI build of Stockfish (`NETHERFORGE_STOCKFISH`: a folder with its `module.wasm` and its
 * network file, as `wasm/<id>/` would hold them), its own `bench` in nodes a second. Not a pass/fail test: run it by
 * hand (`NETHERFORGE_BENCH=1 [NETHERFORGE_STOCKFISH=dir] node tools/gradle.mjs :plugin:runtime:test --tests
 * '*WasmBenchmark*' -i`). The guide's numbers (docs/guide/wasm.md) came from it.
 */
@EnabledIfEnvironmentVariable(named = "NETHERFORGE_BENCH", matches = "1")
class WasmBenchmark {
    @Test
    fun `a loop of integer maths, compiled and interpreted`() {
        for (compile in listOf(true, false)) {
            val module = CompiledModule.compile("loop", WasmFixtures.loop, WasmData.NONE, compile, Long.MAX_VALUE)
            val instance = module.instantiate(WasmIo("loop"), 16).instance
            val n = if (compile) 200_000_000L else 5_000_000L
            instance.export("spin").apply(n / 10)
            val started = System.nanoTime()
            instance.export("spin").apply(n)
            val seconds = (System.nanoTime() - started) / 1e9
            println("loop ${if (compile) "compiled" else "interpreted"}: ${"%.0f".format(n / seconds / 1e6)} million iterations a second")
        }
    }

    @Test
    fun `stockfish's bench, compiled`() {
        val folder = System.getenv("NETHERFORGE_STOCKFISH")?.let(Path::of) ?: return println("NETHERFORGE_STOCKFISH not set: skipped")
        val files = Files.list(folder).use { list -> list.toList() }.filter { it.fileName.toString() != "module.wasm" }
        val data = WasmData(files.associate { it.fileName.toString() to it })
        val started = System.nanoTime()
        val module = CompiledModule.compile("stockfish", Files.readAllBytes(folder.resolve("module.wasm")), data, true, Long.MAX_VALUE)
        println("stockfish compiled in ${(System.nanoTime() - started) / 1_000_000} ms")
        for (depth in listOf(10, 13)) {
            val output = ByteArrayOutputStream()
            val running = module.instantiate(WasmIo("stockfish", "bench 16 1 $depth\nquit\n".byteInputStream(), stderr = output), 16 * 512)
            try {
                running.instance.export("_start").apply()
            } catch (_: Exception) {
                // `quit` exits.
            }
            val report = output.toString(Charsets.UTF_8).lines().filter { it.startsWith("Nodes") || it.startsWith("Total time") }
            println("stockfish bench depth $depth: ${report.joinToString(", ")}")
        }
    }
}
