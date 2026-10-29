package dev.netherforge.plugin.script

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * The one directory scripts may keep things in (`nf.file`).
 *
 * Paths are checked as strings before anything touches the disk, and a path
 * that fails is refused (`nil` in Lua) rather than clamped, so a bad path is
 * something a script can test for instead of a write that went somewhere
 * surprising. The rules are stricter than any one filesystem needs so a
 * project behaves the same on every OS: no `..`, no backslashes, no names that
 * start with a dot or a space, none of Windows' reserved device names.
 *
 * [root] is `plugins/NetherForge/data/` on every server, the editor's dev
 * server included (whose folder is outside the project).
 *
 * **Each package has a folder of its own**, `<root>/<namespace>/`: every path
 * here starts with the namespace (`nf.files.get` puts the calling package's in
 * front, and a `File` never climbs above it), so no package reads or writes
 * another's files unless it's handed one. Each package's folder is held to
 * [limits]: its bytes and its entries (files and folders), counted once and
 * kept up to date by every change made here ([usage]). Files from before
 * folders were per package are moved into the project's ([adopt]).
 */
class ScriptFiles(private val root: Path, private val limits: FilesConfig = FilesConfig()) {
    /** What one package's folder holds. */
    private class Usage(var bytes: Long, var entries: Int)

    /** By namespace, counted the first time it's needed, then kept up to date. */
    private val usages = HashMap<String, Usage>()

    /** The package folder (first segment) of a normalized path. */
    private fun namespaceOf(normalized: String) = normalized.substringBefore('/')

