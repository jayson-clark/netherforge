package dev.netherforge.plugin.datapack

import dev.netherforge.format.datapack.DatapackEntry
import dev.netherforge.format.datapack.GameDatapack
import dev.netherforge.format.datapack.StartupDatapack
import dev.netherforge.format.datapack.TextJson
import dev.netherforge.format.project.ProjectSnapshot
import dev.netherforge.format.ref.ResourceKey
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists

/**
 * The start-up datapack's bytes, built the one way the adapter's start-up
 * and the runtime's restart check both build it: format's [StartupDatapack]
 * with the server's text serializer, project files copied in as they are.
 */
object StartupDatapackFiles {
    /**
     * Every file of the datapack for [snapshot], by path: empty when the
     * project puts nothing in one. [format] is the server's data pack format;
     * [text] its MiniMessage-to-JSON ([dev.netherforge.plugin.platform.DatapackOps.textJson]);
     * [readBytes] reads a project or package file. [mainWorld] is the server's main world's name, if known.
     */
    fun build(
        snapshot: ProjectSnapshot,
        format: List<Int>,
        text: (String, (String) -> String?) -> String,
        mainWorld: String?,
        readBytes: (String) -> ByteArray?,
        passThrough: Boolean = true,
        game: GameDatapack = GameDatapack.NONE,
        leaveOut: (sky: ResourceKey) -> Boolean = { false }
    ): Map<String, ByteArray> {
        val json = TextJson { mini, glyph -> Json.parseToJsonElement(text(mini, glyph)) }
        return StartupDatapack.build(snapshot, format, json, mainWorld, passThrough, game, leaveOut).mapNotNull { (path, entry) ->
            when (entry) {
                is DatapackEntry.Text -> path to entry.text.encodeToByteArray()
                is DatapackEntry.Copy -> readBytes(entry.projectPath)?.let { path to it }
            }
        }.toMap()
    }

    /**
     * What a server loads as it starts, the one way the adapter's start-up and the restart check work it out: the
     * whole datapack, unless [refusal] (the one on record) is about exactly these files, when it's the datapack
     * without what the refusal named ([DatapackRefusal.leavesOut]): the skies it named, and the project's datapacks
     * ([StartupDatapack.build]'s `passThrough`) unless it named only skies.
     */
    fun forStart(
        snapshot: ProjectSnapshot,
        format: List<Int>,
        text: (String, (String) -> String?) -> String,
        mainWorld: String?,
        readBytes: (String) -> ByteArray?,
        refusal: DatapackRefusal?,
        game: GameDatapack = GameDatapack.NONE
    ): Start {
        val whole = build(snapshot, format, text, mainWorld, readBytes, game = game)
        val hash = DatapackRefusal.hashOf(whole)
        val refused = refusal?.takeIf { it.hash == hash }
        val files = refused?.leavesOut(snapshot.namespace)?.let { left ->
            build(snapshot, format, text, mainWorld, readBytes, left.passThrough, game, left.sky)
        } ?: whole
        // The least a refusal could leave out: what it would if it named nothing it could tell.
        val least = { build(snapshot, format, text, mainWorld, readBytes, passThrough = false, game = game) { true } }
        return Start(files, hash, refused) { !same(whole, least()) }
    }

    /**
     * What [forStart] worked out: the [files] to load, the [hash] of the whole datapack, and the [refused] refusal
     * it left something out for (null when it's all in). [canLeaveOut] says whether the whole datapack has anything a
     * refusal could leave out (the project's datapacks, a sky's twins), so whether one is worth hearing: a start
     * that could leave out nothing would be refused again.
     */
    class Start(val files: Map<String, ByteArray>, val hash: String, val refused: DatapackRefusal?, private val leaving: () -> Boolean) {
        val canLeaveOut: Boolean by lazy { leaving() }
    }

    /** Whether [a] and [b] are the same files with the same bytes. */
    fun same(a: Map<String, ByteArray>, b: Map<String, ByteArray>): Boolean =
        a.keys == b.keys && a.all { (path, bytes) -> bytes.contentEquals(b.getValue(path)) }

    /**
     * Writes [files] as the folder [folder], replacing whatever was there: in
     * a folder beside it first, then moved into place, so the server never
     * reads half of one.
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
