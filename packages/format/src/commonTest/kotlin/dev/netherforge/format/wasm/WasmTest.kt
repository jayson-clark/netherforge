package dev.netherforge.format.wasm

import dev.netherforge.format.project.Kinds
import dev.netherforge.format.project.MapProjectSource
import dev.netherforge.format.project.PathRole
import dev.netherforge.format.project.ProjectSnapshot
import dev.netherforge.format.project.Projects
import dev.netherforge.format.project.Requirement
import dev.netherforge.format.testManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `wasm/<id>/`: a module and its data, checked by what its host read of it. */
class WasmTest {
    private fun load(wasm: Map<String, WasmInfo>?, vararg files: Pair<String, String?>): ProjectSnapshot =
        Projects.load(MapProjectSource(mapOf("netherforge.json" to testManifest()) + files, wasm = wasm))

    private fun codes(snapshot: ProjectSnapshot) = snapshot.problems.map { it.code }

    @Test
    fun theModuleFileMakesTheFolderOneAndTheRestIsItsData() {
        fun at(path: String) = Kinds.classify(path)?.let { listOf(it.kind, it.id, it.role, it.rest) }
        assertEquals(listOf("wasm", "chess", PathRole.MAIN, "module.wasm"), at("wasm/chess/module.wasm"))
        assertEquals(listOf("wasm", "chess", PathRole.FILE, "nets/big.nnue"), at("wasm/chess/nets/big.nnue"))
        val snapshot = load(null, "wasm/chess/module.wasm" to null, "wasm/chess/nets/big.nnue" to null, "wasm/chess/book.bin" to null)
        assertEquals(emptyList(), snapshot.problems)
        assertEquals(listOf("book.bin", "nets/big.nnue"), snapshot.models(WasmModuleKind).getValue("chess").data)
    }

    @Test
    fun readsWhatAModuleImportsAndExports() {
        val info = WasmReader.read(
            module(
                types = listOf(listOf(I32, I32) to listOf(I32), emptyList<Int>() to emptyList()),
                imports = listOf(Triple("wasi_snapshot_preview1", "fd_write", 0)),
                functions = listOf(0, 1),
                memory = Triple(2, 16, false),
                exports = listOf(Triple("add", 0x00, 1), Triple("_start", 0x00, 2), Triple("memory", 0x02, 0))
            )
        )
        assertNull(info.error)
        assertEquals(listOf(WasmImport("wasi_snapshot_preview1", "fd_write", WasmExtern.FUNCTION)), info.imports)
        assertEquals(
            listOf(
                WasmExport("add", WasmExtern.FUNCTION, listOf("i32", "i32"), listOf("i32")),
                WasmExport("_start", WasmExtern.FUNCTION, emptyList(), emptyList()),
                WasmExport("memory", WasmExtern.MEMORY)
            ),
            info.exports
        )
        assertEquals(WasmMemory(2, 16), info.memory)
        assertTrue(info.command)
    }

