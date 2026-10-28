package dev.netherforge.format.wasm

/**
 * Reads a WebAssembly module's sections far enough for [WasmInfo]: its types, imports, functions, memories,
 * exports and start, skipping every other section by its size. It checks the binary's framing (the header, each
 * section inside the file, each vector inside its section), not the code: the server's runtime validates that
 * when it compiles the module, and says so as a problem then.
 */
object WasmReader {
    /** `\0asm`, then version 1 of the core format. */
    private val MAGIC = byteArrayOf(0x00, 0x61, 0x73, 0x6D)
    private val VERSION = byteArrayOf(0x01, 0x00, 0x00, 0x00)

    /** Version 13 of the binary, with layer 1: a component of the component model, not a core module. */
    private val COMPONENT = byteArrayOf(0x0D, 0x00, 0x01, 0x00)

    private const val TYPE = 1
    private const val IMPORT = 2
    private const val FUNCTION = 3
    private const val MEMORY = 5
    private const val EXPORT = 7
    private const val START = 8

    /** The function type form; any other (a GC proposal's struct, array or rec group) leaves the types unknown. */
    private const val FUNC_TYPE = 0x60

    fun read(bytes: ByteArray): WasmInfo = try {
        readModule(bytes)
    } catch (e: Malformed) {
        WasmInfo(error = e.message)
    }

    private fun readModule(bytes: ByteArray): WasmInfo {
        if (bytes.size < 8 || !bytes.copyOfRange(0, 4).contentEquals(MAGIC)) {
            throw Malformed("it isn't a WebAssembly module (it doesn't start with \\0asm)")
        }
        val version = bytes.copyOfRange(4, 8)
        if (version.contentEquals(COMPONENT)) {
            throw Malformed("it's a WebAssembly component, not a core module: build a core module (wasm32-wasip1)")
        }
        if (!version.contentEquals(
                VERSION
            )
        ) {
            throw Malformed("it's version ${version.joinToString(" ")} of WebAssembly's binary format, not 1")
        }

        // Null once a type isn't a plain function type: signatures are then unknown.
        var types: List<Pair<List<String>, List<String>>>? = emptyList()
        val imports = mutableListOf<WasmImport>()
        val importedFunctionTypes = mutableListOf<Int>()
        val functionTypes = mutableListOf<Int>()
        var memory: WasmMemory? = null
        val exports = mutableListOf<Triple<String, WasmExtern, Long>>()
        var start = false

        val input = Input(bytes, 8, bytes.size)
        while (!input.done) {
            val id = input.byte()
            val size = input.u32()
            val section = input.slice(size, "section $id")
            when (id) {
                TYPE -> types = readTypes(section)
                IMPORT -> repeat(section.count("imports")) {
                    val module = section.name()
                    val name = section.name()
                    when (val kind = section.byte()) {
                        0x00 -> {
                            importedFunctionTypes += section.u32().toInt()
                            imports += WasmImport(module, name, WasmExtern.FUNCTION)
                        }
                        0x01 -> {
                            section.valueType()
                            section.limits()
                            imports += WasmImport(module, name, WasmExtern.TABLE)
                        }
                        0x02 -> {
                            val limits = section.limits()
                            if (memory == null) memory = limits.copy(imported = true)
                            imports += WasmImport(module, name, WasmExtern.MEMORY)
                        }
                        0x03 -> {
                            section.valueType()
                            section.byte()
                            imports += WasmImport(module, name, WasmExtern.GLOBAL)
                        }
                        0x04 -> {
                            section.byte()
                            section.u32()
                            imports += WasmImport(module, name, WasmExtern.TAG)
                        }
                        else -> throw Malformed("import $module.$name is of an unknown kind ($kind)")
                    }
                }
                FUNCTION -> repeat(section.count("functions")) { functionTypes += section.u32().toInt() }
                MEMORY -> repeat(section.count("memories")) {
                    val limits = section.limits()
                    if (memory == null) memory = limits
                }
                EXPORT -> repeat(section.count("exports")) {
                    val name = section.name()
                    val kind = when (val byte = section.byte()) {
                        0x00 -> WasmExtern.FUNCTION
                        0x01 -> WasmExtern.TABLE
                        0x02 -> WasmExtern.MEMORY
                        0x03 -> WasmExtern.GLOBAL
                        0x04 -> WasmExtern.TAG
                        else -> throw Malformed("export \"$name\" is of an unknown kind ($byte)")
                    }
                    exports += Triple(name, kind, section.u32())
                }
                START -> start = true
            }
        }

        val allFunctions = importedFunctionTypes + functionTypes
        return WasmInfo(
            imports = imports,
            exports = exports.map { (name, kind, index) ->
                val signature = if (kind ==
                    WasmExtern.FUNCTION
                ) {
                    allFunctions.getOrNull(index.toInt())?.let { types?.getOrNull(it) }
                } else {
                    null
                }
                WasmExport(name, kind, signature?.first, signature?.second)
            },
            memory = memory,
            start = start
        )
    }

