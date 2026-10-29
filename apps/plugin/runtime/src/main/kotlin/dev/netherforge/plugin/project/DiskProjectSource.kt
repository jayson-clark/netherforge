package dev.netherforge.plugin.project

import dev.netherforge.format.project.ImageInfo
import dev.netherforge.format.project.ProjectSource
import dev.netherforge.format.resourcepack.PackFonts
import dev.netherforge.format.wasm.WasmInfo
import dev.netherforge.format.wasm.WasmReader
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * A project read straight off the disk: the plugin has no build step, it
 * reads the folder the editor (or `git pull`) keeps up to date.
 *
 * Paths are project-relative with `/` on every OS. Folders NetherForge never
 * reads into are skipped while walking rather than filtered afterwards, so a
 * project's `.git` history or the editor's `.netherforge/` output costs nothing.
 */
class DiskProjectSource(val root: Path) : ProjectSource {
    private val absoluteRoot = root.toAbsolutePath().normalize()

    /** Every file, or none when the folder doesn't exist (the project is then refused for having no manifest). */
    override fun files(): Collection<String> {
        if (!Files.isDirectory(absoluteRoot)) return emptyList()
        val found = mutableListOf<String>()
        Files.walkFileTree(
            absoluteRoot,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val skipped = dir != absoluteRoot && dir.fileName.toString() in SKIPPED
                    return if (skipped) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) found += absoluteRoot.relativize(file).joinToString("/")
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            }
        )
        return found.sorted()
    }

    override fun read(path: String): String? {
        val file = resolve(path) ?: return null
        return try {
            if (file.isRegularFile()) file.readText() else null
        } catch (_: IOException) {
            null
        }
    }

    override val readsImages: Boolean get() = true

    override val readsWasm: Boolean get() = true

    /** What a WebAssembly module says about itself, by format's reader; null when it's missing. */
    override fun wasm(path: String): WasmInfo? = readBytes(path)?.let(WasmReader::read)

    /**
     * A PNG's size and opaque width, decoded with ImageIO: what a skin's title
     * prefix needs to move back by; and for a picture too wide for a font sheet,
     * which columns are opaque, which its pieces' advances are measured from.
     * Null when it's missing or won't decode.
     */
    override fun image(path: String): ImageInfo? {
        val bytes = readBytes(path) ?: return null
        val image = Pictures.decode(bytes) ?: return null
        if (image.width <= 0 || image.height <= 0) return null
        var opaque = 0
        val hasAlpha = image.colorModel.hasAlpha()
        if (!hasAlpha) {
            opaque = image.width
        } else {
            column@ for (x in image.width - 1 downTo 0) {
                for (y in 0 until image.height) {
                    if (image.getRGB(x, y) ushr 24 != 0) {
                        opaque = x + 1
                        break@column
                    }
                }
            }
        }
        val columns = if (image.width <= PackFonts.SHEET) {
            null
        } else {
            List(image.width) { x -> !hasAlpha || (0 until image.height).any { y -> image.getRGB(x, y) ushr 24 != 0 } }
        }
        return ImageInfo(image.width, image.height, opaque, columns)
    }

    /** A file's bytes (a texture), or null if it doesn't exist. */
    fun readBytes(path: String): ByteArray? {
        val file = resolve(path) ?: return null
        return try {
            if (file.isRegularFile()) Files.readAllBytes(file) else null
        } catch (_: IOException) {
            null
        }
    }

    /** The file at a project path, or null for a path that would leave the project. */
    fun resolve(path: String): Path? {
        val file = absoluteRoot.resolve(path.replace('\\', '/')).normalize()
        return file.takeIf { it.startsWith(absoluteRoot) }
    }

    companion object {
        /** Never project content: VCS metadata, what the editor generates, package managers' caches. */
        val SKIPPED = setOf(".git", ".netherforge", "node_modules")
    }
}