    @Test
    fun saysWhyBytesArentAModule() {
        assertEquals("it isn't a WebAssembly module (it doesn't start with \\0asm)", WasmReader.read("hello".encodeToByteArray()).error)
        val component = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x0D, 0x00, 0x01, 0x00)
        assertTrue(WasmReader.read(component).error!!.contains("component"))
        // A section that says it's longer than the file.
        val cut = module(types = listOf(emptyList<Int>() to emptyList())).let { it.copyOf(it.size - 1) }
        assertEquals("section 1 runs past the end of the file", WasmReader.read(cut).error)
        // A count that couldn't fit in its section is refused before anything is made of it.
        val forged = header + section(1, leb(1_000_000))
        assertTrue(WasmReader.read(forged).error!!.contains("more than its section's bytes can hold"))
    }

    @Test
    fun aModuleImportsWasiAlone() {
        val path = "wasm/engine/module.wasm"
        fun problems(info: WasmInfo) = load(mapOf(path to info), path to null).problems.map { it.code to it.message }
        val start = WasmExport("_start", WasmExtern.FUNCTION, emptyList(), emptyList())

        assertEquals(emptyList(), problems(WasmInfo(imports = listOf(wasi("fd_write")), exports = listOf(start))))
        assertEquals(
            listOf(
                "wasm.import" to
                    "The module imports env.now, which the server doesn't provide: a module may import only WASI's functions (wasi_snapshot_preview1)",
                "wasm.import" to "The module imports wasi_snapshot_preview1.fd_teleport, which isn't a function of WASI preview 1"
            ),
            problems(
                WasmInfo(imports = listOf(WasmImport("env", "now", WasmExtern.FUNCTION), wasi("fd_teleport")), exports = listOf(start))
            )
        )
        // A threads build says so once, whatever gave it away.
        assertEquals(
            listOf("wasm.threads"),
            problems(
                WasmInfo(
                    imports = listOf(
                        WasmImport("env", "memory", WasmExtern.MEMORY),
                        WasmImport("wasi", "thread-spawn", WasmExtern.FUNCTION)
                    ),
                    exports = listOf(start),
                    memory = WasmMemory(17, 16384, shared = true, imported = true)
                )
            ).map { it.first }
        )
        assertEquals(
            listOf("wasm.memory"),
            problems(WasmInfo(exports = listOf(start), memory = WasmMemory(1, memory64 = true))).map {
                it.first
            }
        )
        assertEquals(listOf("wasm.exports"), problems(WasmInfo(exports = listOf(WasmExport("memory", WasmExtern.MEMORY)))).map { it.first })
        assertEquals(listOf("wasm.invalid" to "module.wasm can't be run: it ends too soon"), problems(WasmInfo(error = "it ends too soon")))
    }

    @Test
    fun aHostThatReadsModulesSaysWhenOneCantBeRead() {
        val path = "wasm/engine/module.wasm"
        assertEquals(listOf("wasm.invalid"), codes(load(emptyMap(), path to null)))
        // One that reads none (the editor) says nothing: the dev server does.
        assertEquals(emptyList(), codes(load(null, path to null)))
    }

    @Test
    fun runningModulesIsARequirement() {
        assertEquals(Requirement.Wasm, Requirement.parse("wasm"))
        assertEquals("\"requires\": { \"wasm\": true }", Requirement.Wasm.declaration)
    }

    private fun wasi(name: String) = WasmImport("wasi_snapshot_preview1", name, WasmExtern.FUNCTION)

    private companion object {
        const val I32 = 0x7F
        val header = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)

        fun leb(value: Int): ByteArray {
            val out = mutableListOf<Byte>()
            var rest = value
            do {
                var b = rest and 0x7F
                rest = rest ushr 7
                if (rest != 0) b = b or 0x80
                out += b.toByte()
            } while (rest != 0)
            return out.toByteArray()
        }

        fun name(text: String): ByteArray = text.encodeToByteArray().let { leb(it.size) + it }

        fun section(id: Int, body: ByteArray): ByteArray = byteArrayOf(id.toByte()) + leb(body.size) + body

        fun vector(items: List<ByteArray>): ByteArray = items.fold(leb(items.size)) { all, it -> all + it }

        /** A module of the given sections, each function's body empty. */
        fun module(
            types: List<Pair<List<Int>, List<Int>>> = emptyList(),
            imports: List<Triple<String, String, Int>> = emptyList(),
            functions: List<Int> = emptyList(),
            memory: Triple<Int, Int, Boolean>? = null,
            exports: List<Triple<String, Int, Int>> = emptyList()
        ): ByteArray {
            var bytes = header
            if (types.isNotEmpty()) {
                bytes += section(
                    1,
                    vector(
                        types.map { (params, results) ->
                            byteArrayOf(0x60) + vector(params.map { byteArrayOf(it.toByte()) }) +
                                vector(results.map { byteArrayOf(it.toByte()) })
                        }
                    )
                )
            }
            if (imports.isNotEmpty()) {
                bytes +=
                    section(2, vector(imports.map { (m, n, type) -> name(m) + name(n) + byteArrayOf(0x00) + leb(type) }))
            }
            if (functions.isNotEmpty()) bytes += section(3, vector(functions.map(::leb)))
            if (memory != null) bytes += section(5, vector(listOf(byteArrayOf(0x01) + leb(memory.first) + leb(memory.second))))
            if (exports.isNotEmpty()) {
                bytes +=
                    section(7, vector(exports.map { (n, kind, index) -> name(n) + byteArrayOf(kind.toByte()) + leb(index) }))
            }
            if (functions.isNotEmpty()) bytes += section(10, vector(functions.map { byteArrayOf(0x02, 0x00, 0x0B) }))
            return bytes
        }
    }
}
