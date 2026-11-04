package dev.netherforge.plugin.project

import dev.netherforge.format.Problem
import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.Severity
import dev.netherforge.format.project.ImageInfo
import dev.netherforge.format.project.PackagePaths
import dev.netherforge.format.project.ProjectSnapshot
import dev.netherforge.format.project.ProjectSource
import dev.netherforge.format.resourcepack.ResourcePackFile
import dev.netherforge.format.resourcepack.ResourcePackKind
import dev.netherforge.format.wasm.WasmInfo
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * Resource packs a package's scripts wrote while the server runs: `packs/<name>/` in the package's own `nf.files`
 * folder (`<data>/<namespace>/packs/<name>/pack.json` and its pictures and sounds), read as though they were the
 * package's `resource_packs/<name>/`. So format loads, checks and numbers them with the rest, a script names their
 * glyphs as its own packs' (`<glyph:<name>/<key>>`), an `onDemand` one is pushed like any other, and
 * `nf.packs.rebuild` (which reads everything again) builds what changed and sends it.
 *
 * A pack the package already has under `resource_packs/` keeps its name: a written one of that name isn't read
 * (`runtime.written-pack`). One with errors mustn't take the rest down with it (an error in any pack leaves the
 * whole build as it was, and at start there's no build to keep), so its own problems are reported, as warnings at
 * the file that has them, and the load is done again with **its last good copy**, which is kept in [kept] (beside
 * the plugin's state, out of the scripts' reach) each time it loads clean; without one, it's left out. A
 * deployed project's folder, its hash and the editor never see any of this: the files are the server's.
 */
