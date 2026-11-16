package dev.netherforge.plugin.datapack

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists

/**
 * The start-up datapack's files on disk: what the Rust runtime built (`NativeRuntime.startupDatapack`) written where
 * the server discovers it, and read back.
 */
object StartupDatapackFiles {
    /** What the server loads as it starts, as the runtime worked it out (see `crates/runtime/src/reload.rs`, `for_start`). */
    class Start(
        /** Every file, by its path in the datapack. */
        val files: Map<String, ByteArray>,
        /** The whole datapack's hash, which a refusal is recorded against. */
        val hash: String,
        /** Whether the refusal on record left something out. */
        val leftOut: Boolean,
        /** Whether the whole datapack has anything a refusal could leave out, so whether one is worth hearing. */
        val canLeaveOut: Boolean
    )

    /** Whether [a] and [b] are the same files with the same bytes. */
    fun same(a: Map<String, ByteArray>, b: Map<String, ByteArray>): Boolean =
        a.keys == b.keys && a.all { (path, bytes) -> bytes.contentEquals(b.getValue(path)) }

    /**
     * Writes [files] as the folder [folder], replacing whatever was there: in a folder beside it first, then moved
     * into place, so the server never reads half of one.
     */
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun write(folder: Path, files: Map<String, ByteArray>) {
        val staging = folder.resolveSibling("${folder.fileName}.new")
        if (staging.exists()) staging.deleteRecursively()
        for ((path, bytes) in files) {
            val file = staging.resolve(path)
            Files.createDirectories(file.parent)
            Files.write(file, bytes)
        }
        if (folder.exists()) folder.deleteRecursively()
        Files.createDirectories(staging)
        Files.move(staging, folder, StandardCopyOption.ATOMIC_MOVE)
    }

    /** Every file under [folder], by its path there with `/`; empty when there's no folder. */
    fun read(folder: Path): Map<String, ByteArray> {
        if (!Files.isDirectory(folder)) return emptyMap()
        return Files.walk(folder).use { paths ->
            paths.filter(Files::isRegularFile).toList().associate { file ->
                folder.relativize(file).joinToString("/") to Files.readAllBytes(file)
            }
        }
    }
}