    private fun usage(namespace: String): Usage = usages.getOrPut(namespace) {
        val folder = root.resolve(namespace)
        var bytes = 0L
        var entries = 0
        if (folder.isDirectory()) {
            Files.walk(folder).use { paths ->
                for (path in paths) {
                    if (path == folder) continue
                    entries++
                    if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) bytes += runCatching { Files.size(path) }.getOrDefault(0L)
                }
            }
        }
        Usage(bytes, entries)
    }

    /** Whether [namespace]'s folder has room for [bytes] more and [entries] more. */
    private fun room(namespace: String, bytes: Long, entries: Int): Boolean {
        val usage = usage(namespace)
        return usage.bytes + bytes <= limits.maxBytesPerPackage && usage.entries + entries <= limits.maxEntriesPerPackage
    }

    /** Folders [target] needs made above it, inside its package's folder: each an entry. */
    private fun missingFolders(target: Path, namespace: String): Int {
        val top = root.resolve(namespace)
        var count = 0
        var at = target.parent
        while (at != null && at.startsWith(top) && at != top && !at.exists()) {
            count++
            at = at.parent
        }
        return count
    }

    /**
     * Moves files kept from before each package had a folder into
     * [namespace]'s (the project's), once: they were the project's to see, and
     * nothing says which package wrote which. A marker file (a name scripts
     * can't use) says it's done.
     */
    fun adopt(namespace: String) {
        val marker = root.resolve(LAYOUT_MARKER)
        if (marker.exists()) return
        try {
            Files.createDirectories(root)
            val old = root.listDirectoryEntries().filter { it.name != LAYOUT_MARKER }
            if (old.isNotEmpty()) {
                val moving = Files.createTempDirectory(root, ".moving-")
                for (entry in old) Files.move(entry, moving.resolve(entry.name))
                Files.move(moving, root.resolve(namespace))
            }
            Files.writeString(marker, "Each package's nf.files are in the folder named after its namespace.\n")
        } catch (_: IOException) {
            // Left as it was: the next start tries again.
        }
        usages.clear()
    }

    /** The canonical form of [path] (`""` for the root), or null when it isn't allowed. */
    fun normalize(path: String): String? {
        val trimmed = path.trim('/')
        if (trimmed.isEmpty()) return ""
        if (path.length > MAX_PATH) return null
        val parts = trimmed.split('/')
        if (parts.size > MAX_DEPTH) return null
        if (parts.any { !isName(it) }) return null
        return parts.joinToString("/")
    }

    private fun isName(name: String): Boolean {
        if (name.isEmpty() || name.length > MAX_NAME) return false
        if (!NAME.matches(name) || name.startsWith('.') || name.startsWith(' ') || name.endsWith(' ') || name.endsWith('.')) return false
        val stem = name.substringBefore('.').lowercase()
        return stem !in RESERVED
    }

    private fun resolve(path: String): Path? {
        val normalized = normalize(path) ?: return null
        val resolved = if (normalized.isEmpty()) root else root.resolve(normalized)
        // normalize() rules out `..`; a symlink placed in the folder by hand could
        // still point out of it, so the path must stay inside once links are followed.
        val real = real(resolved) ?: return null
        val realRoot = real(root) ?: return null
        return resolved.takeIf { real.startsWith(realRoot) }
    }

    /**
     * [path] with every symlink along it followed: the deepest part that
     * exists (a link counts, even a dangling one) resolved for real, the rest
     * appended. Null when that part can't be resolved (a dangling link).
     */
    private fun real(path: Path): Path? {
        var existing = path.toAbsolutePath().normalize()
        while (!existing.exists(LinkOption.NOFOLLOW_LINKS)) existing = existing.parent ?: return path.toAbsolutePath().normalize()
        return try {
            existing.toRealPath().resolve(existing.relativize(path.toAbsolutePath().normalize())).normalize()
        } catch (e: IOException) {
            null
        }
    }

    fun exists(path: String): Boolean = resolve(path)?.exists() ?: false

    fun isDirectory(path: String): Boolean = if (normalize(path) == "") true else resolve(path)?.isDirectory() ?: false

    fun size(path: String): Long? = resolve(path)?.takeIf { it.isRegularFile() }?.fileSize()

    /** Names directly inside, sorted; empty for a file or nothing. */
    fun list(path: String): List<String> {
        val dir = resolve(path)?.takeIf { it.isDirectory() } ?: return emptyList()
        return dir.listDirectoryEntries().map { it.name }.filter { isName(it) }.sorted().take(MAX_LIST)
    }

    fun read(path: String): String? = resolve(path)?.takeIf { it.isRegularFile() }?.readText()

    /** Replaces or appends. False for a directory, for text past [MAX_BYTES], or on an I/O failure. */
    fun write(path: String, text: String, append: Boolean): Boolean {
        val target = resolve(path) ?: return false
        if (normalize(path) == "" || target.isDirectory()) return false
        val bytes = text.encodeToByteArray()
        val before = if (target.isRegularFile()) target.fileSize() else null
        val existing = if (append) before ?: 0L else 0L
        if (existing + bytes.size > MAX_BYTES) return false
        val namespace = namespaceOf(normalize(path)!!)
        val grows = existing + bytes.size - (before ?: 0L)
        val made = (if (before == null) 1 else 0) + missingFolders(target, namespace)
        if (!room(namespace, grows, made)) return false
        return try {
            Files.createDirectories(target.parent)
            if (append) {
                Files.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            } else {
                Files.write(target, bytes)
            }
            usage(namespace).apply {
                this.bytes += grows
                entries += made
            }
            true
        } catch (_: IOException) {
            usages.remove(namespace)
            false
        }
    }

    fun makeDirectory(path: String): Boolean {
        val target = resolve(path) ?: return false
        val normalized = normalize(path)!!
        if (!normalized.contains('/')) return target.isDirectory() || runCatching { Files.createDirectories(target) }.isSuccess
        val namespace = namespaceOf(normalized)
        val made = (if (target.exists()) 0 else 1) + missingFolders(target, namespace)
        if (!room(namespace, 0, made)) return false
        return try {
            Files.createDirectories(target)
            usage(namespace).entries += made
            true
        } catch (_: IOException) {
            usages.remove(namespace)
            false
        }
    }

    /** When it last changed, in Unix milliseconds; null when nothing is there. */
    fun modified(path: String): Long? =
        resolve(path)?.takeIf { it.exists() }?.let { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrNull() }

    /**
     * Moves a file or a directory (with everything in it) from [from] to [to],
     * creating directories above [to]. False when there's nothing at [from],
     * something already at [to], either is the root or not allowed, a
     * directory would go inside itself, or the move fails.
     */
    fun move(from: String, to: String): Boolean {
        val (source, target) = pair(from, to) ?: return false
        if (target.startsWith(source)) return false
        // Across packages only through a handle another package was given: recounted, both.
        val fromPackage = namespaceOf(normalize(from)!!)
        val intoPackage = namespaceOf(normalize(to)!!)
        if (fromPackage != intoPackage &&
            !room(intoPackage, sizeOf(source), entriesOf(source) + missingFolders(target, intoPackage))
        ) {
            return false
        }
        return try {
            Files.createDirectories(target.parent)
            Files.move(source, target)
            usages.remove(fromPackage)
            usages.remove(intoPackage)
            true
        } catch (_: IOException) {
            usages.remove(fromPackage)
            usages.remove(intoPackage)
            false
        }
    }

    /** Copies a file from [from] to [to], creating directories above [to]. False for a directory, and as for [move]. */
    fun copy(from: String, to: String): Boolean {
        val (source, target) = pair(from, to) ?: return false
        if (!source.isRegularFile()) return false
        val namespace = namespaceOf(normalize(to)!!)
        val size = source.fileSize()
        val made = 1 + missingFolders(target, namespace)
        if (!room(namespace, size, made)) return false
        return try {
            Files.createDirectories(target.parent)
            Files.copy(source, target)
            usage(namespace).apply {
                bytes += size
                entries += made
            }
            true
        } catch (_: IOException) {
            usages.remove(namespace)
            false
        }
    }

    /** Both ends of a move or copy, resolved: something at the first, nothing at the second, neither the root. */
    private fun pair(from: String, to: String): Pair<Path, Path>? {
        if (normalize(from).isNullOrEmpty() || normalize(to).isNullOrEmpty()) return null
        val source = resolve(from)?.takeIf { it.exists(LinkOption.NOFOLLOW_LINKS) } ?: return null
        val target = resolve(to)?.takeIf { !it.exists(LinkOption.NOFOLLOW_LINKS) } ?: return null
        return source to target
    }

    /** Removes a file, or a directory with nothing in it. Never the root. */
    fun delete(path: String): Boolean {
        if (normalize(path).isNullOrEmpty()) return false
        val target = resolve(path) ?: return false
        val namespace = namespaceOf(normalize(path)!!)
        val size = if (target.isRegularFile()) target.fileSize() else 0L
        return try {
            Files.deleteIfExists(target).also { deleted ->
                if (deleted) {
                    usage(namespace).apply {
                        bytes -= size
                        entries -= 1
                    }
                }
            }
        } catch (_: IOException) {
            usages.remove(namespace)
            false
        }
    }

    private fun sizeOf(path: Path): Long = if (path.isDirectory()) {
        Files.walk(path).use { paths ->
            paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.mapToLong { Files.size(it) }.sum()
        }
    } else {
        path.fileSize()
    }

    private fun entriesOf(path: Path): Int = if (path.isDirectory()) Files.walk(path).use { it.count().toInt() } else 1

    companion object {
        const val MAX_BYTES = 1L shl 20
        const val MAX_NAME = 64
        const val MAX_PATH = 200
        const val MAX_DEPTH = 8
        const val MAX_LIST = 500

        /** In [root]: the files are in a folder per package ([adopt] has run). Not a name a script can use. */
        const val LAYOUT_MARKER = ".netherforge-files"

        private val NAME = Regex("^[A-Za-z0-9_. -]+$")
        private val RESERVED = setOf("con", "prn", "aux", "nul") + (1..9).flatMap { listOf("com$it", "lpt$it") }
    }
}
