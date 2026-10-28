package dev.netherforge.format

import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.project.FormatVersion
import dev.netherforge.format.project.MapProjectSource
import dev.netherforge.format.project.OpenedPackage
import dev.netherforge.format.project.PackageHash
import dev.netherforge.format.project.PackageLookup
import dev.netherforge.format.project.PackageMissing
import dev.netherforge.format.project.PackageRequest
import dev.netherforge.format.project.PackageSources
import dev.netherforge.format.project.Packages
import dev.netherforge.format.project.ProjectManifest
import dev.netherforge.format.project.ProjectSnapshot
import dev.netherforge.format.project.Projects
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * The repo's files, for golden tests. The Gradle build passes the repo root in
 * `NETHERFORGE_REPO`; `UPDATE_GOLDEN=1` makes golden tests rewrite their
 * expectations instead of comparing against them.
 */
expect object TestFiles {
    fun read(relative: String): String?
    fun write(relative: String, text: String)

    /** Every file under [relative], as paths relative to it with `/` separators. */
    fun list(relative: String): List<String>

    /** Immediate subdirectory names of [relative]. */
    fun dirs(relative: String): List<String>

    /** The hex SHA-256 of the file [relative]'s bytes, or of [text]'s UTF-8: what a host gives `PackageHash`. */
    fun sha256(relative: String): String

    fun sha256OfText(text: String): String

    val updateGolden: Boolean
}

/**
 * Packages for a test project in `<base>/<root>` (`examples` and `basic`):
 * a dependency's path is relative to its requirer's folder there, and each
 * package is hashed as a host would. Git repositories are `<base>/repos.json`:
 * `{ url: { "refs": { rev: commit }, "commits": { commit: folder } } }`, a
 * folder relative to `<base>` (`HEAD` is the default branch's rev).
 */
class TestPackages(private val base: String, private val root: String) : PackageSources {
    @Serializable
    private class Repo(val refs: Map<String, String> = emptyMap(), val commits: Map<String, String> = emptyMap())

    private val repos = TestFiles.read("$base/repos.json")?.let {
        CanonicalJson.json.decodeFromString(MapSerializer(String.serializer(), Repo.serializer()), it)
    }.orEmpty()

    override fun open(namespace: String, request: PackageRequest): PackageLookup = when (request) {
        is PackageRequest.Folder -> folder("$base/${Packages.join(root, request.location)}", null)
        is PackageRequest.Git -> {
            val repo = repos[request.url]
            val commit = request.commit ?: repo?.refs?.get(request.rev ?: "HEAD")
            val folder = commit?.let { repo?.commits?.get(it) }
            when {
                repo == null -> PackageMissing("there's no repository at ${request.url}")
                commit == null || folder == null -> PackageMissing("${request.url} has no ${request.commit ?: request.rev ?: "HEAD"}")
                else -> folder("$base/$folder", commit)
            }
        }
    }

    private fun folder(dir: String, commit: String?): PackageLookup {
        val files = TestFiles.list(dir)
        if (ProjectManifest.FILE_NAME !in files) return PackageMissing()
        val source = MapProjectSource(files.associateWith { TestFiles.read("$dir/$it") })
        val listing = PackageHash.listing(files.associateWith { TestFiles.sha256("$dir/$it") })
        return OpenedPackage(source, PackageHash.of(TestFiles.sha256OfText(listing)), commit)
    }
}

/** The project in `<base>/<root>` and the packages it depends on. */
fun loadTestProject(base: String, root: String): ProjectSnapshot {
    val files = TestFiles.list("$base/$root")
    return Projects.load(MapProjectSource(files.associateWith { TestFiles.read("$base/$root/$it") }), packages = TestPackages(base, root))
}

/** A `netherforge.json` for a test project in namespace `test`, with [more] keys added (`, "managedWorlds": ["lobby"]`). */
fun testManifest(more: String = ""): String =
    """{ "formatVersion": ${FormatVersion.CURRENT}, "name": "Test", "namespace": "test", "version": "1.0.0", "minecraft": "26.3"$more }"""