    private fun readTypes(section: Input): List<Pair<List<String>, List<String>>>? {
        val types = mutableListOf<Pair<List<String>, List<String>>>()
        repeat(section.count("types")) {
            if (section.byte() != FUNC_TYPE) return null
            val params = List(section.count("parameters")) { section.valueType() }
            val results = List(section.count("results")) { section.valueType() }
            types += params to results
        }
        return types
    }

    private class Malformed(message: String) : Exception(message)

    /** The bytes from [at] up to [end], read forward. */
    private class Input(private val bytes: ByteArray, private var at: Int, private val end: Int) {
        val done: Boolean get() = at >= end

        fun byte(): Int {
            if (at >= end) throw Malformed("it ends in the middle of a section")
            return bytes[at++].toInt() and 0xFF
        }

        fun u32(): Long = unsigned(32)

        /** An unsigned LEB128 number of at most [bits] bits; one past [Long.MAX_VALUE] is held there. */
        fun unsigned(bits: Int): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = byte()
                val digits = (b and 0x7F).toLong()
                result = if (shift < 63) {
                    result or (digits shl shift)
                } else if (digits != 0L) {
                    Long.MAX_VALUE
                } else {
                    result
                }
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift >= bits + 7) throw Malformed("a number in it is longer than $bits bits")
            }
        }

        /** How many of something follow, held to the bytes left, so a forged count can't make a huge list. */
        fun count(what: String): Int {
            val count = u32()
            if (count > end - at) throw Malformed("it says it has $count $what, more than its section's bytes can hold")
            return count.toInt()
        }

        fun slice(size: Long, what: String): Input {
            if (size > end - at) throw Malformed("$what runs past the end of the file")
            val slice = Input(bytes, at, at + size.toInt())
            at += size.toInt()
            return slice
        }

        fun name(): String {
            val size = u32()
            if (size > end - at) throw Malformed("a name in it runs past its section")
            val name = bytes.copyOfRange(at, at + size.toInt()).decodeToString()
            at += size.toInt()
            return name
        }

        fun valueType(): String = when (val b = byte()) {
            0x7F -> "i32"
            0x7E -> "i64"
            0x7D -> "f32"
            0x7C -> "f64"
            0x7B -> "v128"
            0x70 -> "funcref"
            0x6F -> "externref"
            0x63, 0x64 -> {
                // A heap type follows: an s33, one byte for an abstract one or a type's index.
                do {
                    val next = byte()
                } while (next and 0x80 != 0)
                "ref"
            }
            // The GC proposal's other abstract reference types, each one byte.
            in 0x69..0x74 -> "ref"
            else -> throw Malformed("it has an unknown value type (0x${b.toString(16)})")
        }

        fun limits(): WasmMemory {
            val flags = byte()
            if (flags > 0x07) throw Malformed("a memory or table in it has unknown limits (flags $flags)")
            val bits = if (flags and 0x04 != 0) 64 else 32
            val minimum = unsigned(bits)
            val maximum = if (flags and 0x01 != 0) unsigned(bits) else null
            return WasmMemory(minimum, maximum, shared = flags and 0x02 != 0, memory64 = bits == 64)
        }
    }
}
