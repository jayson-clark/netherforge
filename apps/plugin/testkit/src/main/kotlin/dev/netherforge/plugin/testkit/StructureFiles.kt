package dev.netherforge.plugin.testkit

import dev.netherforge.format.terrain.StructureTemplate
import java.io.DataInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream

/**
 * A structure file (`structures/<id>.nbt`, Minecraft's gzipped NBT) read the way a test can: the fake's
 * [FakeStructures.template] for a real file (a project's), and the integration test for what format's generator
 * should place where the server read it with the game's own loader. Only what a structure holds is read (no NBT
 * writing, no data fixing, so a file from an older version keeps its old names).
 */
object StructureFiles {
    /** Whether [file] starts as a gzipped file does: a real structure file rather than the fake's text. */
    fun isGzip(file: Path): Boolean = Files.newInputStream(file).use { it.read() == 0x1f && it.read() == 0x8b }

    fun template(file: Path): StructureTemplate {
        val root = DataInputStream(GZIPInputStream(Files.newInputStream(file))).use { input ->
            require(input.readByte().toInt() == COMPOUND) { "$file isn't NBT" }
            input.readUTF()
            compound(input)
        }
        val size = (root["size"] as List<*>).map { (it as Number).toInt() }
        val palette = (root["palette"] ?: (root["palettes"] as List<*>).first()) as List<*>
        val blocks = (root["blocks"] as List<*>).flatMap { block ->
            block as Map<*, *>
            (block["pos"] as List<*>).map { (it as Number).toInt() } + (block["state"] as Number).toInt()
        }
        // A block saved with block data (`nbt`: a chest's items) holds a block entity.
        val withEntity = (root["blocks"] as List<*>).map {
            it as Map<*, *>
        }.filter { "nbt" in it }.map { (it["state"] as Number).toInt() }.toSet()
        return StructureTemplate(size[0], size[1], size[2], palette.map(::stateOf), blocks.toIntArray(), withEntity)
    }

    /** A palette entry as block state text: `Name`/`Properties` (or 26.x's `id`/`properties`), a bare id, or `{"": id}`. */
    private fun stateOf(entry: Any?): String {
        if (entry is String) return entry
        entry as Map<*, *>
        (entry[""] as? String)?.let { return it }
        val name = (entry["id"] ?: entry["Name"]) as String
        val properties = (entry["properties"] ?: entry["Properties"]) as Map<*, *>? ?: return name
        if (properties.isEmpty()) return name
        return name + properties.entries.sortedBy { it.key as String }.joinToString(",", "[", "]") { "${it.key}=${it.value}" }
    }

    private fun compound(input: DataInputStream): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        while (true) {
            val type = input.readByte().toInt()
            if (type == END) return out
            out[input.readUTF()] = payload(input, type)
        }
    }

    private fun payload(input: DataInputStream, type: Int): Any? = when (type) {
        1 -> input.readByte()
        2 -> input.readShort()
        3 -> input.readInt()
        4 -> input.readLong()
        5 -> input.readFloat()
        6 -> input.readDouble()
        7 -> ByteArray(input.readInt()).also(input::readFully).toList()
        8 -> input.readUTF()
        9 -> {
            val of = input.readByte().toInt()
            List(input.readInt()) { payload(input, of) }
        }
        COMPOUND -> compound(input)
        11 -> List(input.readInt()) { input.readInt() }
        12 -> List(input.readInt()) { input.readLong() }
        else -> error("an NBT tag of unknown type $type")
    }

    private const val END = 0
    private const val COMPOUND = 10
}
