package dev.netherforge.plugin.project

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.zip.ZipInputStream

/**
 * A bundle run by reference, not by folder: `project: https://example.com/arena-1.4.0.nfbundle#sha256=<hex>` in
 * `config.yml` (what `netherforge publish` prints). Downloaded once, checked against its SHA-256, unpacked into
 * `bundles/<sha256>/` in the plugin's folder, and from then on read from there; the bundle's own checks (its packages'
 * hashes, its signature when one is required) are then as for any bundle. The reference names the bytes, so a changed
 * file at the URL is refused rather than run.
 */
object BundleFetch {
    /** Whether [project] (the `project` setting) names a bundle by URL. */
    fun isReference(project: String) = project.startsWith("https://") || project.startsWith("http://")

    /** The folder of the bundle [reference] names, in [cache]: fetched and unpacked the first time. Throws saying why not. */
    fun folder(reference: String, cache: Path, log: (String) -> Unit = {}): Path {
        val url = reference.substringBefore('#')
        val expected = reference.substringAfter("#sha256=", "").lowercase()
        if (!Regex("[0-9a-f]{64}").matches(expected)) {
            throw IllegalArgumentException(
                "the project $reference names no #sha256=<the archive's SHA-256> (netherforge publish prints it)"
            )
        }
        val folder = cache.resolve(expected)
        if (Files.isRegularFile(folder.resolve(DONE))) return folder
        log("NetherForge: fetching the project's bundle from $url")
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build()
        val response = client.send(
            HttpRequest.newBuilder(URI(url)).timeout(Duration.ofMinutes(5)).GET().build(),
            HttpResponse.BodyHandlers.ofInputStream()
        )
        if (response.statusCode() != 200) throw IOException("$url answered ${response.statusCode()}")
        Files.createDirectories(cache)
        val archive = Files.createTempFile(cache, "fetch", ".nfbundle")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            response.body().use { input ->
                Files.newOutputStream(archive).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_BYTES) throw IOException("$url is over ${MAX_BYTES / 1024 / 1024} MB")
                        digest.update(buffer, 0, read)
                        out.write(buffer, 0, read)
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != expected) throw IOException("$url isn't the bundle the project names: its SHA-256 is $actual, not $expected")
            unpack(archive, folder)
            log("NetherForge: unpacked the project's bundle into $folder")
            return folder
        } finally {
            Files.deleteIfExists(archive)
        }
    }

    /** Unpacks [archive] into [folder] whole, or not at all: into a folder beside it, then moved into place. */
    internal fun unpack(archive: Path, folder: Path) {
        val partial = Files.createTempDirectory(folder.parent, "unpack")
        try {
            ZipInputStream(Files.newInputStream(archive)).use { zip ->
                var files = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val target = partial.resolve(entry.name).normalize()
                    // Nothing outside the folder, whatever the archive says.
                    if (!target.startsWith(partial) ||
                        entry.name.contains('\\')
                    ) {
                        throw IOException("the bundle holds a path outside it: ${entry.name}")
                    }
                    if (++files > MAX_FILES) throw IOException("the bundle holds more than $MAX_FILES files")
                    if (entry.isDirectory) {
                        Files.createDirectories(target)
                    } else {
                        Files.createDirectories(target.parent)
                        Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
            Files.writeString(partial.resolve(DONE), "")
            if (Files.exists(folder)) folder.toFile().deleteRecursively()
            Files.move(partial, folder, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            partial.toFile().deleteRecursively()
            throw e
        }
    }

    /** What marks a folder unpacked whole. */
    private const val DONE = ".netherforge-fetched"
    private const val MAX_BYTES = 1024L * 1024 * 1024
    private const val MAX_FILES = 100_000
}