internal class WrittenPacks(
    /** `nf.files`' root (`plugins/NetherForge/data/`): a package's folder is its namespace. Null: nothing is read. */
    private val data: Path?,
    /** Where each written pack's last good copy is kept, `<kept>/<namespace>/<name>/`. */
    private val kept: Path?
) {
    private enum class Use { LIVE, KEPT, NONE }

    /** One pack as this load reads it: from the scripts' [live] folder or its last good copy, [folder]. */
    private class Chosen(val namespace: String, val id: String, val live: Path, val folder: Path, val use: Use)

    /** The packs whose live copy had errors on an earlier pass of this load, by `<namespace>/<id>`: read from their copy, or not at all. */
    private var fallback: Map<String, Use> = emptyMap()

    /** What this pass read, by namespace then pack id. */
    @Volatile
    private var chosen: Map<String, Map<String, Chosen>> = emptyMap()

    /** What this pass found wrong besides format's problems: a written pack whose name the package has. */
    private val notes = mutableListOf<Problem>()

    /** Each live pack's listing as it was last kept, so an unchanged one isn't copied again. */
    private val keptListing = HashMap<String, String>()

    /**
     * The `pack.json` of every pack the scripts of a namespace the last load read have written now, read or not yet,
     * as a reload names it (a package's at its package path): what `nf.packs.rebuild` reloads, with the packs there were.
     */
    fun packFiles(home: String): List<String> = chosen.keys.sorted().flatMap { namespace ->
        val live =
            data?.resolve(namespace)?.resolve(FOLDER)?.takeIf { it.isDirectory(LinkOption.NOFOLLOW_LINKS) } ?: return@flatMap emptyList()
        val folders = runCatching { live.listDirectoryEntries() }.getOrDefault(emptyList())
        folders.filter {
            !it.name.startsWith(".") && it.resolve(ResourcePackFile.FILE_NAME).isRegularFile()
        }.map { it.name }.sorted().map { id ->
            val local = "${ResourcePackKind.folder}/$id/${ResourcePackFile.FILE_NAME}"
            if (namespace == home) local else PackagePaths.of(namespace, local)
        }
    }

    /** Whether the last load read any written pack. */
    val any: Boolean get() = chosen.values.any { it.isNotEmpty() }

    /** Starts one pass of a load: [broken] are packs to read from their kept copy ([Use.KEPT]) or leave out. */
    private fun begin(broken: Map<String, Use>) {
        fallback = broken
        chosen = emptyMap()
        notes.clear()
    }

    /**
     * Loads with [once] (which opens each package through [over]) until no written pack it read has errors: a broken
     * one is read from its last good copy on the next pass, and left out on the one after. Its problems, and why, are
     * among the load's own.
     */
    fun load(once: () -> ProjectFiles.Load): ProjectFiles.Load {
        val reported = mutableListOf<Problem>()
        var broken = emptyMap<String, Use>()
        var load: ProjectFiles.Load
        while (true) {
            begin(broken)
            load = once()
            val failing = failing(load.snapshot)
            if (failing.isEmpty()) break
            for (pack in failing) reported += report(load.snapshot, pack)
            broken = broken + failing.associate { pack ->
                val next = if (pack.use == Use.LIVE && keptCopy(pack.namespace, pack.id) != null) Use.KEPT else Use.NONE
                key(pack.namespace, pack.id) to next
            }
        }
        keep()
        return ProjectFiles.Load(load.snapshot, load.problems + notes + reported, load.files)
    }

    /**
     * [base] (a package's source, or the project's) with the packs its scripts wrote laid in under `resource_packs/`,
     * as [namespace]'s; [base] itself when there are none.
     */
    fun over(namespace: String, base: ProjectSource): ProjectSource {
        val packs = choose(namespace, base.files())
        if (packs.isEmpty()) return base
        return Overlaid(base, packs.mapValues { DiskProjectSource(it.value.folder) })
    }

    /** [files] (the project's own, as [namespace]) with the files of the packs its scripts wrote, under `resource_packs/`. */
    fun withPacks(namespace: String, files: Collection<String>): Collection<String> {
        val packs = choose(namespace, files)
        return files + packs.flatMap { (id, pack) -> DiskProjectSource(pack.folder).files().map { "${ResourcePackKind.folder}/$id/$it" } }
    }

    /** The written packs of [namespace] this pass reads, given its own files [files]: chosen once per pass. */
    private fun choose(namespace: String, files: Collection<String>): Map<String, Chosen> {
        chosen[namespace]?.let { return it }
        val live = data?.resolve(namespace)?.resolve(FOLDER)
        val found = LinkedHashMap<String, Chosen>()
        if (live != null && live.isDirectory(LinkOption.NOFOLLOW_LINKS)) {
            val own = files.filter { it.startsWith("${ResourcePackKind.folder}/") }.map { it.split('/')[1] }.toSet()
            val folders = try {
                live.listDirectoryEntries().filter { it.isDirectory(LinkOption.NOFOLLOW_LINKS) && !it.name.startsWith(".") }
            } catch (_: IOException) {
                emptyList()
            }
            for (folder in folders.sortedBy { it.name }) {
                val id = folder.name
                if (!folder.resolve(ResourcePackFile.FILE_NAME).isRegularFile()) continue
                if (id in own) {
                    notes += ProblemCodes.RUNTIME_WRITTEN_PACK.at(
                        shown(namespace, id, ResourcePackFile.FILE_NAME),
                        "\"$namespace\" has a resource pack \"$id\" of its own (${ResourcePackKind.folder}/$id/), so the one its " +
                            "scripts wrote of that name isn't read: give it another name"
                    )
                    continue
                }
                val chosen = when (fallback[key(namespace, id)]) {
                    null, Use.LIVE -> Chosen(namespace, id, folder, folder, Use.LIVE)
                    Use.KEPT -> keptCopy(namespace, id)?.let { Chosen(namespace, id, folder, it, Use.KEPT) }
                    Use.NONE -> null
                }
                if (chosen != null) found[id] = chosen
            }
        }
        chosen = chosen + (namespace to found)
        return found
    }

    /**
     * Where a file of [namespace]'s written pack is read from, for a file at [local] (a path in that package, like
     * `resource_packs/featured/textures/a.png`): the pack's folder and the path in it; null for any other file.
     */
    fun sourceOf(namespace: String, local: String): Pair<DiskProjectSource, String>? {
        val prefix = "${ResourcePackKind.folder}/"
        if (!local.startsWith(prefix)) return null
        val id = local.removePrefix(prefix).substringBefore('/')
        val pack = chosen[namespace]?.get(id) ?: return null
        return DiskProjectSource(pack.folder) to local.removePrefix("$prefix$id/")
    }

    /** [problem] as the owner reads it: one in a written pack's files names the file where the scripts wrote it. */
    fun shown(problem: Problem, home: String): Problem {
        val (namespace, local) = PackagePaths.split(problem.file).let { (pkg, path) -> (pkg ?: home) to path }
        val prefix = "${ResourcePackKind.folder}/"
        if (!local.startsWith(prefix)) return problem
        val id = local.removePrefix(prefix).substringBefore('/')
        if (chosen[namespace]?.containsKey(id) != true) return problem
        return problem.copy(file = shown(namespace, id, local.removePrefix("$prefix$id/")))
    }

    /** The written packs this pass read from their live folder whose files have errors. */
    private fun failing(snapshot: ProjectSnapshot): List<Chosen> = chosen.values.flatMap { it.values }.filter { pack ->
        val prefix = fileIn(snapshot, pack.namespace, pack.id)
        pack.use != Use.NONE && snapshot.problems.any { it.severity == Severity.ERROR && it.file.startsWith(prefix) }
    }

    /** What's wrong with [pack]: its problems, at the files the scripts wrote, as warnings, and what's read instead. */
    private fun report(snapshot: ProjectSnapshot, pack: Chosen): List<Problem> {
        val prefix = fileIn(snapshot, pack.namespace, pack.id)
        val own = snapshot.problems.filter { it.file.startsWith(prefix) }
        val where = shown(pack.namespace, pack.id, ResourcePackFile.FILE_NAME)
        val errors = own.count { it.severity == Severity.ERROR }
        val message = when {
            pack.use == Use.KEPT ->
                "The last good copy of the resource pack \"${pack.id}\" that \"${pack.namespace}\"'s scripts wrote has errors " +
                    "too ($errors) now, so it isn't built"
            keptCopy(pack.namespace, pack.id) != null ->
                "The resource pack \"${pack.id}\" that \"${pack.namespace}\"'s scripts wrote has errors ($errors), so its last " +
                    "good copy is built instead"
            else ->
                "The resource pack \"${pack.id}\" that \"${pack.namespace}\"'s scripts wrote has errors ($errors), so it isn't " +
                    "built until they're fixed and nf.packs.rebuild() reads it again"
        }
        val summary = ProblemCodes.RUNTIME_WRITTEN_PACK.at(where, message)
        if (pack.use == Use.KEPT) return listOf(summary)
        return listOf(summary) + own.map {
            it.copy(severity = Severity.WARNING, file = shown(pack.namespace, pack.id, it.file.removePrefix(prefix)))
        }
    }

    /** Where format names [namespace]'s pack [id]'s files in [snapshot]: `resource_packs/<id>/`, at a package path for a package. */
    private fun fileIn(snapshot: ProjectSnapshot, namespace: String, id: String): String {
        val local = "${ResourcePackKind.folder}/$id/"
        return if (namespace == snapshot.namespace) local else PackagePaths.of(namespace, local)
    }

    /**
     * Copies each written pack read from its live folder, which therefore loaded clean, to its last good copy (once
     * per change), and drops the copies of packs that are gone from a namespace this load read.
     */
    private fun keep() {
        val kept = kept ?: return
        for ((namespace, packs) in chosen) {
            for (pack in packs.values.filter { it.use == Use.LIVE }) {
                val listing = listing(pack.live) ?: continue
                val key = key(namespace, pack.id)
                val target = kept.resolve(namespace).resolve(pack.id)
                if (keptListing[key] == listing && target.isDirectory()) continue
                if (copy(pack.live, target)) keptListing[key] = listing
            }
            val folder = kept.resolve(namespace)
            if (!folder.isDirectory()) continue
            val written = data?.resolve(namespace)?.resolve(FOLDER)
            val stale = runCatching { folder.listDirectoryEntries() }.getOrDefault(emptyList()).filter { copy ->
                written == null || !written.resolve(copy.name).resolve(ResourcePackFile.FILE_NAME).isRegularFile()
            }
            for (copy in stale) {
                delete(copy)
                keptListing.remove(key(namespace, copy.name))
            }
        }
    }

    private fun keptCopy(namespace: String, id: String): Path? =
        kept?.resolve(namespace)?.resolve(id)?.takeIf { it.resolve(ResourcePackFile.FILE_NAME).isRegularFile() }

    /** The path a written pack's file has on the server, for problems: in the package's `nf.files` folder. */
    private fun shown(namespace: String, id: String, rest: String): String {
        val folder = data?.resolve(namespace)?.resolve(FOLDER)?.resolve(id)?.toString()?.replace('\\', '/') ?: "$namespace/$FOLDER/$id"
        return if (rest.isEmpty()) folder else "$folder/$rest"
    }

    /** Each file's path, size and time, which change when a script writes it: null when the folder can't be read. */
    private fun listing(folder: Path): String? = try {
        Files.walk(folder).use { paths ->
            paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.map {
                "${folder.relativize(it)}:${Files.size(it)}:${Files.getLastModifiedTime(it).toMillis()}"
            }.sorted().toList().joinToString("\n")
        }
    } catch (_: IOException) {
        null
    }

    /** [from] copied whole to [to], replacing what's there: written beside it first, then moved in. */
    private fun copy(from: Path, to: Path): Boolean = try {
        Files.createDirectories(to.parent)
        val staging = Files.createTempDirectory(to.parent, ".copy-")
        Files.walk(from).use { paths ->
            for (path in paths) {
                if (Files.isSymbolicLink(path)) continue
                val target = staging.resolve(from.relativize(path).toString())
                if (Files.isDirectory(
                        path
                    )
                ) {
                    Files.createDirectories(target)
                } else {
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
        delete(to)
        Files.move(staging, to)
        true
    } catch (_: IOException) {
        false
    }

    private fun delete(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        try {
            Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        } catch (_: IOException) {
            // Tried again on the next load.
        }
    }

    private fun key(namespace: String, id: String) = "$namespace/$id"

    /** A package's source with [packs] (by pack id, each the folder it's read from) laid in under `resource_packs/`. */
    private class Overlaid(private val base: ProjectSource, private val packs: Map<String, DiskProjectSource>) : ProjectSource {
        private fun split(path: String): Pair<DiskProjectSource, String>? {
            val prefix = "${ResourcePackKind.folder}/"
            if (!path.startsWith(prefix)) return null
            val id = path.removePrefix(prefix).substringBefore('/')
            return packs[id]?.let { it to path.removePrefix("$prefix$id/") }
        }

        override fun files(): Collection<String> =
            base.files() + packs.flatMap { (id, source) -> source.files().map { "${ResourcePackKind.folder}/$id/$it" } }

        override fun read(path: String): String? = split(path)?.let { (source, local) -> source.read(local) } ?: base.read(path)

        override val readsImages: Boolean get() = base.readsImages

        override fun image(path: String): ImageInfo? = split(path)?.let { (source, local) -> source.image(local) } ?: base.image(path)

        override val readsWasm: Boolean get() = base.readsWasm

        override fun wasm(path: String): WasmInfo? = base.wasm(path)
    }

    companion object {
        /** The folder of a package's `nf.files` its scripts write packs in. */
        const val FOLDER = "packs"
    }
}
